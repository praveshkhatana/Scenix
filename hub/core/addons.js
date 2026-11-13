'use strict';
/*
 * core/addons.js — the addon host. Content only enters the hub through addons.
 *
 * Two kinds, one contract (restated in ADDONS.md):
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
const dns = require('dns');
const fs = require('fs');
const net = require('net');
const path = require('path');
const { URL, URLSearchParams } = require('url');

const settings = require('./settings');
const langdb = require('./langdb');

const ROOT = path.join(__dirname, '..');
const DATA_DIR = process.env.DATA_DIR || path.join(ROOT, 'data');
const ADDONS_FILE = process.env.ADDONS_FILE || path.join(DATA_DIR, 'addons.json');
const DEFAULT_ADDONS_FILE = process.env.DEFAULT_ADDONS_FILE || path.join(ROOT, 'addons.example.json');
const CACHE_FILE = process.env.ADDON_CACHE_FILE || path.join(DATA_DIR, 'addon-cache.json');

function boundedInteger(value, fallback, min, max) {
  const n = Number(value);
  if (!Number.isFinite(n)) return fallback;
  return Math.min(max, Math.max(min, Math.floor(n)));
}

const TTL_MS = Number(process.env.ADDON_TTL_MS || 6 * 60 * 60 * 1000); // freshness rule, 6 h
/**
 * An addon answering `{"streams": []}` is not reporting a fact about the title; it is reporting that it
 * had nothing at that moment. Measured on the play path: an episode's copy list was present on one
 * request and empty on the next, and the 6 h rule held that empty answer for the rest of the evening —
 * "1080p · 259 MB: no play target", with the good copy gone from the candidate list. An empty answer is
 * kept short so the next press re-asks; a non-empty one still rides the long TTL.
 */
const EMPTY_TTL_MS = Number(process.env.ADDON_EMPTY_TTL_MS || 3 * 60 * 1000);
// Remote add-ons are untrusted network peers. Keep every wait bounded even when a deployment has a
// malformed environment value, and leave enough headroom for a slow but otherwise healthy TV network.
const FETCH_TIMEOUT_MS = boundedInteger(process.env.ADDON_TIMEOUT_MS, 8000, 250, 30000);
const META_WAIT_MS = boundedInteger(process.env.ADDON_META_WAIT_MS, 6000, 250, 30000); // a detail page may wait this long
const MANIFEST_WAIT_MS = boundedInteger(process.env.ADDON_MANIFEST_WAIT_MS, 8000, 250, 30000);
const MAX_BODY_BYTES = boundedInteger(process.env.ADDON_MAX_BODY_BYTES, 2 * 1024 * 1024, 16 * 1024, 8 * 1024 * 1024);
const MAX_REDIRECTS = boundedInteger(process.env.ADDON_MAX_REDIRECTS, 4, 0, 8);
// Subtitle format lies: some addons serve SRT bytes from a URL/name that says ".vtt", and the app's
// subtitleMime(format, url) trusts the hub's format first. sniffing the first bytes of the file itself
// (range GET) is the only honest signal. Keep those probes small, short-lived and via the pinned
// transport below, so a sniff can never break the subtitle listing it serves.
const SUB_SNIFF_BYTES = boundedInteger(process.env.SUB_SNIFF_BYTES, 512, 128, 2048);
const SUB_SNIFF_TIMEOUT_MS = boundedInteger(process.env.SUB_SNIFF_TIMEOUT_MS, 2500, 250, 8000);
const SUB_SNIFF_BUDGET_MS = boundedInteger(process.env.SUB_SNIFF_BUDGET_MS, 2000, 250, 8000);
const SUB_SNIFF_CONCURRENCY = boundedInteger(process.env.SUB_SNIFF_CONCURRENCY, 24, 1, 48);
const SUB_SNIFF_CACHE_MAX = 2000;
const SUB_SNIFF_TTL_MS = TTL_MS; // a sniffed truth rides the addon TTL
const SUB_SNIFF_UNKNOWN_TTL_MS = boundedInteger(process.env.SUB_SNIFF_UNKNOWN_TTL_MS, 5 * 60 * 1000, 10000, 6 * 60 * 60 * 1000);
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

// Seed install: what a brand-new hub ships with, before anyone configures anything. The list lives in
// addons.example.json so the legal defaults are reviewable in one place, and so an owner can see exactly
// what a fresh box starts with. It contains only catalogues, subtitle search, watch links and the native
// providers already in this tree: no torrent index and no credential.
//
// The file is a SEED, not the owner's list. readEntries() reads data/addons.json; only when that file is
// absent does init() build the first list from here. Existing hubs keep their own file, including any
// source they added, and the seed merge below only fills a source that is missing altogether.
//
// A community addon used to carry this household's language list inside its URL. That is deliberately
// gone from the defaults: a stranger's hub must not be told what languages to ask for, and the per-profile
// language rules already decide what is worth showing. `SEED_ADDON_URLS` still replaces the external list
// for an installer that wants its own sources.
const FALLBACK_DEFAULT_ADDONS = [
  { id: 'cinemeta', name: 'Cinemeta', url: 'https://v3-cinemeta.strem.io/manifest.json', priority: 40 },
  { id: 'opensubtitles', name: 'OpenSubtitles v3', url: 'https://opensubtitles-v3.strem.io/manifest.json', priority: 41 },
  { id: 'watchhub', name: 'WatchHub', url: 'https://watchhub.strem.io/manifest.json', priority: 42 },
  { id: 'scenix-games', name: 'Scenix Games', url: 'https://praveshkhatana.github.io/scenix-games-addon/manifest.json', priority: 43 },
  { id: 'local-files', name: 'Local Files', native: true },
  { id: 'public-domain', name: 'Public Domain Movies', native: true },
  { id: 'live-tv', name: 'Live TV playlists', native: true },
  { id: 'kids-yt', name: 'Curated kids YouTube', native: true },
];

