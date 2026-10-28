'use strict';
/*
 * core/debrid.js — an OPTIONAL debrid resolver, off with no token.
 *
 * The hub can already play a torrent through core/gateway.js (TorrServer over HTTP) and a direct file
 * through the client. A debrid account buys the one thing a home swarm cannot: the file is already on a
 * fast server, so a magnet or a file-hoster link becomes an ordinary seekable HTTP URL in a second.
 * That is a paid service, so this file is not allowed to be necessary. With `debrid.enabled: false` —
 * the shipped default — not one line of the play path changes and no network call is made here.
 *
 * The contract (design/debrid.md):
 *
 *   provider file in core/debrid/*.js
 *     { id, label, hosters?: string[],
 *       unrestrict(url, ctx)          -> { url, kind, expiresAt, provider, providerLabel, fileName? }
 *       resolveMagnet(magnet, ctx)    -> the same shape
 *       status(ctx)                   -> { ok, premium?, expiresAt?, reason? } }
 *
 *   this module
 *     unrestrict(url, opts)           -> { url|null, reason }
 *     resolveMagnet(magnet, opts)     -> { url|null, reason }
 *     resolve(source, opts)           -> either of the above, chosen from the source shape
 *     status()                        -> facts for /healthz, never the token
 *
 * `ctx` carries `{ token, want, deadline, http, redact }`. Every call has a hard deadline; a provider
 * that ignores it is cut off by `withTimeout`. Nothing here throws: a resolver that is down, unconfigured
 * or slow is a reason on an answer, and the caller falls through to the gateway.
 */

const fs = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');
const settings = require('./settings');

const PROVIDER_DIR = path.join(__dirname, 'debrid');
const DEFAULT_BUDGET_MS = Math.max(1000, Number(process.env.DEBRID_BUDGET_MS || 5000));
const MAX_BUDGET_MS = Math.max(DEFAULT_BUDGET_MS, Number(process.env.DEBRID_MAX_BUDGET_MS || 10000));
const HTTP_TIMEOUT_MS = Math.max(500, Number(process.env.DEBRID_HTTP_TIMEOUT_MS || 5000));
const CACHE_TTL_MS = Math.max(60e3, Number(process.env.DEBRID_CACHE_MS || 2 * 3600e3));
const CACHE_MAX = Math.max(16, Number(process.env.DEBRID_CACHE_MAX || 200));
const NEGATIVE_TTL_MS = Math.max(1000, Number(process.env.DEBRID_MISS_MS || 20e3));

const log = (...a) => console.log(new Date().toISOString(), ...a);

/* ------------------------------------------------------------------ provider discovery */

/**
 * A provider is one file. It is loaded at boot; a broken adapter is reported once and skipped, never
 * allowed to make the hub fail to start.
 */
function loadProviders() {
  const out = new Map();
  let names = [];
  try {
    names = fs.readdirSync(PROVIDER_DIR)
      .filter((f) => f.endsWith('.js') && !f.startsWith('.') && !f.startsWith('_'))
      .sort();
  } catch (e) { /* no provider directory is a valid install: only the built-in path is used */ }
  for (const name of names) {
    try {
      const p = require(path.join(PROVIDER_DIR, name));
      if (!p || !p.id || typeof p.unrestrict !== 'function' || typeof p.resolveMagnet !== 'function') {
        log('DEBRID PROVIDER SKIP', name, 'missing id/unrestrict/resolveMagnet');
        continue;
      }
      out.set(String(p.id).toLowerCase(), p);
    } catch (e) {
      // An adapter's syntax error is a fact about that adapter, not a reason to take the hub down.
      log('DEBRID PROVIDER FAIL', name, redactText(e && e.message));
    }
  }
  return out;
}

const PROVIDERS = loadProviders();

/** The provider list a settings screen can offer, without any account or token. */
function providers() {
  return Array.from(PROVIDERS.values()).map((p) => ({ id: p.id, label: p.label || p.id }));
}

/* ------------------------------------------------------------------ redaction + HTTP */

