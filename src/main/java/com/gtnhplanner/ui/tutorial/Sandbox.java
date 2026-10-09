package com.gtnhplanner.ui.tutorial;

import java.util.Properties;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;

import com.gtnhplanner.api.PlanAPI;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.data.flowchart.Serializer;
import com.gtnhplanner.ui.Planner;
import com.gtnhplanner.ui.PlannerSettings;
import com.gtnhplanner.ui.card.MachinePicks;
import com.gtnhplanner.ui.card.SettingMemory;
import com.gtnhplanner.ui.world.PlanSnapshot;

import codechicken.nei.LayoutManager;

/**
 * Keeps the player's things safe while the tour runs: their plans are put aside for plans of the tour's own (never
 * saved), and everything else the tour touches is noted and put back when it ends: the minimap's settings, the
 * machine picked per NEI tab, the settings remembered per machine, NEI's search, the minimap's picture of the plan,
 * where the player was, NEI's item groups the tour opened, and the GUI scale (the tour plays at a scale that gives it
 * room; see {@link #roomyScale}).
 */
final class Sandbox {

    private boolean active;
    /** Whether the planner was open when the tour started, so it opens again after. */
    private boolean plannerWasOpen;
    private Properties picks, memory;
    private PlanSnapshot snapshot;
    private float viewX, viewY;
    private String search;
    private boolean minimap, circle, follows;
    private int size, zoom;
    private PlannerSettings.Corner corner;
    /** The player's GUI scale setting (0 is Auto). */
    private int guiScale;

    /** The GUI size the tour is made for: its moves and its saved starts are laid out on a GUI this big. */
    private static final int ROOM_W = 960, ROOM_H = 540;

    void enter() {
        if (active) return;
        active = true;
        final GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        plannerWasOpen = Planner.isPlanner(screen);
        // The board closing saves the player's plan as it is.
        if (screen != null) Minecraft.getMinecraft()
            .displayGuiScreen(null);
        final Minecraft mc = Minecraft.getMinecraft();
        guiScale = mc.gameSettings.guiScale;
        // With no screen open, the next one opens at the new scale.
        final int roomy = roomyScale(mc);
        if (roomy > 0) mc.gameSettings.guiScale = roomy;
        PlanAPI.save();
        picks = MachinePicks.copy();
        memory = SettingMemory.copy();
        snapshot = PlanSnapshot.latest();
        viewX = PlanSnapshot.lastViewX();
        viewY = PlanSnapshot.lastViewY();
        search = LayoutManager.searchField == null ? "" : LayoutManager.searchField.text();
        minimap = PlannerSettings.minimap();
        circle = PlannerSettings.minimapCircle();
        follows = PlannerSettings.minimapFollows();
        size = PlannerSettings.minimapSizeIndex();
        zoom = PlannerSettings.minimapZoomIndex();
        corner = PlannerSettings.minimapCorner();
        Plan.enterSandbox();
    }

    /** The tour's plans as they are, to go back to. */
    String saveTourPlans() {
        return Serializer.encodePlan(Plan.getInstance());
    }

    /** Puts the tour's plans back as {@link #saveTourPlans()} had them. */
    void loadTourPlans(final String saved) {
        Plan.replaceSandbox(Serializer.decodePlan(saved));
    }

    /** Clears NEI's search, as the tour found it before it typed (between chapters too). */
    void clearSearch() {
        if (LayoutManager.searchField != null) LayoutManager.searchField.setText(search == null ? "" : search);
    }

    /** Puts everything back and returns the player to where they were. */
    void leave() {
        if (!active) return;
        active = false;
        final Minecraft mc = Minecraft.getMinecraft();
        // Off the tour's screens first: a board closing on the tour's plan must close while those plans stand in.
        if (mc.currentScreen != null) mc.displayGuiScreen(null);
        mc.gameSettings.guiScale = guiScale;
        Plan.leaveSandbox();
        MachinePicks.restore(picks);
        SettingMemory.restore(memory);
        PlanSnapshot.publish(snapshot);
        PlanSnapshot.setView(viewX, viewY);
        clearSearch();
        Targets.closeNeiGroups();
        PlannerSettings.setMinimap(minimap);
        PlannerSettings.setMinimapCircle(circle);
        PlannerSettings.setMinimapFollows(follows);
        PlannerSettings.setMinimapSizeIndex(size);
        PlannerSettings.setMinimapZoomIndex(zoom);
        PlannerSettings.setMinimapCorner(corner);
        if (plannerWasOpen && mc.theWorld != null) Planner.open();
    }

    /**
     * A smaller GUI scale when the player's leaves the GUI under the tour's {@link #ROOM_W} by {@link #ROOM_H} (a large
     * scale on a small window: GTNH ships scale 4): the largest that gives that room, or 1 when even 1 cannot. On a
     * smaller GUI the tour's drags run out of board (a clone has nowhere to go, a wire drops off the canvas). 0 when
     * the GUI already has the room. Never larger than the scale in use.
     */
    private static int roomyScale(final Minecraft mc) {
        final int now = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
        if (mc.displayWidth / now >= ROOM_W && mc.displayHeight / now >= ROOM_H) return 0;
        int s = now - 1;
        while (s > 1 && (mc.displayWidth / s < ROOM_W || mc.displayHeight / s < ROOM_H)) s--;
        return Math.max(1, s);
    }

    boolean active() {
        return active;
    }
}
