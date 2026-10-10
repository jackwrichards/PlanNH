package com.gtnhplanner.data.flowchart.balancer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.Drawer;

/**
 * The chart as the solver sees it, built ONCE per run: machines in recipe-extent form, ports
 * interned per machine and direction, the drawn edges, gates over the connected components of ports
 * and the packed per-gate weights. Everything a stage reads from here is stable for the whole solve;
 * the per-run pins and the point arrays live on {@link SolveContext}. Distinguishing the "dataset"
 * from the "point" is what lets the stage pipeline thread state without rebuilding the chart per
 * stage.
 */
public final class ModelData {

    /** A machine as the solver sees it: the snapshot's machine plus its per-port rates in recipe-extent form. */
    public static final class Machine {

        final SolveInput.Machine spec;
        public final double durTicks;

        /** The machine as the snapshot took it: id, name, ports and effect. */
        public SolveInput.Machine spec() {
            return spec;
        }

        final double[] inQty;
        final double[] outQty;
        /** Extent implied by the node's target output rates, or zero when there is none. */
        final double targetExtent;
        /** Extent implied by the node's fixed machine count, or null when unfixed. */
        final @Nullable Double fixedExtent;

        private Machine(final SolveInput.Machine spec) {
            this.spec = spec;
            this.durTicks = spec.durationTicks() > 0 ? spec.durationTicks() : 1;
            final int tf = spec.throughputFactor();
            this.inQty = new double[spec.inputs()
                .size()];
            for (int i = 0; i < inQty.length; i++) {
                final SolveInput.PortIn s = spec.inputs()
                    .get(i);
                // Float arithmetic on purpose: the same product the node's own ports multiply out to.
                inQty[i] = Math.max(0, s.amount()) * s.chance() * s.multiplier() * tf;
            }
            this.outQty = new double[spec.outputs()
                .size()];
            for (int i = 0; i < outQty.length; i++) {
                final SolveInput.PortIn s = spec.outputs()
                    .get(i);
                outQty[i] = Math.max(0, s.amount()) * s.chance() * s.multiplier() * tf;
            }
            this.targetExtent = targetExtent(spec, outQty);
            this.fixedExtent = spec.countFixed() ? spec.machineCount() * (double) Numerics.TICKS_PER_SECOND / durTicks
                : null;
        }

        boolean hasPort(final int portIndex, final boolean input) {
            return portIndex >= 0 && portIndex < (input ? inQty.length : outQty.length);
        }

        double qty(final int portIndex, final boolean input) {
            return input ? inQty[portIndex] : outQty[portIndex];
        }

        /** The snapshot's port: its name and ingredient. */
        public SolveInput.PortIn port(final int portIndex, final boolean input) {
            return spec.ports(input)
                .get(portIndex);
        }

        /**
         * The extent implied by the node's target output rates: rate divided by per-craft quantity,
         * largest target winning (parallel outputs share one extent, so only the tightest can be hit
         * exactly). Targets on stale ports are skipped the same way stale edges are.
         */
        private static double targetExtent(final SolveInput.Machine spec, final double[] outQty) {
            double extent = 0;
            for (final Map.Entry<Integer, Double> t : spec.targetOutputRates()
                .entrySet()) {
                if (t.getValue() == null || t.getValue() <= 0) continue;
                final int i = t.getKey();
                if (i < 0 || i >= outQty.length || outQty[i] <= 0) continue;
                extent = Math.max(extent, t.getValue() / outQty[i]);
            }
            return extent > 0 ? extent : 0;
        }
    }

    public record EdgeData(UUID id, int srcPort, int dstPort) {}

    public record ConnectedPort(int machine, int portIndex, boolean input, double qtyPerCraft, List<Integer> edges) {}

    /** One gate: an ingredient component in one direction, covering the listed ports. */
    public record Gate(boolean input, List<Integer> ports) {}

    /**
     * One machine-sharing group with a capacity: the machines that run on the same hardware and how
     * many machines that hardware is. Only groups the player capped are built - a sharing group
     * without a capacity constrains nothing, so it never reaches the model.
     */
    public record Pool(List<Integer> machines, int capacity, boolean exact) {}

