'use strict';
/*
 * core/ratings.js — our OWN certificate database, so a title's age rating is looked up once and then
 * lives on this box forever.
 *
 * Why this exists: the addons in this house state no certificate at all (cinemeta metas carry no
 * `certification`), so every "is this for kids" decision was a guess from genres. A certificate is not a
 * guess, and asking the internet on every page view is not acceptable on a TV network. So:
 *
 *   data/ratings.json   one entry per identity, written the first time we look it up, NEVER re-fetched
 *                       afterwards. A successful entry has no TTL. Only "I asked and found nothing" is
 *                       retried, and only after RATINGS_MISS_TTL_MS.
 *
 * Two upstreams, asked concurrently, merged into one record:
 *   OMDb  http://www.omdbapi.com/?i=<imdb id>&apikey=<key>     -> `Rated` (the certificate), Genre,
 *         Runtime, Title, Year. Keyed by IMDb id, which is already this hub's merge key.
 *   TMDB  /find/<imdb id>?external_source=imdb_id, then one detail call with
 *         append_to_response=release_dates,content_ratings -> per-country board certifications (IN is
 *         the CBFC one, the board that actually rates what this family watches) and the `adult` boolean,
 *         which is the cleanest XXX flag anywhere.
 * Coverage is patchy (Pathaan has no CBFC entry at all), so the two upstreams enrich each other and
 * whatever is missing falls back to the heuristic tier in rules/maturity.js.
 *
 * Keys live in data/keys.json (mode 600, gitignored, never rsynced): {"omdb":"…","tmdb":"…"}. No keys,
 * or no network, is not an error: the hub answers from the heuristic tier and tries again later.
 * A key is never written into a log line or into data/ratings.json.
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const DATA_DIR = process.env.DATA_DIR || path.join(ROOT, 'data');
const FILE = process.env.RATINGS_FILE || path.join(DATA_DIR, 'ratings.json');
// Keys live beside the data, and stay there: data/ is gitignored and rsync-excluded, so a key never
// enters the repo or a deploy. The repo's own data/ dir is tried too, so a tool run with DATA_DIR
// pointed somewhere else still finds the household's keys.
const KEYS_FILE = process.env.RATINGS_KEYS_FILE
  || (fs.existsSync(path.join(DATA_DIR, 'keys.json')) ? path.join(DATA_DIR, 'keys.json') : path.join(ROOT, 'data', 'keys.json'));
const ADDONS_FILE = process.env.ADDONS_FILE || path.join(DATA_DIR, 'addons.json');

/* ------------------------------------------------------------------ the ladder
 * The whole age policy of the hub, as a table. Edit these rows; nothing below needs to change.
 * tier 0/1/2 are "how old must you be"; tier 3 is the dark-red zone, which is not an age question at
 * all — it is a switch the household turns on (prefs.xxxEnabled).
 */
const LADDER = [
  { tier: 0, minAge: 0, certs: ['U', 'G', 'PG', 'TV-Y', 'TV-Y7', 'TV-Y7-FV', 'TV-G', 'TV-PG'] },
  { tier: 1, minAge: 13, certs: ['U/A 7+', 'U/A 13+', 'PG-13', '12', '12A', '15A', 'M', 'TV-14'] },
  // "generic adult": nudity, sex, violence. Normal for adults, invisible to kids.
  { tier: 2, minAge: 16, certs: ['A', 'U/A 16+', 'R', 'NC-17', '15', '16', '18', '18A', 'R18', 'TV-MA', 'X'] },
];
const TIER3 = { tier: 3, minAge: 18 }; // XXX: no certificate says it, a flag or a folder does
// A title we cannot rate at all is treated as generic adult. Safer to hide from children than to guess,
// and it is what the big services do with unrated titles.
const UNRATED = { tier: 2, minAge: 16 };
// Words that mean "the certificate field is empty", not "rated for everyone".
const NOT_RATED = ['', 'N/A', 'NA', 'NR', 'NOT RATED', 'UNRATED', 'APPROVED', 'PASSED', 'GP', 'TBD'];

// Evidence for tier 3 that is not a certificate. Also a table, also editable.
const ADULT_GENRE = ['Adult', 'Erotica'];
const ADULT_WORDS = /\b(xxx|porn|adult only|erotic|blue film|18\+ only)\b/i;
const PRIVATE_DIR = /^(private|adult|xxx|18\+|explicit)\b/i; // a media/ folder named like this

