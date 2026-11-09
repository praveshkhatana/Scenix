#!/usr/bin/env node
/*
 * The glow theme hands D-pad focus to haloRow's outer canvas.  Custom profile rows must forward
 * their action to that canvas; otherwise a row can look focused while OK silently does nothing.
 */

import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const source = await readFile(
  new URL('../app/src/main/java/com/scenix/tv/ManageProfilesActivity.kt', import.meta.url),
  'utf8',
);

assert.match(
  source,
  /Screens\.haloRow\(this, app, row, Screens\.listRadius\(app\)\)\s*\{\s*showProfileActions\(p\)\s*\}/,
  'the glow-profile focus canvas must preserve the profile editor action',
);

console.log('PROFILE EDITOR FOCUS GATE OK');
