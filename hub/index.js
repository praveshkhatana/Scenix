#!/usr/bin/env node
/*
 * hub/index.js — the plugin host: addons in, clients out.
 *
 *   node hub/index.js                     # 0.0.0.0:8788
 *   PORT=8789 LOG_REQUESTS=1 node hub/index.js
 *
 * Two surfaces, one process:
 *   /app/v1/*                  the product API: TV, web and mobile clients all read this
 *   /manifest.json, /catalog/, /meta/, /stream/   legacy addon-compat routes for an addon already
 *                              installed on the TV; nothing under /app/v1 depends on them
 *
 * Content enters the hub ONLY as an addon (design/plugin-architecture.md §0): a native one is a file
 * in providers/ scanned at boot, an installed one is a row in data/addons.json hot-mounted at
 * runtime through POST /app/v1/addons. Adding a feature never means editing this file.
 *
 * Node built-ins only: http, https, url, zlib. No npm packages, no framework, no database.
 */
'use strict';

const http = require('http');
const https = require('https');
const zlib = require('zlib');
const { URL, URLSearchParams } = require('url');

const ROOT = __dirname;
const PORT = Number(process.env.PORT || 8788);
const HUB_ID = process.env.HUB_ID || 'scenix-hub';
const HUB_VERSION = process.env.HUB_VERSION || '0.1.0';

const registry = require('./core/registry').load(ROOT);
const store = require('./core/store').create(ROOT);
const merge = require('./core/merge');
const prefs = require('./core/prefs');
const gateway = require('./core/gateway');
const addons = require('./core/addons');
const trailers = require('./core/trailers');
const ratings = require('./core/ratings');
const probe = require('./core/probe');
const webui = require('./core/webui');
// What a copy actually contains (core/media.js) and how to re-wrap it for a browser (core/remux.js).
// Both are read-only against the engine's own stream URL and both report themselves in /healthz.
const media = require('./core/media');
const remux = require('./core/remux');
// What the owner chose (core/settings.js) and what this hub says about itself on the LAN
// (core/discovery.js). Both are read when a request asks, so a change made in the hub's own UI lands on
// the next request: a setting that needs a restart to take effect is a setting nobody really made.
const settings = require('./core/settings');
const discovery = require('./core/discovery');
const maturityRule = require('./rules/maturity');
const ranking = require('./rules/source_ranking');


// Home must answer from cache: /app/v1 never waits on the network for more than ~1.5 s.
const HOME_BUDGET_MS = Number(process.env.HOME_BUDGET_MS || 1500);
const ROW_BUDGET_MS = Number(process.env.ROW_BUDGET_MS || 1200);
const CATALOG_BUDGET_MS = Number(process.env.CATALOG_BUDGET_MS || 800);
const META_BUDGET_MS = Number(process.env.META_BUDGET_MS || 3000); // a detail page may take a round trip
const HEALTH_BUDGET_MS = Number(process.env.HEALTH_BUDGET_MS || 800);
const SOURCE_BUDGET_MS = Number(process.env.SOURCE_BUDGET_MS || 120); // cache-only, so this is generous
const SUBS_BUDGET_MS = Number(process.env.SUBS_BUDGET_MS || 4000);
// A cold torrent can take 20+ s to produce its first byte: the engine has to find peers and pull the
// head of the file. The player waits on the hub, and the hub waits here, so this has to be longer than
// a first-byte wait or the proxy answers 502 while the copy is still arriving.
const PROXY_TIMEOUT_MS = Number(process.env.PROXY_TIMEOUT_MS || 30000);
// The engine gets its own, longer patience, and it is not a bigger number chosen at random: the TV app's
// player (media3) gives a read 60 s of its own, so the hub must answer *before* the player gives up, and
// the reopening a cold torrent pays was measured here at 19 s (ffprobe header) to 108 s (a full play
// reopen after the engine idled the swarm out). 45 s is inside the player's patience and outside the
// engine's usual reopen; past that we stop waiting and say what happened.
const PROXY_ENGINE_HEADERS_MS = Number(process.env.PROXY_ENGINE_HEADERS_MS || 45000);
const ROW_MAX = Number(process.env.ROW_MAX || 80);
const SEARCH_MAX = Number(process.env.SEARCH_MAX || 60);
// A person typed this, so it is worth a round trip; a home row is never allowed one.
const SEARCH_BUDGET_MS = Number(process.env.SEARCH_BUDGET_MS || 3000);
const ALT_MAX = Number(process.env.ALT_MAX || 9);
// The picker's own budgets. `PLAY_TOTAL_BUDGET_MS` is how long one Play press may spend looking for a
// link that actually answers; `GATEWAY_DELIVER_MS` is how long a single cold torrent is allowed to
// prove it can put bytes on the wire. Both are generous on purpose: a viewer waiting 8s for a film
// that starts and keeps going beats a link that "succeeds" in 0.3s and dies in the player. The total
// stays inside the 12s read timeout the TV app puts on this call, so the last candidate tried is one
// that has actually been measured rather than one the app gave up waiting for.
const PLAY_MAX_TRIES = Number(process.env.PLAY_MAX_TRIES || 3);
const PLAY_TOTAL_BUDGET_MS = Number(process.env.PLAY_TOTAL_BUDGET_MS || 11000);
const GATEWAY_DELIVER_MS = Number(process.env.GATEWAY_DELIVER_MS || 5000);
// How much of the ranking rule's speed bar applies to a client that plays the copy itself.
//
// A browser over MSE and a native player on a TV are not equally nervous about a swarm that delivers at
// the file's own average bitrate. Measured on the BRAVIA: the picker marked a Spider-Verse copy `slow`
// (its sample came in under the ~2.8 Mbps the file needs with the rule's 25% headroom) and the TV then
// played that exact copy at 1920x808 with 4:38 of buffer ahead and recovered from a buffering blip by
// itself. media3 buffers minutes ahead, and the hub reads one sequential stream for it, so a native
// client gets a relaxed bar (0.7x the headroom, i.e. a wider ceiling) while a browser keeps the rule's
// full demand. This scales a *risk threshold*, never the language or seeder rules: a Hindi copy still
// wins on language, and a dead swarm is still disqualified.
const PLAY_NATIVE_HEADROOM_SCALE = Math.min(1, Math.max(0.2, Number(process.env.PLAY_NATIVE_HEADROOM_SCALE || 0.7)));

// What this line can carry through the torrent engine, learned from the bytes it actually delivered.
// Measured here: plain HTTP download reaches 70+ Mbps while BitTorrent on the same box and the same
// trackers delivers around 1 Mbps, so a 4K copy needing 8 Mbps is not a quality choice, it is a broken
// promise. The picker feeds this number back into its own ordering, which is what "Auto" quality on a
// paid app really means — the sharpest copy the connection can feed, worked out by measurement.
let gatewayMbps = null;
const GATEWAY_FIT = 0.85; // keep 15% of the measured pipe for the player's own buffer
// A play decision is the one place a page view is allowed to touch the internet: the hub measures the
// links it is about to hand over, but never waits more than this for the measurement (core/probe.js
// keeps probing in the background so the NEXT view of the same title is answered from cache).
const PLAY_PROBE_BUDGET_MS = Number(process.env.PLAY_PROBE_BUDGET_MS || probe.limits.BUDGET_MS);
const PLAY_PROBE_MAX = Number(process.env.PLAY_PROBE_MAX || 6); // measured candidates per decision
const DAYS = Number(process.env.DAYS || 45); // "just added" window
const THEME_ID = process.env.THEME_ID || 'scenix-dark';
const HEALTH_CACHE_MS = 15000;
const UPSTREAM_UA = process.env.ADDON_UA || 'Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 ScenixHub/0.1';

const log = (...a) => console.log(new Date().toISOString(), ...a);

// ---- plugin lifecycle -----------------------------------------------------------------------------

for (const p of registry.providers) {
  if (typeof p.start === 'function') {
    try {
      p.start();
    } catch (e) {
      log('PROVIDER START FAIL', p.id, e.message);
    }
  }
}
log('PLUGINS', registry.providers.length, 'providers,', registry.rows.length, 'rows,',
  registry.rules.length, 'rules loaded',
  registry.failures.length ? '(' + registry.failures.length + ' failed)' : '');
for (const f of registry.failures) log('  FAILED', f.kind, f.file, '-', f.error);

/**
 * Boot the addon host: every native addon is mirrored into data/addons.json (so the same
 * enable/disable surface works for both kinds) and every installed external addon is hot-mounted,
 * which puts it in registry.providers before the first request is served.
 */
const addonBoot = addons.init(registry, store);
log('ADDONS', addonBoot.external, 'external mounted from', addonBoot.file,
  '+', registry.providers.filter((p) => p.native).length, 'native');

/** Stale the operator caches whenever the plugin set changes. */
function invalidateCaches() {
  healthCache = null;
  healthCacheAt = 0;
}

/**
 * What this hub can do, as a fact about this install rather than about which build it is. The client
 * branches on these and never on the hub's version or identity, because one contract is answered by a
 * separate hub process and, in standalone mode, by the app on the TV with no server anywhere: the UI may
 * branch on whether a capability exists, never on who is answering.
 *
 * Each entry is a fact about this install, not a transient measurement. A gateway that is configured but
 * briefly unreachable still has the capability; a hub with no ffmpeg never acquires one.
 */
function capabilities() {
  const g = gateway.status();
  const live = registry.byId['live-tv'];
  return {
    gateway: g.enabled === true, // torrents are served through an engine here, not handed to the client
    live: !!live && !!String((settings.get().live || {}).playlist || '').trim(),
    localFiles: !!registry.byId['local-files'],
    remux: remux.status().binFound === true, // a browser can be handed fragmented MP4 / HLS
    tracks: media.status().binFound === true, // audio and subtitle languages are read from the files
    announce: process.env.ANNOUNCE !== '0', // a client can find this hub without being told its address
  };
}

/**
 * Legacy addon-compat keeps the old behaviour exactly: the request waits for the playlist (the
 * single-file addon did `await refresh()` before routing). App routes never wait - they read cache.
 */
const COMPAT_BUDGET_MS = Number(process.env.COMPAT_BUDGET_MS || 20000);

async function ensureCompat(provider) {
  if (typeof provider.legacy.ensure !== 'function') return;
  await withBudget(Promise.resolve().then(() => provider.legacy.ensure()), COMPAT_BUDGET_MS,
    null, 'compat warmup ' + provider.id);
}

/** Resolve `promise`, but never later than `ms`; a slow plugin degrades to `fallback`, no crash. */
function withBudget(promise, ms, fallback, label) {
  let timer;
  const guard = new Promise((resolve) => {
    timer = setTimeout(() => {
      log('BUDGET', label || 'task', 'exceeded', ms + 'ms - serving cached/partial');
      resolve(fallback);
    }, ms);
    if (timer.unref) timer.unref();
  });
  const safe = Promise.resolve(promise)
    .then((v) => (v === undefined ? fallback : v))
    .catch((e) => {
      log('PLUGIN ERROR', label || '', e.message);
      return fallback;
    });
  return Promise.race([safe, guard]).finally(() => clearTimeout(timer));
}

// ---- the row context (what a row's build(ctx) gets) -----------------------------------------------

