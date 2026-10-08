package com.gtnhplanner.importer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.importer.FfDrawers.Role;
import com.gtnhplanner.importer.FfDrawers.Target;
import com.gtnhplanner.importer.FfPlan.FfEdge;
import com.gtnhplanner.importer.FfPlan.FfNode;
import com.gtnhplanner.importer.FfPlan.FfRecipe;
import com.gtnhplanner.importer.FfPlan.FfSection;
import com.gtnhplanner.importer.FfPlan.FfSlot;
import com.gtnhplanner.importer.FfPlan.FfStorage;
import com.gtnhplanner.importer.FfPlan.FfTarget;
import com.gtnhplanner.importer.ImportReport.Kind;
import com.gtnhplanner.importer.NodeMaker.PortInfo;
import com.gtnhplanner.importer.RecipeIndex.Lookup;

/**
 * Turns an {@link FfPlan} into a GTNH Planner {@link Graph}, reporting whatever does not carry over as it was.
 *
 * <ul>
 * <li>Cards become nodes, each FF recipe matched to an in-game one ({@link RecipeMatcher}); a card with no match is
 * left out. A shared machine stays one: its recipes become the sections of one shared card.</li>
 * <li>Wires become edges, landing on the port that holds the wire's resource (FF names ports by resource, never by
 * slot index). A buffer drawer (fed and drawn from) has no GTNH Planner counterpart: each feeder is wired straight to
 * each
 * taker.</li>
 * <li>Drawers keep the role their wires give them (FF's storage roles); their rate rules come over only from Solve
 * and Pool plans, the only plans where FF enforces them. Custom rate cards become drawers, hatch-supplied fluids
 * source drawers.</li>
 * <li>Pool plans ignore saved wires in FF, so every maker of a resource is wired to every user of it.</li>
 * <li>Positions keep FF's layout, scaled to GTNH Planner's cards; an Arrange afterwards tidies it.</li>
 * </ul>
 * Power cards and disabled cards are left out.
 */
public final class FfConverter {

    /** FF's cards are 380 px wide, GTNH Planner's 320. */
    static final double SCALE_X = 320.0 / 380.0;
    /**
     * GTNH Planner's port rows are half FF's height; drawers and the gaps between cards are not, so a bit over half.
     */
    static final double SCALE_Y = 0.6;
    static final int MARGIN = 40;
    /** Where a hatch's source drawer sits, left of its card, in GTNH Planner pixels. */
    static final int HATCH_DRAWER_DX = 180;

    public record Result(Graph graph, ImportReport report) {}

    private FfConverter() {}

    public static Result convert(final FfPlan plan, final RecipeIndex index, final NodeMaker maker) {
        return new Run(plan, index, maker).run();
    }

    /** One recipe of a card, on the board. */
    private record Placed(Node node, FfNode card, FfRecipe recipe, Map<Integer, FfSlot> overrides, int section,
        String name) {}

    /** A custom rate card, waiting to become a drawer. */
    private record CustomRate(FfNode card, FfSlot slot, boolean supply) {}

    /** A drawer to be: what it is, and the ports it takes. */
    private static final class PendingDrawer {

        final Drawer.Kind kind;
        final String subject;
        @Nullable
        final String label;
        final int x, y;
        final Target target;
        @Nullable
        final String converted;
        final List<Drawer.Link> links = new ArrayList<>();
        final List<PortInfo> infos = new ArrayList<>();

        PendingDrawer(final Drawer.Kind kind, final String subject, @Nullable final String label, final int x,
            final int y, final Target target, @Nullable final String converted) {
            this.kind = kind;
            this.subject = subject;
            this.label = label;
            this.x = x;
            this.y = y;
            this.target = target;
            this.converted = converted;
        }

        void link(final Node node, final int port, final PortInfo info) {
            final Drawer.Link link = new Drawer.Link(node.id, port);
            if (links.contains(link)) return;
            links.add(link);
            infos.add(info);
        }
    }

    private static final class Run {

