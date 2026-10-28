/**
 * js/screens/search.js — one field and whatever this hub can find.
 *
 * Two devices, one screen:
 *
 *   · a television has a D-pad and no keyboard, so the left rail is a compact pad of keys and the
 *     results are a pane on the right. The pad never covers the answer, and the ring stays on the keys
 *     while a person is typing;
 *   · a laptop, phone or tablet already has a keyboard and a clipboard, so the rail is a real <input>
 *     and there is no second keyboard drawn over the page. Paste is the browser's own paste.
 *
 * The hub decides what a search means; this page only draws the answer. `#/search/row:<id>` is the
 * "Explore all" path: it loads the whole shelf into the same results pane.
 */

import { el, clear } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { api } from '/ui/js/api.js';
import { cardFor } from '/ui/js/plugins.js';
import { setTypingTarget, pushHandler, popHandler } from '/ui/js/input.js';
import { entryField } from '/ui/js/keyboard.js';

const DEBOUNCE_MS = 280;
const RECENT_KEY = 'scenix.search.recent';

function recent() {
  try {
    const raw = localStorage.getItem(RECENT_KEY);
    const list = raw ? JSON.parse(raw) : [];
    return Array.isArray(list) ? list.filter((x) => typeof x === 'string' && x.trim()).slice(0, 6) : [];
  } catch (e) { return []; }
}
function remember(q) {
  const word = String(q || '').trim();
  if (!word) return;
  try {
    const list = [word, ...recent().filter((x) => x.toLowerCase() !== word.toLowerCase())].slice(0, 6);
    localStorage.setItem(RECENT_KEY, JSON.stringify(list));
  } catch (e) { /* private mode */ }
}

