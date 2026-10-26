/**
 * js/screens/home.js — the billboard and the shelves.
 *
 * The whole page is a loop over `/app/v1/home`: rows come in the order the hub chose, each one names its
 * own presentation, and js/plugins.js decides what that means. Nothing here mentions a shelf by name, so
 * a new addon that registers a new row changes this page without changing it.
 */

import { api, getTrailer } from '/ui/js/api.js';
import { el, clear, toast } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { token } from '/ui/js/theme.js';
import { billboard, rowFor, cardFor, lazyMount, dropMountedRows } from '/ui/js/plugins.js';
import { startPreview, stopPreview } from '/ui/js/video.js';
import { session, rememberFocus } from '/ui/js/state.js';

export function screen(ctx) {
  const root = el('div', { class: 'screen home' });
  const shelves = el('div', { class: 'rows pulled' });
  root.appendChild(shelves);

  let rotate = null;
  let bb = null;
  let dwell = null;
  let previewFor = null;
  let dead = false;

  /* ---- billboard -------------------------------------------------------- */

  function startRotation(row) {
    if (rotate) clearInterval(rotate);
    const ms = token('hero', 'rotateMs', 6000);
    if (!row || row.__items.length < 2) return;
    rotate = setInterval(() => {
      if (dead || document.hidden) return;
      // A preview that is running is the viewer paying attention: rotation waits for it.
      if (previewFor) return;
      const next = (row.__index() + 1) % row.__items.length;
      row.__setItem(next);
      for (const b of row.querySelectorAll('.bb-btns .btn, .bb-btns .round')) focus.focusable(b);
    }, ms);
  }

  async function previewBillboard(slot) {
    const item = slot.__items[slot.__index()];
    if (!item || dead) return;
    const trailer = await getTrailer(item.identity, session.profile && session.profile.id);
    if (dead || slot.__items[slot.__index()] !== item) return;
    if (trailer && startPreview(slot.__previewSlot, trailer)) previewFor = slot;
  }

  /* ---- shelves ---------------------------------------------------------- */

  /**
   * One shelf, on its own terms. A row that fails is replaced by its own retry line; the fourteen rows
   * next to it keep working, which is the difference between a hub that is mid-restart and a broken TV.
   */
  async function buildRow(row, index) {
    const ctxFor = () => ({
      index,
      play: (item) => ctx.play(item),
      open: (item) => { rememberFocus('home', item.identity); ctx.open(item); },
      list: (item) => ctx.list(item),
      explore: (r) => ctx.navigate('search', `row:${r.id}`),
    });
    const node = rowFor(row)(row, ctxFor());
    node.style.setProperty('--i', String(index));
    shelves.appendChild(node);

    if ((node.__items || []).length) { lazyMount(node); return node; }

    // The hub answers `/home` fast with some shelves still empty; those fill themselves in when reached.
    const size = row.card === 'poster' ? 'poster' : 'landscape';
    const sk = el('div', { class: `cards skeleton ${size}` });
    for (let i = 0; i < 6; i++) sk.appendChild(el('i'));
    node.querySelector('.cards').replaceWith(sk);
    let filled = false;
    const fill = async () => {
      if (filled || dead) return;
      filled = true;
      try {
        const fresh = await api.row(row.id, session.profile.id);
        const items = (fresh && fresh.items) || [];
        if (!items.length) { node.remove(); return; }
        row.items = items;
        node.__items = items;
        node.__mount = (its) => {
          const list = el('div', { class: `cards ${size}`, 'data-nav': 'h' });
          const render = cardFor(row);
          its.slice(0, 30).forEach((item, i) => list.appendChild(render(item, Object.assign({}, ctxFor(), { index: i, row }))));
          sk.replaceWith(list);
          node.__mounted = true;
          return list;
        };
        lazyMount(node);
      } catch (e) {
        filled = false;
        sk.classList.remove('skeleton');
        for (const kid of Array.from(sk.children)) sk.removeChild(kid);
        sk.appendChild(el('div', { class: 'row-err' },
          el('span', { text: `${row.title || 'This shelf'} is not answering right now.` }),
          el('button', {
            class: 'btn ghost', type: 'button',
            onclick: () => { node.remove(); buildRow(row, index); },
          }, 'Try again')));
        focus.focusable(sk.querySelector('.btn'));
      }
    };
    if ('IntersectionObserver' in window) {
      const io = new IntersectionObserver((es) => {
        for (const e of es) if (e.isIntersecting) { io.disconnect(); fill(); }
      }, { rootMargin: '900px 0px' });
      io.observe(sk);
      disposers.push(() => io.disconnect());
    } else fill();
    return node;
  }

  const disposers = [];

  /* ---- load ------------------------------------------------------------- */

  async function load() {
    const profile = session.profile && session.profile.id;
    if (!profile) return;
    let home = session.home;
    if (!home || home.__profile !== profile) {
      home = await api.home(profile);
      home.__profile = profile;
      session.home = home;
    }
    if (dead) return;

    const rows = (home.rows || []).filter((r) => r && (r.items || []).length);
    // The hub orders the rows; `order` is authoritative when present, array order otherwise.
    const ordered = rows
      .map((r, i) => ({ r, i, o: r.order == null ? i : Number(r.order) }))
      .sort((a, b) => (a.o - b.o) || (a.i - b.i))
      .map((x) => x.r);

    const hero = ordered.find((r) => r.card === 'hero');
    const rest = ordered.filter((r) => r !== hero);

    if (hero && (hero.items || []).length) {
      bb = billboard(hero, {
        play: (item) => ctx.play(item),
        open: (item) => ctx.open(item),
        list: (item) => ctx.list(item),
      });
      root.insertBefore(bb, shelves);
      startRotation(bb);
    }
    for (let i = 0; i < rest.length; i++) buildRow(rest[i], i);
  }

  /* ---- dwell: 250 ms expands the card, 1500 ms starts its trailer ------- */

  const onDwell = focus.onFocus((node) => {
    if (dwell) { clearTimeout(dwell); dwell = null; }
    if (previewFor && previewFor !== node) { stopPreview(); previewFor = null; }
    if (!node || node === document.body) return;
    const card = node.closest ? node.closest('.card') : null;
    const inBillboard = node.closest && node.closest('.billboard');
    const delay = token('preview', 'delayMs', 1500);
    if (card) {
      dwell = setTimeout(async () => {
        if (dead) return;
        const identity = card.dataset.identity;
        const trailer = await getTrailer(identity, session.profile && session.profile.id);
        if (dead || focus.getFocus() !== node) return;
        const art = card.querySelector('.art');
        if (trailer && art && startPreview(art, trailer)) previewFor = node;
      }, delay);
    } else if (inBillboard && bb) {
      dwell = setTimeout(() => { if (!dead) previewBillboard(bb); }, delay);
    }
    void inBillboard;
  });
  disposers.push(() => { if (typeof onDwell === 'function') onDwell(); });

  root.__dispose = () => {
    dead = true;
    if (rotate) clearInterval(rotate);
    if (dwell) clearTimeout(dwell);
    stopPreview();
    dropMountedRows();
    for (const d of disposers) { try { d(); } catch (e) { /* ignore */ } }
  };

  load().catch((e) => {
    if (dead) return;
    console.error('[scenix] home failed', e);
    clear(shelves);
    shelves.appendChild(el('div', { class: 'row-err' },
      el('span', { text: 'The hub did not answer. Scenix will try again.' }),
      el('button', { class: 'btn ghost', type: 'button', onclick: () => ctx.reload() }, 'Try again')));
    toast('Check the hub address in Settings');
  });

  return root;
}
