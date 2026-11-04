#!/usr/bin/env node
'use strict';
/*
 * design-system/tools/capture.mjs — the catalog, captured from the television.
 *
 * The Studio and the player preview render the real components; this script walks every entry in
 * studio.json, launches it in capture mode with one theme and state, takes a screenshot and either
 * writes the golden or diffs it against the stored one.
 *
 *   node design-system/tools/capture.mjs --write            # refresh every golden
 *   node design-system/tools/capture.mjs                     # capture and diff
 *   node design-system/tools/capture.mjs --only settings.rows
 *   node design-system/tools/capture.mjs --strict            # fail on any missing or changed golden
 *
 * Requires adb on PATH (or ADB=...); the TV must be connected and awake.
 */
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.join(HERE, '..');
const REPO = path.join(ROOT, '..');
const GOLDENS = path.join(ROOT, 'goldens', 'android');
const TMP = path.join(ROOT, '.captures');
const ADB = process.env.ADB || 'adb';
const SERIAL = process.env.ADB_SERIAL || '';
const ADB_BASE = SERIAL ? ['-s', SERIAL] : [];
const PY = process.env.PYTHON || '/usr/bin/python3';
const STUDIO = 'com.scenix.tv/.StudioActivity';
const PLAYER = 'com.scenix.tv/.PlayerActivity';

const args = process.argv.slice(2);
const write = args.includes('--write');
const strict = args.includes('--strict');
const only = (() => { const i = args.indexOf('--only'); return i >= 0 ? args[i + 1] : ''; })();

const studio = JSON.parse(fs.readFileSync(path.join(ROOT, 'studio.json'), 'utf8'));
const themes = studio.themes;
const targets = [];
for (const entry of studio.entries) {
  if (String(entry.status || '').startsWith('blocked')) continue;
  if (only && entry.id !== only) continue;
  const list = Array.isArray(entry.themes) && entry.themes.length ? entry.themes : themes;
  for (const state of entry.states || []) {
    for (const theme of list) targets.push({ entry, state, theme });
  }
}

fs.mkdirSync(TMP, { recursive: true });
try { adb(['shell', 'svc', 'power', 'stayon', 'true']); } catch (e) { /* not every TV allows it */ }

function sh(file, args) {
  return execFileSync(file, args, { encoding: 'buffer', maxBuffer: 40 * 1024 * 1024 });
}

function adb(args) {
  return execFileSync(ADB, ADB_BASE.concat(args), { encoding: 'buffer', maxBuffer: 40 * 1024 * 1024 });
}

function sleep(ms) {
  // A capture is a wall-clock act: the activity must be laid out and its preview posted.
  const end = Date.now() + ms;
  while (Date.now() < end) {
    try { execFileSync('sleep', ['0.1']); } catch (e) { /* ignore */ }
  }
}

function goldenPath(t) {
  return path.join(GOLDENS, t.entry.id, t.state, t.theme + '.png');
}

function launch(t) {
  // A television sleeps when nobody presses a key. `screencap` on a sleeping panel is a black
  // rectangle, which is how half the first golden set came out empty.
  try { adb(['shell', 'input', 'keyevent', 'KEYCODE_WAKEUP']); } catch (e) { /* ignore */ }
  adb(['shell', 'am', 'force-stop', 'com.scenix.tv']);
  // `adb shell` re-splits its argument through the device shell. Passing the whole `am start`
  // command as ONE argument keeps a quoted title with spaces intact; passing it as argv entries
  // turned "The Last of Us" into title=The (and the player rightly refused it).
  if (t.entry.kind === 'screen' || t.entry.builder === 'player.preview') {
    adb(['shell',
      `am start -n ${PLAYER} --ez preview true --es previewState ${t.state} --es theme ${t.theme} ` +
      `--es title 'The Last of Us'`]);
  } else {
    adb(['shell',
      `am start -n ${STUDIO} --ez capture true --es entry ${t.entry.id} --es state ${t.state} ` +
      `--es theme ${t.theme}`]);
  }
}

