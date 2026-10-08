package com.gtnhplanner.power;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * A knob on a power card (types.ts PowerSetting): a select, a number or a toggle. Values are stored on the node as
 * strings under the setting's id, as the website stores them in {@code machineConfigTiers}.
 */
public sealed interface PowerSetting permits PowerSetting.Select,PowerSetting.Number,PowerSetting.Toggle {

    String id();

    String label();

    /** Grayed out unless another setting holds a value; null when always live. */
    @Nullable
    Condition enabledWhen();

    record Option(String key, String label) {}

    /** The setting {@code settingId} must read {@code equals} for this one to mean anything. */
    record Condition(String settingId, String equals) {}

    /** {@code legacyKeys} maps a retired option key to the current key it reads as; shipped plans store old keys. */
    record Select(String id, String label, List<Option> options, String defaultKey, @Nullable Condition enabledWhen,
        Map<String, String> legacyKeys) implements PowerSetting {

        public Select {
            options = List.copyOf(options);
            legacyKeys = Map.copyOf(legacyKeys);
        }

        public Select(final String id, final String label, final List<Option> options, final String defaultKey) {
            this(id, label, options, defaultKey, null, Map.of());
        }

        /** The option a stored value reads as: itself, its legacy mapping, or the default. */
        public String value(@Nullable final String raw) {
            if (raw == null) return defaultKey;
            if (has(raw)) return raw;
            final String mapped = legacyKeys.get(raw);
            return mapped != null && has(mapped) ? mapped : defaultKey;
        }

        public boolean has(final String key) {
            for (final Option option : options) {
                if (option.key()
                    .equals(key)) return true;
            }
            return false;
        }

        @Nullable
        public Option option(final String key) {
            for (final Option option : options) {
                if (option.key()
                    .equals(key)) return option;
            }
            return null;
        }
    }

    record Number(String id, String label, double min, double max, double step, double defaultValue,
        @Nullable String unit, @Nullable Condition enabledWhen) implements PowerSetting {

        public Number(final String id, final String label, final double min, final double max, final double step,
            final double defaultValue) {
            this(id, label, min, max, step, defaultValue, null, null);
        }

        /** A stored value as a number: the default when missing or not a number, else clamped to the range. */
        public double value(@Nullable final String raw) {
            if (raw == null || raw.trim()
                .isEmpty()) return defaultValue;
            final double parsed;
            try {
                parsed = Double.parseDouble(raw.trim());
            } catch (final NumberFormatException e) {
                return defaultValue;
            }
            if (!Double.isFinite(parsed)) return defaultValue;
            return Math.min(max, Math.max(min, parsed));
        }
    }

    record Toggle(String id, String label, boolean defaultOn, @Nullable Condition enabledWhen) implements PowerSetting {

        public Toggle(final String id, final String label, final boolean defaultOn) {
            this(id, label, defaultOn, null);
        }

        public boolean value(@Nullable final String raw) {
            return raw == null ? defaultOn : "1".equals(raw) || "true".equals(raw);
        }
    }
}
