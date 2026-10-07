package com.sbancuz.plannh.importer.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.sbancuz.plannh.Compat;
import com.sbancuz.plannh.importer.FfPlan.FfRecipe;
import com.sbancuz.plannh.importer.FfPlan.FfSlot;
import com.sbancuz.plannh.importer.RecipeIndex;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.ICraftingHandler;

/**
 * NEI's recipes for the importer. GregTech recipes come from their recipe map (see {@link GtRecipeSource}); crafting
 * and smelting recipes from NEI's crafting and furnace pages for the recipe's first output. One per import: it keeps
 * each recipe map it has read. Client thread only.
 */
public final class NeiRecipeIndex implements RecipeIndex {

    private final Map<String, List<GameRecipe>> gregtechMaps = new HashMap<>();

    @Override
    public Lookup find(final FfRecipe recipe) {
        if ("crafting".equals(recipe.category()) || recipe.machineType()
            .endsWith(" Crafting")) return byOutput(recipe, "crafting");
        if ("furnace".equals(recipe.category()) || "Furnace".equals(recipe.machineType()))
            return byOutput(recipe, "smelting");
        if ("gregtech".equals(recipe.category()) || recipe.gameMapSlug() != null) {
            if (!Compat.GREGTECH.isLoaded) return Lookup.none("GregTech is not loaded");
            try {
                return GtRecipeSource.find(recipe, gregtechMaps);
            } catch (final LinkageError e) {
                return Lookup.none("GregTech's recipes could not be read (" + e + ")");
            }
        }
        final String kind = (recipe.kind()
            .isEmpty() ? recipe.category() : recipe.kind()).replace('_', ' ');
        return Lookup
            .none("Factory Flow's " + (kind.isEmpty() ? recipe.machineType() : kind) + " cards have no recipe in game");
    }

    /** Every recipe on NEI's crafting (or smelting) pages that makes the recipe's first output. */
    private static Lookup byOutput(final FfRecipe recipe, final String overlay) {
        if (recipe.outputs()
            .isEmpty()) return Lookup.none("it makes nothing");
        final FfSlot out = recipe.outputs()
            .getFirst();
        final ItemStack stack = GameIds.stackOf(out.id());
        if (stack == null) return Lookup.none("no item " + out.id() + " in game");
        final List<GameRecipe> found = new ArrayList<>();
        for (final ICraftingHandler handler : GuiCraftingRecipe.getCraftingHandlers("item", stack)) {
            if (!overlay.equals(handler.getOverlayIdentifier())) continue;
            for (int i = 0; i < handler.numRecipes(); i++) found.add(read(handler, i));
        }
        return Lookup.of(found);
    }

    /** One NEI recipe as plain data: a stack per ingredient slot, every permutation's ids on it; no timing. */
    private static GameRecipe read(final ICraftingHandler handler, final int index) {
        final List<GameStack> inputs = new ArrayList<>();
        for (final PositionedStack ps : handler.getIngredientStacks(index)) {
            final GameStack s = stack(ps);
            if (s != null) inputs.add(s);
        }
        final List<GameStack> outputs = new ArrayList<>();
        final GameStack result = stack(handler.getResultStack(index));
        if (result != null) outputs.add(result);
        return new GameRecipe(
            handler,
            index,
            handler.getRecipeName()
                .trim(),
            inputs,
            outputs,
            null,
            null,
            null);
    }

    @Nullable
    private static GameStack stack(@Nullable final PositionedStack ps) {
        if (ps == null || ps.item == null || ps.item.getItem() == null) return null;
        final List<String> ids = new ArrayList<>(GameIds.itemIds(ps.item));
        if (ps.items != null) for (final ItemStack alt : ps.items) {
            if (alt == null || alt.getItem() == null) continue;
            for (final String id : GameIds.itemIds(alt)) if (!ids.contains(id)) ids.add(id);
        }
        return new GameStack("item", ids, Math.max(1, ps.item.stackSize), 1, true);
    }
}
