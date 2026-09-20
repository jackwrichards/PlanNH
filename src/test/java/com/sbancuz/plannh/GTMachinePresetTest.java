package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sbancuz.plannh.data.Settings;
import com.sbancuz.plannh.data.provider.gregtech.GTMachineOverrides;
import com.sbancuz.plannh.data.provider.gregtech.GTMachinePreset;
import com.sbancuz.plannh.data.provider.gregtech.GTStructureTiers;
import com.sbancuz.plannh.data.provider.gregtech.StructureState;

import gregtech.api.enums.HeatingCoilLevel;

/**
 * The override rows are hand-copied from one pinned GT version, so they cannot be checked by
 * re-deriving the same arithmetic. These assert the things that actually go wrong instead: keys that
 * stop resolving, and formulas whose direction is inverted.
 */
class GTMachinePresetTest {

    private static final String EBF = "gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace";
    private static final String MULTI_FURNACE = "gregtech.common.tileentities.machines.multi.MTEMultiFurnace";
    private static final String CAL = "bartworks.common.tileentities.multis.MTECircuitAssemblyLine";

    private static StructureState state(final int voltageTier, final int coilTier) {
        return new StructureState(voltageTier, coilTier, 4, 4, 2, 0, 0, 1, 0, 0);
    }

    @Test
    void lookupWalksSuperclasses() throws ClassNotFoundException {
        final Class<?> ebf = Class.forName(
            "gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace",
            false,
            getClass().getClassLoader());

        assertNotNull(GTMachineOverrides.preset(ebf));
        assertTrue(
            GTMachineOverrides.preset(ebf)
                .usesHeat());
    }

    @Test
    void anUnknownMachineHasNoPreset() {
        assertEquals(null, GTMachineOverrides.preset(String.class));
    }

    /**
     * The EBF's machine heat is the coil plus 100K per voltage tier above MV. Getting the
     * HeatingCoilLevel tier offset wrong (getTier() is ordinal - 2) silently shifts every heat
     * overclock by two coil steps.
     */
    @Test
    void blastFurnaceHeatMatchesCoilPlusVoltageBonus() throws ClassNotFoundException {
        final GTMachinePreset ebf = GTMachineOverrides.preset(
            Class.forName(
                "gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace",
                false,
                getClass().getClassLoader()));

        // Cupronickel is tier 0 and 1801K; at MV (tier 2) the voltage bonus is exactly zero.
        assertEquals(
            1801,
            HeatingCoilLevel.getFromTier((byte) 0)
                .getHeat());
        assertEquals(
            1801,
            ebf.machineHeat()
                .applyAsInt(state(2, 0)));
        assertEquals(
            1801 + 300,
            ebf.machineHeat()
                .applyAsInt(state(5, 0)));
    }

    /** More coil is never worse: faster or equal, and never more EU per tick. */
    @ParameterizedTest
    @ValueSource(strings = { EBF, MULTI_FURNACE, CAL })
    void coilDrivenFormulasImproveMonotonically(final String className) throws ClassNotFoundException {
        final GTMachinePreset preset = GTMachineOverrides
            .preset(Class.forName(className, false, getClass().getClassLoader()));
        assertNotNull(preset);
        if (!preset.settings()
            .contains(Settings.GT_COIL)) return;

        for (int coil = 0; coil < GTStructureTiers.MAX_COIL_TIER; coil++) {
            final StructureState low = state(5, coil);
            final StructureState high = state(5, coil + 1);

            assertTrue(
                preset.durationModifier()
                    .applyAsDouble(high)
                    <= preset.durationModifier()
                        .applyAsDouble(low) + 1e-9,
                className + ": coil " + (coil + 1) + " is slower than coil " + coil);
            assertTrue(
                preset.euModifier()
                    .applyAsDouble(high)
                    <= preset.euModifier()
                        .applyAsDouble(low) + 1e-9,
                className + ": coil " + (coil + 1) + " costs more EU than coil " + coil);
            assertTrue(
                preset.machineHeat()
                    .applyAsInt(high)
                    >= preset.machineHeat()
                        .applyAsInt(low),
                className + ": coil " + (coil + 1) + " is colder than coil " + coil);
        }
    }

    /** A bigger machine never runs fewer recipes at once. */
    @ParameterizedTest
    @ValueSource(strings = { EBF, MULTI_FURNACE, CAL })
    void parallelNeverShrinksWithVoltage(final String className) throws ClassNotFoundException {
        final GTMachinePreset preset = GTMachineOverrides
            .preset(Class.forName(className, false, getClass().getClassLoader()));
        assertNotNull(preset);

        for (int tier = 1; tier < 14; tier++) {
            assertTrue(
                preset.maxParallel()
                    .applyAsInt(state(tier + 1, 5))
                    >= preset.maxParallel()
                        .applyAsInt(state(tier, 5)),
                className + ": parallel shrinks going from tier " + tier + " to " + (tier + 1));
        }
    }

    /** Nothing may return a zero or negative parallel; that would zero out a node's throughput. */
    @ParameterizedTest
    @ValueSource(strings = { EBF, MULTI_FURNACE, CAL })
    void parallelIsAlwaysPositive(final String className) throws ClassNotFoundException {
        final GTMachinePreset preset = GTMachineOverrides
            .preset(Class.forName(className, false, getClass().getClassLoader()));
        assertNotNull(preset);

        for (int tier = 1; tier <= 14; tier++) {
            assertTrue(
                preset.maxParallel()
                    .applyAsInt(state(tier, 0)) >= 1,
                className + ": non-positive parallel at tier " + tier);
        }
    }

    /** Duration and EU multipliers are ratios; a non-positive one would invert or zero the recipe. */
    @ParameterizedTest
    @ValueSource(strings = { EBF, MULTI_FURNACE, CAL })
    void modifiersStayPositive(final String className) throws ClassNotFoundException {
        final GTMachinePreset preset = GTMachineOverrides
            .preset(Class.forName(className, false, getClass().getClassLoader()));
        assertNotNull(preset);

        for (int coil = 0; coil <= GTStructureTiers.MAX_COIL_TIER; coil++) {
            final StructureState s = state(5, coil);
            assertTrue(
                preset.durationModifier()
                    .applyAsDouble(s) > 0,
                className + ": duration modifier <= 0");
            assertTrue(
                preset.euModifier()
                    .applyAsDouble(s) > 0,
                className + ": eu modifier <= 0");
            assertTrue(
                preset.eutIncreasePerOC()
                    .applyAsDouble(s) > 0,
                className + ": eutIncreasePerOC <= 0");
            assertTrue(
                preset.durationDecreasePerOC()
                    .applyAsDouble(s) > 0,
                className + ": durationDecreasePerOC <= 0");
        }
    }
}
