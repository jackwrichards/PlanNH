package com.gtnhplanner.machines.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * precise-assembler.test.ts, table side: both modes' settings, the normal mode's casing parallels and speed, the
 * precise mode's casing gate, and the machine casing's voltage cap. Every case there runs through the solver (power
 * report, overclocks, throughput, hatch migration), which the golden fixture covers end to end.
 */
class PreciseAssemblerTest {

    private static final String NORMAL = "Precise Auto-Assembler MT-3662";
    private static final String PRECISE = "Precise Assembler";

    private static MachineTable.Context casing(final int position, final Integer specialValue) {
        return new MachineTable.Context(
            id -> id.equals("preciseCasing") ? position : 0,
            id -> 0,
            1,
            null,
            specialValue,
            null,
            null,
            null);
    }

    private static List<String> ids(final List<Web.Control> controls) {
        final List<String> out = new ArrayList<>();
        for (final Web.Control c : controls) out.add(c.id);
        return out;
    }

    @Test
    void offersBothModesTheUnitAndMachineCasings() {
        for (final String machine : List.of(PRECISE, NORMAL))
            assertEquals(List.of("preciseCasing", "prassMachineCasing"), ids(MachineTable.controls(machine, null)));
        assertTrue(MachineTable.behaviour(NORMAL).recipeTierFromBase);
        assertFalse(MachineTable.behaviour(PRECISE).recipeTierFromBase);
    }

    @ParameterizedTest(name = "uses {0} casing for {1} normal-mode parallels at twice base speed")
    @CsvSource({ "mk0, 16", "mk1, 32", "mk2, 64", "mk3, 128", "mk4, 256" })
    void usesTheUnitCasingForNormalModeParallels(final String key, final int parallels) {
        final MachineTable.Behaviour normal = MachineTable.behaviour(NORMAL);
        int position = -1;
        for (int i = 0; i < PreciseAssembler.NORMAL_CASING.tiers.size(); i++)
            if (PreciseAssembler.NORMAL_CASING.tiers.get(i).key.equals(key)) position = i;
        assertEquals(parallels, MachineTable.resolve(normal.parallels, casing(position, null), 1));
        assertEquals(200, 400 * (1 / MachineTable.resolve(normal.speed, casing(position, null), 1)));
        assertEquals(1, MachineTable.resolve(normal.power, casing(position, null), 1));
    }

    @Test
    void usesRecipeCasingRequirementsInPreciseModeWithoutAddingParallelsOrSpeed() {
        final Web.Control control = PreciseAssembler.PRECISE_CASING;
        assertEquals(Boolean.TRUE, control.minimumFromSpecialValue);
        final List<String> keys = new ArrayList<>();
        for (final Web.TierOption t : control.tiers) keys.add(t.key);
        assertEquals(List.of("mk1", "mk2", "mk3", "mk4"), keys);

        // Special value 3 needs Mk-III (position 2 on this ladder) or better.
        final MachineTable.Behaviour precise = MachineTable.behaviour(PRECISE);
        for (final int position : new int[] { 2, 3 }) {
            assertNull(precise.recipeGate.apply(casing(position, 3)));
            assertEquals(1, MachineTable.resolve(precise.parallels, casing(position, 3), 1));
            assertEquals(1, MachineTable.resolve(precise.speed, casing(position, 3), 1));
        }
        assertNotNull(precise.recipeGate.apply(casing(1, 3)));
    }

    @Test
    void appliesMachineCasingVoltageBeforeAmpsAndRemovesTheCapAtUhv() {
        assertEquals(6, PreciseAssembler.inputVoltageLimit(Map.of("prassMachineCasing", "luv")));
        assertEquals(5, PreciseAssembler.inputVoltageLimit(Map.of("prassMachineCasing", "iv")));
        assertEquals(Double.POSITIVE_INFINITY, PreciseAssembler.inputVoltageLimit(Map.of("prassMachineCasing", "uhv")));
        // A plan with no stored casing assumes a sufficient one: UHV, uncapped.
        assertEquals(Double.POSITIVE_INFINITY, PreciseAssembler.inputVoltageLimit(Map.of()));
        assertEquals("uhv", PreciseAssembler.MACHINE_CASING.defaultKey);

        // The table's int limit: infinity is Integer.MAX_VALUE, which caps nothing either.
        for (final String machine : List.of(PRECISE, NORMAL)) {
            final MachineTable.Behaviour b = MachineTable.behaviour(machine);
            assertEquals(6, b.inputVoltageTierLimit.applyAsInt(Map.of("prassMachineCasing", "luv")));
            assertEquals(Integer.MAX_VALUE, b.inputVoltageTierLimit.applyAsInt(Map.of("prassMachineCasing", "uhv")));
            assertEquals(Integer.MAX_VALUE, b.inputVoltageTierLimit.applyAsInt(Map.of()));
        }
    }
}
