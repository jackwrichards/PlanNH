package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
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
}
