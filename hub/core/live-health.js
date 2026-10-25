'use strict';
/*
 * core/live-health.js — is this channel actually on air right now?
 *
 * A playlist entry is a promise, not a fact: on any given evening a large share of the free-to-air
 * feeds in a public playlist are simply offline, and the old behaviour (serve the playlist in
 * alphabetical order) let the viewer land on a dead channel over and over. This module probes each
 * channel's own stream manifest with a short timeout, in a bounded-concurrency batch, and remembers
 * {ok, checkedAt, latencyMs, error} in data/live-health.json, so the answer survives a restart and a
 * re-deploy instead of being re-learned by the viewer.
 *
 * It also hands every channel a STABLE channel number:
 *   - numbers are assigned ONCE, in the order the curated live row puts channels on screen (working
 *     channels first, then the playlist's own order), and written into the same JSON file;
 *   - an identity that already has a number keeps it forever, so the TV app can promise
 *     "1. Sab TV", "2. &TV" and zap the same way tomorrow;
 *   - a channel that appears in the playlist for the first time gets the next free number.
 * Nothing is random and nothing is re-shuffled by a restart, because the numbers are data in
 * data/live-health.json, not a sort computed per request.
 *
 * Rank (0 working, 1 unknown, 2 known-dead) is what a row sorts on, so a viewer never lands on a
 * channel the hub already watched fail.
 *
 * No npm, no framework: http/https from Node, JSON file, atomic rename. The live-tv provider kicks
 * probe() at startup and on its own schedule; rows and providers read get()/rank() synchronously.
 * Server address is never written here (see the repo rule about identifiers).
 */

const fs = require('fs');
const http = require('http');
const https = require('https');
const path = require('path');
const { URL } = require('url');

const ROOT = path.join(__dirname, '..');
const DATA_DIR = process.env.DATA_DIR || path.join(ROOT, 'data');
const FILE = process.env.LIVE_HEALTH_FILE || path.join(DATA_DIR, 'live-health.json');

const PROBE_TIMEOUT_MS = Number(process.env.LIVE_HEALTH_TIMEOUT_MS || 6000); // a dead feed says so fast
const CONCURRENCY = Number(process.env.LIVE_HEALTH_CONCURRENCY || 8); // never take the box's uplinks
const MAX_REDIRECTS = 3;
const STALE_MS = Number(process.env.LIVE_HEALTH_STALE_MS || 24 * 60 * 60 * 1000); // after a day, "dead" is a rumour
const UA = process.env.ADDON_UA || 'Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 GhartvHub/0.1';

const log = (...a) => console.log(new Date().toISOString(), ...a);

// ---- persistence --------------------------------------------------------------------------------

/** { channels: { identity: {ok, checkedAt, latencyMs, error, number, url} }, updatedAt } */
let state = load();
let dirtyTimer = null;

function load() {
  try {
    const raw = JSON.parse(fs.readFileSync(FILE, 'utf8'));
    const channels = raw && typeof raw.channels === 'object' ? raw.channels : {};
    return { channels, updatedAt: Number(raw && raw.updatedAt) || 0 };
  } catch (e) {
    if (e.code !== 'ENOENT') log('LIVE HEALTH load failed', e.message);
    return { channels: {}, updatedAt: 0 };
  }
}

function save() {
  try {
    fs.mkdirSync(path.dirname(FILE), { recursive: true });
    const tmp = FILE + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(state, null, 1));
    fs.renameSync(tmp, FILE);
  } catch (e) {
    log('LIVE HEALTH save failed', e.message); // losing it costs a re-probe, nothing else
  }
}

/** Coalesce writes: a batch of 400 probes must not mean 400 file writes. */
function scheduleSave() {
  if (dirtyTimer) return;
  dirtyTimer = setTimeout(() => {
    dirtyTimer = null;
    save();
  }, 1000);
  if (dirtyTimer.unref) dirtyTimer.unref();
}

// ---- the probe ------------------------------------------------------------------------------------

