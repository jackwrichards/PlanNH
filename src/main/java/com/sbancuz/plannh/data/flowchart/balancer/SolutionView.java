package com.sbancuz.plannh.data.flowchart.balancer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.sbancuz.plannh.Config;

/**
 * The whole solve's answer, shaped for the GUI: per-node extents and machine counts, per-edge
 * flows, the gated sources/sinks and the free terminals, the gate count and the raw external
 * quantity, and the completion flags a reader reconstructs conservation from. Built ONLY by
 * {@link Balancer}: always for the GUI's solve-and-enumerate entry, and for {@link #run} only when
 * a profiler is attached - so the plain-run payload never constructs it.
 *
 * <p>
 * Cutoffs and derived numbers follow the solver's numerics: flows below the
 * {@code dust * solutionScale} cutoff, counts as {@code extent * durTicks / TICKS_PER_SECOND}, the
 * terminal rule (machine ports with no drawn edge), and {@code externalQuantity} as the raw sum
 * over the reported externals. Deliberately NOT the normalized/craft-weighted stage-2 objective.
 */
public final class SolutionView {

    public final Map<UUID, Double> extentsPerSecond = new LinkedHashMap<>();
    public final Map<UUID, Double> machineCounts = new LinkedHashMap<>();
    public final Map<UUID, Double> edgeFlowsPerSecond = new LinkedHashMap<>();
    public final List<External> gatedSources = new ArrayList<>();
    public final List<External> gatedSinks = new ArrayList<>();
    public final List<External> terminalInputs = new ArrayList<>();
    public final List<External> terminalOutputs = new ArrayList<>();
    public final int openGates;
    public final double externalQuantity;
    public final double totalInternalFlow;
    public final boolean floorsUsed;
    public final long wallMillis;
    public final List<Note> notes;
    public final ChoiceKey key;
    /** Every drawer's total at this point, and which drawers' rules it does not keep. */
    public final DrawerReadout drawers;

    private SolutionView(final SolveContext ctx, final long wallMillis) {
        final ModelData model = ctx.model;
        final double tol = ctx.heuristics.numerics().dust
            * ctx.solutionScale(ctx.extents(), ctx.flows(), ctx.externals());
        for (int m = 0; m < model.machines.size(); m++) {
            final ModelData.Machine md = model.machines.get(m);
            extentsPerSecond.put(md.spec.id(), ctx.extents()[m]);
            machineCounts.put(md.spec.id(), ctx.extents()[m] * md.durTicks / (double) Numerics.TICKS_PER_SECOND);
        }
        for (int e = 0; e < model.edges.size(); e++) {
            edgeFlowsPerSecond.put(
                model.edges.get(e)
                    .id(),
                ctx.flows()[e]);
        }
        double quantity = 0;
        for (int p = 0; p < ctx.externals().length; p++) {
            final double ext = ctx.externals()[p];
            if (ext <= tol) continue;
            final ModelData.ConnectedPort port = model.connectedPorts.get(p);
            final External external = new External(ctx.refOf(p), ext);
            (port.input() ? gatedSources : gatedSinks).add(external);
            quantity += ext;
        }
        collectTerminals(ctx, tol);
        double flow = 0;
        for (final double f : ctx.flows()) {
            flow += f;
        }
        this.openGates = ctx.support()
            .size();
        this.externalQuantity = quantity;
        this.totalInternalFlow = flow;
        this.floorsUsed = ctx.floorsUsed;
        this.wallMillis = wallMillis;
        this.key = ctx.keyOf(ctx.support());
        this.drawers = DrawerReadout.of(ctx, ctx.extents(), ctx.externals());
        final List<Note> allNotes = new ArrayList<>();
        allNotes.addAll(ctx.notes);
        allNotes.addAll(ctx.stageNotes);
        allNotes.addAll(wiringDiagnostics(ctx, tol, terminalInputs));
        this.notes = List.copyOf(allNotes);
    }

    /** The view over the run's committed point. */
    static SolutionView of(final SolveContext ctx, final long wallMillis) {
        return new SolutionView(ctx, wallMillis);
    }

    /** Machine ports with no drawn edge, flowing at their own rate - the free terminals. */
    private void collectTerminals(final SolveContext ctx, final double tol) {
        final ModelData model = ctx.model;
        for (int m = 0; m < model.machines.size(); m++) {
            final ModelData.Machine md = model.machines.get(m);
            terminalScan(ctx, m, md.inQty.length, true, tol, terminalInputs);
            terminalScan(ctx, m, md.outQty.length, false, tol, terminalOutputs);
        }
    }

