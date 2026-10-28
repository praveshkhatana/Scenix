'use strict';
/*
 * core/settings.js — what the owner chose, read at request time.
 *
 * This file exists to enforce one rule (design/deployment-modes.md): **no household-specific value is a
 * constant in the code.** The languages a family watches, which live-TV playlist they pull, how many
 * torrent copies a picker shows — those belong to the person who installed the hub, not to the build they
 * downloaded. A hub that hardcodes someone else's language list hands a stranger in Brazil a product that
 * hides half of its own catalogue and calls that a feature.
 *
 * So: values live in data/settings.json, are editable through GET/PUT /app/v1/settings, and are read fresh
 * by whoever needs them. Environment variables are install-time seeding only — a way to pre-fill this
 * file on first boot (Docker, a deploy script), never the place an owner has to reach for a screwdriver.
 * A setting nobody can change in the UI is a bug.
 *
 * Shipped defaults are NEUTRAL: no languages preselected (the content filter is simply off), no live-TV
 * playlist, the limits a hub needs anyway. A hub boots working and the owner chooses theirs. This
 * household's choices are in this box's data/settings.json, and data/ is the one directory a deploy
 * never overwrites.
 *
 * Hot reload without a watcher: the file is re-read as soon as its mtime changes, so a PUT takes effect on
 * the next request and an edit made in a text editor works too. Writes go through a temp file and a
 * rename, like every other file in data/ — a hub killed mid-write must not come back with half a settings
 * object.
 */

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const DATA_DIR = process.env.DATA_DIR || path.join(ROOT, 'data');
const FILE = process.env.SETTINGS_FILE || path.join(DATA_DIR, 'settings.json');

/**
 * Neutral defaults. Anything here has to be true of a hub anywhere; anything that is one family's taste
 * arrives through the UI and lands in the JSON file instead.
 */
const DEFAULTS = {
  // What this hub calls itself where a client lists what it found on the network. Not a machine name:
  // the owner gives it, and two hubs on one LAN must be tellable apart by a person, not by a hostname.
  hubName: 'Scenix Hub',
  // ISO-639-2/T codes, the form the hub's own rules and profiles work in (hin, eng, pan).
  // Empty means: no language filter at all — every copy the addons return is allowed.
  languages: [],
  // Live TV. One playlist URL, because that is what an owner can actually find and paste; empty means no
  // live TV row rather than a row full of somebody else's country.
  // Live TV: one playlist URL and the owner's selection over it. `hidden` names channel identities
  // (`norm:<slug>`) the household does not want; `groupsHidden` removes a whole genre; `order` is the
  // owner's own sequence, with anything it does not mention following in the provider's order. All three
  // ship empty, so a fresh hub shows the playlist as published and the owner edits from the UI.
  live: { playlist: '', hidden: [], groupsHidden: [], order: [] },
  // How the addon host behaves. These were env-var constants; they are the owner's to set now.
  addons: { maxSources: 12, minSizeMB: 100, sizeFilter: '200MB-1.5GB,1.5GB-8GB' },
  // Which of this hub's home rows a viewer sees, and in what order. Both lists are empty by default:
  // every hub ships the rows its own plugins define, in the order those plugins ask for, and an owner's
  // edits land here as an override rather than a rewrite. `hidden` names rows to leave off the screen;
  // `order` is the sequence they should appear in, with anything it does not mention following behind.
  rows: { hidden: [], order: [] },
  // Owner-made shelves: a provider's catalogue, optionally narrowed to one genre it publishes. This is
  // the one place "more categories" lives, because which genres a household wants is not something a
  // build can know. Each entry is { id, title, provider, catalog, genre, enabled, order }; `genre` is
  // empty for the whole catalogue. They are virtual rows on /app/v1/home — no file is written under
  // rows/ — and they follow `rows.hidden`/`rows.order` like every plugin row once their id is known.
  categories: [],
  // Generated profile faces. Empty means this hub does not proxy any avatar service and a client draws
  // its own; pointing it at one (self-hosted or public) makes the hub fetch and cache through it, so the
  // TV still only ever talks to this box.
  avatar: {
    baseUrl: '', // a generated-avatar service to proxy; empty means clients draw their own faces
    // The two catalogues character faces come from. Both are free and need no key or account; empty
    // switches that source off for an owner who would rather not call that host at all.
    superheroApi: 'https://akabab.github.io/superhero-api/api',
    wikiApi: 'https://en.wikipedia.org/api/rest_v1/page/summary/',
  },

  // Playback smoothness. The complaint this whole block exists for is "the picture stops"; the two ways a
  // hub fixes it without paying a caching service are (a) refuse a copy whose measured rate cannot carry
  // its own bitrate and (b) read ahead of the player into RAM, so a torrent that bursts and pauses plays
  // like a file on a server. Both are the owner's call, so both live here and on the Settings screen.
  // What does NOT live here is "what counts as stuck" — that stays a timing rule in code (see
  // Scenix/design/playback-strategy.md §5). These are how much of the film we hold, not how long we wait.
  playback: {
    readAhead: true,      // buffer torrent bytes in the hub ahead of the player at all
    readAheadMB: 96,      // the cushion, in bytes of RAM, per copy
    readAheadSec: 90,     // the same cushion as seconds of media; whichever is smaller wins
    preBufferSec: 15,     // hold this much picture before promising the first frame
    sustainBudgetMs: 4000, // how long a Play press spends learning a copy's real, sustained rate
    starvedWindows: 2,    // slow windows in a row before the hub moves the viewer to a smaller copy
    // Kept out of the Settings screen because an owner has no reason to touch them:
    readAheadCopies: 2,      // how many copies may be cushioned at the same time
    readAheadIdleMs: 45000,  // release a cushion this long after the last reader hangs up
    preBufferMB: 32,         // never pre-roll more bytes than this, whatever the seconds say
    preBufferMs: 6000,       // never delay the first frame longer than this to get the pre-roll
    rateWindowMs: 1000,      // one measurement window; the worst window in a sample is the verdict
    starvedFloor: 1,         // a copy is slow when its rate falls under its own bitrate times this
    starvedGraceSec: 20,     // a cushion this deep absorbs a slow window without calling it starvation
    starvedHoldMs: 900000,   // how long a copy that starved a viewer stays out of favour
  },

  // Optional debrid/direct resolver (core/debrid.js). Off, with no provider and no token, because a
  // paid account is the owner's decision and must never be required for this hub to work. When it is
  // configured, a magnet or a file-hoster link can become an ordinary HTTP stream before the torrent
  // gateway is asked. `token` is a private API token: it is written here, read by core/debrid.js, and
  // never returned by GET /app/v1/settings. An empty token in a PUT means "keep the stored one"; null
  // explicitly removes it.
  debrid: { enabled: false, provider: '', token: '' },
};

