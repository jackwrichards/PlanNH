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
 * sources. The website's power picker over a dimmed board: one column per kind (generators, turbines, boilers,
 * engines, reactors; solar and endgame on a shelf below), each tile the machine's structure render or its block, its
 * name and the tier that unlocks it. Typing searches the machines and everything they burn or make, so "benzene" finds
 * every machine that runs on it and places it with that fuel set.
 */
public final class PowerPicker extends ParentWidget<PowerPicker> implements Interactable {

    private static final int HEAD_H = 26, PAD = 8, GAP = 8, COL_MIN = 112, COL_MAX = 150, TITLE_H = 16;
    private static final int ART_H = 62, LABEL_H = 14, VIA_H = 10;
    private static final int SHEET_BG = 0xFF101215, SHEET_EDGE = 0xFF23262D, TILE_BG = 0xFF24262B,
        TILE_HOT = 0xFF2E3036, TILE_EDGE = 0xFF3A3C43, ART_BG = 0xFF0B0D10;
    /** The column titles: a dim brass (35% of #d99a2b over the card grey), with light amber ink. */
    private static final int BRASS = Hyb.mix(0xD99A2B, 0x31333A, 0.35f) | 0xFF000000, BRASS_INK = 0xFFFEF3C7,
        AMBER = 0xFFFCD34D, CYAN = 0xFF22D3EE;

    /** The fuel-to-power path up top; solar and the endgame on the shelf below. */
    private static final PowerGroup[][] SHELVES = {
        { PowerGroup.BURNERS, PowerGroup.TURBINES, PowerGroup.STEAM, PowerGroup.ENGINES, PowerGroup.REACTORS },
        { PowerGroup.PASSIVE, PowerGroup.ENDGAME } };
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

    /** The sheet: the board's room less a margin, as wide as the browse columns need and no wider. */
    private int[] sheet() {
        final int w = getArea().width, h = getArea().height;
        final int sw = Math.max(240, Math.min(w - 2 * PAD, 5 * COL_MAX + 4 * GAP + 2 * PAD + 4));
        final int sh = h - 2 * 6;
        return new int[] { (w - sw) / 2, 6, sw, sh };
    }

    private static int columns(final int width) {
        return Math.max(1, (width + GAP) / (COL_MIN + GAP));
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
        final int[] s = sheet();
        final int sx = s[0], sy = s[1], sw = s[2], sh = s[3];
        hits.add(new Hit(Kind.SHEET, sx, sy, sx + sw, sy + sh, null));
        Hyb.rect(sx, sy, sw, sh, SHEET_EDGE);
        Hyb.rect(sx + 2, sy + 2, sw - 4, sh - 4, SHEET_BG);
        Hyb.rect(sx + 2, sy + 2, sw - 4, 1, 0x0DFFFFFF);
        drawHeader(sx + 2, sy + 2, sw - 4, hover);
        final int top = sy + 2 + HEAD_H, bottom = sy + sh - 2;
        Stencil.apply(sx + 2, top, sw - 4, bottom - top, context);
        final List<PowerSearch.Hit> found = found();
        final int x0 = sx + 2 + PAD, innerW = sw - 4 - 2 * PAD;
        int y = top + PAD - scroll;
        if (searching()) y = drawResults(found, x0, y, innerW, z, hover);
        else {
            for (int shelf = 0; shelf < SHELVES.length; shelf++) {
                final int before = y;
                y = drawShelf(SHELVES[shelf], found, x0, y, innerW, z, hover);
                if (shelf + 1 < SHELVES.length && y > before) {
                    Hyb.rect(x0, y + 6, innerW, 1, 0x1AFFFFFF);
                    y += 14;
                }
            }
        }
        Stencil.remove();
        contentH = y + scroll - top;
        final int visible = bottom - top, max = Math.max(0, contentH - visible);
        if (scroll > max) scroll = max;
        if (max > 0) {
            final int thumb = Math.max(12, visible * visible / Math.max(1, contentH));
            Hyb.rect(sx + sw - 6, top + (visible - thumb) * scroll / max, 2, thumb, Hyb.MUTED);
        }
        lastHits.clear();
        lastHits.addAll(hits);
    }

