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

    /**
     * One of the minimap's keys as the player has it bound, written as people say it ("N", "]", "Up"), for text that
     * says which to press: "show" (show or hide it), "in", "out" (zoom), "up", "down", "left", "right" (move).
     */
    public static String minimapKey(final String what) {
        final KeyBinding k = switch (what) {
            case "show" -> MINIMAP;
            case "in" -> ZOOM_IN;
            case "out" -> ZOOM_OUT;
            case "up" -> PAN_UP;
            case "down" -> PAN_DOWN;
            case "left" -> PAN_LEFT;
            case "right" -> PAN_RIGHT;
            default -> null;
        };
        return k == null ? "?" : keyLabel(k.getKeyCode());
    }

    /** Whether the minimap moves with the arrow keys, as it does until they are bound to others. */
    public static boolean minimapOnArrows() {
        return PAN_UP.getKeyCode() == Keyboard.KEY_UP && PAN_DOWN.getKeyCode() == Keyboard.KEY_DOWN
            && PAN_LEFT.getKeyCode() == Keyboard.KEY_LEFT
            && PAN_RIGHT.getKeyCode() == Keyboard.KEY_RIGHT;
    }

    /** A key's name as people say it: [ and ] as themselves, Ctrl, Shift and Alt, else the game's. */
    public static String keyLabel(final int code) {
        return switch (code) {
            case Keyboard.KEY_LBRACKET -> "[";
            case Keyboard.KEY_RBRACKET -> "]";
            case Keyboard.KEY_MINUS -> "-";
            case Keyboard.KEY_EQUALS -> "=";
            case Keyboard.KEY_COMMA -> ",";
            case Keyboard.KEY_PERIOD -> ".";
            case Keyboard.KEY_SLASH -> "/";
            case Keyboard.KEY_NONE -> "(none)";
            default -> PlacementKeys.keyName(code);
        };
    }

    public static void register() {
        for (final KeyBinding k : new KeyBinding[] { MINIMAP, ZOOM_IN, ZOOM_OUT, PAN_UP, PAN_DOWN, PAN_LEFT, PAN_RIGHT,
            RECENTRE, AR, LINK }) ClientRegistry.registerKeyBinding(k);
    }

    /** The presses: show or hide, zoom a step, re-centre, the AR lens, linking. */
    @SubscribeEvent
    public void onKey(final InputEvent.KeyInputEvent event) {
        if (Minecraft.getMinecraft().currentScreen != null) return;
        while (MINIMAP.isPressed()) PlannerSettings.setMinimap(!PlannerSettings.minimap());
        // The plan over the world on and off, with sneak held or not (Shift + Y in the keys strip).
        while (AR.isPressed()) {
            PlannerSettings.setArLens(!PlannerSettings.arLens());
            WorldView.say(PlannerSettings.arLens() ? "Plan over the world: on" : "Plan over the world: off");
        }
        while (LINK.isPressed()) LinkTarget.beginForCrosshair();
        // Placing a machine takes [ and ] for its size.
        if (!PlannerSettings.minimap() || LinkPicker.active()) {
            while (ZOOM_IN.isPressed() || ZOOM_OUT.isPressed()) {}
            return;
        }
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
