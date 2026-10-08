package com.gtnhplanner.ui.world;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import com.gtnhplanner.api.PlanAPI;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Plan;

/**
 * Plan cards placed on blocks in the world ({@link Node#worldLinks}): which card a block holds, and placing or removing
 * one. A card is placed on one block, and a block holds one card: placing a card on a block takes the block from any
 * other card, in any plan. A link edit is an undo step and is saved, but does not count as a change to the plan:
 * nothing
 * is
 * solved again and the minimap does not go out of date.
 */
public final class WorldLinks {

    private WorldLinks() {}

    /** A linked block's card: the plan, and the card's node. */
    public record Hit(Graph graph, Node node) {}

    /** The card a block is linked to: in the plan last open in the planner first, then every other plan; or null. */
    @Nullable
    public static Hit find(final int dim, final int x, final int y, final int z) {
        final Plan plan = Plan.loaded();
        if (plan == null) return null;
        final PlanSnapshot snap = PlanSnapshot.latest();
        final Graph first = snap != null ? snap.graph() : null;
        final Hit hit = findIn(first, dim, x, y, z);
        if (hit != null) return hit;
        for (final Graph g : plan.getGraphs()) {
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

    /** Unlinks the block from the card. */
    public static void unlink(final Graph graph, final Node node, final int dim, final int x, final int y,
        final int z) {
        final int at = indexOf(node, dim, x, y, z);
        if (at < 0) return;
        PlanAPI.recordEdit(graph, () -> node.worldLinks.remove(at));
        PlanAPI.save();
    }

    /**
     * Links the block to the card, taking it off whichever card had it (in any plan) first. One undo step in each plan
     * it touches. Returns the card it was on before, or null.
     */
    @Nullable
    public static Hit assign(final Graph graph, final Node node, final int dim, final int x, final int y, final int z) {
        final int[] at = { dim, x, y, z };
        Hit was = null;
        for (final Graph g : Plan.getInstance()
            .getGraphs()) {
            if (g == graph) continue;
            final Node holder = holder(g, at, null);
            if (holder == null) continue;
            was = new Hit(g, holder);
            PlanAPI.recordEdit(g, () -> moveWithin(g, null, at));
        }
        final Node holder = holder(graph, at, node);
        if (holder != null) was = new Hit(graph, holder);
        final boolean there = node.worldLinks.size() == 1 && indexOf(node, dim, x, y, z) == 0;
        if (holder != null || !there) PlanAPI.recordEdit(graph, () -> moveWithin(graph, node, at));
        PlanAPI.save();
        return was;
    }

    /** The card in a plan other than {@code except} that has the block, or null. */
    @Nullable
    private static Node holder(final Graph graph, final int[] at, @Nullable final Node except) {
        for (final Node n : graph.nodes.values())
            if (n != except && indexOf(n, at[0], at[1], at[2], at[3]) >= 0) return n;
        return null;
    }

    /**
     * Within one plan, takes the block off every card but {@code keep} and places {@code keep} on it alone (when not
     * null). No undo, no save: {@link #assign} wraps it.
     */
    static void moveWithin(final Graph graph, @Nullable final Node keep, final int[] at) {
        for (final Node n : graph.nodes.values()) {
            if (n == keep) continue;
            n.worldLinks.removeIf(l -> l[0] == at[0] && l[1] == at[1] && l[2] == at[2] && l[3] == at[3]);
        }
        if (keep != null) {
            keep.worldLinks.clear();
            keep.worldLinks.add(at.clone());
        }
    }

    /** Unlinks every block of the card. */
    public static void clear(final Graph graph, final Node node) {
        if (node.worldLinks.isEmpty()) return;
        PlanAPI.recordEdit(graph, node.worldLinks::clear);
        PlanAPI.save();
    }

    /**
     * Unlinks every linked block in this dimension that is gone (air now), in every plan: one undo step per plan.
     * Only blocks in chunks the client has are checked: an unloaded chunk reads as air. Returns the cards that lost
     * blocks, by name.
     */
    public static List<String> pruneBroken(final World world) {
        final int dim = world.provider.dimensionId;
        final List<String> names = new ArrayList<>();
        final Plan plan = Plan.loaded();
        if (plan == null) return names;
        for (final Graph g : plan.getGraphs()) {
            final List<Node> hit = new ArrayList<>();
            for (final Node n : g.nodes.values())
                for (final int[] l : n.worldLinks) if (l[0] == dim && gone(world, l)) {
                    hit.add(n);
                    break;
                }
            if (hit.isEmpty()) continue;
            PlanAPI.recordEdit(
                g,
                () -> { for (final Node n : hit) n.worldLinks.removeIf(l -> l[0] == dim && gone(world, l)); });
            for (final Node n : hit) names.add(WorldView.cardName(n));
        }
        if (!names.isEmpty()) PlanAPI.save();
        return names;
    }

    /** Whether a linked block is air in a chunk the client has. */
    private static boolean gone(final World world, final int[] l) {
        if (l[2] < 0 || l[2] > 255) return false;
        final Chunk chunk = world.getChunkFromBlockCoords(l[1], l[3]);
        return chunk != null && !chunk.isEmpty() && world.isAirBlock(l[1], l[2], l[3]);
    }

    /** How many of the card's blocks are in a dimension. */
    static int countIn(final Node node, final int dim) {
        int n = 0;
        for (final int[] l : node.worldLinks) if (l[0] == dim) n++;
        return n;
    }
}
