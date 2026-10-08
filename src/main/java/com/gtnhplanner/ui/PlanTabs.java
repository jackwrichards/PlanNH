package com.gtnhplanner.ui;

import java.util.ArrayList;
import java.util.List;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.Stencil;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.ui.popup.PickList;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.popup.TextPopup;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * The plan tabs on the top bar: one per open plan, then "+". A tab is only a plan that is open; closing it (its x,
 * or a middle click) keeps the plan, in the library's My plans, as on the website. Click switches; right-click
 * renames, copies, closes or deletes; the active tab is raised like a card.
 *
 * <p>
 * Every tab keeps its name readable. When they do not all fit, the strip scrolls sideways (the wheel, or the thin bar
 * along its bottom), fades at an edge with more beyond it, and keeps the open plan's tab in view; "+" stays put at the
 * end.
 */
final class PlanTabs extends Widget<PlanTabs> implements Interactable {

    private static final int TAB_MAX = 120, TAB_MIN = 48, TAB_GAP = 2;
    private static final int PLUS = 16;
    /** The close key on a hovered tab. */
    private static final int CLOSE = 9;
    /** How far the fades reach in from a clipped edge, and the scroll bar's thickness. */
    private static final int FADE = 18, BAR_H = 2;
    /** The top bar's colour, which the fades fade into. */
    private static final int BAR_BG = 0x0E0F12;

    private final BoardSession session;
    private int scroll;
    /** The plan whose tab was last brought into view; a change of plan scrolls to the new one. */
    private int shownActive = -1;
    private boolean draggingBar;
    private int barGrab;

    PlanTabs(final BoardSession session) {
        this.session = session;
    }

    private static int tabWidth(final Graph g) {
        // Room for the close key a hovered tab shows, so the name is not cut for it.
        return Math.max(TAB_MIN, Math.min(TAB_MAX, Hyb.width(g.getName()) + 14 + CLOSE));
    }

    /** One tab: the plan slot it shows, where it is in the strip (before scrolling) and how wide. */
    private record Tab(int slot, int x, int w) {}

    /** The width the tabs scroll in: the bar less the "+" key at its end. */
    private int stripW() {
        return Math.max(0, getArea().width - PLUS - 4);
    }

    /** Every open plan's tab, side by side at full width; the strip shows a window of them. */
    private List<Tab> tabs() {
        final List<Graph> slots = session.slots();
        final List<Tab> out = new ArrayList<>();
        int x = 0;
        for (final int slot : Plan.getInstance()
            .openSlots()) {
            final int w = tabWidth(slots.get(slot));
            out.add(new Tab(slot, x, w));
            x += w + TAB_GAP;
        }
        return out;
    }

    private static int contentW(final List<Tab> tabs) {
        if (tabs.isEmpty()) return 0;
        final Tab last = tabs.get(tabs.size() - 1);
        return last.x() + last.w();
    }

    private int maxScroll(final List<Tab> tabs) {
        return Math.max(0, contentW(tabs) - stripW());
    }

    /** The "+" key: right after the last tab, or at the strip's end once the tabs fill it. */
    private Tab plus(final List<Tab> tabs) {
        return new Tab(-1, Math.min(contentW(tabs) + TAB_GAP, stripW() + 4), PLUS);
    }

    /** Scrolls the open plan's tab into view when the open plan changes; keeps the scroll in range. */
    private void follow(final List<Tab> tabs) {
        final int active = session.activeSlot();
        if (active != shownActive) {
            shownActive = active;
            for (final Tab t : tabs) {
                if (t.slot() != active) continue;
                if (t.x() < scroll) scroll = t.x();
                else if (t.x() + t.w() > scroll + stripW()) scroll = t.x() + t.w() - stripW();
            }
        }
        scroll = Math.max(0, Math.min(maxScroll(tabs), scroll));
    }

    /** While the plan solves, a short cyan line runs along the bottom of its tab. */
    private static void solving(final int x, final int w, final int h) {
        final int seg = Math.max(8, w / 3);
        final int at = (int) ((w + seg) * (System.currentTimeMillis() % 900) / 900) - seg;
        final int from = Math.max(1, at), to = Math.min(w - 1, at + seg);
        if (to > from) Hyb.rect(x + from, h - 2, to - from, 1, 0xFF22D3EE);
    }

    private int mouseX() {
        return getContext().getAbsMouseX() - getArea().x;
    }

    private int mouseY() {
        return getContext().getAbsMouseY() - getArea().y;
    }

    /** The tab under the mouse, at its place on screen (scroll applied); "+" has slot -1. */
    private Tab tabAtMouse() {
        final int mx = mouseX();
        final List<Tab> tabs = tabs();
        final Tab plus = plus(tabs);
        if (mx >= plus.x() && mx < plus.x() + plus.w()) return plus;
        if (mx < 0 || mx >= stripW()) return null;
        for (final Tab t : tabs) {
            final int x = t.x() - scroll;
            if (mx >= x && mx < x + t.w()) return new Tab(t.slot(), x, t.w());
        }
        return null;
    }

