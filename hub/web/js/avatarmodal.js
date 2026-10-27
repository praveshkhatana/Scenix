/**
 * js/avatarmodal.js — the face picker, opened by whoever needs one.
 *
 * This is the picker that used to live inside js/screens/profiles.js. It moved so the profile gate and
 * `#/manage` and `#/add` all offer the same rails of faces rather than three screens with three ideas about
 * what choosing a face means. One rule it carries with it: *moving the ring previews, OK keeps*. Nothing is
 * written until a face is chosen, so a viewer can look through every face available and leave the person
 * exactly as they were.
 *
 * What gets stored is only a reference — a style and a seed, or a path this hub serves — which is why a face
 * can be replaced at any time and nothing is downloaded into the product (see js/avatars.js).
 */

import { el, clear } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { avatarFor, paintSpec, setAvatar, tileFill, paletteById, CHARACTERS, PALETTES } from '/ui/js/avatars.js';
import { GENERATED, generatorSpec, characterFaces } from '/ui/js/avatarapi.js';

/** How many faces one roll of the shelf shows. The hub caps the rail itself and reshuffles the rest behind
 *  "Another set", so this is a screenful-and-a-bit, not the size of the catalogue. */
const RAIL = 60;

/** An unreachable avatar API must not take the picker down with it. */
function generatorUrlSafe(styleId, seed, palette) {
  try { return generatorSpec(styleId, seed, palette).src; } catch (e) { return ''; }
}

/**
 * @param ctx        the screen context (it owns the modal layer)
 * @param profile    the person whose face is being chosen
 * @param face       the element to repaint when a face is kept — the card on the gate, the row in Manage
 * @param index      the profile's position, used only to seed a face for a profile with no id of its own
 * @param restore    the node the ring should return to when the picker closes
 * @param onKeep     called with the stored spec after a face is kept
 * @param onClose    called when the picker closes, kept or not — `#/add` uses it to leave with the new person
 */
