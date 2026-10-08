package com.gtnhplanner.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.gtnhplanner.power.PowerModel.Flow;
import com.gtnhplanner.power.sources.Reactors;

/**
 * Reactor and endgame cards where the pack's Java (GT5U 5.09.54.20) and the workbook disagree; each expectation names
 * the class it follows (the website's sources/reactors.test.ts).
 */
class ReactorsTest {

    private static PowerModel compute(final String sourceId, final Map<String, String> settings) {
        final PowerSource source = PowerRegistry.get(sourceId);
        if (source == null) throw new IllegalArgumentException("No power source " + sourceId);
        return source.compute(settings);
    }

    /** The per-second rate of the flow with this name, or null. */
    private static Double flow(final List<Flow> lines, final String name) {
        for (final Flow line : lines) {
            if (line.name()
                .equals(name)) return line.perSecond();
        }
        return null;
    }

    /** vitest's toBeCloseTo(expected, digits): within half a unit of the last digit. */
    private static void assertCloseTo(final double expected, final Double actual, final int digits) {
        assertNotNull(actual);
        assertEquals(expected, actual, 0.5 * Math.pow(10, -digits));
    }

    // ---- Eye of Harmony

    @Test
    @DisplayName("Eye of Harmony: spreads a craft's net EU over its duration in seconds")
    void eohSpreadsNetEuOverDuration() {
        // EyeOfHarmonyRecipeStorage.timeCalculator: T0 runs 18,000 s. The workbook's own net power cell (14. EOH
        // Q10) is -64,360,304,863 EU/t.
        final PowerModel model = compute("eye-of-harmony", Map.of("star", "T0 Overworld"));
        assertCloseTo(1, model.euPerTick() / -64_360_304_863d, 5);
        assertEquals("5h", model.stat("Cycle"));
    }

    @Test
    @DisplayName("Eye of Harmony: consumes 1e9 x (tier + 1) L of hydrogen and helium a craft")
    void eohConsumesHydrogenAndHelium() {
        final PowerModel overworld = compute("eye-of-harmony", Map.of("star", "T0 Overworld"));
        assertCloseTo(1e9 / 18_000, flow(overworld.inputs(), "Hydrogen"), 6);
        assertCloseTo(1e9 / 18_000, flow(overworld.inputs(), "Helium"), 6);
        // The Deep Dark runs at rocket tier 9: 1e10 L over 371,898 s.
        final PowerModel deepDark = compute("eye-of-harmony", Map.of("star", "T10 Deep Dark"));
        assertCloseTo(1e10 / 371_898, flow(deepDark.inputs(), "Hydrogen"), 6);
    }

    // ---- LFTR

    @Test
    @DisplayName("LFTR: leaves TB Salt from LFTR Fuel 2")
    void lftrLeavesTbSalt() {
        // RecipeLoaderLFTR: LiFBeF2ZrF4UF4 -> 50 L U Salt, 100 L LiFBeF2ThF4 (TB Salt).
        final PowerModel model = compute("lftr", Map.of("fuel", "LFTR Fuel 2"));
        assertEquals(1, flow(model.outputs(), "TB-Salt"), 0);
        assertNull(flow(model.outputs(), "T-Salt"));
        assertEquals(0.5, flow(model.outputs(), "U-Salt"), 0);
    }

    // ---- Large Naquadah Reactor

