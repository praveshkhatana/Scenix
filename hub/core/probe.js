'use strict';
/*
 * core/probe.js — has this URL ACTUALLY been touched, and how fast did it answer?
 *
 * Everything else in this hub ranks sources from metadata: a label that says 1080p, a size, a language,
 * a seeder emoji. Metadata is a promise. The viewer is the only thing that ever found out whether a link
 * was alive, which is why the complaint was "look around multiple links before playing anything" and "so
 * much buffering all the place". A community app this household likes solves it by checking the stream
 * before handing it over (its API has /api/stream AND /api/stream/check, and it names its sources with
 * codenames so the player can retry a different one). This module is the hub's version of that check.
 *
 * One probe = a ranged GET of the first few hundred KB, aborted the moment the budget is spent:
 *   { ok, status, latencyMs, bytes, kbps, rateReliable, rateFloor, contentType, seekable, error, checkedAt }
 * `latencyMs` is time-to-first-byte (what the player waits before anything is on screen) and `kbps` is
 * the rate measured while reading those bytes (what decides whether playback stalls). Both are the
 * numbers the viewer feels; a source that answers in 200 ms at 6 Mbps is worth more than a "bigger"
 * file that answers in 4 s at 0.4 Mbps, and now the ranking rule can know that.
 *
 * What `kbps` is allowed to mean, because dividing bytes by a millisecond is how nonsense gets made:
 *   - `rateReliable: true` — the sample was either timed over a window of PROBE_MIN_SAMPLE_MS or more,
 *     or the entire requested sample arrived, so the number describes this connection;
 *   - `rateFloor: true` — a sub-case of the above: the sample landed faster than the window could be
 *     opened, so the divisor was clamped up and `kbps` is a proven MINIMUM ("at least 33 Mbps"), never
 *     a measurement. On a LAN, a real value computed from a 0.9 ms window reads 9.5 Tbps and means less;
 *   - `rateReliable: false` — nothing about speed may be claimed. An HLS master playlist is 200 bytes
 *     and is over in a millisecond: that answered, it did not stream.
 *
 * Cost control, because this sits in a request path:
 *   - results are cached in data/probe-cache.json keyed by a hash of url + the headers we replay;
 *     ok answers are trusted for PROBE_OK_TTL_MS (12 min), failures are re-tried after PROBE_FAIL_TTL_MS
 *     (60 s) — a dead link coming back to life should not cost the household an evening;
 *   - concurrent lookups of the same URL are coalesced into one request;
 *   - a global cap (PROBE_CONCURRENCY, 4) means two people browsing at once never hammer one addon;
 *   - probeAll() waits at most PROBE_BUDGET_MS (1.5 s) for the batch. Anything that has not answered
 *     when the budget is spent is simply `null` in the result AND keeps running in the background, so
 *     the next view finds it warm. A page view never waits longer than that, whatever the internet does.
 *   - nothing here throws: a probe that cannot answer contributes nothing, and the source keeps the
 *     score the metadata ranking gave it. With PROBE_ENABLED=0 no measurement is taken and none of the
 *     probe fields or weights appear anywhere: those responses are exactly as they were before this
 *     module existed.
 *
 * No npm, no framework: http/https from Node, JSON file, atomic rename.
 * Server address and household identifiers are never written here (repo rule).
 */

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { URL } = require('url');

const ROOT = path.join(__dirname, '..');
const DATA_DIR = process.env.DATA_DIR || path.join(ROOT, 'data');
const FILE = process.env.PROBE_FILE || path.join(DATA_DIR, 'probe-cache.json');

const num = (v, d) => (Number.isFinite(Number(v)) && Number(v) > 0 ? Number(v) : d);
const OFF = ['0', 'false', 'off', 'no'];

