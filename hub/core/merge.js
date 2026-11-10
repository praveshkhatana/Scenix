'use strict';
/*
 * core/merge.js — dedupe + source ranking. The app must never see two tiles for the same thing.
 *
 * Items are merged on `identity`: identity is the merge key.
 * Everything a second provider knows about the same identity becomes extra `sources[]` on the one
 * surviving tile; fields already filled by a higher-priority provider are never overwritten by a
 * lower-priority one (rows gather providers in priority order, so "first wins" == "best wins").
 */

const FIELDS = [
  'identity', 'type', 'title', 'year', 'poster', 'backdrop', 'logo', 'rating', 'match', 'maturity',
  'durationMs', 'languages', 'genres', 'addedAt', 'releasedAt', 'inCinemas', 'live', 'progress',
  'episodes',
  // The detail page's text. Off this whitelist a synopsis picked up from one addon vanishes the
  // moment a second provider touches the same title.
  'overview', 'tagline', 'cast', 'crew', 'country',
  // Which address this tile's sources were actually fetched for (an episode of a series). It has to
  // survive dedupe: the addon indexes streams by the address asked for, so a caller that loses it looks
  // up the show and finds nothing, which showed up as "no play target" on every series.
  'streamId',
];

/** Empty item with the exact §1 shape, so every provider emits the same keys. */
function blankItem() {
  return {
    identity: null, type: null, title: null, year: null, poster: null, backdrop: null, logo: null,
    rating: null, match: null, maturity: null, durationMs: null, languages: null, genres: null,
    addedAt: null, releasedAt: null, inCinemas: false, live: null, progress: null, sources: [],
    episodes: null, streamId: null, overview: null, tagline: null, cast: null, crew: null, country: null,
  };
}

/**
 * One picture, many sizes. TMDB publishes each poster as `original` too, and an original poster is
 * an 1800×2667 file (~1 MB) — handing that to a 280 px tile makes every client pay for pixels no
 * screen shows, and it was the difference between a screen that scrolls and one that freezes. Only
 * over-sized URLs are rewritten: a provider that already chose w500 or w780 keeps its choice, a
 * non-TMDB host is untouched, and the client is still free to ask for its own width.
 *
 * Episode stills are the other place an `original` leaks through. They are shown at 300–400 px in
 * every surface (the native episode rail, the web detail page), yet a single show can carry sixty
 * of them — a Continue-watching tile that ships its whole episode list must not hand each client
 * sixty 1 MB originals.
 */
const TMDB_SIZE = { poster: 'w500', backdrop: 'w1280', logo: 'w500', thumb: 'w300' };
function sizedImage(url, field) {
  const want = TMDB_SIZE[field];
  if (!want || typeof url !== 'string') return url;
  const m = /^(https?:\/\/image\.tmdb\.org\/t\/p\/)(original|w\d+)(\/.*)$/.exec(url);
  if (!m || m[2] !== 'original') return url;
  return m[1] + want + m[3];
}

/** The art fields of one item, sized for a screen. Applied to fresh merges and to cache reads. */
function normalizeArt(item) {
  if (!item || typeof item !== 'object') return item;
  for (const f of ['poster', 'backdrop', 'logo']) item[f] = sizedImage(item[f], f);
  if (Array.isArray(item.episodes)) {
    for (const ep of item.episodes) {
      if (ep && typeof ep === 'object' && ep.thumb) ep.thumb = sizedImage(ep.thumb, 'thumb');
    }
  }
  return item;
}

/**
 * Sources carry one hub-internal field: `_addon`, the id of the addon that offered them. It is what
 * lets a rule ask "is this item from an addon this profile allows?" and what routes play() back to the
 * right addon, while `provider` on the wire stays a transport word (live / youtube / torrent /
 * adaptive) that the client can render without knowing our plugin names. Anything named `_…` is
 * stripped before a response leaves the hub.
 */
function sourceKey(s) {
  return [s && s._addon, s && s.kind, s && s.quality, s && s.sizeBytes, s && s.label].join('|');
}

