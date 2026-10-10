package com.gtnhplanner.importer;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnhplanner.importer.FfPlan.FfEdge;
import com.gtnhplanner.importer.FfPlan.FfHandler;
import com.gtnhplanner.importer.FfPlan.FfNode;
import com.gtnhplanner.importer.FfPlan.FfNote;
import com.gtnhplanner.importer.FfPlan.FfRecipe;
import com.gtnhplanner.importer.FfPlan.FfSection;
import com.gtnhplanner.importer.FfPlan.FfSlot;
import com.gtnhplanner.importer.FfPlan.FfStorage;
import com.gtnhplanner.importer.FfPlan.FfTarget;

/**
 * Reads FF's plan JSON (FF's types.ts and schemas.ts) leniently: unknown keys are ignored, a missing field takes
 * FF's default, and an entry too broken to use is skipped rather than failing the plan. Then does the parts of FF's
 * load funnel (project-normalize.ts) that change what the plan means: pool mode implies solve mode, the old "OpV"
 * tier is UXV, legacy trash cans become trash drawers, and boards are flattened (flatten-boards.ts).
 */
public final class FfPlanParser {

    private static final Gson GSON = new Gson();
    /** FF's size for a new text note (board-grid.ts), for a note whose size is missing. */
    private static final double NOTE_WIDTH = 240, NOTE_HEIGHT = 80;
    /** FF's annotation kinds other than text; an unknown kind is counted as a drawing. */
    private static final List<String> DRAWINGS = List.of("box", "arrow", "zone", "image");

    private FfPlanParser() {}

    /** The plan in FF's JSON. */
    public static FfPlan parse(final String json) {
        final JsonObject root;
        try {
            root = GSON.fromJson(json, JsonObject.class);
        } catch (final RuntimeException e) {
            throw new FfImportException("That is not a plan: the JSON does not read.", e);
        }
        if (root == null) throw new FfImportException("That is not a plan: it is empty.");
        if (!root.has("recipes") || !root.has("nodes"))
            throw new FfImportException("That is not a plan: it has no recipes or cards.");
        return read(root);
    }

    static FfPlan read(final JsonObject root) {
        final List<String> notes = new ArrayList<>();
        final Double schema = num(root, "schemaVersion");
        if (schema != null && schema != 1) notes.add("Plan format " + fmt(schema) + " read as format 1.");

        final boolean pool = bool(root, "poolMode", false);
        final boolean solve = bool(root, "solveMode", false) || pool;

        final Map<String, Board> boards = new HashMap<>();
        for (final JsonObject p : objects(root, "pockets")) {
            final String id = str(p, "id");
            if (id == null) continue;
            final double[] at = position(p);
            boards.put(id, new Board(str(p, "parentPocketId"), at[0], at[1], p.has("size")));
        }
        if (!boards.isEmpty())
            notes.add(boards.size() + " board" + (boards.size() == 1 ? "" : "s") + " flattened onto one board.");

        final List<FfRecipe> recipes = new ArrayList<>();
        for (final JsonObject r : objects(root, "recipes")) {
            final FfRecipe recipe = recipe(r);
            if (recipe != null) recipes.add(recipe);
        }
        final List<FfNode> nodes = new ArrayList<>();
        for (final JsonObject n : objects(root, "nodes")) {
            final FfNode node = node(n, boards);
            if (node != null) nodes.add(node);
        }
        final List<FfEdge> edges = new ArrayList<>();
        for (final JsonObject e : objects(root, "edges")) {
            final FfEdge edge = edge(e);
            if (edge != null) edges.add(edge);
        }
        final List<FfStorage> storages = new ArrayList<>();
        for (final JsonObject s : objects(root, "storages")) {
            final FfStorage storage = storage(s, boards);
            if (storage != null) storages.add(storage);
        }

        // Text annotations are the board's notes; FF's other drawings have no counterpart.
        final List<FfNote> textNotes = new ArrayList<>();
        final Map<String, Integer> drawings = new LinkedHashMap<>();
        for (final JsonObject a : objects(root, "annotations")) {
            final String kind = str(a, "kind", "");
            if ("text".equals(kind)) textNotes.add(note(a, boards));
            else drawings.merge(DRAWINGS.contains(kind) ? kind : "", 1, Integer::sum);
        }
        if (!drawings.isEmpty()) notes.add(drawings(drawings) + " on the board left out; only text notes come over.");
        if (pool && (root.has("productionGroups") || root.has("poolResourceRules")))
            notes.add("Pool scopes (production groups and share/import rules) left out; every resource is one pool.");

        final JsonObject target = obj(root, "targetRate");
        final FfTarget targetRate = target == null ? null : target(target);

        final String name = str(root, "name", "Imported plan");
        final FfPlan plan = new FfPlan(
            name,
            solve,
            pool,
            recipes,
            nodes,
            edges,
            storages,
            textNotes,
            targetRate,
            notes);
        return migrateTrashCans(plan);
    }

