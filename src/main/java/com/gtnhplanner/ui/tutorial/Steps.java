package com.gtnhplanner.ui.tutorial;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.ui.tutorial.Targets.Target;

/** The tour's steps, as makers: a beat keeps the makers and makes fresh steps each time it plays. */
final class Steps {

    private Steps() {}

    /** Where text goes, a little at a time: NEI's search, a number box, a note. */
    interface Sink {

        void set(String text);
    }

    // region Pointer

    /** Glides the pointer to a point of a target (its middle unless {@code fx, fy} say otherwise). */
    static Supplier<Step> move(final Target t, final float fx, final float fy, final float speed) {
        return move(t, fx, fy, speed, false);
    }

    /**
     * As above; {@code pinned}: once found, the target stays the board spot it was then (a drop point found among the
     * cards would otherwise move as the card being carried does).
     */
    static Supplier<Step> move(final Target t, final float fx, final float fy, final float speed,
        final boolean pinned) {
        return () -> new Step() {

            private long t0 = -1, waiting = -1, dur;
            private float sx, sy, lastX = Float.NaN, lastY = Float.NaN;
            private int settled;
            private long movingSince = -1, restingSince = -1, restDraws;
            private float[] spot;

            private Rect target() {
                final com.gtnhplanner.ui.BoardScreen b = Targets.board();
                if (spot != null && b != null) {
                    final com.gtnhplanner.ui.canvas.BoardCanvas c = b.canvas();
                    return new Rect(c.screenX(spot[0]), c.screenY(spot[1]), 0, 0);
                }
                final Rect r = t.rect();
                if (r != null && pinned && b != null) {
                    final com.gtnhplanner.ui.canvas.BoardCanvas c = b.canvas();
                    spot = new float[] { c.worldX(Math.round(r.x() + r.w() * fx)),
                        c.worldY(Math.round(r.y() + r.h() * fy)) };
                    return new Rect(c.screenX(spot[0]), c.screenY(spot[1]), 0, 0);
                }
                return r;
            }

            @Override
            public boolean frame(final Director d) {
                final Rect r = target();
                if (r == null) {
                    // Hurrying, a target that is not there soon is skipped quickly.
                    if (waiting < 0) waiting = System.currentTimeMillis();
                    return System.currentTimeMillis() - waiting > (d.hurrying() ? 1500 : 6000)
                        && d.gaveUp("a target never showed");
                }
                final float tx = spot != null ? r.x() : r.x() + r.w() * fx,
                    ty = spot != null ? r.y() : r.y() + r.h() * fy;
                if (t0 < 0) {
                    t0 = d.now();
                    sx = d.px();
                    sy = d.py();
                    d.ghost()
                        .show(!d.hurrying());
                    final float dist = (float) Math.hypot(tx - sx, ty - sy);
                    // Hurrying, a move jumps, except with a button held: a drag that jumps is one drag event, and the
                    // board takes no drop from it.
                    dur = d.hurrying() ? (VirtualInput.holding() ? 90 : 0)
                        : (long) (Math.min(620, 170 + dist * 0.75f) / speed);
                }
                final float t = dur <= 0 ? 1 : Math.min(1, (d.now() - t0) / (float) dur);
                final float e = t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
                // A gentle arc, as a hand moves: sideways by a twelfth of the way at the middle.
                final float dx = tx - sx, dy = ty - sy, arc = (float) Math.sin(Math.PI * e) * 0.08f;
                d.pointTo(sx + dx * e - dy * arc, sy + dy * e + dx * arc);
                // At rest on a target that has stopped moving until the screen has drawn twice with the pointer there:
                // what is under the pointer is found as the screen draws, a draw behind, and the screen does not draw
                // on every frame. A popup just opened can still be finding its place, and the board's camera can still
                // be easing to a zoom or gliding out of a fling; hurrying, a press on where things were a moment ago
                // would miss. A target that keeps moving (the board panning under a drag) is taken as it is after a
                // second.
                final boolean moving = !(Math.abs(tx - lastX) < 0.5f && Math.abs(ty - lastY) < 0.5f) || boardMoving();
                lastX = tx;
                lastY = ty;
                if (!moving) movingSince = -1;
                else if (movingSince < 0) movingSince = d.now();
                final boolean still = !moving || d.now() - movingSince > 1000;
                if (t < 1 || !still) {
                    settled = 0;
                    restingSince = -1;
                    return false;
                }
                if (restingSince < 0) {
                    restingSince = d.now();
                    restDraws = d.draws();
                }
                // Hurrying, the pointer still rests a game tick: some of what a click lands on is only built on the
                // tick. With no screen drawing, frames are counted instead.
                return (d.draws() - restDraws >= 2 || ++settled >= 30)
                    && (!d.hurrying() || d.now() - restingSince >= 60);
            }
        };
    }

