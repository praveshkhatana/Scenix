/**
 * js/avatarapi.js — faces that come from somebody else's drawing hand.
 *
 * This lives apart from js/avatars.js so the two can fail independently. avatars.js draws faces in markup and
 * needs no network; this file asks for them. A profile that has never been touched gets a generated face, which
 * is the one part of the opening screen that depends on a server we do not run, so two things hold it up: every
 * generated spec carries a drawn `fallback` that paints the moment an image fails, and the base address is a
 * theme key rather than a constant, so the hub can generate and cache these itself and the TV needs no
 * internet at all.
 *
 * Two constraints shape the list below.
 *
 *   Licence. The generator's code is MIT, but the *styles* are not: each one carries its own terms, and most
 *   are CC BY 4.0, which obliges us to credit the author on screen. Nothing here credits anyone, so the rail
 *   leads with the CC0 styles and the handful whose authors grant commercial use outright. A CC BY style is
 *   included because it is the friendliest set of the lot; if it stays, the attribution line goes on the
 *   about page, not in the middle of the product.
 *
 *   One peer. The TV talks to the hub and to nothing else. A generated face is therefore asked for from the
 *   hub's own proxy — `/app/v1/avatars/proxy/<style>/svg?...` — which caches the SVG against style + seed and
 *   needs no internet on the panel. The base is still a theme key (`avatarApi.base`) for the case where a
 *   deployment wants to point straight at an upstream instead.
 *
 * Film and television likenesses are a separate question, and it is not a front-end one. Nothing here ships a
 * picture of anybody's Spider-Man; `characterFaces()` asks the hub for the faces it has cached and keeps only
 * the reference, so whoever owns the licence decides what that rail contains, and a new source is a hub change
 * rather than a release. A viewer's own choice is stored the same way — a style and a seed, or a URL — which
 * is why a face can be changed at any time without anything being downloaded into the product.
 */

import { api, apiBase, apiUrl } from '/ui/js/api.js';
import { token } from '/ui/js/theme.js';
import { paletteById } from '/ui/js/avatars.js';

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

/** What the household looks like before anyone has chosen: a generated face, tinted. */
export const DEFAULT_ADULT = ['open-peeps', 'notionists', 'avataaars-neutral', 'miniavs'];
export const DEFAULT_KIDS = ['fun-emoji', 'croodles-neutral', 'bottts-neutral', 'notionists'];

/**
 * The hub by default. `avatarApi.base` in the theme is the escape hatch for a deployment that would rather
 * the TV fetch from an upstream itself; left unset, every face is asked for from the hub, which caches it.
 */
export function avatarApiBase() {
  const t = String(token('avatarApi', 'base', '') || '').replace(/\/+$/, '');
  if (t) return t;
  return `${apiBase()}/app/v1/avatars/proxy`;
}

/**
 * Seed by profile id, so the same person keeps the same generated face across every Scenix TV in the house,
 * and tint the background with the palette already chosen so a face and a colour stay one decision.
 * `background` may be a bare hex or the id of one of the picker's palettes — the picker works in palettes.
 */
export function generatorUrl(styleId, seed, background) {
  const p = paletteById(String(background || ''));
  const bg = String((p ? p.b : background) || '').replace('#', '');
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
 * `palette` are carried so the picker can reopen on this choice and re-tint it later, and `fallback` so a TV
 * with no internet still shows a face rather than a hole.
 */
export function generatorSpec(styleId, seed, palette, fallback) {
  const spec = { kind: 'photo', gen: styleId, palette, src: generatorUrl(styleId, seed, palette) };
  if (fallback) spec.fallback = fallback;
  return spec;
}

/**
 * The faces this hub has already cached — film, series, animation — and the hub decides which. Asked for by
 * seed so "Another set" is a reshuffle of the same shelf, and `refresh` lets the hub go and fetch one it has
 * never held. Keys, quotas and any attribution obligation stay server-side; the client keeps only the
 * reference, plus the one fact it needs to show the art without wrecking it — whether the picture is a tall
 * poster, where the face sits in the upper third, or a wide banner, which must be used whole.
 */
export async function characterFaces(seed, limit = 40, refresh = false) {
  const r = await api.characters(seed, limit, refresh);
  const items = Array.isArray(r) ? r : ((r && (r.characters || r.avatars || r.items || r.results)) || []);
  return items
    .map((x) => {
      const u = String((x && (x.image || x.url || x.href || x.src)) || '');
      if (!u) return null;
      return {
        kind: 'photo',
        src: apiUrl(u),
        label: String((x && (x.label || x.name || x.fullName)) || ''),
        id: String((x && x.id) || ''),
        source: String((x && x.source) || ''),
        // Where the hub got it, and who owns it. Kept as a reference only, and read back as the credit line.
        from: String((x && (x.from || x.source)) || ''),
        publisher: String((x && x.publisher) || ''),
        alignment: String((x && x.alignment) || ''),
        page: String((x && x.page) || ''),
        licenceUrl: String((x && x.licenceUrl) || ''),
        powerScore: x && x.powerScore != null ? Number(x.powerScore) : null,
        fit: (x && x.portrait === false) ? 'center' : 'center 30%',
      };
    })
    .filter(Boolean);
}
