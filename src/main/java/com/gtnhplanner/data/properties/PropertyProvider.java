package com.gtnhplanner.data.properties;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.tileentity.TileEntityFurnace;

import com.gtnhplanner.api.RecipePropertyAPI;
import com.gtnhplanner.data.Settings;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.nei.NEIPlanConfig;

import codechicken.nei.NEIClientConfig;
import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.IRecipeHandler;

public interface PropertyProvider {

    void register();

    default Map<RecipeProperty<?>, Object> extract(final Node node, final IRecipeHandler handler,
        final int recipeIndex) {
        final List<PositionedStack> ins = handler.getIngredientStacks(recipeIndex);
        for (final PositionedStack ps : ins) {
            if (ps != null && ps.item != null && ps.item.stackSize > 0) {
                node.inputs.add(new Port<>(RecipePropertyAPI.ITEM, ps.item.copy(), 1.f));
            }
        }

        final PositionedStack result = handler.getResultStack(recipeIndex);
        if (result != null && result.item != null) {
            node.outputs.add(new Port<>(RecipePropertyAPI.ITEM, result.item.copy(), 1.f));
        }

        final List<PositionedStack> others = handler.getOtherStacks(recipeIndex);
        for (final PositionedStack ps : others) {
            if (ps != null && ps.item != null) {
                if (NEIClientConfig.getSetting(NEIPlanConfig.ConfigBurnableOverride.KEY)
                    .getIntValue(NEIPlanConfig.ConfigBurnableOverride.OFF) == NEIPlanConfig.ConfigBurnableOverride.ON) {
                    if (node.machineConfig.getString(Settings.BURNABLE_OVERRIDE.key())
                        .equals("IN")) {
                        node.inputs.add(new Port<>(RecipePropertyAPI.ITEM, ps.item.copy(), 1.f));
                    } else if (node.machineConfig.getString(Settings.BURNABLE_OVERRIDE.key())
                        .equals("OUT")) {
                            node.outputs.add(new Port<>(RecipePropertyAPI.ITEM, ps.item.copy(), 1.f));
                        }
                } else if (TileEntityFurnace.getItemBurnTime(ps.item) <= 0) {
                    node.outputs.add(new Port<>(RecipePropertyAPI.ITEM, ps.item.copy(), 1.f));
                }
            }
        }

        return Map.of();
    }

    @Nullable
    default String getProfileId(final IRecipeHandler handler, final int recipeIndex) {
        return null;
    }

    default boolean canCraft(final IRecipeHandler handler, final int recipeIndex) {
        return true;
    }

    @Nonnull
    default String getExtractorName() {
        return getClass().getSimpleName();
    }
}
