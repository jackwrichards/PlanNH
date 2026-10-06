package com.sbancuz.plannh.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.item.ItemStack;

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
import com.sbancuz.plannh.ui.card.CardDefaults;
import com.sbancuz.plannh.ui.card.CardLayout;
import com.sbancuz.plannh.ui.card.CardModel;
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
    /** Bumped when the set of cards changes, so the canvas knows to rebuild its widgets. */
    private int structure;
    private Fmt.RateUnit rateUnit = Fmt.RateUnit.SECOND;
    private NodeLookupContext pendingLookup;
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
