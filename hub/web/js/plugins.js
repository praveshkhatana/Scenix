/**
 * js/plugins.js — the presentation registry. One file, two maps.
 *
 * This is the web mirror of the launcher's `plugins/PluginCatalog.kt`: the hub sends a row that names a
 * presentation (`card: "poster" | "continue" | "channel" | "hero"`) and this file decides what that looks
 * like. A screen never asks "is this the Top 10 row?" — it asks the registry for a renderer and calls it.
 * A new shelf shape is one `registerRow` call, not an edit to a screen, and the hub can start sending a
 * card type we have never seen without breaking the page (the fallback is the poster rail).
 *
 * Lookup order is deliberately row-id first, card-type second: a registered row plugin may restyle one
 * particular shelf (the rank numerals) while every other `poster` row keeps the default.
 */

import { el, clear, posterImg } from '/ui/js/dom.js';
import { icon } from '/ui/js/icons.js';
import { focusable } from '/ui/js/focus.js';
import { runtime, timeLeft, episodeLabel, langList, since } from '/ui/js/format.js';
import { inMyList, isKids, session } from '/ui/js/state.js';
import { token } from '/ui/js/theme.js';

export const CARDS = new Map();
export const ROWS = new Map();

export function registerCard(name, render) { CARDS.set(name, render); return render; }
export function registerRow(name, render) { ROWS.set(name, render); return render; }

export function cardFor(row) {
  return CARDS.get(row && row.card) || CARDS.get('poster');
}

export function rowFor(row) {
  return ROWS.get(row && row.id) || ROWS.get(row && row.card) || ROWS.get('poster');
}

/* ------------------------------------------------------------------ shared bits */

/** A picture that can be missing without taking a row down with it. */
function art(img, src, alt) {
  if (img && src) img.appendChild(posterImg(src, alt));
  return img;
}

function isNew(item) {
  const added = Number(item.addedAt) || 0;
  return added && (Date.now() - added) < 7 * 86400000;
}

/* Everything a viewer uses to decide rides on the picture itself, in two corners: what is new and how
   much of it matches on the left, who it is for and how mature it is on the right. A cluster per side,
   so adding a fact never needs new geometry. */
function badges(item, art) {
  const left = [];
  const right = [];
  if (item.live) left.push(el('span', { class: 'badge live' }, 'Live'));
  else if (isNew(item)) left.push(el('span', { class: 'badge new' }, 'New'));
  if (item.match) left.push(el('span', { class: 'badge mt' }, `${item.match}%`));
  if (isKids(session.profile)) right.push(el('span', { class: 'badge kids' }, 'Kids'));
  if (item.maturity) right.push(el('span', { class: 'badge mat' }, item.maturity));
  const tier = token('cards', 'hdBadge', null);
  if (tier && Number(item.tier) === 1) right.push(el('span', { class: 'hdbadge' }, tier));
  if (left.length) art.appendChild(el('div', { class: 'bset l' }, left));
  if (right.length) art.appendChild(el('div', { class: 'bset r' }, right));
}

/** The small strip of facts under a title: match %, year, runtime, languages. */
function metaLine(item) {
  const bits = [];
  if (item.match) bits.push(el('span', { class: 'match', text: `${item.match}% match` }));
  if (item.year) bits.push(el('span', { text: String(item.year) }));
  const rt = runtime(item.durationMs);
  if (rt) bits.push(el('span', { text: rt }));
  if (item.maturity) bits.push(el('span', { class: 'matbox', text: item.maturity }));
  if (!bits.length) return null;
  return el('div', { class: 'meta' }, bits);
}

/**
 * The quick-action strip that rides on a focused card. Not focusable on purpose: D-pad order stays
 * card-to-card, matching visual order with no traps (section F), and Enter opens the detail page where
 * every action lives. The mouse gets them directly.
 */
function quickActions(item, ctx) {
  const listOn = inMyList(item.identity);
  const btn = (name, cls, label, fn) => el('button', {
    class: `r ${cls || ''}`, type: 'button', title: label, 'aria-label': label,
    onclick: (e) => { e.stopPropagation(); fn(item); },
  }, icon(name, { solid: name === 'play' }));
  return el('div', { class: 'exp' },
    btn('play', 'w', 'Play', ctx.play),
    btn(listOn ? 'check' : 'plus', '', listOn ? 'Remove from My list' : 'Add to My list', ctx.list),
    btn('info', '', 'More info', ctx.open));
}

