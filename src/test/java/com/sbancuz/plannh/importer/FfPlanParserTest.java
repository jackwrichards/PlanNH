package com.sbancuz.plannh.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.importer.FfPlan.FfEdge;
import com.sbancuz.plannh.importer.FfPlan.FfNode;
import com.sbancuz.plannh.importer.FfPlan.FfRecipe;
import com.sbancuz.plannh.importer.FfPlan.FfStorage;

/**
 * Reading Factory Flow's plan JSON: the fixtures are FF's own test plans (src/lib/solver/__fixtures__,
 * src/components/flow/__fixtures__, examples/), with icons, NEI layouts and overclock tables trimmed away.
 */
class FfPlanParserTest {

    @Test
    void readsTheSmallReactorPlan() {
        final FfPlan plan = FfPlanParser.parse(FakeGame.fixture("high-amperage-reactor.json"));
        assertEquals("Benzene", plan.name());
        assertFalse(plan.solveMode());
        assertEquals(
            1,
            plan.recipes()
                .size());
        assertEquals(
            1,
            plan.nodes()
                .size());
        assertEquals(
            3,
            plan.edges()
                .size());
        assertEquals(
            3,
            plan.storages()
                .size());

        final FfRecipe recipe = plan.recipes()
            .getFirst();
        assertEquals("gt.recipe.largechemicalreactor", recipe.mapId());
        assertEquals("gt-recipe-largechemicalreactor", recipe.mapSlug());
        assertEquals(80, recipe.durationTicks());
        assertEquals(30, recipe.eut());
        assertEquals(0, recipe.specialValue());
        assertEquals(22, recipe.circuit());
        assertFalse(
            recipe.inputs()
                .getFirst()
                .consumed(),
            "the circuit is kept, not used up");
        assertTrue(
            recipe.inputs()
                .get(1)
                .consumed());
        assertEquals(
            "large-chemical-reactor",
            recipe.handler(null)
                .id(),
            "no pick means the first machine");
        assertTrue(
            recipe.handler("mega-chemical-reactor")
                .multiblock());

        final FfNode node = plan.nodes()
            .getFirst();
        assertEquals("UV", node.hatchVoltageTier());
        assertEquals(16, node.hatchAmps());
        assertEquals("amps", node.powerInputMode());
        assertEquals(
            2,
            node.overrides()
                .size());
        assertEquals(600, node.x());
        assertEquals(-740, node.y());
        assertTrue(node.enabled());
    }

    @Test
    void readsSlimmedRecipesWithoutMetadata() {
        final FfPlan plan = FfPlanParser.parse(FakeGame.fixture("titanium-line-chembath.slim.json"));
        assertEquals(
            19,
            plan.recipes()
                .size());
        assertEquals(
            10,
            plan.nodes()
                .size());
        assertEquals(
            31,
            plan.edges()
                .size());
        assertEquals(
            14,
            plan.storages()
                .size());

        final FfRecipe freezer = plan.recipesById()
            .get("oracle:local-2.9.0-beta-2:gregtech:gt-recipe-vacuumfreezer:fc038b6c9cec5426");
        assertNull(freezer.mapId(), "the slim plan dropped metadata and source");
        assertEquals("gt-recipe-vacuumfreezer", freezer.mapSlug(), "but the recipe id still names the map");

        final FfRecipe salt = plan.recipesById()
            .get("oracle:local-2.9.0-beta-2:gregtech:gt-recipe-electrolyzer:2211d2a81ffdca26");
        assertEquals(
            List.of("gregtech:gt.metaitem.01@2817", "harvestcraft:saltitem"),
            salt.inputs()
                .getFirst()
                .ids());

        final FfRecipe custom = plan.recipesById()
            .get("recipe-bf976b72-ae80-4db5-827d-551e6203dabe");
        assertTrue(custom.isCustomRate());
        assertNull(custom.mapSlug());
    }

    @Test
    void readsSharedMachineSectionsAndConfig() {
        final FfPlan plan = FfPlanParser.parse(FakeGame.fixture("alumina-line-shared-reactor.json"));
        final FfNode reactor = plan.nodes()
            .stream()
            .filter(
                n -> n.id()
                    .equals("node-5f49041d-0bc1-4ef6-8cea-ad435b3d2add"))
            .findFirst()
            .orElseThrow();
        assertEquals(
            3,
            reactor.extraRecipes()
                .size());
        assertEquals(List.of("water"), reactor.hatchSupplies());
        final FfNode mixer = plan.nodes()
            .stream()
            .filter(
                n -> n.id()
                    .equals("node-50c62806-d57e-4285-bd8b-1057bd0d3b07"))
            .findFirst()
            .orElseThrow();
        assertEquals(
            "quantium",
            mixer.machineConfigTiers()
                .get("itemPipeCasing"));
        assertEquals("industrial-mixing-machine", mixer.machineHandlerId());
    }

