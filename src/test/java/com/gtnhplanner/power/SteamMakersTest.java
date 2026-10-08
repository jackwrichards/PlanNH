package com.gtnhplanner.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.power.PowerModel.Flow;

/**
 * Steam makers against the pack's Java (GT5U 5.09.54.20), ported from the website's sources/steam-makers.test.ts.
 * Each case names the class whose rule it pins.
 */
class SteamMakersTest {

    static PowerModel compute(final String sourceId, final Map<String, String> settings) {
        final PowerSource source = PowerRegistry.get(sourceId);
        if (source == null) fail("No power source " + sourceId);
        return source.compute(settings);
    }

    static PowerModel compute(final String sourceId) {
        return compute(sourceId, Map.of());
    }

    static PowerSetting setting(final String sourceId, final String settingId) {
        final PowerSource source = PowerRegistry.get(sourceId);
        return source == null ? null : source.setting(settingId);
    }

    static List<String> optionKeys(final String sourceId, final String settingId) {
        final List<String> keys = new ArrayList<>();
        if (setting(sourceId, settingId) instanceof final PowerSetting.Select select) {
            for (final PowerSetting.Option option : select.options()) keys.add(option.key());
        }
        return keys;
    }

    /** The per-second rate of the first input or output with this name; fails when there is none. */
    static double flow(final PowerModel model, final String name) {
        final List<Flow> all = new ArrayList<>(model.inputs());
        all.addAll(model.outputs());
        for (final Flow line : all) {
            if (line.name()
                .equals(name)) return line.perSecond();
        }
        return fail("No flow " + name);
    }

    static Flow liters(final String name, final double perSecond) {
        return new Flow(name, perSecond, PowerModel.Unit.L);
    }

    // ---- Thermal Boiler (RecipesGregTech.thermalBoilerRecipes)

    @Test
    void thermalBoilerTurnsLavaIntoPlainSteamAndPahoehoe() {
        // turns 1000 L/s of lava into 16,000 L/s of plain Steam and pahoehoe lava
        final PowerModel model = compute("thermal-boiler", Map.of("fluid", "Lava", "intake", "1000"));
        assertEquals(List.of(liters("Steam", 16_000), liters("Pahoehoe Lava", 1000)), model.outputs());
        assertEquals(100, flow(model, "Water"), 0);
    }

    @Test
    void thermalBoilerTurnsPahoehoeIntoPlainSteam() {
        // turns pahoehoe lava into plain Steam at the same 16 per L
        final PowerModel model = compute("thermal-boiler", Map.of("fluid", "Pahoehoe Lava", "intake", "1000"));
        assertEquals(List.of(liters("Steam", 16_000)), model.outputs());
    }

    @Test
    void thermalBoilerKeepsCoolantAndSaltOnSuperheatedSteam() {
        // keeps hot coolant and hot solar salt on superheated steam
        final PowerModel coolant = compute("thermal-boiler", Map.of("fluid", "Hot Coolant", "intake", "500"));
        assertEquals(
            liters("SH Steam", 100_000),
            coolant.outputs()
                .get(0));
        final PowerModel salt = compute("thermal-boiler", Map.of("fluid", "Hot Solar Salt", "intake", "100"));
        assertEquals(
            liters("SH Steam", 100_000),
            salt.outputs()
                .get(0));
    }

    @Test
    void thermalBoilerHasNoCircuit() {
        // has no circuit setting: MTEThermalBoiler reads none
        assertNull(setting("thermal-boiler", "tier"));
    }

    // ---- heat exchanger circuit (MTEHeatExchanger, MTEAdvHeatExchanger, MTEExtremeHeatExchanger)

    @Test
    void circuitIsOneToTwentyFive() {
        // is a programmed circuit from 1 to 25
        for (final String id : List.of("large-heat-exchanger", "whakawhiti-wera-xl", "extreme-heat-exchanger")) {
            final PowerSetting.Number circuit = assertInstanceOf(PowerSetting.Number.class, setting(id, "tier"), id);
            assertEquals("Circuit", circuit.label(), id);
            assertEquals(1, circuit.min(), 0, id);
            assertEquals(25, circuit.max(), 0, id);
        }
    }