function makeCtx(profile, metaBudget) {
  const memo = { library: null };
  const ctx = {
    profile,
    store,
    providers: registry.byId,
    rules: registry.rules,
    prefs: prefs.resolve(profile),
    days: DAYS,
    now: Date.now(),

    /** Cache-only addon catalog. Never blocks on the network, unless the caller asks and pays. */
    async catalog(addonId, catalogId, extra = {}, budget = CATALOG_BUDGET_MS) {
      const p = registry.byId[addonId];
      if (!p || typeof p.catalog !== 'function') return [];
      return withBudget(
        Promise.resolve().then(() => p.catalog(catalogId, extra || {})),
        budget, [], 'catalog ' + addonId + '/' + catalogId,
      );
    },

    /** Every item every addon currently publishes, deduped, addedAt stamped. */
    async library() {
      // Memoised as a PROMISE: several rows of the same request ask for the library in parallel.
      if (!memo.library) {
        memo.library = (async () => {
          const all = [];
          for (const p of registry.providers) {
            if (typeof p.catalog !== 'function') continue;
            let cats = [];
            try {
              cats = (typeof p.catalogs === 'function' ? await p.catalogs() : []) || [];
            } catch (e) {
              log('CATALOGS FAIL', p.id, e.message);
            }
            for (const c of cats) all.push(...(await ctx.catalog(p.id, c.id)));
          }
          const items = merge.dedupe(all, registry.priorities);
          store.touchItems(items);
          return items;
        })();
      }
      return memo.library;
    },

    /** One identity across every addon, merged into one tile with all its sources. */
    async resolve(identity, hint) {
      const parts = [];
      for (const p of registry.providers) {
        if (typeof p.meta !== 'function') continue;
        const it = await withBudget(
          Promise.resolve().then(() => p.meta(identity, hint)), metaBudget || 300, null, 'meta ' + p.id,
        );
        if (it) parts.push(it);
      }
      const item = merge.mergeItems(parts, registry.priorities);
      if (item) {
        store.touch(item.identity);
        // A series is addressed episode by episode. The detail page has to list the sources of the same
        // episode Play will fetch, or the "Source 1..N" menu names torrents from a different episode than
        // the one that starts. The tile's identity stays the show: rows, dedupe and Continue all depend
        // on that.
        if (item.type === 'series') {
          item.progress = store.progressFor(ctx.profile.id, item.identity);
          const ep = episodeOf(item, null, null);
          if (ep) item.streamId = item.identity + ':' + ep.season + ':' + ep.episode;
        }
        await contributeSources(item);
        // A detail page may spend one round trip so the picker is complete before anyone taps Play.
        // An addon that already answered from cache is not asked twice.
        for (const p of registry.providers) {
          if (typeof p.fetchStreams !== 'function') continue;
          if ((item.sources || []).some((s) => s._addon === p.id)) continue;
          const fresh = await withBudget(
            Promise.resolve().then(() => p.fetchStreams(item.streamId || identity)),
            META_BUDGET_MS, [], 'streams ' + p.id,
          );
          if (fresh && fresh.length) item.sources.push(...fresh);
        }
        item.sources = merge.rankSources(item.sources, registry.priorities);
      }
      return item;
    },
  };
  return ctx;
}

/**
 * A streams-only addon (catalogs of its own? no) still feeds sources to a tile another addon
 * published. This is how metadata from one addon and streams from another become ONE tile with a
 * ranked picker, and it is the only place that asks. Cache-only: sources() reads the addon's warmed
 * stream cache; the background warm is kicked separately so the next request has it.
 */
async function contributeSources(item) {
  if (!item || !Array.isArray(item.sources)) return;
  for (const p of registry.providers) {
    if (typeof p.sources !== 'function') continue;
    const extra = await withBudget(
      Promise.resolve().then(() => p.sources(item)), SOURCE_BUDGET_MS, [], 'sources ' + p.id,
    );
    if (extra && extra.length) item.sources.push(...extra);
  }
  item.sources = merge.rankSources(item.sources, registry.priorities);
}

/** Warm streams for what is actually going on screen (fire and forget, capped by the addon). */
function warmStreams(identities) {
  if (!identities.length) return;
  for (const p of registry.providers) {
    if (typeof p.warmStreams !== 'function') continue;
    try {
      p.warmStreams(identities);
    } catch (e) { /* a cold lookup is fine; the detail page retries */ }
  }
}

/**
 * The one item pipeline every /app/v1 response goes through:
 * dedupe -> record addedAt -> contribute sources from every addon -> rules in order (a rule returning
 * null drops the item) -> progress.
 */
async function pipeline(items, ctx) {
  const deduped = merge.dedupe(items, registry.priorities);
  store.touchItems(deduped);
  for (const it of deduped) await contributeSources(it);
  warmStreams(deduped.slice(0, addons.limits.WARM_STREAMS).map((it) => it.identity));
  const kept = [];
  for (const start of deduped) {
    let it = start;
    for (const rule of registry.rules) {
      try {
        it = await rule.apply(it, ctx);
      } catch (e) {
        log('RULE ERROR', rule.id, e.message); // a broken rule must not lose the item
      }
      if (it === null || it === undefined) break;
    }
    if (!it) continue;
    it.progress = ctx.store.progressFor(ctx.profile.id, it.identity);
    kept.push(it);
  }
  return kept;
}

function rowVisible(row, profile) {
  const scope = row.scope || 'all';
  // A billboard is a SHAPE, not a content tier, so every profile gets one — including a child, whose
  // billboard is decided by the rules that run over its items (maturity, then the kids allowlist) rather
  // than by a second hero row for kids. rows/hero.js is scope 'all' and carries no adult-only content.
  if (row.card === 'hero') return true;
  if (profile.kind === 'kids') return scope === 'kids'; // kids: kids rows only
  return scope === 'all' || scope === 'adult';
}

async function buildRow(row, ctx, budgetMs) {
  const raw = await withBudget(
    Promise.resolve().then(() => row.build(ctx)), budgetMs || ROW_BUDGET_MS, [], 'row ' + row.id,
  );
  const items = await pipeline(raw || [], ctx);
  // Trim after the rules: a row may hand back more candidates than it wants on screen.
  return items.slice(0, row.max || ROW_MAX);
}

// How many billboard tiles to answer with. The app paints the first one full-bleed; the rest are the
// alternates it can cycle, and rows/hero.js proposes several times this many on purpose.
const HERO_MAX = Number(process.env.HERO_MAX || 5);

/**
 * The home screen's shape, decided in one place rather than inside a row: the billboard goes first, and
 * it never repeats a title already sitting on a shelf under it. Someone scrolling down should find the
 * rest of the screen, not the poster they were just shown at arm's length.
 *
 * `title: null` on a hero row says out loud what the app already draws — a full-bleed billboard with no
 * shelf header — while core/registry.js still requires every row module to name itself, and /app/v1/
 * manifest reports that name to a settings screen that lists the shelves this hub can build.
 *
 * The dedupe runs one way on purpose: the shelves below keep their tiles, because those rows are what a
 * person browses. If that leaves the billboard with nothing at all, its best candidate stays — one title
 * shown twice is a smaller loss than a home screen with no billboard.
 */
function arrangeHome(rows) {
  const heroes = rows.filter((r) => r.card === 'hero');
  if (!heroes.length) return rows;
  const rest = rows.filter((r) => r.card !== 'hero');
  const below = new Set();
  for (const r of rest) for (const it of r.items) below.add(it.identity);
  for (const h of heroes) {
    const unique = h.items.filter((it) => !below.has(it.identity));
    h.items = (unique.length ? unique : h.items.slice(0, 1)).slice(0, HERO_MAX);
    h.title = null;
  }
  return [...heroes, ...rest];
}

// ---- legacy addon-compat surface -------------------------------------------------------------------

const compatProviders = () => registry.providers.filter((p) => p.legacy);

/**
 * Title match for search. Every query word has to appear as the start of some word in the title, so
 * "break" finds "Breaking Bad" and "and tv" finds "&TV" (punctuation is not a word boundary here).
 */
function titleMatches(title, q) {
  const words = String(q || '').toLowerCase().replace(/[^a-z0-9\u0900-\u097F\u0A00-\u0A7F]+/g, ' ')
    .split(' ').filter(Boolean);
  if (!words.length) return false;
  const hay = String(title || '').toLowerCase().replace(/[^a-z0-9\u0900-\u097F\u0A00-\u0A7F]+/g, ' ');
  const tokens = hay.split(' ').filter(Boolean);
  return words.every((w) => tokens.some((t) => t.startsWith(w)));
}

/**
 * Search what the hub already publishes: every item from every addon's catalogs, already deduped and
 * already carrying its sources. Instant and offline, and it covers titles no addon search catalog has
 * surfaced. Addon search catalogs are queried on top of this by the route that calls it.
 */
function searchLibrary(items, q) {
  const first = String(q || '').trim().toLowerCase().split(/\s+/)[0] || '';
  return items
    .filter((it) => titleMatches(it.title, q))
    .sort((a, b) => relevance(b, first) - relevance(a, first));
}

/** A title that starts with the word beats a later match; a popular title beats an obscure one. */
function relevance(it, first) {
  const title = String(it.title || '').toLowerCase();
  return (title.startsWith(first) ? 4 : 0)
    + (it.inCinemas ? 1 : 0)
    + Math.min(3, (Number(it.match) || 0) / 40);
}

function uniq(list) {
  return [...new Set(list)];
}

/**
 * Merge every compat addon's manifest fragment. Scalars come from the highest-priority addon that
 * names them; catalogs / resources / idPrefixes are unioned in addon order — which reproduces the
 * single-file addon's manifest byte for byte, so a TV with that addon installed keeps working.
 */
function addonManifest() {
  const out = {};
  for (const p of compatProviders()) {
    let part;
    try {
      part = (p.legacy.manifestPart && p.legacy.manifestPart()) || {};
    } catch (e) {
      log('MANIFEST PART FAIL', p.id, e.message);
      continue;
    }
    // Keys are taken in the order the highest-priority addon names them, so the merged object
    // serialises with exactly the key order the single-file addon produced.
    for (const [key, value] of Object.entries(part)) {
      if (key === 'resources') {
        out.resources = out.resources || [];
        for (const r of value || []) {
          const found = out.resources.find(
            (x) => x.name === r.name && JSON.stringify(x.types) === JSON.stringify(r.types));
          if (found) found.idPrefixes = uniq([...(found.idPrefixes || []), ...(r.idPrefixes || [])]);
          else out.resources.push({ name: r.name, types: r.types, idPrefixes: uniq(r.idPrefixes || []) });
        }
      } else if (key === 'catalogs' || key === 'idPrefixes') {
        out[key] = uniq([...(out[key] || []), ...(value || [])]);
      } else if (!(key in out)) {
        out[key] = value;
      }
    }
  }
  return out;
}

/** Route a compat id to the addon that claims its prefix (so ids stay stable per addon). */
function claimsPrefix(p, id) {
  const prefixes = (p.legacy && p.legacy.idPrefixes) || [];
  return prefixes.some((prefix) => String(id).startsWith(prefix));
}

// ---- HTTP ------------------------------------------------------------------------------------------

/** Internal plugin fields (`_addon`, `_addons`) are hub plumbing and never cross the wire. */
function scrub(value) {
  if (Array.isArray(value)) return value.map(scrub);
  if (value && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) if (!k.startsWith('_')) out[k] = scrub(v);
    return out;
  }
  return value;
}

function corsHeaders() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET,POST,DELETE,OPTIONS',
    'Access-Control-Allow-Headers': 'content-type,range,accept-encoding',
    'Access-Control-Expose-Headers': 'content-range,accept-ranges,content-length',
  };
}

function send(res, code, body, type = 'application/json', extra = null) {
  const req = res.req;
  if (process.env.LOG_REQUESTS === '1' && req) {
    console.log(new Date().toISOString(), 'REQ', req.method, req.url, '->', code);
  }
  const headers = { 'Content-Type': type, 'Cache-Control': 'no-cache', ...corsHeaders(), ...(extra || {}) };
  let data = type === 'application/json' ? JSON.stringify(scrub(body)) : body;
  // A warm home is ~120 KB of JSON; every client (TV, browser, phone) accepts gzip.
  if (type === 'application/json' && req && /gzip/i.test(String(req.headers['accept-encoding'] || ''))
      && data.length > 860) {
    data = zlib.gzipSync(data);
    headers['Content-Encoding'] = 'gzip';
    headers.Vary = 'Accept-Encoding';
  }
  res.writeHead(code, headers);
  res.end(data);
}

function readBody(req) {
  return new Promise((resolve) => {
    let body = '';
    req.on('data', (c) => {
      body += c;
      if (body.length > 1e6) {
        resolve('');
        req.destroy();
      }
    });
    req.on('end', () => {
      try {
        resolve(body ? JSON.parse(body) : {});
      } catch (e) {
        resolve(null);
      }
    });
    req.on('error', () => resolve(null));
  });
}

let healthCache = null;
let healthCacheAt = 0;

