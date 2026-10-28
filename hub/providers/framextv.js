'use strict';
/*
 * providers/framextv.js — an aggregator addon, not a host of its own.
 *
 * FrameXTV answers a title by asking several upstream scrapers and then handing back whichever one
 * produced a working stream. This provider keeps that behaviour behind the hub's own contract: the
 * catalogs are ordinary rows, `sources()` returns rows the ranking rule can score, and `play()` hands
 * the client one URL at a time. The upstream host is a setting (`FRAMEXTV_API`), not a constant in a
 * client, so a deployment can point it at a mirror without editing either the app or this file.
 *
 * Two shape limits are handled honestly:
 *   · Movies can be addressed by IMDb or by the aggregator's numeric id.
 *   · Series need the aggregator's numeric TMDB id. An IMDb show is resolved by searching the
 *     aggregator for the title the metadata already gave us; no other source is named in code.
 *
 * The stream cache is deliberately small. Upstream URLs expire, so a cached row is used to render a
 * list, while `play()` refreshes the stream before handing the URL to a player.
 */

const http = require('http');
const https = require('https');
const media = require('../core/media');

const PROVIDER_ID = 'framextv';
const BASE = String(process.env.FRAMEXTV_API || 'https://api.framextv.cc').replace(/\/+$/, '');
const UA = process.env.FRAMEXTV_UA || 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36';
const STREAM_TTL_MS = Number(process.env.FRAMEXTV_STREAM_TTL_MS || 3 * 60 * 1000);
const CATALOG_TTL_MS = Number(process.env.FRAMEXTV_CATALOG_TTL_MS || 60 * 1000);
const REQUEST_TIMEOUT_MS = Number(process.env.FRAMEXTV_TIMEOUT_MS || 15000);

const log = (...a) => console.log(new Date().toISOString(), ...a);

const LANG_WORDS = {
  hindi: 'hin', english: 'eng', punjabi: 'pan', tamil: 'tam', telugu: 'tel', malayalam: 'mal',
  kannada: 'kan', bengali: 'ben', marathi: 'mar', urdu: 'urd', gujarati: 'guj', russian: 'rus',
  spanish: 'spa', portuguese: 'por', french: 'fre', german: 'ger', italian: 'ita', japanese: 'jpn',
  korean: 'kor', chinese: 'chi', arabic: 'ara', turkish: 'tur', persian: 'per', polish: 'pol',
  ukrainian: 'ukr', dutch: 'dut', swedish: 'swe', norwegian: 'nor', danish: 'dan', finnish: 'fin',
  czech: 'cze', hungarian: 'hun', romanian: 'rum', bulgarian: 'bul', serbian: 'srp', croatian: 'hrv',
  slovak: 'slo', slovenian: 'slv', estonian: 'est', latvian: 'lav', lithuanian: 'lit', greek: 'gre',
  indonesian: 'ind', malay: 'may', thai: 'tha', vietnamese: 'vie', hebrew: 'heb',
};

const streamCache = new Map(); // identity -> { raw, at }
const catalogCache = new Map(); // catalog id + page/query -> { body, at }
const titleCache = new Map(); // base identity -> { title, year, type }

function getJson(url, timeoutMs = REQUEST_TIMEOUT_MS) {
  return new Promise((resolve, reject) => {
    const mod = url.startsWith('https:') ? https : http;
    const req = mod.get(url, {
      headers: { 'User-Agent': UA, Accept: 'application/json' },
      timeout: timeoutMs,
    }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume();
        const next = new URL(res.headers.location, url).toString();
        return resolve(getJson(next, timeoutMs));
      }
      let body = '';
      res.setEncoding('utf8');
      res.on('data', (c) => { body += c; });
      res.on('end', () => {
        if (res.statusCode < 200 || res.statusCode >= 300) {
          return reject(new Error('HTTP ' + res.statusCode + ' for ' + url));
        }
        try { resolve(JSON.parse(body)); }
        catch (e) { reject(new Error('unreadable answer from ' + url)); }
      });
    });
    req.on('timeout', () => req.destroy(new Error('timeout for ' + url)));
    req.on('error', reject);
  });
}

