package com.gtnhplanner.ui.power;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.Stencil;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.gtnhplanner.power.PowerGroup;
import com.gtnhplanner.power.PowerRegistry;
import com.gtnhplanner.power.PowerSearch;
import com.gtnhplanner.power.PowerSource;
import com.gtnhplanner.power.game.PowerPorts;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.card.StructureArt;
import com.gtnhplanner.ui.popup.PickList;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.popup.Tip;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * The non-recipe machines: everything you can put on the board that does not run an NEI recipe, for now the power
 * sources, listed compactly over a dimmed board: five columns of kinds (generators, turbines, boilers, engines and
 * solar, reactors and endgame), one row per machine with its block, its name and the tier that unlocks it, and the
 * structure picture in a preview beside the row under the mouse. Typing searches the machines and everything they burn
 * or make, so "benzene" finds every machine that runs on it and places it with that fuel set.
 */
public final class PowerPicker extends ParentWidget<PowerPicker> implements Interactable {

    private static final int HEAD_H = 26, PAD = 8, GAP = 8, COL_W = 150, COL_MIN = 120, TITLE_H = 14;
    /** One machine per row: its icon, name and unlock tier; a search hit adds what matched under the name. */
    private static final int ROW_H = 18, VIA_ROW_H = 27, ICON = 16;
    private static final int SHEET_BG = 0xFF101215, SHEET_EDGE = 0xFF23262D, TILE_HOT = 0xFF2E3036, ART_BG = 0xFF0B0D10;
    /** The column titles: a dim brass (35% of #d99a2b over the card grey), with light amber ink. */
    private static final int BRASS = Hyb.mix(0xD99A2B, 0x31333A, 0.35f) | 0xFF000000, BRASS_INK = 0xFFFEF3C7,
        AMBER = 0xFFFCD34D, CYAN = 0xFF22D3EE;

    /** Five columns, the groups stacked in them: the short ones share, so the whole list fits on one screen. */
    private static final PowerGroup[][] COLUMNS = { { PowerGroup.BURNERS }, { PowerGroup.TURBINES },
        { PowerGroup.STEAM }, { PowerGroup.ENGINES, PowerGroup.PASSIVE }, { PowerGroup.REACTORS, PowerGroup.ENDGAME } };
    private static final String[] TIERS = { "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV",
        "UIV", "UMV", "UXV", "MAX" };

    private final BoardSession session;
    private final Runnable close;
    private final TextFieldWidget searchField;
    private String query = "";
    @Nullable
    private PowerGroup groupFilter;
    @Nullable
    private String tierFilter;
    private int scroll, contentH;
    private final com.gtnhplanner.ui.theme.ScrollBar bar = new com.gtnhplanner.ui.theme.ScrollBar();

    private enum Kind {
        CLOSE,
        GROUP,
        TIER,
        TILE,
        BACKDROP,
        SHEET
    }

    private record Hit(Kind kind, int x0, int y0, int x1, int y1, @Nullable Object data) {

