package com.gtnhplanner.ui.tutorial;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiInventory;

import org.lwjgl.opengl.Display;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.ui.Planner;
import com.gtnhplanner.ui.tutorial.Targets.Target;
import com.gtnhplanner.ui.tutorial.Tour.Beat;
import com.gtnhplanner.ui.tutorial.Tour.Chapter;

/**
 * Plays the tour: a beat at a time, its steps a frame at a time, then its caption held long enough to read, then the
 * next. Next hurries the beat to its end; Back and the chapter list go to any beat by putting the tour's plans back
 * as they were at that chapter's start, opening its screen, and hurrying through the beats before it. Its clock only
 * runs while it plays, so a pause (or the game window losing focus) stops everything where it is.
 */
final class Director {

    enum Mode {
        /** The chapter list, before starting or between. */
        MENU,
        PLAY,
        /** The last beat is done: the end card. */
        END
    }

    final List<Chapter> chapters;
    final Sandbox sandbox;
    final Ghost ghost = new Ghost();
    final Spotlight spotlight = new Spotlight();

    Mode mode = Mode.MENU;
    int chapter, beat;
    boolean paused;
    /** Hurrying to (fastChapter, fastBeat): every step at once, nothing held. */
    private boolean fast;
    private int fastChapter, fastBeat;
    /** Only this beat hurries (Next pressed part way through it). */
    private boolean hurryBeat;

    private List<java.util.function.Supplier<Step>> steps = List.of();
    private final List<Step> made = new ArrayList<>();
    private int step;
    private long beatDoneAt = -1;
    private String caption = "";
    private long captionAt;
    private Target spotTarget;
    private boolean spotDims;

    private long clock, lastReal = -1;
    /** Until then no step runs: a screen just opened is still building its cards and finding its view. */
    private long settleUntil;
    /** Times the open screen has drawn; what is under the pointer is found as it draws. */
    private long draws;
    private float px = -1, py = -1;

    /**
     * How things stood at the start of each beat reached from a settled screen (the board with nothing open over it,
     * the inventory, the world): the tour's plans, the screen, NEI's search. Going back to one of these beats puts
     * that back and plays on; going anywhere else hurries on from the nearest one before it.
     */
    private record Saved(String plans, Tour.Scene scene, String search, boolean played) {}

    private final Map<Long, Saved> saved = new HashMap<>();

    private static long key(final int chapter, final int beat) {
        return (long) chapter << 16 | beat;
    }

    /** Chapters seen, for the list's marks. */
    final java.util.Set<Integer> seen = new java.util.HashSet<>();

    Director(final List<Chapter> chapters, final Sandbox sandbox) {
        this.chapters = chapters;
        this.sandbox = sandbox;
    }

    // region What steps see

    long now() {
        return clock;
    }

    float px() {
        return px;
    }

    float py() {
        return py;
    }

    void pointTo(final float x, final float y) {
        px = x;
        py = y;
        Pointer.drive(x, y);
    }

    Ghost ghost() {
        return ghost;
    }

    void drawn() {
        draws++;
    }

    long draws() {
        return draws;
    }

    boolean hurrying() {
        return fast || hurryBeat;
    }

    void spot(final Target t) {
        spot(t, true);
    }

    /** Lights a target: the rest dimmed, or ({@code dims} false) only a frame round it. */
    void spot(final Target t, final boolean dims) {
        spotTarget = t;
        spotDims = dims;
        if (t == null) spotlight.off();
    }

    void caption(final String text) {
        caption = text;
        captionAt = clock;
    }

    /** A step that cannot go on: noted, and the beat carries on without it. */
    boolean gaveUp(final String why) {
        GtnhPlanner.LOG.warn("[tutorial] chapter {} beat {}: {}", chapter + 1, beat + 1, why);
        return true;
    }

    // endregion

    // region What the panel reads

    Chapter currentChapter() {
        return chapters.get(Math.min(chapter, chapters.size() - 1));
    }

    String caption() {
        return caption;
    }

    /** How much of the caption shows yet, as it comes in. */
    int captionShown() {
        return fast ? caption.length() : (int) Math.min(caption.length(), (clock - captionAt) * 0.11f);
    }

    boolean catchingUp() {
        return fast;
    }

    /** How far the beat's hold has run, 0 to 1, for the bar's progress line. */
    float holdProgress() {
        if (beatDoneAt < 0 || mode != Mode.PLAY) return 0;
        return Math.min(
            1,
            (clock - beatDoneAt) / (float) beats().get(beat)
                .holdMs());
    }

