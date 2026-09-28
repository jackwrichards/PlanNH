package com.sbancuz.plannh.data.setting;

import java.util.Arrays;
import java.util.function.BiPredicate;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.drawable.text.TextRenderer;
import com.cleanroommc.modularui.value.EnumValue;
import com.cleanroommc.modularui.widgets.CycleButtonWidget;
import com.sbancuz.plannh.data.MachineConfig;
import com.sbancuz.plannh.data.RecipeContext;

import lombok.Getter;

@Getter
public class EnumSettingDef<E extends Enum<E>> extends SettingDef<E> {

    private final Class<E> type;

    public EnumSettingDef(String key, E defaultValue, Class<E> type,
        BiPredicate<RecipeContext, MachineConfig> visibility) {
        super(key, defaultValue, visibility);
        this.type = type;
    }

    public EnumSettingDef(String key, E defaultValue, Class<E> type) {
        this(key, defaultValue, type, (_, _) -> true);
    }

    private int getMaxWidth() {
        return Arrays.stream(type.getEnumConstants())
            .map(Enum::toString)
            .mapToInt(
                s -> TextRenderer.getFontRenderer()
                    .getStringWidth(s))
            .max()
            .orElseThrow() + 10;
    }

    @Override
    public IWidget settingsWidget(MachineConfig config) {
        return new CycleButtonWidget().value(new EnumValue.Dynamic<>(type, () -> {
            E val = config.get(this);
            return val != null ? val : type.getEnumConstants()[0];
        }, val -> config.set(this, val)))
            .width(getMaxWidth()); // this throws if
    }
}
