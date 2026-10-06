package com.sbancuz.plannh.data.flowchart;

import java.util.Collection;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import com.sbancuz.plannh.data.flowchart.balancer.BalanceMode;
import com.sbancuz.plannh.data.flowchart.balancer.BalanceResult;
import com.sbancuz.plannh.data.flowchart.balancer.BalanceView;
import com.sbancuz.plannh.data.flowchart.balancer.ChoiceKey;

import lombok.Getter;
import lombok.Setter;

public class Graph {

    // TODO make these use getters
    /**
     * Sorted, so the graph hands its contents back in id order and every consumer that needs a
     * reproducible answer gets one without sorting first - the solver, the serializer, the router
     * and the layout all read these directly.
     */
    public final SortedMap<UUID, Node> nodes = new TreeMap<>();
    public final SortedMap<UUID, Edge> edges = new TreeMap<>();
    public final SortedMap<UUID, Note> notes = new TreeMap<>();
    public final SortedMap<UUID, Group> groups = new TreeMap<>();
    /**
     * The board's drawers. Read through {@link #getDrawers()}; change membership and links through
     * {@link #addDrawer}, {@link #removeDrawer}, {@link #linkDrawer} and {@link #unlinkDrawer} so the
     * version moves.
     */
    public final SortedMap<UUID, Drawer> drawers = new TreeMap<>();

    @Getter
    @Setter
    private String name;

    @Getter
    @Setter
    private float zoom = 1f;
    @Getter
    @Setter
    private float panX;
    @Getter
    @Setter
    private float panY;
    @Getter
    @Setter
    private boolean snapToGrid;

    @Getter
    private BalanceMode balanceMode = BalanceMode.AUTO;

    /**
     * Which slot this graph is, for state kept per slot outside the graph (the undo history, see
     * {@link UndoHistories}). A graph an undo or redo puts in a slot's place takes over the slot of
     * the graph it replaces; any other graph is a slot of its own.
     */
    private transient UUID slot = UUID.randomUUID();

    /**
     * The display view, built on first ask after a solve rather than with it: the canvas wants the
     * boundary every frame and never the choices, which the summary reads straight from the solve.
     */
    private List<BalanceView.Boundary> boundaryView = null;

    /** Shared by every graph, so a version names one state of one graph across the whole client. */
    private static final AtomicLong VERSIONS = new AtomicLong();

    /**
     * Moves on every change, layout included. Drawn from one client-wide counter, so it only ever
     * grows and no two graphs - a slot and the graph an undo put in its place, say - ever share a
     * value: equal versions mean the same graph in the same state. Transient because a loaded plan
     * starts cold and re-derives everything on first ask.
     */
    private transient long version = VERSIONS.incrementAndGet();

    /**
     * Moves on every change that can change the solve - not on a move or a resize. The solve caches
     * (and a {@code SolveService} request) key on this, so dragging a card never re-solves.
     */
    private transient long solveVersion = version;

    /** The solve version the solve caches above were built from. */
    private transient long solvedAt = -1;

    public Graph() {
        this.name = "";
    }

    public Graph(final String name) {
        this.name = name;
    }

    /** Every change moves this, layout included; equal values mean the same graph, unchanged. */
    public long version() {
        return version;
    }

    /** Every change that can change the solve moves this; layout-only edits do not. */
    public long solveVersion() {
        return solveVersion;
    }

    /** The slot this graph is; see {@link UndoHistories}. */
    public UUID slot() {
        return slot;
    }

    /** This graph takes over {@code replaced}'s slot: it is that slot's state after an undo or redo. */
    void takeSlotOf(final Graph replaced) {
        this.slot = replaced.slot;
    }

    private void bumpVersion() {
        version = VERSIONS.incrementAndGet();
        solveVersion = version;
    }

    /**
     * Records an edit the graph cannot see for itself that may change the solve: a machine setting,
     * a pinned machine count or a target rate, a drawer's rule, rate, kind or label, a machine
     * group's capacity or members, a node's recipe. Moves both {@link #version()} and
     * {@link #solveVersion()}. Edits made through the graph's own methods (nodes, edges, drawers,
     * links, balance mode, excess choice) already do this.
     */
    public void touch() {
        bumpVersion();
    }

    /**
     * Records a layout-only edit: something moved or was resized, a note's text changed. Moves
     * {@link #version()} but not {@link #solveVersion()}, so nothing is re-solved.
     */
    public void touchLayout() {
        version = VERSIONS.incrementAndGet();
    }

    /** @deprecated Use {@link #touch()} (or {@link #touchLayout()} for a layout-only edit). */
    @Deprecated
    public void markDirty() {
        touch();
    }

    public ChoiceKey getExcessChoice() {
        return Plan.getInstance()
            .getSummary()
            .getExcessChoice();
    }

    public void setExcessChoice(final ChoiceKey choice) {
        Plan.getInstance()
            .getSummary()
            .setExcessChoice(choice);
        bumpVersion();
    }

    public void setBalanceMode(final BalanceMode mode) {
        balanceMode = mode;
        bumpVersion();
    }