/** Drop duplicate sources, then rank: addon priority first, original addon order within that. */
function rankSources(sources, priorities = {}) {
  const seen = new Set();
  const list = [];
  for (const s of sources || []) {
    if (!s || !s.provider) continue;
    const k = sourceKey(s);
    if (seen.has(k)) continue;
    seen.add(k);
    list.push(s);
  }
  return list
    .map((s, i) => ({
      s,
      i,
      p: Number(priorities[s._addon] != null ? priorities[s._addon] : 99),
    }))
    .sort((a, b) => a.p - b.p || a.i - b.i)
    .map((x) => x.s);
}

/** Union two episode lists the viewer needs all of. Two addons describing the same show each know
 *  part of it; the entry that came first (higher priority) keeps its fields, and episodes only the
 *  other one knows are appended, sorted. This is why `episodes` is not a first-wins field: DesiFlix
 *  knowing 20 episodes of Breaking Bad must never erase the 62 cinemeta described. */
function unionEpisodes(a, b) {
  if (!Array.isArray(a)) return Array.isArray(b) ? b.slice() : null;
  if (!Array.isArray(b)) return a;
  const key = (e) => `${e && e.season}x${e && e.episode}`;
  const out = a.slice();
  const seen = new Set(out.map(key));
  for (const e of b) if (e && !seen.has(key(e))) { seen.add(key(e)); out.push(e); }
  return out.sort((x, y) => (x.season - y.season) || (x.episode - y.episode));
}

/**
 * Merge an array of §1 items into one tile per identity.
 * `priorities` maps providerId -> provider priority (lower = more trusted).
 */
function dedupe(items, priorities = {}) {
  const out = new Map();
  for (const it of items) {
    if (!it || !it.identity) continue;
    const key = String(it.identity);
    let keep = out.get(key);
    if (!keep) {
      keep = blankItem();
      for (const f of FIELDS) if (it[f] !== undefined) keep[f] = it[f];
      keep.identity = key;
      keep.sources = [];
      out.set(key, keep);
    } else {
      // fill only what is still unknown (first/higher-priority provider wins)
      for (const f of FIELDS) {
        const v = it[f];
        if (v === undefined || v === null) continue;
        if (keep[f] === null || keep[f] === undefined) keep[f] = v;
        else if (f === 'inCinemas' && v === true) keep[f] = true; // "in cinemas" is sticky
      }
      if (Array.isArray(it.genres) && Array.isArray(keep.genres)) {
        keep.genres = [...new Set(keep.genres.concat(it.genres))];
      }
      if (Array.isArray(it.languages) && Array.isArray(keep.languages)) {
        keep.languages = [...new Set(keep.languages.concat(it.languages))];
      }
      // Episodes are the exception to "first wins": a show's episode list is the union of what every
      // addon knows. A partial list (a dub catalog carrying 20 of 62 episodes) must never erase the
      // full one — that is how Breaking Bad lost seasons 1-3 on the tile and, with them, the copies
      // addressed by the ids the full list carries.
      if (Array.isArray(it.episodes) && it.episodes.length) {
        keep.episodes = unionEpisodes(keep.episodes, it.episodes);
      }
    }
    if (Array.isArray(it.sources)) keep.sources.push(...it.sources);
    // Which addons touched this identity. Internal (`_` fields never cross the wire), and the only
    // way a row or a rule can ask "did an installed addon contribute this tile?" without naming addons.
    const ids = [...(it._addons || []), ...(it.sources || []).map((s) => s && s._addon).filter(Boolean)];
    keep._addons = [...new Set([...(keep._addons || []), ...ids])];
  }
  const list = [...out.values()];
  for (const it of list) {
    it.sources = rankSources(it.sources, priorities);
    normalizeArt(it);
    // cinemeta describes a series with a `released` date and a `year` that is often a run ("2008–2013"),
    // which is not a number. The tile shows one year, so take it from the release date whenever the
    // addon's own value is missing or unusable.
    const year = Number(it.year);
    if ((!Number.isInteger(year) || year < 1888 || year > 2100) && Number.isFinite(it.releasedAt)) {
      it.year = new Date(it.releasedAt).getUTCFullYear();
    }
  }
  return list;
}

/** Resolve one identity against several partial items (e.g. meta from two providers). */
function mergeItems(items, priorities = {}) {
  const merged = dedupe(items, priorities);
  return merged[0] || null;
}

module.exports = { dedupe, mergeItems, rankSources, blankItem, unionEpisodes, normalizeArt, FIELDS, sourceKey };
