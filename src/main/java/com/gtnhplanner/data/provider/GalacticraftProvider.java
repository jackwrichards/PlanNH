package com.gtnhplanner.data.provider;

import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.gtnhplanner.api.RecipePropertyAPI;
import com.gtnhplanner.data.MachineProfile;
import com.gtnhplanner.data.MachineProfileRegistry;
import com.gtnhplanner.data.Settings;
import com.gtnhplanner.data.effect.Effects;
import com.gtnhplanner.data.effect.steps.CoFHCompat;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.properties.PropertyProvider;
import com.gtnhplanner.data.properties.RecipeProperty;

import codechicken.nei.recipe.IRecipeHandler;
import micdoodle8.mods.galacticraft.core.nei.CircuitFabricatorRecipeHandler;
import micdoodle8.mods.galacticraft.core.nei.RefineryRecipeHandler;

public final class GalacticraftProvider implements PropertyProvider {

    private static final String PROFILE_ID = "gc:basic";

    @Override
    public void register() {
        RecipePropertyAPI.registerExtractor(RefineryRecipeHandler.class, this);
        RecipePropertyAPI.registerExtractor(CircuitFabricatorRecipeHandler.class, this);

        MachineProfileRegistry.register(
            MachineProfile.builder(PROFILE_ID, "Galacticraft")
                .setting(Settings.MACHINES.def())
                .setting(Settings.TICK_MODIFIER.def())
                .effect(
                    Effects.durationFromHandler()
                        .amortizeCost(CoFHCompat.RF_COST)
                        .applyParallelism())
                .build());
    }

    @Override
    public boolean canCraft(final IRecipeHandler handler, final int recipeIndex) {
        return getProfileId(handler, recipeIndex) != null;
    }

    @Override
    @Nullable
    public String getProfileId(final IRecipeHandler handler, final int recipeIndex) {
        return switch (handler) {
            case RefineryRecipeHandler _, CircuitFabricatorRecipeHandler _ -> PROFILE_ID;
            default -> null;
        };
    }

    @Override
    @Nonnull
    public Map<RecipeProperty<?>, Object> extract(final Node node, final IRecipeHandler handler,
        final int recipeIndex) {
        return PropertyProvider.super.extract(node, handler, recipeIndex);
    }
}