/** Keep only well-formed entries; a typo in the example file must not break a first boot. */
function normalizeDefaultAddons(entries) {
  const out = [];
  const seen = new Set();
  for (const entry of Array.isArray(entries) ? entries : []) {
    if (!entry || typeof entry !== 'object') continue;
    const id = String(entry.id || '').trim().slice(0, 80);
    if (!id || seen.has(id)) continue;
    const name = String(entry.name || id).trim().slice(0, 80) || id;
    if (entry.native === true) {
      seen.add(id);
      out.push({ id, name, native: true, enabled: entry.enabled !== false });
      continue;
    }
    const url = String(entry.url || '').trim();
    if (!/^https?:\/\//i.test(url)) continue;
    seen.add(id);
    out.push({
      id, name, url,
      enabled: entry.enabled !== false,
      priority: Number.isFinite(Number(entry.priority)) ? Number(entry.priority) : 40 + out.length,
    });
  }
  return out;
}

/** The fresh-install defaults, from addons.example.json when it is present. */
function defaultAddons() {
  let raw = null;
  try {
    raw = JSON.parse(fs.readFileSync(DEFAULT_ADDONS_FILE, 'utf8'));
  } catch (e) {
    if (e.code !== 'ENOENT') log('ADDONS defaults unreadable, using built-ins:', DEFAULT_ADDONS_FILE, e.message);
    return normalizeDefaultAddons(FALLBACK_DEFAULT_ADDONS);
  }
  const entries = Array.isArray(raw) ? raw
    : Array.isArray(raw && raw.addons) ? raw.addons : null;
  return normalizeDefaultAddons(entries || FALLBACK_DEFAULT_ADDONS);
}

/** The external seeds only: what the seed merge and resyncSeeds() may add or configure. */
function seedAddons() {
  if (process.env.SEED_ADDON_URLS) {
    return String(process.env.SEED_ADDON_URLS)
      .split(/,(?=https?:\/\/)/).map((s) => s.trim()).filter(Boolean)
      .map((url) => ({ id: '', name: null, url, enabled: true }));
  }
  return defaultAddons().filter((a) => !a.native);
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

// ---- remote boundary -------------------------------------------------------------------------------

// Add-ons are configured by URL, which makes this the trust boundary for every external catalogue.
// Keep the policy here rather than at a single install route: persisted legacy entries, seed entries and
// every redirect must all receive the same protection.
const DEFAULT_REDIRECT_POLICY = 'public';
const SAME_ORIGIN_REDIRECT_POLICY = 'same-origin';
const CACHE_KEY_SEPARATOR = '\u0000';

function blockedAddonUrl(message) {
  const error = new Error(message || 'addon address is not a public HTTP(S) endpoint');
  error.code = 'ADDON_URL_BLOCKED';
  return error;
}

function normalizeRedirectPolicy(value) {
  if (value === SAME_ORIGIN_REDIRECT_POLICY || value === true
    || (value && typeof value === 'object' && value.sameOrigin === true)) {
    return SAME_ORIGIN_REDIRECT_POLICY;
  }
  return DEFAULT_REDIRECT_POLICY;
}

/**
 * Provenance is deliberately stored on the entry instead of inferred from its address. A source that a
 * person entered stays manual even if a later reviewed catalogue happens to contain the same URL.
 */
function normalizeProvenance(value) {
  const reviewed = value === 'reviewed' || (value && typeof value === 'object'
    && (value.kind === 'reviewed' || value.source === 'reviewed' || value.type === 'reviewed'
      || value.reviewed === true));
  const community = value === 'community' || (value && typeof value === 'object'
    && (value.kind === 'community' || value.source === 'community' || value.type === 'community'
      || value.community === true));
  const details = value && typeof value === 'object' ? value : {};
  if (community && !reviewed) {
    // Community descriptors are deliberately a third provenance class. They are neither promoted to
    // reviewed nor folded into an arbitrary manual URL, so every client can keep the consent model
    // visible after an install and a future registry update cannot silently endorse one.
    const result = { kind: 'community' };
    const directoryId = String(details.directoryId || details.communityId || '').trim().slice(0, 120);
    const revision = String(details.revision || details.communityRevision || '').trim().slice(0, 120);
    if (directoryId) result.directoryId = directoryId;
    if (revision) result.revision = revision;
    return result;
  }
  if (!reviewed) {
    return { kind: 'manual' };
  }
  const result = { kind: 'reviewed' };
  const registryId = String(details.registryId || details.reviewedId || details.id || '').trim().slice(0, 120);
  const revision = String(details.revision || details.reviewedRevision || '').trim().slice(0, 120);
  if (registryId) result.registryId = registryId;
  if (revision) result.revision = revision;
  return result;
}

function redirectPolicyFor(provenance, requested) {
  // Reviewed installs are fail-closed. The later reviewed-registry integration only needs to pass the
  // provenance object; it cannot accidentally loosen redirects for a reviewed source.
  return normalizeProvenance(provenance).kind === 'reviewed'
    ? SAME_ORIGIN_REDIRECT_POLICY : normalizeRedirectPolicy(requested);
}

function addonCacheKey(url, redirectPolicy) {
  return normalizeRedirectPolicy(redirectPolicy) + CACHE_KEY_SEPARATOR + String(url);
}

function manifestUrlFor(value) {
  const raw = String(value || '').trim();
  if (!raw) throw blockedAddonUrl('addon URL is required');
  let parsed;
  try {
    parsed = new URL(raw);
  } catch (e) {
    throw blockedAddonUrl('addon URL must be HTTP(S)');
  }
  if ((parsed.protocol !== 'http:' && parsed.protocol !== 'https:') || !parsed.hostname) {
    throw blockedAddonUrl('addon URL must be HTTP(S)');
  }
  if (parsed.username || parsed.password) {
    throw blockedAddonUrl('addon URL credentials are not allowed');
  }
  // Fragments are never sent in an HTTP request and should not create duplicate configured addresses.
  parsed.hash = '';
  if (/\/$/.test(parsed.pathname)) parsed.pathname += 'manifest.json';
  else if (!/\/manifest\.json$/i.test(parsed.pathname)) {
    parsed.pathname = parsed.pathname.replace(/\/+$/, '') + '/manifest.json';
  }
  return parsed;
}

function httpUrlFor(value) {
  let parsed;
  try {
    parsed = value instanceof URL ? new URL(value.toString()) : new URL(String(value));
  } catch (e) {
    throw blockedAddonUrl('addon URL must be HTTP(S)');
  }
  if ((parsed.protocol !== 'http:' && parsed.protocol !== 'https:') || !parsed.hostname) {
    throw blockedAddonUrl('addon URL must be HTTP(S)');
  }
  if (parsed.username || parsed.password) {
    throw blockedAddonUrl('addon URL credentials are not allowed');
  }
  parsed.hash = '';
  return parsed;
}

function ipv4Octets(address) {
  const parts = String(address || '').split('.');
  if (parts.length !== 4) return null;
  const octets = parts.map((part) => Number(part));
  return octets.every((part) => Number.isInteger(part) && part >= 0 && part <= 255) ? octets : null;
}

function isPublicIpv4(address) {
  const octets = ipv4Octets(address);
  if (!octets) return false;
  const [a, b] = octets;
  // RFC 1918, loopback, link-local, CGNAT, documentation/testing and every non-unicast range are not
  // remote add-on endpoints. Blocking documentation ranges also keeps test/development addresses from
  // becoming a surprising route to a private network.
  if (a === 0 || a === 10 || a === 127 || a >= 224) return false;
  if (a === 100 && b >= 64 && b <= 127) return false;
  if (a === 169 && b === 254) return false;
  if (a === 172 && b >= 16 && b <= 31) return false;
  if (a === 192 && (b === 0 || b === 2 || b === 168)) return false;
  if (a === 198 && (b === 18 || b === 19 || b === 51)) return false;
  if (a === 203 && b === 0) return false;
  return true;
}

function expandIpv6(address) {
  let value = String(address || '').toLowerCase();
  const zone = value.indexOf('%');
  if (zone >= 0) value = value.slice(0, zone);
  if (net.isIP(value) !== 6) return null;
  if (value.includes('.')) {
    const pivot = value.lastIndexOf(':');
    const octets = ipv4Octets(value.slice(pivot + 1));
    if (!octets) return null;
    value = value.slice(0, pivot) + ':' + ((octets[0] << 8) | octets[1]).toString(16)
      + ':' + ((octets[2] << 8) | octets[3]).toString(16);
  }
  const halves = value.split('::');
  if (halves.length > 2) return null;
  const left = halves[0] ? halves[0].split(':') : [];
  const right = halves.length === 2 && halves[1] ? halves[1].split(':') : [];
  const missing = 8 - left.length - right.length;
  if (missing < 0 || (halves.length === 1 && missing !== 0)) return null;
  const groups = left.concat(Array(missing).fill('0'), right).map((part) => Number.parseInt(part, 16));
  return groups.length === 8 && groups.every((part) => Number.isInteger(part) && part >= 0 && part <= 0xffff)
    ? groups : null;
}

function embeddedIpv4(groups) {
  if (!groups || groups.length !== 8) return null;
  // IPv4-compatible, IPv4-mapped and IPv4-translated forms can otherwise smuggle a loopback/private
  // v4 address. The translated prefix is less common but is still meaningful to dual-stack stacks.
  const compatible = groups.slice(0, 6).every((part) => part === 0);
  const mapped = groups.slice(0, 5).every((part) => part === 0) && groups[5] === 0xffff;
  const translated = groups.slice(0, 4).every((part) => part === 0)
    && groups[4] === 0xffff && groups[5] === 0;
  if (!compatible && !mapped && !translated) return null;
  return [groups[6] >>> 8, groups[6] & 0xff, groups[7] >>> 8, groups[7] & 0xff].join('.');
}

function isPublicIpv6(address) {
  const groups = expandIpv6(address);
  if (!groups) return false;
  const v4 = embeddedIpv4(groups);
  if (v4) return isPublicIpv4(v4);
  const first = groups[0];
  if (groups.every((part) => part === 0)) return false; // ::
  if (groups.slice(0, 7).every((part) => part === 0) && groups[7] === 1) return false; // ::1
  if ((first & 0xfe00) === 0xfc00) return false; // unique local fc00::/7
  if ((first & 0xffc0) === 0xfe80 || (first & 0xffc0) === 0xfec0) return false; // link/site local
  if ((first & 0xff00) === 0xff00) return false; // multicast
  if (first === 0x2001 && (groups[1] === 0 || groups[1] === 0x0002 || groups[1] === 0x0db8)) return false;
  if (first === 0x2002) return false; // 6to4 can tunnel an embedded private v4 endpoint
  // The well-known NAT64 prefix embeds a v4 destination. Treat an embedded private address exactly as
  // its direct v4 form rather than trusting the publicly-routable-looking IPv6 prefix.
  if (first === 0x0064 && groups[1] === 0xff9b && groups.slice(2, 6).every((part) => part === 0)) {
    const mapped = [groups[6] >>> 8, groups[6] & 0xff, groups[7] >>> 8, groups[7] & 0xff].join('.');
    if (!isPublicIpv4(mapped)) return false;
  }
  return true;
}

function isPublicAddress(address) {
  const ip = String(address || '').replace(/^\[|\]$/g, '');
  const family = net.isIP(ip);
  return family === 4 ? isPublicIpv4(ip) : family === 6 ? isPublicIpv6(ip) : false;
}

function withinTimeout(promise, timeoutMs, message) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(message)), timeoutMs);
    if (timer.unref) timer.unref();
    Promise.resolve(promise).then(
      (value) => { clearTimeout(timer); resolve(value); },
      (error) => { clearTimeout(timer); reject(error); },
    );
  });
}

async function resolvePublicAddresses(value, timeoutMs = FETCH_TIMEOUT_MS) {
  const parsed = httpUrlFor(value);
  const host = parsed.hostname.replace(/^\[|\]$/g, '').replace(/\.$/, '').toLowerCase();
  if (!host || host === 'localhost' || /\.(localhost|local|internal|home|lan)$/.test(host)) {
    throw blockedAddonUrl('addon host is not public');
  }
  if (net.isIP(host)) {
    if (!isPublicAddress(host)) throw blockedAddonUrl('addon host is not public');
    return [{ address: host, family: net.isIP(host) }];
  }
  let answers;
  try {
    answers = await withinTimeout(dns.promises.lookup(host, { all: true, verbatim: true }),
      boundedInteger(timeoutMs, FETCH_TIMEOUT_MS, 1, 30000), 'addon DNS lookup timed out');
  } catch (e) {
    throw blockedAddonUrl('addon host did not resolve to a public address');
  }
  if (!Array.isArray(answers) || !answers.length || answers.some((answer) => !isPublicAddress(answer.address))) {
    throw blockedAddonUrl('addon host did not resolve to a public address');
  }
  return answers.map((answer) => ({ address: answer.address, family: answer.family }));
}

