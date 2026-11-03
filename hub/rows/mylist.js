'use strict';
/*
 * rows/mylist.js — the shelf a person stocked themselves.
 *
 * Every service has this row because the catalogue is not the point: the household's *intent* is.
 * The list lives on the hub (core/store.js, data/list.json) so the list you build on the living
 * room TV is the list the bedroom TV and the browser show — a per-screen list is a per-screen lie.
 *
 * Resolution copies rows/continue.js exactly: a title from a catalogue is free, an unknown one gets
 * one budgeted metadata ask, and one slow addon may not delete the row.
 */
module.exports = {
  id: 'mylist',
  title: 'My List',
  card: 'poster',
  order: 12,
  scope: 'all',

  async build(ctx) {
    const ids = ctx.store.myListIds(ctx.profile.id, 60);
    if (!ids.length) return [];
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
    const asked = missing.slice(0, 12);
    const resolved = await Promise.all(asked.map((id) =>
      Promise.resolve().then(() => ctx.metaOnly(id, null, 300)).catch(() => null)));
    asked.forEach((id, i) => {
      if (resolved[i]) found.set(id, resolved[i]);
    });
    const out = [];
    for (const id of ids) {
      const it = found.get(id);
      if (!it) continue;
      // Do not mutate the catalogue object: rows share it. The saved mark belongs to this copy.
      out.push(Object.assign({}, it, { inList: true }));
    }
    return out;
  },
};
