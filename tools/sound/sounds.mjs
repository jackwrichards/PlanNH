// The planner's sounds: what each is made of. engine.mjs is the material; synth.mjs renders these into the game's
// files and the sound lab (tools/sound/lab) plays them live. Docs: docs/design/sound.md.
//
// Each sound: how loud against the click (1; the synth scales each so its loudest 30 ms are that many times the
// click's), how many variants the game gets, when it plays, and its options, the first being the one the game uses
// unless `pick` names another. The lab offers every option, and the first set (FIRST_SET, below) to compare.
//
// The feel: clicky but smooth. Knocks are rounded tocks that die away without ringing, edges are soft snaps of noise,
// motion is a swish, tones are low and short. Nothing glassy: no high pure sines, no inharmonic ring.

const click = (s, o = {}) => s.softClick(o);

// region The palette: families of material, each able to play every gesture

/**
 * A family is one material and its register: `base` is the pitch its plain tap sits at (all low: nothing here is
 * meant to poke), `note` plays one hit of it, {f: Hz, at: seconds, peak, len: times its own length}.
 */
export const FAMILIES = {
  "Low click": { base: 520, note: (s, o) => s.softClick({ freq: o.f, peak: 0.5 * o.peak, body: 0.2 * o.peak, edge: 1300, delay: o.at }) },
  "Felt mallet": { base: 440, note: (s, o) => s.mallet({ freq: o.f, dur: 0.12 * o.len, peak: 0.6 * o.peak, delay: o.at }) },
  "Wood block": { base: 560, note: (s, o) => s.woodblock({ freq: o.f, dur: 0.045 * o.len, peak: 0.6 * o.peak, delay: o.at }) },
  "Rubber pop": { base: 300, note: (s, o) => s.pop({ freq: o.f, dur: 0.05 * o.len, peak: 0.7 * o.peak, delay: o.at }) },
  "Plucked string": { base: 294, note: (s, o) => s.pluck({ freq: o.f, dur: 0.25 * o.len, peak: 0.6 * o.peak, delay: o.at }) },
  "Soft synth": { base: 392, note: (s, o) => s.boop({ freq: o.f, dur: 0.11 * o.len, peak: 0.6 * o.peak, delay: o.at }) },
  "Retro square": { base: 330, note: (s, o) => s.square({ freq: o.f, dur: 0.07 * o.len, peak: 0.45 * o.peak, delay: o.at, cutoff: 1800 }) },
  "Warm keys": { base: 349, note: (s, o) => s.fm({ freq: o.f, dur: 0.16 * o.len, peak: 0.55 * o.peak, delay: o.at }) },
  "Hollow box": { base: 300, note: (s, o) => s.box({ freq: o.f, dur: 0.045 * o.len, peak: 0.6 * o.peak, delay: o.at }) },
  "Thock": { base: 240, note: (s, o) => s.thock({ freq: o.f, dur: 0.014 * o.len, peak: 0.7 * o.peak, delay: o.at }) },
  "Bubble": { base: 280, note: (s, o) => s.blob({ from: o.f * 0.85, to: o.f * 1.3, dur: 0.06 * o.len, peak: 0.55 * o.peak, delay: o.at }) },
  "Air": { base: 560, note: (s, o) => s.swish({ from: o.f * 0.7, to: o.f * 1.2, dur: 0.05 * o.len + 0.02, peak: 0.2 * o.peak, q: 1.1, delay: o.at }) },
};

/** Gestures: what a sound does, as hits of a family, each [pitch times its base, at seconds, peak, length]. */
export const GESTURES = {
  tap: [[1, 0, 1, 1]],
  adjust: [[0.9, 0, 0.8, 0.8]],
  tick: [[1.35, 0, 0.55, 0.5]],
  small: [[1.2, 0, 0.45, 0.6]],
  snap: [[1.5, 0, 0.4, 0.4]],
  up2: [[1, 0, 0.85, 0.8], [1.335, 0.05, 0.8, 0.9]],
  down2: [[1.335, 0, 0.85, 0.8], [1, 0.05, 0.8, 0.9]],
  rise: [[0.85, 0, 0.6, 0.7], [1.27, 0.06, 0.7, 1]],
  fall: [[1.27, 0, 0.6, 0.7], [0.85, 0.06, 0.65, 1]],
  deny: [[0.6, 0, 0.9, 0.8], [0.57, 0.085, 0.8, 0.9]],
  page: [[1.1, 0, 0.5, 0.7], [1.3, 0.03, 0.3, 0.6]],
  set: [[0.5, 0, 1, 1.3]],
  drop: [[0.6, 0, 0.8, 1]],
  lift: [[1.2, 0, 0.4, 0.6]],
  remove: [[1, 0, 0.8, 0.8], [0.7, 0.06, 0.8, 1.1]],
  double: [[0.55, 0, 0.9, 1], [0.62, 0.075, 0.9, 1]],
  merge: [[0.75, 0, 0.6, 0.7], [0.84, 0.05, 0.6, 0.7], [1, 0.11, 0.9, 1.2], [0.5, 0.11, 0.7, 1.2]],
  sweep: [[1.5, 0, 0.5, 0.8], [1.25, 0.06, 0.55, 0.8], [1, 0.12, 0.6, 0.9], [0.5, 0.2, 0.8, 1.3]],
  pin: [[1, 0, 0.7, 0.6], [0.5, 0.01, 0.8, 1.2]],
  unpin: [[0.5, 0, 0.5, 0.8], [1, 0.03, 0.6, 0.6]],
  running: [[1, 0, 0.6, 1], [1.26, 0.07, 0.6, 1], [1.5, 0.14, 0.7, 1.5]],
  open: [[0.85, 0, 0.55, 0.8], [1.27, 0.06, 0.65, 1], [0.42, 0.13, 0.7, 1.2]],
  close: [[1.27, 0, 0.55, 0.8], [0.85, 0.06, 0.6, 1], [0.42, 0.12, 0.6, 1.2]],
  latch: [[1, 0, 0.7, 0.8], [0.75, 0.028, 0.9, 1.1]],
  unlatch: [[0.75, 0, 0.6, 0.8], [0.55, 0.03, 0.8, 1.1]],
  heavy: [[0.4, 0, 1, 1.5], [0.8, 0.005, 0.4, 0.6]],
  heavyUp: [[0.5, 0, 0.7, 1], [0.8, 0.05, 0.6, 0.8]],
};

