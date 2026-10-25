'use strict';
/*
 * rows/latenight.js — the tier-3 shelf, and the only place those titles are ever proposed.
 *
 * It contributes items ONLY while the requesting profile has `xxxEnabled` on, and it contributes nothing
 * else to the home screen: with the switch off the row builds empty, so the family walking past the TV
 * never sees a tile named after the corner. Turning the switch on is a deliberate act, and so is going
 * looking for this row — it sits at the end of the home order, not the middle.
 *
 * The items are titles our own database (core/ratings.js) calls tier 3: an explicit adult flag from
 * TMDB, a genre that says Adult, or an addon the household marked `"adult": true` in data/addons.json.
 * Titles we simply have no certificate for are tier 2 (generic adult), not tier 3, so they do NOT turn up
 * here — the heuristic never accuses a title it has not seen evidence about.
 */
const ratings = require('../core/ratings');

module.exports = {
  id: 'latenight',
  title: 'Late Night',
  card: 'poster',
  order: 70, // after every taste row: the last thing on the home screen, not one of the first
  scope: 'adult',
  max: 30,

  async build(ctx) {
    if (!(ctx.prefs && ctx.prefs.xxxEnabled)) return []; // switch off: no row, no trace
    const items = await ctx.library();
    const out = [];
    for (const it of items) {
      // Any kind of item can land here: a release carrying an adult flag, a file under a folder named
      // "Private", or anything published by an addon the household marked `"adult": true`.
      const known = ratings.know(it.identity, it);
      if (known.tier === 3) out.push(it);
    }
    // Known-by-flag first, in the order the addons published them.
    return out;
  },
};
