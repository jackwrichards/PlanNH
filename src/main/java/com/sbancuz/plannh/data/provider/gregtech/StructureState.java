package com.sbancuz.plannh.data.provider.gregtech;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

import com.sbancuz.plannh.data.Settings;

/**
 * A machine's effective structure tiers, fully resolved. Built by {@code GTSettings.resolve}, which
 * owns every default; preset functions only read.
 */
public final class StructureState {

    /** The settings read as structure, for the profile loop. */
    public static final Set<Settings> STRUCTURE_SETTINGS = Collections.unmodifiableSet(
        EnumSet.of(
            Settings.GT_COIL,
            Settings.GT_SOLENOID,
            Settings.GT_ITEM_PIPE,
            Settings.GT_PIPE_CASING,
            Settings.GT_SAWBLADE,
            Settings.GT_ELECTRODE,
            Settings.GT_STRUCTURE_TIER,
            Settings.GT_WIDTH,
            Settings.GT_MODE));

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
        tiers.put(Settings.GT_COIL, GTStructureTiers.MAX_COIL_TIER);
        tiers.put(Settings.GT_SOLENOID, GTStructureTiers.MAX_SOLENOID_TIER);
        tiers.put(Settings.GT_ITEM_PIPE, GTStructureTiers.MAX_ITEM_PIPE_TIER);
        tiers.put(Settings.GT_PIPE_CASING, GTStructureTiers.MAX_PIPE_CASING_TIER);
        tiers.put(Settings.GT_SAWBLADE, GTStructureTiers.MAX_SAWBLADE_TIER);
        tiers.put(Settings.GT_ELECTRODE, 0);
        tiers.put(Settings.GT_STRUCTURE_TIER, 2);
        tiers.put(Settings.GT_WIDTH, GTStructureTiers.MAX_WIDTH);
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

    @Override
    public boolean equals(final Object o) {
        return o instanceof final StructureState other && tiers.equals(other.tiers);
    }

    @Override
    public int hashCode() {
        return tiers.hashCode();
    }
}
