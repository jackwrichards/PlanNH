package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.data.flowchart.Drawer;
import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.data.flowchart.UndoHistories;
import com.sbancuz.plannh.data.flowchart.UndoHistory;
import com.sbancuz.plannh.harness.GtnhFlowLoader;

/** Undo history lives with the slot, so it survives the graph swap every undo makes. */
class UndoHistoriesTest {

    @Test
    void historySurvivesUndoAndRedo() {
        final UndoHistories histories = new UndoHistories();
        final Graph start = GtnhFlowLoader.load("light_fuel")
            .graph();
        final UndoHistory history = histories.of(start);

        edit(history, start, new Drawer(Drawer.Kind.PRODUCT, "fluid:a"));
        edit(history, start, new Drawer(Drawer.Kind.PRODUCT, "fluid:b"));
        assertEquals(
            2,
            start.getDrawers()
                .size());

        final Graph once = histories.of(start)
            .undo(start);
        assertEquals(
            1,
            once.getDrawers()
                .size());
        assertSame(history, histories.of(once), "the restored graph is the same slot");
        assertTrue(
            histories.of(once)
                .canUndo(),
            "a second undo still has somewhere to go");

        final Graph twice = histories.of(once)
            .undo(once);
        assertTrue(
            twice.getDrawers()
                .isEmpty());
        assertFalse(
            histories.of(twice)
                .canUndo());
        assertTrue(
            histories.of(twice)
                .canRedo());

        final Graph again = histories.of(twice)
            .redo(twice);
        assertEquals(
            1,
            again.getDrawers()
                .size());
        assertEquals(start.slot(), again.slot());
    }

    @Test
    void slotsHaveTheirOwnHistories() {
        final UndoHistories histories = new UndoHistories();
        final Graph a = new Graph("a");
        final Graph b = new Graph("b");
        edit(histories.of(a), a, new Drawer());

        assertNotSame(histories.of(a), histories.of(b));
        assertFalse(
            histories.of(b)
                .canUndo());

        histories.retainOnly(List.of(b));
        assertFalse(
            histories.of(a)
                .canUndo(),
            "a closed slot's history goes with it");
    }

    private static void edit(final UndoHistory history, final Graph graph, final Drawer drawer) {
        final String before = history.beginEdit(graph);
        graph.addDrawer(drawer);
        history.commitEdit(before, graph);
    }
}
