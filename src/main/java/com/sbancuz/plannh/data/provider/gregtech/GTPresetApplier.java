package com.sbancuz.plannh.data.provider.gregtech;

import static com.gtnewhorizon.gtnhlib.util.numberformatting.NumberFormatUtil.formatNumber;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.util.StatCollector;

import com.sbancuz.plannh.data.MachineProfile;
import com.sbancuz.plannh.data.RecipeContext;
import com.sbancuz.plannh.data.Settings;
import com.sbancuz.plannh.data.effect.EffectResult;
import com.sbancuz.plannh.data.machine.MachineVariant;
import com.sbancuz.plannh.data.provider.GTProvider;

import gregtech.api.logic.ProcessingRun;
import gregtech.api.logic.ResolvedRecipe;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.OverclockCalculator;

/**
 * GregTech's implementation of {@link MachineVariant#run}: turns "this node is a Maceration Stack with HSS-G coils at
 * IV" into a duration, a draw and a parallel count, from the machine's ProcessingSpec or else GT's own describer.
 *
 * <p>
 * A spec comes first, because GregTech checks at load that each machine runs exactly as its spec says, and it covers
 * the structure around a multiblock. A describer covers every singleblock exactly and for free.
 */
public final class GTPresetApplier {

    private GTPresetApplier() {}

    /**
     * The machine's own answer for this recipe, which is what {@code Effects.machineDriven} asks for. A recipe GregTech
     * would not start at this structure keeps its own numbers, with GregTech's reason. Null for a machine with neither
     * a spec nor a describer, or a recipe with no energy or duration for a describer to overclock.
     */
    @Nullable
    public static EffectResult run(@Nonnull final GTMachineIndex.MachineEntry entry, final RecipeContext ctx,
        final Map<String, Object> settings, final EffectResult recipe) {
        final int machines = MachineProfile.getInt(settings, Settings.MACHINES.key(), 1);
        final ResolvedRecipe resolved = GTSettings.resolved(ctx, settings);
        if (resolved != null) {
            // 0 is what an untouched node stores, and it means the machine's maximum
            final int userCap = MachineProfile.getInt(settings, Settings.PARALLELS.key(), 0);
            final ResolvedRecipe capped = userCap > 0 ? resolved.capParallel(userCap) : resolved;
            final ProcessingRun run = capped.calculate(applyOverrides(capped.toCalculator(), settings));
            if (!run.result()
                .wasSuccessful())
                return recipe.rejectedBecause(
                    run.result()
                        .getDisplayString());
            return effect(run, machines);
        }

        // a singleblock runs as GregTech's own describer says, the same calculator the machine and NEI use
        final GTRecipe gtRecipe = ctx.getOrDefault(GTProvider.GT_RECIPE, null);
        if (entry.describer() == null || gtRecipe == null || gtRecipe.mEUt <= 0 || gtRecipe.mDuration <= 0) return null;
        final OverclockCalculator calculator = applyOverrides(
            entry.describer()
                .createCalculator(gtRecipe),
            settings).calculate();
        return new EffectResult(calculator.getDuration(), calculator.getConsumption(), machines);
    }

    /** A node's numbers for a run GregTech worked out, at this many machines. */
    @Nonnull
    public static EffectResult effect(final ProcessingRun run, final int machines) {
        return new EffectResult(run.ticks(), run.euPerTick(), run.parallel() * machines)
            .withOutputs(expectedOutput(run.output()), details(run));
    }

    /** Each parallel's outputs on average: the chance it succeeds, times what a success yields. */
    private static double expectedOutput(final ProcessingRun.Output output) {
        return output.successChance() * output.yield();
    }

    /** What the run costs and gives besides EU/t, and its odds, which the node states beside its numbers. */
    @Nonnull
    private static List<String> details(final ProcessingRun run) {
        final List<String> details = new ArrayList<>();
        if (!run.output()
            .equals(ProcessingRun.Output.CERTAIN)) {
            details.add(
                StatCollector.translateToLocalFormatted(
                    "plannh.gt.output_odds",
                    GTSettings.percent(
                        run.output()
                            .successChance()),
                    GTSettings.percent(
                        run.output()
                            .yield())));
        }
        final ProcessingRun.RunEu eu = run.eu();
        if (eu.startup()
            .signum() != 0) {
            details.add(StatCollector.translateToLocalFormatted("plannh.gt.startup_eu", formatNumber(eu.startup())));
        }
        if (eu.perRun()
            .signum() != 0) {
            details.add(StatCollector.translateToLocalFormatted("plannh.gt.eu_per_run", formatNumber(eu.perRun())));
        }
        if (eu.generated()
            .signum() != 0) {
            details
                .add(StatCollector.translateToLocalFormatted("plannh.gt.eu_generated", formatNumber(eu.generated())));
        }
        return details;
    }