/**
 * One probe: GET the channel's own manifest/stream with a hard timeout. A manifest that answers 200
 * but carries no playlist markers is a parked domain or a captcha page, not a channel, so it counts
 * as dead — the whole point is to stop offering things that do not play.
 */
function probeUrl(url, timeoutMs = PROBE_TIMEOUT_MS) {
  const started = Date.now();
  return new Promise((resolve) => {
    let settled = false;
    const finish = (r) => {
      if (settled) return;
      settled = true;
      resolve({ ok: r.ok, error: r.error || null, latencyMs: Date.now() - started });
    };

    const walk = (target, redirectsLeft) => {
      let mod;
      try {
        mod = new URL(target).protocol === 'https:' ? https : http;
      } catch (e) {
        return finish({ ok: false, error: 'bad url' });
      }
      const req = mod.get(target, { headers: { 'User-Agent': UA, Accept: '*/*' }, timeout: timeoutMs }, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirectsLeft > 0) {
          res.resume();
          return walk(new URL(res.headers.location, target).toString(), redirectsLeft - 1);
        }
        if (res.statusCode >= 400) {
          res.resume();
          return finish({ ok: false, error: 'HTTP ' + res.statusCode });
        }
        let body = '';
        res.setEncoding('utf8');
        res.on('data', (c) => {
          body += c;
          if (body.length > 8192) {
            // Enough evidence either way — a live manifest declares itself in its first lines.
            res.destroy();
            return verdict(res, body);
          }
        });
        res.on('end', () => verdict(res, body));
        res.on('error', () => finish({ ok: false, error: 'stream read failed' }));
      });
      req.on('timeout', () => {
        req.destroy();
        finish({ ok: false, error: 'timeout ' + timeoutMs + 'ms' });
      });
      req.on('error', (e) => finish({ ok: false, error: e.code || e.message }));
    };

    /** A parked domain or a captcha page answers 200; a real playlist starts with #EXT. */
    function verdict(res, body) {
      const ctype = String(res.headers['content-type'] || '').toLowerCase();
      const looksLikeManifest = /#EXTM3U|#EXTINF/i.test(body);
      const len = Number(res.headers['content-length'] || 0);
      if (wantManifest(url)) {
        if (looksLikeManifest || /mpegurl|octet-stream|json/.test(ctype)) return finish({ ok: true });
        return finish({ ok: false, error: 'answered 200 but is not a manifest' });
      }
      if (/^text\/html/i.test(ctype) && !looksLikeManifest) return finish({ ok: false, error: 'html page, not a stream' });
      if (len > 0 || body.length > 0) return finish({ ok: true });
      return finish({ ok: true }); // 200 with a chunked body: the player will know within a second
    };

    walk(url, MAX_REDIRECTS);
  });
}

function wantManifest(url) {
  return /\.m3u8(\?|$)/i.test(String(url));
}

/**
 * Probe a batch of channels, bounded by CONCURRENCY, and record one result per identity.
 * `targets` = [{ identity, urls: [best first] }]. The first working url wins, so a channel with a
 * 1080p feed that is down but a 480p feed that is up is still offered (and shown as working).
 */
async function probe(targets, { timeoutMs = PROBE_TIMEOUT_MS, concurrency = CONCURRENCY, label = '' } = {}) {
  const list = (targets || []).filter((t) => t && t.identity && Array.isArray(t.urls) && t.urls.length);
  if (!list.length) return { checked: 0, ok: 0 };
  let at = 0;
  let okCount = 0;
  let checked = 0;
  const worker = async () => {
    for (;;) {
      const i = at++;
      if (i >= list.length) return;
      const t = list[i];
      let best = null;
      for (const url of t.urls.slice(0, 2)) {
        const r = await probeUrl(url, timeoutMs);
        if (!best || (r.ok && !best.ok)) best = { ...r, url };
        if (r.ok) break;
      }
      const prev = state.channels[t.identity] || {};
      state.channels[t.identity] = {
        ...prev,
        ok: Boolean(best && best.ok),
        checkedAt: Date.now(),
        latencyMs: best ? best.latencyMs : null,
        error: best && !best.ok ? best.error : null,
        url: best && best.ok ? best.url : prev.url || null,
      };
      checked++;
      if (best && best.ok) okCount++;
      if (checked % 50 === 0) scheduleSave();
    }
  };
  const t0 = Date.now();
  await Promise.all(Array.from({ length: Math.max(1, Math.min(concurrency, list.length)) }, worker));
  state.updatedAt = Date.now();
  save();
  log('LIVE HEALTH', label, 'checked', checked, 'working', okCount, 'in', Date.now() - t0, 'ms');
  return { checked, ok: okCount };
}

