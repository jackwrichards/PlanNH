package com.gtnhplanner.importer;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.importer.FfPlan.FfEdge;
import com.gtnhplanner.importer.FfPlan.FfStorage;

/**
 * What an FF drawer is and what it asks for, read the way FF reads it (storage-role.ts, storage-target.ts), and what
 * that becomes in GTNH Planner.
 */
public final class FfDrawers {

    /**
     * FF's storage roles. A drawer's role comes from its wires: fed only, it is a drain (its drain mode says product,
     * byproduct or trash); drawn from only, a source; both, a buffer, which GTNH Planner has no drawer for.
     */
    public enum Role {
        SOURCE,
        BUFFER,
        PRODUCT,
        BYPRODUCT,
        TRASH,
        IDLE
    }

    /** A drawer's rule and rate, GTNH Planner's way. */
    public record Target(Drawer.Rule rule, double rate) {

        public static final Target NONE = new Target(Drawer.Rule.ANY, 0);
    }

    private FfDrawers() {}

    /** Every drawer's role, from the plan's wires (FF's getStorageRoles). */
    public static Map<String, Role> roles(final FfPlan plan) {
        final Set<String> fed = new HashSet<>(), drawn = new HashSet<>();
        for (final FfEdge e : plan.edges()) {
            fed.add(e.target());
            drawn.add(e.source());
        }
        final Map<String, Role> roles = new LinkedHashMap<>();
        for (final FfStorage s : plan.storages()) {
            roles.put(s.id(), roleFor(s, fed.contains(s.id()), drawn.contains(s.id()), plan.poolMode()));
        }
        return roles;
    }

    /** FF's storageRoleFor. In pool mode a drawer's side counts, not its wires. */
    public static Role roleFor(final FfStorage s, final boolean fed, final boolean drawn, final boolean poolMode) {
        if (poolMode) {
            final String side = poolSideOf(s, fed, drawn);
            if ("source".equals(side)) return Role.SOURCE;
            return "drain".equals(side) ? drainRole(s) : Role.IDLE;
        }
        if (fed) return drawn ? Role.BUFFER : drainRole(s);
        if (drawn) return Role.SOURCE;
        if (s.targetPerSecond() != null && s.targetPerSecond() < 0) return Role.SOURCE;
        if ("drain".equals(s.poolSide())) return drainRole(s);
        if ("source".equals(s.poolSide())) return Role.SOURCE;
        return Role.IDLE;
    }

    /** FF's poolSideOf: a negative target or a declared side wins, else what the saved wires say. */
    @Nullable
    static String poolSideOf(final FfStorage s, final boolean fed, final boolean drawn) {
        if (s.targetPerSecond() != null && s.targetPerSecond() < 0) return "source";
        if (s.poolSide() != null) return s.poolSide();
        if (fed && !drawn) return "drain";
        if (drawn && !fed) return "source";
        return null;
    }

    private static Role drainRole(final FfStorage s) {
        if ("byproduct".equals(s.drainMode())) return Role.BYPRODUCT;
        if ("trash".equals(s.drainMode())) return Role.TRASH;
        return Role.PRODUCT;
    }

    /** The GTNH Planner drawer for a role; null for a buffer or an unwired drawer, which have none. */
    @Nullable
    public static Drawer.Kind kindOf(final Role role) {
        return switch (role) {
            case SOURCE -> Drawer.Kind.SOURCE;
            case PRODUCT -> Drawer.Kind.PRODUCT;
            case BYPRODUCT -> Drawer.Kind.BYPRODUCT;
            case TRASH -> Drawer.Kind.TRASH;
            case BUFFER, IDLE -> null;
        };
    }

    /** FF's storageTargetMode: "at-least", "at-most", "exact" or "ignore". */
    public static String targetMode(final FfStorage s, final Role role) {
        if (s.targetMode() != null) return s.targetMode();
        if ("ignore".equals(s.poolTargetMode())) return "ignore";
        if (isInputRate(s, role)) return "exact";
        return s.poolTargetMode() != null ? s.poolTargetMode() : "at-least";
    }

    private static boolean isInputRate(final FfStorage s, final Role role) {
        if (s.targetPerSecond() != null && s.targetPerSecond() < 0) return true;
        if ("drain".equals(s.poolSide()) && s.targetPerSecond() != null) return false;
        return role == Role.SOURCE;
    }

    /**
     * The rule a drawer carries over. FF enforces drawer rates only in Solve and Pool plans, and only on sources and
     * products (FF's hasStorageTarget); a negative rate is FF's way of writing an input, so the size is what counts.
     */
    public static Target target(final FfStorage s, final Role role, final boolean solveRules) {
        if (!solveRules || (role != Role.SOURCE && role != Role.PRODUCT)) return Target.NONE;
        final Double rate = s.targetPerSecond();
        final String mode = targetMode(s, role);
        if ("ignore".equals(mode) || rate == null || !Double.isFinite(rate)) return Target.NONE;
        if (rate == 0 && !"exact".equals(mode) && !"at-most".equals(mode)) return Target.NONE;
        final Drawer.Rule rule = switch (mode) {
            case "at-most" -> Drawer.Rule.AT_MOST;
            case "exact" -> Drawer.Rule.EXACTLY;
            default -> Drawer.Rule.AT_LEAST;
        };
        return new Target(rule, Math.abs(rate));
    }
}
