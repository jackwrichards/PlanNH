package com.gtnhplanner.machines.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * extreme-heat-exchanger.test.ts, table side: the fixed 20-tick cycle and no overclocking at any voltage. The
 * duration, overclock and throughput cases go through the recipe rules and the solver and are covered by the golden
 * fixture.
 */
class ExtremeHeatExchangerTest {

    @Test
    void runsOnItsTwentyTickCycleWithNoOverclocks() {
        final MachineTable.Behaviour b = MachineTable.behaviour("Extreme Heat Exchanger");
        assertEquals(20, b.cycleTicks);
        for (final String tier : new String[] { "ULV", "LV", "IV", "MAX" }) {
            final MachineTable.Context c = new MachineTable.Context(
                id -> 0,
                id -> 0,
                Tiers.index(tier),
                null,
                null,
                null,
                null,
                null);
            assertEquals(MachineTable.none(), MachineTable.overclockSpec(b, c));
        }
        assertNull(b.speed);
        assertNull(b.power);
        assertNull(b.parallels);
    }
}
