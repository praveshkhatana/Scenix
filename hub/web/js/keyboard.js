/**
 * js/keyboard.js — text entry that works from a remote, a thumb, or a real keyboard.
 *
 * Any screen that wants text builds the same two nodes: a field that shows what has been typed and a pad of
 * keys under it. The field is not an `<input>`: a focused HTML input on Android TV invites the firmware's own
 * keyboard over the top of the page, and the D-pad cannot leave it. So the value lives here, drawn as text, and
 * focus moves between keys like it does anywhere else in the interface.
 *
 * Three kinds of input reach the same buffer:
 *   · the pad, drawn for a D-pad - every key is a focusable button;
 *   · a physical or on-screen keyboard, through `setTypingTarget` in js/input.js, which a screen wires to
 *     `insert`/`back`/`clear` so typing works in a browser and on a phone;
 *   · the number keys of a remote, which js/input.js routes to `insert` while a pad is on screen.
 *
 * The layout carries the punctuation an address needs (`/`, `:`) because the only text anyone is asked for on
 * a TV is a title or a URL, and a keyboard without a colon cannot produce either one properly. There is no
 * shift key: matching in this hub is case-insensitive, and the addon addresses people type are lowercase.
 */

import { el } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { icon } from '/ui/js/icons.js';

/* A row is either a run of single characters or an explicit list of keys. Writing the last row as the string
   '⌫ clear' once asked the loop to read it one character at a time, which produced Delete, a stray space bar,
   and then c, l, e, a, r as five separate keys. */
const ROWS = [
  '1234567890',
  'qwertyuiop',
  'asdfghjkl',
  'zxcvbnm',
  [' ', '.', '/', ':', '-', '?'],
  ['⌫', 'Clear'],
];

/**
 * Make an entry field and its keyboard.
 *
 * @param opts.placeholder  shown while the value is empty
 * @param opts.initial      starting value
 * @param opts.glyph        icon shown inside the field
 * @param opts.onChange     called with the value after every edit
 * @param opts.onCommit     called with the value by Enter or a Done key
 * @param opts.actions      extra wide keys appended to the last row: [{ label, fn }]
 * @returns nodes to place, plus the buffer's API
 */
export function entryField(opts = {}) {
  const placeholder = String(opts.placeholder || '');
  const onChange = typeof opts.onChange === 'function' ? opts.onChange : () => {};
  const onCommit = typeof opts.onCommit === 'function' ? opts.onCommit : () => {};
  let text = String(opts.initial || '');

  const shown = el('div', { class: `q${text ? '' : ' empty'}`, text: text || placeholder });
  const field = el('div', { class: 'field' },
    el('span', { class: 'ico' }, icon(opts.glyph || 'search')), shown, el('span', { class: 'caret' }));
  const kbd = el('div', { class: 'kbd' });

  function draw() {
    shown.textContent = text || placeholder;
    shown.classList.toggle('empty', !text);
    onChange(text);
  }

  // `wide` spans two columns: an action key carries a word, and a 24 px word does not fit in the 72 px column
  // a letter gets, which is how "Delete" once ended up printed over its own border.
  const padKey = (label, fn, wide) => {
    const b = el('button', { class: wide ? 'key wide' : 'key', type: 'button', text: label, onclick: fn });
    focus.focusable(b);
    return b;
  };

  const api = {
    field, kbd,
    get value() { return text; },
    set(v) { text = String(v == null ? '' : v); draw(); },
    insert(ch) { text += String(ch); draw(); },
    back(all) { text = all ? '' : text.slice(0, -1); draw(); },
    commit() { onCommit(text); },
    firstKey() { return kbd.querySelector('.key'); },
  };

  for (let r = 0; r < ROWS.length; r++) {
    const kr = el('div', { class: 'kr' });
    for (const raw of ROWS[r]) {
      const ch = String(raw);
      if (ch === '⌫') kr.appendChild(padKey('Delete', () => api.back(false), true));
      else if (ch === 'Clear') kr.appendChild(padKey('Clear', () => api.back(true), true));
      else kr.appendChild(padKey(ch, () => api.insert(ch)));
    }
    // A screen that wants a decision rather than a search puts its own keys on the last row, so Done sits
    // where a thumb already expects the action to be.
    if (r === ROWS.length - 1) {
      for (const act of opts.actions || []) kr.appendChild(padKey(act.label, act.fn, true));
    }
    kbd.appendChild(kr);
  }

  draw();
  return api;
}
