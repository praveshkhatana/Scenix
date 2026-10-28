'use strict';
/*
 * rows/series.js — the Series section: every show this hub carries.
 *
 * Same contract as rows/movies.js: a section for the nav tab, not a shelf on Home (`home: false`).
 * Newest first release wins the top; a series with no known date sorts last rather than vanishing,
 * because "no date known" is the hub being incomplete, not the show being old.
 */
module.exports = {
  id: 'series',
  title: 'Series',
  card: 'poster',
  order: 61,
  scope: 'all',
  home: false,
  max: 300,

  async build(ctx) {
    const items = await ctx.library();
    return items
      .filter((it) => it.type === 'series')
      .sort((a, b) => (Number(b.releasedAt) || 0) - (Number(a.releasedAt) || 0)
        || String(a.title || '').localeCompare(String(b.title || '')));
  },
};