        boolean contains(final int x, final int y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    private final List<Hit> hits = new ArrayList<>(), lastHits = new ArrayList<>();
    private final Map<String, ItemStack> machineStacks = new HashMap<>();

    public PowerPicker(final BoardSession session, final Runnable close) {
        this.session = session;
        this.close = close;
        searchField = new TextFieldWidget().value(new StringValue.Dynamic(() -> query, s -> {
            final String next = s == null ? "" : s;
            if (!next.equals(query)) scroll = 0;
            query = next;
        }))
            .hintText("Machine, fuel, or product...");
        child(searchField);
    }

    /** Opens fresh: no search, no filters, at the top. */
    public void opened() {
        query = "";
        searchField.setText("");
        groupFilter = null;
        tierFilter = null;
        scroll = 0;
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        final String live = searchField.getText();
        if (live != null && !live.equals(query)) {
            query = live;
            scroll = 0;
        }
    }

    // region Layout

    /** The sheet: as wide as five columns, and as tall as what it shows (the board's room at most). */
    private int[] sheet(final int content) {
        final int w = getArea().width, h = getArea().height;
        final int sw = Math
            .max(240, Math.min(w - 2 * PAD, COLUMNS.length * COL_W + (COLUMNS.length - 1) * GAP + 2 * PAD + 4));
        final int sh = Math.max(HEAD_H + 60, Math.min(h - 12, HEAD_H + content + 4));
        return new int[] { (w - sw) / 2, 6, sw, sh };
    }

    private static int columns(final int width) {
        return Math.max(1, Math.min(COLUMNS.length, (width + GAP) / (COL_MIN + GAP)));
    }

    // endregion

    // region Drawing

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        hits.clear();
        final int w = getArea().width, h = getArea().height;
        final float z = context.getCurrentDrawingZ();
        final Hit hover = hitAt(lastHits);
        Hyb.rect(0, 0, w, h, 0xB3000000);
        hits.add(new Hit(Kind.BACKDROP, 0, 0, w, h, null));
        final List<PowerSearch.Hit> found = found();
        final int[] s = sheet(contentH);
        final int sx = s[0], sy = s[1], sw = s[2], sh = s[3];
        hits.add(new Hit(Kind.SHEET, sx, sy, sx + sw, sy + sh, null));
        Hyb.rect(sx, sy, sw, sh, SHEET_EDGE);
        Hyb.rect(sx + 2, sy + 2, sw - 4, sh - 4, SHEET_BG);
        Hyb.rect(sx + 2, sy + 2, sw - 4, 1, 0x0DFFFFFF);
        drawHeader(sx + 2, sy + 2, sw - 4, hover);
        final int top = sy + 2 + HEAD_H, bottom = sy + sh - 2;
        Stencil.apply(sx + 2, top, sw - 4, bottom - top, context);
        final int x0 = sx + 2 + PAD, innerW = sw - 4 - 2 * PAD;
        int y = top + PAD - scroll;
        if (searching()) y = drawResults(found, x0, y, innerW, z, hover);
        else y = drawColumns(found, x0, y, innerW, z, hover);
        Stencil.remove();
        contentH = y + scroll - top + PAD;
        scroll = bar
            .draw(sx + sw - 2, top, bottom - top, contentH, scroll, isHovering() && bar.contains(localX(), localY()));
        lastHits.clear();
        lastHits.addAll(hits);
    }

    @Override
    public void drawOverlay(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        super.drawOverlay(context, widgetTheme);
        final Hit hover = hitAt(lastHits);
        if (hover == null) return;
        if (hover.kind() == Kind.TILE) {
            drawPreview((PowerSearch.Hit) hover.data(), hover);
            return;
        }
        final Tip tip = tip(hover);
        if (tip != null) tip.drawNear(localX(), localY(), getArea().width, getArea().height);
    }

    private void drawHeader(final int x, final int y, final int w, final Hit hover) {
        Hyb.rect(x, y + HEAD_H - 2, w, 2, SHEET_EDGE);
        bolt(x + 8, y + 7, AMBER);
        Hyb.text("NON-RECIPE MACHINES", x + 20, y + 9, Hyb.INK);
        int right = x + w - 6;
        right = closeKey(right, y + 4, hover) - 8;
        right = key(right, y + 4, tierFilter == null ? "All tiers" : tierFilter, Kind.TIER, hover) - 4;
        right = key(right, y + 4, groupFilter == null ? "All types" : groupFilter.title, Kind.GROUP, hover) - 8;
        final int left = x + 20 + Hyb.width("NON-RECIPE MACHINES") + 12;
        final int fieldW = Math.max(60, Math.min(220, right - left));
        searchField.pos(right - fieldW, y + 5)
            .size(fieldW, 14);
    }

