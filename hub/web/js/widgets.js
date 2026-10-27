/**
 * js/widgets.js — the controls every list of settings is built from.
 *
 * These were written inside js/screens/settings.js. They moved here because the profile screens
 * (`#/add`, `#/manage`) need the same rows, switches and chips, and a second copy of them would drift: a fix
 * to the switch that only reached one screen leaves the other one looking like a different product.
 *
 * Nothing in this file knows what a setting *is*. Each control is handed its current value and a function to
 * write a new one, so the hub stays the only place a value is decided. A control whose `write` is null is
 * read-only and says so through the caller's own refusal, rather than pretending the change took.
 */

import { el } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { icon } from '/ui/js/icons.js';

/** A headed block of rows. The note is the sentence that tells a person why the row is worth their attention. */
export function group(title, note) {
  const g = el('div', { class: 'sgroup' }, el('h3', { text: title }));
  if (note) g.appendChild(el('p', { class: 'note', text: note }));
  return g;
}

export function line(label, note) {
  const li = el('div', { class: 'li' },
    el('div', {}, el('span', { text: label }), note ? el('span', { class: 'note', text: note }) : null));
  return li;
}

export function value(text) { return el('span', { class: 'lv', text: String(text == null ? '—' : text) }); }

export function roundBtn(name, fn, aria) {
  const b = el('button', { class: 'round', type: 'button', 'aria-label': aria, title: aria, onclick: fn }, icon(name));
  focus.focusable(b);
  return b;
}

/** A number moved with + and − rather than typed: the only editing a remote and a touch screen share. */
export function stepper(parent, label, note, values, current, write, fmt) {
  let i = values.indexOf(current);
  if (i < 0) {
    const near = values.findIndex((v) => Number(v) >= Number(current));
    i = near < 0 ? values.length - 1 : Math.max(0, near - 1);
  }
  if (Number.isNaN(Number(current)) && current == null) i = Math.floor(values.length / 2);
  const lv = value(fmt ? fmt(values[i]) : values[i]);
  const step = (d) => {
    const j = Math.min(values.length - 1, Math.max(0, i + d));
    if (j === i) return;
    i = j;
    lv.textContent = fmt ? fmt(values[i]) : String(values[i]);
    if (write) write(values[i]);
  };
  const li = line(label, note);
  li.appendChild(lv);
  li.appendChild(roundBtn('minus', () => step(-1), `Less ${label}`));
  li.appendChild(roundBtn('plus', () => step(1), `More ${label}`));
  parent.appendChild(li);
}

export function toggle(parent, label, note, on, write) {
  const sw = el('button', { class: `sw${on ? ' on' : ''}`, type: 'button', role: 'switch', 'aria-checked': on ? 'true' : 'false' }, el('i'));
  focus.focusable(sw);
  sw.onclick = async () => {
    const next = !sw.classList.contains('on');
    sw.classList.toggle('on', next);
    sw.setAttribute('aria-checked', next ? 'true' : 'false');
    if (write) await write(next);
  };
  const li = line(label, note);
  li.appendChild(sw);
  parent.appendChild(li);
}

/** Chips for a set of codes or levels. Read-only rows get the same chips, without the write. */
export function chipSet(parent, options, selected, labelFor, write) {
  const wrap = el('div', { class: 'chips' });
  for (const o of options) {
    let isOn = false;
    for (const s of selected) if (String(s) === String(o)) isOn = true;
    const b = el('button', { class: `chip${isOn ? ' on' : ''}`, type: 'button', text: labelFor(o) });
    b.onclick = () => {
      const now = b.classList.toggle('on');
      const list = selected.slice();
      const k = list.findIndex((s) => String(s) === String(o));
      if (now && k < 0) list.push(o);
      if (!now && k >= 0) list.splice(k, 1);
      selected.length = 0;
      Array.prototype.push.apply(selected, list);
      if (write) write(list);
    };
    focus.focusable(b);
    wrap.appendChild(b);
  }
  parent.appendChild(wrap);
  return wrap;
}

/** One choice out of a list — a profile's kind, unlike languages: turning one on turns the others off. */
export function radioSet(parent, options, current, labelFor, write, onRefuse) {
  const wrap = el('div', { class: 'chips' });
  const buttons = [];
  for (const o of options) {
    const b = el('button', { class: `chip${String(o) === String(current) ? ' on' : ''}`, type: 'button', text: labelFor(o) });
    b.onclick = () => {
      if (!write) { if (onRefuse) onRefuse(); return; }
      for (const k of buttons) k.classList.remove('on');
      b.classList.add('on');
      write(o);
    };
    focus.focusable(b);
    buttons.push(b);
    wrap.appendChild(b);
  }
  parent.appendChild(wrap);
  return wrap;
}
