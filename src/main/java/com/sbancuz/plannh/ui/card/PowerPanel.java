package com.sbancuz.plannh.ui.card;

import com.cleanroommc.modularui.drawable.GuiDraw;
import com.sbancuz.plannh.api.RecipePropertyAPI;
import com.sbancuz.plannh.data.MachineConfig;
import com.sbancuz.plannh.data.effect.EffectResult;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.data.provider.GTProvider;
import com.sbancuz.plannh.ui.popup.Tip;
import com.sbancuz.plannh.ui.theme.Fmt;
import com.sbancuz.plannh.ui.theme.Hyb;

/**
 * Factory Flow's POWER INPUT panel for a multiblock's card: what its amps at its tier supply, what that buys
 * (overclocks, time and runs, EU per run, draw), the next amp count that would make it faster and by how much, a
 * scale from nothing to that next step with what is supplied and drawn on it, and what the card asks of the grid. With
 * it, a guide to what the mouse does on the amps and tier chips. See {@code docs/design/ff-card-spec.md}.
 */
final class PowerPanel {

    static final int W = 262;
    static final int GUIDE_W = 164;
    private static final int PAD = 7, LINE = 10;
    /** The most amps the chip takes, as on the website (laser hatches go that far). */
    static final int MAX_AMPS = 1 << 24;

    /** One run at a given number of amps: ticks, EU/t for all its parallels, parallels. */
    record Run(int duration, long eut, int parallels) {

        double runsPerSecond() {
            return parallels * 20.0 / Math.max(1, duration);
        }
    }

    /** What the panel shows, worked out once per model. */
    record Facts(int amps, String tier, long volt, Run now, Integer nextAmps, Run next, int overclocks, int perfect,
        double machines) {}

    private PowerPanel() {}

    static Run runAt(final Node node, final int amps) {
        final MachineConfig cfg = node.machineConfig;
        final MachineConfig copy = new MachineConfig(node, cfg.getProfile());
        copy.settings.putAll(cfg.settings);
        copy.settings.put("amp", amps);
        final EffectResult e = copy.computeEffect(node.properties);
        return new Run(Math.max(1, e.durationTicks()), e.energyPerT(), Math.max(1, e.throughputFactor()));
    }

    private static boolean faster(final Run a, final Run b) {
        return a.runsPerSecond() > b.runsPerSecond() * 1.0001;
    }

    static Facts facts(final CardModel m) {
        final int t = CardDefaults.tierIndex(m.tier);
        final long volt = t < 0 ? 0 : 8L << (2 * t);
        final Run now = runAt(m.node, m.amps);
        // The next amp count that runs it faster (past the one-tick floor more amps only draw more): double until one
        // does, then halve the gap back.
        Integer nextAmps = null;
        Run next = null;
        int lo = m.amps, hi = m.amps;
        while (hi < MAX_AMPS) {
            hi = (int) Math.min(MAX_AMPS, hi * 2L);
            if (faster(runAt(m.node, hi), now)) break;
            lo = hi;
        }
        if (lo < hi) {
            final Run top = runAt(m.node, hi);
            if (faster(top, now)) {
                while (hi - lo > 1) {
                    final int mid = lo + (hi - lo) / 2;
                    if (!faster(runAt(m.node, mid), now)) lo = mid;
                    else hi = mid;
                }
                nextAmps = hi;
                next = runAt(m.node, hi);
            }
        }
        // How many overclocks: each one quadruples the draw; the ones that also quarter the time are perfect.
        int overclocks = 0, perfect = 0;
        if (m.node.properties.get(GTProvider.EU_PER_TICK) instanceof final Number base && base.longValue() > 0
            && m.node.properties.get(RecipePropertyAPI.DURATION_TICKS) instanceof final Number baseDur) {
            final double perRun = (double) now.eut() / (base.doubleValue() * now.parallels());
            overclocks = Math.max(0, (int) Math.round(Math.log(perRun) / Math.log(4)));
            final double faster = baseDur.doubleValue() / now.duration();
            final int halvings = Math.max(0, (int) Math.round(Math.log(faster) / Math.log(2)));
            perfect = Math.max(0, Math.min(overclocks, halvings - overclocks));
        }
        return new Facts(m.amps, m.tier, volt, now, nextAmps, next, overclocks, perfect, m.machines);
    }

