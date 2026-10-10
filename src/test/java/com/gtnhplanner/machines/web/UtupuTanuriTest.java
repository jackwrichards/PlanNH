package com.gtnhplanner.machines.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * utupu-tanuri.test.ts: one entry under both exported maps, and the table side of the cases that go through the
 * solver and recipe rules (coil minimum, heat, parallels, speed and EU), which the golden fixture covers end to end.
 */
class UtupuTanuriTest {

    private static MachineTable.Context ctx(final int voltageTier) {
        return new MachineTable.Context(id -> 0, id -> 0, voltageTier, null, null, null, null, null);
    }

    @Test
    void recognizesBothExportedMapsAsTheSameMultiblockWithoutAbsorbingSingleblocks() {
        assertSame(MachineTable.behaviour("Utupu-Tanuri"), MachineTable.behaviour("Vacuum Furnace"));
        assertSame(MachineTable.behaviour("Utupu-Tanuri"), MachineTable.behaviour("Multiblock Dehydrator"));
        assertNull(MachineTable.behaviour("Dehydrator"));
        assertNull(MachineTable.behaviour("Chemical Dehydrator"));
    }

    @ParameterizedTest(name = "uses {0} K to choose minimum {1}")
    @CsvSource({ "0, cupronickel", "5500, hss_s", "7200, naquadah" })
    void offersTheCoilsThatMeetTheRecipesHeat(final int heat, final String minimum) {
        // Table side: the coil control takes its floor from the recipe's heat; the first coil whose heat meets it.
        final List<Web.Control> controls = MachineTable.controls("Vacuum Furnace", null);
        assertEquals(1, controls.size());
        final Web.Control coil = controls.get(0);
        assertEquals("heatingCoil", coil.id);
        assertEquals(Boolean.TRUE, coil.minimumHeatFromSpecialValue);
        String first = null;
        for (final Web.TierOption t : coil.tiers) if (first == null && t.heat >= heat) first = t.key;
        assertEquals(minimum, first);
    }

    @Test
    void usesFourParallels22xSpeedAndHalfEuBeforeHeatBonuses() {
        final MachineTable.Behaviour b = MachineTable.behaviour("Vacuum Furnace");
        assertSame(MachineTable.HEAT, MachineTable.overclockSpec(b, ctx(8)));
        // Off its raw coil heat: no 100 K per voltage tier.
        assertNull(b.heat);
        assertEquals(4, MachineTable.resolve(b.parallels, ctx(8), 1));
        assertEquals(2.2, MachineTable.resolve(b.speed, ctx(8), 1));
        assertEquals(0.5, MachineTable.resolve(b.power, ctx(8), 1));
        // floor(1200 / 2.2 / 2), as the overclocked duration's first factor.
        assertEquals(272, Math.floor(1200 * (1 / MachineTable.resolve(b.speed, ctx(8), 1)) / 2));
    }
}
