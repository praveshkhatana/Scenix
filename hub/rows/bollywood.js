'use strict';
/*
 * rows/bollywood.js — Hindi films from every addon, under the name a household actually uses.
 *
 * The profile's language order is the region signal this hub already has. When Hindi is on it,
 * Bollywood is not a keyword row: it is the Hindi films the library already holds, mixed from every
 * installed addon and ordered by the profile's taste and the editorial rating.
 */
const media = require('../core/media');

module.exports = {
  id: 'bollywood',
  title: 'Bollywood Movies',
  card: 'poster',
  order: 72,
  scope: 'all',
  max: 30,

  async build(ctx) {
    const wanted = ((ctx.prefs && ctx.prefs.audioLanguages) || [])
      .map((c) => media.langOf(c));
    if (!wanted.includes('hin')) return [];
    const library = await ctx.library().catch(() => []);
    return (library || [])
      .filter((it) => it && it.type === 'movie'
        && ((it.languages || []).some((l) => media.langOf(l) === 'hin')
          // DesiFlix is an Indian catalogue even when its metadata omits the language tag.
          || (it._addons || []).includes('desiflix')))
      .sort((a, b) => (Number(b.rating) || 0) * 10 + (Number(b.match) || 0)
        - ((Number(a.rating) || 0) * 10 + (Number(a.match) || 0))
        || (Number(b.releasedAt) || 0) - (Number(a.releasedAt) || 0))
      .slice(0, 30);
  },
};
