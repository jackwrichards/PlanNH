package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.MachineProfile;
import com.gtnhplanner.data.MachineProfileRegistry;
import com.gtnhplanner.data.Settings;
import com.gtnhplanner.data.effect.EffectResult;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.data.flowchart.Serializer;
import com.gtnhplanner.harness.GtnhFlowLoader;
import com.gtnhplanner.harness.GtnhFlowLoader.LoadedChart;

/**
 * The save format against damaged and unusual input, ported from Factory Flow's
 * src/lib/import-export/plan-code.test.ts (garbage and cut-short codes), factory-json.test.ts
 * (round trips) and src/lib/model/project-normalize.test.ts (doubled wires). A chart that cannot be
 * read has to fail as one plain RuntimeException, which is what {@link Serializer#decodePlan} turns
 * into an empty slot; anything else (an Error, a hang, a half-read graph) would cost the whole save.
 */
class SerializerTest {

    private static final String DECODE_FAILED = "Failed to decode flowchart";
    private static final String GT_PROFILE = "test:gt";

    @Test
    void garbageFailsAsOnePlainException() {
        final String[] garbage = { "", "hello", "gtnh1.!!!", base64("not gzip at all"), gzipped("not json"),
            gzipped("[]"), gzipped("{}"), gzipped("{\"name\": \"x\", \"zoom\": 1, \"panX\": 0, \"panY\": 0}"),
            gzipped("{\"name\": \"x\", \"zoom\": 1, \"panX\": 0, \"panY\": 0, \"nodes\": 5}") };
        for (final String data : garbage) {
            final RuntimeException e = assertThrows(RuntimeException.class, () -> Serializer.decode(data), data);
            assertEquals(DECODE_FAILED, e.getMessage(), data);
        }
    }

    @Test
    void aSaveCutShortFailsCleanly() {
        final String whole = Serializer.encode(
            GtnhFlowLoader.load("mk1")
                .graph());
        // Cut inside the base64, at a clean four-character boundary (the gzip trailer is lost), and
        // inside an intact gzip stream around half-written JSON.
        final String json = gunzip(whole);
        for (final String data : new String[] { whole.substring(0, whole.length() / 2),
            whole.substring(0, whole.length() - 4), whole.substring(0, 8),
            gzipped(json.substring(0, json.length() / 2)) }) {
            final RuntimeException e = assertThrows(RuntimeException.class, () -> Serializer.decode(data));
            assertEquals(DECODE_FAILED, e.getMessage());
        }
    }

    @Test
    void plansListMostRecentlyOpenedFirstAndRememberItThroughASave() {
        final Plan plan = Serializer.decodePlan("{}");
        for (final String name : List.of("a", "b", "c", "d")) plan.getGraphs()
            .add(new Graph(name));
        plan.getGraphs()
            .get(1)
            .setLastOpen(100);
        plan.getGraphs()
            .get(3)
            .setLastOpen(300);
        plan.setActiveIndex(2);
        assertEquals(
            List.of(2, 0, 3, 1),
            plan.byRecency(),
            "the open one, the one it replaced, then by when each was open");

        final Plan again = Serializer.decodePlan(Serializer.encodePlan(plan));
        assertEquals(List.of(2, 0, 3, 1), again.byRecency(), "and the same after a save");
        assertEquals(
            300,
            again.getGraphs()
                .get(3)
                .getLastOpen());
    }

    @Test
    void aPlanWithOneDamagedSlotKeepsTheOthers() {
        final LoadedChart chart = GtnhFlowLoader.load("mk1");
        final JsonObject root = new JsonObject();
        final JsonArray slots = new JsonArray();
        slots.add(slot("good", Serializer.encode(chart.graph())));
        slots.add(slot("bad", "hello"));
        root.add("graphs", slots);

        final Plan plan = Serializer.decodePlan(root.toString());

        final List<Graph> graphs = plan.getGraphs();
        assertEquals(2, graphs.size(), "the damaged slot keeps its place");
        assertEquals(
            "good",
            graphs.get(0)
                .getName());
        assertEquals(
            2,
            graphs.get(0)
                .getNodes()
                .size());
        assertEquals(
            "bad",
            graphs.get(1)
                .getName());
        assertTrue(
            graphs.get(1)
                .getNodes()
                .isEmpty(),
            "and loads empty");
    }

    @Test
    void aSlotThatCannotBeReadIsSavedAsItWas() {
        final LoadedChart chart = GtnhFlowLoader.load("mk1");
        final String kept = Serializer.encode(chart.graph())
            .substring(0, 40);
        final JsonObject root = new JsonObject();
        final JsonArray slots = new JsonArray();
        slots.add(slot("good", Serializer.encode(chart.graph())));
        slots.add(slot("unreadable", kept));
        root.add("graphs", slots);

        // Read (the slot fails), then saved: its data goes back out untouched, not as an empty plan.
        final String saved = Serializer.encodePlan(Serializer.decodePlan(root.toString()));
        final JsonArray back = new com.google.gson.JsonParser().parse(saved)
            .getAsJsonObject()
            .getAsJsonArray("graphs");
        assertEquals(
            kept,
            back.get(1)
                .getAsJsonObject()
                .get("data")
                .getAsString());
        // Once something is put in the slot, it is saved as it is now.
        final Plan reread = Serializer.decodePlan(root.toString());
        reread.getGraphs()
            .get(1)
            .addNode(
                chart.graph()
                    .getNodes()
                    .iterator()
                    .next());
        final String used = Serializer.encodePlan(reread);
        assertFalse(used.contains("\"" + kept + "\""), "a slot used since is saved as it is now");
    }

