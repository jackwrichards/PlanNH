package com.gtnhplanner.power;

/**
 * EU as a resource: what a generator's output port holds. Per craft, and a generator's craft is one second, so the
 * amount is EU per second (twenty times EU/t), as every flow is; only the display turns it into EU/t. Nothing
 * consumes it through a port (machines draw power through their EU/t), so its wires only ever end on drawers.
 */
public final class Energy {

    /** The drawer and overview key of EU. */
    public static final String KEY = "power:eu";
    public static final String NAME = "EU";
    /** The website's power amber (its POWER button's text-amber-400). */
    public static final int AMBER = 0xFBBF24;

    public double perCraft;

    public Energy(final double perCraft) {
        this.perCraft = perCraft;
    }
}
