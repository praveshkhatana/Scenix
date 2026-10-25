'use strict';
/*
 * rules/size_cap.js — no source bigger than the cap, whatever provider offered it.
 *
 * A 12 GB remux is a stall on this TV's Wi-Fi, so every source over the cap is dropped from
 * `sources[]`. The cap is no longer a global constant: it comes from core/prefs.js, which derives it
 * from the profile's `qualityCap` (8 GB at 1080p, 25 GB when 4K is asked for, 4 GB at 720p), so a
 * household that wants 4K raises one number instead of editing three files.
 *
 * Two spellings, in priority order:
 *   "rules": { "size-cap": { "maxBytes": 2000000000 } }   an explicit per-profile hard cap (wins)
 *   "prefs": { "qualityCap": "720p" }                     or "prefs": { "maxSizeGB": 1.5 } (derived)
 *
 * If every source is over the cap the tile stays, keeping its smallest one: a title that exists only
 * as a 4K remux should be offered with that written on it (rules/source_ranking.js pushes it to the
 * bottom of the picker and says "X GB over your 8 GB cap") rather than silently vanishing from the
 * shelf. Nothing playable at all — no sources — still goes.
 */

/** The byte ceiling for this request: explicit profile rule, else the resolved prefs. */
function capFor(ctx) {
  const r = ctx.profile && ctx.profile.rules && ctx.profile.rules['size-cap'];
  if (r && Number.isFinite(r.maxBytes)) return r.maxBytes;
  const p = ctx.prefs || {};
  if (Number.isFinite(p.maxSizeBytes)) return p.maxSizeBytes;
  if (Number.isFinite(p.maxSizeGB)) return p.maxSizeGB * 1e9;
  return (Number(process.env.SIZE_CAP_GB || 0) || 8) * 1e9; // last resort: the 1080p default
}

module.exports = {
  id: 'size-cap',
  order: 30,

  async apply(item, ctx) {
    const cap = capFor(ctx);
    const sources = item.sources || [];
    if (!sources.length) return item;
    const kept = sources.filter((s) => !(Number.isFinite(s.sizeBytes) && s.sizeBytes > cap));
    if (kept.length === sources.length) return item;
    if (!kept.length) {
      // Nothing fits. Keep the one file closest to the cap rather than dropping the title; the ranking
      // rule already marks it as over the cap and sinks it below anything that fits.
      const smallest = sources
        .slice()
        .sort((a, b) => (Number(a.sizeBytes) || 0) - (Number(b.sizeBytes) || 0))[0];
      item.sources = [smallest];
      item._overCap = Math.round((Number(smallest.sizeBytes) || 0) / 1e6); // internal, for the report
      return item;
    }
    item.sources = kept;
    return item;
  },
};