/* ------------------------------------------------------------------ plumbing */
const TIMEOUT_MS = Number(process.env.RATINGS_TIMEOUT_MS || 6000);
const MISS_TTL_MS = Number(process.env.RATINGS_MISS_TTL_MS || 7 * 24 * 3600 * 1000);
const BUDGET_MS = Number(process.env.RATINGS_BUDGET_MS || 2500); // what a page view may wait for
// OMDb's free tier is about 1000 requests a day. The live path stays under it; the backfill tool does not
// have to, because it is run once and sleeps between calls.
const DAILY_CAP = Number(process.env.RATINGS_DAILY_CAP || 800);
let cappedLogged = false;

let state = { ratings: {}, day: { date: today(), calls: 0 } };
let loaded = false;
const inflight = new Map(); // identity -> promise, so one search burst asks for a title once
let saveTimer = null;
let keys = null;
let keyMtime = 0;

function log(...a) { console.log(new Date().toISOString(), ...a); }

function load() {
  if (loaded) return;
  loaded = true;
  try {
    const raw = JSON.parse(fs.readFileSync(FILE, 'utf8'));
    if (raw && raw.ratings && typeof raw.ratings === 'object') state = raw;
    if (!state.day || typeof state.day !== 'object') state.day = { date: today(), calls: 0 };
  } catch (e) {
    if (e.code !== 'ENOENT') log('RATINGS load failed:', e.message);
  }
}

function save() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    try {
      fs.mkdirSync(path.dirname(FILE), { recursive: true });
      const tmp = FILE + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(state, null, 1));
      fs.renameSync(tmp, FILE); // a crash mid-write never leaves a half file
    } catch (e) {
      log('RATINGS save failed:', e.message);
    }
  }, 500);
  if (saveTimer.unref) saveTimer.unref();
}

/** Keys are read per call and re-read if the file changed: adding a key must not need a restart. */
function readKeys() {
  try {
    const st = fs.statSync(KEYS_FILE);
    if (!keys || st.mtimeMs !== keyMtime) {
      keys = JSON.parse(fs.readFileSync(KEYS_FILE, 'utf8'));
      keyMtime = st.mtimeMs;
    }
  } catch (e) {
    keys = null;
  }
  return keys || {};
}

/** Never let a key reach a log line or an error message. */
function redact(text) {
  const k = readKeys();
  let out = String(text || '');
  for (const v of Object.values(k)) if (typeof v === 'string' && v.length > 3) out = out.split(v).join('<key>');
  return out;
}

async function getJson(url, timeoutMs = TIMEOUT_MS) {
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), timeoutMs);
  if (timer.unref) timer.unref();
  try {
    const res = await fetch(url, {
      signal: ac.signal,
      headers: { 'user-agent': 'GhartvHub/0.1 (self-hosted; personal use)', accept: 'application/json' },
    });
    if (!res.ok) return null;
    return await res.json();
  } catch (e) {
    log('RATINGS upstream fail:', redact(url.replace(/([?&])(api_key|apikey)=[^&]*/g, '$1$2=<key>')), e.message);
    return null;
  }
}

/* ------------------------------------------------------------------ the ladder as code */
function normCert(value) {
  return String(value == null ? '' : value).toUpperCase().replace(/\s+/g, ' ').replace(/[.\u00A0]/g, ' ').trim();
}

/** Map one board's certificate to {tier, minAge}, or null when it states nothing. */
function ladderFor(cert) {
  const c = normCert(cert);
  if (!c || NOT_RATED.includes(c)) return null;
  for (const row of LADDER) if (row.certs.includes(c)) return { tier: row.tier, minAge: row.minAge, cert: c };
  // A certificate from a board we do not know is still a certificate: treat it as generic adult.
  return { tier: UNRATED.tier, minAge: UNRATED.minAge, cert: c, unknownBoard: true };
}

/* ------------------------------------------------------------------ upstreams */
function imdbIdOf(identity, known) {
  const m = /^imdb:(tt\d{7,10})$/i.exec(String(identity || ''));
  if (m) return m[1];
  const k = known && known.imdbId;
  return typeof k === 'string' && /^tt\d{7,10}$/.test(k) ? k : null;
}

