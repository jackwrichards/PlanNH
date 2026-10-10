package com.gtnhplanner.machines.web;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The website's machine data per recipe map ({@code assets/gtnhplanner/machines/handlers.json}, written by its
 * {@code tools/audits/export-mod-machine-data.mjs}, never hand-edited): which machines run each map, as templates the
 * dataset pipeline instantiates per recipe, and the map's recipe-level settings. A recipe built here carries exactly
 * the {@code machineHandlers} and {@code machineConfigControls} the website's own copy of it does.
 */
public final class HandlerData {

    private HandlerData() {}

    /** A recipe map: its game id, its name (the website's machineType and recipe map), and its machines. */
    public static final class MapEntry {

        public String id;
        public String name;
        public boolean handlersOnRecipes;
        @Nullable
        public List<Template> handlers;
        @Nullable
        public List<String> controls;
        public boolean coilFromSpecialValue;
        @Nullable
        public List<AppHandler> app;
    }

    /** A machine template (instantiateRecipeMachineHandlers in the dataset pipeline). */
    public static final class Template {

        public String id;
        public String label;
        public String kind;
        public boolean primary;
        @Nullable
        public String minimumTier;
        @Nullable
        public String maximumTier;
        @Nullable
        public List<String> availableTiers;
        public boolean perfectOverclock;
        @Nullable
        public Double durationMultiplier;
        @Nullable
        public Double eutMultiplier;
        @Nullable
        public Double durationTicks;
        @Nullable
        public Double eut;
        @Nullable
        public List<String> controls;
        /** The machines this handler stands for, by their website ids ({@code gregtech:gt.blockmachines@1193}). */
        @Nullable
        public List<String> items;
    }

    /** What the website's getRecipeMachineHandlers gives the map's first recipe. */
    public static final class AppHandler {

        public String id;
        public String label;
        public String machineType;
        public String kind;
        @Nullable
        public Double durationTicks;
        @Nullable
        public Double eut;
        /** The machine table entry it runs on, if any. */
        @Nullable
        public String table;
        public boolean multiblock;
        @Nullable
        public List<String> items;
    }

    /** A control in the file's compact form: options carry an icon id and a name in place of a resource. */
    private static final class CompactOption {

        String key, label, icon, name;
        Double heat, durationMultiplier, eutMultiplier, outputMultiplier, parallelMultiplier, parallelPerVoltageTier,
            parallelVoltageBase;
    }

    private static final class CompactControl {

        String id, label, minimumKey, defaultKey;
        Web.Numeric numeric;
        Boolean minimumFromSpecialValue, minimumHeatFromSpecialValue;
        List<CompactOption> tiers;
    }

    /** A heating coil's key, label and heat in K. */
    public record Coil(String key, String label, double heat) {}

    private static Map<String, MapEntry> maps;
    private static Map<String, Web.Control> controls;
    private static List<Coil> coils;
    private static List<String> tierNames;
    /** The dataset's tier names for a recipe's draw and their voltages (it has no UMV: such draws read UXV). */
    private static List<String> recipeTierNames;
    private static List<Double> recipeTierVoltages;

    /**
     * A recipe's minimumTier as the website's dataset writes it (normalize-oracle-export.mjs voltageTierForEu): the
     * first of its tiers whose voltage carries |EU/t|, else MAX. Its table skips UMV, so a UMV draw is marked UXV and a
     * UXV draw OpV (read as UXV): the website's own numbers, kept.
     */
    public static String recipeMinimumTier(final double eut) {
        load();
        final double abs = Math.abs(eut);
        for (int i = 0; i < recipeTierNames.size(); i++)
            if (abs <= recipeTierVoltages.get(i)) return recipeTierNames.get(i);
        return "MAX";
    }

    /** The entry for a game recipe map id ({@code RecipeMap.unlocalizedName}, or "smelting"), or null. */
    @Nullable
    public static MapEntry map(@Nullable final String id) {
        load();
        return id == null ? null : maps.get(id);
    }

    /** Every map in the file. */
    public static Map<String, MapEntry> maps() {
        load();
        return Collections.unmodifiableMap(maps);
    }

    /** The 14 heating coils, cupronickel first. */
    public static List<Coil> coils() {
        load();
        return coils;
    }

    /**
     * The map's recipe-level settings for a recipe with this special value: on the blast furnace maps a positive
     * special value makes the first coil hot enough (the last if none is) the coil's minimum and default.
     */
    public static List<Web.Control> recipeControls(final MapEntry entry, final double specialValue) {
        final List<Web.Control> out = new ArrayList<>();
        if (entry.controls != null) for (final String ref : entry.controls) out.add(
            controls.get(ref)
                .copy());
        if (entry.coilFromSpecialValue && specialValue > 0) for (final Web.Control c : out) {
            if (!c.id.equals("heatingCoil")) continue;
            Coil coil = coils.get(coils.size() - 1);
            for (final Coil k : coils) if (k.heat >= specialValue) {
                coil = k;
                break;
            }
            c.minimumKey = coil.key;
            c.defaultKey = coil.key;
        }
        return out;
    }

