package com.sbancuz.plannh.ui.popup;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.GL11;

import com.sbancuz.plannh.ui.theme.Hyb;

/**
 * A tooltip as Factory Flow draws one: a dark beveled panel with a title, a muted subtitle, label and value rows,
 * notes, and under a rule what each mouse button does, each with a little mouse that shows which. Built with the
 * chained adders, then drawn beside the pointer with {@link #drawNear}.
 */
public final class Tip {

    /** What the action is done with; the little mouse lights that part. */
    public enum Input {
        LEFT,
        RIGHT,
        WHEEL,
        MIDDLE,
        DRAG,
        /** A key, named in the action's label. */
        KEY
    }

    private record Row(String label, String value, int color) {}

    private record Note(String text, int color) {}

    private record Action(Input input, String label) {}

    public static final int BG = 0xFF26282D;
    public static final int EDGE = 0xFF111317;
    public static final int HI = 0xFF4A4C54;
    public static final int LO = 0xFF17191D;
    public static final int TEXT = 0xFFE5E5E5;
    public static final int TITLE = 0xFFF5F5F5;
    public static final int SUBTLE = 0xFFA3A3A3;
    public static final int RULE = 0xFF3A3B40;
    public static final int WARN = 0xFFFFD236;
    private static final int PAD_X = 6, PAD_Y = 5, LINE = 10, MAX_W = 220;

    private final String title;
    /** The title over as many lines as it takes at {@link #MAX_W}: a long one never runs out of the panel. */
    private final List<String> titleLines;
    private String subtitle;
    private int titleColor = TITLE;
    private final List<Row> rows = new ArrayList<>();
    private final List<Note> notes = new ArrayList<>();
    private final List<Action> actions = new ArrayList<>();

    private Tip(final String title) {
        this.title = title == null ? "" : title;
        this.titleLines = Hyb.width(this.title) <= MAX_W ? List.of(this.title)
            : Hyb.font()
                .listFormattedStringToWidth(this.title, MAX_W);
    }

    public static Tip of(final String title) {
        return new Tip(title);
    }

    /**
     * The old way, a list of lines: the first is the title, lines starting {@code §7} become muted notes, the rest
     * notes in the body colour.
     */
    public static Tip ofLines(final List<String> lines) {
        if (lines == null || lines.isEmpty()) return null;
        final Tip tip = new Tip(lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            final String line = lines.get(i);
            final boolean muted = line.startsWith("§7") || line.startsWith("§8");
            final String text = muted ? line.substring(2) : line;
            if (muted && tip.addActions(text)) continue;
            if (muted) tip.muted(text);
            else tip.note(text);
        }
        return tip;
    }

    private static final String[][] ACTION_PREFIXES = { { "Click: ", "LEFT" }, { "Left click: ", "LEFT" },
        { "Right click: ", "RIGHT" }, { "Middle click: ", "MIDDLE" }, { "Wheel: ", "WHEEL" }, { "Drag: ", "DRAG" },
        { "Double-click: ", "LEFT" }, { "R, U: ", "KEY" } };

    /**
     * Reads a hint line such as "Click: pick Wheel: next" (parts two spaces apart) as actions. False, adding nothing,
     * when any part is not one.
     */
    private boolean addActions(final String line) {
        final List<Action> found = new ArrayList<>();
        for (final String part : line.split(" {2,}")) {
            Action action = null;
            for (final String[] p : ACTION_PREFIXES) {
                if (!part.startsWith(p[0])) continue;
                String label = part.substring(p[0].length());
                if (p[0].startsWith("Double")) label += " (double-click)";
                if (p[1].equals("KEY")) label = "R, U: " + label;
                label = label.isEmpty() ? label : Character.toUpperCase(label.charAt(0)) + label.substring(1);
                action = new Action(Input.valueOf(p[1]), label);
                break;
            }
            if (action == null) return false;
            found.add(action);
        }
        actions.addAll(found);
        return !found.isEmpty();
    }

    public Tip sub(final String text) {
        subtitle = text;
        return this;
    }

