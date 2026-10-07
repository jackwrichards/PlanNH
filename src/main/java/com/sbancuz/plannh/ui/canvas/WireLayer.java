package com.sbancuz.plannh.ui.canvas;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.sbancuz.plannh.data.flowchart.Drawer;
import com.sbancuz.plannh.data.flowchart.Edge;
import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.data.flowchart.Port;
import com.sbancuz.plannh.data.flowchart.balancer.BalanceResult;
import com.sbancuz.plannh.data.flowchart.balancer.Balancer;
import com.sbancuz.plannh.layout.ArrowRouter;
import com.sbancuz.plannh.ui.BoardSession;
import com.sbancuz.plannh.ui.Resources;
import com.sbancuz.plannh.ui.card.CardLayout;
import com.sbancuz.plannh.ui.card.RecipeCard;
import com.sbancuz.plannh.ui.drawer.DrawerCard;
import com.sbancuz.plannh.ui.theme.Hyb;

/**
 * The board's wires, Factory Flow style: one per resource between two objects, routed on a grid with right angles
 * around cards and drawers, drawn in the resource's colour, thicker with more flow, with arrowheads along the run.
 * Everything is in world space; the canvas draws this under the cards with its pan and zoom applied.
 */
final class WireLayer {

    enum Kind {
        /** Card output to card input. */
        EDGE,
        /** A drawer and the port it holds. */
        LINK
    }

    record Wire(UUID key, Kind kind, @Nullable Edge edge, @Nullable Drawer drawer, @Nullable Drawer.Link link,
        int color, float width, double perSecond, List<int[]> path, String resource) {}

    /** Ten-unit cells: parallel wires sit a cell apart, and routing a busy board stays quick. */
    private static final ArrowRouter ROUTER = new ArrowRouter(10, 12);

    private final BoardSession session;
    private List<Wire> wires = List.of();
    private long builtSignature = Long.MIN_VALUE;

    WireLayer(final BoardSession session) {
        this.session = session;
    }

    /**
     * The wires, routed again when anything they depend on moved. {@code moving}: a card or drawer is being dragged, so
     * route quickly (no rip-up pass) on each step, and thoroughly once it lands.
     */
    List<Wire> wires(final Map<UUID, RecipeCard> cards, final Map<UUID, DrawerCard> drawers, final boolean moving) {
        final long signature = signature(cards) * 31 + (moving ? 1 : 0);
        if (signature != builtSignature) {
            builtSignature = signature;
            final long started = System.nanoTime();
            wires = build(cards, drawers, !moving);
            final long ms = (System.nanoTime() - started) / 1_000_000;
            if (ms > 8) com.sbancuz.plannh.PlanNH.LOG.info("[wires] routed {} wires in {} ms", wires.size(), ms);
        }
        return wires;
    }

    /**
     * Moves with the plan, the latest answer, and where every card and drawer is: a card being dragged has not changed
     * the plan yet, but its wires should follow it.
     */
    private long signature(final Map<UUID, RecipeCard> cards) {
        final Graph graph = session.graph();
        long sig = graph.version() * 31 + System.identityHashCode(session.result());
        for (final RecipeCard card : cards.values()) {
            if (card.layout() != null) sig = sig * 31 + card.layout().height;
        }
        for (final Node n : graph.getNodes()) sig = (sig * 31 + n.x) * 31 + n.y;
        for (final Drawer d : graph.getDrawers()) sig = (sig * 31 + d.getX()) * 31 + d.getY();
        return sig;
    }

