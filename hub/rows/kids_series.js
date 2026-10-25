'use strict';
/*
 * rows/kids_series.js — a Kids Series shelf for the kids home, added as a NEW FILE only.
 *
 * Same contract as rows/kids_movies.js: it asks for the Animation / Family / Adventure genres rather
 * than naming an addon, it is `scope: "kids"` so only a kids profile sees it, and every tile passes
 * through rules/kids_allowlist.js. A kids profile's home is therefore cartoons and shows a child can
 * watch, not "whatever the addon happened to return".
 */
const shelves = require('../core/catalogs');

module.exports = {
  id: 'kids_series',
  title: 'Kids Series',
  card: 'poster',
  order: 24,
  scope: 'kids',
  max: 30,

  async build(ctx) {
    const lists = await Promise.all(
      ['Animation', 'Family', 'Adventure'].map((g) => shelves.genreShelf(ctx, 'series', g, { limit: 20 })),
    );
    return lists.flat().filter(Boolean);
  },
};
