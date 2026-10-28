'use strict';
/*
 * core/addons.js — the addon host. Content only enters the hub through addons.
 *
 * Two kinds, one contract (design/plugin-architecture.md §2, restated in ADDONS.md):
 *   native    a file in providers/, loaded by core/registry.js at boot
 *   external  installed at runtime from data/addons.json: no code, no restart, and rows and rules
 *             cannot tell it apart from a native addon (they never mention an addon's name)
 *
 * An external addon is any service that speaks the community manifest protocol over plain JSON:
 *   GET /manifest.json
 *   GET /catalog/<type>/<id>/<extra>.json      extra = 'default' | 'search=…' | 'skip=…' | 'genre=…'
 *   GET /meta/<type>/<id>.json
 *   GET /stream/<type>/<id>.json               streams[] -> our sources[]
 *   GET /subtitles/<type>/<id>/<lang>.json     subtitles[] -> /app/v1/subs
 * The addon's own configuration rides in the URL path (that is how every community addon ships it),
 * e.g. https://host.tld/language=hindi,english|sizefilter=4GB/manifest.json — so the base for every
 * request is the manifest URL with /manifest.json removed, never the origin.
 *
 * Everything the hub asks an addon for on the home path is CACHE-ONLY: catalog()/sources() answer
 * from the 6 h cache and kick a background refresh; only an explicit detail page (meta/subtitles)
 * may wait, and only up to a budget. A dead addon costs an empty row, never a hung request.
 */

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const { URL, URLSearchParams } = require('url');

const settings = require('./settings');
const langdb = require('./langdb');

const ROOT = path.join(__dirname, '..');
const DATA_DIR = process.env.DATA_DIR || path.join(ROOT, 'data');
const ADDONS_FILE = process.env.ADDONS_FILE || path.join(DATA_DIR, 'addons.json');
const CACHE_FILE = process.env.ADDON_CACHE_FILE || path.join(DATA_DIR, 'addon-cache.json');

const TTL_MS = Number(process.env.ADDON_TTL_MS || 6 * 60 * 60 * 1000); // freshness rule, 6 h
const FETCH_TIMEOUT_MS = Number(process.env.ADDON_TIMEOUT_MS || 8000);
const META_WAIT_MS = Number(process.env.ADDON_META_WAIT_MS || 6000); // a detail page may wait this long
const MANIFEST_WAIT_MS = Number(process.env.ADDON_MANIFEST_WAIT_MS || 8000);
/** How soon after a failed manifest another ask may try again. A floor so a dead upstream is not hammered. */
const MANIFEST_RETRY_MS = Number(process.env.ADDON_MANIFEST_RETRY_MS || 30000);
// How many copies a picker shows, and how small a file has to be before it stops looking like a film.
// Both are the owner's setting (settings.addons), read per request; the env vars only seed it at install
// time. The names these shipped under were borrowed from someone else's protocol and are honoured for one
// release as a fallback, then dropped.
function maxSources() { return settings.get().addons.maxSources; }
function minSizeBytes() { return settings.get().addons.minSizeMB * 1e6; }
const WARM_STREAMS = Number(process.env.ADDON_WARM_STREAMS || 12); // identities whose streams we pre-fetch
const UA = process.env.ADDON_UA || 'Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 ScenixHub/1.0';

// Seed install: what a brand-new hub ships with, before anyone configures anything. Ids are named,
// not derived from the hostname, because they surface in the list the user reads in Settings.
// A hub that is already running picks these up too (see the seed merge in init()), so shipping a new
// addon in code is enough to put it on a family's TV.
//
// The list is built per boot rather than written out as a literal, because a community addon carries its
// own configuration inside its URL: baking one household's languages into that string would decide what a
// stranger's catalogue contains. `settings.languages` empty means no `language=` segment at all — ask the
// addon for everything it has and let this hub's own rules decide what is worth showing.
function seedAddons() {
  const langs = settings.languages()
    .map((code) => LANG_CODE_TO_NAME[code] || null)
    .filter(Boolean);
  const sizeFilter = String(settings.get().addons.sizeFilter || '').trim();
  const torrentioPath = [
    langs.length ? 'language=' + langs.join(',') : '',
    sizeFilter ? 'sizefilter=' + sizeFilter : '',
  ].filter(Boolean).join('|') || 'sort=qualitysize';
  const seeds = [
    { id: 'cinemeta', name: 'Cinemeta', url: 'https://v3-cinemeta.strem.io/manifest.json' },
    {
      id: 'torrentio',
      name: 'Torrentio',
      // A community addon carries its own configuration in the path, using commas AND pipes - which
      // is why SEED_ADDON_URLS below splits on a comma only where another URL follows it.
      // `language=` comes from the owner's languages, and is left out entirely when they named none.
      // `sizefilter=` is read by the addon as a list of size RANGES, not as one descending ceiling: the
      // old `25GB,8GB,4GB,2GB` asked for 2-4 GB, 4-8 GB and 8-25 GB and therefore hid every copy under
      // 2 GB — which is every 720p rip, the ones that actually start playing on a home connection. What
      // this household plays is still decided per profile by core/prefs.js (qualityCap) and
      // rules/size_cap.js + source_ranking.js; these two bands just make sure the fast-starting small
      // copies and a proper 1080p/4K copy are both on the table to choose between.
      url: 'https://torrentio.strem.fun/' + torrentioPath + '/manifest.json',
    },
    { id: 'opensubtitles', name: 'OpenSubtitles', url: 'https://opensubtitles-v3.strem.io/manifest.json' },
    // A streams-only addon that answers with the services a title sits on. Measured on the hub, its
    // public endpoint answers with *service links* (subscription / rent landing pages), not media files;
    // rules/source_ranking.js keeps those below a real file so play() never hands the TV a web page.
    { id: 'watchhub', name: 'WatchHub', url: 'https://watchhub.strem.io/manifest.json' },
  ];
  if (process.env.SEED_ADDON_URLS) {
    seeds.length = 0;
    for (const url of String(process.env.SEED_ADDON_URLS)
      .split(/,(?=https?:\/\/)/).map((s) => s.trim()).filter(Boolean)) {
      seeds.push({ id: '', name: null, url });
    }
  }
  return seeds;
}

/**
 * Whether two addresses name the same add-on. A source's configuration lives in the path, so equality has to
 * be on the whole address, not the host: torrentio with hindi,english and torrentio without are genuinely
 * different sources, while a trailing slash or a missing manifest name is the same one written twice.
 */
function sameAddress(a, b) {
  const fix = (u) => {
    let s = String(u || '').trim();
    if (/\/$/.test(s)) s += 'manifest.json';
    else if (!/manifest\.json$/.test(s)) s = s.replace(/\/+$/, '') + '/manifest.json';
    return s;
  };
  return !!fix(a) && fix(a) === fix(b);
}

const log = (...a) => console.log(new Date().toISOString(), ...a);

// ---- shared JSON cache: TTL in memory, per-item responses survive a restart ----------------------

const cache = new Map(); // url -> { at, body }
const inflight = new Map(); // url -> promise

