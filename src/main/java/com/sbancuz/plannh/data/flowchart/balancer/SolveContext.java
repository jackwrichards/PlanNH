package com.sbancuz.plannh.data.flowchart.balancer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.sbancuz.plannh.data.flowchart.Drawer;

/**
 * The shared, mutable state of one solve run - the "threaded" half of the zero-copy pipeline.
 * Everything a stage may read or write on a single shared object rather than copied per stage:
 * the build-once {@link ModelData}, the type's {@link Heuristics}, the run's {@link Budget} and
 * pins, the pass-2 floors, and the current point ({@link StageOutcome}). Stages mutate this in
 * place; the next stage reads the result. The only objects rebuilt per stage are the ojAlgo models
 * themselves, which is inherent to the library and allowed.
 */
public final class SolveContext {

    /** Pins, strongest first: explicit extent, target output rate, fixed machine count. */
    private static final List<SolverMessage> PIN_STRENGTH = List
        .of(SolverMessage.PIN_COUNT, SolverMessage.PIN_TARGET, SolverMessage.PIN_EXTENT);

    public final ModelData model;
    public final Heuristics heuristics;
    /**
     * The wall-clock deadline for the whole solve. Mutable: the alternatives search swaps it for
     * its own split budgets mid-run, so a stage always reads the deadline that governs the model
     * it is about to build.
     */
    public Budget budget;
    public final boolean anyPin;
    /**
     * Whether anything sets the chart's scale: a pin, or a drawer that asks for a positive amount
     * (at least or exactly) and is connected to something. AUTO has nothing to solve without one.
     */
    public final boolean anchored;
    /**
     * Whether drawer rows bind the externals of wired linked ports. True for the conservation
     * family (AUTO), where a wired port's external is exactly what crosses the boundary there; the
     * OUTPUT / INPUT models have no such variable on their claimed rows, so there a drawer counts
     * its unwired links only.
     */
    public final boolean bindsExternals;
    /**
     * Per drawer row ({@link ModelData#drawers}), the bounds its rule puts on the drawer's total;
     * NaN for none. Mutable: the drawer relaxation loosens a rule that cannot be met to what the
     * chart can do, and the re-run solves under those.
     */
    public final double[] drawerLower;
    public final double[] drawerUpper;
    /** Drawers whose rule this run cannot meet, in the order they were found; read into the result. */
    public final Set<UUID> unmetDrawers = new LinkedHashSet<>();
    /** What each unmet drawer can reach and what holds it back, as found by the relaxation. */
    public final List<DrawerReadout.Shortfall> shortfalls = new ArrayList<>();
    /** Pool rows ({@link ModelData#pools}) left out of the models built now; the relaxation's probes only. */
    final Set<Integer> droppedPools = new HashSet<>();
    /** The instrumentation hook for this run; {@link Profiler#disabled()} unless a test attaches one. */
    public final Profiler profiler;
    /** Per-machine declared extent (crafts/s), NaN = free (a 0-count pin is a real pin). */
    public final double[] pinnedExtent;
    /**
     * The kind of pin on each machine, named by {@link SolverMessage}; null-free, NaN slots hold the least useful
     * label.
     */
    public final SolverMessage[] pinKind;
    /** The pin types this balance mode honors; others are ignored at construction. */
    public final Set<Pin> pins;

    /** Pass-2 "every machine runs" floors (crafts/s); empty when none. Set by the runner. */
    public double[] floors = new double[0];
    /** True when a prelude or stage has proven the whole chain optimal and halted it. */
    public boolean shortCircuit;
    /** True when the replay pass (audit floors) actually ran. */
    public boolean floorsUsed;

    /** The committed point - the only null in the state, until a stage commits one. */
    @Nullable
    private StageOutcome point;

    /** The committed point, or null when no stage has committed one yet. */
    @Nullable
    public StageOutcome point() {
        return point;
    }

    /** Why the last model produced nothing usable; stages read it for their failure messages. */
    public Note rejection = new Note(SolverMessage.SOLVER_NO_SOLUTION);
    /** Final answer notes: pin overshoots from construction, then each pass's own stage notes. */
    public final List<Note> notes = new ArrayList<>();
    /** Stage notes produced by the pass currently running (cleared on every pass). */
    public final List<Note> stageNotes = new ArrayList<>();

