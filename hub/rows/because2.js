'use strict';
/*
 * rows/because2.js — a second hook rail, seeded by the SECOND-recently watched title.
 *
 * One "Because you watched" row answers for the last thing played; a household that watches a
 * crime series AND a comedy wants both read back. Netflix does exactly this — two hook rails,
 * different seeds. When the profile has only one watched title this row builds nothing, and the
 * home composition drops empty rows, so the screen never shows a headline with no posters.
 */
const hooks = require('../core/hooks');

function clip(t) {
  const s = String(t || '').trim();
  return s.length > 34 ? s.slice(0, 33).trimEnd() + '…' : s;
}

module.exports = {
  id: 'because-2',
  title: 'Because you watched',
  card: 'poster',
  order: 13,
  scope: 'all',
  max: 18,

  async label(ctx) {
    const seeds = await hooks.recentTitles(ctx, 2);
    const seed = seeds[1];
    return seed && seed.title ? 'More like ' + clip(seed.title) : null;
  },

  async build(ctx) {
    const seeds = await hooks.recentTitles(ctx, 2);
    if (seeds.length < 2) return [];
    // The first seed drives the row above; this rail answers to the second one alone, so the
    // two hook rails genuinely differ instead of being the same shelf twice.
    return hooks.similarTo(ctx, [seeds[1]], { limit: 60 });
  },
};