        final FfPlan plan;
        final RecipeIndex index;
        final NodeMaker maker;
        final ImportReport report = new ImportReport();
        final Graph graph;
        final Map<String, FfRecipe> recipes;
        final Map<String, FfStorage> storages = new LinkedHashMap<>();
        final Map<String, Role> roles;
        /** Card id, then section, to what was placed. */
        final Map<String, Map<Integer, Placed>> placed = new LinkedHashMap<>();
        final Map<String, CustomRate> customRates = new LinkedHashMap<>();
        /** Every card of the plan: a wire to one that was left out goes quietly, counted. */
        final Set<String> cardIds = new HashSet<>();
        /** Drawers to be, by FF storage id, custom rate card id, or "hatch:<card>:<fluid>". */
        final Map<String, PendingDrawer> pending = new LinkedHashMap<>();
        final Map<UUID, List<PortInfo>> inPorts = new HashMap<>(), outPorts = new HashMap<>();
        final double minX, minY;
        int lostWires;

        Run(final FfPlan plan, final RecipeIndex index, final NodeMaker maker) {
            this.plan = plan;
            this.index = index;
            this.maker = maker;
            this.graph = new Graph(plan.name());
            this.recipes = plan.recipesById();
            for (final FfStorage s : plan.storages()) storages.put(s.id(), s);
            for (final FfNode n : plan.nodes()) cardIds.add(n.id());
            this.roles = FfDrawers.roles(plan);
            double x = Double.POSITIVE_INFINITY, y = Double.POSITIVE_INFINITY;
            for (final FfNode n : plan.nodes()) {
                x = Math.min(x, n.x());
                y = Math.min(y, n.y());
            }
            for (final FfStorage s : plan.storages()) {
                x = Math.min(x, s.x());
                y = Math.min(y, s.y());
            }
            minX = Double.isFinite(x) ? x : 0;
            minY = Double.isFinite(y) ? y : 0;
        }

        Result run() {
            for (final FfNode card : plan.nodes()) placeCard(card);
            if (plan.poolMode()) wirePool();
            else for (final FfEdge e : expandBuffers()) wire(e);
            supplyHatches();
            planTarget();
            makeDrawers();
            reportUnusedStorages();
            if (lostWires > 0) report.add(
                Kind.WIRE,
                "Plan",
                lostWires + " wire" + (lostWires == 1 ? "" : "s") + " to cards that were left out dropped");
            if (!plan.solveMode()) report.add(
                Kind.NOTE,
                "Plan",
                "A Build plan: machine counts came over as starting counts, not pins, and drawer rates as no rule"
                    + " (FF does not enforce them in Build either). Set a drawer's rate to scale the plan.");
            for (final String note : plan.notes()) report.add(Kind.NOTE, "Plan", note);
            report.setCounts(graph.nodes.size(), graph.edges.size(), graph.drawers.size());
            return new Result(graph, report);
        }

        // region Cards

        void placeCard(final FfNode card) {
            final FfRecipe recipe = recipes.get(card.recipeId());
            if (recipe == null) {
                report.add(Kind.UNMATCHED, card.recipeId(), "the plan does not carry this card's recipe");
                return;
            }
            if (recipe.isCustomRate()) {
                customRate(card, recipe);
                return;
            }
            if (!card.enabled()) {
                report.add(Kind.DROPPED, recipe.name(), "a switched-off card; left out");
                return;
            }
            if (recipe.power()) {
                placePower(card, recipe);
                return;
            }
            final List<FfSection> sections = new ArrayList<>();
            sections.add(new FfSection(card.recipeId(), card.overrides()));
            sections.addAll(card.extraRecipes());
            final List<Node> made = new ArrayList<>();
            for (int i = 0; i < sections.size(); i++) {
                final FfRecipe r = recipes.get(
                    sections.get(i)
                        .recipeId());
                final String name = r == null ? card.recipeId() : r.name();
                if (r == null) {
                    report.add(Kind.UNMATCHED, name, "the plan does not carry this recipe");
                    continue;
                }
                final Node node = placeSection(
                    card,
                    r,
                    sections.get(i)
                        .overrides(),
                    i,
                    name);
                if (node != null) made.add(node);
            }
            if (made.size() > 1) share(made);
        }

