'use strict';
/*
 * rules/kids_allowlist.js — a kids profile only ever sees content that is allow-listed for that child.
 *
 * Hard walls, checked on EVERY item of EVERY row (and again at play time, because pickAndPlay runs the
 * same pipeline), so a row or addon someone adds later cannot leak something adult onto a child's shelf:
 *   1. a live channel must sit in a kids group (Kids / Education / Animation / Children) and must not
 *      be on the deny-list of kid-grouped-but-not-for-kids channels;
 *   2. a video must carry a kids tag (the curated YouTube addon tags its uploads "Kids"; a folder of
 *      your own files called "Kids" works the same way);
 *   3. a movie/series is judged by the child's own age, read off the profile — never from a hardcoded
 *      list of profile ids in this file;
 *   4. a torrent is never offered to a kid: it waits on strangers' uploaders and needs a player that
 *      understands magnets.
 * Nothing here names an addon, so native and installed addons are judged by exactly the same evidence.
 * Only runs when the profile is `kind: "kids"`.
 *
 * HOW THE AGE DECIDES (profile.age, or rules['kids-allowlist'].maxAge to override it):
 *   - age ≤ 12  strict: only a stated rating inside {G, PG, U, U/A 7+, TV-Y, TV-Y7, TV-Y7-FV, TV-G,
 *             TV-PG}, or a kids genre.
 *   - age 13-17 teen: the strict list plus the 13+ band {PG-13, UA, U/A, U/A 13+, 12A, 12, 13+, TV-14,
 *                   15, 15A, M} — which is exactly what "Yug may watch UA/13+ content" means, spelled as
 *                   data. `M` is Australia's mature-15+, not an adult mark.
 *   - a certificate that writes an age (11, 12, 15A) is allowed when the child has reached that age,
 *     because the boards do not agree on words.
 *   - no age at all: the strict band, because guessing is the unsafe direction.
 *
 * One honest limitation, written down rather than hidden: the metadata addons in this household
 * (cinemeta) state **no rating at all** — `maturity` is null on every one of their movie/series tiles.
 * So the rating ceiling can only reject a rating that exists; what actually keeps an adult film off the
 * shelf when the rating is missing is the genre wall below (ADULT_GENRES, plus a required kids genre for
 * the strict band). A household that wants a stronger bar sets "allowTitles" or adds a rated addon.
 *
 * Per profile, profiles.json may narrow it further:
 *   "rules": { "kids-allowlist": { "allowAddons": ["live-tv"], "maxAge": 11, "requireSources": true } }
 */
const KIDS_GROUPS = ['Kids', 'Education', 'Animation', 'Children'];
const KIDS_BLOCK = (process.env.KIDS_BLOCK || 'Animax,MediBizTV,BVG,Vyas,DD Kisan,Unique TV')
  .split(',').map((s) => s.trim()).filter(Boolean);

// Ratings inside the strict (≤ 12) band. TV-PG belongs here, not in the teen band: it means parental
// guidance, our own ladder scores it tier 0, and leaving it out killed a series for one child whose *only*
// stated certificate was TV-PG — a rating that passed the maturity rule and then died on this list.
const STRICT_MATURITY = ['G', 'PG', 'U', 'TV-Y', 'TV-Y7', 'TV-Y7-FV', 'TV-G', 'TV-PG', 'U/A 7+'];
// What a 13-17 profile may additionally watch: India's UA, the 12A/12 certificates, the 13+ band.
// `M` is Australia's "mature 15+", not an adult mark — it reads as adult only in the US.
const TEEN_EXTRA_MATURITY = ['PG-13', 'UA', 'U/A', 'U/A 13+', '12A', '12', '13+', 'TV-14', '15', '15A', 'M'];
// A stated rating in here is adult for any child, whatever the age says.
const ADULT_MATURITY = ['R', 'A', 'NC-17', 'TV-MA', '18', '16', 'X', 'Banned', 'Unrated'];

/**
 * A certificate that writes an age outright beats any word list, because the boards do not agree on
 * words: several European boards print `11`, `12`, `14`, and Ireland prints `15A`. Reading those as
 * strings means an age this child has reached is rejected for being spelled unusually, so the number
 * itself decides. A number that is not an age at all (a year, a duration) cannot sneak through: it has
 * to be in the range a classification board would ever publish.
 */