    SolveContext(final SolveInput input, final Heuristics heuristics, final Budget budget,
        final Map<UUID, Double> extraExtentPins, final Set<Pin> pins, final boolean bindsExternals,
        final Profiler profiler) {
        this.model = new ModelData(input, heuristics);
        this.heuristics = heuristics;
        this.budget = budget;
        this.pins = Set.copyOf(pins);
        this.profiler = profiler;
        this.bindsExternals = bindsExternals;

        final int n = model.machines.size();
        this.pinnedExtent = new double[n];
        this.pinKind = new SolverMessage[n];
        boolean any = false;
        for (int m = 0; m < n; m++) {
            final ModelData.Machine md = model.machines.get(m);
            final Double extra = extraExtentPins.get(md.spec.id());
            if (pins.contains(Pin.EXTENT) && extra != null) {
                pinnedExtent[m] = extra;
                pinKind[m] = SolverMessage.PIN_EXTENT;
                any = true;
            } else if (pins.contains(Pin.TARGET_RATE) && md.targetExtent > 0) {
                pinnedExtent[m] = md.targetExtent;
                pinKind[m] = SolverMessage.PIN_TARGET;
                any = true;
                noteOvershotTargets(m, md.targetExtent);
            } else if (pins.contains(Pin.FIXED_COUNT) && md.fixedExtent != null) {
                pinnedExtent[m] = md.fixedExtent;
                pinKind[m] = SolverMessage.PIN_COUNT;
                any = true;
            } else {
                // NaN, not 0: a machine pinned to a zero count is a real pin (it must NOT run),
                // and a bare double 0 would read it as unpinned.
                pinnedExtent[m] = Double.NaN;
            }
        }
        this.anyPin = any;

        final int d = model.drawers.size();
        this.drawerLower = new double[d];
        this.drawerUpper = new double[d];
        boolean anchor = false;
        for (int i = 0; i < d; i++) {
            final ModelData.DrawerRow row = model.drawers.get(i);
            drawerLower[i] = Double.NaN;
            drawerUpper[i] = Double.NaN;
            if (row.rule() == Drawer.Rule.ANY) continue;
            final boolean asksForFlow = row.rule() != Drawer.Rule.AT_MOST && row.rate() > 0;
            if (!row.hasTerms(bindsExternals)) {
                // Nothing reaches this drawer, so its total is zero: a rule asking for flow cannot
                // be met and would only make the whole chart infeasible, so it is reported and left
                // out of the model; a rule zero satisfies needs no row at all.
                if (asksForFlow) {
                    unmetDrawers.add(row.id());
                    notes.add(
                        new Note(
                            row.externalPorts().length > 0 ? SolverMessage.DRAWER_WIRED_IGNORED
                                : SolverMessage.DRAWER_NOT_CONNECTED,
                            row.label()));
                }
                continue;
            }
            switch (row.rule()) {
                case AT_LEAST -> drawerLower[i] = row.rate();
                case AT_MOST -> drawerUpper[i] = row.rate();
                case EXACTLY -> {
                    drawerLower[i] = row.rate();
                    drawerUpper[i] = row.rate();
                }
                default -> {}
            }
            if (asksForFlow) anchor = true;
        }
        this.anchored = any || anchor;
    }

    /** Whether any drawer puts a bound in the model. */
    public boolean hasDrawerBounds() {
        for (int i = 0; i < drawerLower.length; i++) {
            if (!Double.isNaN(drawerLower[i]) || !Double.isNaN(drawerUpper[i])) return true;
        }
        return false;
    }

    /**
     * A drawer's total at a point: its unwired links' machine rates plus, where this model binds
     * them, its wired links' externals.
     */
    public double drawerTotal(final int drawer, final double[] extents, final double[] externals) {
        final ModelData.DrawerRow row = model.drawers.get(drawer);
        double total = 0;
        for (int i = 0; i < row.extentMachines().length; i++) {
            total += extents[row.extentMachines()[i]] * row.extentQty()[i];
        }
        if (bindsExternals) {
            for (final int p : row.externalPorts()) {
                total += externals[p];
            }
        }
        return total;
    }