    @Test
    void aDoubledEdgeLoadsAsOne() {
        // The same two ports wired twice: addEdge refuses that, so the twin goes straight into the
        // map, the way an old save or a bug elsewhere could have left it.
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final Graph graph = chart.graph();
        final int wired = graph.edges.size();
        final Edge edge = graph.getEdges()
            .iterator()
            .next();
        final Edge twin = new Edge(
            UUID.nameUUIDFromBytes("twin".getBytes(StandardCharsets.UTF_8)),
            edge.sourceNodeId,
            edge.targetNodeId,
            edge.sourceOutputIndex,
            edge.targetInputIndex);
        graph.edges.put(twin.id, twin);
        assertEquals(wired + 1, graph.edges.size(), "precondition: the save holds both");

        final Graph decoded = Serializer.decode(Serializer.encode(graph));

        assertEquals(wired, decoded.edges.size(), "one wire per pair of ports");
        final long between = decoded.getEdges()
            .stream()
            .filter(
                e -> e.sourceNodeId.equals(edge.sourceNodeId) && e.sourceOutputIndex == edge.sourceOutputIndex
                    && e.targetNodeId.equals(edge.targetNodeId)
                    && e.targetInputIndex == edge.targetInputIndex)
            .count();
        assertEquals(1, between);
    }

    @Test
    void worldLinksSurviveARoundTrip() {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final Node linked = chart.machine(0);
        linked.worldLinks.add(new int[] { 0, 518, 5, -3 });
        linked.worldLinks.add(new int[] { -1, 12, 64, 40 });

        final Graph decoded = Serializer.decode(Serializer.encode(chart.graph()));

        final List<int[]> back = decoded.nodes.get(linked.id).worldLinks;
        assertEquals(2, back.size());
        assertEquals(List.of(0, 518, 5, -3), List.of(back.get(0)[0], back.get(0)[1], back.get(0)[2], back.get(0)[3]));
        assertEquals(List.of(-1, 12, 64, 40), List.of(back.get(1)[0], back.get(1)[1], back.get(1)[2], back.get(1)[3]));
        assertTrue(decoded.nodes.get(chart.machine(1).id).worldLinks.isEmpty(), "a card with no links gets none");
    }

    @Test
    void aPlacementKeepsItsFacing() {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final Node placed = chart.machine(0);
        placed.worldLinks.add(new int[] { 0, 10, 4, -2, 3 });
        placed.worldLinks.add(new int[] { 0, 14, 4, -2 });
        placed.worldLinks.add(new int[] { 0, 18, 4, -2, 1, 6 });

        final List<int[]> back = Serializer.decode(Serializer.encode(chart.graph())).nodes.get(placed.id).worldLinks;

        assertEquals(3, back.size());
        assertEquals(
            List.of(0, 18, 4, -2, 1, 6),
            Arrays.stream(back.get(2))
                .boxed()
                .toList(),
            "a structure set to a size");
        assertEquals(
            List.of(0, 10, 4, -2, 3),
            Arrays.stream(back.get(0))
                .boxed()
                .toList(),
            "a machine and its facing");
        assertEquals(
            List.of(0, 14, 4, -2),
            Arrays.stream(back.get(1))
                .boxed()
                .toList(),
            "saved before facings");
    }

    @Test
    void settingPinsSurviveARoundTrip() {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        chart.graph().settingPins.put("machine:Electric Blast Furnace", List.of("machine_heat", "perfect_oc"));
        chart.graph().settingPins.put("power:large-steam-turbine", List.of());

        final Graph decoded = Serializer.decode(Serializer.encode(chart.graph()));

        assertEquals(List.of("machine_heat", "perfect_oc"), decoded.settingPins.get("machine:Electric Blast Furnace"));
        assertEquals(List.of(), decoded.settingPins.get("power:large-steam-turbine"), "a machine with all unpinned");
        assertTrue(
            Serializer.decode(
                Serializer.encode(
                    GtnhFlowLoader.load("light_fuel")
                        .graph())).settingPins.isEmpty(),
            "a plan with no pins gets none");
    }

