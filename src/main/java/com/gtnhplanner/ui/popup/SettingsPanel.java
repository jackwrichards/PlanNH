package com.gtnhplanner.ui.popup;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.gui.ScaledResolution;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.ui.PlannerSettings;
import com.gtnhplanner.ui.card.CardPaint;
import com.gtnhplanner.ui.sound.Sfx;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * The planner's settings (the top bar's gear): a box in the middle of the screen in the card's look, its sections down
 * the left and the chosen one's settings on the right, each with the control that fits it: a switch, a row of
 * choices, a slider, the minimap's corner. A change saves at once; the wheel over a control steps it, as on the cards.
 */
public final class SettingsPanel extends Widget<SettingsPanel> implements Interactable {

    /** How a setting is shown and set. */
    public enum Kind {
        /** On or off: a switch; a click anywhere on the row flips it. */
        SWITCH,
        /** One of a few named values, side by side. */
        CHOICE,
        /** One of many values in order, along a track. */
        SLIDER,
        /** A corner of the screen, picked on a little screen. */
        CORNER,
        /** Does something (its one name is the key's). */
        BUTTON
    }

    /**
     * A setting: its name, a quiet hint after it (a key, a unit), its control, the names of its values, which one it
     * has now and how to set one by its index.
     */
    public record Row(String label, @Nullable String hint, Kind kind, String[] names, IntSupplier at, IntConsumer to) {

        public String value() {
            return names[index()];
        }

        int index() {
            return Math.max(0, Math.min(names.length - 1, at.getAsInt()));
        }
    }

    private record Section(String name, List<Row> rows) {}

    // The box: a title bar, the sections on the left, the settings on the right.
    private static final int W = 340, TITLE = 22, SIDE = 96, SIDE_ROW = 18, ROW = 22, PAD = 6, MAX_ROWS = 6;
    private static final int H = TITLE + PAD + MAX_ROWS * ROW + PAD;
    // The controls, right-aligned in their row.
    private static final int RIGHT = W - 10, SWITCH_W = 22, SWITCH_H = 12, CHOICE_H = 16, TRACK_W = 96, VALUE_W = 30,
        SCREEN_W = 28, SCREEN_H = 18, CLOSE = 14;

    private static final int SIDE_FILL = 0xFF1D1F23, SIDE_PICKED = 0xFF2C2F35, SIDE_HOVER = 0xFF26282D,
        ROW_HOVER = 0xFF282A2F, CONTROL = 0xFF2A2C31, CONTROL_HOVER = 0xFF33363C, CONTROL_PICKED = 0xFF4A4D55,
        TRACK = 0xFF3A3C42, TRACK_FILLED = 0xFF8F939C, SWITCH_ON = 0xFF3E8F72;

    /** The section showing, kept while the game runs so the box opens where it was left. */
    private static int shown;

    private final List<Section> sections;
    /** The slider being dragged, or null. */
    @Nullable
    private Row dragging;

    private SettingsPanel(final List<Section> sections) {
        this.sections = sections;
        size(W, H);
    }

    /** Opens the settings in the middle of the screen. */
    public static void open(final ModularPanel parent) {
        final SettingsPanel settings = new SettingsPanel(sections());
        final Popup popup = new Popup("gtnhplanner_settings_panel", W, H) {

            @Override
            public void drawBackground(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
                // The board behind dims a little; the box in the card's colours, with the card's shadow.
                final ScaledResolution sr = resolution();
                Hyb.rect(-getArea().x, -getArea().y, sr.getScaledWidth(), sr.getScaledHeight(), 0x60000000);
                Hyb.dropShadow(0, 0, W, H);
                CardPaint.surface(W, H);
            }
        };
        popup.child(settings.pos(0, 0));
        final ScaledResolution sr = resolution();
        Popup.open(parent, popup, (sr.getScaledWidth() - W) / 2, (sr.getScaledHeight() - H) / 2);
    }

    private static ScaledResolution resolution() {
        final Minecraft mc = Minecraft.getMinecraft();
        return new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
    }

    // region The settings

