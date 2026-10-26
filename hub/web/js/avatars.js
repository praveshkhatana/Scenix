/**
 * js/avatars.js — who the household sees on the profile screen.
 *
 * The hub sends a profile as `{id, name, kind, age?, pinRequired}` and no picture, so the client owns the
 * face. Three sources, in order of trust:
 *
 *   1. `profile.avatar` — a URL from the hub, used the moment that field exists. A photo has to come from
 *      the hub eventually: a TV has no file picker worth building, and the same field then drives the
 *      Android shell's account chip. Resolved through `apiUrl()` so a root-relative value works.
 *   2. A choice this viewer made on this TV (localStorage, per profile id).
 *   3. A character picked deterministically from the profile id, so the same person gets the same face on
 *      every Scenix TV in the house without the hub remembering anything.
 *
 * The characters are drawn here, in flat shapes, on purpose: they are original artwork with no licence
 * attached, they scale to any TV without a bitmap, and they paint before any font or image request settles.
 */

import { apiUrl } from '/ui/js/api.js';

/* Palettes are pairs: the bright face colour and the deep tile colour it sits on. */
export const PALETTES = [
  { id: 'brand',  a: '#F0404B', b: '#7E0A10' },
  { id: 'violet', a: '#7B6BE0', b: '#2B2350' },
  { id: 'teal',   a: '#3FA3A3', b: '#12343A' },
  { id: 'amber',  a: '#F2C33A', b: '#6E5210' },
  { id: 'ocean',  a: '#4E8FD0', b: '#16324F' },
  { id: 'moss',   a: '#4E9E62', b: '#173D24' },
  { id: 'clay',   a: '#D2764F', b: '#4A1F16' },
  { id: 'slate',  a: '#8A93A2', b: '#2A2F37' },
];

/**
 * Each character is a function of its accent colour, drawn in a 100x100 box. White and near-black are
 * fixed: they are the parts that have to stay legible whatever palette the viewer picks.
 */
