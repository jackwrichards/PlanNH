package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.data.Settings;
import com.sbancuz.plannh.data.provider.gregtech.GTMachinePreset;
import com.sbancuz.plannh.data.provider.gregtech.GTSettings;
import com.sbancuz.plannh.data.provider.gregtech.GTSpecReader;
import com.sbancuz.plannh.data.provider.gregtech.StructureState;

import gregtech.api.logic.ProcessingSpec;
import gregtech.api.util.tooltip.TooltipTier;

/**
 * The spec is GregTech's, so these pin only what PlanNH adds on top: which structure a node's state stands for, and
 * how the spec's settings land on the preset the applier reads.
 */
class GTSpecReaderTest {

    private static final Map<TooltipTier, GTSettings.TierRange> COIL_0_TO_13 = Map
        .of(TooltipTier.COIL, new GTSettings.TierRange(0, 13));

    private static GTMachinePreset read(final ProcessingSpec spec) {
        return GTSpecReader.toPreset(spec, COIL_0_TO_13, 1);
    }

    /** An untouched node shows every structure parameter at its maximum, so that is what an unset one reads as. */
    @Test
    void anUnsetStructureValueIsItsMaximum() {
        final GTMachinePreset preset = read(
            ProcessingSpec.builder()
                .parallelPerTier(2, TooltipTier.COIL)
                .parallelPerTier(3, TooltipTier.VOLTAGE)
                .build());

        assertEquals(
            2 * 13 + 3 * 5,
            preset.maxParallel()
                .applyAsInt(StructureState.of(5, 0)));
        assertEquals(
            2 * 4 + 3 * 5,
            preset.maxParallel()
                .applyAsInt(new StructureState(5, 0, Map.of(TooltipTier.COIL, 4))));
    }

    @Test
    void heatSettingsCarryOver() {
        final GTMachinePreset preset = read(
            ProcessingSpec.builder()
                .heatOverclock(in -> 1000 * in.tier(TooltipTier.COIL))
                .heatDiscount()
                .recipeHeat(0)
                .build());

        assertTrue(preset.heatOC());
        assertTrue(preset.heatDiscount());
        assertEquals(0, preset.recipeHeatOverride());
        assertEquals(
            4000,
            preset.machineHeat()
                .applyAsInt(new StructureState(1, 0, Map.of(TooltipTier.COIL, 4))));
    }

    /** One is what the calculator starts at; zero is a real answer, which steam machines give. */
    @Test
    void tierSkipsLeaveTheDefaultUnset() {
        assertEquals(GTMachinePreset.TIER_SKIPS_UNSET, read(ProcessingSpec.STANDARD).maxTierSkips());
        assertEquals(
            0,
            read(
                ProcessingSpec.builder()
                    .maxTierSkips(0)
                    .build()).maxTierSkips());
        assertTrue(
            read(
                ProcessingSpec.builder()
                    .unlimitedTierSkips()
                    .build()).unlimitedTierSkips());
    }

    @Test
    void aFixedRecipeCostCarriesOver() {
        assertNull(read(ProcessingSpec.STANDARD).recipeOverride());
        final GTMachinePreset smelter = read(
            ProcessingSpec.builder()
                .recipeOverride(4, 128)
                .build());
        assertNotNull(smelter.recipeOverride());
        assertEquals(
            4,
            smelter.recipeOverride()
                .eut());
        assertEquals(
            128,
            smelter.recipeOverride()
                .duration());
    }

    /** A mode row is only worth offering when switching mode moves a number. */
    @Test
    void aModeRowOnlyWhenTheModeMatters() {
        final ProcessingSpec byMode = ProcessingSpec.builder()
            .parallel(in -> in.mode() == 0 ? 1 : 32)
            .build();
        assertTrue(
            GTSpecReader.toPreset(byMode, Map.of(), 2)
                .settings()
                .contains(Settings.GT_MODE));
        assertFalse(
            GTSpecReader.toPreset(ProcessingSpec.STANDARD, Map.of(), 2)
                .settings()
                .contains(Settings.GT_MODE));
        assertFalse(
            GTSpecReader.toPreset(byMode, Map.of(), 1)
                .settings()
                .contains(Settings.GT_MODE),
            "a machine with one mode has no mode to switch");
    }

    /** A steam multiblock: its own voltage, a cost that does not count against parallels, and no describer. */
    @Test
    void noOverclockAndEnergyCostCarryOver() {
        final GTMachinePreset preset = read(
            ProcessingSpec.builder()
                .energyCost(in -> 1.25 * in.tier(TooltipTier.COIL))
                .noOverclock()
                .build());

        assertTrue(preset.noOverclock());
        assertTrue(preset.ownsOverclock(), "a spec that states its overclock outranks a describer");
        assertEquals(
            2.5,
            preset.energyCost()
                .applyAsDouble(new StructureState(1, 0, Map.of(TooltipTier.COIL, 2))));
        assertFalse(read(ProcessingSpec.STANDARD).ownsOverclock());
    }

    @Test
    void bestCaseNumbersAreMarked() {
        final GTMachinePreset preset = read(
            ProcessingSpec.builder()
                .parallelPerTier(8, TooltipTier.VOLTAGE)
                .bestCase(ProcessingSpec.Quantity.PARALLEL)
                .build());

        assertEquals(Set.of(ProcessingSpec.Quantity.PARALLEL), preset.bestCase());
        assertEquals(
            40,
            preset.maxParallel()
                .applyAsInt(StructureState.of(5, 0)),
            "planned at the best case");
    }
}
