package com.gtnhplanner.ui.sound;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import com.gtnhplanner.ui.PlannerSettings;
import com.gtnhplanner.ui.Resources;
import com.gtnhplanner.ui.tutorial.Tutorial;

/**
 * The planner's sounds: made by {@code tools/sound/synth.mjs} (each balanced there against the click), named in
 * {@code assets/gtnhplanner/sounds.json}, and what plays where is {@code docs/design/sound.md}. All play at one volume,
 * matched to the vanilla click's, times the player's Sounds setting, under the game's master volume.
 * <p>
 * {@link #CLICK} is the fallback for anything pressed: it gives way to a sound of the same action that says more (a
 * tier stepping, a menu opening, a card landing), played just before or just after it. Repeats of a sound in quick
 * succession play quieter (a wheel spun sounds like one, not a crescendo), and nothing plays while the tour hurries.
 * <p>
 * Each sound has several takes (each its own brightness and timing, made by the synth), dealt like a shuffled deck so
 * the same take never plays twice in a row; each play steps its pitch a little (a whole tone either way, or none,
 * landing on a pentatonic scale, so repeats stay in tune) and its loudness by up to a decibel. As picked in the sound
 * lab. Client thread only.
 */
public enum Sfx {

    // Keys
    CLICK("ui.click"),
    TOGGLE_ON("ui.toggle_on"),
    TOGGLE_OFF("ui.toggle_off"),
    OPEN("ui.open"),
    CLOSE("ui.close"),
    DENY("ui.deny"),
    TICK("ui.tick"),
    PAGE("ui.page"),
    // The board
    PLACE("board.place"),
    REMOVE("board.remove"),
    LIFT("board.lift"),
    DROP("board.drop"),
    CLONE("board.clone"),
    MERGE("board.merge"),
    SWEEP("board.sweep"),
    UNDO("board.undo"),
    REDO("board.redo"),
    PIN("board.pin"),
    UNPIN("board.unpin"),
    ADJUST("board.adjust"),
    RUNNING("board.running"),
    // Wires, by what they carry
    WIRE_GRAB("wire.grab"),
    WIRE_SNAP("wire.snap"),
    WIRE_ITEM("wire.item"),
    WIRE_FLUID("wire.fluid"),
    WIRE_POWER("wire.power"),
    WIRE_CUT("wire.cut"),
    WIRE_FLUID_CUT("wire.fluid_cut"),
    WIRE_POWER_CUT("wire.power_cut"),
    // A voltage tier stepped, pitched up its ladder
    TIER("dial.tier"),
    // Notes, the world
    NOTE_STICK("note.stick"),
    NOTE_CRUMPLE("note.crumple"),
    WORLD_PLACE("world.place"),
    WORLD_REMOVE("world.remove");

    /** The volume our click plays at to be as loud as the game's own click, which plays at a quarter. */
    private static final float BASE = 0.573f;
    /** Each play's loudness, up to this many decibels either way. */
    private static final float LOUDNESS_DB = 1f;
    /** Each play's pitch step in semitones, one picked at random; then the pitch lands on {@link #SCALE}. */
    private static final int[] PITCH_STEPS = { -2, 0, 0, 0, 2 };
    /** The pentatonic scale every pitch lands on, in semitones within the octave (12 closes it). */
    private static final int[] SCALE = { 0, 2, 4, 7, 9, 12 };
    /** A second play of a sound this soon is the same event twice (keys come in twice), and dropped. */
    private static final long SAME_MS = 30;
    /**
     * Repeats inside this window play quieter, by {@link #DUCK} a time up to {@link #DUCK_FLOOR} times: the ear sums
     * pulses closer than ~200 ms, so a wheel spun would swell. Tight, so two deliberate actions do not duck.
     */
    private static final long REPEAT_MS = 120;
    private static final float DUCK = 0.6f;
    private static final int DUCK_FLOOR = 3;
    /** How near a click and a sound that says more must be to be one action. */
    private static final long SAME_ACTION_MS = 80;

    private static final Random RANDOM = new Random();
    private static long quietUntil, lastSpecificAt = -1, lastClickAt = -1;
    private static UiSound lastClick;
    /** The last plays, newest first, for the dev harness (the dev game is muted). */
    private static final Deque<String> RECENT = new ArrayDeque<>();

    private final ResourceLocation location;
    private long lastAt = -1;
    private int streak;
    /** Its takes (gtnhplanner:name.1, .2...), found once the sounds have loaded; empty plays it whole. */
    private ResourceLocation[] takes;
    /** The takes left in this round of the deck, and the last one dealt. */
    private final java.util.ArrayDeque<Integer> deck = new java.util.ArrayDeque<>();
    private int lastTake = -1;

    Sfx(final String name) {
        this.location = new ResourceLocation("gtnhplanner", name);
    }

    /** Its name in sounds.json (ui.click, wire.fluid...). */
    public String id() {
        return location.getResourcePath();
    }

    public void play() {
        play(1f, 1f);
    }

    public void play(final float pitch) {
        play(1f, pitch);
    }

