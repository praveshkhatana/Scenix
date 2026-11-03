'use strict';
/*
 * rows/genres.js — a shelf of the categories this library actually has.
 *
 * Not a hardcoded genre list: the names and counts come from the items the hub already holds, so a
 * library of Punjabi films grows a Punjabi chip and a household that installs a documentary addon sees
 * Documentaries appear. The tile opens the search screen with that word, which is the same path a typed
 * search takes and therefore obeys the profile's rules.
 *
 * Cache counts alone make a browse page, not a taxonomy: a genre that lives only past the first
 * cached page never shows up, and a TV viewer's "Browse by category" then reads as a luck test.
 * A standard set of shelf names is appended so the category list is complete; the genre route
 * answers those tiles from the addons' live genre shelves, so a chip only promises what some addon
 * can actually fill.
 */
const shelves = require('../core/catalogs');

function slug(text) {
  return String(text || '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 48);
}

function tile(name, count) {
  return {
    identity: 'genre:' + slug(name),
    type: 'genre',
    title: name,
    year: null, poster: null, backdrop: null, logo: null,
    match: count, maturity: null, durationMs: null, languages: null,
    genres: [name], addedAt: null, releasedAt: null, inCinemas: false,
    live: null, progress: null, sources: [], count,
  };
}

module.exports = {
  id: 'genres',
  title: 'Browse by category',
  card: 'genre',
  order: 55,
  scope: 'all',
  max: 30,

  async build(ctx) {
    const items = await ctx.library();
    const counts = new Map();
    for (const it of items || []) {
      if (!it || it.type === 'channel') continue;
      for (const g of it.genres || []) {
        const name = String(g || '').trim();
        if (name) counts.set(name, (counts.get(name) || 0) + 1);
      }
    }
    const out = [...counts.entries()]
      .filter(([, n]) => n >= 2)
      .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
      .slice(0, 30)
      .map(([name, count]) => tile(name, count));
    // Complete the taxonomy for whoever is browsing, but only where an addon actually publishes a
    // genre shelf — a chip over an empty page is worse than no chip.
    const seen = new Set(out.map((t) => t.title.toLowerCase()));
    let canAsk = false;
    try {
      canAsk = (await shelves.catalogsOf(ctx))
        .some((e) => ((e.cat && e.cat.extra) || []).includes('genre'));
    } catch (e) { canAsk = false; }
    if (canAsk) {
      for (const name of shelves.STANDARD_GENRES) {
        if (out.length >= 30) break;
        if (seen.has(name.toLowerCase())) continue;
        seen.add(name.toLowerCase());
        out.push(tile(name, 0));
      }
    }
    return out;
  },
};
