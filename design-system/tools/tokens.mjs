#!/usr/bin/env node
'use strict';
/*
 * design-system/tools/tokens.mjs — one token source, checked against every platform.
 *
 * The television's theme assets are what it runs; the design system's canonical copies are what the
 * other platforms generate from. Neither may drift: `check` compares the token groups in
 * app/src/main/assets/theme*.json with design-system/tokens/themes/*.json and fails on any
 * difference, so a colour changed in one place cannot silently miss the others.
 *
 *   node design-system/tools/tokens.mjs extract   # app assets -> canonical (the one direction a change goes)
 *   node design-system/tools/tokens.mjs check     # canonical vs the Android app, exit 1 on drift
 *   node design-system/tools/tokens.mjs list      # what the canonical set holds
 *
 * Token groups are renamed to the portable vocabulary:
 *   colors -> color, spacing -> space, radii -> radius, everything else keeps its name.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.join(HERE, '..', '..');
const ASSETS = path.join(ROOT, 'app', 'src', 'main', 'assets');
const OUT = path.join(HERE, '..', 'tokens');

const GROUPS = [
  ['colors', 'color'],
  ['spacing', 'space'],
  ['radii', 'radius'],
  ['type', 'type'],
  ['focus', 'focus'],
  ['anim', 'anim'],
  ['player', 'player'],
  ['pack', 'pack'],
];

function catalog() {
  const j = JSON.parse(fs.readFileSync(path.join(ASSETS, 'themes.json'), 'utf8'));
  return j.themes || [];
}

function extractOne(entry) {
  const file = path.join(ASSETS, entry.asset);
  const src = JSON.parse(fs.readFileSync(file, 'utf8'));
  const tokens = {};
  for (const [from, to] of GROUPS) {
    if (src[from] && typeof src[from] === 'object') tokens[to] = src[from];
  }
  return {
    id: entry.id,
    name: entry.name,
    pack: entry.pack || null,
    asset: entry.asset,
    version: 1,
    tokens,
  };
}

function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(value, null, 2) + '\n');
}

function extract() {
  const themes = catalog().map(extractOne);
  writeJson(path.join(OUT, 'index.json'), {
    version: 1,
    source: 'app/src/main/assets/themes.json',
    themes: themes.map((t) => ({ id: t.id, name: t.name, file: t.id + '.json' })),
  });
  for (const t of themes) writeJson(path.join(OUT, 'themes', t.id + '.json'), t);
  console.log(`extracted ${themes.length} themes to design-system/tokens/themes/`);
}

function canonical() {
  const index = JSON.parse(fs.readFileSync(path.join(OUT, 'index.json'), 'utf8'));
  return index.themes.map((t) => JSON.parse(fs.readFileSync(path.join(OUT, 'themes', t.file), 'utf8')));
}

function check() {
  let failed = 0;
  const want = new Map(canonical().map((t) => [t.id, t]));
  for (const entry of catalog()) {
    const app = extractOne(entry);
    const ds = want.get(entry.id);
    if (!ds) {
      console.error(`MISSING  ${entry.id} is in the app but not canonical — run extract`);
      failed++;
      continue;
    }
    for (const [, group] of GROUPS) {
      const a = JSON.stringify(app.tokens[group] || {});
      const b = JSON.stringify(ds.tokens[group] || {});
      if (a !== b) {
        console.error(`DRIFT    ${entry.id} · ${group}`);
        failed++;
      }
    }
    if (app.name !== ds.name) {
      console.error(`DRIFT    ${entry.id} · name ${JSON.stringify(ds.name)} -> ${JSON.stringify(app.name)}`);
      failed++;
    }
  }
  for (const id of want.keys()) {
    if (!catalog().some((e) => e.id === id)) {
      console.error(`MISSING  ${id} is canonical but not in the app`);
      failed++;
    }
  }
  if (failed) {
    console.error(`\n${failed} token drift(s). Change the canonical copy first, then extract/port.`);
    process.exit(1);
  }
  console.log(`token parity ok: ${want.size} themes`);
}

function list() {
  for (const t of canonical()) {
    const counts = Object.entries(t.tokens).map(([k, v]) => `${k} ${Object.keys(v).length}`).join(' · ');
    console.log(`${t.id.padEnd(18)} ${t.name.padEnd(10)} ${counts}`);
  }
}

const cmd = process.argv[2] || 'list';
if (cmd === 'extract') extract();
else if (cmd === 'check') check();
else if (cmd === 'list') list();
else {
  console.error('usage: tokens.mjs extract|check|list');
  process.exit(2);
}
