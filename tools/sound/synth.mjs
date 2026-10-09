// The planner's sounds, synthesized: every one is built here from oscillators and filtered noise, rendered to WAV and
// encoded to Ogg Vorbis (ffmpeg) into the mod's assets with the sounds.json that names them. Nothing is sampled, so the
// whole set is ours and remakes from this file.
//
//   node tools/sound/synth.mjs            render everything
//   node tools/sound/synth.mjs ui.click   render the sounds whose names start with that
//
// The materials follow the website's board sounds (src/lib/board-sounds.ts in gtnh-factory-flow): every envelope ramps
// in and fully out, fundamentals sit at 200 Hz and up, the mix runs under one soft lowpass and a memoryless tanh
// clip. Added here: a click in the game's own clicky manner (a knock with a tick of noise), bubbles for fluids, a
// metal clink for items, sparks over a mains buzz for power, paper grains for notes. Each sound renders a few
// variants (a seed apiece: noise grains and a little pitch drift differ) and the game picks one at random.
//
// Design notes and what plays where: docs/design/sound.md.

import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import url from "node:url";

const ROOT = path.resolve(path.dirname(url.fileURLToPath(import.meta.url)), "../..");
const OUT = path.join(ROOT, "src/main/resources/assets/gtnhplanner/sounds");
const WAVS = path.join(ROOT, "build/sound/wav");
const SR = 44100;

// region DSP

function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function hash(s) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  return h >>> 0;
}

/** RBJ biquad, run in place over a buffer. */
function biquad(buf, type, freq, q) {
  const w = (2 * Math.PI * Math.min(freq, SR * 0.45)) / SR;
  const cos = Math.cos(w), alpha = Math.sin(w) / (2 * q);
  let b0, b1, b2, a0, a1, a2;
  if (type === "lowpass") {
    b0 = (1 - cos) / 2; b1 = 1 - cos; b2 = (1 - cos) / 2;
  } else if (type === "highpass") {
    b0 = (1 + cos) / 2; b1 = -(1 + cos); b2 = (1 + cos) / 2;
  } else {
    // Band-pass, 0 dB at the centre.
    b0 = alpha; b1 = 0; b2 = -alpha;
  }
  a0 = 1 + alpha; a1 = -2 * cos; a2 = 1 - alpha;
  b0 /= a0; b1 /= a0; b2 /= a0; a1 /= a0; a2 /= a0;
  let x1 = 0, x2 = 0, y1 = 0, y2 = 0;
  for (let i = 0; i < buf.length; i++) {
    const x = buf[i];
    const y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
    x2 = x1; x1 = x; y2 = y1; y1 = y;
    buf[i] = y;
  }
  return buf;
}

/**
 * The website's envelope: fades in over the attack, decays with a body (a third of the peak at 45%), ends at true
 * zero. The body matters: the ear sums over ~150 ms, so a note that is all attack sounds faint.
 */
function shape(t, dur, peak, attack) {
  if (t < 0) return 0;
  if (t < attack) return (peak * t) / attack;
  const mid = dur * 0.45;
  if (t < mid) return peak * Math.pow(0.35, (t - attack) / Math.max(1e-6, mid - attack));
  if (t < dur) return peak * 0.35 * Math.pow(0.002 / 0.35, (t - mid) / (dur - mid));
  if (t < dur + 0.015) return peak * 0.002 * (1 - (t - dur) / 0.015);
  return 0;
}

/** A sound under construction: voices are mixed into one buffer, then the master chain runs over it. */
class Sound {
  constructor(seed) {
    this.rng = mulberry32(seed);
    // A few cents of drift per variant, as the website's blips have per note.
    this.drift = 1 + (this.rng() - 0.5) * 0.03;
    this.buf = new Float32Array(SR * 2);
    this.end = 0;
  }

  add(at, samples) {
    const start = Math.round(at * SR);
    for (let i = 0; i < samples.length && start + i < this.buf.length; i++) this.buf[start + i] += samples[i];
    this.end = Math.max(this.end, start + samples.length);
  }

