package com.gtnhplanner.data.provider;

import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.brandon3055.draconicevolution.integration.nei.ReactorNEIHandler;
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

public final class DraconicEvolutionProvider implements PropertyProvider {

    @Override
    public void register() {
        RecipePropertyAPI.registerExtractor(ReactorNEIHandler.class, this);

        MachineProfileRegistry.register(
            MachineProfile.builder("draconicevolution:fusion_crafter", "Fusion Crafter")
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
            case ReactorNEIHandler _ -> "draconicevolution:fusion_crafter";
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
