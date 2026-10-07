package com.gtnhplanner.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.importer.FfPlan.FfRecipe;
import com.gtnhplanner.importer.FfPlan.FfSlot;
import com.gtnhplanner.importer.RecipeIndex.GameRecipe;
import com.gtnhplanner.importer.RecipeIndex.GameStack;
import com.gtnhplanner.importer.RecipeMatcher.Grade;
import com.gtnhplanner.importer.RecipeMatcher.Match;

/** Matching FF recipes to in-game ones by what they do (FF's recipe-ref-match.ts). */
class RecipeMatcherTest {

    // region Builders

    private static FfSlot in(final String kind, final String id, final double amount, final String... alternatives) {
        return new FfSlot(kind, id, amount, 1, true, List.of(alternatives), id);
    }

    private static FfSlot kept(final String id) {
        return new FfSlot("item", id, 1, 1, false, List.of(), id);
    }

    private static FfSlot out(final String kind, final String id, final double amount) {
        return new FfSlot(kind, id, amount, 1, true, List.of(), id);
    }

    private static FfRecipe ref(final List<FfSlot> inputs, final List<FfSlot> outputs, final int ticks,
        final double eut, @Nullable final Double special, @Nullable final String circuit) {
        return new FfRecipe(
            "ff",
            "Plan recipe",
            "gregtech_machine",
            "gregtech",
            "Chemical Reactor",
            "LV",
            ticks,
            eut,
            special,
            circuit,
            inputs,
            outputs,
            List.of(),
            "gt.recipe.chemicalreactor",
            null,
            false);
    }

    private static GameStack stack(final String kind, final String id, final double amount) {
        return new GameStack(kind, List.of(id), amount, 1, true);
    }

    private static GameStack keptStack(final String id) {
        return new GameStack("item", List.of(id), 1, 1, false);
    }

    private static GameRecipe game(final String name, final List<GameStack> inputs, final List<GameStack> outputs,
        @Nullable final Integer ticks, @Nullable final Long eut, @Nullable final Integer special) {
        return new GameRecipe(name, 0, name, inputs, outputs, ticks, eut, special);
    }

    // endregion

    /** 2 dust + 1000 L water, circuit 1, makes 1000 L acid in 40 ticks at 30 EU/t. */
    private static final FfRecipe ACID = ref(
        List.of(
            kept("gregtech:gt.integrated_circuit@1"),
            in("item", "gregtech:gt.metaitem.01@2022", 2),
            in("fluid", "water", 1000)),
        List.of(out("fluid", "sulfuricacid", 1000)),
        40,
        30,
        0.0,
        "1");

    private static GameRecipe acid(final String name, final double water, final int ticks, final int circuit) {
        return game(
            name,
            List.of(
                keptStack("gregtech:gt.integrated_circuit@" + circuit),
                stack("item", "gregtech:gt.metaitem.01@2022", 2),
                stack("fluid", "water", water)),
            List.of(stack("fluid", "sulfuricacid", 1000)),
            ticks,
            30L,
            0);
    }

    @Test
    void theSameRecipeIsAnExactMatch() {
        final GameRecipe same = acid("same", 1000, 40, 1);
        final Match m = RecipeMatcher.match(ACID, Map.of(), List.of(same));
        assertEquals(Grade.EXACT, m.grade());
        assertSame(same, m.recipe());
        assertTrue(m.accepted());
        assertEquals("", m.detail());
    }

    @Test
    void differentTimingIsTakenWithAWarning() {
        final Match m = RecipeMatcher.match(ACID, Map.of(), List.of(acid("slow", 1000, 80, 1)));
        assertEquals(Grade.SAME_RESOURCES, m.grade());
        assertTrue(m.accepted());
        assertTrue(
            m.detail()
                .contains("80 ticks in game, 40 in the plan"),
            m.detail());
    }

    @Test
    void differentAmountsAreTakenWithAWarning() {
        final Match m = RecipeMatcher.match(ACID, Map.of(), List.of(acid("thirsty", 2000, 40, 1)));
        assertEquals(Grade.SAME_RESOURCES, m.grade());
        assertTrue(
            m.detail()
                .contains("takes 2000 water in game, 1000 in the plan"),
            m.detail());
    }

    @Test
    void anExactMatchBeatsAFuzzyOneWhereverItIs() {
        final GameRecipe exact = acid("exact", 1000, 40, 1);
        final Match m = RecipeMatcher.match(ACID, Map.of(), List.of(acid("slow", 1000, 80, 1), exact));
        assertSame(exact, m.recipe());
    }

