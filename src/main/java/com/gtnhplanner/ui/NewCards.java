package com.gtnhplanner.ui;

import java.util.List;
import java.util.UUID;

import com.gtnhplanner.api.PlanAPI;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.ui.card.CardLayout;
import com.gtnhplanner.ui.drawer.DrawerCard;

/**
 * What a new card is wired to and where it goes, on any plan: the open board's adds and the plan button on NEI's
 * recipe pages (which can add with the planner closed) both place cards this way.
 */
public final class NewCards {

    /** Room between a new card and the card it is wired to. */
    static final int GAP = 80;

    private NewCards() {}

    /** Puts a new card into a plan the board does not have open: wired and placed as the board would, undoable. */
    public static void addTo(final Graph graph, final Node node) {
        PlanAPI.recordEdit(graph, () -> {
            graph.addNode(node);
            autoWire(graph, node);
            place(graph, node);
        });
        PlanAPI.save();
    }

    /**
     * Wires a new card to what is already on the board: each input to the first card that makes it (or a source
     * drawer holding it), each output to a card that uses it and has no supply yet (or a product drawer holding it).
     */
    public static void autoWire(final Graph graph, final Node added) {
        for (int in = 0; in < added.inputs.size(); in++) {
            final Port<?> want = added.inputs.get(in);
            boolean done = false;
            for (final Node n : graph.getNodes()) {
                if (n == added || done) continue;
                for (int out = 0; out < n.outputs.size() && !done; out++) {
                    if (!n.outputs.get(out)
                        .canConnect(want)) continue;
                    graph.addEdge(new Edge(UUID.randomUUID(), n.id, added.id, out, in));
                    done = true;
                }
            }
            if (!done) linkToDrawer(graph, added, false, in);
        }
        for (int out = 0; out < added.outputs.size(); out++) {
            boolean done = false;
            for (final Node n : graph.getNodes()) {
                if (n == added || done) continue;
                final int in = graph.findCompatibleInput(added, out, n);
                if (in < 0 || hasSupply(graph, n, in)) continue;
                graph.addEdge(new Edge(UUID.randomUUID(), added.id, n.id, out, in));
                done = true;
            }
            if (!done) linkToDrawer(graph, added, true, out);
        }
    }

    private static boolean hasSupply(final Graph graph, final Node node, final int input) {
        for (final Edge e : graph.getEdges()) {
            if (e.targetNodeId.equals(node.id) && e.targetInputIndex == input) return true;
        }
        return graph.drawerAt(node.id, input, true) != null;
    }

    /** Links a port to a drawer already on the board for the same resource and direction, if there is one. */
    private static void linkToDrawer(final Graph graph, final Node node, final boolean output, final int port) {
        final List<Port<?>> ports = output ? node.outputs : node.inputs;
        final String key = Resources.key(ports.get(port));
        if (key.isEmpty()) return;
        for (final Drawer d : graph.getDrawers()) {
            if (d.getKind()
                .linksInputs() == output || !key.equals(d.getResourceKey())) continue;
            graph.linkDrawer(d.getId(), new Drawer.Link(node.id, port));
            return;
        }
    }

    /**
     * Places a new card next to what it was wired to: left of the first card it feeds, else right of the first card
     * that feeds it, else right of everything on the board. Then moves it down until it overlaps nothing.
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
