package com.gtnhplanner.library;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.properties.RecipeProperty;
import com.gtnhplanner.power.CustomRate;
import com.gtnhplanner.power.Energy;
import com.gtnhplanner.power.PowerModel;
import com.gtnhplanner.power.PowerRegistry;
import com.gtnhplanner.power.PowerSource;

/**
 * A plan as Factory Flow's project JSON (its {@code factoryProjectSchema}, version 1), for posting to the library:
 * each card's recipe written out in full (Factory Flow plans carry their recipes), the cards, the drawers, the wires
 * and the notes (its text annotations), in Solve mode, laid out at Factory Flow's scale. The game's side (ids, names,
 * solved counts) comes in through {@link World}, so this stays free of Minecraft and can be tested on its own.
 */
public final class PlanExport {

    /** One item or fluid as Factory Flow names it: {@code item} or {@code fluid}, its id, and its name. */
    public record Res(String kind, String id, String name) {}

    /** EU, as the site names it: a generator's output and an EU drawer's resource. */
    public static final Res EU = new Res("power", "eu", "EU");

    /** What the export needs from the game. */
    public interface World {

        /** A port's item or fluid, or null to leave it out (nothing the site could name). */
        @Nullable
        Res port(Port<?> port);

        /** A drawer's item or fluid, from its resource key. */
        @Nullable
        Res resource(String key);

        /** The machine a card runs on, by name. */
        String machine(Node node);

        /** How many machines the plan runs the card at (the solve's answer), 0 when unknown. */
        double machines(Node node);

        /** The card's tier name ("LV", "HV"...). */
        String tier(Node node);
    }

    /** Factory Flow's cards are 380 wide to our 320, and taller (as the importer scales them the other way). */
    private static final double SCALE_X = 380.0 / 320.0, SCALE_Y = 1 / 0.6;
    /** Factory Flow's board cell: it grows every drawing's size to whole cells when it loads a plan. */
    private static final int CELL = 20;
    /** The colour tags the site's schema takes (its factoryNodeColorTagSchema); a note in any other goes untagged. */
    private static final Set<String> COLOR_TAGS = Set.of(
        "white",
        "orange",
        "magenta",
        "light_blue",
        "yellow",
        "lime",
        "pink",
        "gray",
        "light_gray",
        "cyan",
        "purple",
        "blue",
        "brown",
        "green",
        "red",
        "black",
        "scarlet",
        "amber",
        "emerald",
        "azure",
        "steel",
        "onyx");

    private PlanExport() {}

    /** An item or fluid as the face of a plan or a post: the site's {@code EntryIcon}, without its picture. */
    public static JsonObject icon(final Res r) {
        final JsonObject o = new JsonObject();
        o.addProperty("kind", r.kind());
        o.addProperty("resourceId", r.id());
        if (!r.name()
            .isEmpty()) o.addProperty("displayName", r.name());
        return o;
    }

    public static JsonObject project(final Graph g, final String name, final World world) {
        final JsonArray recipes = new JsonArray(), nodes = new JsonArray(), storages = new JsonArray(),
            edges = new JsonArray();
        // Each port's resource, once: edges and drawers name them as the recipes do.
        final Map<Port<?>, Res> res = new HashMap<>();
        for (final Node n : g.getNodes()) {
            final String recipeId = "gtnhplanner:" + n.id;
            recipes.add(recipe(n, recipeId, world, res));
            nodes.add(node(n, recipeId, world));
        }
        for (final Edge e : g.getEdges()) {
            final Node from = g.nodes.get(e.sourceNodeId), to = g.nodes.get(e.targetNodeId);
            if (from == null || to == null || e.sourceOutputIndex >= from.outputs.size()) continue;
            final Res r = res.get(from.outputs.get(e.sourceOutputIndex));
            if (r == null) continue;
            edges.add(edge(e.id.toString(), from.id.toString(), to.id.toString(), r));
        }
        for (final Drawer d : g.getDrawers()) {
            final Res r = Energy.KEY.equals(d.getResourceKey()) ? EU : world.resource(d.getResourceKey());
            if (r == null) continue;
            final boolean source = d.getKind()
                .linksInputs();
            storages.add(storage(d, r));
            for (final Drawer.Link link : d.getLinks()) {
                final Node n = g.nodes.get(link.nodeId());
                if (n == null) continue;
                final String id = d.getId() + ":" + link.nodeId() + ":" + link.portIndex();
                edges.add(
                    source ? edge(
                        id,
                        d.getId()
                            .toString(),
                        n.id.toString(),
                        r)
                        : edge(
                            id,
                            n.id.toString(),
                            d.getId()
                                .toString(),
                            r));
            }
        }
        final JsonArray annotations = new JsonArray();
        for (final Note n : g.getNotes()) annotations.add(note(n));
        final JsonObject p = new JsonObject();
        p.addProperty("schemaVersion", 1);
        p.addProperty(
            "id",
            UUID.randomUUID()
                .toString());
        p.addProperty("name", name);
        // The plan's face, which the website's plan card shows: its description and icon.
        if (!g.getDescription()
            .isEmpty()) p.addProperty("description", g.getDescription());
        final Res face = g.getIcon() == null ? null : world.resource(g.getIcon());
        if (face != null) p.add("icon", icon(face));
        p.addProperty("solveMode", true);
        p.add("recipes", recipes);
        p.add("nodes", nodes);
        p.add("storages", storages);
        p.add("annotations", annotations);
        p.add("pockets", new JsonArray());
        p.add("edges", edges);
        p.add("fuelProfiles", new JsonArray());
        final JsonObject meta = new JsonObject();
        meta.addProperty("source", "GTNH Planner");
        p.add("metadata", meta);
        return p;
    }

