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
import { hubBuild, buildLabel } from '/ui/js/build.js';
import { backendName } from '/ui/js/player.js';
import { setTextScale, textScale } from '/ui/js/theme.js';
import { session, isLocked } from '/ui/js/state.js';
import { langList } from '/ui/js/format.js';
import { withAdultProof } from '/ui/js/profileedit.js';
import { ensureSettingsAdmin, withSettingsAdmin } from '/ui/js/adminlock.js';
import { pushHandler, popHandler } from '/ui/js/input.js';

export function screen(ctx) {
  const root = el('div', { class: 'screen settings' });
  const profile = ctx.profile || {};
  const manifest = ctx.manifest() || {};
  const caps = manifest.capabilities || {};

  let setting = null;      // /app/v1/settings → settings
  let themeChoices = [];  // /app/v1/settings → themes
  let names = {};          // /app/v1/settings → languageNames, supplied by the hub
  let prefs = null;        // /app/v1/prefs → prefs
  let installed = [];      // /app/v1/addons → addons
  let reviewed = [];       // /app/v1/addon-registry → vetted source metadata only
  let reviewedDirectory = null; // optional richer projection; legacy hubs only return `reviewed`
  let reviewedDirectoryState = 'loading'; // ready | refreshing | unavailable
  let reviewedInstalledHere = new Set(); // a short-lived acknowledgement while a legacy hub lacks entry.installed
  // Discovery has two explicit trust lanes.  Community data never falls through to the reviewed
  // registry (or vice versa), even when both happen to describe a source with a similar name.
  let sourceDirectoryLane = 'curated'; // curated | community
  let communityDirectory = null; // /app/v1/community-addon-directory → redacted descriptors only
  let communityDirectoryState = 'loading'; // ready | refreshing | unavailable
  let communityInstalledHere = new Set(); // bridge an install until the next Hub directory response
  let sourceDirectoryQuery = '';
  let sourceDirectoryCategory = '';
  let sourceDirectoryType = '';
  let communityDirectoryQuery = '';
  let communityDirectoryType = '';
  // Source directories can be much larger than the household's installed list.  Keep the first
  // interaction bounded: a remote never needs ninety focus stops created before it can choose one,
  // and a browser never needs to lay out every unfiltered card before the first frame paints.
  const SOURCE_DIRECTORY_PAGE_SIZE = 12;
  let reviewedDirectoryVisible = SOURCE_DIRECTORY_PAGE_SIZE;
  let communityDirectoryVisible = SOURCE_DIRECTORY_PAGE_SIZE;
  let rowList = null;      // /app/v1/settings → rows, when this backend has any to offer
  let liveInfo = null;     // /app/v1/settings → live {channels, groups}, the channel manager's data
  let catalogOptions = null; // /app/v1/settings → catalogOptions, the material for owner-made categories
  let debridInfo = null;   // /app/v1/settings → debrid status: provider list, tokenSet, never the token
  let busy = false;

  let sections = [];       // the rail: one entry per section this hub can answer for, built on every paint
  let current = 0;         // which section the pane is showing
  let currentSectionId = 'overview'; // preserve the owner’s place if an async write triggers a repaint
  // Sources are a second-level destination, exactly as they are on the television: the entry lives in
  // Access & safety, the household Settings PIN is asked before the pane opens, and the rail only
  // carries the section once this visit has proved the PIN. That keeps a source list — addresses,
  // tokens, packs — out of the everyday settings walk, where anyone holding the remote can see it.
  let sourcesOpen = false;
  let diagOpen = false;    // the This Hub pane's advanced step; the readout costs a walk to reach otherwise
  let handler = null;      // the Back key, while this screen holds the stage

  root.appendChild(el('div', { class: 'page-head' },
    el('h1', { text: 'Settings' }),
    el('div', { class: 'sub', text: 'What your hub is configured to do, and what this profile asks for inside it.' })));

  // The compact control header: which area this is, what it currently holds, and
  // the trust state of the household. It is not a control — the remote never has
  // to land on it — and it repaints with the section, the way the television's
  // own header strip does, so the eye never has to check back left.
  const strip = el('div', { class: 'context-strip', 'aria-hidden': 'true' },
    el('span', { class: 'ctx-scope' }),
    el('span', { class: 'ctx-summary' }),
    el('span', { class: 'ctx-state' }));
  root.appendChild(strip);
  paintContextStrip();

  // The rail never scrolls away with its section: the two panes are columns of a grid that fills the
  // screen, and only the right pane scrolls when a section runs long. The ring enters on the rail, so
  // the first key a viewer presses is Down through the list of things they came to change.
  const rail = el('div', { class: 'settings-rail', 'data-nav': 'v' });
  const pane = el('div', { class: 'settings-pane' });
  root.appendChild(el('div', { class: 'settings-shell' }, rail, pane));
  pane.appendChild(el('div', { class: 'row-err' }, el('span', { text: 'Reading this hub’s configuration…' })));

  // The rail is a selector, not a queue: the pane must always answer to the name the ring is on, or
  // walking Down past "Watching" while its playback or live-TV rows sit under another label reads as a
  // broken page. So the section
  // switches the moment the ring lands on a rail item — the same rule every TV settings menu follows,
  // and why the ring never waits for an Enter the viewer has no reason to press.
  // The engine's own focus listener is the hook, not the DOM `focusin` event: the engine moves the ring
  // through setFocus, and a document that has lost browser focus (a backgrounded webview) moves
  // activeElement without ever firing focusin — the pane would then stop answering the rail.
  const onRailFocus = focus.onFocus((node) => {
    const b = node && node.closest ? node.closest('.sitem') : null;
    if (!b || !rail.contains(b)) return;
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
  /** Write a row's value and let it mark the change: a short pop, so a saved number is visibly the
   *  same number the dial just held rather than new text that appeared somewhere. */
  function setLv(b, text) {
    if (!b.__lv) return;
    b.__lv.textContent = text;
    b.__lv.classList.remove('bump');
    void b.__lv.offsetWidth;
    b.__lv.classList.add('bump');
  }

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
        // A prior write may still be waiting on the Hub or a Settings-PIN prompt.  Do not let a
        // second dial selection paint an unconfirmed value while that first result is pending.
        if (busy) { ctx.closeModals(); return; }
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
          // As with the dial, reject a second commit before it changes the row's selected array
          // or displayed summary. The authoritative response will repaint the completed write.
          if (busy) { ctx.closeModals(); return; }
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
      if (busy) return;
      const next = !sw.classList.contains('on');
      sw.classList.toggle('on', next);
      b.setAttribute('aria-checked', next ? 'true' : 'false');
      await write(next);
    };
    parent.appendChild(b);
  }

  /* ------------------------------------------------------------------ writes */

  async function writePrefs(patch) {
    if (busy) return false;
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
    return ok;
  }
  async function writeSettings(patch) {
    if (busy) return false;
    busy = true;
    const next = Object.assign({}, setting, patch);
    let ok = true;
    let cancelled = false;
    let saved = null;
    try {
      saved = await withSettingsAdmin(ctx,
        () => api.saveSettings({ settings: next }), setting && setting.lock);
      if (saved === null) { ok = false; cancelled = true; }
    }
    catch (e) {
      // Older builds accept the object flat rather than wrapped in `settings`.
      try {
        saved = await withSettingsAdmin(ctx, () => api.saveSettings(next), setting && setting.lock);
        if (saved === null) { ok = false; cancelled = true; }
      } catch (e2) { ok = false; }
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
      busy = false;
      return true;
    }
    // Pick, checklist and switch rows intentionally update their local affordance before their
    // asynchronous writer returns. A cancelled Settings-PIN pad or a refused/expired capability must
    // therefore rebuild from the Hub rather than leave a value that only looked saved. Clear `busy`
    // first: the replacement screen can immediately accept the next deliberate action.
    ctx.toast(cancelled ? 'That change was not saved' : 'The hub would not take that change');
    busy = false;
    ctx.reload();
    return false;
  }

  /* --------------------------------------------------------------- the pane */

  /**
   * The pane holds one section, and a switch to another replaces its whole subtree. Replacing rather
   * than hiding keeps the focus engine's candidate list honest — rows from the other four sections are
   * not sitting off-screen for the ring to fall into — and the entrance animation is transform and
   * opacity only, the same language the rest of the product moves in.
   */
  function selectSection(i, { refocus = false } = {}) {
    if (!sections[i]) return;
    current = i;
    currentSectionId = sections[i].id;
    for (let k = 0; k < rail.children.length; k++) {
      const selected = k === i;
      rail.children[k].classList.toggle('on', selected);
      rail.children[k].setAttribute('aria-selected', selected ? 'true' : 'false');
    }
    // The section itself is not a nav band: a band that spans the whole pane walks members in document
    // order, which reads as Down moving sideways along a chip line. Each row carries its own small band
    // (`data-nav="h"` on the row button), and bandStep between those bands is the row-by-row walk the
    // remote needs.
    pane.textContent = '';
    const node = el('div', { class: 'settings-section' });
    // The pane names itself the way every TV menu does — section title and its mark at the head of the
    // list — so the eye confirms the selection without checking back to the rail each time.
    node.appendChild(el('div', { class: 'pane-head' },
      icon(sections[i].icon), el('h2', { text: sections[i].title })));
    sections[i].build(node);
    pane.appendChild(node);
    // Rows rise in sequence as a section enters: the same staggered fade the home shelves use, so the
    // pane arrives as a stack of things rather than a wall that pops into being all at once.
    // Directory entries can be a large remote catalogue. They render at final geometry: staging the
    // first fourteen cards made a D-pad switch look like a late, uneven list on lower-memory TVs.
    const movers = node.querySelectorAll('.pane-head, .sgroup > h3, .li:not(.source-directory-card)');
    for (let k = 0; k < movers.length && k < 14; k++) {
      movers[k].classList.add('rise');
      movers[k].style.animationDelay = `${Math.min(420, k * 35)}ms`;
    }
    pane.scrollTop = 0;
    if (refocus) focus.focusDefault(rail.children[i]);
    paintContextStrip();
  }

  /**
   * The header strip is the section map: scope, what the area currently holds,
   * and the trust state. Every value is read from state the page already
   * loaded; nothing new is fetched, and nothing here can be pressed.
   */
  function paintContextStrip() {
    const scopeEl = strip.querySelector('.ctx-scope');
    const summaryEl = strip.querySelector('.ctx-summary');
    const stateEl = strip.querySelector('.ctx-state');
    if (!scopeEl) return;
    const locked = !!(setting && setting.lock && setting.lock.enabled);
    const isKid = profile && profile.kind === 'kids';
    const backend = caps.preview === true ? 'Local preview'
      : backendName() === 'native' ? 'This TV' : 'Hub';
    const firstName = (list) => (Array.isArray(list) && list[0] ? (names[list[0]] || list[0]) : '—');
    const audio = prefs ? firstName(prefs.audioLanguages) : '—';
    const subs = prefs ? firstName(prefs.subtitleLanguages) : '—';
    const quality = prefs && prefs.qualityCap != null ? String(prefs.qualityCap) : 'auto';
    const selectedTheme = themeChoices.find((choice) => choice.id === (setting && setting.theme));
    const themeName = (selectedTheme && selectedTheme.name) || (setting && setting.theme) || 'Default';
    const hidden = new Set(((setting && setting.rows && setting.rows.hidden) || []));
    const visibleRows = rowList ? rowList.filter((entry) => !hidden.has(entry.id)).length : 0;
    const pinState = locked ? 'PIN ON' : 'PIN OFF';
    const map = {
      overview: ['SETTINGS', `Audio ${audio} · ${backend}`, isKid ? 'CHILD PROFILE' : pinState],
      watching: ['PROFILE PLAYBACK', `Audio ${audio} · Subtitles ${subs}`, quality],
      appearance: ['THIS TV', themeName, String(profile.name || 'Default profile')],
      home: ['HOME ROWS', `${visibleRows} visible`, 'ADMIN'],
      sources: ['HUB SOURCES', backend, 'ADMIN'],
      household: ['SHARED HOUSEHOLD', 'Library, live TV and profiles', locked ? 'PIN ON' : 'ADMIN'],
      access: ['ACCESS & SAFETY', 'Administrator settings', pinState],
      hub: ['THIS HUB', backend, 'ADMIN'],
    };
    const id = sections[current] && sections[current].id;
    const entry = map[id] || ['SETTINGS', backend, isKid ? 'CHILD PROFILE' : pinState];
    scopeEl.textContent = entry[0];
    summaryEl.textContent = entry[1];
    stateEl.textContent = entry[2];
  }

  function paint() {
    const previousSectionId = currentSectionId;
    sections = availableSections();
    if (!sections.length) sections = [{ id: 'overview', icon: 'home', title: 'Overview', build: buildOverview }];
    const kept = sections.findIndex((section) => section.id === previousSectionId);
    current = kept >= 0 ? kept : 0;

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
    const list = [{ id: 'overview', icon: 'home', title: 'Overview', build: buildOverview }];
    if (prefs || (setting && setting.live)) list.push({ id: 'watching', icon: 'play', title: 'Watching', build: buildWatching });
    // Appearance is always present: even a hub that reports no shared themes still has the browser's own
    // Text size, which is the same control the television keeps beside its theme.
    list.push({ id: 'appearance', icon: 'sliders', title: 'Appearance', build: buildAppearance });
    const hasRows = !!(rowList && rowList.length && caps.settingsAdmin);
    const hasCats = !!(setting && canHub && (Array.isArray(catalogOptions) || Array.isArray(setting.categories)));
    if (hasRows || hasCats) list.push({ id: 'home', icon: 'home', title: 'Home', build: buildHome });
    // Sources joins the rail only after Access & safety has proved the household PIN this visit. Until
    // then the entry is the row inside Access, which is the only way in — a source list is not part of
    // the everyday walk through settings.
    if (caps.addonAdmin && sourcesOpen) list.push({ id: 'sources', icon: 'list', title: 'Sources', build: buildSources });
    const hasHouse = !!caps.profileEdit || !!(setting && setting.debrid);
    if (hasHouse) list.push({ id: 'household', icon: 'user', title: 'Household', build: buildHousehold });
    if (setting && canHub) list.push({ id: 'access', icon: 'lock', title: 'Access & safety', build: buildAccess });
    list.push({ id: 'hub', icon: 'gear', title: 'This Hub', build: buildHub });
    return list;
  }

  /** A summary is useful only when it can take the owner to the setting it describes. */
  function openSection(id) {
    const target = sections.findIndex((section) => section.id === id);
    if (target >= 0) selectSection(target, { refocus: true });
  }

  function buildOverview(parent) {
    const g = group('At a glance', 'The household backend, profile, sources, and theme in one quiet place. Nothing on this page changes a setting.');
    const backend = caps.preview === true ? 'Local preview'
      : backendName() === 'native' ? 'This TV' : 'Hub';
    const backendRow = line('Backend', 'Where this browser is getting its library and household settings.');
    backendRow.appendChild(value(backend));
    g.appendChild(backendRow);

    const profileRow = line('Watching as', 'The active profile controls safe content and playback preferences.');
    profileRow.appendChild(value(profile.name || 'Default profile'));
    g.appendChild(profileRow);

    const activeSources = (installed || []).filter((source) => source && source.enabled !== false).length;
    const sourceRow = line('Sources', 'Only enabled sources can contribute titles or streams.');
    sourceRow.appendChild(value(`${activeSources} active`));
    g.appendChild(sourceRow);

    const selectedTheme = themeChoices.find((theme) => theme.id === (setting && setting.theme));
    const themeRow = line('Theme', 'The same registered theme contract is used by compatible screens.');
    themeRow.appendChild(value((selectedTheme && selectedTheme.name) || (setting && setting.theme) || 'Default'));
    g.appendChild(themeRow);
    parent.appendChild(g);

    const shortcuts = group('Quick links', 'Open the part of the control centre you came to manage.');
    let shortcutCount = 0;
    const addShortcut = (id, label, note, valueText) => {
      if (sections.some((section) => section.id === id)) {
        openRow(shortcuts, label, note, () => openSection(id), { valueText });
        shortcutCount += 1;
      }
    };
    addShortcut('watching', 'Playback and live TV', 'Audio, subtitles, quality, buffering, and channels.', 'Open');
    addShortcut('appearance', 'Appearance', 'Select a shared visual theme for compatible devices.', 'Open');
    addShortcut('sources', 'Sources', 'Discover, install, and manage the sources this Hub can use.', 'Open');
    addShortcut('household', 'Household', 'Profiles, kids safety, and household-wide account choices.', 'Open');
    addShortcut('access', 'Access & safety', 'Protect settings and profile changes with the household PIN.', 'Open');
    addShortcut('hub', 'This Hub', 'Hub identity, cache, maintenance, and diagnostics.', 'Open');
    if (shortcutCount) parent.appendChild(shortcuts);
  }

  function buildWatching(parent) {
    buildPlayback(parent);
    buildLive(parent);

    // The television's Watching pane ends with the two household shortcuts a person reaches for while
    // tuning playback: what Home shows, and who is watching. The web keeps them in the same place and
    // the same order, so a person who learned one settings screen can walk the other.
    const g = group('Home and people', 'The same shortcuts the television puts under Watching: what the home screen shows, and which person is watching.');
    if (rowList && rowList.length && sections.some((section) => section.id === 'home')) {
      const hidden = new Set(((setting && setting.rows && setting.rows.hidden) || []));
      const visible = rowList.filter((entry) => !hidden.has(entry.id)).length;
      openRow(g, 'Home shelves', 'Show, hide or reorder the rows the home screen draws.',
        () => openSection('home'), { valueText: `${visible} of ${rowList.length} shown` });
    }
    if (Array.isArray(session.profiles) && session.profiles.length
      && sections.some((section) => section.id === 'household')) {
      openRow(g, 'People profiles', 'Add, rename or change who is watching.',
        () => openSection('household'),
        { valueText: `${session.profiles.length} ${session.profiles.length === 1 ? 'person' : 'people'}` });
    }
    if (g.querySelector('.li')) parent.appendChild(g);
  }

  function buildAppearance(parent) {
    if (themeChoices.length) {
      const g = group('Theme', 'Choose a look this browser and connected TV both ship. TV-only layouts stay on that television.');
      pickRow(g, 'Theme', 'Only layouts with synchronized shared tokens appear here. Changing it reloads the browser.', themeChoices.map((theme) => theme.id),
        (setting && setting.theme) || 'scenix-dark',
        (id) => { const theme = themeChoices.find((choice) => choice.id === id); return theme ? theme.name : id; },
        async (id) => {
          // A refresh is how the web shell applies a new token file. It must follow the Hub's
          // acknowledgement, never a cancelled PIN pad or a rejected TV-only choice.
          if (await writeSettings({ theme: id })) window.location.reload();
        });
      parent.appendChild(g);
    }

    // The television's Appearance pane has a Readability group under Theme. The web carries the same
    // control because it drives the same thing — `--s`, the one scale factor every length in the shell
    // is derived from — so type, tiles and gaps grow together. The choice is kept on this device.
    const g2 = group('Readability', 'Make everything bigger or smaller on this browser. It grows type, tiles and gaps together, the way the television’s zoom does, and stays on this device.');
    pickRow(g2, 'Text size', 'The size of every word and control on this browser.',
      [1, 1.15, 1.3, 1.5], textScale(),
      (v) => `${Math.round(Number(v) * 100)}%`,
      (v) => { setTextScale(v); paint(); },
      (v) => `${Math.round(Number(v) * 100)}%`);
    parent.appendChild(g2);
  }

  /* -------------------------------------------------------------- access */

  function lockError(error, fallback) {
    return (error && error.body && error.body.error) || fallback;
  }

  /** Persist the lock state and immediately redraw its single source of truth in the rail. */
  async function saveSettingsLock(body, note) {
    const out = await api.saveSettingsLock(body);
    if (!out || !out.lock) throw new Error('The hub did not return the Settings-lock state');
    setting = Object.assign({}, setting, { lock: out.lock });
    if (note) ctx.toast(note);
    paint();
    return out;
  }

  /** The household lock belongs in a small, explicit Access & safety section rather than diagnostics. */
  function buildAccess(parent) {
    if (!setting || !canHubSetting()) return;
    const lock = setting.lock || { enabled: false, pinSet: false };
    const g = group('Settings lock',
      'Protects household settings and profile changes on this hub. It stays in this browser only for the current visit.');

    if (lock.enabled === true) {
      actionRow(g, 'Settings lock', 'On. Changes to household settings and profiles need the household Settings PIN.', {
        valueText: 'On',
        actions: () => [{
          label: 'Change Settings PIN',
          run: async () => {
            const fresh = await ctx.enterSettingsPin('Choose a new 4 to 8 digit Settings PIN');
            if (!fresh) return;
            try {
              await withSettingsAdmin(ctx,
                () => saveSettingsLock({ enabled: true, pin: fresh }, 'Settings PIN changed'), lock);
            } catch (error) { ctx.toast(lockError(error, 'The hub would not change the Settings PIN')); }
          },
        }, {
          label: 'Turn Settings lock off', danger: true,
          run: async () => {
            try {
              await withSettingsAdmin(ctx,
                () => saveSettingsLock({ enabled: false }, 'Settings lock turned off'), lock);
            } catch (error) { ctx.toast(lockError(error, 'The hub would not turn the Settings lock off')); }
          },
        }],
      });
    } else if (lock.pinSet === true) {
      actionRow(g, 'Settings lock', 'Off. A Settings PIN is stored and can turn the lock back on.', {
        valueText: 'Off',
        actions: () => [{
          label: 'Turn Settings lock on',
          run: async () => {
            const current = await ctx.enterSettingsPin('Enter the existing 4 to 8 digit Settings PIN');
            if (!current) return;
            try { await saveSettingsLock({ enabled: true, pinCurrent: current }, 'Settings lock turned on'); }
            catch (error) { ctx.toast(lockError(error, 'The hub would not turn the Settings lock on')); }
          },
        }, {
          label: 'Change stored Settings PIN',
          run: async () => {
            const current = await ctx.enterSettingsPin('Enter the current 4 to 8 digit Settings PIN');
            if (!current) return;
            const fresh = await ctx.enterSettingsPin('Choose a new 4 to 8 digit Settings PIN');
            if (!fresh) return;
            try {
              await saveSettingsLock(
                { enabled: false, pin: fresh, pinCurrent: current }, 'Stored Settings PIN changed',
              );
            } catch (error) { ctx.toast(lockError(error, 'The hub would not change the Settings PIN')); }
          },
        }],
      });
    } else {
      openRow(g, 'Settings lock', 'Off. Add a 4 to 8 digit Settings PIN to protect household changes.', async () => {
        const fresh = await ctx.enterSettingsPin('Choose a new 4 to 8 digit Settings PIN');
        if (!fresh) return;
        try { await saveSettingsLock({ enabled: true, pin: fresh }, 'Settings lock turned on'); }
        catch (error) { ctx.toast(lockError(error, 'The hub would not turn the Settings lock on')); }
      }, { valueText: 'Off' });
    }
    parent.appendChild(g);

    // Sources live behind the same lock the television puts in front of them. The row is always here
    // for an owner to see (it is how the PIN is discovered), but pressing it asks the household PIN
    // first and only then opens the pane — add-ons, source packs and direct copies are addresses and
    // tokens, not a shelf a child should be able to browse from the couch.
    if (caps.addonAdmin) {
      const active = (installed || []).filter((source) => source && source.enabled !== false).length;
      const lockNow = (setting && setting.lock) || { enabled: false, pinSet: false };
      const hasPin = lockNow.enabled === true || lockNow.pinSet === true;
      const gS = group('Sources',
        'Add-ons, reviewed and community directories, owner source packs and direct copies. The household Settings PIN is asked before the list opens.');
      openRow(gS, 'Sources',
        'Discover, install, enable or remove the sources this hub reads.',
        async () => {
          if (!hasPin) {
            // No PIN yet means no one to ask. Creating one here is what the television does before
            // its Sources rows appear: the directory is a household control, not a shelf a child can
            // browse because the household never got round to setting a number.
            const fresh = await ctx.enterSettingsPin('Choose a 4 to 8 digit Settings PIN to protect Sources');
            if (!fresh) return;
            try { await saveSettingsLock({ enabled: true, pin: fresh }, 'Settings lock turned on; Sources is now protected'); }
            catch (error) { ctx.toast(lockError(error, 'The hub would not store that Settings PIN')); return; }
          } else if (!await ensureSettingsAdmin(ctx, lockNow, 'Enter the Settings PIN to manage sources')) {
            return;
          }
          sourcesOpen = true;
          paint();
          openSection('sources');
        },
        { valueText: sourcesOpen ? 'Open' : (hasPin ? `${active} active · PIN` : 'Set a PIN') });
      parent.appendChild(gS);
    }
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
      const ad = setting.adaptive || {};
      toggle(g3, 'Build an adaptive quality ladder',
        'On: a Play press may return one HLS master with variants transcoded on this hub, so the player can change quality mid-film instead of rebuffering. Off keeps playback copy-only. This costs hub CPU and disk.',
        !!ad.enabled,
        canHubSetting() ? async (v) => {
          if (await writeSettings({ adaptive: Object.assign({}, ad, { enabled: v }) })) paint();
        } : null);
      pickRow(g3, 'Ladder top quality',
        'The highest rung the ladder may offer. The client can lower it further from its own display ceiling; it is never upscaled above the source.',
        [720, 1080, 1440, 2160], Number(ad.maxHeight || 1080),
        (v) => (Number(v) >= 2160 ? '2160p / 4K' : v + 'p'),
        canHubSetting() ? (v) => writeSettings({ adaptive: Object.assign({}, ad, { maxHeight: Number(v) }) }) : null);
      toggle(g3, 'Hold a window of the film in memory',
        pb.readAhead
          ? 'The hub reads ahead of the player into its own memory, so a P2P copy that bursts and pauses still plays like a file on a server.'
          : 'Off: bytes go straight from the P2P copy to the player, which is what it did before — a quiet swarm shows up as a frozen picture.',
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
        if (await writeSettings({ live: Object.assign({}, setting.live, { playlist: url }) })) paint();
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
      g.appendChild(el('p', { class: 'note', text: 'Set a playlist address first: Settings → Watching → Live TV playlist.' }));
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
                  if (await writeRows({ hidden: [...hidden] })) paint();
                },
              }];
              if (at > 0) acts.push({
                label: `Move ${title} earlier`,
                run: async () => {
                  const to = seq.slice();
                  to.splice(at, 1); to.splice(at - 1, 0, row.id);
                  if (await writeRows({ order: to })) paint();
                },
              });
              if (at >= 0 && at < seq.length - 1) acts.push({
                label: `Move ${title} later`,
                run: async () => {
                  const to = seq.slice();
                  to.splice(at, 1); to.splice(at + 1, 0, row.id);
                  if (await writeRows({ order: to })) paint();
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
    if (e && e.status === 403 && e.body && e.body.sessionRequired === true) {
      return 'The household Settings PIN is needed';
    }
    return e && e.status === 403 ? 'That is not the current PIN' : fallback;
  }

  async function setPin(p) {
    const current = await proofOfLock(p);
    if (current === null) return;
    const digits = await ctx.enterPin(p, current ? 'Choose a new PIN' : 'Choose a PIN');
    if (!digits) return;
    try {
      const out = await withSettingsAdmin(ctx,
        () => api.updateProfile(p.id, { pin: digits, pinRequired: true, pinCurrent: current }), setting && setting.lock);
      if (out === null) return;
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
      const out = await withSettingsAdmin(ctx,
        () => api.updateProfile(p.id, { pin: null, pinRequired: false, pinCurrent: current }), setting && setting.lock);
      if (out === null) return;
      ctx.toast(`${p.name} no longer needs a PIN`);
      ctx.reload();
    } catch (e) { ctx.toast(whyRefused(e, 'The hub would not remove the PIN')); }
  }

  function buildHousehold(parent) {
    // The television's Household pane starts with the shared library it owns: the hub's name and search
    // limits, the shared Live TV playlist, and the categories built from catalogues. Each is a screen of
    // its own here too, so this group points at them instead of restating their contents.
    const gLib = group('Shared library', 'One library for the house: its name and search limits, the shared Live TV playlist, and the shelves you built from catalogues.');
    if (setting) {
      const nameRow = line('Library and search', 'The hub name every screen shows, and the limits search obeys.');
      nameRow.appendChild(value(setting.hubName || 'this hub'));
      gLib.appendChild(nameRow);
    }
    if (setting && setting.live && sections.some((section) => section.id === 'watching')) {
      openRow(gLib, 'Live TV', 'The shared playlist and the channels this house keeps.',
        () => openSection('watching'),
        { valueText: setting.live.playlist ? 'playlist set' : 'no playlist' });
    }
    if (Array.isArray(setting && setting.categories) || Array.isArray(catalogOptions)) {
      const cats = Array.isArray(setting && setting.categories) ? setting.categories : [];
      openRow(gLib, 'Categories', 'Genre shelves drawn on Home, in your order.',
        () => ctx.navigate('categories'),
        { valueText: cats.length ? `${cats.length} made` : 'none yet' });
    }
    if (gLib.querySelector('.li')) parent.appendChild(gLib);

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
                    const out = await withSettingsAdmin(ctx,
                      () => api.updateProfile(p.id, { kind: kids ? 'adult' : 'kids', pinCurrent: current }), setting && setting.lock);
                    if (out === null) return;
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
                  try {
                    const out = await withSettingsAdmin(ctx, () => api.deleteProfile(p.id), setting && setting.lock);
                    if (out === null) return;
                    ctx.toast(`${p.name} removed`);
                    ctx.reload();
                  }
                  catch (e) { ctx.toast('The hub would not remove that profile'); }
                },
              });
              return acts;
            },
          });
      }
      openRow(g, 'Add profile', 'A new profile starts with the house defaults.', async () => {
        try {
          const r = await withSettingsAdmin(ctx,
            () => api.createProfile({ name: 'New profile', kind: 'adult' }), setting && setting.lock);
          if (r === null) return;
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
        'Optional. With your own debrid account, the hub asks the service to turn a P2P link or a file-host link into an ordinary HTTP stream, so it starts without a P2P engine on the hub. Off by default; with no token nothing changes.');
      toggle(gD, 'Use a debrid account',
        d.enabled
          ? 'On: the hub tries your provider before the P2P gateway and falls through if it cannot answer.'
          : 'Off: P2P copies use the gateway and host links play as they are.',
        !!d.enabled,
        canHubSetting() ? async (v) => {
          // Enabling an account with no provider chosen cannot work. With one adapter available, choose
          // it here so the switch is not a control that silently does nothing.
          const provider = d.provider || (only ? only.id : '');
          if (await writeSettings({ debrid: Object.assign({}, d, { enabled: v, provider }) })) paint();
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
                if (await writeSettings({ debrid: Object.assign({}, d, { token }) })) paint();
              },
            }];
            if (tokenSet) acts.push({
              label: 'Remove the token', danger: true,
              run: async () => {
                // null is the explicit erase; an empty token from this screen would mean "keep it".
                if (await writeSettings({ debrid: Object.assign({}, d, { token: null }) })) paint();
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

  /* The source registry is Hub-owned. Reviewed entries are repository-owned declarations rather than a
     remote marketplace; manual URLs are deliberately separate, advanced, and visibly unreviewed. */
  function sourceDirectoryString(value) {
    return typeof value === 'string' ? value.trim() : '';
  }

  function uniqueSourceDirectoryValues(values) {
    const seen = new Set();
    for (const value of values || []) {
      const clean = sourceDirectoryString(value);
      if (clean) seen.add(clean);
    }
    return [...seen].sort((a, b) => a.localeCompare(b));
  }

  function sourceDirectoryEntries() {
    const entries = reviewedDirectory && Array.isArray(reviewedDirectory.entries)
      ? reviewedDirectory.entries : reviewed;
    return Array.isArray(entries) ? entries.filter((entry) => entry && typeof entry === 'object') : [];
  }

  function sourceDirectoryResources(source) {
    const names = [];
    for (const resource of (source && Array.isArray(source.resources) ? source.resources : [])) {
      names.push(typeof resource === 'string' ? resource : resource && resource.name);
    }
    return uniqueSourceDirectoryValues(names);
  }

  function sourceDirectoryTypes(source) {
    const types = Array.isArray(source && source.types) ? source.types.slice() : [];
    for (const resource of (source && Array.isArray(source.resources) ? source.resources : [])) {
      if (resource && typeof resource === 'object' && Array.isArray(resource.types)) types.push(...resource.types);
    }
    return uniqueSourceDirectoryValues(types);
  }

  function sourceDirectoryCategories(source) {
    return uniqueSourceDirectoryValues(source && Array.isArray(source.categories) ? source.categories : []);
  }

  function sourceDirectoryLabel(value) {
    const clean = sourceDirectoryString(value);
    if (!clean) return 'Source';
    return clean.replace(/[-_]+/g, ' ').replace(/\b\w/g, (letter) => letter.toUpperCase());
  }

  function sourceDirectoryValues(kind) {
    const key = kind === 'category' ? 'categories' : 'types';
    const supplied = reviewedDirectory && reviewedDirectory.filters && Array.isArray(reviewedDirectory.filters[key])
      ? reviewedDirectory.filters[key] : [];
    const derived = [];
    for (const source of sourceDirectoryEntries()) {
      derived.push(...(kind === 'category' ? sourceDirectoryCategories(source) : sourceDirectoryTypes(source)));
    }
    return uniqueSourceDirectoryValues([...supplied, ...derived]);
  }

  function sourceDirectoryIsInstalled(source) {
    if (!source) return false;
    if (source.installed === true || (source.install && source.install.installed === true)) return true;
    return reviewedInstalledHere.has(String(source.id || ''));
  }

  function sourceDirectoryInstallUnavailable(source) {
    return !!(source && source.install && source.install.available === false);
  }

  /** Config-required sources never turn into a manual-URL path: the only alternative is a short-lived code. */
  function sourceSetupAvailable(source) {
    const setup = source && source.install && source.install.setup;
    return !!(source && source.install && source.install.reason === 'configuration-required'
      && setup && setup.available === true && setup.method === 'web-code');
  }

  function sourceDirectoryInstallUnavailableMessage(source) {
    if (source && source.install && source.install.reason === 'configuration-required') {
      return sourceSetupAvailable(source)
        ? 'This source needs browser setup. Start a short-lived setup code; configuration details never appear on the TV.'
        : 'This source needs setup that this Hub cannot complete yet. Installation is not available from this screen.';
    }
    return 'This reviewed source is not available to install from this Hub right now.';
  }

  function sourceDirectoryDescription(source) {
    const description = sourceDirectoryString(source && source.description);
    if (description) return description.slice(0, 320);
    const types = sourceDirectoryTypes(source);
    const resources = sourceDirectoryResources(source);
    if (types.length && resources.length) {
      return `${types.map(sourceDirectoryLabel).join(' and ')} through ${resources.map(sourceDirectoryLabel).join(' and ')}.`;
    }
    return 'The Hub checks this source’s reviewed identity and capability contract during installation.';
  }

  function sourceDirectoryMatches(source) {
    if (sourceDirectoryCategory && !sourceDirectoryCategories(source).includes(sourceDirectoryCategory)) return false;
    if (sourceDirectoryType && !sourceDirectoryTypes(source).includes(sourceDirectoryType)) return false;
    const query = sourceDirectoryQuery.trim().toLocaleLowerCase();
    if (!query) return true;
    const searchable = [
      source && source.id,
      source && source.name,
      source && source.description,
      ...sourceDirectoryCategories(source),
      ...sourceDirectoryTypes(source),
      ...sourceDirectoryResources(source),
    ].map(sourceDirectoryString).join(' ').toLocaleLowerCase();
    return searchable.includes(query);
  }

  function reviewedDirectoryResults() {
    return sourceDirectoryEntries().filter(sourceDirectoryMatches)
      .sort((a, b) => String(a.name || a.id || '').localeCompare(String(b.name || b.id || '')));
  }

  function applyReviewedDirectory(reply) {
    reviewed = Array.isArray(reply && reply.reviewed) ? reply.reviewed : [];
    reviewedDirectory = reply && reply.directory && typeof reply.directory === 'object' ? reply.directory : null;
    // A fresh directory response is authoritative when it supplies install state. The local marker only
    // bridges legacy Hub responses between a successful install and the next directory read.
    if (reviewedDirectory && Array.isArray(reviewedDirectory.entries)
      && reviewedDirectory.entries.some((entry) => entry && Object.prototype.hasOwnProperty.call(entry, 'installed'))) {
      reviewedInstalledHere.clear();
    }
    reviewedDirectoryState = reply ? 'ready' : 'unavailable';
    resetSourceDirectoryPaging('curated');
  }

  async function refreshReviewedDirectory() {
    if (reviewedDirectoryState === 'refreshing') return;
    reviewedDirectoryState = 'refreshing';
    try {
      applyReviewedDirectory(await api.reviewedAddons());
      ctx.toast('Reviewed source directory refreshed');
    } catch (e) {
      reviewedDirectoryState = 'unavailable';
      ctx.toast('The Hub could not refresh its reviewed source directory');
    }
    if (root.isConnected && (!ctx.route || !ctx.route() || ctx.route().name === 'settings')) paint();
  }

  /* Community entries are intentionally handled as a second, non-endorsed data model.  Do not reuse
     the reviewed install marker or trust copy here: a similar card shape must never erase the source's
     provenance from either a mouse or remote viewer. */
  function communityDirectoryEntries() {
    return communityDirectory && Array.isArray(communityDirectory.entries)
      ? communityDirectory.entries.filter((entry) => entry && typeof entry === 'object') : [];
  }

  function applyCommunityDirectory(reply) {
    communityDirectory = reply && typeof reply === 'object' ? reply : null;
    const entries = communityDirectoryEntries();
    if (communityDirectory && Array.isArray(communityDirectory.entries)) {
      // The Hub's installed flag wins over the short visit-local acknowledgement after a refresh.
      if (entries.some((entry) => Object.prototype.hasOwnProperty.call(entry, 'installed'))) {
        communityInstalledHere.clear();
      }
      communityDirectoryState = 'ready';
    } else {
      communityDirectoryState = 'unavailable';
    }
    resetSourceDirectoryPaging('community');
  }

  async function refreshCommunityDirectory({ quiet = false } = {}) {
    if (communityDirectoryState === 'refreshing') return;
    communityDirectoryState = 'refreshing';
    try {
      applyCommunityDirectory(await api.communityAddonDirectory());
      if (!quiet) ctx.toast('Community source directory refreshed');
    } catch (e) {
      communityDirectoryState = 'unavailable';
      if (!quiet) ctx.toast('The Hub could not refresh its community source directory');
    }
    const at = focus.getFocus();
    if (root.isConnected && sourceDirectoryLane === 'community'
      && (!ctx.route || !ctx.route() || ctx.route().name === 'settings')
      && (!at || root.contains(at))) paint();
  }

  function communityDirectoryTypes() {
    const supplied = communityDirectory && communityDirectory.filters
      && Array.isArray(communityDirectory.filters.types) ? communityDirectory.filters.types : [];
    const derived = [];
    for (const source of communityDirectoryEntries()) derived.push(...sourceDirectoryTypes(source));
    return uniqueSourceDirectoryValues([...supplied, ...derived]);
  }

  function communityDirectoryIsInstalled(source) {
    if (!source) return false;
    return source.installed === true || communityInstalledHere.has(String(source.id || ''));
  }

  function communityDirectoryInstallUnavailable(source) {
    return !!(source && (source.configurationRequired === true
      || (source.install && source.install.available === false)));
  }

  function communityDirectoryInstallUnavailableMessage(source) {
    if (source && (source.configurationRequired === true
      || (source.install && source.install.reason === 'configuration-required'))) {
      return sourceSetupAvailable(source)
        ? 'This Community source needs browser setup. It remains unreviewed and still requires acknowledgement.'
        : 'This community source requires configuration that this Hub cannot safely complete. Installation is unavailable here.';
    }
    return 'This community source is not currently available to install from this Hub.';
  }

  function communityDirectoryDescription(source) {
    const description = sourceDirectoryString(source && source.description);
    return description.slice(0, 320)
      || 'A community-published source. It is not reviewed or endorsed by this Hub.';
  }

  function communityDirectoryMatches(source) {
    if (communityDirectoryType && !sourceDirectoryTypes(source).includes(communityDirectoryType)) return false;
    const query = communityDirectoryQuery.trim().toLocaleLowerCase();
    if (!query) return true;
    const searchable = [
      source && source.id,
      source && source.name,
      source && source.description,
      ...sourceDirectoryTypes(source),
      ...sourceDirectoryResources(source),
    ].map(sourceDirectoryString).join(' ').toLocaleLowerCase();
    return searchable.includes(query);
  }

  function communityDirectoryResults() {
    return communityDirectoryEntries().filter(communityDirectoryMatches)
      .sort((a, b) => String(a.name || a.id || '').localeCompare(String(b.name || b.id || '')));
  }

  function communityDirectorySafetyTags(source) {
    const tags = [{ label: 'Community · unreviewed', tone: 'community' }];
    if (source && source.adult === true) tags.push({ label: 'Adult material', tone: 'attention' });
    if (source && source.p2p === true) tags.push({ label: 'P2P', tone: 'attention' });
    if (source && source.configurationRequired === true) tags.push({ label: 'Setup required', tone: 'unavailable' });
    else if (source && source.configurable === true) tags.push({ label: 'May need setup', tone: 'muted' });
    return tags;
  }

  function selectSourceDirectoryLane(lane) {
    if (lane !== 'curated' && lane !== 'community') return;
    resetSourceDirectoryPaging();
    sourceDirectoryLane = lane;
    // Lane contents are mutually exclusive. Repainting removes the other directory from the focus
    // engine instead of visually hiding hundreds of stale remote stops behind this list.
    paint();
    const currentLane = pane.querySelector(`[data-source-directory-lane="${lane}"]`);
    if (currentLane) focus.focusDefault(currentLane);
  }

  function sourceDirectoryLanePicker() {
    const lanes = el('div', { class: 'source-directory-lanes', role: 'tablist', 'data-nav': 'h',
      'aria-label': 'Source directory trust lane' });
    const lane = (id, label, note) => {
      const selected = sourceDirectoryLane === id;
      const button = el('button', {
        class: `source-directory-lane${selected ? ' on' : ''}`,
        type: 'button', role: 'tab', 'aria-selected': selected ? 'true' : 'false',
        'data-source-directory-lane': id,
        'aria-label': `${label}. ${note}`,
        onclick: () => selectSourceDirectoryLane(id),
      }, el('span', { class: 'source-directory-lane-label', text: label }),
      el('span', { class: 'source-directory-lane-note', text: note }));
      focus.focusable(button);
      return button;
    };
    lanes.appendChild(lane('curated', 'Curated', 'Reviewed by this Hub'));
    lanes.appendChild(lane('community', 'Community', 'Unreviewed listings'));
    return lanes;
  }

  /**
   * A directory is a catalogue, not a one-screen list.  Keeping a bounded page means filters answer
   * immediately on lower-memory TVs and browser scroll stays responsive when a Hub exposes hundreds of
   * descriptors.  Pages are deliberately explicit instead of auto-loading on scroll: the next work is
   * always a named, keyboard-reachable choice and never surprises a remote user by moving the target.
   */
  function resetSourceDirectoryPaging(lane = 'all') {
    if (lane === 'all' || lane === 'curated') reviewedDirectoryVisible = SOURCE_DIRECTORY_PAGE_SIZE;
    if (lane === 'all' || lane === 'community') communityDirectoryVisible = SOURCE_DIRECTORY_PAGE_SIZE;
  }

  function sourceDirectoryPageLimit(lane) {
    return lane === 'community' ? communityDirectoryVisible : reviewedDirectoryVisible;
  }

  function showMoreSourceDirectoryEntries(lane) {
    if (lane === 'community') communityDirectoryVisible += SOURCE_DIRECTORY_PAGE_SIZE;
    else reviewedDirectoryVisible += SOURCE_DIRECTORY_PAGE_SIZE;
  }

  function sourceDirectoryResultCount(label, shown, total) {
    const noun = total === 1 ? 'source' : 'sources';
    if (!total) return `No ${label} ${noun}`;
    return shown < total
      ? `Showing ${shown} of ${total} ${label} ${noun}`
      : `${total} ${label} ${noun}`;
  }

  /**
   * Draw only the directory page the viewer asked to see.  The card maker owns provenance and install
   * copy, while this shared pager owns nothing but bounded DOM work and an honest count.  That separation
   * keeps Curated and Community trust rules distinct even though both benefit from the same performance
   * behaviour.
   */
  function renderSourceDirectoryPage({ lane, label, results, list, count, emptyText, makeCard }) {
    const render = (focusIndex = -1) => {
      const shown = Math.min(results.length, sourceDirectoryPageLimit(lane));
      const visible = results.slice(0, shown);
      count.textContent = sourceDirectoryResultCount(label, visible.length, results.length);
      list.textContent = '';

      if (!visible.length) {
        list.appendChild(el('div', { class: 'source-directory-empty', role: 'status', text: emptyText }));
        return;
      }

      visible.forEach((source, index) => {
        const card = makeCard(source);
        card.dataset.sourceDirectoryCardIndex = String(index);
        list.appendChild(card);
      });

      if (shown < results.length) {
        const remaining = results.length - shown;
        const next = Math.min(SOURCE_DIRECTORY_PAGE_SIZE, remaining);
        const more = el('button', {
          class: 'btn line source-directory-more', type: 'button', 'data-nav': 'h',
          text: `Show ${next} more`,
          'aria-label': `Show ${next} more ${label} sources. ${remaining} remain.`,
          onclick: () => {
            const firstNewIndex = shown;
            showMoreSourceDirectoryEntries(lane);
            render(firstNewIndex);
          },
        });
        focus.focusable(more);
        list.appendChild(more);
      }

      if (focusIndex >= 0) {
        const nextCard = list.querySelector(`[data-source-directory-card-index="${focusIndex}"]`);
        if (nextCard) focus.setFocus(nextCard);
      }
    };
    render();
  }

  function sourceDirectoryFact(label, content) {
    return el('div', { class: 'source-detail-fact' },
      el('span', { class: 'source-detail-fact-label', text: label }),
      el('span', { class: 'source-detail-fact-value', text: content }));
  }

  /**
   * Complete an opaque code on the web. The configured address is typed into the browser only after
   * the owner has independently completed the publisher's flow; the TV and directory never receive
   * it, and the Hub validates it against the original source origin before it reaches addons.add.
   */
  async function finishSourceSetupCode(initialCode = '') {
    const code = await ctx.enterText({
      title: 'Finish source setup',
      reason: 'Enter the short setup code shown on the TV. It expires after ten minutes and can be used only once.',
      placeholder: 'SCX-XXXX-XXXX-XXXX',
      initial: initialCode,
      action: 'Find setup',
    }, { valueText: initialCode || 'Enter code' });
    if (code === null) return false;
    let setupReply;
    try {
      setupReply = await withSettingsAdmin(ctx, () => api.sourceSetup(code), setting && setting.lock);
      if (setupReply === null) return false;
    } catch (e) {
      ctx.toast((e && e.body && e.body.error) || 'That setup code is unavailable or expired');
      return false;
    }
    const setup = setupReply && setupReply.setup;
    if (!setup || !setup.code || !setup.source) {
      ctx.toast('That setup code did not return a safe source handoff');
      return false;
    }
    if (setup.state === 'completed') {
      ctx.toast(`${setup.source.name || 'Source'} setup is already complete`);
      return true;
    }
    const configuredManifest = await ctx.enterText({
      title: `Finish ${setup.source.name || 'source'} setup`,
      reason: `Complete the publisher's configuration in a trusted browser window, then paste its configured public manifest address here. This address is kept server-side and is never sent to the TV. Code: ${setup.code}`,
      placeholder: 'https://…/manifest.json',
      initial: '',
      action: 'Verify and install',
    }, { valueText: 'Paste manifest' });
    if (configuredManifest === null) return false;
    try {
      const result = await withSettingsAdmin(ctx,
        () => api.completeSourceSetup(setup.code, configuredManifest), setting && setting.lock);
      if (result === null) return false;
      installed = (result && result.addons) || installed;
      reviewedInstalledHere.clear();
      communityInstalledHere.clear();
      ctx.toast(`${setup.source.name || 'Source'} configured and installed`);
      paint();
      return true;
    } catch (e) {
      ctx.toast((e && e.body && e.body.error) || 'The configured source could not be installed');
      return false;
    }
  }

  async function startReviewedSourceSetup(source) {
    try {
      const reply = await withSettingsAdmin(ctx,
        () => api.startReviewedSourceSetup(source.id), setting && setting.lock);
      if (reply === null || !reply.setup || !reply.setup.code) return;
      await finishSourceSetupCode(reply.setup.code);
    } catch (e) {
      ctx.toast((e && e.body && e.body.error) || 'The secure setup could not be started');
    }
  }

  async function startCommunitySourceSetup(source) {
    try {
      const reply = await withSettingsAdmin(ctx,
        () => api.startCommunitySourceSetup(source.id, true), setting && setting.lock);
      if (reply === null || !reply.setup || !reply.setup.code) return;
      await finishSourceSetupCode(reply.setup.code);
    } catch (e) {
      ctx.toast((e && e.body && e.body.error) || 'The secure Community setup could not be started');
    }
  }

  /** Details remain a review step. The install request is only reachable after a second, named consent. */
  function openReviewedSourceDetail(source, returnFocus) {
    const box = el('div', { class: 'mbox source-detail', role: 'dialog', 'aria-modal': 'true' });
    let phase = 'details';
    let installing = false;
    let failure = '';

    const close = () => ctx.closeModals();
    const render = () => {
      box.textContent = '';
      const closeButton = el('button', {
        class: 'mclose', type: 'button', text: '✕', 'aria-label': 'Close source details', title: 'Close', onclick: close,
      });
      focus.focusable(closeButton);
      box.appendChild(closeButton);

      const installedHere = sourceDirectoryIsInstalled(source);
      const installUnavailable = sourceDirectoryInstallUnavailable(source);
      const types = sourceDirectoryTypes(source);
      const categories = sourceDirectoryCategories(source);
      const resources = sourceDirectoryResources(source);
      box.appendChild(el('div', { class: 'source-detail-kicker', text: installedHere
        ? 'Installed reviewed source' : installUnavailable ? 'Setup required' : 'Reviewed source' }));
      box.appendChild(el('h2', { text: source.name || source.id || 'Reviewed source' }));
      box.appendChild(el('p', { class: 'source-detail-description', text: sourceDirectoryDescription(source) }));
      box.appendChild(el('div', { class: 'source-detail-facts' },
        sourceDirectoryFact('Review', 'Listed and capability-checked by this Hub'),
        sourceDirectoryFact('Version', source.version ? `v${source.version}` : 'Version not supplied'),
        types.length ? sourceDirectoryFact('Supports', types.map(sourceDirectoryLabel).join(' · ')) : null,
        resources.length ? sourceDirectoryFact('Provides', resources.map(sourceDirectoryLabel).join(' · ')) : null,
        categories.length ? sourceDirectoryFact('Categories', categories.map(sourceDirectoryLabel).join(' · ')) : null,
      ));
      if (installUnavailable) {
        box.appendChild(el('p', { class: 'source-detail-unavailable', role: 'status',
          text: sourceDirectoryInstallUnavailableMessage(source) }));
      } else if (source.configurationRequired === true) {
        box.appendChild(el('p', { class: 'source-detail-notice', text: 'This source declares that it may need additional setup after installation.' }));
      }
      if (failure) box.appendChild(el('p', { class: 'source-detail-error', role: 'alert', text: failure }));

      const foot = el('div', { class: 'mfoot source-detail-actions' });
      const cancel = el('button', {
        class: 'btn line', type: 'button', text: phase === 'confirm' ? 'Back' : 'Close', onclick: () => {
          if (phase === 'confirm') { phase = 'details'; failure = ''; render(); }
          else close();
        },
      });
      focus.focusable(cancel);
      foot.appendChild(cancel);

      if (installedHere) {
        box.appendChild(el('p', { class: 'source-detail-installed', text: 'Installed on this Hub. Manage it in Installed sources below.' }));
      } else if (installUnavailable) {
        if (sourceSetupAvailable(source)) {
          const startSetup = el('button', {
            class: 'btn white', type: 'button', text: 'Start secure browser setup', onclick: () => {
              close();
              startReviewedSourceSetup(source);
            },
          });
          startSetup.dataset.directoryPrimary = 'true';
          focus.focusable(startSetup);
          foot.appendChild(startSetup);
        }
      } else if (phase === 'details') {
        const reviewInstall = el('button', {
          class: 'btn white', type: 'button', text: 'Review install', onclick: () => { phase = 'confirm'; failure = ''; render(); },
        });
        reviewInstall.dataset.directoryPrimary = 'true';
        focus.focusable(reviewInstall);
        foot.appendChild(reviewInstall);
      } else {
        box.appendChild(el('p', {
          class: 'source-detail-consent',
          text: `Install ${source.name || source.id || 'this source'} on this Hub? The Hub will retrieve and verify the approved manifest. No address is entered or copied from this screen.`,
        }));
        const install = el('button', {
          class: 'btn white', type: 'button', text: installing ? 'Installing…' : 'Install source',
          disabled: installing, onclick: async () => {
            if (installing) return;
            installing = true;
            failure = '';
            render();
            try {
              const result = await withSettingsAdmin(ctx,
                () => api.installReviewedAddon(source.id), setting && setting.lock);
              if (result === null) {
                phase = 'details';
                failure = 'Installation was not completed.';
                return;
              }
              installed = (result && result.addons) || installed;
              reviewedInstalledHere.add(String(source.id || ''));
              ctx.closeModals();
              ctx.toast(`${source.name || 'Source'} installed`);
              paint();
              return;
            } catch (e) {
              failure = (e && e.body && e.body.error) || 'The reviewed source could not be installed';
            } finally {
              installing = false;
              if (box.isConnected) render();
            }
          },
        });
        if (!installing) {
          install.dataset.directoryPrimary = 'true';
          focus.focusable(install);
        }
        foot.appendChild(install);
      }
      box.appendChild(foot);
      const primary = box.querySelector('[data-directory-primary]') || cancel || closeButton;
      if (primary) focus.focusDefault(primary);
    };

    ctx.mountModal(box, () => {
      if (returnFocus && returnFocus.isConnected) focus.setFocus(returnFocus, { scroll: false });
    });
    render();
  }

  function buildReviewedSourceDirectory(parent, { embedded = false } = {}) {
    const g = embedded ? el('div', { class: 'source-directory-lane-body' }) : group('Reviewed source directory',
      'Browse sources this Hub has reviewed. Opening a source shows its capabilities first; installation always asks for an explicit confirmation.');
    const all = sourceDirectoryEntries();

    if (reviewedDirectoryState === 'unavailable' && !all.length) {
      g.appendChild(el('div', { class: 'source-directory-empty source-directory-error', role: 'status',
        text: 'The Hub did not return a reviewed source directory. Advanced manual addresses remain separate and are not a substitute for reviewed entries.' }));
      openRow(g, 'Retry reviewed directory', 'Ask this Hub for its reviewed source list again.', refreshReviewedDirectory, { valueText: 'Retry' });
      parent.appendChild(g);
      return;
    }

    if (!all.length) {
      // The empty directory is intentional. Leaving the lane out makes discovery look broken and can
      // encourage an owner to mistake an arbitrary URL for a recommendation.
      g.appendChild(el('div', { class: 'source-directory-empty', role: 'status',
        text: 'This Hub has not listed any curated sources yet. Community listings and advanced manual addresses remain separate.' }));
      parent.appendChild(g);
      return;
    }

    openRow(g, 'Search reviewed sources',
      'Find a source by name, category, capability, or description.',
      async () => {
        const next = await ctx.enterText({
          title: 'Search reviewed sources',
          reason: 'Search the Hub’s reviewed directory. A keyboard can type or paste; a TV remote uses the on-screen keys.',
          placeholder: 'Name, category, or capability',
          initial: sourceDirectoryQuery,
          action: 'Show results',
        });
        if (next === null) return;
        sourceDirectoryQuery = next.trim().slice(0, 120);
        resetSourceDirectoryPaging('curated');
        paint();
      }, { valueText: sourceDirectoryQuery || 'All' });

    const categories = sourceDirectoryValues('category');
    if (categories.length) {
      pickRow(g, 'Category', 'Narrow the reviewed directory by its Hub-supplied category.',
        ['', ...categories], sourceDirectoryCategory,
        (value) => value ? sourceDirectoryLabel(value) : 'Any category',
        (value) => { sourceDirectoryCategory = value; resetSourceDirectoryPaging('curated'); paint(); });
    }
    const types = sourceDirectoryValues('type');
    if (types.length) {
      pickRow(g, 'Content type', 'Show sources that support a particular kind of content.',
        ['', ...types], sourceDirectoryType,
        (value) => value ? sourceDirectoryLabel(value) : 'Any content type',
        (value) => { sourceDirectoryType = value; resetSourceDirectoryPaging('curated'); paint(); });
    }
    if (sourceDirectoryQuery || sourceDirectoryCategory || sourceDirectoryType) {
      openRow(g, 'Clear directory filters', 'Return to every reviewed source in this Hub.', () => {
        sourceDirectoryQuery = '';
        sourceDirectoryCategory = '';
        sourceDirectoryType = '';
        resetSourceDirectoryPaging('curated');
        paint();
      }, { valueText: 'Clear' });
    }

    const results = reviewedDirectoryResults();
    const resultCount = el('span', {
      class: 'source-directory-result-count', role: 'status', 'aria-live': 'polite', 'aria-atomic': 'true',
    });
    g.appendChild(el('div', { class: 'source-directory-result-head' },
      resultCount,
      reviewedDirectoryState === 'refreshing'
        ? el('span', { class: 'source-directory-refreshing', text: 'Refreshing…' })
        : reviewedDirectoryState === 'unavailable'
          ? el('span', { class: 'source-directory-unavailable', text: 'Refresh unavailable · showing the last list' })
          : null));
    const list = el('div', { class: 'source-directory-results' });
    renderSourceDirectoryPage({
      lane: 'curated', label: 'reviewed', results, list, count: resultCount,
      emptyText: 'No reviewed sources match these filters. Clear them to browse the complete directory.',
      makeCard: (source) => {
        const installedHere = sourceDirectoryIsInstalled(source);
        const installUnavailable = sourceDirectoryInstallUnavailable(source);
        const tags = [
          ...sourceDirectoryCategories(source),
          ...sourceDirectoryTypes(source),
        ].slice(0, 5);
        const card = el('button', {
          class: 'source-directory-card li act', type: 'button', 'data-nav': 'h',
          'aria-label': `${source.name || source.id || 'Reviewed source'}. ${installedHere
            ? 'Installed.' : installUnavailable ? 'Setup required. Open details.' : 'Reviewed source. Open details.'}`,
        },
        el('div', { class: 'source-directory-card-main' },
          el('div', { class: 'source-directory-card-head' },
            el('span', { class: 'source-directory-card-name', text: source.name || source.id || 'Reviewed source' }),
            el('span', { class: `source-directory-state${installedHere ? ' installed' : installUnavailable ? ' unavailable' : ''}`,
              text: installedHere ? 'Installed' : installUnavailable ? 'Setup required' : 'Reviewed' })),
          el('span', { class: 'source-directory-card-description', text: sourceDirectoryDescription(source) })),
        el('div', { class: 'source-directory-tags' },
          ...tags.map((tag) => el('span', { class: 'source-directory-tag', text: sourceDirectoryLabel(tag) })),
          source.version ? el('span', { class: 'source-directory-version', text: `v${source.version}` }) : null));
        card.onclick = () => openReviewedSourceDetail(source, card);
        focus.focusable(card);
        return card;
      },
    });
    g.appendChild(list);
    parent.appendChild(g);
  }

  /** Community source detail keeps the non-endorsement acknowledgement separate from reviewed installs. */
  function openCommunitySourceDetail(source, returnFocus) {
    const box = el('div', { class: 'mbox source-detail community-source-detail', role: 'dialog', 'aria-modal': 'true' });
    let phase = 'details';
    let acknowledged = false;
    let installing = false;
    let failure = '';

    const close = () => ctx.closeModals();
    const render = (restore = '') => {
      box.textContent = '';
      const closeButton = el('button', {
        class: 'mclose', type: 'button', text: '✕', 'aria-label': 'Close community source details', title: 'Close', onclick: close,
      });
      focus.focusable(closeButton);
      box.appendChild(closeButton);

      const installedHere = communityDirectoryIsInstalled(source);
      const installUnavailable = communityDirectoryInstallUnavailable(source);
      const setupAvailable = sourceSetupAvailable(source);
      const types = sourceDirectoryTypes(source);
      const resources = sourceDirectoryResources(source);
      const safety = [];
      if (source && source.adult === true) safety.push('May include adult material');
      if (source && source.p2p === true) safety.push('Uses peer-to-peer delivery');
      if (!safety.length) safety.push('No adult or peer-to-peer indicator was supplied');

      box.appendChild(el('div', { class: 'source-detail-kicker community', text: installedHere
        ? 'Installed community source' : installUnavailable ? 'Community source · setup required' : 'Community source · unreviewed' }));
      box.appendChild(el('h2', { text: source.name || source.id || 'Community source' }));
      box.appendChild(el('p', { class: 'source-detail-description', text: communityDirectoryDescription(source) }));
      box.appendChild(el('div', { class: 'source-detail-facts' },
        sourceDirectoryFact('Trust', 'Community-published · not reviewed or endorsed by this Hub'),
        sourceDirectoryFact('Version', source.version ? `v${source.version}` : 'Version not supplied'),
        sourceDirectoryFact('Safety signals', safety.join(' · ')),
        sourceDirectoryFact('Setup', source && source.configurationRequired === true
          ? setupAvailable ? 'Configuration required — secure web setup' : 'Configuration required — unavailable here'
          : source && source.configurable === true ? 'May need setup after installation' : 'No setup declared'),
        types.length ? sourceDirectoryFact('Supports', types.map(sourceDirectoryLabel).join(' · ')) : null,
        resources.length ? sourceDirectoryFact('Provides', resources.map(sourceDirectoryLabel).join(' · ')) : null,
      ));
      box.appendChild(el('p', { class: 'source-detail-notice source-detail-community-notice',
        text: 'Community publication is not an endorsement. Review privacy, legality, availability, and the safety signals above before installing. This screen never exposes a manifest address.' }));
      if (installUnavailable) {
        box.appendChild(el('p', { class: 'source-detail-unavailable', role: 'status',
          text: communityDirectoryInstallUnavailableMessage(source) }));
      }
      if (failure) box.appendChild(el('p', { class: 'source-detail-error', role: 'alert', text: failure }));

      const foot = el('div', { class: 'mfoot source-detail-actions' });
      const cancel = el('button', {
        class: 'btn line', type: 'button', text: phase === 'confirm' ? 'Back' : 'Close', onclick: () => {
          if (phase === 'confirm') { phase = 'details'; acknowledged = false; failure = ''; render(); }
          else close();
        },
      });
      focus.focusable(cancel);
      foot.appendChild(cancel);

      let acknowledgementControl = null;
      let installControl = null;
      if (installedHere) {
        box.appendChild(el('p', { class: 'source-detail-installed', text: 'Installed on this Hub. Manage it in Installed sources below.' }));
      } else if (installUnavailable && !setupAvailable) {
        // A Community entry never falls back to manual URL entry when a safe handoff is unavailable.
      } else if (phase === 'details') {
        const reviewRisks = el('button', {
          class: 'btn white', type: 'button', text: 'Review community risks', onclick: () => { phase = 'confirm'; failure = ''; render('ack'); },
        });
        reviewRisks.dataset.directoryPrimary = 'true';
        focus.focusable(reviewRisks);
        foot.appendChild(reviewRisks);
      } else {
        box.appendChild(el('p', { class: 'source-detail-consent',
          text: setupAvailable
            ? `Start secure browser setup for ${source.name || source.id || 'this community source'}? The Hub will preserve its Community trust boundary and re-check the cached descriptor before it installs it.`
            : `Install ${source.name || source.id || 'this community source'} on this Hub? The Hub will re-check the cached descriptor before it installs it.`,
        }));
        acknowledgementControl = el('button', {
          class: `source-detail-ack${acknowledged ? ' on' : ''}`,
          type: 'button', role: 'checkbox', 'aria-checked': acknowledged ? 'true' : 'false',
          'aria-label': 'Acknowledge that this community source is not reviewed or endorsed',
          text: acknowledged
            ? 'Acknowledged: this is a community listing, not a reviewed recommendation'
            : 'Acknowledge: this is a community listing, not a reviewed recommendation',
          onclick: () => { acknowledged = !acknowledged; render('ack'); },
        });
        focus.focusable(acknowledgementControl);
        box.appendChild(acknowledgementControl);
        installControl = el('button', {
          class: 'btn white source-detail-community-install', type: 'button',
          text: installing ? (setupAvailable ? 'Starting setup…' : 'Installing…')
            : (setupAvailable ? 'Start secure browser setup' : 'Install community source'),
          disabled: !acknowledged || installing,
          'aria-disabled': !acknowledged || installing ? 'true' : 'false',
          onclick: async () => {
            if (!acknowledged || installing) return;
            installing = true;
            failure = '';
            render('ack');
            try {
              if (setupAvailable) {
                close();
                await startCommunitySourceSetup(source);
                return;
              }
              const result = await withSettingsAdmin(ctx,
                () => api.installCommunityAddon(source.id, true), setting && setting.lock);
              if (result === null) {
                failure = 'Installation was not completed.';
                return;
              }
              installed = (result && result.addons) || installed;
              communityInstalledHere.add(String(source.id || ''));
              ctx.closeModals();
              ctx.toast(`${source.name || 'Community source'} installed`);
              paint();
              return;
            } catch (e) {
              failure = (e && e.body && e.body.error) || 'The community source could not be installed';
            } finally {
              installing = false;
              if (box.isConnected) render('ack');
            }
          },
        });
        if (acknowledged && !installing) {
          installControl.dataset.directoryPrimary = 'true';
          focus.focusable(installControl);
        }
        foot.appendChild(installControl);
      }
      box.appendChild(foot);
      const primary = restore === 'ack' ? acknowledgementControl
        : box.querySelector('[data-directory-primary]') || acknowledgementControl || cancel || closeButton;
      if (primary) focus.focusDefault(primary);
    };

    ctx.mountModal(box, () => {
      if (returnFocus && returnFocus.isConnected) focus.setFocus(returnFocus, { scroll: false });
    });
    render();
  }

  function buildCommunitySourceDirectory(parent, { embedded = false } = {}) {
    const g = embedded ? el('div', { class: 'source-directory-lane-body' }) : group('Community source directory',
      'Community listings are not reviewed or endorsed. Read the safety signals and acknowledge that boundary before installing.');
    const all = communityDirectoryEntries();

    if (communityDirectoryState === 'loading' && !all.length) {
      g.appendChild(el('div', { class: 'source-directory-empty', role: 'status',
        text: 'Checking this Hub’s community source directory…' }));
      parent.appendChild(g);
      return;
    }
    if (communityDirectoryState === 'unavailable' && !all.length) {
      g.appendChild(el('div', { class: 'source-directory-empty source-directory-error', role: 'status',
        text: 'The Hub could not provide community sources. Curated sources and advanced manual addresses remain separate.' }));
      openRow(g, 'Retry community directory', 'Ask this Hub for its non-endorsed community list again.', refreshCommunityDirectory, { valueText: 'Retry' });
      parent.appendChild(g);
      return;
    }
    if (!all.length) {
      g.appendChild(el('div', { class: 'source-directory-empty', role: 'status',
        text: 'This Hub’s community directory is empty right now. A missing listing is not a recommendation to use an advanced manual address.' }));
      parent.appendChild(g);
      return;
    }

    const notice = sourceDirectoryString(communityDirectory && communityDirectory.notice)
      || 'Community sources are not reviewed or endorsed. Review privacy, legality, and availability before installation.';
    g.appendChild(el('p', { class: 'source-directory-community-notice', role: 'status', text: notice }));
    openRow(g, 'Search community sources',
      'Find a community listing by name, content type, capability, or description.',
      async () => {
        const next = await ctx.enterText({
          title: 'Search community sources',
          reason: 'Search only the Hub’s redacted community descriptors. A keyboard can type or paste; a TV remote uses on-screen keys.',
          placeholder: 'Name, type, or capability',
          initial: communityDirectoryQuery,
          action: 'Show results',
        });
        if (next === null) return;
        communityDirectoryQuery = next.trim().slice(0, 80);
        resetSourceDirectoryPaging('community');
        paint();
      }, { valueText: communityDirectoryQuery || 'All' });
    const types = communityDirectoryTypes();
    if (types.length) {
      pickRow(g, 'Content type', 'Show community sources that declare a particular content type.',
        ['', ...types], communityDirectoryType,
        (value) => value ? sourceDirectoryLabel(value) : 'Any content type',
        (value) => { communityDirectoryType = value; resetSourceDirectoryPaging('community'); paint(); });
    }
    if (communityDirectoryQuery || communityDirectoryType) {
      openRow(g, 'Clear community filters', 'Return to every community listing this Hub supplied.', () => {
        communityDirectoryQuery = '';
        communityDirectoryType = '';
        resetSourceDirectoryPaging('community');
        paint();
      }, { valueText: 'Clear' });
    }

    const results = communityDirectoryResults();
    const resultCount = el('span', {
      class: 'source-directory-result-count', role: 'status', 'aria-live': 'polite', 'aria-atomic': 'true',
    });
    g.appendChild(el('div', { class: 'source-directory-result-head' },
      resultCount,
      communityDirectoryState === 'refreshing'
        ? el('span', { class: 'source-directory-refreshing', text: 'Refreshing…' })
        : communityDirectoryState === 'unavailable'
          ? el('span', { class: 'source-directory-unavailable', text: 'Refresh unavailable · showing the last list' })
          : null));
    const list = el('div', { class: 'source-directory-results' });
    renderSourceDirectoryPage({
      lane: 'community', label: 'community', results, list, count: resultCount,
      emptyText: 'No community sources match these filters. Clear them to browse this Hub’s complete community list.',
      makeCard: (source) => {
        const installedHere = communityDirectoryIsInstalled(source);
        const installUnavailable = communityDirectoryInstallUnavailable(source);
        const tags = [
          ...communityDirectorySafetyTags(source),
          ...sourceDirectoryTypes(source).map((label) => ({ label: sourceDirectoryLabel(label), tone: 'neutral' })),
        ].slice(0, 5);
        const safetyDescription = [
          'Community listing, not reviewed or endorsed.',
          source.adult === true ? 'May include adult material.' : '',
          source.p2p === true ? 'Uses peer-to-peer delivery.' : '',
          installUnavailable ? 'Setup required.' : '',
        ].filter(Boolean).join(' ');
        const card = el('button', {
          class: 'source-directory-card source-directory-community-card li act', type: 'button', 'data-nav': 'h',
          'aria-label': `${source.name || source.id || 'Community source'}. ${installedHere
            ? 'Installed.' : installUnavailable ? 'Setup required. Open details.' : safetyDescription + ' Open details.'}`,
        },
        el('div', { class: 'source-directory-card-main' },
          el('div', { class: 'source-directory-card-head' },
            el('span', { class: 'source-directory-card-name', text: source.name || source.id || 'Community source' }),
            el('span', { class: `source-directory-state community${installedHere ? ' installed' : installUnavailable ? ' unavailable' : ''}`,
              text: installedHere ? 'Installed' : installUnavailable ? 'Setup required' : 'Unreviewed' })),
          el('span', { class: 'source-directory-card-description', text: communityDirectoryDescription(source) })),
        el('div', { class: 'source-directory-tags' },
          ...tags.map((tag) => el('span', { class: `source-directory-tag ${tag.tone}`, text: tag.label })),
          source.version ? el('span', { class: 'source-directory-version', text: `v${source.version}` }) : null));
        card.onclick = () => openCommunitySourceDetail(source, card);
        focus.focusable(card);
        return card;
      },
    });
    g.appendChild(list);
    parent.appendChild(g);
  }

  function buildSourceDirectory(parent) {
    const g = group('Discover sources',
      'Curated sources are reviewed by this Hub. Community listings are unreviewed and require an explicit acknowledgement before installation.');
    g.appendChild(sourceDirectoryLanePicker());
    openRow(g, 'Finish a source setup code',
      'Complete a setup that was started on this TV. The short code expires after ten minutes; configuration details stay off the TV.',
      () => finishSourceSetupCode(), { valueText: 'Enter code' });
    if (sourceDirectoryLane === 'community') buildCommunitySourceDirectory(g, { embedded: true });
    else buildReviewedSourceDirectory(g, { embedded: true });
    parent.appendChild(g);
  }

  function sourceState(ad, detail) {
    // This is a rendering of observations the Hub already has, not a fresh probe. Opening Settings
    // must stay cheap and private: a source page cannot wake every third-party provider just to paint
    // a badge, and a quiet upstream must not turn into a timing oracle for this browser.
    if (ad.enabled === false) return { value: 'Off', note: `${detail} · kept here but not used` };
    if (ad.error) return { value: 'Attention', note: `Needs attention · ${String(ad.error).slice(0, 60)}` };
    if (ad.native) return { value: 'On', note: detail };
    const lastFetch = Number(ad.counts && ad.counts.lastFetch) || 0;
    return lastFetch > 0
      ? { value: 'Ready', note: `${detail} · last refresh is available` }
      : { value: 'Starting', note: `${detail} · waiting for its first refresh` };
  }

  function buildAddons(parent) {
    buildSourceDirectory(parent);

    const g = group('Installed sources', 'Sources this hub reads. A fresh Scenix includes Public Domain Movies, a built-in shelf of films in the U.S. public domain; switching a source off keeps its address and settings, and the hub simply stops asking it.');
    for (const ad of installed || []) {
      const cats = (ad.catalogs || []).length;
      const detail = ad.native
        ? `Built in${cats ? ` · ${cats} catalogue${cats > 1 ? 's' : ''}` : ''}`
        : `${ad.provenance === 'reviewed' ? 'Reviewed source'
          : ad.provenance === 'community' ? 'Community source · unreviewed'
            : 'Advanced manual source'}${cats ? ` · ${cats} catalogue${cats > 1 ? 's' : ''}` : ''}`;
      const state = sourceState(ad, detail);
      const name = ad.name || ad.id;
      actionRow(g, name, state.note, {
        valueText: state.value,
        actions: () => {
          const on = ad.enabled !== false;
          const acts = [{
            label: on ? `Turn ${name} off` : `Turn ${name} on`,
            run: async () => {
              try {
                const out = await withSettingsAdmin(ctx,
                  () => api.setAddon(ad.id, !on), setting && setting.lock);
                if (out === null) return;
                if (out && out.addon) {
                  installed = (installed || []).map((item) => item.id === ad.id
                    ? Object.assign({}, item, out.addon) : item);
                }
                ctx.toast(`${name} ${on ? 'off' : 'on'}`);
              }
              catch (e) { ctx.toast('The hub would not change that add-on'); }
              paint();
            },
          }];
          if (!ad.native) acts.push({
            label: `Remove ${name}`, danger: true,
            run: async () => {
              try {
                const r = await withSettingsAdmin(ctx,
                  () => api.removeAddon(ad.id), setting && setting.lock);
                if (r === null) return;
                installed = (r && r.addons) || (installed || []).filter((o) => o.id !== ad.id);
                // Older Hub responses do not expose a reviewed registry id on an installed row. Clearing
                // the short-lived acknowledgement avoids leaving a removed source marked installed.
                reviewedInstalledHere.clear();
                communityInstalledHere.clear();
                ctx.toast(`${name} removed`);
              } catch (e) { ctx.toast('The hub would not remove that add-on'); }
              paint();
            },
          });
          return acts;
        },
      });
    }
    parent.appendChild(g);

    const manual = group('Advanced manual source',
      'Use this only when you already trust a public HTTP(S) manifest address. Manual sources are unreviewed and never appear in the reviewed directory.');
    openRow(manual, 'Add an advanced manual source',
      'Type an unreviewed public HTTP(S) manifest address. The Hub validates it before keeping it, but its upstream behavior has not been curated by this build.',
      async () => {
        const url = await ctx.enterText({
          title: 'Add an advanced manual source',
          reason: 'The public HTTP(S) manifest address of a source, for example https://example.org/manifest.json. A folder address works too: the Hub appends the manifest name.',
          placeholder: 'https://…/manifest.json',
          action: 'Add',
          validate: (v) => (!v ? 'Type an address first.'
            : !/^https?:\/\//i.test(v) ? 'That has to start with http:// or https://.' : null),
        });
        if (!url) return;
        try {
          const r = await withSettingsAdmin(ctx, () => api.addAddon(url), setting && setting.lock);
          if (r === null) return;
          installed = (r && r.addons) || installed;
          ctx.toast(`${(r && r.addon && r.addon.name) || 'Add-on'} is now a source here`);
          paint();
        } catch (e) {
          ctx.toast((e && e.body && e.body.error) || 'The hub would not take that add-on');
        }
      }, { valueText: null });
    parent.appendChild(manual);
  }

  /** Sources are kept together: discovery, installed add-ons, manual addresses, and owner-managed packs. */
  function buildSources(parent) {
    buildAddons(parent);
    buildHub(parent, { includeHub: false, includeSourcePacks: true, includeDiagnostics: false });
  }

  function buildHub(parent, {
    includeHub = true,
    includeSourcePacks = false,
    includeDiagnostics = true,
  } = {}) {
    const canHub = !!caps.settingsAdmin;

    if (includeHub && setting) {
      const g = group('This hub', 'Set once for the whole house. The values below are what the hub is running with.');
      const name = line('Name', 'The word that appears on the splash screen and in the launcher.');
      name.appendChild(value(setting.hubName));
      g.appendChild(name);
      // The television's This TV pane has a Hub connection row; on the web the equivalent fact is the
      // address that reaches this hub. It is the same string the TV's Connect from phone QR encodes, so
      // a person can type it into a second device without hunting for it in a terminal.
      const address = line('Address', 'Open this on a phone or another device. The television shows the same address as a QR under This TV → Connect from phone.');
      address.appendChild(value(apiBase()));
      g.appendChild(address);
      // The build the household is looking at, stated on the page instead of only in a terminal.
      // It also answers the question a stale phone tab raises: a page that looks old with a current
      // commit behind it is the page, not the hub.
      const build = line('Build', 'The commit this hub runs. If a screen looks like an older design, compare this mark with the one at the foot of Home — a page can be older than the hub serving it.');
      const buildValue = value('checking…');
      build.appendChild(buildValue);
      g.appendChild(build);
      hubBuild().then((s) => {
        if (!buildValue.isConnected) return;
        const label = buildLabel(s);
        buildValue.textContent = label || 'not reported by this hub';
      });
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
            try {
              const out = await withSettingsAdmin(ctx, () => api.clearCache(), setting && setting.lock);
              if (out === null) return;
              ctx.toast('Cache cleared');
            }
            catch (e) { ctx.toast('The hub would not clear the cache'); }
          }, { valueText: null });
      }
      if (caps.hubUpdate) {
        openRow(g, 'Update the hub',
          'The hub pulls the newest Scenix code for itself and restarts for a moment — updating the app updates the hub too.',
          async () => {
            ctx.toast('Checking the hub’s build…');
            try {
              const s = await api.hubUpdate();
              if (!s.git) { ctx.toast(s.blocked || 'This hub is not a git checkout'); return; }
              if (!s.behind) { ctx.toast(`The hub is already on the newest build (${s.commit})`); return; }
              if (!s.canUpdate) { ctx.toast(`${s.behind} commits behind, but: ${s.blocked}`); return; }
              ctx.toast('Pulling — the hub restarts in a moment…');
              const r = await api.hubUpdateNow();
              ctx.toast(r.updated ? `The hub moved ${r.from} → ${r.to}; reload this page in a few seconds`
                : `The hub declined: ${r.reason}`);
            } catch (e) {
              ctx.toast('The hub would not update: ' + e.message);
            }
          }, { valueText: null });
      }
      parent.appendChild(g);
    }

    /* Source packs: the owner's own rows, kept as descriptors in settings rather than as add-ons.
       A picture pack contributes copies from a URL template; an audio pack contributes audio-only rows
       the hub mixes into a picture that does not carry the household's language. The hub's code never
       learns the name of either host — it only reads what the owner typed here. */
    if (includeSourcePacks && setting && caps.addonAdmin) {
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
                  if (await writePack(next)) paint();
                },
              }, {
                label: 'Change the address',
                run: async () => {
                  const url = await ask('Source address',
                    'The address of this source. It may contain {title}, {year}, {type}, {season}, {episode}, {identity} and {lang}.',
                    d.url, (v) => (!v ? 'Type an address first.'
                      : !/^https?:\/\//i.test(v) ? 'That has to start with http:// or https://.' : null));
                  if (url === null) return;
                  if (await writePack(list.map((x) => (x.id === d.id ? { ...x, url } : x)))) paint();
                },
              }, {
                label: 'Change the languages',
                run: async () => {
                  const raw = await ask('Languages',
                    'Comma-separated codes, in the order you want them offered: hin, eng, pan…',
                    (d.languages || []).join(', '));
                  if (raw === null) return;
                  const languages = raw.split(/[,\s]+/).map((s) => s.trim().toLowerCase()).filter(Boolean);
                  if (await writePack(list.map((x) => (x.id === d.id ? { ...x, languages } : x)))) paint();
                },
              }, {
                label: `Remove ${shown}`, danger: true,
                run: async () => {
                  if (await writePack(list.filter((x) => x.id !== d.id))) paint();
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
          if (await writePack([...list, { id, name: name.trim(), url: url.trim(), languages, enabled: true }])) {
            ctx.toast(`${name.trim()} added`);
            paint();
          }
        }, { valueText: null });
        parent.appendChild(g);
      }
    }

    // The readout is for whoever fixes the box, not for whoever watches: it stays one deliberate step
    // behind a switch, so a viewer walking the pane never reads a wall of capability jargon.
    if (includeDiagnostics) toggle(parent, 'Show advanced', 'What this hub reports about itself right now: the address it was reached on, what it can do, and what it last tried.',
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
    api.health().then((health) => {
      if (health && health.version) row('Hub build', `v${health.version}`, 'mid');
      row('Hub reachable', 'yes', 'ok');
    }).catch(() => row('Hub reachable', 'no answer', 'no'));
    return d;
  }

  /* --------------------------------------------------------------------- keys */

  // BACK unwinds this screen before the router unwinds the route: from any section but the first it
  // returns the rail to Overview, which is what "go back" means on a control centre with no history. From the
  // first section it is left to the router, which leaves Settings the way it leaves any screen.
  // (A dial or action list that is open is handled above this screen in the key stack: Back there
  // cancels the modal and never reaches this handler.)
  //
  // LEFT and RIGHT get the same treatment at the columns' edges. The focus engine's geometry crosses
  // honestly most of the time, but a section whose first control sits at the far right edge (Watching)
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
    const [s, p, a3, r4] = await Promise.all([
      api.settings().catch(() => null),
      profile.id ? api.prefs(profile.id).catch(() => null) : Promise.resolve(null),
      caps.addonAdmin ? api.addons().catch(() => null) : Promise.resolve(null),
      caps.addonAdmin ? api.reviewedAddons().catch(() => null) : Promise.resolve(null),
    ]);
    rowList = Array.isArray(s && s.rows) ? s.rows : null;
    catalogOptions = Array.isArray(s && s.catalogOptions) ? s.catalogOptions : null;
    // The Hub already projects this list to browser-capable shared themes. Leaving it unassigned
    // hid the Appearance → Theme row completely even though the browser had matching token assets.
    themeChoices = Array.isArray(s && s.themes) ? s.themes : [];
    const ad = a3 ? (a3.addons || []) : [];
    installed = Array.isArray(ad) ? ad : [];
    applyReviewedDirectory(r4);
    setting = s ? (s.settings || s) : null;
    liveInfo = (s && s.live) || null;
    debridInfo = (s && s.debrid) || null;
    names = (s && s.languageNames) || {};
    prefs = p ? (p.prefs || p) : null;
    // A slow hub answer must not paint, or raise a PIN dialog, over the screen the viewer already left.
    if (!root.isConnected || (ctx.route() && ctx.route().name !== 'settings')) return;
    // The common gate is also used by direct profile management and the nested administration screens.
    // It owns the Settings-PIN pad and session expiry recovery, so this page cannot drift into a second
    // keypad or leave a viewer at a protected row after its short-lived capability expires.
    if (setting && setting.lock && setting.lock.enabled === true) {
      pane.textContent = '';
      pane.appendChild(el('div', { class: 'row-err' }, el('span', { text: 'This settings page is locked.' })));
      const open = await ensureSettingsAdmin(ctx, setting.lock);
      if (!open) { ctx.back(); return; }
    }
    paint();
    // Community discovery is optional and must not delay a usable Settings page or make curated
    // discovery wait on a remote catalogue. Its lane paints an honest loading/unavailable state until
    // this bounded Hub request resolves.
    if (caps.addonAdmin) refreshCommunityDirectory({ quiet: true });
  })();

  // The ring enters on the rail: the first thing a viewer does in Settings is choose a section, not
  // hunt for a control. The rail is empty until the hub answers, so `__defaultFocus` may return null
  // on the very first frame — `paint` puts the ring on the first rail item when the sections arrive.
  root.__defaultFocus = () => rail.querySelector('.sitem');
  root.__dispose = () => {
    // The focus engine keeps global listeners. Both its rail subscription and the screen's Back-key
    // handler must leave together; replacing one disposer with the other leaks Settings into later routes.
    onRailFocus();
    if (handler) popHandler(handler);
  };
  return root;
}
