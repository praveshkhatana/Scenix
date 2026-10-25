'use strict';
/*
 * rows/taste_superhero.js — a "Superhero" shelf for the adult profiles, added as a NEW FILE only.
 *
 * One file per taste, so "add a Sci-Fi row" is one file and "stop showing Horror" is deleting one, with
 * no edit to index.js or core/*. It asks whichever addons are mounted for the `Superhero` **genre**
 * (movies first, then series) rather than naming an addon, so installing a better title addon changes
 * what lands here without changing any code. `scope: "adult"` keeps it off a child's home, and
 * rules/language_allowlist.js still drops anything whose audio language this house does not watch.
 *
 * 'Superhero' is not in Cinemeta's declared genre list, but its catalog answers it (verified live), so the row is real rather than a guess.
 */
const shelves = require('../core/catalogs');

module.exports = {
  id: 'taste-superhero',
  title: 'Superhero',
  card: 'poster',
  order: 67,
  scope: 'adult',
  max: 30,

  build(ctx) {
    return shelves.genreTitles(ctx, 'Superhero', { limit: 30 });
  },
};