function pinnedLookup(addresses) {
  return (_host, options, callback) => {
    let lookupOptions = options;
    let done = callback;
    if (typeof options === 'function') {
      done = options;
      lookupOptions = {};
    }
    const family = Number((lookupOptions || {}).family) || 0;
    const candidates = family ? addresses.filter((entry) => entry.family === family) : addresses;
    if (!candidates.length) return done(blockedAddonUrl('addon host has no usable public address'));
    if (lookupOptions && lookupOptions.all) return done(null, candidates.map((entry) => ({ ...entry })));
    return done(null, candidates[0].address, candidates[0].family);
  };
}

function redirectAllowed(initialUrl, redirectedUrl, redirectPolicy) {
  if (normalizeRedirectPolicy(redirectPolicy) !== SAME_ORIGIN_REDIRECT_POLICY) return true;
  try {
    return httpUrlFor(initialUrl).origin === httpUrlFor(redirectedUrl).origin;
  } catch (e) {
    return false;
  }
}

function publicEndpoint(value) {
  try {
    const parsed = new URL(String(value));
    return parsed.protocol + '//' + parsed.host;
  } catch (e) {
    return 'remote addon';
  }
}

function publicAddonError(value) {
  if (!value) return null;
  const message = String(value);
  if (/tim(?:e)?d out/i.test(message)) return 'remote addon timed out';
  if (/size limit|too large/i.test(message)) return 'remote addon response was too large';
  if (/redirect/i.test(message)) return 'remote addon redirect was blocked';
  if (/HTTP\s+\d{3}/i.test(message)) return 'remote addon returned an error';
  if (/URL|address|resolve|lookup|credentials|ENOTFOUND|ECONN|EAI_/i.test(message)) return 'remote addon unavailable';
  // Do not turn an upstream error into a configuration leak. Internal logs retain the operational detail.
  return 'remote addon unavailable';
}

function publicLabel(value, fallback = 'External add-on') {
  const text = String(value || '').trim();
  if (!text || /\b(?:https?|magnet):/i.test(text)
    || /\b(?:token|secret|password|api[_-]?key|config)=/i.test(text)) return fallback;
  return text.slice(0, 120);
}

const PUBLIC_PRIVATE_KEYS = new Set([
  'url', 'manifesturl', 'config', 'configuration', 'headers', 'proxyheaders', 'authorization',
  'cookie', 'cookies', 'token', 'secret', 'password', 'apikey', 'api_key',
  // Native-provider diagnostics often carry absolute roots, cache files, or executable locations.
  // The add-on list is a management surface, not a way to map the Hub's filesystem.
  'file', 'path', 'root', 'roots', 'rootdir', 'rootdirs', 'bin', 'binary', 'executable',
]);

function publicStats(value, key = '') {
  if (PUBLIC_PRIVATE_KEYS.has(String(key).toLowerCase())) return undefined;
  if (Array.isArray(value)) return value.map((entry) => publicStats(entry)).filter((entry) => entry !== undefined);
  if (value && typeof value === 'object') {
    const out = {};
    for (const [childKey, childValue] of Object.entries(value)) {
      const safe = publicStats(childValue, childKey);
      if (safe !== undefined) out[childKey] = safe;
    }
    return out;
  }
  if (typeof value === 'string') {
    // Statistics and errors are never a reason to return a configured endpoint or its path/query.
    if (/\b(?:https?|magnet):/i.test(value)) return '[redacted]';
    return value.slice(0, 240);
  }
  return value;
}

function publicExternalRow(addon) {
  return {
    id: addon.id,
    name: publicLabel(addon.name, addon.id || 'External add-on'),
    native: false,
    kind: addon.kind,
    priority: addon.priority,
    enabled: addon.enabled,
    addedAt: addon.addedAt || null,
    provenance: normalizeProvenance(addon.provenance).kind,
    resources: Object.keys(addon.resources || {}),
    catalogs: (addon.cats || []).map((catalog) => ({ id: catalog.id, name: catalog.name })),
    error: publicAddonError(addon.lastError),
    counts: {
      items: addon.items && Number.isFinite(addon.items.size) ? addon.items.size : 0,
      streams: addon.streamSources && Number.isFinite(addon.streamSources.size) ? addon.streamSources.size : 0,
      lastFetch: addon.lastFetch || null,
    },
  };
}

// ---- shared JSON cache: TTL in memory, per-item responses survive a restart ----------------------

const cache = new Map(); // redirect-policy + url -> { at, body }
const inflight = new Map(); // redirect-policy + url -> promise
const pending = new Map(); // identity -> how many upstream calls for that identity are in flight right now

/**
 * Is the hub still waiting on an answer for this identity from any mounted addon?
 *
 * A route that resolves an identity to nothing while these are in flight must not answer "not found":
 * the answer is in transit, not missing. This is the difference between the 404 that made a detail
 * page blink on a first tap and the "warming, again in 1.5 s" that lets the same request succeed.
 */
function pendingFor(identity) {
  return (pending.get(String(identity)) || 0) > 0;
}

/** Run `work` while the identity is marked in flight; the mark is reference-counted and self-cleared. */
function trackPending(identity, work) {
  const key = String(identity || '');
  pending.set(key, (pending.get(key) || 0) + 1);
  return Promise.resolve()
    .then(() => work())
    .finally(() => {
      const n = (pending.get(key) || 0) - 1;
      if (n <= 0) pending.delete(key);
      else pending.set(key, n);
    });
}

/**
 * Fetch JSON from an external add-on without giving it a path back into the hub's LAN. Every hostname is
 * resolved before connecting, every returned address must be public, and the validated answer is pinned
 * into the request lookup callback to close the usual DNS-rebinding gap between validation and connect.
 */
function get(url, timeoutMs = FETCH_TIMEOUT_MS, ok404 = false, options = {}) {
  let initialUrl;
  try {
    initialUrl = httpUrlFor(url);
  } catch (error) {
    return Promise.reject(error);
  }
  const timeout = boundedInteger(timeoutMs, FETCH_TIMEOUT_MS, 250, 30000);
  const deadline = Date.now() + timeout;
  options = options && typeof options === 'object' ? options : {};
  const policy = normalizeRedirectPolicy(options.redirectPolicy || options.policy);

  const fetchOne = async (currentUrl, redirects) => {
    const remainingBeforeLookup = deadline - Date.now();
    if (remainingBeforeLookup <= 0) throw new Error('addon request timed out');
    const addresses = await resolvePublicAddresses(currentUrl, remainingBeforeLookup);
    const remaining = deadline - Date.now();
    if (remaining <= 0) throw new Error('addon request timed out');

    return new Promise((resolve, reject) => {
      let settled = false;
      let req = null;
      const timer = setTimeout(() => fail(new Error('addon request timed out')), remaining);
      if (timer.unref) timer.unref();
      const finish = (callback, value) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        callback(value);
      };
      const fail = (error) => {
        if (settled) return;
        if (req) req.destroy();
        finish(reject, error);
      };

      const mod = currentUrl.protocol === 'https:' ? https : http;
      try {
        req = mod.get(currentUrl, {
          headers: { 'User-Agent': UA, Accept: 'application/json' },
          timeout: remaining,
          lookup: pinnedLookup(addresses),
        }, (res) => {
          const status = Number(res.statusCode) || 0;
          if (status >= 300 && status < 400 && res.headers.location) {
            res.resume();
            if (redirects >= MAX_REDIRECTS) return fail(new Error('addon redirect limit exceeded'));
            let redirectedUrl;
            try {
              redirectedUrl = httpUrlFor(new URL(res.headers.location, currentUrl));
            } catch (error) {
              return fail(error);
            }
            if (!redirectAllowed(initialUrl, redirectedUrl, policy)) {
              return fail(new Error('addon redirect left its reviewed origin'));
            }
            return finish(resolve, fetchOne(redirectedUrl, redirects + 1));
          }
          if (status === 404 && ok404) {
            // "I have nothing for this id" is a protocol answer, not a failure: cache it empty.
            res.resume();
            return finish(resolve, '{}');
          }
          if (status !== 200) {
            res.resume();
            return fail(new Error('addon returned HTTP ' + status));
          }
          const declaredLength = Number(res.headers['content-length']);
          if (Number.isFinite(declaredLength) && declaredLength > MAX_BODY_BYTES) {
            res.resume();
            return fail(new Error('addon response exceeded size limit'));
          }
          const chunks = [];
          let bytes = 0;
          res.on('data', (chunk) => {
            if (settled) return;
            const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
            bytes += buffer.length;
            if (bytes > MAX_BODY_BYTES) return fail(new Error('addon response exceeded size limit'));
            chunks.push(buffer);
          });
          res.on('error', fail);
          res.on('end', () => finish(resolve, Buffer.concat(chunks, bytes).toString('utf8')));
        });
        req.on('timeout', () => fail(new Error('addon request timed out')));
        req.on('error', fail);
      } catch (error) {
        fail(error);
      }
    });
  };

  return fetchOne(initialUrl, 0);
}

// ---- subtitle format sniff (a filename is a claim; the file's first bytes are the truth) -------------

