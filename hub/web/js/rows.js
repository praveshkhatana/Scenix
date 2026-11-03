/**
 * js/rows.js — the remote standard every screen shares, written once.
 *
 * A row is the whole list item as one focusable button: name on the left, current value on the right,
 * a chevron saying "OK opens something". One ring stop per row is the point — a remote aims at words,
 * not at a 64-pixel switch parked in their corner. This module is those rows (the ones proven in
 * Settings) made available to every other screen: pickRow for a value out of a fixed list, multiRow
 * for several choices at once, actionRow when more than one verb could apply, and toggle for the one
 * switch whose whole row is the control.
 */

import { el } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { icon } from '/ui/js/icons.js';
import { pushHandler, popHandler } from '/ui/js/input.js';

export function makeRows(ctx) {
  function line(label, note) {
    const li = el('div', { class: 'li', 'data-nav': 'h' },
      el('div', {}, el('span', { text: label }), note ? el('span', { class: 'note', text: note }) : null));
    return li;
  }
  function value(text) { return el('span', { class: 'lv', text: String(text == null ? '—' : text) }); }
  /** Write a row's value and let it mark the change: a short pop, so a saved number is visibly the
   *  same number the dial just held rather than new text that appeared somewhere. */
  function setLv(b, text) {
    if (!b.__lv) return;
    b.__lv.textContent = text;
    b.__lv.classList.remove('bump');
    void b.__lv.offsetWidth;
    b.__lv.classList.add('bump');
  }

  /** The whole list item as one focusable button: name on the left, current value on the right,
   *  a chevron saying "OK opens something". */
  function rowShell(label, note, { valueText = null, chev = true, role = null, checked = null } = {}) {
    const attrs = { class: 'li act', type: 'button', 'data-nav': 'h' };
    if (role) { attrs.role = role; attrs['aria-checked'] = checked ? 'true' : 'false'; }
    const b = el('button', attrs,
      el('div', { class: 'ltext' },
        el('span', { class: 'lname', text: label }),
        note ? el('span', { class: 'note', text: note }) : null));
    let lv = null;
    if (valueText !== null) { lv = value(valueText); b.appendChild(lv); }
    if (chev) b.appendChild(el('span', { class: 'chev' }, icon('chevRight')));
    focus.focusable(b);
    b.__lv = lv;
    return b;
  }

  /** A row whose OK runs something directly — opens a dialog, navigates, or performs one verb. */
  function openRow(parent, label, note, fn, { valueText = null } = {}) {
    const b = rowShell(label, note, { valueText });
    b.onclick = () => fn();
    parent.appendChild(b);
    return b;
  }

  /** One value out of a fixed list. The row shows what it is; OK opens the dial the remote actually
   *  turns — the value large in the middle, Up/Down moving it, OK saving, Back cancelling. Nothing is
   *  written until OK, so walking past a wrong value costs nothing. */
  function pickRow(parent, label, note, options, currentVal, labelFor, write, fmt) {
    const show = (v) => (fmt ? fmt(v) : String(labelFor ? labelFor(v) : v));
    if (!write) {
      const li = line(label, note);
      li.appendChild(value(show(currentVal)));
      parent.appendChild(li);
      return li;
    }
    const b = rowShell(label, note, { valueText: show(currentVal) });
    b.onclick = () => {
      const values = options.slice();
      let i = values.indexOf(currentVal);
      if (i < 0) {
        const near = values.findIndex((v) => Number(v) >= Number(currentVal));
        i = near < 0 ? Math.max(0, values.length - 1) : near;
        if (Number.isNaN(Number(currentVal)) && currentVal == null) i = Math.floor(values.length / 2);
      }
      const box = el('div', { class: 'mbox wheel' },
        el('h2', { text: label }),
        note ? el('p', { class: 'wnote', text: note }) : null,
        el('div', { class: 'wdial' },
          (() => { const u = el('button', { class: 'warrow', type: 'button', 'aria-label': 'Higher' }, icon('chevUp')); focus.focusable(u); u.onclick = () => move(1); return u; })(),
          (() => { const v = el('button', { class: 'wval', type: 'button' }); focus.focusable(v); v.onclick = () => commit(); return v; })(),
          (() => { const d = el('button', { class: 'warrow', type: 'button', 'aria-label': 'Lower' }, icon('chevDown')); focus.focusable(d); d.onclick = () => move(-1); return d; })()),
        el('p', { class: 'whint', text: 'Up and Down change it · OK saves · Back cancels' }));
      const wval = box.querySelector('.wval');
      const paintVal = () => {
        wval.textContent = show(values[i]);
        wval.setAttribute('aria-label', `${label}: ${show(values[i])}`);
        // The number itself marks every step: it re-pops as it changes, so turning the dial has a
        // tick you can see instead of silent text swapping in place.
        wval.classList.remove('pop');
        void wval.offsetWidth;
        wval.classList.add('pop');
      };
      const move = (d) => {
        const j = Math.min(values.length - 1, Math.max(0, i + d));
        if (j === i) return;
        i = j;
        paintVal();
      };
      const commit = () => {
        ctx.closeModals();
        setLv(b, show(values[i]));
        write(values[i]);
      };
      // The dial answers to the D-pad before anything else on the stage does: Up/Down move the value
      // wherever the ring happens to be inside the box, and OK means "save" — not "click whatever is
      // focused". Escape falls through to the modal's own handler, which closes without writing: cancel.
      const lane = (e, key) => {
        if (key === 'ArrowUp') { move(1); return true; }
        if (key === 'ArrowDown') { move(-1); return true; }
        if (key === 'Enter' || key === 'NumpadEnter' || key === ' ') { commit(); return true; }
        return false;
      };
      paintVal();
      ctx.mountModal(box, () => popHandler(lane));
      pushHandler(lane);
      focus.focusDefault(wval);
    };
    parent.appendChild(b);
    return b;
  }

  /** Several choices at once. The row says how many are on; OK opens a checklist of full-width rows
   *  whose switches flip under the ring, and one Save button commits them together. Back leaves the
   *  row untouched, so a mistap mid-list is free. */
  function multiRow(parent, label, note, options, selected, labelFor, write) {
    const summary = () => {
      if (!selected.length) return 'none';
      if (selected.length <= 2) return selected.map((k) => labelFor(k)).join(', ');
      return `${selected.length} chosen`;
    };
    if (!write) {
      const li = line(label, note);
      li.appendChild(value(summary()));
      parent.appendChild(li);
      return li;
    }
    const b = rowShell(label, note, { valueText: summary() });
    b.onclick = () => {
      const chosen = selected.slice();
      const box = el('div', { class: 'mbox check' },
        el('h2', { text: label }),
        note ? el('p', { class: 'wnote', text: note }) : null,
        el('div', { class: 'wlist', 'data-nav': 'v' }));
      const list = box.querySelector('.wlist');
      for (const o of options) {
        const on = () => chosen.some((s) => String(s) === String(o));
        const sw = el('span', { class: `sw${on() ? ' on' : ''}` }, el('i'));
        const r = el('button', {
          class: 'li act', type: 'button', role: 'switch', 'aria-checked': on() ? 'true' : 'false',
          'aria-label': labelFor(o),
        }, el('div', { class: 'ltext' }, el('span', { class: 'lname', text: labelFor(o) })), sw);
        r.onclick = () => {
          const next = !on();
          if (next) chosen.push(o);
          else {
            const k = chosen.findIndex((s) => String(s) === String(o));
            if (k >= 0) chosen.splice(k, 1);
          }
          sw.classList.toggle('on', next);
          r.setAttribute('aria-checked', next ? 'true' : 'false');
        };
        focus.focusable(r);
        list.appendChild(r);
      }
      box.appendChild(el('div', { class: 'mfoot' },
        (() => { const c = el('button', { class: 'btn line', type: 'button', text: 'Cancel', onclick: () => ctx.closeModals() }); focus.focusable(c); return c; })(),
        (() => { const s = el('button', { class: 'btn white', type: 'button', text: 'Save' }); s.onclick = () => {
          ctx.closeModals();
          selected.length = 0;
          Array.prototype.push.apply(selected, chosen);
          setLv(b, summary());
          write(chosen.slice());
        }; focus.focusable(s); return s; })()));
      ctx.mountModal(box, null);
      focus.focusDefault(list.querySelector('[data-f]'));
    };
    parent.appendChild(b);
    return b;
  }

  /** A row more than one thing could be done to. Pressing OK on the row asks which, with the
   *  destructive action in red and Cancel last. Back closes it having changed nothing. */
  function actionRow(parent, label, note, { valueText = null, actions }) {
    const b = rowShell(label, note, { valueText });
    b.onclick = () => {
      const box = el('div', { class: 'mbox actions' },
        el('h2', { text: label }),
        note ? el('p', { class: 'wnote', text: note }) : null,
        el('div', { class: 'alist', 'data-nav': 'v' }));
      const list = box.querySelector('.alist');
      for (const a of actions()) {
        const r = el('button', {
          class: `li act${a.danger ? ' danger' : ''}`, type: 'button',
          text: a.label,
        });
        r.onclick = async () => {
          ctx.closeModals();
          await a.run();
        };
        focus.focusable(r);
        list.appendChild(r);
      }
      const cancel = el('button', { class: 'btn line cancel', type: 'button', text: 'Cancel', onclick: () => ctx.closeModals() });
      focus.focusable(cancel);
      box.appendChild(el('div', { class: 'mfoot' }, cancel));
      ctx.mountModal(box, null);
      focus.focusDefault(list.querySelector('[data-f]'));
    };
    parent.appendChild(b);
    return b;
  }

  /** One switch: the whole row is the control. OK flips it and writes; there is no modal to unwind. */
  function toggle(parent, label, note, on, write) {
    const sw = el('span', { class: `sw${on ? ' on' : ''}` }, el('i'));
    const b = rowShell(label, note, { valueText: null, chev: false, role: 'switch', checked: on });
    b.appendChild(sw);
    b.onclick = async () => {
      const next = !sw.classList.contains('on');
      sw.classList.toggle('on', next);
      b.setAttribute('aria-checked', next ? 'true' : 'false');
      await write(next);
    };
    parent.appendChild(b);
    return b;
  }

  return { line, value, setLv, rowShell, openRow, pickRow, multiRow, actionRow, toggle };
}