    /**
     * Plays it, {@code volume} times its own level (1 as balanced), at {@code pitch} (0.5 to 2, as the game allows).
     */
    public void play(final float volume, final float pitch) {
        final float gain = PlannerSettings.soundGain();
        if (gain <= 0 || quiet()) return;
        final long now = now();
        if (this == CLICK) {
            if (lastSpecificAt >= 0 && now - lastSpecificAt < SAME_ACTION_MS) return;
        } else if (this != TICK) {
            lastSpecificAt = now;
            // The click this action already made gives way.
            if (lastClick != null && now - lastClickAt < SAME_ACTION_MS) {
                stop(lastClick);
                lastClick = null;
            }
        }
        if (lastAt >= 0 && now - lastAt < SAME_MS) return;
        streak = lastAt >= 0 && now - lastAt < REPEAT_MS ? streak + 1 : 0;
        lastAt = now;
        final float duck = (float) Math.pow(DUCK, Math.min(streak, DUCK_FLOOR));
        // A few cents either way, so a sound played over and over is never stamped out.
        final float step = (float) Math.pow(2, PITCH_STEPS[RANDOM.nextInt(PITCH_STEPS.length)] / 12.0);
        final float p = Math.max(0.5f, Math.min(2f, onScale(pitch * step)));
        final float loud = (float) Math.pow(10, (RANDOM.nextFloat() * 2 - 1) * LOUDNESS_DB / 20);
        final ResourceLocation take = deal();
        final UiSound sound = new UiSound(take, Math.min(1f, BASE * gain * volume * duck * loud), p);
        Minecraft.getMinecraft()
            .getSoundHandler()
            .playSound(sound);
        if (this == CLICK) {
            lastClick = sound;
            lastClickAt = now;
        }
        remember(String.format("%s v%.2f p%.2f", take.getResourcePath(), sound.getVolume(), p));
    }

    /** The next take: a shuffled round of them, the first of a round never the last of the one before. */
    private ResourceLocation deal() {
        if (takes == null) {
            final java.util.List<ResourceLocation> found = new java.util.ArrayList<>();
            final net.minecraft.client.audio.SoundHandler handler = Minecraft.getMinecraft()
                .getSoundHandler();
            for (int k = 1; k <= 16; k++) {
                final ResourceLocation at = new ResourceLocation("gtnhplanner", id() + "." + k);
                if (handler.getSound(at) == null) break;
                found.add(at);
            }
            takes = found.toArray(new ResourceLocation[0]);
        }
        if (takes.length == 0) return location;
        if (takes.length == 1) return takes[0];
        if (deck.isEmpty()) {
            final java.util.List<Integer> round = new java.util.ArrayList<>();
            for (int k = 0; k < takes.length; k++) round.add(k);
            java.util.Collections.shuffle(round, RANDOM);
            if (round.get(0) == lastTake) java.util.Collections.swap(round, 0, round.size() - 1);
            deck.addAll(round);
        }
        lastTake = deck.poll();
        return takes[lastTake];
    }

    /** A pitch moved to the nearest note of {@link #SCALE}, in whatever octave it is in. */
    private static float onScale(final float pitch) {
        final double semis = 12 * Math.log(pitch) / Math.log(2);
        final double octave = Math.floor(semis / 12), within = semis - octave * 12;
        int best = 0;
        for (final int note : SCALE) if (Math.abs(note - within) < Math.abs(best - within)) best = note;
        return (float) Math.pow(2, (octave * 12 + best) / 12);
    }

    /** One rung of a ladder: the tier dial's and the like, {@code step} rungs of {@code ratio} up from {@code base}. */
    public void playStep(final int step, final float base, final float ratio) {
        play(1f, (float) (base * Math.pow(ratio, step)));
    }

    /** A wire latching in (or a drawer taking a resource), as what it carries: item, fluid or power. */
    public static void connect(final String resourceKey) {
        if (resourceKey == null) WIRE_ITEM.play();
        else if (Resources.isPower(resourceKey)) WIRE_POWER.play();
        else if (Resources.isFluid(resourceKey)) WIRE_FLUID.play();
        else WIRE_ITEM.play();
    }

    /** A wire cut, as what it carried: power hums off, a fluid drains, an item unlatches. */
    public static void cut(final String resourceKey) {
        if (resourceKey != null && Resources.isPower(resourceKey)) WIRE_POWER_CUT.play();
        else if (resourceKey != null && Resources.isFluid(resourceKey)) WIRE_FLUID_CUT.play();
        else WIRE_CUT.play();
    }

    /** A voltage tier stepped to: pitched up the ladder, ULV low to MAX high. */
    public static void tier(final int tier) {
        TIER.playStep(tier, 0.62f, 1.085f);
    }

    /** No sounds for a moment: a plan loading, a tab switching (its cards are not landing, they were there). */
    public static void quietFor(final long ms) {
        quietUntil = Math.max(quietUntil, now() + ms);
    }

    private static boolean quiet() {
        return now() < quietUntil || Tutorial.hurried();
    }

    private static void stop(final UiSound sound) {
        try {
            Minecraft.getMinecraft()
                .getSoundHandler()
                .stopSound(sound);
        } catch (final RuntimeException e) {
            // A sound the system has already let go of.
        }
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    private static void remember(final String what) {
        synchronized (RECENT) {
            RECENT.addFirst(System.currentTimeMillis() % 100_000 + " " + what);
            while (RECENT.size() > 40) RECENT.removeLast();
        }
    }

    /** The last sounds played, newest first: for the dev harness. */
    public static List<String> recent() {
        synchronized (RECENT) {
            return new ArrayList<>(RECENT);
        }
    }
}