    @Test
    void otherResourcesAreRefusedWithTheClosestNamed() {
        final GameRecipe other = game(
            "Chemical Reactor",
            List.of(stack("item", "gregtech:gt.metaitem.01@2022", 2), stack("fluid", "oxygen", 1000)),
            List.of(stack("fluid", "sulfuricacid", 1000)),
            40,
            30L,
            0);
        final Match m = RecipeMatcher.match(ACID, Map.of(), List.of(other));
        assertEquals(Grade.NONE, m.grade());
        assertFalse(m.accepted());
        assertNull(m.recipe());
        assertEquals(1, m.candidates());
        assertTrue(
            m.detail()
                .contains("lacks water"),
            m.detail());
        assertTrue(
            m.detail()
                .contains("has oxygen"),
            m.detail());

        final Match none = RecipeMatcher.match(ACID, Map.of(), List.of());
        assertFalse(none.accepted());
        assertEquals("", none.detail(), "no candidates: the index says why");
    }

    @Test
    void keptInputsDoNotCountButTheCircuitBreaksTies() {
        final GameRecipe two = acid("circuit 2", 1000, 40, 2);
        final GameRecipe one = acid("circuit 1", 1000, 40, 1);
        assertSame(
            one,
            RecipeMatcher.match(ACID, Map.of(), List.of(two, one))
                .recipe(),
            "the plan's circuit wins");
        final GameRecipe noCircuit = game(
            "bare",
            List.of(stack("item", "gregtech:gt.metaitem.01@2022", 2), stack("fluid", "water", 1000)),
            List.of(stack("fluid", "sulfuricacid", 1000)),
            40,
            30L,
            0);
        assertEquals(
            Grade.EXACT,
            RecipeMatcher.match(ACID, Map.of(), List.of(noCircuit))
                .grade(),
            "a circuit is not something the recipe takes");
        assertSame(
            two,
            RecipeMatcher.match(ACID, Map.of(), List.of(two, acid("again", 1000, 40, 2)))
                .recipe(),
            "ties keep the first");
    }

    @Test
    void wildcardMetaMatchesAnyMeta() {
        final FfRecipe dye = ref(
            List.of(in("item", "minecraft:wool@32767", 1)),
            List.of(out("item", "minecraft:string", 4)),
            100,
            2,
            null,
            null);
        final GameRecipe red = game(
            "Macerator",
            List.of(stack("item", "minecraft:wool@14", 1)),
            List.of(stack("item", "minecraft:string", 4)),
            100,
            2L,
            null);
        assertEquals(
            Grade.EXACT,
            RecipeMatcher.match(dye, Map.of(), List.of(red))
                .grade());

        final FfRecipe white = ref(
            List.of(in("item", "minecraft:wool", 1)),
            List.of(out("item", "minecraft:string", 4)),
            100,
            2,
            null,
            null);
        final GameRecipe any = game(
            "Macerator",
            List.of(stack("item", "minecraft:wool@32767", 1)),
            List.of(stack("item", "minecraft:string", 4)),
            100,
            2L,
            null);
        assertEquals(
            Grade.EXACT,
            RecipeMatcher.match(white, Map.of(), List.of(any))
                .grade(),
            "either side may be wild");
    }

    @Test
    void idsCompareIgnoringCase() {
        final FfRecipe cells = ref(
            List.of(in("item", "ic2:itemcellempty", 1)),
            List.of(out("item", "gregtech:gt.metaitem.01@30013", 1)),
            16,
            1,
            null,
            null);
        final GameRecipe game = game(
            "Canner",
            List.of(stack("item", "IC2:itemCellEmpty", 1)),
            List.of(stack("item", "gregtech:gt.metaitem.01@30013", 1)),
            16,
            1L,
            null);
        assertEquals(
            Grade.EXACT,
            RecipeMatcher.match(cells, Map.of(), List.of(game))
                .grade());
    }

    @Test
    void alternativesCountAsTheirSlot() {
        final FfRecipe salt = ref(
            List.of(
                in("item", "gregtech:gt.metaitem.01@2817", 2, "gregtech:gt.metaitem.01@2817", "harvestcraft:saltitem")),
            List.of(out("item", "gregtech:gt.metaitem.01@2017", 1), out("fluid", "chlorine", 1000)),
            320,
            30,
            0.0,
            null);
        final GameRecipe pam = game(
            "Electrolyzer",
            List.of(stack("item", "harvestcraft:saltitem", 2)),
            List.of(stack("item", "gregtech:gt.metaitem.01@2017", 1), stack("fluid", "chlorine", 1000)),
            320,
            30L,
            0);
        assertEquals(
            Grade.EXACT,
            RecipeMatcher.match(salt, Map.of(), List.of(pam))
                .grade());
    }

