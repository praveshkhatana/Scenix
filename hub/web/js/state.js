/**
 * js/state.js — what the interface remembers between screens.
 *
 * Deliberately small. The hub owns the library, progress and preferences; this module holds only the
 * session (who is watching, what was fetched, what focus was on a screen when you left it) plus a
 * cached copy of the hub's per-profile "My list" so buttons flip instantly and the page still works
 * when the hub is briefly away.
 */

import { api } from './api.js';

const LS = {
  profile: 'scenix.profile',
  list: 'scenix.list',
  kidsAck: 'scenix.kids.ack',
};

export const session = {
  profiles: [],
  profile: null,
  manifest: null,
  prefs: null,
  home: null,            // last /app/v1/home payload, keyed by profile id
  homeKey: null,
  search: '',            // last query, so returning to search keeps your typing
  focusMemory: new Map(), // screen key -> the identity to put the ring back on
  returnMemory: new Map(), // route key -> where the ring stood when that screen was left
  myList: new Set(),
};

export function setProfiles(list) { session.profiles = Array.isArray(list) ? list : []; }

export function profileById(id) {
  return session.profiles.find((p) => String(p.id) === String(id)) || null;
}

export function isKids(profile = session.profile) {
  if (!profile) return false;
  return String(profile.kind || '').toLowerCase() === 'kids' || !!profile.isKids;
}

/**
 * Does this profile actually need a PIN right now?
 *
 * The hub answers with one honest boolean, `locked`. An older hub answers with `pinRequired` and `pinSet` and
 * expects the page to AND them, which is what the fallback does. The distinction matters: a profile whose PIN
 * was removed but whose requirement flag was left behind is not locked, and a pad demanding four digits that
 * nothing can check is a door with no handle.
 */
export function isLocked(profile) {
  if (!profile) return false;
  if (typeof profile.locked === 'boolean') return profile.locked;
  return !!profile.pinRequired && !!(profile.pinSet || profile.pin);
}

export function setProfile(p) {
  session.profile = p || null;
  try { if (p) localStorage.setItem(LS.profile, p.id); } catch (e) { /* private mode */ }
}

export function lastProfileId() {
  try { return localStorage.getItem(LS.profile) || ''; } catch (e) { return ''; }
}

export function adultProfilesExist() {
  return session.profiles.some((p) => !isKids(p));
}

export function profileAvatarClass(p, index) {
  if (isKids(p)) return 'grad-k';
  return `grad${(index % 4) + 1}`;
}

/* ---- My list: the hub's shelf (data/list.json), this page just renders it ----
 *
 * It used to be localStorage per browser — three screens, three different lists, and every one of
 * them wrong about the other two. The hub endpoint exists now, so local storage only serves as the
 * offline cache and the one-time seed: a list a person built in this browser before migration is
 * pushed up once instead of silently vanishing.
 */

function listKey() { return `${LS.list}.${session.profile ? session.profile.id : 'default'}`; }

export function loadMyList() {
  try {
    const raw = localStorage.getItem(listKey());
    session.myList = new Set(raw ? JSON.parse(raw) : []);
  } catch (e) { session.myList = new Set(); }
  // Ask the hub, then adopt its answer. The GET also seeds the hub with what this browser already
  // had, so the migration cannot lose a list on the day of the switch.
  const profile = session.profile ? session.profile.id : 'default';
  const local = [...session.myList];
  api.myList(profile).then((r) => {
    const hubIds = (r && Array.isArray(r.ids)) ? r.ids : [];
    const missing = hubIds.filter((id) => !session.myList.has(id));
    missing.forEach((id) => session.myList.add(id));
    local.filter((id) => !hubIds.includes(id)).forEach((id) => api.setMyList(profile, id, true));
    try { localStorage.setItem(listKey(), JSON.stringify([...session.myList])); } catch (e) { /* ignore */ }
    if (missing.length) window.dispatchEvent(new CustomEvent('mylist-changed'));
  }).catch(() => { /* offline: the local cache is the list until the hub answers */ });
  return session.myList;
}

export function inMyList(identity) { return session.myList.has(identity); }

export function toggleMyList(identity) {
  const on = !session.myList.has(identity);
  // Optimistic: the button must flip this frame. The POST confirms; a failed POST flips back.
  if (on) session.myList.add(identity); else session.myList.delete(identity);
  try { localStorage.setItem(listKey(), JSON.stringify([...session.myList])); } catch (e) { /* ignore */ }
  const profile = session.profile ? session.profile.id : 'default';
  api.setMyList(profile, identity, on).catch(() => {
    if (on) session.myList.delete(identity); else session.myList.add(identity);
    try { localStorage.setItem(listKey(), JSON.stringify([...session.myList])); } catch (e) { /* ignore */ }
    window.dispatchEvent(new CustomEvent('mylist-changed'));
  });
  return on;
}

/* ---- trailer preview cache: one lookup per title per session, including the misses ---- */

export const trailerCache = new Map();   // identity -> {url, headers} | null
export const metaCache = new Map();      // identity -> item (so "More like this" is instant)

export function rememberMeta(item) {
  if (item && item.identity) metaCache.set(item.identity, item);
  return item;
}

/* ---- focus memory, so Back puts the ring where you left it ---- */

export function rememberFocus(screenKey, identity) {
  if (screenKey && identity) session.focusMemory.set(screenKey, identity);
}
export function focusMemoryFor(screenKey) { return session.focusMemory.get(screenKey) || null; }

/**
 * Where a viewer stood when they left a screen, kept for the way back. The control is named by
 * whatever stable handle it carries — a card's identity, a settings-style row's `ctl` tag, or failing
 * both its ordinal place among the screen's focus stops — plus anything the screen itself adds:
 * search remembers the word that was typed. Scroll needs no separate record: the focus engine
 * scrolls whatever holds the ring into view.
 */
export function rememberReturn(key, rec) { if (key && rec) session.returnMemory.set(key, rec); }
export function returnMemoryFor(key) { return session.returnMemory.get(key) || null; }

/* ---- native shell capabilities, feature-detected and never assumed ---- */

export function shell() {
  return (typeof window !== 'undefined' && window.Scenix) ? window.Scenix : null;
}
/**
 * The preferences this profile will actually be played with. The hub sends what the owner wrote
 * (`prefs`) beside what it means once household defaults fill the gaps (`effectivePrefs`); reading only
 * the first loses a profile that inherits its languages from the hub, whose empty list then reaches the
 * player and lets whatever the file's packager flagged decide the audio.
 */
export function effectivePrefs() {
  const p = session.profile;
  if (!p) return {};
  return p.effectivePrefs || p.prefs || {};
}

export function hasNativePlayer() { return !!shell(); }
export function shellDevice() {
  const s = shell();
  try { return s && typeof s.device === 'function' ? String(s.device()) : ''; } catch (e) { return ''; }
}
export function openExternal(url) {
  const s = shell();
  if (s && typeof s.openExternal === 'function') { try { s.openExternal(url); return true; } catch (e) { /* fall through */ } }
  return false;
}
