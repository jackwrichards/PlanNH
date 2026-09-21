package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.data.Settings;
import com.sbancuz.plannh.data.provider.gregtech.GTPresetApplier;

import gregtech.api.enums.GTValues;
import gregtech.api.util.OverclockCalculator;

/**
 * Advanced mode is an override, not a second set of maths: a stored value replaces just that one
 * setting and everything else stays exactly as the machine computed it. The old design ran a wholly
 * separate calculator whose unset rows fell back to global defaults, so ticking Advanced silently
 * changed numbers the user had not touched.
 */
class GTOverrideTest {

    /** What a perfect-OC preset lowers to once it reaches a calculator. */
    private static OverclockCalculator build(final Map<String, Object> settings) {
        return build(settings, GTValues.VP[1]);
    }

    private static OverclockCalculator build(final Map<String, Object> settings, final long recipeEUt) {
        final OverclockCalculator calc = new OverclockCalculator().setRecipeEUt(recipeEUt)
            .setEUt(GTValues.V[5])
            .setDuration(1024)
            .setEUtIncreasePerOC(4.0)
            .setDurationDecreasePerOC(4.0);
        GTPresetApplier.applyOverrides(calc, settings);
        return calc.setParallel(1)
            .setAmperageOC(true)
            .calculate();
    }

    /** The LCR perfect-overclocks; an empty override map must not disturb that. */
    @Test
    void noStoredKeysMeansTheMachinesOwnNumbers() {
        final OverclockCalculator machine = build(Map.of());
        final OverclockCalculator plain = new OverclockCalculator().setRecipeEUt(GTValues.VP[1])
            .setEUt(GTValues.V[5])
            .setDuration(1024)
            .setParallel(1)
            .setAmperageOC(true)
            .calculate();

        assertTrue(machine.getDuration() < plain.getDuration(), "the machine's perfect OC should still apply");
    }

    @Test
    void aStoredOverclockFactorReplacesTheMachines() {
        final OverclockCalculator machine = build(Map.of());
        final OverclockCalculator overridden = build(Map.of(Settings.DURATION_DECREASE_PER_OC.key(), 200));

        assertNotEquals(
            machine.getDuration(),
            overridden.getDuration(),
            "overriding the OC factor must change the result");
    }

    /**
     * The sentinel these replace could not express this: 0 meant "unset", so a machine that forbids
     * tier skipping had no way to say so through the settings map.
     */
    @Test
    void zeroTierSkipsIsExpressible() {
        // One tier over the machine: refused with skipping disabled.
        final OverclockCalculator noSkips = build(Map.of(Settings.MAX_TIER_SKIPS.key(), 0), GTValues.V[6]);

        assertTrue(!noSkips.getAllowedTierSkip(), "a stored 0 must mean no skipping, not 'unset'");
    }

    /** A stored discount equal to the nominal default is still a choice and must be applied. */
    @Test
    void aStoredValueEqualToTheDefaultIsStillApplied() {
        final OverclockCalculator overridden = build(Map.of(Settings.EUT_DISCOUNT.key(), 50));
        final OverclockCalculator machine = build(Map.of());

        assertTrue(overridden.getConsumption() < machine.getConsumption(), "a 50% discount must halve the draw");
        assertEquals(machine.getDuration(), overridden.getDuration(), "an EU discount is not a speed change");
    }
}
