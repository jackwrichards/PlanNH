package com.gtnhplanner.data.provider;

import com.gtnhplanner.data.properties.RecipeProperty;
import com.gtnhplanner.data.properties.SummaryProperty;

/**
 * The GregTech recipe properties the board reads, kept apart from {@link GTProvider}: loading that class loads
 * GregTech's, so the UI reads these from here and a game without GregTech (the plain dev run) still draws its cards.
 */
public final class GTKeys {

    private GTKeys() {}

    public static final RecipeProperty<Long> TOTAL_EU = SummaryProperty.<Long>builder("gt.total_eu", 0L)
        .build();
    public static final RecipeProperty<Long> EU_PER_TICK = SummaryProperty.<Long>builder("gt.eu_per_tick", 0L)
        .perSec(true)
        .build();
    public static final RecipeProperty<Integer> COIL_HEAT = RecipeProperty.<Integer>builder("gt.coil_heat", 0)
        .build();
}
