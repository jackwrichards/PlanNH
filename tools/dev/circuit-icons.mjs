// Clearer programmed circuit icons, for the planner's cards: GregTech's own 25 icons (0-24) with the number made to
// read at a glance and nothing else changed. The frame, pins, screen colour and its gradient are kept pixel for pixel;
// the faint "88" left on the screen goes (the screen is refitted as its smooth gradient), and the lit segments go
// much darker. Run with node from the checkout: it reads the GregTech jar from Gradle's cache and writes
// src/main/resources/assets/gtnhplanner/textures/circuits/<n>.png, and a before/after strip to build/circuit-icons.png.
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { execFileSync } from "node:child_process";
import os from "node:os";

const OUT = "src/main/resources/assets/gtnhplanner/textures/circuits";
/** The lit segments' colour: the screen's olive, near black. */
const LIT = [0x1c, 0x1f, 0x16];
/** A screen pixel this dark or darker is a lit segment. */
const LIT_LUMA = 0x80;

function findJar() {
    const root = path.join(os.homedir(), ".gradle", "caches", "modules-2", "files-2.1", "com.github.GTNewHorizons",
        "GT5-Unofficial");
    for (const version of fs.readdirSync(root)) {
        for (const hash of fs.readdirSync(path.join(root, version))) {
            for (const file of fs.readdirSync(path.join(root, version, hash))) {
                if (file.endsWith(".jar") && !file.includes("sources")) return path.join(root, version, hash, file);
            }
        }
    }
    throw new Error("GregTech jar not found under " + root);
}

function decode(buf) {
    let p = 8, w, h, ct, bd, plte = null, trns = null;
    const idat = [];
    while (p < buf.length) {
        const len = buf.readUInt32BE(p), type = buf.toString("ascii", p + 4, p + 8), data = buf.subarray(p + 8, p + 8 + len);
        if (type === "IHDR") { w = data.readUInt32BE(0); h = data.readUInt32BE(4); bd = data[8]; ct = data[9]; }
        else if (type === "PLTE") plte = data;
        else if (type === "tRNS") trns = data;
        else if (type === "IDAT") idat.push(data);
        p += 12 + len;
    }
    const raw = zlib.inflateSync(Buffer.concat(idat));
    const ch = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[ct], bpp = Math.max(1, (ch * bd) / 8), stride = Math.ceil((w * ch * bd) / 8);
    const px = Buffer.alloc(w * h * 4);
    let prev = Buffer.alloc(stride), o = 0;
    for (let y = 0; y < h; y++) {
        const f = raw[o++], line = Buffer.from(raw.subarray(o, o + stride));
        o += stride;
        for (let i = 0; i < stride; i++) {
            const a = i >= bpp ? line[i - bpp] : 0, b = prev[i], c = i >= bpp ? prev[i - bpp] : 0;
            let v = line[i];
            if (f === 1) v += a;
            else if (f === 2) v += b;
            else if (f === 3) v += (a + b) >> 1;
            else if (f === 4) { const pp = a + b - c, pa = Math.abs(pp - a), pb = Math.abs(pp - b), pc = Math.abs(pp - c); v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c; }
            line[i] = v & 255;
        }
        prev = line;
        for (let x = 0; x < w; x++) {
            let r, g, bl, al = 255;
            if (ct === 6) [r, g, bl, al] = [line[x * 4], line[x * 4 + 1], line[x * 4 + 2], line[x * 4 + 3]];
            else if (ct === 2) [r, g, bl] = [line[x * 3], line[x * 3 + 1], line[x * 3 + 2]];
            else if (ct === 3) {
                let idx;
                if (bd === 8) idx = line[x];
                else { const per = 8 / bd, shift = (per - 1 - (x % per)) * bd; idx = (line[Math.floor(x / per)] >> shift) & ((1 << bd) - 1); }
                [r, g, bl] = [plte[idx * 3], plte[idx * 3 + 1], plte[idx * 3 + 2]];
                al = trns && idx < trns.length ? trns[idx] : 255;
            } else if (ct === 4) { r = g = bl = line[x * 2]; al = line[x * 2 + 1]; }
            else r = g = bl = line[x];
            px.set([r, g, bl, al], (y * w + x) * 4);
        }
    }
    return { w, h, px };
}

function crc32(b) {
    let crc = 0xffffffff;
    for (const byte of b) {
        let c = (crc ^ byte) & 255;
        for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
        crc = (crc >>> 8) ^ c;
    }
    return (crc ^ 0xffffffff) >>> 0;
}