    /** A pixel lightning bolt, 7 x 11: the website's Zap, the power wing's mark. */
    public static void bolt(final float x, final float y, final int color) {
        final int[][] runs = { { 4, 0, 3 }, { 3, 1, 3 }, { 3, 2, 2 }, { 2, 3, 3 }, { 1, 4, 6 }, { 0, 5, 6 },
            { 3, 6, 2 }, { 2, 7, 2 }, { 2, 8, 1 }, { 1, 9, 1 }, { 1, 10, 1 } };
        for (final int[] r : runs) Hyb.rect(x + r[0], y + r[1], r[2], 1, color);
    }

    /** The groups in their columns, each a brass title over its rows; empty groups and columns drop out. */
    private int drawColumns(final List<PowerSearch.Hit> found, final int x0, final int y0, final int width,
        final float z, final Hit hover) {
        final List<List<PowerGroup>> columns = new ArrayList<>();
        for (final PowerGroup[] column : COLUMNS) {
            final List<PowerGroup> shown = new ArrayList<>();
            for (final PowerGroup g : column) if (!inGroup(found, g).isEmpty()) shown.add(g);
            if (!shown.isEmpty()) columns.add(shown);
        }
        if (columns.isEmpty()) return y0;
        final int colW = Math.min(COL_W, (width - (COLUMNS.length - 1) * GAP) / COLUMNS.length);
        int bottom = y0;
        for (int c = 0; c < columns.size(); c++) {
            final int cx = x0 + c * (colW + GAP);
            int y = y0;
            for (final PowerGroup group : columns.get(c)) {
                final List<PowerSearch.Hit> list = inGroup(found, group);
                if (y > y0) y += 8;
                Hyb.rect(cx, y, colW, TITLE_H, BRASS);
                final String count = Integer.toString(list.size());
                Hyb.text(
                    Hyb.fit(group.title.toUpperCase(Locale.ROOT), colW - 14 - Hyb.width(count)),
                    cx + 4,
                    y + 3,
                    BRASS_INK);
                Hyb.textRight(count, cx + colW - 4, y + 3, 0x99FEF3C7);
                y += TITLE_H + 1;
                for (final PowerSearch.Hit hit : list) y = drawRow(hit, cx, y, colW, z, hover);
            }
            bottom = Math.max(bottom, y);
        }
        return bottom;
    }

    /** Search results: every hit, down the columns. */
    private int drawResults(final List<PowerSearch.Hit> found, final int x0, final int y0, final int width,
        final float z, final Hit hover) {
        if (found.isEmpty()) {
            Hyb.text("No machine burns, makes or is named that.", x0, y0 + 4, Hyb.MUTED);
            return y0 + 16;
        }
        final int cols = columns(width);
        final int colW = (width - (cols - 1) * GAP) / cols;
        final int perCol = (found.size() + cols - 1) / cols;
        int bottom = y0;
        for (int c = 0; c < cols; c++) {
            int y = y0;
            for (int i = c * perCol; i < Math.min(found.size(), (c + 1) * perCol); i++)
                y = drawRow(found.get(i), x0 + c * (colW + GAP), y, colW, z, hover);
            bottom = Math.max(bottom, y);
        }
        return bottom;
    }

