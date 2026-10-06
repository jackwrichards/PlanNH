package com.sbancuz.plannh.data.flowchart.balancer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.optimisation.Variable;

/**
 * What to do when the drawers' rules cannot all hold. Pins are the player's hard word (a machine
 * count they typed); a drawer's rate is a wish the plan should come as close to as it can. So when
 * a run stalls and drawers bound the model, this solves the most permissive model there is - every
 * gate open, every external free, pins and machine groups held - with each drawer bound given a
 * slack, and minimizes the total slack, each unit weighed relative to its drawer's rate. When that
 * LP is infeasible the pins (or groups) conflict among themselves and the drawers are not the
 * problem; when it needs no slack the stall had another cause. Otherwise every drawer that needed
 * slack is unmet: its bounds are loosened to what the relaxed point reaches, and the caller re-runs
 * the chain under them, so the plan still gets numbers and the unmet drawers are reported.
 *
 * <p>
 * The trade-off between several unmet drawers is the LP's: it minimizes the sum of relative
 * shortfalls, so with one shared bottleneck it may starve one product rather than split the
 * shortage. For each unmet drawer the constraints holding it back are found by dropping them one
 * at a time (pins, other drawers' rules, machine groups) and seeing whether it is then kept;
 * capped at {@link #MAX_PROBES} models and the relaxation's own budget.
 */
final class DrawerRelaxation {

    /** How far a loosened bound sits past the relaxed point, relative, so the re-run is not on a knife edge. */
    private static final double LOOSEN = 1e-7;
    /** Cap on the drop-one probes across all unmet drawers. */
    private static final int MAX_PROBES = 32;
    /** Wall budget for the relaxation and its probes, scaled by effort. */
    private static final long BUDGET_MILLIS = 4_000;

    private DrawerRelaxation() {}

    /** The loosened bounds and what the relaxation found; apply to a fresh context before re-running. */
    record Outcome(double[] lower, double[] upper, Set<UUID> unmet, List<DrawerReadout.Shortfall> shortfalls,
        List<Note> notes) {

        void applyTo(final SolveContext ctx) {
            System.arraycopy(lower, 0, ctx.drawerLower, 0, lower.length);
            System.arraycopy(upper, 0, ctx.drawerUpper, 0, upper.length);
            ctx.unmetDrawers.addAll(unmet);
            ctx.shortfalls.addAll(shortfalls);
            ctx.notes.addAll(notes);
        }
    }

    /** The relaxed point: per drawer, the slack it used and the total it reached. */
    private record Relaxed(double[] slack, double[] total) {}