    static int height(final Facts f) {
        return PAD + 12 + 14 + 4 + 34 + 5 + (f.nextAmps() != null ? 12 + 30 + 44 : 14) + 5 + 18 + PAD;
    }

    private static String amps(final double a) {
        return Fmt.compact(a) + "A";
    }

    private static String seconds(final int ticks) {
        return Fmt.compact(ticks / 20.0) + " s";
    }

    /** A tier in its colours, inline in a sentence: returns how wide it was. */
    private static int badge(final String tier, final float x, final float y) {
        final Hyb.Tier t = Hyb.tier(tier);
        final int w = Hyb.width(t.name()) + 4;
        Hyb.rect(x, y - 1, w, 10, t.border());
        Hyb.rect(x + 1, y, w - 2, 8, t.bg());
        GuiDraw.drawText(t.name(), x + 2.5f, y + 0.5f, 1f, t.border(), false);
        GuiDraw.drawText(t.name(), x + 2, y, 1f, t.text(), false);
        return w;
    }

    /** Text pieces and tier badges along a line; a piece starting with "@" is a tier. Returns the end x. */
    private static float sentence(float x, final float y, final int color, final String... pieces) {
        for (final String piece : pieces) {
            if (piece.startsWith("@")) x += badge(piece.substring(1), x, y) + 3;
            else {
                Hyb.text(piece, x, y, color);
                x += Hyb.width(piece) + 3;
            }
        }
        return x;
    }

    private static int sentenceWidth(final String... pieces) {
        int w = 0;
        for (final String piece : pieces)
            w += (piece.startsWith("@") ? Hyb.width(piece.substring(1)) + 4 : Hyb.width(piece)) + 3;
        return w - 3;
    }

    static void draw(final Facts f, final int x, final int y) {
        final int w = W, h = height(f), left = x + PAD, right = x + w - PAD, cw = right - left;
        Tip.beginPanel();
        Tip.chrome(x, y, w, h);
        int ty = y + PAD;
        Hyb.text("Power input", left, ty, Tip.TITLE);
        ty += 12;
        // 4A x [EV] = 8,192 EU/t ........ 2 [EV] hatches (2A each)
        sentence(
            left,
            ty,
            Tip.TITLE,
            amps(f.amps()) + " ×",
            "@" + f.tier(),
            "= " + Fmt.power(f.volt() * f.amps()) + " EU/t");
        final int hatchAmps = f.amps() == 1 ? 1 : 2;
        final String hatches = Fmt.compact((double) f.amps() / hatchAmps), each = "(" + hatchAmps + "A each)";
        final String noun = f.amps() <= hatchAmps ? "hatch" : "hatches";
        final int hw = sentenceWidth(hatches, "@" + f.tier(), noun) + 3 + Hyb.width(each);
        final float hx = sentence(right - hw, ty, Tip.TEXT, hatches, "@" + f.tier(), noun);
        Hyb.text(each, hx, ty, Tip.SUBTLE);
        ty += 14;
        // The stats, two columns between rules.
        Hyb.rect(left, ty, cw, 1, Tip.RULE);
        ty += 4;
        final Run now = f.now();
        final String[][] stats = { { "Overclocks", overclockText(f) },
            { "Parallels", Integer.toString(now.parallels()) }, { "Time / run", seconds(now.duration()) },
            { "Runs / second", Fmt.compact(now.runsPerSecond()) },
            { "EU / run", Fmt.power(now.eut() * now.duration()) }, { "Draw", Fmt.power(now.eut()) + " EU/t" } };
        final int colW = (cw - 12) / 2;
        for (int i = 0; i < stats.length; i++) {
            final int cx = left + (i % 2) * (colW + 12), cy = ty + (i / 2) * 11;
            Hyb.text(stats[i][0], cx, cy, Tip.SUBTLE);
            Hyb.textRight(stats[i][1], cx + colW, cy, Tip.TITLE);
        }
        ty += 3 * 11 + 1;
        Hyb.rect(left, ty, cw, 1, Tip.RULE);
        ty += 5;
        if (f.nextAmps() == null) {
            Hyb.text("Next improvement", left, ty, Tip.TITLE);
            Hyb.textRight("none: more amps buy nothing", right, ty, Tip.SUBTLE);
            ty += 14;
        } else {
            final Run next = f.next();
            Hyb.text("Next improvement", left, ty, Tip.TITLE);
            final String plus = "+" + amps(f.nextAmps() - f.amps());
            final int pw = sentenceWidth(plus, "@" + f.tier());
            sentence(right - pw, ty, Tip.TITLE, plus, "@" + f.tier());
            ty += 12;
            // What the step buys, before and after.
            final int boxW = (cw - 8) / 3;
            box(
                left,
                ty,
                boxW,
                "Output",
                "runs/s",
                Fmt.compact(now.runsPerSecond()),
                Fmt.compact(next.runsPerSecond()));
            box(left + boxW + 4, ty, boxW, "Time", "per run", seconds(now.duration()), seconds(next.duration()));
            box(
                left + 2 * (boxW + 4),
                ty,
                cw - 2 * (boxW + 4),
                "Draw",
                "EU/t",
                Fmt.power(now.eut()),
                Fmt.power(next.eut()));
            ty += 30;
            scale(f, left, ty, cw);
            ty += 44;
        }
        Hyb.rect(left, ty, cw, 1, Tip.RULE);
        ty += 5;
        Hyb.text("Demand for this card", left, ty, Tip.SUBTLE);
        Hyb.textRight(Fmt.power(now.eut() * f.machines()) + " EU/t", right, ty, Tip.TITLE);
        Hyb.text("Across the required machines.", left, ty + 10, Tip.SUBTLE);
        Tip.endPanel();
    }

