package com.sbancuz.plannh.data;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.IntFunction;
import java.util.function.ToIntBiFunction;
import java.util.stream.IntStream;

import javax.annotation.Nonnull;

/**
 * An int-stored tier whose row cycles names. Options are gathered from the range, not provided as
 * a key list: storage is the tier, only the row reads as the build step. Unset rows display the
 * automatic value like every other auto int row, so no enum-style default is needed.
 *
 * <p>
 * Only visibility is overridden: display and default are fixed at construction for tier rows, so
 * copies through any other method leave the tier behind by design.
 */
public class TierSetting extends SettingDef<Integer> {

    private final int min;
    private final int max;
    private final ToIntBiFunction<RecipeContext, Map<String, Object>> autoValueFn;
    private final IntFunction<String> names;
    private final BiFunction<Integer, MachineConfig, String> badgeFn;

    public TierSetting(final String key, final int min, final int max,
        final ToIntBiFunction<RecipeContext, Map<String, Object>> autoValueFn,
        final IntFunction<String> names,
        final BiFunction<Integer, MachineConfig, String> badgeFn) {
        this(key, min, max, autoValueFn, names, badgeFn, (ctx, settings) -> true);
    }

    private TierSetting(final String key, final int min, final int max,
        final ToIntBiFunction<RecipeContext, Map<String, Object>> autoValueFn,
        final IntFunction<String> names,
        final BiFunction<Integer, MachineConfig, String> badgeFn,
        final BiPredicate<RecipeContext, Map<String, Object>> visibility) {
        super(
            key,
            Integer.class,
            min,
            min,
            max,
            ctx -> IntStream.rangeClosed(min, max)
                .mapToObj(String::valueOf)
                .toList(),
            null,
            tier -> names.apply(Integer.parseInt(tier)),
            badgeFn,
            visibility,
            autoValueFn,
            null,
            null);
        this.min = min;
        this.max = max;
        this.autoValueFn = autoValueFn;
        this.names = names;
        this.badgeFn = badgeFn;
    }

    /** Names the tier without going through storage; what minimums and tables show. */
    @Nonnull
    public String name(final int tier) {
        return names.apply(tier);
    }

    /** Copies keep their tier-ness: the profile gates every row through this. */
    @Override
    @Nonnull
    public TierSetting withVisibility(final BiPredicate<RecipeContext, Map<String, Object>> condition) {
        return new TierSetting(key, min, max, autoValueFn, names, badgeFn, condition);
    }
}
