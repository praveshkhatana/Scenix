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

function visible(node) {
  if (!node || !node.isConnected) return false;
  if (node.getAttribute('aria-hidden') === 'true') return false;
  const r = node.getBoundingClientRect();
  if (r.width < 2 || r.height < 2) return false;
  const st = window.getComputedStyle(node);
  if (st.visibility === 'hidden' || st.display === 'none' || st.opacity === '0') return false;
  return true;
}

function root() { return scopes.length ? scopes[scopes.length - 1] : document.body; }

export function candidates() {
  return Array.from(root().querySelectorAll(`[${ATTR}]`)).filter(visible);
}

export function onFocus(cb) { listeners.push(cb); return () => { const i = listeners.indexOf(cb); if (i >= 0) listeners.splice(i, 1); }; }

export function setFocus(node, { scroll = true, source = 'key' } = {}) {
  if (!node || node === current) return false;
  if (current) current.classList.remove('foc');
  current = node;
  node.classList.add('foc');
  try { node.focus({ preventScroll: true }); } catch (e) { /* older engines: class + ring still work */ }
  if (scroll) scrollTo(node);
  for (const cb of listeners) { try { cb(node, source); } catch (e) { console.error('[scenix] focus listener', e); } }
  return true;
}

export function blurFocus() {
  if (current) current.classList.remove('foc');
  current = null;
}

function scrollTo(node) {
  const behavior = prefersReducedMotion() ? 'auto' : 'smooth';
  try { node.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior }); }
  catch (e) { try { node.scrollIntoView(); } catch (e2) { /* ignore */ } }
}

/* ---- group navigation: walking a row or a grid by index ---- */

function groupOf(node) { return node.closest('[data-nav]'); }
function groupMembers(group, node) {
  const kids = Array.from(group.querySelectorAll(`[${ATTR}]`)).filter(visible);
  return kids.includes(node) ? kids : null;
}

/* ---- geometric navigation: what is really above or below on this screen ---- */

function rectOf(node) { return node.getBoundingClientRect(); }

function geometric(from, dir) {
  const a = rectOf(from);
  const acx = a.left + a.width / 2;
  const acy = a.top + a.height / 2;
  let best = null;
  let bestScore = Infinity;
  for (const cand of candidates()) {
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

export function move(dir) {
  if (!current || !visible(current)) {
    const first = candidates()[0];
    if (first) setFocus(first);
    return !!first;
  }
  const group = groupOf(current);
  let next = null;

  if (group) {
    const kind = group.getAttribute('data-nav');
    const members = groupMembers(group, current);
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
  if (!next) next = geometric(current, dir);
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
