'use strict';
/*
 * rows/taste_comedy.js — a "Comedy" shelf for the adult profiles, added as a NEW FILE only.
 *
 * One file per taste, so "add a Sci-Fi row" is one file and "stop showing Horror" is deleting one, with
 * no edit to index.js or core/*. It asks whichever addons are mounted for the `Comedy` **genre**
 * (movies first, then series) rather than naming an addon, so installing a better title addon changes
 * what lands here without changing any code. `scope: "adult"` keeps it off a child's home, and
 * rules/language_allowlist.js still drops anything whose audio language this house does not watch.
 *
 * Cinemeta declares Comedy as a genre extra; nothing else is needed.
 */
const shelves = require('../core/catalogs');

module.exports = {
  id: 'taste-comedy',
  title: 'Comedy',
  card: 'poster',
  order: 62,
  scope: 'adult',
  max: 30,

  build(ctx) {
    return shelves.genreTitles(ctx, 'Comedy', { limit: 30 });
  },
};