/**
 * Strip the account token out of anything that might be shown or logged. Called on every reason and
 * every HTTP error; provider responses are never passed through whole.
 */
function redactText(text, token) {
  let s = String(text == null ? '' : text);
  if (token) s = s.split(String(token)).join('[redacted]');
  s = s.replace(/(Bearer\s+)[A-Za-z0-9._~+\/=.-]+/gi, '$1[redacted]');
  s = s.replace(/(auth_token=)[^&\s]+/gi, '$1[redacted]');
  return s.slice(0, 240);
}

/**
 * One bounded HTTP exchange. Never throws, never logs, never includes request headers in its answer.
 * `maxBytes` stops a provider (or a proxy in front of one) from handing this process an endless body.
 */
function httpJson(opts, token) {
  return new Promise((resolve) => {
    const started = Date.now();
    let settled = false;
    let timer = null;
    const done = (v) => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      resolve(Object.assign({ ms: Date.now() - started }, v));
    };
    let u;
    try { u = new URL(String(opts.url || '')); } catch (e) { return done({ ok: false, error: 'bad provider URL' }); }
    if (u.protocol !== 'https:' && u.protocol !== 'http:') return done({ ok: false, error: 'unsupported provider protocol' });

    const timeoutMs = Math.max(250, Number(opts.timeoutMs) || HTTP_TIMEOUT_MS);
    const maxBytes = Math.max(1024, Number(opts.maxBytes) || 256 * 1024);
    const payload = opts.form ? Buffer.from(new URLSearchParams(opts.form).toString()) : null;
    const headers = Object.assign({ Accept: 'application/json' }, opts.headers || {});
    if (payload) {
      headers['content-type'] = 'application/x-www-form-urlencoded';
      headers['content-length'] = payload.length;
    }

    let req;
    try {
      req = (u.protocol === 'https:' ? https : http).request(u, {
        method: opts.method || 'GET',
        headers,
        timeout: timeoutMs,
      }, (res) => {
        const chunks = [];
        let size = 0;
        res.on('data', (c) => {
          size += c.length;
          if (size > maxBytes) {
            try { res.destroy(); } catch (e) { /* already gone */ }
            done({ ok: false, status: res.statusCode, error: 'provider response too large' });
            return;
          }
          chunks.push(c);
        });
        res.on('end', () => {
          const body = Buffer.concat(chunks).toString('utf8');
          let json = null;
          try { json = JSON.parse(body); } catch (e) { json = null; }
          const status = Number(res.statusCode) || 0;
          if (status >= 200 && status < 300) return done({ ok: true, status, json, text: body.slice(0, 240) });
          let reason = '';
          if (json && typeof json === 'object' && json.error) reason = String(json.error);
          else if (typeof json === 'string' && json.trim()) reason = json.trim();
          if (status === 401) reason = 'bad or expired token (401)';
          else if (status === 403) reason = 'permission denied (403)';
          else if (status === 429) reason = 'provider rate limit reached (429)';
          else if (status >= 500 && !reason) reason = 'provider unavailable (' + status + ')';
          else if (!reason) reason = 'provider HTTP ' + status;
          done({ ok: false, status, json, error: redactText(reason, token) });
        });
        res.on('error', (e) => done({ ok: false, status: res.statusCode, error: 'provider response failed' }));
      });
    } catch (e) {
      return done({ ok: false, error: 'provider request failed' });
    }

    timer = setTimeout(() => {
      try { req.destroy(); } catch (e) { /* already gone */ }
      done({ ok: false, error: 'provider timeout after ' + timeoutMs + 'ms' });
    }, timeoutMs);
    if (timer.unref) timer.unref();
    req.on('error', (e) => {
      done({ ok: false, error: 'provider unreachable (' + String((e && e.code) || 'network') + ')' });
    });
    req.on('timeout', () => {
      try { req.destroy(); } catch (e) { /* already gone */ }
      done({ ok: false, error: 'provider timeout after ' + timeoutMs + 'ms' });
    });
    if (payload) req.write(payload);
    req.end();
  });
}

