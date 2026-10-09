package com.gtnhplanner.ui.note;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * A note's text laid out in lines: each paragraph (between newlines) wrapped at word breaks to a width, a word wider
 * than the line broken where it must. Lines keep their place in the whole text, so the editor can map a caret to a
 * line and a point on it, and a click back to a caret. Pure: the font's width comes in as a function.
 */
public final class NoteText {

    /** A line: the text from {@code start} up to {@code end} (no newline; a soft break's space ends the line). */
    public record Line(int start, int end) {}

    private final String text;
    private final List<Line> lines;
    private final ToIntFunction<String> width;

    private NoteText(final String text, final List<Line> lines, final ToIntFunction<String> width) {
        this.text = text;
        this.lines = lines;
        this.width = width;
    }

    /** Lays {@code text} out in lines at most {@code maxWidth} wide (in the font's units). */
    public static NoteText of(final String text, final float maxWidth, final ToIntFunction<String> width) {
        final List<Line> lines = new ArrayList<>();
        int p = 0;
        while (true) {
            int nl = text.indexOf('\n', p);
            if (nl < 0) nl = text.length();
            wrap(text, p, nl, maxWidth, width, lines);
            if (nl >= text.length()) break;
            p = nl + 1;
        }
        return new NoteText(text, lines, width);
    }

    /** One paragraph, {@code from} to {@code to}, into lines. */
    private static void wrap(final String text, final int from, final int to, final float maxWidth,
        final ToIntFunction<String> width, final List<Line> out) {
        if (from == to) {
            out.add(new Line(from, to));
            return;
        }
        int start = from;
        while (start < to) {
            int end = start;
            int lastSpace = -1;
            while (end < to && width.applyAsInt(text.substring(start, end + 1)) <= maxWidth) {
                if (text.charAt(end) == ' ') lastSpace = end;
                end++;
            }
            if (end >= to) {
                out.add(new Line(start, to));
                return;
            }
            // Break after the last space that fits, keeping the space on this line; else mid-word, one letter at least.
            final int cut = lastSpace >= start ? lastSpace + 1 : Math.max(end, start + 1);
            out.add(new Line(start, cut));
            start = cut;
        }
    }

    public List<Line> lines() {
        return lines;
    }

    public String text() {
        return text;
    }

    /** A line's text as drawn: without the space a soft break leaves at its end. */
    public String shown(final int line) {
        final Line l = lines.get(line);
        String s = text.substring(l.start, l.end);
        if (s.endsWith(" ") && line + 1 < lines.size() && lines.get(line + 1).start == l.end)
            s = s.substring(0, s.length() - 1);
        return s;
    }

    /** The line a caret sits on: at a soft break it starts the next line, at a paragraph's end it ends its own. */
    public int lineOf(final int caret) {
        for (int i = 0; i < lines.size(); i++) {
            final Line l = lines.get(i);
            final boolean soft = i + 1 < lines.size() && lines.get(i + 1).start == l.end;
            if (caret >= l.start && (caret < l.end || caret == l.end && !soft)) return i;
        }
        return lines.size() - 1;
    }

    /** How far along its line a caret is, in the font's units. */
    public int xOf(final int caret) {
        final Line l = lines.get(lineOf(caret));
        return width.applyAsInt(text.substring(l.start, Math.max(l.start, Math.min(caret, l.end))));
    }

    /** The caret nearest a point {@code x} along line {@code line}. */
    public int caretAt(final int line, final float x) {
        final int i = Math.max(0, Math.min(lines.size() - 1, line));
        final Line l = lines.get(i);
        final boolean soft = i + 1 < lines.size() && lines.get(i + 1).start == l.end;
        // A soft line's last place is its trailing space, which belongs to the next line's start.
        final int last = soft ? Math.max(l.start, l.end - 1) : l.end;
        int best = l.start;
        float bestGap = Float.MAX_VALUE;
        for (int c = l.start; c <= last; c++) {
            final float gap = Math.abs(width.applyAsInt(text.substring(l.start, c)) - x);
            if (gap < bestGap) {
                bestGap = gap;
                best = c;
            }
        }
        return best;
    }
}