        /**
         * A shared machine's recipes on one machine again: one card, its recipes as sections at its place, the
         * card's pin (set on the first) the machine's count, which its recipes' machines then add up to.
         */
        void share(final List<Node> made) {
            final com.gtnhplanner.data.flowchart.MachineGroup g = new com.gtnhplanner.data.flowchart.MachineGroup();
            g.setHeader("Shared machine");
            final Node host = made.getFirst();
            if (host.isMachineCountFixed()) {
                g.setMachineCapacity(host.machineConfig.getMachineCount());
                g.setPinned(true);
            }
            for (final Node n : made) {
                g.addSection(n.id);
                n.setMachineCountFixed(false);
                n.x = host.x;
                n.y = host.y;
            }
            graph.groups.put(g.getId(), g);
        }

        Node placeSection(final FfNode card, final FfRecipe recipe, final Map<Integer, FfSlot> overrides,
            final int section, final String name) {
            final Lookup lookup = index.find(recipe);
            final RecipeMatcher.Match match = RecipeMatcher.match(recipe, overrides, lookup.candidates());
            if (!match.accepted()) {
                String why = match.detail();
                if (why.isEmpty()) why = lookup.note() != null ? lookup.note() : "no recipe like it in game";
                report.add(Kind.UNMATCHED, name, why);
                return null;
            }
            final RecipeIndex.GameRecipe game = match.recipe();
            if (match.grade() == RecipeMatcher.Grade.SAME_RESOURCES)
                report.add(Kind.FUZZY, name, "the in-game recipe differs: " + match.detail());
            if (recipe.isPlannerTank())
                report.add(Kind.CONVERTED, name, "Factory Flow's free, instant Tank is a Canner recipe in game");

            final long eut = game.euPerTick() != null ? game.euPerTick() : Math.round(recipe.eut());
            final FfSettings.Mapped mapped = FfSettings.map(card, recipe, eut, plan.solveMode(), section == 0);
            final Node node = maker.make(game, mapped.machineLabel());
            if (node == null) {
                report.add(Kind.UNMATCHED, name, "the game could not build a card for it");
                return null;
            }
            node.x = px(card.x());
            node.y = py(card.y());
            maker.applySettings(node, mapped.settings());
            node.machineConfig.setMachineCount(mapped.machines());
            node.setMachineCountFixed(mapped.pinned());
            for (final String note : mapped.notes()) report.add(Kind.CONVERTED, name, note);
            graph.addNode(node);
            final Placed p = new Placed(node, card, recipe, overrides, section, name);
            placed.computeIfAbsent(card.id(), k -> new HashMap<>())
                .put(section, p);

            final FfTarget target = section == 0 ? card.targetOutput() : null;
            if (target != null) {
                final int out = findPort(node, true, target.kind(), List.of(target.resourceId()));
                if (out >= 0) node.targetOutputRates.put(out, target.perSecond());
                else report
                    .add(Kind.WIRE, name, "its target rate names " + target.resourceId() + ", which it does not make");
            }
            return node;
        }

        /**
         * A power card: the same generator at the same settings, which rebuilds its ports from the source as the site
         * does on load. Its pin comes over as a pin in a Solve plan, else its count as a starting count.
         */
        void placePower(final FfNode card, final FfRecipe recipe) {
            final Node node = maker.makePower(recipe.powerSource(), card.machineConfigTiers());
            if (node == null) {
                report.add(Kind.UNMATCHED, recipe.name(), "a generator GTNH Planner does not know");
                return;
            }
            node.x = px(card.x());
            node.y = py(card.y());
            final Double pin = card.solvePin();
            if (plan.solveMode() && pin != null && pin > 0) {
                node.machineConfig.setMachineCount((int) Math.ceil(pin - 1e-9));
                node.setMachineCountFixed(true);
            } else node.machineConfig.setMachineCount((int) Math.max(1, Math.ceil(card.machineCount() - 1e-9)));
            graph.addNode(node);
            placed.computeIfAbsent(card.id(), k -> new HashMap<>())
                .put(0, new Placed(node, card, recipe, Map.of(), 0, recipe.name()));
        }

