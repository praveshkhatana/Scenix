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
const maturityRule = require('./rules/maturity');


// Home must answer from cache: /app/v1 never waits on the network for more than ~1.5 s.
const HOME_BUDGET_MS = Number(process.env.HOME_BUDGET_MS || 1500);
const ROW_BUDGET_MS = Number(process.env.ROW_BUDGET_MS || 1200);
const CATALOG_BUDGET_MS = Number(process.env.CATALOG_BUDGET_MS || 800);
const META_BUDGET_MS = Number(process.env.META_BUDGET_MS || 3000); // a detail page may take a round trip
const HEALTH_BUDGET_MS = Number(process.env.HEALTH_BUDGET_MS || 800);
const SOURCE_BUDGET_MS = Number(process.env.SOURCE_BUDGET_MS || 120); // cache-only, so this is generous
const SUBS_BUDGET_MS = Number(process.env.SUBS_BUDGET_MS || 4000);
const PROXY_TIMEOUT_MS = Number(process.env.PROXY_TIMEOUT_MS || 15000);
const ROW_MAX = Number(process.env.ROW_MAX || 80);
const SEARCH_MAX = Number(process.env.SEARCH_MAX || 60);
// A person typed this, so it is worth a round trip; a home row is never allowed one.
const SEARCH_BUDGET_MS = Number(process.env.SEARCH_BUDGET_MS || 3000);
const ALT_MAX = Number(process.env.ALT_MAX || 9);
const DAYS = Number(process.env.DAYS || 45); // "just added" window
const THEME_ID = process.env.THEME_ID || 'netflix-dark';
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
        await contributeSources(item);
        // A detail page may spend one round trip so the picker is complete before anyone taps Play.
        // An addon that already answered from cache is not asked twice.
        for (const p of registry.providers) {
          if (typeof p.fetchStreams !== 'function') continue;
          if ((item.sources || []).some((s) => s._addon === p.id)) continue;
          const fresh = await withBudget(
            Promise.resolve().then(() => p.fetchStreams(identity)), META_BUDGET_MS, [], 'streams ' + p.id,
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

function send(res, code, body, type = 'application/json') {
  const req = res.req;
  if (process.env.LOG_REQUESTS === '1' && req) {
    console.log(new Date().toISOString(), 'REQ', req.method, req.url, '->', code);
  }
  const headers = { 'Content-Type': type, 'Cache-Control': 'no-cache', ...corsHeaders() };
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
    gateway: gateway.status(),
    trailers: trailers.status(),
    ratings: ratings.status(),
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
  const mod = target.startsWith('https:') ? https : http;
  const upstream = await new Promise((resolve) => {
    const r = mod.get(target, { headers, timeout: PROXY_TIMEOUT_MS }, resolve);
    r.on('error', (e) => resolve({ error: e }));
    r.on('timeout', () => {
      r.destroy();
      resolve({ error: new Error('upstream timeout') });
    });
  });
  if (upstream.error) return send(res, 502, { error: 'proxy failed', detail: upstream.error.message });
  const pass = { ...corsHeaders() };
  for (const k of ['content-type', 'content-length', 'content-range', 'accept-ranges', 'cache-control']) {
    if (upstream.headers[k]) pass[k] = upstream.headers[k];
  }
  if (process.env.LOG_REQUESTS === '1') log('REQ', req.method, req.url, '->', upstream.statusCode, '(proxy)');
  res.writeHead(upstream.statusCode || 200, pass);
  upstream.pipe(res);
  upstream.on('error', () => res.destroy());
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

/** Ask the addon that offered a source for its playable URL, then wrap it in the client contract. */
async function pickAndPlay(identity, profile, wanted, type) {
  const ctx = makeCtx(profile, META_BUDGET_MS);
  const item = await ctx.resolve(identity, type);
  if (!item) return { status: 404, body: { error: 'not found', identity } };
  const [kept] = await pipeline([item], ctx); // rules rank sources and enforce the profile's scope
  if (!kept) return { status: 403, body: { identity, droppedByRule: true } };
  const sources = kept.sources || [];
  if (!sources.length) return { status: 404, body: { error: 'no sources', identity } };
  // A client may ask for a specific source (the "Source 1..N" menu); otherwise the top of the
  // ranking wins. Either way the ranking is the rules' output, not this file's opinion.
  let source = sources[0];
  if (wanted) {
    const match = sources.find((s) => (wanted.label && s.label === wanted.label)
      || (wanted.provider && (s._addon === wanted.provider || s.provider === wanted.provider)));
    if (match) source = match;
  }
  const addon = registry.byId[source._addon] || registry.byId[wanted && wanted.provider];
  if (!addon || typeof addon.play !== 'function') {
    return { status: 404, body: { error: 'no playable addon', identity, sources: sources.length } };
  }
  const out = await withBudget(Promise.resolve().then(() => addon.play(identity, source)),
    META_BUDGET_MS, null, 'play ' + addon.id);
  if (!out || !out.url) return { status: 404, body: { error: 'no playable source', identity } };

  // mode is the whole difference between our three playback paths; the client contract never changes.
  const gw = gateway.gatewayUrl(source, identity);
  out.mode = gw ? 'gateway' : (out.needsProxy ? 'proxy' : 'direct');
  if (gw) out.url = gw;
  if (/^https?:\/\//i.test(out.url)) {
    const h = Object.entries(out.headers || {}).map(([k, v]) => k + ':' + v).join('||');
    out.proxiedUrl = '/app/v1/proxy?u=' + encodeURIComponent(out.url) + (h ? '&h=' + encodeURIComponent(h) : '');
  }
  out.picked = source;
  out.why = source.why || [];
  out.alternatives = sources.filter((s) => s !== source).slice(0, ALT_MAX);
  out.title = kept.title || null;
  return { status: 200, body: out };
}

const server = http.createServer(async (req, res) => {
  const raw = req.url || '/';
  const u = new URL(raw, 'http://localhost');
  const path = decodeURIComponent(u.pathname);
  res.req = req;

  try {
    if (req.method === 'OPTIONS') {
      res.writeHead(204, corsHeaders());
      return res.end();
    }

    // ---- health ----
    if (path === '/' || path === '/healthz') {
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
        gateway: gateway.status(),
        compat: { routes: ['/manifest.json', '/catalog/', '/meta/', '/stream/'] },
      });
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
      return send(res, 200, { rows: built });
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
      return send(res, kept ? 200 : 403, kept || { identity, droppedByRule: true });
    }

    // A trailer is a preview, not a playback decision. It is looked up on this box by the title the hub
    // itself knows (core/trailers.js shells out to yt-dlp), and answered in exactly the shape
    // /app/v1/play uses — { url, headers, kind, expiresAt } — so the app has one player path, not two.
    // 404 with a reason when there is nothing to show; an empty shelf of a trailer is not an error state.
    m = /^\/app\/v1\/trailer\/(.+)$/.exec(path);
    if (m) {
      const identity = decodeURIComponent(m[1]);
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

    if (path === '/app/v1/play' && req.method === 'POST') {
      const body = await readBody(req);
      if (!body || !body.identity) return send(res, 400, { error: 'identity required' });
      const profile = store.profile(body.profile || body.profileId);
      const picked = await pickAndPlay(body.identity, profile,
        body.source || (body.provider ? { provider: body.provider } : null), body.type);
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
  // Addons warm their own caches on their own schedule (see each addon's start()).
  // Kick the legacy compat load in the background too, exactly as the old addon did on listen.
  for (const p of compatProviders()) ensureCompat(p);
});
