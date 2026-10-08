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
import com.gtnhplanner.layout.ArrowRouter;
import com.gtnhplanner.layout.WireHops;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.Resources;
import com.gtnhplanner.ui.card.CardLayout;
import com.gtnhplanner.ui.card.RecipeCard;
import com.gtnhplanner.ui.drawer.DrawerCard;
import com.gtnhplanner.ui.theme.Hyb;

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
    private long builtSignature = Long.MIN_VALUE, builtGeometry = Long.MIN_VALUE;

    WireLayer(final BoardSession session) {
        this.session = session;
    }

    /**
     * The wires, routed again when anything they depend on moved. {@code moving}: a card or drawer is being dragged, so
     * route quickly (no rip-up pass), and thoroughly once it lands. A board that routes in a few milliseconds routes on
     * the spot; a big one routes on its own thread, and until the route comes back a wire whose card or drawer moved
     * goes straight while the rest keep their routes, so the board never waits for the router.
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
        if (routeKey != routedGeometry) {
            if (lastRouteMillis >= 0 ? lastRouteMillis <= SYNC_MILLIS
                : p.requests()
                    .size() <= SYNC_WIRES) {
                final long started = System.nanoTime();
                final Map<UUID, List<int[]>> routed = ROUTER
                    .route(p.obstacles(), List.of(), p.requests(), null, !moving);
                took(new Routed(routeKey, routed, endsOf(p), (System.nanoTime() - started) / 1_000_000));
            } else if (job == null) {
                final long routing = routeKey;
                final boolean thorough = !moving;
                job = WORKER.submit(() -> {
                    final long started = System.nanoTime();
                    final Map<UUID, List<int[]>> routed = WORKER_ROUTER
                        .route(p.obstacles(), List.of(), p.requests(), null, thorough);
                    return new Routed(routing, routed, endsOf(p), (System.nanoTime() - started) / 1_000_000);
                });
            }
        }
        wires = assemble(p);
        builtGeometry = routeKey;
        builtSignature = signature;
        return wires;
    }

    /** Routes this many milliseconds long or shorter are worked out on the spot; longer ones on the router's thread. */
    private static final long SYNC_MILLIS = 25;
    /** Before any route has been timed: up to this many wires route on the spot. */
    private static final int SYNC_WIRES = 40;

    /** A route the router's thread works on, if any. */
    @Nullable
    private java.util.concurrent.Future<Routed> job;
    /** The latest routes by wire key, where each wire's two ends stood when it was routed, and for which geometry. */
    private Map<UUID, List<int[]>> routes = Map.of();
    private Map<UUID, ArrowRouter.Rect[]> routedEnds = Map.of();
    private long routedGeometry = Long.MIN_VALUE, lastRouteMillis = -1;

    /** One route's answer. */
    private record Routed(long geometry, Map<UUID, List<int[]>> routes, Map<UUID, ArrowRouter.Rect[]> ends,
        long millis) {}

    /** The router's thread and its own router: a router keeps scratch state, so each thread needs its own. */
    private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors
        .newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "GTNH Planner wires");
            t.setDaemon(true);
            return t;
        });
    private static final ArrowRouter WORKER_ROUTER = new ArrowRouter(10, 12);

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
            "[wires] routed {} wires in {} ms",
            r.routes()
                .size(),
            r.millis());
    }

    private static Map<UUID, ArrowRouter.Rect[]> endsOf(final Plan p) {
        final Map<UUID, ArrowRouter.Rect[]> ends = new HashMap<>();
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
     * What a route works from, taken on the client thread: the obstacles, one request per wire weighted by its width,
     * the wires themselves (no path yet), and the boxes at each wire's two ends. Plain data, so another thread can
     * route it.
     */
    private record Plan(List<ArrowRouter.Rect> obstacles, List<ArrowRouter.Request> requests, List<Wire> wires,
        List<ArrowRouter.Rect[]> ends) {}

    private Plan plan(final Map<UUID, RecipeCard> cards, final Map<UUID, DrawerCard> drawers) {
        final Graph graph = session.graph();
        final List<ArrowRouter.Rect> obstacles = new ArrayList<>();
        // Once per card: a shared machine's recipes all find the same one.
        for (final RecipeCard card : new java.util.LinkedHashSet<>(cards.values())) {
            if (card.model() == null || card.layout() == null) continue;
            final Node n = card.model().node;
            obstacles.add(new ArrowRouter.Rect(n.x, n.y, CardLayout.W, card.layout().height));
        }
        for (final Drawer d : graph.getDrawers()) {
            obstacles.add(new ArrowRouter.Rect(d.getX(), d.getY(), DrawerCard.W, DrawerCard.H));
        }

        final List<ArrowRouter.Request> requests = new ArrayList<>();
        final List<Wire> pending = new ArrayList<>();
        final List<ArrowRouter.Rect[]> ends = new ArrayList<>();
        final BalanceResult result = session.result();
        for (final Edge e : graph.getEdges()) {
            final Node src = graph.nodes.get(e.sourceNodeId), dst = graph.nodes.get(e.targetNodeId);
            if (src == null || dst == null
                || e.sourceOutputIndex >= src.outputs.size()
                || e.targetInputIndex >= dst.inputs.size()) continue;
            final Port<?> port = src.outputs.get(e.sourceOutputIndex);
            final double flow = edgeFlow(result, e);
            // Anywhere on either card's edge, as on the website; a wire from a card back to itself (two recipes on one
            // shared machine) leaves its right side and comes round into its left, so it reads as a loop.
            final boolean loop = cards.get(src.id) != null && cards.get(src.id) == cards.get(dst.id);
            requests.add(
                ArrowRouter.Request.docked(
                    e.id,
                    docks(cardRect(cards, src), loop ? ArrowRouter.Side.RIGHT : null),
                    docks(cardRect(cards, dst), loop ? ArrowRouter.Side.LEFT : null),
                    0));
            pending.add(new Wire(e.id, Kind.EDGE, e, null, null, colorOf(port), 0, flow, null, Resources.key(port)));
            ends.add(new ArrowRouter.Rect[] { cardRect(cards, src), cardRect(cards, dst) });
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
                final List<ArrowRouter.Dock> drawerDocks = docks(
                    new ArrowRouter.Rect(d.getX(), d.getY(), DrawerCard.W, DrawerCard.H),
                    null), cardDocks = docks(cardRect(cards, n), null);
                requests.add(
                    source ? ArrowRouter.Request.docked(key, drawerDocks, cardDocks, 0)
                        : ArrowRouter.Request.docked(key, cardDocks, drawerDocks, 0));
                final double flow = linkFlow(result, n, source, link.portIndex());
                pending.add(new Wire(key, Kind.LINK, null, d, link, colorOf(port), 0, flow, null, Resources.key(port)));
                ends.add(
                    new ArrowRouter.Rect[] { new ArrowRouter.Rect(d.getX(), d.getY(), DrawerCard.W, DrawerCard.H),
                        cardRect(cards, n) });
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
                        .width(),
                    r.sources(),
                    r.targets()));
        }
        return new Plan(obstacles, weighted, sized, ends);
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
            List<int[]> path = routes.get(w.key());
            // A wire whose card or drawer moved since it was routed goes straight for now; its route is on the way.
            if (path == null || path.size() < 2
                || !java.util.Arrays.equals(
                    routedEnds.get(w.key()),
                    p.ends()
                        .get(i)))
                path = elbow(
                    p.requests()
                        .get(i));
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

    /** A card's box on the board: a shared machine's recipes all have their card's. */
    private static ArrowRouter.Rect cardRect(final Map<UUID, RecipeCard> cards, final Node n) {
        final RecipeCard card = cards.get(n.id);
        final int h = card == null || card.layout() == null ? 100 : card.layout().height;
        return new ArrowRouter.Rect(n.x, n.y, CardLayout.W, h);
    }

    /** Where a wire may meet a box: anywhere on its edge, or only on {@code side} when given. */
    private static List<ArrowRouter.Dock> docks(final ArrowRouter.Rect box, final ArrowRouter.Side side) {
        final List<ArrowRouter.Dock> all = ArrowRouter.perimeterDocks(box);
        if (side == null) return all;
        final List<ArrowRouter.Dock> out = new ArrayList<>();
        for (final ArrowRouter.Dock d : all) if (d.side() == side) out.add(d);
        return out;
    }

    /** A port's anchor on its card, from the card's own layout (rows grow when a name takes two lines). */
    private static int anchorY(final Map<UUID, RecipeCard> cards, final Node n, final boolean output, final int port) {
        final RecipeCard card = cards.get(n.id);
        return card == null || card.layout() == null ? CardLayout.RAILS_Y + CardLayout.ROW / 2
            : card.anchorY(n.id, output, port);
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
        // Thickest first, so thinner wires lie on top and do the hopping, as in Factory Flow: a thin line survives
        // being drawn over a fat pipe, and a small bump on it reads at once where a fat pipe rearing up is a blob.
        // The sort is stable, so equal widths keep the route order.
        final List<Wire> order = new ArrayList<>(wires);
        order.sort((a, b) -> Float.compare(b.width(), a.width()));
        final Map<Wire, WireHops.Hopped> hopped = hops(wires, order);
        Hyb.beginBatch();
        if (glow != null && !glow.isEmpty()) {
            // A slow breath (1.6 s) on the halo, as Factory Flow's glow does.
            final double phase = (System.currentTimeMillis() % 1600) / 1600.0 * 2 * Math.PI;
            final int alpha = (int) (0x70 + 0x38 * Math.sin(phase));
            final int halo = alpha << 24 | 0xFFD257;
            for (final Wire w : wires) {
                if (glow.equals(w.resource())) drawHopped(hopped.get(w), w.width() + 7, halo, 0, 0);
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
