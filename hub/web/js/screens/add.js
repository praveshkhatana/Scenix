/**
 * js/screens/add.js — making a person.
 *
 * This screen exists because adding someone used to send you to Settings: a page about the hub, with the row of
 * faces you were looking at gone behind you, and a household section somewhere below the fold to find again.
 * Here the new tile is the largest thing on the screen and it changes as you choose — the name appears on it as
 * the keypad types it, its face becomes a kid's face when Kids is chosen, its pill shows the age, its padlock
 * shows the PIN. The viewer is watching the person they are making the whole time, not a form about them.
 *
 * Nothing on this screen knows this household. The kind a new profile starts with, whether the hub wants a PIN
 * on new profiles, which ages it is willing to put on one, and which languages it can even name all come from
 * `/app/v1/settings` (see core/settings.js, `profiles`). A hub that offers no ages shows no age row: the hub
 * treats "no age on the profile" as its strictest shelf, so an unanswered question is the safe answer rather
 * than a guess made here.
 *
 * The PIN is asked for *before* anything is written. If the viewer backs out of the keypad, no profile has been
 * created and there is nothing to undo — a half-made person with no number on their gate is the mess this order
 * avoids.
 */

import { el, clear } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { icon } from '/ui/js/icons.js';
import { initials } from '/ui/js/format.js';
import { session } from '/ui/js/state.js';
import { api } from '/ui/js/api.js';
import { paintAvatar } from '/ui/js/avatars.js';
import { entryField } from '/ui/js/keyboard.js';
import { setTypingTarget } from '/ui/js/input.js';
import { openAvatarPicker } from '/ui/js/avatarmodal.js';
import { profileDefaults, languageChoices, refreshProfiles, whyRefused, withAdultProof } from '/ui/js/profileedit.js';
import { makeRows } from '/ui/js/rows.js';

