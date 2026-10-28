/**
 * js/screens/manage.js — the household, one row at a time.
 *
 * This is its own screen, reached from the card at the end of the profile row — not a modal sitting on top of
 * the picker, and not a section of Settings. The reason is the list itself: to change someone you have to see
 * all of them at once, and on the gate the cards are 280 px faces whose whole job is to be chosen. A list is the
 * shape that carries a name, what kind of profile it is, whether a PIN is on it, and a way into that person's
 * settings without entering them.
 *
 * Opening a person does not stop at their padlock — looking is free. What needs the number is moving it, and the
 * hub's rule is that the PIN on a profile has to be produced before its PIN, its mode, its age, its rules and its
 * languages move. So the pad asks at the moment a protected control is actually touched, which is the same rule
 * Settings follows (both go through js/profileedit.js), and the sheet repaints from whatever the hub answered, so
 * it can never show a change that did not happen.
 *
 * Nothing here knows this household: the ages offered, the languages named, and whether a new profile starts
 * behind a PIN all come from `/app/v1/settings`, and the people come from `/app/v1/profiles`.
 */

import { el, clear } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { icon } from '/ui/js/icons.js';
import { initials } from '/ui/js/format.js';
import { session, isKids, isLocked } from '/ui/js/state.js';
import { api } from '/ui/js/api.js';
import { paintAvatar } from '/ui/js/avatars.js';
import { openAvatarPicker } from '/ui/js/avatarmodal.js';
import { profileBadges, profileDefaults, profileEditor, profileNote, refreshProfiles } from '/ui/js/profileedit.js';
import { makeRows } from '/ui/js/rows.js';

