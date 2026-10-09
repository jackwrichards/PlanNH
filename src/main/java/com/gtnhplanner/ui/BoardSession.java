package com.gtnhplanner.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.api.PlanAPI;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.flowchart.balancer.BalanceMode;
import com.gtnhplanner.data.flowchart.balancer.BalanceResult;
import com.gtnhplanner.data.flowchart.balancer.DrawerReadout;
import com.gtnhplanner.data.flowchart.balancer.Note;
import com.gtnhplanner.data.flowchart.balancer.Severity;
import com.gtnhplanner.data.flowchart.balancer.SolveInput;
import com.gtnhplanner.data.flowchart.balancer.SolveService;
import com.gtnhplanner.data.flowchart.balancer.SolverMessage;
import com.gtnhplanner.nei.NodeLookupContext;
import com.gtnhplanner.ui.card.CardDefaults;
import com.gtnhplanner.ui.card.CardLayout;
import com.gtnhplanner.ui.card.CardModel;
import com.gtnhplanner.ui.card.MachineChoices;
import com.gtnhplanner.ui.drawer.DrawerCard;
import com.gtnhplanner.ui.drawer.DrawerModel;
import com.gtnhplanner.ui.sound.Sfx;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;

import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.RecipeHandlerRef;

/**
 * The open board: which plan slot is shown, its latest solve, and the card models built from it. Every edit goes
 * through {@link #edit} so it is undoable, saved, and re-solved. Lives as long as the screen.
 */
public final class BoardSession {

    private static BoardSession current;

    private Graph graph;
    private long seenVersion = Long.MIN_VALUE;
    private BalanceResult result;
    private Map<UUID, CardModel> models = Collections.emptyMap();
    private Map<UUID, DrawerModel> drawerModels = Collections.emptyMap();
    private List<Notice> notices = List.of();
    private boolean nothingToSolveFor;
    /** Bumped when the set of cards changes, so the canvas knows to rebuild its widgets. */
    private int structure;
    /** The sticky notes the widgets were last built for. */
    private Set<UUID> noteIds = Set.of();
    private Fmt.RateUnit rateUnit = Fmt.RateUnit.SECOND;
    private NodeLookupContext pendingLookup;
    private SolveService solver = new SolveService();
    /** Set when the board closed; NEI's pages close it too, and closing back to it reopens it. */
    private boolean closed;
    private SolveService.Result lastResult;

    BoardSession() {
        graph = Plan.getActiveGraph();
        current = this;
    }

    /** The session of the open board, or null when the planner is closed. */
    public static BoardSession current() {
        return current;
    }

    void close() {
        closed = true;
        solver.close();
        PlanAPI.save();
        if (current == this) current = null;
    }

    /**
     * The board is showing again after a close it came back from: an NEI page opened over it (clicking a port, adding a
     * recipe) closes the board's screen and closes back to the same one. The old solver stopped with the close, so a
     * fresh one, or every edit after the visit would wait on a solve that never comes.
     */
    void reopen() {
        if (!closed) return;
        closed = false;
        solver = new SolveService();
        current = this;
    }

    public Graph graph() {
        return graph;
    }

    public Map<UUID, CardModel> models() {
        return models;
    }

    public CardModel model(final UUID nodeId) {
        return models.get(nodeId);
    }

    public Map<UUID, DrawerModel> drawerModels() {
        return drawerModels;
    }

    public DrawerModel drawerModel(final UUID drawerId) {
        return drawerModels.get(drawerId);
    }

    public BalanceResult result() {
        return result;
    }

    public int structure() {
        return structure;
    }

    private HoverScope hover = HoverScope.NONE;
    private String railHoverKey;
    private HoverScope railHover = HoverScope.NONE;

    /**
     * What glows for what the mouse is on: on the board, a port, drawer or wire and what it is wired to; in the
     * overview, a resource everywhere it flows (that wins while set).
     */
    public HoverScope lit() {
        return railHoverKey != null ? railHover : hover;
    }

    /** The resource under the mouse in the overview rail; it wins over the board's while set. */
    public void setRailHoverKey(final String key) {
        final String next = key == null || key.isEmpty() ? null : key;
        if (java.util.Objects.equals(next, railHoverKey)) return;
        railHoverKey = next;
        railHover = HoverScope.resource(next);
    }

    public void setHover(final HoverScope scope) {
        hover = scope == null ? HoverScope.NONE : scope;
    }

    public Fmt.RateUnit rateUnit() {
        return rateUnit;
    }

    public void setRateUnit(final Fmt.RateUnit unit) {
        rateUnit = unit;
    }

    /**
     * One undoable edit: records the before state, runs the change, marks the plan changed and saves. The next tick
     * re-solves and rebuilds the cards. Every custom rate card the change left with nothing wired lets go of its
     * resource, in the same step.
     */
    @SuppressWarnings("deprecation")
    public void edit(final Runnable change) {
        PlanAPI.recordEdit(graph, () -> {
            change.run();
            releaseCustomRates();
        });
        graph.touch();
        PlanAPI.save();
    }

    /** An undoable change that only moves things: saved, but no re-solve. */
    @SuppressWarnings("deprecation")
    public void editLayout(final Runnable change) {
        PlanAPI.recordEdit(graph, change);
        graph.touchLayout();
        PlanAPI.save();
    }

    /**
     * Remembers the port an NEI lookup (R/U) started from, so the recipe added from NEI's page next wires into it.
     */
    public void armLookup(final UUID nodeId, final boolean output, final int portIndex) {
        pendingLookup = new NodeLookupContext(nodeId, output, portIndex);
    }

    /** Forgets an armed lookup, when the page it was for never opened. */
    public void disarmLookup() {
        pendingLookup = null;
    }

    /** Set when the board comes back; its next tick forgets what is still armed. */
    private boolean disarmOnTick;

    /**
     * Forgets every armed lookup (a port's, or a card's "Add another recipe") on the tick after the board is back. NEI
     * shows the board again and then hands it the recipe picked with "+", in the same click, so that add still finds
     * its lookup; anything armed after it was for a page closed without one.
     */
    public void disarmPending() {
        disarmOnTick = true;
    }

    /** The card added from NEI last, once, for the board to bring into view. */
    private UUID justAdded;
    /** Whether that card is to be centred and selected (the plan button's), not just brought into view (+'s). */
    private boolean focusAdded;

    public UUID takeJustAdded() {
        final UUID id = justAdded;
        justAdded = null;
        return id;
    }

    public boolean takeFocusAdded() {
        final boolean focus = focusAdded;
        focusAdded = false;
        return focus;
    }

    /**
     * Puts an NEI recipe on the board, on the machine last picked for its tab (else NEI's first). When it came from a
     * lookup on a port, it lands beside that card and only that resource is wired; otherwise it lands beside what it
     * wires to.
     */
    public Node addRecipe(final IRecipeHandler handler, final int recipeIndex) {
        return add(MachineChoices.newNode(handler, recipeIndex, null), false, true);
    }

    /**
     * As above, for a card already set up (the plan button's, on the machine picked in its menu), and then centred in
     * view and selected: the board was just opened on it from NEI.
     */
    public Node addAndFocus(final Node node) {
        return add(node, true, true);
    }

    /**
     * Puts a card on the board. Only a card from NEI ({@code fromNei}: its + or the plan button) takes what was armed
     * there: the port it was looked up from (it lands beside it, wired to it alone) or the card it joins. Anything else
     * (a non-recipe machine, a custom rate card) never wires itself.
     */
    private Node add(final Node node, final boolean focus, final boolean fromNei) {
        focusAdded = focus;
        final NodeLookupContext origin = fromNei ? pendingLookup : null;
        pendingLookup = null;
        final UUID joinTo = fromNei ? addSectionTo : null;
        addSectionTo = null;
        justAdded = joinTo != null && graph.nodes.containsKey(joinTo) ? joinTo : node.id;
        final boolean[] joined = { false };
        edit(() -> {
            graph.addNode(node);
            // "Add another recipe" on a card: it joins that machine.
            if (joinTo != null && joinArmedCard(node, joinTo)) {
                joined[0] = true;
                return;
            }
            final Node from = origin == null ? null : graph.nodes.get(origin.nodeId());
            if (from != null && wireToOrigin(node, from, origin)) {
                node.x = origin.output() ? from.x + CardLayout.W + NewCards.GAP : from.x - CardLayout.W - NewCards.GAP;
                node.y = from.y;
                for (int tries = 0; tries < 50 && NewCards.overlapsAnything(graph, node); tries++) node.y += 40;
            } else NewCards.place(graph, node);
        });
        (joined[0] ? Sfx.MERGE : Sfx.PLACE).play();
        return node;
    }

    // region Wires and drawers (each one undoable, saved, re-solved)

    /** Wires an output to an input; drawing a wire that already exists removes it instead. */
    public void connect(final UUID from, final int output, final UUID to, final int input) {
        final boolean[] cut = { false };
        edit(() -> {
            for (final Edge e : new ArrayList<>(graph.getEdges())) {
                if (e.sourceNodeId.equals(from) && e.sourceOutputIndex == output
                    && e.targetNodeId.equals(to)
                    && e.targetInputIndex == input) {
                    graph.removeEdge(e.id);
                    cut[0] = true;
                    return;
                }
            }
            graph.addEdge(new Edge(UUID.randomUUID(), from, to, output, input));
        });
        final String key = outputKey(from, output);
        if (cut[0]) Sfx.cut(key);
        else Sfx.connect(key);
    }

    public void deleteEdge(final UUID edgeId) {
        String key = null;
        for (final Edge e : graph.getEdges())
            if (e.id.equals(edgeId)) key = outputKey(e.sourceNodeId, e.sourceOutputIndex);
        edit(() -> graph.removeEdge(edgeId));
        Sfx.cut(key);
    }

    /** The resource an output carries, for its wire's sound; null when there is no such port. */
    private String outputKey(final UUID nodeId, final int output) {
        final Node n = graph.nodes.get(nodeId);
        return n == null || output < 0 || output >= n.outputs.size() ? null : Resources.key(n.outputs.get(output));
    }

    /**
     * A port dragged onto another card: an output wires into the first input there that takes it, an input takes the
     * first output there that fits. False when nothing on that card matches.
     */
    public boolean dropPortOnCard(final UUID fromNode, final boolean output, final int port, final UUID toNode) {
        final Node to = graph.nodes.get(toNode);
        if (com.gtnhplanner.power.CustomRate.is(to)) return dropPortOnCustomRate(fromNode, output, port, to);
        // A shared machine's recipes in turn: the first with a matching port takes the wire, as on the website.
        for (final UUID section : sectionsOf(toNode)) {
            if (!section.equals(fromNode) && dropPortOnRecipe(fromNode, output, port, section)) return true;
        }
        return false;
    }

