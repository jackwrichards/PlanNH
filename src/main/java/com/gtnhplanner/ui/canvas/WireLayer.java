package com.gtnhplanner.ui.canvas;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.flowchart.balancer.BalanceResult;
import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.layout.WireHops;
import com.gtnhplanner.layout.WireRouter;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.Resources;
import com.gtnhplanner.ui.card.CardLayout;
import com.gtnhplanner.ui.card.RecipeCard;
import com.gtnhplanner.ui.drawer.DrawerCard;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * The board's wires, Factory Flow style: one per resource between two objects, routed around cards and drawers
 * ({@link WireRouter}), drawn in the resource's colour, thicker with more flow, with arrowheads along the run.
 * Everything is in world space; the canvas draws this under the cards with its pan and zoom applied.
 */
final class WireLayer {

    /** How much thicker than Factory Flow's the board draws its wires (so they read at a glance). */
    private static final float WIDTH = 1.35f;

    enum Kind {
        /** Card output to card input. */
        EDGE,
        /** A drawer and the port it holds. */
        LINK
    }

    record Wire(UUID key, Kind kind, @Nullable Edge edge, @Nullable Drawer drawer, @Nullable Drawer.Link link,
        int color, float width, double perSecond, List<int[]> path, String resource) {}

    private final BoardSession session;
    private List<Wire> wires = List.of();
    private long builtSignature = Long.MIN_VALUE, builtGeometry = Long.MIN_VALUE;

    /**
     * This board's router, used on the router's thread only: it keeps the board between routes, so a move, a new
     * wire or a new card routes again only what it touched.
     */
    private final WireRouter router = new WireRouter();

    WireLayer(final BoardSession session) {
        this.session = session;
    }

    /**
     * The wires, routed again when anything they depend on moved. {@code moving}: a card or drawer is being dragged,
     * so route quickly, and thoroughly once it lands. Routing happens on the router's thread; a route that comes back
     * within the frame is waited for, so a board that routes quickly never shows a wire out of place, and until a
     * slower one comes back a wire whose card or drawer moved stretches to follow it.
     */
    List<Wire> wires(final Map<UUID, RecipeCard> cards, final Map<UUID, DrawerCard> drawers, final boolean moving) {
        final boolean arrived = collect();
        final long geometry = geometry(cards);
        // What a route is for: the geometry, and whether a drag is under way (its quick route is redone thoroughly
        // once it lands, even if nothing moved since the last step).
        final long routeKey = geometry * 31 + (moving ? 1 : 0);
        final long signature = signature();
        if (!arrived && routeKey == builtGeometry && signature == builtSignature) return wires;
        final Plan p = plan(cards, drawers);
        if (routeKey != routedGeometry && job == null) {
            final long routing = routeKey;
            final boolean thorough = !moving;
            job = WORKER.submit(() -> {
                final long started = System.nanoTime();
                final Map<UUID, List<int[]>> routed = router.reroute(p.boxes(), p.keys(), p.requests(), null, thorough);
                return new Routed(
                    routing,
                    routed,
                    endsOf(p),
                    (System.nanoTime() - started) / 1_000_000,
                    router.statRouted);
            });
            if (lastRouteMillis >= 0 ? lastRouteMillis <= WAIT_MILLIS
                : p.requests()
                    .size() <= WAIT_WIRES) {
                try {
                    took(job.get(WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS));
                    job = null;
                } catch (final java.util.concurrent.TimeoutException e) {
                    // It comes back on a later frame.
                } catch (final InterruptedException e) {
                    Thread.currentThread()
                        .interrupt();
                } catch (final java.util.concurrent.ExecutionException e) {
                    com.gtnhplanner.GtnhPlanner.LOG.warn("[wires] routing failed", e);
                    job = null;
                }
            }
        }
        wires = assemble(p);
        builtGeometry = routeKey;
        builtSignature = signature;
        return wires;
    }

    /** A route expected back this soon is waited for on the spot; longer ones are picked up on a later frame. */
    private static final long WAIT_MILLIS = 10;
    /** Before any route has been timed: up to this many wires are waited for. */
    private static final int WAIT_WIRES = 60;