    /** One machine as a row: its icon, its name and unlock tier, and on a search what matched; returns the bottom. */
    private int drawRow(final PowerSearch.Hit hit, final int x, final int y, final int w, final float z,
        final Hit hover) {
        final PowerSource source = hit.source();
        final int h = hit.via() != null ? VIA_ROW_H : ROW_H;
        final boolean hot = hover != null && hover.kind() == Kind.TILE && hover.data() == hit;
        if (hot) {
            Hyb.rect(x, y, w, h, TILE_HOT);
            Hyb.rect(x, y, 2, h, AMBER);
        }
        drawIcon(source, x + 3, y + (ROW_H - ICON) / 2, z);
        final String tier = source.unlock();
        final int badgeW = tier == null ? 0 : Hyb.width(tier) + 6;
        Hyb.text(Hyb.fit(source.name(), w - ICON - 10 - badgeW), x + ICON + 6, y + 5, Hyb.INK);
        if (tier != null) badge(tier, x + w - 2 - badgeW, y + 4, badgeW);
        if (hit.via() != null) {
            final String via = (hit.via()
                .takes() ? "Takes " : "Makes ") + hit.via()
                    .name();
            Hyb.text(Hyb.fit(via, w - ICON - 8), x + ICON + 6, y + 15, CYAN);
        }
        // Only what is in view takes a click.
        hits.add(new Hit(Kind.TILE, x, y, x + w, y + h, hit));
        return y + h;
    }

    /** The machine's own item, as NEI shows it; failing that its structure picture, small; failing that the bolt. */
    private void drawIcon(final PowerSource source, final float x, final float y, final float z) {
        final ItemStack stack = machineStacks.computeIfAbsent(source.id(), PowerPorts::machineStack);
        if (stack != null) {
            Hyb.item(stack, x, y, ICON, z);
            return;
        }
        final StructureArt.Art art = StructureArt.forMachine(source.id());
        if (art != null) drawArt(art, x, y, ICON, ICON);
        else bolt(x + 4, y + 2, AMBER);
    }

    /** A structure picture fitted to a box, keeping its shape. */
    private static void drawArt(final StructureArt.Art art, final float x, final float y, final float w,
        final float h) {
        final float scale = Math.min(w / art.width(), h / art.height());
        final float pw = art.width() * scale, ph = art.height() * scale;
        Hyb.texture(art.location(), x + (w - pw) / 2f, y + (h - ph) / 2f, pw, ph);
    }

    private static final int PREVIEW_W = 190, PREVIEW_ART = 84;

    /**
     * The machine under the mouse, beside its row: the structure picture (or its block, large), its name, kind and
     * unlock tier, what it does, and on a search what matched.
     */
    private void drawPreview(final PowerSearch.Hit hit, final Hit row) {
        final PowerSource s = hit.source();
        final StructureArt.Art art = StructureArt.forMachine(s.id());
        final List<String> blurb = Hyb.font()
            .listFormattedStringToWidth(s.blurb(), PREVIEW_W - 12);
        final int h = 6 + PREVIEW_ART + 6 + 10 + 10 + blurb.size() * 9 + (hit.via() != null ? 10 : 0) + 6;
        final int w = getArea().width, areaH = getArea().height;
        int x = row.x1() + 6;
        if (x + PREVIEW_W > w - 2) x = row.x0() - 6 - PREVIEW_W;
        x = Math.max(2, x);
        final int y = Math.max(2, Math.min(row.y0() - 20, areaH - h - 2));
        Hyb.rect(x, y, PREVIEW_W, h, 0xFF3A3C43);
        Hyb.rect(x + 1, y + 1, PREVIEW_W - 2, h - 2, 0xF0101215);
        Hyb.rect(x + 6, y + 6, PREVIEW_W - 12, PREVIEW_ART, ART_BG);
        if (art != null) drawArt(art, x + 8, y + 8, PREVIEW_W - 16, PREVIEW_ART - 4);
        else {
            final ItemStack stack = machineStacks.computeIfAbsent(s.id(), PowerPorts::machineStack);
            if (stack != null) Hyb.item(stack, x + (PREVIEW_W - 48) / 2f, y + 6 + (PREVIEW_ART - 48) / 2f, 48, 0);
            else bolt(x + PREVIEW_W / 2f - 3, y + 6 + PREVIEW_ART / 2f - 5, AMBER);
        }
        int ty = y + 6 + PREVIEW_ART + 6;
        Hyb.text(Hyb.fit(s.name(), PREVIEW_W - 12), x + 6, ty, Hyb.INK);
        ty += 10;
        Hyb.text(s.group().title + (s.unlock() != null ? ", unlocks at " + s.unlock() : ""), x + 6, ty, Hyb.MUTED);
        ty += 10;
        for (final String line : blurb) {
            Hyb.text(line, x + 6, ty, 0xFFB8BAC2);
            ty += 9;
        }
        if (hit.via() != null) Hyb.text(
            Hyb.fit(
                (hit.via()
                    .takes() ? "Takes " : "Makes ") + hit.via()
                        .name(),
                PREVIEW_W - 12),
            x + 6,
            ty,
            CYAN);
    }

