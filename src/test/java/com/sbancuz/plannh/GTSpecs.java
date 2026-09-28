package com.sbancuz.plannh;

import static org.mockito.Mockito.mock;

import java.util.Map;

import com.sbancuz.plannh.data.provider.gregtech.GTMachineSpec;
import com.sbancuz.plannh.data.provider.gregtech.GTStructureTiers;
import com.sbancuz.plannh.data.provider.gregtech.StructureState;

import gregtech.api.logic.ProcessingSpec;
import gregtech.api.structure.StructureParameter;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.OverclockCalculator;
import gregtech.api.util.tooltip.TooltipTier;

/** Machines built from a spec with a heating coil, as the GregTech registry would hand them over. */
final class GTSpecs {

    private GTSpecs() {}

    private static final Map<TooltipTier, StructureParameter> COIL = Map.of(
        TooltipTier.COIL,
        StructureParameter.builder(TooltipTier.COIL)
            .between(0, GTStructureTiers.MAX_COIL_TIER)
            .getter(() -> 0)
            .setter(tier -> {})
            .build());

    static GTMachineSpec machine(final ProcessingSpec spec) {
        return GTMachineSpec.of(spec, COIL, 1);
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
        final int duration, final long voltage, final long amperage, final int recipeHeat) {
        return machine(spec).calculator(recipe(recipeEUt, duration, recipeHeat), state, voltage, amperage);
    }
}
