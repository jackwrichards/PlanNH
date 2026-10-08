package com.gtnhplanner.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.power.PowerModel.Flow;
import com.gtnhplanner.power.PowerModel.Unit;

/** The website's sources/singleblocks.test.ts: the singleblock burns against the pack's Java. */
class SingleblocksTest {

    static PowerModel compute(final String sourceId, final Map<String, String> settings) {
        final PowerSource source = PowerRegistry.get(sourceId);
        assertNotNull(source, "No power source " + sourceId);
        return source.compute(settings);
    }

    static List<String> optionKeys(final PowerSource source, final String settingId) {
        final List<String> keys = new ArrayList<>();
        if (source.setting(settingId) instanceof final PowerSetting.Select select) {
            for (final PowerSetting.Option option : select.options()) keys.add(option.key());
        }
        return keys;
    }

    static List<String> optionLabels(final PowerSource source, final String settingId) {
        final List<String> labels = new ArrayList<>();
        if (source.setting(settingId) instanceof final PowerSetting.Select select) {
            for (final PowerSetting.Option option : select.options()) labels.add(option.label());
        }
        return labels;
    }

    // ---- singleblock burns (MTEBasicGenerator)

    @Test
    void floorsEuPerLiterFishOilInAnLvCombustionGeneratorMakes1EuPerL() {
        // floor(2 x 95 / 100) = 1, so 33 EU per packet needs 33 L.
        final PowerModel model = compute("combustion-generator", Map.of("tier", "LV", "fuel", "Fish Oil"));
        assertEquals("1", model.stat("EU per L"));
        assertEquals(32, model.euPerTick(), 0);
        assertEquals(
            660,
            model.inputs()
                .get(0)
                .perSecond(),
            0.5e-9);
        assertTrue(
            model.warnings()
                .isEmpty());
    }

    @Test
    void capsATenTickBurnAtThe16000LTankAndDeratesEuPerTick() {
        // IV fish oil: 1 EU/L, 82,080 L per burn wanted, 16,000 L held.
        final PowerModel model = compute("combustion-generator", Map.of("tier", "IV", "fuel", "Fish Oil"));
        assertEquals(
            32_000,
            model.inputs()
                .get(0)
                .perSecond(),
            0);
        assertEquals(((16_000 * 1) / 10.0) * (8192 / 8208.0), model.euPerTick(), 0.5e-9);
        assertEquals(
            List.of("Burns at most 16,000 L per 10 ticks (the tank), so it runs at 1,597 EU/t, not 8,192."),
            model.warnings());
    }

    @Test
    void givesTheGeothermalEngineItsOwn5000LPerTierTank() {
        // LuV pahoehoe: floor(24 x 58%) = 13 EU/L, 25,231 L per burn fits 30,000 L.
        final PowerModel model = compute("geothermal-engine", Map.of("tier", "LuV", "fuel", "Pahoehoe Lava"));
        assertEquals(32_768, model.euPerTick(), 0);
        assertEquals(
            (32_800 / 13.0) * 20,
            model.inputs()
                .get(0)
                .perSecond(),
            0.5e-9);
        assertTrue(
            model.warnings()
                .isEmpty());
    }

    @Test
    void burnsRocketFuelAtTheFuelMapValueAThirdOfTheLargeEngines() {
        // RP-1 is 512 in the map: floor(512 x 80%) = 409 EU/L at EV.
        final PowerModel model = compute("rocket-fuel-generator", Map.of("tier", "EV", "fuel", "RP-1 (red)"));
        assertEquals("409", model.stat("EU per L"));
        assertEquals(
            (2056 / 409.0) * 20,
            model.inputs()
                .get(0)
                .perSecond(),
            0.5e-9);
    }