    /** The unlock tier in its voltage colours, as the card's tier chip wears them. */
    private static void badge(final String tier, final int x, final int y, final int w) {
        final Hyb.Tier t = Hyb.tier(tier);
        Hyb.rect(x, y, w, 10, t.border());
        Hyb.rect(x + 1, y + 1, w - 2, 8, t.bg());
        Hyb.text(tier, x + 3, y + 1.5f, t.text());
        if (t.underline()) Hyb.rect(x + 3, y + 9, w - 6, 1, t.text());
    }

    private int key(final int right, final int y, final String label, final Kind kind, final Hit hover) {
        final int kw = Hyb.width(label) + 18, x = right - kw;
        final boolean hot = hover != null && hover.kind() == kind;
        Hyb.bevel(x, y, kw, 16, hot ? Hyb.KEY_HOVER : Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
        Hyb.text(label, x + 5, y + 4, Hyb.INK);
        final int cx = x + kw - 9;
        Hyb.rect(cx, y + 7, 5, 1, Hyb.MUTED);
        Hyb.rect(cx + 1, y + 8, 3, 1, Hyb.MUTED);
        Hyb.rect(cx + 2, y + 9, 1, 1, Hyb.MUTED);
        hits.add(new Hit(kind, x, y, right, y + 16, null));
        return x;
    }

    private int closeKey(final int right, final int y, final Hit hover) {
        final int x = right - 16;
        final boolean hot = hover != null && hover.kind() == Kind.CLOSE;
        Hyb.bevel(x, y, 16, 16, hot ? Hyb.KEY_HOVER : Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
        Hyb.textCentered("x", x + 8.5f, y + 4, Hyb.INK);
        hits.add(new Hit(Kind.CLOSE, x, y, right, y + 16, null));
        return x;
    }

    // endregion

    // region What shows

    private boolean searching() {
        return !query.trim()
            .isEmpty();
    }

    /** The machines the search and filters leave, in the catalog's order. */
    private List<PowerSearch.Hit> found() {
        final List<PowerSearch.Hit> out = new ArrayList<>();
        for (final PowerSearch.Hit hit : PowerSearch.search(query)) {
            if (groupFilter != null && hit.source()
                .group() != groupFilter) continue;
            if (tierFilter != null && !tierFilter.equals(
                hit.source()
                    .unlock()))
                continue;
            out.add(hit);
        }
        return out;
    }

    private static List<PowerSearch.Hit> inGroup(final List<PowerSearch.Hit> found, final PowerGroup group) {
        final List<PowerSearch.Hit> out = new ArrayList<>();
        for (final PowerSearch.Hit hit : found) if (hit.source()
            .group() == group) out.add(hit);
        return out;
    }

    // endregion

    // region Input

    private Hit hitAt(final List<Hit> from) {
        if (!isHovering()) return null;
        final int x = localX(), y = localY();
        for (int i = from.size() - 1; i >= 0; i--) if (from.get(i)
            .contains(x, y)) return from.get(i);
        return null;
    }

    private int localX() {
        return getContext().getAbsMouseX() - getArea().x;
    }

    private int localY() {
        return getContext().getAbsMouseY() - getArea().y;
    }

    @Override
    public boolean canHover() {
        return true;
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (mouseButton == 0 && isHovering() && bar.contains(localX(), localY())) {
            scroll = bar.press(localY(), scroll);
            return Result.SUCCESS;
        }
        final Hit hit = hitAt(lastHits);
        if (hit == null) return Result.IGNORE;
        if (mouseButton != 0) return Result.SUCCESS;
        switch (hit.kind()) {
            case BACKDROP, CLOSE -> {
                Hyb.click();
                close.run();
            }
            case TILE -> {
                Hyb.click();
                final PowerSearch.Hit picked = (PowerSearch.Hit) hit.data();
                close.run();
                session.addPower(
                    picked.source()
                        .id(),
                    picked.settings());
            }
            case GROUP -> {
                Hyb.click();
                groupMenu(hit);
            }
            case TIER -> {
                Hyb.click();
                tierMenu(hit);
            }
            default -> {}
        }
        return Result.SUCCESS;
    }

    private void groupMenu(final Hit at) {
        final List<PickList.Entry> rows = new ArrayList<>();
        rows.add(new PickList.Entry(null, "All types", "", Hyb.INK, groupFilter == null, () -> filterGroup(null)));
        for (final PowerGroup group : PowerGroup.values()) rows.add(
            new PickList.Entry(
                null,
                group.title,
                Integer.toString(
                    PowerRegistry.inGroup(group)
                        .size()),
                Hyb.INK,
                group == groupFilter,
                () -> filterGroup(group)));
        Popup.open(
            getPanel(),
            PickList.popup("gtnhplanner_power_types", null, rows, false, 170),
            getArea().x + at.x0(),
            getArea().y + at.y1() + 2);
    }

    private void tierMenu(final Hit at) {
        final List<PickList.Entry> rows = new ArrayList<>();
        rows.add(new PickList.Entry(null, "All tiers", "", Hyb.INK, tierFilter == null, () -> filterTier(null)));
        for (final String tier : TIERS) {
            boolean any = false;
            for (final PowerSource s : PowerRegistry.sources()) any |= tier.equals(s.unlock());
            if (any)
                rows.add(new PickList.Entry(null, tier, "", Hyb.INK, tier.equals(tierFilter), () -> filterTier(tier)));
        }
        Popup.open(
            getPanel(),
            PickList.popup("gtnhplanner_power_tiers", null, rows, rows.size() > 10, 110),
            getArea().x + at.x0(),
            getArea().y + at.y1() + 2);
    }

    private void filterGroup(@Nullable final PowerGroup group) {
        groupFilter = group;
        scroll = 0;
    }

    private void filterTier(@Nullable final String tier) {
        tierFilter = tier;
        scroll = 0;
    }

    @Override
    public void onMouseDrag(final int mouseButton, final long timeSinceClick) {
        if (bar.dragging()) scroll = bar.drag(localY());
    }

    @Override
    public boolean onMouseRelease(final int mouseButton) {
        return bar.release();
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        scroll = Math.max(0, scroll + (direction == UpOrDown.UP ? -1 : 1) * 3 * ROW_H);
        return true;
    }

    private Tip tip(final Hit hit) {
        return switch (hit.kind()) {
            case TILE -> {
                final PowerSearch.Hit h = (PowerSearch.Hit) hit.data();
                final PowerSource s = h.source();
                final Tip tip = Tip.of(s.name())
                    .sub(s.group().title + (s.unlock() != null ? ", unlocks at " + s.unlock() : ""))
                    .muted(s.blurb());
                if (h.via() != null) tip.row(
                    h.via()
                        .takes() ? "Takes" : "Makes",
                    h.via()
                        .name());
                yield tip.action(Tip.Input.LEFT, "Add to the plan");
            }
            case GROUP -> Tip.of("Filter by type");
            case TIER -> Tip.of("Filter by unlock tier");
            case CLOSE -> Tip.of("Close (Esc)");
            default -> null;
        };
    }

    // endregion
}
