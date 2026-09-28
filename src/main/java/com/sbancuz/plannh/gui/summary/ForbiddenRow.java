package com.sbancuz.plannh.gui.summary;

import javax.annotation.Nonnull;

import com.cleanroommc.modularui.api.GuiAxis;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.screen.RichTooltip;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.sbancuz.plannh.api.PlanAPI;
import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.data.flowchart.Plan;
import com.sbancuz.plannh.data.flowchart.Summary;
import com.sbancuz.plannh.gui.PlannhColors;

/** A forbidden gate; clicking it allows the gate again. */
final class ForbiddenRow extends SummaryFlow implements Interactable {

    private final Summary.Line.Forbidden forbidden;

    ForbiddenRow(final Summary.Line.Forbidden forbidden) {
        super(GuiAxis.X);
        this.forbidden = forbidden;

        fullWidth().coverChildrenHeight(SummaryBody.LINE_H)
            .hoverBackground(new Rectangle().color(PlannhColors.SUMMARY_ROW_HOVER.getColor()))
            .child(
                new TextWidget<>(IKey.str("x " + forbidden.displayName())).paddingLeft(SummaryBody.TEXT_X)
                    .color(PlannhColors.ACCENT_RED.getColor())
                    .textAlign(Alignment.CenterLeft)
                    .fullWidth());
        tooltip(new RichTooltip().add(IKey.lang("plannh.summary.forbidden.allow")));
    }

    @Override
    public @Nonnull Result onMousePressed(final int mouseButton) {
        if (mouseButton != 0) return Result.IGNORE;
        final Graph graph = Plan.getActiveGraph();
        PlanAPI.recordEdit(graph, () -> graph.allowGate(forbidden.port()));
        return Result.SUCCESS;
    }
}