    @Test
    void savedCircuitFiveComputesAsBefore() {
        // computes a saved circuit 5 exactly as before
        // Lava: threshold 1000 - 4 x 37.5 = 850, cap 1700, SH at 80 per L, 94% efficiency.
        final PowerModel model = compute(
            "large-heat-exchanger",
            Map.of("fluid", "Lava", "intake", "2000", "tier", "5"));
        assertEquals(
            "SH Steam",
            model.outputs()
                .get(0)
                .name());
        assertEquals(
            1700 * 80 * 0.94,
            model.outputs()
                .get(0)
                .perSecond(),
            0.5e-6);
        assertEquals(1700, flow(model, "Lava"), 0);
    }

    @Test
    void lheThresholdLowersToCircuitTwentyFive() {
        // lowers the LHE threshold down to circuit 25
        // Hot coolant: 800 - 24 x 30 = 80 L/s, so 100 L/s is over it.
        final PowerModel model = compute(
            "large-heat-exchanger",
            Map.of("fluid", "Hot Coolant", "intake", "100", "tier", "25"));
        assertEquals(
            "SH Steam",
            model.outputs()
                .get(0)
                .name());
        assertEquals(
            100 * 200 * (1 - 0.015 * 24),
            model.outputs()
                .get(0)
                .perSecond(),
            0.5e-6);
    }

    @Test
    void eheThresholdNeverBelowOne() {
        // never lets the EHE threshold fall below 1
        // Hot solar salt: 1600 - 24 x 150 is negative; the Java clamps it to 1.
        final PowerModel model = compute(
            "extreme-heat-exchanger",
            Map.of("fluid", "Hot Solar Salt", "intake", "1", "tier", "25"));
        assertEquals(
            "SC Steam",
            model.outputs()
                .get(0)
                .name());
        assertEquals("1 L/s", model.stat("Threshold"));
    }

    @Test
    void whakawhitiCapsAtTwiceLoweredThreshold() {
        // caps the Whakawhiti Wera XL at twice its lowered threshold
        // Lava at circuit 10: threshold 32,000 - 9 x 1,200 = 21,200, so at most 42,400 L/s.
        final PowerModel model = compute(
            "whakawhiti-wera-xl",
            Map.of("fluid", "Lava", "intake", "64000", "tier", "10"));
        assertEquals(42_400, flow(model, "Lava"), 0);
        assertEquals(42_400, flow(model, "Pahoehoe Lava"), 0);
        assertEquals(
            42_400 * 80 * (1 - 0.015 * 9),
            model.outputs()
                .get(0)
                .perSecond(),
            0.5e-6);
        assertEquals(List.of("Intake is capped at 42,400 L/s for this fluid."), model.warnings());
    }

    @Test
    void whakawhitiFullOnCircuitOne() {
        // keeps the XL at its full 64,000 L/s on circuit 1
        final PowerModel model = compute("whakawhiti-wera-xl", Map.of("fluid", "Lava", "intake", "64000", "tier", "1"));
        assertEquals(64_000, flow(model, "Lava"), 0);
    }

    @Test
    void steamStatAtDecimalTieMatchesWebsite() {
        // Not in steam-makers.test.ts: a value the website shows, where the steam sits on a decimal tie
        // and Intl rounds it up.
        final PowerModel model = compute(
            "extreme-heat-exchanger",
            Map.of("fluid", "Hot Coolant", "intake", "321.7", "tier", "2"));
        assertEquals("3,168.75 L/t SH Steam", model.stat("Steam"));
    }

    // ---- large boilers (MTELargeBoilerBase, LargeBoilerFuelBackend)

    @Test
    void superheatedBoilersOfferOnlyAllowedFuels() {
        // offers the superheated boilers only the ALLOWED_FUELS fluids
        final List<String> allowed = new ArrayList<>(
            List.of(
                "Ether",
                "Gasoline",
                "Cetane-Boosted Diesel",
                "Ethanol Gasoline",
                "Jet Fuel No.3",
                "Jet Fuel A",
                "High Octane Gasoline"));
        allowed.sort(null);
        for (final String id : List.of("large-titanium-boiler", "large-tungstensteel-boiler")) {
            final List<String> keys = new ArrayList<>(optionKeys(id, "liquidFuel"));
            keys.removeIf("None"::equals);
            keys.sort(null);
            assertEquals(allowed, keys, id);
        }
    }

