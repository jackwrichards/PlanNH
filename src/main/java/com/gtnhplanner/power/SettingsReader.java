package com.gtnhplanner.power;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * A power card's stored settings, typed and defaulted (types.ts buildPowerSettingsReader). Reading an id the source
 * does not declare, or as the wrong kind, gives "", 0 or false, as on the website.
 */
public final class SettingsReader {

    private final Map<String, PowerSetting> byId = new HashMap<>();
    private final Map<String, String> values;

    public SettingsReader(final PowerSource source, @Nullable final Map<String, String> values) {
        for (final PowerSetting setting : source.settings()) byId.put(setting.id(), setting);
        this.values = values == null ? Map.of() : values;
    }

    public String select(final String id) {
        return byId.get(id) instanceof final PowerSetting.Select select ? select.value(values.get(id)) : "";
    }

    public double number(final String id) {
        return byId.get(id) instanceof final PowerSetting.Number number ? number.value(values.get(id)) : 0;
    }

    public boolean on(final String id) {
        return byId.get(id) instanceof final PowerSetting.Toggle toggle && toggle.value(values.get(id));
    }
}
