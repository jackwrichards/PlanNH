package com.gtnhplanner.machines.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * industrial-coke-oven.test.ts, table side: issue #58's oven (Kanthal coils, heat-resistant casing, one slice) and the
 * brick Coke Oven under the legacy alias. The hatch migration, power report and overclock cases go through the solver
 * and are covered by the golden fixture.
 */
class IndustrialCokeOvenTest {

    private static MachineTable.Context ctx(final Map<String, Integer> tiers, final Map<String, Double> values) {
        return new MachineTable.Context(
            id -> tiers.getOrDefault(id, 0),
            id -> values.getOrDefault(id, 0.0),
            4,
            null,
            null,
            null,
            null,
            null);
    }

    @Test
    void runsSixteenParallelsAtTheKanthalDiscount() {
        // MTEIndustrialCokeOven: 16 base parallels, Kanthal modifier 0.98^2, normal overclocks.
        final MachineTable.Behaviour b = MachineTable.behaviour("Industrial Coke Oven");
        final MachineTable.Context c = ctx(
            Map.of("heatingCoil", 1, "cokeOvenCasing", 0),
            Map.of("cokeOvenSlices", 1.0));
        assertEquals(16, MachineTable.resolve(b.parallels, c, 1));
        assertEquals(Math.pow(0.98, 2), MachineTable.resolve(b.power, c, 1), 1e-12);
        assertEquals(MachineTable.normal(), MachineTable.overclockSpec(b, c));
    }

    @Test
    void keepsTheOldIndustrialMapLabelEquivalentWithoutGivingBrickOvensIndustrialSettings() {
        assertSame(MachineTable.behaviour("Industrial Coke Oven"), MachineTable.behaviour("Coke Oven"));
        // A brick oven has no slices control: one op at a time, and a saved voltage buys no overclocks.
        final MachineTable.Behaviour b = MachineTable.behaviour("Coke Oven");
        final MachineTable.Context brick = ctx(Map.of(), Map.of());
        assertEquals(1, MachineTable.resolve(b.parallels, brick, 1));
        assertEquals(MachineTable.none(), MachineTable.overclockSpec(b, brick));
    }
}
