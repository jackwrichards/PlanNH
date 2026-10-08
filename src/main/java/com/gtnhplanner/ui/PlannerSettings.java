package com.gtnhplanner.ui;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import net.minecraft.client.Minecraft;

import com.gtnhplanner.GtnhPlanner;

/**
 * The player's planner settings (the top bar's gear): how the board looks zoomed out, the minimap, and the in-world
 * views of linked machines. Kept in {@code config/gtnhplanner-settings.properties}, so they carry across worlds.
 */
public final class PlannerSettings {

    /** Where the minimap sits on the screen. */
    public enum Corner {

        TOP_RIGHT("Top right"),
        TOP_LEFT("Top left"),
        BOTTOM_RIGHT("Bottom right"),
        BOTTOM_LEFT("Bottom left");

        public final String label;

        Corner(final String label) {
            this.label = label;
        }
    }

    /** The minimap's sizes, in GUI pixels. */
    public static final int[] MINIMAP_SIZES = { 110, 160, 220, 300 };
    public static final String[] MINIMAP_SIZE_NAMES = { "Small", "Medium", "Large", "Huge" };
    /** The minimap's zoom steps (world units per screen pixel inverted: board pixels drawn per screen pixel). */
    public static final float[] MINIMAP_ZOOMS = { 0.04f, 0.06f, 0.09f, 0.13f, 0.18f, 0.25f, 0.35f };
    /** How far away linked machines show their AR panels, in blocks. */
    public static final int[] AR_RANGES = { 16, 32, 64, 128 };

    private static Properties props;

    private PlannerSettings() {}

    // region The settings

    /** Board: names of drawers and machines over them when zoomed far out. */
    public static boolean zoomedOutNames() {
        return bool("board.zoomedOutNames", false);
    }

    public static void setZoomedOutNames(final boolean on) {
        set("board.zoomedOutNames", on);
    }

    public static boolean minimap() {
        return bool("minimap.on", false);
    }

    public static void setMinimap(final boolean on) {
        set("minimap.on", on);
    }

    public static int minimapSizeIndex() {
        return clamp(integer("minimap.size", 1), MINIMAP_SIZES.length);
    }

    public static void setMinimapSizeIndex(final int i) {
        set("minimap.size", clamp(i, MINIMAP_SIZES.length));
    }

    public static int minimapSize() {
        return MINIMAP_SIZES[minimapSizeIndex()];
    }

    public static boolean minimapCircle() {
        return bool("minimap.circle", false);
    }

    public static void setMinimapCircle(final boolean circle) {
        set("minimap.circle", circle);
    }

    public static Corner minimapCorner() {
        try {
            return Corner.valueOf(load().getProperty("minimap.corner", Corner.TOP_RIGHT.name()));
        } catch (final IllegalArgumentException e) {
            return Corner.TOP_RIGHT;
        }
    }

    public static void setMinimapCorner(final Corner corner) {
        load().setProperty("minimap.corner", corner.name());
        save();
    }

    public static int minimapZoomIndex() {
        return clamp(integer("minimap.zoom", 2), MINIMAP_ZOOMS.length);
    }

    public static void setMinimapZoomIndex(final int i) {
        set("minimap.zoom", clamp(i, MINIMAP_ZOOMS.length));
    }

    /** The minimap centres on the card of the linked machine under the crosshair. */
    public static boolean minimapFollows() {
        return bool("minimap.follow", true);
    }

    public static void setMinimapFollows(final boolean on) {
        set("minimap.follow", on);
    }

    /** World: an outline on linked machines while you look at them, and their card's name by the crosshair. */
    public static boolean worldHighlight() {
        return bool("world.highlight", true);
    }

    public static void setWorldHighlight(final boolean on) {
        set("world.highlight", on);
    }

    public static boolean arLens() {
        return bool("world.ar", false);
    }

    public static void setArLens(final boolean on) {
        set("world.ar", on);
    }

    public static int arRangeIndex() {
        return clamp(integer("world.arRange", 2), AR_RANGES.length);
    }

    public static void setArRangeIndex(final int i) {
        set("world.arRange", clamp(i, AR_RANGES.length));
    }

    public static int arRange() {
        return AR_RANGES[arRangeIndex()];
    }

    // endregion

    // region Storage

    private static boolean bool(final String key, final boolean fallback) {
        final String v = load().getProperty(key);
        return v == null ? fallback : Boolean.parseBoolean(v);
    }

    private static int integer(final String key, final int fallback) {
        try {
            return Integer.parseInt(load().getProperty(key, Integer.toString(fallback)));
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(final int i, final int length) {
        return Math.max(0, Math.min(length - 1, i));
    }

    private static void set(final String key, final Object value) {
        load().setProperty(key, String.valueOf(value));
        save();
    }

    private static File file() {
        return new File(Minecraft.getMinecraft().mcDataDir, "config/gtnhplanner-settings.properties");
    }

    private static synchronized Properties load() {
        if (props != null) return props;
        props = new Properties();
        final File f = file();
        if (!f.isFile()) return props;
        try (Reader in = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (final IOException e) {
            GtnhPlanner.LOG.warn("Could not read {}", f, e);
        }
        return props;
    }

    private static synchronized void save() {
        final File f = file(), dir = f.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        try (Writer out = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            props.store(out, "GTNH Planner settings");
        } catch (final IOException e) {
            GtnhPlanner.LOG.warn("Could not write {}", f, e);
        }
    }

    // endregion
}
