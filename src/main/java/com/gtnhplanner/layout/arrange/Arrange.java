package com.gtnhplanner.layout.arrange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;

/**
 * Arrange, after Factory Flow's arrangeBoard: three candidate layouts (the plain column pass, the column pass with the
 * optimiser, and the free placement), each routed by the real router and scored in its points plus the air owed between
 * strangers; the best two are polished (cards on crossing wires tried elsewhere, a move kept when the routed board
 * scores better) and the better polished board wins. Deterministic for a deterministic judge. Runs for seconds on a big
 * board, so the board runs it in the background with a progress line.
 */
public final class Arrange {

    /** The real router's verdict on a layout: crossings, length, points, and which wires cross (by id). */
    public record Verdict(int crossings, double length, double points, List<String[]> events) {

        Verdict plus(final double extra) {
            return new Verdict(crossings, length, points + extra, events);
        }
    }

    /**
     * Routes the board's real wires with every card at the given top-left (cards absent stay where they are). A quick
     * verdict, given the {@code base} layout it differs from, may keep the base's routes for wires the change does not
     * touch; the final word is always a full one.
     */
    public interface Judge {

        Verdict judge(Map<String, Point> positions, boolean quick, Map<String, Point> base);

        /** A judge for another thread (a judge may keep state, so two threads each need their own). */
        default Judge fork() {
            return this;
        }
    }

    /** {@code judge}'s verdicts with the air at the layout added to their points. */
    private static Judge withAir(final Judge judge, final ToDoubleFunction<Map<String, Point>> air) {
        return new Judge() {

            @Override
            public Verdict judge(final Map<String, Point> positions, final boolean quick,
                final Map<String, Point> base) {
                return judge.judge(positions, quick, base)
                    .plus(air.applyAsDouble(positions));
            }

            @Override
            public Judge fork() {
                return withAir(judge.fork(), air);
            }
        };
    }

    /**
     * Where the arrange is, for a progress line: its step (0 to 5), what it is doing, and how far through that it is.
     */
    public record Progress(int step, String stage, int done, int total) {}

    /**
     * @param origin       where the layout's top-left lands (null: where the cards' top-left stands today)
     * @param spacing      "compact", "normal" or "roomy"
     * @param judge        the router's verdict (null: the proxy score picks, no polish)
     * @param polishBudget router questions each polish may ask (null: the prices')
     * @param prices       null for the defaults
     * @param onProgress   null for none
     * @param cancelled    checked between steps and trials (null: never)
     */
    public record Input(List<ArrangeCard> cards, List<ArrangeWire> wires, Point origin, String spacing, Judge judge,
        Integer polishBudget, Prices prices, Consumer<Progress> onProgress, BooleanSupplier cancelled) {}

    /** Every card's new top-left, the islands' rectangles, which candidate won, and its points (with air). */
    public record Result(Map<String, Point> positions, List<ColumnArrange.Island> islands, String chosen,
        double points) {}

    /** The arrange was called off. */
    public static final class Cancelled extends RuntimeException {

        public Cancelled() {
            super("arrange cancelled", null, false, false);
        }
    }

    /** Wall-clock allowance for one polish on top of its count of router questions: a big board's verdict is slow. */
    private static final long POLISH_MILLIS = 8_000;

    /** Prints how long the searches take (the benchmark sets it). */
    private static final boolean TIMES = Boolean.getBoolean("gtnhplanner.stages");

    private Arrange() {}