    /** Removes the node, every edge touching it and every drawer link to it. */
    public void removeNode(final UUID id) {
        nodes.remove(id);
        edges.values()
            .removeIf(e -> e.sourceNodeId.equals(id) || e.targetNodeId.equals(id));
        for (final Drawer drawer : drawers.values()) {
            drawer.removeLinksTo(id);
        }
        bumpVersion();
    }

    // ── Drawers ──

    public Collection<Drawer> getDrawers() {
        return drawers.values();
    }

    @Nullable
    public Drawer getDrawer(final UUID id) {
        return drawers.get(id);
    }

    /**
     * Adds (or replaces, by id) a drawer. Its links are taken as they are; a port another drawer of
     * the same direction already holds is taken away from that drawer, as {@link #linkDrawer} does.
     */
    public void addDrawer(final Drawer drawer) {
        drawers.put(drawer.getId(), drawer);
        for (final Drawer.Link link : drawer.getLinks()) {
            releasePort(drawer, link);
        }
        bumpVersion();
    }

    public void removeDrawer(final UUID id) {
        drawers.remove(id);
        bumpVersion();
    }

    /**
     * Attaches a node port to a drawer: an input port for a source, an output port otherwise. A port
     * belongs to at most one drawer per direction, so any other drawer holding it lets go. Returns
     * false (and changes nothing) when there is no such drawer.
     */
    public boolean linkDrawer(final UUID drawerId, final Drawer.Link link) {
        final Drawer drawer = drawers.get(drawerId);
        if (drawer == null) return false;
        releasePort(drawer, link);
        drawer.addLink(link);
        bumpVersion();
        return true;
    }

    /** Detaches a node port from a drawer; false when the drawer did not hold it. */
    public boolean unlinkDrawer(final UUID drawerId, final Drawer.Link link) {
        final Drawer drawer = drawers.get(drawerId);
        if (drawer == null || !drawer.removeLink(link)) return false;
        bumpVersion();
        return true;
    }

    /** The drawer holding a node port in the given direction, or null when the port has none. */
    @Nullable
    public Drawer drawerAt(final UUID nodeId, final int portIndex, final boolean input) {
        for (final Drawer drawer : drawers.values()) {
            if (drawer.holds(nodeId, portIndex, input)) return drawer;
        }
        return null;
    }

    /** Takes {@code link} away from every drawer but {@code keeper} that links the same direction. */
    private void releasePort(final Drawer keeper, final Drawer.Link link) {
        final boolean input = keeper.getKind()
            .linksInputs();
        for (final Drawer other : drawers.values()) {
            if (other != keeper && other.getKind()
                .linksInputs() == input) other.removeLink(link);
        }
    }

    /**
     * The balance, solved on the calling thread when the solve version moved since the last ask.
     * The legacy canvas reads this; the board solves through a {@code SolveService} instead.
     */
    public BalanceResult balance() {
        if (solvedAt != solveVersion) {
            Plan.getInstance()
                .getSummary()
                .recompute(this);
            solvedAt = solveVersion;
            boundaryView = null;
        }
        return Plan.getInstance()
            .getSummary()
            .balance();
    }

    /**
     * Everything crossing the chart's boundary. Held from solve to solve because the canvas asks
     * once per frame and the answer only moves when the chart does.
     */
    public List<BalanceView.Boundary> boundary() {
        balance(); // drops a view built before the last edit
        if (boundaryView == null) boundaryView = BalanceView.boundary(this);
        return boundaryView;
    }

    public Collection<Node> getNodes() {
        return nodes.values();
    }

    public void addNode(final Node node) {
        nodes.put(node.id, node);
        bumpVersion();
    }

    public Collection<Edge> getEdges() {
        return edges.values();
    }

    public void addEdge(final Edge edge) {
        // A source-port/target-port pair carries at most one edge: re-wiring it replaces the
        // existing edge instead of stacking a duplicate.
        edges.values()
            .removeIf(
                e -> e.sourceNodeId.equals(edge.sourceNodeId) && e.sourceOutputIndex == edge.sourceOutputIndex
                    && e.targetNodeId.equals(edge.targetNodeId)
                    && e.targetInputIndex == edge.targetInputIndex);
        edges.put(edge.id, edge);
        bumpVersion();
    }

    public void removeEdge(final UUID id) {
        edges.remove(id);
        bumpVersion();
    }

    /**
     * First input port of {@code dst} that accepts {@code src}'s given output; -1 when none is
     * compatible.
     */
    public int findCompatibleInput(final Node src, final int srcOutIdx, final Node dst) {
        if (src == dst || srcOutIdx < 0 || srcOutIdx >= src.outputs.size()) return -1;
        final Port<?> out = src.outputs.get(srcOutIdx);
        for (int i = 0; i < dst.inputs.size(); i++) {
            if (out.canConnect(dst.inputs.get(i))) return i;
        }
        return -1;
    }

    /** Removes a group; a machine group's capacity is a solve constraint, so this moves the version. */
    public void removeGroup(final UUID id) {
        groups.remove(id);
        bumpVersion();
    }

    public Collection<Group> getGroups() {
        return groups.values();
    }

    public Collection<Note> getNotes() {
        return notes.values();
    }
}
