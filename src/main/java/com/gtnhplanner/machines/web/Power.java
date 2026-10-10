package com.gtnhplanner.machines.web;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * A node's power as the website reads it (src/lib/solver/power.ts and power-input-rules.ts): whether the machine is a
 * multiblock, the tier it runs at, its hatches and working amps, its power pool and the summed-voltage ordinal its own
 * formulas see.
 */
public final class Power {

    private Power() {}

    // region power-input-rules.ts: ProcessingLogic power overrides from GT5U, for table and scraped machines alike

    private static final Pattern NO_TIER_SKIP = Pattern.compile(
        "precise (?:auto-)?assembler|industrial arc furnace|steam (grinder|squasher|separator|purifier|presser|blender|fuser|hearth)");

    private static final Pattern NO_AMPERAGE_OVERCLOCK = Pattern.compile(
        "fusion|nano forge|transcendent plasma mixer|godforge|(?:smelting|molten|plasma|exotic) module|nanochip|(?:assembly matrix|biological coordination|board processor|cutting chamber|encasement wrapper|etching array|optical organizer|smd processor|superconductor splitter|splitter|wire tracer) module|industrial arc furnace|steam (grinder|squasher|separator|purifier|presser|blender|fuser|hearth)");

    /** maxInputTierSkips: how many tiers above its hatches a recipe may sit and still start. */
    public static double maxInputTierSkips(@Nullable final String machine) {
        final MachineTable.Behaviour b = MachineTable.behaviour(machine);
        if (b != null && b.unlimitedTierSkip) return Double.POSITIVE_INFINITY;
        return NO_TIER_SKIP.matcher(lower(machine))
            .find() ? 0 : 1;
    }

    /** hasAmperageOverclock: whether stacked amps buy overclocks past the hatch tier. */
    public static boolean hasAmperageOverclock(@Nullable final String machine) {
        return !NO_AMPERAGE_OVERCLOCK.matcher(lower(machine))
            .find();
    }

