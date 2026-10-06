package com.sbancuz.plannh.ui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sbancuz.plannh.PlanNH;
import com.sbancuz.plannh.api.PlanAPI;
import com.sbancuz.plannh.data.flowchart.Edge;
import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.data.flowchart.Plan;
import com.sbancuz.plannh.data.flowchart.Port;
import com.sbancuz.plannh.data.flowchart.balancer.BalanceMode;
import com.sbancuz.plannh.data.flowchart.balancer.BalanceResult;
import com.sbancuz.plannh.data.flowchart.balancer.Balancer;
import com.sbancuz.plannh.nei.NodeLookupContext;
import com.sbancuz.plannh.ui.card.CardLayout;
import com.sbancuz.plannh.ui.card.CardModel;
import com.sbancuz.plannh.ui.theme.Fmt;

import codechicken.nei.recipe.IRecipeHandler;

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
    /** Bumped when the set of cards changes, so the canvas knows to rebuild its widgets. */
    private int structure;
    private Fmt.RateUnit rateUnit = Fmt.RateUnit.SECOND;
    private NodeLookupContext pendingLookup;

    BoardSession() {
        graph = Plan.getActiveGraph();
        current = this;
    }

    /** The session of the open board, or null when the planner is closed. */
    public static BoardSession current() {
        return current;
    }

    void close() {
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

    public BalanceResult result() {
        return result;
    }

    public int structure() {
        return structure;
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
        graph.markDirty();
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
        final Node node = new Node(handler, recipeIndex, 0, 0);
        edit(() -> {
            graph.addNode(node);
            final Node from = origin == null ? null : graph.nodes.get(origin.nodeId());
            if (from != null && wireToOrigin(node, from, origin)) {
                node.x = origin.output() ? from.x + CardLayout.W + GAP : from.x - CardLayout.W - GAP;
                node.y = from.y;
            } else {
                placeRightOfEverything(node);
            }
        });
        return node;
    }

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

    private void placeRightOfEverything(final Node node) {
        int right = Integer.MIN_VALUE, top = Integer.MAX_VALUE;
        for (final Node n : graph.getNodes()) {
            if (n == node) continue;
            right = Math.max(right, n.x + CardLayout.W);
            top = Math.min(top, n.y);
        }
        node.x = right == Integer.MIN_VALUE ? 40 : right + GAP;
        node.y = top == Integer.MAX_VALUE ? 40 : top;
    }

    /** Called every client tick while the board is open. Never from drawing. */
    @SuppressWarnings("deprecation")
    public void tick() {
        final Graph active = Plan.getActiveGraph();
        if (active != graph) {
            graph = active;
            seenVersion = Long.MIN_VALUE;
        }
        if (graph.version() == seenVersion) return;
        seenVersion = graph.version();
        try {
            result = Balancer.balance(graph, BalanceMode.AUTO);
        } catch (final RuntimeException e) {
            PlanNH.LOG.warn("Solve failed", e);
            result = null;
        }
        final Map<UUID, CardModel> next = new LinkedHashMap<>();
        for (final Node node : graph.getNodes()) {
            try {
                next.put(
                    node.id,
                    CardModel.of(
                        node,
                        result == null ? null
                            : result.nodeBalances()
                                .get(node.id)));
            } catch (final RuntimeException e) {
                PlanNH.LOG.warn("Card for {} failed", node.machineName, e);
            }
        }
        if (!next.keySet()
            .equals(models.keySet())) structure++;
        models = next;
    }
}
