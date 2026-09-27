package com.sbancuz.plannh.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.data.provider.gregtech.GTMachinePreset;

/**
 * The machine table is checked in and diffed against the next GregTech, so this rendering has to move
 * only when a number moves. These assert that property rather than any particular string: an expected
 * string here would just be a copy of the implementation.
 */
class MachineTableNumbersTextTest {

    /** Every field, every time. A snapshot that drops defaults cannot tell "left" from "never set". */
    @Test
    void everyFieldIsAlwaysPresent() {
        final String text = MachineTableCommand.numbersText(
            GTMachinePreset.builder()
                .build());

        for (final String key : new String[] { "par=", "dur=", "eu=", "cost=", "ocD=", "ocE=", "noOC=", "heat=", "hOC=",
            "hDisc=", "rHeat=", "skips=" }) {
            assertTrue(text.contains(key), key + " missing from " + text);
        }
    }

    /**
     * A whole number must not render as "2.0", or every regenerated table diffs against itself the
     * first time a double lands exactly on an integer.
     */
    @Test
    void wholeNumbersCarryNoTrailingZeros() {
        final String text = MachineTableCommand.numbersText(
            GTMachinePreset.builder()
                .speed(_ -> 2.0)
                .eu(_ -> 1.0)
                .build());

        assertTrue(text.contains("dur=2 "), "expected dur=2 in " + text);
        assertTrue(text.contains("eu=1 "), "expected eu=1 in " + text);
        assertFalse(text.contains(".0"), "a trailing .0 survived in " + text);
    }

    /** A decimal separator that follows the machine's locale would diff on someone else's checkout. */
    @Test
    void fractionsUseADot() {
        final String text = MachineTableCommand.numbersText(
            GTMachinePreset.builder()
                .speed(_ -> 0.5)
                .build());

        assertTrue(text.contains("dur=0.5"), "expected dur=0.5 in " + text);
        assertFalse(text.contains(","), "a locale-dependent separator reached the table: " + text);
    }

    /**
     * GregTech writes these as ratios, and a third rounded to 0.33 stops reconciling against anything.
     * Two significant digits only where they are exact.
     */
    @Test
    void ratiosRenderAsFractionsRatherThanRoundedDecimals() {
        final String third = MachineTableCommand.numbersText(
            GTMachinePreset.builder()
                .speed(_ -> 1 / 3.0)
                .build());
        assertTrue(third.contains("dur=1/3"), "expected dur=1/3 in " + third);

        final String quarters = MachineTableCommand.numbersText(
            GTMachinePreset.builder()
                .speed(_ -> 2.25)
                .build());
        assertTrue(quarters.contains("dur=9/4"), "expected dur=9/4 in " + quarters);
    }

    /** A value two significant digits describe exactly stays a decimal, because that is how it reads. */
    @Test
    void exactShortDecimalsStayDecimal() {
        final String text = MachineTableCommand.numbersText(
            GTMachinePreset.builder()
                .speed(_ -> 0.9)
                .eu(_ -> 0.95)
                .build());

        assertTrue(text.contains("dur=0.9 "), "expected dur=0.9 in " + text);
        assertTrue(text.contains("eu=0.95 "), "expected eu=0.95 in " + text);
    }

    /** A value too small for any listed fraction prints as a number, not as a zero numerator. */
    @Test
    void tinyValuesAreNotZeroFractions() {
        final String text = MachineTableCommand.numbersText(
            GTMachinePreset.builder()
                .speed(_ -> 3.6e-12)
                .build());

        assertFalse(text.contains("dur=0/"), "a tiny value rendered as a zero fraction: " + text);
    }
}
