package com.sbancuz.plannh.nei;

import net.minecraft.client.gui.inventory.GuiContainer;

import codechicken.nei.VisiblityData;
import codechicken.nei.api.INEIGuiAdapter;

public class FlowchartGuiHandler extends INEIGuiAdapter {

    @Override
    public VisiblityData modifyVisiblity(final GuiContainer gui, final VisiblityData currentVisibility) {
        if (com.sbancuz.plannh.ui.Planner.isPlanner(gui)) {
            currentVisibility.showSearchSection = true;
            currentVisibility.showWidgets = true;
            // NEI's cheat buttons (trash, time, weather) mean nothing in the planner and sit on its top bar.
            currentVisibility.showUtilityButtons = false;
            currentVisibility.showStateButtons = false;
            currentVisibility.showBookmarkPanel = false;
            currentVisibility.showItemSection = true;
        }
        return currentVisibility;
    }

    /** An item dragged from NEI's list onto the board: offered as a drawer there, or its recipes. */
    @Override
    public boolean handleDragNDrop(final GuiContainer gui, final int mouseX, final int mouseY,
        final net.minecraft.item.ItemStack draggedStack, final int button) {
        if (!(com.sbancuz.plannh.ui.Planner.screenOf(gui) instanceof final com.sbancuz.plannh.ui.BoardScreen board)
            || draggedStack == null) return false;
        final com.cleanroommc.modularui.widget.sizer.Area area = board.canvas()
            .getArea();
        if (mouseX < area.x || mouseY < area.y || mouseX >= area.x + area.width || mouseY >= area.y + area.height)
            return false;
        final net.minecraft.item.ItemStack stack = draggedStack.copy();
        stack.stackSize = 1;
        board.canvas()
            .dropNeiItem(stack, mouseX, mouseY);
        draggedStack.stackSize = 0;
        return true;
    }
}
