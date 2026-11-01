'use strict';
/*
 * rows/trending.js — "Trending on Scenix": the library the addons themselves rank first.
 *
 * Nothing here counts plays, so trend is read off the addons' own shelves: catalogue pages arrive
 * upstream-ranked, and the hub's cache keeps that order (core/taste.js only decorates, never
 * reorders raw pools). Two honest differences from Top 10 in this house: channels are excluded —
 * a playlist is alphabetical, not popular — and anything this profile has already started is
 * dropped, because a viewer does not need "trending" pushed at them while it sits on Continue
 * watching.
 */
module.exports = {
  id: 'trending',
  title: 'Trending on Scenix',
  card: 'poster',
  order: 25,
  scope: 'all',
  max: 18,

  async build(ctx) {
    const items = await ctx.library();
    const started = new Set(ctx.store.continueIdentities(ctx.profile.id, 30) || []);
    return (items || [])
      .filter((it) => it && (it.type === 'movie' || it.type === 'series'))
      .filter((it) => !started.has(it.identity))
      .slice(0, 40);
  },
};
