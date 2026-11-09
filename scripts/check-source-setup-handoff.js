'use strict';
/*
 * No-network proof for the configured-source handoff.  A source code is deliberately not an
 * alternate URL installer: it is short-lived, administrator-gated by the route, names only a
 * server-side descriptor, and becomes unusable once completion succeeds.
 */

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const handoff = require('../hub/core/source-setup');

let clock = 1_000;
let entropy = 0;
const service = handoff.create({
  now: () => clock,
  ttlMs: 60_000,
  randomBytes: (size) => Buffer.from(Array.from({ length: size }, () => (++entropy * 13) & 0xff)),
});

const started = service.createSession({
  kind: 'reviewed',
  id: 'internal-registry-key-never-public',
  name: 'Fixture live source',
  revision: '2.0.0',
});
assert.ok(started && handoff.CODE_PATTERN.test(started.code), 'setup must mint a typed opaque code');
assert.strictEqual(started.state, 'pending');
assert.strictEqual(started.handoff.method, 'web-code');
assert.strictEqual(started.handoff.expiresInSeconds, 60);
assert.deepStrictEqual(started.source, {
  name: 'Fixture live source', trust: 'reviewed', nonEndorsed: false,
});
const publicJson = JSON.stringify(started);
for (const forbidden of ['internal-registry-key-never-public', 'manifestUrl', 'https://']) {
  assert.ok(!publicJson.includes(forbidden), `public handoff leaked ${forbidden}`);
}

assert.strictEqual(handoff.normaliseCode(started.code.toLowerCase()), started.code,
  'a couch-entered code may be case-normalised');
assert.strictEqual(handoff.normaliseCode('SCX-OOOO-OOOO-OOOO'), null,
  'ambiguous/non-base32 characters must not become a valid code');

const reservation = service.beginCompletion(started.code);
assert.strictEqual(reservation.ok, true);
assert.strictEqual(reservation.source.id, 'internal-registry-key-never-public',
  'only the server-side reservation can recover the source id');
assert.strictEqual(service.beginCompletion(started.code).status, 409,
  'parallel completion must be blocked');
const released = service.releaseCompletion(started.code);
assert.strictEqual(released.state, 'pending');
assert.strictEqual(service.beginCompletion(started.code).ok, true);
const completed = service.complete(started.code);
assert.strictEqual(completed.state, 'completed');
assert.strictEqual(service.beginCompletion(started.code).status, 409,
  'completed setup codes must be one-time');

const allowed = handoff.configuredManifestUrl(
  'https://addons.example.org/config/opaque/manifest.json?token=private',
  'https://addons.example.org/manifest.json',
);
assert.strictEqual(allowed.ok, true, 'same-origin configured manifest may carry provider-specific path/query state');
assert.strictEqual(handoff.configuredManifestUrl(
  'https://elsewhere.example.org/manifest.json', 'https://addons.example.org/manifest.json',
).ok, false, 'completion must not become a cross-origin installer');
assert.strictEqual(handoff.configuredManifestUrl(
  'https://user:secret@addons.example.org/manifest.json', 'https://addons.example.org/manifest.json',
).ok, false, 'configured URLs must not carry authority credentials');
assert.strictEqual(handoff.configuredManifestUrl(
  'http://addons.example.org/manifest.json', 'https://addons.example.org/manifest.json',
).ok, false, 'configured URLs must remain HTTPS');

const expiring = service.createSession({ kind: 'community', id: 'community-abcdef0123456789abcdef01', name: 'Community fixture', revision: '1.0.0' });
clock += 60_001;
assert.strictEqual(service.status(expiring.code), null, 'expired codes must not remain claimable');

const root = path.join(__dirname, '..');
const hub = fs.readFileSync(path.join(root, 'hub', 'index.js'), 'utf8');
const api = fs.readFileSync(path.join(root, 'hub', 'web', 'js', 'api.js'), 'utf8');
const web = fs.readFileSync(path.join(root, 'hub', 'web', 'js', 'screens', 'settings.js'), 'utf8');
const tv = fs.readFileSync(path.join(root, 'app', 'src', 'main', 'java', 'com', 'scenix', 'tv', 'AddonsActivity.kt'), 'utf8');
const browseApi = fs.readFileSync(path.join(root, 'app', 'src', 'main', 'java', 'com', 'scenix', 'tv', 'BrowseApi.kt'), 'utf8');

assert.ok(hub.includes('addon-registry\\/([a-z0-9-]+)\\/setup-session'),
  'reviewed config setup needs a registry-id-only route');
assert.ok(hub.includes('community-addon-directory\\/(community-[a-f0-9]{24})\\/setup-session'),
  'community config setup needs an opaque-id-only route');
const setupStart = hub.indexOf("setup-session$/.exec(path)");
const setupEnd = hub.indexOf("// The generic handoff endpoints", setupStart);
assert.ok(setupStart >= 0 && setupEnd > setupStart, 'source-specific handoff routes must be discoverable');
const starts = hub.slice(setupStart, setupEnd);
assert.match(starts, /requireAdminSession\(req, res\)/,
  'every setup start must require the household admin session');
assert.match(starts, /hasNonEndorsementAcknowledgement\(body\)/,
  'community setup must retain the explicit non-endorsement acknowledgement');
assert.ok(hub.includes('source-setups\\/([^/]+)\\/complete'),
  'completion must accept only an opaque setup code');
assert.match(hub, /sourceSetupCore\.configuredManifestUrl/,
  'completion must bind the configured address to the original source origin');
assert.match(hub, /sourceSetup\.complete\(reservation\.code\)/,
  'completion must consume the one-time code only after install succeeds');
assert.doesNotMatch(starts, /body\s*(?:&&|\.)\s*(?:url|manifestUrl)/,
  'setup start must not accept a caller-supplied manifest URL');

assert.match(api, /startReviewedSourceSetup:/,
  'web API must start reviewed setup by id');
assert.match(api, /startCommunitySourceSetup:/,
  'web API must start acknowledged community setup by opaque id');
assert.match(api, /completeSourceSetup:/,
  'web API must complete only with an opaque code');
assert.match(web, /Finish a source setup code/,
  'web settings must offer a code completion route for TV handoffs');
assert.match(web, /This address is kept server-side and is never sent to the TV\./,
  'web copy must state the TV secret boundary');
assert.match(tv, /private fun showSetupHandoff\(setup: JSONObject\)/,
  'TV must display the safe setup receipt');
assert.match(tv, /private fun checkSourceSetup\(code: String, name: String\)/,
  'TV must let the couch check an explicit completion receipt');
assert.match(tv, /The TV never receives configuration details\./,
  'TV copy must preserve the secret boundary');
assert.match(browseApi, /fun startReviewedSourceSetup\(/,
  'TV API must initiate the reviewed opaque handoff');
assert.match(browseApi, /fun startCommunitySourceSetup\(/,
  'TV API must initiate acknowledged Community handoff');
assert.match(browseApi, /fun sourceSetupStatus\(/,
  'TV API must read only the safe completion state');
assert.doesNotMatch(tv, /setup\.optString\("(?:url|manifest|config)"\)/,
  'TV handoff rendering must never read an upstream URL, manifest, or config secret');

console.log('SOURCE SETUP HANDOFF SELFTEST OK');