    private static Row toggle(final String label, @Nullable final String hint, final BooleanSupplier get,
        final Consumer<Boolean> set) {
        return new Row(
            label,
            hint,
            Kind.SWITCH,
            new String[] { "Off", "On" },
            () -> get.getAsBoolean() ? 1 : 0,
            i -> set.accept(i == 1));
    }

    private static Row choice(final String label, @Nullable final String hint, final String[] names,
        final IntSupplier at, final IntConsumer to) {
        return new Row(label, hint, Kind.CHOICE, names, at, to);
    }

    private static List<Section> sections() {
        final List<Section> out = new ArrayList<>();

        final String[] seeThrough = new String[100 / PlannerSettings.SEE_THROUGH_STEP + 1];
        for (int i = 0; i < seeThrough.length; i++) seeThrough[i] = i * PlannerSettings.SEE_THROUGH_STEP + "%";
        out.add(
            new Section(
                "Board",
                List.of(
                    toggle(
                        "Names when zoomed out",
                        null,
                        PlannerSettings::zoomedOutNames,
                        PlannerSettings::setZoomedOutNames),
                    toggle(
                        "Circuit as an input",
                        null,
                        PlannerSettings::circuitAsInput,
                        PlannerSettings::setCircuitAsInput),
                    new Row(
                        "See-through",
                        null,
                        Kind.SLIDER,
                        seeThrough,
                        () -> PlannerSettings.seeThrough() / PlannerSettings.SEE_THROUGH_STEP,
                        i -> PlannerSettings.setSeeThrough(i * PlannerSettings.SEE_THROUGH_STEP)))));

        out.add(
            new Section(
                "Sound",
                List.of(
                    choice(
                        "Sounds",
                        null,
                        PlannerSettings.SOUND_NAMES,
                        PlannerSettings::soundIndex,
                        PlannerSettings::setSoundIndex))));

        final String[] zooms = new String[PlannerSettings.MINIMAP_ZOOMS.length];
        for (int i = 0; i < zooms.length; i++) zooms[i] = Math.round(PlannerSettings.MINIMAP_ZOOMS[i] * 100) + "%";
        final PlannerSettings.Corner[] corners = PlannerSettings.Corner.values();
        final String[] cornerNames = new String[corners.length];
        for (int i = 0; i < corners.length; i++) cornerNames[i] = corners[i].label;
        out.add(
            new Section(
                "Minimap",
                List.of(
                    toggle("Show the minimap", null, PlannerSettings::minimap, PlannerSettings::setMinimap),
                    choice(
                        "Size",
                        null,
                        PlannerSettings.MINIMAP_SIZE_NAMES,
                        PlannerSettings::minimapSizeIndex,
                        PlannerSettings::setMinimapSizeIndex),
                    choice(
                        "Shape",
                        null,
                        new String[] { "Circle", "Square" },
                        () -> PlannerSettings.minimapCircle() ? 0 : 1,
                        i -> PlannerSettings.setMinimapCircle(i == 0)),
                    new Row(
                        "Position",
                        null,
                        Kind.CORNER,
                        cornerNames,
                        () -> PlannerSettings.minimapCorner()
                            .ordinal(),
                        i -> PlannerSettings.setMinimapCorner(corners[i])),
                    new Row(
                        "Zoom",
                        null,
                        Kind.SLIDER,
                        zooms,
                        PlannerSettings::minimapZoomIndex,
                        PlannerSettings::setMinimapZoomIndex),
                    toggle(
                        "Centre on the machine you look at",
                        null,
                        PlannerSettings::minimapFollows,
                        PlannerSettings::setMinimapFollows))));

        final String[] ranges = new String[PlannerSettings.AR_RANGES.length];
        for (int i = 0; i < ranges.length; i++) ranges[i] = Integer.toString(PlannerSettings.AR_RANGES[i]);
        out.add(
            new Section(
                "In the world",
                List.of(
                    toggle(
                        "Show the plan over the world",
                        "Shift+Y",
                        PlannerSettings::arLens,
                        PlannerSettings::setArLens),
                    toggle(
                        "Cards only where you look",
                        "Shift+U",
                        PlannerSettings::arFocus,
                        PlannerSettings::setArFocus),
                    choice(
                        "Range, in blocks",
                        null,
                        ranges,
                        PlannerSettings::arRangeIndex,
                        PlannerSettings::setArRangeIndex))));

        out.add(
            new Section(
                "Keys",
                List.of(new Row("Set them in Controls", null, Kind.BUTTON, new String[] { "Open" }, () -> 0, i -> {
                    final Minecraft mc = Minecraft.getMinecraft();
                    mc.displayGuiScreen(new GuiControls(mc.currentScreen, mc.gameSettings));
                }))));
        return out;
    }

