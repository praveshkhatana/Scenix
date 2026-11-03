'use strict';
/*
 * scripts/check-debrid.js — a no-network check of the Real-Debrid adapter.
 *
 * This is deliberately not a live API test: no token, no account, no request leaves the process. It
 * scripts the documented answers (addMagnet -> waiting_files_selection -> selectFiles -> downloaded ->
 * unrestrict) and asserts the episode the viewer asked for is the file the adapter selects.
 *
 *   node scripts/check-debrid.js
 */

const assert = require('assert');
const rd = require('../hub/core/debrid/real-debrid');

const calls = [];
function answer(url, opts) {
  const path = new URL(url).pathname.replace('/rest/1.0', '');
  calls.push(path + (opts && opts.form ? ' ' + JSON.stringify(opts.form) : ''));
  if (path === '/user') {
    return { ok: true, status: 200, json: { type: 'premium', premium: 3600, expiration: '2030-01-01T00:00:00.000Z' } };
  }
  if (path === '/torrents/addMagnet') return { ok: true, status: 201, json: { id: 'torrent-1', uri: 'rd://torrent-1' } };
  if (path === '/torrents/info/torrent-1') {
    // First look: the provider is waiting for a file selection. Second look: it is downloaded.
    const infos = calls.filter((c) => c.startsWith('/torrents/info/torrent-1')).length;
    if (infos === 1) {
      return {
        ok: true,
        status: 200,
        json: {
          status: 'waiting_files_selection',
          files: [
            { id: 7, path: '/Show/Show.S01E02.mkv', bytes: 2000 },
            { id: 8, path: '/Show/Show.S01E03.mkv', bytes: 2100 },
          ],
        },
      };
    }
    return { ok: true, status: 200, json: { status: 'downloaded', links: ['https://rd.example/link/abc'] } };
  }
  if (path === '/torrents/selectFiles/torrent-1') return { ok: true, status: 204, json: null };
  if (path === '/unrestrict/link') {
    return {
      ok: true,
      status: 200,
      json: { download: 'https://rd.example/dl/Show.S01E02.mkv', filename: 'Show.S01E02.mkv', filesize: 2000 },
    };
  }
  return { ok: false, status: 404, error: 'unexpected path in self-test: ' + path };
}

const ctx = {
  token: 'test-token-never-printed',
  want: { season: 1, episode: 2 },
  deadline: Date.now() + 5000,
  http: (opts) => Promise.resolve(answer(opts.url, opts)),
  redact: (s) => String(s),
};

(async () => {
  const out = await rd.resolveMagnet('magnet:?xt=urn:btih:' + 'a'.repeat(40), ctx);
  assert.strictEqual(out.url, 'https://rd.example/dl/Show.S01E02.mkv');
  assert.strictEqual(out.kind, 'http');
  assert.strictEqual(out.provider, 'real-debrid');
  assert.ok(out.expiresAt > Date.now());
  const selected = calls.find((c) => c.startsWith('/torrents/selectFiles/torrent-1'));
  assert.ok(selected && selected.includes('"files":"7"'), 'the requested episode, not the largest file');
  assert.ok(!JSON.stringify(out).includes(ctx.token), 'no token in the answer');

  const status = await rd.status(ctx);
  assert.strictEqual(status.ok, true);
  assert.strictEqual(status.premium, true);
  assert.ok(!JSON.stringify(status).includes(ctx.token));

  const hoster = await rd.unrestrict('https://rapidgator.net/file/abc', ctx);
  assert.strictEqual(hoster.url, 'https://rd.example/dl/Show.S01E02.mkv');
  assert.ok(!JSON.stringify(hoster).includes(ctx.token));

  console.log('DEBRID SELFTEST OK:', calls.length, 'mocked calls');
})().catch((e) => { console.error('DEBRID SELFTEST FAILED:', e.message); process.exitCode = 1; });