async function fromOMDb(imdbId) {
  const key = readKeys().omdb;
  if (!key || !imdbId) return null;
  const body = await getJson(`http://www.omdbapi.com/?i=${encodeURIComponent(imdbId)}&apikey=${encodeURIComponent(key)}`);
  if (!body || body.Response === 'False' || !body.Title) return null;
  const genres = String(body.Genre || '').split(',').map((s) => s.trim()).filter(Boolean);
  const runtime = parseInt(body.Runtime, 10);
  return {
    source: 'omdb',
    title: body.Title,
    year: /^\d{4}$/.test(String(body.Year)) ? Number(body.Year) : null,
    cert: body.Rated, // the certificate field; "Not Rated" and "" mean nothing was stated
    genres,
    runtimeMs: Number.isFinite(runtime) && runtime > 0 ? runtime * 60000 : null,
    adultFlag: genres.some((g) => ADULT_GENRE.includes(g)),
    type: body.Type === 'series' ? 'series' : 'movie',
  };
}

/** TMDB: find by IMDb id, then one detail call carrying both the boards and the adult flag. */
async function fromTMDB(imdbId, wantedType) {
  const key = readKeys().tmdb;
  if (!key || !imdbId) return null;
  const found = await getJson(`https://api.themoviedb.org/3/find/${encodeURIComponent(imdbId)}?external_source=imdb_id&api_key=${encodeURIComponent(key)}`);
  const movie = (found && Array.isArray(found.movie_results) && found.movie_results[0]) || null;
  const tv = (found && Array.isArray(found.tv_results) && found.tv_results[0]) || null;
  const pick = wantedType === 'series' ? (tv || movie) : (movie || tv);
  if (!pick) return null;
  const kind = pick === tv ? 'tv' : 'movie';
  const detail = await getJson(`https://api.themoviedb.org/3/${kind}/${pick.id}?api_key=${encodeURIComponent(key)}&append_to_response=release_dates,content_ratings`);
  if (!detail) return null;
  const certs = {};
  const blocks = (detail.release_dates && detail.release_dates.results)
    || (detail.content_ratings && detail.content_ratings.results) || [];
  for (const b of blocks) {
    const country = String(b.iso_3166_1 || '').toUpperCase();
    for (const rd of b.release_dates || [b]) {
      const cert = normCert(rd.certification);
      if (cert && !certs[country]) certs[country] = cert;
    }
  }
  return {
    source: 'tmdb',
    tmdbId: pick.id,
    type: kind === 'tv' ? 'series' : 'movie',
    title: detail.title || detail.name || pick.title || pick.name || null,
    year: (detail.release_date || detail.first_air_date || '').slice(0, 4) || null,
    certs,
    adultFlag: Boolean(detail.adult),
    genres: Array.isArray(detail.genres) ? detail.genres.map((g) => g.name) : [],
    runtimeMs: Number(detail.runtime) > 0 ? Number(detail.runtime) * 60000 : null,
  };
}

/* ------------------------------------------------------------------ merge */
/**
 * Boards disagree (Dangal is `U` in India and unrated elsewhere; a film can be `U/A 13+` at home and `R`
 * abroad). The decision uses the STRICTEST board, because the point of the tier is to protect a child who
 * is watching on the sofa. The label shown to the family is the local one: IN (the CBFC board) when it
 * exists, else US, else whichever board was strictest.
 */