    public static Result arrange(final Input input) {
        final Prices prices = input.prices() == null ? Prices.DEFAULT : input.prices();
        // The stranger air is added to the judge's points here, so every stage (the search's proxy, the finalists, the
        // polish, the final choice) optimises the same thing and the judge never undoes the search.
        final ToDoubleFunction<Map<String, Point>> air = Air.term(input.cards(), input.wires(), prices.islandAir());
        final Judge judge = input.judge() == null ? null : withAir(input.judge(), air);
        progress(input, 0, "Laying the board out", 0, 1);
        final String spacing = input.spacing() == null ? "compact" : input.spacing();
        final ColumnArrange plainPass = new ColumnArrange(spacing, false, prices, air, null, null);
        final Result plain = of(plainPass.arrange(input.cards(), input.wires(), input.origin()), "plain");
        if (input.cards()
            .size() < 2) return plain;
        check(input);
        final ToDoubleFunction<Result> proxy = r -> Optimize
            .scoreLayoutProxy(input.cards(), input.wires(), r.positions(), prices, air);
        if (judge == null) {
            // No router at hand: the proxy chooses among the three, the way it chose among the challenger's trials.
            final Result challenger = of(
                new ColumnArrange(spacing, true, prices, air, null, null)
                    .arrange(input.cards(), input.wires(), input.origin()),
                "challenger");
            final Result free = free(input, prices, plainPass.rowGap(), spacing);
            Result best = plain;
            for (final Result r : List.of(challenger, free))
                if (proxy.applyAsDouble(r) < proxy.applyAsDouble(best)) best = r;
            return best;
        }
        progress(input, 1, "Searching for a better layout", 0, 1);
        // The challenger and the free placement search at once, on two threads; then the two polishes do.
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(1, r -> {
            final Thread t = new Thread(r, "GTNH Planner arrange");
            t.setDaemon(true);
            return t;
        });
        try {
            return judged(input, prices, air, judge, spacing, plainPass, plain, pool);
        } finally {
            pool.shutdownNow();
        }
    }

