package com.gtnhplanner.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.importer.ImportReport.Kind;
import com.gtnhplanner.library.PlanExport;

/**
 * Whole FF plans converted against a game whose recipes are the plans' own, so every card matches. The expected
 * counts come from FF's rules applied to the fixtures by hand: cards (a shared machine's recipes each a node), wires
 * between cards (a buffer drawer's feeders wired to its takers), and drawers.
 */
class FfConverterTest {

    private static FfConverter.Result convert(final String json, final FakeGame game) {
        return FfImport.importFromText(json, game, game);
    }

    private static FfConverter.Result fixture(final String name) {
        return convert(FakeGame.fixture(name), new FakeGame());
    }

    private static List<Node> nodes(final Graph g, final String machine) {
        return g.getNodes()
            .stream()
            .filter(n -> n.machineName.equals(machine))
            .collect(Collectors.toList());
    }

    private static Node node(final Graph g, final String machine) {
        final List<Node> found = nodes(g, machine);
        assertEquals(1, found.size(), "one " + machine);
        return found.getFirst();
    }

    @Nullable
    private static Drawer drawer(final Graph g, final String key) {
        for (final Drawer d : g.getDrawers()) if (d.getResourceKey()
            .equals(key)) return d;
        return null;
    }

    private static List<Edge> edgesInto(final Graph g, final Node node, final int input) {
        final List<Edge> out = new ArrayList<>();
        for (final Edge e : g.getEdges()) if (e.targetNodeId.equals(node.id) && e.targetInputIndex == input) out.add(e);
        return out;
    }

    private static boolean has(final ImportReport report, final Kind kind, final String text) {
        return report.entries(kind)
            .stream()
            .anyMatch(
                e -> e.message()
                    .contains(text)
                    || e.subject()
                        .contains(text));
    }

    @Test
    void theSmallReactorPlan() {
        final FakeGame game = new FakeGame();
        final FfConverter.Result r = convert(FakeGame.fixture("high-amperage-reactor.json"), game);
        final Graph g = r.graph();
        assertEquals("Benzene", g.getName());
        assertEquals(1, g.nodes.size());
        assertEquals(0, g.edges.size());
        assertEquals(3, g.drawers.size());

        final Node lcr = node(g, "Large Chemical Reactor");
        assertEquals("UV", lcr.machineConfig.settings.get("voltage"));
        assertEquals(16, lcr.machineConfig.settings.get("amp"));
        assertEquals(true, lcr.machineConfig.settings.get("gt_multiblock"));
        assertEquals(true, lcr.machineConfig.settings.get("perfect_oc"));
        assertEquals(1, lcr.machineConfig.getMachineCount());
        assertTrue(lcr.isMachineCountFixed(), "a Build plan runs its counts: they come over pinned");
        assertEquals("Large Chemical Reactor", game.machines.get(lcr.id));
        assertEquals(2, lcr.inputs.size(), "the circuit is no port");

        final Drawer hydrogen = drawer(g, "fluid:hydrogen");
        assertNotNull(hydrogen);
        assertEquals(Drawer.Kind.SOURCE, hydrogen.getKind());
        assertEquals("Hydrogen", hydrogen.getLabel());
        assertEquals(List.of(new Drawer.Link(lcr.id, game.port(lcr, false, "hydrogen"))), hydrogen.getLinks());
        assertEquals(Drawer.Rule.ANY, hydrogen.getRule(), "Build plans carry no rules");
        final Drawer water = drawer(g, "fluid:ic2distilledwater");
        assertNotNull(water);
        assertEquals(Drawer.Kind.PRODUCT, water.getKind());
        assertEquals(List.of(new Drawer.Link(lcr.id, game.port(lcr, true, "ic2distilledwater"))), water.getLinks());

        // FF's layout, scaled: the hydrogen drawer is leftmost and topmost, the card 180 FF px right of it.
        assertEquals(FfConverter.MARGIN, hydrogen.getX());
        assertEquals(FfConverter.MARGIN, hydrogen.getY());
        assertEquals(FfConverter.MARGIN + 150, lcr.x);
        assertEquals(FfConverter.MARGIN + 10, lcr.y);

        assertTrue(
            r.report()
                .entries(Kind.UNMATCHED)
                .isEmpty());
        assertTrue(has(r.report(), Kind.NOTE, "Build plan"));
        assertEquals(
            "1 card, 0 wires, 3 drawers",
            r.report()
                .summary());
    }

