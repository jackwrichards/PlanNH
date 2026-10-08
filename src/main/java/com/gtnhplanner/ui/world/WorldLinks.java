package com.gtnhplanner.ui.world;

import java.util.UUID;

import javax.annotation.Nullable;

import com.gtnhplanner.api.PlanAPI;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Plan;

/**
 * Cards' links to blocks in the world ({@link Node#worldLinks}): which card a block belongs to, and linking or
 * unlinking one. A link edit is an undo step and is saved, but does not count as a change to the plan: nothing is
 * solved again and the minimap does not go out of date.
 */
public final class WorldLinks {

    private WorldLinks() {}

    /** A linked block's card: the plan, and the card's node. */
    public record Hit(Graph graph, Node node) {}

    /** The card a block is linked to: in the plan last open in the planner first, then every other plan; or null. */
    @Nullable
    public static Hit find(final int dim, final int x, final int y, final int z) {
        final PlanSnapshot snap = PlanSnapshot.latest();
        final Graph first = snap != null ? snap.graph() : Plan.getActiveGraph();
        final Hit hit = findIn(first, dim, x, y, z);
        if (hit != null) return hit;
        for (final Graph g : Plan.getInstance()
            .getGraphs()) {
            if (g == first) continue;
            final Hit h = findIn(g, dim, x, y, z);
            if (h != null) return h;
        }
        return null;
    }

    @Nullable
    private static Hit findIn(final Graph graph, final int dim, final int x, final int y, final int z) {
        if (graph == null) return null;
        for (final Node n : graph.nodes.values()) if (indexOf(n, dim, x, y, z) >= 0) return new Hit(graph, n);
        return null;
    }

    static int indexOf(final Node node, final int dim, final int x, final int y, final int z) {
        for (int i = 0; i < node.worldLinks.size(); i++) {
            final int[] l = node.worldLinks.get(i);
            if (l[0] == dim && l[1] == x && l[2] == y && l[3] == z) return i;
        }
        return -1;
    }

    /** The node of a plan by id, or null when it is gone. */
    @Nullable
    static Node node(@Nullable final Graph graph, @Nullable final UUID id) {
        return graph == null || id == null ? null : graph.nodes.get(id);
    }

    /** Links the block to the card, or unlinks it when it was; true when it is now linked. */
    public static boolean toggle(final Graph graph, final Node node, final int dim, final int x, final int y,
        final int z) {
        final int at = indexOf(node, dim, x, y, z);
        PlanAPI.recordEdit(graph, () -> {
            if (at >= 0) node.worldLinks.remove(at);
            else node.worldLinks.add(new int[] { dim, x, y, z });
        });
        PlanAPI.save();
        return at < 0;
    }

    /** Unlinks every block of the card. */
    public static void clear(final Graph graph, final Node node) {
        if (node.worldLinks.isEmpty()) return;
        PlanAPI.recordEdit(graph, node.worldLinks::clear);
        PlanAPI.save();
    }

    /** How many of the card's blocks are in a dimension. */
    static int countIn(final Node node, final int dim) {
        int n = 0;
        for (final int[] l : node.worldLinks) if (l[0] == dim) n++;
        return n;
    }
}
