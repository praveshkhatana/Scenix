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
async function getJson(url, timeoutMs) {
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), timeoutMs);
  if (timer.unref) timer.unref();
  try {
    const res = await fetch(url, { signal: ac.signal, headers: { accept: 'application/json' } });
    if (!res.ok) return null;
    return await res.json();
  } catch (e) { return null; } finally { clearTimeout(timer); }
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
  const work = (async () => {
    let tmdbId = held && held.tmdbId;
    if (!tmdbId) {
      const found = await getJson(`${API}/find/${imdb}?external_source=imdb_id&api_key=${encodeURIComponent(key)}`, budgetMs);
      const list = (found && (found.movie_results || found.tv_results)) || [];
      tmdbId = list[0] && list[0].id;
    }
    if (!tmdbId) return null;
    const kind = item.type === 'series' ? 'tv' : 'movie';
    const credits = await getJson(`${API}/${kind}/${tmdbId}/credits?api_key=${encodeURIComponent(key)}`, budgetMs);
    return normalise(credits && credits.cast);
  })();

  const timed = await Promise.race([
    work.catch(() => null),
    new Promise((resolve) => { const t = setTimeout(() => resolve(undefined), Math.max(200, budgetMs)); if (t.unref) t.unref(); }),
  ]);
  if (timed === undefined) return held && held.cast ? held.cast : null;
  const cast = Array.isArray(timed) ? timed : [];
  db[identity] = { at: now, tmdbId: db[identity] && db[identity].tmdbId, cast };
  write();
  return cast.length ? cast : null;
}

function status() {
  const db = read();
  return { file: FILE, entries: Object.keys(db).length, withCast: Object.values(db).filter((x) => x.cast && x.cast.length).length, writes, key: !!keys().tmdb };
}

module.exports = { enrich, status, FILE };