    private void terminalScan(final SolveContext ctx, final int m, final int portCount, final boolean input,
        final double tol, final List<External> out) {
        final ModelData model = ctx.model;
        final ModelData.Machine md = model.machines.get(m);
        for (int i = 0; i < portCount; i++) {
            final double qty = input ? md.inQty[i] : md.outQty[i];
            if (qty <= 0) continue;
            final long key = ((long) m << 32) | ((long) i << 1) | (input ? 1 : 0);
            if (model.portLookup.containsKey(key)) continue; // connected, not a terminal
            final double rate = ctx.extents()[m] * qty;
            if (rate <= tol) continue;
            out.add(new External(new PortRef(md.spec.id(), i, input), rate));
        }
    }

    /**
     * Flags externals that look like missing edges rather than intent. The free-terminal rule
     * means an unwired input silently becomes "supplied from outside" - correct for oil, wrong
     * when the same ingredient is right there on the chart. Two smells: (1) a terminal input
     * whose ingredient the chart also produces (through drawn edges or another terminal); (2) a
     * gated source whose ingredient is produced in a DIFFERENT edge-connected component (two
     * unlinked islands of the same fluid). Same-component gated sources are the normal deficit
     * case and stay quiet.
     *
     * <p>
     * Severities differ because the two smells do: importing from outside while the chart also
     * makes some is routine, so smell 1 is an observation. Two unlinked islands of the SAME
     * ingredient is a chart that does not describe one factory, so smell 2 is a warning.
     * Ingredients the pack gives away are filtered out of both - see {@link Config#isFreeIngredient}, read
     * when the snapshot was taken.
     */
    private List<Note> wiringDiagnostics(final SolveContext ctx, final double tol, final List<External> terminalIn) {
        final ModelData model = ctx.model;
        // A set: the same ingredient imported at two ports of one machine is one wiring mistake to
        // the reader, and the message that describes it is identical either way.
        final Set<Note> result = new LinkedHashSet<>();
        // A port a source drawer supplies is imported on purpose: the player said where it comes from.
        final Set<PortRef> supplied = new HashSet<>();
        for (final ModelData.DrawerRow row : model.drawers) {
            if (row.kind()
                .linksInputs()) supplied.addAll(row.links());
        }
        for (final External in : terminalIn) {
            if (supplied.contains(in.port())) continue;
            final SolveInput.PortIn ingredient = portOf(ctx, in);
            if (ingredient.free()) continue;
            final String match = findProduction(ctx, in, -1);
            if (match != null) {
                result.add(new Note(SolverMessage.WIRING_IMPORT, machineNameOf(ctx, in), ingredient.name(), match));
            }
        }
        for (int p = 0; p < model.connectedPorts.size(); p++) {
            final ModelData.ConnectedPort port = model.connectedPorts.get(p);
            if (!port.input() || ctx.externals()[p] <= tol) continue;
            final External src = new External(ctx.refOf(p), ctx.externals()[p]);
            if (supplied.contains(src.port())) continue;
            final SolveInput.PortIn ingredient = portOf(ctx, src);
            if (ingredient.free()) continue;
            final String match = findProduction(ctx, src, model.portComponent[p]);
            if (match != null) {
                result.add(new Note(SolverMessage.WIRING_UNLINKED, machineNameOf(ctx, src), ingredient.name(), match));
            }
        }
        return List.copyOf(result);
    }

    /**
     * A machine name producing the same ingredient as {@code consumer}'s port, or null. Checks
     * terminal outputs and connected output ports; {@code excludeComponent} skips the consumer's
     * own component (-1 checks everything).
     */
    private String findProduction(final SolveContext ctx, final External consumer, final int excludeComponent) {
        final ModelData model = ctx.model;
        final int want = portOf(ctx, consumer).resource();
        for (int p = 0; p < model.connectedPorts.size(); p++) {
            final ModelData.ConnectedPort port = model.connectedPorts.get(p);
            if (port.input() || model.portComponent[p] == excludeComponent) continue;
            final ModelData.Machine md = model.machines.get(port.machine());
            if (md.port(port.portIndex(), false)
                .resource() == want) {
                return md.spec.name();
            }
        }
        for (int m = 0; m < model.machines.size(); m++) {
            final ModelData.Machine md = model.machines.get(m);
            for (int i = 0; i < md.outQty.length; i++) {
                if (md.outQty[i] <= 0) continue;
                if (model.portLookup.containsKey(ModelData.portKey(m, i, false))) continue; // connected, handled above
                if (md.port(i, false)
                    .resource() == want) {
                    return md.spec.name();
                }
            }
        }
        return null;
    }

    private SolveInput.PortIn portOf(final SolveContext ctx, final External external) {
        final ModelData model = ctx.model;
        return model.machines.get(
            model.machineIndex.get(
                external.port()
                    .nodeId()))
            .port(
                external.port()
                    .portIndex(),
                external.port()
                    .input());
    }

    private String machineNameOf(final SolveContext ctx, final External external) {
        return ctx.model.machines.get(
            ctx.model.machineIndex.get(
                external.port()
                    .nodeId())).spec.name();
    }
}
