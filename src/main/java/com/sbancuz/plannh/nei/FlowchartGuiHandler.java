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
}
