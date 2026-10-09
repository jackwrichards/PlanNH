// The sound engine: oscillators, filtered noise and envelopes rendered into a buffer, the same in Node (synth.mjs
// renders the game's files) and in a browser (the sound lab plays the same recipes live). Plain JavaScript, no imports.
//
// Materials, by feel:
// - tock: a rounded knock whose pitch drops as it dies away, the way a tap on plastic or wood sounds; no ring. With a
//   snap (a millisecond or two of soft noise) on top it is the click.
// - swish: noise through a band that slides from one pitch to another; lifts, slides, pages, panels.
// - tone: a soft sine with a little warmth, gliding if asked; kept low and short.
// - blob: a low rounded bubble, its pitch rising a little (fluids without the glassware).
// - hum: a smooth buzz built from a few harmonics (power).
// - grains: paper.
// The first set's materials (blip, puff, pad, knock, bubble, buzz) stay for comparison in the lab.

export const SR = 44100;

export function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function hash(s) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  return h >>> 0;
}

function coefficients(type, freq, q) {
  const w = (2 * Math.PI * Math.min(freq, SR * 0.45)) / SR;
  const cos = Math.cos(w), alpha = Math.sin(w) / (2 * q);
  let b0, b1, b2;
  if (type === "lowpass") {
    b0 = (1 - cos) / 2; b1 = 1 - cos; b2 = (1 - cos) / 2;
  } else if (type === "highpass") {
    b0 = (1 + cos) / 2; b1 = -(1 + cos); b2 = (1 + cos) / 2;
  } else {
    // Band-pass, 0 dB at the centre.
    b0 = alpha; b1 = 0; b2 = -alpha;
  }
  const a0 = 1 + alpha;
  return [b0 / a0, b1 / a0, b2 / a0, (-2 * cos) / a0, (1 - alpha) / a0];
}