/** A gesture played in a family. */
export function inFamily(family, gesture) {
  const F = FAMILIES[family];
  return (s) => {
    for (const [mul, at, peak, len] of GESTURES[gesture]) F.note(s, { f: F.base * mul, at, peak, len });
  };
}

// endregion

export const SOUNDS = {
  "ui.click": {
    level: 1, variants: 4,
    when: "Any key, row, chip or tab pressed that has nothing more to say; softer and higher when it selects a card.",
    options: {
      "Soft plastic": (s) => click(s),
      "Wood thock": (s) => {
        s.snap({ freq: 1700, q: 0.7, dur: 0.004, peak: 0.25 });
        s.tock({ freq: 620, drop: 1.5, decay: 0.011, peak: 0.6 });
        s.tock({ freq: 170, drop: 1.3, decay: 0.02, peak: 0.3 });
      },
      "Crisp switch": (s) => {
        s.snap({ freq: 3000, dur: 0.002, peak: 0.3 });
        s.tock({ freq: 1500, drop: 1.3, decay: 0.004, peak: 0.45 });
        s.snap({ freq: 2600, dur: 0.002, peak: 0.15, delay: 0.014 });
        s.tock({ freq: 1300, drop: 1.2, decay: 0.003, peak: 0.25, delay: 0.014 });
      },
    },
  },
  "ui.toggle_on": {
    level: 1, variants: 2, when: "A setting switched on: gear rows, card toggles, the minimap and overlay keys.",
    options: {
      "Click and a step up": (s) => {
        click(s);
        s.tone({ freq: 392, to: 523, dur: 0.07, peak: 0.13, delay: 0.012 });
      },
      "Two clicks rising": (s) => {
        click(s, { freq: 950 });
        click(s, { freq: 1250, peak: 0.4, body: 0.1, delay: 0.045 });
      },
    },
  },
  "ui.toggle_off": {
    level: 1, variants: 2, when: "A setting switched off.",
    options: {
      "Click and a step down": (s) => {
        click(s, { freq: 950 });
        s.tone({ freq: 523, to: 392, dur: 0.07, peak: 0.12, delay: 0.012 });
      },
      "Two clicks falling": (s) => {
        click(s, { freq: 1250 });
        click(s, { freq: 950, peak: 0.4, body: 0.1, delay: 0.045 });
      },
    },
  },
  "ui.open": {
    level: 0.75, variants: 2, when: "A menu or box opening over the board, the Non-recipe picker, the plan button's menu.",
    options: {
      "Swish up, latch": (s) => {
        s.swish({ from: 500, to: 1300, dur: 0.07, peak: 0.16, q: 0.8 });
        click(s, { freq: 1200, peak: 0.3, body: 0.1, delay: 0.05 });
      },
    },
  },
  "ui.close": {
    level: 0.65, variants: 2, when: "A menu dismissed (Esc, a click off it), the picker closed, a tab closed, the tour left.",
    options: {
      "Latch, swish down": (s) => {
        click(s, { freq: 900, peak: 0.28, body: 0.1 });
        s.swish({ from: 1300, to: 500, dur: 0.06, peak: 0.12, q: 0.8, delay: 0.008 });
      },
    },
  },
  "ui.deny": {
    level: 0.75, variants: 2,
    when: "Refused: a wire dropped where nothing takes it, cards that cannot share a machine, nothing to undo.",
    options: {
      "Two soft knocks": (s) => {
        s.tock({ freq: 300, drop: 1.3, decay: 0.015, peak: 0.45 });
        s.tock({ freq: 250, drop: 1.3, decay: 0.018, peak: 0.4, delay: 0.075 });
      },
      "A soft scratch": (s) => {
        s.swish({ from: 900, to: 700, dur: 0.06, peak: 0.18, q: 1.1 });
        s.swish({ from: 600, to: 450, dur: 0.09, peak: 0.16, q: 1, delay: 0.05 });
      },
    },
  },
  "ui.tick": {
    level: 0.55, variants: 3,
    when: "One wheel step on a value: amps, machine count, a drawer's rate, note text size; pitched up or down.",
    options: {
      "Small tock": (s) => {
        s.tock({ freq: 1300, drop: 1.3, decay: 0.003, peak: 0.35 });
        s.snap({ freq: 2600, dur: 0.0015, peak: 0.12 });
      },
    },
  },
  "ui.page": {
    level: 0.6, variants: 3,
    when: "A plan tab switched or added, the Library opened, NEI's pages turned from the board, the tour's Next and Back.",
    options: {
      "Paper swish": (s) => {
        s.swish({ from: 700, to: 1600, dur: 0.08, peak: 0.15, q: 0.7 });
        s.swish({ from: 1200, to: 900, dur: 0.05, peak: 0.08, q: 1, delay: 0.03 });
      },
    },
  },

  "board.place": {
    level: 1.3, variants: 3, when: "A card lands: added from NEI, the picker, a custom rate card, or one card pasted.",
    options: {
      "Set on the table": (s) => {
        s.tock({ freq: 210, drop: 1.45, decay: 0.028, peak: 0.7 });
        s.snap({ freq: 1100, q: 0.8, dur: 0.006, peak: 0.25 });
        s.tock({ freq: 650, drop: 1.2, decay: 0.006, peak: 0.2 });
      },
    },
  },
  "board.remove": {
    level: 1.1, variants: 2, when: "Cards or drawers deleted, a recipe taken off a shared machine.",
    options: {
      "Swept off": (s) => {
        s.swish({ from: 1000, to: 400, dur: 0.1, peak: 0.14 });
        s.tone({ freq: 300, to: 200, dur: 0.12, peak: 0.28 });
        s.tock({ freq: 180, drop: 1.2, decay: 0.02, peak: 0.3, delay: 0.05 });
      },
    },
  },
  "board.lift": {
    level: 0.5, variants: 2, when: "A card, drawer or note picked up, once it moves.",
    options: {
      "Soft lift": (s) => {
        s.swish({ from: 400, to: 900, dur: 0.06, peak: 0.12 });
        s.tock({ freq: 500, decay: 0.004, peak: 0.1 });
      },
    },
  },
  "board.drop": {
    level: 0.85, variants: 3, when: "Set down where it was carried.",
    options: {
      "Set down": (s) => {
        s.tock({ freq: 240, drop: 1.35, decay: 0.02, peak: 0.55 });
        s.snap({ freq: 1000, dur: 0.004, peak: 0.18 });
      },
    },
  },
  "board.clone": {
    level: 1.2, variants: 2, when: "A card cloned.",
    options: {
      "Two set down": (s) => {
        s.tock({ freq: 240, drop: 1.35, decay: 0.018, peak: 0.5 });
        s.snap({ freq: 1000, dur: 0.004, peak: 0.15 });
        s.tock({ freq: 285, drop: 1.35, decay: 0.02, peak: 0.55, delay: 0.075 });
        s.snap({ freq: 1150, dur: 0.004, peak: 0.15, delay: 0.075 });
      },
    },
  },
  "board.merge": {
    level: 1.2, variants: 2, when: "Cards combined onto one machine, or a recipe added to one.",
    options: {
      "Close in, latch": (s) => {
        s.tock({ freq: 300, decay: 0.012, peak: 0.35 });
        s.tock({ freq: 340, decay: 0.012, peak: 0.35, delay: 0.05 });
        click(s, { freq: 1100, peak: 0.4, delay: 0.11, body: 0 });
        s.tock({ freq: 220, drop: 1.4, decay: 0.025, peak: 0.45, delay: 0.11 });
      },
    },
  },
  "board.sweep": {
    level: 1.1, variants: 2, when: "Many things at once: Arrange landing, several pasted, a plan pasted or imported.",
    options: {
      "Broad swish, settle": (s) => {
        s.swish({ from: 300, to: 1500, dur: 0.18, peak: 0.2, q: 0.8 });
        s.swish({ from: 1500, to: 500, dur: 0.16, peak: 0.16, q: 0.8, delay: 0.12 });
        s.tock({ freq: 200, drop: 1.3, decay: 0.03, peak: 0.3, delay: 0.2 });
      },
    },
  },
  "board.undo": {
    level: 0.8, variants: 2, when: "Undo.",
    options: {
      "Swish back": (s) => {
        s.swish({ from: 1100, to: 500, dur: 0.07, peak: 0.12 });
        s.tock({ freq: 520, decay: 0.006, peak: 0.25, delay: 0.04 });
      },
    },
  },
  "board.redo": {
    level: 0.8, variants: 2, when: "Redo.",
    options: {
      "Swish forward": (s) => {
        s.swish({ from: 500, to: 1100, dur: 0.07, peak: 0.12 });
        s.tock({ freq: 600, decay: 0.006, peak: 0.25, delay: 0.04 });
      },
    },
  },
  "board.pin": {
    level: 0.9, variants: 2, when: "A machine count pinned, a drawer's rate set: a target the plan solves for.",
    options: {
      "Pressed in": (s) => {
        click(s, { freq: 1200, peak: 0.4, body: 0 });
        s.tock({ freq: 330, drop: 1.3, decay: 0.012, peak: 0.4, delay: 0.008 });
      },
    },
  },
  "board.unpin": {
    level: 0.8, variants: 2, when: "Unpinned, a rate cleared.",
    options: {
      "Pulled out": (s) => {
        s.snap({ freq: 1800, dur: 0.003, peak: 0.15 });
        s.swish({ from: 600, to: 1200, dur: 0.05, peak: 0.1 });
        s.tock({ freq: 700, decay: 0.004, peak: 0.2, delay: 0.02 });
      },
    },
  },
  "board.adjust": {
    level: 0.8, variants: 3,
    when: "A setting changed: machine, coil, number, choice, drawer rule or kind, note colour, plan renamed.",
    options: {
      "Neutral tock": (s) => {
        s.tock({ freq: 800, decay: 0.006, peak: 0.45 });
        s.snap({ freq: 2000, dur: 0.002, peak: 0.15 });
      },
    },
  },
  "board.running": {
    level: 0.7, variants: 1, when: "The plan starts running where it did not: the first pin or rate that makes it solve.",
    options: {
      "Soft fifth": (s) => {
        click(s, { freq: 1100, peak: 0.25, body: 0 });
        s.tone({ freq: 392, dur: 0.09, peak: 0.16 });
        s.tone({ freq: 587, dur: 0.16, peak: 0.16, delay: 0.08 });
      },
      "Motor starting": (s) => {
        click(s, { freq: 900, peak: 0.3, body: 0.1 });
        s.hum({ freq: 45, to: 95, dur: 0.28, peak: 0.22, cutoff: 700, attack: 0.08 });
      },
    },
  },

  "wire.grab": {
    level: 0.5, variants: 2, when: "A wire picked up off a port, once it leaves it.",
    options: {
      "Pluck": (s) => {
        s.tock({ freq: 700, decay: 0.005, peak: 0.3 });
        s.swish({ from: 800, to: 1400, dur: 0.03, peak: 0.06 });
      },
    },
  },
  "wire.snap": {
    level: 0.65, variants: 3, when: "The wire in hand comes over a card or drawer that would take it.",
    options: {
      "Magnet tick": (s) => {
        s.tock({ freq: 1200, decay: 0.003, peak: 0.3 });
        s.snap({ freq: 2400, dur: 0.0015, peak: 0.1 });
      },
    },
  },
  "wire.item": {
    level: 1.1, variants: 3, when: "A wire or drawer link made, carrying items.",
    options: {
      "Snap-fit": (s) => {
        click(s, { freq: 900, peak: 0.45, body: 0 });
        s.snap({ freq: 1500, dur: 0.003, peak: 0.2, delay: 0.028 });
        s.tock({ freq: 520, drop: 1.4, decay: 0.01, peak: 0.55, delay: 0.028 });
        s.tock({ freq: 200, drop: 1.2, decay: 0.015, peak: 0.25, delay: 0.028 });
      },
      "Crate knock": (s) => {
        s.tock({ freq: 300, drop: 1.5, decay: 0.02, peak: 0.6 });
        s.snap({ freq: 1200, dur: 0.005, peak: 0.3 });
        s.tock({ freq: 360, drop: 1.3, decay: 0.012, peak: 0.3, delay: 0.07 });
      },
      "Felt latch": (s) => {
        s.mallet({ freq: 330, dur: 0.1, peak: 0.5 });
        s.mallet({ freq: 247, dur: 0.14, peak: 0.6, delay: 0.03 });
      },
      "Wood peg": (s) => {
        s.woodblock({ freq: 520, peak: 0.55 });
        s.woodblock({ freq: 390, peak: 0.6, delay: 0.035 });
      },
      "Cardboard": (s) => {
        s.box({ freq: 280, peak: 0.6 });
        s.box({ freq: 220, peak: 0.45, delay: 0.04 });
      },
      "Clunk": (s) => {
        s.thock({ freq: 200, peak: 0.7 });
        s.box({ freq: 160, peak: 0.4, delay: 0.01 });
      },
      "Cog": (s) => {
        s.thock({ freq: 420, peak: 0.5 });
        s.thock({ freq: 400, peak: 0.45, delay: 0.022 });
        s.thock({ freq: 380, peak: 0.55, delay: 0.044 });
      },
      "Pluck": (s) => s.pluck({ freq: 220, dur: 0.2, peak: 0.55 }),
      "Retro pickup": (s) => {
        s.square({ freq: 330, dur: 0.05, peak: 0.4, cutoff: 1600 });
        s.square({ freq: 440, dur: 0.07, peak: 0.4, cutoff: 1600, delay: 0.05 });
      },
      "Rubber pop": (s) => {
        s.pop({ freq: 260, peak: 0.7 });
        s.pop({ freq: 200, peak: 0.5, delay: 0.035 });
      },
    },
  },
  "wire.fluid": {
    level: 1.1, variants: 3, when: "A wire or drawer link made, carrying a fluid.",
    options: {
      "Blub": (s) => {
        s.tock({ freq: 300, decay: 0.01, peak: 0.2 });
        s.blob({ from: 260, to: 480, dur: 0.07, peak: 0.5 });
        s.swish({ from: 400, to: 900, dur: 0.09, peak: 0.12, q: 0.7 });
      },
      "Valve": (s) => {
        s.tock({ freq: 600, decay: 0.006, peak: 0.35 });
        s.swish({ from: 1200, to: 800, dur: 0.14, peak: 0.14, q: 0.8 });
        s.blob({ from: 220, to: 300, dur: 0.1, peak: 0.25, delay: 0.02 });
      },
      "Low plop": (s) => {
        s.pop({ freq: 180, dur: 0.07, peak: 0.7 });
        s.blob({ from: 220, to: 300, dur: 0.08, peak: 0.35, delay: 0.01 });
      },
      "Pour": (s) => {
        s.swish({ from: 300, to: 700, dur: 0.16, peak: 0.14, q: 0.6 });
        s.blob({ from: 200, to: 260, dur: 0.12, peak: 0.3 });
      },
      "Gurgle": (s) => {
        s.blob({ from: 200, to: 300, dur: 0.05, peak: 0.4 });
        s.blob({ from: 240, to: 340, dur: 0.05, peak: 0.35, delay: 0.045 });
        s.blob({ from: 210, to: 320, dur: 0.06, peak: 0.35, delay: 0.09 });
      },
      "Soft bloop": (s) => {
        s.boop({ freq: 300, dur: 0.1, peak: 0.5 });
        s.boop({ freq: 400, dur: 0.08, peak: 0.35, delay: 0.05 });
      },
      "Tank slosh": (s) => {
        s.swish({ from: 250, to: 500, dur: 0.2, peak: 0.16, q: 0.5 });
        s.swish({ from: 500, to: 300, dur: 0.15, peak: 0.1, q: 0.5, delay: 0.1 });
        s.thock({ freq: 160, peak: 0.3, delay: 0.02 });
      },
      "Drip": (s) => {
        s.blob({ from: 400, to: 650, dur: 0.03, peak: 0.4 });
        s.blob({ from: 280, to: 360, dur: 0.06, peak: 0.22, delay: 0.04 });
      },
      "Felt bubble": (s) => {
        s.mallet({ freq: 300, dur: 0.1, peak: 0.4 });
        s.blob({ from: 240, to: 380, dur: 0.07, peak: 0.4 });
      },
    },
  },
  "wire.power": {
    level: 1.1, variants: 3, when: "A wire or drawer link made, carrying EU.",
    options: {
      "Hum on": (s) => {
        click(s, { freq: 1100, peak: 0.35, body: 0 });
        s.hum({ freq: 100, dur: 0.12, peak: 0.28, cutoff: 1600, attack: 0.004 });
        s.snap({ freq: 2600, q: 3, dur: 0.006, peak: 0.12, delay: 0.01 });
      },
      "Soft spark": (s) => {
        s.snap({ freq: 2400, q: 3, dur: 0.008, peak: 0.25 });
        s.snap({ freq: 2900, q: 3, dur: 0.006, peak: 0.15, delay: 0.03 });
        s.tone({ freq: 330, to: 440, dur: 0.09, peak: 0.18, delay: 0.01 });
        s.hum({ freq: 120, dur: 0.09, peak: 0.12, cutoff: 1200 });
      },
      "Low thrum": (s) => {
        s.hum({ freq: 55, to: 80, dur: 0.25, peak: 0.35, cutoff: 600, attack: 0.03 });
        s.thock({ freq: 200, peak: 0.3 });
      },
      "Relay clunk": (s) => {
        s.thock({ freq: 260, peak: 0.55 });
        s.thock({ freq: 220, peak: 0.5, delay: 0.03 });
        s.hum({ freq: 90, dur: 0.08, peak: 0.15, cutoff: 900, delay: 0.03 });
      },
      "Capacitor": (s) => {
        s.tone({ freq: 180, to: 360, dur: 0.18, peak: 0.25, attack: 0.02 });
        s.hum({ freq: 100, dur: 0.12, peak: 0.12, cutoff: 800 });
      },
      "Transformer": (s) => {
        s.softClick({ freq: 500, peak: 0.3, edge: 1200, body: 0.15 });
        s.hum({ freq: 60, dur: 0.3, peak: 0.3, cutoff: 700, attack: 0.06 });
      },
      "Retro power-up": (s) => {
        s.square({ freq: 220, dur: 0.05, peak: 0.35, cutoff: 1500 });
        s.square({ freq: 277, dur: 0.05, peak: 0.35, cutoff: 1500, delay: 0.05 });
        s.square({ freq: 330, dur: 0.08, peak: 0.35, cutoff: 1500, delay: 0.1 });
      },
      "Warm zap": (s) => {
        s.fm({ freq: 260, dur: 0.14, peak: 0.45, index: 3 });
        s.hum({ freq: 110, dur: 0.07, peak: 0.12, cutoff: 900 });
      },
      "Static": (s) => {
        for (const at of [0, 0.017, 0.031, 0.052, 0.07]) s.snap({ freq: 900 + s.rng() * 500, q: 2, dur: 0.004, peak: 0.2, delay: at });
        s.hum({ freq: 100, dur: 0.1, peak: 0.12, cutoff: 800 });
      },
    },
  },
  "wire.cut": {
    level: 0.9, variants: 2, when: "An item wire or drawer link cut.",
    options: {
      "Unlatch": (s) => {
        s.snap({ freq: 1500, dur: 0.003, peak: 0.2 });
        s.tone({ freq: 420, to: 300, dur: 0.07, peak: 0.22 });
        s.tock({ freq: 260, decay: 0.012, peak: 0.3, delay: 0.02 });
      },
    },
  },
  "wire.fluid_cut": {
    level: 0.9, variants: 2, when: "A fluid wire or drawer link cut.",
    options: {
      "Drain": (s) => {
        s.blob({ from: 480, to: 260, dur: 0.08, peak: 0.4 });
        s.swish({ from: 900, to: 400, dur: 0.09, peak: 0.1, q: 0.7 });
      },
      "Low plop down": (s) => {
        s.pop({ freq: 220, dur: 0.05, peak: 0.55 });
        s.blob({ from: 300, to: 200, dur: 0.08, peak: 0.35, delay: 0.02 });
      },
      "Pour out": (s) => {
        s.swish({ from: 700, to: 300, dur: 0.15, peak: 0.13, q: 0.6 });
        s.blob({ from: 260, to: 200, dur: 0.1, peak: 0.3 });
      },
      "Gurgle down": (s) => {
        s.blob({ from: 320, to: 240, dur: 0.05, peak: 0.4 });
        s.blob({ from: 280, to: 210, dur: 0.05, peak: 0.35, delay: 0.045 });
        s.blob({ from: 240, to: 180, dur: 0.06, peak: 0.35, delay: 0.09 });
      },
      "Soft bloop down": (s) => {
        s.boop({ freq: 400, dur: 0.08, peak: 0.4 });
        s.boop({ freq: 300, dur: 0.1, peak: 0.45, delay: 0.05 });
      },
    },
  },
  "wire.power_cut": {
    level: 0.9, variants: 2, when: "A power wire or drawer link cut.",
    options: {
      "Hum off": (s) => {
        click(s, { freq: 900, peak: 0.3, body: 0 });
        s.hum({ freq: 110, to: 70, dur: 0.12, peak: 0.24, cutoff: 1400, attack: 0.003 });
      },
      "Thrum down": (s) => s.hum({ freq: 80, to: 45, dur: 0.22, peak: 0.35, cutoff: 600, attack: 0.005 }),
      "Relay release": (s) => {
        s.thock({ freq: 220, peak: 0.5 });
        s.thock({ freq: 260, peak: 0.4, delay: 0.03 });
        s.hum({ freq: 90, to: 60, dur: 0.08, peak: 0.12, cutoff: 800 });
      },
      "Discharge": (s) => {
        s.fm({ freq: 300, dur: 0.1, peak: 0.35, index: 2 });
        s.tone({ freq: 300, to: 180, dur: 0.12, peak: 0.25 });
      },
      "Retro power-down": (s) => {
        s.square({ freq: 330, dur: 0.05, peak: 0.35, cutoff: 1500 });
        s.square({ freq: 277, dur: 0.05, peak: 0.35, cutoff: 1500, delay: 0.05 });
        s.square({ freq: 220, dur: 0.08, peak: 0.35, cutoff: 1500, delay: 0.1 });
      },
    },
  },

  "dial.tier": {
    level: 0.8, variants: 2, when: "A voltage tier stepped; the game pitches it up the ladder from ULV to MAX.",
    options: {
      "Buzz and tock": (s) => {
        s.hum({ freq: 140, dur: 0.06, peak: 0.25, cutoff: 1200, attack: 0.003 });
        s.tock({ freq: 900, decay: 0.004, peak: 0.3 });
      },
    },
  },

  "screen.open": {
    level: 1, variants: 1, when: "The planner opening.",
    options: {
      "Panel slides in": (s) => {
        s.swish({ from: 300, to: 1200, dur: 0.16, peak: 0.18, q: 0.8 });
        s.tock({ freq: 260, drop: 1.3, decay: 0.02, peak: 0.3, delay: 0.12 });
        click(s, { freq: 1100, peak: 0.3, body: 0, delay: 0.14 });
      },
    },
  },
  "screen.close": {
    level: 0.9, variants: 1, when: "The planner closing.",
    options: {
      "Panel slides out": (s) => {
        click(s, { freq: 950, peak: 0.3, body: 0 });
        s.swish({ from: 1200, to: 300, dur: 0.14, peak: 0.16, q: 0.8, delay: 0.01 });
        s.tock({ freq: 220, drop: 1.3, decay: 0.02, peak: 0.25, delay: 0.1 });
      },
    },
  },

  "note.stick": {
    level: 0.9, variants: 2, when: "A sticky note added.",
    options: {
      "Paper slapped down": (s) => {
        s.swish({ from: 900, to: 2000, dur: 0.04, peak: 0.15, q: 0.6, attack: 0.01 });
        s.tock({ freq: 260, drop: 1.3, decay: 0.012, peak: 0.35, delay: 0.02 });
      },
    },
  },
  "note.crumple": {
    level: 0.85, variants: 2, when: "A sticky note deleted, or a plan deleted.",
    options: {
      "Crumpled": (s) => {
        s.grains({ count: 9, from: 900, to: 2800, span: 0.17, peak: 0.1 });
        s.swish({ from: 700, to: 400, dur: 0.12, peak: 0.08 });
      },
    },
  },

  "world.place": {
    level: 1.3, variants: 2, when: "A machine placed on a spot in the world.",
    options: {
      "Block set": (s) => {
        s.tock({ freq: 140, drop: 1.6, decay: 0.035, peak: 0.75 });
        s.snap({ freq: 900, q: 0.7, dur: 0.01, peak: 0.35 });
        s.tock({ freq: 500, decay: 0.006, peak: 0.2 });
      },
    },
  },
  "world.remove": {
    level: 1, variants: 2, when: "A placed machine taken off its spot.",
    options: {
      "Block lifted": (s) => {
        s.swish({ from: 500, to: 1100, dur: 0.08, peak: 0.14 });
        s.tock({ freq: 200, drop: 1.3, decay: 0.02, peak: 0.35 });
      },
    },
  },
};