export const CHARACTERS = [
  {
    id: 'fox', label: 'Fox',
    art: (c) => `
      <polygon points="22,18 42,34 24,46" fill="${c}"/><polygon points="78,18 58,34 76,46" fill="${c}"/>
      <polygon points="50,26 80,42 72,68 50,86 28,68 20,42" fill="${c}"/>
      <polygon points="50,58 66,48 50,84 34,48" fill="#fff" opacity=".92"/>
      <path d="M32 46q8-7 14 0-6 7-14 0z" fill="#141414"/><path d="M68 46q-8-7-14 0 6 7 14 0z" fill="#141414"/>
      <polygon points="50,68 44,60 56,60" fill="#141414"/>`,
  },
  {
    id: 'owl', label: 'Owl',
    art: (c) => `
      <polygon points="24,28 42,24 32,44" fill="${c}"/><polygon points="76,28 58,24 68,44" fill="${c}"/>
      <circle cx="50" cy="56" r="30" fill="${c}"/>
      <circle cx="38" cy="52" r="12" fill="#fff" opacity=".94"/><circle cx="62" cy="52" r="12" fill="#fff" opacity=".94"/>
      <circle cx="39" cy="53" r="5" fill="#141414"/><circle cx="61" cy="53" r="5" fill="#141414"/>
      <polygon points="50,60 43,70 57,70" fill="#141414" opacity=".8"/>
      <path d="M32 78q18 8 36 0" stroke="#141414" stroke-width="3" fill="none" opacity=".45"/>`,
  },
  {
    id: 'robot', label: 'Robot',
    art: (c) => `
      <rect x="48" y="14" width="4" height="14" fill="${c}"/><circle cx="50" cy="12" r="5" fill="#fff" opacity=".9"/>
      <rect x="14" y="46" width="8" height="16" rx="3" fill="${c}" opacity=".7"/>
      <rect x="78" y="46" width="8" height="16" rx="3" fill="${c}" opacity=".7"/>
      <rect x="22" y="28" width="56" height="48" rx="12" fill="${c}"/>
      <rect x="32" y="40" width="13" height="13" rx="4" fill="#fff" opacity=".94"/>
      <rect x="55" y="40" width="13" height="13" rx="4" fill="#fff" opacity=".94"/>
      <rect x="36" y="44" width="5" height="5" fill="#141414"/><rect x="59" y="44" width="5" height="5" fill="#141414"/>
      <rect x="36" y="62" width="8" height="4" rx="2" fill="#141414" opacity=".55"/>
      <rect x="46" y="62" width="8" height="4" rx="2" fill="#141414" opacity=".55"/>
      <rect x="56" y="62" width="8" height="4" rx="2" fill="#141414" opacity=".55"/>`,
  },
  {
    id: 'rocket', label: 'Rocket',
    art: (c) => `
      <path d="M50 14c12 14 16 32 16 48H34c0-16 4-34 16-48z" fill="${c}"/>
      <circle cx="50" cy="44" r="10" fill="#fff" opacity=".94"/><circle cx="50" cy="44" r="5" fill="#16324F"/>
      <polygon points="34,54 20,78 34,72" fill="#fff" opacity=".85"/><polygon points="66,54 80,78 66,72" fill="#fff" opacity=".85"/>
      <polygon points="41,74 50,94 59,74 50,68" fill="#F2C33A"/>`,
  },
  {
    id: 'tiger', label: 'Tiger',
    art: (c) => `
      <circle cx="27" cy="32" r="11" fill="${c}"/><circle cx="73" cy="32" r="11" fill="${c}"/>
      <circle cx="50" cy="56" r="30" fill="${c}"/>
      <path d="M38 32q6 8 0 16M50 30q0 8 0 16M62 32q-6 8 0 16" stroke="#141414" stroke-width="4" fill="none" opacity=".75"/>
      <ellipse cx="50" cy="68" rx="15" ry="11" fill="#fff" opacity=".92"/>
      <circle cx="40" cy="54" r="3.6" fill="#141414"/><circle cx="60" cy="54" r="3.6" fill="#141414"/>
      <polygon points="50,66 45,61 55,61" fill="#141414"/>
      <path d="M42 74q8 5 16 0" stroke="#141414" stroke-width="2.5" fill="none"/>`,
  },
  {
    id: 'dino', label: 'Dino',
    art: (c) => `
      <polygon points="24,46 30,28 37,46 44,26 51,44 58,26 65,44 72,28 78,46" fill="#fff" opacity=".7"/>
      <rect x="24" y="44" width="52" height="34" rx="14" fill="${c}"/>
      <path d="M62 56h16a4 4 0 0 1 0 12H62z" fill="${c}" opacity=".85"/>
      <circle cx="38" cy="56" r="4.2" fill="#141414"/>
      <circle cx="70" cy="60" r="2.4" fill="#141414" opacity=".7"/>
      <polygon points="42,78 45,84 48,78 51,84 54,78 57,84 60,78" fill="#fff" opacity=".9"/>`,
  },
  {
    id: 'ghost', label: 'Ghost',
    art: () => `
      <path d="M22 78V52c0-20 12-32 28-32s28 12 28 32v26l-8-7-8 7-8-7-8 7-8-7z" fill="#fff" opacity=".94"/>
      <ellipse cx="39" cy="46" rx="5" ry="6.5" fill="#141414"/><ellipse cx="61" cy="46" rx="5" ry="6.5" fill="#141414"/>
      <ellipse cx="50" cy="62" rx="6" ry="7" fill="#141414" opacity=".7"/>`,
  },
  {
    id: 'panda', label: 'Panda',
    art: (c) => `
      <circle cx="28" cy="32" r="12" fill="#141414"/><circle cx="72" cy="32" r="12" fill="#141414"/>
      <circle cx="50" cy="56" r="29" fill="#fff" opacity=".95"/>
      <ellipse cx="39" cy="52" rx="8.5" ry="11" fill="#141414" transform="rotate(-8 39 52)"/>
      <ellipse cx="61" cy="52" rx="8.5" ry="11" fill="#141414" transform="rotate(8 61 52)"/>
      <circle cx="39" cy="52" r="3.2" fill="#fff"/><circle cx="61" cy="52" r="3.2" fill="#fff"/>
      <ellipse cx="50" cy="67" rx="5.5" ry="4" fill="#141414"/>
      <path d="M43 74q7 5 14 0" stroke="#141414" stroke-width="2.5" fill="none"/>`,
  },
  {
    id: 'comet', label: 'Comet',
    art: (c) => `
      <polygon points="18,84 46,50 62,66 34,90" fill="${c}" opacity=".5"/>
      <circle cx="62" cy="44" r="24" fill="${c}"/>
      <circle cx="54" cy="36" r="7" fill="#fff" opacity=".85"/>
      <circle cx="24" cy="24" r="4" fill="#fff" opacity=".7"/><circle cx="36" cy="16" r="2.5" fill="#fff" opacity=".55"/>
      <circle cx="86" cy="72" r="3" fill="#fff" opacity=".6"/>`,
  },
];

const STORE = 'scenix.avatars';

