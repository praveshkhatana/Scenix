/**
 * js/screens/search.js — one field, and whatever this hub can find.
 *
 * There are two ways onto this page and they share everything but the first request:
 *
 *   · `#/search` — an empty field. Letters arrive from a keyboard, a remote, or the on-screen pad below, and
 *     every pause in typing asks `/app/v1/search` what it knows. The hub decides what counts as a match; this
 *     page only draws the answer.
 *   · `#/search/row:<id>` — the "Explore all" button on a home shelf. A shelf on the home page is capped at
 *     thirty cards, so the rest of it is fetched and laid into the same grid, with the hub's own title as the
 *     heading. Typing in the field narrows into search, because that is what typing means.
 *
 * Results are drawn by the card plugins, so a result carries the same badges, match, and quick actions it would
 * have had at home. Nothing here knows a genre, a language, or a title.
 */

import { el } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { api } from '/ui/js/api.js';
import { cardFor } from '/ui/js/plugins.js';
import { setTypingTarget } from '/ui/js/input.js';
import { entryField } from '/ui/js/keyboard.js';

const DEBOUNCE_MS = 320;

export function screen(ctx, arg) {
  const root = el('div', { class: 'screen search' });
  const profile = ctx.profile || {};
  const rowId = typeof arg === 'string' && arg.startsWith('row:') ? arg.slice(4) : null;

  const status = el('div', { class: 'sub' });
  const head = el('div', { class: 'page-head' },
    el('h1', { text: rowId ? 'Browsing' : 'Search' }), status);

  /* The field and its keyboard come from js/keyboard.js, so a title and an addon address are typed on the
     same keys and a fix to one is a fix to both. `ready` keeps the first draw from scheduling a search
     before the debounce timer it would clear exists. */
  let ready = false;
  const entry = entryField({
    placeholder: rowId ? 'Type to search instead' : 'Type to search this hub',
    onChange: () => { if (ready) schedule(); },
  });

  root.appendChild(head);
  root.appendChild(entry.field);
  let seq = 0;                 // answers may arrive out of order, so only the newest one is drawn
  let items = [];
  let cardStyle = 'poster';

  /* ------------------------------------------------------------------ keyboard */

  root.appendChild(entry.kbd);

  /* ------------------------------------------------------------------ results */

  const grid = el('div', { class: 'grid' });
  root.appendChild(grid);

  function paint() {
    grid.textContent = '';
    const render = cardFor({ card: cardStyle, id: 'search' });
    for (let i = 0; i < items.length; i++) {
      const node = render(items[i], {
        index: i,
        play: (it, opts) => ctx.play(it, opts),
        open: (it) => ctx.open(it),
        list: (it) => ctx.list(it),
        explore: null,
      });
      node.style.setProperty('--i', String(i));
      grid.appendChild(node);
    }
    // Results arrive while the person is still writing. Taking the ring on every keystroke means a
    // second letter never gets typed - the remote walks onto a poster instead of the next key - so the
    // ring only moves into the results when it is not already resting on the pad.
    if (!(document.activeElement && document.activeElement.closest('.kbd'))) {
      focus.focusDefault(grid.querySelector('.card'));
    }
  }

  async function runSearch() {
    const mine = ++seq;
    const q = entry.value.trim();
    if (!q) {
      status.textContent = rowId ? shelfLabel : 'Searching reads this hub only — nothing leaves your network.';
      return;
    }
    status.textContent = `Asking the hub about “${q}”…`;
    try {
      const r = await api.search(q, profile.id || '');
      if (mine !== seq) return;
      items = (r && Array.isArray(r.items)) ? r.items : [];
      cardStyle = 'poster';
      status.textContent = items.length
        ? `${items.length} title${items.length > 1 ? 's' : ''} here match “${q}”.`
        : `Nothing in this hub matches “${q}”. A shorter word usually finds it.`;
    } catch (e) {
      if (mine !== seq) return;
      status.textContent = 'The search did not answer. It is still reachable from this page.';
    }
    paint();
  }

  let timer = null;
  ready = true;
  function schedule() {
    if (timer) clearTimeout(timer);
    timer = setTimeout(runSearch, DEBOUNCE_MS);
  }

  /* --------------------------------------------------------- typed characters */

  // Every edit goes through the shared buffer, which already asks for a search through `onChange`; these are
  // just the names js/input.js calls when a real keyboard or a remote's numbers reach the page.
  setTypingTarget({
    insert: (ch) => entry.insert(ch),
    backspace: () => entry.back(false),
    clear: () => entry.back(true),
    commit: () => { if (timer) clearTimeout(timer); runSearch(); },
  });

  /* ------------------------------------------------------------ shelf browsing */

  let shelfLabel = '';
  async function loadShelf() {
    status.textContent = 'Opening this shelf…';
    try {
      const r = await api.row(rowId, profile.id || '');
      const row = (r && r.row) || r || {};
      items = Array.isArray(row.items) ? row.items : [];
      cardStyle = row.card || 'poster';
      shelfLabel = `${row.title || 'This shelf'} — ${items.length} title${items.length === 1 ? '' : 's'}, the whole shelf rather than the first thirty.`;
      head.replaceChild(el('h1', { text: row.title || 'Browsing' }), head.firstChild);
      status.textContent = shelfLabel;
      paint();
    } catch (e) {
      status.textContent = 'That shelf could not be opened. Try searching instead.';
    }
  }

  if (rowId) loadShelf();
  else if (typeof arg === 'string' && arg && !arg.startsWith('row:')) { entry.set(arg); runSearch(); }
  else runSearch();

  root.__defaultFocus = () => grid.querySelector('.card') || entry.firstKey();
  root.__dispose = () => {
    setTypingTarget(null);
    if (timer) clearTimeout(timer);
    seq++;                       // any answer still in flight is now stale and will not paint
  };
  return root;
}
