#!/usr/bin/env node
'use strict';
/*
 * design-system/tools/coverage.mjs — the catalog is only a library if every contract has a real render.
 *
 * For every contract it finds the catalog entry that claims it, resolves the states and themes it
 * must cover, and checks the goldens exist:
 *
 *   design-system/goldens/android/<entry>/<state>/<theme>.png
 *
 *   node design-system/tools/coverage.mjs            # report
 *   node design-system/tools/coverage.mjs --strict   # exit 1 on blocked entries or missing goldens
 *
 * "blocked" is not a pass. It names a component still owned by an Activity instead of extracted, and
 * that is exactly the debt the Studio exists to retire.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.join(HERE, '..');
const GOLDENS = path.join(ROOT, 'goldens', 'android');
const studio = JSON.parse(fs.readFileSync(path.join(ROOT, 'studio.json'), 'utf8'));
const contractDir = path.join(ROOT, 'contracts');
const contracts = fs.readdirSync(contractDir)
  .filter((f) => f.endsWith('.json'))
  .map((f) => JSON.parse(fs.readFileSync(path.join(contractDir, f), 'utf8')));

const themesFor = (entry) => (Array.isArray(entry.themes) && entry.themes.length
  ? entry.themes : studio.themes);

const goldenPath = (entry, state, theme) => path.join(GOLDENS, entry.id, state, theme + '.png');

function main(strict) {
  const byContract = new Map();
  for (const entry of studio.entries) {
    if (!entry.contract) continue;
    const list = byContract.get(entry.contract) || [];
    list.push(entry);
    byContract.set(entry.contract, list);
  }

  let missingGoldens = 0;
  let blocked = 0;
  let uncontracted = 0;
  const rows = [];

  for (const contract of contracts) {
    const entries = byContract.get(contract.id) || [];
    if (!entries.length) {
      rows.push({ id: contract.id, status: 'NO CATALOG ENTRY', coverage: '0/0' });
      blocked++;
      continue;
    }
    for (const entry of entries) {
      if (String(entry.status || '').startsWith('blocked')) {
        blocked++;
        rows.push({ id: contract.id, status: 'BLOCKED', coverage: '0/' + (entry.states || contract.states || []).length });
        continue;
      }
      const states = entry.states || contract.states || [];
      const themes = themesFor(entry);
      const expected = states.length * themes.length;
      let have = 0;
      for (const state of states) {
        for (const theme of themes) {
          if (fs.existsSync(goldenPath(entry, state, theme))) have++;
          else missingGoldens++;
        }
      }
      rows.push({ id: contract.id, status: have === expected ? 'ok' : 'MISSING GOLDENS', coverage: `${have}/${expected}` });
    }
  }

  for (const entry of studio.entries) {
    if (!entry.contract) continue;
    if (!contracts.some((c) => c.id === entry.contract)) {
      uncontracted++;
      rows.push({ id: entry.contract, status: 'NO CONTRACT FILE', coverage: '—' });
    }
  }

  const width = Math.max(...rows.map((r) => r.id.length), 8);
  for (const r of rows) {
    const mark = r.status === 'ok' ? 'ok  ' : 'FAIL';
    console.log(`${mark} ${r.id.padEnd(width)} ${r.status.padEnd(22)} ${r.coverage}`);
  }
  console.log(`\n${rows.length} contracts · ${blocked} blocked · ${missingGoldens} goldens missing · ${uncontracted} entries without a contract`);

  if (strict && (blocked || missingGoldens)) {
    console.error('\nstrict: every contract needs a real catalog entry and a golden per state and theme.');
    process.exit(1);
  }
}

main(process.argv.includes('--strict'));
