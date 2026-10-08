package com.gtnhplanner.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.power.PowerModel.Flow;

/**
 * The website's sources/engines.test.ts. Golden values are the pack's Java (GT5U 5.09.54.20), ported independently of
 * this code; where the workbook differs, the Java wins.
 */
class EnginesTest {

    static PowerModel compute(final String sourceId, final Map<String, String> settings) {
        final PowerSource source = PowerRegistry.get(sourceId);
        assertNotNull(source, "No power source " + sourceId);
        return source.compute(settings);
    }

    /** The per-second rate of the named input; fails when there is none (undefined on the website). */
    static double input(final PowerModel model, final String name) {
        for (final Flow flow : model.inputs()) {
            if (flow.name()
                .equals(name)) return flow.perSecond();
        }
        return fail("No input " + name);
    }

    private static Map<String, String> settings(final String... pairs) {
        final Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    // ---- combustion engines burn whole litres per tick

    @Test
    void floorsTheEcesJetFuelATo4LitresPerTick() {
        final PowerModel model = compute("extreme-combustion-engine", settings("fuel", "Jet Fuel A"));
        assertEquals(10_900, model.euPerTick(), 0);
        assertEquals(80, input(model, "Jet Fuel A"), 0);
    }

    @Test
    void addsTheWeightedExtraLitreToBoostedHighOctaneGasoline() {
        // floor(4096 / 2500) = 1, plus frac(6144 / floor(2500 x 1.5)) = 0.6384.
        final PowerModel model = compute(
            "large-combustion-engine",
            settings("fuel", "High Octane Gasoline", "boost", "1"));
        assertEquals(6144, model.euPerTick(), 0);
        assertEquals(1.6384 * 20, input(model, "High Octane Gasoline"), 0.5e-9);
    }

    @Test
    void floorsTheSemifluidBurnerAgainst2048And4096WhenBoosted() {
        final PowerModel plain = compute("large-semifluid-generator", settings("fuel", "Heavy Fuel"));
        assertEquals(100, input(plain, "Heavy Fuel"), 0);
        final PowerModel boosted = compute("large-semifluid-generator", settings("fuel", "Heavy Fuel", "boost", "1"));
        assertEquals(6144, boosted.euPerTick(), 0);
        assertEquals(220, input(boosted, "Heavy Fuel"), 0);
        assertEquals(80, input(boosted, "Oxygen"), 0);
    }

    @Test
    void gatesLubricant14TimesPer1002Ticks() {
        final PowerModel model = compute("large-combustion-engine", settings("fuel", "Diesel", "boost", "1"));
        assertEquals((2 * 14 * 20) / 1002.0, input(model, "Lubricant"), 0.5e-12);
    }

    @Test
    void offersOnlyFuelsThePackRegisters() {
        final List<String> names = new ArrayList<>();
        for (final PowerData.Fuel fuel : PowerData.get().semifluidFuels) names.add(fuel.name);
        assertFalse(names.contains("Manure Slurry"));
        assertFalse(names.contains("Raw Animal Waste"));
        int ether = 0;
        for (final PowerData.Fuel fuel : PowerData.get().ucfeFuels) {
            if (fuel.name.equals("Ether")) ether++;
        }
        assertEquals(1, ether);
    }

    // ---- large rocket engine

    @Test
    void burnsOncePer21TicksButSpreadsTheEnergyOver20() {
        // Below the knee: 300 L/s burns 315 L per 21 ticks, P = 1536 x 315 / 20.
        final PowerModel low = compute("large-rocket-engine", settings("fuel", "RP-1 (red)", "throttle", "300"));
        assertEquals(1.6384 * ((1536 * 315) / 20.0), low.euPerTick(), 0.5e-6);
        // Past it (P = 40,320): the game truncates to 59,858.
        final PowerModel model = compute("large-rocket-engine", settings("fuel", "RP-1 (red)", "throttle", "500"));
        assertTrue(Math.abs(model.euPerTick() - 59_858) < 5);
    }

    @Test
    void boostMetersTheFalloffOnAThirdOfTheFuelWithNoStepAtTheKnee() {
        final PowerModel boosted = compute(
            "large-rocket-engine",
            settings("fuel", "RP-1 (red)", "throttle", "1500", "boost", "1"));
        assertTrue(Math.abs(boosted.euPerTick() - 179_576) / 179_576 < 1e-4);
        final PowerModel deep = compute(
            "large-rocket-engine",
            settings("fuel", "RP-1 (red)", "throttle", "4000", "boost", "1"));
        assertTrue(Math.abs(deep.euPerTick() - 312_921) / 312_921 < 1e-4);
        final double knee = (90_000 * 20) / (1536 * 1.05);
        final PowerModel below = compute(
            "large-rocket-engine",
            settings("fuel", "RP-1 (red)", "throttle", Double.toString(knee - 0.01), "boost", "1"));
        final PowerModel above = compute(
            "large-rocket-engine",
            settings("fuel", "RP-1 (red)", "throttle", Double.toString(knee + 0.01), "boost", "1"));
        assertTrue(above.euPerTick() > below.euPerTick());
    }

    // ---- large neutralization engine

    @Test
    void losesAnArmOnARollOf45TimesTierPlus2PerMinute() {
        final PowerModel model = compute(
            "large-neutralization-engine",
            settings("arms", "16", "armTier", "Amount (MV)"));
        assertEquals("8 min", model.stat("Avg lifespan"));
    }

    @Test
    void offersLvRobotArms() {
        final PowerSource source = PowerRegistry.get("large-neutralization-engine");
        assertNotNull(source);
        final PowerSetting.Select armTier = (PowerSetting.Select) source.setting("armTier");
        assertEquals(
            "Amount (LV)",
            armTier.options()
                .get(0)
                .key());
        assertEquals("Amount (HV)", armTier.defaultKey());
    }

    @Test
    void burnsOneFranciumHydroxideDustPer241Ticks() {
        final PowerModel model = compute("large-neutralization-engine", settings("base", "Francium Hydroxide"));
        assertEquals(20 / 241.0, input(model, "Francium Hydroxide Dust"), 0.5e-12);
    }

    // ---- unlock chips follow the controller recipe

    @Test
    void putsTheSofcsAtHvAndLuv() {
        assertEquals(
            "HV",
            PowerRegistry.get("solid-oxide-fuel-cell-1")
                .unlock());
        assertEquals(
            "LuV",
            PowerRegistry.get("solid-oxide-fuel-cell-2")
                .unlock());
    }
}
