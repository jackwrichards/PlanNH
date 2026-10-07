package com.gtnhplanner.data.flowchart.balancer;

import java.util.Locale;

import net.minecraft.util.StatCollector;

import com.gtnhplanner.data.flowchart.Drawer;

/**
 * The single authority for what the balancer says. Every note, error, preference name, rank reason
 * and boundary label is one constant here - the {@link Severity} the panel colours it by and the key
 * it is localized under (resolved with {@link StatCollector} by {@link #render}). A concrete
 * utterance is a {@link Note} built with {@link #toNote}: the constant names WHAT was said, the
 * arguments carry the runtime data (machine names, ingredients, rates) it was said about.
 */
public enum SolverMessage {

    // -----------------------------------------------------------------------------------------
    // Failures - why a solve produced no point
    // -----------------------------------------------------------------------------------------
    NO_PIN(Severity.INFO, "gtnhplanner.solver.no_pin"),
    EMPTY_GRAPH(Severity.ERROR, "gtnhplanner.solver.empty_graph"),
    BALANCE_FAILED(Severity.ERROR, "gtnhplanner.solver.balance_failed"),
    VALIDATION_FAILED(Severity.ERROR, "gtnhplanner.solver.validation_failed"),

    // -----------------------------------------------------------------------------------------
    // Wiring diagnostics - smells the solver raises against the chart as drawn
    // -----------------------------------------------------------------------------------------
    WIRING_IMPORT(Severity.INFO, "gtnhplanner.solver.wiring_import"),
    WIRING_UNLINKED(Severity.WARN, "gtnhplanner.solver.wiring_unlinked"),
    OVERSHOOTS_TARGET(Severity.WARN, "gtnhplanner.solver.overshoots_target"),

    // -----------------------------------------------------------------------------------------
    // The alternatives search - the honesty notes a truncated or overridden answer owes the reader
    // -----------------------------------------------------------------------------------------
    SHOWING_CLOSEST(Severity.INFO, "gtnhplanner.solver.showing_closest"),
    STOPPED_EARLY(Severity.INFO, "gtnhplanner.solver.stopped_early"),
    CHOICE_NO_LONGER_FITS(Severity.WARN, "gtnhplanner.solver.choice_no_longer_fits"),
    CHOICE_NEEDS_MORE_GATES(Severity.WARN, "gtnhplanner.solver.choice_needs_more_gates"),

    // -----------------------------------------------------------------------------------------
    // Pipeline diagnostics - the solver explaining a rejection or a conflict
    // -----------------------------------------------------------------------------------------
    PIN_CONFLICT(Severity.ERROR, "gtnhplanner.solver.pins_conflict"),
    /** One pin a conflict had to drop. Args: machine name, the pin kind (a nested PIN_* note). */
    PIN_DROPPED(Severity.INFO, "gtnhplanner.solver.pin_dropped"),
    STAGE_FAILED(Severity.ERROR, "gtnhplanner.solver.stage_failed"),
    MACHINES_CANNOT_RUN(Severity.ERROR, "gtnhplanner.solver.machines_cannot_run"),
    SOLVER_BUDGET(Severity.ERROR, "gtnhplanner.solver.budget"),
    SOLVER_UNSATISFIABLE(Severity.ERROR, "gtnhplanner.solver.unsatisfiable"),
    SOLVER_NO_SOLUTION(Severity.ERROR, "gtnhplanner.solver.no_solution"),
    SOLVER_NOT_CONSERVING(Severity.ERROR, "gtnhplanner.solver.not_conserving"),
    SOLVER_NEGATIVE_EXTENT(Severity.ERROR, "gtnhplanner.solver.negative_extent"),
    SOLVER_NEGATIVE_FLOW(Severity.ERROR, "gtnhplanner.solver.negative_flow"),
    SOLVER_INEXACT_RESIDUAL(Severity.ERROR, "gtnhplanner.solver.inexact_residual"),
    SOLVER_UNDER_SUPPLY(Severity.ERROR, "gtnhplanner.solver.under_supply"),
    GATE_COUNT_NOT_CERTIFIED(Severity.INFO, "gtnhplanner.solver.gate_count_not_certified"),
    /** A worker-thread solve threw; the argument is the exception's own message. */
    SOLVE_CRASHED(Severity.ERROR, "gtnhplanner.solver.solve_crashed"),

