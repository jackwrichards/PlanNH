package com.gtnhplanner.ui.tutorial;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.event.KeyboardInputEvent;
import com.cleanroommc.modularui.api.event.MouseInputEvent;
import com.gtnhplanner.GtnhPlanner;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The tour: GTNH Planner showing itself, in the player's game, while they watch. It starts from the "?" key on the
 * board (or the first-run notice), runs in plans of its own ({@link Sandbox}) and drives the screens with a pointer of
 * its own ({@link Pointer}, {@link VirtualInput}); the player's mouse and keys work only its bar meanwhile, and Esc
 * twice leaves. {@link Script} is what it shows; {@link Director} plays it; {@link Panel} is the bar.
 */
public final class Tutorial {

    private static Tutorial current;

    private final Sandbox sandbox = new Sandbox();
    private final Director director;
    private final Panel panel;
    private long lastDraw = -1;
    private long escAt = -1;

    private Tutorial() {
        director = new Director(Script.chapters(), sandbox);
        panel = new Panel(director, Tutorial::stop);
    }

    /** Whether the tour is running. */
    public static boolean active() {
        return current != null;
    }

    /** Starts the tour on its chapter list (in a world only). */
    public static void start() {
        final Minecraft mc = Minecraft.getMinecraft();
        if (current != null || mc.theWorld == null) return;
        try {
            com.gtnhplanner.ui.PlannerSettings.setTourOffered(true);
            current = new Tutorial();
            current.sandbox.enter();
            mc.displayGuiScreen(new TourScreen());
            GtnhPlanner.LOG.info("[tutorial] started");
        } catch (final RuntimeException | LinkageError e) {
            GtnhPlanner.LOG.error("[tutorial] could not start", e);
            stop();
        }
    }

    /** Starts the tour straight at a chapter and beat (the dev harness). */
    public static void startAt(final int chapter, final int beat) {
        start();
        if (current != null) current.director.goTo(chapter, beat);
    }

    /** Ends the tour: everything put back, the player where they were. */
    public static void stop() {
        final Tutorial t = current;
        if (t == null) return;
        current = null;
        t.director.release();
        try {
            t.sandbox.leave();
        } catch (final RuntimeException | LinkageError e) {
            GtnhPlanner.LOG.error("[tutorial] could not put everything back", e);
        }
        GtnhPlanner.LOG.info("[tutorial] stopped");
    }

    /** Where the tour is, for the dev harness. */
    public static String where() {
        final Tutorial t = current;
        if (t == null) return "off";
        final codechicken.nei.SearchField f = codechicken.nei.LayoutManager.searchField;
        final String search = f == null ? "none"
            : "[" + f.text() + "]" + (f.focused() ? " focused" : "") + " at " + f.x + "," + f.y + " " + f.w + "x" + f.h;
        return t.director.mode + " chapter "
            + (t.director.chapter + 1)
            + " beat "
            + (t.director.beat + 1)
            + (t.director.catchingUp() ? " (catching up)" : "")
            + (t.director.paused ? " (paused)" : "")
            + ": "
            + t.director.caption()
            + " | search "
            + search
            + " | screen "
            + (Minecraft.getMinecraft().currentScreen == null ? "none"
                : Minecraft.getMinecraft().currentScreen.getClass()
                    .getSimpleName());
    }

    /**
     * Writes the start of every chapter played this time into the mod's resources (the dev harness), for picking a
     * chapter not watched yet to start there at once. Returns the file, or null when the tour is not running.
     */
    public static java.io.File exportStarts() throws java.io.IOException {
        final Tutorial t = current;
        if (t == null) return null;
        final java.io.File repo = Minecraft.getMinecraft().mcDataDir.getAbsoluteFile()
            .getParentFile()
            .getParentFile();
        final java.io.File file = new java.io.File(repo, "src/main/resources/assets/gtnhplanner/tutorial/starts.json");
        t.director.exportStarts(file);
        return file;
    }

    /** Plays on at once (the dev harness): the current beat hurried and the next begun. */
    public static void nextBeat() {
        if (current != null) current.director.next();
    }

    // region Events

    public static void register() {
        final Events events = new Events();
        MinecraftForge.EVENT_BUS.register(events);
        FMLCommonHandler.instance()
            .bus()
            .register(events);
    }

