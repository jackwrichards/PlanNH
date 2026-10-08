package com.gtnhplanner.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.power.PowerModel.Flow;
import com.gtnhplanner.power.PowerModel.Unit;

/**
 * The website's power.test.ts, its singleblock, engine and 2.9-sheet blocks and the RTG case. Golden values are the
 * Power Planner 2.9 workbook's own computed cells (docs/power-planner-math.md on the website documents which); a
 * failure here means the transcription drifted from the spreadsheet, not that the spreadsheet moved.
 */
class PowerSheetSinglesEnginesTest {

    static PowerModel compute(final String sourceId, final Map<String, String> settings) {
        final PowerSource source = PowerRegistry.get(sourceId);
        assertNotNull(source, "No power source " + sourceId);
        return source.compute(settings);
    }

    // ---- singleblock generators

    @Test
    void pricesTheLvSteamTurbineAsMteSteamTurbineBurns() {
        // 3 EU per 7 L: 1540 L/s. The workbook's E21 (1552.94) prices the tooltip's truncated 85%.
        final PowerModel model = compute("steam-turbine", Map.of("tier", "LV"));
        assertEquals(32, model.euPerTick(), 0);
        assertEquals(
            "Steam",
            model.inputs()
                .get(0)
                .name());
        assertEquals(
            (33 / 3.0) * 7 * 20,
            model.inputs()
                .get(0)
                .perSecond(),
            0.5e-9);
    }

    @Test
    void pricesTheLvGasTurbineOnBenzene() {
        // L21 = 1.9298 L/s.
        final PowerModel model = compute("gas-turbine", Map.of("tier", "LV", "fuel", "Benzene"));
        assertEquals(
            1.929824561,
            model.inputs()
                .get(0)
                .perSecond(),
            0.5e-6);
    }

    @Test
    void pricesTheLvCombustionGeneratorOnDiesel() {
        // S21 = 1.4474 L/s.
        final PowerModel model = compute("combustion-generator", Map.of("tier", "LV", "fuel", "Diesel"));
        assertEquals(
            1.447368421,
            model.inputs()
                .get(0)
                .perSecond(),
            0.5e-6);
    }

    @Test
    void pricesTheLvSemifluidGeneratorOnCreosoteAtFlooredEuPerL() {
        // floor(48 x 95%) = 45 EU/L. The workbook's Z21 (14.4737) skips MTEBasicGenerator's floor.
        final PowerModel model = compute("semifluid-generator", Map.of("tier", "LV", "fuel", "Creosote Oil"));
        assertEquals(
            (33 / 45.0) * 20,
            model.inputs()
                .get(0)
                .perSecond(),
            0.5e-9);
    }

    @Test
    void pricesTheLuvNaquadahReactorOnALongEnrichedRod() {
        // E59 = 3.1488 per hour.
        final PowerModel model = compute("naquadah-reactor", Map.of("tier", "LuV", "fuel", "naquadah"));
        assertEquals(
            "Long Enriched Naquadah Rod (LuV)",
            model.inputs()
                .get(0)
                .name());
        assertEquals(
            3.1488,
            model.inputs()
                .get(0)
                .perSecond() * 3600,
            0.5e-4);
    }

    @Test
    void pricesTheLvMagicAbsorberOnQuicksilver() {
        // S59 = 41.25 per hour. The ABSORBER's own ladder (0.9 at LV) - the 39.079 this once pinned was the
        // converter's cell, read through a swapped extraction.
        final PowerModel model = compute("magic-energy-absorber", Map.of("tier", "LV", "fuel", "Quicksilver"));
        assertEquals(
            41.25,
            model.inputs()
                .get(0)
                .perSecond() * 3600,
            0.5e-4);
    }

    // ---- engines

    @Test
    void burnsDieselInTheLceAtFlooredLitresPerTick() {
        // floor(2048/480) = 4 L/t, as the Java floors it.
        final PowerModel model = compute("large-combustion-engine", Map.of("fuel", "Diesel"));
        assertEquals(2048, model.euPerTick(), 0);
        assertEquals(
            80,
            model.inputs()
                .get(0)
                .perSecond(),
            0);
    }

    @Test
    void boostTriplesOutputOnFlooredLitresAndAddsOxygen() {
        // floor(4096/480) = 8 L/t.
        final PowerModel model = compute("large-combustion-engine", Map.of("fuel", "Diesel", "boost", "1"));
        assertEquals(6144, model.euPerTick(), 0);
        assertEquals(
            160,
            model.inputs()
                .get(0)
                .perSecond(),
            0);
        assertTrue(
            model.inputs()
                .stream()
                .anyMatch(
                    flow -> flow.name()
                        .equals("Oxygen")));
    }

    @Test
    void refusesOver2048EuPerLFuelsWithoutTheBoost() {
        final PowerModel model = compute("large-combustion-engine", Map.of("fuel", "High Octane Gasoline"));
        assertEquals(0, model.euPerTick(), 0);
        assertFalse(
            model.warnings()
                .isEmpty());
    }

    @Test
    void runsTheUcfeAtItsExponentialEfficiency() {
        // 1.5e^(-C/ratio): N29 = 56,177.85 EU/t.
        final PowerModel model = compute(
            "universal-chemical-fuel-engine",
            Map.of("fuel", "RP-1 (red)", "flow", "500", "promoterRatio", "0.2"));
        assertEquals(56177.85093, model.euPerTick(), 0.5e-2);
        assertEquals(
            "Combustion Promoter",
            model.inputs()
                .get(1)
                .name());
        assertEquals(
            100,
            model.inputs()
                .get(1)
                .perSecond(),
            0.5e-6);
    }

