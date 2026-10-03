package com.sbancuz.plannh.data.provider.gregtech;

import java.util.Map;

import javax.annotation.Nonnull;

import gregtech.api.logic.ModifierKind;

/**
 * The structure a player built around a machine, as far as the overclock math cares: the energy hatch tier and the
 * amps it delivers, the machine mode, and a value for each {@link ModifierKind} the machine's spec reads. Values use
 * GregTech's numbering per kind.
 *
 * <p>
 * A kind with no value here reads as the spec's best for it, so a state only has to name what the player chose or the
 * chart decided.
 *
 * @param structure What the player chose
 * @param floors    What the chart plans at for a kind the player left alone, raised where the recipe needs more
 */
public record StructureState(int voltageTier, long amperage, int mode, Map<ModifierKind, Long> structure,
    Map<ModifierKind, Long> floors) {

    public StructureState {
        structure = Map.copyOf(structure);
        floors = Map.copyOf(floors);
    }

    public StructureState(final int voltageTier, final long amperage, final int mode,
        final Map<ModifierKind, Long> structure) {
        this(voltageTier, amperage, mode, structure, Map.of());
    }

    @Nonnull
    public static StructureState of(final int voltageTier, final int mode) {
        return new StructureState(voltageTier, 1, mode, Map.of());
    }
}
