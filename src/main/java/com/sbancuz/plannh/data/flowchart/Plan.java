package com.sbancuz.plannh.data.flowchart;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.sbancuz.plannh.api.PlanAPI;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Plan {

    @Nullable
    private static Plan INSTANCE;

    private transient final List<Graph> graphs = new ArrayList<>();
    private int activeIndex = 0;
    private boolean snapToGrid;

    @Getter
    @Setter
    private Summary summary = new Summary();

    /** The slots' undo histories; transient, so a loaded plan starts with none. */
    private final transient UndoHistories histories = new UndoHistories();

    private Plan() {}

    /** Opens a slot: it becomes the active one. The one it replaces was open until now, and is stamped so. */
    public void setActiveIndex(final int index) {
        final long now = System.currentTimeMillis();
        if (activeIndex >= 0 && activeIndex < graphs.size()) graphs.get(activeIndex)
            .setLastOpen(now);
        activeIndex = index;
        if (index >= 0 && index < graphs.size()) graphs.get(index)
            .setLastOpen(now);
    }

    /** The slots' indices, most recently open first: the active one, then by when each was last open. */
    public List<Integer> byRecency() {
        final List<Integer> order = new ArrayList<>();
        for (int i = 0; i < graphs.size(); i++) order.add(i);
        order.sort((a, b) -> {
            if (a.equals(b)) return 0;
            if (a == activeIndex || b == activeIndex) return a == activeIndex ? -1 : 1;
            return Long.compare(
                graphs.get(b)
                    .getLastOpen(),
                graphs.get(a)
                    .getLastOpen());
        });
        return order;
    }

    /** The undo history of the slot {@code graph} is; it survives undo and redo swapping the graph. */
    public UndoHistory history(final Graph graph) {
        return histories.of(graph);
    }

    public static Plan getInstance() {
        if (INSTANCE == null) {
            INSTANCE = loadPlan();
        }
        return INSTANCE;
    }

    public static Graph getActiveGraph() {
        Plan plan = getInstance();
        if (plan.graphs.isEmpty()) {
            plan.graphs.add(new Graph("Slot 1"));
        }
        if (plan.activeIndex < 0 || plan.activeIndex >= plan.graphs.size()) {
            plan.activeIndex = 0;
        }
        return plan.graphs.get(plan.activeIndex);
    }

    private static Plan loadPlan() {
        try {
            final File saveFile = PlanAPI.getSaveFile();
            if (saveFile.isFile()) {
                final String data = Files.readString(saveFile.toPath(), StandardCharsets.UTF_8);
                return Serializer.decodePlan(data);
            }
        } catch (final Exception | Error ignored) {}
        final Plan plan = new Plan();
        plan.getGraphs()
            .add(new Graph("Slot 1"));
        return plan;
    }

    public static void unloadPlan() {
        PlanAPI.save();
        INSTANCE = null;
    }
}
