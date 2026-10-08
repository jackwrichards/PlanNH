package com.gtnhplanner.ui.card;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.power.PowerModel;
import com.gtnhplanner.power.PowerSetting;
import com.gtnhplanner.power.PowerSource;
import com.gtnhplanner.power.sources.Helpers;

/**
 * A power card's tiles, the website's PowerConfigPanel: every setting on a tile (a select is a ladder, a number a
 * count, a toggle On or Off) but a select tier, which is the head's tier chip as on every machine; then the model's
 * readings (efficiency, flows, lifespans) as plain facts.
 */
public final class PowerTiles {

    /** A setting's tile, or a reading's. */
    public record Tile(@Nullable PowerSetting setting, @Nullable PowerModel.Stat stat) {

        public boolean isSetting() {
            return setting != null;
        }
    }

    public static List<Tile> of(final CardModel model) {
        final List<Tile> tiles = new ArrayList<>();
        final PowerSource source = model.power.source();
        if (source != null) {
            for (final PowerSetting setting : source.settings()) {
                if (setting == tierSetting(source)) continue;
                tiles.add(new Tile(setting, null));
            }
        }
        for (final PowerModel.Stat stat : model.power.stats()) tiles.add(new Tile(null, stat));
        return tiles;
    }

    /** The select the head's tier chip steps, when the source has one ("tier", in voltage tiers). */
    @Nullable
    public static PowerSetting.Select tierSetting(@Nullable final PowerSource source) {
        if (source == null) return null;
        return source.setting("tier") instanceof final PowerSetting.Select select
            && CardDefaults.tierIndex(select.defaultKey()) >= 0 ? select : null;
    }

    /** A setting's live value as stored, its default filled in (settingValue). */
    public static String value(final PowerSetting setting, final Map<String, String> values) {
        final String raw = values.get(setting.id());
        return switch (setting) {
            case PowerSetting.Select s -> s.value(raw);
            case PowerSetting.Toggle t -> t.value(raw) ? "1" : "0";
            case PowerSetting.Number n -> raw == null ? Helpers.number(n.defaultValue()) : raw;
        };
    }

    /** What a setting's tile shows: the option's label, the number, or On and Off. */
    public static String shown(final PowerSetting setting, final Map<String, String> values) {
        final String raw = values.get(setting.id());
        return switch (setting) {
            case PowerSetting.Select s -> {
                final PowerSetting.Option option = s.option(s.value(raw));
                yield option == null ? s.value(raw) : option.label();
            }
            case PowerSetting.Toggle t -> t.value(raw) ? "On" : "Off";
            case PowerSetting.Number n -> Helpers.number(n.value(raw));
        };
    }

    /** A setting's caption: its label, with its unit for a number. */
    public static String caption(final PowerSetting setting) {
        return setting instanceof final PowerSetting.Number n && n.unit() != null ? setting.label() + " (" + n.unit()
            + ")" : setting.label();
    }

    /** Whether a setting means anything at the others' values: grayed out when not. */
    public static boolean enabled(final PowerSource source, final PowerSetting setting,
        final Map<String, String> values) {
        final PowerSetting.Condition when = setting.enabledWhen();
        if (when == null) return true;
        final PowerSetting other = source.setting(when.settingId());
        return other != null && when.equals()
            .equals(value(other, values));
    }

    /** The value one step along a select's options or a number's range; the same value at either end. */
    public static String step(final PowerSetting setting, final Map<String, String> values, final int step) {
        final String raw = values.get(setting.id());
        return switch (setting) {
            case PowerSetting.Select s -> {
                final List<PowerSetting.Option> options = s.options();
                int i = 0;
                for (int k = 0; k < options.size(); k++) if (options.get(k)
                    .key()
                    .equals(s.value(raw))) i = k;
                yield options.get(Math.max(0, Math.min(options.size() - 1, i + step)))
                    .key();
            }
            case PowerSetting.Toggle t -> step > 0 ? "1" : "0";
            case PowerSetting.Number n -> plain(Math.max(n.min(), Math.min(n.max(), n.value(raw) + step * n.step())));
        };
    }

    /** A number as stored: no grouping, no trailing zeros, no float noise past ten places. */
    public static String plain(final double value) {
        return java.math.BigDecimal.valueOf(value)
            .setScale(10, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString();
    }

    private PowerTiles() {}
}
