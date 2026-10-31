/* core/games.js — the Game mode catalogue.

   Sources are static data files built by tools/build-game-catalog.js:
     data/games/ported2browser.json  AAA and console titles ported to the browser
     data/games/poki.json            the Poki web arcade
     data/games/crazygames.json      the CrazyGames web arcade

   A game is not a video title: it has no sources, no subtitles and no progress. What it carries
   instead is `playUrl` — the address a client opens full-screen — and `pad`, whether the port
   answers a game controller. The shelves are assembled here so every client (the native TV app,
   the phone) draws one shared shape: rows of items with identity "game:<id>". */
'use strict';

const fs = require('fs');
const path = require('path');

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
    pad: !!g.pad,
    kb: g.kb !== false,
    touch: g.touch !== undefined ? !!g.touch : kind !== 'p2b',
    tags: (g.tags || []).slice(0, 4),
    desc: (g.desc || '').slice(0, 200),
    source: kind,
  };
}

const dedupe = (items) => [...new Map(items.map((i) => [i.title.toLowerCase(), i])).values()];

function rows() {
  const p2b = load('ported2browser.json').map((g) => card(g, 'p2b'));
  const poki = load('poki.json').map((g) => card(g, 'poki'));
  const crazy = load('crazygames.json').map((g) => card(g, 'crazy'));
  const out = [];

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

  // The web arcades get one shelf each: the popular rotation is a different promise than the ports.
  if (crazy.length >= 4) out.push({ id: 'games-crazy', title: 'Web arcade · CrazyGames', card: 'game', items: crazy.slice(0, 30), total: crazy.length });
  if (poki.length >= 4) out.push({ id: 'games-poki', title: 'Web arcade · Poki', card: 'game', items: poki.slice(0, 30), total: poki.length });

  return out;
}

module.exports = { rows };
