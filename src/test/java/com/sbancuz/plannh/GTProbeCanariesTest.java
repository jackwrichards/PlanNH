package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.sbancuz.plannh.data.Settings;
import com.sbancuz.plannh.data.provider.gregtech.probe.StructureWriter;

/**
 * One canary per structure mechanism the probe claims to support.
 *
 * <p>
 * This is the inversion of the old coverage test, which asked "what does GregTech contain that we
 * do not explain" and was red forever: its universe was GT's codebase. This one asks "do the
 * mechanisms we promise still resolve" and is green unless action is needed: its universe is our
 * claim list below. A red row means a supported mechanism broke on a GT update - run a client,
 * {@code /plannh_machines}, look at that machine's rows column, and fix
 * {@code StructureWriter.BY_NAME} (possibly with a new {@code Coding} and {@code Settings}
 * constant) or add a {@code GTMachineOverrides} row.
 *
 * <p>
 * Rules for this file, so it does not rot back into an audit:
 *
 * <ul>
 * <li>One canary per mechanism, not per machine. A new row belongs here only when PlanNH learns a
 * new {@code Coding}; a new GT machine using an existing mechanism is covered by its mechanism's
 * canary and needs no row.
 * <li>Expectations are {@link Settings}, never field names. If GT renames {@code mCoilTier} and the
 * mapping is updated, the canary passes unchanged - it asserts our claim, not GT's spelling.
  * <li>Subset, not equality: more reachable than expected is fine. A field being present only means
  * the machine stores it; the sensitivity scan narrows it to what moves a number.
  * <li>Canary rows change only in the same commit as an intentional mechanism change.
  * <li>The list audits itself: {@link #theCanariesCoverEveryRecognizedSetting} fails when a
  * {@code Coding} has no canary, so the set cannot rot one side at a time.
  * </ul>
 *
 * <p>
 * The override machines ({@code EBF}, {@code MultiFurnace}) appear below as <em>mapping</em>
 * canaries only: the probe never reads them, but their fields exercise two {@code Coding}s no
 * probed machine below covers. Their existence as classes is guarded by the override
 * consistency test instead.
 */
class GTProbeCanariesTest {

    private static final String GT_MULTI = "gregtech.common.tileentities.machines.multi.";
    private static final String GTPP = "gtPlusPlus.xmod.gregtech.common.tileentities.machines.multi.";
    private static final String KUBATECH = "kubatech.tileentity.gregtech.multiblock.";

    /**
     * One row per mechanism, held as data rather than annotations so the coverage assertion below
     * can read the same list. Each row names the {@code Coding} it guards in a comment. EBF and
     * MultiFurnace are override machines kept as mapping specimens (see class javadoc); everything
     * else is probe-read at runtime.
     */
    private record Canary(String className, EnumSet<Settings> expected) {}

