package com.sbancuz.plannh.ui.drawer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.IDraggable;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.sbancuz.plannh.data.flowchart.Drawer;
import com.sbancuz.plannh.ui.BoardSession;
import com.sbancuz.plannh.ui.canvas.BoardCanvas;
import com.sbancuz.plannh.ui.popup.NumberPopup;
import com.sbancuz.plannh.ui.popup.PickList;
import com.sbancuz.plannh.ui.popup.Popup;
import com.sbancuz.plannh.ui.theme.Fmt;
import com.sbancuz.plannh.ui.theme.Hyb;

/**
 * A drawer on the board, Factory Flow style: what the player brings in (source) or wants (product), or where surplus
 * goes (byproduct, trash). Title bar with delete and cycle keys, the resource and the rate the plan moves through it,
 * then for sources and products the rule and its rate.
 */
public final class DrawerCard extends Widget<DrawerCard> implements Interactable, IDraggable {

    public static final int W = 136;
    public static final int H = 62;
    /** Where wires meet a drawer: its left edge for kinds that take outputs, its right edge for sources. */
    public static final int ANCHOR_Y = 30;

    public enum Part {
        DELETE,
        CYCLE,
        RULE,
        RATE,
        BODY
    }

    private static final int KEY = 12;
    private static final int RULE_X = 5, RULE_W = 56, ROW_Y = 43, ROW_H = 14;
    private static final int RATE_X = 63, RATE_W = W - 5 - 63;

    private final BoardSession session;
    public final UUID drawerId;
    private DrawerModel model;

    private boolean moving;

    public DrawerCard(final BoardSession session, final UUID drawerId) {
        this.session = session;
        this.drawerId = drawerId;
        size(W, H);
        refresh();
    }

    public DrawerModel model() {
        return model;
    }

    public void refresh() {
        final DrawerModel next = session.drawerModel(drawerId);
        if (next == null) return;
        model = next;
        pos(next.drawer.getX(), next.drawer.getY());
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (session.drawerModel(drawerId) != model) refresh();
    }

    @Override
    public boolean canHover() {
        return true;
    }

    // region Geometry

    public static int frameColor(final Drawer.Kind kind) {
        return switch (kind) {
            case SOURCE -> 0xFFC0504D;
            case PRODUCT -> 0xFF3FA36B;
            case BYPRODUCT -> 0xFFB58B3A;
            case TRASH -> 0xFF6A6C74;
        };
    }

    private BoardCanvas canvas() {
        return getParent() instanceof final BoardCanvas c ? c : null;
    }

    private float localX() {
        final BoardCanvas c = canvas();
        return c == null ? -1 : c.worldX(getContext().getAbsMouseX()) - model.drawer.getX();
    }

    private float localY() {
        final BoardCanvas c = canvas();
        return c == null ? -1 : c.worldY(getContext().getAbsMouseY()) - model.drawer.getY();
    }

    private static boolean in(final float x, final float y, final int rx, final int ry, final int rw, final int rh) {
        return x >= rx && y >= ry && x < rx + rw && y < ry + rh;
    }

    private boolean hasRule() {
        return model.kind.hasRule();
    }

    public Part partAt(final float x, final float y) {
        if (in(x, y, 4, 4, KEY, KEY)) return Part.DELETE;
        if (model.kind != Drawer.Kind.SOURCE && in(x, y, W - 4 - KEY, 4, KEY, KEY)) return Part.CYCLE;
        if (hasRule() && in(x, y, RULE_X, ROW_Y, RULE_W, ROW_H)) return Part.RULE;
        if (hasRule() && in(x, y, RATE_X, ROW_Y, RATE_W, ROW_H)) return Part.RATE;
        return Part.BODY;
    }

    /** Card-local rectangle of a part, or null when this kind has none; for the dev harness. */
    public int[] partRect(final Part part) {
        if (model == null) return null;
        return switch (part) {
            case DELETE -> new int[] { 4, 4, KEY, KEY };
            case CYCLE -> model.kind == Drawer.Kind.SOURCE ? null : new int[] { W - 4 - KEY, 4, KEY, KEY };
            case RULE -> hasRule() ? new int[] { RULE_X, ROW_Y, RULE_W, ROW_H } : null;
            case RATE -> hasRule() ? new int[] { RATE_X, ROW_Y, RATE_W, ROW_H } : null;
            case BODY -> new int[] { 0, 0, W, H };
        };
    }

    public Part partUnderMouse() {
        if (model == null) return null;
        final float x = localX(), y = localY();
        return x < 0 || y < 0 || x >= W || y >= H ? null : partAt(x, y);
    }

    private int screenX(final int lx) {
        final BoardCanvas c = canvas();
        return c == null ? 0 : c.screenX(model.drawer.getX() + lx);
    }

