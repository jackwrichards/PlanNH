package com.gtnhplanner.ui.card;

import org.jetbrains.annotations.Nullable;

import com.cleanroommc.modularui.api.widget.IDraggable;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.gtnhplanner.ui.canvas.BoardCanvas;

/**
 * One of an empty custom rate card's two sockets: "Drain any" on the left, "Supply any" on the right. Drag a wire from
 * it to a machine's port (an output for the drain, an input for the supply) and the card takes that port's resource.
 * Drawing is done by the card; this widget only answers "what is under the mouse" and starts the drag.
 */
public final class SocketSlot extends Widget<SocketSlot> implements Interactable, IDraggable {

    /** Where the sockets sit on the card, card-local: a port row's height, a little in from the rail's edges. */
    public static final int Y = CardLayout.RAILS_Y + 4, H = CardLayout.ROW - 4;

    private final RecipeCard card;
    /** The supply socket (on the right, it feeds inputs), or the drain (on the left, it takes outputs). */
    public final boolean supply;
    private boolean moving;

    SocketSlot(final RecipeCard card, final boolean supply) {
        this.card = card;
        this.supply = supply;
        size(CardLayout.RAIL_W - 4, H);
        pos(CardLayout.railX(supply) + 2, Y);
    }

    public RecipeCard card() {
        return card;
    }

    /** Card-local {x, y, w, h}. */
    public static int[] rect(final boolean supply) {
        return new int[] { CardLayout.railX(supply) + 2, Y, CardLayout.RAIL_W - 4, H };
    }

    /** Where a wire from it meets the card, card-local y. */
    public static int anchorY() {
        return Y + H / 2;
    }

    private BoardCanvas canvas() {
        return card.getParent() instanceof final BoardCanvas c ? c : null;
    }

    @Override
    public boolean canHover() {
        return card.session()
            .graph()
            .getZoom() > RecipeCard.GLANCE_ZOOM;
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        return mouseButton == 0 ? Result.ACCEPT : Result.IGNORE;
    }

    @Override
    public boolean onDragStart(final int button) {
        final BoardCanvas canvas = canvas();
        if (button != 0 || canvas == null) return false;
        canvas.beginSocketDrag(card.nodeId, supply);
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
}
