package com.gtnhplanner.ui.theme;

/**
 * A list's scroll bar along its right edge: a thumb to drag, a track to click (the thumb jumps there), drawn wider and
 * brighter while the mouse is on it. The list owns the scroll; this works it out from the thumb.
 */
public final class ScrollBar {

    public static final int W = 5;

    private int x, top, height, thumb, track, max;
    private boolean dragging;
    private int grab;

    /**
     * Draws the bar for a list {@code visible} tall showing {@code content} of it from {@code scroll}, against the
     * right edge {@code right}; returns the scroll clamped to the content. Nothing is drawn when everything fits.
     */
    public int draw(final int right, final int top, final int visible, final int content, final int scroll,
        final boolean hot) {
        this.x = right - W - 1;
        this.top = top;
        this.height = visible;
        max = Math.max(0, content - visible);
        final int clamped = Math.max(0, Math.min(max, scroll));
        if (max <= 0) return clamped;
        thumb = Math.max(14, visible * visible / Math.max(1, content));
        track = visible - thumb;
        final int y = top + track * clamped / max;
        final boolean lit = dragging || hot;
        if (lit) Hyb.rect(x - 1, top, W + 1, visible, 0x40000000);
        Hyb.rect(x + 1, y, W - 2, thumb, lit ? Hyb.INK : 0xFF6A6C74);
        return clamped;
    }

    /** Whether there is a bar (the content overflows), and the point is on it. */
    public boolean contains(final int px, final int py) {
        return max > 0 && px >= x - 2 && px < x + W + 2 && py >= top && py < top + height;
    }

    /**
     * A press on the bar at {@code y}: takes the thumb where it was grabbed, or centres it on the mouse; the scroll.
     */
    public int press(final int y, final int scroll) {
        final int thumbTop = top + (max <= 0 ? 0 : track * scroll / max);
        grab = y >= thumbTop && y < thumbTop + thumb ? y - thumbTop : thumb / 2;
        dragging = true;
        return drag(y);
    }

    /** The scroll for the mouse at {@code y} while dragging. */
    public int drag(final int y) {
        if (track <= 0) return 0;
        final int at = Math.max(0, Math.min(track, y - grab - top));
        return Math.round((float) at * max / track);
    }

    public boolean dragging() {
        return dragging;
    }

    /** Lets go; true when it was being dragged. */
    public boolean release() {
        final boolean was = dragging;
        dragging = false;
        return was;
    }
}