    // -----------------------------------------------------------------------------------------
    // Drawers - rules the plan cannot keep, and why
    // -----------------------------------------------------------------------------------------
    /** Args: drawer label, the rule (a nested RULE_* note), the rate asked for, the rate reachable. */
    DRAWER_UNMET(Severity.WARN, "gtnhplanner.solver.drawer_unmet"),
    /** Args: drawer label, what holds it back (a nested LIMIT_* note). One note per limit. */
    DRAWER_LIMITED_BY(Severity.INFO, "gtnhplanner.solver.drawer_limited_by"),
    /** Args: drawer label. The drawer asks for flow but is linked to nothing that carries any. */
    DRAWER_NOT_CONNECTED(Severity.WARN, "gtnhplanner.solver.drawer_not_connected"),
    /** Args: drawer label. OUTPUT / INPUT count unwired links only; this drawer has none. */
    DRAWER_WIRED_IGNORED(Severity.WARN, "gtnhplanner.solver.drawer_wired_ignored"),
    /** No args. Nothing sets the plan's size: the only drawer rules are upper limits. */
    DRAWER_ONLY_UPPER_BOUNDS(Severity.INFO, "gtnhplanner.solver.drawer_only_upper_bounds"),
    /** Args: machine name, the pin kind (a nested PIN_* note). */
    LIMIT_PIN(Severity.INFO, "gtnhplanner.solver.limit_pin"),
    /** Args: drawer label. */
    LIMIT_DRAWER(Severity.INFO, "gtnhplanner.solver.limit_drawer"),
    /** Args: machine group capacity. */
    LIMIT_GROUP(Severity.INFO, "gtnhplanner.solver.limit_group"),
    RULE_ANY(Severity.INFO, "gtnhplanner.solver.rule_any"),
    RULE_AT_LEAST(Severity.INFO, "gtnhplanner.solver.rule_at_least"),
    RULE_EXACTLY(Severity.INFO, "gtnhplanner.solver.rule_exactly"),
    RULE_AT_MOST(Severity.INFO, "gtnhplanner.solver.rule_at_most"),

    // -----------------------------------------------------------------------------------------
    // Names - pin kinds and the preferences that rank answers
    // -----------------------------------------------------------------------------------------
    PIN_EXTENT(Severity.INFO, "gtnhplanner.solver.pin_extent"),
    PIN_TARGET(Severity.INFO, "gtnhplanner.solver.pin_target"),
    PIN_COUNT(Severity.INFO, "gtnhplanner.solver.pin_count"),
    PREF_FEWEST_GATES(Severity.INFO, "gtnhplanner.solver.pref_fewest_gates"),
    PREF_FEWEST_IMPORTS(Severity.INFO, "gtnhplanner.solver.pref_fewest_imports"),
    PREF_LEAST_EXCESS(Severity.INFO, "gtnhplanner.solver.pref_least_excess"),
    PREF_LEAST_FLOW(Severity.INFO, "gtnhplanner.solver.pref_least_flow"),

    // -----------------------------------------------------------------------------------------
    // Rank reasons - why an answer is not the default
    // -----------------------------------------------------------------------------------------
    REASON_EQUALLY_VALID(Severity.INFO, "gtnhplanner.solver.reason_equally_valid"),
    REASON_IMPORTS_INSTEAD(Severity.INFO, "gtnhplanner.solver.reason_imports_instead"),
    REASON_MOVES_MORE(Severity.INFO, "gtnhplanner.solver.reason_moves_more"),
    REASON_MOVES_LESS(Severity.INFO, "gtnhplanner.solver.reason_moves_less"),
    REASON_VOIDS_MORE(Severity.INFO, "gtnhplanner.solver.reason_voids_more"),
    REASON_CROSSES_MORE(Severity.INFO, "gtnhplanner.solver.reason_crosses_more"),
    REASON_IMPORTS_MORE(Severity.INFO, "gtnhplanner.solver.reason_imports_more"),
    REASON_LEAVES_EXCESS(Severity.INFO, "gtnhplanner.solver.reason_leaves_excess"),

    // -----------------------------------------------------------------------------------------
    // Boundary labels - what crosses the chart's edge
    // -----------------------------------------------------------------------------------------
    BOUNDARY_EXCESS(Severity.INFO, "gtnhplanner.solver.boundary_excess"),
    BOUNDARY_ADD(Severity.INFO, "gtnhplanner.solver.boundary_add"),
    BOUNDARY_FLOW(Severity.INFO, "gtnhplanner.solver.boundary_flow"),
    BOUNDARY_NOTHING(Severity.INFO, "gtnhplanner.solver.boundary_nothing");

    private final Severity severity;
    private final String key;

    SolverMessage(final Severity severity, final String key) {
        this.severity = severity;
        this.key = key;
    }

    public Severity severity() {
        return severity;
    }

    /** The {@code gtnhplanner.solver.*} language-file key. */
    public String key() {
        return key;
    }

    /** The name of a drawer rule, as a message so it localizes with the note it sits in. */
    public static SolverMessage ruleName(final Drawer.Rule rule) {
        return switch (rule) {
            case ANY -> RULE_ANY;
            case AT_LEAST -> RULE_AT_LEAST;
            case EXACTLY -> RULE_EXACTLY;
            case AT_MOST -> RULE_AT_MOST;
        };
    }

    /** One utterance of this message: the constant plus the data it was said about. */
    public Note toNote(final Object... args) {
        return new Note(this, args);
    }

    /**
     * The sentence through the language file, with {@code args} spliced into it. A missing
     * translation resolves to the bare key rather than to a crash. GUI only - this needs a live
     * {@link StatCollector}.
     */
    public String render(final Object... args) {
        final String local = StatCollector.translateToLocal(key);
        if (local.equals(key)) {
            return key;
        }
        return String.format(Locale.ROOT, local, args);
    }

    /** The key itself, Minecraft-free: the pipeline, profiler, logs and tests use this identity. */
    public String describe() {
        return key;
    }
}
