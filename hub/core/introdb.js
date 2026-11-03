'use strict';
/*
 * core/introdb.js — the skip-intro window, from IntroDB (https://introdb.app).
 *
 * The hub has no intro data of its own: chapter names are not reliable and a guess from the clock is
 * worse than no button. IntroDB is a public, keyless endpoint that returns an episode's intro window as
 * milliseconds (`GET /segments?imdb_id=&season=&episode=`), so this module is the bridge from that fact
 * to the play answer's `introStartMs`/`introEndMs`. The native and web players already only show the
 * button inside that window; with no window they show nothing.
 *
 * It is intentionally tiny and forgiving: one HTTPS GET, a short timeout, an in-memory cache (a week
 * for a hit, six hours for a miss). A timeout or a 404 is "this episode has no known intro", never a
 * failed play. No npm.
 */

const https = require('https');

const HIT_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const MISS_TTL_MS = 6 * 60 * 60 * 1000;
const ERROR_TTL_MS = 5 * 60 * 1000;
const MAX_BYTES = 64 * 1024;

const cache = new Map();
let calls = 0;
let hits = 0;
let failures = 0;

function getJson(url, timeoutMs) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const req = https.get(url, { headers: { 'Accept': 'application/json', 'User-Agent': 'ScenixHub/1.0' } }, (res) => {
      if (res.statusCode !== 200) {
        res.resume();
        reject(new Error('HTTP ' + res.statusCode));
        return;
      }
      let size = 0;
      const chunks = [];
      res.on('data', (c) => {
        size += c.length;
        if (size > MAX_BYTES) {
          req.destroy();
          reject(new Error('answer too large'));
          return;
        }
        chunks.push(c);
      });
      res.on('end', () => {
        if (settled) return;
        settled = true;
        try {
          resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
        } catch (e) {
          reject(e);
        }
      });
    });
    req.setTimeout(timeoutMs, () => req.destroy(new Error('timeout')));
    req.on('error', (e) => {
      if (!settled) {
        settled = true;
        reject(e);
      }
    });
  });
}

/**
 * The intro window for one IMDb episode, or null. `identity` must be the hub's `imdb:tt…` form; any
 * other identity (a FrameXTV id, a channel) simply skips the lookup.
 */
async function introFor(identity, season, episode, opts = {}) {
  const m = /^imdb:(tt\d+)$/i.exec(String(identity || '').trim());
  const s = Number(season);
  const e = Number(episode);
  if (!m || !(s > 0) || !(e > 0)) return null;
  const key = m[1] + ':' + s + ':' + e;
  const now = Date.now();
  const hit = cache.get(key);
  if (hit && now - hit.at < hit.ttl) {
    hits++;
    return hit.value;
  }
  const timeoutMs = Math.max(400, Math.min(3000, Number(opts.timeoutMs) || 1200));
  calls++;
  const url = 'https://api.introdb.app/segments?imdb_id=' + encodeURIComponent(m[1])
    + '&season=' + s + '&episode=' + e;
  try {
    const j = await getJson(url, timeoutMs);
    const intro = j && j.intro;
    const start = Number(intro && intro.start_ms);
    const end = Number(intro && intro.end_ms);
    const value = Number.isFinite(start) && Number.isFinite(end) && end > start && end > 0
      ? { startMs: Math.max(0, Math.round(start)), endMs: Math.round(end) }
      : null;
    cache.set(key, { value, at: now, ttl: value ? HIT_TTL_MS : MISS_TTL_MS });
    return value;
  } catch (err) {
    failures++;
    // A temporary failure is not "no intro"; remember it only briefly so a later play asks again.
    cache.set(key, { value: null, at: now, ttl: ERROR_TTL_MS });
    return null;
  }
}

function status() {
  return { entries: cache.size, calls, hits, failures, source: 'api.introdb.app' };
}

module.exports = { introFor, status };
