'use strict';
/* No-network checks for the reviewed-source registry boundary. */

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const reviewed = require('../hub/core/reviewed-addons');

const FIXTURE = path.join(__dirname, '..', 'hub', 'registry', 'fixtures', 'reviewed-addons.directory.json');

function rejects(value, pattern) {
  assert.throws(() => reviewed.parseDocument(value, 'test-registry'), pattern);
}

const valid = {
  version: 1,
  entries: [{
    id: 'example-cinema',
    name: 'Example Cinema',
    version: '1.2.3',
    manifest: 'https://addons.example.org/manifest.json',
    manifestId: 'org.example.cinema',
    resources: [
      { name: 'catalog', types: ['movie', 'series'] },
      { name: 'meta', types: ['movie', 'series'] },
      { name: 'stream', types: ['movie', 'series'] },
    ],
  }],
};

const parsed = reviewed.parseDocument(valid, 'test-registry');
assert.strictEqual(parsed.entries.length, 1);
assert.strictEqual(parsed.entries[0].manifest, 'https://addons.example.org/manifest.json');
assert.ok(Object.isFrozen(parsed.entries[0]));
assert.strictEqual(parsed.entries[0].description, '');
assert.deepStrictEqual(parsed.entries[0].categories, []);
assert.deepStrictEqual(parsed.entries[0].tags, []);
assert.strictEqual(parsed.entries[0].configurationRequired, false);
assert.deepStrictEqual(reviewed.validateRemoteManifest(parsed.entries[0], {
  id: 'org.example.cinema',
  version: '1.2.3',
  resources: [
    { name: 'catalog', types: ['movie', 'series'] },
    { name: 'meta', types: ['movie', 'series'] },
    { name: 'stream', types: ['movie', 'series'] },
  ],
}), { ok: true });
assert.deepStrictEqual(reviewed.validateRemoteManifest(parsed.entries[0], {
  id: 'org.example.changed',
  version: '1.2.3',
  resources: [{ name: 'catalog', types: ['movie', 'series'] }],
}), { ok: false, error: 'reviewed manifest id changed' });
assert.deepStrictEqual(reviewed.validateRemoteManifest(parsed.entries[0], {
  id: 'org.example.cinema',
  version: '1.2.3',
  resources: [{ name: 'catalog', types: ['movie', 'series'] }],
}), { ok: false, error: 'reviewed manifest resources changed' });
assert.deepStrictEqual(reviewed.validateRemoteManifest(parsed.entries[0], {
  id: 'org.example.cinema',
  version: '1.2.4',
  resources: [
    { name: 'catalog', types: ['movie', 'series'] },
    { name: 'meta', types: ['movie', 'series'] },
    { name: 'stream', types: ['movie', 'series'] },
  ],
}), { ok: false, error: 'reviewed manifest version changed' });
assert.deepStrictEqual(reviewed.validateRemoteManifest(parsed.entries[0], {
  id: 'org.example.cinema',
  version: '1.2.3',
  resources: [
    { name: 'catalog', types: ['movie', 'series'] },
    { name: 'meta', types: ['movie', 'series'] },
    { name: 'stream', types: ['movie', 'series'] },
    'addon_catalog',
  ],
}), { ok: true }, 'a known but unmounted marketplace extension must not loosen the reviewed contract');

rejects({ ...valid, version: 2 }, /version/);
rejects({ ...valid, extra: true }, /unsupported field/);
rejects({
  ...valid,
  entries: [{ ...valid.entries[0], manifest: 'http://addons.example.org/manifest.json' }],
}, /credential-free HTTPS/);
rejects({
  ...valid,
  entries: [{ ...valid.entries[0], manifest: 'https://secret@addons.example.org/manifest.json' }],
}, /credential-free HTTPS/);
rejects({
  ...valid,
  entries: [{ ...valid.entries[0], manifest: 'https://addons.example.org/source.json' }],
}, /manifest\.json/);
rejects({
  ...valid,
  entries: [{ ...valid.entries[0], version: '1.2' }],
}, /version/);
rejects({
  ...valid,
  entries: [{ ...valid.entries[0], resources: [{ name: 'execute', types: ['movie'] }] }],
}, /allowed resource/);
rejects({
  ...valid,
  entries: [{ ...valid.entries[0], resources: [{ name: 'catalog', types: ['game'] }] }],
}, /allowed media type/);
rejects({
  ...valid,
  entries: [{ ...valid.entries[0], categories: ['Not safe'] }],
}, /invalid format/);
rejects({
  ...valid,
  entries: [{ ...valid.entries[0], categories: ['movies', 'movies'] }],
}, /duplicated/);
rejects({
  ...valid,
  entries: [{ ...valid.entries[0], configurationRequired: 'yes' }],
}, /boolean/);
rejects({
  ...valid,
  entries: [{ ...valid.entries[0], homepage: 'https://addons.example.org/' }],
}, /unsupported field/);
rejects({
  ...valid,
  entries: [{ ...valid.entries[0], description: 'See https://addons.example.org/' }],
}, /must not contain a URL/);

