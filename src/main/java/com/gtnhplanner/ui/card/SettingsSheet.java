package com.gtnhplanner.ui.card;

import java.util.ArrayList;
import java.util.List;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * A card's settings (its gear key), in the clean card's look: "Settings" faint at the top, then the settings by
 * section in one column, or two side by side when there are many, each a row with a pin, its name and its control (a
 * switch, a number with arrows, or a list), and a line on how it works. Pinned settings show on the card as chips, on
 * every card of the machine. It stays open while settings change, and reads them afresh every frame.
 */
public final class SettingsSheet extends Popup {

    static final int COL = 236, HEAD = 18, HEADING = 16, ROW = 18, FOOT = 16;
    /** At most this many settings in one column; more go in two. */
    private static final int ONE_COLUMN = 7;

    private SettingsSheet(final RecipeCard card, final Sheet sheet) {
        super("gtnhplanner_card_settings", sheet.w, sheet.h);
        child(new Body(card, this, sheet).pos(0, 0));
    }

    /** Opens the card's settings with its top left at a screen point (nudged onto the screen). */
    static void open(final RecipeCard card, final int x, final int y) {
        final CardModel m = card.model();
        if (m == null) return;
        Popup.open(card.getPanel(), new SettingsSheet(card, Sheet.of(SettingControls.of(m))), x, y);
    }

    @Override
    public void drawBackground(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final int w = getArea().width, h = getArea().height;
        Hyb.dropShadow(0, 0, w, h);
        CardPaint.surface(w, h);
    }

    /** A line of the sheet, where it is: a section's heading ({@code control} null) or a setting. */
    private record Line(String heading, SettingControls.Control control, int x, int y) {}

    /** The sheet laid out: its lines, its size, and how many columns. */
    private record Sheet(List<Line> lines, int w, int h, int columns) {

        static Sheet of(final List<SettingControls.Control> controls) {
            final List<List<SettingControls.Control>> sections = new ArrayList<>();
            String group = null;
            for (final SettingControls.Control c : controls) {
                if (!c.group()
                    .equals(group)) {
                    group = c.group();
                    sections.add(new ArrayList<>());
                }
                sections.get(sections.size() - 1)
                    .add(c);
            }
            final int columns = controls.size() > ONE_COLUMN && sections.size() > 1 ? 2 : 1;
            final int[] height = new int[columns];
            final List<Line> lines = new ArrayList<>();
            for (final List<SettingControls.Control> section : sections) {
                // Each section goes in the shorter column.
                int col = 0;
                for (int k = 1; k < columns; k++) if (height[k] < height[col]) col = k;
                final int x = col * (COL + 1);
                int y = HEAD + height[col];
                lines.add(
                    new Line(
                        section.get(0)
                            .group(),
                        null,
                        x,
                        y));
                y += HEADING;
                for (final SettingControls.Control c : section) {
                    lines.add(new Line(null, c, x, y));
                    y += ROW;
                }
                height[col] = y - HEAD + 4;
            }
            int tallest = ROW;
            for (final int h : height) tallest = Math.max(tallest, h);
            return new Sheet(lines, columns * COL + (columns - 1), HEAD + tallest + foot(columns) + 4, columns);
        }

        /** The line on how it works: two short lines in one column, one long line across two. */
        static int foot(final int columns) {
            return columns == 1 ? FOOT + 10 : FOOT;
        }
    }

    private static final class Body extends Widget<Body> implements Interactable {

        private final RecipeCard card;
        private final SettingsSheet popup;
        private Sheet sheet;

        Body(final RecipeCard card, final SettingsSheet popup, final Sheet sheet) {
            this.card = card;
            this.popup = popup;
            this.sheet = sheet;
            size(sheet.w, sheet.h);
        }

        /** Reads the settings afresh; when some come or go (a multiblock's own appear when it is one), resizes. */
        @Override
        public void onUpdate() {
            super.onUpdate();
            final CardModel m = card.model();
            if (m == null) return;
            final Sheet next = Sheet.of(SettingControls.of(m));
            if (next.w != sheet.w || next.h != sheet.h) {
                size(next.w, next.h);
                popup.size(next.w, next.h);
                popup.scheduleResize();
            }
            sheet = next;
        }

