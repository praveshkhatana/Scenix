'use strict';
/*
 * Sources is a household control, not a destination in everyday navigation. The only door is
 * Settings → Access & safety, where the household Settings PIN is asked before the directory is
 * drawn; the TV's left rail must not offer a way around that gate.
 */

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const read = (relative) => fs.readFileSync(path.join(root, relative), 'utf8');
const browse = read('app/src/main/java/com/scenix/tv/BrowseActivity.kt');
const sources = read('app/src/main/java/com/scenix/tv/AddonsActivity.kt');
const settings = read('app/src/main/java/com/scenix/tv/SettingsActivity.kt');

// The left rail is everyday navigation. A Sources entry there bypassed the Settings PIN for every
// adult profile, which is exactly the bypass this destination must not have.
assert.doesNotMatch(
  browse,
  /LeftRail\.Entry\("Sources"/,
  'Sources must not sit in the left rail: it is a household control reached through Settings',
);

// Settings owns the entry, and it lives inside Access & safety next to the lock it depends on.
assert.match(
  settings,
  /private fun paintSources\(\)[\s\S]*?addRow\("Sources",/,
  'the Settings rail must carry the only visible Sources entry, in its own PIN-gated area',
);
assert.match(
  settings,
  /private fun paintSources\(\)[\s\S]*?requireSources \{ paint\(\) \}/,
  'opening Sources must prove (or create) the household Settings PIN first',
);
assert.match(
  settings,
  /if \(isSourcesUnlocked\(\)\) \{\s*addSourcesRows\(\)/,
  'the Sources rows must appear only after the household Settings PIN has been proved',
);
assert.match(
  settings,
  /private fun requireSources\(onUnlocked: \(\) -> Unit\) \{[\s\S]*?showPinPad\("PIN required for Sources"/,
  'a stored PIN must be asked, and a hub with no PIN must create one before Sources opens',
);
assert.match(
  settings,
  /Intent\(this, AddonsActivity::class\.java\)\s*\.putExtra\(AddonsActivity\.EXTRA_PROFILE, profileId\)\s*\/\/[^\n]*\n\s*\.putExtra\(AddonsActivity\.EXTRA_SETTINGS_PROVED, true\)/,
  'the Settings route must carry the profile context and the fact that the PIN was just proved',
);
assert.match(
  sources,
  /const val EXTRA_PROFILE = "profile"/,
  'the source route must require an explicit profile id',
);
assert.match(
  sources,
  /profileId\.isBlank\(\)[\s\S]*?finish\(\)/,
  'a missing profile must fail closed before source data is rendered',
);
assert.match(
  sources,
  /private fun verifyTrustedAdultProfile\(\)[\s\S]*?api\.profiles \{ profiles, _ ->[\s\S]*?profile == null \|\| profile\.kids[\s\S]*?finish\(\)/,
  'the source route must positively identify an adult profile and fail closed for children or unknown ids',
);

// Defence in depth: the Activity asks the lock itself, so a stale intent or a future entry point
// cannot render the household directory without the number.
assert.match(
  sources,
  /private fun requireSettingsGate\(\)[\s\S]*?val enabled = lock\?\.optBoolean\("enabled"\) == true[\s\S]*?adminSessionFor\(HubEndpoint\.base\(this\)\)[\s\S]*?askSettingsPin\(\)/,
  'AddonsActivity must prove the Settings lock at entry, not only rely on the caller',
);
assert.match(
  sources,
  /private fun askSettingsPin\(\)[\s\S]*?TextEntryActivity\.EXTRA_NUMERIC_PIN, true[\s\S]*?REQ_SETTINGS_GATE/,
  'the entry gate must use the same numeric PIN entry as every other household prompt',
);
assert.match(
  sources,
  /requestCode == REQ_SETTINGS_GATE[\s\S]*?api\.unlockSettings\(pin\)[\s\S]*?settingsGatePassed = true[\s\S]*?load\(\)/,
  'a correct PIN must be the only way past the entry gate',
);
assert.match(
  sources,
  /retryAfterSettingsUnlock/,
  'direct browsing must retain the existing Settings-admin/PIN mutation recovery path',
);

console.log('SOURCES ENTRYPOINT GATE OK');
