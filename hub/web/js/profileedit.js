/**
 * js/profileedit.js — what it takes to change a person, in one place.
 *
 * The hub owns profiles; this module owns the *ritual* around changing one:
 *
 *   · A locked profile's gate fields — its PIN, its mode, its age, the rules and languages that decide what it
 *     may see — only move when the number already on it is produced. The hub is the only place that number is
 *     kept, so this module never compares digits itself: it hands them to `/app/v1/profiles/<id>/unlock`, and
 *     the hub decides. `proofOfLock` is that handshake, and `whyRefused` is what a 403 sounds like to a person
 *     on a sofa ("That is not the current PIN") instead of a control that quietly snaps back.
 *   · Some acts belong to an adult whoever is holding the remote, because they set a ceiling rather than obey
 *     one: adding an Adult profile, removing one, lifting a Kids profile into the adult band or past the age it
 *     was held to, and the dark-red switch. A locked profile's own number answers for those as well; a profile
 *     with no number of its own — the one a child just made — cannot, so the hub refuses with `gate: 'profiles'`
 *     and `withAdultProof` turns that refusal into one keypad and one retry. Nothing here decides on its own
 *     when to ask: it asks because the hub said so, which is why the same screen still works against a hub that
 *     has no such rule yet.
 *   · The fields the hub does *not* guard — a name, a face — are not gated here either. A screen that invented
 *     its own stricter rule would only teach viewers that the real lock is arbitrary.
 *   · The household's own numbers (which ages to offer, whether a new profile starts behind a PIN, which
 *     languages this hub asks for) are read from `/app/v1/settings`. Nothing in this file names a person, an
 *     age, or a language.
 *
 * It started life inside js/screens/settings.js. It lives here so Settings, `#/add` and `#/manage` cannot drift
 * into three different answers to "what happens when a kid tries to switch off their own PIN".
 */

import { el } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { api } from '/ui/js/api.js';
import { session, setProfiles, isKids, isLocked } from '/ui/js/state.js';

/**
 * The fields the hub refuses to move on a locked profile without the current PIN
 * (index.js: `holdsGate`). Kept here rather than re-derived per screen, because a screen that gates a field the
 * hub does not gate invents friction, and one that misses a field the hub does gate shows the viewer a control
 * that fails for no visible reason.
 */
export const GATE_FIELDS = ['pin', 'pinRequired', 'kind', 'age', 'rules', 'prefs', 'languages'];

/** Does this patch touch anything the hub protects? */
export function touchesGate(fields) {
  const keys = Object.keys(fields || {});
  for (const k of keys) if (GATE_FIELDS.indexOf(k) >= 0) return true;
  return false;
}

/** A refusal that reads as what happened rather than as a dead button. */
export function whyRefused(e, fallback) {
  if (isAdultGate(e)) return (e.body && e.body.error) || 'An adult PIN is needed for that';
  if (e && e.status === 403) return 'That is not the current PIN';
  const body = e && e.body && e.body.error;
  return body || fallback;
}

/**
 * Did the hub refuse the *act* rather than the number? A lock refusal names the profile whose PIN was wanted
 * (`locked`); the ceiling refusal says the act belongs to an adult (`gate: 'profiles'`) and may arrive with a
 * number already typed, or with none. Reading these two the same way is how a screen ends up asking a viewer to
 * retype a PIN they just got right.
 */
export function isAdultGate(e) {
  return !!(e && e.status === 403 && e.body && e.body.gate === 'profiles');
}

/**
 * The profile whose number opens an adult act, picked from what the hub says rather than from what this screen
 * guesses: an adult profile that has a PIN, and failing that any profile that has one. The hub makes the same
 * two-step choice on the inside (index.js `adultHolders`); this is only which name to put on the keypad heading.
 */
export function adultHolder() {
  const withPin = (session.profiles || []).filter((p) => p.pinSet);
  const grown = withPin.filter((p) => p.kind !== 'kids');
  return grown[0] || withPin[0] || null;
}

/**
 * Ask for that number and let the hub check it, the same handshake as `proofOfLock`. This module never compares
 * digits: on a hub where the household's numbers differ from the ones remembered here, the hub's answer is the
 * only authority, and a client-side match would be a second lock to maintain.
 */
