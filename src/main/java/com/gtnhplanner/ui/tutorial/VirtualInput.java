package com.gtnhplanner.ui.tutorial;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.core.mixins.early.minecraft.GuiScreenAccessor;
import com.cleanroommc.modularui.screen.ClientScreenHandler;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.gtnhplanner.GtnhPlanner;

/**
 * The tour's hand: presses, drags, releases, wheel turns and keys, delivered to the open screen the way the game
 * delivers the player's (ModularUI's screens first, then the screen itself and NEI's hooks on it), at the pointer.
 * Nothing goes through the operating system, so it works the same on every Java and LWJGL the pack runs on.
 */
final class VirtualInput {

    private VirtualInput() {}

    /** The button held down, or -1. */
    private static int held = -1;
    private static int lastX = Integer.MIN_VALUE, lastY;

    private static int px() {
        return (int) Math.floor(Pointer.x());
    }

    private static int py() {
        return (int) Math.floor(Pointer.y());
    }

    private static GuiScreen screen() {
        return Minecraft.getMinecraft().currentScreen;
    }

    /** ModularUI's screen, when it is the one open. */
    private static ModularScreen mui() {
        final GuiScreen gui = screen();
        final ModularScreen m = ClientScreenHandler.getMuiScreen();
        return gui != null && m != null && ClientScreenHandler.getMCScreen() == gui ? m : null;
    }

    static boolean holding() {
        return held >= 0;
    }

    static void press(final int button) {
        final GuiScreen gui = screen();
        if (gui == null) return;
        held = button;
        lastX = px();
        lastY = py();
        final GuiScreenAccessor acc = (GuiScreenAccessor) gui;
        acc.setEventButton(button);
        acc.setLastMouseEvent(Minecraft.getSystemTime());
        try {
            final ModularScreen m = mui();
            final boolean used = m != null && (m.onMouseInputPre(button, true) || m.onMousePressed(button));
            if (!used) acc.invokeMouseClicked(px(), py(), button);
        } catch (final Exception | LinkageError e) {
            GtnhPlanner.LOG.warn("[tutorial] press failed", e);
        }
    }

    static void release() {
        final GuiScreen gui = screen();
        final int button = held;
        held = -1;
        if (gui == null || button < 0) return;
        final GuiScreenAccessor acc = (GuiScreenAccessor) gui;
        acc.setEventButton(-1);
        try {
            final ModularScreen m = mui();
            final boolean used = m != null && (m.onMouseInputPre(button, false) || m.onMouseRelease(button));
            if (!used) acc.invokeMouseReleased(px(), py(), button);
        } catch (final Exception | LinkageError e) {
            GtnhPlanner.LOG.warn("[tutorial] release failed", e);
        }
    }

    /** Called every frame: a held button that moved drags, as the game reports a move with a button down. */
    static void frame() {
        if (held < 0) return;
        final int x = px(), y = py();
        if (x == lastX && y == lastY) return;
        lastX = x;
        lastY = y;
        final GuiScreen gui = screen();
        if (gui == null) return;
        final GuiScreenAccessor acc = (GuiScreenAccessor) gui;
        final long since = Minecraft.getSystemTime() - acc.getLastMouseEvent();
        try {
            final ModularScreen m = mui();
            final boolean used = m != null && m.onMouseDrag(held, since);
            if (!used) acc.invokeMouseClickMove(x, y, held, since);
        } catch (final Exception | LinkageError e) {
            GtnhPlanner.LOG.warn("[tutorial] drag failed", e);
        }
    }

    static void scroll(final int notches) {
        final ModularScreen m = mui();
        if (m == null || notches == 0) return;
        try {
            m.onMouseScroll(notches > 0 ? UpOrDown.UP : UpOrDown.DOWN, Math.abs(notches));
        } catch (final Exception | LinkageError e) {
            GtnhPlanner.LOG.warn("[tutorial] scroll failed", e);
        }
    }

    static void key(final char c, final int code) {
        final GuiScreen gui = screen();
        if (gui == null) return;
        Pointer.typing = true;
        try {
            final ModularScreen m = mui();
            // NEI's search takes its keys first when it has the focus (NEI reads keys its own way on every screen).
            final codechicken.nei.SearchField search = codechicken.nei.LayoutManager.searchField;
            if (search != null && search.focused() && search.handleKeyPress(code, c)) return;
            if (m == null || !m.onKeyPressed(c, code)) ((GuiScreenAccessor) gui).invokeKeyTyped(c, code);
        } catch (final Exception | LinkageError e) {
            GtnhPlanner.LOG.warn("[tutorial] key failed", e);
        } finally {
            Pointer.typing = false;
        }
    }

    /** The key code for a character the tour types: letters, digits and the usual marks; 0 for the rest. */
    static int codeOf(final char c) {
        if (c == ' ') return org.lwjgl.input.Keyboard.KEY_SPACE;
        if (c == '\n' || c == '\r') return org.lwjgl.input.Keyboard.KEY_RETURN;
        if (c == '.') return org.lwjgl.input.Keyboard.KEY_PERIOD;
        if (c == ',') return org.lwjgl.input.Keyboard.KEY_COMMA;
        if (c == '-') return org.lwjgl.input.Keyboard.KEY_MINUS;
        final int i = org.lwjgl.input.Keyboard.getKeyIndex(String.valueOf(Character.toUpperCase(c)));
        return i == org.lwjgl.input.Keyboard.KEY_NONE ? 0 : i;
    }

    /** Lets go of anything held, without a release reaching the screen: the tour is stopping. */
    static void reset() {
        held = -1;
    }
}
