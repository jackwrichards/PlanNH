package com.gtnhplanner.nei;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.inventory.GuiContainer;

import com.gtnhplanner.api.RecipePropertyAPI;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.data.properties.PropertyProvider;
import com.gtnhplanner.ui.BoardScreen;
import com.gtnhplanner.ui.NewCards;
import com.gtnhplanner.ui.Planner;
import com.gtnhplanner.ui.card.MachineChoices;

import codechicken.nei.PositionedStack;
import codechicken.nei.api.IOverlayHandler;
import codechicken.nei.recipe.GuiOverlayButton;
import codechicken.nei.recipe.IRecipeHandler;

public class PlanOverlayHandler implements IOverlayHandler {

    @Override
    public void overlayRecipe(final GuiContainer firstGui, final IRecipeHandler recipe, final int recipeIndex,
        final boolean maxTransfer) {
        addRecipe(firstGui, recipe, recipeIndex);
    }

    @Override
    public int transferRecipe(final GuiContainer firstGui, final IRecipeHandler recipe, final int recipeIndex,
        final int multiplier) {
        addRecipe(firstGui, recipe, recipeIndex);
        return 0;
    }

    @Override
    public boolean canCraft(final GuiContainer firstGui, final IRecipeHandler handler, final int recipeIndex) {
        return plannable(handler, recipeIndex);
    }

    @Override
    public boolean craft(final GuiContainer firstGui, final IRecipeHandler handler, final int recipeIndex,
        final int multiplier) {
        return plannable(handler, recipeIndex);
    }

    /** Whether the planner can read the recipe: + and the plan button only show on recipes it can. */
    public static boolean plannable(final IRecipeHandler handler, final int recipeIndex) {
        for (final PropertyProvider p : RecipePropertyAPI.getExtractors(handler.getClass())) {
            if (p.canCraft(handler, recipeIndex)) return true;
        }
        return false;
    }

    /**
     * Adds the recipe to the open board (placed and wired by the board's session), or to the active plan when the
     * recipe page was opened from somewhere else.
     */
    private static void addRecipe(final GuiContainer firstGui, final IRecipeHandler handler, final int recipeIndex) {
        if (Planner.screenOf(firstGui) instanceof final BoardScreen board) {
            board.session()
                .addRecipe(handler, recipeIndex);
            return;
        }
        NewCards.addTo(Plan.getActiveGraph(), MachineChoices.newNode(handler, recipeIndex, null));
    }

    @Override
    public List<GuiOverlayButton.ItemOverlayState> presenceOverlay(final GuiContainer firstGui,
        final IRecipeHandler recipe, final int recipeIndex) {
        final List<GuiOverlayButton.ItemOverlayState> itemPresenceSlots = new ArrayList<>();
        final List<PositionedStack> ingredients = recipe.getIngredientStacks(recipeIndex);

        for (final PositionedStack stack : ingredients) {
            itemPresenceSlots.add(new GuiOverlayButton.ItemOverlayState(stack, true));
        }

        return itemPresenceSlots;
    }
}
