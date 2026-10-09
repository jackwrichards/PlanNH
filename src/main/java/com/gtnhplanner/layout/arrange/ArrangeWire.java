package com.gtnhplanner.layout.arrange;

/**
 * A wire between two cards: the source makes the resource, the target takes it.
 *
 * @param id          the wire (null: it still places cards, it just cannot be named)
 * @param sourcePortY the port's middle below its card's top edge, when known (null: the card's middle)
 * @param targetPortY the same at the target
 * @param weight      how much it matters, on any consistent scale (null: 1); heavier wires pull harder
 * @param width       the stroke it routes at, in px (null: unknown); sets its weight in the points
 */
public record ArrangeWire(String id, String source, String target, Double sourcePortY, Double targetPortY,
    Double weight, Double width) {}