        void customRate(final FfNode card, final FfRecipe recipe) {
            final String name = "Custom rate card";
            if (!card.enabled()) {
                report.add(Kind.DROPPED, name, "a switched-off card; left out");
                return;
            }
            final boolean supply = !recipe.outputs()
                .isEmpty();
            final FfSlot slot = supply ? recipe.outputs()
                .getFirst()
                : recipe.inputs()
                    .isEmpty() ? null
                        : recipe.inputs()
                            .getFirst();
            if (slot == null) {
                report.add(Kind.DROPPED, name, "it holds no resource; left out");
                return;
            }
            customRates.put(card.id(), new CustomRate(card, slot, supply));
        }

        // endregion

        // region Wires

        /**
         * The plan's wires with every buffer drawer taken out: each wire into a buffer joined to each wire out of
         * it, one buffer at a time, so a chain of buffers still joins its ends.
         */
        List<FfEdge> expandBuffers() {
            final List<FfEdge> all = new ArrayList<>(plan.edges());
            for (final Map.Entry<String, Role> r : roles.entrySet()) {
                if (r.getValue() != Role.BUFFER) continue;
                final String b = r.getKey();
                final List<FfEdge> ins = new ArrayList<>(), outs = new ArrayList<>();
                for (final FfEdge e : all) {
                    if (e.target()
                        .equals(b)
                        && !e.source()
                            .equals(b))
                        ins.add(e);
                    if (e.source()
                        .equals(b)
                        && !e.target()
                            .equals(b))
                        outs.add(e);
                }
                all.removeIf(
                    e -> e.source()
                        .equals(b)
                        || e.target()
                            .equals(b));
                for (final FfEdge in : ins) for (final FfEdge out : outs) all.add(
                    new FfEdge(
                        in.id() + ">" + out.id(),
                        in.source(),
                        out.target(),
                        in.sourceHandle(),
                        out.targetHandle(),
                        in.resourceKind(),
                        in.resourceId(),
                        in.crossForm() || out.crossForm()));
                final FfStorage s = storages.get(b);
                report.add(
                    Kind.CONVERTED,
                    s == null ? b : storageName(s),
                    "a buffer drawer (GTNH Planner has none), replaced by " + ins.size() * outs.size()
                        + " direct wire"
                        + (ins.size() * outs.size() == 1 ? "" : "s")
                        + " from its "
                        + ins.size()
                        + " feeder"
                        + (ins.size() == 1 ? "" : "s")
                        + " to its "
                        + outs.size()
                        + " taker"
                        + (outs.size() == 1 ? "" : "s"));
            }
            return all;
        }

        /** What a wire end is on the board: a placed card section, or a drawer to be. */
        private record End(@Nullable Placed card, @Nullable String drawer) {}

        @Nullable
        End end(final String id, @Nullable final String handle) {
            final Map<Integer, Placed> sections = placed.get(id);
            if (sections != null) {
                final Placed p = sections.get(FfHandle.sectionOf(handle));
                return p == null ? null : new End(p, null);
            }
            if (storages.containsKey(id) || customRates.containsKey(id)) return new End(null, id);
            return null;
        }

        void wire(final FfEdge e) {
            final End from = end(e.source(), e.sourceHandle()), to = end(e.target(), e.targetHandle());
            if (from == null || to == null) {
                if (from == null && !known(e.source()) || to == null && !known(e.target()))
                    report.add(Kind.WIRE, e.resourceId(), "a wire to something not on the plan dropped");
                else lostWires++;
                return;
            }
            if (e.crossForm()) {
                report.add(
                    Kind.WIRE,
                    e.resourceId(),
                    "a loose cell wire (cell into a fluid slot) dropped; GTNH Planner needs a Canner card there");
                return;
            }
            if (from.card() != null && to.card() != null) {
                final int out = port(from.card(), true, e.sourceHandle(), e, null);
                final int in = port(to.card(), false, e.targetHandle(), e, null);
                if (out < 0 || in < 0) {
                    report.add(
                        Kind.WIRE,
                        e.resourceId(),
                        "wire from " + from.card()
                            .name()
                            + " to "
                            + to.card()
                                .name()
                            + ": no "
                            + (out < 0 ? "output" : "input")
                            + " for it on "
                            + (out < 0 ? from.card() : to.card()).name());
                    return;
                }
                graph.addEdge(
                    new Edge(
                        UUID.randomUUID(),
                        from.card()
                            .node().id,
                        to.card()
                            .node().id,
                        out,
                        in));
                return;
            }
            if (from.drawer() != null && to.drawer() != null) {
                report.add(Kind.WIRE, e.resourceId(), "a wire between two drawers dropped");
                return;
            }
            final boolean drawerFeeds = from.drawer() != null;
            final String drawerId = drawerFeeds ? from.drawer() : to.drawer();
            final Placed card = drawerFeeds ? to.card() : from.card();
            final PendingDrawer d = pendingFor(drawerId);
            if (d == null || d.kind.linksInputs() != drawerFeeds) {
                report.add(Kind.WIRE, e.resourceId(), "a drawer wired the wrong way round dropped");
                return;
            }
            final int port = port(
                card,
                !drawerFeeds,
                drawerFeeds ? e.targetHandle() : e.sourceHandle(),
                e,
                resourceOf(drawerId));
            if (port < 0) {
                report.add(Kind.WIRE, d.subject, "no port for it on " + card.name());
                return;
            }
            d.link(card.node(), port, describe(card.node(), !drawerFeeds).get(port));
        }

