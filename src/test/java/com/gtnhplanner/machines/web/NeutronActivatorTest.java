package com.gtnhplanner.machines.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * neutron-activator.test.ts, table side: the open-ended height control, and the height's speed and tick rounding as
 * the solver spends them on a recipe (duration x 1 / speed, then this machine's own rounding). The cases through the
 * solver itself, the controls and project loading are covered by the golden fixture.
 */
class NeutronActivatorTest {

    private static final String NA = "Neutron Activator";

    private static MachineTable.Context height(final int height) {
        return new MachineTable.Context(
            id -> 0,
            id -> id.equals("speedingPipeCasing") ? height : 0,
            1,
            null,
            null,
            null,
            null,
            null);
    }

    /** The solver's duration with no overclock steps: duration x (1 / speed), then the machine's rounding. */
    private static double duration(final double durationTicks, final int height) {
        final MachineTable.Behaviour b = MachineTable.behaviour(NA);
        final double speed = MachineTable.resolve(b.speed, height(height), 1);
        final double multiplier = speed > 0 ? 1 / speed : 1;
        return b.quantiseDuration.applyAsDouble(durationTicks / 1 / 1 * multiplier);
    }

    @Test
    void offersAnOpenEndedPipeHeightFromFour() {
        final List<Web.Control> controls = MachineTable.controls(NA, null);
        assertEquals(1, controls.size());
        final Web.Control control = controls.get(0);
        assertEquals("speedingPipeCasing", control.id);
        assertEquals(4, control.numeric.min);
        assertNull(control.numeric.max);
        assertNull(control.numeric.step);
        assertEquals("4", control.minimumKey);
    }

    @ParameterizedTest(name = "runs height {0} at {1} equivalent ticks")
    @CsvSource({ "4, 400", "5, 360", "12, 173", "32, 21", "60, 2", "64, 1" })
    void runsAHeightAtItsEquivalentTicks(final int height, final double ticks) {
        assertEquals(ticks, duration(400, height));
        final MachineTable.Behaviour b = MachineTable.behaviour(NA);
        assertEquals(MachineTable.none(), b.overclock);
        assertEquals(0, MachineTable.resolve(b.power, height(height), 1));
    }

    @Test
    void runsHeight100AtASixtyFirstOfATick() {
        assertEquals(1.0 / 61, duration(400, 100));
    }

    @Test
    void usesJavaFloatPrecisionAtAnIntegerBoundary() {
        // 100 * 0.9f^2 is just below 81, not the 81.00000000000001 of repeated decimal operations, which would ceil
        // to 82.
        assertEquals(81, duration(100, 6));
    }

    @Test
    void saturatesAtTheGamesParallelIntegerLimitInsteadOfBecomingSlowOnOverflow() {
        assertEquals(1.0 / 2_147_483_647, duration(400, 10000));
    }
}
