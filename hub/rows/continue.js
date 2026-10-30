'use strict';
/*
 * rows/continue.js — "keep watching where you stopped", straight from hub/data/progress.json.
 * The hub knows nothing about what the app played except what it POSTed to /app/v1/progress.
 *
 * Resolving twenty identities one after another through every addon is how this row missed its
 * budget and disappeared from Home exactly when a viewer had the most history. A card needs metadata,
 * not streams: the catalogue the hub already read answers most of them, and the rest are asked for
 * together through ctx.metaOnly. Live-channel zaps are not "continue watching" at all.
 */
module.exports = {
  id: 'continue',
  title: 'Continue watching',
  card: 'continue',
  order: 10,
  scope: 'all',

  async build(ctx) {
    const ids = ctx.store.continueIdentities(ctx.profile.id, 20)
      .filter((id) => id && !String(id).startsWith('norm:'));
    if (!ids.length) return [];
    // The library may still be warming behind the first Home request. A short race keeps this row
    // independent of it; anything the catalogue did answer is free and already carries its art.
    const library = await Promise.race([
      ctx.library().catch(() => []),
      new Promise((resolve) => {
        const t = setTimeout(() => resolve([]), 320);
        if (t.unref) t.unref();
      }),
    ]);
    const known = new Map((library || []).map((it) => [it && it.identity, it]));
    const found = new Map();
    const missing = [];
    for (const id of ids) {
      const it = known.get(id);
      if (it) found.set(id, it);
      else missing.push(id);
    }
    // A recent title that is not in a catalogue (a local file, a just-opened detail page) still
    // deserves its tile. Metadata only, in parallel, and never one slow addon holding the row.
    const asked = missing.slice(0, 10);
    const resolved = await Promise.all(asked.map((id) =>
      Promise.resolve().then(() => ctx.metaOnly(id, null, 240)).catch(() => null)));
    asked.forEach((id, i) => {
      if (resolved[i]) found.set(id, resolved[i]);
    });
    const out = [];
    for (const id of ids) {
      const it = found.get(id);
      if (!it) continue;
      // Do not mutate the catalogue object: other rows share it. The progress bar belongs to this
      // viewer's copy of the tile.
      const p = ctx.store.progressFor(ctx.profile.id, id);
      out.push(p ? Object.assign({}, it, { progress: p }) : it);
    }
    return out;
  },
};
