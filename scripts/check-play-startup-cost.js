#!/usr/bin/env node
'use strict';
/*
 * What a Play press may not do is spend the viewer's seconds twice on the same question.
 *
 * Measured on the hub, one cold press on a series episode took 24 s: 12.7 s of it asking the installed
 * addons for the episode's copy list one addon at a time, and most of the rest asking the torrent engine
 * about three copies in turn — three separate `add` calls for three torrents, each with a few seconds of
 * patience, each timing out while the engine was still reading the magnet it had been asked to add. The
 * press then blacklisted those copies for half a minute, so the viewer's own retry was answered from
 * memory instead of from the engine that had, by then, finished.
 *
 * This gate runs a fake engine and the real gateway against it, and holds the line on the four things
 * that fix cost:
 *
 *   1. one engine conversation per torrent, however many callers want it (a warm and a press must not
 *      each open their own add);
 *   2. a caller that runs out of patience gets an honest "still reading", not a blacklist the next press
 *      has to wait out;
 *   3. the engine is never stampeded: at most `GATEWAY_ADD_CONCURRENCY` adds at a time;
 *   4. the picker's own shape — the copies it will walk are warmed before it walks them, the addons are
 *      asked together, the walk stops after two cold copies, and every press names where its time went.
 */

const assert = require('assert');
const fs = require('fs');
const http = require('http');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');

// ---------------------------------------------------------------- the fake engine
//
// TorrServer answers `/torrents {action:"get"}` in about a millisecond and `{action:"add"}` in ten to
// twenty for a magnet it has not met. The fake copies that asymmetry, and counts what the hub does to it.
const engine = { adds: 0, gets: 0, addInFlight: 0, addMax: 0, addDelayMs: 700, filesAfterMs: 400, hang: new Set() };
const server = http.createServer((req, res) => {
  let body = '';
  req.on('data', (c) => { body += c; });
  req.on('end', async () => {
    if (req.method !== 'POST') { // the hub checks the engine is alive with one GET /
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end('{"version":"fake-torrserver"}');
    }
    const q = JSON.parse(body || '{}');
    if (q.action === 'get' && engine.hang.has(String(q.hash || ''))) {
      // Busy engine: the question is accepted and nothing comes back. The hub may hang up; this
      // server simply never answers, which is what a stalled TorrServer looks like from the outside.
      return;
    }
    if (q.action === 'add') {
      engine.adds++;
      engine.addInFlight++;
      engine.addMax = Math.max(engine.addMax, engine.addInFlight);
      await new Promise((r) => setTimeout(r, engine.addDelayMs));
      engine.addInFlight--;
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end('{}');
    }
    if (q.action === 'get') {
      engine.gets++;
      const hash = String(q.hash || '');
      const ready = engine.seen[hash] !== undefined && Date.now() - engine.seen[hash] >= engine.filesAfterMs;
      if (engine.seen[hash] === undefined) engine.seen[hash] = Date.now();
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(JSON.stringify(ready
        ? {
          stat_string: 'Torrent working',
          total_peers: 12,
          torrent_size: 500e6,
          file_stats: [{ id: 1, path: 'Bluey - S02E31 - Barky Boats.mkv', size: 500e6 }],
        }
        : { stat_string: 'Torrent getting info', total_peers: 1, file_stats: [] }));
    }
    res.writeHead(400).end('{}');
  });
});