    /** The board is not at rest: its camera moving on its own, or its widgets being built afresh. */
    private static boolean boardMoving() {
        final com.gtnhplanner.ui.BoardScreen b = Targets.board();
        return b != null && b.canvas()
            .busy();
    }

    static Supplier<Step> move(final Target t) {
        return move(t, 0.5f, 0.5f, 1);
    }

    static Supplier<Step> press(final int button) {
        return () -> d -> {
            VirtualInput.press(button);
            d.ghost()
                .down();
            return true;
        };
    }

    static Supplier<Step> release() {
        return () -> d -> {
            VirtualInput.release();
            d.ghost()
                .up(d.px(), d.py());
            return true;
        };
    }

    static Supplier<Step> click(final Target t, final int button) {
        return seq(move(t), press(button), pause(70), release(), pause(110));
    }

    static Supplier<Step> click(final Target t) {
        return click(t, 0);
    }

    /** Clicks with Shift held (adding a card to the selection, say): a Shift cap shows by the pointer meanwhile. */
    static Supplier<Step> shiftClick(final Target t) {
        return seq(move(t), () -> d -> {
            Pointer.shift = true;
            if (!d.hurrying()) d.ghost()
                .key("Shift", 520);
            return true;
        }, pause(120), press(0), pause(70), release(), run(() -> Pointer.shift = false), pause(110));
    }

    /** Clicks a target and waits for the popup it opens: a new one, not one still closing from before. */
    static Supplier<Step> clickOpens(final Target t, final int button) {
        return () -> {
            final java.util.Set<Object> before = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            return seq(run(() -> {
                before.clear();
                before.addAll(Targets.popups());
            }), click(t, button), until(() -> {
                for (final Object p : Targets.popups()) if (!before.contains(p)) return true;
                return false;
            }, 2500)).get();
        };
    }

    /** Presses Enter in a number box, and waits for it to close. */
    static Supplier<Step> enterCloses() {
        return seq(
            enter(),
            until(
                () -> Targets.popups()
                    .isEmpty(),
                1500));
    }

    static Supplier<Step> doubleClick(final Target t) {
        return seq(move(t), press(0), pause(50), release(), pause(70), press(0), pause(50), release(), pause(160));
    }

    /** Presses on {@code from}, carries to {@code to} at a steady pace, and lets go. */
    static Supplier<Step> drag(final Target from, final Target to) {
        return seq(
            move(from),
            press(0),
            pause(100),
            move(to, 0.5f, 0.5f, 0.7f, true),
            pause(90),
            release(),
            pause(120));
    }

    /**
     * Drags the board itself by {@code dx, dy} screen pixels from a point of empty board: the drop point is fixed on
     * the screen when the drag starts (the board moving under the cursor must not move it).
     */
    static Supplier<Step> pan(final Target from, final float dx, final float dy) {
        return () -> {
            final float[] to = new float[2];
            final boolean[] fixed = { false };
            final Target dest = () -> {
                if (!fixed[0]) {
                    final Rect r = from.rect();
                    if (r == null) return null;
                    to[0] = r.cx() + dx;
                    to[1] = r.cy() + dy;
                    fixed[0] = true;
                }
                return new Rect(to[0], to[1], 0, 0);
            };
            return seq(move(from), press(0), pause(90), move(dest, 0.5f, 0.5f, 0.75f), pause(80), release(), pause(110))
                .get();
        };
    }

    static Supplier<Step> hover(final Target t, final long ms) {
        return seq(move(t), pause(ms));
    }

    /** Turns the wheel over a target, a notch at a time ({@code notches} up when positive). */
    static Supplier<Step> wheel(final Target t, final int notches) {
        final List<Supplier<Step>> parts = new ArrayList<>();
        parts.add(move(t));
        for (int i = 0; i < Math.abs(notches); i++) {
            parts.add(() -> d -> {
                VirtualInput.scroll(Integer.signum(notches));
                d.ghost()
                    .wheel(notches);
                return true;
            });
            parts.add(pause(150));
        }
        return seq(parts);
    }

    // endregion

    // region Keys and text

    /** Shows a key cap being pressed beside the pointer, and does what the key does. */
    static Supplier<Step> key(final String label, final Runnable action) {
        return () -> new Step() {

            private long t0 = -1;
            private boolean done;

            @Override
            public boolean frame(final Director d) {
                if (t0 < 0) {
                    t0 = d.now();
                    if (!d.hurrying()) d.ghost()
                        .key(label, 560);
                }
                if (!done && (d.hurrying() || d.now() - t0 >= 140)) {
                    done = true;
                    act(action);
                }
                return done && (d.hurrying() || d.now() - t0 >= 400);
            }
        };
    }

