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

import codechicken.nei.recipe.GuiCraftingRecipe;
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
    private final SolveService solver = new SolveService();
    private SolveService.Result lastResult;
    private UUID pendingReplace;

    BoardSession() {
        graph = Plan.getActiveGraph();
        current = this;
    }

    /** The session of the open board, or null when the planner is closed. */
    public static BoardSession current() {
        return current;
    }

    void close() {
        solver.close();
        PlanAPI.save();
        if (current == this) current = null;
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

    /**
     * Puts an NEI recipe on the board. When it came from a lookup on a port, it lands beside that card and only that
     * resource is wired; otherwise it lands to the right of everything already there.
     */
    public Node addRecipe(final IRecipeHandler handler, final int recipeIndex) {
        final NodeLookupContext origin = pendingLookup;
        pendingLookup = null;
        final UUID replacing = pendingReplace;
        pendingReplace = null;
        final Node node = new Node(handler, recipeIndex, 0, 0);
        CardDefaults.apply(node);
        final Node old = replacing == null ? null : graph.nodes.get(replacing);
        if (old != null) {
            edit(() -> replaceNode(old, node));
            return node;
        }
        edit(() -> {
            graph.addNode(node);
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
        for (int tries = 0; tries < 100 && overlapsAnything(x, y, DrawerCard.W, DrawerCard.H, null); tries++) y += 20;
        addDrawer(source ? Drawer.Kind.SOURCE : Drawer.Kind.PRODUCT, key, label, x, y);
    }

    /** A new, unlinked drawer for a resource at a world point (from an NEI drag). */
    public void addDrawer(final Drawer.Kind kind, final String resourceKey, final String label, final int x,
        final int y) {
        final Drawer drawer = new Drawer(kind, resourceKey);
        drawer.setLabel(label);
        drawer.setX(x);
        drawer.setY(y);
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
        return CardLayout.RAILS_Y + Math.max(rows * (CardLayout.ROW + CardLayout.ROW_GAP), CardLayout.PICTURE_MIN) + 70;
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
        final List<UUID> ids = new ArrayList<>(selection);
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

    // region Card edits (each one undoable, saved, re-solved)

    public void setVoltage(final Node node, final String tier) {
        edit(() -> node.machineConfig.setString("voltage", tier));
    }

    public void setSetting(final Node node, final String key, final Object value) {
        edit(() -> {
            if (value instanceof final Boolean b) node.machineConfig.setBoolean(key, b);
            else if (value instanceof final Integer i) node.machineConfig.setInt(key, i);
            else node.machineConfig.setString(key, String.valueOf(value));
        });
    }

    /** Picks which machine runs the recipe; GregTech single blocks bring their tier, multiblocks their options. */
    public void chooseMachine(final Node node, final ItemStack machine, final boolean gregtech) {
        edit(() -> {
            node.machineName = CardDefaults.itemKey(machine);
            if (!gregtech) return;
            final GtMachines.Kind kind = GtMachines.of(machine);
            if (kind == null) return;
            node.machineConfig.setBoolean("gt_multiblock", kind.multiblock());
            if (!kind.multiblock() && kind.tier() >= 0 && kind.tier() < CardDefaults.TIERS.length) {
                node.machineConfig.setString("voltage", CardDefaults.TIERS[kind.tier()]);
            }
        });
    }

    /** Pins the machine count (gold on the card); zero or less unpins and lets the plan decide. */
    public void pin(final Node node, final double count) {
        edit(() -> {
            if (count <= 0) {
                node.setMachineCountFixed(false);
                return;
            }
            node.machineConfig.setMachineCount(Math.max(1, (int) Math.round(count)));
            node.setMachineCountFixed(true);
        });
    }

    public void delete(final Node node) {
        edit(() -> graph.removeNode(node.id));
    }

    /** A copy of the card beside it, with the same machine and settings, not wired. */
    public void cloneNode(final Node node) {
        final RecipeHandlerRef ref = RecipeHandlerRef.of(node.recipeId);
        if (ref == null) return;
        final Node copy = new Node(ref.handler, ref.recipeIndex, node.x + 24, node.y + 24);
        edit(() -> {
            copy.machineConfig.copySettingsFrom(node.machineConfig);
            copy.machineName = node.machineName;
            graph.addNode(copy);
        });
    }

    /**
     * Opens NEI on what makes the card's main output; the recipe added from there with + takes this card's place and
     * keeps every wire that still fits.
     */
    public void beginReplace(final Node node, final ItemStack lookup) {
        pendingReplace = node.id;
        pendingLookup = null;
        if (lookup != null) GuiCraftingRecipe.openRecipeGui("item", lookup);
    }

    private void replaceNode(final Node old, final Node added) {
        added.x = old.x;
        added.y = old.y;
        // The tier is a choice about the line, so it carries over; machine, amps and coil come from the new recipe.
        if (CardModel.GT_PROFILE.equals(added.machineConfig.profileId)
            && CardModel.GT_PROFILE.equals(old.machineConfig.profileId)) {
            added.machineConfig.setString("voltage", CardDefaults.stringSetting(old.machineConfig, "voltage"));
        }
        if (old.isMachineCountFixed()) added.machineConfig.setMachineCount(old.machineConfig.getMachineCount());
        added.setMachineCountFixed(old.isMachineCountFixed());
        graph.addNode(added);
        for (final Edge e : new ArrayList<>(graph.getEdges())) {
            if (e.sourceNodeId.equals(old.id) && e.sourceOutputIndex < old.outputs.size()) {
                final Port<?> was = old.outputs.get(e.sourceOutputIndex);
                for (int out = 0; out < added.outputs.size(); out++) {
                    if (added.outputs.get(out)
                        .canConnect(was)) {
                        graph.addEdge(new Edge(UUID.randomUUID(), added.id, e.targetNodeId, out, e.targetInputIndex));
                        break;
                    }
                }
            } else if (e.targetNodeId.equals(old.id) && e.targetInputIndex < old.inputs.size()) {
                final Port<?> was = old.inputs.get(e.targetInputIndex);
                for (int in = 0; in < added.inputs.size(); in++) {
                    if (was.canConnect(added.inputs.get(in))) {
                        graph.addEdge(new Edge(UUID.randomUUID(), e.sourceNodeId, added.id, e.sourceOutputIndex, in));
                        break;
                    }
                }
            }
        }
        graph.removeNode(old.id);
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
        return notices;
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