export async function openAvatarPicker(ctx, { profile, face, index = 0, restore = null, onKeep = null, onClose = null } = {}) {
  if (!profile) return;
  const faceEl = face || null;
  const start = avatarFor(profile, index) || {};
  // A generated face is stored as an image reference, so the picker has to reopen on the style it came from
  // rather than falling back to a drawn character.
  const spec = start.kind === 'photo'
    ? { kind: 'photo', src: start.src, gen: start.gen, fit: start.fit, palette: start.palette || PALETTES[0].id }
    : { kind: 'char', char: start.char || CHARACTERS[0].id, palette: start.palette || PALETTES[0].id };
  const seed = profile.id || String(index);
  const preview = el('div', { class: 'a' });
  paintSpec(preview, spec);

  const chars = el('div', { class: 'avchars' });
  const cells = [];
  for (const c of CHARACTERS) {
    const cell = el('button', {
      class: 'avchar', type: 'button', 'aria-label': c.label, dataset: { char: c.id },
      onclick: () => { spec.kind = 'char'; spec.char = c.id; delete spec.gen; delete spec.src; keep(); },
    });
    cells.push({ cell, c });
    focus.focusable(cell);
    chars.appendChild(cell);
  }
  // The faces are re-tinted rather than rebuilt, so the ring never loses its place mid-arrow. Each cell
  // carries the same skin the card itself would have, so a choice previews as an avatar and not a sticker.
  function tint(p) {
    for (const { cell, c } of cells) {
      cell.style.background = tileFill(p);
      cell.innerHTML = `<svg class="av-art" viewBox="0 0 100 100" aria-hidden="true" focusable="false">${c.art(p.a)}</svg>`;
    }
  }
  tint(paletteById(spec.palette) || PALETTES[0]);

  // Remote faces: nothing is downloaded into the product, only a reference is kept, so a new source is a line
  // in one list rather than a release. The hub may proxy any of them; see js/avatarapi.js.
  const gens = el('div', { class: 'avchars avgen' });
  let variant = 0;   // another roll of the same styles, for whoever is not taken with the first set
  const faceSeed = () => (variant ? `${seed}#${variant}` : seed);
  function paintGens() {
    clear(gens);
    for (const g of GENERATED) {
      const cell = el('button', {
        class: 'avchar', type: 'button', 'aria-label': g.label, dataset: { gen: g.id },
        onclick: () => { Object.assign(spec, generatorSpec(g.id, faceSeed(), spec.palette, spec.fallback)); keep(); },
      }, el('img', {
        class: 'av-photo', src: generatorUrlSafe(g.id, faceSeed(), spec.palette), alt: '',
        onerror: () => { cell.classList.add('avgone'); },
      }));
      focus.focusable(cell);
      gens.appendChild(cell);
    }
  }
  paintGens();
  // Faces are infinite and the rail is not, so the row itself offers another set. The seed is reshuffled and
  // the hub reshuffles the same shelf the same way every time, which makes a new set free.
  const more = el('button', {
    class: 'avmore', type: 'button', text: 'Another set',
    onclick: () => { variant++; paintGens(); reroll(); },
  });
  focus.focusable(more);

  /*
   * Real character art, already cached on the hub, which also says which pictures are tall posters — where the
   * face sits in the upper third — and which are banners that have to be used whole. A circle would cut off
   * exactly the art that makes these worth looking at, so this rail is a rail of 2:3 cards and the crop travels
   * with the stored reference.
   */
  const facesRail = el('div', { class: 'avchars avfaces' });
  let faces = [];
  function drawFaces(list) {
    faces = Array.isArray(list) ? list : [];
    clear(facesRail);
    faces.forEach((f, i) => {
      const cell = el('button', {
        class: `avface${f.fit === 'center' ? ' wide' : ''}`, type: 'button',
        'aria-label': f.label || 'Character face', dataset: { face: String(i) },
        onclick: () => { Object.assign(spec, { kind: 'photo', src: f.src, fit: f.fit, palette: spec.palette }); keep(); },
      }, el('img', { src: f.src, alt: '', loading: 'lazy', decoding: 'async' }), el('span', { text: f.label || '' }));
      focus.focusable(cell);
      facesRail.appendChild(cell);
    });
  }
  async function reroll() {
    /* A roll is not a refresh. Asking the hub to re-fetch every face it already keeps would put sixty
       picture downloads behind one button press. A plain ask is answered from the cache, and the hub
       warms whatever the new roll happens to want in the background for the next one. */
    const list = await characterFaces(faceSeed(), RAIL).catch(() => []);
    if (list.length) drawFaces(list);
  }

  const sws = el('div', { class: 'avsw' });
  for (const p of PALETTES) {
    const sw = el('button', {
      class: 'sw', type: 'button', 'aria-label': p.id, dataset: { pal: p.id },
      style: `background: linear-gradient(135deg, ${p.a}, ${p.b})`,
      onclick: () => {
        spec.palette = p.id;
        if (spec.gen) Object.assign(spec, generatorSpec(spec.gen, seed, p.id));
        keep();
      },
    });
    focus.focusable(sw);
    sws.appendChild(sw);
  }

  function keep() {
    setAvatar(profile.id, spec);
    // The face that opened the picker is the one that should change: the card on the gate, the row in Manage,
    // the tile on the Add screen. The caller hands us the element rather than the screen.
    if (faceEl) paintSpec(faceEl, spec);
    if (onKeep) onKeep(spec);
    ctx.toast(`${profile.name}’s avatar is set`);
    ctx.closeModals();
  }

  // The hub's cached faces lead the picker: they are the ones a person recognises. Asked for before the modal
  // mounts so the rail is already inside the focus scope, and a hub with nothing cached just produces a
  // shorter picker. One quiet credit line, in the picker and nowhere else.
  const got = await characterFaces(faceSeed(), RAIL).catch(() => []);
  drawFaces(got);
  /* The credit is built from what the hub says each face came from. Nothing here names an upstream, so a hub
     that changes its sources changes the credit by itself. */
  const credits = [];
  for (const f of got) {
    const c = f.from || f.source;
    if (c && !credits.includes(c)) credits.push(c);
  }
  /* The generated rail is a proxy, and a hub with no generator configured answers 503 for every style in
     it. That is not a rail, it is a row of broken pictures, so ask for one face before offering the lot: a
     deployment that cannot serve them simply does not show them, and the picker gets shorter instead of
     looking broken. */
  const genOk = await new Promise((done) => {
    const src = generatorUrlSafe(GENERATED[0].id, seed, spec.palette);
    if (!src) { done(false); return; }
    const probe = new Image();
    probe.onload = () => done(true);
    probe.onerror = () => done(false);
    probe.src = src;
  });

  const parts = [
    el('h2', { text: 'Choose an avatar' }),
    el('div', { class: 'who', text: `${profile.name} — move to preview, OK to keep, Back to leave it alone` }),
    el('div', { class: 'avprev' }, preview),
    got.length ? el('div', { class: 'avlabel', text: 'Characters' }) : null,
    got.length ? facesRail : null,
    got.length ? el('div', { class: 'avcred', text: `Faces: ${credits.length ? credits.join(', ') : 'this hub'}` }) : null,
    more,
    genOk ? el('div', { class: 'avlabel', text: 'Generated faces' }) : null,
    genOk ? gens : null,
    el('div', { class: 'avlabel', text: 'Scenix faces' }),
    chars,
    el('div', { class: 'avlabel', text: 'Colours' }),
    sws,
    el('div', { class: 'avhint', text: 'Only a reference is kept, so a face can be changed any time. A photo the hub attaches to a profile takes precedence, and a face that cannot be reached falls back to a drawn one.' }),
  ];

  const box = el('div', { class: 'mbox avpick' }, ...parts);

  // Moving the ring previews, OK keeps. Nothing is written until a face is chosen, so a viewer can look
  // through every face available and leave the person exactly as they were — and landing on a colour
  // repaints the whole rail of drawn faces at once, which is how a palette gets seen before it is picked.
  let pw = () => {};
  pw = focus.onFocus((n) => {
    if (!n || !box.contains(n)) return;
    const pal = paletteById(n.dataset.pal || spec.palette) || PALETTES[0];
    let next;
    const fi = n.dataset.face;
    if (fi !== undefined && faces[Number(fi)]) {
      const f = faces[Number(fi)];
      next = { kind: 'photo', src: f.src, fit: f.fit, palette: pal.id };
    } else if (n.dataset.char) next = { kind: 'char', char: n.dataset.char, palette: pal.id };
    else if (n.dataset.gen) next = generatorSpec(n.dataset.gen, faceSeed(), pal.id, spec.fallback);
    else if (n.dataset.pal) {
      next = spec.gen
        ? generatorSpec(spec.gen, faceSeed(), pal.id, spec.fallback)
        : { kind: 'char', char: spec.char || CHARACTERS[0].id, palette: pal.id };
    } else return;
    paintSpec(preview, next);
    tint(pal);
  });

  /* The rails arrive over the network, and a viewer who pressed BACK while they were arriving has already left
     the screen that asked for them. Mounting a picker into a page that is gone leaves it floating over whatever
     came next, with its own focus scope on top of a screen nobody is looking at. */
  if ((restore && restore.isConnected === false) || (faceEl && faceEl.isConnected === false)) return;

  // mountModal already owns a focus scope, so the picker does not push another. Closing hands the ring back to
  // the card it came from: whoever you were standing on is still who you are standing on.
  ctx.mountModal(box, () => {
    pw();
    if (restore) focus.setFocus(restore, { scroll: false });
    if (onClose) onClose();
  });
  const home = spec.gen ? gens.querySelector(`[data-gen="${spec.gen}"]`)
    : chars.querySelector(`[data-char="${spec.char}"]`);
  focus.focusDefault(home || facesRail.firstChild || gens.firstChild);
}