    @Test
    void machineSettingsSurviveARoundTrip() {
        registerGtProfile();
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final Node reactor = chart.machine(0);
        final MachineConfig cfg = reactor.machineConfig;
        cfg.profileId = GT_PROFILE;
        cfg.settings.put(Settings.VOLTAGE.key(), "HV");
        cfg.settings.put(Settings.AMP.key(), 4);
        cfg.settings.put(Settings.MACHINE_HEAT.key(), 4500); // the coil
        cfg.settings.put(Settings.PARALLELS.key(), 16);
        cfg.settings.put(Settings.PERFECT_OC.key(), true);
        cfg.setMachineCount(7);
        reactor.setMachineCountFixed(true);

        final Graph decoded = Serializer.decode(Serializer.encode(chart.graph()));

        final Node restored = decoded.nodes.get(reactor.id);
        final MachineConfig back = restored.machineConfig;
        assertEquals(GT_PROFILE, back.profileId);
        assertEquals("HV", back.getString(Settings.VOLTAGE.key()));
        assertEquals(4, back.getInt(Settings.AMP.key()));
        assertEquals(4500, back.getInt(Settings.MACHINE_HEAT.key()));
        assertEquals(16, back.getInt(Settings.PARALLELS.key()));
        assertTrue(back.getBoolean(Settings.PERFECT_OC.key()));
        assertEquals(7, back.getMachineCount());
        assertTrue(restored.isMachineCountFixed(), "the pinned count stays pinned");
        // The distillery's number pin from the chart survives too, and the electrolyzer stays free.
        assertTrue(
            decoded.nodes.get(chart.machine(1).id)
                .isMachineCountFixed());
        assertFalse(
            decoded.nodes.get(chart.machine(2).id)
                .isMachineCountFixed());
    }

    @Test
    void notesSurviveARoundTrip() {
        final Graph graph = GtnhFlowLoader.load("mk1")
            .graph();
        final Note note = new Note();
        note.setX(120);
        note.setY(-40);
        note.setJoined("Line one\nline two");
        note.setWidth(260);
        note.setHeight(90);
        note.setColor("pink");
        note.setFontSize(18);
        graph.notes.put(note.getId(), note);

        final Note back = Serializer.decode(Serializer.encode(graph)).notes.get(note.getId());

        assertEquals(List.of("Line one", "line two"), back.getText());
        assertEquals(120, back.getX());
        assertEquals(-40, back.getY());
        assertEquals(260, back.getWidth());
        assertEquals(90, back.getHeight());
        assertEquals("pink", back.colorTag());
        assertEquals(18, back.fontSizeOrDefault());
    }

    @Test
    void notesSavedBeforeSizesReadWithDefaults() {
        // A note as saves wrote it before notes had a size, a colour or a text size.
        final JsonObject root = new com.google.gson.JsonParser()
            .parse(
                gunzip(
                    Serializer.encode(
                        GtnhFlowLoader.load("mk1")
                            .graph())))
            .getAsJsonObject();
        final UUID id = UUID.nameUUIDFromBytes("old note".getBytes(StandardCharsets.UTF_8));
        final JsonObject old = new com.google.gson.JsonParser()
            .parse(
                "{\"text\": [\"Remember\"], \"id\": \"" + id
                    + "\", \"x\": 100, \"y\": 200, \"header\": \"Note\", \"type\": \"note\"}")
            .getAsJsonObject();
        root.getAsJsonArray("notes")
            .add(old);

        final Note note = Serializer.decode(gzipped(root.toString())).notes.get(id);

        assertEquals(List.of("Remember"), note.getText());
        assertEquals(100, note.getX());
        assertEquals(200, note.getY());
        assertEquals(Note.DEFAULT_W, note.getWidth());
        assertEquals(Note.DEFAULT_H, note.getHeight());
        assertEquals(Note.DEFAULT_COLOR, note.colorTag());
        assertEquals(Note.DEFAULT_FONT, note.fontSizeOrDefault());
    }

    // ── helpers ──

    /** A profile carrying GregTech's settings, built directly: the real one needs a running client. */
    private static void registerGtProfile() {
        GtnhFlowLoader.ensureDefaultMachineProfile();
        if (MachineProfileRegistry.get(GT_PROFILE) != null) return;
        MachineProfileRegistry.register(
            new MachineProfile(
                GT_PROFILE,
                "GT",
                List.of(
                    Settings.VOLTAGE.def(),
                    Settings.AMP.def(),
                    Settings.PARALLELS.def(),
                    Settings.MACHINES.def(),
                    Settings.PERFECT_OC.def(),
                    Settings.MACHINE_HEAT.def()),
                (s, ctx) -> new EffectResult(ctx.getOrDefault(GtnhFlowLoader.DURATION_TICKS, 1), 0, 1)));
    }

    private static JsonObject slot(final String name, final String data) {
        final JsonObject slot = new JsonObject();
        slot.addProperty("name", name);
        slot.addProperty("data", data);
        return slot;
    }

    private static String base64(final String text) {
        return Base64.getEncoder()
            .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String gzipped(final String text) {
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (Writer writer = new OutputStreamWriter(new GZIPOutputStream(bytes), StandardCharsets.UTF_8)) {
                writer.write(text);
            }
            return Base64.getEncoder()
                .encodeToString(bytes.toByteArray());
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String gunzip(final String encoded) {
        try (InputStream in = new GZIPInputStream(
            new ByteArrayInputStream(
                Base64.getDecoder()
                    .decode(encoded)))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
    }
}
