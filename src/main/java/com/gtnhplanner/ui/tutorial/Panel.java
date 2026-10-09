package com.gtnhplanner.ui.tutorial;

import java.util.ArrayList;
import java.util.List;

import com.gtnhplanner.ui.note.NoteText;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * What the player works while the tour plays, with their own mouse: the caption bar (the chapter, a dot per beat, the
 * caption coming in, Back, Pause, Next, Chapters and Leave, a thin line for the time left on the caption), the chapter
 * list, and the end card. The bar keeps out of the way: at the bottom, or at the top when what the tour shows is down
 * there.
 */
final class Panel {

    private record Hit(Rect r, Runnable action, boolean enabled) {}

    private final Director d;
    private final Runnable leave;
    private final List<Hit> hits = new ArrayList<>();
    private float barY = Float.NaN;
    /** A note shown for a moment (a click outside the bar). */
    private String flash;
    private long flashAt;

    Panel(final Director d, final Runnable leave) {
        this.d = d;
        this.leave = leave;
    }

    void flash(final String text) {
        flash = text;
        flashAt = System.currentTimeMillis();
    }

    /** A click with the player's mouse: true when it hit something of the panel's. */
    boolean click(final float x, final float y) {
        for (final Hit h : hits) {
            if (h.r.contains(x, y)) {
                if (h.enabled) {
                    Hyb.click();
                    h.action.run();
                }
                return true;
            }
        }
        return false;
    }

    boolean over(final float x, final float y) {
        for (final Hit h : hits) if (h.r.contains(x, y)) return true;
        return false;
    }

    void draw(final int sw, final int sh, final float mx, final float my, final float dt) {
        hits.clear();
        switch (d.mode) {
            case MENU -> drawMenu(sw, sh, mx, my);
            case END -> drawEnd(sw, sh, mx, my);
            default -> drawBar(sw, sh, mx, my, dt);
        }
        if (flash != null) {
            final long age = System.currentTimeMillis() - flashAt;
            if (age > 2600) flash = null;
            else {
                final float a = Math.min(1, (2600 - age) / 300f);
                final int w = Hyb.width(flash) + 16;
                final float x = (sw - w) / 2f, y = sh / 2f - 30;
                Hyb.rect(x, y, w, 18, Math.round(0xE0 * a) << 24 | 0x141416);
                Hyb.ring(x, y, w, 18, 1, Math.round(0xFF * a) << 24 | 0xFFD257);
                Hyb.text(flash, x + 8, y + 5, 1f, Math.round(0xFF * a) << 24 | 0xE8E9EE, false);
            }
        }
    }

    // region The bar

