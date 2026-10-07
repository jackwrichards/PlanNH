package com.gtnhplanner.ui;

import java.util.List;
import java.util.UUID;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.ui.canvas.BoardCanvas;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * Notices along the top of the board, one row each: a stripe in the severity's colour, the sentence, and "Show me",
 * which frames the cards and drawers the notice is about. At most {@value #MAX_ROWS} rows; the rest are counted.
 */
final class NoticeBar extends Widget<NoticeBar> implements Interactable {

    static final int ROW = 14;
    static final int MAX_ROWS = 4;
    private static final String SHOW_ME = "Show me";

    /** Which card "Show me" went to last, per notice, for notices it walks through. */
    private final java.util.Map<String, Integer> turns = new java.util.HashMap<>();

    private final BoardSession session;
    private final BoardCanvas canvas;
    private int shownRows = -1;

    NoticeBar(final BoardSession session, final BoardCanvas canvas) {
        this.session = session;
        this.canvas = canvas;
        height(0);
    }

    private int localX() {
        return getContext().getAbsMouseX() - getArea().x;
    }

    private int localY() {
        return getContext().getAbsMouseY() - getArea().y;
    }

    private int rows() {
        final int n = session.notices()
            .size();
        return n <= MAX_ROWS ? n : MAX_ROWS + 1;
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        final int rows = rows();
        if (rows != shownRows) {
            shownRows = rows;
            height(rows * ROW);
            scheduleResize();
        }
    }

    @Override
    public boolean canHover() {
        return shownRows > 0;
    }

    private static int stripe(final com.gtnhplanner.data.flowchart.balancer.Severity severity) {
        return switch (severity) {
            case ERROR -> Hyb.RED_INK;
            case WARN -> Hyb.AMBER_INK;
            case INFO -> 0xFF6A8CB5;
        };
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final List<Notice> notices = session.notices();
        final int w = getArea().width;
        final int showW = Hyb.width(SHOW_ME);
        final int hoverRow = isHovering() ? localY() / ROW : -1;
        for (int i = 0; i < Math.min(notices.size(), MAX_ROWS); i++) {
            final Notice n = notices.get(i);
            final int y = i * ROW;
            final int rw = rowWidth(n, w);
            Hyb.rect(0, y, rw, ROW - 1, 0xE0181A1F);
            Hyb.rect(0, y, 3, ROW - 1, stripe(n.severity()));
            final boolean canShow = !n.focus()
                .isEmpty();
            final int textRoom = rw - 10 - (canShow ? showW + 10 : 0);
            Hyb.text(Hyb.fit(n.text(), textRoom), 7, y + 3, Hyb.INK);
            if (canShow) {
                final boolean hot = hoverRow == i && localX() >= rw - showW - 8 && localX() < rw;
                final int color = hot ? 0xFFFFFFFF : Hyb.SELECTION;
                Hyb.text(SHOW_ME, rw - showW - 5, y + 3, color);
                Hyb.rect(rw - showW - 5, y + 12, showW, 1, color);
            }
        }
        if (notices.size() > MAX_ROWS) {
            final int y = MAX_ROWS * ROW;
            Hyb.rect(0, y, w, ROW - 1, 0xE0181A1F);
            Hyb.text("+" + (notices.size() - MAX_ROWS) + " more", 7, y + 3, Hyb.MUTED);
        }
    }

    /** A row is as wide as its sentence and "Show me", so the board stays visible beside it. */
    private static int rowWidth(final Notice n, final int max) {
        final int show = n.focus()
            .isEmpty() ? 0 : Hyb.width(SHOW_ME) + 10;
        return Math.min(max, 7 + Hyb.width(n.text()) + 8 + show);
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (mouseButton != 0) return Result.IGNORE;
        final int row = localY() / ROW;
        final List<Notice> notices = session.notices();
        if (row < 0 || row >= Math.min(notices.size(), MAX_ROWS)) return Result.IGNORE;
        final Notice n = notices.get(row);
        final int rw = rowWidth(n, getArea().width);
        if (localX() >= rw) return Result.IGNORE;
        if (n.focus()
            .isEmpty() || localX() < rw - Hyb.width(SHOW_ME) - 8) return Result.SUCCESS;
        // All at once while that still shows their ports; else one at a time, in turn, close enough to read.
        Hyb.click();
        final List<UUID> focus = n.focus();
        if (focus.size() == 1 || canvas.framesReadably(focus)) canvas.frame(focus);
        else canvas.frame(List.of(focus.get((turns.merge(n.text(), 1, Integer::sum) - 1) % focus.size())));
        return Result.SUCCESS;
    }
}
