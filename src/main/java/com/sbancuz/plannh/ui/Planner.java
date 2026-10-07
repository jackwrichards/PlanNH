package com.sbancuz.plannh.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.screen.GuiContainerWrapper;
import com.cleanroommc.modularui.screen.ModularContainer;
import com.cleanroommc.modularui.screen.ModularScreen;

import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.GuiUsageRecipe;

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
     * NEI's own page of the recipes that make an item ({@code uses}: that use it), opened over the planner (opening the
     * planner first when it is not up), so the recipe added with + lands on the board and the page closes back to it.
     * False when NEI knows none.
     */
    public static boolean lookUp(final ItemStack stack, final boolean uses) {
        if (!isPlanner(Minecraft.getMinecraft().currentScreen)) open();
        return uses ? GuiUsageRecipe.openRecipeGui("item", stack) : GuiCraftingRecipe.openRecipeGui("item", stack);
    }
}