function api(path, params = {}) {
  const u = new URL(BASE + path);
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') u.searchParams.set(k, String(v));
  }
  return getJson(u.toString());
}

function parseStreamId(streamId) {
  const s = String(streamId || '');
  let m = /^framextv:([^:]+)(?::(\d+):(\d+))?$/.exec(s);
  if (m) {
    return {
      base: 'framextv:' + m[1], id: m[1],
      season: m[2] == null ? null : Number(m[2]),
      episode: m[3] == null ? null : Number(m[3]),
      source: 'framextv',
    };
  }
  m = /^imdb:(tt\d+)(?::(\d+):(\d+))?$/.exec(s);
  if (m) {
    return {
      base: 'imdb:' + m[1], id: m[1],
      season: m[2] == null ? null : Number(m[2]),
      episode: m[3] == null ? null : Number(m[3]),
      source: 'imdb',
    };
  }
  return { base: s, id: null, season: null, episode: null, source: 'other' };
}

function qualityBand(raw) {
  const q = String(raw || '');
  const m = /(\d{3,4})p/i.exec(q);
  if (m) return m[1] + 'p';
  if (/4k|2160|uhd/i.test(q)) return '2160p';
  return null;
}

function langsFromText(text) {
  const t = String(text || '').toLowerCase();
  const out = [];
  for (const [word, code] of Object.entries(LANG_WORDS)) {
    if (t.includes(word) && !out.includes(code)) out.push(code);
  }
  return out;
}

function langName(code) {
  const names = {
    hin: 'Hindi', eng: 'English', pan: 'Punjabi', tam: 'Tamil', tel: 'Telugu', mal: 'Malayalam',
    kan: 'Kannada', ben: 'Bengali', mar: 'Marathi', urd: 'Urdu', guj: 'Gujarati', rus: 'Russian',
    spa: 'Spanish', por: 'Portuguese', fre: 'French', ger: 'German', ita: 'Italian', jpn: 'Japanese',
    kor: 'Korean', chi: 'Chinese', ara: 'Arabic', tur: 'Turkish', per: 'Persian', pol: 'Polish',
    ukr: 'Ukrainian', dut: 'Dutch', swe: 'Swedish', nor: 'Norwegian', dan: 'Danish', fin: 'Finnish',
    cze: 'Czech', hun: 'Hungarian', rum: 'Romanian', bul: 'Bulgarian', srp: 'Serbian',
    hrv: 'Croatian', slo: 'Slovak', slv: 'Slovenian', est: 'Estonian', lav: 'Latvian',
    lit: 'Lithuanian', gre: 'Greek', ind: 'Indonesian', may: 'Malay', tha: 'Thai',
    vie: 'Vietnamese', heb: 'Hebrew',
  };
  return names[code] || code;
}

/**
 * The identity this provider is asked to stream. A series must always name an episode, because the
 * aggregator's stream endpoint needs season and episode and a bare show id returns whichever episode
 * its upstream scraper happened to pick.
 */
function streamKeyForItem(item) {
  const sid = String(item.streamId || item.identity || '');
  const p = parseStreamId(sid);
  if (!p.id) return null;
  const type = item.type === 'series' ? 'tv' : 'movie';
  const season = p.season == null && type === 'tv' ? 1 : p.season;
  const episode = p.episode == null && type === 'tv' ? 1 : p.episode;
  return {
    key: p.base + (type === 'tv' ? ':' + season + ':' + episode : ''),
    type, id: p.id, season, episode, base: p.base, source: p.source,
  };
}

function mapCatalogItem(v) {
  if (!v || v.id == null) return null;
  const type = v.type === 'tv' ? 'series' : v.type === 'movie' ? 'movie' : 'video';
  return {
    identity: 'framextv:' + v.id,
    type,
    title: String(v.title || '').trim(),
    year: Number(v.year) || null,
    poster: v.poster || v.image || null,
    backdrop: v.backdrop || v.image || null,
    logo: null,
    match: null,
    maturity: null,
    durationMs: null,
    languages: null,
    genres: Array.isArray(v.genres) ? v.genres : [],
    addedAt: null,
    releasedAt: null,
    inCinemas: false,
    live: null,
    progress: null,
    overview: v.overview || null,
    rating: v.rating || null,
    sources: [],
  };
}

