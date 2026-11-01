/* core/games.js — the Game mode catalogue.

   Two sources ship built-in, as static data files built by tools/build-game-catalog.js:
     data/games/ported2browser.json  AAA and console titles ported to the browser
     data/games/crazygames.json      the CrazyGames web arcade
   (poki.json exists on disk but is not shelved — see builtinRows for why.)
   Plus data/games/curated.json — the owner's highlighted titles, kept by hand and shown first.
   They are seeds, not a closed list: any add-on the household installs may carry games too. An
   installed external add-on is asked for `<base>/games.json`, which answers in exactly the shape
   this endpoint returns — `{ rows: [ { title, items: [ { id,title,poster,playUrl,pad,… } ] } ] }` —
   and its shelves land after the built-ins, named after the add-on. A dead or game-less add-on
   costs one cached miss, never a hung request.

   A game is not a video title: it has no sources, no subtitles and no progress. What it carries
   instead is `playUrl` — the address a client opens full-screen — `fallbackUrl` for when that
   first host refuses this region, and `pad`, whether the port answers a game controller. The
   shelves are assembled here so every client (the native TV app, the phone) draws one shared
   shape: rows of items with identity "game:<id>". */
'use strict';

const fs = require('fs');
const http = require('http');
const https = require('https');
const path = require('path');

const addons = require('./addons');

const DATA_DIR = path.join(__dirname, '..', 'data', 'games');
const cache = new Map(); // file -> { mtime, rows }