    /**
     * The recipe's machine handlers, from the map's templates; null where the website's recipes carry none (it then
     * falls back to a machine named for the map). The minimum tier is the higher of the template's and the recipe's.
     */
    @Nullable
    public static List<Web.Handler> handlers(final MapEntry entry, final String recipeMinimumTier,
        final double durationTicks, final double eut, final List<Web.Control> recipeControls) {
        if (!entry.handlersOnRecipes || entry.handlers == null) return null;
        final int recipeTier = tierIndex(recipeMinimumTier);
        final List<Web.Handler> out = new ArrayList<>();
        for (final Template t : entry.handlers) {
            final int index = Math.max(tierIndex(t.minimumTier), recipeTier);
            final Web.Handler h = new Web.Handler();
            h.id = t.id;
            h.label = t.label;
            h.kind = t.kind;
            h.machineType = t.label;
            h.minimumTier = index >= 0 ? tierNames.get(index) : recipeMinimumTier;
            h.maximumTier = t.maximumTier;
            h.availableTiers = t.availableTiers;
            if (t.durationMultiplier != null)
                h.durationTicks = Math.max(1, Js.round(durationTicks * t.durationMultiplier));
            if (t.eutMultiplier != null) h.eut = Math.max(0, Js.round(eut * t.eutMultiplier * 100) / 100);
            if (t.durationTicks != null) h.durationTicks = Math.max(1, Js.round(t.durationTicks));
            if (t.eut != null) h.eut = Math.max(0, t.eut);
            if (t.perfectOverclock) h.perfectOverclock = true;
            if (t.controls != null) {
                final List<Web.Control> own = new ArrayList<>();
                final Set<String> ownIds = new HashSet<>();
                for (final String ref : t.controls) {
                    final Web.Control c = controls.get(ref)
                        .copy();
                    own.add(c);
                    ownIds.add(c.id);
                }
                for (final Web.Control c : recipeControls) if (!ownIds.contains(c.id)) own.add(c);
                h.machineConfigControls = own;
            }
            out.add(h);
        }
        return out;
    }

    /** The tier's index, any case, ignoring spaces around it; -1 for anything else. */
    private static int tierIndex(@Nullable final String tier) {
        if (tier == null) return -1;
        for (int i = 0; i < tierNames.size(); i++) if (tierNames.get(i)
            .equalsIgnoreCase(tier.trim())) return i;
        return -1;
    }

    private static synchronized void load() {
        if (maps != null) return;
        final Map<String, MapEntry> byId = new HashMap<>();
        final Map<String, Web.Control> byRef = new HashMap<>();
        final List<Coil> coilList = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final List<String> recipeNames = new ArrayList<>();
        final List<Double> recipeVoltages = new ArrayList<>();
        try (InputStream in = HandlerData.class.getResourceAsStream("/assets/gtnhplanner/machines/handlers.json")) {
            if (in != null) {
                final Gson gson = new Gson();
                final JsonObject root = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
                for (final JsonElement e : root.getAsJsonArray("tiers")) names.add(e.getAsString());
                if (root.has("recipeTiers")) for (final JsonElement e : root.getAsJsonArray("recipeTiers")) {
                    recipeNames.add(
                        e.getAsJsonArray()
                            .get(0)
                            .getAsString());
                    recipeVoltages.add(
                        e.getAsJsonArray()
                            .get(1)
                            .getAsDouble());
                }
                for (final JsonElement e : root.getAsJsonArray("coils")) {
                    final JsonObject c = e.getAsJsonObject();
                    coilList.add(
                        new Coil(
                            c.get("key")
                                .getAsString(),
                            c.get("label")
                                .getAsString(),
                            c.get("heat")
                                .getAsDouble()));
                }
                for (final Map.Entry<String, JsonElement> e : root.getAsJsonObject("controls")
                    .entrySet()) byRef.put(e.getKey(), control(gson.fromJson(e.getValue(), CompactControl.class)));
                final JsonArray list = root.getAsJsonArray("maps");
                for (final JsonElement e : list) {
                    final MapEntry m = gson.fromJson(e, MapEntry.class);
                    byId.put(m.id, m);
                }
            }
        } catch (final Exception ignored) {
            // Without the file every recipe falls back to a machine named for its map, as the website's would.
        }
        tierNames = names.isEmpty() ? List.of(Tiers.NAMES) : names;
        if (recipeNames.isEmpty()) for (int i = 0; i < Tiers.NAMES.length - 1; i++) {
            recipeNames.add(Tiers.NAMES[i]);
            recipeVoltages.add(Tiers.MAX_EUT[i]);
        }
        recipeTierNames = recipeNames;
        recipeTierVoltages = recipeVoltages;
        coils = coilList;
        controls = byRef;
        maps = byId;
    }

    private static Web.Control control(final CompactControl c) {
        final Web.Control out = new Web.Control();
        out.id = c.id;
        out.label = c.label;
        out.minimumKey = c.minimumKey;
        out.defaultKey = c.defaultKey;
        out.numeric = c.numeric;
        out.minimumFromSpecialValue = c.minimumFromSpecialValue;
        out.minimumHeatFromSpecialValue = c.minimumHeatFromSpecialValue;
        out.tiers = new ArrayList<>();
        if (c.tiers != null) for (final CompactOption o : c.tiers) {
            final Web.TierOption t = new Web.TierOption();
            t.key = o.key;
            t.label = o.label;
            t.heat = o.heat;
            t.durationMultiplier = o.durationMultiplier;
            t.eutMultiplier = o.eutMultiplier;
            t.outputMultiplier = o.outputMultiplier;
            t.parallelMultiplier = o.parallelMultiplier;
            t.parallelPerVoltageTier = o.parallelPerVoltageTier;
            t.parallelVoltageBase = o.parallelVoltageBase;
            if (o.icon != null || o.name != null) {
                final Web.Resource r = new Web.Resource();
                r.kind = "item";
                r.id = o.icon;
                r.amount = 1;
                r.displayName = o.name;
                r.consumed = false;
                t.resource = r;
            }
            out.tiers.add(t);
        }
        return out;
    }
}
