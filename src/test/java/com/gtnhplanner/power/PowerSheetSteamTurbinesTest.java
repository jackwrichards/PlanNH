package com.gtnhplanner.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.power.PowerModel.Flow;

/**
 * The website's power.test.ts, its steam and turbine cases: "turbines", "steam makers", "singleblock boilers (game
 * source constants)" and the XL steam turbine split from "community-flagged additions". Golden values are the Power
 * Planner 2.9 workbook's own computed cells (docs/power-planner-math.md documents which); a failure here means the
 * transcription drifted from the spreadsheet, not that the spreadsheet moved.
 */
class PowerSheetSteamTurbinesTest {

    static PowerModel compute(final String sourceId, final Map<String, String> settings) {
        final PowerSource source = PowerRegistry.get(sourceId);
        if (source == null) fail("No power source " + sourceId);
        return source.compute(settings);
    }

    static Flow input(final PowerModel model, final int index) {
        return model.inputs()
            .get(index);
    }

    static Flow output(final PowerModel model, final int index) {
        return model.outputs()
            .get(index);
    }

    static Flow liters(final String name, final double perSecond) {
        return new Flow(name, perSecond, PowerModel.Unit.L);
    }

    static Flow items(final String name, final double perSecond) {
        return new Flow(name, perSecond, PowerModel.Unit.ITEM);
    }

    // ---- community-flagged additions (2.9 sheet)

    @Test
    void splitsXlSteamTurbines() {
        // splits the XL steam turbines: HP exhausts steam, SC exhausts SH
        final PowerModel hp = compute("xl-turbo-hp-steam-turbine", Map.of("rotor", "HSS-E", "size", "Huge"));
        assertEquals("SH Steam", input(hp, 0).name());
        assertEquals("Steam", output(hp, 0).name());
        assertEquals(input(hp, 0).perSecond(), output(hp, 0).perSecond(), 0);
        final PowerModel sc = compute("xl-turbo-sc-steam-turbine", Map.of("rotor", "HSS-E", "size", "Huge"));
        assertEquals("SC Steam", input(sc, 0).name());
        assertEquals("SH Steam", output(sc, 0).name());
        // The plain XL only offers plain and dense steam now.
        final PowerSource plain = PowerRegistry.get("xl-turbo-steam-turbine");
        assertNotNull(plain);
        final PowerSetting.Select grade = assertInstanceOf(PowerSetting.Select.class, plain.setting("grade"));
        final List<String> keys = new ArrayList<>();
        for (final PowerSetting.Option option : grade.options()) keys.add(option.key());
        assertEquals(List.of("Steam", "Dense Steam"), keys);
    }

    // ---- turbines

    @Test
    void tightSmallShadowMetalRunsAtOptimal() {
        // runs a tight Small Shadow Metal large steam turbine at its optimal
        final PowerModel model = compute(
            "large-steam-turbine",
            Map.of("rotor", "Shadow Metal", "size", "Small", "fitting", "tight", "flowMode", "optimal"));
        // Workbook default selection: eff 0.95, optimal 1600 L/t ->
        // floor(0.95 x 0.5 x 1600) = 760 EU/t.
        assertEquals(760, model.euPerTick(), 0);
        assertEquals("Steam", input(model, 0).name());
        assertEquals(1600 * 20, input(model, 0).perSecond(), 0);
        // MTELargeTurbineSteam condenses: 1 L distilled water per 160 L steam.
        assertEquals("Distilled Water", output(model, 0).name());
        assertEquals((1600 * 20) / 160.0, output(model, 0).perSecond(), 0);
    }

    @Test
    void superheatedExhaustsIntoPlainSteamOneToOne() {
        // exhausts superheated steam into plain steam 1:1
        final PowerModel model = compute(
            "large-hp-steam-turbine",
            Map.of("rotor", "Shadow Metal", "size", "Normal", "flowMode", "optimal"));
        assertEquals("Steam", output(model, 0).name());
        assertEquals(input(model, 0).perSecond(), output(model, 0).perSecond(), 0);
    }

