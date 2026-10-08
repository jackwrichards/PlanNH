package com.gtnhplanner.power;

import java.util.List;

/**
 * One machine at its chosen settings (types.ts PowerModel): net EU/t, what it eats and makes per second, the stat
 * lines its card shows, and plain sentences for setting combinations that cannot run (empty when it runs).
 */
public record PowerModel(double euPerTick, List<Flow> inputs, List<Flow> outputs, List<Stat> stats,
    List<String> warnings) {

    public PowerModel {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        stats = List.copyOf(stats);
        warnings = List.copyOf(warnings);
    }

    public PowerModel(final double euPerTick, final List<Flow> inputs, final List<Flow> outputs,
        final List<Stat> stats) {
        this(euPerTick, inputs, outputs, stats, List.of());
    }

    public enum Unit {
        /** Litres of a fluid. */
        L,
        /** Whole items. */
        ITEM
    }

    /**
     * One resource stream of ONE machine. {@code name} is the workbook's display name; {@link PowerResources}
     * resolves it to an item or fluid where the map knows it, and the card shows it as a stat where not.
     */
    public record Flow(String name, double perSecond, Unit unit) {}

    public record Stat(String label, String value) {}

    /** The stat with this label, or null. */
    public String stat(final String label) {
        for (final Stat stat : stats) {
            if (stat.label()
                .equals(label)) return stat.value();
        }
        return null;
    }
}