    @Test
    void theCardsPickChoosesAmongAlternatives() {
        // The circuit assembler's solder slot: 72 L soldering alloy, or 144 L tin; the game has one recipe for each.
        final FfRecipe circuit = ref(
            List.of(
                in("item", "gregtech:gt.metaitem.03@32100", 1),
                in("fluid", "molten.solderingalloy", 72, "molten.tin")),
            List.of(out("item", "gregtech:gt.metaitem.03@32700", 4)),
            200,
            30,
            0.0,
            null);
        final GameRecipe alloy = game(
            "alloy",
            List.of(stack("item", "gregtech:gt.metaitem.03@32100", 1), stack("fluid", "molten.solderingalloy", 72)),
            List.of(stack("item", "gregtech:gt.metaitem.03@32700", 4)),
            200,
            30L,
            0);
        final GameRecipe tin = game(
            "tin",
            List.of(stack("item", "gregtech:gt.metaitem.03@32100", 1), stack("fluid", "molten.tin", 144)),
            List.of(stack("item", "gregtech:gt.metaitem.03@32700", 4)),
            200,
            30L,
            0);
        assertSame(
            alloy,
            RecipeMatcher.match(circuit, Map.of(), List.of(tin, alloy))
                .recipe());
        final FfSlot pick = new FfSlot("fluid", "molten.tin", 144, 1, true, List.of(), "Molten Tin");
        final Match picked = RecipeMatcher.match(circuit, Map.of(1, pick), List.of(alloy, tin));
        assertSame(tin, picked.recipe(), "the card picked tin");
        assertEquals(Grade.EXACT, picked.grade());
    }

    @Test
    void oreDictionarySlotsMergeLikePorts() {
        final FfRecipe bucket = ref(
            List.of(
                in("item", "oredict:ingotiron", 1),
                in("item", "oredict:ingotiron", 1),
                in("item", "oredict:ingotiron", 1)),
            List.of(out("item", "minecraft:bucket", 1)),
            1,
            0,
            null,
            null);
        final GameStack iron = new GameStack("item", List.of("minecraft:iron_ingot", "oredict:ingotiron"), 1, 1, true);
        final GameRecipe crafting = game(
            "Shaped Crafting",
            List.of(iron, iron, iron),
            List.of(stack("item", "minecraft:bucket", 1)),
            null,
            null,
            null);
        final Match m = RecipeMatcher.match(bucket, Map.of(), List.of(crafting));
        assertEquals(Grade.EXACT, m.grade(), "a crafting grid has no timing to disagree with: " + m.detail());
    }

    @Test
    void outputsSplitOverSlotsAddUp() {
        final FfRecipe alumina = ref(
            List.of(in("fluid", "slurry", 8000)),
            List.of(out("item", "gregtech:gt.metaitem.01@2697", 64), out("item", "gregtech:gt.metaitem.01@2697", 16)),
            300,
            480,
            0.0,
            null);
        final GameRecipe game = game(
            "LCR",
            List.of(stack("fluid", "slurry", 8000)),
            List.of(stack("item", "gregtech:gt.metaitem.01@2697", 80)),
            300,
            480L,
            0);
        assertEquals(
            Grade.EXACT,
            RecipeMatcher.match(alumina, Map.of(), List.of(game))
                .grade());
    }

    @Test
    void aDifferentSpecialValueIsAWarning() {
        final FfRecipe hot = ref(
            List.of(in("item", "a:dust", 1)),
            List.of(out("item", "a:ingot", 1)),
            800,
            480,
            2140.0,
            null);
        final GameRecipe cooler = game(
            "EBF",
            List.of(stack("item", "a:dust", 1)),
            List.of(stack("item", "a:ingot", 1)),
            800,
            480L,
            1700);
        final Match m = RecipeMatcher.match(hot, Map.of(), List.of(cooler));
        assertEquals(Grade.SAME_RESOURCES, m.grade());
        assertTrue(
            m.detail()
                .contains("special value 1700 in game, 2140"),
            m.detail());
    }

    @Test
    void aMapWithNothingMakingTheOutputSaysSo() {
        final GameRecipe nitric = game(
            "Chemical Reactor",
            List.of(stack("fluid", "water", 1000)),
            List.of(stack("fluid", "nitricacid", 1000)),
            40,
            30L,
            0);
        final Match m = RecipeMatcher.match(ACID, Map.of(), List.of(nitric, nitric));
        assertFalse(m.accepted());
        assertEquals(2, m.candidates());
        assertEquals("none of the 2 in-game recipes makes sulfuricacid", m.detail());
    }
}
