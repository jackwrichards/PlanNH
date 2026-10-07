package com.gtnhplanner.ui.library;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.Stencil;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.data.flowchart.balancer.Severity;
import com.gtnhplanner.importer.FfConverter;
import com.gtnhplanner.importer.ImportReport;
import com.gtnhplanner.importer.game.FactoryFlowImport;
import com.gtnhplanner.library.CommunityApi;
import com.gtnhplanner.library.LibraryFeed;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.popup.PickList;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.popup.Tip;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * Factory Flow's public setups in the planner, as the website's Library shows them: a search with the website's
 * syntax ({@code #tag}, {@code @name}), sort, tier and pack version, the setups as tiles, and the one picked in a pane
 * beside them with what it makes and needs. "Open as new plan" brings it in as a plan tab. It takes the board's place
 * while it is open.
 */
public final class LibraryView extends ParentWidget<LibraryView> implements Interactable {

    private static final int HEAD_H = 24, PAD = 8, GAP = 6, TILE_MIN_W = 214, TILE_H = 50, PANE_W = 252;
    private static final int TILE_BG = 0xFF24262B, TILE_HOT = 0xFF2E3036, TILE_EDGE = 0xFF3A3C43;
    private static final int EU_INK = 0xFFE7C35A, CYAN = 0xFF22D3EE;

    private static final String[][] SORTS = { { "top", "Top voted" }, { "active", "Recently active" },
        { "new", "Newest" }, { "downloads", "Most downloaded" }, { "machines", "Most machines" },
        { "power", "Highest power" }, { "lowPower", "Lowest power" }, { "tier", "Highest tier" },
        { "commented", "Latest comment" } };
    private static final String[] TIERS = { "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV",
        "UIV", "UMV", "UXV", "MAX" };

    private final BoardSession session;
    private final Runnable close, showOpened;
    private final LibraryFeed feed = new LibraryFeed();
    private final TextFieldWidget searchField;
    private String searchText = "";

    @Nullable
    private CommunityApi.Setup picked;
    /** The setup being downloaded to open, if any. */
    @Nullable
    private String opening;
    @Nullable
    private String openError;
    private int scroll, contentH;
    private long lastClick;
    @Nullable
    private String lastClickId;

    private enum Kind {
        SORT,
        TIER,
        VERSION,
        CLOSE,
        TILE,
        OPEN,
        WEB,
        RETRY,
        TAG,
        AUTHOR,
        TIER_BADGE,
        PANE_CLOSE,
        MAKES_CLEAR,
        SHELF,
        MY_TILE,
        NEW_TILE,
        ACCOUNT,
        MINE_CLEAR
    }

    private record Hit(Kind kind, int x0, int y0, int x1, int y1, @Nullable Object data) {

        boolean contains(final int x, final int y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    private final List<Hit> hits = new ArrayList<>(), lastHits = new ArrayList<>();

    /** Game stacks for the setups' resources, looked up once each. */
    private final Map<String, Object> icons = new HashMap<>();

    /**
     * @param close      back to the board
     * @param showOpened back to the board, showing a plan just opened from here
     */
    public LibraryView(final BoardSession session, final Runnable close, final Runnable showOpened) {
        this.session = session;
        this.close = close;
        this.showOpened = showOpened;
        searchField = new TextFieldWidget()
            .value(new StringValue.Dynamic(() -> searchText, s -> searchText = s == null ? "" : s))
            .hintText("Find a plan");
        child(searchField);
    }

    /** Opens on the shelf as it was left; the public list is fetched the first time it is shown. */
    public void opened() {
        if (!mine && !feed.started()) feed.restart();
    }

    /** Opens on My plans: every plan kept, open in a tab or not. */
    public void showMine() {
        shelf(true);
    }

    /** Opens on the public setups. */
    public void showPublic() {
        shelf(false);
    }

    /** Which shelf shows: My plans, or the public setups. Each keeps its own search. */
    boolean mine = true;
    private String mineSearch = "", publicSearch = "";

    private void shelf(final boolean toMine) {
        if (toMine != mine) {
            if (mine) mineSearch = searchText;
            else publicSearch = searchText;
            mine = toMine;
            searchText = mine ? mineSearch : publicSearch;
            searchField.setText(searchText);
            searchField.hintText(mine ? "Find a plan" : "Search setups (#tag, @name)");
            scroll = 0;
        }
        if (!mine && !feed.started()) feed.restart();
    }

    /** Shows the setups that make a resource ({@code kind:id}, as Factory Flow writes them). */
    public void showMaking(final String ffKey, final String label) {
        shelf(false);
        searchText = "";
        searchField.setText("");
        feed.set(
            feed.query()
                .withSearch("")
                .withMakes(List.of(ffKey)));
        makingLabel = label;
        scroll = 0;
    }

    @Nullable
    private String makingLabel;

    @Override
    public void onUpdate() {
        super.onUpdate();
        feed.poll();
        final String live = searchField.getText();
        if (live == null) return;
        if (mine) {
            if (!live.equals(searchText)) scroll = 0;
            searchText = live;
        } else if (!live.equals(
            feed.query()
                .search())) {
                    searchText = live;
                    feed.type(live);
                    scroll = 0;
                }
    }

    // region Layout

    int gridRight() {
        return getArea().width - (picked != null && !mine ? PANE_W + PAD : 0) - PAD;
    }

    int columns() {
        final int w = gridRight() - PAD;
        return Math.max(1, (w + GAP) / (TILE_MIN_W + GAP));
    }

    int tileW() {
        final int cols = columns();
        return (gridRight() - PAD - (cols - 1) * GAP) / cols;
    }

    // endregion

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        hits.clear();
        final int w = getArea().width, h = getArea().height;
        final float z = context.getCurrentDrawingZ();
        Hyb.rect(0, 0, w, h, Hyb.CANVAS);
        final Hit hover = hitAt(lastHits);
        drawHeader(w, hover);
        final int top = HEAD_H + 4;
        Stencil.apply(0, top, gridRight() + PAD, h - top, context);
        if (mine) drawMine(top, h, z, hover);
        else drawGrid(top, h, z, hover);
        Stencil.remove();
        if (picked != null && !mine) drawPane(w, h, z, hover);
        lastHits.clear();
        lastHits.addAll(hits);
        // Near the end of what is loaded: the next page.
        if (!mine && contentH - scroll < (h - top) * 2) feed.more();
    }

    @Override
    public void drawOverlay(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        super.drawOverlay(context, widgetTheme);
        final Hit hover = hitAt(lastHits);
        if (hover == null) return;
        final List<String> lines = tip(hover);
        if (lines.isEmpty()) return;
        final Tip tip = Tip.ofLines(lines);
        if (tip != null) tip.drawNear(localX(), localY(), getArea().width, getArea().height);
    }

    private void drawHeader(final int w, final Hit hover) {
        Hyb.rect(0, 0, w, HEAD_H, 0xFF2A2D33);
        Hyb.rect(0, HEAD_H - 1, w, 1, Hyb.RING);
        int x = PAD;
        final int plans = Plan.getInstance()
            .getGraphs()
            .size();
        x = shelfTab(x, "MY PLANS", Integer.toString(plans), true, hover) + 6;
        x = shelfTab(x, "PUBLIC SETUPS", feed.total() < 0 ? "..." : Fmt.compact(feed.total()), false, hover) + 12;
        // Right to left: close, then the public list's version, tier and sort.
        int right = w - PAD;
        right = closeKey(right, 4, Kind.CLOSE, hover) - 8;
        right = accountKey(right, hover) - 10;
        if (mine) {
            searchField.pos(x, 5)
                .size(Math.max(80, Math.min(240, right - x)), 14);
            return;
        }
        final CommunityApi.Query q = feed.query();
        right = key(
            right,
            q.gameVersion()
                .isEmpty() ? "Any version" : q.gameVersion(),
            Kind.VERSION,
            hover) - 4;
        right = key(right, q.maxTierIndex() < 0 ? "Any tier" : "Up to " + TIERS[q.maxTierIndex()], Kind.TIER, hover)
            - 4;
        right = key(right, sortLabel(q.sort()), Kind.SORT, hover) - 8;
        // The search takes the room left between them, less a filter chip's when one shows.
        final int chipRoom = q.mine() ? Hyb.width("My posts") + 24
            : makingLabel != null && !q.makes()
                .isEmpty() ? Math.min(160, Hyb.width("Makes " + makingLabel) + 24) : 0;
        final int fieldW = Math.max(80, Math.min(300, right - x - chipRoom));
        searchField.pos(x, 5)
            .size(fieldW, 14);
        if (q.mine()) {
            final String chip = "My posts";
            final int cx = x + fieldW + 6, chipW = Hyb.width(chip) + 16;
            final boolean hot = hover != null && hover.kind() == Kind.MINE_CLEAR;
            Hyb.rect(cx, 5, chipW, 14, hot ? 0xFF3A4A52 : 0xFF2C3036);
            Hyb.text(chip, cx + 4, 8, CYAN);
            Hyb.text("x", cx + chipW - 8, 8, hot ? 0xFFFFFFFF : Hyb.MUTED);
            hits.add(new Hit(Kind.MINE_CLEAR, cx, 5, cx + chipW, 19, null));
        } else if (makingLabel != null && !q.makes()
            .isEmpty()) {
                final String chip = Hyb.fit("Makes " + makingLabel, right - x - fieldW - 26);
                final int cx = x + fieldW + 6, chipW = Hyb.width(chip) + 16;
                final boolean hot = hover != null && hover.kind() == Kind.MAKES_CLEAR;
                Hyb.rect(cx, 5, chipW, 14, hot ? 0xFF3A4A52 : 0xFF2C3036);
                Hyb.text(chip, cx + 4, 8, CYAN);
                Hyb.text("x", cx + chipW - 8, 8, hot ? 0xFFFFFFFF : Hyb.MUTED);
                hits.add(new Hit(Kind.MAKES_CLEAR, cx, 5, cx + chipW, 19, null));
            }
    }

    /**
     * Who is signed in to gtnhplanner.com, as a key: their name with a dot (a menu: their posts, signing out), or
     * "Sign in". Returns its left edge.
     */
    private int accountKey(final int right, final Hit hover) {
        final String who = com.gtnhplanner.library.Account.username();
        final String label = who == null ? "Sign in" : who;
        final int kw = Hyb.width(label) + (who == null ? 12 : 22), x = right - kw;
        final boolean hot = hover != null && hover.kind() == Kind.ACCOUNT;
        Hyb.bevel(x, 4, kw, 16, hot ? Hyb.KEY_HOVER : Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
        if (who == null) Hyb.text(label, x + 6, 8, CYAN);
        else {
            // Signed in: a green dot, the name, a chevron.
            Hyb.rect(x + 5, 10, 3, 3, 0xFF5EE9B5);
            Hyb.text(label, x + 11, 8, Hyb.INK);
            final int cx = x + kw - 8;
            Hyb.rect(cx, 11, 5, 1, Hyb.MUTED);
            Hyb.rect(cx + 1, 12, 3, 1, Hyb.MUTED);
            Hyb.rect(cx + 2, 13, 1, 1, Hyb.MUTED);
        }
        hits.add(new Hit(Kind.ACCOUNT, x, 4, right, 20, null));
        return x;
    }

    private void accountMenu(final Hit hit) {
        if (!com.gtnhplanner.library.Account.signedIn()) {
            AccountForms.signIn(getPanel(), () -> {});
            return;
        }
        final List<PickList.Entry> rows = new ArrayList<>();
        rows.add(PickList.Entry.of("My posts", () -> {
            shelf(false);
            refilter(
                feed.query()
                    .withMine(true));
        }));
        rows.add(
            new PickList.Entry(
                null,
                "Sign out",
                "",
                Hyb.INK,
                false,
                () -> AccountForms.signOut(text -> session.flash(Severity.INFO, text))));
        Popup.open(
            getPanel(),
            PickList.popup("gtnhplanner_account", "gtnhplanner.com", rows, false, hit.x1() - hit.x0()),
            getArea().x + hit.x0(),
            getArea().y + hit.y1() + 2);
    }

    private static String sortLabel(final String sort) {
        for (final String[] s : SORTS) if (s[0].equals(sort)) return s[1];
        return sort;
    }

    /** A dropdown key, right-aligned at {@code right}; returns its left edge. */
    private int key(final int right, final String label, final Kind kind, final Hit hover) {
        final int kw = Hyb.width(label) + 18, x = right - kw;
        final boolean hot = hover != null && hover.kind() == kind;
        Hyb.bevel(x, 4, kw, 16, hot ? Hyb.KEY_HOVER : Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
        Hyb.text(label, x + 5, 8, Hyb.INK);
        // A chevron.
        final int cx = x + kw - 9;
        Hyb.rect(cx, 11, 5, 1, Hyb.MUTED);
        Hyb.rect(cx + 1, 12, 3, 1, Hyb.MUTED);
        Hyb.rect(cx + 2, 13, 1, 1, Hyb.MUTED);
        hits.add(new Hit(kind, x, 4, right, 20, null));
        return x;
    }

    private int closeKey(final int right, final int y, final Kind kind, final Hit hover) {
        final int x = right - 16;
        final boolean hot = hover != null && hover.kind() == kind;
        Hyb.bevel(x, y, 16, 16, hot ? Hyb.KEY_HOVER : Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
        Hyb.textCentered("x", x + 8.5f, y + 4, Hyb.INK);
        hits.add(new Hit(kind, x, y, right, y + 16, null));
        return x;
    }

    private void drawGrid(final int top, final int h, final float z, final Hit hover) {
        final List<CommunityApi.Setup> setups = feed.setups();
        final int cols = columns(), tw = tileW();
        int y = top + PAD - scroll;
        if (setups.isEmpty()) {
            final String msg = feed.error() != null ? "Couldn't reach gtnhplanner.com: " + feed.error()
                : feed.loading() || !feed.started() ? "Loading setups from gtnhplanner.com..." : "No setups match.";
            final float cx = (PAD + gridRight()) / 2f, cy = top + (h - top) / 2f - 10;
            Hyb.textCentered(msg, cx, cy, feed.error() != null ? Hyb.AMBER_INK : Hyb.MUTED);
            if (feed.error() != null) retryKey(cx, cy + 14, hover);
            contentH = 0;
            return;
        }
        for (int i = 0; i < setups.size(); i++) {
            final int col = i % cols, row = i / cols;
            final int tx = PAD + col * (tw + GAP), ty = y + row * (TILE_H + GAP);
            if (ty + TILE_H < top || ty > h) continue;
            tile(setups.get(i), tx, ty, tw, z, hover);
        }
        final int rows = (setups.size() + cols - 1) / cols;
        int end = y + rows * (TILE_H + GAP);
        if (feed.loading() || feed.error() != null) {
            final String msg = feed.error() != null ? "Couldn't load more: " + feed.error() : "Loading more...";
            Hyb.textCentered(msg, (PAD + gridRight()) / 2f, end + 4, feed.error() != null ? Hyb.AMBER_INK : Hyb.MUTED);
            if (feed.error() != null) retryKey((PAD + gridRight()) / 2f, end + 18, hover);
            end += 36;
        }
        contentH = end + scroll - top;
        final int visible = h - top, max = Math.max(0, contentH - visible);
        if (scroll > max) scroll = max;
        if (max > 0) {
            final int thumb = Math.max(12, visible * visible / contentH);
            Hyb.rect(gridRight() + PAD - 4, top + (visible - thumb) * scroll / max, 2, thumb, Hyb.MUTED);
        }
    }

    private void retryKey(final float cx, final float y, final Hit hover) {
        final String label = "Try again";
        final int kw = Hyb.width(label) + 12, x = Math.round(cx - kw / 2f);
        final boolean hot = hover != null && hover.kind() == Kind.RETRY;
        Hyb.bevel(x, y, kw, 16, hot ? Hyb.KEY_HOVER : Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
        Hyb.text(label, x + 6, y + 4, Hyb.INK);
        hits.add(new Hit(Kind.RETRY, x, Math.round(y), x + kw, Math.round(y) + 16, null));
    }

    /** One setup as the website's tile: its face, name and tier, who and when, then its numbers. */
    private void tile(final CommunityApi.Setup s, final int x, final int y, final int w, final float z,
        final Hit hover) {
        final boolean hot = hover != null && hover.kind() == Kind.TILE && hover.data() == s;
        final boolean sel = picked != null && picked.id()
            .equals(s.id());
        Hyb.rect(x + 3, y + 3, w, TILE_H, 0x66000000);
        Hyb.rect(x, y, w, TILE_H, sel ? CYAN : hot ? Hyb.RING : TILE_EDGE);
        Hyb.rect(x + 1, y + 1, w - 2, TILE_H - 2, hot || sel ? TILE_HOT : TILE_BG);
        face(s.icon(), x + 5, y + 5, 24, z);
        final int nameX = x + 34;
        int nameRight = x + w - 6;
        if (!s.tier()
            .isEmpty()) nameRight -= badge(s.tier(), nameRight, y + 6, true) + 4;
        Hyb.text(Hyb.fit(s.name(), nameRight - nameX), nameX, y + 6, 0xFFFFFFFF);
        final String who = (s.author()
            .isEmpty() ? "" : s.author() + " · ") + ago(s.active());
        Hyb.text(Hyb.fit(who, x + w - 6 - nameX), nameX, y + 17, 0xFF8A8C94);
        // The numbers: machines and EU/t at the left, votes and downloads at the right.
        final int ry = y + TILE_H - 13;
        int sx = x + 6;
        sx = stat(sx, ry, Fmt.compact(s.machines()) + (s.machines() == 1 ? " machine" : " machines"), Hyb.MUTED) + 8;
        if (s.euPerTick() > 0) stat(sx, ry, Fmt.power(s.euPerTick()) + " EU/t", EU_INK);
        int rx = x + w - 6;
        rx = statRight(rx, ry, Fmt.compact(s.downloads()), Glyph.DOWN) - 7;
        if (s.comments() > 0) rx = statRight(rx, ry, Integer.toString(s.comments()), Glyph.BUBBLE) - 7;
        statRight(rx, ry, Integer.toString(s.upvotes() - s.downvotes()), Glyph.UP);
        hits.add(new Hit(Kind.TILE, x, y, x + w, y + TILE_H, s));
    }

    private int stat(final int x, final int y, final String text, final int color) {
        Hyb.text(text, x, y, color);
        return x + Hyb.width(text);
    }

    private enum Glyph {
        UP,
        DOWN,
        BUBBLE
    }

    /** A number with its glyph before it, right-aligned at {@code right}; returns its left edge. */
    private int statRight(final int right, final int y, final String text, final Glyph glyph) {
        final int tx = right - Hyb.width(text);
        Hyb.text(text, tx, y, Hyb.MUTED);
        final int gx = tx - 7, gy = y + 1;
        final int c = Hyb.MUTED;
        switch (glyph) {
            case UP -> Hyb.triangle(gx, gy + 5, gx + 5, gy + 5, gx + 2.5f, gy + 1, c);
            case DOWN -> {
                Hyb.rect(gx + 2, gy, 1, 3, c);
                Hyb.triangle(gx, gy + 3, gx + 5, gy + 3, gx + 2.5f, gy + 6, c);
            }
            case BUBBLE -> {
                Hyb.rect(gx, gy + 1, 5, 4, c);
                Hyb.rect(gx + 1, gy + 5, 1, 1, c);
            }
        }
        return gx;
    }

    /** A tier in its colours; right-aligned at {@code x} when {@code fromRight}. Returns its width. */
    private static int badge(final String tier, final int x, final int y, final boolean fromRight) {
        final Hyb.Tier t = Hyb.tier(tier);
        final int w = Hyb.width(t.name()) + 6, bx = fromRight ? x - w : x;
        Hyb.rect(bx, y - 1, w, 10, t.border());
        Hyb.rect(bx + 1, y, w - 2, 8, t.bg());
        com.cleanroommc.modularui.drawable.GuiDraw.drawText(t.name(), bx + 3, y, 1f, t.text(), false);
        return w;
    }

    /** A setup's face, or a resource's icon: the game's own item or fluid, else a quiet placeholder. */
    private void face(@Nullable final CommunityApi.Resource r, final int x, final int y, final int size,
        final float z) {
        final Object stack = r == null ? null : stackOf(r);
        if (stack instanceof final ItemStack item) Hyb.icon(item, null, x, y, size, z);
        else if (stack instanceof final FluidStack fluid) Hyb.icon(null, fluid, x, y, size, z);
        else {
            Hyb.rect(x, y, size, size, 0xFF1B1D21);
            final int c = 0xFF4A4C54, m = size / 4;
            Hyb.rect(x + m, y + m, size / 5, size / 5, c);
            Hyb.rect(x + size - m - size / 5, y + size - m - size / 5, size / 5, size / 5, c);
        }
    }

    @Nullable
    private Object stackOf(final CommunityApi.Resource r) {
        final String key = r.kind() + ":" + r.id();
        if (icons.containsKey(key)) return icons.get(key);
        final Object stack = r.fluid() ? FactoryFlowImport.fluid(r.id()) : FactoryFlowImport.item(r.id());
        icons.put(key, stack);
        return stack;
    }

    // region My plans

    /** One of the header's two shelves: its name and count, underlined in cyan when it is the one shown. */
    private int shelfTab(final int x, final String label, final String count, final boolean isMine, final Hit hover) {
        final boolean on = isMine == mine;
        final boolean hot = hover != null && hover.kind() == Kind.SHELF && hover.data() == (Boolean) isMine;
        Hyb.text(label, x, 8, on ? Hyb.INK : hot ? 0xFFD0D2D8 : Hyb.MUTED);
        int end = x + Hyb.width(label) + 5;
        final int cw = Hyb.width(count) + 6;
        Hyb.rect(end, 7, cw, 10, 0xFF353942);
        Hyb.text(count, end + 3, 8, 0xFFA3A3A3);
        end += cw;
        if (on) Hyb.rect(x, HEAD_H - 3, end - x, 2, CYAN);
        hits.add(new Hit(Kind.SHELF, x - 2, 0, end + 2, HEAD_H, isMine));
        return end;
    }

    /** Every plan kept, the most recently open first, after a tile for a new one. */
    private void drawMine(final int top, final int h, final float z, final Hit hover) {
        final Plan plan = Plan.getInstance();
        final List<Graph> graphs = plan.getGraphs();
        final String f = searchText.trim()
            .toLowerCase(Locale.ROOT);
        final List<Integer> shown = new ArrayList<>();
        for (final int i : plan.byRecency()) {
            if (f.isEmpty() || graphs.get(i)
                .getName()
                .toLowerCase(Locale.ROOT)
                .contains(f)) shown.add(i);
        }
        final int cols = columns(), tw = tileW(), y0 = top + PAD - scroll;
        int n = 0;
        if (f.isEmpty()) {
            newTile(PAD, y0, tw, hover);
            n = 1;
        }
        for (final int slot : shown) {
            final int tx = PAD + n % cols * (tw + GAP), ty = y0 + n / cols * (TILE_H + GAP);
            if (ty + TILE_H >= top && ty <= h) planTile(slot, graphs.get(slot), tx, ty, tw, z, hover);
            n++;
        }
        if (n == 0)
            Hyb.textCentered("No plan by that name.", (PAD + gridRight()) / 2f, top + (h - top) / 2f, Hyb.MUTED);
        contentH = (n + cols - 1) / cols * (TILE_H + GAP) + PAD;
        final int visible = h - top, max = Math.max(0, contentH - visible);
        if (scroll > max) scroll = max;
        if (max > 0) {
            final int thumb = Math.max(12, visible * visible / contentH);
            Hyb.rect(gridRight() + PAD - 4, top + (visible - thumb) * scroll / max, 2, thumb, Hyb.MUTED);
        }
    }

    private void newTile(final int x, final int y, final int w, final Hit hover) {
        final boolean hot = hover != null && hover.kind() == Kind.NEW_TILE;
        Hyb.dashed(x, y, w, TILE_H, hot ? CYAN : 0xFF4A4C54, 4, 3);
        if (hot) Hyb.rect(x + 1, y + 1, w - 2, TILE_H - 2, 0x1422D3EE);
        Hyb.textCentered("+ New plan", x + w / 2f, y + TILE_H / 2f - 4, hot ? CYAN : 0xFF9FD9E6);
        hits.add(new Hit(Kind.NEW_TILE, x, y, x + w, y + TILE_H, null));
    }

    /** A plan of yours: what it makes, its name, how big it is, and when it was last open. */
    private void planTile(final int slot, final Graph g, final int x, final int y, final int w, final float z,
        final Hit hover) {
        final boolean hot = hover != null && hover.kind() == Kind.MY_TILE && hover.data() == (Integer) slot;
        final boolean current = slot == session.activeSlot();
        Hyb.rect(x + 3, y + 3, w, TILE_H, 0x66000000);
        Hyb.rect(x, y, w, TILE_H, current ? CYAN : hot ? Hyb.RING : TILE_EDGE);
        Hyb.rect(x + 1, y + 1, w - 2, TILE_H - 2, hot || current ? TILE_HOT : TILE_BG);
        final Object face = planFace(g);
        if (face instanceof final ItemStack item) Hyb.icon(item, null, x + 5, y + 5, 24, z);
        else if (face instanceof final FluidStack fluid) Hyb.icon(null, fluid, x + 5, y + 5, 24, z);
        else face(null, x + 5, y + 5, 24, z);
        final int nameX = x + 34;
        int nameRight = x + w - 6;
        if (g.isOpen()) {
            final String chip = current ? "SHOWING" : "OPEN";
            final int cw = Hyb.width(chip) + 6;
            nameRight -= cw + 4;
            Hyb.rect(nameRight + 4, y + 5, cw, 10, current ? 0xFF1E4A52 : 0xFF353942);
            Hyb.text(chip, nameRight + 7, y + 6, current ? CYAN : 0xFFA3A3A3);
        }
        Hyb.text(Hyb.fit(g.getName(), nameRight - nameX), nameX, y + 6, 0xFFFFFFFF);
        final int cards = g.getNodes()
            .size(), drawers = g.getDrawers()
                .size();
        final String size = cards + (cards == 1 ? " card" : " cards") + " · " + drawers + (drawers == 1 ? " drawer" : " drawers");
        Hyb.text(Hyb.fit(size, x + w - 6 - nameX), nameX, y + 17, 0xFF8A8C94);
        final String when = g.getLastOpen() > 0 ? "last open " + agoMs(g.getLastOpen()) : "not opened yet";
        Hyb.text(when, x + 6, y + TILE_H - 13, Hyb.MUTED);
        hits.add(new Hit(Kind.MY_TILE, x, y, x + w, y + TILE_H, slot));
    }

    /** A plan's face: the first thing it makes into a drawer, else the first output of its first card. */
    @Nullable
    private Object planFace(final Graph g) {
        String key = null;
        for (final com.gtnhplanner.data.flowchart.Drawer d : g.getDrawers()) {
            if (d.getKind() == com.gtnhplanner.data.flowchart.Drawer.Kind.PRODUCT) {
                key = d.getResourceKey();
                break;
            }
        }
        if (key == null) {
            for (final com.gtnhplanner.data.flowchart.Node n : g.getNodes()) {
                if (!n.outputs.isEmpty()) {
                    key = com.gtnhplanner.ui.Resources.key(n.outputs.get(0));
                    break;
                }
            }
        }
        if (key == null || key.isEmpty()) return null;
        final String cacheKey = "plan:" + key;
        if (icons.containsKey(cacheKey)) return icons.get(cacheKey);
        final Object stack = com.gtnhplanner.ui.Resources.isFluid(key) ? com.gtnhplanner.ui.Resources.fluid(key)
            : com.gtnhplanner.ui.Resources.item(key);
        icons.put(cacheKey, stack);
        return stack;
    }

    /** Opens one of your plans in a tab, back on the board. */
    private void openPlan(final int slot) {
        session.switchSlot(slot);
        close.run();
    }

    private void planMenu(final int slot, final Hit hit) {
        final Graph g = Plan.getInstance()
            .getGraphs()
            .get(slot);
        final int sx = getArea().x + hit.x0() + 8, sy = getArea().y + hit.y0() + 20;
        final List<PickList.Entry> rows = new ArrayList<>();
        rows.add(PickList.Entry.of("Open", () -> openPlan(slot)));
        rows.add(
            PickList.Entry.of(
                "Rename",
                () -> Popup.open(
                    getPanel(),
                    com.gtnhplanner.ui.popup.TextPopup
                        .create("Plan name", g.getName(), name -> session.renameSlot(slot, name)),
                    sx,
                    sy)));
        rows.add(
            new PickList.Entry(
                null,
                "Copy plan code",
                "to paste or share",
                Hyb.INK,
                false,
                () -> session.copyPlan(slot)));
        rows.add(
            new PickList.Entry(
                null,
                "Post to library...",
                "share it",
                Hyb.INK,
                false,
                () -> AccountForms.post(getPanel(), session, g)));
        rows.add(
            new PickList.Entry(
                null,
                "Delete plan",
                "",
                Hyb.RED_INK,
                false,
                () -> session.confirmDelete(slot, getPanel(), sx, sy)));
        Popup.open(getPanel(), PickList.popup("gtnhplanner_my_plan", null, rows, false, 150), sx, sy);
    }

    // endregion

    // region The picked setup

    private void drawPane(final int w, final int h, final float z, final Hit hover) {
        final CommunityApi.Setup s = picked;
        final int px = w - PANE_W - PAD, py = HEAD_H + 4 + PAD, pw = PANE_W, ph = h - py - PAD;
        Hyb.rect(px + 3, py + 3, pw, ph, 0x66000000);
        Hyb.rect(px, py, pw, ph, TILE_EDGE);
        Hyb.rect(px + 1, py + 1, pw - 2, ph - 2, 0xFF202226);
        closeKey(px + pw - 4, py + 4, Kind.PANE_CLOSE, hover);
        int y = py + 8;
        face(s.icon(), px + 8, y, 32, z);
        final int tx = px + 46, tr = px + pw - 24;
        final List<String> name = wrap(s.name(), tr - tx, 2);
        for (final String line : name) {
            Hyb.text(line, tx, y, 0xFFFFFFFF);
            y += 10;
        }
        final String who = s.author()
            .isEmpty() ? "" : "by " + s.author();
        if (!who.isEmpty()) {
            Hyb.text(Hyb.fit(who, tr - tx), tx, y + 1, 0xFF8A8C94);
            hits.add(new Hit(Kind.AUTHOR, tx, y, tx + Hyb.width(who), y + 10, s.author()));
        }
        y = Math.max(y + 12, py + 44);
        // Tier, pack version and when, on one line.
        int lx = px + 8;
        if (!s.tier()
            .isEmpty()) {
            final int bw = badge(s.tier(), lx, y + 1, false);
            hits.add(new Hit(Kind.TIER_BADGE, lx, y, lx + bw, y + 10, s.tierIndex()));
            lx += bw + 6;
        }
        Hyb.text(Hyb.fit(s.gameVersion() + " · " + ago(s.active()), px + pw - 8 - lx), lx, y + 1, Hyb.MUTED);
        y += 14;
        // Tags, as the website's chips.
        if (!s.tags()
            .isEmpty()) {
            int cx = px + 8;
            for (final String tag : s.tags()) {
                final String t = "#" + tag;
                final int tw = Hyb.width(t) + 6;
                if (cx + tw > px + pw - 8) {
                    cx = px + 8;
                    y += 13;
                }
                final boolean hot = hover != null && hover.kind() == Kind.TAG && tag.equals(hover.data());
                Hyb.rect(cx, y, tw, 11, hot ? 0xFF3A4A52 : 0xFF2C3036);
                Hyb.text(t, cx + 3, y + 2, hot ? CYAN : 0xFF9FD9E6);
                hits.add(new Hit(Kind.TAG, cx, y, cx + tw, y + 11, tag));
                cx += tw + 4;
            }
            y += 16;
        }
        // The numbers.
        y = numbers(s, px + 8, y, pw - 16) + 6;
        // The buttons, at the bottom.
        final int by = py + ph - 26;
        final boolean busy = s.id()
            .equals(opening);
        final String openLabel = busy ? "Opening..." : "Open as new plan";
        final int ow = Math.max(110, Hyb.width(openLabel) + 16);
        final boolean openHot = hover != null && hover.kind() == Kind.OPEN;
        Hyb.rect(px + 8, by, ow, 18, openHot && !busy ? 0xFF38E1F5 : CYAN);
        Hyb.rect(px + 8, by, ow, 1, 0x66FFFFFF);
        com.cleanroommc.modularui.drawable.GuiDraw
            .drawText(openLabel, px + 8 + (ow - Hyb.width(openLabel)) / 2f, by + 5, 1f, 0xFF0B1A1E, false);
        hits.add(new Hit(Kind.OPEN, px + 8, by, px + 8 + ow, by + 18, s));
        final String web = "On the website";
        final int ww = Hyb.width(web) + 14, wx = px + 8 + ow + 6;
        final boolean webHot = hover != null && hover.kind() == Kind.WEB;
        Hyb.bevel(wx, by + 1, ww, 16, webHot ? Hyb.KEY_HOVER : Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
        Hyb.text(web, wx + 7, by + 5, Hyb.INK);
        hits.add(new Hit(Kind.WEB, wx, by + 1, wx + ww, by + 17, s));
        if (openError != null) {
            Hyb.text(Hyb.fit(openError, pw - 16), px + 8, by - 12, Hyb.AMBER_INK);
        }
        // What it makes and needs, then the description, in what room is left above the buttons.
        final int bottom = by - (openError != null ? 16 : 6);
        y = resources("MAKES", s.outputs(), px + 8, y, pw - 16, bottom, z, Hyb.PRODUCT_INK);
        y = resources("NEEDS", s.needs(), px + 8, y, pw - 16, bottom, z, Hyb.SOURCE_INK);
        if (!s.description()
            .isBlank() && y + 12 < bottom) {
            for (final String line : wrap(s.description(), pw - 16, (bottom - y - 2) / 10)) {
                Hyb.text(line, px + 8, y, 0xFFB8BAC2);
                y += 10;
            }
        }
    }

    /** EU/t, machines, cards, votes, downloads: a label over each number, side by side. */
    private int numbers(final CommunityApi.Setup s, final int x, final int y, final int w) {
        final String[][] cells = { { "EU/T", s.euPerTick() > 0 ? Fmt.power(s.euPerTick()) : "0" },
            { "MACHINES", Fmt.compact(s.machines()) }, { "VOTES", Integer.toString(s.upvotes() - s.downvotes()) },
            { "DOWNLOADS", Fmt.compact(s.downloads()) } };
        final int cw = w / cells.length;
        for (int i = 0; i < cells.length; i++) {
            final int cx = x + i * cw;
            Hyb.rect(cx, y, cw - 3, 24, 0xFF2A2D33);
            Hyb.text(cells[i][0], cx + 4, y + 3, 0xFF7A7C84);
            Hyb.text(cells[i][1], cx + 4, y + 13, i == 0 ? EU_INK : Hyb.INK);
        }
        return y + 24;
    }

    private int resources(final String title, final List<CommunityApi.Resource> list, final int x, int y, final int w,
        final int bottom, final float z, final int ink) {
        if (list.isEmpty() || y + 26 > bottom) return y;
        Hyb.text(title, x, y + 2, ink);
        y += 13;
        final Fmt.RateUnit unit = session.rateUnit();
        for (final CommunityApi.Resource r : list) {
            if (y + 14 > bottom) {
                Hyb.text("...", x, y, Hyb.MUTED);
                return y + 12;
            }
            face(r, x, y, 12, z);
            final String rate = Fmt.compact(r.perSecond() * unit.perSecond) + (r.fluid() ? " L" : "") + unit.suffix;
            Hyb.textRight(rate, x + w, y + 2, Hyb.MUTED);
            Hyb.text(Hyb.fit(r.name(), w - 18 - Hyb.width(rate) - 6), x + 16, y + 2, Hyb.INK);
            y += 14;
        }
        return y + 4;
    }

    // endregion

    // region Input

    @Override
    public Result onMousePressed(final int mouseButton) {
        final Hit hit = hitAt(lastHits);
        if (hit == null) return Result.IGNORE;
        if (mouseButton == 1 && hit.kind() == Kind.MY_TILE) {
            Hyb.click();
            planMenu((Integer) hit.data(), hit);
            return Result.SUCCESS;
        }
        if (mouseButton != 0) return Result.SUCCESS;
        Hyb.click();
        final CommunityApi.Query q = feed.query();
        switch (hit.kind()) {
            case CLOSE -> close.run();
            case SHELF -> shelf((Boolean) hit.data());
            case ACCOUNT -> accountMenu(hit);
            case MINE_CLEAR -> refilter(q.withMine(false));
            case MY_TILE -> openPlan((Integer) hit.data());
            case NEW_TILE -> {
                session.addSlot();
                close.run();
            }
            case PANE_CLOSE -> pick(null);
            case MAKES_CLEAR -> refilter(q.withMakes(List.of()));
            case SORT -> {
                final List<PickList.Entry> rows = new ArrayList<>();
                for (final String[] s : SORTS) rows.add(
                    new PickList.Entry(
                        null,
                        s[1],
                        "",
                        Hyb.INK,
                        s[0].equals(q.sort()),
                        () -> refilter(q.withSort(s[0]))));
                dropdown("gtnhplanner_library_sort", rows, hit);
            }
            case TIER -> {
                final List<PickList.Entry> rows = new ArrayList<>();
                rows.add(
                    new PickList.Entry(
                        null,
                        "Any tier",
                        "",
                        Hyb.INK,
                        q.maxTierIndex() < 0,
                        () -> refilter(q.withMaxTier(-1))));
                for (int i = 0; i < TIERS.length; i++) {
                    final int t = i;
                    rows.add(
                        new PickList.Entry(
                            null,
                            "Up to " + TIERS[i],
                            "",
                            Hyb.tier(TIERS[i])
                                .bg(),
                            q.maxTierIndex() == i,
                            () -> refilter(q.withMaxTier(t))));
                }
                dropdown("gtnhplanner_library_tier", rows, hit);
            }
            case VERSION -> {
                final List<PickList.Entry> rows = new ArrayList<>();
                rows.add(
                    new PickList.Entry(
                        null,
                        "Any version",
                        "",
                        Hyb.INK,
                        q.gameVersion()
                            .isEmpty(),
                        () -> refilter(q.withVersion(""))));
                for (final String v : feed.gameVersions()) rows.add(
                    new PickList.Entry(
                        null,
                        v,
                        "",
                        Hyb.INK,
                        v.equals(q.gameVersion()),
                        () -> refilter(q.withVersion(v))));
                dropdown("gtnhplanner_library_version", rows, hit);
            }
            case RETRY -> feed.restart();
            case TILE -> {
                final CommunityApi.Setup s = (CommunityApi.Setup) hit.data();
                final long now = System.currentTimeMillis();
                if (s.id()
                    .equals(lastClickId) && now - lastClick < 400) open(s);
                else pick(s);
                lastClick = now;
                lastClickId = s.id();
            }
            case OPEN -> open((CommunityApi.Setup) hit.data());
            case WEB -> openInBrowser(((CommunityApi.Setup) hit.data()).link());
            case TAG -> search("#" + hit.data());
            case AUTHOR -> search("@" + hit.data());
            case TIER_BADGE -> refilter(q.withMaxTier((Integer) hit.data()));
        }
        return Result.SUCCESS;
    }

    private void search(final String text) {
        searchText = text;
        searchField.setText(text);
        feed.set(
            feed.query()
                .withSearch(text));
        scroll = 0;
    }

    private void refilter(final CommunityApi.Query q) {
        if (q.makes()
            .isEmpty()) makingLabel = null;
        feed.set(q);
        scroll = 0;
    }

    private void dropdown(final String name, final List<PickList.Entry> rows, final Hit hit) {
        Popup.open(
            getPanel(),
            PickList.popup(name, null, rows, false, hit.x1() - hit.x0(), 16),
            getArea().x + hit.x0(),
            getArea().y + hit.y1() + 2);
    }

    private void pick(@Nullable final CommunityApi.Setup s) {
        picked = s;
        openError = null;
    }

    /** Esc: the pane first, then the library. */
    public boolean escape() {
        if (picked != null) {
            pick(null);
            return true;
        }
        return false;
    }

    /** Downloads the setup and brings it in as a new plan tab; the library closes on the board showing it. */
    private void open(final CommunityApi.Setup s) {
        if (opening != null) return;
        pick(s);
        opening = s.id();
        feed.download(s, d -> {
            opening = null;
            try {
                final FfConverter.Result r = FactoryFlowImport.importAsSlot(d.planJson());
                final int missing = r.report()
                    .entries(ImportReport.Kind.UNMATCHED)
                    .size();
                showOpened.run();
                session.flash(
                    missing == 0 ? Severity.INFO : Severity.WARN,
                    "Opened '" + r.graph()
                        .getName()
                        + "' from the library: "
                        + r.report()
                            .summary());
            } catch (final RuntimeException e) {
                openError = "Couldn't read that plan: " + e.getMessage();
                com.gtnhplanner.GtnhPlanner.LOG.info("Library: could not import setup {}", s.id(), e);
            }
        }, why -> {
            opening = null;
            openError = "Couldn't download it: " + why;
        });
    }

    static void openInBrowser(final String url) {
        try {
            java.awt.Desktop.getDesktop()
                .browse(java.net.URI.create(url));
        } catch (final Exception | LinkageError e) {
            org.lwjgl.Sys.openURL(url);
        }
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        final Hit hit = hitAt(lastHits);
        if (picked != null && localX() > gridRight() + PAD) return true;
        if (hit != null && hit.y1() <= HEAD_H) return true;
        scroll = Math.max(0, scroll + (direction == UpOrDown.UP ? -1 : 1) * (TILE_H + GAP));
        return true;
    }

    private List<String> tip(final Hit hit) {
        return switch (hit.kind()) {
            case TILE -> {
                final CommunityApi.Setup s = (CommunityApi.Setup) hit.data();
                yield List.of(s.name(), "§7Click: details  Double-click: open as a new plan");
            }
            case OPEN -> List.of("Bring it in as a new plan tab", "§7Its recipes are matched to this pack's");
            case WEB -> List.of("Open its page on gtnhplanner.com", "§7Comments and votes are there");
            case TAG -> List.of("Setups tagged #" + hit.data());
            case AUTHOR -> List.of("Setups by " + hit.data());
            case TIER_BADGE -> List.of("Setups up to this tier");
            case CLOSE -> List.of("Back to the board (Esc)");
            case SORT -> List.of("Sort the setups");
            case TIER -> List.of("Only setups up to a tier");
            case VERSION -> List.of("Only setups made for a pack version");
            case MAKES_CLEAR -> List.of("Show every setup again");
            case SHELF -> (Boolean) hit.data()
                ? List.of("My plans", "§7Every plan you have made or opened, in a tab or not")
                : List.of("Public setups", "§7Everyone's shared setups, from gtnhplanner.com");
            case MY_TILE -> List.of(
                Plan.getInstance()
                    .getGraphs()
                    .get((Integer) hit.data())
                    .getName(),
                "§7Click: open  Right click: rename, copy, delete");
            case NEW_TILE -> List.of("Start a new plan");
            case ACCOUNT -> com.gtnhplanner.library.Account.signedIn()
                ? List.of("Signed in to gtnhplanner.com", "§7Click: your posts, or sign out")
                : List.of("Sign in to gtnhplanner.com", "§7To post your plans to the public library");
            case MINE_CLEAR -> List.of("Show everyone's setups again");
            default -> List.of();
        };
    }

    // endregion

    // region Helpers

    @Nullable
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

    /** "5m ago", "3h ago", "12d ago" from the site's ISO time. */
    /** "5m ago" and so on, from a time in ms since the epoch. */
    private static String agoMs(final long ms) {
        return ago(
            java.time.Instant.ofEpochMilli(ms)
                .atOffset(java.time.ZoneOffset.UTC)
                .toString());
    }

    private static String ago(final String iso) {
        try {
            final Duration d = Duration.between(OffsetDateTime.parse(iso), OffsetDateTime.now());
            final long m = Math.max(0, d.toMinutes());
            if (m < 60) return m + "m ago";
            if (m < 60 * 24) return m / 60 + "h ago";
            if (m < 60 * 24 * 60) return m / (60 * 24) + "d ago";
            return m / (60 * 24 * 30) + "mo ago";
        } catch (final RuntimeException e) {
            return "";
        }
    }

    /** Words onto lines no wider than {@code width}, at most {@code max} of them (the last cut short). */
    private static List<String> wrap(final String text, final int width, final int max) {
        final List<String> lines = new ArrayList<>();
        if (max <= 0) return lines;
        for (final String para : text.replace("\r", "")
            .split("\n")) {
            StringBuilder line = new StringBuilder();
            for (final String word : para.split(" ")) {
                final String next = line.length() == 0 ? word : line + " " + word;
                if (Hyb.width(next) <= width || line.length() == 0) line = new StringBuilder(next);
                else {
                    lines.add(line.toString());
                    if (lines.size() == max) return cutLast(lines, width);
                    line = new StringBuilder(word);
                }
            }
            lines.add(line.toString());
            if (lines.size() >= max) return cutLast(lines.subList(0, max), width);
        }
        return lines;
    }

    private static List<String> cutLast(final List<String> lines, final int width) {
        final List<String> out = new ArrayList<>(lines);
        out.set(out.size() - 1, Hyb.fit(out.get(out.size() - 1) + " ...", width));
        return out;
    }

    // endregion
}