    private boolean dropPortOnRecipe(final UUID fromNode, final boolean output, final int port, final UUID toNode) {
        final Node src = graph.nodes.get(fromNode), dst = graph.nodes.get(toNode);
        if (src == null || dst == null || src == dst) return false;
        if (output) {
            final int in = graph.findCompatibleInput(src, port, dst);
            if (in < 0) return false;
            connect(src.id, port, dst.id, in);
            return true;
        }
        final Port<?> want = src.inputs.get(port);
        for (int out = 0; out < dst.outputs.size(); out++) {
            if (!dst.outputs.get(out)
                .canConnect(want)) continue;
            connect(dst.id, out, src.id, port);
            return true;
        }
        return false;
    }

    /**
     * A port dropped on a custom rate card: the card takes the port's resource and is wired to it, supplying an input
     * or
     * draining an output.
     */
    private boolean dropPortOnCustomRate(final UUID fromNode, final boolean output, final int port, final Node card) {
        final Node src = graph.nodes.get(fromNode);
        if (src == null || src == card || com.gtnhplanner.power.CustomRate.is(src)) return false;
        final List<Port<?>> ports = output ? src.outputs : src.inputs;
        if (port < 0 || port >= ports.size()) return false;
        final String key = Resources.key(ports.get(port));
        if (key.isEmpty()) return false;
        holdAndWire(card, key, !output, src.id, port);
        return true;
    }

    /**
     * Sets a custom rate card to a resource on a side and wires it to a port of another card ({@code supply}: the card
     * feeds that input; else it drains that output). A different resource or side first drops the card's old wires.
     * One undo step.
     */
    public void holdAndWire(final Node card, final String key, final boolean supply, final UUID other,
        final int otherPort) {
        edit(() -> {
            if (!key.equals(com.gtnhplanner.power.CustomRate.resource(card))
                || supply != com.gtnhplanner.power.CustomRate.supply(card)) {
                unwire(card.id);
                com.gtnhplanner.power.CustomRate.hold(card, key, supply);
                card.refresh();
            }
            // A resource this game lacks makes no port: nothing to wire.
            if (card.inputs.isEmpty() && card.outputs.isEmpty()) return;
            final UUID from = supply ? card.id : other, to = supply ? other : card.id;
            final int out = supply ? 0 : otherPort, in = supply ? otherPort : 0;
            for (final Edge e : graph.getEdges()) if (e.sourceNodeId.equals(from) && e.sourceOutputIndex == out
                && e.targetNodeId.equals(to)
                && e.targetInputIndex == in) return;
            graph.addEdge(new Edge(UUID.randomUUID(), from, to, out, in));
        });
        Sfx.connect(key);
    }

    /**
     * Sets a custom rate card to a drawer's resource, on the side the drawer links (a source drawer's resource is
     * drained, a product drawer is supplied), and links them. One undo step.
     */
    public void holdAndLink(final Node card, final Drawer drawer) {
        final boolean supply = !drawer.getKind()
            .linksInputs();
        final String key = drawer.getResourceKey();
        if (key == null || key.isEmpty()) return;
        edit(() -> {
            if (!key.equals(com.gtnhplanner.power.CustomRate.resource(card))
                || supply != com.gtnhplanner.power.CustomRate.supply(card)) {
                unwire(card.id);
                com.gtnhplanner.power.CustomRate.hold(card, key, supply);
                card.refresh();
            }
            if (card.inputs.isEmpty() && card.outputs.isEmpty()) return;
            graph.linkDrawer(drawer.getId(), new Drawer.Link(card.id, 0));
        });
        Sfx.connect(key);
    }

    /** Every wire and drawer link on a card, gone. */
    private void unwire(final UUID nodeId) {
        for (final Edge e : new ArrayList<>(graph.getEdges()))
            if (e.sourceNodeId.equals(nodeId) || e.targetNodeId.equals(nodeId)) graph.removeEdge(e.id);
        for (final Drawer d : graph.getDrawers()) d.removeLinksTo(nodeId);
    }

    /**
     * Custom rate cards holding a resource with nothing wired to them let go of it (the website's releaseCustomRates).
     */
    private void releaseCustomRates() {
        for (final Node n : graph.nodes.values()) {
            if (!com.gtnhplanner.power.CustomRate.is(n) || com.gtnhplanner.power.CustomRate.resource(n) == null
                || wired(n.id)) continue;
            com.gtnhplanner.power.CustomRate.release(n);
            n.refresh();
        }
    }

    /** Whether anything is wired to a card: a wire either way, or a drawer. */
    private boolean wired(final UUID nodeId) {
        for (final Edge e : graph.getEdges())
            if (e.sourceNodeId.equals(nodeId) || e.targetNodeId.equals(nodeId)) return true;
        for (final Drawer d : graph.getDrawers()) for (final Drawer.Link link : d.getLinks()) if (link.nodeId()
            .equals(nodeId)) return true;
        return false;
    }

    /** A port dragged onto a drawer: linked when the drawer holds the same resource on the same side. */
    public boolean dropPortOnDrawer(final UUID fromNode, final boolean output, final int port, final Drawer drawer) {
        final Node node = graph.nodes.get(fromNode);
        if (node == null || drawer.getKind()
            .linksInputs() == output) return false;
        final String key = Resources.key((output ? node.outputs : node.inputs).get(port));
        if (!key.equals(drawer.getResourceKey())) return false;
        final boolean linked = drawer.isLinked(node.id, port);
        edit(() -> {
            if (linked) graph.unlinkDrawer(drawer.getId(), new Drawer.Link(node.id, port));
            else graph.linkDrawer(drawer.getId(), new Drawer.Link(node.id, port));
        });
        if (linked) Sfx.cut(key);
        else Sfx.connect(key);
        return true;
    }

    /**
     * A port dragged onto empty board: a new drawer there, linked to it. An input makes a source (it sits to the left
     * of the drop point), an output a product (to the right).
     */
       /** A port on a recipe: its node, which side, and its index there. */
    public record PortRef(UUID node, boolean output, int index) {}

    /**
     * The port a port dropped on a card would be wired to, as {@link #dropPortOnCard} picks it: the first recipe on the
     * card with a port that takes it. Null when none does, and for a custom rate card (its own socket takes it).
     */
    @Nullable
    public PortRef dropTarget(final UUID fromNode, final boolean output, final int port, final UUID toNode) {
        final Node to = graph.nodes.get(toNode), src = graph.nodes.get(fromNode);
        if (to == null || src == null || com.gtnhplanner.power.CustomRate.is(to)) return null;
        for (final UUID section : sectionsOf(toNode)) {
            final Node dst = graph.nodes.get(section);
            if (dst == null || dst == src) continue;
            if (output) {
                final int in = graph.findCompatibleInput(src, port, dst);
                if (in >= 0) return new PortRef(section, false, in);
                continue;
            }
            if (port < 0 || port >= src.inputs.size()) return null;
            final Port<?> want = src.inputs.get(port);
            for (int out = 0; out < dst.outputs.size(); out++) if (dst.outputs.get(out)
                .canConnect(want)) return new PortRef(section, true, out);
        }
        return null;
    }

    /** Whether a port already has a drawer on it: one per port, as on the website. */
    public boolean portHasDrawer(final UUID node, final boolean output, final int port) {
        for (final Drawer d : graph.getDrawers()) {
            if (d.getKind()
                .linksInputs() == output) continue;
            for (final Drawer.Link l : d.getLinks()) if (l.nodeId()
                .equals(node) && l.portIndex() == port) return true;
        }
        return false;
    }

    /**
     * A port dropped on empty board: a drawer there for it, wired to it (a product for an output, a source for an
     * input). Refused, false, when the port has a drawer already.
     */
    public boolean dropPortOnBoard(final UUID fromNode, final boolean output, final int port, final int worldX,
        final int worldY) {
        final Node node = graph.nodes.get(fromNode);
        if (node == null || portHasDrawer(fromNode, output, port)) return false;
        final Port<?> p = (output ? node.outputs : node.inputs).get(port);
        final Drawer drawer = new Drawer(output ? Drawer.Kind.PRODUCT : Drawer.Kind.SOURCE, Resources.key(p));
        drawer.setLabel(p.getDisplayName());
        drawer.setX(output ? worldX : worldX - DrawerCard.W);
        // Dropped nearly level with the port: make it level, so the wire runs straight instead of jogging.
        final CardModel card = models.get(fromNode);
        final int portY = card == null ? worldY : node.y + new CardLayout(card, graph()).anchorY(output, port);
        drawer.setY((Math.abs(worldY - portY) <= 24 ? portY : worldY) - DrawerCard.ANCHOR_Y);
        edit(() -> {
            graph.addDrawer(drawer);
            graph.linkDrawer(drawer.getId(), new Drawer.Link(node.id, port));
        });
        Sfx.connect(drawer.getResourceKey());
        return true;
    }

    /**
     * A drawer for a resource from the overview: a source left of the first card that uses it, or a product right of
     * the first card that makes it, linked to every port waiting for it.
     */
    public void addDrawerFor(final String key, final String label, final boolean source) {
        int x = 40, y = 40;
        search: for (final CardModel card : models.values()) {
            final List<CardModel.PortView> ports = source ? card.inputs : card.outputs;
            for (int i = 0; i < ports.size(); i++) {
                if (!ports.get(i)
                    .key()
                    .equals(key)) continue;
                x = source ? card.node.x - DrawerCard.W - 60 : card.node.x + CardLayout.W + 60;
                // Its wire runs straight: the drawer's anchor level with the port's.
                y = card.node.y + new CardLayout(card, graph()).anchorY(!source, i) - DrawerCard.ANCHOR_Y;
                break search;
            }
        }
        // In line with, and below, any drawers already there.
        for (final Drawer d : graph.getDrawers()) if (Math.abs(d.getX() - x) <= 40) {
            x = d.getX();
            break;
        }
        addDrawer(source ? Drawer.Kind.SOURCE : Drawer.Kind.PRODUCT, key, label, x, y);
    }