    @Test
    void aBufferDrawerBecomesDirectWires() {
        final FfConverter.Result r = fixture("pa-cell-loop-plan.json");
        final Graph g = r.graph();
        assertEquals(6, g.nodes.size());
        assertEquals(11, g.edges.size(), "7 wires between cards, and 2 feeders x 2 takers through the cell buffer");
        assertEquals(3, g.drawers.size(), "water, hydrogen and oxygen; the cell buffer is gone");
        assertTrue(has(r.report(), Kind.CONVERTED, "4 direct wires"));
        assertEquals(null, drawer(g, "item:ic2:itemcellempty:0"));

        final FakeGame game = new FakeGame();
        final Node electrolyzer = node(g, "Electrolyzer");
        assertEquals(
            2,
            edgesInto(g, electrolyzer, game.port(electrolyzer, false, "ic2:itemcellempty")).size(),
            "both canners that empty cells feed the electrolyzer's cell input");
        final Node acid = nodes(g, "Canner").stream()
            .filter(n -> game.port(n, false, "phosphoricacid_gt5u") >= 0)
            .findFirst()
            .orElseThrow();
        assertEquals(2, edgesInto(g, acid, game.port(acid, false, "ic2:itemcellempty")).size());
        for (final Edge e : g.getEdges()) {
            assertTrue(g.nodes.containsKey(e.sourceNodeId) && g.nodes.containsKey(e.targetNodeId));
        }
    }

    @Test
    void theTitaniumLine() {
        final FfConverter.Result r = fixture("titanium-line-chembath.slim.json");
        final Graph g = r.graph();
        assertEquals(10, g.nodes.size());
        assertEquals(15, g.edges.size());
        assertEquals(13, g.drawers.size(), "14 drawers less the magnesium buffer");
        assertTrue(has(r.report(), Kind.CONVERTED, "1 direct wire"));

        final Node ebf = node(g, "Blast Furnace");
        assertEquals(2701, ebf.machineConfig.settings.get("machine_heat"), "kanthal coils");
        assertEquals("HV", ebf.machineConfig.settings.get("voltage"));
        assertEquals(4, ebf.machineConfig.settings.get("amp"), "two hatches at 2 A");

        final Drawer iron = drawer(g, "item:gregtech:gt.metaitem.01:2032");
        assertNotNull(iron);
        assertEquals(Drawer.Kind.BYPRODUCT, iron.getKind());
        assertEquals(
            3,
            iron.getLinks()
                .size(),
            "fed by the electrolyzer, the centrifuge and the macerator");
        for (final Drawer d : g.getDrawers()) {
            for (final Drawer.Link l : d.getLinks()) {
                final Node n = g.nodes.get(l.nodeId());
                assertNotNull(n);
                final var ports = d.getKind()
                    .linksInputs() ? n.inputs : n.outputs;
                assertEquals(
                    d.getResourceKey(),
                    new FakeGame().describe(ports.get(l.portIndex()))
                        .key(),
                    "a drawer links its own resource");
            }
        }
        assertTrue(
            r.report()
                .entries(Kind.UNMATCHED)
                .isEmpty());
    }

