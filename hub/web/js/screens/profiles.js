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
 *
 * Adding a person and managing them are *not* done here. The gate is the row of faces and nothing else: the two
 * cards at the end of the row open `#/add` and `#/manage`, their own screens. A viewer standing in front of the
 * row should be able to make someone new without walking into the settings of the hub they are not using yet.
 */

import { el } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { icon } from '/ui/js/icons.js';
import { initials } from '/ui/js/format.js';
import { session, isKids, isLocked } from '/ui/js/state.js';
import { paintAvatar } from '/ui/js/avatars.js';
import { openAvatarPicker } from '/ui/js/avatarmodal.js';
import { refreshProfiles } from '/ui/js/profileedit.js';

let refreshed = false;

export function screen(ctx) {
  // The gate is the one screen a household reaches after changing something somewhere else (a PIN set on
  // another device, a profile removed on the hub). The session copy is a cache; refresh it once when the
  // door opens so a lock that was just put on a person is actually asked for on this screen.
  if (!refreshed) {
    refreshed = true;
    refreshProfiles()
      .then(() => { if (location.hash.startsWith('#/profiles')) ctx.reload(); })
      .catch(() => {});
  }
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
   * Changing a face. An open profile is edited straight away; a locked one asks for the PIN first, because the
   * gate is there to stop someone changing the child's settings on the way past, not to protect the face. The
   * picker itself is shared with `#/manage` and `#/add` (js/avatarmodal.js) so one screen cannot drift from the
   * others on what choosing a face means.
   */
  function editProfile(profile, node) {
    if (!profile) return;
    if (isLocked(profile)) {
      ctx.askPin(profile).then((ok) => {
        if (ok) openAvatarPicker(ctx, {
          profile, face: node.querySelector('.a'), index: node.__index, restore: node,
        });
      });
      return;
    }
    openAvatarPicker(ctx, { profile, face: node.querySelector('.a'), index: node.__index, restore: node });
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
  // Both cards at the end of the row open their own screen. Sending a viewer to Settings to add a person makes
  // them find the household section of a page about the hub, with the row of faces they were looking at gone
  // behind them; `#/add` keeps the face they are making in front of them the whole time.
  const add = el('button', {
    class: 'avatar avatar-add', type: 'button', 'aria-label': 'Add profile',
    style: `--i: ${i++}`,
    onclick: () => ctx.navigate('add'),
  }, el('div', { class: 'a' }, icon('plus'), el('div', { class: 'nm', text: 'Add profile' })));
  focus.focusable(add);
  grid.appendChild(add);

  // Managing people is a card at the end of the row, not a link below the fold. The row is the menu: whatever
  // the screen can do is somewhere in the thing the eye is already sweeping. Profiles themselves belong to the
  // hub, so this is wayfinding to where they are managed rather than an editor.
  const manage = el('button', {
    class: 'avatar avatar-manage', type: 'button', 'aria-label': 'Manage profiles',
    style: `--i: ${i}`,
    onclick: () => ctx.navigate('manage'),
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
      if (node) editProfile(node.__profile, node);
    });
  }
  return wrap;
}
