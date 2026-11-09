'use strict';
/*
 * Browser Settings is a control centre, not a grab-bag of API buckets. Keep the rail's language,
 * read-only overview, dedicated source boundary, and route cleanup explicit without needing a browser
 * fixture or a running Hub.
 */

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const source = fs.readFileSync(path.join(root, 'hub', 'web', 'js', 'screens', 'settings.js'), 'utf8');

const between = (start, end, from = 0) => {
  const first = source.indexOf(start, from);
  assert.ok(first >= 0, `missing ${start}`);
  const last = source.indexOf(end, first);
  assert.ok(last >= 0, `missing ${end} after ${start}`);
  return source.slice(first, last);
};

const rail = between('function availableSections()', 'function openSection(');
const expectedRail = [
  "id: 'overview'",
  "id: 'watching'",
  "id: 'appearance'",
  "id: 'home'",
  "id: 'sources'",
  "id: 'household'",
  "id: 'access'",
  "id: 'hub'",
];
let previous = -1;
for (const entry of expectedRail) {
  const position = rail.indexOf(entry);
  assert.ok(position > previous, `settings rail must keep ${entry} in control-centre order`);
  previous = position;
}
assert.match(rail, /title: 'Access & safety'/,
  'the household lock must not be hidden behind a generic access bucket');
assert.match(rail, /title: 'This Hub'/,
  'maintenance and diagnostics must remain clearly Hub-scoped');

const overview = between('function buildOverview(parent)', 'function buildWatching(parent)');
assert.match(overview, /group\('At a glance'/,
  'overview must give the owner an immediate non-editing status summary');
assert.match(overview, /Backend[\s\S]*?Watching as[\s\S]*?Sources[\s\S]*?Theme/,
  'overview must show backend, profile, source, and theme state together');
assert.match(overview, /group\('Quick links'/,
  'overview must provide focused navigation, rather than duplicate every setting');
assert.match(overview, /openSection\(id\)/,
  'overview navigation must reuse the rail selection path');
assert.doesNotMatch(overview, /\bapi\./,
  'overview must remain read-only and avoid new network activity');

const appearance = between('function buildAppearance(parent)', '/* -------------------------------------------------------------- access */');
assert.match(appearance, /group\('Theme'/,
  'theme selection belongs in Appearance');
assert.match(appearance, /writeSettings\(\{ theme: id \}\)[\s\S]*?window\.location\.reload\(\)/,
  'theme changes must retain the acknowledged write-then-reload path');

const sources = between('function buildSources(parent)', 'function buildHub(');
assert.match(sources, /buildAddons\(parent\)/,
  'curated, community, installed, and manual source flows must stay in Sources');
assert.match(sources, /includeHub: false, includeSourcePacks: true, includeDiagnostics: false/,
  'owner-managed picture/audio packs must remain with sources, not Hub maintenance');

const hub = between('function buildHub(', 'function buildDiag(');
assert.doesNotMatch(hub, /themeChoices|group\('Theme'/,
  'This Hub must not reintroduce the theme picker');

assert.match(source, /const previousSectionId = currentSectionId;[\s\S]*?findIndex\(\(section\) => section\.id === previousSectionId\)/,
  'an async repaint must retain the chosen section by identity, not stale index');
assert.match(source, /setAttribute\('aria-selected', selected \? 'true' : 'false'\)/,
  'the accessible selected state must follow rail focus');

const dispose = between('root.__dispose = () => {', 'return root;');
assert.match(dispose, /onRailFocus\(\);[\s\S]*?popHandler\(handler\);/,
  'leaving Settings must unregister both the rail listener and Back-key handler');

console.log('WEB SETTINGS CONTROL-CENTRE STATIC CHECK OK');
