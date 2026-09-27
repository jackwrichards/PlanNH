package com.sbancuz.plannh.gui.node;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.screen.RichTooltip;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.sbancuz.plannh.api.RecipePropertyAPI;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.data.flowchart.Port;
import com.sbancuz.plannh.data.flowchart.balancer.Balancer;
import com.sbancuz.plannh.data.properties.SummaryProperty;
import com.sbancuz.plannh.gui.GuiHelper;
import com.sbancuz.plannh.gui.PlannhColors;
import com.sbancuz.plannh.gui.common.FlowchartFlow;
import com.sbancuz.plannh.gui.common.FlowchartTextWidget;
import com.sbancuz.plannh.gui.common.FlowchartWidget;
import com.sbancuz.plannh.gui.common.IFlowchartDraggable;

/** Machine count, cycle time, and the costs the recipe declares. Hover the row for port rates. */
public class ThroughputInfoWidget extends ParentWidget<ThroughputInfoWidget> implements IFlowchartDraggable {

    private static final String LANG = "plannh.gui.node.throughput.";

    private final NodeWidget parent;
    private final Flow lines;

    public ThroughputInfoWidget(NodeWidget parent) {
        this.parent = parent;

        lines = FlowchartFlow.col(parent)
            .fullWidth()
            .coverChildrenHeight();
        child(lines);

        rebuild();
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        rebuild();
    }

    @Override
    public FlowchartWidget<?, ?> getFlowchartParent() {
        return parent;
    }

    private void rebuild() {
        final Node node = parent.getData();
        final Balancer.NodeBalance nb = balance();
        final int duration = durationTicks(node);

        final IKey count;
        if (nb == null) count = IKey.lang(LANG + "unbalanced");
        else if (nb.operations() <= 0) count = IKey.lang(LANG + "unplanned");
        else count = IKey.lang(LANG + "ops", GuiHelper.formatCount(nb.operations()));

        lines.removeAll();
        lines.child(
            FlowchartFlow.row(parent)
                .mainAxisAlignment(Alignment.MainAxis.SPACE_BETWEEN)
                .tooltipBuilder(this::rates)
                .tooltipAutoUpdate(true)
                .child(new FlowchartTextWidget(count, parent))
                .child(
                    new FlowchartTextWidget(IKey.lang(LANG + "duration", GuiHelper.formatDuration(duration)), parent)));

        for (final IKey property : properties(node)) {
            lines.child(new FlowchartTextWidget(property, parent));
        }

        scheduleResize();
    }

    private Balancer.NodeBalance balance() {
        return parent.getCanvas()
            .getGraph()
            .balance()
            .nodeBalances()
            .get(
                parent.getData()
                    .getId());
    }

    private void rates(final RichTooltip t) {
        final Node node = parent.getData();
        final Balancer.NodeBalance nb = balance();
        if (nb == null || nb.durationPerOp() <= 0) return;

        final float cycleSeconds = nb.durationPerOp() / (float) GuiHelper.TICKS_PER_SECOND;
        for (int i = 0; i < node.getOutputs()
            .size(); i++) {
            addRate(
                t,
                node.getOutputs()
                    .get(i),
                nb.effectiveOutputs()
                    .get(i),
                cycleSeconds,
                true);
        }
        for (int i = 0; i < node.getInputs()
            .size(); i++) {
            addRate(
                t,
                node.getInputs()
                    .get(i),
                nb.effectiveInputs()
                    .get(i),
                cycleSeconds,
                false);
        }
    }

    private static void addRate(final RichTooltip t, final Port<?> port, final Float perCycle, final float cycleSeconds,
        final boolean output) {
        if (perCycle == null || perCycle <= 0) return;
        final String value = port.getType()
            .formatAmount(perCycle / cycleSeconds)
            + StatCollector.translateToLocal(GuiHelper.RateUnit.SECONDS.suffixKey());
        t.addLine(
            IKey.lang(LANG + "rate", value, port.getDisplayName())
                .color(portColor(port, output)));
    }

    private static int portColor(final Port<?> port, final boolean output) {
        if (port.getType() == RecipePropertyAPI.FLUID) {
            return output ? PlannhColors.ACCENT_CYAN.getColor() : PlannhColors.ACCENT_BLUE3.getColor();
        }
        return output ? PlannhColors.ACCENT_YELLOW.getColor() : PlannhColors.TEXT_MUTED.getColor();
    }

    /** Each cost spelled by the property that owns it, rather than assumed to be EU. */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static List<IKey> properties(final Node node) {
        final List<IKey> out = new ArrayList<>();
        for (final var entry : node.getProperties()
            .entrySet()) {
            if (!(entry.getKey() instanceof final SummaryProperty prop)) continue;
            if (!(entry.getValue() instanceof final Number num)) continue;
            final float value = num.floatValue();
            if (value == 0) continue;
            out.add(
                IKey.lang(
                    LANG + "property",
                    prop.formatDisplayName(prop.getDefaultValue()),
                    prop.formatAmount(value)));
        }

        out.sort(Comparator.comparing(IKey::get));
        return out;
    }

    private static int durationTicks(final Node node) {
        final Object raw = node.getProperties()
            .get(RecipePropertyAPI.DURATION_TICKS);
        return raw instanceof final Number n ? n.intValue() : 0;
    }
}
