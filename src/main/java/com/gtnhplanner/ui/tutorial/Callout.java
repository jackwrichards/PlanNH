package com.gtnhplanner.ui.tutorial;

import java.util.ArrayList;
import java.util.List;

import com.gtnhplanner.ui.theme.Hyb;
import com.gtnhplanner.ui.tutorial.Targets.Target;

/**
 * The tour's only chrome: a box beside what a beat shows, pointing at it, saying what it is (the words that matter
 * most in gold: written between asterisks in the script), with three small keys
 * along its bottom: leave on the left, back and next on the right (next turns gold once the beat is done). It sits
 * below the thing when there is room, else above, to the right or to the left, off the tour's cursor, and follows it
 * as the board moves.
 */
final class Callout {

    private static final int TEXT_W = 200, PAD = 7, LINE = 11, KEY = 12, GAP = 7, TIP = 5, EDGE = 6;
    private static final int FILL = 0xFF222327, GOLD = 0xFFFFD257, HOT = 0xFF3C3E45, OFF = 0xFF55575F,
        ON_GOLD = 0xFF1A1B1F;

    private enum Side {
        BELOW,
        ABOVE,
        RIGHT,
        LEFT,
        /** On a target too big to sit beside: inside it, along its bottom. */
        INSIDE,
        /** Nothing to point at: the middle of the screen. */
        MIDDLE
    }

    private record Hit(Rect r, Runnable action, boolean enabled) {}

    private final Director d;
    private final Runnable leave;
    private final List<Hit> hits = new ArrayList<>();
    private Side side;
    private Rect box;

    Callout(final Director d, final Runnable leave) {
        this.d = d;
        this.leave = leave;
    }

    /** A click with the player's mouse: true when it landed on the callout. */
    boolean click(final float x, final float y) {
        for (final Hit h : hits) {
            if (!h.r.contains(x, y)) continue;
            if (h.enabled) {
                Hyb.click();
                h.action.run();
            }
            return true;
        }
        return box != null && box.contains(x, y);
    }

    void draw(final int sw, final int sh, final float mx, final float my) {
        hits.clear();
        final String text = d.noteText();
        if (text == null) {
            box = null;
            side = null;
            return;
        }
        final List<List<Word>> lay = layout(text);
        final int lines = lay.size();
        int tw = 0;
        for (final List<Word> line : lay) tw = Math.max(tw, width(line));
        final int w = Math.max(tw, 3 * KEY + 30) + 2 * PAD, h = PAD + lines * LINE + 4 + KEY + PAD - 2;
        final Target t = d.noteTarget();
        final Rect r = t == null ? null : t.rect();
        if (t != null && r == null) {
            // Its target not on screen (yet): nothing to point at, so wait.
            box = null;
            return;
        }
        final float[] at = place(r, w, h, sw, sh);
        // In over 170 ms: fading up, and sliding the last few pixels toward what it points at.
        final float age = Math.min(1, (System.currentTimeMillis() - d.noteAt()) / 170f);
        final float e = 1 - (1 - age) * (1 - age), slide = (1 - e) * 5;
        float x = at[0], y = at[1];
        switch (side) {
            case BELOW -> y += slide;
            case ABOVE -> y -= slide;
            case RIGHT -> x += slide;
            case LEFT -> x -= slide;
            default -> y += slide;
        }
        box = new Rect(x, y, w, h);
        Hyb.beginBatch();
        Hyb.rect(x + 2, y + 3, w, h, fade(0x70000000, e));
        Hyb.rect(x - 1, y - 1, w + 2, h + 2, fade(0xFF0B0C0E, e));
        Hyb.rect(x, y, w, h, fade(GOLD, e));
        Hyb.rect(x + 1, y + 1, w - 2, h - 2, fade(FILL, e));
        if (r != null) tip(r, x, y, w, h, e);
        Hyb.endBatch();
        for (int i = 0; i < lines; i++) {
            float wx = x + PAD;
            for (final Word word : lay.get(i)) {
                for (final Bit bit : word.bits()) {
                    Hyb.text(bit.text(), wx, y + PAD + i * LINE, 1f, fade(bit.gold() ? GOLD : Hyb.INK, e), false);
                    wx += Hyb.width(bit.text());
                }
                wx += SPACE;
            }
        }
        // The keys: leave on the left, back and next on the right.
        final float ky = y + h - PAD - KEY + 2;
        key(Glyph.LEAVE, x + PAD - 2, ky, mx, my, e, false, true, leave);
        final boolean done = d.waiting();
        key(Glyph.BACK, x + w - PAD + 2 - 2 * KEY - 3, ky, mx, my, e, false, !d.first() && !d.catchingUp(), d::back);
        key(
            d.last() ? Glyph.DONE : Glyph.NEXT,
            x + w - PAD + 2 - KEY,
            ky,
            mx,
            my,
            e,
            done,
            true,
            () -> { if (!d.next()) leave.run(); });
    }

