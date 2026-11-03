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
 * Unrated is NOT a tier. A title where a board spoke is decided by the strictest board that answered —
 * that part is a fact and it never changes. A title where no board spoke is a policy question, and
 * core/ratings.js answers it in two steps: first the cheap signals that are already in the item (which
 * shelf it came from, the genres it is filed under, how long it runs), which is what keeps a curated
 * kids shelf full, and only if those say nothing does the conservative adult default apply. Every such
 * decision carries `defaulted: true`, so nothing here pretends to know a certificate it never read.
 *
 * The consequence worth knowing: an unknown title can now be VISIBLE to a child when the evidence says
 * it is children's content, and an unknown title in an adult catalogue stays invisible to children while
 * remaining completely ordinary for the adults (an adult profile has no age ceiling).
 */
const ratings = require('../core/ratings');

/** Set MATURITY_DEBUG=1 to log every item this rule hides, with the evidence it hid it on. */
const DEBUG = process.env.MATURITY_DEBUG === '1' || process.env.MATURITY_DEBUG === 'true';
const log = (...a) => console.log(new Date().toISOString(), ...a);

/** Policy per tier. `switchRequired` is the one worth editing: set it false to let tier 3 be age-only. */
const POLICY = {
  0: { label: 'everyone', minAge: 0 },
  1: { label: 'teen', minAge: 13 },
  2: { label: 'adult', minAge: 16, switchRequired: false },
  3: { label: 'xxx', minAge: 18, switchRequired: true },
};

/*
 * What a certificate suggests, not a scene-by-scene guide. The hub has no timecodes for nudity or
 * violence, and pretending otherwise would be worse than saying nothing: this is the short list a person
 * reads before putting a film on with children in the room. A title from a board with a known age gets the
 * list for that band; a horror also says so, because a 15-rated horror and a 15-rated comedy are not the
 * same evening. The UI always labels this as coming from the rating.
 */
const ADVISORY_BY_TIER = {
  0: [],
  1: ['violence', 'language'],
  2: ['violence', 'language', 'sexual content'],
  3: ['nudity', 'sex', 'violence', 'strong language'],
};
function advisoriesFor(tier, genres) {
  const out = new Set(ADVISORY_BY_TIER[tier] || []);
  const g = (genres || []).map((x) => String(x).toLowerCase());
  if (g.some((x) => x.includes('horror'))) out.add('disturbing images');
  if (tier >= 2 && g.some((x) => x.includes('romance') || x.includes('drama'))) out.add('sexual content');
  return [...out];
}

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
    item.advisories = isTitle ? advisoriesFor(tier, item.genres) : [];
    item.minAge = minAge;
    item._ratingSource = known.from; // internal: scrub() drops it, the reason stays in the hub log
    // `defaulted` is the difference between a number a board gave and a number the household chose.
    // `inferred` narrows it further: a guess from the shelf and the genres, not the flat adult default.
    item._ratingDefaulted = known.defaulted === true;
    item._ratingInferred = known.inferred === true;
    item._ratingBoards = known.boards || 0;

    // tier 3 is not an age question: it is a switch. Off, the title does not exist for anyone.
    if (policy.switchRequired && !(ctx.prefs && ctx.prefs.xxxEnabled)) {
      if (DEBUG) log('hide', ctx.profile && ctx.profile.id, item.identity, item.title, '- tier 3, switch off');
      return null;
    }

    // Children see only what their age allows. Adults have no ceiling.
    const age = ageOf(ctx.profile);
    const ceiling = ctx.profile && ctx.profile.kind === 'kids' ? (age == null ? 12 : age) : ADULT_CEILING;
    if (minAge > ceiling) {
      if (DEBUG) {
        log('hide', ctx.profile && ctx.profile.id, item.identity, item.title,
          '-', tier + '/' + minAge, 'for age', ceiling, known.defaulted ? '(defaulted' + (known.inferred ? ', inferred' : '') + ')' : '(board)',
          '|', known.from);
      }
      return null;
    }

    return item;
  },
};