function makeHttp(token) {
  return (opts) => httpJson(opts, token);
}

/* ------------------------------------------------------------------ config + status */

function config() {
  const raw = (settings.get() || {}).debrid || {};
  const provider = String(raw.provider || '').trim().toLowerCase();
  return {
    enabled: raw.enabled === true,
    provider,
    token: String(raw.token || '').trim(),
    providerModule: PROVIDERS.get(provider) || null,
  };
}

/** Is the setting on and is there an adapter for the chosen provider? (A token may still be missing.) */
function enabled() {
  const c = config();
  return c.enabled && !!c.providerModule;
}

/** On, a known provider and a token: the only state in which the play path may spend a round trip. */
function ready() {
  const c = config();
  return c.enabled && !!c.providerModule && !!c.token;
}

function statusNote(c) {
  if (!c.enabled) return 'off: no debrid account; torrents use the gateway and direct links play as they are';
  if (!c.provider) return 'enabled, but no provider is chosen';
  if (!c.providerModule) return 'enabled, but provider "' + c.provider + '" is not installed';
  if (!c.token) return 'enabled for ' + c.providerModule.label + ', but no token is stored';
  return c.providerModule.label + ' is configured; magnets and hoster links are tried before the gateway';
}

let lastResolve = null; // { at, ok, kind, provider, ms, reason, cached } — no URL, no token
let lastCheck = { at: 0, ok: null, reason: 'not checked' };
const cache = new Map(); // key -> { url, kind, expiresAt, provider, providerLabel, fileName, fileSize, at }
const failures = new Map(); // key -> { until, reason }
let cacheHits = 0;
let cacheMisses = 0;

function status() {
  const c = config();
  const now = Date.now();
  prune(now);
  let negative = 0;
  for (const [, f] of failures) if (f.until > now) negative++;
  return {
    enabled: c.enabled,
    provider: c.provider || null,
    providerLabel: c.providerModule ? (c.providerModule.label || c.providerModule.id) : null,
    tokenSet: !!c.token,
    ready: ready(),
    note: statusNote(c),
    providers: providers(),
    cache: {
      entries: cache.size,
      hits: cacheHits,
      misses: cacheMisses,
      negative: negative,
      max: CACHE_MAX,
      ttlMs: CACHE_TTL_MS,
    },
    last: lastResolve ? Object.assign({}, lastResolve) : null,
    check: Object.assign({}, lastCheck),
  };
}

/** Ask the provider whether the token works. Never called by /healthz; a settings screen or an operator may. */
async function check() {
  const c = config();
  if (!c.enabled) { lastCheck = { at: Date.now(), ok: false, reason: 'debrid off' }; return lastCheck; }
  if (!c.providerModule) { lastCheck = { at: Date.now(), ok: false, reason: 'no provider installed' }; return lastCheck; }
  if (!c.token) { lastCheck = { at: Date.now(), ok: false, reason: 'no token stored' }; return lastCheck; }
  const ctx = {
    token: c.token,
    deadline: Date.now() + 4000,
    http: makeHttp(c.token),
    redact: (s) => redactText(s, c.token),
  };
  const r = await withTimeout(Promise.resolve().then(() => c.providerModule.status(ctx)), 4500);
  lastCheck = {
    at: Date.now(),
    ok: !!(r && r.ok),
    reason: r && r.ok ? null : redactText((r && (r.reason || r.error)) || 'provider did not answer', c.token),
    premium: r && r.ok ? r.premium === true : null,
    expiresAt: r && r.ok ? (r.expiresAt || null) : null,
  };
  return lastCheck;
}

/* ------------------------------------------------------------------ cache */

function prune(now) {
  for (const [k, v] of cache) {
    if (!v.expiresAt || v.expiresAt <= now + 30e3) cache.delete(k);
  }
  for (const [k, f] of failures) if (f.until <= now) failures.delete(k);
  while (cache.size > CACHE_MAX) {
    const oldest = cache.keys().next();
    if (oldest.done) break;
    cache.delete(oldest.value);
  }
}

