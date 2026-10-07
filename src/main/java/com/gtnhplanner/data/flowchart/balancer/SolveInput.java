package com.gtnhplanner.data.flowchart.balancer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.gtnhplanner.Config;
import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.effect.EffectResult;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Group;
import com.gtnhplanner.data.flowchart.MachineGroup;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.properties.RecipeProperty;

/**
 * Everything one solve reads, copied out of a {@link Graph} so the solve can run on another thread.
 *
 * <p>
 * Built on the client thread by {@link #of(Graph)}: that is where the machine effects are computed
 * (the GregTech overclock calculator keeps shared mutable state and writes into the node, so it
 * must never run anywhere else), where ingredient names are resolved, and where ports are grouped
 * into ingredients with {@link com.gtnhplanner.data.flowchart.Port#canConnect}. What comes out
 * is plain data - numbers, strings, UUIDs and enums in unmodifiable collections - so
 * {@link Balancer#solve(SolveInput)} touches no Minecraft, NEI, localization or {@code Plan} code
 * and a snapshot can be handed to any thread. Notes still carry keys and arguments; rendering
 * them ({@link Note#render()}) stays on the client.
 *
 * <p>
 * A snapshot never changes, and editing the graph afterwards does not reach it: submit a new one.
 *
 * @param mode       the balance mode to solve in.
 * @param choice     a stored excess choice to honour, or null for the solver's own answer.
 * @param machines   one per node, in the graph's (id) order.
 * @param edges      every drawn edge, in the graph's (id) order.
 * @param pools      the capped machine groups.
 * @param drawers    every drawer, in the graph's (id) order.
 * @param extentPins per-node extent pins in crafts per second, beyond what the nodes carry.
 */
