#!/usr/bin/env node
/*
 * A first visit to Home without a selected profile mounts the picker beneath the
 * splash.  The original Home route remains in a local variable while that
 * happens, so falling through to the generic no-profile fallback mounted a
 * second picker after the splash faded. Keep this hand-off singular without
 * weakening deep-link and explicit-profile handling.
 */
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const source = await readFile(new URL('../hub/web/js/app.js', import.meta.url), 'utf8');
const start = source.indexOf('async function boot()');
const end = source.indexOf('\nboot().catch(', start);
assert.ok(start >= 0 && end > start, 'boot function must be present');
const boot = source.slice(start, end);

assert.match(boot,
  /const needsProfilePicker = gated && !state\.session\.profile && !wanted;/,
  'boot must name the first-visit profile-picker path once');
assert.match(boot,
  /if \(needsProfilePicker\) await navigate\('profiles'\);[\s\S]*?await hideBoot\(\);[\s\S]*?if \(needsProfilePicker\) return;/,
  'the splash-mounted picker must return before the generic no-profile fallback');

const firstVisit = boot.slice(boot.indexOf('const needsProfilePicker'), boot.indexOf('if (wanted'));
assert.equal((firstVisit.match(/navigate\('profiles'\)/g) || []).length, 1,
  'the first-visit boot path must mount Profiles exactly once');
assert.match(boot,
  /if \(wanted && !state\.session\.profile\)/,
  'explicit profile deep links must remain after the first-visit guard');
assert.match(boot,
  /if \(!gated && !state\.session\.profile\)/,
  'ungated deep links must retain their profile-unlock fallback');

process.stdout.write('PROFILE BOOT GATE STATIC CHECK OK\n');
