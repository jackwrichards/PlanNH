package com.sbancuz.plannh.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.screen.GuiContainerWrapper;
import com.cleanroommc.modularui.screen.ModularContainer;
import com.cleanroommc.modularui.screen.ModularScreen;

/** Opening the planner, and telling whether a GUI is it (for the NEI glue and mixins). */
public final class Planner {

    private Planner() {}

    /** Opens the board. NEI only attaches its item list to a GuiContainer, hence the wrapper. */
    public static BoardScreen open() {
        final ModularContainer container = new ModularContainer();
        container.constructClientOnly();
        final BoardScreen screen = BoardScreen.create();
        Minecraft.getMinecraft()
            .displayGuiScreen(new GuiContainerWrapper(container, screen));
        return screen;
    }

    /** The planner screen inside {@code gui}, or null. */
    public static ModularScreen screenOf(final GuiScreen gui) {
        if (!(gui instanceof final GuiContainerWrapper wrapper)) return null;
        final ModularScreen screen = wrapper.getScreen();
        return screen instanceof BoardScreen ? screen : null;
    }

    public static boolean isPlanner(final GuiScreen gui) {
        return screenOf(gui) != null;
    }

    /**
     * "What makes this?" (or uses it) for any item, from anywhere NEI sees one: opens the planner when it is not
     * already up, then the recipe picker in the middle of the board. The recipe picked goes on the board, auto-wired.
     */
    public static void pick(final ItemStack stack, final boolean uses) {
        final ModularScreen open = screenOf(Minecraft.getMinecraft().currentScreen);
        final BoardScreen board = open instanceof final BoardScreen b ? b : open();
        board.canvas()
            .openRecipePicker(stack, uses, null);
    }
}