    private List<Wire> build(final Map<UUID, RecipeCard> cards, final Map<UUID, DrawerCard> drawers,
        final boolean thorough) {
        final Graph graph = session.graph();
        final List<ArrowRouter.Rect> obstacles = new ArrayList<>();
        for (final RecipeCard card : cards.values()) {
            if (card.model() == null || card.layout() == null) continue;
            final Node n = card.model().node;
            obstacles.add(new ArrowRouter.Rect(n.x, n.y, CardLayout.W, card.layout().height));
        }
        for (final Drawer d : graph.getDrawers()) {
            obstacles.add(new ArrowRouter.Rect(d.getX(), d.getY(), DrawerCard.W, DrawerCard.H));
        }

        final List<ArrowRouter.Request> requests = new ArrayList<>();
        final List<Wire> pending = new ArrayList<>();
        final BalanceResult result = session.result();
        for (final Edge e : graph.getEdges()) {
            final Node src = graph.nodes.get(e.sourceNodeId), dst = graph.nodes.get(e.targetNodeId);
            if (src == null || dst == null
                || e.sourceOutputIndex >= src.outputs.size()
                || e.targetInputIndex >= dst.inputs.size()) continue;
            final Port<?> port = src.outputs.get(e.sourceOutputIndex);
            final double flow = edgeFlow(result, e);
            requests.add(
                new ArrowRouter.Request(
                    e.id,
                    src.x + CardLayout.W,
                    src.y + anchorY(cards, src, true, e.sourceOutputIndex),
                    dst.x,
                    dst.y + anchorY(cards, dst, false, e.targetInputIndex)));
            pending.add(new Wire(e.id, Kind.EDGE, e, null, null, colorOf(port), 0, flow, null, Resources.key(port)));
        }
        for (final Drawer d : graph.getDrawers()) {
            final boolean source = d.getKind()
                .linksInputs();
            for (final Drawer.Link link : d.getLinks()) {
                final Node n = graph.nodes.get(link.nodeId());
                if (n == null) continue;
                final List<Port<?>> ports = source ? n.inputs : n.outputs;
                if (link.portIndex() >= ports.size()) continue;
                final Port<?> port = ports.get(link.portIndex());
                final UUID key = UUID.nameUUIDFromBytes(
                    (d.getId() + ":" + link.nodeId() + ":" + link.portIndex()).getBytes(StandardCharsets.UTF_8));
                final int portY = n.y + anchorY(cards, n, !source, link.portIndex());
                if (source) {
                    requests.add(
                        new ArrowRouter.Request(
                            key,
                            d.getX() + DrawerCard.W,
                            d.getY() + DrawerCard.ANCHOR_Y,
                            n.x,
                            portY));
                } else {
                    requests.add(
                        new ArrowRouter.Request(
                            key,
                            n.x + CardLayout.W,
                            portY,
                            d.getX(),
                            d.getY() + DrawerCard.ANCHOR_Y));
                }
                final double flow = linkFlow(result, n, source, link.portIndex());
                pending.add(new Wire(key, Kind.LINK, null, d, link, colorOf(port), 0, flow, null, Resources.key(port)));
            }
        }

        // Weighted by width: the router routes the busiest wires first, so they get the cleanest lines.
        final List<Wire> sized = withWidths(pending);
        final List<ArrowRouter.Request> weighted = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            final ArrowRouter.Request r = requests.get(i);
            weighted.add(
                new ArrowRouter.Request(
                    r.key(),
                    r.sx(),
                    r.sy(),
                    r.dx(),
                    r.dy(),
                    sized.get(i)
                        .width()));
        }
        final Map<UUID, List<int[]>> routes = ROUTER.route(obstacles, List.of(), weighted, null, thorough);
        final List<Wire> built = new ArrayList<>(pending.size());
        for (int i = 0; i < pending.size(); i++) {
            final Wire w = pending.get(i);
            List<int[]> path = routes.get(w.key());
            if (path == null || path.size() < 2) path = elbow(requests.get(i));
            built.add(
                new Wire(
                    w.key(),
                    w.kind(),
                    w.edge(),
                    w.drawer(),
                    w.link(),
                    w.color(),
                    w.width(),
                    w.perSecond(),
                    path,
                    w.resource()));
        }
        return withWidths(built);
    }

    /** A port's anchor on its card, from the card's own layout (rows grow when a name takes two lines). */
    private static int anchorY(final Map<UUID, RecipeCard> cards, final Node n, final boolean output, final int port) {
        final RecipeCard card = cards.get(n.id);
        return card == null || card.layout() == null ? CardLayout.RAILS_Y + 10
            : card.layout()
                .anchorY(output, port);
    }

    private static List<int[]> elbow(final ArrowRouter.Request r) {
        final int mid = (r.sx() + r.dx()) / 2;
        return List.of(
            new int[] { r.sx(), r.sy() },
            new int[] { mid, r.sy() },
            new int[] { mid, r.dy() },
            new int[] { r.dx(), r.dy() });
    }

    private static double edgeFlow(@Nullable final BalanceResult result, final Edge e) {
        if (result == null) return 0;
        if (result instanceof final BalanceResult.Solved solved && solved.auto() != null) {
            final Double f = solved.auto().edgeFlowsPerSecond.get(e.id);
            if (f != null) return f;
        }
        final Balancer.NodeBalance b = result.nodeBalances()
            .get(e.sourceNodeId);
        return b == null ? 0 : b.outputPerSecond(e.sourceOutputIndex);
    }

    private static double linkFlow(@Nullable final BalanceResult result, final Node n, final boolean input,
        final int port) {
        if (result == null) return 0;
        final Balancer.NodeBalance b = result.nodeBalances()
            .get(n.id);
        if (b == null) return 0;
        return input ? b.inputPerSecond(port) : b.outputPerSecond(port);
    }

    /**
     * The resource's colour made to read on the dark board, as Factory Flow does: more saturated and a little
     * brighter; near-black resources (carbon) come out a mid grey.
     */
    static int colorOf(final Port<?> port) {
        final int c = port.getArrowColor();
        final float[] hsb = java.awt.Color.RGBtoHSB(c >> 16 & 0xFF, c >> 8 & 0xFF, c & 0xFF, null);
        final float s = Math.min(1, hsb[1] * 1.3f + 0.05f);
        final float b = Math.min(1, Math.max(0.55f, hsb[2] * 0.85f + 0.2f));
        return 0xFF000000 | java.awt.Color.HSBtoRGB(hsb[0], s, b) & 0xFFFFFF;
    }

    /**
     * Wire widths by how much each carries against the others of its kind (items and fluids ranged apart), as
     * Factory Flow does: heat = 0.62 x rank + 0.38 x log share, then 2 px (least) to 7 px (most).
     */
    private static List<Wire> withWidths(final List<Wire> wires) {
        final java.util.Map<Wire, Float> width = new java.util.IdentityHashMap<>();
        for (final boolean fluids : new boolean[] { false, true }) {
            final List<Wire> kind = new ArrayList<>();
            for (final Wire w : wires) if (w.perSecond() > 0 && Resources.isFluid(w.resource()) == fluids) kind.add(w);
            kind.sort(java.util.Comparator.comparingDouble(Wire::perSecond));
            final double max = kind.isEmpty() ? 0
                : kind.get(kind.size() - 1)
                    .perSecond();
            for (int i = 0; i < kind.size(); i++) {
                final double rank = kind.size() == 1 ? 1 : (double) i / (kind.size() - 1);
                final double share = Math.log1p(
                    kind.get(i)
                        .perSecond())
                    / Math.log1p(max);
                width.put(kind.get(i), (float) (2 + 5 * (0.62 * rank + 0.38 * share)));
            }
        }
        final List<Wire> out = new ArrayList<>(wires.size());
        for (final Wire w : wires) out.add(
            new Wire(
                w.key(),
                w.kind(),
                w.edge(),
                w.drawer(),
                w.link(),
                w.color(),
                width.getOrDefault(w, 1.5f),
                w.perSecond(),
                w.path(),
                w.resource()));
        return out;
    }

    // region Drawing

    /** Draws every wire; those carrying {@code glow} (the resource under the mouse) get a gold halo first. */
    void draw(final List<Wire> wires, @Nullable final String glow) {
        if (glow != null && !glow.isEmpty()) {
            // A slow breath (1.6 s) on the halo, as Factory Flow's glow does.
            final double phase = (System.currentTimeMillis() % 1600) / 1600.0 * 2 * Math.PI;
            final int alpha = (int) (0x70 + 0x38 * Math.sin(phase));
            final int halo = alpha << 24 | 0xFFD257;
            for (final Wire w : wires) {
                if (!glow.equals(w.resource())) continue;
                final List<int[]> path = w.path();
                for (int i = 1; i < path.size(); i++) segment(path.get(i - 1), path.get(i), w.width() + 7, halo);
            }
        }
        // The shadow the wire layer casts (Factory Flow: 4 right, 5 down, soft, 35%), under every wire.
        for (final Wire w : wires) {
            if (w.perSecond() <= 0) continue;
            final List<int[]> path = w.path();
            final float width = w.width() + Math.max(2, 0.22f * w.width()) + 2;
            for (int i = 1; i < path.size(); i++) {
                final int[] a = path.get(i - 1), b = path.get(i);
                segment(a[0] + 4, a[1] + 5, b[0] + 4, b[1] + 5, width, 0x3C000000);
            }
        }
        // Thickest first, so thinner wires lie on top, as in Factory Flow.
        final List<Wire> order = new ArrayList<>(wires);
        order.sort((a, b) -> Float.compare(b.width(), a.width()));
        for (int k = 0; k < order.size(); k++) {
            final Wire w = order.get(k);
            drawWire(w.path(), w.color(), w.width(), w.perSecond() > 0);
            if (w.perSecond() > 0) bridges(w, order.subList(0, k));
        }
    }

    /**
     * Where a wire crosses one drawn before it (a thicker one), it bridges over: a short stretch of its own core over a
     * dark halo, so the crossing reads as one wire passing over the other rather than a junction.
     */
    private static void bridges(final Wire top, final List<Wire> under) {
        final List<int[]> p = top.path();
        for (int i = 1; i < p.size(); i++) {
            final int[] a = p.get(i - 1), b = p.get(i);
            for (final Wire o : under) {
                final List<int[]> q = o.path();
                for (int j = 1; j < q.size(); j++) {
                    final int[] c = q.get(j - 1), d = q.get(j);
                    final float[] at = crossing(a, b, c, d);
                    if (at == null) continue;
                    // Across the other wire and a little beyond, longer the more slanted the crossing.
                    final float reach = (o.width() / 2 + 4) / at[2];
                    final float run = dist(a, b), ux = (b[0] - a[0]) / run, uy = (b[1] - a[1]) / run;
                    final float x0 = at[0] - ux * reach, y0 = at[1] - uy * reach;
                    final float x1 = at[0] + ux * reach, y1 = at[1] + uy * reach;
                    segment(x0, y0, x1, y1, top.width() + 5, 0xE0101114);
                    segment(x0, y0, x1, y1, top.width(), top.color());
                }
            }
        }
    }

    /**
     * Where two runs cross inside both (not at or near an end, so wires meeting at a port do not bridge): {x, y, sine
     * of
     * the angle between them}, or null when they are parallel or miss.
     */
    private static float[] crossing(final int[] a, final int[] b, final int[] c, final int[] d) {
        final float rx = b[0] - a[0], ry = b[1] - a[1], sx = d[0] - c[0], sy = d[1] - c[1];
        final float denom = rx * sy - ry * sx;
        if (Math.abs(denom) < 1e-3f) return null;
        final float t = ((c[0] - a[0]) * sy - (c[1] - a[1]) * sx) / denom;
        final float u = ((c[0] - a[0]) * ry - (c[1] - a[1]) * rx) / denom;
        final float lr = (float) Math.hypot(rx, ry), ls = (float) Math.hypot(sx, sy);
        // At least 2 units in from either end of both runs.
        if (t * lr <= 2 || (1 - t) * lr <= 2 || u * ls <= 2 || (1 - u) * ls <= 2) return null;
        return new float[] { a[0] + rx * t, a[1] + ry * t, Math.abs(denom) / (lr * ls) };
    }

    /**
     * One wire, Factory Flow style: a dark casing, the core in the resource's colour, and filled arrowheads along its
     * straight runs. A wire that carries nothing is dotted.
     */
    static void drawWire(final List<int[]> path, final int color, final float width, final boolean flowing) {
        if (path.size() < 2) return;
        if (!flowing) {
            dotted(path, color, width);
            return;
        }
        final float casing = width + Math.max(2, 0.22f * width);
        for (int i = 1; i < path.size(); i++) segment(path.get(i - 1), path.get(i), casing, CASING);
        for (int i = 1; i < path.size(); i++) segment(path.get(i - 1), path.get(i), width, color);
        arrows(path, color, width);
    }

    private static final int CASING = 0xB8111827;
    private static final int ARROW_EVERY = 160;

    /** Dots along the run, for a wire nothing flows through yet. */
    private static void dotted(final List<int[]> path, final int color, final float width) {
        final float size = Math.max(2, width);
        final float step = Math.max(8, 2.5f * width);
        final float total = length(path);
        for (float d = 0; d <= total; d += step) {
            final float[] p = pointAt(path, d);
            Hyb.rect(p[0] - size / 2 - 0.5f, p[1] - size / 2 - 0.5f, size + 1, size + 1, CASING);
            Hyb.rect(p[0] - size / 2, p[1] - size / 2, size, size, color);
        }
    }

    private static float length(final List<int[]> path) {
        float total = 0;
        for (int i = 1; i < path.size(); i++) total += dist(path.get(i - 1), path.get(i));
        return total;
    }

    private static float dist(final int[] a, final int[] b) {
        return (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
    }

    /** The point {@code d} along the path, and the unit direction of the run it lies on. */
    private static float[] pointAt(final List<int[]> path, final float d) {
        float at = 0;
        for (int i = 1; i < path.size(); i++) {
            final int[] a = path.get(i - 1), b = path.get(i);
            final float len = dist(a, b);
            if (at + len >= d || i == path.size() - 1) {
                final float t = len == 0 ? 0 : Math.min(1, (d - at) / len);
                final float ux = len == 0 ? 1 : (b[0] - a[0]) / len, uy = len == 0 ? 0 : (b[1] - a[1]) / len;
                return new float[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, ux, uy, at, at + len };
            }
            at += len;
        }
        final int[] last = path.get(path.size() - 1);
        return new float[] { last[0], last[1], 1, 0, 0, 0 };
    }

    /**
     * Arrowheads centred on the straight runs, clear of the ports and the corners: one on each run with room for it,
     * one
     * every {@value #ARROW_EVERY} on a long run, and at least one per wire, on its longest run.
     */
    private static void arrows(final List<int[]> path, final int color, final float width) {
        final float len = Math.max(7, Math.min(12, 2f * width + 3));
        final float half = Math.max(3.5f, Math.min(6, width * 0.9f + 1.5f));
        final float clear = len + 8;
        final int fill = brighter(color, 0.3f), outline = darker(color);
        int longest = 0;
        float longestLen = 0;
        boolean any = false;
        for (int i = 1; i < path.size(); i++) {
            final int[] a = path.get(i - 1), b = path.get(i);
            final float run = dist(a, b);
            if (run > longestLen) {
                longestLen = run;
                longest = i;
            }
            if (run < len + 2 * clear) continue;
            final int count = Math.max(1, Math.round(run / ARROW_EVERY));
            for (int k = 0; k < count; k++) arrowOn(a, b, run * (k + 0.5f) / count + len / 2, len, half, fill, outline);
            any = true;
        }
        if (!any && longestLen >= len + 4)
            arrowOn(path.get(longest - 1), path.get(longest), longestLen / 2 + len / 2, len, half, fill, outline);
    }

    /** An arrowhead on the run from a to b, its tip {@code tip} along it. */
    private static void arrowOn(final int[] a, final int[] b, final float tip, final float len, final float half,
        final int fill, final int outline) {
        final float run = dist(a, b), dx = (b[0] - a[0]) / run, dy = (b[1] - a[1]) / run;
        final float x = a[0] + dx * tip, y = a[1] + dy * tip;
        head(x, y, dx, dy, len + 1.5f, half + 1.5f, outline);
        head(x - dx, y - dy, dx, dy, len, half, fill);
    }

    /** A filled triangle with its tip at (x, y), pointing along (dx, dy). */
    private static void head(final float x, final float y, final float dx, final float dy, final float len,
        final float half, final int color) {
        final float bx = x - dx * len, by = y - dy * len;
        Hyb.triangle(x, y, bx - dy * half, by + dx * half, bx + dy * half, by - dx * half, color);
    }

    private static int brighter(final int argb, final float amount) {
        final int r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, b = argb & 0xFF;
        return argb & 0xFF000000 | (int) (r + (255 - r) * amount) << 16
            | (int) (g + (255 - g) * amount) << 8
            | (int) (b + (255 - b) * amount);
    }

    private static int darker(final int argb) {
        final int r = (argb >> 16 & 0xFF) * 2 / 5, g = (argb >> 8 & 0xFF) * 2 / 5, b = (argb & 0xFF) * 2 / 5;
        return argb & 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static void segment(final int[] a, final int[] b, final float width, final int color) {
        segment(a[0], a[1], b[0], b[1], width, color);
    }

    /**
     * A run of wire, square-ended half its width past each end so runs meeting at a corner close up. Straight runs are
     * rectangles; a diagonal one is the same rectangle turned, as two triangles.
     */
    private static void segment(final float ax, final float ay, final float bx, final float by, final float width,
        final int color) {
        final float h = width / 2;
        if (ay == by) {
            Hyb.rect(Math.min(ax, bx) - h, ay - h, Math.abs(bx - ax) + width, width, color);
        } else if (ax == bx) {
            Hyb.rect(ax - h, Math.min(ay, by) - h, width, Math.abs(by - ay) + width, color);
        } else {
            final float len = (float) Math.hypot(bx - ax, by - ay), ux = (bx - ax) / len, uy = (by - ay) / len;
            // Along (u) and across (v) the run, half a width each way.
            final float x0 = ax - ux * h, y0 = ay - uy * h, x1 = bx + ux * h, y1 = by + uy * h;
            final float vx = -uy * h, vy = ux * h;
            Hyb.triangle(x0 + vx, y0 + vy, x1 + vx, y1 + vy, x1 - vx, y1 - vy, color);
            Hyb.triangle(x0 + vx, y0 + vy, x1 - vx, y1 - vy, x0 - vx, y0 - vy, color);
        }
    }

    // endregion

    /** The wire within a few world pixels of a point, topmost first. */
    @Nullable
    Wire hit(final float wx, final float wy) {
        for (int i = wires.size() - 1; i >= 0; i--) {
            final Wire w = wires.get(i);
            final float tolerance = Math.max(3, w.width() / 2 + 2);
            final List<int[]> path = w.path();
            for (int k = 1; k < path.size(); k++) {
                final int[] a = path.get(k - 1), b = path.get(k);
                // Distance from the point to the run, diagonal runs included.
                final float vx = b[0] - a[0], vy = b[1] - a[1], len2 = vx * vx + vy * vy;
                final float t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((wx - a[0]) * vx + (wy - a[1]) * vy) / len2));
                if (Math.hypot(wx - (a[0] + vx * t), wy - (a[1] + vy * t)) <= tolerance) return w;
            }
        }
        return null;
    }
}
