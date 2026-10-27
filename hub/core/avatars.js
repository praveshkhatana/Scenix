'use strict';
/*
 * core/avatars.js — faces for profiles, held on this box.
 *
 * A profile needs a face, and a generated blob looks like a placeholder. Character art — the superhero a
 * teenager likes, the cartoon a younger child asks for — is what makes a household's screen feel like
 * theirs. But a TV on this network should not be learning third-party hostnames, should not pay a round
 * trip every time a profile screen opens, and should still show a person when the internet is down. So
 * the hub fetches each face once, keeps the bytes, and serves them itself: one peer, warm forever.
 *
 * Two sources, both the owner's to configure and neither hardcoded in a screen:
 *
 *   characters  a shipped list (`characters.json`) of well-known characters. The list is content, not
 *               configuration: `data/characters.json` overrides it entry by entry, so a household can put
 *               their own favourites there, and the Hub UI writes exactly that file. Each entry names the
 *               source to resolve it against, because the two sources cover different people:
 *                 superherodb  a free, key-less catalogue of 563 comic characters - Marvel, DC and the
 *                              independents - with clean portrait renders and real metadata (publisher,
 *                              full name, alignment, power stats). This is what a superhero entry uses.
 *                 wikipedia    everyone that catalogue does not know: cartoons, animation, film
 *                              characters, looked up by article title, where the lead image is sometimes
 *                              a poster and sometimes a statue.
 *               `page` is the lookup key in both cases.
 *   generated   an upstream avatar service, proxied and cached for the same one-peer reason. Off by
 *               default: the setting (`avatar.baseUrl`) is empty until an owner points it somewhere.
 *
 * Licences are recorded rather than assumed. Wikipedia's character images are a mix of freely licensed
 * files and single-purpose fair-use covers, so each entry carries the page it came from and whatever
 * licence the API stated, and a client that shows these in a product shows the credit with them.
 */
const fs = require('fs');
const path = require('path');
const settings = require('./settings');

const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, '..', 'data');
const SEED_FILE = path.join(__dirname, '..', 'characters.json');
const OVERRIDE_FILE = path.join(DATA_DIR, 'characters.json');
const FACE_DIR = path.join(DATA_DIR, 'avatar-faces');
const CACHE_DIR = path.join(DATA_DIR, 'avatar-cache');

const UA = 'ScenixHub/1.0 (self-hosted media hub; https://github.com/praveshkhatana/Scenix)';
const TIMEOUT_MS = Number(process.env.AVATAR_TIMEOUT_MS || 6000);
// Wikipedia throttles a caller that arrives in a burst, and a throttled picture is simply a missing face on
// the profile screen. One at a time, with a pause, plus one patient retry, is what an install does once.
const GAP_MS = Number(process.env.AVATAR_GAP_MS || 350);
const MAX_BYTES = Number(process.env.AVATAR_MAX_BYTES || 512 * 1024); // one picture
const MAX_CATALOGUE_BYTES = 8 * 1024 * 1024; // a whole character catalogue is a different kind of answer
const TTL_MS = Number(process.env.AVATAR_TTL_MS || 30 * 24 * 3600 * 1000); // a character does not recast itself
const WIKI_DEFAULT = 'https://en.wikipedia.org/api/rest_v1/page/summary/';
const HEROES_DEFAULT = 'https://akabab.github.io/superhero-api/api';

function wikiApi() {
  const s = settings.get();
  return String((s.avatar && s.avatar.wikiApi) || '').trim() || WIKI_DEFAULT;
}

/** Empty means the comic-character catalogue is switched off, which is a choice an owner can make. */
function heroesApi() {
  const s = settings.get();
  const raw = (s.avatar && s.avatar.superheroApi === undefined) ? HEROES_DEFAULT : String(s.avatar.superheroApi || '');
  return raw.trim();
}

function readJson(file, fallback) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return fallback; }
}

function log(...args) { console.log(new Date().toISOString(), ...args); }