async function healthBody() {
  if (healthCache && Date.now() - healthCacheAt < HEALTH_CACHE_MS) return healthCache;
  const addonStats = {};
  for (const p of registry.providers) {
    let stats = {};
    try {
      stats = (typeof p.stats === 'function' ? p.stats() : {}) || {};
    } catch (e) {
      stats = { error: e.message };
    }
    addonStats[p.id] = { kind: p.kind, priority: p.priority, native: p.native === true, ...stats };
  }
  // Per-row counts: every row built once with a profile that can see it, through the same
  // pipeline home uses (cache-only addons + a budget, so /healthz cannot hang).
  const rows = {};
  const kidsProfile = store.profiles().find((p) => p.kind === 'kids') || { id: '-', kind: 'kids' };
  const bigProfile = store.profiles().find((p) => p.kind !== 'kids') || { id: '-', kind: 'adult' };
  const counts = await Promise.all(registry.rows.map(async (row) => {
    const ctx = makeCtx(row.scope === 'kids' ? kidsProfile : bigProfile, HEALTH_BUDGET_MS);
    const items = await buildRow(row, ctx, HEALTH_BUDGET_MS);
    return [row.id, { items: items.length, order: row.order, scope: row.scope || 'all', card: row.card }];
  }));
  for (const [id, value] of counts) rows[id] = value;
  const body = {
    status: 'ok',
    hub: HUB_ID,
    version: HUB_VERSION,
    port: PORT,
    addons: addonStats,
    rows,
    rules: registry.rules.map((r) => ({ id: r.id, order: r.order })),
    store: store.stats(),
    gateway: Object.assign({}, gateway.status(), {
      // The rate the engine has last been measured delivering, so the number the picker orders by is
      // visible from outside rather than inferred from which copy it happened to choose.
      linkMbps: gatewayMbps ? Number(gatewayMbps.toFixed(2)) : null,
    }),
    probe: probe.status(), // how many links this hub has actually measured, and what they answered
    // What the copies contain, and whether the tooling that says so is installed: an operator with a
    // missing ffprobe sees "binFound: false" here rather than wondering why every source says
    // "audio: not stated" forever.
    tracks: media.status(),
    // Whether a browser can be handed MP4 at all: the binary, how many ffmpeg are running, how many
    // requests were refused because the box is already remuxing.
    remux: remux.status(),
    // The owner's choices as this request sees them, and what the LAN responder is currently saying.
    // A hub that "works" but has lost its announce socket shows that here rather than in a support call
    // about a TV that cannot find it.
    settings: settings.status(),
    discovery: discovery.status(),
    trailers: trailers.status(),
    ratings: ratings.status(),
    // The front-end the hub itself serves: whether it is on disk, how many files, how many bytes. A
    // half-copied deploy shows up here instead of as a blank page on someone's TV.
    web: webui.status(),
    compat: { routes: ['/manifest.json', '/catalog/', '/meta/', '/stream/'], addons: compatProviders().map((p) => p.id) },
    plugins: {
      loaded: {
        providers: registry.providers.map((p) => p.id),
        rows: registry.rows.map((r) => r.id),
        rules: registry.rules.map((r) => r.id),
      },
      failed: registry.failures,
    },
  };
  healthCache = body;
  healthCacheAt = Date.now();
  return body;
}

/**
 * Hub-side proxy: a browser cannot set request headers, so a source that needs them (a Referer, a
 * particular User-Agent) is unplayable there. /app/v1/proxy replays the request with the headers the
 * addon asked for and passes byte ranges straight through, so seeking works from any client.
 *
 * Two different failures live behind this one route and they used to be treated the same way.
 *
 * A CDN refusing a link is a broken source: answer fast, the player should switch. The engine taking a
 * long time to answer is a *cold torrent* — it has to rejoin the swarm, and that was measured here at
 * 19-108 s. Cutting that off at 30 s and calling it "proxy failed" is what made playback die in the
 * middle of a scene: the player's read timeout (media3: 60 s) had not even expired yet, so the hub was
 * the one giving up first. So the header wait is now engine-specific (PROXY_ENGINE_HEADERS_MS, 45 s by
 * default, under the player's own 60 s so the player hears from us rather than timing out in silence),
 * and when the engine does not answer even then we say what it is — 503 with `warming:true` and a
 * `Retry-After` — instead of a generic 502. Honest and quick beats patient and vague.
 *
 * Once the headers are in, the body is never cut on a timer at all. Torrents go quiet for a minute now
 * and then; that is the swarm, not a broken stream.
 *
 * Every read also registers itself with the gateway as an active reader for that hash, which is what
 * lets the keep-warm timer tell "someone is watching this torrent" from "nobody is, and the engine is
 * about to close it".
 */
