package com.gtnhplanner.ui.world;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.drawable.GuiDraw;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * How the AR lens draws a machine: as the machine's own window looks when opened, in the game's grey with its slots
 * and progress arrow, since it shows the machine as it is in the world, not the plan. A link to a plan card hangs
 * under it as a dark tag in the planner's colours. Three sizes: a slot-sized tile for far machines, a panel for near
 * ones (name, slots, progress, state), and the panel for the machine looked at, which adds what it made and used,
 * how busy it was, and its power. Every colour takes the panel's fade.
 */
final class ArPanel {

    private ArPanel() {}

    /** A linked plan card: its name and plan, and how many of its machines are placed of how many the plan has. */
    record Tag(String card, String plan, int placed, int planned) {}

    /** A machine to draw: what it is, what it is doing (null when unknown), its link, how far it is. */
    record View(String name, @Nullable ItemStack icon, @Nullable MachineStatus status, @Nullable Tag tag,
        int distance) {}

    // The game's window colours.
    private static final int FACE = 0xFFC6C6C6, LIGHT = 0xFFFFFFFF, DARK = 0xFF555555, EDGE = 0xFF000000;
    private static final int SLOT = 0xFF8B8B8B, SLOT_DARK = 0xFF373737;
    private static final int TEXT = 0xFF404040, SOFT = 0xFF6B6B6B;
    private static final int GREEN = 0xFF1C7C1C, RED = 0xFFAA0000;
    private static final int PAD = 6, TITLE = 16, TAG_H = 12;
    private static final int FOCUS_W = 236;

    /** The fade every colour is drawn at. */
    private static float alpha = 1;

    private static int a(final int argb) {
        return (Math.round((argb >>> 24) * alpha) & 0xFF) << 24 | argb & 0xFFFFFF;
    }

    private static void rect(final float x, final float y, final float w, final float h, final int argb) {
        Hyb.rect(x, y, w, h, a(argb));
    }

    private static void text(final String s, final float x, final float y, final int color, final boolean shadow) {
        if (alpha < 0.06f || s.isEmpty()) return;
        GuiDraw.drawText(s, x, y, 1, a(color), shadow);
    }

    private static void textRight(final String s, final float right, final float y, final int color) {
        text(s, right - Hyb.width(s), y, color, false);
    }