        @Override
        public boolean canHover() {
            return true;
        }

        private int mouseX() {
            return getContext().getAbsMouseX() - getArea().x;
        }

        private int mouseY() {
            return getContext().getAbsMouseY() - getArea().y;
        }

        /** The setting's line under the mouse, or null. */
        private Line lineAtMouse() {
            if (!isHovering()) return null;
            final int mx = mouseX(), my = mouseY();
            for (final Line l : sheet.lines) {
                if (l.control() != null && mx >= l.x() && mx < l.x() + COL && my >= l.y() && my < l.y() + ROW) return l;
            }
            return null;
        }

        // region Drawing

        @Override
        public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
            final CardModel m = card.model();
            if (m == null) return;
            Hyb.text("Settings", 8, 6, Hyb.MUTED);
            final Line hot = lineAtMouse();
            final int mx = mouseX();
            final int fy = sheet.h - Sheet.foot(sheet.columns) - 2;
            if (sheet.columns > 1) Hyb.rect(COL, HEAD + 2, 1, fy - HEAD - 4, CardPaint.HAIR);
            for (final Line l : sheet.lines) {
                if (l.control() == null) {
                    Hyb.rect(l.x() + 8, l.y() + 1, COL - 16, 1, CardPaint.HAIR);
                    Hyb.text(l.heading(), l.x() + 8, l.y() + 5, 0xFF6E7179);
                } else row(m, l, l == hot, mx - l.x());
            }
            Hyb.rect(1, fy, sheet.w - 2, 1, CardPaint.HAIR);
            if (sheet.columns == 1) {
                Hyb.text("Pinned settings show on the card.", 8, fy + 5, 0xFF6E7179);
                Hyb.text("Click, right-click or scroll to change.", 8, fy + 15, 0xFF6E7179);
            } else Hyb.text(
                "Pinned settings show on the card. Click, right-click or scroll to change.",
                8,
                fy + 5,
                0xFF6E7179);
        }

        private void row(final CardModel m, final Line l, final boolean hot, final int mx) {
            final SettingControls.Control c = l.control();
            final int x = l.x(), y = l.y();
            if (hot && c.enabled()) Hyb.rect(x + 1, y, COL - 2, ROW, 0x0DFFFFFF);
            final boolean pinned = SettingPins.pinned(
                card.session()
                    .graph(),
                m,
                c);
            pinGlyph(x + 6, y + 4, pinned ? Hyb.LIT : hot && mx < 16 ? Hyb.INK : hot ? Hyb.MUTED : 0xFF44474F);
            final int[] box = controlBox(c);
            Hyb.text(Hyb.fit(c.label(), box[0] - 18 - 6), x + 18, y + 5, c.enabled() ? Hyb.INK : 0xFF5A5C65);
            control(c, x, y, hot, mx);
        }

        /** The control at the row's right, column-local: {x, w}, by type. */
        private static int[] controlBox(final SettingControls.Control c) {
            final int right = COL - 8;
            final int w = switch (c.type()) {
                case TOGGLE -> 22;
                case READING -> Hyb.width(c.value());
                case NUMBER -> Math.max(52, Hyb.width(c.value()) + 28);
                default -> Math.min(110, Math.max(56, (c.icon() != null ? 15 : 0) + Hyb.width(c.value()) + 20));
            };
            return new int[] { right - w, w };
        }

