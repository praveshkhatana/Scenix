'use strict';

const settings = require('../core/settings');
/*
 * rows/addon_catalogs.js — the catalogues the owner's own addons brought.
 *
 * The "installed" row is a teaser: thirty titles mixed from every installed addon. A household that
 * added DesiFlix wants DesiFlix's own shelves, not three of its films among another addon's. This row
 * turns each installed addon catalog into a tile; opening one asks /app/v1/addon-catalog for that
 * catalogue, through the same profile rules as every other row.
 */
module.exports = {
  id: 'addon-catalogs',
  title: 'Browse addons',
  card: 'genre',
  order: 46,
  scope: 'all',
  max: 18,

  async build(ctx) {
    const out = [];
    const overrides = settings.get().addonCatalogNames || {};
    const providers = Object.values(ctx.providers || {})
      // Only addons the household installed. Native rows already have a home of their own, and a
      // channel playlist is Live TV's job, not a film shelf.
      .filter((p) => p && p.native === false && p.enabled !== false && p.kind !== 'channel')
      .sort((a, b) => (Number(b.addedAt) || 0) - (Number(a.addedAt) || 0));
    for (const p of providers) {
      if (typeof p.catalogs !== 'function') continue;
      let cats = [];
      try {
        cats = await p.catalogs();
      } catch (e) {
        continue;
      }
      for (const c of cats || []) {
        if (!c || !c.id) continue;
        let name = String(c.name || c.id);
        // DesiFlix names its shelves "Movies - DesiFlix". The tile already says "DesiFlix"; strip
        // the repeated suffix so the label is "DesiFlix · Movies".
        if (p.name) {
          const escaped = String(p.name).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
          name = name.replace(new RegExp('\\s*[-–—]\\s*' + escaped + '\\s*$', 'i'), '').trim();
        }
        const key = p.id + ':' + c.id;
        const custom = typeof overrides[key] === 'string' ? overrides[key].trim() : '';
        out.push({
          identity: 'catalog:' + p.id + ':' + c.id,
          type: 'genre',
          title: custom || [p.name || p.id, name].filter(Boolean).join(' · '),
          poster: null,
          backdrop: null,
          logo: null,
          genres: [p.name || p.id],
          addedAt: null,
          releasedAt: null,
          inCinemas: false,
          live: null,
        });
      }
    }
    return out;
  },
};
