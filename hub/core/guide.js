'use strict';
/*
 * core/guide.js — an optional XMLTV programme guide behind the live-TV row.
 *
 * iptv-org playlists carry channels, not schedules: every published `live.now` was null and the
 * player header showed nothing but a channel name. The owner can now point `settings.live.xmltv` at
 * one XMLTV URL and the same rows answer with what is ON and what comes NEXT.
 *
 * Deliberately small: the guide is polish on top of playback, so it must never cost the hub memory
 * or time it cannot give back. One fetch per refresh cycle, a byte cap on the response, regex parsing
 * (no DOM tree over a 40 MB document), a programme cap, and everything runs in the background — a
 * dead guide URL is a null in the health block, never an exception on a play path.
 */

const http = require('http');
const https = require('https');
const zlib = require('zlib');
const { URL } = require('url');

const settings = require('./settings');

const REFRESH_MS = 30 * 60 * 1000;   // guide data is only ever fresher than this
const FETCH_TIMEOUT_MS = 30 * 1000;
const MAX_BYTES = 40 * 1024 * 1024;  // one XMLTV day for a big country sits under this
const MAX_PROGRAMMES = 300000;       // a box this hub runs on does not need more
const UA = 'Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 ScenixHub/1.0';
// A "now" programme may run this long past its printed end (guides are written by people) before
// the honest answer becomes "nothing known" instead of a stale title.
const GUIDE_GRACE_MS = 15 * 60e3;

const log = (...a) => console.log(new Date().toISOString(), ...a);

/** slug(tvg-id or display-name) -> programmes, sorted by start. */
let byChannel = new Map();
let updatedAt = 0;
let lastError = null;
let channelKeys = 0;
let programmeCount = 0;
let fetching = null;

function slug(s) {
  return String(s || '').toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 64);
}

function guideUrl() {
  return String((settings.get().live || {}).xmltv || '').trim();
}

/** XMLTV timestamps: YYYYMMDDHHmmss (+HHMM | -HHMM | Z), as UTC epoch ms. */
const TIME_RE = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*([Zz]|[+-]\d{4})?$/;

function timeFrom(m) {
  if (!m) return null;
  const [, y, mo, d, h, mi, se, off] = m;
  const base = Date.UTC(+y, +mo - 1, +d, +h, +mi, se ? +se : 0);
  if (!Number.isFinite(base)) return null;
  if (!off || off === 'Z' || off === 'z') return base;
  const sign = off[0] === '-' ? 1 : -1; // "-0500": the wall time trails UTC, so add the offset back
  const mins = +off.slice(1, 3) * 60 + +off.slice(3, 5);
  return base + sign * mins * 60000;
}

function parseXmltvTime(s) {
  return timeFrom(TIME_RE.exec(String(s || '').trim()));
}

function decode(s) {
  return String(s || '')
    .replace(/&#x([0-9a-fA-F]+);/g, (_, h) => { try { return String.fromCodePoint(parseInt(h, 16)); } catch (e) { return ''; } })
    .replace(/&#(\d+);/g, (_, d) => { try { return String.fromCodePoint(+d); } catch (e) { return ''; } })
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'").replace(/&amp;/g, '&')
    .replace(/\s+/g, ' ').trim();
}

function attr(tag, name) {
  const m = new RegExp('\\b' + name + '\\s*=\\s*"([^"]*)"|\\b' + name + "\\s*=\\s*'([^']*)'").exec(tag);
  if (!m) return '';
  return decode(m[1] != null ? m[1] : m[2]);
}

function firstTag(re, s) {
  const m = re.exec(s);
  return m ? decode(m[1]) : '';
}

function toText(buf) {
  // Most public guides are served .gz: the magic bytes decide, not the file name. A raw inflate
  // failure falls back to treating the body as text — some hosts name a file .gz and ship it plain.
  if (buf.length > 2 && buf[0] === 0x1f && buf[1] === 0x8b) {
    try { return zlib.gunzipSync(buf).toString('utf8'); } catch (e) { /* fall through */ }
  }
  return buf.toString('utf8');
}

function fetchText(url, depth = 0) {
  return new Promise((resolve, reject) => {
    const mod = url.startsWith('https:') ? https : http;
    const req = mod.get(url, { headers: { 'User-Agent': UA }, timeout: FETCH_TIMEOUT_MS }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume();
        if (depth > 4) return reject(new Error('too many redirects from the guide'));
        let next;
        try { next = new URL(res.headers.location, url).toString(); } catch (e) {
          return reject(new Error('bad redirect from guide: ' + res.headers.location));
        }
        return resolve(fetchText(next, depth + 1));
      }
      if (res.statusCode !== 200) {
        res.resume();
        return reject(new Error('HTTP ' + res.statusCode + ' for ' + url));
      }
      const chunks = [];
      let size = 0;
      res.on('data', (c) => {
        size += c.length;
        if (size > MAX_BYTES) { res.destroy(); return; } // the cap: 'close' ends what arrived
        chunks.push(c);
      });
      let settled = false;
      const done = () => { if (!settled) { settled = true; resolve(toText(Buffer.concat(chunks))); } };
      res.on('end', done);
      res.on('close', done);
      res.on('error', (e) => { if (!settled) { settled = true; reject(e); } });
    });
    req.on('timeout', () => req.destroy(new Error('guide fetch timeout')));
    req.on('error', reject);
  });
}

