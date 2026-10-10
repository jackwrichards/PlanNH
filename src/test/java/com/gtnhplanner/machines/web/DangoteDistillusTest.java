package com.gtnhplanner.machines.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * dangote-distillus.test.ts, table side: the two recipe-map modes' speed, EU and parallels, and the stale handler
 * controls it drops. The handler, power report and overclock cases go through the solver and are covered by the
 * golden fixture.
 */
class DangoteDistillusTest {

    private static final String DANGOTE = "Dangote Distillus";

    private static MachineTable.Context ctx(final int voltageTier, final String recipeMap) {
        return new MachineTable.Context(id -> 0, id -> 0, voltageTier, null, null, recipeMap, null, null);
    }

    @Test
    void dropsStaleParallelControlsAndSeedsFromTheRecipesBase() {
        final MachineTable.Behaviour b = MachineTable.behaviour(DANGOTE);
        assertEquals(List.of("machineParallel", "voltageParallel"), MachineTable.hiddenControlIds(DANGOTE));
        assertEquals(List.of(), MachineTable.controls(DANGOTE, null));
        assertTrue(b.recipeTierFromBase);
        assertTrue(MachineTable.seedsFromBase(DANGOTE));
    }

    @ParameterizedTest(name = "scales a 12-layer distillery at {0} to {1} structural parallels")
    @CsvSource({ "LV, 8", "MV, 16", "HV, 24", "EV, 32", "IV, 40", "LuV, 48", "ZPM, 56" })
    void scalesATwelveLayerDistillery(final String tier, final int parallels) {
        final MachineTable.Behaviour b = MachineTable.behaviour(DANGOTE);
        final MachineTable.Context distillery = ctx(Tiers.index(tier), "Distillery");
        assertEquals(parallels, Math.floor(MachineTable.resolve(b.parallels, distillery, 1)));
        assertEquals(0.5, 1 / MachineTable.resolve(b.speed, distillery, 1));
        assertEquals(0.15, MachineTable.resolve(b.power, distillery, 1));

        final MachineTable.Context tower = ctx(Tiers.index(tier), "Distillation Tower");
        assertEquals(12, Math.floor(MachineTable.resolve(b.parallels, tower, 1)));
        assertEquals(1.0 / 3, 1 / MachineTable.resolve(b.speed, tower, 1));
        assertEquals(1, MachineTable.resolve(b.power, tower, 1));
    }
}
