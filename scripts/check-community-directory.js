'use strict';
/*
 * No-network contract checks for the community directory.
 *
 * The fixture is injected into the parser service; if a future edit reaches the network this test has
 * no transport setup to make that work. It protects the separation between community, reviewed, and
 * advanced/manual installation lanes while exercising redaction and consent gates.
 */

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const addons = require('../hub/core/addons');
const reviewed = require('../hub/core/reviewed-addons');
const community = require('../hub/core/community-directory');

const fixture = {
  addons: [
    {
      transportName: 'http',
      transportUrl: 'https://catalog.example.test/private-path/manifest.json',
      manifest: {
        id: 'org.example.community.safe',
        name: 'Community Cinema',
        version: '1.4.2',
        description: 'Browse community movies. Docs: https://catalog.example.test/help apiKey=do-not-return',
        types: ['movie', 'series'],
        resources: [
          { name: 'catalog', types: ['movie', 'series'] },
          { name: 'stream', types: ['movie', 'series'] },
        ],
        behaviorHints: { adult: true, p2p: true, configurable: true, configurationRequired: false },
        config: [{ key: 'apiKey', default: 'do-not-return', required: false }],
      },
    },
    {
      transportName: 'http',
      transportUrl: 'https://needs-config.example.test/manifest.json',
      manifest: {
        id: 'org.example.community.needs-config',
        name: 'Configuration Required',
        version: '2.0.0',
        description: 'This source has a required private field.',
        types: ['movie'],
        resources: ['stream'],
        behaviorHints: { configurationRequired: false },
        config: [{ key: 'accessToken', required: true, default: 'never-return-this' }],
      },
    },
    // The remote catalog is untrusted. An unsafe descriptor is ignored before it can become an opaque id.
    {
      transportUrl: 'http://127.0.0.1/manifest.json',
      manifest: {
        id: 'org.example.private', name: 'Private', version: '1.0.0',
        types: ['movie'], resources: ['stream'],
      },
    },
  ],
};

