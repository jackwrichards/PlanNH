package com.sbancuz.plannh.data.provider.gregtech;

import java.util.EnumMap;
import java.util.Map;

import javax.annotation.Nonnull;

import net.minecraft.util.MathHelper;

import com.sbancuz.plannh.data.MachineProfile;
import com.sbancuz.plannh.data.RecipeContext;
import com.sbancuz.plannh.data.Settings;

/**
 * A machine's effective structure tiers, fully resolved. Built by {@link #resolve}, which owns every
 * default; preset functions only read.
 */
public final class StructureState {

    private final EnumMap<Settings, Integer> tiers;

    private StructureState(final EnumMap<Settings, Integer> tiers) {
        this.tiers = tiers;
    }

    /** A resolved state. Dense by contract: a missing key fails fast here, not as a silent zero. */
    @Nonnull
    public static StructureState copyOf(final Map<Settings, Integer> tiers) {
        final EnumMap<Settings, Integer> filled = new EnumMap<>(Settings.class);
        filled.putAll(tiers);
        return new StructureState(filled);
    }

    /** An untouched node outside a game: best available, electrode 0, structureTier 2, mode 0. */
    @Nonnull
    public static StructureState untouched(final int voltageTier) {
        final EnumMap<Settings, Integer> tiers = new EnumMap<>(Settings.class);
        tiers.put(Settings.VOLTAGE, voltageTier);
        tiers.put(Settings.GT_COIL, GTSettings.MAX_COIL_TIER);
        tiers.put(Settings.GT_SOLENOID, GTSettings.MAX_SOLENOID_TIER);
        tiers.put(Settings.GT_ITEM_PIPE, GTSettings.MAX_ITEM_PIPE_TIER);
        tiers.put(Settings.GT_PIPE_CASING, GTSettings.MAX_PIPE_CASING_TIER);
        tiers.put(Settings.GT_SAWBLADE, GTSettings.MAX_SAWBLADE_TIER);
        tiers.put(Settings.GT_ELECTRODE, 0);
        tiers.put(Settings.GT_STRUCTURE_TIER, 2);
        tiers.put(Settings.GT_WIDTH, GTSettings.MAX_WIDTH);
        tiers.put(Settings.GT_MODE, 0);
        return new StructureState(tiers);
    }

    /** The effective tier. Never absent: resolve pre-fills every key. */
    public int get(final Settings setting) {
        final Integer tier = tiers.get(setting);
        if (tier == null) throw new IllegalStateException("no tier resolved for " + setting);
        return tier;
    }

    /** The same structure with one setting moved; how the probe tests whether a setting matters. */
    @Nonnull
    public StructureState with(final Settings setting, final int tier) {
        final EnumMap<Settings, Integer> next = new EnumMap<>(tiers);
        next.put(setting, tier);
        return new StructureState(next);
    }

    /**
     * Structure settings open on what the chart says it can build, and on the best the game offers
     * where the chart has said nothing. The row is right there to move one node off that.
     */
    @Nonnull
    public static StructureState resolve(final RecipeContext ctx, final Map<String, Object> settings,
        final int voltageTier) {
        return resolve(ctx, settings, voltageTier, MachineProfile.getInt(settings, Settings.GT_MODE.key(), 0));
    }

    /**
     * As above, with the mode supplied by a caller that already knows the machine. Kept separate so
     * that resolving a structure never reaches the machine index, which a chart does per frame.
     */
    @Nonnull
    public static StructureState resolve(final RecipeContext ctx, final Map<String, Object> settings,
        final int voltageTier, final int mode) {
        final EnumMap<Settings, Integer> tiers = new EnumMap<>(Settings.class);
        tiers.put(Settings.VOLTAGE, voltageTier);
        tiers.put(
            Settings.GT_COIL,
            MathHelper.clamp_int(
                MachineProfile.getInt(settings, Settings.GT_COIL.key(), GTSettings.defaultCoilTier(ctx)),
                0,
                GTSettings.MAX_COIL_TIER));
        tiers.put(Settings.GT_SOLENOID,
            MachineProfile.getInt(settings, Settings.GT_SOLENOID.key(), GTSettings.MAX_SOLENOID_TIER));
        tiers.put(Settings.GT_ITEM_PIPE,
            MachineProfile.getInt(settings, Settings.GT_ITEM_PIPE.key(), GTSettings.MAX_ITEM_PIPE_TIER));
        tiers.put(
            Settings.GT_PIPE_CASING,
            MachineProfile.getInt(
                settings, Settings.GT_PIPE_CASING.key(), GTSettings.defaultPipeCasingTier()));
        tiers.put(
            Settings.GT_SAWBLADE,
            MachineProfile.getInt(settings, Settings.GT_SAWBLADE.key(), GTSettings.MAX_SAWBLADE_TIER));
        tiers.put(Settings.GT_ELECTRODE, MachineProfile.getInt(settings, Settings.GT_ELECTRODE.key(), 0));
        tiers.put(Settings.GT_STRUCTURE_TIER, MachineProfile.getInt(settings, Settings.GT_STRUCTURE_TIER.key(), 2));
        tiers.put(Settings.GT_WIDTH, MachineProfile.getInt(settings, Settings.GT_WIDTH.key(), GTSettings.MAX_WIDTH));
        tiers.put(Settings.GT_MODE, mode);
        return copyOf(tiers);
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof final StructureState other && tiers.equals(other.tiers);
    }

    @Override
    public int hashCode() {
        return tiers.hashCode();
    }
}
