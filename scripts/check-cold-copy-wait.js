#!/usr/bin/env node
'use strict';
/*
 * check-cold-copy-wait.js — a cold copy is not a dead copy.
 *
 * The engine answers "getting info, 0 files" for a few seconds on every torrent it has not read
 * before. The hub used to write that into the miss blacklist, so the press right after a warming one
 * came back instantly as "engine already tried this copy, asked again in 27s" — and the app, which
 * retries two seconds later, turned that into a terminal "No playable source". Every cold play failed
 * on the second ask while the engine was still fetching the file list.
 *
 * Runs against a stub engine: no TorrServer, no swarm, no network.
 */

const http = require('http');

const HASH_COLD = 'a'.repeat(40);
const HASH_DEAD = 'b'.repeat(40);

let coldGets = 0;
let engineCalls = 0;
let coldReadyAfter = 3; // this many "getting info" answers, then the file list

const stub = http.createServer((req, res) => {
  if (req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'text/html' });
    res.end('ok');
    return;
  }
  let body = '';
  req.on('data', (c) => { body += c; });
  req.on('end', () => {
    engineCalls++;
    let msg = {};
    try { msg = JSON.parse(body || '{}'); } catch (e) {}
    const hash = String(msg.hash || '').toLowerCase();
    const reply = (o) => {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(o));
    };
    if (msg.action === 'add') return reply({});
    if (msg.action === 'get') {
      if (hash === HASH_DEAD) {
        return reply({
          stat_string: 'Torrent working',
          file_stats: [{ id: 1, path: 'readme.txt', length: 4096 }],
        });
      }
      coldGets++;
      if (coldGets <= coldReadyAfter) {
        return reply({ stat_string: 'Torrent getting info', file_stats: [] });
      }
      return reply({
        stat_string: 'Torrent working',
        total_peers: 42,
        torrent_size: 2e9,
        file_stats: [{ id: 1, path: 'Film.2026.1080p.mkv', length: 2e9 }],
      });
    }
    return reply({});
  });
});

const fails = [];
const ok = (cond, line) => { if (!cond) fails.push(line); };

stub.listen(0, '127.0.0.1', async () => {
  const port = stub.address().port;
  process.env.GATEWAY = 'http';
  process.env.TORR_URL = 'http://127.0.0.1:' + port;
  process.env.GATEWAY_BUDGET_MS = '300';
  process.env.GATEWAY_WARM_MS = '1200';
  process.env.GATEWAY_GET_MS = '300';
  process.env.GATEWAY_ADD_MS = '300';
  const gw = require('../hub/core/gateway.js');
  const src = (hash) => ({
    kind: 'torrent',
    url: 'magnet:?xt=urn:btih:' + hash + '&dn=test',
    infoHash: hash,
    label: '1080p · 2 GB',
  });

  // 1. First press on a cold copy: a warming answer, not a url.
  const first = await gw.resolve(src(HASH_COLD), { budgetMs: 300 });
  const firstCalls = engineCalls;
  ok(!first.url, 'first press should not name a url before the file list exists');
  ok(/metadata still arriving|has not read this copy/i.test(String(first.reason)),
    'first press should say the engine has not read it yet, got: ' + first.reason);

  // 2. The retry the app makes two seconds later: it must not be refused from memory. Joining the
  //    conversation still in flight is fine; answering from a miss blacklist is the bug.
  const second = await gw.resolve(src(HASH_COLD), { budgetMs: 300 });
  ok(!/asked again in/i.test(String(second.reason)),
    'the retry must not be refused from the miss blacklist, got: ' + second.reason);

  // 3. Once the file list lands, the same copy plays.
  const deadline = Date.now() + 4000;
  let playable = null;
  while (Date.now() < deadline && !playable) {
    const r = await gw.resolve(src(HASH_COLD), { budgetMs: 600 });
    if (r.url) playable = r;
    else await new Promise((r2) => setTimeout(r2, 150));
  }
  ok(Boolean(playable), 'the copy must become playable once the engine lists its file');
  ok(playable && /\/play\/[a-f0-9]{40}\/1$/.test(playable.url),
    'a playable answer is the gateway play path, got: ' + (playable && playable.url));

  // 4. A torrent that really has no video inside stays a verdict: asking again is refused from the
  //    blacklist immediately, because re-reading a dead file for ten minutes helps nobody.
  await gw.resolve(src(HASH_DEAD), { budgetMs: 300 });
  await new Promise((r) => setTimeout(r, 1600)); // let the background conversation classify it
  const deadAgain = await gw.resolve(src(HASH_DEAD), { budgetMs: 600 });
  ok(/asked again in/i.test(String(deadAgain.reason)),
    'a dead copy must stay blacklisted, got: ' + deadAgain.reason);

  stub.close();
  if (fails.length) {
    for (const f of fails) console.error('FAIL', f);
    process.exit(1);
  }
  console.log('COLD COPY WAIT GATE OK — ' + engineCalls + ' engine calls, '
    + 'warming press retried, file list played, dead copy still refused');
  process.exit(0);
});
