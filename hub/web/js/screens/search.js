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
import { icon } from '/ui/js/icons.js';
import { api } from '/ui/js/api.js';
import { cardFor } from '/ui/js/plugins.js';
import { setTypingTarget } from '/ui/js/input.js';

/* A keyboard laid out for a thumb on a touch screen and for a D-pad on a remote alike. Characters only: the
   hub does the matching, and a catalogue this mixed has no use for a shift key. */
// A row is either a run of single characters or an explicit list of keys. Writing the last row as the
// string '⌫ clear' asked the loop to read it one character at a time, which produced Delete, a stray
// space bar, and then c, l, e, a, r as five separate keys.
const PAD = ['1234567890', 'qwertyuiop', 'asdfghjkl', 'zxcvbnm', [' ', '.', '-'], ['⌫', 'Clear']];
const DEBOUNCE_MS = 320;

export function screen(ctx, arg) {
  const root = el('div', { class: 'screen search' });
  const profile = ctx.profile || {};
  const rowId = typeof arg === 'string' && arg.startsWith('row:') ? arg.slice(4) : null;

  const status = el('div', { class: 'sub' });
  const head = el('div', { class: 'page-head' },
    el('h1', { text: rowId ? 'Browsing' : 'Search' }), status);

  let text = '';
  const qText = el('div', { class: 'q empty', text: 'Type to search this hub' });
  const field = el('div', { class: 'field' },
    el('span', { class: 'ico' }, icon('search')), qText, el('span', { class: 'caret' }));

  root.appendChild(head);
  root.appendChild(field);
  let seq = 0;                 // answers may arrive out of order, so only the newest one is drawn
  let items = [];
  let cardStyle = 'poster';

  /* ------------------------------------------------------------------ keyboard */

  const kbd = el('div', { class: 'kbd' });
  // `wide` spans two columns: an action key carries a word, and a 24 px word does not fit in the 72 px
  // column a letter gets, which is how "Delete" ended up printed over its own border.
  const padKey = (label, fn, wide) => {
    const b = el('button', { class: wide ? 'key wide' : 'key', type: 'button', text: label, onclick: fn });
    focus.focusable(b);
    return b;
  };
  for (const keys of PAD) {
    const kr = el('div', { class: 'kr' });
    for (const raw of keys) {
      const ch = String(raw);
      if (ch === '⌫') kr.appendChild(padKey('Delete', back, true));
      else if (ch === 'Clear') kr.appendChild(padKey('Clear', () => { back(true); }, true));
      else kr.appendChild(padKey(ch, () => insert(ch)));
    }
    kbd.appendChild(kr);
  }
  root.appendChild(kbd);

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
    focus.focusDefault(grid.querySelector('.card'));
  }

  async function runSearch() {
    const mine = ++seq;
    const q = text.trim();
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
  function schedule() {
    if (timer) clearTimeout(timer);
    timer = setTimeout(runSearch, DEBOUNCE_MS);
  }

  /* --------------------------------------------------------- typed characters */

  function draw() {
    qText.textContent = text || (rowId ? 'Type to search instead' : 'Type to search this hub');
    qText.classList.toggle('empty', !text);
  }
  function insert(ch) { text += ch; draw(); schedule(); }
  function back(all) { text = all ? '' : text.slice(0, -1); draw(); schedule(); }

  setTypingTarget({
    insert,
    backspace: () => back(false),
    clear: () => back(true),
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
  else if (typeof arg === 'string' && arg && !arg.startsWith('row:')) { text = arg; draw(); runSearch(); }
  else runSearch();

  root.__defaultFocus = () => grid.querySelector('.card') || kbd.querySelector('.key');
  root.__dispose = () => {
    setTypingTarget(null);
    if (timer) clearTimeout(timer);
    seq++;                       // any answer still in flight is now stale and will not paint
  };
  return root;
}