function get(url, timeoutMs = FETCH_TIMEOUT_MS, ok404 = false) {
  return new Promise((resolve, reject) => {
    const mod = url.startsWith('https:') ? https : http;
    const req = mod.get(url, {
      headers: { 'User-Agent': UA, Accept: 'application/json' },
      timeout: timeoutMs,
    }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume();
        return resolve(get(new URL(res.headers.location, url).toString(), timeoutMs, ok404));
      }
      if (res.statusCode === 404 && ok404) {
        // "I have nothing for this id" is a protocol answer, not a failure: cache it empty.
        res.resume();
        return resolve('{}');
      }
      if (res.statusCode !== 200) {
        res.resume();
        return reject(new Error('HTTP ' + res.statusCode + ' for ' + url));
      }
      let body = '';
      res.setEncoding('utf8');
      res.on('data', (c) => (body += c));
      res.on('end', () => resolve(body));
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', reject);
  });
}

/**
 * Cache-first JSON GET.
 *  wait     - block on the network for this caller (a cold detail page may take one round trip)
 *  staleOk  - an expired copy answers now, the refresh happens in the background
 * Without either, a miss kicks the fetch and answers the stale copy or null: home never waits.
 */
function getJson(url, { wait = false, staleOk = false, timeoutMs = FETCH_TIMEOUT_MS, tag = '', ok404 = false } = {}) {
  const hit = cache.get(url);
  if (hit && Date.now() - hit.at < TTL_MS) return Promise.resolve(hit.body);
  if (!inflight.has(url)) {
    const p = get(url, timeoutMs, ok404)
      .then((body) => {
        const parsed = JSON.parse(body);
        cache.set(url, { at: Date.now(), body: parsed });
        scheduleCacheSave();
        return parsed;
      })
      .catch((e) => {
        // A connect failure often arrives as an AggregateError with no message at all; a blank log line
        // is exactly how one of these hunts went blind, so name the failure by whatever it does carry.
        if (tag) log('ADDON UPSTREAM FAIL', tag, url, e.message || e.code || e.name || String(e));
        return hit ? hit.body : null;
      })
      .finally(() => inflight.delete(url));
    inflight.set(url, p);
  }
  if (staleOk && hit) return Promise.resolve(hit.body);
  return wait ? inflight.get(url) : Promise.resolve(hit ? hit.body : null);
}

/**
 * The persisted per-title responses are read back at boot. data/addon-cache.json is written on every
 * fetch and data/ survives a deploy, so a restart does not put the family back to a cold hub: the
 * rows and detail pages that need sources answer from disk on the very first request.
 */
function loadPersistedCache() {
  try {
    const raw = JSON.parse(fs.readFileSync(CACHE_FILE, 'utf8'));
    let n = 0;
    for (const [url, entry] of Object.entries(raw && typeof raw === 'object' ? raw : {})) {
      if (entry && entry.body && Number.isFinite(entry.at)) {
        cache.set(url, entry);
        n++;
      }
    }
    if (n) log('ADDON cache restored', n, 'entries from', CACHE_FILE);
  } catch (e) {
    if (e.code !== 'ENOENT') log('ADDON cache restore failed', e.message);
  }
}
loadPersistedCache();

let saveTimer = null;
function scheduleCacheSave() {  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    saveTimer = null;
    try {
      const out = {};
      for (const [url, entry] of cache) {
        // Catalog bodies are re-fetched at boot anyway; per-title responses are what make a restart warm.
        if (/\/(meta|stream|subtitles)\//.test(url)) out[url] = entry;
      }
      fs.mkdirSync(path.dirname(CACHE_FILE), { recursive: true });
      const tmp = CACHE_FILE + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(out));
      fs.renameSync(tmp, CACHE_FILE);
    } catch (e) {
      // losing the cache write costs a round trip later, nothing else
    }
  }, 500);
  if (saveTimer.unref) saveTimer.unref();
}

/**
 * Forget every upstream answer this box is holding.
 *
 * A household that changed an addon's address, its size filter or its language list wants the old answers
 * gone now rather than aged out over hours, and a support conversation needs "start from nothing" without
 * someone deleting files over ssh. The in-memory map and the file that makes a restart warm are both
 * cleared, because clearing one and leaving the other is how a cache comes back after you deleted it.
 */
function clearCache() {
  const cleared = cache.size;
  cache.clear();
  if (saveTimer) { clearTimeout(saveTimer); saveTimer = null; }
  try { fs.writeFileSync(CACHE_FILE, '{}'); } catch (e) { /* no file is the goal, not a failure */ }
  return cleared;
}

// ---- upstream -> the hub item model (§1) ----------------------------------------------------------

const LANG_NAME_TO_CODE = {
  hindi: 'hin', english: 'eng', punjabi: 'pan', tamil: 'tam', telugu: 'tel', malayalam: 'mal',
  kannada: 'kan', bengali: 'ben', marathi: 'mar', urdu: 'urd', gujarati: 'guj', italian: 'ita',
  french: 'fre', spanish: 'spa', german: 'ger', japanese: 'jpn', korean: 'kor', russian: 'rus',
  chinese: 'chi', portuguese: 'por', persian: 'per', arabic: 'ara', turkish: 'tur', polish: 'pol',
};


/** The other direction: the hub's codes spelled the way an addon's own URL wants them. */
const LANG_CODE_TO_NAME = (() => {
  const out = {};
  for (const [name, code] of Object.entries(LANG_NAME_TO_CODE)) out[code] = name;
  return out;
})();

/** Trim to usable text, or nothing: an empty string on a tile reads like a field that failed. */
function cleanText(v) {
  return typeof v === 'string' ? v.trim().slice(0, 1200) : '';
}

/** A cast or crew list arrives as an array of strings, an array of objects naming a person, or — for the
credits, which a show usually has one of — a single comma-separated line. Keep the names, cap the length. */
function nameList(v) {
  if (typeof v === 'string') v = v.split(',');
  if (!Array.isArray(v)) return [];
  return v
    .map((x) => (typeof x === 'string' ? x.trim() : String((x && (x.person || x.name)) || '').trim()))
    .filter(Boolean)
    .slice(0, 12);
}

function langCodes(raw) {
  if (!raw) return null;
  const list = (Array.isArray(raw) ? raw : String(raw).split(/[,;]/))
    .map((s) => String(s).trim().toLowerCase())
    .filter(Boolean)
    .map((s) => LANG_NAME_TO_CODE[s] || (s.length === 3 ? s : null))
    .filter(Boolean);
  return list.length ? [...new Set(list)] : null;
}

function toMs(iso) {
  if (!iso) return null;
  const t = Date.parse(iso);
  return Number.isFinite(t) ? t : null;
}

function runtimeMs(runtime) {
  const m = /(\d+)\s*min/i.exec(String(runtime || ''));
  return m ? Number(m[1]) * 60000 : null;
}

const EIGHT_WEEKS_MS = 8 * 7 * 24 * 3600 * 1000;

/** "inCinemas = releasedAt within 8 weeks" — either side of today, so pre-release hype counts too. */
function inCinemas(releasedAt) {
  return Number.isFinite(releasedAt) && Math.abs(Date.now() - releasedAt) <= EIGHT_WEEKS_MS;
}

function humanSize(bytes) {
  if (!Number.isFinite(bytes)) return '';
  if (bytes >= 1e9) return (bytes / 1e9).toFixed(2).replace(/\.?0+$/, '') + ' GB';
  if (bytes >= 1e6) return Math.round(bytes / 1e6) + ' MB';
  return Math.round(bytes / 1e3) + ' KB';
}

/**
 * No analytics anywhere in this system, so `risk` is a transparent heuristic off the torrent's own
 * seeder count: 5 seeds is far likelier to stall on a 2 GB TV than 500 seeds.
 */
