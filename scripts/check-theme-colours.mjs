#!/usr/bin/env node
/*
 * Eight hex digits are not one colour. `#1AFFFFFF` is ten-percent white written the Android way and opaque
 * cyan read as CSS, nothing errors when the wrong one is parsed, and every border, chip and nav label in the
 * interface goes wrong at once - which is exactly what happened. So a token file spells transparency
 * `rgba(r,g,b,a)` and never eight-digit hex; the rule lives in design/ui-tokens.md.
 *
 * Enforce it here, statically, rather than by letting a screen go visibly wrong: a check that fails costs
 * nothing, while runtime tolerance is what keeps a pasted value from repainting the whole UI.
 *
 *   node scripts/check-theme-colours.mjs        exit 0 = clean, 1 = violations found
 */
import { readdirSync, readFileSync, lstatSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = process.argv[2] || new URL('..', import.meta.url).pathname;
const HEX8 = /#[0-9a-fA-F]{8}\b/g;

function themeFiles(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    if (entry === 'node_modules' || entry === 'build' || entry === '.git' || entry === 'data') continue;
    const full = join(dir, entry);
    // Never follow a link: a token directory once held `ui -> .`, which walks forever and changes nothing.
    const st = lstatSync(full);
    if (st.isSymbolicLink()) continue;
    if (st.isDirectory()) themeFiles(full, out);
    else if (entry === 'theme.json' || entry === 'tokens.json') out.push(full);
  }
  return out;
}

let bad = 0;
for (const file of themeFiles(ROOT)) {
  const lines = readFileSync(file, 'utf8').split('\n');
  lines.forEach((line, i) => {
    const hits = line.match(HEX8);
    if (!hits) return;
    bad += hits.length;
    console.log(`${relative(ROOT, file)}:${i + 1}: ${hits.join(' ')} - write rgba(r,g,b,a) instead`);
  });
}

if (bad) {
  console.log(`\n${bad} eight-digit colour${bad > 1 ? 's' : ''} in a token file. Both clients parse rgba();`
    + ' eight-digit hex means two different things depending on which side wrote it.');
  process.exit(1);
}
console.log('token colours are clean');