    private static JsonObject recipe(final Node n, final String id, final World world, final Map<Port<?>, Res> res) {
        if (CustomRate.is(n)) return customRateRecipe(n, id, world, res);
        if (n.isPower()) return powerRecipe(n, id, world, res);
        final JsonObject r = new JsonObject();
        r.addProperty("id", id);
        final String machine = world.machine(n);
        r.addProperty(
            "name",
            n.outputs.isEmpty() ? machine
                : n.outputs.get(0)
                    .getDisplayName());
        r.addProperty("kind", "gregtech_machine");
        r.addProperty("machineType", machine);
        r.addProperty("minimumTier", world.tier(n));
        r.addProperty("durationTicks", Math.max(1, intProperty(n, "duration_ticks")));
        r.addProperty("eut", Math.max(0, longProperty(n, "gt.eu_per_tick")));
        r.add("inputs", ports(n.inputs, false, world, res));
        r.add("outputs", ports(n.outputs, true, world, res));
        final JsonObject source = new JsonObject();
        source.addProperty("exporter", "unknown");
        source.addProperty("sourceMod", "GTNH Planner");
        r.add("source", source);
        return r;
    }

    /**
     * A generator, as the site's power cards carry it (buildPowerRecipe): a one-second custom recipe, flows per second
     * exact, EU first among the outputs, and the source with its readings; the site rebuilds it from the source id and
     * the node's settings when it loads the plan.
     */
    private static JsonObject powerRecipe(final Node n, final String id, final World world,
        final Map<Port<?>, Res> res) {
        final JsonObject r = new JsonObject();
        final PowerSource source = PowerRegistry.get(n.powerSource);
        final String name = source != null ? source.name() : n.machineName;
        r.addProperty("id", id);
        r.addProperty("name", name);
        r.addProperty("kind", "custom");
        r.addProperty("category", "power-source");
        r.addProperty("machineType", name);
        r.addProperty("minimumTier", "NONE");
        r.addProperty("durationTicks", 20);
        r.addProperty("eut", 0);
        r.add("inputs", ports(n.inputs, false, world, res));
        r.add("outputs", ports(n.outputs, true, world, res));
        if (source != null) r.addProperty("notes", source.blurb());
        final JsonObject from = new JsonObject();
        from.addProperty("recipeMap", "power-source");
        r.add("source", from);
        final JsonObject power = new JsonObject();
        power.addProperty("sourceId", n.powerSource);
        final PowerModel model = n.powerModel;
        power.addProperty("euPerTick", model == null ? 0 : model.euPerTick());
        final JsonArray stats = new JsonArray();
        if (model != null) for (final PowerModel.Stat s : model.stats()) {
            final JsonObject line = new JsonObject();
            line.addProperty("label", s.label());
            line.addProperty("value", s.value());
            stats.add(line);
        }
        power.add("stats", stats);
        if (model != null && !model.warnings()
            .isEmpty()) {
            final JsonArray warnings = new JsonArray();
            for (final String w : model.warnings()) warnings.add(new com.google.gson.JsonPrimitive(w));
            power.add("warnings", warnings);
        }
        r.add("power", power);
        return r;
    }