    /** Whether a total keeps within a drawer's bounds, to the validation tolerance. */
    public boolean drawerHolds(final int drawer, final double total) {
        final double lower = drawerLower[drawer];
        final double upper = drawerUpper[drawer];
        final double tol = heuristics.numerics().validateTol;
        if (!Double.isNaN(lower) && total < lower - tol * Math.max(1.0, Math.abs(lower))) return false;
        return Double.isNaN(upper) || total <= upper + tol * Math.max(1.0, Math.abs(upper));
    }

    /**
     * The machines a drawer that sets the scale reaches: they anchor their component for the
     * "every machine runs" floors just as a pinned machine does.
     */
    private Set<Integer> anchorMachines() {
        final Set<Integer> out = new HashSet<>();
        for (int i = 0; i < model.drawers.size(); i++) {
            final ModelData.DrawerRow row = model.drawers.get(i);
            final boolean asksForFlow = !Double.isNaN(drawerLower[i]) && drawerLower[i] > 0;
            if (!asksForFlow) continue;
            for (final int m : row.extentMachines()) {
                out.add(m);
            }
            if (bindsExternals) {
                for (final int p : row.externalPorts()) {
                    out.add(
                        model.connectedPorts.get(p)
                            .machine());
                }
            }
        }
        return out;
    }

    /**
     * Says so when a node carries output-rate targets it cannot all hit (parallel outputs share
     * one extent, so the largest target wins and the rest overproduce).
     */
    private void noteOvershotTargets(final int m, final double chosenExtent) {
        final ModelData.Machine md = model.machines.get(m);
        final double tieRel = heuristics.numerics().tieRel;
        for (final Map.Entry<Integer, Double> t : md.spec.targetOutputRates()
            .entrySet()) {
            if (t.getValue() == null || t.getValue() <= 0) continue;
            final int i = t.getKey();
            if (i < 0 || i >= md.outQty.length || md.outQty[i] <= 0) continue;
            final double actual = chosenExtent * md.outQty[i];
            if (actual > t.getValue() * (1 + tieRel)) {
                notes.add(
                    new Note(
                        SolverMessage.OVERSHOOTS_TARGET,
                        md.spec.name(),
                        md.port(i, false)
                            .name(),
                        actual,
                        t.getValue()));
            }
        }
    }

    public double externalWeight(final int port) {
        return heuristics.externalWeight(
            model.connectedPorts.get(port)
                .qtyPerCraft());
    }

    public double gateWeight(final int gate) {
        return model.gateWeights[gate];
    }

    public double importTilt(final int gate) {
        return heuristics.importTilt(
            model.gates.get(gate)
                .input());
    }

    /** Stage-2/'s objective value for externals: excess measured in crafts of the carrying machine. */
    public double normalizedQuantity(final double[] externals) {
        double qty = 0;
        for (int p = 0; p < externals.length; p++) {
            qty += externals[p] * externalWeight(p);
        }
        return qty;
    }

    public double weightedCost(final Set<Integer> support) {
        double total = 0;
        for (final int g : support) {
            total += gateWeight(g);
        }
        return total;
    }

    /** The largest magnitude in a vector, never zero, so it can be divided into safely. */
    public static double scaleOf(final double[] values) {
        double max = 0;
        for (final double v : values) {
            max = Math.max(max, Math.abs(v));
        }
        return Math.max(max, Double.MIN_NORMAL);
    }

    /** Relative tolerance for "a rate is negligible". */
    public double zeroTolerance(final double[] values) {
        return heuristics.numerics().zero * scaleOf(values);
    }

