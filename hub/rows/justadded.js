'use strict';
/*
 * rows/justadded.js — everything the hub has seen in the last `ctx.days`, newest first.
 *
 * "Seen" means the identity was first noticed by any provider and stamped into
 * hub/data/library.json, so this row survives a restart and a re-deploy: a channel that has been
 * there for a month is not "just added" any more, and one that dropped out of the playlist and came
 * back keeps its original addedAt.
 */
module.exports = {
  id: 'justadded',
  title: 'Just added to your library',
  card: 'poster',
  order: 30,
  scope: 'all',
  max: 30,

  async build(ctx) {
    const cutoff = ctx.now - ctx.days * 86400000;
    const items = await ctx.library();
    return items
      .filter((it) => Number.isFinite(it.addedAt) && it.addedAt >= cutoff)
      .sort((a, b) => b.addedAt - a.addedAt);
  },
};
