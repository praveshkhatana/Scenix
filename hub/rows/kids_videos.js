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
    return items
      .filter((it) =>
        (it.type === 'video' || it.type === 'series')
        && (it.genres || []).some((g) => KIDS_TAGS.includes(g)))
      // The `catalog:` mark is internal plumbing (scrub() drops it) and survives merge.dedupe, so
      // core/ratings.js can tell "a title from a shelf a parent curated for a child" apart from the same
      // title arriving out of a search of the whole catalogue. Only titles with no certificate use it.
      .map((it) => ({ ...it, _addons: [...(it._addons || []), `catalog:${module.exports.id}`] }));
  },
};