const SUBTITLE_FORMATS = new Set(['srt', 'ass', 'ssa', 'vtt', 'ttml', 'dfxp', 'sub']);
const SUB_EXT_RE = /\.(srt|ass|ssa|vtt|webvtt|ttml|dfxp|sub)\b/i;

function normalizeSubtitleFormat(value) {
  const raw = String(value || '').trim().toLowerCase();
  if (!raw) return null;
  const bare = raw.replace(/^\./, '');
  if (bare === 'webvtt') return 'vtt';
  return SUBTITLE_FORMATS.has(bare) ? bare : null;
}

function subtitleFormatFromName(name) {
  const m = SUB_EXT_RE.exec(String(name || ''));
  return m ? normalizeSubtitleFormat(m[1]) : null;
}

function withSubtitleExtension(name, format) {
  const s = String(name || '');
  const want = normalizeSubtitleFormat(format);
  if (!s || !want) return s;
  const m = SUB_EXT_RE.exec(s);
  if (!m) return s; // no extension to contradict: leave the name as-is
  const current = normalizeSubtitleFormat(m[1]);
  if (current === want) return s;
  return s.slice(0, m.index) + '.' + want + s.slice(m.index + m[0].length);
}

/**
 * What the file is, from its opening bytes. Pure and offline (no network, no clock), so it can be
 * fixture-tested exactly. null means the opening is not recognisable as a subtitle at all.
 */
function detectSubtitleFormat(sample) {
  if (sample == null) return null;
  let text = Buffer.isBuffer(sample) ? sample.toString('utf8') : String(sample);
  if (text.charCodeAt(0) === 0xFEFF) text = text.slice(1);
  if (text.length > 4096) text = text.slice(0, 4096);
  const lines = text.split(/\r?\n/).map((l) => l.trim()).filter((l) => l.length > 0);
  if (!lines.length) return null;
  const first = lines[0];
  if (/^WEBVTT\b/i.test(first)) return 'vtt';
  if (first.startsWith('<?xml') || /^<tt\b/i.test(first)) return 'ttml';
  if (/^\[Script Info\]/i.test(first)) return 'ass';
  const head = lines.slice(0, 3).join('\n');
  // A timestamp arrow without a WEBVTT header and without a TTML/ASS opener is SubRip: SRT uses
  // "00:00:01,000 --> ...", VTT would have matched the header check above.
  if (/(\d{1,2}:){2}\d{1,2}[.,]\d{3}\s*-->/.test(head)) return 'srt';
  if (/^\d+\s*$/.test(first)) return 'srt';
  return null;
}

/**
 * First up-to-`maxBytes` bytes of a URL, through the same DNS-pinned transport as get(). Sends
 * `Range: bytes=0-…` but does not require 206: when a host ignores Range the first chunk is still
 * the truth, and the connection is torn down as soon as enough bytes have arrived.
 * Never rejects: every failure (blocked host, timeout, bad status, redirect policy, …) resolves null,
 * so a sniff can never break the listing it serves.
 */
function getHeadText(url, { timeoutMs = SUB_SNIFF_TIMEOUT_MS, maxBytes = SUB_SNIFF_BYTES, redirectPolicy } = {}) {
  let initialUrl;
  try {
    initialUrl = httpUrlFor(url);
  } catch (error) {
    return Promise.resolve(null);
  }
  const timeout = boundedInteger(timeoutMs, SUB_SNIFF_TIMEOUT_MS, 250, 30000);
  const deadline = Date.now() + timeout;
  const policy = normalizeRedirectPolicy(redirectPolicy);
  const cap = boundedInteger(maxBytes, SUB_SNIFF_BYTES, 64, 8192);

  const fetchOne = async (currentUrl, redirects) => {
    const remainingBeforeLookup = deadline - Date.now();
    if (remainingBeforeLookup <= 0) return null;
    let addresses;
    try {
      addresses = await resolvePublicAddresses(currentUrl, remainingBeforeLookup);
    } catch (error) {
      return null; // private / unresolvable / blocked: unknown, not an error for the listing
    }
    const remaining = deadline - Date.now();
    if (remaining <= 0) return null;

    return new Promise((resolve) => {
      let settled = false;
      let req = null;
      const timer = setTimeout(() => finish(null), remaining);
      if (timer.unref) timer.unref();
      const finish = (value) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        if (req) {
          try { req.destroy(); } catch (e) { /* destroying is best-effort */ }
        }
        resolve(value);
      };

      const mod = currentUrl.protocol === 'https:' ? https : http;
      try {
        req = mod.get(currentUrl, {
          headers: {
            'User-Agent': UA,
            Accept: 'text/plain, text/vtt, application/x-subrip, */*',
            Range: 'bytes=0-' + (cap - 1),
          },
          timeout: remaining,
          lookup: pinnedLookup(addresses),
        }, (res) => {
          const status = Number(res.statusCode) || 0;
          if (status >= 300 && status < 400 && res.headers.location) {
            try { res.resume(); } catch (e) { /* best-effort */ }
            if (redirects >= MAX_REDIRECTS) return finish(null);
            let redirectedUrl;
            try {
              redirectedUrl = httpUrlFor(new URL(res.headers.location, currentUrl));
            } catch (error) {
              return finish(null);
            }
            if (!redirectAllowed(initialUrl, redirectedUrl, policy)) return finish(null);
            return finish(fetchOne(redirectedUrl, redirects + 1));
          }
          if (status !== 200 && status !== 206) {
            try { res.resume(); } catch (e) { /* best-effort */ }
            return finish(null);
          }
          const chunks = [];
          let bytes = 0;
          res.on('data', (chunk) => {
            if (settled) return;
            const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
            bytes += buffer.length;
            chunks.push(buffer);
            if (bytes >= cap) {
              const text = Buffer.concat(chunks, bytes).toString('utf8', 0, Math.min(bytes, cap + 1024));
              try { res.resume(); } catch (e) { /* best-effort */ }
              return finish(text.slice(0, cap * 4));
            }
          });
          res.on('error', () => finish(null));
          res.on('end', () => finish(Buffer.concat(chunks, bytes).toString('utf8')));
        });
        req.on('timeout', () => finish(null));
        req.on('error', () => finish(null));
      } catch (error) {
        finish(null);
      }
    });
  };

  return fetchOne(initialUrl, 0);
}

const subFormatCache = new Map(); // policy + url -> { format, at }

function subCacheKey(url, policy) {
  return normalizeRedirectPolicy(policy) + '\u0000' + String(url || '');
}

function clearSubFormatCache() {
  subFormatCache.clear();
}

function cachedSubtitleFormat(url, policy) {
  const key = subCacheKey(url, policy);
  const hit = subFormatCache.get(key);
  if (!hit) return undefined;
  const ttl = hit.format ? SUB_SNIFF_TTL_MS : SUB_SNIFF_UNKNOWN_TTL_MS;
  if (Date.now() - hit.at >= ttl) {
    subFormatCache.delete(key);
    return undefined;
  }
  return hit.format;
}

function rememberSubtitleFormat(url, policy, format) {
  try {
    subFormatCache.set(subCacheKey(url, policy), { format: format || null, at: Date.now() });
    if (subFormatCache.size > SUB_SNIFF_CACHE_MAX) {
      const oldest = subFormatCache.keys().next().value;
      if (oldest !== undefined) subFormatCache.delete(oldest);
    }
  } catch (e) { /* the cache is an optimisation, never a failure mode */ }
}

