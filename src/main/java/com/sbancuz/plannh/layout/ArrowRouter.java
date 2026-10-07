package com.sbancuz.plannh.layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The wire router, after Factory Flow's: wires run on a grid in eight directions, straight, at right angles, or along
 * real 45-degree runs, around node boxes and clear of each other. Every wire leaves its output and lands on its input
 * heading right, along a straight run.
 * <p>
 * All coordinates are in graph/world space (un-zoomed, un-panned). Routing in world space keeps the produced paths
 * stable while the user pans (a pure translation) and lets the caller cache them.
 * <p>
 * A* runs over {@code (cell, heading)} so that bends can be priced: a 45-degree bend costs less than a right angle, so
 * where two cards are far enough apart a wire goes out straight, crosses on the diagonal and comes in straight, like a
 * metro map. Node rectangles are hard obstacles and their margin ring is dear; a cell another wire already runs along
 * is dear, and crossing another wire dearer still. Wires are routed heaviest first, then longest, and the ones that
 * still cross are ripped up and routed again around the rest while that removes crossings.
 */
public final class ArrowRouter {

    // Costs are in tenths of a world unit of travel, after Factory Flow's (whose are pixels): a right angle is worth an
    // 80-unit detour to avoid, a 45-degree bend 35. Making a turn cheaper than a right angle is what puts diagonals in.
    private static final int TURN45 = 350, TURN90 = 800;
    /** Turning within the clean run at either end: wires leave and land straight. */
    private static final int EARLY_TURN = 1000;
    /** Running into a cell another wire runs through along another line: a crossing. */
    private static final int CROSS = 4000;
    /** Per step, in steps, along a line another wire already runs on: the two would draw as one. */
    private static final int OVERLAP_STEPS = 6;
    /** Per step, in steps, beside another wire, so wires keep a cell apart where there is room. */
    private static final int NEAR_STEPS = 1;
    /**
     * Per step, in steps, in a node's margin ring. Soft rather than blocked: hard margins seal any gap narrower than
     * two
     * margins and force whole-chart detours.
     */
    private static final int MARGIN_STEPS = 8;
    /** Per step, in steps, on another wire's port approach, keeping each port's first and last run its own. */
    private static final int ANCHOR_STEPS = 30;

    /** Diagonals only between ends at least this far apart on one axis (Factory Flow: six of its 20-unit cells). */
    private static final int DIAGONAL_MIN_SPAN = 120;
    /** A diagonal run is at least this many cells long: a one-cell diagonal reads as a kink, not a direction. */
    private static final int DIAGONAL_MIN_STEPS = 4;
    /** Cells past the stub that count as the clean run at either end. */
    private static final int CLEAN_CELLS = 2;
    /** Rip-up-and-reroute rounds for wires that still cross, and the time they may take in all. */
    private static final int ROUNDS = 4;
    private static final long REROUTE_NANOS = 10_000_000;
    /**
     * A wire is first searched for within this many cells of the box round its two ends (Factory Flow: four of its
     * 20-unit cells), then within the wider window when that found nothing or still crosses a wire, and only then on
     * the whole board: most wires stay near their ends, and a wide search for each one is what makes routing slow.
     */
    private static final int WINDOW_CELLS = 13, WIDE_WINDOW_CELLS = 50;
    /** A search that has looked at this many states gives up (the wire falls back to a plain elbow). */
    private static final int MAX_POPS = 400_000;

    /**
     * World units a no-turn zone is grown by. One router cell, because that is the granularity the grid can actually
     * express: a smaller pad would round away to nothing on most edges and to a whole cell on others.
     */
    private static final int NO_TURN_PAD = 6;
    /** Hard cap on grid cells; the cell size is grown if a region would exceed it. Eight states per cell. */
    private static final int MAX_CELLS = 150_000;
    /** Padding added around the bounding region so wires can route around outer nodes. */
    private static final int PAD = 48;

    // Headings 0 E, 1 SE, 2 S, 3 SW, 4 W, 5 NW, 6 N, 7 NE: odd ones are diagonal, d ^ 4 is the reverse.
    private static final int[] DX = { 1, 1, 0, -1, -1, -1, 0, 1 };
    private static final int[] DY = { 0, 1, 1, 1, 0, -1, -1, -1 };
    /** The line a heading runs along: 0 horizontal, 1 vertical, 2 falling diagonal, 3 rising diagonal. */
    private static final int[] LINE = { 0, 2, 1, 3, 0, 2, 1, 3 };

    /** A rectangular obstacle (a recipe node) in world space. */
    public record Rect(int x, int y, int w, int h) {}

    /**
     * A single wire to route, from a source output port to a target input port. {@code weight} orders the routing,
     * heaviest first (the caller passes the wire's width), so the busiest wires get the cleanest lines.
     */
    public record Request(UUID key, int sx, int sy, int dx, int dy, double weight, List<Dock> sources,
        List<Dock> targets) {

        public Request(final UUID key, final int sx, final int sy, final int dx, final int dy, final double weight) {
            this(key, sx, sy, dx, dy, weight, null, null);
        }

        public Request(final UUID key, final int sx, final int sy, final int dx, final int dy) {
            this(key, sx, sy, dx, dy, 0);
        }

        /**
         * A wire that may leave its source and land on its target anywhere on their edges, as Factory Flow routes: the
         * router picks one dock at each end. (sx, sy) and (dx, dy) are the docks' middles, for ordering.
         */
        public static Request docked(final UUID key, final List<Dock> sources, final List<Dock> targets,
            final double weight) {
            return new Request(
                key,
                middle(sources, true),
                middle(sources, false),
                middle(targets, true),
                middle(targets, false),
                weight,
                List.copyOf(sources),
                List.copyOf(targets));
        }

        private static int middle(final List<Dock> docks, final boolean x) {
            long sum = 0;
            for (final Dock d : docks) sum += x ? d.x() : d.y();
            return docks.isEmpty() ? 0 : (int) (sum / docks.size());
        }

        boolean isDocked() {
            return sources != null && targets != null && !sources.isEmpty() && !targets.isEmpty();
        }
    }

