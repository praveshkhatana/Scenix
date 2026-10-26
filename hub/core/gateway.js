'use strict';
/*
 * core/gateway.js — the seam where a torrent becomes a seekable HTTP stream.
 *
 * Why this exists: a magnet needs a torrent engine, and a 2 GB 32-bit TV is a poor place to run one.
 * The machine that hosts the hub (x86, plenty of RAM, wired) is a good place. A gateway downloads
 * torrent pieces in playback order and serves them back as an ordinary HTTP byte stream with Range
 * support, so the TV, a web page and a phone all play the same file with no torrent code on the
 * client at all — which is also the only way a browser tab can play what an addon hands us.
 *
 * The engine is a separate process, spoken to over HTTP. It is deliberately NOT linked in: the
 * engines worth using are GPL, and this hub stays MIT and dependency-free. Point the hub at one with
 *
 *   GATEWAY=http                    ask the hub to serve torrents itself
 *   TORR_URL=http://127.0.0.1:8090  where that engine listens
 *
 * On the hub host that is localhost, and the TV never learns the engine exists: it gets a plain
 * http URL with mode "gateway" and seeks it like any other file.
 *
 * Measured on the household hub (TorrServer MatriX.147.1, a 31 GB 2160p torrent, 14 seeders):
 *   sequential start  2 MB in 0.5 s = 33.6 Mbps  — above any bitrate ladder we run
 *   cold seek 2 GB in 2 MB in 21 s  = 0.79 Mbps  — a far jump still pays the peer round trip
 * That asymmetry is why "Auto" may pick a torrent to start a film but must keep measuring before it
 * promises a seek. See rules/source_ranking.js.
 */

const http = require('http');
const { URL } = require('url');
// One-way dependency: the engine knows which file a torrent is, and only that makes it worth asking the
// file what languages it carries. core/media.js never asks about the engine.
const media = require('./media');

const MODE = String(process.env.GATEWAY || 'off').toLowerCase();
const ENGINE = String(process.env.TORR_URL || 'http://127.0.0.1:8090').replace(/\/+$/, '');
/** Same format as the hub's own lines, so `grep 'PROXY TTFB' hub.log` reads across both files. */
const log = (...a) => console.log(new Date().toISOString(), ...a);
// Only needed when the engine is reached through something other than its own address.
const PUBLIC_ENGINE = process.env.TORR_PUBLIC_URL ? String(process.env.TORR_PUBLIC_URL).replace(/\/+$/, '') : '';

// Metadata resolution is a round trip through the swarm; measured at ~3 s for a torrent this engine
// has seen before and longer for one it has not. The Play button can afford seconds — a film that
// starts 5 s late beats one that buffers every 20 s — but it must not be unbounded.
const PREPARE_BUDGET_MS = Number(process.env.GATEWAY_BUDGET_MS || 6000);
// A title page has all the time in the world; the Play button has none, so warming runs long.
const WARM_BUDGET_MS = Number(process.env.GATEWAY_WARM_MS || 15000);
const PROBE_TIMEOUT_MS = 1500;
// How long an engine reachability answer is trusted before it is asked again. The same value drives
// the background refresh, so a hub that starts with the engine already running answers its first Play
// press correctly instead of spending half a minute calling the engine "not checked".
const CHECK_TTL_MS = 30e3;

/** infoHash -> { fileId, fileName, fileSize, seeders, at } — what the engine told us about it. */
const prepared = new Map();
/** Engine reachability, refreshed on a delay rather than per request. */
let engineSeen = { ok: false, at: 0, note: 'not checked' };

const VIDEO_EXT = /\.(mkv|mp4|avi|ts|m2ts|mov|webm|mpg|mpeg)$/i;

/** Pull the 40-char info hash out of a magnet, a bare infoHash, or a hash-bearing URL. */
/**
 * The info hash of a torrent source, wherever the addon chose to put it. Addons are inconsistent on
 * purpose: some hand over a magnet in `url`, some in `link`, and some (Torrentio, after our own
 * ranking pass) leave both null and carry only `infoHash`. Every one of those names the same torrent.
 */