/** Which gesture each sound is, for its options in every family of the palette. */
const GESTURE_OF = {
  "ui.click": "tap", "ui.toggle_on": "up2", "ui.toggle_off": "down2", "ui.open": "rise", "ui.close": "fall",
  "ui.deny": "deny", "ui.tick": "tick", "ui.page": "page",
  "board.place": "set", "board.remove": "remove", "board.lift": "lift", "board.drop": "drop", "board.clone": "double",
  "board.merge": "merge", "board.sweep": "sweep", "board.undo": "fall", "board.redo": "rise", "board.pin": "pin",
  "board.unpin": "unpin", "board.adjust": "adjust", "board.running": "running",
  "wire.grab": "small", "wire.snap": "snap", "wire.item": "latch", "wire.cut": "unlatch",
  "dial.tier": "tap", "screen.open": "open", "screen.close": "close",
  "note.stick": "drop", "note.crumple": "remove", "world.place": "heavy", "world.remove": "heavyUp",
};
for (const [name, gesture] of Object.entries(GESTURE_OF)) {
  for (const family of Object.keys(FAMILIES)) SOUNDS[name].options[family] ??= inFamily(family, gesture);
}
for (const name of ["ui.click", "ui.tick", "ui.page", "ui.close", "board.lift", "board.adjust", "board.running", "wire.grab", "wire.snap"]) {
  SOUNDS[name].options["None"] = () => {};
}