    @Test
    void burnsAtMostOneItemPerTenTicksAndDeratesEuPerTick() {
        // LuV pyrotheum: 62 x 10 x 58 = 35,960 EU a dust, 2 dusts a second.
        final PowerModel model = compute("geothermal-engine", Map.of("tier", "LuV", "fuel", "Pyrotheum Dust"));
        final Flow input = model.inputs()
            .get(0);
        assertEquals("Pyrotheum Dust", input.name());
        assertEquals(2, input.perSecond(), 0);
        assertEquals(Unit.ITEM, input.unit());
        assertEquals(((2 * 35_960) / 20.0) * (32_768 / 32_800.0), model.euPerTick(), 0.5e-9);
        assertEquals(
            List.of("Burns at most 1 item per 10 ticks, so it runs at 3,592 EU/t, not 32,768."),
            model.warnings());
        final PowerModel ev = compute("geothermal-engine", Map.of("tier", "EV", "fuel", "Pyrotheum Dust"));
        assertEquals(2048, ev.euPerTick(), 0);
        assertTrue(
            ev.warnings()
                .isEmpty());
    }

    // ---- steam turbine (MTESteamTurbine)

    private record SteamCase(String tier, double liters, double packet, String efficiency) {}

    @Test
    void turnsSixPlusTierLitersOfSteamIntoThreeEuAndShowsTheExactEfficiency() {
        final List<SteamCase> cases = List.of(
            new SteamCase("LV", 7, 33, "85.71%"),
            new SteamCase("MV", 8, 130, "75%"),
            new SteamCase("HV", 9, 516, "66.67%"));
        for (final SteamCase c : cases) {
            final PowerModel model = compute("steam-turbine", Map.of("tier", c.tier()));
            assertEquals(
                (c.packet() / 3) * c.liters() * 20,
                model.inputs()
                    .get(0)
                    .perSecond(),
                0.5e-9);
            assertEquals(c.efficiency(), model.stat("Efficiency"));
            assertEquals(List.of(), model.outputs());
        }
    }

    // ---- naquadah reactor marks (MTENaquadahReactor)

    @Test
    void offersOnlyTheNaquadahOrTiberiumFormTheMarkPicksTheRod() {
        final PowerSource source = PowerRegistry.get("naquadah-reactor");
        assertNotNull(source);
        assertEquals(List.of("naquadah", "tiberium"), optionKeys(source, "fuel"));
        final List<List<String>> rods = new ArrayList<>();
        for (final String tier : List.of("EV", "IV", "LuV", "ZPM", "UV")) {
            rods.add(
                List.of(
                    compute("naquadah-reactor", Map.of("tier", tier, "fuel", "naquadah")).inputs()
                        .get(0)
                        .name(),
                    compute("naquadah-reactor", Map.of("tier", tier, "fuel", "tiberium")).inputs()
                        .get(0)
                        .name()));
        }
        assertEquals(
            List.of(
                List.of("Enriched Naquadah Bolt (EV)", "Tiberium Bolt (EV)"),
                List.of("Enriched Naquadah Rod (IV)", "Tiberium Rod (IV)"),
                List.of("Long Enriched Naquadah Rod (LuV)", "Long Tiberium Rod (LuV)"),
                List.of("Naquadria Bolt (ZPM)", "Tiberium Rod (ZPM)"),
                List.of("Naquadria Rod (UV)", "Long Tiberium Rod (UV)")),
            rods);
    }

    @Test
    void handsBackTheNaquadahPartAndNothingForTiberium() {
        final PowerModel naquadria = compute("naquadah-reactor", Map.of("tier", "ZPM", "fuel", "naquadah"));
        assertEquals(
            List.of(
                new Flow(
                    "Naquadah Bolt",
                    naquadria.inputs()
                        .get(0)
                        .perSecond(),
                    Unit.ITEM)),
            naquadria.outputs());
        assertEquals(List.of(), compute("naquadah-reactor", Map.of("tier", "ZPM", "fuel", "tiberium")).outputs());
    }

    @Test
    void loadsAStoredRodTheMarkCannotBurnAsThatMarksNaquadahRod() {
        final PowerModel model = compute("naquadah-reactor", Map.of("tier", "EV", "fuel", "Naquadria Rod (UV)"));
        assertEquals(
            "Enriched Naquadah Bolt (EV)",
            model.inputs()
                .get(0)
                .name());
        // 50,000 x 10 x 80 EU a bolt.
        assertEquals("40M", model.stat("EU per item"));
    }

    private static String rod(final String tier, final String fuel) {
        return compute("naquadah-reactor", Map.of("tier", tier, "fuel", fuel)).inputs()
            .get(0)
            .name();
    }

