package com.sbancuz.plannh.data.setting;

import java.util.function.BiPredicate;

import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.sbancuz.plannh.data.MachineConfig;
import com.sbancuz.plannh.data.RecipeContext;
import com.sbancuz.plannh.gui.common.TooltipStyle;

import lombok.Getter;

@Getter
public abstract class SettingDef<T> {

    protected final String key;
    protected final String label;
    protected final T defaultValue;
    protected BiPredicate<RecipeContext, MachineConfig> visibility;

    protected SettingDef(final String key, final T defaultValue, BiPredicate<RecipeContext, MachineConfig> visibility) {
        this.key = key;
        this.label = StatCollector.translateToLocal("plannh.settings." + key);
        this.defaultValue = defaultValue;
        this.visibility = visibility;

        Settings.register(this);
    }

    /** This setting's line on a node tooltip, or null when the value it holds has nothing to say. */
    public String tooltip(final T value) {
        return TooltipStyle.entry(getLabel(), valueColour(), String.valueOf(value));
    }

    protected EnumChatFormatting valueColour() {
        return TooltipStyle.IDENTITY;
    }

    public boolean isVisible(final RecipeContext ctx, final MachineConfig config) {
        return visibility.test(ctx, config);
    }

    public SettingDef<T> withVisibility(final BiPredicate<RecipeContext, MachineConfig> visibility) {
        this.visibility = visibility;
        return this;
    }

    public abstract IWidget settingsWidget(MachineConfig config);

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof SettingDef<?>setting)) return false;
        return key.equals(setting.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }
}