    private static String overclockText(final Facts f) {
        if (f.overclocks() == 0) return "none";
        if (f.perfect() == 0) return Integer.toString(f.overclocks());
        return f.perfect() + " perfect, " + (f.overclocks() - f.perfect());
    }

    /** A before-and-after box: what it is, then "a -> b". */
    private static void box(final int x, final int y, final int w, final String label, final String unit,
        final String before, final String after) {
        Hyb.rect(x, y, w, 26, Tip.RULE);
        Hyb.rect(x + 1, y + 1, w - 2, 24, Hyb.SHADOW);
        final int lw = Hyb.width(label) + 3 + Hyb.width(unit);
        Hyb.text(label, x + (w - lw) / 2f, y + 3, Tip.SUBTLE);
        Hyb.text(unit, x + (w - lw) / 2f + Hyb.width(label) + 3, y + 3, 0xFF6F737C);
        final int bw = Hyb.width(before), aw = Hyb.width(after), total = bw + 4 + 7 + 4 + aw;
        final float vx = x + (w - total) / 2f;
        Hyb.text(before, vx, y + 14, Tip.TITLE);
        arrow(vx + bw + 4, y + 17);
        Hyb.text(after, vx + bw + 15, y + 14, Tip.TITLE);
    }

    private static void arrow(final float x, final float y) {
        Hyb.rect(x, y, 6, 1, Tip.SUBTLE);
        Hyb.rect(x + 4, y - 2, 1, 5, Tip.SUBTLE);
        Hyb.rect(x + 5, y - 1, 1, 3, Tip.SUBTLE);
        Hyb.rect(x + 6, y, 1, 1, Tip.SUBTLE);
    }

