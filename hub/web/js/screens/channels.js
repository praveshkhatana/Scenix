/**
 * js/screens/channels.js — the owner's control over Live TV.
 *
 * The playlist is a published list of hundreds of feeds. A household does not want all of them: it
 * wants the dozen it actually watches, in its own order, and it wants to be able to take a dead or
 * unwanted group out without editing a file on the box. This screen is that control.
 *
 * Three decisions live here and nowhere else:
 *   · a group is on or off (SD, News, Music …);
 *   · a channel is on or off inside a group it has not switched off;
 *   · the order the remote zaps through is this list's order.
 *
 * Nothing is named in code: the groups and channels come from `/app/v1/settings` → `live`, which the
 * hub builds from the playlist it actually fetched. A playlist with different groups shows different
 * chips the next time this screen opens.
 */

import { el, clear, toast } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { icon } from '/ui/js/icons.js';
import { api } from '/ui/js/api.js';

const PAGE = 60;

export function screen(ctx) {
  const root = el('div', { class: 'screen channel-manager' });
  const head = el('div', { class: 'page-head' },
    el('h1', { text: 'Live TV' }),
    el('p', { class: 'sub', text: 'Choose the channels this house keeps, and the order the remote zaps through them. A hidden channel is not probed, searched or played.' }));
  root.appendChild(head);

  const bar = el('div', { class: 'chbar' });
  const list = el('div', { class: 'chlist', 'data-nav': 'v' });
  const foot = el('div', { class: 'chfoot' });
  root.appendChild(bar);
  root.appendChild(list);
  root.appendChild(foot);

  let channels = [];
  let groups = [];
  let hidden = new Set();
  let groupsHidden = new Set();
  let order = [];
  let activeGroup = '';
  let shown = PAGE;
  let busy = false;

  function ordered() {
    const rank = new Map(order.map((id, i) => [id, i]));
    return channels.slice().sort((a, b) => {
      const ar = rank.has(a.identity) ? rank.get(a.identity) : Number.MAX_SAFE_INTEGER;
      const br = rank.has(b.identity) ? rank.get(b.identity) : Number.MAX_SAFE_INTEGER;
      return ar - br || String(a.name).localeCompare(String(b.name));
    });
  }

  function visible() {
    let list = ordered();
    if (activeGroup) list = list.filter((c) => (c.groups || []).includes(activeGroup));
    return list;
  }

  function save(patch, note) {
    if (busy) return;
    busy = true;
    const live = Object.assign({}, {
      hidden: [...hidden], groupsHidden: [...groupsHidden], order,
    }, patch || {});
    api.saveSettings({ live })
      .then(() => { if (note) toast(note); })
      .catch(() => toast('The hub would not save that'))
      .finally(() => { busy = false; });
  }

  function groupChip(label, id) {
    const on = id ? !groupsHidden.has(id) : groupsHidden.size === 0;
    const b = el('button', {
      class: `chip${activeGroup === id ? ' on' : ''}${on ? '' : ' off'}`,
      type: 'button', 'aria-pressed': activeGroup === id ? 'true' : 'false',
      text: label,
    });
    b.onclick = () => {
      if (id && !activeGroup) {
        // A group chip with no filter selected is a switch; with a filter selected it is the filter.
      }
      if (activeGroup === id) { activeGroup = ''; paint(); return; }
      activeGroup = id;
      paint();
    };
    // A long-press is not a TV gesture: the group's own switch is the small pill beside the filter.
    focus.focusable(b);
    return b;
  }

  function paintBar() {
    clear(bar);
    const groupsRow = el('div', { class: 'chgroups' });
    groupsRow.appendChild(el('span', { class: 'chlabel', text: 'Groups' }));
    groupsRow.appendChild(groupChip('All', ''));
    for (const g of groups) groupsRow.appendChild(groupChip(g, g));
    bar.appendChild(groupsRow);

    const off = el('div', { class: 'chgroups' });
    off.appendChild(el('span', { class: 'chlabel', text: 'Group switches' }));
    for (const g of groups) {
      const on = !groupsHidden.has(g);
      const sw = el('button', {
        class: `sw${on ? ' on' : ''}`, type: 'button', role: 'switch',
        'aria-checked': on ? 'true' : 'false', 'aria-label': `${g} channels shown`,
        title: `${on ? 'Hide' : 'Show'} every ${g} channel`,
      }, el('i'));
      focus.focusable(sw);
      sw.onclick = () => {
        if (on) groupsHidden.add(g); else groupsHidden.delete(g);
        syncEnabled();
        save({}, `${g} ${on ? 'hidden' : 'shown'}`);
        paint();
      };
      off.appendChild(el('div', { class: 'chg' }, el('span', { text: g }), sw));
    }
    bar.appendChild(off);
  }

  function syncEnabled() {
    for (const c of channels) {
      c.enabled = !hidden.has(c.identity) && !(c.groups || []).some((g) => groupsHidden.has(g));
    }
  }

  function move(c, delta) {
    const seq = ordered().map((x) => x.identity);
    const at = seq.indexOf(c.identity);
    const to = at + delta;
    if (at < 0 || to < 0 || to >= seq.length) return;
    seq.splice(at, 1);
    seq.splice(to, 0, c.identity);
    order = seq;
    save({}, `${c.name} moved`);
    paint();
  }

  function paintList() {
    clear(list);
    const all = visible();
    const slice = all.slice(0, shown);
    for (const c of slice) {
      const on = c.enabled !== false;
      const row = el('div', {
        class: `chrow${on ? '' : ' off'}`, dataset: { identity: c.identity },
      });
      const num = el('span', { class: 'chno', text: c.number ? String(c.number) : '·' });
      const logo = el('span', { class: 'chlogo' });
      if (c.logo) logo.appendChild(el('img', { src: c.logo, alt: '', loading: 'lazy', decoding: 'async' }));
      const name = el('div', { class: 'chname' },
        el('b', { text: c.name }),
        el('span', { text: (c.groups || []).slice(0, 2).join(' · ') || c.group || '' }));
      const sw = el('button', {
        class: `sw${on ? ' on' : ''}`, type: 'button', role: 'switch',
        'aria-checked': on ? 'true' : 'false', 'aria-label': `${c.name} shown`,
      }, el('i'));
      focus.focusable(sw);
      sw.onclick = () => {
        if (on) hidden.add(c.identity); else hidden.delete(c.identity);
        c.enabled = !on;
        save({}, `${c.name} ${on ? 'hidden' : 'shown'}`);
        paintList();
      };
      const up = el('button', { class: 'round', type: 'button', 'aria-label': `Move ${c.name} earlier` }, icon('chevUp'));
      const down = el('button', { class: 'round', type: 'button', 'aria-label': `Move ${c.name} later` }, icon('chevDown'));
      focus.focusable(up); focus.focusable(down);
      up.onclick = () => move(c, -1);
      down.onclick = () => move(c, 1);
      row.appendChild(num); row.appendChild(logo); row.appendChild(name);
      row.appendChild(sw); row.appendChild(up); row.appendChild(down);
      list.appendChild(row);
    }
    clear(foot);
    const hiddenCount = channels.filter((c) => c.enabled === false).length;
    foot.appendChild(el('span', { class: 'note',
      text: `${all.length} channel${all.length === 1 ? '' : 's'} here · ${hiddenCount} hidden · ${channels.length} in the playlist` }));
    if (all.length > shown) {
      const more = el('button', { class: 'btn ghost', type: 'button', text: `Show ${Math.min(PAGE, all.length - shown)} more` });
      focus.focusable(more);
      more.onclick = () => { shown += PAGE; paintList(); };
      foot.appendChild(more);
    }
  }

  function paint() { paintBar(); paintList(); }

  (async () => {
    try {
      const s = await api.settings();
      const setting = (s && s.settings) || s || {};
      const live = setting.live || {};
      const liveInfo = (s && s.live) || {};
      hidden = new Set(live.hidden || []);
      groupsHidden = new Set(live.groupsHidden || []);
      order = (live.order || []).slice();
      channels = (liveInfo.channels || []).map((c) => Object.assign({}, c));
      groups = (liveInfo.groups || []).slice();
      if (!channels.length) {
        head.appendChild(el('p', { class: 'note',
          text: 'This hub has not published a live playlist yet. Add one under Settings → This hub, then come back.' }));
      }
      syncEnabled();
      paint();
      focus.focusDefault(bar.querySelector('.chip'));
    } catch (e) {
      head.appendChild(el('p', { class: 'note', text: 'The hub did not answer. Try again from Settings.' }));
    }
  })();

  root.__defaultFocus = () => bar.querySelector('.chip') || list.querySelector('.sw');
  root.__dispose = () => {};
  return root;
}
