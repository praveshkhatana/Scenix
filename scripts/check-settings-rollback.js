'use strict';
/*
 * Small no-browser regression tripwire for the Settings write contract.
 *
 * The remote controls are DOM code, but the safety property is structural: a failed protected
 * write has to return false and rehydrate the page, while a browser theme refresh can only follow
 * a true acknowledgement. Keeping this check dependency-free makes it runnable with the other
 * release boundary checks.
 */

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const file = path.join(__dirname, '..', 'hub', 'web', 'js', 'screens', 'settings.js');
const source = fs.readFileSync(file, 'utf8');

function requirePattern(pattern, explanation) {
  assert.match(source, pattern, explanation);
}

requirePattern(
  /themeChoices\s*=\s*Array\.isArray\(s\s*&&\s*s\.themes\)\s*\?\s*s\.themes\s*:\s*\[\]/,
  'the browser Settings screen must consume the Hub theme projection',
);
requirePattern(
  /async function writeSettings\(patch\)[\s\S]*?return true;[\s\S]*?ctx\.reload\(\);[\s\S]*?return false;/,
  'Settings writes must expose acknowledgement and rehydrate after refusal',
);
requirePattern(
  /if\s*\(await writeSettings\(\{ theme: id \}\)\)\s*window\.location\.reload\(\);/,
  'a browser theme refresh must follow a confirmed Hub write only',
);
assert.doesNotMatch(
  source,
  /writeSettings\(\{ theme: id \}\)\.then\(\(\)\s*=>\s*window\.location\.reload\(\)\)/,
  'an unconfirmed theme write must never force a page reload',
);

function requireGuardBefore(region, guard, mutation, explanation) {
  const body = source.match(region);
  assert(body, explanation + ' (handler not found)');
  const guarded = body[0].indexOf(guard);
  const mutates = body[0].indexOf(mutation);
  assert(guarded >= 0 && mutates >= 0 && guarded < mutates, explanation);
}

requireGuardBefore(
  /const commit = \(\) => \{[\s\S]*?write\(values\[i\]\);\n\s*\};/,
  'if (busy) { ctx.closeModals(); return; }', 'setLv(b, show(values[i]))',
  'a busy picker must stop before painting its local value',
);
requireGuardBefore(
  /const s = el\('button', \{ class: 'btn white',[\s\S]*?write\(chosen\.slice\(\)\);\n\s*\};/,
  'if (busy) { ctx.closeModals(); return; }', 'selected.length = 0',
  'a busy checklist must stop before changing its selected values',
);
requireGuardBefore(
  /b\.onclick = async \(\) => \{[\s\S]*?await write\(next\);\n\s*\};\n\s*parent\.appendChild\(b\);/,
  'if (busy) return;', "sw.classList.toggle('on', next)",
  'a busy switch must stop before changing its local visual state',
);

requirePattern(
  /api\.setAddon\(ad\.id, !on\)[\s\S]*?installed = \(installed \|\| \[\]\)\.map\(\(item\) => item\.id === ad\.id/,
  'an add-on toggle must update the rendered source state from the Hub acknowledgement',
);

const nativeSettings = fs.readFileSync(
  path.join(__dirname, '..', 'app', 'src', 'main', 'java', 'com', 'scenix', 'tv', 'SettingsActivity.kt'),
  'utf8',
);
assert.match(nativeSettings, /private var profileKind = "unknown"/,
  'a profile whose lookup fails must start with least privilege');
assert.match(nativeSettings,
  /profileKind = if \(profile != null && !profile\.kids\) "adult" else "kids"/,
  'only a positively identified adult may receive household routes');
assert.match(nativeSettings, /private fun isTrustedAdult\(\): Boolean = profileKind == "adult"/,
  'native Settings must centralize the adult capability check');
assert.match(nativeSettings, /private var householdSettingsReady = false/,
  'a missing household settings payload must start closed');
assert.match(nativeSettings, /lockInfo = null\n\s*householdSettingsReady = false/,
  'a refresh must discard stale lock state before loading a new profile/settings snapshot');
assert.match(nativeSettings, /if \(!householdSettingsReady\) \{\n\s*status\.text = "Household controls are unavailable — try again"/,
  'protected native actions must refuse while household settings are unconfirmed');
assert.match(nativeSettings,
  /KeyEvent\.KEYCODE_DPAD_LEFT, KeyEvent\.KEYCODE_DPAD_RIGHT -> return true/,
  'a one-column picker must consume horizontal remote input instead of leaking focus behind its modal');

const nativeScreens = fs.readFileSync(
  path.join(__dirname, '..', 'app', 'src', 'main', 'java', 'com', 'scenix', 'tv', 'core', 'Screens.kt'),
  'utf8',
);
assert.match(nativeScreens,
  /contentDescription = listOf\(label, value, note\)[\s\S]*?joinToString\("\. "\)/,
  'a settings row must expose its label, selected value and explanation as one accessible control',
);

console.log('SETTINGS ROLLBACK STATIC CHECK OK');