    /** A route the router's thread works on, if any. */
    @Nullable
    private java.util.concurrent.Future<Routed> job;
    /** The latest routes by wire key, where each wire's two ends stood when it was routed, and for which geometry. */
    private Map<UUID, List<int[]>> routes = Map.of();
    private Map<UUID, int[]> routedEnds = Map.of();
    private long routedGeometry = Long.MIN_VALUE, lastRouteMillis = -1;

    /** One route's answer, and how many wires it routed again. */
    private record Routed(long geometry, Map<UUID, List<int[]>> routes, Map<UUID, int[]> ends, long millis,
        int routed) {}

    /** The router's thread: each board's router keeps scratch state, so they all route on this one thread. */
    private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors
        .newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "GTNH Planner wires");
            t.setDaemon(true);
            return t;
        });

    /** Takes a route the router's thread has finished; true when one came back. */
    private boolean collect() {
        if (job == null || !job.isDone()) return false;
        try {
            took(job.get());
        } catch (final InterruptedException | java.util.concurrent.ExecutionException e) {
            com.gtnhplanner.GtnhPlanner.LOG.warn("[wires] routing failed", e);
        }
        job = null;
        return true;
    }

    private void took(final Routed r) {
        routes = r.routes();
        routedEnds = r.ends();
        routedGeometry = r.geometry();
        lastRouteMillis = r.millis();
        if (r.millis() > 8) com.gtnhplanner.GtnhPlanner.LOG.info(
            "[wires] routed {} of {} wires in {} ms",
            r.routed(),
            r.routes()
                .size(),
            r.millis());
    }

    private static Map<UUID, int[]> endsOf(final Plan p) {
        final Map<UUID, int[]> ends = new HashMap<>();
        for (int i = 0; i < p.wires()
            .size(); i++)
            ends.put(
                p.wires()
                    .get(i)
                    .key(),
                p.ends()
                    .get(i));
        return ends;
    }

    /** Where the wires go: every card's and drawer's place and size, and what is wired to what. */
    private long geometry(final Map<UUID, RecipeCard> cards) {
        final Graph graph = session.graph();
        long sig = 17;
        for (final RecipeCard card : cards.values()) {
            if (card.layout() != null) sig = sig * 31 + card.layout().height;
        }
        for (final Node n : graph.getNodes()) sig = ((sig * 31 + n.id.hashCode()) * 31 + n.x) * 31 + n.y;
        for (final Drawer d : graph.getDrawers()) {
            sig = ((sig * 31 + d.getId()
                .hashCode()) * 31 + d.getX()) * 31 + d.getY();
            for (final Drawer.Link l : d.getLinks()) sig = sig * 31 + l.hashCode();
        }
        for (final Edge e : graph.getEdges())
            sig = ((sig * 31 + e.id.hashCode()) * 31 + e.sourceOutputIndex) * 31 + e.targetInputIndex;
        return sig;
    }

    /**
     * Moves with the plan, the latest answer, and where every card and drawer is: a card being dragged has not changed
     * the plan yet, but its wires should follow it.
     */
    private long signature() {
        return session.graph()
            .version() * 31 + System.identityHashCode(session.result());
    }

    /**
     * What a route works from, taken on the client thread: the boxes (every card once, then every drawer) and their
     * keys, one router wire per wire weighted by its width, the wires themselves (no path yet), and the two boxes at
     * each wire's ends (x, y, w, h each). Plain data, so the router's thread can route it.
     */
    private record Plan(List<WireRouter.Box> boxes, List<UUID> keys, List<WireRouter.Wire> requests, List<Wire> wires,
        List<int[]> ends) {}

    private Plan plan(final Map<UUID, RecipeCard> cards, final Map<UUID, DrawerCard> drawers) {
        final Graph graph = session.graph();
        final List<WireRouter.Box> boxes = new ArrayList<>();
        final List<UUID> keys = new ArrayList<>();
        final Map<RecipeCard, Integer> cardBox = new java.util.IdentityHashMap<>();
        // Once per card: a shared machine's recipes all find the same one.
        for (final RecipeCard card : new java.util.LinkedHashSet<>(cards.values())) {
            final Node n = graph.nodes.get(card.nodeId);
            if (n == null) continue;
            cardBox.put(card, boxes.size());
            keys.add(card.nodeId);
            boxes.add(new WireRouter.Box(n.x, n.y, CardLayout.W, card.layout() == null ? 100 : card.layout().height));
        }
        final Map<UUID, Integer> drawerBox = new HashMap<>();
        for (final Drawer d : graph.getDrawers()) {
            drawerBox.put(d.getId(), boxes.size());
            keys.add(d.getId());
            boxes.add(new WireRouter.Box(d.getX(), d.getY(), DrawerCard.W, DrawerCard.H));
        }

        // Each wire's two boxes, source first, and whether it loops.
        final List<int[]> between = new ArrayList<>();
        final List<Wire> pending = new ArrayList<>();
        final BalanceResult result = session.result();
        for (final Edge e : graph.getEdges()) {
            final Node src = graph.nodes.get(e.sourceNodeId), dst = graph.nodes.get(e.targetNodeId);
            if (src == null || dst == null
                || e.sourceOutputIndex >= src.outputs.size()
                || e.targetInputIndex >= dst.inputs.size()) continue;
            final Integer a = cardBox.get(cards.get(src.id)), b = cardBox.get(cards.get(dst.id));
            if (a == null || b == null) continue;
            final Port<?> port = src.outputs.get(e.sourceOutputIndex);
            between.add(new int[] { a, b });
            pending.add(
                new Wire(
                    e.id,
                    Kind.EDGE,
                    e,
                    null,
                    null,
                    colorOf(port),
                    0,
                    edgeFlow(result, e),
                    null,
                    Resources.key(port)));
        }
        for (final Drawer d : graph.getDrawers()) {
            final boolean source = d.getKind()
                .linksInputs();
            final int drawer = drawerBox.get(d.getId());
            for (final Drawer.Link link : d.getLinks()) {
                final Node n = graph.nodes.get(link.nodeId());
                if (n == null) continue;
                final List<Port<?>> ports = source ? n.inputs : n.outputs;
                if (link.portIndex() >= ports.size()) continue;
                final Integer card = cardBox.get(cards.get(n.id));
                if (card == null) continue;
                final Port<?> port = ports.get(link.portIndex());
                final UUID key = UUID.nameUUIDFromBytes(
                    (d.getId() + ":" + link.nodeId() + ":" + link.portIndex()).getBytes(StandardCharsets.UTF_8));
                between.add(source ? new int[] { drawer, card } : new int[] { card, drawer });
                pending.add(
                    new Wire(
                        key,
                        Kind.LINK,
                        null,
                        d,
                        link,
                        colorOf(port),
                        0,
                        linkFlow(result, n, source, link.portIndex()),
                        null,
                        Resources.key(port)));
            }
        }

        // Weighted by width: the router routes the busiest wires first, so they get the cleanest lines. Anywhere on
        // either box's edge, as on the website; a wire from a card back to itself (two recipes on one shared machine)
        // leaves its right side and comes round into its left, so it reads as a loop.
        final List<Wire> sized = withWidths(pending);
        final List<WireRouter.Wire> requests = new ArrayList<>(sized.size());
        final List<int[]> ends = new ArrayList<>(sized.size());
        for (int i = 0; i < sized.size(); i++) {
            final int a = between.get(i)[0], b = between.get(i)[1];
            final double weight = sized.get(i)
                .width();
            requests.add(
                a == b ? new WireRouter.Wire(
                    sized.get(i)
                        .key(),
                    a,
                    b,
                    WireRouter.RIGHT,
                    WireRouter.LEFT,
                    weight)
                    : new WireRouter.Wire(
                        sized.get(i)
                            .key(),
                        a,
                        b,
                        weight));
            final WireRouter.Box from = boxes.get(a), to = boxes.get(b);
            ends.add(new int[] { from.x(), from.y(), from.w(), from.h(), to.x(), to.y(), to.w(), to.h() });
        }
        return new Plan(List.copyOf(boxes), List.copyOf(keys), List.copyOf(requests), sized, ends);
    }

    /** The plan's wires along the latest routes. */
    private List<Wire> assemble(final Plan p) {
        final List<Wire> built = new ArrayList<>(
            p.wires()
                .size());
        for (int i = 0; i < p.wires()
            .size(); i++) {
            final Wire w = p.wires()
                .get(i);
            final int[] now = p.ends()
                .get(i), then = routedEnds.get(w.key());
            List<int[]> path = routes.get(w.key());
            // A wire whose card or drawer moved since it was routed follows it for now; its route is on the way.
            if (path == null || path.size() < 2 || then == null) path = elbow(now);
            else if (!java.util.Arrays.equals(then, now)) path = stretched(path, then, now);
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

    /**
     * A route whose boxes moved since, made to follow them until its new route comes: moved whole when both moved
     * alike, else each end that moved carried along with the corner next to it, the run between stretching.
     */
    private static List<int[]> stretched(final List<int[]> path, final int[] then, final int[] now) {
        final int sdx = now[0] - then[0], sdy = now[1] - then[1], tdx = now[4] - then[4], tdy = now[5] - then[5];
        final List<int[]> out = new ArrayList<>(path.size());
        for (final int[] p : path) out.add(new int[] { p[0], p[1] });
        final int last = out.size() - 1;
        if (sdx == tdx && sdy == tdy) {
            for (final int[] p : out) {
                p[0] += sdx;
                p[1] += sdy;
            }
            return out;
        }
        for (int k = 0; k <= Math.min(1, last - 1); k++) {
            out.get(k)[0] += sdx;
            out.get(k)[1] += sdy;
        }
        for (int k = Math.max(last - 1, 1); k <= last; k++) {
            out.get(k)[0] += tdx;
            out.get(k)[1] += tdy;
        }
        return out;
    }

    /** A wire not routed yet: square out of its source's side facing the target, across, and into the target. */
    private static List<int[]> elbow(final int[] ends) {
        final int sx = ends[0], sy = ends[1], sw = ends[2], sh = ends[3];
        final int tx = ends[4], ty = ends[5], tw = ends[6], th = ends[7];
        final boolean right = tx + tw / 2 >= sx + sw / 2;
        final int ax = right ? sx + sw : sx, ay = sy + sh / 2, bx = right ? tx : tx + tw, by = ty + th / 2;
        final int mid = (ax + bx) / 2;
        return List.of(new int[] { ax, ay }, new int[] { mid, ay }, new int[] { mid, by }, new int[] { bx, by });
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
                width.put(kind.get(i), (float) (WIDTH * (2 + 5 * (0.62 * rank + 0.38 * share))));
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
                width.getOrDefault(w, WIDTH * 1.5f),
                w.perSecond(),
                w.path(),
                w.resource()));
        return out;
    }

    // region Drawing

    /** Draws every wire; those {@code lit} (what the mouse is on, or wired to it) get a faint highlight halo first. */
    void draw(final List<Wire> wires, final com.gtnhplanner.ui.HoverScope lit) {
        // Thickest first, so thinner wires lie on top and do the hopping, as in Factory Flow: a thin line survives
        // being drawn over a fat pipe, and a small bump on it reads at once where a fat pipe rearing up is a blob.
        // The sort is stable, so equal widths keep the route order.
        final List<Wire> order = new ArrayList<>(wires);
        order.sort((a, b) -> Float.compare(b.width(), a.width()));
        final Map<Wire, WireHops.Hopped> hopped = hops(wires, order);
        Hyb.beginBatch();
        if (!lit.isEmpty()) {
            // A slow breath (1.6 s) on the halo, as Factory Flow's glow does.
            final double phase = (System.currentTimeMillis() % 1600) / 1600.0 * 2 * Math.PI;
            final int alpha = (int) (0x48 + 0x20 * Math.sin(phase));
            final int halo = alpha << 24 | Hyb.LIT & 0xFFFFFF;
            for (final Wire w : wires) {
                if (lit.wire(w.edge(), w.drawer(), w.link(), w.resource()))
                    drawHopped(hopped.get(w), w.width() + 5, halo, 0, 0);
            }
        }
        Hyb.endBatch();
        // The rest only changes with the wires: recorded once into a display list and replayed every frame.
        if (wires != listFor || listId == 0) {
            if (listId == 0) listId = org.lwjgl.opengl.GL11.glGenLists(1);
            org.lwjgl.opengl.GL11.glNewList(listId, org.lwjgl.opengl.GL11.GL_COMPILE);
            Hyb.beginBatch();
            drawStill(wires, order, hopped);
            Hyb.endBatch();
            org.lwjgl.opengl.GL11.glEndList();
            listFor = wires;
        }
        com.cleanroommc.modularui.utils.Platform.setupDrawColor();
        org.lwjgl.opengl.GL11.glCallList(listId);
    }

    /** The display list of the wires' shadows, casings, cores and arrows, and the wires it was made for. */
    private int listId;
    private List<Wire> listFor;

    private void drawStill(final List<Wire> wires, final List<Wire> order, final Map<Wire, WireHops.Hopped> hopped) {
        // The shadow the wire layer casts (Factory Flow: 4 right, 5 down, soft, 35%), under every wire.
        for (final Wire w : wires) {
            if (w.perSecond() <= 0) continue;
            drawHopped(hopped.get(w), w.width() + Math.max(2, 0.22f * w.width()) + 2, 0x3C000000, 4, 5);
        }
        for (final Wire w : order) {
            if (w.perSecond() <= 0) {
                dotted(w.path(), w.color(), w.width());
                continue;
            }
            final WireHops.Hopped h = hopped.get(w);
            drawHopped(h, w.width() + Math.max(2, 0.22f * w.width()), CASING, 0, 0);
            drawHopped(h, w.width(), w.color(), 0, 0);
            arrows(w.path(), w.color(), w.width(), h.spans());
        }
    }

    /** Hops worked out for the wire list they were made for: it is only rebuilt when something moves. */
    private List<Wire> hopsFor;
    private Map<Wire, WireHops.Hopped> hopsMade;

    /**
     * Each flowing wire hops the wires drawn behind it (the thicker ones, and equal ones routed before it), so exactly
     * one side of every crossing bumps and it is the side you can see. A wire nothing flows through is dotted and
     * does not hop.
     */
    private Map<Wire, WireHops.Hopped> hops(final List<Wire> wires, final List<Wire> order) {
        if (wires == hopsFor && hopsMade != null) return hopsMade;
        final Map<Wire, WireHops.Hopped> out = new java.util.IdentityHashMap<>();
        for (int k = 0; k < order.size(); k++) {
            final Wire w = order.get(k);
            final List<WireHops.Crossed> crossed = new ArrayList<>();
            if (w.perSecond() > 0) {
                for (int j = 0; j < k; j++) {
                    final Wire o = order.get(j);
                    final List<int[]> q = o.path();
                    for (int i = 1; i < q.size(); i++) {
                        final int[] c = q.get(i - 1), d = q.get(i);
                        crossed.add(new WireHops.Crossed(c[0], c[1], d[0], d[1], o.width()));
                    }
                }
            }
            out.put(w, WireHops.build(w.path(), crossed, w.width()));
        }
        hopsFor = wires;
        hopsMade = out;
        return out;
    }

    /** A hopped wire {@code width} wide, offset by (dx, dy): its straight runs, then its bumps as smooth ribbons. */
    private static void drawHopped(final WireHops.Hopped h, final float width, final int color, final float dx,
        final float dy) {
        for (final List<float[]> run : h.straights()) {
            for (int i = 1; i < run.size(); i++) {
                final float[] a = run.get(i - 1), b = run.get(i);
                segment(a[0] + dx, a[1] + dy, b[0] + dx, b[1] + dy, width, color);
            }
        }
        for (final WireHops.Bump b : h.bumps()) ribbon(b.points(BUMP_STEPS), width, color, dx, dy);
    }

    private static final int BUMP_STEPS = 14;

    /**
     * A curve {@code width} wide through the points: each point pushed out both ways along the curve's normal there,
     * the band between filled with triangles, so the curve has no steps at its joints.
     */
    private static void ribbon(final List<float[]> pts, final float width, final int color, final float dx,
        final float dy) {
        final int n = pts.size();
        final float h = width / 2;
        final float[] lx = new float[n], ly = new float[n], rx = new float[n], ry = new float[n];
        for (int i = 0; i < n; i++) {
            final float[] prev = pts.get(Math.max(0, i - 1)), next = pts.get(Math.min(n - 1, i + 1));
            final float tx = next[0] - prev[0], ty = next[1] - prev[1], len = (float) Math.hypot(tx, ty);
            final float nx = len == 0 ? 0 : -ty / len * h, ny = len == 0 ? 0 : tx / len * h;
            final float[] p = pts.get(i);
            lx[i] = p[0] + dx + nx;
            ly[i] = p[1] + dy + ny;
            rx[i] = p[0] + dx - nx;
            ry[i] = p[1] + dy - ny;
        }
        for (int i = 1; i < n; i++) {
            Hyb.triangle(lx[i - 1], ly[i - 1], lx[i], ly[i], rx[i], ry[i], color);
            Hyb.triangle(lx[i - 1], ly[i - 1], rx[i], ry[i], rx[i - 1], ry[i - 1], color);
        }
    }

    /**
     * One wire, Factory Flow style: a dark casing, the core in the resource's colour, and filled arrowheads along its
     * straight runs. A wire that carries nothing is dotted. (The port-drag preview; board wires go through draw.)
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
        arrows(path, color, width, List.of());
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
    private static void arrows(final List<int[]> path, final int color, final float width, final List<float[]> hops) {
        final float len = Math.max(7, Math.min(12, 2f * width + 3));
        final float half = Math.max(3.5f, Math.min(6, width * 0.9f + 1.5f));
        final float clear = len + 8;
        final int fill = brighter(color, 0.3f), outline = darker(color);
        int longest = 0;
        float longestLen = 0, longestAt = 0, at = 0;
        boolean any = false;
        for (int i = 1; i < path.size(); i++, at += dist(path.get(i - 2), path.get(i - 1))) {
            final int[] a = path.get(i - 1), b = path.get(i);
            final float run = dist(a, b);
            if (run > longestLen) {
                longestLen = run;
                longest = i;
                longestAt = at;
            }
            if (run < len + 2 * clear) continue;
            final int count = Math.max(1, Math.round(run / ARROW_EVERY));
            for (int k = 0; k < count; k++) {
                final float tip = run * (k + 0.5f) / count + len / 2;
                if (offHops(at + tip, len, hops)) {
                    arrowOn(a, b, tip, len, half, fill, outline);
                    any = true;
                }
            }
        }
        if (any || longestLen < len + 4) return;
        // At least one, on the longest run: its middle, or a quarter in from either end when a hop is there.
        for (final float share : new float[] { 0.5f, 0.25f, 0.75f }) {
            final float tip = longestLen * share + len / 2;
            if (tip > longestLen || !offHops(longestAt + tip, len, hops)) continue;
            arrowOn(path.get(longest - 1), path.get(longest), tip, len, half, fill, outline);
            return;
        }
    }

    /** Whether an arrowhead ending {@code tip} along the wire keeps off every hop, with its own length to spare. */
    private static boolean offHops(final float tip, final float len, final List<float[]> hops) {
        for (final float[] span : hops) {
            if (tip > span[0] - len && tip - len < span[1] + len) return false;
        }
        return true;
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
        segment(ax, ay, bx, by, width, color, true);
    }

    /** A run {@code width} wide; with {@code caps} it reaches half a width past each end, so runs join at corners. */
    private static void segment(final float ax, final float ay, final float bx, final float by, final float width,
        final int color, final boolean caps) {
        final float h = width / 2, c = caps ? h : 0;
        if (ay == by) {
            Hyb.rect(Math.min(ax, bx) - c, ay - h, Math.abs(bx - ax) + 2 * c, width, color);
        } else if (ax == bx) {
            Hyb.rect(ax - h, Math.min(ay, by) - c, width, Math.abs(by - ay) + 2 * c, color);
        } else {
            final float len = (float) Math.hypot(bx - ax, by - ay), ux = (bx - ax) / len, uy = (by - ay) / len;
            // Along (u) and across (v) the run, half a width each way (along only with caps).
            final float x0 = ax - ux * c, y0 = ay - uy * c, x1 = bx + ux * c, y1 = by + uy * c;
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