    @Test
    void scRunsAtRotorsOwnSteamFlowLikeHp() {
        // runs SC steam at the rotor's own steam flow, like HP
        final Map<String, String> rotor = Map
            .of("rotor", "MAR-Ce-M200 Steel", "size", "Large", "fitting", "tight", "flowMode", "optimal");
        final PowerModel sc = compute("large-sc-steam-turbine", rotor);
        final PowerModel hp = compute("large-hp-steam-turbine", rotor);
        // Workbook and MTELargeTurbineSCSteam: 22,500 L/t at 1 EU/L x 145%.
        assertEquals("SC Steam", input(sc, 0).name());
        assertEquals(22_500 * 20, input(sc, 0).perSecond(), 0);
        assertEquals(32_625, sc.euPerTick(), 0);
        assertEquals(input(hp, 0).perSecond(), input(sc, 0).perSecond(), 0);
        // The XL is sixteen rotors, nothing more; dense steam divides by 1000.
        final PowerModel tight = compute(
            "xl-turbo-sc-steam-turbine",
            Map.of(
                "rotor",
                "Duranium",
                "size",
                "Large",
                "grade",
                "Dense SC Steam",
                "flowMode",
                "optimal",
                "fitting",
                "tight"));
        final PowerModel loose = compute(
            "xl-turbo-sc-steam-turbine",
            Map.of(
                "rotor",
                "Duranium",
                "size",
                "Large",
                "grade",
                "Dense SC Steam",
                "flowMode",
                "optimal",
                "fitting",
                "loose"));
        // The game's best supply, ceil of 16 x 76,800 / 1000; the sheet shows 1,228.
        assertEquals(1_229 * 20, input(tight, 0).perSecond(), 0);
        // Likewise ceil of 16 x 3,020,545.5 / 1000; the sheet shows 48,328.
        assertEquals(48_329 * 20, input(loose, 0).perSecond(), 0);
    }

    @Test
    void penalizesOverOptimalFlowButCapsAtMax() {
        // penalizes over-optimal flow but caps at max
        final PowerSource source = PowerRegistry.get("large-steam-turbine");
        final PowerModel atOptimal = compute(
            "large-steam-turbine",
            Map.of("rotor", "Shadow Metal", "size", "Normal", "flowMode", "optimal"));
        final PowerModel overfed = compute(
            "large-steam-turbine",
            Map.of("rotor", "Shadow Metal", "size", "Normal", "flowMode", "custom", "customFlow", "999999"));
        assertNotNull(source);
        assertTrue(
            overfed.warnings()
                .size() > 0);
        assertTrue(overfed.euPerTick() < (input(overfed, 0).perSecond() / 20) * 0.5 * 0.95);
        assertTrue(input(overfed, 0).perSecond() > input(atOptimal, 0).perSecond());
    }

    @Test
    void burnsHeliumPlasmaAndExhaustsHelium() {
        // burns helium plasma and exhausts helium
        final PowerModel model = compute(
            "large-plasma-generator",
            Map.of("rotor", "Shadow Metal", "size", "Normal", "fuel", "Helium Plasma", "flowMode", "optimal"));
        assertEquals("Helium", output(model, 0).name());
        assertTrue(model.euPerTick() > 0);
    }

    // ---- steam makers

    @Test
    void bronzeBoilerOnCreosoteAloneAtEightyPercent() {
        // runs the bronze boiler on creosote alone at 80% (625 L/s creosote, 960 L/t steam)
        final PowerModel model = compute(
            "large-bronze-boiler",
            Map.of("liquidFuel", "Creosote Oil", "solidFuel", "None"));
        assertEquals(625, input(model, 0).perSecond(), 0.5e-6);
        assertEquals("Steam", output(model, 0).name());
        assertEquals(960 * 20, output(model, 0).perSecond(), 0);
    }

    @Test
    void dualFuelReachesFullAndHalvesBurnRates() {
        // dual fuel reaches 100% and halves each burn rate
        final PowerModel model = compute(
            "large-bronze-boiler",
            Map.of("liquidFuel", "Creosote Oil", "solidFuel", "Charcoal"));
        assertEquals(1200 * 20, output(model, 0).perSecond(), 0);
        assertEquals(312.5, input(model, 0).perSecond(), 0.5e-6);
    }

