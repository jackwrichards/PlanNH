package com.gtnhplanner.data.flowchart;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.gtnhplanner.api.PlanAPI;

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

    /**
     * Opens a slot: it becomes the active one, with a tab. The one it replaces was open until now, and is stamped so.
     */
    public void setActiveIndex(final int index) {
        final long now = System.currentTimeMillis();
        if (activeIndex >= 0 && activeIndex < graphs.size()) graphs.get(activeIndex)
            .setLastOpen(now);
        activeIndex = index;
        if (index >= 0 && index < graphs.size()) {
            graphs.get(index)
                .setLastOpen(now);
            graphs.get(index)
                .setOpen(true);
        }
    }

    /**
     * Closes a slot's tab; the plan stays. If it was the active one, the most recently open of the others takes over.
     * The last tab stays open.
     */
    public void closeSlot(final int index) {
        if (index < 0 || index >= graphs.size() || openSlots().size() <= 1) return;
        if (index == activeIndex) setActiveIndex(mostRecentOpenExcept(index));
        graphs.get(index)
            .setOpen(false);
    }

    /** Removes a slot for good; the most recently open of the others takes over. The last slot stays. */
    public void removeSlot(final int index) {
        if (graphs.size() <= 1 || index < 0 || index >= graphs.size()) return;
        if (index == activeIndex) {
            int next = mostRecentOpenExcept(index);
            if (next < 0) next = index == 0 ? 1 : 0;
            setActiveIndex(next);
        }
        graphs.remove(index);
        if (activeIndex > index) activeIndex--;
    }

    private int mostRecentOpenExcept(final int index) {
        for (final int i : byRecency()) if (i != index && graphs.get(i)
            .isOpen()) return i;
        return -1;
    }

    /** The slots with a tab, in tab order. */
    public List<Integer> openSlots() {
        final List<Integer> out = new ArrayList<>();
        for (int i = 0; i < graphs.size(); i++) if (graphs.get(i)
            .isOpen()) out.add(i);
        return out;
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

    /**
     * The plans if they are loaded, without loading them: for code that runs in the world (the minimap, world links),
     * which must not be what loads them, since that can happen while NEI is still loading its recipes.
     */
    @Nullable
    public static Plan loaded() {
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
        plan.reread(plan.activeIndex);
        return plan.graphs.get(plan.activeIndex);
    }

    /** A slot that could not be read at load, read again (NEI has its recipes by the time a slot is opened). */
    private void reread(final int index) {
        final Graph g = graphs.get(index);
        if (g.getUnreadable() == null || !g.nodes.isEmpty()) return;
        try {
            final Graph read = Serializer.decode(g.getUnreadable());
            read.setName(g.getName());
            read.setOpen(g.isOpen());
            read.setLastOpen(g.getLastOpen());
            graphs.set(index, read);
            com.gtnhplanner.GtnhPlanner.LOG.info("Slot '{}' read on a second try", g.getName());
        } catch (final RuntimeException e) {
            com.gtnhplanner.GtnhPlanner.LOG
                .error("Slot '{}' still cannot be read; its data stays kept", g.getName(), e);
        }
    }

    private static Plan loadPlan() {
        try {
            File saveFile = PlanAPI.getSaveFile();
            if (!saveFile.isFile()) saveFile = PlanAPI.getLegacySaveFile();
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