const ENABLED = !OFF.includes(String(process.env.PROBE_ENABLED === undefined ? '1' : process.env.PROBE_ENABLED).toLowerCase());
// 256 KB is enough to time a stream: at even 1 Mbps that is two seconds of media, and a source that
// cannot push 256 KB through in a couple of seconds is a source the viewer is going to buffer on.
const PROBE_BYTES = Math.min(4 * 1024 * 1024, Math.max(8192, num(process.env.PROBE_BYTES, 256 * 1024)));
// A rate measured over 5 ms is not a rate. Keep reading until this much time has actually passed, so a
// fast CDN and a 0.5x-realtime one are told apart instead of both looking instantaneous.
const MIN_SAMPLE_MS = Math.min(2000, Math.max(50, num(process.env.PROBE_MIN_SAMPLE_MS, 250)));
// ... and never read more than this much, however fast the pipe is (4 x the sample size).
const SAMPLE_BYTES_MAX = PROBE_BYTES * Math.min(8, num(process.env.PROBE_SAMPLE_MULTIPLIER, 4));
// Slightly under PROBE_BUDGET_MS on purpose: a source that is merely SLOW must still be measured inside
// the request path, because "this one streams at 0.8 Mbps" is exactly the answer the viewer needs.
const PROBE_TIMEOUT_MS = num(process.env.PROBE_TIMEOUT_MS, 1200);
const OK_TTL_MS = num(process.env.PROBE_OK_TTL_MS, 12 * 60 * 1000); // a working link is believed for a while
const FAIL_TTL_MS = num(process.env.PROBE_FAIL_TTL_MS, 60 * 1000); // a dead link is a rumour for a minute
const CONCURRENCY = Math.min(16, Math.max(1, num(process.env.PROBE_CONCURRENCY, 4)));
const BUDGET_MS = num(process.env.PROBE_BUDGET_MS, 1500); // a request never waits longer than this
const CACHE_MAX = num(process.env.PROBE_CACHE_MAX, 600); // entries kept; oldest are pruned on save
const MAX_REDIRECTS = 3;
const UA = process.env.ADDON_UA || 'Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 ScenixHub/0.1';

const log = (...a) => console.log(new Date().toISOString(), ...a);

// ---- persistence --------------------------------------------------------------------------------

/** { entries: { key: {url, ok, status, latencyMs, bytes, kbps, contentType, seekable, error, checkedAt} }, updatedAt } */
let state = load();
let saveTimer = null;

function load() {
  try {
    const raw = JSON.parse(fs.readFileSync(FILE, 'utf8'));
    const entries = raw && typeof raw.entries === 'object' ? raw.entries : {};
    return { entries, updatedAt: Number(raw && raw.updatedAt) || 0 };
  } catch (e) {
    if (e.code !== 'ENOENT') log('PROBE load failed', e.message);
    return { entries: {}, updatedAt: 0 };
  }
}

function save() {
  try {
    prune();
    fs.mkdirSync(path.dirname(FILE), { recursive: true });
    const tmp = FILE + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(state, null, 1));
    fs.renameSync(tmp, FILE);
  } catch (e) {
    log('PROBE save failed', e.message); // losing it costs a re-probe, nothing else
  }
}

/** Keep the file small and honest: nothing older than a generous multiple of the TTL, newest CACHE_MAX. */
function prune() {
  const horizon = Math.max(OK_TTL_MS, FAIL_TTL_MS) * 20;
  const now = Date.now();
  let list = Object.entries(state.entries).filter(([, v]) => v && now - (Number(v.checkedAt) || 0) <= horizon);
  if (list.length > CACHE_MAX) {
    list.sort((a, b) => (Number(b[1].checkedAt) || 0) - (Number(a[1].checkedAt) || 0));
    list = list.slice(0, CACHE_MAX);
  }
  state.entries = Object.fromEntries(list);
}

/** Coalesce writes: a home screen touching 40 links must not mean 40 file writes. */
function scheduleSave() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    save();
  }, 1000);
  if (saveTimer.unref) saveTimer.unref();
}

// ---- the key ------------------------------------------------------------------------------------

/** Stable short hash, used for the cache key and for the source ids the app passes back as `exclude`. */
function hash(...parts) {
  return crypto.createHash('sha1').update(parts.map((p) => String(p == null ? '' : p)).join('\n')).digest('hex');
}

