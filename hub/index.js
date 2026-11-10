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
 * Content enters the hub ONLY as an addon (see ADDONS.md): a native one is a file
 * in providers/ scanned at boot, an installed one is a row in data/addons.json hot-mounted at
 * runtime through POST /app/v1/addons. Adding a feature never means editing this file.
 *
 * Node built-ins only: http, https, url, zlib. No npm packages, no framework, no database.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');
const crypto = require('crypto');
const zlib = require('zlib');
const { URL, URLSearchParams } = require('url');

const ROOT = __dirname;
// This must run before the cores below are imported: most choose their state files at module load.
const offlinePreview = require('./core/offline-preview').prepare(ROOT);
const OFFLINE_PREVIEW = offlinePreview.enabled;
const HUB_BIND = offlinePreview.bind;
const THEME_DIR = path.join(ROOT, 'web', 'assets');
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
const store = require('./core/store').create(ROOT, OFFLINE_PREVIEW ? {
  dataDir: offlinePreview.dataDir,
  profilesSeedFile: offlinePreview.profilesSeedFile,
} : undefined);
const merge = require('./core/merge');
const shelves = require('./core/catalogs');
const taste = require('./core/taste');
const prefs = require('./core/prefs');
const gateway = require('./core/gateway');
const liveHealth = require('./core/live-health');
// The wall's own reader: /app/v1/live/channels and rows/live.js must hand out the same channels, so
// they ask the same question of the same providers.
const liveRow = require('./rows/live');
const channelItems = liveRow.channelItems;
// Optional: a paid resolver, off unless the owner configures a provider and a token. It is tried before
// the gateway and is never a hard dependency of the play path.
const debrid = require('./core/debrid');
const addons = require('./core/addons');
const reviewedAddons = require('./core/reviewed-addons');
const communityDirectory = require('./core/community-directory');
const sourceSetupCore = require('./core/source-setup');
const sourceSetup = sourceSetupCore.create();
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
const ladder = require('./core/ladder');
const fingerprint = require('./core/fingerprint');
const introdb = require('./core/introdb');
// The hub's own read-ahead: one bounded cushion of torrent bytes per copy, shared by the player, the
// remuxer and the Play press that measures the copy before promising it. See core/readahead.js.
const readahead = require('./core/readahead');
// The hub keeping itself current: this checkout is the repo, so a fast-forward pull is its release
// channel and one route lets the TV, the browser and the hub's own timer all ask for it. See core/update.js.
const hubupdate = require('./core/update');
// What the owner chose (core/settings.js) and what this hub says about itself on the LAN
// (core/discovery.js). Both are read when a request asks, so a change made in the hub's own UI lands on
// the next request: a setting that needs a restart to take effect is a setting nobody really made.
const settings = require('./core/settings');
const langdb = require('./core/langdb');
const avatars = require('./core/avatars');
const discovery = require('./core/discovery');
const gamesApi = require('./core/games');
const maturityRule = require('./rules/maturity');
const ranking = require('./rules/source_ranking');


// Home must answer from cache: /app/v1 never waits on the network for more than ~1.5 s.
// A cold hub used to answer Home inside 1.5 s and drop every row whose addon was still waking up:
// the first screen after a restart was missing Continue, the installed-addon shelf or the billboard.
// Three seconds is the point where those rows are in the answer on a cold cache; after that the
// addon caches make Home fast again. The client shows its skeleton, not a half-empty page.
const HOME_BUDGET_MS = Number(process.env.HOME_BUDGET_MS || 3000);
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
// How many copies one press is allowed to check, in ranked order, before it gives up. This used to
// be 2 "to bound the walk", but the walk is already bounded by PLAY_TOTAL_BUDGET_MS (the deadline
// check inside it stops trying when the press is out of time). Two seats let a title with 13 copies
// fail on two junk links: an aggregator's wrong-title answer and one dead hoster link occupied both
// seats while five working torrent copies waited untried below. Seats only decide how far down the
// ranking a press may reach; the clock still decides how many it can actually afford.
const PLAY_MAX_TRIES = Number(process.env.PLAY_MAX_TRIES || 6);
// Wall-clock the whole picker walk may spend before it answers. The walk is also what gives a torrent
// its first 256 KB, so on a title whose HTTP copies are all dead this is the time it takes the engine
// to prove a swarm works - 5 s was measured too tight for one engine mint plus proof.
const PLAY_TOTAL_BUDGET_MS = Number(process.env.PLAY_TOTAL_BUDGET_MS || 8000);
// How long a warming title asks the player to wait before pressing again. The hub says it rather than
// the client guessing, because only the hub knows whether it is talking to an engine that is awake.
const PLAY_RETRY_AFTER_MS = Number(process.env.PLAY_RETRY_AFTER_MS || 2000);
// How many presses in a row a gateway copy may answer with not one byte before the hub stops calling it
// "still being prepared" and says what it actually knows. Measured on this box: a 720p copy measured 0
// Mbps over 4 s and produced nothing in the 1.5 s pre-roll either, so the hub answered `warming` and the
// television pressed again every 7.6 s — nine identical refusals, ~70 s of "the hub is preparing this
// copy", in front of a copy that had never handed over a single byte. The retry is what warms a cold
// torrent, so it is allowed twice; after that the same open read hearing nothing again is evidence.
const PLAY_NOBYTE_TRIES = Number(process.env.PLAY_NOBYTE_TRIES || 2);
// Presses further apart than this are separate attempts rather than one retry loop, so each gets its
// evidence again: a swarm can gain a peer at any moment.
const PLAY_NOBYTE_WINDOW_MS = Number(process.env.PLAY_NOBYTE_WINDOW_MS || 90000);
const GATEWAY_DELIVER_MS = Number(process.env.GATEWAY_DELIVER_MS || 1500);
// A direct/adaptive addon can mint its playable URL at play time, so the hub has no measurement of that
// exact link and cannot rank it honestly. One short check catches a service page answering 200 before a
// player does; this many more ranked copies are woken when that check refuses one.
const PLAY_VALIDATE_TIMEOUT_MS = Number(process.env.PLAY_VALIDATE_TIMEOUT_MS || 1200);
// A blank environment variable is not a zero: `Number('')` is 0 and finite, so the naive guard let a
// stray `PLAY_VALIDATE_RETRIES=` in a service file silently mean "no mint retries at all".
const PLAY_VALIDATE_RETRIES = String(process.env.PLAY_VALIDATE_RETRIES ?? '').trim() !== ''
    && Number.isFinite(Number(process.env.PLAY_VALIDATE_RETRIES))
  ? Math.max(0, Math.min(5, Number(process.env.PLAY_VALIDATE_RETRIES))) : 2;
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

// What this line can carry, learned from the bytes it actually delivered. Measured here: plain HTTP
// download reaches 70+ Mbps while BitTorrent on the same box and the same trackers delivers around
// 1 Mbps, so a 4K copy needing 8 Mbps is not a quality choice, it is a broken promise. The picker feeds
// these numbers back into its own ordering, which is what "Auto" quality on a paid app really means —
// the sharpest copy the connection can feed, worked out by measurement. Two numbers, not one average:
// an addon host's speed says nothing about what a swarm will carry, and one fast HTTP link mixing into
// the swarm figure demoted 4K torrents that the box happily streamed all evening.
let directMbps = null;   // measured through core/readahead.js against a direct/adaptive host
let swarmMbps = null;    // measured through the torrent engine
function linkMbpsNow() {
  const v = [directMbps, swarmMbps].filter((x) => Number.isFinite(x));
  return v.length ? Math.max.apply(null, v) : null;
}
const GATEWAY_FIT = 0.85; // keep 15% of the measured pipe for the player's own buffer
// A play decision is the one place a page view is allowed to touch the internet: the hub measures the
// links it is about to hand over, but never waits more than this for the measurement (core/probe.js
// keeps probing in the background so the NEXT view of the same title is answered from cache).
const PLAY_PROBE_BUDGET_MS = Number(process.env.PLAY_PROBE_BUDGET_MS || probe.limits.BUDGET_MS);
const PLAY_PROBE_MAX = Number(process.env.PLAY_PROBE_MAX || 6); // measured candidates per decision
const DAYS = Number(process.env.DAYS || 45); // "just added" window
const THEME_ID = process.env.THEME_ID || 'scenix-apple';
const HEALTH_CACHE_MS = 15000;
const UPSTREAM_UA = process.env.ADDON_UA || 'Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 ScenixHub/0.1';

const log = (...a) => console.log(new Date().toISOString(), ...a);

// This is surfaced only on the loopback preview's health response.  It gives the focused test a
// machine-readable proof that the startup lane did not quietly start a provider, compat, avatar or
// genre job.  Production never creates or reports this object.
const previewTelemetry = OFFLINE_PREVIEW ? {
  enabled: true,
  bind: HUB_BIND,
  dataDir: offlinePreview.dataDir,
  externalNetwork: false,
  readOnly: true,
  warmups: { providerStarts: 0, compatibility: 0, avatars: 0, genres: 0 },
  skipped: { providerStarts: 0, compatibility: 0, avatars: 0, genres: 0 },
} : null;

// ---- plugin lifecycle -----------------------------------------------------------------------------

for (const p of registry.providers) {
  if (OFFLINE_PREVIEW) {
    previewTelemetry.skipped.providerStarts++;
    continue;
  }
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
const addonBoot = addons.init(registry, store, { offlinePreview: OFFLINE_PREVIEW });
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
    audioMix: remux.status().binFound === true, // a donor audio stream can be muxed into the picture
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
    hubUpdate: true, // this backend can pull its own newest build (core/update.js); a copied
                     // folder with no git still reports itself honestly through that route
  };
  if (OFFLINE_PREVIEW) {
    // The preview is a renderer inspection surface, not an alternate household backend.  Do not let a
    // browser imply that it can change state, install a source, fetch a face or start playback.
    Object.assign(caps, {
      gateway: false,
      live: false,
      localFiles: false,
      remux: false,
      tracks: false,
      audioMix: false,
      announce: false,
      search: false,
      subtitles: false,
      settingsAdmin: false,
      addonAdmin: false,
      prefsEdit: false,
      profileEdit: false,
      cacheAdmin: false,
      avatarFaces: false,
      preview: true,
    });
  }
  // A flag only when the resolver can actually do something. Off, unconfigured or missing a token means
  // the capability is absent, exactly like an uninstalled addon: the clients need no "debrid is off" state.
  if (d.ready === true) caps.debrid = true;
  // The ladder exists only when this build has ffmpeg; the setting still decides whether a play uses it.
  if (remux.status().binFound === true) caps.adaptive = true;
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
 * The optional modes this household actually installed. Live TV is the live-tv add-on with a playlist
 * pointing somewhere real, and Games is an enabled add-on that declares the games resource — the tab is
 * a door to a room the household built, so a hub that never had either must not draw one. Presence here
 * is a fact about the install, never about which build the client is talking to.
 */
async function navCaps() {
  const list = await addons.list();
  const games = list.some((a) => a.enabled !== false &&
    Array.isArray(a.resources) && a.resources.includes('games'));
  return { live: capabilities().live, games };
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

// A successful PIN entry grants a short-lived, client-bound capability. The old implementation
// returned `{ok:true}` but never attached that proof to later writes, so a direct request could
// change household settings while the on-screen PIN pad was locked.
const ADMIN_SESSION_HEADER = 'x-scenix-admin-session';
const ADMIN_SESSION_TTL_MS = Math.max(60e3, Math.min(60 * 60e3,
  Number(process.env.ADMIN_SESSION_TTL_MS || 30 * 60e3) || 30 * 60e3));
const adminSessions = new Map(); // opaque token -> { client, expiresAt }

function adminLockActive() {
  const lock = settings.get().lock || {};
  return lock.enabled === true && typeof lock.pinHash === 'string' && lock.pinHash.length > 0;
}

function adminClientKey(req) {
  const address = String((req && req.socket && req.socket.remoteAddress) || '');
  const userAgent = String((req && req.headers && req.headers['user-agent']) || '').slice(0, 512);
  // A bearer hub token is already a client secret. Hash it into the binding rather than keeping it
  // in session memory or returning it in an error/log.
  const authorization = String((req && req.headers && req.headers.authorization) || '');
  return crypto.createHash('sha256').update(address + '\n' + userAgent + '\n' + authorization).digest('hex');
}

function pruneAdminSessions(now = Date.now()) {
  for (const [token, session] of adminSessions.entries()) {
    if (!session || session.expiresAt <= now) adminSessions.delete(token);
  }
}

function mintAdminSession(req) {
  pruneAdminSessions();
  const token = crypto.randomBytes(32).toString('hex');
  const expiresAt = Date.now() + ADMIN_SESSION_TTL_MS;
  adminSessions.set(token, { client: adminClientKey(req), expiresAt });
  return { token, expiresAt };
}

function validAdminSession(req) {
  const token = String((req && req.headers && req.headers[ADMIN_SESSION_HEADER]) || '').trim();
  if (!/^[a-f0-9]{64}$/i.test(token)) return false;
  const session = adminSessions.get(token);
  if (!session || session.expiresAt <= Date.now()) {
    if (session) adminSessions.delete(token);
    return false;
  }
  return safeEqual(session.client, adminClientKey(req));
}

function clearAdminSessions() { adminSessions.clear(); }

/** Enforce the server-side half of the household lock only while it is enabled. */
function requireAdminSession(req, res) {
  if (!adminLockActive() || validAdminSession(req)) return true;
  send(res, 403, {
    error: 'the household settings PIN is needed',
    gate: 'settings-lock',
    sessionRequired: true,
  });
  return false;
}

/** Preview is a renderer-only lane: sources may be inspected there but never altered or fetched. */
function rejectPreviewSourceMutation(res) {
  if (!OFFLINE_PREVIEW) return false;
  send(res, 403, {
    error: 'offline preview is read-only',
    code: 'preview-read-only',
  });
  return true;
}

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
  if (OFFLINE_PREVIEW) {
    previewTelemetry.skipped.compatibility++;
    return null;
  }
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

// ---- the tile cache -------------------------------------------------------------------------------
//
// A row already carries fully resolved items. The detail and play routes used to throw that away and
// ask every addon again with a few hundred milliseconds; a slow catalogue addon (DesiFlix) then turned
// a tile the viewer could see into "not found". Keeping the tile by identity makes the detail page
// answer with the same object the row showed, and the provider pass below only enriches it.
const ITEM_CACHE_MAX = Number(process.env.ITEM_CACHE_MAX || 6000);
const ITEM_CACHE_FILE = path.join(process.env.DATA_DIR || path.join(__dirname, 'data'), 'item-cache.json');
const itemCache = new Map();

(function loadItemCache() {
  try {
    const raw = JSON.parse(fs.readFileSync(ITEM_CACHE_FILE, 'utf8'));
    let n = 0;
    for (const [id, it] of Object.entries(raw && typeof raw === 'object' ? raw : {})) {
      if (it && it.identity) { itemCache.set(String(id), it); n++; }
    }
    while (itemCache.size > ITEM_CACHE_MAX) itemCache.delete(itemCache.keys().next().value);
    if (n) log('ITEM cache restored', n, 'tiles from', ITEM_CACHE_FILE);
  } catch (e) {
    if (e.code !== 'ENOENT') log('ITEM cache restore failed', e.message);
  }
})();

let itemCacheSave = null;
function scheduleItemCacheSave() {
  if (itemCacheSave) clearTimeout(itemCacheSave);
  itemCacheSave = setTimeout(() => {
    itemCacheSave = null;
    try {
      fs.mkdirSync(path.dirname(ITEM_CACHE_FILE), { recursive: true });
      const out = {};
      for (const [id, it] of itemCache) out[id] = it;
      const tmp = ITEM_CACHE_FILE + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(out));
      fs.renameSync(tmp, ITEM_CACHE_FILE);
    } catch (e) { /* a tile that fails to persist cold-reopens once; the row will remember it again */ }
  }, 800);
  if (itemCacheSave.unref) itemCacheSave.unref();
}

function rememberItems(items) {
  if (!Array.isArray(items)) return;
  let changed = false;
  for (const it of items) {
    if (!it || !it.identity) continue;
    // A row re-resolving a show from its thin catalog tile knows less than the detail page that
    // already adopted the sibling's 4,862 episodes. Replacing every other field is right; the
    // episode list never shrinks. Measured live: this overwrite is how a restart restored the thin
    // record and the first open after it said "Season 1 - 1 episode" again.
    const old = itemCache.get(it.identity);
    if (old && Array.isArray(old.episodes) && old.episodes.length > (it.episodes || []).length)
      it.episodes = merge.unionEpisodes(it.episodes, old.episodes);
    itemCache.delete(it.identity);
    itemCache.set(it.identity, it);
    changed = true;
  }
  while (itemCache.size > ITEM_CACHE_MAX) {
    const first = itemCache.keys().next();
    if (first.done) break;
    itemCache.delete(first.value);
    changed = true;
  }
  if (changed) scheduleItemCacheSave();
}

function cachedItem(identity) {
  const it = itemCache.get(String(identity));
  // Art is normalised on the way out as well as on merge: entries written before the hub stopped
  // publishing TMDB `original` URLs keep their over-sized art in the cache file.
  return it ? merge.normalizeArt(JSON.parse(JSON.stringify(it))) : null;
}

// ---- the row context (what a row's build(ctx) gets) -----------------------------------------------

