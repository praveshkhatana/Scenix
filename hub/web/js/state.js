/**
 * js/state.js — what the interface remembers between screens.
 *
 * Deliberately small. The hub owns the library, progress and preferences; this module holds only the
 * session (who is watching, what was fetched, what focus was on a screen when you left it) plus the one
 * thing the hub has no endpoint for yet: a personal "My list".
 */

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

/* ---- My list: local until the hub offers an endpoint (noted in the report) ---- */

function listKey() { return `${LS.list}.${session.profile ? session.profile.id : 'default'}`; }

export function loadMyList() {
  try {
    const raw = localStorage.getItem(listKey());
    session.myList = new Set(raw ? JSON.parse(raw) : []);
  } catch (e) { session.myList = new Set(); }
  return session.myList;
}

export function inMyList(identity) { return session.myList.has(identity); }

export function toggleMyList(identity) {
  if (session.myList.has(identity)) session.myList.delete(identity);
  else session.myList.add(identity);
  try { localStorage.setItem(listKey(), JSON.stringify([...session.myList])); } catch (e) { /* ignore */ }
  return session.myList.has(identity);
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

/* ---- native shell capabilities, feature-detected and never assumed ---- */

export function shell() {
  return (typeof window !== 'undefined' && window.Scenix) ? window.Scenix : null;
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
