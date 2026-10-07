package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Drawer.Kind;
import com.gtnhplanner.data.flowchart.Drawer.Link;
import com.gtnhplanner.data.flowchart.Drawer.Rule;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Serializer;
import com.gtnhplanner.harness.GtnhFlowLoader;
import com.gtnhplanner.harness.GtnhFlowLoader.LoadedChart;

/** Drawers as graph data: membership, links, version bumps and the save format. */
class DrawerModelTest {

    @Test
    void drawersSurviveEncodeDecode() {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final Graph graph = chart.graph();
        final UUID first = chart.machine(0).id;
        final UUID second = chart.machine(1).id;

        final Drawer product = new Drawer(Kind.PRODUCT, "fluid:liquid_light_fuel");
        product.setTarget(Rule.AT_LEAST, 1250.5);
        product.setLabel("Light Fuel");
        product.setX(320);
        product.setY(-48);
        product.addLink(new Link(first, 1));
        product.addLink(new Link(second, 0));
        graph.addDrawer(product);

        final Drawer source = new Drawer(Kind.SOURCE, "fluid:oil");
        source.setTarget(Rule.EXACTLY, 1000);
        source.addLink(new Link(first, 0));
        graph.addDrawer(source);

        final Drawer empty = new Drawer(Kind.TRASH, "item:minecraft:dirt:0");
        graph.addDrawer(empty);

        final Graph decoded = Serializer.decode(Serializer.encode(graph));

        assertEquals(
            3,
            decoded.getDrawers()
                .size());
        final Drawer p = decoded.getDrawer(product.getId());
        assertNotNull(p, "the product drawer keeps its id");
        assertEquals(Kind.PRODUCT, p.getKind());
        assertEquals(Rule.AT_LEAST, p.getRule());
        assertEquals(1250.5, p.getRate(), 0);
        assertEquals("fluid:liquid_light_fuel", p.getResourceKey());
        assertEquals("Light Fuel", p.getLabel());
        assertEquals(320, p.getX());
        assertEquals(-48, p.getY());
        assertEquals(List.of(new Link(first, 1), new Link(second, 0)), p.getLinks(), "links in order");
        assertEquals(Drawer.TYPE, p.getType());

        final Drawer s = decoded.getDrawer(source.getId());
        assertEquals(Kind.SOURCE, s.getKind());
        assertEquals(Rule.EXACTLY, s.getRule());
        assertEquals(1000, s.getRate(), 0);
        assertEquals(List.of(new Link(first, 0)), s.getLinks());

        final Drawer t = decoded.getDrawer(empty.getId());
        assertEquals(Kind.TRASH, t.getKind());
        assertTrue(
            t.getLinks()
                .isEmpty(),
            "a drawer with no links is a drawer all the same");
    }

    @Test
    void saveWithoutDrawersStillLoads() {
        final LoadedChart chart = GtnhFlowLoader.load("mk1");
        final JsonObject root = unpack(Serializer.encode(chart.graph()));
        assertTrue(root.has("drawers"), "new saves write the key");
        root.remove("drawers");

        final Graph decoded = Serializer.decode(pack(root));

        assertTrue(
            decoded.getDrawers()
                .isEmpty());
        assertEquals(chart.graph().nodes.size(), decoded.nodes.size(), "the rest of the chart is untouched");
    }

    @Test
    void unknownNamesAndMissingFieldsAreRepairedOnLoad() {
        final JsonObject root = unpack(Serializer.encode(new Graph("x")));
        final UUID id = UUID.randomUUID();
        final JsonArray drawers = new JsonArray();
        final JsonObject drawer = new JsonObject();
        drawer.addProperty("id", id.toString());
        drawer.addProperty("type", Drawer.TYPE);
        drawer.addProperty("kind", "SOMETHING_NEW");
        drawer.addProperty("rate", -5);
        drawers.add(drawer);
        root.add("drawers", drawers);

        final Drawer loaded = Serializer.decode(pack(root))
            .getDrawer(id);

        assertNotNull(loaded);
        assertEquals(Kind.PRODUCT, loaded.getKind(), "an unknown kind reads as a product");
        assertEquals(Rule.ANY, loaded.getRule());
        assertEquals(0, loaded.getRate(), 0);
        assertTrue(
            loaded.getLinks()
                .isEmpty());
    }

