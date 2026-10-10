package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.harness.GtnhFlowLoader;
import com.gtnhplanner.library.PlanExport;

/**
 * A plan posted to the library is Factory Flow's project JSON. The shape is checked here; the build writes the
 * export to {@code build/tmp/gtnhplanner-export.json} so it can also be run past the website's own schema.
 */
class PlanExportTest {

    /** The game's side, stood in for: every port names itself. */
    private static final PlanExport.World WORLD = new PlanExport.World() {

        @Override
        public PlanExport.Res port(final Port<?> port) {
            final String name = port.getDisplayName();
            return new PlanExport.Res(
                "item",
                "test:" + name.toLowerCase(Locale.ROOT)
                    .replace(' ', '_'),
                name);
        }

        @Override
        public PlanExport.Res resource(final String key) {
            return new PlanExport.Res("item", "test:" + key, key);
        }

        @Override
        public String machine(final Node node) {
            return node.machineName == null ? "Machine" : node.machineName;
        }

        @Override
        public double machines(final Node node) {
            return 2;
        }

        @Override
        public String tier(final Node node) {
            return "LV";
        }
    };

    @Test
    void aModelledMachinesSettingsGoOutUnderTheWebsitesKeys() throws IOException {
        final Graph g = GtnhFlowLoader.load("mk1")
            .graph();
        final Node first = g.getNodes()
            .iterator()
            .next();
        first.machineConfig.settings.put("tgsLogTool", "log:chainsaw");
        first.machineConfig.settings.put("bioVatFill", "49.5");
        first.machineConfig.settings.put("tgsHeight", "");
        final JsonObject p = PlanExport.project(g, "Test plan", WORLD);
        JsonObject tiers = null;
        for (final JsonElement n : p.getAsJsonArray("nodes")) if (n.getAsJsonObject()
            .get("id")
            .getAsString()
            .equals(first.id.toString()))
            tiers = n.getAsJsonObject()
                .getAsJsonObject("machineConfigTiers");
        assertEquals(
            "log:chainsaw",
            tiers.get("tgsLogTool")
                .getAsString());
        assertEquals(
            "49.5",
            tiers.get("bioVatFill")
                .getAsString());
        assertFalse(tiers.has("tgsHeight"), "a default (empty) setting stays out");
    }

