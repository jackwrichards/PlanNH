package com.gtnhplanner.machines.web;

import javax.annotation.Nullable;

/**
 * Whether a build starts, from the game's own checks (src/lib/solver/power-report.ts): there is no slow mode in GT, so
 * a machine runs as reported or sits idle ("ok", "under-powered", "over-tier"), with the pool, the draw and a reason.
 * Also what a steam machine burns (getNodeSteamReport).
 */
public final class PowerReport {

    private PowerReport() {}

    public static final String OK = "ok", UNDER_POWERED = "under-powered", OVER_TIER = "over-tier";

    /** NodePowerReport. */
    public record Report(String state, @Nullable String recipeGateReason, String tier, String minimumTier, int hatches,
        @Nullable String hatchTypeLabel, @Nullable String hatchChip, boolean typedBudget, boolean isMultiblock,
        double amps, double poolEuT, double singleDrawEuT, double parallels, double drawEuT, int overclockSteps,
        int perfectOverclockSteps, double perfectSpeedFactor, double perfectEuFactor, double usage) {

        /** isPowerStalled. */
        public boolean stalled() {
            return !OK.equals(state);
        }
    }

    /** hasPowerReport: a recipe with a draw and a duration (crops and bees are the website's alone). */
    public static boolean has(final Web.Recipe recipe) {
        return Math.abs(recipe.eut) > 0 && recipe.durationTicks > 0;
    }

    /** getNodePowerReport. */
    public static Report report(final Web.Recipe recipe, final Web.Node node) {
        final Web.Recipe effective = recipe.machineType != null ? RecipeRules.applyHandler(recipe, node) : recipe;
        final String minimumTier = Tiers.recipeMinimum(effective);
        final String tier = Power.runTier(effective, node);
        final boolean multiblock = Power.isMultiblock(effective);
        final int hatches = Power.energyHatches(effective, node);
        final EnergyHatches.Type hatchType = EnergyHatches.type(node.energyHatchType);
        final boolean typedBudget = Power.budget(effective, node) != null;
        final double amps = Power.amps(effective, node);
        final double poolEuT = Tiers.maxEuT(tier) * amps;

        final double rawEuT = Math.abs(effective.eut);
        final double eutMultiplier = MachineEffects.eutMultiplier(effective, node);
        final double heatDiscount = Heat
            .discount(effective, node, tier, Power.effectiveVoltageOrdinal(effective, node, tier));
        final double singleDrawEuT = Math.ceil(rawEuT * eutMultiplier * heatDiscount);

        final Overclock.Stats stats = Overclock.stats(recipe, node);
        final double parallels = MachineEffects.parallelMultiplier(effective, node);
        final double drawEuT = Math.abs(stats.eut()) * parallels;
        final MachineTable.Behaviour behaviour = MachineTable.behaviour(effective.machineType);
        final Integer voltageLimit = behaviour != null && behaviour.inputVoltageTierLimit != null
            ? behaviour.inputVoltageTierLimit.applyAsInt(Power.settings(node))
            : null;
        final String casingGate = voltageLimit != null && Tiers.index(Tiers.forEuT(rawEuT)) > voltageLimit
            ? "Machine casing voltage is too low for this recipe. Select a higher machine casing; UHV casings remove the limit."
            : null;
        final Fusion.Stats fusion = Fusion.stats(effective);
        final String fusionGate = fusion != null && !fusion.eligible()
            ? fusion.recipeMark() == null ? "Fusion startup metadata is unavailable for this recipe."
                : "This recipe requires a Mk-" + fusion.recipeMark() + " fusion reactor or higher."
            : null;
        String gate = fusionGate;
        if (gate == null && behaviour != null && behaviour.recipeGate != null)
            gate = behaviour.recipeGate.apply(MachineEffects.context(effective, node));
        if (gate == null) gate = casingGate;

        final boolean exoticChip = multiblock && !typedBudget && hatchType.exotic();
        return new Report(
            gate != null ? OVER_TIER : state(effective, tier, multiblock, poolEuT, singleDrawEuT),
            gate,
            tier,
            minimumTier,
            hatches,
            exoticChip ? hatchType.label() : null,
            exoticChip ? hatchType.chip() : null,
            typedBudget,
            multiblock,
            amps,
            poolEuT,
            singleDrawEuT,
            parallels,
            drawEuT,
            stats.overclockSteps(),
            stats.perfectOverclockSteps(),
            stats.perfectSpeedFactor(),
            stats.perfectEuFactor(),
            Double.isFinite(poolEuT) && poolEuT > 0 ? drawEuT / poolEuT : 0);
    }

