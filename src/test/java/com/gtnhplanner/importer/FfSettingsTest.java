package com.gtnhplanner.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.importer.FfPlan.FfHandler;
import com.gtnhplanner.importer.FfPlan.FfNode;
import com.gtnhplanner.importer.FfPlan.FfRecipe;

/** FF card settings as GTNH Planner machine settings. */
class FfSettingsTest {

    private static final FfRecipe SINGLE = recipe(new FfHandler("ebf", "Chemical Reactor", "single", false));
    private static final FfRecipe MULTI = recipe(
        new FfHandler("lcr", "Large Chemical Reactor", "multiblock", true),
        new FfHandler("mega", "Mega Chemical Reactor", "multiblock", false));

    private static FfRecipe recipe(final FfHandler... handlers) {
        return new FfRecipe(
            "r",
            "R",
            "gregtech_machine",
            "gregtech",
            "Chemical Reactor",
            "LV",
            100,
            30,
            0.0,
            null,
            List.of(),
            List.of(),
            List.of(handlers),
            "gt.recipe.chemicalreactor",
            null,
            false);
    }

    /** A card with FF's defaults; the arguments are what the tests vary. */
    private static FfNode card(final String tier, @Nullable final String hatchTier, @Nullable final Double amps,
        @Nullable final String mode, @Nullable final Double budget, final int hatches, @Nullable final String handler) {
        return card(tier, hatchTier, amps, mode, budget, hatches, handler, null, 1, Map.of(), 1, null);
    }

    private static FfNode card(final String tier, @Nullable final String hatchTier, @Nullable final Double amps,
        @Nullable final String mode, @Nullable final Double budget, final int hatches, @Nullable final String handler,
        @Nullable final String coil, final int parallel, final Map<String, String> config, final double count,
        @Nullable final Double pin) {
        return new FfNode(
            "n",
            "r",
            count,
            parallel,
            tier,
            hatches,
            null,
            budget,
            hatchTier,
            amps,
            mode,
            handler,
            coil,
            config,
            Map.of(),
            List.of(),
            pin,
            null,
            true,
            0,
            0,
            null,
            null,
            List.of());
    }

    @Test
    void aMultiblockRunsOnItsHatches() {
        final FfSettings.Mapped m = FfSettings
            .map(card("LV", "UV", 16.0, "amps", 8388608.0, 0, null), MULTI, 30, false, true);
        assertEquals(
            "UV",
            m.settings()
                .get("voltage"));
        assertEquals(
            16,
            m.settings()
                .get("amp"));
        assertEquals(
            true,
            m.settings()
                .get("gt_multiblock"));
        assertEquals(
            true,
            m.settings()
                .get("perfect_oc"),
            "the Large Chemical Reactor overclocks perfectly");
        assertEquals("Large Chemical Reactor", m.machineLabel());
        assertTrue(
            m.notes()
                .isEmpty(),
            m.notes()
                .toString());
    }

    @Test
    void moreThan64AmpsBecomeHigherTiersAtTheSamePower() {
        final FfSettings.Mapped m = FfSettings
            .map(card("LV", "LuV", 256.0, "amps", null, 0, "mega"), MULTI, 30, false, true);
        assertEquals(
            "ZPM",
            m.settings()
                .get("voltage"));
        assertEquals(
            64,
            m.settings()
                .get("amp"));
        assertFalse(
            m.settings()
                .containsKey("perfect_oc"),
            "the Mega reactor's handler does not say perfect");
        assertEquals("Mega Chemical Reactor", m.machineLabel());
        assertEquals(
            1,
            m.notes()
                .size());
        assertTrue(
            m.notes()
                .getFirst()
                .contains("256 A LuV"));
    }

    @Test
    void aRawPowerBudgetIsHatchesOfTheTierThatFits() {
        final FfSettings.Mapped m = FfSettings
            .map(card("LV", null, null, "eut", 6000.0, 0, null), MULTI, 30, false, true);
        assertEquals(
            "EV",
            m.settings()
                .get("voltage"));
        assertEquals(
            3,
            m.settings()
                .get("amp"),
            "2.93 A rounds to 3");
        assertTrue(
            m.notes()
                .getFirst()
                .contains("carried as 3 A EV"));
    }

    @Test
    void aLegacyMultiblockCountsItsHatches() {
        final FfSettings.Mapped one = FfSettings
            .map(card("HV", null, null, null, null, 1, null), MULTI, 30, false, true);
        assertEquals(
            "HV",
            one.settings()
                .get("voltage"));
        assertEquals(
            1,
            one.settings()
                .get("amp"));
        final FfSettings.Mapped two = FfSettings
            .map(card("HV", null, null, null, null, 2, null), MULTI, 30, false, true);
        assertEquals(
            4,
            two.settings()
                .get("amp"),
            "two or more hatches work at 2 A each");
    }

