package com.gtnhplanner.layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The wire router: wires run on a grid of ten-pixel cells in eight directions, straight, at right angles, or along
 * 45-degree runs, around the boxes (cards and drawers) and clear of each other, and meet a box anywhere on its edge,
 * square to it.
 *
 * <p>
 * How it reads a board:
 * <ul>
 * <li>A box blocks the cells it covers. The ring of cells touching it is all but closed to other wires (a wire hugging
 * a box reads as touching it), the ring outside that a little dear; a box's own wires cross both freely leaving or
 * arriving square to its side, so two boxes a cell apart still join with a straight line.</li>
 * <li>A wire ends in the cell just outside a box's side (its lane), heading out of the source or into the target, at
 * any row or column along the side but the corners. Facing sides line up exactly wherever they overlap, so a wire
 * between them can be straight.</li>
 * <li>Where each wire meets each box is planned first, as on the website: every end's ideal place is on the side facing
 * the other box, across from it; a box's ends are matched to places along its edge in that order, two cells apart
 * where there is room, so the wires leaving a box fan out without crossing at it, and two wires between the same two
 * boxes run side by side. The search then starts from places near the planned one, cheapest nearest it, and only when
 * that crosses another wire from anywhere on the side.</li>
 * <li>A* runs over (cell, heading), so bends are priced, and dear: a wire runs straight wherever it can, and a far one
 * goes out straight, crosses on a diagonal and comes in straight, like a metro map. After a turn a wire runs on at
 * least three cells (four on a diagonal), so there are no little jogs. Running along another wire's line is dear,
 * crossing it as dear as a right angle, a third line through one point dearer again: weaving round other wires to
 * dodge a crossing reads worse than the crossing.</li>
 * <li>Each search is exact (it never settles for a dearer route, which would be a wigglier one) and works on a window
 * round the wire's ends: the board's costs there are copied out for that wire into one small table, and the least cost
 * from every cell of the window to an arrival is worked out first (boxes, rings and other wires included); that, with
 * the turning any way on must still do, guides the search straight round rows of cards and across the wires it must
 * cross, where a guess by distance alone would have it search every dead end on the way.</li>
 * <li>Each wire is searched near its ends first, wider only when that crosses or finds nothing. A wire whose ends are
 * walled apart (boxes overlapping) is known from the board's open regions, and is routed through boxes as lightly as
 * it can rather than searched for in vain.</li>
 * <li>Wires route heaviest first, then longest; those still crossing or running on another are ripped up and routed
 * again around the rest while that helps, within a budget of search steps, so the result never depends on how fast
 * the machine is.</li>
 * </ul>
 * The router keeps the board and its routes between calls: {@link #reroute} after a box moves routes again only the
 * wires at that box and those running where it now is or just was, so dragging a card re-routes in a moment on any
 * board. All coordinates are world space, so routes stay put while the view pans.
 */
public final class WireRouter {

    /** The grid's cell, in world px: wires run at least this far apart. */
    public static final int CELL = 10;
    /** Sides, as bits: a wire may meet its box on any of those it is given. */
    public static final int RIGHT = 1, BOTTOM = 2, LEFT = 4, TOP = 8, ANY = 15;

    /** A box: a card or a drawer, in world space. */
    public record Box(int x, int y, int w, int h) {}

    /**
     * A wire to route between two boxes (indexes into the box list), on the sides allowed at each end.
     * {@code weight} orders the routing, heaviest first (its drawn width), so the busiest wires get the cleanest lines.
     */
    public record Wire(UUID key, int from, int to, int fromSides, int toSides, double weight) {

        public Wire(final UUID key, final int from, final int to, final double weight) {
            this(key, from, to, ANY, ANY, weight);
        }

    }

    // Costs are in tenths of a world px of travel. A 45-degree bend is worth a 100 px detour to avoid, a right angle
    // 200,
    // a crossing 200: set against the layout benchmark (build/bench), where Factory Flow's own prices (35, 80, 400) had
    // long wires weaving. The gtnhplanner.router.* system properties override them there.
    private static final int STEP = CELL * 10;
    private static final int DIAG = (int) Math.round(STEP * Math.sqrt(2));
    private static final int TURN45 = Integer.getInteger("gtnhplanner.router.turn45", 1000),
        TURN90 = Integer.getInteger("gtnhplanner.router.turn90", 2000);
    /** Into a cell another wire runs through along another line: a crossing. */
    private static final int CROSS = Integer.getInteger("gtnhplanner.router.cross", 2000);
    /** On top of a crossing: a third line through the point, or a corner on another wire; they read as a junction. */
    private static final int JUNCTION = 2000;
    /** Per step along another wire's line, in the same cell: the two would draw as one. */
    private static final int OVERLAP = 6 * STEP;
    /** Per wire running in a neighbouring cell: a little, so wires keep a cell apart where they can without weaving. */
    private static final int NEAR = Integer.getInteger("gtnhplanner.router.nearCost", 30);
    /** Per step in the ring of cells touching a box (all but closed), and in the ring outside that. */
    private static final int RING1 = 40 * STEP, RING2 = 2 * STEP;
    /** Per step through a box, for a wire walled off from where it is going: through, but as little as it can. */
    private static final int THROUGH = 30 * STEP;
    /** Per world px a planned wire's end is off its planned place, and an unplanned one off its side's middle. */
    private static final int PLAN_PX = 4, DOCK_PX = 3;
    /** How far along the edge from its planned place a wire's end may move, in cells. */
    private static final int PLAN_REACH = 12;
    /** Planned ends on one box keep this many cells apart where the side has room. */
    private static final int PLAN_SPACING = 2;
    /** Ending where another wire ends: a detour of up to that long beats sharing the spot. */
    private static final int DOCK_TAKEN = 100 * STEP;
    /** Docks keep a cell clear of a box's corners: a wire off the very corner reads as clipped through it. */
    private static final int KEEP = CELL;
    /** Diagonals only between boxes at least this far apart on one axis, and in runs of at least this many cells. */
    private static final int DIAGONAL_MIN_GAP = 120, DIAGONAL_MIN_STEPS = 4;
    /** After a turn onto a straight heading, at least this many cells on it: no one-cell jogs. */
    private static final int STRAIGHT_MIN_STEPS = Integer.getInteger("gtnhplanner.router.run", 3);
    /** Room round everything, for wires that go round the outer boxes. */
    private static final int PAD = 100;
    /** Room past that the grid keeps, so a box dragged past the board's edge still routes on the same grid. */
    private static final int SLACK = 400;
    /** Cells round a wire's ends searched first; if that crosses or fails, then wider, then wider again. */
    private static final int WINDOW = 6, WIDE_WINDOW = 30, FAR_WINDOW = 120;
    /** The most cells one search's window may hold: a wider reach is cut down round the wire's ends. */
    private static final int MAX_WINDOW_CELLS = 400_000;
    /**
     * A search gives up, keeping the best arrival it found, after looking at this many states for each cell of its
     * window (and at least the floor).
     */
    private static final int POPS_PER_CELL = 24, MIN_POPS = 40_000;
    /** Wider searches stop being tried once the first pass has looked at this many states per wire. */
    private static final long WIDEN_POPS_PER_WIRE = 20_000;
    /**
     * How far each search trusts its estimate over what it has paid, in percent (weighted A*): 100, exact, for the near
     * search and the wider ones alike. A weighted search settles for dearer routes, and on a crowded board a dearer
     * route is a wigglier one; with the estimate this good, exact is as quick.
     */
    private static final int NEAR_WEIGHT = Integer.getInteger("gtnhplanner.router.near", 100),
        WIDE_WEIGHT = Integer.getInteger("gtnhplanner.router.wide", 100);
    /**
     * Rip-up-and-reroute rounds (a second round gains next to nothing), and the search steps per wire they may take.
     */
    private static final int ROUNDS = Integer.getInteger("gtnhplanner.router.rounds", 1);
    private static final long REROUTE_POPS_PER_WIRE = 40_000;
    /** The grid grows its cell past this many cells (a very large board routes coarser rather than slower). */
    private static final long MAX_CELLS = 2_500_000;

    // Headings 0 E, 1 SE, 2 S, 3 SW, 4 W, 5 NW, 6 N, 7 NE: odd ones are diagonal, d ^ 4 is the reverse.
    private static final int[] DX = { 1, 1, 0, -1, -1, -1, 0, 1 };
    private static final int[] DY = { 0, 1, 1, 1, 0, -1, -1, -1 };
    /** The line a heading runs along: 0 horizontal, 1 vertical, 2 falling diagonal, 3 rising diagonal. */
    private static final int[] LINE = { 0, 2, 1, 3, 0, 2, 1, 3 };
    /** Sides in order round a box, clockwise from the top: their bits and outward headings. */
    private static final int[] SIDE_BITS = { TOP, RIGHT, BOTTOM, LEFT };
    private static final int[] OUTWARD = { 6, 0, 2, 4 };
    /** Turning from one heading to another ({@code from * 8 + to}): -1 when not allowed (135 degrees, or back). */
    private static final int[] TURN = turns();
    /**
     * Entering a cell along a line ({@code lines * 4 + line}) where other wires run on {@code lines}: running along
     * one of them, crossing them, and a third line through the point.
     */
    private static final int[] LINE_COST = lineCosts();

    // ── The board, rasterized; kept between routes, so a move routes only what it touched ──
    private int cell, ox, oy, cols, rows;
    private List<Box> boxes;
    /** Each box's cells: the columns and rows whose middles it covers (none when c0 > c1 or r0 > r1). */
    private int[] bc0 = new int[0], bc1 = new int[0], br0 = new int[0], br1 = new int[0];
    /** How many boxes cover each cell: a covered cell is blocked. */
    private short[] cover = new short[0];
    /**
     * Wires through each cell per line, which lines carry any, wires in the four cells round it, wires ending there.
     */
    private byte[] lines = new byte[0], mask = new byte[0];
    private short[] near = new short[0], dockUse = new short[0];
    /** Every place a wire may meet each box, in order round it from its top-left corner. */
    private List<List<Dock>> rims = new ArrayList<>();
    /**
     * The open cells' regions, worked out when first asked after a box moved: each row's runs of open cells, joined
     * with the runs they touch in the row above. Two open cells reach each other exactly when their runs share a root.
     */
    private boolean regionsStale = true;
    private int[] rowStart = new int[0], runX0 = new int[0], runX1 = new int[0], runRoot = new int[0];

    // ── What the last call left: every box's key, every wire, its route, its corners, whether it was routed roughly ──
    private List<?> keys;
    private List<Wire> wires;
    private Route[] routes = new Route[0];
    private List<List<int[]>> paths = new ArrayList<>();
    private boolean[] rough = new boolean[0];

    // ── One search's window: the cells round a wire's ends inside a shut border, its costs worked out for that wire ──
    private int wx0, wy0, lw, lh;
    /** Per window cell and line: what entering it costs beyond the step, or {@link #SHUT}. */
    private int[] wcost = new int[0];
    /** Per window cell: the lines other wires run on there, and {@link #BOX} for a box's cell. */
    private byte[] wflag = new byte[0];
    /** Per window cell: the least cost from it to an arrival; arrivals' extra cost, dock and heading. */
    private int[] field = new int[0], goalExtra = new int[0], goalDock = new int[0];
    private byte[] goalHeading = new byte[0];
    /**
     * Per window cell and heading: what reaching it cost, and the heading it came from ({@link #START}, {@link #RUN}).
     */
    private int[] g8 = new int[0];
    private byte[] from8 = new byte[0];
    /** A step in each heading, in window cells. */
    private final int[] dl = new int[8];
    private final Heap open = new Heap(), fieldOpen = new Heap();
    /** States the first pass may look at before wider searches stop being tried. */
    private long widenBudget;
    /** Each search as it ends, for a benchmark looking into where the time goes; null in the game. */
    public static java.util.function.Consumer<String> trace;

    private static final int SHUT = -1, UNREACHED = Integer.MAX_VALUE;
    private static final int BOX = 16, LINES = 15;
    /** {@link #from8}: a start, and a state reached by a whole diagonal run at once. */
    private static final byte START = 32, RUN = 8, CLOSED = 64;
    /**
     * This search's arrivals by the way they are made (E, S, W, N): which ways at all, and for each the lane line
     * (least and most) and the span along it, in window cells; and the least turn it may make.
     */
    private int goalSides, turnMin;
    private boolean diagonalsOn;
    private final int[] goalLineMin = new int[4], goalLineMax = new int[4], goalLo = new int[4], goalHi = new int[4];

    /** What the last call did: states looked at, searches run, searches widened, wires that fell back. */
    public long statPops, statSealedPops, statFirstPassPops, statWindowCells;

    public int statSearches, statWidened, statFailed, statRerouted, statSealed, statMaxWindow, statRouted;

    /**
     * A place a wire may meet a box: its lane cell (a grid index), the heading out of the box, the point on its edge,
     * how far round the box it is from its top-left corner, and what ending there costs.
     */
    private record Dock(int cell, int heading, int x, int y, int round, int cost) {}

    /** A routed wire: the cells it runs through, the heading it enters each one on, what it cost and its two docks. */
    private record Route(int[] cells, int[] headings, int cost, Dock source, Dock target) {}

    /** The grid's size in cells, for the record. */
    public long gridCells() {
        return (long) cols * rows;
    }

    /** Routes every wire; a map from {@link Wire#key()} to its corners in world space, source first. */
    public Map<UUID, List<int[]>> route(final List<Box> boxes, final List<Wire> wires) {
        return route(boxes, wires, null, true);
    }

    /**
     * Routes every wire afresh. {@code fellBack} (if given) receives every wire the search could not serve, drawn as a
     * plain elbow instead; {@code thorough} false skips the rip-up-and-reroute pass and the wider searches, for routing
     * while a box is dragged.
     */
    public Map<UUID, List<int[]>> route(final List<Box> boxes, final List<Wire> wires, final Collection<UUID> fellBack,
        final boolean thorough) {
        return route(boxes, null, wires, fellBack, thorough);
    }

    /**
     * As {@link #route(List, List, Collection, boolean)}, with a key for each box: anything that stays equal for the
     * same box from one call to the next (null: its place in the list), so {@link #reroute} can tell a box that moved
     * from one that is new.
     */
    public Map<UUID, List<int[]>> route(final List<Box> boxes, final List<?> keys, final List<Wire> wires,
        final Collection<UUID> fellBack, final boolean thorough) {
        resetStats();
        this.keys = keys == null ? indexes(boxes.size()) : List.copyOf(keys);
        this.wires = List.copyOf(wires);
        grid(boxes, null);
        final int n = wires.size();
        routes = new Route[n];
        paths = new ArrayList<>(Collections.nCopies(n, List.of()));
        rough = new boolean[n];
        final boolean[] redo = new boolean[n];
        Arrays.fill(redo, true);
        routeAgain(redo, thorough);
        return answer(fellBack);
    }

    /** As {@link #reroute(List, List, List, Collection, boolean)}, the boxes known by their place in the list. */
    public Map<UUID, List<int[]>> reroute(final List<Box> boxes, final List<Wire> wires,
        final Collection<UUID> fellBack, final boolean thorough) {
        return reroute(boxes, null, wires, fellBack, thorough);
    }

    /**
     * As {@link #route}, after the last call's board changed, routing again only what the change touched. Boxes are
     * told apart by their keys and wires by theirs: a wire keeps its route when both its boxes are where they were and
     * the route runs nowhere a box left, took or now stands; the rest (wires at a box that moved, new wires, wires
     * running through where a box now is or just was) are routed again around the kept ones. When most of the board
     * changed it routes afresh. {@code thorough} also routes again, properly, every wire routed roughly since (while a
     * box was dragged).
     */
    public Map<UUID, List<int[]>> reroute(final List<Box> boxes, final List<?> keys, final List<Wire> wires,
        final Collection<UUID> fellBack, final boolean thorough) {
        final List<?> next = keys == null ? indexes(boxes.size()) : keys;
        if (this.boxes == null || this.wires == null) return route(boxes, next, wires, fellBack, thorough);
        // Each box to the one it was last time (by key), or -1 when it is new.
        final Map<Object, Integer> was = new HashMap<>();
        for (int i = 0; i < this.keys.size(); i++) was.put(this.keys.get(i), i);
        final List<Box> before = this.boxes;
        final int n = boxes.size();
        final int[] old = new int[n];
        final boolean[] changed = new boolean[n], kept = new boolean[before.size()];
        int changes = 0;
        for (int i = 0; i < n; i++) {
            final Integer o = was.get(next.get(i));
            old[i] = o == null || kept[o] ? -1 : o;
            if (old[i] >= 0) kept[old[i]] = true;
            changed[i] = old[i] < 0 || !boxes.get(i)
                .equals(before.get(old[i]));
            if (changed[i]) changes++;
        }
        for (final boolean k : kept) if (!k) changes++;
        // Most of the board changed: routing it afresh is as quick, and cleaner.
        if (2 * changes > Math.max(n, before.size())) return route(boxes, next, wires, fellBack, thorough);
        resetStats();

        if (!fits(boxes, changed)) {
            if (!regrid(boxes)) return route(boxes, next, wires, fellBack, thorough);
        } else {
            // Off the grid: the boxes gone, and those that changed from where they were.
            for (int o = 0; o < before.size(); o++) if (!kept[o]) cover(o, -1);
            for (int i = 0; i < n; i++) if (changed[i] && old[i] >= 0) cover(old[i], -1);
            // The boxes as they are now: those that stayed keep their cells and rims.
            final int[] c0 = new int[n], c1 = new int[n], r0 = new int[n], r1 = new int[n];
            final List<List<Dock>> now = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                if (changed[i]) {
                    now.add(List.of());
                    continue;
                }
                c0[i] = bc0[old[i]];
                c1[i] = bc1[old[i]];
                r0[i] = br0[old[i]];
                r1[i] = br1[old[i]];
                now.add(rims.get(old[i]));
            }
            this.boxes = boxes;
            bc0 = c0;
            bc1 = c1;
            br0 = r0;
            br1 = r1;
            rims = now;
            for (int i = 0; i < n; i++) {
                if (!changed[i]) continue;
                cells(i);
                cover(i, 1);
                rims.set(i, rim(i));
            }
            if (changes > 0) regionsStale = true;
        }

        // The areas boxes left and took, their rings with them.
        final List<int[]> areas = new ArrayList<>();
        for (int o = 0; o < before.size(); o++) if (!kept[o]) areas.add(area(before.get(o)));
        for (int i = 0; i < n; i++) {
            if (!changed[i]) continue;
            if (old[i] >= 0) areas.add(area(before.get(old[i])));
            areas.add(area(boxes.get(i)));
        }
        // Each wire to the one it was last time (by key): kept when nothing it depends on changed.
        final Map<UUID, Integer> wireWas = new HashMap<>();
        for (int k = 0; k < this.wires.size(); k++) wireWas.put(this.wires.get(k).key, k);
        final int m = wires.size();
        final Route[] nextRoutes = new Route[m];
        final List<List<int[]>> nextPaths = new ArrayList<>(Collections.nCopies(m, List.of()));
        final boolean[] nextRough = new boolean[m], redo = new boolean[m], carried = new boolean[this.wires.size()];
        for (int k = 0; k < m; k++) {
            final Wire w = wires.get(k);
            final Integer o = wireWas.get(w.key);
            boolean keep = false;
            if (o != null && !carried[o]) {
                final Wire ow = this.wires.get(o);
                final boolean same = old[w.from] == ow.from && old[w.to] == ow.to
                    && !changed[w.from]
                    && !changed[w.to]
                    && w.fromSides == ow.fromSides
                    && w.toSides == ow.toSides;
                // A wire that fell back is tried again only properly, or when its boxes move.
                keep = same && (routes[o] != null ? !(thorough && rough[o]) && !runsIn(routes[o], areas) : !thorough);
            }
            if (keep) {
                carried[o] = true;
                nextRoutes[k] = routes[o];
                nextPaths.set(k, paths.get(o));
                nextRough[k] = rough[o];
            } else redo[k] = true;
        }
        for (int o = 0; o < this.wires.size(); o++) if (!carried[o] && routes[o] != null) occupy(routes[o], -1);
        this.keys = List.copyOf(next);
        this.wires = List.copyOf(wires);
        routes = nextRoutes;
        paths = nextPaths;
        rough = nextRough;
        routeAgain(redo, thorough);
        return answer(fellBack);
    }

    private static List<Integer> indexes(final int n) {
        final List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(i);
        return out;
    }

    private void resetStats() {
        statPops = statSealedPops = statFirstPassPops = statWindowCells = 0;
        statSearches = statWidened = statFailed = statRerouted = statSealed = statMaxWindow = statRouted = 0;
    }

    /**
     * Routes the wires marked (none of them on the board): heaviest first, then longest, then by place, so the result
     * never depends on the order wires were listed in; then, when {@code thorough}, those still crossing or running on
     * another are each routed again around all the others and kept when they do better, bounded in count and search
     * steps, so a tangled board still routes quickly and always the same way (what is left crossing hops when drawn).
     */
    private void routeAgain(final boolean[] redo, final boolean thorough) {
        final List<Integer> picked = new ArrayList<>();
        for (int i = 0; i < redo.length; i++) if (redo[i]) picked.add(i);
        if (picked.isEmpty()) return;
        statRouted = picked.size();
        widenBudget = WIDEN_POPS_PER_WIRE * picked.size();
        final Dock[][] plan = plan(wires);
        picked.sort(
            Comparator.<Integer>comparingDouble(i -> -wires.get(i).weight)
                .thenComparingInt(i -> -span(wires.get(i)))
                .thenComparingInt(i -> centreY(wires.get(i).from))
                .thenComparingInt(i -> centreX(wires.get(i).from))
                .thenComparingInt(i -> centreY(wires.get(i).to))
                .thenComparingInt(i -> centreX(wires.get(i).to)));
        for (final int i : picked) {
            routes[i] = search(wires.get(i), plan[i][0], plan[i][1], thorough);
            if (routes[i] != null) occupy(routes[i], 1);
        }
        statFirstPassPops = statPops;
        final long budgetPops = statPops + REROUTE_POPS_PER_WIRE * picked.size();
        int budget = Math.max(16, 2 * picked.size());
        rounds: for (int round = 0; thorough && round < ROUNDS; round++) {
            boolean improved = false;
            for (final int i : picked) {
                final Route old = routes[i];
                if (old == null) continue;
                if (budget <= 0 || statPops > budgetPops) break rounds;
                occupy(old, -1);
                final int before = conflicts(old);
                if (before == 0) {
                    occupy(old, 1);
                    continue;
                }
                budget--;
                statRerouted++;
                final Route again = search(wires.get(i), plan[i][0], plan[i][1], true);
                if (again != null
                    && (conflicts(again) < before || conflicts(again) == before && again.cost < old.cost)) {
                    routes[i] = again;
                    improved = true;
                }
                occupy(routes[i], 1);
            }
            if (!improved) break;
        }
        for (final int i : picked) {
            paths.set(i, routes[i] == null ? fallback(wires.get(i)) : toWorld(routes[i]));
            rough[i] = !thorough;
        }
    }

    /** Every wire's corners by key; those that fell back to an elbow also into {@code fellBack}. */
    private Map<UUID, List<int[]>> answer(final Collection<UUID> fellBack) {
        final Map<UUID, List<int[]>> out = new HashMap<>();
        for (int i = 0; i < wires.size(); i++) {
            final UUID key = wires.get(i).key;
            out.put(key, paths.get(i));
            if (routes[i] == null && fellBack != null) fellBack.add(key);
        }
        return out;
    }

    private int span(final Wire w) {
        return Math.abs(centreX(w.to) - centreX(w.from)) + Math.abs(centreY(w.to) - centreY(w.from));
    }

    private int centreX(final int box) {
        final Box b = boxes.get(box);
        return b.x + b.w / 2;
    }

    private int centreY(final int box) {
        final Box b = boxes.get(box);
        return b.y + b.h / 2;
    }

    // region The grid

    /**
     * Lays the grid over the boxes (and over {@code keep}, x0 y0 x1 y1, when given: the grid it replaces), with room
     * round them, and puts the boxes on it; no wires yet.
     */
    private void grid(final List<Box> boxes, final int[] keep) {
        this.boxes = boxes;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (final Box b : boxes) {
            x0 = Math.min(x0, b.x);
            y0 = Math.min(y0, b.y);
            x1 = Math.max(x1, b.x + b.w);
            y1 = Math.max(y1, b.y + b.h);
        }
        if (x0 > x1) x0 = y0 = x1 = y1 = 0;
        x0 -= PAD + SLACK;
        y0 -= PAD + SLACK;
        x1 += PAD + SLACK;
        y1 += PAD + SLACK;
        if (keep != null) {
            x0 = Math.min(x0, keep[0]);
            y0 = Math.min(y0, keep[1]);
            x1 = Math.max(x1, keep[2]);
            y1 = Math.max(y1, keep[3]);
        }
        cell = CELL;
        while ((long) ((x1 - x0) / cell + 1) * ((y1 - y0) / cell + 1) > MAX_CELLS) cell *= 2;
        // The origin on the cell grid, so boxes on the board's grid have their edges on cell boundaries.
        ox = Math.floorDiv(x0, cell) * cell;
        oy = Math.floorDiv(y0, cell) * cell;
        cols = (x1 - ox) / cell + 1;
        rows = (y1 - oy) / cell + 1;
        final int n = cols * rows;
        if (cover.length < n) {
            cover = new short[n];
            near = new short[n];
            dockUse = new short[n];
            mask = new byte[n];
            lines = new byte[4 * n];
        } else {
            Arrays.fill(cover, 0, n, (short) 0);
            Arrays.fill(near, 0, n, (short) 0);
            Arrays.fill(dockUse, 0, n, (short) 0);
            Arrays.fill(mask, 0, n, (byte) 0);
            Arrays.fill(lines, 0, 4 * n, (byte) 0);
        }
        final int count = boxes.size();
        bc0 = new int[count];
        bc1 = new int[count];
        br0 = new int[count];
        br1 = new int[count];
        rims = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            cells(i);
            cover(i, 1);
        }
        for (int i = 0; i < count; i++) rims.add(rim(i));
        regionsStale = true;
    }

    /** Box {@code i}'s cells, from where it stands now. */
    private void cells(final int i) {
        final Box b = boxes.get(i);
        bc0[i] = colAfter(b.x);
        bc1[i] = colBefore(b.x + b.w);
        br0[i] = rowAfter(b.y);
        br1[i] = rowBefore(b.y + b.h);
    }

    /** Puts box {@code i} on its cells ({@code sign} 1) or takes it off them (-1). */
    private void cover(final int i, final int sign) {
        for (int r = Math.max(0, br0[i]); r <= Math.min(rows - 1, br1[i]); r++) {
            for (int c = Math.max(0, bc0[i]); c <= Math.min(cols - 1, bc1[i]); c++) cover[r * cols + c] += sign;
        }
    }

    /** Whether every moved box, with room round it, is still on the grid. */
    private boolean fits(final List<Box> next, final boolean[] moved) {
        final int room = PAD + 2 * cell;
        for (int i = 0; i < moved.length; i++) {
            if (!moved[i]) continue;
            final Box b = next.get(i);
            if (b.x - room < ox || b.y - room < oy
                || b.x + b.w + room > ox + (cols - 1) * cell
                || b.y + b.h + room > oy + (rows - 1) * cell) return false;
        }
        return true;
    }

    /**
     * A bigger grid, over the old one and the boxes where they are now, with every route kept moved onto it; false
     * when the board grew so far the cell had to grow (then everything routes afresh).
     */
    private boolean regrid(final List<Box> next) {
        final int oldOx = ox, oldOy = oy, oldCols = cols, oldRows = rows, oldCell = cell;
        grid(next, new int[] { oldOx, oldOy, oldOx + (oldCols - 1) * oldCell, oldOy + (oldRows - 1) * oldCell });
        if (cell != oldCell) return false;
        final int dx = (oldOx - ox) / cell, dy = (oldOy - oy) / cell;
        for (int i = 0; i < routes.length; i++) {
            final Route r = routes[i];
            if (r == null) continue;
            final int[] moved = new int[r.cells.length];
            for (int k = 0; k < moved.length; k++) moved[k] = shift(r.cells[k], oldCols, dx, dy);
            routes[i] = new Route(
                moved,
                r.headings,
                r.cost,
                shift(r.source, oldCols, dx, dy),
                shift(r.target, oldCols, dx, dy));
            occupy(routes[i], 1);
        }
        return true;
    }

    private int shift(final int idx, final int oldCols, final int dx, final int dy) {
        return (idx / oldCols + dy) * cols + idx % oldCols + dx;
    }

    private Dock shift(final Dock d, final int oldCols, final int dx, final int dy) {
        return new Dock(shift(d.cell, oldCols, dx, dy), d.heading, d.x, d.y, d.round, d.cost);
    }

    /** A box's cells with its two rings, as c0 r0 c1 r1. */
    private int[] area(final Box b) {
        return new int[] { colAfter(b.x) - 2, rowAfter(b.y) - 2, colBefore(b.x + b.w) + 2, rowBefore(b.y + b.h) + 2 };
    }

    /** Whether a route runs through any of the areas. */
    private boolean runsIn(final Route r, final List<int[]> areas) {
        for (final int idx : r.cells) {
            final int x = idx % cols, y = idx / cols;
            for (final int[] a : areas) if (x >= a[0] && x <= a[2] && y >= a[1] && y <= a[3]) return true;
        }
        return false;
    }

    /** The first column whose middle is past {@code x}, and the last whose middle is short of it. */
    private int colAfter(final int x) {
        return Math.floorDiv(x - ox - cell / 2, cell) + 1;
    }

    private int colBefore(final int x) {
        return -Math.floorDiv(-(x - ox - cell / 2), cell) - 1;
    }

    private int rowAfter(final int y) {
        return Math.floorDiv(y - oy - cell / 2, cell) + 1;
    }

    private int rowBefore(final int y) {
        return -Math.floorDiv(-(y - oy - cell / 2), cell) - 1;
    }

    private int midX(final int c) {
        return ox + c * cell + cell / 2;
    }

    private int midY(final int r) {
        return oy + r * cell + cell / 2;
    }

    /** The first (or last) row whose middle is at or past (or at or short of) {@code y}. */
    private int rowAt(final int y, final boolean first) {
        return first ? -Math.floorDiv(-(y - oy - cell / 2), cell) : Math.floorDiv(y - oy - cell / 2, cell);
    }

    private int colAt(final int x, final boolean first) {
        return first ? -Math.floorDiv(-(x - ox - cell / 2), cell) : Math.floorDiv(x - ox - cell / 2, cell);
    }

    /**
     * Every place a wire may meet a box, clockwise from its top-left corner: the lane cell outside each side at every
     * column (or row) clear of the corners, with the point on the edge it meets. Lane cells inside another box are
     * kept (a wire walled in may still need them) and costed when used.
     */
    private List<Dock> rim(final int box) {
        final Box b = boxes.get(box);
        final List<Dock> out = new ArrayList<>();
        final int c0 = bc0[box], c1 = bc1[box], r0 = br0[box], r1 = br1[box];
        // Top, left to right.
        for (int c = Math.max(c0, colAt(b.x + KEEP, true)); c <= Math.min(c1, colAt(b.x + b.w - KEEP, false)); c++) {
            add(out, c, r0 - 1, 6, midX(c), b.y, midX(c) - b.x);
        }
        // Right, top to bottom.
        for (int r = Math.max(r0, rowAt(b.y + KEEP, true)); r <= Math.min(r1, rowAt(b.y + b.h - KEEP, false)); r++) {
            add(out, c1 + 1, r, 0, b.x + b.w, midY(r), b.w + midY(r) - b.y);
        }
        // Bottom, right to left.
        for (int c = Math.min(c1, colAt(b.x + b.w - KEEP, false)); c >= Math.max(c0, colAt(b.x + KEEP, true)); c--) {
            add(out, c, r1 + 1, 2, midX(c), b.y + b.h, b.w + b.h + b.x + b.w - midX(c));
        }
        // Left, bottom to top.
        for (int r = Math.min(r1, rowAt(b.y + b.h - KEEP, false)); r >= Math.max(r0, rowAt(b.y + KEEP, true)); r--) {
            add(out, c0 - 1, r, 4, b.x, midY(r), 2 * b.w + b.h + b.y + b.h - midY(r));
        }
        return out;
    }

    private void add(final List<Dock> out, final int c, final int r, final int heading, final int x, final int y,
        final int round) {
        if (c < 0 || c >= cols || r < 0 || r >= rows) return;
        out.add(new Dock(r * cols + c, heading, x, y, round, 0));
    }

    /** Works out the open cells' regions again if a box has moved since. */
    private void regions() {
        if (!regionsStale) return;
        regionsStale = false;
        if (rowStart.length < rows + 1) rowStart = new int[rows + 1];
        int runs = 0;
        for (int y = 0; y < rows; y++) {
            rowStart[y] = runs;
            final int base = y * cols;
            int x = 0;
            while (x < cols) {
                while (x < cols && cover[base + x] > 0) x++;
                if (x == cols) break;
                final int s = x;
                while (x < cols && cover[base + x] == 0) x++;
                if (runs == runX0.length) {
                    runX0 = Arrays.copyOf(runX0, Math.max(1024, runs * 2));
                    runX1 = Arrays.copyOf(runX1, runX0.length);
                }
                runX0[runs] = s;
                runX1[runs] = x - 1;
                runs++;
            }
        }
        rowStart[rows] = runs;
        if (runRoot.length < runs) runRoot = new int[runX0.length];
        for (int i = 0; i < runs; i++) runRoot[i] = i;
        for (int y = 1; y < rows; y++) {
            int a = rowStart[y - 1], b = rowStart[y];
            final int aEnd = rowStart[y], bEnd = rowStart[y + 1];
            while (a < aEnd && b < bEnd) {
                if (runX1[a] >= runX0[b] && runX1[b] >= runX0[a]) join(a, b);
                if (runX1[a] < runX1[b]) a++;
                else b++;
            }
        }
    }

    private int root(int run) {
        while (runRoot[run] != run) {
            runRoot[run] = runRoot[runRoot[run]];
            run = runRoot[run];
        }
        return run;
    }

    private void join(final int a, final int b) {
        final int ra = root(a), rb = root(b);
        if (ra != rb) runRoot[Math.max(ra, rb)] = Math.min(ra, rb);
    }

    /** An open cell's region (the root of its run); -1 for a covered cell. */
    private int region(final int idx) {
        if (cover[idx] > 0) return -1;
        final int y = idx / cols, x = idx % cols;
        int lo = rowStart[y], hi = rowStart[y + 1] - 1;
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            if (runX1[mid] < x) lo = mid + 1;
            else if (runX0[mid] > x) hi = mid - 1;
            else return root(mid);
        }
        return -1;
    }

    // endregion

    // region Planning where wires meet boxes

    /**
     * Where each wire's two ends should meet their boxes, before any route: per box, every end's ideal place (on the
     * side facing the other box, across from it), then the box's ends matched to places round its edge in that order,
     * apart where there is room, so they fan out without crossing. Two wires between the same two boxes are ordered
     * oppositely at the two, so they run side by side. Returns, per wire, its planned source and target dock.
     */
    private Dock[][] plan(final List<Wire> wires) {
        final Dock[][] plan = new Dock[wires.size()][2];
        // Every end at each box: {wire, 0 source / 1 target}.
        final List<List<int[]>> ends = new ArrayList<>();
        for (int b = 0; b < boxes.size(); b++) ends.add(new ArrayList<>());
        for (int i = 0; i < wires.size(); i++) {
            ends.get(wires.get(i).from)
                .add(new int[] { i, 0 });
            ends.get(wires.get(i).to)
                .add(new int[] { i, 1 });
        }
        for (int b = 0; b < boxes.size(); b++) {
            final List<int[]> at = ends.get(b);
            if (at.isEmpty()) continue;
            final List<Dock> rim = rims.get(b);
            if (rim.isEmpty()) continue;
            final int perimeter = 2 * (boxes.get(b).w + boxes.get(b).h);
            // Each end's ideal place round the box, and a tiebreak that mirrors pairs between the same two boxes.
            final int[] ideal = new int[at.size()];
            final long[] tie = new long[at.size()];
            for (int e = 0; e < at.size(); e++) {
                final Wire w = wires.get(at.get(e)[0]);
                final boolean source = at.get(e)[1] == 0;
                final int other = source ? w.to : w.from, sides = source ? w.fromSides : w.toSides;
                ideal[e] = ideal(b, other, sides);
                final long key = w.key.getMostSignificantBits() ^ w.key.getLeastSignificantBits();
                tie[e] = b <= other ? key : -key;
            }
            final Integer[] order = new Integer[at.size()];
            for (int e = 0; e < order.length; e++) order[e] = e;
            Arrays.sort(
                order,
                Comparator.<Integer>comparingInt(e -> ideal[e])
                    .thenComparingLong(e -> tie[e]));
            final int[] chosen = match(rim, order, ideal, perimeter);
            for (int k = 0; k < order.length; k++) {
                final int[] end = at.get(order[k]);
                plan[end[0]][end[1]] = rim.get(chosen[k]);
            }
        }
        return plan;
    }

    /**
     * An end's ideal place round box {@code b} (as {@link Dock#round}), toward box {@code other}: on the side across
     * the wider gap between them, level with the other box's middle as near as the side allows; a loop back to the same
     * box (or two boxes overlapping) in the middle of the first allowed side its other box lies toward.
     */
    private int ideal(final int b, final int other, final int sides) {
        final Box a = boxes.get(b), o = boxes.get(other);
        final int acx = a.x + a.w / 2, acy = a.y + a.h / 2, ocx = o.x + o.w / 2, ocy = o.y + o.h / 2;
        final int gapX = Math.max(o.x - (a.x + a.w), a.x - (o.x + o.w));
        final int gapY = Math.max(o.y - (a.y + a.h), a.y - (o.y + o.h));
        int side;
        if (b == other) side = -1;
        else if (gapY > gapX && gapY > 0) side = ocy > acy ? 2 : 0;
        else if (gapX > 0 || Math.abs(ocx - acx) * a.h >= Math.abs(ocy - acy) * a.w) side = ocx >= acx ? 1 : 3;
        else side = ocy > acy ? 2 : 0;
        if (side < 0 || (sides & SIDE_BITS[side]) == 0) {
            // The allowed side facing the other box best.
            int best = -1;
            long bestDot = Long.MIN_VALUE;
            for (int s = 0; s < 4; s++) {
                if ((sides & SIDE_BITS[s]) == 0) continue;
                final long dot = (long) DX[OUTWARD[s]] * (ocx - acx) + (long) DY[OUTWARD[s]] * (ocy - acy);
                if (dot > bestDot) {
                    bestDot = dot;
                    best = s;
                }
            }
            side = best < 0 ? 1 : best;
            if (b == other) return round(a, side, side == 0 || side == 2 ? acx : acy);
        }
        final int along = side == 0 || side == 2 ? Math.clamp(ocx, a.x + KEEP, a.x + a.w - KEEP)
            : Math.clamp(ocy, a.y + KEEP, a.y + a.h - KEEP);
        return round(a, side, along);
    }

    /** A place on side {@code side} (0 top, 1 right, 2 bottom, 3 left) at {@code along}, as distance round the box. */
    private static int round(final Box a, final int side, final int along) {
        return switch (side) {
            case 0 -> along - a.x;
            case 1 -> a.w + along - a.y;
            case 2 -> a.w + a.h + a.x + a.w - along;
            default -> 2 * a.w + a.h + a.y + a.h - along;
        };
    }

    /**
     * Ends (in {@code order}, by ideal place) matched to rim places in the same order, a spacing apart where the rim
     * has room, as near their ideals as can be: a monotone matching by dynamic programming. Returns rim indexes.
     */
    private static int[] match(final List<Dock> rim, final Integer[] order, final int[] ideal, final int perimeter) {
        final int e = order.length, d = rim.size();
        int gap = PLAN_SPACING;
        while (gap > 1 && (long) (e - 1) * gap + 1 > d) gap--;
        final int[] out = new int[e];
        if ((long) (e - 1) * gap + 1 > d) {
            // More ends than places: each to its nearest.
            for (int k = 0; k < e; k++) out[k] = nearest(rim, ideal[order[k]]);
            return out;
        }
        final long[][] best = new long[e][d];
        final int[][] from = new int[e][d];
        for (int k = 0; k < e; k++) {
            final int want = ideal[order[k]];
            long run = Long.MAX_VALUE;
            int runAt = -1;
            for (int j = 0; j < d; j++) {
                // The best placement of the ends before this one, at least a spacing earlier.
                if (k > 0 && j - gap >= 0 && best[k - 1][j - gap] < run) {
                    run = best[k - 1][j - gap];
                    runAt = j - gap;
                }
                final long cost = Math.abs(rim.get(j).round - want);
                if (k == 0) {
                    best[k][j] = cost;
                    from[k][j] = -1;
                } else if (runAt < 0) best[k][j] = Long.MAX_VALUE;
                else {
                    best[k][j] = run + cost;
                    from[k][j] = runAt;
                }
            }
        }
        int at = 0;
        for (int j = 1; j < d; j++) if (best[e - 1][j] < best[e - 1][at]) at = j;
        for (int k = e - 1; k >= 0; k--) {
            out[k] = at;
            at = from[k][at];
        }
        return out;
    }

    private static int nearest(final List<Dock> rim, final int round) {
        int best = 0;
        for (int j = 1; j < rim.size(); j++)
            if (Math.abs(rim.get(j).round - round) < Math.abs(rim.get(best).round - round)) best = j;
        return best;
    }

    /**
     * Where a wire may meet its box this time: near its planned place, cheapest nearest it ({@code planned} given), or
     * anywhere on its allowed sides, cheapest at each side's middle; another wire's spot costs more, and a spot inside
     * another box only when {@code through}.
     */
    private List<Dock> docks(final int box, final int sides, final boolean arriving, final Dock planned,
        final boolean through) {
        final Box b = boxes.get(box);
        final List<Dock> out = new ArrayList<>();
        final int perimeter = 2 * (b.w + b.h);
        for (final Dock d : rims.get(box)) {
            if ((sides & bitOf(d.heading)) == 0) continue;
            final boolean covered = cover[d.cell] > 0;
            if (covered && !through) continue;
            final int cost;
            if (planned != null) {
                int off = Math.abs(d.round - planned.round);
                off = Math.min(off, perimeter - off);
                if (off > PLAN_REACH * cell) continue;
                cost = off * PLAN_PX;
            } else {
                final boolean level = d.heading == 0 || d.heading == 4;
                cost = Math.abs(level ? d.y - (b.y + b.h / 2) : d.x - (b.x + b.w / 2)) * DOCK_PX;
            }
            out.add(
                new Dock(
                    d.cell,
                    arriving ? d.heading ^ 4 : d.heading,
                    d.x,
                    d.y,
                    d.round,
                    cost + (dockUse[d.cell] > 0 ? DOCK_TAKEN : 0) + (covered ? THROUGH : 0)));
        }
        return out;
    }

    private static int bitOf(final int heading) {
        return switch (heading) {
            case 0 -> RIGHT;
            case 2 -> BOTTOM;
            case 4 -> LEFT;
            default -> TOP;
        };
    }

    // endregion

    // region Occupancy

    /** Puts a routed wire on the board ({@code sign} 1) or takes it off again (-1). */
    private void occupy(final Route route, final int sign) {
        dockUse[route.source.cell] += sign;
        dockUse[route.target.cell] += sign;
        for (int k = 0; k < route.cells.length; k++) {
            final int idx = route.cells[k], line = LINE[route.headings[k]];
            lines[idx * 4 + line] += sign;
            if (lines[idx * 4 + line] > 0) mask[idx] |= (byte) (1 << line);
            else mask[idx] &= (byte) ~(1 << line);
            final int x = idx % cols, y = idx / cols;
            if (x > 0) near[idx - 1] += sign;
            if (x < cols - 1) near[idx + 1] += sign;
            if (y > 0) near[idx - cols] += sign;
            if (y < rows - 1) near[idx + cols] += sign;
        }
    }

    /** Cells where a wire crosses another or runs along one: what the rip-up pass works down. */
    private int conflicts(final Route route) {
        int n = 0;
        for (int k = 0; k < route.cells.length; k++) {
            if (mask[route.cells[k]] != 0) n++;
            if (k == 0) continue;
            final int a = route.cells[k - 1], b = route.cells[k];
            if (cornerCross(a % cols, a / cols, b % cols, b / cols, route.headings[k])) n++;
        }
        return n;
    }

    /**
     * A diagonal step over another wire's opposite diagonal: the two cross at the cells' shared corner without ever
     * sharing a cell, so the cell alone would miss it.
     */
    private boolean cornerCross(final int x, final int y, final int nx, final int ny, final int dir) {
        if ((dir & 1) == 0) return false;
        final int other = 1 << 5 - LINE[dir];
        return (mask[y * cols + nx] & other) != 0 && (mask[ny * cols + x] & other) != 0;
    }

    // endregion

    // region Search

    /**
     * A wire's route: from near its planned places, in a window round them; if that crosses another wire or finds
     * nothing, from anywhere on its sides in a wider window, then a wider one again; the cheaper wins. A wire walled
     * apart from where it goes is routed through boxes, as lightly as it can.
     */
    private Route search(final Wire w, final Dock plannedSource, final Dock plannedTarget, final boolean wide) {
        Route best = null;
        if (plannedSource != null && plannedTarget != null) {
            best = search(
                w,
                docks(w.from, w.fromSides, false, plannedSource, false),
                docks(w.to, w.toSides, true, plannedTarget, false),
                WINDOW,
                false,
                NEAR_WEIGHT);
        }
        if (best != null && (conflicts(best) == 0 || !wide || statPops > widenBudget)) return best;
        // Walled apart: no open region holds both a place to leave from and one to arrive at.
        if (best == null && sealed(w)) {
            statSealed++;
            final long before = statPops;
            final Route r = search(
                w,
                docks(w.from, w.fromSides, false, null, true),
                docks(w.to, w.toSides, true, null, true),
                WIDE_WINDOW,
                true,
                WIDE_WEIGHT);
            statSealedPops += statPops - before;
            if (r == null) statFailed++;
            return r;
        }
        statWidened++;
        final List<Dock> sources = docks(w.from, w.fromSides, false, null, false),
            targets = docks(w.to, w.toSides, true, null, false);
        Route r = search(w, sources, targets, WIDE_WINDOW, false, WIDE_WEIGHT);
        if (r == null) r = search(w, sources, targets, FAR_WINDOW, false, WIDE_WEIGHT);
        if (r != null && (best == null || conflicts(r) < conflicts(best)
            || conflicts(r) == conflicts(best) && r.cost < best.cost)) best = r;
        if (best == null) statFailed++;
        return best;
    }

    /** Whether no open region holds both a place the wire may leave from and one it may arrive at. */
    private boolean sealed(final Wire w) {
        regions();
        final BitSet from = new BitSet();
        for (final Dock d : rims.get(w.from)) {
            if ((w.fromSides & bitOf(d.heading)) == 0) continue;
            final int r = region(d.cell);
            if (r >= 0) from.set(r);
        }
        for (final Dock d : rims.get(w.to)) {
            if ((w.toSides & bitOf(d.heading)) == 0) continue;
            final int r = region(d.cell);
            if (r >= 0 && from.get(r)) return false;
        }
        return true;
    }

    /**
     * A* from every source dock at once (each leaving its side square, starting at its cost) to whichever target dock
     * is cheapest to arrive at (square into its side, plus its cost), within a window round the docks ({@code margin}
     * cells, less if that would be too many). Goals are priced as they are reached, and the search stops once nothing
     * still open could beat the best. {@code through}: boxes are passable at a price.
     */
    private Route search(final Wire w, final List<Dock> sources, final List<Dock> targets, final int margin,
        final boolean through, final int weight) {
        if (sources.isEmpty() || targets.isEmpty()) return null;
        int ax0 = Integer.MAX_VALUE, ay0 = Integer.MAX_VALUE, ax1 = Integer.MIN_VALUE, ay1 = Integer.MIN_VALUE;
        for (final List<Dock> docks : List.of(sources, targets)) {
            for (final Dock d : docks) {
                final int x = d.cell % cols, y = d.cell / cols;
                ax0 = Math.min(ax0, x);
                ay0 = Math.min(ay0, y);
                ax1 = Math.max(ax1, x);
                ay1 = Math.max(ay1, y);
            }
        }
        int m = margin, x0, y0, x1, y1;
        while (true) {
            x0 = Math.max(0, ax0 - m);
            y0 = Math.max(0, ay0 - m);
            x1 = Math.min(cols - 1, ax1 + m);
            y1 = Math.min(rows - 1, ay1 + m);
            if ((long) (x1 - x0 + 3) * (y1 - y0 + 3) <= MAX_WINDOW_CELLS) break;
            if (m == 0) return null;
            m /= 2;
        }
        wx0 = x0;
        wy0 = y0;
        lw = x1 - x0 + 3;
        lh = y1 - y0 + 3;
        final int cells = lw * lh;
        ensure(cells);
        for (int k = 0; k < 8; k++) dl[k] = DY[k] * lw + DX[k];
        // Diagonals once the boxes stand far enough apart; close by, square corners read better.
        final Box a = boxes.get(w.from), b = boxes.get(w.to);
        final int gapX = Math.max(b.x - (a.x + a.w), a.x - (b.x + b.w));
        final int gapY = Math.max(b.y - (a.y + a.h), a.y - (b.y + b.h));
        final boolean diagonals = w.from != w.to && Math.max(gapX, gapY) >= DIAGONAL_MIN_GAP;
        window(w, through, x0, y0, x1, y1);

        // Arrivals: on a target dock's lane cell, heading into the box; the cheaper dock where two share a cell.
        Arrays.fill(goalHeading, 0, cells, (byte) -1);
        for (int t = 0; t < targets.size(); t++) {
            final Dock d = targets.get(t);
            final int l = local(d.cell);
            if (goalHeading[l] >= 0 && goalExtra[l] <= d.cost) continue;
            goalHeading[l] = (byte) d.heading;
            goalExtra[l] = d.cost;
            goalDock[l] = t;
        }
        if (!field(sources, targets, diagonals, through)) return null;
        goalSides = 0;
        for (final Dock d : targets) {
            final int s = d.heading >> 1, x = d.cell % cols - wx0 + 1, y = d.cell / cols - wy0 + 1;
            final boolean level = (d.heading & 2) == 0;
            final int line = level ? x : y, along = level ? y : x;
            if ((goalSides & 1 << s) == 0) {
                goalSides |= 1 << s;
                goalLineMin[s] = goalLineMax[s] = line;
                goalLo[s] = goalHi[s] = along;
            } else {
                goalLineMin[s] = Math.min(goalLineMin[s], line);
                goalLineMax[s] = Math.max(goalLineMax[s], line);
                goalLo[s] = Math.min(goalLo[s], along);
                goalHi[s] = Math.max(goalHi[s], along);
            }
        }
        diagonalsOn = diagonals;
        turnMin = diagonals ? TURN45 : TURN90;

        statSearches++;
        statWindowCells += cells;
        statMaxWindow = Math.max(statMaxWindow, cells);
        Arrays.fill(g8, 0, cells << 3, Integer.MAX_VALUE);
        open.clear();
        for (final Dock d : sources) {
            final int lc = local(d.cell), l = (lc << 3) + d.heading;
            if (d.cost >= g8[l]) continue;
            final int h = estimate(lc, lc % lw, lc / lw, d.heading);
            if (h == UNREACHED) continue;
            g8[l] = d.cost;
            from8[l] = START;
            open.push(d.cost + weigh(h, weight), l, d.cost);
        }
        int bestState = -1, bestCost = Integer.MAX_VALUE, pops = 0;
        final int maxPops = Math.max(MIN_POPS, POPS_PER_CELL * cells);
        while (!open.isEmpty()) {
            if (++pops > maxPops) break;
            final int e = open.pop();
            // Nothing still open can arrive cheaper than the best arrival found.
            if (open.key(e) >= bestCost) break;
            final int l = open.value(e), g = open.aux(e);
            // A stale entry (the state was reached more cheaply since it was filed), or one already looked at: the
            // estimate never overestimates by more than the weight, so a state is looked at once.
            if (g != g8[l] || (from8[l] & CLOSED) != 0) continue;
            from8[l] |= CLOSED;
            final int lc = l >> 3, dir = l & 7, x = lc % lw, y = lc / lw;
            if (goalHeading[lc] == dir && g + goalExtra[lc] < bestCost) {
                bestCost = g + goalExtra[lc];
                bestState = l;
            }
            // Turning where another wire runs reads as a junction.
            final int junction = (wflag[lc] & LINES) != 0 ? JUNCTION : 0;
            for (int nd = 0; nd < 8; nd++) {
                final int turn = TURN[(dir << 3) + nd];
                if (turn < 0) continue;
                final boolean diag = (nd & 1) == 1;
                if (diag && !diagonals) continue;
                // After a turn: the whole minimum run at once.
                final int steps = nd == dir ? 1 : diag ? DIAGONAL_MIN_STEPS : STRAIGHT_MIN_STEPS;
                final int step = dl[nd], line = LINE[nd], across = DY[nd] * lw;
                int at = lc, ax = x, ay = y, cost = nd == dir ? 0 : turn + junction;
                boolean ok = true;
                for (int k = 0; k < steps; k++) {
                    final int next = at + step;
                    final int c = wcost[(next << 2) + line];
                    if (c < 0) {
                        ok = false;
                        break;
                    }
                    if (diag) {
                        // No cutting a box's corner; over another wire's opposite diagonal is a crossing.
                        final int side1 = wflag[at + DX[nd]], side2 = wflag[at + across];
                        if (!through && ((side1 | side2) & BOX) != 0) {
                            ok = false;
                            break;
                        }
                        if ((side1 & side2 & 1 << 5 - line) != 0) cost += CROSS;
                        cost += DIAG + c;
                    } else cost += STEP + c;
                    at = next;
                    ax += DX[nd];
                    ay += DY[nd];
                }
                if (!ok) continue;
                final int ng = g + cost, nl = (at << 3) + nd, old = g8[nl];
                if (ng >= old || old != Integer.MAX_VALUE && (from8[nl] & CLOSED) != 0) continue;
                final int h = estimate(at, ax, ay, nd);
                if (h == UNREACHED) continue;
                g8[nl] = ng;
                from8[nl] = (byte) (steps > 1 ? dir | RUN : dir);
                open.push(ng + weigh(h, weight), nl, ng);
            }
        }
        statPops += pops;
        if (trace != null) trace.accept(
            String.format(
                java.util.Locale.ROOT,
                "pops %8d  window %4dx%-4d margin %3d through %b weight %d  wire %d->%d  sources %d targets %d  cost %d  field0 %d  %s",
                pops,
                lw,
                lh,
                margin,
                through,
                weight,
                w.from,
                w.to,
                sources.size(),
                targets.size(),
                bestCost,
                minField(sources),
                bestState < 0 ? "" : breakdown(bestState)));
        if (bestState < 0) return null;
        return path(bestState, bestCost, sources, targets);
    }

    /** What a found route paid, by kind: steps (with cell costs), turns, junctions, corner crossings; turns count. */
    private String breakdown(final int arrival) {
        long steps = 0, turns = 0, junctions = 0, corners = 0, fieldAtStart = 0;
        int count = 0, cellsRun = 0;
        for (int s = arrival;;) {
            final int f = from8[s];
            final int c = s >> 3, d = s & 7;
            if ((f & START) != 0) {
                fieldAtStart = field[c];
                break;
            }
            final int run = run(f, d), prevDir = f & 7;
            final int prev = c - run * dl[d];
            for (int j = run - 1; j >= 0; j--) {
                final int at = prev + j * dl[d], next = at + dl[d];
                steps += ((d & 1) == 1 ? DIAG : STEP) + wcost[(next << 2) + LINE[d]];
                cellsRun++;
                if ((d & 1) == 1) {
                    final int side1 = wflag[at + DX[d]], side2 = wflag[at + DY[d] * lw];
                    if ((side1 & side2 & 1 << 5 - LINE[d]) != 0) corners += CROSS;
                }
            }
            if (prevDir != d) {
                turns += TURN[(prevDir << 3) + d];
                count++;
                if ((wflag[prev] & LINES) != 0) junctions += JUNCTION;
            }
            s = (prev << 3) + prevDir;
        }
        return String.format(
            java.util.Locale.ROOT,
            "| cells %d steps %d turns %d (%d) junctions %d corners %d fieldStart %d",
            cellsRun,
            steps,
            turns,
            count,
            junctions,
            corners,
            fieldAtStart);
    }

    private int minField(final List<Dock> sources) {
        int best = UNREACHED;
        for (final Dock d : sources)
            best = Math.min(best, field[local(d.cell)] == UNREACHED ? UNREACHED : field[local(d.cell)] + d.cost);
        return best;
    }

    /** The route back from an arrival: every cell (those a diagonal run jumped over too), and its two docks. */
    private Route path(final int arrival, final int cost, final List<Dock> sources, final List<Dock> targets) {
        int count = 0;
        for (int s = arrival;;) {
            final int f = from8[s];
            if ((f & START) != 0) {
                count++;
                break;
            }
            final int steps = run(f, s & 7);
            count += steps;
            s = ((s >> 3) - steps * dl[s & 7] << 3) + (f & 7);
        }
        final int[] idxs = new int[count], heads = new int[count];
        int k = count - 1;
        for (int s = arrival;;) {
            final int c = s >> 3, d = s & 7, f = from8[s];
            if ((f & START) != 0) {
                idxs[k] = global(c);
                heads[k] = d;
                break;
            }
            final int steps = run(f, s & 7);
            for (int j = 0; j < steps; j++, k--) {
                idxs[k] = global(c - j * dl[d]);
                heads[k] = d;
            }
            s = (c - steps * dl[d] << 3) + (f & 7);
        }
        Dock source = null;
        for (final Dock d : sources) if (d.cell == idxs[0] && d.heading == heads[0]) {
            if (source == null || d.cost < source.cost) source = d;
        }
        return new Route(idxs, heads, cost, source, targets.get(goalDock[arrival >> 3]));
    }

    /** How many cells the move into a state took: a whole run after a turn, else one. */
    private static int run(final int from, final int heading) {
        if ((from & RUN) == 0) return 1;
        return (heading & 1) == 1 ? DIAGONAL_MIN_STEPS : STRAIGHT_MIN_STEPS;
    }

    /** A grid cell's window cell, and back. */
    private int local(final int idx) {
        return (idx / cols - wy0 + 1) * lw + idx % cols - wx0 + 1;
    }

    private int global(final int lc) {
        return (lc / lw - 1 + wy0) * cols + lc % lw - 1 + wx0;
    }

    private static int weigh(final int h, final int weight) {
        return (int) ((long) h * weight / 100);
    }

    private void ensure(final int cells) {
        if (wflag.length >= cells) return;
        final int n = Math.max(cells, wflag.length * 3 / 2);
        wcost = new int[n * 4];
        wflag = new byte[n];
        field = new int[n];
        goalExtra = new int[n];
        goalDock = new int[n];
        goalHeading = new byte[n];
        g8 = new int[n * 8];
        from8 = new byte[n * 8];
    }

    /**
     * The window's costs for this wire: per cell and line, what entering it costs beyond the step (nearby wires, other
     * wires' lines, a box's cell when going through) or {@link #SHUT}; then the rings round every box near the window,
     * all but closed next to a box and a little dear one further out, except where the wire's own box's are crossed
     * square to its side. A shut border round it all keeps the search in.
     */
    private void window(final Wire w, final boolean through, final int x0, final int y0, final int x1, final int y1) {
        final int cells = lw * lh;
        Arrays.fill(wcost, 0, lw << 2, SHUT);
        Arrays.fill(wcost, cells - lw << 2, cells << 2, SHUT);
        Arrays.fill(wflag, 0, lw, (byte) 0);
        Arrays.fill(wflag, cells - lw, cells, (byte) 0);
        for (int r = 1; r < lh - 1; r++) {
            final int row = r * lw;
            Arrays.fill(wcost, row << 2, row + 1 << 2, SHUT);
            Arrays.fill(wcost, row + lw - 1 << 2, row + lw << 2, SHUT);
            wflag[row] = wflag[row + lw - 1] = 0;
            int idx = (y0 + r - 1) * cols + x0;
            for (int lc = row + 1, end = row + lw - 1; lc < end; lc++, idx++) {
                final int m = mask[idx];
                final boolean box = cover[idx] > 0;
                wflag[lc] = (byte) (box ? m | BOX : m);
                final int o = lc << 2;
                if (box && !through) {
                    wcost[o] = wcost[o + 1] = wcost[o + 2] = wcost[o + 3] = SHUT;
                    continue;
                }
                final int base = near[idx] * NEAR + (box ? THROUGH : 0);
                if (m == 0) wcost[o] = wcost[o + 1] = wcost[o + 2] = wcost[o + 3] = base;
                else {
                    final int t = m << 2;
                    wcost[o] = base + LINE_COST[t];
                    wcost[o + 1] = base + LINE_COST[t + 1];
                    wcost[o + 2] = base + LINE_COST[t + 2];
                    wcost[o + 3] = base + LINE_COST[t + 3];
                }
            }
        }
        for (int i = 0; i < boxes.size(); i++) {
            final int c0 = bc0[i], c1 = bc1[i], r0 = br0[i], r1 = br1[i];
            if (c0 > c1 || r0 > r1 || c1 + 2 < x0 || c0 - 2 > x1 || r1 + 2 < y0 || r0 - 2 > y1) continue;
            final boolean own = i == w.from || i == w.to;
            for (int r = Math.max(r0 - 2, y0); r <= Math.min(r1 + 2, y1); r++) {
                final int dr = Math.max(r0 - r, r - r1);
                final int row = (r - y0 + 1) * lw - x0 + 1;
                for (int c = Math.max(c0 - 2, x0); c <= Math.min(c1 + 2, x1); c++) {
                    final int dc = Math.max(c0 - c, c - c1);
                    if (dc <= 0 && dr <= 0) {
                        // Inside the box: on to its far side.
                        c = c1;
                        continue;
                    }
                    final int o = row + c << 2;
                    if (wcost[o] < 0) continue;
                    final int cost = Math.max(dc, dr) == 1 ? RING1 : RING2;
                    if (own && dr <= 0) {
                        // Beside its own box: level steps leave or arrive square.
                        wcost[o + 1] += cost;
                        wcost[o + 2] += cost;
                        wcost[o + 3] += cost;
                    } else if (own && dc <= 0) {
                        // Above or below it: upright steps do.
                        wcost[o] += cost;
                        wcost[o + 2] += cost;
                        wcost[o + 3] += cost;
                    } else {
                        wcost[o] += cost;
                        wcost[o + 1] += cost;
                        wcost[o + 2] += cost;
                        wcost[o + 3] += cost;
                    }
                }
            }
        }
    }

    /**
     * The least each window cell can cost to get from to an arrival (Dijkstra out from the arrivals, over the window's
     * costs): the steps, the boxes and their rings, other wires as crossed or run along. Turns and corners are left
     * out, so it never overestimates. An arrival is reached only heading into its box, so it starts from the cell
     * before each. Returns whether any source can arrive at all.
     */
    private boolean field(final List<Dock> sources, final List<Dock> targets, final boolean diagonals,
        final boolean through) {
        Arrays.fill(field, 0, lw * lh, UNREACHED);
        fieldOpen.clear();
        for (int t = 0; t < targets.size(); t++) {
            final Dock d = targets.get(t);
            final int g = local(d.cell);
            if (goalDock[g] != t) continue;
            final int p = g - dl[d.heading], c = wcost[(g << 2) + LINE[d.heading]];
            if (c < 0 || wcost[p << 2] < 0) continue;
            final int v = goalExtra[g] + STEP + c;
            if (v < field[p]) {
                field[p] = v;
                fieldOpen.push(v, p, 0);
            }
        }
        while (!fieldOpen.isEmpty()) {
            final int e = fieldOpen.pop();
            final int lc = fieldOpen.value(e), d = fieldOpen.key(e);
            if (d != field[lc]) continue;
            final int o = lc << 2;
            for (int k = 0; k < 8; k++) {
                final boolean diag = (k & 1) == 1;
                if (diag && !diagonals) continue;
                final int n = lc - dl[k];
                if (wcost[n << 2] < 0) continue;
                int nd = d + wcost[o + LINE[k]];
                if (diag) {
                    // As the search steps: never across a box's corner, and over another diagonal is a crossing.
                    final int side1 = wflag[n + DX[k]], side2 = wflag[n + DY[k] * lw];
                    if (!through && ((side1 | side2) & BOX) != 0) continue;
                    if ((side1 & side2 & 1 << 5 - LINE[k]) != 0) nd += CROSS;
                    nd += DIAG;
                } else nd += STEP;
                if (nd < field[n]) {
                    field[n] = nd;
                    fieldOpen.push(nd, n, 0);
                }
            }
        }
        for (int t = 0; t < targets.size(); t++) {
            final int g = local(targets.get(t).cell);
            if (goalDock[g] == t) field[g] = Math.min(field[g], goalExtra[g]);
        }
        for (final Dock d : sources) if (field[local(d.cell)] != UNREACHED) return true;
        return false;
    }

    /**
     * A lower bound on the way on from window cell {@code lc} (at x, y) entered heading {@code dir}: arriving here; or
     * on straight into the next cell and the cheapest way on from there; or a turn and the cheapest way on from here;
     * either way with the least turning that any way on still needs.
     */
    private int estimate(final int lc, final int x, final int y, final int dir) {
        final int h = goalHeading[lc] == dir ? goalExtra[lc] : UNREACHED, f = field[lc];
        if (f == UNREACHED) return h;
        final int bound = turning(x, y, dir);
        int best = f + Math.max(turnMin, bound);
        final int n = lc + dl[dir], c = wcost[(n << 2) + LINE[dir]];
        if (c >= 0 && field[n] != UNREACHED)
            best = Math.min(best, ((dir & 1) == 1 ? DIAG : STEP) + c + field[n] + bound);
        return Math.min(h, best);
    }

    /**
     * The least turning a way on from (x, y) heading {@code dir} must do to arrive: at least the angle between the
     * heading and the way it arrives, in 45-degree turns (right angles without diagonals); heading the way it arrives
     * but not in line with an arrival, a turn off and a turn back.
     */
    private int turning(final int x, final int y, final int dir) {
        int best = Integer.MAX_VALUE;
        for (int s = 0; s < 4; s++) {
            if ((goalSides & 1 << s) == 0) continue;
            final int diff = Math.abs(dir - (s << 1)), k = Math.min(diff, 8 - diff);
            final int t;
            if (k == 0) t = inLine(s, x, y) ? 0 : 2 * turnMin;
            else t = diagonalsOn ? k * TURN45 : (k + 1 >> 1) * TURN90;
            if (t < best) best = t;
        }
        return best == Integer.MAX_VALUE ? 0 : best;
    }

    /** Whether (x, y) lies on a line straight into an arrival made heading E, S, W or N ({@code s} 0 to 3). */
    private boolean inLine(final int s, final int x, final int y) {
        return switch (s) {
            case 0 -> y >= goalLo[0] && y <= goalHi[0] && x <= goalLineMax[0];
            case 1 -> x >= goalLo[1] && x <= goalHi[1] && y <= goalLineMax[1];
            case 2 -> y >= goalLo[2] && y <= goalHi[2] && x >= goalLineMin[2];
            default -> x >= goalLo[3] && x <= goalHi[3] && y >= goalLineMin[3];
        };
    }

    private static int[] turns() {
        final int[] t = new int[64];
        for (int from = 0; from < 8; from++) for (int to = 0; to < 8; to++) {
            final int d = Math.abs(from - to), steps = Math.min(d, 8 - d);
            t[from * 8 + to] = steps == 0 ? 0 : steps == 1 ? TURN45 : steps == 2 ? TURN90 : -1;
        }
        return t;
    }

    private static int[] lineCosts() {
        final int[] t = new int[64];
        for (int m = 0; m < 16; m++) for (int line = 0; line < 4; line++) {
            final int own = 1 << line, other = m & ~own;
            int c = (m & own) != 0 ? OVERLAP : 0;
            if (other != 0) c += CROSS + (Integer.bitCount(other) >= 2 ? JUNCTION : 0);
            t[(m << 2) + line] = c;
        }
        return t;
    }

    // endregion

    // region Output

    /** A route in world points: from its source dock square out, its corners, square into its target dock. */
    private List<int[]> toWorld(final Route route) {
        final List<int[]> pts = new ArrayList<>();
        pts.add(new int[] { route.source.x, route.source.y });
        final int n = route.cells.length;
        for (int k = 0; k < n; k++) {
            if (k == 0 || k == n - 1 || route.headings[k + 1] != route.headings[k]) {
                final int idx = route.cells[k];
                pts.add(new int[] { midX(idx % cols), midY(idx / cols) });
            }
        }
        pts.add(new int[] { route.target.x, route.target.y });
        return simplify(pts);
    }

    /** A wire the search could not serve: from the source's middle side nearest the target, square, to the target. */
    private List<int[]> fallback(final Wire w) {
        final Box a = boxes.get(w.from), b = boxes.get(w.to);
        final boolean right = b.x + b.w / 2 >= a.x + a.w / 2;
        final int sx = right ? a.x + a.w : a.x, sy = a.y + a.h / 2;
        final int tx = right ? b.x : b.x + b.w, ty = b.y + b.h / 2;
        final int mid = (sx + tx) / 2;
        final List<int[]> p = new ArrayList<>(4);
        p.add(new int[] { sx, sy });
        p.add(new int[] { mid, sy });
        p.add(new int[] { mid, ty });
        p.add(new int[] { tx, ty });
        return simplify(p);
    }

    /** Drops repeated points and points in the middle of a straight run, so only real corners remain. */
    static List<int[]> simplify(final List<int[]> pts) {
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

    // endregion

    /**
     * The search's open set (and the field's): a bucket queue. Costs are whole tenths of a pixel and no step adds more
     * than a few thousand, so entries are filed by cost in buckets a pixel wide on a ring that covers far more than the
     * span of costs open at once; adding and taking are then constant time and walk memory in order, where a binary
     * heap of millions of entries spent most of the router's time missing cache. Within a bucket the last added comes
     * out first (the state further along), so the order, and so every route, is the same on every run. Each entry is a
     * key (its cost), a value (a state) and an extra number (what reaching it cost, to tell stale entries).
     */
    private static final class Heap {

        /** A bucket's width in cost (tenths of a px), and how many buckets the ring has (a power of two). */
        private static final int WIDTH = 10, BUCKETS = 1 << 16, RING = BUCKETS - 1;

        private final int[] head = new int[BUCKETS];
        private int[] keys = new int[1 << 12], values = new int[1 << 12], extras = new int[1 << 12],
            next = new int[1 << 12];
        /** Entries used, live entries, and the bucket (by cost, not ring slot) being taken from. */
        private int used, size;
        private long at;
        private boolean started;

        Heap() {
            Arrays.fill(head, -1);
        }

        void clear() {
            // Only the buckets still holding entries need emptying; taking empties the rest as it goes.
            if (size > 0) Arrays.fill(head, -1);
            used = size = 0;
            started = false;
        }

        boolean isEmpty() {
            return size == 0;
        }

        void push(final int key, final int value, final int extra) {
            long bucket = key / WIDTH;
            if (!started) {
                if (size == 0 || bucket < at) at = bucket;
            } else if (bucket < at) bucket = at;
            // Past the ring (never in practice): filed at its far end, a little early.
            if (bucket - at >= BUCKETS) bucket = at + BUCKETS - 1;
            if (used == keys.length) {
                keys = Arrays.copyOf(keys, used * 2);
                values = Arrays.copyOf(values, used * 2);
                extras = Arrays.copyOf(extras, used * 2);
                next = Arrays.copyOf(next, used * 2);
            }
            final int slot = (int) (bucket & RING), e = used++;
            keys[e] = key;
            values[e] = value;
            extras[e] = extra;
            next[e] = head[slot];
            head[slot] = e;
            size++;
        }

        /** Takes the cheapest entry; returns its handle, for {@link #key}, {@link #value} and {@link #aux}. */
        int pop() {
            started = true;
            int slot = (int) (at & RING);
            while (head[slot] == -1) {
                at++;
                slot = (int) (at & RING);
            }
            final int e = head[slot];
            head[slot] = next[e];
            size--;
            return e;
        }

        int key(final int e) {
            return keys[e];
        }

        int value(final int e) {
            return values[e];
        }

        int aux(final int e) {
            return extras[e];
        }
    }
}
