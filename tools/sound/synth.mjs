// Renders the planner's sounds (sounds.mjs, built with engine.mjs) to WAV and encodes them to Ogg Vorbis (ffmpeg) into
// the mod's assets, with the sounds.json that names them. Nothing is sampled: the whole set remakes from these files.
//
//   node tools/sound/synth.mjs            render everything
//   node tools/sound/synth.mjs ui.click   render the sounds whose names start with that
//
// Each sound renders its picked option (its first, unless `pick` names another), a few variants apiece (a seed each:
// noise grains and a little pitch drift differ), scaled to its level against the click. The game plays them all at one
// volume matched to the vanilla click's, so the levels are the whole balance. Design notes: docs/design/sound.md.

import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import url from "node:url";

import { SR, Sound, hash, punch } from "./engine.mjs";
import { SOUNDS } from "./sounds.mjs";

const ROOT = path.resolve(path.dirname(url.fileURLToPath(import.meta.url)), "../..");
const OUT = path.join(ROOT, "src/main/resources/assets/gtnhplanner/sounds");
const WAVS = path.join(ROOT, "build/sound/wav");
const MANIFEST = path.join(ROOT, "tools/sound/manifest.json");

/** The recipe a sound renders with: its picked option, else its first. */
export function recipe(name) {
  const def = SOUNDS[name];
  return def.options[def.pick ?? Object.keys(def.options)[0]];
}

/** A sound's variants, rendered (variant v is seeded from its name, so a render is the same every time). */
export function render(name, make = recipe(name), variants = SOUNDS[name].variants) {
  const out = [];
  for (let v = 0; v < variants; v++) {
    const s = new Sound(hash(name) + v * 7919);
    make(s);
    out.push(s.finish());
  }
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
  const rendered = {};
  for (const name of Object.keys(SOUNDS)) if (!only || name.startsWith(only)) rendered[name] = render(name);
  // Each sound scaled to its level against one reference loudness, the reference as high as lets the peakiest file
  // stop just short of full scale. A partial render keeps the full set's reference so it matches the files there.
  const level = (name) => SOUNDS[name].level;
  const loudness = (list) => list.reduce((a, b) => a + punch(b), 0) / list.length;
  const old = fs.existsSync(MANIFEST) ? JSON.parse(fs.readFileSync(MANIFEST, "utf8")) : null;
  let reference;
  if (only && old) reference = old.reference;
  else {
    reference = Infinity;
    for (const [name, list] of Object.entries(rendered)) {
      let peak = 0;
      for (const b of list) for (const x of b) peak = Math.max(peak, Math.abs(x));
      reference = Math.min(reference, (0.89 * loudness(list)) / (peak * level(name)));
    }
  }
  const manifest = only && old ? old : { reference, sounds: {} };
  fs.mkdirSync(WAVS, { recursive: true });
  for (const [name, list] of Object.entries(rendered)) {
    const [family, file] = name.split(".");
    fs.mkdirSync(path.join(OUT, family), { recursive: true });
    // Variants this render no longer makes (fewer than before) go.
    for (const f of fs.readdirSync(path.join(OUT, family))) {
      const m = f.match(new RegExp(`^${file}(\\d+)\\.ogg$`));
      if (m && Number(m[1]) > list.length) fs.rmSync(path.join(OUT, family, f));
    }
    const g = (reference * level(name)) / loudness(list);
    const files = [];
    list.forEach((b, i) => {
      for (let k = 0; k < b.length; k++) b[k] *= g;
      const base = `${file}${i + 1}`;
      const w = path.join(WAVS, `${family}.${base}.wav`);
      fs.writeFileSync(w, wav(b));
      execFileSync("ffmpeg", ["-loglevel", "error", "-y", "-i", w, "-c:a", "libvorbis", "-q:a", "6",
        path.join(OUT, family, `${base}.ogg`)]);
      files.push(`${family}/${base}`);
    });
    manifest.sounds[name] = { files, ms: Math.round((list[0].length / SR) * 1000), punch: Number(punch(list[0]).toFixed(4)) };
  }
  // sounds.json names every sound the set has now.
  const json = {};
  for (const name of Object.keys(SOUNDS).sort()) {
    const m = manifest.sounds[name];
    if (m) json[name] = { category: "master", sounds: m.files.map((f) => `gtnhplanner:${f}`) };
  }
  for (const name of Object.keys(manifest.sounds)) if (!SOUNDS[name]) delete manifest.sounds[name];
  fs.writeFileSync(path.join(OUT, "..", "sounds.json"), JSON.stringify(json, null, 2) + "\n");
  fs.writeFileSync(MANIFEST, JSON.stringify(manifest, null, 2) + "\n");
  console.log(`${Object.keys(rendered).length} sounds, ${Object.values(rendered).flat().length} files, click loudness ${reference.toFixed(4)}`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === url.fileURLToPath(import.meta.url)) main();