/**
 * What the game uses where it is not a sound's first option: picked in the lab on 2026-10-09. A sound not here plays its
 * first option.
 */
const PICKS = {
  "ui.click": "Low click",
  "ui.toggle_on": "Hollow box",
  "ui.page": "Thock",
  "ui.toggle_off": "Hollow box",
  "ui.open": "Thock",
  "ui.close": "Thock",
  "board.place": "Rubber pop",
  "board.remove": "Thock",
  "board.lift": "Hollow box",
  "board.drop": "Hollow box",
  "board.clone": "Hollow box",
  "board.merge": "Hollow box",
  "board.sweep": "Hollow box",
  "board.undo": "Hollow box",
  "board.redo": "Hollow box",
  "board.pin": "Thock",
  "board.unpin": "Thock",
  "board.adjust": "Hollow box",
  "board.running": "Motor starting",
  "wire.grab": "Soft synth",
  "wire.snap": "Wood block",
  "wire.fluid": "Drip",
  "wire.power": "Low thrum",
  "wire.fluid_cut": "Low plop down",
  "wire.power_cut": "Thrum down",
  "screen.open": "Low click",
  "screen.close": "Low click",
  "world.place": "Thock",
  "world.remove": "Thock",
};
for (const [name, pick] of Object.entries(PICKS)) {
  if (!SOUNDS[name] || !SOUNDS[name].options[pick]) throw new Error("No option " + pick + " for " + name);
  SOUNDS[name].pick = pick;
}