  noise(n) {
    const out = new Float32Array(n);
    for (let i = 0; i < n; i++) out[i] = this.rng() * 2 - 1;
    return out;
  }

  /** A rounded note: a sine and a quiet, slightly sharp octave, gliding from one pitch to another, low-passed. */
  blip({ from, to = from, dur, peak, delay = 0, octave = 0.22, attack = 0.01 }) {
    const f0 = from * this.drift, f1 = to * this.drift;
    const n = Math.ceil((dur + 0.03) * SR);
    const out = new Float32Array(n);
    let p1 = 0, p2 = 0;
    for (let i = 0; i < n; i++) {
      const t = i / SR;
      const f = f0 * Math.pow(f1 / f0, Math.min(1, t / dur));
      p1 += (2 * Math.PI * f) / SR;
      p2 += (2 * Math.PI * f * 2.004) / SR;
      out[i] = (Math.sin(p1) + octave * Math.sin(p2)) * shape(t, dur, peak, attack);
    }
    biquad(out, "lowpass", Math.min(from * 3.5, 3000), 0.5);
    this.add(delay, out);
  }

  /** A filtered puff of noise: knocks, brushes, air. */
  puff({ freq, q = 1.2, dur, peak, delay = 0, attack = 0.01 }) {
    const n = Math.ceil((dur + 0.03) * SR);
    const out = biquad(this.noise(n), "bandpass", freq * this.drift, q);
    for (let i = 0; i < n; i++) out[i] *= shape(i / SR, dur, peak, attack) * 1.6;
    this.add(delay, out);
  }

  /** A slow-swelled chord of detuned sines: the one material that fades in. */
  pad({ from, to, dur, peak, delay = 0 }) {
    const layers = [
      [1, -5, 0.45], [1, 6, 0.45], [1.498, 3, 0.26], [2.004, -4, 0.12],
    ];
    const n = Math.ceil((dur + 0.05) * SR);
    const out = new Float32Array(n);
    for (const [mul, cents, level] of layers) {
      const det = Math.pow(2, cents / 1200);
      let p = 0;
      for (let i = 0; i < n; i++) {
        const t = i / SR;
        const f = from * mul * det * Math.pow(to / from, Math.min(1, t / (dur * 0.7)));
        p += (2 * Math.PI * f) / SR;
        out[i] += level * Math.sin(p);
      }
    }
    for (let i = 0; i < n; i++) {
      const t = i / SR;
      const g = t < 0.08 ? (peak * t) / 0.08 : shape(t, dur, peak, 0.08);
      out[i] *= g;
    }
    biquad(out, "lowpass", 2600, 0.4);
    this.add(delay, out);
  }

  /**
   * Struck things: damped sines, each partial {mul, amp, decay seconds}, after a half-millisecond rise. The click's
   * knock, a pin's tap, metal's ring.
   */
  knock({ freq, peak, partials = [[1, 1, 0.01]], delay = 0 }) {
    const longest = Math.max(...partials.map((p) => p[2]));
    const n = Math.ceil(longest * 7 * SR);
    const out = new Float32Array(n);
    for (const [mul, amp, decay] of partials) {
      const f = freq * mul * this.drift;
      const phase = this.rng() * Math.PI * 2;
      for (let i = 0; i < n; i++) {
        const t = i / SR;
        out[i] += amp * Math.sin(phase + 2 * Math.PI * f * t) * Math.exp(-t / decay);
      }
    }
    const rise = 0.0005 * SR;
    for (let i = 0; i < n; i++) out[i] *= peak * Math.min(1, i / rise);
    this.add(delay, out);
  }