function merge(identity, imdbId, parts) {
  const live = parts.filter(Boolean);
  if (!live.length) return null;
  const certs = {};
  const rated = [];
  for (const p of live) {
    if (p.cert) {
      const step = ladderFor(p.cert);
      if (step) {
        certs.IN = certs.IN || normCert(p.cert); // OMDb states the board for the country we ask from
        rated.push(step);
      }
    }
    for (const [country, cert] of Object.entries(p.certs || {})) {
      certs[country] = cert;
      const step = ladderFor(cert);
      if (step) rated.push(step);
    }
  }
  const genres = [...new Set(live.flatMap((p) => p.genres || []))];
  const adultFlag = live.some((p) => p.adultFlag);
  const runtimeMs = live.map((p) => p.runtimeMs).find((v) => Number.isFinite(v)) || null;

  let tier = null;
  let minAge = null;
  let maturity = null;
  if (adultFlag || genres.some((g) => ADULT_GENRE.includes(g))) {
    tier = TIER3.tier; minAge = TIER3.minAge; // an explicit adult flag outrates any board
    maturity = certs.IN || certs.US || null;
  } else if (rated.length) {
    const strictest = rated.reduce((a, b) => (b.minAge > a.minAge ? b : a));
    const stated = rated.find((r) => r.cert === certs.IN) || rated.find((r) => r.cert === certs.US) || strictest;
    tier = Math.max(strictest.tier, ladderFor(stated.cert).tier);
    minAge = Math.max(strictest.minAge, ladderFor(stated.cert).minAge);
    maturity = stated.cert;
  }
  if (tier === null) ({ tier, minAge } = UNRATED); // asked, found no certificate: the conservative default

  return {
    identity,
    imdbId,
    tmdbId: (live.find((p) => p.tmdbId) || {}).tmdbId || null,
    tier,
    minAge,
    maturity, // the certificate the family sees, e.g. "U/A 13+"
    certs, // every board we heard from: { IN: 'U/A 13+', US: 'PG-13', … }
    genres: genres.length ? genres : null,
    adultFlag,
    runtimeMs,
    title: (live.find((p) => p.title) || {}).title || null,
    year: (live.find((p) => p.year) || {}).year || null,
    type: (live.find((p) => p.type) || {}).type || null,
    unrated: rated.length === 0, // asked and nothing was stated: the heuristic tier still applies
    sources: live.map((p) => p.source),
    fetchedAt: Date.now(),
  };
}

/**
 * Look a title up. Resolves with the record, or null when neither upstream answered (the caller then
 * uses the heuristic tier, and "found nothing" is remembered so the next page view does not ask again).
 *
 * `opts.force` ignores the daily cap; that is what tools/backfill-ratings.js uses, because it asks on
 * purpose and sleeps between calls. The live path never does.
 */
async function lookup(identity, known = {}, opts = {}) {
  load();
  const key = String(identity);
  const have = state.ratings[key];
  if (have && fresh(have)) return have;
  if (inflight.has(key)) return inflight.get(key);
  if (!opts.force && spentToday() >= DAILY_CAP) {
    // The free tier is roughly 1000 lookups a day and a home screen touches a thousand identities on a
    // cold database. Past the cap the hub answers from the heuristic tier until the date rolls over;
    // tools/backfill-ratings.js asks on purpose and is allowed past the cap.
    if (!cappedLogged) {
      cappedLogged = true;
      log('RATINGS daily cap reached', DAILY_CAP, '- heuristic tier only until the date rolls over');
    }
    return have || null;
  }
  const run = (async () => {
    const imdbId = imdbIdOf(key, known);
    const parts = await Promise.all([fromOMDb(imdbId), fromTMDB(imdbId, known.type)]);
    const rec = merge(key, imdbId, parts);
    if (rec) {
      state.ratings[key] = rec;
    } else {
      // Remember the question, not just the answer, so a miss costs one round every week, not per view.
      state.ratings[key] = { ...(have && have.sources && have.sources.length ? have : {}), identity, tier: null, minAge: null, unrated: true, lookedUpAt: Date.now(), sources: [] };
    }
    if (imdbId) countCall(); // a title we could not ask about costs no quota
    save();
    return state.ratings[key];
  })();
  inflight.set(key, run);
  return run;
}

/* One counter, persisted with the records: the cap has to survive restarts to mean anything. */
function today() { return new Date().toISOString().slice(0, 10); }
function spentToday() {
  return state.day && state.day.date === today() ? Number(state.day.calls || 0) : 0;
}
function countCall() {
  if (!state.day || state.day.date !== today()) state.day = { date: today(), calls: 0 };
  state.day.calls = Number(state.day.calls || 0) + 1;
}

function fresh(rec) {
  if (!rec) return false;
  if (rec.sources && rec.sources.length) return true; // a real record never expires
  return Date.now() - Number(rec.lookedUpAt || 0) < MISS_TTL_MS;
}

/** The stored record for an identity, or null. Synchronous: this is what the rules run on. */
function peek(identity) {
  load();
  const rec = state.ratings[String(identity)];
  return rec && fresh(rec) ? rec : null;
}

/** Await a lookup but never longer than the page's patience. */
async function lookupWithin(identity, known = {}, ms = BUDGET_MS, opts = {}) {
  let timer;
  const guard = new Promise((resolve) => { timer = setTimeout(() => resolve(null), ms); if (timer.unref) timer.unref(); });
  const p = Promise.resolve().then(() => lookup(identity, known, opts)).catch((e) => { log('RATINGS lookup failed:', e.message); return null; });
  return Promise.race([p, guard]).finally(() => clearTimeout(timer));
}

