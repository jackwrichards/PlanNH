package com.gtnhplanner.ui.world;

import java.util.UUID;

import javax.annotation.Nullable;

import com.gtnhplanner.api.PlanAPI;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Plan;

/**
 * Plan cards placed in the world ({@link Node#worldLinks}): which card a spot holds, and placing or removing one. A
 * spot
 * is a block's place, often an imaginary block in front of a real one: breaking blocks never moves it. A card is placed
 * on one spot, and a spot holds one card: placing a card on a spot takes it from any other card, in any plan. A link
 * edit is an undo step and is saved, but does not count as a change to the plan:
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
        return assign(graph, node, dim, x, y, z, 0, false);
    }

    /**
     * Places the card on the spot facing {@code facing} (0 south, 1 west, 2 north, 3 east: where a multiblock's
     * controller looks), taking the spot off whichever card had it; with {@code another}, as one more of the card's
     * machines, else in place of where it was. One undo step in each plan it touches. Returns the card it was on
     * before, or null.
     */
    @Nullable
    public static Hit assign(final Graph graph, final Node node, final int dim, final int x, final int y, final int z,
        final int facing, final boolean another) {
        final int[] at = { dim, x, y, z, facing };
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
        final boolean there = node.worldLinks.size() == 1 && indexOf(node, dim, x, y, z) == 0
            && facing(node.worldLinks.get(0)) == facing;
        if (holder != null || !there) PlanAPI.recordEdit(graph, () -> moveWithin(graph, node, at, another));
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
        moveWithin(graph, keep, at, false);
    }

    /** As {@link #moveWithin(Graph, Node, int[])}; with {@code another}, {@code keep} keeps its other spots too. */
    static void moveWithin(final Graph graph, @Nullable final Node keep, final int[] at, final boolean another) {
        for (final Node n : graph.nodes.values())
            n.worldLinks.removeIf(l -> l[0] == at[0] && l[1] == at[1] && l[2] == at[2] && l[3] == at[3]);
        if (keep != null) {
            if (!another) keep.worldLinks.clear();
            keep.worldLinks.add(at.clone());
        }
    }

    /** Where a spot's machine faces: 0 south, 1 west, 2 north, 3 east (south for spots saved before facings). */
    public static int facing(final int[] link) {
        return link.length > 4 ? link[4] & 3 : 0;
    }

    /** The facing that turns a machine's front toward the player: the way they look, turned around. */
    public static int facingToward(final float yaw) {
        return (Math.floorMod(Math.round(yaw / 90f), 4) + 2) & 3;
    }

    /** Unlinks every block of the card. */
    public static void clear(final Graph graph, final Node node) {
        if (node.worldLinks.isEmpty()) return;
        PlanAPI.recordEdit(graph, node.worldLinks::clear);
        PlanAPI.save();
    }

    /**
     * The placed card whose spot a ray from the eye meets first, within range, through anything (the plan is drawn
     * over the world), in {@code only} or every plan; or null. Spots are whole blocks, often empty ones.
     */
    @Nullable
    public static Spot onRay(final net.minecraft.client.Minecraft mc, final double range, @Nullable final Graph only) {
        final net.minecraft.entity.EntityLivingBase eye = mc.renderViewEntity;
        final Plan plan = Plan.loaded();
        if (eye == null || mc.theWorld == null || plan == null) return null;
        final net.minecraft.util.Vec3 from = eye.getPosition(1f), dir = eye.getLook(1f);
        final int dim = mc.theWorld.provider.dimensionId;
        Spot best = null;
        double bestT = range;
        for (final Graph g : plan.getGraphs()) {
            if (only != null && g != only) continue;
            for (final Node n : g.nodes.values()) for (final int[] l : n.worldLinks) {
                if (l[0] != dim) continue;
                final double t = enter(from, dir, extent(g, n, l));
                if (t >= 0 && t < bestT) {
                    bestT = t;
                    best = new Spot(new Hit(g, n), l[1], l[2], l[3], t);
                }
            }
        }
        return best;
    }

    /**
     * What a placed card's spot fills: its whole structure when the card is a multiblock of the plan last open (the
     * plan whose machines are known), else its block. {x0, y0, z0, x1, y1, z1}.
     */
    static double[] extent(final Graph graph, final Node node, final int[] link) {
        final PlanSnapshot snap = PlanSnapshot.latest();
        final PlanSnapshot.Card card = snap == null || snap.graph() != graph ? null : snap.cardOf(node.id);
        return WorldMarks.extent(card == null ? null : card.machine(), link[1], link[2], link[3], facing(link));
    }

    /** A placed card's spot met by a ray: the card, where it is, and how far along the ray. */
    public record Spot(Hit hit, int x, int y, int z, double distance) {}

    /** Where a ray enters a block's cube, as a distance along it, or -1 when it misses. */
    static double enter(final net.minecraft.util.Vec3 o, final net.minecraft.util.Vec3 d, final int x, final int y,
        final int z) {
        return enter(o, d, new double[] { x, y, z, x + 1, y + 1, z + 1 });
    }

    /** Where a ray enters a box {x0, y0, z0, x1, y1, z1}, as a distance along it, or -1 when it misses. */
    static double enter(final net.minecraft.util.Vec3 o, final net.minecraft.util.Vec3 d, final double[] box) {
        double near = 0, far = Double.MAX_VALUE;
        final double[] origin = { o.xCoord, o.yCoord, o.zCoord }, dir = { d.xCoord, d.yCoord, d.zCoord };
        for (int k = 0; k < 3; k++) {
            final double lo = box[k], hi = box[k + 3];
            if (Math.abs(dir[k]) < 1e-9) {
                if (origin[k] < lo || origin[k] > hi) return -1;
                continue;
            }
            double t1 = (lo - origin[k]) / dir[k], t2 = (hi - origin[k]) / dir[k];
            if (t1 > t2) {
                final double t = t1;
                t1 = t2;
                t2 = t;
            }
            near = Math.max(near, t1);
            far = Math.min(far, t2);
            if (near > far) return -1;
        }
        return near;
    }

    /** The spot in front of the face a ray hit: where a placed card goes (an imaginary block there). */
    public static int[] inFront(final net.minecraft.util.MovingObjectPosition hit) {
        int x = hit.blockX, y = hit.blockY, z = hit.blockZ;
        switch (hit.sideHit) {
            case 0 -> y--;
            case 1 -> y++;
            case 2 -> z--;
            case 3 -> z++;
            case 4 -> x--;
            case 5 -> x++;
            default -> {}
        }
        return new int[] { x, y, z };
    }

    /** How many of the card's blocks are in a dimension. */
    static int countIn(final Node node, final int dim) {
        int n = 0;
        for (final int[] l : node.worldLinks) if (l[0] == dim) n++;
        return n;
    }
}