    /** A new, unlinked drawer for a resource at a world point (from an NEI drag). */
    public void addDrawer(final Drawer.Kind kind, final String resourceKey, final String label, final int x,
        final int y) {
        final Drawer drawer = new Drawer(kind, resourceKey);
        drawer.setLabel(label);
        drawer.setX(x);
        // Never under or over a card or another drawer: step down until it is clear.
        int clear = y;
        for (int tries = 0; tries < 100
            && NewCards.overlapsAnything(graph, x, clear, DrawerCard.W, DrawerCard.H, null); tries++) clear += 20;
        drawer.setY(clear);
        edit(() -> {
            graph.addDrawer(drawer);
            // Take the ports already waiting for this resource.
            for (final Node n : graph.getNodes()) {
                final List<Port<?>> ports = kind.linksInputs() ? n.inputs : n.outputs;
                for (int i = 0; i < ports.size(); i++) {
                    if (!resourceKey.equals(Resources.key(ports.get(i))) || isWired(n, !kind.linksInputs(), i))
                        continue;
                    graph.linkDrawer(drawer.getId(), new Drawer.Link(n.id, i));
                }
            }
        });
        Sfx.connect(resourceKey);
    }

    private boolean isWired(final Node node, final boolean output, final int port) {
        for (final Edge e : graph.getEdges()) {
            if (output && e.sourceNodeId.equals(node.id) && e.sourceOutputIndex == port) return true;
            if (!output && e.targetNodeId.equals(node.id) && e.targetInputIndex == port) return true;
        }
        return graph.drawerAt(node.id, port, !output) != null;
    }

    /** A drawer for a wire's resource beside it, taking the wire's surplus (product) or topping it up (source). */
    public void addDrawerOnEdge(final Edge edge, final Drawer.Kind kind, final int worldX, final int worldY) {
        final Node src = graph.nodes.get(edge.sourceNodeId), dst = graph.nodes.get(edge.targetNodeId);
        if (src == null || dst == null) return;
        final boolean source = kind == Drawer.Kind.SOURCE;
        final Port<?> p = source ? dst.inputs.get(edge.targetInputIndex) : src.outputs.get(edge.sourceOutputIndex);
        final Drawer drawer = new Drawer(kind, Resources.key(p));
        drawer.setLabel(p.getDisplayName());
        drawer.setX(worldX - DrawerCard.W / 2);
        drawer.setY(worldY + 12);
        edit(() -> {
            graph.addDrawer(drawer);
            graph.linkDrawer(
                drawer.getId(),
                source ? new Drawer.Link(dst.id, edge.targetInputIndex)
                    : new Drawer.Link(src.id, edge.sourceOutputIndex));
        });
        Sfx.connect(drawer.getResourceKey());
    }

    public void unlinkDrawer(final Drawer drawer, final Drawer.Link link) {
        edit(() -> graph.unlinkDrawer(drawer.getId(), link));
        Sfx.cut(drawer.getResourceKey());
    }

    public void deleteDrawer(final Drawer drawer) {
        edit(() -> graph.removeDrawer(drawer.getId()));
        Sfx.REMOVE.play();
    }

    /** Product, byproduct, trash, product again; a source stays a source. */
    public void cycleDrawer(final Drawer drawer) {
        edit(
            () -> drawer.setKind(
                drawer.getKind()
                    .next()));
        Sfx.ADJUST.playStep(
            drawer.getKind()
                .ordinal(),
            0.9f,
            1.12f);
    }

    /** Rates being wheeled, per drawer (per second), shown at once and set once the wheel is still; null for none. */
    private Map<UUID, Double> wheeledRates;
    private long rateWheelAt;

    /**
     * A drawer rate from the wheel: shown at once, set (one edit, one solve) once the wheel has been still a moment.
     */
    public void wheelDrawerRate(final Drawer drawer, final double perSecond) {
        if (wheeledRates == null) wheeledRates = new HashMap<>();
        final Double was = wheeledRates.get(drawer.getId());
        Sfx.TICK.play(perSecond >= (was == null ? drawer.getRate() : was) ? 1.12f : 0.9f);
        wheeledRates.put(drawer.getId(), perSecond);
        rateWheelAt = System.currentTimeMillis();
    }

    /** The rate a drawer is being wheeled to, per second; null when it is not. */
    public Double wheeledRate(final UUID drawerId) {
        return wheeledRates == null ? null : wheeledRates.get(drawerId);
    }

    private void commitWheeledRates() {
        if (wheeledRates == null || wheeledRates.isEmpty() || System.currentTimeMillis() - rateWheelAt < 500) return;
        final Map<UUID, Double> rates = new HashMap<>(wheeledRates);
        wheeledRates.clear();
        rates.forEach((id, rate) -> {
            final Drawer d = graph.getDrawer(id);
            if (d != null) setDrawerRate(d, rate);
        });
    }

    public void setDrawerRule(final Drawer drawer, final Drawer.Rule rule) {
        edit(() -> drawer.setRule(rule));
        Sfx.ADJUST.playStep(rule.ordinal(), 0.9f, 1.12f);
    }

    /**
     * Sets the drawer's rate (per second). Typing a rate on "Any" picks the rule the player almost always means:
     * exactly for a source, at least for a product. Zero clears the rule.
     */
    public void setDrawerRate(final Drawer drawer, final double perSecond) {
        edit(() -> {
            if (!(perSecond > 0)) {
                drawer.setTarget(Drawer.Rule.ANY, 0);
                return;
            }
            Drawer.Rule rule = drawer.getRule();
            if (rule == Drawer.Rule.ANY)
                rule = drawer.getKind() == Drawer.Kind.SOURCE ? Drawer.Rule.EXACTLY : Drawer.Rule.AT_LEAST;
            drawer.setTarget(rule, perSecond);
        });
        // A rate is a target the plan solves for: pinned, or let go.
        (perSecond > 0 ? Sfx.PIN : Sfx.UNPIN).play();
    }

    // endregion

    private boolean wireToOrigin(final Node added, final Node origin, final NodeLookupContext lookup) {
        final List<Port<?>> originPorts = lookup.output() ? origin.outputs : origin.inputs;
        final int idx = lookup.portIndex();
        if (idx < 0 || idx >= originPorts.size()) return false;
        if (lookup.output()) {
            final int in = graph.findCompatibleInput(origin, idx, added);
            if (in < 0) return false;
            graph.addEdge(new Edge(UUID.randomUUID(), origin.id, added.id, idx, in));
            return true;
        }
        final Port<?> want = originPorts.get(idx);
        for (int out = 0; out < added.outputs.size(); out++) {
            if (!added.outputs.get(out)
                .canConnect(want)) continue;
            graph.addEdge(new Edge(UUID.randomUUID(), added.id, origin.id, out, idx));
            return true;
        }
        return false;
    }

    // region Copy and paste

    /** A copied card: its recipe and settings, and where it sat from the copy's top-left. */
    private record ClipNode(codechicken.nei.recipe.Recipe.RecipeId recipe, Map<String, Object> settings,
        String machineName, boolean fixed, int dx, int dy, String powerSource, Map<String, String> powerSettings) {}

    /** A copied drawer, and the copied cards' ports it was linked to, as {card index in the copy, port}. */
    private record ClipDrawer(Drawer.Kind kind, String key, String label, Drawer.Rule rule, double rate, int dx, int dy,
        List<int[]> links) {}

    /** A wire between two copied cards, by their index in the copy. */
    private record ClipEdge(int from, int output, int to, int input) {}

    /** A copied sticky note, and where it sat from the copy's top-left. */
    private record ClipNote(com.gtnhplanner.data.flowchart.Note note, int dx, int dy) {}

    private record Clip(List<ClipNode> nodes, List<ClipDrawer> drawers, List<ClipEdge> edges, List<ClipNote> notes) {}

    /** The last copy, shared by every plan, so a group copied from one plan pastes into another. */
    private static Clip clipboard;

    /** Copies the selected cards, drawers and notes with the wires among them; false when nothing is selected. */
    public boolean copySelection() {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE;
        final List<Node> nodes = new ArrayList<>();
        final List<Drawer> drawers = new ArrayList<>();
        final List<com.gtnhplanner.data.flowchart.Note> notes = new ArrayList<>();
        for (final UUID id : selection) {
            final Node n = graph.nodes.get(id);
            if (n != null) {
                nodes.add(n);
                x0 = Math.min(x0, n.x);
                y0 = Math.min(y0, n.y);
            }
            final Drawer d = graph.getDrawer(id);
            if (d != null) {
                drawers.add(d);
                x0 = Math.min(x0, d.getX());
                y0 = Math.min(y0, d.getY());
            }
            final com.gtnhplanner.data.flowchart.Note note = graph.notes.get(id);
            if (note != null) {
                notes.add(note);
                x0 = Math.min(x0, note.getX());
                y0 = Math.min(y0, note.getY());
            }
        }
        if (nodes.isEmpty() && drawers.isEmpty() && notes.isEmpty()) return false;
        final List<ClipNote> clipNotes = new ArrayList<>();
        for (final com.gtnhplanner.data.flowchart.Note note : notes)
            clipNotes.add(new ClipNote(note.copy(), note.getX() - x0, note.getY() - y0));
        final Map<UUID, Integer> index = new HashMap<>();
        final List<ClipNode> clipNodes = new ArrayList<>();
        for (final Node n : nodes) {
            index.put(n.id, clipNodes.size());
            clipNodes.add(
                new ClipNode(
                    n.recipeId,
                    new HashMap<>(n.machineConfig.settings),
                    n.machineName,
                    n.isMachineCountFixed(),
                    n.x - x0,
                    n.y - y0,
                    n.powerSource,
                    new LinkedHashMap<>(n.powerSettings)));
        }
        final List<ClipEdge> edges = new ArrayList<>();
        for (final Edge e : graph.getEdges()) {
            final Integer from = index.get(e.sourceNodeId), to = index.get(e.targetNodeId);
            if (from != null && to != null) edges.add(new ClipEdge(from, e.sourceOutputIndex, to, e.targetInputIndex));
        }
        final List<ClipDrawer> clipDrawers = new ArrayList<>();
        for (final Drawer d : drawers) {
            final List<int[]> links = new ArrayList<>();
            for (final Drawer.Link l : d.getLinks()) {
                final Integer at = index.get(l.nodeId());
                if (at != null) links.add(new int[] { at, l.portIndex() });
            }
            clipDrawers.add(
                new ClipDrawer(
                    d.getKind(),
                    d.getResourceKey(),
                    d.getLabel(),
                    d.getRule(),
                    d.getRate(),
                    d.getX() - x0,
                    d.getY() - y0,
                    links));
        }
        clipboard = new Clip(clipNodes, clipDrawers, edges, clipNotes);
        return true;
    }