    @Test
    void lheFlipsToSuperheatedOverHotCoolantThreshold() {
        // flips the LHE to superheated steam over the hot coolant threshold (13,800 L/t)
        final PowerModel model = compute(
            "large-heat-exchanger",
            Map.of("fluid", "Hot Coolant", "intake", "1380", "tier", "1"));
        assertEquals("SH Steam", output(model, 0).name());
        assertEquals(13800, output(model, 0).perSecond() / 20, 0.5e-4);
        assertEquals("Coolant", output(model, 1).name());
        assertEquals(1380, output(model, 1).perSecond(), 0);
    }

    @Test
    void eheStaysBelowThresholdOnSuperheatedSteam() {
        // keeps the EHE below threshold on superheated steam
        final PowerModel model = compute(
            "extreme-heat-exchanger",
            Map.of("fluid", "Hot Coolant", "intake", "4000", "tier", "1"));
        assertEquals("SH Steam", output(model, 0).name());
    }

    // ---- singleblock boilers (game source constants)

    @Test
    void smallCoalBoilerBurnsOneEnergyPerFortySixTicks() {
        // burns the Small Coal Boiler at 1 energy per 46t: one coal is 368s of 120 L/s
        final PowerModel model = compute("small-coal-boiler", Map.of("solidFuel", "Coal"));
        assertEquals(List.of(liters("Steam", 120)), model.outputs());
        // Coal is 1600 furnace ticks -> 160 boiler energy; the 45t cooldown fires every 46t: 160 / (20/46) = 368s.
        assertEquals("Coal", input(model, 0).name());
        assertEquals(PowerModel.Unit.ITEM, input(model, 0).unit());
        assertEquals(1.0 / 368, input(model, 0).perSecond(), 0.5e-12);
        assertEquals(liters("Water", 120.0 / 160), input(model, 1));
        assertTrue(
            model.warnings()
                .stream()
                .anyMatch(line -> line.contains("by hand")));
    }

    @Test
    void largeCoalBoilerBurnsTwoEnergyPerFortyOneTicks() {
        // burns the Large Coal Boiler at 2 energy per 41t: one coal is 164s of 300 L/s
        final PowerModel model = compute("large-coal-boiler", Map.of("solidFuel", "Coal"));
        assertEquals(List.of(liters("Steam", 300)), model.outputs());
        assertEquals("Coal", input(model, 0).name());
        assertEquals(PowerModel.Unit.ITEM, input(model, 0).unit());
        assertEquals(1.0 / 164, input(model, 0).perSecond(), 0.5e-12);
    }

    @Test
    void reinforcedLavaBoilerRunsAtSixHundred() {
        // runs the Reinforced Lava Boiler at 600 L/s steam on 60/21 L/s lava
        final PowerModel model = compute("lava-boiler", Map.of());
        assertEquals(List.of(liters("Steam", 600), items("Obsidian", 60.0 / 21 / 1000)), model.outputs());
        assertEquals(List.of(liters("Lava", 60.0 / 21), liters("Water", 600.0 / 160)), model.inputs());
    }

    @Test
    void solarBoilersFullOnDistilledCalcifiedOnRegular() {
        // holds the solar boilers at full rate on distilled water and calcifies on regular
        final PowerModel fresh = compute("solar-boiler", Map.of("model", "steel"));
        assertEquals(List.of(liters("Steam", 360)), fresh.outputs());
        assertEquals(List.of(), fresh.warnings());
        final PowerModel calcified = compute(
            "solar-boiler",
            Map.of("model", "steel", "waterKind", "Water", "calcified", "1"));
        assertEquals(List.of(liters("Steam", 120)), calcified.outputs());
        assertEquals(
            1,
            calcified.warnings()
                .size());
    }

    @Test
    void advancedBoilerScalesByTier() {
        // scales the GT++ Advanced Boiler by tier: HV is 2,250 L/s and coal lasts 170.15s
        final PowerModel model = compute("advanced-boiler", Map.of("tier", "HV", "solidFuel", "Coal"));
        assertEquals(List.of(liters("Steam", 2250)), model.outputs());
        // (160 / 2 + 1600 / 500) degrees, one lost per 41 ticks.
        assertEquals("Coal", input(model, 0).name());
        assertEquals(PowerModel.Unit.ITEM, input(model, 0).unit());
        assertEquals(1 / 170.15, input(model, 0).perSecond(), 0.5e-12);
    }
}
