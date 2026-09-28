package com.sbancuz.plannh.data.provider.gregtech;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.sbancuz.plannh.PlanNH;
import com.sbancuz.plannh.data.Settings;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.logic.ProcessingLogic;
import gregtech.api.logic.ProcessingSpec;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.structure.StructureParameter;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.OverclockCalculator;
import gregtech.api.util.tooltip.TooltipTier;

/**
 * The {@link ProcessingSpec} a GregTech multiblock declares, with the structure parameters it reads. Every number is
 * the spec's own, evaluated at the structure a node describes.
 *
 * @param parameters  The registry prototype's parameters, read for their ranges only
 * @param modeMatters Whether switching mode moves a number, which is what earns the machine a mode row
 */
public record GTMachineSpec(ProcessingSpec spec, Map<TooltipTier, StructureParameter> parameters, boolean modeMatters) {

    /** The structure the mode check compares at: the lowest real voltage, every structure parameter at its maximum. */
    private static final StructureState REFERENCE = StructureState.of(1, 0);

    private static final ProcessingSpec.OverclockRule.Ratio STANDARD_OVERCLOCK = new ProcessingSpec.OverclockRule.Ratio(
        2,
        4);

    /** The machine's spec, or null when it declares none or declares one PlanNH cannot evaluate. */
    @Nullable
    public static GTMachineSpec read(@Nonnull final IMetaTileEntity prototype, final int modeCount) {
        if (!(prototype instanceof final MTEMultiBlockBase multi)) return null;
        final ProcessingSpec spec = multi.getProcessingSpec();
        if (spec == null) return null;

        try {
            final Map<TooltipTier, StructureParameter> parameters = new EnumMap<>(TooltipTier.class);
            for (final StructureParameter parameter : multi.getStructureParametersForInspection()) {
                parameters.put(parameter.kind, parameter);
            }
            return of(spec, parameters, modeCount);
        } catch (final RuntimeException e) {
            PlanNH.LOG.warn("PlanNH: {} declares a spec PlanNH cannot evaluate", prototype.getClass().getName(), e);
            return null;
        }
    }

    /**
     * @throws RuntimeException when the spec reads a structure value its machine never declares, so that fails the
     *                          index rather than a chart
     */
    @Nonnull
    public static GTMachineSpec of(@Nonnull final ProcessingSpec spec,
        @Nonnull final Map<TooltipTier, StructureParameter> parameters, final int modeCount) {
        final GTMachineSpec unchecked = new GTMachineSpec(spec, Collections.unmodifiableMap(parameters), false);
        boolean modeMatters = false;
        final String reference = unchecked.numbers(REFERENCE);
        for (int mode = 1; mode < modeCount && !modeMatters; mode++) {
            modeMatters = !reference.equals(unchecked.numbers(REFERENCE.withMode(mode)));
        }
        return new GTMachineSpec(spec, unchecked.parameters, modeMatters);
    }

    /** What the spec reads, for this structure. A structure value the state leaves unset is the range's maximum. */
    @Nonnull
    public ProcessingSpec.Inputs inputs(@Nonnull final StructureState state) {
        final ProcessingSpec.Inputs.Builder inputs = ProcessingSpec.Inputs.builder()
            .voltageTier(state.voltageTier())
            .mode(state.mode());
        parameters.forEach((kind, parameter) -> inputs.tier(kind, state.tier(kind, parameter.max)));
        return inputs.build();
    }

    public int maxParallel(@Nonnull final StructureState state) {
        return spec.getMaxParallel(inputs(state));
    }

    public double durationMultiplier(@Nonnull final StructureState state) {
        return spec.getDurationMultiplier(inputs(state));
    }

    public double euModifier(@Nonnull final StructureState state) {
        return spec.getEuModifier(inputs(state));
    }

    public double euModifierNotLimitingParallel(@Nonnull final StructureState state) {
        return spec.getEuModifierNotLimitingParallel(inputs(state));
    }

    /** 0 for a machine without heat. */
    public int machineHeat(@Nonnull final StructureState state) {
        return spec.getHeat()
            .map(heat -> heat.getMachineHeat(inputs(state)))
            .orElse(0);
    }

    /**
     * The calculator GregTech builds for this recipe at this structure, voltage and amperage, before
     * {@link OverclockCalculator#calculate()}. Numbers the spec gives at the machine's best are taken at their best.
     */
    @Nonnull
    public OverclockCalculator calculator(@Nonnull final GTRecipe recipe, @Nonnull final StructureState state,
        final long voltage, final long amperage) {
        return new ProcessingLogic().setAvailableVoltage(voltage)
            .setAvailableAmperage(amperage)
            .setAmperageOC(true)
            .applySpecForInspection(spec, () -> inputs(state))
            .createOverclockCalculatorForInspection(recipe);
    }

    /** The spec's overclock ratios, or GT's standard 2x speed for 4x EU/t when it leaves overclocks alone. */
    @Nonnull
    public ProcessingSpec.OverclockRule.Ratio overclockRatio() {
        return spec.getOverclock()
            .filter(ProcessingSpec.OverclockRule.Ratio.class::isInstance)
            .map(ProcessingSpec.OverclockRule.Ratio.class::cast)
            .orElse(STANDARD_OVERCLOCK);
    }

    /** The structure parameters this machine reads, each with its range. */
    @Nonnull
    public Map<TooltipTier, GTSettings.TierRange> structure() {
        final Map<TooltipTier, GTSettings.TierRange> structure = new EnumMap<>(TooltipTier.class);
        parameters
            .forEach((kind, parameter) -> structure.put(kind, new GTSettings.TierRange(parameter.min, parameter.max)));
        return structure;
    }

    /** The rows beyond the structure that change a number for this machine. */
    @Nonnull
    public Set<Settings> settings() {
        return modeMatters ? EnumSet.of(Settings.GT_MODE) : EnumSet.noneOf(Settings.class);
    }

    /** Every number the spec gives at this structure, for telling two structures apart. */
    @Nonnull
    private String numbers(final StructureState state) {
        final ProcessingSpec.Inputs inputs = inputs(state);
        return spec.getMaxParallel(inputs) + " "
            + spec.getDurationMultiplier(inputs)
            + " "
            + spec.getEuModifier(inputs)
            + " "
            + spec.getEuModifierNotLimitingParallel(inputs)
            + " "
            + machineHeat(state);
    }
}
