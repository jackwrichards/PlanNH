package com.gtnhplanner.nei;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;

import com.gtnhplanner.ui.Planner;

import codechicken.nei.ItemPanel;
import codechicken.nei.ItemPanels;
import codechicken.nei.api.ShortcutInputHandler;
import codechicken.nei.guihook.IContainerInputHandler;

/**
 * A click on an item in NEI's list while the planner is open looks it up, as on NEI's own recipe pages: left-click its
 * recipes, right-click its uses (R and U). Elsewhere NEI gives the item away instead when cheat mode is on, which on
 * the
 * planner (no inventory to put it in) only fills the player's pockets. A drag still carries the item to the board.
 */
public final class PlannerItemClicks implements IContainerInputHandler {

    public static final PlannerItemClicks INSTANCE = new PlannerItemClicks();

    private PlannerItemClicks() {}

    /**
     * Runs before NEI's own release handling: when the press and the release were on the same item and nothing was
     * dragged, the lookup happens here and NEI is told no item was pressed, so it does not give it away.
     */
    @Override
    public void onMouseUp(final GuiContainer gui, final int mousex, final int mousey, final int button) {
        if (button != 0 && button != 1 || !Planner.isPlanner(gui)) return;
        final ItemPanel panel = ItemPanels.itemPanel;
        if (panel == null || panel.draggedStack != null || panel.mouseDownSlot < 0) return;
        final ItemPanel.ItemPanelSlot slot = panel.getSlotMouseOver(mousex, mousey);
        if (slot == null || slot.slotIndex != panel.mouseDownSlot) return;
        final ItemStack stack = slot.getItemStack();
        panel.mouseDownSlot = -1;
        if (stack != null) ShortcutInputHandler.handleMouseClick(stack);
    }

    @Override
    public boolean keyTyped(final GuiContainer gui, final char keyChar, final int keyCode) {
        return false;
    }

    @Override
    public void onKeyTyped(final GuiContainer gui, final char keyChar, final int keyID) {}

    @Override
    public boolean lastKeyTyped(final GuiContainer gui, final char keyChar, final int keyID) {
        return false;
    }

    @Override
    public boolean mouseClicked(final GuiContainer gui, final int mousex, final int mousey, final int button) {
        return false;
    }

    @Override
    public void onMouseClicked(final GuiContainer gui, final int mousex, final int mousey, final int button) {}

    @Override
    public boolean mouseScrolled(final GuiContainer gui, final int mousex, final int mousey, final int scrolled) {
        return false;
    }

    @Override
    public void onMouseScrolled(final GuiContainer gui, final int mousex, final int mousey, final int scrolled) {}

    @Override
    public void onMouseDragged(final GuiContainer gui, final int mousex, final int mousey, final int button,
        final long heldTime) {}
}