/** Sniffed truth for one URL, or null. Cached, bounded, and never throws. */
async function sniffSubtitleFormat(url, { redirectPolicy, timeoutMs, maxBytes } = {}) {
  const text = String(url || '');
  if (!/^https?:\/\//i.test(text)) return null;
  const policy = normalizeRedirectPolicy(redirectPolicy);
  const cached = cachedSubtitleFormat(text, policy);
  if (cached !== undefined) return cached;
  let format = null;
  try {
    const head = await getHeadText(text, { timeoutMs, maxBytes, redirectPolicy: policy });
    format = head ? detectSubtitleFormat(head) : null;
  } catch (e) {
    format = null;
  }
  rememberSubtitleFormat(text, policy, format);
  return format;
}

/**
 * Sniff many URLs at once, capped concurrency. When the batch budget runs out mid-flight the
 * remaining entries simply stay null (the caller falls back to the filename extension): started
 * probes still finish in the background and populate the cache for the next request.
 */
function sniffSubtitleFormats(urls, { redirectPolicy, budgetMs, timeoutMs } = {}) {
  const list = (Array.isArray(urls) ? urls : []).map((u) => String(u || ''));
  if (!list.length) return Promise.resolve([]);
  const policy = normalizeRedirectPolicy(redirectPolicy);
  const budget = boundedInteger(budgetMs, SUB_SNIFF_BUDGET_MS, 250, 30000);
  const results = new Array(list.length).fill(null);
  const probe = (async () => {
    const byUrl = new Map();
    list.forEach((u, i) => {
      if (!byUrl.has(u)) byUrl.set(u, []);
      byUrl.get(u).push(i);
    });
    const unique = [...byUrl.keys()];
    for (let at = 0; at < unique.length; at += SUB_SNIFF_CONCURRENCY) {
      const wave = unique.slice(at, at + SUB_SNIFF_CONCURRENCY);
      await Promise.allSettled(wave.map(async (u) => {
        let format = null;
        try {
          format = await sniffSubtitleFormat(u, { redirectPolicy: policy, timeoutMs });
        } catch (e) {
          format = null;
        }
        for (const i of byUrl.get(u) || []) results[i] = format;
      }));
    }
    return results;
  })();
  if (!Number.isFinite(budget) || budget <= 0) return probe.catch(() => results);
  const timer = new Promise((res) => {
    const t = setTimeout(() => res(results.slice()), budget);
    if (t.unref) t.unref();
  });
  return Promise.race([probe.catch(() => results), timer]);
}

/**
 * Cache-first JSON GET.
 *  wait     - block on the network for this caller (a cold detail page may take one round trip)
 *  staleOk  - an expired copy answers now, the refresh happens in the background
 * Without either, a miss kicks the fetch and answers the stale copy or null: home never waits.
 */
/**
 * How long a cached answer is worth serving. An answer with nothing in it — no streams, no items, no
 * meta — is a shrug, and a shrug is re-asked within minutes rather than obeyed for six hours.
 */
function answerIsShrug(body) {
  if (!body || typeof body !== 'object') return true;
  if (Array.isArray(body)) return body.length === 0;
  for (const k of ['streams', 'items', 'sources']) {
    if (Array.isArray(body[k])) return body[k].length === 0;
  }
  if ('meta' in body) return !body.meta;
  return Object.keys(body).length === 0;
}

function ttlFor(body) {
  return answerIsShrug(body) ? Math.min(TTL_MS, EMPTY_TTL_MS) : TTL_MS;
}

/**
 * Drop the cached stream answers for one identity. The play ladder calls this when the copies it was
 * told about had no address behind them: the answer is thrown away rather than re-asked for, so the
 * next press goes to the addons instead of reading the same empty reply back for hours.
 */
function forgetStreams(identity) {
  const raw = String(identity || '');
  const bare = raw.replace(/^[a-z]+:/i, '');
  const show = bare.replace(/:\d+(:\d+)?$/, '');
  const ids = [...new Set([raw, bare, show].filter((s) => s && s.length >= 5))];
  if (!ids.length) return 0;
  let n = 0;
  for (const key of cache.keys()) {
    if (!/\/stream\//.test(key)) continue;
    if (!ids.some((id) => key.includes(id))) continue;
    cache.delete(key);
    n++;
  }
  if (n) log('ADDON CACHE FORGOT', raw, n, 'stream answers');
  scheduleCacheSave();
  return n;
}

/**
 * Public aggregators (the desitvhub-style manifest hosts, torrentio) answer HTTP 429 when a hub
 * calls them too often, and a 429 is the one failure retrying makes WORSE: the hub used to hammer
 * through its whole warm-up and every keypress, and the log carried a thousand refusals a day while
 * every title that lives behind that aggregator looked broken to the family. A refusal now buys the
 * host silence: live calls to it pause for a few minutes and stale cache answers for it instead;
 * a host that keeps refusing waits longer. When the window passes, one call probes it again.
 */
const rateLimited = new Map(); // host -> until (ms). Set on 429; probed again after.
function rateLimitedHost(url) {
  let h = '';
  try { h = new URL(String(url)).host; } catch (e) { return ''; }
  const until = rateLimited.get(h);
  if (until == null) return '';
  if (until <= Date.now()) { rateLimited.delete(h); return ''; }
  return h;
}
function noteRateLimited(url) {
  let h = '';
  try { h = new URL(String(url)).host; } catch (e) { return; }
  const was = rateLimited.get(h);
  const calm = was && was > Date.now() ? 10 * 60 * 1000 : 3 * 60 * 1000;
  rateLimited.set(h, Date.now() + calm);
  if (!was || was <= Date.now()) log('ADDON RATE LIMITED', h, 'refused with 429 - waiting ' + Math.round(calm / 60000) + ' min before asking again');
}

/*
 * The sibling rule for hosts that do not answer at all: connection refused, a dead catalog serving
 * HTML where JSON was promised, a TLS handshake that never completes. Without this, every keypress
 * re-pays the full fetch timeout for each broken source — measured as most of the play path's wait
 * when two aggregators in the household list were gone. A host that fails once goes quiet for a
 * minute (answers already in cache are still served from it); after that one call probes it again.
 */
const FAIL_COOLDOWN_MS = Number(process.env.ADDON_FAIL_COOLDOWN_MS || 60 * 1000);
const failing = new Map(); // host -> until (ms). Set on fetch/parse failure; probed again after.
function failingHost(url) {
  let h = '';
  try { h = new URL(String(url)).host; } catch (e) { return ''; }
  const until = failing.get(h);
  if (until == null) return '';
  if (until <= Date.now()) { failing.delete(h); return ''; }
  return h;
}
function noteFailing(url) {
  let h = '';
  try { h = new URL(String(url)).host; } catch (e) { return; }
  const was = failing.get(h);
  const calm = was && was > Date.now() ? Math.min(5 * 60 * 1000, (was - Date.now()) + FAIL_COOLDOWN_MS) : FAIL_COOLDOWN_MS;
  failing.set(h, Date.now() + calm);
  if (!was || was <= Date.now()) log('ADDON HOST DOWN', h, 'not answering - waiting ' + Math.round(calm / 1000) + 's before asking again');
}

function getJson(url, {
  wait = false, staleOk = false, timeoutMs = FETCH_TIMEOUT_MS, tag = '', ok404 = false,
  redirectPolicy = DEFAULT_REDIRECT_POLICY, policy: requestedPolicy,
} = {}) {
  const policy = normalizeRedirectPolicy(requestedPolicy || redirectPolicy);
  const key = addonCacheKey(url, policy);
  const hit = cache.get(key);
  if (hit && Date.now() - hit.at < ttlFor(hit.body)) return Promise.resolve(hit.body);
  // Rate-limit by endpoint host, but retain redirect-policy cache partitioning. A reviewed source can
  // therefore never consume a response fetched through the public redirect policy, even while a host
  // is cooling down after a 429. A host already in its failure cooldown gets no new fetch either — only
  // keys with nothing cached fall silent, since cached answers above still age normally.
  if (!inflight.has(key) && !rateLimitedHost(url) && !(hit == null && failingHost(url))) {
    const p = get(url, timeoutMs, ok404, { redirectPolicy: policy })
      .then((body) => {
        const parsed = JSON.parse(body);
        cache.set(key, { at: Date.now(), body: parsed });
        scheduleCacheSave();
        let host = ''; try { host = new URL(String(url)).host; } catch (e) {}
        if (host) failing.delete(host); // the probe answered; the whole host is back
        return parsed;
      })
      .catch((e) => {
        // A connect failure often arrives as an AggregateError with no message at all; a blank log line
        // is exactly how one of these hunts went blind, so name the failure by whatever it does carry.
        const msg = e.message || e.code || e.name || String(e);
        if (tag) log('ADDON UPSTREAM FAIL', tag, publicEndpoint(url), msg);
        if (/HTTP 429/.test(msg)) noteRateLimited(url); else noteFailing(url);
        return hit ? hit.body : null;
      })
      .finally(() => inflight.delete(key));
    inflight.set(key, p);
  }
  if (staleOk && hit) return Promise.resolve(hit.body);
  return wait ? (inflight.get(key) || Promise.resolve(hit ? hit.body : null)) : Promise.resolve(hit ? hit.body : null);
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
        // Caches written before redirect-policy isolation used the raw URL key. Treat those as the
        // default public policy only; a reviewed add-on must never consume a response cached through a
        // less restrictive redirect chain.
        const key = url.includes(CACHE_KEY_SEPARATOR) ? url : addonCacheKey(url, DEFAULT_REDIRECT_POLICY);
        cache.set(key, entry);
        n++;
      }
    }
    if (n) log('ADDON cache restored', n, 'entries from', CACHE_FILE);
  } catch (e) {
    if (e.code !== 'ENOENT') log('ADDON cache restore failed', e.message);
  }
}
loadPersistedCache();

/*
 * The per-title cache is tens of megabytes by the end of a viewing week. Writing it used to mean one
 * synchronous JSON.stringify plus writeFileSync a half-second after EVERY new answer — which froze the
 * event loop for seconds on the household box while that evening's play and home requests were in
 * flight. The file is a restart convenience, not a live object: answers live in memory, so the disk
 * copy only has to be durable eventually. It is now written at most once every SAVE_MIN_MS, never
 * while another write is still running, and in chunks handed back to the event loop between writes,
 * so a save can never make a request wait on it.
 */
const SAVE_MIN_MS = Number(process.env.ADDON_CACHE_SAVE_MIN_MS || 30 * 1000);
let saveTimer = null;
let saveDirty = false;
let saveRunning = false;
let lastSavedAt = 0;

function scheduleCacheSave() {
  saveDirty = true;
  if (saveTimer) return;
  const wait = Math.max(500, SAVE_MIN_MS - (Date.now() - lastSavedAt));
  saveTimer = setTimeout(() => { saveTimer = null; persistCache(); }, wait);
  if (saveTimer.unref) saveTimer.unref();
}

function fsWrite(fd, chunk) {
  return new Promise((resolve, reject) => fs.write(fd, chunk, (e, n) => e ? reject(e) : resolve(n)));
}
function yieldLoop() { return new Promise((resolve) => setImmediate(resolve)); }

