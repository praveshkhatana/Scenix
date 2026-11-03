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
import { api } from '/ui/js/api.js';
import { makeRows } from '/ui/js/rows.js';

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
  const rows = makeRows(ctx);

  /* ------------------------------------------------------------------ choices */

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
    return save(categories.concat([entry]), `${entry.title} added to Home`);
  }

  function toggle(category) {
    const on = category.enabled !== false;
    const next = categories.map((c) => (c.id === category.id
      ? Object.assign({}, c, { enabled: !on }) : c));
    return save(next, `${category.title} ${on ? 'hidden from Home' : 'shown on Home'}`);
  }

  function move(category, delta) {
    const at = categories.findIndex((c) => c.id === category.id);
    const to = at + delta;
    if (at < 0 || to < 0 || to >= categories.length) return;
    const next = categories.slice();
    next.splice(at, 1);
    next.splice(to, 0, category);
    return save(renumber(next), `${category.title} moved`);
  }

  function remove(category) {
    const next = categories.filter((c) => c.id !== category.id);
    return save(renumber(next), `${category.title} removed`);
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
    return save(next, 'Category renamed');
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

    /* The add ladder is three dials stacked — source, catalogue, genre — one ring stop each, so it
       reads like every other choice in the app: the line names what it is and what stands picked, OK
       opens the wheel, OK again commits. Turning a source re-fills the catalogue under it and the ring
       steps down to that next line instead of going dark. */
    const stepTo = (i) => {
      paintBuilder();
      const r = builder.querySelectorAll('.li')[i];
      if (r) focus.focusDefault(r);
    };
    rows.pickRow(builder, 'Source', 'whose catalogue this shelf will read',
      ids, providerId, providerName, (id) => {
        providerId = id;
        catalogId = null;
        genre = '';
        stepTo(1);
      });
    const mine = options.filter((o) => o.provider === providerId);
    if (mine.length) {
      rows.pickRow(builder, 'Catalogue', 'which list inside it',
        mine.map((o) => o.catalog), catalogId,
        (cat) => catalogLabel(mine.find((x) => x.catalog === cat)),
        (cat) => {
          catalogId = cat;
          genre = '';
          stepTo(2);
        });
    }

    const opt = selectedOption();
    const genres = (opt && opt.genres) || [];
    const genreRow = genres.length;
    if (genreRow) {
      rows.pickRow(builder, 'Genre', 'leave it on Any genre to take the whole catalogue',
        ['', ...genres], genre, (g) => (g === '' ? 'Any genre' : g),
        (g) => {
          genre = g;
          stepTo(3);
        });
    }

    const parts = [providerName(providerId), opt ? catalogLabel(opt) : catalogId];
    if (genre) parts.push(genre);
    rows.openRow(builder, 'Add this category', parts.filter(Boolean).join(' · ') || 'pick a source first', add);
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
    categories.forEach((c) => {
      const opt = optionFor(c);
      const bits = [opt ? opt.providerName : c.provider, opt ? catalogLabel(opt) : c.catalog];
      if (c.genre) bits.push(c.genre);
      if (!opt) bits.push('not published right now');
      // One row, one ring stop; OK asks what to do with the shelf. The switch, the two arrows, Rename
      // and Delete used to be five stops per row. Backing out of the sheet changes nothing.
      rows.actionRow(list, c.title || c.genre || c.catalog, bits.filter(Boolean).join(' · '), {
        valueText: c.enabled !== false ? 'On Home' : 'Hidden',
        actions: () => {
          const on = c.enabled !== false;
          const at = categories.findIndex((x) => x.id === c.id);
          const acts = [{ label: on ? 'Hide this shelf' : 'Show this shelf on Home', run: async () => { await toggle(c); ringTo(c); } }];
          if (at > 0) acts.push({ label: 'Move earlier', run: async () => { await move(c, -1); ringTo(c); } });
          if (at >= 0 && at < categories.length - 1) acts.push({ label: 'Move later', run: async () => { await move(c, 1); ringTo(c); } });
          acts.push({ label: 'Rename', run: async () => { await rename(c); ringTo(c); } });
          acts.push({ label: 'Delete', danger: true, run: () => { remove(c); } });
          return acts;
        },
      });
    });
  }

  /** After a shelf's action redraws the list, find that shelf again and put the ring back on it. */
  function ringTo(c) {
    const i = categories.findIndex((x) => x.id === c.id);
    const r = [...list.querySelectorAll('.li')][Math.max(0, i)];
    if (r) focus.focusDefault(r);
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
      focus.focusDefault(builder.querySelector('.li') || list.querySelector('.li'));
    } catch (e) {
      head.appendChild(el('p', {
        class: 'note',
        text: 'The hub did not answer. Try again from Settings.',
      }));
    }
  })();

  root.__defaultFocus = () => builder.querySelector('.li') || list.querySelector('.li');
  root.__dispose = () => {};
  return root;
}
