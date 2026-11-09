#!/usr/bin/env node
/*
 * Native Settings has a deliberately dense safety contract: a visual redesign must not turn the
 * persistent TV area rail into a focus trap, or hide the only Hub/standalone entry point behind
 * an adult/household gate.  This small source gate is dependency-free so it runs in release QA
 * alongside the Kotlin compile.
 */

import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const source = await readFile(
  new URL('../app/src/main/java/com/scenix/tv/SettingsActivity.kt', import.meta.url),
  'utf8',
);

assert.match(
  source,
  /paintControlHeader\(p\)\s*\n\s*paintSectionRail\(\)/,
  'every rendered settings area must expose its compact state strip and area rail',
);
assert.match(
  source,
  /private lateinit var railHost: FrameLayout[\s\S]*?page\.addView\(railHost[\s\S]*?railHost\.addView\(\s*rail/,
  'the section rail must remain outside the scrolling controls',
);
assert.match(
  source,
  /private fun paintControlHeader\(p: JSONObject\)[\s\S]*?minimumHeight = app\.theme\.px\(54f\)/,
  'the operational context must stay compact rather than becoming a duplicate summary panel',
);
assert.match(
  source,
  /private fun paintSectionRail\(\)[\s\S]*?registerControl\("section:\$\{section\.name\}", chip\)[\s\S]*?nextFocusRightId/,
  'the persistent area rail must use named controls and explicit horizontal remote routing',
);
assert.match(
  source,
  /private fun wireControlCentreFocus\(\)[\s\S]*?tab\.nextFocusDownId = first\.id[\s\S]*?entry\.nextFocusUpId = tab\.id/,
  'the active area chip and first control must form a reversible Up/Down remote route',
);
assert.match(
  source,
  /private fun addAreaGrid\(areas: List<ControlArea>\)[\s\S]*?card\.nextFocusLeftId[\s\S]*?card\.nextFocusRightId[\s\S]*?card\.nextFocusDownId/,
  'overview cards must have explicit two-column D-pad links rather than relying on geometry',
);
assert.match(
  source,
  /private fun controlRow\([\s\S]*?Screens\.rowFill\(app\)[\s\S]*?Focus\.attach\([\s\S]*?scale = false[\s\S]*?control = true/,
  'detail controls must retain shared theme surfaces and an inexpensive, visible focus treatment',
);
assert.match(
  source,
  /pendingGridDown[\s\S]*?card\.nextFocusDownId = row\.id/,
  'a recovery row below the overview grid must remain reachable by pressing Down',
);
assert.match(
  source,
  /contentDescription = listOf\(area\.label, area\.value, area\.note\)[\s\S]*?joinToString\("\. "\)/,
  'a control-centre card must announce its label, selected value and explanation together',
);
assert.match(
  source,
  /if \(isTrustedAdult\(\) && householdSettingsReady\) \{\s*addRow\("Hub connection", backendSummary\(\)/,
  'an authorized adult must retain the visible Hub connection affordance',
);
assert.match(
  source,
  /Intent\(this, MainActivity::class\.java\)\.putExtra\("setup", true\)/,
  'Hub connection must retain the existing setup route',
);
assert.match(
  source,
  /Hub connection is protected[\s\S]*?standalone mode/,
  'a child profile must receive an explanation instead of a deceptive, dead Hub control',
);
assert.match(
  source,
  /Local files need standalone mode/,
  'Hub mode must visibly explain why the local-files control is unavailable',
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
