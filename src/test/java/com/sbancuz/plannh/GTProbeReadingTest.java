package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.data.Settings;
import com.sbancuz.plannh.data.provider.gregtech.GTMachinePreset;
import com.sbancuz.plannh.data.provider.gregtech.StructureState;
import com.sbancuz.plannh.data.provider.gregtech.probe.MachineProbe;
import com.sbancuz.plannh.data.provider.gregtech.probe.ProbeReading;

/**
 * Protocol documentation for the probe's sentinel trick, in the form of one test. The probe hands
 * GregTech a recipe carrying values no real recipe would (see {@code MachineProbe.SENTINEL_*}), so
 * anything that comes back changed was changed by the machine - that is how a fixed heat floor or a
 * rewritten recipe cost is told apart from one passed through untouched. This pins our side of that
 * contract: given a reading where nothing moved, {@code toPreset} must yield no heat handling and no
 * recipe override. It cannot detect GregTech changing its own calculator defaults - the passthrough
 * fixture below restates them by hand, so it would keep passing while production misclassified. That
 * drift is owned by the probe disagreement log and the machine table, not here.
 */
class GTProbeReadingTest {

    private static final StructureState ANY = new StructureState(1, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    private static final EnumSet<Settings> NO_SETTINGS = EnumSet.noneOf(Settings.class);

    /** A machine that touched nothing: every number is the calculator's own default. */
    private static ProbeReading passthrough() {
        return new ProbeReading(
            1,
            1.0,
            1.0,
            4.0,
            2.0,
            1,
            false,
            false,
            0,
            MachineProbe.SENTINEL_HEAT,
            MachineProbe.SENTINEL_EUT,
            MachineProbe.SENTINEL_DURATION,
            false,
            false);
    }

    @Test
    void aPassthroughReadingClaimsNoHeatAndNoRecipeOverride() {
        final GTMachinePreset preset = MachineProbe.toPreset(passthrough(), s -> passthrough(), NO_SETTINGS);

        assertFalse(preset.usesHeat());
        assertEquals(GTMachinePreset.RECIPE_HEAT_FROM_RECIPE, preset.recipeHeatOverride());
        assertNull(preset.recipeOverride());
        assertFalse(preset.unlimitedTierSkips());
        // A machine that reported the calculator's own default said nothing, so the preset leaves it
        // unset and the applier never calls the setter - which lands on that same default.
        assertEquals(GTMachinePreset.TIER_SKIPS_UNSET, preset.maxTierSkips());
    }
}