    // endregion

    // region Where things are (widget-local)

    private Section section() {
        shown = Math.max(0, Math.min(sections.size() - 1, shown));
        return sections.get(shown);
    }

    private static int rowY(final int i) {
        return TITLE + PAD + i * ROW;
    }

    private static int[] sideRect(final int i) {
        return new int[] { 1, TITLE + PAD + i * SIDE_ROW, SIDE - 1, SIDE_ROW };
    }

    private static int[] closeRect() {
        return new int[] { W - 4 - CLOSE, (TITLE - CLOSE) / 2, CLOSE, CLOSE };
    }

    /** The control's box in a row at {@code y}. */
    private static int[] controlRect(final Row row, final int y) {
        return switch (row.kind()) {
            case SWITCH -> new int[] { RIGHT - SWITCH_W, y + (ROW - SWITCH_H) / 2, SWITCH_W, SWITCH_H };
            case CHOICE -> {
                final int w = choicesWidth(row);
                yield new int[] { RIGHT - w, y + (ROW - CHOICE_H) / 2, w, CHOICE_H };
            }
            case SLIDER -> new int[] { RIGHT - VALUE_W - 6 - TRACK_W, y + 4, TRACK_W, ROW - 8 };
            case CORNER -> new int[] { RIGHT - SCREEN_W, y + (ROW - SCREEN_H) / 2, SCREEN_W, SCREEN_H };
            case BUTTON -> {
                final int w = Hyb.width(row.names()[0]) + 16;
                yield new int[] { RIGHT - w, y + (ROW - CHOICE_H) / 2, w, CHOICE_H };
            }
        };
    }

    private static int choiceWidth(final String name) {
        return Hyb.width(name) + 12;
    }

    private static int choicesWidth(final Row row) {
        int w = 1;
        for (final String n : row.names()) w += choiceWidth(n) + 1;
        return w;
    }

    /** One choice's box, {@code i} of the row's names, in a row at {@code y}. */
    private static int[] choiceRect(final Row row, final int y, final int i) {
        final int[] c = controlRect(row, y);
        if (row.kind() == Kind.CORNER) {
            // The screen's corners: top right, top left, bottom right, bottom left (the order of Corner).
            final int cw = 10, ch = 6;
            final boolean right = i == 0 || i == 2, bottom = i >= 2;
            return new int[] { right ? c[0] + c[2] - 2 - cw : c[0] + 2, bottom ? c[1] + c[3] - 2 - ch : c[1] + 2, cw,
                ch };
        }
        int x = c[0] + 1;
        for (int k = 0; k < i; k++) x += choiceWidth(row.names()[k]) + 1;
        return new int[] { x, c[1] + 1, choiceWidth(row.names()[i]), c[3] - 2 };
    }

    private static boolean in(final int[] r, final int x, final int y) {
        return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }

    // endregion

    // region For the tour and the harness (GUI coordinates)

    private int[] abs(final int[] r) {
        return r == null ? null : new int[] { getArea().x + r[0], getArea().y + r[1], r[2], r[3] };
    }

    private int rowIndex(final String label) {
        final List<Row> rows = section().rows();
        for (int i = 0; i < rows.size(); i++) if (rows.get(i)
            .label()
            .contains(label)) return i;
        return -1;
    }

    /** The GUI rectangle {x, y, w, h} of the showing setting whose name contains {@code label}; null for none. */
    public int[] rowRect(final String label) {
        final int i = rowIndex(label);
        return i < 0 ? null : abs(new int[] { SIDE + 1, rowY(i), W - SIDE - 2, ROW });
    }

