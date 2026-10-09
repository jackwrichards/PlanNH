package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.flowchart.balancer.BalanceMode;
import com.gtnhplanner.data.flowchart.balancer.BalanceResult;
import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.harness.TestIngredients;
import com.gtnhplanner.library.PlanExport;
import com.gtnhplanner.power.CustomRate;
import com.gtnhplanner.power.Energy;
import com.gtnhplanner.power.PowerRegistry;

/**
 * The custom rate card (the website's custom-rate.ts): a dial that supplies or drains a resource, pinned at one so the
 * dial is the rate. Its ports are built by hand here, without the game's registries.
 */
class CustomRateTest {

    /** A custom rate card holding {@code name} at {@code perSecond}, pinned at one, its port built by hand. */
    private static Node card(final String name, final boolean supply, final double perSecond) {
        com.gtnhplanner.harness.GtnhFlowLoader.ensureDefaultMachineProfile();
        final Node n = new Node(UUID.randomUUID(), 400, 0);
        n.powerSource = CustomRate.ID;
        CustomRate.hold(n, "item:test:" + name + ":0", supply);
        n.powerSettings.put(CustomRate.RATE, Double.toString(perSecond));
        n.machineName = CustomRate.SOURCE.name();
        n.properties.put(com.gtnhplanner.harness.GtnhFlowLoader.DURATION_TICKS, 20);
        final Port<?> port = TestIngredients.port(name, perSecond);
        port.setExactAmount(perSecond);
        (supply ? n.outputs : n.inputs).add(port);
        n.machineConfig.setMachineCount(1);
        n.setMachineCountFixed(true);
        return n;
    }

    /** An empty custom rate card. */
    private static Node blank() {
        com.gtnhplanner.harness.GtnhFlowLoader.ensureDefaultMachineProfile();
        final Node n = new Node(UUID.randomUUID(), 0, 0);
        n.powerSource = CustomRate.ID;
        return n;
    }

    /** A recipe card making or using one {@code name} a second per machine. */
    private static Node machine(final String name, final boolean makes) {
        final Node n = new Node(UUID.randomUUID(), 0, 0);
        n.machineName = makes ? "Maker" : "User";
        n.properties.put(com.gtnhplanner.harness.GtnhFlowLoader.DURATION_TICKS, 20);
        (makes ? n.outputs : n.inputs).add(TestIngredients.port(name, 1));
        return n;
    }

    @Test
    void theDialStartsAtOneSupplyAndOutlivesItsResource() {
        final Node n = blank();
        assertTrue(CustomRate.supply(n), "a new card supplies");
        assertEquals(CustomRate.DEFAULT_RATE, CustomRate.rate(n), 0);
        assertNull(CustomRate.resource(n), "and holds nothing");

        n.powerSettings.put(CustomRate.RATE, "50");
        CustomRate.hold(n, "fluid:water", false);
        assertFalse(CustomRate.supply(n));
        assertEquals("fluid:water", CustomRate.resource(n));
        assertEquals("L/s", CustomRate.unit(CustomRate.resource(n)));

        CustomRate.release(n);
        assertNull(CustomRate.resource(n), "unwired, it lets go of the resource");
        assertEquals(50, CustomRate.rate(n), 0, "but keeps the dial");
        assertFalse(CustomRate.supply(n));
        assertFalse(
            CustomRate.fresh(Map.of(CustomRate.RESOURCE, "fluid:water", CustomRate.RATE, "7"))
                .containsKey(CustomRate.RESOURCE),
            "a new card takes the last dial, never the last resource");
    }

    @Test
    void euIsDialedInEuPerTick() {
        final Node n = blank();
        CustomRate.hold(n, Energy.KEY, true);
        n.powerSettings.put(CustomRate.RATE, "512");
        assertEquals("EU/t", CustomRate.unit(Energy.KEY));
        assertEquals(512 * 20, CustomRate.perSecond(n), 0, "a second's worth for the port");
    }

    @Test
    void itIsTheRegistrysButNeverTheCatalogs() {
        assertEquals(CustomRate.SOURCE, PowerRegistry.get(CustomRate.ID));
        assertFalse(
            PowerRegistry.sources()
                .contains(CustomRate.SOURCE),
            "placed from its own key, not listed with the generators");
    }

