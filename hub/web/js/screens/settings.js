/**
 * js/screens/settings.js — this hub's own configuration, drawn from what the hub actually reports.
 *
 * Two rules keep this page from becoming a wall of controls that do nothing:
 *
 *   · Every value is read from `/app/v1/settings` or `/app/v1/prefs`, and every control writes back through
 *     the same endpoint. Nothing here carries a default of its own — if the hub has no value for something,
 *     the row does not appear.
 *   · A row is only editable when the manifest says the browser may change it (`settingsAdmin`, `prefsEdit`,
 *     `addonAdmin`, `profileEdit`, `cacheAdmin`). Values that would need a sentence typed — a playlist URL,
 *     an addon path — are shown rather than edited, with a line naming where they belong. A remote and a
 *     touch screen can both move a chip or a +/− button; neither should be offered a phantom text field.
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

export function screen(ctx) {
  const root = el('div', { class: 'screen settings' });
  const profile = ctx.profile || {};
  const manifest = ctx.manifest() || {};
  const caps = manifest.capabilities || {};

  let setting = null;      // /app/v1/settings → settings
  let names = {};          // /app/v1/settings → languageNames, supplied by the hub
  let prefs = null;        // /app/v1/prefs → prefs
  let busy = false;

  root.appendChild(el('div', { class: 'page-head' },
    el('h1', { text: 'Settings' }),
    el('div', { class: 'sub', text: 'What your hub is configured to do, and what this profile asks for inside it.' })));
  const cols = el('div', { class: 'settings-cols' });
  const left = el('div');
  const right = el('div');
  cols.appendChild(left);
  cols.appendChild(right);
  root.appendChild(cols);
  const pending = el('div', { class: 'row-err' }, el('span', { text: 'Reading this hub’s configuration…' }));
  left.appendChild(pending);

  /* ------------------------------------------------------------------- parts */

  function group(title, note) {
    const g = el('div', { class: 'sgroup' }, el('h3', { text: title }));
    if (note) g.appendChild(el('p', { class: 'note', text: note }));
    return g;
  }
  function line(label, note) {
    const li = el('div', { class: 'li' },
      el('div', {}, el('span', { text: label }), note ? el('span', { class: 'note', text: note }) : null));
    return li;
  }
  function value(text) { return el('span', { class: 'lv', text: String(text == null ? '—' : text) }); }
  function roundBtn(name, fn, aria) {
    const b = el('button', { class: 'round', type: 'button', 'aria-label': aria, title: aria, onclick: fn }, icon(name));
    focus.focusable(b);
    return b;
  }
  /** A number moved with + and − rather than typed: the only editing a remote and a touch screen share. */
  function stepper(parent, label, note, values, current, write, fmt) {
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
      write(values[i]);
    };
    const li = line(label, note);
    li.appendChild(lv);
    li.appendChild(roundBtn('minus', () => step(-1), `Less ${label}`));
    li.appendChild(roundBtn('plus', () => step(1), `More ${label}`));
    parent.appendChild(li);
  }
  function toggle(parent, label, note, on, write) {
    const sw = el('button', { class: `sw${on ? ' on' : ''}`, type: 'button', role: 'switch', 'aria-checked': on ? 'true' : 'false' }, el('i'));
    focus.focusable(sw);
    sw.onclick = async () => {
      const next = !sw.classList.contains('on');
      sw.classList.toggle('on', next);
      sw.setAttribute('aria-checked', next ? 'true' : 'false');
      await write(next);
    };
    const li = line(label, note);
    li.appendChild(sw);
    parent.appendChild(li);
  }
  /** Chips for a set of codes or levels. Read-only rows get the same chips, without the write. */
  function chipSet(parent, options, selected, labelFor, write) {
    const wrap = el('div', { class: 'chips' });
    for (const o of options) {
      let isOn = false;
      for (const s of selected) if (String(s) === String(o)) isOn = true;
      const b = el('button', { class: `chip${isOn ? ' on' : ''}`, type: 'button', text: labelFor(o) }, );
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
  }
  /** One choice out of a list — quality, unlike languages: turning one on turns the others off. */
  function radioSet(parent, options, current, labelFor, write) {
    const wrap = el('div', { class: 'chips' });
    const buttons = [];
    for (const o of options) {
      const b = el('button', { class: `chip${String(o) === String(current) ? ' on' : ''}`, type: 'button', text: labelFor(o) });
      b.onclick = () => {
        if (!write) return ctx.toast('This hub is read-only from a browser');
        for (const k of buttons) k.classList.remove('on');
        b.classList.add('on');
        write(o);
      };
      focus.focusable(b);
      buttons.push(b);
      wrap.appendChild(b);
    }
    parent.appendChild(wrap);
  }

  /* ------------------------------------------------------------------ writes */

  async function writePrefs(patch) {
    if (busy) return;
    busy = true;
    Object.assign(prefs, patch);
    try {
      await api.savePrefs(profile.id, prefs);
      ctx.toast('Saved for this profile');
    } catch (e) {
      ctx.toast('The hub would not take that change');
    }
    busy = false;
  }
  async function writeSettings(patch) {
    if (busy) return;
    busy = true;
    const next = Object.assign({}, setting, patch);
    let ok = true;
    try { await api.saveSettings({ settings: next }); }
    catch (e) {
      // Older builds accept the object flat rather than wrapped in `settings`.
      try { await api.saveSettings(next); } catch (e2) { ok = false; }
    }
    if (ok) { setting = next; ctx.toast('Saved on the hub'); } else ctx.toast('The hub would not take that change');
    busy = false;
  }

  /* ------------------------------------------------------------------ paint */

  function paint() {
    left.textContent = '';
    right.textContent = '';
    const canPrefs = !!caps.prefsEdit;
    const canHub = !!caps.settingsAdmin;

    /* ------------------------------------------------ playback limits (profile) */
    if (prefs) {
      const g = group('Quality and size', 'Ceilings this profile asks the hub to keep to. They narrow which copies are offered, not what the hub can find.');
      if (Array.isArray(prefs.qualityLevels) && prefs.qualityLevels.length) {
        const words = { auto: 'Let the hub choose' };
        radioSet(g, prefs.qualityLevels, prefs.qualityCap == null ? 'auto' : prefs.qualityCap,
          (o) => words[o] || o, canPrefs ? (v) => writePrefs({ qualityCap: v }) : null);
        if (!canPrefs) g.appendChild(el('p', { class: 'note', text: 'This hub lets a browser read its settings but not change them.' }));
      }
      if (Number.isFinite(Number(prefs.maxSizeGB))) {
        stepper(g, 'Largest file', 'Anything bigger than this is left out of the copy list.',
          [0, 2, 4, 6, 8, 12, 16, 24, 40], Number(prefs.maxSizeGB),
          canPrefs ? (v) => writePrefs({ maxSizeGB: v }) : () => {},
          (v) => (v === 0 ? 'No cap' : `${v} GB`));
      }
      if (prefs.maxBitrateMbps !== undefined) {
        stepper(g, 'Bitrate ceiling', 'A live or marginal connection stays watchable below this line.',
          [0, 3, 5, 8, 12, 20, 40], Number(prefs.maxBitrateMbps || 0),
          canPrefs ? (v) => writePrefs({ maxBitrateMbps: v }) : () => {},
          (v) => (v === 0 ? 'Unlimited' : `${v} Mbps`));
      }
      if (prefs.preferAdaptive !== undefined) {
        toggle(g, 'Prefer a stream that adapts', 'Keep watching through a slow network by changing quality mid-playback, instead of buffering.',
          !!prefs.preferAdaptive, canPrefs ? (v) => writePrefs({ preferAdaptive: v }) : () => {});
      }
      left.appendChild(g);

      /* ------------------------------------------------------- languages (profile) */
      /* The hub answers with both the modern and the legacy code for a few languages. Two chips that read
         "French" are one choice to a viewer, so one chip per name is kept — preferring the code this profile
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
        g2.appendChild(el('p', { class: 'note', text: 'Audio you expect' }));
        chipSet(g2, langKeys, prefs.audioLanguages || [], (k) => names[k] || k,
          canPrefs ? (list) => writePrefs({ audioLanguages: list }) : null);
        g2.appendChild(el('p', { class: 'note', text: 'Subtitles you expect' }));
        chipSet(g2, langKeys, prefs.subtitleLanguages || [], (k) => names[k] || k,
          canPrefs ? (list) => writePrefs({ subtitleLanguages: list }) : null);
        if (prefs.xxxEnabled !== undefined) {
          toggle(g2, 'Include adult titles', 'Off keeps them out of the shelves and out of search for this profile.',
            !!prefs.xxxEnabled, canPrefs ? (v) => writePrefs({ xxxEnabled: v }) : () => {});
        }
        left.appendChild(g2);
      }
    }

    /* --------------------------------------------------------------- the hub */
    if (setting) {
      const g = group('This hub', 'Set once for the whole house. The values below are what the hub is running with.');
      const name = line('Name', 'The word that appears on the splash screen and in the launcher.');
      name.appendChild(value(setting.hubName));
      g.appendChild(name);
      if (Array.isArray(setting.languages)) {
        const l = line('Languages the addons search', 'Which audio and subtitle languages this hub asks its addons for.');
        l.appendChild(value(langList(setting.languages)));
        g.appendChild(l);
        if (canHub) chipSet(g, Object.keys(names), setting.languages, (k) => names[k] || k, (list) => writeSettings({ languages: list }));
      }
      if (setting.live && setting.live.playlist) {
        const l = line('Live TV playlist', 'A channel list the hub reads at startup. It is edited in the hub’s settings file, where a URL can be typed comfortably.');
        l.appendChild(value(setting.live.playlist));
        g.appendChild(l);
      }
      if (caps.addonAdmin && setting.addons) {
        stepper(g, 'Copies per title', 'How many sources the hub is allowed to rank before it stops looking.',
          [1, 2, 4, 6, 8, 12, 16, 24], Number(setting.addons.maxSources),
          (v) => writeSettings({ addons: Object.assign({}, setting.addons, { maxSources: v }) }));
        stepper(g, 'Smallest file worth offering', 'Below this the copy is usually unwatchable, so it is dropped.',
          [0, 50, 100, 200, 300, 500], Number(setting.addons.minSizeMB),
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
        const l = line('Search and metadata cache', 'Clearing it makes the hub ask its addons again on the next request. Nothing you have watched is touched.');
        const b = el('button', { class: 'btn line', type: 'button', text: 'Clear cache', onclick: async () => {
          b.disabled = true;
          b.textContent = 'Clearing…';
          try { await api.clearCache(); ctx.toast('Cache cleared'); b.textContent = 'Cleared'; }
          catch (e) { ctx.toast('The hub would not clear the cache'); b.textContent = 'Clear cache'; }
          b.disabled = false;
        } });
        focus.focusable(b);
        l.appendChild(b);
        g.appendChild(l);
      }
      right.appendChild(g);
    }

    /* -------------------------------------------------------------- profiles */

    /* A PIN is typed the way every other PIN in this app is typed — on the remote's number row or the on-screen
       pad — and handed straight to the hub, which is the only place the number is ever kept. */
    async function setPin(p) {
      const digits = await ctx.enterPin(p, isLocked(p) ? 'Choose a new PIN' : 'Choose a PIN');
      if (!digits) return;
      try {
        await api.updateProfile(p.id, { pin: digits, pinRequired: true });
        ctx.toast(`${p.name} now needs that PIN`);
        ctx.reload();
      } catch (e) { ctx.toast('The hub would not store that PIN'); }
    }

    async function clearPin(p) {
      try {
        // The hub clears the number but leaves the requirement flag behind, which is how a profile ends up
        // "PIN wanted, none set" and a pad that can never be satisfied. Take both off in one request.
        await api.updateProfile(p.id, { pin: null, pinRequired: false });
        ctx.toast(`${p.name} no longer needs a PIN`);
        ctx.reload();
      } catch (e) { ctx.toast('The hub would not remove the PIN'); }
    }

    if (caps.profileEdit) {
      const g = group('Profiles', 'Names and ages live in the hub’s profile file. A new profile starts with the house defaults and is renamed there, where a keyboard is comfortable.');
      for (const p of (session.profiles || [])) {
        const kids = p.kind === 'kids';
        const lockedNow = isLocked(p);
        const l = line(p.name,
          `${kids ? 'Kids' : 'Standard'} profile`
          + (lockedNow ? ' · PIN set' : (p.pinRequired ? ' · PIN wanted, none set' : ''))
          + (p.id === profile.id ? ' · in use now' : ''));
        const sw = el('button', { class: `sw${kids ? ' on' : ''}`, type: 'button', role: 'switch',
          'aria-checked': kids ? 'true' : 'false', 'aria-label': `Kids mode for ${p.name}`, title: 'Kids mode' }, el('i'));
        sw.onclick = async (ev) => {
          const on = ev.currentTarget.classList.toggle('on');
          ev.currentTarget.setAttribute('aria-checked', on ? 'true' : 'false');
          try { await api.updateProfile(p.id, { kind: on ? 'kids' : 'adult' }); ctx.toast(`${p.name} is now a ${on ? 'kids' : 'standard'} profile`); }
          catch (e) { ctx.toast('The hub would not change that profile'); ev.currentTarget.classList.toggle('on', !on); }
        };
        focus.focusable(sw);
        l.appendChild(sw);
        /* The kids gate is only half a feature while a parent can switch a profile to Kids but cannot put a
           PIN behind it. One row per profile: set it, change it, or take it off again. */
        const pinBtn = el('button', {
          class: 'btn ghost', type: 'button', text: lockedNow ? 'Change PIN' : 'Set PIN',
          'aria-label': `${lockedNow ? 'Change' : 'Set'} the PIN for ${p.name}`, onclick: () => setPin(p),
        });
        focus.focusable(pinBtn);
        l.appendChild(pinBtn);
        if (lockedNow) {
          const offBtn = el('button', {
            class: 'btn ghost', type: 'button', text: 'Remove PIN', 'aria-label': `Remove the PIN for ${p.name}`,
            onclick: () => clearPin(p),
          });
          focus.focusable(offBtn);
          l.appendChild(offBtn);
        }
        if (p.id !== profile.id) l.appendChild(roundBtn('close', () => remove(p), `Remove ${p.name}`));
        g.appendChild(l);
      }
      const add = el('button', { class: 'btn line', type: 'button', text: 'Add profile', onclick: async () => {
        try {
          const r = await api.createProfile({ name: 'New profile', kind: 'adult' });
          ctx.toast(r && r.profile ? `${r.profile.name} added — give it a name in the hub’s profile file` : 'Profile added');
          ctx.reload();
        } catch (e) { ctx.toast('The hub would not add a profile'); }
      } });
      focus.focusable(add);
      g.appendChild(el('div', { class: 'mfoot' }, add));
      right.appendChild(g);
    }

    /* ---------------------------------------------------------- diagnostics */
    const d = el('div', { class: 'diag' }, el('h4', { text: 'Diagnostics' }));
    const row = (k, v, cls) => d.appendChild(el('div', { class: 'dr' }, el('b', { text: k }), el('span', { class: cls || '', text: String(v) })));
    row('Address used', apiBase());
    row('Player in use', backendName() === 'native' ? 'TV shell player' : 'Browser video');
    row('Search', caps.search ? 'the hub answers it' : 'not offered by this hub', caps.search ? 'ok' : 'no');
    row('Subtitles', caps.subtitles ? 'the hub finds them' : 'not offered by this hub', caps.subtitles ? 'ok' : 'no');
    row('Track switching', caps.tracks ? 'audio and subtitle tracks can change mid-playback' : 'one track per copy', caps.tracks ? 'ok' : 'mid');
    row('Live TV', caps.live ? 'channels are available' : 'no channel list loaded', caps.live ? 'ok' : 'no');
    row('Editable from here', [canHub && 'hub', canPrefs && 'this profile', !!caps.profileEdit && 'profiles', !!caps.cacheAdmin && 'cache'].filter(Boolean).join(', ') || 'nothing — this hub is read-only from a browser', 'mid');
    api.health().then(() => row('Hub reachable', 'yes', 'ok')).catch(() => row('Hub reachable', 'no answer', 'no'));
    right.appendChild(d);

    focus.focusDefault(root.querySelector('.chip') || root.querySelector('.sw') || root.querySelector('.round'));
  }

  function remove(p) {
    const box = el('div', { class: 'mbox pin' },
      el('h2', { text: `Remove ${p.name}?` }),
      el('p', { text: 'The profile and its preferences go away. What has been watched stays on the hub.' }),
      el('div', { class: 'mfoot' },
        (() => { const b = el('button', { class: 'btn line', type: 'button', text: 'Keep', onclick: () => ctx.closeModals() }); focus.focusable(b); return b; })(),
        (() => { const b = el('button', { class: 'btn red', type: 'button', text: 'Remove' }); b.onclick = async () => {
          try { await api.deleteProfile(p.id); ctx.closeModals(); ctx.toast(`${p.name} removed`); ctx.reload(); }
          catch (e) { ctx.toast('The hub would not remove that profile'); }
        }; focus.focusable(b); return b; })()));
    ctx.mountModal(box, null);
    focus.focusDefault(box.querySelector('.btn.line'));
  }

  /* ------------------------------------------------------------------- load */

  (async () => {
    const [s, p] = await Promise.all([
      api.settings().catch(() => null),
      profile.id ? api.prefs(profile.id).catch(() => null) : Promise.resolve(null),
    ]);
    setting = s ? (s.settings || s) : null;
    names = (s && s.languageNames) || {};
    prefs = p ? (p.prefs || p) : null;
    paint();
  })();

  root.__defaultFocus = () => root.querySelector('.chip') || root.querySelector('.sw') || root.querySelector('.round');
  root.__dispose = () => {};
  return root;
}
