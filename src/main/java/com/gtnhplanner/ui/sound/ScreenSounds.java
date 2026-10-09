package com.gtnhplanner.ui.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.GuiOpenEvent;

import com.gtnhplanner.ui.Planner;

import codechicken.nei.recipe.GuiRecipe;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * The planner's screens coming and going: it unfolds when it opens and folds when it closes, and NEI's recipe pages
 * opened from it (a port clicked, R or U on the board, a page turned there) turn like pages, going and coming back.
 */
public final class ScreenSounds {

    public static final ScreenSounds INSTANCE = new ScreenSounds();

    private ScreenSounds() {}

    @SubscribeEvent
    public void onGuiOpen(final GuiOpenEvent event) {
        final GuiScreen from = Minecraft.getMinecraft().currentScreen, to = event.gui;
        if (from == to) return;
        final boolean fromBoard = Planner.isPlanner(from), toBoard = Planner.isPlanner(to);
        final boolean fromPage = pageOverBoard(from), toPage = pageOverBoard(to);
        if ((fromBoard || fromPage) && (toBoard || toPage)) Sfx.PAGE.play();
        else if (toBoard) Sfx.SCREEN_OPEN.play();
        else if (fromBoard || fromPage) Sfx.SCREEN_CLOSE.play();
    }

    private static boolean pageOverBoard(final GuiScreen gui) {
        return gui instanceof final GuiRecipe<?> page && Planner.isPlanner(page.firstGui);
    }
}
