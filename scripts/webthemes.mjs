#!/usr/bin/env node
/*
 * scripts/webthemes.mjs — the web client draws the television's packs, from the television's file.
 *
 * The two clients used to carry their own copy of every theme, and the copies drifted until the same pack
 * name meant two different interfaces: the TV's Scene pack shows 16:9 rails with names under the art while
 * the browser showed tall posters, the TV's PS5 pack opens on a hero rail the browser had never heard of.
 * One owner fixes that — `app/src/main/assets/theme*.json` is what the TV runs, so the web assets are
 * generated from them byte for byte. Theme assets carry layout and performance policy as well as
 * colors, so a browser-side overlay would make the same named pack mean a different experience.
 *
 *   node scripts/webthemes.mjs            regenerate hub/web/assets/theme*.json + themes.json
 *   node scripts/webthemes.mjs --check    fail (exit 1) if the web assets have drifted
 *
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SRC = path.join(ROOT, 'app', 'src', 'main', 'assets');
const DEST = path.join(ROOT, 'hub', 'web', 'assets');

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function build() {
  const catalog = readJson(path.join(SRC, 'themes.json'));
  const jobs = (catalog.themes || []).map((entry) => {
    const asset = path.basename(String(entry.asset));
    if (!asset.endsWith('.json') || asset !== String(entry.asset)) {
      throw new Error(`invalid theme asset: ${entry.asset}`);
    }
    const bytes = fs.readFileSync(path.join(SRC, asset));
    // Parse once so an accidentally malformed TV asset cannot be copied into the web bundle.
    JSON.parse(bytes.toString('utf8'));
    return { asset, bytes };
  });
  return { catalogBytes: fs.readFileSync(path.join(SRC, 'themes.json')), jobs };
}

function main() {
  const check = process.argv.includes('--check');
  const { catalogBytes, jobs } = build();
  const drift = [];
  fs.mkdirSync(DEST, { recursive: true });

  for (const { asset, bytes } of jobs) {
    const file = path.join(DEST, asset);
    const have = fs.existsSync(file) ? fs.readFileSync(file) : null;
    if (!have || !have.equals(bytes)) {
      drift.push(asset);
      if (!check) fs.writeFileSync(file, bytes);
    }
    // The fallback file the shell loads before the hub answers is the catalog's default pack. The hub's
    // shipped default is "Clean"; the TV's own base pack is `theme.json`, so that stays the fallback.
  }
  const catalogFile = path.join(DEST, 'themes.json');
  const haveCatalog = fs.existsSync(catalogFile) ? fs.readFileSync(catalogFile) : null;
  if (!haveCatalog || !haveCatalog.equals(catalogBytes)) {
    drift.push('themes.json');
    if (!check) fs.writeFileSync(catalogFile, catalogBytes);
  }

  const names = jobs.map((j) => j.asset);
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