    @Test
    @DisplayName("Large Naquadah Reactor: draws liquid air, coolant and booster ceil(d/20) times a recipe")
    void lnrDrawsPerTwentyTicks() {
        // MTELargeNaquadahReactor draws when progress % 20 == 0: Mk-II's 70-tick recipe draws 4 times in 3.5 s.
        final PowerModel mk2 = compute(
            "large-naquadah-reactor",
            Map.of("fuel", "Naq Fuel Mk-II", "coolant", "Cryotheum", "booster", "Molten Caesium"));
        assertCloseTo((2400 * 4) / 3.5, flow(mk2.inputs(), "Liquid Air"), 9);
        assertCloseTo((1000 * 4) / 3.5, flow(mk2.inputs(), "Cryotheum"), 9);
        assertCloseTo((180 * 4) / 3.5, flow(mk2.inputs(), "Molten Caesium"), 9);
        // The fuel is still 1 L per boost level per recipe.
        assertCloseTo(2 / 3.5, flow(mk2.inputs(), "Naq Fuel Mk-II"), 9);
        // Plutonium: 150 ticks, 8 draws in 7.5 s.
        final PowerModel plutonium = compute("large-naquadah-reactor", Map.of("fuel", "Plutonium Fuel (Excited)"));
        assertCloseTo(2560, flow(plutonium.inputs(), "Liquid Air"), 9);
        // Whole-second recipes draw once a second.
        final PowerModel mk1 = compute("large-naquadah-reactor", Map.of("fuel", "Naq Fuel Mk-I"));
        assertEquals(2400, flow(mk1.inputs(), "Liquid Air"), 0);
    }

    // ---- Antimatter

    @Test
    @DisplayName("Antimatter: feeds the growth with Protomatter and burns it with the UMV catalyst")
    void antimatterFeedsProtomatter() {
        // AntimatterForge depletes Protomatter equal to the antimatter it adds; the workbook's gain at 657,600 L is
        // 396.11 L/s (13. Antimatter K6).
        final PowerModel model = compute("antimatter", Map.of("amount", "657600"));
        assertCloseTo(396.1129138, flow(model.inputs(), "Protomatter"), 5);
        assertCloseTo(396.1129138, flow(model.inputs(), "Molten Superconductor Base UMV"), 5);
    }

    // ---- HTGR

    @Test
    @DisplayName("HTGR: will not run below 100 balls")
    void htgrMinimumFill() {
        // MTEHighTempGasCooledReactor.MIN_CAPACITY is 1% of 10,000.
        final PowerSetting fill = PowerRegistry.get("htgr")
            .setting("fill");
        assertInstanceOf(PowerSetting.Number.class, fill);
        assertEquals(100, ((PowerSetting.Number) fill).min(), 0);
    }

    @Test
    @DisplayName("HTGR: adds no speedup for a draw that truncates to 0 L/t")
    void htgrNoSpeedupForZeroDraw() {
        // Tungsten at 150 balls draws 3 L/t of coolant and 0 of water, so only the coolant speedup (16 a tick)
        // runs: ceil(4,586 / 17) = 270 ticks.
        final Reactors.HtgrOperation run = Reactors.htgrOperation(new Reactors.Pebble(0.05, 0.8, 0.5), 150);
        assertEquals(3, run.coolantPerTick(), 0);
        assertEquals(0, run.waterPerTick(), 0);
        assertEquals(270, run.cycleTicks(), 0);
        // A full glowstone reactor keeps both speedups: 202 ticks.
        assertEquals(
            202,
            Reactors.htgrOperation(new Reactors.Pebble(2, 1.2, 1.1), 10_000)
                .cycleTicks(),
            0);
    }

    // ---- unlock chips

    @ParameterizedTest(name = "puts {0} at {1}, the tier of its controller recipe")
    @CsvSource({
        // THTR and HTGR are crafted with circuitUltimate (ZPM) circuits.
        "thtr, ZPM", "htgr, ZPM",
        // The LFTR is crafted around an IV Machine Hull.
        "lftr, IV",
        // bartworks assembles the DEHP at RECIPE_IV.
        "dehp, IV",
        // goodgenerator's assembly line recipe runs at RECIPE_UV.
        "large-naquadah-reactor, UV",
        // The Antimatter Forge's assembly line recipe runs at RECIPE_UMV.
        "antimatter, UMV" })
    void unlockChips(final String id, final String tier) {
        assertEquals(
            tier,
            PowerRegistry.get(id)
                .unlock());
    }
}