/**
 * The one card skeleton every renderer shares. `size` names the CSS sizing class the rail already set;
 * the card itself only ever says what is inside it.
 */
function card(item, ctx, { art: artSrc, ratio, rank, progress, extra } = {}) {
  const node = el('div', {
    class: 'card',
    dataset: { identity: item.identity, i: ctx.index },
    'aria-label': item.title,
  });
  node.style.setProperty('--i', String(ctx.index || 0));
  const a = el('div', { class: 'art' });
  art(a, artSrc, item.title);
  badges(item, a);
  if (progress && progress.percent > 0) {
    const bar = el('i');
    bar.style.transform = `scaleX(${Math.max(0, Math.min(1, progress.percent))})`;
    a.appendChild(el('div', { class: 'prog' }, bar));
  }
  if (rank) a.appendChild(el('div', { class: 'num' }, el('span', { text: String(rank.rank) })));
  node.appendChild(a);
  // Nothing is printed under a shelf card: the picture is the label, and the name lives in the
  // accessible label instead of on the page.
  node.setAttribute('aria-label', item.title || '');
  if (extra) node.appendChild(extra);
  node.appendChild(quickActions(item, ctx));
  node.addEventListener('click', () => ctx.open(item));
  focusable(node);
  return node;
}

/* --------------------------------------------------------------- card plugins */

/* 16:9 landscape card — Continue Watching and channel-adjacent shelves. 320x180 at 1920 (spec B). */
registerCard('continue', (item, ctx) => {
  const p = item.progress || {};
  const total = Number(item.durationMs) || Number(p.durationMs) || 0;
  const pos = Number(p.positionMs) || 0;
  const ep = p.episode;
  const extra = el('div', { class: 'now' },
    ep ? el('span', { text: `${episodeLabel(ep.season, ep.episode)} · ${ep.title || ''}` }) : null,
    total ? el('span', { class: 'left', text: timeLeft(pos, total) }) : null);
  return card(item, ctx, {
    art: item.backdrop || item.poster,
    progress: total ? { percent: pos / total } : null,
    extra: (ep || total) ? extra : null,
  });
});

/* 2:3 poster — the default shelf shape, and the fallback for anything the hub invents later. */
registerCard('poster', (item, ctx) => card(item, ctx, { art: item.poster || item.backdrop }));

/* The billboard's own row. If a hub ever renders `hero` as a shelf it reads as a wide still, not a
   broken 1920 px card. */
registerCard('hero', (item, ctx) => card(item, ctx, { art: item.backdrop || item.poster }));

/* Live channels: numbered, logo-centred, with what is on right now. */
registerCard('channel', (item, ctx) => {
  const node = el('div', {
    class: 'card chan-card',
    dataset: { identity: item.identity, i: ctx.index },
    'aria-label': item.title,
  });
  node.style.setProperty('--i', String(ctx.index || 0));
  const a = el('div', { class: 'art' },
    el('div', { class: 'chan' },
      item.poster ? el('img', { src: item.poster, alt: '', loading: 'lazy', decoding: 'async' }) : null,
      el('div', { class: 'cname', text: item.title })),
    item.number ? el('div', { class: 'chnum', text: String(item.number) }) : null);
  art(a, item.backdrop, '');
  if (item.live && item.live.program) {
    a.appendChild(el('div', { class: 'now' }, el('span', { text: item.live.program })));
  }
  node.appendChild(a);
  node.appendChild(el('div', { class: 'cap' },
    el('div', { class: 't', text: item.title }),
    item.languages && item.languages.length ? el('div', { class: 'meta' }, el('span', { text: langList(item.languages) })) : null));
  node.addEventListener('click', () => ctx.play(item));
  focusable(node);
  return node;
});

/* --------------------------------------------------------------- row plugins */

const MAX_CARDS = 30;   // a 440-channel addon row must not become 440 DOM nodes on a 1 GB TV

/** Build the shelf frame; the cards mount when the row is actually reached (see mountCards). */
function rail(row, ctx, opts = {}) {
  const size = opts.size || 'poster';
  const head = el('h3', {},
    el('span', { class: 'rt', text: row.title || '' }),
    opts.explore === false ? null
      : el('button', {
        class: 'explore', type: 'button',
        onclick: (e) => { e.stopPropagation(); if (ctx.explore) ctx.explore(row); },
      }, 'Explore all', icon('chevRight')));
  const list = el('div', { class: `cards ${size}`, 'data-nav': 'h' });
  const node = el('div', { class: 'row', dataset: { row: row.id, i: ctx.index } }, head, list);
  node.__mount = (items) => {
    const render = cardFor(row);
    const shown = (items || []).slice(0, MAX_CARDS);
    for (let i = 0; i < shown.length; i++) {
      list.appendChild(render(shown[i], Object.assign({}, ctx, { index: i, row })));
    }
    node.__mounted = true;
    return list;
  };
  node.__items = row.items || [];
  return node;
}