    private int screenY(final int ly) {
        final BoardCanvas c = canvas();
        return c == null ? 0 : c.screenY(model.drawer.getY() + ly);
    }

    // endregion

    // region Drawing

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final DrawerModel m = model;
        if (m == null) return;
        final float z = context.getCurrentDrawingZ();
        final Part hover = isHovering() ? partAt(localX(), localY()) : null;
        final BoardCanvas board = canvas();
        final int ring = board != null && board.drawerAcceptsDrag(m.drawer) ? Hyb.PRODUCT_INK
            : m.drawer.getResourceKey()
                .equals(session.hoverKey()) ? Hyb.GOLD : frameColor(m.kind);

        if (canvas() != null && canvas().isCarried(drawerId)) {
            Hyb.rect(5, 7, W, H, 0x50000000);
            Hyb.rect(3, 4, W, H, 0x40000000);
        }
        if (session.isSelected(drawerId)) Hyb.ring(-3, -3, W + 6, H + 6, 2, Hyb.SELECTION);
        Hyb.rect(0, 0, W, H, ring);
        if (session.graph()
            .getZoom() <= com.sbancuz.plannh.ui.card.RecipeCard.GLANCE_ZOOM) {
            drawGlance(m, z, ring);
            return;
        }
        Hyb.rect(1, 1, W - 2, H - 2, Hyb.FRAME);
        Hyb.rect(1, 1, W - 2, 1, Hyb.HIGHLIGHT);
        Hyb.rect(1, H - 2, W - 2, 1, Hyb.SHADOW);
        // Title band in the kind's colour, dimmed.
        Hyb.rect(1, 1, W - 2, 18, (ring & 0x00FFFFFF) | 0x55000000);

        key(4, 4, hover == Part.DELETE);
        Hyb.rect(4 + 3, 4 + 5, KEY - 6, 2, Hyb.INK);
        if (m.kind != Drawer.Kind.SOURCE) {
            key(W - 4 - KEY, 4, hover == Part.CYCLE);
            cycleGlyph(W - 4 - KEY, 4);
        }
        final int titleL = 4 + KEY + 3, titleR = m.kind == Drawer.Kind.SOURCE ? W - 4 : W - 4 - KEY - 3;
        Hyb.textCentered(Hyb.fit(m.label, titleR - titleL), (titleL + titleR) / 2f, 6, 0xFFFFFFFF);

        // The resource and what the plan moves through it.
        Hyb.slot(5, 21, m.isFluid());
        if (m.isFluid()) Hyb.fluid(m.fluid, 6, 22, 16, z);
        else Hyb.item(m.item, 6, 22, 16, z);
        final Fmt.RateUnit unit = session.rateUnit();
        final String sign = m.rate <= 0 ? "" : m.kind == Drawer.Kind.SOURCE ? "-" : "+";
        final String number = sign + Fmt.compact(m.rate * unit.perSecond);
        final String suffix = (m.isFluid() ? " L" : "") + unit.suffix;
        final int color = m.rate <= 0 ? Hyb.MUTED : m.kind == Drawer.Kind.SOURCE ? Hyb.SOURCE_INK : Hyb.PRODUCT_INK;
        final int room = W - 28 - 5;
        final boolean big = Hyb.width(number) * Hyb.FIGURE + Hyb.width(suffix) + 2 <= room;
        if (big) {
            Hyb.text(number, 28, 25, Hyb.FIGURE, color);
            Hyb.text(suffix, 28 + Hyb.width(number) * Hyb.FIGURE + 2, 29, Hyb.MUTED);
        } else {
            Hyb.text(Hyb.fit(number + suffix, room), 28, 27, color);
        }

