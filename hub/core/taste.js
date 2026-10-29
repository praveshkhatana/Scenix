'use strict';
/*
 * core/taste.js — what this viewer is likely to enjoy, learned only from this hub's own history.
 *
 * The percentage on a tile is not a quality score and not an IMDb rating: it says how much a title
 * resembles the things this person has actually watched. That signal already exists — every play posts
 * to /app/v1/progress, and data/progress.json keeps the identity, how far it got and when. Nothing here
 * asks the internet for a recommendation, and nothing leaves the box.
 *
 * A watch contributes more when it is recent and when it was finished:
 *   recency   half-life of HALF_LIFE_DAYS, so last week's film counts for more than last year's
 *   completion  a finished film is a stronger "yes" than one abandoned after four minutes
 *
 * From those watches we build a taste fingerprint — genre, the people on screen and behind it, the
 * kind of thing, its decade, its languages — and each tile is scored against it. The result is a
 * stable 0–100 number for one profile; the same history always produces the same percentages.
 *
 * Taste is per profile and only per profile. Watching a film on one person's profile must not change
 * the percentages on anyone else's, not even a child's or a partner's. A profile that has watched
 * nothing has no fingerprint and therefore no honest percentage: `match` stays null and its cards print
 * none until that person has watched something themselves.
 *
 * Separately, `isNew` gives "New" one meaning on every surface: released recently, or added to this
 * hub's library recently. The browser used to decide that from addedAt alone; a film that opened last
 * week but had been in the library for a month was not "new" there. This is the server's answer, so the
 * TV and the browser cannot disagree.
 */

const DAY = 86400000;
const HALF_LIFE_DAYS = Number(process.env.TASTE_HALF_LIFE_DAYS || 45);
const MAX_HISTORY = Number(process.env.TASTE_MAX_HISTORY || 60);
// "Recently added" is about the hub's own library. "New release" is about the title's own date.
const NEW_ADDED_DAYS = Number(process.env.NEW_ADDED_DAYS || 7);
const NEW_RELEASE_DAYS = Number(process.env.NEW_RELEASE_DAYS || 45);

const clamp01 = (n) => Math.max(0, Math.min(1, Number(n) || 0));

function genreList(item) {
  const g = item && item.genres;
  if (!Array.isArray(g)) return [];
  return g.map((x) => String(x || '').trim().toLowerCase()).filter(Boolean);
}

function peopleList(item, field) {
  const v = item && item[field];
  if (!Array.isArray(v)) return [];
  return v.map((x) => String(x || '').trim().toLowerCase()).filter(Boolean);
}

function langList(item) {
  const v = item && item.languages;
  if (!Array.isArray(v)) return [];
  return v.map((x) => String(x || '').trim().toLowerCase()).filter(Boolean);
}

function bump(map, key, weight) {
  if (!key || !(weight > 0)) return;
  map.set(key, (map.get(key) || 0) + weight);
}

function top(map) {
  let max = 0;
  for (const v of map.values()) if (v > max) max = v;
  return max;
}

/**
 * How strongly one watch counts. A live channel opened for three seconds is not taste; a film watched
 * to the credits is. `positionMs / watchedMs` is the completion ratio the player already reports, and a
 * title with no duration yet (a live stream) falls back to a small, fixed signal.
 */
function watchWeight(entry, now) {
  const at = Number(entry && entry.updatedAt) || now;
  const ageDays = Math.max(0, (now - at) / DAY);
  const recency = Math.pow(0.5, ageDays / HALF_LIFE_DAYS);
  const pos = Math.max(0, Number(entry && entry.positionMs) || 0);
  const total = Math.max(0, Number(entry && entry.watchedMs) || 0);
  const completion = total > 0 ? clamp01(pos / total) : 0.1;
  return recency * (0.25 + 0.75 * completion);
}

function historyFor(store, profileId, now) {
  const p = (store && store.progress && store.progress[String(profileId)]) || {};
  const out = [];
  for (const [identity, entry] of Object.entries(p)) {
    const weight = watchWeight(entry, now);
    if (weight > 0.01) out.push({ identity, weight, updatedAt: Number(entry.updatedAt) || 0 });
  }
  out.sort((a, b) => b.updatedAt - a.updatedAt);
  return out.slice(0, MAX_HISTORY);
}