    /**
     * A custom rate card, as the site's own (custom-rate.ts): a one-second recipe of its one slot, the amount its rate
     * a second, named after what it holds; the dial rides on the node. The site wants an amount above nothing, so a
     * dial at zero posts the least it takes.
     */
    private static JsonObject customRateRecipe(final Node n, final String id, final World world,
        final Map<Port<?>, Res> res) {
        final JsonObject r = new JsonObject();
        final JsonArray inputs = ports(n.inputs, false, world, res), outputs = ports(n.outputs, true, world, res);
        for (final JsonArray side : List.of(inputs, outputs)) for (final com.google.gson.JsonElement e : side) {
            final JsonObject slot = e.getAsJsonObject();
            slot.remove("byproduct");
            if (!(slot.get("amount")
                .getAsDouble() > 0)) slot.addProperty("amount", 0.001);
        }
        final com.google.gson.JsonElement held = outputs.size() > 0 ? outputs.get(0)
            : inputs.size() > 0 ? inputs.get(0) : null;
        r.addProperty("id", id);
        r.addProperty("name", held == null ? "Custom Rate" : "Custom Rate: " + slotName(held.getAsJsonObject()));
        r.addProperty("kind", "custom");
        r.addProperty("category", "custom-rate");
        r.addProperty("machineType", "Custom Rate");
        r.addProperty("minimumTier", "NONE");
        r.addProperty("durationTicks", 20);
        r.addProperty("eut", 0);
        r.add("inputs", inputs);
        r.add("outputs", outputs);
        r.addProperty("notes", "Wire any port to this and it adopts that resource.");
        final JsonObject from = new JsonObject();
        from.addProperty("recipeMap", "custom-rate");
        r.add("source", from);
        return r;
    }

    private static JsonArray ports(final List<Port<?>> ports, final boolean outputs, final World world,
        final Map<Port<?>, Res> res) {
        final JsonArray out = new JsonArray();
        for (final Port<?> p : ports) {
            if (p.fromModel) continue;
            final Res r = p.getValue() instanceof Energy ? EU : world.port(p);
            if (r == null) continue;
            res.put(p, r);
            if (!Double.isNaN(p.getExactAmount())) {
                // A generator's exact rate per second (its craft is a second); EU rides as a byproduct, as on the site.
                final JsonObject o = resource(r);
                o.addProperty("amount", p.amount());
                if (r == EU) o.addProperty("byproduct", true);
                out.add(o);
                continue;
            }
            // The site wants an amount above nothing; a port that is not used up (a catalyst) still counts one.
            final int amount = Math.max(1, p.getAmount());
            final JsonObject o = resource(r);
            o.addProperty("amount", amount);
            if (outputs && p.getChance() > 0 && p.getChance() < 1) o.addProperty("chance", p.getChance());
            if (!outputs && p.getAmount() <= 0) o.addProperty("consumed", false);
            out.add(o);
        }
        return out;
    }