    /** Types {@code text} into whatever has the keyboard, a key at a time, at a typist's uneven pace. */
    static Supplier<Step> keys(final String text) {
        return () -> new Step() {

            private long next = -1;
            private int n;

            @Override
            public boolean frame(final Director d) {
                if (next < 0) next = d.now();
                while (n < text.length() && (d.hurrying() || d.now() >= next)) {
                    final char c = text.charAt(n++);
                    VirtualInput.key(c == '\n' ? '\r' : c, VirtualInput.codeOf(c));
                    next += c == ' ' ? 50 : 24 + (long) (Math.abs(Math.sin(n * 12.9898) * 43758.5453) % 1 * 22);
                }
                return n >= text.length();
            }
        };
    }

    /** Presses Enter. */
    static Supplier<Step> enter() {
        return () -> d -> {
            VirtualInput.key('\r', org.lwjgl.input.Keyboard.KEY_RETURN);
            return true;
        };
    }

    /**
     * NEI's search, typed into by setting its text: with lwjgl3ify a key press carries no character (they come as
     * events of their own), so a pressed key types nothing there.
     */
    static Sink neiSearch() {
        return text -> {
            final codechicken.nei.SearchField f = codechicken.nei.LayoutManager.searchField;
            if (f != null) f.setText(text);
        };
    }

    /**
     * The text field of the newest open popup (a number box), else the one with the keyboard: its text set as typed,
     * and the keyboard given to it first. A box takes the keyboard a tick after it opens, and until then the one before
     * it, closed, may still hold it.
     */
    static Sink focusedField() {
        return text -> {
            final com.gtnhplanner.ui.BoardScreen b = Targets.board();
            if (b == null) return;
            com.cleanroommc.modularui.widgets.textfield.TextFieldWidget field = null;
            final java.util.List<com.cleanroommc.modularui.screen.ModularPanel> open = Targets.popups();
            if (!open.isEmpty()) field = fieldIn(open.get(open.size() - 1));
            if (field == null) {
                final com.cleanroommc.modularui.screen.viewport.LocatedWidget f = b.getContext()
                    .getFocusedWidget();
                if (f != null && f.getElement() instanceof final com.cleanroommc.modularui.widgets.textfield.TextFieldWidget t)
                    field = t;
            }
            if (field == null) return;
            if (!field.isFocused()) field.getContext()
                .focus(field);
            field.setText(text);
        };
    }

    /** The first text field in a widget's tree. */
    private static com.cleanroommc.modularui.widgets.textfield.TextFieldWidget fieldIn(
        final com.cleanroommc.modularui.api.widget.IWidget w) {
        if (w instanceof final com.cleanroommc.modularui.widgets.textfield.TextFieldWidget t) return t;
        if (w.getChildren() == null) return null;
        for (final com.cleanroommc.modularui.api.widget.IWidget c : w.getChildren()) {
            final com.cleanroommc.modularui.widgets.textfield.TextFieldWidget t = fieldIn(c);
            if (t != null) return t;
        }
        return null;
    }

    /**
     * Holds a key down for a while: its cap shows, and {@code each} runs once a game tick meanwhile, as a held key
     * does.
     */
    static Supplier<Step> holdKey(final String label, final long ms, final Runnable each) {
        return () -> new Step() {

            private long t0 = -1, last = -1;

            @Override
            public boolean frame(final Director d) {
                if (t0 < 0) {
                    t0 = d.now();
                    if (!d.hurrying()) d.ghost()
                        .key(label, ms + 150);
                }
                if (last < 0 || d.now() - last >= 50) {
                    last = d.now();
                    act(each);
                }
                return d.hurrying() || d.now() - t0 >= ms;
            }
        };
    }

    /** Clicks a target until {@code done} holds, {@code max} times at most, a moment between clicks. */
    static Supplier<Step> clickUntil(final Target t, final BooleanSupplier done, final int max) {
        return () -> new Step() {

            private int clicks;
            private Step click;

            @Override
            public boolean frame(final Director d) {
                if (click == null) {
                    if (test(done) || clicks >= max) return true;
                    click = seq(click(t), pause(260)).get();
                    clicks++;
                }
                if (click.frame(d)) click = null;
                return false;
            }
        };
    }

    /** Presses a key on the open screen (Esc closing a menu, say), its cap showing. */
    static Supplier<Step> press(final String label, final char c, final int code) {
        return key(label, () -> VirtualInput.key(c, code));
    }