    public Tip titleColor(final int color) {
        titleColor = color;
        return this;
    }

    public Tip row(final String label, final String value) {
        return row(label, value, TITLE);
    }

    public Tip row(final String label, final String value, final int color) {
        rows.add(new Row(label, value, color));
        return this;
    }

    public Tip note(final String text) {
        return note(text, TEXT);
    }

    public Tip note(final String text, final int color) {
        for (final String line : Hyb.font()
            .listFormattedStringToWidth(text, MAX_W)) notes.add(new Note(line, color));
        return this;
    }

    public Tip muted(final String text) {
        return note(text, SUBTLE);
    }

    public Tip action(final Input input, final String label) {
        actions.add(new Action(input, label));
        return this;
    }

    // region Layout

    private static final int ACTION_GAP = 10, MOUSE_W = 7, MOUSE_GAP = 4;

    private static int actionWidth(final Action a) {
        return MOUSE_W + MOUSE_GAP + Hyb.width(a.label);
    }

    /** Actions laid out in lines no wider than {@code width}. */
    private List<List<Action>> actionLines(final int width) {
        final List<List<Action>> out = new ArrayList<>();
        List<Action> line = new ArrayList<>();
        int used = 0;
        for (final Action a : actions) {
            final int w = actionWidth(a);
            if (!line.isEmpty() && used + ACTION_GAP + w > width) {
                out.add(line);
                line = new ArrayList<>();
                used = 0;
            }
            used += (line.isEmpty() ? 0 : ACTION_GAP) + w;
            line.add(a);
        }
        if (!line.isEmpty()) out.add(line);
        return out;
    }

    private int contentWidth() {
        int w = 0;
        for (final String line : titleLines) w = Math.max(w, Hyb.width(line));
        if (subtitle != null) w = Math.max(w, Hyb.width(subtitle));
        for (final Row r : rows) w = Math.max(w, Hyb.width(r.label) + 14 + Hyb.width(r.value));
        for (final Note n : notes) w = Math.max(w, Hyb.width(n.text));
        int actionsW = 0;
        for (final Action a : actions) actionsW += (actionsW == 0 ? 0 : ACTION_GAP) + actionWidth(a);
        // Actions wrap rather than widen the panel past what the text needs, but never below one action.
        int widest = 0;
        for (final Action a : actions) widest = Math.max(widest, actionWidth(a));
        w = Math.max(w, Math.min(actionsW, Math.max(widest, Math.max(w, 150))));
        return Math.min(Math.max(w, 60), MAX_W + 40);
    }

    public int width() {
        return contentWidth() + 2 * PAD_X;
    }

    public int height() {
        int h = LINE * titleLines.size();
        if (subtitle != null) h += LINE;
        if (!rows.isEmpty()) h += 3 + rows.size() * LINE;
        if (!notes.isEmpty()) h += 3 + notes.size() * LINE;
        if (!actions.isEmpty()) h += 5 + 4 + actionLines(contentWidth()).size() * 12 - 2;
        return h + 2 * PAD_Y - 2;
    }

    // endregion

    // region Drawing