    private static JsonObject node(final Node n, final String recipeId, final World world) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", n.id.toString());
        o.addProperty("recipeId", recipeId);
        o.addProperty("machineCount", Math.max(0, world.machines(n)));
        o.addProperty("parallel", 1);
        o.addProperty("overclockTier", CustomRate.is(n) ? "NONE" : n.isPower() ? "LV" : world.tier(n));
        o.addProperty("enabled", true);
        if (CustomRate.is(n)) {
            // The dial, where the site keeps it: a second's worth, and which way it runs.
            final JsonObject dial = new JsonObject();
            dial.addProperty("perSecond", CustomRate.perSecond(n));
            dial.addProperty("mode", CustomRate.supply(n) ? CustomRate.SUPPLY : CustomRate.REQUEST);
            o.add("customRate", dial);
        } else if (n.isPower()) {
            // A power card's settings, where the site keeps them.
            final JsonObject settings = new JsonObject();
            for (final Map.Entry<String, String> s : n.powerSettings.entrySet())
                settings.addProperty(s.getKey(), s.getValue());
            o.add("machineConfigTiers", settings);
        } else if (n.machineConfig != null) {
            // A modelled machine's settings (machines/), under the website's own keys.
            final JsonObject settings = new JsonObject();
            for (final Map.Entry<String, Object> s : n.machineConfig.settings.entrySet()) {
                final boolean modelled = com.gtnhplanner.machines.TreeGrowthSimulator.SETTING_KEYS.contains(s.getKey())
                    || com.gtnhplanner.machines.BacterialVat.SETTING_KEYS.contains(s.getKey());
                if (modelled && s.getValue() instanceof final String v && !v.isEmpty()) settings.addProperty(s.getKey(), v);
            }
            if (!settings.entrySet()
                .isEmpty()) o.add("machineConfigTiers", settings);
        }
        if (n.isMachineCountFixed()) o.addProperty("solvePin", Math.max(0, world.machines(n)));
        o.add("position", position(n.x, n.y));
        return o;
    }

    private static JsonObject storage(final Drawer d, final Res r) {
        final JsonObject o = new JsonObject();
        o.addProperty(
            "id",
            d.getId()
                .toString());
        o.addProperty("kind", r.kind());
        o.addProperty("resourceId", r.id());
        o.addProperty("displayName", r.name());
        if (!d.getKind()
            .linksInputs()) {
            o.addProperty("drainMode", "product");
            final String mode = switch (d.getRule()) {
                case AT_LEAST -> "at-least";
                case AT_MOST -> "at-most";
                case EXACTLY -> "exact";
                case ANY -> null;
            };
            if (mode != null && d.getRate() > 0) {
                o.addProperty("targetPerSecond", d.getRate());
                o.addProperty("targetMode", mode);
            }
        }
        o.add("position", position(d.getX(), d.getY()));
        return o;
    }

    /**
     * A note as the site's text annotation, placed as a card is. Its size is rounded to whole cells: the site grows a
     * size to the next cell when it loads, so a size a pixel over one could gain a whole cell on a trip there and back.
     */
    private static JsonObject note(final Note n) {
        final JsonObject o = new JsonObject();
        o.addProperty(
            "id",
            n.getId()
                .toString());
        o.addProperty("kind", "text");
        if (COLOR_TAGS.contains(n.colorTag())) o.addProperty("colorTag", n.colorTag());
        o.addProperty("text", n.joined());
        o.add("position", position(n.getX(), n.getY()));
        final JsonObject size = new JsonObject();
        size.addProperty("width", cells(Math.max(Note.MIN_W, n.getWidth()) * SCALE_X));
        size.addProperty("height", cells(Math.max(Note.MIN_H, n.getHeight()) * SCALE_Y));
        o.add("size", size);
        o.addProperty("fontSize", n.fontSizeOrDefault());
        return o;
    }

    /** A length to the nearest whole number of the site's cells, at least one. */
    private static int cells(final double length) {
        return Math.max(CELL, (int) Math.round(length / CELL) * CELL);
    }

    private static JsonObject edge(final String id, final String source, final String target, final Res r) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("source", source);
        o.addProperty("target", target);
        o.addProperty("sourceHandle", "output:" + r.kind() + ":" + enc(r.id()));
        o.addProperty("targetHandle", "input:" + r.kind() + ":" + enc(r.id()));
        o.addProperty("resourceKind", r.kind());
        o.addProperty("resourceId", r.id());
        return o;
    }

    /** A slot's name as the site shows it: its display name, else its id. */
    private static String slotName(final JsonObject slot) {
        return (slot.has("displayName") ? slot.get("displayName") : slot.get("id")).getAsString();
    }

    private static JsonObject resource(final Res r) {
        final JsonObject o = new JsonObject();
        o.addProperty("kind", r.kind());
        o.addProperty("id", r.id());
        if (!r.name()
            .isEmpty()) o.addProperty("displayName", r.name());
        return o;
    }

    private static JsonObject position(final int x, final int y) {
        final JsonObject o = new JsonObject();
        o.addProperty("x", Math.round(x * SCALE_X));
        o.addProperty("y", Math.round(y * SCALE_Y));
        return o;
    }

    private static int intProperty(final Node n, final String key) {
        return (int) longProperty(n, key);
    }

    private static long longProperty(final Node n, final String key) {
        for (final Map.Entry<RecipeProperty<?>, Object> e : n.properties.entrySet()) {
            if (key.equals(
                e.getKey()
                    .getKey())
                && e.getValue() instanceof final Number num) return num.longValue();
        }
        return 0;
    }

    /** {@code encodeURIComponent}, as the website writes handles. */
    private static String enc(final String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name())
                .replace("+", "%20")
                .replace("%21", "!")
                .replace("%27", "'")
                .replace("%28", "(")
                .replace("%29", ")")
                .replace("%7E", "~");
        } catch (final java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