function slugOf(s) {
  return String(s || '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
}

function fresh(file) {
  try { return Date.now() - fs.statSync(file).mtimeMs < TTL_MS; } catch { return false; }
}

/** The owner's list wins entry by entry, so a household can replace the shipped one without a code change. */
function seed() {
  const base = readJson(SEED_FILE, { characters: [] });
  const over = readJson(OVERRIDE_FILE, null);
  const out = new Map();
  const add = (list) => {
    for (const c of Array.isArray(list) ? list : []) {
      const page = String((c && (c.page || c.name)) || '').trim();
      if (!page) continue;
      const source = c.source === 'superherodb' ? 'superherodb' : 'wikipedia';
      out.set(page + '|' + source, { name: String(c.name || page).trim(), page, source });
    }
  };
  add(base && base.characters);
  if (over) add(Array.isArray(over) ? over : over.characters);
  // One card per character: the shipped list and the owner's file may name the same person through
  // different sources, and a picker that draws Spider-Man twice is a bug, not a feature.
  const seen = new Set();
  return [...out.values()].filter((c) => {
    if (seen.has(c.page)) return false;
    seen.add(c.page);
    return true;
  });
}

async function getOnce(url, headers = {}, maxBytes = MAX_BYTES) {
  const res = await fetch(url, {
    headers: { 'user-agent': UA, accept: '*/*', ...headers },
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  if (!res.ok) {
    const err = new Error('upstream ' + res.status);
    err.throttled = res.status === 429 || res.status === 503;
    const after = Number(res.headers.get('retry-after'));
    err.retryAfter = Number.isFinite(after) ? after * 1000 : 0;
    throw err;
  }
  const buf = Buffer.from(await res.arrayBuffer());
  if (!buf.length) throw new Error('empty answer');
  if (buf.length > maxBytes) throw new Error('answer too large (' + buf.length + ' bytes)');
  return { buf, type: res.headers.get('content-type') || 'application/octet-stream' };
}

/** A throttled answer is worth waiting for once; a 404 is not worth anything. */
async function getBytes(url, headers = {}, maxBytes = MAX_BYTES) {
  for (let try_ = 0; try_ < 2; try_++) {
    try {
      return await getOnce(url, headers, maxBytes);
    } catch (e) {
      if (try_ === 0 && (e.throttled || /timeout|abort/i.test(e.message))) {
        await new Promise((r) => setTimeout(r, e.retryAfter || 2500));
        continue;
      }
      return { error: e.message };
    }
  }
  return { error: 'unreachable' };
}

const nap = (ms = GAP_MS) => new Promise((r) => setTimeout(r, ms));

// ---- characters: a curated list, resolved once, kept on disk ---------------------------------------

/**
 * Pixel size read out of the bytes themselves. Neither source states dimensions beside its picture, and a
 * card cannot crop a standing poster the way it crops a wide cast banner without knowing which it holds.
 */
function sniffDims(buf) {
  if (buf.length > 24 && buf[0] === 0x89 && buf[1] === 0x50) return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
  if (buf.length > 24 && buf[0] === 0xff && buf[1] === 0xd8) {
    let i = 2;
    while (i + 9 < buf.length) {
      if (buf[i] !== 0xff) { i++; continue; }
      const marker = buf[i + 1];
      if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
        return { height: buf.readUInt16BE(i + 5), width: buf.readUInt16BE(i + 7) };
      }
      i += 2 + buf.readUInt16BE(i + 2);
    }
  }
  if (buf.length > 30 && buf.slice(0, 4).toString() === 'RIFF' && buf.slice(8, 12).toString() === 'WEBP') {
    const form = buf.slice(12, 16).toString();
    if (form === 'VP8X') return { width: 1 + buf.readUIntLE(20, 3), height: 1 + buf.readUIntLE(23, 3) };
    if (form === 'VP8 ') return { width: buf.readUInt16LE(26) & 0x3fff, height: buf.readUInt16LE(28) & 0x3fff };
    if (form === 'VP8L') {
      const bits = buf.readUInt32LE(21);
      return { width: 1 + (bits & 0x3fff), height: 1 + ((bits >> 14) & 0x3fff) };
    }
  }
  return { width: 0, height: 0 };
}

// ---- the two character sources --------------------------------------------------------------------

let heroesCache = null; // { at, list: [{name, slug, publisher, fullName, alignment, stats, md, lg}] }

/**
 * The whole comic catalogue, fetched once and kept: 700 KB of JSON, no key, no quota, no rate limit worth
 * mentioning, and a month between asks. One file answers every superhero entry, so a household adding a
 * favourite pays a lookup rather than a round trip.
 */
async function heroesCatalogue() {
  const api = heroesApi();
  if (!api) return null;
  const file = path.join(CACHE_DIR, 'superherodb.json');
  if (heroesCache) return heroesCache.list;
  try {
    if (fresh(file)) {
      heroesCache = { at: fs.statSync(file).mtimeMs, list: JSON.parse(fs.readFileSync(file, 'utf8')) };
      return heroesCache.list;
    }
  } catch { /* not cached yet, which is the first-run case */ }
  const got = await getBytes(api.replace(/\/+$/, '') + '/all.json', {}, MAX_CATALOGUE_BYTES);
  if (got.error) return null;
  let all = [];
  try { all = JSON.parse(got.buf.toString('utf8')); } catch { return null; }
  const list = (Array.isArray(all) ? all : []).map((h) => ({
    name: String(h.name || ''),
    slug: String(h.slug || ''),
    publisher: String((h.biography && h.biography.publisher) || ''),
    fullName: String((h.biography && h.biography.fullName) || ''),
    alignment: String((h.biography && h.biography.alignment) || ''),
    stats: h.powerstats || {},
    md: (h.images && h.images.md) || '',
    lg: (h.images && h.images.lg) || '',
  })).filter((h) => h.name && (h.md || h.lg));
  if (!list.length) return null;
  heroesCache = { at: Date.now(), list };
  try {
    fs.mkdirSync(CACHE_DIR, { recursive: true });
    fs.writeFileSync(file, JSON.stringify(list));
  } catch { /* the next ask refetches, which is the right failure mode */ }
  return list;
}

async function resolveSuperhero(entry) {
  const catalogue = await heroesCatalogue();
  if (!catalogue) return null;
  const asked = String(entry.page || entry.name).trim().toLowerCase();
  const hero = catalogue.find((h) => h.name.toLowerCase() === asked)
    || catalogue.find((h) => h.slug && slugOf(h.slug) === slugOf(asked));
  if (!hero) return null;
  const got = await getBytes(hero.lg || hero.md);
  if (got.error) return null;
  const dims = sniffDims(got.buf);
  const powerScore = ['intelligence', 'strength', 'speed', 'durability', 'power', 'combat']
    .reduce((n, k) => n + (Number(hero.stats[k]) || 0), 0);
  return {
    buf: got.buf,
    meta: {
      name: entry.name,
      page: heroesApi(),
      title: hero.name,
      description: [
        hero.fullName && hero.fullName.toLowerCase() !== hero.name.toLowerCase() ? hero.fullName : '',
        hero.publisher, hero.alignment,
      ].filter(Boolean).join(' - '),
      attribution: 'SuperHeroDB',
      licence: 'the catalogue is MIT; the artwork belongs to the character publisher',
      width: dims.width, height: dims.height, type: got.type,
      publisher: hero.publisher, fullName: hero.fullName, alignment: hero.alignment, powerScore,
    },
  };
}

async function resolveWikipedia(entry) {
  const base = wikiApi();
  if (!base) return null;
  const res = await fetch(base + encodeURIComponent(entry.page), {
    headers: { 'user-agent': UA, 'accept-language': 'en' },
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  if (!res.ok) return null;
  let summary;
  try { summary = await res.json(); } catch { return null; }
  // A description page can point at a logo, a map or a coat of arms, and an SVG line drawing has no face
  // in it. Beyond that this hub does not second-guess what counts as a picture of a character.
  const url = (summary.thumbnail && summary.thumbnail.source)
    || (summary.originalimage && summary.originalimage.source);
  if (!url || /\.(svg)(\?|$)/i.test(url)) return null;
  const got = await getBytes(url);
  if (got.error) return null;
  const dims = {
    width: (summary.thumbnail && summary.thumbnail.width) || (summary.originalimage && summary.originalimage.width) || 0,
    height: (summary.thumbnail && summary.thumbnail.height) || (summary.originalimage && summary.originalimage.height) || 0,
  };
  if (!dims.width) Object.assign(dims, sniffDims(got.buf));
  if (dims.width && dims.width < 120) return null;
  return {
    buf: got.buf,
    meta: {
      name: entry.name,
      page: summary.content_urls && summary.content_urls.desktop
        ? summary.content_urls.desktop.page : 'https://en.wikipedia.org/wiki/' + encodeURIComponent(entry.page),
      title: summary.title || entry.page,
      description: summary.description || '',
      attribution: 'Wikipedia',
      licence: summary.license ? (summary.license.type || '') : '',
      licenceUrl: summary.license ? (summary.license.url || '') : '',
      width: dims.width, height: dims.height, type: got.type,
    },
  };
}

const metaPath = (slug) => path.join(FACE_DIR, slug + '.json');
const imgPath = (slug) => path.join(FACE_DIR, slug + '.img');

/**
 * Resolve one character to a cached picture. Returns the entry ready for the wire, or null when this
 * character has no usable image (a page that does not exist, or one whose only picture is a logo).
 */
async function ensureCharacter(entry, force = false) {
  const slug = slugOf(entry.page);
  if (!slug) return null;
  let meta = null;
  try { meta = JSON.parse(fs.readFileSync(metaPath(slug), 'utf8')); } catch { /* not fetched yet */ }
  if (meta && !force && fresh(metaPath(slug))) {
    try { fs.accessSync(imgPath(slug)); return { ...meta, slug }; } catch { meta = null; }
  }

  const resolve = entry.source === 'superherodb' ? resolveSuperhero : resolveWikipedia;
  let got = null;
  try { got = await resolve(entry); } catch { got = null; }
  if (!got || !got.buf) return meta ? { ...meta, slug } : null; // unknown or offline: keep what we have

  const out = { ...got.meta, source: entry.source || 'wikipedia', fetchedAt: Date.now() };
  try {
    fs.mkdirSync(FACE_DIR, { recursive: true });
    fs.writeFileSync(imgPath(slug), got.buf);
    fs.writeFileSync(metaPath(slug), JSON.stringify(out));
  } catch { /* a failed write means the next ask tries again, which is the right failure mode */ }
  return { ...out, slug };
}

let warming = null;

/** A deterministic shuffle, so a picker can offer "another set" without the hub remembering anything. */
function orderBySeed(list, s) {
  if (!s) return list;
  const key = (str) => {
    let x = 2166136261;
    for (let i = 0; i < str.length; i++) { x ^= str.charCodeAt(i); x = Math.imul(x, 16777619); }
    return x >>> 0;
  };
  return [...list].sort((a, b) => key(a.id + '|' + s) - key(b.id + '|' + s) || (a.id < b.id ? -1 : 1));
}

/** The list a client draws, and the only place a face is ever fetched from. */
async function characters({ limit = 40, refresh = false, wait = false, seed: pick = '' } = {}) {
  const list = seed();
  const picked = list.slice(0, Math.max(1, Math.min(200, Number(limit) || 40)));
  const out = [];
  for (const entry of picked) {
    const hit = readCached(entry);
    if (hit && !refresh) { out.push(hit); continue; }
    // A screen asks for faces and gets the faces this box already has. Fetching the rest is a background
    // job (warm), because a profile screen should not sit waiting on somebody else's image host.
    if (!wait) { warm(); continue; }
    const got = await ensureCharacter(entry, refresh);
    if (got) out.push(got);
    await nap();
  }
  return orderBySeed(out.map((c) => ({
    id: c.slug,
    name: c.name,
    label: c.name,
    image: '/app/v1/avatars/character/' + c.slug,
    source: c.attribution,
    page: c.page,
    licence: c.licence || null,
    licenceUrl: c.licenceUrl || null,
    description: c.description || '',
    // What the source knows about the character, for a picker that can say more than a name.
    from: c.source || 'wikipedia',
    publisher: c.publisher || null,
    fullName: c.fullName || null,
    alignment: c.alignment || null,
    powerScore: c.powerScore || null,
    width: c.width || 0,
    height: c.height || 0,
    portrait: (c.height || 0) >= (c.width || 0),
  })), pick);
}

function readCached(entry) {
  const slug = slugOf(entry.page);
  if (!slug) return null;
  try {
    const meta = JSON.parse(fs.readFileSync(metaPath(slug), 'utf8'));
    fs.accessSync(imgPath(slug));
    return { ...meta, slug };
  } catch { return null; }
}

/**
 * Fetch every face we do not have yet, slowly, in the background. Started at boot so a household's first
 * profile screen already shows characters, and never awaited by a request.
 */
function warm() {
  if (warming) return warming;
  const missing = seed().filter((e) => !readCached(e));
  if (!missing.length) return Promise.resolve(0);
  warming = (async () => {
    prune();
    let made = 0;
    for (const entry of missing) {
      if (await ensureCharacter(entry)) made++;
      await nap();
    }
    log('AVATARS cached', made, 'of', missing.length, 'listed faces');
    return made;
  })().finally(() => { warming = null; });
  return warming;
}

/**
 * Remove cached faces the current list no longer asks for. An entry that changes its page or its source
 * gets a new file, and without this the old picture sits in data/ forever, counted as a face we offer.
 */
function prune() {
  const wanted = new Set(seed().map((e) => slugOf(e.page)));
  let files = [];
  try { files = fs.readdirSync(FACE_DIR); } catch { return 0; }
  let gone = 0;
  for (const f of files) {
    if (!/\.(img|json)$/.test(f)) continue;
    if (wanted.has(f.replace(/\.(img|json)$/, ''))) continue;
    try { fs.unlinkSync(path.join(FACE_DIR, f)); gone++; } catch { /* next boot tries again */ }
  }
  if (gone) log('AVATARS pruned', gone, 'files no character asks for');
  return gone;
}

function characterImage(slug) {
  const s = slugOf(slug);
  if (!s) return null;
  try {
    const buf = fs.readFileSync(imgPath(s));
    let type = 'image/jpeg';
    try {
      const m = JSON.parse(fs.readFileSync(metaPath(s), 'utf8'));
      if (m && m.type) type = m.type;
    } catch { /* the bytes are enough */ }
    return { buf, type };
  } catch { return null; }
}

// ---- generated faces: the same one-peer rule for an upstream avatar service ------------------------

function proxyBase() {
  const s = settings.get();
  const raw = String((s.avatar && s.avatar.baseUrl) || process.env.AVATAR_BASE_URL || '').trim();
  return raw ? raw.replace(/\/+$/, '') : '';
}

const MEDIA = { svg: 'image/svg+xml', png: 'image/png', json: 'application/json' };
function mimeFor(name) {
  const ext = String(name).toLowerCase().split('.').pop();
  return MEDIA[ext] || 'application/octet-stream';
}

/** Ask the configured avatar service for one path, from disk if we already have it. */
async function proxy(relPath, query) {
  const b = proxyBase();
  if (!b) return { error: 'no avatar upstream configured', disabled: true };
  const key = relPath + (query ? '?' + query : '');
  const file = path.join(CACHE_DIR, encodeURIComponent(key) + '.bin');
  try {
    if (fresh(file)) return { buf: fs.readFileSync(file), type: mimeFor(relPath), cached: true };
  } catch { /* not cached yet */ }
  const got = await getBytes(b + '/' + String(relPath).replace(/^\/+/, '') + (query ? '?' + query : ''));
  if (got.error) {
    try { return { buf: fs.readFileSync(file), type: mimeFor(relPath), cached: true, stale: true }; } catch { return got; }
  }
  try {
    fs.mkdirSync(CACHE_DIR, { recursive: true });
    fs.writeFileSync(file, got.buf);
  } catch { /* a cache write failure costs a round trip later, nothing else */ }
  return got;
}

function status() {
  const faceCount = (() => { try { return fs.readdirSync(FACE_DIR).filter((f) => f.endsWith('.img')).length; } catch { return 0; } })();
  const proxyCount = (() => { try { return fs.readdirSync(CACHE_DIR).length; } catch { return 0; } })();
  return {
    seedFile: SEED_FILE,
    overrideFile: OVERRIDE_FILE,
    sources: {
      superherodb: heroesApi() || null,
      wikipedia: wikiApi() || null,
      catalogueCached: Boolean(heroesCache) || fs.existsSync(path.join(CACHE_DIR, 'superherodb.json')),
    },
    faceDir: FACE_DIR,
    listed: seed().length,
    cached: faceCount,
    warming: Boolean(warming),
    proxy: { dir: CACHE_DIR, base: proxyBase() || null, entries: proxyCount },
    attributionNote: 'a picture comes from the catalogue named on its entry; each one records its source and licence',
  };
}

module.exports = { characters, characterImage, warm, proxy, proxyBase, status, seed };
