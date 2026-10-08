package com.gtnhplanner.ui.card;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.minecraft.client.Minecraft;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.SettingDef;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.provider.GTProvider;

/**
 * The settings last changed on each machine, so the next card for it starts with them: change an Electric Blast
 * Furnace's coil or overclocks, and the next one added comes the same way. Cards already on the board keep their own,
 * and an imported plan keeps its. Kept in the config folder, so it carries across plans, worlds and games.
 */
public final class SettingMemory {

    private static Properties kept;
    /** Set by the recipe or the machine, not by the player: never carried over. */
    private static final Set<String> NOT_KEPT = Set.of("voltage", "amp", "machines", "recipe_heat", "gt_multiblock");

    private SettingMemory() {}

    /** Remembers a machine setting just changed on a card. */
    public static void remember(final Node node, final String key, final Object value) {
        if (node.machineName == null || NOT_KEPT.contains(key)) return;
        final String tag = value instanceof Boolean ? "b:" : value instanceof Integer ? "i:" : "s:";
        load().setProperty(node.machineName + "|" + key, tag + value);
        save();
    }

    /** Remembers a power card's setting just changed. */
    public static void rememberPower(final String sourceId, final String settingId, final String value) {
        if ("tier".equals(settingId)) return;
        load().setProperty("power:" + sourceId + "|" + settingId, "s:" + value);
        save();
    }

    /**
     * Gives a new card the settings last changed on its machine, those its machine has; a remembered coil only when it
     * is hot enough for the recipe.
     */
    public static void applyTo(final Node node) {
        final MachineConfig cfg = node.machineConfig;
        if (node.machineName == null || cfg == null || cfg.getProfile() == null) return;
        final String prefix = node.machineName + "|";
        for (final String name : load().stringPropertyNames()) {
            if (!name.startsWith(prefix)) continue;
            final String key = name.substring(prefix.length());
            if (def(cfg, key) == null) continue;
            final String raw = kept.getProperty(name);
            if (raw == null || raw.length() < 2) continue;
            final String value = raw.substring(2);
            try {
                switch (raw.charAt(0)) {
                    case 'b' -> cfg.setBoolean(key, Boolean.parseBoolean(value));
                    case 'i' -> {
                        final int v = Integer.parseInt(value);
                        if ("machine_heat".equals(key) && v < recipeHeat(node)) continue;
                        cfg.setInt(key, v);
                    }
                    default -> cfg.setString(key, value);
                }
            } catch (final NumberFormatException ignored) {}
        }
    }

    /** A new power card's settings: those last changed on its source, then {@code given} over them. */
    public static Map<String, String> powerSettings(final String sourceId, final Map<String, String> given) {
        final Map<String, String> out = new HashMap<>();
        final String prefix = "power:" + sourceId + "|";
        for (final String name : load().stringPropertyNames()) {
            if (!name.startsWith(prefix)) continue;
            final String raw = kept.getProperty(name);
            if (raw != null && raw.length() >= 2) out.put(name.substring(prefix.length()), raw.substring(2));
        }
        out.putAll(given);
        return out;
    }

    private static int recipeHeat(final Node node) {
        return node.properties.get(GTProvider.COIL_HEAT) instanceof final Number n ? n.intValue() : 0;
    }

    private static SettingDef<?> def(final MachineConfig cfg, final String key) {
        for (final SettingDef<?> d : cfg.getProfile()
            .settings()) if (d.key.equals(key)) return d;
        return null;
    }

    private static File file() {
        return new File(Minecraft.getMinecraft().mcDataDir, "config/gtnhplanner-setting-memory.properties");
    }

    private static Properties load() {
        if (kept != null) return kept;
        kept = new Properties();
        final File f = file();
        if (!f.isFile()) return kept;
        try (Reader in = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            kept.load(in);
        } catch (final IOException e) {
            GtnhPlanner.LOG.warn("Could not read {}", f, e);
        }
        return kept;
    }

    private static void save() {
        final File f = file(), dir = f.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        try (Writer out = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            kept.store(out, "GTNH Planner: the settings last changed on each machine, for its next card");
        } catch (final IOException e) {
            GtnhPlanner.LOG.warn("Could not write {}", f, e);
        }
    }
}
