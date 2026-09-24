package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.data.RecipeContext;
import com.sbancuz.plannh.data.Settings;
import com.sbancuz.plannh.data.properties.RecipeProperty;
import com.sbancuz.plannh.data.provider.gregtech.GTSettings;
import com.sbancuz.plannh.data.provider.gregtech.StructureState;

import gregtech.api.enums.HeatingCoilLevel;
import gregtech.common.tileentities.machines.multi.MTEIndustrialCuttingMachine.SawbladeTiers;
import kubatech.loaders.ArcFurnaceElectrode;

/**
 * The coil row stores a tier int but the preset formulas take GT's coil <em>tier</em>,
 * which is {@code ordinal - 2}. Getting that offset wrong shifts every heat overclock by two coil
 * steps and still looks plausible, so it is pinned here.
 */
class GTStructureSettingsTest {

    private static final RecipeContext EMPTY = new RecipeContext(new HashMap<RecipeProperty<?>, Object>());

    @Test
    void coilOptionsAreGatheredFromTheTierRangeStartingAtCupronickel() {
        assertEquals(GTSettings.MAX_COIL_TIER + 1, GTSettings.COIL_DEF.options(EMPTY).size());
        assertEquals("0", GTSettings.COIL_DEF.options(EMPTY).getFirst(), "tier 0 is Cupronickel (LV)");
        assertEquals(
            String.valueOf(GTSettings.MAX_COIL_TIER),
            GTSettings.COIL_DEF.options(EMPTY).getLast(),
            "top tier is Eternal (MAX)");
    }

    @Test
    void everyCoilTierDisplaysItsOwnMaterial() {
        for (int tier = 0; tier <= GTSettings.MAX_COIL_TIER; tier++) {
            final HeatingCoilLevel level = HeatingCoilLevel.getFromTier((byte) tier);
            assertEquals(level.getName(), GTSettings.COIL_DEF.display(String.valueOf(tier)), "tier " + tier);
            assertEquals(tier, level.getTier());
        }
    }

    @Test
    void aStoredCoilTierResolvesToThatTier() {
        final Map<String, Object> settings = Map.of(Settings.GT_COIL.key(), 2);

        // Tier 2 is Nichrome (HV): 3601K.
        assertEquals(
            2,
            StructureState.resolve(EMPTY, settings, 5)
                .get(Settings.GT_COIL));
        assertEquals(3601, HeatingCoilLevel.HV.getHeat());
    }

    /** A stored width is honored, not reset to the widest structure on every resolve. */
    @Test
    void aStoredWidthResolvesToThatWidth() {
        final Map<String, Object> settings = Map.of(Settings.GT_WIDTH.key(), 7);

        assertEquals(7, StructureState.resolve(EMPTY, settings, 5)
            .get(Settings.GT_WIDTH));
    }

    /** Unset settings open on the best structure; a planner should show the endgame number. */
    @Test
    void unsetSettingsDefaultToTheBestStructure() {
        final StructureState state = StructureState.resolve(EMPTY, Map.of(), 5);

        assertEquals(GTSettings.MAX_COIL_TIER, state.get(Settings.GT_COIL));
        assertEquals(GTSettings.MAX_SOLENOID_TIER, state.get(Settings.GT_SOLENOID));
        assertEquals(GTSettings.MAX_ITEM_PIPE_TIER, state.get(Settings.GT_ITEM_PIPE));
        assertEquals(GTSettings.MAX_PIPE_CASING_TIER, state.get(Settings.GT_PIPE_CASING));
        assertEquals(GTSettings.MAX_WIDTH, state.get(Settings.GT_WIDTH));
        assertEquals(5, state.get(Settings.VOLTAGE));
    }

    /** The row shows the block a player places, not the tier number it stores. */
    @Test
    void theCoilRowReadsAsItsMaterial() {
        assertEquals("0", GTSettings.COIL_DEF.options(EMPTY).getFirst(), "storage stays the tier number");
        assertNotEquals(
            "0",
            GTSettings.COIL_DEF.display("0"),
            "but the row must not show 0");
        assertEquals(HeatingCoilLevel.LV.getName(), GTSettings.COIL_DEF.display("0"));
    }

    /**
     * Both machines that read the setting take tier 1 as the Bronze Pipe Casing and count up from there,
     * so the row's own numbers are the tiers and the display is a lookup beside them. A tier outside
     * the range is a stored value from a pack with more casings, and must clamp rather than throw.
     */
    @Test
    void thePipeCasingRowNamesTheCasingAtEachTier() {
        assertEquals(4, GTSettings.MAX_PIPE_CASING_TIER, "Bronze, Steel, Titanium, Tungstensteel");
        assertEquals(
            GTSettings.MAX_PIPE_CASING_TIER,
            GTSettings.PIPE_CASING_DEF.options(EMPTY).size(),
            "options are gathered from the tier range, starting at 1");
        assertEquals("1", GTSettings.PIPE_CASING_DEF.options(EMPTY).getFirst());

        for (int tier = 1; tier <= GTSettings.MAX_PIPE_CASING_TIER; tier++) {
            assertEquals(
                GTSettings.pipeCasingName(tier),
                GTSettings.PIPE_CASING_DEF.display(String.valueOf(tier)),
                "the row and the tier table must name the same casing");
        }
        assertEquals(GTSettings.pipeCasingName(1), GTSettings.pipeCasingName(0));
        assertEquals(
            GTSettings.pipeCasingName(GTSettings.MAX_PIPE_CASING_TIER),
            GTSettings.pipeCasingName(99));
    }

    /** A tier outside the range is a stored value from a pack with more coils, and must clamp. */
    @Test
    void anOutOfRangeCoilTierClampsToTheTable() {
        assertEquals(
            GTSettings.MAX_COIL_TIER,
            StructureState.resolve(EMPTY, Map.of(Settings.GT_COIL.key(), 99), 5)
                .get(Settings.GT_COIL));
        assertEquals(
            0,
            StructureState.resolve(EMPTY, Map.of(Settings.GT_COIL.key(), -5), 5)
                .get(Settings.GT_COIL));
    }

    /** The presets clamp coil tiers to this ceiling, so every tier up to it has to be a real coil. */
    @Test
    void everyCoilTierUpToTheCeilingResolves() {
        for (int tier = 0; tier <= GTSettings.MAX_COIL_TIER; tier++) {
            final HeatingCoilLevel level = HeatingCoilLevel.getFromTier((byte) tier);
            assertEquals(tier, level.getTier(), "coil tier " + tier + " does not round-trip");
            assertTrue(level.getHeat() > 0, "coil tier " + tier + " has no heat");
        }
    }

    /**
     * The electrode and sawblade ceilings come from the installed mods' own enums, so a version that
     * adds or drops a tier moves the rows with it.
     */
    @Test
    void electrodeTiersMatchTheInstalledKubatech() {
        assertEquals(
            ArcFurnaceElectrode.values().length - 1,
            GTSettings.MAX_ELECTRODE_TIER,
            "row ceiling must follow the installed electrodes");
        assertTrue(GTSettings.MAX_ELECTRODE_TIER > 0, "an electrode table of one is not a table");
    }

    @Test
    void sawbladeTiersMatchTheInstalledGregTech() {
        assertEquals(
            SawbladeTiers.values().length - 1,
            GTSettings.MAX_SAWBLADE_TIER,
            "row ceiling must follow the installed sawblades");
        assertTrue(GTSettings.MAX_SAWBLADE_TIER > 0, "a sawblade table of one is not a table");
    }
}
