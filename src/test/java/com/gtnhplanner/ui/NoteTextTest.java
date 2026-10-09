package com.gtnhplanner.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.ToIntFunction;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.ui.note.NoteText;

/** A sticky note's text in lines: word wrap, hard breaks, and the caret on them. Every letter is 6 wide here. */
class NoteTextTest {

    private static final ToIntFunction<String> SIX = s -> 6 * s.length();

    @Test
    void wrapsAtSpacesAndKeepsHardBreaks() {
        final NoteText t = NoteText.of("one two three\nfour", 6 * 8, SIX);
        assertEquals(
            3,
            t.lines()
                .size());
        assertEquals("one two", t.shown(0));
        assertEquals("three", t.shown(1));
        assertEquals("four", t.shown(2));
    }

    @Test
    void breaksAWordWiderThanTheLine() {
        final NoteText t = NoteText.of("abcdefghij", 6 * 4, SIX);
        assertEquals("abcd", t.shown(0));
        assertEquals("efgh", t.shown(1));
        assertEquals("ij", t.shown(2));
    }

    @Test
    void emptyLinesAreLines() {
        final NoteText t = NoteText.of("a\n\nb", 100, SIX);
        assertEquals(
            3,
            t.lines()
                .size());
        assertEquals("", t.shown(1));
        assertEquals(1, t.lineOf(2));
        assertEquals(2, t.lineOf(3));
    }

    @Test
    void theCaretAtASoftBreakStartsTheNextLine() {
        final NoteText t = NoteText.of("one two three", 6 * 8, SIX);
        // "one two " | "three": the caret after the space is at the start of "three".
        assertEquals(1, t.lineOf(8));
        assertEquals(0, t.xOf(8));
        assertEquals(0, t.lineOf(7));
        assertEquals(42, t.xOf(7));
        // The end of the text ends the last line.
        assertEquals(1, t.lineOf(13));
    }

    @Test
    void aClickFindsTheNearestCaret() {
        final NoteText t = NoteText.of("one two three", 6 * 8, SIX);
        assertEquals(2, t.caretAt(0, 13));
        // Past the end of a soft line, the caret stops before the space that belongs to the break.
        assertEquals(7, t.caretAt(0, 500));
        assertEquals(13, t.caretAt(1, 500));
        assertEquals(8, t.caretAt(5, -10));
    }
}
