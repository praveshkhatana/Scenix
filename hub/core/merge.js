'use strict';
/*
 * core/merge.js — dedupe + source ranking. The app must never see two tiles for the same thing.
 *
 * Items are merged on `identity` (design/plugin-architecture.md §1: "identity is the merge key").
 * Everything a second provider knows about the same identity becomes extra `sources[]` on the one
 * surviving tile; fields already filled by a higher-priority provider are never overwritten by a
 * lower-priority one (rows gather providers in priority order, so "first wins" == "best wins").
 */

const FIELDS = [
  'identity', 'type', 'title', 'year', 'poster', 'backdrop', 'logo', 'match', 'maturity',
  'durationMs', 'languages', 'genres', 'addedAt', 'releasedAt', 'inCinemas', 'live', 'progress',
];

/** Empty item with the exact §1 shape, so every provider emits the same keys. */
function blankItem() {
  return {
    identity: null, type: null, title: null, year: null, poster: null, backdrop: null, logo: null,
    match: null, maturity: null, durationMs: null, languages: null, genres: null,
    addedAt: null, releasedAt: null, inCinemas: false, live: null, progress: null, sources: [],
  };
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

module.exports = { dedupe, mergeItems, rankSources, blankItem, FIELDS };