    /**
     * Where the box goes: beside its target, on the side and at the place along it that covers least of what is on the
     * board (cards, drawers, notes, popups, the overview); below first, then above, right, left; and off the tour's
     * cursor. It stays where it was while that is nearly as good, so it does not flick between two places.
     */
    private float[] place(final Rect r, final int w, final int h, final int sw, final int sh) {
        if (r == null) {
            side = Side.MIDDLE;
            return new float[] { (sw - w) / 2f, sh * 0.42f - h / 2f };
        }
        final List<Rect> content = Targets.content();
        float[] best = null;
        Side bestSide = null;
        float bestScore = Float.MAX_VALUE, keptScore = Float.MAX_VALUE;
        float[] kept = null;
        final Side[] order = { Side.BELOW, Side.ABOVE, Side.RIGHT, Side.LEFT };
        for (int si = 0; si < order.length; si++) {
            final Side s = order[si];
            for (int k = 0; k < SLOTS; k++) {
                final float[] p = at(s, k, r, w, h, sw, sh);
                if (p == null) continue;
                final Rect b = new Rect(p[0], p[1], w, h);
                float score = si * 300 + p[2] * 2;
                for (final Rect c : content) score += overlap(b, c);
                if (score < bestScore) {
                    bestScore = score;
                    best = p;
                    bestSide = s;
                }
                if (s == side && box != null && Math.abs(p[0] - lastX) < 1.5f && Math.abs(p[1] - lastY) < 1.5f) {
                    kept = p;
                    keptScore = score;
                }
            }
        }
        if (kept != null && keptScore <= bestScore + 400) {
            best = kept;
            bestSide = side;
        }
        if (best == null) {
            side = Side.INSIDE;
            best = new float[] { clamp(r.cx() - w / 2f, EDGE, sw - w - EDGE),
                clamp(r.bottom() - h - 12, EDGE, sh - h - EDGE), 0 };
        } else side = bestSide;
        lastX = best[0];
        lastY = best[1];
        return best;
    }

    /** Places tried along each side: the middle, then spread from one end of the target to the other. */
    private static final int SLOTS = 9;

    private float lastX = Float.NaN, lastY = Float.NaN;

    /**
     * The box's corner on one side of its target at place {@code k} along it, and how far that is from the middle; null
     * when it does not fit there. Along the side, the box always keeps 16 px of its edge against the target, for the
     * tip.
     */
    private float[] at(final Side s, final int k, final Rect r, final int w, final int h, final int sw, final int sh) {
        final float off = GAP + TIP;
        final boolean across = s == Side.BELOW || s == Side.ABOVE;
        // Along the side: the middle first, then from one end to the other.
        final float mid = across ? r.cx() - w / 2f : r.cy() - h / 2f;
        final float lo = across ? r.x() + 16 - w : r.y() + 16 - h, hi = across ? r.right() - 16 : r.bottom() - 16;
        final float want = k == 0 ? mid : lo + (hi - lo) * (k - 1) / (SLOTS - 2f);
        final float along = across ? clamp(want, EDGE, sw - w - EDGE) : clamp(want, EDGE, sh - h - EDGE);
        final float x, y;
        switch (s) {
            case BELOW -> {
                x = along;
                y = r.bottom() + off;
                if (y + h > sh - EDGE) return null;
            }
            case ABOVE -> {
                x = along;
                y = r.y() - off - h;
                if (y < EDGE) return null;
            }
            case RIGHT -> {
                x = r.right() + off;
                y = along;
                if (x + w > sw - EDGE) return null;
            }
            default -> {
                x = r.x() - off - w;
                y = along;
                if (x < EDGE) return null;
            }
        }
        // Off the tour's cursor, which usually rests by what is shown.
        if (Pointer.driving() && new Rect(x - 4, y - 4, w + 8, h + 8).contains(d.px(), d.py())) return null;
        return new float[] { x, y, Math.abs(along - mid) };
    }

    private static float overlap(final Rect a, final Rect b) {
        final float w = Math.min(a.right(), b.right()) - Math.max(a.x(), b.x()),
            h = Math.min(a.bottom(), b.bottom()) - Math.max(a.y(), b.y());
        return w <= 0 || h <= 0 ? 0 : w * h;
    }

    /** The point on the box's edge facing its target: a small gold-edged triangle. */
    private void tip(final Rect r, final float x, final float y, final int w, final int h, final float e) {
        final float ax, ay, dx, dy;
        switch (side) {
            case BELOW -> {
                ax = clamp(r.cx(), x + 9, x + w - 9);
                ay = y;
                dx = 0;
                dy = -1;
            }
            case ABOVE -> {
                ax = clamp(r.cx(), x + 9, x + w - 9);
                ay = y + h;
                dx = 0;
                dy = 1;
            }
            case RIGHT -> {
                ax = x;
                ay = clamp(r.cy(), y + 9, y + h - 9);
                dx = -1;
                dy = 0;
            }
            case LEFT -> {
                ax = x + w;
                ay = clamp(r.cy(), y + 9, y + h - 9);
                dx = 1;
                dy = 0;
            }
            default -> {
                return;
            }
        }
        triangle(ax, ay, dx, dy, TIP + 1.5f, TIP + 1.5f, 0, fade(GOLD, e));
        triangle(ax, ay, dx, dy, TIP, TIP, 1, fade(FILL, e));
    }

