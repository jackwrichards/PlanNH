package com.sbancuz.plannh.ui;

import java.util.ArrayList;
import java.util.List;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.data.flowchart.Plan;
import com.sbancuz.plannh.ui.popup.PickList;
import com.sbancuz.plannh.ui.popup.Popup;
import com.sbancuz.plannh.ui.popup.TextPopup;
import com.sbancuz.plannh.ui.theme.Hyb;

/**
 * The plan tabs on the top bar: one per open plan, then "+". A tab is only a plan that is open; closing it (its x,
 * or a middle click) keeps the plan, in the library's My plans, as on the website. Click switches; right-click
 * renames, copies, closes or deletes; the active tab is raised like a card.
 */
final class PlanTabs extends Widget<PlanTabs> implements Interactable {

    private static final int TAB_MAX = 110, TAB_MIN = 28;
    private static final int PLUS = 16;
    /** The close key on a hovered tab. */
    private static final int CLOSE = 9;

    private final BoardSession session;

    PlanTabs(final BoardSession session) {
        this.session = session;
    }

    private static int tabWidth(final Graph g) {
        // Room for the close key a hovered tab shows, so the name is not cut for it.
        return Math.min(TAB_MAX, Hyb.width(g.getName()) + 14 + CLOSE);
    }

    /** One tab: the plan slot it shows, where it is and how wide. */
    private record Tab(int slot, int x, int w) {}

    /**
     * The tabs, then the "+" key (slot -1). When they do not all fit, the open plan keeps its whole name and the
     * others give way, the widest first (their names cut short), so every open plan keeps a tab; past
     * {@link #TAB_MIN} the rest are left off.
     */
    private List<Tab> layout() {
        final List<Graph> slots = session.slots();
        final List<Integer> open = Plan.getInstance()
            .openSlots();
        final int active = session.activeSlot();
        final int[] natural = new int[open.size()];
        int activeAt = -1;
        for (int i = 0; i < natural.length; i++) {
            natural[i] = tabWidth(slots.get(open.get(i)));
            if (open.get(i) == active) activeAt = i;
        }
        final int kept = activeAt >= 0 ? natural[activeAt] : 0;
        final int room = getArea().width - PLUS - 2 * natural.length - kept;
        int cap = TAB_MAX;
        while (cap > TAB_MIN && cappedWidth(natural, cap, activeAt) > room) cap--;
        final List<Tab> out = new ArrayList<>();
        int x = 0;
        for (int i = 0; i < natural.length; i++) {
            final int w = i == activeAt ? natural[i] : Math.min(natural[i], cap);
            if (x + w + PLUS > getArea().width) break;
            out.add(new Tab(open.get(i), x, w));
            x += w + 2;
        }
        out.add(new Tab(-1, x, PLUS));
        return out;
    }

    /** While the plan solves, a short cyan line runs along the bottom of its tab. */
    private static void solving(final int x, final int w, final int h) {
        final int seg = Math.max(8, w / 3);
        final int at = (int) ((w + seg) * (System.currentTimeMillis() % 900) / 900) - seg;
        final int from = Math.max(1, at), to = Math.min(w - 1, at + seg);
        if (to > from) Hyb.rect(x + from, h - 2, to - from, 1, 0xFF22D3EE);
    }

    private static int cappedWidth(final int[] natural, final int cap, final int except) {
        int sum = 0;
        for (int i = 0; i < natural.length; i++) if (i != except) sum += Math.min(natural[i], cap);
        return sum;
    }

    private int mouseX() {
        return getContext().getAbsMouseX() - getArea().x;
    }

    private Tab tabAtMouse() {
        final int mx = mouseX();
        for (final Tab t : layout()) if (mx >= t.x() && mx < t.x() + t.w()) return t;
        return null;
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
        final List<Tab> tabs = layout();
        final List<Graph> slots = session.slots();
        final int h = getArea().height;
        final Tab hover = isHovering() ? tabAtMouse() : null;
        for (final Tab t : tabs) {
            final int x = t.x(), w = t.w();
            final boolean plus = t.slot() < 0, hot = hover != null && hover.equals(t);
            final boolean active = !plus && t.slot() == session.activeSlot();
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
                continue;
            }
            // Hovered, the name makes room for the close key at its end.
            final boolean closable = hot && canClose();
            final String name = slots.get(t.slot())
                .getName();
            Hyb.text(
                Hyb.fit(name, w - 10 - (closable ? CLOSE : 0)),
                x + 5,
                (h - 8) / 2f,
                active ? 0xFFFFFFFF : Hyb.MUTED);
            if (closable) {
                final int cx = x + w - CLOSE - 2, cy = (h - CLOSE) / 2;
                final boolean onX = onClose(t);
                if (onX) Hyb.rect(cx, cy, CLOSE, CLOSE, 0xFF4A4C54);
                final int c = onX ? 0xFFFFFFFF : Hyb.MUTED;
                for (int k = 0; k < 5; k++) {
                    Hyb.rect(cx + 2 + k, cy + 2 + k, 1, 1, c);
                    Hyb.rect(cx + 6 - k, cy + 2 + k, 1, 1, c);
                }
            }
        }
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        final Tab t = tabAtMouse();
        if (t == null) return Result.IGNORE;
        if (mouseButton > 2) return Result.IGNORE;
        Hyb.click();
        final int sx = getArea().x + t.x(), sy = getArea().y + getArea().height + 2;
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
            rows.add(new PickList.Entry(null, "Browse library", "public setups", Hyb.INK, false, session::openLibrary));
            Popup.open(getPanel(), PickList.popup("plannh_new_plan", null, rows, false, 190), sx, sy);
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
                () -> com.sbancuz.plannh.ui.library.AccountForms.post(getPanel(), session, g)));
        if (canClose()) rows.add(
            new PickList.Entry(null, "Close tab", "kept in My plans", Hyb.INK, false, () -> session.closeSlot(slot)));
        rows.add(
            new PickList.Entry(
                null,
                "Delete plan",
                "",
                Hyb.RED_INK,
                false,
                () -> session.confirmDelete(slot, getPanel(), sx, sy)));
        Popup.open(getPanel(), PickList.popup("plannh_tab", null, rows, false, 150), sx, sy);
        return Result.SUCCESS;
    }

    /** Tooltip lines for the tab under the mouse. */
    List<String> hoverLines() {
        if (!isHovering()) return null;
        final Tab t = tabAtMouse();
        if (t == null) return null;
        if (t.slot() < 0)
            return List.of("New plan, or open one", "§7From My plans, a Factory Flow link or code, or the library");
        if (onClose(t)) return List.of("Close the tab", "§7The plan stays in My plans");
        return List.of(
            session.slots()
                .get(t.slot())
                .getName(),
            "§7Click: open  Middle click: close  Right click: rename, copy, close, delete");
    }
}