function ageCertAllowed(rated, age) {
  if (age == null) return false;
  const m = /(?:^|[^\d])(\d{1,2})(?:\+?A?)(?:$|[^\d])/.exec(rated);
  if (!m) return false;
  const n = Number(m[1]);
  return n >= 3 && n <= 18 && n <= age;
}

// A genre that is never a child's film, whatever the missing rating would have said.
const ADULT_GENRES = ['Horror', 'Crime', 'Thriller', 'War', 'Mystery'];
// Genres that, on their own, are evidence enough that a young child may watch this. Deliberately short:
// with no rating stated anywhere, "Adventure" or "Fantasy" is not evidence of anything (a Game of
// Thrones is Fantasy and Drama), so only the genres that exist *for children* let a title through the
// strict band. The teen band does not need them, and the kids rows ask for more genres than this allows,
// which is the point: the row proposes, this rule decides per child.
const YOUNG_GENRES = ['Animation', 'Family', 'Kids', 'Children'];

function ageOf(profile, cfg) {
  const raw = Number.isFinite(cfg.maxAge) ? cfg.maxAge : Number(profile.age);
  return Number.isFinite(raw) && raw > 0 && raw < 18 ? Math.floor(raw) : null;
}

/** The ratings this child may watch, derived from the age on the profile. */
function maturityFor(age) {
  if (age == null || age <= 12) return { band: 'strict', allowed: STRICT_MATURITY.slice() };
  return { band: 'teen', allowed: STRICT_MATURITY.concat(TEEN_EXTRA_MATURITY) };
}

module.exports = {
  id: 'kids-allowlist',
  order: 20,

  async apply(item, ctx) {
    if (!ctx.profile || ctx.profile.kind !== 'kids') return item;
    const cfg = (ctx.profile.rules && ctx.profile.rules['kids-allowlist']) || {};

    // Optional, in data (never in code): "this kid watches these addons and nothing else".
    if (Array.isArray(cfg.allowAddons) && cfg.allowAddons.length) {
      if (!(item._addons || []).some((a) => cfg.allowAddons.includes(a))) return null;
    }

    const age = ageOf(ctx.profile, cfg);
    const maturity = maturityFor(age);

    // 4. a torrent is never offered to a kid, whatever the type.
    const sources = (item.sources || []).filter((s) => s.kind !== 'torrent');

    if (item.type === 'channel') {
      const groups = item.genres || [];
      if (!groups.some((g) => KIDS_GROUPS.includes(g))) return null;
      if (KIDS_BLOCK.some((b) => String(item.title || '').startsWith(b))) return null;
      item.sources = sources;
      return item;
    }

    if (item.type === 'video') {
      if (!(item.genres || []).some((g) => KIDS_GROUPS.includes(g))) return null;
      if (cfg.requireSources && !sources.length) return null;
      item.sources = sources;
      return item;
    }

    if (item.type === 'movie' || item.type === 'series') {
      const genres = item.genres || [];
      const rated = item.maturity ? String(item.maturity).trim().toUpperCase() : null;
      if (rated) {
        if (ADULT_MATURITY.includes(rated)) return null;
        // Stated, but over this child's ceiling — unless the certificate states an age this child has.
        if (!maturity.allowed.includes(rated) && !ageCertAllowed(rated, age)) return null;
      } else {
        // No rating stated: the genres are the only evidence, so they carry the whole decision.
        if (genres.some((g) => ADULT_GENRES.includes(g))) return null;
        if (maturity.band === 'strict' && !genres.some((g) => YOUNG_GENRES.includes(g))) return null;
      }
      // A tile whose only copies are torrents stays on the shelf and answers "no sources" when it is
      // played (this rule runs again at play time). That is deliberate: an empty shelf is invisible,
      // and the hub warms and persists its stream cache, so the copies usually arrive on their own.
      if (cfg.requireSources && !sources.length) return null;
      item.sources = sources;
      return item;
    }

    return null; // anything else an addon invents stays off a kids shelf until we decide what it is
  },

  // Exposed for the shelf rows and for /healthz: what this child's band is.
  bandFor(profile) {
    const cfg = (profile && profile.rules && profile.rules['kids-allowlist']) || {};
    const age = ageOf(profile || {}, cfg);
    return { age, ...maturityFor(age) };
  },
};