    @Test
    void aSingleBlockRunsAtItsTierButNeverBelowTheRecipes() {
        final FfSettings.Mapped hv = FfSettings
            .map(card("HV", null, null, null, null, 0, null), SINGLE, 30, false, true);
        assertEquals(
            "HV",
            hv.settings()
                .get("voltage"));
        assertFalse(
            hv.settings()
                .containsKey("amp"));
        assertEquals(
            false,
            hv.settings()
                .get("gt_multiblock"),
            "a single block says so, whatever machine the board would pick first");
        final FfSettings.Mapped floored = FfSettings
            .map(card("LV", null, null, null, null, 0, null), SINGLE, 480, false, true);
        assertEquals(
            "HV",
            floored.settings()
                .get("voltage"),
            "480 EU/t is an HV recipe");
        final FfSettings.Mapped demo = FfSettings
            .map(card("DEMO", null, null, null, null, 0, null), SINGLE, 30, false, true);
        assertFalse(
            demo.settings()
                .containsKey("voltage"),
            "not a tier: the board's default stays");
    }

    @Test
    void coilsParallelsAndOptions() {
        final FfSettings.Mapped m = FfSettings.map(
            card("HV", null, null, null, null, 0, null, "kanthal", 4, Map.of("itemPipeCasing", "quantium"), 1, null),
            SINGLE,
            30,
            false,
            true);
        assertEquals(
            2701,
            m.settings()
                .get("machine_heat"));
        assertEquals(
            4,
            m.settings()
                .get("parallels"));
        assertTrue(
            m.notes()
                .stream()
                .anyMatch(n -> n.contains("itemPipeCasing")));
        final FfSettings.Mapped odd = FfSettings.map(
            card("HV", null, null, null, null, 0, null, "unobtainium", 1, Map.of(), 1, null),
            SINGLE,
            30,
            false,
            true);
        assertFalse(
            odd.settings()
                .containsKey("machine_heat"));
        assertTrue(
            odd.notes()
                .getFirst()
                .contains("unobtainium"));
        assertEquals(13501, FfSettings.COIL_HEAT.get("eternal"));
        assertEquals(10801, FfSettings.COIL_HEAT.get("awakened_draconium"));
    }

    @Test
    void solvePlansPinOnlyWhatFactoryFlowPinned() {
        final FfNode pinned = card("HV", null, null, null, null, 0, null, null, 1, Map.of(), 7, 2.5);
        final FfSettings.Mapped p = FfSettings.map(pinned, SINGLE, 30, true, true);
        assertTrue(p.pinned());
        assertEquals(3, p.machines());
        assertTrue(
            p.notes()
                .getFirst()
                .contains("rounded to 3"));

        final FfSettings.Mapped section = FfSettings.map(pinned, SINGLE, 30, true, false);
        assertFalse(section.pinned(), "a shared machine's extra recipe never carries the card's pin");

        final FfNode free = card("HV", null, null, null, null, 0, null, null, 1, Map.of(), 7, null);
        final FfSettings.Mapped f = FfSettings.map(free, SINGLE, 30, true, true);
        assertFalse(f.pinned(), "machine counts are the answer in Solve");
        assertEquals(1, f.machines());
    }

    @Test
    void buildPlansKeepTheirCountsUnpinned() {
        final FfNode built = card("HV", null, null, null, null, 0, null, null, 1, Map.of(), 2.3, 5.0);
        final FfSettings.Mapped m = FfSettings.map(built, SINGLE, 30, false, true);
        assertFalse(m.pinned());
        assertEquals(3, m.machines());
    }

    @Test
    void tiers() {
        assertEquals(1, FfSettings.recipeTier(30));
        assertEquals(1, FfSettings.recipeTier(32));
        assertEquals(2, FfSettings.recipeTier(33));
        assertEquals(0, FfSettings.recipeTier(0));
        assertEquals(4, FfSettings.tierWithin(6000));
        assertEquals(0, FfSettings.tierWithin(3));
        assertEquals(-1, FfSettings.tierIndex("NONE"));
        assertEquals(6, FfSettings.tierIndex("luv"));
        assertEquals(
            "lcr",
            MULTI.handler("nope")
                .id(),
            "an unknown pick falls back to the first machine");
        assertNull(recipe().handler(null), "a recipe with no machines has none to pick");
    }
}
