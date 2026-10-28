/**
 * js/screens/list.js — My list: the titles this profile saved, and nowhere else.
 *
 * The list itself lives on this TV (js/state.js — the hub has no endpoint for it yet), so the only
 * question this screen asks the hub is *what* each saved identity is, asked through the same
 * /app/v1/meta the detail page uses and answered from the same cache the detail page fills. A
 * profile switching is a different person's list, so the screen is mounted per profile and never
 * shares a row with another one.
 *
 * A saved title that no longer resolves is not dropped: it stays in the list — the owner may add an
 * addon that brings it back — and is drawn with the only name we have for it, so nothing the viewer
 * saved ever vanishes because a shelf did. Removing is the card's own list action: it removes here
 * too, and the grid repaints under the ring.
 */

import { el, clear } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { api } from '/ui/js/api.js';
import { cardFor } from '/ui/js/plugins.js';
import * as state from '/ui/js/state.js';

export function screen(ctx) {
  const root = el('div', { class: 'screen list' });

  const head = el('div', { class: 'page-head' },
    el('h1', { text: 'My list' }),
    el('p', { class: 'sub', text: 'Loading…' }));
  const grid = el('div', { class: 'grid' });
  const empty = el('div', { class: 'list-empty' },
    el('div', { class: 'big', text: 'Nothing saved yet' }),
    el('p', { text: 'Press + on a poster you want to come back to, and it waits here for this profile.' }),
    el('button', { class: 'btn white big', type: 'button', text: 'Find something',
      onclick: () => ctx.navigate('search') }));
  empty.hidden = true;
  root.appendChild(head);
  root.appendChild(grid);
  root.appendChild(empty);

  let dead = false;
  const sub = head.querySelector('.sub');

  async function load() {
    const ids = [...state.session.myList];
    const profile = ctx.profile && ctx.profile.id;
    sub.textContent = ids.length
      ? `${ids.length} title${ids.length === 1 ? '' : 's'} saved on this profile.`
      : '';
    if (dead) return;
    if (!ids.length) { empty.hidden = false; paint([]); return; }

    const items = [];
    for (const id of ids) {
      if (dead) return;
      let it = state.metaCache.get(id);
      if (!it) {
        try {
          const r = await api.meta(id, profile);
          it = (r && r.item) || r || null;
          if (it && it.identity) state.rememberMeta(it);
        } catch (e) { it = null; }
      }
      // Unresolvable but still saved: name it by what the identity says and let the card art be empty.
      items.push(it || { identity: id, title: id.replace(/^[^:]+:/, '') || id });
    }
    if (dead) return;
    paint(items);
  }

  function paint(items) {
    clear(grid);
    empty.hidden = items.length > 0;
    const render = cardFor({ card: 'poster', id: 'list' });
    items.forEach((it, i) => {
      const node = render(it, {
        index: i,
        play: (x, opts) => ctx.play(x, opts),
        open: (x) => ctx.open(x),
        list: (x) => { ctx.list(x); load(); },
        explore: null,
      });
      node.style.setProperty('--i', String(i));
      grid.appendChild(node);
    });
    if (items.length) focus.focusDefault(grid.querySelector('.card'));
  }

  root.__defaultFocus = () => grid.querySelector('.card') || empty.querySelector('.btn');
  load();

  root.__dispose = () => { dead = true; };
  return root;
}