    @Test
    void aSharedMachineStaysOneCardWithItsRecipes() {
        final FfConverter.Result r = fixture("alumina-line-shared-reactor.json");
        final Graph g = r.graph();
        assertEquals(11, g.nodes.size(), "7 cards, one with 1 extra recipe and one with 3");
        assertEquals(12, g.edges.size());
        assertEquals(28, g.drawers.size(), "26 drawers and 2 water hatches");
        // The two shared machines, a reactor of four recipes and one of two, each one card at its place.
        final List<com.gtnhplanner.data.flowchart.MachineGroup> shared = g.getGroups()
            .stream()
            .filter(x -> x instanceof com.gtnhplanner.data.flowchart.MachineGroup)
            .map(x -> (com.gtnhplanner.data.flowchart.MachineGroup) x)
            .sorted(
                java.util.Comparator.comparingInt(
                    x -> x.getSections()
                        .size()))
            .collect(Collectors.toList());
        assertEquals(2, shared.size());
        assertEquals(
            2,
            shared.get(0)
                .getSections()
                .size());
        assertEquals(
            4,
            shared.get(1)
                .getSections()
                .size());
        for (final com.gtnhplanner.data.flowchart.MachineGroup m : shared) {
            final Node host = g.nodes.get(
                m.getSections()
                    .getFirst());
            for (final java.util.UUID id : m.getSections()) {
                assertEquals(host.x, g.nodes.get(id).x, "a section sits where its card does");
                assertEquals(host.y, g.nodes.get(id).y);
                assertFalse(
                    g.nodes.get(id)
                        .isMachineCountFixed(),
                    "the pin is the machine's, not a recipe's");
            }
        }
        assertTrue(has(r.report(), Kind.CONVERTED, "water hatch"));
        assertTrue(has(r.report(), Kind.CONVERTED, "itemPipeCasing"));

        final FakeGame game = new FakeGame();
        final List<Node> reactors = nodes(g, "Large Chemical Reactor");
        assertEquals(6, reactors.size(), "two plain reactors, and the shared one's four recipes");
        // Section 2 of the shared reactor makes aluminium hydroxide for section 0: a wire between two of its recipes.
        final Node hydroxideUser = reactors.stream()
            .filter(n -> game.port(n, false, "gregtech:gt.metaitem.01@2698") >= 0)
            .findFirst()
            .orElseThrow();
        assertEquals(
            1,
            edgesInto(g, hydroxideUser, game.port(hydroxideUser, false, "gregtech:gt.metaitem.01@2698")).size());
        final Edge e = edgesInto(g, hydroxideUser, game.port(hydroxideUser, false, "gregtech:gt.metaitem.01@2698"))
            .getFirst();
        assertFalse(e.sourceNodeId.equals(e.targetNodeId));
        assertTrue(
            shared.get(1)
                .getSections()
                .containsAll(List.of(e.sourceNodeId, e.targetNodeId)),
            "both recipes on the shared reactor");

        final Node ebf = node(g, "Blast Furnace");
        assertEquals(8101, ebf.machineConfig.settings.get("machine_heat"), "naquadah alloy coils");
        assertEquals(2, ebf.machineConfig.getMachineCount(), "Build count kept as the starting count");

        final List<Drawer> waterHatches = g.getDrawers()
            .stream()
            .filter(
                d -> d.getResourceKey()
                    .equals("fluid:water") && d.getKind() == Drawer.Kind.SOURCE)
            .collect(Collectors.toList());
        assertEquals(2, waterHatches.size());
        assertEquals(
            3,
            waterHatches.stream()
                .mapToInt(
                    d -> d.getLinks()
                        .size())
                .sum(),
            "the mixer, and two recipes of the shared reactor");
    }

    @Test
    void aLegacyPlanWithoutHandles() {
        final FfConverter.Result r = fixture("biodiesel-demo.json");
        final Graph g = r.graph();
        assertEquals(7, g.nodes.size());
        assertEquals(7, g.edges.size());
        assertEquals(1, g.drawers.size());
        final FakeGame game = new FakeGame();
        final Node biodiesel = nodes(g, "Chemical Reactor").stream()
            .filter(n -> game.port(n, true, "biodiesel") >= 0)
            .findFirst()
            .orElseThrow();
        assertEquals(100.0, biodiesel.targetOutputRates.get(game.port(biodiesel, true, "biodiesel")));
        assertFalse(biodiesel.machineConfig.settings.containsKey("voltage"), "a DEMO tier is no tier");
        final Node electrolyzer = node(g, "Electrolyzer");
        assertEquals(
            Drawer.Kind.BYPRODUCT,
            g.getDrawers()
                .iterator()
                .next()
                .getKind());
        assertEquals(3, electrolyzer.machineConfig.getMachineCount());
    }