(async () => {
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const port = server.address().port;

  const data = fs.mkdtempSync(path.join(require('os').tmpdir(), 'scenix-startup-'));
  process.env.DATA_DIR = data;
  process.env.GATEWAY = 'http';
  process.env.TORR_URL = 'http://127.0.0.1:' + port;
  process.env.GATEWAY_ADD_MS = '400';      // the fake's add is slower than this on purpose
  process.env.GATEWAY_GET_MS = '500';      // and so is one poll of its file list
  const gateway = require('../hub/core/gateway');

  // ---- 1. one conversation per torrent -------------------------------------------
  // A title page's warm and the viewer's press arrive together. They must not each open an add.
  engine.seen = {};
  engine.adds = 0;
  const magnet = 'magnet:?xt=urn:btih:' + 'a'.repeat(40);
  const warm = gateway.prepare({ kind: 'torrent', url: magnet }, 5000, null);
  const press = gateway.resolve({ kind: 'torrent', url: magnet }, { budgetMs: 5000 });
  const [one, two] = await Promise.all([warm, press]);
  assert.strictEqual(engine.adds, 1,
    'a warm and a Play press on the same torrent must share one engine conversation, saw ' + engine.adds + ' adds');
  assert.strictEqual(one.fileId, 1, 'the warm has to answer with the file inside the torrent');
  assert.ok(two.url && /\/play\/a{40}\/1$/.test(two.url), 'the press must get the served URL: ' + two.url);

  // ---- 2. a caller that runs out of patience must not blacklist the copy ---------
  engine.seen = {};
  engine.adds = 0;
  engine.filesAfterMs = 2500;   // the engine is slower than this press may be
  const magnet2 = 'magnet:?xt=urn:btih:' + 'b'.repeat(40);
  const first = await gateway.prepare({ kind: 'torrent', url: magnet2 }, 600, null);
  assert.ok(first && first.error, 'a press shorter than the engine answer has to be refused, not invented');
  assert.ok(/still arriving/i.test(first.error),
    'and it must be refused as "the engine is still working", got: ' + first.error);
  const second = await gateway.prepare({ kind: 'torrent', url: magnet2 }, 6000, null);
  assert.strictEqual(engine.adds, 1,
    'the retry must join the conversation that was already running, saw ' + engine.adds + ' adds');
  assert.ok(second && second.fileId === 1,
    'and the retry has to get the answer the engine produced, got: ' + JSON.stringify(second));

  // ---- 3. an engine that says nothing is not a verdict on the copy ---------------
  engine.seen = {};
  engine.adds = 0; engine.filesAfterMs = 0;
  const magnet3 = 'magnet:?xt=urn:btih:' + 'e'.repeat(40);
  engine.hang.add('e'.repeat(40));   // the engine accepts the torrent and stops answering about it
  const busy = await gateway.prepare({ kind: 'torrent', url: magnet3 }, 1200, null);
  assert.ok(busy && busy.error, 'a press against a silent engine has to be told it got no answer');
  engine.hang.delete('e'.repeat(40));
  const after = await gateway.prepare({ kind: 'torrent', url: magnet3 }, 8000, null);
  assert.strictEqual(engine.adds, 1,
    'the press after a silent engine must join the conversation already running, saw ' + engine.adds + ' adds');
  assert.ok(after && after.fileId === 1,
    'and it must get the answer, not a blacklist the silent press wrote: ' + JSON.stringify(after));

  // ---- 4. the engine is never stampeded -----------------------------------------
  engine.seen = {};
  engine.adds = 0; engine.addMax = 0; engine.addDelayMs = 300; engine.filesAfterMs = 0;
  await Promise.all([0, 1, 2, 3, 4, 5].map((i) =>
    gateway.prepare({ kind: 'torrent', url: 'magnet:?xt=urn:btih:' + String(i).repeat(40) },
      6000, null)));
  // The adds are fired in the background and never delay the poll, so give the semaphore a moment to
  // drain before counting them: that is the point of the change, not an accident of the test.
  await new Promise((r) => setTimeout(r, 1600));
  assert.strictEqual(engine.adds, 6, 'every torrent still gets its add');
  assert.ok(engine.addMax <= 2,
    'but no more than two adds run at once — the engine serialises them anyway; saw ' + engine.addMax);

  // ---- 4. the picker's shape -----------------------------------------------------
  const index = read('hub/index.js');
  assert.match(index, /await Promise\.all\(askers\.map\(async \(p\) =>/,
    'the addons must be asked for an episode copies together; asking them one after another costs the '
      + 'viewer the sum of their round trips');
  assert.match(index, /gateway\.prewarm\(queue\.map\(/,
    'the press must warm the engine on the copies it is about to walk, not only on the list the title '
      + 'page happened to show');
  assert.match(index, /coldEngine >= 2/,
    'two copies the engine has not read is a fact about the press; it must stop rather than ask the '
      + 'engine about every seat');
  assert.match(index, /log\('PLAY TIMING'/,
    'every press reports where its seconds went, so the next argument about startup cost is settled '
      + 'by the log');
  assert.match(index, /queue\.indexOf\(cand\) === 0 \? 4000 : 2500/,
    'the copy at the front of the walk gets the long look, because it is the one this press is most '
      + 'likely to play');
  assert.match(index, /queue\.indexOf\(cand\) === 0\s*\? Math\.max\(0, deadline - Date\.now\(\) - 900\)/,
    'and the front seat keeps the walk\'s time to measure with: six copies at a sixth of the budget '
      + 'each is less than this engine\'s first-byte time, so nothing gets proved about any of them');
  assert.match(index, /_streamId: showAddr/,
    'a series press must keep the show address\'s copies - the season packs a title page warmed - and '
      + 'tag them with the address they were published under, or the walk only ever asks the engine '
      + 'about per-episode magnets it has never met');
  assert.match(index, /episodeInLabel\(labelOf\(s\), ep\)\)\)/,
    'a season pack whose label names no episode must carry over from an episode-addressed tile: it is '
      + 'the copy the engine is most likely to have open, and dropping it sends the press back to the '
      + 'addons for a list it already had');
  assert.match(index, /- \(engineHas\(s\) \? 1 : 0\)/,
    'a copy the engine has already read must lead the seats; seated behind cold magnets it is never '
      + 'walked, because the walk stops after two copies the engine has not read');
  assert.match(index, /Number\(r\.fileSize\) < Number\(cand\.sizeBytes\)/,
    'the bitrate a link must carry is the file the engine named, not the whole season pack it lives in');
  assert.match(index, /const warmPack = packCopies\.some\(\(s\) => episodeInLabel\(labelOf\(s\), ep\)/,
    'a series press must ask the engine whether it already holds a show-address copy before spending '
      + 'the addon round trip on per-episode streams, and must not count a copy that names another '
      + 'episode as that warm copy');
  assert.match(index, /if \(warmPack\) \{[\s\S]*?const fill = Object\.assign\(\{\}, item, \{ sources: \[\] \}\)[\s\S]*?item\.sources = packCopies\.slice\(\);/,
    'and when it does, the press walks that copy now while the episode list fills in the background, '
      + 'on a separate item so the background fetch cannot mutate the list being walked');
  assert.match(index, /if \(m\.ms >= 3000\) noteNoByte\(/,
    'a copy that produced no byte over a real look must be recorded: the warm pack whose later '
      + 'episodes are unseeded otherwise spends the same four seconds on every press');
  assert.match(index, /clearNoByte\(\{ hash: r\.hash, fileId: r\.fileId \}\)/,
    'and a copy that later measures or serves bytes must lose the strike');
  assert.match(read('hub/core/gateway.js'), /function holds\(source\)[\s\S]*?k\.startsWith\(hash \+ ':'\)/,
    'the engine must be asked about the torrent, not one episode of it: a pack resolved for S02E35 is '
      + 'still the pack a press for S02E36 can start from');
  const gw = read('hub/core/gateway.js');
  assert.match(gw, /if \(!known\) fireAdd\(\);/,
    'the engine add has to be fired, not awaited: it can hold a socket for thirty seconds while the '
      + 'poll that answers in milliseconds waits behind it');
  assert.match(gw, /if \(!addFired && !sawFiles/,
    'and the poll has to re-fire it when a cached preparation outlived what the engine remembers');

  server.close();
  console.log('PLAY STARTUP COST GATE OK');
})().catch((e) => { server.close(); console.error(e); process.exit(1); });