    /**
     * From nothing to the next step: the bar filled to what is supplied, ticks where the draw and the supply are, each
     * labelled; the label slides along its own width as the tick does, so it never leaves the bar's ends.
     */
    private static void scale(final Facts f, final int x, final int y, final int w) {
        final double next = f.nextAmps();
        final double supplied = f.amps(), draw = f.volt() > 0 ? (double) f.now()
            .eut() / f.volt() : 0;
        final float ps = (float) Math.min(1, supplied / next), pd = (float) Math.min(1, draw / next);
        Hyb.text("0A", x, y, Tip.SUBTLE);
        final String nextLabel = amps(next) + " next";
        Hyb.textRight(nextLabel, x + w, y, Tip.TITLE);
        final int barY = y + 22;
        label(amps(supplied) + " supplied", x, w, ps, y + 11, barY);
        // The bar: a dark well, filled to the supply.
        Hyb.rect(x, barY, w, 7, 0xFF111317);
        Hyb.rect(x + 1, barY + 1, w - 2, 5, Hyb.SHADOW);
        Hyb.rect(x + 1, barY + 1, (w - 2) * ps, 5, Hyb.MUTED);
        Hyb.rect(x + 1, barY + 1, (w - 2) * ps, 1, Hyb.HIGHLIGHT);
        tick(x + 1 + (w - 2) * ps, barY - 2, 11);
        tick(x + 1 + (w - 2) * pd, barY + 1, 8);
        tick(x + w - 2, barY - 2, 11);
        final String drawLabel = amps(draw) + " draw";
        final float dx = x + pd * w - pd * Hyb.width(drawLabel);
        Hyb.rect(x + 1 + (w - 2) * pd, barY + 8, 1, 3, Hyb.MUTED);
        Hyb.text(drawLabel, dx, barY + 12, Tip.TEXT);
    }

    private static void label(final String text, final int x, final int w, final float pct, final int y,
        final int barY) {
        final float lx = x + pct * w - pct * Hyb.width(text);
        Hyb.text(text, lx, y, Tip.TEXT);
        Hyb.rect(x + 1 + (w - 2) * pct, y + 9, 1, barY - y - 9, Hyb.MUTED);
    }

    /** An ink tick across the bar, outlined so it reads on the fill and off it. */
    private static void tick(final float x, final float y, final float h) {
        Hyb.rect(x - 2, y, 4, h, 0xFF111317);
        Hyb.rect(x - 1, y + 1, 2, h - 2, Hyb.INK);
    }

    /** What the mouse does on the amps and tier chips; the section for the chip under the mouse is marked. */
    static int guideHeight() {
        return PAD + 12 + 5 * LINE + 6 + 12 + 2 * LINE + PAD - 2;
    }

    static void drawGuide(final int x, final int y, final RecipeCard.Part on) {
        final int w = GUIDE_W, h = guideHeight(), left = x + PAD + 4, right = x + w - PAD;
        Tip.beginPanel();
        Tip.chrome(x, y, w, h);
        int ty = y + PAD;
        final String[][] ampRows = { { "Type amps", "Click" }, { "-1", "Right click" }, { "+1 / -1", "Wheel" },
            { "+10 / -10", "Ctrl+Wheel" }, { "×4 or ÷4", "Shift+Wheel" } };
        if (on == RecipeCard.Part.AMPS) Hyb.rect(x + PAD, ty, 1, 12 + ampRows.length * LINE - 2, Tip.SUBTLE);
        Hyb.text("Amps", left, ty, Tip.TITLE);
        ty += 12;
        for (final String[] r : ampRows) {
            Hyb.text(r[0], left, ty, Tip.SUBTLE);
            Hyb.textRight(r[1], right, ty, Tip.TITLE);
            ty += LINE;
        }
        ty += 6;
        final String[][] tierRows = { { "Up", "Click, wheel up" }, { "Down", "Right click, wheel down" } };
        if (on == RecipeCard.Part.TIER) Hyb.rect(x + PAD, ty, 1, 12 + tierRows.length * LINE - 2, Tip.SUBTLE);
        Hyb.text("Tier", left, ty, Tip.TITLE);
        ty += 12;
        for (final String[] r : tierRows) {
            Hyb.text(r[0], left, ty, Tip.SUBTLE);
            Hyb.textRight(r[1], right, ty, Tip.TITLE);
            ty += LINE;
        }
        Tip.endPanel();
    }
}
