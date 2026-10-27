'use strict';
/*
 * rows/hero.js — the billboard: the one title the home screen opens with, full-bleed.
 *
 * The app has had a billboard for months (`plugins/cards/HeroCard.kt` renders any row whose `card` is
 * "hero", and `HomeScreen` pulls that row out of the list and puts it above the first shelf). The hub
 * proposed no such row, so on a real TV the home screen simply started with a shelf: `grep -n hero` in
 * this folder found nothing. One row file fixes that; the only other place the word appears is the
 * home route, which puts this row first and stops it repeating a title already on the screen below it.
 *
 * What belongs up here is a decision, so the decision is a table you can read and edit: the billboard
 * takes the NEW thing — just opened in cinemas, just released, just added to this house — and
 * behind that, something this household would actually watch (the addons' own popularity, whether a
 * copy actually exists to play) and whether the billboard can preview itself (core/trailers.js). The
 * weights are a table at the top of this file for the same reason the maturity ladder is a table in
 * core/ratings.js: a household tunes a number, not a paragraph of logic.
 *
 * NO NETWORK on this path, on purpose. /app/v1/home has about half a second of patience, so every
 * signal here is read off what this box already holds: the addon catalog cache (`ctx.library()`, the
 * same memoised promise every other row of this request uses), our own certificate database
 * (`ratings.peek`) and our own trailer database (`trailers.peek`). A billboard that had to fetch
 * something would be the slowest thing on the screen and would not be a billboard.
 *
 * The row returns up to `max` CANDIDATES, not tiles. `buildRow` runs the rules first and trims after,
 * so a filtered profile keeps whatever survived; then the home route drops the candidates that are
 * already on a shelf below the billboard and shows `HERO_MAX` (3-5) — see `arrangeHome` in index.js.
 * `scope` is 'all' and the home route lets a hero row through to every profile, because a billboard is
 * a SHAPE, not a content tier: for a child the rules (maturity order 15, kids allowlist order 20) are
 * what decide which title is up here, and the genre cap below is what makes sure the rules are offered
 * a pool varied enough to have something to allow. A kids billboard is therefore tier 0/1 by
 * construction, not by a special case in this file.
 *
 * `title` is required by core/registry.js (every row module must name itself) and is never rendered:
 * the app draws a hero row as the billboard with no header, and the home route sends `title: null` for
 * a hero row so the wire shape says the same thing.
 */
const ratings = require('../core/ratings');
const trailers = require('../core/trailers');

const DAY = 86400000;

/* The billboard policy. Edit these numbers; nothing below them needs to change. */
const W = {
  inCinemas: 45,        // the thing that just opened in cinemas outranks everything else up here
  freshDays: 180,       // how far back "recently released" reaches...
  fresh: 30,            // ...and what it is worth at release day (linear to 0 at freshDays)
  justAdded: 18,        // first time this hub ever saw it (data/library.json), fading over ctx.days
  trailer: 14,          // it can preview itself while the viewer reads the title
  playable: 12,         // a billboard you cannot press Play on is a poster, not a billboard
  nonTorrent: 6,        // ...and a copy this TV can start without a stream engine is worth more
  matchMax: 20,         // the addons' own popularity/rating signal, capped so it never rules alone
  languageFit: 22,      // a title we know this house watches beats one whose language was never stated
  broadlyWatchable: 8,  // a certificate that says everyone/teen: safe to put in front of the household
  rotate: 3,            // ±3, deterministic per day: see the rotation note in score()
};

// How many candidates this row proposes. The home route keeps HERO_MAX of them, so this is deliberately
// several times the number of tiles: the rules and the dedupe against the shelves below both subtract.
const CANDIDATES = 24;

// At most this many candidates from any one primary genre. Without it the pool is 24 copies of the same
// blockbuster band, and a profile whose rules reject that band (a child) is left with no billboard.
const PER_GENRE = 4;

const isUrl = (u) => typeof u === 'string' && /^https?:\/\//i.test(u);

/** Deterministic per (identity, day) — a shuffle that must not need a random seed to be repeatable. */
function hash(s) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619) >>> 0;
  return h;
}

/**
 * One candidate's billboard score. Every input is a fact already on this box.
 *
 * The ±3 rotation is the one line worth defending: a billboard that never changes tells a returning
 * viewer the screen is broken, and a billboard that changes on every request makes "open the app twice"
 * two different homes. So the jitter is keyed to the calendar day — stable through an evening, different
 * tomorrow — and it is smaller than any real signal, so it only ever reorders candidates that were
 * already worth the same to us.
 */