    // region Entries

    @Nullable
    private static FfRecipe recipe(final JsonObject r) {
        final String id = str(r, "id");
        if (id == null) return null;
        final JsonObject meta = obj(r, "metadata");
        final JsonObject source = obj(r, "source");
        Double special = num(r, "specialValue");
        if (special == null && meta != null) special = num(meta, "specialValue");
        final List<FfSlot> inputs = new ArrayList<>();
        for (final JsonObject s : objects(r, "inputs")) {
            final FfSlot slot = slot(s, true);
            if (slot != null) inputs.add(slot);
        }
        final List<FfSlot> outputs = new ArrayList<>();
        for (final JsonObject s : objects(r, "outputs")) {
            final FfSlot slot = slot(s, false);
            if (slot != null) outputs.add(slot);
        }
        final List<FfHandler> handlers = new ArrayList<>();
        for (final JsonObject h : objects(r, "machineHandlers")) {
            final String hid = str(h, "id");
            if (hid == null) continue;
            handlers.add(
                new FfHandler(
                    hid,
                    str(h, "label", str(h, "machineType", hid)),
                    str(h, "kind", ""),
                    bool(h, "perfectOverclock", false)));
        }
        final Double duration = num(r, "durationTicks");
        final Double eut = num(r, "eut");
        return new FfRecipe(
            id,
            str(r, "name", id),
            str(r, "kind", ""),
            str(r, "category", ""),
            str(r, "machineType", ""),
            str(r, "minimumTier", ""),
            duration == null ? 0 : (int) Math.round(duration),
            eut == null ? 0 : eut,
            special,
            str(r, "programmedCircuit"),
            inputs,
            outputs,
            handlers,
            meta == null ? null : str(meta, "recipeMapId"),
            source == null ? null : str(source, "rawRecipeId"),
            obj(r, "power") == null ? null : str(obj(r, "power"), "sourceId", ""));
    }

    @Nullable
    private static FfSlot slot(final JsonObject s, final boolean input) {
        final String id = str(s, "id");
        if (id == null || id.isEmpty()) return null;
        final Double amount = num(s, "amount");
        final Double chance = num(s, "chance");
        final List<String> alternatives = new ArrayList<>();
        for (final JsonObject a : objects(s, "alternatives")) {
            final String alt = str(a, "id");
            if (alt != null && !alt.isEmpty()) alternatives.add(alt);
        }
        return new FfSlot(
            str(s, "kind", "item"),
            id,
            amount == null || amount <= 0 ? 1 : amount,
            chance == null ? 1 : chance,
            !input || bool(s, "consumed", true),
            alternatives,
            str(s, "displayName", id));
    }