    /**
     * Pastes the last copy with its top-left at a board point, as one undoable edit, and selects and returns what it
     * made. A card whose recipe NEI no longer knows is left out, with its wires.
     */
    public List<UUID> paste(final int worldX, final int worldY) {
        final Clip clip = clipboard;
        if (clip == null) return List.of();
        final List<Node> made = new ArrayList<>();
        for (final ClipNode c : clip.nodes()) {
            if (c.powerSource() != null) {
                final Node n = Node
                    .power(c.powerSource(), c.powerSettings(), snap(worldX + c.dx()), snap(worldY + c.dy()));
                n.setMachineCountFixed(c.fixed());
                made.add(n);
                continue;
            }
            final RecipeHandlerRef ref = RecipeHandlerRef.of(c.recipe());
            if (ref == null) {
                made.add(null);
                continue;
            }
            final Node n = new Node(ref.handler, ref.recipeIndex, snap(worldX + c.dx()), snap(worldY + c.dy()));
            n.machineConfig.settings.clear();
            n.machineConfig.settings.putAll(c.settings());
            n.machineName = c.machineName();
            n.setMachineCountFixed(c.fixed());
            made.add(n);
        }
        final List<Drawer> madeDrawers = new ArrayList<>();
        final List<UUID> madeNotes = new ArrayList<>();
        edit(() -> {
            for (final Node n : made) if (n != null) graph.addNode(n);
            for (final ClipEdge e : clip.edges()) {
                final Node from = made.get(e.from()), to = made.get(e.to());
                if (from != null && to != null)
                    graph.addEdge(new Edge(UUID.randomUUID(), from.id, to.id, e.output(), e.input()));
            }
            for (final ClipDrawer c : clip.drawers()) {
                final Drawer d = new Drawer(c.kind(), c.key());
                d.setLabel(c.label());
                d.setX(snap(worldX + c.dx()));
                d.setY(snap(worldY + c.dy()));
                d.setTarget(c.rule(), c.rate());
                graph.addDrawer(d);
                for (final int[] l : c.links()) {
                    final Node n = made.get(l[0]);
                    if (n != null) graph.linkDrawer(d.getId(), new Drawer.Link(n.id, l[1]));
                }
                madeDrawers.add(d);
            }
            for (final ClipNote c : clip.notes()) {
                final com.gtnhplanner.data.flowchart.Note note = c.note()
                    .copy();
                note.setX(snap(worldX + c.dx()));
                note.setY(snap(worldY + c.dy()));
                graph.notes.put(note.getId(), note);
                madeNotes.add(note.getId());
            }
        });
        selection.clear();
        for (final Node n : made) if (n != null) selection.add(n.id);
        for (final Drawer d : madeDrawers) selection.add(d.getId());
        selection.addAll(madeNotes);
        (selection.size() > 1 ? Sfx.SWEEP : Sfx.PLACE).play();
        return List.copyOf(selection);
    }

    // endregion

    // region Sticky notes

    /** Adds a sticky note with its top-left at a board point, as one undoable step, and selects it. */
    public com.gtnhplanner.data.flowchart.Note addNote(final int worldX, final int worldY) {
        final com.gtnhplanner.data.flowchart.Note note = new com.gtnhplanner.data.flowchart.Note();
        note.setX(snap(worldX));
        note.setY(snap(worldY));
        editLayout(() -> graph.notes.put(note.getId(), note));
        selection.clear();
        selection.add(note.getId());
        Sfx.NOTE_STICK.play();
        return note;
    }

    /** Deletes a sticky note, as one undoable step. */
    public void deleteNote(final UUID id) {
        selection.remove(id);
        if (!graph.notes.containsKey(id)) return;
        editLayout(() -> graph.notes.remove(id));
        Sfx.NOTE_CRUMPLE.play();
    }

    // endregion

    // region Selection

    /**
     * Where cards and drawers land when dragged: a 10 px grid, fine enough to feel smooth, coarse enough to line up.
     */
    public static int snap(final float v) {
        return Math.round(v / 10f) * 10;
    }

    private final Set<UUID> selection = new java.util.LinkedHashSet<>();

    /** Selects a card or drawer; {@code add} keeps the others (Shift), and toggles this one. */
    public void select(final UUID id, final boolean add) {
        Sfx.CLICK.play(0.6f, 1.15f);
        if (!add) {
            selection.clear();
            selection.add(id);
        } else if (!selection.remove(id)) selection.add(id);
    }

    public boolean isSelected(final UUID id) {
        return selection.contains(id);
    }

    /**
     * Whether a card, drawer or note draws its selection outline: only when it is one of several selected, where the
     * outline says what a move or a delete takes. One thing alone is just the thing clicked.
     */
    public boolean showsSelected(final UUID id) {
        return selection.size() > 1 && selection.contains(id);
    }

    public Set<UUID> selection() {
        return java.util.Collections.unmodifiableSet(selection);
    }

    public boolean hasSelection() {
        return !selection.isEmpty();
    }

    public void clearSelection() {
        selection.clear();
    }

    /** Every card, drawer and note on the board. */
    public void selectAll() {
        selection.clear();
        selection.addAll(graph.nodes.keySet());
        selection.addAll(graph.drawers.keySet());
        selection.addAll(graph.notes.keySet());
        Sfx.CLICK.play(0.6f, 1.15f);
    }

    /** Deletes every selected card, drawer and note as one undoable step. */
    public void deleteSelected() {
        if (selection.isEmpty()) return;
        // A shared machine goes whole: every recipe on its card.
        final List<UUID> ids = new ArrayList<>();
        for (final UUID id : selection) for (final UUID s : sectionsOf(id)) if (!ids.contains(s)) ids.add(s);
        selection.clear();
        final boolean notesOnly = ids.stream()
            .allMatch(graph.notes::containsKey);
        edit(() -> {
            for (final UUID id : ids) {
                if (graph.nodes.containsKey(id)) graph.removeNode(id);
                else if (graph.drawers.containsKey(id)) graph.removeDrawer(id);
                else graph.notes.remove(id);
            }
        });
        (notesOnly ? Sfx.NOTE_CRUMPLE : Sfx.REMOVE).play();
    }

    // endregion

    // region Undo, plan slots, board keys

    public boolean canUndo() {
        return PlanAPI.undoHistory(graph)
            .canUndo();
    }

    public boolean canRedo() {
        return PlanAPI.undoHistory(graph)
            .canRedo();
    }

    public void undo() {
        if (!canUndo()) {
            Sfx.DENY.play();
            return;
        }
        adopt(
            PlanAPI.undoHistory(graph)
                .undo(graph));
        Sfx.UNDO.play();
    }

    public void redo() {
        if (!canRedo()) {
            Sfx.DENY.play();
            return;
        }
        adopt(
            PlanAPI.undoHistory(graph)
                .redo(graph));
        Sfx.REDO.play();
    }

    /**
     * Puts a graph restored by undo or redo into the active slot. The view is not part of an edit, so it carries over;
     * the slot must take the graph or the next save writes the old one back.
     */
    private void adopt(final Graph restored) {
        restored.setZoom(graph.getZoom());
        restored.setPanX(graph.getPanX());
        restored.setPanY(graph.getPanY());
        final Plan plan = Plan.getInstance();
        plan.getGraphs()
            .set(plan.getActiveIndex(), restored);
        graph = restored;
        seenVersion = Long.MIN_VALUE;
        PlanAPI.save();
    }

    /** Opens the library in place of the board: set by the screen, used by the plan tabs' + menu. */
    private Runnable libraryOpener = () -> {};

    private Runnable powerPickerOpener = () -> {};

    public void setPowerPickerOpener(final Runnable opener) {
        powerPickerOpener = opener;
    }

    /** Opens the non-recipe machines over the board. */
    public void openPowerPicker() {
        powerPickerOpener.run();
    }

    public void setLibraryOpener(final Runnable opener) {
        libraryOpener = opener;
    }

    public void openLibrary() {
        libraryOpener.run();
    }

    /** Opens the library on My plans: every plan kept, open in a tab or not. */
    private Runnable myPlansOpener = () -> {};

    public void setMyPlansOpener(final Runnable opener) {
        myPlansOpener = opener;
    }

    public void openMyPlans() {
        myPlansOpener.run();
    }

    /** Opens the library on what makes a resource ({@code kind:id}, Factory Flow's key, and its name). */
    private java.util.function.BiConsumer<String, String> librarySearch = (key, label) -> {};

    public void setLibrarySearch(final java.util.function.BiConsumer<String, String> search) {
        librarySearch = search;
    }

    /** The library, showing the public setups that make a resource on the overview. */
    public void showSetupsMaking(final TotalLine line) {
        final String key = line.isFluid()
            ? "fluid:" + com.gtnhplanner.importer.game.FactoryFlowImport.idOf(line.fluid())
            : line.item() == null ? null : "item:" + com.gtnhplanner.importer.game.FactoryFlowImport.idOf(line.item());
        if (key != null) librarySearch.accept(key, line.label());
    }

    public List<Graph> slots() {
        return Plan.getInstance()
            .getGraphs();
    }

    public int activeSlot() {
        return Plan.getInstance()
            .getActiveIndex();
    }

    public void switchSlot(final int index) {
        final Plan plan = Plan.getInstance();
        if (index < 0 || index >= plan.getGraphs()
            .size() || index == plan.getActiveIndex()) return;
        plan.setActiveIndex(index);
        follow();
        PlanAPI.save();
        Sfx.PAGE.play();
    }

    /** Takes up the active plan when it changed (a tab, or the plan button on NEI's recipe page picking another). */
    private void follow() {
        final Graph active = Plan.getActiveGraph();
        if (active != graph) {
            graph = active;
            seenVersion = Long.MIN_VALUE;
        }
    }

    public void addSlot() {
        final Plan plan = Plan.getInstance();
        final int size = plan.getGraphs()
            .size();
        plan.getGraphs()
            .add(new Graph("Plan " + (size + 1)));
        plan.setActiveIndex(size);
        PlanAPI.save();
        Sfx.PAGE.play();
    }

    public void renameSlot(final int index, final String name) {
        if (index < 0 || index >= slots().size() || name == null || name.isBlank()) return;
        slots().get(index)
            .setName(name.trim());
        PlanAPI.save();
        Sfx.ADJUST.play();
    }

    /** Removes a plan slot; the last one stays. */
    public void deleteSlot(final int index) {
        final Plan plan = Plan.getInstance();
        if (index < 0 || index >= plan.getGraphs()
            .size()) return;
        final String name = plan.getGraphs()
            .get(index)
            .getName();
        plan.removeSlot(index);
        follow();
        PlanAPI.save();
        Sfx.NOTE_CRUMPLE.play();
        flash(Severity.INFO, "Deleted '" + name + "'");
    }

    /** Closes a plan's tab; the plan stays in the library's My plans. */
    public void closeSlot(final int index) {
        final Plan plan = Plan.getInstance();
        if (index < 0 || index >= plan.getGraphs()
            .size()) return;
        plan.closeSlot(index);
        follow();
        PlanAPI.save();
        Sfx.CLOSE.play();
    }

