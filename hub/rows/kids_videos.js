'use strict';
/*
 * rows/kids_videos.js — videos tagged for children: curated YouTube uploads today, and a folder of
 * your own files called "Kids" tomorrow. The row asks the library, never an addon by name, so an
 * installed addon that publishes kid-tagged videos lands here without touching this file.
 * The kids-allowlist rule double-checks every item anyway, so this row cannot leak.
 */
const KIDS_TAGS = ['Kids', 'Education', 'Animation', 'Children'];

module.exports = {
  id: 'kids_videos',
  title: 'Kids videos',
  card: 'poster',
  order: 20,
  scope: 'kids',
  max: 30,

  async build(ctx) {
    const items = await ctx.library();
    return items.filter((it) =>
      (it.type === 'video' || it.type === 'series')
      && (it.genres || []).some((g) => KIDS_TAGS.includes(g)));
  },
};
