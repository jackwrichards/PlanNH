package com.sbancuz.plannh.data.provider.gregtech;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.sbancuz.plannh.PlanNH;
import com.sbancuz.plannh.data.Settings;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.logic.ModifierKind;
import gregtech.api.logic.ModifierRange;
import gregtech.api.logic.ProcessingInputs;
import gregtech.api.logic.ProcessingSpec;
import gregtech.api.logic.ResolvedRecipe;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.util.GTRecipe;

/**
 * The {@link ProcessingSpec} a GregTech multiblock declares. Every number is the spec's own, evaluated at the structure
 * a node describes.
 */
public record GTMachineSpec(ProcessingSpec spec) {

    /** Evaluated once when the machine is indexed, so a spec PlanNH cannot evaluate fails there rather than a chart. */
    private static final StructureState REFERENCE = StructureState.of(1, 0);

    /** The machine's spec, or null when it declares none or declares one PlanNH cannot evaluate. */
    @Nullable
    public static GTMachineSpec read(@Nonnull final IMetaTileEntity prototype) {
        if (!(prototype instanceof final MTEMultiBlockBase multi)) return null;
        final ProcessingSpec spec = multi.getProcessingSpec();
        if (spec == null) return null;
        try {
            return of(spec);
        } catch (final RuntimeException e) {
            PlanNH.LOG.warn("PlanNH: {} declares a spec PlanNH cannot evaluate", prototype.getClass().getName(), e);
            return null;
        }
    }

    /** @throws RuntimeException when the spec reads a value it does not declare */
    @Nonnull
    public static GTMachineSpec of(@Nonnull final ProcessingSpec spec) {
        final GTMachineSpec machine = new GTMachineSpec(spec);
        spec.getMaxParallel(machine.inputs(REFERENCE));
        return machine;
    }

    /**
     * What the spec reads, for this structure: one hatch at the node's tier carrying the node's amps, and the spec's
     * best for every value the state leaves unset.
     */
    @Nonnull
    public ProcessingInputs inputs(@Nonnull final StructureState state) {
        final ProcessingInputs.Builder inputs = spec.bestInputs()
            .energyHatch(ProcessingInputs.EnergyHatch.exotic(state.voltageTier(), state.amperage()))
            .mode(state.mode());
        state.structure()
            .forEach((kind, value) -> {
                switch (kind) {
                    case ModifierKind.IntKind tier -> inputs.value(tier, Math.toIntExact(value));
                    case ModifierKind.LongKind amount -> inputs.value(amount, value);
                }
            });
        return inputs.build();
    }

    /** Every number the spec gives this recipe at this structure. */
    @Nonnull
    public ResolvedRecipe resolve(@Nonnull final GTRecipe recipe, @Nonnull final StructureState state) {
        return spec.resolve(recipe, inputs(state));
    }

    /**
     * The values a player builds or inserts, each with its range. Values the machine builds up while running, such as
     * momentum, are planned at their best and offer no row.
     */
    @Nonnull
    public Map<ModifierKind, ModifierRange> structure() {
        final Map<ModifierKind, ModifierRange> structure = new LinkedHashMap<>();
        for (final ModifierRange range : spec.getModifiers()) {
            if (range.kind().source != ModifierKind.Source.RUNTIME) structure.put(range.kind(), range);
        }
        return structure;
    }

    /** The rows beyond the structure that change a number for this machine. */
    @Nonnull
    public Set<Settings> settings() {
        return spec.variesByMode() ? EnumSet.of(Settings.GT_MODE) : EnumSet.noneOf(Settings.class);
    }
}