function hashOf(source) {
  if (!source) return null;
  const raw = String(source.url || source.link || source.magnet || '');
  const m = raw.match(/btih:([a-fA-F0-9]{40})/i);
  if (m) return m[1].toLowerCase();
  // The addon already parsed the magnet for us; a bare 40-hex is the hash itself.
  const direct = String(source.infoHash || source.hash || '');
  if (/^[a-fA-F0-9]{40}$/.test(direct)) return direct.toLowerCase();
  const h = raw.match(/\b([a-fA-F0-9]{40})\b/);
  return h ? h[1].toLowerCase() : null;
}

/** One GET against the engine. Never throws: a dead engine is a fact, not an exception. */
function engineGet(pathAndQuery, timeoutMs) {
  return new Promise((resolve) => {
    let url;
    try { url = new URL(ENGINE + pathAndQuery); } catch (e) { return resolve({ error: 'bad TORR_URL: ' + e.message }); }
    const req = http.get(url, { timeout: timeoutMs || PROBE_TIMEOUT_MS }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const body = Buffer.concat(chunks).toString('utf8');
        if (res.statusCode >= 400) return resolve({ error: 'engine HTTP ' + res.statusCode, body: body.slice(0, 200) });
        try { resolve({ json: JSON.parse(body) }); } catch (e) { resolve({ text: body.slice(0, 400) }); }
      });
    });
    req.on('timeout', () => { req.destroy(); resolve({ error: 'engine timeout' }); });
    req.on('error', (e) => resolve({ error: 'engine unreachable: ' + (e.code || e.message) }));
  });
}

/**
 * The engine reports a torrent's files in two places with two field names: `file_stats[].size` while
 * the torrent is loaded, and `data.TorrServer.Files[].length` in the stored record, which is the only
 * one present once the engine has idled the torrent out. Read both, so a warm hash answers instantly
 * and a cold one still answers.
 */
function filesOf(record) {
  if (!record) return [];
  const live = Array.isArray(record.file_stats) ? record.file_stats : [];
  if (live.length) return live;
  let data = record.data;
  if (typeof data === 'string') { try { data = JSON.parse(data); } catch (e) { data = null; } }
  const stored = (data && data.TorrServer && data.TorrServer.Files) || [];
  return Array.isArray(stored) ? stored : [];
}

/** Biggest video file, else biggest file — in a folder torrent the largest entry is the film.
 *
 * `want` is the episode the viewer asked for. Half the torrents for a series are season packs: one
 * torrent, eight or twenty-four files inside it. Picking the biggest from a pack used to mean pressing
 * Play on S01E04 and watching S01E01, because the longest episode is the largest file. So when we know
 * which episode was requested, the file whose name says that episode wins outright; only a pack with no
 * readable episode names falls back to size.
 */
function pickFile(files, want) {
  const sizeOf = (f) => Number(f && (f.size !== undefined ? f.size : f.length)) || 0;
  const usable = (files || []).filter((f) => f && sizeOf(f) > 10e6 && f.id !== undefined);
  if (!usable.length) return null;
  const videos = usable.filter((f) => VIDEO_EXT.test(String(f.path || '')));
  const pool = videos.length ? videos : usable;
  const s = Number(want && want.season); const e = Number(want && want.episode);
  if (s > 0 && e > 0) {
    const exact = pool.filter((f) => {
      const q = episodeOfPath(f.path);
      return q && q.season === s && q.episode === e;
    });
    if (exact.length) return exact.sort((a, b) => sizeOf(b) - sizeOf(a))[0];
  }
  return pool.sort((a, b) => sizeOf(b) - sizeOf(a))[0];
}

