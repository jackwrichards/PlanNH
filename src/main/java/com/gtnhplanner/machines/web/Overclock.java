package com.gtnhplanner.machines.web;

import javax.annotation.Nullable;

/**
 * A recipe overclocked as the website computes it (src/lib/solver/overclock.ts, getOverclockedRecipeStats): the run
 * tier, the steps (perfect first, then normal), the duration in ticks as the game runs it and the EU/t. Branches in the
 * website's order: power cards as written, the picked machine's handler, fusion, the Extreme Entity Crusher, the
 * game's own ladder where the website prefers it ({@link RuntimeCalculation}), then the generic path (table or scraped
 * coefficients, heat discount, parallels paid before overclocks, a singleblock's voltage cap, quantising). Its crops
 * and bees, and the Tree Growth Simulator (the mod's own model), are not here.
 */
public final class Overclock {

    private Overclock() {}

    /** OverclockedRecipeStats. */
    public record Stats(String tier, String minimumTier, int overclockSteps, int perfectOverclockSteps,
        double perfectSpeedFactor, double perfectEuFactor, double durationTicks, double eut) {}

    /** getOverclockedRecipeStats. */
    public static Stats stats(final Web.Recipe recipe, final Web.Node node) {
        if (recipe.power != null) {
            final String minimum = Tiers.recipeMinimum(recipe);
            return new Stats(minimum, minimum, 0, 0, 4, 4, recipe.durationTicks, recipe.eut);
        }
        final Web.Recipe effective = recipe.machineType != null ? RecipeRules.applyHandler(recipe, node) : recipe;
        final String minimumTier = Tiers.recipeMinimum(effective);
        final Fusion.Stats fusion = Fusion.stats(effective);
        if (fusion != null) return new Stats(
            fusion.machine()
                .tier(),
            minimumTier,
            fusion.steps(),
            fusion.steps(),
            fusion.factor(),
            fusion.factor(),
            quantiseDurationToTicks(fusion.durationTicks(), true),
            fusion.eut());
        // A multiblock's pick stands below the recipe's minimum (the power report names it); a singleblock is floored.
        final String tier = Power.runTier(effective, node);
        final ExtremeEntityCrusher.Metadata eec = ExtremeEntityCrusher.metadata(effective);
        if (eec != null) {
            // kubatech's own overclock, infernals and ritual; the duration is per kill, past-floor output folded in.
            final ExtremeEntityCrusher.Stats s = ExtremeEntityCrusher.stats(
                eec,
                ExtremeEntityCrusher.settings(node.machineConfigTiers),
                Tiers.maxEuT(tier) * Power.amps(effective, node));
            return new Stats(tier, minimumTier, s.steps(), s.steps(), 4, 4, s.durationTicks(), s.eut());
        }

        final Web.RuntimeVariant runtime = RuntimeCalculation.select(effective, node);
        if (runtime != null) {
            final String runtimeTier = RuntimeCalculation.tier(runtime, tier);
            return new Stats(
                runtimeTier,
                minimumTier,
                RuntimeCalculation.overclockSteps(runtimeTier, minimumTier),
                0,
                4,
                4,
                runtime.durationTicks,
                runtime.eut);
        }

        final double durationMultiplier = effective.machineType != null
            ? MachineEffects.durationMultiplier(effective, node)
            : 1;
        final double eutMultiplier = effective.machineType != null ? MachineEffects.eutMultiplier(effective, node) : 1;

        // The heat discount comes first: ParallelHelper folds it into the draw the parallels are paid with.
        final int ordinal = Power.effectiveVoltageOrdinal(effective, node, tier);
        final double heatDiscount = Heat.stats(effective, node, tier, 0, ordinal)
            .heatDiscountMultiplier();

        // Parallels are spent before overclocks: only the headroom left after paying for them buys steps.
        final double parallels = effective.machineType != null ? MachineEffects.parallelMultiplier(effective, node) : 1;
        final double parallelEuT = Math.abs(effective.eut) * eutMultiplier * heatDiscount * parallels;

        // OverclockCalculator: whole power-of-four steps of the machine's power (tier voltage x working amps) over the
        // recipe's draw, a draw under 32 EU/t billed as 32.
        final boolean subTickCapable = Power.isMultiblock(effective);
        final double machinePower = Tiers.maxEuT(tier) * Power.amps(effective, node);
        final double recipePower = Math.max(Math.ceil(parallelEuT), 32);
        double affordableSteps = Double.isFinite(machinePower) ? floorLog4(Math.floor(machinePower / recipePower))
            : Double.POSITIVE_INFINITY;

        // A singleblock (or a machine without amperage overclocks) is also capped by voltage tier against the raw draw.
        if ((!subTickCapable || !Power.hasAmperageOverclock(effective.machineType)) && Double.isFinite(machinePower)) {
            final int machineVoltageTier = Math
                .max(ceilLog4((subTickCapable ? Tiers.maxEuT(tier) : machinePower) / 8), 1);
            final int recipeVoltageTier = Math.max(ceilLog4(Math.abs(effective.eut) / 8), 1);
            affordableSteps = Math.min(affordableSteps, machineVoltageTier - recipeVoltageTier);
        }
        final double affordable = Math.max(0, affordableSteps);
        final Heat.Stats heat = Heat.stats(effective, node, tier, clampInt(affordable), ordinal);
        final MachineTable.Rule rule = rule(effective, node, heat.heatOverclockSteps());

        final double perfectSteps = Math.min(rule.maxPerfect(), affordable);
        final double normalSteps = Math.min(rule.maxNormal(), affordable - perfectSteps);
        final double steps = perfectSteps + normalSteps;

        final MachineTable.Behaviour behaviour = MachineTable.behaviour(effective.machineType);
        final double raw = effective.durationTicks / Math.pow(rule.multiplier(), perfectSteps)
            / Math.pow(2, normalSteps)
            * durationMultiplier;
        final double duration = behaviour != null && behaviour.quantiseDuration != null
            ? behaviour.quantiseDuration.applyAsDouble(raw)
            : quantiseDurationToTicks(raw, subTickCapable);
        return new Stats(
            tier,
            minimumTier,
            clampInt(steps),
            clampInt(perfectSteps),
            rule.multiplier(),
            rule.euFactor(),
            duration,
            effective.eut * heat.heatDiscountMultiplier()
                * eutMultiplier
                * Math.pow(rule.euFactor(), perfectSteps)
                * Math.pow(4, normalSteps));
    }

