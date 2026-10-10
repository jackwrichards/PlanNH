package com.gtnhplanner.machines.web;

import javax.annotation.Nullable;

/**
 * How the website seeds and carries a multiblock's hatches (src/lib/solver/hatch-input.ts): a new card at its recipe's
 * tier with whole amps enough for every structural parallel, and a switch between a singleblock and a multiblock that
 * keeps the voltage it ran at.
 */
public final class HatchInput {

    private HatchInput() {}

    /** MAX_HATCH_AMPS: the highest selectable amperage. */
    public static final double MAX_AMPS = 16_777_216;

    /** A multiblock's hatches: their tier and amps. */
    public record Hatches(String tier, double amps) {}

    /**
     * normalizeHatchInput for a new card (not a migration): null when the recipe is no multiblock or draws nothing,
     * else its minimum tier and the whole amps its structural parallels draw, searched tier by summed-voltage tier
     * (parallels and heat discounts are constant within one).
     */
    @Nullable
    public static Hatches seed(final Web.Recipe recipe, final Web.Node node) {
        final Web.Recipe effective = RecipeRules.applyHandler(recipe, node);
        if (!Power.isMultiblock(effective) || effective.eut <= 0 || recipe.power != null) return null;
        final String tier = node.hatchVoltageTier != null ? node.hatchVoltageTier : Tiers.recipeMinimum(effective);
        final double voltage = Tiers.maxEuT(tier);
        if (node.powerEuT != null) return new Hatches(tier, node.powerEuT / voltage);
        final MachineTable.Behaviour b = MachineTable.behaviour(effective.machineType);
        final double factor = b != null && b.fullPowerPool ? 1 : 2;
        double lower = 0, amps = 0;
        for (int i = 0; i < Tiers.NAMES.length; i++) {
            final double maxEuT = Tiers.MAX_EUT[i];
            final double upper = factor * maxEuT;
            if (maxEuT < voltage) continue;
            final Web.Node candidate = copy(node);
            candidate.hatchVoltageTier = tier;
            candidate.hatchAmps = lower / voltage;
            final double perParallel = Math.abs(effective.eut) * MachineEffects.eutMultiplier(effective, candidate)
                * Heat.discount(effective, candidate, tier, Power.effectiveVoltageOrdinal(effective, candidate, tier));
            final double required = Math.ceil(MachineEffects.structuralParallels(effective, candidate) * perParallel);
            // Whole amps: nobody builds 0.94 A.
            final double whole = Math.max(1, Math.ceil(Math.max(lower, required) / voltage));
            if (whole * voltage <= upper || i == Tiers.NAMES.length - 1) {
                amps = whole;
                break;
            }
            lower = upper + 1;
        }
        return new Hatches(tier, amps);
    }

    /**
     * carryMachineVoltage: what a switch from one machine to another keeps. A multiblock becoming a singleblock takes
     * its hatch tier as the machine's tier; a singleblock becoming a multiblock gets one hatch of its tier, never below
     * the recipe's draw; otherwise null (nothing carried).
     */
    @Nullable
    public static Hatches carry(final Web.Recipe recipe, final Web.Node from, @Nullable final String toHandlerId) {
        if (recipe.power != null) return null;
        final Web.Recipe before = RecipeRules.applyHandler(recipe, from);
        final Web.Node target = new Web.Node();
        target.machineHandlerId = toHandlerId;
        final Web.Recipe after = RecipeRules.applyHandler(recipe, target);
        if (Fusion.machine(before.machineType) != null || Fusion.machine(after.machineType) != null) return null;
        final boolean was = Power.isMultiblock(before), is = Power.isMultiblock(after);
        if (was == is) return null;
        final String tier = Power.runTier(before, from);
        if (was) return new Hatches(tier, 0);
        if (after.eut <= 0) return null;
        final String powerTier = Tiers.recipePowerTier(after.eut);
        return new Hatches(Tiers.index(powerTier) > Tiers.index(tier) ? powerTier : tier, 1);
    }

    /** ampsForNewTier: under one amp becomes one; zero stays zero, typed decimals stay. */
    public static double ampsForNewTier(final double amps) {
        return amps > 0 && amps < 1 ? 1 : amps;
    }

    private static Web.Node copy(final Web.Node n) {
        final Web.Node c = new Web.Node();
        c.overclockTier = n.overclockTier;
        c.hatchVoltageTier = n.hatchVoltageTier;
        c.hatchAmps = n.hatchAmps;
        c.energyHatches = n.energyHatches;
        c.energyHatchType = n.energyHatchType;
        c.powerInputMode = n.powerInputMode;
        c.powerEuT = n.powerEuT;
        c.coilTier = n.coilTier;
        c.machineConfigTiers = n.machineConfigTiers;
        c.machineHandlerId = n.machineHandlerId;
        c.parallel = n.parallel;
        c.machineCount = n.machineCount;
        return c;
    }
}