function riskOf(seeders) {
  if (seeders == null) return '1.0x';
  if (seeders >= 100) return '0.3x';
  if (seeders >= 30) return '0.5x';
  if (seeders >= 10) return '0.7x';
  return '0.9x';
}

const QUALITY_RANK = { '2160p': 5, '1080p': 4, '720p': 3, '480p': 2, '360p': 1 };

// What a fetchable media file looks like once you strip the addon's query string. Anything http that
// does not match is a page the viewer has to open, not a stream the player can read.
const MEDIA_EXT = /\.(mp4|mkv|webm|m4v|mov|avi|ts|m2ts|flv|wmv|m3u8|mpd)(\?|#|$)/i;

/** One upstream stream entry -> a source plus everything play() needs to resolve it later. */
function parseStream(entry) {
  const nameLines = String(entry.name || '').split('\n');
  const tag = (nameLines[1] || nameLines[0] || '').trim();
  const desc = String(entry.title || '');
  const hints = entry.behaviorHints || {};

  let quality = entry.quality || null;
  const qm = /(\d{3,4})p/i.exec(tag) || /(\d{3,4})p/i.exec(desc);
  if (!quality && qm) quality = qm[1] + 'p';
  else if (!quality && /\b4k\b|\b2160\b|uhd/i.test(tag + ' ' + desc)) quality = '2160p';

  let sizeBytes = Number.isFinite(entry.videoSize) ? entry.videoSize : null;
  if (sizeBytes == null) {
    const sm = /([\d.]+)\s*(GB|MB|KB)/i.exec(desc + ' ' + tag);
    if (sm) sizeBytes = Math.round(parseFloat(sm[1]) * { GB: 1e9, MB: 1e6, KB: 1e3 }[sm[2].toUpperCase()]);
  }
  const seeds = /👤\s*([\d.,]+)/.exec(desc);
  const seeders = seeds ? Number(seeds[1].replace(/[,.]/g, '')) : null;

  const magnet = (typeof entry.externalUrl === 'string' && entry.externalUrl.startsWith('magnet:'))
    ? entry.externalUrl : null;
  // Some addons spell a plain stream `url`, some `externalUrl`, some `yt`.
  const direct = entry.url || entry.externalUrl || entry.yt || null;
  const torrent = Boolean(entry.infoHash) || Boolean(magnet) || /magnet:/.test(String(direct || ''));
  // A "stream" that is really a link to a service page (the subscription/rent entries a service
  // catalog answers with) is not a file a TV can open. The protocol spells that `externalUrl` with no
  // `url`; some addons put the same landing page straight in `url`. Either way it stays in the list -
  // the app can offer "watch it on <service>" - but it is marked, so the ranking rule keeps it below a
  // real file and play() never hands the TV a web page.
  const looksLikeMedia = MEDIA_EXT.test(String(direct || ''));
  const link = !torrent && Boolean(direct) && (!looksLikeMedia || (!entry.url && Boolean(entry.externalUrl)));

  const firstLine = (nameLines[0] || '').trim();
  const label = link
    ? (firstLine || 'watch on ' + (direct ? String(direct).split('/')[2] : 'service')).slice(0, 60)
    : ([quality || (torrent ? 'torrent' : 'stream'), humanSize(sizeBytes)].filter(Boolean).join(' · ')
      || firstLine.slice(0, 60) || 'stream');

  // Audio language is rarely stated in the stream name; when it is, the ranking rule can use it.
  const langs = langCodes(entry.lang || entry.language ||
    (/\b(hindi|english|punjabi|tamil|telugu|malayalam|kannada|bengali|marathi|urdu)\b/i.exec(desc) || [])[1]);

  return {
    kind: torrent ? 'torrent' : (/\.m3u8(\?|$)/i.test(String(direct || '')) ? 'hls' : 'http'),
    link, // a service page, not a file: kept in the list, never picked by play()
    label,
    quality,
    sizeBytes,
    risk: torrent ? riskOf(seeders) : null,
    langs,
    infoHash: entry.infoHash || null,
    magnet,
    url: torrent ? null : (magnet ? null : direct) || null,
    filename: hints.filename || null,
    bingeGroup: hints.bingeGroup || null,
    proxyHeaders: hints.proxyHeaders || null,
    notWebReady: Boolean(hints.notWebReady),
    seeders,
  };
}

/**
 * streams[] -> sources[], with the policies that keep a TV picker usable: drop sample clips, keep the
 * best dozen (sharpest picture, then healthiest swarm). Applies to EVERY addon, not to one of them.
 */
function sourcesFromStreams(addonId, body) {
  const sources = [];
  const byLabel = new Map();
  for (const entry of (body && Array.isArray(body.streams) ? body.streams : [])) {
    const t = parseStream(entry);
    if (t.sizeBytes != null && t.sizeBytes < minSizeBytes()) continue;
    sources.push({
      provider: t.kind === 'torrent' ? 'torrent' : 'adaptive',
      label: t.label,
      kind: t.kind,
      link: t.link || null, // true = a link to a service page, not a file the player can open
      quality: t.quality,
      sizeBytes: t.sizeBytes,
      risk: t.risk,
      // The swarm count as the addon states it. `risk` is a lossy encoding of the same number, and the
      // picker needs the real one: a copy nobody is seeding is not a slow stream, it is a dead one.
      seeders: Number.isFinite(t.seeders) ? t.seeders : null,
      _addon: addonId,
      _langs: t.langs,
    });
    byLabel.set(t.label, t);
  }
  sources.sort((a, b) => (QUALITY_RANK[b.quality] || 0) - (QUALITY_RANK[a.quality] || 0)
    || ((byLabel.get(b.label) || {}).seeders || 0) - ((byLabel.get(a.label) || {}).seeders || 0));
  return { sources: spreadAcrossBands(sources), byLabel };
}

/**
 * Best picture first, but never one quality band filling every slot.
 *
 * A flat "keep the best twelve" cut decides the ceiling before the profile has been asked: a title with
 * forty 4K remuxes arrives as a 4K-only tile, the profile's size cap (8 GB at 1080p) then deletes every
 * source it was offered, and the film looks unplayable. Giving each band a share of the dozen keeps the
 * picker able to fall back a level, which is exactly what rules/source_ranking.js does with the list.
 */
const BAND_SHARE = [0.5, 0.3, 0.2]; // sharpest band gets half, the next ~a third, the rest ~a fifth
const UNKNOWN_BAND_CAP = 2; // a band where quality is only guesswork never takes more than two slots

function spreadAcrossBands(sources) {
  const MAX_SOURCES = maxSources(); // read once: the shares must add up to one number
  const bands = new Map(); // quality -> that band's sources, healthiest swarm first
  for (const s of sources) {
    const key = s.quality || 'unknown';
    if (!bands.has(key)) bands.set(key, []);
    bands.get(key).push(s);
  }
  const known = [...bands.keys()].filter((k) => k !== 'unknown')
    .sort((a, b) => (QUALITY_RANK[b] || 0) - (QUALITY_RANK[a] || 0));
  const order = [...known, ...(bands.has('unknown') ? ['unknown'] : [])];
  const cap = new Map();
  known.forEach((key, i) => cap.set(key, Math.ceil(MAX_SOURCES * (BAND_SHARE[i] || 0))));
  cap.set('unknown', UNKNOWN_BAND_CAP);

  const out = [];
  const taken = new Map();
  // Round-robin inside each band's share, so the twelve are the strongest entries *of every band*.
  for (let i = 0; out.length < MAX_SOURCES; i++) {
    let progressed = false;
    for (const key of order) {
      const got = taken.get(key) || 0;
      if (got >= (cap.get(key) || 0)) continue;
      const s = bands.get(key)[i];
      if (!s) continue;
      out.push(s);
      taken.set(key, got + 1);
      progressed = true;
      if (out.length >= MAX_SOURCES) break;
    }
    if (!progressed) break;
  }
  // Slots the shares left empty go back to the sharpest bands that still have sources.
  for (const key of order) {
    for (let i = taken.get(key) || 0; i < bands.get(key).length && out.length < MAX_SOURCES; i++) {
      out.push(bands.get(key)[i]);
      taken.set(key, i + 1);
    }
  }
  return out;
}

// ---- identity mapping ------------------------------------------------------------------------------

function identityFor(remoteId, addonId) {
  const id = String(remoteId || '');
  if (/^tt\d{5,}$/.test(id)) return 'imdb:' + id; // IMDb ids are global: they dedupe across addons
  if (/^yt:/.test(id)) return id;
  return addonId + ':' + id;
}

function remoteIdFor(identity, addonId) {
  const id = String(identity || '');
  if (id.startsWith(addonId + ':')) return id.slice(addonId.length + 1);
  if (id.startsWith('imdb:')) return id.slice(5);
  return id;
}

/**
 * Whether this addon can say anything about this identity at all. Namespaces are shared only for
 * `imdb:` and `yt:`; a `<publisherId>:<id>` identity belongs to whoever published it, so a live
 * channel tile is never asked of a movie addon (that used to cost a pointless 404 per tile).
 */
function addressableBy(identity, addonId) {
  const id = String(identity || '');
  return id.startsWith('imdb:') || id.startsWith('yt:') || id.startsWith(addonId + ':');
}

const TYPE_MAP = { movie: 'movie', series: 'series', tv: 'channel', channel: 'channel' };
function mapType(t) {
  return TYPE_MAP[String(t || '').toLowerCase()] || 'video';
}
// The other direction: our item model says "channel", the community protocol says "tv".
const COMMUNITY_TYPE = { movie: 'movie', series: 'series', channel: 'tv', tv: 'tv' };
function communityType(t) {
  return COMMUNITY_TYPE[String(t || '').toLowerCase()] || null;
}

// ---- the external addon ------------------------------------------------------------------------------

class External {
  constructor(entry) {
    this.id = entry.id;
    this.name = entry.name || entry.id;
    this.manifestUrl = entry.url;
    this.url = entry.url.replace(/\/manifest\.json\/?$/, ''); // base carries the addon's own config
    this.kind = 'video';
    this.priority = Number.isFinite(entry.priority) ? entry.priority : 40;
    this.native = false;
    this.external = true;
    this.enabled = entry.enabled !== false;
    this.addedAt = entry.addedAt || Date.now();

    this.manifest = null;
    this.resources = {}; // name -> {types, idPrefixes}
    this.cats = []; // hub catalog descriptors
    this.items = new Map(); // identity -> last item we mapped
    this.types = new Map(); // identity -> upstream type (stream/subtitle paths are type-scoped)
    this.streamIndex = new Map(); // identity -> Map(label -> parsed stream entry)
    this.streamSources = new Map(); // identity -> sources[]
    this.lastError = null;
    this.lastFetch = null;
    this.timer = null;
  }

  // ---- protocol plumbing ----

  accepts(identity) {
    const rid = remoteIdFor(identity, this.id);
    const prefixes = (this.manifest && this.manifest.idPrefixes) ||
      Object.values(this.resources).flatMap((r) => r.idPrefixes || []);
    if (!prefixes.length) return true;
    return prefixes.some((p) => rid.startsWith(p));
  }

  has(resource) {
    return Boolean(this.resources[resource]);
  }

  resourceTypes(resource, type) {
    const r = this.resources[resource];
    if (!r) return false;
    if (!r.types || !r.types.length) return true;
    return r.types.includes(type);
  }

  /**
   * Ask for the manifest again if we never got one. An addon whose manifest failed to arrive was mounted
   * but mute until its six-hour refresh tick: every catalog answered from cache and every metadata or stream
   * ask said "no such title", which on a bad second at boot meant a home screen with no rows and titles that
   * could not be opened at all - with nothing recorded about why. So every ask revives it, rate-limited, and
   * the reason lands in `lastError` where the health endpoint and the owner's addons list can show it.
   */
  async ensureManifest() {
    if (this.manifest) return true;
    const now = Date.now();
    if (this.nextManifestTry && now < this.nextManifestTry) return false;
    this.nextManifestTry = now + MANIFEST_RETRY_MS;
    return !!(await this.loadManifest({ wait: true }));
  }

  async loadManifest({ wait = false } = {}) {
    const body = await getJson(this.manifestUrl, { wait, staleOk: true, timeoutMs: MANIFEST_WAIT_MS, tag: this.id });
    if (!body || typeof body !== 'object') {
      this.lastError = 'no manifest came back from ' + this.manifestUrl;
      return null;
    }
    this.lastError = null;
    this.nextManifestTry = 0;
    this.manifest = body;
    this.resources = {};
    for (const r of (Array.isArray(body.resources) ? body.resources : [])) {
      if (typeof r === 'string') {
        this.resources[r] = { types: body.types, idPrefixes: body.idPrefixes };
      } else if (r && typeof r === 'object' && r.name) {
        this.resources[r.name] = { types: r.types, idPrefixes: r.idPrefixes };
      }
    }
    const types = new Set(Object.values(this.resources).flatMap((r) => r.types || []));
    if (types.has('tv') || types.has('channel')) this.kind = 'channel';
    else if (types.has('movie') || types.has('series')) this.kind = 'title';
    else if (this.has('subtitles') && !this.has('catalog')) this.kind = 'subtitle';
    this.cats = (Array.isArray(body.catalogs) ? body.catalogs : []).map((c) => ({
      id: c.type + '/' + c.id,
      name: c.name || c.id,
      type: c.type,
      remoteId: c.id,
      // A catalog only answers ?search= if it says so (older manifests use a boolean flag). The
      // protocol spells an extra as { name, isRequired }; `key` was a guess that matched nothing.
      extra: (Array.isArray(c.extra)
        ? c.extra.map((e) => (typeof e === 'string' ? e : e && (e.name || e.key)))
        : c.extra || []).filter(Boolean).concat(body.search === true ? ['search'] : []),
      // The values an addon will actually answer for, kept because it is the only honest list of what
      // genres exist here. A shelf that guesses a genre no addon publishes stays empty forever, and a
      // warm-up that hardcodes genres breaks the moment someone installs an addon with different ones.
      genreOptions: (Array.isArray(c.extra) ? c.extra : [])
        .map((e) => (typeof e === 'string' ? { name: e } : e || {}))
        .filter((e) => (e.name || e.key) === 'genre' && Array.isArray(e.options))
        .flatMap((e) => e.options).slice(0, 24),
    }));
    this.lastFetch = Date.now();
    return body;
  }

  streamPath(identity) {
    const type = this.types.get(identity) || 'movie';
    return this.url + '/stream/' + type + '/' + remoteIdFor(identity, this.id) + '.json';
  }

  metaPath(identity, type) {
    return this.url + '/meta/' + (type || this.types.get(identity) || 'movie') + '/'
      + remoteIdFor(identity, this.id) + '.json';
  }

  // ---- the addon contract (same shape a native provider file implements) ----

  start() {
    const tick = async () => {
      await this.loadManifest({ wait: true });
      const fresh = [];
      for (const c of this.cats) {
        const items = await this.fetchCatalog(c, null, { wait: true });
        fresh.push(...items.map((i) => i.identity));
      }
      if (this.has('stream')) this.warmStreams(fresh);
    };
    tick().catch((e) => { this.lastError = e.message; });
    this.timer = setInterval(() => tick().catch(() => {}), TTL_MS);
    if (this.timer.unref) this.timer.unref();
  }

  stop() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  async catalogs() {
    if (!this.manifest) this.ensureManifest(); // background: a shelf answers from cache, the next ask has it
    // `type` travels with the descriptor: a shelf that wants movies has to be able to tell a movie
    // catalog from a series catalog without reaching into the addon's internals.
    return this.cats.map((c) => ({
      id: c.id, name: c.name, type: c.type, extra: c.extra,
      // The values the addon itself said it answers. The owner's Categories screen offers exactly these;
      // a local genre list would be a guess that goes stale the moment another addon is installed.
      genreOptions: Array.isArray(c.genreOptions) ? c.genreOptions : [],
    }));
  }

  async fetchCatalog(cat, extra, { wait = false } = {}) {
    const parts = [];
    if (extra) {
      for (const key of ['search', 'genre', 'skip']) {
        if (extra[key] !== undefined && extra[key] !== null && String(extra[key]) !== '') {
          parts.push(key + '=' + encodeURIComponent(String(extra[key])));
        }
      }
    }
    const qs = parts.length ? parts.join('&') : 'default';
    const url = this.url + '/catalog/' + cat.type + '/' + cat.remoteId + '/' + qs + '.json';
    const body = await getJson(url, { wait, staleOk: true, tag: this.id });
    const metas = body && Array.isArray(body.metas) ? body.metas : [];
    const items = metas.map((m) => this.toItem(m, cat.type)).filter(Boolean);
    for (const it of items) this.items.set(it.identity, it);
    this.lastFetch = Date.now();
    return items;
  }

  /**
   * Fill the cache for every genre this addon says it answers, so the first home page after a restart is
   * full rather than short. Genre shelves read the cache and kick a background fetch on a miss, which means
   * the row a household looks at first is the one that pays for the fetch - and every shelf of this kind
   * came back empty on a fresh boot for exactly that reason. Sequential with a gap: these are the upstream's
   * own rate limits, not a suggestion.
   */
  async warmGenres({ perCatalog = 8, gapMs = 300 } = {}) {
    if (!this.cats.length) await this.loadManifest({ wait: true });
    let fetched = 0;
    for (const cat of this.cats) {
      for (const genre of (cat.genreOptions || []).slice(0, perCatalog)) {
        try {
          await this.fetchCatalog(cat, { genre });
          fetched++;
          await new Promise((r) => setTimeout(r, gapMs));
        } catch (e) { /* an addon that will not answer this genre is simply not asked again until TTL */ }
      }
    }
    return fetched;
  }

  toItem(meta, type) {
    const rid = meta.id;
    if (!rid) return null;
    const identity = identityFor(rid, this.id);
    const releasedAt = toMs(meta.released)
      || (meta.year ? (Date.parse(meta.year + '-01-01') || null) : null);
    const item = {
      identity,
      type: mapType(meta.type || type),
      title: meta.name || null,
      year: meta.year ? Number(meta.year) : null,
      poster: meta.poster || null,
      backdrop: meta.background || null,
      logo: meta.logo || null,
      match: meta.imdbRating ? Math.round(Number(meta.imdbRating) * 10) : null,
      maturity: meta.certification || null,
      durationMs: runtimeMs(meta.runtime),
      languages: langCodes(meta.language || meta.languages),
      genres: Array.isArray(meta.genres) ? meta.genres : Array.isArray(meta.genre) ? meta.genre : null,
      addedAt: null,
      releasedAt,
      inCinemas: inCinemas(releasedAt),
      live: null,
      progress: null,
      sources: [],
      // The words. A detail page built from a poster and a year is why someone leaves for an app that can
      // say what a thing is about, and this addon has been sending the synopsis, the cast and the crew the
      // whole time - we were simply not picking them up.
      overview: cleanText(meta.description) || null,
      tagline: cleanText(meta.tagline) || null,
      cast: nameList(meta.cast),
      crew: nameList(meta.director).concat(nameList(meta.writer)).filter(Boolean).slice(0, 4),
      country: cleanText(meta.country) || null,
      // A series is a list of episodes, and the community protocol addresses one as
      // `<imdbid>:<season>:<episode>`. Without this map the hub can only ask for the bare show id, and
      // Torrentio answers that with whichever single episode it feels like — which is why "Play" on a
      // series sometimes drops the viewer into S01E04. Cinemeta gives us the whole map for free.
      episodes: mapType(meta.type || type) === 'series' && Array.isArray(meta.videos)
        ? meta.videos
          .map((v) => ({
            season: Number(v.season) || null,
            episode: Number(v.episode) || null,
            // The real key for an episode name here is `name`; `title` is the community-protocol word and
            // most instances never send it. Reading only `title` is why every episode strip came up blank.
            title: cleanText(v.title) || cleanText(v.name) || null,
            synopsis: cleanText(v.overview) || cleanText(v.description) || null,
            releasedAt: toMs(v.released) || toMs(v.firstAired),
            thumb: v.thumbnail || null,
          }))
          .filter((e) => e.season && e.episode)
          .sort((a, b) => a.season - b.season || a.episode - b.episode)
        : null,
      _addons: [this.id],
    };
    // A catalog tile almost never states a language, but a /meta/ response does — and this box has
    // catalogued 1650 titles while answering meta for maybe a few dozen. So the question and its
    // answer are kept on disk: whatever meta taught us classifies the same title next time it shows up
    // as a tile, with nothing fetched. Unknown stays unknown; the language rule keeps classifying what
    // it can and says nothing about what it cannot.
    if (!item.languages) item.languages = langdb.peek(identity);
    else langdb.learn(identity, item.languages);
    const prev = this.items.get(identity);
    if (prev) {
      for (const k of Object.keys(item)) {
        if (item[k] === null && prev[k] !== null && k !== 'sources') item[k] = prev[k];
      }
    }
    this.types.set(identity, meta.type || type || 'movie');
    item.sources = this.sources(item); // cached streams, if we already have them
    return item;
  }

  async catalog(catalogId, extra = {}) {
    const cat = this.cats.find((c) => c.id === catalogId);
    if (!cat) return [];
    if (extra && extra.search) return this.fetchCatalog(cat, extra, { wait: true }); // typed by a person, worth one round trip
    // The extra travels on the cache-only path too: a shelf asking for Horror must not be handed the
    // genre-less default catalog because the addon happened to be cold. getJson is keyed by URL, and the
    // URL carries `genre=Horror`, so each genre is its own cache entry: cold answers empty and kicks the
    // fetch, warm answers from data/addon-cache.json on the next request.
    return this.fetchCatalog(cat, extra && Object.keys(extra).length ? extra : null);
  }

  /**
   * Metadata for one identity. The community type matters: cinemeta answers /meta/movie/tt0903747.json
   * with a completely different title (the 2008 film "Mirror") because IMDB ids are not typed, so the
   * type we already know - from the catalog that published this tile, or from the client's hint - is
   * asked first, and only an unknown identity falls back to guessing.
   */
  async meta(identity, hint) {
    await this.ensureManifest();
    if (!this.has('meta') || !addressableBy(identity, this.id)) return null;
    const known = this.types.get(identity) || null;
    // What the caller says beats what we guessed. One ask without a type used to settle the question for
    // good: a guessed `movie` answer landed here on an id that is ALSO a series upstream, and every later
    // call — even one that said `series` outright — asked the wrong type first because the remembered guess
    // came first in the list. Blind guesses prefer series, which is what this hub's catalogues mostly hold.
    const order = [...new Set([communityType(hint), known, 'series', 'movie', 'tv'].filter(Boolean))];
    let raw = null;
    let askedType = null;
    for (const t of order) {
      const body = await getJson(this.metaPath(identity, t),
        { wait: true, staleOk: true, timeoutMs: META_WAIT_MS, tag: this.id, ok404: true });
      if (body && body.meta && body.meta.id) {
        raw = body.meta;
        askedType = t;
        break; // the first type we trust that answers wins; a lower type is not tried
      }
    }
    if (!raw) return this.items.get(identity) || null;
    const item = this.toItem(raw, raw.type || askedType);
    if (item) {
      this.items.set(identity, item);
      if (this.has('stream')) {
        // streamPath now resolves through the type we just confirmed, so streams are asked correctly.
        await getJson(this.streamPath(identity),
          { wait: true, staleOk: true, timeoutMs: META_WAIT_MS, tag: this.id, ok404: true });
        item.sources = this.sources(item);
      }
    }
    return item;
  }

  /**
   * Cache-only source contribution (optional contract method). A streams-only addon (no catalogs)
   * still feeds sources to items other addons published, which is how metadata and streams from two
   * different addons end up as one tile's ranked picker.
   */
  sources(item) {
    if (!this.manifest) this.ensureManifest(); // background: rows are served from cache, this revives for the next one
    if (!this.has('stream')) return [];
    // `streamId` is the episode address (`imdb:tt123:1:4`) when the caller is playing one episode of a
    // series; the tile's own `identity` stays the show so dedupe, progress and rows keep working.
    const identity = (item && (item.streamId || item.identity)) || item.identity;
    if (!addressableBy(identity, this.id)) return [];
    const cached = this.streamSources.get(identity);
    if (cached) return cached;
    const hit = cache.get(this.streamPath(identity));
    if (!hit || !hit.body) return [];
    const parsed = sourcesFromStreams(this.id, hit.body);
    this.streamSources.set(identity, parsed.sources);
    this.streamIndex.set(identity, parsed.byLabel);
    this.lastFetch = Date.now();
    return parsed.sources;
  }

  /** Background warming: the top of every catalog is what the home screen draws, so stream it first. */
  warmStreams(identities) {
    (async () => {
      for (const identity of identities.filter((id) => addressableBy(id, this.id))
        .slice(0, WARM_STREAMS)) {
        try {
          await getJson(this.streamPath(identity), { wait: true, tag: this.id, ok404: true });
          this.sources({ identity });
        } catch (e) { /* a cold lookup is not worth logging loudly; the detail page retries */ }
      }
    })();
  }

  /**
   * One waiting stream lookup, for the detail page where a round trip is acceptable. Rows never call
   * this: the home screen answers from cache and the same sources show up on the next request.
   */
  async fetchStreams(identity) {
    await this.ensureManifest();
    if (!this.has('stream') || !addressableBy(identity, this.id)) return [];
    await getJson(this.streamPath(identity),
      { wait: true, timeoutMs: META_WAIT_MS, tag: this.id, ok404: true });
    return this.sources({ identity });
  }

  /**
   * The URL this source would hand the player, WITHOUT a round trip or a child process, plus the
   * headers that request needs. core/probe.js asks this before it measures a source: play() is not an
   * option there, because for some addons play() means shelling out to yt-dlp for a signed URL, and a
   * measurement taken from a URL the player may never receive is worth nothing.
   * Returns null when this source has no http(s) endpoint to measure: a magnet (a promise about a
   * swarm) or a link to a service page. That answer is itself useful — the app sees `checked: false`.
   */
  sourceTarget(identity, source) {
    const index = this.streamIndex.get(identity);
    if (!index || !index.size) return null;
    const entry = (source && source.label && index.get(source.label)) || null;
    if (!entry || entry.kind === 'torrent' || entry.link || !entry.url) return null;
    if (!/^https?:\/\//i.test(entry.url)) return null;
    return { url: entry.url, headers: (entry.proxyHeaders && entry.proxyHeaders.request) || {} };
  }

  /**
   * The magnet behind a torrent source, from the very index play() uses — so the torrent engine is
   * pointed at the identical torrent the Play button would choose. The ranked rows a client sees are
   * display shapes (label, quality, size); the hash lives here. Null for anything that is not a torrent.
   */
  magnetFor(identity, source) {
    const index = this.streamIndex.get(identity);
    if (!index || !index.size) return null;
    const entry = (source && source.label && index.get(source.label)) || null;
    if (!entry || entry.kind !== 'torrent') return null;
    return entry.magnet
      || (entry.infoHash
        ? 'magnet:?xt=urn:btih:' + entry.infoHash
          + (entry.filename ? '&dn=' + encodeURIComponent(entry.filename) : '')
        : null)
      || null;
  }

  async play(identity, source) {
    await this.ensureManifest();
    if (!this.has('stream')) return null;
    const index = this.streamIndex.get(identity);
    if (!index || !index.size) return null;
    const entry = (source && source.label && index.get(source.label)) || [...index.values()][0];
    if (!entry) return null;
    if (entry.kind === 'torrent') {
      const magnet = entry.magnet
        || (entry.infoHash ? 'magnet:?xt=urn:btih:' + entry.infoHash
          + (entry.filename ? '&dn=' + encodeURIComponent(entry.filename) : '') : null);
      if (!magnet) return null;
      return {
        url: magnet,
        headers: {},
        kind: 'torrent',
        expiresAt: null,
        infoHash: entry.infoHash || null,
        bingeGroup: entry.bingeGroup || null,
      };
    }
    if (!entry.url) return null;
    const headers = (entry.proxyHeaders && entry.proxyHeaders.request) || {};
    // A service catalog answers some titles with a link to the app that streams them (WatchHub:
    // "Amazon Prime Video", "YouTube"). It is a real answer and stays in the picker, but `kind: "link"`
    // says what it is: a page to open, not a file to hand to the video player.
    if (entry.link) {
      return { url: entry.url, headers: {}, kind: 'link', link: true, expiresAt: null,
        label: entry.label || null };
    }
    return {
      url: entry.url,
      headers,
      kind: entry.kind,
      link: false,
      expiresAt: null,
      needsProxy: entry.notWebReady || Boolean(Object.keys(headers).length),
      bingeGroup: entry.bingeGroup || null,
    };
  }

  async subtitles(identity, langs) {
    await this.ensureManifest();
    if (!this.has('subtitles') || !addressableBy(identity, this.id)) return [];
    const type = this.types.get(identity) || 'movie';
    const rid = remoteIdFor(identity, this.id);
    const out = [];
    for (const lang of langs) {
      const url = this.url + '/subtitles/' + type + '/' + rid + '/' + lang + '.json';
      const body = await getJson(url, { wait: true, staleOk: true, timeoutMs: META_WAIT_MS, tag: this.id, ok404: true });
      for (const s of (body && Array.isArray(body.subtitles) ? body.subtitles : [])) {
        if (!s || !s.url) continue;
        out.push({
          id: String(s.id || (this.id + ':' + rid + ':' + lang)),
          addon: this.id,
          lang: s.lang || lang,
          url: s.url,
        });
      }
    }
    return out;
  }

  stats() {
    return {
      id: this.id,
      name: this.name,
      url: this.manifestUrl,
      native: false,
      enabled: this.enabled,
      kind: this.kind,
      resources: Object.keys(this.resources),
      catalogs: this.cats.length,
      items: this.items.size,
      streams: this.streamSources.size,
      lastFetch: this.lastFetch ? new Date(this.lastFetch).toISOString() : null,
      error: this.lastError,
      addedAt: this.addedAt,
    };
  }
}

// ---- the installed-addons store (data/addons.json) + hot load -------------------------------------

let registry = null;
let store = null;
const installed = new Map(); // id -> External
const nativeMods = new Map(); // id -> provider module (so a native addon can be re-enabled at runtime)

function readEntries() {
  try {
    const raw = JSON.parse(fs.readFileSync(ADDONS_FILE, 'utf8'));
    return Array.isArray(raw) ? raw : Array.isArray(raw.addons) ? raw.addons : [];
  } catch (e) {
    return null; // no file yet
  }
}

function writeEntries(entries) {
  fs.mkdirSync(path.dirname(ADDONS_FILE), { recursive: true });
  const tmp = ADDONS_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(entries, null, 2) + '\n');
  fs.renameSync(tmp, ADDONS_FILE);
}

function entries() {
  return readEntries() || [];
}

function slug(name, url) {
  const base = String(name || url || 'addon')
    .toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 32);
  let id = base || 'addon';
  let n = 2;
  while (installed.has(id) || (registry && registry.byId[id])) id = base + '-' + n++;
  return id;
}