/** Read the episode number out of a filename: S01E04, s1e4, 1x04, x01x04, "Episode 4" as a last resort. */
function episodeOfPath(path) {
  const p = String(path || '').toLowerCase();
  let m = /s(\d{1,2})[\s._-]*e(?:p)?(\d{1,3})/.exec(p);
  if (!m) m = /(\d{1,2})x(\d{1,3})/.exec(p);
  if (!m) m = /x(\d{1,2})x(\d{1,3})/.exec(p);
  if (!m) m = /(?:^|[^\d])(?:episode|ep|cap)[ ._-]?(\d{1,3})(?:$|[^\d])/.exec(p);
  if (!m) return null;
  return m.length === 3 ? { season: Number(m[1]), episode: Number(m[2]) } : { season: 0, episode: Number(m[1]) };
}

/** One POST against the engine. Never throws: a dead engine is a fact, not an exception. */
function enginePost(path, body, timeoutMs) {
  return new Promise((resolve) => {
    let url, payload;
    try { url = new URL(ENGINE + path); payload = Buffer.from(JSON.stringify(body)); } catch (e) { return resolve({ error: 'bad TORR_URL: ' + e.message }); }
    const req = http.request(url, {
      method: 'POST',
      timeout: timeoutMs || PROBE_TIMEOUT_MS,
      headers: { 'content-type': 'application/json', 'content-length': payload.length },
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        if (res.statusCode >= 400) return resolve({ error: 'engine HTTP ' + res.statusCode, body: text.slice(0, 200) });
        try { resolve({ json: JSON.parse(text) }); } catch (e) { resolve({ text: text.slice(0, 400) }); }
      });
    });
    req.on('timeout', () => { req.destroy(); resolve({ error: 'engine timeout' }); });
    req.on('error', (e) => resolve({ error: 'engine unreachable: ' + (e.code || e.message) }));
    req.end(payload);
  });
}

/**
 * Torrents a viewer is watching, kept open on the engine.
 *
 * TorrServer idle-closes a torrent `TorrentDisconnectTimeout` (600 s here) after its last reader, and
 * the engine log shows it doing exactly that ten minutes into a film: `Torrent close by timeout …`
 * followed by `Close cache for: …`. The player is still reading, but the reader on the hub's side is
 * the one the engine counts, and between the hub's probe and the player's first bytes there is a gap
 * the engine treats as disinterest. Re-adding is idempotent and a one-byte read counts as activity, so
 * anything recently handed out gets both on a timer. Without this the picture stops in the middle of a
 * scene and the household blames the internet.
 */
const warm = new Map(); // hash -> { link, fileId, until }
const WARM_KEEP_MS = Number(process.env.GATEWAY_KEEPWARM_MS || 20 * 60e3);
const WARM_INTERVAL_MS = Math.max(15e3, Number(process.env.GATEWAY_KEEPWARM_INTERVAL_MS || 120e3));
// The real read that keeps a reader warm. Small enough to be free on a slow swarm, big enough that the
// engine's own cache window (PreloadCache/ReaderReadAHead) has something in it to serve the next seek.
const TOUCH_BYTES = Math.max(1, Number(process.env.GATEWAY_TOUCH_BYTES || 262144));
const TOUCH_TIMEOUT_MS = Math.max(2000, Number(process.env.GATEWAY_TOUCH_TIMEOUT_MS || 25000));
function keepWarm(hash, link, fileId) {
  if (!hash || !link) return;
  warm.set(hash, { link, fileId, until: Date.now() + WARM_KEEP_MS });
}

/**
 * Who is reading a torrent through this hub right now, keyed `hash:fileId`.
 *
 * The engine counts readers, not connections, and it keeps one sequential cache per torrent. That makes
 * the heartbeat a hazard: a second reader at offset 0 during playback drags that cache backwards and the
 * viewer starves at a point they had already passed (measured: two concurrent `/play` readers on one
 * torrent, each cut off at ~3.3 MB). So the heartbeat has to know when it is redundant, and the only
 * honest way to know is to count the reads this hub is itself serving.
 */
