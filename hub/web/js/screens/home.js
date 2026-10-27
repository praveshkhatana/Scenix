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

  /* ---- first run ---------------------------------------------------------
     A hub with nothing installed answers `/home` with no rows at all. On a TV that reads as a broken app, so
     the page says what is missing and puts the remote on the fix. Everything here comes from `capabilities`:
     a hub that manages its own add-ons sends the viewer to Settings, one that does not is told who to ask. */

  function firstRun() {
    const m = ctx.manifest() || {};
    const caps = m.capabilities || {};
    const canFix = !!caps.addonAdmin || !!caps.settingsAdmin;
    const steps = [];
    if (canFix) steps.push(['Add an add-on', 'Add-ons are what bring titles onto the hub. Scenix shows whatever they hand over, in the order the hub chooses.']);
    steps.push(['Choose the languages you watch', 'Audio and subtitles are ranked by what you ask for, so this decides what you are offered.']);
    const box = el('div', { class: 'firstrun' },
      el('h1', { text: 'Nothing on the shelves yet' }),
      el('p', {
        text: canFix
          ? 'The hub is answering and your profile is set. What is missing is content: this hub has no add-ons installed, so there is nothing to put on a row.'
          : 'The hub is answering and your profile is set, but it has nothing to show yet. Whoever runs this hub adds the content.',
      }),
      el('div', { class: 'fr-steps' },
        ...steps.map((s, i) => el('div', { class: 'fr-step' },
          el('i', { text: String(i + 1) }),
          el('div', {}, el('b', { text: s[0] }), el('span', { text: s[1] })))))
      ,
      el('div', { class: 'bb-btns' },
        canFix ? el('button', { class: 'btn white big', type: 'button', onclick: () => ctx.navigate('settings') }, 'Set up the hub') : null,
        caps.search ? el('button', { class: 'btn ghost big', type: 'button', onclick: () => ctx.navigate('search') }, 'Search anyway') : null));
    for (const b of box.querySelectorAll('.btn')) focus.focusable(b);
    return box;
  }

  const disposers = [];

  /** Stop whatever is moving and give the card back the size it had before. */
  function endPreview() {
    const n = previewFor;
    previewFor = null;
    stopPreview();
    if (n && n.closest) {
      const c = n.closest('.card');
      if (c) c.classList.remove('playing');
    }
  }

  /* ---- load ------------------------------------------------------------- */

  async function load() {
    const profile = session.profile && session.profile.id;
    if (!profile) return;
    // ?empty=1 shows this state against a hub that already has content, so it can be checked on a browser.
    if (new URLSearchParams(location.search).has('empty')) { shelves.appendChild(firstRun()); return; }
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

    // No rows at all means a hub with nothing installed, not a screen that failed to paint.
    if (!ordered.length) { shelves.appendChild(firstRun()); return; }

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
    if (previewFor && previewFor !== node) endPreview();
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
        // A card that is actually moving picture is given room to be one: wider, and at the trailer's ratio.
        if (trailer && art && startPreview(art, trailer)) { previewFor = node; card.classList.add('playing'); }
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
