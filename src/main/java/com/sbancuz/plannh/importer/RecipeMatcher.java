package com.sbancuz.plannh.importer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import com.sbancuz.plannh.importer.FfPlan.FfRecipe;
import com.sbancuz.plannh.importer.FfPlan.FfSlot;
import com.sbancuz.plannh.importer.RecipeIndex.GameRecipe;
import com.sbancuz.plannh.importer.RecipeIndex.GameStack;

/**
 * Finds the in-game recipe an FF recipe is, by what it does (FF's recipe-ref-match.ts): recipe ids differ between
 * FF's dataset and the game, so the only identity a recipe has is what it takes, what it makes, how long it runs and
 * what it draws.
 *
 * <ul>
 * <li>{@link Grade#EXACT}: the same consumed inputs and outputs, amounts included, and the same ticks, EU/t and
 * special value. The same recipe.</li>
 * <li>{@link Grade#SAME_RESOURCES}: the same things in and out, but amounts or timing differ (a pack update, a
 * different machine's numbers). Taken, with a warning.</li>
 * <li>Anything less is refused and reported.</li>
 * </ul>
 * Kept inputs (a programmed circuit) are not compared, except that among equal candidates the one with the plan's
 * circuit wins. An FF slot's alternatives (ore dictionary variants, other solders) count as the slot. Item ids compare
 * ignoring case, and meta 32767 matches any meta.
 */
public final class RecipeMatcher {

    public enum Grade {
        EXACT,
        SAME_RESOURCES,
        NONE
    }

    /**
     * @param recipe     the pick, null when nothing was good enough
     * @param detail     for a fuzzy pick, what differs; for none, why
     * @param candidates how many in-game recipes were looked at
     */
    public record Match(@Nullable GameRecipe recipe, Grade grade, String detail, int candidates) {

        public boolean accepted() {
            return recipe != null && grade != Grade.NONE;
        }
    }

    /** One resource a recipe takes or makes, its slots merged: PlanNH shows one port per resource too. */
    private record Pile(String kind, List<String> ids, double amount, String name) {}

    private static final double EPS = 1e-6;

    private RecipeMatcher() {}

    /**
     * Picks the in-game recipe for {@code ref}. Ties go to the plan's circuit, then to the earliest candidate, so the
     * same plan always imports the same way.
     *
     * @param overrides the card's chosen alternatives by input slot (FF's recipeInputOverrides); a choice made there
     *                  is what the card really takes
     */
    public static Match match(final FfRecipe ref, final Map<Integer, FfSlot> overrides,
        final List<GameRecipe> candidates) {
        final List<Pile> refIn = refInputs(ref, overrides), refOut = merge(ref.outputs(), true);
        final Integer refCircuit = ref.circuit();
        // A whole recipe map can be thousands of recipes: only those making the plan's first output can match.
        final List<GameRecipe> making = new ArrayList<>();
        for (final GameRecipe c : candidates) if (refOut.isEmpty() || makes(c, refOut.getFirst())) making.add(c);
        GameRecipe best = null;
        Grade bestGrade = Grade.NONE;
        String bestDetail = "";
        int bestScore = -1;
        for (final GameRecipe c : making) {
            final List<Pile> in = gamePiles(c.inputs(), true), out = gamePiles(c.outputs(), false);
            final int[] inPairs = pair(refIn, in, false), outPairs = pair(refOut, out, false);
            if (inPairs == null || outPairs == null) continue;
            final boolean amounts = pair(refIn, in, true) != null && pair(refOut, out, true) != null;
            final List<String> timing = timingDiffs(ref, c);
            final Grade grade = amounts && timing.isEmpty() ? Grade.EXACT : Grade.SAME_RESOURCES;
            final int score = (grade == Grade.EXACT ? 100 : 50) + (Objects.equals(refCircuit, c.circuit()) ? 10 : 0)
                + (timing.isEmpty() ? 2 : 0)
                + (amounts ? 1 : 0);
            if (score <= bestScore) continue;
            bestScore = score;
            best = c;
            bestGrade = grade;
            if (grade == Grade.EXACT) {
                bestDetail = "";
            } else {
                final List<String> diffs = new ArrayList<>();
                if (!amounts) {
                    amountDiffs(refIn, in, inPairs, "takes", diffs);
                    amountDiffs(refOut, out, outPairs, "makes", diffs);
                }
                diffs.addAll(timing);
                bestDetail = String.join("; ", diffs);
            }
        }
        if (best != null) return new Match(best, bestGrade, bestDetail, candidates.size());
        final String why = making.isEmpty() && !candidates.isEmpty()
            ? "none of the " + candidates.size()
                + " in-game recipes makes "
                + refOut.getFirst()
                    .name()
            : rejection(refIn, refOut, making);
        return new Match(null, Grade.NONE, why, candidates.size());
    }

