'use strict';
/*
 * core/catalogs.js — "give me this kind of story", asked of every mounted addon at once.
 *
 * A shelf that names an addon in its code is a shelf that breaks the moment the household installs a
 * better addon. Every genre/search row in rows/ asks this instead: which addons are mounted right now,
 * which of their catalogs accept the extra I want (`genre`, `search`), and what do they have in it?
 * Install an addon and its catalogs show up on the home page on the next request; disable it and the
 * shelf empties — the plugin property, kept for shelves as well as for providers.
 *
 * Budgets and caching are the hub's, not this file's: it goes through ctx.catalog(), which answers
 * from the addon's own cache and kicks a background fetch for anything cold. So the first home page
 * after a restart can come back short and the next one is full — same behaviour as every other row that
 * reads an external addon, and the reason `curl` verification is done twice.
 */

/** Catalog descriptors of every mounted provider that can serve a catalog. */
async function catalogsOf(ctx) {
  const out = [];
  for (const p of Object.values(ctx.providers || {})) {
    if (typeof p.catalogs !== 'function' || typeof p.catalog !== 'function') continue;
    let cats = [];
    try {
      cats = (await p.catalogs()) || [];
    } catch (e) {
      continue; // an addon whose catalog list will not load is simply not asked
    }
    for (const c of cats) out.push({ provider: p, cat: c });
  }
  return out;
}

/**
 * Catalogs for one item type that accept one extra. The type is matched strictly: a catalog that does
 * not say what kind of thing it holds (a live-TV catalog, say) is never offered as a movie shelf.
 */
function matching(entries, type, extraName) {
  return entries.filter((e) => {
    if (type && e.cat.type !== type) return false;
    if (extraName && !(e.cat.extra || []).includes(extraName)) return false;
    return true;
  });
}

/**
 * One genre shelf: everything every mounted addon has in that genre for that type.
 * Returns [] when no addon publishes a genre catalog — an empty shelf, not a broken one.
 */
async function genreShelf(ctx, type, genre, opts = {}) {
  const entries = matching(await catalogsOf(ctx), type, 'genre');
  const lists = await Promise.all(
    entries.map((e) => ctx.catalog(e.provider.id, e.cat.id, { genre }, opts.budget)),
  );
  return lists.flat().filter(Boolean).slice(0, opts.limit || 120);
}

/**
 * Movies and series in one genre, as one shelf. Type order is deliberate: films first, then shows, so a
 * mixed row still reads as "Action" rather than as a coin flip between the two.
 */
async function genreTitles(ctx, genre, opts = {}) {
  const kinds = opts.types || ['movie', 'series'];
  const per = Math.ceil((opts.limit || 60) / kinds.length);
  const lists = await Promise.all(kinds.map((t) => genreShelf(ctx, t, genre, { ...opts, limit: per })));
  return lists.flat().filter(Boolean).slice(0, opts.limit || 60);
}

/** A search shelf: free text to whichever addons say their catalog searches. */
async function searchShelf(ctx, type, query, opts = {}) {
  const entries = matching(await catalogsOf(ctx), type, 'search');
  const lists = await Promise.all(
    entries.map((e) => ctx.catalog(e.provider.id, e.cat.id, { search: query }, opts.budget)),
  );
  return lists.flat().filter(Boolean).slice(0, opts.limit || 60);
}

module.exports = { catalogsOf, matching, genreShelf, genreTitles, searchShelf };