    /**
     * A drawer as the solver sees it: who it is, the rule it asks for, and the terms of the total
     * that rule applies to. A linked port with no drawn edge sends its whole rate to the drawer, so
     * it contributes {@code extent * qty} of its machine ({@link #extentMachines}/{@link #extentQty},
     * summed per machine). A linked port that is also wired contributes its external variable
     * ({@link #externalPorts}): what crosses the boundary there, which is an output's surplus or an
     * input's import. Built for every drawer, rule or not, so the read-out covers byproducts too.
     *
     * @param rule  the rule the solver applies ({@link Drawer#effectiveRule()}): ANY for byproducts
     *              and trash whatever they store.
     * @param links every linked port that made it into the row, wired or not, in link order.
     */
    public record DrawerRow(UUID id, String label, Drawer.Kind kind, Drawer.Rule rule, double rate,
        int[] extentMachines, double[] extentQty, int[] externalPorts, List<PortRef> links) {

        /** Whether anything at all can flow through the drawer in a model that binds externals or not. */
        public boolean hasTerms(final boolean bindsExternals) {
            return extentMachines.length > 0 || bindsExternals && externalPorts.length > 0;
        }
    }

    public final List<Machine> machines = new ArrayList<>();
    public final Map<UUID, Integer> machineIndex = new HashMap<>();
    public final List<EdgeData> edges = new ArrayList<>();
    public final List<ConnectedPort> connectedPorts = new ArrayList<>();
    public final Map<Long, Integer> portLookup = new HashMap<>();
    public final List<Gate> gates = new ArrayList<>();
    public final List<Pool> pools = new ArrayList<>();
    public final List<DrawerRow> drawers = new ArrayList<>();
    public int[] portGate;
    public int[] portComponent;
    /** Packed lexicographic per-gate weights from the type's heuristics. */
    final double[] gateWeights;

    ModelData(final SolveInput input, final Heuristics heuristics) {
        for (final SolveInput.Machine spec : input.machines()) {
            if (machineIndex.containsKey(spec.id())) continue;
            machineIndex.put(spec.id(), machines.size());
            machines.add(new Machine(spec));
        }

        for (final SolveInput.EdgeIn edge : input.edges()) {
            final Integer src = machineIndex.get(edge.source());
            final Integer dst = machineIndex.get(edge.target());
            if (src == null || dst == null) continue;
            // Edges keep the port indices they were saved with, while port lists can shrink (a
            // settings change, or a recipe that lost an output between modpack versions). Drop the
            // dangling edge rather than indexing past the node's ports.
            if (!machines.get(src)
                .hasPort(edge.sourceOutput(), false)
                || !machines.get(dst)
                    .hasPort(edge.targetInput(), true)) {
                continue;
            }
            final int srcPort = internPort(src, edge.sourceOutput(), false);
            final int dstPort = internPort(dst, edge.targetInput(), true);
            final int e = edges.size();
            edges.add(new EdgeData(edge.id(), srcPort, dstPort));
            connectedPorts.get(srcPort)
                .edges()
                .add(e);
            connectedPorts.get(dstPort)
                .edges()
                .add(e);
        }

        buildPools(input);
        buildDrawers(input);
        buildGates();
        final boolean[] gateInput = new boolean[gates.size()];
        for (int g = 0; g < gates.size(); g++) {
            gateInput[g] = gates.get(g)
                .input();
        }
        gateWeights = heuristics.gateWeights(gates.size(), gateInput);
    }

    /**
     * The capped machine-sharing groups, as machine indices. A group holds node ids by geometry, so
     * a node that has since left the chart is skipped, and a group left with nothing to constrain
     * (no machines, or a capacity of zero) is not a pool at all.
     */
    private void buildPools(final SolveInput input) {
        for (final SolveInput.PoolIn pool : input.pools()) {
            if (pool.capacity() <= 0) continue;
            final List<Integer> members = new ArrayList<>();
            for (final UUID nodeId : pool.machines()) {
                final Integer m = machineIndex.get(nodeId);
                if (m != null && !members.contains(m)) members.add(m);
            }
            if (members.isEmpty()) continue;
            pools.add(new Pool(List.copyOf(members), pool.capacity(), pool.exact()));
        }
    }

