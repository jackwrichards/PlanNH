package com.gtnhplanner.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Golden values follow the pack's GT5U Java (tag 5.09.54.20): MTELargeTurbine*, MTEXLTurbine* and
 * MTEMultiBlockBase.doRandomMaintenanceDamage. Ported from the website's sources/turbines.test.ts ("turbine
 * formulas").
 */
class TurbinesTest {

    static PowerModel compute(final String sourceId, final Map<String, String> settings) {
        final PowerSource source = PowerRegistry.get(sourceId);
        if (source == null) fail("No power source " + sourceId);
        return source.compute(settings);
    }

    static String statValue(final PowerModel model, final String label) {
        return model.stat(label);
    }

    /** {@code { ...base, key: value }}. */
    static Map<String, String> with(final Map<String, String> base, final String key, final String value) {
        final Map<String, String> out = new HashMap<>(base);
        out.put(key, value);
        return out;
    }

    /** {@code expect(warnings?.[0]).toMatch(regex)}. */
    static void assertFirstWarningMatches(final String regex, final PowerModel model) {
        assertFalse(
            model.warnings()
                .isEmpty(),
            "no warning");
        final String first = model.warnings()
            .get(0);
        assertTrue(
            Pattern.compile(regex)
                .matcher(first)
                .find(),
            first);
    }

    @Test
    void weakRotorBurnsOneLitrePerTickForOneRotorsOptimal() {
        // gives a rotor too weak for its gas one rotor's optimal EU/t at 1 L/t, XL included
        // Stainless Steel Large: 1,050 L/t x 145% = 1,522 EU/t, under one litre of nitrobenzene.
        final Map<String, String> rotor = Map.of("rotor", "Stainless Steel", "size", "Large", "fuel", "Nitrobenzene");
        final PowerModel xl = compute("xl-turbo-gas-turbine", rotor);
        assertEquals(1_522, xl.euPerTick(), 0);
        assertEquals(
            "Nitrobenzene",
            xl.inputs()
                .get(0)
                .name());
        assertEquals(
            20,
            xl.inputs()
                .get(0)
                .perSecond(),
            0);
        assertFirstWarningMatches("1 L/t for one rotor's 1,522 EU/t", xl);
        // The check reads the tight stats, so the fitting changes nothing.
        final PowerModel loose = compute("xl-turbo-gas-turbine", with(rotor, "fitting", "loose"));
        assertEquals(1_522, loose.euPerTick(), 0);
        assertEquals(
            20,
            loose.inputs()
                .get(0)
                .perSecond(),
            0);
        final PowerModel large = compute("large-gas-turbine", rotor);
        assertEquals(1_522, large.euPerTick(), 0);
        assertEquals(
            20,
            large.inputs()
                .get(0)
                .perSecond(),
            0);
    }

    @Test
    void largeGasOptimumTruncatingToZeroMakesNothing() {
        // makes nothing when the Large gas optimum truncates to 0 L/t
        // Carbon Large: 150 EU/t / 160 EU/L truncates to 0 L/t, while 150 x 125% clears the weak-rotor check.
        final PowerModel model = compute(
            "large-gas-turbine",
            Map.of("rotor", "Carbon", "size", "Large", "fuel", "Refinery Gas"));
        assertEquals(0, model.euPerTick(), 0);
        assertEquals(
            0,
            model.inputs()
                .get(0)
                .perSecond(),
            0);
        assertFirstWarningMatches("0 L/t", model);
    }

    @Test
    void xlPlasmaTruncatesLargePlasmaCeils() {
        // truncates the XL plasma optimum and keeps the Large plasma's ceil
        final Map<String, String> rotor = Map.of("rotor", "Naquadah Alloy", "size", "Huge", "fuel", "Americium Plasma");
        // 16 x 67,200 x 20 / 501,760 = 42.9 L/s: the XL takes 42.
        final PowerModel xl = compute("xl-turbo-plasma-turbine", rotor);
        assertEquals(
            42,
            xl.inputs()
                .get(0)
                .perSecond(),
            0);
        assertEquals(1_896_652, xl.euPerTick(), 0);
        assertEquals("42 L/s", statValue(xl, "Optimal flow"));
        // 2.68 L/s on the Large rounds up to 3.
        final PowerModel large = compute("large-plasma-generator", rotor);
        assertEquals(
            3,
            large.inputs()
                .get(0)
                .perSecond(),
            0);
        // Under 1 L/s the XL takes nothing.
        final PowerModel none = compute(
            "xl-turbo-plasma-turbine",
            Map.of("rotor", "Carbon", "size", "Small", "fuel", "Celestial Tungsten Plasma"));
        assertEquals(0, none.euPerTick(), 0);
        assertEquals(List.of(), none.outputs());
    }

