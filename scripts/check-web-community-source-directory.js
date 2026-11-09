'use strict';
/*
 * Community-directory tripwire.  This is intentionally a separate trust lane from the curated
 * registry: the browser may render redacted descriptors, but it must never turn one into a URL
 * field or silently acknowledge a non-endorsed source.
 */

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const api = fs.readFileSync(path.join(root, 'hub', 'web', 'js', 'api.js'), 'utf8');
const screen = fs.readFileSync(path.join(root, 'hub', 'web', 'js', 'screens', 'settings.js'), 'utf8');
const css = fs.readFileSync(path.join(root, 'hub', 'web', 'css', 'screens.css'), 'utf8');

assert.match(api, /communityAddonDirectory: \(\{ type = '', q = '' \} = \{\}\) =>/,
  'the browser must use the Hub-owned community directory rather than a caller-supplied URL');
assert.match(api, /community-addon-directory\$\{suffix\}/,
  'the community API must target only the fixed Hub route');
assert.match(api, /installCommunityAddon: \(id, nonEndorsementAcknowledged\)[\s\S]*?nonEndorsementAcknowledged: nonEndorsementAcknowledged === true/,
  'community installs must send the explicit acknowledgement field');

assert.match(screen, /let sourceDirectoryLane = 'curated'; \/\/ curated \| community/,
  'curated and community discovery must remain distinct lanes');
assert.match(screen, /function sourceDirectoryLanePicker\(\)/,
  'the add-on pane must expose a visible Curated / Community selector');
assert.match(screen, /lane\('curated', 'Curated', 'Reviewed by this Hub'\)/,
  'the selector must name the curated trust lane');
assert.match(screen, /lane\('community', 'Community', 'Unreviewed listings'\)/,
  'the selector must name the community trust lane');
assert.match(screen, /function buildCommunitySourceDirectory\(parent, \{ embedded = false \} = \{\}\)/,
  'community sources must have their own browser, not be appended to reviewed results');
assert.match(screen, /lane: 'community', label: 'community', results, list, count: resultCount/,
  'community results must use the bounded source-directory renderer');
assert.match(screen, /text: `Show \$\{next\} more`/,
  'the next directory page must be an explicit, named action rather than scroll-triggered work');
assert.match(screen, /'aria-label': `Show \$\{next\} more \$\{label\} sources\. \$\{remaining\} remain\.`/,
  'the next-page action must explain the remaining result count to assistive technology');
assert.match(screen, /resetSourceDirectoryPaging\('community'\)/,
  'community search and filters must reset the visible result page');
assert.match(screen, /sourceDirectoryResultCount\(label, visible\.length, results\.length\)/,
  'the live count must distinguish visible and total community results');
assert.match(screen, /function communityDirectorySafetyTags\(source\)[\s\S]*?Adult material[\s\S]*?P2P[\s\S]*?Setup required/,
  'community cards must surface adult, peer-to-peer, and setup indicators');
assert.match(screen, /Community listings are not reviewed or endorsed/,
  'the community trust boundary must be visible before opening a source');
assert.match(screen, /function openCommunitySourceDetail\(source, returnFocus\)/,
  'community entries must open details before any install action');
assert.match(screen, /role: 'checkbox'[\s\S]*?Acknowledge: this is a community listing, not a reviewed recommendation/,
  'the confirmation step must require an explicit acknowledgement control');
assert.match(screen, /api\.installCommunityAddon\(source\.id, true\)/,
  'the final action must install only the opaque community descriptor id with acknowledgement');
assert.match(screen, /ad\.provenance === 'community' \? 'Community source · unreviewed'/,
  'installed community sources must retain their unreviewed provenance');

const detailStart = screen.indexOf('function openCommunitySourceDetail(source, returnFocus)');
const detailEnd = screen.indexOf('function buildCommunitySourceDirectory(parent', detailStart);
assert.ok(detailStart >= 0 && detailEnd > detailStart, 'community detail section must be discoverable');
const detail = screen.slice(detailStart, detailEnd);
assert.ok(detail.indexOf("text: 'Review community risks'") < detail.indexOf('api.installCommunityAddon(source.id, true)'),
  'install must remain behind a named risk-review step');
assert.doesNotMatch(detail, /source\.(?:url|manifest|transportUrl)\b/,
  'community details must not expose upstream manifest or transport URLs');
assert.doesNotMatch(detail, /api\.addAddon\(/,
  'community details must not fall back to the advanced URL installer');

assert.match(css, /\.source-directory-lanes \{[\s\S]*?grid-template-columns: repeat\(2, minmax\(0, 1fr\)\)/,
  'the trust-lane selector needs a responsive, stable layout');
assert.match(css, /\.source-detail-ack \{[\s\S]*?var\(--surface2\)[\s\S]*?var\(--ink1\)/,
  'the acknowledgement control must use semantic theme tokens');
assert.match(css, /\.source-detail-community-install\[disabled\]/,
  'the unavailable final action must have a clear disabled state');
assert.match(screen, /\.li:not\(\.source-directory-card\)/,
  'large source directories must skip staged row entrance work');
assert.doesNotMatch(css, /\.source-directory-lane \{[^}]*\btransform:/,
  'switching trust lanes must not use a transform animation that can jitter a dense settings pane');

console.log('WEB COMMUNITY SOURCE DIRECTORY GATE OK');