    @Test
    void solidBurnsFuelValueOverEightyTimesRuntimeBoost() {
        // burns a solid for fuel value / 80 ticks times the tier's runtimeBoost
        final Map<String, DoubleUnaryOperator> boost = new LinkedHashMap<>();
        boost.put("bronzeSolid", ticks -> ticks * 2);
        boost.put("steelSolid", ticks -> ticks);
        boost.put("titaniumSolid", ticks -> trunc((ticks * 3) / 10));
        boost.put("tungstensteelSolid", ticks -> trunc((ticks * 15) / 100));
        final Map<String, List<PowerData.Fuel>> tables = PowerData.get().boilerFuels;
        for (final Map.Entry<String, DoubleUnaryOperator> entry : boost.entrySet()) {
            for (final PowerData.Fuel row : tables.get(entry.getKey())) {
                assertNotNull(row.burnTime, row.name);
                final double euPerItem = row.euPerItem != null ? row.euPerItem : 0;
                assertEquals(
                    entry.getValue()
                        .applyAsDouble(trunc(euPerItem / 80)) / 20,
                    row.burnTime,
                    0.5e-9,
                    row.name);
            }
        }
    }

    @Test
    void solidSuperFuelBurnTimes() {
        // burns Solid Super Fuel for 125s in bronze and 18.75s in titanium
        final PowerModel bronze = compute(
            "large-bronze-boiler",
            Map.of("liquidFuel", "None", "solidFuel", "Solid Super Fuel"));
        assertEquals(1.0 / 125, flow(bronze, "Solid Super Fuel"), 0.5e-12);
        final PowerModel titanium = compute(
            "large-titanium-boiler",
            Map.of("liquidFuel", "None", "solidFuel", "Solid Super Fuel"));
        assertEquals(1 / 18.75, flow(titanium, "Solid Super Fuel"), 0.5e-12);
        final PowerModel steel = compute(
            "large-steel-boiler",
            Map.of("liquidFuel", "None", "solidFuel", "Block of Diamond"));
        assertEquals(1.0 / 640, flow(steel, "Block of Diamond"), 0.5e-12);
    }

    // ---- singleblock boilers (MTEBoiler)

    @Test
    void coalBoilersRefuseSulfurDust() {
        // refuses sulfur dust in the coal boilers but not the GT++ Advanced Boiler
        assertFalse(optionKeys("small-coal-boiler", "solidFuel").contains("Sulfur Dust"));
        assertFalse(optionKeys("large-coal-boiler", "solidFuel").contains("Sulfur Dust"));
        assertTrue(optionKeys("small-coal-boiler", "solidFuel").contains("Lithium Dust"));
        assertTrue(optionKeys("advanced-boiler", "solidFuel").contains("Sulfur Dust"));
    }

    @Test
    void advancedBoilerItemTimeSameOnEveryTier() {
        // gives the Advanced Boiler the same item time on every tier
        // Solid Super Fuel: (10,000 / 2 + 200) degrees x 41/20.
        for (final String tier : List.of("LV", "MV", "HV")) {
            final PowerModel model = compute("advanced-boiler", Map.of("tier", tier, "solidFuel", "Solid Super Fuel"));
            assertEquals(
                1.0 / 10_660,
                model.inputs()
                    .get(0)
                    .perSecond(),
                0.5e-12,
                tier);
        }
    }

    @Test
    void lavaBoilerLeavesObsidian() {
        // leaves one obsidian per 1000 L of lava
        final PowerModel model = compute("lava-boiler");
        assertEquals(flow(model, "Lava") / 1000, flow(model, "Obsidian"), 0.5e-12);
    }

    @Test
    void solarBoilerStatesCalcification() {
        // states when the solar boiler calcifies on regular water
        final PowerModel model = compute("solar-boiler", Map.of("model", "bronze", "waterKind", "Water"));
        assertEquals(
            List.of(
                "Regular water calcifies this boiler: full 120 L/s for 15 hours of run time, then down to 40 L/s by 25 hours. Distilled water does not."),
            model.warnings());
    }

    // ---- unlock chips (the controller's own recipe)

    @Test
    void exchangersTieredByControllerRecipes() {
        // tiers the exchangers by their controller recipes
        assertEquals(
            "IV",
            PowerRegistry.get("thermal-boiler")
                .unlock());
        assertEquals(
            "EV",
            PowerRegistry.get("large-heat-exchanger")
                .unlock());
        assertEquals(
            "LuV",
            PowerRegistry.get("whakawhiti-wera-xl")
                .unlock());
        assertEquals(
            "IV",
            PowerRegistry.get("extreme-heat-exchanger")
                .unlock());
    }

    /** {@code Math.trunc}. */
    private static double trunc(final double value) {
        return value < 0 ? Math.ceil(value) : Math.floor(value);
    }
}