    @Test
    void aPlanBecomesAFactoryFlowProject() throws IOException {
        final Graph g = GtnhFlowLoader.load("mk1")
            .graph();
        final JsonObject p = PlanExport.project(g, "Test plan", WORLD);
        assertEquals(
            1,
            p.get("schemaVersion")
                .getAsInt());
        assertTrue(
            p.get("solveMode")
                .getAsBoolean());
        assertEquals(
            g.getNodes()
                .size(),
            p.getAsJsonArray("nodes")
                .size(),
            "a node for every card");
        assertEquals(
            g.getNodes()
                .size(),
            p.getAsJsonArray("recipes")
                .size(),
            "and its recipe, carried in full");
        final Set<String> recipeIds = new HashSet<>();
        for (final JsonElement r : p.getAsJsonArray("recipes")) {
            final JsonObject o = r.getAsJsonObject();
            recipeIds.add(
                o.get("id")
                    .getAsString());
            assertTrue(
                o.get("durationTicks")
                    .getAsInt() >= 1);
            for (final JsonElement in : o.getAsJsonArray("inputs")) assertTrue(
                in.getAsJsonObject()
                    .get("amount")
                    .getAsDouble() > 0,
                "every amount above nothing");
        }
        for (final JsonElement n : p.getAsJsonArray("nodes")) {
            assertTrue(
                recipeIds.contains(
                    n.getAsJsonObject()
                        .get("recipeId")
                        .getAsString()),
                "every node's recipe is there");
        }
        assertTrue(
            p.getAsJsonArray("edges")
                .size() > 0,
            "the wires come along");
        for (final JsonElement e : p.getAsJsonArray("edges")) {
            final JsonObject o = e.getAsJsonObject();
            assertTrue(
                o.get("sourceHandle")
                    .getAsString()
                    .startsWith("output:item:"));
            assertTrue(
                o.get("targetHandle")
                    .getAsString()
                    .startsWith("input:item:"));
        }
        final Path out = Path.of("build", "tmp", "gtnhplanner-export.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, p.toString(), StandardCharsets.UTF_8);
    }

    /** The site's colour tags (factoryNodeColorTagSchema in its src/lib/model/schemas.ts). */
    private static final Set<String> SITE_COLOR_TAGS = Set.of(
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

    /** The keys of the site's factoryAnnotationSchema. */
    private static final Set<String> ANNOTATION_KEYS = Set.of(
        "id",
        "kind",
        "colorTag",
        "text",
        "position",
        "size",
        "arrowDirection",
        "points",
        "fontSize",
        "imageUrl",
        "style",
        "pocketId");

    @Test
    void thePlanWearsItsFace() throws IOException {
        final Graph g = GtnhFlowLoader.load("mk1")
            .graph();
        final JsonObject bare = PlanExport.project(g, "Test plan", WORLD);
        assertFalse(bare.has("description"), "no description, none sent");
        assertFalse(bare.has("icon"), "no icon, none sent");
        g.setDescription("Steel, the slow way");
        g.setIcon("steel");
        final JsonObject p = PlanExport.project(g, "Test plan", WORLD);
        assertEquals(
            "Steel, the slow way",
            p.get("description")
                .getAsString());
        final JsonObject icon = p.getAsJsonObject("icon");
        assertEquals(
            "item",
            icon.get("kind")
                .getAsString());
        assertEquals(
            "test:steel",
            icon.get("resourceId")
                .getAsString(),
            "the site's EntryIcon: kind, resourceId, displayName");
        assertEquals(
            "steel",
            icon.get("displayName")
                .getAsString());
    }

    @Test
    void notesBecomeTextAnnotations() throws IOException {
        final Graph g = GtnhFlowLoader.load("mk1")
            .graph();
        final Note tagged = new Note();
        tagged.setX(320);
        tagged.setY(60);
        tagged.setWidth(202);
        tagged.setHeight(48);
        tagged.setColor("lime");
        tagged.setFontSize(22);
        tagged.setJoined("Feed the reactor\nfrom the left");
        g.notes.put(tagged.getId(), tagged);
        final Note plain = new Note();
        plain.setX(0);
        plain.setY(300);
        plain.setColor("chartreuse");
        plain.setJoined("");
        g.notes.put(plain.getId(), plain);

        final JsonObject p = PlanExport.project(g, "Test plan", WORLD);
        final JsonArray annotations = p.getAsJsonArray("annotations");
        assertEquals(2, annotations.size(), "an annotation for every note");
        for (final JsonElement a : annotations) fitsTheSchema(a.getAsJsonObject());

        final JsonObject t = annotation(annotations, tagged);
        assertEquals(
            "text",
            t.get("kind")
                .getAsString());
        assertEquals(
            "lime",
            t.get("colorTag")
                .getAsString());
        assertEquals(
            "Feed the reactor\nfrom the left",
            t.get("text")
                .getAsString());
        assertEquals(
            380,
            t.getAsJsonObject("position")
                .get("x")
                .getAsInt(),
            "placed as a card is: 320 at 380/320");
        assertEquals(
            100,
            t.getAsJsonObject("position")
                .get("y")
                .getAsInt(),
            "and 60 at 1/0.6");
        assertEquals(
            240,
            t.getAsJsonObject("size")
                .get("width")
                .getAsInt(),
            "202 at 380/320, to whole 20 px cells");
        assertEquals(
            80,
            t.getAsJsonObject("size")
                .get("height")
                .getAsInt());
        assertEquals(
            22,
            t.get("fontSize")
                .getAsInt());

        final JsonObject u = annotation(annotations, plain);
        assertFalse(u.has("colorTag"), "a colour the site does not know is left for its default");
        assertEquals(
            "",
            u.get("text")
                .getAsString());
        assertEquals(
            Note.DEFAULT_FONT,
            u.get("fontSize")
                .getAsInt());
        assertEquals(
            220,
            u.getAsJsonObject("size")
                .get("width")
                .getAsInt(),
            "a new note's 180 x 120");
        assertEquals(
            200,
            u.getAsJsonObject("size")
                .get("height")
                .getAsInt());

        final Path out = Path.of("build", "tmp", "gtnhplanner-export-notes.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, p.toString(), StandardCharsets.UTF_8);
    }

    private static JsonObject annotation(final JsonArray annotations, final Note note) {
        for (final JsonElement a : annotations) if (a.getAsJsonObject()
            .get("id")
            .getAsString()
            .equals(
                note.getId()
                    .toString()))
            return a.getAsJsonObject();
        throw new AssertionError("no annotation for note " + note.getId());
    }

    /** What the site's factoryAnnotationSchema asks of a text annotation. */
    private static void fitsTheSchema(final JsonObject a) {
        for (final Map.Entry<String, JsonElement> e : a.entrySet())
            assertTrue(ANNOTATION_KEYS.contains(e.getKey()), "unknown key " + e.getKey());
        assertFalse(
            a.get("id")
                .getAsString()
                .isEmpty());
        assertEquals(
            "text",
            a.get("kind")
                .getAsString());
        if (a.has("colorTag")) assertTrue(
            SITE_COLOR_TAGS.contains(
                a.get("colorTag")
                    .getAsString()),
            "a colour tag the site knows");
        assertTrue(
            a.get("text")
                .getAsJsonPrimitive()
                .isString());
        for (final String key : List.of("x", "y")) assertTrue(
            a.getAsJsonObject("position")
                .get(key)
                .getAsJsonPrimitive()
                .isNumber());
        for (final String key : List.of("width", "height")) assertTrue(
            a.getAsJsonObject("size")
                .get(key)
                .getAsJsonPrimitive()
                .isNumber());
        final int font = a.get("fontSize")
            .getAsInt();
        assertTrue(font >= 8 && font <= 96, "a text size the site takes");
    }
}
