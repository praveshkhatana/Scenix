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
    // A profile's live lineup is a choice about channels, not just about the Live TV wall: when a child
    // has picked their own channels, this shelf shows the kids-safe ones among them instead of ignoring
    // the choice. With no lineup it keeps the genre rule.
    const local = (ctx.profile && ctx.profile.prefs && ctx.profile.prefs.live) || {};
    const only = Array.isArray(local.only) ? local.only : [];
    const allowed = only.length ? new Set(only) : null;
    return items.filter((it) => it.type === 'channel'
      && (!allowed || allowed.has(it.identity))
      && (it.genres || []).some((g) => KIDS_TAGS.includes(g)));
  },
};