// Addons the household marked `"adult": true` in data/addons.json. Read straight from the file, with an
// mtime check, so this rule can ask it per item without a parse per item and without importing the addon
// host (which imports the registry, which imports the rules — a circle).
let addonStamp = 0;
let addonIds = [];
function adultAddonIds() {
  try {
    const st = fs.statSync(ADDONS_FILE);
    if (st.mtimeMs !== addonStamp) {
      addonStamp = st.mtimeMs;
      const list = JSON.parse(fs.readFileSync(ADDONS_FILE, 'utf8'));
      const arr = Array.isArray(list) ? list : (list && list.addons) || [];
      addonIds = arr.filter((e) => e && e.adult === true).map((e) => String(e.id));
    }
    return addonIds;
  } catch (e) {
    return [];
  }
}

/**
 * The tier from what the hub already holds, with no network: an addon the household marked `adult`,
 * a folder in media/ named like "Private", a genre that says Adult, words in the title or synopsis.
 * Returns null when the heuristic has no opinion, which is when UNRATED applies.
 */
function heuristic(item, opts = {}) {
  if (!item) return null;
  const addonsMarked = Array.isArray(opts.adultAddons) ? opts.adultAddons : [];
  const from = Array.isArray(item._addons) ? item._addons : [];
  if (from.some((id) => addonsMarked.includes(id))) return { ...TIER3, because: 'addon marked adult' };
  const genres = Array.isArray(item.genres) ? item.genres : [];
  if (genres.some((g) => ADULT_GENRE.includes(g))) return { ...TIER3, because: 'genre Adult' };
  if (genres.some((g) => PRIVATE_DIR.test(String(g)))) return { ...TIER3, because: 'a private folder in media/' };
  const text = `${item.title || ''} ${item.description || ''}`;
  if (ADULT_WORDS.test(text)) return { ...TIER3, because: 'the title says so' };
  const stated = ladderFor(item.maturity); // some addon does state a certificate: use it
  if (stated) return { tier: stated.tier, minAge: stated.minAge, because: 'certificate ' + stated.cert };
  return null;
}

/** What the hub knows about a title right now, cheap and synchronous, for the rules. */
/**
 * What the hub knows about a title right now, cheap and synchronous, for the rules.
 * `defaulted: true` means the tier is the conservative guess rather than something we read anywhere: a
 * caller that can act differently on that (a live channel has no certificate to find) should check it.
 */
function know(identity, item, opts = {}) {
  if (!Array.isArray(opts.adultAddons)) opts = { ...opts, adultAddons: adultAddonIds() };
  const rec = peek(identity);
  if (rec && rec.tier != null) {
    return { tier: rec.tier, minAge: rec.minAge, maturity: rec.maturity || null, from: 'ratings', defaulted: false, record: rec };
  }
  const h = heuristic(item, opts);
  if (h) return { tier: h.tier, minAge: h.minAge, maturity: (rec && rec.maturity) || null, from: h.because, defaulted: false, record: rec };
  return { tier: UNRATED.tier, minAge: UNRATED.minAge, maturity: null, from: rec ? 'asked, nothing stated' : 'not looked up', defaulted: true, record: rec };
}

function status() {
  load();
  const all = Object.values(state.ratings);
  const byTier = { 0: 0, 1: 0, 2: 0, 3: 0 };
  let rated = 0;
  let missing = 0;
  for (const r of all) {
    if (r.tier == null) { missing++; continue; }
    byTier[r.tier] = (byTier[r.tier] || 0) + 1;
    if (r.sources && r.sources.length) rated++;
  }
  return {
    file: FILE, entries: all.length, withCertificate: rated, askedNothing: missing, byTier,
    keys: Object.keys(readKeys()).filter((k) => k !== '_note' && readKeys()[k]), // never the values
    budgetMs: BUDGET_MS, missTtlMs: MISS_TTL_MS,
    upstreamCallsToday: spentToday(), dailyCap: DAILY_CAP, ladderRows: LADDER.length,
  };
}

module.exports = {
  LADDER, UNRATED, TIER3, ADULT_GENRE, PRIVATE_DIR,
  lookup, lookupWithin, peek, know, heuristic, status, ladderFor, imdbIdOf, adultAddonIds,
  FILE, KEYS_FILE, MISS_TTL_MS, DAILY_CAP,
};
