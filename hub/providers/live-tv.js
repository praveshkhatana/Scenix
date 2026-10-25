'use strict';
/*
 * providers/iptv-org.js — free-to-air Indian TV from the iptv-org public playlist.
 *
 * Moved verbatim (behaviour-wise) from work/iptv/iptv-india-addon.js: same playlist fetch, same
 * M3U parser, same per-language allow-list, same channel ids (`inlive___…`), same sort order
 * (lightest quality first for a name, so the default pick survives a Wi-Fi dip). The legacy
 * compat routes it serves are byte-for-byte what the old single-file addon returned, so the TV's
 * addon surface (manifest.json, /catalog/, /meta/, /stream/) keeps the TV's installed addon working.
 *
 * On top of that it now speaks the hub provider contract: catalog()/meta()/play() return §1 items
 * with one tile per channel *name* and one `sources[]` entry per quality variant.
 *
 * Adding another live source later = copy this file, change PLAYLIST_URL. Nothing else moves.
 */

const http = require('http');
const https = require('https');
const { URL } = require('url');

const liveHealth = require('../core/live-health');

const PROVIDER_ID = 'live-tv';
const PLAYLIST_URL = process.env.PLAYLIST_URL || 'https://iptv-org.github.io/iptv/countries/in.m3u';
const CATALOG_ID = 'in_live';
const KIDS_CATALOG_ID = 'in_kids';
// Groups that are safe for a kids profile. This is an allow-list, not a genre filter: a channel only
// reaches the kids catalog if its iptv-org group is explicitly safe.
const KIDS_GROUPS = ['Kids', 'Education', 'Animation', 'Children'];
// Channels that sit in a kids group but are not for small children. Deny-list on top of the
// allow-list above, so the kids catalog stays something you can hand a child.
const KIDS_BLOCK = (process.env.KIDS_BLOCK || 'Animax,MediBizTV,BVG,Vyas,DD Kisan,Unique TV')
  .split(',').map((s) => s.trim()).filter(Boolean);
// Language allow-list. iptv-org publishes a playlist per language, so a channel is kept only if its
// stream appears in one of these. LANGS="" turns the filter off.
const LANG_NAMES = { hin: 'Hindi', eng: 'English', pan: 'Punjabi' };
const LANGS = (process.env.LANGS === undefined ? 'hin,eng,pan' : process.env.LANGS)
  .split(',').map((s) => s.trim()).filter(Boolean);
const ID_PREFIX = 'inlive___';
const REFRESH_MS = 6 * 60 * 60 * 1000; // playlist re-fetch every 6 h
// A feed can go down inside an evening, so health is re-checked far more often than the playlist.
const HEALTH_REFRESH_MS = Number(process.env.LIVE_HEALTH_REFRESH_MS || 30 * 60 * 1000);
const UA = 'Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 GhartvHub/0.1';

let channels = []; // {id, name, group, groups, logo, url, quality, langs, langCodes}
let loading = null;
let lastFetch = 0;
let lastError = null;
const log = (...a) => console.log(new Date().toISOString(), ...a);