    /** The GUI rectangle of one value's choice on a showing setting (a corner too); null for none. */
    public int[] choiceRect(final String label, final String choice) {
        final int i = rowIndex(label);
        if (i < 0) return null;
        final Row row = section().rows()
            .get(i);
        for (int k = 0; k < row.names().length; k++)
            if (row.names()[k].equals(choice)) return abs(choiceRect(row, rowY(i), k));
        return null;
    }

    /** The GUI rectangle of a section's name on the left; null for none. */
    public int[] sectionRect(final String name) {
        for (int i = 0; i < sections.size(); i++) if (sections.get(i)
            .name()
            .equals(name)) return abs(sideRect(i));
        return null;
    }

    /** What a setting shows now, by its name, in whichever section; null for none. */
    public String value(final String label) {
        for (final Section s : sections) for (final Row r : s.rows()) if (r.label()
            .contains(label)) return r.value();
        return null;
    }

    // endregion

    // region Drawing

    private int mouseX() {
        return getContext().getAbsMouseX() - getArea().x;
    }

    private int mouseY() {
        return getContext().getAbsMouseY() - getArea().y;
    }

    @Override
    public boolean canHover() {
        return true;
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final boolean hovering = isHovering();
        final int mx = hovering ? mouseX() : -1, my = hovering ? mouseY() : -1;
        final List<Row> rows = section().rows();

        // The title, and the key that closes the box.
        Hyb.text("Settings", 10, (TITLE - 8) / 2f + 1, Hyb.INK);
        final int[] close = closeRect();
        final boolean closeHot = in(close, mx, my);
        if (closeHot) Hyb.rect(close[0], close[1], close[2], close[3], CONTROL_HOVER);
        cross(close[0] + close[2] / 2f, close[1] + close[3] / 2f, closeHot ? Hyb.INK : Hyb.MUTED);
        Hyb.rect(1, TITLE, W - 2, 1, CardPaint.HAIR);

        // The sections, the one showing marked in gold.
        Hyb.rect(1, TITLE + 1, SIDE - 1, H - TITLE - 2, SIDE_FILL);
        Hyb.rect(SIDE, TITLE + 1, 1, H - TITLE - 2, CardPaint.HAIR);
        for (int i = 0; i < sections.size(); i++) {
            final int[] r = sideRect(i);
            final boolean picked = i == shown, hot = !picked && in(r, mx, my);
            if (picked || hot) Hyb.rect(r[0], r[1], r[2], r[3], picked ? SIDE_PICKED : SIDE_HOVER);
            if (picked) Hyb.rect(r[0], r[1], 2, r[3], Hyb.GOLD);
            Hyb.text(
                sections.get(i)
                    .name(),
                r[0] + 9,
                r[1] + (SIDE_ROW - 8) / 2f + 1,
                picked || hot ? Hyb.INK : Hyb.MUTED);
        }

        // The settings, a hairline between them.
        for (int i = 0; i < rows.size(); i++) {
            final Row row = rows.get(i);
            final int y = rowY(i);
            final boolean rowHot = mx > SIDE && my >= y && my < y + ROW && mx < W - 1;
            if (rowHot) Hyb.rect(SIDE + 1, y, W - SIDE - 2, ROW, ROW_HOVER);
            if (i > 0) Hyb.rect(SIDE + 8, y, W - SIDE - 16, 1, CardPaint.HAIR);
            Hyb.text(row.label(), SIDE + 10, y + (ROW - 8) / 2f + 1, Hyb.INK);
            final int hintX = SIDE + 10 + Hyb.width(row.label()) + 5;
            if (row.hint() != null && hintX + Hyb.width(row.hint()) + 6 <= controlRect(row, y)[0])
                Hyb.text(row.hint(), hintX, y + (ROW - 8) / 2f + 1, Hyb.MUTED);
            drawControl(row, y, rowHot, mx, my);
        }
    }

    private static void cross(final float cx, final float cy, final int colour) {
        for (int d = -3; d <= 3; d++) {
            Hyb.rect(cx + d - 0.5f, cy + d - 0.5f, 1, 1, colour);
            Hyb.rect(cx + d - 0.5f, cy - d - 0.5f, 1, 1, colour);
        }
    }