    @Test
    void readsLegacyWiresWithoutHandles() {
        final FfPlan plan = FfPlanParser.parse(FakeGame.fixture("biodiesel-demo.json"));
        assertEquals(
            7,
            plan.nodes()
                .size());
        final FfEdge first = plan.edges()
            .getFirst();
        assertNull(first.sourceHandle());
        assertEquals("item", first.resourceKind());
        assertEquals("fish", first.resourceId());
        final FfNode biodiesel = plan.nodes()
            .stream()
            .filter(
                n -> n.id()
                    .equals("node-biodiesel"))
            .findFirst()
            .orElseThrow();
        assertNotNull(biodiesel.targetOutput());
        assertEquals(
            100,
            biodiesel.targetOutput()
                .perSecond());
        assertEquals("DEMO", biodiesel.overclockTier());
        assertNotNull(plan.targetRate(), "the legacy plan target");
        assertEquals(
            "byproduct",
            plan.storages()
                .getFirst()
                .drainMode());
    }

    @Test
    void isLenientAboutUnknownKeysWrongTypesAndBrokenEntries() {
        final FfPlan plan = FfPlanParser.parse("""
            {"schemaVersion": 2, "name": "Odd", "somethingNew": {"x": 1},
             "recipes": [
               {"id": "r1", "name": "R", "machineType": "Mixer", "durationTicks": "100", "eut": 30,
                "inputs": [{"kind": "item", "id": "a:b", "amount": 2, "future": true}, {"kind": "item"}],
                "outputs": [{"kind": "fluid", "id": "water", "amount": 1000, "chance": 0.5}]},
               {"name": "no id"}, 7],
             "nodes": [
               {"id": "n1", "recipeId": "r1", "machineCount": "lots", "parallel": 2.6, "overclockTier": "OpV",
                "position": {"x": 10}},
               {"id": "n2"}],
             "edges": [{"id": "e1", "source": "n1"}, "junk"],
             "storages": [{"id": "s1", "resourceId": "water", "targetPerSecond": -5, "position": {"x": 1, "y": 2}}],
             "annotations": [{"id": "a1"}]}
            """);
        assertEquals("Odd", plan.name());
        assertEquals(
            1,
            plan.recipes()
                .size());
        final FfRecipe r = plan.recipes()
            .getFirst();
        assertEquals(100, r.durationTicks(), "a number written as a string still reads");
        assertEquals(
            1,
            r.inputs()
                .size(),
            "a slot with no id is skipped");
        assertEquals(
            0.5,
            r.outputs()
                .getFirst()
                .chance());
        assertEquals(
            1,
            plan.nodes()
                .size());
        final FfNode n = plan.nodes()
            .getFirst();
        assertEquals(1, n.machineCount(), "an unreadable count is FF's default");
        assertEquals(3, n.parallel(), "FF rounds parallels");
        assertEquals("UXV", n.overclockTier(), "FF renamed the OpV tier");
        assertEquals(10, n.x());
        assertEquals(0, n.y());
        assertTrue(n.enabled());
        assertTrue(
            plan.edges()
                .isEmpty(),
            "a wire with no target is skipped");
        assertEquals(
            -5,
            plan.storages()
                .getFirst()
                .targetPerSecond());
        assertTrue(
            plan.notes()
                .stream()
                .anyMatch(s -> s.contains("format 2")));
        assertTrue(
            plan.notes()
                .stream()
                .anyMatch(s -> s.contains("1 note")));
    }

    @Test
    void poolModeImpliesSolveMode() {
        final FfPlan plan = FfPlanParser.parse("""
            {"name": "P", "poolMode": true, "recipes": [], "nodes": [], "edges": []}""");
        assertTrue(plan.poolMode());
        assertTrue(plan.solveMode());
    }

