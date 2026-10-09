package com.gtnhplanner.ui.tutorial;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiInventory;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.ui.Planner;
import com.gtnhplanner.ui.tutorial.Targets.Target;
import com.gtnhplanner.ui.tutorial.Tour.Beat;

/**
 * Plays the tour a beat at a time: the beat's steps a frame at a time, then it waits for Next. Next part way through a
 * beat hurries it to its end. Back goes to the beat before by putting the tour's plans back as they were at its start
 * and opening its screen; a beat whose start was not saved (it began on NEI's page, or with a menu open) is reached by
 * hurrying on from the nearest one that was.
 */
final class Director {

    final List<Beat> beats;
    final Sandbox sandbox;
    final Ghost ghost = new Ghost();
    final Spotlight spotlight = new Spotlight();

    int beat;
    /** Hurrying to {@link #fastBeat}: every step at once. */
    private boolean fast;
    private int fastBeat;
    /** Only this beat hurries (Next pressed part way through it). */
    private boolean hurryBeat;

    private List<java.util.function.Supplier<Step>> steps = List.of();
    private final List<Step> made = new ArrayList<>();
    private int step;
    private Target spotTarget;
    private boolean spotDims;

    /** The callout: what it says and what it points at (null: the middle of the screen); text null for none. */
    private String noteText;
    private Target noteTarget;
    private long noteAt;

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

    private final Map<Integer, Saved> saved = new HashMap<>();

    Director(final List<Beat> beats, final Sandbox sandbox) {
        this.beats = beats;
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

    void note(final Target t, final String text) {
        noteTarget = t;
        noteText = text;
        noteAt = System.currentTimeMillis();
    }

    /** A step that cannot go on: noted, and the beat carries on without it. */
    boolean gaveUp(final String why) {
        GtnhPlanner.LOG.warn("[tutorial] step {}: {}", beat + 1, why);
        return true;
    }

    // endregion

    // region What the callout reads

    String noteText() {
        return fast ? null : noteText;
    }

    Target noteTarget() {
        return noteTarget;
    }

    long noteAt() {
        return noteAt;
    }

    /** The beat's steps are done: it waits for Next. */
    boolean waiting() {
        return !fast && step >= steps.size();
    }

    boolean catchingUp() {
        return fast;
    }

    boolean first() {
        return beat == 0;
    }

    boolean last() {
        return beat == beats.size() - 1;
    }

    // endregion

    // region Each frame

    void frame() {
        final long real = System.currentTimeMillis();
        final long dt = lastReal < 0 ? 0 : Math.min(100, real - lastReal);
        lastReal = real;
        clock += dt;
        VirtualInput.frame();
        if (spotTarget != null) spotlight.on(spotTarget.rect(), spotDims);
        if (clock < settleUntil) return;
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
        hurryBeat = false;
        if (fast) advance();
    }

    private void startBeat() {
        steps = new ArrayList<>(beats.get(beat).steps);
        made.clear();
        step = 0;
        spot(null);
        noteText = null;
        noteTarget = null;
        // The cursor shows when a step moves it, and stays hidden while catching up.
        if (fast) ghost.show(false);
        final Tour.Scene scene = settledScene();
        if (scene != null) {
            final codechicken.nei.SearchField f = codechicken.nei.LayoutManager.searchField;
            saved.put(beat, new Saved(sandbox.saveTourPlans(), scene, f == null ? "" : f.text(), !fast && !hurryBeat));
        }
    }

    private void advance() {
        hurryBeat = false;
        if (beat >= beats.size() - 1) return;
        beat++;
        if (fast && beat == fastBeat) {
            fast = false;
            ghost.show(true);
        }
        startBeat();
    }

    /** Writes the start of each beat played (not hurried) this time, for {@link Starts} to ship. */
    void exportStarts(final java.io.File file) throws java.io.IOException {
        final Map<Integer, Starts.Entry> out = new java.util.TreeMap<>();
        for (final Map.Entry<Integer, Saved> e : saved.entrySet()) if (e.getValue()
            .played())
            out.put(
                e.getKey(),
                new Starts.Entry(
                    e.getValue()
                        .plans(),
                    e.getValue()
                        .scene()));
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

    /**
     * Next: part way through a beat, hurries it to its end (where it waits again); at its end, the next beat. Returns
     * false at the end of the last beat: the tour is over.
     */
    boolean next() {
        if (fast) return true;
        if (step < steps.size()) {
            hurryBeat = true;
            return true;
        }
        if (last()) return false;
        advance();
        return true;
    }

    /** Back: the beat before this one, from its start. */
    void back() {
        if (fast || beat == 0) return;
        goTo(beat - 1);
    }

    /**
     * Goes to a beat: back to how things stood at its start when that was saved, else at the nearest saved beat before
     * it (the plans put back, the screen opened), hurrying through the beats between.
     */
    void goTo(final int to) {
        final int target = Math.max(0, Math.min(beats.size() - 1, to));
        int from = 0;
        Saved start = null;
        for (int b = target; b >= 0; b--) {
            final Saved s = saved.get(b);
            if (s != null) {
                start = s;
                from = b;
                break;
            }
        }
        // A beat not reached yet starts from the plans the tour ships with, when they read in this pack.
        final int shipped = Starts.nearest(target, start == null ? -1 : from);
        if (shipped >= 0) {
            final String plans = Starts.plans(shipped);
            if (plans != null) {
                start = new Saved(plans, Starts.scene(shipped), "", true);
                from = shipped;
            }
        }
        release();
        ghost.clear();
        hurryBeat = false;
        sandbox.clearSearch();
        if (start != null) {
            sandbox.loadTourPlans(start.plans());
            openScene(start.scene());
            if (codechicken.nei.LayoutManager.searchField != null)
                codechicken.nei.LayoutManager.searchField.setText(start.search());
        } else openScene(Script.START);
        beat = from;
        fast = from != target;
        fastBeat = target;
        // A first step would act on the screen before it has built itself (cards, their view, popups' places).
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

    /** Opens a beat's screen afresh. */
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