// This fixture is intentionally fictional and never fetched. It proves the browse contract from a
// manifest declaration without making a source available in the shipped registry.
const fixture = reviewed.parseDocument(fs.readFileSync(FIXTURE, 'utf8'), 'directory-fixture');
assert.strictEqual(fixture.entries.length, 2);
const directory = reviewed.publicDirectory({ installedIds: new Set(['fixture-live']) }, FIXTURE);
assert.deepStrictEqual(directory.filters, {
  categories: ['featured', 'live', 'movies'],
  types: ['channel', 'movie', 'series'],
});
assert.deepStrictEqual(directory.query, { category: null, type: null, q: null });
assert.deepStrictEqual(directory.entries.map((entry) => entry.id), ['fixture-cinema', 'fixture-live']);
const cinema = directory.entries[0];
const live = directory.entries[1];
assert.deepStrictEqual(cinema.types, ['movie', 'series']);
assert.deepStrictEqual(cinema.categories, ['featured', 'movies']);
assert.strictEqual(cinema.installed, false);
assert.strictEqual(live.installed, true);
assert.strictEqual(live.configurationRequired, true);
assert.deepStrictEqual(live.install, {
  available: false,
  method: 'registry-id',
  requiresAdmin: true,
  reason: 'configuration-required',
  setup: { available: true, method: 'web-code', expiresInSeconds: 600 },
});
assert.strictEqual(live.trust, 'reviewed');
assert.deepStrictEqual(cinema.install, {
  available: true,
  method: 'registry-id',
  requiresAdmin: true,
});

assert.deepStrictEqual(
  reviewed.publicDirectory({ category: 'live' }, FIXTURE).entries.map((entry) => entry.id),
  ['fixture-live'],
);
assert.deepStrictEqual(
  reviewed.publicDirectory({ type: 'movie' }, FIXTURE).entries.map((entry) => entry.id),
  ['fixture-cinema'],
);
assert.deepStrictEqual(
  reviewed.publicDirectory({ q: 'channel' }, FIXTURE).entries.map((entry) => entry.id),
  ['fixture-live'],
);
assert.deepStrictEqual(
  reviewed.publicDirectory({ query: 'cinema' }, FIXTURE).entries.map((entry) => entry.id),
  ['fixture-cinema'],
);
const ignoredUnsafeQuery = reviewed.publicDirectory({ q: '\u0000hidden' }, FIXTURE);
assert.strictEqual(ignoredUnsafeQuery.query.q, null);
assert.deepStrictEqual(ignoredUnsafeQuery.entries.map((entry) => entry.id), ['fixture-cinema', 'fixture-live']);
assert.strictEqual(reviewed.publicDirectory({ q: 'https://example.invalid/' }, FIXTURE).query.q, null);

const detail = reviewed.publicEntry('fixture-live', { installedIds: ['fixture-live'] }, FIXTURE);
assert.strictEqual(detail.id, 'fixture-live');
assert.strictEqual(detail.installed, true);
assert.strictEqual(reviewed.publicEntry('https://live.example.invalid/manifest.json', {}, FIXTURE), null);
assert.strictEqual(reviewed.publicEntry('not-reviewed', {}, FIXTURE), null);
assert.strictEqual(reviewed.find('fixture-live', FIXTURE).manifest, 'https://live.example.invalid/manifest.json');
assert.strictEqual(reviewed.find('https://live.example.invalid/manifest.json', FIXTURE), null);
const publicDirectoryJson = JSON.stringify(directory);
assert.ok(!publicDirectoryJson.includes('example.invalid'));
assert.ok(!publicDirectoryJson.includes('manifestId'));
assert.ok(!publicDirectoryJson.includes('\"manifest\"'));

const shipped = reviewed.publicDirectory();
assert.deepStrictEqual(shipped.entries.map((entry) => entry.id), [
  'stremio-cinemeta',
  'stremio-opensubtitles-v3',
  'stremio-watchhub',
], 'the shipped curated directory must contain only the independently reviewed official entries');
assert.deepStrictEqual(shipped.filters, {
  categories: ['accessibility', 'availability', 'catalogs', 'metadata', 'streaming-links', 'subtitles'],
  types: ['movie', 'series'],
});
const shippedCinemeta = reviewed.find('stremio-cinemeta');
assert.ok(shippedCinemeta, 'the curated metadata source must be installable by stable registry id');
assert.strictEqual(shippedCinemeta.manifestId, 'com.linvo.cinemeta');
assert.deepStrictEqual(reviewed.validateRemoteManifest(shippedCinemeta, {
  id: 'com.linvo.cinemeta',
  version: '3.0.14',
  types: ['movie', 'series'],
  resources: ['catalog', 'meta', 'addon_catalog'],
}), { ok: true }, 'the official metadata declaration may advertise an unmounted marketplace extension');
const shippedJson = JSON.stringify(shipped);
assert.ok(!shippedJson.includes('strem.io/manifest.json'));
assert.ok(!shippedJson.includes('manifestId'));
assert.ok(!shippedJson.includes('\"manifest\"'));
const projection = parsed.entries.map((entry) => ({
  id: entry.id,
  name: entry.name,
  version: entry.version,
  resources: entry.resources,
}));
assert.ok(!JSON.stringify(projection).includes('addons.example.org'));

console.log('REVIEWED SOURCE REGISTRY SELFTEST OK');