export function screen(ctx) {
  const wrap = el('div', { class: 'screen pmanage' });
  const rows = makeRows(ctx);
  // The hub's vocabulary, asked for once and cached for as long as this screen is open. An empty hub answer
  // simply means fewer controls, never a wrong default invented here.
  let hub = { ageOptions: [] };
  const people = el('div', { class: 'prows' });

  const head = el('div', { class: 'page-head' },
    el('h1', { text: 'Manage profiles' }),
    el('p', {
      class: 'sub',
      text: 'Faces, names, what each person may watch, and the PIN on each one. Moving a locked profile’s own settings needs the number already on it.',
    }),
    // Said out loud rather than left to be found: on a hub where the children's profiles are the unlocked ones,
    // the open door is the reason a household comes here, and a parent should not have to hunt for the fact that
    // a lock is even available. Rewritten with the rows, because the answer changes the moment a PIN is set.
    el('p', { class: 'sub', dataset: { hublock: '1' } }));

  /* ----------------------------------------------------------------------- rows */

  /** One person: their face, who they are, and the way into their settings. */
  function row(p, i) {
    const face = el('div', { class: 'a' }, el('span', { class: 'ini', text: initials(p.name) }));
    paintAvatar(face, p, i);
    if (isLocked(p)) face.appendChild(el('span', { class: 'lock' }, icon('lock')));

    const pills = el('div', { class: 'pills' });
    for (const b of profileBadges(p)) pills.appendChild(el('span', { class: 'pc', text: b }));
    const active = session.profile && String(session.profile.id) === String(p.id);
    if (active) pills.appendChild(el('span', { class: 'pc on', text: 'In use now' }));

    const node = el('button', {
      class: 'mrow', type: 'button',
      'aria-label': `${p.name} — ${profileNote(p, active && session.profile.id)}`,
      dataset: { profile: String(p.id) },
      style: `--i: ${i}`,
    }, face,
    el('div', { class: 'mbody' },
      el('div', { class: 'mnm', text: p.name }),
      el('div', { class: 'mmeta', text: profileNote(p, active && session.profile.id) }),
      pills),
    // The same glyph the gate's Edit pill uses, so "this opens their settings" is one language across the two
    // screens rather than a second arrow the viewer has to learn.
    el('span', { class: 'mgo' }, icon('sliders')));
    node.__profile = p;
    node.__index = i;
    node.onclick = () => openPerson(p, node);
    focus.focusable(node);
    return node;
  }

  function paintRows() {
    clear(people);
    session.profiles.forEach((p, i) => people.appendChild(row(p, i)));
    const add = el('button', { class: 'mrow madd', type: 'button', 'aria-label': 'Add profile' },
      el('div', { class: 'a' }, icon('plus')),
      el('div', { class: 'mbody' },
        el('div', { class: 'mnm', text: 'Add profile' }),
        el('div', { class: 'mmeta', text: 'Make someone new, and give them a face.' })),
      el('span', { class: 'mgo' }, icon('plus')));
    add.onclick = () => ctx.navigate('add');
    focus.focusable(add);
    people.appendChild(add);

    /* Which of the hub's adult rules are actually armed, in the household's own words. The hub decides that on
       every request — a screen cannot know whether some other box has just set a number — so this sentence is a
       reading of the list the hub gave, refreshed with the rows. */
    const line = head.querySelector('[data-hublock]');
    if (line) {
      const withPins = session.profiles.filter((p) => p.pinSet);
      line.textContent = withPins.length
        ? 'The hub holds these numbers, so an Adult profile, a removal, a raised age and the adult switch all ask for one of them first.'
        : 'No PIN is set on any profile here: at this TV anyone can make an Adult profile, remove one, or raise a child’s age. Setting a PIN on a profile is what makes the hub ask.';
    }
  }

  /* ------------------------------------------------------------------ one person */

  /** A small text button inside a row — clearer on a TV than a glyph that has to be guessed. */
  function action(label, ctl, fn) {
    const b = el('button', { class: 'btn line', type: 'button', text: label });
    b.dataset.ctl = ctl;
    b.onclick = fn;
    focus.focusable(b);
    return b;
  }

  function openPerson(p, card) {
    if (!p) return;
    const box = el('div', { class: 'mbox psheet' });
    const editor = profileEditor(ctx, p, { afterWrite: () => { repaint(); paintRows(); } });

    ctx.mountModal(box, () => {
      const live = rows.querySelector(`.mrow[data-profile="${cssAttr(p.id)}"]`) || card;
      if (live) focus.setFocus(live, { scroll: false });
    });
    paint();

    /** Draw the sheet from what the hub currently says about this person. */
    function paint() {
      clear(box);
      const face = el('div', { class: 'a' }, el('span', { class: 'ini', text: initials(p.name) }));
      paintAvatar(face, p, session.profiles.indexOf(p));
      box.appendChild(el('div', { class: 'psheet-who' }, face,
        el('div', { class: 'psheet-id' },
          el('h2', { text: p.name }),
          el('p', { text: profileNote(p, session.profile && String(session.profile.id) === String(p.id) && p.id) }))));

      /* Name and face are the two things the hub does not gate, and they are the two things a person may change
         without knowing anyone's number: a padlock keeps a child from lifting their own ceiling, not from being
         found on the row. */
      const nameRow = line('Name', 'On the card, and on the keypad that opens it.');
      nameRow.appendChild(value(p.name));
      nameRow.appendChild(action('Change', 'name', async () => {
        const next = await ctx.enterText({
          title: 'Rename this profile',
          reason: 'The name on the card, and on the keypad that opens it.',
          initial: p.name,
          action: 'Save',
          validate: (v) => (v ? '' : 'A profile needs a name'),
        });
        if (next === null) return;
        await editor.rename(next);
      }));
      box.appendChild(nameRow);

      const faceRow = line('Avatar', 'A style and a seed, or a picture this hub serves — nothing is stored on the TV.');
      faceRow.appendChild(action('Change', 'avatar', () => {
        openAvatarPicker(ctx, {
          profile: p,
          face: card ? card.querySelector('.a') : face,
          index: session.profiles.indexOf(p),
          restore: box.querySelector('[data-ctl="avatar"]'),
          // The row behind the sheet is repainted by the picker itself; the list only needs to agree afterwards.
          onClose: () => paintRows(),
        });
      }));
      box.appendChild(faceRow);

      toggle(box, 'Kids profile',
        'Held to the ratings the hub knows and to the ages on its own certificates. Making this person an adult is an adult’s act: the number on this profile, or an adult’s, opens it.',
        isKids(p), async (on) => {
          const ok = await editor.setKind(on ? 'kids' : 'adult');
          if (!ok) repaint();   // the hub said no: put the switch back the way the hub has it
        });
      const sw = box.querySelector('.sw');
      if (sw) sw.dataset.ctl = 'kids';

      if (hub.ageOptions.length && isKids(p)) {
        const ages = hub.ageOptions.slice();
        const current = p.age === null || p.age === undefined ? '' : p.age;
        if (current !== '' && ages.indexOf(current) < 0) ages.push(current);
        const ageSec = el('div', { class: 'psheet-sec' }, el('h3', { text: 'Age' }));
        const chips = radioSet(ageSec, [''].concat(ages), current,
          (a) => (a === '' ? 'No age' : String(a)),
          async (a) => { await editor.setAge(a === '' ? null : Number(a)); },
          () => ctx.toast('This hub is read-only from a browser'));
        for (const c of chips.querySelectorAll('.chip')) c.dataset.ctl = 'age';
        ageSec.appendChild(el('p', {
          class: 'note',
          text: 'The number on the profile decides which certificates this person is offered. No age is the strictest answer, and the hub asks for a PIN before a child’s age is raised.',
        }));
        box.appendChild(ageSec);
      }

      const locked = isLocked(p);
      /* On a child's profile the absence of a number is not a neutral fact, so it is not written as one. This is
         the row a parent is looking at when they ask why the ceiling moved, and the way to close it is on the
         same line. */
      const pinRow = line('PIN', locked
        ? 'This profile opens on its number, and only that number moves its settings.'
        : isKids(p)
          ? 'No number on this child’s profile. Anyone at this TV opens it, and an adult’s number is what moves its age, its filters and its Kids switch.'
          : 'No number on this profile: anyone who reaches this TV can open it.');
      pinRow.appendChild(value(locked ? 'Set' : 'Not set'));
      pinRow.appendChild(action(locked ? 'Change PIN' : 'Set a PIN', 'pin', async () => { await editor.setPin(); }));
      if (locked) pinRow.appendChild(action('Remove PIN', 'unpin', async () => { await editor.clearPin(); }));
      box.appendChild(pinRow);

      const del = line('Remove profile', locked
        ? 'Their list, their progress and their PIN go with the profile, and the PIN has to be produced to remove it.'
        : 'Their list, their progress and their PIN go with the profile. On a hub where somebody has a PIN, removing one without a number of its own asks for an adult’s.');
      del.appendChild(action('Remove', 'remove', async () => {
        const out = await editor.remove();
        if (!out) return;
        ctx.closeModals();
        if (out.wasActive) {
          // The person who was watching has been removed, so the household is back at its door.
          ctx.setProfile(null);
          ctx.navigate('profiles');
        }
      }));
      box.appendChild(del);

      const done = el('button', { class: 'btn white', type: 'button', text: 'Done' });
      done.dataset.ctl = 'done';
      done.onclick = () => ctx.closeModals();
      focus.focusable(done);
      box.appendChild(el('div', { class: 'psheet-foot' }, done));
    }

    /** Repaint and hand the ring back to the control the viewer was standing on. */
    function repaint() {
      const was = focus.getFocus();
      const ctl = was && was.dataset ? was.dataset.ctl : '';
      paint();
      const next = ctl ? box.querySelector(`[data-ctl="${cssAttr(ctl)}"]`) : null;
      if (next) focus.setFocus(next, { scroll: false });
    }

    focus.focusDefault(box.querySelector('[data-ctl]'));
  }

  function cssAttr(s) {
    return (window.CSS && CSS.escape) ? CSS.escape(String(s)) : String(s).replace(/["\\]/g, '\\$&');
  }

  /* ---------------------------------------------------------------------- wiring */

  async function loadHub() {
    try {
      const s = await api.settings();
      hub = { ageOptions: profileDefaults(((s && s.settings) || {})).ageOptions };
    } catch (e) {
      hub = { ageOptions: [] };
    }
  }

  wrap.appendChild(head);
  wrap.appendChild(rows);
  paintRows();

  /* A person added or removed on another screen — or on another TV — would otherwise stay invisible here until
     the next visit, because the session list is fetched once at boot. */
  loadHub().then(() => refreshProfiles()).then(() => paintRows());

  const remembered = session.profile && session.profile.id
    ? rows.querySelector(`.mrow[data-profile="${cssAttr(session.profile.id)}"]`)
    : null;
  wrap.__defaultFocus = () => remembered || rows.querySelector('.mrow');
  return wrap;
}
