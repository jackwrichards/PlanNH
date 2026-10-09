package com.gtnhplanner.ui.tutorial;

import net.minecraft.client.gui.GuiScreen;

/**
 * The tour's own screen for its parts in the world (the start, the minimap, the end): it draws nothing, so the world
 * and the minimap show through, holds the mouse free for the tour's bar, and keeps the game running underneath.
 */
public final class TourScreen extends GuiScreen {

    @Override
    public void drawScreen(final int mouseX, final int mouseY, final float partialTicks) {}

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
