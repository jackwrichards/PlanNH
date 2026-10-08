package com.gtnhplanner.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Arrays;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

import org.junit.jupiter.api.Test;

/**
 * The rotor catalog follows the pack's Java. Ported from the website's rotor-data.test.ts; every {@code f32} is
 * {@code Math.fround}, every other step a JavaScript double.
 */
class RotorDataTest {

    private static double f32(final double value) {
        return (float) value;
    }

    /** {@code Math.trunc}. */
    private static double trunc(final double value) {
        return value < 0 ? Math.ceil(value) : Math.floor(value);
    }

    /** JavaScript's {@code Math.round}: halves toward positive infinity. */
    private static double jsRound(final double value) {
        final double floor = Math.floor(value);
        return value - floor >= 0.5 ? floor + 1 : floor;
    }

    private static final double[] SIZE_DAMAGE = { 0, 2.5, 5, 7.5 };

    private record JavaLoose(double base, double steamEfficiency, double gasEfficiency, double plasmaEfficiency,
        DoubleUnaryOperator steamFlow, DoubleUnaryOperator gasFlow, DoubleUnaryOperator plasmaFlow) {}

    /**
     * TurbineStatCalculator's loose columns in Java float, from the tool quality and the rotor's own tight flows: base
     * efficiency is 0.5F + (0.5F + damage + quality) x 0.1F, loose efficiency rounds base x 85 on the float product,
     * loose flows scale the tight flow by 1.1 / 1.05 / 1.03 to the power (base - 0.8) x 20. StrictMath.pow is
     * fdlibm's, as JavaScript's Math.pow.
     */
    private static JavaLoose javaLoose(final double quality, final int size) {
        final double base = f32(0.5 + f32(f32(0.5 + f32(SIZE_DAMAGE[size] + quality)) * f32(0.1)));
        final double loose = f32(f32(-0.2) + jsRound(f32(base * 85)) * 0.01);
        final double exponent = f32(f32(base - f32(0.8)) * 20);
        return new JavaLoose(
            base,
            f32(loose * f32(0.9)),
            f32(loose * f32(0.95)),
            loose,
            tight -> f32(f32(3 * tight) * f32(StrictMath.pow(f32(1.1), exponent))),
            tight -> f32(f32(2 * tight) * f32(StrictMath.pow(f32(1.05), exponent))),
            tight -> f32(f32(2 * tight) * f32(StrictMath.pow(f32(1.03), exponent))));
    }

    @Test
    void looseColumnsAndOverflowTierMatchTurbineStatCalculator() {
        // every rotor's loose columns and overflow tier match TurbineStatCalculator
        for (final PowerData.Rotor rotor : PowerData.get().rotors) {
            final double quality = jsRound((rotor.steam.efficiencyTight[0] - 0.55) / 0.1);
            assertEquals(1 + Math.min(2, trunc(quality / 3)), rotor.overflowTier, 0, rotor.name);
            for (int size = 0; size < 4; size++) {
                final double tight = rotor.steam.efficiencyTight[size];
                assertEquals(0.5 + (0.5 + SIZE_DAMAGE[size] + quality) * 0.1, tight, 0.5e-9, rotor.name);
                final JavaLoose java = javaLoose(quality, size);
                final String label = rotor.name + " size " + size;
                assertEquals(java.steamEfficiency(), rotor.steam.efficiencyLoose[size], 0, label);
                assertEquals(java.gasEfficiency(), rotor.gas.efficiencyLoose[size], 0, label);
                assertEquals(java.plasmaEfficiency(), rotor.plasma.efficiencyLoose[size], 0, label);
                assertEquals(
                    java.steamFlow()
                        .applyAsDouble(rotor.steam.optimalTight[size]),
                    rotor.steam.optimalLoose[size],
                    0,
                    label);
                assertEquals(
                    java.gasFlow()
                        .applyAsDouble(rotor.gas.optimalTight[size]),
                    rotor.gas.optimalLoose[size],
                    0,
                    label);
                assertEquals(
                    java.plasmaFlow()
                        .applyAsDouble(rotor.plasma.optimalTight[size]),
                    rotor.plasma.optimalLoose[size],
                    0,
                    label);
                assertNotNull(rotor.plasma.euAtOptimalTight, label);
                assertEquals(
                    f32(rotor.plasma.optimalTight[size] * java.base()),
                    rotor.plasma.euAtOptimalTight[size],
                    0,
                    label);
            }
        }
    }

    @Test
    void looseEfficiencyAtFloatTieRoundsDown() {
        // loose efficiency at a float tie rounds down like the game (81%, not 81.9%)
        final PowerData data = PowerData.get();
        // Base 1.3 is 1.2999999523 in float, so Math.round(base * 85) is 110.
        assertEquals(810, jsRound(data.findRotor("Manyullyn").steam.efficiencyLoose[1] * 1000), 0);
        assertEquals(900, jsRound(data.findRotor("Nickel-Zinc Ferrite").plasma.efficiencyLoose[3] * 1000), 0);
        // Base 2.1 likewise: 1.58, not 1.59.
        assertEquals(1580, jsRound(data.findRotor("HSS-S").plasma.efficiencyLoose[3] * 1000), 0);
    }

    @Test
    void highDurabilityCompoundSteelUsesWerkstoffStats() {
        // High Durability Compound Steel uses its Werkstoff stats
        // WerkstoffLoader.HDCS: durability 291, speed 49.99, quality 13.
        final PowerData data = PowerData.get();
        final PowerData.Rotor rotor = data.findRotor("High Durability Compound Steel");
        assertEquals(29_100, rotor.durability, 0);
        assertEquals(116_400, data.rotorDurability(rotor, 3), 0);
        assertEquals(3, rotor.overflowTier, 0);
        assertEquals(
            List.of(2499.54541015625, 4999.0908203125, 7498.63671875, 9998.181640625),
            Arrays.asList(rotor.steam.optimalTight));
        assertEquals(104980.90625, rotor.plasma.optimalTight[0], 0);
    }

    @Test
    void atomicSeparationCatalystUsesOrundumMass() {
        // Atomic Separation Catalyst uses Orundum's mass of 196
        // GGMaterial.atomicSeparationCatalyst: durability 2590, speed 33.4, quality 10.
        final PowerData.Rotor rotor = PowerData.get()
            .findRotor("Atomic Separation Catalyst");
        assertEquals(259_000, rotor.durability, 0);
        assertEquals(3, rotor.overflowTier, 0);
        assertEquals(
            List.of(1670.0001220703125, 3340.000244140625, 5010.0, 6680.00048828125),
            Arrays.asList(rotor.gas.optimalTight));
    }

    @Test
    void universiumHasToolQualityThirty() {
        // Universium has tool quality 30
        // MaterialsInit.loadUniversium: setTool(10_485_760, 30, 1.0f).
        final PowerData.Rotor rotor = PowerData.get()
            .findRotor("Universium");
        final double[] expected = { 3.55, 3.8, 4.05, 4.3 };
        for (int size = 0; size < rotor.steam.efficiencyTight.length; size++) {
            assertEquals(expected[size], rotor.steam.efficiencyTight[size], 0.5e-9);
        }
        assertEquals(List.of(50.0, 100.0, 150.0, 200.0), Arrays.asList(rotor.steam.optimalTight));
        assertEquals(1_048_576_000, rotor.durability, 0);
    }
}