        boolean known(final String id) {
            return cardIds.contains(id) || storages.containsKey(id);
        }

        @Nullable
        String resourceOf(final String drawerId) {
            final FfStorage s = storages.get(drawerId);
            if (s != null) return s.resourceId();
            final CustomRate c = customRates.get(drawerId);
            return c == null ? null
                : c.slot()
                    .id();
        }

        /**
         * The port on a card that a wire end names, found by resource: the handle's, the wire's, and for an input
         * every id of the recipe slot it lands on (alternatives, the card's pick), so an ore dictionary slot fed a
         * concrete item still finds its port.
         */
        int port(final Placed p, final boolean output, @Nullable final String handle, final FfEdge e,
            @Nullable final String extra) {
            final FfHandle h = FfHandle.parse(handle);
            final String kind = h != null ? h.kind() : e.resourceKind();
            final List<String> want = new ArrayList<>();
            if (h != null) want.add(h.resourceId());
            want.add(e.resourceId());
            if (extra != null) want.add(extra);
            if (!output) {
                final List<FfSlot> inputs = p.recipe()
                    .inputs();
                for (int i = 0; i < inputs.size(); i++) {
                    final List<String> ids = new ArrayList<>(
                        inputs.get(i)
                            .ids());
                    final FfSlot pick = p.overrides()
                        .get(i);
                    if (pick != null) ids.add(pick.id());
                    if (FfIds.anySame(ids, want)) {
                        want.addAll(ids);
                        break;
                    }
                }
            }
            return findPort(p.node(), output, kind, want);
        }

        int findPort(final Node node, final boolean output, final String kind, final List<String> ids) {
            final List<PortInfo> infos = describe(node, output);
            for (int i = 0; i < infos.size(); i++) {
                final PortInfo info = infos.get(i);
                if (info.kind()
                    .equals(kind) && FfIds.anySame(info.ids(), ids)) return i;
            }
            return -1;
        }

        List<PortInfo> describe(final Node node, final boolean output) {
            return (output ? outPorts : inPorts).computeIfAbsent(node.id, k -> {
                final List<PortInfo> infos = new ArrayList<>();
                for (final Port<?> port : output ? node.outputs : node.inputs) infos.add(maker.describe(port));
                return infos;
            });
        }

