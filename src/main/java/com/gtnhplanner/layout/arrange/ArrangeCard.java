package com.gtnhplanner.layout.arrange;

/**
 * A card or drawer to place: its id, where its top-left stands today, its size, and whether it is storage (a drawer).
 * A storage whose every wire meets one other card rides that card as a satellite.
 */
public record ArrangeCard(String id, double x, double y, double width, double height, boolean storage) {}