    @Test
    void turnsLegacyTrashCansIntoTrashDrawers() {
        final FfPlan plan = FfPlanParser.parse("""
            {"name": "T",
             "recipes": [
               {"id": "maker", "name": "Maker", "machineType": "Mixer", "durationTicks": 20, "eut": 0, "inputs": [],
                "outputs": [{"kind": "item", "id": "a:dust", "amount": 1, "displayName": "Dust"},
                            {"kind": "fluid", "id": "steam", "amount": 10}]},
               {"id": "can", "name": "Trash Can", "machineType": "Trash Can", "durationTicks": 20, "eut": 0,
                "inputs": [], "outputs": []}],
             "nodes": [
               {"id": "m", "recipeId": "maker", "machineCount": 1, "parallel": 1, "overclockTier": "LV",
                "enabled": true, "position": {"x": 0, "y": 0}},
               {"id": "c", "recipeId": "can", "machineCount": 1, "parallel": 1, "overclockTier": "NONE",
                "enabled": true, "position": {"x": 400, "y": 40}}],
             "edges": [
               {"id": "e1", "source": "m", "target": "c", "sourceHandle": "output:item:a%3Adust",
                "resourceKind": "item", "resourceId": "a:dust"},
               {"id": "e2", "source": "m", "target": "c", "sourceHandle": "output:fluid:steam",
                "resourceKind": "fluid", "resourceId": "steam"}]}
            """);
        assertEquals(
            1,
            plan.nodes()
                .size(),
            "the can is gone");
        assertEquals(
            1,
            plan.recipes()
                .size(),
            "and its recipe");
        assertEquals(
            2,
            plan.storages()
                .size(),
            "one trash drawer per resource");
        final FfStorage dust = plan.storages()
            .getFirst();
        assertEquals("trash", dust.drainMode());
        assertEquals("a:dust", dust.resourceId());
        assertEquals("Dust", dust.displayName(), "named after the feeder's output");
        assertEquals(400, dust.x());
        assertEquals(
            140,
            plan.storages()
                .get(1)
                .y(),
            "the second steps down a tile");
        assertEquals(
            2,
            plan.edges()
                .size());
        assertEquals(
            dust.id(),
            plan.edges()
                .getFirst()
                .target());
        final FfHandle h = FfHandle.parse(
            plan.edges()
                .getFirst()
                .targetHandle());
        assertNotNull(h);
        assertEquals("a:dust", h.resourceId());
        assertTrue(
            plan.notes()
                .stream()
                .anyMatch(s -> s.contains("trash can")));
    }

    @Test
    void flattensBoardsIntoAbsolutePositions() {
        final FfPlan plan = FfPlanParser.parse(
            """
                {"name": "B",
                 "pockets": [
                   {"id": "outer", "name": "Outer", "position": {"x": 1000, "y": 500}, "size": {"width": 800, "height": 600}},
                   {"id": "inner", "name": "Inner", "parentPocketId": "outer", "position": {"x": 100, "y": 60},
                    "size": {"width": 400, "height": 300}},
                   {"id": "legacy", "name": "Old", "position": {"x": 9000, "y": 9000}}],
                 "recipes": [],
                 "nodes": [
                   {"id": "a", "recipeId": "r", "pocketId": "inner", "position": {"x": 20, "y": 40}},
                   {"id": "b", "recipeId": "r", "pocketId": "legacy", "position": {"x": 20, "y": 40}},
                   {"id": "c", "recipeId": "r", "pocketId": "gone", "position": {"x": 20, "y": 40}}],
                 "storages": [{"id": "s", "resourceId": "water", "pocketId": "outer", "position": {"x": 0, "y": 0}}],
                 "edges": []}
                """);
        assertEquals(
            1120,
            plan.nodes()
                .getFirst()
                .x(),
            "every sized frame on the way up adds its corner");
        assertEquals(
            600,
            plan.nodes()
                .getFirst()
                .y());
        assertEquals(
            20,
            plan.nodes()
                .get(1)
                .x(),
            "a board that never stood open keeps its members' coordinates");
        assertEquals(
            20,
            plan.nodes()
                .get(2)
                .x(),
            "a missing board is the root board");
        assertEquals(
            1000,
            plan.storages()
                .getFirst()
                .x());
        assertTrue(
            plan.notes()
                .stream()
                .anyMatch(s -> s.contains("3 boards")));
    }

    @Test
    void refusesWhatIsNotAPlan() {
        assertThrows(FfImportException.class, () -> FfPlanParser.parse("{}"));
        assertThrows(FfImportException.class, () -> FfPlanParser.parse("[1, 2]"));
        assertThrows(FfImportException.class, () -> FfPlanParser.parse("{\"recipes\": "));
        assertThrows(FfImportException.class, () -> FfImport.read("hello"));
    }
}