    /** The largest rate this solution moves anywhere, terminals included - the homogeneity yardstick. */
    public double solutionScale(final double[] extents, final double[] flows, final double[] externals) {
        double max = Math.max(scaleOf(flows), scaleOf(externals));
        for (int m = 0; m < model.machines.size(); m++) {
            final ModelData.Machine md = model.machines.get(m);
            for (final double q : md.inQty) {
                max = Math.max(max, extents[m] * q);
            }
            for (final double q : md.outQty) {
                max = Math.max(max, extents[m] * q);
            }
        }
        return Math.max(max, Double.MIN_NORMAL);
    }

    /** How close two solves of one objective have to be to count as the same optimum. */
    public double tieTolerance(final double objective, final StageOutcome s) {
        return heuristics.numerics().tieRel
            * Math.max(Math.abs(objective), solutionScale(s.extents, s.flows, s.externals));
    }

    /** Every gate the point puts flow through, however little - the set that must stay open. */
    public Set<Integer> carryingGates(final double[] externals, final Set<Integer> fallback) {
        final Set<Integer> carrying = new HashSet<>(fallback);
        final double dust = heuristics.numerics().dust * scaleOf(externals);
        for (int p = 0; p < externals.length; p++) {
            if (externals[p] > dust) carrying.add(model.portGate[p]);
        }
        return carrying;
    }

    /** The gate support (gate indices) carried by per-port external flows, ZERO-toleranced. */
    public Set<Integer> gateSupport(final double[] externals) {
        final double[] gateFlow = new double[model.gates.size()];
        for (int p = 0; p < externals.length; p++) {
            gateFlow[model.portGate[p]] += externals[p];
        }
        final double tol = zeroTolerance(gateFlow);
        final Set<Integer> support = new HashSet<>();
        for (int g = 0; g < gateFlow.length; g++) {
            if (gateFlow[g] > tol) support.add(g);
        }
        return support;
    }

    public PortRef refOf(final int port) {
        final ModelData.ConnectedPort p = model.connectedPorts.get(port);
        return new PortRef(model.machines.get(p.machine()).spec.id(), p.portIndex(), p.input());
    }

    public @Nullable PortRef anchorOf(final int gate) {
        PortRef best = null;
        for (final int p : model.gates.get(gate)
            .ports()) {
            final PortRef ref = refOf(p);
            if (best == null || PortRef.ORDER.compare(ref, best) < 0) best = ref;
        }
        return best;
    }

    public ChoiceKey keyOf(final Set<Integer> support) {
        final List<PortRef> anchors = new ArrayList<>(support.size());
        for (final int g : support) {
            anchors.add(anchorOf(g));
        }
        return ChoiceKey.of(anchors);
    }

    /** The committed point's arrays; the subpackage stages and view read them via these accessors. */
    public double[] extents() {
        return point.extents;
    }

    public double[] flows() {
        return point.flows;
    }

    public double[] externals() {
        return point.externals;
    }

    public Set<Integer> support() {
        return point.support;
    }

    /** Commits a point the way the chain reads it: one authoritative object replaces the last. */
    public void commit(final StageOutcome committed) {
        this.point = committed;
    }

    /** Wipes the threaded pass state; called between pass 1 and pass 2 of the replay. */
    public void resetForReplay() {
        this.point = null;
        this.shortCircuit = false;
        this.rejection = new Note(SolverMessage.SOLVER_NO_SOLUTION);
        this.floorsUsed = false;
        this.stageNotes.clear();
    }

    /** Independent conservation check; null when the point conserves, else the offending row. */
    public @Nullable String validate(final double[] extents, final double[] flows, final double[] externals) {
        final double floor = -heuristics.numerics().dust * solutionScale(extents, flows, externals);
        for (final double extent : extents) {
            if (extent < floor) return "negative extent " + extent;
        }
        for (final double flow : flows) {
            if (flow < floor) return "negative edge flow " + flow;
        }
        for (final double ext : externals) {
            if (ext < floor) return "negative external " + ext;
        }
        for (int p = 0; p < model.connectedPorts.size(); p++) {
            final ModelData.ConnectedPort port = model.connectedPorts.get(p);
            double flowsSum = 0;
            for (final int e : port.edges()) {
                flowsSum += flows[e];
            }
            final double residual = flowsSum + externals[p] - extents[port.machine()] * port.qtyPerCraft();
            final double scale = Math.max(1.0, port.qtyPerCraft());
            if (Math.abs(residual) / scale > heuristics.numerics().validateTol) {
                return "port " + (port.input() ? "in" : "out")
                    + "["
                    + port.portIndex()
                    + "] of '"
                    + model.machines.get(port.machine()).spec.name()
                    + "' residual "
                    + residual;
            }
        }
        return null;
    }