    private static int clampInt(final double n) {
        return n >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) n;
    }

    /** Whole power-of-four steps that fit in {@code ratio}: 0 under 4. */
    static int floorLog4(final double ratio) {
        int steps = 0;
        for (double power = 4; power <= ratio; power *= 4) steps++;
        return steps;
    }

    /** The smallest n with 4^n at least {@code ratio}: the game's log4ceil. */
    static int ceilLog4(final double ratio) {
        int steps = 0;
        double power = 1;
        while (power < ratio) {
            steps++;
            power *= 4;
        }
        return steps;
    }

    /**
     * quantiseDurationToTicks: GT truncates above one tick; below it a multiblock banks the rest as parallels
     * (calculateMultiplierUnderOneTick, ceil(1 / duration)), so the duration rounds down to 1/n; a singleblock sits at
     * one tick.
     */
    public static double quantiseDurationToTicks(final double durationTicks, final boolean subTickCapable) {
        if (!Double.isFinite(durationTicks) || durationTicks <= 0) return 1;
        if (durationTicks > 1) return Math.floor(durationTicks);
        return subTickCapable ? 1 / Math.ceil(1 / durationTicks) : 1;
    }

    /** resolveOverclockRule: the table's rule, heat's perfect-then-normal, or the dataset's perfect flag. */
    private static MachineTable.Rule rule(final Web.Recipe recipe, final Web.Node node, final int heatSteps) {
        final MachineTable.Behaviour behaviour = MachineTable.behaviour(recipe.machineType);
        @Nullable
        final Object spec = behaviour != null
            ? MachineTable.overclockSpec(behaviour, MachineEffects.context(recipe, node))
            : null;
        if (MachineTable.HEAT.equals(spec)) return MachineTable.perfectThenNormal(heatSteps);
        if (spec instanceof final MachineTable.Rule r) return r;
        return recipe.machineProfile != null && Boolean.TRUE.equals(recipe.machineProfile.perfectOverclock)
            ? MachineTable.perfect()
            : MachineTable.normal();
    }
}