function score(it, ctx) {
  let s = 0;

  if (it.inCinemas) s += W.inCinemas;
  if (Number.isFinite(it.releasedAt)) {
    const ageDays = (ctx.now - it.releasedAt) / DAY;
    if (ageDays >= 0 && ageDays <= W.freshDays) s += W.fresh * (1 - ageDays / W.freshDays);
  }
  if (Number.isFinite(it.addedAt)) {
    const window = Math.max(1, ctx.days || 45);
    const ageDays = (ctx.now - it.addedAt) / DAY;
    if (ageDays >= 0 && ageDays <= window) s += W.justAdded * (1 - ageDays / window);
  }

  const sources = it.sources || [];
  if (sources.length) {
    s += W.playable;
    if (sources.some((x) => x.kind !== 'torrent')) s += W.nonTorrent;
  }

  if (Number.isFinite(it.match)) s += Math.min(W.matchMax, Math.max(0, it.match) / 5);

  // The billboard is the front door of the house. A title whose language this household has rejected is
  // already gone — the allowlist rule dropped it before this row ever saw it — so what is left to order is
  // a title we positively know the house watches against one nobody has ever stated the language of. On
  // the single tile that decides the first two seconds of the app, the known one wins: this weight sits
  // just above the addons' popularity signal, and below what just opened in cinemas or last week.
  // Nothing here invents a language. It spends the knowledge already in data/languages.json.
  const allow = (ctx.prefs && ctx.prefs.audioLanguages) || [];
  if (allow.length && Array.isArray(it.languages) && it.languages.length
    && it.languages.some((l) => allow.includes(l))) s += W.languageFit;

  // Cached lookups only: a record this box has never made is not a fact, it is a request.
  const cert = ratings.peek(it.identity);
  if (cert && (cert.tier === 0 || cert.tier === 1)) s += W.broadlyWatchable;
  const clip = trailers.peek(it.identity);
  if (clip && clip.videoId) s += W.trailer;

  s += (hash(String(it.identity) + '@' + Math.floor(ctx.now / DAY)) % (2 * W.rotate + 1)) - W.rotate;
  return s;
}

// The row's own name. The home route nulls a hero row's `title` on the way out (the billboard has no
// heading over it), so the name has to ride on the tile for the app to be able to say where a tile came
// from. It is this shelf's name, not a chart the hub is quoting.
const TITLE = 'Featured';

module.exports = {
  id: 'hero',
  title: TITLE, // never rendered — see the header; the registry requires every row to name itself
  card: 'hero',
  order: 0, // before continue (10): the billboard is the first thing on the screen
  scope: 'all',
  max: CANDIDATES, // candidates through the rules, not tiles (the home route shows HERO_MAX of them)

  async build(ctx) {
    const items = await ctx.library();
    const pool = [];
    for (const it of items) {
      // A billboard is a title with art. A live channel has no backdrop to bleed, and a short video has
      // no story to sell in one frame, so both stay out of this row (they have their own shelves).
      if (it.type !== 'movie' && it.type !== 'series') continue;
      if (!it.title) continue;
      // The app paints the full-bleed frame from `backdrop`, and falls back to the poster. A tile with
      // neither is a black rectangle behind the household's own title, so it is not billboardsque.
      if (!isUrl(it.backdrop) && !isUrl(it.poster)) continue;
      pool.push(it);
    }
    pool.sort((a, b) => score(b, ctx) - score(a, ctx) || String(a.identity).localeCompare(String(b.identity)));

    // Diversity cap on the way out: highest first, but no genre band takes the whole pool.
    const perGenre = {};
    const out = [];
    for (const it of pool) {
      const band = (it.genres && it.genres[0]) || it.type || 'other';
      perGenre[band] = (perGenre[band] || 0) + 1;
      if (perGenre[band] > PER_GENRE) continue;
      out.push(it);
      if (out.length >= CANDIDATES) break;
    }
    // The app can say where a billboard tile came from, in the hub's own words: this shelf, this place in
    // it. The rank is the order these rules produced, before the home route drops a tile that also sits on
    // a shelf below and before it trims to the number of tiles that rotate — so the numbers can skip, and
    // a #7 here really is the seventh thing this box thought the household should see.
    out.forEach((it, i) => { it.board = { rank: i + 1, total: out.length, list: TITLE }; });
    return out;
  },
};