    @Test
    void solvePlansCarryRulesAndPins() {
        final JsonObject plan = new Gson().fromJson(FakeGame.fixture("high-amperage-reactor.json"), JsonObject.class);
        plan.addProperty("solveMode", true);
        plan.getAsJsonArray("nodes")
            .get(0)
            .getAsJsonObject()
            .addProperty("solvePin", 2);
        for (final var e : plan.getAsJsonArray("storages")) {
            final JsonObject s = e.getAsJsonObject();
            switch (s.get("resourceId")
                .getAsString()) {
                case "hydrogen" -> s.addProperty("targetPerSecond", -2000);
                case "oxygen" -> {
                    s.addProperty("targetPerSecond", -1000);
                    s.addProperty("targetMode", "at-most");
                }
                default -> s.addProperty("targetPerSecond", 100);
            }
        }
        final Graph g = convert(plan.toString(), new FakeGame()).graph();
        final Node lcr = g.getNodes()
            .iterator()
            .next();
        assertTrue(lcr.isMachineCountFixed());
        assertEquals(2, lcr.machineConfig.getMachineCount());
        assertEquals(Drawer.Rule.EXACTLY, drawer(g, "fluid:hydrogen").getRule());
        assertEquals(2000, drawer(g, "fluid:hydrogen").getRate());
        assertEquals(Drawer.Rule.AT_MOST, drawer(g, "fluid:oxygen").getRule());
        assertEquals(1000, drawer(g, "fluid:oxygen").getRate());
        assertEquals(Drawer.Rule.AT_LEAST, drawer(g, "fluid:ic2distilledwater").getRule());
        assertEquals(100, drawer(g, "fluid:ic2distilledwater").getRate());
    }

    @Test
    void aCardWithNoRecipeInGameIsLeftOutAndSaysWhy() {
        final FakeGame game = new FakeGame();
        game.missing.add("oracle:local-2.9.0-beta-2:gregtech:gt-recipe-largechemicalreactor:6db988ddcbfcf8e3");
        final FfConverter.Result r = convert(FakeGame.fixture("titanium-line-chembath.slim.json"), game);
        assertEquals(9, r.graph().nodes.size());
        final List<ImportReport.Entry> unmatched = r.report()
            .entries(Kind.UNMATCHED);
        assertEquals(1, unmatched.size());
        assertTrue(
            unmatched.getFirst()
                .subject()
                .startsWith("Large Chemical Reactor"));
        assertEquals(
            "not in this game",
            unmatched.getFirst()
                .message());
        assertTrue(has(r.report(), Kind.WIRE, "5 wires to cards that were left out"));
        assertTrue(
            r.report()
                .summary()
                .contains("1 left out"));
    }