    private void drawBar(final int sw, final int sh, final float mx, final float my, final float dt) {
        final int w = Math.min(470, sw - 24);
        final NoteText text = NoteText.of(d.caption(), w - 20, Hyb::width);
        final int lines = Math.min(
            3,
            text.lines()
                .size());
        final int h = 44 + lines * 11;
        final float x = (sw - w) / 2f;
        // At the bottom, unless what the tour points at (or its pointer) is down there.
        final float bottom = sh - h - 8, top = 26;
        final Rect atBottom = new Rect(x - 10, bottom - 10, w + 20, h + 20);
        final Rect lit = d.spotlight.current();
        final boolean clash = lit != null && lit.overlaps(atBottom)
            || Pointer.driving() && atBottom.contains(d.px(), d.py());
        final float want = clash && (lit == null || !lit.overlaps(new Rect(x, top - 10, w, h + 20))) ? top : bottom;
        if (Float.isNaN(barY)) barY = want;
        barY += (want - barY) * Math.min(1, dt / 110f);
        final float y = barY;

        Hyb.rect(x + 3, y + 4, w, h, 0x66000000);
        Hyb.rect(x - 1, y - 1, w + 2, h + 2, 0xFF0B0C0E);
        Hyb.rect(x, y, w, h, 0xF02A2C31);
        Hyb.rect(x, y, w, 1, 0x30FFFFFF);
        final Tour.Chapter ch = d.currentChapter();
        final String label = (d.chapter + 1) + " / " + d.chapters.size() + "   " + ch.title.toUpperCase();
        Hyb.text(label, x + 10, y + 7, 1f, 0xFF8E9099, false);
        // A dot a beat: gold for this one, light for those done.
        final int n = ch.beats.size();
        for (int i = 0; i < n; i++) {
            final float dx = x + w - 10 - (n - i) * 7;
            final int c = i == d.beat ? 0xFFFFD257 : i < d.beat ? 0xFFB9BBC2 : 0xFF4A4C54;
            Hyb.rect(dx, y + 9, 4, 4, c);
        }
        if (d.catchingUp()) {
            Hyb.text("Getting there...", x + 10, y + 22, 1f, Hyb.MUTED, false);
        } else {
            // The caption comes in a letter at a time, quickly.
            final int left = d.captionShown();
            for (int i = 0; i < lines; i++) {
                final NoteText.Line l = text.lines()
                    .get(i);
                final String shown = text.shown(i);
                final int visible = Math.max(0, Math.min(shown.length(), left - l.start()));
                if (visible > 0) Hyb.text(shown.substring(0, visible), x + 10, y + 21 + i * 11, 1f, Hyb.INK, false);
            }
        }
        // The keys.
        final float ky = y + h - 17;
        float kx = x + w - 10;
        kx = keyLeft("Leave", kx, ky, mx, my, true, leave);
        kx = keyLeft("Next  ▶", kx - 4, ky, mx, my, !d.catchingUp(), d::next);
        kx = keyLeft(d.paused ? "Play" : "Pause", kx - 4, ky, mx, my, !d.catchingUp(), d::togglePause);
        keyLeft("◀  Back", kx - 4, ky, mx, my, !d.catchingUp() && (d.chapter > 0 || d.beat > 0), d::back);
        key("Chapters", x + 10, ky, mx, my, true, d::menu);
        if (d.paused) Hyb.text("Paused", x + 10 + Hyb.width("Chapters") + 20, ky + 3, 1f, Hyb.GOLD, false);
        // The time left on the caption, along the bottom edge.
        final float p = d.holdProgress();
        if (p > 0 && !d.paused) Hyb.rect(x, y + h - 1, w * p, 1, 0xC0FFD257);
        hits.add(new Hit(new Rect(x, y, w, h), () -> {}, false));
    }

    // endregion

    // region The chapter list and the end card

    private void drawMenu(final int sw, final int sh, final float mx, final float my) {
        final int w = Math
            .max(330, Hyb.width("It plays itself, in a plan of its own. Your plans aren't touched.") + 26), row = 15,
            n = d.chapters.size();
        final int h = 62 + n * row + 30;
        final float x = (sw - w) / 2f, y = Math.max(8, (sh - h) / 2f);
        card(x, y, w, h);
        Hyb.text("GTNH Planner: the tour", x + 12, y + 10, 1.5f, Hyb.INK, false);
        Hyb.text(
            "It plays itself, in a plan of its own. Your plans aren't touched.",
            x + 12,
            y + 28,
            1f,
            Hyb.MUTED,
            false);
        Hyb.text("Start anywhere: pick a chapter.", x + 12, y + 39, 1f, Hyb.MUTED, false);
        float ry = y + 56;
        for (int i = 0; i < n; i++) {
            final int c = i;
            final Rect r = new Rect(x + 6, ry, w - 12, row);
            final boolean hot = r.contains(mx, my);
            if (hot) Hyb.rect(r.x(), r.y(), r.w(), r.h(), 0xFF3C3E45);
            Hyb.text((i + 1) + ".", x + 12, ry + 4, 1f, Hyb.MUTED, false);
            Hyb.text(d.chapters.get(i).title, x + 30, ry + 4, 1f, hot ? 0xFFFFD257 : Hyb.INK, false);
            if (d.seen.contains(i)) Hyb.textRight("✔", x + w - 12, ry + 4, 0xFF5EE9B5);
            hits.add(new Hit(r, () -> d.goTo(c, 0), true));
            ry += row;
        }
        final float ky = y + h - 22;
        float kx = x + w - 12;
        kx = keyLeft("Leave", kx, ky, mx, my, true, leave);
        keyLeft(d.seen.isEmpty() ? "Start  ▶" : "Start over", kx - 4, ky, mx, my, true, () -> d.goTo(0, 0));
        hits.add(new Hit(new Rect(x, y, w, h), () -> {}, false));
    }

