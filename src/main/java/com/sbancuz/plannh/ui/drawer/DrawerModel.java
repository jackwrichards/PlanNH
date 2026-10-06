package com.sbancuz.plannh.ui.drawer;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.Nullable;

import com.sbancuz.plannh.data.flowchart.Drawer;
import com.sbancuz.plannh.data.flowchart.balancer.BalanceResult;
import com.sbancuz.plannh.data.flowchart.balancer.DrawerReadout;
import com.sbancuz.plannh.ui.Resources;

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
}
