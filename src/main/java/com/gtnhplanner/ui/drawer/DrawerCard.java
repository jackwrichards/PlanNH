package com.gtnhplanner.ui.drawer;

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
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.canvas.BoardCanvas;
import com.gtnhplanner.ui.popup.NumberPopup;
import com.gtnhplanner.ui.popup.PickList;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * A drawer on the board: what the player brings in (source) or wants (product), or where surplus goes (byproduct,
 * trash), drawn by {@link DrawerPaint} in its kind's shape. A header with its delete and kind keys and its name; the
 * resource beside the rate the plan moves through it; for sources and products the rule and its rate under that, the
 * rate's box filling as the plan reaches it.
 */
public final class DrawerCard extends Widget<DrawerCard> implements Interactable, IDraggable,
    com.cleanroommc.modularui.integration.recipeviewer.RecipeViewerIngredientProvider {

    public static final int W = DrawerPaint.W;
    public static final int H = DrawerPaint.H;
    /** The resource's middle, level with the port a drawer is made for when it is placed beside a card. */
    public static final int ANCHOR_Y = DrawerPaint.ICON_Y + DrawerPaint.ICON / 2;

    public enum Part {
        DELETE,
        CYCLE,
        RULE,
        RATE,
        BODY
    }

    private static final int KEY = DrawerPaint.KEY, ROW_Y = DrawerPaint.ROW_Y, ROW_H = DrawerPaint.ROW_H;

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
        return com.gtnhplanner.ui.Resources.lookupStack(model.item, model.fluid);
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
            .getZoom() <= com.gtnhplanner.ui.card.RecipeCard.GLANCE_ZOOM) return Part.BODY;
        for (final Part part : new Part[] { Part.DELETE, Part.CYCLE, Part.RULE, Part.RATE }) {
            final int[] r = partRect(part);
            if (r != null && in(x, y, r[0], r[1], r[2], r[3])) return part;
        }
        return Part.BODY;
    }

    /** Card-local rectangle of a part, or null when this kind has none; for the dev harness. */
    public int[] partRect(final Part part) {
        if (model == null) return null;
        final int side = DrawerPaint.side(model.kind), textX = DrawerPaint.TEXT_X + side;
        return switch (part) {
            case DELETE -> new int[] { DrawerPaint.KEY_IN + side, DrawerPaint.KEY_IN, KEY, KEY };
            case CYCLE -> model.kind == Drawer.Kind.SOURCE ? null
                : new int[] { W - DrawerPaint.KEY_IN - side - KEY, DrawerPaint.KEY_IN, KEY, KEY };
            case RULE -> hasRule() ? new int[] { textX, ROW_Y, DrawerPaint.RULE_W, ROW_H } : null;
            case RATE -> hasRule()
                ? new int[] { textX + DrawerPaint.RULE_W + 2, ROW_Y, W - 5 - side - (textX + DrawerPaint.RULE_W + 2),
                    ROW_H }
                : null;
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

    /** The kind's colour, which tints the whole drawer as on the website. */
    public static int tint(final Drawer.Kind kind) {
        return DrawerPaint.tint(kind);
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        if (!com.gtnhplanner.dev.DevPerf.on()) {
            drawDrawer(context, widgetTheme);
            return;
        }
        final long started = System.nanoTime();
        drawDrawer(context, widgetTheme);
        com.gtnhplanner.dev.DevPerf.time("drawers", System.nanoTime() - started);
    }

    private void drawDrawer(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final DrawerModel m = model;
        if (m == null) return;
        final float z = context.getCurrentDrawingZ();
        final Part hover = isHovering() ? partAt(localX(), localY()) : null;
        final BoardCanvas board = canvas();
        final Drawer.Kind kind = m.kind;
        final boolean accepts = board != null && board.drawerAcceptsDrag(m.drawer);
        final boolean lit = m.drawer.getResourceKey()
            .equals(session.hoverKey());

        // Its shadow, its selection and its glow, all in its shape.
        Hyb.beginBatch();
        DrawerPaint.shadow(kind, board != null && board.isCarried(drawerId));
        if (session.showsSelected(drawerId)) DrawerPaint.shape(kind, 0, 0, W, H, 2, Hyb.SELECTION, 1);
        if (lit)
            for (int i = 3; i >= 1; i--) DrawerPaint.shape(kind, 0, 0, W, H, i, 0x16000000 | Hyb.LIT & 0xFFFFFF, 1);
        final int edge = accepts ? 0xFF53EAFD
            : lit ? Hyb.mix(DrawerPaint.edge(kind), Hyb.LIT, 0.6f) : DrawerPaint.edge(kind);
        if (session.graph()
            .getZoom() <= com.gtnhplanner.ui.card.RecipeCard.GLANCE_ZOOM) {
            Hyb.endBatch();
            drawGlance(m, z, edge);
            return;
        }
        DrawerPaint.frame(kind, edge);
        Hyb.endBatch();

        // The header: delete on the left, the kind's key on the right (a source has none), the name between.
        final int[] del = partRect(Part.DELETE), cycle = partRect(Part.CYCLE);
        key(del[0], del[1], hover == Part.DELETE, true);
        Hyb.rect(del[0] + 3, del[1] + 5, KEY - 6, 2, Hyb.INK);
        if (cycle != null) {
            key(cycle[0], cycle[1], hover == Part.CYCLE, false);
            cycleGlyph(cycle[0], cycle[1]);
        }
        DrawerPaint
            .name(kind, m.label, del[0] + KEY + 3, cycle != null ? cycle[0] - 3 : W - 5 - DrawerPaint.side(kind));

        // The body: the resource, what the plan moves through it, and the rule under that.
        DrawerPaint.icon(kind, m.item, m.fluid, m.isPower(), z);
        final Fmt.RateUnit unit = session.rateUnit();
        DrawerPaint.rate(
            kind,
            DrawerPaint.sign(kind, m.rate) + Fmt.compact(m.shown(m.rate, unit)),
            m.suffix(unit, false),
            DrawerPaint.rateInk(kind, m.rate),
            hasRule(),
            hasRule() ? null : kind == Drawer.Kind.TRASH ? "Voided" : "Surplus");
        if (hasRule()) {
            drawRule(m, hover == Part.RULE);
            drawRate(m, hover == Part.RATE, unit);
        }
    }

    /**
     * Zoomed out, as the cards do: the shape with a rim that stays a screen pixel or two, the resource big inside and
     * its rate in a dark pill in the corner, at whole screen pixels per font pixel so it stays sharp. The name is in
     * the tooltip.
     */
    private void drawGlance(final DrawerModel m, final float z, final int edge) {
        final float zoom = session.graph()
            .getZoom();
        final float rim = Math.min(6, Math.max(1.5f, 1 / zoom));
        Hyb.beginBatch();
        DrawerPaint.shape(m.kind, 0, 0, W, H, 0, edge, 1);
        DrawerPaint.shape(m.kind, 0, 0, W, H, -rim, DrawerPaint.fill(m.kind), 1);
        Hyb.endBatch();
        final int side = DrawerPaint.side(m.kind);
        final float size = H - 14;
        if (m.isPower()) com.gtnhplanner.ui.card.RecipeCard.euIcon(8 + side, 7, size);
        else Hyb.icon(m.item, m.fluid, 8 + side, 7, size, z);
        final Fmt.RateUnit unit = session.rateUnit();
        final String rate = DrawerPaint.sign(m.kind, m.rate) + Fmt.brief(m.shown(m.rate, unit));
        final int color = m.rate <= 0 ? Hyb.MUTED : DrawerPaint.rateInk(m.kind, m.rate);
        // Two screen pixels per font pixel, or one far out: whole pixels at every wheel step, so it stays sharp.
        float cs = 1 / zoom;
        if (cs > 4) cs /= 2;
        while (cs > 1 && Hyb.width(rate) * cs + 2 * cs > W - 8) cs /= 2;
        final float pad = cs, tw = Hyb.width(rate) * cs, th = 8 * cs;
        final float py = H - 4 - th - 2 * pad, px = W - 4 - DrawerPaint.insetAt(m.kind, H - 4) - tw - 2 * pad;
        Hyb.rect(px, py, tw + 2 * pad, th + 2 * pad, 0xC0101114);
        Hyb.text(rate, px + pad, py + pad, cs, color);
        final BoardCanvas board = canvas();
        if (board != null)
            board.labelDrawer(m.label, m.drawer.getX(), m.drawer.getY(), W, H, m.kind == Drawer.Kind.SOURCE, m.rate);
    }

    /**
     * A header key, as the card's: a dark face with a light and a dark band; the delete key reddens under the mouse.
     */
    private static void key(final int x, final int y, final boolean hover, final boolean delete) {
        final int face = hover ? delete ? 0xFF9C3543 : Hyb.KEY_HOVER : Hyb.KEY;
        Hyb.bevel(x + 1, y + 1, KEY - 2, KEY - 2, face, Hyb.KEY_HI, Hyb.KEY_LO, Hyb.KEY_EDGE, 1);
    }

    /** The website's repeat sign, two arrows round a loop: product, byproduct and trash take turns. */
    private static void cycleGlyph(final int x, final int y) {
        final int gx = x + 2, gy = y + 2;
        // The top arrow, out to the right.
        Hyb.rect(gx, gy + 1, 7, 1, Hyb.INK);
        Hyb.rect(gx + 5, gy, 1, 3, Hyb.INK);
        Hyb.rect(gx, gy + 1, 1, 3, Hyb.INK);
        // The bottom arrow, back to the left.
        Hyb.rect(gx + 1, gy + 6, 7, 1, Hyb.INK);
        Hyb.rect(gx + 2, gy + 5, 1, 3, Hyb.INK);
        Hyb.rect(gx + 7, gy + 4, 1, 3, Hyb.INK);
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

    /** The rule: its mark in gold (muted for Any) and a chevron, on a key in the card's chip style. */
    private void drawRule(final DrawerModel m, final boolean hover) {
        final int[] r = partRect(Part.RULE);
        Hyb.rect(r[0], r[1], r[2], r[3], hover ? 0xFF4A4D55 : com.gtnhplanner.ui.card.CardPaint.EDGE);
        Hyb.rect(r[0] + 1, r[1] + 1, r[2] - 2, r[3] - 2, hover ? 0xFF33363C : 0xFF2A2C31);
        final int markColor = m.rule == Drawer.Rule.ANY ? Hyb.MUTED : Hyb.GOLD;
        drawRuleMark(m.rule, r[0] + 3, r[1] + 3, markColor);
        final int cx = r[0] + r[2] - 8, cy = r[1] + 6;
        Hyb.rect(cx, cy, 5, 1, markColor);
        Hyb.rect(cx + 1, cy + 1, 3, 1, markColor);
        Hyb.rect(cx + 2, cy + 2, 1, 1, markColor);
    }

    /**
     * The rule's rate: a dark box, the number in gold and its unit at the right; red when it cannot be reached. A line
     * along its foot fills as the plan gets there: green when met, red when it cannot be, slate for an upper bound;
     * none for Any.
     */
    private void drawRate(final DrawerModel m, final boolean hover, final Fmt.RateUnit unit) {
        final int[] r = partRect(Part.RATE);
        final int x = r[0], y = r[1], w = r[2], h = r[3];
        // A rate being wheeled shows at once, before it is set.
        final Double wheeled = session.wheeledRate(m.drawer.getId());
        final double target = wheeled != null ? wheeled : m.target;
        final boolean empty = wheeled != null ? wheeled <= 0 : m.rule == Drawer.Rule.ANY || m.target <= 0;
        Hyb.rect(x, y, w, h, m.unmet ? 0xBFF87171 : hover ? Hyb.GOLD : 0xFF4E525B);
        Hyb.rect(x + 1, y + 1, w - 2, h - 2, hover ? 0xFF15171C : 0xFF0F1114);
        Hyb.rect(x + 1, y + 1, w - 2, 1, 0xB3000000);
        if (empty) {
            // Gold and pulsing while the plan has nothing to solve for, so the empty box reads as the next thing to do.
            final boolean idle = session.nothingToSolveFor();
            Hyb.textCentered(
                "Set rate",
                x + w / 2f,
                y + 3,
                idle ? com.gtnhplanner.ui.card.CardPaint.prompt() : 0xFF6F737C);
            return;
        }
        final String suffix = m.suffix(unit, true);
        final String number = Fmt.compact(m.shown(target, unit));
        Hyb.text(Hyb.fit(number, w - 10 - Hyb.width(suffix)), x + 4, y + 3, m.unmet ? 0xFFF87171 : Hyb.GOLD);
        Hyb.textRight(suffix, x + w - 4, y + 3, 0xFF8A8E97);
        if (m.rule == Drawer.Rule.ANY || m.target <= 0 || wheeled != null) return;
        final float share = (float) Math.min(1, Math.abs(m.rate) / m.target);
        if (share <= 0) return;
        final int fill = m.unmet ? 0xFFCF3333 : m.rule == Drawer.Rule.AT_MOST ? 0xFF7B8B9C : 0xFF3FAE5C;
        Hyb.rect(x + 1, y + h - 3, (w - 2) * share, 2, fill);
    }

    /** The tooltip for the part under the mouse: what it is, its figures, what the mouse does. */
    public com.gtnhplanner.ui.popup.Tip tip() {
        if (model == null || !isHovering()) return null;
        final Part part = partAt(localX(), localY());
        final Fmt.RateUnit unit = session.rateUnit();
        final DrawerModel m = model;
        return switch (part) {
            case DELETE -> com.gtnhplanner.ui.popup.Tip.of("Delete this drawer")
                .action(com.gtnhplanner.ui.popup.Tip.Input.LEFT, "Delete");
            case CYCLE -> com.gtnhplanner.ui.popup.Tip.of(kindName(m.kind))
                .muted("Product: what you want. Byproduct: surplus. Trash: voided.")
                .action(com.gtnhplanner.ui.popup.Tip.Input.LEFT, "Next kind");
            case RULE -> com.gtnhplanner.ui.popup.Tip.of("Rule")
                .sub(ruleLabel(m.rule))
                .muted(ruleMeaning(m.kind, m.rule))
                .action(com.gtnhplanner.ui.popup.Tip.Input.LEFT, "Pick")
                .action(com.gtnhplanner.ui.popup.Tip.Input.WHEEL, "Next")
                .action(com.gtnhplanner.ui.popup.Tip.Input.MIDDLE, "Clear");
            case RATE -> {
                final com.gtnhplanner.ui.popup.Tip tip = com.gtnhplanner.ui.popup.Tip
                    .of(m.rule == Drawer.Rule.ANY ? "No rate yet" : "Target rate");
                if (m.rule != Drawer.Rule.ANY) tip.row(ruleLabel(m.rule), m.rate(m.target, unit));
                if (m.shortfall != null) tip.row("Reaches", m.rate(m.shortfall.reachable(), unit), Hyb.RED_INK);
                yield tip.action(com.gtnhplanner.ui.popup.Tip.Input.LEFT, "Type (2.5k, 1/3)")
                    .action(com.gtnhplanner.ui.popup.Tip.Input.WHEEL, "+1, Ctrl 10, Shift 100")
                    .action(com.gtnhplanner.ui.popup.Tip.Input.MIDDLE, "Clear");
            }
            default -> {
                final com.gtnhplanner.ui.popup.Tip tip = com.gtnhplanner.ui.popup.Tip.of(m.label)
                    .sub(kindName(m.kind))
                    .row(m.kind == Drawer.Kind.SOURCE ? "Supplies" : "Takes", m.rate(m.rate, unit));
                if (!m.linked) tip.note("Unconnected", com.gtnhplanner.ui.popup.Tip.WARN);
                if (m.shortfall != null) tip.note(
                    "Reaches only " + m.rate(m.shortfall.reachable(), unit)
                        + " of its "
                        + m.rate(m.shortfall.target(), unit)
                        + " target",
                    Hyb.RED_INK);
                yield tip.action(com.gtnhplanner.ui.popup.Tip.Input.DRAG, "Move")
                    .action(com.gtnhplanner.ui.popup.Tip.Input.KEY, "R, U");
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
        final double shown = wheeled != null ? model.shown(wheeled, unit)
            : model.rule == Drawer.Rule.ANY ? 0 : model.shown(model.target, unit);
        final double next = Math.max(0, Math.round(shown) + step * (shift ? 100 : ctrl ? 10 : 1));
        session.wheelDrawerRate(model.drawer, next / model.shown(1, unit));
    }

    /** The wheel on a drawer's rule: the next or previous rule. */
    public static void stepRule(final BoardSession session, final DrawerModel model, final int step) {
        final Drawer.Rule[] rules = Drawer.Rule.values();
        final int i = (model.rule.ordinal() + step + rules.length) % rules.length;
        session.setDrawerRule(model.drawer, rules[i]);
    }

    private void openRules() {
        openRules(getPanel(), session, model, screenX(partRect(Part.RULE)[0]), screenY(ROW_Y + ROW_H + 2));
    }

    private void openRate() {
        openRate(getPanel(), session, model, screenX(partRect(Part.RATE)[0]), screenY(ROW_Y + ROW_H + 2));
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
        Popup.open(panel, PickList.popup("gtnhplanner_rule", null, rows, false, 110), screenX, screenY);
    }

    /** The rate box for a drawer, in the board's unit; the overview's drawer rows open the same one. */
    public static void openRate(final ModularPanel panel, final BoardSession session, final DrawerModel model,
        final int screenX, final int screenY) {
        final Fmt.RateUnit unit = session.rateUnit();
        final double current = model.rule == Drawer.Rule.ANY ? 0 : model.shown(model.target, unit);
        final double perShown = model.shown(1, unit);
        Popup.open(
            panel,
            NumberPopup.create(
                "Rate in " + model.suffix(unit, true) + " (empty: no rule)",
                "2.5k, 1/3",
                current,
                0,
                Double.MAX_VALUE,
                v -> session.setDrawerRate(model.drawer, v / perShown)),
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