/** The headers change the answer (a Referer-gated CDN), so they are part of the key. */
function keyFor(url, headers) {
  const h = Object.entries(headers || {})
    .filter(([k]) => !/^host|connection|range|accept-encoding$/i.test(k))
    .map(([k, v]) => k.toLowerCase() + ':' + v)
    .sort()
    .join(';');
  return hash(url, h).slice(0, 16);
}

/** Only a plain http(s) URL can be touched. A magnet is a promise about a swarm, not an endpoint. */
function probeable(url) {
  return typeof url === 'string' && /^https?:\/\//i.test(url);
}

/**
 * A stable id for one candidate source, so the app can name the exact link it wants left out
 * (`POST /app/v1/switch {exclude:["src…"]}`) instead of matching on a label two addons worded alike.
 * It hashes what identifies the source within one title - which addon produced it, what kind of thing
 * it is, its quality/size/label and its URL - so the same source keeps the same id across requests
 * while the addon cache holds it, and a different feed never collides with it.
 */
function sourceId(source) {
  if (!source || typeof source !== 'object') return null;
  return 'src' + hash(
    source._addon, source.provider, source.kind, source.quality,
    source.sizeBytes, source.label, source._url
  ).slice(0, 10);
}

// ---- one probe ------------------------------------------------------------------------------------

/**
 * GET the first `maxBytes` of `url`, with a hard wall-clock cap. We always stop the transfer ourselves
 * (that is the point), so "aborted" is a normal outcome and never means failure: what matters is that
 * the server answered with 2xx and started moving real media bytes.
 * An HTML page answering 200 (a parked domain, a captcha, an addon's landing page) is NOT a stream —
 * that verdict is the whole reason this module exists.
 */