    private static List<Canary> canaries() {
        return List.of(
            // COIL_LEVEL (HeatingCoilLevel-typed field) + SLICES (width) + CASING_TIER (tier).
            new Canary(
                GT_MULTI + "MTEIndustrialCokeOven",
                EnumSet.of(Settings.GT_COIL, Settings.GT_WIDTH, Settings.GT_STRUCTURE_TIER)),
            // COIL_LEVEL + SOLENOID_TIER (solenoidLevel, boxed Byte).
            new Canary(
                GT_MULTI + "MTEIndustrialThermalCentrifuge", EnumSet.of(Settings.GT_COIL, Settings.GT_SOLENOID)),
            // ITEM_PIPE_TIER, two unrelated witnesses sharing the Coding.
            new Canary(GT_MULTI + "MTEIndustrialMixer", EnumSet.of(Settings.GT_ITEM_PIPE)),
            new Canary(GT_MULTI + "MTEIndustrialWireMill", EnumSet.of(Settings.GT_ITEM_PIPE)),
            // CASING_TIER (controllerTier, structureTier).
            new Canary(GT_MULTI + "MTEIndustrialMacerator", EnumSet.of(Settings.GT_STRUCTURE_TIER)),
            // COIL_LEVEL.
            new Canary(GT_MULTI + "MTEMegaOilCracker", EnumSet.of(Settings.GT_COIL)),
            // COIL_LEVEL, spelt coilHeat - type wins over name.
            new Canary(GT_MULTI + "MTEPyrolyseOven", EnumSet.of(Settings.GT_COIL)),
            // COIL_HEAT (int mHeatingCapacity). Override machine; mapping specimen only.
            new Canary(GT_MULTI + "MTEElectricBlastFurnace", EnumSet.of(Settings.GT_COIL)),
            // COIL_TIER_FROM_ONE (int mLevel, GT++'s absent-coil-reads-as-one). Override machine;
            // mapping specimen only.
            new Canary(GT_MULTI + "MTEMultiFurnace", EnumSet.of(Settings.GT_COIL)),
            // COIL_TIER (int mCoilTier) + PIPE_CASING_TIER (int mPipeCasingTier).
            new Canary(
                GTPP + "production.chemplant.MTEChemicalPlant",
                EnumSet.of(Settings.GT_COIL, Settings.GT_PIPE_CASING)),
            // COIL_LEVEL.
            new Canary(GTPP + "processing.MTEIndustrialAlloySmelter", EnumSet.of(Settings.GT_COIL)),
            // COIL_LEVEL.
            new Canary(GTPP + "processing.advanced.MTEAdvEBF", EnumSet.of(Settings.GT_COIL)),
            // Sawblade: no numeric field, recognised by isValidSawblade declaring itself.
            new Canary(GT_MULTI + "MTEIndustrialCuttingMachine", EnumSet.of(Settings.GT_SAWBLADE)),
            // ELECTRODE_ITEM: recognised by field type, kubatech's own enum.
            new Canary(KUBATECH + "MTEIndustrialArcFurnace", EnumSet.of(Settings.GT_ELECTRODE)),
            // MACHINE_MODE: the machine overrides supportsMachineModeSwitch for itself. The
            // negative half (no declaration, no row) lives in the mode test below.
            new Canary(GT_MULTI + "MTEOreWashingPlant", EnumSet.of(Settings.GT_MODE)));
    }

    @ParameterizedTest
    @MethodSource("canaries")
    void eachSupportedMechanismStillResolves(final Canary canary) {
        final EnumSet<Settings> reachable = StructureWriter.forClass(uninitialised(canary.className()))
            .reachableSettings();
        for (final Settings setting : canary.expected()) {
            assertTrue(
                reachable.contains(setting),
                canary.className() + " no longer exposes " + setting + ", found " + reachable);
        }
    }

    /**
     * The list above must cover every setting the mapper can yield. A {@code Coding} added without
     * a canary row fails here, not silently months later.
     */
    @Test
    void theCanariesCoverEveryRecognizedSetting() {
        final EnumSet<Settings> covered = EnumSet.noneOf(Settings.class);
        for (final Canary canary : canaries()) {
            covered.addAll(canary.expected());
        }
        final EnumSet<Settings> missing = StructureWriter.recognizedSettings();
        missing.removeAll(covered);
        assertTrue(missing.isEmpty(), "no canary exercises " + missing + " - add a row, not a waiver");
    }

    /**
     * Every multiblock inherits {@code machineMode}, so the field alone would put a mode row on all
     * of them. A machine that really has modes answers {@code supportsMachineModeSwitch} for
     * itself, and that is what the probe goes on.
     */
    @Test
    void onlyMachinesThatDeclareModesExposeTheModeSetting() {
        assertTrue(
            StructureWriter.forClass(uninitialised(GT_MULTI + "MTEOreWashingPlant"))
                .reachableSettings()
                .contains(Settings.GT_MODE),
            "MTEOreWashingPlant no longer declares supportsMachineModeSwitch");
        assertFalse(
            StructureWriter.forClass(uninitialised(GT_MULTI + "MTEIndustrialSifter"))
                .reachableSettings()
                .contains(Settings.GT_MODE),
            "the Industrial Sifter has no modes, so it must not offer the row");
    }

    private static Class<?> uninitialised(final String className) {
        try {
            return Class.forName(className, false, GTProbeCanariesTest.class.getClassLoader());
        } catch (final ClassNotFoundException e) {
            throw new AssertionError("GregTech no longer ships " + className, e);
        }
    }
}
