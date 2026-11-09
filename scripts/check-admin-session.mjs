#!/usr/bin/env node
/*
 * Server-bound administrator-session check.
 *
 * Starts an isolated Hub with an empty temporary data directory. It proves that enabling the
 * settings lock returns an opaque session, protected writes reject without it, and a token cannot
 * be replayed by a different client fingerprint.
 */

import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const tempDir = mkdtempSync(path.join(os.tmpdir(), 'scenix-admin-session-'));
const hubSource = readFileSync(path.join(ROOT, 'hub', 'index.js'), 'utf8');

// Offline preview is intentionally loopback-only. The preview guard is meaningless if the listener
// silently substitutes a LAN bind after accepting HUB_BIND.
assert.match(hubSource, /server\.listen\(PORT,\s*HUB_BIND,/,
  'Hub listener must honor HUB_BIND');

// Standalone uses the same user-visible route but cannot be booted from Node. Keep a narrow
// source-level tripwire alongside the Hub integration test so the alternate PIN verifier cannot
// silently lose its shared rate-limit calls in one backend.
const standaloneSource = readFileSync(
  path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'scenix', 'tv', 'core', 'LocalHub.kt'),
  'utf8',
);
const standaloneLockRoute = standaloneSource.match(
  /fun setSettingsLock\([\s\S]*?\n\s*var nextHash = currentHash/,
);
assert.ok(standaloneLockRoute, 'Standalone settings-lock route must exist');
assert.match(standaloneLockRoute[0], /settingsUnlockAllowed\(now\)/,
  'Standalone lock changes must share the PIN attempt budget');
assert.match(standaloneLockRoute[0], /settingsUnlockFailed\(now\)/,
  'Standalone lock changes must record bad current-PIN attempts');
const standaloneServerSource = readFileSync(
  path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'scenix', 'tv', 'core', 'LocalServer.kt'),
  'utf8',
);
const standaloneLockHandler = standaloneServerSource.match(
  /path == "\/app\/v1\/settings\/lock"[\s\S]*?\n\s*}\n\s*\n\s*path == "\/app\/v1\/settings"/,
);
assert.ok(standaloneLockHandler, 'Standalone settings-lock HTTP handler must exist');
assert.match(standaloneLockHandler[0], /"too many attempts" -> res\.json\(429, out\)/,
  'Standalone lock-change throttling must be exposed as HTTP 429');

function freePort() {
  return new Promise((resolve, reject) => {
    const probe = net.createServer();
    probe.once('error', reject);
    probe.listen(0, '127.0.0.1', () => {
      const address = probe.address();
      probe.close((error) => error ? reject(error) : resolve(address.port));
    });
  });
}

function call(port, method, requestPath, body, session = '', userAgent = 'admin-session-selftest') {
  return new Promise((resolve, reject) => {
    const encoded = body == null ? '' : JSON.stringify(body);
    const headers = { Accept: 'application/json', 'User-Agent': userAgent };
    if (encoded) {
      headers['Content-Type'] = 'application/json';
      headers['Content-Length'] = Buffer.byteLength(encoded);
    }
    if (session) headers['X-Scenix-Admin-Session'] = session;
    const req = http.request({ host: '127.0.0.1', port, path: requestPath, method, headers }, (res) => {
      let text = '';
      res.setEncoding('utf8');
      res.on('data', (chunk) => { text += chunk; });
      res.on('end', () => {
        let json = null;
        try { json = text ? JSON.parse(text) : null; } catch (error) { return reject(error); }
        resolve({ status: res.statusCode || 0, json });
      });
    });
    req.once('error', reject);
    if (encoded) req.write(encoded);
    req.end();
  });
}

