'use strict';
/*
 * rows/taste_horror.js — a "Horror" shelf for the adult profiles, added as a NEW FILE only.
 *
 * One file per taste, so "add a Sci-Fi row" is one file and "stop showing Horror" is deleting one, with
 * no edit to index.js or core/*. It asks whichever addons are mounted for the `Horror` **genre**
 * (movies first, then series) rather than naming an addon, so installing a better title addon changes
 * what lands here without changing any code. `scope: "adult"` keeps it off a child's home, and
 * rules/language_allowlist.js still drops anything whose audio language this house does not watch.
 *
 * Horror is adult by policy here: the row is scope 'adult', and rules/kids_allowlist.js refuses the genre on a kids shelf whatever the rating says.
 */
const shelves = require('../core/catalogs');

module.exports = {
  id: 'taste-horror',
  title: 'Horror',
  card: 'poster',
  order: 65,
  scope: 'adult',
  max: 30,

  build(ctx) {
    return shelves.genreTitles(ctx, 'Horror', { limit: 30 });
  },
};
