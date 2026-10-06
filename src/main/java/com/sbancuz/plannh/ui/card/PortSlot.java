package com.sbancuz.plannh.ui.card;

import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.integration.recipeviewer.RecipeViewerIngredientProvider;
import com.cleanroommc.modularui.widget.Widget;

/**
 * The NEI slot of one port, as its own widget so NEI's R/U and the item tooltip see exactly this stack. Drawing is
 * done by the card; this widget only answers "what is under the mouse".
 */
public final class PortSlot extends Widget<PortSlot> implements RecipeViewerIngredientProvider {

    private final RecipeCard card;
    public final boolean output;
    public final int index;

    PortSlot(final RecipeCard card, final boolean output, final int index) {
        this.card = card;
        this.output = output;
        this.index = index;
        size(18, 18);
        pos(CardLayout.railX(output), CardLayout.portRowY(index) + 1);
    }

    public CardModel.PortView view() {
        final CardModel model = card.model();
        if (model == null) return null;
        final java.util.List<CardModel.PortView> ports = output ? model.outputs : model.inputs;
        return index < ports.size() ? ports.get(index) : null;
    }

    public RecipeCard card() {
        return card;
    }

    /** The stack for tooltips. Unlike {@link #getStackForRecipeViewer()} it never arms an NEI lookup. */
    public ItemStack stack() {
        final CardModel.PortView view = view();
        return view == null ? null : view.lookupStack();
    }

    @Override
    public ItemStack getStackForRecipeViewer() {
        // AE2 and NEI can ask after the screen is gone.
        if (!isValid()) return null;
        // NEI asks this when R or U is pressed: remember the port so the recipe added next wires into it.
        card.session()
            .armLookup(card.nodeId, output, index);
        return stack();
    }
}