/** Poster rail — 280x420 with a 28 px gap (spec B). The fallback for every unknown card type. */
registerRow('poster', (row, ctx) => rail(row, ctx, { size: 'poster' }));

/* Landscape rail — 5 cards across plus a 120 px peek of the sixth at 1920. */
registerRow('continue', (row, ctx) => rail(row, ctx, { size: 'landscape' }));

/* Channel rail: same footprint as landscape, its own sizing hook. */
registerRow('channel', (row, ctx) => rail(row, ctx, { size: 'channel' }));

/* A registered exception: this one shelf draws its rank numeral over the art (spec B: 208 px, weight
   900, black fill, 8 px grey stroke, 96 px overlap). Keyed by row id so no screen names it. */
registerRow('top10', (row, ctx) => {
  const node = rail(row, ctx, { size: 'rank', explore: false });
  const mount = node.__mount;
  node.__mount = (items) => {
    const render = cardFor(row);
    const list = node.querySelector('.cards');
    (items || []).slice(0, MAX_CARDS).forEach((item, i) => {
      const c = render(item, Object.assign({}, ctx, { index: i, row }));
      c.classList.add('rank');
      const num = el('div', { class: 'num' }, el('span', { text: String(i + 1) }));
      const a = c.querySelector('.art');
      if (a) a.parentNode.insertBefore(num, a);
      list.appendChild(c);
    });
    node.__mounted = true;
    return list;
  };
  void mount;
  return node;
});

/**
 * Mount a shelf's cards lazily. Every row on `/app/v1/home` is in the document at once for D-pad
 * geometry, but only the rows the viewer has reached get their cards, and rows scrolled far past are
 * dropped again. On a panel that has really killed a WebView renderer, that is the difference between a
 * page that survives and one that reloads itself.
 */
/* Rows are mounted as the viewer gets near them. This deliberately does not use IntersectionObserver:
   shelves below the first screenful are clipped to zero area by the page's own scroll container, an
   observer rooted at the viewport never sees them, and the page is left holding rows with nothing in
   them — which on a remote is a dead end, because there is no card there to step onto and therefore no
   scroll to bring them to life. Measuring against the scroller instead always answers. */

let scroller = null;
let queued = [];
let frame = 0;
let preloadPx = 1200;

function pump() {
  frame = 0;
  if (!scroller || !queued.length) return;
  const vb = scroller.getBoundingClientRect();
  const top = vb.top - preloadPx;
  const bottom = vb.bottom + preloadPx;
  const rest = [];
  let mounted = 0;
  for (const node of queued) {
    if (!node.isConnected || node.__mounted) continue;
    const r = node.getBoundingClientRect();
    if (r.bottom >= top && r.top <= bottom) {
      if (node.__ensure) node.__ensure();
      else node.__mount(node.__items);
      mounted++;
    } else rest.push(node);
  }
  queued = rest;
  // Mounting changes the page's height, which moves everything below it. Look again next frame.
  if (mounted && queued.length) schedule();
}

function schedule() { if (!frame) frame = requestAnimationFrame(pump); }

function detach() {
  if (!scroller) return;
  scroller.removeEventListener('scroll', schedule);
  scroller = null;
}

export function lazyMount(rowNode, { root = null, preload = '1200px' } = {}) {
  if (!rowNode || rowNode.__mounted) return;
  if (!rowNode.__ensure && !(rowNode.__items || []).length) return;
  if (!root) { if (rowNode.__ensure) rowNode.__ensure(); else rowNode.__mount(rowNode.__items); return; }
  if (root !== scroller) { detach(); scroller = root; scroller.addEventListener('scroll', schedule, { passive: true }); }
  preloadPx = Math.max(0, parseFloat(preload) || 1200);
  if (queued.indexOf(rowNode) < 0) queued.push(rowNode);
  schedule();
}

export function dropMountedRows() {
  detach();
  queued = [];
  if (frame) { cancelAnimationFrame(frame); frame = 0; }
}

/* ------------------------------------------------------------------ billboard */