/** The fingerprint: weighted feature counts, plus the normalisers that make a 0–1 score. */
function fingerprint(watches, byId) {
  const genres = new Map();
  const cast = new Map();
  const crew = new Map();
  const languages = new Map();
  const types = new Map();
  let yearSum = 0;
  let yearWeight = 0;
  let mass = 0;
  const seen = new Set();
  for (const w of watches) {
    const it = byId.get(String(w.identity));
    if (!it) continue;
    // A live channel says what someone zapped past, not what they like. The catalogue rows are films,
    // series and videos; a channel's genre is not a taste signal a recommendation should imitate.
    if (it.type !== 'movie' && it.type !== 'series' && it.type !== 'video') continue;
    const weight = w.weight;
    mass += weight;
    for (const g of genreList(it)) bump(genres, g, weight);
    for (const c of peopleList(it, 'cast').slice(0, 8)) bump(cast, c, weight * 0.5);
    for (const c of peopleList(it, 'crew').slice(0, 4)) bump(crew, c, weight * 0.25);
    for (const l of langList(it)) bump(languages, l, weight * 0.2);
    bump(types, String(it.type || ''), weight * 0.15);
    const year = Number(it.year) || 0;
    if (year > 1880) {
      yearSum += year * weight;
      yearWeight += weight;
    }
    seen.add(String(w.identity));
  }
  if (!mass) return null;
  return {
    genres, cast, crew, languages, types,
    genreTop: top(genres), castTop: top(cast), crewTop: top(crew), langTop: top(languages),
    typeTop: top(types),
    meanYear: yearWeight ? yearSum / yearWeight : 0,
    mass,
    seen,
  };
}

function signals(ctx, byId) {
  const now = Number(ctx.now) || Date.now();
  // Exactly one profile's history, no household merge: taste is personal, and a number on a tile has to
  // mean "because THIS viewer watched" or it means nothing.
  const watches = historyFor(ctx.store, ctx.profile && ctx.profile.id, now);
  return fingerprint(watches, byId);
}

/** How much a title shares with the fingerprint, 0–1 before it is turned into a percentage. */
function affinity(item, fp) {
  const share = (keys, map, max) => {
    if (!max) return 0;
    let sum = 0;
    for (const k of keys) sum += map.get(k) || 0;
    return clamp01(sum / max);
  };
  const g = share(genreList(item), fp.genres, fp.genreTop);
  const c = share(peopleList(item, 'cast').slice(0, 8), fp.cast, fp.castTop);
  const d = share(peopleList(item, 'crew').slice(0, 4), fp.crew, fp.crewTop);
  const l = share(langList(item), fp.languages, fp.langTop);
  const t = clamp01((fp.types.get(String(item.type || '')) || 0) / (fp.typeTop || 1));
  const year = Number(item.year) || 0;
  // A film from the viewer's own era is a weak signal next to genre or a familiar face: 15 years is
  // roughly one generation of cinema, and the curve should not reward a decade over a director.
  const y = (year > 1880 && fp.meanYear) ? Math.exp(-Math.abs(year - fp.meanYear) / 15) : 0;
  return clamp01(0.62 * g + 0.16 * c + 0.08 * d + 0.08 * l + 0.04 * t + 0.02 * y);
}

/** A 55–99 percentage, the range a viewer reads as "worth my evening" rather than a grade. */
function percent(score) {
  return Math.round(55 + 44 * clamp01(score));
}

/** True when the title is a recent release OR recently entered this hub's library. */
function isNew(item, now) {
  if (!item) return false;
  const at = Number(item.addedAt) || 0;
  const rel = Number(item.releasedAt) || 0;
  const added = at > 0 && (now - at) <= NEW_ADDED_DAYS * DAY;
  // A future release date is an announcement, not a new release; the badge is for something watchable.
  const released = rel > 0 && rel <= now && (now - rel) <= NEW_RELEASE_DAYS * DAY;
  return Boolean(added || released || item.inCinemas === true);
}

/**
 * Stamp `match` and `isNew` on a batch of items for the ctx's profile.
 *
 * The library is asked for once per request (it is already memoised by the ctx); history identities
 * that are no longer in any catalogue are simply not used, because guessing their genres would be
 * inventing taste. Mutates the items in place, as rows hand them to arrangeHome afterwards.
 */
async function decorate(ctx, items) {
  if (!Array.isArray(items) || !items.length) return items;
  const now = Number(ctx.now) || Date.now();
  let byId = null;
  try {
    const library = await ctx.library();
    byId = new Map();
    for (const it of library) if (it && it.identity) byId.set(String(it.identity), it);
    // The item being scored can be fresher than the catalogue snapshot (a detail page, search hit).
    for (const it of items) if (it && it.identity && !byId.has(String(it.identity))) byId.set(String(it.identity), it);
  } catch (e) {
    byId = new Map();
    for (const it of items) if (it && it.identity) byId.set(String(it.identity), it);
  }
  const fp = signals(ctx, byId);
  for (const it of items) {
    if (!it) continue;
    // A live channel is not a title to recommend: "you watched BBC News for four minutes" is not a
    // taste for news, and a percentage or a New badge on a channel tile is noise. Channels keep their
    // own Live treatment; taste and New are for films, series and videos.
    if (it.type === 'channel' || it.live) {
      it.match = null;
      it.isNew = false;
      continue;
    }
    it.isNew = isNew(it, now);
    if (!fp) {
      it.match = null;
      continue;
    }
    const seen = fp.seen.has(String(it.identity));
    it.match = seen ? 99 : percent(affinity(it, fp));
  }
  return items;
}

module.exports = { decorate, isNew, percent, affinity, historyFor, watchWeight, NEW_ADDED_DAYS, NEW_RELEASE_DAYS };
