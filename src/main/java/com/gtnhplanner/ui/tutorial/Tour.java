package com.gtnhplanner.ui.tutorial;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.gtnhplanner.ui.tutorial.Targets.Target;

/**
 * The tour's shape: a list of beats, one per press of Next. A beat acts something out, then notes what it showed in a
 * callout beside it and waits.
 */
final class Tour {

    private Tour() {}

    /** A screen the tour can open afresh, to start a beat there. */
    enum Scene {
        /** In the world, no inventory: the tour's own see-through screen. */
        WORLD,
        /** The player's inventory, NEI beside it. */
        INVENTORY,
        /** The planner, on the tour's plan. */
        BOARD
    }

    /** What happens before the player presses Next. Each method adds a step and returns the beat, to chain. */
    static final class Beat {

        final List<Supplier<Step>> steps = new ArrayList<>();

        Beat add(final Supplier<Step> step) {
            steps.add(step);
            return this;
        }

        /** The callout: {@code text} beside {@code t} (null: the middle of the screen), framed in gold. */
        Beat note(final Target t, final String text) {
            return add(Steps.note(t, text));
        }

        Beat spot(final Target t) {
            return add(Steps.spot(t));
        }

        Beat ring(final Target t) {
            return add(Steps.ring(t));
        }

        Beat move(final Target t) {
            return add(Steps.move(t));
        }

        Beat hover(final Target t, final long ms) {
            return add(Steps.hover(t, ms));
        }

        Beat click(final Target t) {
            return add(Steps.click(t));
        }

        Beat shiftClick(final Target t) {
            return add(Steps.shiftClick(t));
        }

        /** Clicks and waits for the popup the click opens. */
        Beat opens(final Target t) {
            return add(Steps.clickOpens(t, 0));
        }

        /** Right-clicks and waits for the menu it opens. */
        Beat opensMenu(final Target t) {
            return add(Steps.clickOpens(t, 1));
        }

        /** Enter in a number box, waiting for it to close. */
        Beat commit() {
            return add(Steps.enterCloses());
        }

        Beat drag(final Target from, final Target to) {
            return add(Steps.drag(from, to));
        }

        Beat pan(final Target from, final float dx, final float dy) {
            return add(Steps.pan(from, dx, dy));
        }

        Beat wheel(final Target t, final int notches) {
            return add(Steps.wheel(t, notches));
        }

        Beat key(final String label, final Runnable action) {
            return add(Steps.key(label, action));
        }

        Beat type(final Supplier<Steps.Sink> sink, final String text) {
            return add(Steps.type(sink, text));
        }

        Beat keys(final String text) {
            return add(Steps.keys(text));
        }

        Beat pause(final long ms) {
            return add(Steps.pause(ms));
        }

        Beat until(final BooleanSupplier ready, final long timeout) {
            return add(Steps.until(ready, timeout));
        }

        Beat run(final Runnable action) {
            return add(Steps.run(action));
        }

        Beat holdKey(final String label, final long ms, final Runnable each) {
            return add(Steps.holdKey(label, ms, each));
        }

        Beat clickUntil(final Target t, final BooleanSupplier done, final int max) {
            return add(Steps.clickUntil(t, done, max));
        }

        /** Esc: shuts the open menu or popup. */
        Beat esc() {
            return add(Steps.press("Esc", (char) 27, org.lwjgl.input.Keyboard.KEY_ESCAPE));
        }

        /** Esc only when {@code cond} holds: one meant for a popup must not close the screen when none opened. */
        Beat escIf(final BooleanSupplier cond) {
            return add(Steps.when(cond, Steps.press("Esc", (char) 27, org.lwjgl.input.Keyboard.KEY_ESCAPE)));
        }

        Beat rest() {
            return add(Steps.rest());
        }

        Beat when(final BooleanSupplier cond, final Supplier<Step> then) {
            return add(Steps.when(cond, then));
        }
    }
}