(async () => {
  let calls = 0;
  let requestedUrl = '';
  const directory = community.createDirectory({
    now: () => 1_000,
    fetchCatalog: async (url) => {
      calls += 1;
      requestedUrl = url;
      return JSON.stringify(fixture);
    },
  });

  const browse = await directory.directory({ type: 'movie', q: 'community' });
  assert.strictEqual(calls, 1, 'the injected fixture is the only catalogue input');
  assert.strictEqual(requestedUrl, community.OFFICIAL_CATALOG_URL,
    'production service must pass only the fixed official endpoint to its transport');
  assert.strictEqual(browse.trust, 'community');
  assert.strictEqual(browse.nonEndorsed, true);
  assert.deepStrictEqual(browse.acknowledgement, { required: true, field: 'nonEndorsementAcknowledged' });
  assert.deepStrictEqual(browse.filters, { types: ['movie', 'series'] });
  assert.deepStrictEqual(browse.query, { type: 'movie', q: 'community' });
  assert.strictEqual(browse.entries.length, 1, 'the required-config descriptor does not match the safe query');

  const safe = browse.entries[0];
  assert.match(safe.id, /^community-[a-f0-9]{24}$/);
  assert.strictEqual(safe.trust, 'community');
  assert.strictEqual(safe.adult, true, 'adult indicator must survive redaction');
  assert.strictEqual(safe.p2p, true, 'P2P indicator must survive redaction');
  assert.strictEqual(safe.configurable, true);
  assert.strictEqual(safe.configurationRequired, false);
  assert.deepStrictEqual(safe.install, {
    method: 'community-id',
    requiresAdmin: true,
    requiresAcknowledgement: true,
    acknowledgement: 'community-not-endorsed',
    available: true,
  });

  const publicJson = JSON.stringify(browse);
  for (const secret of [
    'catalog.example.test', 'private-path', 'org.example.community.safe', 'do-not-return', 'apiKey',
    'transportUrl', 'manifestUrl', 'manifestId', '"config"',
  ]) {
    assert.ok(!publicJson.includes(secret), 'public projection leaked ' + secret);
  }

  const all = await directory.directory({ installedIds: [safe.id] });
  assert.strictEqual(calls, 1, 'fresh cache must avoid a second catalogue fetch');
  const installedSafe = all.entries.find((entry) => entry.id === safe.id);
  const configRequired = all.entries.find((entry) => entry.name === 'Configuration Required');
  assert.strictEqual(installedSafe.installed, true);
  assert.ok(configRequired, 'configuration-required source is safely explainable, not hidden');
  assert.strictEqual(configRequired.configurationRequired, true);
  assert.deepStrictEqual(configRequired.install, {
    method: 'community-id',
    requiresAdmin: true,
    requiresAcknowledgement: true,
    acknowledgement: 'community-not-endorsed',
    available: false,
    reason: 'configuration-required',
    setup: { available: true, method: 'web-code', expiresInSeconds: 600 },
  });

  // IDs are opaque cache keys, not alternate spellings of a manifest URL. Unknown/URL-shaped values fail
  // closed; the server can never turn them into a caller-directed transport request.
  assert.strictEqual(await directory.find('community-000000000000000000000000'), null);
  assert.strictEqual(await directory.find('https://catalog.example.test/private-path/manifest.json'), null);
  assert.strictEqual(await directory.publicEntry('not-an-id'), null);

  assert.strictEqual(community.hasNonEndorsementAcknowledgement(null), false);
  assert.strictEqual(community.hasNonEndorsementAcknowledgement({ acknowledged: true }), false);
  assert.strictEqual(community.hasNonEndorsementAcknowledgement({ nonEndorsementAcknowledged: 'true' }), false);
  assert.strictEqual(community.hasNonEndorsementAcknowledgement({ nonEndorsementAcknowledged: true }), true);

  const internal = await directory.find(safe.id);
  assert.ok(internal && internal.manifestUrl, 'only the Hub-side cache retains an install descriptor');
  assert.deepStrictEqual(community.validateRemoteManifest(internal, fixture.addons[0].manifest), { ok: true });
  assert.deepStrictEqual(community.validateRemoteManifest(internal, {
    ...fixture.addons[0].manifest,
    behaviorHints: { ...fixture.addons[0].manifest.behaviorHints, p2p: false },
  }), { ok: false, error: 'community manifest safety indicators changed' });
  assert.deepStrictEqual(community.validateRemoteManifest(internal, {
    ...fixture.addons[0].manifest,
    config: [{ key: 'nowRequired', required: true }],
  }), { ok: false, error: 'community manifest now requires configuration' });
  const requiredInternal = await directory.find(configRequired.id);
  assert.deepStrictEqual(community.validateConfiguredManifest(requiredInternal, {
    ...fixture.addons[1].manifest,
    behaviorHints: { configurationRequired: false },
    config: [{ key: 'accessToken', required: false }],
  }), { ok: true }, 'a completed setup may clear only the required-config flag');
  assert.deepStrictEqual(community.validateConfiguredManifest(requiredInternal, fixture.addons[1].manifest),
    { ok: false, error: 'community manifest still requires configuration' });

  // Regression guard: provenance keeps the three lanes distinct and the remote directory cannot mutate
  // the separately repository-owned reviewed registry.
  assert.deepStrictEqual(addons.util.normalizeProvenance({ kind: 'reviewed', registryId: 'kept-reviewed', revision: '1' }),
    { kind: 'reviewed', registryId: 'kept-reviewed', revision: '1' });
  assert.deepStrictEqual(addons.util.normalizeProvenance({ kind: 'community', directoryId: safe.id, revision: '1.4.2' }),
    { kind: 'community', directoryId: safe.id, revision: '1.4.2' });
  assert.deepStrictEqual(addons.util.normalizeProvenance({ kind: 'manual' }), { kind: 'manual' });
  assert.ok(reviewed.publicDirectory().entries.every((entry) => entry.trust === 'reviewed'));
  assert.ok(all.entries.every((entry) => entry.trust === 'community'));

  // The HTTP route must retain the backend gates rather than trusting a UI review step.
  const source = fs.readFileSync(path.join(__dirname, '..', 'hub', 'index.js'), 'utf8');
  const routeStart = source.indexOf("if (path === '/app/v1/community-addon-directory' && req.method === 'GET')");
  const routeEnd = source.indexOf("// Add-ons: the list", routeStart);
  assert.ok(routeStart >= 0 && routeEnd > routeStart, 'community route block is present');
  const routes = source.slice(routeStart, routeEnd);
  assert.match(routes, /requireAdminSession\(req, res\)/, 'install route must enforce the admin session gate');
  assert.match(routes, /hasNonEndorsementAcknowledgement\(body\)/, 'install route must enforce explicit acknowledgement');
  assert.match(routes, /entry\.configurationRequired/, 'install route must reject configuration-required descriptors');
  assert.match(routes, /addons\.add\(entry\.manifestUrl/, 'install route must use only the server-cached descriptor');
  assert.match(routes, /redirectPolicy: 'same-origin'/, 'community installs must pin redirects to the source origin, like the reviewed lane');
  assert.doesNotMatch(routes, /body\s*(?:&&|\.)\s*url/, 'community route must not accept a caller manifest URL');

  console.log('COMMUNITY DIRECTORY SELFTEST OK');
})().catch((error) => {
  console.error('COMMUNITY DIRECTORY SELFTEST FAILED:', error && error.stack || error);
  process.exitCode = 1;
});