    /** A side of a card, the heading a wire leaves it on, and its outward normal. */
    public enum Side {

        RIGHT(0, 1, 0),
        BOTTOM(2, 0, 1),
        LEFT(4, -1, 0),
        TOP(6, 0, -1);

        final int outward, nx, ny;

        Side(final int outward, final int nx, final int ny) {
            this.outward = outward;
            this.nx = nx;
            this.ny = ny;
        }
    }

    /**
     * Where a wire may meet a card: a point on its edge, the side it is on, and how far it is from that side's
     * middle (world px), which docking there costs: Factory Flow's centre bias, so facing wires meet middle to middle
     * and a wire slides toward a corner only when the route earns it.
     */
    public record Dock(int x, int y, Side side, int offCentre) {}

    /** The grid docks sit on, in world px: Factory Flow's board grid. */
    public static final int DOCK_GRID = 20;

    /**
     * Every place a wire may meet a box (a card, a drawer): each grid line crossing its edge, every other line on a
     * very large one so the candidates stay few, none closer than a cell to a corner (a wire off the very corner reads
     * as clipped through it). Factory Flow's {@code resolveGridRouteEndpoints}.
     */
    public static List<Dock> perimeterDocks(final Rect r) {
        final int left = r.x, right = r.x + r.w, top = r.y, bottom = r.y + r.h;
        final int cells = (r.w + r.h) / DOCK_GRID;
        final int step = cells > 60 ? 2 * DOCK_GRID : DOCK_GRID;
        final int keepX = r.w >= 2 * DOCK_GRID ? DOCK_GRID : 0, keepY = r.h >= 2 * DOCK_GRID ? DOCK_GRID : 0;
        final int cx = (left + right) / 2, cy = (top + bottom) / 2;
        final List<Dock> out = new ArrayList<>();
        // From the middle out each way, so the middle itself is always a dock.
        for (int d = 0; cx + d <= right - keepX; d += step) {
            out.add(new Dock(cx + d, top, Side.TOP, d));
            out.add(new Dock(cx + d, bottom, Side.BOTTOM, d));
            if (d == 0 || cx - d < left + keepX) continue;
            out.add(new Dock(cx - d, top, Side.TOP, d));
            out.add(new Dock(cx - d, bottom, Side.BOTTOM, d));
        }
        for (int d = 0; cy + d <= bottom - keepY; d += step) {
            out.add(new Dock(left, cy + d, Side.LEFT, d));
            out.add(new Dock(right, cy + d, Side.RIGHT, d));
            if (d == 0 || cy - d < top + keepY) continue;
            out.add(new Dock(left, cy - d, Side.LEFT, d));
            out.add(new Dock(right, cy - d, Side.RIGHT, d));
        }
        return out;
    }

    /** What a dock's distance from its side's middle costs, per world px: mid-way out a long side, about a turn. */
    private static final int DOCK_CENTRE_COST = 10;

    private final int baseCell;
    private final int margin;

    // Search buffers, kept between calls: the board routes on every layout change. Scores carry the run they belong to,
    // so they never need clearing. Not thread-safe; one router per thread.
    private int[] gScore = new int[0], cameFrom = new int[0], scoreRun = new int[0];
    private int runId = 1;
    private final OpenSet open = new OpenSet();

    /**
     * @param cell   nominal grid cell size in world units (also the minimum spacing granularity)
     * @param margin clearance kept around node boxes in world units
     */
    public ArrowRouter(final int cell, final int margin) {
        this.baseCell = Math.max(1, cell);
        this.margin = Math.max(0, margin);
    }

    /** @return a map from {@link Request#key()} to a list of {@code {x, y}} world-space waypoints */
    public Map<UUID, List<int[]>> route(final List<Rect> obstacles, final List<Request> requests) {
        return route(obstacles, List.of(), requests);
    }

    /**
     * @param noTurn regions a wire may cross but may not change direction inside.
     *
     *               <p>
     *               For the boundary chips: blocking them outright seals the approach to any pin one is parked in
     *               front of. A straight run behind a label reads fine; it is the corner that looks like the wire
     *               ends there.
     */
    public Map<UUID, List<int[]>> route(final List<Rect> obstacles, final List<Rect> noTurn,
        final List<Request> requests) {
        return route(obstacles, noTurn, requests, null);
    }

    /**
     * @param fellBack if given, receives the key of every request A* could not serve, which came back as the
     *                 obstacle-ignoring {@link #fallback}. A route that quietly gives up looks identical on screen to
     *                 one that went somewhere silly on purpose, and only one of those is worth investigating.
     */
    public Map<UUID, List<int[]>> route(final List<Rect> obstacles, final List<Rect> noTurn,
        final List<Request> requests, final Collection<UUID> fellBack) {
        return route(obstacles, noTurn, requests, fellBack, true);
    }

