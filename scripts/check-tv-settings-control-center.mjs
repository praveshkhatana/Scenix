#!/usr/bin/env node
/*
 * Native Settings has a deliberately dense safety contract: a visual redesign must not turn the
 * phone QR into a focus trap, or hide the only Hub/standalone entry point behind an adult/household
 * gate.  This small source gate is dependency-free so it runs in release QA alongside the Kotlin
 * compile.
 */

import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const source = await readFile(
  new URL('../app/src/main/java/com/scenix/tv/SettingsActivity.kt', import.meta.url),
  'utf8',
);

assert.match(
  source,
  /paintSafeGroups\(p\)\s*\n\s*paintGuardedGroups\(\)/,
  'every render must paint the safe groups first and the guarded shelf last',
);
assert.match(
  source,
  /body\.addView\(contentScroll[\s\S]*?phoneRail = buildPhoneRail\(\)[\s\S]*?body\.addView\(phoneRail/,
  'the phone rail must remain outside the scrolling controls',
);
assert.match(
  source,
  /private fun paintSafeGroups\(p: JSONObject\) \{[\s\S]*?paintAppearance\(\)[\s\S]*?paintPlayback\(p\)[\s\S]*?paintPeople\(\)[\s\S]*?paintThisTv\(\)/,
  'the safe half must be appearance, playback, people and this TV, in that order',
);
assert.match(
  source,
  /private fun paintAppearance\(\) \{[\s\S]*?addGroup\("Appearance"\)[\s\S]*?addRow\("Theme"/,
  'Appearance must be a visible group with Theme in it',
);
assert.doesNotMatch(
  source,
  /showPickTheme|requireAdmin \{ pickTheme\(\) \}/,
  'Appearance must not ask for the household PIN',
);
assert.match(
  source,
  /private fun paintGuardedGroups\(\) \{[\s\S]*?addGroup\("Household & sources"\)[\s\S]*?if \(!isSourcesUnlocked\(\)\)[\s\S]*?requireSources \{ paint\(\) \}/,
  'the guarded shelf must be one locked row until its PIN is proved',
);
assert.match(
  source,
  /private fun addRow\([\s\S]*?firstRow == null[\s\S]*?registerControl\("row:\$label", row\)/,
  'each row must be a named remote control and the first row must be the landing stop',
);
assert.match(
  source,
  /private fun controlRow\([\s\S]*?Screens\.rowFill\(app\)[\s\S]*?Focus\.attach\([\s\S]*?scale = false[\s\S]*?control = true/,
  'detail controls must retain shared theme surfaces and an inexpensive, visible focus treatment',
);
assert.match(
  source,
  /contentDescription = listOf\(label, value, note\)[\s\S]*?joinToString\("\. "\)/,
  'a row must announce its label, value and explanation together',
);
assert.match(
  source,
  /addRow\("Hub connection", backendSummary\(\)/,
  'an authorized adult must retain the visible Hub connection affordance',
);
assert.match(
  source,
  /Intent\(this, MainActivity::class\.java\)\.putExtra\("setup", true\)/,
  'Hub connection must retain the existing setup route',
);
assert.match(
  source,
  /if \(app\.prefs\.backendChoice\(\) == Prefs\.BACKEND_STANDALONE\)[\s\S]*?addRow\("Local files"/,
  'Local files must appear only in standalone mode, where the TV really has the files',
);
assert.match(
  source,
  /private fun load\(\)[\s\S]*?api\.prefs\(profileId\)[\s\S]*?api\.profiles[\s\S]*?api\.settings[\s\S]*?private var enteredOnce/s,
  'settings refresh must fan out the independent prefs, profile, and household reads',
);
assert.match(
  source,
  /settingsRefreshVersion[\s\S]*?deferredFocusKey[\s\S]*?deferredScrollY/,
  'a background refresh must retain a versioned focus and viewport snapshot',
);
assert.match(
  source,
  /deferredFocusKey \?: focusedControlKey\(\)[\s\S]*?contentScroll\.scrollTo\(0, keepScrollY\.coerceAtLeast\(0\)\)/,
  'the accepted refresh must restore the prior remote stop and scroll position',
);
assert.match(
  source,
  /private fun requireAdmin\(block: \(\) -> Unit\) \{[\s\S]*?if \(householdRefreshPending\)/,
  'preserved settings controls must stay non-authoritative while household access refreshes',
);
assert.match(
  source,
  /private fun changeLock\(\) \{[\s\S]*?if \(householdRefreshPending\)/,
  'the standalone lock-change path must also wait for a fresh household snapshot',
);

console.log('TV SETTINGS CONTROL-CENTRE STATIC CHECK OK');
