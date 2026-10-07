package com.sbancuz.plannh.ui.drawer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.item.ItemStack;

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
public final class DrawerCard extends Widget<DrawerCard> implements Interactable, IDraggable,
    com.cleanroommc.modularui.integration.recipeviewer.RecipeViewerIngredientProvider {

    public static final int W = 136;
    public static final int H = 68;
    /** Where wires meet a drawer: its left edge for kinds that take outputs, its right edge for sources. */
    public static final int ANCHOR_Y = 35;

    public enum Part {
        DELETE,
        CYCLE,
        RULE,
        RATE,
        BODY
    }

    private static final int KEY = 12;
    private static final int RULE_X = 6, RULE_W = 24, ROW_Y = 49, ROW_H = 14;
    private static final int RATE_X = RULE_X + RULE_W + 3, RATE_W = W - 6 - RATE_X;

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

    /** NEI's keys (R, U, bookmarks) work over a drawer, on its resource. */
    @Override
    public ItemStack getStackForRecipeViewer() {
        if (!isValid() || model == null) return null;
        return com.sbancuz.plannh.ui.Resources.lookupStack(model.item, model.fluid);
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
        // Far out the controls are not drawn, so nothing invisible answers a click or the wheel.
        if (session.graph()
            .getZoom() <= com.sbancuz.plannh.ui.card.RecipeCard.GLANCE_ZOOM) return Part.BODY;
        if (in(x, y, 5, 4, KEY, KEY)) return Part.DELETE;
        if (model.kind != Drawer.Kind.SOURCE && in(x, y, W - 5 - KEY, 4, KEY, KEY)) return Part.CYCLE;
        if (hasRule() && in(x, y, RULE_X, ROW_Y, RULE_W, ROW_H)) return Part.RULE;
        if (hasRule() && in(x, y, RATE_X, ROW_Y, RATE_W, ROW_H)) return Part.RATE;
        return Part.BODY;
    }

    /** Card-local rectangle of a part, or null when this kind has none; for the dev harness. */
    public int[] partRect(final Part part) {
        if (model == null) return null;
        return switch (part) {
            case DELETE -> new int[] { 5, 4, KEY, KEY };
            case CYCLE -> model.kind == Drawer.Kind.SOURCE ? null : new int[] { W - 5 - KEY, 4, KEY, KEY };
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

    /** The kind's colour, which tints the whole drawer as on the website: sources red, products green. */
    private static int tint(final Drawer.Kind kind) {
        return switch (kind) {
            case SOURCE -> Hyb.SOURCE_INK;
            case PRODUCT -> Hyb.PRODUCT_INK;
            case BYPRODUCT -> 0xFFE0B860;
            case TRASH -> 0xFF8A8F99;
        };
    }

    /** A source is a rounded tank, the rest nearly square boxes. */
    private static float radius(final Drawer.Kind kind) {
        return kind == Drawer.Kind.SOURCE ? 10 : 3;
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final DrawerModel m = model;
        if (m == null) return;
        final float z = context.getCurrentDrawingZ();
        final Part hover = isHovering() ? partAt(localX(), localY()) : null;
        final BoardCanvas board = canvas();
        final int tint = tint(m.kind);
        final float r = radius(m.kind);
        final boolean accepts = board != null && board.drawerAcceptsDrag(m.drawer);
        final boolean lit = m.drawer.getResourceKey()
            .equals(session.hoverKey());

        if (board != null && board.isCarried(drawerId)) {
            Hyb.roundRect(5, 7, W, H, r, 0x50000000);
            Hyb.roundRect(3, 4, W, H, r, 0x40000000);
        } else {
            for (int i = 3; i >= 0; i--)
                Hyb.roundRect(6 - 2 * i, 8 - 2 * i, W + 4 * i, H + 4 * i, r + 2 * i, 0x1E000000);
        }
        if (session.isSelected(drawerId)) Hyb.roundRect(-2, -2, W + 4, H + 4, r + 2, Hyb.SELECTION);
        if (lit) {
            for (int i = 4; i >= 1; i--) Hyb.roundRect(-i, -i, W + 2 * i, H + 2 * i, r + i, 0x26FFCA54);
        }
        final int frame = accepts ? 0xFF53EAFD : lit ? Hyb.GOLD : Hyb.mix(tint, 0x262B34, 0.55f);
        Hyb.roundRect(0, 0, W, H, r, frame);
        if (session.graph()
            .getZoom() <= com.sbancuz.plannh.ui.card.RecipeCard.GLANCE_ZOOM) {
            drawGlance(m, z, tint, r);
            return;
        }
        Hyb.roundRect(2, 2, W - 4, H - 4, Math.max(0, r - 2), Hyb.mix(tint, 0x101318, 0.24f));
        // The title bar, in the kind's colour deep-dimmed, a dark line under it.
        Hyb.roundRect(2, 2, W - 4, 17, Math.max(0, r - 2), Hyb.mix(tint, 0x0B0D10, 0.30f), true, false);
        Hyb.rect(2, 19, W - 4, 1, 0x80000000);

        key(5, 4, hover == Part.DELETE);
        Hyb.rect(5 + 3, 4 + 5, KEY - 6, 2, Hyb.INK);
        if (m.kind != Drawer.Kind.SOURCE) {
            key(W - 5 - KEY, 4, hover == Part.CYCLE);
            cycleGlyph(W - 5 - KEY, 4);
        }
        final int titleL = 5 + KEY + 4, titleR = m.kind == Drawer.Kind.SOURCE ? W - 6 : W - 5 - KEY - 4;
        Hyb.text(Hyb.fit(m.label, titleR - titleL), titleL, 6, 0xFFFFFFFF);

        // The resource, bare with its shadow, and what the plan moves through it.
        Hyb.icon(m.item, m.fluid, 7, 23, 24, z);
        final Fmt.RateUnit unit = session.rateUnit();
        final String sign = m.rate <= 0 ? "" : m.kind == Drawer.Kind.SOURCE ? "-" : "+";
        final String number = sign + Fmt.compact(m.rate * unit.perSecond);
        final String suffix = (m.isFluid() ? " L" : "") + unit.suffix;
        final int color = m.rate <= 0 ? 0xFFA8AFBB : m.kind == Drawer.Kind.SOURCE ? Hyb.SOURCE_INK : Hyb.PRODUCT_INK;
        final int textX = 37, room = W - 6 - textX;
        final boolean big = Hyb.width(number) * Hyb.FIGURE + Hyb.width(suffix) + 2 <= room;
        if (big) {
            Hyb.text(number, textX, 25, Hyb.FIGURE, color);
            Hyb.text(suffix, textX + Hyb.width(number) * Hyb.FIGURE + 2, 28.5f, Hyb.MUTED);
        } else {
            Hyb.text(Hyb.fit(number + suffix, room), textX, 27, color);
        }
        if (hasRule()) {
            drawRuleBar(m, textX, 39, room);
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

    /**
     * How far the plan gets toward the rule: full and green when met, red when it cannot be reached, slate for an
     * upper bound. Nothing for Any, but the space is kept so nothing shifts.
     */
    private static void drawRuleBar(final DrawerModel m, final int x, final int y, final int w) {
        if (m.rule == Drawer.Rule.ANY || m.target <= 0) return;
        Hyb.rect(x, y, w, 3, 0xFF3C3C3C);
        Hyb.rect(x, y, w, 1, 0x59000000);
        final float share = (float) Math.min(1, Math.abs(m.rate) / m.target);
        final int fill = m.unmet ? 0xFFCF3333 : m.rule == Drawer.Rule.AT_MOST ? 0xFF7B8B9C : 0xFF3FAE5C;
        if (share <= 0) return;
        Hyb.rect(x, y, w * share, 3, fill);
        Hyb.rect(x, y, w * share, 1, Hyb.mix(fill, 0xFFFFFF, 0.7f));
    }

    /**
     * Zoomed out, as the cards do: the resource big on the tinted tile and its rate in a dark pill in the corner, at
     * whole screen pixels per font pixel so it stays sharp. The name is in the tooltip.
     */
    private void drawGlance(final DrawerModel m, final float z, final int tint, final float r) {
        final float zoom = session.graph()
            .getZoom();
        Hyb.roundRect(2, 2, W - 4, H - 4, Math.max(0, r - 2), Hyb.mix(tint, 0x101318, 0.24f));
        final float side = H - 12;
        Hyb.icon(m.item, m.fluid, 8, 6, side, z);
        final Fmt.RateUnit unit = session.rateUnit();
        final String sign = m.rate <= 0 ? "" : m.kind == Drawer.Kind.SOURCE ? "-" : "+";
        final int color = m.rate <= 0 ? Hyb.MUTED : m.kind == Drawer.Kind.SOURCE ? Hyb.SOURCE_INK : Hyb.PRODUCT_INK;
        final String rate = sign + Fmt.brief(m.rate * unit.perSecond);
        // One screen pixel per font pixel at most; less when the number would not fit the drawer.
        // Two screen pixels per font pixel, or one far out: whole pixels at every wheel step, so it stays sharp.
        float cs = 1 / zoom;
        if (cs > 4) cs /= 2;
        while (cs > 1 && Hyb.width(rate) * cs + 2 * cs > W - 8) cs /= 2;
        final float pad = cs, tw = Hyb.width(rate) * cs, th = 8 * cs;
        final float px = W - 4 - tw - 2 * pad, py = H - 4 - th - 2 * pad;
        Hyb.rect(px, py, tw + 2 * pad, th + 2 * pad, 0xC0101114);
        Hyb.text(rate, px + pad, py + pad, cs, color);
    }

    /** A title-bar key: a hard dark edge, a dark face. */
    private static void key(final int x, final int y, final boolean hover) {
        Hyb.rect(x, y, KEY, KEY, 0xFF111317);
        Hyb.rect(x + 1, y + 1, KEY - 2, KEY - 2, hover ? 0xFF454952 : 0xFF34373E);
        Hyb.rect(x + 1, y + 1, KEY - 2, 1, 0x1FFFFFFF);
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

    /**
     * A rule's mark where a line of text at {@code y} would go, centred on that line: the font draws "~" at the very
     * top of its cell, so it comes down to the middle like the others.
     */
    public static void drawRuleMark(final Drawer.Rule rule, final float x, final float y, final int color) {
        Hyb.text(ruleMark(rule), x, y + (rule == Drawer.Rule.ANY ? 2.5f : 0), color);
    }

    public static String ruleMark(final Drawer.Rule rule) {
        return switch (rule) {
            case ANY -> "~";
            case AT_LEAST -> "≥";
            case EXACTLY -> "=";
            case AT_MOST -> "≤";
        };
    }

    /** The rule as the website shows it: its mark in gold (muted for Any) and a chevron, on a small dark key. */
    private void drawRule(final DrawerModel m, final boolean hover) {
        Hyb.rect(RULE_X, ROW_Y, RULE_W, ROW_H, 0xFF111317);
        Hyb.rect(RULE_X + 1, ROW_Y + 1, RULE_W - 2, ROW_H - 2, hover ? 0xFF454952 : 0xFF34373E);
        Hyb.rect(RULE_X + 1, ROW_Y + 1, RULE_W - 2, 1, 0x1FFFFFFF);
        final int markColor = m.rule == Drawer.Rule.ANY ? Hyb.MUTED : Hyb.GOLD;
        drawRuleMark(m.rule, RULE_X + 4, ROW_Y + 3, markColor);
        final int cx = RULE_X + RULE_W - 9, cy = ROW_Y + 6;
        Hyb.rect(cx, cy, 5, 1, markColor);
        Hyb.rect(cx + 1, cy + 1, 3, 1, markColor);
        Hyb.rect(cx + 2, cy + 2, 1, 1, markColor);
    }

    /** The rule's rate: a dark box, the number in gold and its unit at the right; red when it cannot be reached. */
    private void drawRate(final DrawerModel m, final boolean hover, final Fmt.RateUnit unit) {
        // A rate being wheeled shows at once, before it is set.
        final Double wheeled = session.wheeledRate(m.drawer.getId());
        final double target = wheeled != null ? wheeled : m.target;
        final boolean empty = wheeled != null ? wheeled <= 0 : m.rule == Drawer.Rule.ANY || m.target <= 0;
        Hyb.rect(RATE_X, ROW_Y, RATE_W, ROW_H, m.unmet ? 0xBFF87171 : hover ? Hyb.GOLD : 0xFF5A5E68);
        Hyb.rect(RATE_X + 1, ROW_Y + 1, RATE_W - 2, ROW_H - 2, hover ? 0xFF15171C : 0xFF0F1114);
        Hyb.rect(RATE_X + 1, ROW_Y + 1, RATE_W - 2, 1, 0xB3000000);
        Hyb.rect(RATE_X + 1, ROW_Y + 1, 1, ROW_H - 2, 0xB3000000);
        if (empty) {
            // Gold and pulsing while the plan has nothing to solve for, so the empty box reads as the next thing to do.
            final boolean idle = session.nothingToSolveFor();
            final int color = idle && (System.currentTimeMillis() / 500) % 2 == 0 ? Hyb.GOLD : 0xFF6F737C;
            Hyb.text("§orate?", RATE_X + 5, ROW_Y + 3, color);
            return;
        }
        final String suffix = (m.isFluid() ? "L" : "") + unit.suffix;
        final String number = Fmt.compact(target * unit.perSecond);
        final int numberColor = m.unmet ? 0xFFF87171 : Hyb.GOLD;
        Hyb.text(Hyb.fit(number, RATE_W - 10 - Hyb.width(suffix)), RATE_X + 5, ROW_Y + 3, numberColor);
        Hyb.textRight(suffix, RATE_X + RATE_W - 4, ROW_Y + 3, 0xFF8A8E97);
    }

    /** The tooltip for the part under the mouse: what it is, its figures, what the mouse does. */
    public com.sbancuz.plannh.ui.popup.Tip tip() {
        if (model == null || !isHovering()) return null;
        final Part part = partAt(localX(), localY());
        final Fmt.RateUnit unit = session.rateUnit();
        final DrawerModel m = model;
        return switch (part) {
            case DELETE -> com.sbancuz.plannh.ui.popup.Tip.of("Delete this drawer")
                .action(com.sbancuz.plannh.ui.popup.Tip.Input.LEFT, "Delete");
            case CYCLE -> com.sbancuz.plannh.ui.popup.Tip.of(kindName(m.kind))
                .muted("Product, byproduct or trash: what happens to what arrives.")
                .action(com.sbancuz.plannh.ui.popup.Tip.Input.LEFT, "Next kind");
            case RULE -> com.sbancuz.plannh.ui.popup.Tip.of("Rule")
                .sub(ruleLabel(m.rule))
                .muted(ruleMeaning(m.kind, m.rule))
                .action(com.sbancuz.plannh.ui.popup.Tip.Input.LEFT, "Pick")
                .action(com.sbancuz.plannh.ui.popup.Tip.Input.WHEEL, "Next")
                .action(com.sbancuz.plannh.ui.popup.Tip.Input.MIDDLE, "Clear");
            case RATE -> {
                final com.sbancuz.plannh.ui.popup.Tip tip = com.sbancuz.plannh.ui.popup.Tip
                    .of(m.rule == Drawer.Rule.ANY ? "No rate yet" : "Target rate");
                if (m.rule != Drawer.Rule.ANY) tip.row(ruleLabel(m.rule), Fmt.rate(m.target, unit, m.isFluid()));
                if (m.shortfall != null)
                    tip.row("Reaches", Fmt.rate(m.shortfall.reachable(), unit, m.isFluid()), Hyb.RED_INK);
                yield tip.action(com.sbancuz.plannh.ui.popup.Tip.Input.LEFT, "Type (2.5k, 1/3)")
                    .action(com.sbancuz.plannh.ui.popup.Tip.Input.WHEEL, "+1, Ctrl 10, Shift 100")
                    .action(com.sbancuz.plannh.ui.popup.Tip.Input.MIDDLE, "Clear");
            }
            default -> {
                final com.sbancuz.plannh.ui.popup.Tip tip = com.sbancuz.plannh.ui.popup.Tip.of(m.label)
                    .sub(kindName(m.kind))
                    .row(m.kind == Drawer.Kind.SOURCE ? "Supplies" : "Takes", Fmt.rate(m.rate, unit, m.isFluid()));
                if (!m.linked) tip.note("Unconnected", com.sbancuz.plannh.ui.popup.Tip.WARN);
                if (m.shortfall != null) tip.note(
                    "Can't reach the target: " + Fmt.rate(m.shortfall.reachable(), unit, m.isFluid())
                        + " of "
                        + Fmt.rate(m.shortfall.target(), unit, m.isFluid()),
                    Hyb.RED_INK);
                yield tip.action(com.sbancuz.plannh.ui.popup.Tip.Input.DRAG, "Move")
                    .action(com.sbancuz.plannh.ui.popup.Tip.Input.KEY, "R, U");
            }
        };
    }

    /** What a rule asks of the plan, in a sentence. */
    private static String ruleMeaning(final Drawer.Kind kind, final Drawer.Rule rule) {
        final String flow = kind == Drawer.Kind.SOURCE ? "draws from it" : "puts in it";
        return switch (rule) {
            case ANY -> "No target: the plan " + flow + " whatever it needs.";
            case AT_LEAST -> "The plan " + flow + " at least the rate.";
            case EXACTLY -> "The plan " + flow + " exactly the rate.";
            case AT_MOST -> "The plan " + flow + " no more than the rate.";
        };
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
        Hyb.click();
        return Result.SUCCESS;
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        if (model == null) return false;
        final int step = direction == UpOrDown.UP ? 1 : -1;
        switch (partAt(localX(), localY())) {
            case RULE -> stepRule(step);
            case RATE -> stepRate(step);
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * The wheel on the rate, as on the website: one at a time in the shown unit, ten with Ctrl, a hundred with Shift.
     */
    private void stepRate(final int step) {
        stepRate(session, model, step);
    }

    private void stepRule(final int step) {
        stepRule(session, model, step);
    }

    /** The wheel on a drawer's rate; the overview's rows take it too. */
    public static void stepRate(final BoardSession session, final DrawerModel model, final int step) {
        final Fmt.RateUnit unit = session.rateUnit();
        final boolean ctrl = org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LCONTROL)
            || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RCONTROL);
        final boolean shift = org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LSHIFT)
            || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RSHIFT);
        final Double wheeled = session.wheeledRate(model.drawer.getId());
        final double shown = wheeled != null ? wheeled * unit.perSecond
            : model.rule == Drawer.Rule.ANY ? 0 : model.target * unit.perSecond;
        final double next = Math.max(0, Math.round(shown) + step * (shift ? 100 : ctrl ? 10 : 1));
        session.wheelDrawerRate(model.drawer, next / unit.perSecond);
    }

    /** The wheel on a drawer's rule: the next or previous rule. */
    public static void stepRule(final BoardSession session, final DrawerModel model, final int step) {
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