    /** A triangle with its base along an edge at (ax, ay), set {@code in} back inside, its point out along (dx, dy). */
    private static void triangle(final float ax, final float ay, final float dx, final float dy, final float half,
        final float len, final float in, final int argb) {
        final float bx = ax - dx * in, by = ay - dy * in, px = -dy, py = dx;
        Hyb.triangle(
            bx + px * half,
            by + py * half,
            bx - px * half,
            by - py * half,
            ax + dx * len,
            ay + dy * len,
            argb);
        Hyb.triangle(
            bx - px * half,
            by - py * half,
            bx + px * half,
            by + py * half,
            ax + dx * len,
            ay + dy * len,
            argb);
    }

    // region The words

    /** A run of a word in one colour. */
    private record Bit(String text, boolean gold) {}

    private record Word(List<Bit> bits) {

        int width() {
            int w = 0;
            for (final Bit b : bits) w += Hyb.width(b.text());
            return w;
        }
    }

    private static final int SPACE = 4;

    /** The text in lines no wider than the box allows, each word in its runs of colour (gold between asterisks). */
    private static List<List<Word>> layout(final String text) {
        final List<List<Word>> lines = new ArrayList<>();
        List<Word> line = new ArrayList<>();
        int lineW = 0;
        boolean gold = false;
        for (final String raw : text.split(" ")) {
            if (raw.isEmpty()) continue;
            final List<Bit> bits = new ArrayList<>();
            final StringBuilder run = new StringBuilder();
            for (final char ch : raw.toCharArray()) {
                if (ch != '*') {
                    run.append(ch);
                    continue;
                }
                if (run.length() > 0) bits.add(new Bit(run.toString(), gold));
                run.setLength(0);
                gold = !gold;
            }
            if (run.length() > 0) bits.add(new Bit(run.toString(), gold));
            if (bits.isEmpty()) continue;
            final Word word = new Word(bits);
            final int w = word.width();
            if (!line.isEmpty() && lineW + SPACE + w > TEXT_W) {
                lines.add(line);
                line = new ArrayList<>();
                lineW = 0;
            }
            lineW += (line.isEmpty() ? 0 : SPACE) + w;
            line.add(word);
        }
        if (!line.isEmpty()) lines.add(line);
        return lines;
    }

    private static int width(final List<Word> line) {
        int w = 0;
        for (final Word word : line) w += word.width();
        return w + Math.max(0, line.size() - 1) * SPACE;
    }

    // endregion

    // region Keys

    private enum Glyph {
        BACK,
        NEXT,
        DONE,
        LEAVE
    }

    private void key(final Glyph g, final float x, final float y, final float mx, final float my, final float e,
        final boolean lit, final boolean enabled, final Runnable action) {
        final Rect r = new Rect(x, y, KEY, KEY);
        final boolean hot = enabled && r.contains(mx, my);
        if (lit) Hyb.rect(x, y, KEY, KEY, fade(hot ? 0xFFFFE08A : GOLD, e));
        else if (hot) Hyb.rect(x, y, KEY, KEY, fade(HOT, e));
        final int ink = fade(!enabled ? OFF : lit ? ON_GOLD : hot ? GOLD : Hyb.INK, e);
        glyph(g, x, y, ink);
        hits.add(new Hit(r.grow(1), action, enabled));
    }

    /** Drawn a pixel at a time: the game's font has no arrows or crosses at this size. */
    private static void glyph(final Glyph g, final float x, final float y, final int ink) {
        switch (g) {
            case NEXT -> {
                for (int i = 0; i < 4; i++) Hyb.rect(x + 4 + i, y + 2 + i, 1, 8 - 2 * i, ink);
            }
            case BACK -> {
                for (int i = 0; i < 4; i++) Hyb.rect(x + 7 - i, y + 2 + i, 1, 8 - 2 * i, ink);
            }
            case LEAVE -> {
                for (int i = 0; i < 6; i++) {
                    Hyb.rect(x + 3 + i, y + 3 + i, 1, 1, ink);
                    Hyb.rect(x + 8 - i, y + 3 + i, 1, 1, ink);
                }
            }
            case DONE -> {
                for (int i = 0; i < 3; i++) Hyb.rect(x + 2 + i, y + 5 + i, 1, 2, ink);
                for (int i = 0; i < 5; i++) Hyb.rect(x + 5 + i, y + 7 - i, 1, 2, ink);
            }
        }
    }

    // endregion

    private static float clamp(final float v, final float lo, final float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int fade(final int argb, final float a) {
        return Math.round((argb >>> 24) * a) << 24 | argb & 0xFFFFFF;
    }
}
