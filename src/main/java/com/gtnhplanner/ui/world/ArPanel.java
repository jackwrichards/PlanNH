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
 * How the AR lens draws a machine, in the planner's look: a small dark panel with the machine's name and tier, what
 * its recipe takes and makes in tiles either side of a progress arrow (each with its recent rate under it), one line
 * for what it is doing and its power, and a quiet line for its plan card and how busy it has been. Far machines are a
 * tile with a state light. Every colour takes the panel's fade.
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

    private static final int PAD = 6, SLOT = 20, GAP = 4, ARROW = 22, MAX_SLOTS = 4;
    private static final int GOLD_SOFT = 0xFFB59A54;
    /** Small type: half the game's font, a screen pixel a font pixel at GUI scale 2. */
    private static final float SMALL = 0.5f;

    /** The fade every colour is drawn at. */
    private static float alpha = 1;

    private static int a(final int argb) {
        return (Math.round((argb >>> 24) * alpha) & 0xFF) << 24 | argb & 0xFFFFFF;
    }

    private static void rect(final float x, final float y, final float w, final float h, final int argb) {
        Hyb.rect(x, y, w, h, a(argb));
    }

    private static void text(final String s, final float x, final float y, final float scale, final int color) {
        if (alpha < 0.06f || s.isEmpty()) return;
        GuiDraw.drawText(s, x, y, scale, a(color), scale >= 1);
    }

    private static float width(final String s, final float scale) {
        return Hyb.width(s) * scale;
    }

    private static void icon(@Nullable final ItemStack item, @Nullable final FluidStack fluid, final float x,
        final float y, final float size) {
        // Items cannot fade: they come in once the panel is mostly there.
        if (alpha < 0.55f) return;
        Hyb.icon(item, fluid, x, y, size, 0);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    // region Layout

    private static List<MachineStatus.Flow> ins(final View v) {
        return v.status == null ? List.of() : first(v.status.inputs());
    }

    private static List<MachineStatus.Flow> outs(final View v) {
        return v.status == null ? List.of() : first(v.status.outputs());
    }

    private static List<MachineStatus.Flow> first(final List<MachineStatus.Flow> all) {
        return all.subList(0, Math.min(MAX_SLOTS, all.size()));
    }

    private static boolean slots(final View v) {
        return !ins(v).isEmpty() || !outs(v).isEmpty();
    }

    /** The slots and the arrow: their width. */
    private static int rowWidth(final View v) {
        final int i = ins(v).size(), o = outs(v).size();
        return i * (SLOT + 2) + (i > 0 ? GAP : 0) + ARROW + (o > 0 ? GAP : 0) + o * (SLOT + 2);
    }

    private static String status(final View v) {
        final MachineStatus st = v.status;
        if (st == null) return "Linked block";
        return switch (st.state()) {
            case RUNNING -> "Running" + (st.progress() >= 0 ? "  " + Math.round(st.progress() * 100) + "%" : "")
                + (st.ticksLeft() > 0 ? "  " + seconds(st.ticksLeft()) : "");
            case PROBLEM -> st.detail()
                .isEmpty() ? "Stopped" : st.detail();
            case OFF -> "Turned off";
            case IDLE -> st.detail()
                .isEmpty() ? "Idle" : "Idle: " + st.detail();
        };
    }

    private static int light(final View v) {
        final MachineStatus st = v.status;
        if (st == null) return Hyb.MUTED;
        return st.state() == MachineStatus.State.RUNNING ? Hyb.PRODUCT_INK
            : st.state() == MachineStatus.State.PROBLEM ? Hyb.RED_INK : Hyb.MUTED;
    }

    private static String power(final View v) {
        final MachineStatus st = v.status;
        if (st == null || st.maxEuPerTick() <= 0 && st.euPerTick() <= 0) return "";
        final long now = st.state() == MachineStatus.State.RUNNING ? st.euPerTick() : 0;
        return st.maxEuPerTick() > 0 ? Fmt.power(now) + " / " + Fmt.power(st.maxEuPerTick()) + " EU/t"
            : Fmt.power(now) + " EU/t";
    }

    /** The quiet line: the plan card, or that there is none. */
    private static String planLine(final View v) {
        if (v.tag == null) return "Not linked to a plan (L)";
        final Tag t = v.tag;
        return t.card + " · " + t.plan + (t.planned > 0 ? " · " + t.placed + " of " + t.planned : "");
    }

    /** How busy it has been this last hour, or null before it has been watched. */
    @Nullable
    private static String busy(final View v) {
        if (v.track == null) return null;
        final float hour = v.track.busy(MachineStats.now(), true);
        return hour < 0 ? null : "busy " + Math.round(hour * 100) + "%";
    }

    static int width(final View v) {
        final String tier = v.status != null ? v.status.tier() : "";
        float w = Hyb.width(v.name) + (tier.isEmpty() ? 0 : Hyb.width(tier) + 12);
        w = Math.max(w, rowWidth(v));
        w = Math.max(w, 9 + Hyb.width(status(v)) + 10 + Hyb.width(power(v)));
        final String busy = busy(v);
        w = Math.max(w, 8 + width(planLine(v), SMALL) + (busy == null ? 0 : 8 + width(busy, SMALL)));
        return Math.min(230, Math.max(120, Math.round(w) + 2 * PAD));
    }

    private static int rowHeight(final View v) {
        return slots(v) ? SLOT + 2 + (v.track != null ? 6 : 0) + 4 : 0;
    }

    static int height(final View v) {
        return PAD - 1 + 12 + rowHeight(v) + 11 + 3 + 4 + PAD;
    }

    // endregion

    // region Near: the panel

    /** The panel at the origin, at a fade; the machine looked at has the selection ring. */
    static void near(final View v, final float fade, final boolean lookedAt) {
        alpha = fade;
        final int w = width(v), h = height(v);
        if (alpha >= 0.95f) Hyb.dropShadow(0, 0, w, h);
        if (lookedAt) rect(-2, -2, w + 4, h + 4, Hyb.SELECTION);
        rect(0, 0, w, h, Hyb.FRAME);
        rect(1, 1, w - 2, h - 2, 0xF0202226);
        rect(1, 1, w - 2, 1, 0x30FFFFFF);
        // A plan card's machine has a gold rule down its left edge.
        if (v.tag != null) rect(1, 1, 2, h - 2, Hyb.GOLD);
        float y = PAD - 1;
        // Name, and the tier in its colours.
        final String tier = v.status != null ? v.status.tier() : "";
        final int tierW = tier.isEmpty() ? 0 : Hyb.width(tier) + 6;
        text(Hyb.fit(v.name, w - 2 * PAD - tierW - 4), PAD, y, 1, Hyb.INK);
        if (!tier.isEmpty()) {
            final Hyb.Tier t = Hyb.tier(tier);
            final float tx = w - PAD - tierW;
            rect(tx, y - 1, tierW, 10, t.border());
            rect(tx + 1, y, tierW - 2, 8, t.bg());
            text(tier, tx + 3, y, 1, t.text());
        }
        y += 12;
        if (slots(v)) row(v, Math.round((w - rowWidth(v)) / 2f), y);
        y += rowHeight(v);
        // What it is doing, and its power.
        final int light = light(v);
        rect(PAD, y + 2, 4, 4, light);
        final String power = power(v);
        text(
            Hyb.fit(status(v), Math.round(w - 2 * PAD - 9 - Hyb.width(power) - 8)),
            PAD + 8,
            y,
            1,
            light == Hyb.MUTED ? Hyb.INK : light);
        if (!power.isEmpty()) text(power, w - PAD - Hyb.width(power), y, 1, Hyb.MUTED);
        y += 11;
        // The quiet line: its plan card, and how busy it has been.
        rect(PAD, y, w - 2 * PAD, 1, 0x18FFFFFF);
        y += 3;
        final String busy = busy(v);
        final float busyW = busy == null ? 0 : width(busy, SMALL);
        if (v.tag != null) chain(PAD, y);
        final float px = PAD + (v.tag != null ? 8 : 0);
        text(fitSmall(planLine(v), w - PAD - px - busyW - 6), px, y, SMALL, v.tag != null ? GOLD_SOFT : Hyb.MUTED);
        if (busy != null) text(busy, w - PAD - busyW, y, SMALL, Hyb.MUTED);
    }

    /** What goes in, the arrow filling with the recipe's progress, what comes out; recent rates under. */
    private static void row(final View v, final float x0, final float y) {
        float x = x0;
        final MachineStatus st = v.status;
        final long now = MachineStats.now();
        final double watched = v.track == null ? 0 : v.track.seen.lastHour(now);
        for (final MachineStatus.Flow f : ins(v)) {
            slot(x, y, f, rate(v.track == null ? null : v.track.used, f, watched, now));
            x += SLOT + 2;
        }
        if (!ins(v).isEmpty()) x += GAP;
        arrow(
            x,
            y + (SLOT - 9) / 2f,
            st != null && st.state() == MachineStatus.State.RUNNING ? st.progress() : -1,
            light(v));
        x += ARROW + (outs(v).isEmpty() ? 0 : GAP);
        for (final MachineStatus.Flow f : outs(v)) {
            slot(x, y, f, rate(v.track == null ? null : v.track.made, f, watched, now));
            x += SLOT + 2;
        }
    }

    /** A tile as the board's ports: the icon with its shadow, what one cycle moves in the corner, the rate under. */
    private static void slot(final float x, final float y, final MachineStatus.Flow f, @Nullable final String rate) {
        rect(x, y, SLOT, SLOT, Hyb.TILE_EDGE);
        rect(x + 1, y + 1, SLOT - 2, SLOT - 2, Hyb.TILE);
        rect(x + 1, y + 1, SLOT - 2, 1, Hyb.TILE_HI);
        icon(f.item(), f.fluid(), x + 2, y + 2, 16);
        final String n = f.perCycle() > 1 || f.fluid() != null ? Fmt.compact(f.perCycle()) : "";
        if (!n.isEmpty()) {
            final float nw = width(n, SMALL);
            rect(x + SLOT - 2 - nw, y + SLOT - 6, nw + 1, 5, 0xB0000000);
            text(n, x + SLOT - 1.5f - nw, y + SLOT - 5.5f, SMALL, Hyb.INK);
        }
        if (rate != null) text(rate, x + (SLOT - width(rate, SMALL)) / 2f, y + SLOT + 2, SMALL, Hyb.MUTED);
    }

    /** What a machine made or used of something, per minute over the watched part of the last hour. */
    @Nullable
    private static String rate(@Nullable final Map<String, MachineStats.Amount> amounts, final MachineStatus.Flow f,
        final double watchedSeconds, final long now) {
        if (amounts == null || watchedSeconds <= 0) return null;
        for (final MachineStats.Amount m : amounts.values()) {
            final boolean same = f.fluid() != null ? m.fluid != null && m.fluid.isFluidEqual(f.fluid())
                : f.item() != null && m.item != null && m.item.isItemEqual(f.item());
            if (!same) continue;
            final double perMin = m.window.lastHour(now) / watchedSeconds * 60;
            return Fmt.compact(perMin < 10 ? Math.round(perMin * 10) / 10.0 : Math.round(perMin)) + "/min";
        }
        return "0/min";
    }

    /** The progress arrow: a shaft and head, filled in the state's colour as far as the recipe has got. */
    private static void arrow(final float x, final float y, final float progress, final int fill) {
        final int done = progress < 0 ? 0 : Math.round(ARROW * progress);
        for (int col = 0; col < ARROW; col++) {
            final int h = col < ARROW - 7 ? 3 : 9 - 2 * (col - (ARROW - 7));
            if (h <= 0) continue;
            rect(x + col, y + (9 - h) / 2f, 1, h, col < done ? fill : Hyb.TILE_HI);
        }
    }

    /** A small chain link, the mark of a plan card's machine. */
    private static void chain(final float x, final float y) {
        final int c = GOLD_SOFT;
        rect(x, y + 1, 3, 1, c);
        rect(x, y + 3, 3, 1, c);
        rect(x, y + 1, 1, 3, c);
        rect(x + 3, y, 3, 1, c);
        rect(x + 3, y + 2, 3, 1, c);
        rect(x + 5, y, 1, 3, c);
    }

    private static String fitSmall(final String s, final float room) {
        return width(s, SMALL) <= room ? s : Hyb.fit(s, Math.round(room / SMALL));
    }

    // endregion

    // region Far: the tile

    static final int TILE = 18;

    /** A far machine, as the board's zoomed-out card: its icon on a dark tile, a light for its state, its progress. */
    static void far(final View v, final float fade) {
        alpha = fade;
        rect(0, 0, TILE, TILE, v.tag != null ? Hyb.GOLD : Hyb.FRAME);
        rect(1, 1, TILE - 2, TILE - 2, 0xF0202226);
        icon(v.icon, null, 1, 1, 16);
        rect(TILE - 5, 1, 4, 4, 0xFF000000);
        rect(TILE - 4, 2, 2, 2, light(v));
        final MachineStatus st = v.status;
        if (st != null && st.state() == MachineStatus.State.RUNNING && st.progress() >= 0) {
            rect(1, TILE - 2, TILE - 2, 1, 0xFF000000);
            rect(1, TILE - 2, (TILE - 2) * st.progress(), 1, Hyb.PRODUCT_INK);
        }
    }

    // endregion

    private static String seconds(final int ticks) {
        final int s = (ticks + 19) / 20;
        return s < 60 ? s + " s left" : s / 60 + " min " + s % 60 + " s left";
    }
}
