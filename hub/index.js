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

const fs = require('fs');
const http = require('http');
const https = require('https');
const crypto = require('crypto');
const zlib = require('zlib');
const { URL, URLSearchParams } = require('url');

const ROOT = __dirname;
const PORT = Number(process.env.PORT || 8788);
const TLS_CERT = String(process.env.TLS_CERT || '').trim();
const TLS_KEY = String(process.env.TLS_KEY || '').trim();
let tlsOptions = null;
if (TLS_CERT || TLS_KEY) {
  if (!TLS_CERT || !TLS_KEY) {
    console.error('TLS_CERT and TLS_KEY must be set together');
    process.exit(1);
  }
  try {
    tlsOptions = { cert: fs.readFileSync(TLS_CERT), key: fs.readFileSync(TLS_KEY) };
  } catch (e) {
    console.error('TLS certificate/key unreadable:', e.message);
    process.exit(1);
  }
}
const SCHEME = tlsOptions ? 'https' : 'http';
// Optional shared access token. Unset means the old trusted-LAN behaviour: no prompt, no header.
// Set means every route except the identity-only /healthz needs HTTP Basic or `Bearer <token>`.
const HUB_TOKEN = String(process.env.HUB_TOKEN || '').trim();

function sameSecret(a, b) {
  if (!a || !b) return false;
  const x = Buffer.from(String(a));
  const y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

/** True when this request may see the library. With no token configured, everyone may. */
function authorized(req) {
  if (!HUB_TOKEN) return true;
  const raw = String((req.headers && req.headers.authorization) || '').trim();
  if (/^Bearer\s+/i.test(raw)) return sameSecret(raw.replace(/^Bearer\s+/i, '').trim(), HUB_TOKEN);
  if (/^Basic\s+/i.test(raw)) {
    let decoded = '';
    try { decoded = Buffer.from(raw.replace(/^Basic\s+/i, '').trim(), 'base64').toString('utf8'); } catch (e) { return false; }
    const at = decoded.indexOf(':');
    // The username is deliberately ignored: this is one household token, not a user directory. Browsers
    // insist on a username field for Basic auth, so any name may be typed there.
    return at >= 0 && sameSecret(decoded.slice(at + 1), HUB_TOKEN);
  }
  return false;
}
const HUB_ID = process.env.HUB_ID || 'scenix-hub';
const HUB_VERSION = process.env.HUB_VERSION || '0.1.0';

const registry = require('./core/registry').load(ROOT);
const store = require('./core/store').create(ROOT);
const merge = require('./core/merge');
const prefs = require('./core/prefs');
const gateway = require('./core/gateway');
// Optional: a paid resolver, off unless the owner configures a provider and a token. It is tried before
// the gateway and is never a hard dependency of the play path.
const debrid = require('./core/debrid');
const addons = require('./core/addons');
const trailers = require('./core/trailers');
const ratings = require('./core/ratings');
const castApi = require('./core/cast');
const probe = require('./core/probe');
const webui = require('./core/webui');
const { origin } = require('./core/origin');
// What a copy actually contains (core/media.js) and how to re-wrap it for a browser (core/remux.js).
// Both are read-only against the engine's own stream URL and both report themselves in /healthz.
const media = require('./core/media');
const remux = require('./core/remux');
// The hub's own read-ahead: one bounded cushion of torrent bytes per copy, shared by the player, the
// remuxer and the Play press that measures the copy before promising it. See core/readahead.js.
const readahead = require('./core/readahead');
// What the owner chose (core/settings.js) and what this hub says about itself on the LAN
// (core/discovery.js). Both are read when a request asks, so a change made in the hub's own UI lands on
// the next request: a setting that needs a restart to take effect is a setting nobody really made.
const settings = require('./core/settings');
const langdb = require('./core/langdb');
const avatars = require('./core/avatars');
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
// The settings screen asks every mounted addon what catalogues it publishes. That is a cache read for a
// warmed addon and a short wait for a cold one; it must not hold the settings page open longer than this.
const CATALOG_OPTIONS_BUDGET_MS = Number(process.env.CATALOG_OPTIONS_BUDGET_MS || 600);
// A person typed this, so it is worth a round trip; a home row is never allowed one.
const SEARCH_BUDGET_MS = Number(process.env.SEARCH_BUDGET_MS || 3000);
const ALT_MAX = Number(process.env.ALT_MAX || 9);
// The picker's own budgets. `PLAY_TOTAL_BUDGET_MS` is how long one Play press may spend looking for a
// link that actually answers; `GATEWAY_DELIVER_MS` is how long a single cold torrent is allowed to
// prove it can put bytes on the wire. Both are generous on purpose: a viewer waiting 8s for a film
// that starts and keeps going beats a link that "succeeds" in 0.3s and dies in the player. The total
// stays inside the 12s read timeout the TV app puts on this call, so the last candidate tried is one
// that has actually been measured rather than one the app gave up waiting for.
const PLAY_MAX_TRIES = Number(process.env.PLAY_MAX_TRIES || 2);
const PLAY_TOTAL_BUDGET_MS = Number(process.env.PLAY_TOTAL_BUDGET_MS || 5000);
// How long a warming title asks the player to wait before pressing again. The hub says it rather than
// the client guessing, because only the hub knows whether it is talking to an engine that is awake.
const PLAY_RETRY_AFTER_MS = Number(process.env.PLAY_RETRY_AFTER_MS || 2000);
const GATEWAY_DELIVER_MS = Number(process.env.GATEWAY_DELIVER_MS || 1500);
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
  const d = debrid.status();
  const live = registry.byId['live-tv'];
  const caps = {
    gateway: g.enabled === true, // torrents are served through an engine here, not handed to the client
    live: !!live && !!String((settings.get().live || {}).playlist || '').trim(),
    localFiles: !!registry.byId['local-files'],
    remux: remux.status().binFound === true, // a browser can be handed fragmented MP4 / HLS
    tracks: media.status().binFound === true, // audio and subtitle languages are read from the files
    announce: process.env.ANNOUNCE !== '0', // a client can find this hub without being told its address

    // The rest of the vocabulary is about what a client may *change*, not about what it may watch. The
    // rule it encodes is the deployment rule: configuration lives with whatever backend owns the
    // household, so a hub install configures through the hub and a standalone TV configures itself. A
    // client never learns which kind of box it is facing - it hides a control whose name is absent.
    search: true, // /app/v1/search answers across every mounted addon
    subtitles: hasSubtitleSource(), // an addon here supplies subtitle files
    settingsAdmin: true, // languages, channels and rows: GET+PUT /app/v1/settings
    addonAdmin: true, // addons are added, enabled, disabled and removed over HTTP
    prefsEdit: true, // the per-profile definition of "the stream I want"
    profileEdit: true, // profiles are created, renamed and removed over HTTP,
    cacheAdmin: true, // the answers this backend cached can be thrown away on purpose,
    avatarFaces: true, // profile faces are offered and served by this backend
  };
  // A flag only when the resolver can actually do something. Off, unconfigured or missing a token means
  // the capability is absent, exactly like an uninstalled addon: the clients need no "debrid is off" state.
  if (d.ready === true) caps.debrid = true;
  return caps;
}

/** Does anything mounted here answer the subtitle resource? A capability is a route that exists, not a hope. */
function hasSubtitleSource() {
  return registry.providers.some((p) => {
    const src = (p && (p.legacy || p.source || p)) || {};
    return typeof src.has === 'function' && src.has('subtitles');
  });
}

/**
 * A profile on the wire. The PIN is deliberately absent: it travels inbound, never outbound, so a code
 * typed on a TV cannot be read off the network by whoever is listening, and a client that wants to know
 * whether to show a keypad asks with `pinSet`.
 */
/*
 * A profile PIN never leaves the box in a form anyone can read, and it never sits on disk in one either.
 * A four-digit code has only ten thousand answers, so the defence is not a longer hash: it is a slow hash
 * (scrypt, per-profile salt) plus a rate limit on the unlock route. The tag in front of the hash is what
 * lets a record written before this change still be opened once, and be rewritten hashed on its next save.
 */