function probeOne(url, headers = {}, { timeoutMs = PROBE_TIMEOUT_MS, maxBytes = PROBE_BYTES } = {}) {
  // hrtime, not Date.now: a transfer over a fast pipe finishes in well under a millisecond, and a
  // clock that cannot see it reports a 0 ms window, which would report the speed as zero.
  const t0 = process.hrtime.bigint();
  const ms = (big) => Number(big) / 1e6; // nanoseconds -> milliseconds
  const rnd = (v) => Math.round(v * 10) / 10; // 0.1 ms resolution is plenty
  return new Promise((resolve) => {
    let settled = false;
    let req = null;
    let status = null;
    let contentType = null;
    let seekable = false;
    let bytes = 0;
    let head = '';
    let ttfb = null; // hrtime mark of the response headers
    let firstByte = null; // hrtime mark of the first body byte

    const result = (extra) => {
      const transferMs = firstByte ? ms(process.hrtime.bigint() - firstByte) : 0;
      // A rate needs a window worth dividing by. Two different things can make a number here:
      //   - the transfer ran long enough to time directly (transferMs >= MIN_SAMPLE_MS): a measurement;
      //   - the whole sample arrived before that window closed (bytes >= maxBytes in 0.9 ms on a LAN):
      //     then the rate is a LOWER BOUND — 1 MB inside 0.9 ms is also 1 MB inside 250 ms, so clamp the
      //     divisor up and report what can be proved rather than 9.5 Tbps of rounding error;
      //   - a few hundred bytes arrived and the response simply ended (an HLS master playlist): that is
      //     a completed request, not a stream, so no speed may be claimed from it at all.
      const timed = transferMs >= MIN_SAMPLE_MS;
      const fullSample = bytes >= maxBytes;
      const rateReliable = bytes > 0 && (timed || fullSample);
      const rateFloor = rateReliable && !timed; // true: the number below is a "at least", not a value
      const denom = rateFloor ? MIN_SAMPLE_MS : transferMs;
      const kbps = denom > 0 ? (bytes * 8) / denom : 0; // bytes*8 bits over denom/1000 s, /1000 = kbps
      return {
        ok: false,
        status,
        latencyMs: ttfb == null ? null : rnd(ms(ttfb - t0)),
        bytes,
        kbps: Math.round(kbps * 10) / 10,
        rateReliable,
        rateFloor,
        contentType,
        seekable,
        sampleMs: rnd(transferMs), // how long the rate was measured over — honesty about the number
        checkedAt: Date.now(),
        ...extra,
      };
    };
    const finish = (extra) => {
      if (settled) return;
      settled = true;
      clearTimeout(hard);
      if (req) { try { req.destroy(); } catch (e) { /* already gone */ } }
      resolve(result(extra || {}));
    };

    // Our own budget. If bytes already moved, the measurement we have IS the answer — the abort is ours,
    // not the server refusing. If nothing moved, it is a timeout.
    const hard = setTimeout(() => {
      if (bytes > 0) finish({ ok: status >= 200 && status < 300, error: null, partial: true });
      else if (ttfb != null) finish({ ok: false, error: 'headers, then silence: no bytes in ' + timeoutMs + 'ms' });
      else finish({ ok: false, error: 'no answer in ' + timeoutMs + 'ms' });
    }, timeoutMs);
    if (hard.unref) hard.unref();

    const walk = (target, redirectsLeft) => {
      let mod;
      try {
        mod = new URL(target).protocol === 'https:' ? https : http;
      } catch (e) {
        return finish({ ok: false, error: 'bad url' });
      }
      req = mod.get(target, {
        headers: { 'User-Agent': UA, Accept: '*/*', 'Accept-Encoding': 'identity', Range: 'bytes=0-' + (maxBytes - 1), ...headers },
        timeout: timeoutMs,
      }, (res) => {
        status = res.statusCode;
        if (status >= 300 && status < 400 && res.headers.location && redirectsLeft > 0) {
          res.resume();
          return walk(new URL(res.headers.location, target).toString(), redirectsLeft - 1);
        }
        ttfb = process.hrtime.bigint(); // every answer is timed, including a 404: "dead in 90 ms" is useful
        if (status >= 400) {
          res.resume();
          return finish({ ok: false, error: 'HTTP ' + status });
        }
        if (status < 200) return finish({ ok: false, error: 'HTTP ' + status });

        contentType = String(res.headers['content-type'] || '').toLowerCase() || null;
        seekable = status === 206 || /bytes/i.test(String(res.headers['accept-ranges'] || ''));
        res.on('data', (chunk) => {
          if (!firstByte) firstByte = process.hrtime.bigint();
          bytes += chunk.length;
          if (head.length < 512) head += chunk.slice(0, 512 - head.length).toString('utf8');
          const window = ms(process.hrtime.bigint() - firstByte);
          if (bytes >= SAMPLE_BYTES_MAX) {
            // A fast pipe: we have all the evidence we asked for.
            finish({ ok: true, error: null, sampleMax: true });
          } else if (bytes >= maxBytes && window >= MIN_SAMPLE_MS) {
            // Enough bytes AND enough time: the rate is a measurement, not a rounding error.
            finish({ ok: true, error: null });
          }
        });
        res.on('end', () => {
          // An HTML page with a 200 is a dead source wearing a costume.
          if (looksLikeHtml(head, contentType, target)) {
            return finish({ ok: false, error: 'answered 200 with an HTML page, not a stream' });
          }
          finish({ ok: bytes > 0, error: bytes > 0 ? null : '200 with no bytes' });
        });
        res.on('error', () => finish({ ok: bytes > 0, error: bytes > 0 ? null : 'stream read failed' }));
      });
      req.on('timeout', () => {
        if (bytes > 0) return finish({ ok: true, error: null, partial: true });
        finish({ ok: false, error: 'timeout ' + timeoutMs + 'ms' });
      });
      req.on('error', (e) => {
        if (bytes > 0) return finish({ ok: true, error: null, partial: true });
        finish({ ok: false, error: e.code || e.message || 'connect failed' });
      });
    };

    walk(url, MAX_REDIRECTS);
  });
}

/**
 * A real media file or a real manifest says what it is in its first bytes; a parked domain, a captcha
 * or an addon's landing page answers 200 with a document. Content-type alone is not evidence in either
 * direction — plenty of CDN-hosted HLS says `text/html` — so the sniff decides.
 */