function cacheGet(key) {
  const hit = cache.get(key);
  if (!hit) return null;
  if (!hit.expiresAt || hit.expiresAt <= Date.now() + 30e3) { cache.delete(key); return null; }
  cache.delete(key);
  cache.set(key, hit); // keep recently used entries when pruning
  cacheHits++;
  return hit;
}

function cachePut(key, value, provider) {
  const expiresAt = Number(value.expiresAt) > Date.now()
    ? Number(value.expiresAt) : Date.now() + CACHE_TTL_MS;
  cache.delete(key);
  cache.set(key, {
    url: value.url,
    kind: value.kind || 'http',
    expiresAt,
    provider: value.provider || provider,
    providerLabel: value.providerLabel || null,
    fileName: value.fileName || null,
    fileSize: value.fileSize || null,
    at: Date.now(),
  });
  prune(Date.now());
}

function missGet(key) {
  const f = failures.get(key);
  if (!f) return null;
  if (f.until <= Date.now()) { failures.delete(key); return null; }
  return f.reason;
}

function missPut(key, reason) {
  failures.set(key, { until: Date.now() + NEGATIVE_TTL_MS, reason });
  prune(Date.now());
}

/* ------------------------------------------------------------------ helpers */

function sleep(ms) { return new Promise((resolve) => setTimeout(resolve, Math.max(0, ms))); }

/** A hard outer stop. Provider code that ignores its deadline still cannot hold a Play press open. */
function withTimeout(promise, ms, token) {
  return new Promise((resolve) => {
    let done = false;
    const finish = (v) => {
      if (done) return;
      done = true;
      resolve(v);
    };
    const timer = setTimeout(() => finish({ url: null, reason: 'debrid timeout after ' + ms + 'ms' }), Math.max(250, ms));
    if (timer.unref) timer.unref();
    Promise.resolve(promise).then((v) => {
      clearTimeout(timer);
      finish(v);
    }).catch((e) => {
      clearTimeout(timer);
      finish({ url: null, reason: 'debrid failed: ' + redactText((e && e.message) || 'unknown error', token) });
    });
  });
}

function hashOf(text) {
  const m = /btih:([a-fA-F0-9]{40}|[a-zA-Z2-7]{32})/.exec(String(text || ''));
  return m ? m[1].toLowerCase() : null;
}

/** A magnet from whichever field the addon used, or from a bare info hash. */
function magnetOf(source) {
  const raw = String((source && (source.magnet || source.url || source.link)) || '').trim();
  if (/^magnet:\?/i.test(raw)) return raw;
  const direct = String((source && (source.infoHash || source.hash)) || '').trim();
  if (/^[a-fA-F0-9]{40}$/.test(direct)) return 'magnet:?xt=urn:btih:' + direct.toLowerCase();
  if (/^[a-fA-F0-9]{40}$/.test(raw)) return 'magnet:?xt=urn:btih:' + raw.toLowerCase();
  const m = /btih:([a-fA-F0-9]{40})/i.exec(raw);
  return m ? 'magnet:?xt=urn:btih:' + m[1].toLowerCase() : null;
}

function wantKey(want) {
  const s = Number(want && want.season);
  const e = Number(want && want.episode);
  return s > 0 && e > 0 ? ':' + s + ':' + e : '';
}

function isPrivateHost(host) {
  const h = String(host || '').toLowerCase().replace(/^\[|\]$/g, '').replace(/\.$/, '');
  if (!h) return true;
  if (h === 'localhost' || h === '::1' || h === '::' || h.endsWith('.local') || h.endsWith('.internal')) return true;
  const v4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(h);
  if (v4) {
    const [a, b] = [Number(v4[1]), Number(v4[2])];
    if (a === 0 || a === 10 || a === 127) return true;
    if (a === 169 && b === 254) return true; // link-local
    if (a === 192 && b === 168) return true;
    if (a === 172 && b >= 16 && b <= 31) return true;
    if (a === 100 && b >= 64 && b <= 127) return true; // carrier-grade NAT
    return false;
  }
  if (/^(fc|fd|fe80)/.test(h)) return true; // IPv6 ULA / link-local
  return false;
}

