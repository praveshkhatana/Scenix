'use strict';
/*
 * rows/newreleases.js — newest `releasedAt` first (Cinemeta's release date, not our addedAt).
 * Anything with no known release date is skipped: a channel is not a new release.
 */
module.exports = {
  id: 'newreleases',
  title: 'New releases',
  card: 'poster',
  order: 40,
  scope: 'all',
  max: 30,

  async build(ctx) {
    const items = await ctx.library();
    return items
      .filter((it) => Number.isFinite(it.releasedAt))
      .sort((a, b) => b.releasedAt - a.releasedAt);
  },
};