    @Test
    void readsAStoredRodKeyAsItsFormSoSavedTiberiumStaysTiberium() {
        assertEquals("Tiberium Rod (IV)", rod("IV", "Tiberium Rod (IV)"));
        assertEquals("Long Tiberium Rod (UV)", rod("UV", "Tiberium Bolt (EV)"));
        assertEquals("Tiberium Rod (ZPM)", rod("ZPM", "Long Tiberium Rod (LuV)"));
        assertEquals("Long Enriched Naquadah Rod (LuV)", rod("LuV", "Long Enriched Naquadah Rod (LuV)"));
        assertEquals("Enriched Naquadah Rod (IV)", rod("IV", "Naquadria Bolt (ZPM)"));
        // A key that is neither an option nor a retired one falls back to the default.
        assertEquals("Enriched Naquadah Bolt (EV)", rod("EV", "Something Else"));
    }

    // ---- magic energy converter byproducts (getEmptyContainer)

    @Test
    void handsBackTheNextSlateDownWhereTheResourceMapKnowsIt() {
        final PowerModel imbued = compute("magic-energy-converter", Map.of("tier", "LV", "fuel", "Imbued Slate"));
        assertEquals(
            List.of(
                new Flow(
                    "Reinforced Slate",
                    imbued.inputs()
                        .get(0)
                        .perSecond(),
                    Unit.ITEM)),
            imbued.outputs());
        final PowerModel reinforced = compute(
            "magic-energy-converter",
            Map.of("tier", "LV", "fuel", "Reinforced Slate"));
        assertEquals(
            List.of(
                new Flow(
                    "Blank Slate",
                    reinforced.inputs()
                        .get(0)
                        .perSecond(),
                    Unit.ITEM)),
            reinforced.outputs());
        // Liveroots hand back four sticks each.
        final PowerModel liveroots = compute("magic-energy-converter", Map.of("tier", "LV", "fuel", "Liveroots"));
        assertEquals(
            List.of(
                new Flow(
                    "Stick",
                    liveroots.inputs()
                        .get(0)
                        .perSecond() * 4,
                    Unit.ITEM)),
            liveroots.outputs());
    }

    // ---- RTG pellets (MTERTGenerator)

    private static double days(final String key) {
        final PowerModel model = compute("rtg", Map.of("pellet", key));
        return 1 / model.inputs()
            .get(0)
            .perSecond() / 86_400;
    }

    @Test
    void capsAPelletAtIntegerMaxValueEuAndChargesThePacketLoss() {
        final double cap = Math.pow(2, 31) - 1;
        // Am-241 and Pu-238 overflow the cap; the others lose only the packet loss.
        assertEquals(cap / 16 / 20 / 86_400, days("am241"), 0.5e-9);
        assertEquals(cap / 62 / 20 / 86_400, days("pu238"), 0.5e-9);
        assertEquals((29 * 30) / 31.0, days("sr90"), 0.5e-9);
        assertEquals(480 / 484.0, days("po210"), 0.5e-9);
        // roundToClosestInt(2.6f) is 2 days, at 7 EU/t paying 8.
        assertEquals((2 * 7) / 8.0, days("ic2"), 0.5e-9);
    }

    @Test
    void labelsEachPelletWithItsRealRunTime() {
        final PowerSource source = PowerRegistry.get("rtg");
        assertNotNull(source);
        assertEquals(
            List.of(
                "Am-241 (15 EU/t, 77.67 days)",
                "Sr-90 (30 EU/t, 28.06 days)",
                "Pu-238 (60 EU/t, 20.04 days)",
                "Po-210 (480 EU/t, 0.99 days)",
                "Pellets of RTG Fuel (7 EU/t, 1.75 days)"),
            optionLabels(source, "pellet"));
        assertEquals("20.04 real days", compute("rtg", Map.of("pellet", "pu238")).stat("One pellet runs"));
    }

    // ---- unlock chips (the controller's own recipe)

    @Test
    void putsTheRtgAtIvAndThePlasmaGeneratorAtLuv() {
        assertEquals(
            "IV",
            PowerRegistry.get("rtg")
                .unlock());
        assertEquals(
            "LuV",
            PowerRegistry.get("plasma-generator")
                .unlock());
    }
}