function encode(w, h, px) {
    const rows = [];
    for (let y = 0; y < h; y++) rows.push(Buffer.from([0]), px.subarray(y * w * 4, (y + 1) * w * 4));
    const chunk = (t, d) => {
        const len = Buffer.alloc(4);
        len.writeUInt32BE(d.length);
        const td = Buffer.concat([Buffer.from(t), d]), crc = Buffer.alloc(4);
        crc.writeUInt32BE(crc32(td));
        return Buffer.concat([len, td, crc]);
    };
    const ih = Buffer.alloc(13);
    ih.writeUInt32BE(w, 0);
    ih.writeUInt32BE(h, 4);
    ih[8] = 8;
    ih[9] = 6;
    return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", ih),
        chunk("IDAT", zlib.deflateSync(Buffer.concat(rows))), chunk("IEND", Buffer.alloc(0))]);
}

const luma = (r, g, b) => 0.3 * r + 0.59 * g + 0.11 * b;

/** The screen: the olive pixels, light (unlit) or dark (lit), inside the frame. */
function isScreen(r, g, b, a) {
    return a === 255 && g > r && g > b && g - b > 6;
}

function clearer(icon) {
    const { w, h, px } = icon;
    const out = Buffer.from(px);
    // Fit the unlit screen's gradient, a plane per channel, by least squares.
    const pts = [];
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
        const i = (y * w + x) * 4, [r, g, b, a] = px.subarray(i, i + 4);
        if (isScreen(r, g, b, a) && luma(r, g, b) > LIT_LUMA) pts.push([x, y, r, g, b]);
    }
    const plane = [2, 3, 4].map((c) => fit(pts.map((p) => [p[0], p[1], p[c]])));
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
        const i = (y * w + x) * 4, [r, g, b, a] = px.subarray(i, i + 4);
        if (!isScreen(r, g, b, a)) continue;
        if (luma(r, g, b) <= LIT_LUMA) out.set([...LIT, 255], i);
        else out.set([...plane.map((p) => clamp(Math.round(p[0] + p[1] * x + p[2] * y))), 255], i);
    }
    return { w, h, px: out };
}

/** v = a + b x + c y, by least squares. */
function fit(points) {
    let n = 0, sx = 0, sy = 0, sv = 0, sxx = 0, syy = 0, sxy = 0, sxv = 0, syv = 0;
    for (const [x, y, v] of points) { n++; sx += x; sy += y; sv += v; sxx += x * x; syy += y * y; sxy += x * y; sxv += x * v; syv += y * v; }
    const m = [[n, sx, sy], [sx, sxx, sxy], [sy, sxy, syy]], r = [sv, sxv, syv];
    return solve(m, r);
}

function solve(m, r) {
    const a = m.map((row, i) => [...row, r[i]]);
    for (let c = 0; c < 3; c++) {
        let best = c;
        for (let k = c + 1; k < 3; k++) if (Math.abs(a[k][c]) > Math.abs(a[best][c])) best = k;
        [a[c], a[best]] = [a[best], a[c]];
        for (let k = 0; k < 3; k++) {
            if (k === c) continue;
            const f = a[k][c] / a[c][c];
            for (let j = c; j < 4; j++) a[k][j] -= f * a[c][j];
        }
    }
    return a.map((row, i) => row[3] / row[i]);
}

const clamp = (v) => Math.max(0, Math.min(255, v));

const jar = findJar();
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "circuits-"));
execFileSync("unzip", ["-o", "-q", jar, "assets/gregtech/textures/items/gt.integrated_circuit/*", "-d", tmp]);
const src = path.join(tmp, "assets/gregtech/textures/items/gt.integrated_circuit");
fs.mkdirSync(OUT, { recursive: true });
const S = 8, cell = 16 * S + 6, strip = { w: 25 * cell, h: 2 * cell };
const preview = Buffer.alloc(strip.w * strip.h * 4, 0);
for (let n = 0; n <= 24; n++) {
    const before = decode(fs.readFileSync(path.join(src, n + ".png")));
    const after = clearer(before);
    fs.writeFileSync(path.join(OUT, n + ".png"), encode(after.w, after.h, after.px));
    [before, after].forEach((im, row) => {
        for (let y = 0; y < 16 * S; y++) for (let x = 0; x < 16 * S; x++) {
            const s = (Math.floor(y / S) * im.w + Math.floor(x / S)) * 4, a = im.px[s + 3] / 255;
            const d = ((row * cell + y) * strip.w + n * cell + x) * 4;
            preview.set([0, 1, 2].map((c) => Math.round(im.px[s + c] * a + 0x26 * (1 - a))).concat(255), d);
        }
    });
}
fs.mkdirSync("build", { recursive: true });
fs.writeFileSync("build/circuit-icons.png", encode(strip.w, strip.h, preview));
fs.rmSync(tmp, { recursive: true, force: true });
console.log("wrote 25 icons to " + OUT + " and build/circuit-icons.png from " + path.basename(jar));