        private void control(final SettingControls.Control c, final int lx, final int y, final boolean hot,
            final int mx) {
            final int[] box = controlBox(c);
            final int x = lx + box[0], w = box[1];
            final int cx = mx - box[0];
            final boolean live = c.enabled();
            switch (c.type()) {
                case TOGGLE -> {
                    final boolean on = "On".equals(c.value());
                    Hyb.roundRect(x, y + 4, 22, 10, 5, !live ? 0xFF2C2E33 : on ? 0xFF3FAE5C : 0xFF3A3C42);
                    Hyb.roundRect(on ? x + 13 : x + 1, y + 5, 8, 8, 4, live ? 0xFFF2F3F5 : 0xFF6E7179);
                }
                case READING -> Hyb.text(c.value(), x, y + 5, Hyb.INK);
                case NUMBER -> {
                    well(x, y, w, hot && live && cx >= 11 && cx < w - 11);
                    arrow(x + 4, y + 7, false, !live ? 0xFF44474F : hot && cx >= 0 && cx < 11 ? Hyb.INK : Hyb.MUTED);
                    arrow(
                        x + w - 7,
                        y + 7,
                        true,
                        !live ? 0xFF44474F : hot && cx >= w - 11 && cx < w ? Hyb.INK : Hyb.MUTED);
                    Hyb.textCentered(c.value(), x + w / 2f, y + 5, live ? Hyb.INK : 0xFF74767E);
                }
                default -> {
                    well(x, y, w, hot && live && cx >= 0);
                    int tx = x + 5;
                    if (c.icon() != null) {
                        Hyb.item(c.icon(), tx, y + 3, 12, 0);
                        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_LIGHTING);
                        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
                        tx += 15;
                    }
                    Hyb.text(
                        Hyb.fit(c.value(), x + w - 12 - tx),
                        tx,
                        y + 5,
                        c.warn() ? Hyb.RED_INK : live ? Hyb.INK : 0xFF74767E);
                    chevronDown(x + w - 9, y + 8, live ? Hyb.MUTED : 0xFF44474F);
                }
            }
        }

        private static void well(final int x, final int y, final int w, final boolean hot) {
            Hyb.rect(x, y + 2, w, ROW - 4, CardPaint.EDGE);
            Hyb.rect(x + 1, y + 3, w - 2, ROW - 6, hot ? 0xFF2E3036 : 0xFF1B1D21);
        }

        private static void arrow(final int x, final int y, final boolean right, final int color) {
            for (int i = 0; i < 3; i++) {
                final int len = 5 - 2 * i;
                Hyb.rect(right ? x + i : x + 2 - i, y + i, 1, len, color);
            }
        }

        private static void chevronDown(final int x, final int y, final int color) {
            Hyb.rect(x, y, 5, 1, color);
            Hyb.rect(x + 1, y + 1, 3, 1, color);
            Hyb.rect(x + 2, y + 2, 1, 1, color);
        }

        /** A pushpin, 7 wide and 10 tall. */
        private static void pinGlyph(final int x, final int y, final int color) {
            Hyb.rect(x + 1, y, 5, 1, color);
            Hyb.rect(x + 2, y + 1, 3, 2, color);
            Hyb.rect(x + 1, y + 3, 5, 1, color);
            Hyb.rect(x, y + 4, 7, 1, color);
            Hyb.rect(x + 3, y + 5, 1, 4, color);
        }

        // endregion

        // region Input

        @Override
        public Result onMousePressed(final int mouseButton) {
            final Line l = lineAtMouse();
            final CardModel m = card.model();
            if (l == null || m == null || mouseButton > 1) return Result.ACCEPT;
            final SettingControls.Control c = l.control();
            final int mx = mouseX() - l.x();
            if (mx < 16) {
                if (mouseButton == 0) {
                    SettingPins.toggle(
                        card.session()
                            .graph(),
                        m,
                        c);
                    Hyb.click();
                }
                return Result.SUCCESS;
            }
            if (!c.enabled() || c.type() == SettingControls.Type.READING) return Result.ACCEPT;
            final int[] box = controlBox(c);
            final int sx = getArea().x + l.x() + box[0], sy = getArea().y + l.y() + ROW;
            if (c.type() == SettingControls.Type.NUMBER && mouseButton == 0) {
                if (mx >= box[0] && mx < box[0] + 11) card.stepSetting(c, -1);
                else if (mx >= box[0] + box[1] - 11 && mx < box[0] + box[1]) card.stepSetting(c, 1);
                else card.pressSetting(c, 0, sx, sy);
            } else card.pressSetting(c, mouseButton, sx, sy);
            Hyb.click();
            return Result.SUCCESS;
        }

        @Override
        public boolean onMouseScroll(final UpOrDown direction, final int amount) {
            final Line l = lineAtMouse();
            if (l == null) return false;
            final SettingControls.Control c = l.control();
            if (!c.enabled() || c.type() == SettingControls.Type.READING) return false;
            card.stepSetting(c, direction == UpOrDown.UP ? 1 : -1);
            return true;
        }

        // endregion
    }
}
