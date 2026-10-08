package com.gtnhplanner.ui.world;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.drawable.GuiDraw;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * How the AR lens draws a machine, in the planner's look. A panel is as wide as its machine needs and never changes
 * size as its numbers do: the name (a small gold star when it is a plan card's machine) and tier; what its recipe
 * takes and makes in tiles either side of an arrow that fills with the recipe's progress, the rate of each under it
 * and the time left under the arrow; a graph of what it has made lately, with its average rate and total, and why it
 * is stopped across the graph when it is; and a footer with its state, its power against what it can take, and how
 * busy it has been. Far machines are a tile with a state light. Every colour takes the panel's fade.
 */
final class ArPanel {

    private ArPanel() {}

    /** A linked plan card: its name and plan, and how many of its machines are placed of how many the plan has. */
    record Tag(String card, String plan, int placed, int planned) {}

    /**
     * A machine to draw: what it is, what it is doing (null when unknown), its link, and what it has done lately
     * (null before it has been watched).
     */
    record View(String name, @Nullable ItemStack icon, @Nullable MachineStatus status, @Nullable Tag tag,
        @Nullable MachineStats.Track track) {}

    private static final int PAD = 7, SLOT = 20, COL = 40, MAX_SLOTS = 3, GRAPH_H = 30;
    /** The charts' points: each two minutes of the last hour. */
    private static final int POINTS = 30;
    private static final int GREEN = 0xFF5EE9B5, AMBER = 0xFFFBBF24;
    private static final int WELL = 0xFF17191D;

    /** The fade every colour is drawn at. */
    private static float alpha = 1;

    private static int a(final int argb) {
        return (Math.round((argb >>> 24) * alpha) & 0xFF) << 24 | argb & 0xFFFFFF;
    }

    private static void rect(final float x, final float y, final float w, final float h, final int argb) {
        Hyb.rect(x, y, w, h, a(argb));
    }

    private static void text(final String s, final float x, final float y, final int color) {
        if (alpha < 0.06f || s.isEmpty()) return;
        GuiDraw.drawText(s, x, y, 1, a(color), true);
    }

    private static void textRight(final String s, final float right, final float y, final int color) {
        text(s, right - Hyb.width(s), y, color);
    }

    private static void textCentred(final String s, final float cx, final float y, final int color) {
        text(s, Math.round(cx - Hyb.width(s) / 2f), y, color);
    }

    private static void icon(@Nullable final ItemStack item, @Nullable final FluidStack fluid, final float x,
        final float y, final float size) {
        // Items cannot fade: they come in once the panel is mostly there.
        if (alpha < 0.55f) return;
        Hyb.icon(item, fluid, x, y, size, 0);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    // region What it shows

    private static List<MachineStatus.Flow> ins(final View v) {
        return v.status == null ? List.of() : first(v.status.inputs());
    }

    private static List<MachineStatus.Flow> outs(final View v) {
        return v.status == null ? List.of() : first(v.status.outputs());
    }

    private static List<MachineStatus.Flow> first(final List<MachineStatus.Flow> all) {
        return all.subList(0, Math.min(MAX_SLOTS, all.size()));
    }

    /** Whether the machine's recipe is known: single player, where it has slots and history. */
    private static boolean known(final View v) {
        return !ins(v).isEmpty() || !outs(v).isEmpty();
    }

    private static boolean running(final View v) {
        return v.status != null && v.status.state() == MachineStatus.State.RUNNING;
    }

    private static String state(final View v) {
        if (v.status == null) return "Linked";
        return switch (v.status.state()) {
            case RUNNING -> "Running";
            case IDLE -> "Idle";
            case OFF -> "Off";
            case PROBLEM -> "Stopped";
        };
    }

    private static int light(final View v) {
        if (v.status == null) return Hyb.MUTED;
        return running(v) ? Hyb.PRODUCT_INK : v.status.state() == MachineStatus.State.PROBLEM ? Hyb.RED_INK : Hyb.MUTED;
    }

    /** Why it is not running, when it says; empty otherwise. */
    private static String reason(final View v) {
        return v.status == null || running(v) ? "" : v.status.detail();
    }

    // endregion

    // region Size: by the machine alone, never by its numbers

    private static final String[] STATES = { "Running", "Stopped", "Idle", "Off", "Linked" };

    private static int stateW() {
        int w = 0;
        for (final String s : STATES) w = Math.max(w, Hyb.width(s));
        return 9 + w;
    }

    /** The power figure at its widest for this machine: its most. */
    private static int powerW(final View v) {
        final long max = v.status == null ? 0 : Math.max(v.status.maxEuPerTick(), v.status.euPerTick());
        return 10 + Hyb.width(Fmt.power(Math.max(max, 9999)) + " EU/t");
    }

    private static int rowWidth(final View v) {
        return (ins(v).size() + outs(v).size()) * COL + COL;
    }

    static int width(final View v) {
        final String tier = v.status != null ? v.status.tier() : "";
        int w = Hyb.width(v.name) + (v.tag != null ? 11 : 0) + (tier.isEmpty() ? 0 : Hyb.width(tier) + 14);
        if (known(v)) w = Math.max(w, rowWidth(v));
        w = Math.max(w, stateW() + 10 + Hyb.width("999.9k made") + 10 + Hyb.width("100% busy"));
        if (known(v)) w = Math.max(w, Hyb.width("Power") + 10 + Hyb.width("last hour") + 10 + powerW(v) + 30);
        return Math.min(280, Math.max(200, w + 2 * PAD));
    }

    /** A chart: its title line, the chart, the gap after. */
    private static final int CHART = 11 + GRAPH_H + 6;
    private static final int HEAD = 6 + 10 + 5, ROW = SLOT + 2 + 9 + 6, HISTORY = 2 * CHART, FOOT = 9 + 7;

    static int height(final View v) {
        return HEAD + (known(v) ? ROW + HISTORY : 0) + FOOT;
    }

    // endregion

    // region Near: the panel

    /** The panel at the origin, at a fade; the machine looked at is ringed. */
    static void near(final View v, final float fade, final boolean lookedAt) {
        alpha = fade;
        final int w = width(v), h = height(v);
        // A faint shadow just under it.
        rect(1, 2, w, h, 0x38000000);
        if (lookedAt) rect(-2, -2, w + 4, h + 4, Hyb.SELECTION);
        rect(0, 0, w, h, Hyb.FRAME);
        rect(1, 1, w - 2, h - 2, 0xF21E2024);
        rect(1, 1, w - 2, 1, 0x26FFFFFF);
        float y = 6;
        head(v, w, y);
        y = HEAD;
        if (known(v)) {
            row(v, Math.round((w - rowWidth(v)) / 2f), y);
            y += ROW;
            history(v, w, y);
            y += HISTORY;
        }
        foot(v, w, y);
    }

    /** The name, the star of a plan card's machine, and the tier in its colours. */
    private static void head(final View v, final int w, final float y) {
        final String tier = v.status != null ? v.status.tier() : "";
        final int tierW = tier.isEmpty() ? 0 : Hyb.width(tier) + 6;
        final int room = w - 2 * PAD - tierW - 6 - (v.tag != null ? 11 : 0);
        final String name = Hyb.fit(v.name, room);
        text(name, PAD, y, Hyb.INK);
        if (v.tag != null) star(PAD + Hyb.width(name) + 4, y + 1);
        if (!tier.isEmpty()) {
            final Hyb.Tier t = Hyb.tier(tier);
            final float tx = w - PAD - tierW;
            rect(tx, y - 1, tierW, 10, t.border());
            rect(tx + 1, y, tierW - 2, 8, t.bg());
            text(tier, tx + 3, y, t.text());
        }
    }

    /** What goes in, the arrow filling with the recipe's progress, what comes out; rates and the time left under. */
    private static void row(final View v, final float x0, final float y) {
        float x = x0;
        final long now = MachineStats.now();
        final MachineStats.Track t = v.track;
        final double watched = t == null ? 0 : t.seen.lastHour(now);
        for (final MachineStatus.Flow f : ins(v)) {
            slot(x + (COL - SLOT) / 2f, y, f);
            textCentred(rate(t == null ? null : t.used, f, watched, now), x + COL / 2f, y + SLOT + 3, Hyb.MUTED);
            x += COL;
        }
        final MachineStatus st = v.status;
        arrow(x + 6, y + (SLOT - 9) / 2f, COL - 12, running(v) ? st.progress() : -1);
        if (running(v) && st.ticksLeft() > 0) textCentred(seconds(st.ticksLeft()), x + COL / 2f, y + SLOT + 3, Hyb.INK);
        x += COL;
        for (final MachineStatus.Flow f : outs(v)) {
            slot(x + (COL - SLOT) / 2f, y, f);
            textCentred(rate(t == null ? null : t.made, f, watched, now), x + COL / 2f, y + SLOT + 3, Hyb.MUTED);
            x += COL;
        }
    }

    /** A tile as the board's ports: the icon with its shadow, and what one cycle moves, as the game counts a stack. */
    private static void slot(final float x, final float y, final MachineStatus.Flow f) {
        rect(x, y, SLOT, SLOT, Hyb.TILE_EDGE);
        rect(x + 1, y + 1, SLOT - 2, SLOT - 2, Hyb.TILE);
        rect(x + 1, y + 1, SLOT - 2, 1, Hyb.TILE_HI);
        icon(f.item(), f.fluid(), x + 2, y + 2, 16);
        final String n = f.perCycle() > 1 && f.fluid() == null ? Fmt.compact(f.perCycle()) : "";
        if (!n.isEmpty()) text(n, x + SLOT - 1 - Hyb.width(n), y + SLOT - 8, Hyb.INK);
    }

    /** What a machine made or used of something, per minute over the watched part of the last hour. */
    private static String rate(@Nullable final Map<String, MachineStats.Amount> amounts, final MachineStatus.Flow f,
        final double watchedSeconds, final long now) {
        if (amounts == null || watchedSeconds <= 0) return "";
        final MachineStats.Amount m = find(amounts, f);
        return perMinute(m == null ? 0 : m.window.lastHour(now) / watchedSeconds * 60) + "/min";
    }

    @Nullable
    private static MachineStats.Amount find(final Map<String, MachineStats.Amount> amounts,
        final MachineStatus.Flow f) {
        for (final MachineStats.Amount m : amounts.values()) {
            final boolean same = f.fluid() != null ? m.fluid != null && m.fluid.isFluidEqual(f.fluid())
                : f.item() != null && m.item != null && m.item.isItemEqual(f.item());
            if (same) return m;
        }
        return null;
    }

    private static String perMinute(final double n) {
        return Fmt.compact(n < 10 ? Math.round(n * 10) / 10.0 : Math.round(n));
    }

    /**
     * The last hour as two charts, a point each two minutes: what it made (per minute) and the power it drew (EU/t,
     * against the most it can take), each in its own space and scale. Across the first, why it is stopped. Minutes it
     * was not watched are gaps.
     */
    private static void history(final View v, final int w, final float y) {
        final float gx = PAD, gw = w - 2 * PAD;
        final MachineStats.Track t = v.track;
        final long now = MachineStats.now();
        final List<MachineStatus.Flow> outs = outs(v);
        final MachineStats.Amount main = t == null || outs.isEmpty() ? null : find(t.made, outs.get(0));
        final double[] seen = t == null ? null : pairs(t.seen.minutes(now));
        // What it made: per minute over each two minutes, and its average this hour.
        String rate = "";
        double[] made = null;
        if (t != null && main != null) {
            final double[] amounts = pairs(main.window.minutes(now));
            made = new double[POINTS];
            for (int i = 0; i < POINTS; i++) made[i] = seen[i] > 0 ? amounts[i] / seen[i] * 60 : 0;
            rate = perMinute(main.window.lastHour(now) / Math.max(1, t.seen.lastHour(now)) * 60) + "/min";
        }
        title("Made", t == null ? "No history yet" : rate, GREEN, gx, gw, y, t != null);
        chart(made, seen, 0, GREEN, gx, y + 11, gw);
        final String why = reason(v);
        if (!why.isEmpty()) {
            final String fit = Hyb.fit(why, Math.round(gw - 12));
            final float tw = Hyb.width(fit) + 8, tx = Math.round(gx + (gw - tw) / 2f),
                ty = y + 11 + (GRAPH_H - 11) / 2f;
            rect(tx, ty, tw, 11, 0xE0141416);
            text(fit, tx + 4, ty + 1.5f, light(v) == Hyb.RED_INK ? Hyb.RED_INK : Hyb.AMBER_INK);
        }
        // The power it drew, against the most it can take; the figure now.
        final float py = y + CHART;
        final MachineStatus st = v.status;
        double[] power = null;
        if (t != null) {
            final double[] energy = pairs(t.energy.minutes(now));
            power = new double[POINTS];
            for (int i = 0; i < POINTS; i++) power[i] = seen[i] > 0 ? energy[i] / seen[i] : 0;
        }
        final long eu = running(v) ? st.euPerTick() : 0;
        final String figure = Fmt.power(eu)
            + (st != null && st.maxEuPerTick() > 0 ? " / " + Fmt.power(st.maxEuPerTick()) : "")
            + " EU/t";
        title("Power", figure, AMBER, gx, gw, py, false);
        chart(power, seen, st != null ? st.maxEuPerTick() : 0, AMBER, gx, py + 11, gw);
    }

    /** Sums pairs of minutes: the last hour in two-minute points, oldest first. */
    private static double[] pairs(final double[] minutes) {
        final double[] out = new double[POINTS];
        for (int i = 0; i < POINTS; i++) out[i] = minutes[2 * i] + minutes[2 * i + 1];
        return out;
    }

    /** A chart's title line: its name, "last hour" over the first, and its figure in its colour. */
    private static void title(final String name, final String figure, final int color, final float x, final float w,
        final float y, final boolean window) {
        text(name, x, y, Hyb.INK);
        if (window) textCentred("last hour", x + w / 2f, y, 0xFF6B6E76);
        textRight(figure, x + w, y, color);
    }

    /**
     * A chart in its own well: the points joined by a line, each marked with a dot, scaled from zero to the larger of
     * {@code floor} and its highest point; a gap where a point was not watched.
     */
    private static void chart(@Nullable final double[] values, @Nullable final double[] seen, final double floor,
        final int color, final float x, final float y, final float w) {
        rect(x, y, w, GRAPH_H, Hyb.TILE_EDGE);
        rect(x + 1, y + 1, w - 2, GRAPH_H - 2, WELL);
        for (int i = 1; i < 3; i++) rect(x + 1, y + 1 + i * (GRAPH_H - 2) / 3f, w - 2, 1, 0x0CFFFFFF);
        if (values == null || seen == null) return;
        double top = floor;
        for (final double v : values) top = Math.max(top, v);
        if (top <= 0) top = 1;
        final float left = x + 4, width = w - 8, bottom = y + GRAPH_H - 4, height = GRAPH_H - 8;
        final float step = width / (POINTS - 1);
        if (alpha >= 0.06f) {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            GL11.glLineWidth(
                new net.minecraft.client.gui.ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor());
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            final net.minecraft.client.renderer.Tessellator tes = net.minecraft.client.renderer.Tessellator.instance;
            int i = 0;
            while (i < POINTS) {
                if (seen[i] <= 0) {
                    i++;
                    continue;
                }
                tes.startDrawing(GL11.GL_LINE_STRIP);
                tes.setColorRGBA_I(color & 0xFFFFFF, Math.round(200 * alpha));
                for (; i < POINTS && seen[i] > 0; i++)
                    tes.addVertex(left + i * step, bottom - height * values[i] / top, 0);
                tes.draw();
            }
            GL11.glDisable(GL11.GL_LINE_SMOOTH);
            GL11.glLineWidth(1);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
        }
        // A dot on each point.
        for (int i = 0; i < POINTS; i++) {
            if (seen[i] <= 0) continue;
            final float px = Math.round(left + i * step), py = Math.round(bottom - height * (float) (values[i] / top));
            rect(px - 1, py - 2, 2, 4, color);
            rect(px - 2, py - 1, 4, 2, color);
        }
    }

    /** The state, how much it has made in all, how busy it has been this last hour. */
    private static void foot(final View v, final int w, final float y) {
        rect(PAD, y - 3, w - 2 * PAD, 1, 0x14FFFFFF);
        final int light = light(v);
        rect(PAD, y + 2, 5, 5, light);
        text(state(v), PAD + 9, y, light == Hyb.MUTED ? Hyb.INK : light);
        final MachineStats.Track t = v.track;
        final List<MachineStatus.Flow> outs = outs(v);
        final MachineStats.Amount main = t == null || outs.isEmpty() ? null : find(t.made, outs.get(0));
        if (main != null) textCentred(Fmt.compact(Math.round(main.window.total())) + " made", w / 2f, y, Hyb.MUTED);
        final float busy = t == null ? -1 : t.busy(MachineStats.now(), true);
        if (busy >= 0) textRight(Math.round(busy * 100) + "% busy", w - PAD, y, Hyb.MUTED);
    }

    /** The progress arrow: a shaft and a head, filled in green as far as the recipe has got. */
    private static void arrow(final float x, final float y, final int len, final float progress) {
        final int done = progress < 0 ? 0 : Math.round(len * progress);
        for (int col = 0; col < len; col++) {
            final int h = col < len - 7 ? 3 : 9 - 2 * (col - (len - 7));
            if (h <= 0) continue;
            rect(x + col, y + (9 - h) / 2f, 1, h, col < done ? Hyb.PRODUCT_INK : Hyb.TILE_HI);
        }
    }

    /** The power wing's bolt, small. */
    private static void bolt(final float x, final float y) {
        final int c = 0xFFFBBF24;
        rect(x + 3, y, 3, 1, c);
        rect(x + 2, y + 1, 3, 1, c);
        rect(x + 1, y + 2, 3, 1, c);
        rect(x, y + 3, 6, 1, c);
        rect(x + 3, y + 4, 3, 1, c);
        rect(x + 2, y + 5, 3, 1, c);
        rect(x + 1, y + 6, 2, 1, c);
        rect(x + 1, y + 7, 1, 1, c);
    }

    /** A small gold star: the machine is a plan card's. */
    private static void star(final float x, final float y) {
        final int c = Hyb.GOLD;
        rect(x + 3, y, 1, 2, c);
        rect(x, y + 2, 7, 1, c);
        rect(x + 1, y + 3, 5, 1, c);
        rect(x + 2, y + 4, 3, 1, c);
        rect(x + 1, y + 5, 2, 1, c);
        rect(x + 4, y + 5, 2, 1, c);
        rect(x + 1, y + 6, 1, 1, c);
        rect(x + 5, y + 6, 1, 1, c);
    }

    // endregion

    // region Far: the tile

    static final int TILE = 18;

    /** A far machine, as the board's zoomed-out card: its icon on a dark tile, a light for its state, its progress. */
    static void far(final View v, final float fade) {
        alpha = fade;
        rect(1, 2, TILE, TILE, 0x38000000);
        rect(0, 0, TILE, TILE, Hyb.FRAME);
        rect(1, 1, TILE - 2, TILE - 2, 0xF21E2024);
        icon(v.icon, null, 1, 1, 16);
        rect(TILE - 5, 1, 4, 4, 0xFF000000);
        rect(TILE - 4, 2, 2, 2, light(v));
        if (v.tag != null) star(-3, -3);
        final MachineStatus st = v.status;
        if (st != null && st.state() == MachineStatus.State.RUNNING && st.progress() >= 0) {
            rect(1, TILE - 2, TILE - 2, 1, 0xFF000000);
            rect(1, TILE - 2, (TILE - 2) * st.progress(), 1, Hyb.PRODUCT_INK);
        }
    }

    // endregion

    private static String seconds(final int ticks) {
        final int s = (ticks + 19) / 20;
        return s < 60 ? s + "s" : s / 60 + "m " + s % 60 + "s";
    }
}