/**
 * The billboard is the `hero` row drawn as one big still instead of as cards — the same registry, a
 * different renderer. Its shape lives here so the home screen only says "this row is a hero row" and then
 * rotates through what the hub put in it.
 *
 * Every line under the picture is a field the hub sent for this tile: the title's own lettering, the
 * number its addons rated it by, the place the hub gave it on its featured board, the story, and the
 * date, genre and length it carries. A tile without one of those has no line for it. A word invented on
 * the billboard is a claim about a title that nobody in the house can check.
 */

/**
 * The number the hub gave a tile, in the unit the hub said it in. `rating` is the field rows/hero.js puts
 * on a billboard tile; an object is read as { value, source }, a bare number as the value alone.
 */
function ratingOf(item) {
  const r = item.rating;
  if (r === undefined || r === null) return null;
  const value = Number(typeof r === 'object' ? r.value : r);
  if (!Number.isFinite(value) || value <= 0) return null;
  const source = (typeof r === 'object' && r.source) ? String(r.source) : 'Rating';
  return { value, source };
}

/** "Jan 2026" — the month and year the hub dated this title with, never a day it cannot know. */
function monthYear(ts) {
  const n = Number(ts);
  if (!Number.isFinite(n) || n <= 0) return '';
  try { return new Date(n).toLocaleDateString(undefined, { month: 'short', year: 'numeric' }); }
  catch (e) { return ''; }
}

/** How many seasons the hub's own episode list covers. */
function seasonCount(item) {
  if (!Array.isArray(item.episodes) || !item.episodes.length) return 0;
  const seen = new Set();
  for (const e of item.episodes) seen.add(e && e.season !== undefined ? e.season : 1);
  return seen.size;
}