const reading = new Map(); // 'hash:fileId' -> count
function rkey(hash, fileId) { return String(hash) + ':' + String(fileId); }
function readBegin(hash, fileId) {
  if (!hash) return () => {};
  const k = rkey(hash, fileId);
  reading.set(k, (reading.get(k) || 0) + 1);
  return () => readEnd(hash, fileId);
}
function readEnd(hash, fileId) {
  if (!hash) return;
  const k = rkey(hash, fileId);
  const n = (reading.get(k) || 0) - 1;
  if (n > 0) reading.set(k, n); else reading.delete(k);
}
/** Any open read on this hash, whatever file inside it. */
function isReading(hash) {
  if (!hash) return false;
  const prefix = String(hash) + ':';
  for (const k of reading.keys()) if (k.startsWith(prefix)) return true;
  return false;
}

/**
 * A small real read: re-open the torrent and pull `bytes` of it, then hang up.
 *
 * `Range: bytes=0-0` looked clever and was not: the engine counted the request, and in practice a
 * zero-length read did not reliably bring a timed-out torrent back with its cache attached. Reading
 * 256 KB is the same order of cost and leaves those bytes in the engine's cache, which is also what a
 * player pressing play a second later wants. Latency is logged the same way a real read is, so one grep
 * of `PROXY TTFB` shows every engine first-byte measurement this box has ever made.
 */
function touchPlay(hash, fileId, bytes) {
  if (!hash || fileId === undefined || fileId === null) return;
  const want = Math.max(1, Number(bytes) || TOUCH_BYTES);
  const t0 = Date.now();
  try {
    const req = http.get(ENGINE + '/play/' + hash + '/' + fileId,
      { timeout: TOUCH_TIMEOUT_MS, headers: { Range: 'bytes=0-' + (want - 1) } }, (res) => {
        let got = 0;
        res.on('data', (c) => {
          got += c.length;
          if (got >= want) res.destroy(); // all we asked for; do not sit on the reader
        });
        res.on('close', () => {
          log('PROXY TTFB', 'hash=' + String(hash).slice(0, 12), 'file=' + fileId,
            'ms=' + (Date.now() - t0), 'bytes=' + got, 'note=keepwarm');
        });
        res.on('error', () => {});
      });
    req.on('error', (e) => log('PROXY FAIL', 'phase=headers', 'cause=keepwarm',
      'elapsed=' + (Date.now() - t0) + 'ms', 'hash=' + String(hash).slice(0, 12), e.message));
    req.on('timeout', () => {
      req.destroy();
      log('PROXY FAIL', 'phase=headers', 'cause=keepwarm-timeout',
        'elapsed=' + (Date.now() - t0) + 'ms', 'hash=' + String(hash).slice(0, 12));
      noteCold(hash, Date.now() - t0);
    });
  } catch (e) { /* engine down; the next resolve() reports it properly */ }
}

/**
 * Hashes the engine has recently failed to answer, so the next Play press can say so honestly instead
 * of handing out a link that will sit on a dead socket for the length of the client's patience.
 */
const cold = new Map(); // hash -> { until, waitedMs, at }
const COLD_MS = Number(process.env.GATEWAY_COLD_MS || 90000);
function noteCold(hash, waitedMs) {
  if (!hash) return;
  cold.set(String(hash), { until: Date.now() + COLD_MS, waitedMs: Math.round(waitedMs || 0), at: Date.now() });
}
function isCold(hash) {
  if (!hash) return null;
  const c = cold.get(String(hash));
  if (!c) return null;
  if (c.until < Date.now()) { cold.delete(String(hash)); return null; }
  return c;
}