    private static String state(final Web.Recipe effective, final String tier, final boolean multiblock,
        final double poolEuT, final double singleDrawEuT) {
        final double rawEuT = Math.abs(effective.eut);
        if (!(rawEuT > 0) || !Double.isFinite(poolEuT)) return OK;

        // A structural minimum above the recipe's own draw cannot be bought with amps.
        final String powerTier = Tiers.forEuT(rawEuT);
        final String declared = Tiers.resolve(effective.minimumTier, powerTier);
        if (Tiers.index(declared) > Tiers.index(powerTier) && Tiers.index(tier) < Tiers.index(declared))
            return OVER_TIER;

        if (multiblock) {
            if (poolEuT <= 0 && singleDrawEuT > 0) return UNDER_POWERED;
            // getAllowedTierSkip: a recipe more than one tier above the hatches never runs, however many amps.
            if (rawEuT > Tiers.maxEuT(tier) * Math.pow(4, Power.maxInputTierSkips(effective.machineType)))
                return OVER_TIER;
            // ParallelHelper.determineParallel: the pool must carry one whole parallel.
            if (singleDrawEuT > poolEuT) return UNDER_POWERED;
            return OK;
        }
        // A singleblock's recipe over its power belongs to a higher machine.
        if (singleDrawEuT > poolEuT) return OVER_TIER;
        return OK;
    }

    /** describePowerStall: one line saying why a build will not start. */
    @Nullable
    public static String describeStall(final Report report) {
        if (report.recipeGateReason() != null) return report.recipeGateReason();
        if (UNDER_POWERED.equals(report.state())) {
            if (report.typedBudget()) return "Needs " + Js.str(report.singleDrawEuT())
                + " EU/t. Supplied "
                + formatBudget(report.poolEuT())
                + ".";
            final String supply = report.hatchTypeLabel() != null
                ? "the " + report.tier() + " " + report.hatchTypeLabel() + " supplies"
                : report.hatches() + "x "
                    + report.tier()
                    + " "
                    + (report.hatches() == 1 ? "hatch supplies" : "hatches supply");
            return "Underpowered: the recipe draws " + Js.str(report.singleDrawEuT())
                + " EU/t but "
                + supply
                + " "
                + Js.str(report.poolEuT())
                + " EU/t. Add amperage or raise the tier.";
        }
        if (OVER_TIER.equals(report.state())) return "Won't run at " + report.tier()
            + ": this recipe needs at least "
            + report.minimumTier()
            + (report.isMultiblock() ? " hatches (amps cannot skip more than one tier)" : "")
            + ".";
        return null;
    }

    private static String formatBudget(final double euT) {
        return euT == Math.rint(euT) ? Js.str(euT) : String.format(java.util.Locale.ROOT, "%.1f", euT);
    }

    /** NodeSteamReport: what a steam machine burns, in L/t. */
    public record Steam(double drawSteamPerTick, double singleDrawSteamPerTick, double parallels, boolean isMultiblock,
        boolean highPressure) {}

    /**
     * getNodeSteamReport: a singleblock burns 2 L per EU (a bronze machine the recipe's EU at twice the duration, a
     * high pressure one twice the EU); a steam multiblock 1 L per EU of recipe EU x 1.25 x its pressure tier per
     * parallel. Smelting bills GT's 4 EU/t furnace recipe.
     */
    @Nullable
    public static Steam steam(final Web.Recipe recipe, final Web.Node node) {
        if (recipe.machineType == null) return null;
        final Web.Handler handler = RecipeRules.selectedHandler(recipe, node);
        if (!RecipeRules.isSteamHandler(handler)) return null;
        final double baseEut = Math.abs(recipe.eut) > 0 ? Math.abs(recipe.eut)
            : RecipeRules.isSmeltingRecipeMap(recipe) ? RecipeRules.GT_FURNACE_RECIPE_EUT : 0;
        if (!(baseEut > 0) || recipe.durationTicks <= 0) return null;

        final Web.Recipe effective = RecipeRules.applyHandler(recipe, node);
        if (effective.machineProfile != null && "multiblock".equals(effective.machineProfile.kind)) {
            final int pressureTier = MachineEffects.context(effective, node)
                .tier(MachineControls.STEAM_PRESSURE) + 1;
            final double parallels = MachineEffects.parallelMultiplier(effective, node);
            final double single = baseEut * 1.25 * pressureTier;
            return new Steam(Math.ceil(single * parallels), single, parallels, true, pressureTier == 2);
        }
        final boolean highPressure = RecipeRules.isHighPressureSteamHandler(handler);
        final double single = baseEut * (highPressure ? 2 : 1) * 2;
        return new Steam(Math.ceil(single), single, 1, false, highPressure);
    }
}