    /**
     * Lays the user's own numbers over the machine's. Only keys actually stored are applied - the map
     * is sparse, so anything absent stays exactly as the machine computed it, at full precision
     * rather than the rounded percentage the advanced rows display. A stored value that cannot be read
     * keeps the machine's own.
     *
     * <p>
     * This is what makes Advanced an override rather than a separate world: ticking it does not
     * change a single number until a row is edited.
     */
    @Nonnull
    public static OverclockCalculator applyOverrides(final OverclockCalculator calculator,
        final Map<String, Object> settings) {
        if (settings.containsKey(Settings.AMP.key())) {
            calculator.setAmperage(
                MachineProfile.getInt(settings, Settings.AMP.key(), (int) calculator.getMachineAmperage()));
        }
        if (settings.containsKey(Settings.SPEED.key())) {
            calculator.setDurationModifier(
                100.0 / Math.max(
                    1,
                    MachineProfile.getInt(
                        settings,
                        Settings.SPEED.key(),
                        GTSettings.percent(1 / calculator.getDurationModifier()))));
        }
        if (settings.containsKey(Settings.EUT_DISCOUNT.key())) {
            calculator.setEUtDiscount(
                MachineProfile
                    .getInt(settings, Settings.EUT_DISCOUNT.key(), GTSettings.percent(calculator.getEUtDiscount()))
                    / 100.0);
        }
        if (settings.containsKey(Settings.EUT_INCREASE_PER_OC.key())) {
            calculator.setEUtIncreasePerOC(
                MachineProfile.getInt(
                    settings,
                    Settings.EUT_INCREASE_PER_OC.key(),
                    GTSettings.percent(calculator.getEUtIncreasePerOC())) / 100.0);
        }
        if (settings.containsKey(Settings.DURATION_DECREASE_PER_OC.key())) {
            calculator.setDurationDecreasePerOC(
                MachineProfile.getInt(
                    settings,
                    Settings.DURATION_DECREASE_PER_OC.key(),
                    GTSettings.percent(calculator.getDurationDecreasePerOC())) / 100.0);
        }
        if (settings.containsKey(Settings.PERFECT_OC.key())
            && MachineProfile.getBool(settings, Settings.PERFECT_OC.key(), false)) {
            calculator.enablePerfectOC();
        }
        if (settings.containsKey(Settings.NO_OVERCLOCK.key())) {
            calculator.setNoOverclock(
                MachineProfile.getBool(settings, Settings.NO_OVERCLOCK.key(), calculator.isNoOverclock()));
        }
        if (settings.containsKey(Settings.LASER_OC.key())) {
            calculator.setLaserOC(MachineProfile.getBool(settings, Settings.LASER_OC.key(), calculator.isLaserOC()));
        }
        if (settings.containsKey(Settings.MAX_OVERCLOCKS.key())) {
            calculator.setMaxOverclocks(
                MachineProfile.getInt(settings, Settings.MAX_OVERCLOCKS.key(), calculator.getMaxOverclocks()));
        }
        if (settings.containsKey(Settings.MAX_REGULAR_OC.key())) {
            calculator.setMaxRegularOverclocks(
                MachineProfile.getInt(settings, Settings.MAX_REGULAR_OC.key(), calculator.getMaxRegularOverclocks()));
        }
        if (settings.containsKey(Settings.UNLIMITED_SKIPS.key())
            && MachineProfile.getBool(settings, Settings.UNLIMITED_SKIPS.key(), false)) {
            calculator.setUnlimitedTierSkips();
        } else if (settings.containsKey(Settings.MAX_TIER_SKIPS.key())) {
            // Zero is a real answer here, not "unset" - presence is what says the user meant it.
            calculator.setMaxTierSkips(
                MachineProfile.getInt(settings, Settings.MAX_TIER_SKIPS.key(), calculator.getMaxTierSkips()));
        }
        if (settings.containsKey(Settings.MACHINE_HEAT.key())) {
            calculator.setMachineHeat(
                MachineProfile.getInt(settings, Settings.MACHINE_HEAT.key(), calculator.getMachineHeat()));
        }
        if (settings.containsKey(Settings.RECIPE_HEAT.key())) {
            calculator
                .setRecipeHeat(MachineProfile.getInt(settings, Settings.RECIPE_HEAT.key(), calculator.getRecipeHeat()));
        }
        if (settings.containsKey(Settings.HEAT_OC.key())) {
            calculator.setHeatOC(MachineProfile.getBool(settings, Settings.HEAT_OC.key(), calculator.isHeatOC()));
        }
        if (settings.containsKey(Settings.HEAT_DISCOUNT.key())) {
            calculator.setHeatDiscount(
                MachineProfile.getBool(settings, Settings.HEAT_DISCOUNT.key(), calculator.isHeatDiscount()));
        }
        if (settings.containsKey(Settings.HEAT_DISCOUNT_MULT.key())) {
            calculator.setHeatDiscountMultiplier(
                MachineProfile.getInt(
                    settings,
                    Settings.HEAT_DISCOUNT_MULT.key(),
                    GTSettings.percent(calculator.getHeatDiscountMultiplier())) / 100.0);
        }
        return calculator;
    }
}