function ink(file) {
  const out = sh(PY, [path.join(HERE, 'shot.py'), 'ink', file]).toString().trim();
  return Number(out) || 0;
}

function capture(t) {
  const shotPath = path.join(TMP, `${t.entry.id}.${t.state}.${t.theme}.png`);
  launch(t);
  // A cold start on the BRAVIA can take several seconds. A fixed sleep captured the moment before
  // the chrome existed as often as the chrome itself, so wait for ink and re-take while the frame is
  // still blank. A dark player still carries a title and controls; under 0.5% ink is an empty frame.
  const base = t.entry.builder === 'sheet.action' || t.entry.kind === 'screen'
    || t.entry.builder === 'player.preview' ? 5600 : 3400;
  sleep(base);
  let png = adb(['exec-out', 'screencap', '-p']);
  fs.writeFileSync(shotPath, png);
  // A frame still fading in (window transition, TV wake) is dimmer than the one after it. Wait for
  // two consecutive frames to agree; that is the stable state a golden should record.
  for (let attempt = 0; attempt < 5; attempt++) {
    const next = path.join(TMP, 'stable-' + path.basename(shotPath));
    sleep(800);
    fs.writeFileSync(next, adb(['exec-out', 'screencap', '-p']));
    let stable = false;
    try {
      execFileSync(PY, [path.join(HERE, 'shot.py'), 'same', shotPath, next], { stdio: 'ignore' });
      stable = true;
    } catch (e) { stable = false; }
    fs.copyFileSync(next, shotPath);
    if (stable && ink(shotPath) >= 0.5) break;
    sleep(900);
  }
  return shotPath;
}

function normalise(src, dst) {
  execFileSync(PY, [path.join(HERE, 'shot.py'), 'normalize', src, dst], { stdio: 'inherit' });
}

function diff(a, b) {
  try {
    execFileSync(PY, [path.join(HERE, 'shot.py'), 'diff', a, b], { stdio: 'inherit' });
    return true;
  } catch (e) {
    return false;
  }
}

// The goldens are a 1280x720 canvas. A television that renders at 1080p and is then scaled down does
// not produce the same frame as one that renders at 720p: glyph rasterisation and every hairline move,
// which tripped all 53 goldens at once. Ask the device for the canvas the goldens were made at, and put
// its own setting back when the run is over.
const CANVAS = '1280x720';
function pinCanvas() {
  try {
    adb(['shell', 'wm', 'size', CANVAS]);
    console.log(`canvas pinned to ${CANVAS} (the golden size)`);
  } catch (e) { console.log('could not pin the capture canvas:', e.message); }
}
function releaseCanvas() {
  try { adb(['shell', 'wm', 'size', 'reset']); } catch (e) { /* the TV keeps its own setting */ }
}

let failed = 0;
let written = 0;
pinCanvas();
try {
  for (const t of targets) {
    const shot = capture(t);
    const golden = goldenPath(t);
    const label = `${t.entry.id.padEnd(18)} ${t.state.padEnd(10)} ${t.theme}`;
    const normal = path.join(TMP, 'norm-' + path.basename(shot));
    normalise(shot, normal);
    if (write) {
      fs.mkdirSync(path.dirname(golden), { recursive: true });
      fs.copyFileSync(normal, golden);
      written++;
      console.log(`write  ${label}`);
      continue;
    }
    if (!fs.existsSync(golden)) {
      failed++;
      if (strict) console.log(`missing ${label}`);
      continue;
    }
    process.stdout.write(`diff   ${label}  `);
    if (!diff(golden, normal)) {
      failed++;
      console.log(`  (changed; new capture at ${shot})`);
    }
  }
} finally {
  releaseCanvas();
}

adb(['shell', 'am', 'force-stop', 'com.scenix.tv']);
if (write) {
  console.log(`\nwrote ${written} goldens`);
} else {
  console.log(`\n${targets.length} captures · ${failed} missing or changed`);
  if (strict && failed) process.exit(1);
}