setInterval(() => {
  const now = Date.now();
  for (const [hash, w] of warm) {
    if (w.until < now) { warm.delete(hash); continue; }
    enginePost('/torrents', { action: 'add', link: w.link, save_to_db: true }, 3000);
    // A viewer mid-film already has a reader on this torrent, and a second reader at offset 0 makes the
    // engine's sequential cache jump backwards — the player then starves at a point it had already
    // passed. Their own connection is the heartbeat, so `add` alone is enough while they watch.
    // Nobody watching is the case that used to break: the engine closed the torrent 600 s after the
    // last reader, and the next press paid a cold reopen measured at 19-108 s. That one gets a real read.
    if (!isReading(hash)) touchPlay(hash, w.fileId, TOUCH_BYTES);
  }
}, WARM_INTERVAL_MS).unref();

const PUMP_BYTES = Math.min(64e6, Math.max(0, Number(process.env.GATEWAY_PUMP_MB || 24) * 1048576));
/**
 * Pull the first chunk of a file into the engine's cache the moment we hand a URL to a viewer.
 *
 * A cold torrent answers its first megabytes at 0.3 Mbps while it is still handshaking with the rest
 * of the swarm, so the player gets ~28 s of picture, drains the buffer and stalls — which is exactly
 * what the viewer reads as "this app buffers all the time". The engine caches whatever a reader asked
 * for, so one sequential read from offset 0 buys the player a cushion before it starts. Fire and
 * forget, capped by the file size and by the byte target.
 */
function pump(hash, fileId, fileSize) {
  if (!hash || fileId === undefined) return;
  const target = Math.min(PUMP_BYTES, Number(fileSize) > 0 ? Number(fileSize) : PUMP_BYTES);
  if (target < 1048576) return;
  try {
    const req = http.get(ENGINE + '/play/' + hash + '/' + fileId,
      { timeout: 60e3, headers: { Range: 'bytes=0-' + (target - 1) } }, (res) => {
        let got = 0;
        res.on('data', (c) => { got += c.length; if (got >= target) { res.destroy(); } });
        res.on('error', () => {});
      });
    req.on('error', () => {});
    req.on('timeout', () => req.destroy());
  } catch (e) { /* the engine is down; resolve() has already said so in words */ }
}

/**
 * Ask the engine to take a torrent and wait until it names the files inside.
 *
 * Two engine facts that shape this. `GET /stream?link=…` blocks until the swarm has answered, which
 * is far too slow to sit in front of a Play button; `POST /torrents {action:"add"}` followed by
 * `{action:"get"}` returns at once and metadata lands a few seconds later, so that is what we poll.
 * And a torrent that contains a folder numbers its files from 1, because index 0 is the folder —
 * asking `/play/<hash>/0` on such a torrent is a 404, so the file id always comes from the engine.
 */
