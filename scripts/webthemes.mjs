#!/usr/bin/env node
/*
 * scripts/webthemes.mjs — the web client draws the television's packs, from the television's file.
 *
 * The two clients used to carry their own copy of every theme, and the copies drifted until the same pack
 * name meant two different interfaces: the TV's Scene pack shows 16:9 rails with names under the art while
 * the browser showed tall posters, the TV's PS5 pack opens on a hero rail the browser had never heard of.
 * One owner fixes that — `app/src/main/assets/theme*.json` is what the TV runs, so the web assets are
 * generated from them, key for key, with the exceptions below written out instead of remembered.
 *
 *   node scripts/webthemes.mjs            regenerate hub/web/assets/theme*.json + themes.json
 *   node scripts/webthemes.mjs --check    fail (exit 1) if the web assets have drifted
 *
 * The one deliberate exception is the autoplay budget. `preview.enabled` is off on the Glass and Kids packs
 * because a 2 GB television panel cannot churn a trailer under a card wall; the browser is not that panel,
 * and the trailer preview is part of what the web hero does. So that one key keeps the web's value, and
 * nothing else does.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SRC = path.join(ROOT, 'app', 'src', 'main', 'assets');
const DEST = path.join(ROOT, 'hub', 'web', 'assets');

/** Keys a client is allowed to hold differently, and what the browser's answer is. */
const WEB_OVERLAY = {
  preview: { enabled: 1 },
};

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

/**
 * One line of explanation, so a regenerated file still says where it came from. `_read` is the block a
 * person reads first in either asset tree; `_from` says this one was generated, and by what.
 */
function stamp(tokens, sourceName) {
  const out = { ...tokens };
  out._from = `generated from app/src/main/assets/${sourceName} by scripts/webthemes.mjs — edit the TV's file`;
  return out;
}

function build() {
  const catalog = readJson(path.join(SRC, 'themes.json'));
  const jobs = (catalog.themes || []).map((entry) => {
    const src = readJson(path.join(SRC, path.basename(String(entry.asset))));
    const tokens = { ...src };
    for (const [group, keys] of Object.entries(WEB_OVERLAY)) {
      if (!tokens[group]) continue;
      tokens[group] = { ...tokens[group], ...keys };
    }
    return { entry, tokens };
  });
  return { catalog, jobs };
}

function main() {
  const check = process.argv.includes('--check');
  const { catalog, jobs } = build();
  const drift = [];
  fs.mkdirSync(DEST, { recursive: true });

  for (const { entry, tokens } of jobs) {
    const asset = path.basename(String(entry.asset));
    const file = path.join(DEST, asset);
    const wanted = `${JSON.stringify(stamp(tokens, asset), null, 2)}\n`;
    const have = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
    if (have !== wanted) {
      drift.push(asset);
      if (!check) fs.writeFileSync(file, wanted);
    }
    // The fallback file the shell loads before the hub answers is the catalog's default pack. The hub's
    // shipped default is "Clean"; the TV's own base pack is `theme.json`, so that stays the fallback.
  }
  const catalogFile = path.join(DEST, 'themes.json');
  const wantedCatalog = `${JSON.stringify(catalog, null, 2)}\n`;
  if (fs.readFileSync(catalogFile, 'utf8') !== wantedCatalog) {
    drift.push('themes.json');
    if (!check) fs.writeFileSync(catalogFile, wantedCatalog);
  }

  const names = jobs.map((j) => path.basename(String(j.entry.asset)));
  if (check) {
    if (drift.length) {
      console.error(`web themes drifted from the TV's: ${drift.join(', ')}`);
      console.error('run: node scripts/webthemes.mjs');
      process.exit(1);
    }
    console.log(`web themes match the TV's: ${names.length} packs`);
    return;
  }
  console.log(`wrote ${names.length} packs to hub/web/assets: ${names.join(', ')}`);
  if (drift.length) console.log(`changed: ${drift.join(', ')}`);
}

main();
