package com.gtnhplanner.machines.web;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The fusion reactors as the website models them (src/lib/machines/fusion.ts): GT5U's fusion overclock describers
 * and GoodGenerator's compact reactors. A reactor's mark is not its hatch voltage; the startup EU gates recipes and
 * sets a compact reactor's parallels (strict {@code <} bands, where tiering uses {@code <=}).
 */
public final class Fusion {

    private Fusion() {}

    /** A reactor: its name, mark (1 to 5), whether compact, its tier and its startup limit. */
    public record Machine(String name, int mark, boolean compact, String tier, double startupLimit) {}

    private static final String[] TIERS = { "LuV", "ZPM", "UV", "UHV", "UEV" };
    private static final double[] STARTUP_LIMITS = { 160_000_000d, 320_000_000d, 640_000_000d, 5_120_000_000d,
        20_480_000_000d };

    public static final List<Machine> MACHINES;
    private static final Map<String, Machine> BY_NAME = new HashMap<>();

    static {
        final List<Machine> all = new ArrayList<>();
        final String[] regular = { "Fusion Control Computer Mark I", "Fusion Control Computer Mark II",
            "Fusion Control Computer Mark III", "FusionTech MK IV", "FusionTech MK V" };
        final String[] compact = { "Compact Fusion Computer MK-I Prototype", "Compact Fusion Computer MK-II",
            "Compact Fusion Computer MK-III", "Compact Fusion Computer MK-IV Prototype",
            "Compact Fusion Computer MK-V" };
        for (int i = 0; i < 5; i++) all.add(new Machine(regular[i], i + 1, false, TIERS[i], STARTUP_LIMITS[i]));
        for (int i = 0; i < 5; i++) all.add(new Machine(compact[i], i + 1, true, TIERS[i], STARTUP_LIMITS[i]));
        MACHINES = Collections.unmodifiableList(all);
        for (final Machine m : all) {
            BY_NAME.put(m.name.toLowerCase(Locale.ROOT), m);
            BY_NAME.put(slug(m.name), m);
        }
    }

    private static String slug(final String name) {
        return name.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-");
    }

    /** getFusionMachine: by name or slug, any case. */
    @Nullable
    public static Machine machine(@Nullable final String name) {
        return name == null || name.isEmpty() ? null : BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }

    /** isFusionRecipe: off the Fusion Reactor map, or run by a reactor; never a power card. */
    public static boolean isRecipe(final Web.Recipe recipe) {
        return recipe.power == null && ((recipe.source != null && "Fusion Reactor".equals(recipe.source.recipeMap))
            || "Fusion Reactor".equals(recipe.machineType)
            || machine(recipe.machineType) != null);
    }

    /** fusionRecipeKey: the recipe's fingerprint in the startup snapshot. */
    public static String recipeKey(final Web.Recipe recipe) {
        return slots(recipe.inputs) + "|"
            + slots(recipe.outputs)
            + "|"
            + Js.str(recipe.durationTicks)
            + "|"
            + Js.str(recipe.eut);
    }

    private static String slots(@Nullable final List<Web.Resource> entries) {
        final List<String> out = new ArrayList<>();
        if (entries != null) for (final Web.Resource r : entries) out.add(r.kind + ":" + r.id + "@" + Js.str(r.amount));
        Collections.sort(out);
        return String.join(";", out);
    }

    /** getFusionStartupEu: the recipe's own metadata wins, else the snapshot by fingerprint. */
    @Nullable
    public static Double startupEu(final Web.Recipe recipe) {
        if (recipe.metadata != null) {
            final JsonElement exported = recipe.metadata.get("fusionStartupEu");
            if (exported != null && exported.isJsonPrimitive()
                && exported.getAsJsonPrimitive()
                    .isNumber()) {
                final double v = exported.getAsDouble();
                if (Double.isFinite(v) && v >= 0) return v;
            }
        }
        return snapshot().get(recipeKey(recipe));
    }

    /** getFusionRecipeMark: the lowest reactor whose startup and voltage carry the recipe. */
    @Nullable
    public static Integer recipeMark(final Web.Recipe recipe) {
        final Double startup = startupEu(recipe);
        if (startup == null) return null;
        int energyMark = -1;
        for (int i = 0; i < STARTUP_LIMITS.length; i++) if (startup <= STARTUP_LIMITS[i]) {
            energyMark = i;
            break;
        }
        return Math.max(Math.max(energyMark < 0 ? 6 : energyMark + 1, Tiers.index(Tiers.forEuT(recipe.eut)) - 5), 1);
    }