function registerAddon(addon) {
  installed.set(addon.id, addon);
  if (addon.enabled) {
    const problem = registry.add(addon);
    if (problem) log('ADDON REGISTER FAIL', addon.id, '-', problem);
  }
  return addon;
}

/**
 * Hot-load: from this moment the addon's catalogs are in /app/v1/manifest, its items reach every
 * library-based row, and its streams feed the picker. No restart, no filesystem change beyond the
 * one JSON file.
 */
function mount(entry) {
  const addon = new External(entry);
  registerAddon(addon);
  if (addon.enabled) {
    try {
      addon.start();
    } catch (e) {
      addon.lastError = e.message;
      log('ADDON START FAIL', addon.id, e.message);
    }
  }
  return addon;
}

function unmount(id) {
  const addon = installed.get(id);
  if (!addon) return null;
  addon.stop();
  installed.delete(id);
  registry.remove(id);
  return addon;
}

// ---- public API ------------------------------------------------------------------------------------

/**
 * Boot the addon host. Every native provider file is mirrored into data/addons.json, so the same
 * surface (enable / disable) works for both kinds and a hub shows one honest list of what serves it.
 */
function init(reg, st) {
  registry = reg;
  store = st;
  for (const p of registry.providers) if (p.native) nativeMods.set(p.id, p);

  let list = readEntries();
  if (list === null) {
    const now = Date.now();
    list = [
      ...seedAddons().map((a, i) => ({
        id: a.id || slugFromUrl(a.url), name: a.name, url: a.url,
        enabled: true, addedAt: now, priority: 40 + i,
      })),
      ...registry.providers.map((p) => ({
        id: p.id, name: p.name || p.id, native: true, enabled: true, addedAt: now,
      })),
    ];
    try {
      writeEntries(list);
      log('ADDONS seeded', ADDONS_FILE, '-', list.filter((e) => !e.native).length, 'external addons');
    } catch (e) {
      log('ADDONS seed write failed', e.message);
    }
  }

  // A native addon file added after the seed (the "one file = one feature" case) joins the list too.
  const seen = new Set(list.map((e) => e.id));
  let addedNative = false;
  for (const p of registry.providers) {
    if (p.native && !seen.has(p.id)) {
      list.push({ id: p.id, name: p.name || p.id, native: true, enabled: true, addedAt: Date.now() });
      addedNative = true;
    }
  }
  if (addedNative) {
    try {
      writeEntries(list);
    } catch (e) { /* the list is still correct in memory */ }
  }

  /**
   * Seed merge. data/ survives a deploy, so an addon added to SEED_ADDONS after this hub was first
   * installed would otherwise never reach a running one - shipping a feature in code has to be enough.
   * An entry a person configured by hand (`configured`) is left exactly as they left it; for our own
   * seeds the config string is ours, so a raised size filter or a fixed endpoint is re-applied on boot
   * and the change is logged rather than silently made.
   */
  const byId = new Map(list.map((e) => [e.id, e]));
  let touched = false;
  let lastPriority = 39;
  for (const e of list) if (!e.native) lastPriority = Math.max(lastPriority, Number(e.priority) || 40);
  for (const seed of seedAddons()) {
    const existing = byId.get(seed.id);
    // Keying only on the id meant that an owner who installed a default source themselves - under whatever
    // name that install chose - got a second copy of it under the default name at the next restart. The
    // address decides whether two entries are the same source, exactly as it does when adding one.
    const elsewhere = existing ? null : list.find((e) => e && !e.native && sameAddress(e.url, seed.url));
    if (elsewhere) {
      log('ADDONS seed skipped', seed.id, '-', seed.url, 'already installed as', elsewhere.id);
    } else if (!existing) {
      list.push({
        id: seed.id, name: seed.name, url: seed.url,
        enabled: true, addedAt: Date.now(), priority: ++lastPriority,
      });
      touched = true;
      log('ADDONS seeded', seed.id, '-', seed.url);
    } else if (!existing.native && !existing.configured && existing.url !== seed.url) {
      log('ADDONS seed config updated', seed.id, ':', existing.url, '->', seed.url);
      existing.url = seed.url;
      touched = true;
    }
  }
  if (touched) {
    try {
      writeEntries(list);
    } catch (e) { log('ADDONS seed merge write failed', e.message); }
  }

  for (const entry of list) {
    if (entry.native) {
      const mod = registry.byId[entry.id];
      if (!mod) continue;
      if (entry.name) mod.name = entry.name;
      nativeMods.set(entry.id, mod);
      if (entry.enabled === false) {
        registry.remove(entry.id);
        log('ADDON disabled at boot', entry.id);
      }
      installed.set(entry.id, { entry, native: true });
      continue;
    }
    if (!entry.url) continue;
    try {
      mount(entry);
    } catch (e) {
      log('ADDON MOUNT FAIL', entry.id || entry.url, e.message);
    }
  }
  return { file: ADDONS_FILE, external: [...installed.values()].filter((x) => !x.native).length };
}

