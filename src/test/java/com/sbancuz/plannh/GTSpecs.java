package com.sbancuz.plannh;

import static org.mockito.Mockito.mock;

import com.sbancuz.plannh.data.provider.gregtech.GTMachineSpec;
import com.sbancuz.plannh.data.provider.gregtech.StructureState;

import gregtech.api.logic.ProcessingSpec;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.OverclockCalculator;

/** Machines built from a spec, as the GregTech registry would hand them over. */
final class GTSpecs {

    private GTSpecs() {}

    static GTMachineSpec machine(final ProcessingSpec spec) {
        return GTMachineSpec.of(spec);
    }

    /** GTRecipe's constructor needs the game loaded; these are the only fields the calculator reads. */
    static GTRecipe recipe(final long eut, final int duration, final int heat) {
        final GTRecipe recipe = mock(GTRecipe.class);
        recipe.mEUt = (int) eut;
        recipe.mDuration = duration;
        recipe.mSpecialValue = heat;
        return recipe;
    }

    /** The calculator a chart node runs this machine with, before its overrides, parallel and calculation. */
    static OverclockCalculator calculator(final ProcessingSpec spec, final StructureState state, final long recipeEUt,
        final int duration, final int recipeHeat) {
        return machine(spec).resolve(recipe(recipeEUt, duration, recipeHeat), state)
            .toCalculator();
    }
}