/** Only a public http(s) URL may be sent to a third-party resolver; a LAN URL is never its business. */
function isPublicHttp(raw) {
  let u;
  try { u = new URL(String(raw || '')); } catch (e) { return false; }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') return false;
  return !isPrivateHost(u.hostname);
}

/** Does this URL look like a file hoster a provider could unrestrict? */
function isHosterUrl(raw) {
  if (!isPublicHttp(raw)) return false;
  let host = '';
  try { host = new URL(String(raw)).hostname.toLowerCase().replace(/^www\./, ''); } catch (e) { return false; }
  for (const p of PROVIDERS.values()) {
    for (const h of (p.hosters || [])) {
      const d = String(h || '').toLowerCase();
      if (d && (host === d || host.endsWith('.' + d))) return true;
    }
  }
  return false;
}

function hostOf(url) {
  try { return new URL(String(url)).hostname.slice(0, 80); } catch (e) { return '?'; }
}

function note(kind, ok, ms, provider, reason, cached) {
  lastResolve = {
    at: Date.now(),
    ok: ok === true,
    kind,
    provider: provider || null,
    ms: Math.round(ms || 0),
    reason: ok ? null : String(reason || 'no answer').slice(0, 240),
    cached: cached === true,
  };
}

/* ------------------------------------------------------------------ resolution */

function context(c, budgetMs, opts) {
  return {
    token: c.token,
    want: opts && opts.want ? { season: opts.want.season, episode: opts.want.episode } : null,
    deadline: Date.now() + budgetMs,
    budgetMs,
    http: makeHttp(c.token),
    redact: (s) => redactText(s, c.token),
  };
}

async function attempt(c, kind, key, budgetMs, opts, work) {
  const hit = cacheGet(key);
  if (hit) {
    note(kind, true, 0, hit.provider || c.provider, null, true);
    log('DEBRID', hit.provider || c.provider, kind, 'ok', 'cached=true', 'host=' + hostOf(hit.url));
    return Object.assign({}, hit, { reason: null, cached: true });
  }
  const known = missGet(key);
  if (known) {
    note(kind, false, 0, c.provider, known, true);
    return { url: null, reason: known, cached: true };
  }

  cacheMisses++;
  const started = Date.now();
  const ctx = context(c, budgetMs, opts);
  const raw = await withTimeout(Promise.resolve().then(() => work(ctx)), budgetMs + 500, c.token);
  const ms = Date.now() - started;

  if (raw && raw.url && /^https?:\/\//i.test(String(raw.url))) {
    const value = {
      url: String(raw.url),
      kind: raw.kind === 'hls' ? 'hls' : 'http',
      expiresAt: Number(raw.expiresAt) > Date.now() ? Number(raw.expiresAt) : Date.now() + CACHE_TTL_MS,
      provider: raw.provider || c.provider,
      providerLabel: raw.providerLabel || (c.providerModule && c.providerModule.label) || c.provider,
      fileName: raw.fileName || null,
      fileSize: raw.fileSize || null,
    };
    cachePut(key, value, c.provider);
    note(kind, true, ms, value.provider, null, false);
    log('DEBRID', value.provider, kind, 'ok', 'ms=' + Math.round(ms),
      'cached=false', 'host=' + hostOf(value.url));
    return Object.assign({}, value, { reason: null, cached: false });
  }

  const reason = redactText((raw && (raw.reason || raw.error)) || 'provider returned no URL', c.token);
  missPut(key, reason);
  note(kind, false, ms, c.provider, reason, false);
  log('DEBRID MISS', c.provider || '?', kind, 'ms=' + Math.round(ms), reason);
  return { url: null, reason };
}

