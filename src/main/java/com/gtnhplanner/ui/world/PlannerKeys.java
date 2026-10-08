package com.gtnhplanner.ui.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import com.gtnhplanner.ui.PlannerSettings;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The planner's keys while playing, in Minecraft's Controls under GTNH Planner: the minimap (show, zoom, pan,
 * re-centre), the AR lens, and linking the machine under the crosshair to a card.
 */
public final class PlannerKeys {

    private static final String CATEGORY = "key.categories.neiflowchart";

    static final KeyBinding MINIMAP = new KeyBinding("key.gtnhplanner.minimap", Keyboard.KEY_N, CATEGORY);
    static final KeyBinding ZOOM_IN = new KeyBinding("key.gtnhplanner.minimapZoomIn", Keyboard.KEY_RBRACKET, CATEGORY);
    static final KeyBinding ZOOM_OUT = new KeyBinding(
        "key.gtnhplanner.minimapZoomOut",
        Keyboard.KEY_LBRACKET,
        CATEGORY);
    static final KeyBinding PAN_UP = new KeyBinding("key.gtnhplanner.minimapUp", Keyboard.KEY_UP, CATEGORY);
    static final KeyBinding PAN_DOWN = new KeyBinding("key.gtnhplanner.minimapDown", Keyboard.KEY_DOWN, CATEGORY);
    static final KeyBinding PAN_LEFT = new KeyBinding("key.gtnhplanner.minimapLeft", Keyboard.KEY_LEFT, CATEGORY);
    static final KeyBinding PAN_RIGHT = new KeyBinding("key.gtnhplanner.minimapRight", Keyboard.KEY_RIGHT, CATEGORY);
    static final KeyBinding RECENTRE = new KeyBinding("key.gtnhplanner.minimapRecentre", Keyboard.KEY_NONE, CATEGORY);
    static final KeyBinding AR = new KeyBinding("key.gtnhplanner.ar", Keyboard.KEY_Y, CATEGORY);
    static final KeyBinding LINK = new KeyBinding("key.gtnhplanner.link", Keyboard.KEY_L, CATEGORY);

    public PlannerKeys() {}

    public static void register() {
        for (final KeyBinding k : new KeyBinding[] { MINIMAP, ZOOM_IN, ZOOM_OUT, PAN_UP, PAN_DOWN, PAN_LEFT, PAN_RIGHT,
            RECENTRE, AR, LINK }) ClientRegistry.registerKeyBinding(k);
    }

    /** The presses: show or hide, zoom a step, re-centre, the AR lens, linking. */
    @SubscribeEvent
    public void onKey(final InputEvent.KeyInputEvent event) {
        if (Minecraft.getMinecraft().currentScreen != null) return;
        while (MINIMAP.isPressed()) PlannerSettings.setMinimap(!PlannerSettings.minimap());
        while (AR.isPressed()) PlannerSettings.setArLens(!PlannerSettings.arLens());
        while (LINK.isPressed()) LinkChooser.openForCrosshair();
        if (!PlannerSettings.minimap()) return;
        while (ZOOM_IN.isPressed()) PlannerSettings.setMinimapZoomIndex(PlannerSettings.minimapZoomIndex() + 1);
        while (ZOOM_OUT.isPressed()) PlannerSettings.setMinimapZoomIndex(PlannerSettings.minimapZoomIndex() - 1);
        while (RECENTRE.isPressed()) Minimap.INSTANCE.recentre();
    }

    /** Panning goes on while the keys are held, at a steady speed on screen whatever the zoom. */
    @SubscribeEvent
    public void onTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || Minecraft.getMinecraft().currentScreen != null
            || !PlannerSettings.minimap()) return;
        final int dx = (PAN_RIGHT.getIsKeyPressed() ? 1 : 0) - (PAN_LEFT.getIsKeyPressed() ? 1 : 0);
        final int dy = (PAN_DOWN.getIsKeyPressed() ? 1 : 0) - (PAN_UP.getIsKeyPressed() ? 1 : 0);
        if (dx != 0 || dy != 0) Minimap.INSTANCE.pan(dx, dy);
    }
}