function get(url, timeoutMs = 20000) {
  return new Promise((resolve, reject) => {
    const mod = url.startsWith('https:') ? https : http;
    const req = mod.get(url, { headers: { 'User-Agent': UA }, timeout: timeoutMs }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume();
        return resolve(get(new URL(res.headers.location, url).toString(), timeoutMs));
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

function slug(s) {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 48);
}

function parseM3U(text) {
  const out = [];
  const seen = new Set();
  const lines = text.split(/\r?\n/);
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line.startsWith('#EXTINF')) continue;
    const url = (lines[i + 1] || '').trim();
    if (!url || url.startsWith('#')) continue;
    const attr = (name) => {
      const m = new RegExp(name + '="([^"]*)"', 'i').exec(line);
      return m ? m[1] : '';
    };
    const title = (line.split(',').slice(1).join(',') || '').trim();
    if (!title) continue;
    let name = title;
    let quality = '';
    const q = /\((\d{3,4}p)\)\s*$/i.exec(name);
    if (q) {
      quality = q[1];
      name = name.slice(0, q.index).trim();
    }
    if (!name) continue;
    const key = name.toLowerCase() + '|' + quality;
    if (seen.has(key)) continue;
    seen.add(key);
    // iptv-org groups can be compound ("Business;News") - split into clean genres.
    const groups = (attr('group-title') || 'General')
      .split(';')
      .map((x) => x.trim())
      .filter(Boolean);
    out.push({
      id: ID_PREFIX + (slug(attr('tvg-id')) || slug(name) + '_' + out.length),
      name,
      group: groups[0],
      groups,
      logo: attr('tvg-logo'),
      url,
      quality,
    });
  }
  // Same channel name, several variants: list the lightest first, so the default pick is the one
  // that survives a Wi-Fi dip instead of the 1080p feed that stalls.
  const qbits = (c) => Number(String(c.quality || '').replace(/\D/g, '')) || 0;
  out.sort(
    (a, b) => a.name.localeCompare(b.name) || qbits(a) - qbits(b),
  );
  return out;
}

function kidsChannels() {
  return channels.filter(
    (c) =>
      c.groups.some((g) => KIDS_GROUPS.includes(g)) &&
      !KIDS_BLOCK.some((b) => c.name.startsWith(b)),
  );
}

// Which allowed languages each stream URL appears under, from iptv-org's per-language playlists.
// Returns url -> { names: ['Hindi', …] } (display names for the legacy surface, unchanged from the old
// addon) and codes: ['hin', …] (the §1 `languages` codes the hub rules work on).
async function languageByStreamUrl() {
  const map = new Map();
  const failed = [];
  for (const code of LANGS) {
    try {
      const text = await get(`https://iptv-org.github.io/iptv/languages/${code}.m3u`);
      let hits = 0;
      for (const line of text.split('\n')) {
        const u = line.trim();
        if (u && !u.startsWith('#')) {
          const entry = map.get(u) || { names: [], codes: [] };
          entry.names.push(LANG_NAMES[code] || code);
          entry.codes.push(code);
          map.set(u, entry);
          hits++;
        }
      }
      console.log(new Date().toISOString(), 'language', code, 'ok:', hits, 'streams');
    } catch (e) {
      failed.push(code + ': ' + e.message);
    }
  }
  if (failed.length) console.error(new Date().toISOString(), 'language playlists failed:', failed.join('; '));
  return map;
}

async function refresh(force = false) {
  if (loading) return loading;
  if (!force && channels.length && Date.now() - lastFetch < REFRESH_MS) return Promise.resolve(channels);
  loading = get(PLAYLIST_URL)
    .then(async (text) => {
      let parsed = parseM3U(text);
      if (parsed.length) {
        if (LANGS.length) {
          const byUrl = await languageByStreamUrl();
          if (byUrl.size) {
            for (const c of parsed) {
              const hit = byUrl.get(c.url);
              c.langs = hit ? hit.names : [];
              c.langCodes = hit ? hit.codes : [];
            }
            const kept = parsed.filter((c) => c.langs.length);
            if (kept.length) {
              console.log(new Date().toISOString(), 'language filter:', parsed.length, '->', kept.length);
              parsed = kept;
            } else {
              console.error(new Date().toISOString(), 'language filter left 0 channels, keeping all');
            }
          }
        }
        channels = parsed;
        lastFetch = Date.now();
        lastError = null;
        console.log(new Date().toISOString(), 'playlist ok:', channels.length, 'channels');
      } else {
        lastError = 'playlist parsed to 0 channels';
        console.error(new Date().toISOString(), lastError);
      }
      return channels;
    })
    .catch((e) => {
      lastError = e.message;
      console.error(new Date().toISOString(), 'playlist fetch failed:', e.message);
      return channels;
    })
    .finally(() => {
      loading = null;
    });
  return loading;
}

function findChannel(id) {
  return channels.find((c) => c.id === id);
}

// ---- channel health: core/live-health.js drives what this provider publishes ----------------------

let probing = null;

/** identity -> { name, urls[] } for the whole current playlist, lightest variant first. */
function probeTargets() {
  const byIdentity = new Map();
  for (const c of channels) {
    const identity = identityForName(c.name);
    if (!byIdentity.has(identity)) byIdentity.set(identity, { identity, name: c.name, urls: [] });
    byIdentity.get(identity).urls.push(c.url);
  }
  return [...byIdentity.values()];
}

/**
 * One bounded-concurrency pass over the playlist, then the zap numbering.
 *
 * The numbers are assigned in the order the curated live row shows channels — the feeds the hub
 * watched answer first, then the ones it has never managed to check, then the ones it watched fail —
 * and inside each of those classes the playlist's own alphabetical order. Because core/live-health.js
 * persists the number it handed an identity, the list is identical after a restart: the TV app can
 * label the wall "1. Sab TV", "2. &TV" and zap the same way tomorrow.
 */
async function probePass(label) {
  if (probing) return probing;
  probing = (async () => {
    const targets = probeTargets();
    if (!targets.length) return { checked: 0, ok: 0 };
    await liveHealth.probe(targets, { label });
    const rank = (t) => liveHealth.rank(t.identity);
    const ordered = targets
      .slice()
      .sort((a, b) => rank(a) - rank(b) || a.name.localeCompare(b.name))
      .map((t) => t.identity);
    const numbers = liveHealth.assignNumbers(ordered);
    log('LIVE NUMBERS', numbers.assigned, 'new of', numbers.total);
    return numbers;
  })().finally(() => {
    probing = null;
  });
  return probing;
}

function genres() {
  const set = new Set();
  for (const c of channels) for (const g of c.groups) set.add(g);
  return [...set].sort((a, b) => a.localeCompare(b));
}

function qbits(q) {
  return Number(String(q || '').replace(/\D/g, '')) || 0;
}

// ---- hub §1 items -------------------------------------------------------------------------------
// One tile per channel NAME; every quality variant of that name is a `sources[]` entry. The
// identity is the §1 fallback form `norm:<slug>` (a live channel has no year).

function identityForName(name) {
  return 'norm:' + slug(name);
}

/** Group the parsed channel variants into §1 items (identity = norm:<slug(name)>). */
function hubItems(list) {
  const byName = new Map();
  for (const c of list) {
    const identity = identityForName(c.name);
    let item = byName.get(identity);
    if (!item) {
      item = {
        identity,
        type: 'channel',
        title: c.name,
        year: null,
        poster: c.logo || null,
        backdrop: c.logo || null,
        logo: c.logo || null,
        match: null,
        maturity: null,
        durationMs: null,
        languages: null,
        genres: null,
        addedAt: null,
        releasedAt: null,
        inCinemas: false,
        live: { now: null, quality: c.quality || null },
        progress: null,
        sources: [],
      };
      byName.set(identity, item);
    }
    item.sources.push({
      provider: 'live', // what the client renders: a transport word, not our plugin name
      _addon: PROVIDER_ID,
      label: c.quality ? c.quality + ' stream' : 'stream',
      kind: /\.m3u8(\?|$)/i.test(c.url) ? 'hls' : 'http',
      quality: c.quality || null,
      sizeBytes: null,
      risk: null,
      _url: c.url, // internal: lets the health record point at the variant that actually answered
    });
    const langs = c.langCodes || [];
    if (langs.length) item.languages = [...new Set([...(item.languages || []), ...langs])];
    item.genres = [...new Set([...(item.genres || []), ...c.groups])];
    if (qbits(c.quality) > qbits(item.live.quality)) item.live.quality = c.quality;
    // iptv-org gives no EPG here: `live.now` stays null, the app shows the channel name instead.
  }

  // Additive on the §1 `live` object: what the prober saw, and the stable zap number.
  //   live.health   "working" | "dead" | "unknown"  (unknown = never checked, or the evidence is old)
  //   live.checkedAt / live.latencyMs / live.error   the probe record behind that verdict
  //   live.number   1, 2, 3 … assigned once and kept across restarts (core/live-health.js)
  for (const item of byName.values()) {
    const h = liveHealth.get(item.identity);
    item.live.health = h ? (h.ok ? 'working' : 'dead') : 'unknown';
    item.live.checkedAt = h ? h.checkedAt : null;
    item.live.latencyMs = h ? h.latencyMs : null;
    item.live.error = h && !h.ok ? h.error : null;
    item.live.number = liveHealth.numberFor(item.identity);
    if (h && h.ok && h.url) {
      const at = item.sources.findIndex((s) => s._url === h.url);
      if (at > 0) item.sources.unshift(item.sources.splice(at, 1)[0]);
    }
  }
  return [...byName.values()];
}

function channelForSource(item, source) {
  const variants = channels.filter((c) => identityForName(c.name) === item.identity);
  if (!variants.length) return null;
  if (source && source.quality) {
    const exact = variants.find((c) => c.quality === source.quality);
    if (exact) return exact;
  }
  // No explicit pick: hand back the variant the prober watched answer instead of just the lightest,
  // so a channel that has one dead feed and one live one still plays.
  const h = liveHealth.get(item.identity);
  if (h && h.ok && h.url) {
    const live = variants.find((c) => c.url === h.url);
    if (live) return live;
  }
  return variants[0]; // lightest variant first (parseM3U sorted them that way)
}

function catalogMeta(c) {
  const langs = c.langs || [];
  return {
    id: c.id,
    type: 'tv',
    name: c.name + (c.quality ? ' (' + c.quality + ')' : ''),
    genre: c.groups,
    language: langs.join(', ') || undefined,
    poster: c.logo || undefined,
    posterShape: 'landscape',
    description:
      'Live TV • ' + (langs.join(', ') ? langs.join('/') + ' • ' : '') + c.groups.join(' • ') +
      (c.quality ? ' • ' + c.quality : ''),
  };
}

function ytFreeManifestPart() {
  return {
    id: 'org.local.iptvindia',
    version: '1.0.0',
    name: 'India Live TV',
    description:
      'Free to air Indian TV channels from the iptv-org public playlist (' +
      channels.length +
      ' channels). Served from your own box - streams are third party and may go offline.',
    resources: [
      { name: 'catalog', types: ['tv'], idPrefixes: [CATALOG_ID, KIDS_CATALOG_ID] },
      { name: 'meta', types: ['tv'], idPrefixes: [ID_PREFIX] },
      { name: 'stream', types: ['tv'], idPrefixes: [ID_PREFIX] },
    ],
    types: ['tv'],
    catalogs: [
      {
        type: 'tv',
        id: CATALOG_ID,
        name: 'India Live TV',
        extra: [
          { name: 'genre', isRequired: false, options: genres() },
          { name: 'language', isRequired: false, options: LANGS.map((c) => LANG_NAMES[c] || c) },
          { name: 'search', isRequired: false },
        ],
      },
      {
        type: 'tv',
        id: KIDS_CATALOG_ID,
        name: 'India Kids TV',
        extra: [{ name: 'search', isRequired: false }],
      },
    ],
    idPrefixes: [ID_PREFIX],
    behaviorHints: { configurable: false, adult: false },
  };
}

module.exports = {
  id: PROVIDER_ID,
  name: 'Live TV playlists',
  native: true,
  kind: 'channel',
  priority: 10,

  // Hub-side warmup: /app/v1 never waits on the network, it reads whatever this holds.
  start() {
    // Playlist first, then the health pass behind it — both in the background, never awaited, so a
    // slow playlist or a wall of dead feeds cannot delay the hub coming up.
    refresh(true).then(() => probePass('startup'));
    const t = setInterval(() => refresh(true).then(() => probePass('playlist refresh')), REFRESH_MS);
    if (t.unref) t.unref();
    const h = setInterval(() => {
      if (channels.length) probePass('health refresh');
    }, HEALTH_REFRESH_MS);
    if (h.unref) h.unref();
  },
  // Legacy compat keeps the old behaviour: the first request waits for the playlist.
  async ensure() {
    await refresh();
  },

  async catalogs() {
    return [
      { id: CATALOG_ID, name: 'India Live TV', extra: ['search', 'genre', 'language'] },
      { id: KIDS_CATALOG_ID, name: 'India Kids TV', extra: ['search'] },
    ];
  },

  /** Cache-only: returns [] until the first playlist fetch lands, never blocks the caller. */
  async catalog(catalogId, extra = {}) {
    if (catalogId !== CATALOG_ID && catalogId !== KIDS_CATALOG_ID) return [];
    let list = catalogId === KIDS_CATALOG_ID ? kidsChannels() : channels;
    const genre = extra.genre;
    const language = extra.language;
    const search = (extra.search || '').toLowerCase();
    if (genre) list = list.filter((c) => c.groups.includes(genre));
    if (language) list = list.filter((c) => (c.langCodes || []).includes(language) || (c.langs || []).includes(language));
    if (search) list = list.filter((c) => c.name.toLowerCase().includes(search));
    const items = hubItems(list);
    // Working channels first, then the zap order — a viewer scrolling this catalog should never land
    // on a feed the hub already watched fail.
    items.sort(liveHealth.compare);
    return Number.isFinite(extra.limit) ? items.slice(0, extra.limit) : items;
  },

  async meta(identity) {
    if (!identity || !String(identity).startsWith('norm:')) return null;
    const list = channels.filter((c) => identityForName(c.name) === identity);
    return list.length ? hubItems(list)[0] : null;
  },

  async play(identity, source) {
    const item = await this.meta(identity);
    if (!item) return null;
    const c = channelForSource(item, source);
    if (!c) return null;
    return {
      url: c.url,
      headers: { 'User-Agent': UA, Referer: new URL(c.url).origin + '/' },
      kind: /\.m3u8(\?|$)/i.test(c.url) ? 'hls' : 'http',
      expiresAt: null, // iptv-org feeds are not signed; they die when the feed dies
    };
  },

  stats() {
    return {
      channels: channels.length,
      kidsChannels: kidsChannels().length,
      tiles: hubItems(channels).length,
      languages: LANGS.join(','),
      playlist: PLAYLIST_URL,
      lastFetch: lastFetch ? new Date(lastFetch).toISOString() : null,
      lastError,
      health: liveHealth.status(), // {checked, working, dead, unknown, numbered, updatedAt}
    };
  },

  // ---- legacy addon compatibility (byte-compatible with iptv-india-addon.js) ------------------
  legacy: {
    idPrefixes: [ID_PREFIX],
    catalogIds: [CATALOG_ID, KIDS_CATALOG_ID],
    async ensure() {
      await refresh();
    },
    manifestPart() {
      return ytFreeManifestPart();
    },
    // /catalog/tv/<in_live|in_kids>[/<extra>].json
    async catalog(type, catId, extra) {
      if (type !== 'tv' || (catId !== CATALOG_ID && catId !== KIDS_CATALOG_ID)) return null;
      await refresh();
      const search = (extra.get('search') || '').toLowerCase();
      const limit = Number(extra.get('limit') || 1000);
      const genre = extra.get('genre');
      const language = extra.get('language');
      let list = catId === KIDS_CATALOG_ID ? kidsChannels() : channels;
      if (genre) list = list.filter((c) => c.groups.includes(genre));
      if (language) list = list.filter((c) => (c.langs || []).includes(language));
      if (search) list = list.filter((c) => c.name.toLowerCase().includes(search));
      return { status: 200, body: { metas: list.slice(0, limit).map(catalogMeta) } };
    },
    // /meta/tv/inlive___.json
    async meta(remoteId) {
      await refresh();
      const c = findChannel(remoteId);
      if (!c) return { status: 404, body: { meta: null } };
      return {
        status: 200,
        body: {
          meta: Object.assign(catalogMeta(c), {
            releaseInfo: 'Live',
            links: [],
            background: c.logo || undefined,
          }),
        },
      };
    },
    // /stream/tv/inlive___.json
    async stream(remoteId) {
      await refresh();
      const c = findChannel(remoteId);
      if (!c) return { status: 404, body: { streams: [] } };
      return {
        status: 200,
        body: {
          streams: [
            {
              name: c.name + (c.quality ? ' • ' + c.quality : '') + '\n' + c.group,
              title: c.name,
              url: c.url,
              external: false,
              behaviorHints: {
                notWebReady: true,
                bingeGroup: 'iptv-' + c.id,
                proxyHeaders: {
                  request: { 'User-Agent': UA, Referer: new URL(c.url).origin + '/' },
                  response: { 'Access-Control-Allow-Origin': '*' },
                },
              },
            },
          ],
        },
      };
    },
  },
};