export async function adultProof(ctx, act) {
  const holder = adultHolder();
  if (!holder) {
    // The hub asked for an adult's number and this list has nobody with one: the list is older than the hub.
    ctx.toast('The household changed while you were here: ask again');
    await refreshProfiles();
    return null;
  }
  const digits = await ctx.enterPin(holder, `An adult PIN is needed to ${act}`);
  if (!digits) return null;
  try {
    const out = await api.unlockProfile(holder.id, digits);
    if (!out || !out.ok) { ctx.toast('That is not the current PIN'); return null; }
  } catch (e) {
    ctx.toast(whyRefused(e, 'That is not the current PIN'));
    return null;
  }
  return digits;
}

/**
 * Make a hub call that the hub may refuse as an adult act. The call is tried first without proof, because the hub
 * is the one that knows whether this household has any numbers to ask for; only when it says the act belongs to
 * an adult does the screen ask, and then it asks once. A screen that decided on its own when to prompt would be
 * a second copy of the hub's rules, and the two would drift the first time the rules changed.
 *
 * Resolves to whatever `run` resolves to, or `null` when the viewer backed out or the hub refused twice (both
 * already said out loud). Errors that are not the adult gate are thrown back to the caller, which reads them with
 * `whyRefused`.
 */
export async function withAdultProof(ctx, act, run) {
  try {
    return await run({});
  } catch (e) {
    if (!isAdultGate(e)) throw e;
    ctx.toast(e.body.error || `An adult PIN is needed to ${act}`);
    const digits = await adultProof(ctx, act);
    if (digits === null) return null;
    try {
      return await run({ pinCurrent: digits });
    } catch (e2) {
      ctx.toast(whyRefused(e2, `The hub would not ${act}`));
      return null;
    }
  }
}

/**
 * Re-read `/app/v1/profiles`. The session list is fetched once at boot, so a screen that creates, renames or
 * removes a person has to ask the hub again or the next screen it opens still shows the household as it was.
 */
export async function refreshProfiles() {
  try {
    const out = await api.profiles();
    if (out && Array.isArray(out.profiles)) {
      setProfiles(out.profiles);
      return session.profiles;
    }
  } catch (e) { /* the session list is better than an empty screen */ }
  return session.profiles;
}

/**
 * Ask for the number already on a profile and let the hub check it.
 * `''` means there is nothing to prove; `null` means the viewer backed out or the hub said no.
 */
export async function proofOfLock(ctx, p) {
  if (!isLocked(p)) return '';
  const current = await ctx.enterPin(p, `Enter ${p.name}’s current PIN`);
  if (!current) return null;
  try {
    const out = await api.unlockProfile(p.id, current);
    if (!out || !out.ok) { ctx.toast('That is not the current PIN'); return null; }
  } catch (e) {
    ctx.toast(whyRefused(e, 'That is not the current PIN'));
    return null;
  }
  return current;
}

/** What a profile is, said in the fewest words that stay true on a card. */
export function profileBadges(p) {
  const out = [];
  if (isKids(p)) out.push('Kids');
  const age = p.age == null ? p.ageBand : p.age;
  if (age != null && age !== '') out.push(String(age));
  if (isLocked(p)) out.push('PIN');
  else if (p.pinRequired) out.push('PIN wanted, none set');
  // A Kids profile with no number on it is the household's open door: whoever reaches this TV can open it as a
  // child and put it back as an adult. Saying so on the card is cheaper than hoping a parent goes looking.
  else if (isKids(p)) out.push('No PIN');
  return out;
}

/** The one-line description used wherever a profile is listed. */
export function profileNote(p, activeId) {
  return `${isKids(p) ? 'Kids' : 'Adult'} profile`
    + (isLocked(p) ? ' · PIN set'
      : (p.pinRequired ? ' · PIN wanted, none set' : (isKids(p) ? ' · no PIN of its own' : '')))
    + (activeId && p.id === activeId ? ' · in use now' : '');
}

/**
 * The ages this hub is willing to put on a profile, the kind a new one starts with, and whether a new one
 * starts locked. All of it is the owner's setting (`settings.profiles`); a hub that has no such block offers no
 * ages at all, and the age row does not appear — the hub treats "no age" as its strictest band, so leaving the
 * question unanswered is the safe direction rather than a guess.
 */