    @Test
    void feedsTheSofcMkIBenzeneAtTheFlooredRate() {
        // 113 L/s.
        final PowerModel model = compute("solid-oxide-fuel-cell-1", Map.of("fuel", "Benzene"));
        assertEquals(2048, model.euPerTick(), 0);
        assertEquals(
            113,
            model.inputs()
                .get(0)
                .perSecond(),
            0);
        final Flow steam = model.outputs()
            .get(0);
        assertEquals("Steam", steam.name());
        assertEquals(20000, steam.perSecond(), 0);
    }

    // ---- community-flagged additions (2.9 sheet); its XL steam turbine case is in the turbine port's tests

    @Test
    void runsTheLvAcidGeneratorOnMoltenRedstoneAtFlooredEuPerL() {
        // floor(40 x 97%) = 38 EU/L. The workbook's E33 (17.0103) skips MTEBasicGenerator's floor.
        final PowerModel model = compute("acid-generator", Map.of("tier", "LV", "fuel", "Molten Redstone"));
        assertEquals(32, model.euPerTick(), 0);
        assertEquals(
            (33 / 38.0) * 20,
            model.inputs()
                .get(0)
                .perSecond(),
            0.5e-9);
    }

    @Test
    void runsTheEvGeothermalEngineOnCryotheumDustAsItems() {
        // L40 = 0.9211/s.
        final PowerModel model = compute("geothermal-engine", Map.of("tier", "EV", "fuel", "Cryotheum Dust"));
        assertEquals(2048, model.euPerTick(), 0);
        assertEquals(
            0.9211469534,
            model.inputs()
                .get(0)
                .perSecond(),
            0.5e-6);
        assertNotEquals(
            Unit.L,
            model.inputs()
                .get(0)
                .unit());
    }

    @Test
    void pricesTheLvMagicEnergyConverterOnQuicksilver() {
        // L52 = 39.0789 per hour.
        final PowerModel model = compute("magic-energy-converter", Map.of("tier", "LV", "fuel", "Quicksilver"));
        assertEquals(32, model.euPerTick(), 0);
        assertEquals(
            39.07894737,
            model.inputs()
                .get(0)
                .perSecond() * 3600,
            0.5e-4);
    }

    @Test
    void computesTheLneAtTheSheetsDefaultsAndBoostsOnABase() {
        // Z29 = 2000 EU/t.
        final PowerModel plain = compute(
            "large-neutralization-engine",
            Map.of("structure", "T1", "fuel", "Molten Redstone", "rate", "50", "base", "None"));
        assertEquals(2000, plain.euPerTick(), 0);
        // Rate is per tick (the game's maxFluidUse dial); the slot is per second.
        final Flow acid = plain.inputs()
            .get(0);
        assertEquals("Molten Redstone", acid.name());
        assertEquals(1000, acid.perSecond(), 0);
        final PowerModel boosted = compute(
            "large-neutralization-engine",
            Map.of("structure", "T1", "fuel", "Molten Redstone", "rate", "50", "base", "Sodium Hydroxide"));
        // x1.5 power for one hydroxide dust per 21 ticks (20 boosted plus one to reload).
        assertEquals(3000, boosted.euPerTick(), 0);
        final Flow dust = boosted.inputs()
            .get(1);
        assertEquals("Sodium Hydroxide Dust", dust.name());
        assertEquals(20 / 21.0, dust.perSecond(), 0);
    }

    @Test
    void matchesTheSheetOnTheReportedCaseFluoroantimonicAt1LPerTick() {
        // 5760 EU/t.
        final PowerModel model = compute(
            "large-neutralization-engine",
            Map.of("structure", "T1", "fuel", "Fluoroantimonic Acid", "rate", "1", "base", "None"));
        assertEquals(5760, model.euPerTick(), 0);
        final Flow acid = model.inputs()
            .get(0);
        assertEquals("Fluoroantimonic Acid", acid.name());
        assertEquals(20, acid.perSecond(), 0);
        // Average residue is deterministic (random walk averages 1.0): 0.05 x 5760^0.8 per tick, against T1
        // full-tank decay 200 x 375000^0.08.
        final double avg = 0.05 * Math.pow(5760, 0.8);
        final double decayAtFull = 200 * Math.pow(375000, 0.08);
        assertNotNull(model.stat("Avg residue"));
        assertEquals(50.94, avg, 0.5e-1);
        assertTrue(avg - decayAtFull < 0);
        assertTrue(
            model.warnings()
                .isEmpty());
    }

    @Test
    void warnsWhenLneResidueOutrunsDecayEvenAtAFullTank() {
        final PowerModel model = compute(
            "large-neutralization-engine",
            Map.of("structure", "T1", "fuel", "Fluoroantimonic Acid", "rate", "1000", "base", "None"));
        assertTrue(
            model.warnings()
                .get(0)
                .contains("explode"));
    }

    // ---- RTG and Dyson Swarm (the RTG case; the Dyson Swarm is in the reactor port's tests)

    @Test
    void runsTheRtgOnAPu238PelletUntilItsIntegerMaxValueEuRunOut() {
        // 87 days x 60 EU/t overflows the int cap; each 60 EU packet drains 62.
        final PowerModel model = compute("rtg", Map.of("pellet", "pu238"));
        assertEquals(60, model.euPerTick(), 0);
        assertEquals(List.of(new Flow("Pu Pellet", 1 / ((Math.pow(2, 31) - 1) / 62 / 20), Unit.ITEM)), model.inputs());
        assertEquals("miscutils:mu-metaitem.01@32041", PowerResources.resolve("Pu Pellet").id);
    }
}
