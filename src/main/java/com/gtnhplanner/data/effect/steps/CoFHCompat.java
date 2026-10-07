package com.gtnhplanner.data.effect.steps;

import com.gtnhplanner.data.properties.RecipeProperty;
import com.gtnhplanner.data.properties.SummaryProperty;

public class CoFHCompat {

    public static final RecipeProperty<Integer> RF_COST = SummaryProperty.<Integer>builder("cofh.rf_total", 0)
        .build();

    public static final RecipeProperty<Integer> RF_PER_T = SummaryProperty.<Integer>builder("cofh.rf_per_t", 0)
        .perSec(true)
        .build();
}