    private static void icon(@Nullable final ItemStack item, @Nullable final FluidStack fluid, final float x,
        final float y, final float size) {
        // Items cannot fade: they come in once the panel is mostly there.
        if (alpha < 0.55f) return;
        if (fluid != null) Hyb.fluid(fluid, x, y, size, 0);
        else if (item != null) Hyb.item(item, x, y, size, 0);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    // region The game's window, slots and arrow

    private static void window(final float x, final float y, final float w, final float h) {
        rect(x + 1, y, w - 2, 1, EDGE);
        rect(x + 1, y + h - 1, w - 2, 1, EDGE);
        rect(x, y + 1, 1, h - 2, EDGE);
        rect(x + w - 1, y + 1, 1, h - 2, EDGE);
        rect(x + 1, y + 1, w - 2, h - 2, FACE);
        rect(x + 1, y + 1, w - 3, 2, LIGHT);
        rect(x + 1, y + 1, 2, h - 3, LIGHT);
        rect(x + 3, y + h - 3, w - 4, 2, DARK);
        rect(x + w - 3, y + 3, 2, h - 4, DARK);
    }

    private static void slot(final float x, final float y) {
        rect(x, y, 17, 17, SLOT_DARK);
        rect(x + 1, y + 1, 17, 17, LIGHT);
        rect(x + 1, y + 1, 16, 16, SLOT);
    }

    /** A slot with what goes through it per cycle, its count in the corner as the game draws a stack's. */
    private static void slot(final float x, final float y, final MachineStatus.Flow f) {
        slot(x, y);
        icon(f.item(), f.fluid(), x + 1, y + 1, 16);
        final String n = f.fluid() != null ? Fmt.compact(f.perCycle())
            : f.perCycle() > 1 ? Fmt.compact(f.perCycle()) : "";
        if (!n.isEmpty()) text(n, x + 18 - Hyb.width(n), y + 9.5f, 0xFFFFFFFF, true);
    }

    /** The progress arrow, filled white as far as the recipe has got. */
    private static void arrow(final float x, final float y, final float progress) {
        final int fill = progress < 0 ? 0 : Math.round(22 * progress);
        for (int col = 0; col < 22; col++) {
            final int h = col < 14 ? 6 : 15 - 2 * (col - 14);
            if (h <= 0) continue;
            rect(x + col, y + (15 - h) / 2f, 1, h, col < fill ? LIGHT : SLOT);
        }
    }

    // endregion

    // region Near: the panel

    private record Grid(List<MachineStatus.Flow> ins, List<MachineStatus.Flow> outs, int inCols, int outCols,
        int rows) {

        static Grid of(@Nullable final MachineStatus st) {
            final List<MachineStatus.Flow> ins = st == null ? List.of()
                : st.inputs()
                    .subList(
                        0,
                        Math.min(
                            6,
                            st.inputs()
                                .size()));
            final List<MachineStatus.Flow> outs = st == null ? List.of()
                : st.outputs()
                    .subList(
                        0,
                        Math.min(
                            6,
                            st.outputs()
                                .size()));
            final int inCols = Math.min(3, ins.size()), outCols = Math.min(3, outs.size());
            final int rows = Math.max((ins.size() + 2) / 3, (outs.size() + 2) / 3);
            return new Grid(ins, outs, inCols, outCols, rows);
        }

        boolean empty() {
            return rows == 0;
        }

        int width() {
            return inCols * 18 + (inCols > 0 ? 5 : 0) + 22 + (outCols > 0 ? 5 : 0) + outCols * 18;
        }

        int height() {
            return rows * 18;
        }
    }

    /** What the machine is doing, in a line, and its colour. */
    private static String state(final View v) {
        final MachineStatus st = v.status;
        if (st == null) return "Linked block";
        return switch (st.state()) {
            case RUNNING -> "Running" + (st.progress() >= 0 ? "  " + Math.round(st.progress() * 100) + "%" : "")
                + (st.ticksLeft() > 0 ? "  " + seconds(st.ticksLeft()) : "");
            case PROBLEM -> "Stopped" + (st.detail()
                .isEmpty() ? "" : ": " + st.detail());
            case OFF -> "Turned off";
            case IDLE -> "Idle" + (st.detail()
                .isEmpty() ? "" : ": " + st.detail());
        };
    }

    private static int stateColor(final View v) {
        final MachineStatus st = v.status;
        if (st == null) return SOFT;
        return st.state() == MachineStatus.State.RUNNING ? GREEN
            : st.state() == MachineStatus.State.PROBLEM ? RED : SOFT;
    }

    static int width(final View v) {
        final Grid g = Grid.of(v.status);
        final String tier = v.status != null ? v.status.tier() : "";
        int w = Math.max(g.width(), Hyb.width(v.name) + (tier.isEmpty() ? 0 : Hyb.width(tier) + 8));
        w = Math.max(w, Math.min(180, Hyb.width(state(v))));
        // Wide enough for its plan tag, within reason.
        if (v.tag != null) w = Math.max(w, Math.min(206, Hyb.width(tagText(v.tag)) + Hyb.width(tagCount(v.tag)) + 16));
        return Math.min(220, Math.max(110, w + 2 * PAD));
    }

    static int height(final View v) {
        final Grid g = Grid.of(v.status);
        return TITLE + (g.empty() ? 0 : g.height() + 5) + 9 + 6 + (v.tag != null ? TAG_H : 0);
    }

    /** The near panel at the origin, at a fade. */
    static void near(final View v, final float fade, final boolean lookedAt) {
        alpha = fade;
        final int w = width(v), h = height(v) - (v.tag != null ? TAG_H : 0);
        body(v, 0, 0, w, h, lookedAt);
        if (v.tag != null) tag(v.tag, 0, h, w);
    }

    /** The window with the name and tier, the slots and arrow, and the state; returns where the state line ends. */
    private static float body(final View v, final float x, final float y, final int w, final int h,
        final boolean lookedAt) {
        if (lookedAt) rect(x - 2, y - 2, w + 4, h + 4, Hyb.SELECTION);
        window(x, y, w, h);
        final String tier = v.status != null ? v.status.tier() : "";
        final int tierW = tier.isEmpty() ? 0 : Hyb.width(tier) + 6;
        text(Hyb.fit(v.name, w - 2 * PAD - tierW), x + PAD, y + 6, TEXT, false);
        if (!tier.isEmpty()) textRight(
            tier,
            x + w - PAD,
            y + 6,
            Hyb.tier(tier)
                .bg());
        float row = y + TITLE;
        final Grid g = Grid.of(v.status);
        if (!g.empty()) {
            float gx = x + (w - g.width()) / 2f;
            for (int i = 0; i < g.ins.size(); i++) slot(gx + i % 3 * 18, row + i / 3 * 18, g.ins.get(i));
            gx += g.inCols * 18 + (g.inCols > 0 ? 5 : 0);
            arrow(
                gx,
                row + (g.height() - 15) / 2f,
                v.status.state() == MachineStatus.State.RUNNING ? v.status.progress() : -1);
            gx += 22 + (g.outCols > 0 ? 5 : 0);
            for (int i = 0; i < g.outs.size(); i++) slot(gx + i % 3 * 18, row + i / 3 * 18, g.outs.get(i));
            row += g.height() + 5;
        }
        text(Hyb.fit(state(v), w - 2 * PAD), x + PAD, row, stateColor(v), false);
        return row + 9;
    }

    private static String tagText(final Tag t) {
        return "PLAN " + t.card + " · " + t.plan;
    }

    private static String tagCount(final Tag t) {
        return t.planned > 0 ? t.placed + " of " + t.planned + " placed" : t.placed + " placed";
    }

    /** The link to the plan, in the planner's colours: the card, its plan, and how many of its machines are placed. */
    private static void tag(final Tag t, final float x, final float y, final int w) {
        rect(x + 2, y, w - 4, TAG_H, 0xF0141416);
        rect(x + 2, y, 2, TAG_H, Hyb.GOLD);
        text("PLAN", x + 7, y + 2, Hyb.GOLD, false);
        final String count = tagCount(t);
        final float left = x + 11 + Hyb.width("PLAN");
        final int room = Math.round(w - 14 - Hyb.width("PLAN") - Hyb.width(count) - 8);
        text(Hyb.fit(t.card + " · " + t.plan, room), left, y + 2, Hyb.INK, false);
        textRight(count, x + w - 6, y + 2, Hyb.MUTED);
    }

    // endregion

    // region Far: the tile

    static final int TILE = 18;

    /** A far machine: its icon in a slot, a light for its state, its progress along the bottom. */
    static void far(final View v, final float fade) {
        alpha = fade;
        rect(-1, -1, TILE + 1, TILE + 1, 0xC0000000);
        slot(0, 0);
        icon(v.icon, null, 1, 1, 16);
        final MachineStatus st = v.status;
        final int light = st == null ? 0xFFAAAAAA
            : st.state() == MachineStatus.State.RUNNING ? 0xFF55FF55
                : st.state() == MachineStatus.State.PROBLEM ? 0xFFFF5555 : 0xFFAAAAAA;
        rect(TILE - 5, 1, 4, 4, EDGE);
        rect(TILE - 4, 2, 2, 2, light);
        if (v.tag != null) rect(0, -2, TILE, 2, Hyb.GOLD);
        if (st != null && st.state() == MachineStatus.State.RUNNING && st.progress() >= 0) {
            rect(0, TILE + 1, TILE, 2, 0xC0000000);
            rect(0, TILE + 1, TILE * st.progress(), 2, 0xFF55FF55);
        }
    }

    // endregion

    // region Looked at: the whole story

    static int focusWidth(final View v) {
        return Math.max(FOCUS_W, width(v));
    }

    /** Rows for what it made and used: up to four each. */
    private static int rows(@Nullable final MachineStats.Track t) {
        if (t == null) return 0;
        final int made = Math.min(4, t.made.size()), used = Math.min(4, t.used.size());
        return (made > 0 ? made + 1 : 0) + (used > 0 ? used + 1 : 0);
    }

    static int focusHeight(final View v, @Nullable final MachineStats.Track t, final boolean hint) {
        final Grid g = Grid.of(v.status);
        int h = TITLE + (g.empty() ? 0 : g.height() + 5) + 9 + 6;
        h += 6 + 10; // busy line
        if (rows(t) > 0) h += 11 + rows(t) * 10;
        if (v.status != null && (v.status.maxEuPerTick() > 0 || v.status.euPerTick() > 0)) h += 10;
        h += 10 + 4; // the footnote
        return h + (v.tag != null || hint ? TAG_H : 0) + 11;
    }

    /**
     * The machine looked at, at the origin: an AR tab with how far it is, the window with the slots, then how busy it
     * has been, what it made and used (per minute lately, in the last hour, in all), its power, and its plan link or
     * how
     * to make one.
     */
    static void focus(final View v, @Nullable final MachineStats.Track t, final float fade, final String linkKey) {
        alpha = fade;
        final int w = focusWidth(v);
        final boolean hint = v.tag == null && !linkKey.isEmpty();
        final int h = focusHeight(v, t, hint) - (v.tag != null || hint ? TAG_H : 0) - 11;
        // The AR tab over the window.
        final String ar = "AR", away = v.distance + " m away";
        final int tabW = Hyb.width(ar) + Hyb.width(away) + 16;
        rect(4, 0, tabW, 11, 0xF0141416);
        rect(4, 0, tabW, 1, Hyb.SELECTION);
        text(ar, 8, 2, Hyb.SELECTION, false);
        text(away, 14 + Hyb.width(ar), 2, Hyb.MUTED, false);
        final float y0 = 11;
        float row = body(v, 0, y0, w, h, false);
        // A groove, then the history.
        row += 2;
        rect(PAD, row, w - 2 * PAD, 1, SLOT);
        rect(PAD, row + 1, w - 2 * PAD, 1, LIGHT);
        row += 5;
        final long now = MachineStats.now();
        final float minute = t == null ? -1 : t.busy(now, false), hour = t == null ? -1 : t.busy(now, true);
        text(
            minute < 0 ? "No history yet"
                : "Busy " + Math.round(minute * 100) + "% last minute, " + Math.round(hour * 100) + "% last hour",
            PAD,
            row,
            TEXT,
            false);
        row += 10;
        if (rows(t) > 0) {
            // Three number columns, right-aligned, each as wide as its heading.
            final float c3 = w - PAD, c2 = c3 - Hyb.width("in all") - 14, c1 = c2 - Hyb.width("last hour") - 14;
            textRight("per min", c1, row, SOFT);
            textRight("last hour", c2, row, SOFT);
            textRight("in all", c3, row, SOFT);
            row += 11;
            final double watched = Math.max(1, t.seen.lastMinute(now));
            row = amounts("MADE", t.made(), row, c1, c2, c3, now, watched);
            row = amounts("USED", t.used(), row, c1, c2, c3, now, watched);
        }
        final MachineStatus st = v.status;
        if (st != null && (st.maxEuPerTick() > 0 || st.euPerTick() > 0)) {
            final boolean running = st.state() == MachineStatus.State.RUNNING;
            String power = "Power " + (running ? Fmt.power(st.euPerTick()) : "0") + " EU/t";
            if (st.maxEuPerTick() > 0) power += " of " + Fmt.power(st.maxEuPerTick()) + " max";
            if (st.parallels() > 1) power += ", " + st.parallels() + " parallel";
            text(power, PAD, row, TEXT, false);
            row += 10;
        }
        text(
            t == null ? "Counting while you are nearby"
                : "Counted while you were nearby, for " + duration(now - t.since),
            PAD,
            row + 1,
            SOFT,
            false);
        if (v.tag != null) tag(v.tag, 0, y0 + h, w);
        else if (hint) {
            rect(2, y0 + h, w - 4, TAG_H, 0xF0141416);
            rect(2, y0 + h, 2, TAG_H, Hyb.SELECTION);
            text(linkKey, 7, y0 + h + 2, Hyb.SELECTION, false);
            text("Link it to a plan card", 11 + Hyb.width(linkKey), y0 + h + 2, Hyb.INK, false);
        }
    }

    /** A section of the table: per minute over the watched part of the last minute, the last hour, and in all. */
    private static float amounts(final String title, final List<MachineStats.Amount> list, float row, final float c1,
        final float c2, final float c3, final long now, final double watchedSeconds) {
        if (list.isEmpty()) return row;
        text(title, PAD, row, SOFT, false);
        row += 10;
        for (int i = 0; i < Math.min(4, list.size()); i++) {
            final MachineStats.Amount m = list.get(i);
            icon(m.item, m.fluid, PAD, row - 0.5f, 8);
            final boolean fluid = m.fluid != null;
            final String perMin = amount(m.window.lastMinute(now) / watchedSeconds * 60, fluid);
            text(Hyb.fit(m.name(), Math.round(c1 - Hyb.width(perMin) - PAD - 18)), PAD + 11, row, TEXT, false);
            textRight(perMin, c1, row, TEXT);
            textRight(amount(m.window.lastHour(now), fluid), c2, row, TEXT);
            textRight(amount(m.window.total(), fluid), c3, row, TEXT);
            row += 10;
        }
        return row;
    }

    private static String amount(final double n, final boolean fluid) {
        return Fmt.compact(n < 10 ? Math.round(n * 10) / 10.0 : Math.round(n)) + (fluid ? " L" : "");
    }

    private static String duration(final long seconds) {
        if (seconds < 60) return seconds + " s";
        if (seconds < 3600) return seconds / 60 + " min";
        return seconds / 3600 + " h " + seconds % 3600 / 60 + " min";
    }

    private static String seconds(final int ticks) {
        final int s = (ticks + 19) / 20;
        return s < 60 ? s + " s left" : s / 60 + " min " + s % 60 + " s left";
    }

    // endregion
}
