package com.sbancuz.plannh.data.setting;

import java.util.function.BiPredicate;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.value.IntValue;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.sbancuz.plannh.data.MachineConfig;
import com.sbancuz.plannh.data.RecipeContext;

import lombok.Getter;

@Getter
public class IntegerSettingDef extends SettingDef<Integer> {

    private final int min;
    private final int max;

    public IntegerSettingDef(final String key, final int def, final int min, final int max,
        BiPredicate<RecipeContext, MachineConfig> visibility) {
        super(key, def, visibility);
        this.min = min;
        this.max = max;
    }

    public IntegerSettingDef(final String key, final int def, final int min, final int max) {
        this(key, def, min, max, (_, _) -> true);
    }

    private int getMaxWidth() {
        return (int) Math.log10(Math.max(Math.abs(min), Math.abs(max))) * 10;
    }

    @Override
    public IWidget settingsWidget(MachineConfig config) {
        return new TextFieldWidget().width(getMaxWidth())
            .value(new IntValue.Dynamic(() -> config.get(this), val -> config.set(this, val)))
            .numbersInt(min, max)
            .formatAsInteger(true);
    }
}
