package com.gtnhplanner.ui.tutorial;

/**
 * One thing a beat of the tour does: move the pointer, press, type, wait for the board to settle. Made afresh each
 * time its beat plays, and run a frame at a time until it says it is done; in a hurry (catching up, or Next pressed)
 * it does the same thing without the animation.
 */
@FunctionalInterface
interface Step {

    /** One frame of the step; true once it is done. */
    boolean frame(Director d);
}