let cached = null; // the last parsed+merged object, or null when the file has not been read yet
let cachedAt = -1; // mtimeMs of the file behind `cached`
let cachedMissing = false;

/** Copy that keeps nested defaults nested: {languages:[…]} merged over DEFAULTS, never a shallow patch. */
function merge(base, patch) {
  const out = {};
  for (const k of Object.keys(base)) out[k] = base[k];
  for (const [k, v] of Object.entries(patch || {})) {
    out[k] = (v && typeof v === 'object' && !Array.isArray(v) && base[k] && typeof base[k] === 'object')
      ? merge(base[k], v) : v;
  }
  return out;
}

/** Language codes arrive as "hin,eng,pan" or ["HIN"," eng "]; they leave as clean 3-letter codes. */
function normalizeLanguages(raw) {
  const list = Array.isArray(raw) ? raw : String(raw == null ? '' : raw).split(/[,;]/);
  const out = [];
  for (const item of list) {
    const code = String(item || '').trim().toLowerCase().replace(/[^a-z]/g, '');
    if (!code) continue;
    if (!out.includes(code)) out.push(code);
  }
  return out;
}

/** Row ids come from a screen that listed them, so they only need trimming and de-duplicating. */
function idList(raw) {
  const out = [];
  for (const item of Array.isArray(raw) ? raw : []) {
    const id = String(item || '').trim().slice(0, 64);
    if (id && !out.includes(id)) out.push(id);
  }
  return out;
}

/**
 * Playback smoothness, clamped: a screen that sends `readAheadMB: 999999` would otherwise ask this box
 * for 1 TB of RAM, and a hub that swaps is a hub that stalls. Bounds here are about the machine surviving
 * a typo, not about what the household should choose — those stay in DEFAULTS.
 */
function num(raw, dflt, lo, hi) {
  const n = Number(raw);
  return Number.isFinite(n) ? Math.min(hi, Math.max(lo, n)) : dflt;
}