  /** A bubble: a sine whose pitch rises as it goes, the way a bubble rings as it lifts off. */
  bubble({ from, to, dur, peak, delay = 0 }) {
    const f0 = from * this.drift, f1 = to * this.drift;
    const n = Math.ceil((dur + 0.02) * SR);
    const out = new Float32Array(n);
    let p = 0;
    for (let i = 0; i < n; i++) {
      const t = i / SR;
      const f = f0 * Math.pow(f1 / f0, Math.min(1, t / dur));
      p += (2 * Math.PI * f) / SR;
      const env = t < 0.003 ? t / 0.003 : Math.exp(-(t - 0.003) / (dur * 0.35));
      out[i] = Math.sin(p) * env * peak;
    }
    this.add(delay, out);
  }

  /** Mains hum: a buzzing pulse at the line's pitch, band-passed into the ear's range. */
  buzz({ freq, dur, peak, center = 1200, q = 1.5, delay = 0 }) {
    const n = Math.ceil((dur + 0.03) * SR);
    const out = new Float32Array(n);
    let p = 0;
    for (let i = 0; i < n; i++) {
      p += freq / SR;
      const ph = p % 1;
      out[i] = ph < 0.3 ? 1 : -0.43;
    }
    biquad(out, "bandpass", center, q);
    for (let i = 0; i < n; i++) out[i] *= shape(i / SR, dur, peak * 3, 0.006);
    this.add(delay, out);
  }

  /** Paper: grains of noise, each its own pitch, scattered over a stretch. */
  grains({ count, from, to, span, peak, delay = 0, q = 2 }) {
    for (let g = 0; g < count; g++) {
      const at = delay + this.rng() * span;
      const freq = from * Math.pow(to / from, this.rng());
      const dur = 0.012 + this.rng() * 0.02;
      this.puff({ freq, q, dur, peak: peak * (0.5 + this.rng() * 0.5), delay: at, attack: 0.002 });
    }
  }

  /** The game's kind of click: a short plastic knock, a tick of bright noise, a little low body under it. */
  click({ freq = 1750, peak = 0.5, delay = 0, body = 0.22 }) {
    this.knock({ freq, peak, partials: [[1, 1, 0.009], [2.71, 0.35, 0.004]], delay });
    this.puff({ freq: 3600, q: 0.9, dur: 0.006, peak: peak * 0.7, delay, attack: 0.0006 });
    if (body > 0) this.knock({ freq: 330, peak: body, partials: [[1, 1, 0.018]], delay });
  }

  /** The master chain: a soft roof, a memoryless clip, the tail trimmed where it falls under hearing. */
  finish() {
    const buf = biquad(this.buf.slice(0, this.end + Math.round(0.02 * SR)), "lowpass", 6000, 0.6);
    for (let i = 0; i < buf.length; i++) buf[i] = Math.tanh(buf[i] * 1.2) / 1.2;
    let last = buf.length - 1;
    while (last > 0 && Math.abs(buf[last]) < 1e-4) last--;
    const out = buf.slice(0, Math.min(buf.length, last + Math.round(0.005 * SR)));
    const fade = Math.min(out.length, Math.round(0.004 * SR));
    for (let i = 0; i < fade; i++) out[out.length - 1 - i] *= i / fade;
    return out;
  }
}

// endregion

// region The sounds

/**
 * Name (the sounds.json event, gtnhplanner:<name>), how many variants, how loud against the click (1: each sound is
 * scaled so its loudest 30 ms are that many times the click's), and how it is made. The game plays them all at one
 * volume, matched to the vanilla click's, so these levels are the whole balance.
 */
const SOUNDS = {
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

// endregion

// region Writing

function wav(samples) {
  const data = Buffer.alloc(samples.length * 2);
  for (let i = 0; i < samples.length; i++) {
    data.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(samples[i] * 32767))), i * 2);
  }
  const h = Buffer.alloc(44);
  h.write("RIFF", 0); h.writeUInt32LE(36 + data.length, 4); h.write("WAVE", 8);
  h.write("fmt ", 12); h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22);
  h.writeUInt32LE(SR, 24); h.writeUInt32LE(SR * 2, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34);
  h.write("data", 36); h.writeUInt32LE(data.length, 40);
  return Buffer.concat([h, data]);
}

