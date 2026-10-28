/**
 * js/focus.js — one focus system for the remote and the mouse.
 *
 * On a TV the only pointer is the D-pad; on a laptop it is the mouse. They must be the *same* state:
 * hovering a card focuses it exactly as arrowing to it does, and the focused card is the one that scales
 * up, takes the white ring, lifts above its neighbours and starts a trailer preview. There is no
 * "hover style" separate from a "focus style" anywhere in the CSS because there is only one thing.
 *
 * Navigation is geometric rather than hand-wired, and that is what stops focus getting stuck: rows have
 * different lengths, cards scroll sideways, and a screen may have a panel open on top. Left/right step
 * inside the group the card belongs to (so a row scrolls as you walk it); up/down look at the real
 * rectangles on the screen and pick the nearest thing above or below, whatever row it happens to belong
 * to. A panel or modal pushes a scope, and then only what is inside it can be reached.
 */

import { prefersReducedMotion } from '/ui/js/dom.js';

const ATTR = 'data-f';

let current = null;
const scopes = [];            // elements: while one is pushed, only its subtree is navigable
const listeners = [];         // focus observers (the trailer preview uses this)

export function focusable(node) {
  node.setAttribute(ATTR, '1');
  if (!node.hasAttribute('tabindex')) node.setAttribute('tabindex', '-1');
  return node;
}

export function isFocusable(node) { return node && node.hasAttribute && node.hasAttribute(ATTR); }

export function getFocus() { return current; }

/*
 * One layout read per focus move.
 *
 * A D-pad step used to call getBoundingClientRect() and getComputedStyle() once per focusable, several
 * times over (group members, geometric fallback, band walk). On this panel that is four or five forced
 * style/layout passes per key. The cache below is alive only for the length of one move: rectangles are
 * read once, reused by every strategy, and thrown away before anything can change.
 */
let moveCache = null;

function beginMove() { moveCache = { pool: null, rects: new Map(), styles: new Map(), bands: new Map() }; }
function endMove() { moveCache = null; }

function rectOf(node) {
  if (moveCache) {
    const held = moveCache.rects.get(node);
    if (held) return held;
    const r = node.getBoundingClientRect();
    moveCache.rects.set(node, r);
    return r;
  }
  return node.getBoundingClientRect();
}

function styleOf(node) {
  if (moveCache) {
    const held = moveCache.styles.get(node);
    if (held) return held;
    const st = window.getComputedStyle(node);
    moveCache.styles.set(node, st);
    return st;
  }
  return window.getComputedStyle(node);
}

function visible(node) {
  if (!node || !node.isConnected) return false;
  if (node.getAttribute('aria-hidden') === 'true') return false;
  const r = rectOf(node);
  if (r.width < 2 || r.height < 2) return false;
  const st = styleOf(node);
  // Opacity is deliberately not a visibility test. The player's chrome fades in on the same keypress that
  // asks the ring to enter it; rejecting an element mid-transition made the controls unreachable.
  if (st.visibility === 'hidden' || st.display === 'none') return false;
  return true;
}

function root() { return scopes.length ? scopes[scopes.length - 1] : document.body; }

export function candidates() {
  if (moveCache && moveCache.pool) return moveCache.pool;
  const pool = Array.from(root().querySelectorAll(`[${ATTR}]`)).filter(visible);
  if (moveCache) moveCache.pool = pool;
  return pool;
}

export function onFocus(cb) { listeners.push(cb); return () => { const i = listeners.indexOf(cb); if (i >= 0) listeners.splice(i, 1); }; }

export function setFocus(node, { scroll = true, source = 'key' } = {}) {
  if (!node || node === current) return false;
  if (current) current.classList.remove('foc');
  current = node;
  node.classList.add('foc');
  try { node.focus({ preventScroll: true }); } catch (e) { /* older engines: class + ring still work */ }
  if (scroll) scrollFor(node);
  for (const cb of listeners) { try { cb(node, source); } catch (e) { console.error('[scenix] focus listener', e); } }
  return true;
}

export function blurFocus() {
  if (current) current.classList.remove('foc');
  current = null;
}

/**
 * Where the page should sit when the ring lands on `node`.
 *
 * A hero is not a card: scrolling just enough to show its Play button leaves the title above the top
 * edge, which is exactly the "I can see the banner but not the film" state on a detail page. Entering a
 * billboard or a detail hero goes to the top of the screen, so the name and the button are one picture.
 */
function scrollFor(node) {
  if (node.closest && node.closest('.nav')) return;
  if (node.closest && node.closest('.billboard, .detail-hero')) {
    const screen = node.closest('.screen');
    if (screen) { screen.scrollTop = 0; return; }
  }
  scrollTo(node);
}

