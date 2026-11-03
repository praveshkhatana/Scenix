'use strict';
/*
 * rows/toprated.js — the shelf a undecided evening reaches for: the best-liked things in the hub.
 *
 * The addons already ship IMDb/TMDB numbers on their metas; this row only asks a simple question
 * of the library — rated, high, film or show — and lets the number do the ranking. It reads the
 * cached library only: a shelf of verdicts should be instant and stable, and a cold cache means
 * no shelf rather than a slow one. core/taste.js still decorates the tiles afterwards, so "best"
 * arrives personalised without the ordering pretending to know this house.
 */
module.exports = {
  id: 'toprated',
  title: 'Critically acclaimed',
  card: 'poster',
  order: 26,
  scope: 'all',
  max: 18,

  async build(ctx) {
    const items = await ctx.library();
    return (items || [])
      .filter((it) => it && (it.type === 'movie' || it.type === 'series'))
      .filter((it) => it.rating && Number(it.rating.value) >= 7.5)
      .sort((a, b) => Number(b.rating.value) - Number(a.rating.value))
      .slice(0, 40);
  },
};
