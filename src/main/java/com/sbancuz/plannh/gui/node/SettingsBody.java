package com.sbancuz.plannh.gui.node;

import com.cleanroommc.modularui.api.GuiAxis;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.sbancuz.plannh.api.PlanAPI;
import com.sbancuz.plannh.data.MachineConfig;
import com.sbancuz.plannh.data.MachineProfile;
import com.sbancuz.plannh.data.RecipeContext;
import com.sbancuz.plannh.data.setting.SettingDef;
import com.sbancuz.plannh.gui.CanvasWidget;
import com.sbancuz.plannh.gui.common.FlowchartFlow;
import com.sbancuz.plannh.gui.common.FlowchartTextWidget;

class SettingsBody extends FlowchartFlow {

    private final NodeWidget node;
    private boolean dirty = true;
    private MachineProfile builtFor;

    SettingsBody(final NodeWidget node) {
        super(GuiAxis.Y, node);
        this.node = node;
        this.builtFor = profile();

        fullWidth().coverChildrenHeight()
            .childPadding(2)
            .crossAxisAlignment(Alignment.CrossAxis.START)
            .setEnabledIf(
                _ -> node.getData()
                    .isSettingsOpen());
        rebuild();
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (!dirty && builtFor == profile()) return;

        dirty = false;
        builtFor = profile();
        rebuild();
    }

    /** The rows are a function of the profile and the extracted recipe, so a change in either is a rebuild. */
    private MachineProfile profile() {
        return node.getData()
            .getMachineConfig()
            .getProfile();
    }

    private void rebuild() {
        removeAll();
        final MachineConfig config = node.getData()
            .getMachineConfig();
        config.getProfile()
            .visibleSettings(
                new RecipeContext(
                    node.getData()
                        .getProperties()),
                config)
            .forEach(def -> child(row(def)));
        scheduleResize();
    }

    private Flow row(final SettingDef<?> def) {
        return FlowchartFlow.row(node)
            .fullWidth()
            .coverChildrenHeight()
            .mainAxisAlignment(Alignment.MainAxis.SPACE_BETWEEN)
            .child(new FlowchartTextWidget(def.getLabel(), node))
            .child(settingsWidget(def));
    }

    private IWidget settingsWidget(final SettingDef<?> def) {
        return def.settingsWidget(
            node.getData()
                .getMachineConfig(),
            this::applyEdit);
    }

    private void applyEdit(final Runnable change) {
        final CanvasWidget canvas = node.getCanvas();
        PlanAPI.recordEdit(canvas.getGraph(), change);
        canvas.getGraph()
            .bumpVersion();
        PlanAPI.save();
        dirty = true;
    }
}