    /** Whether the mouse is on the scroll bar's band along the strip's bottom, while there is one. */
    private boolean onBar() {
        return maxScroll(tabs()) > 0 && mouseY() >= getArea().height - 4 && mouseX() >= 0 && mouseX() < stripW();
    }

    /** Whether the mouse is on a tab's close key; only the last tab has none, there being nothing left to show. */
    private boolean onClose(final Tab t) {
        return t.slot() >= 0 && canClose() && mouseX() >= t.x() + t.w() - CLOSE - 3;
    }

    private static boolean canClose() {
        return Plan.getInstance()
            .openSlots()
            .size() > 1;
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final List<Tab> tabs = tabs();
        follow(tabs);
        final List<Graph> slots = session.slots();
        final int h = getArea().height, strip = stripW(), max = maxScroll(tabs);
        final Tab hover = isHovering() && !draggingBar ? tabAtMouse() : null;
        Stencil.apply(0, 0, strip, h, context);
        for (final Tab t : tabs) {
            final int x = t.x() - scroll, w = t.w();
            if (x + w <= 0 || x >= strip) continue;
            drawTab(t.slot(), x, w, h, slots, hover != null && hover.slot() == t.slot() ? hover : null);
        }
        Stencil.remove();
        // More beyond an edge: the tabs fade out into the bar there.
        if (scroll > 0) fade(0, h, true);
        if (scroll < max) fade(strip - FADE, h, false);
        if (max > 0 && (isHovering() || draggingBar)) {
            final int thumb = Math.max(16, strip * strip / Math.max(1, contentW(tabs)));
            final int tx = (strip - thumb) * scroll / max;
            final boolean hot = draggingBar || onBar();
            Hyb.rect(0, h - BAR_H, strip, BAR_H, 0x60000000);
            Hyb.rect(tx, h - BAR_H, thumb, BAR_H, hot ? Hyb.INK : 0xFF6A6C74);
        }
        final Tab plus = plus(tabs);
        drawTab(-1, plus.x(), plus.w(), h, slots, hover != null && hover.slot() < 0 ? hover : null);
    }

    /** A soft edge {@value #FADE} wide in the bar's colour: opaque at the strip's edge, clear inward. */
    private static void fade(final int x, final int h, final boolean leftEdge) {
        for (int i = 0; i < FADE; i++) {
            final float a = (FADE - i) / (float) FADE;
            final int col = leftEdge ? x + i : x + FADE - 1 - i;
            Hyb.rect(col, 0, 1, h, (int) (a * a * 255) << 24 | BAR_BG);
        }
    }

    private void drawTab(final int slot, final int x, final int w, final int h, final List<Graph> slots,
        final Tab hover) {
        final boolean plus = slot < 0, hot = hover != null;
        final boolean active = !plus && slot == session.activeSlot();
        if (active) {
            Hyb.rect(x, 0, w, h, Hyb.RING);
            Hyb.rect(x + 1, 1, w - 2, h - 1, Hyb.FRAME);
            Hyb.rect(x + 1, 1, w - 2, 1, Hyb.HIGHLIGHT);
            if (session.solvingVisibly()) solving(x, w, h);
        } else {
            Hyb.rect(x, 1, w, h - 1, hot ? 0xFF2D2F35 : 0xFF1C1E22);
        }
        if (plus) {
            Hyb.textCentered("+", x + w / 2f + 0.5f, (h - 8) / 2f, hot ? 0xFFFFFFFF : Hyb.MUTED);
            return;
        }
        // Hovered, the name makes room for the close key at its end.
        final boolean closable = hot && canClose();
        final String name = slots.get(slot)
            .getName();
        Hyb.text(Hyb.fit(name, w - 10 - (closable ? CLOSE : 0)), x + 5, (h - 8) / 2f, active ? 0xFFFFFFFF : Hyb.MUTED);
        if (closable) {
            final int cx = x + w - CLOSE - 2, cy = (h - CLOSE) / 2;
            final boolean onX = onClose(hover);
            if (onX) Hyb.rect(cx, cy, CLOSE, CLOSE, 0xFF4A4C54);
            final int c = onX ? 0xFFFFFFFF : Hyb.MUTED;
            for (int k = 0; k < 5; k++) {
                Hyb.rect(cx + 2 + k, cy + 2 + k, 1, 1, c);
                Hyb.rect(cx + 6 - k, cy + 2 + k, 1, 1, c);
            }
        }
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        final List<Tab> tabs = tabs();
        if (maxScroll(tabs) <= 0) return false;
        scroll = Math.max(0, Math.min(maxScroll(tabs), scroll + (direction == UpOrDown.UP ? -1 : 1) * 40));
        return true;
    }