function scrollTo(node) {
  /* Instant, and only the two scrollers that actually need to move. `scrollIntoView({block,inline})`
     walks every scrollable ancestor and lets the engine choose a scroll anchoring point; on this panel
     that produced a layout/anchor pass after every key. The shelf scrolls horizontally, the screen
     vertically, and a card already inside both is left alone. */
  const row = node.closest ? node.closest('.cards') : null;
  if (row && row.scrollWidth > row.clientWidth + 1) {
    const view = row.getBoundingClientRect();
    const card = node.getBoundingClientRect();
    const pad = 24;
    if (card.left < view.left + pad) row.scrollLeft -= (view.left + pad - card.left);
    else if (card.right > view.right - pad) row.scrollLeft += (card.right - (view.right - pad));
  }
  const screen = node.closest ? node.closest('.screen') : null;
  if (screen && screen.scrollHeight > screen.clientHeight + 1) {
    const view = screen.getBoundingClientRect();
    const card = node.getBoundingClientRect();
    const top = view.top + 96;       // keep the ring out from under the fixed nav
    const bottom = view.bottom - 24;
    if (card.top < top) screen.scrollTop -= (top - card.top);
    else if (card.bottom > bottom) screen.scrollTop += (card.bottom - bottom);
  }
}

/* ---- group navigation: walking a row or a grid by index ---- */

function groupOf(node) { return node.closest('[data-nav]'); }
function groupMembers(group, node, pool) {
  // Read the move's one candidate snapshot rather than running a second subtree query. The order is the
  // page's own document order either way, and `visible` has already paid for every rect/style read.
  const kids = (pool || candidates()).filter((n) => group.contains(n));
  return kids.includes(node) ? kids : null;
}

/* ---- geometric navigation: what is really above or below on this screen ---- */

function geometric(from, dir, pool) {
  const a = rectOf(from);
  const acx = a.left + a.width / 2;
  const acy = a.top + a.height / 2;
  let best = null;
  let bestScore = Infinity;
  for (const cand of (pool || candidates())) {
    if (cand === from) continue;
    const b = rectOf(cand);
    const bcx = b.left + b.width / 2;
    const bcy = b.top + b.height / 2;
    let along, across;
    if (dir === 'right') { along = b.left - a.right; across = Math.abs(bcy - acy); if (bcx <= acx + 2) continue; }
    else if (dir === 'left') { along = a.left - b.right; across = Math.abs(bcy - acy); if (bcx >= acx - 2) continue; }
    else if (dir === 'down') { along = b.top - a.bottom; across = Math.abs(bcx - acx); if (bcy <= acy + 2) continue; }
    else { along = a.top - b.bottom; across = Math.abs(bcx - acx); if (bcy >= acy - 2) continue; }
    if (along < -2) continue;                       // not actually in that direction
    // Straight ahead wins; a candidate slightly off-axis is fine, one far off-axis is a different row.
    const score = Math.max(0, along) + across * (dir === 'up' || dir === 'down' ? 0.9 : 1.6);
    if (score < bestScore) { bestScore = score; best = cand; }
  }
  return best;
}

/* ---- bands: a shelf, the billboard's buttons and the menu bar are one unit each ----
   Pure geometry compares card centres. A card that grows — a moving preview, a focused poster — moves
   its own centre and swallows the centres of the shelves above and below it, and the ring stops moving.
   So vertical steps walk bands in layout order and land in the same column the viewer was in. */

const BAND_SEL = '.nav, .bb-btns, .billboard, .detail-hero, .mbox, .sheet, .firstrun, .keys';

function bandOf(node) {
  if (moveCache && moveCache.bands.has(node)) return moveCache.bands.get(node);
  let n = node;
  let found = null;
  while (n && n.tagName && n.tagName !== 'BODY') {
    if (n.hasAttribute && n.hasAttribute('data-nav')) { found = n; break; }
    if (n.matches && n.matches(BAND_SEL)) { found = n; break; }
    n = n.parentElement;
  }
  if (moveCache) moveCache.bands.set(node, found);
  return found;
}

function bands(pool) {
  const map = new Map();
  for (const c of (pool || candidates())) {
    const b = bandOf(c);
    if (!b) continue;
    let e = map.get(b);
    if (!e) { e = { el: b, rect: rectOf(b), items: [] }; map.set(b, e); }
    e.items.push(c);
  }
  const out = Array.from(map.values());
  // Document order, not pixel order: the menu bar is fixed, so its pixels sit above whatever the page
  // has scrolled past, while in the document it is honestly the first thing on the page.
  out.sort((p, q) => (p.el.compareDocumentPosition(q.el) & 4) ? -1 : 1);
  return out;
}