async function proxyStream(req, res, u) {
  const target = u.searchParams.get('u');
  if (!target || !/^https?:\/\//i.test(target)) return send(res, 400, { error: 'u must be an http(s) url' });
  const headers = { 'User-Agent': UPSTREAM_UA };
  for (const pair of String(u.searchParams.get('h') || '').split('||')) {
    const at = pair.indexOf(':');
    if (at > 0) headers[pair.slice(0, at).trim()] = pair.slice(at + 1).trim();
  }
  if (req.headers.range) headers.Range = req.headers.range;
  const who = gateway.parsePlayUrl(target);
  const hash = who.hash;
  const fileId = who.fileId;
  const headersMs = hash ? PROXY_ENGINE_HEADERS_MS : PROXY_TIMEOUT_MS;
  const mod = target.startsWith('https:') ? https : http;
  const t0 = Date.now();
  const tag = 'hash=' + (hash ? hash.slice(0, 12) : '-') + ' file=' + (fileId == null ? '-' : fileId);
  const upstream = await new Promise((resolve) => {
    const r = mod.get(target, { headers, timeout: headersMs }, (got) => {
      // The timeout is only about the ANSWER. See the comment on this function: the body runs as long
      // as the swarm feeds it.
      r.setTimeout(0);
      resolve(got);
    });
    r.on('error', (e) => resolve({ error: e }));
    r.on('timeout', () => {
      r.destroy();
      resolve({ error: new Error('upstream timeout after ' + headersMs + 'ms') });
    });
  });
  if (upstream.error) {
    // The one line that says whether the failure was the player's or the upstream's, and which phase of
    // the request it happened in.
    const waited = Date.now() - t0;
    log('PROXY FAIL', 'phase=headers', 'cause=' + (hash ? 'engine-no-answer' : 'upstream-refused'),
      'elapsed=' + waited + 'ms', tag, String(target).slice(0, 96), upstream.error.message);
    if (hash) {
      gateway.noteCold(hash, waited);
      return send(res, 503, {
        error: 'warming',
        warming: true,
        detail: 'the torrent engine did not start this copy within ' + waited + 'ms',
        retryAfterMs: 5000,
        hash,
        fileId,
      }, 'application/json', { 'Retry-After': '5' });
    }
    return send(res, 502, { error: 'proxy failed', detail: upstream.error.message });
  }
  const ttfb = Date.now() - t0;
  if (hash) {
    // Every engine first-byte measurement this box makes, one grep away: `grep 'PROXY TTFB' hub.log`.
    log('PROXY TTFB', tag, 'ms=' + ttfb, 'status=' + upstream.statusCode,
      'range=' + (req.headers.range || 'none'));
    if (ttfb > 10000) gateway.noteCold(hash, ttfb); // reopening cost something: worth knowing at next play
  }
  const done = gateway.readBegin(hash, fileId);
  let bytes = 0;
  let ended = false;
  const pass = { ...corsHeaders() };
  for (const k of ['content-type', 'content-length', 'content-range', 'accept-ranges', 'cache-control']) {
    if (upstream.headers[k]) pass[k] = upstream.headers[k];
  }
  if (process.env.LOG_REQUESTS === '1') log('REQ', req.method, req.url, '->', upstream.statusCode, '(proxy)');
  res.writeHead(upstream.statusCode || 200, pass);
  upstream.on('data', (c) => { bytes += c.length; });
  upstream.pipe(res);
  upstream.on('error', (e) => {
    // A stream that ends early is a stalled swarm; a stream that was refused is a broken link. The
    // player sees a truncation either way, so the log has to be able to tell them apart.
    ended = true;
    done();
    log('PROXY CUT', 'phase=body', 'cause=' + (e.message || 'upstream error'),
      'elapsed=' + (Date.now() - t0) + 'ms', 'bytes=' + bytes, tag);
    res.destroy();
  });
  upstream.on('end', () => {
    done();
    const expected = Number(upstream.headers['content-length']);
    // A 206 whose body stops short of the requested range is a swarm that ran out of willing peers.
    if (Number.isFinite(expected) && bytes < expected && !ended) {
      log('PROXY CUT', 'phase=body', 'cause=short-read', 'elapsed=' + (Date.now() - t0) + 'ms',
        'bytes=' + bytes + '/' + expected, tag);
    }
  });
  // The viewer walking away (Back, a seek, the next episode) has to close the engine read as well, or
  // every seek in the evening leaves another reader open on the same torrent and the engine's single
  // sequential cache starts jumping backwards.
  res.on('close', () => {
    done();
    upstream.destroy();
    if (!ended) {
      log('PROXY CUT', 'phase=body', 'cause=client-disconnect', 'elapsed=' + (Date.now() - t0) + 'ms',
        'bytes=' + bytes, tag);
      ended = true;
    }
  });
}

/**
 * Merged meta for one identity, sources left alone: what a guard needs to know about a title without
 * paying for the stream warm-up ctx.resolve() does for a detail page.
 */
async function metaOnly(identity, hint, budgetMs = 300) {
  const parts = [];
  for (const p of registry.providers) {
    if (typeof p.meta !== 'function') continue;
    const it = await withBudget(Promise.resolve().then(() => p.meta(identity, hint)), budgetMs, null, 'meta ' + p.id);
    if (it) parts.push(it);
  }
  return merge.mergeItems(parts, registry.priorities);
}

/**
 * The routes that answer about a single title WITHOUT going through pipeline() (subs, trailer) ask the
 * maturity rule the same question the shelves ask, so a hidden title cannot leak its subtitles or its
 * trailer. The policy itself stays in rules/maturity.js; this only asks it.
 */
async function visibleTo(identity, profile, type) {
  const item = await metaOnly(identity, type);
  if (!item) return { ok: true, item: null }; // nothing to judge: the route's own 404 decides
  const kept = maturityRule.apply(item, { profile, prefs: prefs.resolve(profile) });
  return kept ? { ok: true, item: kept } : { ok: false, tier: item.tier, minAge: item.minAge };
}

/**
 * The URL this source would hand the player, plus the headers that request needs — asked WITHOUT a
 * round trip or a child process. An addon answers cheaply through `sourceTarget()`; a source that
 * carries its own URL (live-tv's variants) needs no asking. A magnet, a signed URL that costs a
 * yt-dlp run to mint, or a service landing page has nothing to measure here, which the app sees as
 * `checked: false` rather than as a guessed green light.
 */
function sourceTarget(source, identity) {
  if (!source || source.link) return null; // a page to open is not a stream to measure
  const addon = registry.byId[source._addon] || registry.byId[source.provider];
  if (addon && typeof addon.sourceTarget === 'function') {
    try {
      const t = addon.sourceTarget(identity, source);
      const url = typeof t === 'string' ? t : t && t.url;
      if (url && probe.probeable(url)) return { url, headers: (t && t.headers) || {} };
      return null;
    } catch (e) {
      log('SOURCE TARGET FAIL', identity, e.message); // a resolver that throws is still just "unknown"
      return null;
    }
  }
  if (source._url && probe.probeable(source._url)) return { url: source._url, headers: {} };
  return null;
}

/** Sources the app already tried and rejected: accepts the id, the label, or "provider:label". */
function exclusions(raw) {
  const list = Array.isArray(raw) ? raw : (raw ? [raw] : []);
  return new Set(list.map((x) => String(x)));
}
function isExcluded(source, skip) {
  if (!skip.size) return false;
  return skip.has(source.id) || (source.label && skip.has(source.label))
    || (source.provider && skip.has(source.provider + ':' + source.label))
    || (source._url && skip.has(source._url));
}

/**
 * Measure the candidates this title could actually play, inside one wall-clock budget, and mark every
 * source `checked` — true means a number came back, false means the ranking is still metadata.
 * A probe that cannot answer contributes nothing: the source keeps exactly the score it had before
 * probing existed, and the response shape is unchanged apart from the documented `checked` marker.
 */
async function probePass(sources, identity, { force = false, budgetMs = PLAY_PROBE_BUDGET_MS } = {}) {
  if (!probe.ENABLED || !sources.length) return 0;
  const targets = [];
  for (const s of sources) {
    s.checked = false; // measured only if a result says so
    s.probe = null; // checked:false must never travel beside a stale measurement
    if (targets.length >= PLAY_PROBE_MAX) continue;
    const t = sourceTarget(s, identity);
    if (t) targets.push({ source: s, url: t.url, headers: t.headers });
  }
  if (!targets.length) return 0;
  let results;
  try {
    results = await probe.probeAll(targets, { budgetMs, force });
  } catch (e) {
    log('PROBE PASS FAIL', identity, e.message); // a measurement must never break playback
    return 0;
  }
  let measured = 0;
  targets.forEach((t, i) => {
    const r = results[i];
    if (!r) return;
    t.source.probe = r;
    t.source.checked = true;
    measured++;
  });
  return measured;
}

/** Attach what we already measured, waiting for nothing: the detail page warms the next decision. */
function peekPass(sources, identity) {
  if (!probe.ENABLED) return 0;
  let attached = 0;
  for (const s of sources) {
    const t = sourceTarget(s, identity);
    if (!t) {
      s.checked = false; // nothing to measure here, and the app should know that rather than guess
      s.probe = null;
      continue;
    }
    const p = probe.peek(t.url, t.headers);
    if (p) {
      s.probe = p;
      s.checked = true;
      attached++;
    } else {
      s.checked = false;
      s.probe = null;
    }
  }
  return attached;
}

/** `<imdbid>:2:5` -> { season: 2, episode: 5 }, so a cache lookup uses the same key the writer used. */
function wantFromStreamId(streamId) {
  const m = /:(\d+):(\d+)$/.exec(String(streamId || ''));
  return m ? { season: Number(m[1]), episode: Number(m[2]) } : null;
}

/**
 * Attach what the file itself carries — audio languages, subtitles — from caches only, waiting for
 * nothing. Two caches have to agree for this to be possible: the engine has to have told us which file
 * inside the torrent is the one (core/gateway.js), and ffprobe has to have read that file
 * (core/media.js). Both are warmed while the title page is being looked at, so by the time this runs the
 * answer is a memory read. A source without either stays exactly as it was: the ranking rule then says
 * "audio: not stated" rather than inventing a language from a filename.
 */
/**
 * The magnet behind a ranked row, from the addon's own index.
 *
 * A ranked source carries what a row displays — label, quality, size, seeders — and deliberately not the
 * magnet: it is a 300-byte string, and the list is sent to every client. So any server-side decision that
 * needs to know *which torrent* a row is (registering it with the engine, reading its tracks, ranking on
 * the languages its file states) has to ask the addon that produced the row, exactly as play() does.
 */
function magnetOf(streamId, s) {
  if (!s || s.kind !== 'torrent') return null;
  const a = registry.byId[s && s._addon];
  return a && typeof a.magnetFor === 'function' ? a.magnetFor(streamId, s) : null;
}

function attachTracks(sources, want, streamId) {
  let attached = 0;
  for (const s of sources || []) {
    if (!s || s.kind !== 'torrent') continue;
    // The hash comes from the addon's index, not from the row: a ranked row carries no magnet of its own.
    const addr = { ...s, url: magnetOf(streamId, s) };
    const hash = gateway.hashOf(s) || gateway.hashOf(addr);
    if (!hash) continue;
    // The engine's file id when something already asked the engine for this torrent, and if not, whatever
    // file of it the title page measured. The language a copy states is the fact that decides its place in
    // the list, and the play path knows the hash long before it knows the file id — refusing to answer on
    // the hash alone would throw away a measurement this hub already paid for.
    const info = gateway.peekPrepared(addr, want);
    const entry = (info && info.fileId !== undefined ? media.peek(hash, info.fileId) : null)
      || media.peekByHash(hash);
    if (!entry) continue;
    // Copies: the cache entry is shared by every response, and each caller may rewrite a subtitle URL for
    // its own origin.
    s.audioTracks = (entry.audioTracks || []).map((t) => ({ ...t }));
    s.subtitleTracks = (entry.subtitleTracks || []).map((t) => ({ ...t }));
    // The same facts in the shape a list row can render and a picker can grey out without walking the
    // track arrays: which languages this file states its audio and subtitles are in, and that they came
    // from the file rather than from the uploader's filename.
    s.audioLangs = ranking.statedLanguages(s);
    s.subLangs = s.subtitleTracks
      .map((t) => String(t.language || '').toLowerCase())
      .filter((l) => l && l !== 'und')
      .filter((l, i, a) => a.indexOf(l) === i);
    s.langsStated = true;
    attached++;
  }
  return attached;
}

/** Re-run the ranking rule now that there is a measurement to rank on. */
async function rerank(item, ctx) {
  const rule = registry.byId['rule:source-ranking'];
  if (!rule || typeof rule.apply !== 'function') return;
  try {
    await rule.apply(item, ctx);
  } catch (e) {
    log('RERANK FAIL', item.identity, e.message);
  }
}

/**
 * Ask the addon that offered a source for its playable URL, then wrap it in the client contract.
 * `opts.exclude` names sources the app already tried (buffering, dead) and `opts.reprobe` ignores the
 * measurement cache — together they are the "give me another link" path the player needs mid-film.
 */
/**
 * Which episode a Play press means.
 *
 * A series tile is ONE merge key with many addresses. The community protocol wants
 * `<imdbid>:<season>:<episode>`, and asking for the bare show id returns whichever single episode the
 * addon feels like — pressing Play on Pluribus landed the viewer on S01E04. So Play always names an
 * episode: the one the client asks for, else the one it stopped watching, else the first one that
 * exists. A show with no episode list still gets S01E01, never "the show".
 */
function episodeOf(item, season, episode) {
  if (item.type !== 'series') return null;
  const eps = Array.isArray(item.episodes) ? item.episodes : [];
  const want = (s, e) => eps.find((x) => x.season === Number(s) && x.episode === Number(e)) || null;
  const s = Number(season); const e = Number(episode);
  if (s > 0 && e > 0) return want(s, e) || { season: s, episode: e, title: null };
  const last = item.progress && item.progress.episode;
  if (last && want(last.season, last.episode)) return want(last.season, last.episode);
  if (eps.length) return eps[0];
  return { season: 1, episode: 1, title: null };
}

/** Streams for an address nobody has asked for yet — the detail page's waiting path, reused by Play. */
async function fetchEpisodeSources(item) {
  for (const p of registry.providers) {
    if (typeof p.fetchStreams !== 'function') continue;
    const extra = await withBudget(Promise.resolve().then(() => p.fetchStreams(item.streamId)),
      META_BUDGET_MS, [], 'episode streams ' + p.id);
    if (extra && extra.length) item.sources.push(...extra);
  }
  item.sources = merge.rankSources(item.sources || [], registry.priorities);
}

async function pickAndPlay(identity, profile, wanted, type, opts = {}) {
  const ctx = makeCtx(profile, META_BUDGET_MS);
  const item = await ctx.resolve(identity, type);
  if (!item) return { status: 404, body: { error: 'not found', identity } };
  // Address the episode before anything ranks or filters sources, so the rules and the picker all see
  // the streams for the episode the viewer is about to watch.
  let ep = episodeOf(item, opts.season, opts.episode);
  if (ep && item.streamId !== item.identity + ':' + ep.season + ':' + ep.episode) {
    item.streamId = item.identity + ':' + ep.season + ':' + ep.episode;
    item.sources = [];
    await fetchEpisodeSources(item);
    if (!(item.sources || []).length) {
      // Episode addressing is the right question, but some addons only list a show as a whole (a season
      // pack, or a catalog with no episode ids). An empty answer must not cost the title: fall back to
      // the show address the hub used before this existed.
      log('EPISODE MISS', item.streamId, 'falling back to the show address');
      item.streamId = null;
      ep = null;
      await contributeSources(item);
    }
  }
  const [kept] = await pipeline([item], ctx); // rules rank sources and enforce the profile's scope
  if (!kept) return { status: 403, body: { identity, droppedByRule: true } };
  let sources = kept.sources || [];
  if (!sources.length) return { status: 404, body: { error: 'no sources', identity } };
  for (const s of sources) s.id = probe.sourceId(s); // the handle `exclude` names, assigned before filtering

  // Touch the links before handing any of them over, then let the ranking rule use what came back.
  const skip = exclusions(opts.exclude);
  const open = sources.filter((s) => !isExcluded(s, skip));
  await probePass(open.length ? open : sources, kept.streamId || identity, { force: opts.reprobe === true });
  // And the languages the copies state, from the track cache: the ranking rule prefers a proven Hindi
  // track over a filename that promises one, and the response has to carry the same list the picker
  // ranked on. Cache-only, so this costs the play budget nothing.
  const attached = attachTracks(sources, ep || wantFromStreamId(kept.streamId || identity),
    kept.streamId || identity);
  // Re-run the ranking rule with the measurement in hand. When probing is off this does not happen at
  // all, so a hub with PROBE_ENABLED=0 answers exactly as it did before any of this existed — except
  // that when the files themselves stated a language, ranking on that is worth the pass, and the rule
  // still refuses to invent a status or tagline without a measurement.
  if (probe.ENABLED || attached) {
    await rerank(kept, ctx);
    sources = kept.sources || sources;
  }

  // A client may ask for a specific source (the "Source 1..N" menu); otherwise the top of the
  // ranking wins. Either way the ranking is the rules' output, not this file's opinion.
  const pool = sources.filter((s) => !isExcluded(s, skip));
  const allExcluded = !pool.length;
  const candidates = allExcluded ? sources : pool;
  let source = candidates[0];
  if (wanted) {
    const match = candidates.find((s) => (wanted.label && s.label === wanted.label)
      || (wanted.provider && (s._addon === wanted.provider || s.provider === wanted.provider)));
    if (match) source = match;
  }
  const addonOf = (s) => registry.byId[s && s._addon];
  // Which wrapper the caller can read — settled BEFORE the picker runs, because the speed bar a copy has
  // to clear is a property of the client as much as of the file (PLAY_NATIVE_HEADROOM_SCALE explains why
  // a TV and a browser are not held to the same number).
  const asked = String(opts.container || 'any').toLowerCase();
  const container = ['any', 'mp4', 'hls'].includes(asked) ? asked : 'any';
  const nativeClient = container === 'any';
  const speedBar = ranking.HEADROOM * (nativeClient ? PLAY_NATIVE_HEADROOM_SCALE : 1);
  const fitCeiling = GATEWAY_FIT / (nativeClient ? PLAY_NATIVE_HEADROOM_SCALE : 1);

  // ---- the picker -------------------------------------------------------------------------
  //
  // The top of the ranking used to be handed over on trust, and that is how the household lost
  // evenings. A torrent whose swarm has not produced a single byte yet still resolves to a perfectly
  // shaped gateway URL; the engine answers it with an empty body; the player sniffs nothing and calls
  // the file corrupt. So the picker walks the ranked list instead of trusting its head, and a torrent
  // only wins after 256 KB has actually been read back through the engine. That read is not wasted
  // waiting: it is the first minutes of the film sitting in the engine's cache, which is exactly what
  // makes the player that follows start at once.
  //
  // `wanted` (the "Source 1..N" menu) still leads the queue — an explicit choice is honoured first and
  // abandoned only when it cannot deliver.
  const queue = [source];
  for (const s of candidates) if (s !== source && queue.length < PLAY_MAX_TRIES) queue.push(s);
  // Two kinds of known-bad get pushed to the back of the queue rather than dropped: a link the prober
  // watched die, and a torrent the addon says nobody is sharing. Stable sort keeps the ranking intact.
  const rankDown = (s) => (s && s.status === 'down' ? 2 : 0) + (s && s.seeders === 0 ? 1 : 0);
  queue.sort((a, b) => rankDown(a) - rankDown(b));
  // Then the copies this connection can actually feed. Once the hub has measured the engine's rate, a
  // 6 GB 4K copy stops competing with a 900 MB 720p one: the smaller is tried first because the bigger
  // one is known to stall. A source that is not a torrent gets the same standing as one that fits,
  // because it is the 70 Mbps HTTP path rather than the 1 Mbps BitTorrent one. The viewer asked for top
  // quality and gets the top quality that plays — which is what "Auto" means in a paid app.
  const fitRank = (s) => {
    if (!gatewayMbps || !s || s.kind !== 'torrent') return -1;
    // `kept` is the merged item itself (pipeline returns the items it kept), so the runtime this
    // arithmetic needs comes off it directly.
    const need = ranking.estimateMbps(s, kept);
    if (!need) return -1; // an adaptive ladder has no fixed bitrate; it adjusts itself
    return need <= gatewayMbps * fitCeiling ? 0 : need;
  };
  queue.sort((a, b) => fitRank(a) - fitRank(b));
  const labelOf = (s) => (s && (s.label || s.provider || s.id)) || 'source';

  const deadline = Date.now() + PLAY_TOTAL_BUDGET_MS;
  const tried = [];
  // What this response adds to the source's own reasoning: the container it chose and why, and whether
  // the engine is the one holding the copy back. Appended to `source.why` at the end, never replacing it.
  const extraWhy = [];
  let out = null;
  let lastResort = null; // a torrent the gateway could not serve: unplayable here, fine on an engine client
  let slowGateway = null; // a gateway link that resolved but has not produced bytes yet
  let tooSlow = null; // a gateway link that produced bytes, just not fast enough for this file
  for (const cand of queue) {
    const addon = addonOf(cand);
    if (!addon || typeof addon.play !== 'function') continue;
    const left = deadline - Date.now();
    if (left <= 0) { tried.push('out of time after ' + queue.indexOf(cand) + ' tries'); break; }
    const o = await withBudget(Promise.resolve().then(() => addon.play(kept.streamId || identity, cand)),
      Math.min(META_BUDGET_MS, left), null, 'play ' + addon.id);
    if (!o || !o.url) { tried.push(labelOf(cand) + ': no play target'); continue; }

    if (cand.kind !== 'torrent') {
      // A service-page link — a Prime Video / Hotstar / Jio detail URL — is not media. It used to be
      // accepted here because it answers instantly and "succeeds", and the TV's player then sniffed an
      // HTML page and reported a corrupt file: that is the whole of "error playing any movie/series".
      // These links belong on the title page as "Watch on <service>", never behind the Play button.
      if (o.link === true || o.kind === 'link' || o.needsProxy === true) {
        tried.push(labelOf(cand) + ': a service page, not a stream');
        continue;
      }
      if (rankDown(cand) && queue.length > 1) {
        tried.push(labelOf(cand) + ': measured down (' + ((cand.probe && cand.probe.error) || 'no bytes') + ')');
        lastResort = lastResort || { o, cand };
        continue;
      }
      out = o; source = cand; break;
    }

    // mode is the whole difference between our three playback paths; the client contract never changes.
    // Async on purpose: the gateway has to ask the engine which file inside the torrent is the film.
    //
    // The gateway is given the *resolved* source, not the ranked row: an addon's entry in the list is a
    // display shape (label, quality, size) and the actual magnet/hash only exists in the answer play()
    // just built. Reading the link out of `out` is the one place where the two are guaranteed to agree.
    const r = await gateway.resolve(
      { ...cand, url: o.url, infoHash: o.infoHash },
      // The prepare budget is clamped to the time left, not left at its own default: the app puts a
      // 12 s read timeout on this call, and a hub that answers at 15 s might as well not have answered.
      { clientHost: opts.clientHost, budgetMs: Math.max(1200, deadline - Date.now() - 1500),
        // A season pack is one torrent holding the whole season. The engine has to be told which file
        // inside it this press means, or the biggest file wins and a Play on S01E04 plays S01E01.
        want: ep ? { season: ep.season, episode: ep.episode } : null },
    );
    if (!r.url) {
      // The reason comes from the gateway itself, so the log can tell an engine that is down from a
      // torrent whose metadata never resolved and an addon that never gave us a hash.
      tried.push(labelOf(cand) + ': ' + r.reason);
      if (gateway.MODE === 'http') log('GATEWAY MISS', identity, r.reason);
      o.mode = 'direct';
      lastResort = lastResort || { o, cand };
      continue;
    }
    o.mode = 'gateway';
    o.magnet = o.url; // kept: a client with its own engine plays the magnet, and a warming answer falls back to it
    o.url = r.url;
    o.torrent = { hash: r.hash, fileId: r.fileId, fileName: r.fileName, fileSize: r.fileSize, seeders: r.seeders };
    // The engine's seeder count is read seconds after the torrent is added, before the swarm has had a
    // chance to connect, so a zero there proves nothing. The number the addon states — Torrentio writes
    // it into every stream — is the one that predicts whether a copy can play.
    if (cand.seeders === 0 && queue.length > 1) {
      tried.push(labelOf(cand) + ': nobody is seeding this copy');
      lastResort = lastResort || { o, cand };
      continue;
    }
    // Prove the bytes flow, and prove they flow *fast enough*. `null` means there is nothing to measure
    // (probing switched off) and is not a reason to refuse the link; `ok === false` means bytes were
    // asked for and none arrived. This is the same arithmetic the ranking rule uses on a measured HTTP
    // link — file size over runtime is the bitrate this copy needs, and the measured rate has to clear
    // it with headroom — which is what "auto" means on a paid app: keep the sharpest copy the swarm can
    // actually feed, and walk down a rung when it cannot.
    // The probe is clamped to the time actually left: the TV app puts a 12 s read timeout on this call,
    // so the last candidate the viewer hears about has to be one that was measured, not one the app gave
    // up waiting for. The 800 ms floor keeps a final try from being a zero-length measurement.
    const p = await probe.probeUrl(r.url, o.headers || {}, {
      timeoutMs: Math.max(800, Math.min(GATEWAY_DELIVER_MS, deadline - Date.now())),
      force: true,
    });
    if (p && p.ok === false) {
      tried.push(labelOf(cand) + ': engine served nothing (' + (p.error || p.status || 'no bytes') + ')');
      // A gateway link that was merely slow still beats a magnet on a TV that has no engine of its
      // own, so it is kept as the last resort rather than thrown away.
      o.warming = true;
      slowGateway = slowGateway || { o, cand };
      continue;
    }
    const needs = ranking.estimateMbps(cand, kept);
    const got = p && Number.isFinite(p.kbps) ? p.kbps / 1000 : null;
    // A sample that arrived faster than it can be timed is a floor, not a rate — and a floor taken out of
    // the engine's own cache is not even that. One 100 KB sample served from RAM divided by its own
    // millisecond of transfer once looked like 8388 kbps, and the picker then ordered a 6 GB copy that
    // had never put a byte on the wire. Only a real, timed transfer may move the link estimate.
    const rateIsReal = p && p.ok && p.rateReliable !== false && !p.rateFloor && got != null;
    if (rateIsReal) gatewayMbps = gatewayMbps ? gatewayMbps * 0.6 + got * 0.4 : got;
    else if (got != null && p && p.ok) {
      log('PLAY RATE IGNORED', identity, 'kbps=' + Math.round(got * 1000),
        'reason=' + (p.rateFloor ? 'rateFloor' : 'rateReliable=false'));
    }
    // Refusing a link on a number we cannot trust is worse than playing it. The bar itself is the
    // client's: a native player that buffers minutes ahead is given the relaxed one (speedBar), a
    // browser re-wrapping the copy keeps the rule's full demand.
    if (rateIsReal && needs && got < needs * speedBar) {
      tried.push(labelOf(cand) + ': measured ' + got.toFixed(1) + ' Mbps, this file needs ~'
        + needs.toFixed(1) + ' Mbps'
        + (speedBar < ranking.HEADROOM ? ' (bar relaxed to ' + speedBar.toFixed(2)
          + 'x for a client that plays the copy itself)' : ''));
      o.checked = true;
      o.probe = p;
      o.slow = true;
      o.neededMbps = Number(needs.toFixed(2));
      o.measuredMbps = Number(got.toFixed(2));
      const headroom = got / needs;
      if (!tooSlow || headroom > tooSlow.headroom) tooSlow = { o, cand, headroom };
      continue;
    }
    if (p) { o.checked = true; o.probe = p; }
    out = o; source = cand; break;
  }

  // A copy that is merely slow is a better answer than one that never answered, which is better than a
  // magnet this TV cannot open at all.
  const fallback = tooSlow || slowGateway || lastResort;
  if (!out && fallback) {
    out = fallback.o;
    source = fallback.cand;
    tried.push('nothing better than ' + labelOf(source));
  }
  if (!out || !out.url) {
    // The viewer sees "nothing played" either way; the difference is whether the log can say why.
    log('PLAY FAIL', identity, tried.join(' | '));
    return { status: 404, body: { error: 'no playable source', identity, tried } };
  }

  if (/^https?:\/\//i.test(out.url)) {
    const h = Object.entries(out.headers || {}).map(([k, v]) => k + ':' + v).join('||');
    out.proxiedUrl = '/app/v1/proxy?u=' + encodeURIComponent(out.url) + (h ? '&h=' + encodeURIComponent(h) : '');
    // A torrent is served through the hub, not straight from the engine, for two reasons measured on
    // this TV: (1) a cold copy answers slowly and the player's own HTTP timeout kills the connection
    // while the first bytes are still arriving, and the hub can wait far longer than a TV can; (2) every
    // extra reader on one torrent — the player, a retry, the hub's own read-ahead — makes the engine's
    // single sequential reader jump backwards. One reader, the hub, is the difference between a film and
    // an error. The engine port also stops needing to be reachable from every device in the house.
    if (out.mode === 'gateway' && opts.clientHost) {
      out.url = 'http://' + opts.clientHost + out.proxiedUrl;
      out.proxied = true;
    }
  }
  // ---- container: which wrapper the copy is handed over in ----------------------------------------
  //
  // `any` (the default, and what the installed TV app sends) is the copy's own container with its packets
  // untouched: the only way an E-AC3 or DTS bitstream reaches a receiver intact, and the reason the TV
  // path never goes near a re-wrap. `mp4` is the browser's answer — the same video packets re-wrapped as
  // fragmented MP4 by core/remux.js, audio copied when MP4 can carry it and AAC only when it cannot.
  // `hls` is the optional playlist for players that want segments.
  //
  // Nothing in this file transcodes video. This box has an i5-8500T and no GPU; decoding 1080p24 to
  // re-encode it is how a remux turns into a slideshow.
  out.container = container; // validated once, before the picker, so the bar and the answer agree
  out.remuxed = false;
  // The app hands this straight to DefaultHttpDataSource. An empty object is an answer, not an
  // omission: a torrent needs no headers, and the field being *present* is what lets one client code
  // path serve every mode.
  out.headers = out.headers || {};

  // The tracks of the copy actually chosen. At play time this is a cache read, or at most the remainder
  // of a probe that was already running from the title page — never a new ffprobe, and never more than
  // the time the play budget has left.
  const playHash = out.torrent && out.torrent.hash;
  const tracks = playHash
    ? await media.tracksFor(out.torrent.hash, out.torrent.fileId, null,
      { waitMs: Math.max(0, Math.min(1200, deadline - Date.now())) })
    : null;
  // Copies, not the cache's own objects: the TV needs absolute subtitle URLs and the browser needs the
  // relative ones, and both read the same cached entry.
  out.audioTracks = ((tracks && tracks.audioTracks) || []).map((t) => ({ ...t }));
  out.subtitleTracks = ((tracks && tracks.subtitleTracks) || []).map((t) => {
    const c = { ...t };
    // A browser is same-origin with the hub, so the relative path the cache stores is enough; a TV is
    // not, and a subtitle URL it can open has to name this hub.
    if (c.url && opts.clientHost && !/^https?:/i.test(c.url)) c.url = 'http://' + opts.clientHost + c.url;
    return c;
  });
  const wantAudio = remux.streamIndex(opts.audio, 'a');
  const wantSub = remux.streamIndex(opts.sub, 's');
  const byIndex = (list, prefix, idx) => (idx == null ? null : list.find((t) => t.id === prefix + ':' + idx)) || null;
  const byLanguage = (list, raw) => (typeof raw === 'string' && /^[a-z]{2,3}$/i.test(raw) && !/^\d+$/.test(raw)
    ? list.find((t) => t.language === String(raw).toLowerCase()) : null) || null;
  const chosenAudio = byIndex(out.audioTracks, 'a', wantAudio) || byLanguage(out.audioTracks, opts.audio)
    // Nothing asked for: name the track the hub would recommend, which is the one the container marks
    // default, else the first in the profile's own language order, else the first there is. The native
    // player uses this as its starting selection, so a Hindi copy starts in Hindi without the viewer
    // knowing the word "audio" exists.
    || out.audioTracks.find((t) => t.default)
    || out.audioTracks.find((t) => (prefs.resolve(profile).audioLanguages || []).includes(t.language))
    || out.audioTracks[0] || null;
  out.selectedAudio = chosenAudio ? chosenAudio.id : null;
  const chosenSub = byIndex(out.subtitleTracks, 's', wantSub) || byLanguage(out.subtitleTracks, opts.sub);
  // A subtitle nobody asked for is not a subtitle to burn in: the browser starts with them off and the
  // native player keeps its own default.
  out.selectedSub = chosenSub ? chosenSub.id : null;

  if (out.mode === 'gateway' && out.torrent && (out.container === 'mp4' || out.container === 'hls')) {
    const engine = gateway.enginePlayUrl(out.torrent.hash, out.torrent.fileId);
    // The container stream index the remux will map, which is what decides whether that audio is legal
    // in MP4 or has to become AAC.
    const audioIdx = wantAudio != null ? wantAudio
      : (chosenAudio ? Number(String(chosenAudio.id).slice(2)) : null);
    const audioPlan = remux.planFor(tracks, Number.isInteger(audioIdx) ? audioIdx : null);
    const rel = out.container === 'mp4'
      ? remux.mp4Url({ url: engine, audio: out.selectedAudio, sub: out.selectedSub })
      : remux.hlsUrl({ url: engine, audio: out.selectedAudio, sub: out.selectedSub, tracks });
    out.proxiedUrl = rel;
    out.url = 'http://' + (opts.clientHost || '127.0.0.1:' + PORT) + rel;
    out.proxied = true;
    out.remuxed = true;
    out.remux = {
      container: out.container,
      video: 'copy',
      audio: audioPlan,
      audioCodec: chosenAudio ? chosenAudio.codec : null,
      subtitle: out.selectedSub ? 'mov_text' : null,
    };
    extraWhy.push(out.container === 'mp4'
      ? 'container: fragmented MP4, video copied'
        + (audioPlan === 'aac'
          ? ', audio re-encoded to AAC because '
            + (chosenAudio && chosenAudio.codec ? chosenAudio.codec.toUpperCase() : 'that codec')
            + ' cannot live in an MP4 a browser will play'
          : audioPlan === 'none' ? ', this copy has no audio track' : ', audio copied')
      : 'container: HLS segments, video copied');
  } else if (out.container !== 'any') {
    extraWhy.push(out.mode === 'gateway'
      ? 'container: asked for ' + out.container + ', but this copy is not a torrent — handed over as it is'
      : 'container: this source is already an HTTP stream the player reads directly, no re-wrap needed');
  }
  // The TV path keeps the file's own container, and says so, because that is the difference between a
  // receiver that decodes Dolby and one that gets a stereo downmix nobody asked for.
  if (out.container === 'any' && out.mode === 'gateway') {
    extraWhy.push('container: native copy, no re-wrap (bitstream left intact)');
  }
  // A gateway link the engine has recently failed to open is not a playable answer, and the app already
  // knows what to do with a warming one: it re-presses the same source a beat later rather than starting
  // a player on a copy that has not been opened. Rejoining the swarm costs 19-108 s here — longer than
  // any player's patience, shorter than the keep-warm window — so pressing again is the correct move, and
  // the hub saying so is what makes the app do it instead of showing an error.
  if (out.warming === true && out.mode === 'gateway' && out.torrent && out.container === 'any'
      && gateway.isCold(out.torrent.hash)) {
    extraWhy.push('the engine opened this copy and lost it: pulling it back from the swarm takes longer than the player will wait, so press Play again in a moment');
    out.warmingUrl = out.url; // still here for a client that would rather wait on the stream itself
    out.url = out.magnet || '';
    out.proxied = false;
  }
  // The engine counts peers seconds after the torrent is added, before the swarm has connected, and the
  // app gates its own retry on this number. Hand it the count the hub actually believes.
  if (out.torrent && !out.torrent.seeders && Number.isFinite(source.seeders) && source.seeders > 0) {
    out.torrent.seeders = source.seeders;
  }
  // The legacy top-level `seeders` field, for a client reading the flat shape: same number, one place.
  if (Number.isFinite(source.seeders)) out.seeders = source.seeders;
  // Every copy of this title states zero seeders: that is not a pick that went slightly wrong, it is a
  // title nobody is sharing, and the answer has to say so instead of handing out an unwatchable link.
  if (candidates.length && candidates.every((s) => s.kind === 'torrent' && s.seeders === 0)) {
    tried.push('every copy of this title states 0 seeders: nobody is sharing any of them');
    extraWhy.push('nobody is seeding any copy of this title (0 seeders everywhere)');
    out.deadSwarm = true;
    if (!out.status || out.status === 'available') out.status = 'down';
  }
  // The one line that answers "why did the player fail?": which link the hub actually handed over. A
  // magnet and a gateway URL look the same to the caller until the player sniffs one and finds HTML.
  log('PLAY PICK', identity, [
    'mode=' + (out.mode || '?'), 'kind=' + (out.kind || '?'),
    'slow=' + (out.slow === true), 'warming=' + (out.warming === true),
    'tried=' + tried.length, String(out.url).slice(0, 96),
  ].join(' '));
  // One sequential read-ahead for the copy the viewer is actually about to watch. The engine caches
  // whatever a reader asked for, so this is the difference between "28 seconds then stall" and a film:
  // the player starts with a cushion instead of pulling the swarm up from a standing start. Only when
  // the player reads the engine directly — through the proxy the player's own read IS the read-ahead,
  // and a second reader on the same file would fight it for the engine's sequential cache.
  if (out.mode === 'gateway' && out.torrent && !out.proxied) {
    gateway.pump(out.torrent.hash, out.torrent.fileId, out.torrent.fileSize);
  }
  // The gateway read is the only real measurement a torrent ever gets — before this the ranked row for
  // a magnet had nothing to score but its label and size. Writing it back onto the row means the next
  // time this title is opened the ranking already knows how fast this exact link put bytes on the wire.
  if (out.probe && out.probe !== source.probe) {
    source.probe = out.probe;
    source.checked = true;
    if (out.probe.ok) source.status = 'available';
  }
  out.picked = source;
  out.why = (source.why || []).concat(extraWhy);
  // The three things the player actually asks: is this one known to stream, what was measured, and
  // which link to try if this one buffers. `checked:false` means the hub could not measure this one
  // cheaply (a magnet, a URL that costs a child process to mint), so it is choosing on metadata.
  out.checked = out.checked === true || source.checked === true;
  out.probe = out.probe || source.probe || null;
  out.status = source.status || null;
  out.tagline = source.tagline || null;
  // What the picker rejected on the way here. When playback fails this is the difference between a
  // log line that explains the evening and one that says "no playable source".
  out.tried = tried;
  const next = candidates.find((s) => s !== source && (s.id !== source.id));
  out.nextSource = next ? {
    id: next.id || null,
    label: next.label || null,
    provider: next.provider || null,
    tagline: next.tagline || null,
    status: next.status || null,
    checked: next.checked === true,
  } : null;
  if (allExcluded) out.allExcluded = true;
  out.alternatives = candidates.filter((s) => s !== source).slice(0, ALT_MAX);
  out.title = kept.title || null;
  // Which episode the Play button actually resolved, and the one after it, so the player can label its
  // button "Play S1 E4" and "Autoplay next" can step a series instead of replaying the same one.
  if (ep) {
    out.episode = { season: ep.season, episode: ep.episode, title: ep.title || null };
    const eps = Array.isArray(kept.episodes) ? kept.episodes : [];
    const nx = eps.find((x) => x.season === ep.season && x.episode === ep.episode + 1)
      || eps.find((x) => x.season === ep.season + 1) || null;
    out.nextEpisode = nx ? { season: nx.season, episode: nx.episode, title: nx.title || null } : null;
  }
  return { status: 200, body: out };
}

const server = http.createServer(async (req, res) => {
  const raw = req.url || '/';
  // A bad URL is a bad request, not a broken hub. `decodeURIComponent` THROWS on a malformed escape (a
  // scanner asking for "/%%", a TV app that percent-encoded half an identity), and these two lines used to
  // sit above the try below — so one unparsed request took the process down and every screen in the house
  // went blank until the hub was restarted. Answer 400 and carry on.
  let u;
  let path;
  try {
    u = new URL(raw, 'http://localhost');
    path = decodeURIComponent(u.pathname);
  } catch (e) {
    return send(res, 400, { error: 'malformed request URI', uri: raw });
  }
  res.req = req;

  try {
    if (req.method === 'OPTIONS') {
      res.writeHead(204, corsHeaders());
      return res.end();
    }

    // ---- the front-end itself ----
    // One UI everywhere: a browser loads the app straight from the hub, and the TV app shows the very
    // same files over a native video surface. Served before the API so `/` can be the app while the
    // machine-readable health body keeps its own path.
    if (webui.serve(req, res, path)) return;

    // ---- health ----
    if (path === '/healthz') {
      return send(res, 200, await healthBody());
    }

    // ---- legacy addon-compat (byte-compatible with work/iptv/iptv-india-addon.js) ----
    if (path === '/manifest.json') {
      await Promise.all(compatProviders().map((p) => ensureCompat(p)));
      return send(res, 200, addonManifest());
    }

    let m = /^\/catalog\/([^/]+)\/([^/]+?)(?:\/(.*))?\.json$/.exec(path);
    if (m) {
      const [, type, catId, rest] = m;
      const extra = new URLSearchParams(rest === 'default' ? '' : (rest || ''));
      const hit = type === 'tv' && compatProviders().find((p) => (p.legacy.catalogIds || []).includes(catId));
      if (!hit) return send(res, 404, { metas: [] });
      await ensureCompat(hit);
      const out = await withBudget(Promise.resolve().then(() => hit.legacy.catalog(type, catId, extra)),
        COMPAT_BUDGET_MS, null, 'compat catalog ' + hit.id + '/' + catId);
      if (!out) return send(res, 404, { metas: [] });
      return send(res, out.status, out.body);
    }

    m = /^\/meta\/([^/]+)\/([^/]+)\.json$/.exec(path);
    if (m) {
      const [, , id] = m;
      const hit = compatProviders().find((p) => claimsPrefix(p, id));
      if (!hit) return send(res, 404, { meta: null });
      await ensureCompat(hit);
      const out = await withBudget(Promise.resolve().then(() => hit.legacy.meta(id)),
        COMPAT_BUDGET_MS, null, 'compat meta ' + hit.id);
      if (!out) return send(res, 404, { meta: null });
      return send(res, out.status, out.body);
    }

    m = /^\/stream\/([^/]+)\/([^/]+)\.json$/.exec(path);
    if (m) {
      const [, , id] = m;
      const hit = compatProviders().find((p) => claimsPrefix(p, id));
      if (!hit) return send(res, 404, { streams: [] });
      await ensureCompat(hit);
      const out = await withBudget(Promise.resolve().then(() => hit.legacy.stream(id)),
        COMPAT_BUDGET_MS, null, 'compat stream ' + hit.id);
      if (!out) return send(res, 404, { streams: [] });
      return send(res, out.status, out.body);
    }

    // ---- product API ----
    if (path === '/app/v1/manifest') {
      return send(res, 200, {
        id: HUB_ID,
        version: HUB_VERSION,
        theme: THEME_ID,
        api: '/app/v1',
        addons: await addons.list(),
        rows: registry.rows.map((r) => ({
          id: r.id, title: r.title, card: r.card, order: r.order, scope: r.scope || 'all', max: r.max || ROW_MAX,
        })),
        cards: uniq(registry.rows.map((r) => r.card)),
        rules: registry.rules.map((r) => ({ id: r.id, order: r.order })),
        players: ['http', 'hls', 'dash', 'torrent', 'gateway'],
        gateway: Object.assign({}, gateway.status(), {
      // The rate the engine has last been measured delivering, so the number the picker orders by is
      // visible from outside rather than inferred from which copy it happened to choose.
      linkMbps: gatewayMbps ? Number(gatewayMbps.toFixed(2)) : null,
    }),
        // What this hub can actually do, so a client UI decides from facts rather than from guessing
        // which build of the hub it happens to be talking to — the same contract is answered by the app
        // itself with no server at all, and that difference has to be visible in the answer.
        capabilities: capabilities(),
        compat: { routes: ['/manifest.json', '/catalog/', '/meta/', '/stream/'] },
      });
    }

    // ---- the owner's settings -------------------------------------------------------------
    // Everything a household would want to change is here rather than in an environment variable,
    // because a value only changeable by editing a file on the server is a value the household does not
    // control. GET answers the current values plus what each means; PUT merges a patch and re-applies
    // whatever other subsystems derived from the old one.
    if (path === '/app/v1/settings' && req.method === 'GET') {
      return send(res, 200, {
        settings: settings.get(),
        defaults: settings.DEFAULTS,
        // The language names are returned so a settings screen can label the codes it is choosing
        // between without carrying its own translation table.
        languageNames: ranking.LANG_NAMES,
        file: settings.FILE,
      });
    }
    if (path === '/app/v1/settings' && req.method === 'PUT') {
      const body = await readBody(req);
      if (!body || typeof body !== 'object' || Array.isArray(body)) {
        return send(res, 400, { error: 'expected a JSON object of settings' });
      }
      const before = settings.get();
      const next = settings.set(body);
      const effects = {};
      // A community addon's configuration *is* its URL, so changing the hub's languages or size bands
      // means re-asking the addon: the answer it gave the old question is not an answer to the new one.
      effects.addonsResynced = addons.resyncSeeds().changed;
      const live = registry.byId['live-tv'];
      if (live && typeof live.reload === 'function'
        && ((before.live || {}).playlist !== (next.live || {}).playlist
          || before.languages.join(',') !== next.languages.join(','))) {
        live.reload();
        effects.liveReload = 'requested';
      }
      invalidateCaches();
      log('SETTINGS changed', JSON.stringify({
        languages: next.languages.join(',') || '(no filter)',
        hubName: next.hubName,
        playlist: (next.live || {}).playlist || '(none)',
        addons: effects.addonsResynced + ' remounted',
      }));
      return send(res, 200, { ok: true, settings: next, effects });
    }

    if (path === '/app/v1/profiles') {
      return send(res, 200, {
        profiles: store.profiles().map((p) => ({
          ...p,
          prefs: prefs.resolve(p),
          rows: registry.rows.filter((r) => rowVisible(r, p)).map((r) => r.id),
        })),
      });
    }

    if (path === '/app/v1/home') {
      const profile = store.profile(u.searchParams.get('profile') || u.searchParams.get('profileId'));
      const ctx = makeCtx(profile, HEALTH_BUDGET_MS);
      // Rows build in parallel, each inside the home budget: /app/v1/home never waits on a slow
      // upstream, it answers from addon cache and degrades to a shorter row.
      const built = await Promise.all(
        registry.rows.filter((r) => rowVisible(r, profile)).map(async (row) => ({
          id: row.id,
          title: row.title,
          card: row.card,
          items: await buildRow(row, ctx, HOME_BUDGET_MS),
        })),
      );
      // Then one pass over the finished screen: billboard to the front, and not the same title twice
      // (see arrangeHome). Every row is already built and ruled, so this costs nothing but a Set.
      return send(res, 200, { rows: arrangeHome(built) });
    }

    m = /^\/app\/v1\/row\/([^/]+)$/.exec(path);
    if (m) {
      const row = registry.rows.find((r) => r.id === m[1]);
      if (!row) return send(res, 404, { error: 'no such row', rowId: m[1] });
      const profile = store.profile(u.searchParams.get('profile') || u.searchParams.get('profileId'));
      if (!rowVisible(row, profile)) {
        return send(res, 403, { error: 'row not for this profile', rowId: row.id, scope: row.scope });
      }
      const ctx = makeCtx(profile, HEALTH_BUDGET_MS);
      return send(res, 200, { id: row.id, title: row.title, card: row.card, items: await buildRow(row, ctx) });
    }

    if (path === '/app/v1/search') {
      const q = u.searchParams.get('q') || '';
      const profile = store.profile(u.searchParams.get('profile') || u.searchParams.get('profileId'));
      const ctx = makeCtx(profile, HEALTH_BUDGET_MS);
      // Two sources of answers, one pipeline: what the hub already carries (instant, offline) and any
      // addon whose catalog declares a search extra. Both then pass the same profile rules.
      const all = searchLibrary(await ctx.library(), q);
      for (const p of registry.providers) {
        if (typeof p.catalog !== 'function') continue;
        let cats = [];
        try {
          cats = (typeof p.catalogs === 'function' ? await p.catalogs() : []) || [];
        } catch (e) { /* skip */ }
        for (const c of cats) {
          if (!(c.extra || []).includes('search')) continue;
          all.push(...(await ctx.catalog(p.id, c.id, { search: q }, SEARCH_BUDGET_MS)));
        }
      }
      const items = await pipeline(all, ctx);
      return send(res, 200, { q, items: items.slice(0, SEARCH_MAX) });
    }

    m = /^\/app\/v1\/meta\/(.+)$/.exec(path);
    if (m) {
      const identity = m[1];
      const profile = store.profile(u.searchParams.get('profile') || u.searchParams.get('profileId'));
      const ctx = makeCtx(profile, META_BUDGET_MS);
      // The client knows the tile's type; passing it stops an addon guessing the wrong one.
      const item = await ctx.resolve(identity, u.searchParams.get('type'));
      if (!item) return send(res, 404, { error: 'not found', identity });
      const [kept] = await pipeline([item], ctx);
      // Anything already measured is free, so the detail page shows the same status the play path
      // will use. Nothing here waits on the network: the meta response is exactly as fast as it was.
      // Files first, and only from caches: the ranking rule reads the languages a copy states, and it can
      // only do that if the tracks are on the source before the rule runs. The detail page is where that
      // measurement is worth having — it is what turns "audio language not stated" into "Hindi audio".
      const attached = kept
        ? attachTracks(kept.sources || [], wantFromStreamId(kept.streamId || identity), kept.streamId || identity) : 0;
      if (kept && probe.ENABLED) {
        // The stream index is keyed by the address that was fetched — for a series that is the episode,
        // not the show, so every lookup below has to use the same key or it finds nothing.
        peekPass(kept.sources || [], kept.streamId || identity);
        await rerank(kept, ctx); // the rule turns any measurement into status + tagline on each source
      } else if (kept && attached) {
        // Probing off, but the files spoke: rank on what they said. The rule still refuses to set
        // status/tagline without a measurement, so the response keeps its old shape.
        await rerank(kept, ctx);
      }
      send(res, kept ? 200 : 403, kept || { identity, droppedByRule: true });
      // Then start measuring the rest, after the bytes are out: the person is reading the synopsis,
      // and by the time they press play the answer is in the cache instead of costing 1.5 s.
      if (kept) {
        probePass(kept.sources || [], kept.streamId || identity, {})
          .catch((e) => log('PROBE WARM FAIL', identity, e.message));
        // Same trick for the torrent engine: registering a magnet and learning which file inside it is
        // the film costs seconds the Play button does not have. Doing it while the synopsis is being
        // read is what makes the press instant. The ranked rows only carry the display shape, so the
        // magnet comes from the addon's own index — the same lookup play() will do a moment later.
        gateway.prewarm((kept.sources || []).map((s) => {
          const magnet = magnetOf(kept.streamId || identity, s);
          return magnet ? { ...s, url: magnet } : s;
        }), wantFromStreamId(kept.streamId || identity));
      }
      // Must not fall through: the next matcher would answer this same request a second time, and a
      // second send on a finished response takes the whole hub down (ERR_HTTP_HEADERS_SENT).
      return;
    }

    // A trailer is a preview, not a playback decision. It is looked up on this box by the title the hub
    // itself knows (core/trailers.js shells out to yt-dlp), and answered in exactly the shape
    // /app/v1/play uses — { url, headers, kind, expiresAt } — so the app has one player path, not two.
    // 404 with a reason when there is nothing to show; an empty shelf of a trailer is not an error state.
    m = /^\/app\/v1\/trailer\/(.+)$/.exec(path);
    if (m) {
      // A malformed percent-escape is a bad identity, not a broken hub: decodeURIComponent throws, and the
      // app would read the resulting 500 as "the hub is down" when the truth is "that is not a title".
      let identity;
      try { identity = decodeURIComponent(m[1]); } catch (e) {
        return send(res, 404, { error: 'no trailer', identity: m[1], reason: 'unreadable identity' });
      }
      const profile = store.profile(u.searchParams.get('profile') || u.searchParams.get('profileId'));
      // A title hidden from this profile does not have a trailer either.
      const seen = await visibleTo(identity, profile, u.searchParams.get('type'));
      if (!seen.ok) return send(res, 403, { identity, droppedByRule: true, tier: seen.tier, minAge: seen.minAge });
      // Already looked up once? Then the record carries the title, and no addon needs to be asked.
      let known = trailers.peek(identity);
      if (!known || !known.title) {
        const ctx = makeCtx(profile, META_BUDGET_MS);
        known = await ctx.resolve(identity, u.searchParams.get('type')) || {};
      }
      if (!known.title) return send(res, 404, { error: 'no trailer', identity, reason: 'unknown identity' });
      const t = await withBudget(
        Promise.resolve().then(() => trailers.forTitle(identity, known)),
        trailers.TIME_BUDGET_MS, null, 'trailer ' + identity,
      );
      if (!t || !t.url) {
        return send(res, 404, {
          identity, title: known.title || null,
          error: (t && t.error) || 'no trailer within ' + trailers.TIME_BUDGET_MS + 'ms',
        });
      }
      const out = {
        identity,
        url: t.url,
        headers: t.headers || {},
        kind: t.kind || 'http',
        // The clip this url is a copy of. A client with its own YouTube player can take the id and ignore
        // the signed url entirely; the signed url is here because it is what a plain TV player opens.
        videoId: t.videoId || null,
        expiresAt: t.expiresAt || null,
        title: known.title || null,
        quality: t.quality || null,
        durationMs: t.durationMs || null,
        sizeBytes: t.sizeBytes || null,
        cached: Boolean(t.cached),
      };
      // A signed googlevideo URL needs the headers that produced it replayed, so the proxy is offered
      // beside it exactly as play() offers it — the app picks whichever its player can use.
      const h = Object.entries(out.headers).map(([k, v]) => k + ':' + v).join('||');
      out.proxiedUrl = '/app/v1/proxy?u=' + encodeURIComponent(out.url) + (h ? '&h=' + encodeURIComponent(h) : '');
      return send(res, 200, out);
    }

    // Subtitles are addons too: merge every installed addon's subtitles catalog.
    m = /^\/app\/v1\/subs\/(.+)$/.exec(path);
    if (m) {
      const identity = m[1];
      const profile = store.profile(u.searchParams.get('profile') || u.searchParams.get('profileId'));
      const seen = await visibleTo(identity, profile, u.searchParams.get('type'));
      if (!seen.ok) return send(res, 403, { identity, droppedByRule: true, tier: seen.tier, minAge: seen.minAge });
      const want = String(u.searchParams.get('lang') || prefs.resolve(profile).audioLanguages.join(','))
        .split(',').map((s) => s.trim().toLowerCase()).filter(Boolean);
      const found = [];
      for (const p of registry.providers) {
        if (typeof p.subtitles !== 'function') continue;
        const list = await withBudget(Promise.resolve().then(() => p.subtitles(identity, want)),
          SUBS_BUDGET_MS, [], 'subs ' + p.id);
        for (const s of list || []) found.push({ ...s, addon: p.name || p.id });
      }
      found.sort((a, b) => {
        const ai = want.indexOf(String(a.lang || '').toLowerCase());
        const bi = want.indexOf(String(b.lang || '').toLowerCase());
        return (ai < 0 ? 99 : ai) - (bi < 0 ? 99 : bi);
      });
      return send(res, 200, { identity, langs: want, subs: found });
    }

    // Addons: the list the app's Settings renders, and the install/remove surface.
    if (path === '/app/v1/addons' && req.method === 'POST') {
      const body = await readBody(req);
      const out = await addons.add(body && body.url, body && body.name);
      invalidateCaches();
      if (!out.ok) return send(res, out.status || 500, { error: out.error });
      return send(res, 200, { ok: true, addon: out.addon.stats(), addons: await addons.list() });
    }
    m = /^\/app\/v1\/addons\/([^/]+)\/(enable|disable)$/.exec(path);
    if (m && req.method === 'POST') {
      const st = addons.setEnabled(m[1], m[2] === 'enable');
      invalidateCaches();
      return st
        ? send(res, 200, { ok: true, addon: st })
        : send(res, 404, { error: 'no such addon', id: m[1] });
    }
    m = /^\/app\/v1\/addons\/([^/]+)$/.exec(path);
    if (m && req.method === 'DELETE') {
      const ok = addons.remove(m[1]);
      invalidateCaches();
      return ok
        ? send(res, 200, { ok: true, removed: m[1], addons: await addons.list() })
        : send(res, 404, { error: 'no such addon', id: m[1] });
    }
    if (path === '/app/v1/addons') {
      return send(res, 200, {
        addons: await addons.list(),
        file: addons.paths.ADDONS_FILE,
        limits: addons.limits,
      });
    }

    // Per-profile playback preference: what this household wants a stream to be.
    // The client spells these in bytes and bps; the rule reads GB and Mbps. Both travel.
    const toWire = (p) => ({
      ...p,
      maxFileSizeBytes: Number.isFinite(p.maxSizeGB) ? Math.round(p.maxSizeGB * 1e9) : 0,
      maxBitrate: p.maxBitrateMbps ? Math.round(p.maxBitrateMbps * 1e6) : 0,
      subtitleLanguages: p.subtitleLanguages || p.audioLanguages,
      qualityLevels: ['auto', '4k', '1080p', '720p', '480p'], // what a settings screen can offer
    });
    const fromWire = (body) => {
      const src = (body && (body.prefs || body)) || {};
      const out = {};
      if (Array.isArray(src.audioLanguages) && src.audioLanguages.length) out.audioLanguages = src.audioLanguages;
      if (Array.isArray(src.subtitleLanguages) && src.subtitleLanguages.length) out.subtitleLanguages = src.subtitleLanguages;
      if (src.quality) out.quality = src.quality;
      if (typeof src.preferAdaptive === 'boolean') out.preferAdaptive = src.preferAdaptive;
      // The picture level this profile asks for. Stored as the canonical word, so a settings screen can
      // send "Full HD" or "2160p" and core/prefs.js resolves the same ceiling either way.
      if (src.qualityCap !== undefined) {
        const cap = prefs.CAP_ALIASES[String(src.qualityCap).trim().toLowerCase()];
        if (cap) out.qualityCap = cap;
        else out.qualityCap = String(src.qualityCap).trim(); // kept so the app can show what it sent
      }
      // The dark-red-zone switch. A settings screen may send a boolean or the word "on"; anything else is
      // a no. Off, tier-3 titles are not in home, search, history or Continue Watching for that profile.
      if (src.xxxEnabled !== undefined) {
        out.xxxEnabled = src.xxxEnabled === true || ['true', '1', 'yes', 'on'].includes(String(src.xxxEnabled).toLowerCase());
      }
      const bytes = Number(src.maxFileSizeBytes || src.maxSizeBytes || 0);
      if (bytes > 0) out.maxSizeGB = Number((bytes / 1e9).toFixed(3));
      const rate = Number(src.maxBitrate || src.maxBitrateBps || 0);
      if (rate > 0) out.maxBitrateMbps = rate > 1000 ? Number((rate / 1e6).toFixed(2)) : rate;
      if (src.maxBitrateMbps) out.maxBitrateMbps = Number(src.maxBitrateMbps);
      return out;
    };
    if (path === '/app/v1/prefs' && req.method === 'POST') {
      const body = await readBody(req);
      const wanted = (body && (body.profile || body.profileId)) || '';
      if (!wanted) return send(res, 400, { error: 'profile required' });
      const current = store.profile(wanted);
      if (!current || !current.id) return send(res, 404, { error: 'no such profile', profile: wanted });
      const saved = store.patchProfile(current.id, { prefs: { ...(current.prefs || {}), ...fromWire(body) } });
      return send(res, 200, {
        profile: current.id,
        prefs: toWire(prefs.resolve(saved || current)),
        defaults: toWire(prefs.DEFAULTS),
      });
    }
    if (path === '/app/v1/prefs') {
      // `profile` and `profileId` are the same question in two spellings; the app uses the second.
      const profile = store.profile(u.searchParams.get('profile') || u.searchParams.get('profileId'));
      return send(res, 200, { profile: profile.id, prefs: toWire(prefs.resolve(profile)), defaults: toWire(prefs.DEFAULTS) });
    }

    if (path === '/app/v1/proxy') return await proxyStream(req, res, u);

    // A browser cannot play Matroska, and a TV does not want its Dolby re-encoded on the way to it, so
    // the caller says which container it can read. `any` (the default, and what the installed app sends)
    // is the copy untouched; `mp4` is fragmented MP4 from core/remux.js; `hls` is the optional playlist.
    // `audio` and `sub` are track ids from the source list (`a:1`, `s:2`) — the container's own stream
    // indexes, which is what lets the same id drive a native track selection and an ffmpeg `-map`.
    if (path === '/app/v1/remux' && req.method === 'GET') return remux.serve(req, res, u, corsHeaders(), media.peek);

    m = /^\/app\/v1\/hls\/([^/]+)\/([^/]+)$/.exec(path);
    if (m && req.method === 'GET') return remux.hlsServe(req, res, u, corsHeaders(), m[1], m[2]);

    // An embedded subtitle track, converted to WebVTT by the hub: a browser `<track>` element is blocked
    // by CORS on the engine, and an ASS stream is not a format any browser reads in the first place.
    if (path === '/app/v1/subtitle' && req.method === 'GET') return remux.subtitle(req, res, u, corsHeaders(), media.peek);

    if (path === '/app/v1/play' && req.method === 'POST') {
      const body = await readBody(req);
      if (!body || !body.identity) return send(res, 400, { error: 'identity required' });
      const profile = store.profile(body.profile || body.profileId);
      const picked = await pickAndPlay(body.identity, profile,
        body.source || (body.provider ? { provider: body.provider } : null), body.type,
        { exclude: body.exclude, reprobe: body.reprobe === true, clientHost: req.headers.host,
          season: body.season, episode: body.episode,
          container: body.container, audio: body.audio, sub: body.sub });
      return send(res, picked.status, picked.body);
    }

    // "This link is buffering — give me another one." Same answer as /app/v1/play, but the measurement
    // cache is ignored: the viewer is complaining about a source that measured fine a minute ago, so
    // the hub re-measures the alternatives and ranks again. `exclude` names what has already been
    // tried (id, label, or "provider:label" — the id comes back on every source object).
    if (path === '/app/v1/switch' && req.method === 'POST') {
      const body = await readBody(req);
      if (!body || !body.identity) return send(res, 400, { error: 'identity required' });
      const profile = store.profile(body.profile || body.profileId);
      const picked = await pickAndPlay(body.identity, profile,
        body.source || (body.provider ? { provider: body.provider } : null), body.type,
        { exclude: body.exclude, reprobe: body.reprobe !== false, clientHost: req.headers.host,
          season: body.season, episode: body.episode,
          container: body.container, audio: body.audio, sub: body.sub });
      return send(res, picked.status, picked.body);
    }

    if (path === '/app/v1/progress' && req.method === 'POST') {
      const body = await readBody(req);
      if (!body || !body.identity) return send(res, 400, { error: 'identity required' });
      const who = body.profile || body.profileId || 'default';
      // A tier-3 title whose switch is off does not exist for this profile, so it cannot have a history
      // entry either: refuse the write instead of storing something no view is allowed to show. Turning
      // the switch back on brings the stored entries of the person who switched it on out again — the
      // records are kept, only the views filter them (rows/continue.js runs through the same rule).
      // Judge the real item, not whatever the client chose to send: the folder a file lives in, or the
      // addon that published a title, is what marks it tier 3, and neither is in the request body.
      const item = await metaOnly(body.identity, body.type);
      const known = ratings.know(body.identity, item || { identity: body.identity, title: body.title, genres: body.genres, type: body.type });
      if (known.tier === 3 && !prefs.resolve(store.profile(who)).xxxEnabled) {
        return send(res, 403, { error: 'not stored: the adult switch is off for this profile', identity: body.identity, tier: 3 });
      }
      const progress = store.setProgress(who, body.identity, body);
      return send(res, 200, { ok: true, identity: body.identity, progress });
    }

    return send(res, 404, { error: 'not found', path });
  } catch (e) {
    log('REQUEST FAIL', path, e.stack || e.message);
    return send(res, 500, { error: String(e.message || e) });
  }
});

server.listen(PORT, '0.0.0.0', () => {
  log(HUB_ID, HUB_VERSION, 'on http://0.0.0.0:' + PORT + '  (clients: /app/v1/home?profile=<id>)');
  log('addons:', registry.providers.map((p) => p.id + (p.native ? '' : '@' + p.priority)).join(', '));
  log('rows:', registry.rows.map((r) => r.id + '/' + (r.scope || 'all') + '@' + r.order).join(', '));
  log('rules:', registry.rules.map((r) => r.id + '@' + r.order).join(', '));
  log('gateway:', gateway.status().mode, '-', gateway.status().note);
  log('probe:', probe.ENABLED
    ? 'measures ' + Math.round(probe.limits.PROBE_BYTES / 1024) + ' KB per link, '
      + probe.limits.CONCURRENCY + ' at a time, max ' + probe.limits.BUDGET_MS
      + 'ms per play decision, cache ' + probe.FILE
    : 'off (PROBE_ENABLED=0) - every source is ranked on metadata alone');
  // The two things that read the files themselves. Both fail the same way when the binary is missing —
  // every copy looks like it states no language, or a browser is handed a container it cannot open — so
  // the state of the tooling belongs on the console next to the gateway line, not buried in /healthz.
  log('tracks:', media.status().binFound
    ? (media.status().binVersion || 'ffprobe') + ' at ' + media.status().bin + ' - cache ' + media.status().file
      + ', ' + media.limits.PREWARM_MAX + ' probed per title page, ' + media.limits.PLAY_WAIT_MS
      + 'ms max wait at play'
    : 'NO ffprobe (' + media.status().binNote + ') - audio languages stay "not stated"');
  // Pairing by typing an address with a remote is the worst interaction in the product, so the hub says
  // what it is called and where it lives on the LAN. Off with ANNOUNCE=0.
  discovery.start(PORT);
  log('remux:', remux.status().binFound
    ? (remux.status().binVersion || 'ffmpeg') + ' at ' + remux.status().bin + ' - fragmented MP4 and HLS, '
      + remux.status().max + ' concurrent, video always copied'
    : 'NO ffmpeg (' + remux.status().binNote + ') - container "mp4"/"hls" answer 503, native playback unaffected');
  // Addons warm their own caches on their own schedule (see each addon's start()).
  // Kick the legacy compat load in the background too, exactly as the old addon did on listen.
  for (const p of compatProviders()) ensureCompat(p);
});
