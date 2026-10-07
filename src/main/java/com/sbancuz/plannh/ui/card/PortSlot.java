package com.sbancuz.plannh.ui.card;

import net.minecraft.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import com.cleanroommc.modularui.api.widget.IDraggable;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.integration.recipeviewer.RecipeViewerIngredientProvider;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.sbancuz.plannh.ui.canvas.BoardCanvas;

/**
 * One port row (NEI slot, name and rate) as its own widget, so NEI's R/U and the item tooltip see exactly this stack.
 * Drawing is
 * done by the card; this widget only answers "what is under the mouse".
 */
public final class PortSlot extends Widget<PortSlot>
    implements RecipeViewerIngredientProvider, Interactable, IDraggable {

    private final RecipeCard card;
    /** Which recipe on the card the port is on: 0, or a later one on a shared machine. */
    public final int section;
    public final boolean output;
    public final int index;

    PortSlot(final RecipeCard card, final int section, final boolean output, final int index) {
        this.card = card;
        this.section = section;
        this.output = output;
        this.index = index;
        if (card.layout() != null) place(card.layout());
    }

    /** The whole row (icon, name, rate) is the handle: drag a wire from anywhere on it, click it for NEI's recipes. */
    void place(final CardLayout layout) {
        size(CardLayout.RAIL_W, layout.rowH(output, index));
        pos(CardLayout.railX(output), layout.rowY(section, output, index));
    }

    public CardModel.PortView view() {
        final CardModel model = card.modelOf(section);
        if (model == null) return null;
        final java.util.List<CardModel.PortView> ports = output ? model.outputs : model.inputs;
        return index < ports.size() ? ports.get(index) : null;
    }

    public RecipeCard card() {
        return card;
    }

    /** The node the port belongs to: its recipe's, which on a shared machine is not the card's own. */
    public java.util.UUID node() {
        return card.sectionId(section);
    }

    /** Far out the ports are not drawn: they take no hover, clicks or drags, and the card under them does. */
    @Override
    public boolean canHover() {
        return card.session()
            .graph()
            .getZoom() > RecipeCard.GLANCE_ZOOM;
    }

    /** The stack for tooltips. Unlike {@link #getStackForRecipeViewer()} it never arms an NEI lookup. */
    public ItemStack stack() {
        final CardModel.PortView view = view();
        return view == null ? null : view.lookupStack();
    }

    // region Dragging a wire out of the port

    private boolean moving;
    /**
     * Whether this port saw the press of the click now ending and it did not become a drag. A release alone is no
     * click: pressing NEI's + closes its page onto the board, and that release lands on whatever port is under it.
     */
    private boolean pressed;

    private BoardCanvas canvas() {
        return card.getParent() instanceof final BoardCanvas c ? c : null;
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        pressed = mouseButton == 0;
        return mouseButton == 0 ? Result.ACCEPT : Result.IGNORE;
    }

    @Override
    public boolean onMouseRelease(final int mouseButton) {
        if (mouseButton != 0 || !pressed) return false;
        pressed = false;
        final BoardCanvas canvas = canvas();
        if (canvas != null) canvas.clickPort(node(), output, index);
        return true;
    }

    @Override
    public boolean onDragStart(final int button) {
        final BoardCanvas canvas = canvas();
        if (button != 0 || canvas == null) return false;
        pressed = false;
        canvas.beginPortDrag(node(), output, index);
        return true;
    }

    @Override
    public void onDrag(final int mouseButton, final long timeSinceLastClick) {}

    @Override
    public void onDragEnd(final boolean successful) {
        final BoardCanvas canvas = canvas();
        if (canvas != null) canvas.endPortDrag(successful);
    }

    @Override
    public void drawMovingState(final ModularGuiContext context, final float partialTicks) {}

    @Override
    public @Nullable Area getMovingArea() {
        return null;
    }

    @Override
    public boolean isMoving() {
        return moving;
    }

    @Override
    public void setMoving(final boolean moving) {
        this.moving = moving;
    }

    // endregion

    @Override
    public ItemStack getStackForRecipeViewer() {
        // AE2 and NEI can ask after the screen is gone.
        if (!isValid()) return null;
        // NEI asks this when R or U is pressed: remember the port so the recipe added next wires into it.
        card.session()
            .armLookup(node(), output, index);
        return stack();
    }
}