function bandStep(from, dir, pool) {
  const mine = bandOf(from);
  if (!mine) return null;
  const list = bands(pool);
  let idx = -1;
  for (let i = 0; i < list.length; i++) if (list[i].el === mine) { idx = i; break; }
  if (idx < 0) return null;
  const a = rectOf(from);
  const ax = a.left + a.width / 2;
  const rest = dir === 'down' ? list.slice(idx + 1) : list.slice(0, idx).reverse();
  for (const b of rest) {
    if (!b.items.length) continue;
    // Coming up from a shelf into the billboard, the right answer is the Play button, not whichever
    // thumbnail happens to be under the same column. The queue is still reachable with one more step.
    if (b.el.classList && b.el.classList.contains('billboard')) {
      const play = b.el.querySelector('.bb-btns .btn, .bb-btns .round');
      if (play) return play;
    }
    // Same for a detail page: entering the hero from a shelf should land on Play/Resume.
    if (b.el.classList && b.el.classList.contains('detail-hero')) {
      const play = b.el.querySelector('.detail-actions .btn');
      if (play) return play;
    }
    let best = b.items[0];
    let bestD = Infinity;
    for (const c of b.items) {
      const r = rectOf(c);
      const d = Math.abs(r.left + r.width / 2 - ax);
      if (d < bestD) { bestD = d; best = c; }
    }
    return best;
  }
  return null;
}

export function move(dir) {
  beginMove();
  try {
    return moveWithin(dir);
  } finally {
    endMove();
  }
}

function moveWithin(dir) {
  if (!current || !visible(current)) {
    const first = candidates()[0];
    if (first) setFocus(first);
    return !!first;
  }
  // One candidate snapshot for the whole move. Group walking, band walking and the geometric fallback
  // all read this same array: the DOM is not queried a second time, and two strategies cannot disagree
  // about which cards are on the screen.
  const pool = candidates();
  const group = groupOf(current);
  let next = null;

  if (group) {
    const kind = group.getAttribute('data-nav');
    const members = groupMembers(group, current, pool);
    if (members && members.length > 1) {
      const i = members.indexOf(current);
      if (kind === 'h' && dir === 'right' && i < members.length - 1) next = members[i + 1];
      else if (kind === 'h' && dir === 'left' && i > 0) next = members[i - 1];
      else if (kind === 'v' && dir === 'down' && i < members.length - 1) next = members[i + 1];
      else if (kind === 'v' && dir === 'up' && i > 0) next = members[i - 1];
      else if (kind === 'grid' && (dir === 'right' || dir === 'left')) {
        const j = dir === 'right' ? i + 1 : i - 1;
        if (j >= 0 && j < members.length) next = members[j];
      }
    }
  }
  if (!next) {
    if (dir === 'up' || dir === 'down') {
      next = bandStep(current, dir, pool);
      // A band's neighbour is the only honest answer for leaving it: pure geometry would happily walk
      // back into a card that is merely scrolled behind the fixed menu bar. But a band is often taller
      // than the screen - an avatar picker with three rails in it - and then the way on is still down,
      // and refusing it strands the ring on the last row the viewer can see. So geometry gets the last
      // word, restricted to the band we are already standing in: the menu bar stays out of the running,
      // and a grid still reaches its own next row and the rails beneath it.
      if (!next) {
        const band = bandOf(current);
        next = geometric(current, dir, band ? pool.filter((n) => band.contains(n)) : pool);
      }
    } else next = geometric(current, dir, pool);
  }
  if (!next) return false;
  setFocus(next);
  return true;
}

export function activate() {
  if (!current) return false;
  current.click();
  return true;
}

export function pushScope(node) { scopes.push(node); }
export function popScope() {
  scopes.pop();
  // The element that had the ring may have been inside the scope that just closed.
  if (current && !current.isConnected) current = null;
  if (current && !root().contains(current)) current = null;
}
export function resetScopes() { scopes.length = 0; }
export function scopeDepth() { return scopes.length; }

/**
 * Put the ring on the first sensible thing on a new screen: whatever the screen marks as its default, or
 * the first visible focusable. Called by every screen after it renders.
 */
export function focusDefault(preferred) {
  const target = preferred && preferred.isConnected ? preferred : candidates()[0];
  return target ? setFocus(target, { source: 'screen' }) : false;
}

/* ---- the mouse: hovering is focusing ---- */

let hoverEnabled = true;
export function setHoverEnabled(on) { hoverEnabled = !!on; }

document.addEventListener('mouseover', (e) => {
  if (!hoverEnabled) return;
  const node = e.target.closest && e.target.closest(`[${ATTR}]`);
  if (!node) return;
  if (current === node) return;
  setFocus(node, { source: 'pointer' });
}, true);

// Moving the mouse out of everything should not leave a stale ring on a card that is no longer under it.
document.addEventListener('mouseleave', () => { /* the ring stays; a TV has no pointer-leave at all */ }, true);
