package com.gtnhplanner.ui.tutorial;

import java.util.List;
import java.util.Locale;

import net.minecraft.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.IRecipeHandler;

/**
 * The recipes the tour uses, found the way a player would: by name in NEI's list, and on NEI's page by what goes in
 * and comes out, so another pack version that moved a recipe still finds it (or the tour says it could not).
 */
final class TourRecipes {

    private TourRecipes() {}

    /** A recipe on NEI's open page: its handler there and its index. */
    record Found(IRecipeHandler handler, int recipe) {}

    static String name(final ItemStack s) {
        try {
            return s == null ? ""
                : s.getDisplayName()
                    .toLowerCase(Locale.ROOT);
        } catch (final RuntimeException e) {
            return "";
        }
    }

    /** NEI's entry for a fluid by its name: the fluid itself, else the cell of it. */
    static boolean isFluidNamed(final ItemStack s, final String fluid) {
        final String n = name(s);
        return n.equals(fluid) || n.equals(fluid + " cell");
    }

    /**
     * The first recipe on the open page whose tab's name has {@code tab}, taking every {@code in} and making
     * {@code out}.
     */
    @Nullable
    static Found onPage(final String tab, final List<String> in, final String out) {
        final GuiRecipe<?> page = Targets.recipePage();
        if (page == null) return null;
        for (final IRecipeHandler h : page.currenthandlers) {
            if (!h.getRecipeName()
                .toLowerCase(Locale.ROOT)
                .contains(tab)) continue;
            for (int i = 0; i < h.numRecipes(); i++) {
                if (has(h.getIngredientStacks(i), h.getOtherStacks(i), in) && makes(h, i, out)) return new Found(h, i);
            }
        }
        return null;
    }

    /** The open page's tab for {@code tab}. */
    @Nullable
    static IRecipeHandler tab(final String tab) {
        final GuiRecipe<?> page = Targets.recipePage();
        if (page == null) return null;
        for (final IRecipeHandler h : page.currenthandlers) if (h.getRecipeName()
            .toLowerCase(Locale.ROOT)
            .contains(tab)) return h;
        return null;
    }

    private static boolean has(final List<PositionedStack> ingredients, final List<PositionedStack> others,
        final List<String> wanted) {
        outer: for (final String w : wanted) {
            for (final PositionedStack ps : ingredients) if (ps != null && named(ps, w)) continue outer;
            for (final PositionedStack ps : others) if (ps != null && named(ps, w)) continue outer;
            return false;
        }
        return true;
    }

    private static boolean makes(final IRecipeHandler h, final int i, final String out) {
        final PositionedStack result = h.getResultStack(i);
        if (result != null && named(result, out)) return true;
        for (final PositionedStack ps : h.getOtherStacks(i)) if (ps != null && named(ps, out)) return true;
        return false;
    }

    private static boolean named(final PositionedStack ps, final String what) {
        for (final ItemStack s : ps.items) if (name(s).contains(what)) return true;
        return false;
    }

    /** Shows a recipe's page: its tab and the page it is on. */
    static void open(final Found f) {
        final GuiRecipe<?> page = Targets.recipePage();
        if (page == null || f == null) return;
        page.openTargetRecipe(codechicken.nei.recipe.Recipe.RecipeId.of(f.handler, f.recipe));
    }
}
