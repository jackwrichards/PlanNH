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
    },
  },
  "wire.power_cut": {
    level: 0.9, variants: 2, when: "A power wire or drawer link cut.",
    options: {
      "Hum off": (s) => {
        click(s, { freq: 900, peak: 0.3, body: 0 });
        s.hum({ freq: 110, to: 70, dur: 0.12, peak: 0.24, cutoff: 1400, attack: 0.003 });
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
