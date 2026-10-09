package com.gtnhplanner.ui.tutorial;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

/**
 * The mouse as the game sees it while the tour drives: the tour's own pointer instead of the real one. Screens hover,
 * highlight and show their tips from where the game says the mouse is, so the tour points by moving this, and the
 * player's real mouse stays theirs (it only works the tour's own bar meanwhile). Two mixins read it: the frame's mouse
 * position (what every screen is drawn with) and NEI's own reading of the mouse.
 */
public final class Pointer {

    private Pointer() {}

    private static volatile boolean driving;
    /** Set while the tour types a key, so a key reader that drops a doubled event takes it. */
    public static volatile boolean typing;
    /** Where it is, in GUI pixels. */
    private static volatile float x, y;

    /** Whether the tour has the mouse. */
    public static boolean driving() {
        return driving;
    }

    /** Takes the mouse, at a GUI point. */
    public static void drive(final float guiX, final float guiY) {
        x = guiX;
        y = guiY;
        driving = true;
    }

    /** Gives the mouse back. */
    public static void release() {
        driving = false;
    }

    public static float x() {
        return x;
    }

    public static float y() {
        return y;
    }

    /** The mouse's X in window pixels, as LWJGL reports it: the tour's when it drives, else {@code real}. */
    public static int windowX(final int real) {
        if (!driving) return real;
        final Minecraft mc = Minecraft.getMinecraft();
        final ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        // The middle of the GUI pixel, so the game's rounding down lands on it exactly.
        return (int) ((Math.floor(x) + 0.5) * mc.displayWidth / sr.getScaledWidth());
    }

    /** The mouse's Y in window pixels, from the bottom as LWJGL counts it. */
    public static int windowY(final int real) {
        if (!driving) return real;
        final Minecraft mc = Minecraft.getMinecraft();
        final ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        final int h = sr.getScaledHeight();
        return (int) ((h - 1 - Math.floor(y) + 0.5) * mc.displayHeight / h);
    }
}