    /**
     * Every drawer as a {@link DrawerRow}. Links are checked the way edges are: a link to a node that
     * left the chart, to a port index past the node's ports, or to a port that carries nothing is
     * skipped, so a stale link costs that link and not the solve. Must run after the edges are
     * interned: whether a linked port is wired is whether it was interned.
     */
    private void buildDrawers(final SolveInput in) {
        for (final SolveInput.DrawerIn drawer : in.drawers()) {
            final boolean input = drawer.kind()
                .linksInputs();
            final Map<Integer, Double> perMachine = new TreeMap<>();
            final List<Integer> ports = new ArrayList<>();
            final List<PortRef> refs = new ArrayList<>();
            for (final Drawer.Link link : drawer.links()) {
                final Integer m = machineIndex.get(link.nodeId());
                if (m == null) continue;
                final Machine md = machines.get(m);
                if (!md.hasPort(link.portIndex(), input)) continue;
                final double qty = md.qty(link.portIndex(), input);
                if (qty <= 0) continue;
                refs.add(new PortRef(link.nodeId(), link.portIndex(), input));
                final Integer port = portLookup.get(portKey(m, link.portIndex(), input));
                if (port != null) {
                    if (!ports.contains(port)) ports.add(port);
                } else {
                    perMachine.merge(m, qty, Double::sum);
                }
            }
            final int[] extentMachines = new int[perMachine.size()];
            final double[] extentQty = new double[perMachine.size()];
            int i = 0;
            for (final Map.Entry<Integer, Double> e : perMachine.entrySet()) {
                extentMachines[i] = e.getKey();
                extentQty[i++] = e.getValue();
            }
            drawers.add(
                new DrawerRow(
                    drawer.id(),
                    drawer.label(),
                    drawer.kind(),
                    drawer.kind()
                        .hasRule() ? drawer.rule() : Drawer.Rule.ANY,
                    drawer.rate(),
                    extentMachines,
                    extentQty,
                    ports.stream()
                        .mapToInt(Integer::intValue)
                        .toArray(),
                    List.copyOf(refs)));
        }
    }

    /** The {@link #portLookup} key of a machine's port. */
    public static long portKey(final int machine, final int portIndex, final boolean input) {
        return ((long) machine << 32) | ((long) portIndex << 1) | (input ? 1 : 0);
    }

    /**
     * Ingredient identity is structural: connected components of ports under the drawn edges. One
     * gate covers a component's input ports, one its output ports - so the binary count is ~2x
     * intermediates and not one per port.
     */
    private void buildGates() {
        final int n = connectedPorts.size();
        final int[] root = new int[n];
        for (int i = 0; i < n; i++) {
            root[i] = i;
        }
        for (final EdgeData e : edges) {
            union(root, e.srcPort(), e.dstPort());
        }
        portGate = new int[n];
        portComponent = new int[n];
        final Map<Long, Integer> gateLookup = new HashMap<>();
        for (int p = 0; p < n; p++) {
            final boolean input = connectedPorts.get(p)
                .input();
            portComponent[p] = find(root, p);
            final long key = ((long) portComponent[p] << 1) | (input ? 1 : 0);
            Integer gate = gateLookup.get(key);
            if (gate == null) {
                gates.add(new Gate(input, new ArrayList<>()));
                gate = gates.size() - 1;
                gateLookup.put(key, gate);
            }
            gates.get(gate)
                .ports()
                .add(p);
            portGate[p] = gate;
        }
    }

    private int internPort(final int machine, final int portIndex, final boolean input) {
        final long key = portKey(machine, portIndex, input);
        return portLookup.computeIfAbsent(key, k -> {
            connectedPorts.add(
                new ConnectedPort(
                    machine,
                    portIndex,
                    input,
                    machines.get(machine)
                        .qty(portIndex, input),
                    new ArrayList<>()));
            return connectedPorts.size() - 1;
        });
    }

    private static int find(final int[] root, final int i) {
        int r = i;
        while (root[r] != r) {
            r = root[r];
        }
        root[i] = r;
        return r;
    }

    private static void union(final int[] root, final int a, final int b) {
        root[find(root, a)] = find(root, b);
    }
}