    /** Types {@code text} into a sink a letter at a time, at a typist's uneven pace. */
    static Supplier<Step> type(final Supplier<Sink> sink, final String text) {
        return () -> new Step() {

            private long next = -1;
            private int n;
            private Sink s;

            @Override
            public boolean frame(final Director d) {
                if (s == null) s = sink.get();
                if (s == null) return d.gaveUp("nowhere to type");
                // Nothing to type (clearing a box) still clears it.
                if (d.hurrying() || text.isEmpty()) {
                    s.set(text);
                    return true;
                }
                if (next < 0) next = d.now();
                while (n < text.length() && d.now() >= next) {
                    n++;
                    s.set(text.substring(0, n));
                    final char c = text.charAt(n - 1);
                    next += c == ' ' ? 50 : 24 + (long) (Math.abs(Math.sin(n * 12.9898) * 43758.5453) % 1 * 22);
                }
                return n >= text.length();
            }
        };
    }

    // endregion

    // region Time and the game

    static Supplier<Step> pause(final long ms) {
        return () -> new Step() {

            private long t0 = -1;

            @Override
            public boolean frame(final Director d) {
                if (t0 < 0) t0 = d.now();
                // Hurrying, a pause is a game tick at most, not nothing: the screens do some of their work once a tick
                // (a popup opening or closing, a new note taking the keys), and a press and its release in one frame
                // is not a click to all of them.
                return d.now() - t0 >= (d.hurrying() ? Math.min(ms, 60) : ms);
            }
        };
    }

    /** Waits until {@code ready} holds (the board solved, a screen open), {@code timeout} at most. */
    static Supplier<Step> until(final BooleanSupplier ready, final long timeout) {
        return () -> new Step() {

            private long t0 = -1;

            @Override
            public boolean frame(final Director d) {
                if (t0 < 0) t0 = System.currentTimeMillis();
                if (test(ready)) return true;
                return System.currentTimeMillis() - t0 > timeout && d.gaveUp("waited " + timeout + " ms");
            }
        };
    }

    /** Does something at once. */
    static Supplier<Step> run(final Runnable action) {
        return () -> d -> {
            act(action);
            return true;
        };
    }

    /** Lights a target (null: nothing). */
    static Supplier<Step> spot(final Target t) {
        return () -> d -> {
            d.spot(t);
            return true;
        };
    }

    /** Frames a target without dimming the rest, so a tip that opens beside it stays bright. */
    static Supplier<Step> ring(final Target t) {
        return () -> d -> {
            d.spot(t, false);
            return true;
        };
    }

    /**
     * Moves the pointer out of the way (so nothing it rests on shows a tip) and lets the cursor fade: to the board's
     * bottom left corner, or the top of the screen elsewhere.
     */
    static Supplier<Step> rest() {
        final Target spot = () -> {
            final Rect board = Targets.canvasArea()
                .rect();
            final net.minecraft.client.gui.ScaledResolution sr = Targets.resolution();
            return board != null ? new Rect(board.x() + 14, board.bottom() - 14, 0, 0)
                : new Rect(sr.getScaledWidth() / 2f, sr.getScaledHeight() * 0.12f, 0, 0);
        };
        return seq(move(spot, 0.5f, 0.5f, 1.6f), () -> d -> {
            d.ghost()
                .show(false);
            return true;
        });
    }

    /**
     * Shows the callout: {@code text} beside {@code t} (null: the middle of the screen), with a gold frame round it.
     */
    static Supplier<Step> note(final Target t, final String text) {
        return () -> d -> {
            d.note(t, text);
            d.spot(t, false);
            return true;
        };
    }

    // endregion

    /** A step only when {@code cond} holds as it comes up (a menu that may not open, say). */
    static Supplier<Step> when(final BooleanSupplier cond, final Supplier<Step> then) {
        return () -> new Step() {

            private Boolean go;
            private Step inner;

            @Override
            public boolean frame(final Director d) {
                if (go == null) go = test(cond);
                if (!go) return true;
                if (inner == null) inner = then.get();
                return inner.frame(d);
            }
        };
    }

    /** Steps one after another, as one. */
    static Supplier<Step> seq(final List<Supplier<Step>> parts) {
        return () -> new Step() {

            private final List<Step> steps = new ArrayList<>();
            private int i;

            @Override
            public boolean frame(final Director d) {
                for (int guard = 0; guard < 32 && i < parts.size(); guard++) {
                    if (steps.size() <= i) steps.add(
                        parts.get(i)
                            .get());
                    if (!steps.get(i)
                        .frame(d)) return false;
                    i++;
                }
                return i >= parts.size();
            }
        };
    }

    @SafeVarargs
    static Supplier<Step> seq(final Supplier<Step>... parts) {
        return seq(Arrays.asList(parts));
    }

    private static void act(final Runnable action) {
        try {
            action.run();
        } catch (final RuntimeException | LinkageError e) {
            GtnhPlanner.LOG.warn("[tutorial] a step failed", e);
        }
    }

    private static boolean test(final BooleanSupplier s) {
        try {
            return s.getAsBoolean();
        } catch (final RuntimeException | LinkageError e) {
            return false;
        }
    }
}
