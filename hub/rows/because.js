'use strict';
/*
 * rows/because.js — "Because you watched <last title>", the row every good app leads with after
 * Continue watching, built entirely from what this hub knows without any analytics.
 *
 * The hub's own progress store says what this profile watched; the addons say what each title IS
 * (its genres). core/hooks.js joins them: cached catalogue first, the addons' live genre shelves
 * to reach past the first page, and a shared-genre score so the row means RELATED — not merely
 * "also good". The shelf header names the actual title ("Because you watched City Hunter"), which
 * is what makes the row feel written for this person rather than printed from a template.
 */
const hooks = require('../core/hooks');

function clip(t) {
  const s = String(t || '').trim();
  return s.length > 34 ? s.slice(0, 33).trimEnd() + '…' : s;
}

module.exports = {
  id: 'because',
  title: 'Because you watched',
  card: 'poster',
  order: 12,
  scope: 'all',
  max: 18,

  async label(ctx) {
    const seed = (await hooks.recentTitles(ctx, 1))[0];
    return seed && seed.title ? 'Because you watched ' + clip(seed.title) : null;
  },

  async build(ctx) {
    const seeds = await hooks.recentTitles(ctx, 2);
    if (!seeds.length) return [];
    return hooks.similarTo(ctx, seeds, { limit: 60 });
  },
};