    private static boolean makes(final GameRecipe c, final Pile output) {
        for (final GameStack s : c.outputs()) if (s.kind()
            .equals(output.kind()) && FfIds.anySame(s.ids(), output.ids())) return true;
        return false;
    }

    // region Piles

    private static List<Pile> refInputs(final FfRecipe ref, final Map<Integer, FfSlot> overrides) {
        final List<FfSlot> effective = new ArrayList<>();
        for (int i = 0; i < ref.inputs()
            .size(); i++) {
            final FfSlot slot = ref.inputs()
                .get(i);
            if (!slot.consumed()) continue;
            final FfSlot chosen = overrides.get(i);
            if (chosen == null || !chosen.kind()
                .equals(slot.kind())) {
                effective.add(slot);
                continue;
            }
            // The pick leads; the slot's own id and alternatives still count as the slot.
            final List<String> alts = new ArrayList<>(slot.ids());
            alts.addAll(chosen.alternatives());
            effective.add(new FfSlot(chosen.kind(), chosen.id(), chosen.amount(), 1, true, alts, chosen.displayName()));
        }
        return merge(effective, true);
    }

    private static List<Pile> merge(final List<FfSlot> slots, final boolean consumedOnly) {
        final Map<String, Pile> piles = new LinkedHashMap<>();
        for (final FfSlot s : slots) {
            if (consumedOnly && !s.consumed()) continue;
            add(piles, s.kind(), s.ids(), s.amount(), s.displayName());
        }
        return new ArrayList<>(piles.values());
    }

    private static List<Pile> gamePiles(final List<GameStack> stacks, final boolean consumedOnly) {
        final Map<String, Pile> piles = new LinkedHashMap<>();
        for (final GameStack s : stacks) {
            if (consumedOnly && !s.consumed()) continue;
            if (s.ids()
                .isEmpty()) continue;
            add(piles, s.kind(), s.ids(), s.amount(), s.id());
        }
        return new ArrayList<>(piles.values());
    }

    private static void add(final Map<String, Pile> piles, final String kind, final List<String> ids,
        final double amount, final String name) {
        final String key = kind + ":" + FfIds.normalize(ids.getFirst());
        final Pile had = piles.get(key);
        if (had == null) {
            final List<String> normalized = new ArrayList<>();
            for (final String id : ids) normalized.add(FfIds.normalize(id));
            piles.put(key, new Pile(kind, normalized, amount, name));
            return;
        }
        final List<String> union = new ArrayList<>(had.ids());
        for (final String id : ids) if (!union.contains(FfIds.normalize(id))) union.add(FfIds.normalize(id));
        piles.put(key, new Pile(kind, union, had.amount() + amount, had.name()));
    }

    private static boolean compatible(final Pile a, final Pile b, final boolean amounts) {
        if (!a.kind()
            .equals(b.kind()) || !FfIds.anySame(a.ids(), b.ids())) return false;
        return !amounts || Math.abs(a.amount() - b.amount()) <= EPS * Math.max(1, Math.abs(a.amount()));
    }

    /**
     * A one-to-one pairing of the plan's piles with the game's (Kuhn's matching, sizes are tiny): for each plan pile
     * the index of its game pile, or null when the two sides are not the same set.
     */
    @Nullable
    private static int[] pair(final List<Pile> ref, final List<Pile> game, final boolean amounts) {
        if (ref.size() != game.size()) return null;
        final int[] gameToRef = new int[game.size()];
        Arrays.fill(gameToRef, -1);
        for (int r = 0; r < ref.size(); r++) {
            if (!augment(r, ref, game, amounts, gameToRef, new boolean[game.size()])) return null;
        }
        final int[] refToGame = new int[ref.size()];
        for (int g = 0; g < gameToRef.length; g++) refToGame[gameToRef[g]] = g;
        return refToGame;
    }

