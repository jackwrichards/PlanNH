package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.flowchart.balancer.BalanceMode;
import com.gtnhplanner.data.flowchart.balancer.BalanceResult;
import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.data.properties.ResourceProperty;
import com.gtnhplanner.harness.TestIngredients;
import com.gtnhplanner.library.PlanExport;
import com.gtnhplanner.power.Energy;
import com.gtnhplanner.power.PowerRegistry;
import com.gtnhplanner.power.PowerSource;

/**
 * A non-recipe machine on the board: a generator runs one craft a second at its exact (fractional) rates, its EU drawer
 * sizes it, and it posts to the library as the website's power card. Built by hand, without the game's registries.
 */
class PowerCardTest {

    /** EU as the game types it, without loading the game's own resource types. */
    private static final ResourceProperty<Energy> EU = ResourceProperty.builder("power", new Energy(0))
        .amountExtractor(e -> (int) Math.round(e.perCraft))
        .connectionChecker((a, b) -> true)
        .displayFormatter(e -> Energy.NAME)
        .build();

    private static Node generator(final String sourceId, final Map<String, String> settings) {
        com.gtnhplanner.harness.GtnhFlowLoader.ensureDefaultMachineProfile();
        final Node gen = new Node(UUID.randomUUID(), 400, 0);
        gen.powerSource = sourceId;
        gen.powerSettings.putAll(settings);
        final PowerSource source = PowerRegistry.get(sourceId);
        gen.machineName = source.name();
        gen.powerModel = source.compute(settings);
        final double euPerSecond = gen.powerModel.euPerTick() * 20;
        final Port<Energy> eu = new Port<>(EU, new Energy(euPerSecond), 1f);
        eu.setExactAmount(euPerSecond);
        gen.outputs.add(eu);
        for (final var flow : gen.powerModel.inputs()) {
            final Port<?> in = TestIngredients.port(
                flow.name()
                    .toLowerCase(Locale.ROOT),
                1);
            in.setExactAmount(flow.perSecond());
            gen.inputs.add(in);
        }
        return gen;
    }

    @Test
    void anEuDrawerSizesTheGeneratorAndItsFuelChain() {
        final Graph g = new Graph("power");
        // A gas turbine on benzene, LV: its fuel rate is a fraction of a litre a second.
        final Node gen = generator("gas-turbine", Map.of("tier", "LV", "fuel", "Benzene"));
        final double fuelPerSecond = gen.powerModel.inputs()
            .get(0)
            .perSecond();
        assertTrue(fuelPerSecond != Math.rint(fuelPerSecond), "the test wants a fractional rate");
        final Node maker = new Node(UUID.randomUUID(), 0, 0);
        maker.machineName = "Benzene maker";
        maker.properties.put(com.gtnhplanner.harness.GtnhFlowLoader.DURATION_TICKS, 20);
        maker.outputs.add(TestIngredients.port("benzene", 1));
        g.addNode(maker);
        g.addNode(gen);
        g.addEdge(new Edge(UUID.randomUUID(), maker.id, gen.id, 0, 0));
        final Drawer power = new Drawer(Drawer.Kind.PRODUCT, Energy.KEY);
        power.setTarget(Drawer.Rule.EXACTLY, 10 * gen.powerModel.euPerTick() * 20);
        g.addDrawer(power);
        g.linkDrawer(power.getId(), new Drawer.Link(gen.id, 0));

        final BalanceResult result = Balancer.balance(g, BalanceMode.AUTO);

        assertEquals(
            10,
            result.nodeBalances()
                .get(gen.id)
                .operations(),
            1e-6,
            "ten turbines make the drawer's rate");
        assertEquals(
            10 * fuelPerSecond,
            result.nodeBalances()
                .get(maker.id)
                .operations(),
            1e-6,
            "and burn ten turbines' exact fuel");
    }

    @Test
    void aGeneratorPostsAsTheWebsitesPowerCard() throws IOException {
        final Graph g = new Graph("power");
        final Map<String, String> settings = Map.of("tier", "MV", "fuel", "Benzene");
        final Node gen = generator("gas-turbine", settings);
        g.addNode(gen);
        final Drawer power = new Drawer(Drawer.Kind.PRODUCT, Energy.KEY);
        power.setTarget(Drawer.Rule.AT_LEAST, 2000);
        g.addDrawer(power);
        g.linkDrawer(power.getId(), new Drawer.Link(gen.id, 0));

        final JsonObject p = PlanExport.project(g, "Power test", new PlanExport.World() {

            @Override
            public PlanExport.Res port(final Port<?> port) {
                return new PlanExport.Res("fluid", port.getDisplayName(), port.getDisplayName());
            }

            @Override
            public PlanExport.Res resource(final String key) {
                return new PlanExport.Res("item", "test:" + key, key);
            }

            @Override
            public String machine(final Node node) {
                return node.machineName;
            }

            @Override
            public double machines(final Node node) {
                return 3;
            }

            @Override
            public String tier(final Node node) {
                return "LV";
            }
        });

        final JsonObject recipe = p.getAsJsonArray("recipes")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            "custom",
            recipe.get("kind")
                .getAsString());
        assertEquals(
            "power-source",
            recipe.get("category")
                .getAsString());
        assertEquals(
            20,
            recipe.get("durationTicks")
                .getAsInt());
        assertEquals(
            "gas-turbine",
            recipe.getAsJsonObject("power")
                .get("sourceId")
                .getAsString());
        final JsonObject eu = recipe.getAsJsonArray("outputs")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            "power",
            eu.get("kind")
                .getAsString());
        assertEquals(
            gen.powerModel.euPerTick() * 20,
            eu.get("amount")
                .getAsDouble(),
            1e-9);
        assertTrue(
            eu.get("byproduct")
                .getAsBoolean());
        final JsonObject fuel = recipe.getAsJsonArray("inputs")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            gen.powerModel.inputs()
                .get(0)
                .perSecond(),
            fuel.get("amount")
                .getAsDouble(),
            1e-12,
            "the exact rate, not a whole number");
        final JsonObject node = p.getAsJsonArray("nodes")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            "MV",
            node.getAsJsonObject("machineConfigTiers")
                .get("tier")
                .getAsString());
        final JsonArray storages = p.getAsJsonArray("storages");
        assertEquals(
            "power",
            storages.get(0)
                .getAsJsonObject()
                .get("kind")
                .getAsString());
        // For running past the website's own schema by hand (scratchpad validate-export.ts).
        final Path out = Path.of("build", "tmp", "gtnhplanner-power-export.json");
        Files.createDirectories(out.getParent());
        Files.writeString(
            out,
            new GsonBuilder().setPrettyPrinting()
                .create()
                .toJson(p),
            StandardCharsets.UTF_8);
    }
}