/** Parse one XMLTV document into the slug -> sorted-programmes map. */
function parse(text) {
  const map = new Map();
  // <channel id="X">Display Name</channel> lets a guide keyed by tvg-id still be found by a
  // playlist that only knows the channel's name (and the other way round).
  const idToName = new Map();
  const chRe = /<channel\b([^>]*)>([\s\S]*?)<\/channel>/g;
  let cm;
  while ((cm = chRe.exec(text))) {
    const id = attr(cm[1], 'id');
    const name = firstTag(/<display-name[^>]*>([\s\S]*?)<\/display-name>/, cm[2]);
    if (id && name) idToName.set(slug(id), name);
  }
  programmeCount = 0;
  const progRe = /<programme\b([^>]*)>([\s\S]*?)<\/programme>/g;
  let pm;
  while ((pm = progRe.exec(text))) {
    const open = pm[1];
    const body = pm[2];
    const start = parseXmltvTime(attr(open, 'start'));
    if (start == null) continue;
    const stop = parseXmltvTime(attr(open, 'stop'));
    if (stop != null && stop <= start) continue;
    const title = firstTag(/<title[^>]*>([\s\S]*?)<\/title>/, body);
    if (!title) continue;
    const cid = attr(open, 'channel');
    const keys = new Set();
    if (cid) {
      keys.add(slug(cid));
      const viaName = idToName.get(slug(cid));
      if (viaName) keys.add(slug(viaName));
    }
    if (!keys.size) continue;
    for (const key of keys) {
      let list = map.get(key);
      if (!list) map.set(key, list = []);
      list.push({
        start, stop: stop == null ? start + 3600e3 : stop,
        title: title.slice(0, 200),
        desc: firstTag(/<desc[^>]*>([\s\S]*?)<\/desc>/, body).slice(0, 300),
        cat: firstTag(/<category[^>]*>([\s\S]*?)<\/category>/, body).slice(0, 60) || null,
        live: /\blive\b/i.test(body),
      });
    }
    if (++programmeCount % 50000 === 0) log('GUIDE PARSE', 'programmes', programmeCount);
    if (programmeCount >= MAX_PROGRAMMES) { log('GUIDE PARSE', 'cap reached at', MAX_PROGRAMMES); break; }
  }
  for (const list of map.values()) {
    list.sort((a, b) => a.start - b.start);
    // The same programme published by two feeds should not read as two entries.
    for (let i = list.length - 1; i > 0; i--) {
      if (list[i].start === list[i - 1].start) list.splice(i, 1);
    }
  }
  return map;
}

/**
 * Fetch (if the owner configured one) and parse the guide. Always resolves; the outcome is visible
 * through status(). The playlist refresh and the 30-minute health tick both call this.
 */
function refresh(force = false) {
  if (fetching) return fetching;
  const url = guideUrl();
  if (!url) {
    byChannel = new Map();
    updatedAt = 0; lastError = null; channelKeys = 0; programmeCount = 0;
    return Promise.resolve();
  }
  if (!force && updatedAt && Date.now() - updatedAt < REFRESH_MS) return Promise.resolve();
  fetching = fetchText(url).then((text) => {
    const map = parse(text);
    byChannel = map;
    channelKeys = map.size;
    updatedAt = Date.now();
    lastError = null;
    let total = 0;
    for (const list of map.values()) total += list.length;
    programmeCount = total;
    log('GUIDE OK', 'keys', map.size, 'programmes', total);
  }).catch((e) => {
    lastError = String(e.message || e);
    log('GUIDE FAIL', lastError);
  }).finally(() => {
    fetching = null;
  });
  return fetching;
}

function lookup(channel) {
  const name = channel && typeof channel === 'object' ? String(channel.name || '') : String(channel || '');
  const id = channel && typeof channel === 'object' ? String(channel.tvgId || '') : '';
  for (const key of [slug(id), slug(name)]) {
    if (!key) continue;
    const list = byChannel.get(key);
    if (list && list.length) return list;
  }
  return null;
}

function pub(x, at) {
  return {
    title: x.title,
    start: x.start,
    stop: x.stop,
    minsLeft: Math.max(0, Math.round((x.stop - at) / 60e3)),
    progress: x.stop > x.start
      ? Math.max(0, Math.min(1000, Math.round(((at - x.start) / (x.stop - x.start)) * 1000)))
      : null,
    live: x.live || null,
    category: x.cat || null,
  };
}

/**
 * What is on `channel` right now and what follows it, or nulls when this hub knows neither.
 * `channel` may be a name, or { name, tvgId } when the playlist carries a tvg-id to match on.
 */
function nowNext(channel, at = Date.now()) {
  if (!byChannel.size) return { now: null, next: null };
  const list = lookup(channel);
  if (!list) return { now: null, next: null };
  // The first programme that has not ended yet.
  let lo = 0, hi = list.length - 1, idx = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (list[mid].stop > at) { idx = mid; hi = mid - 1; } else lo = mid + 1;
  }
  if (idx < 0) return { now: null, next: null };
  const p = list[idx];
  const next = list[idx + 1] || null;
  const stillOn = p.stop > at && p.start <= at + GUIDE_GRACE_MS;
  const upcoming = next && next.stop > at && next.start <= p.stop + GUIDE_GRACE_MS;
  return {
    now: stillOn ? pub(p, at) : null,
    next: upcoming ? pub(next, at) : null,
  };
}

function status() {
  return {
    configured: Boolean(guideUrl()),
    channels: channelKeys, programmes: programmeCount,
    updatedAt: updatedAt || null, error: lastError,
  };
}

module.exports = { refresh, nowNext, status, parse, parseXmltvTime };
