'use strict';
/*
 * rows/genres.js — a shelf of the categories this library actually has.
 *
 * Not a hardcoded genre list: the names and counts come from the items the hub already holds, so a
 * library of Punjabi films grows a Punjabi chip and a household that installs a documentary addon sees
 * Documentaries appear. The tile opens the search screen with that word, which is the same path a typed
 * search takes and therefore obeys the profile's rules.
 */
function slug(text) {
  return String(text || '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 48);
}

module.exports = {
  id: 'genres',
  title: 'Browse by category',
  card: 'genre',
  order: 55,
  scope: 'all',
  max: 18,

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
    return [...counts.entries()]
      .filter(([, n]) => n >= 3)
      .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
      .slice(0, 18)
      .map(([name, count]) => ({
        identity: 'genre:' + slug(name),
        type: 'genre',
        title: name,
        year: null, poster: null, backdrop: null, logo: null,
        match: null, maturity: null, durationMs: null, languages: null,
        genres: [name], addedAt: null, releasedAt: null, inCinemas: false,
        live: null, progress: null, sources: [], count,
      }));
  },
};