        /** Pool plans: every maker of a resource wired to every user of it, drawers to every port of theirs. */
        void wirePool() {
            final Map<String, List<int[]>> makers = new LinkedHashMap<>(), users = new LinkedHashMap<>();
            final List<Placed> all = new ArrayList<>();
            for (final Map<Integer, Placed> sections : placed.values()) all.addAll(sections.values());
            for (int n = 0; n < all.size(); n++) {
                final Node node = all.get(n)
                    .node();
                final List<PortInfo> outs = describe(node, true), ins = describe(node, false);
                for (int i = 0; i < outs.size(); i++) makers.computeIfAbsent(
                    outs.get(i)
                        .key(),
                    k -> new ArrayList<>())
                    .add(new int[] { n, i });
                for (int i = 0; i < ins.size(); i++) users.computeIfAbsent(
                    ins.get(i)
                        .key(),
                    k -> new ArrayList<>())
                    .add(new int[] { n, i });
            }
            for (final Map.Entry<String, List<int[]>> m : makers.entrySet()) {
                if (m.getKey()
                    .isEmpty()) continue;
                final List<int[]> takers = users.get(m.getKey());
                if (takers == null) continue;
                for (final int[] from : m.getValue()) for (final int[] to : takers) {
                    if (from[0] == to[0]) continue;
                    graph.addEdge(
                        new Edge(
                            UUID.randomUUID(),
                            all.get(from[0])
                                .node().id,
                            all.get(to[0])
                                .node().id,
                            from[1],
                            to[1]));
                }
            }
            report.add(
                Kind.CONVERTED,
                "Plan",
                "a Pool plan: every card making a resource is wired to every card using it");
            final Set<String> drawerIds = new LinkedHashSet<>(storages.keySet());
            drawerIds.addAll(customRates.keySet());
            for (final String id : drawerIds) {
                final PendingDrawer d = pendingFor(id);
                final String resource = resourceOf(id);
                if (d == null || resource == null) continue;
                for (final Placed p : all) {
                    final List<PortInfo> infos = describe(p.node(), !d.kind.linksInputs());
                    for (int i = 0; i < infos.size(); i++) if (FfIds.anySame(
                        infos.get(i)
                            .ids(),
                        List.of(resource))) d.link(p.node(), i, infos.get(i));
                }
            }
        }

        // endregion

        // region Drawers

        /** The drawer to be for a storage or custom rate card; null for a buffer or an unwired drawer. */
        @Nullable
        PendingDrawer pendingFor(final String id) {
            final PendingDrawer had = pending.get(id);
            if (had != null) return had;
            final FfStorage s = storages.get(id);
            if (s != null) {
                final Role role = roles.getOrDefault(id, Role.IDLE);
                final Drawer.Kind kind = FfDrawers.kindOf(role);
                if (kind == null) return null;
                final PendingDrawer d = new PendingDrawer(
                    kind,
                    storageName(s),
                    s.displayName(),
                    px(s.x()),
                    py(s.y()),
                    FfDrawers.target(s, role, plan.solveMode()),
                    null);
                pending.put(id, d);
                return d;
            }
            final CustomRate c = customRates.get(id);
            if (c == null) return null;
            final FfNode card = c.card();
            final double perSecond = card.customRatePerSecond() != null ? card.customRatePerSecond()
                : c.slot()
                    .amount();
            // A custom rate card is a machine making (or eating) perSecond each: Build fixes how many, Solve only a
            // pin does; unpinned in Solve the plan decides, so no rule.
            final Double count = plan.solveMode() ? card.solvePin() : Double.valueOf(card.machineCount());
            final Target target;
            if (count == null || count <= 0) target = Target.NONE;
            else target = new Target(
                c.supply() && !plan.solveMode() ? Drawer.Rule.AT_MOST : Drawer.Rule.EXACTLY,
                perSecond * count);
            final String rule = target.rule() == Drawer.Rule.ANY ? "no rule"
                : (target.rule() == Drawer.Rule.AT_MOST ? "at most " : "exactly ") + fmt(target.rate()) + "/s";
            final PendingDrawer d = new PendingDrawer(
                c.supply() ? Drawer.Kind.SOURCE : Drawer.Kind.PRODUCT,
                "Custom rate card",
                c.slot()
                    .displayName(),
                px(card.x()),
                py(card.y()),
                target,
                "turned into a " + (c.supply() ? "source" : "product")
                    + " drawer for "
                    + c.slot()
                        .displayName()
                    + ", "
                    + rule);
            pending.put(id, d);
            return d;
        }