    /** Scrolls so the scroll bar's thumb starts at {@code x}. */
    private void scrollBarTo(final int x) {
        final List<Tab> tabs = tabs();
        final int strip = stripW(), max = maxScroll(tabs);
        final int thumb = Math.max(16, strip * strip / Math.max(1, contentW(tabs)));
        if (strip - thumb <= 0) return;
        scroll = Math.max(0, Math.min(max, Math.round((float) Math.max(0, x) * max / (strip - thumb))));
    }

    @Override
    public void onMouseDrag(final int mouseButton, final long timeSinceClick) {
        if (draggingBar) scrollBarTo(mouseX() - barGrab);
    }

    @Override
    public boolean onMouseRelease(final int mouseButton) {
        if (!draggingBar) return false;
        draggingBar = false;
        return true;
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (mouseButton == 0 && onBar()) {
            // On the thumb it is taken where it was grabbed; on the track the thumb jumps to centre on the mouse.
            final List<Tab> tabs = tabs();
            final int strip = stripW(), max = maxScroll(tabs);
            final int thumb = Math.max(16, strip * strip / Math.max(1, contentW(tabs)));
            final int tx = max <= 0 ? 0 : (strip - thumb) * scroll / max, mx = mouseX();
            barGrab = mx >= tx && mx < tx + thumb ? mx - tx : thumb / 2;
            draggingBar = true;
            scrollBarTo(mx - barGrab);
            return Result.SUCCESS;
        }
        final Tab t = tabAtMouse();
        if (t == null) return Result.IGNORE;
        if (mouseButton > 2) return Result.IGNORE;
        Hyb.click();
        final int sx = getArea().x + Math.max(0, t.x()), sy = getArea().y + getArea().height + 2;
        if (t.slot() < 0) {
            final List<PickList.Entry> rows = new ArrayList<>();
            rows.add(new PickList.Entry(null, "New plan", "", Hyb.INK, false, session::addSlot));
            rows.add(new PickList.Entry(null, "Open a plan...", "My plans", Hyb.INK, false, session::openMyPlans));
            rows.add(
                new PickList.Entry(
                    null,
                    "Paste plan",
                    "Factory Flow link or code",
                    Hyb.INK,
                    false,
                    session::pastePlan));
            rows.add(new PickList.Entry(null, "Browse library", "public plans", Hyb.INK, false, session::openLibrary));
            Popup.open(getPanel(), PickList.popup("gtnhplanner_new_plan", null, rows, false, 190), sx, sy);
            return Result.SUCCESS;
        }
        final int slot = t.slot();
        if (mouseButton == 2 || mouseButton == 0 && onClose(t)) {
            session.closeSlot(slot);
            return Result.SUCCESS;
        }
        if (mouseButton == 0) {
            session.switchSlot(slot);
            return Result.SUCCESS;
        }
        final Graph g = session.slots()
            .get(slot);
        final List<PickList.Entry> rows = new ArrayList<>();
        rows.add(
            PickList.Entry.of(
                "Rename",
                () -> Popup.open(
                    getPanel(),
                    TextPopup.create("Plan name", g.getName(), name -> session.renameSlot(slot, name)),
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
                () -> com.gtnhplanner.ui.library.AccountForms.post(getPanel(), session, g)));
        if (canClose()) {
            rows.add(
                new PickList.Entry(
                    null,
                    "Close tab",
                    "kept in My plans",
                    Hyb.INK,
                    false,
                    () -> session.closeSlot(slot)));
            rows.add(
                new PickList.Entry(
                    null,
                    "Close other tabs",
                    "kept in My plans",
                    Hyb.INK,
                    false,
                    () -> session.closeOtherSlots(slot)));
        }
        rows.add(
            new PickList.Entry(
                null,
                "Delete plan",
                "",
                Hyb.RED_INK,
                false,
                () -> session.confirmDelete(slot, getPanel(), sx, sy)));
        Popup.open(getPanel(), PickList.popup("gtnhplanner_tab", null, rows, false, 170), sx, sy);
        return Result.SUCCESS;
    }

    /** Tooltip lines for the tab under the mouse. */
    List<String> hoverLines() {
        if (!isHovering() || draggingBar || onBar()) return null;
        final Tab t = tabAtMouse();
        if (t == null) return null;
        if (t.slot() < 0) return List.of("New plan, or open an existing one");
        if (onClose(t)) return List.of("Close tab (the plan is kept in My plans)");
        return List.of(
            session.slots()
                .get(t.slot())
                .getName(),
            "§7Middle-click to close, right-click for more");
    }
}