    @Nullable
    private static FfNode node(final JsonObject n, final Map<String, Board> boards) {
        final String id = str(n, "id"), recipeId = str(n, "recipeId");
        if (id == null || recipeId == null) return null;
        final double[] at = absolute(position(n), str(n, "pocketId"), boards);
        final Double count = num(n, "machineCount");
        final Double parallel = num(n, "parallel");
        final Double hatches = num(n, "energyHatches");
        String tier = str(n, "overclockTier", "");
        if ("OpV".equals(tier)) tier = "UXV";

        final Map<String, String> configTiers = new LinkedHashMap<>();
        final JsonObject config = obj(n, "machineConfigTiers");
        if (config != null) for (final Map.Entry<String, JsonElement> e : config.entrySet()) {
            final String v = asString(e.getValue());
            if (v != null) configTiers.put(e.getKey(), v);
        }
        final List<FfSection> extras = new ArrayList<>();
        for (final JsonObject x : objects(n, "extraRecipes")) {
            final String rid = str(x, "recipeId");
            if (rid != null) extras.add(new FfSection(rid, overrides(x)));
        }
        final JsonObject targetJson = obj(n, "targetOutput");
        final JsonObject custom = obj(n, "customRate");
        final List<String> supplies = new ArrayList<>();
        for (final JsonElement e : array(n, "hatchSupplies")) {
            final String v = asString(e);
            if (v != null) supplies.add(v);
        }
        return new FfNode(
            id,
            recipeId,
            count == null ? 1 : count,
            parallel == null ? 1 : (int) Math.max(1, Math.round(parallel)),
            tier,
            hatches == null ? 0 : (int) Math.round(hatches),
            str(n, "energyHatchType"),
            num(n, "powerEuT"),
            str(n, "hatchVoltageTier"),
            num(n, "hatchAmps"),
            str(n, "powerInputMode"),
            str(n, "machineHandlerId"),
            str(n, "coilTier"),
            configTiers,
            overrides(n),
            extras,
            num(n, "solvePin"),
            targetJson == null ? null : target(targetJson),
            bool(n, "enabled", true),
            at[0],
            at[1],
            custom == null ? null : num(custom, "perSecond"),
            custom == null ? null : str(custom, "mode"),
            supplies);
    }

    private static Map<Integer, FfSlot> overrides(final JsonObject holder) {
        final Map<Integer, FfSlot> out = new LinkedHashMap<>();
        final JsonObject o = obj(holder, "recipeInputOverrides");
        if (o == null) return out;
        for (final Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (!e.getValue()
                .isJsonObject()) continue;
            final int slot;
            try {
                slot = Integer.parseInt(e.getKey());
            } catch (final NumberFormatException ignored) {
                continue;
            }
            final FfSlot s = slot(
                e.getValue()
                    .getAsJsonObject(),
                true);
            if (s != null) out.put(slot, s);
        }
        return out;
    }

    @Nullable
    private static FfTarget target(final JsonObject t) {
        final String id = str(t, "resourceId");
        final Double rate = num(t, "amountPerSecond");
        if (id == null || rate == null) return null;
        return new FfTarget(str(t, "kind", "item"), id, rate);
    }

    @Nullable
    private static FfEdge edge(final JsonObject e) {
        final String id = str(e, "id", ""), source = str(e, "source"), target = str(e, "target");
        if (source == null || target == null) return null;
        final String sourceHandle = str(e, "sourceHandle"), targetHandle = str(e, "targetHandle");
        String kind = str(e, "resourceKind");
        String resource = str(e, "resourceId");
        if (kind == null || resource == null) {
            // Very old wires: the handle still names the resource.
            final FfHandle h = FfHandle.parse(sourceHandle != null ? sourceHandle : targetHandle);
            if (h == null) return null;
            if (kind == null) kind = h.kind();
            if (resource == null) resource = h.resourceId();
        }
        return new FfEdge(id, source, target, sourceHandle, targetHandle, kind, resource, obj(e, "crossForm") != null);
    }

    @Nullable
    private static FfStorage storage(final JsonObject s, final Map<String, Board> boards) {
        final String id = str(s, "id"), resource = str(s, "resourceId");
        if (id == null || resource == null) return null;
        final double[] at = absolute(position(s), str(s, "pocketId"), boards);
        return new FfStorage(
            id,
            str(s, "kind", "item"),
            resource,
            str(s, "displayName"),
            str(s, "drainMode"),
            str(s, "bufferMode"),
            num(s, "targetPerSecond"),
            str(s, "targetMode"),
            str(s, "poolTargetMode"),
            str(s, "poolSide"),
            at[0],
            at[1]);
    }