    /** Closes every tab but this one, which becomes the open plan; the others stay in My plans. */
    public void closeOtherSlots(final int index) {
        final Plan plan = Plan.getInstance();
        if (index < 0 || index >= plan.getGraphs()
            .size()) return;
        if (index != plan.getActiveIndex()) switchSlot(index);
        for (final int other : plan.openSlots()) if (other != index) plan.closeSlot(other);
        follow();
        PlanAPI.save();
        Sfx.CLOSE.play();
    }

    /** Asks before deleting a plan for good: it cannot be undone. */
    public void confirmDelete(final int index, final com.cleanroommc.modularui.screen.ModularPanel panel, final int x,
        final int y) {
        if (index < 0 || index >= slots().size()) return;
        final String name = slots().get(index)
            .getName();
        final List<com.gtnhplanner.ui.popup.PickList.Entry> rows = List.of(
            new com.gtnhplanner.ui.popup.PickList.Entry(
                null,
                "Delete it",
                "",
                Hyb.RED_INK,
                false,
                () -> deleteSlot(index)),
            com.gtnhplanner.ui.popup.PickList.Entry.of("Keep it", () -> {}));
        com.gtnhplanner.ui.popup.Popup.open(
            panel,
            com.gtnhplanner.ui.popup.PickList
                .popup("gtnhplanner_delete", "Delete '" + name + "' for good?", rows, false, 150),
            x,
            y);
    }

    /** How the board shows power: EU/t, or amps at each card's tier. */
    public enum PowerKey {
        EU,
        AMPS
    }

    private PowerKey powerKey = PowerKey.EU;
    private boolean peakPower;

    public PowerKey powerKey() {
        return powerKey;
    }

    public void togglePowerKey() {
        powerKey = powerKey == PowerKey.EU ? PowerKey.AMPS : PowerKey.EU;
    }

    /** Peak counts every machine running at once (whole machines); average uses the solved fraction. */
    public boolean peakPower() {
        return peakPower;
    }

    public void togglePeakPower() {
        peakPower = !peakPower;
        // The totals rail sums power with it.
        seenVersion = Long.MIN_VALUE;
    }

    /** A card's power as the board's switches say: average or peak. */
    public double power(final CardModel card) {
        return card.euPerTick * (peakPower ? Math.ceil(card.machines - 1e-9) : card.machines);
    }

    // endregion

    // region Shared machines (Factory Flow's: one card, several recipes time-sharing one machine)

    /** Each section's shared machine, as of the last tick. */
    private Map<UUID, com.gtnhplanner.data.flowchart.MachineGroup> sharedBySection = Map.of();

    /** The settings that belong to the machine rather than the recipe: a shared card keeps them the same throughout. */
    private static final Set<String> RECIPE_SETTINGS = Set.of("duration_ticks", "recipe_heat", "machines");

    /** The shared machine a recipe is a section of, or null when its card is its own. */
    public com.gtnhplanner.data.flowchart.MachineGroup sharedOf(final UUID nodeId) {
        return sharedBySection.get(nodeId);
    }

    /** The recipes on the card a node is on, top first: its shared machine's sections, or just itself. */
    public List<UUID> sectionsOf(final UUID nodeId) {
        final com.gtnhplanner.data.flowchart.MachineGroup g = sharedBySection.get(nodeId);
        return g == null ? List.of(nodeId) : List.copyOf(g.getSections());
    }

    /** The node whose card a node is drawn on: the first section of its shared machine, or itself. */
    public UUID hostOf(final UUID nodeId) {
        final com.gtnhplanner.data.flowchart.MachineGroup g = sharedBySection.get(nodeId);
        return g == null ? nodeId
            : g.getSections()
                .get(0);
    }

    /** The cards a set of ids covers, each once, by host: what a selection of cards is. */
    public List<UUID> cardsIn(final java.util.Collection<UUID> ids) {
        final List<UUID> out = new ArrayList<>();
        for (final UUID id : ids) {
            if (!graph.nodes.containsKey(id)) continue;
            final UUID host = hostOf(id);
            if (!out.contains(host)) out.add(host);
        }
        return out;
    }

    /**
     * Rebuilds the section index, and keeps every shared machine in shape: a recipe that left the plan leaves its
     * machine, a machine left with one recipe is an ordinary card again, and every section sits where its card does
     * (wires find its ports from there).
     */
    private void indexSharedMachines() {
        final Map<UUID, com.gtnhplanner.data.flowchart.MachineGroup> index = new HashMap<>();
        for (final com.gtnhplanner.data.flowchart.Group group : new ArrayList<>(graph.getGroups())) {
            if (!(group instanceof final com.gtnhplanner.data.flowchart.MachineGroup g) || g.getSections()
                .isEmpty()) continue;
            for (final UUID id : new ArrayList<>(g.getSections())) if (!graph.nodes.containsKey(id)) g.removeSection(id);
            if (!g.isShared()) {
                graph.removeGroup(g.getId());
                continue;
            }
            final Node host = graph.nodes.get(
                g.getSections()
                    .get(0));
            for (final UUID id : g.getSections()) {
                final Node n = graph.nodes.get(id);
                n.x = host.x;
                n.y = host.y;
                index.put(id, g);
            }
        }
        sharedBySection = index;
    }

    /** The machines (by NEI catalyst) that run every one of these recipes, in the first one's order. */
    public List<ItemStack> commonMachines(final List<UUID> nodeIds) {
        List<ItemStack> common = null;
        for (final UUID id : nodeIds) {
            final CardModel m = models.get(id);
            if (m == null) return List.of();
            if (common == null) common = new ArrayList<>(m.catalysts);
            else common.removeIf(
                c -> m.catalysts.stream()
                    .noneMatch(o -> ItemStack.areItemStacksEqual(o, c)));
        }
        return common == null ? List.of() : common;
    }

    /** Every recipe on these cards, card by card, each card's sections in order. */
    private List<UUID> allSections(final List<UUID> cards) {
        final List<UUID> out = new ArrayList<>();
        for (final UUID card : cards) for (final UUID s : sectionsOf(card)) if (!out.contains(s)) out.add(s);
        return out;
    }

    /** Whether these ids are two or more recipe cards that one machine can run all of. */
    public boolean canCombine(final java.util.Collection<UUID> ids) {
        for (final UUID id : ids) if (!graph.nodes.containsKey(id)) return false;
        final List<UUID> cards = cardsIn(ids);
        return cards.size() >= 2 && !commonMachines(allSections(cards)).isEmpty();
    }

    /**
     * Puts these cards' recipes on one machine, Factory Flow's "Combine N into one machine". The top-left card is the
     * host: it stays where it is and keeps its machine and settings, which every recipe then takes; the other cards'
     * recipes become its sections, their wires with them. A pinned count on the host becomes the machine's. One undo
     * step. Returns the host, or null when they cannot share a machine.
     */
    public UUID combine(final java.util.Collection<UUID> ids) {
        if (!canCombine(ids)) {
            Sfx.DENY.play();
            return null;
        }
        final List<UUID> cards = cardsIn(ids);
        cards.sort(
            java.util.Comparator.comparingInt((UUID id) -> graph.nodes.get(id).y)
                .thenComparingInt(id -> graph.nodes.get(id).x));
        final UUID hostId = cards.get(0);
        final List<UUID> sections = allSections(cards);
        final List<ItemStack> common = commonMachines(sections);
        edit(() -> {
            final Node host = graph.nodes.get(hostId);
            com.gtnhplanner.data.flowchart.MachineGroup g = sharedOf(hostId);
            final int capacity = g != null ? g.getMachineCapacity()
                : host.isMachineCountFixed() ? host.machineConfig.getMachineCount() : 0;
            final boolean pinned = g != null ? g.isPinned() : host.isMachineCountFixed();
            for (final UUID card : cards) {
                final com.gtnhplanner.data.flowchart.MachineGroup old = sharedOf(card);
                if (old != null) graph.removeGroup(old.getId());
            }
            g = new com.gtnhplanner.data.flowchart.MachineGroup();
            g.setHeader("Shared machine");
            for (final UUID s : sections) g.addSection(s);
            g.setMachineCapacity(capacity);
            g.setPinned(pinned && capacity > 0);
            graph.groups.put(g.getId(), g);
            // The host's machine when every recipe runs on it, else the first that runs them all.
            final CardModel hostModel = models.get(hostId);
            final boolean hostCommon = hostModel != null && hostModel.machineStack != null
                && common.stream()
                    .anyMatch(c -> ItemStack.areItemStacksEqual(c, hostModel.machineStack));
            if (!hostCommon) host.machineName = CardDefaults.itemKey(common.get(0));
            for (final UUID s : sections) {
                final Node n = graph.nodes.get(s);
                n.setMachineCountFixed(false);
                n.x = host.x;
                n.y = host.y;
            }
            copyMachineSettings(host, sections);
        });
        selection.clear();
        selection.add(hostId);
        Sfx.MERGE.play();
        return hostId;
    }

    /** Gives every section the host's machine and its machine settings; each keeps its own recipe's. */
    private void copyMachineSettings(final Node host, final List<UUID> sections) {
        for (final UUID s : sections) {
            final Node n = graph.nodes.get(s);
            if (n == null || n == host) continue;
            n.machineName = host.machineName;
            for (final Map.Entry<String, Object> e : host.machineConfig.settings.entrySet()) {
                if (!RECIPE_SETTINGS.contains(e.getKey())) n.machineConfig.settings.put(e.getKey(), e.getValue());
            }
        }
    }

    /** An edit to a card's machine (one undo step); a shared machine's other recipes take it too. */
    public void editMachine(final Node node, final Runnable change) {
        edit(() -> {
            change.run();
            syncShared(node);
        });
    }

    /** After an edit to a card's machine, the same on every other recipe of its shared machine. */
    private void syncShared(final Node node) {
        final com.gtnhplanner.data.flowchart.MachineGroup g = sharedOf(node.id);
        if (g != null) copyMachineSettings(node, g.getSections());
    }

    /** Takes a recipe off its shared machine: the recipe and its wires go, as Factory Flow's X does. */
    public void removeSection(final UUID nodeId) {
        final com.gtnhplanner.data.flowchart.MachineGroup g = sharedOf(nodeId);
        if (g == null) return;
        edit(() -> {
            g.removeSection(nodeId);
            graph.removeNode(nodeId);
            if (!g.isShared()) graph.removeGroup(g.getId());
        });
        Sfx.REMOVE.play();
    }

