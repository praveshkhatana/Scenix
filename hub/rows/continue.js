'use strict';
/*
 * rows/continue.js — "keep watching where you stopped", straight from hub/data/progress.json.
 * The hub knows nothing about what the app played except what it POSTed to /app/v1/progress.
 */
module.exports = {
  id: 'continue',
  title: 'Continue watching',
  card: 'continue',
  order: 10,
  scope: 'all',

  async build(ctx) {
    const ids = ctx.store.continueIdentities(ctx.profile.id, 20);
    const items = [];
    for (const identity of ids) {
      const it = await ctx.resolve(identity);
      if (it) items.push(it);
    }
    return items;
  },
};