/** RBJ biquad run in place over a buffer; {@code freq} may be a function of the sample index (a sweep). */
export function biquad(buf, type, freq, q) {
  let c = coefficients(type, typeof freq === "function" ? freq(0) : freq, q);
  let x1 = 0, x2 = 0, y1 = 0, y2 = 0;
  for (let i = 0; i < buf.length; i++) {
    if (typeof freq === "function" && i % 16 === 0) c = coefficients(type, freq(i), q);
    const x = buf[i];
    const y = c[0] * x + c[1] * x1 + c[2] * x2 - c[3] * y1 - c[4] * y2;
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

/** A smooth swell: up over the attack along a half sine, down along a cosine to true zero. */
function swell(t, dur, peak, attack) {
  if (t < 0 || t > dur) return 0;
  if (t < attack) return peak * Math.sin((Math.PI / 2) * (t / attack));
  const u = (t - attack) / Math.max(1e-6, dur - attack);
  return peak * Math.pow(Math.cos((Math.PI / 2) * u), 2);
}

/** A sound under construction: voices are mixed into one buffer, then the master chain runs over it. */
export class Sound {
  /** {@code roof}: the master lowpass, lower is smoother. */
  constructor(seed, { roof = 4800 } = {}) {
    this.rng = mulberry32(seed);
    // A few cents of drift per variant.
    this.drift = 1 + (this.rng() - 0.5) * 0.03;
    this.roof = roof;
    this.buf = new Float32Array(SR * 2);
    this.end = 0;
  }

  add(at, samples) {
    const start = Math.max(0, Math.round(at * SR));
    for (let i = 0; i < samples.length && start + i < this.buf.length; i++) this.buf[start + i] += samples[i];
    this.end = Math.max(this.end, start + samples.length);
  }

  noise(n) {
    const out = new Float32Array(n);
    for (let i = 0; i < n; i++) out[i] = this.rng() * 2 - 1;
    return out;
  }

  // region The smooth set

  /** A rounded knock: a sine starting {@code drop} times higher, falling to {@code freq} as it dies away; no ring. */
  tock({ freq, drop = 1.35, decay, peak, delay = 0, rise = 0.0006 }) {
    const f0 = freq * this.drift;
    const n = Math.ceil(decay * 8 * SR);
    const out = new Float32Array(n);
    let p = this.rng() * 0.2;
    for (let i = 0; i < n; i++) {
      const t = i / SR;
      const f = f0 * (1 + (drop - 1) * Math.exp(-t / (decay * 0.5)));
      p += (2 * Math.PI * f) / SR;
      const env = (t < rise ? t / rise : 1) * Math.exp(-t / decay);
      out[i] = (Math.sin(p) + 0.12 * Math.sin(2 * p)) * env * peak;
    }
    this.add(delay, out);
  }

  /** A soft snap of noise, a millisecond or few: the edge on a click, low-passed so it never spits. */
  snap({ freq = 2200, q = 0.8, dur = 0.003, peak, delay = 0 }) {
    const n = Math.ceil(dur * 6 * SR);
    const out = biquad(this.noise(n), "bandpass", freq * this.drift, q);
    const rise = 0.0003;
    for (let i = 0; i < n; i++) {
      const t = i / SR;
      out[i] *= (t < rise ? t / rise : 1) * Math.exp(-t / (dur / 2)) * peak * 2;
    }
    biquad(out, "lowpass", 5200, 0.7);
    this.add(delay, out);
  }

  /** Noise through a band sliding from one pitch to another, swelled in and out. */
  swish({ from, to, dur, peak, q = 0.9, delay = 0, attack }) {
    const n = Math.ceil(dur * SR);
    const f0 = from * this.drift, f1 = to * this.drift;
    const out = biquad(this.noise(n), "bandpass", (i) => f0 * Math.pow(f1 / f0, i / n), q);
    const a = attack ?? dur * 0.35;
    for (let i = 0; i < n; i++) out[i] *= swell(i / SR, dur, peak * 2.2, a);
    this.add(delay, out);
  }

  /** A soft sine with a little warmth (a quiet second harmonic), gliding if asked, swelled so it never pings. */
  tone({ freq, to = freq, dur, peak, delay = 0, attack = 0.006 }) {
    const f0 = freq * this.drift, f1 = to * this.drift;
    const n = Math.ceil(dur * SR);
    const out = new Float32Array(n);
    let p = 0;
    for (let i = 0; i < n; i++) {
      const t = i / SR;
      p += (2 * Math.PI * f0 * Math.pow(f1 / f0, t / dur)) / SR;
      const env = t < attack ? Math.sin((Math.PI / 2) * (t / attack)) : Math.exp(-((t - attack) / (dur * 0.32)));
      const tail = t > dur * 0.85 ? (dur - t) / (dur * 0.15) : 1;
      out[i] = (Math.sin(p) + 0.15 * Math.sin(2 * p)) * env * tail * peak;
    }
    this.add(delay, out);
  }

  /** A low rounded bubble: its pitch rising a little as it goes, swelled in, no glassy top. */
  blob({ from, to, dur, peak, delay = 0 }) {
    const f0 = from * this.drift, f1 = to * this.drift;
    const n = Math.ceil(dur * 1.4 * SR);
    const out = new Float32Array(n);
    let p = 0;
    for (let i = 0; i < n; i++) {
      const t = i / SR;
      p += (2 * Math.PI * f0 * Math.pow(f1 / f0, Math.min(1, t / dur))) / SR;
      const env = t < 0.004 ? Math.sin((Math.PI / 2) * (t / 0.004)) : Math.exp(-(t - 0.004) / (dur * 0.4));
      out[i] = Math.sin(p) * env * peak;
    }
    this.add(delay, out);
  }

  /** A smooth hum: a few harmonics of a low pitch (a sawtooth with its top cut off), swelled, gliding if asked. */
  hum({ freq, to = freq, dur, peak, cutoff = 1500, delay = 0, attack = 0.005 }) {
    const n = Math.ceil(dur * SR);
    const out = new Float32Array(n);
    const f0 = freq * this.drift, f1 = to * this.drift;
    const top = Math.max(1, Math.floor(cutoff / Math.max(f0, f1)));
    let p = 0;
    for (let i = 0; i < n; i++) {
      const t = i / SR;
      p += (2 * Math.PI * f0 * Math.pow(f1 / f0, t / dur)) / SR;
      let x = 0;
      for (let k = 1; k <= top; k++) x += Math.sin(k * p) / k;
      out[i] = x * 0.6 * swell(t, dur, peak, attack);
    }
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

  // region The wider palette: materials that are not clicks at all

  /** A soft mallet on a wooden bar (marimba-like): a fundamental, a quiet fourth-ish partial, a felt touch. */
  mallet({ freq, dur = 0.14, peak, delay = 0, bright = 0.5 }) {
    const f0 = freq * this.drift;
    const parts = [[1, 1, dur], [3.93, 0.22 * bright, dur * 0.3], [9.79, 0.05 * bright, dur * 0.12]];
    const n = Math.ceil(dur * 5 * SR);
    const out = new Float32Array(n);
    const rise = 0.0025;
    for (const [mul, amp, decay] of parts) {
      if (f0 * mul > 9000) continue;
      const ph = this.rng() * 6.28;
      for (let i = 0; i < n; i++) {
        const t = i / SR;
        out[i] += amp * Math.sin(ph + 2 * Math.PI * f0 * mul * t) * Math.exp(-t / decay);
      }
    }
    for (let i = 0; i < n; i++) {
      const t = i / SR;
      out[i] *= peak * (t < rise ? Math.sin((Math.PI / 2) * (t / rise)) : 1);
    }
    this.add(delay, out);
  }

  /** A plucked string (Karplus-Strong): warm, never harsh; {@code dur} is how long it rings. */
  pluck({ freq, dur = 0.3, peak, delay = 0, bright = 0.4 }) {
    const f0 = freq * this.drift;
    const period = Math.max(2, Math.round(SR / f0));
    const line = new Float32Array(period);
    // A soft excitation: noise smoothed so the pluck starts round.
    let lp = 0;
    for (let i = 0; i < period; i++) {
      lp += (this.rng() * 2 - 1 - lp) * (0.15 + bright * 0.6);
      line[i] = lp;
    }
    const n = Math.ceil(dur * SR);
    const rho = Math.pow(10, -3 / (dur * f0));
    const out = new Float32Array(n);
    let prev = 0;
    for (let i = 0; i < n; i++) {
      const k = i % period;
      const x = line[k];
      out[i] = x;
      const next = rho * (0.5 * (x + prev));
      prev = x;
      line[k] = next;
    }
    const rise = 0.002 * SR;
    for (let i = 0; i < n; i++) {
      const tail = i > n * 0.8 ? (n - i) / (n * 0.2) : 1;
      out[i] *= peak * 2.2 * Math.min(1, i / rise) * tail;
    }
    biquad(out, "lowpass", Math.min(2400, f0 * 5), 0.5);
    this.add(delay, out);
  }

  /** A rubber pop: a low sine that starts high and drops into place in a few milliseconds. */
  pop({ freq, dur = 0.05, peak, delay = 0 }) {
    const f0 = freq * this.drift;
    const n = Math.ceil(dur * 5 * SR);
    const out = new Float32Array(n);
    let p = 0;
    for (let i = 0; i < n; i++) {
      const t = i / SR;
      p += (2 * Math.PI * f0 * (1 + 1.5 * Math.exp(-t / 0.004))) / SR;
      const env = (t < 0.0015 ? t / 0.0015 : 1) * Math.exp(-t / (dur * 0.5));
      out[i] = Math.sin(p) * env * peak;
    }
    this.add(delay, out);
  }

  /** A wood block, low: a soft tap ringing two resonances of a hollow piece of wood. */
  woodblock({ freq, dur = 0.05, peak, delay = 0 }) {
    const f0 = freq * this.drift;
    const n = Math.ceil(dur * 4 * SR);
    const tap = () => {
      const x = new Float32Array(n);
      for (let i = 0; i < Math.round(0.0015 * SR); i++) x[i] = (this.rng() * 2 - 1) * (1 - i / (0.0015 * SR));
      return x;
    };
    const a = biquad(tap(), "bandpass", f0, Math.max(4, f0 * dur * 1.2));
    const b = biquad(tap(), "bandpass", f0 * 2.4, Math.max(4, f0 * dur * 0.8));
    const out = new Float32Array(n);
    for (let i = 0; i < n; i++) out[i] = (a[i] + 0.25 * b[i]) * peak * 9;
    biquad(out, "lowpass", 3000, 0.6);
    this.add(delay, out);
  }

  /** A soft synth note: a sine with a breath of third harmonic, a gentle rise and a little upward lean. */
  boop({ freq, dur = 0.12, peak, delay = 0, attack = 0.008 }) {
    const f0 = freq * this.drift;
    const n = Math.ceil(dur * 1.3 * SR);
    const out = new Float32Array(n);
    let p = 0;
    for (let i = 0; i < n; i++) {
      const t = i / SR;
      p += (2 * Math.PI * f0 * (1 + 0.03 * Math.min(1, t / dur))) / SR;
      const env = t < attack ? Math.sin((Math.PI / 2) * (t / attack)) : Math.exp(-(t - attack) / (dur * 0.4));
      out[i] = (Math.sin(p) + 0.08 * Math.sin(3 * p)) * env * peak;
    }
    this.add(delay, out);
  }

  /** A muted retro square: odd harmonics only, cut off low, swelled so it does not bite. */
  square({ freq, dur = 0.08, peak, delay = 0, cutoff = 2000 }) {
    const f0 = freq * this.drift;
    const n = Math.ceil(dur * 1.2 * SR);
    const out = new Float32Array(n);
    const top = Math.max(1, Math.floor(cutoff / f0));
    for (let i = 0; i < n; i++) {
      const t = i / SR;
      let x = 0;
      for (let k = 1; k <= top; k += 2) x += Math.sin(2 * Math.PI * f0 * k * t) / k;
      const env = (t < 0.003 ? t / 0.003 : 1) * Math.exp(-t / (dur * 0.45));
      out[i] = x * 0.75 * env * peak;
    }
    this.add(delay, out);
  }

  /** Warm FM keys: a sine bent by another at the same pitch, the bend fading fast, so it plinks warm, never glassy. */
  fm({ freq, dur = 0.18, peak, delay = 0, index = 1.4 }) {
    const f0 = freq * this.drift;
    const n = Math.ceil(dur * 1.5 * SR);
    const out = new Float32Array(n);
    for (let i = 0; i < n; i++) {
      const t = i / SR;
      const ix = index * Math.exp(-t / 0.035);
      const env = (t < 0.003 ? t / 0.003 : 1) * Math.exp(-t / (dur * 0.45));
      out[i] = Math.sin(2 * Math.PI * f0 * t + ix * Math.sin(2 * Math.PI * f0 * t)) * env * peak;
    }
    this.add(delay, out);
  }

  /** A hollow box: a soft knock ringing the two hollow resonances of a cardboard or wooden box. */
  box({ freq, dur = 0.05, peak, delay = 0 }) {
    const f0 = freq * this.drift;
    const n = Math.ceil(dur * 4 * SR);
    const src = new Float32Array(n);
    for (let i = 0; i < Math.round(0.003 * SR); i++) src[i] = (this.rng() * 2 - 1) * (1 - i / (0.003 * SR));
    const a = biquad(src.slice(), "bandpass", f0, 6);
    const b = biquad(src.slice(), "bandpass", f0 * 2.3, 5);
    const out = new Float32Array(n);
    for (let i = 0; i < n; i++) {
      const t = i / SR;
      out[i] = (a[i] + 0.5 * b[i]) * peak * 5 * Math.exp(-t / dur);
    }
    biquad(out, "lowpass", 2400, 0.6);
    this.add(delay, out);
  }

  /** A keyboard thock: a low rounded knock with dulled noise on it. */
  thock({ freq, dur = 0.014, peak, delay = 0 }) {
    this.tock({ freq, drop: 1.25, decay: dur, peak, delay, rise: 0.0012 });
    const n = Math.ceil(0.012 * SR);
    const nz = biquad(this.noise(n), "lowpass", 1300, 0.7);
    for (let i = 0; i < n; i++) nz[i] *= peak * 0.5 * Math.exp(-(i / SR) / 0.0025) * Math.min(1, i / (0.0008 * SR));
    this.add(delay, nz);
  }

  // endregion

  /** The click, soft and plastic: a tock with a snap on it and a little weight under it. */
  softClick({ freq = 1050, peak = 0.55, delay = 0, body = 0.25, edge = 2400 }) {
    this.snap({ freq: edge, dur: 0.003, peak: peak * 0.6, delay });
    this.tock({ freq, drop: 1.4, decay: 0.006, peak, delay });
    if (body > 0) this.tock({ freq: 240, drop: 1.2, decay: 0.012, peak: body, delay });
  }

  // endregion

  // region The first set (kept to compare in the lab)

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

  /** Struck things: damped sines, each partial {mul, amp, decay seconds}, after a half-millisecond rise. */
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

  /** A bubble: a sine whose pitch rises as it goes. */
  bubble({ from, to, dur, peak, delay = 0 }) {
    const f0 = from * this.drift, f1 = to * this.drift;
    const n = Math.ceil((dur + 0.02) * SR);
    const out = new Float32Array(n);
    let p = 0;
    for (let i = 0; i < n; i++) {
      const t = i / SR;
      p += (2 * Math.PI * f0 * Math.pow(f1 / f0, Math.min(1, t / dur))) / SR;
      const env = t < 0.003 ? t / 0.003 : Math.exp(-(t - 0.003) / (dur * 0.35));
      out[i] = Math.sin(p) * env * peak;
    }
    this.add(delay, out);
  }

  /** Mains hum: a buzzing pulse, band-passed. */
  buzz({ freq, dur, peak, center = 1200, q = 1.5, delay = 0 }) {
    const n = Math.ceil((dur + 0.03) * SR);
    const out = new Float32Array(n);
    let p = 0;
    for (let i = 0; i < n; i++) {
      p += freq / SR;
      out[i] = p % 1 < 0.3 ? 1 : -0.43;
    }
    biquad(out, "bandpass", center, q);
    for (let i = 0; i < n; i++) out[i] *= shape(i / SR, dur, peak * 3, 0.006);
    this.add(delay, out);
  }

  /** The first set's click: a plastic knock with an inharmonic partial (the glassy one), a bright tick, a body. */
  click({ freq = 1750, peak = 0.5, delay = 0, body = 0.22 }) {
    this.knock({ freq, peak, partials: [[1, 1, 0.009], [2.71, 0.35, 0.004]], delay });
    this.puff({ freq: 3600, q: 0.9, dur: 0.006, peak: peak * 0.7, delay, attack: 0.0006 });
    if (body > 0) this.knock({ freq: 330, peak: body, partials: [[1, 1, 0.018]], delay });
  }

  // endregion

  /** The master chain: the roof, a memoryless clip, the tail trimmed where it falls under hearing. */
  finish() {
    const buf = biquad(this.buf.slice(0, this.end + Math.round(0.02 * SR)), "lowpass", this.roof, 0.6);
    for (let i = 0; i < buf.length; i++) buf[i] = Math.tanh(buf[i] * 1.2) / 1.2;
    let last = buf.length - 1;
    while (last > 0 && Math.abs(buf[last]) < 1e-4) last--;
    const out = buf.slice(0, Math.min(buf.length, last + Math.round(0.005 * SR)));
    const fade = Math.min(out.length, Math.round(0.004 * SR));
    for (let i = 0; i < fade; i++) out[out.length - 1 - i] *= i / fade;
    return out;
  }
}

/**
 * The loudest 30 ms of a sound, RMS, heard through a gentle low cut (the ear hears little below 200 Hz): how loud a
 * short sound is heard, near enough, so a low thud and a bright tick come out matched.
 */
export function punch(samples) {
  const heard = biquad(Float32Array.from(samples), "highpass", 200, 0.6);
  const win = Math.round(0.03 * SR);
  let best = 0, sum = 0;
  for (let i = 0; i < heard.length; i++) {
    sum += heard[i] * heard[i];
    if (i >= win) sum -= heard[i - win] * heard[i - win];
    best = Math.max(best, sum);
  }
  return Math.sqrt(best / win);
}