    /** Moves a recipe up (-1) or down (+1) on its shared card; the first one is the host. */
    public void moveSection(final UUID nodeId, final int step) {
        final com.gtnhplanner.data.flowchart.MachineGroup g = sharedOf(nodeId);
        if (g == null) return;
        final List<UUID> sections = g.getSections();
        final int i = sections.indexOf(nodeId), j = i + step;
        if (i < 0 || j < 0 || j >= sections.size()) return;
        editLayout(() -> java.util.Collections.swap(sections, i, j));
        Sfx.TICK.play(step < 0 ? 1.12f : 0.9f);
    }

    /** Pins a shared machine's count (zero unpins): its recipes' machines then add up to exactly that. */
    public void pinShared(final com.gtnhplanner.data.flowchart.MachineGroup g, final double count) {
        (count > 0 ? Sfx.PIN : Sfx.UNPIN).play();
        edit(() -> {
            g.setPinned(count > 0);
            g.setMachineCapacity(count > 0 ? Math.max(1, (int) Math.round(count)) : 0);
        });
    }

    /** The shared card the next recipe added from NEI joins, armed by its "Add another recipe". */
    private UUID addSectionTo;

    /**
     * Opens NEI on the card's machine and arms the card, so the recipe picked there with "+" joins it as another
     * section: the machine's whole recipe list when NEI has one (as its progress arrow opens it), else the machine's
     * uses. False when NEI shows neither.
     */
    public boolean addRecipeTo(final UUID nodeId) {
        final CardModel m = models.get(hostOf(nodeId));
        if (m == null) return false;
        addSectionTo = hostOf(nodeId);
        final RecipeHandlerRef ref = RecipeHandlerRef.of(m.node.recipeId);
        if (ref != null && com.gtnhplanner.ui.Planner.browse(ref.handler.getOverlayIdentifier())) return true;
        if (m.machineStack != null && com.gtnhplanner.ui.Planner.lookUp(m.machineStack.copy(), true)) return true;
        addSectionTo = null;
        return false;
    }

    /**
     * Makes a just-added recipe a section of the armed card, when one machine runs it and everything already there.
     * Inside the add's edit, so it is one undo step with it. False (with a notice) when no machine runs both.
     */
    private boolean joinArmedCard(final Node node, final UUID hostId) {
        final Node host = graph.nodes.get(hostId);
        if (host == null) return false;
        final List<UUID> sections = new ArrayList<>(sectionsOf(hostId));
        final List<ItemStack> machines = new ArrayList<>(commonMachines(sections));
        final List<ItemStack> mine = CardModel.catalystsOf(node);
        machines.removeIf(
            c -> mine.stream()
                .noneMatch(o -> ItemStack.areItemStacksEqual(o, c)));
        if (machines.isEmpty()) {
            flash(
                Severity.WARN,
                "No machine runs this recipe and what the " + models.get(hostId).machineName
                    + " already has: added as its own card");
            return false;
        }
        com.gtnhplanner.data.flowchart.MachineGroup g = sharedOf(hostId);
        if (g == null) {
            g = new com.gtnhplanner.data.flowchart.MachineGroup();
            g.setHeader("Shared machine");
            g.addSection(hostId);
            if (host.isMachineCountFixed()) {
                g.setMachineCapacity(host.machineConfig.getMachineCount());
                g.setPinned(true);
                host.setMachineCountFixed(false);
            }
            graph.groups.put(g.getId(), g);
        }
        g.addSection(node.id);
        node.x = host.x;
        node.y = host.y;
        copyMachineSettings(host, List.of(node.id));
        return true;
    }

    // endregion

    // region Card edits (each one undoable, saved, re-solved)

    /** Puts a non-recipe machine (a generator) on the board at these settings, centred in view and selected. */
    public Node addPower(final String sourceId, final Map<String, String> settings) {
        return add(Node.power(sourceId, settings, 0, 0), true, false);
    }

    /**
     * Puts an empty custom rate card on the board, centred in view and selected: at the dial last set on one, holding
     * nothing until something is wired to it.
     */
    public Node addCustomRate() {
        final String id = com.gtnhplanner.power.CustomRate.ID;
        final Node node = Node.power(
            id,
            com.gtnhplanner.power.CustomRate.fresh(com.gtnhplanner.ui.card.SettingMemory.powerSettings(id, Map.of())),
            0,
            0);
        // Its dial is its rate: one of it, pinned, until the player unpins it to let the plan scale it.
        node.machineConfig.setMachineCount(1);
        node.setMachineCountFixed(true);
        return add(node, true, false);
    }

    /**
     * Sets one of a power card's settings and rebuilds its ports. A wire or drawer on a port follows its resource to
     * wherever it now is on the card, and goes when the card no longer has it (a fuel switched for another).
     */
    public void setPowerSetting(final Node node, final String settingId, final String value) {
        if (!node.isPower() || value.equals(node.powerSettings.get(settingId))) return;
        if ("true".equals(value)) Sfx.TOGGLE_ON.play();
        else if ("false".equals(value)) Sfx.TOGGLE_OFF.play();
        else Sfx.ADJUST.play();
        edit(() -> {
            final List<String> ins = keys(node.inputs), outs = keys(node.outputs);
            node.powerSettings.put(settingId, value);
            node.refresh();
            final List<String> nowIns = keys(node.inputs), nowOuts = keys(node.outputs);
            for (final Edge e : new ArrayList<>(graph.getEdges())) {
                if (e.sourceNodeId.equals(node.id)) {
                    final int at = moved(outs, nowOuts, e.sourceOutputIndex);
                    if (at < 0) graph.removeEdge(e.id);
                    else e.sourceOutputIndex = at;
                }
                if (e.targetNodeId.equals(node.id)) {
                    final int at = moved(ins, nowIns, e.targetInputIndex);
                    if (at < 0) graph.removeEdge(e.id);
                    else e.targetInputIndex = at;
                }
            }
            for (final Drawer d : graph.getDrawers()) {
                final boolean input = d.getKind()
                    .linksInputs();
                for (final Drawer.Link link : new ArrayList<>(d.getLinks())) {
                    if (!link.nodeId()
                        .equals(node.id)) continue;
                    final int at = input ? moved(ins, nowIns, link.portIndex())
                        : moved(outs, nowOuts, link.portIndex());
                    if (at == link.portIndex()) continue;
                    graph.unlinkDrawer(d.getId(), link);
                    if (at >= 0) graph.linkDrawer(d.getId(), new Drawer.Link(node.id, at));
                }
            }
        });
    }

    private static List<String> keys(final List<com.gtnhplanner.data.flowchart.Port<?>> ports) {
        final List<String> keys = new ArrayList<>(ports.size());
        for (final com.gtnhplanner.data.flowchart.Port<?> p : ports) keys.add(Resources.key(p));
        return keys;
    }

    /** Where the port that was at {@code index} is now, by its resource; -1 when it is gone. */
    private static int moved(final List<String> was, final List<String> now, final int index) {
        return index >= 0 && index < was.size() ? now.indexOf(was.get(index)) : -1;
    }

    public void setVoltage(final Node node, final String tier) {
        edit(() -> {
            node.machineConfig.setString("voltage", tier);
            syncShared(node);
        });
    }

    public void setSetting(final Node node, final String key, final Object value) {
        if (value instanceof final Boolean on) (on ? Sfx.TOGGLE_ON : Sfx.TOGGLE_OFF).play();
        else Sfx.ADJUST.play();
        edit(() -> {
            if (value instanceof final Boolean b) node.machineConfig.setBoolean(key, b);
            else if (value instanceof final Integer i) node.machineConfig.setInt(key, i);
            else node.machineConfig.setString(key, String.valueOf(value));
            syncShared(node);
        });
    }

    /** Picks which machine runs the recipe; GregTech single blocks bring their tier, multiblocks their options. */
    public void chooseMachine(final Node node, final ItemStack machine, final boolean gregtech) {
        edit(() -> {
            CardDefaults.useMachine(node, machine, gregtech);
            syncShared(node);
        });
    }

    /**
     * Pins the machine count (gold on the card); zero or less unpins and lets the plan decide. On a shared machine the
     * count is the machine's: its recipes' machines add up to it.
     */
    public void pin(final Node node, final double count) {
        final com.gtnhplanner.data.flowchart.MachineGroup g = sharedOf(node.id);
        if (g != null) {
            pinShared(g, count);
            return;
        }
        (count > 0 ? Sfx.PIN : Sfx.UNPIN).play();
        edit(() -> {
            if (count <= 0) {
                node.setMachineCountFixed(false);
                return;
            }
            node.machineConfig.setMachineCount(Math.max(1, (int) Math.round(count)));
            node.setMachineCountFixed(true);
        });
    }

    /** Deletes a card: every recipe on it, when it is a shared machine. */
    public void delete(final Node node) {
        final List<UUID> sections = sectionsOf(node.id);
        edit(() -> { for (final UUID s : sections) graph.removeNode(s); });
        Sfx.REMOVE.play();
    }

    /**
     * A copy of the card beside it, with the same machine and settings, not wired; a shared machine with its recipes.
     */
    public void cloneNode(final Node node) {
        final List<Node> copies = new ArrayList<>();
        for (final UUID s : sectionsOf(node.id)) {
            final Node from = graph.nodes.get(s);
            if (from != null && from.isPower()) {
                final Node copy = Node.power(from.powerSource, from.powerSettings, node.x + 24, node.y + 24);
                copy.machineConfig.copySettingsFrom(from.machineConfig);
                copies.add(copy);
                continue;
            }
            final RecipeHandlerRef ref = from == null ? null : RecipeHandlerRef.of(from.recipeId);
            if (ref == null) return;
            final Node copy = new Node(ref.handler, ref.recipeIndex, node.x + 24, node.y + 24);
            copy.machineConfig.copySettingsFrom(from.machineConfig);
            copy.machineName = from.machineName;
            copies.add(copy);
        }
        final com.gtnhplanner.data.flowchart.MachineGroup shared = sharedOf(node.id);
        edit(() -> {
            for (final Node copy : copies) graph.addNode(copy);
            if (shared == null) return;
            final com.gtnhplanner.data.flowchart.MachineGroup g = new com.gtnhplanner.data.flowchart.MachineGroup();
            g.setHeader("Shared machine");
            for (final Node copy : copies) g.addSection(copy.id);
            g.setMachineCapacity(shared.getMachineCapacity());
            g.setPinned(shared.isPinned());
            graph.groups.put(g.getId(), g);
        });
        Sfx.CLONE.play();
    }

    // endregion

    /** True while the board shows an answer older than the plan (a solve is queued or running). */
    public boolean solving() {
        final SolveService.Result latest = solver.latest();
        return latest == null || latest.version() != graph.solveVersion();
    }

