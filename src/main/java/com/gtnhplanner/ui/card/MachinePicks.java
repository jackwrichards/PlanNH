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
import java.util.Properties;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;

import com.gtnhplanner.GtnhPlanner;

import codechicken.nei.recipe.IRecipeHandler;

/**
 * The machine last picked in the plan button's menu, per NEI recipe tab: the next recipe from that tab goes on it
 * without asking (by Shift-click or NEI's +). Kept in the config folder, so it carries across worlds and games.
 */
public final class MachinePicks {

    private static Properties picks;

    private MachinePicks() {}

    /** The {@link MachineChoices.Choice#key()} last picked for the handler's tab, or null. */
    @Nullable
    public static String get(final IRecipeHandler handler) {
        return load().getProperty(tab(handler));
    }

    public static void put(final IRecipeHandler handler, final String key) {
        load().setProperty(tab(handler), key);
        save();
    }

    /**
     * The tab: its handler and overlay id (GregTech has one handler for all its recipe maps, told apart by the overlay
     * id), or the handler and its recipe name when it has no overlay id.
     */
    private static String tab(final IRecipeHandler handler) {
        String id = handler.getHandlerId();
        if (id == null || id.isEmpty()) id = handler.getClass()
            .getName();
        final String overlay = handler.getOverlayIdentifier();
        return id + "|" + (overlay == null || overlay.isEmpty() ? handler.getRecipeName() : overlay);
    }

    /** What is remembered now, for the tour to put back when it ends. */
    public static Properties copy() {
        return (Properties) load().clone();
    }

    /** Puts back what {@link #copy()} took, on disk too. */
    public static void restore(final Properties was) {
        picks = (Properties) was.clone();
        save();
    }

    private static File file() {
        return new File(Minecraft.getMinecraft().mcDataDir, "config/gtnhplanner-machine-picks.properties");
    }

    private static Properties load() {
        if (picks != null) return picks;
        picks = new Properties();
        // Read from the name before the rename until the new one has been written.
        File f = file();
        if (!f.isFile()) f = new File(f.getParentFile(), "plannh-machine-picks.properties");
        if (!f.isFile()) return picks;
        try (Reader in = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            picks.load(in);
        } catch (final IOException e) {
            GtnhPlanner.LOG.warn("Could not read {}", f, e);
        }
        return picks;
    }

    private static void save() {
        final File f = file(), dir = f.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        try (Writer out = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            picks.store(out, "GTNH Planner: the machine last picked for each NEI recipe tab");
        } catch (final IOException e) {
            GtnhPlanner.LOG.warn("Could not write {}", f, e);
        }
    }
}