    private List<Beat> beats() {
        return currentChapter().beats;
    }

    // endregion

    // region Each frame

    void frame() {
        final long real = System.currentTimeMillis();
        final long dt = lastReal < 0 ? 0 : Math.min(100, real - lastReal);
        lastReal = real;
        // Paused, or the window in the background: time stands still.
        final boolean running = mode == Mode.PLAY && (fast || !paused && Display.isActive());
        if (running) clock += dt;
        VirtualInput.frame();
        if (spotTarget != null) spotlight.on(spotTarget.rect(), spotDims);
        if (!running || clock < settleUntil) return;
        for (int guard = 0; guard < 64 && step < steps.size(); guard++) {
            if (made.size() <= step) made.add(
                steps.get(step)
                    .get());
            boolean done;
            try {
                done = made.get(step)
                    .frame(this);
            } catch (final RuntimeException | LinkageError e) {
                GtnhPlanner.LOG.warn("[tutorial] a step failed", e);
                done = true;
            }
            if (!done) break;
            step++;
        }
        if (step < steps.size()) return;
        if (beatDoneAt < 0) beatDoneAt = clock;
        if (fast || hurryBeat
            || clock - beatDoneAt >= beats().get(beat)
                .holdMs())
            advance();
    }

    private void startBeat() {
        final Beat b = beats().get(beat);
        steps = new ArrayList<>(b.steps);
        made.clear();
        step = 0;
        beatDoneAt = -1;
        spot(null);
        caption(b.caption);
        // The cursor shows when a step moves it, and stays hidden while catching up.
        if (fast) ghost.show(false);
        if (beat == 0) seen.add(chapter);
        final Tour.Scene scene = settledScene();
        if (scene != null) {
            final codechicken.nei.SearchField f = codechicken.nei.LayoutManager.searchField;
            saved.put(
                key(chapter, beat),
                new Saved(sandbox.saveTourPlans(), scene, f == null ? "" : f.text(), !fast && !hurryBeat));
        }
    }

    /** On to the next beat, the next chapter, or the end. */
    private void advance() {
        hurryBeat = false;
        beat++;
        if (beat >= beats().size()) {
            beat = 0;
            chapter++;
            if (chapter >= chapters.size()) {
                chapter = chapters.size() - 1;
                beat = beats().size() - 1;
                end();
                return;
            }
        }
        if (fast && chapter == fastChapter && beat == fastBeat) {
            fast = false;
            ghost.show(true);
        }
        steps = List.of();
        step = 0;
        beatDoneAt = -1;
        startBeat();
    }

    private void end() {
        mode = Mode.END;
        fast = false;
        spot(null);
        release();
        Minecraft.getMinecraft()
            .displayGuiScreen(new TourScreen());
    }

    /** Writes the start of each chapter played (not hurried) this time, for {@link Starts} to ship. */
    void exportStarts(final java.io.File file) throws java.io.IOException {
        final Map<Integer, String> out = new java.util.TreeMap<>();
        for (int c = 0; c < chapters.size(); c++) {
            final Saved s = saved.get(key(c, 0));
            if (s != null && s.played()) out.put(c, s.plans());
        }
        Starts.write(file, out);
    }

    /** The screen the tour is on, when it can be opened again just so; null mid-way (a menu, NEI's page, a popup). */
    private static Tour.Scene settledScene() {
        final net.minecraft.client.gui.GuiScreen s = Minecraft.getMinecraft().currentScreen;
        if (s instanceof TourScreen) return Tour.Scene.WORLD;
        final com.gtnhplanner.ui.BoardScreen b = Targets.board();
        if (b != null) return Targets.popups()
            .isEmpty() && !b.libraryOpen()
            && !b.key("picker")
                .isEnabled() ? Tour.Scene.BOARD : null;
        if (s instanceof net.minecraft.client.gui.inventory.GuiContainer
            && !(s instanceof codechicken.nei.recipe.GuiRecipe)
            && !com.gtnhplanner.nei.PlanMenu.INSTANCE.isOpen()) return Tour.Scene.INVENTORY;
        return null;
    }

    /** Lets go of the mouse: the pointer back to the player, nothing held. */
    void release() {
        VirtualInput.reset();
        Pointer.release();
        ghost.show(false);
    }

    // endregion

    // region The controls