export function screen(ctx, arg) {
  const tv = !!window.Scenix;
  const root = el('div', { class: 'screen search' });
  const profile = ctx.profile || {};
  const rowId = typeof arg === 'string' && arg.startsWith('row:') ? arg.slice(4) : null;

  const rail = el('div', { class: 'search-rail' });
  const results = el('div', { class: 'search-results' });
  const shell = el('div', { class: 'search-shell' }, rail, results);
  root.appendChild(shell);

  const status = el('p', { class: 'sub search-status' });
  rail.appendChild(el('div', { class: 'page-head' },
    el('h1', { text: rowId ? 'Browsing' : 'Search' }), status));

  let ready = false;
  const entry = entryField({
    placeholder: rowId ? 'Type to search instead' : 'Search this hub',
    glyph: 'search',
    native: !tv,
    onChange: () => { if (ready) schedule(); },
    onCommit: () => { if (timers) { clearTimeout(timers); timers = null; } runSearch(); },
  });

  rail.appendChild(entry.field);
  if (tv) rail.appendChild(entry.kbd);

  /* The last few things this person looked for belong in the empty state on both devices — the way
     Netflix offers them before you type. On a pointer device they sit under the field; on a TV, where
     the key pad already fills the left rail, they stand in the results pane until a query answers. */
  const suggestions = el('div', { class: 'search-suggest' });

  const empty = el('div', { class: 'search-empty' },
    el('div', { class: 'big', text: 'Find something to watch' }),
    el('p', { text: 'Type a title, an actor, a language or a mood. Search reads this hub only.' }));
  if (!tv) rail.appendChild(suggestions);
  else empty.appendChild(suggestions);
  results.appendChild(empty);
  const grid = el('div', { class: 'grid' });
  results.appendChild(grid);

  let seq = 0;
  let items = [];
  let cardStyle = 'poster';
  let timers = null;
  let shelfLabel = '';

  function paintSuggestions() {
    clear(suggestions);
    const words = recent();
    if (!words.length) {
      suggestions.appendChild(el('p', { class: 'note',
        text: 'Search reads this hub only — nothing leaves your network.' }));
      return;
    }
    suggestions.appendChild(el('p', { class: 'note', text: 'Recent' }));
    const list = el('div', { class: 'sug' });
    for (const word of words) {
      const b = el('button', { class: 'sugbtn', type: 'button', text: word });
      focus.focusable(b);
      b.onclick = () => { entry.set(word); runSearch(); };
      list.appendChild(b);
    }
    suggestions.appendChild(list);
  }

  function paint() {
    grid.textContent = '';
    empty.hidden = items.length > 0;
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
    results.scrollTop = 0;
    // The ring is on the pad while a person is still choosing letters — but when the pause says the word
    // is finished and the hub answers back, the ring steps into the results on its own: Down from there
    // continues exactly where typing left off, which is how Netflix search carries you across. On a
    // pointer device, where there is no pad to stand on, the first result has always been the default.
    if (items.length && (autoStep || !tv) && root.isConnected) {
      autoStep = false;
      const first = grid.querySelector('.card');
      if (first) focus.focusDefault(first);
    }
  }

  async function runSearch() {
    const mine = ++seq;
    const q = String(entry.value || '').trim();
    if (!q) {
      items = [];
      paint();
      status.textContent = rowId ? shelfLabel : 'Search this hub. Nothing leaves your network.';
      return;
    }
    remember(q);
    paintSuggestions();
    status.textContent = `Asking the hub about “${q}”…`;
    try {
      const r = await api.search(q, profile.id || '');
      if (mine !== seq) return;
      items = (r && Array.isArray(r.items)) ? r.items : [];
      cardStyle = 'poster';
      status.textContent = items.length
        ? `${items.length} title${items.length > 1 ? 's' : ''} match “${q}”.`
        : `Nothing in this hub matches “${q}”. A shorter word usually finds it.`;
    } catch (e) {
      if (mine !== seq) return;
      items = [];
      status.textContent = e && e.status === 403
        ? 'This profile may not search.'
        : 'The hub did not answer. It is still reachable from this page.';
    }
    paint();
  }

  ready = true;
  // The debounce ending is the signal that a person stopped typing: only such a run gets to move the
  // ring into the results, so an initial load or an OK-press never yanks the ring out of their hands.
  let autoStep = false;
  function schedule() {
    if (timers) clearTimeout(timers);
    timers = setTimeout(() => { timers = null; autoStep = true; runSearch(); }, DEBOUNCE_MS);
  }

  /* Typed characters. The native field already receives them; the TV pad needs the shared buffer. */
  let backLane = null;
  if (tv) {
    setTypingTarget({
      insert: (ch) => entry.insert(ch),
      backspace: () => entry.back(false),
      clear: () => entry.back(true),
      commit: () => { if (timers) { clearTimeout(timers); timers = null; } runSearch(); },
    });

    /* The first BACK on a TV clears the word instead of leaving the screen — the same verb the pad's
       Clear key runs, wired in ahead of the router's back-step. A wrong letter or two is erased where
       you stand and the empty pane with the recent words comes back; it takes a second BACK, with an
       empty field, to actually go anywhere. */
    backLane = (e, key) => {
      if (key !== 'Escape' && key !== 'GoBack' && key !== 'BrowserBack') return false;
      if (!String(entry.value || '').trim()) return false;
      entry.back(true);                 // onChange fires, so the debounced search unwinds itself
      focus.focusDefault(entry.firstKey());
      return true;
    };
    pushHandler(backLane);
  }

  async function loadShelf() {
    status.textContent = 'Opening this shelf…';
    try {
      const r = await api.row(rowId, profile.id || '');
      const row = (r && r.row) || r || {};
      items = Array.isArray(row.items) ? row.items : [];
      cardStyle = row.card || 'poster';
      shelfLabel = `${row.title || 'This shelf'} — ${items.length} title${items.length === 1 ? '' : 's'}.`;
      const h = rail.querySelector('h1');
      if (h) h.textContent = row.title || 'Browsing';
      status.textContent = shelfLabel;
      // A shelf that answers with nothing says so in its own words, not with the search screen's hint.
      if (!items.length) {
        empty.querySelector('.big').textContent = `No ${String(row.title || 'titles').toLowerCase()} here yet`;
        empty.querySelector('p').textContent = 'The hub has nothing to put on this shelf right now. '
          + 'Adding an addon or a playlist in Settings can change that.';
      }
      paint();
    } catch (e) {
      status.textContent = 'That shelf could not be opened. Try searching instead.';
    }
  }

  paintSuggestions();
  if (rowId) loadShelf();
  else if (typeof arg === 'string' && arg && !arg.startsWith('row:')) { entry.set(arg); runSearch(); }
  else runSearch();

  root.__defaultFocus = () => (tv ? entry.firstKey() : (entry.input || grid.querySelector('.card') || entry.firstKey()));
  root.__dispose = () => {
    if (tv) { setTypingTarget(null); if (backLane) popHandler(backLane); }
    if (timers) clearTimeout(timers);
    seq++;
  };
  return root;
}
