'use strict';
/*
 * rows/installed.js — what your installed addons brought, as its own shelf.
 *
 * This is the row that proves the hub does not care where an item came from: it filters on the
 * `native` flag every addon carries (a property of the addon host, not a name), so a third addon
 * installed through POST /app/v1/addons lands here on the next request, and vanishes when it is
 * disabled — no restart, no code.
 */
module.exports = {
  id: 'installed',
  title: 'From your installed addons',
  card: 'poster',
  order: 45,
  scope: 'all',
  max: 30,

  async build(ctx) {
    const installed = Object.values(ctx.providers)
      .filter((p) => p.native === false)
      .map((p) => p.id);
    if (!installed.length) return [];
    const items = await ctx.library();
    return items.filter((it) => (it._addons || []).some((a) => installed.includes(a)));
  },
};