    @Test
    void membershipChangesBumpTheVersion() {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final Graph graph = chart.graph();
        final Drawer drawer = new Drawer(Kind.PRODUCT, "fluid:x");

        long before = graph.version();
        graph.addDrawer(drawer);
        assertTrue(graph.version() > before, "addDrawer");

        before = graph.version();
        assertTrue(graph.linkDrawer(drawer.getId(), new Link(chart.machine(0).id, 0)));
        assertTrue(graph.version() > before, "linkDrawer");

        before = graph.version();
        assertTrue(graph.unlinkDrawer(drawer.getId(), new Link(chart.machine(0).id, 0)));
        assertTrue(graph.version() > before, "unlinkDrawer");

        before = graph.version();
        graph.removeDrawer(drawer.getId());
        assertTrue(graph.version() > before, "removeDrawer");
        assertNull(graph.getDrawer(drawer.getId()));
    }

    @Test
    void removingANodeDropsItsLinks() {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final Graph graph = chart.graph();
        final UUID gone = chart.machine(0).id;
        final UUID kept = chart.machine(1).id;
        final Drawer drawer = new Drawer(Kind.PRODUCT, "fluid:x");
        drawer.addLink(new Link(gone, 0));
        drawer.addLink(new Link(kept, 0));
        graph.addDrawer(drawer);

        final long before = graph.version();
        graph.removeNode(gone);

        assertTrue(graph.version() > before);
        assertEquals(List.of(new Link(kept, 0)), drawer.getLinks());
    }

    @Test
    void aPortBelongsToOneDrawerPerDirection() {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final Graph graph = chart.graph();
        final Link port = new Link(chart.machine(0).id, 0);
        final Drawer a = new Drawer(Kind.PRODUCT, "fluid:x");
        final Drawer b = new Drawer(Kind.TRASH, "fluid:x");
        final Drawer source = new Drawer(Kind.SOURCE, "fluid:x");
        graph.addDrawer(a);
        graph.addDrawer(b);
        graph.addDrawer(source);

        graph.linkDrawer(a.getId(), port);
        graph.linkDrawer(source.getId(), port); // the same index on the input side is another port
        graph.linkDrawer(b.getId(), port);

        assertFalse(a.isLinked(port.nodeId(), port.portIndex()), "the output port moved to the trash drawer");
        assertTrue(b.isLinked(port.nodeId(), port.portIndex()));
        assertTrue(source.isLinked(port.nodeId(), port.portIndex()), "inputs and outputs are separate ports");
        assertSame(b, graph.drawerAt(port.nodeId(), port.portIndex(), false));
        assertSame(source, graph.drawerAt(port.nodeId(), port.portIndex(), true));
    }

    @Test
    void kindsCycleAndOnlySourcesAndProductsKeepTheirRule() {
        assertEquals(Kind.BYPRODUCT, Kind.PRODUCT.next());
        assertEquals(Kind.TRASH, Kind.BYPRODUCT.next());
        assertEquals(Kind.PRODUCT, Kind.TRASH.next());
        assertEquals(Kind.SOURCE, Kind.SOURCE.next());

        final Drawer drawer = new Drawer(Kind.PRODUCT, "fluid:x");
        drawer.setTarget(Rule.AT_LEAST, 10);
        drawer.addLink(new Link(UUID.randomUUID(), 2));
        assertTrue(drawer.isAnchor());
        drawer.setKind(Kind.BYPRODUCT);
        assertEquals(Rule.ANY, drawer.effectiveRule(), "a byproduct asks for nothing");
        assertEquals(Rule.AT_LEAST, drawer.getRule(), "but remembers the rule for the way back");
        assertFalse(drawer.isAnchor());
        assertEquals(
            1,
            drawer.getLinks()
                .size(),
            "cycling among output kinds keeps the links");
        drawer.setKind(Kind.SOURCE);
        assertTrue(
            drawer.getLinks()
                .isEmpty(),
            "a source's links are inputs, so the output links go");
    }

    private static JsonObject unpack(final String encoded) {
        try (Reader reader = new InputStreamReader(
            new GZIPInputStream(
                new ByteArrayInputStream(
                    Base64.getDecoder()
                        .decode(encoded))),
            StandardCharsets.UTF_8)) {
            return Serializer.GSON.fromJson(reader, JsonObject.class);
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String pack(final JsonObject root) {
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (Writer writer = new OutputStreamWriter(new GZIPOutputStream(bytes), StandardCharsets.UTF_8)) {
                writer.write(root.toString());
            }
            return Base64.getEncoder()
                .encodeToString(bytes.toByteArray());
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
    }
}