    /** fusionParallels: a compact reactor's 64 per mark above the recipe's startup band. */
    public static int parallels(final int mark, final boolean compact, final double startup) {
        if (!compact) return 1;
        int band = 0;
        for (final double limit : new double[] { 160_000_000d, 320_000_000d, 640_000_000d, 5_120_000_000d })
            if (startup >= limit) band++;
        return 64 * Math.max(1, mark - band);
    }

    /** getFusionStats: what a reactor makes of a recipe. */
    public record Stats(Machine machine, @Nullable Integer recipeMark, @Nullable Double startup, boolean eligible,
        double factor, int steps, int parallels, double poolEuT, double durationTicks, double eut) {}

    @Nullable
    public static Stats stats(final Web.Recipe recipe) {
        final Machine machine = machine(recipe.machineType);
        if (machine == null || recipe.power != null) return null;
        final Double startup = startupEu(recipe);
        final Integer recipeMark = recipeMark(recipe);
        final boolean eligible = recipeMark != null && recipeMark <= machine.mark
            && (startup == null ? Double.POSITIVE_INFINITY : startup) <= machine.startupLimit;
        final double factor = machine.mark < 4 ? 2 : 4;
        final int parallels = startup == null ? 1 : parallels(machine.mark, machine.compact, startup);
        final double poolEuT = Tiers.maxEuT(machine.tier) * (machine.compact ? 64 * machine.mark : 1);
        int steps = 0;
        double duration = recipe.durationTicks;
        double eut = recipe.eut;
        // ParallelHelper reserves structural parallels before overclocking, at the reactor's own voltage.
        final double budget = Math.min(Tiers.maxEuT(machine.tier), poolEuT / parallels);
        while (eligible && steps < machine.mark - recipeMark && eut * factor <= budget) {
            steps++;
            duration /= factor;
            eut *= factor;
        }
        return new Stats(machine, recipeMark, startup, eligible, factor, steps, parallels, poolEuT, duration, eut);
    }

    /** normalizeFusionHandler: a reactor runs as a multiblock at its own tier, its parallels a fixed setting. */
    public static Web.Handler normalizeHandler(final Web.Handler handler, final Web.Recipe recipe) {
        final Machine machine = machine(handler.machineType);
        if (machine == null || !isRecipe(recipe)) return handler;
        final Double startup = startupEu(recipe);
        final int parallels = startup == null ? 1 : parallels(machine.mark, machine.compact, startup);
        final Web.Handler h = handler.copy();
        h.kind = "multiblock";
        h.minimumTier = machine.tier;
        h.maximumTier = null;
        h.availableTiers = null;
        h.durationTicks = null;
        h.eut = null;
        h.maxParallel = (double) parallels;
        h.perfectOverclock = true;
        h.machineConfigControls = new ArrayList<>();
        if (parallels > 1) {
            final Web.Control c = new Web.Control();
            c.id = "machineParallel";
            c.label = "Parallels";
            c.minimumKey = String.valueOf(parallels);
            c.defaultKey = String.valueOf(parallels);
            final Web.TierOption o = new Web.TierOption();
            o.key = String.valueOf(parallels);
            o.label = parallels + " Parallels";
            o.parallelMultiplier = (double) parallels;
            final Web.Resource icon = new Web.Resource();
            icon.kind = "item";
            icon.id = "factoryflow:machine_config/parallel";
            icon.amount = 1;
            icon.displayName = parallels + " Parallels";
            o.resource = icon;
            c.tiers = List.of(o);
            h.machineConfigControls.add(c);
        }
        return h;
    }

    private static Map<String, Double> snapshot;

    /** fusion-startups.json, the website's snapshot of GT's fusion thresholds by recipe fingerprint. */
    private static synchronized Map<String, Double> snapshot() {
        if (snapshot != null) return snapshot;
        final Map<String, Double> out = new HashMap<>();
        try (InputStream in = Fusion.class.getResourceAsStream("/assets/gtnhplanner/machines/fusion-startups.json")) {
            if (in != null) {
                final JsonObject recipes = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject()
                    .getAsJsonObject("recipes");
                for (final Map.Entry<String, JsonElement> e : recipes.entrySet()) out.put(
                    e.getKey(),
                    e.getValue()
                        .getAsDouble());
            }
        } catch (final Exception ignored) {
            // No snapshot: recipes without their own startup stay unknown, as on the website without its data.
        }
        snapshot = out;
        return out;
    }
}
