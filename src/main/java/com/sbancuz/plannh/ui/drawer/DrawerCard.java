package com.sbancuz.plannh.ui.drawer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.IDraggable;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.sbancuz.plannh.api.PlanAPI;
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

    public static final int W = 120;
    public static final int H = 80;
    /** Where wires meet a drawer: its left edge for kinds that take outputs, its right edge for sources. */
    public static final int ANCHOR_Y = 31;

    public enum Part {
        DELETE,
        CYCLE,
        RULE,
        RATE,
        BODY
    }

    private static final int KEY = 12;
    private static final int RULE_X = 5, RULE_W = 56, ROW_Y = 48, ROW_H = 14;
    private static final int RATE_X = 63, RATE_W = W - 5 - 63;

    private final BoardSession session;
    public final UUID drawerId;
    private DrawerModel model;

    private int dragStartX, dragStartY, dragMouseX, dragMouseY;
    private String dragUndo;
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
        final int ring = frameColor(m.kind);

        Hyb.rect(0, 0, W, H, ring);
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
        Hyb.slot(5, 23, m.isFluid());
        if (m.isFluid()) Hyb.fluid(m.fluid, 6, 24, 16, z);
        else Hyb.item(m.item, 6, 24, 16, z);
        final Fmt.RateUnit unit = session.rateUnit();
        final String sign = m.rate <= 0 ? "" : m.kind == Drawer.Kind.SOURCE ? "-" : "+";
        final String number = sign + Fmt.compact(m.rate * unit.perSecond);
        final String suffix = (m.isFluid() ? " L" : "") + unit.suffix;
        final int color = m.rate <= 0 ? Hyb.MUTED : m.kind == Drawer.Kind.SOURCE ? Hyb.SOURCE_INK : Hyb.PRODUCT_INK;
        final int room = W - 28 - 5;
        final boolean big = Hyb.width(number) * 2 + Hyb.width(suffix) + 2 <= room;
        if (big) {
            Hyb.text(number, 28, 24, 2f, color);
            Hyb.text(suffix, 28 + Hyb.width(number) * 2 + 2, 31, Hyb.MUTED);
        } else {
            Hyb.text(Hyb.fit(number + suffix, room), 28, 28, color);
        }

        if (hasRule()) {
            drawRule(m, hover == Part.RULE);
            drawRate(m, hover == Part.RATE, unit);
            if (m.kind == Drawer.Kind.PRODUCT && m.target > 0 && m.rule != Drawer.Rule.ANY) drawBar(m);
        } else {
            Hyb.textCentered(
                m.kind == Drawer.Kind.TRASH ? "voids what arrives" : "takes the surplus",
                W / 2f,
                ROW_Y + 3,
                Hyb.MUTED);
        }
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

    static String ruleLabel(final Drawer.Rule rule) {
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

    private void drawBar(final DrawerModel m) {
        final int x = 5, y = ROW_Y + ROW_H + 5, w = W - 10, h = 4;
        Hyb.rect(x, y, w, h, Hyb.SHADOW);
        final double f = Math.max(0, Math.min(1, m.rate / m.target));
        Hyb.rect(x, y, (float) (w * f), h, m.unmet ? Hyb.RED_INK : Hyb.PRODUCT_INK);
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
        Popup.open(
            getPanel(),
            PickList.popup("plannh_rule", null, rows, false, 110),
            screenX(RULE_X),
            screenY(ROW_Y + ROW_H + 2));
    }

    private void openRate() {
        final Fmt.RateUnit unit = session.rateUnit();
        final double current = model.rule == Drawer.Rule.ANY ? 0 : model.target * unit.perSecond;
        Popup.open(
            getPanel(),
            NumberPopup.create(
                "Rate in " + (model.isFluid() ? "L" : "") + unit.suffix + " (empty: no rule)",
                "2.5k, 1/3",
                current,
                0,
                Double.MAX_VALUE,
                v -> session.setDrawerRate(model.drawer, v / unit.perSecond)),
            screenX(RATE_X),
            screenY(ROW_Y + ROW_H + 2));
    }

    // endregion

    // region Moving

    @Override
    public boolean onDragStart(final int button) {
        if (button != 0 || model == null || partAt(localX(), localY()) != Part.BODY) return false;
        dragStartX = model.drawer.getX();
        dragStartY = model.drawer.getY();
        dragMouseX = getContext().getAbsMouseX();
        dragMouseY = getContext().getAbsMouseY();
        dragUndo = PlanAPI.undoHistory()
            .beginEdit(session.graph());
        return true;
    }

    @Override
    public void onDrag(final int mouseButton, final long timeSinceLastClick) {
        if (model == null || dragUndo == null) return;
        final float zoom = session.graph()
            .getZoom();
        final Drawer d = model.drawer;
        d.setX(Math.round(dragStartX + (getContext().getAbsMouseX() - dragMouseX) / zoom));
        d.setY(Math.round(dragStartY + (getContext().getAbsMouseY() - dragMouseY) / zoom));
        pos(d.getX(), d.getY());
    }

    @Override
    public void onDragEnd(final boolean successful) {
        if (model == null || dragUndo == null) return;
        if (successful) {
            PlanAPI.undoHistory()
                .commitEdit(dragUndo, session.graph());
            session.graph()
                .touchLayout();
            PlanAPI.save();
        } else {
            model.drawer.setX(dragStartX);
            model.drawer.setY(dragStartY);
            pos(dragStartX, dragStartY);
        }
        dragUndo = null;
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