    /** A mixer and a dryer, a power card, a switched-off card and a custom rate card supplying water. */
    private static final String SMALL = """
        {"name": "Mud", %s
         "recipes": [
           {"id": "mix", "name": "Mixer: Mud", "machineType": "Mixer", "durationTicks": 20, "eut": 8,
            "inputs": [{"kind": "item", "id": "a:dust", "amount": 1}, {"kind": "fluid", "id": "water", "amount": 1000}],
            "outputs": [{"kind": "fluid", "id": "mud", "amount": 1000}]},
           {"id": "dry", "name": "Dryer: Clay", "machineType": "Dryer", "durationTicks": 40, "eut": 8,
            "inputs": [{"kind": "fluid", "id": "mud", "amount": 500}],
            "outputs": [{"kind": "item", "id": "a:clay", "amount": 1}]},
           {"id": "gen", "name": "Gas Turbine", "machineType": "Gas Turbine", "durationTicks": 20, "eut": 0,
            "inputs": [], "outputs": [], "power": {"sourceId": "turbine", "euPerTick": 32, "stats": []}},
           {"id": "cr", "name": "Custom Rate: Water", "category": "custom-rate", "machineType": "Custom Rate",
            "durationTicks": 20, "eut": 0, "inputs": [],
            "outputs": [{"kind": "fluid", "id": "water", "amount": 50, "displayName": "Water"}]}],
         "nodes": [
           {"id": "m", "recipeId": "mix", "machineCount": 1, "parallel": 1, "overclockTier": "LV", "enabled": true,
            "position": {"x": 400, "y": 0}},
           {"id": "d", "recipeId": "dry", "machineCount": 1, "parallel": 1, "overclockTier": "LV", "enabled": true,
            "position": {"x": 800, "y": 0}},
           {"id": "g", "recipeId": "gen", "machineCount": 1, "parallel": 1, "overclockTier": "LV", "enabled": true,
            "position": {"x": 800, "y": 400}},
           {"id": "off", "recipeId": "dry", "machineCount": 1, "parallel": 1, "overclockTier": "LV", "enabled": false,
            "position": {"x": 800, "y": 800}},
           {"id": "c", "recipeId": "cr", "machineCount": 2, "parallel": 1, "overclockTier": "NONE", "enabled": true,
            "customRate": {"perSecond": 50, "mode": "supply"}, "position": {"x": 0, "y": 0}}],
         "edges": [%s],
         "storages": [
           {"id": "dust", "kind": "item", "resourceId": "a:dust", "displayName": "Dust", "targetPerSecond": -1,
            "position": {"x": 0, "y": 200}},
           {"id": "clay", "kind": "item", "resourceId": "a:clay", "displayName": "Clay", "targetPerSecond": 2,
            "position": {"x": 1200, "y": 0}}]}
        """;

    private static final String SMALL_WIRES = """
        {"id": "w1", "source": "c", "target": "m", "sourceHandle": "output:fluid:water", "targetHandle": "input:fluid:water",
         "resourceKind": "fluid", "resourceId": "water"},
        {"id": "w2", "source": "m", "target": "d", "sourceHandle": "output:fluid:mud", "targetHandle": "input:fluid:mud",
         "resourceKind": "fluid", "resourceId": "mud"},
        {"id": "w3", "source": "m", "target": "off", "resourceKind": "fluid", "resourceId": "mud"},
        {"id": "w4", "source": "dust", "target": "m", "resourceKind": "item", "resourceId": "a:dust"},
        {"id": "w5", "source": "d", "target": "clay", "resourceKind": "item", "resourceId": "a:clay"}""";

    @Test
    void customRateCardsBecomeDrawersAndPowerAndOffCardsGo() {
        final FfConverter.Result r = convert(SMALL.formatted("", SMALL_WIRES), new FakeGame());
        final Graph g = r.graph();
        assertEquals(2, g.nodes.size(), "the mixer and the dryer");
        assertEquals(1, g.edges.size(), "mud, mixer to dryer");
        assertEquals(3, g.drawers.size(), "dust, clay, and the custom rate card's water");
        final Drawer water = drawer(g, "fluid:water");
        assertNotNull(water);
        assertEquals(Drawer.Kind.SOURCE, water.getKind());
        assertEquals(Drawer.Rule.AT_MOST, water.getRule(), "a Build plan's custom rate supplies up to its rate");
        assertEquals(100, water.getRate(), "50/s on each of 2 machines");
        assertEquals(Drawer.Rule.ANY, drawer(g, "item:a:clay:0").getRule(), "Build: no drawer rules");
        // The stand-in game builds no generators; the real one rebuilds the card from its source (PowerCardTest).
        assertTrue(has(r.report(), Kind.UNMATCHED, "generator"));
        assertTrue(has(r.report(), Kind.DROPPED, "switched-off"));
        assertTrue(has(r.report(), Kind.CONVERTED, "source drawer for Water, at most 100/s"));
        assertTrue(has(r.report(), Kind.WIRE, "1 wire to cards that were left out"));
    }

