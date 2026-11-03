'use strict';
/*
 * core/hooks.js — the mechanics the engagement rows share: the ones that answer to what this
 * household has actually been watching.
 *
 * A Netflix-style hook is one question asked again and again: what did this person just watch, and
 * what in this hub looks like it? recentTitles reads the hub's own progress store and answers with
 * titles that are RESOLVED — the catalogue cache first, one bounded meta ask for the rest, because a
 * history-driven row must never hang the home budget. similarTo mixes two pools: the catalogue the
 * hub already holds (free, instant, ranked by core/taste.js) and the addons' live genre shelves
 * (slower, but they reach past the first cached page). Seeds and history are excluded by identity
 * AND by title+year, because the same show published twice under two identities is one thing to a
 * viewer.
 */

const shelves = require('./catalogs');

function shortRace(promise, ms) {
  return Promise.race([
    Promise.resolve(promise).catch(() => []),
    new Promise((resolve) => {
      const t = setTimeout(() => resolve([]), ms);
      if (t.unref) t.unref();
    }),
  ]);
}

/** The titles this profile watched most recently, resolved enough to know what they are. */
async function recentTitles(ctx, n = 3) {
  const ids = (ctx.store.continueIdentities(ctx.profile.id, 12) || [])
    .filter((id) => id && !String(id).startsWith('norm:')); // zapping live TV is not a taste
  if (!ids.length) return [];
  const library = await shortRace(ctx.library(), 400);
  const byId = new Map();
  for (const it of library || []) if (it && it.identity) byId.set(it.identity, it);
  const out = [];
  const missing = [];
  for (const id of ids) {
    const it = byId.get(id);
    if (it && it.title) {
      out.push(it);
      if (out.length >= n) break;
    } else if (missing.length < 3) missing.push(id);
  }
  // A title watched long ago is not in the catalogue cache any more: ONE bounded meta ask each.
  // A history-driven row must never pay the home budget for the whole world.
  if (missing.length && out.length < n) {
    const got = await Promise.all(
      missing.map((id) => ctx.metaOnly(id, undefined, 500).catch(() => null)),
    );
    for (const g of got) if (g && g.title && out.length < n) out.push(g);
  }
  return out;
}

function titleKey(it) {
  return String((it && it.title) || '').trim().toLowerCase() + '|' + ((it && it.year) || '');
}

/**
 * What this hub has that looks like `seeds`: cached catalogue plus the addons' live genre shelves,
 * scored by shared genres (the only signal that means RELATED, not merely GOOD), then type, then
 * this profile's own taste score, then rating. Seeds and their twin identities never come back.
 */
async function similarTo(ctx, seeds, opts = {}) {
  const limit = opts.limit || 60;
  const seedList = (Array.isArray(seeds) ? seeds : [seeds]).filter((s) => s && s.title);
  if (!seedList.length) return [];
  const lower = new Set();
  const ask = [];
  const kinds = new Set();
  const skip = new Set();
  for (const s of seedList) {
    for (const g of s.genres || []) {
      const name = String(g || '').trim();
      if (!name) continue;
      if (!lower.has(name.toLowerCase())) {
        lower.add(name.toLowerCase());
        if (ask.length < 2) ask.push(name);
      }
    }
    if (s.type && s.type !== 'channel') kinds.add(s.type);
    if (s.identity) skip.add(String(s.identity));
    skip.add('t:' + titleKey(s));
  }
  const seen = new Map();
  const pool = [];
  const take = (list) => {
    for (const it of list || []) {
      if (!it || !it.identity || !it.title || it.type === 'channel') continue;
      if (skip.has(String(it.identity)) || skip.has('t:' + titleKey(it))) continue;
      if (seen.has(it.identity)) continue;
      seen.set(it.identity, it);
      pool.push(it);
    }
  };
  take(await shortRace(ctx.library(), 500));
  // The live genre shelves reach past the cached first pages. Bounded, in parallel, cache-cheap.
  const live = await Promise.all(ask.map((g) =>
    shelves.genreTitles(ctx, g, { limit: Math.max(20, Math.ceil(limit / 2)), budget: 900 })
      .catch(() => [])));
  for (const list of live) take(list);
  const score = (it) => {
    let s = 0;
    let shared = 0;
    for (const g of it.genres || []) {
      if (lower.has(String(g || '').toLowerCase())) shared++;
    }
    if (!shared) return -1;
    s += shared * 2;
    if (kinds.has(it.type)) s += 1;
    s += (Math.max(0, Math.min(100, Number(it.match) || 0)) / 100) * 3;
    const r = it.rating && Number(it.rating.value);
    if (r) s += r / 10;
    return s;
  };
  return pool
    .map((it) => ({ it, s: score(it) }))
    .filter((x) => x.s >= 0)
    .sort((a, b) => b.s - a.s)
    .slice(0, limit)
    .map((x) => x.it);
}

module.exports = { recentTitles, similarTo, titleKey };
