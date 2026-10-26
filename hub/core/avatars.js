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
 *   characters  a shipped list (`characters.json`) of well-known characters, each looked up by its
 *               Wikipedia page title. The list is content, not configuration: `data/characters.json`
 *               overrides it entry by entry, so a household can put their own favourites there, and the
 *               Hub UI writes exactly that file.
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
const MAX_BYTES = Number(process.env.AVATAR_MAX_BYTES || 512 * 1024);
const TTL_MS = Number(process.env.AVATAR_TTL_MS || 30 * 24 * 3600 * 1000); // a character does not recast itself
const WIKI = 'https://en.wikipedia.org/api/rest_v1/page/summary/';

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
      out.set(page, { name: String(c.name || page).trim(), page });
    }
  };
  add(base && base.characters);
  if (over) add(Array.isArray(over) ? over : over.characters);
  return [...out.values()];
}

async function getOnce(url, headers = {}) {
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
  if (buf.length > MAX_BYTES) throw new Error('answer too large (' + buf.length + ' bytes)');
  return { buf, type: res.headers.get('content-type') || 'application/octet-stream' };
}

/** A throttled answer is worth waiting for once; a 404 is not worth anything. */
async function getBytes(url, headers = {}) {
  for (let try_ = 0; try_ < 2; try_++) {
    try {
      return await getOnce(url, headers);
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

  let summary;
  try {
    const res = await fetch(WIKI + encodeURIComponent(entry.page), {
      headers: { 'user-agent': UA, 'accept-language': 'en' },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    if (!res.ok) return meta ? { ...meta, slug } : null;
    summary = await res.json();
  } catch {
    return meta ? { ...meta, slug } : null; // offline: keep showing what we already have
  }

  // A description page can point at a logo, a map or a coat of arms. Nothing here decides what counts as
  // a face beyond the basic facts: it has to be a picture, and it has to be big enough to look like one.
  const url = (summary.thumbnail && summary.thumbnail.source)
    || (summary.originalimage && summary.originalimage.source);
  if (!url || /\.(svg)(\?|$)/i.test(url)) return meta ? { ...meta, slug } : null;

  const got = await getBytes(url);
  if (got.error) return meta ? { ...meta, slug } : null;
  const width = summary.thumbnail && summary.thumbnail.width;
  if (Number.isFinite(width) && width < 120) return meta ? { ...meta, slug } : null;

  const out = {
    name: entry.name,
    page: summary.content_urls && summary.content_urls.desktop
      ? summary.content_urls.desktop.page : 'https://en.wikipedia.org/wiki/' + encodeURIComponent(entry.page),
    title: summary.title || entry.page,
    description: summary.description || '',
    licence: summary.license ? (summary.license.type || '') : '',
    licenceUrl: summary.license ? (summary.license.url || '') : '',
    attribution: 'Wikipedia',
    // Whether this picture is a standing poster or a wide banner decides how a square or 2:3 card should
    // crop it, and guessing from bytes at draw time is worse than recording it once.
    width: (summary.thumbnail && summary.thumbnail.width) || (summary.originalimage && summary.originalimage.width) || 0,
    height: (summary.thumbnail && summary.thumbnail.height) || (summary.originalimage && summary.originalimage.height) || 0,
    type: got.type, // what the bytes actually are, so the picture is served with an honest content-type
    fetchedAt: Date.now(),
  };
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
    description: c.description || '',
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
    faceDir: FACE_DIR,
    listed: seed().length,
    cached: faceCount,
    warming: Boolean(warming),
    proxy: { dir: CACHE_DIR, base: proxyBase() || null, entries: proxyCount },
    attributionNote: 'character pictures come from Wikipedia; each entry carries the page it came from',
  };
}

module.exports = { characters, characterImage, warm, proxy, proxyBase, status, seed };
