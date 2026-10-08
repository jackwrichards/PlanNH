package com.gtnhplanner.power;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;

/**
 * One placeable generator family (types.ts PowerSourceDefinition): its settings and a pure compute from settings to
 * flows and EU/t. {@code id} is stored in plans, here and on the website: never rename one that has shipped.
 * {@code unlock} is the voltage tier chip in the picker (a plain label, enforced nowhere), null for none.
 */
public record PowerSource(String id, String name, PowerGroup group, @Nullable String unlock, String blurb,
    List<PowerSetting> settings, Function<SettingsReader, PowerModel> compute) {

    public PowerSource {
        settings = List.copyOf(settings);
    }

    public PowerModel compute(final SettingsReader read) {
        return compute.apply(read);
    }

    /** The model at these stored settings (null or missing values read as defaults). */
    public PowerModel compute(@Nullable final Map<String, String> values) {
        return compute.apply(new SettingsReader(this, values));
    }

    @Nullable
    public PowerSetting setting(final String settingId) {
        for (final PowerSetting setting : settings) {
            if (setting.id()
                .equals(settingId)) return setting;
        }
        return null;
    }
}