    @Test
    void aSupplyPinnedAtOneFeedsExactlyItsDial() {
        final Graph g = new Graph("custom");
        final Node supply = card("ilmenite", true, 5);
        final Node user = machine("ilmenite", false);
        g.addNode(supply);
        g.addNode(user);
        g.addEdge(new Edge(UUID.randomUUID(), supply.id, user.id, 0, 0));

        final BalanceResult result = Balancer.balance(g, BalanceMode.AUTO);

        assertEquals(
            5,
            result.nodeBalances()
                .get(user.id)
                .operations(),
            1e-6,
            "five machines use the five a second it supplies");
    }

    @Test
    void aRequestPinnedAtOneDrainsExactlyItsDial() {
        final Graph g = new Graph("custom");
        final Node maker = machine("rutile", true);
        final Node request = card("rutile", false, 3);
        g.addNode(maker);
        g.addNode(request);
        g.addEdge(new Edge(UUID.randomUUID(), maker.id, request.id, 0, 0));

        final BalanceResult result = Balancer.balance(g, BalanceMode.AUTO);

        assertEquals(
            3,
            result.nodeBalances()
                .get(maker.id)
                .operations(),
            1e-6,
            "three machines make the three a second it drains");
    }

    @Test
    void itPostsAsTheWebsitesCustomRateCard() throws IOException {
        final Graph g = new Graph("custom");
        final Node supply = card("water", true, 50);
        final Node user = machine("water", false);
        g.addNode(supply);
        g.addNode(user);
        g.addEdge(new Edge(UUID.randomUUID(), supply.id, user.id, 0, 0));

        final JsonObject p = PlanExport.project(g, "Custom rate test", new PlanExport.World() {

            @Override
            public PlanExport.Res port(final Port<?> port) {
                return new PlanExport.Res("fluid", TestIngredients.nameOf(port), "Water");
            }

            @Override
            public PlanExport.Res resource(final String key) {
                return new PlanExport.Res("fluid", key, key);
            }

            @Override
            public String machine(final Node node) {
                return node.machineName;
            }

            @Override
            public double machines(final Node node) {
                return 1;
            }

            @Override
            public String tier(final Node node) {
                return "LV";
            }
        });

        JsonObject recipe = null, node = null;
        for (final var r : p.getAsJsonArray("recipes")) if ("custom-rate".equals(
            r.getAsJsonObject()
                .get("category") == null ? null
                    : r.getAsJsonObject()
                        .get("category")
                        .getAsString()))
            recipe = r.getAsJsonObject();
        for (final var n : p.getAsJsonArray("nodes")) if (n.getAsJsonObject()
            .has("customRate")) node = n.getAsJsonObject();
        assertTrue(recipe != null && node != null, "the card goes up as a custom rate card");
        assertEquals(
            "Custom Rate",
            recipe.get("machineType")
                .getAsString());
        assertEquals(
            "custom",
            recipe.get("kind")
                .getAsString());
        assertEquals(
            "Custom Rate: Water",
            recipe.get("name")
                .getAsString());
        assertEquals(
            20,
            recipe.get("durationTicks")
                .getAsInt());
        assertEquals(
            50,
            recipe.getAsJsonArray("outputs")
                .get(0)
                .getAsJsonObject()
                .get("amount")
                .getAsDouble(),
            0,
            "its slot is its rate a second");
        assertEquals(
            0,
            recipe.getAsJsonArray("inputs")
                .size());
        final JsonObject dial = node.getAsJsonObject("customRate");
        assertEquals(
            50,
            dial.get("perSecond")
                .getAsDouble(),
            0);
        assertEquals(
            "supply",
            dial.get("mode")
                .getAsString());
        assertFalse(node.has("machineConfigTiers"), "the dial is not a generator's setting");
        assertEquals(
            "NONE",
            node.get("overclockTier")
                .getAsString());
        // For running past the website's own schema by hand.
        final Path out = Path.of("build", "tmp", "gtnhplanner-custom-rate-export.json");
        Files.createDirectories(out.getParent());
        Files.writeString(
            out,
            new GsonBuilder().setPrettyPrinting()
                .create()
                .toJson(p),
            StandardCharsets.UTF_8);
    }
}
