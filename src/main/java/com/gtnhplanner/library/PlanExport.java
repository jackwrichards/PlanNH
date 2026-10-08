package com.gtnhplanner.library;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.properties.RecipeProperty;
import com.gtnhplanner.power.Energy;
import com.gtnhplanner.power.PowerModel;
import com.gtnhplanner.power.PowerRegistry;
import com.gtnhplanner.power.PowerSource;

/**
 * A plan as Factory Flow's project JSON (its {@code factoryProjectSchema}, version 1), for posting to the library:
 * each card's recipe written out in full (Factory Flow plans carry their recipes), the cards, the drawers and the
 * wires, in Solve mode, laid out at Factory Flow's scale. The game's side (ids, names, solved counts) comes in
 * through {@link World}, so this stays free of Minecraft and can be tested on its own.
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

    private PlanExport() {}

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
        final JsonObject p = new JsonObject();
        p.addProperty("schemaVersion", 1);
        p.addProperty(
            "id",
            UUID.randomUUID()
                .toString());
        p.addProperty("name", name);
        p.addProperty("solveMode", true);
        p.add("recipes", recipes);
        p.add("nodes", nodes);
        p.add("storages", storages);
        p.add("annotations", new JsonArray());
        p.add("pockets", new JsonArray());
        p.add("edges", edges);
        p.add("fuelProfiles", new JsonArray());
        final JsonObject meta = new JsonObject();
        meta.addProperty("source", "GTNH Planner");
        p.add("metadata", meta);
        return p;
    }

    private static JsonObject recipe(final Node n, final String id, final World world, final Map<Port<?>, Res> res) {
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

    private static JsonArray ports(final List<Port<?>> ports, final boolean outputs, final World world,
        final Map<Port<?>, Res> res) {
        final JsonArray out = new JsonArray();
        for (final Port<?> p : ports) {
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
        o.addProperty("overclockTier", n.isPower() ? "LV" : world.tier(n));
        o.addProperty("enabled", true);
        if (n.isPower()) {
            // A power card's settings, where the site keeps them.
            final JsonObject settings = new JsonObject();
            for (final Map.Entry<String, String> s : n.powerSettings.entrySet())
                settings.addProperty(s.getKey(), s.getValue());
            o.add("machineConfigTiers", settings);
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