    void togglePause() {
        if (mode == Mode.PLAY) paused = !paused;
    }

    /** Next: hurries the beat to its end and moves on. */
    void next() {
        if (mode != Mode.PLAY || fast) return;
        paused = false;
        if (step < steps.size()) hurryBeat = true;
        else advance();
    }

    /** Back: the beat before this one, from its start. */
    void back() {
        if (mode == Mode.END) {
            goTo(chapters.size() - 1, chapters.get(chapters.size() - 1).beats.size() - 1);
            return;
        }
        if (mode != Mode.PLAY || fast) return;
        int c = chapter, b = beat - 1;
        if (b < 0) {
            if (c == 0) b = 0;
            else {
                c--;
                b = chapters.get(c).beats.size() - 1;
            }
        }
        goTo(c, b);
    }

    void menu() {
        mode = Mode.MENU;
        fast = false;
        spot(null);
        release();
    }

    /**
     * Goes to a beat: back to how things stood at its start when that was saved, else at the nearest saved beat before
     * it (the plans put back, the screen opened), hurrying through the beats between.
     */
    void goTo(final int toChapter, final int toBeat) {
        int fromChapter = 0, fromBeat = 0;
        Saved from = null;
        search: for (int c = toChapter; c >= 0; c--) {
            for (int b = c == toChapter ? toBeat : chapters.get(c).beats.size() - 1; b >= 0; b--) {
                final Saved s = saved.get(key(c, b));
                if (s != null) {
                    from = s;
                    fromChapter = c;
                    fromBeat = b;
                    break search;
                }
            }
        }
        // A chapter not reached yet starts from the plans it ships with, when they read in this pack.
        final int shipped = Starts.nearest(toChapter, from == null ? -1 : fromChapter);
        if (shipped >= 0) {
            final String plans = Starts.plans(shipped);
            if (plans != null) {
                from = new Saved(plans, chapters.get(shipped).scene, "", true);
                fromChapter = shipped;
                fromBeat = 0;
            }
        }
        release();
        ghost.clear();
        paused = false;
        hurryBeat = false;
        sandbox.clearSearch();
        if (from != null) {
            sandbox.loadTourPlans(from.plans());
            openScene(from.scene());
            if (codechicken.nei.LayoutManager.searchField != null)
                codechicken.nei.LayoutManager.searchField.setText(from.search());
        } else openScene(chapters.get(0).scene);
        chapter = fromChapter;
        beat = fromBeat;
        mode = Mode.PLAY;
        fast = fromChapter != toChapter || fromBeat != toBeat;
        fastChapter = toChapter;
        fastBeat = toBeat;
        steps = List.of();
        step = 0;
        beatDoneAt = -1;
        // A hurried first step would act on the screen before it has built itself (cards, their view, popups' places).
        settleUntil = clock + 400;
        // The pointer starts from the middle of the screen.
        final net.minecraft.client.gui.ScaledResolution sr = Targets.resolution();
        pointTo(sr.getScaledWidth() / 2f, sr.getScaledHeight() / 2f);
        startBeat();
    }

    /**
     * The player's inventory, NEI beside it. In creative the inventory opens on the tab of the player's own slots: NEI
     * hides its list on the item tabs.
     */
    static void openInventory() {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.playerController != null && mc.playerController.isInCreativeMode()) {
            try {
                final java.lang.reflect.Field tab = cpw.mods.fml.relauncher.ReflectionHelper.findField(
                    net.minecraft.client.gui.inventory.GuiContainerCreative.class,
                    "selectedTabIndex",
                    "field_147058_w");
                tab.setInt(null, net.minecraft.creativetab.CreativeTabs.tabInventory.getTabIndex());
            } catch (final RuntimeException | ReflectiveOperationException e) {
                GtnhPlanner.LOG.warn("[tutorial] could not pick the creative inventory's tab", e);
            }
        }
        mc.displayGuiScreen(new GuiInventory(mc.thePlayer));
    }

    /** Opens a chapter's first screen afresh. */
    static void openScene(final Tour.Scene scene) {
        final Minecraft mc = Minecraft.getMinecraft();
        switch (scene) {
            case WORLD -> mc.displayGuiScreen(new TourScreen());
            case INVENTORY -> {
                mc.displayGuiScreen(null);
                openInventory();
            }
            case BOARD -> {
                mc.displayGuiScreen(null);
                Planner.open();
            }
        }
    }

    // endregion
}
