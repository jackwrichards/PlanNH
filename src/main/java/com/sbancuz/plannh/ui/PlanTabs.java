package com.sbancuz.plannh.ui;

import java.util.ArrayList;
import java.util.List;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.ui.popup.PickList;
import com.sbancuz.plannh.ui.popup.Popup;
import com.sbancuz.plannh.ui.popup.TextPopup;
import com.sbancuz.plannh.ui.theme.Hyb;

/**
 * The plan tabs on the top bar: one per plan slot, then "+". Click switches; right-click renames or deletes;
 * the active tab is raised like a card.
 */
final class PlanTabs extends Widget<PlanTabs> implements Interactable {

    private static final int TAB_MAX = 110;
    private static final int PLUS = 16;

    private final BoardSession session;

    PlanTabs(final BoardSession session) {
        this.session = session;
    }

    private static int tabWidth(final Graph g) {
        return Math.min(TAB_MAX, Hyb.width(g.getName()) + 14);
    }

    /** {x, width} of each tab, then the "+" key, as far as they fit. */
    private List<int[]> layout() {
        final List<int[]> out = new ArrayList<>();
        int x = 0;
        for (final Graph g : session.slots()) {
            final int w = tabWidth(g);
            if (x + w + PLUS > getArea().width) break;
            out.add(new int[] { x, w });
            x += w + 2;
        }
        out.add(new int[] { x, PLUS });
        return out;
    }

    private int indexAtMouse() {
        final int mx = getContext().getAbsMouseX() - getArea().x;
        final List<int[]> tabs = layout();
        for (int i = 0; i < tabs.size(); i++) {
            if (mx >= tabs.get(i)[0] && mx < tabs.get(i)[0] + tabs.get(i)[1]) return i;
        }
        return -1;
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final List<int[]> tabs = layout();
        final List<Graph> slots = session.slots();
        final int h = getArea().height;
        final int hover = isHovering() ? indexAtMouse() : -1;
        for (int i = 0; i < tabs.size(); i++) {
            final int x = tabs.get(i)[0], w = tabs.get(i)[1];
            final boolean plus = i == tabs.size() - 1;
            final boolean active = !plus && i == session.activeSlot();
            if (active) {
                Hyb.rect(x, 0, w, h, Hyb.RING);
                Hyb.rect(x + 1, 1, w - 2, h - 1, Hyb.FRAME);
                Hyb.rect(x + 1, 1, w - 2, 1, Hyb.HIGHLIGHT);
            } else {
                Hyb.rect(x, 1, w, h - 1, hover == i ? 0xFF2D2F35 : 0xFF1C1E22);
            }
            if (plus) {
                Hyb.textCentered("+", x + w / 2f + 0.5f, (h - 8) / 2f, hover == i ? 0xFFFFFFFF : Hyb.MUTED);
            } else {
                Hyb.text(
                    Hyb.fit(
                        slots.get(i)
                            .getName(),
                        w - 10),
                    x + 5,
                    (h - 8) / 2f,
                    active ? 0xFFFFFFFF : Hyb.MUTED);
            }
        }
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        final int i = indexAtMouse();
        if (i < 0) return Result.IGNORE;
        final List<int[]> tabs = layout();
        if (i == tabs.size() - 1) {
            if (mouseButton == 0) session.addSlot();
            return Result.SUCCESS;
        }
        if (mouseButton == 0) {
            session.switchSlot(i);
            return Result.SUCCESS;
        }
        if (mouseButton != 1) return Result.IGNORE;
        final Graph g = session.slots()
            .get(i);
        final int sx = getArea().x + tabs.get(i)[0], sy = getArea().y + getArea().height + 2;
        final List<PickList.Entry> rows = new ArrayList<>();
        rows.add(
            PickList.Entry.of(
                "Rename",
                () -> Popup.open(
                    getPanel(),
                    TextPopup.create("Plan name", g.getName(), name -> session.renameSlot(i, name)),
                    sx,
                    sy)));
        if (session.slots()
            .size() > 1)
            rows.add(new PickList.Entry(null, "Delete this plan", "", Hyb.RED_INK, false, () -> session.deleteSlot(i)));
        Popup.open(getPanel(), PickList.popup("plannh_tab", null, rows, false, 120), sx, sy);
        return Result.SUCCESS;
    }

    /** Tooltip lines for the tab under the mouse. */
    List<String> hoverLines() {
        if (!isHovering()) return null;
        final int i = indexAtMouse();
        if (i < 0) return null;
        if (i == layout().size() - 1) return List.of("New plan");
        return List.of(
            session.slots()
                .get(i)
                .getName(),
            "§7Click: open  Right click: rename, delete");
    }
}
