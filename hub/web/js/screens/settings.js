/**
 * js/screens/settings.js — this hub's own configuration, drawn from what the hub actually reports.
 *
 * Two rules keep this page from becoming a wall of controls that do nothing:
 *
 *   · Every value is read from `/app/v1/settings` or `/app/v1/prefs`, and every control writes back through
 *     the same endpoint. Nothing here carries a default of its own — if the hub has no value for something,
 *     the row does not appear. A section whose hub answered nothing is not drawn in the rail at all.
 *   · A row is only editable when the manifest says the browser may change it (`settingsAdmin`, `prefsEdit`,
 *     `addonAdmin`, `profileEdit`, `cacheAdmin`). Values that would need a sentence typed — a playlist URL,
 *     an addon path — are shown rather than edited, with a line naming where they belong. A remote and a
 *     touch screen can both move a row; neither should be offered a phantom text field.
 *
 * The page is two panes, the way a TV settings menu has to be. A long scrolling page makes a remote walk
 * past forty rows to reach the one group it came for; here the left rail is the section list — one item
 * per section the hub can actually answer for — and the right pane shows exactly one section at a time,
 * its rows full-width with the value on the right. Up/Down walks the rail or the pane, Left/Right crosses
 * between them, and Back unwinds to the first section before it leaves the screen.
 *
 * The row IS the control, which is what makes it usable from a couch:
 *
 *   · Every setting is one full-width list item showing its name and its current value. The ring selects
 *     the whole row, not a small button parked at its edge — so the only aim a remote needs is up and down.
 *   · OK on a value row (a number, a quality, a provider) opens a big dial: the value huge in the middle,
 *     an arrow above and below. Up and Down on the remote move it, OK saves, Back cancels without writing.
 *   · OK on a choice-with-several (languages) opens a checklist; Save commits, Back discards.
 *   · OK on a row that is one switch flips it where it stands — no modal to walk into and out of.
 *   · OK on a row that has more than one thing that could be done to it (an add-on, a profile, a home row)
 *     opens a short list of those actions, with the destructive one drawn in red and Cancel last.
 *   · A single verb row (clear the cache, open the channel manager) runs straight on OK.
 *
 * The language list is the hub's own (`languageNames`, keyed by the codes it uses), so this page never hardcodes
 * which languages a household watches, and the quality steps come from `prefs.qualityLevels`.
 */

import { el } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { icon } from '/ui/js/icons.js';
import { api, apiBase } from '/ui/js/api.js';
import { backendName } from '/ui/js/player.js';
import { session, isLocked } from '/ui/js/state.js';
import { langList } from '/ui/js/format.js';
import { withAdultProof } from '/ui/js/profileedit.js';
import { pushHandler, popHandler } from '/ui/js/input.js';

