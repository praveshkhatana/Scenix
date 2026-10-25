'use strict';
/*
 * rules/maturity.js — the two tiers of adult content, enforced on every path, hot-loaded like the rest.
 *
 * Drop this file in rules/ and it registers itself. It runs before the kids allowlist so the kids rule
 * sees only titles that already passed the age gate.
 *
 * Where the numbers come from: core/ratings.js keeps our own certificate database (OMDb + TMDB, looked up
 * once per title, stored in data/ratings.json forever). This file only decides what a tier MEANS for a
 * profile. The ladder table itself — which certificate maps to which tier — lives at the top of
 * core/ratings.js, as a table, for the same reason: the household edits data, not logic.
 *
 * The two levels:
 *   tier 2 "generic adult"   R / A / 16+ style. Invisible to children, ordinary for adults.
 *   tier 3 "the dark-red zone"  visible ONLY while the profile's `xxxEnabled` switch is on, and nowhere
 *      else: not in home, not in search, not in Continue Watching, not in history, for anyone. Turning it
 *      on adds no permanent tile either — `rows/latenight.js` only appears while the switch is on.
 *
 * Unrated is tier 2. The addons in this house state no certificate, and OMDb/TMDB coverage is patchy for
 * Indian titles, so "we asked and nothing was stated" is treated as adult-by-default: safer to hide from
 * a child than to guess.
 */
const ratings = require('../core/ratings');

/** Policy per tier. `switchRequired` is the one worth editing: set it false to let tier 3 be age-only. */
const POLICY = {
  0: { label: 'everyone', minAge: 0 },
  1: { label: 'teen', minAge: 13 },
  2: { label: 'adult', minAge: 16, switchRequired: false },
  3: { label: 'xxx', minAge: 18, switchRequired: true },
};

// An adult profile has no age ceiling of its own; it is not a child who needs protecting.
const ADULT_CEILING = 99;

function ageOf(profile) {
  const raw = profile && profile.age;
  const n = Number(raw);
  return Number.isFinite(n) && n > 0 ? n : null;
}

module.exports = {
  id: 'maturity',
  order: 15, // after the language allowlist, before the kids allowlist

  /**
   * Rules are synchronous per item here, and cheap: the certificate is read from data/ratings.json, never
   * fetched on the request path. A title we have never looked at is judged by the heuristic tier now and
   * looked up in the background, so the next page view has the real certificate.
   */
  apply(item, ctx) {
    if (!item || !item.identity) return item;

    // A certificate is a fact about a released title. A live channel or a file someone copied onto the
    // box has none and never will, so "we cannot rate this" must not read as "this is adult" — that would
    // hide every kids channel. Titles get the conservative default; everything else gets no opinion.
    const isTitle = item.type === 'movie' || item.type === 'series';

    const rec = ratings.peek(item.identity);
    // Only IMDb-backed titles can be looked up at all; asking for a channel spends the day's quota on
    // nothing. Everything the hub cannot ask about is judged by the heuristic alone.
    if (!rec && ratings.imdbIdOf(item.identity, item)) {
      ratings.lookup(item.identity, { type: item.type, title: item.title, year: item.year }).catch(() => {});
    }

    const known = ratings.know(item.identity, item);
    let tier = known.tier;
    let minAge = known.minAge;
    if (!isTitle && known.defaulted) {
      // No evidence of any kind on a channel or a file on disk: leave it where the other rules put it.
      tier = 0;
      minAge = 0;
    }
    const policy = POLICY[tier] || POLICY[2];

    // Additive: the app shows this on the tile, and a parent can see why something was hidden.
    item.maturity = known.maturity || item.maturity || null;
    item.tier = tier;
    item.minAge = minAge;
    item._ratingSource = known.from; // internal: scrub() drops it, the reason stays in the hub log

    // tier 3 is not an age question: it is a switch. Off, the title does not exist for anyone.
    if (policy.switchRequired && !(ctx.prefs && ctx.prefs.xxxEnabled)) return null;

    // Children see only what their age allows. Adults have no ceiling.
    const age = ageOf(ctx.profile);
    const ceiling = ctx.profile && ctx.profile.kind === 'kids' ? (age == null ? 12 : age) : ADULT_CEILING;
    if (minAge > ceiling) return null;

    return item;
  },
};
