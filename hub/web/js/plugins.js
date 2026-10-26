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

function badges(item, art) {
  if (item.live) art.appendChild(el('span', { class: 'badge live' }, 'Live'));
  else if (isNew(item)) art.appendChild(el('span', { class: 'badge new' }, 'New'));
  if (isKids(session.profile)) art.appendChild(el('span', { class: 'badge kids right' }, 'Kids'));
  const tier = token('cards', 'hdBadge', null);
  if (tier && Number(item.tier) === 1) art.appendChild(el('span', { class: 'hdbadge' }, tier));
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
  const cap = el('div', { class: 'cap' }, el('div', { class: 't', text: item.title }), metaLine(item));
  node.appendChild(cap);
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
export function lazyMount(rowNode, { root = null, preload = '1200px', keep = 4 } = {}) {
  const items = rowNode.__items || [];
  if (!items.length || rowNode.__mounted) return;
  if (!('IntersectionObserver' in window)) { rowNode.__mount(items); return; }
  if (!lazyMount.__obs) lazyMount.__obs = new IntersectionObserver((entries) => {
    for (const e of entries) {
      if (e.isIntersecting && !e.target.__mounted) e.target.__mount(e.target.__items);
    }
  }, { root, rootMargin: `${preload} 0px ${preload} 0px` });
  lazyMount.__obs.observe(rowNode);
  void keep;
}

export function dropMountedRows() {
  if (lazyMount.__obs) lazyMount.__obs.disconnect();
  lazyMount.__obs = null;
}

/* ------------------------------------------------------------------ billboard */

/**
 * The billboard is the `hero` row drawn as one big still instead of as cards — the same registry, a
 * different renderer. Its shape lives here so the home screen only says "this row is a hero row" and then
 * rotates through what the hub put in it.
 */
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
  slot.appendChild(arts);
  slot.appendChild(preview);
  slot.appendChild(text);

  let index = -1;
  const setItem = (i) => {
    const item = items[i];
    if (!item) return false;
    index = i;
    for (let k = 0; k < arts.children.length; k++) arts.children[k].classList.toggle('on', k === i);

    const on = inMyList(item.identity);
    const rt = runtime(item.durationMs);
    const facts = [];
    if (item.match) facts.push(el('span', { class: 'match', text: `${item.match}% match` }));
    if (item.year) facts.push(el('span', { text: String(item.year) }));
    if (item.maturity) facts.push(el('span', { class: 'matbox', text: item.maturity }));
    if (rt) facts.push(el('span', { text: rt }));
    if (item.languages && item.languages.length) facts.push(el('span', { text: langList(item.languages) }));
    if (item.type === 'series' && item.episodes && item.episodes.length) {
      facts.push(el('span', { text: `${item.episodes.length} episodes` }));
    }

    const why = (item.why && item.why[0]) || (item.sources && item.sources[0] && item.sources[0].why && item.sources[0].why[0]);
    clear(text);
    text.appendChild(el('div', { class: 'mark', text: 'Scenix' }));
    if (item.logo) {
      text.appendChild(el('img', { class: 'logo', src: item.logo, alt: item.title }));
    } else {
      text.appendChild(el('h1', { text: item.title }));
    }
    if (item.tagline) text.appendChild(el('div', { class: 'tagline', text: item.tagline }));
    if (item.overview) text.appendChild(el('div', { class: 'sub', text: item.overview }));
    if (facts.length) text.appendChild(el('div', { class: 'mat' }, facts));
    if (why) text.appendChild(el('div', { class: 'why', text: why }));
    text.appendChild(el('div', { class: 'bb-btns' },
      el('button', {
        class: 'btn big white', type: 'button', onclick: () => ctx.play(item),
      }, icon('play', { solid: true }), 'Play'),
      el('button', {
        class: 'btn big ghost', type: 'button', onclick: () => { ctx.list(item); setItem(index); },
      }, icon(on ? 'check' : 'plus'), on ? 'In My List' : 'My List'),
      el('button', {
        class: 'round', type: 'button', 'aria-label': 'More info', onclick: () => ctx.open(item),
      }, icon('info'))));
    for (const b of text.querySelectorAll('.btn, .round')) focusable(b);
    return true;
  };
  setItem(0);
  slot.__items = items;
  slot.__setItem = setItem;
  slot.__previewSlot = preview;
  slot.__index = () => index;
  return slot;
}