    private long solvingSince = -1;

    /** Solving for long enough to say so: a quick solve should not flicker the top bar. */
    public boolean solvingVisibly() {
        if (!solving()) {
            solvingSince = -1;
            return false;
        }
        if (solvingSince < 0) solvingSince = System.currentTimeMillis();
        return System.currentTimeMillis() - solvingSince > 250;
    }

    /**
     * Called every client tick while the board is open, never from drawing. Hands the solver a snapshot when the plan
     * changed in a way that matters to the answer, and rebuilds the card models when the layout moved or an answer
     * came back. Until then the cards keep showing the last answer.
     */
    public void tick() {
        if (!com.gtnhplanner.dev.DevPerf.on()) {
            tickBoard();
            return;
        }
        final long started = System.nanoTime();
        tickBoard();
        com.gtnhplanner.dev.DevPerf.time("session.tick", System.nanoTime() - started);
    }

    private void tickBoard() {
        commitWheeledRates();
        if (disarmOnTick) {
            disarmOnTick = false;
            pendingLookup = null;
            addSectionTo = null;
        }
        follow();
        try {
            solver.request(graph.solveVersion(), () -> SolveInput.of(graph, BalanceMode.AUTO, null));
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.warn("Could not snapshot the plan for solving", e);
        }
        final SolveService.Result latest = solver.latest();
        final boolean answered = latest != null && latest != lastResult;
        if (answered) {
            lastResult = latest;
            if (latest.error() != null) GtnhPlanner.LOG.warn("Solve failed", latest.error());
            result = latest.balance();
        } else if (graph.version() == seenVersion) {
            return;
        }
        indexSharedMachines();
        seenVersion = graph.version();
        final Set<String> wired = wiredPorts();
        final Map<UUID, CardModel> next = new LinkedHashMap<>();
        for (final Node node : graph.getNodes()) {
            try {
                next.put(
                    node.id,
                    CardModel.of(
                        node,
                        result == null ? null
                            : result.nodeBalances()
                                .get(node.id),
                        (output, index) -> wired.contains(portKey(node.id, output, index))));
            } catch (final RuntimeException e) {
                GtnhPlanner.LOG.warn("Card for {} failed", node.machineName, e);
            }
        }
        final Map<UUID, DrawerModel> nextDrawers = new LinkedHashMap<>();
        for (final Drawer drawer : graph.getDrawers()) nextDrawers.put(drawer.getId(), DrawerModel.of(drawer, result));
        if (!next.keySet()
            .equals(models.keySet())
            || !nextDrawers.keySet()
                .equals(drawerModels.keySet())
            || !graph.notes.keySet()
                .equals(noteIds)) {
            structure++;
            noteIds = new HashSet<>(graph.notes.keySet());
        }
        models = next;
        drawerModels = nextDrawers;
        notices = buildNotices(next);
        totals = buildTotals(next);
        if (answered) heardRunning(next);
    }

    /** Whether the plan last solved to machines running, and for which graph: another one is taken as it is. */
    private boolean running;
    private Graph runningOf;

    /**
     * The relay when a fresh answer has the plan running where it did not (the first pin or rate that makes it
     * solve). A graph taken up (a tab, an undo, back from NEI) only sets where things stand.
     */
    private void heardRunning(final Map<UUID, CardModel> cards) {
        final boolean now = result instanceof BalanceResult.Solved && !nothingToSolveFor
            && notices.stream()
                .noneMatch(n -> n.severity() == Severity.ERROR)
            && cards.values()
                .stream()
                .anyMatch(m -> m.machines > 0);
        if (runningOf == graph && now && !running) Sfx.RUNNING.play();
        running = now;
        runningOf = graph;
    }

    // region Totals

    /** One resource line of the overview: what it is and its rate (per second). */
    public record TotalLine(String key, String label, ItemStack item, net.minecraftforge.fluids.FluidStack fluid,
        double amount) {

        public boolean isFluid() {
            return fluid != null;
        }
    }

    /** One card in the overview's machine list. */
    public record MachineLine(UUID nodeId, String name, ItemStack stack, double machines, boolean pinned, String tier,
        int amps, boolean multiblock, boolean gregtech, double euPerTick, boolean tooLow, double madeEuPerTick) {}

    /**
     * The overview, Factory Flow's resources column: per resource what the plan needs from outside (deficit), what it
     * gives out (surplus) and what it makes and uses itself (internal); its power; one line per card to build.
     */
    public record Totals(List<TotalLine> inputs, List<TotalLine> outputs, List<TotalLine> internal, double euPerTick,
        List<MachineLine> machines, double euMade) {

        static final Totals EMPTY = new Totals(List.of(), List.of(), List.of(), 0, List.of(), 0);
    }

    private Totals totals = Totals.EMPTY;

    public Totals totals() {
        return totals;
    }

    /** A resource's name and icon, from the first port or drawer that holds it. */
    private record Look(String label, ItemStack item, net.minecraftforge.fluids.FluidStack fluid) {}

    /** Below this a rate (per second) is solver noise, not a flow worth a line. */
    private static final double EPS = 1e-6;

    private Totals buildTotals(final Map<UUID, CardModel> cards) {
        final Map<String, double[]> flow = new LinkedHashMap<>();
        final Map<String, Look> looks = new HashMap<>();
        double eu = 0, euMade = 0;
        final List<MachineLine> machines = new ArrayList<>();
        for (final CardModel card : cards.values()) {
            // A custom rate card is the plan's edge, as a drawer is: what it supplies comes in from outside (an input)
            // and what it drains leaves (an output). It is no machine to build.
            if (com.gtnhplanner.power.CustomRate.is(card.node)) continue;
            for (final CardModel.PortView p : card.outputs) {
                flow.computeIfAbsent(p.key(), k -> new double[2])[0] += p.perSecond();
                looks.putIfAbsent(p.key(), new Look(p.name(), p.item(), p.fluid()));
            }
            for (final CardModel.PortView p : card.inputs) {
                flow.computeIfAbsent(p.key(), k -> new double[2])[1] += p.perSecond();
                looks.putIfAbsent(p.key(), new Look(p.name(), p.item(), p.fluid()));
            }
            final double power = power(card);
            eu += power;
            euMade += card.madeEuPerTick();
            if (card.machines > 0 || card.pinned) machines.add(
                new MachineLine(
                    card.node.id,
                    card.machineName,
                    card.machineStack,
                    card.machines,
                    card.pinned,
                    card.tier,
                    card.amps,
                    card.multiblock,
                    card.gregtech,
                    power,
                    card.tierTooLow(),
                    card.madeEuPerTick()));
        }
        final List<TotalLine> in = new ArrayList<>(), out = new ArrayList<>(), internal = new ArrayList<>();
        for (final Map.Entry<String, double[]> e : flow.entrySet()) {
            final double made = e.getValue()[0], used = e.getValue()[1];
            final Look look = looks.get(e.getKey());
            if (used - made > EPS) in.add(line(e.getKey(), look, used - made));
            if (made - used > EPS) out.add(line(e.getKey(), look, made - used));
            if (Math.min(made, used) > EPS) internal.add(line(e.getKey(), look, Math.min(made, used)));
        }
        sortByAmount(in);
        sortByAmount(out);
        sortByAmount(internal);
        // A resource with a drawer stays listed at 0, so its rule and rate can be set from here.
        for (final DrawerModel d : drawerModels.values()) {
            final String key = d.drawer.getResourceKey();
            final List<TotalLine> side = d.kind == Drawer.Kind.SOURCE ? in : out;
            if (side.stream()
                .noneMatch(
                    l -> l.key()
                        .equals(key)))
                side.add(new TotalLine(key, d.label, d.item, d.fluid, 0));
        }
        machines.sort(
            java.util.Comparator.<MachineLine>comparingInt(m -> -CardDefaults.tierIndex(m.tier()))
                .thenComparing(m -> -m.euPerTick())
                .thenComparing(MachineLine::name));
        return new Totals(in, out, internal, eu, machines, euMade);
    }

    private static TotalLine line(final String key, final Look look, final double amount) {
        return look == null ? new TotalLine(key, Resources.name(key), Resources.item(key), Resources.fluid(key), amount)
            : new TotalLine(key, look.label(), look.item(), look.fluid(), amount);
    }

    private static void sortByAmount(final List<TotalLine> lines) {
        lines.sort((a, b) -> Double.compare(b.amount(), a.amount()));
    }

    // endregion

    // region Wiring state and notices

    private static String portKey(final UUID nodeId, final boolean output, final int index) {
        return nodeId + (output ? ":o:" : ":i:") + index;
    }

    /** Every port with a wire or a drawer on it. */
    private Set<String> wiredPorts() {
        final Set<String> wired = new HashSet<>();
        for (final Edge e : graph.getEdges()) {
            wired.add(portKey(e.sourceNodeId, true, e.sourceOutputIndex));
            wired.add(portKey(e.targetNodeId, false, e.targetInputIndex));
        }
        for (final Drawer d : graph.getDrawers()) {
            for (final Drawer.Link link : d.getLinks()) {
                wired.add(
                    portKey(
                        link.nodeId(),
                        !d.getKind()
                            .linksInputs(),
                        link.portIndex()));
            }
        }
        return wired;
    }

    public List<Notice> notices() {
        if (flash == null) return notices;
        if (System.currentTimeMillis() > flashUntil) {
            flash = null;
            return notices;
        }
        final List<Notice> out = new ArrayList<>(notices.size() + 1);
        out.add(flash);
        out.addAll(notices);
        return out;
    }

    private Notice flash;
    private long flashUntil;

    /** Shows a sentence above the solver's notices for a few seconds (what a paste or an import did). */
    public void flash(final Severity severity, final String text) {
        flash = new Notice(severity, text, List.of());
        flashUntil = System.currentTimeMillis() + 7000;
    }