    private void drawControl(final Row row, final int y, final boolean rowHot, final int mx, final int my) {
        final int[] c = controlRect(row, y);
        final int at = row.index();
        switch (row.kind()) {
            case SWITCH -> {
                final boolean on = at == 1;
                pill(c[0], c[1], c[2], c[3], on ? SWITCH_ON : rowHot ? 0xFF44464D : TRACK);
                final int k = c[3] - 4;
                pill(on ? c[0] + c[2] - 2 - k : c[0] + 2, c[1] + 2, k, k, on ? Hyb.INK : 0xFF9A9CA4);
            }
            case CHOICE, BUTTON -> {
                Hyb.rect(c[0], c[1], c[2], c[3], CardPaint.EDGE);
                final int n = row.names().length;
                for (int i = 0; i < n; i++) {
                    final int[] r = row.kind() == Kind.BUTTON ? new int[] { c[0] + 1, c[1] + 1, c[2] - 2, c[3] - 2 }
                        : choiceRect(row, y, i);
                    final boolean picked = row.kind() == Kind.CHOICE && i == at, hot = in(r, mx, my);
                    Hyb.rect(r[0], r[1], r[2], r[3], picked ? CONTROL_PICKED : hot ? CONTROL_HOVER : CONTROL);
                    Hyb.textCentered(
                        row.names()[i],
                        r[0] + r[2] / 2f,
                        r[1] + (r[3] - 8) / 2f + 1,
                        picked || hot || row.kind() == Kind.BUTTON ? Hyb.INK : Hyb.MUTED);
                }
            }
            case SLIDER -> {
                final float f = row.names().length <= 1 ? 0 : at / (float) (row.names().length - 1);
                final int ty = c[1] + c[3] / 2 - 1, kx = Math.round(c[0] + f * (c[2] - 6));
                Hyb.rect(c[0], ty, c[2], 3, TRACK);
                Hyb.rect(c[0], ty, kx - c[0] + 3, 3, TRACK_FILLED);
                final boolean hot = dragging == row || in(c, mx, my);
                Hyb.rect(kx, c[1], 6, c[3], CardPaint.EDGE);
                Hyb.rect(kx + 1, c[1] + 1, 4, c[3] - 2, hot ? 0xFFFFFFFF : Hyb.INK);
                Hyb.textRight(row.value(), RIGHT, y + (ROW - 8) / 2f + 1, Hyb.INK);
            }
            case CORNER -> {
                // A little screen with a key in each corner; the minimap's lit.
                Hyb.rect(c[0], c[1], c[2], c[3], CardPaint.EDGE);
                Hyb.rect(c[0] + 1, c[1] + 1, c[2] - 2, c[3] - 2, CONTROL);
                for (int i = 0; i < row.names().length; i++) {
                    final int[] r = choiceRect(row, y, i);
                    final boolean hot = in(r, mx, my) || i != at && in(c, mx, my) && nearest(row, y, mx, my) == i;
                    Hyb.rect(r[0], r[1], r[2], r[3], i == at ? Hyb.GOLD : hot ? 0xFF6A6D76 : TRACK);
                }
                Hyb.textRight(row.value(), c[0] - 6, y + (ROW - 8) / 2f + 1, Hyb.MUTED);
            }
        }
    }

    /** A box with its corners off, a pixel's rounding. */
    private static void pill(final int x, final int y, final int w, final int h, final int colour) {
        Hyb.rect(x + 1, y, w - 2, h, colour);
        Hyb.rect(x, y + 1, 1, h - 2, colour);
        Hyb.rect(x + w - 1, y + 1, 1, h - 2, colour);
    }

    /** The corner of the little screen nearest the mouse. */
    private static int nearest(final Row row, final int y, final int mx, final int my) {
        final int[] c = controlRect(row, y);
        final boolean right = mx >= c[0] + c[2] / 2, bottom = my >= c[1] + c[3] / 2;
        return (bottom ? 2 : 0) + (right ? 0 : 1);
    }

    // endregion