        if (hasRule()) {
            drawRule(m, hover == Part.RULE);
            drawRate(m, hover == Part.RATE, unit);
        } else {
            Hyb.textCentered(
                m.kind == Drawer.Kind.TRASH ? "voids what arrives" : "takes the surplus",
                W / 2f,
                ROW_Y + 3,
                Hyb.MUTED);
        }
    }

    /** Zoomed out: the resource, its name and the rate, big enough to read at half zoom. */
    private void drawGlance(final DrawerModel m, final float z, final int ring) {
        Hyb.rect(2, 2, W - 4, H - 4, Hyb.FRAME);
        Hyb.rect(2, 2, W - 4, H - 4, (ring & 0x00FFFFFF) | 0x33000000);
        // The name across the top, then the icon and the rate.
        Hyb.text(Hyb.fit(m.label, (W - 12) / 2), 6, 6, 2f, 0xFFFFFFFF);
        // Far out the icon is too small to read: the rate takes its place, at a scale that stays sharp.
        final boolean far = session.graph()
            .getZoom() <= 0.25f;
        if (!far && m.isFluid()) Hyb.fluid(m.fluid, 6, 26, 28, z);
        else if (!far) Hyb.item(m.item, 6, 26, 28, z);
        final Fmt.RateUnit unit = session.rateUnit();
        final String sign = m.rate <= 0 ? "" : m.kind == Drawer.Kind.SOURCE ? "-" : "+";
        final int color = m.rate <= 0 ? Hyb.MUTED : m.kind == Drawer.Kind.SOURCE ? Hyb.SOURCE_INK : Hyb.PRODUCT_INK;
        final String rate = sign + Fmt.brief(m.rate * unit.perSecond);
        // A number is never cut: if it does not fit big it drops to the next scale that stays sharp, centred.
        final float x = far ? 6 : 40, room = W - 4 - x, big = far ? 4f : 3f, small = 2f;
        final float scale = Hyb.width(rate) * big <= room ? big : small;
        Hyb.text(rate, x, (far ? 24 : 29) + (big - scale) * 9 / 2, scale, color);
    }

    private static void key(final int x, final int y, final boolean hover) {
        Hyb.bevel(x, y, KEY, KEY, hover ? Hyb.KEY_HOVER : Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
    }

    /** A small loop arrow: product, byproduct and trash take turns. */
    private static void cycleGlyph(final int x, final int y) {
        Hyb.rect(x + 3, y + 3, 6, 1, Hyb.INK);
        Hyb.rect(x + 3, y + 3, 1, 5, Hyb.INK);
        Hyb.rect(x + 3, y + 8, 6, 1, Hyb.INK);
        Hyb.rect(x + 8, y + 5, 1, 4, Hyb.INK);
        Hyb.rect(x + 7, y + 4, 3, 1, Hyb.INK);
    }

    public static String ruleLabel(final Drawer.Rule rule) {
        return switch (rule) {
            case ANY -> "~ Any";
            case AT_LEAST -> "≥ At least";
            case EXACTLY -> "= Exactly";
            case AT_MOST -> "≤ At most";
        };
    }

    private void drawRule(final DrawerModel m, final boolean hover) {
        Hyb.bevel(RULE_X, ROW_Y, RULE_W, ROW_H, hover ? Hyb.KEY_HOVER : Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
        Hyb.textCentered(Hyb.fit(ruleLabel(m.rule), RULE_W - 4), RULE_X + RULE_W / 2f, ROW_Y + 3, Hyb.INK);
    }

    private void drawRate(final DrawerModel m, final boolean hover, final Fmt.RateUnit unit) {
        final boolean empty = m.rule == Drawer.Rule.ANY || m.target <= 0;
        final int fill = m.unmet ? 0xFF4A2020 : hover ? Hyb.TILE_HI : Hyb.WELL;
        Hyb.well(RATE_X, ROW_Y, RATE_W, ROW_H, fill, 0xFF282A2F, 0xFF5A5C65);
        final String text;
        int color = m.unmet ? Hyb.RED_INK : Hyb.INK;
        if (empty) {
            text = "rate?";
            // Pulse when the plan has nothing to solve for, so the empty box reads as the next thing to do.
            final boolean idle = session.nothingToSolveFor();
            color = idle && (System.currentTimeMillis() / 500) % 2 == 0 ? Hyb.GOLD : Hyb.MUTED;
        } else {
            text = Fmt.rate(m.target, unit, m.isFluid());
        }
        Hyb.textCentered(Hyb.fit(text, RATE_W - 4), RATE_X + RATE_W / 2f, ROW_Y + 3, color);
    }

    /** Tooltip lines for the part under the mouse. */
    public List<String> hoverLines() {
        if (model == null || !isHovering()) return null;
        final Part part = partAt(localX(), localY());
        final List<String> lines = new ArrayList<>();
        final String hint = "§7";
        final Fmt.RateUnit unit = session.rateUnit();
        switch (part) {
            case DELETE -> lines.add("Delete this drawer");
            case CYCLE -> {
                lines.add("Product, byproduct or trash");
                lines.add(hint + "Click: next kind");
            }
            case RULE -> {
                lines.add("Rule: " + ruleLabel(model.rule));
                lines.add(hint + "Click: pick  Wheel: next");
            }
            case RATE -> {
                lines.add(
                    model.rule == Drawer.Rule.ANY ? "No rate yet"
                        : "Rate: " + Fmt.rate(model.target, unit, model.isFluid()));
                if (model.shortfall != null) lines.add(
                    "§cReaches " + Fmt.rate(model.shortfall.reachable(), unit, model.isFluid())
                        + " of "
                        + Fmt.rate(model.shortfall.target(), unit, model.isFluid()));
                lines.add(hint + "Click: type a rate (2.5k, 1/3)  Empty: no rule");
            }
            default -> {
                lines.add(kindName(model.kind) + ": " + model.label);
                lines.add(hint + Fmt.rate(model.rate, unit, model.isFluid()) + (model.linked ? "" : "  (not wired)"));
                if (model.shortfall != null) lines.add(
                    "§cCan't reach the target: " + Fmt.rate(model.shortfall.reachable(), unit, model.isFluid())
                        + " of "
                        + Fmt.rate(model.shortfall.target(), unit, model.isFluid()));
            }
        }
        return lines;
    }

    static String kindName(final Drawer.Kind kind) {
        return switch (kind) {
            case SOURCE -> "Source";
            case PRODUCT -> "Product";
            case BYPRODUCT -> "Byproduct";
            case TRASH -> "Trash";
        };
    }

    // endregion

    // region Controls

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (model == null) return Result.IGNORE;
        final Drawer drawer = model.drawer;
        switch (partAt(localX(), localY())) {
            case DELETE -> {
                if (mouseButton == 0) session.deleteDrawer(drawer);
            }
            case CYCLE -> {
                if (mouseButton == 0) session.cycleDrawer(drawer);
            }
            case RULE -> {
                if (mouseButton == 1) stepRule(-1);
                else openRules();
            }
            case RATE -> {
                if (mouseButton == 0) openRate();
            }
            default -> {
                return mouseButton == 0 ? Result.ACCEPT : Result.IGNORE;
            }
        }
        return Result.SUCCESS;
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        if (model == null || partAt(localX(), localY()) != Part.RULE) return false;
        stepRule(direction == UpOrDown.UP ? 1 : -1);
        return true;
    }

    private void stepRule(final int step) {
        final Drawer.Rule[] rules = Drawer.Rule.values();
        final int i = (model.rule.ordinal() + step + rules.length) % rules.length;
        session.setDrawerRule(model.drawer, rules[i]);
    }

    private void openRules() {
        openRules(getPanel(), session, model, screenX(RULE_X), screenY(ROW_Y + ROW_H + 2));
    }

    private void openRate() {
        openRate(getPanel(), session, model, screenX(RATE_X), screenY(ROW_Y + ROW_H + 2));
    }

    /** The rule list for a drawer; the overview's drawer rows open the same one. */
    public static void openRules(final ModularPanel panel, final BoardSession session, final DrawerModel model,
        final int screenX, final int screenY) {
        final List<PickList.Entry> rows = new ArrayList<>();
        for (final Drawer.Rule rule : Drawer.Rule.values()) {
            rows.add(
                new PickList.Entry(
                    null,
                    ruleLabel(rule),
                    "",
                    Hyb.INK,
                    rule == model.rule,
                    () -> session.setDrawerRule(model.drawer, rule)));
        }
        Popup.open(panel, PickList.popup("plannh_rule", null, rows, false, 110), screenX, screenY);
    }

    /** The rate box for a drawer, in the board's unit; the overview's drawer rows open the same one. */
    public static void openRate(final ModularPanel panel, final BoardSession session, final DrawerModel model,
        final int screenX, final int screenY) {
        final Fmt.RateUnit unit = session.rateUnit();
        final double current = model.rule == Drawer.Rule.ANY ? 0 : model.target * unit.perSecond;
        Popup.open(
            panel,
            NumberPopup.create(
                "Rate in " + (model.isFluid() ? "L" : "") + unit.suffix + " (empty: no rule)",
                "2.5k, 1/3",
                current,
                0,
                Double.MAX_VALUE,
                v -> session.setDrawerRate(model.drawer, v / unit.perSecond)),
            screenX,
            screenY);
    }

    // endregion

    // region Moving

    @Override
    public boolean onDragStart(final int button) {
        if (button != 0 || model == null || partAt(localX(), localY()) != Part.BODY || canvas() == null) return false;
        // The board moves it, with the rest of the selection when it is selected.
        canvas().beginMove(drawerId);
        return true;
    }

    @Override
    public void onDrag(final int mouseButton, final long timeSinceLastClick) {
        if (canvas() != null) canvas().dragMove();
    }

    @Override
    public void onDragEnd(final boolean successful) {
        if (canvas() != null) canvas().endMove(successful);
    }

    @Override
    public void drawMovingState(final ModularGuiContext context, final float partialTicks) {}

    @Override
    public @Nullable Area getMovingArea() {
        return null;
    }

    @Override
    public boolean isMoving() {
        return moving;
    }

    @Override
    public void setMoving(final boolean moving) {
        this.moving = moving;
    }

    // endregion
}