    private static String lower(@Nullable final String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    // endregion

    /** getNodePowerBudget: a fusion reactor's pool, or a multiblock's hatch tier times amps, or a typed EU/t. */
    @Nullable
    public static Double budget(final Web.Recipe recipe, final Web.Node node) {
        final Fusion.Stats fusion = Fusion.stats(recipe);
        if (fusion != null) return fusion.poolEuT();
        if (!isMultiblock(recipe)) return null;
        if (node.hatchVoltageTier != null && node.hatchAmps != null
            && Double.isFinite(node.hatchAmps)
            && node.hatchAmps >= 0) return Tiers.maxEuT(node.hatchVoltageTier) * node.hatchAmps;
        final Double budget = node.powerEuT;
        return budget != null && Double.isFinite(budget) && budget >= 0 ? budget : null;
    }

    /**
     * isMultiblockRecipe: a reactor, a multiblock profile, or (with no handlers on the recipe) a table entry not
     * marked single.
     */
    public static boolean isMultiblock(final Web.Recipe recipe) {
        if (Fusion.machine(recipe.machineType) != null) return true;
        if (recipe.machineProfile != null && "multiblock".equals(recipe.machineProfile.kind)) return true;
        if (recipe.machineHandlers != null && !recipe.machineHandlers.isEmpty()) return false;
        final MachineTable.Behaviour b = MachineTable.behaviour(recipe.machineType);
        return b != null && !b.single;
    }

    /** rawInputTier: a typed EU/t reads as the highest tier inside it, never under the recipe's minimum. */
    private static String rawInputTier(final Web.Recipe recipe, final Web.Node node) {
        final String minimum = Tiers.recipeMinimum(recipe.eut, recipe.minimumTier == null ? "ULV" : recipe.minimumTier);
        final Double budget = budget(recipe, node);
        final String supplied = Tiers.withinEuT(budget == null ? 0 : budget);
        return Tiers.index(minimum) > Tiers.index(supplied) ? minimum : supplied;
    }

    /**
     * getNodeRunTier: a singleblock floored at the recipe's minimum (Tiers.runTier); a multiblock at its hatches as
     * picked, under its casing's voltage limit.
     */
    public static String runTier(final Web.Recipe recipe, final Web.Node node) {
        final Fusion.Machine fusion = Fusion.machine(recipe.machineType);
        if (fusion != null) return fusion.tier();
        if (!isMultiblock(recipe)) return Tiers.runTier(recipe, node.overclockTier);
        final MachineTable.Behaviour b = MachineTable.behaviour(recipe.machineType);
        final double limit = b != null && b.inputVoltageTierLimit != null
            ? b.inputVoltageTierLimit.applyAsInt(settings(node))
            : Double.POSITIVE_INFINITY;
        if ("eut".equals(node.powerInputMode)) return limited(rawInputTier(recipe, node), limit);
        if (node.hatchVoltageTier != null) return limited(node.hatchVoltageTier, limit);
        final Double budget = budget(recipe, node);
        if (budget != null) return limited(Tiers.withinEuT(budget), limit);
        return limited(Tiers.resolve(node.overclockTier, Tiers.recipeMinimum(recipe)), limit);
    }

    private static String limited(final String tier, final double limit) {
        final String at = Tiers.at((int) Math.min(Tiers.index(tier), limit));
        return at != null ? at : tier;
    }

    static Map<String, String> settings(final Web.Node node) {
        return node.machineConfigTiers != null ? node.machineConfigTiers : Collections.emptyMap();
    }

    /** getNodeEnergyHatches: a multiblock's hatch count (one for an exotic hatch), 1 on a singleblock. */
    public static int energyHatches(final Web.Recipe recipe, final Web.Node node) {
        if (!isMultiblock(recipe)) return 1;
        if (EnergyHatches.type(node.energyHatchType)
            .exotic()) return 1;
        final double hatches = node.energyHatches != null ? node.energyHatches : 1;
        return Double.isFinite(hatches) ? (int) Math.max(1, Math.floor(hatches)) : 1;
    }

    /** getHatchAmps: one standard hatch is clamped to 1 A; two or more work at 2 A each. */
    public static double hatchAmps(final int hatches) {
        return hatches <= 1 ? 1 : 2.0 * hatches;
    }

    /** getNodePowerAmps: the amps the power maths run on. */
    public static double amps(final Web.Recipe recipe, final Web.Node node) {
        final Fusion.Machine fusion = Fusion.machine(recipe.machineType);
        if (fusion != null) return fusion.compact() ? 64 * fusion.mark() : 1;
        final MachineTable.Behaviour b = MachineTable.behaviour(recipe.machineType);
        if (isMultiblock(recipe)) {
            if ("eut".equals(node.powerInputMode)) {
                final Double budget = budget(recipe, node);
                return (budget == null ? 0 : budget) / Tiers.maxEuT(rawInputTier(recipe, node));
            }
            if (node.hatchVoltageTier != null && node.hatchAmps != null) return node.hatchAmps;
            final Double budget = budget(recipe, node);
            if (budget != null) return budget / Tiers.maxEuT(Tiers.withinEuT(budget));
            final EnergyHatches.Type type = EnergyHatches.type(node.energyHatchType);
            if (type.exotic()) return type.amps();
            final int hatches = energyHatches(recipe, node);
            if (b != null && b.fullPowerPool) return 2.0 * hatches;
            return hatchAmps(hatches);
        }
        return b != null && b.amperage != null ? b.amperage : 1;
    }

    /** getPowerPoolEuT: tier voltage times working amps. */
    public static double poolEuT(final Web.Recipe recipe, final Web.Node node, final String tier) {
        return Tiers.maxEuT(tier) * amps(recipe, node);
    }

    /**
     * getEffectiveVoltageOrdinal: the tier of the SUMMED hatch voltage (GTUtility.getTier(getMaxInputVoltage())), so
     * two MV hatches already count as HV for per-tier parallels and the blast furnaces' heat bonus.
     */
    public static int effectiveVoltageOrdinal(final Web.Recipe recipe, final Web.Node node, final String tier) {
        final MachineTable.Behaviour b = MachineTable.behaviour(recipe.machineType);
        final boolean fullPowerPool = b != null && b.fullPowerPool;
        final Double budget = budget(recipe, node);
        if (budget != null) {
            final double summed = fullPowerPool ? budget : Math.max(Tiers.maxEuT(tier), budget / 2);
            return Tiers.index(Tiers.forEuT(summed));
        }
        final int hatches = energyHatches(recipe, node);
        final double summed = Tiers.maxEuT(tier) * hatches * (fullPowerPool ? 2 : 1);
        if (!Double.isFinite(summed)) return Tiers.index(tier);
        return Tiers.index(Tiers.forEuT(summed));
    }
}
