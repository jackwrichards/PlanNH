package com.sbancuz.plannh.data.provider.gregtech;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.sbancuz.plannh.PlanNH;
import com.sbancuz.plannh.data.Settings;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.logic.ProcessingSpec;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.structure.StructureParameter;
import gregtech.api.util.tooltip.TooltipTier;

/**
 * Turns the {@link ProcessingSpec} a GregTech multiblock declares into the {@link GTMachinePreset} a chart plans with.
 * GregTech evaluates the spec itself, with the structure a node describes, so every number is the machine's own.
 */
public final class GTSpecReader {

    private GTSpecReader() {}

    /** The structure the mode check compares at: the lowest real voltage, every structure parameter at its maximum. */
    private static final StructureState REFERENCE = StructureState.of(1, 0);

    /**
     * The machine's preset, or null when it declares no spec. Read off the registry prototype: the spec is a constant,
     * and a prototype's structure parameters are only asked for their range.
     */
    @Nullable
    public static GTMachinePreset read(@Nonnull final IMetaTileEntity prototype, final int modeCount) {
        if (!(prototype instanceof final MTEMultiBlockBase multi)) return null;
        final ProcessingSpec spec = multi.getProcessingSpec();
        if (spec == null) return null;

        try {
            final Map<TooltipTier, GTSettings.TierRange> structure = new EnumMap<>(TooltipTier.class);
            for (final StructureParameter parameter : multi.getStructureParametersForInspection()) {
                structure.put(parameter.kind, new GTSettings.TierRange(parameter.min, parameter.max));
            }
            final GTMachinePreset preset = toPreset(spec, structure, modeCount);
            // Evaluated here once so a spec that reads a structure value its machine never declares fails the
            // index rather than a chart.
            preset.maxParallel()
                .applyAsInt(REFERENCE);
            preset.durationModifier()
                .applyAsDouble(REFERENCE);
            preset.euModifier()
                .applyAsDouble(REFERENCE);
            preset.machineHeat()
                .applyAsInt(REFERENCE);
            return preset;
        } catch (final RuntimeException e) {
            PlanNH.LOG.warn("PlanNH: {} declares a spec PlanNH cannot evaluate", prototype.getClass().getName(), e);
            return null;
        }
    }

    /**
     * The spec as a preset over the given structure ranges. A structure value the state leaves unset is the range's
     * maximum, the structure an untouched node shows.
     */
    @Nonnull
    public static GTMachinePreset toPreset(@Nonnull final ProcessingSpec spec,
        @Nonnull final Map<TooltipTier, GTSettings.TierRange> structure, final int modeCount) {
        final GTMachinePreset.Builder preset = GTMachinePreset.builder()
            .parallel(s -> spec.getMaxParallel(inputs(s, structure)))
            .speed(s -> spec.getSpeedBonus(inputs(s, structure)))
            .eu(s -> spec.getEuModifier(inputs(s, structure)))
            .energyCost(s -> spec.getEnergyCost(inputs(s, structure)))
            .overclock(spec.getOverclockTimeReduction(), spec.getOverclockPowerIncrease())
            .machineHeat(s -> spec.getMachineHeat(inputs(s, structure)));
        if (spec.isNoOverclock()) preset.noOverclock();
        if (spec.sets(ProcessingSpec.Quantity.OVERCLOCK)) preset.ownsOverclock();
        final EnumSet<ProcessingSpec.Quantity> bestCase = EnumSet.noneOf(ProcessingSpec.Quantity.class);
        for (final ProcessingSpec.Quantity quantity : ProcessingSpec.Quantity.values()) {
            if (spec.isBestCase(quantity)) bestCase.add(quantity);
        }
        preset.bestCase(bestCase);
        structure.forEach((kind, range) -> preset.structure(kind, range.min(), range.max()));

        if (spec.isHeatOverclock()) preset.heatOC(s -> spec.getMachineHeat(inputs(s, structure)));
        if (spec.isHeatDiscount()) preset.heatDiscount();
        spec.getFixedRecipeHeat()
            .ifPresent(preset::recipeHeat);

        // One is what OverclockCalculator starts at, so a spec that leaves it there has nothing to set.
        if (spec.getMaxTierSkips() == Integer.MAX_VALUE) preset.unlimitedTierSkips();
        else if (spec.getMaxTierSkips() != 1) preset.maxTierSkips(spec.getMaxTierSkips());

        final ProcessingSpec.RecipeOverride override = spec.getRecipeOverride();
        if (override != null) preset.recipeOverride(override.eut(), override.duration());

        if (modeMatters(spec, structure, modeCount)) preset.settings(Settings.GT_MODE);
        return preset.build();
    }

    @Nonnull
    private static ProcessingSpec.Inputs inputs(final StructureState state,
        final Map<TooltipTier, GTSettings.TierRange> structure) {
        return new ProcessingSpec.Inputs(state.voltageTier(), state.mode(), kind -> {
            final GTSettings.TierRange range = structure.get(kind);
            if (range == null) throw new IllegalArgumentException("no structure parameter " + kind);
            return state.tier(kind, range.max());
        });
    }

    /**
     * Whether switching mode moves a number, which is what earns a machine its mode row. GregTech says which machines
     * have modes, not whether a mode changes anything: several switch recipemaps with identical numbers.
     */
    private static boolean modeMatters(final ProcessingSpec spec,
        final Map<TooltipTier, GTSettings.TierRange> structure, final int modeCount) {
        final ProcessingSpec.Inputs first = inputs(REFERENCE, structure);
        for (int mode = 1; mode < modeCount; mode++) {
            final ProcessingSpec.Inputs other = inputs(REFERENCE.withMode(mode), structure);
            if (spec.getMaxParallel(first) != spec.getMaxParallel(other)
                || spec.getSpeedBonus(first) != spec.getSpeedBonus(other)
                || spec.getEuModifier(first) != spec.getEuModifier(other)
                || spec.getMachineHeat(first) != spec.getMachineHeat(other)) return true;
        }
        return false;
    }
}
