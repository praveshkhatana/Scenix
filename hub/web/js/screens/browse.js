/**
 * js/screens/browse.js — a section tab opens shelves, not a search box.
 *
 * Movies and Series used to route to the search screen pre-loaded with their hub row: a heading, a text
 * field, and one long grid — the layout for typing, standing in for the layout for browsing. Netflix opens
 * a category as rails you walk. The hub already ships each section as one flat row (/app/v1/row/movies);
 * this page splits that same payload by its own genre tags into shelves. No new endpoint, nothing invented:
 * every rail is titles the hub actually delivered, grouped by metadata it already sent.
 */

import { api } from '/ui/js/api.js';
import { el } from '/ui/js/dom.js';
import { rowFor, lazyMount, dropMountedRows } from '/ui/js/plugins.js';
import { session } from '/ui/js/state.js';

const GENRE_SHELVES = 6;   // the six genres this section actually has most of
const PER_SHELF = 18;      // keep in step with MAX_CARDS in plugins.js

export function screen(ctx, arg) {
  const kind = arg === 'series' ? 'series' : 'movies';
  const label = kind === 'series' ? 'Series' : 'Movies';

  const root = el('div', { class: 'screen browse' });
  const head = el('div', { class: 'page-head' },
    el('h1', { text: label }),
    el('p', { class: 'sub', text: 'Loading…' }));
  // Plain `rows`, not home's `pulled`: the hero-pull overlap belongs under a billboard, and against a
  // page heading it just lands the first shelf's title on top of the count line.
  const shelves = el('div', { class: 'rows' });
  root.appendChild(head);
  root.appendChild(shelves);

  let dead = false;
  root.__dispose = () => { dead = true; dropMountedRows(root); };

  /**
   * One shelf. A synthetic row (id, title, items, total) is indistinguishable from a hub row at this
   * level — plugins.js mounts it like any other, and fitRow keeps its promise: the See-all tile only
   * appears when titles were left off the rail, and it opens the search grid for that genre.
   */
  function buildRow(row, index) {
    const node = rowFor(row)(row, {
      index,
      play: (item) => ctx.play(item),
      open: (item) => ctx.open(item),
      list: (item) => ctx.list(item),
      // A genre shelf's "all" is a search for the genre word — that is exactly what the hub's search
      // answers with the full list. The recent shelf has no word to carry, so its header button is
      // removed below rather than pointed at something it cannot name.
      explore: (r) => ctx.navigate('search', r.q || ''),
    });
    node.style.setProperty('--i', String(index));
    shelves.appendChild(node);
    if (!row.q && node.querySelector('h3 .explore')) node.querySelector('h3 .explore').remove();
    lazyMount(node, { root, preload: '700px' });
  }

  async function load() {
    const profile = session.profile && session.profile.id;
    const r = await api.row(kind, profile).catch(() => null);
    if (dead || !root.isConnected) return;
    if (!r) {
      // A hub that has just started is still building its biggest rows. "Nothing in this section" is a
      // promise the screen cannot make about a request that merely did not answer in time.
      head.querySelector('.sub').textContent = 'The hub is still warming up — open this again in a moment.';
      return;
    }
    const items = ((r && r.items) || []).filter((it) => it && it.identity);
    head.querySelector('.sub').textContent = items.length
      ? `${items.length} ${kind === 'series' ? 'shows' : 'titles'} on this hub.`
      : 'Nothing in this section yet.';
    if (!items.length) return;

    // Group once by the genre tags the hub already carries, and keep only genres this section has real
    // depth in — three Action titles is not an Action shelf. Titles with no genre still ride the rails
    // of the genres they do have; a shelf below four names would just repeat the poster wall.
    const byGenre = new Map();
    for (const it of items) {
      for (const g of (it.genres || [])) {
        const list = byGenre.get(g) || [];
        list.push(it);
        byGenre.set(g, list);
      }
    }
    const genres = [...byGenre.entries()]
      .filter(([, list]) => list.length >= 4)
      .sort((a, b) => b[1].length - a[1].length)
      .slice(0, GENRE_SHELVES);

    let index = 0;
    const added = items.filter((it) => it.addedAt).sort((a, b) => Number(b.addedAt) - Number(a.addedAt));
    if (added.length >= 4) {
      buildRow({ id: `${kind}-recent`, title: `New on ${label}`, card: 'poster',
        items: added.slice(0, PER_SHELF), total: added.length }, index++);
    }
    for (const [g, list] of genres) {
      buildRow({ id: `${kind}-${g}`, q: g, title: g, card: 'poster',
        items: list.slice(0, PER_SHELF), total: list.length }, index++);
    }
    root.__defaultFocus = () => shelves.querySelector('.card') || head.querySelector('h1');
  }

  load().catch(() => {
    if (!dead && root.isConnected) head.querySelector('.sub').textContent = 'The hub is not answering right now.';
  });
  return root;
}