    /** A text note. It needs nothing else on the plan, so it is always kept: no id, no size or no text still read. */
    private static FfNote note(final JsonObject a, final Map<String, Board> boards) {
        final double[] at = absolute(position(a), str(a, "pocketId"), boards);
        final JsonObject size = obj(a, "size");
        final Double width = size == null ? null : num(size, "width");
        final Double height = size == null ? null : num(size, "height");
        final Double font = num(a, "fontSize");
        return new FfNote(
            str(a, "id", ""),
            at[0],
            at[1],
            width == null ? NOTE_WIDTH : width,
            height == null ? NOTE_HEIGHT : height,
            str(a, "colorTag"),
            str(a, "text", ""),
            font == null ? null : (int) Math.round(font));
    }

    /** FF's other drawings, counted by kind: "2 boxes and 1 arrow". */
    private static String drawings(final Map<String, Integer> byKind) {
        final List<String> parts = new ArrayList<>();
        for (final Map.Entry<String, Integer> e : byKind.entrySet()) {
            final int n = e.getValue();
            final String what = switch (e.getKey()) {
                case "box" -> n == 1 ? "box" : "boxes";
                case "arrow" -> n == 1 ? "arrow" : "arrows";
                case "zone" -> n == 1 ? "zone" : "zones";
                case "image" -> n == 1 ? "picture" : "pictures";
                default -> n == 1 ? "drawing" : "drawings";
            };
            parts.add(n + " " + what);
        }
        if (parts.size() == 1) return parts.getFirst();
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.getLast();
    }

    // endregion

    // region Load funnel

    /** A board frame; a board that has a size holds frame-relative member positions (FF's flatten-boards.ts). */
    private record Board(@Nullable String parent, double x, double y, boolean sized) {}

    private static double[] absolute(final double[] at, @Nullable final String boardId,
        final Map<String, Board> boards) {
        double x = at[0], y = at[1];
        final Set<String> seen = new HashSet<>();
        for (String id = boardId; id != null && seen.add(id);) {
            final Board board = boards.get(id);
            if (board == null) break;
            if (board.sized()) {
                x += board.x();
                y += board.y();
            }
            id = board.parent();
        }
        return new double[] { x, y };
    }