    public static final class Events {

        @SubscribeEvent(priority = EventPriority.HIGH)
        public void onRenderTick(final TickEvent.RenderTickEvent e) {
            final Tutorial t = current;
            if (t == null || e.phase != TickEvent.Phase.START) return;
            if (Minecraft.getMinecraft().theWorld == null) {
                stop();
                return;
            }
            try {
                t.director.frame();
            } catch (final RuntimeException | LinkageError ex) {
                GtnhPlanner.LOG.error("[tutorial] stopped by an error", ex);
                stop();
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onDrawn(final GuiScreenEvent.DrawScreenEvent.Post e) {
            final Tutorial t = current;
            if (t == null) return;
            t.director.drawn();
            t.draw();
        }

        /** The player's mouse works the bar only; everything else waits until the tour ends. */
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onMouse(final MouseInputEvent.Pre e) {
            final Tutorial t = current;
            if (t == null) return;
            e.setCanceled(true);
            if (!Mouse.getEventButtonState() || Mouse.getEventButton() != 0) return;
            final Minecraft mc = Minecraft.getMinecraft();
            final ScaledResolution sr = Targets.resolution();
            final float x = Mouse.getEventX() * sr.getScaledWidth() / (float) mc.displayWidth;
            final float y = sr.getScaledHeight() - Mouse.getEventY() * sr.getScaledHeight() / (float) mc.displayHeight
                - 1;
            if (!t.panel.click(x, y) && t.director.mode == Director.Mode.PLAY)
                t.panel.flash("The tour has the mouse: use its bar below, or press Esc twice to leave.");
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onKey(final KeyboardInputEvent.Pre e) {
            final Tutorial t = current;
            if (t == null) return;
            final int key = Keyboard.getEventKey();
            // The function keys (screenshots, the debug screen) stay the game's.
            if (key >= Keyboard.KEY_F1 && key <= Keyboard.KEY_F12 || key == Keyboard.KEY_F13) return;
            e.setCanceled(true);
            if (!Keyboard.getEventKeyState() || Keyboard.isRepeatEvent()) return;
            t.key(key);
        }

        /** The tour keeps a screen up; a world closing ends it. */
        @SubscribeEvent
        public void onOpen(final GuiOpenEvent e) {
            if (current == null) return;
            if (e.gui instanceof GuiMainMenu || Minecraft.getMinecraft().theWorld == null) {
                stop();
                return;
            }
            if (e.gui == null) e.gui = new TourScreen();
        }
    }

    // endregion

    private void key(final int key) {
        switch (key) {
            case Keyboard.KEY_ESCAPE -> {
                final long now = System.currentTimeMillis();
                if (director.mode != Director.Mode.PLAY || now - escAt < 3000) stop();
                else {
                    escAt = now;
                    if (!director.paused) director.togglePause();
                    panel.flash("Paused. Esc again leaves the tour.");
                }
            }
            case Keyboard.KEY_RIGHT, Keyboard.KEY_SPACE, Keyboard.KEY_RETURN -> director.next();
            case Keyboard.KEY_LEFT -> director.back();
            case Keyboard.KEY_P -> director.togglePause();
            default -> {}
        }
    }

    private void draw() {
        final Minecraft mc = Minecraft.getMinecraft();
        final ScaledResolution sr = Targets.resolution();
        final int sw = sr.getScaledWidth(), sh = sr.getScaledHeight();
        final long now = System.currentTimeMillis();
        final float dt = lastDraw < 0 ? 0 : Math.min(100, now - lastDraw);
        lastDraw = now;
        final float mx = Mouse.getX() * sw / (float) mc.displayWidth;
        final float my = sh - Mouse.getY() * sh / (float) mc.displayHeight - 1;
        final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        GL11.glPushMatrix();
        GL11.glTranslatef(0, 0, 450);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        try {
            director.spotlight.draw(sw, sh, dt);
            director.ghost.draw(director.px(), director.py(), dt);
            panel.draw(sw, sh, mx, my, dt);
        } catch (final RuntimeException | LinkageError e) {
            GtnhPlanner.LOG.warn("[tutorial] drawing failed", e);
        } finally {
            GL11.glPopMatrix();
            if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glColor4f(1, 1, 1, 1);
        }
    }
}