public record SolveInput(BalanceMode mode, @Nullable ChoiceKey choice, List<Machine> machines, List<EdgeIn> edges,
    List<PoolIn> pools, List<DrawerIn> drawers, Map<UUID, Double> extentPins) {

    public SolveInput {
        machines = List.copyOf(machines);
        edges = List.copyOf(edges);
        pools = List.copyOf(pools);
        drawers = List.copyOf(drawers);
        extentPins = Collections.unmodifiableMap(new LinkedHashMap<>(extentPins));
    }

    /**
     * One port of a machine.
     *
     * @param amount     units per craft, before chance.
     * @param chance     the chance the units appear (1 for a plain port).
     * @param multiplier the node's own consumption / productivity multiplier on this port.
     * @param resource   the ingredient, as a number shared by every port of the same snapshot that
     *                   {@code canConnect}s to it; equal numbers mean the same ingredient.
     * @param name       the ingredient's display name, resolved when the snapshot was taken.
     * @param free       whether the pack gives this ingredient away ({@link Config#isFreeIngredient}).
     */
    public record PortIn(int amount, float chance, float multiplier, int resource, String name, boolean free) {}

    /**
     * One machine (node) with its effect already computed.
     *
     * @param durationTicks     ticks per craft after overclocking and tick modifiers.
     * @param energyPerTick     energy per tick of one craft.
     * @param throughputFactor  how many crafts one machine runs at once (parallels).
     * @param countFixed        whether the machine count is pinned.
     * @param machineCount      the configured machine count (the pin when {@code countFixed}).
     * @param targetOutputRates the node's target output rates, units/s by output index.
     * @param properties        the node's numeric recipe properties, for the per-run totals.
     */
    public record Machine(UUID id, String name, int durationTicks, long energyPerTick, int throughputFactor,
        List<PortIn> inputs, List<PortIn> outputs, boolean countFixed, int machineCount,
        Map<Integer, Double> targetOutputRates, Map<RecipeProperty<?>, Number> properties) {

        public Machine {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
            targetOutputRates = Collections.unmodifiableMap(new LinkedHashMap<>(targetOutputRates));
            properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        }

        public List<PortIn> ports(final boolean input) {
            return input ? inputs : outputs;
        }
    }

    /** One drawn edge, by node id and port index. */
    public record EdgeIn(UUID id, UUID source, int sourceOutput, UUID target, int targetInput) {}

    /**
     * A machine group: its member node ids and how many machines it is. A cap by default (the recipes fit in that many
     * machines); {@code exact}, the count is pinned and the recipes' machine time adds up to exactly that many, as a
     * pinned shared machine on Factory Flow's board.
     */
    public record PoolIn(List<UUID> machines, int capacity, boolean exact) {

        public PoolIn {
            machines = List.copyOf(machines);
        }

        public PoolIn(final List<UUID> machines, final int capacity) {
            this(machines, capacity, false);
        }
    }

    /**
     * One drawer. {@code rule} is what the solver applies ({@link Drawer#effectiveRule()}), and
     * {@code label} the name its notes use: the drawer's label, else its resource key.
     */
    public record DrawerIn(UUID id, String label, Drawer.Kind kind, Drawer.Rule rule, double rate,
        List<Drawer.Link> links) {

        public DrawerIn {
            links = List.copyOf(links);
        }
    }

    /** The graph in its own balance mode, honouring the stored excess choice. Client thread only. */
    public static SolveInput of(final Graph graph) {
        return of(graph, graph.getBalanceMode(), graph.getExcessChoice(), Map.of());
    }

    /** The graph in the given mode. Client thread only. */
    public static SolveInput of(final Graph graph, final BalanceMode mode, @Nullable final ChoiceKey choice) {
        return of(graph, mode, choice, Map.of());
    }

    /**
     * The graph in the given mode with extra per-node extent pins (crafts/s). Client thread only:
     * this computes every node's effect and resolves every ingredient's name.
     */
    public static SolveInput of(final Graph graph, final BalanceMode mode, @Nullable final ChoiceKey choice,
        final Map<UUID, Double> extentPins) {
        final Ingredients ingredients = new Ingredients();
        final List<Machine> machines = new ArrayList<>(graph.nodes.size());
        for (final Node node : graph.getNodes()) {
            machines.add(machineOf(node, ingredients));
        }
        final List<EdgeIn> edges = new ArrayList<>(graph.edges.size());
        for (final Edge edge : graph.getEdges()) {
            edges.add(
                new EdgeIn(
                    edge.id,
                    edge.sourceNodeId,
                    edge.sourceOutputIndex,
                    edge.targetNodeId,
                    edge.targetInputIndex));
        }
        final List<PoolIn> pools = new ArrayList<>();
        for (final Group group : graph.getGroups()) {
            if (group instanceof final MachineGroup machineGroup && machineGroup.getMachineCapacity() > 0) {
                // Sorted: the group's id set is a HashSet, and the pool's member order should be the chart's.
                final List<UUID> members = new ArrayList<>(group.getNodeIds());
                members.sort(UUID::compareTo);
                pools.add(new PoolIn(members, machineGroup.getMachineCapacity(), machineGroup.isPinned()));
            }
        }
        final List<DrawerIn> drawers = new ArrayList<>(graph.drawers.size());
        for (final Drawer drawer : graph.getDrawers()) {
            drawers.add(
                new DrawerIn(
                    drawer.getId(),
                    drawer.getLabel()
                        .isEmpty() ? drawer.getResourceKey() : drawer.getLabel(),
                    drawer.getKind(),
                    drawer.effectiveRule(),
                    drawer.getRate(),
                    drawer.getLinks()));
        }
        return new SolveInput(mode, choice, machines, edges, pools, drawers, extentPins);
    }

    private static Machine machineOf(final Node node, final Ingredients ingredients) {
        final MachineConfig cfg = node.machineConfig;
        final EffectResult effect = cfg.computeEffect(node.properties);
        final List<PortIn> inputs = new ArrayList<>(node.inputs.size());
        for (int i = 0; i < node.inputs.size(); i++) {
            inputs.add(ingredients.portOf(node.inputs.get(i), cfg.inputMultiplier(i)));
        }
        final List<PortIn> outputs = new ArrayList<>(node.outputs.size());
        for (int i = 0; i < node.outputs.size(); i++) {
            outputs.add(ingredients.portOf(node.outputs.get(i), cfg.outputMultiplier(i)));
        }
        final Map<RecipeProperty<?>, Number> properties = new LinkedHashMap<>();
        for (final Map.Entry<RecipeProperty<?>, Object> e : node.properties.entrySet()) {
            if (e.getValue() instanceof final Number n) properties.put(e.getKey(), n);
        }
        final Map<Integer, Double> targets = new LinkedHashMap<>();
        for (final Map.Entry<Integer, Double> t : node.targetOutputRates.entrySet()) {
            if (t.getKey() != null && t.getValue() != null) targets.put(t.getKey(), t.getValue());
        }
        return new Machine(
            node.id,
            node.machineName == null ? "" : node.machineName,
            effect.durationTicks(),
            effect.energyPerT(),
            effect.throughputFactor(),
            inputs,
            outputs,
            node.isMachineCountFixed(),
            cfg.getMachineCount(),
            targets,
            properties);
    }

    /** Numbers the ingredients of one snapshot: ports that connect share a number. */
    private static final class Ingredients {

        private final List<Port<?>> representatives = new ArrayList<>();

        PortIn portOf(final Port<?> port, final float multiplier) {
            final String name = port.getDisplayName();
            return new PortIn(
                port.getAmount(),
                port.getChance(),
                multiplier,
                resourceOf(port),
                name == null ? "" : name,
                name != null && Config.isFreeIngredient(name));
        }

        private int resourceOf(final Port<?> port) {
            for (int i = 0; i < representatives.size(); i++) {
                if (representatives.get(i)
                    .canConnect(port)) return i;
            }
            representatives.add(port);
            return representatives.size() - 1;
        }
    }
}