export function profileDefaults(setting) {
  const p = (setting && setting.profiles) || {};
  const ages = Array.isArray(p.ageOptions) ? p.ageOptions : [];
  const list = [];
  for (const a of ages) {
    const n = Math.round(Number(a));
    if (Number.isFinite(n) && n >= 0 && n <= 99 && list.indexOf(n) < 0) list.push(n);
  }
  list.sort((x, y) => x - y);
  return {
    // The hub's own create default is adult, so a hub that says nothing means exactly that.
    defaultKind: String(p.defaultKind || '').toLowerCase() === 'kids' ? 'kids' : 'adult',
    requirePin: !!p.requirePin,
    ageOptions: list,
  };
}

/**
 * One chip per language, whatever spelling the hub's map uses. The map carries both ISO 639-2/T and the older
 * bibliographic code for the same language, so drawing every key shows "French" twice; the spelling this
 * profile already uses wins, and the alias is dropped.
 */
export function languageChoices(names, chosen) {
  const picked = (chosen || []).map((c) => String(c));
  const byLabel = new Map();
  const order = [];
  for (const code of Object.keys(names || {})) {
    const label = String(names[code] || code);
    const held = byLabel.get(label);
    if (!held) { byLabel.set(label, code); order.push(label); }
    else if (picked.indexOf(code) >= 0 && picked.indexOf(held) < 0) byLabel.set(label, code);
  }
  const codes = order.map((label) => byLabel.get(label));
  // A profile watching a language this hub never names is still a profile watching it: keep it visible.
  for (const code of picked) if (codes.indexOf(code) < 0) codes.push(code);
  const labelFor = (code) => {
    for (const label of byLabel.keys()) if (byLabel.get(label) === code) return label;
    return String(code).toUpperCase();
  };
  return { codes, labelFor };
}

/**
 * Everything one person's editing needs, with the PIN proof remembered only while the caller is on screen.
 *
 * `afterWrite` replaces `ctx.reload()` so a screen can repaint the row it changed instead of tearing itself
 * down under an open keypad. Settings passes its own reload; the manage sheet passes a row repaint.
 */