    @Override
    public void drawOverlay(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        super.drawOverlay(context, widgetTheme);
        final Hit hover = hitAt(lastHits);
        if (hover == null) return;
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

    /** One shelf: its kinds side by side, as many to a row as fit; returns the bottom. */
    private int drawShelf(final PowerGroup[] groups, final List<PowerSearch.Hit> found, final int x0, final int y0,
        final int width, final float z, final Hit hover) {
        final List<PowerGroup> shown = new ArrayList<>();
        for (final PowerGroup g : groups) if (!inGroup(found, g).isEmpty()) shown.add(g);
        if (shown.isEmpty()) return y0;
        final int perRow = Math.min(shown.size(), columns(width));
        final int colW = Math.min(COL_MAX, (width - (perRow - 1) * GAP) / perRow);
        int y = y0;
        for (int start = 0; start < shown.size(); start += perRow) {
            int bottom = y;
            for (int i = start; i < Math.min(shown.size(), start + perRow); i++) {
                final PowerGroup group = shown.get(i);
                final List<PowerSearch.Hit> list = inGroup(found, group);
                final int cx = x0 + (i - start) * (colW + GAP);
                // The column's title bar in brass, with its count.
                Hyb.rect(cx, y, colW, TITLE_H, 0xFF15171A);
                Hyb.rect(cx + 1, y + 1, colW - 2, TITLE_H - 2, BRASS);
                Hyb.rect(cx + 1, y + 1, colW - 2, 1, 0x2EFFFFFF);
                final String count = Integer.toString(list.size());
                Hyb.text(
                    Hyb.fit(group.title.toUpperCase(Locale.ROOT), colW - 14 - Hyb.width(count)),
                    cx + 5,
                    y + 4,
                    BRASS_INK);
                Hyb.textRight(count, cx + colW - 5, y + 4, 0x99FEF3C7);
                int ty = y + TITLE_H + 4;
                for (final PowerSearch.Hit hit : list) ty = drawTile(hit, cx, ty, colW, z, hover) + 4;
                bottom = Math.max(bottom, ty);
            }
            y = bottom + 6;
        }
        return y;
    }

    /** Search results: every hit, in a grid across the sheet. */
    private int drawResults(final List<PowerSearch.Hit> found, final int x0, final int y0, final int width,
        final float z, final Hit hover) {
        if (found.isEmpty()) {
            Hyb.text(
                "Nothing matches. Machines are found by name and by every fuel and product",
                x0,
                y0 + 4,
                Hyb.MUTED);
            Hyb.text("they can run on.", x0, y0 + 14, Hyb.MUTED);
            return y0 + 26;
        }
        final int cols = columns(width);
        final int colW = Math.min(COL_MAX + 40, (width - (cols - 1) * GAP) / cols);
        int y = y0, rowBottom = y0;
        for (int i = 0; i < found.size(); i++) {
            final int col = i % cols;
            if (col == 0 && i > 0) {
                y = rowBottom + 4;
            }
            rowBottom = Math.max(rowBottom, drawTile(found.get(i), x0 + col * (colW + GAP), y, colW, z, hover));
        }
        return rowBottom + 4;
    }

    /** One machine: its picture, its name and unlock tier, and on a search what matched; returns the bottom. */
    private int drawTile(final PowerSearch.Hit hit, final int x, final int y, final int w, final float z,
        final Hit hover) {
        final PowerSource source = hit.source();
        final int h = ART_H + LABEL_H + (hit.via() != null ? VIA_H : 0) + 2;
        final boolean hot = hover != null && hover.kind() == Kind.TILE && hover.data() == hit;
        Hyb.rect(x, y, w, h, hot ? 0xB3FCD34D : TILE_EDGE);
        Hyb.rect(x + 1, y + 1, w - 2, h - 2, hot ? TILE_HOT : TILE_BG);
        Hyb.rect(x + 1, y + 1, w - 2, ART_H, ART_BG);
        Hyb.rect(x + 1, y + ART_H + 1, w - 2, 1, TILE_EDGE);
        drawArt(source, x + 4, y + 4, w - 8, ART_H - 6, z);
        final String tier = source.unlock();
        final int badgeW = tier == null ? 0 : Hyb.width(tier) + 6;
        Hyb.text(Hyb.fit(source.name(), w - 10 - badgeW), x + 4, y + ART_H + 5, Hyb.INK);
        if (tier != null) badge(tier, x + w - 3 - badgeW, y + ART_H + 4, badgeW);
        if (hit.via() != null) {
            final String via = (hit.via()
                .takes() ? "> Takes " : "< Makes ") + hit.via()
                    .name();
            Hyb.text(Hyb.fit(via, w - 8), x + 4, y + ART_H + LABEL_H + 3, CYAN);
        }
        // Only what is in view takes a click.
        hits.add(new Hit(Kind.TILE, x, y, x + w, y + h, hit));
        return y + h;
    }

    /** The structure render, fitted; else the machine's block, as large as the game draws it crisply. */
    private void drawArt(final PowerSource source, final float x, final float y, final float w, final float h,
        final float z) {
        final StructureArt.Art art = StructureArt.forMachine(source.id());
        if (art != null) {
            final float scale = Math.min(w / art.width(), h / art.height());
            final float pw = art.width() * scale, ph = art.height() * scale;
            Hyb.texture(art.location(), x + (w - pw) / 2f, y + (h - ph) / 2f, pw, ph);
            return;
        }
        final ItemStack stack = machineStacks.computeIfAbsent(source.id(), PowerPorts::machineStack);
        if (stack != null) {
            final float side = Math.min(48, Math.min(w, h));
            Hyb.item(stack, x + (w - side) / 2f, y + (h - side) / 2f, side, z);
        } else bolt(x + w / 2f - 3, y + h / 2f - 5, AMBER);
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
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        scroll = Math.max(0, scroll + (direction == UpOrDown.UP ? -1 : 1) * (ART_H + LABEL_H + 6));
        return true;
    }

    private Tip tip(final Hit hit) {
        return switch (hit.kind()) {
            case TILE -> {
                final PowerSearch.Hit h = (PowerSearch.Hit) hit.data();
                final PowerSource s = h.source();
                final Tip tip = Tip.of(s.name())
                    .sub(s.group().title + (s.unlock() != null ? ", from " + s.unlock() : ""))
                    .muted(s.blurb());
                if (h.via() != null) tip.row(
                    h.via()
                        .takes() ? "Takes" : "Makes",
                    h.via()
                        .name());
                yield tip.action(Tip.Input.LEFT, "Put it on the board");
            }
            case GROUP -> Tip.of("Kind of machine");
            case TIER -> Tip.of("The tier that unlocks it");
            case CLOSE -> Tip.of("Close (Esc)");
            default -> null;
        };
    }

    // endregion
}
