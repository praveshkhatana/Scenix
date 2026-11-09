'use strict';
/*
 * Sources is a first-class secondary navigation destination, but it must not turn into a child
 * profile bypass around the existing Hub-admin/PIN mutation policy.
 */

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const browse = fs.readFileSync(path.join(root, 'app', 'src', 'main', 'java', 'com', 'scenix', 'tv', 'BrowseActivity.kt'), 'utf8');
const sources = fs.readFileSync(path.join(root, 'app', 'src', 'main', 'java', 'com', 'scenix', 'tv', 'AddonsActivity.kt'), 'utf8');
const settings = fs.readFileSync(path.join(root, 'app', 'src', 'main', 'java', 'com', 'scenix', 'tv', 'SettingsActivity.kt'), 'utf8');

assert.match(
  browse,
  /LeftRail\.Entry\("Sources", R\.drawable\.ic_chip_globe, "sources"\)[\s\S]*?Intent\(this, AddonsActivity::class\.java\)[\s\S]*?putExtra\(AddonsActivity\.EXTRA_PROFILE, profileId\)/,
  'Sources must be directly reachable from the secondary rail with the selected profile context',
);
assert.ok(
  browse.indexOf('LeftRail.Entry("Sources"') < browse.indexOf('LeftRail.Entry("Settings"'),
  'Sources should be offered before generic Settings in the rail',
);
assert.doesNotMatch(
  browse,
  /navTab\("Sources"|navButton\("Sources"/,
  'Sources belongs in secondary navigation, not the compact playback top bar',
);
assert.match(sources, /const val EXTRA_PROFILE = "profile"/,
  'the source route must require an explicit profile id');
assert.match(sources, /profileId\.isBlank\(\)[\s\S]*?finish\(\)/,
  'a missing profile must fail closed before source data is rendered');
assert.match(sources, /private fun verifyTrustedAdultProfile\(\)[\s\S]*?api\.profiles \{ profiles, _ ->[\s\S]*?profile == null \|\| profile\.kids[\s\S]*?finish\(\)/,
  'the source route must positively identify an adult profile and fail closed for children or unknown ids');
assert.match(sources, /retryAfterSettingsUnlock/,
  'direct browsing must retain the existing Settings-admin/PIN mutation recovery path');
assert.match(
  settings,
  /Intent\(this, AddonsActivity::class\.java\)[\s\S]*?putExtra\(AddonsActivity\.EXTRA_PROFILE, profileId\)/,
  'the existing Settings route must pass the same required profile context',
);

console.log('SOURCES ENTRYPOINT GATE OK');