    /**
     * Which pins the chart cannot satisfy at once, or null when the pins are not the problem. A
     * floor-free stage 1 fails only when the most permissive model there is comes back infeasible,
     * and with nonnegative externals on every connected port the only thing left to conflict is the
     * pins - so free them weakest first (the pin-strength ranking), until the LP closes, and name
     * the ones that had to go.
     */
    public @Nullable Note diagnosePins() {
        final List<Integer> pinned = new ArrayList<>();
        for (int m = 0; m < model.machines.size(); m++) {
            if (!Double.isNaN(pinnedExtent[m])) pinned.add(m);
        }
        if (pinned.size() < 2) return null;
        pinned.sort(Comparator.comparingInt(m -> PIN_STRENGTH.indexOf(pinKind[m])));

        final Map<Integer, Double> saved = new HashMap<>();
        final List<Note> dropped = new ArrayList<>();
        try {
            for (final int m : pinned) {
                if (!Solver.externalsLp(this, null)
                    .isRejected()) break;
                if (saved.size() == pinned.size() - 1) return null; // one pin left: not a conflict
                saved.put(m, pinnedExtent[m]);
                pinnedExtent[m] = Double.NaN;
                dropped
                    .add(new Note(SolverMessage.PIN_DROPPED, model.machines.get(m).spec.name(), pinKind[m].toNote()));
            }
        } finally {
            saved.forEach((m, value) -> pinnedExtent[m] = value);
        }
        if (dropped.isEmpty()) return null;
        return new Note(SolverMessage.PIN_CONFLICT, List.copyOf(dropped));
    }

    /** "Every machine runs" pass-2 floors; empty when every unpinned machine already runs. */
    public double[] machineRunFloors(final double[] extents) {
        final int n = model.machines.size();
        final int[] root = new int[n];
        for (int m = 0; m < n; m++) {
            root[m] = m;
        }
        for (final ModelData.EdgeData e : model.edges) {
            final int a = model.connectedPorts.get(e.srcPort())
                .machine();
            final int b = model.connectedPorts.get(e.dstPort())
                .machine();
            union(root, a, b);
        }

        final Set<Integer> pinnedComponents = new HashSet<>();
        for (int m = 0; m < n; m++) {
            if (!Double.isNaN(pinnedExtent[m])) pinnedComponents.add(find(root, m));
        }
        for (final int m : anchorMachines()) {
            pinnedComponents.add(find(root, m));
        }
        if (pinnedComponents.isEmpty()) return new double[0];

        boolean anyIdle = false;
        double minRunning = Double.MAX_VALUE;
        for (int m = 0; m < n; m++) {
            if (!Double.isNaN(pinnedExtent[m]) || !pinnedComponents.contains(find(root, m))) continue;
            if (extents[m] <= heuristics.numerics().useEpsDetect) {
                anyIdle = true;
            } else {
                minRunning = Math.min(minRunning, extents[m]);
            }
        }
        if (!anyIdle) return new double[0];

        final double useEps = heuristics.numerics().useEps;
        final double floor = (minRunning == Double.MAX_VALUE ? useEps : minRunning) * 1e-3;
        final double[] floors = new double[n];
        for (int m = 0; m < n; m++) {
            if (Double.isNaN(pinnedExtent[m]) && pinnedComponents.contains(find(root, m))) floors[m] = floor;
        }
        return floors;
    }

    /** Machines the solution leaves at zero, ignoring the ones the user pinned. */
    public int idleCount(final double[] extents) {
        int idle = 0;
        for (int m = 0; m < extents.length; m++) {
            if (Double.isNaN(pinnedExtent[m]) && extents[m] <= heuristics.numerics().useEpsDetect) idle++;
        }
        return idle;
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
