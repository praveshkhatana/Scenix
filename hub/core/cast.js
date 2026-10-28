'use strict';
/*
 * core/cast.js — cast names, roles and faces, remembered on this box.
 *
 * The addons in this house send names (sometimes), no roles and no photographs. A detail page that draws
 * three grey initials is not a cast list; the viewer wants to see the people. TMDB has the credits and a
 * profile image for most titles, and the household already puts a TMDB key in data/keys.json for the
 * certificate database. This module is the one extra call: find the TMDB id by the IMDb id we already
 * have, then read the credits once, and keep them in data/cast.json forever.
 *
 * It is deliberately optional and bounded. No key, no network. A slow network returns null inside the
 * detail page's budget; the next visit finds the answer in the file. A failure is remembered for a week
 * so a title with no credits does not cost a call on every open.
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const DATA_DIR = process.env.DATA_DIR || path.join(ROOT, 'data');
const FILE = process.env.CAST_FILE || path.join(DATA_DIR, 'cast.json');
const KEYS_FILE = process.env.RATINGS_KEYS_FILE
  || (fs.existsSync(path.join(DATA_DIR, 'keys.json')) ? path.join(DATA_DIR, 'keys.json') : path.join(ROOT, 'data', 'keys.json'));
const API = 'https://api.themoviedb.org/3';
const MISS_TTL_MS = 7 * 86400000;
const OK_TTL_MS = 30 * 86400000;
const MAX_CAST = 12;
const DEBUG = process.env.CAST_DEBUG === '1' || process.env.CAST_DEBUG === 'true';

let state = null;
let writes = 0;

function read() {
  if (state) return state;
  try { state = JSON.parse(fs.readFileSync(FILE, 'utf8')) || {}; } catch (e) { state = {}; }
  return state;
}
function write() {
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    const tmp = FILE + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(state || {}, null, 2));
    fs.renameSync(tmp, FILE);
    writes++;
  } catch (e) { /* a cache that cannot be written is still a cache */ }
}
function keys() {
  try { return JSON.parse(fs.readFileSync(KEYS_FILE, 'utf8')) || {}; } catch (e) { return {}; }
}
function imdbOf(item) {
  const m = /^imdb:(tt\d+)/.exec(String((item && item.identity) || ''));
  return m ? m[1] : null;
}
/**
 * One GET with the built-in http(s) client. The hub's Node build answers `fetch failed` for TMDB while
 * `https.get` on the same URL works, so this module does not depend on fetch's TLS/proxy behaviour.
 */
function getJson(url, timeoutMs) {
  return new Promise((resolve) => {
    let parsed;
    try { parsed = new URL(url); } catch (e) { resolve(null); return; }
    const mod = parsed.protocol === 'http:' ? require('http') : require('https');
    const req = mod.get(parsed, { headers: { accept: 'application/json', 'user-agent': 'ScenixHub/1.0' } }, (res) => {
      if (res.statusCode < 200 || res.statusCode >= 300) { res.resume(); resolve(null); return; }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); } catch (e) { resolve(null); }
      });
    });
    req.setTimeout(Math.max(200, timeoutMs), () => { req.destroy(); resolve(null); });
    req.on('error', () => resolve(null));
  });
}
const clean = (v) => String(v == null ? '' : v).trim();
function normalise(list) {
  return (Array.isArray(list) ? list : [])
    .map((c) => ({
      name: clean(c && c.name),
      character: clean(c && c.character),
      profile: c && c.profile_path ? 'https://image.tmdb.org/t/p/w185' + c.profile_path : null,
    }))
    .filter((c) => c.name)
    .slice(0, MAX_CAST);
}

/**
 * Look up (and cache) the cast for one item. Resolves to an array of {name, character, profile} or null.
 * Never throws. `budgetMs` is the longest the detail page will wait; the fetch may finish after it and
 * land in the cache for the next visit.
 */
async function enrich(item, budgetMs = 1400) {
  if (!item || (item.type !== 'movie' && item.type !== 'series')) return null;
  const identity = String(item.identity || '');
  if (!identity) return null;
  const db = read();
  const held = db[identity];
  const now = Date.now();
  if (held && now - held.at < (held.cast && held.cast.length ? OK_TTL_MS : MISS_TTL_MS)) {
    return held.cast && held.cast.length ? held.cast : null;
  }
  const key = keys().tmdb;
  if (!key) return null;

  const imdb = imdbOf(item);
  if (!imdb) return null;
  if (DEBUG) console.log('CAST start', identity, item.type, imdb);
  const work = (async () => {
    let tmdbId = held && held.tmdbId;
    if (!tmdbId) {
      const found = await getJson(`${API}/find/${imdb}?external_source=imdb_id&api_key=${encodeURIComponent(key)}`, budgetMs);
      if (DEBUG) console.log('CAST find', !!found, found && Object.keys(found));
      const list = (found && ((found.movie_results && found.movie_results.length) ? found.movie_results : found.tv_results)) || [];
      tmdbId = list[0] && list[0].id;
    }
    if (!tmdbId) return null;
    const kind = item.type === 'series' ? 'tv' : 'movie';
    const credits = await getJson(`${API}/${kind}/${tmdbId}/credits?api_key=${encodeURIComponent(key)}&language=en-US`, budgetMs);
    if (DEBUG) console.log('CAST credits', tmdbId, kind, !!credits, credits && (credits.cast || []).length);
    // A network failure is not "this title has no cast". Only a real answer may become a cached miss,
    // otherwise one bad minute would hide a face for a week.
    if (!credits) return null;
    return normalise(credits.cast);
  })();
  // Cache the answer even when it arrives after the detail page stopped waiting; the next open is free.
  work.then((cast) => {
    if (DEBUG) console.log('CAST work result', Array.isArray(cast) ? cast.length : cast);
    if (!Array.isArray(cast)) return;
    db[identity] = { at: now, tmdbId: db[identity] && db[identity].tmdbId, cast };
    write();
  }).catch(() => {});

  const timed = await Promise.race([
    work.catch(() => null),
    new Promise((resolve) => { const t = setTimeout(() => resolve(undefined), Math.max(200, budgetMs)); if (t.unref) t.unref(); }),
  ]);
  if (timed === undefined) return held && held.cast ? held.cast : null;
  const cast = Array.isArray(timed) ? timed : null;
  if (!cast) return null;
  return cast.length ? cast : null;
}

function status() {
  const db = read();
  return { file: FILE, entries: Object.keys(db).length, withCast: Object.values(db).filter((x) => x.cast && x.cast.length).length, writes, key: !!keys().tmdb };
}

module.exports = { enrich, status, FILE };
