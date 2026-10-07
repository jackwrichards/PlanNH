package com.gtnhplanner.data.effect.steps;

/**
 * Which recipe heat GregTech's heat overclock is measured against. Kept apart from
 * {@link GTOverclockStep} because that class needs GregTech on the classpath and this rule does
 * not, so it can be tested headlessly.
 */
public final class GTHeat {

    private GTHeat() {}

    /**
     * The player's recipe-heat override when they set one; otherwise the recipe's own minimum coil
     * heat (GregTech's {@code COIL_HEAT}, or the heating-coil special value); and only when the
     * recipe states none, the machine's own heat, which gives no heat bonus and no penalty. Using the
     * machine heat while the recipe does state its heat threw away every heat overclock and discount
     * the coils earn.
     *
     * @param override       the {@code recipe_heat} setting, 0 when unset.
     * @param recipeCoilHeat the recipe's {@code COIL_HEAT} property, 0 when it has none.
     * @param machineHeat    the {@code machine_heat} setting.
     */
    public static int recipeHeat(final int override, final int recipeCoilHeat, final int machineHeat) {
        if (override > 0) return override;
        if (recipeCoilHeat > 0) return recipeCoilHeat;
        return machineHeat;
    }
}
