package com.sbancuz.plannh.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.data.flowchart.Plan;

import codechicken.nei.recipe.GuiOverlayButton;
import codechicken.nei.recipe.Recipe;
import codechicken.nei.recipe.RecipeHandlerRef;

@Mixin(value = GuiOverlayButton.class, remap = false)
public class GuiOverlayButtonMixin {

    @Inject(method = "drawItemOverlay", at = @At("HEAD"), cancellable = true, remap = false)
    private void plannh$onDrawItemOverlay(final CallbackInfo ci) {
        final GuiOverlayButton self = (GuiOverlayButton) (Object) this;
        if (com.sbancuz.plannh.ui.Planner.isPlanner(self.firstGui) || planNH$isRecipeInGraph(self.handlerRef)) {
            ci.cancel();
        }
    }

    @Unique
    private static boolean planNH$isRecipeInGraph(final RecipeHandlerRef ref) {
        final Recipe.RecipeId currentId = Recipe.RecipeId.of(ref.handler, ref.recipeIndex);
        final Graph graph = Plan.getActiveGraph();
        for (final Node node : graph.getNodes()) {
            if (currentId.equals(node.recipeId)) {
                return true;
            }
        }
        return false;
    }
}