    // region Input

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (mouseButton != 0 && mouseButton != 1) return Result.IGNORE;
        final int mx = mouseX(), my = mouseY();
        if (in(closeRect(), mx, my)) {
            if (getPanel() != null) getPanel().closeIfOpen();
            Sfx.CLOSE.play();
            return Result.SUCCESS;
        }
        for (int i = 0; i < sections.size(); i++) if (in(sideRect(i), mx, my)) {
            if (i != shown) {
                shown = i;
                Hyb.click();
            }
            return Result.SUCCESS;
        }
        final List<Row> rows = section().rows();
        for (int i = 0; i < rows.size(); i++) {
            final Row row = rows.get(i);
            final int y = rowY(i);
            if (my < y || my >= y + ROW || mx <= SIDE) continue;
            final int[] c = controlRect(row, y);
            switch (row.kind()) {
                // The whole row flips a switch.
                case SWITCH -> set(row, 1 - row.index());
                case CHOICE -> {
                    for (int k = 0; k < row.names().length; k++) if (in(choiceRect(row, y, k), mx, my)) set(row, k);
                }
                case CORNER -> {
                    if (in(c, mx, my)) set(row, nearest(row, y, mx, my));
                }
                case SLIDER -> {
                    if (mx >= c[0] - 4 && mx < c[0] + c[2] + 4) {
                        dragging = row;
                        slide(c);
                    }
                }
                case BUTTON -> {
                    if (in(c, mx, my)) {
                        Hyb.click();
                        row.to()
                            .accept(0);
                    }
                }
            }
            return Result.SUCCESS;
        }
        return Result.SUCCESS;
    }

    @Override
    public void onMouseDrag(final int mouseButton, final long timeSinceClick) {
        if (dragging == null) return;
        final List<Row> rows = section().rows();
        final int i = rows.indexOf(dragging);
        if (i >= 0) slide(controlRect(dragging, rowY(i)));
    }

    @Override
    public boolean onMouseRelease(final int mouseButton) {
        dragging = null;
        return true;
    }

    /** The dragged slider to the value under the mouse. */
    private void slide(final int[] track) {
        final int n = dragging.names().length;
        final float f = (mouseX() - track[0] - 3) / (float) Math.max(1, track[2] - 6);
        set(dragging, Math.round(Math.max(0, Math.min(1, f)) * (n - 1)));
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        final int mx = mouseX(), my = mouseY(), step = direction == UpOrDown.UP ? 1 : -1;
        if (mx < SIDE && my > TITLE) {
            // Over the sections, the wheel goes through them.
            final int next = Math.max(0, Math.min(sections.size() - 1, shown - step));
            if (next != shown) {
                shown = next;
                Sfx.TICK.play(step > 0 ? 1.12f : 0.9f);
            }
            return true;
        }
        final List<Row> rows = section().rows();
        for (int i = 0; i < rows.size(); i++) {
            final Row row = rows.get(i);
            final int y = rowY(i);
            if (my < y || my >= y + ROW || mx <= SIDE) continue;
            final int n = row.names().length;
            switch (row.kind()) {
                case SWITCH -> set(row, step > 0 ? 1 : 0);
                case CORNER -> set(row, ((row.index() + step) % n + n) % n);
                case CHOICE, SLIDER -> set(row, Math.max(0, Math.min(n - 1, row.index() + step)));
                case BUTTON -> {}
            }
            return true;
        }
        return true;
    }

    /** Sets a row to its {@code i}th value, with the sound of what changed (nothing when nothing did). */
    private static void set(final Row row, final int i) {
        if (i == row.index()) return;
        final boolean up = i > row.index();
        row.to()
            .accept(i);
        switch (row.kind()) {
            case SWITCH -> (i == 1 ? Sfx.TOGGLE_ON : Sfx.TOGGLE_OFF).play();
            case SLIDER -> Sfx.TICK.play(0.85f + 0.4f * i / Math.max(1, row.names().length - 1));
            case CHOICE, CORNER -> {
                // The Sounds row clicks at its new loudness.
                if (row.label()
                    .equals("Sounds")) Hyb.click();
                else Sfx.TICK.play(up ? 1.12f : 0.9f);
            }
            case BUTTON -> {}
        }
    }

    // endregion
}
