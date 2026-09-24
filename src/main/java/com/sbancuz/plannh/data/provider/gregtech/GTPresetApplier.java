package com.sbancuz.plannh.data.provider.gregtech;

import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.sbancuz.plannh.data.MachineProfile;
import com.sbancuz.plannh.data.RecipeContext;
import com.sbancuz.plannh.data.Settings;
import com.sbancuz.plannh.data.provider.GTProvider;

import gregtech.api.enums.GTValues;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.OverclockCalculator;

/**
 * GT's own calculator for machines that publish a describer, plus the stored-override layer.
 * Preset multis run through the step instead, over merged defaults.
 */
public final class GTPresetApplier {

    private GTPresetApplier() {}

    /** A describer-built calculator plus the parallel count it was given. */
    public record Configured(OverclockCalculator calculator, int parallels) {}

    /** GT's own calculator with stored overrides, or null when no describer answers. */
    @Nullable
    public static Configured describe(@Nullable final GTMachineIndex.MachineEntry entry,
        final Map<String, Object> settings, final RecipeContext ctx, final long recipeEUt, final int duration) {
        if (entry == null || entry.describer() == null) return null;
        // A singleblock's tier is fixed by the block itself; only a multiblock's energy hatch is a
        // choice, so only there does the voltage row mean anything.
        final int voltageTier = entry.tieredByBuild() ? GTSettings.voltageTier(ctx, settings) : entry.voltageTier();

        final StructureState state = StructureState
            .resolve(ctx, settings, voltageTier, GTMachineIndex.mode(ctx, entry, settings));

        final OverclockCalculator calculator = fromDescriber(ctx, entry, recipeEUt, duration);

        // Stored settings only: a default must not pose as a user choice.
        applyOverrides(calculator, settings);
        final int parallels = resolveParallels(settings, entry.preset(), state);
        calculator.setParallel(parallels)
            .setAmperageOC(true);
        return new Configured(calculator, parallels);
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
    public static void applyOverrides(final OverclockCalculator calculator, final Map<String, Object> settings) {
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
    }

    /**
     * The recipe's own heat requirement, which GT stores in mSpecialValue. A preset may pin it -
     * the Industrial Alloy Smelter overclocks against a floor of 0 with its coil heat doubled.
     */
    public static int recipeHeat(final RecipeContext ctx, @Nullable final GTMachinePreset preset) {
        if (preset != null && preset.recipeHeatOverride() != GTMachinePreset.RECIPE_HEAT_FROM_RECIPE) {
            return preset.recipeHeatOverride();
        }
        final Integer special = ctx.getOrDefault(GTProvider.SPECIAL_VALUE, null);
        return special != null ? special : 0;
    }

    /**
     * The machine's own maximum, unless the user capped it lower. 0 is what an untouched node stores,
     * and it means the maximum - a node must not silently run at one parallel because a default of 1
     * looked like a deliberate cap.
     */
    static int resolveParallels(final Map<String, Object> settings, @Nullable final GTMachinePreset preset,
        final StructureState state) {
        final int fromPreset = preset == null ? 1
            : Math.max(
                1,
                preset.maxParallel()
                    .applyAsInt(state));
        final int userCap = MachineProfile.getInt(settings, Settings.PARALLELS.key(), 0);
        return userCap > 0 ? Math.min(userCap, fromPreset) : fromPreset;
    }

}