    private static Result judged(final Input input, final Prices prices, final ToDoubleFunction<Map<String, Point>> air,
        final Judge judge, final String spacing, final ColumnArrange plainPass, final Result plain,
        final java.util.concurrent.ExecutorService pool) {
        final long started = System.nanoTime();
        final java.util.concurrent.Future<Result> freeJob = pool.submit(() -> {
            final Result r = free(input, prices, plainPass.rowGap(), spacing);
            if (TIMES) System.out.printf("[arrange] free placement %.0f ms%n", (System.nanoTime() - started) / 1e6);
            return r;
        });
        final Result challenger = of(
            new ColumnArrange(
                spacing,
                true,
                prices,
                air,
                positions -> judge.judge(positions, false, null)
                    .points(),
                (done, total) -> {
                    check(input);
                    progress(input, 1, "Searching for a better layout", done, total);
                }).arrange(input.cards(), input.wires(), input.origin()),
            "challenger");
        if (TIMES) System.out.printf("[arrange] challenger %.0f ms%n", (System.nanoTime() - started) / 1e6);
        check(input);
        final Result free = await(freeJob);
        check(input);
        progress(input, 2, "Routing the candidates", 0, 3);
        // The two best by the router are polished: they start from different structures and the polish is greedy, so
        // each can reach a place the other cannot; the better finished board wins. Points alone decide: a crossing is
        // already priced into them.
        final List<Result> candidates = List.of(plain, challenger, free);
        final List<Verdict> verdicts = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            verdicts.add(
                judge.judge(
                    candidates.get(i)
                        .positions(),
                    false,
                    null));
            progress(input, 2, "Routing the candidates", i + 1, 3);
            check(input);
        }
        final List<Integer> ranked = new ArrayList<>(List.of(0, 1, 2));
        ranked.sort(
            (a, b) -> Double.compare(
                verdicts.get(a)
                    .points(),
                verdicts.get(b)
                    .points()));
        final Judge other = judge.fork();
        final java.util.concurrent.Future<Result> secondJob = pool
            .submit(() -> polish(input, other, prices, candidates.get(ranked.get(1)), verdicts.get(ranked.get(1)), 4));
        final Result first = polish(
            input,
            judge,
            prices,
            candidates.get(ranked.get(0)),
            verdicts.get(ranked.get(0)),
            3);
        final Result second = await(secondJob);
        progress(input, 5, "Choosing the better board", 1, 1);
        final double a = judge.judge(first.positions(), false, null)
            .points(),
            b = judge.judge(second.positions(), false, null)
                .points();
        return b < a ? withPoints(second, b) : withPoints(first, a);
    }

    /** A job's result, its failure rethrown as it was (a cancel stays a cancel). */
    private static <T> T await(final java.util.concurrent.Future<T> job) {
        try {
            return job.get();
        } catch (final InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            throw new Cancelled();
        } catch (final java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof final RuntimeException r) throw r;
            if (e.getCause() instanceof final Error r) throw r;
            throw new IllegalStateException(e.getCause());
        }
    }

    private static Result of(final ColumnArrange.Result r, final String name) {
        return new Result(r.positions(), r.islands(), name, Double.NaN);
    }

    private static Result withPoints(final Result r, final double points) {
        return new Result(r.positions(), r.islands(), r.chosen(), points);
    }

    private static void progress(final Input input, final int step, final String stage, final int done,
        final int total) {
        if (input.onProgress() != null) input.onProgress()
            .accept(new Progress(step, stage, done, total));
    }

    private static void check(final Input input) {
        if (input.cancelled() != null && input.cancelled()
            .getAsBoolean()) throw new Cancelled();
    }

    /** The free placement as a candidate: no columns at all. */
    private static Result free(final Input input, final Prices prices, final double rowGap, final String spacing) {
        final FreeArrange.Result placed = FreeArrange.arrange(
            input.cards(),
            input.wires(),
            new FreeArrange.Options(
                prices,
                // Per card, not per board: a trial is one card's move re-priced incrementally.
                Math.max(
                    prices.searchTrials(),
                    1500 * input.cards()
                        .size()),
                (int) Math.round(rowGap / Grid.CELL),
                "compact".equals(spacing) ? 2 : "roomy".equals(spacing) ? 5 : 3,
                (done, total) -> {
                    check(input);
                    progress(input, 1, "Placing freely", done, total);
                }));
        double ox = Double.POSITIVE_INFINITY, oy = Double.POSITIVE_INFINITY;
        if (input.origin() != null) {
            ox = input.origin()
                .x();
            oy = input.origin()
                .y();
        } else for (final ArrangeCard card : input.cards()) {
            ox = Math.min(ox, card.x());
            oy = Math.min(oy, card.y());
        }
        final Map<String, Point> positions = new LinkedHashMap<>();
        for (final ArrangeCard card : input.cards()) {
            final Point p = placed.positions()
                .getOrDefault(card.id(), new Point(0, 0));
            positions.put(card.id(), new Point(ox + p.x(), oy + p.y()));
        }
        final List<ColumnArrange.Island> islands = new ArrayList<>();
        for (final FreeArrange.Island island : placed.islands()) islands.add(
            new ColumnArrange.Island(
                ox + island.x(),
                oy + island.y(),
                island.width(),
                island.height(),
                island.backdrop()));
        return new Result(positions, islands, "free", Double.NaN);
    }

    /**
     * The polish: the router says where wires still cross (or, with none, which wires are longest), and the cards on
     * them are tried elsewhere: beside a partner on any side, level with it in their own column, or swapped with a card
     * in their column, the drawers serving only them riding along. A try that lowers the router's points is taken and
     * the search repeats until nothing helps or the budget runs out. Every try keeps the grid and keeps cards apart.
     */
    private static Result polish(final Input input, final Judge judge, final Prices prices, final Result result,
        final Verdict verdict, final int step) {
        final int fullBudget = input.polishBudget() != null ? input.polishBudget() : prices.polishBudget();
        final int[] budget = { fullBudget };
        final long started = System.currentTimeMillis(), deadline = started + POLISH_MILLIS;
        final Runnable spend = () -> {
            budget[0] = System.currentTimeMillis() > deadline ? 0 : budget[0] - 1;
            check(input);
            final double timeShare = fullBudget * (System.currentTimeMillis() - started) / (double) POLISH_MILLIS;
            progress(
                input,
                step,
                verdict.crossings() + " crossings to start",
                (int) Math.min(fullBudget, Math.max(fullBudget - budget[0], timeShare)),
                fullBudget);
        };
        progress(input, step, verdict.crossings() + " crossings to start", 0, fullBudget);
        final Map<String, ArrangeCard> cardById = new LinkedHashMap<>();
        for (final ArrangeCard card : input.cards()) cardById.put(card.id(), card);
        final Map<String, List<ArrangeWire>> wiresOf = new HashMap<>();
        for (final ArrangeWire wire : input.wires()) {
            wiresOf.computeIfAbsent(wire.source(), k -> new ArrayList<>())
                .add(wire);
            wiresOf.computeIfAbsent(wire.target(), k -> new ArrayList<>())
                .add(wire);
        }
        final Map<String, ArrangeWire> wireById = new HashMap<>();
        for (final ArrangeWire wire : input.wires()) if (wire.id() != null) wireById.put(wire.id(), wire);
        final Map<String, Point> positions = new LinkedHashMap<>(result.positions());
        // The search runs on quick verdicts against a fully judged base: a move is tried with only its own wires routed
        // again, and when it wins, the new layout gets the full verdict and becomes the base.
        Map<String, Point> base = new LinkedHashMap<>(positions);
        Verdict best = verdict;
        final Map<String, Point> start = new LinkedHashMap<>(positions);
        final double gap = Grid.CELL;
        final Set<String> tried = new HashSet<>();
        // A machine moves with the drawers that serve only it, the way a hand drags a machine and its buds together.
        final Map<String, List<String>> budsOf = new HashMap<>();
        for (final ArrangeCard card : input.cards()) {
            if (!card.storage()) continue;
            final Set<String> partners = new LinkedHashSet<>();
            for (final ArrangeWire wire : wiresOf.getOrDefault(card.id(), List.of())) partners.add(
                wire.source()
                    .equals(card.id()) ? wire.target() : wire.source());
            if (partners.size() != 1) continue;
            final String anchor = partners.iterator()
                .next();
            final ArrangeCard anchorCard = cardById.get(anchor);
            if (anchorCard != null && !anchorCard.storage()) budsOf.computeIfAbsent(anchor, k -> new ArrayList<>())
                .add(card.id());
        }
        for (int round = 0; round < 40 && budget[0] > 0; round++) {
            // Cards on crossing wires, the most crossed first; drawers before machines, being cheap to move.
            final Map<String, Integer> blame = new LinkedHashMap<>();
            final Map<String, Set<String>> crossingPartners = new HashMap<>();
            if (best.events() != null) for (final String[] event : best.events()) {
                for (final String edgeId : event) {
                    final ArrangeWire wire = wireById.get(edgeId);
                    if (wire == null) continue;
                    blame.merge(wire.source(), 1, Integer::sum);
                    blame.merge(wire.target(), 1, Integer::sum);
                    crossingPartners.computeIfAbsent(wire.source(), k -> new HashSet<>())
                        .add(wire.target());
                    crossingPartners.computeIfAbsent(wire.target(), k -> new HashSet<>())
                        .add(wire.source());
                }
            }
            // Nothing crosses: the cards on the longest wires are the suspects, every remaining point being wire.
            final Map<String, Double> span = new LinkedHashMap<>();
            if (blame.isEmpty()) {
                for (final ArrangeWire wire : input.wires()) {
                    final Point a = positions.get(wire.source()), b = positions.get(wire.target());
                    if (a == null || b == null) continue;
                    final double s = Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y());
                    span.merge(wire.source(), s, Math::max);
                    span.merge(wire.target(), s, Math::max);
                }
                if (span.isEmpty()) break;
            }
            final List<String> suspects = new ArrayList<>(blame.isEmpty() ? span.keySet() : blame.keySet());
            suspects.sort((a, b) -> {
                final double blameA = blame.isEmpty() ? span.get(a) : blame.get(a),
                    blameB = blame.isEmpty() ? span.get(b) : blame.get(b);
                if (blameA != blameB) return blameB > blameA ? 1 : -1;
                final int storageA = cardById.get(a)
                    .storage() ? 1 : 0,
                    storageB = cardById.get(b)
                        .storage() ? 1 : 0;
                if (storageA != storageB) return storageB - storageA;
                return a.compareTo(b);
            });
            boolean improved = false;
            for (final String id : suspects) {
                if (improved || budget[0] <= 0) break;
                final ArrangeCard size = cardById.get(id);
                final List<String> partners = new ArrayList<>();
                for (final ArrangeWire wire : wiresOf.getOrDefault(id, List.of())) {
                    final String partner = wire.source()
                        .equals(id) ? wire.target() : wire.source();
                    if (positions.containsKey(partner) && !partners.contains(partner)) partners.add(partner);
                }
                final Point own = positions.get(id);
                final List<String> group = groupOf(id, budsOf);
                final List<Point> candidates = new ArrayList<>();
                for (final String partner : partners) {
                    final Point at = positions.get(partner);
                    final ArrangeCard ps = cardById.get(partner);
                    final double[] rows = { at.y(), at.y() + ps.height() - size.height(),
                        at.y() + (ps.height() - size.height()) / 2 };
                    final double[] columns = { at.x(), at.x() + ps.width() - size.width(),
                        at.x() + (ps.width() - size.width()) / 2 };
                    for (final double y : rows) {
                        candidate(
                            candidates,
                            tried,
                            id,
                            at.x() - size.width() - 2 * gap,
                            y,
                            positions,
                            group,
                            cardById);
                        candidate(candidates, tried, id, at.x() + ps.width() + 2 * gap, y, positions, group, cardById);
                        // A slide in its own column, level with the partner.
                        candidate(candidates, tried, id, own.x(), y, positions, group, cardById);
                    }
                    for (final double x : columns) {
                        candidate(
                            candidates,
                            tried,
                            id,
                            x,
                            at.y() - size.height() - 2 * gap,
                            positions,
                            group,
                            cardById);
                        candidate(candidates, tried, id, x, at.y() + ps.height() + 2 * gap, positions, group, cardById);
                    }
                }
                // Nearest a partner first, the partners on crossing wires counting four times over: the smallest move
                // that helps is the one a hand would make.
                final Set<String> wanted = crossingPartners.get(id);
                candidates.sort(
                    (p, q) -> Double.compare(
                        distance(p, own, size, partners, positions, cardById, wanted),
                        distance(q, own, size, partners, positions, cardById, wanted)));
                for (int c = 0; c < Math.min(18, candidates.size()) && budget[0] > 0; c++) {
                    final Point candidate = candidates.get(c);
                    final Map<String, Point> saved = new HashMap<>();
                    for (final String member : group) saved.put(member, positions.get(member));
                    final double dx = candidate.x() - own.x(), dy = candidate.y() - own.y();
                    for (final String member : group) positions.put(
                        member,
                        new Point(
                            saved.get(member)
                                .x() + dx,
                            saved.get(member)
                                .y() + dy));
                    final Verdict quick = judge.judge(positions, true, base);
                    spend.run();
                    // Only a quick verdict at least two percent better earns a full one.
                    if (quick.points() < best.points() * 0.98) {
                        final Verdict next = judge.judge(positions, false, null);
                        if (next.points() < best.points() - 1) {
                            best = next;
                            base = new LinkedHashMap<>(positions);
                            improved = true;
                            break;
                        }
                    }
                    positions.putAll(saved);
                }
                if (improved || budget[0] <= 0) break;
                // Swaps: trade places with a card in the same column, the move that fixes a wire climbing past its
                // neighbours' wires. Each takes the other's top-left, buds riding along; both must fit.
                for (final Map.Entry<String, Point> e : new ArrayList<>(positions.entrySet())) {
                    if (budget[0] <= 0 || improved) break;
                    final String other = e.getKey();
                    final Point otherAt = e.getValue();
                    if (other.equals(id) || group.contains(other) || groupOf(other, budsOf).contains(id)) continue;
                    final ArrangeCard otherSize = cardById.get(other);
                    final boolean sameColumn = own.x() < otherAt.x() + otherSize.width()
                        && otherAt.x() < own.x() + size.width();
                    if (!sameColumn) continue;
                    final String key = (id.compareTo(other) < 0 ? id + "<>" + other : other + "<>" + id) + "@"
                        + own.x()
                        + ","
                        + own.y()
                        + "|"
                        + otherAt.x()
                        + ","
                        + otherAt.y();
                    if (!tried.add(key)) continue;
                    final List<String> otherGroup = groupOf(other, budsOf);
                    final List<String> both = new ArrayList<>(group);
                    both.addAll(otherGroup);
                    final Map<String, Point> saved = new HashMap<>();
                    for (final String member : both) saved.put(member, positions.get(member));
                    final double dxA = otherAt.x() - own.x(), dyA = otherAt.y() - own.y();
                    for (final String member : group) positions.put(
                        member,
                        new Point(
                            saved.get(member)
                                .x() + dxA,
                            saved.get(member)
                                .y() + dyA));
                    for (final String member : otherGroup) positions.put(
                        member,
                        new Point(
                            saved.get(member)
                                .x() - dxA,
                            saved.get(member)
                                .y() - dyA));
                    if (legal(both, positions, cardById, gap)) {
                        final Verdict quick = judge.judge(positions, true, base);
                        spend.run();
                        if (quick.points() < best.points() * 0.98) {
                            final Verdict next = judge.judge(positions, false, null);
                            if (next.points() < best.points() - 1) {
                                best = next;
                                base = new LinkedHashMap<>(positions);
                                improved = true;
                                break;
                            }
                        }
                    }
                    positions.putAll(saved);
                }
            }
            if (!improved) break;
        }
        // The full verdict decides: the polished layout replaces the starting one only if the router says it is better.
        final Verdict last = judge.judge(positions, false, null);
        return new Result(
            last.points() < verdict.points() ? positions : start,
            result.islands(),
            result.chosen(),
            Double.NaN);
    }

    private static List<String> groupOf(final String id, final Map<String, List<String>> budsOf) {
        final List<String> group = new ArrayList<>();
        group.add(id);
        group.addAll(budsOf.getOrDefault(id, List.of()));
        return group;
    }

    /** A place to try {@code id} at, snapped, unless tried already or it would land its group on another card. */
    private static void candidate(final List<Point> out, final Set<String> tried, final String id, final double x,
        final double y, final Map<String, Point> positions, final List<String> group,
        final Map<String, ArrangeCard> cardById) {
        final double sx = Grid.snap(x), sy = Grid.snap(y);
        final String key = id + "@" + sx + "," + sy;
        if (tried.contains(key)) return;
        final Point home = positions.get(id);
        if (overlaps(group, sx - home.x(), sy - home.y(), positions, cardById)) return;
        tried.add(key);
        out.add(new Point(sx, sy));
    }

    /** Whether the group, shifted, would come within a cell of any other card. */
    private static boolean overlaps(final List<String> group, final double dx, final double dy,
        final Map<String, Point> positions, final Map<String, ArrangeCard> cardById) {
        final double gap = Grid.CELL;
        for (final String member : group) {
            final Point at = positions.get(member);
            final ArrangeCard size = cardById.get(member);
            final double x = at.x() + dx, y = at.y() + dy;
            for (final Map.Entry<String, Point> e : positions.entrySet()) {
                if (group.contains(e.getKey())) continue;
                final ArrangeCard other = cardById.get(e.getKey());
                final Point o = e.getValue();
                if (x < o.x() + other.width() + gap && x + size.width() + gap > o.x()
                    && y < o.y() + other.height() + gap
                    && y + size.height() + gap > o.y()) return true;
            }
        }
        return false;
    }

    /** Whether none of {@code members} lands within a cell of any other card. */
    private static boolean legal(final List<String> members, final Map<String, Point> positions,
        final Map<String, ArrangeCard> cardById, final double gap) {
        for (final String member : members) {
            final Point at = positions.get(member);
            final ArrangeCard size = cardById.get(member);
            for (final Map.Entry<String, Point> e : positions.entrySet()) {
                if (e.getKey()
                    .equals(member)) continue;
                final ArrangeCard other = cardById.get(e.getKey());
                final Point o = e.getValue();
                if (at.x() < o.x() + other.width() + gap && at.x() + size.width() + gap > o.x()
                    && at.y() < o.y() + other.height() + gap
                    && at.y() + size.height() + gap > o.y()) return false;
            }
        }
        return true;
    }

    private static double distance(final Point p, final Point own, final ArrangeCard size, final List<String> partners,
        final Map<String, Point> positions, final Map<String, ArrangeCard> cardById, final Set<String> wanted) {
        double sum = 0;
        for (final String partner : partners) {
            final Point at = positions.get(partner);
            final ArrangeCard ps = cardById.get(partner);
            final double weight = wanted != null && wanted.contains(partner) ? 4 : 1;
            sum += weight * (Math.max(0, Math.max(at.x() - p.x() - size.width(), p.x() - at.x() - ps.width()))
                + Math.max(0, Math.max(at.y() - p.y() - size.height(), p.y() - at.y() - ps.height())));
        }
        return sum + Math.abs(p.x() - own.x()) * 0.1 + Math.abs(p.y() - own.y()) * 0.1;
    }
}