    /**
     * Starts drawing a panel over everything: no depth test, and no lighting (item rendering before it, NEI's list
     * among it, can leave lighting on, which dims flat colours). Pair with {@link #endPanel}.
     */
    public static void beginPanel() {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_LIGHTING_BIT);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_COLOR_MATERIAL);
    }

    public static void endPanel() {
        GL11.glPopAttrib();
    }

    /**
     * Factory Flow's tooltip panel: a hard dark edge, a lit top-left and a shadowed bottom-right, a soft drop shadow.
     */
    public static void chrome(final float x, final float y, final float w, final float h) {
        Hyb.rect(x + 1, y + 2, w + 1, h + 1, 0x24000000);
        Hyb.rect(x + 2, y + 3, w, h, 0x18000000);
        Hyb.rect(x, y, w, h, EDGE);
        Hyb.rect(x + 1, y + 1, w - 2, h - 2, BG);
        Hyb.rect(x + 1, y + 1, w - 2, 1, HI);
        Hyb.rect(x + 1, y + 1, 1, h - 2, HI);
        Hyb.rect(x + 1, y + h - 2, w - 2, 1, LO);
        Hyb.rect(x + w - 2, y + 1, 1, h - 2, LO);
    }

    /**
     * Draws the tip beside the pointer, below and to the right, flipped left or up where it would cross
     * {@code right} or {@code bottom}. Returns {x, y, w, h} of where it went.
     */
    public int[] drawNear(final int mouseX, final int mouseY, final int right, final int bottom) {
        final int w = width(), h = height();
        int x = mouseX + 12, y = mouseY + 12;
        if (x + w > right) x = Math.max(2, mouseX - 12 - w);
        if (y + h > bottom) y = Math.max(2, bottom - h);
        draw(x, y);
        return new int[] { x, y, w, h };
    }

    public void draw(final int x, final int y) {
        final int w = width(), h = height(), cw = contentWidth();
        Tip.beginPanel();
        chrome(x, y, w, h);
        final int left = x + PAD_X, right = left + cw;
        int ty = y + PAD_Y;
        for (final String line : titleLines) {
            Hyb.text(line, left, ty, titleColor);
            ty += LINE;
        }
        if (subtitle != null) {
            Hyb.text(subtitle, left, ty, SUBTLE);
            ty += LINE;
        }
        if (!rows.isEmpty()) {
            ty += 3;
            for (final Row r : rows) {
                Hyb.text(r.label, left, ty, SUBTLE);
                Hyb.textRight(r.value, right, ty, r.color);
                ty += LINE;
            }
        }
        if (!notes.isEmpty()) {
            ty += 3;
            for (final Note n : notes) {
                Hyb.text(n.text, left, ty, n.color);
                ty += LINE;
            }
        }
        if (!actions.isEmpty()) {
            ty += 3;
            Hyb.rect(left, ty, cw, 1, RULE);
            ty += 5;
            for (final List<Action> line : actionLines(cw)) {
                int ax = left;
                for (final Action a : line) {
                    mouse(a.input, ax, ty);
                    Hyb.text(a.label, ax + MOUSE_W + MOUSE_GAP, ty + 1, TEXT);
                    ax += actionWidth(a) + ACTION_GAP;
                }
                ty += 12;
            }
        }
        Tip.endPanel();
    }

    /** A mouse 7 by 10, the part that does the action lit; a key is a small keycap instead. */
    public static void mouse(final Input input, final float x, final float y) {
        final int line = SUBTLE, lit = TITLE;
        if (input == Input.KEY) {
            Hyb.rect(x, y + 1, 7, 7, line);
            Hyb.rect(x + 1, y + 2, 5, 5, BG);
            Hyb.rect(x + 2, y + 3, 3, 3, lit);
            return;
        }
        // Outline with clipped corners.
        Hyb.rect(x + 1, y, 5, 1, line);
        Hyb.rect(x + 1, y + 9, 5, 1, line);
        Hyb.rect(x, y + 1, 1, 8, line);
        Hyb.rect(x + 6, y + 1, 1, 8, line);
        // The line between the buttons, and the one under them.
        Hyb.rect(x + 3, y + 1, 1, 3, line);
        Hyb.rect(x + 1, y + 4, 5, 1, line);
        switch (input) {
            case LEFT -> Hyb.rect(x + 1, y + 1, 2, 3, lit);
            case RIGHT -> Hyb.rect(x + 4, y + 1, 2, 3, lit);
            case WHEEL, MIDDLE -> Hyb.rect(x + 3, y + 1, 1, 3, lit);
            case DRAG -> {
                Hyb.rect(x + 1, y + 1, 2, 3, lit);
                Hyb.rect(x + 2, y + 6, 3, 1, lit);
                Hyb.rect(x + 3, y + 5, 1, 3, lit);
            }
            default -> {}
        }
    }

    // endregion
}