async function prepare(source, budgetMs, want) {
  const hash = hashOf(source);
  if (!hash) return null;
  // The chosen file inside a season pack depends on which episode was asked for, so the cache entry is
  // keyed by that too — otherwise E04 plays whatever file E01 cached.
  const ws = Number(want && want.season); const we = Number(want && want.episode);
  const ckey = ws > 0 && we > 0 ? hash + ':' + ws + ':' + we : hash;
  const hit = prepared.get(ckey);
  if (hit && Date.now() - hit.at < 6 * 3600e3) {
    // The file list stays valid for hours, the engine's *open torrent* does not: it closes 600 s after
    // its last reader. Serving a cached `/play/<hash>/<id>` for a torrent the engine has already shut
    // down is how a player ends up sniffing an empty body. Wake it, and keep it warm from now on.
    // The wake-up read is skipped when something is already reading this hash — then the torrent is by
    // definition open, and a fresh reader at offset 0 would only knock the sequential cache around.
    if (hit.link) {
      keepWarm(hash, hit.link, hit.fileId);
      if (!isReading(hash)) touchPlay(hash, hit.fileId, TOUCH_BYTES);
    }
    return hit;
  }
  const failed = prepared.get('miss:' + hash);
  if (failed && Date.now() - failed.at < 10 * 60e3) return null;

  // Adding is idempotent; doing it again on a live torrent is free. Prefer the magnet when the addon
  // gave us one (it carries trackers), and fall back to the bare hash, which the engine resolves over
  // the DHT — slower, but it works.
  const link = String(source.url || source.link || source.magnet || '').trim() || hash;
  await enginePost('/torrents', { action: 'add', link, save_to_db: true }, 3000);

  const deadline = Date.now() + (budgetMs || PREPARE_BUDGET_MS);
  let lastError = 'metadata not resolved';
  do {
    const r = await enginePost('/torrents', { action: 'get', hash }, 2000);
    if (r.json) {
      const files = filesOf(r.json);
      const chosen = pickFile(files, want);
      if (chosen) {
        const sizeOf = (f) => Number(f.size !== undefined ? f.size : f.length) || 0;
        const out = {
          hash,
          fileId: chosen.id,
          fileName: String(chosen.path || '').split('/').pop(),
          fileSize: sizeOf(chosen),
          // The engine's real field is `total_peers` (this API version has no `connected_seeders`), and
          // it is the number that predicts whether a stream will actually flow. Read from the live
          // record rather than the addon's catalogue figure, which is minutes stale.
          seeders: Number(r.json.total_peers || 0),
          totalSize: Number(r.json.torrent_size || 0) || undefined,
          state: String(r.json.stat_string || ''),
          link, // kept so the warm timer can re-add this torrent without asking the addon again
          at: Date.now(),
        };
        prepared.set(ckey, out);
        keepWarm(hash, link, chosen.id);
        return out;
      }
      lastError = (r.json.stat_string || 'unknown') + ', ' + files.length + ' files';
    } else {
      lastError = r.error || 'engine said nothing';
      if (/unreachable|timeout|bad TORR_URL/.test(lastError)) break;
    }
    await new Promise((res) => setTimeout(res, 600));
  } while (Date.now() < deadline);

  prepared.set('miss:' + hash, { error: lastError, at: Date.now() });
  return { hash, error: lastError };
}

/**
 * Get the engine started on torrents before anyone presses Play — the hub knows the candidate
 * sources when a title page opens, and the swarm needs a few seconds that the Play button does not
 * have. Fire and forget; results land in the same cache prepare() reads.
 */
function prewarm(sources, want) {
  if (MODE !== 'http') return;
  for (const s of (sources || []).slice(0, 4)) {
    if (s && s.kind === 'torrent') {
      // `want` is the season/episode the viewer is actually looking at. Without it a season pack resolves
      // to whichever file the engine happens to list first — measured on a 7-season pack where the title
      // page probed file 72 and Play then chose file 1, so every language fact the hub had learned
      // belonged to a different episode than the one being offered.
      prepare(s, WARM_BUDGET_MS, want)
        // Learning which file is the episode costs seconds the Play button does not have; asking the file
        // what languages it carries costs more still (core/media.js). Both are background work off the
        // title page, and the track probe is only worth starting once the file inside is known.
        .then((r) => {
          if (r && r.hash && r.fileId !== undefined) {
            media.prewarm([{ hash: r.hash, fileId: r.fileId, url: enginePlayUrl(r.hash, r.fileId) }]);
          }
        })
        .catch(() => {});
    }
  }
}

/** The engine's own address for a copy: loopback, for processes running on this box (ffprobe, ffmpeg). */
function enginePlayUrl(hash, fileId) {
  return ENGINE + '/play/' + hash + '/' + fileId;
}

/** The two numbers a `/play/<hash>/<fileId>` URL carries, or nulls when it is some other URL. */
function parsePlayUrl(u) {
  const m = /\/play\/([0-9a-fA-F]{40})\/(\d+)/.exec(String(u || ''));
  return m ? { hash: m[1].toLowerCase(), fileId: Number(m[2]) } : { hash: null, fileId: null };
}