async function waitForHub(port) {
  let latest;
  for (let i = 0; i < 60; i++) {
    try {
      const answer = await call(port, 'GET', '/healthz');
      if (answer.status === 200) return;
      latest = new Error(`health answered ${answer.status}`);
    } catch (error) {
      latest = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw latest || new Error('Hub did not start');
}

async function stop(child) {
  if (child.exitCode != null) return;
  child.kill('SIGTERM');
  await new Promise((resolve) => {
    const timer = setTimeout(() => {
      child.kill('SIGKILL');
      resolve();
    }, 3000);
    child.once('exit', () => { clearTimeout(timer); resolve(); });
  });
}

let child;
try {
  const port = await freePort();
  child = spawn(process.execPath, ['hub/index.js'], {
    cwd: ROOT,
    env: {
      ...process.env,
      PORT: String(port),
      DATA_DIR: tempDir,
      ANNOUNCE: '0',
      PROBE_ENABLED: '0',
    },
    stdio: ['ignore', 'ignore', 'ignore'],
  });
  await waitForHub(port);

  // An unlocked household remains backwards-compatible: no capability is needed until the owner
  // explicitly enables the settings lock.
  const unlockedWrite = await call(port, 'PUT', '/app/v1/settings', { settings: { hubName: 'open' } });
  assert.equal(unlockedWrite.status, 200);

  const enabled = await call(port, 'POST', '/app/v1/settings/lock', { enabled: true, pin: '1234' });
  assert.equal(enabled.status, 200);
  assert.equal(enabled.json?.lock?.enabled, true);
  assert.match(enabled.json?.adminSession || '', /^[a-f0-9]{64}$/i);
  const session = enabled.json.adminSession;

  // The curated source directory is a separate, read-only discovery surface. It ships only the
  // independently reviewed official entries, redacts their manifests, and never turns an
  // arbitrary registry id into a URL.
  const reviewed = await call(port, 'GET', '/app/v1/addon-registry');
  assert.equal(reviewed.status, 200);
  assert.deepEqual(reviewed.json?.directory?.entries?.map((entry) => entry.id), [
    'stremio-cinemeta',
    'stremio-opensubtitles-v3',
    'stremio-watchhub',
  ]);
  assert.deepEqual(reviewed.json?.reviewed, reviewed.json?.directory?.entries);
  assert.deepEqual(reviewed.json?.directory?.filters, {
    categories: ['accessibility', 'availability', 'catalogs', 'metadata', 'streaming-links', 'subtitles'],
    types: ['movie', 'series'],
  });
  assert.equal(JSON.stringify(reviewed.json).includes('manifest.json'), false);
  assert.equal(JSON.stringify(reviewed.json).includes('manifestId'), false);
  assert.equal(reviewed.json?.manualInstall?.safety, 'advanced-unreviewed');
  const reviewedDirectory = await call(port, 'GET', '/app/v1/addon-registry/directory?category=live&type=movie&q=fixture');
  assert.equal(reviewedDirectory.status, 200);
  assert.deepEqual(reviewedDirectory.json?.filters, {
    categories: ['accessibility', 'availability', 'catalogs', 'metadata', 'streaming-links', 'subtitles'],
    types: ['movie', 'series'],
  });
  assert.deepEqual(reviewedDirectory.json?.query, { category: 'live', type: 'movie', q: 'fixture' });
  assert.deepEqual(reviewedDirectory.json?.entries, []);
  assert.equal(reviewedDirectory.json?.manualInstall?.safety, 'advanced-unreviewed');
  const missingReviewedDetail = await call(port, 'GET', '/app/v1/addon-registry/not-reviewed');
  assert.equal(missingReviewedDetail.status, 404);
  const missingReviewed = await call(port, 'POST', '/app/v1/addon-registry/not-reviewed/install', {}, session);
  assert.equal(missingReviewed.status, 404);

  const blockedSettings = await call(port, 'PUT', '/app/v1/settings', { settings: { hubName: 'blocked' } });
  assert.equal(blockedSettings.status, 403);
  assert.equal(blockedSettings.json?.gate, 'settings-lock');

  const savedSettings = await call(port, 'PUT', '/app/v1/settings', { settings: { hubName: 'allowed' } }, session);
  assert.equal(savedSettings.status, 200);
  assert.equal(savedSettings.json?.settings?.hubName, 'allowed');

  // Profile reads and a profile's own unlock remain usable: the Settings lock must not turn an
  // existing profile gate into a circular dependency. Profile mutations, however, are household
  // administration and need the same session as every other protected write.
  const listedProfiles = await call(port, 'GET', '/app/v1/profiles');
  assert.equal(listedProfiles.status, 200);
  const firstProfile = listedProfiles.json?.profiles?.[0];
  assert.ok(firstProfile?.id);
  const profileUnlock = await call(port, 'POST', `/app/v1/profiles/${encodeURIComponent(firstProfile.id)}/unlock`, { pin: '0000' });
  assert.equal(profileUnlock.status, 200);

  const blockedCreateProfile = await call(port, 'POST', '/app/v1/profiles', { name: 'Blocked profile', kind: 'kids' });
  assert.equal(blockedCreateProfile.status, 403);
  assert.equal(blockedCreateProfile.json?.gate, 'settings-lock');
  const blockedPatchProfile = await call(port, 'PUT', `/app/v1/profiles/${encodeURIComponent(firstProfile.id)}`, { name: 'Blocked rename' });
  assert.equal(blockedPatchProfile.status, 403);
  const blockedDeleteProfile = await call(port, 'DELETE', `/app/v1/profiles/${encodeURIComponent(firstProfile.id)}`, {});
  assert.equal(blockedDeleteProfile.status, 403);

  const createdProfile = await call(port, 'POST', '/app/v1/profiles', { name: 'Allowed profile', kind: 'kids' }, session);
  assert.equal(createdProfile.status, 200);
  const createdId = createdProfile.json?.profile?.id;
  assert.ok(createdId);
  const patchedProfile = await call(port, 'PATCH', `/app/v1/profiles/${encodeURIComponent(createdId)}`, { name: 'Allowed rename' }, session);
  assert.equal(patchedProfile.status, 200);
  const deletedProfile = await call(port, 'DELETE', `/app/v1/profiles/${encodeURIComponent(createdId)}`, {}, session);
  assert.equal(deletedProfile.status, 200);

  const blockedAddon = await call(port, 'POST', '/app/v1/addons', { url: 'https://example.invalid/manifest.json' });
  assert.equal(blockedAddon.status, 403);
  assert.equal(blockedAddon.json?.gate, 'settings-lock');

  const blockedCache = await call(port, 'POST', '/app/v1/cache/clear', {});
  assert.equal(blockedCache.status, 403);
  const clearedCache = await call(port, 'POST', '/app/v1/cache/clear', {}, session);
  assert.equal(clearedCache.status, 200);

  const replay = await call(port, 'PUT', '/app/v1/settings', { settings: { hubName: 'replay' } }, session, 'other-client');
  assert.equal(replay.status, 403);

  const wrongPin = await call(port, 'POST', '/app/v1/settings/unlock', { pin: '9999' });
  assert.equal(wrongPin.status, 403);
  const unlocked = await call(port, 'POST', '/app/v1/settings/unlock', { pin: '1234' });
  assert.equal(unlocked.status, 200);
  assert.match(unlocked.json?.adminSession || '', /^[a-f0-9]{64}$/i);

  // The lock-change route verifies the same PIN. It must not provide an unlimited alternate
  // oracle after the dedicated unlock endpoint becomes throttled.
  for (let attempt = 0; attempt < 10; attempt++) {
    const rejectedChange = await call(port, 'POST', '/app/v1/settings/lock', {
      enabled: true,
      pinCurrent: '9999',
    });
    assert.equal(rejectedChange.status, 403);
  }
  const throttledChange = await call(port, 'POST', '/app/v1/settings/lock', {
    enabled: true,
    pinCurrent: '1234',
  });
  assert.equal(throttledChange.status, 429);

  console.log('ADMIN SESSION SELFTEST OK');
} finally {
  if (child) await stop(child);
  rmSync(tempDir, { recursive: true, force: true });
}