/**
 * The set's shape, as picked in the lab: one lowpass over everything (tone, Hz), the first milliseconds faded in (attack,
 * seconds), and what each take varies by, baked into the takes (brightness: a lowpass this far either way of 5 kHz, in
 * halves of an octave; timing: each layer this many seconds early or late). The game does the rest as it plays: musical
 * pitch (a step either way, landing on a pentatonic scale), loudness (±1 dB), ducking fast repeats, and dealing the
 * takes shuffled, never the same twice in a row.
 */
export const SET = { tone: 3500, attack: 0.004, brightness: 0.25, timing: 0.002, takes: 5 };

/** The first set, as it shipped on 2026-10-09 (the glassy one), kept to compare in the lab: name: [variants, level, make]. */
export const FIRST_SET = {
  // The keys: clicky, short, the game's own manner. The workhorse of everything pressed.
  "ui.click": [4, 1, (s) => s.click({ freq: 1750 })],
  "ui.toggle_on": [2, 1, (s) => {
    s.click({ freq: 1900, peak: 0.38 });
    s.blip({ from: 660, to: 880, dur: 0.07, peak: 0.13, delay: 0.018 });
  }],
  "ui.toggle_off": [2, 1, (s) => {
    s.click({ freq: 1600, peak: 0.38 });
    s.blip({ from: 660, to: 494, dur: 0.07, peak: 0.12, delay: 0.018 });
  }],
  // A menu or a box opening over the board, and dismissed: a soft pop up, a soft fold down.
  "ui.open": [2, 0.7, (s) => {
    s.click({ freq: 2000, peak: 0.22, body: 0.08 });
    s.puff({ freq: 1100, q: 0.9, dur: 0.08, peak: 0.1 });
    s.blip({ from: 440, to: 622, dur: 0.09, peak: 0.13, delay: 0.008 });
  }],
  "ui.close": [2, 0.6, (s) => {
    s.puff({ freq: 800, q: 0.9, dur: 0.08, peak: 0.09 });
    s.blip({ from: 587, to: 415, dur: 0.09, peak: 0.12 });
  }],
  // Refused: not a buzzer, a soft scratch. Nothing happened.
  "ui.deny": [2, 0.75, (s) => {
    s.puff({ freq: 900, q: 1.2, dur: 0.06, peak: 0.2 });
    s.puff({ freq: 550, q: 1, dur: 0.1, peak: 0.18, delay: 0.05 });
  }],
  // One step of a wheel on a value; the game pitches it by the step.
  "ui.tick": [3, 0.55, (s) => {
    s.knock({ freq: 2300, peak: 0.24, partials: [[1, 1, 0.005], [2.4, 0.3, 0.0025]] });
    s.puff({ freq: 4200, q: 1.2, dur: 0.004, peak: 0.1, attack: 0.0006 });
  }],
  // A page turned: the library's views, the tour's steps.
  "ui.page": [3, 0.6, (s) => {
    s.puff({ freq: 1100, q: 1.2, dur: 0.07, peak: 0.12 });
    s.puff({ freq: 1600, q: 1.6, dur: 0.04, peak: 0.06, delay: 0.03 });
    s.blip({ from: 587, dur: 0.05, peak: 0.06, delay: 0.01 });
  }],

  // The board: cards on a table. Thumps, rounded, a knock for definition.
  "board.place": [3, 1.3, (s) => {
    s.blip({ from: 196, dur: 0.2, peak: 0.44 });
    s.puff({ freq: 1400, dur: 0.05, peak: 0.18 });
    s.knock({ freq: 900, peak: 0.12, partials: [[1, 1, 0.006]] });
  }],
  "board.remove": [2, 1.1, (s) => {
    s.blip({ from: 311, to: 233, dur: 0.18, peak: 0.34 });
    s.puff({ freq: 600, q: 0.8, dur: 0.08, peak: 0.07 });
  }],
  "board.lift": [2, 0.5, (s) => {
    s.puff({ freq: 900, q: 0.8, dur: 0.07, peak: 0.1 });
    s.blip({ from: 294, to: 349, dur: 0.06, peak: 0.07 });
  }],
  "board.drop": [3, 0.85, (s) => {
    s.blip({ from: 220, dur: 0.12, peak: 0.26 });
    s.puff({ freq: 1300, dur: 0.04, peak: 0.12 });
  }],
  "board.clone": [2, 1.2, (s) => {
    s.blip({ from: 220, dur: 0.1, peak: 0.28 });
    s.puff({ freq: 1400, dur: 0.04, peak: 0.12 });
    s.blip({ from: 262, dur: 0.13, peak: 0.32, delay: 0.075 });
    s.puff({ freq: 1500, dur: 0.04, peak: 0.1, delay: 0.075 });
  }],
  // Two recipes onto one machine: two taps closing in, then the latch.
  "board.merge": [2, 1.2, (s) => {
    s.blip({ from: 262, dur: 0.07, peak: 0.2 });
    s.blip({ from: 330, dur: 0.07, peak: 0.2, delay: 0.05 });
    s.click({ freq: 2000, peak: 0.3, delay: 0.12, body: 0.12 });
    s.blip({ from: 392, dur: 0.16, peak: 0.24, delay: 0.12 });
  }],
  // One broad brush for a bulk change: arrange done, a plan pasted, many cards at once.
  "board.sweep": [2, 1.1, (s) => {
    s.puff({ freq: 700, q: 0.9, dur: 0.3, peak: 0.34 });
    s.blip({ from: 233, to: 311, dur: 0.28, peak: 0.2 });
  }],
  "board.undo": [2, 0.8, (s) => {
    s.puff({ freq: 1200, q: 1, dur: 0.07, peak: 0.1 });
    s.blip({ from: 494, to: 392, dur: 0.1, peak: 0.13 });
  }],
  "board.redo": [2, 0.8, (s) => {
    s.puff({ freq: 1200, q: 1, dur: 0.07, peak: 0.1 });
    s.blip({ from: 392, to: 494, dur: 0.1, peak: 0.13 });
  }],
  // A count or a rate pinned: a pushpin pressed in. Unpinned: pulled out.
  "board.pin": [2, 0.9, (s) => {
    s.knock({ freq: 2200, peak: 0.28, partials: [[1, 1, 0.004], [2.2, 0.3, 0.002]] });
    s.blip({ from: 523, to: 659, dur: 0.09, peak: 0.15, delay: 0.015 });
  }],
  "board.unpin": [2, 0.8, (s) => {
    s.puff({ freq: 1500, q: 2, dur: 0.03, peak: 0.08 });
    s.blip({ from: 659, to: 523, dur: 0.09, peak: 0.13 });
  }],
  // A setting on a card changed: a neutral tap.
  "board.adjust": [3, 0.8, (s) => {
    s.blip({ from: 523, dur: 0.08, peak: 0.2 });
    s.knock({ freq: 1800, peak: 0.08, partials: [[1, 1, 0.004]] });
  }],
  // The plan starts running (it solves where it did not): a relay engaging, short and dry.
  "board.running": [1, 0.7, (s) => {
    s.blip({ from: 1047, to: 1319, dur: 0.05, peak: 0.09 });
    s.puff({ freq: 2400, q: 2, dur: 0.04, peak: 0.1 });
    s.blip({ from: 1568, dur: 0.06, peak: 0.06, delay: 0.09 });
  }],

  // Wires, by what they carry. The drag itself is generic; what latches in sounds of its resource.
  "wire.grab": [2, 0.5, (s) => {
    s.knock({ freq: 1400, peak: 0.16, partials: [[1, 1, 0.006]] });
    s.puff({ freq: 2000, q: 2, dur: 0.025, peak: 0.05 });
  }],
  "wire.snap": [3, 0.65, (s) => {
    s.puff({ freq: 1300, q: 1.4, dur: 0.045, peak: 0.2 });
    s.blip({ from: 523, dur: 0.04, peak: 0.1, delay: 0.01 });
  }],
  // Items: a solid clack, a short metal ring, then the latch settling.
  "wire.item": [3, 1.1, (s) => {
    s.knock({ freq: 1100, peak: 0.34, partials: [[1, 1, 0.01], [2.4, 0.4, 0.005]] });
    s.puff({ freq: 3000, q: 1, dur: 0.006, peak: 0.16, attack: 0.0006 });
    s.knock({ freq: 2600, peak: 0.11, partials: [[1, 1, 0.06], [2.76, 0.5, 0.03], [5.4, 0.25, 0.015]], delay: 0.008 });
    s.knock({ freq: 1500, peak: 0.16, partials: [[1, 1, 0.006]], delay: 0.07 });
    s.blip({ from: 587, dur: 0.11, peak: 0.12, delay: 0.07 });
  }],
  // Fluids: two bubbles rising, over a low gloop, a droplet of sparkle.
  "wire.fluid": [3, 1.1, (s) => {
    s.bubble({ from: 520, to: 1150, dur: 0.07, peak: 0.3 });
    s.bubble({ from: 700, to: 1500, dur: 0.06, peak: 0.2, delay: 0.06 });
    s.blip({ from: 247, to: 330, dur: 0.12, peak: 0.14 });
    s.puff({ freq: 3000, q: 1, dur: 0.03, peak: 0.03, delay: 0.05 });
  }],
  // Power: three sparks over a rising bite, a breath of mains buzz under them.
  "wire.power": [3, 1.1, (s) => {
    s.puff({ freq: 2800, q: 6, dur: 0.03, peak: 0.22 });
    s.puff({ freq: 3400, q: 6, dur: 0.025, peak: 0.16, delay: 0.035 });
    s.puff({ freq: 2300, q: 5, dur: 0.035, peak: 0.14, delay: 0.065 });
    s.blip({ from: 740, to: 988, dur: 0.1, peak: 0.18, delay: 0.015 });
    s.buzz({ freq: 120, dur: 0.09, peak: 0.05, center: 1100 });
  }],
  "wire.cut": [2, 0.9, (s) => {
    s.blip({ from: 440, to: 330, dur: 0.16, peak: 0.28 });
    s.puff({ freq: 900, q: 1, dur: 0.05, peak: 0.06 });
  }],
  // Power going: the sparks discharging, the bite falling.
  "wire.power_cut": [2, 0.9, (s) => {
    s.puff({ freq: 3200, q: 6, dur: 0.03, peak: 0.18 });
    s.puff({ freq: 2300, q: 6, dur: 0.03, peak: 0.14, delay: 0.035 });
    s.puff({ freq: 1500, q: 5, dur: 0.045, peak: 0.12, delay: 0.07 });
    s.blip({ from: 988, to: 659, dur: 0.11, peak: 0.16, delay: 0.015 });
  }],

  // A voltage tier stepped: a bite and a spark; the game pitches it up the ladder.
  "dial.tier": [2, 0.8, (s) => {
    s.blip({ from: 300, to: 336, dur: 0.09, peak: 0.2 });
    s.puff({ freq: 2700, q: 6, dur: 0.03, peak: 0.1, delay: 0.01 });
    s.puff({ freq: 2000, q: 5, dur: 0.035, peak: 0.07, delay: 0.04 });
  }],

  // The planner opening: a board unfolding to a latch; closing: the latch, then folding away.
  "screen.open": [1, 1, (s) => {
    s.puff({ freq: 500, q: 0.7, dur: 0.16, peak: 0.12 });
    s.puff({ freq: 950, q: 0.8, dur: 0.12, peak: 0.08, delay: 0.06 });
    s.blip({ from: 262, to: 392, dur: 0.17, peak: 0.18 });
    s.click({ freq: 1800, peak: 0.2, delay: 0.15, body: 0.1 });
  }],
  "screen.close": [1, 0.9, (s) => {
    s.click({ freq: 1650, peak: 0.18, body: 0.1 });
    s.blip({ from: 392, to: 262, dur: 0.15, peak: 0.16, delay: 0.02 });
    s.puff({ freq: 650, q: 0.7, dur: 0.14, peak: 0.1, delay: 0.02 });
  }],

  // Sticky notes: paper slapped down, paper crumpled.
  "note.stick": [2, 0.9, (s) => {
    s.puff({ freq: 1800, q: 0.8, dur: 0.05, peak: 0.16, attack: 0.003 });
    s.puff({ freq: 600, q: 0.7, dur: 0.08, peak: 0.14 });
    s.blip({ from: 330, dur: 0.06, peak: 0.08 });
  }],
  "note.crumple": [2, 0.85, (s) => {
    s.grains({ count: 9, from: 1400, to: 4200, span: 0.17, peak: 0.12 });
    s.puff({ freq: 700, q: 0.7, dur: 0.12, peak: 0.06 });
  }],

  // A machine placed on a spot in the world, and taken off it.
  "world.place": [2, 1.3, (s) => {
    s.knock({ freq: 190, peak: 0.42, partials: [[1, 1, 0.03], [2.3, 0.3, 0.012]] });
    s.puff({ freq: 900, q: 1, dur: 0.06, peak: 0.18 });
    s.click({ freq: 2100, peak: 0.2, delay: 0.004, body: 0 });
    s.blip({ from: 392, dur: 0.12, peak: 0.12, delay: 0.05 });
  }],
  "world.remove": [2, 1, (s) => {
    s.knock({ freq: 260, peak: 0.25, partials: [[1, 1, 0.02]] });
    s.blip({ from: 392, to: 294, dur: 0.14, peak: 0.18 });
  }],
};