/** The prepared answer for a source, from the cache only: never asks the engine, never waits. */
function peekPrepared(source, want) {
  if (MODE !== 'http' || !source) return null;
  const hash = hashOf(source);
  if (!hash) return null;
  const ws = Number(want && want.season);
  const we = Number(want && want.episode);
  const ckey = ws > 0 && we > 0 ? hash + ':' + ws + ':' + we : hash;
  let hit = prepared.get(ckey) || prepared.get(hash);
  if (!hit && !(ws > 0 && we > 0)) {
    // A season pack is keyed by the episode it was resolved for (`hash:1:4`), and a title page asks
    // about the show. Any file inside that torrent answers "which copy is this", which is all the
    // track cache needs. A Play press names the episode and gets the exact key above instead.
    for (const [k, v] of prepared) {
      if (k.startsWith(hash + ':')) { hit = v; break; }
    }
  }
  return hit && hit.fileId !== undefined && Date.now() - hit.at < 6 * 3600e3 ? hit : null;
}

/**
 * A URL the *client* can open. `TORR_URL` is what this process uses, and on a hub that shares its box
 * with the engine that is loopback — perfect for us, meaningless to a TV. When the engine is local we
 * hand out the same host the client reached us on (LAN address, Tailscale address, whatever it was);
 * when the engine is another machine we hand out that machine's address as configured. `TORR_PUBLIC_URL`
 * overrides both, for an engine behind a reverse proxy or a different port.
 */
function clientBaseUrl(clientHost) {
  if (PUBLIC_ENGINE) return PUBLIC_ENGINE;
  const u = new URL(ENGINE);
  // Any 127/8 loopback form, not just the literal string: a hostname is the whole match, so the
  // alternation has to stay inside the group and the anchor has to sit outside it.
  const local = u.hostname === 'localhost' || u.hostname === '::1' || u.hostname === '[::1]'
    || u.hostname === '0.0.0.0' || /^127\.\d+\.\d+\.\d+$/.test(u.hostname);
  if (clientHost && local) {
    try { u.hostname = new URL('http://' + clientHost).hostname; } catch (e) { /* keep the configured host */ }
  }
  return (u.protocol + '//' + u.host).replace(/\/+$/, '');
}

/**
 * Turn a torrent source into a URL the client can simply play. Returns null when there is nothing to
 * gateway (off, not a torrent, or the engine could not name the file) — the normal case, in which the
 * caller hands over the magnet exactly as before.
 *
 * Async because the file id comes from the engine.
 */
/**
 * The gateway answer for one source, with the reason when there is no answer.
 *
 * A bare `null` covers four very different situations — the gateway is off, the engine is down, the
 * addon never handed us a hash, or TorrServer has not finished reading this torrent's metadata — and
 * one log line that blames "no torrent engine" for all four is how an operator ends up restarting a
 * service that was fine. It also carries what the engine learned about the torrent: `seeders` is
 * `connected_seeders` from a live engine, which is the only honest preview of whether this link can
 * put bytes on the wire before a viewer finds out.
 */
async function resolve(source, opts = {}) {
  if (MODE === 'off') return { url: null, reason: 'gateway off' };
  if (MODE !== 'http') return { url: null, reason: 'unknown GATEWAY value "' + MODE + '": expected off or http' };
  if (!source) return { url: null, reason: 'no source' };
  if (source.kind !== 'torrent') return { url: null, reason: 'not a torrent source' };
  if (!hashOf(source)) return { url: null, reason: 'addon gave no magnet or info hash' };
  // Ask the engine directly rather than trusting the 30s cache: right after a deploy the cache still
  // says "not checked", and a TV handed a magnet cannot play anything at all.
  if (!(await ready())) {
    return { url: null, reason: 'engine not answering at ' + ENGINE + (engineSeen.note ? ' (' + engineSeen.note + ')' : '') };
  }
  const info = await prepare(source, opts.budgetMs, opts.want);
  if (!info) return { url: null, reason: 'no info hash to serve' };
  if (info.error || info.fileId === undefined) {
    return { url: null, reason: 'engine has not read this torrent (' + (info.error || 'no file list') + ')' };
  }
  return {
    url: clientBaseUrl(opts.clientHost) + '/play/' + info.hash + '/' + info.fileId,
    hash: info.hash,
    fileId: info.fileId,
    fileName: info.fileName,
    fileSize: info.fileSize,
    seeders: info.seeders,
    state: info.state,
  };
}