const PIN_TAG = 'scrypt';
function hashPin(pin) {
  const salt = crypto.randomBytes(16).toString('hex');
  const hash = crypto.scryptSync(String(pin), salt, 32).toString('hex');
  return PIN_TAG + ':' + salt + ':' + hash;
}
function isHashedPin(v) { return typeof v === 'string' && v.startsWith(PIN_TAG + ':'); }
function safeEqual(a, b) {
  const x = Buffer.from(String(a == null ? '' : a));
  const y = Buffer.from(String(b == null ? '' : b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}
function profileHasPin(p) {
  return !!(p && (isHashedPin(p.pinHash) || (p.pin != null && String(p.pin).trim() !== '')));
}
function pinMatches(p, given) {
  if (!p || given == null) return false;
  if (isHashedPin(p.pinHash)) {
    const parts = String(p.pinHash).split(':');
    if (parts.length !== 3) return false;
    const got = crypto.scryptSync(String(given), parts[1], 32);
    const want = Buffer.from(parts[2], 'hex');
    return want.length === got.length && crypto.timingSafeEqual(want, got);
  }
  // A record written before the hash existed is compared once, in constant time, and hashed on next save.
  return p.pin != null && safeEqual(p.pin, given);
}

/** One slow attempt per profile, counted in memory; ten wrong numbers in five minutes is a brute force. */
const unlockTries = new Map(); // profile id -> { count, until }
function unlockAllowed(id) {
  const now = Date.now();
  const row = unlockTries.get(String(id));
  if (!row || row.until <= now) return true;
  return row.count < 10;
}
function unlockFailed(id) {
  const now = Date.now();
  const row = unlockTries.get(String(id)) || { count: 0, until: now + 5 * 60e3 };
  row.count++;
  if (row.until <= now) row.until = now + 5 * 60e3;
  unlockTries.set(String(id), row);
}
function unlockSucceeded(id) { unlockTries.delete(String(id)); }

/** Hash every plaintext PIN the seed or an older build left behind, once, at boot. */
function migratePins() {
  let changed = 0;
  for (const p of store.profiles()) {
    if (p && p.pin != null && String(p.pin).trim() !== '' && !isHashedPin(p.pinHash)) {
      store.patchProfile(p.id, { pinHash: hashPin(String(p.pin).trim()), pin: null });
      changed++;
    }
  }
  if (changed) log('PINS hashed at rest:', changed);
}

migratePins();

function wireProfile(p) {
  const { pin, pinHash, ...rest } = p || {};
  const pinSet = profileHasPin(p);
  // One honest answer instead of two flags a screen has to combine right. `pinRequired` without a PIN is a
  // gate that checks nothing, and a client that only reads the flag builds a wall the viewer cannot pass -
  // which is what happened to a profile here whose PIN was removed during testing. The hub knows whether an
  // unlock would actually be asked for, so it says so.
  // `prefs` is what the owner wrote; `effectivePrefs` is what the hub will actually rank with, after
  // the household defaults fill the gaps. A settings screen edits the first and must see it untouched,
  // while anything that plays needs the second - a profile that inherits its languages from the hub was
  // sending an empty list to the player, so the household's first-choice audio silently stopped applying.
  return { ...rest, pinSet, locked: pinSet && !!p.pinRequired, effectivePrefs: prefs.resolve(p) };
}

/* ------------------------------------------------------------------ adult acts

   The lock on a profile holds *that profile's* settings down, and it has never covered making a profile or
   taking one away. On a hub where the grown-ups' profiles are the ones with numbers and the children's are not,
   that leaves a door a child can walk through without touching a lock: add an adult profile, read the whole
   catalogue through it, remove it again, and the household is left exactly as it was - which is what the owner
   found, with the latenight row switched off and adult titles still on the home screen, because a profile whose
   kind is `adult` has no age ceiling at all (rules/maturity.js) and tier-2 titles need no switch.

   So these are named as adult acts, whoever is standing in front of the TV when they happen:

     · adding a profile that is not a Kids one. A new Kids profile is harmless and stays free: whatever age is
       written on it, the hub holds it to its own ceiling, and a child adding a playmate costs nothing.
     · removing a profile that has no number of its own to produce.
     · raising a Kids profile into the adult band, or raising the age it is held to.
     · switching on the dark-red zone, which rules/maturity.js calls "a switch the household turns on".

   The proof is the PIN of a profile that is already an adult's. If nobody on this hub has a number there is
   nothing to ask, and the act stays free: the hub never invents a PIN, and a guard that refused everything
   would be a lock-out of this file's making. Every refusal says so in the same shape - `gate: 'profiles'` -
   so a screen can tell "this act belongs to an adult" apart from "you typed the wrong number".
*/
function pinHolders() {
  return store.profiles().filter((p) => profileHasPin(p));
}

/**
 * The numbers that open an adult act: an adult profile's, and on a hub that has put a number only on a child's
 * profile, that number too. There it is the only proof anyone here has, and refusing it would lock the
 * household out of its own settings rather than keep anyone out of anything.
 */
function adultHolders() {
  const held = pinHolders();
  const grown = held.filter((p) => p.kind !== 'kids');
  return grown.length ? grown : held;
}

function proofGiven(body) {
  return !body || body.pinCurrent == null ? '' : String(body.pinCurrent).trim();
}

/** Does this request carry a number an adult act needs? True when there is no number here to need. */
function adultProofOk(body) {
  const holders = adultHolders();
  if (!holders.length) return true;
  const given = proofGiven(body);
  if (!given) return false;
  return holders.some((p) => pinMatches(p, given));
}

/** The refusal itself, worded by whether a wrong number was typed or none was offered. */
function refuseAdult(res, body, act) {
  const given = proofGiven(body);
  const error = given
    ? `that PIN is not the PIN of an adult profile here, and one is needed to ${act}`
    : `an adult PIN is needed to ${act}`;
  log('PROFILE LOCK REFUSED', act, given ? 'the number offered is not an adult profile’s' : 'no number offered');
  return send(res, 403, { error, gate: 'profiles' });
}

/**
 * Is this proposed change one an adult has to make? Two ways it can be: the ceiling a profile is held to goes up
 * (a Kids profile moved into the adult band, or a child's age raised past where it was), or the filters that
 * decide what a Kids profile may see are rewritten at all.
 *
 * The second half is deliberately coarse. The hub can tell that a Kids profile's rules or language list is being
 * edited, not whether this particular edit widens the shelf or narrows it, and a child who narrows their own
 * shelf today can widen it again tomorrow with nothing on the screen to notice. Tightening stays free in the two
 * places the hub can see the direction for certain: the kind and the age.
 */
function isAdultChange(held, patch) {
  if (!held) return false;
  if ('kind' in patch && patch.kind !== 'kids' && held.kind === 'kids') return true;
  if ('age' in patch && held.kind === 'kids'
    && maturityRule.ceilingOf({ kind: 'kids', age: patch.age }) > maturityRule.ceilingOf(held)) return true;
  if (held.kind === 'kids' && ('rules' in patch || 'languages' in patch)) return true;
  return false;
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

/**
 * A catalog descriptor can state the genres it answers, either as `genreOptions` (the hub's own
 * providers) or in the addon-protocol form `extra: [{ name: 'genre', options: [...] }]`. Reading both
 * here is what lets the Categories screen offer real choices without carrying a genre list of its own.
 */
function genreOptionsOf(cat) {
  const out = [];
  const add = (value) => {
    const name = String(value == null ? '' : value).trim().slice(0, 80);
    if (name && !out.includes(name)) out.push(name);
  };
  if (Array.isArray(cat.genreOptions)) {
    for (const g of cat.genreOptions) add(g);
  }
  if (!out.length && Array.isArray(cat.extra)) {
    for (const e of cat.extra) {
      if (!e || typeof e !== 'object') continue;
      if (e.name !== 'genre' && e.key !== 'genre') continue;
      if (Array.isArray(e.options)) for (const g of e.options) add(g);
    }
  }
  return out;
}

/** The catalogues an owner can build a category from, in the order the providers asked for. */
async function catalogOptions() {
  const groups = await Promise.all(registry.providers.map(async (p) => {
    if (typeof p.catalogs !== 'function' || typeof p.catalog !== 'function') return [];
    let cats = [];
    try {
      cats = await withBudget(
        Promise.resolve().then(() => p.catalogs()),
        CATALOG_OPTIONS_BUDGET_MS, [], 'catalogs ' + p.id,
      );
    } catch (e) {
      return []; // an addon that cannot list its catalogues is not offered as a source
    }
    return (Array.isArray(cats) ? cats : []).filter((c) => c && c.id).map((c) => ({
      provider: p.id,
      providerName: p.name || p.id,
      catalog: String(c.id),
      name: c.name || String(c.id),
      type: c.type || null,
      // What the home screen should draw. A live/TV catalogue is a channel wall; everything else is a
      // poster rail. The decision is a fact about the catalogue, not a setting the owner has to make.
      card: (c.type === 'tv' || c.type === 'channel' || p.kind === 'channel') ? 'channel' : 'poster',
      genres: genreOptionsOf(c),
    }));
  }));
  return groups.flat();
}

const CATEGORY_ROW_PREFIX = 'category:';

/** The id a category has on /home and in `rows.hidden` / `rows.order`. */
function categoryRowId(category) {
  return CATEGORY_ROW_PREFIX + category.id;
}

/**
 * One owner-made shelf, shaped exactly like a plugin row so it walks the same pipeline: build -> budget
 * -> dedupe -> rules -> trim. It names a provider and a catalogue rather than importing one, so an
 * addon that is slow, disabled or gone simply yields an empty row which /home drops. Nothing here can
 * take the home screen down with it.
 */
function categoryRow(category) {
  const provider = registry.byId[category.provider];
  const extra = category.genre ? { genre: category.genre } : {};
  return {
    id: categoryRowId(category),
    title: category.title || category.genre || category.catalog,
    card: provider && (provider.kind === 'channel' || provider.kind === 'tv') ? 'channel' : 'poster',
    // Plugin rows keep their own order; categories start behind them. `rows.order` can still place a
    // category explicitly because the home route sorts the finished list by that same list.
    order: 1000 + (Number(category.order) || 0),
    scope: 'all',
    category,
    build(ctx) {
      return ctx.catalog(category.provider, category.catalog, extra);
    },
  };
}

/** A category by its virtual row id or its bare id, for the one-row route. */
function categoryById(id) {
  const key = String(id || '');
  const category = (settings.get().categories || [])
    .find((c) => c.id === key || categoryRowId(c) === key);
  return category ? categoryRow(category) : null;
}

/** Every category the owner left switched on, as virtual home rows. */
function enabledCategoryRows() {
  return (settings.get().categories || [])
    .filter((c) => c.enabled !== false)
    .map(categoryRow);
}

function rowVisible(row, profile) {
  const scope = row.scope || 'all';
  // An owner's choice is final before any rule runs: "do not show this row on the home screen" is not
  // something a profile's age or a row's scope should be able to overrule.
  if ((settings.get().rows || {}).hidden.includes(row.id)) return false;
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

/**
 * Tier 3 ("the dark-red zone") has exactly one shelf: Late Night. Turning the switch on must not turn
 * Just Added, Top 10, Continue Watching or a taste row into a porn wall; that is not what the switch says
 * and it is not what a household expects to see when it opens Home. Direct detail/meta/play still work, so
 * a title the owner asked for can be opened by name, but the general shelves never advertise it.
 */
const ADULT_ROW_ID = 'latenight';
// The first screen is a preview, not the whole shelf. Eighteen cards fit two screens of a TV row and
// keep the home payload (and the DOM the WebView must build) roughly a third smaller than thirty.
const HOME_ROW_MAX = Number(process.env.HOME_ROW_MAX || 18);
function generalItems(rowId, items) {
  if (!Array.isArray(items)) return items;
  if (rowId === ADULT_ROW_ID) return items;
  return items.filter((it) => Number(it && it.tier) !== 3);
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
    scheme: SCHEME,
    tls: !!tlsOptions,
    addons: addonStats,
    rows,
    rules: registry.rules.map((r) => ({ id: r.id, order: r.order })),
    store: store.stats(),
    gateway: Object.assign({}, gateway.status(), {
      // The rate the engine has last been measured delivering, so the number the picker orders by is
      // visible from outside rather than inferred from which copy it happened to choose.
      linkMbps: gatewayMbps ? Number(gatewayMbps.toFixed(2)) : null,
    }),
    // Optional paid resolver. Off is a normal, expected state; when on, this says which provider is
    // selected and whether a token is stored, never what it is. The check is not performed here so
    // /healthz never waits on a third party.
    debrid: debrid.status(),
    probe: probe.status(), // how many links this hub has actually measured, and what they answered
    // What the copies contain, and whether the tooling that says so is installed: an operator with a
    // missing ffprobe sees "binFound: false" here rather than wondering why every source says
    // "audio: not stated" forever.
    tracks: media.status(),
    // Whether a browser can be handed MP4 at all: the binary, how many ffmpeg are running, how many
    // requests were refused because the box is already remuxing.
    remux: remux.status(),
    // The cushion in front of the player: what is buffered, how fast the swarm is actually feeding it,
    // and which copies this hub has watched fail to carry a film. This is the block to read when the
    // question is "why did the picture stop".
    readahead: readahead.status(),
    // The owner's choices as this request sees them, and what the LAN responder is currently saying.
    // A hub that "works" but has lost its announce socket shows that here rather than in a support call
    // about a TV that cannot find it.
    settings: settings.status(),
    discovery: discovery.status(),
    trailers: trailers.status(),
    // What this box knows about the languages of what it catalogues: the number that says how much of the
    // owner's language rule is actually enforceable, and how much is still guesswork.
    avatars: avatars.status(),
    languages: { file: langdb.FILE, ...langdb.coverage() },
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
/**
 * Follow a CDN's redirect while keeping the headers the addon asked for. Google Video answers a
 * proxied trailer with a 302 to a signed host; handing that 302 back to a <video> element makes the
 * browser request it without the User-Agent/Referer the link requires, and the trailer just fails.
 * Torrent-engine URLs never redirect, so this only changes the ordinary-URL path.
 */
function upstreamGet(target, headers, timeoutMs, depth = 0) {
  const mod = target.startsWith('https:') ? https : http;
  return new Promise((resolve) => {
    const r = mod.get(target, { headers, timeout: timeoutMs }, (got) => {
      r.setTimeout(0);
      const loc = got.headers && got.headers.location;
      if (loc && got.statusCode >= 300 && got.statusCode < 400 && depth < 4) {
        got.resume();
        let next;
        try { next = new URL(loc, target).toString(); } catch (e) { resolve(got); return; }
        resolve(upstreamGet(next, headers, timeoutMs, depth + 1));
        return;
      }
      resolve(got);
    });
    r.on('error', (e) => resolve({ error: e }));
    r.on('timeout', () => { r.destroy(); resolve({ error: new Error('upstream timeout after ' + timeoutMs + 'ms') }); });
  });
}

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
  if (hash) {
    // A torrent goes through the read-ahead buffer, not straight to the engine. The player reads at LAN
    // speed out of RAM while the swarm bursts and pauses, and the hub's single sequential reader is the
    // only thing touching the engine's one cache per torrent. It falls through to the plain pass-through
    // when read-ahead is switched off or asks something the buffer cannot answer (a suffix range).
    const served = await readahead.serve(req, res, who, {
      headersMs,
      needMbps: Number(u.searchParams.get('need')) || 0,
      retryAfterMs: PLAY_RETRY_AFTER_MS,
      onReader: () => gateway.readBegin(hash, fileId),
      onCold: (waited) => gateway.noteCold(hash, waited),
    });
    if (served) return;
  }
  const t0 = Date.now();
  const tag = 'hash=' + (hash ? hash.slice(0, 12) : '-') + ' file=' + (fileId == null ? '-' : fileId);
  const upstream = await upstreamGet(target, headers, headersMs);
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
    if (entry.video && entry.video.codec) s.videoCodec = entry.video.codec;
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

/**
 * The byte of the file a viewer at `positionMs` is sitting on.
 *
 * Seconds of film and bytes of file meet at the average bitrate, which is the same arithmetic
 * rules/source_ranking.js uses to estimate what a copy needs. A variable-bitrate file is off by a few
 * percent; starting a pre-roll a few percent early costs nothing, and starting one at the wrong place
 * costs the whole point of it.
 */
function resumeOffset(source, item, positionMs) {
  const pos = Number(positionMs);
  const dur = Number(item && item.durationMs);
  const size = Number((source && source.sizeBytes) || (item && item.sizeBytes) || 0);
  if (!(pos > 0) || !(dur > 0) || !(size > 0)) return 0;
  return Math.max(0, Math.min(size - 1, Math.floor(size * (pos / dur))));
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
  // Which codecs this device says it can decode, in the device's own words. A client that names nothing
  // imposes no constraint - the TV plays whatever the torrent holds - so this only ever narrows the
  // choices for a client that has actually checked.
  const decodable = (Array.isArray(opts.codecs) ? opts.codecs : [])
    .map((c) => ranking.codecFamily(c)).filter(Boolean);
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
  // Three kinds of known-bad are held back rather than dropped: a link the prober watched die, a torrent
  // the addon says nobody is sharing, and a copy this device has no decoder for. Held back, not deleted —
  // when every copy of a title is H.265 a browser still deserves an answer, and an honest one.
  const rankDown = (s) => (s && s.status === 'down' ? 2 : 0) + (s && s.seeders === 0 ? 1 : 0)
    + (decodable.length && s && s.videoCodec
      && !decodable.includes(ranking.codecFamily(s.videoCodec)) ? 3 : 0);
  // The walk only ever seats PLAY_MAX_TRIES copies, so those seats go to what can fail this device least,
  // with the household's ranking intact underneath. Taking the seats straight off the top of the ranking
  // let an unplayable file hold one a playable copy was waiting further down for: measured on a series
  // whose two H.264 copies ranked fourth and fifth of twelve, a browser that had already said it cannot
  // draw H.265 was handed a black screen with sound because three better-ranked H.265 files got there
  // first. A viewer who picked a source by hand still leads.
  const seated = candidates.slice().sort((a, b) => rankDown(a) - rankDown(b));
  const queue = wanted && source ? [source, ...seated.filter((s) => s !== source)] : seated;
  if (queue.length > PLAY_MAX_TRIES) queue.length = PLAY_MAX_TRIES;
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
  let slowGateway = null; // a gateway link that resolved but has not proved it can carry this file
  let tooSlow = null; // a gateway link that produced bytes, just not fast enough for this file

  /**
   * The optional paid resolver, tried before the gateway. It only ever *adds* an answer: disabled, slow
   * or refused and the caller falls through to the code below unchanged. The budget is deliberately
   * smaller than a Play press, so a provider that is thinking cannot spend the time the gateway needs.
   */
  const tryDebrid = async (o, cand) => {
    if (!debrid.ready()) return null;
    const isTorrent = cand.kind === 'torrent' || /^magnet:/i.test(String(o.url || ''))
      || /^[a-fA-F0-9]{40}$/.test(String(o.infoHash || ''));
    const isHoster = cand.kind === 'hoster' || o.kind === 'hoster' || debrid.isHosterUrl(o.url);
    if (!isTorrent && !isHoster) return null;
    const left = deadline - Date.now();
    if (left < 2000) return null;
    const budgetMs = Math.max(700, Math.min(3200, left - 1200));
    let r = null;
    try {
      r = await debrid.resolve(
        { kind: isTorrent ? 'torrent' : 'hoster', url: o.url, magnet: o.url, infoHash: o.infoHash },
        { want: ep ? { season: ep.season, episode: ep.episode } : null, budgetMs },
      );
    } catch (e) {
      // debrid.resolve() itself never throws; this is belt-and-braces for a future adapter.
      r = { url: null, reason: 'debrid failed' };
    }
    if (!r || !r.url) {
      if (r && !r.skipped) {
        tried.push(labelOf(cand) + ': debrid ' + (r.reason || 'no answer'));
        log('DEBRID MISS', identity, labelOf(cand), r.reason || 'no answer');
      }
      return null;
    }
    const value = Object.assign({}, o);
    value.mode = 'direct';
    value.kind = r.kind === 'hls' ? 'hls' : 'http';
    value.url = r.url;
    value.provider = r.provider;
    value.providerLabel = r.providerLabel || r.provider;
    value.expiresAt = r.expiresAt || null;
    value.debrid = {
      provider: r.provider || null,
      providerLabel: value.providerLabel || null,
      kind: value.kind,
      expiresAt: value.expiresAt,
      fileName: r.fileName || null,
      fileSize: r.fileSize || null,
      cached: r.cached === true,
    };
    if (isTorrent) value.magnet = o.url;
    value.headers = {};
    extraWhy.push('debrid: served by ' + (value.providerLabel || 'your provider')
      + ' as ordinary HTTP, no torrent engine involved');
    return value;
  };

  for (const cand of queue) {
    const addon = addonOf(cand);
    if (!addon || typeof addon.play !== 'function') continue;
    const left = deadline - Date.now();
    if (left <= 0) { tried.push('out of time after ' + queue.indexOf(cand) + ' tries'); break; }
    const o = await withBudget(Promise.resolve().then(() => addon.play(kept.streamId || identity, cand)),
      Math.min(META_BUDGET_MS, left), null, 'play ' + addon.id);
    if (!o || !o.url) { tried.push(labelOf(cand) + ': no play target'); continue; }

    // Debrid first: a magnet or a hoster link becomes ordinary HTTP and skips the swarm entirely. If it
    // says no, the torrent branch asks the gateway exactly as it did before this file existed.
    const viaDebrid = await tryDebrid(o, cand);
    if (viaDebrid) { out = viaDebrid; source = cand; break; }

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
      { clientOrigin: opts.clientOrigin,
        // A cold torrent's metadata can take much longer than a Play press may. One press gets a short
        // look; the engine keeps the torrent, so the client's own retry finds it already resolved.
        budgetMs: Math.max(1200, Math.min(2500, deadline - Date.now() - 1200)),
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
    // Measure before promising. `size / duration` is the bitrate this copy needs, and the rate the hub
    // can actually pull out of it has to clear that — which is what "auto quality" means on the apps this
    // household compares us to: keep the sharpest copy the swarm can feed, and step down a rung when it
    // cannot. There are two ways to learn that rate and the difference between them is the difference
    // between a film and a freeze:
    //
    //   · Read-ahead on (the default): read the copy continuously through core/readahead.js for
    //     playback.sustainBudgetMs and score the WORST playback.rateWindowMs window inside it. Tonight's
    //     copy handed over 171 MB in five seconds out of the engine's cache and then sent close to
    //     nothing for 45; an average over that sample flatters it and a 256 KB sample cannot see it at
    //     all. Playback dies in the worst window, so the worst window is the verdict. The bytes this
    //     read pulls are not thrown away afterwards — they are the pre-roll the film starts with.
    //   · Read-ahead off: the old 256 KB probe, which can only ever produce a floor.
    const needs = ranking.estimateMbps(cand, kept);
    // The engine has recently failed to answer for this torrent at all. Asking it again on the very next
    // press spends the viewer's whole patience budget finding that out again, so while any other copy is
    // on the list this one waits for its turn.
    const frozen = gateway.isCold(r.hash);
    if (frozen && queue.length > 1) {
      tried.push(labelOf(cand) + ': the engine has not answered for this torrent for '
        + Math.round((frozen.until - Date.now()) / 1000) + 's (no header ' + (frozen.tries || 1)
        + 'x, last after ' + frozen.waitedMs + 'ms)');
      o.cold = true;
      slowGateway = slowGateway || { o, cand };
      continue;
    }
    let m = null;
    const buf = readahead.acquire({ hash: r.hash, fileId: r.fileId });
    if (buf) {
      readahead.noteNeed({ hash: r.hash, fileId: r.fileId }, needs);
      m = await readahead.sustain(buf, {
        needMbps: needs,
        at: resumeOffset(cand, kept, opts.positionMs),
        // A fair share of the time that is left, not all of it: three copies inside eleven seconds means
        // the copy tested third must not be judged on a 1.5 s glance while the first had four. The reader
        // is left open either way, so a copy passed over keeps warming in the background and the next
        // press finds its bytes already in RAM.
        budgetMs: Math.max(1000, Math.min(readahead.cfg().sustainMs,
          Math.floor(Math.max(0, deadline - Date.now() - 900) / Math.max(1, queue.length - queue.indexOf(cand))))),
      });
    } else {
      const p = await probe.probeUrl(r.url, o.headers || {}, {
        timeoutMs: Math.max(800, Math.min(GATEWAY_DELIVER_MS, deadline - Date.now())),
        force: true,
      });
      // A sample that arrived faster than it can be timed is a floor, not a rate — and a floor taken out
      // of the engine's own cache is not even that. One 100 KB sample served from RAM divided by its own
      // millisecond of transfer once looked like 8388 kbps, and the picker then ordered a 6 GB copy that
      // had never put a byte on the wire. Only a real, timed transfer may move the link estimate.
      m = p && {
        ok: p.ok !== false,
        error: p.error || p.status || 'no bytes',
        mbps: Number.isFinite(p.kbps) ? p.kbps / 1000 : null,
        real: !!p.ok && p.rateReliable !== false && !p.rateFloor,
        bytes: Number(p.bytes) || 0,
        ms: Number(p.ms) || 0,
        probe: p,
      };
    }
    if (m && m.ok === false) {
      tried.push(labelOf(cand) + ': engine served nothing (' + m.error + ')');
      // A gateway link that was merely slow still beats a magnet on a TV that has no engine of its
      // own, so it is kept as the last resort rather than thrown away.
      o.warming = true;
      slowGateway = slowGateway || { o, cand };
      continue;
    }
    const got = m && Number.isFinite(m.mbps) ? m.mbps : null;
    const rateIsReal = !!m && !!m.real && got != null;
    if (m && m.cached) {
      // The cushion is already full of this copy, so it is sitting on a fast server — the one advantage a
      // paid caching service sells, arrived at with RAM instead of money. Nothing left to measure.
      log('PLAY RATE CACHED', identity, labelOf(cand),
        'bufferedMB=' + m.bufferedMB, 'need=' + (needs == null ? '?' : needs.toFixed(1)));
      gateway.clearCold(r.hash);
      readahead.clearSlow(r.hash);
    } else if (rateIsReal) {
      gatewayMbps = gatewayMbps ? gatewayMbps * 0.6 + got * 0.4 : got;
      log('PLAY RATE MEASURED', identity, labelOf(cand),
        'mbps=' + got.toFixed(1), 'need=' + (needs == null ? '?' : needs.toFixed(1)),
        'avg=' + m.avgMbps, 'over=' + m.ms + 'ms', 'windows=' + JSON.stringify(m.windows || []),
        'bufferedMB=' + m.bufferedMB);
      // A copy that has now been measured carrying its own bitrate is not the copy that starved someone.
      if (needs && got >= needs * speedBar) {
        gateway.clearCold(r.hash);
        readahead.clearSlow(r.hash);
      }
    } else if (m && !m.bytes) {
      // Nothing arrived at all. That is a cold torrent still handshaking with its swarm, not a slow one,
      // and the difference matters: the answer is to keep reading (this buffer's reader stays open, and
      // the engine's first byte has been measured arriving seventeen seconds after we asked) rather than
      // to call the copy a failure. The bytes land in RAM anyway, and a later press finds them there.
      log('PLAY RATE WARMING', identity, labelOf(cand), 'over=' + m.ms + 'ms',
        'seeders=' + (cand.seeders != null ? cand.seeders : '?'),
        'need=' + (needs == null ? '?' : needs.toFixed(1)));
      tried.push(labelOf(cand) + ': no byte of it arrived in ' + m.ms + 'ms (cold torrent, still reading)');
      o.warming = true;
      o.measuredBytes = 0;
      o.neededMbps = Number.isFinite(needs) ? Number(needs.toFixed(2)) : null;
      slowGateway = slowGateway || { o, cand };
      continue;
    } else if (got != null) {
      // The sample was too short, or was a floor rather than a rate: the number cannot be trusted either
      // way, which is precisely the situation that used to end in `PLAY RATE IGNORED … reason=rateFloor`
      // and the same 1.8 GB copy being chosen every night. An unproven copy is not a green light; it goes
      // into the same limbo as a cold one, its reader stays open so it keeps proving itself, and the
      // choice is made among the copies that did measure.
      log('PLAY RATE IGNORED', identity, 'kbps=' + Math.round(got * 1000),
        'reason=' + (m.probe ? (m.probe.rateFloor ? 'rateFloor' : 'rateReliable=false') : 'shortWindow'),
        'bytes=' + (m.bytes || 0), 'over=' + (m.ms || 0) + 'ms');
      tried.push(labelOf(cand) + ': only ' + ((m.windows || []).length) + ' timed window of it ('
        + (m.bytes || 0) + ' bytes in ' + (m.ms || 0) + 'ms) — no rate to judge');
      o.checked = true;
      o.measuredBytes = m.bytes || 0;
      o.measuredMbps = Number(got.toFixed(2));
      o.neededMbps = Number.isFinite(needs) ? Number(needs.toFixed(2)) : null;
      slowGateway = slowGateway || { o, cand };
      continue;
    }
    // A copy this hub watched starve a viewer minutes ago is not offered again as though nothing had
    // happened. It stays available — a different evening's swarm may feed it — but it stops being the
    // automatic first choice while anything else is on the list.
    const shunned = readahead.isSlow(r.hash);
    if (shunned && queue.length > 1) {
      tried.push(labelOf(cand) + ': starved a viewer '
        + Math.round((Date.now() - shunned.at) / 1000) + 's ago at ' + shunned.mbps
        + ' Mbps against the ~' + shunned.needMbps + ' Mbps this file needs');
      o.checked = true;
      o.slow = true;
      o.measuredBytes = 0;
      o.neededMbps = shunned.needMbps;
      o.measuredMbps = shunned.mbps;
      slowGateway = slowGateway || { o, cand };
      continue;
    }
    // Refusing a link on a number we cannot trust is worse than playing it. The bar itself is the
    // client's: a native player that buffers minutes ahead is given the relaxed one (speedBar), a
    // browser re-wrapping the copy keeps the rule's full demand.
    if (rateIsReal && needs && got < needs * speedBar) {
      tried.push(labelOf(cand) + ': measured ' + got.toFixed(1) + ' Mbps over ' + m.ms + 'ms, this file needs ~'
        + needs.toFixed(1) + ' Mbps'
        + (speedBar < ranking.HEADROOM ? ' (bar relaxed to ' + speedBar.toFixed(2)
        + 'x for a client that plays the copy itself)' : ''));
      o.checked = true;
      if (m.probe) o.probe = m.probe;
      o.measuredBytes = m.bytes || 0;
      o.slow = true;
      o.neededMbps = Number(needs.toFixed(2));
      o.measuredMbps = Number(got.toFixed(2));
      const headroom = got / needs;
      if (!tooSlow || headroom > tooSlow.headroom) tooSlow = { o, cand, headroom };
      continue;
    }
    if (m) {
      o.checked = true;
      if (m.probe) o.probe = m.probe;
      // What this copy needs and what it was measured at, kept on the answer: the screen can say why it
      // picked this one, and the proxy address can carry the number to every later reader of the same film.
      if (Number.isFinite(needs)) o.neededMbps = Number(needs.toFixed(2));
      if (got != null) o.measuredMbps = Number(got.toFixed(2));
      if (m.cached) o.cached = true;
    }
    out = o; source = cand; break;
  }

  // A copy that is merely slow is a better answer than one that never answered, which is better than a
  // magnet this TV cannot open at all.
  // Nothing cleared the bar, so the question is which failure is most likely to play: the copy that
  // actually moved bytes beats one that never started, and among those that tie, the one with more people
  // sharing it and a smaller file is the one a swarm can carry. The candidate list is already in the
  // household's own preference order and the sort is stable, so a tie leaves the preference alone.
  const scored = [tooSlow, slowGateway].filter(Boolean).map((f) => ({
    f,
    bytes: (f.o && f.o.measuredBytes) || 0,
    seeders: Number(f.cand && f.cand.seeders) || 0,
    size: Number(f.cand && f.cand.sizeBytes) || Infinity,
  }));
  scored.sort((x, y) => (y.bytes - x.bytes) || (y.seeders - x.seeders) || (x.size - y.size));
  const fallback = (scored[0] && scored[0].f) || lastResort;
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

  // A magnet is not a stream. It is an invitation to run a BitTorrent client, and no client of this
  // route is one: the TV passes it to a media decoder that reports "unknown protocol: magnet", and a
  // browser tab cannot open it either. So when the gateway produced HTTP for nothing, say what is
  // actually true — the hub has no copy ready to serve yet, and here is how long to wait — instead of
  // answering 200 with something unopenable and letting the screen call it a broken file. The magnet is
  // still in the answer, for the sake of any client that can use one and for the log.
  if (!/^https?:\/\//i.test(String(out.url || ''))) {
    log('PLAY WARMING', identity, labelOf(source), String(out.url).slice(0, 42));
    return {
      status: 503,
      body: {
        identity, title: kept.title || null, tried,
        error: 'no copy of this is ready to stream from the hub yet',
        warming: true, retryAfterMs: PLAY_RETRY_AFTER_MS,
        magnet: out.url,
        alternatives: candidates.filter((s) => s !== source).slice(0, ALT_MAX),
      },
    };
  }
  // Buffer the film, then start it. A paid service starts instantly because the file already sits on a
  // fast server; the free version of that is to pull a window of the copy into this box's RAM *before*
  // the first frame is promised, and to tell the screen how much of the film that bought. The Play press
  // has already been reading this copy to measure it, so on a warm one this waits for nothing at all.
  if (out.mode === 'gateway' && out.torrent && readahead.cfg().on) {
    const bufWho = { hash: out.torrent.hash, fileId: out.torrent.fileId };
    const buf = readahead.get(bufWho);
    if (buf) {
      // Enough to prove the copy is moving, not a six-second wait in front of a black screen. The
      // cushion keeps filling while the player reads it, so the rest of the pre-roll is free.
      const leftMs = Math.max(0, Math.min(1500, deadline - Date.now() - 400));
      const pb = await readahead.preBuffer(buf, { budgetMs: leftMs });
      const need = ranking.estimateMbps(source, kept);
      out.prebuffer = {
        ms: pb.ms,
        bytes: Math.round(pb.bytes || 0),
        sec: pb.sec,
        neededSec: readahead.cfg().preSec,
        neededMbps: need == null ? null : Number(need.toFixed(2)),
        complete: !!pb.complete,
      };
      log('PLAY PREBUFFER', identity, labelOf(source),
        'MB=' + pb.MB, 'sec=' + (pb.sec == null ? '?' : pb.sec), 'ms=' + pb.ms,
        'complete=' + pb.complete, 'targetMB=' + (pb.targetMB == null ? '?' : pb.targetMB));
      // A gateway link that produced no byte at all is not a stream yet, however honest the ranking was
      // about its peers. Answer "preparing" and leave the reader open; the client asks again a beat later
      // and finds the head already in RAM. A 200 here is what produced "This copy has not started moving
      // yet" on the TV after a twelve-second wait.
      if (!pb.complete && !(Number(out.measuredBytes) > 0) && out.warming !== false) {
        log('PLAY WARMING', identity, labelOf(source), 'prebuffer incomplete, no measured byte');
        return {
          status: 503,
          body: {
            identity, title: kept.title || null, tried,
            error: 'this copy is still being prepared',
            warming: true, retryAfterMs: PLAY_RETRY_AFTER_MS,
            alternatives: candidates.filter((s) => s !== source).slice(0, ALT_MAX),
          },
        };
      }
    }
  }

  if (/^https?:\/\//i.test(out.url)) {
    const h = Object.entries(out.headers || {}).map(([k, v]) => k + ':' + v).join('||');
    out.proxiedUrl = '/app/v1/proxy?u=' + encodeURIComponent(out.url)
      + (h ? '&h=' + encodeURIComponent(h) : '')
      // What this copy has to sustain, carried on the address the player will use, so a reconnect an
      // hour later still knows what "slow" is supposed to mean for this file.
      + (out.mode === 'gateway' && Number.isFinite(out.neededMbps) ? '&need=' + out.neededMbps : '');
    // A torrent is served through the hub, not straight from the engine, for two reasons measured on
    // this TV: (1) a cold copy answers slowly and the player's own HTTP timeout kills the connection
    // while the first bytes are still arriving, and the hub can wait far longer than a TV can; (2) every
    // extra reader on one torrent — the player, a retry, the hub's own read-ahead — makes the engine's
    // single sequential reader jump backwards. One reader, the hub, is the difference between a film and
    // an error. The engine port also stops needing to be reachable from every device in the house.
    if (out.mode === 'gateway' && opts.clientOrigin) {
      out.url = opts.clientOrigin + out.proxiedUrl;
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
  // The codec the picture is in, so the player can say "this browser cannot draw H.265" instead of
  // serving a black screen with sound. Entries written before the probe recorded video have nothing to
  // report, and are left unknown rather than guessed at.
  out.videoCodec = (tracks && tracks.video && tracks.video.codec) || source.videoCodec || null;
  if (decodable.length && out.videoCodec && !decodable.includes(ranking.codecFamily(out.videoCodec))) {
    extraWhy.push('this device cannot decode ' + ranking.codecFamily(out.videoCodec)
      + ', and no copy of this title it can play was found');
  }
  out.audioTracks = ((tracks && tracks.audioTracks) || []).map((t) => ({ ...t }));
  out.subtitleTracks = ((tracks && tracks.subtitleTracks) || []).map((t) => {
    const c = { ...t };
    // A browser is same-origin with the hub, so the relative path the cache stores is enough; a TV is
    // not, and a subtitle URL it can open has to name this hub.
    if (c.url && opts.clientOrigin && !/^https?:/i.test(c.url)) c.url = opts.clientOrigin + c.url;
    return c;
  });
  const wantAudio = remux.streamIndex(opts.audio, 'a');
  const wantSub = remux.streamIndex(opts.sub, 's');
  const byIndex = (list, prefix, idx) => (idx == null ? null : list.find((t) => t.id === prefix + ':' + idx)) || null;
  const byLanguage = (list, raw) => (typeof raw === 'string' && /^[a-z]{2,3}$/i.test(raw) && !/^\d+$/.test(raw)
    ? list.find((t) => t.language === String(raw).toLowerCase()) : null) || null;
  // The household's codes and the file's codes are not always spelled alike, so both sides go through the
  // same normalizer this hub uses for every other language claim it makes.
  const prefAudio = (prefs.resolve(profile).audioLanguages || []).map((c) => media.langOf(c));
  const chosenAudio = byIndex(out.audioTracks, 'a', wantAudio) || byLanguage(out.audioTracks, opts.audio)
    // Nothing asked for: name the track the hub would recommend. The profile's own language order comes
    // first, and only then whatever flag the packager stamped on the file — measured on a torrent whose
    // Italian track carries the container's default bit while its English one sits second, and honouring
    // the file over the household played a Hindi-first viewer an Italian film. The native player takes
    // this as its starting selection, so a Hindi copy starts in Hindi without the viewer knowing the word
    // "audio" exists.
    || out.audioTracks.find((t) => prefAudio.includes(media.langOf(t.language)))
    || out.audioTracks.find((t) => t.default)
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
    out.url = (opts.clientOrigin || 'http://127.0.0.1:' + PORT) + rel;
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
  // A debrid answer is the exception: the provider already has the file, so a torrent with no peers can
  // still be a perfectly good direct stream and must not be labelled down.
  if (!out.debrid && candidates.length && candidates.every((s) => s.kind === 'torrent' && s.seeders === 0)) {
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
  // How long this copy is, stated by the hub rather than guessed at by whoever plays it. A player cannot
  // tell a film that finished from a source that stopped answering: both arrive as a closed connection, and
  // guessing wrong drops the viewer back to the row they came from halfway through a movie. With the length
  // here, an ending that shows up minutes early is recognisable as a stall and gets healed instead.
  // The probe of the file we are about to play wins over the title's metadata: they disagree when the copy
  // is a different cut, and the file on disk is what the client will actually be watching.
  out.durationMs = (out.probe && out.probe.durationMs) || kept.durationMs || null;
  // The kind of thing this is, so a player that arrived by identity (a deep link, a zap) does not have to
  // guess whether it is holding a film or a channel.
  out.type = kept.type || (out.kind === 'hls' ? 'channel' : 'movie');
  out.live = out.type === 'channel';
  out.title = kept.title || null;
  // A provider that returned a URL has already proved the copy exists somewhere reachable, so a source
  // row that said "down" because nobody was seeding it stops being the answer the player sees.
  out.status = out.debrid ? 'available' : (source.status || null);
  out.tagline = source.tagline || null;
  // The one line a source sheet reads. A debrid answer is still ordinary HTTP, but it is worth saying
  // whose server is sending it: this is the difference between "torrent, waiting for peers" and "ready".
  if (out.debrid) {
    const who = out.debrid.providerLabel || 'Debrid';
    out.tagline = (out.tagline ? out.tagline + ' \u00b7 ' : '') + who;
  }
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

async function handle(req, res) {
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

    // A token is an access gate, not a second identity system. The public health answer is the one
    // thing pairing needs before it has a token, and it carries no library, paths or counts.
    if (!authorized(req)) {
      if (path === '/healthz') {
        return send(res, 200, { status: 'ok', hub: HUB_ID, version: HUB_VERSION });
      }
      return send(res, 401, { error: 'authentication required' }, 'application/json', {
        'WWW-Authenticate': 'Basic realm="Scenix", charset="UTF-8"',
      });
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

    // ---- legacy addon-compat (byte-compatible with the predecessor addon) ----
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
        // How long this hub gives an unstarted copy before it admits the peers are not feeding it. The
        // player needs the number to tell "still warming" from "this copy is dead" without guessing.
        remux: { warmingMs: remux.limits.FIRST_BYTE_MS },
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
      const options = await catalogOptions();
      return send(res, 200, {
        // A debrid token is write-only. This shape carries `tokenSet` instead; the settings screen can
        // say "set" or "not set" and never receives the secret back over the network.
        settings: settings.redacted(settings.get()),
        defaults: settings.redacted(settings.DEFAULTS),
        // The resolver's own view: which providers this build loaded, whether one is configured, and
        // what it last did. It cannot contain the token.
        debrid: debrid.status(),
        // The language names are returned so a settings screen can label the codes it is choosing
        // between without carrying its own translation table.
        // One entry per language: this map carries both ISO 639-2/T and the older bibliographic
        // spelling of the same language, and a screen that draws every key shows "French" twice.
        languageNames: ranking.LANG_UI,
        // The rows this hub can put on a home screen, so a settings screen offers an owner the choice
        // over exactly what exists here rather than a guessed-at list. A backend that hosts no rows of
        // its own simply omits the key, and the control does not appear anywhere.
        rows: registry.rows.map((r) => ({
          id: r.id, title: r.title, card: r.card || 'card', scope: r.scope || 'all',
        })),
        // The owner's virtual home rows and the raw material for making more. `catalogOptions` is one
        // entry per mounted provider catalogue, with the genres that catalogue says it publishes, so
        // the screen can offer a real choice on any hub without naming a provider or a genre here.
        categories: settings.get().categories,
        catalogOptions: options,
        // Live TV's published channels and groups, with the owner's current selection state. The
        // channel manager needs the list the playlist published, not the filtered one it shows.
        live: (() => {
          const p = registry.byId['live-tv'];
          return p && typeof p.channelList === 'function'
            ? { channels: p.channelList(), groups: p.channelGroups() }
            : { channels: [], groups: [] };
        })(),
        file: settings.FILE,
      });
    }
    if (path === '/app/v1/settings' && req.method === 'PUT') {
      const raw = await readBody(req);
      if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
        return send(res, 400, { error: 'expected a JSON object of settings' });
      }
      // Two shapes reach this route: the settings object itself, or that object wrapped in
      // `{ settings: ... }`, which is what our own screens send. Merging the wrapper as if it were one
      // more setting meant every change made from the interface was filed under a key nothing reads, and
      // the hub answered 200 without changing anything - a settings screen that looked saved and wasn't.
      const body = (raw.settings && typeof raw.settings === 'object' && !Array.isArray(raw.settings))
        ? raw.settings : raw;
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
      } else if (live && typeof live.reselect === 'function') {
        // Same playlist, different selection: filtering the list already in memory is enough, and a
        // network fetch here would make a toggle in Settings wait on iptv-org.
        const was = before.live || {};
        const now = next.live || {};
        if (String(was.hidden || '') !== String(now.hidden || '')
          || String(was.groupsHidden || '') !== String(now.groupsHidden || '')
          || String(was.order || '') !== String(now.order || '')) {
          live.reselect();
          effects.liveSelection = 'applied';
        }
      }
      invalidateCaches();
      log('SETTINGS changed', JSON.stringify({
        languages: next.languages.join(',') || '(no filter)',
        hubName: next.hubName,
        playlist: (next.live || {}).playlist || '(none)',
        addons: effects.addonsResynced + ' remounted',
        rows: next.rows.hidden.length + ' hidden, ' + (next.rows.order.length ? 'owner order' : 'plugin order'),
        categories: next.categories.length + ' (' + next.categories.filter((c) => c.enabled !== false).length + ' on)',
        // Provider and on/off only: the token itself must never appear in a log, including this one.
        debrid: next.debrid.enabled
          ? (next.debrid.provider || 'no provider') + ', token ' + (next.debrid.token ? 'set' : 'missing')
          : 'off',
      }));
      return send(res, 200, {
        ok: true,
        settings: settings.redacted(next),
        debrid: debrid.status(),
        effects,
      });
    }

    if (path === '/app/v1/profiles' && req.method !== 'POST') {
      // Every verb but create lands here: a listing is a read, and POST is the one thing this path also
      // means (a new profile), so the method guard is what keeps a create from being answered with a list.
      return send(res, 200, {
        profiles: store.profiles().map((p) => ({
          ...wireProfile(p),
          prefs: prefs.resolve(p),
          rows: registry.rows.filter((r) => rowVisible(r, p)).map((r) => r.id),
        })),
      });
    }

    // Create, change and remove a profile on whichever backend owns the household. A PIN set here is
    // verified by this process and never read back, which is what turns "ask for four digits" from
    // theatre into a gate.
    if (path === '/app/v1/profiles' && req.method === 'POST') {
      const body = await readBody(req);
      /* The ceiling a new profile starts behind is the whole point of the household's locks, so choosing it is
         an adult act: a Kids profile is held to the hub's ceiling by its rules whatever the number on it says,
         and an adult one has no ceiling at all. A hub where nobody has a PIN yet adds freely. */
      if (body && body.kind !== 'kids' && !adultProofOk(body)) return refuseAdult(res, body, 'add a profile');
      const out = store.addProfile(body);
      if (out.error) return send(res, 400, { error: out.error });
      invalidateCaches();
      return send(res, 200, { ok: true, profile: wireProfile(out.profile) });
    }
    m = /^\/app\/v1\/profiles\/([^/]+)$/.exec(path);
    if (m && (req.method === 'PUT' || req.method === 'PATCH')) {
      const body = await readBody(req);
      const patch = {};
      for (const k of ['name', 'kind', 'age', 'languages', 'rules', 'prefs', 'pin', 'pinRequired', 'avatar']) {
        if (body && body[k] !== undefined) patch[k] = body[k];
      }
      // A face is the profile's own choice, and it travels as a path this backend serves, so a TV never
      // needs an image host to draw its start screen.
      if (patch.avatar !== undefined) {
        const v = String(patch.avatar).trim().slice(0, 400);
        if (v && !v.startsWith('/') && !/^https?:\/\//.test(v)) return send(res, 400, { error: 'avatar must be a path this backend serves or an http(s) URL' });
        patch.avatar = v;
      }
      // A PIN and the wish to be asked for one are a single decision, not two fields that happen to sit next
      // to each other. A stored number with no flag is a gate that never opens; the flag without a number is
      // a wall that checks nothing - one profile was left standing behind exactly that when a test PIN was
      // deleted, and an older client could put any household there. So the backend keeps them together and
      // refuses to hold the half-state at all.
      if ('pin' in patch) {
        const v = patch.pin == null ? '' : String(patch.pin).trim().slice(0, 12);
        // The client sends digits; the box keeps a scrypt hash and a per-profile salt, never the digits.
        patch.pinHash = v ? hashPin(v) : null;
        patch.pin = null;
        patch.pinRequired = !!v;
      } else if (patch.pinRequired === true) {
        const held = store.profile(m[1]);
        if (!held || !profileHasPin(held)) {
          return send(res, 400, { error: 'set a PIN before asking for one' });
        }
      }
      /* A lock that anyone who reaches Settings can take off is not a lock. The number already on a
         profile has to be produced before the profile's own settings - its PIN, its mode, its ceiling,
         the rules that decide what it may see - can be moved, so one household member cannot quietly
         change another's gate, and a child cannot lift their own ceiling by posting parameters. */
      /* `store.profile(id)` falls back to the household's first profile when the id is unknown, and a lock is
         judged against the person named in the URL or against nobody: the record is looked up by id here, so a
         request for a profile that does not exist is answered 404 further down instead of being refused with
         somebody else's PIN. */
      const held = store.profiles().find((p) => String(p.id) === String(m[1]));
      /* And the id has to exist. `store.patchProfile` builds a record out of a patch for a profile it has never
         heard of, which is a second way to mint a profile - one that answered to no rule on the create route at
         all, since it was never a create. */
      if (!held) return send(res, 404, { error: 'no such profile', id: m[1] });
      const holdsGate = ['pin', 'pinHash', 'pinRequired', 'kind', 'age', 'rules', 'prefs', 'languages'].some((k) => k in patch);
      if (held && profileHasPin(held) && holdsGate) {
        const given = !body || body.pinCurrent == null ? '' : String(body.pinCurrent).trim();
        if (!pinMatches(held, given)) {
          log('PROFILE LOCK REFUSED', m[1], 'gate change without the current PIN');
          return send(res, 403, { error: 'this profile is locked: its current PIN is needed to change that', locked: m[1] });
        }
      }
      /* That lock only speaks for a profile which has a number of its own. A profile with none — the one a child
         minted this afternoon — can still have its ceiling lifted, so the ceiling is guarded on its own terms as
         well: into the adult band, up past the age a child was held to, or rewriting the filters a Kids profile
         is screened by. Producing that profile's own number answers this too, deliberately: the request carries
         one field, and a change asked for twice would leave a screen holding two answers for it. */
      const ownProof = !!(held && profileHasPin(held) && pinMatches(held, proofGiven(body)));
      if (isAdultChange(held, patch) && !ownProof && !adultProofOk(body)) {
        return refuseAdult(res, body, 'change that profile');
      }
      if (!Object.keys(patch).length) return send(res, 400, { error: 'nothing to change' });
      const updated = store.patchProfile(m[1], patch);
      if (!updated) return send(res, 404, { error: 'no such profile', id: m[1] });
      invalidateCaches();
      return send(res, 200, { ok: true, profile: wireProfile(store.profile(m[1])) });
    }
    if (m && req.method === 'DELETE') {
      /* Removing a person takes their gate down with them, so the number on the profile has to be produced to
         remove it. Without this, every lock in this file could be undone by a DELETE that asks nothing — a child
         cannot lift their own ceiling, but they could delete the profile that had it and start a new one.
         `store.profile(id)` falls back to the household's first profile when the id is unknown, so the lock is
         looked up by id in the list itself: the number compared has to be the one on the person being removed. */
      const body = await readBody(req);
      const held = store.profiles().find((p) => String(p.id) === String(m[1]));
      if (held && profileHasPin(held)) {
        const given = !body || body.pinCurrent == null ? '' : String(body.pinCurrent).trim();
        if (!pinMatches(held, given)) {
          log('PROFILE LOCK REFUSED', m[1], 'removal without the current PIN');
          return send(res, 403, { error: 'this profile is locked: its current PIN is needed to remove it', locked: m[1] });
        }
      } else if (!adultProofOk(body)) {
        /* A profile with no number on it has none to produce, which is exactly the profile a child can add in the
           morning and erase at night — the evidence of an afternoon spent in somebody else's ceiling. So removal
           is an adult act here too, answered by the number of an adult profile, and a hub where nobody has one
           still lets the household tidy itself freely. */
        return refuseAdult(res, body, 'remove a profile');
      }
      const out = store.removeProfile(m[1]);
      if (out.error) return send(res, 400, { error: out.error });
      invalidateCaches();
      return send(res, 200, { ok: true, removed: out.removed });
    }
    m = /^\/app\/v1\/profiles\/([^/]+)\/unlock$/.exec(path);
    if (m && req.method === 'POST') {
      if (!unlockAllowed(m[1])) {
        return send(res, 429, { error: 'too many attempts; wait a few minutes', retryAfterMs: 300000 });
      }
      const body = await readBody(req);
      const held = store.profile(m[1]);
      const asked = String((body && body.pin) || '');
      if (!held || !profileHasPin(held)) return send(res, 200, { ok: true, unlocked: m[1], noPin: true });
      if (pinMatches(held, asked)) {
        unlockSucceeded(m[1]);
        return send(res, 200, { ok: true, unlocked: m[1] });
      }
      unlockFailed(m[1]);
      return send(res, 403, { error: 'wrong PIN', unlocked: null });
    }

    if (path === '/app/v1/home') {
      const profile = store.profile(u.searchParams.get('profile') || u.searchParams.get('profileId'));
      const ctx = makeCtx(profile, HEALTH_BUDGET_MS);
      // Rows build in parallel, each inside the home budget: /app/v1/home never waits on a slow
      // upstream, it answers from addon cache and degrades to a shorter row. The owner's categories are
      // virtual rows built the same way and appended after the plugin rows, so a category from a slow
      // or removed addon becomes an empty row here and is dropped below like any other.
      // `home: false` marks a row as a section rather than a shelf: the Movies and Series rows answer
      // the nav tab and /app/v1/row/<id>, but Home keeps its curated shape without two more strips of
      // the entire library.
      const rowsToBuild = registry.rows.filter((r) => rowVisible(r, profile) && r.home !== false)
        .concat(enabledCategoryRows().filter((r) => rowVisible(r, profile)));
      const built = await Promise.all(
        rowsToBuild.map(async (row) => ({
          id: row.id,
          title: row.title,
          card: row.card,
          order: row.order,
          scope: row.scope || 'all',
          items: generalItems(row.id, await buildRow(row, ctx, HOME_BUDGET_MS)).slice(0, HOME_ROW_MAX),
        })),
      );
      // Owner order, applied once the rows exist: names in `rows.order` come first in that sequence and
      // everything else keeps the order its plugin asked for, so a hub gains a row plugin and the screen
      // still places it sensibly without the owner having to re-list every row.
      const wanted = (settings.get().rows || {}).order || [];
      if (wanted.length) {
        const rank = new Map(wanted.map((id, i) => [id, i]));
        built.sort((a, b) => (rank.has(a.id) ? rank.get(a.id) : wanted.length)
          - (rank.has(b.id) ? rank.get(b.id) : wanted.length));
      }
      // Then one pass over the finished screen: billboard to the front, and not the same title twice
      // (see arrangeHome). Every row is already built and ruled, so this costs nothing but a Set.
      // A row that ends up with nothing in it is not a shelf with no posters on it: it is a heading over
      // an empty strip, which is what "the tiles below are empty" really was. A cold addon cache, a strict
      // language allowlist or a maturity rule can each empty a row, and the honest answer is that this
      // screen has no such row right now.
      const filled = built.filter((r) => r.items.length);
      if (filled.length !== built.length) {
        log('HOME', 'dropped empty row(s):', built.filter((r) => !r.items.length).map((r) => r.id).join(','));
      }
      return send(res, 200, { rows: arrangeHome(filled) });
    }

    m = /^\/app\/v1\/row\/([^/]+)$/.exec(path);
    if (m) {
      // Plugin rows come from the registry; a category is a virtual row that exists only in settings.
      // Both answer the same shape, which is what lets "See all" open an owner-made shelf too.
      const row = registry.rows.find((r) => r.id === m[1]) || categoryById(m[1]);
      if (!row) return send(res, 404, { error: 'no such row', rowId: m[1] });
      const profile = store.profile(u.searchParams.get('profile') || u.searchParams.get('profileId'));
      if (!rowVisible(row, profile)) {
        return send(res, 403, { error: 'row not for this profile', rowId: row.id, scope: row.scope });
      }
      const ctx = makeCtx(profile, HEALTH_BUDGET_MS);
      return send(res, 200, {
        id: row.id, title: row.title, card: row.card, order: row.order, scope: row.scope || 'all',
        items: generalItems(row.id, await buildRow(row, ctx)),
      });
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
      const items = generalItems('search', await pipeline(all, ctx));
      return send(res, 200, { q, items: items.slice(0, SEARCH_MAX) });
    }

    m = /^\/app\/v1\/meta\/(.+)$/.exec(path);
    if (m) {
      const identity = m[1];
      const profile = store.profile(u.searchParams.get('profile') || u.searchParams.get('profileId'));
      // A viewer opening a detail page must not wait on a slow addon, so this route answers inside
      // META_BUDGET_MS and serves a cached/partial item when the upstream call is still running. That
      // is the right trade for a person holding a remote and the wrong trade for a background learner:
      // a learner that keeps being served the partial item never teaches the hub anything, which is how
      // a catalogue of 1500 titles ended up with 21 known languages. `?budgetMs=` buys a longer wait for
      // exactly that job, clamped so a typo cannot make the hub wait on an addon forever.
      const askedBudget = Number(u.searchParams.get('budgetMs'));
      const budget = Number.isFinite(askedBudget) ? Math.min(20000, Math.max(300, askedBudget)) : META_BUDGET_MS;
      const ctx = makeCtx(profile, budget);
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
      // The cast list is the one place a detail page needs a face rather than a name. It is cached per
      // title forever, so this costs a round trip once; a slow network falls back to the addon's names.
      if (kept) {
        const faces = await castApi.enrich(kept, Math.min(2200, budget));
        if (faces && faces.length) kept.cast = faces;
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
        for (const s of list || []) {
          // The community protocol names a subtitle and states its language, but never its container, and a
          // decoder has to be told one. The extension inside the name the addon chose is the only honest
          // signal available; when it is missing `format` stays absent rather than being guessed at.
          const ext = /\.(srt|ass|ssa|vtt)\b/i.exec(String(s.name || ''));
          found.push({ ...s, ...(ext ? { format: ext[1].toLowerCase() } : {}), addon: p.name || p.id });
        }
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
    // ---- faces ---------------------------------------------------------------------------
    // Character pictures, cached here, plus a proxy for whatever generated-avatar service the owner
    // configured. A TV asks its own hub for both and never learns a third-party hostname.
    if (path === '/app/v1/avatars/characters') {
      const ask = u.searchParams.get('refresh') === '1';
      const list = await avatars.characters({
        limit: Number(u.searchParams.get('limit') || 40),
        refresh: ask,
        wait: ask, // only an explicit refresh waits on the image host
        seed: u.searchParams.get('seed') || '',
      });
      avatars.warm(); // whatever is missing lands in the background, never in this request
      return send(res, 200, { characters: list });
    }
    m = /^\/app\/v1\/avatars\/character\/([a-z0-9-]+)$/.exec(path);
    if (m) {
      const img = avatars.characterImage(m[1]);
      if (!img) return send(res, 404, { error: 'no cached face', id: m[1] });
      res.writeHead(200, { ...corsHeaders(), 'content-type': img.type, 'cache-control': 'public, max-age=86400' });
      return res.end(img.buf);
    }
    m = /^\/app\/v1\/avatars\/proxy\/(.+)$/.exec(path);
    if (m) {
      const got = await avatars.proxy(m[1], u.searchParams.toString());
      if (got.error) return send(res, got.disabled ? 503 : 502, { error: got.error });
      res.writeHead(200, { ...corsHeaders(), 'content-type': got.type, 'cache-control': 'public, max-age=86400' });
      return res.end(got.buf);
    }
    if (path === '/app/v1/avatars/warm' && req.method === 'POST') {
      const n = await avatars.warm();
      return send(res, 200, { ok: true, faces: n });
    }

    // What this backend cached from upstream, on purpose: an addon whose address or filters just changed
    // should not keep serving hour-old answers, and "clear the cache" is the first thing a support
    // conversation reaches for. Standalone hosts answer the same route, so one UI can offer one button.
    if (path === '/app/v1/cache/clear' && req.method === 'POST') {
      // Everything this process remembers about the outside world, not just the addon responses: a stale
      // track listing or a link written off last week is just as much cache as a catalog page, and an
      // owner pressing "clear" cannot tell which of the three the button actually meant.
      const cleared = addons.clearCache();
      const tracks = media.clearCache();
      const links = probe.clearCache();
      invalidateCaches();
      return send(res, 200, { ok: true, cleared, tracks, links });
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
      // The web settings screen speaks GB directly rather than bytes, and its patch travels inside a copy
      // of the last GET response — which carries the *previous* maxFileSizeBytes. Read the plain field
      // after the derived ones so the change someone just made wins over the byte echo they didn't touch.
      if (src.maxSizeGB !== undefined && src.maxSizeGB !== null && Number.isFinite(Number(src.maxSizeGB))) {
        out.maxSizeGB = Math.max(0, Number(src.maxSizeGB));
      }
      const rate = Number(src.maxBitrate || src.maxBitrateBps || 0);
      if (rate > 0) out.maxBitrateMbps = rate > 1000 ? Number((rate / 1e6).toFixed(2)) : rate;
      if (src.maxBitrateMbps) out.maxBitrateMbps = Number(src.maxBitrateMbps);
      return out;
    };
    if (path === '/app/v1/prefs' && req.method === 'POST') {
      const body = await readBody(req);
      const wanted = (body && (body.profile || body.profileId)) || u.searchParams.get('profile') || u.searchParams.get('profileId') || '';
      if (!wanted) return send(res, 400, { error: 'profile required' });
      const current = store.profile(wanted);
      if (!current || !current.id) return send(res, 404, { error: 'no such profile', profile: wanted });
      const patchPrefs = fromWire(body);
      /* The dark-red zone is a switch the household turns on, and while it is off rules/maturity.js hides
         tier-3 titles from everyone - including a profile that has no number of its own. Switching it on is an
         adult act for the same reason adding an adult profile is. Switching it off is free: nobody is punished
         for tightening their own shelf, and a grown-up turning it off at the end of the night should not be
         stopped by a number they cannot remember. */
      if (patchPrefs.xxxEnabled === true && !prefs.resolve(current).xxxEnabled && !adultProofOk(body)) {
        return refuseAdult(res, body, 'turn the adult switch on for that profile');
      }
      const saved = store.patchProfile(current.id, { prefs: { ...(current.prefs || {}), ...patchPrefs } });
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
        { exclude: body.exclude, reprobe: body.reprobe === true, clientOrigin: origin(req),
          season: body.season, episode: body.episode,
          container: body.container, audio: body.audio, sub: body.sub,
          // Where the viewer already is: the hub measures and pre-buffers the part of the file they are
          // about to watch, not the part of it they finished an hour ago.
          positionMs: body.positionMs,
          // Which video codecs this device can draw. Not a preference and never inferred here: the client
          // states it, and it decides which copies are worth offering this screen at all.
          codecs: body.codecs });
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
        { exclude: body.exclude, reprobe: body.reprobe !== false, clientOrigin: origin(req),
          season: body.season, episode: body.episode,
          container: body.container, audio: body.audio, sub: body.sub,
          // Where the viewer already is, so the hub measures and pre-buffers the part of the file they are
          // about to watch instead of the part they watched an hour ago.
          positionMs: body.positionMs,
          codecs: body.codecs });
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
      // The answer carries what the hub is doing with the bytes of the film this profile is watching:
      // the rate the swarm is really delivering against the bitrate the copy needs, and how many seconds
      // of picture are sitting in RAM in front of the screen. A client that asks every
      // playback.progressPostMs gets the downgrade decision handed to it instead of having to discover a
      // frozen frame on its own.
      return send(res, 200, { ok: true, identity: body.identity, progress, stream: readahead.live() });
    }

    return send(res, 404, { error: 'not found', path });
  } catch (e) {
    log('REQUEST FAIL', path, e.stack || e.message);
    return send(res, 500, { error: String(e.message || e) });
  }
}

// A certificate pair turns the hub into an https server directly. A household that puts the hub behind
// a TLS terminator instead still gets the right scheme from `core/origin.js`, because the proxy is the
// machine the television actually talks to.
const server = tlsOptions ? https.createServer(tlsOptions, handle) : http.createServer(handle);

server.listen(PORT, '0.0.0.0', () => {
  log(HUB_ID, HUB_VERSION, 'on ' + SCHEME + '://0.0.0.0:' + PORT + '  (clients: /app/v1/home?profile=<id>)');
  // ffmpeg reads a torrent through this hub's own proxy so the cushion sits in front of it too; it needs
  // to know the address to loop back to.
  readahead.setSelfOrigin(SCHEME + '://127.0.0.1:' + PORT);
  log('addons:', registry.providers.map((p) => p.id + (p.native ? '' : '@' + p.priority)).join(', '));
  log('rows:', registry.rows.map((r) => r.id + '/' + (r.scope || 'all') + '@' + r.order).join(', '));
  log('rules:', registry.rules.map((r) => r.id + '@' + r.order).join(', '));
  log('gateway:', gateway.status().mode, '-', gateway.status().note);
  {
    const d = debrid.status();
    log('debrid:', d.enabled ? ((d.providerLabel || d.provider || 'no provider') + ' - ' + d.note) : d.note);
  }
  {
    const ra = readahead.cfg();
    log('readahead:', ra.on
      ? Math.round(ra.maxBytes / 1048576) + 'MB or ' + ra.maxSec + 's of media per copy, '
        + ra.copies + ' copies, pre-roll ' + ra.preSec + 's/' + Math.round(ra.preMB / 1048576) + 'MB'
        + ' (' + ra.preMs + 'ms max), rate sample ' + ra.sustainMs + 'ms in ' + ra.windowMs
        + 'ms windows, downgrade after ' + ra.starvedWindows + ' slow windows'
      : 'off (playback.readAhead=false) - torrent bytes are piped straight to the player');
  }
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
  discovery.start(PORT, SCHEME);
  log('remux:', remux.status().binFound
    ? (remux.status().binVersion || 'ffmpeg') + ' at ' + remux.status().bin + ' - fragmented MP4 and HLS, '
      + remux.status().max + ' concurrent, video always copied'
    : 'NO ffmpeg (' + remux.status().binNote + ') - container "mp4"/"hls" answer 503, native playback unaffected');
  // Addons warm their own caches on their own schedule (see each addon's start()).
  // Kick the legacy compat load in the background too, exactly as the old addon did on listen.
  for (const p of compatProviders()) ensureCompat(p);
  // Faces are the first thing a household sees, before they have watched a single thing, so the pictures
  // are fetched at boot and kept. A profile screen then answers from this box in milliseconds.
  avatars.warm();
  // Genre shelves read whatever the addons have already said. On a fresh process that is nothing, so the
  // first home page a household sees after a restart is missing Action, Comedy and Horror entirely, and it
  // only fills in once someone happens to look at them. Warm the genres the addons themselves declare, in
  // the background, never inside a request.
  addons.warmGenres()
    .then((n) => { if (n) console.log(`genres: ${n} catalog pages warmed from what the addons declare`); })
    .catch(() => {});
});
