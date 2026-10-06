package com.sbancuz.plannh.data.flowchart.balancer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.sbancuz.plannh.data.flowchart.Drawer;

/**
 * What the plan does at each drawer, and which drawers' rules it cannot keep.
 *
 * @param rates      per drawer id, the total flowing through it in units per second: what the
 *                   linked outputs deliver to a product, byproduct or trash, what a source supplies
 *                   to its linked inputs. Every drawer on the chart has an entry after a solve; empty
 *                   when nothing was solved.
 * @param unmet      drawers whose rule the plan does not keep (beyond the validation tolerance).
 * @param shortfalls for each unmet drawer the relaxation could measure, how far it gets and what
 *                   holds it back.
 */
public record DrawerReadout(Map<UUID, Double> rates, Set<UUID> unmet, List<Shortfall> shortfalls) {

    /** Nothing solved, nothing known. */
    public static final DrawerReadout NONE = new DrawerReadout(Map.of(), Set.of(), List.of());

    public DrawerReadout {
        rates = Collections.unmodifiableMap(new LinkedHashMap<>(rates));
        unmet = Collections.unmodifiableSet(new LinkedHashSet<>(unmet));
        shortfalls = List.copyOf(shortfalls);
    }

    /**
     * One drawer whose rule the chart cannot keep, measured by the relaxation: the most (or, for an
     * at-most rule, the least) the drawer can get while every pin and every other keepable rule
     * holds, and the constraints that, dropped one at a time, would let it be kept. A limit is a
     * pinned machine ({@code limitingNodes}), another drawer's rule ({@code limitingDrawers}) or a
     * capped machine group ({@code limitingGroups}, by capacity row order). All three empty means
     * the chart's own shape caps it (e.g. a drawer linked to nothing).
     */
    public record Shortfall(UUID drawer, Drawer.Rule rule, double target, double reachable, List<UUID> limitingNodes,
        List<UUID> limitingDrawers, int limitingGroups) {

        public Shortfall {
            limitingNodes = List.copyOf(limitingNodes);
            limitingDrawers = List.copyOf(limitingDrawers);
        }
    }

    /** Whether the drawer's rule holds (true for drawers with no rule, and for unknown ids). */
    public boolean met(final UUID drawer) {
        return !unmet.contains(drawer);
    }

    /** The drawer's total in units per second; 0 when nothing was solved. */
    public double rate(final UUID drawer) {
        return rates.getOrDefault(drawer, 0.0);
    }

    @Nullable
    public Shortfall shortfall(final UUID drawer) {
        for (final Shortfall s : shortfalls) {
            if (s.drawer()
                .equals(drawer)) return s;
        }
        return null;
    }

    /** The read-out at a committed point: every drawer's total, checked against its bounds. */
    static DrawerReadout of(final SolveContext ctx, final double[] extents, final double[] externals) {
        final Map<UUID, Double> rates = new LinkedHashMap<>();
        final Set<UUID> unmet = new LinkedHashSet<>(ctx.unmetDrawers);
        for (int d = 0; d < ctx.model.drawers.size(); d++) {
            final UUID id = ctx.model.drawers.get(d)
                .id();
            final double total = ctx.drawerTotal(d, extents, externals);
            rates.put(id, total);
            if (!ctx.drawerHolds(d, total)) unmet.add(id);
        }
        return new DrawerReadout(rates, unmet, ctx.shortfalls);
    }

    /** The read-out of a run that committed no point: only what the context already knew was unmet. */
    static DrawerReadout unsolved(final SolveContext ctx) {
        if (ctx.unmetDrawers.isEmpty() && ctx.shortfalls.isEmpty()) return NONE;
        return new DrawerReadout(Map.of(), ctx.unmetDrawers, ctx.shortfalls);
    }
}
