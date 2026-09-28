package com.sbancuz.plannh.data.setting;

import java.util.function.BiPredicate;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.value.BoolValue;
import com.cleanroommc.modularui.widgets.ToggleButton;
import com.sbancuz.plannh.data.MachineConfig;
import com.sbancuz.plannh.data.RecipeContext;

public class BooleanSettingDef extends SettingDef<Boolean> {

    protected BooleanSettingDef(String key, Boolean defaultValue,
        BiPredicate<RecipeContext, MachineConfig> visibility) {
        super(key, defaultValue, visibility);
    }

    public BooleanSettingDef(String key, Boolean defaultValue) {
        this(key, defaultValue, (_, _) -> true);
    }

    @Override
    public IWidget settingsWidget(MachineConfig config) {
        return new ToggleButton().value(new BoolValue.Dynamic(() -> config.get(this), val -> config.set(this, val)))
            .overlay(false, IKey.str("[ ]"))
            .overlay(true, IKey.str("[✓]"));
    }
}