export function billboard(row, ctx) {
  const max = token('hero', 'maxItems', 5);
  const items = (row.items || []).slice(0, max);
  const slot = el('div', { class: 'billboard', dataset: { row: row.id } });
  const arts = el('div', { class: 'bb-arts' });
  for (const item of items) {
    arts.appendChild(el('div', { class: 'bb-art' },
      el('img', { src: item.backdrop || item.poster || '', alt: '', decoding: 'async' }),
      el('div', { class: 'scrim' })));
  }
  const preview = el('div', { class: 'preview' }, el('div', { class: 'scrim' }));
  const text = el('div', { class: 'bb' });
  /* Rotation status, not a control: the dots say which of the hub's tiles is up and how many there are.
     They stay outside `.bb-btns` because home.js re-registers that band after every turn and treats one
     step down from it as the way onto the first shelf — a second line of small targets along the bottom
     edge would sit in that step. A mouse can still click one. */
  const dots = el('div', { class: 'bb-dots', 'aria-hidden': 'true' });
  for (let i = 0; i < items.length; i++) {
    dots.appendChild(el('button', { class: 'bb-dot', type: 'button', tabindex: '-1', onclick: () => setItem(i) }));
  }
  slot.appendChild(arts);
  slot.appendChild(preview);
  slot.appendChild(text);
  slot.appendChild(dots);

  let index = -1;
  let prevIndex = -1;
  const setItem = (i) => {
    const item = items[i];
    if (!item) return false;
    prevIndex = index;
    index = i;
    for (let k = 0; k < arts.children.length; k++) {
      arts.children[k].classList.toggle('on', k === i);
      // The title that is leaving keeps its own slide for a moment so the two never occupy the same frame.
      arts.children[k].classList.toggle('out', k === prevIndex && prevIndex !== i);
    }
    for (let k = 0; k < dots.children.length; k++) dots.children[k].classList.toggle('on', k === i);

    const on = inMyList(item.identity);
    clear(text);
    // The kicker is the sheet's first line: whose shelf this is, and what kind of thing it is holding.
    const kind = item.type === 'live' ? 'Live TV' : item.type === 'series' ? 'Series' : 'Movie';
    text.appendChild(el('div', { class: 'kicker' }, el('b', { text: 'Scenix' }), el('span', { text: kind })));
    // The title as artwork when the hub has lettering for it, and as heavy type when it does not.
    if (item.logo) text.appendChild(el('img', { class: 'logo', src: item.logo, alt: item.title }));
    else text.appendChild(el('h1', { text: item.title }));

    const rating = ratingOf(item);
    if (rating || Number(item.match) > 0) {
      const line = el('div', { class: 'bb-rating' });
      line.appendChild(el('span', { class: 'rsrc', text: rating ? rating.source : 'Match' }));
      if (rating) {
        line.appendChild(el('span', { class: 'rnum', text: rating.value.toFixed(1) }));
        line.appendChild(icon('star', { solid: true }));
      } else {
        /* A hub that has not been rebuilt answers with `match` only — the same addon signal in per-cent —
           so it is printed as a percentage instead of being dressed up as a star score. */
        line.appendChild(el('span', { class: 'rnum', text: `${item.match}%` }));
      }
      if (item.maturity) line.appendChild(el('span', { class: 'matbox', text: item.maturity }));
      else if (Number(item.minAge) > 0) line.appendChild(el('span', { class: 'matbox', text: `${item.minAge}+` }));
      text.appendChild(line);
    }

    // Where the hub put this title on its own featured board, when the hub said so.
    const board = item.board;
    if (board && Number.isFinite(Number(board.rank))) {
      text.appendChild(el('div', { class: 'trend' },
        el('b', { text: `#${board.rank}` }), el('span', { text: board.list || '' })));
    }

    if (item.overview) text.appendChild(el('div', { class: 'sub', text: item.overview }));

    /* The buttons, the hub's own date line and the queue of the hub's other tiles are one block:
       `.bb-btns` is the band the remote walks inside the billboard, and a focusable control left outside it
       opens a band of its own whose box covers the whole picture. The date and the queue are told to take
       the full width, which puts them on lines under the buttons instead of beside them. */
    const btns = el('div', { class: 'bb-btns' },
      el('button', {
        class: 'btn big red', type: 'button', onclick: () => ctx.play(item),
      }, icon('play', { solid: true }), 'Play'),
      el('button', {
        class: 'btn big ghost', type: 'button', onclick: () => { ctx.list(item); setItem(index); },
      }, icon(on ? 'check' : 'plus'), on ? 'In My List' : 'My List'),
      el('button', {
        class: 'round', type: 'button', 'aria-label': 'More info', onclick: () => ctx.open(item),
      }, icon('info')));

    const meta = [];
    meta.push(monthYear(item.releasedAt) || (item.year ? String(item.year) : ''));
    if (Array.isArray(item.genres) && item.genres[0]) meta.push(item.genres[0]);
    const seasons = seasonCount(item);
    if (seasons) meta.push(`${seasons} Season${seasons === 1 ? '' : 's'}`);
    else if (Number(item.durationMs) > 0) meta.push(runtime(item.durationMs));
    const shown = meta.filter(Boolean);
    if (shown.length) btns.appendChild(el('div', { class: 'bb-meta', text: shown.join('  ·  ') }));

    /* The tiles the hub queued behind the one that is up, in the hub's order wrapped around, each wearing
       the wide art the hub carries for it and nothing else: a name repeated under a picture of itself is a
       caption, and the line above already says it. The first is the tile on the screen — the largest, ringed
       in the brand colour — and the two behind it step down beside it, bottom aligned. `data-nav="h"` makes
       the row its own band, so one step down from the buttons is the next tile and one more step is the
       first shelf, instead of the queue being skipped over. */
    const held = document.activeElement;
    const heldAt = held && held.classList && held.classList.contains('bb-thumb') ? held.dataset.at : null;
    const queue = el('div', { class: 'bb-thumbs', 'data-nav': 'h' });
    for (let k = 0; k < Math.min(3, items.length); k++) {
      const at = (i + k) % items.length;
      const tile = el('button', {
        class: 'bb-thumb' + (k === 0 ? ' on' : ''), type: 'button',
        dataset: { at: String(at) }, 'aria-label': items[at].title,
        onclick: () => setItem(at),
      });
      // The hub's shared art helper: a tile whose picture is missing stays an empty panel rather than
      // becoming a broken-image icon in the middle of the billboard.
      art(tile, items[at].backdrop || items[at].poster, items[at].title);
      queue.appendChild(tile);
    }
    btns.appendChild(queue);
    text.appendChild(btns);

    for (const b of text.querySelectorAll('.btn, .round, .bb-thumb')) focusable(b);
    // A turn of the billboard carries the ring to the same tile in its new position, so the remote is never
    // left holding a node that has just been thrown away.
    if (heldAt !== null) {
      const again = queue.querySelector(`.bb-thumb[data-at="${heldAt}"]`);
      if (again) again.focus();
    }
    return true;
  };
  setItem(0);
  slot.__items = items;
  slot.__setItem = setItem;
  slot.__previewSlot = preview;
  slot.__index = () => index;
  return slot;
}
