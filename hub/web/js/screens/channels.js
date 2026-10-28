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
import { api } from '/ui/js/api.js';
import { makeRows } from '/ui/js/rows.js';

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
  const rows = makeRows(ctx);

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

  /* The controls are rows in the voice the rest of the app uses — one ring stop per row. The filter is
     a dial (the whole playlist, or one group at a time), and the group switches are a checklist that
     saves at once: walking past a wrong group costs nothing until OK. After every commit the ring goes
     back to the row you were on, because a closed dialog on a TV must not leave the screen dark. */
  function ringOnRow(i) {
    const r = [...list.querySelectorAll('.li')];
    if (!r.length) return;
    focus.focusDefault(r[Math.max(0, Math.min(i, r.length - 1))]);
  }
  function paintBar() {
    clear(bar);
    if (!channels.length) return;
    const browseRow = rows.pickRow(bar, 'Browse', 'the whole playlist, or one group at a time',
      ['', ...groups], activeGroup, (g) => (g === '' ? 'All groups' : g),
      (g) => { activeGroup = g; paintList(); focus.focusDefault(browseRow); });
    if (groups.length) {
      const shownGroups = groups.filter((g) => !groupsHidden.has(g));
      const switchRow = rows.multiRow(bar, 'Group switches', 'take a whole group out of the zapping order at once',
        groups, shownGroups, (g) => g, () => {
          groupsHidden = new Set(groups.filter((g) => !shownGroups.includes(g)));
          syncEnabled();
          save({}, 'group switches saved');
          paintList();
          focus.focusDefault(switchRow);
        });
    }
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
      const note = [c.number ? `#${c.number}` : '', (c.groups || []).slice(0, 2).join(' · ') || c.group || '']
        .filter(Boolean).join(' · ');
      // The whole row is one control; OK asks what to do with it — hide it, or move it in the zapping
      // order. The old corner switch and the two round steppers were three ring stops for one decision.
      const b = rows.actionRow(list, c.name, note, {
        valueText: on ? 'Shown' : 'Hidden',
        actions: () => {
          const live = c.enabled !== false;
          const backToRow = () => ringOnRow(visible().indexOf(c));
          return [
            { label: live ? 'Hide this channel' : 'Show this channel', run: () => {
                if (live) hidden.add(c.identity); else hidden.delete(c.identity);
                c.enabled = !live;
                save({}, `${c.name} ${live ? 'hidden' : 'shown'}`);
                paintList();
                backToRow();
              } },
            { label: 'Move earlier', run: () => { move(c, -1); backToRow(); } },
            { label: 'Move later', run: () => { move(c, 1); backToRow(); } },
          ];
        },
      });
      if (c.logo) {
        const logo = el('span', { class: 'chlogo' },
          el('img', { src: c.logo, alt: '', loading: 'lazy', decoding: 'async' }));
        b.insertBefore(logo, b.firstChild);
      }
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
      focus.focusDefault(bar.querySelector('.li') || list.querySelector('.li'));
    } catch (e) {
      head.appendChild(el('p', { class: 'note', text: 'The hub did not answer. Try again from Settings.' }));
    }
  })();

  root.__defaultFocus = () => bar.querySelector('.li') || list.querySelector('.li');
  root.__dispose = () => {};
  return root;
}