function normalizePlayback(p) {
  const d = DEFAULTS.playback;
  const src = p && typeof p === 'object' ? p : {};
  const out = {};
  for (const k of Object.keys(d)) {
    if (k === 'readAhead') { out[k] = src[k] !== false; continue; }
    out[k] = num(src[k], d[k], 0, 1e9);
  }
  // Sizes and seconds have their own sane ceilings; a 4 GB cushion on a 32 GB box that also runs the
  // torrent engine is not a preference, it is a swap.
  out.readAheadMB = num(src.readAheadMB, d.readAheadMB, 8, 2048);
  out.readAheadSec = num(src.readAheadSec, d.readAheadSec, 5, 900);
  out.preBufferSec = num(src.preBufferSec, d.preBufferSec, 0, 180);
  out.preBufferMB = num(src.preBufferMB, d.preBufferMB, 1, 512);
  out.preBufferMs = num(src.preBufferMs, d.preBufferMs, 0, 20000);
  out.sustainBudgetMs = num(src.sustainBudgetMs, d.sustainBudgetMs, 500, 20000);
  out.rateWindowMs = num(src.rateWindowMs, d.rateWindowMs, 250, 30000);
  out.starvedWindows = Math.round(num(src.starvedWindows, d.starvedWindows, 1, 10));
  out.starvedFloor = num(src.starvedFloor, d.starvedFloor, 0.2, 3);
  out.starvedGraceSec = num(src.starvedGraceSec, d.starvedGraceSec, 0, 300);
  out.readAheadCopies = Math.round(num(src.readAheadCopies, d.readAheadCopies, 1, 8));
  out.readAheadIdleMs = num(src.readAheadIdleMs, d.readAheadIdleMs, 3000, 600000);
  out.starvedHoldMs = num(src.starvedHoldMs, d.starvedHoldMs, 10000, 86400000);
  return out;
}

