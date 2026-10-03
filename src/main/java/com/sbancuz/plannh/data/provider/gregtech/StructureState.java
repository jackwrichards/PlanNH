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
 */
public record StructureState(int voltageTier, long amperage, int mode, Map<ModifierKind, Long> structure) {

    public StructureState {
        structure = Map.copyOf(structure);
    }

    @Nonnull
    public static StructureState of(final int voltageTier, final int mode) {
        return new StructureState(voltageTier, 1, mode, Map.of());
    }
}