function slugFromUrl(url) {
  try {
    const u = new URL(url);
    return slug(u.hostname.split('.')[0], url);
  } catch (e) {
    return slug(String(url));
  }
}

/**
 * Re-apply the seed configuration after the owner changed a setting the seeds are built from — their
 * languages, the size bands to ask for. A community addon's configuration *is* its URL, so changing the
 * setting means handing the addon a different question, and the answer it gave the old one is not an
 * answer to the new: the addon is remounted on the new URL and its stream cache simply stops matching.
 * An entry a person configured by hand is left exactly as they left it, same rule as the seed merge in
 * init(). This runs on a settings change so the owner sees the effect on the next request, not on the
 * next restart.
 */
function resyncSeeds() {
  const list = entries();
  const seeds = new Map(seedAddons().map((s) => [s.id, s]));
  const remount = [];
  for (const entry of list) {
    if (entry.native || entry.configured) continue;
    const seed = seeds.get(entry.id);
    if (!seed || !seed.url || entry.url === seed.url) continue;
    log('ADDONS settings resynced', entry.id, ':', entry.url, '->', seed.url);
    entry.url = seed.url;
    remount.push(entry.id);
  }
  if (!remount.length) return { changed: 0 };
  try {
    writeEntries(list);
  } catch (e) {
    log('ADDONS settings resync write failed', e.message);
  }
  for (const id of remount) {
    unmount(id);
    const entry = list.find((x) => x.id === id);
    if (entry) mount(entry);
  }
  return { changed: remount.length };
}