/** A stable, URL-safe piece of an id. Titles change; the id a row is addressed by must not. */
function slugPart(raw) {
  return String(raw || '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40);
}

/**
 * The owner's category shelves, cleaned and renumbered.
 *
 * A category is only meaningful with a provider and a catalogue, so an entry missing either is dropped:
 * a broken row on the home screen is worse than a category the owner has to add again. Ids are kept when
 * they are safe, generated from the three fields when they are not, and made unique so two "Action" rows
 * from different providers remain two rows. `order` is renumbered in the stored sequence, which is what
 * the home route and the Categories screen both read.
 */
function normalizeCategories(raw) {
  const list = Array.isArray(raw) ? raw : [];
  const parsed = [];
  const seen = new Set();
  list.forEach((entry, index) => {
    if (!entry || typeof entry !== 'object' || Array.isArray(entry)) return;
    const provider = String(entry.provider || '').trim().slice(0, 80);
    const catalog = String(entry.catalog || '').trim().slice(0, 120);
    if (!provider || !catalog) return;
    const genre = String(entry.genre == null ? '' : entry.genre).trim().slice(0, 80);
    let id = String(entry.id || '').trim().toLowerCase()
      .replace(/[^a-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 64);
    if (!id) id = [provider, catalog, genre].filter(Boolean).map(slugPart).join('-').slice(0, 64) || 'category';
    let unique = id;
    let n = 2;
    while (seen.has(unique)) {
      unique = id.slice(0, 60).replace(/-+$/, '') + '-' + n;
      n++;
    }
    seen.add(unique);
    const title = String(entry.title || '').trim().slice(0, 60)
      || [genre, catalog].filter(Boolean).join(' · ') || 'Category';
    const order = Number(entry.order);
    parsed.push({
      id: unique,
      title,
      provider,
      catalog,
      genre,
      enabled: entry.enabled !== false,
      order: Number.isFinite(order) ? order : index,
    });
  });
  parsed.sort((a, b) => a.order - b.order);
  return parsed.map((entry, index) => Object.assign(entry, { order: index }));
}

/**
 * The optional debrid settings, in the one shape core/debrid.js reads. `provider` is a slug because it
 * names a file in core/debrid/; the token is kept verbatim (trimmed) because providers do not all use
 * the same alphabet, and a token that is shortened or lowercased is a token that does not work.
 */
function normalizeDebrid(d) {
  const src = d && typeof d === 'object' ? d : {};
  return {
    enabled: src.enabled === true,
    provider: String(src.provider || '').trim().toLowerCase().replace(/[^a-z0-9-]/g, '').slice(0, 32),
    token: String(src.token == null ? '' : src.token).trim().slice(0, 512),
  };
}

function normalize(s) {
  const out = {
    hubName: String(s.hubName || DEFAULTS.hubName).trim().slice(0, 60) || DEFAULTS.hubName,
    languages: normalizeLanguages(s.languages),
    live: {
      playlist: String((s.live || {}).playlist || '').trim().slice(0, 400),
      hidden: idList((s.live || {}).hidden),
      groupsHidden: idList((s.live || {}).groupsHidden),
      order: idList((s.live || {}).order),
    },
    addons: {
      maxSources: Math.max(1, Math.min(60, Number((s.addons || {}).maxSources) || DEFAULTS.addons.maxSources)),
      minSizeMB: Math.max(0, Math.min(10000, Number((s.addons || {}).minSizeMB) || DEFAULTS.addons.minSizeMB)),
      sizeFilter: String((s.addons || {}).sizeFilter == null
        ? DEFAULTS.addons.sizeFilter : (s.addons || {}).sizeFilter).trim().slice(0, 200),
    },
    rows: {
      hidden: idList((s.rows || {}).hidden),
      order: idList((s.rows || {}).order),
    },
    categories: normalizeCategories(s.categories),
    avatar: {
      baseUrl: String((s.avatar || {}).baseUrl || '').trim().slice(0, 300),
      superheroApi: String((s.avatar || {}).superheroApi == null
        ? DEFAULTS.avatar.superheroApi : (s.avatar || {}).superheroApi).trim().slice(0, 300),
      wikiApi: String((s.avatar || {}).wikiApi == null
        ? DEFAULTS.avatar.wikiApi : (s.avatar || {}).wikiApi).trim().slice(0, 300),
    },
    playback: normalizePlayback(s.playback),
    debrid: normalizeDebrid(s.debrid),
  };
  // Anything the owner put in the file that this build does not know about is kept, so an older hub
  // reading a newer file does not quietly delete a setting a newer hub wrote.
  for (const k of Object.keys(s || {})) {
    if (!(k in out)) out[k] = s[k];
  }
  return out;
}

/** Install-time seeding: env vars fill in a key only where the file has nothing for it yet. */
function seedFromEnv(s) {
  if (process.env.LANGS !== undefined && !s.languages.length) s.languages = normalizeLanguages(process.env.LANGS);
  if (process.env.HUB_NAME && s.hubName === DEFAULTS.hubName) s.hubName = String(process.env.HUB_NAME).trim();
  if (process.env.PLAYLIST_URL && !s.live.playlist) s.live.playlist = String(process.env.PLAYLIST_URL).trim();
  const max = process.env.ADDON_MAX_SOURCES;
  const min = process.env.ADDON_MIN_SIZE_MB;
  if (max !== undefined && s.addons.maxSources === DEFAULTS.addons.maxSources) s.addons.maxSources = Number(max);
  if (min !== undefined && s.addons.minSizeMB === DEFAULTS.addons.minSizeMB) s.addons.minSizeMB = Number(min);
  // Read-ahead is on by default; an owner who wants the old pass-through pipe, or a smaller cushion on a
  // smaller box, can say so at install time without opening the UI.
  if (process.env.READ_AHEAD === '0' && s.playback.readAhead === DEFAULTS.playback.readAhead) {
    s.playback.readAhead = false;
  }
  const ra = process.env.READ_AHEAD_MB;
  if (ra !== undefined && s.playback.readAheadMB === DEFAULTS.playback.readAheadMB) s.playback.readAheadMB = Number(ra);
  // A debrid account is optional, so these only fill a blank install: an owner who turned it off in the
  // UI must not have it switched back on by an environment variable left in a compose file.
  if (process.env.DEBRID_ENABLED === '1' && s.debrid.enabled === DEFAULTS.debrid.enabled) {
    s.debrid.enabled = true;
  }
  if (process.env.DEBRID_PROVIDER && !s.debrid.provider) {
    s.debrid.provider = normalizeDebrid({ provider: process.env.DEBRID_PROVIDER }).provider;
  }
  if (process.env.DEBRID_TOKEN && !s.debrid.token) {
    s.debrid.token = String(process.env.DEBRID_TOKEN).trim().slice(0, 512);
  }
  return s;
}

function read() {
  let st = null;
  try {
    st = fs.statSync(FILE);
  } catch (e) {
    st = null;
  }
  if (cached && st && st.mtimeMs === cachedAt) return cached;
  let raw = {};
  if (st) {
    try {
      raw = JSON.parse(fs.readFileSync(FILE, 'utf8'));
    } catch (e) {
      // A settings file that does not parse is a broken install, not a licence to serve defaults as if
      // they were the owner's choices: say so once, loudly, and keep the hub up on the defaults.
      if (!cachedMissing) console.log(new Date().toISOString(), 'SETTINGS unreadable, using defaults:', FILE, e.message);
      raw = {};
    }
  }
  const base = normalize(merge(DEFAULTS, raw && typeof raw === 'object' ? raw : {}));
  // The environment seeds a hub that has no settings file yet — an installation, not a household that has
  // already chosen. Once the file exists the UI owns these values, including a deliberately empty language
  // list: re-reading LANGS on every boot would silently undo an owner who switched the filter off.
  cached = st ? base : normalize(seedFromEnv(base));
  cachedAt = st ? st.mtimeMs : -1;
  cachedMissing = !st;
  return cached;
}

function get() {
  return read();
}

/** The content policy in the one form every consumer wants: clean codes, empty = filter off. */
function languages() {
  return read().languages.slice();
}

/**
 * The playback smoothness knobs, in the form core/readahead.js wants. A fresh object every call, so a
 * settings PUT changes the next byte streamed without a restart.
 */
function playback() {
  return Object.assign({}, read().playback);
}

/**
 * A deep copy of the settings-shaped JSON. Used only to answer a browser: the live object is cached and
 * shared, and a screen must never be able to mutate the hub's copy of its own configuration.
 */
function clone(v) {
  if (v === null || typeof v !== 'object') return v;
  if (Array.isArray(v)) return v.map(clone);
  const out = {};
  for (const [k, x] of Object.entries(v)) out[k] = clone(x);
  return out;
}

/**
 * The settings a browser may see. A debrid token is write-only: the answer says whether one is stored,
 * never what it is. This is the only shape GET /app/v1/settings and the PUT reply are allowed to send.
 */
function redacted(which) {
  const out = clone(which === undefined ? read() : which);
  if (out && out.debrid && typeof out.debrid === 'object') {
    const token = String(out.debrid.token || '');
    out.debrid.tokenSet = !!token;
    delete out.debrid.token;
  }
  return out;
}

/**
 * A PUT from the settings screen carries the whole debrid object it last read, and that object cannot
 * contain the token. An empty token therefore means "keep the one already stored", not "erase it"; an
 * explicit null is the way to remove it.
 */
function scrubSecretPatch(patch) {
  const p = clone(patch);
  if (!p || typeof p !== 'object' || !p.debrid || typeof p.debrid !== 'object') return p;
  const d = p.debrid;
  if (Object.prototype.hasOwnProperty.call(d, 'token')) {
    if (d.token === null) d.token = '';
    else if (typeof d.token === 'string') {
      const token = d.token.trim();
      if (token) d.token = token; else delete d.token;
    } else {
      // A number, object or array is not a token. Treat it like a field the screen did not have, so a
      // malformed PUT cannot replace a working credential with "[object Object]".
      delete d.token;
    }
  }
  // `tokenSet` is an answer from redacted(), never a value the file stores.
  delete d.tokenSet;
  return p;
}

/** Merge a patch into the file and return what it became. Nested objects merge key by key. */
function set(patch) {
  const current = read();
  const scrubbed = scrubSecretPatch(patch && typeof patch === 'object' ? patch : {});
  const next = normalize(merge(current, scrubbed));
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    const tmp = FILE + '.tmp';
    // This file can hold a paid account's API token. Create it readable by its owner only; rename
    // replaces the old inode, so an older 0644 settings.json does not keep its mode.
    fs.writeFileSync(tmp, JSON.stringify(next, null, 2), { mode: 0o600 });
    fs.renameSync(tmp, FILE);
    try { fs.chmodSync(FILE, 0o600); } catch (e) { /* a filesystem without chmod; write still succeeded */ }
  } catch (e) {
    console.log(new Date().toISOString(), 'SETTINGS write failed:', FILE, e.message);
  }
  cached = next;
  try {
    cachedAt = fs.statSync(FILE).mtimeMs;
    cachedMissing = false;
  } catch (e) {
    cachedAt = -1;
  }
  return next;
}

function status() {
  return {
    file: FILE,
    present: !cachedMissing && cached !== null,
    keys: Object.keys(read()),
    languages: languages(),
    hubName: read().hubName,
  };
}

module.exports = { get, set, languages, playback, status, redacted, DEFAULTS, FILE };
