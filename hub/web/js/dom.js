/**
 * js/dom.js — the only DOM helper in the product.
 *
 * `el()` builds an element from a tag, a props object and children. No virtual DOM, no diffing: a screen
 * that changes rebuilds the piece that changed. Text set with `text` is never parsed as HTML, which is
 * what keeps a title containing `<script>` from becoming code — the hub's data is remote and this UI must
 * treat it as data.
 */

export function el(tag, props = {}, ...kids) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k === 'class') node.className = v;
    else if (k === 'text') node.textContent = String(v);
    else if (k === 'dataset') Object.assign(node.dataset, v);
    else if (k === 'style') node.setAttribute('style', v);
    else if (k.startsWith('on') && typeof v === 'function') node.addEventListener(k.slice(2), v);
    else if (k === 'data') { for (const [dk, dv] of Object.entries(v)) node.setAttribute(dk, String(dv)); }
    else node.setAttribute(k, v === true ? '' : String(v));
  }
  append(node, kids);
  return node;
}

export function append(parent, kids) {
  for (const k of kids.flat(3)) {
    if (k === null || k === undefined || k === false) continue;
    parent.appendChild(typeof k === 'string' || typeof k === 'number' ? document.createTextNode(String(k)) : k);
  }
  return parent;
}

export function clear(node) {
  while (node && node.firstChild) node.removeChild(node.firstChild);
  return node;
}

export function byId(id) { return document.getElementById(id); }
export function layer() { return byId('layer'); }

/** A short status line at the bottom of the screen — used for saves, errors and honest notices. */
let toastTimer = null;
export function toast(msg, ms = 2600) {
  const l = layer();
  if (!l) return;
  const old = l.querySelector('.toast');
  if (old) old.remove();
  const t = el('div', { class: 'toast', text: String(msg) });
  l.appendChild(t);
  clearTimeout(toastTimer);
  // It leaves the way it came — a short fade-down rather than a yank. The extra window is animation
  // only; a newer toast still replaces it instantly.
  toastTimer = setTimeout(() => {
    t.classList.add('out');
    setTimeout(() => t.remove(), 260);
  }, ms);
}

export function prefersReducedMotion() {
  try { return window.matchMedia('(prefers-reduced-motion: reduce)').matches; } catch (e) { return false; }
}

export function scrollBehavior() { return prefersReducedMotion() ? 'auto' : 'smooth'; }

/**
 * A remote image that never breaks a row: lazy, neutral placeholder behind it, and the placeholder stays
 * if the image 404s or the CDN is unreachable. Rows must survive a missing picture.
 */
/** Ask the CDN for a screen-sized picture, not the master. A 4K original is not more detail on a TV. */
export function imageUrl(src) {
  const u = String(src || '');
  if (!u) return '';
  if (/image\.tmdb\.org\/t\/p\/original\//i.test(u)) return u.replace(/\/t\/p\/original\//i, '/t/p/w500/');
  if (/images\.metahub\.space\/backdrop\/large\//i.test(u)) return u.replace('/backdrop/large/', '/backdrop/medium/');
  return u;
}

export function posterImg(src, alt, cls) {
  const img = el('img', { alt: alt || '', loading: 'lazy', decoding: 'async', class: cls || '' });
  src = imageUrl(src);
  if (src) {
    img.addEventListener('error', () => { img.remove(); });   // the gradient placeholder underneath stays
    img.src = src;
  } else {
    img.style.display = 'none';
  }
  return img;
}
