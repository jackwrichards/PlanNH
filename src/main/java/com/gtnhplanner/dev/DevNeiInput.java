package com.gtnhplanner.dev;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.inventory.GuiContainer;

import codechicken.nei.guihook.IContainerInputHandler;

/**
 * Records what NEI's input hooks see on a planner screen (presses, releases, drags), so a click that goes missing
 * between ModularUI and NEI can be traced: {@code call neiinput} lists and clears them.
 */
final class DevNeiInput implements IContainerInputHandler {

    static final DevNeiInput INSTANCE = new DevNeiInput();
    private final List<String> seen = new ArrayList<>();

    private void log(final GuiContainer gui, final String what) {
        if (!com.gtnhplanner.ui.Planner.isPlanner(gui)) return;
        synchronized (seen) {
            if (seen.size() < 200) seen.add(what);
        }
    }

    List<String> take() {
        synchronized (seen) {
            final List<String> out = new ArrayList<>(seen);
            seen.clear();
            return out;
        }
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
    public void onMouseClicked(final GuiContainer gui, final int mousex, final int mousey, final int button) {
        log(gui, "press " + button + " at " + mousex + "," + mousey);
    }

    @Override
    public void onMouseUp(final GuiContainer gui, final int mousex, final int mousey, final int button) {
        log(gui, "release " + button + " at " + mousex + "," + mousey);
    }

    @Override
    public boolean mouseScrolled(final GuiContainer gui, final int mousex, final int mousey, final int scrolled) {
        return false;
    }

    @Override
    public void onMouseScrolled(final GuiContainer gui, final int mousex, final int mousey, final int scrolled) {}

    @Override
    public void onMouseDragged(final GuiContainer gui, final int mousex, final int mousey, final int button,
        final long heldTime) {
        log(gui, "drag " + button);
    }
}