    @Test
    void poolPlansWireEveryMakerToEveryUser() {
        // No wire between the mixer and the dryer: in a pool, sharing a resource is enough.
        final String wires = """
            {"id": "w4", "source": "dust", "target": "m", "resourceKind": "item", "resourceId": "a:dust"},
            {"id": "w5", "source": "d", "target": "clay", "resourceKind": "item", "resourceId": "a:clay"}""";
        final FfConverter.Result r = convert(SMALL.formatted("\"poolMode\": true,", wires), new FakeGame());
        final Graph g = r.graph();
        assertEquals(2, g.nodes.size());
        assertEquals(1, g.edges.size(), "mud runs from the mixer to the dryer");
        final Drawer clay = drawer(g, "item:a:clay:0");
        assertNotNull(clay);
        assertEquals(Drawer.Rule.AT_LEAST, clay.getRule(), "a Pool plan is a Solve plan: rules come over");
        assertEquals(2, clay.getRate());
        final Drawer dust = drawer(g, "item:a:dust:0");
        assertNotNull(dust);
        assertEquals(Drawer.Rule.EXACTLY, dust.getRule());
        assertEquals(1, dust.getRate());
        final Drawer water = drawer(g, "fluid:water");
        assertNotNull(water, "the custom rate card supplies the pool");
        assertEquals(Drawer.Rule.ANY, water.getRule(), "unpinned in Solve: the plan decides how much");
        assertTrue(has(r.report(), Kind.CONVERTED, "Pool plan"));
    }

    /**
     * A mixer between two text notes, one above-left of everything that comes over, and a box further out still, which
     * does not come over and so does not move anything.
     */
    private static final String NOTES = """
        {"name": "Notes",
         "recipes": [
           {"id": "mix", "name": "Mixer: Mud", "machineType": "Mixer", "durationTicks": 20, "eut": 8,
            "inputs": [{"kind": "item", "id": "a:dust", "amount": 1}],
            "outputs": [{"kind": "fluid", "id": "mud", "amount": 1000}]}],
         "nodes": [
           {"id": "m", "recipeId": "mix", "machineCount": 1, "parallel": 1, "overclockTier": "LV", "enabled": true,
            "position": {"x": 400, "y": 200}}],
         "edges": [],
         "annotations": [
           {"id": "t1", "kind": "text", "colorTag": "light_blue", "text": "Mud line\\nfeeds the dryer",
            "position": {"x": 0, "y": 0}, "size": {"width": 240, "height": 80}, "fontSize": 20},
           {"id": "t2", "kind": "text", "text": "small", "position": {"x": 760, "y": 400},
            "size": {"width": 100, "height": 40}},
           {"id": "b1", "kind": "box", "position": {"x": -400, "y": -400}, "size": {"width": 800, "height": 800}}]}
        """;

    private static Note note(final Graph g, final String firstLine) {
        for (final Note n : g.getNotes()) if (!n.getText()
            .isEmpty() && n.getText()
                .getFirst()
                .equals(firstLine))
            return n;
        throw new AssertionError("no note starting " + firstLine);
    }

    @Test
    void textNotesBecomeBoardNotes() {
        final FfConverter.Result r = convert(NOTES, new FakeGame());
        final Graph g = r.graph();
        assertEquals(2, g.notes.size());

        // The first note is the plan's top-left corner, so it sits at the margin and the mixer moves out from it.
        final Note big = note(g, "Mud line");
        assertEquals(List.of("Mud line", "feeds the dryer"), big.getText());
        assertEquals(FfConverter.MARGIN, big.getX());
        assertEquals(FfConverter.MARGIN, big.getY());
        assertEquals(202, big.getWidth(), "240 FF px at 320/380");
        assertEquals(48, big.getHeight(), "80 FF px at 0.6");
        assertEquals("light_blue", big.getColor());
        assertEquals(20, big.getFontSize());

        final Node mixer = node(g, "Mixer");
        assertEquals(FfConverter.MARGIN + 340, mixer.x);
        assertEquals(FfConverter.MARGIN + 120, mixer.y);

        final Note small = note(g, "small");
        assertEquals(FfConverter.MARGIN + 640, small.getX());
        assertEquals(FfConverter.MARGIN + 240, small.getY());
        assertEquals(Note.MIN_W, small.getWidth(), "never smaller than a note gets");
        assertEquals(Note.MIN_H, small.getHeight());
        assertEquals(Note.DEFAULT_COLOR, small.colorTag(), "no colour is FF's yellow");
        assertEquals(Note.DEFAULT_FONT, small.fontSizeOrDefault());

        assertTrue(has(r.report(), Kind.NOTE, "1 box on the board left out"));
        assertEquals(
            "1 card, 0 wires, 0 drawers, 2 notes",
            r.report()
                .summary());
    }

