package com.gtnhplanner.data.provider;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.gtnhplanner.api.RecipePropertyAPI;
import com.gtnhplanner.data.MachineProfile;
import com.gtnhplanner.data.MachineProfileRegistry;
import com.gtnhplanner.data.Settings;
import com.gtnhplanner.data.effect.Effects;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.properties.PropertyProvider;
import com.gtnhplanner.data.properties.RecipeProperty;

import codechicken.nei.recipe.IRecipeHandler;
import thaumic.tinkerer.client.nei.NEINecromancyHandler;

public final class ThaumicTinkererProvider implements PropertyProvider {

    // TODO: Make a PR to expose this constant
    // Soul Mould: scan interval (ticks) from TileSoulMould
    private static final int SCAN_INTERVAL = 300;

    @Override
    public void register() {
        RecipePropertyAPI.registerExtractor(NEINecromancyHandler.class, this);

        MachineProfileRegistry.register(
            MachineProfile.builder("tt:necromancy_soul_mould", "Soul Mould")
                .setting(Settings.MACHINES.def())
                .setting(Settings.TICK_MODIFIER.def())
                .effect(
                    Effects.clearCost()
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
            case NEINecromancyHandler _ -> "tt:necromancy_soul_mould";
            default -> null;
        };
    }

    @Override
    @Nonnull
    public Map<RecipeProperty<?>, Object> extract(final Node node, final IRecipeHandler handler,
        final int recipeIndex) {
        final Map<RecipeProperty<?>, Object> props = new HashMap<>(
            PropertyProvider.super.extract(node, handler, recipeIndex));
        props.put(RecipePropertyAPI.DURATION_TICKS, SCAN_INTERVAL);
        return props;
    }
}
