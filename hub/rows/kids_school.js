'use strict';
/*
 * rows/kids_school.js — live channels whose own group is a kids group. Allow-list first, deny-list
 * second (the deny-list lives in rules/kids_allowlist.js): nothing reaches this row because a filter
 * failed to think of it. Same story as kids_videos: the row reads the library, not an addon.
 */
const KIDS_TAGS = ['Kids', 'Education', 'Animation', 'Children'];

module.exports = {
  id: 'kids_school',
  title: 'Cartoons & learning channels',
  card: 'channel',
  order: 30,
  scope: 'kids',
  max: 40,

  async build(ctx) {
    const items = await ctx.library();
    return items.filter((it) =>
      it.type === 'channel' && (it.genres || []).some((g) => KIDS_TAGS.includes(g)));
  },
};
