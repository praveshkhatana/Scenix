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
  live: { playlist: '' },
  // How the addon host behaves. These were env-var constants; they are the owner's to set now.
  addons: { maxSources: 12, minSizeMB: 100, sizeFilter: '200MB-1.5GB,1.5GB-8GB' },
  // Which of this hub's home rows a viewer sees, and in what order. Both lists are empty by default:
  // every hub ships the rows its own plugins define, in the order those plugins ask for, and an owner's
  // edits land here as an override rather than a rewrite. `hidden` names rows to leave off the screen;
  // `order` is the sequence they should appear in, with anything it does not mention following behind.
  rows: { hidden: [], order: [] },
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

function normalize(s) {
  const out = {
    hubName: String(s.hubName || DEFAULTS.hubName).trim().slice(0, 60) || DEFAULTS.hubName,
    languages: normalizeLanguages(s.languages),
    live: { playlist: String((s.live || {}).playlist || '').trim().slice(0, 400) },
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
    avatar: {
      baseUrl: String((s.avatar || {}).baseUrl || '').trim().slice(0, 300),
      superheroApi: String((s.avatar || {}).superheroApi == null
        ? DEFAULTS.avatar.superheroApi : (s.avatar || {}).superheroApi).trim().slice(0, 300),
      wikiApi: String((s.avatar || {}).wikiApi == null
        ? DEFAULTS.avatar.wikiApi : (s.avatar || {}).wikiApi).trim().slice(0, 300),
    },
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

/** Merge a patch into the file and return what it became. Nested objects merge key by key. */
function set(patch) {
  const current = read();
  const next = normalize(merge(current, patch && typeof patch === 'object' ? patch : {}));
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    const tmp = FILE + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(next, null, 2));
    fs.renameSync(tmp, FILE);
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

module.exports = { get, set, languages, status, DEFAULTS, FILE };