    /**
     * FF's migrateTrashCansToDrawers: every wire into a legacy trash can becomes a wire into a trash drawer of that
     * wire's resource, one drawer per resource per can; the cans and their recipes go.
     */
    private static FfPlan migrateTrashCans(final FfPlan plan) {
        final Set<String> trashRecipes = new HashSet<>();
        for (final FfRecipe r : plan.recipes()) if (r.isTrashCan()) trashRecipes.add(r.id());
        if (trashRecipes.isEmpty()) return plan;
        final Map<String, FfRecipe> recipes = plan.recipesById();
        final Map<String, FfNode> nodes = new HashMap<>();
        for (final FfNode n : plan.nodes()) nodes.put(n.id(), n);
        final List<FfStorage> storages = new ArrayList<>(plan.storages());
        final List<FfEdge> edges = new ArrayList<>(plan.edges());
        final Set<String> cans = new HashSet<>();
        for (final FfNode can : plan.nodes()) {
            if (!trashRecipes.contains(can.recipeId())) continue;
            cans.add(can.id());
            final Map<String, String> drawerByResource = new HashMap<>();
            for (int i = 0; i < edges.size(); i++) {
                final FfEdge e = edges.get(i);
                if (!e.target()
                    .equals(can.id())) continue;
                final String key = e.resourceKind() + ":" + e.resourceId();
                String drawerId = drawerByResource.get(key);
                if (drawerId == null) {
                    drawerId = "trash-" + can.id() + "-" + drawerByResource.size();
                    String name = null;
                    final FfNode feeder = nodes.get(e.source());
                    final FfRecipe feederRecipe = feeder == null ? null : recipes.get(feeder.recipeId());
                    if (feederRecipe != null) for (final FfSlot out : feederRecipe.outputs()) {
                        if (out.kind()
                            .equals(e.resourceKind())
                            && out.id()
                                .equals(e.resourceId())) {
                            name = out.displayName();
                            break;
                        }
                    }
                    storages.add(
                        new FfStorage(
                            drawerId,
                            e.resourceKind(),
                            e.resourceId(),
                            name,
                            "trash",
                            null,
                            null,
                            null,
                            null,
                            null,
                            can.x(),
                            can.y() + drawerByResource.size() * 100));
                    drawerByResource.put(key, drawerId);
                }
                edges.set(
                    i,
                    new FfEdge(
                        e.id(),
                        e.source(),
                        drawerId,
                        e.sourceHandle(),
                        "input:" + e.resourceKind() + ":" + encode(e.resourceId()),
                        e.resourceKind(),
                        e.resourceId(),
                        e.crossForm()));
            }
        }
        final List<FfNode> keptNodes = new ArrayList<>();
        for (final FfNode n : plan.nodes()) if (!cans.contains(n.id())) keptNodes.add(n);
        edges.removeIf(e -> cans.contains(e.source()) || cans.contains(e.target()));
        final List<FfRecipe> keptRecipes = new ArrayList<>();
        for (final FfRecipe r : plan.recipes()) if (!r.isTrashCan()) keptRecipes.add(r);
        final List<String> notes = new ArrayList<>(plan.notes());
        if (!cans.isEmpty()) notes.add(
            cans.size() + " old trash can" + (cans.size() == 1 ? "" : "s") + " turned into trash drawers, as FF does.");
        return new FfPlan(
            plan.name(),
            plan.solveMode(),
            plan.poolMode(),
            keptRecipes,
            keptNodes,
            edges,
            storages,
            plan.textNotes(),
            plan.targetRate(),
            notes);
    }

    private static String encode(final String id) {
        try {
            return URLEncoder.encode(id, "UTF-8")
                .replace("+", "%20");
        } catch (final UnsupportedEncodingException e) {
            return id;
        }
    }

    // endregion

    // region Lenient JSON

    private static double[] position(final JsonObject o) {
        final JsonObject p = obj(o, "position");
        if (p == null) return new double[] { 0, 0 };
        final Double x = num(p, "x"), y = num(p, "y");
        return new double[] { x == null ? 0 : x, y == null ? 0 : y };
    }

    @Nullable
    private static JsonObject obj(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static List<JsonElement> array(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        final List<JsonElement> out = new ArrayList<>();
        if (e == null || !e.isJsonArray()) return out;
        final JsonArray a = e.getAsJsonArray();
        for (final JsonElement x : a) if (x != null && !x.isJsonNull()) out.add(x);
        return out;
    }

    private static List<JsonObject> objects(final JsonObject o, final String key) {
        final List<JsonObject> out = new ArrayList<>();
        for (final JsonElement e : array(o, key)) if (e.isJsonObject()) out.add(e.getAsJsonObject());
        return out;
    }

    @Nullable
    private static String asString(@Nullable final JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) return null;
        return e.getAsString();
    }

    @Nullable
    private static String str(final JsonObject o, final String key) {
        return asString(o.get(key));
    }

    private static String str(final JsonObject o, final String key, final String fallback) {
        final String s = str(o, key);
        return s == null ? fallback : s;
    }

    @Nullable
    private static Double num(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return null;
        final JsonPrimitive p = e.getAsJsonPrimitive();
        try {
            if (p.isNumber() || p.isString()) {
                final double d = p.getAsDouble();
                return Double.isFinite(d) ? d : null;
            }
        } catch (final NumberFormatException ignored) {}
        return null;
    }

    private static boolean bool(final JsonObject o, final String key, final boolean fallback) {
        final JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        final JsonPrimitive p = e.getAsJsonPrimitive();
        return p.isBoolean() ? p.getAsBoolean() : fallback;
    }

    private static String fmt(final double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    // endregion
}