// ---- stable channel numbers -----------------------------------------------------------------------

/**
 * Number the channels in the order the curated live row puts them on screen. Existing numbers never
 * move: the list is the identity -> number map the TV zaps through, so it has to be the same map
 * after every restart. New identities take the next free numbers in the order they are handed over.
 */
function assignNumbers(orderedIdentities) {
  let max = 0;
  for (const entry of Object.values(state.channels)) max = Math.max(max, Number(entry.number) || 0);
  let assigned = 0;
  for (const identity of orderedIdentities || []) {
    if (!identity) continue;
    const key = String(identity);
    const entry = state.channels[key] || (state.channels[key] = {});
    if (!Number.isFinite(entry.number)) {
      entry.number = ++max;
      assigned++;
    }
  }
  if (assigned) save();
  return { total: Object.keys(state.channels).length, assigned };
}

function numberFor(identity) {
  const entry = state.channels[String(identity)];
  return entry && Number.isFinite(entry.number) ? entry.number : null;
}

/** The probe record for an identity, or null when the hub has never checked this channel. */
function get(identity) {
  const entry = state.channels[String(identity)];
  if (!entry) return null;
  return {
    ok: entry.ok === true,
    checkedAt: Number(entry.checkedAt) || null,
    latencyMs: Number.isFinite(entry.latencyMs) ? entry.latencyMs : null,
    error: entry.error || null,
    number: Number.isFinite(entry.number) ? entry.number : null,
    url: entry.url || null, // which of the channel's variants answered (internal, never published)
  };
}

/**
 * 0 = the hub watched this one play, 1 = never checked / the evidence is old, 2 = the hub watched it
 * fail. Dead-last, unknown-in-the-middle: an unchecked channel is not guilty until it is checked.
 */
function rank(identity) {
  const h = get(identity);
  if (!h || !h.checkedAt) return 1;
  if (Date.now() - h.checkedAt > STALE_MS) return 1;
  return h.ok ? 0 : 2;
}

/** Compare two channel items for the live row: working first, then the stable zap order. */
function compare(a, b) {
  const ra = rank(a && a.identity);
  const rb = rank(b && b.identity);
  if (ra !== rb) return ra - rb;
  const na = numberFor(a && a.identity);
  const nb = numberFor(b && b.identity);
  if (na !== nb) return (na == null ? 1e9 : na) - (nb == null ? 1e9 : nb);
  return String((a && a.title) || '').localeCompare(String((b && b.title) || ''));
}

function status() {
  let ok = 0;
  let dead = 0;
  let stale = 0;
  let numbered = 0;
  for (const entry of Object.values(state.channels)) {
    if (Number.isFinite(entry.number)) numbered++;
    const age = Date.now() - (Number(entry.checkedAt) || 0);
    if (entry.ok === true) ok++;
    else if (entry.ok === false && age <= STALE_MS) dead++;
    else stale++;
  }
  return {
    file: FILE,
    checked: Object.keys(state.channels).length,
    working: ok,
    dead,
    unknown: stale,
    numbered,
    updatedAt: state.updatedAt || null,
    timeoutMs: PROBE_TIMEOUT_MS,
    concurrency: CONCURRENCY,
  };
}

module.exports = { probe, assignNumbers, numberFor, get, rank, compare, status, FILE,
  limits: { PROBE_TIMEOUT_MS, CONCURRENCY, STALE_MS } };
