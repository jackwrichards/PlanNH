package com.sbancuz.plannh.data.provider.gregtech;

import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.sbancuz.plannh.data.MachineProfile;
import com.sbancuz.plannh.data.RecipeContext;
import com.sbancuz.plannh.data.Settings;
import com.sbancuz.plannh.data.effect.EffectResult;
import com.sbancuz.plannh.data.machine.MachineVariant;
import com.sbancuz.plannh.data.provider.GTProvider;

import gregtech.api.enums.GTValues;
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
     * The machine's own answer for this recipe, which is what {@code Effects.machineDriven} asks for. Null for a
     * machine with neither a spec nor a describer, for a recipe GregTech would not start at this structure, or for one
     * with no energy or duration for a describer to overclock - the node then keeps the recipe's own numbers.
     */
    @Nullable
    public static EffectResult run(@Nonnull final GTMachineIndex.MachineEntry entry, final RecipeContext ctx,
        final Map<String, Object> settings, final EffectResult recipe) {
        final int machines = MachineProfile.getInt(settings, Settings.MACHINES.key(), 1);
        final ResolvedRecipe resolved = GTSettings.resolved(ctx, settings);
        if (resolved != null) {
            // 0 is what an untouched node stores, and it means the machine's maximum
            final int userCap = MachineProfile.getInt(settings, Settings.PARALLELS.key(), 0);
            final ResolvedRecipe capped = userCap > 0 && userCap < resolved.maxParallel()
                ? resolved.withMaxParallel(userCap)
                : resolved;
            final ProcessingRun run = capped.calculate(applyOverrides(capped.toCalculator(), settings));
            if (!run.result()
                .wasSuccessful()) return null;
            return new EffectResult(run.ticks(), run.euPerTick(), run.parallel() * machines);
        }

        final long recipeEUt = GTSettings.recipeEUt(ctx, recipe);
        final int duration = recipe.durationTicks();
        if (entry.describer() == null || recipeEUt <= 0 || duration <= 0) return null;
        final OverclockCalculator calculator = applyOverrides(fromDescriber(ctx, entry, recipeEUt, duration), settings)
            .setParallel(1)
            .setAmperageOC(true)
            .calculate();
        return new EffectResult(calculator.getDuration(), calculator.getConsumption(), machines);
    }

    /**
     * GT's own behaviour for this machine. The template is deliberately minimal: SteamOverclockDescriber
     * and EUNoOverclockDescriber ignore it entirely and rebuild from the recipe, so anything set here
     * would be silently dropped for exactly the machines that need it most.
     */
    @Nonnull
    private static OverclockCalculator fromDescriber(final RecipeContext ctx, final GTMachineIndex.MachineEntry entry,
        final long recipeEUt, final int duration) {
        final GTRecipe recipe = ctx.getOrDefault(GTProvider.GT_RECIPE, null);
        final OverclockCalculator template = new OverclockCalculator().setRecipeEUt(recipeEUt)
            .setDuration(duration);
        if (recipe == null) return template.setEUt(GTValues.V[Math.min(entry.voltageTier(), GTValues.V.length - 1)]);
        return entry.describer()
            .createCalculator(template, recipe);
    }

    /**
     * Lays the user's own numbers over the machine's. Only keys actually stored are applied - the map
     * is sparse, so anything absent stays exactly as the machine computed it, at full precision
     * rather than the rounded percentage the advanced rows display.
     *
     * <p>
     * This is what makes Advanced an override rather than a separate world: ticking it does not
     * change a single number until a row is edited.
     */
    @Nonnull
    public static OverclockCalculator applyOverrides(final OverclockCalculator calculator,
        final Map<String, Object> settings) {
        if (settings.containsKey(Settings.AMP.key())) {
            calculator.setAmperage(MachineProfile.getInt(settings, Settings.AMP.key(), 1));
        }
        if (settings.containsKey(Settings.SPEED.key())) {
            calculator
                .setDurationModifier(100.0 / Math.max(1, MachineProfile.getInt(settings, Settings.SPEED.key(), 100)));
        }
        if (settings.containsKey(Settings.EUT_DISCOUNT.key())) {
            calculator.setEUtDiscount(MachineProfile.getInt(settings, Settings.EUT_DISCOUNT.key(), 100) / 100.0);
        }
        if (settings.containsKey(Settings.EUT_INCREASE_PER_OC.key())) {
            calculator
                .setEUtIncreasePerOC(MachineProfile.getInt(settings, Settings.EUT_INCREASE_PER_OC.key(), 400) / 100.0);
        }
        if (settings.containsKey(Settings.DURATION_DECREASE_PER_OC.key())) {
            calculator.setDurationDecreasePerOC(
                MachineProfile.getInt(settings, Settings.DURATION_DECREASE_PER_OC.key(), 200) / 100.0);
        }
        if (settings.containsKey(Settings.PERFECT_OC.key())
            && MachineProfile.getBool(settings, Settings.PERFECT_OC.key(), false)) {
            calculator.enablePerfectOC();
        }
        if (settings.containsKey(Settings.NO_OVERCLOCK.key())) {
            calculator.setNoOverclock(MachineProfile.getBool(settings, Settings.NO_OVERCLOCK.key(), false));
        }
        if (settings.containsKey(Settings.LASER_OC.key())) {
            calculator.setLaserOC(MachineProfile.getBool(settings, Settings.LASER_OC.key(), false));
        }
        if (settings.containsKey(Settings.MAX_OVERCLOCKS.key())) {
            calculator.setMaxOverclocks(MachineProfile.getInt(settings, Settings.MAX_OVERCLOCKS.key(), 0));
        }
        if (settings.containsKey(Settings.MAX_REGULAR_OC.key())) {
            calculator.setMaxRegularOverclocks(MachineProfile.getInt(settings, Settings.MAX_REGULAR_OC.key(), 0));
        }
        if (settings.containsKey(Settings.UNLIMITED_SKIPS.key())
            && MachineProfile.getBool(settings, Settings.UNLIMITED_SKIPS.key(), false)) {
            calculator.setUnlimitedTierSkips();
        } else if (settings.containsKey(Settings.MAX_TIER_SKIPS.key())) {
            // Zero is a real answer here, not "unset" - presence is what says the user meant it.
            calculator.setMaxTierSkips(MachineProfile.getInt(settings, Settings.MAX_TIER_SKIPS.key(), 1));
        }
        if (settings.containsKey(Settings.MACHINE_HEAT.key())) {
            calculator.setMachineHeat(MachineProfile.getInt(settings, Settings.MACHINE_HEAT.key(), 0));
        }
        if (settings.containsKey(Settings.RECIPE_HEAT.key())) {
            calculator.setRecipeHeat(MachineProfile.getInt(settings, Settings.RECIPE_HEAT.key(), 0));
        }
        if (settings.containsKey(Settings.HEAT_OC.key())) {
            calculator.setHeatOC(MachineProfile.getBool(settings, Settings.HEAT_OC.key(), true));
        }
        if (settings.containsKey(Settings.HEAT_DISCOUNT.key())) {
            calculator.setHeatDiscount(MachineProfile.getBool(settings, Settings.HEAT_DISCOUNT.key(), false));
        }
        if (settings.containsKey(Settings.HEAT_DISCOUNT_MULT.key())) {
            calculator.setHeatDiscountMultiplier(
                MachineProfile.getInt(settings, Settings.HEAT_DISCOUNT_MULT.key(), 95) / 100.0);
        }
        return calculator;
    }
}