    /**
     * Adds the plan on the clipboard as a new slot: a Factory Flow link, code or JSON, or a GTNH Planner share code.
     * Says what happened in a notice.
     */
    public void pastePlan() {
        final String text = net.minecraft.client.gui.GuiScreen.getClipboardString();
        if (text == null || text.isBlank()) {
            flash(Severity.WARN, "The clipboard is empty: copy a Factory Flow plan link or code first");
            Sfx.DENY.play();
            return;
        }
        final Graph own = PlanAPI.importFromClipboard();
        if (own != null) {
            com.gtnhplanner.importer.game.FactoryFlowImport.addAsSlot(own);
            flash(Severity.INFO, "Pasted '" + own.getName() + "'");
            Sfx.SWEEP.play();
            return;
        }
        try {
            final com.gtnhplanner.importer.FfConverter.Result result = com.gtnhplanner.importer.game.FactoryFlowImport
                .importAsSlot(text);
            final com.gtnhplanner.importer.ImportReport report = result.report();
            final int missing = report.entries(com.gtnhplanner.importer.ImportReport.Kind.UNMATCHED)
                .size();
            final String name = result.graph()
                .getName();
            flash(missing == 0 ? Severity.INFO : Severity.WARN, "Imported '" + name + "': " + report.summary());
            Sfx.SWEEP.play();
        } catch (final RuntimeException e) {
            flash(
                Severity.WARN,
                "The clipboard does not hold a plan (a Factory Flow link or code, or a GTNH Planner code)");
            Sfx.DENY.play();
            com.gtnhplanner.GtnhPlanner.LOG.info("Paste plan failed", e);
        }
    }

    /** Copies a slot's GTNH Planner share code to the clipboard. */
    public void copyPlan(final int index) {
        if (index < 0 || index >= slots().size()) return;
        PlanAPI.copyToClipboard(slots().get(index));
        flash(
            Severity.INFO,
            "Copied '" + slots().get(index)
                .getName() + "' to the clipboard");
    }

    /** True when the solver had nothing to scale the plan by: no product rate and no pinned count. */
    public boolean nothingToSolveFor() {
        return nothingToSolveFor;
    }

    /**
     * The lines along the top of the board, as a player wants them: what is wrong in a few words, the thing it is about
     * named first, and "Show me" to find it. The solver's own sentences are for the log; problems of one kind share a
     * line.
     */
    private List<Notice> buildNotices(final Map<UUID, CardModel> cards) {
        final List<Notice> out = new ArrayList<>();
        nothingToSolveFor = false;
        if (result != null) {
            for (final Note note : result.notes()) {
                if (note.message() == SolverMessage.NO_PIN) {
                    nothingToSolveFor = !cards.isEmpty();
                    if (nothingToSolveFor) out.add(
                        new Notice(
                            Severity.INFO,
                            "Nothing to calculate yet: set a rate on a drawer, or pin a machine count",
                            List.of()));
                }
            }
            for (final DrawerReadout.Shortfall s : result.drawers()
                .shortfalls()) {
                final DrawerModel d = drawerModels.get(s.drawer());
                final boolean supply = d != null && d.kind == Drawer.Kind.SOURCE;
                final String label = "The " + (d == null ? "" : d.label + " ") + (supply ? "supply" : "drawer");
                final List<UUID> focus = new ArrayList<>();
                focus.add(s.drawer());
                focus.addAll(s.limitingNodes());
                focus.addAll(s.limitingDrawers());
                final String reach = d == null ? Fmt.rate(s.reachable(), rateUnit, false)
                    : d.rate(s.reachable(), rateUnit);
                final String target = d == null ? Fmt.rate(s.target(), rateUnit, false) : d.rate(s.target(), rateUnit);
                // Plain sentences: what the drawer asks for, and what the plan does instead.
                final String asks = s.rule() == Drawer.Rule.EXACTLY ? "exactly " : "";
                final String text;
                if (s.reachable() > s.target()) text = label + " allows at most "
                    + target
                    + ", but the plan "
                    + (supply ? "needs " : "makes ")
                    + reach;
                else text = label + (supply ? " should give " : " wants ")
                    + asks
                    + target
                    + ", but the plan can only "
                    + (supply ? "use " : "make ")
                    + reach;
                out.add(new Notice(Severity.WARN, text, focus));
            }
            for (final Note note : result.notes()) {
                // If/else, not a switch: a switch on an enum compiles to a lookup table a hot swap does not refresh.
                // The solver also guesses at missing wires (something made here and brought in too); that is a guess
                // about what the player meant, not a problem with the plan, so it is not shown.
                final SolverMessage m = note.message();
                if (m == SolverMessage.OVERSHOOTS_TARGET) out.add(overshoot(note, cards));
                else if (m == SolverMessage.DRAWER_NOT_CONNECTED) out.add(
                    new Notice(
                        Severity.WARN,
                        "The " + note.args()[0] + " drawer has a rate, but nothing is wired to it",
                        idsIn(note)));
                else if (m == SolverMessage.DRAWER_WIRED_IGNORED) out.add(
                    new Notice(
                        Severity.WARN,
                        "The " + note.args()[0] + " drawer's rate does nothing: the ports it's on are wired",
                        idsIn(note)));
                else if (m == SolverMessage.CHOICE_NO_LONGER_FITS || m == SolverMessage.CHOICE_NEEDS_MORE_GATES)
                    out.add(
                        new Notice(
                            Severity.WARN,
                            "Your saved choice no longer fits this plan, so the planner's own answer is shown",
                            List.of()));
                else if (note.severity() == Severity.ERROR && m != SolverMessage.EMPTY_GRAPH) out.add(failure(note));
            }
        } else if (lastResult != null && lastResult.errorNote() != null) {
            out.add(failure(lastResult.errorNote()));
        }
        final List<UUID> unwiredCards = new ArrayList<>();
        int unwired = 0, looseIn = 0, looseOut = 0;
        for (final CardModel card : cards.values()) {
            int here = 0;
            for (final CardModel.PortView p : card.inputs) if (!p.wired()) {
                here++;
                looseIn++;
            }
            for (final CardModel.PortView p : card.outputs) if (!p.wired()) {
                here++;
                looseOut++;
            }
            if (here > 0) unwiredCards.add(card.node.id);
            unwired += here;
        }
        if (unwired > 0)
            out.add(new Notice(Severity.WARN, loose(looseIn, looseOut) + " no wire or drawer yet", unwiredCards));
        return out;
    }

    /** "2 inputs and 1 output have", "1 input has": the ports with nothing on them. */
    private static String loose(final int inputs, final int outputs) {
        final String in = inputs + (inputs == 1 ? " input" : " inputs"),
            out = outputs + (outputs == 1 ? " output" : " outputs");
        if (outputs == 0) return in + (inputs == 1 ? " has" : " have");
        if (inputs == 0) return out + (outputs == 1 ? " has" : " have");
        return in + " and " + out + " have";
    }

    /** An output made past its target, said in full. */
    private Notice overshoot(final Note note, final Map<UUID, CardModel> cards) {
        final Object[] a = note.args();
        final String what = String.valueOf(a[1]);
        boolean fluid = false;
        final CardModel card = a.length > 4 && a[4] instanceof final UUID id ? cards.get(id) : null;
        if (card != null) for (final CardModel.PortView p : card.outputs) if (p.name()
            .equals(what)) fluid = p.isFluid();
        final double made = a[2] instanceof final Number n ? n.doubleValue() : 0;
        final double target = a[3] instanceof final Number n ? n.doubleValue() : 0;
        return new Notice(
            Severity.WARN,
            "The " + named(note).args()[0]
                + " makes "
                + Fmt.rate(made, rateUnit, fluid)
                + " of "
                + what
                + ", more than its "
                + Fmt.rate(target, rateUnit, fluid)
                + " target, because another of its outputs needs more",
            idsIn(note));
    }

    /**
     * A solve that found no answer, as what the player can do about it. The solver's own account goes to the log, once
     * per change.
     */
    private Notice failure(final Note note) {
        final String detail = render(note);
        if (!detail.equals(lastFailureLogged)) {
            lastFailureLogged = detail;
            GtnhPlanner.LOG.info("[solve] no answer: {}", detail);
        }
        final List<Note> clashing = new ArrayList<>();
        collect(note, SolverMessage.PIN_DROPPED, clashing);
        if (!clashing.isEmpty()) {
            final List<String> names = new ArrayList<>();
            final List<UUID> ids = new ArrayList<>();
            for (final Note pin : clashing) {
                final String name = String.valueOf(named(pin).args()[0]);
                if (!names.contains(name)) names.add(name);
                ids.addAll(idsIn(pin));
            }
            return new Notice(
                Severity.ERROR,
                "The pinned counts on " + String.join(" and ", names) + " can't all be met at once: unpin one",
                ids);
        }
        if (note.containsMessage(SolverMessage.MACHINES_CANNOT_RUN))
            return new Notice(Severity.ERROR, "Some machines can't run with these rates and pinned counts", List.of());
        if (note.containsMessage(SolverMessage.SOLVER_UNSATISFIABLE)
            || note.containsMessage(SolverMessage.SOLVER_NO_SOLUTION)
            || note.containsMessage(SolverMessage.SOLVER_BUDGET)
            || note.containsMessage(SolverMessage.STAGE_FAILED))
            return new Notice(
                Severity.ERROR,
                "There's no way to meet all these rates and pinned counts: change a rate or unpin a count",
                List.of());
        return new Notice(
            Severity.ERROR,
            "The planner couldn't solve this plan (the details are in the log)",
            List.of());
    }

    private String lastFailureLogged = "";

    /** Every note of a kind inside a note's arguments, nested notes and lists included. */
    private static void collect(final Object arg, final SolverMessage kind, final List<Note> into) {
        if (arg instanceof final Note note) {
            if (note.message() == kind) into.add(note);
            for (final Object a : note.args()) collect(a, kind, into);
        } else if (arg instanceof final List<?> list) for (final Object o : list) collect(o, kind, into);
    }

    private static String render(final Note note) {
        try {
            return named(note).render();
        } catch (final RuntimeException e) {
            return note.describe();
        }
    }

    /**
     * The note with every resource key among its arguments as the name a player reads: a GregTech card's machine is
     * remembered as its item key ("item:gregtech:gt.blockmachines:1000"), and the solver names machines by it.
     */
    private static Note named(final Note note) {
        final Object[] args = note.args()
            .clone();
        for (int i = 0; i < args.length; i++) args[i] = namedArg(args[i]);
        return new Note(note.message(), args);
    }

    private static Object namedArg(final Object arg) {
        if (arg instanceof final Note nested) return named(nested);
        if (arg instanceof final List<?> list) {
            final List<Object> out = new ArrayList<>(list.size());
            for (final Object o : list) out.add(namedArg(o));
            return out;
        }
        if (arg instanceof final String s && (s.startsWith("item:") || s.startsWith("fluid:"))) return Resources.name(s);
        return arg;
    }

    /** Node and drawer ids a note names, for "Show me". */
    private List<UUID> idsIn(final Note note) {
        final List<UUID> ids = new ArrayList<>();
        for (final Object arg : note.args()) {
            if (arg instanceof final UUID id && (graph.nodes.containsKey(id) || graph.drawers.containsKey(id)))
                ids.add(id);
            else if (arg instanceof final Note nested) ids.addAll(idsIn(nested));
        }
        return ids;
    }

    // endregion
}
