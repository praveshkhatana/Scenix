'use strict';
/*
 * No-browser tripwire for the reviewed source directory. The Hub owns the catalog and install policy;
 * this check keeps the browser from collapsing that safe path into a manual URL form or a one-click
 * install as the settings screen evolves.
 */

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const screen = fs.readFileSync(path.join(root, 'hub', 'web', 'js', 'screens', 'settings.js'), 'utf8');
const css = fs.readFileSync(path.join(root, 'hub', 'web', 'css', 'screens.css'), 'utf8');

assert.match(screen, /reviewedDirectory && Array\.isArray\(reviewedDirectory\.entries\)/,
  'the browser must use the richer Hub directory projection when it is available');
assert.match(screen, /: reviewed;/,
  'the browser must retain a legacy reviewed[] fallback');
assert.match(screen, /function sourceDirectoryMatches\(source\)/,
  'the directory must filter its locally supplied, safe projection');
assert.match(screen, /const SOURCE_DIRECTORY_PAGE_SIZE = 12;/,
  'large source directories must have a bounded initial render budget');
assert.match(screen, /function renderSourceDirectoryPage\(\{ lane, label, results, list, count, emptyText, makeCard \}\)/,
  'reviewed and community directories must share the bounded renderer');
assert.match(screen, /const visible = results\.slice\(0, shown\);/,
  'the bounded renderer must create cards only for the visible page');
assert.match(screen, /lane: 'curated', label: 'reviewed', results, list, count: resultCount/,
  'reviewed results must use the bounded renderer');
assert.match(screen, /resetSourceDirectoryPaging\('curated'\)/,
  'reviewed search and filters must reset the visible result page');
assert.match(screen, /Search reviewed sources/,
  'the directory must expose a searchable browser/remote input');
assert.match(screen, /Content type/,
  'the directory must expose a capability filter');
assert.match(screen, /function openReviewedSourceDetail\(source, returnFocus\)/,
  'a reviewed source must open details before installation');
assert.match(screen, /function sourceDirectoryInstallUnavailable\(source\)[\s\S]*?source\.install\.available === false/,
  'the directory must recognise a Hub-declared unavailable install');
assert.match(screen,
  /else if \(installUnavailable\) \{[\s\S]*?sourceSetupAvailable\(source\)[\s\S]*?Start secure browser setup/,
  'a configuration-required source may expose only the opaque browser-code handoff');
assert.match(screen, /This source needs browser setup\. Start a short-lived setup code/,
  'a configuration-required source must explain the safe handoff boundary');
assert.match(screen, /api\.startReviewedSourceSetup\(source\.id\)/,
  'reviewed setup must begin with a registry id, not a source URL');
assert.match(screen, /text: 'Review install'/,
  'the first install affordance must be a review step');
assert.match(screen, /text: installing \? 'Installing…' : 'Install source'/,
  'the final install affordance must name the operation');
assert.ok(screen.indexOf("text: 'Review install'") < screen.indexOf('api.installReviewedAddon(source.id)'),
  'the install request must remain behind the review step');
assert.match(screen, /api\.installReviewedAddon\(source\.id\)/,
  'reviewed installation must send only the registry id to the Hub');
assert.doesNotMatch(screen, /source\.(?:url|manifest)\b/,
  'the source directory must not expose or send a source manifest URL');
assert.match(screen, /group\('Advanced manual source'/,
  'manual URLs must stay in a separately labelled advanced group');
assert.match(screen, /api\.addAddon\(url\)/,
  'the advanced manual flow must retain its existing Hub API path');
assert.match(css, /\.source-directory-card \{/,
  'reviewed entries need a dedicated, theme-aware card treatment');
assert.match(css, /\.mbox\.source-detail \{/,
  'source details need a stable modal layout');
assert.match(css, /var\(--surface2\)/,
  'the directory must use the shared theme surface tokens');

console.log('WEB REVIEWED SOURCE DIRECTORY GATE OK');