function readStore() {
  try {
    const raw = JSON.parse(localStorage.getItem(STORE) || '{}');
    return raw && typeof raw === 'object' ? raw : {};
  } catch (e) { return {}; }
}

/** Remembered on this TV only. When the hub grows an `avatar` field on profiles, this becomes the cache. */
export function setAvatar(profileId, spec) {
  const store = readStore();
  if (spec) store[profileId] = spec; else delete store[profileId];
  try { localStorage.setItem(STORE, JSON.stringify(store)); } catch (e) { /* a TV with no room keeps the default */ }
}

export function characterById(id) { return CHARACTERS.find((c) => c.id === id) || null; }
export function paletteById(id) { return PALETTES.find((p) => p.id === id) || null; }

function hash(s) {
  let h = 0;
  for (let i = 0; i < String(s || '').length; i++) h = (h * 31 + String(s).charCodeAt(i)) >>> 0;
  return h;
}

/** What a profile looks like: `{kind:'photo', src}` or `{kind:'char', char, palette}`. */
export function avatarFor(profile, index = 0) {
  if (!profile) return null;
  // A choice this household made outranks a hub picture: if someone picks a face for a profile that later
  // grows a photo, the face they chose is the one their family will keep looking for.
  const stored = readStore()[profile.id];
  if (stored && (stored.kind === 'photo' ? stored.src : characterById(stored.char) && paletteById(stored.palette))) {
    return stored;
  }
  const photo = profile.avatar || profile.photo || profile.image || profile.picture;
  if (photo) return { kind: 'photo', src: String(photo) };
  const kids = String(profile.kind || '').toLowerCase() === 'kids';
  const h = hash(profile.id || String(index));
  const chars = kids
    ? CHARACTERS.filter((c) => ['ghost', 'panda', 'dino', 'comet', 'owl', 'rocket'].includes(c.id))
    : CHARACTERS;
  const pals = kids
    ? PALETTES.filter((p) => ['amber', 'teal', 'violet', 'moss'].includes(p.id))
    : PALETTES.filter((p) => ['slate', 'brand', 'ocean', 'clay'].includes(p.id));
  return {
    kind: 'char',
    char: chars[h % chars.length].id,
    palette: pals[(h >>> 3) % pals.length].id,
  };
}

/**
 * Mix a hex toward white by amt. Written out rather than using color-mix(), because this renders on webviews a
 * few Android versions behind the browser.
 */
function mix(hex, amt) {
  const h = String(hex).replace('#', '');
  if (h.length !== 6) return hex;
  const c = (i) => Math.round(parseInt(h.slice(i, i + 2), 16) * (1 - amt) + 255 * amt);
  return `rgb(${c(0)}, ${c(2)}, ${c(4)})`;
}

/**
 * The ground a drawn face sits on. On a wall of black a tile filled with its own dark tone disappears — the
 * first cut read as holes in the screen rather than as cards — so the palette's darker tone is lifted toward
 * white in the upper left and allowed to fall off to near-black at the far edge. One light source, shared by
 * every card, which is also what makes the row look lit by the halo behind it.
 */
export function tileFill(p) {
  return `radial-gradient(130% 130% at 26% 14%, ${mix(p.b, 0.34)} 0%, ${p.b} 46%, #101318 100%)`;
}

/**
 * Paint a spec onto a face. Only the skin is replaced: the lock and the Kids badge are the screen's
 * overlays, and the profile switch clones this element and flies it, so its structure has to survive.
 */
export function paintSpec(face, spec) {
  if (!face || !spec) return;
  face.querySelectorAll('.av-art, .av-photo').forEach((n) => n.remove());
  if (spec.kind === 'photo') {
    face.style.background = 'var(--key)';
    face.insertAdjacentHTML('afterbegin', `<img class="av-photo" src="${apiUrl(String(spec.src).replace(/"/g, '%22'))}" alt=""/>`);
  } else {
    const c = characterById(spec.char) || CHARACTERS[0];
    const p = paletteById(spec.palette) || PALETTES[0];
    face.style.background = tileFill(p);
    face.insertAdjacentHTML('afterbegin', `<svg class="av-art" viewBox="0 0 100 100" aria-hidden="true" focusable="false">${c.art(p.a)}</svg>`);
  }
  const ini = face.querySelector('.ini');
  if (ini) ini.style.display = 'none';   // the initial is a fallback, not a second layer
}

/** Paint whatever this profile should look like right now. */
export function paintAvatar(face, profile, index = 0) {
  paintSpec(face, avatarFor(profile, index));
}
