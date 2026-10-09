// Builds the sound lab: the page (template.html) with the engine and the recipes inlined, so it plays the very sounds
// the game's files are rendered from. Writes build/sound/lab/gtnh-planner-sound-lab.html.
//
//   node tools/sound/lab/build.mjs

import fs from "node:fs";
import path from "node:path";
import url from "node:url";

const HERE = path.dirname(url.fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "../../..");
const plain = (file) => fs.readFileSync(path.join(HERE, "..", file), "utf8")
  .replace(/^import .*$/gm, "")
  .replace(/^export /gm, "");

const page = fs.readFileSync(path.join(HERE, "template.html"), "utf8")
  .replace("/*ENGINE*/", () => plain("engine.mjs"))
  .replace("/*SOUNDS*/", () => plain("sounds.mjs"));
const out = path.join(ROOT, "build/sound/lab/gtnh-planner-sound-lab.html");
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, page);
console.log(out, page.length, "bytes");