    private void drawEnd(final int sw, final int sh, final float mx, final float my) {
        final int w = 340, h = 132;
        final float x = (sw - w) / 2f, y = (sh - h) / 2f;
        card(x, y, w, h);
        Hyb.text("That's the planner.", x + 12, y + 10, 1.5f, Hyb.INK, false);
        Hyb.text("Placing a plan in your world has its own video.", x + 12, y + 30, 1f, Hyb.MUTED, false);
        Hyb.text("Every control is under the ? key on the board.", x + 12, y + 41, 1f, Hyb.MUTED, false);
        Hyb.text("Keep the minimap as the tour set it up?", x + 12, y + 62, 1f, Hyb.INK, false);
        final float ty = y + 76;
        float tx = x + 12;
        tx = toggle("Keep it", tx, ty, mx, my, d.sandbox.keepMinimap, () -> d.sandbox.keepMinimap = true);
        toggle("Put it back", tx + 4, ty, mx, my, !d.sandbox.keepMinimap, () -> d.sandbox.keepMinimap = false);
        final float ky = y + h - 22;
        float kx = x + w - 12;
        kx = keyLeft("Done", kx, ky, mx, my, true, leave);
        kx = keyLeft("Chapters", kx - 4, ky, mx, my, true, d::menu);
        keyLeft("◀  Back", kx - 4, ky, mx, my, true, d::back);
        hits.add(new Hit(new Rect(x, y, w, h), () -> {}, false));
    }

    private static void card(final float x, final float y, final int w, final int h) {
        Hyb.rect(x + 4, y + 5, w, h, 0x70000000);
        Hyb.rect(x - 1, y - 1, w + 2, h + 2, 0xFF0B0C0E);
        Hyb.rect(x, y, w, h, 0xF52A2C31);
        Hyb.rect(x, y, w, 1, 0x30FFFFFF);
        Hyb.rect(x, y, w, 2, 0xC0FFD257);
    }

    // endregion

    // region Keys

    /** A key with its right edge at {@code right}; returns its left edge. */
    private float keyLeft(final String label, final float right, final float y, final float mx, final float my,
        final boolean enabled, final Runnable action) {
        final int w = Hyb.width(label) + 14;
        key(label, right - w, y, mx, my, enabled, action);
        return right - w;
    }

    private void key(final String label, final float x, final float y, final float mx, final float my,
        final boolean enabled, final Runnable action) {
        final int w = Hyb.width(label) + 14, h = 13;
        final Rect r = new Rect(x, y, w, h);
        final boolean hot = enabled && r.contains(mx, my);
        Hyb.rect(x, y, w, h, Hyb.KEY_EDGE);
        Hyb.rect(x + 1, y + 1, w - 2, h - 2, hot ? 0xFF4E5058 : 0xFF34363C);
        Hyb.rect(x + 1, y + 1, w - 2, 1, 0x26FFFFFF);
        Hyb.text(label, x + 7, y + 3, 1f, !enabled ? 0xFF5A5C65 : hot ? 0xFFFFD257 : Hyb.INK, false);
        hits.add(new Hit(r, action, enabled));
    }

    /** A choice of two: lit when chosen. */
    private float toggle(final String label, final float x, final float y, final float mx, final float my,
        final boolean on, final Runnable action) {
        final int w = Hyb.width(label) + 16, h = 14;
        final Rect r = new Rect(x, y, w, h);
        final boolean hot = r.contains(mx, my);
        Hyb.rect(x, y, w, h, on ? 0xFFFFD257 : Hyb.KEY_EDGE);
        Hyb.rect(x + 1, y + 1, w - 2, h - 2, on ? 0xFF4A4127 : hot ? 0xFF4E5058 : 0xFF34363C);
        Hyb.text(label, x + 8, y + 3, 1f, on ? 0xFFFFD257 : Hyb.INK, false);
        hits.add(new Hit(r, action, true));
        return x + w;
    }

    // endregion
}