/** The loudest 30 ms of a sound, RMS: how loud a short sound is heard, near enough. */
function punch(samples) {
  const win = Math.round(0.03 * SR);
  let best = 0, sum = 0;
  for (let i = 0; i < samples.length; i++) {
    sum += samples[i] * samples[i];
    if (i >= win) sum -= samples[i - win] * samples[i - win];
    best = Math.max(best, sum);
  }
  return Math.sqrt(best / win);
}

function main() {
  const only = process.argv[2];
  const rendered = {};
  for (const [name, [variants, , make]] of Object.entries(SOUNDS)) {
    if (only && !name.startsWith(only)) continue;
    rendered[name] = [];
    for (let v = 0; v < variants; v++) {
      const s = new Sound(hash(name) + v * 7919);
      make(s);
      rendered[name].push(s.finish());
    }
  }
  // Each sound scaled to its level against one reference loudness, the reference as high as lets the peakiest file
  // stop just short of full scale. A partial render keeps the full set's reference (in the manifest) so it matches the
  // files already there.
  const manifestPath = path.join(ROOT, "tools/sound/manifest.json");
  const level = (name) => SOUNDS[name][1];
  const loudness = (list) => list.reduce((a, b) => a + punch(b), 0) / list.length;
  let reference;
  if (only && fs.existsSync(manifestPath)) {
    reference = JSON.parse(fs.readFileSync(manifestPath, "utf8")).reference;
  } else {
    reference = Infinity;
    for (const [name, list] of Object.entries(rendered)) {
      let peak = 0;
      for (const b of list) for (const x of b) peak = Math.max(peak, Math.abs(x));
      reference = Math.min(reference, (0.89 * loudness(list)) / (peak * level(name)));
    }
  }
  fs.mkdirSync(WAVS, { recursive: true });
  const manifest = only && fs.existsSync(manifestPath)
    ? JSON.parse(fs.readFileSync(manifestPath, "utf8"))
    : { reference, sounds: {} };
  const json = {};
  for (const [name, list] of Object.entries(rendered)) {
    const [family, file] = name.split(".");
    fs.mkdirSync(path.join(OUT, family), { recursive: true });
    const files = [];
    const g = (reference * level(name)) / loudness(list);
    list.forEach((b, i) => {
      for (let k = 0; k < b.length; k++) b[k] *= g;
      const base = `${file}${i + 1}`;
      const w = path.join(WAVS, `${family}.${base}.wav`);
      fs.writeFileSync(w, wav(b));
      execFileSync("ffmpeg", ["-loglevel", "error", "-y", "-i", w, "-c:a", "libvorbis", "-q:a", "6",
        path.join(OUT, family, `${base}.ogg`)]);
      files.push(`${family}/${base}`);
    });
    manifest.sounds[name] = {
      files,
      ms: Math.round((list[0].length / SR) * 1000),
      punch: Number(punch(list[0]).toFixed(4)),
    };
    json[name] = { category: "master", sounds: files.map((f) => `gtnhplanner:${f}`) };
  }
  // sounds.json lists every sound, rendered now or before.
  const all = {};
  for (const [name, m] of Object.entries(manifest.sounds)) {
    all[name] = { category: "master", sounds: m.files.map((f) => `gtnhplanner:${f}`) };
  }
  Object.assign(all, json);
  const sorted = Object.fromEntries(Object.keys(all).sort().map((k) => [k, all[k]]));
  fs.writeFileSync(path.join(OUT, "..", "sounds.json"), JSON.stringify(sorted, null, 2) + "\n");
  fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + "\n");
  console.log(`${Object.keys(rendered).length} sounds, ${Object.values(rendered).flat().length} files, click loudness ${reference.toFixed(4)}`);
}

main();

// endregion