    /**
     * As above. {@code thorough} false skips the rip-up-and-reroute pass: for routing on every step of a drag, where
     * speed matters more than the last crossing; route thoroughly again once the drag ends.
     */
    public Map<UUID, List<int[]>> route(final List<Rect> obstacles, final List<Rect> noTurn,
        final List<Request> requests, final Collection<UUID> fellBack, final boolean thorough) {
        final Map<UUID, List<int[]>> result = new HashMap<>();
        if (requests.isEmpty()) return result;

        // ── Bounding region ──
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (final Rect r : obstacles) {
            minX = Math.min(minX, r.x - margin);
            minY = Math.min(minY, r.y - margin);
            maxX = Math.max(maxX, r.x + r.w + margin);
            maxY = Math.max(maxY, r.y + r.h + margin);
        }
        for (final Rect r : noTurn) {
            minX = Math.min(minX, r.x);
            minY = Math.min(minY, r.y);
            maxX = Math.max(maxX, r.x + r.w);
            maxY = Math.max(maxY, r.y + r.h);
        }
        final int stub = margin + baseCell;
        for (final Request q : requests) {
            minX = Math.min(minX, Math.min(q.sx, q.dx) - stub);
            minY = Math.min(minY, Math.min(q.sy, q.dy));
            maxX = Math.max(maxX, Math.max(q.sx, q.dx) + stub);
            maxY = Math.max(maxY, Math.max(q.sy, q.dy));
            if (!q.isDocked()) continue;
            for (final List<Dock> docks : List.of(q.sources, q.targets)) {
                for (final Dock d : docks) {
                    minX = Math.min(minX, d.x - stub);
                    minY = Math.min(minY, d.y - stub);
                    maxX = Math.max(maxX, d.x + stub);
                    maxY = Math.max(maxY, d.y + stub);
                }
            }
        }
        minX -= PAD;
        minY -= PAD;
        maxX += PAD;
        maxY += PAD;

        // Grow the cell if the region would need too many cells.
        int cell = baseCell;
        int cols, rows;
        while (true) {
            cols = (maxX - minX) / cell + 1;
            rows = (maxY - minY) / cell + 1;
            if ((long) cols * rows <= MAX_CELLS || cell > 4096) break;
            cell *= 2;
        }

        final Grid grid = new Grid(minX, minY, cols, rows, cell, stub);
        grid.blockObstacles(obstacles, margin);
        grid.markNoTurn(noTurn, NO_TURN_PAD);
        grid.reserveAnchors(requests);

        // Heaviest first, then longest, then top to bottom, so the order (and so the result) never depends on the
        // order the caller happened to list the wires in.
        final List<Integer> order = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) order.add(i);
        order.sort(
            Comparator.<Integer>comparingDouble(i -> -requests.get(i).weight)
                .thenComparingInt(i -> -span(requests.get(i)))
                .thenComparingInt(i -> requests.get(i).sy)
                .thenComparingInt(i -> requests.get(i).sx)
                .thenComparingInt(i -> requests.get(i).dy)
                .thenComparingInt(i -> requests.get(i).dx));

        final Route[] routes = new Route[requests.size()];
        for (final int i : order) {
            final Request q = requests.get(i);
            // Ports closer than two anchor stubs and room to turn between them cannot satisfy the
            // leave-right/arrive-right
            // state machine without looping around themselves; draw the canonical Z directly. Forward edges only: for a
            // backward edge the gap
            // is negative, and the Z would cut straight through every node between the two ports.
            if (!q.isDocked() && q.dx > q.sx
                && q.dx - q.sx < 2 * stub + 2 * cell
                && Math.abs(q.dy - q.sy) < 10 * baseCell) continue;
            routes[i] = q.isDocked() ? grid.searchDocks(q, i) : grid.search(q, i);
            if (routes[i] != null) grid.occupy(routes[i], 1);
        }

        // Wires that still cross: route each again around all the others, and keep it when it crosses less. Bounded in
        // count and time, so a tangled board still routes quickly; what is left crossing bridges when drawn.
        final long started = System.nanoTime();
        int budget = Math.max(12, 2 * requests.size());
        rounds: for (int round = 0; thorough && round < ROUNDS; round++) {
            boolean improved = false;
            for (final int i : order) {
                final Route old = routes[i];
                if (old == null) continue;
                if (budget <= 0 || System.nanoTime() - started > REROUTE_NANOS) break rounds;
                grid.occupy(old, -1);
                final int before = grid.crossings(old);
                if (before == 0) {
                    grid.occupy(old, 1);
                    continue;
                }
                budget--;
                final Route again = requests.get(i)
                    .isDocked() ? grid.searchDocks(requests.get(i), i) : grid.search(requests.get(i), i);
                if (again != null && grid.crossings(again) < before) {
                    routes[i] = again;
                    improved = true;
                }
                grid.occupy(routes[i], 1);
            }
            if (!improved) break;
        }

