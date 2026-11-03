'use strict';
/*
 * rows/punjabiclassics.js — added as a NEW FILE, with no edit to index.js or to core/*.
 * The worked example of that: a new taste row is one file and one registration.
 *
 * It reads the shared library and filters on a language tag, so it works with the live TV addon, with
 * an installed addon that publishes Punjabi titles, and with both at once. Delete the file, the row is
 * gone; change the filter, the row changes. That is the whole point.
 */
module.exports = {
  id: 'punjabiclassics',
  title: 'Punjabi classics',
  card: 'poster',
  order: 60,
  scope: 'all',
  max: 20,

  async build(ctx) {
    const items = await ctx.library();
    return items.filter((it) => (it.languages || []).includes('pan'));
  },
};
