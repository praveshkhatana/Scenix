'use strict';
/*
 * rows/movies.js — the Movies section: everything this hub carries that is a film.
 *
 * A row plugin rather than a client-side filter, because the hub decides what exists: the nav tab is
 * offered only when this shelf answers with something, and the section page and a "See all" would
 * open the same answer. `home: false` makes it a section, not a shelf — the home screen keeps its
 * curated shape, and this row exists for the tab above it. Newest release first; a film with no
 * known release date sorts last rather than disappearing.
 */
module.exports = {
  id: 'movies',
  title: 'Movies',
  card: 'poster',
  order: 60,
  scope: 'all',
  home: false,
  max: 300,

  async build(ctx) {
    const items = await ctx.library();
    return items
      .filter((it) => it.type === 'movie')
      .sort((a, b) => (Number(b.releasedAt) || 0) - (Number(a.releasedAt) || 0)
        || String(a.title || '').localeCompare(String(b.title || '')));
  },
};
