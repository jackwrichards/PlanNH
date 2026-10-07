package com.gtnhplanner.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.ui.canvas.BoardCanvas;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * Factory Flow's selection action bar, top middle over the board: with two or more cards selected that one machine
 * can run, "Combine N into one machine". Shown only when it applies; never disabled, never explained when absent.
 */
final class SelectionBar extends Widget<SelectionBar> implements Interactable {

    private static final int H = 18, PAD = 8, ICON = 10;

    private final BoardSession session;
    private final BoardCanvas canvas;
    private final NoticeBar notices;
    private int shownX = Integer.MIN_VALUE, shownY = Integer.MIN_VALUE, shownW = -1;

    SelectionBar(final BoardSession session, final BoardCanvas canvas, final NoticeBar notices) {
        this.session = session;
        this.canvas = canvas;
        this.notices = notices;
        size(0, H);
    }

    /** The selected cards, when there are two or more that can share a machine; else empty. */
    private List<UUID> combinable() {
        final List<UUID> selected = new ArrayList<>(session.selection());
        return session.canCombine(selected) ? session.cardsIn(selected) : List.of();
    }

    private String label(final int cards) {
        return "Combine " + cards + " into one machine";
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        final List<UUID> cards = combinable();
        final int w = cards.isEmpty() ? 0 : Hyb.width(label(cards.size())) + 2 * PAD + ICON + 4;
        // Centred over the board, under the notices so it never covers one.
        final int x = canvas.getArea().x + (canvas.getArea().width - w) / 2;
        final int y = notices.getArea().y + notices.getArea().height + 4;
        if (x != shownX || y != shownY || w != shownW) {
            shownX = x;
            shownY = y;
            shownW = w;
            left(x);
            top(y);
            width(Math.max(1, w));
            scheduleResize();
        }
    }

    @Override
    public boolean canHover() {
        return shownW > 0;
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final List<UUID> cards = combinable();
        if (cards.isEmpty()) return;
        final int w = getArea().width;
        // Factory Flow's: a deep teal face, a 2 px cyan rim, lit top-left and shaded bottom-right inside it.
        Hyb.rect(2, 3, w, H, 0x59000000);
        Hyb.rect(0, 0, w, H, Hyb.SELECTION);
        Hyb.rect(1, 1, w - 2, H - 2, isHovering() ? 0xFF0E6878 : 0xFF0B5563);
        Hyb.rect(1, 1, w - 2, 1, 0x9922D3EE);
        Hyb.rect(1, 1, 1, H - 2, 0x9922D3EE);
        Hyb.rect(1, H - 2, w - 2, 1, 0xFF063640);
        Hyb.rect(w - 2, 1, 1, H - 2, 0xFF063640);
        // Two boxes coming together.
        final int ix = PAD, iy = (H - ICON) / 2;
        Hyb.ring(ix + 1, iy + 1, 5, 5, 1, 0xFFFFFFFF);
        Hyb.ring(ix + 5, iy + 5, 4, 4, 1, 0xFFFFFFFF);
        Hyb.text(label(cards.size()), PAD + ICON + 4, (H - 8) / 2f + 0.5f, 0xFFFFFFFF);
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (mouseButton != 0 || shownW <= 0) return Result.IGNORE;
        Hyb.click();
        session.combine(new ArrayList<>(session.selection()));
        return Result.SUCCESS;
    }
}
