/**
 * js/screens/profiles.js — "Who's watching?", and the door into the household.
 *
 * The list is whatever `/app/v1/profiles` returns: names, kinds, age bands, whether a PIN is required.
 * Nothing here knows there are four people in this house or that two of them are children.
 *
 * Choosing a profile is the product's signature transition, so the screen does not navigate: it hands the
 * tile it was given to `ctx.switchProfile`, which flies that face to the nav bar while Home assembles
 * underneath it.
 */

import { el } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { icon } from '/ui/js/icons.js';
import { initials } from '/ui/js/format.js';
import { session, isKids, profileAvatarClass, adultProfilesExist } from '/ui/js/state.js';

export function screen(ctx) {
  const wrap = el('div', { class: 'screen pwrap' });
  const grid = el('div', { class: 'pfs' });
  let busy = false;

  function choose(profile, node) {
    if (busy || !profile) return;
    if (profile.pinRequired) {
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
    const face = el('div', { class: `a ${kids ? 'k' : profileAvatarClass(profile, i)}` },
      el('span', { class: 'ini', text: initials(profile.name) }));
    if (profile.pinRequired) face.appendChild(el('span', { class: 'lock' }, icon('lock')));
    if (kids) face.appendChild(el('span', { class: 'badge kids kbadge', text: 'Kids' }));
    const node = el('button', {
      class: 'avatar', type: 'button', 'aria-label': profile.name,
      dataset: { profile: profile.id },
      style: `--i: ${i}`,
      onclick: () => choose(profile, node),
    }, face, el('div', { class: 'nm', text: profile.name }));
    focus.focusable(node);
    return node;
  }

  wrap.appendChild(el('h1', { text: "Who's watching?" }));
  session.profiles.forEach((p, i) => grid.appendChild(tile(p, i)));

  const add = el('button', {
    class: 'avatar avatar-add', type: 'button', 'aria-label': 'Add profile',
    style: `--i: ${session.profiles.length}`,
    onclick: () => ctx.navigate('settings'),
  }, el('div', { class: 'a' }, icon('plus')), el('div', { class: 'nm', text: 'Add profile' }));
  focus.focusable(add);
  grid.appendChild(add);
  wrap.appendChild(grid);

  // Profiles belong to the hub, so this is a wayfinding affordance, not an editor.
  if (adultProfilesExist()) {
    const manage = el('button', {
      class: 'manage', type: 'button', text: 'Manage profiles',
      onclick: () => ctx.navigate('settings'),
    });
    focus.focusable(manage);
    wrap.appendChild(manage);
  }

  // The ring starts on whoever the household used last, or on the first person they know.
  const remembered = session.profile && session.profile.id
    ? grid.querySelector(`.avatar[data-profile="${session.profile.id}"]`)
    : null;
  wrap.__defaultFocus = () => remembered || grid.querySelector('.avatar');

  // ?switch=<id> presses that avatar for you. A TV has no devtools and no mouse, and this transition is the
  // one thing that has to be verifiable from a screenshot.
  const auto = new URLSearchParams(location.search).get('switch');
  if (auto) {
    const node = grid.querySelector(`.avatar[data-profile="${auto}"]`);
    const p = session.profiles.find((x) => x.id === auto);
    if (node && p && !p.pinRequired) setTimeout(() => choose(p, node), 400);
  }
  return wrap;
}