    @Test
    void notesGoToTheWebsiteAndBackUnchanged() {
        final FakeGame game = new FakeGame();
        final Graph first = convert(NOTES, game).graph();
        // The game's side of the export: each port names the FF resource it came from.
        final PlanExport.World world = new PlanExport.World() {

            @Override
            public PlanExport.Res port(final Port<?> port) {
                final NodeMaker.PortInfo info = game.describe(port);
                return new PlanExport.Res(info.kind(), info.label(), info.label());
            }

            @Override
            public PlanExport.Res resource(final String key) {
                return null;
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
        };
        final String posted = PlanExport.project(first, "Notes", world)
            .toString();
        final Graph back = convert(posted, game).graph();

        assertEquals(2, back.notes.size());
        for (final Note was : first.getNotes()) {
            final Note now = note(
                back,
                was.getText()
                    .getFirst());
            assertEquals(was.getText(), now.getText());
            assertEquals(was.colorTag(), now.colorTag());
            assertEquals(was.fontSizeOrDefault(), now.fontSizeOrDefault());
            assertEquals(was.getX(), now.getX(), "the layout comes back as it went");
            assertEquals(was.getY(), now.getY());
        }
        final Note big = note(back, "Mud line");
        assertEquals(202, big.getWidth(), "a size in whole FF cells comes back as it went");
        assertEquals(48, big.getHeight());
        final Node mixer = node(back, "Mixer");
        assertEquals(node(first, "Mixer").x, mixer.x);
        assertEquals(node(first, "Mixer").y, mixer.y);
    }

    @Test
    void aPlanLinkImportsLikeItsJson() {
        final FfConverter.Result r = convert(FakeGame.fixture("high-amperage-reactor.link.txt"), new FakeGame());
        assertEquals(
            "Benzene",
            r.graph()
                .getName());
        assertEquals(
            "1 card, 0 wires, 3 drawers",
            r.report()
                .summary());
    }

    @Test
    void importedNodesAreDistinctAndWiredInsideTheGraph() {
        for (final String name : List.of(
            "high-amperage-reactor.json",
            "pa-cell-loop-plan.json",
            "titanium-line-chembath.slim.json",
            "alumina-line-shared-reactor.json",
            "biodiesel-demo.json")) {
            final Graph g = fixture(name).graph();
            final List<UUID> ids = new ArrayList<>(g.nodes.keySet());
            assertEquals(
                ids.size(),
                ids.stream()
                    .distinct()
                    .count(),
                name);
            for (final Edge e : g.getEdges()) {
                final Node from = g.nodes.get(e.sourceNodeId), to = g.nodes.get(e.targetNodeId);
                assertNotNull(from, name);
                assertNotNull(to, name);
                assertTrue(e.sourceOutputIndex >= 0 && e.sourceOutputIndex < from.outputs.size(), name);
                assertTrue(e.targetInputIndex >= 0 && e.targetInputIndex < to.inputs.size(), name);
                assertTrue(
                    from.outputs.get(e.sourceOutputIndex)
                        .canConnect(to.inputs.get(e.targetInputIndex)),
                    name + ": a wire joins one resource");
            }
            for (final Node n : g.getNodes()) {
                assertTrue(n.x >= FfConverter.MARGIN && n.y >= FfConverter.MARGIN, name);
                assertEquals(0, n.x % 10, name);
            }
        }
    }
}
