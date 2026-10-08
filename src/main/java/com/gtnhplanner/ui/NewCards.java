package com.gtnhplanner.ui;

import com.gtnhplanner.api.PlanAPI;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.ui.card.CardLayout;
import com.gtnhplanner.ui.drawer.DrawerCard;

/**
 * Where a new card goes, on any plan (it is wired to nothing: the player wires it): the open board's adds and the plan
 * button on NEI's
 * recipe pages (which can add with the planner closed) both place cards this way.
 */
public final class NewCards {

    /** Room between a new card and the card it is wired to. */
    static final int GAP = 80;

    private NewCards() {}

    /** Puts a new card into a plan the board does not have open, placed as the board would, undoable. */
    public static void addTo(final Graph graph, final Node node) {
        PlanAPI.recordEdit(graph, () -> {
            graph.addNode(node);
            place(graph, node);
        });
        PlanAPI.save();
    }

    /**
     * Places a new card next to what it is wired to (a recipe added from a port lookup): left of the first card it
     * feeds, else right of the first card that feeds it, else right of everything on the board. Then moves it down
     * until
     * it overlaps nothing.
     */
    public static void place(final Graph graph, final Node node) {
        Node feeds = null, fedBy = null;
        for (final Edge e : graph.getEdges()) {
            if (feeds == null && e.sourceNodeId.equals(node.id)) feeds = graph.nodes.get(e.targetNodeId);
            if (fedBy == null && e.targetNodeId.equals(node.id)) fedBy = graph.nodes.get(e.sourceNodeId);
        }
        if (feeds != null) {
            node.x = feeds.x - CardLayout.W - GAP;
            node.y = feeds.y;
        } else if (fedBy != null) {
            node.x = fedBy.x + CardLayout.W + GAP;
            node.y = fedBy.y;
        } else {
            int right = Integer.MIN_VALUE, top = Integer.MAX_VALUE;
            for (final Node n : graph.getNodes()) {
                if (n == node) continue;
                right = Math.max(right, n.x + CardLayout.W);
                top = Math.min(top, n.y);
            }
            for (final Drawer d : graph.getDrawers()) {
                right = Math.max(right, d.getX() + DrawerCard.W);
                top = Math.min(top, d.getY());
            }
            node.x = right == Integer.MIN_VALUE ? 40 : right + GAP;
            node.y = top == Integer.MAX_VALUE ? 40 : top;
        }
        for (int tries = 0; tries < 50 && overlapsAnything(graph, node); tries++) node.y += 40;
    }

    /** Rough card height from its port count, for placement before the card has been drawn. */
    private static int estimatedHeight(final Node node) {
        final int rows = Math.max(1, Math.max(node.inputs.size(), node.outputs.size()));
        return CardLayout.RAILS_Y + Math.max(rows * CardLayout.ROW, CardLayout.PICTURE_MIN) + 85;
    }

    static boolean overlapsAnything(final Graph graph, final Node node) {
        return overlapsAnything(graph, node.x, node.y, CardLayout.W, estimatedHeight(node), node);
    }

    /** Whether a box, with a margin, overlaps any card or drawer on the board other than {@code self}. */
    static boolean overlapsAnything(final Graph graph, final int x, final int y, final int w, final int h,
        final Object self) {
        final int m = 16;
        for (final Node n : graph.getNodes()) {
            if (n != self && boxesOverlap(x, y, w, h, n.x, n.y, CardLayout.W, estimatedHeight(n), m)) return true;
        }
        for (final Drawer d : graph.getDrawers()) {
            if (d != self && boxesOverlap(x, y, w, h, d.getX(), d.getY(), DrawerCard.W, DrawerCard.H, m)) return true;
        }
        return false;
    }

    private static boolean boxesOverlap(final int ax, final int ay, final int aw, final int ah, final int bx,
        final int by, final int bw, final int bh, final int margin) {
        return ax < bx + bw + margin && bx < ax + aw + margin && ay < by + bh + margin && by < ay + ah + margin;
    }
}