    @Test
    void denseSteamInWholeLitresScoredAgainstUnroundedOptimum() {
        // feeds dense steam in whole litres and scores them against the unrounded optimum
        final Map<String, String> rotor = Map.of("rotor", "Duranium", "size", "Large", "grade", "Dense SC Steam");
        // 16 x 76,800 = 1,228,800 L/t of steam, 1,228.8 dense litres.
        final PowerModel best = compute("xl-turbo-sc-steam-turbine", rotor);
        assertEquals(
            1_229 * 20,
            best.inputs()
                .get(0)
                .perSecond(),
            0);
        assertEquals(2_641_920, best.euPerTick(), 0);
        assertEquals(
            "Dense SH Steam",
            best.outputs()
                .get(0)
                .name());
        assertEquals(
            1_229 * 20,
            best.outputs()
                .get(0)
                .perSecond(),
            0);
        final Map<String, String> custom = with(rotor, "flowMode", "custom");
        custom.put("customFlow", "1228");
        final PowerModel shortFed = compute("xl-turbo-sc-steam-turbine", custom);
        assertEquals(2_638_480, shortFed.euPerTick(), 0);
        // HSS-E Small: 25.6 dense litres, fed 26.
        final Map<String, String> hssE = with(rotor, "rotor", "HSS-E");
        hssE.put("size", "Small");
        final PowerModel small = compute("xl-turbo-sc-steam-turbine", hssE);
        assertEquals(
            26 * 20,
            small.inputs()
                .get(0)
                .perSecond(),
            0);
        assertEquals(31_991, small.euPerTick(), 0);
    }

    @Test
    void largeScSkipsRotorsUnderFullBaseEfficiency() {
        // does not run rotors under 100% base efficiency in the Large SC turbine
        final PowerModel weak = compute("large-sc-steam-turbine", Map.of("rotor", "Carbon", "size", "Small"));
        assertEquals(0, weak.euPerTick(), 0);
        assertEquals(
            0,
            weak.inputs()
                .get(0)
                .perSecond(),
            0);
        assertFirstWarningMatches("under 100%", weak);
        // Exactly 100% runs.
        final PowerModel even = compute("large-sc-steam-turbine", Map.of("rotor", "Carbon", "size", "Normal"));
        assertEquals(100, even.euPerTick(), 0);
    }

    static String lifespan(final String id, final Map<String, String> settings) {
        return statValue(compute(id, settings), "Rotor lifespan");
    }

    @Test
    void rotorsWearAtJavasDamageRolls() {
        // wears rotors at the Java's damage rolls
        // One roll per 1002 ticks landing half the time, per rotor on the XL.
        final Map<String, String> hssE = Map.of("rotor", "HSS-E", "size", "Huge");
        assertEquals("41.43h", lifespan("large-plasma-generator", with(hssE, "fuel", "Helium Plasma")));
        assertEquals("295h", lifespan("xl-turbo-steam-turbine", with(hssE, "grade", "Steam")));
        // MTEXLTurbineGas skips 1 roll in 4 in either fitting.
        assertEquals("260h", lifespan("xl-turbo-gas-turbine", with(hssE, "fuel", "Nitrobenzene")));
        // SC: 2 per roll tight, a coin flip of 0 or 1 loose.
        final Map<String, String> scRotor = Map.of("rotor", "MAR-Ce-M200 Steel", "size", "Large");
        assertEquals("1677h", lifespan("large-sc-steam-turbine", scRotor));
        assertEquals("2155h", lifespan("large-sc-steam-turbine", with(scRotor, "fitting", "loose")));
        // Steam: a loose rotor skips 1 roll in 4.
        final Map<String, String> steamRotor = Map.of("rotor", "Shadow Metal", "size", "Small");
        assertEquals("430h", lifespan("large-steam-turbine", steamRotor));
        assertEquals("345h", lifespan("large-steam-turbine", with(steamRotor, "fitting", "loose")));
    }

    @Test
    void lifespanAtDecimalTieMatchesWebsite() {
        // Not in turbines.test.ts: a value the website shows, where the hours sit on a decimal tie
        // (19.205 in shortest form) and Intl rounds them up.
        final PowerModel model = compute(
            "large-gas-turbine",
            Map.of("rotor", "MAR-M200 Steel", "size", "Large", "fuel", "Naphtha"));
        assertEquals("19.21h", statValue(model, "Rotor lifespan"));
    }

    @Test
    void xlOutputClampsAtIntLimit() {
        // clamps XL output at the int limit
        final PowerModel model = compute(
            "xl-turbo-hp-steam-turbine",
            Map.of("rotor", "Shirabon", "size", "Huge", "grade", "SH Steam", "fitting", "loose"));
        assertEquals(2_147_483_640, model.euPerTick(), 0);
    }
}
