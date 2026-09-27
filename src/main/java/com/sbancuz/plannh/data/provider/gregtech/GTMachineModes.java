package com.sbancuz.plannh.data.provider.gregtech;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.sbancuz.plannh.PlanNH;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.RecipeMap;

/**
 * How many modes a machine has, and which recipe implies which. Several multiblocks are two machines
 * behind one controller; where GregTech makes {@code getRecipeMap()} a function of the mode, the mode
 * is a function of the recipe, so it is derived rather than asked - picking tower mode for a
 * distillery recipe would model a machine that cannot run it, and nothing would say so.
 *
 * <p>
 * The count is needed even where the mapping is ambiguous, to bound the row. GregTech states both on
 * the prototype: {@code getMachineModeCount()} and {@code getRecipeMapForMode(int)}.
 */
public final class GTMachineModes {

    private GTMachineModes() {}

    private static final Modes SINGLE = new Modes(1, null);

    /**
     * @param count       how many modes the machine cycles through, at least one.
     * @param byRecipeMap recipemap name to the mode that selects it, or null when two modes share a
     *                    recipemap and the recipe therefore cannot say which is meant.
     */
    public record Modes(int count, @Nullable Map<String, Integer> byRecipeMap) {

        /** The mode this recipe implies, or -1 when the user still has to say. */
        public int modeFor(@Nullable final RecipeMap<?> recipeMap) {
            if (byRecipeMap == null || recipeMap == null) return -1;
            return byRecipeMap.getOrDefault(recipeMap.unlocalizedName, -1);
        }
    }

    /** Read off the registry prototype, which states its modes without being switched through them. */
    @Nonnull
    public static Modes of(@Nonnull final IMetaTileEntity prototype) {
        if (!(prototype instanceof final MTEMultiBlockBase machine) || !machine.supportsMachineModeSwitch()) {
            return SINGLE;
        }
        try {
            final int count = machine.getMachineModeCount();
            if (count < 2) return SINGLE;
            final Map<String, Integer> byRecipeMap = new HashMap<>();
            boolean distinct = true;
            for (int mode = 0; mode < count; mode++) {
                final RecipeMap<?> map = machine.getRecipeMapForMode(mode);
                // Two modes on one recipemap: the recipe no longer says which, so ask after all. The
                // count still stands, because the machine still cycles through them.
                if (map == null || byRecipeMap.putIfAbsent(map.unlocalizedName, mode) != null) distinct = false;
            }
            return new Modes(count, distinct ? Map.copyOf(byRecipeMap) : null);
        } catch (final RuntimeException | LinkageError e) {
            PlanNH.LOG.debug("PlanNH: {} would not report its modes", machine.getClass(), e);
            return SINGLE;
        }
    }
}