/** GET /app/v1/addons — native and installed, with live counts and the last error for each. */
async function list() {
  const rows = [];
  for (const p of registry.providers) {
    if (!p.native) continue;
    const entry = (installed.get(p.id) || {}).entry || {};
    let stats = {};
    try {
      stats = typeof p.stats === 'function' ? p.stats() : {};
    } catch (e) {
      stats = { error: e.message };
    }
    let cats = [];
    try {
      cats = (typeof p.catalogs === 'function' ? await p.catalogs() : []) || [];
    } catch (e) { /* reported through error */ }
    rows.push({
      id: p.id,
      name: p.name || p.id,
      native: true,
      kind: p.kind,
      priority: p.priority,
      enabled: entry.enabled !== false,
      addedAt: entry.addedAt || null,
      catalogs: cats.map((c) => ({ id: c.id, name: c.name })),
      error: stats.error || null,
      counts: stats,
    });
  }
  for (const addon of installed.values()) {
    if (!addon || addon.native) continue;
    // Same shape as a native row: `catalogs` is a list the app can render, counts is an object.
    rows.push({
      id: addon.id,
      name: addon.name || addon.id,
      native: false,
      kind: addon.kind,
      priority: addon.priority,
      enabled: addon.enabled,
      addedAt: addon.addedAt || null,
      url: addon.manifestUrl,
      resources: Object.keys(addon.resources || {}),
      catalogs: (addon.cats || []).map((c) => ({ id: c.id, name: c.name })),
      error: addon.lastError || null,
      counts: { items: addon.items.size, streams: addon.streamSources.size, lastFetch: addon.lastFetch || null },
    });
  }
  rows.sort((a, b) => Number(a.native === false) - Number(b.native === false)
    || String(a.id).localeCompare(String(b.id)));
  return rows;
}

