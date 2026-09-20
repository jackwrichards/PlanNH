package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.data.Settings;
import com.sbancuz.plannh.data.provider.gregtech.StructureState;
import com.sbancuz.plannh.data.provider.gregtech.probe.ProbeReading;
import com.sbancuz.plannh.data.provider.gregtech.probe.SensitivityScan;

/**
 * The multi-mode sweep rule, which is the one subtle part of the scan: a setting has to be judged
 * in every mode the machine has, because a mode-dependent setting (the Mega Distillation Tower's
 * height, read in distillery mode and ignored in tower mode) judged in one mode alone would lose
 * its row. A stub machine stands in for GregTech here, which lets the rule be tested against a
 * known answer rather than against whatever the pack happens to ship.
 */
class GTSensitivityScanTest {

    private static final StructureState REFERENCE = StructureState.untouched(1)
        .with(Settings.GT_COIL, 5)
        .with(Settings.GT_SOLENOID, 6)
        .with(Settings.GT_ITEM_PIPE, 4)
        .with(Settings.GT_PIPE_CASING, 2)
        .with(Settings.GT_SAWBLADE, 1)
        .with(Settings.GT_STRUCTURE_TIER, 1)
        .with(Settings.GT_WIDTH, 4);

    /** Everything at the calculator's defaults. Individual tests vary one number from this. */
    private static ProbeReading flat() {
        return new ProbeReading(1, 1.0, 1.0, 4.0, 2.0, 1, false, false, 0, 0, 100, 100, false, false);
    }

    private static ProbeReading withParallel(final int parallel) {
        return new ProbeReading(parallel, 1.0, 1.0, 4.0, 2.0, 1, false, false, 0, 0, 100, 100, false, false);
    }

    /** A machine with a third mode must have that mode examined, not just the first two. */
    @Test
    void everyModeTheMachineHasIsScanned() {
        // Only readable in mode 2, which a two-mode sweep would never visit.
        final Function<StructureState, ProbeReading> readings = state -> state.get(Settings.GT_MODE) == 2
            ? withParallel(1 + state.get(Settings.GT_COIL))
            : flat();

        assertFalse(
            SensitivityScan.scan(REFERENCE, EnumSet.of(Settings.GT_COIL, Settings.GT_MODE), 2, readings)
                .contains(Settings.GT_COIL),
            "a two-mode sweep cannot see it");
        assertTrue(
            SensitivityScan.scan(REFERENCE, EnumSet.of(Settings.GT_COIL, Settings.GT_MODE), 3, readings)
                .contains(Settings.GT_COIL),
            "a three-mode machine must have its third mode scanned");
    }

    /**
     * A three-mode machine whose first two modes agree still needs its mode row: judging MODE on a pair
     * of ends would take those two as the whole answer and hide the third.
     */
    @Test
    void aThirdModeThatDiffersEarnsTheModeRow() {
        final Function<StructureState, ProbeReading> readings = state -> state.get(Settings.GT_MODE) == 2
            ? withParallel(9)
            : flat();

        assertTrue(
            SensitivityScan.scan(REFERENCE, EnumSet.of(Settings.GT_MODE), 3, readings)
                .contains(Settings.GT_MODE),
            "mode 2 differs, so the mode row belongs on the node");
        assertFalse(
            SensitivityScan.scan(REFERENCE, EnumSet.of(Settings.GT_MODE), 2, readings)
                .contains(Settings.GT_MODE),
            "with only two modes nothing differs, so no row");
    }
}
