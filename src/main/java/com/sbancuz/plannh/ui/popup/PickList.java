package com.sbancuz.plannh.ui.popup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.sbancuz.plannh.ui.theme.Hyb;

/**
 * Rows of icon, label and detail; click one to pick it and close the popup. The current choice is shaded, the
 * hovered row lit; the wheel scrolls when there are more rows than fit. Used for the actions menu, the machine
 * switch and dropdowns such as coils.
 */
public final class PickList extends Widget<PickList> implements Interactable {

    public record Entry(ItemStack icon, String label, String detail, int labelColor, boolean current, Runnable action) {

        public static Entry of(final String label, final Runnable action) {
            return new Entry(null, label, "", Hyb.INK, false, action);
        }
    }

    public static final int ROW = 20;
    private static final int MAX_ROWS = 10;

    private final List<Entry> entries;
    private final int rows;
    private String filter = "";
    private List<Entry> shown;
    private int scroll;

    private PickList(final List<Entry> entries, final int width) {
        this.entries = entries;
        this.shown = entries;
        this.rows = Math.max(1, Math.min(MAX_ROWS, entries.size()));
        size(width, rows * ROW);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).current && i >= rows) scroll = Math.min(i, entries.size() - rows);
        }
    }

    /**
     * A popup with an optional title and filter box above the list.
     *
     * @param width popup width in GUI pixels; rows show icon, label and a right-aligned detail
     */
    public static Popup popup(final String name, final String title, final List<Entry> entries,
        final boolean filterable, final int width) {
        final PickList list = new PickList(entries, width - 8);
        int y = 4;
        final List<com.cleanroommc.modularui.api.widget.IWidget> top = new ArrayList<>();
        if (title != null) {
            top.add(
                new TextWidget<>(IKey.str(title)).color(Hyb.MUTED)
                    .shadow(true)
                    .pos(6, y + 2)
                    .size(width - 12, 10));
            y += 14;
        }
        if (filterable) {
            top.add(
                new TextFieldWidget().value(new StringValue.Dynamic(() -> list.filter, list::setFilter))
                    .hintText("Filter...")
                    .pos(4, y)
                    .size(width - 8, 14));
            y += 18;
        }
        final Popup popup = new Popup(name, width, y + list.rows * ROW + 4);
        for (final com.cleanroommc.modularui.api.widget.IWidget w : top) popup.child(w);
        popup.child(list.pos(4, y));
        return popup;
    }

    private void setFilter(final String text) {
        filter = text == null ? "" : text;
        final String f = filter.toLowerCase(Locale.ROOT);
        if (f.isEmpty()) shown = entries;
        else {
            shown = new ArrayList<>();
            for (final Entry e : entries) {
                if (e.label.toLowerCase(Locale.ROOT)
                    .contains(f)) shown.add(e);
            }
        }
        scroll = 0;
    }

    private int hoveredRow() {
        if (!isHovering()) return -1;
        final int my = getContext().getAbsMouseY() - getArea().y;
        final int row = my / ROW;
        return row >= 0 && row < rows ? row + scroll : -1;
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final int w = getArea().width;
        final int hovered = hoveredRow();
        final float z = context.getCurrentDrawingZ();
        for (int r = 0; r < rows; r++) {
            final int i = r + scroll;
            if (i >= shown.size()) break;
            final Entry e = shown.get(i);
            final int y = r * ROW;
            if (i == hovered) Hyb.rect(0, y, w, ROW, Hyb.MENU_HOVER);
            else if (e.current) Hyb.rect(0, y, w, ROW, Hyb.TILE);
            int x = 4;
            if (e.icon != null) {
                Hyb.item(e.icon, x, y + 2, 16, z);
                x += 20;
            }
            final int detailW = e.detail.isEmpty() ? 0 : Hyb.width(e.detail) + 8;
            Hyb.text(Hyb.fit(e.label, w - x - detailW - 4), x, y + 6, e.labelColor);
            if (!e.detail.isEmpty()) Hyb.textRight(e.detail, w - 4, y + 6, Hyb.MUTED);
        }
        if (shown.size() > rows) {
            final int barH = Math.max(8, rows * ROW * rows / shown.size());
            final int barY = (rows * ROW - barH) * scroll / Math.max(1, shown.size() - rows);
            Hyb.rect(w - 2, barY, 2, barH, Hyb.MUTED);
        }
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        final int i = hoveredRow();
        if (mouseButton != 0 || i < 0 || i >= shown.size()) return Result.IGNORE;
        final Entry e = shown.get(i);
        final ModularPanel panel = getPanel();
        panel.closeIfOpen();
        e.action.run();
        return Result.SUCCESS;
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        final int max = Math.max(0, shown.size() - rows);
        scroll = Math.max(0, Math.min(max, scroll + (direction == UpOrDown.UP ? -1 : 1)));
        return true;
    }
}