async function gatewayUrl(source, opts = {}) {
  return (await resolve(source, opts)).url;
}

function statusNote() {
  if (MODE === 'off') return 'gateway off: torrents are handed to the client as magnets';
  if (MODE === 'http') {
    return engineSeen.ok ? 'torrents served as seekable HTTP by the engine'
      : 'gateway on, engine not answering at ' + ENGINE + (engineSeen.note ? ' (' + engineSeen.note + ')' : '');
  }
  return 'unknown GATEWAY value "' + MODE + '": expected off or http';
}

/** Facts for /healthz, so an operator sees a dead engine instead of guessing. */
function status() {
  const now = Date.now();
  let coldCount = 0;
  for (const [, c] of cold) if (c.until > now) coldCount++;
  return {
    mode: MODE,
    enabled: MODE === 'http',
    engine: MODE === 'http' ? ENGINE : null,
    reachable: Date.now() - engineSeen.at < CHECK_TTL_MS ? engineSeen.ok : null,
    note: statusNote(),
    prepared: prepared.size,
    // The keep-warm answer to "why did the picture stop mid-scene": which torrents are being held open,
    // which are being read by a viewer right now, and which the engine recently failed to answer.
    warmHashes: warm.size,
    warmIntervalMs: WARM_INTERVAL_MS,
    warmKeepMs: WARM_KEEP_MS,
    touchBytes: TOUCH_BYTES,
    reading: Array.from(reading.keys()).map((k) => k.split(':')[0].slice(0, 12)),
    coldHashes: coldCount,
  };
}

/** Refresh engine reachability in the background; callers read the cache, never block on it. */
async function checkEngine() {
  if (MODE !== 'http') { engineSeen = { ok: false, at: Date.now(), note: 'off' }; return false; }
  const r = await engineGet('/', PROBE_TIMEOUT_MS);
  engineSeen = { ok: !r.error, at: Date.now(), note: r.error || '' };
  return engineSeen.ok;
}

/**
 * Whether the engine is up, resolved *inside* the call.
 *
 * `available()` reads a cache refreshed every 30s, which leaves a hole the size of that interval at
 * every boot: a hub that has just started answers "engine not answering (not checked)" and hands the
 * TV an unplayable magnet even though TorrServer is running two ports away. A caller that is about to
 * offer someone a film can afford the one round trip it takes to know.
 */
async function ready() {
  if (MODE !== 'http') return false;
  if (!engineSeen.ok && Date.now() - engineSeen.at > CHECK_TTL_MS) await checkEngine().catch(() => {});
  return engineSeen.ok === true;
}

/** Whether the ranking rules may treat torrents as play-ready HTTP. Cheap, cached, never throws. */
function available() {
  if (MODE !== 'http') return false;
  if (Date.now() - engineSeen.at > CHECK_TTL_MS) checkEngine().catch(() => {});
  return engineSeen.ok !== false;
}

const timer = setInterval(() => { checkEngine().catch(() => {}); }, CHECK_TTL_MS);
if (timer.unref) timer.unref();
checkEngine().catch(() => {}); // warm the answer before the first Play press needs it

module.exports = {
  MODE,
  status,
  available,
  ready,
  prepare,
  prewarm,
  pump,
  resolve,
  gatewayUrl,
  hashOf,
  pickFile,
  checkEngine,
  enginePlayUrl,
  parsePlayUrl,
  peekPrepared,
  readBegin,
  readEnd,
  isReading,
  noteCold,
  isCold,
  ENGINE,
  notEnabledError() { return { error: 'gateway not enabled', gateway: status() }; },
};
