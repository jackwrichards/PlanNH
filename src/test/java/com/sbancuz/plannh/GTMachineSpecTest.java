package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.data.Settings;
import com.sbancuz.plannh.data.provider.gregtech.GTMachineSpec;
import com.sbancuz.plannh.data.provider.gregtech.StructureState;

import gregtech.api.enums.GTValues;
import gregtech.api.logic.ProcessingSpec;
import gregtech.api.structure.StructureParameter;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.OverclockCalculator;
import gregtech.api.util.tooltip.TooltipTier;

/**
 * The spec is GregTech's, so these pin only what PlanNH adds on top: which structure a node's state stands for, and
 * when a machine earns its mode row.
 */
class GTMachineSpecTest {

    private static final Map<TooltipTier, StructureParameter> COIL_0_TO_13 = Map.of(
        TooltipTier.COIL,
        StructureParameter.builder(TooltipTier.COIL)
            .between(0, 13)
            .getter(() -> 0)
            .setter(tier -> {})
            .build());

    private static GTMachineSpec read(final ProcessingSpec spec) {
        return GTMachineSpec.of(spec, COIL_0_TO_13, 1);
    }

    /** An untouched node shows every structure parameter at its maximum, so that is what an unset one reads as. */
    @Test
    void anUnsetStructureValueIsItsMaximum() {
        final GTMachineSpec machine = read(
            ProcessingSpec.builder()
                .parallel(in -> 2 * in.tier(TooltipTier.COIL))
                .parallelPerTier(3, TooltipTier.VOLTAGE)
                .build());

        assertEquals(2 * 13 + 3 * 5, machine.maxParallel(StructureState.of(5, 0)));
        assertEquals(2 * 4 + 3 * 5, machine.maxParallel(new StructureState(5, 0, Map.of(TooltipTier.COIL, 4))));
    }

    @Test
    void aSpecThatReadsAnUndeclaredKindFails() {
        assertThrows(
            IllegalArgumentException.class,
            () -> GTMachineSpec.of(
                ProcessingSpec.builder()
                    .parallelPerTier(2, TooltipTier.SOLENOID)
                    .build(),
                Map.of(),
                1));
    }

    /** A mode row is only worth offering when switching mode moves a number, the steam furnace's cost included. */
    @Test
    void aModeRowOnlyWhenTheModeMatters() {
        final ProcessingSpec byMode = ProcessingSpec.builder()
            .parallel(in -> in.mode() == 0 ? 1 : 32)
            .build();
        final ProcessingSpec costByMode = ProcessingSpec.builder()
            .euModifierNotLimitingParallel(in -> in.mode() == 0 ? 1 : 2)
            .build();

        assertTrue(
            GTMachineSpec.of(byMode, Map.of(), 2)
                .settings()
                .contains(Settings.GT_MODE));
        assertTrue(
            GTMachineSpec.of(costByMode, Map.of(), 2)
                .settings()
                .contains(Settings.GT_MODE));
        assertFalse(
            GTMachineSpec.of(ProcessingSpec.STANDARD, Map.of(), 2)
                .settings()
                .contains(Settings.GT_MODE));
        assertFalse(
            GTMachineSpec.of(byMode, Map.of(), 1)
                .settings()
                .contains(Settings.GT_MODE),
            "a machine with one mode has no mode to switch");
    }

    /** Planners assume the machine at its best: full momentum, a stable black hole. */
    @Test
    void bestCaseNumbersArePlannedAtTheirBest() {
        final GTMachineSpec machine = read(
            ProcessingSpec.builder()
                .parallelPerTier(8, TooltipTier.VOLTAGE)
                .speed(4)
                .bestCase(ProcessingSpec.Quantity.PARALLEL, ProcessingSpec.Quantity.DURATION)
                .build());
        final GTRecipe recipe = mock(GTRecipe.class);
        recipe.mEUt = 30;
        recipe.mDuration = 200;

        final OverclockCalculator calculator = machine.calculator(recipe, StructureState.of(5, 0), GTValues.V[5], 1);

        assertEquals(40, machine.maxParallel(StructureState.of(5, 0)));
        assertEquals(0.25, calculator.getDurationModifier());
    }
}
