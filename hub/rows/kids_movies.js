'use strict';
/*
 * rows/kids_movies.js — a Kids Movies shelf for the kids home, added as a NEW FILE only.
 *
 * It asks the hub for the Animation / Family / Adventure **genres**, never for an addon, so it fills
 * from Cinemeta today and from whatever title addon the household installs tomorrow. The row is
 * `scope: "kids"`, so it appears on a kids profile's home and nowhere else; and every tile still runs
 * through rules/kids_allowlist.js, which is what actually keeps an adult title off a child's shelf
 * (the genre wall plus, where a rating exists, the child's age band).
 */
const shelves = require('../core/catalogs');

module.exports = {
  id: 'kids_movies',
  title: 'Kids Movies',
  card: 'poster',
  order: 22,
  scope: 'kids',
  max: 30,

  async build(ctx) {
    const lists = await Promise.all(
      ['Animation', 'Family', 'Adventure'].map((g) => shelves.genreShelf(ctx, 'movie', g, { limit: 20 })),
    );
    // Stamp which shelf this came from. `_addons` is the one field merge.dedupe carries across sources,
    // so it survives the pipeline, and it is internal (scrub() drops it): it is how a rule can tell a
    // title a parent curated for a child from the same title arriving through a search of the whole
    // catalogue. core/ratings.js inferTier() reads the `catalog:` mark; see its comment.
    return lists.flat().filter(Boolean).map((it) => ({
      ...it,
      _addons: [...(it._addons || []), `catalog:${module.exports.id}`],
    }));
  },
};
