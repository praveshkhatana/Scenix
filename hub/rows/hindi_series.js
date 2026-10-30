'use strict';
/*
 * rows/hindi_series.js — Hindi-language shows for a household that watches Hindi.
 *
 * Same rule as Bollywood: the library is the mix, the profile's language order is the filter.
 */
const media = require('../core/media');

module.exports = {
  id: 'hindi-series',
  title: 'Hindi Web Series',
  card: 'poster',
  order: 73,
  scope: 'all',
  max: 30,

  async build(ctx) {
    const wanted = ((ctx.prefs && ctx.prefs.audioLanguages) || [])
      .map((c) => media.langOf(c));
    if (!wanted.includes('hin')) return [];
    const library = await ctx.library().catch(() => []);
    return (library || [])
      .filter((it) => it && it.type === 'series'
        && ((it.languages || []).some((l) => media.langOf(l) === 'hin')
          || (it._addons || []).includes('desiflix')))
      .sort((a, b) => (Number(b.rating) || 0) * 10 + (Number(b.match) || 0)
        - ((Number(a.rating) || 0) * 10 + (Number(a.match) || 0))
        || (Number(b.releasedAt) || 0) - (Number(a.releasedAt) || 0))
      .slice(0, 30);
  },
};
