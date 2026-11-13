/**
 * js/screens/games.js — the Game mode, in the browser.
 *
 * The hub shelves this section once for every client (`GET /app/v1/games`), so this page draws what it is
 * told and invents nothing: the first title of the first shelf is lifted out of its rail and drawn as the
 * page's hero — the same move the television's Games screen makes — and everything under it is an ordinary
 * rail through the same card and row plugins Home uses. A shelf that fails is that shelf's problem, not the
 * page's.
 *
 * Opening a game is not playing a video. A game is a page on its maker's own address, opened here rather
 * than on a portal, which is the whole reason it carries no ads: the portal SDKs that carry them are never
 * asked for. So the launcher is a full-screen frame plus a bar, not a player with a seek bar, and what it
 * owns is the way out — BACK in the television shell, and this page's own button in a browser, where an
 * iframe holding the D-pad keeps Escape for the game.
 */

import { api } from '/ui/js/api.js';
import { el } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { icon } from '/ui/js/icons.js';
import { rowFor, cardFor, lazyMount, dropMountedRows } from '/ui/js/plugins.js';

export function screen(ctx) {
  const root = el('div', { class: 'screen games' });
  const heroSlot = el('div', { class: 'game-hero-slot' });
  const shelves = el('div', { class: 'rows pulled' });
  const head = el('div', { class: 'page-head' },
    el('h1', { text: 'Games' }),
    el('p', { class: 'sub', text: 'Opening the arcade…' }));
  root.appendChild(head);
  root.appendChild(heroSlot);
  root.appendChild(shelves);

  let dead = false;
  root.__dispose = () => { dead = true; dropMountedRows(root); };

  /* ---------------------------------------------------------------- launcher */

  /**
   * One game, full screen. `playUrl` first; `fallbackUrl` is the same title on a host that answers where
   * the first one refuses this region, so it is offered rather than fired blindly — a mirror that is not
   * needed costs a keystroke nobody pressed.
   */
  function launch(g) {
    const frame = el('iframe', {
      class: 'game-frame',
      title: g.title,
      allow: 'fullscreen; autoplay; gamepad; keyboard-map; fullscreen;top-navigation',
      referrerpolicy: 'no-referrer-when-downgrade',
    });
    frame.tabIndex = -1;

    const status = el('span', { class: 'gl-status', text: `Opening ${g.title}…` });
    const back = el('button', { class: 'gl-exit', type: 'button', onclick: () => ctx.back() },
      icon('back'), 'Back');
    const bar = el('div', { class: 'gl-bar' },
      el('span', { class: 'gl-title', text: g.title }),
      el('span', { class: 'gl-pad', text: g.pad ? 'Controller ready' : 'Remote & keys' }),
      status);
    bar.appendChild(back);

    // The frame cannot tell its parent that the game inside refused to start — it is a different origin —
    // so the escapes are offered instead of waiting for an error event that never comes.
    const outs = [];
    if (g.fallbackUrl) {
      outs.push(el('button', {
        class: 'gl-out', type: 'button',
        onclick: () => { frame.src = g.fallbackUrl; status.textContent = 'Trying the mirror…'; },
      }, 'Try the mirror'));
    }
    outs.push(el('button', {
      class: 'gl-out', type: 'button',
      onclick: () => window.open(g.playUrl, '_blank', 'noopener'),
    }, 'Open in a new tab'));
    if (g.coi && !window.crossOriginIsolated) {
      outs.unshift(el('span', {
        class: 'gl-note',
        text: 'This port is built multithreaded — if it stalls here, open it in a new tab.',
      }));
    }
    const hint = el('div', { class: 'gl-hint' }, ...outs,
      el('span', { class: 'gl-keys', text: g.pad
        ? 'A gamepad connected to this device plays directly.'
        : 'Arrows and OK go to the game while it holds focus.' }));

    const box = el('div', { class: 'game-launch' }, bar, frame, hint);
    const wrap = ctx.mountModal(box, () => {
      // An iframe left pointing at a game keeps its scripts, audio and request running behind the page.
      try { frame.src = 'about:blank'; } catch (e) { /* the frame is going away anyway */ }
    });
    wrap.classList.add('game-launch-wrap');
    focus.focusable(back);
    for (const b of outs) if (b.tagName === 'BUTTON') focus.focusable(b);

    // The D-pad belongs to the game, so the frame takes focus as soon as its document is there; a click is
    // the mouse's way back when a game has grabbed the pointer.
    frame.addEventListener('load', () => {
      status.textContent = '';
      try { frame.focus(); } catch (e) { /* a frame that will not take focus is not worth chasing */ }
    });
    frame.addEventListener('click', () => { try { frame.focus(); } catch (e) { /* ignore */ } });
    frame.src = g.playUrl;
  }

  /* -------------------------------------------------------------------- hero */

  /**
   * The shelf's first title, lifted out of the rail: the arcade's front page is one game, not a wall of
   * them. The billboard classes are reused rather than redrawn, so whatever hero shape the household's
   * pack chose (full-bleed, plate, centred) is the shape this hero takes too.
   */
  function buildHero(g, row) {
    const art = el('div', { class: 'bb-arts' },
      el('div', { class: 'bb-art on' },
        el('img', { src: g.poster || g.backdrop || '', alt: '', decoding: 'async' }),
        el('div', { class: 'scrim' })));
    const text = el('div', { class: 'bb' },
      el('div', { class: 'kicker' },
        el('b', { text: 'Scenix' }),
        el('span', { text: (g.tags && g.tags[0]) ? g.tags[0] : 'Game' })),
      el('h1', { text: g.title }),
      el('p', { class: 'sub', text: g.pad ? 'Controller ready — play it from the couch' : 'Remote & keyboard' }));
    if (g.desc) text.appendChild(el('p', { class: 'why', text: g.desc }));
    const play = el('button', { class: 'btn white big', type: 'button', onclick: () => launch(g) },
      icon('play'), 'Play');
    text.appendChild(el('div', { class: 'bb-btns' }, play));

    const slot = el('div', { class: 'billboard game-hero', dataset: { row: row.id } }, art, text);
    heroSlot.appendChild(slot);
    focus.focusable(play);
    return play;
  }

  /* ----------------------------------------------------------------- shelves */

  /**
   * A rail holds eighteen tiles; this section has hundreds. "See all" is therefore not another page — the
   * hub has already sent the shelf's whole list — it is the rest of this rail arriving where the viewer is
   * already standing, and the tile that asked for it leaving.
   */
  function revealRest(node, row) {
    const list = node && node.__list;
    if (!list || node.__revealed) return;
    node.__revealed = true;
    const tile = list.querySelector('.card.seeall');
    if (tile) tile.remove();
    const render = cardFor(row);
    const shown = list.querySelectorAll('.card').length;
    (row.items || []).slice(shown).forEach((item, i) => {
      list.appendChild(render(item, {
        index: shown + i, row, play: launch, open: launch,
      }));
    });
  }

  function buildRow(row, index) {
    const node = rowFor(row)(row, {
      index,
      open: (item) => launch(item),
      play: (item) => launch(item),
      explore: (r) => revealRest(node, r),
    });
    node.style.setProperty('--i', String(index));
    shelves.appendChild(node);
    lazyMount(node, { root, preload: '700px' });
  }

  async function load() {
    let rows = null;
    try {
      const payload = await api.games();
      rows = (payload && payload.rows) || [];
    } catch (e) {
      rows = null;
    }
    if (dead || !root.isConnected) return null;
    if (!rows || !rows.length) {
      // The hub answers this endpoint only when something carries games — its own catalogue or an installed
      // add-on — so an empty answer is the honest state, not a failure to hide behind.
      head.querySelector('.sub').textContent = 'The hub has no games to show right now.';
      return null;
    }

    const total = rows.reduce((n, r) => n + ((r.items || []).length), 0);
    head.querySelector('.sub').textContent =
      `${total} ${total === 1 ? 'title' : 'titles'} — browser ports from their makers, no ads asked for.`;

    let index = 0;
    let hero = null;
    for (const row of rows) {
      const items = (row.items || []).filter((it) => it && it.playUrl);
      if (!items.length) continue;
      if (!hero) {
        hero = items[0];
        buildHero(hero, row);
        // The hero title is out of the rail below it, exactly as on the television: seeing the same game
        // twice in one screenful is a shelf with a hole in it.
        const rest = items.slice(1);
        if (rest.length) buildRow(Object.assign({}, row, { items: rest, total: (row.total || items.length) - 1 }), index++);
        continue;
      }
      buildRow(Object.assign({}, row, { items }), index++);
    }

    root.__defaultFocus = () => heroSlot.querySelector('.btn')
      || shelves.querySelector('.card') || head.querySelector('h1');
    return hero;
  }

  load().catch(() => {
    if (!dead && root.isConnected) head.querySelector('.sub').textContent = 'The hub is not answering right now.';
  });
  return root;
}
