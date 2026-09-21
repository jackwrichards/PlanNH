package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.data.RecipeContext;
import com.sbancuz.plannh.data.provider.gregtech.GTMachineIndex;
import com.sbancuz.plannh.data.provider.gregtech.GTMachineModes;
import com.sbancuz.plannh.data.provider.gregtech.GTMachineOverrides;
import com.sbancuz.plannh.data.provider.gregtech.GTMachinePreset;

/**
 * What little of the applier runs headless: override-table data and the absent-preset contract.
 * Everything that computes needs recipe keys whose classes require a booted game; that coverage
 * lives in {@link GTMachinePresetTest}, {@link GTOverrideTest}, and the client-reviewed table.
 */
class GTPresetApplierTest {

    private static final String MULTI_SMELTER = "gregtech.common.tileentities.machines.multi.MTEMultiFurnace";

    private static GTMachinePreset preset(final String className) throws ClassNotFoundException {
        final GTMachinePreset found = GTMachineOverrides
            .preset(Class.forName(className, false, GTPresetApplierTest.class.getClassLoader()));
        assertNotNull(found, className);
        return found;
    }

    /** The Multi Smelter ignores the recipe's own cost entirely: always 4 EU/t over 128 ticks. */
    @Test
    void multiSmelterOverridesTheRecipeCost() throws ClassNotFoundException {
        final GTMachinePreset smelter = preset(MULTI_SMELTER);
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

    /** A machine with no preset reads no structure, so it offers no rows. */
    @Test
    void absentPresetMeansNoOpinion() {
        final GTMachineIndex.MachineEntry entry = new GTMachineIndex.MachineEntry("test.machine", "Test Machine",
            true, 0, 1, 0, null, null, GTMachineIndex.NumberSource.NONE, new GTMachineModes.Modes(1, null));

        assertTrue(entry.settings()
            .isEmpty());
        // Null preset short-circuits before touching the recipe, so an empty context suffices.
        assertTrue(entry.defaults(new RecipeContext(Map.of()), Map.of())
            .isEmpty());
    }
}
