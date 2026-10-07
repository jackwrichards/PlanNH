package com.sbancuz.plannh.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.item.ItemStack;

import com.sbancuz.plannh.PlanNH;
import com.sbancuz.plannh.api.PlanAPI;
import com.sbancuz.plannh.data.flowchart.Drawer;
import com.sbancuz.plannh.data.flowchart.Edge;
import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.data.flowchart.Plan;
import com.sbancuz.plannh.data.flowchart.Port;
import com.sbancuz.plannh.data.flowchart.balancer.BalanceMode;
import com.sbancuz.plannh.data.flowchart.balancer.BalanceResult;
import com.sbancuz.plannh.data.flowchart.balancer.DrawerReadout;
import com.sbancuz.plannh.data.flowchart.balancer.Note;
import com.sbancuz.plannh.data.flowchart.balancer.Severity;
import com.sbancuz.plannh.data.flowchart.balancer.SolveInput;
import com.sbancuz.plannh.data.flowchart.balancer.SolveService;
import com.sbancuz.plannh.data.flowchart.balancer.SolverMessage;
import com.sbancuz.plannh.nei.NodeLookupContext;
import com.sbancuz.plannh.ui.card.CardDefaults;
import com.sbancuz.plannh.ui.card.CardLayout;
import com.sbancuz.plannh.ui.card.CardModel;
import com.sbancuz.plannh.ui.drawer.DrawerCard;
import com.sbancuz.plannh.ui.drawer.DrawerModel;
import com.sbancuz.plannh.ui.gt.GtMachines;
import com.sbancuz.plannh.ui.theme.Fmt;

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

    private String hoverKey;

    /** The resource under the mouse (a port, drawer or wire), so everything carrying it can glow; null for none. */
    public String hoverKey() {
        return railHoverKey != null ? railHoverKey : hoverKey;
    }

    private String railHoverKey;

    /** The resource under the mouse in the overview rail; it wins over the board's while set. */
    public void setRailHoverKey(final String key) {
        railHoverKey = key == null || key.isEmpty() ? null : key;
    }

    public void setHoverKey(final String key) {
        hoverKey = key == null || key.isEmpty() ? null : key;
    }

    public Fmt.RateUnit rateUnit() {
        return rateUnit;
    }

    public void setRateUnit(final Fmt.RateUnit unit) {
        rateUnit = unit;
    }

    /**
     * One undoable edit: records the before state, runs the change, marks the plan changed and saves. The next tick
     * re-solves and rebuilds the cards.
     */
    @SuppressWarnings("deprecation")
    public void edit(final Runnable change) {
        PlanAPI.recordEdit(graph, change);
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

    public UUID takeJustAdded() {
        final UUID id = justAdded;
        justAdded = null;
        return id;
    }

    /**
     * Puts an NEI recipe on the board. When it came from a lookup on a port, it lands beside that card and only that
     * resource is wired; otherwise it lands to the right of everything already there.
     */
    public Node addRecipe(final IRecipeHandler handler, final int recipeIndex) {
        final NodeLookupContext origin = pendingLookup;
        pendingLookup = null;
        final UUID joinTo = addSectionTo;
        addSectionTo = null;
        final Node node = new Node(handler, recipeIndex, 0, 0);
        CardDefaults.apply(node);
        justAdded = joinTo != null && graph.nodes.containsKey(joinTo) ? joinTo : node.id;
        edit(() -> {
            graph.addNode(node);
            // "Add another recipe" on a card: it joins that machine, wired like any add.
            if (joinTo != null && joinArmedCard(node, joinTo)) {
                autoWire(node);
                return;
            }
            final Node from = origin == null ? null : graph.nodes.get(origin.nodeId());
            if (from != null && wireToOrigin(node, from, origin)) {
                node.x = origin.output() ? from.x + CardLayout.W + GAP : from.x - CardLayout.W - GAP;
                node.y = from.y;
                for (int tries = 0; tries < 50 && overlapsAnything(node); tries++) node.y += 40;
            } else {
                autoWire(node);
                placeBesideNeighbours(node);
            }
        });
        return node;
    }

    /**
     * Wires a new card to what is already on the board: each input to the first card that makes it (or a source
     * drawer holding it), each output to a card that uses it and has no supply yet (or a product drawer holding it).
     */
    private void autoWire(final Node added) {
        for (int in = 0; in < added.inputs.size(); in++) {
            final Port<?> want = added.inputs.get(in);
            boolean done = false;
            for (final Node n : graph.getNodes()) {
                if (n == added || done) continue;
                for (int out = 0; out < n.outputs.size() && !done; out++) {
                    if (!n.outputs.get(out)
                        .canConnect(want)) continue;
                    graph.addEdge(new Edge(UUID.randomUUID(), n.id, added.id, out, in));
                    done = true;
                }
            }
            if (!done) linkToDrawer(added, false, in);
        }
        for (int out = 0; out < added.outputs.size(); out++) {
            boolean done = false;
            for (final Node n : graph.getNodes()) {
                if (n == added || done) continue;
                final int in = graph.findCompatibleInput(added, out, n);
                if (in < 0 || hasSupply(n, in)) continue;
                graph.addEdge(new Edge(UUID.randomUUID(), added.id, n.id, out, in));
                done = true;
            }
            if (!done) linkToDrawer(added, true, out);
        }
    }

    private boolean hasSupply(final Node node, final int input) {
        for (final Edge e : graph.getEdges()) {
            if (e.targetNodeId.equals(node.id) && e.targetInputIndex == input) return true;
        }
        return graph.drawerAt(node.id, input, true) != null;
    }

    /** Links a port to a drawer already on the board for the same resource and direction, if there is one. */
    private void linkToDrawer(final Node node, final boolean output, final int port) {
        final List<Port<?>> ports = output ? node.outputs : node.inputs;
        final String key = Resources.key(ports.get(port));
        if (key.isEmpty()) return;
        for (final Drawer d : graph.getDrawers()) {
            if (d.getKind()
                .linksInputs() == output || !key.equals(d.getResourceKey())) continue;
            graph.linkDrawer(d.getId(), new Drawer.Link(node.id, port));
            return;
        }
    }

    // region Wires and drawers (each one undoable, saved, re-solved)

    /** Wires an output to an input; drawing a wire that already exists removes it instead. */
    public void connect(final UUID from, final int output, final UUID to, final int input) {
        edit(() -> {
            for (final Edge e : new ArrayList<>(graph.getEdges())) {
                if (e.sourceNodeId.equals(from) && e.sourceOutputIndex == output
                    && e.targetNodeId.equals(to)
                    && e.targetInputIndex == input) {
                    graph.removeEdge(e.id);
                    return;
                }
            }
            graph.addEdge(new Edge(UUID.randomUUID(), from, to, output, input));
        });
    }

    public void deleteEdge(final UUID edgeId) {
        edit(() -> graph.removeEdge(edgeId));
    }

    /**
     * A port dragged onto another card: an output wires into the first input there that takes it, an input takes the
     * first output there that fits. False when nothing on that card matches.
     */
    public boolean dropPortOnCard(final UUID fromNode, final boolean output, final int port, final UUID toNode) {
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

    /** A port dragged onto a drawer: linked when the drawer holds the same resource on the same side. */
    public boolean dropPortOnDrawer(final UUID fromNode, final boolean output, final int port, final Drawer drawer) {
        final Node node = graph.nodes.get(fromNode);
        if (node == null || drawer.getKind()
            .linksInputs() == output) return false;
        final String key = Resources.key((output ? node.outputs : node.inputs).get(port));
        if (!key.equals(drawer.getResourceKey())) return false;
        edit(() -> {
            if (drawer.isLinked(node.id, port)) graph.unlinkDrawer(drawer.getId(), new Drawer.Link(node.id, port));
            else graph.linkDrawer(drawer.getId(), new Drawer.Link(node.id, port));
        });
        return true;
    }

    /**
     * A port dragged onto empty board: a new drawer there, linked to it. An input makes a source (it sits to the left
     * of the drop point), an output a product (to the right).
     */
    public void dropPortOnBoard(final UUID fromNode, final boolean output, final int port, final int worldX,
        final int worldY) {
        final Node node = graph.nodes.get(fromNode);
        if (node == null) return;
        final Port<?> p = (output ? node.outputs : node.inputs).get(port);
        final Drawer drawer = new Drawer(output ? Drawer.Kind.PRODUCT : Drawer.Kind.SOURCE, Resources.key(p));
        drawer.setLabel(p.getDisplayName());
        drawer.setX(output ? worldX : worldX - DrawerCard.W);
        // Dropped nearly level with the port: make it level, so the wire runs straight instead of jogging.
        final CardModel card = models.get(fromNode);
        final int portY = card == null ? worldY : node.y + new CardLayout(card).anchorY(output, port);
        drawer.setY((Math.abs(worldY - portY) <= 24 ? portY : worldY) - DrawerCard.ANCHOR_Y);
        edit(() -> {
            graph.addDrawer(drawer);
            graph.linkDrawer(drawer.getId(), new Drawer.Link(node.id, port));
        });
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
                y = card.node.y + new CardLayout(card).anchorY(!source, i) - DrawerCard.ANCHOR_Y;
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
        for (int tries = 0; tries < 100 && overlapsAnything(x, clear, DrawerCard.W, DrawerCard.H, null); tries++)
            clear += 20;
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
    }

    public void unlinkDrawer(final Drawer drawer, final Drawer.Link link) {
        edit(() -> graph.unlinkDrawer(drawer.getId(), link));
    }

    public void deleteDrawer(final Drawer drawer) {
        edit(() -> graph.removeDrawer(drawer.getId()));
    }

    /** Product, byproduct, trash, product again; a source stays a source. */
    public void cycleDrawer(final Drawer drawer) {
        edit(
            () -> drawer.setKind(
                drawer.getKind()
                    .next()));
    }

    public void setDrawerRule(final Drawer drawer, final Drawer.Rule rule) {
        edit(() -> drawer.setRule(rule));
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
    }

    // endregion

    private static final int GAP = 80;

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

    /**
     * Places a new card next to what it was wired to: left of the first card it feeds, else right of the first card
     * that feeds it, else right of everything on the board. Then moves it down until it overlaps nothing.
     */
    private void placeBesideNeighbours(final Node node) {
        Node feeds = null, fedBy = null;
        for (final Edge e : graph.getEdges()) {
            if (feeds == null && e.sourceNodeId.equals(node.id)) feeds = graph.nodes.get(e.targetNodeId);
            if (fedBy == null && e.targetNodeId.equals(node.id)) fedBy = graph.nodes.get(e.sourceNodeId);
        }
        if (feeds != null) {
            node.x = feeds.x - CardLayout.W - GAP;
            node.y = feeds.y;
        } else if (fedBy != null) {
            node.x = fedBy.x + CardLayout.W + GAP;
            node.y = fedBy.y;
        } else {
            int right = Integer.MIN_VALUE, top = Integer.MAX_VALUE;
            for (final Node n : graph.getNodes()) {
                if (n == node) continue;
                right = Math.max(right, n.x + CardLayout.W);
                top = Math.min(top, n.y);
            }
            for (final Drawer d : graph.getDrawers()) {
                right = Math.max(right, d.getX() + DrawerCard.W);
                top = Math.min(top, d.getY());
            }
            node.x = right == Integer.MIN_VALUE ? 40 : right + GAP;
            node.y = top == Integer.MAX_VALUE ? 40 : top;
        }
        for (int tries = 0; tries < 50 && overlapsAnything(node); tries++) node.y += 40;
    }

    /** Rough card height from its port count, for placement before the card has been drawn. */
    private static int estimatedHeight(final Node node) {
        final int rows = Math.max(1, Math.max(node.inputs.size(), node.outputs.size()));
        return CardLayout.RAILS_Y + Math.max(rows * CardLayout.ROW, CardLayout.PICTURE_MIN) + 85;
    }

    private boolean overlapsAnything(final Node node) {
        return overlapsAnything(node.x, node.y, CardLayout.W, estimatedHeight(node), node);
    }

    /** Whether a box, with a margin, overlaps any card or drawer on the board other than {@code self}. */
    private boolean overlapsAnything(final int x, final int y, final int w, final int h, final Object self) {
        final int m = 16;
        for (final Node n : graph.getNodes()) {
            if (n != self && boxesOverlap(x, y, w, h, n.x, n.y, CardLayout.W, estimatedHeight(n), m)) return true;
        }
        for (final Drawer d : graph.getDrawers()) {
            if (d != self && boxesOverlap(x, y, w, h, d.getX(), d.getY(), DrawerCard.W, DrawerCard.H, m)) return true;
        }
        return false;
    }

    private static boolean boxesOverlap(final int ax, final int ay, final int aw, final int ah, final int bx,
        final int by, final int bw, final int bh, final int margin) {
        return ax < bx + bw + margin && bx < ax + aw + margin && ay < by + bh + margin && by < ay + ah + margin;
    }

    // region Copy and paste

    /** A copied card: its recipe and settings, and where it sat from the copy's top-left. */
    private record ClipNode(codechicken.nei.recipe.Recipe.RecipeId recipe, Map<String, Object> settings,
        String machineName, boolean fixed, int dx, int dy) {}

    /** A copied drawer, and the copied cards' ports it was linked to, as {card index in the copy, port}. */
    private record ClipDrawer(Drawer.Kind kind, String key, String label, Drawer.Rule rule, double rate, int dx, int dy,
        List<int[]> links) {}

    /** A wire between two copied cards, by their index in the copy. */
    private record ClipEdge(int from, int output, int to, int input) {}

    private record Clip(List<ClipNode> nodes, List<ClipDrawer> drawers, List<ClipEdge> edges) {}

    /** The last copy, shared by every plan, so a group copied from one plan pastes into another. */
    private static Clip clipboard;

    /** Copies the selected cards and drawers with the wires among them; false when nothing is selected. */
    public boolean copySelection() {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE;
        final List<Node> nodes = new ArrayList<>();
        final List<Drawer> drawers = new ArrayList<>();
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
        }
        if (nodes.isEmpty() && drawers.isEmpty()) return false;
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
                    n.y - y0));
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
        clipboard = new Clip(clipNodes, clipDrawers, edges);
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
        });
        selection.clear();
        for (final Node n : made) if (n != null) selection.add(n.id);
        for (final Drawer d : madeDrawers) selection.add(d.getId());
        return List.copyOf(selection);
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
        if (!add) {
            selection.clear();
            selection.add(id);
        } else if (!selection.remove(id)) selection.add(id);
    }

    public boolean isSelected(final UUID id) {
        return selection.contains(id);
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

    /** Every card and drawer on the board. */
    public void selectAll() {
        selection.clear();
        selection.addAll(graph.nodes.keySet());
        selection.addAll(graph.drawers.keySet());
    }

    /** Deletes every selected card and drawer as one undoable step. */
    public void deleteSelected() {
        if (selection.isEmpty()) return;
        // A shared machine goes whole: every recipe on its card.
        final List<UUID> ids = new ArrayList<>();
        for (final UUID id : selection) for (final UUID s : sectionsOf(id)) if (!ids.contains(s)) ids.add(s);
        selection.clear();
        edit(() -> {
            for (final UUID id : ids) {
                if (graph.nodes.containsKey(id)) graph.removeNode(id);
                else if (graph.drawers.containsKey(id)) graph.removeDrawer(id);
            }
        });
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
        if (canUndo()) adopt(
            PlanAPI.undoHistory(graph)
                .undo(graph));
    }

    public void redo() {
        if (canRedo()) adopt(
            PlanAPI.undoHistory(graph)
                .redo(graph));
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
        PlanAPI.save();
    }

    public void addSlot() {
        final Plan plan = Plan.getInstance();
        final int size = plan.getGraphs()
            .size();
        plan.getGraphs()
            .add(new Graph("Plan " + (size + 1)));
        plan.setActiveIndex(size);
        PlanAPI.save();
    }

    public void renameSlot(final int index, final String name) {
        if (index < 0 || index >= slots().size() || name == null || name.isBlank()) return;
        slots().get(index)
            .setName(name.trim());
        PlanAPI.save();
    }

    /** Removes a plan slot; the last one stays. */
    public void deleteSlot(final int index) {
        final Plan plan = Plan.getInstance();
        final int size = plan.getGraphs()
            .size();
        if (size <= 1 || index < 0 || index >= size) return;
        plan.getGraphs()
            .remove(index);
        if (plan.getActiveIndex() >= index && plan.getActiveIndex() > 0) plan.setActiveIndex(plan.getActiveIndex() - 1);
        PlanAPI.save();
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
    private Map<UUID, com.sbancuz.plannh.data.flowchart.MachineGroup> sharedBySection = Map.of();

    /** The settings that belong to the machine rather than the recipe: a shared card keeps them the same throughout. */
    private static final Set<String> RECIPE_SETTINGS = Set.of("duration_ticks", "recipe_heat", "machines");

    /** The shared machine a recipe is a section of, or null when its card is its own. */
    public com.sbancuz.plannh.data.flowchart.MachineGroup sharedOf(final UUID nodeId) {
        return sharedBySection.get(nodeId);
    }

    /** The recipes on the card a node is on, top first: its shared machine's sections, or just itself. */
    public List<UUID> sectionsOf(final UUID nodeId) {
        final com.sbancuz.plannh.data.flowchart.MachineGroup g = sharedBySection.get(nodeId);
        return g == null ? List.of(nodeId) : List.copyOf(g.getSections());
    }

    /** The node whose card a node is drawn on: the first section of its shared machine, or itself. */
    public UUID hostOf(final UUID nodeId) {
        final com.sbancuz.plannh.data.flowchart.MachineGroup g = sharedBySection.get(nodeId);
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
        final Map<UUID, com.sbancuz.plannh.data.flowchart.MachineGroup> index = new HashMap<>();
        for (final com.sbancuz.plannh.data.flowchart.Group group : new ArrayList<>(graph.getGroups())) {
            if (!(group instanceof final com.sbancuz.plannh.data.flowchart.MachineGroup g) || g.getSections()
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
        if (!canCombine(ids)) return null;
        final List<UUID> cards = cardsIn(ids);
        cards.sort(
            java.util.Comparator.comparingInt((UUID id) -> graph.nodes.get(id).y)
                .thenComparingInt(id -> graph.nodes.get(id).x));
        final UUID hostId = cards.get(0);
        final List<UUID> sections = allSections(cards);
        final List<ItemStack> common = commonMachines(sections);
        edit(() -> {
            final Node host = graph.nodes.get(hostId);
            com.sbancuz.plannh.data.flowchart.MachineGroup g = sharedOf(hostId);
            final int capacity = g != null ? g.getMachineCapacity()
                : host.isMachineCountFixed() ? host.machineConfig.getMachineCount() : 0;
            final boolean pinned = g != null ? g.isPinned() : host.isMachineCountFixed();
            for (final UUID card : cards) {
                final com.sbancuz.plannh.data.flowchart.MachineGroup old = sharedOf(card);
                if (old != null) graph.removeGroup(old.getId());
            }
            g = new com.sbancuz.plannh.data.flowchart.MachineGroup();
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
        final com.sbancuz.plannh.data.flowchart.MachineGroup g = sharedOf(node.id);
        if (g != null) copyMachineSettings(node, g.getSections());
    }

    /** Takes a recipe off its shared machine: the recipe and its wires go, as Factory Flow's X does. */
    public void removeSection(final UUID nodeId) {
        final com.sbancuz.plannh.data.flowchart.MachineGroup g = sharedOf(nodeId);
        if (g == null) return;
        edit(() -> {
            g.removeSection(nodeId);
            graph.removeNode(nodeId);
            if (!g.isShared()) graph.removeGroup(g.getId());
        });
    }

    /** Moves a recipe up (-1) or down (+1) on its shared card; the first one is the host. */
    public void moveSection(final UUID nodeId, final int step) {
        final com.sbancuz.plannh.data.flowchart.MachineGroup g = sharedOf(nodeId);
        if (g == null) return;
        final List<UUID> sections = g.getSections();
        final int i = sections.indexOf(nodeId), j = i + step;
        if (i < 0 || j < 0 || j >= sections.size()) return;
        editLayout(() -> java.util.Collections.swap(sections, i, j));
    }

    /** Pins a shared machine's count (zero unpins): its recipes' machines then add up to exactly that. */
    public void pinShared(final com.sbancuz.plannh.data.flowchart.MachineGroup g, final double count) {
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
        if (ref != null && com.sbancuz.plannh.ui.Planner.browse(ref.handler.getOverlayIdentifier())) return true;
        if (m.machineStack != null && com.sbancuz.plannh.ui.Planner.lookUp(m.machineStack.copy(), true)) return true;
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
        com.sbancuz.plannh.data.flowchart.MachineGroup g = sharedOf(hostId);
        if (g == null) {
            g = new com.sbancuz.plannh.data.flowchart.MachineGroup();
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

    public void setVoltage(final Node node, final String tier) {
        edit(() -> {
            node.machineConfig.setString("voltage", tier);
            syncShared(node);
        });
    }

    public void setSetting(final Node node, final String key, final Object value) {
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
            node.machineName = CardDefaults.itemKey(machine);
            if (gregtech) {
                final GtMachines.Kind kind = GtMachines.of(machine);
                if (kind != null) {
                    node.machineConfig.setBoolean("gt_multiblock", kind.multiblock());
                    if (!kind.multiblock() && kind.tier() >= 0 && kind.tier() < CardDefaults.TIERS.length) {
                        node.machineConfig.setString("voltage", CardDefaults.TIERS[kind.tier()]);
                    }
                }
            }
            syncShared(node);
        });
    }

    /**
     * Pins the machine count (gold on the card); zero or less unpins and lets the plan decide. On a shared machine the
     * count is the machine's: its recipes' machines add up to it.
     */
    public void pin(final Node node, final double count) {
        final com.sbancuz.plannh.data.flowchart.MachineGroup g = sharedOf(node.id);
        if (g != null) {
            pinShared(g, count);
            return;
        }
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
    }

    /**
     * A copy of the card beside it, with the same machine and settings, not wired; a shared machine with its recipes.
     */
    public void cloneNode(final Node node) {
        final List<Node> copies = new ArrayList<>();
        for (final UUID s : sectionsOf(node.id)) {
            final Node from = graph.nodes.get(s);
            final RecipeHandlerRef ref = from == null ? null : RecipeHandlerRef.of(from.recipeId);
            if (ref == null) return;
            final Node copy = new Node(ref.handler, ref.recipeIndex, node.x + 24, node.y + 24);
            copy.machineConfig.copySettingsFrom(from.machineConfig);
            copy.machineName = from.machineName;
            copies.add(copy);
        }
        final com.sbancuz.plannh.data.flowchart.MachineGroup shared = sharedOf(node.id);
        edit(() -> {
            for (final Node copy : copies) graph.addNode(copy);
            if (shared == null) return;
            final com.sbancuz.plannh.data.flowchart.MachineGroup g = new com.sbancuz.plannh.data.flowchart.MachineGroup();
            g.setHeader("Shared machine");
            for (final Node copy : copies) g.addSection(copy.id);
            g.setMachineCapacity(shared.getMachineCapacity());
            g.setPinned(shared.isPinned());
            graph.groups.put(g.getId(), g);
        });
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
        if (disarmOnTick) {
            disarmOnTick = false;
            pendingLookup = null;
            addSectionTo = null;
        }
        final Graph active = Plan.getActiveGraph();
        if (active != graph) {
            graph = active;
            seenVersion = Long.MIN_VALUE;
        }
        try {
            solver.request(graph.solveVersion(), () -> SolveInput.of(graph, BalanceMode.AUTO, null));
        } catch (final RuntimeException e) {
            PlanNH.LOG.warn("Could not snapshot the plan for solving", e);
        }
        final SolveService.Result latest = solver.latest();
        if (latest != null && latest != lastResult) {
            lastResult = latest;
            if (latest.error() != null) PlanNH.LOG.warn("Solve failed", latest.error());
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
                PlanNH.LOG.warn("Card for {} failed", node.machineName, e);
            }
        }
        final Map<UUID, DrawerModel> nextDrawers = new LinkedHashMap<>();
        for (final Drawer drawer : graph.getDrawers()) nextDrawers.put(drawer.getId(), DrawerModel.of(drawer, result));
        if (!next.keySet()
            .equals(models.keySet())
            || !nextDrawers.keySet()
                .equals(drawerModels.keySet()))
            structure++;
        models = next;
        drawerModels = nextDrawers;
        notices = buildNotices(next);
        totals = buildTotals(next);
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
        int amps, boolean multiblock, boolean gregtech, double euPerTick, boolean tooLow) {}

    /**
     * The overview, Factory Flow's resources column: per resource what the plan needs from outside (deficit), what it
     * gives out (surplus) and what it makes and uses itself (internal); its power; one line per card to build.
     */
    public record Totals(List<TotalLine> inputs, List<TotalLine> outputs, List<TotalLine> internal, double euPerTick,
        List<MachineLine> machines) {

        static final Totals EMPTY = new Totals(List.of(), List.of(), List.of(), 0, List.of());
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
        double eu = 0;
        final List<MachineLine> machines = new ArrayList<>();
        for (final CardModel card : cards.values()) {
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
                    card.tierTooLow()));
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
        return new Totals(in, out, internal, eu, machines);
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
     * Adds the plan on the clipboard as a new slot: a Factory Flow link, code or JSON, or a PlanNH share code.
     * Says what happened in a notice.
     */
    public void pastePlan() {
        final String text = net.minecraft.client.gui.GuiScreen.getClipboardString();
        if (text == null || text.isBlank()) {
            flash(Severity.WARN, "The clipboard is empty: copy a Factory Flow plan link or code first");
            return;
        }
        final Graph own = PlanAPI.importFromClipboard();
        if (own != null) {
            com.sbancuz.plannh.importer.game.FactoryFlowImport.addAsSlot(own);
            flash(Severity.INFO, "Pasted '" + own.getName() + "'");
            return;
        }
        try {
            final com.sbancuz.plannh.importer.FfConverter.Result result = com.sbancuz.plannh.importer.game.FactoryFlowImport
                .importAsSlot(text);
            final com.sbancuz.plannh.importer.ImportReport report = result.report();
            final int missing = report.entries(com.sbancuz.plannh.importer.ImportReport.Kind.UNMATCHED)
                .size();
            final String name = result.graph()
                .getName();
            flash(missing == 0 ? Severity.INFO : Severity.WARN, "Imported '" + name + "': " + report.summary());
        } catch (final RuntimeException e) {
            flash(Severity.WARN, "The clipboard does not hold a plan (a Factory Flow link or code, or a PlanNH code)");
            com.sbancuz.plannh.PlanNH.LOG.info("Paste plan failed", e);
        }
    }

    /** Copies a slot's PlanNH share code to the clipboard. */
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
                            "Nothing to solve for: set a rate on a product, or pin a machine count.",
                            List.of()));
                }
            }
            for (final DrawerReadout.Shortfall s : result.drawers()
                .shortfalls()) {
                final DrawerModel d = drawerModels.get(s.drawer());
                final String label = d == null ? "a product" : d.label;
                final boolean fluid = d != null && d.isFluid();
                final List<UUID> focus = new ArrayList<>();
                focus.add(s.drawer());
                focus.addAll(s.limitingNodes());
                focus.addAll(s.limitingDrawers());
                out.add(
                    new Notice(
                        Severity.WARN,
                        "Can't reach " + label
                            + ": "
                            + Fmt.rate(s.reachable(), rateUnit, fluid)
                            + " of "
                            + Fmt.rate(s.target(), rateUnit, fluid),
                        focus));
            }
            for (final Note note : result.notes()) {
                // An empty plan is not an error: the board says how to start one.
                if (note.severity() == Severity.INFO || note.message() == SolverMessage.DRAWER_UNMET
                    || note.message() == SolverMessage.DRAWER_LIMITED_BY
                    || note.message() == SolverMessage.EMPTY_GRAPH) continue;
                out.add(new Notice(note.severity(), render(note), idsIn(note)));
            }
        } else if (lastResult != null && lastResult.errorNote() != null) {
            out.add(new Notice(Severity.ERROR, render(lastResult.errorNote()), List.of()));
        }
        final List<UUID> unwiredCards = new ArrayList<>();
        int unwired = 0;
        for (final CardModel card : cards.values()) {
            int here = 0;
            for (final CardModel.PortView p : card.inputs) if (!p.wired()) here++;
            for (final CardModel.PortView p : card.outputs) if (!p.wired()) here++;
            if (here > 0) unwiredCards.add(card.node.id);
            unwired += here;
        }
        if (unwired > 0) out.add(
            new Notice(Severity.WARN, "Not wired up: " + unwired + (unwired == 1 ? " port" : " ports"), unwiredCards));
        return out;
    }

    private static String render(final Note note) {
        try {
            return note.render();
        } catch (final RuntimeException e) {
            return note.describe();
        }
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