        for (int i = 0; i < requests.size(); i++) {
            final Request q = requests.get(i);
            if (routes[i] == null) {
                if (fellBack != null) fellBack.add(q.key);
                result.put(q.key, simplify(q.isDocked() ? fallbackDocked(q) : fallback(q, stub)));
            } else {
                result.put(q.key, q.isDocked() ? grid.toWorldDocked(routes[i], q) : grid.toWorld(routes[i], q));
            }
        }
        return result;
    }

    private static int span(final Request q) {
        return Math.abs(q.dx - q.sx) + Math.abs(q.dy - q.sy);
    }

    /** A docked wire A* could not route: from the source dock nearest the target to the target dock nearest it. */
    private static List<int[]> fallbackDocked(final Request q) {
        Dock s = q.sources.getFirst(), t = q.targets.getFirst();
        for (final Dock d : q.sources) if (dist2(d, q.dx, q.dy) < dist2(s, q.dx, q.dy)) s = d;
        for (final Dock d : q.targets) if (dist2(d, s.x, s.y) < dist2(t, s.x, s.y)) t = d;
        final List<int[]> p = new ArrayList<>(4);
        p.add(new int[] { s.x, s.y });
        p.add(new int[] { t.x, s.y });
        p.add(new int[] { t.x, t.y });
        return p;
    }

    private static long dist2(final Dock d, final int x, final int y) {
        return (long) (d.x - x) * (d.x - x) + (long) (d.y - y) * (d.y - y);
    }

    /** Simple direct route used when A* finds no path (degenerate layouts). */
    private static List<int[]> fallback(final Request q, final int stub) {
        final int midX = (q.sx + stub + q.dx - stub) / 2;
        final List<int[]> p = new ArrayList<>(4);
        p.add(new int[] { q.sx, q.sy });
        p.add(new int[] { midX, q.sy });
        p.add(new int[] { midX, q.dy });
        p.add(new int[] { q.dx, q.dy });
        return p;
    }

    /** The cost of turning from one heading to another; -1 when not allowed (135 degrees, or straight back). */
    private static int turnCost(final int from, final int to) {
        final int d = Math.abs(from - to), steps = Math.min(d, 8 - d);
        return steps == 0 ? 0 : steps == 1 ? TURN45 : steps == 2 ? TURN90 : -1;
    }

    /** A routed wire: the cells it runs through, start to goal, the heading it runs each one on, and what it cost. */
    private record Route(int[] cells, int[] headings, int cost, int source, int target) {

        Route(final int[] cells, final int[] headings, final int cost) {
            this(cells, headings, cost, -1, -1);
        }
    }

    private final class Grid {

        final int originX, originY, cols, rows, cell, stub;
        /** Step costs: a straight step is a cell, a diagonal step a cell times the square root of two. */
        final int orthStep, diagStep;
        final boolean[] blocked;
        /** Cells a wire may pass straight through but may not turn in. */
        final boolean[] straightOnly;
        /** What a step into each cell costs on top of its length: margin rings covering it, wires running beside it. */
        final int[] penalty;
        /** Wires running through each cell, per line (four per cell), and which lines carry any (a bit each). */
        final int[] lines;
        final byte[] lineMask;
        final int[] anchorOwner;

        Grid(final int originX, final int originY, final int cols, final int rows, final int cell, final int stub) {
            this.originX = originX;
            this.originY = originY;
            this.cols = cols;
            this.rows = rows;
            this.cell = cell;
            this.stub = stub;
            this.orthStep = cell * 10;
            this.diagStep = (int) Math.round(cell * 10 * Math.sqrt(2));
            final int cells = cols * rows;
            this.blocked = new boolean[cells];
            this.straightOnly = new boolean[cells];
            this.penalty = new int[cells];
            this.lines = new int[cells * 4];
            this.lineMask = new byte[cells];
            this.anchorOwner = new int[cells];
            Arrays.fill(anchorOwner, -1);
            final int states = cells * 8;
            if (gScore.length < states) {
                gScore = new int[states];
                cameFrom = new int[states];
                scoreRun = new int[states];
                runId = 1;
            }
        }

        int gx(final int wx) {
            return Math.clamp((wx - originX) / cell, 0, cols - 1);
        }

        int gy(final int wy) {
            return Math.clamp((wy - originY) / cell, 0, rows - 1);
        }

        int centerX(final int gx) {
            return originX + gx * cell + cell / 2;
        }

        int centerY(final int gy) {
            return originY + gy * cell + cell / 2;
        }

        void blockObstacles(final List<Rect> obstacles, final int margin) {
            for (final Rect r : obstacles) {
                final int x0 = gx(r.x - margin), x1 = gx(r.x + r.w + margin);
                final int y0 = gy(r.y - margin), y1 = gy(r.y + r.h + margin);
                final int bx0 = gx(r.x), bx1 = gx(r.x + r.w);
                final int by0 = gy(r.y), by1 = gy(r.y + r.h);
                for (int y = y0; y <= y1; y++) {
                    for (int x = x0; x <= x1; x++) {
                        if (x >= bx0 && x <= bx1 && y >= by0 && y <= by1) blocked[y * cols + x] = true;
                        else penalty[y * cols + x] += MARGIN_STEPS * orthStep;
                    }
                }
            }
        }

        /**
         * @param pad world units grown around each zone before it is rasterized. A corner sitting exactly on a label's
         *            edge still reads as a corner in the label; pushing the zone out slightly moves it clear.
         */
        void markNoTurn(final List<Rect> zones, final int pad) {
            for (final Rect r : zones) {
                for (int y = gy(r.y - pad); y <= gy(r.y + r.h + pad); y++) {
                    for (int x = gx(r.x - pad); x <= gx(r.x + r.w + pad); x++) {
                        straightOnly[y * cols + x] = true;
                    }
                }
            }
        }

        /** Marks every request's port-approach runs so other wires keep out of them. */
        void reserveAnchors(final List<Request> requests) {
            for (int i = 0; i < requests.size(); i++) {
                final Request q = requests.get(i);
                if (q.isDocked()) continue;
                markAnchor(gx(q.sx), gx(q.sx + stub), gy(q.sy), i);
                markAnchor(gx(q.dx - stub), gx(q.dx), gy(q.dy), i);
            }
        }

        private void markAnchor(final int x0, final int x1, final int y, final int owner) {
            for (int x = x0; x <= x1; x++) {
                final int idx = y * cols + x;
                if (anchorOwner[idx] == -1) anchorOwner[idx] = owner;
            }
        }

        /** Adds a wire to the board ({@code sign} 1) or takes it off again (-1). */
        void occupy(final Route route, final int sign) {
            for (int k = 0; k < route.cells.length; k++) {
                final int idx = route.cells[k];
                final int line = LINE[route.headings[k]];
                lines[idx * 4 + line] += sign;
                if (lines[idx * 4 + line] > 0) lineMask[idx] |= (byte) (1 << line);
                else lineMask[idx] &= (byte) ~(1 << line);
                final int x = idx % cols, y = idx / cols;
                for (int d = 0; d < 8; d += 2) {
                    final int nx = x + DX[d], ny = y + DY[d];
                    if (nx >= 0 && nx < cols && ny >= 0 && ny < rows)
                        penalty[ny * cols + nx] += sign * NEAR_STEPS * orthStep;
                }
            }
        }

        /** Cells where a wire runs through another one along a different line. */
        int crossings(final Route route) {
            int n = 0;
            for (int k = 0; k < route.cells.length; k++) {
                if ((lineMask[route.cells[k]] & ~(1 << LINE[route.headings[k]])) != 0) n++;
            }
            return n;
        }

        /** What a step into cell {@code idx} heading {@code dir} costs, beyond the turn. */
        private int stepCost(final int idx, final int dir, final int requestIndex) {
            final int step = (dir & 1) == 1 ? diagStep : orthStep;
            int cost = step + penalty[idx];
            final int mask = lineMask[idx], own = 1 << LINE[dir];
            if ((mask & own) != 0) cost += OVERLAP_STEPS * step;
            if ((mask & ~own) != 0) cost += CROSS;
            if (anchorOwner[idx] != -1 && anchorOwner[idx] != requestIndex) cost += ANCHOR_STEPS * orthStep;
            return cost;
        }

        /** A* over (cell, heading), near the wire's ends first; null when the goal cannot be reached. */
        Route search(final Request q, final int requestIndex) {
            // Close in first, wider only when that found nothing: a crossing left is the rip-up pass's to undo.
            Route best = search(q, requestIndex, WINDOW_CELLS);
            if (best == null) {
                final Route wider = search(q, requestIndex, WIDE_WINDOW_CELLS);
                if (wider != null && (best == null || wider.cost < best.cost)) best = wider;
            }
            if (best == null) best = search(q, requestIndex, Integer.MAX_VALUE / 4);
            return best;
        }

        private Route search(final Request q, final int requestIndex, final int window) {
            final int sgx = gx(q.sx + stub), sgy = gy(q.sy);
            final int ggx = gx(q.dx - stub), ggy = gy(q.dy);
            final int wx0 = Math.min(sgx, ggx) - window, wx1 = Math.max(sgx, ggx) + window;
            final int wy0 = Math.min(sgy, ggy) - window, wy1 = Math.max(sgy, ggy) + window;
            int pops = 0;
            // Diagonals on forward wires that go far enough; one looping back round its cards keeps square corners.
            final boolean diagonals = q.dx > q.sx
                && Math.max(Math.abs(q.dx - q.sx), Math.abs(q.dy - q.sy)) >= DIAGONAL_MIN_SPAN;
            final int run = nextRun();

            // Start heading +x (heading 0) so the wire leaves the output port to the right.
            final int startState = (sgy * cols + sgx) * 8;
            setScore(startState, 0, -1, run);
            open.clear();
            open.push(heuristic(sgx, sgy, 0, ggx, ggy, diagonals), 0, startState);

            while (!open.isEmpty()) {
                if (++pops > MAX_POPS) return null;
                final long top = open.pop();
                final int state = OpenSet.state(top);
                final int g = score(state, run);
                // A stale entry: the state was reached more cheaply since it was pushed.
                if (OpenSet.g(top) != Math.min(g, OpenSet.MASK)) continue;

                final int idx = state >> 3, dir = state & 7;
                final int cx = idx % cols, cy = idx / cols;
                if (cx == ggx && cy == ggy && dir == 0) return reconstruct(state, g);

                for (int nd = 0; nd < 8; nd++) {
                    final int turn = turnCost(dir, nd);
                    if (turn < 0 || (nd & 1) == 1 && !diagonals) continue;
                    // A corner inside a label reads as the wire ending there. Crossing it does not.
                    if (nd != dir && straightOnly[idx]) continue;
                    // Onto a diagonal: the whole minimum run at once.
                    final int steps = (nd & 1) == 1 && nd != dir ? DIAGONAL_MIN_STEPS : 1;
                    int x = cx, y = cy, cost = turn;
                    boolean ok = true;
                    for (int k = 0; k < steps && ok; k++) {
                        final int nx = x + DX[nd], ny = y + DY[nd];
                        if (nx < 0 || nx >= cols || ny < 0 || ny >= rows || blocked[ny * cols + nx]) ok = false;
                        else if (nx < wx0 || nx > wx1 || ny < wy0 || ny > wy1) ok = false;
                        // No cutting a corner on a diagonal.
                        else if ((nd & 1) == 1 && (blocked[y * cols + nx] || blocked[ny * cols + x])) ok = false;
                        else if (k == 0 && nd != dir && straightOnly[ny * cols + nx]) ok = false;
                        else {
                            cost += stepCost(ny * cols + nx, nd, requestIndex);
                            x = nx;
                            y = ny;
                        }
                    }
                    if (!ok) continue;
                    // Leaving and landing straight: a turn within the clean run at either end costs extra.
                    if (nd != dir && dir == 0 && cy == sgy && cx - sgx >= 0 && cx - sgx < CLEAN_CELLS)
                        cost += EARLY_TURN;
                    if (nd == 0 && nd != dir && y == ggy && ggx - x >= 0 && ggx - x < CLEAN_CELLS) cost += EARLY_TURN;

                    final int ng = g + cost;
                    final int nState = (y * cols + x) * 8 + nd;
                    if (ng < score(nState, run)) {
                        setScore(nState, ng, state, run);
                        open.push(ng + heuristic(x, y, nd, ggx, ggy, diagonals), ng, nState);
                    }
                }
            }
            return null;
        }

        /**
         * A lower bound on the cost to the goal, arriving heading +x: the distance on the grid (octile when diagonals
         * are allowed) plus the turns no path can avoid. Turns cost far more than steps, so counting them keeps A*
         * from fanning out over every equally short detour.
         */
        private int heuristic(final int x, final int y, final int dir, final int gx, final int gy,
            final boolean diagonals) {
            final int dx = gx - x, dy = gy - y, ax = Math.abs(dx), ay = Math.abs(dy);
            if (!diagonals) {
                final int turns = switch (dir) {
                    case 0 -> dy == 0 && dx >= 0 ? 0 : 2;
                    case 4 -> 2;
                    case 2 -> dy < 0 ? 3 : 1;
                    default -> dy > 0 ? 3 : 1;
                };
                return (ax + ay) * orthStep + turns * TURN90;
            }
            // In 45-degree steps. Wherever the goal is further down (or up) than it is ahead, a 45-degree run cannot
            // cover the drop, so there is an upright stretch to turn onto and off again: that is what the bound must
            // know, or A* fans out over every way to make the drop.
            final boolean steep = ay > dx;
            final int turns = switch (dir) {
                case 0 -> dy == 0 && dx >= 0 ? 0 : steep ? 4 : 2;
                // Heading down-right (1) or up-right (7): one bend back level, three if the goal is the other way up
                // or too steep for the diagonal.
                case 1 -> dy < 0 || steep ? 3 : 1;
                case 7 -> dy > 0 || steep ? 3 : 1;
                // Heading down (2) or up (6): two bends back level, four if the goal is the other way.
                case 2 -> dy < 0 ? 4 : 2;
                case 6 -> dy > 0 ? 4 : 2;
                case 3, 5 -> 3;
                default -> 4;
            };
            return (Math.max(ax, ay) - Math.min(ax, ay)) * orthStep + Math.min(ax, ay) * diagStep + turns * TURN45;
        }

        private int nextRun() {
            if (runId == Integer.MAX_VALUE) {
                Arrays.fill(scoreRun, 0);
                runId = 1;
            }
            return runId++;
        }

        private int score(final int state, final int run) {
            return scoreRun[state] == run ? gScore[state] : Integer.MAX_VALUE;
        }

        private void setScore(final int state, final int score, final int previousState, final int run) {
            scoreRun[state] = run;
            gScore[state] = score;
            cameFrom[state] = previousState;
        }

        /** The cells from start to goal, filling in the cells a multi-cell diagonal step jumped over. */
        private Route reconstruct(final int goalState, final int cost) {
            final List<Integer> states = new ArrayList<>();
            for (int s = goalState; s != -1; s = cameFrom[s]) states.add(s);
            final List<int[]> out = new ArrayList<>();
            final int first = states.getLast();
            out.add(new int[] { first >> 3, first & 7 });
            for (int i = states.size() - 2; i >= 0; i--) {
                final int s = states.get(i), from = states.get(i + 1) >> 3, to = s >> 3, dir = s & 7;
                int x = from % cols, y = from / cols;
                while (y * cols + x != to) {
                    x += DX[dir];
                    y += DY[dir];
                    out.add(new int[] { y * cols + x, dir });
                }
            }
            final int[] cells = new int[out.size()], headings = new int[out.size()];
            for (int i = 0; i < out.size(); i++) {
                cells[i] = out.get(i)[0];
                headings[i] = out.get(i)[1];
            }
            return new Route(cells, headings, cost);
        }

        // region Docked wires (Factory Flow's: either end anywhere on its card's edge)

        /** A docked wire: close in first, wider only when that found nothing, as {@link #search(Request, int)}. */
        Route searchDocks(final Request q, final int requestIndex) {
            Route best = searchDocks(q, requestIndex, WINDOW_CELLS);
            if (best == null) best = searchDocks(q, requestIndex, WIDE_WINDOW_CELLS);
            if (best == null) best = searchDocks(q, requestIndex, Integer.MAX_VALUE / 4);
            return best;
        }

        /**
         * A* from every source dock at once (each leaving its side straight out, from an apron a stub off the card,
         * starting at its centre cost) to whichever target dock is cheapest to land on (arriving straight in, plus its
         * centre cost). Goals are priced as they are reached, and the search stops once nothing still open could beat
         * the best one: the dock at each end is the router's call, as on the website.
         */
        private Route searchDocks(final Request q, final int requestIndex, final int window) {
            final List<Dock> sources = q.sources, targets = q.targets;
            final int[] sx = new int[sources.size()], sy = new int[sources.size()];
            final int[] tx = new int[targets.size()], ty = new int[targets.size()];
            int ax0 = Integer.MAX_VALUE, ay0 = Integer.MAX_VALUE, ax1 = Integer.MIN_VALUE, ay1 = Integer.MIN_VALUE;
            int tx0 = Integer.MAX_VALUE, ty0 = Integer.MAX_VALUE, tx1 = Integer.MIN_VALUE, ty1 = Integer.MIN_VALUE;
            for (int s = 0; s < sources.size(); s++) {
                final Dock d = sources.get(s);
                sx[s] = gx(d.x + d.side.nx * stub);
                sy[s] = gy(d.y + d.side.ny * stub);
                ax0 = Math.min(ax0, sx[s]);
                ay0 = Math.min(ay0, sy[s]);
                ax1 = Math.max(ax1, sx[s]);
                ay1 = Math.max(ay1, sy[s]);
            }
            for (int t = 0; t < targets.size(); t++) {
                final Dock d = targets.get(t);
                tx[t] = gx(d.x + d.side.nx * stub);
                ty[t] = gy(d.y + d.side.ny * stub);
                tx0 = Math.min(tx0, tx[t]);
                ty0 = Math.min(ty0, ty[t]);
                tx1 = Math.max(tx1, tx[t]);
                ty1 = Math.max(ty1, ty[t]);
            }
            final int wx0 = Math.min(ax0, tx0) - window, wx1 = Math.max(ax1, tx1) + window;
            final int wy0 = Math.min(ay0, ty0) - window, wy1 = Math.max(ay1, ty1) + window;
            // Diagonals once the ends are far enough apart; close by, square corners read better.
            final boolean diagonals = Math.max(Math.abs(q.dx - q.sx), Math.abs(q.dy - q.sy)) >= DIAGONAL_MIN_SPAN;
            final int run = nextRun();
            open.clear();

            final Map<Integer, Integer> startOf = new HashMap<>();
            for (int s = 0; s < sources.size(); s++) {
                final int idx = sy[s] * cols + sx[s];
                if (blocked[idx]) continue;
                final int state = idx * 8 + sources.get(s).side.outward;
                final int g = sources.get(s).offCentre * DOCK_CENTRE_COST;
                if (g >= score(state, run)) continue;
                setScore(state, g, -1, run);
                startOf.put(state, s);
                open.push(g + dockHeuristic(sx[s], sy[s], tx0, ty0, tx1, ty1, diagonals), g, state);
            }
            // Landing: on a target dock's apron, heading straight in; the cheaper dock where two share an apron.
            final Map<Integer, Integer> goalOf = new HashMap<>(), goalCost = new HashMap<>();
            for (int t = 0; t < targets.size(); t++) {
                final int idx = ty[t] * cols + tx[t];
                if (blocked[idx]) continue;
                final int state = idx * 8 + (targets.get(t).side.outward + 4) % 8;
                final int cost = targets.get(t).offCentre * DOCK_CENTRE_COST;
                if (cost < goalCost.getOrDefault(state, Integer.MAX_VALUE)) {
                    goalCost.put(state, cost);
                    goalOf.put(state, t);
                }
            }

            int bestState = -1, bestCost = Integer.MAX_VALUE, pops = 0;
            while (!open.isEmpty()) {
                if (++pops > MAX_POPS) break;
                final long top = open.pop();
                // Nothing still open can land cheaper than the best landing found.
                if (OpenSet.f(top) >= bestCost) break;
                final int state = OpenSet.state(top);
                final int g = score(state, run);
                if (OpenSet.g(top) != Math.min(g, OpenSet.MASK)) continue;
                final Integer landing = goalCost.get(state);
                if (landing != null && g + landing < bestCost) {
                    bestCost = g + landing;
                    bestState = state;
                }
                final int idx = state >> 3, dir = state & 7;
                final int cx = idx % cols, cy = idx / cols;
                for (int nd = 0; nd < 8; nd++) {
                    final int turn = turnCost(dir, nd);
                    if (turn < 0 || (nd & 1) == 1 && !diagonals) continue;
                    if (nd != dir && straightOnly[idx]) continue;
                    final int steps = (nd & 1) == 1 && nd != dir ? DIAGONAL_MIN_STEPS : 1;
                    int x = cx, y = cy, cost = turn;
                    boolean ok = true;
                    for (int k = 0; k < steps && ok; k++) {
                        final int nx = x + DX[nd], ny = y + DY[nd];
                        if (nx < 0 || nx >= cols || ny < 0 || ny >= rows || blocked[ny * cols + nx]) ok = false;
                        else if (nx < wx0 || nx > wx1 || ny < wy0 || ny > wy1) ok = false;
                        else if ((nd & 1) == 1 && (blocked[y * cols + nx] || blocked[ny * cols + x])) ok = false;
                        else if (k == 0 && nd != dir && straightOnly[ny * cols + nx]) ok = false;
                        else {
                            cost += stepCost(ny * cols + nx, nd, requestIndex);
                            x = nx;
                            y = ny;
                        }
                    }
                    if (!ok) continue;
                    final int ng = g + cost;
                    final int nState = (y * cols + x) * 8 + nd;
                    if (ng < score(nState, run)) {
                        setScore(nState, ng, state, run);
                        open.push(ng + dockHeuristic(x, y, tx0, ty0, tx1, ty1, diagonals), ng, nState);
                    }
                }
            }
            if (bestState < 0) return null;
            final Route route = reconstruct(bestState, bestCost);
            int first = bestState;
            while (cameFrom[first] != -1) first = cameFrom[first];
            return new Route(route.cells, route.headings, bestCost, startOf.get(first), goalOf.get(bestState));
        }

        /** A lower bound on the way to any target apron: the distance to their bounding box, octile with diagonals. */
        private int dockHeuristic(final int x, final int y, final int tx0, final int ty0, final int tx1, final int ty1,
            final boolean diagonals) {
            final int ax = Math.max(0, Math.max(tx0 - x, x - tx1)), ay = Math.max(0, Math.max(ty0 - y, y - ty1));
            if (!diagonals) return (ax + ay) * orthStep;
            return (Math.max(ax, ay) - Math.min(ax, ay)) * orthStep + Math.min(ax, ay) * diagStep;
        }

        /** A docked route in world points: out of its source dock straight, its corners, straight into its target. */
        List<int[]> toWorldDocked(final Route route, final Request q) {
            final Dock s = q.sources.get(route.source), t = q.targets.get(route.target);
            final List<int[]> pts = new ArrayList<>();
            pts.add(new int[] { s.x, s.y });
            final int n = route.cells.length;
            for (int k = 0; k < n; k++) {
                if (k == 0 || k == n - 1 || route.headings[k + 1] != route.headings[k]) {
                    final int idx = route.cells[k];
                    pts.add(new int[] { centerX(idx % cols), centerY(idx / cols) });
                }
            }
            pts.add(new int[] { t.x, t.y });
            // The end runs leave and land square to their sides: on the docks' own lines.
            lineUp(pts, true, s, n > 1 && route.headings[1] == s.side.outward);
            lineUp(pts, false, t, n > 1 && route.headings[n - 1] == (t.side.outward + 4) % 8);
            return simplify(true45(simplify(pts)));
        }

        /** Puts the corner next to an end (and the one past it, when the run goes on straight) on its dock's line. */
        private void lineUp(final List<int[]> pts, final boolean start, final Dock d, final boolean straightOn) {
            final int axis = d.side == Side.LEFT || d.side == Side.RIGHT ? 1 : 0;
            final int value = axis == 1 ? d.y : d.x;
            final int i = start ? 1 : pts.size() - 2, next = start ? 2 : pts.size() - 3;
            pts.get(i)[axis] = value;
            if (straightOn && next > 0 && next < pts.size() - 1) pts.get(next)[axis] = value;
        }

        // endregion

        /** Converts a route to world waypoints, snapping the end runs to the exact ports. */
        List<int[]> toWorld(final Route route, final Request q) {
            // The corners: the start, every cell the heading changes after, and the goal.
            final List<int[]> pts = new ArrayList<>();
            pts.add(new int[] { q.sx, q.sy });
            final int n = route.cells.length;
            for (int k = 0; k < n; k++) {
                if (k == 0 || k == n - 1 || route.headings[k + 1] != route.headings[k]) {
                    final int idx = route.cells[k];
                    pts.add(new int[] { centerX(idx % cols), centerY(idx / cols) });
                }
            }
            pts.add(new int[] { q.dx, q.dy });
            // The first run leaves heading right and the last lands heading right: put them on the ports' rows.
            pts.get(1)[1] = q.sy;
            if (n > 1 && route.headings[1] == 0) pts.get(2)[1] = q.sy;
            pts.get(pts.size() - 2)[1] = q.dy;
            if (n > 1 && route.headings[n - 1] == 0) pts.get(pts.size() - 3)[1] = q.dy;
            return simplify(true45(simplify(pts)));
        }
    }

    /**
     * Makes every diagonal run exactly 45 degrees again after the end runs were moved onto the ports' rows (up to half
     * a
     * cell), by sliding the corner it shares with a straight run along that run. A run that cannot be fixed that way
     * becomes an L.
     */
    private static List<int[]> true45(final List<int[]> pts) {
        final List<int[]> out = new ArrayList<>(pts);
        for (int i = 0; i + 1 < out.size(); i++) {
            final int[] a = out.get(i), b = out.get(i + 1);
            final int dx = b[0] - a[0], dy = b[1] - a[1];
            if (dx == 0 || dy == 0 || Math.abs(dx) == Math.abs(dy)) continue;
            final int[] prev = i > 0 ? out.get(i - 1) : null, next = i + 2 < out.size() ? out.get(i + 2) : null;
            if (prev != null && i > 0 && prev[1] == a[1]) {
                // Slide a along the straight run before it.
                final int x = b[0] - Integer.signum(dx) * Math.abs(dy);
                if (Integer.signum(x - prev[0]) == Integer.signum(a[0] - prev[0])) {
                    a[0] = x;
                    continue;
                }
            }
            if (next != null && i + 2 < out.size() && next[1] == b[1]) {
                // Slide b along the straight run after it.
                final int x = a[0] + Integer.signum(dx) * Math.abs(dy);
                if (Integer.signum(next[0] - x) == Integer.signum(next[0] - b[0])) {
                    b[0] = x;
                    continue;
                }
            }
            if (prev != null && i > 0 && prev[0] == a[0]) {
                final int y = b[1] - Integer.signum(dy) * Math.abs(dx);
                if (Integer.signum(y - prev[1]) == Integer.signum(a[1] - prev[1])) {
                    a[1] = y;
                    continue;
                }
            }
            if (next != null && i + 2 < out.size() && next[0] == b[0]) {
                final int y = a[1] + Integer.signum(dy) * Math.abs(dx);
                if (Integer.signum(next[1] - y) == Integer.signum(next[1] - b[1])) {
                    b[1] = y;
                    continue;
                }
            }
            // No straight neighbour to absorb it: go round the corner square.
            out.add(i + 1, new int[] { b[0], a[1] });
            i++;
        }
        return out;
    }

    /** Drops repeated points and points in the middle of a straight run, so only real corners remain. */
    private static List<int[]> simplify(final List<int[]> pts) {
        final List<int[]> out = new ArrayList<>(pts.size());
        for (final int[] p : pts) {
            if (!out.isEmpty()) {
                final int[] last = out.getLast();
                if (last[0] == p[0] && last[1] == p[1]) continue;
            }
            out.add(p);
        }
        for (int i = 1; i < out.size() - 1;) {
            final int[] a = out.get(i - 1), b = out.get(i), c = out.get(i + 1);
            final long cross = (long) (b[0] - a[0]) * (c[1] - b[1]) - (long) (b[1] - a[1]) * (c[0] - b[0]);
            final boolean sameWay = (long) (b[0] - a[0]) * (c[0] - b[0]) + (long) (b[1] - a[1]) * (c[1] - b[1]) > 0;
            if (cross == 0 && sameWay) out.remove(i);
            else i++;
        }
        return out;
    }

    /**
     * The A* open set: a binary min-heap of longs, each packing f, then g inverted (so of two equally promising states
     * the one further along comes out first), then the state. No allocation per push.
     */
    private static final class OpenSet {

        private static final int BITS = 21;
        static final long MASK = (1L << BITS) - 1;
        private long[] heap = new long[1024];
        private int size;

        void clear() {
            size = 0;
        }

        boolean isEmpty() {
            return size == 0;
        }

        void push(final int f, final int g, final int state) {
            final long key = Math.min(f, MASK) << 2 * BITS | (MASK - Math.min(g, MASK)) << BITS | state;
            if (size == heap.length) heap = Arrays.copyOf(heap, size * 2);
            int i = size++;
            while (i > 0) {
                final int parent = (i - 1) >>> 1;
                if (heap[parent] <= key) break;
                heap[i] = heap[parent];
                i = parent;
            }
            heap[i] = key;
        }

        long pop() {
            final long top = heap[0];
            final long last = heap[--size];
            if (size == 0) return top;
            int i = 0;
            while (true) {
                int child = 2 * i + 1;
                if (child >= size) break;
                if (child + 1 < size && heap[child + 1] < heap[child]) child++;
                if (heap[child] >= last) break;
                heap[i] = heap[child];
                i = child;
            }
            heap[i] = last;
            return top;
        }

        static int state(final long key) {
            return (int) (key & MASK);
        }

        static int g(final long key) {
            return (int) (MASK - (key >>> BITS & MASK));
        }

        static int f(final long key) {
            return (int) (key >>> 2 * BITS);
        }
    }
}
