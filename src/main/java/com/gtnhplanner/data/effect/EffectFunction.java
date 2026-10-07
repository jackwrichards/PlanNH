package com.gtnhplanner.data.effect;

import java.util.Map;

import com.gtnhplanner.data.RecipeContext;

@FunctionalInterface
public interface EffectFunction<T> {

    T apply(EffectResult current, Map<String, Object> settings, RecipeContext ctx);

}
