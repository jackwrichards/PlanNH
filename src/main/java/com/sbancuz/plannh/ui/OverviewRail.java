package com.sbancuz.plannh.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.item.ItemStack;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.Stencil;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.sbancuz.plannh.data.flowchart.Drawer;
import com.sbancuz.plannh.ui.canvas.BoardCanvas;
import com.sbancuz.plannh.ui.card.CardModel;
import com.sbancuz.plannh.ui.drawer.DrawerCard;
import com.sbancuz.plannh.ui.drawer.DrawerModel;
import com.sbancuz.plannh.ui.theme.Fmt;
import com.sbancuz.plannh.ui.theme.Hyb;

/**
 * The overview, Factory Flow's resources column moved to the board's left edge: what the plan needs from outside
 * (inputs), what it gives out (outputs) and what it makes and uses itself (internal), each resource with the drawers
 * that hold it and their rule and rate; then the machines to build, with power. Hovering a resource lights it up on the
 * board; double-clicking flies to the cards that use it. Folds to a thin strip.
 */
final class OverviewRail extends ParentWidget<OverviewRail>
    implements Interactable, com.cleanroommc.modularui.integration.recipeviewer.RecipeViewerIngredientProvider {

    static final int W = 200;
    static final int W_FOLDED = 14;

    private static final int HEAD_H = 18, FILTER_Y = 20, FILTER_H = 14, LIST_Y = 38;
    private static final int SECTION_H = 16, ROW_H = 18, DRAWER_H = 16, GROUP_H = 16, LINE_H = 14, GAP = 4;
    private static final int TOGGLE_W = 34;
    private static final int INTERNAL_INK = 0xFFD4D4D4;

    private enum Kind {
        ADD,
        FOLD,
        CLEAR,
        DELETE,
        SECTION,
        RESOURCE,
        RULE,
        RATE,
        PEAK,
        GROUP,
        MACHINE
    }

    /** Something clickable or hoverable, in rail coordinates, rebuilt every frame by the draw pass. */
    private record Hit(Kind kind, int x0, int y0, int x1, int y1, Object data) {

        boolean contains(final int x, final int y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    private final BoardSession session;
    private final BoardCanvas canvas;
    private final TextFieldWidget filterField;
    private final Set<String> folded = new HashSet<>(Set.of("INTERNAL"));
    private final List<Hit> hits = new ArrayList<>();
    private boolean open = true;
    private String filter = "";
    private int scroll;
    private int contentH;
    private long lastClick;
    private String lastClickKey;
    private final Map<String, Integer> flyIndex = new LinkedHashMap<>();

    OverviewRail(final BoardSession session, final BoardCanvas canvas) {
        this.session = session;
        this.canvas = canvas;
        width(W);
        filterField = new TextFieldWidget()
            .value(new StringValue.Dynamic(() -> filter, s -> filter = s == null ? "" : s))
            .hintText("Filter resources...")
            .pos(4, FILTER_Y)
            .size(W - 8, FILTER_H);
        child(filterField);
    }

    boolean isOpen() {
        return open;
    }

    int currentWidth() {
        return open ? W : W_FOLDED;
    }

    void toggle() {
        open = !open;
        scroll = 0;
        filterField.setEnabled(open);
        width(currentWidth());
        if (getParent() != null) getParent().scheduleResize();
        scheduleResize();
    }

    @Override
    public boolean canHover() {
        return true;
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        // The field commits only on Enter; read its live text so the list filters as the player types.
        final String live = filterField.getText();
        if (!live.equals(filter)) {
            filter = live;
            scroll = 0;
        }
    }

    private int localX() {
        return getContext().getAbsMouseX() - getArea().x;
    }

    private int localY() {
        return getContext().getAbsMouseY() - getArea().y;
    }

    // region Drawing

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        hits.clear();
        final int w = getArea().width, h = getArea().height;
        Hyb.rect(0, 0, w, h, 0xFF202226);
        Hyb.rect(w - 1, 0, 1, h, Hyb.RING);
        if (!open) {
            drawFolded(h);
            session.setRailHoverKey(null);
            return;
        }
        final Hit hover = hitAtMouseFromLastFrame();
        session.setRailHoverKey(
            hover != null && hover.kind() == Kind.RESOURCE ? ((BoardSession.TotalLine) hover.data()).key() : null);

        // Heading: the title and the fold key.
        Hyb.rect(0, 0, w - 1, HEAD_H, 0xFF2A2D33);
        Hyb.text("OVERVIEW", 6, 5, Hyb.MUTED);
        final int fx = w - 4 - 14;
        Hyb.bevel(
            fx,
            2,
            14,
            14,
            hover != null && hover.kind() == Kind.FOLD ? Hyb.KEY_HOVER : Hyb.KEY,
            Hyb.KEY_HI,
            Hyb.KEY_LO,
            0,
            1);
        Hyb.textCentered("«", fx + 7.5f, 5, Hyb.INK);
        hits.add(new Hit(Kind.FOLD, fx, 2, fx + 14, 16, null));

        // The list scrolls under the heading and filter.
        final float z = context.getCurrentDrawingZ();
        final int listY = listY();
        if (session.hasSelection()) selectionStrip(w, hover);
        Stencil.apply(0, listY, w - 1, h - listY, context);
        int y = listY - scroll;
        final BoardSession.Totals t = session.totals();
        inputs.clear();
        inputs.addAll(t.inputs());
        y = resources("INPUTS", t.inputs(), y, w, z, Hyb.SOURCE_INK, "-", "Nothing missing.", hover);
        y = resources("OUTPUTS", t.outputs(), y, w, z, Hyb.PRODUCT_INK, "+", "Nothing coming out yet.", hover);
        y = resources("INTERNAL", t.internal(), y, w, z, INTERNAL_INK, "", "Nothing internal.", hover);
        y = machines(t, y, w, z, hover);
        contentH = y + scroll - listY;
        Stencil.remove();
        // Folding a section can leave the list scrolled past its end; a thin bar says when there is more.
        final int visible = h - listY, max = Math.max(0, contentH - visible);
        if (scroll > max) scroll = max;
        if (max > 0) {
            final int thumb = Math.max(10, visible * visible / contentH);
            Hyb.rect(w - 4, listY + (visible - thumb) * scroll / max, 2, thumb, Hyb.MUTED);
        }
        // Keep what is on screen of the list; the heading's fold key stays as it is.
        final List<Hit> moved = new ArrayList<>(hits.size());
        for (final Hit hit : hits) {
            if (hit.kind() == Kind.FOLD || hit.kind() == Kind.CLEAR || hit.kind() == Kind.DELETE) {
                moved.add(hit);
                continue;
            }
            if (hit.y1() <= listY || hit.y0() >= h) continue;
            moved.add(new Hit(hit.kind(), hit.x0(), Math.max(hit.y0(), listY), hit.x1(), hit.y1(), hit.data()));
        }
        hits.clear();
        hits.addAll(moved);
        lastHits.clear();
        lastHits.addAll(moved);
    }

    /** Hover is resolved against the previous frame's hit list, which is what is on screen. */
    private final List<Hit> lastHits = new ArrayList<>();

    private Hit hitAtMouseFromLastFrame() {
        if (!isHovering()) return null;
        final int x = localX(), y = localY();
        for (final Hit h : lastHits) if (h.contains(x, y)) return h;
        return null;
    }

    /** The list starts lower while something is selected, under the selection strip. */
    private int listY() {
        return LIST_Y + (session.hasSelection() ? 16 : 0);
    }

    /** "2 cards, 1 drawer selected", with Clear and Delete, in Factory Flow's cyan. */
    private void selectionStrip(final int w, final Hit hover) {
        int cards = 0, drawers = 0;
        for (final UUID id : session.selection()) {
            if (session.model(id) != null) cards++;
            else if (session.drawerModel(id) != null) drawers++;
        }
        final int y = LIST_Y - 2;
        Hyb.rect(0, y, w - 1, 16, 0x3322D3EE);
        Hyb.rect(0, y, w - 1, 1, 0x9922D3EE);
        Hyb.rect(0, y + 15, w - 1, 1, 0x9922D3EE);
        final StringBuilder what = new StringBuilder();
        if (cards > 0) what.append(cards)
            .append(cards == 1 ? " card" : " cards");
        if (drawers > 0) what.append(what.length() > 0 ? ", " : "")
            .append(drawers)
            .append(drawers == 1 ? " drawer" : " drawers");
        Hyb.text(what.toString(), 6, y + 4, Hyb.INK);
        final String clear = "Clear", delete = "Delete";
        final int dx = w - 6 - Hyb.width(delete), cx = dx - 8 - Hyb.width(clear);
        Hyb.text(clear, cx, y + 4, hover != null && hover.kind() == Kind.CLEAR ? 0xFFFFFFFF : Hyb.SELECTION);
        Hyb.text(delete, dx, y + 4, hover != null && hover.kind() == Kind.DELETE ? 0xFFFFFFFF : Hyb.RED_INK);
        hits.add(new Hit(Kind.CLEAR, cx - 2, y, cx + Hyb.width(clear) + 2, y + 16, null));
        hits.add(new Hit(Kind.DELETE, dx - 2, y, dx + Hyb.width(delete) + 2, y + 16, null));
    }

    private void drawFolded(final int h) {
        Hyb.textCentered("»", W_FOLDED / 2f + 0.5f, 5, Hyb.INK);
        // The title runs down the strip.
        GL11.glPushMatrix();
        GL11.glTranslatef(3, 20, 0);
        GL11.glRotatef(90, 0, 0, 1);
        Hyb.text("OVERVIEW", 0, -9, Hyb.MUTED);
        GL11.glPopMatrix();
        hits.add(new Hit(Kind.FOLD, 0, 0, W_FOLDED, h, null));
        lastHits.clear();
        lastHits.addAll(hits);
    }

    private boolean matches(final String label) {
        final String f = filter.trim()
            .toLowerCase();
        return f.isEmpty() || label.toLowerCase()
            .contains(f);
    }

    private int section(final String title, final int count, final int shown, final int y, final int w, final int ink,
        final Hit hover) {
        final boolean isFolded = folded.contains(title);
        if (hover != null && hover.kind() == Kind.SECTION && title.equals(hover.data()))
            Hyb.rect(0, y, w - 1, SECTION_H, 0xFF30333A);
        else Hyb.rect(0, y, w - 1, SECTION_H, 0xFF2A2D33);
        // A small triangle: pointing right when folded, down when open.
        if (isFolded) Hyb.triangle(6, y + 4, 6, y + 12, 10, y + 8, ink);
        else Hyb.triangle(4, y + 6, 12, y + 6, 8, y + 10, ink);
        Hyb.text(title, 14, y + 4, ink);
        final String badge = filter.isBlank() ? Integer.toString(count) : shown + " / " + count;
        final int bw = Hyb.width(badge) + 6;
        Hyb.rect(14 + Hyb.width(title) + 5, y + 3, bw, 10, 0xFF353942);
        Hyb.text(badge, 14 + Hyb.width(title) + 8, y + 4, 0xFFA3A3A3);
        hits.add(new Hit(Kind.SECTION, 0, y, w - 1, y + SECTION_H, title));
        return y + SECTION_H;
    }

    private int resources(final String title, final List<BoardSession.TotalLine> lines, int y, final int w,
        final float z, final int ink, final String sign, final String empty, final Hit hover) {
        final List<BoardSession.TotalLine> shown = new ArrayList<>();
        for (final BoardSession.TotalLine line : lines) if (matches(line.label())) shown.add(line);
        y = section(title, lines.size(), shown.size(), y, w, ink, hover);
        if (folded.contains(title)) return y + GAP;
        if (shown.isEmpty()) {
            Hyb.text(lines.isEmpty() ? empty : "No matches.", 8, y + 5, 0xFF6A6C74);
            return y + ROW_H + GAP;
        }
        final Fmt.RateUnit unit = session.rateUnit();
        final int tint = ink & 0x00FFFFFF | 0x0D000000;
        for (final BoardSession.TotalLine line : shown) {
            final boolean hot = hover != null && (hover.kind() == Kind.RESOURCE || hover.kind() == Kind.ADD)
                && hover.data() == line;
            Hyb.rect(0, y, w - 1, ROW_H, hot ? 0x1A22D3EE : tint);
            if (hot) {
                Hyb.rect(0, y, w - 1, 1, 0x9922D3EE);
                Hyb.rect(0, y + ROW_H - 1, w - 1, 1, 0x9922D3EE);
            }
            if (line.isFluid()) Hyb.fluid(line.fluid(), 4, y + 1, 16, z);
            else Hyb.item(line.item(), 4, y + 1, 16, z);
            final String number = (line.amount() > 0 ? sign : "") + Fmt.compact(line.amount() * unit.perSecond);
            final String suffix = (line.isFluid() ? " L" : "") + unit.suffix;
            final int rateW = Hyb.width(number) + Hyb.width(suffix) + 1;
            Hyb.text(Hyb.fit(line.label(), w - 24 - rateW - 10), 23, y + 5, Hyb.INK);
            Hyb.text(number, w - 6 - rateW, y + 5, line.amount() > 0 ? ink : Hyb.MUTED);
            Hyb.text(suffix, w - 6 - Hyb.width(suffix), y + 5, Hyb.MUTED);
            // Hovered and without a drawer on this side: a "+" that makes one (a source for inputs, a product for
            // outputs).
            if (hot && !title.equals("INTERNAL") && !hasDrawer(line, title.equals("INPUTS"))) {
                final int ax = w - 6 - rateW - 16;
                final boolean addHot = hover.kind() == Kind.ADD;
                Hyb.bevel(ax, y + 3, 12, 12, addHot ? Hyb.KEY_HOVER : Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
                Hyb.rect(ax + 3, y + 8, 6, 2, ink);
                Hyb.rect(ax + 5, y + 6, 2, 6, ink);
                hits.add(new Hit(Kind.ADD, ax, y + 3, ax + 12, y + 15, line));
            }
            hits.add(new Hit(Kind.RESOURCE, 0, y, w - 1, y + ROW_H, line));
            y += ROW_H;
            y = drawerRows(title, line, y, w, hover);
        }
        return y + GAP;
    }

    /** The input rows drawn this frame, to tell an input's "+" (source) from an output's (product). */
    private final java.util.Set<Object> inputs = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private boolean hasDrawer(final BoardSession.TotalLine line, final boolean sources) {
        for (final DrawerModel d : session.drawerModels()
            .values()) {
            if ((d.kind == Drawer.Kind.SOURCE) == sources && d.drawer.getResourceKey()
                .equals(line.key())) return true;
        }
        return false;
    }

    /** Under a resource, the drawers holding it on that side, each with its rule and rate. */
    private int drawerRows(final String title, final BoardSession.TotalLine line, int y, final int w, final Hit hover) {
        if (title.equals("INTERNAL")) return y;
        final boolean sources = title.equals("INPUTS");
        for (final DrawerModel d : session.drawerModels()
            .values()) {
            if ((d.kind == Drawer.Kind.SOURCE) != sources || !d.drawer.getResourceKey()
                .equals(line.key()) || !d.kind.hasRule()) continue;
            // A little tree branch, then the rule button and the rate box.
            Hyb.rect(9, y, 1, DRAWER_H / 2, Hyb.MUTED);
            Hyb.rect(9, y + DRAWER_H / 2, 5, 1, Hyb.MUTED);
            final int rx = 16, rw = 62, bx = rx + rw + 3, bw = w - 6 - bx;
            final boolean ruleHot = hover != null && hover.kind() == Kind.RULE && hover.data() == d;
            final boolean rateHot = hover != null && hover.kind() == Kind.RATE && hover.data() == d;
            Hyb.bevel(rx, y + 2, rw, 12, ruleHot ? Hyb.KEY_HOVER : Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
            Hyb.textCentered(
                Hyb.fit(DrawerCard.ruleLabel(d.rule), rw - 4),
                rx + rw / 2f,
                y + 4,
                d.rule == Drawer.Rule.ANY ? Hyb.MUTED : Hyb.GOLD);
            Hyb.well(
                bx,
                y + 2,
                bw,
                12,
                d.unmet ? 0xFF4A2020 : rateHot ? Hyb.TILE_HI : 0xFF17191D,
                0xFF282A2F,
                0xFF5A5C65);
            final boolean empty = d.rule == Drawer.Rule.ANY || d.target <= 0;
            final String text = empty ? "rate?" : Fmt.rate(d.target, session.rateUnit(), d.isFluid());
            Hyb.textCentered(
                Hyb.fit(text, bw - 4),
                bx + bw / 2f,
                y + 4,
                d.unmet ? Hyb.RED_INK : empty ? 0xFF6F737C : Hyb.GOLD);
            hits.add(new Hit(Kind.RULE, rx, y + 2, rx + rw, y + 14, d));
            hits.add(new Hit(Kind.RATE, bx, y + 2, bx + bw, y + 14, d));
            y += DRAWER_H;
        }
        return y;
    }

    private int machines(final BoardSession.Totals t, int y, final int w, final float z, final Hit hover) {
        final Map<String, List<BoardSession.MachineLine>> groups = new LinkedHashMap<>();
        for (final BoardSession.MachineLine m : t.machines()) {
            if (matches(m.name())) groups.computeIfAbsent(m.name(), k -> new ArrayList<>())
                .add(m);
        }
        // Whole machines to build: all of them, and those the filter lets through.
        int whole = 0, shown = 0;
        for (final BoardSession.MachineLine m : t.machines()) {
            final int n = (int) Math.ceil(m.machines() - 1e-9);
            whole += n;
            if (groups.containsKey(m.name())) shown += n;
        }
        y = section("MACHINES", whole, shown, y, w, Hyb.INK, hover);
        // Peak / average, at the right of the section heading.
        final int px = w - 6 - TOGGLE_W;
        final boolean peakHot = hover != null && hover.kind() == Kind.PEAK;
        Hyb.bevel(
            px,
            y - SECTION_H + 2,
            TOGGLE_W,
            12,
            peakHot ? Hyb.KEY_HOVER : 0xFF202329,
            Hyb.KEY_HI,
            Hyb.KEY_LO,
            0,
            1);
        Hyb.textCentered(session.peakPower() ? "Peak" : "Avg", px + TOGGLE_W / 2f, y - SECTION_H + 4, Hyb.INK);
        hits.add(new Hit(Kind.PEAK, px, y - SECTION_H + 2, px + TOGGLE_W, y - 2, null));
        if (folded.contains("MACHINES")) return y + GAP;

        Hyb.text("Total", 8, y + 4, Hyb.MUTED);
        Hyb.textRight(Fmt.power(t.euPerTick()) + " EU/t", w - 6, y + 4, Hyb.INK);
        y += LINE_H + 2;
        if (groups.isEmpty()) {
            Hyb.text(
                t.machines()
                    .isEmpty() ? "No machines yet." : "No matches.",
                8,
                y + 4,
                0xFF6A6C74);
            return y + LINE_H + GAP;
        }
        for (final Map.Entry<String, List<BoardSession.MachineLine>> g : groups.entrySet()) {
            final List<BoardSession.MachineLine> lines = g.getValue();
            final boolean groupHot = hover != null && hover.kind() == Kind.GROUP
                && g.getKey()
                    .equals(hover.data());
            if (groupHot) Hyb.rect(0, y, w - 1, GROUP_H, 0x1A22D3EE);
            Hyb.item(
                lines.get(0)
                    .stack(),
                4,
                y,
                16,
                z);
            int count = 0;
            for (final BoardSession.MachineLine m : lines) count += (int) Math.ceil(m.machines() - 1e-9);
            final String total = "×" + count;
            Hyb.text(Hyb.fit(g.getKey(), w - 30 - Hyb.width(total) - 8), 23, y + 4, Hyb.INK);
            Hyb.textRight(total, w - 6, y + 4, Hyb.INK);
            hits.add(new Hit(Kind.GROUP, 0, y, w - 1, y + GROUP_H, g.getKey()));
            y += GROUP_H;
            for (final BoardSession.MachineLine m : lines) {
                final boolean hot = hover != null && hover.kind() == Kind.MACHINE && hover.data() == m;
                if (hot) Hyb.rect(0, y, w - 1, LINE_H, 0x1A22D3EE);
                Hyb.rect(9, y, 1, LINE_H / 2, Hyb.MUTED);
                Hyb.rect(9, y + LINE_H / 2, 5, 1, Hyb.MUTED);
                final String n = Fmt.machines(m.machines()) + "x";
                Hyb.text(n, 16, y + 3, m.pinned() ? Hyb.GOLD : Hyb.INK);
                int cx = 16 + Hyb.width(n) + 4;
                if (m.gregtech()) {
                    final Hyb.Tier tier = Hyb.tier(m.tier());
                    final String chip = (m.multiblock() && m.amps() > 1 ? m.amps() + "A " : "") + tier.name();
                    final int cw = Hyb.width(chip) + 4;
                    Hyb.rect(cx, y + 2, cw, 10, tier.bg());
                    Hyb.text(chip, cx + 2, y + 3, tier.text());
                    cx += cw + 4;
                }
                if (m.tooLow()) Hyb.textRight("TIER!", w - 6, y + 3, Hyb.RED_INK);
                else Hyb.textRight(Fmt.power(m.euPerTick()) + " EU/t", w - 6, y + 3, Hyb.MUTED);
                hits.add(new Hit(Kind.MACHINE, 0, y, w - 1, y + LINE_H, m));
                y += LINE_H;
            }
        }
        return y + GAP;
    }

    // endregion

    // region Mouse

    @Override
    public Result onMousePressed(final int mouseButton) {
        final Hit hit = hitAtMouseFromLastFrame();
        if (hit == null) return open ? Result.SUCCESS : Result.IGNORE;
        // Middle-click on a rule or rate clears the drawer back to "rate?", as on the website.
        if (mouseButton == 2 && (hit.kind() == Kind.RULE || hit.kind() == Kind.RATE)) {
            session.setDrawerRate(((DrawerModel) hit.data()).drawer, 0);
            return Result.SUCCESS;
        }
        if (mouseButton != 0 && hit.kind() != Kind.RULE) return Result.SUCCESS;
        final int sx = getArea().x + hit.x0(), sy = getArea().y + hit.y1() + 2;
        switch (hit.kind()) {
            case FOLD -> toggle();
            case CLEAR -> session.clearSelection();
            case ADD -> {
                final BoardSession.TotalLine line = (BoardSession.TotalLine) hit.data();
                session.addDrawerFor(line.key(), line.label(), inputs.contains(line));
            }
            case DELETE -> session.deleteSelected();
            case SECTION -> {
                final String title = (String) hit.data();
                if (!folded.remove(title)) folded.add(title);
            }
            case RESOURCE -> {
                final BoardSession.TotalLine line = (BoardSession.TotalLine) hit.data();
                final long now = System.currentTimeMillis();
                if (line.key()
                    .equals(lastClickKey) && now - lastClick < 400) flyTo(line.key());
                lastClick = now;
                lastClickKey = line.key();
            }
            case RULE -> {
                final DrawerModel d = (DrawerModel) hit.data();
                if (mouseButton == 1) {
                    final Drawer.Rule[] rules = Drawer.Rule.values();
                    session.setDrawerRule(d.drawer, rules[(d.rule.ordinal() + rules.length - 1) % rules.length]);
                } else DrawerCard.openRules(getPanel(), session, d, sx, sy);
            }
            case RATE -> DrawerCard.openRate(getPanel(), session, (DrawerModel) hit.data(), sx, sy);
            case PEAK -> session.togglePeakPower();
            case GROUP -> flyToGroup((String) hit.data());
            case MACHINE -> canvas.frame(List.of(((BoardSession.MachineLine) hit.data()).nodeId()));
        }
        return Result.SUCCESS;
    }

    /** Frames the next card that makes or uses a resource, cycling on each double-click. */
    private void flyTo(final String key) {
        final List<UUID> using = new ArrayList<>();
        for (final CardModel card : session.models()
            .values()) {
            boolean uses = false;
            for (final CardModel.PortView p : card.inputs) uses |= p.key()
                .equals(key);
            for (final CardModel.PortView p : card.outputs) uses |= p.key()
                .equals(key);
            if (uses) using.add(card.node.id);
        }
        if (using.isEmpty()) return;
        final int i = (flyIndex.merge(key, 1, Integer::sum) - 1) % using.size();
        canvas.frame(List.of(using.get(i)));
    }

    private void flyToGroup(final String name) {
        final List<UUID> cards = new ArrayList<>();
        for (final BoardSession.MachineLine m : session.totals()
            .machines())
            if (m.name()
                .equals(name)) cards.add(m.nodeId());
        if (cards.isEmpty()) return;
        final int i = (flyIndex.merge("machine:" + name, 1, Integer::sum) - 1) % cards.size();
        canvas.frame(List.of(cards.get(i)));
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        if (!open) return false;
        final int max = Math.max(0, contentH - (getArea().height - listY()));
        scroll = Math.max(0, Math.min(max, scroll + (direction == UpOrDown.UP ? -ROW_H : ROW_H)));
        return true;
    }

    /** Tooltip lines for the row under the mouse. */
    /** NEI's keys (R, U, bookmarks) work on a resource row and its drawer controls, as on a port. */
    @Override
    public ItemStack getStackForRecipeViewer() {
        if (!isValid()) return null;
        final Hit hit = hitAtMouseFromLastFrame();
        if (hit == null) return null;
        return switch (hit.kind()) {
            case RESOURCE, ADD -> {
                final BoardSession.TotalLine line = (BoardSession.TotalLine) hit.data();
                yield Resources.lookupStack(line.item(), line.fluid());
            }
            case RULE, RATE -> {
                final DrawerModel d = (DrawerModel) hit.data();
                yield Resources.lookupStack(d.item, d.fluid);
            }
            default -> null;
        };
    }

    List<String> hoverLines() {
        final Hit hit = hitAtMouseFromLastFrame();
        if (hit == null) return null;
        final String hint = "§7";
        final Fmt.RateUnit unit = session.rateUnit();
        return switch (hit.kind()) {
            case FOLD -> List.of(open ? "Fold the overview" : "Open the overview");
            case CLEAR -> List.of("Clear the selection (Esc)");
            case ADD -> List.of(
                inputs.contains(hit.data()) ? "Add a source for it" : "Add a product for it",
                hint + "Linked to every port waiting for it");
            case DELETE -> List.of("Delete the selected cards and drawers (Delete)");
            case SECTION -> List.of(hint + "Click: fold or unfold");
            case RESOURCE -> {
                final BoardSession.TotalLine line = (BoardSession.TotalLine) hit.data();
                yield List.of(
                    line.label(),
                    hint + Fmt.rate(line.amount(), unit, line.isFluid()),
                    hint + "Double-click: show the cards that make or use it",
                    hint + "R, U: its recipes and uses in NEI");
            }
            case RULE -> List.of("Rule", hint + "Click: pick  Right click: previous  Middle click: clear");
            case RATE -> List.of("Rate", hint + "Click: type a rate (2.5k, 1/3)  Middle click: clear");
            case PEAK -> List.of(
                session.peakPower() ? "Peak: every machine running at once"
                    : "Average: the solved fraction of a machine",
                hint + "Click: switch");
            case GROUP -> List.of((String) hit.data(), hint + "Click: show its cards in turn");
            case MACHINE -> {
                final BoardSession.MachineLine m = (BoardSession.MachineLine) hit.data();
                yield List.of(
                    m.name(),
                    hint + Fmt.machines(m.machines()) + " machines" + (m.pinned() ? " (pinned)" : ""),
                    hint + Fmt.power(m.euPerTick()) + " EU/t " + (session.peakPower() ? "peak" : "average"),
                    hint + "Click: show the card");
            }
        };
    }

    // endregion
}