    /**
     * Relaxes the drawer bounds of a stalled run, or returns null when the drawers are not why it
     * stalled. Leaves {@code ctx}'s pins and bounds as it found them; swaps its budget.
     */
    @Nullable
    static Outcome run(final SolveContext ctx) {
        final Numerics numerics = ctx.heuristics.numerics();
        ctx.budget = Budget.of(numerics.effort(BUDGET_MILLIS));
        final Relaxed base = solve(ctx);
        if (base == null) return null;

        final int drawers = ctx.model.drawers.size();
        final double[] lower = ctx.drawerLower.clone();
        final double[] upper = ctx.drawerUpper.clone();
        final List<Integer> short_ = new ArrayList<>();
        for (int d = 0; d < drawers; d++) {
            if (!missed(ctx, base, d)) continue;
            short_.add(d);
            final double total = base.total[d];
            final double eps = LOOSEN * Math.max(1.0, Math.abs(total));
            if (!Double.isNaN(lower[d])) lower[d] = Math.max(0, Math.min(lower[d], total - eps));
            if (!Double.isNaN(upper[d])) upper[d] = Math.max(upper[d], total + eps);
        }
        if (short_.isEmpty()) return null;

        final Set<UUID> unmet = new LinkedHashSet<>();
        final List<DrawerReadout.Shortfall> shortfalls = new ArrayList<>();
        final List<Note> notes = new ArrayList<>();
        int probes = 0;
        for (final int d : short_) {
            final ModelData.DrawerRow row = ctx.model.drawers.get(d);
            unmet.add(row.id());
            notes.add(
                new Note(
                    SolverMessage.DRAWER_UNMET,
                    row.label(),
                    SolverMessage.ruleName(row.rule())
                        .toNote(),
                    row.rate(),
                    base.total[d]));

            final List<UUID> nodes = new ArrayList<>();
            final List<UUID> others = new ArrayList<>();
            int groups = 0;
            final List<Note> limits = new ArrayList<>();
            for (int m = 0; m < ctx.model.machines.size(); m++) {
                if (Double.isNaN(ctx.pinnedExtent[m])) continue;
                if (probes++ >= MAX_PROBES || ctx.budget.expired()) break;
                final double saved = ctx.pinnedExtent[m];
                ctx.pinnedExtent[m] = Double.NaN;
                try {
                    if (keptWithout(ctx, d)) {
                        final ModelData.Machine md = ctx.model.machines.get(m);
                        nodes.add(md.spec.id());
                        limits.add(new Note(SolverMessage.LIMIT_PIN, md.spec.name(), ctx.pinKind[m].toNote()));
                    }
                } finally {
                    ctx.pinnedExtent[m] = saved;
                }
            }
            for (int e = 0; e < drawers; e++) {
                if (e == d || Double.isNaN(ctx.drawerLower[e]) && Double.isNaN(ctx.drawerUpper[e])) continue;
                if (probes++ >= MAX_PROBES || ctx.budget.expired()) break;
                final double savedLower = ctx.drawerLower[e];
                final double savedUpper = ctx.drawerUpper[e];
                ctx.drawerLower[e] = Double.NaN;
                ctx.drawerUpper[e] = Double.NaN;
                try {
                    if (keptWithout(ctx, d)) {
                        final ModelData.DrawerRow other = ctx.model.drawers.get(e);
                        others.add(other.id());
                        limits.add(new Note(SolverMessage.LIMIT_DRAWER, other.label()));
                    }
                } finally {
                    ctx.drawerLower[e] = savedLower;
                    ctx.drawerUpper[e] = savedUpper;
                }
            }
            for (int p = 0; p < ctx.model.pools.size(); p++) {
                if (probes++ >= MAX_PROBES || ctx.budget.expired()) break;
                ctx.droppedPools.add(p);
                try {
                    if (keptWithout(ctx, d)) {
                        groups++;
                        limits.add(
                            new Note(
                                SolverMessage.LIMIT_GROUP,
                                ctx.model.pools.get(p)
                                    .capacity()));
                    }
                } finally {
                    ctx.droppedPools.remove(p);
                }
            }
            for (final Note limit : limits) {
                notes.add(new Note(SolverMessage.DRAWER_LIMITED_BY, row.label(), limit));
            }
            shortfalls.add(
                new DrawerReadout.Shortfall(row.id(), row.rule(), row.rate(), base.total[d], nodes, others, groups));
        }
        return new Outcome(lower, upper, unmet, shortfalls, notes);
    }

    /** Whether drawer {@code d} needed slack at the relaxed point. */
    private static boolean missed(final SolveContext ctx, final Relaxed relaxed, final int d) {
        if (Double.isNaN(ctx.drawerLower[d]) && Double.isNaN(ctx.drawerUpper[d])) return false;
        final double bound = Math.max(
            Double.isNaN(ctx.drawerLower[d]) ? 0 : Math.abs(ctx.drawerLower[d]),
            Double.isNaN(ctx.drawerUpper[d]) ? 0 : Math.abs(ctx.drawerUpper[d]));
        return relaxed.slack[d] > ctx.heuristics.numerics().validateTol * Math.max(1.0, bound);
    }

    /** Whether drawer {@code d} is kept by the relaxed model as the context now stands. */
    private static boolean keptWithout(final SolveContext ctx, final int d) {
        final Relaxed relaxed = solve(ctx);
        return relaxed != null && !missed(ctx, relaxed, d);
    }

    /** The relaxed LP over the context's current pins, bounds and pools; null when infeasible. */
    @Nullable
    private static Relaxed solve(final SolveContext ctx) {
        final ModelBuilder builder = ModelBuilder.over(ctx)
            .extents(new double[ctx.model.machines.size()])
            .pools()
            .flows()
            .externals()
            .conservation()
            .drawersRelaxed();
        final Optimisation.Result result = builder.solve("drawer relaxation");
        if (!result.getState()
            .isFeasible()) return null;
        final Handles h = builder.handles();
        final double[] extents = values(h.extentVars());
        final double[] externals = values(h.extVars());
        final int drawers = ctx.model.drawers.size();
        final double[] slack = new double[drawers];
        final double[] total = new double[drawers];
        for (int d = 0; d < drawers; d++) {
            slack[d] = builder.drawerSlack(d);
            total[d] = ctx.drawerTotal(d, extents, externals);
        }
        return new Relaxed(slack, total);
    }

    private static double[] values(final Variable[] vars) {
        final double[] out = new double[vars.length];
        for (int i = 0; i < vars.length; i++) {
            final Number v = vars[i].getValue();
            out[i] = v == null ? 0 : v.doubleValue();
        }
        return out;
    }
}