function looksLikeHtml(head, contentType, url) {
  const s = String(head || '').replace(/^\s+/, '');
  if (/^#EXTM3U/.test(s) || /^<MPD[\s>]/i.test(s) || /^<\?xml/i.test(s)) return false; // a real manifest
  if (/^<!doctype html|^<html/i.test(s)) return true;
  if (contentType && /text\/html/i.test(contentType) && !/\.m3u8|\.mpd(\?|#|$)/i.test(String(url || ''))) return true;
  return false;
}

// ---- cache + coalescing + concurrency --------------------------------------------------------------

const inflight = new Map(); // key -> promise
let active = 0;
const waiters = [];
const stats = {
  // "measured" = probes this process actually ran; "cache" = answers reused from data/probe-cache.json.
  probes: 0, measuredOk: 0, measuredFail: 0, cacheHits: 0, coalesced: 0,
  budgetMisses: 0, late: 0, capped: 0, // a batch that ran out of time / finished after it / queued for a slot
};

/** Global semaphore: no matter how many requests are in flight, at most CONCURRENCY probes run. */
function acquire() {
  if (active < CONCURRENCY) {
    active++;
    return Promise.resolve();
  }
  stats.capped++;
  return new Promise((resolve) => waiters.push(resolve));
}

function release() {
  const next = waiters.shift();
  if (next) next(); // the slot moves with it
  else active--;
}

function age(entry) {
  return Date.now() - (Number(entry && entry.checkedAt) || 0);
}

function freshEnough(entry) {
  if (!entry) return false;
  const ttl = entry.ok === true ? OK_TTL_MS : FAIL_TTL_MS;
  return age(entry) <= ttl;
}

function publicEntry(entry) {
  if (!entry) return null;
  return {
    ok: entry.ok === true,
    status: Number.isFinite(entry.status) ? entry.status : null,
    latencyMs: Number.isFinite(entry.latencyMs) ? entry.latencyMs : null,
    bytes: Number.isFinite(entry.bytes) ? entry.bytes : 0,
    kbps: Number.isFinite(entry.kbps) ? entry.kbps : 0,
    // false for a measurement too short to be a speed (a 200-byte playlist) and for anything written
    // by an older hub, which did not know to say. Ranking only claims buffering when this is true.
    rateReliable: entry.rateReliable === true,
    // true when kbps is a proven minimum rather than a value ("at least this fast"): the sample landed
    // faster than the timing window, so the divisor was clamped up instead of reporting rounding error.
    rateFloor: entry.rateFloor === true,
    contentType: entry.contentType || null,
    seekable: entry.seekable === true,
    error: entry.error || null,
    checkedAt: Number(entry.checkedAt) || null,
    ageMs: age(entry),
    sampleMs: Number.isFinite(entry.sampleMs) ? entry.sampleMs : null,
  };
}

/**
 * Measure one URL, or reuse a fresh measurement. Never rejects: a probe that cannot answer is `null`.
 * `force` ignores the cache (the app asked for a re-check because playback was stalling).
 */
function probeUrl(url, headers = {}, { force = false, timeoutMs = PROBE_TIMEOUT_MS } = {}) {
  if (!ENABLED || !probeable(url)) return Promise.resolve(null);
  const key = keyFor(url, headers);
  const hit = state.entries[key];
  if (!force && freshEnough(hit)) {
    stats.cacheHits++;
    return Promise.resolve(publicEntry(hit));
  }
  if (inflight.has(key)) {
    stats.coalesced++;
    return inflight.get(key);
  }
  const p = acquire()
    .then(() => probeOne(url, headers, { timeoutMs })
      .finally(release)
      .catch((e) => {
        log('PROBE INTERNAL FAIL', e.message); // a probe must never throw into a request path
        return { ok: false, error: 'probe error: ' + e.message, checkedAt: Date.now(), bytes: 0 };
      }))
    .then((r) => {
      inflight.delete(key);
      state.entries[key] = { url, ...r };
      state.updatedAt = Date.now();
      stats.probes++;
      if (r.ok) stats.measuredOk++;
      else stats.measuredFail++;
      scheduleSave();
      return publicEntry(state.entries[key]);
    })
    .catch((e) => {
      inflight.delete(key);
      log('PROBE FAIL', e.message);
      return null;
    });
  inflight.set(key, p);
  return p;
}

const sleep = (ms) => new Promise((resolve) => {
  const t = setTimeout(resolve, ms);
  if (t.unref) t.unref();
});

/**
 * Probe a batch within one wall-clock budget. Returns an array the same length as `targets`, with
 * `null` wherever the probe could not answer in time — the caller then behaves exactly as it did
 * before probing existed.
 * Anything still running when the budget is spent is NOT cancelled: it finishes in the background and
 * lands in the cache, so the next view of this title is warm.
 */
async function probeAll(targets, { budgetMs = BUDGET_MS, force = false, timeoutMs = PROBE_TIMEOUT_MS } = {}) {
  const list = Array.isArray(targets) ? targets : [];
  const out = new Array(list.length).fill(null);
  if (!ENABLED || !list.length) return out;

  let pending = 0;
  let expired = false; // set once the budget is spent: anything answering after that is for the NEXT view
  const running = [];
  list.forEach((t, i) => {
    const url = t && (t.url || t);
    if (!probeable(url)) return;
    pending++;
    const headers = (t && t.headers) || {};
    const p = probeUrl(url, headers, { force, timeoutMs })
      .then((r) => {
        out[i] = r;
        if (expired) stats.late++;
        return r;
      })
      .catch(() => null);
    running.push(p);
  });
  if (!pending) return out;

  const left = Math.max(0, Math.round(budgetMs));
  const raced = await Promise.race([Promise.all(running), sleep(left).then(() => 'budget')]);
  if (raced === 'budget') {
    expired = true;
    stats.budgetMisses++;
  }
  return out;
}

/** Synchronous read of what we already measured — for a rule or a row that must not wait at all. */
function peek(url, headers = {}) {
  if (!ENABLED || !probeable(url)) return null;
  return publicEntry(state.entries[keyFor(url, headers)] || null);
}

/** Drop a measurement (the app reported "this one buffered": do not trust it until it is re-measured). */
/**
 * Drop every link verdict on record, in memory and on disk. A greyed-out source and a source that was
 * never tried must not look alike forever because one bad evening was remembered.
 */
function clearCache() {
  const n = Object.keys(state.entries).length;
  state = { entries: {}, updatedAt: 0 };
  inflight.clear();
  save();
  return n;
}

function invalidate(url, headers = {}) {
  if (!probeable(url)) return false;
  const key = keyFor(url, headers);
  const had = Boolean(state.entries[key]);
  delete state.entries[key];
  if (had) scheduleSave();
  return had;
}

function status() {
  let ok = 0;
  let failed = 0;
  let fresh = 0;
  for (const entry of Object.values(state.entries)) {
    if (entry.ok === true) ok++;
    else failed++;
    if (freshEnough(entry)) fresh++;
  }
  return {
    file: FILE,
    enabled: ENABLED,
    entries: Object.keys(state.entries).length,
    fresh,
    working: ok,
    failed,
    ...stats,
    inFlight: inflight.size,
    running: active,
    queued: waiters.length,
    updatedAt: state.updatedAt || null,
    limits: {
      PROBE_BYTES, SAMPLE_BYTES_MAX, MIN_SAMPLE_MS, PROBE_TIMEOUT_MS, OK_TTL_MS, FAIL_TTL_MS,
      CONCURRENCY, BUDGET_MS, CACHE_MAX,
    },
  };
}

module.exports = {
  probeAll, probeUrl, probeOne, peek, invalidate, clearCache, keyFor, hash, probeable, sourceId, status,
  ENABLED, FILE,
  limits: { PROBE_BYTES, SAMPLE_BYTES_MAX, MIN_SAMPLE_MS, PROBE_TIMEOUT_MS, OK_TTL_MS, FAIL_TTL_MS, CONCURRENCY, BUDGET_MS, CACHE_MAX },
};
