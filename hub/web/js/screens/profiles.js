/**
 * js/screens/profiles.js — the door into the household.
 *
 * The list is whatever `/app/v1/profiles` returns: names, kinds, age bands, whether a PIN is required. Nothing
 * here knows there are four people in this house or that two of them are children, and nothing here knows what
 * a face looks like either — that is js/avatars.js and js/avatarapi.js.
 *
 * The layout is a carousel, not a grid: the chosen card is the centre of the screen, the row slides to put it
 * there, the neighbours fall back in brightness and the brand's light comes up behind it. That is what makes
 * picking a person feel like turning a dial rather than clicking a link.
 *
 * Two things happen on this one screen. Choosing a person is the product's signature transition, so the screen
 * does not navigate: it hands the card to `ctx.switchProfile`, which flies that face to the nav bar while Home
 * assembles underneath it — the card you were looking for becomes the badge in the corner, which is why the
 * profile gate has to be the first thing on screen every time the app runs, PIN-locked or not. Choosing a
 * *face* is the interactive half: the ring tracks whoever you are standing on, the button under the row names
 * them, and the picker previews live as you move — moving is free, OK keeps it, Back leaves the person exactly
 * as they were.
 */

import { el, clear } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { icon } from '/ui/js/icons.js';
import { initials } from '/ui/js/format.js';
import { session, isKids, isLocked } from '/ui/js/state.js';
import { avatarFor, paintAvatar, paintSpec, setAvatar, tileFill, paletteById, CHARACTERS, PALETTES } from '/ui/js/avatars.js';
import { GENERATED, generatorSpec, characterFaces } from '/ui/js/avatarapi.js';

