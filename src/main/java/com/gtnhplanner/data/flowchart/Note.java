package com.gtnhplanner.data.flowchart;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

import lombok.Getter;
import lombok.Setter;

/**
 * A sticky note on the board: text on a coloured square, moved and resized like a card, nothing to do with the solve.
 * It is Factory Flow's text annotation ({@code kind: "text"}), so a note survives a trip to the website and back:
 * {@link #color} is its colour tag and {@link #fontSize} its size in the website's pixels (14 reads as the board's
 * font at its own size).
 */
@Getter
@Setter
public class Note extends GraphData {

    /** The smallest a note gets, as on the website: five cells by two. */
    public static final int MIN_W = 100, MIN_H = 40;
    /** A new note's size. */
    public static final int DEFAULT_W = 180, DEFAULT_H = 120;
    /** The website's text sizes: 14 unless changed, in steps of 2 from 8 to 96. */
    public static final int DEFAULT_FONT = 14, MIN_FONT = 8, MAX_FONT = 96, FONT_STEP = 2;
    /** A new note's colour. */
    public static final String DEFAULT_COLOR = "yellow";

    /** The text, one entry per line. */
    @NotNull
    private List<String> text = new ArrayList<>();
    private int width = DEFAULT_W;
    private int height = DEFAULT_H;
    /** Factory Flow's colour tag: white, orange, magenta, light_blue, yellow, lime, pink, gray, ... */
    private String color = DEFAULT_COLOR;
    private int fontSize = DEFAULT_FONT;

    public Note() {
        super(UUID.randomUUID());
    }

    @Override
    public String getType() {
        return "note";
    }

    /** The text as one string, lines joined by newlines. */
    public String joined() {
        return String.join("\n", text);
    }

    /** Sets the text from one string; newlines split it into lines. */
    public void setJoined(final String joined) {
        text = new ArrayList<>(Arrays.asList((joined == null ? "" : joined).split("\n", -1)));
        if (text.size() == 1 && text.get(0)
            .isEmpty()) text.clear();
    }

    /** The colour tag, never null (notes saved before colours read as yellow). */
    public String colorTag() {
        return color == null || color.isEmpty() ? DEFAULT_COLOR : color;
    }

    /** The text size, clamped to the website's range (notes saved before sizes read as 14). */
    public int fontSizeOrDefault() {
        return fontSize <= 0 ? DEFAULT_FONT : Math.max(MIN_FONT, Math.min(MAX_FONT, fontSize));
    }

    /** A copy with a new id, for copy and paste. */
    public Note copy() {
        final Note n = new Note();
        n.x = x;
        n.y = y;
        n.header = header;
        n.text = new ArrayList<>(text);
        n.width = width;
        n.height = height;
        n.color = color;
        n.fontSize = fontSize;
        return n;
    }
}