function mapMeta(raw, identity, hint) {
  if (!raw || raw.success === false) return null;
  const type = raw.type === 'tv' ? 'series' : raw.type === 'movie' ? 'movie' : (hint === 'series' ? 'series' : 'movie');
  return {
    identity,
    type,
    title: String(raw.title || '').trim(),
    year: Number(raw.year) || null,
    poster: raw.poster || null,
    backdrop: raw.backdrop || null,
    logo: null,
    match: null,
    maturity: null,
    durationMs: raw.runtime ? Number(raw.runtime) * 60000 : null,
    languages: raw.original_language ? [media.langOf(raw.original_language)] : null,
    genres: Array.isArray(raw.genres) ? raw.genres : [],
    addedAt: null,
    releasedAt: raw.release_date || null,
    inCinemas: false,
    live: null,
    progress: null,
    overview: raw.overview || null,
    rating: raw.rating || null,
    episodes: [],
    sources: [],
  };
}

function mapSources(raw, key) {
  const out = [];
  const list = Array.isArray(raw && raw.sources) ? raw.sources : [];
  list.forEach((s, index) => {
    if (!s || !s.url) return;
    const langs = langsFromText(String(s.quality || '') + ' ' + String(s.server || ''));
    const quality = qualityBand(s.quality);
    const label = [
      quality || String(s.quality || ''),
      langs.map(langName).join('/'),
      String(s.server || ''),
    ].filter(Boolean).join(' \u00b7 ') || 'Stream';
    out.push({
      provider: s.type === 'hls' ? 'hls' : 'direct',
      _addon: PROVIDER_ID,
      label,
      kind: s.type === 'hls' ? 'hls' : 'http',
      quality,
      sizeBytes: null,
      risk: null,
      audioOnly: false,
      _langs: langs,
      audioLangs: langs,
      _url: s.url,
      _streamKey: key,
      _rawIndex: index,
      audioTracks: [],
    });
  });
  return out;
}