export function screen(ctx) {
  const wrap = el('div', { class: 'screen pwrap' });
  // The halo is scenery, not a control: it holds the centre of the screen warm so the focused card has
  // something to stand against on a wall of black.
  const glow = el('div', { class: 'pglow' });
  const grid = el('div', { class: 'pfs' });
  let busy = false;
  let current = null;              // the card the ring is standing on — the picker's subject
  let tx = 0;                      // how far the row has been slid to keep that card centred

  /**
   * Slide the row, never the page. The offset is taken from layout geometry — `offsetLeft`, which is blind to
   * the row's own slide, to the card's scale and to any entrance animation still running — rather than from a
   * bounding rect. A rect measured a frame early used to leave the row parked a third of the way across the
   * screen, because the number it produced described where the card had been, not where it was going to be.
   */
  function centre(node) {
    const half = node.offsetLeft + node.offsetWidth / 2;
    tx = (window.innerWidth / 2) - grid.offsetLeft - half;
    grid.style.transform = `translateX(${tx}px)`;
  }

  function choose(profile, node) {
    if (busy || !profile) return;
    if (isLocked(profile)) {
      busy = true;
      ctx.askPin(profile).then((ok) => {
        busy = false;
        if (ok) ctx.switchProfile(profile, node);
      });
      return;
    }
    ctx.switchProfile(profile, node);
  }

  function tile(profile, i) {
    const kids = isKids(profile);
    const face = el('div', { class: 'a' }, el('span', { class: 'ini', text: initials(profile.name) }));
    paintAvatar(face, profile, i);
    // Everything that reads as a person — scrim, name, badges — sits inside the face, because the face is the
    // element that flies to the nav bar. A name left behind on the old card would break the illusion halfway.
    face.appendChild(el('div', { class: 'scrim' }));
    face.appendChild(el('span', { class: 'pini', text: initials(profile.name) }));
    if (isLocked(profile)) face.appendChild(el('span', { class: 'lock' }, icon('lock')));
    const chips = el('div', { class: 'chips' });
    if (kids) chips.appendChild(el('span', { class: 'pc', text: 'Kids' }));
    const age = profile.age ?? profile.ageBand;
    if (age !== undefined && age !== null && age !== '') chips.appendChild(el('span', { class: 'pc', text: `${age}` }));
    if (chips.children.length) face.appendChild(chips);
    face.appendChild(el('div', { class: 'nm', text: profile.name }));
    // The control rides the card rather than sitting on the page: it fades in under the card the ring is on,
    // and Down from that card lands straight on it. It stays a child of the button and outside the face, so
    // the face can fly to the nav bar without carrying an Edit pill with it.
    const edit = el('div', {
      class: 'pedit', role: 'button', tabindex: '-1',
      'aria-label': `Edit ${profile.name}`,
      // The pill sits inside the face, and the face's own click enters the profile. Editing has to stop
      // there or Edit opens the picker on top of the home screen of a profile nobody chose.
      onclick: (ev) => { ev.stopPropagation(); editProfile(profile, node); },
    }, icon('sliders'), el('span', { text: 'Edit' }));
    focus.focusable(edit);
    // `k` sits on the button: a kids profile has to read from across the room before anyone makes out the chip.
    const node = el('button', {
      class: kids ? 'avatar k' : 'avatar', type: 'button',
      'aria-label': profile.name,
      dataset: { profile: profile.id },
      style: `--i: ${i}`,
      onclick: () => choose(profile, node),
    }, face, edit);
    node.__profile = profile;
    node.__index = i;
    focus.focusable(node);
    return node;
  }

  /**
   * Changing a person. An open profile is edited straight away; a locked one asks for the PIN first, because
   * the gate is there to stop someone changing the child's settings on the way past, not to protect the face.
   */
  function editProfile(profile, node) {
    if (!profile) return;
    if (isLocked(profile)) {
      ctx.askPin(profile).then((ok) => { if (ok) openPicker(profile, node); });
      return;
    }
    openPicker(profile, node);
  }

  /**
   * The avatar picker. No keyboard is involved anywhere: rails of faces, a row of palettes, and a preview that
   * repaints every time the ring moves. That makes exploring free — every face can be looked at without
   * committing to any of them, and what gets stored is only a reference: a style and a seed, or a URL.
   */
  async function openPicker(profile, node) {
    const start = avatarFor(profile, node.__index) || {};
    // A generated face is stored as an image reference, so the picker has to reopen on the style it came from
    // rather than falling back to a drawn character.
    const spec = start.kind === 'photo'
      ? { kind: 'photo', src: start.src, gen: start.gen, fit: start.fit, palette: start.palette || PALETTES[0].id }
      : { kind: 'char', char: start.char || CHARACTERS[0].id, palette: start.palette || PALETTES[0].id };
    const seed = profile.id || String(node.__index);
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
        }, el('img', { class: 'av-photo', src: generatorUrlSafe(g.id, faceSeed(), spec.palette), alt: '' }));
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
      const list = await characterFaces(faceSeed(), 40, variant > 0).catch(() => []);
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
      paintSpec(node.querySelector('.a'), spec);
      ctx.toast(`${profile.name}'s avatar is set`);
      ctx.closeModals();
    }

    // The hub's cached faces lead the picker: they are the ones a person recognises. Asked for before the modal
    // mounts so the rail is already inside the focus scope, and a hub with nothing cached just produces a
    // shorter picker. One quiet credit line, in the picker and nowhere else.
    const got = await characterFaces(faceSeed(), 40).catch(() => []);
    drawFaces(got);
    /* The credit is built from what the hub says each face came from. Nothing here names an upstream, so a hub
       that changes its sources changes the credit by itself. */
    const credits = [];
    for (const f of got) {
      const c = f.from || f.source;
      if (c && !credits.includes(c)) credits.push(c);
    }
    const parts = [
      el('h2', { text: 'Choose an avatar' }),
      el('div', { class: 'who', text: `${profile.name} — move to preview, OK to keep, Back to leave it alone` }),
      el('div', { class: 'avprev' }, preview),
      got.length ? el('div', { class: 'avlabel', text: 'Characters' }) : null,
      got.length ? facesRail : null,
      got.length ? el('div', { class: 'avcred', text: `Faces: ${credits.length ? credits.join(', ') : 'this hub'}` }) : null,
      more,
      el('div', { class: 'avlabel', text: 'Generated faces' }),
      gens,
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

    // mountModal already owns a focus scope, so the picker does not push another. Closing hands the ring back to
    // the card it came from: whoever you were standing on is still who you are standing on.
    ctx.mountModal(box, () => {
      pw();
      if (current) focus.setFocus(current, { scroll: false });
    });
    const home = spec.gen ? gens.querySelector(`[data-gen="${spec.gen}"]`)
      : chars.querySelector(`[data-char="${spec.char}"]`);
    focus.focusDefault(home || facesRail.firstChild || gens.firstChild);
  }

  // Watch the ring: the row slides to keep the chosen card dead centre, the rest of the row dims against the
  // halo, and the card an Edit pill belongs to holds its size while the pill itself is focused.
  let watch = () => {};
  let settled = false;
  watch = focus.onFocus((n) => {
    const isCard = !!(n && n.classList && n.classList.contains('avatar'));
    const owner = n && n.closest ? n.closest('.avatar') : null;
    if (current) current.classList.toggle('keep', !isCard && owner === current);
    grid.classList.toggle('armed', isCard || (!isCard && owner === current));
    if (!isCard) return;
    current = n;
    centre(n);
    // The first placement is not a move, it is the starting position, so it is allowed to be instant. Turning
    // the transition on afterwards is what makes every later slide read as one continuous gesture.
    if (!settled) { settled = true; setTimeout(() => grid.classList.add('settled'), 120); }
    // The row is clipped by this screen, and a clipped box is still a scroll container: a browser asked to
    // bring a card into view will nudge the whole screen, halo and wordmark included. Held at zero, the only
    // thing that moves is the row.
    wrap.scrollLeft = 0;
    wrap.scrollTop = 0;
  });

  /*
   * A trackpad swipe, a drag, or a scroll wheel across the row does what the arrows do: the ring moves and the
   * row follows it. The row is never scrolled by hand, because then the centred card and the ring would
   * disagree about who is in the middle, and the whole point of this screen is that there is only one.
   */
  let acc = 0;
  let cooling = 0;
  wrap.addEventListener('wheel', (e) => {
    const d = Math.abs(e.deltaX) > Math.abs(e.deltaY) ? e.deltaX : e.deltaY;
    if (!d) return;
    acc += d;
    const now = Date.now();
    if (now - cooling < 190 || Math.abs(acc) < 30) return;
    focus.move(acc > 0 ? 'right' : 'left');
    acc = 0;
    cooling = now;
  }, { passive: true });

  let downX = null;
  wrap.addEventListener('pointerdown', (e) => { downX = e.clientX; });
  wrap.addEventListener('pointerup', (e) => {
    if (downX === null) return;
    const dx = e.clientX - downX;
    downX = null;
    if (Math.abs(dx) > 50) focus.move(dx < 0 ? 'right' : 'left');
  });
  wrap.addEventListener('pointercancel', () => { downX = null; });

  wrap.appendChild(glow);
  wrap.appendChild(el('div', { class: 'pbrand' }, el('span', { class: 'brand-mark' })));
  session.profiles.forEach((p, i) => grid.appendChild(tile(p, i)));

  let i = session.profiles.length;
  const add = el('button', {
    class: 'avatar avatar-add', type: 'button', 'aria-label': 'Add profile',
    style: `--i: ${i++}`,
    onclick: () => ctx.navigate('settings'),
  }, el('div', { class: 'a' }, icon('plus'), el('div', { class: 'nm', text: 'Add profile' })));
  focus.focusable(add);
  grid.appendChild(add);

  // Managing people is a card at the end of the row, not a link below the fold. The row is the menu: whatever
  // the screen can do is somewhere in the thing the eye is already sweeping. Profiles themselves belong to the
  // hub, so this is wayfinding to where they are managed rather than an editor.
  const manage = el('button', {
    class: 'avatar avatar-manage', type: 'button', 'aria-label': 'Manage profiles',
    style: `--i: ${i}`,
    onclick: () => ctx.navigate('settings'),
  }, el('div', { class: 'a' }, icon('gear'), el('div', { class: 'nm', text: 'Manage profiles' })));
  focus.focusable(manage);
  grid.appendChild(manage);

  wrap.appendChild(grid);

  // The ring starts on whoever the household used last, or on the first person they know.
  const remembered = session.profile && session.profile.id
    ? grid.querySelector(`.avatar[data-profile="${session.profile.id}"]`)
    : null;
  wrap.__defaultFocus = () => remembered || grid.querySelector('.avatar');

  wrap.__dispose = () => { watch(); };

  // ?switch=<id> presses that card for you, and ?pick=<id> opens the picker for it. A TV has no devtools and no
  // mouse, and this transition is the one thing in the product that has to be verifiable from a screenshot.
  // Both wait for the splash to clear: the intro covers this screen, and a transition that happens underneath it
  // is a transition nobody can see.
  const params = new URLSearchParams(location.search);
  // ?probe= is a temporary measurement tool, not a feature: a TV has no devtools.
  if (params.get('probe')) {
    setTimeout(() => {
      const a = grid.querySelector('.avatar');
      const cs = getComputedStyle(a);
      const gcs = getComputedStyle(grid);
      wrap.appendChild(el('pre', {
        style: 'position:fixed;top:0;left:0;z-index:99;font:16px monospace;color:#0f0;background:#000;margin:0;padding:6px',
        text: JSON.stringify({
          s: getComputedStyle(document.documentElement).getPropertyValue('--s'),
          booted: document.body.classList.contains('booted'),
          row: grid.getBoundingClientRect().toJSON(),
          rowInline: grid.getAttribute('style'),
          rowOpacity: gcs.opacity, rowTransform: gcs.transform, rowOffsetLeft: grid.offsetLeft,
          card: a.getBoundingClientRect().toJSON(),
          cardOpacity: cs.opacity, cardTransform: cs.transform, cardVis: cs.visibility, cardDisplay: cs.display,
          cardAnim: cs.animationName + ' ' + cs.animationPlayState + ' ' + cs.animationFillMode + ' ' + cs.animationDelay,
          cardOffset: [a.offsetLeft, a.offsetWidth, a.offsetHeight],
          wrapScroll: [wrap.scrollLeft, wrap.scrollTop],
          face: a.querySelector('.a').getBoundingClientRect().toJSON(),
        }, null, 1),
      }));
    }, 3000);
  }
  const afterSplash = (fn) => {
    if (!document.getElementById('boot')) { setTimeout(fn, 350); return; }
    setTimeout(() => afterSplash(fn), 120);
  };
  const auto = params.get('switch');
  if (auto) {
    afterSplash(() => {
      const node = grid.querySelector(`.avatar[data-profile="${auto}"]`);
      const p = session.profiles.find((x) => x.id === auto);
      if (node && p && !isLocked(p)) choose(p, node);
    });
  }
  const pick = params.get('pick');
  if (pick) {
    afterSplash(() => {
      const node = grid.querySelector(`.avatar[data-profile="${pick}"]`);
      if (node) openPicker(node.__profile, node);
    });
  }
  return wrap;
}

/** An unreachable avatar API must not take the picker down with it. */
function generatorUrlSafe(styleId, seed, palette) {
  try { return generatorSpec(styleId, seed, palette).src; } catch (e) { return ''; }
}
