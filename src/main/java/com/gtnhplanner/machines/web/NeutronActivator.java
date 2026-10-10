package com.gtnhplanner.machines.web;

/**
 * The Neutron Activator's speed and tick rounding (neutron-activator.ts, after MTENeutronActivator's
 * createProcessingLogic at GT5U 8e23867): 0.9f per pipe layer past four, whole ticks rounded up, and under one tick
 * the custom supplier's floored parallels. Pure: no game classes.
 */
public final class NeutronActivator {

    private NeutronActivator() {}

    /**
     * neutronActivatorSpeed: throughput at a pipe height. GTUtility.powInt promotes the Java 0.9f to double, then
     * squares it.
     */
    public static double speed(final double height) {
        double exponent = Math.max(0, trunc(height) - 4);
        double base = (double) 0.9f;
        double duration = 1;
        while (exponent > 0) {
            if (exponent % 2 == 1) duration *= base;
            base *= base;
            exponent = Math.floor(exponent / 2);
        }
        return 1 / duration;
    }

    /**
     * quantiseNeutronActivatorDuration: whole ticks rounded up; under one tick, floor(1 / duration) parallels capped
     * at the game's int limit (saturating, never resetting), returned as the per-operation duration 1 / parallels.
     */
    public static double quantiseDuration(final double duration) {
        if (duration >= 1) return Math.ceil(duration);
        final double parallels = Math.min(2_147_483_647, Math.max(1, Math.floor(1 / duration)));
        return 1 / parallels;
    }

    /** JavaScript's Math.trunc. */
    private static double trunc(final double x) {
        return x < 0 ? Math.ceil(x) : Math.floor(x);
    }
}