function load(name) {
  const file = path.join(DATA_DIR, name);
  let mtime = 0;
  try { mtime = fs.statSync(file).mtimeMs; } catch (e) { return []; }
  const hit = cache.get(name);
  if (hit && hit.mtime === mtime) return hit.rows;
  let rows = [];
  try {
    rows = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (e) {
    console.error('GAMES', 'cannot read', name, e.message);
    rows = [];
  }
  cache.set(name, { mtime, rows });
  return rows;
}

/* One catalog entry becomes one card. The identity is stable across refreshes so a hub that
   re-scrapes never strands a shortcut someone kept on their launcher. */
function card(g, kind) {
  return {
    identity: 'game:' + g.id,
    type: 'game',
    title: g.title,
    poster: g.cover || g.poster || '',
    backdrop: g.cover || g.poster || '',
    playUrl: g.playUrl,
    fallbackUrl: g.fallbackUrl || '',
    // Ports whose servers insist on cross-origin isolation are multithreaded builds; Android's
    // WebView never grants that, so the player must know to say so instead of showing black.
    coi: !!g.coi,
    pad: !!g.pad,
    kb: g.kb !== false,
    touch: g.touch !== undefined ? !!g.touch : kind !== 'p2b',
    // "desktop" marks a host that answers a browser-identity check before it serves the game at
    // all (the GameDistribution family returns 404 to a mobile UA). The player swaps its identity.
    ua: g.ua || '',
    tags: (g.tags || []).slice(0, 4),
    desc: (g.desc || '').slice(0, 200),
    source: kind,
  };
}

const dedupe = (items) => [...new Map(items.map((i) => [i.title.toLowerCase(), i])).values()];

async function builtinRows() {
  const p2b = load('ported2browser.json').map((g) => card(g, 'p2b'));
  const crazy = load('crazygames.json').map((g) => card(g, 'crazy'));
  const out = [];

  // The household's highlighted games sit above everything: hand-picked by the owner
  // (curated.json), shown once however they arrive.
  const curated = load('curated.json').map((g) => card(g, 'curated'));
  if (curated.length) out.push({ id: 'games-highlight', title: 'Highlighted', card: 'game', items: curated });

  // The marquee shelf: the biggest ports that answer a controller, fighting/racing/action first —
  // the genres where a gamepad is the whole point.
  const padReady = p2b.filter((g) => g.pad);
  const marqueeTags = ['fighting', 'racing', 'sports', 'action', 'shooter'];
  // A title with none of these ranks last, not first: findIndex answers -1, which reads as "top".
  const tagRank = (g) => {
    const at = marqueeTags.findIndex((t) => g.tags.some((x) => x.toLowerCase() === t));
    return at < 0 ? marqueeTags.length : at;
  };
  // Genre alone still leaves the shelf alphabetical, so the first tiles read as deep cuts instead of
  // the big names a viewer recognizes from across the room. One known-franchise pass breaks ties.
  const famous = /tekken|soulcalibur|mortal kombat|need for speed|fifa|grand theft|gta|mario|zelda|pok(e|é)mon|resident evil|silent hill|pro evolution|virtua|guitar hero|wwe|dragon ball|dbz|metal slug|crash|sonic|halo|star wars|f na[f]|angry birds/i;
  const marquee = dedupe(padReady)
    .sort((a, b) => tagRank(a) - tagRank(b) || (famous.test(b.title) ? 1 : 0) - (famous.test(a.title) ? 1 : 0))
    .slice(0, 14);
  if (marquee.length) out.push({ id: 'games-featured', title: 'Play now with a controller', card: 'game', items: marquee });

  const shelf = (id, title, pool, n = 30) => {
    const items = dedupe(pool).slice(0, n);
    if (items.length >= 6) out.push({ id, title, card: 'game', items, total: dedupe(pool).length });
  };

  shelf('games-fighting', 'Fighting', padReady.filter((g) => g.tags.some((t) => /fight/i.test(t))));
  shelf('games-racing', 'Racing & sports', p2b.filter((g) => g.tags.some((t) => /racing|sport/i.test(t))));
  shelf('games-classics', 'Console & PC classics', p2b.filter((g) => g.tags.some((t) => /platform|adventure|rpg|indie/i.test(t))));
  shelf('games-retro', 'Retro & arcade', p2b.filter((g) => g.tags.some((t) => /retro|arcade|puzzle/i.test(t))));
  shelf('games-everything', 'Every port', [...p2b]);

  // The web arcade gets one shelf: the popular rotation is a different promise than the ports.
  // (Poki is not shelved: its SDK probes WebGL VENDOR/RENDERER and silently refuses the emulated
  // GPUs of TV boxes before it ever inserts a game iframe — a blank screen we cannot fix from
  // outside. Subway Surfers, the one title people ask for, ships in curated.json via a
  // GameDistribution embed instead.)
  if (crazy.length >= 4) out.push({ id: 'games-crazy', title: 'Web arcade · CrazyGames', card: 'game', items: crazy.slice(0, 30), total: crazy.length });

  return out;
}

/* ── Game-carrying addons ──────────────────────────────────────────────────────────────────────
   An installed external addon is asked once per cache window for `<base>/games.json`. Nothing
   about this is special-cased per addon: the built-ins above could themselves be addons, and a
   household adding a new source changes this endpoint without a hub edit or restart. */

const ADDON_OK_MS = 6 * 60 * 60 * 1000;   // an addon that answered games keeps them fresh this long
const ADDON_MISS_MS = 10 * 60 * 1000;     // one that did not gets asked again after ten minutes
const ADDON_FETCH_MS = Number(process.env.GAMES_ADDON_TIMEOUT_MS || 6000);
const ADDON_MAX_BYTES = 4e6;
const addonCache = new Map(); // addon id -> { at, rows }

function fetchJson(url, ms) {
  return new Promise((resolve) => {
    let done = false;
    const finish = (v) => { if (!done) { done = true; clearTimeout(timer); resolve(v); } };
    const timer = setTimeout(() => { try { req.destroy(); } catch (e) {} finish(null); }, ms);
    let req;
    try {
      const lib = /^https:/i.test(url) ? https : http;
      req = lib.get(url, { headers: { 'user-agent': 'ScenixHub/1.0', accept: 'application/json' }, timeout: ms }, (res) => {
        if (res.statusCode !== 200) { res.resume(); return finish(null); }
        let body = '';
        let size = 0;
        res.setEncoding('utf8');
        res.on('data', (d) => { size += d.length; if (size > ADDON_MAX_BYTES) { req.destroy(); return finish(null); } body += d; });
        res.on('end', () => { try { finish(JSON.parse(body)); } catch (e) { finish(null); } });
      });
      req.on('error', () => finish(null));
      req.on('timeout', () => { req.destroy(); finish(null); });
    } catch (e) { finish(null); }
  });
}

/* An addon's item becomes a card on the same wire shape the built-ins use. The identity is
   namespaced by addon id so two sources offering the same title never merge into one tile. */
function addonCard(g, aid) {
  if (!g || !g.id || !g.title || !/^https?:\/\//i.test(String(g.playUrl || ''))) return null;
  return {
    identity: 'game:' + aid + ':' + g.id,
    type: 'game',
    title: String(g.title).slice(0, 120),
    poster: g.poster || g.cover || '',
    backdrop: g.backdrop || g.poster || g.cover || '',
    playUrl: String(g.playUrl),
    fallbackUrl: /^https?:\/\//i.test(String(g.fallbackUrl || '')) ? String(g.fallbackUrl) : '',
    coi: !!g.coi,
    pad: !!g.pad,
    kb: g.kb !== false,
    touch: !!g.touch,
    ua: g.ua || '',
    tags: (g.tags || []).slice(0, 4),
    desc: (g.desc || '').slice(0, 200),
    source: 'addon:' + aid,
  };
}

async function addonShelves(addonList) {
  const out = [];
  await Promise.all(addonList.map(async (a) => {
    const hit = addonCache.get(a.id);
    const ttl = hit && hit.rows ? ADDON_OK_MS : ADDON_MISS_MS;
    if (hit && Date.now() - hit.at < ttl) {
      if (hit.rows) out.push(...hit.rows);
      return;
    }
    // The community protocol carries the addon's own configuration in its URL path, so the base —
    // not the origin — is where its games.json lives.
    const base = String(a.url || '').replace(/\/manifest\.json\/?$/, '');
    const body = base ? await fetchJson(base + '/games.json', ADDON_FETCH_MS) : null;
    let shelves = null;
    if (body && Array.isArray(body.rows)) {
      shelves = [];
      for (let i = 0; i < body.rows.length && shelves.length < 8; i++) {
        const r = body.rows[i] || {};
        const items = ((r.items || []).map((g) => addonCard(g, a.id)).filter(Boolean));
        if (items.length >= 3) {
          shelves.push({
            id: 'ga-' + a.id + '-' + (r.id || i),
            title: String(r.title || a.name || 'Games'),
            card: 'game',
            items: items.slice(0, 60),
            total: items.length,
          });
        }
      }
    }
    addonCache.set(a.id, { at: Date.now(), rows: shelves });
    if (shelves) out.push(...shelves);
  }));
  return out;
}

async function rows() {
  const built = await builtinRows();
  let carriers = [];
  try {
    carriers = (await addons.list())
      .filter((a) => !a.native && a.enabled !== false && /https?:\/\//i.test(String(a.url || '')));
  } catch (e) { /* the addon host is having a moment; built-ins still answer */ }
  let extra = [];
  try { extra = await addonShelves(carriers); } catch (e) { /* same */ }
  // One shelf per addon at most would starve a rich source; title-level dedupe across sources
  // keeps the featured row honest while each add-on still owns its own rail.
  return built.concat(extra);
}

module.exports = { rows };