/** POST /app/v1/addons — validate the manifest, persist, hot-load. Returns {ok, addon} or {ok, error}. */
async function add(url, name) {
  if (!/^https?:\/\//i.test(String(url || ''))) return { ok: false, status: 400, error: 'url must be http(s)' };
  let manifestUrl = String(url).trim();
  if (/\/$/.test(manifestUrl)) manifestUrl += 'manifest.json';
  if (!/manifest\.json$/.test(manifestUrl)) manifestUrl = manifestUrl.replace(/\/+$/, '') + '/manifest.json';
  for (const existing of installed.values()) {
    if (existing && !existing.native && sameAddress(existing.manifestUrl, manifestUrl)) {
      return { ok: false, status: 409, error: 'already installed', id: existing.id };
    }
  }
  const probe = new External({ id: 'probe', url: manifestUrl });
  const manifest = await probe.loadManifest({ wait: true });
  if (!manifest) return { ok: false, status: 502, error: 'manifest not reachable', url: manifestUrl };
  if (!manifest.id && !manifest.name && !manifest.catalogs && !manifest.resources) {
    return { ok: false, status: 422, error: 'not an addon manifest', url: manifestUrl };
  }
  const list0 = entries();
  const id = slug(manifest.name || name || manifest.id || manifestUrl, manifestUrl);
  if (registry.byId[id]) return { ok: false, status: 409, error: 'id already taken', id };
  const entry = {
    id,
    name: name || manifest.name || manifest.id || id,
    url: manifestUrl,
    enabled: true,
    addedAt: Date.now(),
    priority: 40 + list0.filter((e) => !e.native).length,
    configured: true, // someone chose this URL: the seed merge in init() must not overwrite it
  };
  list0.push(entry);
  writeEntries(list0);
  const addon = mount(entry);
  return { ok: true, addon };
}

/** DELETE /app/v1/addons/<id> — hot-remove: the row empties on the next request. */
function remove(id) {
  const all = entries();
  const kept = all.filter((e) => e.id !== id);
  if (kept.length === all.length && !installed.has(id)) return false;
  writeEntries(kept);
  unmount(id);
  return true;
}

/** enable/disable without deleting — a broken addon can be switched off from the app's Settings. */
function setEnabled(id, enabled) {
  const list0 = entries();
  const entry = list0.find((e) => e.id === id);
  if (!entry) return null;
  entry.enabled = Boolean(enabled);
  writeEntries(list0);
  const known = installed.get(id);

  if (entry.native) {
    const mod = nativeMods.get(id) || registry.byId[id];
    if (!mod) return { id, native: true, enabled: entry.enabled, note: 'native addon file not present' };
    if (enabled) {
      registry.add(mod);
      mod.enabled = true;
    } else {
      registry.remove(id);
      mod.enabled = false;
    }
    if (known) known.entry = entry;
    return { id, name: mod.name || id, native: true, enabled: entry.enabled };
  }

  if (!known) return null;
  if (enabled && !registry.byId[id]) {
    known.enabled = true;
    registry.add(known);
    try {
      known.start();
    } catch (e) { known.lastError = e.message; }
  } else if (!enabled && registry.byId[id]) {
    known.stop();
    known.enabled = false;
    registry.remove(id);
  }
  return known.stats();
}

function byId(id) {
  const known = installed.get(id);
  return known && !known.native ? known : null;
}

module.exports = {
  init, list, add, remove, setEnabled, byId, entries, resyncSeeds, seedAddons, clearCache,
  // Background cache fill for every genre each mounted addon declares. `installed` holds the live objects -
  // `entries()` is the saved config and has no methods to call, which is the kind of mistake that makes a
  // warm-up look like it ran while it fetched nothing at all. Native providers sit in the same map wrapped
  // as `{ entry, native }`, so the method check is what leaves them out.
  async warmGenres() {
    let n = 0;
    for (const a of installed.values()) {
      if (a && !a.native && a.entry && a.entry.enabled === false) continue;
      if (a && typeof a.warmGenres === 'function') n += await a.warmGenres();
    }
    return n;
  },
  paths: { ADDONS_FILE, CACHE_FILE },
  // A getter, so a client reading its limits sees the owner's current choices rather than the values this
  // process happened to have when it started.
  get limits() {
    return { TTL_MS, FETCH_TIMEOUT_MS, META_WAIT_MS,
      MAX_SOURCES: maxSources(), MIN_SIZE_BYTES: minSizeBytes(), WARM_STREAMS };
  },
  util: { langCodes, humanSize, parseStream, sourcesFromStreams, identityFor, remoteIdFor, mapType },
};