        /** Fluids a card's hatch supplies (FF's hatchSupplies) come from a source drawer beside it. */
        void supplyHatches() {
            for (final Map<Integer, Placed> sections : placed.values()) for (final Placed p : sections.values()) {
                for (final String fluid : p.card()
                    .hatchSupplies()) {
                    final int in = findPort(p.node(), false, "fluid", List.of(fluid));
                    if (in < 0 || fed(p.node(), in)) continue;
                    final String key = "hatch:" + p.card()
                        .id() + ":" + fluid;
                    PendingDrawer d = pending.get(key);
                    if (d == null) {
                        final FfRecipe own = recipes.get(
                            p.card()
                                .recipeId());
                        d = new PendingDrawer(
                            Drawer.Kind.SOURCE,
                            own != null ? own.name() : p.name(),
                            null,
                            p.node().x - HATCH_DRAWER_DX,
                            p.node().y,
                            Target.NONE,
                            "its " + fluid + " hatch is a source drawer");
                        pending.put(key, d);
                    }
                    d.link(p.node(), in, describe(p.node(), false).get(in));
                }
            }
        }

        boolean fed(final Node node, final int input) {
            for (final Edge e : graph.getEdges())
                if (e.targetNodeId.equals(node.id) && e.targetInputIndex == input) return true;
            final Drawer.Link link = new Drawer.Link(node.id, input);
            for (final PendingDrawer d : pending.values())
                if (d.kind.linksInputs() && d.links.contains(link)) return true;
            return false;
        }

        /** The plan's legacy target rate (FF's project.targetRate) lands on the first card making it. */
        void planTarget() {
            final FfTarget t = plan.targetRate();
            if (t == null) return;
            for (final FfNode card : plan.nodes()) {
                final FfTarget own = card.targetOutput();
                if (own != null && FfIds.same(own.resourceId(), t.resourceId())) return;
            }
            for (final Map<Integer, Placed> sections : placed.values()) for (final Placed p : sections.values()) {
                final int out = findPort(p.node(), true, t.kind(), List.of(t.resourceId()));
                if (out < 0 || p.node().targetOutputRates.containsKey(out)) continue;
                p.node().targetOutputRates.put(out, t.perSecond());
                report.add(Kind.CONVERTED, p.name(), "the plan's target rate of " + fmt(t.perSecond()) + "/s set here");
                return;
            }
            report.add(
                Kind.DROPPED,
                "Plan",
                "its target rate names " + t.resourceId() + ", which no imported card makes");
        }

        void makeDrawers() {
            for (final PendingDrawer d : pending.values()) {
                if (d.links.isEmpty()) {
                    report.add(Kind.DROPPED, d.subject, "a drawer with no wires left in GTNH Planner; left out");
                    continue;
                }
                final PortInfo first = d.infos.getFirst();
                final Drawer drawer = new Drawer(d.kind, first.key());
                drawer.setLabel(d.label != null && !d.label.isEmpty() ? d.label : first.label());
                drawer.setX(d.x);
                drawer.setY(d.y);
                drawer.setTarget(d.target.rule(), d.target.rate());
                graph.addDrawer(drawer);
                for (final Drawer.Link link : d.links) {
                    final Drawer holder = graph.drawerAt(link.nodeId(), link.portIndex(), d.kind.linksInputs());
                    if (holder != null && holder != drawer) report.add(
                        Kind.WIRE,
                        d.subject,
                        "a port it shares with drawer " + holder.getLabel()
                            + " went to this one (a port has one drawer)");
                    graph.linkDrawer(drawer.getId(), link);
                }
                if (d.converted != null) report.add(Kind.CONVERTED, d.subject, d.converted);
            }
        }

        void reportUnusedStorages() {
            for (final FfStorage s : plan.storages()) {
                final Role role = roles.getOrDefault(s.id(), Role.IDLE);
                if (role == Role.IDLE) report.add(Kind.DROPPED, storageName(s), "an unwired drawer; left out");
                else if (role != Role.BUFFER && !pending.containsKey(s.id()))
                    report.add(Kind.DROPPED, storageName(s), "its wires all went to cards that were left out");
            }
        }

        // endregion

        int px(final double ffX) {
            return snap((ffX - minX) * SCALE_X) + MARGIN;
        }

        int py(final double ffY) {
            return snap((ffY - minY) * SCALE_Y) + MARGIN;
        }
    }

    private static int snap(final double v) {
        return (int) Math.round(v / 10) * 10;
    }

    private static String storageName(final FfStorage s) {
        return s.displayName() != null && !s.displayName()
            .isEmpty() ? s.displayName() : s.resourceId();
    }

    private static String fmt(final double d) {
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return String.valueOf(Math.round(d * 1000) / 1000.0);
    }
}