export function screen(ctx) {
  const root = el('div', { class: 'screen settings' });
  const profile = ctx.profile || {};
  const manifest = ctx.manifest() || {};
  const caps = manifest.capabilities || {};

  let setting = null;      // /app/v1/settings → settings
  let names = {};          // /app/v1/settings → languageNames, supplied by the hub
  let prefs = null;        // /app/v1/prefs → prefs
  let installed = [];      // /app/v1/addons → addons
  let rowList = null;      // /app/v1/settings → rows, when this backend has any to offer
  let liveInfo = null;     // /app/v1/settings → live {channels, groups}, the channel manager's data
  let catalogOptions = null; // /app/v1/settings → catalogOptions, the material for owner-made categories
  let debridInfo = null;   // /app/v1/settings → debrid status: provider list, tokenSet, never the token
  let busy = false;

  let sections = [];       // the rail: one entry per section this hub can answer for, built on every paint
  let current = 0;         // which section the pane is showing
  let diagOpen = false;    // the System pane's advanced step; the readout costs a walk to reach otherwise
  let handler = null;      // the Back key, while this screen holds the stage

  root.appendChild(el('div', { class: 'page-head' },
    el('h1', { text: 'Settings' }),
    el('div', { class: 'sub', text: 'What your hub is configured to do, and what this profile asks for inside it.' })));

  // The rail never scrolls away with its section: the two panes are columns of a grid that fills the
  // screen, and only the right pane scrolls when a section runs long. The ring enters on the rail, so
  // the first key a viewer presses is Down through the list of things they came to change.
  const rail = el('div', { class: 'settings-rail', 'data-nav': 'v' });
  const pane = el('div', { class: 'settings-pane' });
  root.appendChild(el('div', { class: 'settings-shell' }, rail, pane));
  pane.appendChild(el('div', { class: 'row-err' }, el('span', { text: 'Reading this hub’s configuration…' })));

  // The rail is a selector, not a queue: the pane must always answer to the name the ring is on, or
  // walking Down past "Live TV" while Playback rows sit under it reads as a broken page. So the section
  // switches the moment the ring lands on a rail item — the same rule every TV settings menu follows,
  // and why the ring never waits for an Enter the viewer has no reason to press.
  rail.addEventListener('focusin', (e) => {
    const b = e.target && e.target.closest ? e.target.closest('.sitem') : null;
    if (!b) return;
    const i = Array.prototype.indexOf.call(rail.children, b);
    if (i >= 0 && i !== current) selectSection(i);
  });

  /* ------------------------------------------------------------------- parts */

  function group(title, note) {
    const g = el('div', { class: 'sgroup' }, el('h3', { text: title }));
    if (note) g.appendChild(el('p', { class: 'note', text: note }));
    return g;
  }
  // A row is its own nav band, walked horizontally: Left/Right moves across its controls, and every
  // Up/Down leaves the band for the row above or below (bandStep walks bands in document order). That
  // keeps the ring from drifting along a chip line or leaking into the rail column.
  function line(label, note) {
    const li = el('div', { class: 'li', 'data-nav': 'h' },
      el('div', {}, el('span', { text: label }), note ? el('span', { class: 'note', text: note }) : null));
    return li;
  }
  function value(text) { return el('span', { class: 'lv', text: String(text == null ? '—' : text) }); }

  /**
   * The whole list item as one focusable button: name on the left, current value on the right, a chevron
   * saying "OK opens something". One ring stop per row is the point — a remote aims at words, not at a
   * 64-pixel switch parked in their corner.
   */
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

  /**
   * One value out of a fixed list — every number setting, the quality cap, the debrid provider.
   * The row shows what it is; OK opens the dial the remote actually turns: the value large in the
   * middle, Up/Down on the remote (or the arrows under the ring) moving it, OK saving, Back cancelling.
   * Nothing is written until OK, so walking past a wrong value costs nothing.
   */
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
      };
      const move = (d) => {
        const j = Math.min(values.length - 1, Math.max(0, i + d));
        if (j === i) return;
        i = j;
        paintVal();
      };
      const commit = () => {
        ctx.closeModals();
        if (b.__lv) b.__lv.textContent = show(values[i]);
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

  /**
   * Several choices at once — the languages this profile expects. The row says how many are on;
   * OK opens a checklist of full-width rows whose switches flip under the ring, and one Save button
   * at the bottom commits them together. Back leaves the row untouched, so a mistap mid-list is free.
   */
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
          if (b.__lv) b.__lv.textContent = summary();
          write(chosen.slice());
        }; focus.focusable(s); return s; })()));
      ctx.mountModal(box, null);
      focus.focusDefault(list.querySelector('[data-f]'));
    };
    parent.appendChild(b);
    return b;
  }

  /**
   * A row more than one thing could be done to — an add-on (on/off, remove), a profile (kids, PIN,
   * remove), a home shelf (show/hide, move). Pressing OK on the row asks which, with the destructive
   * action in red and Cancel last. Back closes it having changed nothing.
   */
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
      if (!write) return ctx.toast('This hub is read-only from a browser');
      const next = !sw.classList.contains('on');
      sw.classList.toggle('on', next);
      b.setAttribute('aria-checked', next ? 'true' : 'false');
      await write(next);
    };
    parent.appendChild(b);
  }

  /* ------------------------------------------------------------------ writes */

  async function writePrefs(patch) {
    if (busy) return;
    busy = true;
    let ok = true;
    try {
      // Turning the dark-red zone on is an adult act on this hub, not a checkbox a child can flip.
      // The hub is asked first; only its refusal makes us ask for an adult PIN, and the same call is
      // repeated with the proof. Every other preference is a normal write.
      if (patch.xxxEnabled === true) {
        const out = await withAdultProof(ctx, 'turn adult titles on',
          (extra) => api.savePrefs(profile.id, Object.assign({}, prefs, patch, extra)));
        ok = out !== null;
      } else {
        await api.savePrefs(profile.id, Object.assign({}, prefs, patch));
      }
      if (ok) { Object.assign(prefs, patch); ctx.toast('Saved for this profile'); }
    } catch (e) {
      ok = false;
      ctx.toast(e && e.status === 403 ? 'An adult PIN is needed for that' : 'The hub would not take that change');
    }
    busy = false;
    if (!ok) ctx.reload();
  }
  async function writeSettings(patch) {
    if (busy) return;
    busy = true;
    const next = Object.assign({}, setting, patch);
    let ok = true;
    let saved = null;
    try { saved = await api.saveSettings({ settings: next }); }
    catch (e) {
      // Older builds accept the object flat rather than wrapped in `settings`.
      try { saved = await api.saveSettings(next); } catch (e2) { ok = false; }
    }
    if (ok) {
      setting = next;
      // The PUT reply carries the resolver's fresh status (provider, tokenSet, note). Using it keeps the
      // explanation under the switch from saying "no token stored" after a token was just saved.
      if (saved && saved.debrid) debridInfo = saved.debrid;
      // A token is write-only. Once it has been stored, do not keep the secret in this page's cached
      // settings object: the next toggle would otherwise resend it, and the hub would answer with it in
      // every PUT if the page treated the reply as truth. Keep only "set" or "not set".
      const d = setting.debrid;
      if (d && typeof d === 'object' && Object.prototype.hasOwnProperty.call(d, 'token')) {
        const sent = d.token;
        const tokenSet = sent === null ? false
          : (typeof sent === 'string' && sent.trim() ? true : !!d.tokenSet);
        setting.debrid = Object.assign({}, d, { tokenSet, token: '' });
        if (debridInfo) debridInfo.tokenSet = tokenSet;
      }
      ctx.toast('Saved on the hub');
    } else ctx.toast('The hub would not take that change');
    busy = false;
  }

  /* --------------------------------------------------------------- the pane */

  /**
   * The pane holds one section, and a switch to another replaces its whole subtree. Replacing rather
   * than hiding keeps the focus engine's candidate list honest — rows from the other four sections are
   * not sitting off-screen for the ring to fall into — and the entrance animation is transform and
   * opacity only, the same language the rest of the product moves in.
   */
  function selectSection(i, { refocus = false } = {}) {
    current = i;
    for (let k = 0; k < rail.children.length; k++) rail.children[k].classList.toggle('on', k === i);
    // The section itself is not a nav band: a band that spans the whole pane walks members in document
    // order, which reads as Down moving sideways along a chip line. Each row carries its own small band
    // (`data-nav="h"` on the row button), and bandStep between those bands is the row-by-row walk the
    // remote needs.
    pane.textContent = '';
    const node = el('div', { class: 'settings-section' });
    sections[i].build(node);
    pane.appendChild(node);
    pane.scrollTop = 0;
    if (refocus) focus.focusDefault(rail.children[i]);
  }

  function paint() {
    sections = availableSections();
    if (!sections.length) sections = [{ id: 'system', icon: 'gear', title: 'System', build: buildSystem }];
    if (current >= sections.length) current = 0;

    rail.textContent = '';
    for (let i = 0; i < sections.length; i++) {
      const s = sections[i];
      const b = el('button', {
        class: `sitem${i === current ? ' on' : ''}`, type: 'button',
        role: 'tab', 'aria-selected': i === current ? 'true' : 'false',
        onclick: () => selectSection(i),
      }, icon(s.icon), el('span', { text: s.title }));
      focus.focusable(b);
      rail.appendChild(b);
    }
    selectSection(current);

    // A repaint throws away whatever control in the pane held the ring (a token button, a removed
    // add-on). Land it back on the rail item for the section that is showing, so the next key moves
    // somewhere visible instead of nowhere.
    const had = focus.getFocus();
    if (!had || !had.isConnected || !root.contains(had)) focus.focusDefault(rail.children[current]);
  }

  /** Sections appear only where the hub answered something. An empty rail item is a dead key. */
  function availableSections() {
    const canHub = !!caps.settingsAdmin;
    const list = [];
    if (prefs) list.push({ id: 'playback', icon: 'play', title: 'Playback', build: buildPlayback });
    if (setting && setting.live) list.push({ id: 'live', icon: 'tv', title: 'Live TV', build: buildLive });
    const hasRows = !!(rowList && rowList.length && caps.settingsAdmin);
    const hasCats = !!(setting && canHub && (Array.isArray(catalogOptions) || Array.isArray(setting.categories)));
    if (hasRows || hasCats) list.push({ id: 'home', icon: 'home', title: 'Home & categories', build: buildHome });
    if (caps.addonAdmin) list.push({ id: 'addons', icon: 'list', title: 'Add-ons', build: buildAddons });
    const hasHouse = !!caps.profileEdit || !!(setting && setting.debrid);
    if (hasHouse) list.push({ id: 'household', icon: 'user', title: 'Household', build: buildHousehold });
    list.push({ id: 'system', icon: 'gear', title: 'System', build: buildSystem });
    return list;
  }

  /* ------------------------------------------------------------------ paint */

  function buildPlayback(parent) {
    const canPrefs = !!caps.prefsEdit;
    if (!prefs) return;

    const g = group('Quality and size', 'Ceilings this profile asks the hub to keep to. They narrow which copies are offered, not what the hub can find.');
    if (Array.isArray(prefs.qualityLevels) && prefs.qualityLevels.length) {
      const words = { auto: 'Let the hub choose' };
      pickRow(g, 'Quality', 'The best copy the player is offered. The hub still drops to a smaller one if the line cannot carry it.',
        prefs.qualityLevels, prefs.qualityCap == null ? 'auto' : prefs.qualityCap,
        (o) => words[o] || o, canPrefs ? (v) => writePrefs({ qualityCap: v }) : null);
      if (!canPrefs) g.appendChild(el('p', { class: 'note', text: 'This hub lets a browser read its settings but not change them.' }));
    }
    if (Number.isFinite(Number(prefs.maxSizeGB))) {
      pickRow(g, 'Largest file', 'Anything bigger than this is left out of the copy list.',
        [0, 2, 4, 6, 8, 12, 16, 24, 40], Number(prefs.maxSizeGB), null,
        canPrefs ? (v) => writePrefs({ maxSizeGB: v }) : null,
        (v) => (v === 0 ? 'No cap' : `${v} GB`));
    }
    if (prefs.maxBitrateMbps !== undefined) {
      pickRow(g, 'Bitrate ceiling', 'A live or marginal connection stays watchable below this line.',
        [0, 3, 5, 8, 12, 20, 40], Number(prefs.maxBitrateMbps || 0), null,
        canPrefs ? (v) => writePrefs({ maxBitrateMbps: v }) : null,
        (v) => (v === 0 ? 'Unlimited' : `${v} Mbps`));
    }
    if (prefs.preferAdaptive !== undefined) {
      toggle(g, 'Prefer a stream that adapts', 'Keep watching through a slow network by changing quality mid-playback, instead of buffering.',
        !!prefs.preferAdaptive, canPrefs ? (v) => writePrefs({ preferAdaptive: v }) : null);
    }
    parent.appendChild(g);

    /* The hub answers with both the modern and the legacy code for a few languages. Two entries that read
       "French" are one choice to a viewer, so one per name is kept — preferring the code this profile
       already uses, which is the one the hub will keep answering in. */
    const chosen = [].concat(prefs.audioLanguages || [], prefs.subtitleLanguages || []);
    const byLabel = new Map();
    for (const k of Object.keys(names)) {
      const label = names[k] || k;
      const held = byLabel.get(label);
      if (!held) byLabel.set(label, k);
      else if (!chosen.includes(held) && chosen.includes(k)) byLabel.set(label, k);
    }
    const langKeys = Array.from(byLabel.values());
    if (langKeys.length) {
      const g2 = group('Your audio and subtitles', 'Only the codes your hub already recognises are offered. On the TV the player draws the subtitles itself; these choices decide which tracks the hub goes looking for.');
      multiRow(g2, 'Audio you expect', 'Which audio languages to look for first.',
        langKeys, prefs.audioLanguages || [], (k) => names[k] || k,
        canPrefs ? (list) => writePrefs({ audioLanguages: list }) : null);
      multiRow(g2, 'Subtitles you expect', 'Which subtitle languages to fetch when a copy has no matching audio.',
        langKeys, prefs.subtitleLanguages || [], (k) => names[k] || k,
        canPrefs ? (list) => writePrefs({ subtitleLanguages: list }) : null);
      if (prefs.xxxEnabled !== undefined) {
        toggle(g2, 'Include adult titles', 'Off keeps them out of the shelves and out of search for this profile.',
          !!prefs.xxxEnabled, canPrefs ? (v) => writePrefs({ xxxEnabled: v }) : null);
      }
      parent.appendChild(g2);
    }

    // The two things a hub can do about a picture that stops, in the household's own words: hold a
    // window of the film in memory so a bursty torrent still plays, and refuse a copy the people
    // sharing it cannot carry. Everything here is the owner's call, and the hub takes a change without
    // a restart.
    if (setting && setting.playback) {
      const pb = setting.playback;
      const g3 = group('Playback smoothness',
        'What the hub does with the bytes of a film so it does not stop halfway. Saved the moment it is changed.');
      toggle(g3, 'Hold a window of the film in memory',
        pb.readAhead
          ? 'The hub reads ahead of the player into its own memory, so a torrent that bursts and pauses still plays like a file on a server.'
          : 'Off: bytes go straight from the torrent to the player, which is what it did before — a quiet swarm shows up as a frozen picture.',
        pb.readAhead !== false,
        (v) => writeSettings({ playback: Object.assign({}, pb, { readAhead: v }) }));
      pickRow(g3, 'How much of the film to hold',
        'The cushion in the hub. Bigger survives a longer pause in the swarm, and costs this box that much memory.',
        [32, 64, 96, 128, 256], Number(pb.readAheadMB), null,
        (v) => writeSettings({ playback: Object.assign({}, pb, { readAheadMB: v }) }),
        (v) => `${v} MB`);
      pickRow(g3, '…or how many seconds of picture',
        'The same cushion in seconds of this film, whichever is smaller. A big 4K copy gets a shorter window than a small one.',
        [30, 60, 90, 120, 180], Number(pb.readAheadSec), null,
        (v) => writeSettings({ playback: Object.assign({}, pb, { readAheadSec: v }) }),
        (v) => `${v} s`);
      pickRow(g3, 'Buffer before the first frame',
        'How much picture the hub reads before it starts the film. More means a slower start and a smoother ride.',
        [0, 5, 10, 15, 25, 40], Number(pb.preBufferSec), null,
        (v) => writeSettings({ playback: Object.assign({}, pb, { preBufferSec: v }) }),
        (v) => (v === 0 ? 'Start at once' : `${v} s`));
      pickRow(g3, 'How long to test a copy’s speed',
        'When Play is pressed the hub reads the copy for this long and takes its worst moment, so a copy that cannot carry its own picture is not chosen.',
        [2000, 4000, 6000, 10000], Number(pb.sustainBudgetMs), null,
        (v) => writeSettings({ playback: Object.assign({}, pb, { sustainBudgetMs: v }) }),
        (v) => `${(v / 1000).toFixed(1)} s`);
      pickRow(g3, 'Move to a smaller copy after',
        'How many slow stretches in a row before the hub switches the viewer to another copy without asking.',
        [1, 2, 3, 4], Number(pb.starvedWindows), null,
        (v) => writeSettings({ playback: Object.assign({}, pb, { starvedWindows: v }) }),
        (v) => (v === 1 ? 'One slow stretch' : `${v} slow stretches`));
      parent.appendChild(g3);
    }
  }

  function buildLive(parent) {
    const canHub = !!caps.settingsAdmin;
    if (!setting || !setting.live) return;
    const g = group('Live TV', 'The channels this hub keeps, in the order the remote zaps through them. Hiding a channel stops it being probed, searched or played — the playlist is untouched.');
    openRow(g, 'Live TV playlist',
      'The M3U address this hub reads. Type it in; the channel manager chooses which of its channels this house keeps.',
      async () => {
        const url = await ctx.enterText({
          title: 'Live TV playlist',
          reason: 'An M3U address. The hub reads it, then the channel manager lets you choose what to keep.',
          placeholder: 'https://example.org/playlist.m3u',
          initial: setting.live.playlist || '',
          action: 'Save',
          validate: (v) => (!v ? 'Type an address first.'
            : !/^https?:\/\//i.test(v) ? 'That has to start with http:// or https://.' : null),
        });
        if (url === null) return;
        await writeSettings({ live: Object.assign({}, setting.live, { playlist: url }) });
        paint();
      },
      { valueText: canHub ? (setting.live.playlist || 'not set') : (setting.live.playlist || 'no playlist set') });

    // A channel line-up is hundreds of rows. It gets its own screen rather than a wall of switches here:
    // the thing an owner needs to see is the list, its order and what is on, not a summary of counts.
    if (liveInfo || setting.live.playlist) {
      const counts = liveInfo && Array.isArray(liveInfo.channels)
        ? `${liveInfo.channels.filter((c) => c.enabled !== false).length} shown of ${liveInfo.channels.length} published`
        : (setting.live.playlist ? 'playlist loaded' : 'no playlist yet');
      openRow(g, 'Channel line-up',
        'Every channel the playlist publishes, in zap order. Switching one off hides it without touching the playlist.',
        () => ctx.navigate('channels'),
        { valueText: counts });
    }
    if (!setting.live.playlist) {
      g.appendChild(el('p', { class: 'note', text: 'Set a playlist address first: Settings → Live TV → Live TV playlist.' }));
    }
    parent.appendChild(g);
  }

  function buildHome(parent) {
    const canHub = !!caps.settingsAdmin;

    // Which shelves a home screen shows is the owner's call, not the hub's: one household wants a live
    // TV row, another never watches television and would rather it were not there at all. A backend that
    // hosts no rows (a standalone app with nothing installed yet) reports none, and this whole section
    // stays off the page rather than showing an empty list of choices.
    if (rowList && rowList.length && caps.settingsAdmin) {
      const g = group('Home rows', 'Turn a shelf off and it stops appearing on the home screen for everyone. The order below is the order the home screen draws them in.');
      const hidden = new Set(((setting.rows || {}).hidden) || []);
      const named = ((setting.rows || {}).order) || [];
      const orderOf = () => {
        const listed = named.filter((id) => rowList.some((r) => r.id === id));
        return [...listed, ...rowList.map((r) => r.id).filter((id) => !listed.includes(id))];
      };
      const writeRows = (patch) => writeSettings({ rows: Object.assign({}, setting.rows, patch) });

      for (const id of orderOf()) {
        const row = rowList.find((r) => r.id === id);
        if (!row) continue;
        const title = row.title || row.id;
        actionRow(g, title,
          row.scope === 'all' ? 'Shown to every profile'
            : row.scope === 'kids' ? 'Kids profiles only' : 'Adult profiles only',
          {
            valueText: hidden.has(row.id) ? 'Hidden' : 'On Home',
            actions: () => {
              const seq = orderOf();
              const at = seq.indexOf(row.id);
              const acts = [{
                label: hidden.has(row.id) ? `Show ${title} on Home` : `Hide ${title} from Home`,
                run: async () => {
                  if (hidden.has(row.id)) hidden.delete(row.id); else hidden.add(row.id);
                  await writeRows({ hidden: [...hidden] });
                  paint();
                },
              }];
              if (at > 0) acts.push({
                label: `Move ${title} earlier`,
                run: async () => {
                  const to = seq.slice();
                  to.splice(at, 1); to.splice(at - 1, 0, row.id);
                  await writeRows({ order: to });
                  paint();
                },
              });
              if (at >= 0 && at < seq.length - 1) acts.push({
                label: `Move ${title} later`,
                run: async () => {
                  const to = seq.slice();
                  to.splice(at, 1); to.splice(at + 1, 0, row.id);
                  await writeRows({ order: to });
                  paint();
                },
              });
              return acts;
            },
          });
      }
      parent.appendChild(g);
    }

    // A category is a shelf the owner makes out of a catalogue the hub already carries. It sits beside
    // Home rows because it is the same decision — what appears on the home screen — but a hundred genres
    // do not belong in a wall of switches here: the screen that can reorder and rename them is its own.
    if (setting && canHub && (Array.isArray(catalogOptions) || Array.isArray(setting.categories))) {
      const cats = Array.isArray(setting.categories) ? setting.categories : [];
      const opts = Array.isArray(catalogOptions) ? catalogOptions : [];
      const on = cats.filter((c) => c.enabled !== false).length;
      const g = group('Categories', 'Shelves built from a catalogue or genre, added by you and drawn under the home rows in your order.');
      openRow(g, 'Your categories',
        !cats.length ? 'None yet — add one from any catalogue this hub carries.'
          : `${on} shown of ${cats.length} · ${opts.length} catalogue${opts.length === 1 ? '' : 's'} to choose from`,
        () => ctx.navigate('categories'),
        { valueText: cats.length ? `${cats.length} made` : 'none yet' });
      if (!opts.length) {
        g.appendChild(el('p', { class: 'note', text: 'No add-on is publishing a catalogue right now. Categories you already made stay in the list; install or enable a source to add more.' }));
      }
      parent.appendChild(g);
    }
  }

  /* -------------------------------------------------------------- profiles */

  /* A PIN is typed the way every other PIN in this app is typed — on the remote's number row or the on-screen
     pad — and handed straight to the hub, which is the only place the number is ever kept. */
  /* The hub refuses to move a locked profile's settings without the number already on it, so this screen
     asks for that number and carries it along. Nothing here decides whether a PIN is right - the hub does,
     which is the only place the number is kept. */
  async function proofOfLock(p) {
    if (!isLocked(p)) return '';
    const current = await ctx.enterPin(p, `Enter ${p.name}'s current PIN`);
    if (!current) return null;
    try {
      const out = await api.unlockProfile(p.id, current);
      if (!out || !out.ok) { ctx.toast('That is not the current PIN'); return null; }
    } catch (e) { ctx.toast('That is not the current PIN'); return null; }
    return current;
  }

  function whyRefused(e, fallback) {
    return e && e.status === 403 ? 'That is not the current PIN' : fallback;
  }

  async function setPin(p) {
    const current = await proofOfLock(p);
    if (current === null) return;
    const digits = await ctx.enterPin(p, current ? 'Choose a new PIN' : 'Choose a PIN');
    if (!digits) return;
    try {
      await api.updateProfile(p.id, { pin: digits, pinRequired: true, pinCurrent: current });
      ctx.toast(`${p.name} now needs that PIN`);
      ctx.reload();
    } catch (e) { ctx.toast(whyRefused(e, 'The hub would not store that PIN')); }
  }

  async function clearPin(p) {
    const current = await proofOfLock(p);
    if (current === null) return;
    try {
      // The hub clears the number but leaves the requirement flag behind, which is how a profile ends up
      // "PIN wanted, none set" and a pad that can never be satisfied. Take both off in one request.
      await api.updateProfile(p.id, { pin: null, pinRequired: false, pinCurrent: current });
      ctx.toast(`${p.name} no longer needs a PIN`);
      ctx.reload();
    } catch (e) { ctx.toast(whyRefused(e, 'The hub would not remove the PIN')); }
  }

  function buildHousehold(parent) {
    if (caps.profileEdit) {
      const g = group('Profiles', 'Names and ages live in the hub’s profile file. A new profile starts with the house defaults and is renamed there, where a keyboard is comfortable.');
      for (const p of (session.profiles || [])) {
        const kids = p.kind === 'kids';
        const lockedNow = isLocked(p);
        actionRow(g, p.name,
          (lockedNow ? 'PIN set' : (p.pinRequired ? 'PIN wanted, none set' : ''))
          + (p.id === profile.id ? ' · in use now' : ''),
          {
            valueText: kids ? 'Kids' : 'Standard',
            actions: () => {
              const acts = [{
                label: kids ? `Make ${p.name} a standard profile` : `Turn Kids mode on for ${p.name}`,
                run: async () => {
                  // Flipping Kids mode on a profile that is behind a PIN moves the ceiling over everything
                  // that profile can see, so it is a gate change and needs the number.
                  const current = await proofOfLock(p);
                  if (current === null) return;
                  try {
                    await api.updateProfile(p.id, { kind: kids ? 'adult' : 'kids', pinCurrent: current });
                    ctx.toast(`${p.name} is now a ${kids ? 'standard' : 'kids'} profile`);
                    ctx.reload();
                  } catch (e) { ctx.toast(whyRefused(e, 'The hub would not change that profile')); }
                },
              }];
              acts.push({
                label: lockedNow ? `Change the PIN for ${p.name}` : `Set a PIN for ${p.name}`,
                run: () => setPin(p),
              });
              if (lockedNow) acts.push({ label: `Remove ${p.name}'s PIN`, run: () => clearPin(p) });
              if (p.id !== profile.id) acts.push({
                label: `Remove ${p.name}`,
                danger: true,
                run: async () => {
                  // The profile and its preferences go away; what has been watched stays on the hub.
                  try { await api.deleteProfile(p.id); ctx.toast(`${p.name} removed`); ctx.reload(); }
                  catch (e) { ctx.toast('The hub would not remove that profile'); }
                },
              });
              return acts;
            },
          });
      }
      openRow(g, 'Add profile', 'A new profile starts with the house defaults.', async () => {
        try {
          const r = await api.createProfile({ name: 'New profile', kind: 'adult' });
          ctx.toast(r && r.profile ? `${r.profile.name} added — give it a name in the hub’s profile file` : 'Profile added');
          ctx.reload();
        } catch (e) { ctx.toast('The hub would not add a profile'); }
      }, { valueText: null });
      parent.appendChild(g);
    }

    // Optional, off by default. This belongs with the household's other one-account-for-the-house things:
    // it is one debrid account shared by every profile. A token is typed through the same keyboard/remote
    // dialog as every other address in this interface, and is never drawn back: the row says set or not set.
    if (setting && setting.debrid) {
      const d = setting.debrid;
      const info = debridInfo || {};
      const list = Array.isArray(info.providers) && info.providers.length
        ? info.providers
        : [{ id: 'real-debrid', label: 'Real-Debrid' }];
      const only = list.length === 1 ? list[0] : null;
      const tokenSet = d.tokenSet === true || info.tokenSet === true;
      const gD = group('Debrid / direct',
        'Optional. With your own debrid account, the hub asks the service to turn a magnet or a file-host link into an ordinary HTTP stream, so it starts without a torrent engine. Off by default; with no token nothing changes.');
      toggle(gD, 'Use a debrid account',
        d.enabled
          ? 'On: the hub tries your provider before the torrent gateway and falls through if it cannot answer.'
          : 'Off: torrents use the gateway and host links play as they are.',
        !!d.enabled,
        canHubSetting() ? async (v) => {
          // Enabling an account with no provider chosen cannot work. With one adapter available, choose
          // it here so the switch is not a control that silently does nothing.
          const provider = d.provider || (only ? only.id : '');
          await writeSettings({ debrid: Object.assign({}, d, { enabled: v, provider }) });
          paint();
        } : null);
      pickRow(gD, 'Provider', only
        ? 'This build has one debrid adapter installed.'
        : 'Which service this hub asks first for magnets and host links.',
        list.map((x) => x.id), d.provider || (only ? only.id : ''),
        (id) => (list.find((x) => x.id === id) || {}).label || id,
        canHubSetting() ? (id) => writeSettings({ debrid: Object.assign({}, d, { provider: id }) }) : null);
      actionRow(gD, 'Account token',
        tokenSet
          ? 'A token is stored on this hub. It is never shown again or sent to a browser.'
          : 'No token stored. Paste the private API token from your provider account.',
        {
          valueText: tokenSet ? 'stored' : 'not set',
          actions: () => {
            const acts = [{
              label: tokenSet ? 'Change the token' : 'Set the token',
              run: async () => {
                const provider = list.find((p) => p.id === d.provider) || only;
                const token = await ctx.enterText({
                  title: 'Debrid token',
                  reason: 'The private API token from your ' + ((provider && provider.label) || 'debrid')
                    + ' account. It is kept in this hub’s settings file and only sent to that provider.',
                  placeholder: 'Paste the token',
                  initial: '',
                  action: 'Save',
                  validate: (v) => (!v ? 'Paste the token first.'
                    : v.length < 8 ? 'That looks too short to be an API token.' : null),
                });
                if (token === null) return;
                await writeSettings({ debrid: Object.assign({}, d, { token }) });
                paint();
              },
            }];
            if (tokenSet) acts.push({
              label: 'Remove the token', danger: true,
              run: async () => {
                // null is the explicit erase; an empty token from this screen would mean "keep it".
                await writeSettings({ debrid: Object.assign({}, d, { token: null }) });
                paint();
              },
            });
            return acts;
          },
        });
      if (d.enabled && info.note) gD.appendChild(el('p', { class: 'note', text: info.note }));
      if (info.last && info.last.ok === false && info.last.reason) {
        gD.appendChild(el('p', { class: 'note', text: 'Last attempt: ' + info.last.reason }));
      }
      parent.appendChild(gD);
    }
  }

  function canHubSetting() { return !!caps.settingsAdmin; }

  /* ----------------------------------------------------------------- add-ons */

  /* The registry is the hub's, so the page shows what it reports rather than assuming a fixed set: an
     owner installs Torrentio or a catalog on one box and every screen then has to render whatever that
     box now holds. A built-in row is listed but not removable; anything added over HTTP can be switched
     off without deleting it, which is the difference between "this source is down tonight" and "I never
     want this source again". On a remote, on/off and remove are two things that could be done to the
     same row, so the row opens a choice instead of parking two buttons on it. */
  function buildAddons(parent) {
    const g = group('Add-ons', 'Sources this hub reads. Switching one off keeps its address and settings; the hub simply stops asking it.');
    for (const ad of installed || []) {
      const cats = (ad.catalogs || []).length;
      const note = ad.native
        ? `Built in${cats ? ` · ${cats} catalogue${cats > 1 ? 's' : ''}` : ''}`
        : `${(ad.url || '').replace(/^https?:\/\//, '').slice(0, 46)}${cats ? ` · ${cats} catalogue${cats > 1 ? 's' : ''}` : ''}`;
      const name = ad.name || ad.id;
      actionRow(g, name, ad.error ? `Last error: ${String(ad.error).slice(0, 60)}` : note, {
        valueText: ad.enabled === false ? 'Off' : 'On',
        actions: () => {
          const on = ad.enabled !== false;
          const acts = [{
            label: on ? `Turn ${name} off` : `Turn ${name} on`,
            run: async () => {
              try { await api.setAddon(ad.id, !on); ctx.toast(`${name} ${on ? 'off' : 'on'}`); }
              catch (e) { ctx.toast('The hub would not change that add-on'); }
              paint();
            },
          }];
          if (!ad.native) acts.push({
            label: `Remove ${name}`, danger: true,
            run: async () => {
              try {
                const r = await api.removeAddon(ad.id);
                installed = (r && r.addons) || (installed || []).filter((o) => o.id !== ad.id);
                ctx.toast(`${name} removed`);
              } catch (e) { ctx.toast('The hub would not remove that add-on'); }
              paint();
            },
          });
          return acts;
        },
      });
    }
    openRow(g, 'Add another source',
      'Type the address of an add-on manifest. The hub reads it before keeping it, so a typo is refused here rather than emptying the home screen later.',
      async () => {
        const url = await ctx.enterText({
          title: 'Add an add-on',
          reason: 'The manifest address of a source, for example https://example.org/manifest.json. A folder address works too: the hub appends the manifest name.',
          placeholder: 'https://…/manifest.json',
          action: 'Add',
          validate: (v) => (!v ? 'Type an address first.'
            : !/^https?:\/\//i.test(v) ? 'That has to start with http:// or https://.' : null),
        });
        if (!url) return;
        try {
          const r = await api.addAddon(url);
          installed = (r && r.addons) || installed;
          ctx.toast(`${(r && r.addon && r.addon.name) || 'Add-on'} is now a source here`);
          paint();
        } catch (e) {
          ctx.toast((e && e.body && e.body.error) || 'The hub would not take that add-on');
        }
      }, { valueText: null });
    parent.appendChild(g);
  }

  function buildSystem(parent) {
    const canHub = !!caps.settingsAdmin;

    if (setting) {
      const g = group('This hub', 'Set once for the whole house. The values below are what the hub is running with.');
      const name = line('Name', 'The word that appears on the splash screen and in the launcher.');
      name.appendChild(value(setting.hubName));
      g.appendChild(name);
      if (Array.isArray(setting.languages)) {
        multiRow(g, 'Languages the addons search', 'Which audio and subtitle languages this hub asks its addons for.',
          Object.keys(names), setting.languages, (k) => names[k] || k,
          canHub ? (list) => writeSettings({ languages: list }) : null);
      }
      if (caps.addonAdmin && setting.addons) {
        pickRow(g, 'Copies per title', 'How many sources the hub is allowed to rank before it stops looking.',
          [1, 2, 4, 6, 8, 12, 16, 24], Number(setting.addons.maxSources), null,
          (v) => writeSettings({ addons: Object.assign({}, setting.addons, { maxSources: v }) }));
        pickRow(g, 'Smallest file worth offering', 'Below this the copy is usually unwatchable, so it is dropped.',
          [0, 50, 100, 200, 300, 500], Number(setting.addons.minSizeMB), null,
          (v) => writeSettings({ addons: Object.assign({}, setting.addons, { minSizeMB: v }) }),
          (v) => (v === 0 ? 'No floor' : `${v} MB`));
        if (setting.addons.sizeFilter) {
          const l = line('Size bands considered', 'Which ranges of file size the addons are asked for.');
          l.appendChild(value(setting.addons.sizeFilter));
          g.appendChild(l);
        }
      }
      if (caps.avatarFaces) {
        const base = (setting.avatar && setting.avatar.baseUrl) || '';
        const l = line('Generated faces', base
          ? 'The hub proxies the generator, so no browser on this network talks to a third party directly.'
          : 'Not configured — profiles fall back to the faces this hub already caches, and to Scenix’s own drawn faces.',
        );
        l.appendChild(value(base || 'not set'));
        g.appendChild(l);
      }
      if (caps.cacheAdmin) {
        openRow(g, 'Search and metadata cache',
          'Clearing it makes the hub ask its addons again on the next request. Nothing you have watched is touched.',
          async () => {
            ctx.toast('Clearing the cache…');
            try { await api.clearCache(); ctx.toast('Cache cleared'); }
            catch (e) { ctx.toast('The hub would not clear the cache'); }
          }, { valueText: null });
      }
      parent.appendChild(g);
    }

    /* Source packs: the owner's own rows, kept as descriptors in settings rather than as add-ons.
       A picture pack contributes copies from a URL template; an audio pack contributes audio-only rows
       the hub mixes into a picture that does not carry the household's language. The hub's code never
       learns the name of either host — it only reads what the owner typed here. */
    if (setting && caps.addonAdmin) {
      const packs = [
        {
          key: 'igc', title: 'IGC source pack',
          note: 'Your own picture sources. The address may contain {title}, {year}, {type}, {season}, {episode}, {identity} and {lang} — a template with {lang} becomes one row per language.',
          addLabel: 'Add a picture source',
          addNote: 'Type a name, an address, and the languages it carries. The hub lists it beside the copies from every other source; nothing about it is written into the app.',
          urlNote: 'The direct address of a copy, or a template like https://example.org/play?name={title}&year={year}&lang={lang}.',
        },
        {
          key: 'audio', title: 'Audio-only pack',
          note: 'Rows that carry only audio: a dub in another language, a separate commentary track. The hub mixes one of these into a picture that lacks your language; they are never offered as the picture themselves.',
          addLabel: 'Add an audio source',
          addNote: 'One language per row is the useful shape: the hub picks the row that matches the language you asked for.',
          urlNote: 'The address of the audio file or stream, or a template with {title}, {year} and {lang}.',
        },
      ];
      for (const pack of packs) {
        const g = group(pack.title, pack.note);
        const list = (((setting[pack.key] || {}).sources) || []).slice();
        const writePack = (next) => writeSettings({ [pack.key]: { sources: next } });
        const ask = (title, reason, initial, validate) => ctx.enterText({
          title, reason, placeholder: '', initial: initial || '', action: 'Save', validate,
        });
        for (const d of list) {
          const shown = d.name || d.id;
          const langs = (d.languages || []).map((k) => names[k] || k).join(', ');
          actionRow(g, shown, `${(d.url || '').slice(0, 46)}${langs ? ` · ${langs}` : ''}${d.type ? ` · ${d.type}` : ''}`, {
            valueText: d.enabled === false ? 'Off' : 'On',
            actions: () => {
              const on = d.enabled !== false;
              const acts = [{
                label: on ? `Turn ${shown} off` : `Turn ${shown} on`,
                run: async () => {
                  const next = list.map((x) => (x.id === d.id ? { ...x, enabled: !on } : x));
                  await writePack(next);
                  paint();
                },
              }, {
                label: 'Change the address',
                run: async () => {
                  const url = await ask('Source address',
                    'The address of this source. It may contain {title}, {year}, {type}, {season}, {episode}, {identity} and {lang}.',
                    d.url, (v) => (!v ? 'Type an address first.'
                      : !/^https?:\/\//i.test(v) ? 'That has to start with http:// or https://.' : null));
                  if (url === null) return;
                  await writePack(list.map((x) => (x.id === d.id ? { ...x, url } : x)));
                  paint();
                },
              }, {
                label: 'Change the languages',
                run: async () => {
                  const raw = await ask('Languages',
                    'Comma-separated codes, in the order you want them offered: hin, eng, pan…',
                    (d.languages || []).join(', '));
                  if (raw === null) return;
                  const languages = raw.split(/[,\s]+/).map((s) => s.trim().toLowerCase()).filter(Boolean);
                  await writePack(list.map((x) => (x.id === d.id ? { ...x, languages } : x)));
                  paint();
                },
              }, {
                label: `Remove ${shown}`, danger: true,
                run: async () => {
                  await writePack(list.filter((x) => x.id !== d.id));
                  paint();
                },
              }];
              return acts;
            },
          });
        }
        openRow(g, pack.addLabel, pack.addNote, async () => {
          const name = await ask('What to call it',
            'The words a viewer sees in the source list, for example a studio name or a quality band.',
            '', (v) => (v && v.trim() ? null : 'Type a name first.'));
          if (name === null) return;
          const url = await ask('Source address',
            pack.urlNote, '', (v) => (!v ? 'Type an address first.'
              : !/^https?:\/\//i.test(v) ? 'That has to start with http:// or https://.' : null));
          if (url === null) return;
          const langsRaw = await ask('Languages',
            'Comma-separated codes, in the order you want them offered: hin, eng, pan…',
            '', (v) => (v && v.trim() ? null : 'Leave empty if you do not know — the hub then treats it as unlabelled.'));
          const languages = (langsRaw || '').split(/[,\s]+/).map((s) => s.trim().toLowerCase()).filter(Boolean);
          const slug = String(name).trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40)
            || 'source';
          const id = `${slug}-${Date.now().toString(36)}`;
          await writePack([...list, { id, name: name.trim(), url: url.trim(), languages, enabled: true }]);
          ctx.toast(`${name.trim()} added`);
          paint();
        }, { valueText: null });
        parent.appendChild(g);
      }
    }

    // The readout is for whoever fixes the box, not for whoever watches: it stays one deliberate step
    // behind a switch, so a viewer walking the pane never reads a wall of capability jargon.
    toggle(parent, 'Show advanced', 'What this hub reports about itself right now: the address it was reached on, what it can do, and what it last tried.',
      diagOpen, async (v) => {
        diagOpen = v;
        if (v) {
          const d = buildDiag();
          // Re-insert after the row this toggle lives in: the row is the whole list item now.
          const at = focus.getFocus();
          const holder = at && at.closest ? at.closest('.li') : null;
          if (holder) holder.insertAdjacentElement('afterend', d);
          else parent.appendChild(d);
          // The pane's only scroll is the one the ring makes, and the readout has no controls to walk
          // onto — so the act of opening it is what pulls its bottom edge into view. Instant, like every
          // other scroll the remote causes.
          d.scrollIntoView({ block: 'end', behavior: 'auto' });
        } else {
          const d = parent.querySelector('.diag');
          if (d) d.remove();
        }
      });
  }

  function buildDiag() {
    const canPrefs = !!caps.prefsEdit;
    const canHub = !!caps.settingsAdmin;
    const d = el('div', { class: 'diag' }, el('h4', { text: 'Diagnostics' }));
    const row = (k, v, cls) => d.appendChild(el('div', { class: 'dr' }, el('b', { text: k }), el('span', { class: cls || '', text: String(v) })));
    row('Address used', apiBase());
    row('Player in use', backendName() === 'native' ? 'TV shell player' : 'Browser video');
    row('Search', caps.search ? 'the hub answers it' : 'not offered by this hub', caps.search ? 'ok' : 'no');
    row('Subtitles', caps.subtitles ? 'the hub finds them' : 'not offered by this hub', caps.subtitles ? 'ok' : 'no');
    row('Track switching', caps.tracks ? 'audio and subtitle tracks can change mid-playback' : 'one track per copy', caps.tracks ? 'ok' : 'mid');
    row('Live TV', caps.live ? 'channels are available' : 'no channel list loaded', caps.live ? 'ok' : 'no');
    if (debridInfo) {
      row('Debrid', debridInfo.ready
        ? `${debridInfo.providerLabel || debridInfo.provider} ready`
        : debridInfo.enabled ? 'enabled, not ready' : 'off',
      debridInfo.ready ? 'ok' : debridInfo.enabled ? 'mid' : 'no');
    }
    row('Editable from here', [canHub && 'hub', canPrefs && 'this profile', !!caps.profileEdit && 'profiles', !!caps.cacheAdmin && 'cache'].filter(Boolean).join(', ') || 'nothing — this hub is read-only from a browser', 'mid');
    api.health().then(() => row('Hub reachable', 'yes', 'ok')).catch(() => row('Hub reachable', 'no answer', 'no'));
    return d;
  }

  /* --------------------------------------------------------------------- keys */

  // BACK unwinds this screen before the router unwinds the route: from any section but the first it
  // returns the rail to Playback, which is what "go back" means on a page with no history. From the
  // first section it is left to the router, which leaves Settings the way it leaves any screen.
  // (A dial or action list that is open is handled above this screen in the key stack: Back there
  // cancels the modal and never reaches this handler.)
  //
  // LEFT and RIGHT get the same treatment at the columns' edges. The focus engine's geometry crosses
  // honestly most of the time, but a section whose first control sits at the far right edge (Live TV)
  // would put a Right press from the rail into the fixed menu bar, and a Left press from a row would
  // pick whichever control happens to be leftmost on screen. The two columns are the screen's contract
  // — one Right is into the pane at its first control, one Left from a row is back to the rail — so the
  // screen says that itself. Inside a modal the ring is scoped to the dialog, so this handler only ever
  // sees the rail and the pane's rows.
  handler = (e, key) => {
    if (key === 'Backspace' || key === 'Escape' || key === 'GoBack' || key === 'BrowserBack' || key === 'Back') {
      if (current > 0) { selectSection(0, { refocus: true }); return true; }
      return false;
    }
    const at = focus.getFocus();
    if (!at || !root.contains(at)) return false;
    if (key === 'ArrowRight' && at.classList.contains('sitem')) {
      const first = pane.querySelector('[data-f]');
      if (first) { focus.focusDefault(first); return true; }
      return false;
    }
    if (key === 'ArrowLeft') {
      const band = at.closest('[data-nav]');
      if (band && !band.classList.contains('settings-rail')) {
        // A whole-row control is its own band and its only member: `band.querySelectorAll` would miss
        // the button itself, so count it when the ring is on the band. Left from anywhere in a row
        // means back out to the rail — there is nothing sideways to reach.
        const members = band.matches('[data-f]') ? [band] : Array.prototype.slice.call(band.querySelectorAll('[data-f]'));
        if (!members.length || members.indexOf(at) === 0) {
          focus.focusDefault(rail.children[current]);
          return true;
        }
      }
    }
    return false;
  };
  pushHandler(handler);

  /* ------------------------------------------------------------------- load */

  (async () => {
    const [s, p, a3] = await Promise.all([
      api.settings().catch(() => null),
      profile.id ? api.prefs(profile.id).catch(() => null) : Promise.resolve(null),
      caps.addonAdmin ? api.addons().catch(() => null) : Promise.resolve(null),
    ]);
    rowList = Array.isArray(s && s.rows) ? s.rows : null;
    catalogOptions = Array.isArray(s && s.catalogOptions) ? s.catalogOptions : null;
    const ad = a3 ? (a3.addons || []) : [];
    installed = Array.isArray(ad) ? ad : [];
    setting = s ? (s.settings || s) : null;
    liveInfo = (s && s.live) || null;
    debridInfo = (s && s.debrid) || null;
    names = (s && s.languageNames) || {};
    prefs = p ? (p.prefs || p) : null;
    paint();
  })();

  // The ring enters on the rail: the first thing a viewer does in Settings is choose a section, not
  // hunt for a control. The rail is empty until the hub answers, so `__defaultFocus` may return null
  // on the very first frame — `paint` puts the ring on the first rail item when the sections arrive.
  root.__defaultFocus = () => rail.querySelector('.sitem');
  root.__dispose = () => { if (handler) popHandler(handler); };
  return root;
}
