package com.gtnhplanner.machines.web;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * GregTech's voltage tiers as the website reads them (src/lib/model/tiers.ts): names ULV to MAX, each tier's EU/t, and
 * how a recipe's minimum and a node's run tier are found. A tier is its name, as on the website; ordinals count ULV
 * as 0.
 */
public final class Tiers {

    private Tiers() {}

    public static final String[] NAMES = { "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV", "UIV",
        "UMV", "UXV", "MAX" };

    /** GTValues.V; MAX is a real tier (Integer.MAX_VALUE - 7), the wireless hatches reach it. */
    public static final double[] MAX_EUT = { 8, 32, 128, 512, 2048, 8192, 32768, 131072, 524288, 2097152, 8388608,
        33554432, 134217728, 536870912, 2147483640d };

    /** getVoltageTierForEuT: the lowest tier whose voltage carries this draw; ULV for nothing, MAX past the top. */
    public static String forEuT(final double euT) {
        if (!Double.isFinite(euT) || euT <= 0) return "ULV";
        final double abs = Math.abs(euT);
        for (int i = 0; i < NAMES.length; i++) if (abs <= MAX_EUT[i]) return NAMES[i];
        return "MAX";
    }

    /** getVoltageTierWithinEuT: the highest tier whose voltage fits inside a budget; ULV at least. */
    public static String withinEuT(final double euT) {
        if (!Double.isFinite(euT) || euT <= 0) return "ULV";
        String tier = "ULV";
        for (int i = 0; i < NAMES.length; i++) if (MAX_EUT[i] <= euT) tier = NAMES[i];
        return tier;
    }

    /** getRecipePowerTier. */
    public static String recipePowerTier(final double eut) {
        return forEuT(eut);
    }

    /** getVoltageTierIndex: anything unknown is the top tier. */
    public static int index(@Nullable final String tier) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(tier)) return i;
        return NAMES.length - 1;
    }

    /** isVoltageTierName. */
    public static boolean isName(@Nullable final String value) {
        for (final String n : NAMES) if (n.equals(value)) return true;
        return false;
    }

    /** getVoltageTierMaxEuT: one amp of this tier; infinite for an unknown name. */
    public static double maxEuT(@Nullable final String tier) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(tier)) return MAX_EUT[i];
        return Double.POSITIVE_INFINITY;
    }

    /** GT_VOLTAGE_TIERS[i].tier, or null past either end. */
    @Nullable
    public static String at(final int index) {
        return index >= 0 && index < NAMES.length ? NAMES[index] : null;
    }

    /** resolveVoltageTier: a known name, the old "OpV" as UXV, else the default. */
    public static String resolve(@Nullable final String value, final String defaultTier) {
        if (isName(value)) return value;
        if ("OpV".equals(value)) return "UXV";
        return defaultTier;
    }

    /** getRecipeMinimumVoltageTier: the higher of the declared minimum and the tier of the recipe's draw. */
    public static String recipeMinimum(final double eut, @Nullable final String minimumTier) {
        final String powerTier = recipePowerTier(eut);
        final String declared = resolve(minimumTier, powerTier);
        return index(declared) >= index(powerTier) ? declared : powerTier;
    }

    public static String recipeMinimum(final Web.Recipe recipe) {
        return recipeMinimum(recipe.eut, recipe.minimumTier == null ? "ULV" : recipe.minimumTier);
    }

    /**
     * getRunVoltageTier, the singleblock rule: the requested tier floored at the recipe's minimum, snapped to a
     * machine the family registers, and capped at its last.
     */
    public static String runTier(final Web.Recipe recipe, @Nullable final String requestedTier) {
        final String minimum = recipeMinimum(recipe);
        final String requested = resolve(requestedTier, minimum);
        final List<String> available = available(recipe.availableTiers);
        if (available != null) {
            final List<String> eligible = new ArrayList<>();
            for (final String t : available) if (index(t) >= index(minimum)) eligible.add(t);
            for (int i = eligible.size() - 1; i >= 0; i--)
                if (index(eligible.get(i)) <= index(requested)) return eligible.get(i);
            return !eligible.isEmpty() ? eligible.get(0) : available.get(available.size() - 1);
        }
        if (index(requested) < index(minimum)) return minimum;
        final String maximum = maximum(recipe.maximumTier);
        return maximum != null && index(requested) > index(maximum) ? maximum : requested;
    }

    /** getRecipeAvailableVoltageTiers: the registered singleblock ladder in voltage order; null when absent. */
    @Nullable
    public static List<String> available(@Nullable final List<String> availableTiers) {
        if (availableTiers == null) return null;
        final List<String> out = new ArrayList<>();
        for (final String n : NAMES) if (availableTiers.contains(n)) out.add(n);
        return out.isEmpty() ? null : out;
    }

    /** getRecipeMaximumVoltageTier. */
    @Nullable
    public static String maximum(@Nullable final String maximumTier) {
        return isName(maximumTier) ? maximumTier : null;
    }
}
