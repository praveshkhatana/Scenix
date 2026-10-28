/**
 * js/screens/categories.js — the shelves the owner makes for themselves.
 *
 * Home rows come from the hub's own plugins; categories are the other half of the same idea. The owner
 * picks a catalogue this hub already carries — optionally one genre it says it publishes — names the
 * shelf, and it appears under the plugin rows on `/app/v1/home`. Nothing here names a provider or a
 * genre in code: the choices come from `/app/v1/settings` → `catalogOptions`, and a hub with different
 * addons opens the same screen with different chips.
 *
 * The screen is a remote first: every choice is a focusable chip, reordering is a pair of arrows on the
 * row (not drag), and the only typed value, the title, goes through the shared text modal. Each change
 * is written with `PUT /app/v1/settings` before the screen redraws, so what the list shows is what the
 * next Home load will draw.
 */

import { el, clear, toast } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { icon } from '/ui/js/icons.js';
import { api } from '/ui/js/api.js';

export function screen(ctx) {
  const root = el('div', { class: 'screen categories' });
  const head = el('div', { class: 'page-head' },
    el('h1', { text: 'Categories' }),
    el('p', { class: 'sub', text: 'Shelves made from a catalogue or genre this hub already carries. They follow the home rows and obey the same rules for every profile.' }));
  root.appendChild(head);

  const builder = el('div', { class: 'cat-builder' });
  const list = el('div', { class: 'cat-list' });
  const foot = el('div', { class: 'cat-foot' });
  root.appendChild(builder);
  root.appendChild(list);
  root.appendChild(foot);

  let categories = [];   // the owner's list, in display order
  let options = [];      // /app/v1/settings → catalogOptions
  let providerId = null; // the chip chosen in the add row
  let catalogId = null;  // that provider's catalogue
  let genre = '';        // '' means the whole catalogue
  let busy = false;

  /* ------------------------------------------------------------------ choices */

  function chip(label, on, onclick, extra = null) {
    const b = el('button', {
      class: `chip${on ? ' on' : ''}`, type: 'button', 'aria-pressed': on ? 'true' : 'false',
      text: label,
    }, extra);
    b.onclick = onclick;
    focus.focusable(b);
    return b;
  }

  function providers() {
    const out = [];
    for (const o of options) if (!out.includes(o.provider)) out.push(o.provider);
    return out;
  }

  function providerName(id) {
    const hit = options.find((o) => o.provider === id);
    return hit ? hit.providerName : id;
  }

  /** A catalogue's own name, plus its kind when the name alone would be ambiguous (two "Popular"s). */
  function catalogLabel(opt) {
    const name = (opt && (opt.name || opt.catalog)) || '';
    return opt && opt.type ? `${name} (${opt.type})` : name;
  }

  function selectedOption() {
    return options.find((o) => o.provider === providerId && o.catalog === catalogId) || null;
  }

  function optionFor(category) {
    return options.find((o) => o.provider === category.provider && o.catalog === category.catalog) || null;
  }

  /** Keep the add-row selection valid after a catalogue is disabled or a different hub answers. */
  function syncChoice() {
    const ids = providers();
    if (!ids.includes(providerId)) providerId = ids[0] || null;
    const mine = options.filter((o) => o.provider === providerId);
    if (!mine.some((o) => o.catalog === catalogId)) catalogId = mine.length ? mine[0].catalog : null;
    const opt = selectedOption();
    if (!opt || !(opt.genres || []).includes(genre)) genre = '';
  }

  function defaultTitle(opt) {
    const base = String(genre || opt.name || opt.catalog || 'Category').trim().slice(0, 60) || 'Category';
    const used = new Set(categories.map((c) => String(c.title || '').toLowerCase()));
    if (!used.has(base.toLowerCase())) return base;
    let n = 2;
    while (used.has((base + ' ' + n).toLowerCase())) n++;
    return base + ' ' + n;
  }

  /* ------------------------------------------------------------------ writes */

  /**
   * Every edit lands on the hub first and the screen is redrawn from the answer. Sending only
   * `categories` means a concurrent language or playlist change on another screen is not clobbered by
   * a stale copy of the whole settings object.
   */
  async function save(next, note) {
    if (busy) return;
    busy = true;
    try {
      const out = await api.saveSettings({ categories: next });
      const saved = out && out.settings && Array.isArray(out.settings.categories)
        ? out.settings.categories : next;
      categories = saved.map((c) => Object.assign({}, c));
      if (note) toast(note);
    } catch (e) {
      toast('The hub would not save that');
    }
    busy = false;
    paint();
  }

  function renumber(items) {
    return items.map((c, i) => Object.assign({}, c, { order: i }));
  }

  function add() {
    const opt = selectedOption();
    if (!opt) return;
    const entry = {
      title: defaultTitle(opt),
      provider: opt.provider,
      catalog: opt.catalog,
      genre,
      enabled: true,
      order: categories.length,
    };
    save(categories.concat([entry]), `${entry.title} added to Home`);
  }

  function toggle(category) {
    const on = category.enabled !== false;
    const next = categories.map((c) => (c.id === category.id
      ? Object.assign({}, c, { enabled: !on }) : c));
    save(next, `${category.title} ${on ? 'hidden from Home' : 'shown on Home'}`);
  }

  function move(category, delta) {
    const at = categories.findIndex((c) => c.id === category.id);
    const to = at + delta;
    if (at < 0 || to < 0 || to >= categories.length) return;
    const next = categories.slice();
    next.splice(at, 1);
    next.splice(to, 0, category);
    save(renumber(next), `${category.title} moved`);
  }

  function remove(category) {
    const next = categories.filter((c) => c.id !== category.id);
    save(renumber(next), `${category.title} removed`);
  }

  async function rename(category) {
    const title = await ctx.enterText({
      title: 'Rename category',
      reason: 'The name the shelf carries on the home screen.',
      placeholder: 'Category name',
      initial: category.title || '',
      action: 'Rename',
      validate: (v) => (!String(v || '').trim() ? 'Give the shelf a name first.' : null),
    });
    if (title === null) return;
    const next = categories.map((c) => (c.id === category.id
      ? Object.assign({}, c, { title: String(title).trim().slice(0, 60) }) : c));
    save(next, 'Category renamed');
  }

  /* ------------------------------------------------------------------- paint */

  function paintBuilder() {
    clear(builder);
    if (!options.length) {
      builder.appendChild(el('div', { class: 'cat-step' },
        el('span', { class: 'cat-label', text: 'Add a category' }),
        el('span', { class: 'cat-empty', text: 'This hub is not publishing any catalogue right now. Install or enable an add-on in Settings, then come back.' })));
      return;
    }

    syncChoice();
    const ids = providers();
    builder.appendChild(el('div', { class: 'cat-step' },
      el('span', { class: 'cat-label', text: 'Source' }),
      el('div', { class: 'cat-chips', 'data-nav': 'h' },
        ids.map((id) => chip(providerName(id), id === providerId, () => {
          providerId = id;
          catalogId = null;
          genre = '';
          paintBuilder();
          focus.focusDefault(builder.querySelector('.chip'));
        })))));

    const mine = options.filter((o) => o.provider === providerId);
    builder.appendChild(el('div', { class: 'cat-step' },
      el('span', { class: 'cat-label', text: 'Catalogue' }),
      el('div', { class: 'cat-chips', 'data-nav': 'h' },
        mine.map((o) => chip(catalogLabel(o), o.catalog === catalogId, () => {
          catalogId = o.catalog;
          genre = '';
          paintBuilder();
          focus.focusDefault(builder.querySelector('.chip'));
        })))));

    const opt = selectedOption();
    const genres = (opt && opt.genres) || [];
    if (genres.length) {
      builder.appendChild(el('div', { class: 'cat-step' },
        el('span', { class: 'cat-label', text: 'Genre' }),
        el('div', { class: 'cat-chips', 'data-nav': 'h' },
          chip('Any genre', !genre, () => {
            genre = '';
            paintBuilder();
            focus.focusDefault(builder.querySelector('.chip'));
          }),
          genres.map((g) => chip(g, g === genre, () => {
            genre = g;
            paintBuilder();
            focus.focusDefault(builder.querySelector('.chip'));
          })))));
    }

    const parts = [providerName(providerId), opt ? catalogLabel(opt) : catalogId];
    if (genre) parts.push(genre);
    const line = el('div', { class: 'cat-addline', 'data-nav': 'h' },
      el('span', { class: 'cat-summary', text: parts.filter(Boolean).join(' · ') }),
      (() => {
        const b = el('button', { class: 'btn white', type: 'button', text: 'Add category' });
        b.disabled = !opt;
        focus.focusable(b);
        b.onclick = add;
        return b;
      })());
    builder.appendChild(line);
  }

  function paintList() {
    clear(list);
    if (!categories.length) {
      list.appendChild(el('p', {
        class: 'cat-empty',
        text: 'No categories yet. Choose a source and a catalogue above, then press Add category.',
      }));
      return;
    }
    categories.forEach((c, index) => {
      const opt = optionFor(c);
      const on = c.enabled !== false;
      const bits = [opt ? opt.providerName : c.provider, opt ? catalogLabel(opt) : c.catalog];
      if (c.genre) bits.push(c.genre);
      if (!opt) bits.push('not published right now');
      const row = el('div', { class: `cat-row${on ? '' : ' off'}`, 'data-nav': 'h' },
        el('div', { class: 'cat-body' },
          el('b', { text: c.title || c.genre || c.catalog }),
          el('span', { text: bits.filter(Boolean).join(' · ') })));

      const sw = el('button', {
        class: `sw${on ? ' on' : ''}`, type: 'button', role: 'switch',
        'aria-checked': on ? 'true' : 'false', 'aria-label': `${c.title || c.id} shown on Home`,
        title: on ? 'Hide this shelf from Home' : 'Show this shelf on Home',
      }, el('i'));
      focus.focusable(sw);
      sw.onclick = () => toggle(c);

      const up = el('button', {
        class: 'round', type: 'button', 'aria-label': `Move ${c.title || c.id} earlier`,
      }, icon('chevUp'));
      const down = el('button', {
        class: 'round', type: 'button', 'aria-label': `Move ${c.title || c.id} later`,
      }, icon('chevDown'));
      focus.focusable(up);
      focus.focusable(down);
      up.disabled = index === 0;
      down.disabled = index === categories.length - 1;
      // A disabled arrow is decoration, not a stop: leaving it focusable gives the ring a button that
      // cannot do anything, which on a remote is indistinguishable from a broken key.
      if (up.disabled) up.removeAttribute('data-f');
      if (down.disabled) down.removeAttribute('data-f');
      up.onclick = () => move(c, -1);
      down.onclick = () => move(c, 1);

      const renameBtn = el('button', {
        class: 'btn ghost', type: 'button', text: 'Rename',
        'aria-label': `Rename ${c.title || c.id}`,
      });
      focus.focusable(renameBtn);
      renameBtn.onclick = () => rename(c);

      const del = el('button', {
        class: 'round', type: 'button', 'aria-label': `Delete ${c.title || c.id}`,
        title: 'Delete this category',
      }, icon('trash'));
      focus.focusable(del);
      del.onclick = () => remove(c);

      row.appendChild(sw);
      row.appendChild(up);
      row.appendChild(down);
      row.appendChild(renameBtn);
      row.appendChild(del);
      list.appendChild(row);
    });
  }

  function paint() {
    paintBuilder();
    paintList();
    const shown = categories.filter((c) => c.enabled !== false).length;
    clear(foot);
    foot.appendChild(el('span', {
      class: 'note',
      text: categories.length
        ? `${shown} of ${categories.length} on Home · categories appear after the hub's own rows, in this order.`
        : 'Categories are saved on this hub, not in this browser.',
    }));
  }

  (async () => {
    try {
      const s = await api.settings();
      const setting = (s && s.settings) || s || {};
      categories = Array.isArray(setting.categories) ? setting.categories.map((c) => Object.assign({}, c)) : [];
      options = Array.isArray(s && s.catalogOptions) ? s.catalogOptions : [];
      paint();
      focus.focusDefault(builder.querySelector('.chip') || list.querySelector('.sw'));
    } catch (e) {
      head.appendChild(el('p', {
        class: 'note',
        text: 'The hub did not answer. Try again from Settings.',
      }));
    }
  })();

  root.__defaultFocus = () => builder.querySelector('.chip') || list.querySelector('.sw');
  root.__dispose = () => {};
  return root;
}