    private static boolean augment(final int r, final List<Pile> ref, final List<Pile> game, final boolean amounts,
        final int[] gameToRef, final boolean[] seen) {
        for (int g = 0; g < game.size(); g++) {
            if (seen[g] || !compatible(ref.get(r), game.get(g), amounts)) continue;
            seen[g] = true;
            if (gameToRef[g] < 0 || augment(gameToRef[g], ref, game, amounts, gameToRef, seen)) {
                gameToRef[g] = r;
                return true;
            }
        }
        return false;
    }

    // endregion

    // region Reporting

    private static List<String> timingDiffs(final FfRecipe ref, final GameRecipe c) {
        final List<String> diffs = new ArrayList<>();
        if (c.durationTicks() != null && c.durationTicks() != ref.durationTicks())
            diffs.add("runs " + c.durationTicks() + " ticks in game, " + ref.durationTicks() + " in the plan");
        if (c.euPerTick() != null && c.euPerTick() != Math.round(ref.eut()))
            diffs.add("draws " + c.euPerTick() + " EU/t in game, " + Math.round(ref.eut()) + " in the plan");
        if (c.specialValue() != null && ref.specialValue() != null
            && c.specialValue() != Math.round(ref.specialValue()))
            diffs.add(
                "special value " + c.specialValue() + " in game, " + Math.round(ref.specialValue()) + " in the plan");
        return diffs;
    }

    private static void amountDiffs(final List<Pile> ref, final List<Pile> game, final int[] pairs, final String verb,
        final List<String> out) {
        for (int r = 0; r < ref.size(); r++) {
            final Pile a = ref.get(r), b = game.get(pairs[r]);
            if (compatible(a, b, true)) continue;
            out.add(verb + " " + num(b.amount()) + " " + a.name() + " in game, " + num(a.amount()) + " in the plan");
        }
    }

    private static String rejection(final List<Pile> refIn, final List<Pile> refOut,
        final List<GameRecipe> candidates) {
        if (candidates.isEmpty()) return "";
        GameRecipe closest = null;
        double bestOverlap = -1;
        List<String> missing = List.of(), extra = List.of();
        for (final GameRecipe c : candidates) {
            final List<Pile> game = new ArrayList<>(gamePiles(c.inputs(), true));
            game.addAll(gamePiles(c.outputs(), false));
            final List<Pile> ref = new ArrayList<>(refIn);
            ref.addAll(refOut);
            final List<String> miss = new ArrayList<>(), more = new ArrayList<>();
            int shared = 0;
            for (final Pile r : ref) {
                if (game.stream()
                    .anyMatch(g -> compatible(r, g, false))) shared++;
                else miss.add(r.name());
            }
            for (final Pile g : game) if (ref.stream()
                .noneMatch(r -> compatible(r, g, false))) more.add(g.name());
            final double overlap = shared / (double) Math.max(1, ref.size() + more.size());
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                closest = c;
                missing = miss;
                extra = more;
            }
        }
        final StringBuilder why = new StringBuilder("none of the ").append(candidates.size())
            .append(candidates.size() == 1 ? " in-game recipe" : " in-game recipes")
            .append(
                refOut.isEmpty() ? ""
                    : " making " + refOut.getFirst()
                        .name())
            .append(" takes and makes the same things");
        if (closest != null) {
            why.append("; the closest (")
                .append(closest.handlerName())
                .append(")");
            if (!missing.isEmpty()) why.append(" lacks ")
                .append(first(missing));
            if (!missing.isEmpty() && !extra.isEmpty()) why.append(" and");
            if (!extra.isEmpty()) why.append(" has ")
                .append(first(extra));
        }
        return why.toString();
    }

    private static String first(final List<String> names) {
        final List<String> shown = names.subList(0, Math.min(3, names.size()));
        return String.join(", ", shown) + (names.size() > 3 ? " and " + (names.size() - 3) + " more" : "");
    }

    private static String num(final double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    // endregion
}