export function screen(ctx) {
  const wrap = el('div', { class: 'screen padd' });
  const rows = makeRows(ctx);

  /* The draft is a profile in the shape the hub uses, minus the id it has not been given yet. Everything on
     this screen — the tile, the pills, the create request — reads from this one object, so the preview cannot
     disagree with what gets sent. */
  const draft = { name: '', kind: 'adult', age: null, languages: [] };
  let hubLanguages = [];          // the household's list, as a starting point rather than a rule
  let vocab = {};                 // the languages this hub can name at all
  let ageOptions = [];            // the ages this hub is willing to put on a profile
  let wantPin = false;            // the hub's own answer, until the viewer says otherwise
  let busy = false;
  let created = null;             // the profile the hub made, once it has made it

  /* ------------------------------------------------------------------ preview */

  const face = el('div', { class: 'a' }, el('span', { class: 'ini' }));
  // Two initials, exactly as the picker's cards carry them: the large one under the face is the fallback when no
  // face has been painted, the small one in the corner is the card's badge, and app.css hides the badge once a
  // face is on the card. A preview that only wrote one would read differently from the row it becomes.
  const inEl = face.querySelector('.ini');
  const scrim = el('div', { class: 'scrim' });
  const pini = el('span', { class: 'pini' });
  const nameEl = el('div', { class: 'nm' });
  const chips = el('div', { class: 'chips' });
  face.appendChild(scrim);
  face.appendChild(pini);
  face.appendChild(chips);
  face.appendChild(nameEl);
  const tile = el('div', { class: 'avatar padd-tile' }, face);

  /** Repaint the tile from the draft. Called after every choice, so the tile is never behind the form. */
  function paintTile() {
    const shown = draft.name || 'New profile';
    nameEl.textContent = shown;
    inEl.textContent = initials(shown);
    pini.textContent = initials(shown);
    chips.textContent = '';
    if (draft.kind === 'kids') chips.appendChild(el('span', { class: 'pc', text: 'Kids' }));
    if (draft.age !== null && draft.age !== undefined) chips.appendChild(el('span', { class: 'pc', text: String(draft.age) }));
    if (wantPin) chips.appendChild(el('span', { class: 'pc', text: 'PIN' }));
    const lock = face.querySelector('.lock');
    if (wantPin && !lock) face.appendChild(el('span', { class: 'lock' }, icon('lock')));
    if (!wantPin && lock) lock.remove();
    // The seed is the id the hub will give, so the face the viewer watched being made is the face they keep.
    paintAvatar(face, created || draft, 0);
  }

  /* ------------------------------------------------------------- the name keypad */

  const entry = entryField({
    placeholder: 'Type a name',
    glyph: 'user',
    // `entryField` paints once as it is built, so this runs before `entry` exists: the value arrives as an
    // argument rather than being read back off the object being declared.
    onChange: (text) => { draft.name = String(text || '').trim(); paintTile(); },
  });

  /* --------------------------------------------------------------------- choices

     Every choice on this screen is one row in the voice the rest of the app uses — the row names the
     question and the answer it stands on now, OK opens the dial or the checklist, and Back cancels
     without touching the draft. The tile above still repaints with every commit, so the person being made
     is visible while the wheel turns. */

  const kindBox = el('div', { class: 'padd-fact' });
  const ageBox = el('div', { class: 'padd-fact padd-age' });
  const pinBox = el('div', { class: 'padd-fact padd-pin' });
  const langBox = el('div', { class: 'padd-langs' });
  const langNote = el('p', { class: 'note' });

  let kindRow = null;
  let ageRow = null;
  let langRow = null;

  const NO_AGE = '';

  function ageLabel(a) { return a === NO_AGE ? 'No age' : String(a); }

  /**
   * The age row exists only when the hub offers ages, and it is asked only of a Kids profile: the hub's kids
   * rule reads the number off the profile, and an adult profile's age decides nothing, so asking for it there
   * would be a question with no consequence.
   */
  function paintAge() {
    clear(ageBox);
    ageRow = null;
    const offer = draft.kind === 'kids' && ageOptions.length;
    ageBox.style.display = offer ? '' : 'none';
    if (!offer) {
      if (draft.kind !== 'kids') draft.age = null;
      return;
    }
    const current = draft.age === null || draft.age === undefined ? NO_AGE : draft.age;
    ageRow = rows.pickRow(ageBox, 'Age', 'The hub decides what this person may watch from that number.',
      [NO_AGE].concat(ageOptions), current, ageLabel, (o) => {
        draft.age = o === NO_AGE ? null : Number(o);
        paintTile();
        // The wheel closed over this row; put the ring back where the viewer was standing.
        focus.focusDefault(ageRow);
      });
  }

  function paintKind() {
    clear(kindBox);
    kindRow = rows.pickRow(kindBox, 'Who is this?',
      'A Kids profile is held to the ratings the hub knows, and to the ages on its own certificates.',
      ['adult', 'kids'], draft.kind, (k) => (k === 'kids' ? 'Kids' : 'Adult'), (k) => {
        draft.kind = k === 'kids' ? 'kids' : 'adult';
        // An adult profile carries no age ceiling, so the number chosen for a kid does not follow the switch.
        if (draft.kind !== 'kids') draft.age = null;
        paintAge();
        paintTile();
        // The wheel closed over this row. When a Kids choice just raised the Age question, the ring steps to
        // that new row; otherwise it goes back to the one the viewer committed on.
        focus.focusDefault(ageRow || kindRow);
      });
  }

  function paintPin() {
    clear(pinBox);
    const note = () => (wantPin
      ? 'You will be asked for the number before the profile is made.'
      : 'Anyone who reaches this TV can open this profile.');
    rows.toggle(pinBox, 'Ask for a PIN', note(), wantPin, (on) => {
      wantPin = on;
      const n = pinBox.querySelector('.note');
      if (n) n.textContent = note();
      paintTile();
    });
  }

  function paintLanguages() {
    clear(langBox);
    langRow = null;
    const chosen = draft.languages.slice();
    const { codes, labelFor } = languageChoices(vocab, chosen);
    langRow = rows.multiRow(langBox, 'Languages this profile watches',
      'Walk the list and take languages in or out; nothing applies until Save.',
      codes, chosen, labelFor, (list) => {
        draft.languages = list.slice();
        langNote.textContent = langHint();
        focus.focusDefault(langRow);
      });
    langBox.appendChild(langNote);
    langNote.textContent = langHint();
  }

  function langHint() {
    if (!draft.languages.length) return 'Nothing chosen: this profile watches whatever the hub itself allows.';
    const { labelFor } = languageChoices(vocab, draft.languages);
    return `Only titles in ${draft.languages.map(labelFor).join(', ')} — and anything the hub cannot classify.`;
  }

  /* ---------------------------------------------------------------------- create */

  const create = el('button', { class: 'btn white big', type: 'button', text: 'Create profile' });
  create.onclick = () => make();
  focus.focusable(create);

  async function make() {
    if (busy || created) return;
    const name = String(entry.value || '').trim();
    if (!name) {
      ctx.toast('This profile needs a name before it can be made');
      const k = entry.firstKey();
      if (k) focus.setFocus(k);
      return;
    }
    // The number is asked for first, so backing out of the keypad leaves nothing behind: no profile, no
    // half-set gate to explain.
    let digits = null;
    if (wantPin) {
      digits = await ctx.enterPin({ name }, 'Choose a PIN');
      if (!digits) return;
    }
    busy = true;
    create.textContent = 'Making…';
    const body = { name, kind: draft.kind === 'kids' ? 'kids' : 'adult' };
    if (draft.kind === 'kids' && draft.age !== null && draft.age !== undefined) body.age = draft.age;
    if (draft.languages.length) body.languages = draft.languages.slice();
    let out = null;
    try {
      /* The hub is asked first, so it is the one that says whether this household has any number to demand and
         whether this act needs one. A screen that decided that on its own would be a second copy of the rule,
         and the copy would be wrong the day the rule moved. */
      out = await withAdultProof(ctx, 'add a profile',
        (extra) => api.createProfile(Object.assign({}, body, extra)));
    } catch (e) {
      busy = false;
      create.textContent = 'Create profile';
      ctx.toast(whyRefused(e, 'The hub would not make that profile'));
      return;
    }
    if (out === null) {
      busy = false;
      create.textContent = 'Create profile';
      return;
    }
    const made = out && out.profile;
    if (!made || !made.id) {
      busy = false;
      create.textContent = 'Create profile';
      ctx.toast('The hub did not make that profile');
      return;
    }
    created = made;
    if (digits) {
      // A PIN is not a field the hub takes at creation, so it goes on immediately afterwards. If that write
      // fails the person still exists — unlocked, and saying so beats pretending the gate is there.
      try {
        await api.updateProfile(made.id, { pin: digits, pinRequired: true });
      } catch (e) {
        ctx.toast(`${made.name} was made without a PIN: ${whyRefused(e, 'the hub would not store it')}`);
        wantPin = false;
      }
    }
    await refreshProfiles();
    const real = session.profiles.find((p) => String(p.id) === String(made.id)) || made;
    created = real;
    paintTile();
    busy = false;
    // Straight into the face, on the tile that is already on screen — the person is made, and a name with no
    // face is a card nobody will recognise across the room.
    openAvatarPicker(ctx, {
      profile: real, face, index: 0, restore: create,
      onClose: () => { if (ctx.route().name === 'add') ctx.back(); },
    });
  }

  /* ------------------------------------------------------------------------ layout */

  const left = el('div', { class: 'padd-left' }, tile, kindBox, ageBox, pinBox);
  const right = el('div', { class: 'padd-right' },
    el('h1', { text: 'New profile' }),
    el('p', { class: 'sub', text: 'Type a name on the keypad. The card on the left is the one this person will use.' }),
    entry.field, entry.kbd);
  const foot = el('div', { class: 'padd-foot' }, create);

  wrap.appendChild(el('div', { class: 'padd-top' }, left, right));
  wrap.appendChild(langBox);
  wrap.appendChild(foot);

  // A remote or an IR keyboard types straight into the name; the keypad on screen is the same buffer.
  setTypingTarget({
    insert: (ch) => entry.insert(ch),
    backspace: () => entry.back(false),
    clear: () => entry.back(true),
    /* js/input.js gives Enter to whoever is taking text, and this screen is always taking a name — so Enter has
       to work out what it was pressed on. On a key it is that key, on a choice it is that choice, on the button
       it is the profile, and on a pad the hub opened above this screen it is that pad's own key (focus.activate
       is exactly `current.click()`, so this is the same action, not an imitation of it). Enter pressed on
       nothing the screen owns is the button. Without this, a viewer holding only a remote could type a name and
       then never press one control on a screen that looks entirely made of controls. Escape is not Enter: the
       remote's Back leaves the screen, and leaving must not mint a profile on the way out. */
    commit: (how) => {
      if (how === 'escape') return;
      const f = focus.getFocus();
      const layer = document.getElementById('layer');
      const owned = f && typeof f.click === 'function' && (wrap.contains(f) || (layer && layer.contains(f)));
      if (owned) { f.click(); return; }
      if (!created) make();
    },
  });

  paintKind();
  paintAge();
  paintPin();
  paintLanguages();
  paintTile();

  wrap.__defaultFocus = () => entry.firstKey();
  wrap.__dispose = () => { setTypingTarget(null); };

  /* The hub's own answers, asked before any of the choices are drawn: which kind a new profile starts with,
     whether it starts behind a PIN, which ages it offers, and which languages it can name. */
  api.settings().then((s) => {
    const setting = (s && s.settings) || {};
    const d = profileDefaults(setting);
    vocab = (s && s.languageNames) || {};
    hubLanguages = Array.isArray(setting.languages) ? setting.languages.slice() : [];
    ageOptions = d.ageOptions;
    wantPin = d.requirePin;
    // The household's list is the starting point, not a ceiling: a profile may watch more or less.
    draft.languages = hubLanguages.slice();
    paintAge();
    paintPin();
    paintLanguages();
    paintTile();
  }).catch(() => {
    langNote.textContent = 'The hub would not say which languages it knows, so none are offered here.';
    paintAge();
    paintTile();
  });

  return wrap;
}
