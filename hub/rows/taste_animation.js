'use strict';
/*
 * rows/taste_animation.js — a "Cartoons & Animation" shelf for the adult profiles, added as a NEW FILE only.
 *
 * One file per taste, so "add a Sci-Fi row" is one file and "stop showing Horror" is deleting one, with
 * no edit to index.js or core/*. It asks whichever addons are mounted for the `Animation` **genre**
 * (movies first, then series) rather than naming an addon, so installing a better title addon changes
 * what lands here without changing any code. `scope: "adult"` keeps it off a child's home, and
 * rules/language_allowlist.js still drops anything whose audio language this house does not watch.
 *
 * Animation is not a kids genre on an adult shelf: this is the row for the adult animation the family also watches.
 */
const shelves = require('../core/catalogs');

module.exports = {
  id: 'taste-animation',
  title: 'Cartoons & Animation',
  card: 'poster',
  order: 66,
  scope: 'adult',
  max: 30,

  build(ctx) {
    return shelves.genreTitles(ctx, 'Animation', { limit: 30 });
  },
};
