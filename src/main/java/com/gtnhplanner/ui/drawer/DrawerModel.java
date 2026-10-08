package com.gtnhplanner.ui.drawer;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.Nullable;

import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.balancer.BalanceResult;
import com.gtnhplanner.data.flowchart.balancer.DrawerReadout;
import com.gtnhplanner.ui.Resources;

/** What a drawer shows: its resource, kind and rule, and what the latest solve moved through it. Immutable. */
public final class DrawerModel {

    public final Drawer drawer;
    public final Drawer.Kind kind;
    public final Drawer.Rule rule;
    /** The rule's rate, per second (fluids in L/s). */
    public final double target;
    public final String label;
    public final ItemStack item;
    public final FluidStack fluid;
    /** What the plan moves through the drawer, per second. */
    public final double rate;
    public final boolean unmet;
    @Nullable
    public final DrawerReadout.Shortfall shortfall;
    public final boolean linked;

    private DrawerModel(final Drawer drawer, final double rate, final boolean unmet,
        @Nullable final DrawerReadout.Shortfall shortfall) {
        this.drawer = drawer;
        this.kind = drawer.getKind();
        this.rule = drawer.effectiveRule();
        this.target = drawer.getRate();
        final String key = drawer.getResourceKey();
        this.label = drawer.getLabel()
            .isEmpty() ? Resources.name(key) : drawer.getLabel();
        this.fluid = Resources.fluid(key);
        this.item = fluid == null ? Resources.item(key) : null;
        this.rate = rate;
        this.unmet = unmet;
        this.shortfall = shortfall;
        this.linked = !drawer.getLinks()
            .isEmpty();
    }

    public static DrawerModel of(final Drawer drawer, @Nullable final BalanceResult result) {
        if (result == null) return new DrawerModel(drawer, 0, false, null);
        final DrawerReadout readout = result.drawers();
        return new DrawerModel(
            drawer,
            readout.rate(drawer.getId()),
            !readout.met(drawer.getId()),
            readout.shortfall(drawer.getId()));
    }

    public boolean isFluid() {
        return fluid != null;
    }

    /** An EU drawer: a generator's output, always shown in EU/t whatever the board's rate unit. */
    public boolean isPower() {
        return Resources.isPower(drawer.getResourceKey());
    }

    /** A rate (per second) as the drawer shows it: EU/t for EU, the board's unit for the rest. */
    public double shown(final double perSecond, final com.gtnhplanner.ui.theme.Fmt.RateUnit unit) {
        return isPower() ? perSecond / 20 : perSecond * unit.perSecond;
    }

    /** The unit after a shown figure: " EU/t", " L/s", "/s" (without the space when {@code tight}). */
    public String suffix(final com.gtnhplanner.ui.theme.Fmt.RateUnit unit, final boolean tight) {
        if (isPower()) return tight ? "EU/t" : " EU/t";
        return (isFluid() ? tight ? "L" : " L" : "") + unit.suffix;
    }

    /** A rate (per second) with its unit, as the tooltips say it. */
    public String rate(final double perSecond, final com.gtnhplanner.ui.theme.Fmt.RateUnit unit) {
        return isPower() ? com.gtnhplanner.ui.theme.Fmt.power(perSecond / 20) + " EU/t"
            : com.gtnhplanner.ui.theme.Fmt.rate(perSecond, unit, isFluid());
    }
}
