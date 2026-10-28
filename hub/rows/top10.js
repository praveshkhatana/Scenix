'use strict';
/*
 * rows/top10.js — "Top 10" with no analytics at all.
 *
 * Nothing in this system counts plays, so this row is a DETERMINISTIC PSEUDO-RANK: the popularity
 * signal is the order the addons already publish. A title addon's top catalog is upstream-ranked, so
 * titles keep exactly that order; a playlist is alphabetical, so channels rank behind them (evergreen,
 * not popular). `match` carries the rank so the app can badge it. Same input => the same ten tiles,
 * restart after restart. If real play counts ever arrive, only THIS file changes.
 */
module.exports = {
  id: 'top10',
  title: 'Top 10 in this house',
  // `rank` is a card kind both clients understand: the web drawer numbers the tiles (js/plugins.js
  // registerRow('top10')), and the native BrowseActivity adapter paints the numeral when row.card is
  // 'rank'. The item order IS the rank — position is the signal, on every surface.
  card: 'rank',
  order: 20,
  scope: 'all',
  max: 10, // the hub trims to this AFTER the rules, so 10 tiles survive a filtered candidate

  async build(ctx) {
    const items = await ctx.library();
    const pool = [
      ...items.filter((it) => it.type === 'movie' || it.type === 'series'),
      ...items.filter((it) => it.type === 'channel'),
    ];
    pool.forEach((it, i) => {
      it.match = Math.max(50, 100 - i); // rank -> match, so the app can show a badge
    });
    return pool.slice(0, 30); // more candidates than the row shows, on purpose
  },
};