async function findTvId(title, year) {
  const clean = String(title || '').trim();
  if (!clean) return null;
  const res = await api('/api/search', { query: clean });
  if (!res || res.success === false) return null;
  const results = Array.isArray(res.results) ? res.results : [];
  const norm = (s) => String(s || '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
  const wanted = norm(clean);
  const exact = results.filter((r) => r.type === 'tv' && norm(r.title) === wanted);
  if (!exact.length) return null;
  if (year) {
    const withYear = exact.find((r) => String(r.year) === String(year));
    if (withYear) return withYear.id;
  }
  return exact[0].id;
}

async function streamForKey(key, force = false) {
  const parsed = parseStreamId(key);
  if (!parsed || !parsed.id) return null;
  const hit = streamCache.get(key);
  if (!force && hit && Date.now() - hit.at < STREAM_TTL_MS) {
    return { raw: hit.raw, key, type: parsed.season == null ? 'movie' : 'tv', season: parsed.season, episode: parsed.episode };
  }
  const type = parsed.season == null ? 'movie' : 'tv';
  let id = parsed.id;
  let season = parsed.season == null ? 1 : parsed.season;
  let episode = parsed.episode == null ? 1 : parsed.episode;
  if (parsed.source === 'imdb' && type === 'tv') {
    const info = titleCache.get(parsed.base);
    if (!info) return null;
    id = await findTvId(info.title, info.year);
    if (id == null) return null;
  }
  const params = { type, id };
  if (type === 'tv') {
    params.season = season;
    params.episode = episode;
  }
  const raw = await api('/api/stream', params);
  if (raw && raw.success !== false) {
    streamCache.set(key, { raw, at: Date.now() });
  }
  return { raw, key, type, season, episode };
}

async function streamForItem(item) {
  const spec = streamKeyForItem(item);
  if (!spec) return null;
  if (item.title) {
    titleCache.set(spec.base, { title: item.title, year: item.year, type: item.type });
  }
  return streamForKey(spec.key, false);
}

function sourcesForItem(item) {
  const spec = streamKeyForItem(item);
  if (!spec) return [];
  if (item.title) {
    titleCache.set(spec.base, { title: item.title, year: item.year, type: item.type });
  }
  const hit = streamCache.get(spec.key);
  if (hit && Date.now() - hit.at < STREAM_TTL_MS) {
    return mapSources(hit.raw, spec.key);
  }
  // `sources()` runs under a small cache budget. Fetch in the background and let the detail route's
  // `fetchStreams()` spend the real round trip.
  void streamForItem(item).catch((e) => log('FRAMEXTV STREAM MISS', item.identity, e.message));
  return [];
}

async function fetchForIdentity(streamId) {
  const parsed = parseStreamId(streamId);
  if (!parsed || !parsed.id) return [];
  const hit = streamCache.get(streamId);
  if (hit && Date.now() - hit.at < STREAM_TTL_MS) {
    return mapSources(hit.raw, streamId);
  }
  const got = await streamForKey(streamId, true);
  if (!got || !got.raw) return [];
  return mapSources(got.raw, streamId);
}

async function metaForFramextv(id, hint) {
  const hasTv = hint === 'series' || hint === 'tv';
  const hasMovie = hint === 'movie';
  let raw = null;
  let wantType = hasTv ? 'series' : hasMovie ? 'movie' : null;
  if (hasTv) {
    raw = await api('/api/tv/' + encodeURIComponent(id));
  } else if (hasMovie) {
    raw = await api('/api/movies/' + encodeURIComponent(id));
  } else {
    raw = await api('/api/movies/' + encodeURIComponent(id));
    if (!raw || raw.success === false) raw = await api('/api/tv/' + encodeURIComponent(id));
  }
  if (!raw || raw.success === false) return null;
  const item = mapMeta(raw, 'framextv:' + id, wantType);
  titleCache.set('framextv:' + id, { title: item.title, year: item.year, type: item.type });
  if (item.type === 'series') {
    const seasonList = Array.isArray(raw.seasons) ? raw.seasons : [];
    const episodes = [];
    await Promise.all(seasonList.map(async (season) => {
      const n = Number(season.season_number);
      if (!Number.isFinite(n) || n < 0) return;
      let detail = null;
      try { detail = await api('/api/tv/' + encodeURIComponent(id) + '/season/' + n); }
      catch (e) { return; }
      if (!detail || detail.success === false) return;
      for (const ep of (Array.isArray(detail.episodes) ? detail.episodes : [])) {
        episodes.push({
          season: Number(ep.season_number || n),
          episode: Number(ep.episode_number),
          title: ep.name || null,
          overview: ep.overview || null,
          airDate: ep.air_date || null,
          still: ep.still || null,
        });
      }
    }));
    item.episodes = episodes.sort((a, b) => (a.season - b.season) || (a.episode - b.episode));
  }
  return item;
}

async function catalogPage(catalogId, extra = {}) {
  const page = Math.max(1, Math.min(50, Number(extra.page) || 1));
  const limit = Number(extra.limit);
  const key = catalogId + ':' + page + ':' + String(extra.search || '');
  const hit = catalogCache.get(key);
  if (hit && Date.now() - hit.at < CATALOG_TTL_MS) {
    return Number.isFinite(limit) ? hit.body.slice(0, limit) : hit.body;
  }
  let path = '/api/trending';
  if (catalogId === 'framextv-movies') path = '/api/movies';
  else if (catalogId === 'framextv-tv') path = '/api/tv';
  else if (catalogId === 'framextv-search') path = '/api/search';
  else if (catalogId !== 'framextv-trending') return [];
  const params = { page };
  if (extra.search) params.query = String(extra.search);
  const res = await api(path, params);
  if (!res || res.success === false) return [];
  const items = (Array.isArray(res.results) ? res.results : []).map(mapCatalogItem).filter(Boolean);
  catalogCache.set(key, { body: items, at: Date.now() });
  return Number.isFinite(limit) ? items.slice(0, limit) : items;
}

function parseExpiry(url) {
  // Signed upstream links often carry their own expiry. If it is present, report it; otherwise leave
  // the field null rather than pretending a link lives forever.
  const m = /[?&]expires=(\d+)/i.exec(String(url || ''));
  if (!m) return null;
  const n = Number(m[1]);
  return Number.isFinite(n) ? (n > 1e12 ? n : n * 1000) : null;
}

async function play(identity, source) {
  const streamId = String((source && source._streamKey) || identity || '');
  const got = await streamForKey(streamId, true);
  if (!got || !got.raw) return null;
  const list = Array.isArray(got.raw.sources) ? got.raw.sources : [];
  let raw = source && Number.isInteger(source._rawIndex) ? list[source._rawIndex] : null;
  if (!raw) raw = list.find((s) => s && s.url === source._url) || list[0];
  if (!raw || !raw.url) return null;
  return {
    url: raw.url,
    headers: {},
    kind: raw.type === 'hls' ? 'hls' : 'http',
    expiresAt: parseExpiry(raw.url),
  };
}

async function subtitles(identity, langs) {
  const got = await streamForKey(identity, false);
  if (!got || !got.raw) return [];
  const want = (Array.isArray(langs) ? langs : []).map((l) => media.langOf(l));
  const out = [];
  for (const s of (Array.isArray(got.raw.subtitles) ? got.raw.subtitles : [])) {
    if (!s || !s.url) continue;
    const code = media.langOf(s.lang || s.language);
    if (want.length && !want.includes(code)) continue;
    const format = String(s.format || 'vtt').toLowerCase();
    out.push({
      id: PROVIDER_ID + ':' + got.key + ':' + (s.label || code),
      addon: PROVIDER_ID,
      lang: code,
      name: (s.label || 'Subtitles') + '.' + format,
      url: s.url,
      format,
    });
  }
  return out;
}

module.exports = {
  id: PROVIDER_ID,
  name: 'FrameXTV aggregator',
  native: true,
  kind: 'video',
  priority: 50,

  async catalogs() {
    return [
      { id: 'framextv-trending', name: 'FrameXTV trending', extra: ['page'] },
      { id: 'framextv-movies', name: 'FrameXTV movies', extra: ['page'] },
      { id: 'framextv-tv', name: 'FrameXTV series', extra: ['page'] },
      { id: 'framextv-search', name: 'FrameXTV search', extra: ['search', 'page'] },
    ];
  },

  async catalog(catalogId, extra = {}) {
    return catalogPage(catalogId, extra);
  },

  async meta(identity, hint) {
    const s = String(identity || '');
    if (s.startsWith('framextv:')) {
      return metaForFramextv(s.slice('framextv:'.length), hint);
    }
    if (s.startsWith('imdb:') && hint !== 'series') {
      const id = s.slice('imdb:'.length);
      const raw = await api('/api/movies/' + encodeURIComponent(id));
      if (!raw || raw.success === false) return null;
      const item = mapMeta(raw, 'imdb:' + id, 'movie');
      titleCache.set('imdb:' + id, { title: item.title, year: item.year, type: item.type });
      return item;
    }
    return null;
  },

  /** Cache-only, because the source contribution budget is deliberately small. */
  async sources(item) {
    return sourcesForItem(item);
  },

  async fetchStreams(streamId) {
    return fetchForIdentity(streamId);
  },

  async play(identity, source) {
    return play(identity, source);
  },

  async sourceTarget(identity, source) {
    if (source && source._url) return { url: source._url, headers: {} };
    return null;
  },

  async subtitles(identity, langs) {
    return subtitles(identity, langs);
  },

  stats() {
    return {
      id: PROVIDER_ID,
      name: 'FrameXTV aggregator',
      native: true,
      host: BASE,
      streamCache: streamCache.size,
      catalogCache: catalogCache.size,
    };
  },
};