function makeCtx(profile, metaBudget) {
  const memo = { library: null };
  const ctx = {
    profile,
    store,
    providers: registry.byId,
    rules: registry.rules,
    // id -> priority, the order merge.dedupe keeps a channel or a title from when two providers
    // publish the same identity. A row that reads one class of provider directly (rows/live.js)
    // still has to dedupe like the whole-library walk does.
    priorities: registry.priorities,
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
      const known = cachedItem(identity);
      const parts = known ? [known] : [];
      // Ask the addons together, not one after another: ten providers at 300 ms each made a cold
      // detail page a three-second wait, and the route's own budget then served nothing.
      const askers = registry.providers.filter((p) => typeof p.meta === 'function');
      const raw = askers.map((p) => Promise.resolve().then(() => p.meta(identity, hint)));
      const answers = await Promise.all(
        raw.map((pr, i) => withBudget(pr, metaBudget || 300, null, 'meta ' + askers[i].id)),
      );
      for (const it of answers) if (it) parts.push(it);
      let item = merge.mergeItems(parts, registry.priorities);
      // Nothing answered inside the budget, but at least one addon is still talking to its upstream
      // about this exact title. "Not found" here is a lie the viewer punishes with a second tap — and
      // the second tap works, which is exactly how this bug reads in the living room. The call is in
      // flight, not missing: wait for it to land (capped), then ask once more — that ask is a cache hit.
      if (!item && addons.pendingFor(identity)) {
        const settleMs = Math.min(9000, Math.max(2000, (metaBudget || 300) * 2));
        log('META SETTLE', identity, 'budget=' + (metaBudget || 300) + 'ms', 'waiting=' + settleMs + 'ms',
          'upstreams still answering');
        await Promise.race([
          Promise.allSettled(raw),
          new Promise((r) => { const t = setTimeout(r, settleMs); if (t.unref) t.unref(); }),
        ]);
        const again = await Promise.all(askers.map((p) => withBudget(
          Promise.resolve().then(() => p.meta(identity, hint)), 1500, null, 'meta settle ' + p.id,
        )));
        for (const it of again) if (it) parts.push(it);
        item = merge.mergeItems(parts, registry.priorities);
        if (item) log('META SETTLE', item.identity, 'answered once the upstream landed');
      }
      if (item) {
        store.touch(item.identity);
        // A series is addressed episode by episode. The detail page has to list the sources of the same
        // episode Play will fetch, or the "Source 1..N" menu names torrents from a different episode than
        // the one that starts. The tile's identity stays the show: rows, dedupe and Continue all depend
        // on that.
        if (item.type === 'series') {
          item.progress = store.progressFor(ctx.profile.id, item.identity);
          item.inList = store.inList(ctx.profile.id, item.identity);
          const ep = episodeOf(item, null, null);
          if (ep) item.streamId = episodeStreamId(item, ep);
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
        rememberItems([item]);
      }
      return item;
    },

    /**
     * Metadata only, for a card that is not about to be played. The full resolve below also asks every
     * streams addon and warms every play link, which is right for a detail page and far too much for
     * Continue Watching: twenty of those in a row is how the row missed its budget and disappeared.
     */
    async metaOnly(identity, hint, budget = 400) {
      const parts = [];
      for (const p of registry.providers) {
        if (typeof p.meta !== 'function') continue;
        const it = await withBudget(
          Promise.resolve().then(() => p.meta(identity, hint)), budget, null, 'meta ' + p.id,
        );
        if (it) parts.push(it);
      }
      const item = merge.mergeItems(parts, registry.priorities);
      if (item) {
        item.progress = store.progressFor(ctx.profile.id, item.identity);
        item.inList = store.inList(ctx.profile.id, item.identity);
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
/**
 * A title a rule hid is not a title that does not exist. The app shows these words, so naming the rule
 * is the difference between "this profile can't watch this title" and a status code.
 */
function ruleDropBody(identity, drops) {
  const by = (drops || [])[(drops || []).length - 1];
  const rule = by && by.rule ? by.rule : true;
  const words = {
    'language-allowlist': 'this title is not in the languages this profile allows',
    'maturity': 'this title is above the age this profile allows',
    'kids-allowlist': 'this title is not on the kids list for this profile',
    'size-cap': 'no copy of this title fits the file-size limit this profile uses',
  }[by && by.rule];
  // The household can fix a setting only if they can see what the hub believed.
  const detail = words || 'a hub rule hides this title for this profile';
  const named = by && by.because && by.because.claimed && by.because.claimed.length
    ? detail + ' (it says ' + by.because.claimed.join(', ')
      + '; this profile allows ' + (by.because.allowed || []).join(', ') + ')'
    : detail;
  return {
    identity,
    droppedByRule: rule,
    error: 'dropped by rule',
    detail: named,
  };
}

async function pipeline(items, ctx, drops) {
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
      if (it === null || it === undefined) {
        // A title that vanishes with no name attached to it is the hardest thing in this product to
        // debug, and the viewer deserves better than a 403: one rule said no, and which one it was is
        // the whole answer. `drops` is how the route passes that to the app's message.
        if (drops) drops.push({
          identity: start && start.identity,
          rule: rule.id,
          because: start && start._langDroppedBecause,
        });
        log('DROP BY RULE', (start && start.identity) || '?', rule.id,
          'tier', start && start.tier, 'minAge', start && start.minAge,
          'langs', JSON.stringify((start && start.languages) || null),
          'for profile', ctx.profile && ctx.profile.id);
        break;
      }
    }
    if (!it) continue;
    it.progress = ctx.store.progressFor(ctx.profile.id, it.identity);
    // Whether THIS profile has saved it. One in-memory set lookup per item — every card, row and
    // detail page on every screen (app and browser) can then speak the truth about My List.
    it.inList = ctx.store.inList(ctx.profile.id, it.identity);
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
  // The household's hidden list is final; a profile may also hide a row of its own (one viewer does not
  // want Live TV, another does), but cannot un-hide what the owner removed for everyone.
  const local = (profile && profile.prefs && profile.prefs.rows) || {};
  const hidden = new Set([
    ...(((settings.get().rows || {}).hidden) || []),
    ...(Array.isArray(local.hidden) ? local.hidden : []),
  ]);
  if (hidden.has(row.id)) return false;
  // A billboard is a SHAPE, not a content tier, so every profile gets one — including a child, whose
  // billboard is decided by the rules that run over its items (maturity, then the kids allowlist) rather
  // than by a second hero row for kids. rows/hero.js is scope 'all' and carries no adult-only content.
  if (row.card === 'hero') return true;
  if (profile.kind === 'kids') return scope === 'kids'; // kids: kids rows only
  return scope === 'all' || scope === 'adult';
}

async function buildRow(row, ctx, budgetMs, maxOverride) {
  const raw = await withBudget(
    Promise.resolve().then(() => row.build(ctx)), budgetMs || ROW_BUDGET_MS, [], 'row ' + row.id,
  );
  const items = await pipeline(raw || [], ctx);
  rememberItems(items);
  // Trim after the rules: a row may hand back more candidates than it wants on screen. The section
  // page ("See all") passes a larger cap than the home rail: the rail is a preview, the section is
  // the shelf, and a category page that stops where the rail stopped is the "limited content" bug.
  return items.slice(0, maxOverride || row.max || ROW_MAX);
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
// The section page a "See all" tile opens is the shelf itself, not the rail: deeper, but bounded,
// so one page can never ask an addon for its entire catalogue.
const SECTION_MAX = Number(process.env.SECTION_MAX || 120);
// The live wall is a wall, not a shelf: one playlist publishes 350-400 channels and the remote's zap
// ring has to be able to reach the last one. The budget is wider than a row's because this one is read
// out of the live provider's memory, and the row that used to walk the entire catalogue blew its
// 1200 ms here and answered empty — an empty Live TV tab and a remote that could not change channel.
const LIVE_WALL_MAX = Number(process.env.LIVE_WALL_MAX || 700);
const LIVE_WALL_BUDGET_MS = Number(process.env.LIVE_WALL_BUDGET_MS || 4000);
function generalItems(rowId, items) {
  if (!Array.isArray(items)) return items;
  if (rowId === ADULT_ROW_ID) return items;
  const allowed = items.filter((it) => Number(it && it.tier) !== 3);
  // The same film can reach one row from two providers under two identities — the aggregator's nature.
  // Identities differ, so deduping by identity misses it and a shelf shows the identical poster twice
  // a few tiles apart. Title plus year is what a viewer actually recognises as "the same thing"; keep
  // the first arrival, which is already ordered by the row's ranking.
  const seenYears = new Map();   // lower-cased title -> years already on this row (null = an undated arrival)
  return allowed.filter((it) => {
    if (!it || !it.title) return true;
    const t = String(it.title).trim().toLowerCase();
    const y = it.year == null ? null : String(it.year);
    // Same title plus same year is the same thing twice. A missing year is a hole in one provider's
    // metadata, not evidence of another film: "Home" with no year collides with whatever "Home" years
    // are already there — but dated 1988 and dated 2015 stay apart.
    const ys = seenYears.get(t);
    if (ys && (y === null || ys.has(null) || ys.has(y))) return false;
    if (ys) ys.add(y); else seenYears.set(t, new Set([y]));
    return true;
  });
}

/**
 * What a shelf tile actually is: the fields a card draws. `sources` (every copy of a title) and
 * `episodes` (a whole season list) belong to the detail record, not to a poster — and every client
 * asks for them again by identity when a tile is opened (`/app/v1/meta`). Shipping them on the rail
 * made `/app/v1/home` ~1.9 MB, 83% of it data no card reads; on a 2 GB TV that parse was a visible
 * part of opening Home, and a Continue-watching tile alone could carry sixty episode stills.
 * Home, See all, search and genre lists are tile lists, so they all get the same shelf shape.
 */
function shelfItem(it) {
  if (!it || typeof it !== 'object') return it;
  const { sources, episodes, ...tile } = it;
  // A shelf still tells the truth about depth: the web billboard prints "N seasons" from the tile,
  // and an episode array is the only place that number lived. Compute it before the array is
  // dropped, so the tile keeps the fact without the sixty stills behind it.
  if (Array.isArray(episodes) && episodes.length) {
    const seasons = new Set();
    for (const ep of episodes) seasons.add(ep && ep.season !== undefined ? ep.season : 1);
    tile.seasonCount = seasons.size;
  }
  return tile;
}
function shelfItems(items) {
  return Array.isArray(items) ? items.map(shelfItem) : items;
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
    let chosen = unique.slice();
    // A carousel of one is not a carousel. The billboard's preview strip only exists with two or
    // more slides, and a profile whose shelves overlap the hero pool (or whose rules leave a narrow
    // pool) was collapsing to a single static frame. Uniqueness against the shelves below is a
    // nicety; once it would cost the carousel, fill the remaining slides from the ranked pool even
    // if one of them is also on a shelf.
    if (chosen.length < 2) {
      for (const it of h.items) {
        if (chosen.length >= HERO_MAX) break;
        if (!chosen.includes(it)) chosen.push(it);
      }
    }
    h.items = chosen.slice(0, HERO_MAX);
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
    'Access-Control-Allow-Headers': 'content-type,range,accept-encoding,x-scenix-admin-session',
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
      linkMbps: linkMbpsNow() == null ? null : Number(linkMbpsNow().toFixed(2)),
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
    introdb: introdb.status(),
    // Opt-in transcoded ladder: whether jobs are running, their variants and binary. Empty jobs is the
    // normal state when the setting is off or a title is played copy-only.
    ladder: ladder.status(),
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
    ...(previewTelemetry ? {
      // Keep the preview proof useful without exposing its temporary filesystem directory.
      preview: {
        enabled: true,
        bind: previewTelemetry.bind,
        externalNetwork: false,
        readOnly: true,
        warmups: { ...previewTelemetry.warmups },
        skipped: { ...previewTelemetry.skipped },
      },
    } : {}),
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

/**
 * A local file, served with the same Range semantics as the network proxy so a browser <video> or an
 * ExoPlayer can seek it. This is the other half of `providers/local-files.js`: that provider hands
 * out `/app/v1/proxy?u=file://…`, and without this branch the proxy refused its own provider's URL.
 *
 * Only a path inside a directory a provider declared in `rootDirs` is readable. The query is a path,
 * not a capability, so the check has to be done after resolving `..` and symlinks; comparing the
 * requested string would let `/media/../../etc/passwd` through.
 */
const FILE_MIME = {
  '.mp4': 'video/mp4', '.m4v': 'video/mp4', '.mkv': 'video/x-matroska', '.webm': 'video/webm',
  '.mov': 'video/quicktime', '.avi': 'video/x-msvideo', '.ts': 'video/mp2t', '.m2ts': 'video/mp2t',
  '.mp3': 'audio/mpeg', '.m4a': 'audio/mp4', '.opus': 'audio/ogg', '.flac': 'audio/flac',
};

function localRoots() {
  const roots = [];
  for (const mod of Object.values(registry.byId || {})) {
    for (const dir of (mod && mod.rootDirs) || []) {
      try { roots.push(fs.realpathSync(dir)); } catch (e) { /* a missing root grants nothing */ }
    }
  }
  return [...new Set(roots)];
}

function parseRange(header, total) {
  const m = /^bytes=(\d*)-(\d*)$/.exec(String(header || '').trim());
  if (!m) return null;
  let start;
  let end;
  if (m[1] === '') {
    const suffix = Number(m[2]);
    if (!Number.isFinite(suffix) || suffix <= 0) return { invalid: true };
    start = Math.max(0, total - suffix);
    end = total - 1;
  } else {
    start = Number(m[1]);
    end = m[2] === '' ? total - 1 : Number(m[2]);
  }
  if (!Number.isFinite(start) || !Number.isFinite(end) || start < 0 || end < start) return { invalid: true };
  if (start >= total) return { invalid: true };
  return { start, end: Math.min(end, total - 1) };
}

async function serveLocalFile(req, res, target) {
  let filePath;
  try {
    filePath = decodeURIComponent(new URL(target).pathname);
  } catch (e) {
    return send(res, 400, { error: 'u is not a file url' });
  }
  let real;
  try {
    real = await fs.promises.realpath(filePath);
  } catch (e) {
    return send(res, 404, { error: 'no such file' });
  }
  const inside = localRoots().some((root) => real === root || real.startsWith(root + path.sep));
  if (!inside) return send(res, 403, { error: 'this file is outside every media root' });
  let stat;
  try {
    stat = await fs.promises.stat(real);
  } catch (e) {
    return send(res, 404, { error: 'no such file' });
  }
  if (!stat.isFile()) return send(res, 404, { error: 'not a file' });
  const total = stat.size;
  const type = FILE_MIME[path.extname(real).toLowerCase()] || 'application/octet-stream';
  const base = {
    'Content-Type': type,
    'Accept-Ranges': 'bytes',
    'Cache-Control': 'no-cache',
    ...corsHeaders(),
  };
  const asked = parseRange(req.headers.range, total);
  if (asked && asked.invalid) {
    res.writeHead(416, { ...base, 'Content-Range': 'bytes */' + total });
    return res.end();
  }
  const start = asked ? asked.start : 0;
  const end = asked ? asked.end : total - 1;
  const status = asked ? 206 : 200;
  const headers = { ...base, 'Content-Length': String(end - start + 1) };
  if (asked) headers['Content-Range'] = 'bytes ' + start + '-' + end + '/' + total;
  if (process.env.LOG_REQUESTS === '1') log('REQ', req.method, req.url, '->', status, '(local file)');
  res.writeHead(status, headers);
  if (req.method === 'HEAD') return res.end();
  const stream = fs.createReadStream(real, { start, end });
  stream.on('error', () => res.destroy());
  stream.pipe(res);
}

async function proxyStream(req, res, u) {
  const target = u.searchParams.get('u');
  if (target && /^file:\/\//i.test(target)) return serveLocalFile(req, res, target);
  if (!target || !/^https?:\/\//i.test(target)) return send(res, 400, { error: 'u must be an http(s) or file url' });
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
        detail: 'the P2P engine did not start this copy within ' + waited + 'ms',
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
  const release = gateway.readBegin(hash, fileId);
  // release must fire exactly once per read: `end`, `error` and the response's `close` all overlap on a
  // normal completed response, and readEnd deletes the entry at n<=0 — a double release with a second
  // reader open tells the keep-warm heartbeat nobody is watching, and its offset-0 touch then drags
  // the engine's sequential cache backwards under a mid-film viewer.
  let released = false;
  const done = () => { if (!released) { released = true; release(); } };
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
 * A cushion, addressed.
 *
 * The picker measured a plain internet copy through core/readahead.js and kept the bytes it pulled; this
 * map is the bridge from the token in the player's address back to the origin URL and the headers that
 * origin insists on (`iKey`, `Referer`). Those stay here, where the screen never has to know them, and
 * the TV gets one address that is always a fast local server — which is the whole difference between a
 * copy a rationed host feeds at 2 Mbps on one socket and the same copy playing for two and a half hours.
 *
 * Tokens are per Play press on purpose: a direct addon link is signed and unique per resolve, and a
 * cushion keyed by that URL can never serve someone else's stale bytes. They live as long as a film.
 */
const BUFFER_SESSION_MS = 3 * 60 * 60e3;
const bufferSessions = new Map(); // token -> { url, headers, need, at, lastUsed }

function mintBuffer(target) {
  let tok = '';
  do {
    tok = crypto.randomBytes(9).toString('hex');
  } while (bufferSessions.has(tok));
  bufferSessions.set(tok, {
    url: String(target.url),
    headers: (target.headers && typeof target.headers === 'object') ? target.headers : {},
    need: Number.isFinite(Number(target.need)) ? Number(target.need) : null,
    at: Date.now(),
    lastUsed: Date.now(),
  });
  // Nothing schedules this: a hub that serves one film at a time holds a handful of entries, and the
  // sweep rides along with the press that needs one.
  if (bufferSessions.size > 12) {
    for (const [k, s] of bufferSessions) {
      if (Date.now() - s.lastUsed > BUFFER_SESSION_MS) bufferSessions.delete(k);
    }
  }
  return tok;
}

/**
 * GET /app/v1/buffer?t=… — read a cushioned copy out of this box's RAM.
 *
 * The range handling, the window and the "the origin stopped feeding us" behaviour are all the torrent
 * path's own code (readahead.serve), because a throttled file host and a thin swarm are the same
 * problem wearing different clothes: the screen must never be the one waiting on the internet.
 */
async function bufferStream(req, res, u) {
  const tok = String(u.searchParams.get('t') || '');
  const s = bufferSessions.get(tok);
  if (!s) {
    // The hub restarted, or this address outlived its film. Say so in the shape the app already reads
    // for a warming answer, so the screen re-presses /play instead of calling the file broken.
    return send(res, 410, {
      error: 'buffer gone',
      warming: true,
      detail: 'this buffered address is older than the film it pointed at',
      retryAfterMs: 800,
    }, 'application/json', { 'Retry-After': '1' });
  }
  s.lastUsed = Date.now();
  const need = Number(u.searchParams.get('need'));
  const served = await readahead.serve(req, res, { url: s.url, headers: s.headers }, {
    needMbps: Number.isFinite(need) && need > 0 ? need : s.need,
    // Shorter than the engine's header wait, and deliberately so: the hub has been pulling this copy with
    // several sockets since it promised it, so fifteen seconds without a single byte means the host has
    // stopped cooperating. Say so quickly and let the screen step down to a lighter copy while it still has
    // picture, rather than sitting on one rationed read until the player gives up on its own.
    headersMs: 15000,
    retryAfterMs: PLAY_RETRY_AFTER_MS,
    onCold: (waited, err) => log('BUFFER COLD', 'url=' + String(s.url).slice(0, 90),
      'waited=' + waited + 'ms', String(err || 'no bytes')),
  });
  if (served) return;
  // A suffix range (`bytes=-N`, a player probing the end of a container) or a cushion switched off in
  // Settings: fall back to the plain pass-through with the origin's headers put back the way it wants.
  const back = new URL(req.url, 'http://127.0.0.1:' + PORT);
  back.searchParams.set('u', s.url);
  const hh = Object.entries(s.headers || {}).map(([k, v]) => k + ':' + v).join('||');
  if (hh) back.searchParams.set('h', hh);
  return proxyStream(req, res, back);
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

/**
 * The address to stream one episode of this item.
 *
 * The community protocol says `<show>:<season>:<episode>`, and addons like Torrentio answer that.
 * Catalogue addons like DesiFlix publish each video under its own upstream id and answer streams only
 * for it — asking `<show>:1:1` there is a question the addon cannot hear, so a show with a full episode
 * list played none of them. When the episode list carried an id from upstream, ask with that.
 */
function episodeStreamId(item, ep) {
  const built = item.identity + ':' + ep.season + ':' + ep.episode;
  const alt = addons.episodeAddress(ep._addon || (item._addons || [])[0], ep.remoteId);
  return alt && alt !== built ? alt : built;
}

/** Streams for an address nobody has asked for yet — the detail page's waiting path, reused by Play. */
async function fetchEpisodeSources(item) {
  const askers = registry.providers.filter((p) => typeof p.fetchStreams === 'function');
  const ask = async () => {
    for (const p of askers) {
      const extra = await withBudget(Promise.resolve().then(() => p.fetchStreams(item.streamId)),
        META_BUDGET_MS, [], 'episode streams ' + p.id);
      if (extra && extra.length) item.sources.push(...extra);
    }
  };
  await ask();
  // The episode question was the right one and an addon is still fetching that very stream. Say
  // "one moment" with a bounded wait and ask again — the second ask is a cache hit — instead of
  // answering "no sources" for a stream that lands a second later and makes the viewer press again.
  if (!(item.sources || []).length && addons.pendingFor(item.streamId)) {
    const until = Date.now() + Math.min(6000, META_BUDGET_MS * 2);
    log('EPISODE SETTLE', item.streamId, 'waiting up to', until - Date.now(), 'ms for in-flight streams');
    while (addons.pendingFor(item.streamId) && Date.now() < until) {
      await new Promise((r) => setTimeout(r, 250));
    }
    await ask();
  }
  item.sources = merge.rankSources(item.sources || [], registry.priorities);
}

/**
 * A catalogue addon that publishes the same show twice, with different episode lists.
 *
 * The DesiFlix case: the series row lists "Dead-End Job" under one key that upstream answers with
 * an empty episode list, while the addon's own search finds the same show under a second key that
 * carries the full list, real streams, playable episodes. The viewer is looking at one show. Before
 * answering "no sources" to an episode, ask what a search for this exact title would return, and if
 * a sibling has what this tile lacks, adopt its episode list onto this tile — the identity stays the
 * one rows, progress and Continue are built on. Returns the addressed episode, or null.
 */
const adoptingSiblings = new Set(); // identities whose episode-sibling lookup is already running
async function adoptEpisodeSibling(item, ctx, opts) {
  const title = String(item.title || '').trim();
  if (!title || item.type !== 'series') return null;
  const norm = (s) => String(s || '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
  const want = norm(title);
  const year = Number(item.year);
  // The hub has often already completed this exact show under another id — the same soap under
  // its imdb tile while the aggregator tile stays thin. Re-learning it from a throttled upstream
  // is how \"one episode\" came back for a show the hub knew had 4,862: take what is already in
  // the store before asking anyone.
  for (const [, rec] of itemCache) {
    if (!rec || rec.identity === item.identity || rec.type !== 'series') continue;
    if (norm(rec.title) !== norm(title)) continue;
    const have = (item.episodes || []).length;
    if (Array.isArray(rec.episodes) && rec.episodes.length > have) {
      item.episodes = merge.unionEpisodes(item.episodes, rec.episodes);
      log('EPISODE SIBLING', item.identity, '<- remembered', rec.identity,
        'episodes=' + item.episodes.length);
    }
  }
  if ((item.episodes || []).length >= 5 && opts.fetchSources === false) {
    rememberItems([item]);
    return episodeOf(item, opts.season, opts.episode) || true;
  }
  for (const p of registry.providers) {
    if (typeof p.catalog !== 'function') continue;
    let cats = [];
    try { cats = (typeof p.catalogs === 'function' ? await p.catalogs() : []) || []; } catch (e) { continue; }
    for (const c of cats) {
      if (!(c.extra || []).includes('search')) continue;
      const hits = await ctx.catalog(p.id, c.id, { search: title }, SEARCH_BUDGET_MS);
      const sib = (hits || []).find((h) => h && h.identity !== item.identity && h.type === 'series'
        && norm(h.title) === want
        && (!Number.isFinite(year) || !Number.isFinite(Number(h.year)) || Number(h.year) === year));
      if (!sib) continue;
      const full = await ctx.metaOnly(sib.identity, 'series', opts.metaBudget || 1500);
      if (!full || !Array.isArray(full.episodes) || !full.episodes.length) continue;
      // Union, never replace. DesiFlix knowing 20 of Breaking Bad's 62 episodes must not erase the
      // 62 — the tile lost seasons 1-3 that way, and with the full list the copies indexed by the
      // show's shared ids.
      item.episodes = merge.unionEpisodes(item.episodes, full.episodes);
      log('EPISODE SIBLING', item.identity, '->', sib.identity, 'episodes=' + item.episodes.length);
      if (opts.fetchSources === false) {
        // The detail page only wants the episode list — the seasons and numbers it will print. An
        // Indian soap whose global tile carries one episode sits complete under the catalogue addon's
        // own id (4,922 of them for Taarak Mehta); the next press of that show lists them from cache.
        // A thin sibling is not that: only a list with real depth ends the hunt, so a sibling that
        // knows four episodes does not stop the search for the one that knows four thousand.
        rememberItems([item]);
        if ((item.episodes || []).length >= 5) return episodeOf(item, opts.season, opts.episode) || true;
        continue;
      }
      const ep = episodeOf(item, opts.season, opts.episode);
      if (!ep) continue;
      // Ask both addresses for the episode: the sibling's (the reason to adopt is that its addon
      // indexes streams under its own ids) and this tile's own, when it differs — so adopting never
      // loses the addons that answer at the shared id. The viewer gets the union of the answers.
      const sibEp = full.episodes.find((x) => x.season === ep.season && x.episode === ep.episode) || ep;
      const addrs = [episodeStreamId(item, sibEp)];
      const own = episodeStreamId(item, ep);
      if (own !== addrs[0]) addrs.push(own);
      let adopted = [];
      for (const addr of addrs) {
        item.streamId = addr;
        item.sources = [];
        await fetchEpisodeSources(item);
        adopted.push(...(item.sources || []));
      }
      if (adopted.length) {
        item.sources = merge.rankSources(adopted, registry.priorities);
        rememberItems([item]); // the detail page listing this show now sees the same episodes
        return ep;
      }
    }
  }
  return null;
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
void resumeOffset; // kept for the day a pre-roll is placed by playhead again; the play path pre-rolls the head (see pickAndPlay)

/**
 * What a source says about one language. `fromTracks` is the file's own measurement; `fromClaims` is the
 * addon's label. When a copy's tracks have been read, the tracks win, exactly as the ranking rule does.
 */
function audioEvidence(source, lang) {
  const tracks = Array.isArray(source.audioTracks) ? source.audioTracks : [];
  const fromTracks = tracks.some((t) => media.langOf(t.language) === lang);
  const claims = Array.isArray(source.audioLangs) ? source.audioLangs
    : (Array.isArray(source._langs) ? source._langs : []);
  // A direct provider often states the languages in the release name rather than in a track list.
  // "…Hindi.Line-English.x264…" is exactly the fact a donor search needs.
  const named = languageClaimsFromText((source.probe && source.probe.fileName) || source.label || '');
  const fromClaims = claims.some((l) => media.langOf(l) === lang)
    || named.some((l) => media.langOf(l) === lang);
  return { fromTracks, fromClaims };
}

/** Languages a release name or label states, e.g. "Hindi.Line-English" -> hin, eng. */
function languageClaimsFromText(text) {
  const lower = String(text || '').toLowerCase();
  if (!lower) return [];
  const out = [];
  for (const [code, name] of Object.entries(ranking.LANG_NAMES || {})) {
    if (name && lower.includes(String(name).toLowerCase()) && !out.includes(code)) out.push(code);
  }
  return out;
}

function hasAudioLanguage(source, lang) {
  const e = audioEvidence(source, lang);
  if (Array.isArray(source.audioTracks) && source.audioTracks.length) return e.fromTracks;
  return e.fromClaims;
}

/** Does this source's MEASURED track list carry the language? A filename claim does not count. */
function hasAudioTrack(source, lang) {
  if (!lang) return false;
  const list = source && Array.isArray(source.audioTracks) ? source.audioTracks : [];
  return list.some((t) => t && media.langOf(t.language) === lang);
}

/** The donor track's container index, or null when the source only claims a language. */
function audioOrdinal(source, lang) {
  return ordinalFromTracks(Array.isArray(source.audioTracks) ? source.audioTracks : [], lang);
}

/** The global stream index of the track in `lang` in an ffprobe-shaped track list. */
function ordinalFromTracks(tracks, lang) {
  const t = (tracks || []).find((x) => media.langOf(x.language) === lang);
  if (!t || !/^a:(\d+)$/.test(String(t.id))) return null;
  return Number(String(t.id).slice(2));
}

/** How a donor's audio may enter MP4: copy when its codec fits, otherwise AAC. */
function audioPlanFor(source, lang) {
  const tracks = Array.isArray(source.audioTracks) ? source.audioTracks : [];
  if (!tracks.length) return 'aac';
  const ord = audioOrdinal(source, lang);
  if (ord == null) return 'aac';
  const plan = remux.planFor({ audioTracks: tracks }, ord);
  return plan === 'copy' ? 'copy' : 'aac';
}

/**
 * Measure a donor's own length and audio codec before trusting it against a picture.
 *
 * A donor row only promises a language; nothing says the file is the same cut as the film on screen.
 * One archive rip measured 2 h 00 m for a 1 h 49 m film: mapped as-is, that is eleven minutes of drift
 * past the closing credits, mixed with total confidence. So the hub reads the donor's header the same
 * way it reads any other file — ffmpeg prints `Duration:` before it has read the body, so a 1.5 s wait
 * is enough — and the play path compares it against the title's runtime. An unreadable header is not a
 * refusal: a donor whose length the hub cannot check is still offered, because a hub with no ffmpeg
 * cannot check anything and must not become worse than a hub with no donor feature at all.
 */
const donorProbe = new Map(); // url -> { at, durationMs, audioCodec }
const DONOR_PROBE_TTL_MS = 30 * 60 * 1000;
// A dub that runs within this fraction of the film's length is treated as the same cut. Real rips
// differ by a few seconds of logo and credit padding, not by minutes.
// An audio donor only has to line up with the picture, not be the same release. Five minutes of
// credits or a 105-minute cut against a 110-minute catalogue runtime is still the same film; a
// 20-minute gap is not. Video copies keep the stricter 4% fingerprint above.
const DONOR_FIT_SLACK = 0.06;

function probeDonorUrl(url, headers, timeoutMs) {
  const hit = donorProbe.get(url);
  if (hit && Date.now() - hit.at < DONOR_PROBE_TTL_MS) return Promise.resolve(hit);
  return new Promise((resolveP) => {
    const args = ['-hide_banner', '-loglevel', 'info'];
    if (headers && Object.keys(headers).length) {
      args.push('-headers', Object.entries(headers).map(([k, v]) => k + ': ' + String(v)).join('\r\n') + '\r\n');
    }
    args.push('-i', url, '-t', '0.01', '-f', 'null', '-');
    let child = null;
    try {
      // The hub's deploy passes FFMPEG_BIN; the login PATH does not carry the static binary.
      child = require('child_process').spawn(process.env.FFMPEG_BIN || 'ffmpeg', args,
        { stdio: ['ignore', 'ignore', 'pipe'] });
    } catch (e) { resolveP(null); return; }
    let err = '';
    let done = false;
    const finish = (val) => {
      if (done) return;
      done = true;
      try { if (child && child.exitCode === null) child.kill('SIGKILL'); } catch (e) { /* gone */ }
      resolveP(val);
    };
    const timer = setTimeout(() => finish(null), Math.max(300, timeoutMs || 1500));
    if (timer.unref) timer.unref();
    child.stderr.on('data', (c) => {
      err += c;
      if (err.length > 8000) err = err.slice(0, 8000);
      const dm = /Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)/.exec(err);
      const am = /Audio:\s*([a-z0-9_]+)/i.exec(err);
      if (dm) {
        const durationMs = Math.round((Number(dm[1]) * 3600 + Number(dm[2]) * 60 + Number(dm[3])) * 1000);
        const entry = { at: Date.now(), durationMs, audioCodec: am ? am[1].toLowerCase() : null };
        donorProbe.set(url, entry);
        clearTimeout(timer);
        finish(entry);
      }
    });
    child.on('error', (e) => {
      log('AUDIO DONOR PROBE FAIL', url.replace(/^https?:\/\//, '').slice(0, 60), e.code || e.message);
      clearTimeout(timer);
      finish(null);
    });
    child.on('close', () => { clearTimeout(timer); finish(null); });
  });
}

// The measured verdict for a donor URL, if the hub already has one. Slow CDNs hand over a header in
// 15+ seconds — longer than a Play press may spend — so the first mix against such a donor is blind,
// and this kick fills the cache for the next one. One flight per URL, generous timeout.
const donorFlights = new Set();
function donorPeek(url) {
  const hit = donorProbe.get(url);
  return hit && Date.now() - hit.at < DONOR_PROBE_TTL_MS ? hit : null;
}
function donorProbeAsync(url, headers) {
  if (donorFlights.has(url)) return;
  donorFlights.add(url);
  probeDonorUrl(url, headers, 30000).then((p) => {
    donorFlights.delete(url);
    if (p) log('AUDIO DONOR MEASURED', url.replace(/^https?:\/\//, '').slice(0, 60),
      Math.round(p.durationMs / 60000) + ' min', p.audioCodec || '');
  });
}

/**
 * Ask the donor addon for a playable URL. A torrent donor needs the gateway; an audio-only or direct
 * donor is already an HTTP stream. This is deliberately separate from the primary picker: the donor is
 * allowed to be an audio-only row, and it must never be used as the picture.
 */
async function resolveAudioDonor(source, identity, opts, deadline) {
  const addon = registry.byId[source && source._addon];
  if (!addon || typeof addon.play !== 'function') return null;
  const left = deadline - Date.now();
  if (left <= 0) return null;
  let o = null;
  try {
    o = await withBudget(Promise.resolve().then(() => addon.play(identity, source)),
      Math.min(META_BUDGET_MS, left), null, 'audio donor ' + addon.id);
  } catch (e) {
    log('AUDIO DONOR FAIL', identity, addon.id + ': ' + e.message);
    return null;
  }
  if (!o || !o.url) return null;
  const headers = o.headers || source._headers || {};
  let url = o.url;
  let audioTracks = null;
  let durationMs = null;
  let audioCodec = null;
  if (source.kind === 'torrent') {
    const r = await gateway.resolve(
      { ...source, url: o.url, infoHash: o.infoHash },
      { clientOrigin: opts.clientOrigin,
        budgetMs: Math.max(1000, Math.min(2500, deadline - Date.now())) },
    );
    if (!r.url) return null;
    url = r.url;
    // The row's attached tracks can belong to another file of the same torrent (the engine's file id
    // is chosen at play time). Read the file we are about to hand ffmpeg, so the donor is mapped by
    // its real global stream index rather than by a stale one — and only when there is budget left.
    if (deadline - Date.now() > 900) {
      const entry = await media.probe(r.hash, r.fileId, r.url, { timeoutMs: 1500, force: true })
        .catch(() => null);
      if (entry) {
        if (Array.isArray(entry.audioTracks) && entry.audioTracks.length) audioTracks = entry.audioTracks;
        durationMs = Number(entry.durationMs) > 0 ? Number(entry.durationMs) : null;
        // With one audio track the codec is unambiguous; with several, the wanted track's codec is
        // read from donorTracks below, never from track 0.
        audioCodec = (Array.isArray(entry.audioTracks) && entry.audioTracks.length === 1)
          ? (entry.audioTracks[0].codec || null) : null;
      }
    }
  } else if (/^https?:/i.test(url)) {
    // A direct donor gets the same header read the hub gives every other file it is about to map: its
    // length and the codec the muxer will have to accept. An unreadable header is no opinion.
    const hit = donorPeek(url);
    if (hit) {
      durationMs = hit.durationMs;
      audioCodec = hit.audioCodec;
    } else if (deadline - Date.now() > 1400) {
      const p2 = await probeDonorUrl(url, headers, 1200);
      if (p2) {
        durationMs = p2.durationMs;
        audioCodec = p2.audioCodec;
      } else {
        donorProbeAsync(url, headers);
      }
    } else {
      donorProbeAsync(url, headers);
    }
  }
  return { url, headers, kind: o.kind || source.kind, audioTracks, durationMs, audioCodec };
}

/**
 * Refuse a direct/adaptive link the hub has never measured, and only that.
 *
 * An addon that mints its URL inside play() gives the picker a link with no probe result: every ranking
 * number on the row describes some other address. Nothing checked it, so a service page answering 200
 * (Prime Video detail page, a parked CDN, an addon's landing page) was handed to the player and read
 * there as a corrupt file. One short ranged read settles which kind of answer it is.
 *
 * This never waits on a link the hub has already measured (the probe cache is consulted synchronously),
 * never touches a torrent/gateway URL (not HTTP), and never touches the audio donor (that is resolved
 * after the picture is chosen). `null` means "no opinion, hand the link over": probing is off, the link
 * is not HTTP, it is already in the probe cache, or the play budget is spent.
 */
async function mintedRefusal(o, deadline) {
  if (!probe.ENABLED || !probe.probeable(o && o.url)) return null;
  const headers = o.headers || {};
  // Cloudflare Workers relays are the other cold-start family: the mint hands over a
  // `*.workers.dev/?origin=…` address, and the first request pays the worker's start-up plus the
  // origin's own round trip. Measured on the play path: the same relay answered in 4.6 s once and
  // missed a 1.2 s budget twice, which turned a real copy into "no answer in 1200ms".
  const coldHost = /herokuapp\.com|onrender\.com|koyeb\.app|railway\.app|fly\.dev|web\.app|workers\.dev/i
    .test(String((o && o.url) || ''));
  const known = probe.peek(o.url, headers);
  // Already measured: a working link needs nothing from this check, and a failure we still believe is
  // refused without spending a millisecond on it. An old failure is re-checked like any other mint,
  // because a link coming back to life must not be refused on a stale verdict.
  if (known) {
    if (known.ok === true) return null;
    // On a cold-start host, a failure whose shape is "it never answered in time" is the dyno still
    // asleep — not evidence the link is dead. Re-ask it with the wake-up window instead of honouring
    // that verdict for a minute.
    const silence = /timeout|no answer|silence|stream read failed|ECONN|socket|closed/i
      .test(String(known.error || ''));
    if (Number(known.ageMs) <= probe.limits.FAIL_TTL_MS && !(coldHost && silence)) {
      return known.error || 'measured dead';
    }
  }
  const left = deadline - Date.now();
  if (left < 400) return null; // no time to judge it: answer as the hub did before this check existed
  // Free-tier Heroku (and its friends on Render/Koyeb/Railway) is where these scraper APIs live, and a
  // sleeping dyno answers the first request in 2-6 s and every one after it fast. Judging that silence
  // "dead" at 1.2 s is what made a viewer's second tap work when the first one said "no playable
  // source". Give a cold-start host the time its wake-up actually costs.
  const capMs = coldHost ? Math.max(PLAY_VALIDATE_TIMEOUT_MS, 6000) : PLAY_VALIDATE_TIMEOUT_MS;
  try {
    const v = await probe.check(o.url, headers, {
      timeoutMs: Math.max(300, Math.min(capMs, left)),
    });
    if (!v) return null;
    // Keep the measurement (including the filename) on the answer the picker is about to inspect.
    o.probe = v;
    if (v.ok !== false) return null;
    return v.reason || 'not a stream';
  } catch (e) {
    log('PLAY VALIDATE FAIL', e.message); // a check must never break a play
    return null;
  }
}

/**
 * The filename behind a direct answer, compared with the title the viewer asked for.
 *
 * A provider can answer a perfect MP4 for a different film. The URL is opaque; the file's own name is
 * the one cheap fact that says which film arrived. A generic name ("video.mp4", "stream", a bare hash)
 * is no opinion; a name carrying a different title is enough to refuse the copy and walk on.
 */
function fileLooksLikeTitle(fileName, title) {
  const file = String(fileName || '').toLowerCase().trim();
  if (!file) return true;
  const stem = file.replace(/\.[a-z0-9]{2,5}$/i, '');
  if (/^(video|movie|stream|download|file|play|watch|media|index|master|chunk|source|untitled)$/i.test(stem)) return true;
  if (stem.length < 3) return true;
  const words = String(title || '').toLowerCase().split(/[^a-z0-9]+/)
    .filter((w) => w.length >= 3 && !['the', 'and', 'of', 'for', 'with'].includes(w));
  if (!words.length) return true;
  return words.some((w) => stem.includes(w));
}

// Words that describe a release rather than name a film. A source label is often the only clue an
// HLS provider gives before the player opens it: `480p · Hindi · Bowler XVIII (Citadel)` is a
// perfectly valid stream, and the wrong title for the row that asked for The Office.
/** The codec a release name states, when no probe measured the container. */
function codecFromName(name) {
  const n = String(name || '').toLowerCase();
  if (/\b(hevc|x265|h265)\b/.test(n)) return 'hevc';
  if (/\bav1\b/.test(n)) return 'av1';
  if (/\b(vp9)\b/.test(n)) return 'vp9';
  if (/\b(x264|h264|avc)\b/.test(n)) return 'h264';
  return null;
}

const LABEL_NOISE = new Set([
  '480p', '720p', '1080p', '1440p', '2160p', '4k', 'uhd', 'hdr', 'hdr10', 'sdr', 'dv', 'dolby',
  'vision', 'atmos', 'hindi', 'english', 'tamil', 'telugu', 'malayalam', 'kannada', 'bengali',
  'marathi', 'punjabi', 'urdu', 'gujarati', 'spanish', 'french', 'german', 'japanese', 'korean',
  'chinese', 'arabic', 'russian', 'portuguese', 'italian', 'dutch', 'turkish', 'thai', 'vietnamese',
  'indonesian', 'multi', 'dual', 'audio', 'sub', 'subs', 'subtitle', 'subtitles', 'web', 'webrip',
  'webdl', 'bluray', 'blu', 'ray', 'hdtv', 'dvdrip', 'dvd', 'remux', 'proper', 'repack', 'extended',
  'uncut', 'director', 'imax', 'cam', 'hdrip', 'x264', 'x265', 'h264', 'h265', 'hevc', 'avc', 'av1',
  '10bit', '8bit', 'mb', 'gb', 'kb', 'fps', 'hls', 'dash', 'stream', 'direct', 'auto', 'best',
  'source', 'season', 'episode', 'series', 'movie', 'special', 'complete',
  // Three-letter language codes are common in parentheses: `(Eng)`, `(Hin)`, `(Tam)`.
  'eng', 'hin', 'tam', 'tel', 'mal', 'kan', 'ben', 'mar', 'pan', 'urd', 'guj', 'spa', 'fre',
  'fra', 'ger', 'deu', 'jpn', 'kor', 'chi', 'zho', 'ara', 'rus', 'por', 'ita', 'dut', 'nld',
  'tur', 'tha', 'vie', 'ind', 'ddp', 'ddp5', 'aac2', 'ac3', 'eac3', 'truehd', 'flac', 'opus',
  // Edition words sit in parentheses as often as titles do: `(Unrated)`, `(Theatrical)`.
  'unrated', 'rated', 'theatrical', 'directors', 'collectors', 'edition', 'version', 'dubbed',
  'dub', 'subbed', 'remastered', 'restored', 'anniversary', 'criterion', 'ultimate', 'final',
  'limited', 'international', 'original', 'classic', 'cut',
]);

const LABEL_STOPWORDS = new Set(['the', 'and', 'of', 'for', 'with']);

/** The words in a label that could name a film, with quality, language and edition tags removed. */
function labelWords(text) {
  const clean = String(text || '').toLowerCase()
    .replace(/\b(19|20)\d{2}\b/g, ' ')
    .replace(/\bs\d{1,2}e\d{1,3}\b/g, ' ')
    .replace(/\b\d{1,2}x\d{1,3}\b/g, ' ')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
  return clean.split(/\s+/).filter((w) => w.length >= 3 && !/^\d+$/.test(w)
    && !LABEL_NOISE.has(w) && !LABEL_STOPWORDS.has(w)
    && !/^(?:aac|ac3|eac3|ddp|dd|h26[45]|x26[45]|hevc|avc|av1|hdr|uhd|1080|720|480|2160|1440)/.test(w));
}

function looksLikeAnotherTitle(candidate, title) {
  const words = labelWords(candidate);
  if (!words.length) return false;
  const wanted = labelWords(title);
  if (!wanted.length) return false;
  const clean = String(candidate || '').toLowerCase().replace(/[^a-z0-9]+/g, ' ');
  return !wanted.some((w) => clean.includes(w));
}

/** The URL itself is stronger evidence than a provider's label when it names the requested title. */
function urlLooksLikeTitle(url, title) {
  const wanted = labelWords(title);
  if (!wanted.length) return false;
  let text;
  try {
    text = decodeURIComponent(String(url || '')).toLowerCase();
  } catch (e) {
    text = String(url || '').toLowerCase();
  }
  return wanted.some((w) => text.includes(w));
}

// Provider/service words that say who serves a copy, not what film it is.
const RELEASE_SERVICE_WORDS = new Set([
  'amazon', 'prime', 'video', 'netflix', 'hotstar', 'disney', 'hulu', 'max', 'apple', 'paramount',
  'peacock', 'jiocinema', 'zee', 'sony', 'youtube', 'archive', 'internet', 'org', 'direct',
  'adaptive', 'torrent', 'hls', 'dash', 'stream', 'source', 'file', 'mp4', 'mkv', 'web', 'dl',
  'opensubtitles', 'subtitle', 'subtitles',
]);

/**
 * A label that names a release, not just a quality/size/service. `Titan VII - Direct MP4` on a page
 * for Schindler's List is a different film (or a lure); `1080p · 3.15 GB`, `Hindi`, `Amazon Prime
 * Video` and `Archive.org · 415 MB` are copy descriptions and stay eligible. An explicit pick is
 * still refused here: playing the wrong film is worse than saying that copy is not this title.
 */
function labelNamesARelease(label, title) {
  const words = labelWords(label).filter((w) => !RELEASE_SERVICE_WORDS.has(w));
  if (!words.length) return false;
  const wanted = labelWords(title);
  if (!wanted.length) return false;
  const clean = String(label || '').toLowerCase();
  if (wanted.some((w) => clean.includes(w))) return false;
  return true;
}

const labelOf = (s) => (s && (s.label || s.provider || s.id)) || 'source';

/**
 * Does this copy's own name disagree with the title the page is about? Two tests: the label is a
 * release name for something else, or a parenthetical names another show. Auto-play treats a yes as
 * "do not silently hand this over"; a person choosing the copy by name overrules it (see the play
 * ladder), because they are the only one who can decide that a provider mislabelled the right film.
 */
function nameIsntTheTitle(label, title) {
  return labelNamesARelease(label, title) || labelNamesOtherTitle(label, title);
}

/**
 * The season/episode a file name states, or null. A season pack or an aggregator that answers the
 * wrong episode is the other way a "play S01E01" turns into a different programme; unlike a release
 * name, this is a fact about the copy, so it is never allowed to override the requested episode.
 */
function episodeInLabel(label, ep) {
  if (!ep) return true;
  const m = /(?:^|[^a-z0-9])s(\d{1,2})[\s._-]*e(\d{1,3})(?:[^0-9]|$)/i.exec(String(label || ''));
  if (!m) return true;
  return Number(m[1]) === Number(ep.season) && Number(m[2]) === Number(ep.episode);
}

/**
 * A parenthetical in a label is where providers put the real title: `(Citadel)`, `(2005)`, `(US)`.
 * Only trust it when the rest of the label also carries title-like words. `S01E01 (Pilot)` is an
 * episode reference, not a claim about a different show.
 */
function labelNamesOtherTitle(label, title) {
  const text = String(label || '');
  const parens = [...text.matchAll(/\(([^)]{3,80})\)/g)].map((m) => m[1]);
  if (!parens.length) return false;
  const outside = labelWords(text.replace(/\([^)]*\)/g, ' '));
  if (!outside.length) return false;
  const wanted = labelWords(title);
  // The label already names the requested show outside the parentheses; the parenthetical is then
  // an episode, edition or language (`The Office (Pilot)`), not a claim about a different film.
  if (wanted.length && outside.some((w) => wanted.includes(w))) return false;
  return parens.some((part) => looksLikeAnotherTitle(part, title));
}

/**
 * The copies the Quality menu offers. A source whose own name is another release stays on the list
 * with `mislabeled: true` and sorts after the honest ones: hiding it took the better copy out of the
 * viewer's reach, and the only person who can tell a mislabelled file from a wrong film is the one
 * watching it.
 */
/**
 * The copy's resolution in initials — `1080P`. A file's name is not a quality: `Titan VII - Direct
 * MP4` says nothing about the picture, and the app's source rows need one word the viewer can
 * compare copies by. Order of trust: the addon's own field, then the label, then the tagline.
 */
function resolutionOf(source) {
  const direct = String((source && source.quality) || '').match(/(\d{3,4})[pP]/);
  if (direct) return direct[1].toUpperCase() + 'P';
  for (const field of ['label', 'tagline']) {
    const m = String((source && source[field]) || '').match(/\b(\d{3,4})[pP]\b/i);
    if (m) return m[1].toUpperCase() + 'P';
  }
  const wide = String((source && source.label) || '').match(/\b(4K|UHD)\b/i);
  return wide ? '2160P' : null;
}

/**
 * Does this label state the exact episode being asked for? `episodeInLabel` answers true for a label
 * that states no episode at all, so this is the stronger test: the file names the episode itself.
 * Aggregators name files after themselves ("Titan VII - S01E01 Direct MP4"), and the episode number is
 * then the only identity claim the copy makes — and it is a correct one. Treating the unfamiliar
 * house name as another film was refusing a copy of the right episode.
 */
function statesTheEpisode(label, ep) {
  if (!ep || !ep.season || !ep.episode) return false;
  const text = String(label || '');
  if (!/(?:^|[^a-z0-9])s(\d{1,2})[\s._-]*e(\d{1,3})(?:[^0-9]|$)/i.test(text)) return false;
  return episodeInLabel(text, ep);
}

/**
 * Presses in a row, per gateway copy, on which the hub's own read produced not one byte.
 *
 * The retry the player makes is what warms a cold torrent, so one or two zero-byte presses mean
 * nothing — they are the hub's own reader still open on the copy, still waiting. What they add up to is
 * the point: a third press that finds the same open read having heard nothing is the hub watching a copy
 * that is not answering, and the viewer has been told "preparing" for a minute. A copy that hands over a
 * byte clears this, and so does pressing again after the window closes.
 */
const noByte = new Map(); // hash:file -> { streak, at, waitedMs }
function noByteKey(who) {
  return who && who.hash ? String(who.hash) + ':' + who.fileId : '';
}
function noteNoByte(who, waitedMs) {
  const k = noByteKey(who);
  if (!k) return 0;
  const now = Date.now();
  const prev = noByte.get(k);
  if (prev && prev.at + PLAY_NOBYTE_WINDOW_MS < now) noByte.delete(k);
  const streak = ((noByte.get(k) || {}).streak || 0) + 1;
  noByte.set(k, { streak, at: now, waitedMs: Math.round(waitedMs || 0) });
  // Entries are only ever added here and expire when the copy is asked about again, so a hub that met a
  // handful of dead torrents does not carry them forever.
  if (noByte.size > 200) for (const [k2, v] of noByte) if (v.at + PLAY_NOBYTE_WINDOW_MS < now) noByte.delete(k2);
  return streak;
}
function clearNoByte(who) {
  const k = noByteKey(who);
  if (k) noByte.delete(k);
}

function listAlternatives(candidates, source, title, ep = null) {
  const others = candidates.filter((s) => s !== source);
  // A copy whose parenthetical names a different title, or whose own SxxEyy is another episode,
  // is not a quality choice — it is a different programme. Keep them out of Quality while any
  // honest copy remains; if every copy is odd, keep the list (flagged) rather than empty it.
  const honest = others.filter((s) => !labelNamesOtherTitle(labelOf(s), title)
    && episodeInLabel(labelOf(s), ep));
  const pool = honest.length ? honest : others;
  const flagged = pool.map((s) => (
    // The episode number in the file's own name outranks an unfamiliar house name on it.
    nameIsntTheTitle(labelOf(s), title) && !statesTheEpisode(labelOf(s), ep)
      ? Object.assign({}, s, { mislabeled: true }) : s
  ));
  flagged.sort((a, b) => (a.mislabeled === true ? 1 : 0) - (b.mislabeled === true ? 1 : 0));
  return flagged.slice(0, ALT_MAX).map((s) => (
    s.resolution ? s : Object.assign({}, s, { resolution: resolutionOf(s) })
  ));
}

async function pickAndPlay(identity, profile, wanted, type, opts = {}) {
  const ctx = makeCtx(profile, META_BUDGET_MS);
  const item = await ctx.resolve(identity, type);
  if (!item) {
    if (addons.pendingFor(identity)) {
      return { status: 503, body: {
        error: 'warming', warming: true, retryAfterMs: 1500, identity,
        detail: 'the hub is still resolving this title with its addons',
      } };
    }
    return { status: 404, body: { error: 'not found', identity } };
  }
  // Address the episode before anything ranks or filters sources, so the rules and the picker all see
  // the streams for the episode the viewer is about to watch.
  let ep = episodeOf(item, opts.season, opts.episode);
  if (!ep && type === 'series' && Number(opts.episode) > 0) {
    // The wanted number is not in the list this tile carries — typical of a thin cached tile
    // ("one episode, numbered 4863") that predates the full-list adoption. A sibling that indexes
    // the complete run may hold this very number: adopt before treating the episode as absent.
    await adoptEpisodeSibling(item, ctx, opts);
    ep = episodeOf(item, opts.season, opts.episode);
  }
  const epAddr = ep ? episodeStreamId(item, ep) : null;
  if (ep) {
    if (item.streamId !== epAddr) {
      item.streamId = epAddr;
      item.sources = [];
    }
    if (!(item.sources || []).length) await fetchEpisodeSources(item); // cheap when resolve already asked
    // "Sources answered, but not one of them is this title" — the walk below would reject every copy
    // with `its name is not <title>` — is the same dead end as silence, and must not block the join
    // that exists for it: an aggregator that replies to an episode address with some other show's
    // releases (measured live: "Grand Warden I" for a Taarak Mehta episode) is answering, wrongly.
    const nothingRight = (item.sources || []).length > 0 && type === 'series'
      && (item.sources || []).every((s) => labelNamesOtherTitle(labelOf(s), item.title));
    if ((!(item.sources || []).length || nothingRight) && type === 'series'
        && await adoptEpisodeSibling(item, ctx, opts)) {
      // The sibling carried the episode the show itself could not. Sources are on the item now.
    } else if (!(item.sources || []).length || nothingRight) {
      // Episode addressing is the right question, but some addons only list a show as a whole (a season
      // pack, or a catalog with no episode ids). An empty answer must not cost the title: fall back to
      // the show address the hub used before this existed.
      log('EPISODE MISS', item.streamId, 'falling back to the show address');
      item.streamId = null;
      ep = null;
      await contributeSources(item);
    }
  }
  // The intro window does not depend on which copy wins, so ask IntroDB while the sources rank. A
  // missing window is a promise of null, never a reason to fail the play. Only a series episode has one.
  const introPromise = (type === 'series' && ep && ep.season > 0 && ep.episode > 0)
    ? introdb.introFor(identity, ep.season, ep.episode, { timeoutMs: 1200 })
    : Promise.resolve(null);
  const drops = [];
  const [kept] = await pipeline([item], ctx, drops); // rules rank sources and enforce the profile's scope
  if (!kept) return { status: 403, body: ruleDropBody(identity, drops) };
  let sources = kept.sources || [];
  if (process.env.PLAY_DEBUG) {
    log('PLAY SOURCES', identity, kept.type + ' x' + sources.length,
      sources.map((s) => (s._addon || '?') + ':' + String(s.label || s.name || '').slice(0, 28)).join(' | ').slice(0, 600));
  }
  if (!sources.length) {
    // Same bargain as the missing item: if an addon is still fetching this title's streams, the
    // copies are in transit. The app's warming path retries; a bare 404 sends it to an error card.
    if (addons.pendingFor(identity) || addons.pendingFor(kept.streamId || identity)) {
      return { status: 503, body: {
        error: 'warming', warming: true, retryAfterMs: 1500, identity,
        detail: 'the hub is still fetching sources for this title',
      } };
    }
    return { status: 404, body: { error: 'no sources', identity } };
  }
  if (kept.type !== 'channel' && sources.length
      && sources.every((s) => labelNamesOtherTitle(labelOf(s), kept.title))) {
    // Every saved copy for this id carries some OTHER title's name (measured live: "Grand Warden I"
    // answers for Taarak Mehta and for The Housemaid) — the aggregator's stored answer is for the
    // wrong show, and the walk below would reject each copy with `its name is not`. Once per press,
    // throw the saved answer away and let the addons mint a fresh one.
    addons.forgetStreams(kept.streamId || identity);
    log('PLAY REMINT', identity, sources.length + ' saved copies all named another title',
      'asking the addons for fresh ones');
    await contributeSources(kept);
    sources = kept.sources || [];
    if (!sources.length) return { status: 404, body: { error: 'no sources', identity } };
  }
  for (const s of sources) s.id = probe.sourceId(s); // the handle `exclude` names, assigned before filtering

  // Touch the links before handing any of them over, then let the ranking rule use what came back.
  const skip = exclusions(opts.exclude);
  // An explicit pick is a decision, not a hint: the copy a person tapped must not be filtered out by
  // the screen's own "already tried" list. Without this, re-choosing a copy that stalled once silently
  // fell back to a different one.
  if (wanted && (wanted.id || wanted.label)) {
    for (const s of sources) {
      if ((wanted.id && String(s.id) === String(wanted.id))
          || (wanted.label && s.label === wanted.label)) {
        skip.delete(s.id);
        if (s.label) skip.delete(s.label);
        if (s.provider && s.label) skip.delete(s.provider + ':' + s.label);
        if (s._url) skip.delete(s._url);
      }
    }
  }
  const open = sources.filter((s) => !isExcluded(s, skip));
  await probePass(open.length ? open : sources, kept.streamId || identity, { force: opts.reprobe === true });
  // A live channel that comes BACK to the hub (`/switch` with exclusions — the TV's own failover after
  // a stall or an error) is the freshest evidence that exists about this feed: a screen tried to watch
  // it and could not. The scheduled probe re-checks only every 30 minutes, so without this the dead
  // feed the viewer just left keeps its working badge — and keeps winning the tune — until tonight is
  // over. Report it, so the next tune picks another variant and the wall marks it honestly.
  if (kept.type === 'channel' && Array.isArray(opts.exclude) && opts.exclude.length) {
    const h = liveHealth.get(identity);
    if (h && h.ok && h.url) liveHealth.report(identity, false, h.url, 'a viewer could not play this feed');
  }
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
  const broad = allExcluded ? sources : pool;
  // An audio-only row is a donor, not a picture: it must never become the primary source when any video
  // copy exists. It stays in the candidate list only when a title has no picture at all.
  const videoPool = broad.filter((s) => !(s.audioOnly === true || s.kind === 'audio'));
  const candidates = videoPool.length ? videoPool : broad;
  const prefAudio = (prefs.resolve(profile).audioLanguages || []).map((c) => media.langOf(c));
  let source = candidates[0];
  // `explicit` means the viewer named one copy by id or label. It changes the whole walk below:
  // an explicit copy is played or refused, never swapped for the automatic pick.
  let explicit = false;
  const wantsExact = !!(wanted && (wanted.id || wanted.label));
  if (wanted) {
    // The id is the exact handle the picker sent. Label/provider are only fallbacks: several copies
    // can share a provider, and choosing the first of them is how "try this 1080p copy" became a
    // different file.
    // Precedence matters: the id is the exact copy, the label is the next-closest identity, and a
    // provider is only a family. Using one OR chain let a missing id fall through to the first copy
    // from the same provider, which is how choosing an audio language quietly changed the film.
    let match = wanted.id
      ? candidates.find((s) => String(s.id) === String(wanted.id)) : null;
    if (!match && wanted.label) match = candidates.find((s) => s.label === wanted.label);
    if (!match && !wanted.id && !wanted.label && wanted.provider) {
      match = candidates.find((s) => s._addon === wanted.provider || s.provider === wanted.provider);
    }
    if (match) {
      source = match;
      explicit = wantsExact;
    }
  }
  // The row is stale (the hub's addon changed its list, or the title moved). Say so; do not answer
  // with a different copy while the Quality menu still shows the one that was tapped.
  if (wantsExact && !explicit) {
    return { status: 404, body: {
      error: 'that copy is no longer offered for this title; choose another in Quality',
      identity,
    } };
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
  // A direct source is probed for bytes, not always for codecs. Its release name still says
  // "x265"/"HEVC" often enough to keep an unplayable 10-bit copy from taking a seat.
  for (const s of sources) {
    if (!s || s.videoCodec) continue;
    const name = (s.probe && s.probe.fileName) || s.label || '';
    const codec = codecFromName(name);
    if (codec) s.videoCodec = codec;
  }
  const rankDown = (s) => (s && s.status === 'down' ? 2 : 0) + (s && s.seeders === 0 ? 1 : 0)
    + (decodable.length && s && s.videoCodec
      && !decodable.includes(ranking.codecFamily(s.videoCodec)) ? 3 : 0)
    // A copy whose own release name states another programme cannot pass the walk's name test — it
    // is a certain rejection wearing a seat. Measured live: three such copies occupied the front of
    // a 10-copy title while five working torrent copies waited behind a 5-second budget. Same
    // predicates the walk applies (channels exempt, their labels are qualities, not release names;
    // a copy that states the wanted episode is not treated as another film).
    + (s && kept.type !== 'channel' && !kept.live
      && !statesTheEpisode(labelOf(s), ep)
      && nameIsntTheTitle(labelOf(s), kept.title) ? 4 : 0);
  // The walk only ever seats PLAY_MAX_TRIES copies, so those seats go to what can fail this device least,
  // with the household's ranking intact underneath. Taking the seats straight off the top of the ranking
  // let an unplayable file hold one a playable copy was waiting further down for: measured on a series
  // whose two H.264 copies ranked fourth and fifth of twelve, a browser that had already said it cannot
  // draw H.265 was handed a black screen with sound because three better-ranked H.265 files got there
  // first. A viewer who picked a source by hand still leads.
  const seated = candidates.slice().sort((a, b) => rankDown(a) - rankDown(b));
  // The whole ranked walk is kept: an ordinary press only seats PLAY_MAX_TRIES copies, but a minted link
  // refused below may wake one more copy from further down the same ranking (bounded by the retry count).
  const ranked = explicit ? [source] : seated.slice();
  const queue = ranked.slice(0, PLAY_MAX_TRIES);
  // Then the copies this connection can actually feed. Once the hub has measured the engine's rate, a
  // 6 GB 4K copy stops competing with a 900 MB 720p one: the smaller is tried first because the bigger
  // one is known to stall. A source that is not a torrent gets the same standing as one that fits,
  // because it is the 70 Mbps HTTP path rather than the 1 Mbps BitTorrent one. The viewer asked for top
  // quality and gets the top quality that plays — which is what "Auto" means in a paid app.
  const fitRank = (s) => {
    if (!swarmMbps || !s || s.kind !== 'torrent') return -1;
    // `kept` is the merged item itself (pipeline returns the items it kept), so the runtime this
    // arithmetic needs comes off it directly.
    const need = ranking.estimateMbps(s, kept);
    if (!need) return -1; // an adaptive ladder has no fixed bitrate; it adjusts itself
    return need <= swarmMbps * fitCeiling ? 0 : need;
  };
  // The seats keep their known-bad order under the fit pass: fitRank reorders WITHIN a rankDown tier,
  // it may not promote past one. Reordering by fit alone let a dead hoster link and an aggregator's
  // wrong-title answer keep the two front seats of a 13-copy title - they are HTTP (fit -1) so they
  // led every time, while five working torrent copies waited below the 5-second budget.
  queue.sort((a, b) => (rankDown(a) - rankDown(b)) || (fitRank(a) - fitRank(b)));
  if (process.env.PLAY_DEBUG) {
    log('PLAY QUEUE', identity, queue.length + ' seats: '
      + queue.map((s) => (s._addon || '?') + '/' + rankDown(s) + '/' + fitRank(s)
        + ':' + String(labelOf(s)).slice(0, 22)).join(' | ').slice(0, 700));
  }
  // An explicit copy is the only copy this press may start: "try this copy" is a person's decision,
  // and the answer is either that copy or an honest refusal. Copies past the first seats are only
  // woken by an automatic walk.
  const spare = explicit ? [] : ranked.slice(PLAY_MAX_TRIES)
    .sort((a, b) => (rankDown(a) - rankDown(b)) || (fitRank(a) - fitRank(b)));

  const deadline = Date.now() + PLAY_TOTAL_BUDGET_MS;
  const tried = [];
  let mintedTries = 0; // how many minted links this press has actually had to check
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
      + ' as ordinary HTTP, no P2P engine involved');
    return value;
  };

  let labelOverride = null; // set when a named pick plays despite its own release name
  let reminted = false; // one throw-away of the cached stream answer per play press
  for (const cand of queue) {
    const addon = addonOf(cand);
    if (!addon || typeof addon.play !== 'function') continue;
    const left = deadline - Date.now();
    if (left <= 0) { tried.push('out of time after ' + queue.indexOf(cand) + ' tries'); break; }
    // The label tests, run BEFORE paying for the mint. Asking an aggregator for an address costs
    // ~3 s per copy on this hub, and the copy it mints is the one its label already named: when the
    // label states another programme the mint is spent and the rejection is certain. Measured live:
    // three aggregator junk copies spent nine of a press's eight seconds minting answers the walk
    // then threw away. An explicit Quality-menu pick still pays the mint first (it may play despite
    // an odd release name), and every test below is re-applied to the minted copy unchanged.
    const liveChannel = kept.type === 'channel' || !!kept.live;
    const named = wanted && ((wanted.id && String(cand.id) === String(wanted.id))
      || (wanted.label && labelOf(cand) === wanted.label));
    if (!named && !liveChannel) {
      if (labelNamesOtherTitle(labelOf(cand), kept.title)) {
        tried.push(labelOf(cand) + ': its name is another title');
        log('PLAY WRONG TITLE', identity, labelOf(cand), kept.title);
        continue;
      }
      if (!episodeInLabel(labelOf(cand), ep)) {
        tried.push(labelOf(cand) + ': its name is another episode');
        log('PLAY WRONG EPISODE', identity, labelOf(cand), ep ? ('S' + ep.season + 'E' + ep.episode) : '?');
        continue;
      }
      if (!statesTheEpisode(labelOf(cand), ep) && nameIsntTheTitle(labelOf(cand), kept.title)) {
        tried.push(labelOf(cand) + ': its name is not ' + (kept.title || 'this title'));
        log('PLAY LABEL MISMATCH', identity, labelOf(cand), kept.title);
        continue;
      }
    }
    const o = await withBudget(Promise.resolve().then(() => addon.play(kept.streamId || identity, cand)),
      Math.min(META_BUDGET_MS, left), null, 'play ' + addon.id);
    if (!o || !o.url) {
      // The addon named this copy and then had no address for it. Saying which addon is the whole
      // diagnosis: one aggregator whose mint endpoint is failing looks nothing like a dead internet.
      tried.push(labelOf(cand) + ': no play target from ' + addon.id);
      log('PLAY NO TARGET', identity, addon.id, labelOf(cand));
      continue;
    }
    // The two label tests. A copy whose own name is another release is the most common way a free
    // source turns out to be the wrong film, so the ladder will not pick one by itself.
    // A live channel's "label" is a quality ("576p stream"), not a release name. The film title
    // guard does not apply: it rejected every channel whose label was not its name, which was most
    // of the wall. Identity is the channel; the variants are its feeds.
    const otherTitle = liveChannel ? false : labelNamesOtherTitle(labelOf(cand), kept.title);
    // The episode number the file states about itself is a fact about the copy, and it is the strongest
    // one an aggregator's release name offers. "Titan VII - S01E01 Direct MP4" asked for as S01E01 is
    // the right episode under a house name, so it is not treated as another film. A label that claims a
    // different title in parentheses still is.
    const rightEpisode = !liveChannel && statesTheEpisode(labelOf(cand), ep);
    const mislabeled = liveChannel ? false
      : (rightEpisode ? false : nameIsntTheTitle(labelOf(cand), kept.title));
    // A person who named this copy in the Quality menu outranks an odd release name: providers
    // mislabel real copies, and the better-quality one is often exactly the odd-named file. That
    // grace does NOT extend to a copy whose own name (or SxxEyy) is a different programme: an
    // explicit pick must never put another title or another episode on screen. (`named` was settled
    // before the mint above.)
    if (otherTitle) {
      tried.push(labelOf(cand) + ': its name is another title');
      log('PLAY WRONG TITLE', identity, labelOf(cand), kept.title);
      continue;
    }
    if (!liveChannel && !episodeInLabel(labelOf(cand), ep)) {
      tried.push(labelOf(cand) + ': its name is another episode');
      log('PLAY WRONG EPISODE', identity, labelOf(cand), ep ? ('S' + ep.season + 'E' + ep.episode) : '?');
      continue;
    }
    if (mislabeled && !named) {
      tried.push(labelOf(cand) + ': its name is not ' + (kept.title || 'this title'));
      log('PLAY LABEL MISMATCH', identity, labelOf(cand), kept.title);
      continue;
    }
    if (mislabeled && named) {
      labelOverride = labelOf(cand);
      log('PLAY LABEL OVERRIDE', identity, labelOf(cand), kept.title);
    }

    // Debrid first: a magnet or a hoster link becomes ordinary HTTP and skips the swarm entirely. If it
    // says no, the torrent branch asks the gateway exactly as it did before this file existed.
    const viaDebrid = await tryDebrid(o, cand);
    if (viaDebrid) {
      if (!fileLooksLikeTitle(viaDebrid.fileName, kept.title)) {
        tried.push(labelOf(cand) + ': file is named ' + viaDebrid.fileName);
        log('PLAY TITLE MISMATCH', identity, labelOf(cand), viaDebrid.fileName);
        continue;
      }
      out = viaDebrid; source = cand; break;
    }

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
        const down = (cand.probe && cand.probe.error) || 'no bytes';
        tried.push(labelOf(cand) + ': measured down (' + down + ')');
        lastResort = lastResort || { o, cand };
        // A measured 4xx proves the link the hub cached is dead, not that the title is — and the
        // cached answer is exactly what the next press reads back for six hours. Once per press,
        // drop the addon's stream answer for this title and give this copy one fresh mint at the
        // addon itself; the unprobed clone walks the normal validation road, so a new link gets a
        // real chance and a re-minted death stops here.
        const refused = readahead.originRefusal(down);
        if (!reminted && refused >= 400 && refused < 500) {
          reminted = true;
          addons.forgetStreams(kept.streamId || identity);
          log('PLAY REMINT', identity, labelOf(cand),
            'measured ' + down + ': asking ' + addon.id + ' for a fresh link');
          queue.push(Object.assign({}, cand, { probe: null, checked: false }));
        }
        continue;
      }
      // A link the addon minted inside play() has no probe result of its own, so nothing has asked what
      // kind of answer it is. One short ranged read, and a refusal walks the ranking instead of handing
      // the player an HTML page. Cached measurements are trusted above (mintedRefusal reads them first),
      // so a link the hub already probed never waits here.
      if (mintedTries < 1 + PLAY_VALIDATE_RETRIES) {
        mintedTries++;
        const refusal = await mintedRefusal(o, deadline);
        if (refusal) {
          log('PLAY REJECT', identity, labelOf(cand), refusal);
          tried.push(labelOf(cand) + ': ' + refusal);
          // A direct copy that the hub's short validation window could not reach is not proof it
          // cannot play: a busy archive/CDN node often takes longer than the window to first byte.
          // When it is the last candidate, keep it as the fallback and let the player's own read
          // timeout decide, instead of answering "warming" forever on a URL that is fine.
          const silent = /no answer|timed out|timeout|socket/i.test(refusal);
          if (silent && queue.indexOf(cand) === queue.length - 1
              && /^https?:\/\//i.test(String(o.url || ''))) {
            lastResort = lastResort || { o, cand };
            break;
          }
          // The ranked list was longer than the seats. Waking its next copy is only for a refused mint,
          // only while another validation attempt is still allowed, and only once the current walk has
          // nothing left queued behind it — the retry copies are appended in ranked order.
          if (mintedTries < 1 + PLAY_VALIDATE_RETRIES && queue.indexOf(cand) === queue.length - 1) {
            const next = spare.shift();
            if (next) queue.push(next);
          }
          continue;
        }
      }
      if (!fileLooksLikeTitle((o.probe && o.probe.fileName) || (cand.probe && cand.probe.fileName), kept.title)) {
        tried.push(labelOf(cand) + ': file is named ' + ((o.probe && o.probe.fileName) || ''));
        log('PLAY TITLE MISMATCH', identity, labelOf(cand), (o.probe && o.probe.fileName) || '');
        continue;
      }
      // A file with the right name can still be a different cut. Its own duration is the cheapest
      // content fingerprint available before the player opens it.
      if (kept.type === 'movie' && Number(kept.durationMs) > 0) {
        const fp = await fingerprint.duration(o.url, o.headers, 2600);
        if (fp && fp.ok && !fingerprint.matches(kept.durationMs, fp.durationMs)) {
          const note = 'runs ' + Math.round(fp.durationMs / 60000)
            + ' min against a ' + Math.round(Number(kept.durationMs) / 60000) + ' min film';
          log('PLAY DURATION MISMATCH', identity, labelOf(cand),
            'file=' + Math.round(fp.durationMs / 60000) + 'min',
            'title=' + Math.round(Number(kept.durationMs) / 60000) + 'min',
            explicit ? 'kept: the viewer picked this copy' : 'skipped');
          if (!explicit) {
            tried.push(labelOf(cand) + ': ' + note);
            continue;
          }
          // A person chose this exact copy. A shorter/longer cut is a fact to report, not a reason
          // to play a different film under the same Play press.
          extraWhy.push('you chose this copy; it ' + note);
        }
      }
      // ---- measure the copy the way the screen is going to read it ---------------------------
      //
      // Everything above proves this link *answers*. It does not prove it can carry a picture, and for a
      // direct addon link that is the whole game: one HTTP connection to a free file host, and those
      // hosts ration each connection. Measured tonight on the copy this house watches: one socket ran
      // 5 Mbps for ten megabytes, decayed to 2 Mbps by 25 MB, then handed over 30 MB in ninety seconds
      // and stopped; four ranged reads in parallel carried 48 MB in 36 s. The 256 KB probe only ever
      // sees the burst at the front of that curve — it reported "3.9 Mbps, enough for the 1.7 Mbps this
      // file needs" — so the picker promised a 1080p copy the host could not feed, and the film froze
      // four minutes in, which is exactly the complaint about this app.
      //
      // So the hub measures *through* its own read-ahead buffer, which reads with several ranged
      // connections at once, and the verdict is the worst one-second window of that read: the number
      // that predicts a freeze. The bytes it pulls are kept as the pre-roll, so a viewer who gets a
      // green light here starts with a cushion already under them, and the screen then reads the hub at
      // LAN speed instead of sitting on one rationed socket.
      const needDirect = ranking.estimateMbps(cand, kept);
      // A live channel is not a file. Its "copy" is a moving playlist: measuring it through the
      // read-ahead buffer spends the play budget sustaining a window nobody will read, opens four
      // extra sockets per zap against an IPTV host that reads them as abuse, and the bytes it parks
      // in RAM are dead weight — the answer hands the origin URL to the screen directly (out.live).
      // probePass is the only live check the picker needs; it already GETs the manifest.
      const directOn = readahead.cfg().direct && kept.type !== 'channel' && !o.live
        && opts.fast !== true
        && cand._noCushion !== true
        && /^https?:\/\//i.test(String(o.url || ''));
      if (directOn) {
        const who = { url: o.url, headers: o.headers || {} };
        const shunned = readahead.slowFor(who);
        if (shunned && queue.length > 1) {
          tried.push(labelOf(cand) + ': starved a viewer '
            + Math.round((Date.now() - shunned.at) / 1000) + 's ago at ' + shunned.mbps
            + ' Mbps against the ~' + shunned.needMbps + ' Mbps this file needs');
          o.checked = true;
          o.slow = true;
          o.neededMbps = shunned.needMbps;
          o.measuredMbps = shunned.mbps;
          slowGateway = slowGateway || { o, cand };
          continue;
        }
        const md = await readahead.cushionUrl(o.url, o.headers || {}, {
          needMbps: needDirect,
          // Measure and pre-roll the HEAD of the file, even resuming mid-film. A resume looks as if it
          // wants the byte the viewer sits on — computed from size over runtime, the same arithmetic the
          // ranking uses — but the player opens a new instance for the copy this press chose and parses the
          // container from byte 0 before it seeks anywhere. Measured tonight: the hub pre-rolled 2.9 MB at
          // 1.27 MB, the screen asked for byte 0 forty milliseconds later, the cushion threw the pre-roll
          // away in a seek, and that repeat cost the first frame. The head is always wanted; the resume
          // seek follows once the header is read, and by then the fill is running anyway.
          at: 0,
          leftMs: deadline - Date.now(),
        });
        const gotD = md && Number.isFinite(md.mbps) ? md.mbps : null;
        const realD = !!md && !!md.real && gotD != null;
        if (md && md.bufferedMB != null) o.bufferedMB = md.bufferedMB;
        if (md && md.prebuffer) o.prebuffer = md.prebuffer;
        if (realD) {
          directMbps = directMbps ? directMbps * 0.6 + gotD * 0.4 : gotD;
          log('PLAY RATE MEASURED', identity, labelOf(cand),
            'mbps=' + gotD.toFixed(1), 'need=' + (needDirect == null ? '?' : needDirect.toFixed(1)),
            'avg=' + md.avgMbps, 'over=' + md.ms + 'ms', 'windows=' + JSON.stringify(md.windows || []),
            'bufferedMB=' + md.bufferedMB, 'note=cushion');
          if (needDirect && gotD >= needDirect * speedBar) readahead.clearSlowFor(who);
        }
        if (md && md.cached) {
          // Its bytes are already in this box's RAM: nothing left to learn, and the fastest answer in
          // the house. This is the paid service's advantage, arrived at with a buffer.
          log('PLAY RATE CACHED', identity, labelOf(cand), 'bufferedMB=' + md.bufferedMB,
            'need=' + (needDirect == null ? '?' : needDirect.toFixed(1)), 'note=cushion');
          o.checked = true;
          o.cached = true;
          o.cushioned = true;
          o.neededMbps = Number.isFinite(needDirect) ? Number(needDirect.toFixed(2)) : null;
          if (gotD != null) o.measuredMbps = Number(gotD.toFixed(2));
        } else if (realD && (gotD <= 0 || (needDirect && gotD < needDirect * speedBar))) {
          // Carried bytes, at a rate this file cannot live on — or no bytes at all. The old test only
          // stepped down when the file's bitrate was known: a 1080p release with no stated size that
          // served 0 bytes therefore passed as "measured", and the player spent 25 s discovering it.
          // Zero is zero whatever the estimate. The bar is the same one the torrent path uses.
          const headroom = needDirect ? gotD / needDirect : 0;
          tried.push(labelOf(cand) + ': cushion carried ' + gotD.toFixed(1) + ' Mbps over ' + md.ms
            + 'ms' + (needDirect ? ' against ~' + needDirect.toFixed(1) + ' Mbps this file needs' : '')
            + (speedBar < ranking.HEADROOM ? ' (bar relaxed to ' + speedBar.toFixed(2)
              + 'x for a client that plays the copy itself)' : ''));
          o.checked = true;
          o.slow = true;
          o.neededMbps = needDirect ? Number(needDirect.toFixed(2)) : null;
          o.measuredMbps = Number(gotD.toFixed(2));
          o.measuredBytes = md.bytes || 0;
          if (explicit) {
            // The viewer chose the sharper copy. Report the measurement, but do not quietly play a
            // different one; the player's own buffer is allowed to prove the measurement wrong.
            extraWhy.push('you chose this copy; its first ' + (md.bytes || 0)
              + ' bytes measured ' + gotD.toFixed(1) + ' Mbps'
              + (needDirect ? ' against ~' + needDirect.toFixed(1) + ' Mbps needed' : '')
              + ', but it is playing because you picked it');
          } else {
            if (!tooSlow || headroom > tooSlow.headroom) tooSlow = { o, cand, headroom };
            continue;
          }
        } else if (realD) {
          o.checked = true;
          o.cushioned = true;
          o.neededMbps = Number.isFinite(needDirect) ? Number(needDirect.toFixed(2)) : null;
          o.measuredMbps = Number(gotD.toFixed(2));
        } else if (md && md.bytes > 0 && queue.length > queue.indexOf(cand) + 1) {
          // Bytes, but a sample too short to be a rate. Not a green light while another copy is still
          // queued to be tried; the reader stays open, so this one keeps filling for the next press.
          log('PLAY RATE IGNORED', identity, 'kbps=' + (gotD == null ? '?' : Math.round(gotD * 1000)),
            'reason=shortWindow', 'bytes=' + md.bytes, 'over=' + md.ms + 'ms', 'note=cushion');
          tried.push(labelOf(cand) + ': only ' + ((md.windows || []).length) + ' timed window of it ('
            + md.bytes + ' bytes in ' + md.ms + 'ms) — no rate to judge');
          o.checked = true;
          o.measuredBytes = md.bytes;
          o.neededMbps = Number.isFinite(needDirect) ? Number(needDirect.toFixed(2)) : null;
          o.measuredMbps = gotD == null ? null : Number(gotD.toFixed(2));
          slowGateway = slowGateway || { o, cand };
          continue;
        } else if (md && md.bytes > 0) {
          // Last copy on the list and it moved: play it. Unproven is not the same as proven slow, and
          // the hub's own cushion is what will catch it if the host collapses later.
          o.checked = true;
          o.cushioned = true;
          o.unproven = true;
          o.neededMbps = Number.isFinite(needDirect) ? Number(needDirect.toFixed(2)) : null;
          o.measuredBytes = md.bytes;
          if (gotD != null) o.measuredMbps = Number(gotD.toFixed(2));
        } else if (md && queue.length > queue.indexOf(cand) + 1) {
          // Headers answered and then silence: a host that is throttling us or a link that has gone
          // stale mid-play. Ask the next copy, and remember this one as a fallback.
          tried.push(labelOf(cand) + ': no byte of it arrived in ' + md.ms + 'ms (host silent)');
          o.checked = true;
          o.measuredBytes = 0;
          slowGateway = slowGateway || { o, cand };
          continue;
        }
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
      tried.push(labelOf(cand) + ': the engine has not answered for this copy for '
        + Math.round((frozen.until - Date.now()) / 1000) + 's ('
        + (frozen.reason || 'no header ' + (frozen.tries || 1) + 'x, last after ' + frozen.waitedMs + 'ms') + ')');
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
        // The head of the file, not the byte the resume sits on: the same lesson the direct path
        // above documents at length — the player opens this copy at byte 0 and parses the container
        // before it seeks anywhere, so a pre-roll at the resume point gets thrown away in a seek and
        // that repeat costs the first frame.
        at: 0,
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
      if (!explicit) continue;
      extraWhy.push('you chose this copy; the engine has not served a byte of it yet, so it may start cold');
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
      swarmMbps = swarmMbps ? swarmMbps * 0.6 + got * 0.4 : got;
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
      tried.push(labelOf(cand) + ': no byte of it arrived in ' + m.ms + 'ms (cold copy, still reading)');
      o.warming = true;
      o.measuredBytes = 0;
      o.neededMbps = Number.isFinite(needs) ? Number(needs.toFixed(2)) : null;
      slowGateway = slowGateway || { o, cand };
      if (!explicit) continue;
      extraWhy.push('you chose this copy; it is still cold, so its first frame may take a moment');
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
      if (!explicit) continue;
      extraWhy.push('you chose this copy; its first sample was too short to rate, so it is playing unmeasured');
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
      if (explicit) {
        extraWhy.push('you chose this copy; its first ' + (m.bytes || 0) + ' bytes measured '
          + got.toFixed(1) + ' Mbps against ~' + needs.toFixed(1)
          + ' Mbps needed, but it is playing because you picked it');
      } else {
        if (!tooSlow || headroom > tooSlow.headroom) tooSlow = { o, cand, headroom };
        continue;
      }
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
  if (!out && explicit) {
    // A direct copy whose short validation window was silent is not proof it cannot play: a busy
    // archive/CDN node often takes longer to first byte than the window. Hand the player the URL it
    // chose and let its own read timeout decide, exactly as the automatic path does.
    if (lastResort && lastResort.o && lastResort.o.url) {
      out = lastResort.o;
      source = lastResort.cand;
      tried.push('letting the player read the chosen copy directly');
    }
  }
  if (!out && explicit) {
    // The chosen copy could not be opened. The Quality menu must not be left claiming it is on.
    const reason = tried.find((t) => t.startsWith(labelOf(source))) || tried[0]
      || 'it answered with nothing the player could open';
    log('PLAY EXPLICIT FAIL', identity, labelOf(source), reason);
    const silenceOnly = tried.length > 0 && tried.every((t) =>
      /timeout|no answer|silence|stream read failed|ECONN|socket|closed|measured down|cold copy/i.test(t));
    if (silenceOnly) {
      return { status: 503, body: {
        error: 'warming', warming: true, retryAfterMs: 2500, identity,
        copy: labelOf(source),
        detail: 'the copy you picked has not sent a byte yet; keep waiting and it may start',
        tried,
      } };
    }
    return { status: 404, body: {
      error: 'that copy could not be opened: ' + reason.replace(labelOf(source) + ': ', ''),
      identity,
      copy: labelOf(source),
      tried,
    } };
  }
  if (!out && fallback) {
    out = fallback.o;
    source = fallback.cand;
    tried.push('nothing better than ' + labelOf(source));
  }
  if (!out || !out.url) {
    // The viewer sees "nothing played" either way; the difference is whether the log can say why.
    log('PLAY FAIL', identity, tried.join(' | '));
    // "No play target" means the candidate list named copies that had no address behind them — the
    // addon's stream answer was a shrug, or its mint failed at play time. Drop the cached answers for
    // this identity and answer with the warming bargain: the retry the app already makes goes back to
    // the addons instead of reading the same shrug off the cache.
    if (tried.length > 0 && tried.some((t) => /no play target/i.test(t))
      && tried.every((t) => /no play target|nothing better/i.test(t))) {
      addons.forgetStreams(identity);
      return { status: 503, body: {
        error: 'warming', warming: true, retryAfterMs: 1500, identity, tried,
        detail: 'the copies this hub was told about had no address behind them; asking the addons again',
      } };
    }
    // When every refusal was of the silence kind — timeouts, no bytes, dropped sockets — rather than
    // a refusal of the content (an HTML page, the wrong film's filename), the copies may simply be
    // sleeping hosts that have not woken yet. Answer the hub's warming bargain and the app waits and
    // asks again quietly; the second attempt finds a woken box. A refusal that was about the content
    // itself stays an honest 404.
    const silenceOnly = tried.length > 0 && tried.every((t) =>
      /timeout|no answer|silence|stream read failed|ECONN|socket|closed|measured down/i.test(t));
    if (silenceOnly) {
      return { status: 503, body: {
        error: 'warming', warming: true, retryAfterMs: 2500, identity, tried,
        detail: 'every copy answered too slowly to measure; they stay candidates, not verdicts',
      } };
    }
    return { status: 404, body: { error: 'no playable source', identity, tried } };
  }

  // A native provider may answer with a hub-relative address (`/app/v1/proxy?u=file://…` for a file
  // on this box, for example). That is a real stream on this server, not a magnet, so it must not fall
  // into the "still warming" branch below. Resolve it against the origin the client called from — a TV
  // cannot read a path that does not name the hub.
  const hubOrigin = opts.clientOrigin || ('http://127.0.0.1:' + PORT);
  const hubRelative = typeof out.url === 'string' && out.url.startsWith('/');
  // A magnet is not a stream. It is an invitation to run a BitTorrent client, and no client of this
  // route is one: the TV passes it to a media decoder that reports "unknown protocol: magnet", and a
  // browser tab cannot open it either. So when the gateway produced HTTP for nothing, say what is
  // actually true — the hub has no copy ready to serve yet, and here is how long to wait — instead of
  // answering 200 with something unopenable and letting the screen call it a broken file. The magnet is
  // still in the answer, for the sake of any client that can use one and for the log.
  if (!hubRelative && !/^https?:\/\//i.test(String(out.url || ''))) {
    log('PLAY WARMING', identity, labelOf(source), String(out.url).slice(0, 42));
    return {
      status: 503,
      body: {
        identity, title: kept.title || null, tried,
        error: 'no copy of this is ready to stream from the hub yet',
        warming: true, retryAfterMs: PLAY_RETRY_AFTER_MS,
        magnet: out.url,
        alternatives: listAlternatives(candidates, source, kept.title, ep),
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
        const strikes = noteNoByte(bufWho, pb.ms);
        // Twice the retry is the bargain a cold torrent is owed — its first byte has been measured
        // arriving seventeen seconds after we asked. Past that, with this same buffer never having taken
        // a byte of the copy, "still preparing" is a guess the hub has now watched fail, and the viewer
        // has spent a minute being asked to wait for a copy that is not answering. Say which copy was
        // tried and that another is likelier, and stop offering this one first while one exists.
        if (strikes >= PLAY_NOBYTE_TRIES && !buf.everBytes) {
          gateway.noteCold(out.torrent.hash, pb.ms, 'not one byte in ' + strikes + ' presses of an open read');
          log('PLAY COPY SILENT', identity, labelOf(source), 'presses=' + strikes, 'ms=' + pb.ms,
            'the hub has had this copy open and never received a byte of it');
          return {
            status: 404,
            body: {
              identity, title: kept.title || null, tried,
              error: 'this copy has not handed the hub a single byte, ' + strikes + ' presses in a row',
              detail: 'This copy has not sent the hub one byte, ' + strikes
                + ' presses in a row, while the hub was reading it. Another copy of this title is likelier to play.',
              silent: true, strikes,
              alternatives: listAlternatives(candidates, source, kept.title, ep),
            },
          };
        }
        log('PLAY WARMING', identity, labelOf(source), 'prebuffer incomplete, no measured byte',
          'presses=' + strikes);
        return {
          status: 503,
          body: {
            identity, title: kept.title || null, tried,
            error: 'this copy is still being prepared',
            warming: true, retryAfterMs: PLAY_RETRY_AFTER_MS,
            alternatives: listAlternatives(candidates, source, kept.title, ep),
          },
        };
      }
      // A byte is a byte: the copy is answering, so the count that would have condemned it starts over.
      if (Number(pb.bytes) > 0 || Number(out.measuredBytes) > 0) clearNoByte(bufWho);
    }
  }

  if (hubRelative) {
    // Already a hub route. Keep the relative form for callers that want it, and hand the player an
    // absolute one: this is what makes a file on the hub's own disk play at all.
    out.proxiedUrl = out.url;
    out.url = hubOrigin + out.url;
    out.proxied = true;
  } else if (/^https?:\/\//i.test(out.url)) {
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
  // the time the play budget has left. A non-torrent copy has no hash but may still hold several audio
  // tracks; when the ladder is asked for, one short probe of its URL supplies the languages and the true
  // picture height, so the ladder neither drops a language nor upscales a small file.
  const playHash = out.torrent && out.torrent.hash;
  const adaptiveWanted = opts.ladder === true
    && (settings.get().adaptive || {}).enabled === true
    && (settings.get().adaptive || {}).transcode !== false
    && out.kind !== 'hls' && !out.remuxed && !out.audioMix;
  const tracks = playHash
    ? await media.tracksFor(out.torrent.hash, out.torrent.fileId, null,
      { waitMs: Math.max(0, Math.min(1200, deadline - Date.now())) })
    : null;
  const probed = (!tracks && adaptiveWanted && /^https?:\/\//i.test(out.url))
    ? await media.probeUrl(out.url, {
      timeoutMs: Math.max(700, Math.min(3000, deadline - Date.now() - 300)),
    })
    : null;
  const picture = tracks || probed;
  const pictureHeight = picture && picture.video && Number(picture.video.height) > 0
    ? Number(picture.video.height) : null;
  // Copies, not the cache's own objects: the TV needs absolute subtitle URLs and the browser needs the
  // relative ones, and both read the same cached entry.
  // The codec the picture is in, so the player can say "this browser cannot draw H.265" instead of
  // serving a black screen with sound. Entries written before the probe recorded video have nothing to
  // report, and are left unknown rather than guessed at.
  out.videoCodec = (picture && picture.video && picture.video.codec) || source.videoCodec || null;
  if (decodable.length && out.videoCodec && !decodable.includes(ranking.codecFamily(out.videoCodec))) {
    extraWhy.push('this device cannot decode ' + ranking.codecFamily(out.videoCodec)
      + ', and no copy of this title it can play was found');
  }
  // The picture probe is the best truth, but it is not always there: a cold torrent's header read
  // runs out of the play budget, and the addon's own track list is then the only fact about the copy
  // being served. Dropping it left the player with no audio language at all, so the packager's
  // default (usually English) won and a Hindi profile saw no Hindi in the Audio menu — even when the
  // chosen file stated it. For a native pass-through (container "any") the addon's stream indices are
  // the very indices media3 addresses, so its list is safe to hand over.
  const sourceTracks = (container === 'any' && Array.isArray(source.audioTracks))
    ? source.audioTracks : [];
  out.audioTracks = ((picture && picture.audioTracks) || sourceTracks).map((t) => ({ ...t }));

  out.subtitleTracks = ((picture && picture.subtitleTracks) || []).map((t) => {
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
  // same normalizer this hub uses for every other language claim it makes. `prefAudio` was resolved with
  // the profile before ranking, so the audio picker and the donor search use the same order.
  // Preference order, not set membership: a profile that asked for Hindi then English must start in
  // Hindi even when the file happens to list English first and flags it as the container default.
  const byPref = prefAudio
    .map((lang) => out.audioTracks.find((t) => media.langOf(t.language) === lang))
    .find(Boolean) || null;
  let chosenAudio = byIndex(out.audioTracks, 'a', wantAudio) || byLanguage(out.audioTracks, opts.audio)
    // Nothing asked for: name the track the hub would recommend. The profile's own language order comes
    // first, and only then whatever flag the packager stamped on the file — measured on a torrent whose
    // Italian track carries the container's default bit while its English one sits second, and honouring
    // the file over the household played a Hindi-first viewer an Italian film. The native player takes
    // this as its starting selection, so a Hindi copy starts in Hindi without the viewer knowing the word
    // "audio" exists.
    || byPref
    || out.audioTracks.find((t) => t.default)
    || out.audioTracks[0] || null;
  out.selectedAudio = chosenAudio ? chosenAudio.id : null;
  const chosenSub = byIndex(out.subtitleTracks, 's', wantSub) || byLanguage(out.subtitleTracks, opts.sub);
  // A subtitle nobody asked for is not a subtitle to burn in: the browser starts with them off and the
  // native player keeps its own default.
  out.selectedSub = chosenSub ? chosenSub.id : null;

  // ---- separate audio donor ---------------------------------------------------------------
  //
  // A picture and its audio do not have to come from the same copy. If the chosen video copy does not
  // state the household's language, the hub looks for an audio-only row, or another copy whose file
  // states that track, and re-wraps the picture with the donor's audio as a second input. The donor is
  // never the primary video source; it is an input to remux, not a replacement for the picture.
  const mixOpt = opts.audioMix && typeof opts.audioMix === 'object' ? opts.audioMix : null;
  const mixRequested = opts.audioMix !== false && !(mixOpt && mixOpt.enabled === false);
  let desiredLang = null;
  if (mixRequested) {
    if (mixOpt && mixOpt.language) desiredLang = media.langOf(mixOpt.language);
    else if (typeof opts.audio === 'string' && !/^a:?\d+$/i.test(opts.audio)) desiredLang = media.langOf(opts.audio);
    else if (opts.audio == null && prefAudio.length) desiredLang = prefAudio[0];
    else if (chosenAudio) desiredLang = media.langOf(chosenAudio.language);
  }
  const primaryHas = desiredLang ? hasAudioLanguage(source, desiredLang) : false;
  const forcedMix = !!(mixOpt && mixOpt.force === true);
  // A hand pick says "give me this language even if the filename only claims it". It does not mean
  // "replace the track this file already carries". So measure the picture before honouring it: several
  // of this household's 1080p copies ship BOTH Hindi and English, and the old code re-muxed a donor
  // over the file's own Hindi track — the viewer pressed "Hindi", ffmpeg started a fresh read of a
  // 666 MB file, and the sound changed (if at all) many seconds later. A track in the picture is both
  // the better answer and the instant one.
  let pictureProbe = null;
  // ffmpeg and the header probe should read the bytes this box is already paying for, not a fresh
  // connection to a host that rationes them. On a direct internet copy that address is this hub's own
  // cushion: several ranged reads, the head of the file in RAM, cut reads retried by the hub. The probe
  // below and the remux further down both read it, so one fill serves both.
  let engineUrl = out.url;
  let engineHeadersIn = out.headers;
  if (desiredLang && mixRequested && out.mode !== 'gateway' && readahead.cfg().direct
      && /^https?:\/\//i.test(String(out.url || ''))) {
    const buf = readahead.acquire({ url: out.url, headers: out.headers || {} });
    if (buf) {
      const tok = mintBuffer({ url: out.url, headers: out.headers, need: out.neededMbps });
      const base = opts.clientOrigin || ('http://127.0.0.1:' + PORT);
      engineUrl = base + '/app/v1/buffer?t=' + tok
        + (Number.isFinite(out.neededMbps) ? '&need=' + out.neededMbps : '');
      // The cushion carries the origin's signed link and Referer on the hub side; nothing downstream
      // should send its own copy of them back to the hub.
      engineHeadersIn = {};
      // Pull the bytes ffmpeg will read while the /play request is still open. It parses the container
      // from byte 0 and then jumps to the playhead, so warm both ends: the head is always wanted, and
      // the playhead is where the film is. ffmpeg would otherwise fetch each of them itself at the front
      // of a 25 s first-byte budget, and on a host that rationes reads that wait is the difference
      // between switching language in a second and the viewer giving up.
      const head = await readahead.preBuffer(buf, {
        bytes: 8, sec: readahead.cfg().preSec, budgetMs: Math.max(500, Math.min(1500, deadline - Date.now())),
      });
      const size = Number(source && source.sizeBytes) || 0;
      const durMs = Number(kept.durationMs) || Number(out.probe && out.probe.durationMs) || 0;
      const at = size && durMs
        ? Math.floor(size * (Math.max(0, Number(opts.positionMs) || 0) / durMs)) : 0;
      const warm = at > 0 ? await readahead.sustain(buf, {
        at,
        needMbps: Number.isFinite(out.neededMbps) ? out.neededMbps : 0,
        budgetMs: Math.max(500, Math.min(2000, deadline - Date.now())),
      }) : head;
      log('REMUX INPUT CUSHIONED', identity, labelOf(source), 't=' + tok, 'at=' + at,
        'headMB=' + (head && head.MB != null ? head.MB : '?'),
        'mbps=' + (warm && Number.isFinite(warm.mbps) ? warm.mbps.toFixed(1) : '?'),
        'heldMB=' + Math.round(buf.held() / 1048576));
    }
  }
  if (desiredLang && mixRequested && !hasAudioTrack(source, desiredLang)
      && /^https?:\/\//i.test(String(engineUrl || '')) && deadline - Date.now() > 1200) {
    pictureProbe = await media.probeUrl(engineUrl, {
      timeoutMs: Math.max(700, Math.min(2000, deadline - Date.now() - 400)),
    });
    if (!pictureProbe) {
      log('AUDIO PICTURE PROBE FAIL', identity, labelOf(source), String(out.url).slice(0, 70));
    }
    if (pictureProbe && Array.isArray(pictureProbe.audioTracks) && pictureProbe.audioTracks.length) {
      out.audioTracks = pictureProbe.audioTracks.map((t) => ({ ...t }));
      out.probe = Object.assign({}, out.probe || {}, {
        durationMs: pictureProbe.durationMs || null,
        sizeBytes: pictureProbe.sizeBytes || null,
      });
      log('AUDIO PICTURE PROBED', identity, labelOf(source),
        pictureProbe.audioTracks.map((t) => (t.language || '?') + '@' + t.id).join(','));
    }
  }
  const pictureHasTrack = desiredLang ? hasAudioTrack(source, desiredLang)
    || hasAudioTrack(pictureProbe, desiredLang) : false;
  let donor = null;
  // The picture's own runtime, needed again by the mix metadata after the donor block closes.
  let pictureMs = 0;
  if (pictureHasTrack) {
    // Nothing to mix: the picture copy IS the language the viewer asked for. Select it as a track.
    const own = (out.audioTracks || []).find((t) => media.langOf(t.language) === desiredLang);
    if (own) {
      out.selectedAudio = own.id;
      chosenAudio = own;
      extraWhy.push('audio: this copy carries ' + (ranking.LANG_NAMES[desiredLang] || desiredLang)
        + ' itself, so its own track was selected — no donor, no re-mux');
    }
  }
  if (desiredLang && mixRequested && !pictureHasTrack && (forcedMix || !primaryHas)) {
    const donorPool = (allExcluded ? sources : pool).filter((s) => s !== source && !isExcluded(s, skip));
    let list = donorPool.filter((s) =>
      audioEvidence(s, desiredLang).fromTracks || audioEvidence(s, desiredLang).fromClaims);
    if (!list.length) {
      // A missing mix with nothing said about it is the hardest thing to diagnose afterwards: the copy
      // list the picker shows is a trimmed one, so "no donor" usually means "no donor was ever kept".
      log('AUDIO DONOR NONE', identity, desiredLang,
        'copies=' + donorPool.length,
        'byName=' + donorPool.filter((s) => audioEvidence(s, desiredLang).fromClaims).length,
        'byTrack=' + donorPool.filter((s) => audioEvidence(s, desiredLang).fromTracks).length);
    }
    if (mixOpt && mixOpt.source) {
      const want = mixOpt.source;
      list = list.filter((s) => String(s.id) === String(want) || s.label === want
        || (s._addon && (s._addon === want || s.provider === want)));
    }
    // A donor that needs no swarm is first, and a track the file itself states beats a filename claim.
    list.sort((a, b) => {
      const pa = (a.audioOnly ? 0 : 2) + (audioEvidence(a, desiredLang).fromTracks ? 0 : 1)
        + (a.kind === 'torrent' ? 1 : 0);
      const pb = (b.audioOnly ? 0 : 2) + (audioEvidence(b, desiredLang).fromTracks ? 0 : 1)
        + (b.kind === 'torrent' ? 1 : 0);
      return pa - pb;
    });
    // The file we picked is the picture whose length matters; a title's runtime is a fallback for a
    // movie only. A series' `kept.durationMs` is the show, not the episode, and comparing an episode
    // donor against it would refuse every valid series mix.
    const fileMs = Number(source.durationMs) || Number(source.probe && source.probe.durationMs)
      || Number(out.probe && out.probe.durationMs) || 0;
    pictureMs = fileMs || ((type === 'movie' || kept.type === 'movie')
      ? (Number(kept.durationMs) || 0) : 0);
    // A direct picture can be measured the same way as a donor: ffmpeg prints its duration from the
    // header. This is what makes the fit check work for a series episode (where the title runtime is
    // the show, not the episode) without ever comparing an episode to that show runtime.
    if (!pictureMs && list.length && out && out.url && source.kind !== 'torrent'
        && /^https?:/i.test(out.url) && deadline - Date.now() > 1400) {
      const hit = donorPeek(out.url);
      const p = hit || await probeDonorUrl(out.url, out.headers || {}, 1200);
      if (p && Number(p.durationMs) > 0) pictureMs = Number(p.durationMs);
    }
    for (const cand of list.slice(0, 6)) {
      if (Date.now() >= deadline) break;
      const target = await resolveAudioDonor(cand, kept.streamId || identity, opts, deadline);
      if (!target) continue;
      // A donor measured a different length than the film is a different cut; mapping it would play
      // the right language over the wrong scene, silently. Only a measured disagreement refuses —
      // an unreadable header is no opinion.
      const filmMs = pictureMs;
      const donorMs = Number(target.durationMs) || 0;
      if (filmMs > 0 && donorMs > 0 && Math.abs(donorMs - filmMs) > filmMs * DONOR_FIT_SLACK) {
        log('AUDIO DONOR SKIP', identity, (cand.label || cand.provider)
          + ': runs ' + Math.round(donorMs / 60000) + ' min against a '
          + Math.round(filmMs / 60000) + ' min picture');
        continue;
      }
      donor = { source: cand, target };
      break;
    }
  }
  let audioUrl = null;
  let audioOrd = null;
  let audioPlan = null;
  if (donor) {
    audioUrl = donor.target.url;
    // Null means "we did not measure this donor's stream index": the remux then maps its first audio
    // stream. Coercing that to 0 would map stream 0 of a video copy, which is the picture, not audio.
    // Prefer the donor file's own probed tracks: they are the truth about what ffmpeg will open.
    const donorTracks = Array.isArray(donor.target.audioTracks) ? donor.target.audioTracks : null;
    audioOrd = donorTracks ? ordinalFromTracks(donorTracks, desiredLang) : audioOrdinal(donor.source, desiredLang);
    audioPlan = donorTracks
      ? (remux.planFor({ audioTracks: donorTracks }, audioOrd) === 'copy' ? 'copy' : 'aac')
      : audioPlanFor(donor.source, desiredLang);
    // A codec read from the donor's own header overrides the row's claim: MP4 may carry AAC or MP3
    // untouched, and re-encoding something that needed no re-encoding is a quality loss nobody asked for.
    if (donor.target.audioCodec) {
      audioPlan = ['aac', 'mp3'].includes(donor.target.audioCodec) ? 'copy' : 'aac';
    }
    const track = {
      id: 'a:1',
      language: desiredLang,
      title: donor.source.label || donor.source.provider || 'audio donor',
      codec: null,
      default: true,
      kind: 'audio-mix',
    };
    // The remux output has one audio track, the donor, so the list the player sees is that output,
    // not the primary copy's original tracks.
    out.audioTracks = [track];
    out.selectedAudio = 'a:1';
    out.audioMix = {
      language: desiredLang,
      languageName: ranking.LANG_NAMES[desiredLang] || desiredLang,
      source: donor.source.label || donor.source.provider || null,
      provider: donor.source.provider || null,
      addon: donor.source._addon || null,
      confirmed: audioEvidence(donor.source, desiredLang).fromTracks,
      claimed: audioEvidence(donor.source, desiredLang).fromClaims,
      audioTrack: 'a:1',
      plan: audioPlan,
      // What the hub measured about the donor file itself: its length, and the drift against the
      // picture it was accepted for. Null means the header was never readable.
      donorDurationMs: donor.target.durationMs || null,
      driftMs: (donor.target.durationMs && pictureMs)
        ? donor.target.durationMs - pictureMs : null,
    };
    extraWhy.push('audio: mixed ' + (ranking.LANG_NAMES[desiredLang] || desiredLang)
      + ' from ' + (donor.source.label || donor.source.provider || 'a donor')
      + ' while keeping the picture copy');
  }

  // Every language this title can actually offer, not just the one track the chosen file happens
  // to carry. A mixed output replaces the file's audio with one donor; this list is what lets the
  // Quality/Audio menu ask for another language without changing the picture copy.
  const audioOptions = [];
  // Two keys, because the two kinds answer different questions. A "track" row is a stream inside
  // the copy on screen and a language can hold several of them (the Bluey copy carries AC3 5.1 and
  // AAC 2.0, both English) — keying those by language hid the AAC one, so a panel that refuses AC3
  // was offered the very track it had already failed on. A "mix" row is a donor language the hub
  // can remux, and there is only ever one of those per language.
  const optionByKey = new Map();
  const optionScore = (o) => (o.kind === 'track' ? 4 : 0) + (o.confirmed ? 2 : 0)
    + (o.provider !== 'torrent' ? 1 : 0);
  const addAudioOption = (raw, kind, sourceLabel, confirmed, trackId, codec, channels) => {
    const lang = media.langOf(raw);
    if (!lang || lang === 'und' || lang === 'mis') return;
    const key = kind === 'track' ? 't:' + (trackId || lang) : 'm:' + lang;
    const next = {
      id: (kind === 'track' && trackId ? trackId : 'mix:' + lang),
      language: lang,
      languageName: ranking.LANG_NAMES[lang] || lang,
      kind,
      source: sourceLabel || null,
      confirmed: confirmed === true,
      // What the stream is, so two rows in the same language read differently: "English · AC3 5.1"
      // next to "English · AAC 2.0". Without this the menu showed two identical English rows.
      codec: kind === 'track' ? (codec || null) : null,
      channels: kind === 'track' ? (channels || null) : null,
      title: kind === 'track'
        ? (ranking.LANG_NAMES[lang] || lang) + ' · in this copy'
        : (ranking.LANG_NAMES[lang] || lang) + (sourceLabel ? ' · from ' + sourceLabel : ''),
    };
    const before = optionByKey.get(key);
    if (!before || optionScore(next) > optionScore(before)) optionByKey.set(key, next);
  };
  const pickedLabel = (out.picked && out.picked.label) || source.label || null;
  // When a donor is active the output carries only that track; the picture's original language is
  // the "Original audio" toggle, not a second track in this list.
  for (const t of out.audioTracks || []) {
    const mixed = t.kind === 'audio-mix';
    addAudioOption(t.language, mixed ? 'mix' : 'track',
      mixed ? (donor && donor.source.label) : pickedLabel, true, mixed ? null : t.id,
      mixed ? null : t.codec, mixed ? null : t.channels);
  }
  // The audio menu is a property of the TITLE, not of the copy the viewer happens to be watching.
  // Deriving it from the playable pool meant the language list changed with the resolution: a copy
  // switched to 4K hid the Hindi donor that only existed in a 480p file, and switching back brought
  // it again. Every copy of the title is a possible donor; `exclude` names failed pictures, not
  // failed audio tracks, so it does not apply here either.
  const donorCandidates = sources.filter((s) => s !== source);
  for (const cand of donorCandidates) {
    const provider = cand.kind === 'torrent' ? 'torrent' : (cand.provider || cand._addon || null);
    for (const t of (cand.audioTracks || [])) {
      addAudioOption(t.language, 'mix', cand.label || provider, true, null);
    }
    const claims = new Set([
      ...(Array.isArray(cand._langs) ? cand._langs : []),
      ...(Array.isArray(cand.audioLangs) ? cand.audioLangs : []),
      ...languageClaimsFromText((cand.probe && cand.probe.fileName) || cand.label || ''),
    ]);
    for (const l of claims) addAudioOption(l, 'mix', cand.label || provider, false, null);
  }
  // Deterministic order: the household's own languages first, then the rest alphabetically, so the
  // menu reads the same after every quality/source switch.
  const langRank = (o) => {
    const i = prefAudio.indexOf(media.langOf(o.language));
    return i < 0 ? 99 : i;
  };
  // A language the picture already carries is not a donor request. Keeping both a track row and a
  // "mix from another copy" row for English made the menu offer to replace a track with itself.
  const trackLangs = new Set([...optionByKey.values()]
    .filter((o) => o.kind === 'track').map((o) => o.language));
  out.audioOptions = [...optionByKey.values()]
    .filter((o) => o.kind === 'track' || !trackLangs.has(o.language))
    .sort((a, b) => langRank(a) - langRank(b)
      || (a.kind === 'track' ? 0 : 1) - (b.kind === 'track' ? 0 : 1)
      || String(a.languageName).localeCompare(String(b.languageName)))
    .slice(0, 12);

  const remuxNeeded = !!donor || (out.mode === 'gateway' && out.torrent
    && (out.container === 'mp4' || out.container === 'hls'));
  if (remuxNeeded) {
    if (donor && out.container === 'any') out.container = 'mp4';
    const engine = out.mode === 'gateway' && out.torrent
      ? gateway.enginePlayUrl(out.torrent.hash, out.torrent.fileId)
      : engineUrl;
    const engineHeaders = out.mode === 'gateway' ? out.headers : engineHeadersIn;
    const audioIdx = donor ? null
      : (wantAudio != null ? wantAudio
        : (chosenAudio ? Number(String(chosenAudio.id).slice(2)) : null));
    const plan = donor ? audioPlan : remux.planFor(tracks, Number.isInteger(audioIdx) ? audioIdx : null);
    const common = {
      url: engine,
      audio: out.selectedAudio,
      sub: out.selectedSub,
      audioUrl,
      audioOrd,
      audioPlan: plan,
      tracks,
      // For a direct copy there is no probe entry; carry the facts the remux needs to map a byte
      // range to a time so seeking does not restart the film.
      sizeBytes: (tracks && tracks.sizeBytes) || (source && source.sizeBytes) || 0,
      durationMs: (tracks && tracks.durationMs) || Number(kept.durationMs) || 0,
      // A remux is a fresh ffmpeg run from this film position. The client must add it back to its
      // own timeline; without it a seek looks like the film restarted at zero.
      startMs: Math.max(0, Number(opts.positionMs) || 0),
      headers: engineHeaders,
      audioHeaders: donor && donor.target.headers ? donor.target.headers : null,
    };
    const rel = out.container === 'mp4'
      ? remux.mp4Url(common)
      : remux.hlsUrl(common);
    out.proxiedUrl = rel;
    out.url = (opts.clientOrigin || 'http://127.0.0.1:' + PORT) + rel;
    out.proxied = true;
    out.remuxed = true;
    out.remux = {
      container: out.container,
      video: 'copy',
      audio: plan,
      audioCodec: donor ? null : (chosenAudio ? chosenAudio.codec : null),
      subtitle: out.selectedSub ? 'mov_text' : null,
      mixedAudio: !!donor,
      startMs: Math.max(0, Number(opts.positionMs) || 0),
    };
    extraWhy.push(donor
      ? 'container: fragmented MP4, video copied, donor audio re-encoded into the film'
      : out.container === 'mp4'
        ? 'container: fragmented MP4, video copied'
          + (plan === 'aac'
            ? ', audio re-encoded to AAC because '
              + (chosenAudio && chosenAudio.codec ? chosenAudio.codec.toUpperCase() : 'that codec')
              + ' cannot live in an MP4 a browser will play'
            : plan === 'none' ? ', this copy has no audio track' : ', audio copied')
        : 'container: HLS segments, video copied');
  } else if (out.container !== 'any') {
    extraWhy.push(out.mode === 'gateway'
      ? 'container: asked for ' + out.container + ', but this copy is not a P2P copy — handed over as it is'
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
  out.durationMs = (out.probe && out.probe.durationMs) || (picture && picture.durationMs)
    || kept.durationMs || null;
  // The kind of thing this is, so a player that arrived by identity (a deep link, a zap) does not have to
  // guess whether it is holding a film or a channel.
  out.type = kept.type || (out.kind === 'hls' ? 'channel' : 'movie');
  out.live = out.type === 'channel';
  out.title = kept.title || null;
  // The identity this answer belongs to. A client can refuse a reply that names another title rather
  // than putting a different film on screen because a provider answered the wrong address.
  out.identity = identity;
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
  // The row for the copy that is playing needs the same one-word label as every other row.
  if (out.picked && !out.picked.resolution) {
    out.picked = Object.assign({}, out.picked, { resolution: resolutionOf(source) });
  }
  out.alternatives = listAlternatives(candidates, source, kept.title, ep);
  // A copy this panel's decoder cannot draw is not a quality choice. Say so on the row instead of
  // letting the viewer pick it and land back on the current picture with no explanation.
  if (decodable.length) {
    for (const alt of out.alternatives) {
      const fam = alt.videoCodec ? ranking.codecFamily(alt.videoCodec) : null;
      if (fam && !decodable.includes(fam)) alt.unsupported = true;
    }
  }
  if (labelOverride) out.labelMismatch = labelOverride;
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
  const intro = await introPromise;
  if (intro) {
    out.introStartMs = intro.startMs;
    out.introEndMs = intro.endMs;
    out.why = (out.why || []).concat(
      'intro: IntroDB marks the opening at ' + Math.round(intro.startMs / 1000) + '–'
      + Math.round(intro.endMs / 1000) + ' s',
    );
  }
  // Optional adaptive ladder. It is deliberately the last step: the picker has already chosen a
  // copy, audio/subtitles have been settled, and a donor mix is not something a transcoder should
  // silently replace. Only one source, transcoded into aligned variants, so the player switches
  // between renditions of the same timeline rather than between unrelated files.
  const adaptive = settings.get().adaptive || {};
  if (adaptiveWanted && out && out.url && !out.live
      && !out.remuxed && !out.audioMix && out.kind !== 'hls') {
    try {
      const sourceTracks = Array.isArray(out.audioTracks)
        ? out.audioTracks.filter((t) => t && t.streamIndex != null)
        : [];
      const chosen = sourceTracks.find((t) => t && t.id === out.selectedAudio) || sourceTracks[0] || null;
      const built = await ladder.create({
        url: out.url,
        headers: out.headers || {},
        quality: out.picked && out.picked.quality,
        // The probed picture height, when there is one, decides the ladder's top rung. A 360p file
        // must not get a 720p transcode, and a 1080p file named without a quality must still be 1080.
        sourceHeightPx: pictureHeight,
        audio: chosen ? { streamIndex: chosen.streamIndex, language: chosen.language } : null,
        // Every track the file states, so a language change is a rendition change at a segment
        // boundary rather than a new /play that could land on a different copy.
        audioTracks: sourceTracks,
        // The owner's ceiling and the client's own display ceiling; the lower wins. A 4K source on a
        // 1080 panel must not spend CPU producing a 2160 rung the decoder will refuse.
        maxHeight: Math.min(Number(adaptive.maxHeight) || 1080, Number(opts.displayMaxHeight) || 1080),
        // Resume keeps the ladder instead of falling back to the direct copy: the transcoder seeks to
        // the playhead, and the player carries `startMs` so the timeline still reads in film time.
        positionMs: Number(opts.positionMs || 0),
        sourceDurationMs: picture && Number(picture.durationMs) > 0 ? Number(picture.durationMs) : 0,
      });
      if (built && built.ready) {
        const base = opts.clientOrigin || ('http://127.0.0.1:' + PORT);
        out.url = base + built.path;
        out.kind = 'hls';
        out.mode = 'ladder';
        out.container = 'hls';
        out.proxied = true;
        out.remuxed = true;
        // A multi-audio ladder carries an EXT-X-MEDIA group; each entry is a real rendition of the
        // same timeline, so the player can switch tracks without re-opening the copy. A single-audio
        // ladder stays muxed and keeps the old empty list.
        if ((built.audioTracks || []).length > 1) {
          out.audioTracks = built.audioTracks;
          out.selectedAudio = built.selectedAudio;
        } else {
          out.audioTracks = [];
          out.selectedAudio = null;
        }
        out.subtitleTracks = [];
        out.selectedSub = null;
        out.ladder = {
          id: built.id,
          variants: built.variants || [],
          audio: (built.audioTracks || []).length,
          startMs: built.startMs || 0,
        };
        out.why = (out.why || []).concat('adaptive ladder: ' + (built.variants || []).join('/')
          + ((built.audioTracks || []).length > 1 ? ', ' + built.audioTracks.length + ' audio renditions' : '')
          + (built.startMs > 0 ? ', from ' + Math.round(built.startMs / 1000) + ' s' : '')
          + ', aligned segments');
        log('LADDER PLAY', identity, built.id, 'from=' + (built.startMs || 0) + 'ms',
          'source=' + String(out.picked && out.picked.label || '').slice(0, 60));
      } else if (built) {
        out.why = (out.why || []).concat('adaptive ladder warming; playing the chosen copy directly');
      }
    } catch (e) {
      log('LADDER FAIL', identity, e.message);
    }
  }
  // ---- hand the screen the hub, not the host ---------------------------------------------------
  // This route used to give a direct addon link straight to the player, which put one rationed socket
  // between the viewer and the film: measured on this host, 5 Mbps for the first ten megabytes, 2 Mbps by
  // 25 MB, a stall at 30 MB, and a picture that froze four minutes into a 1080p copy. Every copy the
  // player opens out of the internet now goes through the cushion in this box's RAM — the screen reads at
  // LAN speed while the hub keeps several ranged reads going and paces itself. The origin's own headers
  // (a signed link's key, the Referer a host insists on) stay here, where the screen never has to know
  // them, and a host that refuses a suffix range can no longer kill playback of an otherwise fine file.
  const cushioned = readahead.cfg().direct
    && source && source._noCushion !== true
    && !out.remuxed && !out.audioMix && !out.live
    && out.mode !== 'gateway' && out.mode !== 'ladder'
    && /^https?:\/\//i.test(String(out.url || ''));
  if (cushioned) {
    // The picker normally arrives here with the copy already measured through its cushion. A copy that
    // was chosen by the fallback scoring (nothing cleared the bar, so the least bad one wins) never got
    // that far, and it still deserves the buffer: it is the copy the household is about to watch.
    const buf = readahead.acquire({ url: out.url, headers: out.headers || {} });
    if (buf) {
      if (!Number.isFinite(out.neededMbps)) {
        const need = ranking.estimateMbps(source, kept);
        if (Number.isFinite(need)) out.neededMbps = Number(need.toFixed(2));
      }
      readahead.noteNeed({ url: out.url }, out.neededMbps);
      if (!out.prebuffer) {
        const pb = await readahead.preBuffer(buf, {
          bytes: 6, sec: readahead.cfg().preSec, budgetMs: 1200,
        });
        out.prebuffer = {
          ms: pb.ms, bytes: pb.bytes, sec: pb.sec, MB: pb.MB,
          neededMbps: out.neededMbps, complete: pb.complete,
        };
      }
      out.buffered = true;
      const base = opts.clientOrigin || ('http://127.0.0.1:' + PORT);
      const tok = mintBuffer({ url: out.url, headers: out.headers, need: out.neededMbps });
      out.originUrl = out.url;
      out.proxiedUrl = '/app/v1/buffer?t=' + tok
        + (Number.isFinite(out.neededMbps) ? '&need=' + out.neededMbps : '');
      out.url = base + out.proxiedUrl;
      out.proxied = true;
      out.headers = {};
      out.why = (out.why || []).concat(
        'buffered at the hub: it holds ~' + ((out.prebuffer && out.prebuffer.MB) || out.bufferedMB || 0)
        + ' MB and reads the host with ' + readahead.cfg().conns + ' connections, so this screen never waits on one',
      );
      log('PLAY BUFFERED', identity, labelOf(source), 't=' + tok,
        'bufferedMB=' + ((out.prebuffer && out.prebuffer.MB) != null ? out.prebuffer.MB : out.bufferedMB),
        'need=' + (out.neededMbps == null ? '?' : out.neededMbps),
        'measured=' + (out.measuredMbps == null ? '?' : out.measuredMbps));
    }
  }
  // Be honest when the household's first language is nowhere in what is about to play, after the
  // donor block has had its chance: the client can say so instead of quietly starting in another
  // language. A missing Hindi track is a fact about this title's copies, not about the player.
  if (prefAudio.length) {
    const have = new Set((out.audioTracks || []).map((t) => media.langOf(t.language)));
    if (!have.has(prefAudio[0])) {
      out.audioMissing = prefAudio[0];
      out.audioMissingName = ranking.LANG_NAMES[prefAudio[0]] || prefAudio[0];
    }
  }
  return { status: 200, body: out };
}

/** The browser projection of the curated registry, for web boot and its settings picker. */
function themeCatalog() {
  try {
    const j = JSON.parse(fs.readFileSync(path.join(THEME_DIR, 'themes.json'), 'utf8'));
    return Array.isArray(j.themes) ? j.themes.filter((theme) => (
      theme && typeof theme.id === 'string' && typeof theme.asset === 'string'
      && Array.isArray(theme.targets) && theme.targets.includes('web')
    )) : [];
  } catch (e) {
    return [];
  }
}

/** A requested theme and the browser renderer's truthful effective choice. */
function resolveWebTheme(requested = String(settings.get().theme || 'scenix-apple')) {
  const themes = themeCatalog();
  const wanted = String(requested || '').trim() || 'scenix-apple';
  const requestedChoice = themes.find((theme) => theme.id === wanted);
  const fallback = themes.find((theme) => theme.id === 'scenix-apple') || themes[0]
    || { id: 'scenix-apple', name: 'Apple', asset: 'theme-apple.json', targets: ['web'] };
  return {
    themes,
    wanted,
    choice: requestedChoice || fallback,
    available: !!requestedChoice,
  };
}

function isSharedTheme(id) {
  return themeCatalog().some((theme) => theme.id === String(id || '').trim());
}

function themePayload() {
  const { themes, wanted, choice, available } = resolveWebTheme();
  let tokens = null;
  try {
    // The manifest is the allow-list; an id from a request never becomes a path.
    tokens = JSON.parse(fs.readFileSync(path.join(THEME_DIR, path.basename(String(choice.asset))), 'utf8'));
  } catch (e) { /* the page keeps app.css defaults */ }
  return {
    // `id` keeps older web shells working. New callers must render effectiveId and can surface a
    // clear explanation when an old household setting names a TV-only pack.
    id: choice.id,
    name: choice.name,
    requestedId: wanted,
    effectiveId: choice.id,
    available,
    reason: available ? '' : 'This theme is available on this TV only',
    themes,
    tokens,
  };
}

/** Registry ids are kept in local provenance, so browse state never needs to expose an add-on URL. */
function reviewedInstalledIds() {
  const ids = new Set();
  for (const addon of addons.entries()) {
    const provenance = addon && addon.provenance;
    if (provenance && provenance.kind === 'reviewed' && typeof provenance.registryId === 'string') {
      ids.add(provenance.registryId);
    }
  }
  return ids;
}

function reviewedDirectoryOptions(u) {
  return {
    category: u.searchParams.get('category'),
    type: u.searchParams.get('type'),
    q: u.searchParams.has('q') ? u.searchParams.get('q') : u.searchParams.get('query'),
    installedIds: reviewedInstalledIds(),
  };
}

/** Community provenance remains separate from reviewed provenance even after a source is installed. */
function communityInstalledIds() {
  const ids = new Set();
  for (const addon of addons.entries()) {
    const provenance = addon && addon.provenance;
    if (provenance && provenance.kind === 'community' && typeof provenance.directoryId === 'string') {
      ids.add(provenance.directoryId);
    }
  }
  return ids;
}

function communityDirectoryOptions(u) {
  return {
    type: u.searchParams.get('type'),
    q: u.searchParams.has('q') ? u.searchParams.get('q') : u.searchParams.get('query'),
    installedIds: communityInstalledIds(),
  };
}

/** Safe data retained in a one-time configuration handoff — never a manifest/config address. */
function sourceSetupSessionSource(kind, entry) {
  if (!entry || !entry.configurationRequired) return null;
  return {
    kind,
    id: kind === 'reviewed' ? entry.id : entry.id,
    name: entry.name,
    revision: entry.version,
  };
}

/** A registry/cache refresh while somebody is configuring is a safety event, not an install fallback. */
function sourceSetupMatches(sessionSource, entry, kind) {
  return !!(sessionSource && entry && sessionSource.kind === kind
    && sessionSource.id === entry.id && sessionSource.name === entry.name
    && sessionSource.revision === entry.version && entry.configurationRequired === true);
}

/**
 * Resolve and install the original descriptor only after a one-time code has been reserved.  The
 * configured address comes from the web body, is validated against the original HTTPS origin, and
 * is passed straight to the existing add-on boundary; it is never included in a response or log.
 */
async function installConfiguredSourceSetup(sessionSource, configuredManifest) {
  if (sessionSource.kind === 'reviewed') {
    let entry;
    try { entry = reviewedAddons.find(sessionSource.id); } catch (error) {
      return { ok: false, status: 503, error: 'reviewed source setup is unavailable' };
    }
    if (!sourceSetupMatches(sessionSource, entry, 'reviewed')) {
      return { ok: false, status: 409, error: 'this reviewed source changed; start setup again' };
    }
    const url = sourceSetupCore.configuredManifestUrl(configuredManifest, entry.manifest);
    if (!url.ok) return { ok: false, status: 422, error: url.error };
    return addons.add(url.value, entry.name, {
      provenance: { kind: 'reviewed', registryId: entry.id, revision: entry.version },
      redirectPolicy: 'same-origin',
      validateManifest: (manifest) => reviewedAddons.validateRemoteManifest(entry, manifest),
    });
  }
  if (sessionSource.kind === 'community') {
    let entry;
    try { entry = await communityDirectory.find(sessionSource.id); } catch (error) {
      return { ok: false, status: 503, error: 'community source setup is unavailable' };
    }
    if (!sourceSetupMatches(sessionSource, entry, 'community')) {
      return { ok: false, status: 409, error: 'this Community source changed; start setup again' };
    }
    const url = sourceSetupCore.configuredManifestUrl(configuredManifest, entry.manifestUrl);
    if (!url.ok) return { ok: false, status: 422, error: url.error };
    return addons.add(url.value, entry.name, {
      provenance: { kind: 'community', directoryId: entry.id, revision: entry.version },
      redirectPolicy: 'same-origin',
      validateManifest: (manifest) => communityDirectory.validateConfiguredManifest(entry, manifest),
    });
  }
  return { ok: false, status: 409, error: 'this setup code has an unsupported source' };
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
  // The one line that answers "did this request even reach the hub?" — a stream URL the screen re-opens
  // with a Range header is where playback problems are decided, and without the incoming Range in the
  // log the hub can only report what it answered, not what it was asked.
  if (process.env.LOG_REQUESTS === '1') {
    log('REQ IN', req.method, raw, 'range=' + (req.headers.range || '-'),
      'ua=' + String(req.headers['user-agent'] || '-').slice(0, 40));
  }

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
    // What the navigation may offer, in one small answer: the app asks this at start-up instead of
    // downloading the manifest to count add-ons. A household without the games add-on, or without a
    // live playlist, gets no tab for a mode it cannot enter.
    if (path === '/app/v1/caps') {
      return send(res, 200, await navCaps());
    }

    // ---- the hub's own build ----
    // The household updates its television from GitHub Releases; this route is how the hub follows
    // on the same day instead of becoming the one stale piece. GET answers the git facts (current
    // commit, how far behind, and the honest reason when a self-pull is not allowed); POST pulls and
    // restarts the process — which is why any client may press it: the TV after installing a new
    // APK, the browser's Settings, or the hub's own six-hourly pass.
    if (path === '/app/v1/hub/update') {
      if (req.method === 'POST') {
        const r = await hubupdate.update();
        if (r.updated && hubupdate.selfRestart()) {
          log('HUB SELF UPDATE', r.from + ' -> ' + r.to, 'restarting for the new code');
        }
        return send(res, 200, r);
      }
      return send(res, 200, await hubupdate.status(u.searchParams.has('check')));
    }

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
      linkMbps: linkMbpsNow() == null ? null : Number(linkMbpsNow().toFixed(2)),
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
    if (path === '/app/v1/settings/unlock' && req.method === 'POST') {
      const body = await readBody(req);
      const lock = settings.get().lock || {};
      if (!lock.pinHash) return send(res, 200, { ok: true, pinSet: false });
      if (!unlockAllowed('settings-lock')) {
        return send(res, 429, { error: 'too many attempts', retryAfterMs: 300000 });
      }
      if (pinMatches({ pinHash: lock.pinHash }, body && body.pin)) {
        unlockSucceeded('settings-lock');
        const session = mintAdminSession(req);
        return send(res, 200, {
          ok: true,
          pinSet: true,
          adminSession: session.token,
          adminSessionExpiresAt: session.expiresAt,
        });
      }
      unlockFailed('settings-lock');
      return send(res, 403, { error: 'wrong PIN', ok: false });
    }

    if (path === '/app/v1/settings/lock' && req.method === 'POST') {
      const body = await readBody(req);
      const cur = settings.get().lock || {};
      const wantsEnabled = body && body.enabled === true;
      const sessionIsValid = validAdminSession(req);
      if (cur.pinHash) {
        const given = (body && (body.pinCurrent || body.current)) || '';
        if (!sessionIsValid) {
          // Changing or disabling the lock is also a PIN-verification route.  It must share the
          // unlock rate limit; otherwise this endpoint becomes an unlimited PIN oracle.
          if (!unlockAllowed('settings-lock')) {
            return send(res, 429, { error: 'too many attempts', retryAfterMs: 300000 });
          }
          if (!pinMatches({ pinHash: cur.pinHash }, given)) {
            unlockFailed('settings-lock');
            return send(res, 403, { error: 'the current settings PIN is needed', gate: 'settings-lock' });
          }
          unlockSucceeded('settings-lock');
        }
      }
      let pinHash = cur.pinHash || '';
      const fresh = body && typeof body.pin === 'string' ? body.pin.trim() : '';
      if (body && body.pin === null) {
        // Explicit null removes the stored PIN (after the current one was proved above).
        pinHash = '';
      } else if (fresh) {
        if (!/^\d{4,8}$/.test(fresh)) return send(res, 400, { error: 'the PIN must be 4 to 8 digits' });
        pinHash = hashPin(fresh);
      }
      if (wantsEnabled && !pinHash) {
        return send(res, 400, { error: 'set a PIN before turning the lock on' });
      }
      const next = settings.set({ lock: { enabled: wantsEnabled, pinHash } });
      // A changed lock/PIN invalidates every outstanding capability. When it remains enabled, the
      // request that just proved the PIN receives one fresh session so the settings visit continues.
      clearAdminSessions();
      const session = (next.lock || {}).enabled === true && (next.lock || {}).pinHash
        ? mintAdminSession(req) : null;
      return send(res, 200, {
        ok: true,
        lock: { enabled: (next.lock || {}).enabled === true, pinSet: !!((next.lock || {}).pinHash) },
        ...(session ? {
          adminSession: session.token,
          adminSessionExpiresAt: session.expiresAt,
        } : {}),
      });
    }

    if (path === '/app/v1/theme') {
      return send(res, 200, themePayload());
    }

    if (path === '/app/v1/settings' && req.method === 'GET') {
      const options = await catalogOptions();
      return send(res, 200, {
        // A debrid token is write-only. This shape carries `tokenSet` instead; the settings screen can
        // say "set" or "not set" and never receives the secret back over the network.
        settings: settings.redacted(settings.get()),
        defaults: settings.redacted(settings.DEFAULTS),
        themes: themeCatalog(),
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
            ? {
              channels: p.channelList(), groups: p.channelGroups(),
              // What core/guide.js last saw: {configured, channels, programmes, updatedAt, error}.
              // The Live settings screen shows it under the guide address, and the wall's tiles
              // already carry `now`/`next` from the same source.
              guide: require('./core/guide').status(),
            }
            : { channels: [], groups: [] };
        })(),
      });
    }
    if (path === '/app/v1/settings' && req.method === 'PUT') {
      if (!requireAdminSession(req, res)) return;
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
      // The lock is changed only through its own route (it needs the current PIN, and the GET redacts
      // the hash). Settings screens send back the whole settings object they read, so an unmodified
      // `lock: {enabled, pinSet}` in this PUT must never be allowed to erase the stored hash.
      delete body.lock;
      if (Object.prototype.hasOwnProperty.call(body, 'theme') && !isSharedTheme(body.theme)) {
        return send(res, 400, {
          error: 'this theme is available on this TV only',
          requestedId: String(body.theme || ''),
          themes: themeCatalog().map((theme) => ({
            id: theme.id, name: theme.name, targets: theme.targets,
          })),
        });
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
      // The household Settings lock protects the whole administration surface, including profile
      // creation. Profile *unlock* below stays reachable without this capability: it proves a
      // profile's own PIN and must not become circular when an owner needs either proof.
      if (!requireAdminSession(req, res)) return;
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
      if (!requireAdminSession(req, res)) return;
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
      if (!requireAdminSession(req, res)) return;
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
        rowsToBuild.map(async (row) => {
          const full = generalItems(row.id, await buildRow(row, ctx, HOME_BUDGET_MS));
          // `total` rides along with the trimmed shelf: a row that shows its eighteen delivered titles is
          // not hiding anything, and the web stops drawing a See-all tile on it. A row trimmed from a
          // longer answer says so honestly.
          // A row that can read the viewer's own history writes its headline per request —
          // "Because you watched City Hunter", not a template. `label` falls back to the static
          // title when it has nothing to say, so every consumer still always receives a string.
          const title = typeof row.label === 'function'
            ? (await Promise.resolve(row.label(ctx)).catch(() => null)) || row.title
            : row.title;
          return {
            id: row.id,
            title,
            card: row.card,
            order: row.order,
            scope: row.scope || 'all',
            total: full.length,
            items: full.slice(0, HOME_ROW_MAX),
          };
        }),
      );
      // Owner order, applied once the rows exist: names in `rows.order` come first in that sequence and
      // everything else keeps the order its plugin asked for, so a hub gains a row plugin and the screen
      // still places it sensibly without the owner having to re-list every row.
      // A profile's own order runs first, then the household's, then the plugin's. This is what lets one
      // person put Live TV above the shelves and another leave the default.
      const localOrder = (profile.prefs && profile.prefs.rows && Array.isArray(profile.prefs.rows.order))
        ? profile.prefs.rows.order : [];
      const globalOrder = (settings.get().rows || {}).order || [];
      const wanted = [...localOrder, ...globalOrder.filter((id) => !localOrder.includes(id))];
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
      // Every tile carries this viewer's own match percentage and one shared definition of "New".
      // The fingerprint is built from data/progress.json only; no recommendation service is involved.
      await taste.decorate(ctx, filled.flatMap((r) => r.items));
      return send(res, 200, {
        rows: arrangeHome(filled).map((r) => ({ ...r, items: shelfItems(r.items) })),
      });
    }

    if (path === '/app/v1/games') {
      // Games are not titles: they carry `playUrl` and a controller flag instead of sources, which
      // is why they ride their own endpoint. Built-in shelves answer at once; game-carrying addons
      // are asked in parallel from cache, and a dead one costs a cached miss, never a hung request.
      return send(res, 200, { rows: await gamesApi.rows() });
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
      // A section page is the shelf itself, so it gets the deeper cap — but a row that says "I am a
      // wall" (rows/live.js declares 700 channels) gets its own number. Capping a 358-channel wall at
      // 120 is how the remote's zap ring ran out of channels it had never heard of.
      const cap = Math.max(SECTION_MAX, Number(row.max) || 0);
      const items = generalItems(row.id, await buildRow(row, ctx, undefined, cap));
      await taste.decorate(ctx, items);
      const title = typeof row.label === 'function'
        ? (await Promise.resolve(row.label(ctx)).catch(() => null)) || row.title
        : row.title;
      return send(res, 200, {
        id: row.id, title, card: row.card, order: row.order, scope: row.scope || 'all',
        items: shelfItems(items),
      });
    }

    if (path === '/app/v1/live/channels') {
      // The channel manager's list, and the zap ring's list, from one place: the live row itself, with
      // the profile's rules and lineup already applied. `channels` is in zap order — the order the
      // remote walks — and `unlisted` says how many channels the profile's lineup is holding back, so a
      // screen can say "your lineup shows 12 of 358" instead of quietly hiding the rest.
      const profile = store.profile(u.searchParams.get('profile') || u.searchParams.get('profileId'));
      const row = registry.rows.find((r) => r.id === 'live');
      const live = registry.byId['live-tv'];
      if (!row) {
        return send(res, 200, {
          channels: [], lineup: { only: [], order: [], hidden: [] },
          status: { playlist: null, channels: 0, lastError: 'this hub has no live row' },
        });
      }
      if (!rowVisible(row, profile)) {
        return send(res, 403, { error: 'live TV is not for this profile', scope: row.scope });
      }
      const ctx = makeCtx(profile, HEALTH_BUDGET_MS);
      const items = generalItems('live', await buildRow(row, ctx, LIVE_WALL_BUDGET_MS, LIVE_WALL_MAX));
      const local = (profile.prefs && profile.prefs.live) || {};
      const wall = await channelItems(ctx);
      // The lineup vocabulary is the profile's channel ids. The provider's tvg id would be nicer for
      // a person editing by hand, but the prefs editor, the live-health numbers and the zap ring all
      // speak `norm:<slug>` today, so the wall's identity is the lineup key everywhere. `channel` is
      // still published so a future editor can show the operator's name beside it.
      const only = Array.isArray(local.only) ? local.only : [];
      const order = Array.isArray(local.order) ? local.order : [];
      const hiddenLocal = Array.isArray(local.hidden) ? local.hidden : [];
      const channelOf = (it) => (it.live && it.live.channel) || it.identity;
      // In the lineup = it is one of the profile's chosen channels: in the watch-only gate OR anywhere
      // in the favourites order. Order alone still means "chosen" — the gate only decides whether the
      // OTHER channels are hidden from Live TV, not whether these belong to the viewer.
      const inLineup = (it) => only.includes(it.identity) || only.includes(channelOf(it))
        || order.includes(it.identity) || order.includes(channelOf(it));
      return send(res, 200, {
        channels: items.map((it) => {
          const lv = it.live || {};
          return {
            identity: it.identity,
            channel: channelOf(it),
            name: it.title,
            group: (it.genres || [])[0] || '',
            groups: it.genres || [],
            logo: it.logo || it.poster || null,
            number: lv.number || null,
            quality: lv.quality || null,
            health: lv.health || 'unknown',
            error: lv.error || null,
            checkedAt: lv.checkedAt || null,
            now: lv.now || null,
            next: lv.next || null,
            inLineup: inLineup(it),
            languages: it.languages || null,
          };
        }),
        lineup: { only, order, hidden: hiddenLocal },
        unlisted: Math.max(0, (wall?.length ?? 0) - items.length),
        groups: (() => {
          const set = new Set();
          for (const it of items) for (const g of it.genres || []) set.add(g);
          return [...set].sort((a, b) => a.localeCompare(b));
        })(),
        // Why the wall is what it is: the playlist address, how many channels it published, and the
        // last thing that went wrong fetching it. A wall that came up empty after a DNS blip used to
        // be indistinguishable from a hub with no live TV at all.
        status: (live && typeof live.status === 'function')
          ? live.status()
          : { playlist: null, channels: 0, lastError: null },
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
      const items = generalItems('search', await pipeline(all, ctx)).slice(0, SEARCH_MAX);
      // A search hit the viewer can see is a tile the next tap will ask for by identity. If search
      // results are the one place the tile cache was not filled, a first tap on a fresh result is
      // cold: the addon meta has to beat the route budget from scratch, and "not found" appears
      // where a result card was on screen a second ago.
      rememberItems(items);
      await taste.decorate(ctx, items);
      return send(res, 200, { q, items: shelfItems(items) });
    }

    if (path === '/app/v1/genre') {
      const genre = (u.searchParams.get('name') || u.searchParams.get('genre') || '').trim();
      if (!genre) return send(res, 400, { error: 'genre name required' });
      const profile = store.profile(u.searchParams.get('profile') || u.searchParams.get('profileId'));
      const ctx = makeCtx(profile, SEARCH_BUDGET_MS);
      // A category is a genre query, not a title-word search: "Crime" must bring crime films, not
      // films whose name happens to contain Crime. And a category page must hold the CATEGORY, not
      // one page of one addon: the cached library answers instantly AND the addons' live genre
      // shelves are always asked alongside it, so the grid keeps going past the first cached page.
      const local = await Promise.race([
        ctx.library().catch(() => []),
        new Promise((resolve) => {
          const t = setTimeout(() => resolve([]), 1500);
          if (t.unref) t.unref();
        }),
      ]);
      const want = genre.toLowerCase();
      const held = (local || []).filter((it) => (it.genres || [])
        .some((g) => String(g).toLowerCase() === want));
      // A tile carries a slug ("sci-fi"); addons match their genre word exactly ("Sci-Fi"). The
      // library's own spelling resolves the tile back to the word the addons actually filter on.
      let display = genre;
      for (const it of held.slice(0, 40)) {
        const exact = (it.genres || []).find((g) => String(g).toLowerCase() === want);
        if (exact) { display = String(exact); break; }
      }
      // A canonical tile with nothing cached yet: the tile's slug still has to become the word the
      // addons filter on, or the page answers zero for a spelling mismatch and not for an absence.
      if (!held.length) display = shelves.resolveGenreName(genre);
      // The cache answers now and the addons answer too — always both. A category page that only
      // reads the cache is one addon's first page wearing a genre's clothes; the live genre shelves
      // are what makes the grid deep enough to keep scrolling.
      const live = await shelves.genreTitles(ctx, display,
        { limit: SEARCH_MAX * 3, budget: SEARCH_BUDGET_MS }).catch(() => []);
      const raw = merge.dedupe([...held, ...(live || [])], registry.priorities);
      const items = generalItems('genre', await pipeline(raw, ctx)).slice(0, SEARCH_MAX * 4);
      rememberItems(items);
      await taste.decorate(ctx, items);
      return send(res, 200, { genre, items: shelfItems(items) });
    }

    if (path === '/app/v1/addon-catalog') {
      const addonId = (u.searchParams.get('addon') || '').trim();
      const catalogId = (u.searchParams.get('catalog') || '').trim();
      const provider = registry.byId[addonId];
      if (!provider || typeof provider.catalog !== 'function' || !catalogId) {
        return send(res, 404, { error: 'no such addon catalog' });
      }
      const profile = store.profile(u.searchParams.get('profile') || u.searchParams.get('profileId'));
      const ctx = makeCtx(profile, SEARCH_BUDGET_MS);
      const raw = await withBudget(
        Promise.resolve().then(() => provider.catalog(catalogId, {})),
        SEARCH_BUDGET_MS, [], 'catalog ' + addonId + '/' + catalogId,
      );
      const items = generalItems('addon-catalog:' + addonId,
        await pipeline(raw || [], ctx)).slice(0, 120);
      rememberItems(items);
      await taste.decorate(ctx, items);
      return send(res, 200, { addon: addonId, catalog: catalogId, items: shelfItems(items) });
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
      if (!item) {
        // An upstream that is still past its budget is not an absence: answer the hub's warming bargain
        // (the same one the play route makes) so the app retries and the same tap succeeds. A settled
        // silence — the addon answered, and answered nothing — keeps the honest 404.
        if (addons.pendingFor(identity)) {
          return send(res, 503, {
            error: 'warming', warming: true, retryAfterMs: 1500, identity,
            detail: 'the hub is still asking its addons for this title',
          });
        }
        return send(res, 404, { error: 'not found', identity });
      }
      const metaDrops = [];
      const [kept] = await pipeline([item], ctx, metaDrops);
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
        // The detail page prints the same personal match and New state as the card that opened it.
        await taste.decorate(ctx, [kept]);
      }
      if (kept) {
        kept.reaction = store.reactionFor(profile.id, identity) || null;
        kept.seen = store.seenFor(profile.id, identity);
      }
      // A series whose tile is thin is usually complete somewhere else under its own title. Run
      // that join BEFORE the answer goes out: a sibling that answers inside the window is listed on
      // THIS open - the first open of a thin soap no longer shows "one episode" while the family
      // waits for a second tap to list the 4,862. A sibling that is slower ships the thin record
      // and lands in the store for the next open, exactly as the old post-send behaviour did.
      if (kept && kept.type === 'series' && (!Array.isArray(kept.episodes) || kept.episodes.length < 5)
          && !adoptingSiblings.has(kept.identity)) {
        adoptingSiblings.add(kept.identity);
        const job = adoptEpisodeSibling(kept, makeCtx(profile, 9000),
          { fetchSources: false, metaBudget: 9000 })
          .then((got) => {
            if (got) log('META EPISODES ADOPTED', kept.identity,
              'episodes=' + ((kept.episodes || []).length));
            return !!got;
          })
          .catch((e) => { log('META EPISODES ADOPT FAIL', kept.identity, e.message); return false; })
          .finally(() => adoptingSiblings.delete(kept.identity));
        // The job is not cancelled by the race: a late sibling still lands in the store for the
        // next open - the old post-send behaviour is the floor, not the plan.
        const late = await Promise.race([
          job.then(() => false),
          new Promise((r) => setTimeout(() => r(true), 9000)),
        ]);
        if (late && (!Array.isArray(kept.episodes) || kept.episodes.length < 5)) {
          log('META EPISODES ADOPT LATE', kept.identity,
            'this open carries the cached list; the full one lands when the sibling answers');
        }
      }
      if (process.env.PLAY_DEBUG) {
        log('META SEND', identity, 'type=' + (kept && kept.type) +
          ' eps=' + (kept && Array.isArray(kept.episodes) ? kept.episodes.length : 'none'));
      }
      send(res, kept ? 200 : 403, kept || ruleDropBody(identity, metaDrops));
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
          // A subtitle provider that sniffed its file (see core/addons.js sniffSubtitleFormats) states
          // `format` as a fact about the bytes, so it wins. Only when a provider did not (or could not)
          // sniff does the extension inside the name the addon chose remain as the fallback: when both
          // are missing `format` stays absent rather than being guessed at.
          const normalizeFmt = addons.util && typeof addons.util.normalizeSubtitleFormat === 'function'
            ? addons.util.normalizeSubtitleFormat : null;
          const declared = normalizeFmt
            ? normalizeFmt(s.format)
            : (() => {
              const bare = String(s.format || '').trim().toLowerCase().replace(/^\./, '');
              return ['srt', 'ass', 'ssa', 'vtt', 'ttml', 'dfxp', 'sub'].includes(bare)
                ? (bare === 'webvtt' ? 'vtt' : bare) : null;
            })();
          const ext = /\.(srt|ass|ssa|vtt|webvtt|ttml|dfxp|sub)\b/i.exec(String(s.name || ''));
          let format = declared || null;
          if (!format && ext) format = ext[1].toLowerCase() === 'webvtt' ? 'vtt' : ext[1].toLowerCase();
          const next = { ...s, addon: p.name || p.id };
          if (format) {
            next.format = format;
            if (typeof next.name === 'string' && next.name
              && !new RegExp('\\.' + format + '\\b', 'i').test(next.name)
              && /\.(srt|ass|ssa|vtt|webvtt|ttml|dfxp|sub)\b/i.test(next.name)) {
              next.name = next.name.replace(/\.(srt|ass|ssa|vtt|webvtt|ttml|dfxp|sub)\b/i, '.' + format);
            }
          } else {
            delete next.format;
          }
          found.push(next);
        }
      }
      found.sort((a, b) => {
        const ai = want.indexOf(String(a.lang || '').toLowerCase());
        const bi = want.indexOf(String(b.lang || '').toLowerCase());
        return (ai < 0 ? 99 : ai) - (bi < 0 ? 99 : bi);
      });
      return send(res, 200, { identity, langs: want, subs: found });
    }

    // Reviewed sources are an intentionally small, repository-owned catalogue — never a remote
    // marketplace. Its public projection omits the upstream manifest URL; install accepts only an
    // immutable registry id and verifies the fetched manifest before it is persisted.
    if (path === '/app/v1/addon-registry' && req.method === 'GET') {
      try {
        const directory = reviewedAddons.publicDirectory(reviewedDirectoryOptions(u));
        return send(res, 200, {
          // The legacy reviewed array remains for clients already on the original endpoint. It now
          // carries the same safe metadata as the explicit directory envelope, never a manifest URL.
          reviewed: directory.entries,
          directory,
          manualInstall: { available: true, safety: 'advanced-unreviewed' },
        });
      } catch (error) {
        log('REVIEWED ADDON REGISTRY FAIL', error.message);
        return send(res, 503, { error: 'reviewed sources are unavailable' });
      }
    }
    if (path === '/app/v1/addon-registry/directory' && req.method === 'GET') {
      try {
        const directory = reviewedAddons.publicDirectory(reviewedDirectoryOptions(u));
        return send(res, 200, {
          ...directory,
          manualInstall: { available: true, safety: 'advanced-unreviewed' },
        });
      } catch (error) {
        log('REVIEWED ADDON REGISTRY FAIL', error.message);
        return send(res, 503, { error: 'reviewed sources are unavailable' });
      }
    }
    m = /^\/app\/v1\/addon-registry\/([a-z0-9-]+)$/.exec(path);
    if (m && req.method === 'GET') {
      try {
        const entry = reviewedAddons.publicEntry(m[1], { installedIds: reviewedInstalledIds() });
        if (!entry) return send(res, 404, { error: 'no such reviewed source', id: m[1] });
        return send(res, 200, { entry });
      } catch (error) {
        log('REVIEWED ADDON REGISTRY FAIL', error.message);
        return send(res, 503, { error: 'reviewed sources are unavailable' });
      }
    }
    m = /^\/app\/v1\/addon-registry\/([a-z0-9-]+)\/setup-session$/.exec(path);
    if (m && req.method === 'POST') {
      if (rejectPreviewSourceMutation(res)) return;
      if (!requireAdminSession(req, res)) return;
      let entry;
      try {
        entry = reviewedAddons.find(m[1]);
      } catch (error) {
        log('REVIEWED ADDON REGISTRY FAIL', error.message);
        return send(res, 503, { error: 'reviewed source setup is unavailable' });
      }
      if (!entry) return send(res, 404, { error: 'no such reviewed source', id: m[1] });
      const source = sourceSetupSessionSource('reviewed', entry);
      if (!source) return send(res, 409, {
        error: 'this reviewed source does not require browser setup',
        code: 'configuration-not-required',
        id: entry.id,
      });
      const setup = sourceSetup.createSession(source);
      return send(res, 200, { ok: true, setup });
    }
    m = /^\/app\/v1\/addon-registry\/([a-z0-9-]+)\/install$/.exec(path);
    if (m && req.method === 'POST') {
      if (rejectPreviewSourceMutation(res)) return;
      if (!requireAdminSession(req, res)) return;
      let entry;
      try {
        entry = reviewedAddons.find(m[1]);
      } catch (error) {
        log('REVIEWED ADDON REGISTRY FAIL', error.message);
        return send(res, 503, { error: 'reviewed sources are unavailable' });
      }
      if (!entry) return send(res, 404, { error: 'no such reviewed source', id: m[1] });
      if (entry.configurationRequired) {
        return send(res, 409, {
          error: 'this reviewed source requires browser setup before installation',
          code: 'configuration-required',
          id: entry.id,
        });
      }
      const out = await addons.add(entry.manifest, entry.name, {
        provenance: { kind: 'reviewed', registryId: entry.id, revision: entry.version },
        validateManifest: (manifest) => reviewedAddons.validateRemoteManifest(entry, manifest),
      });
      if (!out.ok) return send(res, out.status || 502, { error: out.error, id: entry.id });
      invalidateCaches();
      return send(res, 200, {
        ok: true,
        addon: out.addon.stats(),
        addons: await addons.list(),
      });
    }

    // Community sources are intentionally a separate, non-endorsed directory. The Hub fetches only
    // the fixed protocol catalogue, returns a redacted projection, and accepts only its opaque cached
    // descriptor id for installation. This route never treats a caller-supplied URL as a community item.
    if (path === '/app/v1/community-addon-directory' && req.method === 'GET') {
      try {
        return send(res, 200, await communityDirectory.publicDirectory(communityDirectoryOptions(u)));
      } catch (error) {
        log('COMMUNITY ADDON DIRECTORY FAIL', error.message);
        return send(res, 503, { error: 'community sources are unavailable', code: 'community-directory-unavailable' });
      }
    }
    m = /^\/app\/v1\/community-addon-directory\/(community-[a-f0-9]{24})$/.exec(path);
    if (m && req.method === 'GET') {
      try {
        const entry = await communityDirectory.publicEntry(m[1], { installedIds: communityInstalledIds() });
        if (!entry) return send(res, 404, { error: 'no such community source', id: m[1] });
        return send(res, 200, { entry });
      } catch (error) {
        log('COMMUNITY ADDON DIRECTORY FAIL', error.message);
        return send(res, 503, { error: 'community sources are unavailable', code: 'community-directory-unavailable' });
      }
    }
    m = /^\/app\/v1\/community-addon-directory\/(community-[a-f0-9]{24})\/setup-session$/.exec(path);
    if (m && req.method === 'POST') {
      if (rejectPreviewSourceMutation(res)) return;
      if (!requireAdminSession(req, res)) return;
      const body = await readBody(req);
      if (!communityDirectory.hasNonEndorsementAcknowledgement(body)) {
        return send(res, 428, {
          error: 'community setup requires an explicit non-endorsement acknowledgement',
          code: 'community-acknowledgement-required',
          id: m[1],
          acknowledgement: { field: 'nonEndorsementAcknowledged', value: true },
        });
      }
      let entry;
      try {
        entry = await communityDirectory.find(m[1]);
      } catch (error) {
        log('COMMUNITY ADDON DIRECTORY FAIL', error.message);
        return send(res, 503, { error: 'community source setup is unavailable', code: 'community-directory-unavailable' });
      }
      if (!entry) return send(res, 404, { error: 'no such community source', id: m[1] });
      const source = sourceSetupSessionSource('community', entry);
      if (!source) return send(res, 409, {
        error: 'this Community source does not require browser setup',
        code: 'configuration-not-required',
        id: entry.id,
      });
      const setup = sourceSetup.createSession(source);
      return send(res, 200, { ok: true, setup });
    }
    m = /^\/app\/v1\/community-addon-directory\/(community-[a-f0-9]{24})\/install$/.exec(path);
    if (m && req.method === 'POST') {
      if (rejectPreviewSourceMutation(res)) return;
      if (!requireAdminSession(req, res)) return;
      const body = await readBody(req);
      if (!communityDirectory.hasNonEndorsementAcknowledgement(body)) {
        return send(res, 428, {
          error: 'community installation requires an explicit non-endorsement acknowledgement',
          code: 'community-acknowledgement-required',
          id: m[1],
          acknowledgement: { field: 'nonEndorsementAcknowledged', value: true },
        });
      }
      let entry;
      try {
        entry = await communityDirectory.find(m[1]);
      } catch (error) {
        log('COMMUNITY ADDON DIRECTORY FAIL', error.message);
        return send(res, 503, { error: 'community sources are unavailable', code: 'community-directory-unavailable' });
      }
      // An id is useful only while it resolves from the bounded server-side cache. A guessed or expired
      // id fails closed rather than becoming a pass-through URL installer.
      if (!entry) return send(res, 404, { error: 'no such community source', id: m[1] });
      if (entry.configurationRequired) {
        return send(res, 409, {
          error: 'this community source requires browser setup before installation',
          code: 'configuration-required',
          id: entry.id,
        });
      }
      const out = await addons.add(entry.manifestUrl, entry.name, {
        provenance: { kind: 'community', directoryId: entry.id, revision: entry.version },
        // Same-origin redirects, like the reviewed lane and the configured handoff: a community
        // host must not 301 the install onto a different origin's manifest.
        redirectPolicy: 'same-origin',
        validateManifest: (manifest) => communityDirectory.validateRemoteManifest(entry, manifest),
      });
      if (!out.ok) return send(res, out.status || 502, { error: out.error, id: entry.id });
      invalidateCaches();
      return send(res, 200, {
        ok: true,
        addon: out.addon.stats(),
        addons: await addons.list(),
      });
    }

    // The generic handoff endpoints deliberately accept only an opaque one-time code. They are
    // administrator-gated even for GET, so a code does not become a LAN-visible source probe.
    m = /^\/app\/v1\/source-setups\/([^/]+)$/.exec(path);
    if (m && req.method === 'GET') {
      if (!requireAdminSession(req, res)) return;
      const setup = sourceSetup.status(m[1]);
      return setup
        ? send(res, 200, { ok: true, setup })
        : send(res, 404, { error: 'setup code is unavailable or expired' });
    }
    m = /^\/app\/v1\/source-setups\/([^/]+)\/complete$/.exec(path);
    if (m && req.method === 'POST') {
      if (rejectPreviewSourceMutation(res)) return;
      if (!requireAdminSession(req, res)) return;
      const body = await readBody(req);
      const reservation = sourceSetup.beginCompletion(m[1]);
      if (!reservation.ok) return send(res, reservation.status, { error: reservation.error });
      const out = await installConfiguredSourceSetup(reservation.source, body && body.manifestUrl);
      if (!out.ok) {
        sourceSetup.releaseCompletion(reservation.code);
        return send(res, out.status || 502, { error: out.error || 'configured source could not be installed' });
      }
      const setup = sourceSetup.complete(reservation.code);
      invalidateCaches();
      return send(res, 200, {
        ok: true,
        setup,
        addon: out.addon.stats(),
        addons: await addons.list(),
      });
    }

    // Add-ons: the list the app's Settings renders, and the explicitly advanced manual install/remove surface.
    if (path === '/app/v1/addons' && req.method === 'POST') {
      if (rejectPreviewSourceMutation(res)) return;
      if (!requireAdminSession(req, res)) return;
      const body = await readBody(req);
      const out = await addons.add(body && body.url, body && body.name);
      invalidateCaches();
      if (!out.ok) return send(res, out.status || 500, { error: out.error });
      return send(res, 200, { ok: true, addon: out.addon.stats(), addons: await addons.list() });
    }
    m = /^\/app\/v1\/addons\/([^/]+)\/(enable|disable)$/.exec(path);
    if (m && req.method === 'POST') {
      if (rejectPreviewSourceMutation(res)) return;
      if (!requireAdminSession(req, res)) return;
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
      if (!requireAdminSession(req, res)) return;
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
      if (rejectPreviewSourceMutation(res)) return;
      if (!requireAdminSession(req, res)) return;
      const ok = addons.remove(m[1]);
      invalidateCaches();
      return ok
        ? send(res, 200, { ok: true, removed: m[1], addons: await addons.list() })
        : send(res, 404, { error: 'no such addon', id: m[1] });
    }
    if (path === '/app/v1/addons') {
      return send(res, 200, {
        addons: await addons.list(),
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
      // Per-profile Home rows and live lineup. The household's settings.rows/settings.live remain the
      // default; these lists only add to or narrow them for the person this prefs call names.
      const cleanIds = (v) => Array.isArray(v)
        ? v.filter((x) => typeof x === 'string' && x.length > 0 && x.length < 200).slice(0, 600)
        : null;
      if (src.rows && typeof src.rows === 'object') {
        const rows = {};
        // Same rule as the live lineup: an empty list says "nothing hidden" / "no order of my own", and
        // dropping it left a profile unable to put a Home row back.
        if (Array.isArray(src.rows.hidden)) rows.hidden = cleanIds(src.rows.hidden);
        if (Array.isArray(src.rows.order)) rows.order = cleanIds(src.rows.order);
        if (Object.keys(rows).length) out.rows = rows;
      }
      if (src.live && typeof src.live === 'object') {
        const live = {};
        for (const k of ['hidden', 'order', 'only']) {
          // An absent key leaves the stored list alone; an EMPTY array is a decision — "this profile has
          // no lineup" — and dropping it (the old behaviour) meant a lineup could never be switched off.
          if (!Array.isArray(src.live[k])) continue;
          live[k] = cleanIds(src.live[k]);
        }
        if (Object.keys(live).length) out.live = live;
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
    // A direct internet copy this hub is cushioning for the screen. The player reads this address rather
    // than the host that published the file; see bufferStream.
    if (path === '/app/v1/buffer') return await bufferStream(req, res, u);

    // A browser cannot play Matroska, and a TV does not want its Dolby re-encoded on the way to it, so
    // the caller says which container it can read. `any` (the default, and what the installed app sends)
    // is the copy untouched; `mp4` is fragmented MP4 from core/remux.js; `hls` is the optional playlist.
    // `audio` and `sub` are track ids from the source list (`a:1`, `s:2`) — the container's own stream
    // indexes, which is what lets the same id drive a native track selection and an ffmpeg `-map`.
    if (path === '/app/v1/remux' && req.method === 'GET') return remux.serve(req, res, u, corsHeaders(), media.peek);

    m = /^\/app\/v1\/ladder\/([a-f0-9]+)\/(.+)$/.exec(path);
    if (m && req.method === 'GET') return ladder.serve(req, res, corsHeaders(), m[1], m[2]);

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
          container: body.container, audio: body.audio, sub: body.sub, audioMix: body.audioMix,
          // Where the viewer already is: the hub measures and pre-buffers the part of the file they are
          // about to watch, not the part of it they finished an hour ago.
          positionMs: body.positionMs,
          // Which video codecs this device can draw. Not a preference and never inferred here: the client
          // states it, and it decides which copies are worth offering this screen at all.
          codecs: body.codecs, ladder: body.ladder === true, fast: body.fast === true });
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
          container: body.container, audio: body.audio, sub: body.sub, audioMix: body.audioMix,
          // Where the viewer already is, so the hub measures and pre-buffers the part of the file they are
          // about to watch instead of the part they watched an hour ago.
          positionMs: body.positionMs,
          codecs: body.codecs, ladder: body.ladder === true });
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
      //
      // A client that TELLS us which copy it holds (the url its /play answer handed it) gets stats about
      // its OWN copy. Without that, live() answered "the most recently touched watched copy" — on two
      // simultaneous screens, one viewer's starved buffer made the hub downgrade the other's healthy
      // stream. A direct third-party url matches a cushion by url too; anything we never buffered
      // answers null and the client judges its own buffer instead.
      let stream = null;
      const held = String(body.stream || '');
      const bm = /\/app\/v1\/buffer\?t=([a-f0-9]+)/.exec(held);
      if (bm && bufferSessions.get(bm[1])) {
        const s = bufferSessions.get(bm[1]);
        stream = readahead.statsFor({ url: s.url, headers: s.headers });
      } else if (held) {
        // The url a client holds is usually OUR proxy wrapper, and inside it the engine's own
        // /play/<hash>/<fileId> address is percent-encoded — parseWho cannot see through %2F.
        // Try the raw string first (a direct engine url matches as-is), then the decoded one.
        let eng = readahead.parseWho(held);
        if (!eng.hash) {
          try { eng = readahead.parseWho(decodeURIComponent(held)); } catch (e) { /* not utf8: raw stands */ }
        }
        stream = readahead.statsFor(eng.hash ? eng : { url: held });
      }
      if (!stream) stream = readahead.live(); // a client that has not learned to name its copy yet
      // The engine's live peer count for the copy this screen named. It changes while a torrent
      // warms, which is exactly the number a viewer wants beside the resolution.
      let peers = null;
      if (body.hash) peers = await gateway.peers(String(body.hash));
      return send(res, 200, { ok: true, identity: body.identity, progress, stream, peers });
    }

    // "Remove from Continue watching" — the tile goes, the position stays (see core/store.js).
    // Any later progress post brings the title back, so resuming re-arms the row by itself.
    if (path === '/app/v1/progress/dismiss' && req.method === 'POST') {
      const body = await readBody(req);
      if (!body || !body.identity) return send(res, 400, { error: 'identity required' });
      const who = body.profile || body.profileId || 'default';
      const done = store.dismissProgress(who, body.identity);
      return send(res, 200, { ok: true, dismissed: done, identity: body.identity });
    }

    // "Start over": the record is rewound to never-started, so the detail button says Play again
    // and the Continue bar empties. The screen does this BEFORE seeking, so a resumed viewer is not
    // left with a hub that thinks they are at 0:00 while they watch from 40:00.
    if (path === '/app/v1/progress/reset' && req.method === 'POST') {
      const body = await readBody(req);
      if (!body || !body.identity) return send(res, 400, { error: 'identity required' });
      const who = body.profile || body.profileId || 'default';
      const fresh = store.resetProgress(who, body.identity);
      return send(res, 200, { ok: true, progress: fresh, identity: body.identity });
    }

    // My List, on the hub: the living room's list IS the bedroom's list and the browser's list.
    // GET answers the whole array (the app caches it per profile), POST adds or removes.
    if (path === '/app/v1/list' && req.method === 'GET') {
      const who = u.searchParams.get('profile') || u.searchParams.get('profileId') || 'default';
      return send(res, 200, { profile: who, ids: store.myListIds(who, 400) });
    }
    if (path === '/app/v1/list' && req.method === 'POST') {
      const body = await readBody(req);
      if (!body || !body.identity) return send(res, 400, { error: 'identity required' });
      const who = body.profile || body.profileId || 'default';
      const on = body.add === undefined ? !store.inList(who, body.identity) : Boolean(body.add);
      if (on) store.listAdd(who, body.identity); else store.listRemove(who, body.identity);
      return send(res, 200, { ok: true, identity: body.identity, inList: on,
        ids: store.myListIds(who, 400) });
    }

    // Reactions and seen state, per profile. "Like"/"love" feed the taste fingerprint; "dislike"
    // subtracts; "seen" keeps a watched title out of the mood board while it stays on My List.
    if (path === '/app/v1/reaction' && req.method === 'GET') {
      const who = u.searchParams.get('profile') || u.searchParams.get('profileId') || 'default';
      return send(res, 200, { profile: who, reactions: store.reactionsFor(who) });
    }
    if (path === '/app/v1/reaction' && req.method === 'POST') {
      const body = await readBody(req);
      if (!body || !body.identity) return send(res, 400, { error: 'identity required' });
      const who = body.profile || body.profileId || 'default';
      const allowed = ['like', 'love', 'dislike', 'none'];
      const reaction = String(body.reaction || 'none').toLowerCase();
      if (!allowed.includes(reaction)) {
        return send(res, 400, { error: 'reaction must be like, love, dislike or none', reaction });
      }
      const now = store.setReaction(who, body.identity, reaction);
      return send(res, 200, { ok: true, identity: body.identity, reaction: now });
    }
    if (path === '/app/v1/seen' && req.method === 'POST') {
      const body = await readBody(req);
      if (!body || !body.identity) return send(res, 400, { error: 'identity required' });
      const who = body.profile || body.profileId || 'default';
      const seen = store.setSeen(who, body.identity, body.seen !== false);
      return send(res, 200, { ok: true, identity: body.identity, seen });
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

server.listen(PORT, HUB_BIND, () => {
  const bindLabel = HUB_BIND.includes(':') ? '[' + HUB_BIND + ']' : HUB_BIND;
  log(HUB_ID, HUB_VERSION, 'on ' + SCHEME + '://' + bindLabel + ':' + PORT
    + '  (clients: /app/v1/home?profile=<id>)');
  // ffmpeg reads a torrent through this hub's own proxy so the cushion sits in front of it too; it needs
  // to know the address to loop back to.
  readahead.setSelfOrigin(SCHEME + '://127.0.0.1:' + PORT);
  // The hub follows its own origin on a slow timer, so "update the app" never leaves an old hub
  // behind — and a hub that cannot self-pull (dirty checkout, no git) logs why once per pass.
  // A loopback offline preview is deliberately a sealed renderer-inspection lane: even its delayed
  // status check must not fetch a remote Git origin while someone is evaluating the UI.
  if (!OFFLINE_PREVIEW) hubupdate.start(log);
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
  if (OFFLINE_PREVIEW) {
    previewTelemetry.skipped.avatars++;
    previewTelemetry.skipped.genres++;
  } else {
    avatars.warm();
    // Genre shelves read whatever the addons have already said. On a fresh process that is nothing, so the
    // first home page a household sees after a restart is missing Action, Comedy and Horror entirely, and it
    // only fills in once someone happens to look at them. Warm the genres the addons themselves declare, in
    // the background, never inside a request.
    addons.warmGenres()
      .then((n) => { if (n) console.log('genres: ' + n + ' catalog pages warmed from what the addons declare'); })
      .catch(() => {});
  }
});