async function persistCache() {
  if (saveRunning || !saveDirty) return;
  saveRunning = true;
  saveDirty = false;
  try {
    fs.mkdirSync(path.dirname(CACHE_FILE), { recursive: true });
    const tmp = CACHE_FILE + '.tmp';
    const fd = await new Promise((resolve, reject) =>
      fs.open(tmp, 'w', (e, f) => e ? reject(e) : resolve(f)));
    try {
      let buf = '{';
      let first = true;
      for (const [url, entry] of cache) {
        // Catalog bodies are re-fetched at boot anyway; per-title responses are what make a restart warm.
        if (!/\/(meta|stream|subtitles)\//.test(url)) continue;
        buf += (first ? '' : ',') + JSON.stringify(url) + ':' + JSON.stringify(entry);
        first = false;
        if (buf.length > 512 * 1024) { await fsWrite(fd, buf); buf = ''; await yieldLoop(); }
      }
      await fsWrite(fd, buf + '}');
    } finally {
      await new Promise((resolve) => fs.close(fd, resolve));
    }
    fs.renameSync(tmp, CACHE_FILE);
    lastSavedAt = Date.now();
  } catch (e) {
    // losing the cache write costs a round trip later, nothing else
  } finally {
    saveRunning = false;
    if (saveDirty) scheduleCacheSave();
  }
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

/**
 * Language words a release name states: "MULTi", "[Tamil + Telugu + Hindi + Eng]", "Dual Audio".
 * Built from the table langCodes reads, so a language added there is recognised in names too. Read with
 * String.match, which ignores `lastIndex` on a /g pattern, so this constant is safe to share.
 */
const LANGUAGE_WORDS = new RegExp('\\b(' + Object.keys(LANG_NAME_TO_CODE).join('|') + ')\\b', 'gi');


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

/**
 * An addon that proxies through itself hides the file inside its own query string:
 * `…/api/stream?url=https%3A%2F%2Fhost%2Ffilm.m3u8&headers=…`. The path ends in nothing, but the
 * thing it fetches for you is a media file — verified against DesiFlix: that endpoint answers a real
 * 206 m3u8 with the headers baked in, while its raw host links 404. Classified as a page, the picker
 * refuses the only working copy the addon had (`a service page, not a stream`).
 */
const MEDIA_IN_QUERY = /[?&](?:url|src|link|file|u|v)=(https?(?:%3A%2F%2F|:\/\/)[^&]*\.(?:mp4|mkv|webm|m4v|mov|avi|ts|m2ts|flv|wmv|m3u8|mpd)(?:%3F|%23|\?|#|&|%26|$))/i;

/**
 * Announce URLs for magnets the add-ons hand over as a bare info hash (Torrentio sends no tracker
 * list at all). The engine can resolve those over the DHT alone — a fresh engine did it in 13 s here
 * — but it also fetches its own tracker list at boot, and that fetch failed on this network before
 * falling back. When both the list and the DHT are thin, the file list takes the forty to ninety
 * seconds that a Play press cannot wait, so name a few long-lived public trackers instead of hoping.
 * Stremio's torrent engine appends an announce list for the same reason. The swarm is still the swarm.
 */
const PUBLIC_TRACKERS = [
  'udp://tracker.opentrackr.org:1337/announce',
  'udp://open.stealth.si:80/announce',
  'udp://tracker.torrent.eu.org:451/announce',
  'udp://exodus.desync.com:6969/announce',
  'udp://open.demonii.com:1337/announce',
  'https://tracker.nanoha.org:443/announce',
];

/** Trackers the add-on named (`sources: ["tracker:udp://…"]`) plus the public list, deduped. */
function trackersFor(entry) {
  const out = [];
  const add = (u) => {
    const s = String(u || '').trim();
    if (s && !out.includes(s)) out.push(s);
  };
  for (const src of (Array.isArray(entry.sources) ? entry.sources : [])) {
    const m = /^tracker:(.+)$/i.exec(String(src).trim());
    if (m) add(m[1]);
  }
  for (const t of PUBLIC_TRACKERS) add(t);
  return out;
}

/** Add announce URLs a magnet does not already name, leaving everything else untouched. */
function withTrackers(magnet, trackers) {
  if (!magnet) return magnet;
  let out = magnet;
  for (const t of trackers) {
    if (out.includes('tr=' + encodeURIComponent(t)) || out.includes('tr=' + t)) continue;
    out += (out.includes('?') ? '&' : '?') + 'tr=' + encodeURIComponent(t);
  }
  return out;
}

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
    // "💾 1.22 GB" is the file size. The same line also states audio bitrates — "(DD+5.1 - 640Kbps)" —
    // and reading one of those as the size files the copy as 640 KB, which is under the hub's
    // minimum-size floor, so the copy was dropped from the list without a trace. That is how two of the
    // best-seeded Hindi copies of a title disappeared while its English remuxes stayed.
    const sm = /([\d.]+)\s*(GB|MB|KB)(?!p)/i.exec(desc + ' ' + tag);
    if (sm) sizeBytes = Math.round(parseFloat(sm[1]) * { GB: 1e9, MB: 1e6, KB: 1e3 }[sm[2].toUpperCase()]);
  }
  const seeds = /👤\s*([\d.,]+)/.exec(desc);
  const seeders = seeds ? Number(seeds[1].replace(/[,.]/g, '')) : null;

  const named = (typeof entry.externalUrl === 'string' && entry.externalUrl.startsWith('magnet:'))
    ? entry.externalUrl : null;
  // A bare info hash still becomes a magnet, with announce URLs attached, so the engine has
  // somewhere to ask besides the DHT (see PUBLIC_TRACKERS).
  const magnet = withTrackers(
    named || (entry.infoHash
      ? 'magnet:?xt=urn:btih:' + entry.infoHash
        + (hints.filename ? '&dn=' + encodeURIComponent(hints.filename) : '')
      : null),
    trackersFor(entry));
  // Some addons spell a plain stream `url`, some `externalUrl`, some `yt`.
  const direct = entry.url || entry.externalUrl || entry.yt || null;
  const torrent = Boolean(entry.infoHash) || Boolean(magnet) || /magnet:/.test(String(direct || ''));
  // A "stream" that is really a link to a service page (the subscription/rent entries a service
  // catalog answers with) is not a file a TV can open. The protocol spells that `externalUrl` with no
  // `url`; some addons put the same landing page straight in `url`. Either way it stays in the list -
  // the app can offer "watch it on <service>" - but it is marked, so the ranking rule keeps it below a
  // real file and play() never hands the TV a web page.
  const looksLikeMedia = MEDIA_EXT.test(String(direct || '')) || MEDIA_IN_QUERY.test(String(direct || ''));
  const link = !torrent && Boolean(direct) && (!looksLikeMedia || (!entry.url && Boolean(entry.externalUrl)));

  const firstLine = (nameLines[0] || '').trim();
  const label = link
    ? (firstLine || 'watch on ' + (direct ? String(direct).split('/')[2] : 'service')).slice(0, 60)
    : ([quality || (torrent ? 'torrent' : 'stream'), humanSize(sizeBytes)].filter(Boolean).join(' · ')
      || firstLine.slice(0, 60) || 'stream');

  // Audio language is rarely stated in the stream name; when it is, the ranking rule can use it. A
  // multi-language release names the whole set ("[Tamil + Telugu + Hindi + Eng]"), and the copy that
  // states the household's language is the one the audio donor search looks for — so take every language
  // the name states, not just the first word of it. Reading one match off that string called a
  // Tamil+Telugu+Hindi+English copy "Tamil" and hid the Hindi in it.
  const stated = String(desc + ' ' + tag).match(LANGUAGE_WORDS) || [];
  const langs = langCodes(entry.lang || entry.language || stated);

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
  return keepLanguageCopies(out, sources, MAX_SOURCES);
}

/**
 * A copy whose name states one of this hub's languages keeps a slot of its own.
 *
 * The band spread chooses by sharpness and swarm health, which is what the picture picker needs. The
 * audio donor search reads this same trimmed list, and a Hindi dub of a film is normally a seed-poor
 * copy sitting under that film's English remuxes: on a 212-row title the 24 slots held none of the 11
 * Hindi-named copies, so play() had no donor to mix and only said "audio: English stated". Paying for a
 * language row with the weakest row that states none of this hub's languages costs a house nothing in
 * picture choices and is the difference between a mix and a subtitle.
 */
const LANGUAGE_SLOTS = 2; // copies reserved per configured language, best-seeded first

function keepLanguageCopies(out, sources, MAX_SOURCES) {
  const wanted = langCodes(settings.get().languages) || [];
  if (!wanted.length) return out;
  const budget = Math.max(1, Math.floor(MAX_SOURCES / 4)); // language rows never own the whole list
  const names = (s) => (Array.isArray(s._langs) ? s._langs : []).filter((l) => wanted.includes(l));
  const health = (s) => (Number.isFinite(s.seeders) ? s.seeders : 0);
  const covers = (list, lang) => list.filter((s) => names(s).includes(lang)).length;
  /**
   * The copy to hand over in exchange for a reserved one, in order of cost: one that states no language
   * this hub serves, and only when every copy states one, a copy whose languages stay stated by another
   * copy in the list. Reserving Hindi and then paying for English with the Hindi copy just reserved is
   * how a rule like this cancels itself, so a copy already doing language work is never the payment.
   */
  const payFor = () => {
    const weakest = (allowed) => {
      let idx = -1;
      let low = Infinity;
      for (let i = 0; i < out.length; i++) {
        if (!allowed(out[i])) continue;
        if (health(out[i]) < low) {
          low = health(out[i]);
          idx = i;
        }
      }
      return idx;
    };
    // Band spread exists so the picker can fall back a quality level; paying a language slot with the
    // only copy of a band would undo that on purpose.
    const spareBand = (s) => out.some((x) => x !== s && x.quality === s.quality);
    const silent = weakest((s) => !names(s).length && spareBand(s));
    if (silent >= 0) return silent;
    const loose = weakest((s) => !names(s).length);
    if (loose >= 0) return loose;
    return weakest((s) => spareBand(s)
      && !names(s).some((l) => covers(out.filter((x) => x !== s), l) === 0));
  };
  // The language with the fewest copies in the list is asked first: a hub that already has three Hindi
  // copies spends nothing, and a hub that has none spends what it must.
  const order = wanted.slice().sort((a, b) => covers(out, a) - covers(out, b));
  let spent = 0;
  for (const lang of order) {
    if (spent >= budget || covers(out, lang) >= LANGUAGE_SLOTS) continue;
    const fresh = sources.filter((s) => !out.includes(s) && names(s).includes(lang))
      .sort((a, b) => health(b) - health(a));
    for (const cand of fresh) {
      if (spent >= budget || covers(out, lang) >= LANGUAGE_SLOTS) break;
      if (out.length < MAX_SOURCES) {
        out.push(cand);
        spent++;
        continue;
      }
      // A seed-poor dub is paid for with a healthy copy on purpose: that is what the mix needs, and one
      // row out of a band's share is what the picture pays.
      const payIdx = payFor();
      if (payIdx < 0) break; // every copy here is doing language work already; spend nothing
      out[payIdx] = cand;
      spent++;
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

/**
 * The hub identity for one upstream video id from an episode list. IMDb-triple ids (`tt123:1:4`) keep
 * the shared `imdb:` namespace so dedupe and cross-addon sources keep working; anything else belongs
 * to the addon that published it.
 */
function episodeAddress(addonId, remoteId) {
  const rid = String(remoteId || '').trim();
  const aid = String(addonId || '');
  if (!rid || !aid) return null;
  if (/^tt\d{5,}(:\d+:\d+)?$/.test(rid)) return 'imdb:' + rid;
  return aid + ':' + rid;
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
    this.provenance = normalizeProvenance(entry.provenance);
    this.redirectPolicy = redirectPolicyFor(this.provenance, entry.redirectPolicy);

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

  fetchJson(url, options) {
    return getJson(url, { ...(options || {}), redirectPolicy: this.redirectPolicy });
  }

  cacheEntry(url) {
    return cache.get(addonCacheKey(url, this.redirectPolicy));
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
    const body = await this.fetchJson(this.manifestUrl,
      { wait, staleOk: true, timeoutMs: MANIFEST_WAIT_MS, tag: this.id });
    if (!body || typeof body !== 'object') {
      this.lastError = 'manifest unavailable';
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
    return this.url + '/stream/' + this.typeFor(identity) + '/' + remoteIdFor(identity, this.id) + '.json';
  }

  metaPath(identity, type) {
    return this.url + '/meta/' + (type || this.typeFor(identity)) + '/'
      + remoteIdFor(identity, this.id) + '.json';
  }

  /**
   * The upstream type for one identity. The remembered map keys what meta was fetched for — the show —
   * while streams are asked for `<show>:<season>:<episode>`. A triple only ever exists for series, so
   * an unremembered triple must not fall back to `movie`: asking `/stream/movie/tt…:1:1.json` answers
   * empty, and per-episode copies (including dubbed ones) vanish from the tile.
   */
  typeFor(identity) {
    const s = String(identity || '');
    return this.types.get(s) || this.types.get(s.replace(/:\d+:\d+$/, ''))
      || (/:\d+:\d+$/.test(s) ? 'series' : 'movie');
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
    const body = await this.fetchJson(url, { wait, staleOk: true, tag: this.id });
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
      // The addon's rating is a fact about the title; `match` is the viewer's own taste, filled in
      // per profile by core/taste.js. Keeping them apart stops an IMDb score being printed as "% match".
      rating: meta.imdbRating ? { value: Number(meta.imdbRating), source: 'IMDb' } : null,
      match: null,
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
            // Not every addon addresses an episode as `<show>:<season>:<episode>`. DesiFlix hands each
            // video its own id (`dsx:e:<base64>`) and answers streams only for that; the community
            // protocol uses the imdb triple. Keep the upstream's own address so Play can ask the
            // question this addon actually understands — throwing it away is why a DesiFlix series
            // listed its episodes and then played none of them.
            remoteId: cleanText(v.id) || null,
            _addon: this.id,
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
  meta(identity, hint) {
    // Marked in flight for the whole call — including the stream fetch it waits on — so a meta route
    // racing this cold lookup can tell "still asking" from "answered nothing".
    return trackPending(identity, () => this.metaWork(identity, hint));
  }

  async metaWork(identity, hint) {
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
      const body = await this.fetchJson(this.metaPath(identity, t),
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
        await this.fetchJson(this.streamPath(identity),
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
    const hit = this.cacheEntry(this.streamPath(identity));
    if (!hit || !hit.body) return [];
    const parsed = sourcesFromStreams(this.id, hit.body);
    // An empty parse is not a fact worth remembering. Memoising it is how a play a minute later answers
    // "no play target" for a copy the picker is still showing: the tile's sources came from one fetch,
    // while this label lookup found the memoised nothing.
    if (parsed.sources.length) {
      this.streamSources.set(identity, parsed.sources);
      this.streamIndex.set(identity, parsed.byLabel);
      this.lastFetch = Date.now();
    }
    return parsed.sources;
  }

  /** Background warming: the top of every catalog is what the home screen draws, so stream it first. */
  warmStreams(identities) {
    (async () => {
      for (const identity of identities.filter((id) => addressableBy(id, this.id))
        .slice(0, WARM_STREAMS)) {
        try {
          await this.fetchJson(this.streamPath(identity), { wait: true, tag: this.id, ok404: true });
          this.sources({ identity });
        } catch (e) { /* a cold lookup is not worth logging loudly; the detail page retries */ }
      }
    })();
  }

  /**
   * One waiting stream lookup, for the detail page where a round trip is acceptable. Rows never call
   * this: the home screen answers from cache and the same sources show up on the next request.
   */
  fetchStreams(identity) {
    // Same in-flight mark as meta: an episode address this hub is still fetching must not 404.
    return trackPending(identity, () => this.fetchStreamsWork(identity));
  }

  async fetchStreamsWork(identity) {
    await this.ensureManifest();
    if (!this.has('stream') || !addressableBy(identity, this.id)) return [];
    await this.fetchJson(this.streamPath(identity),
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
    let index = this.streamIndex.get(identity);
    let entry = (source && source.label && index && index.get(source.label)) || null;
    // The picker is showing this copy, and this addon's own index does not know it: the cached stream
    // answer it was built from has since gone stale or empty. Ask that addon once, live, before calling
    // the copy unplayable — "no play target" was otherwise a hub cache artefact, not a dead file.
    if (!entry) {
      await this.fetchJson(this.streamPath(identity),
        { wait: true, staleOk: true, timeoutMs: META_WAIT_MS, tag: this.id, ok404: true });
      this.sources({ identity });
      index = this.streamIndex.get(identity);
      entry = (source && source.label && index && index.get(source.label))
        || (index && [...index.values()][0]) || null;
    }
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
    const type = this.typeFor(identity);
    const rid = remoteIdFor(identity, this.id);
    const raw = [];
    for (const lang of langs) {
      const url = this.url + '/subtitles/' + type + '/' + rid + '/' + lang + '.json';
      const body = await this.fetchJson(url,
        { wait: true, staleOk: true, timeoutMs: META_WAIT_MS, tag: this.id, ok404: true });
      for (const s of (body && Array.isArray(body.subtitles) ? body.subtitles : [])) {
        if (!s || !s.url) continue;
        const name = (typeof s.name === 'string' && s.name)
          || (typeof s.filename === 'string' && s.filename)
          || (typeof s.file === 'string' && s.file)
          || null;
        raw.push({
          id: String(s.id || (this.id + ':' + rid + ':' + lang)),
          addon: this.id,
          lang: s.lang || lang,
          url: s.url,
          name,
          declared: normalizeSubtitleFormat(s.format),
        });
      }
    }
    if (!raw.length) return [];
    // The filename the addon chose is a claim (the "English.vtt" that serves SRT bytes); the sniffed
    // content is the truth the app's subtitleMime(format, url) must see first. A failed or
    // undeterminable sniff leaves `format` to the hub route's extension fallback — the listing itself
    // never fails because of a sniff.
    const sniffed = await sniffSubtitleFormats(raw.map((e) => e.url), { redirectPolicy: this.redirectPolicy });
    return raw.map((entry, i) => {
      const truth = sniffed[i] || entry.declared || null;
      const out = {
        id: entry.id,
        addon: entry.addon,
        lang: entry.lang,
        url: entry.url,
      };
      if (entry.name) out.name = truth ? withSubtitleExtension(entry.name, truth) : entry.name;
      if (truth) out.format = truth;
      return out;
    });
  }

  stats() {
    return {
      id: this.id,
      name: publicLabel(this.name, this.id || 'External add-on'),
      native: false,
      enabled: this.enabled,
      kind: this.kind,
      provenance: this.provenance.kind,
      resources: Object.keys(this.resources),
      catalogs: this.cats.length,
      items: this.items.size,
      streams: this.streamSources.size,
      lastFetch: this.lastFetch ? new Date(this.lastFetch).toISOString() : null,
      error: publicAddonError(this.lastError),
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

/**
 * Legacy external entries predate provenance. They are explicitly migrated to manual; matching a URL to
 * a future reviewed catalogue is intentionally not part of this migration, so consent never changes on
 * behalf of an owner.
 */
function persistProvenance(entriesList) {
  let changed = false;
  for (const entry of entriesList) {
    if (!entry || entry.native) continue;
    const provenance = normalizeProvenance(entry.provenance);
    if (JSON.stringify(entry.provenance || null) !== JSON.stringify(provenance)) {
      entry.provenance = provenance;
      changed = true;
    }
    if (provenance.kind === 'reviewed' && entry.redirectPolicy !== SAME_ORIGIN_REDIRECT_POLICY) {
      entry.redirectPolicy = SAME_ORIGIN_REDIRECT_POLICY;
      changed = true;
    }
  }
  return changed;
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
    // A fresh hub gets exactly the legal set in addons.example.json. Every other native provider
    // stays in the list but off, so the owner can enable an aggregator or a source pack knowingly
    // instead of a first boot quietly deciding for them.
    const nativeDefaults = defaultAddons()
      .filter((a) => a.native && registry.byId[a.id]);
    const seededExternal = seedAddons().map((a, i) => {
      const id = a.id || slugFromUrl(a.url);
      return {
        id, name: a.name || id, url: a.url,
        enabled: a.enabled !== false, addedAt: now,
        priority: Number.isFinite(a.priority) ? a.priority : 40 + i,
        provenance: { kind: 'manual' },
      };
    });
    const seededNative = nativeDefaults.map((a) => ({
      id: a.id, name: a.name, native: true, enabled: a.enabled !== false, addedAt: now,
    }));
    const known = new Set([...seededExternal, ...seededNative].map((e) => e.id));
    list = [
      ...seededExternal,
      ...seededNative,
      ...registry.providers
        .filter((p) => p.native && !known.has(p.id))
        .map((p) => ({ id: p.id, name: p.name || p.id, native: true, enabled: false, addedAt: now })),
    ];
    try {
      writeEntries(list);
      log('ADDONS seeded', ADDONS_FILE, '-', list.filter((e) => !e.native).length, 'external addons');
    } catch (e) {
      log('ADDONS seed write failed', e.message);
    }
  }

  // A native addon file added after the seed (the "one file = one feature" case) joins the list too —
  // except a provider marked `freshInstallOnly`. That mark is for a bundled shop-front default such as
  // Public Domain Movies: a fresh hub should show it, but appending it to an owner's existing list would
  // change a hub they had already configured. They can add the one entry from addons.example.json.
  const seen = new Set(list.map((e) => e.id));
  let addedNative = false;
  for (const p of registry.providers) {
    if (p.native && !seen.has(p.id) && !p.freshInstallOnly) {
      list.push({ id: p.id, name: p.name || p.id, native: true, enabled: true, addedAt: Date.now() });
      addedNative = true;
    }
  }
  if (addedNative) {
    try {
      writeEntries(list);
    } catch (e) { /* the list is still correct in memory */ }
  }

  // A fresh-install-only provider that is not in this hub's list must also leave the LIVE registry.
  // Otherwise it would be active but invisible in Settings — the worst of both. Nothing is written to
  // data/addons.json here; an owner's file is not rewritten.
  const listedNative = new Set(list.filter((e) => e.native).map((e) => e.id));
  for (const p of registry.providers.slice()) {
    if (p.native && p.freshInstallOnly && !listedNative.has(p.id)) {
      registry.remove(p.id);
      log('ADDON fresh-install default only, not in this hub list:', p.id);
    }
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
        provenance: { kind: 'manual' },
      });
      touched = true;
      log('ADDONS seeded', seed.id, '-', seed.url);
    } else if (!existing.native && !existing.configured && existing.url !== seed.url) {
      log('ADDONS seed config updated', seed.id, ':', existing.url, '->', seed.url);
      existing.url = seed.url;
      touched = true;
    }
  }
  const provenanceTouched = persistProvenance(list);
  if (touched || provenanceTouched) {
    try {
      writeEntries(list);
    } catch (e) { log('ADDONS seed/provenance write failed', e.message); }
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
    const safeStats = publicStats(stats);
    const counts = safeStats && typeof safeStats === 'object' && !Array.isArray(safeStats) ? safeStats : {};
    delete counts.error;
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
      error: publicAddonError(stats.error),
      counts,
    });
  }
  for (const addon of installed.values()) {
    if (!addon || addon.native) continue;
    // Same shape as a native row: `catalogs` is a list the app can render, counts is an object.
    rows.push(publicExternalRow(addon));
  }
  rows.sort((a, b) => Number(a.native === false) - Number(b.native === false)
    || String(a.id).localeCompare(String(b.id)));
  return rows;
}

/**
 * POST /app/v1/addons — validate the manifest, persist, hot-load. The optional third argument is an
 * internal install policy hook for the reviewed registry; public callers retain the two-argument shape.
 */
async function add(url, name, options) {
  let addonName = name;
  let installOptions = options;
  // Keep the existing `(url, name)` API while making a future trusted caller able to use
  // `add(url, { name, provenance })` without a parallel install path.
  if (name && typeof name === 'object' && !Array.isArray(name)) {
    installOptions = name;
    addonName = name.name;
  }
  installOptions = installOptions && typeof installOptions === 'object' ? installOptions : {};
  let manifestUrl;
  try {
    manifestUrl = manifestUrlFor(url).toString();
    await resolvePublicAddresses(manifestUrl, MANIFEST_WAIT_MS);
  } catch (e) {
    return { ok: false, status: 400, error: 'url must be a public HTTP(S) address' };
  }
  const provenance = normalizeProvenance(installOptions.provenance);
  const redirectPolicy = redirectPolicyFor(provenance,
    installOptions.redirectPolicy || installOptions.policy || (installOptions.sameOriginRedirects ? true : undefined));
  for (const existing of installed.values()) {
    if (existing && !existing.native && sameAddress(existing.manifestUrl, manifestUrl)) {
      // A manually-installed URL is not silently promoted when a reviewed installer later sees it.
      return { ok: false, status: 409, error: 'already installed', id: existing.id };
    }
  }
  const probe = new External({ id: 'probe', url: manifestUrl, provenance, redirectPolicy });
  const manifest = await probe.loadManifest({ wait: true });
  if (!manifest) return { ok: false, status: 502, error: 'manifest not reachable' };
  if (!manifest.id && !manifest.name && !manifest.catalogs && !manifest.resources) {
    return { ok: false, status: 422, error: 'not an addon manifest' };
  }
  // A directory installer may supply this closure from its server-side descriptor. It runs after the
  // manifest arrives but before anything is persisted or mounted, so a changed capability surface is
  // quarantined rather than promoted through a second install path.
  if (typeof installOptions.validateManifest === 'function') {
    let validation;
    try {
      validation = installOptions.validateManifest(manifest);
    } catch (error) {
      validation = null;
    }
    if (!validation || validation.ok !== true) {
      return { ok: false, status: 422, error: 'source manifest no longer matches its install contract' };
    }
  }
  const list0 = entries();
  const hostName = new URL(manifestUrl).hostname;
  const id = slug(manifest.name || addonName || manifest.id || hostName, manifestUrl);
  if (registry.byId[id]) return { ok: false, status: 409, error: 'id already taken', id };
  const entry = {
    id,
    name: addonName || manifest.name || manifest.id || id,
    url: manifestUrl,
    enabled: true,
    addedAt: Date.now(),
    priority: 40 + list0.filter((e) => !e.native).length,
    configured: true, // someone chose this URL: the seed merge in init() must not overwrite it
    provenance,
  };
  if (redirectPolicy !== DEFAULT_REDIRECT_POLICY) entry.redirectPolicy = redirectPolicy;
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
  pendingFor, episodeAddress, forgetStreams,
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
    return { TTL_MS, EMPTY_TTL_MS, FETCH_TIMEOUT_MS, META_WAIT_MS,
      MAX_SOURCES: maxSources(), MIN_SIZE_BYTES: minSizeBytes(), WARM_STREAMS };
  },
  util: {
    langCodes, humanSize, parseStream, sourcesFromStreams, identityFor, remoteIdFor, mapType,
    manifestUrlFor, httpUrlFor, isPublicAddress, resolvePublicAddresses, redirectAllowed,
    normalizeProvenance, redirectPolicyFor, publicAddonError, publicStats, publicExternalRow,
    persistProvenance, addonCacheKey,
    get,
    normalizeSubtitleFormat, detectSubtitleFormat, subtitleFormatFromName, withSubtitleExtension,
    getHeadText, sniffSubtitleFormat, sniffSubtitleFormats, clearSubFormatCache,
  },
};
