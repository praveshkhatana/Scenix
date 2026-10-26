/**
 * js/avatarapi.js — generated faces from an avatar API, as an option inside the picker.
 *
 * This lives apart from js/avatars.js on purpose. avatars.js draws faces in markup and needs no network, and
 * the profile gate is the first thing the TV paints — it must never wait on somebody else's server. These are
 * the extra family a viewer opens deliberately, where a slow image is acceptable.
 *
 * Two constraints shape the list below.
 *
 *   Licence. The generator's code is MIT, but the *styles* are not: each one carries its own terms, and most
 *   are CC BY 4.0, which obliges us to credit the author on screen. Nothing here credits anyone, so the rail
 *   leads with the CC0 styles and the handful whose authors grant commercial use outright. A CC BY style is
 *   included because it is the friendliest set of the lot; if it stays, the attribution line goes on the
 *   about page, not in the middle of the product.
 *
 *   One peer. The TV talks to the hub and to nothing else. So the base address comes from the theme
 *   (`avatarApi.base`) and only defaults to the public API. Point that key at the hub and this file needs no
 *   change: the same SVGs can be generated there and cached per style + seed forever, which also makes the
 *   profile screen work with the internet down.
 *
 * Nothing on this rail is a film or television character, and that is deliberate — see the note in
 * js/avatars.js. A real Spider-Man is somebody's trademark, and licensing one per character is not a front-end
 * decision. The licence-clean way to get "my favourite character" as an avatar is to point at art the hub
 * already carries: a poster or a fanart image for a title in the library, cropped to the card.
 */

import { api } from '/ui/js/api.js';
import { token } from '/ui/js/theme.js';

/** Neutral labels: the viewer chooses a look, not a vendor. */
export const GENERATED = [
  { id: 'open-peeps', label: 'Ink', licence: 'CC0' },
  { id: 'notionists', label: 'Flat', licence: 'CC0' },
  { id: 'lorelei-neutral', label: 'Line', licence: 'CC0' },
  { id: 'avataaars-neutral', label: 'Classic', licence: 'commercial use granted' },
  { id: 'bottts-neutral', label: 'Robot', licence: 'commercial use granted' },
  { id: 'fun-emoji', label: 'Emoji', licence: 'CC BY 4.0' },
  { id: 'miniavs', label: 'Retro', licence: 'CC BY 4.0' },
  { id: 'croodles-neutral', label: 'Doodle', licence: 'CC BY 4.0' },
];

export function avatarApiBase() {
  return String(token('avatarApi', 'base', 'https://api.dicebear.com/10.x')).replace(/\/+$/, '');
}

/**
 * Seed by profile id, so the same person keeps the same generated face across every Scenix TV in the house,
 * and tint the background with the palette already chosen so a face and a colour stay one decision.
 */
export function generatorUrl(styleId, seed, background) {
  const bg = String(background || '').replace('#', '');
  const q = new URLSearchParams({ seed: String(seed || 'scenix') });
  if (/^[0-9a-f]{6}$/i.test(bg)) {
    q.set('backgroundColor', bg);
    q.set('backgroundType', 'gradientMonochrome');
  }
  return `${avatarApiBase()}/${styleId}/svg?${q}`;
}

/**
 * A generated face is stored as a photo, which keeps one rendering path: `avatars.js` already knows how to
 * paint an image, and the hub's future `avatar` field arrives through exactly the same door. `gen` and
 * `palette` are carried so the picker can reopen on this choice and re-tint it later.
 */
export function generatorSpec(styleId, seed, palette) {
  return { kind: 'photo', gen: styleId, palette, src: generatorUrl(styleId, seed, palette) };
}

/**
 * Characters from wherever the hub can reach — film, series, animation, and the hub decides which. Asked for by
 * seed so the same person on the same TV is offered the same faces, and the rail simply does not exist when the
 * hub has no such endpoint. Keys, quotas and any attribution obligations stay server-side; the front end keeps
 * only the address it was given, which is the whole point: a new source is a hub change, not a release.
 */
export async function characterFaces(seed, limit = 12) {
  const q = `seed=${encodeURIComponent(String(seed || 'scenix'))}&limit=${Number(limit) || 12}`;
  const r = await api(`/app/v1/avatars/characters?${q}`);
  const items = Array.isArray(r) ? r : ((r && (r.avatars || r.characters || r.items || r.results)) || []);
  return items
    .map((x) => String((x && (x.url || x.href || x.src || x.image)) || ''))
    .filter((u) => /^https?:\/\//i.test(u) || u.startsWith('/'))
    .map((u) => ({ kind: 'photo', src: u }));
}
