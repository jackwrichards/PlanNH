package com.sbancuz.plannh.nei;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.inventory.GuiContainer;

import com.sbancuz.plannh.api.PlanAPI;
import com.sbancuz.plannh.api.RecipePropertyAPI;
import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.data.flowchart.Plan;
import com.sbancuz.plannh.data.properties.PropertyProvider;
import com.sbancuz.plannh.ui.BoardScreen;
import com.sbancuz.plannh.ui.Planner;

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
        for (final PropertyProvider p : RecipePropertyAPI.getExtractors(handler.getClass())) {
            if (p.canCraft(handler, recipeIndex)) return true;
        }
        return false;
    }

    @Override
    public boolean craft(final GuiContainer firstGui, final IRecipeHandler handler, final int recipeIndex,
        final int multiplier) {
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
        final Node node = new Node(handler, recipeIndex, 0, 0);
        final Graph graph = Plan.getActiveGraph();
        PlanAPI.recordEdit(graph, () -> graph.addNode(node));
        PlanAPI.save();
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
