// Renders the planner's sounds (sounds.mjs, built with engine.mjs) to WAV and encodes them to Ogg Vorbis (ffmpeg) into
// the mod's assets, with the sounds.json that names them. Nothing is sampled: the whole set remakes from these files.
//
//   node tools/sound/synth.mjs            render everything
//   node tools/sound/synth.mjs ui.click   render the sounds whose names start with that
//
// Each sound renders its picked option (`pick`, else its first) as SET.takes takes. A take is its own seed (noise
// grains, a little pitch drift), its layers landing up to SET.timing early or late, under its own brightness (a
// lowpass up to SET.brightness octaves-and-a-half either way of 5 kHz); then all of them pass the set's tone (one
// lowpass) and soft attack. Loudness is set from the dry sound, as the lab sets it, so the balance is what was heard
// there. sounds.json names every take on its own (ui.click.1, ui.click.2...) so the game can deal them shuffled, and
// the whole sound too. Design notes: docs/design/sound.md.

import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import url from "node:url";

import { SR, Sound, biquad, hash, mulberry32, punch } from "./engine.mjs";
import { SET, SOUNDS } from "./sounds.mjs";

const ROOT = path.resolve(path.dirname(url.fileURLToPath(import.meta.url)), "../..");
const OUT = path.join(ROOT, "src/main/resources/assets/gtnhplanner/sounds");
const WAVS = path.join(ROOT, "build/sound/wav");
const MANIFEST = path.join(ROOT, "tools/sound/manifest.json");

/** The recipe a sound renders with: its picked option, else its first. */
export function recipe(name) {
  const def = SOUNDS[name];
  return def.options[def.pick ?? Object.keys(def.options)[0]];
}

/** A sound made dry, as the lab makes it to set its loudness: seeded by name, its layers on time. */
function dry(name, make, seed = hash(name), timing = 0) {
  const s = new Sound(seed);
  if (timing > 0) {
    const add = s.add.bind(s);
    s.add = (at, samples) => add(Math.max(0, at + (s.rng() - 0.5) * 2 * timing), samples);
  }
  make(s);
  return s.finish();
}

/** One take: its own seed and layer timing, its brightness, the set's tone and soft attack. */
function take(name, make, k) {
  const seed = hash(name) + k * 7919;
  const out = dry(name, make, seed, SET.timing);
  const r = mulberry32(seed ^ 0x5bd1e995);
  const bright = 5000 * Math.pow(2, (r() * 2 - 1) * SET.brightness * 2);
  biquad(out, "lowpass", bright, 1);
  biquad(out, "lowpass", SET.tone, 1);
  const ramp = Math.round(SET.attack * SR);
  for (let i = 0; i < Math.min(ramp, out.length); i++) out[i] *= i / ramp;
  return out;
}

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

function main() {
  const only = process.argv[2];
  const names = Object.keys(SOUNDS).filter((n) => !only || n.startsWith(only));
  const rendered = {};
  for (const name of names) {
    const make = recipe(name);
    const heard = punch(dry(name, make));
    const takes = [];
    for (let k = 0; k < SET.takes; k++) takes.push(take(name, make, k));
    rendered[name] = { heard, takes };
  }
  // Each sound at its level against one reference loudness, the reference as high as lets the peakiest take stop just
  // short of full scale. A partial render keeps the full set's reference so it matches the files already there.
  const old = fs.existsSync(MANIFEST) ? JSON.parse(fs.readFileSync(MANIFEST, "utf8")) : null;
  let reference;
  if (only && old && old.reference) reference = old.reference;
  else {
    reference = Infinity;
    for (const [name, { heard, takes }] of Object.entries(rendered)) {
      let peak = 0;
      for (const b of takes) for (const x of b) peak = Math.max(peak, Math.abs(x));
      if (heard > 1e-5) reference = Math.min(reference, (0.89 * heard) / (peak * SOUNDS[name].level));
    }
  }
  const manifest = only && old ? old : { reference, sounds: {} };
  manifest.reference = reference;
  fs.mkdirSync(WAVS, { recursive: true });
  for (const [name, { heard, takes }] of Object.entries(rendered)) {
    const [family, file] = name.split(".");
    fs.mkdirSync(path.join(OUT, family), { recursive: true });
    for (const f of fs.readdirSync(path.join(OUT, family))) {
      const m = f.match(new RegExp(`^${file}(\\d+)\\.ogg$`));
      if (m && Number(m[1]) > takes.length) fs.rmSync(path.join(OUT, family, f));
    }
    const g = heard > 1e-5 ? (reference * SOUNDS[name].level) / heard : 0;
    const files = [];
    takes.forEach((b, i) => {
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
      ms: Math.round((takes[0].length / SR) * 1000),
      punch: Number(punch(takes[0]).toFixed(4)),
    };
  }
  for (const name of Object.keys(manifest.sounds)) if (!SOUNDS[name]) delete manifest.sounds[name];
  // sounds.json: every take on its own (the game deals them), and each sound whole.
  const json = {};
  for (const name of Object.keys(SOUNDS).sort()) {
    const m = manifest.sounds[name];
    if (!m) continue;
    json[name] = { category: "master", sounds: m.files.map((f) => `gtnhplanner:${f}`) };
    m.files.forEach((f, i) => (json[`${name}.${i + 1}`] = { category: "master", sounds: [`gtnhplanner:${f}`] }));
  }
  fs.writeFileSync(path.join(OUT, "..", "sounds.json"), JSON.stringify(json, null, 2) + "\n");
  fs.writeFileSync(MANIFEST, JSON.stringify(manifest, null, 2) + "\n");
  const click = manifest.sounds["ui.click"];
  console.log(`${names.length} sounds, ${Object.values(rendered).reduce((a, r) => a + r.takes.length, 0)} takes, reference ${reference.toFixed(4)}, click heard ${click ? click.punch : "?"}`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === url.fileURLToPath(import.meta.url)) main();