export function profileEditor(ctx, profile, opts = {}) {
  const afterWrite = typeof opts.afterWrite === 'function' ? opts.afterWrite : () => ctx.reload();
  let proof = null;      // digits the hub already accepted for this person, held in memory only

  /** The current PIN, asked for at most once per editing session. */
  async function ensureProof() {
    if (!isLocked(profile)) return '';
    if (proof != null) return proof;
    proof = await proofOfLock(ctx, profile);
    return proof;
  }

  /** Send a patch, with the proof attached when the hub asks for one. */
  async function patch(fields, note) {
    const gate = touchesGate(fields);
    let current = '';
    if (gate) {
      current = await ensureProof();
      if (current === null) return false;
    }
    const body = Object.assign({}, fields);
    if (gate && current) body.pinCurrent = current;
    let out = null;
    try {
      // Two proofs can be wanted on one request - this profile's own number, or an adult's for an act that lifts
      // a ceiling - but the field is one, so the request carries the one it has and the hub answers for the other
      // by refusing. A profile with a number of its own never reaches the second question (index.js accepts its
      // own proof for both), so the retry below cannot undo the first answer.
      out = await withAdultProof(ctx, 'change that profile',
        (extra) => api.updateProfile(profile.id, Object.assign({}, body, extra)));
    } catch (e) {
      if (gate) proof = null;   // the number we held is no good: ask again next time
      ctx.toast(whyRefused(e, (note || 'The hub') + ' would not save that change'));
      return false;
    }
    if (out === null) return false;   // refused twice, or backed out: already said out loud
    const wire = out && out.profile;
    if (wire) {
      // Take the hub's answer into the session record, so a row that just gained a PIN shows its padlock
      // without waiting for the next screen. Merged rather than replaced: the PUT answers with the profile,
      // while `prefs` and `rows` were fetched with the list and belong to the screen that is still open.
      Object.assign(profile, wire);
    }
    if (note) ctx.toast(note);
    await afterWrite();
    return true;
  }

  async function setPin() {
    const current = await ensureProof();
    if (current === null) return false;
    const digits = await ctx.enterPin(profile, current ? 'Choose a new PIN' : 'Choose a PIN');
    if (!digits) return false;
    // The hub keeps a PIN and the wish to be asked for one as one decision, so both go in one request.
    return patch({ pin: digits, pinRequired: true }, `${profile.name} now needs that PIN`);
  }

  async function clearPin() {
    const current = await ensureProof();
    if (current === null) return false;
    // Clearing only the number would leave the requirement flag behind: a pad that can never be satisfied.
    return patch({ pin: null, pinRequired: false }, `${profile.name} no longer needs a PIN`);
  }

  async function rename(name) {
    const clean = String(name || '').trim();
    if (!clean || clean === profile.name) return false;
    return patch({ name: clean }, `Renamed to ${clean}`);
  }

  async function setKind(kind) {
    const next = kind === 'kids' ? 'kids' : 'adult';
    if (next === String(profile.kind || 'adult')) return true;
    return patch({ kind: next }, next === 'kids' ? `${profile.name} is a Kids profile` : `${profile.name} is an Adult profile`);
  }

  async function setAge(age) {
    const n = age == null || age === '' ? null : Math.round(Number(age));
    if (n !== null && !Number.isFinite(n)) return false;
    return patch({ age: n }, n == null ? `${profile.name} has no age on the profile` : `${profile.name} is ${n}`);
  }

  async function setLanguages(list) {
    return patch({ languages: (list || []).slice() }, 'Languages saved for this profile');
  }

  /**
   * Remove the person. A locked profile is asked for its PIN even though the field is not in the gate list:
   * the last thing a household needs is a child deleting the account they are locked out of. The number is
   * checked by the hub before the DELETE is sent, and also sent with it.
   *
   * A profile with no number of its own has none to produce, which is the one a child can add in the morning and
   * erase at night; the hub refuses that as an adult act, and the retry below is what turns the refusal into a
   * keypad rather than a dead button.
   */
  async function remove() {
    const confirmed = await confirmRemove(ctx, profile);
    if (!confirmed) return null;
    const current = await ensureProof();
    if (current === null) return null;
    const own = current ? { pinCurrent: current } : {};
    let out = null;
    try {
      out = await withAdultProof(ctx, 'remove a profile',
        (extra) => api.deleteProfile(profile.id, Object.assign({}, own, extra)));
    } catch (e) {
      proof = null;
      ctx.toast(whyRefused(e, (e && e.body && e.body.error) || 'The hub would not remove that profile'));
      return null;
    }
    if (out === null) return null;   // refused twice, or backed out: already said out loud
    const list = session.profiles.filter((p) => String(p.id) !== String(profile.id));
    setProfiles(list);
    const wasActive = !!(session.profile && String(session.profile.id) === String(profile.id));
    ctx.toast(`${profile.name} was removed`);
    await afterWrite();
    return { removed: String(profile.id), wasActive };
  }

  return { profile, patch, setPin, clearPin, rename, setKind, setAge, setLanguages, remove, proofOfLock: ensureProof };
}

/** The one confirmation a deletion gets: a person's whole shelf disappears, so say whose. */
function confirmRemove(ctx, p) {
  return new Promise((resolve) => {
    let done = false;
    const finish = (v) => { if (done) return; done = true; resolve(v); };
    const yes = el('button', { class: 'btn red', type: 'button', text: 'Remove profile' }, );
    yes.onclick = () => { finish(true); ctx.closeModals(); };
    const no = el('button', { class: 'btn line', type: 'button', text: 'Keep' });
    no.onclick = () => { finish(false); ctx.closeModals(); };
    focus.focusable(yes);
    focus.focusable(no);
    const box = el('div', { class: 'mbox' },
      el('h2', { text: `Remove ${p.name}?` }),
      el('p', { text: 'Their list, their progress and the PIN on this profile go with it. This cannot be undone on the TV.' }),
      el('div', { class: 'mfoot' }, no, yes));
    ctx.mountModal(box, () => finish(false));
    focus.focusDefault(no);
  });
}
