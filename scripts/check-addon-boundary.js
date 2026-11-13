'use strict';
/*
 * scripts/check-addon-boundary.js — no-network contract checks for the external add-on boundary.
 *
 * It stubs Node's HTTP transport after loading core/addons, so it verifies the actual redirect, body
 * and timeout guards without allowing a test server on loopback to become an exception to production's
 * public-address rule.
 *
 *   node scripts/check-addon-boundary.js
 */

const assert = require('assert');
const dns = require('dns');
const { EventEmitter } = require('events');
const http = require('http');
const addons = require('../hub/core/addons');
const { util } = addons;

function response(statusCode, headers = {}) {
  const res = new EventEmitter();
  res.statusCode = statusCode;
  res.headers = headers;
  res.resume = () => {};
  return res;
}

async function withStubbedHttp(responder, work) {
  const originalGet = http.get;
  http.get = (url, options, onResponse) => {
    const req = new EventEmitter();
    req.destroy = () => { req.destroyed = true; };
    process.nextTick(() => responder({ url, options, onResponse, req }));
    return req;
  };
  try {
    return await work();
  } finally {
    http.get = originalGet;
  }
}

async function withStubbedLookup(lookup, work) {
  const originalLookup = dns.promises.lookup;
  dns.promises.lookup = lookup;
  try {
    return await work();
  } finally {
    dns.promises.lookup = originalLookup;
  }
}

async function rejects(promise, pattern) {
  await assert.rejects(promise, (error) => pattern.test(String(error && error.message)));
}

(async () => {
  assert.strictEqual(
    util.manifestUrlFor('https://addons.example/languages=eng').toString(),
    'https://addons.example/languages=eng/manifest.json',
  );
  assert.throws(() => util.manifestUrlFor('ftp://addons.example/manifest.json'), /HTTP\(S\)/);
  assert.throws(() => util.manifestUrlFor('https://user:secret@addons.example/manifest.json'), /credentials/);

  for (const address of [
    '127.0.0.1', '10.0.0.8', '172.20.0.9', '192.168.0.4', '169.254.1.2',
    '100.64.0.1', '203.0.113.8', '::1', 'fc00::5', 'fe80::5', '::ffff:127.0.0.1',
    '::ffff:0:127.0.0.1',
  ]) {
    assert.strictEqual(util.isPublicAddress(address), false, address + ' must be blocked');
  }
  assert.strictEqual(util.isPublicAddress('8.8.8.8'), true);
  assert.strictEqual(util.isPublicAddress('2606:4700:4700::1111'), true);
  await rejects(util.resolvePublicAddresses('http://127.0.0.1/manifest.json'), /not public/);
  await rejects(util.get('http://127.0.0.1/manifest.json'), /not public/);
  await withStubbedLookup(async () => [{ address: '192.168.50.20', family: 4 }],
    () => rejects(util.resolvePublicAddresses('https://dns-rebind.example/manifest.json'), /public address/));
  const rejectedInstall = await addons.add('http://127.0.0.1/private-config');
  assert.deepStrictEqual(rejectedInstall, {
    ok: false, status: 400, error: 'url must be a public HTTP(S) address',
  });

  const manualEntries = [{ id: 'legacy', url: 'https://addons.example/token=secret/manifest.json' }];
  assert.strictEqual(util.persistProvenance(manualEntries), true);
  assert.deepStrictEqual(manualEntries[0].provenance, { kind: 'manual' });
  const reviewed = util.normalizeProvenance({ kind: 'reviewed', registryId: 'community-safe', revision: '7' });
  assert.deepStrictEqual(reviewed, { kind: 'reviewed', registryId: 'community-safe', revision: '7' });
  assert.strictEqual(util.redirectPolicyFor(reviewed), 'same-origin');
  assert.strictEqual(util.redirectPolicyFor(manualEntries[0].provenance), 'public');
  assert.strictEqual(util.redirectAllowed(
    new URL('https://addons.example/manifest.json'), new URL('https://addons.example/catalog/movie/top.json'), 'same-origin'), true);
  assert.strictEqual(util.redirectAllowed(
    new URL('https://addons.example/manifest.json'), new URL('https://other.example/manifest.json'), 'same-origin'), false);

  const projection = util.publicExternalRow({
    id: 'private-config', name: 'https://addons.example/token=secret/manifest.json', kind: 'video', priority: 40, enabled: true,
    addedAt: 1, provenance: reviewed, manifestUrl: 'https://addons.example/token=secret/manifest.json',
    resources: {}, cats: [], lastError: 'failed https://addons.example/token=secret/manifest.json',
    items: new Map(), streamSources: new Map(), lastFetch: null,
  });
  assert.ok(!Object.hasOwn(projection, 'url'));
  assert.ok(!JSON.stringify(projection).includes('token=secret'));
  assert.strictEqual(projection.error, 'remote addon unavailable');
  assert.deepStrictEqual(util.publicStats({
    files: 4,
    file: '/private/hub/data/addons.json',
    root: '/private/hub/media',
    bin: '/usr/local/bin/tool',
  }), { files: 4 });

  await withStubbedHttp(({ onResponse }) => {
    const res = response(302, { location: 'http://1.1.1.1/manifest.json' });
    onResponse(res);
  }, () => rejects(util.get('http://8.8.8.8/manifest.json', 1000, false,
    { redirectPolicy: 'same-origin' }), /reviewed origin/));

  await withStubbedHttp(({ onResponse }) => {
    const res = response(200);
    onResponse(res);
    process.nextTick(() => res.emit('data', Buffer.alloc(9 * 1024 * 1024)));
  }, () => rejects(util.get('http://8.8.8.8/manifest.json'), /size limit/));

  // The timeout timer is unref'd in production because a real socket keeps Node alive. Hold this test
  // process explicitly so the no-response transport proves the deadline path instead of exiting early.
  await withStubbedHttp(() => {}, async () => {
    const keepAlive = setInterval(() => {}, 1000);
    try {
      await rejects(util.get('http://8.8.8.8/manifest.json', 250), /timed out/);
    } finally {
      clearInterval(keepAlive);
    }
  });

  console.log('ADDON BOUNDARY SELFTEST OK');
})().catch((error) => {
  console.error('ADDON BOUNDARY SELFTEST FAILED:', error && error.message);
  process.exitCode = 1;
});
