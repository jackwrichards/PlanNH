package com.sbancuz.plannh.nei;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.screen.ModularScreen;
import com.sbancuz.plannh.ui.Planner;

import codechicken.nei.guihook.GuiContainerManager;
import codechicken.nei.guihook.IContainerInputHandler;

/**
 * The planner's NEI input. The plan key (P by default) over any item NEI can see, in any container screen: opens NEI's
 * recipes for it over the planner (opening the planner first), so + puts the one picked on the board; with Shift, its
 * uses. It stays out of the way while a text field has the keyboard.
 * Registered ahead of NEI's own keys: P is also NEI's Potions key, which still works when the mouse is not over an
 * item.
 */
public final class PlanInputHandler implements IContainerInputHandler {

    private final KeyBinding key;

    public PlanInputHandler(final KeyBinding key) {
        this.key = key;
    }

    @Override
    public boolean keyTyped(final GuiContainer gui, final char keyChar, final int keyCode) {
        if (keyCode != key.getKeyCode() || keyCode == 0) return false;
        // Typing into NEI's search box.
        if (codechicken.nei.LayoutManager.getInputFocused() != null) return false;
        final ModularScreen planner = Planner.screenOf(gui);
        if (planner != null && com.sbancuz.plannh.ui.BoardScreen.textFocused(planner)) return false;
        final ItemStack stack = GuiContainerManager.getStackMouseOver(gui);
        if (stack == null) return false;
        Planner.lookUp(stack.copy(), GuiContainer.isShiftKeyDown());
        return true;
    }

    @Override
    public void onKeyTyped(final GuiContainer gui, final char keyChar, final int keyCode) {}

    @Override
    public boolean lastKeyTyped(final GuiContainer gui, final char keyChar, final int keyCode) {
        return false;
    }

    @Override
    public boolean mouseClicked(final GuiContainer gui, final int mouseX, final int mouseY, final int button) {
        return false;
    }

    @Override
    public void onMouseClicked(final GuiContainer gui, final int mouseX, final int mouseY, final int button) {}

    @Override
    public void onMouseUp(final GuiContainer gui, final int mouseX, final int mouseY, final int button) {}

    @Override
    public boolean mouseScrolled(final GuiContainer gui, final int mouseX, final int mouseY, final int scrolled) {
        return false;
    }

    @Override
    public void onMouseScrolled(final GuiContainer gui, final int mouseX, final int mouseY, final int scrolled) {}

    @Override
    public void onMouseDragged(final GuiContainer gui, final int mouseX, final int mouseY, final int button,
        final long heldTime) {}
}