/** Public: unrestrict one hoster URL. Never throws. */
async function unrestrict(url, opts = {}) {
  let c = null;
  const started = Date.now();
  try {
    c = config();
    if (!c.enabled) return { url: null, reason: 'debrid off', skipped: true };
    if (!c.providerModule) return { url: null, reason: 'no debrid provider installed', skipped: true };
    if (!c.token) return { url: null, reason: 'debrid enabled but no token stored', skipped: true };
    if (!isPublicHttp(url)) return { url: null, reason: 'not a public HTTP link', skipped: true };
    const budgetMs = Math.max(700, Math.min(Number(opts.budgetMs) || DEFAULT_BUDGET_MS, MAX_BUDGET_MS));
    return await attempt(c, 'hoster', 'url:' + String(url), budgetMs, opts,
      (ctx) => c.providerModule.unrestrict(String(url), ctx));
  } catch (e) {
    const reason = 'debrid failed: ' + redactText((e && e.message) || 'unknown error', c && c.token);
    note('hoster', false, Date.now() - started, c && c.provider, reason, false);
    return { url: null, reason };
  }
}

/** Public: resolve a magnet or info hash to a direct URL. Never throws. */
async function resolveMagnet(magnet, opts = {}) {
  let c = null;
  const started = Date.now();
  try {
    c = config();
    if (!c.enabled) return { url: null, reason: 'debrid off', skipped: true };
    if (!c.providerModule) return { url: null, reason: 'no debrid provider installed', skipped: true };
    if (!c.token) return { url: null, reason: 'debrid enabled but no token stored', skipped: true };
    const m = String(magnet || '').trim();
    if (!m) return { url: null, reason: 'no magnet' };
    const key = 'magnet:' + (hashOf(m) || m.slice(0, 80)) + wantKey(opts.want);
    const budgetMs = Math.max(700, Math.min(Number(opts.budgetMs) || DEFAULT_BUDGET_MS, MAX_BUDGET_MS));
    return await attempt(c, 'magnet', key, budgetMs, opts,
      (ctx) => c.providerModule.resolveMagnet(m, ctx));
  } catch (e) {
    const reason = 'debrid failed: ' + redactText((e && e.message) || 'unknown error', c && c.token);
    note('magnet', false, Date.now() - started, c && c.provider, reason, false);
    return { url: null, reason };
  }
}

/**
 * The one entry point the play path needs: choose the torrent or hoster question from the source shape.
 * A direct MP4 that belongs to no hoster is `skipped`, so the caller does not put a misleading reason in
 * its "tried" list.
 */
async function resolve(source, opts = {}) {
  let c = null;
  try {
    c = config();
    if (!c.enabled) return { url: null, reason: 'debrid off', skipped: true };
    if (!source) return { url: null, reason: 'no source', skipped: true };
    const kind = String(source.kind || '').toLowerCase();
    const raw = String(source.url || source.magnet || source.link || '').trim();
    if (kind === 'torrent' || /^magnet:/i.test(raw) || source.infoHash) {
      const magnet = magnetOf(source);
      if (!magnet) return { url: null, reason: 'no magnet or info hash', skipped: true };
      return await resolveMagnet(magnet, opts);
    }
    if (kind === 'hoster' || isHosterUrl(raw)) return await unrestrict(raw, opts);
    return { url: null, reason: 'not a magnet or a supported hoster link', skipped: true };
  } catch (e) {
    const reason = 'debrid failed: ' + redactText((e && e.message) || 'unknown error', c && c.token);
    return { url: null, reason };
  }
}

module.exports = {
  id: 'debrid',
  enabled,
  ready,
  status,
  check,
  providers,
  resolve,
  unrestrict,
  resolveMagnet,
  isHosterUrl,
  isPublicHttp,
  // The one-seam self-test: a caller can pass a provider object and a fake http helper without a token
  // or a live account. Nothing in the hub calls this.
  _providerContext: (c, budgetMs, opts) => context(c, budgetMs, opts),
  _redact: redactText,
};
