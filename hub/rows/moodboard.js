'use strict';
/*
 * rows/moodboard.js — a shelf built from what this person liked and watched.
 *
 * Like / love / dislike and background watches already live on the box (core/taste.js). There is no
 * recommendation service and no network call here: the row is the highest-affinity titles this
 * profile has not seen yet, with a per-genre cap so it stays a shelf and not a wall of one film's
 * cousins. It is deliberately quiet when the profile has no taste yet — an empty row is dropped.
 */
const taste = require('../core/taste');

module.exports = {
  id: 'moodboard',
  title: 'In your mood',
  card: 'poster',
  order: 14,
  scope: 'all',
  max: 24,

  async build(ctx) {
    const items = await ctx.library();
    return taste.moodBoard(ctx, items, 18);
  },
};
