package com.gtnhplanner.ui.tutorial;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.gtnhplanner.ui.tutorial.Targets.Target;

/** The tour's shape: chapters of beats, each beat a caption and the steps that act it out. */
final class Tour {

    private Tour() {}

    /** Where a chapter starts, so it can be started on its own (the chapter list, going back). */
    enum Scene {
        /** In the world, no inventory: the tour's own see-through screen. */
        WORLD,
        /** The player's inventory, NEI beside it. */
        INVENTORY,
        /** The planner, on the tour's plan. */
        BOARD
    }

    static final class Chapter {

        final String title;
        final Scene scene;
        final List<Beat> beats = new ArrayList<>();

        Chapter(final String title, final Scene scene) {
            this.title = title;
            this.scene = scene;
        }

        Beat beat(final String caption) {
            final Beat b = new Beat(caption);
            beats.add(b);
            return b;
        }
    }

    /** One caption and what happens under it. Each method adds a step and returns the beat, to chain. */
    static final class Beat {

        final String caption;
        final List<Supplier<Step>> steps = new ArrayList<>();
        /** How long the caption stays once the steps are done, in ms; -1 for as long as it takes to read. */
        long hold = -1;

        Beat(final String caption) {
            this.caption = caption;
        }

        long holdMs() {
            if (hold >= 0) return hold;
            return Math.max(1700, Math.min(7000, 500 + 48L * caption.length()));
        }

        Beat hold(final long ms) {
            hold = ms;
            return this;
        }

        Beat add(final Supplier<Step> step) {
            steps.add(step);
            return this;
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

        Beat move(final Target t, final float fx, final float fy) {
            return add(Steps.move(t, fx, fy, 1));
        }

        Beat hover(final Target t, final long ms) {
            return add(Steps.hover(t, ms));
        }

        Beat click(final Target t) {
            return add(Steps.click(t));
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

        Beat rightClick(final Target t) {
            return add(Steps.click(t, 1));
        }

        Beat doubleClick(final Target t) {
            return add(Steps.doubleClick(t));
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

        Beat enter() {
            return add(Steps.enter());
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

        Beat cursor(final boolean shown) {
            return add(Steps.cursor(shown));
        }

        Beat say(final String caption) {
            return add(Steps.say(caption));
        }
    }
}
