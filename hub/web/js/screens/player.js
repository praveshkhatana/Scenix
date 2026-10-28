/**
 * js/screens/player.js — the picture and the chrome that floats over it.
 *
 * This screen never touches a media element. It drives the controller in js/player.js, which owns whichever
 * backend this TV can actually decode: the shell's own player (codecs, track switching, ASS captions) or a
 * plain `<video>`. The chrome is therefore the same on both paths, and the page paints nothing opaque over
 * the picture area, because in a native shell the film is *behind* this WebView.
 *
 * Three things this screen insists on:
 *
 *   · Every control is a real control. Audio, Subtitles (including Off), Quality, speed, skip-intro,
 *     next-episode, and a position bar that scrubs. Nothing is drawn as decoration. The value each menu
 *     chip stands for is painted on the chip itself and repainted whenever the choice or the track
 *     reports change, so "what am I watching this in" never needs a button pressed to find out.
 *   · The hub's honesty is shown, not hidden. `describePlayback` turns what the hub said about a copy —
 *     slow, re-muxing, capped, not measured — into one line in the corner, because a viewer who is told
 *     which of "slow" and "broken" they have stops blaming the television.
 *   · The chrome gets out of the way. It appears on any input and leaves after a few seconds of playback,
 *     but it never hides out from under a ring that is standing on one of its buttons. The entrance and
 *     exit are slide-and-fade only — each part slides along the edge it lives on, never a layout
 *     property — and the settings sheet slides up over the bar the same way, so the D-pad always sees
 *     where it is.
 *   · A live feed behaves like a set-top box: no scrubber, no speed, left/right zap, and a number badge
 *     naming the channel that just came up.
 */

import { el } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { icon } from '/ui/js/icons.js';
import { api } from '/ui/js/api.js';
import { createPlayer, describePlayback } from '/ui/js/player.js';
import { session, hasNativePlayer, effectivePrefs } from '/ui/js/state.js';
import { clock, langName, qualityWord, episodeLabel, size as fmtSize } from '/ui/js/format.js';
import { token, px } from '/ui/js/theme.js';
import { setFeatureVisible, setSurfaceLive } from '/ui/js/video.js';
import { pushHandler, popHandler } from '/ui/js/input.js';

// An intro window is data from the hub, not a guess from the clock. The old 5 s..90 s window put a
// Skip intro button over the first minute and a half of every series, whether or not it had an intro.
const SKIP_DEFAULT_LENGTH = 90000;
const NEXT_WINDOW = 7000;       // spec B: the next-episode card appears in the last 7 s
const HIDE_AFTER = 5000;

export function screen(ctx, identity) {
  const spec = Object.assign({ identity }, session.pendingPlay || {});
  const root = el('div', { class: 'screen player' });

  /* The hub's own word for the title decides what the floor looks like. A channel is a live feed: there is
     no position to scrub to, so no line to scrub and no buttons that move along it. A series has a next
     episode waiting; a feature does not. A stream that has not reported a length yet is a different thing
     again — that is the `live` flag below, and it only ever decides whether the bar can be moved along,
     never which controls exist. */
  const kind = String(spec.type || '').toLowerCase();
  let isLiveFeed = kind === 'channel' || kind === 'live';
  const isSeries = kind === 'series';

  /* ---------------------------------------------------------------- picture */

  // In the browser path the feature element is already on the page and only needs to be allowed to paint.
  // In the shell it was never appended at all; the transparent background is the whole contract.
  const native = hasNativePlayer();
  if (native) setSurfaceLive(true); else setFeatureVisible(true);

  const player = createPlayer();

  /* ------------------------------------------------------------------ nodes */

  const stage = el('div', { class: 'ply-stage' });

  const title = el('div', { class: 'pt', text: spec.title || '' });
  const subtitle = el('div', { class: 'ps', text: spec.subtitle || '' });
  const cert = String(spec.maturity || '');
  const advisories = Array.isArray(spec.advisories) ? spec.advisories : [];
  const content = el('div', { class: 'pcontent' },
    el('span', { class: 'pccert', text: cert || 'Not rated' }),
    advisories.length ? el('span', { class: 'pcadv', text: advisories.join(' · ') }) : null);
  const status = el('div', { class: 'ply-status' });
  const hud = el('div', { class: 'ply-hud ply-fade' },
    el('div', {}, title, subtitle, content),
    el('div', { class: 'right' }, status));
  const top = el('div', { class: 'ply-top ply-fade' });

  const buf = el('i', { class: 'buf' });
  const fill = el('i', { class: 'pl' });
  const knob = el('i', { class: 'kn' });
  const trk = el('div', { class: 'trk' }, buf, fill, knob);
  const tLeft = el('span', { text: '0:00' });
  const tRight = el('span', { text: '' });
  const pbtn = (name, cls, label, fn, ico) => {
    const b = el('button', {
      class: `pbtn ${cls || ''}`, type: 'button', 'aria-label': label, title: label,
      onclick: () => fn(),
    }, icon(ico || name, { solid: name === 'play' }), el('span', { class: 'lbl', text: label }));
    focus.focusable(b);
    return b;
  };
  const btnBack = pbtn('back', '', 'Back', () => ctx.back());
  const btnRew = pbtn('rew', '', 'Rewind 10s', () => player.seekBy(-10000), 'rew');
  const btnPlay = pbtn('play', 'play', 'Play', () => player.toggle());
  const btnFwd = pbtn('fwd', '', 'Forward 10s', () => player.seekBy(10000), 'fwd');
  // Not in the row with the transport: the next episode waits at the right edge of the picture, where it can
  // be seen coming without covering the one that is playing.
  const btnNext = pbtn('next', 'edge', 'Next episode', () => playNext());
  const tool = (name, label, fn) => {
    const b = el('button', { class: 'iconbtn', type: 'button', 'aria-label': label, title: label,
      onclick: () => fn() }, icon(name), el('span', { text: label }), el('b', { class: 'val' }));
    focus.focusable(b);
    return b;
  };
  const btnSub = tool('cc', 'Subtitles', () => openMenu('sub', btnSub));
  const btnAudio = tool('audio', 'Audio', () => openMenu('audio', btnAudio));
  const btnSpeed = tool('speed', 'Speed', () => openMenu('speed', btnSpeed));
  const btnQuality = tool('globe', 'Quality', () => openMenu('quality', btnQuality));
  const rate = el('span', { class: 'rate' });

  /* Two rows and no panel: where the programme is (elapsed, the line, what is left) and what you can do to it
     (the transport at one end, the menus that open panels at the other). The scrims app.css fades over the top
     and bottom of the picture are the whole background — a painted surface on top of those reads as a box
     floating in the film. Nothing here carries a `data-nav` band: the ring walks these controls by geometry,
     which is only safe while they are all on the same floor, so every one of them lives inside the chrome that
     fades in and out together. */
  const times = el('div', { class: 'times' }, tLeft, trk, tRight);
  // A channel has no position to move along, so the line that shows one is replaced by the only fact that
  // stream supports: it is happening now.
  const liveMark = el('div', { class: 'live-mark' }, el('i', { class: 'dot' }), el('span', { text: 'Live' }));
  const bar = el('div', { class: 'ply-bar' },
    isLiveFeed ? liveMark : times,
    el('div', { class: 'pbtns' }, btnPlay, ...(isLiveFeed ? [] : [btnRew, btnFwd]),
      el('div', { class: 'ply-right' }, btnSub, btnAudio, ...(isLiveFeed ? [] : [btnSpeed]), btnQuality)));

  // Shown only when there is a next episode to go to, so a film and a channel are not offered a button that
  // does nothing.
  const edge = el('div', { class: 'ply-edge ply-fade' }, btnNext);
  edge.hidden = true;

  // The way out of a film sits at the top-left, beside the name of it, which is where a person looks for it —
  // not in the row of things you can do to it.
  top.appendChild(btnBack);

  // Which player answered, and the speed if it is not 1x: below the bar, where a person can read it
  // without it competing with the thing they are pressing.
  const ctl = el('div', { class: 'ctl' },
    el('span', { class: 'iconbtn dim' }, icon(player.backend === 'native' ? 'tv' : 'external'),
      el('span', { text: player.backend === 'native' ? 'TV player' : 'Browser player' })),
    rate);
  const bottom = el('div', { class: 'ply-bottom ply-fade' }, bar, ctl);

  /* An intro is a thing the hub marks on an episode, and the first frame of a film has not been watched long
     enough for anyone to want out of it. So the button starts put away — `gone` is opacity 0, which is also
     how focus.js decides a control is not there — and only a position report that lands inside an intro brings
     it back. */
  let skipToMs = 0;
  const skip = el('button', { class: 'skip gone', type: 'button', text: 'Skip intro',
    onclick: () => { if (skipToMs > 0) player.seek(skipToMs); hideSkip(); } });
  focus.focusable(skip);
  let skipShown = false;

  const nextUp = el('div', { class: 'nextup ply-fade' });
  nextUp.hidden = true;

  /* The set-top-box habit on a live feed: when a zap lands, the channel's number and name flash up in the
     top-left and fade out on their own, so the viewer knows which feed answered without the chrome in the
     way. `session.zapping` (js/state.js, set by the router for every channel play) names the feed this
     screen was built to show, so arriving while it matches is the whole test for "this screen is a tune".
     The number rides on the hub's channel items and on its play answer; when neither has it, the name is
     still worth flashing. */
  const zapBadge = el('div', { class: 'ply-zap' });
  zapBadge.hidden = true;
  let zapTimer = null;
  let zapShownFor = null;   // one badge per (number, name): a quality switch must not re-flash it

  function channelNumber() {
    if (spec.number != null) return spec.number;
    const rows = (session.home && session.home.rows) || [];
    for (const r of rows) {
      if (r.id !== 'live' && r.card !== 'channel') continue;
      const it = (r.items || []).find((x) => x.identity === identity);
      if (it && it.number != null) return it.number;
    }
    return null;
  }

  function showZapBadge(number, name) {
    zapShownFor = `${number == null ? '' : number}|${name || ''}`;
    zapBadge.textContent = '';
    if (number != null && number !== '') zapBadge.appendChild(el('b', { class: 'num', text: String(number) }));
    zapBadge.appendChild(el('span', { class: 'nm', text: name || '' }));
    zapBadge.hidden = false;
    requestAnimationFrame(() => zapBadge.classList.add('in'));
    if (zapTimer) clearTimeout(zapTimer);
    zapTimer = setTimeout(() => zapBadge.classList.remove('in'), 2500);
  }

  /** Called on arrival and again when the hub names the feed: the play answer may be the first thing
      that knows the channel's number. */
  function maybeZapBadge(t) {
    const who = (t && t.identity) || identity;
    if (!session.zapping || String(session.zapping) !== String(who)) return;
    const number = (t && t.live && t.live.number) || (t && t.number) || channelNumber();
    const name = (t && t.title) || spec.title || '';
    if (`${number == null ? '' : number}|${name}` === zapShownFor) return;
    showZapBadge(number, name);
  }

  const center = el('div', { class: 'ply-center' });

  /* The loading surface: the title's own artwork in the middle, with a bar that fills as the hub gets
     the copy ready. A spinner is a generic "wait"; the logo says which film is being fetched, and the bar
     says the wait is moving. The nodes are kept and refilled so the bar animates instead of restarting. */
  const loadLogo = el('div', { class: 'ply-loadlogo' });
  if (spec.logo) {
    const img = el('img', { src: spec.logo, alt: spec.title || '' });
    img.addEventListener('error', () => img.replaceWith(el('div', { class: 'ply-loadtitle', text: spec.title || 'Scenix' })));
    loadLogo.appendChild(img);
  } else {
    loadLogo.appendChild(el('div', { class: 'ply-loadtitle', text: spec.title || 'Scenix' }));
  }
  const loadFill = el('i');
  const loadBar = el('div', { class: 'ply-loadbar' }, loadFill);
  const loadText = el('div', { class: 'ply-loadtext' });
  function showLoading(text, pct) {
    if (!center.contains(loadBar)) {
      center.textContent = '';
      center.appendChild(loadLogo);
      center.appendChild(loadBar);
      center.appendChild(loadText);
    }
    loadText.textContent = text || '';
    loadFill.style.width = `${Math.max(6, Math.min(92, Number(pct) || 12))}%`;
    center.hidden = false;
  }

  const panel = el('div', { class: 'panel', style: 'bottom: calc(100% + 16px); right: 0;' });
  panel.hidden = true;
  bottom.appendChild(panel);

  root.appendChild(stage);
  root.appendChild(top);
  root.appendChild(hud);
  root.appendChild(zapBadge);
  root.appendChild(center);
  root.appendChild(skip);
  root.appendChild(nextUp);
  root.appendChild(edge);
  root.appendChild(bottom);
  skip.style.zIndex = '8';

  /* ------------------------------------------------------------------- chrome */

  let hidden = false;
  let idle = null;
  let openKind = null;

  function chromeVisible() { return !hidden; }

  // Everything that fades with the chrome, in one list, so no control can be left shining over a film that
  // has decided to hide its buttons.
  const chrome = [top, hud, bottom, nextUp, edge];

  function show() {
    hidden = false;
    for (const n of chrome) n.classList.remove('hide');
    arm();
  }

  function hide() {
    if (openKind) return;
    // The ring used to be a reason to hold the chrome up. On a TV it is not one: the bar is the only thing
    // anyone can focus here, so keeping it up would keep it up forever and the film would be watched through
    // a control panel — the way out at the top and the next episode at the edge included. Only a control
    // that is genuinely outside the chrome, the skip button, holds it on, and the first key press brings
    // everything back, the ring included.
    const a = document.activeElement;
    if (a && skip.parentNode === root && a === skip) { arm(); return; }
    if (player.state().playing === false) { arm(); return; }
    hidden = true;
    for (const n of chrome) n.classList.add('hide');
  }

  function arm() {
    if (idle) clearTimeout(idle);
    idle = setTimeout(hide, HIDE_AFTER);
  }

  function setStatus(txt, tone) {
    status.textContent = '';
    if (!txt) { status.hidden = true; return; }
    status.hidden = false;
    status.className = `ply-status${tone === 'warn' ? ' warn' : tone === 'bad' ? ' bad' : ''}`;
    if (tone === 'wait') status.appendChild(el('i', { class: 'spin' }));
    status.appendChild(el('span', { text: txt }));
  }

  /** One honest line, refreshed whenever the hub or the backend says something new. */
  function refreshStatus(extra = {}) {
    const d = describePlayback(player, extra);
    // The buffer line is true and useful once; left up for the whole film it is a debug readout. A viewer
    // who has been watching for a moment does not need to be told the hub is holding bytes for them.
    if (d && /^The hub (is holding|started with)/.test(d.text) && playingSince && Date.now() - playingSince > 7000) {
      return setStatus('', null);
    }
    if (d) return setStatus(d.text, d.tone === 'info' ? 'wait' : d.tone);
    const st = player.state();
    if (st.buffering) return setStatus('Buffering…', 'wait');
    if (!st.playing && !player.ended) return setStatus('Paused', null);
    setStatus('', null);
  }

  function setPlayIcon() {
    const playing = player.state().playing;
    // Only the triangle is a solid shape; the pause bars are two strokes, and `.ico.solid` switches the
    // stroke off — asking for solid here would paint a pause that cannot be seen.
    const ico = icon(playing ? 'pause' : 'play', { solid: !playing });
    // Ask the button for the glyph it is wearing rather than trusting a node captured at build time: the
    // first swap detaches that node, and a second call would then add a glyph beside the one already there.
    const worn = btnPlay.querySelector('.ico');
    if (worn) worn.replaceWith(ico);
    else btnPlay.insertBefore(ico, btnPlay.firstChild);
    const label = playing ? 'Pause' : 'Play';
    const lbl = btnPlay.querySelector('.lbl');
    if (lbl) lbl.textContent = label;
    btnPlay.setAttribute('aria-label', label);
    btnPlay.setAttribute('title', label);
  }

  /* ----------------------------------------------------------------- transport */

  let live = false;   // a channel has no position to scrub to

  function drawPosition() {
    const st = player.state();
    const total = Number(st.durationMs) || 0;
    const pos = Number(st.positionMs) || 0;
    const buffered = Number(st.bufferedMs) || 0;
    live = total <= 0;
    if (live) {
      fill.style.transform = 'scaleX(0)';
      buf.style.transform = 'scaleX(1)';
      knob.style.left = '0%';
      /* A film or an episode whose length the hub has not measured yet is not live — it has simply not
         started, and a torrent being probed can take a dozen seconds. Saying "Live" there sends a person
         looking for a broadcast that is not happening. A real channel has no line at all: the marker above
         the transport says it is happening now. */
      tLeft.textContent = isLiveFeed ? 'Live' : '0:00';
      tRight.textContent = '';
      return;
    }
    const p = Math.max(0, Math.min(1, pos / total));
    fill.style.transform = `scaleX(${p})`;
    buf.style.transform = `scaleX(${Math.max(0, Math.min(1, buffered / total))})`;
    knob.style.left = `${(p * 100).toFixed(2)}%`;
    tLeft.textContent = clock(pos);
    tRight.textContent = `-${clock(total - pos)}`;
  }

  function showSkip() {
    if (skipShown) return;
    skipShown = true;
    skip.classList.remove('gone');
  }
  function hideSkip() {
    if (!skipShown) return;
    skipShown = false;
    skip.classList.add('gone');
  }
  hideSkip();

  function nextEpisode() {
    const n = spec.next || (player.target && player.target.nextEpisode) || null;
    return n && (n.title || n.episode) ? n : null;
  }
  /** The button at the edge of the picture exists exactly as long as there is an episode to go to. */
  let edgeShown = false;
  function paintEdge() {
    const want = isSeries && !isLiveFeed && !!nextEpisode();
    if (want === edgeShown) return;
    edgeShown = want;
    edge.hidden = !want;
    if (!hidden) edge.classList.remove('hide');
  }
  function playNext() {
    const n = nextEpisode();
    if (!n) return;
    ctx.play(Object.assign({ identity: n.identity || spec.identity, title: n.title || spec.title }, n.type ? { type: n.type } : {}), {
      season: n.season !== undefined ? n.season : (spec.season + 1),
      episode: n.episode !== undefined ? n.episode : ((spec.episode || 0) + 1),
      next: n.next || null,
      title: n.title || spec.title,
      subtitle: episodeLabel(n.season !== undefined ? n.season : spec.season + 1, n.episode !== undefined ? n.episode : (spec.episode || 0) + 1),
    });
  }
  function drawNext() {
    paintEdge();
    const n = nextEpisode();
    const st = player.state();
    const total = Number(st.durationMs) || 0;
    if (!n || live || !total || total - st.positionMs > NEXT_WINDOW) { nextUp.hidden = true; return; }
    if (nextUp.hidden) {
      nextUp.hidden = false;
      nextUp.textContent = '';
      if (n.poster || n.backdrop) nextUp.appendChild(el('img', { src: n.backdrop || n.poster, alt: '' }));
      nextUp.appendChild(el('div', { class: 'nt', text: `Up next · ${episodeLabel(n.season ?? spec.season, n.episode ?? spec.episode)}` }));
      nextUp.appendChild(el('div', { class: 'nx', text: n.title || '' }));
      const go = el('button', { class: 'btn white', type: 'button', text: 'Play', onclick: () => playNext() });
      const stay = el('button', { class: 'btn ghost', type: 'button', text: 'Stay', onclick: () => { nextUp.hidden = true; } });
      nextUp.appendChild(el('div', { class: 'row2' }, go, stay));
      focus.focusable(go);
      focus.focusable(stay);
    }
    nextUp.classList.remove('hide');
  }

  /* -------------------------------------------------------------------- menus */

  function audioLabel() {
    const id = player.selectedAudio;
    const t = (player.audioTracks || []).find((x) => String(x.id) === String(id));
    if (t) return langName(t.language) || t.title || `Track ${t.id}`;
    return 'Default';
  }
  function subLabel() {
    const id = player.selectedSub;
    if (!id) return 'Off';
    const t = (player.textTracks || []).find((x) => String(x.id) === String(id));
    if (t) return langName(t.language) || t.title || `Sub ${t.id}`;
    return 'On';
  }
  function qualityLabel() {
    const picked = player.target && player.target.picked;
    if (!picked) return 'Auto';
    return qualityWord(picked.quality) || picked.label || 'Chosen by hub';
  }

  /* One panel, refilled per request. Opening a menu is an input, so the chrome stays up while one is open —
     the panel is inside the bottom bar, and hiding the bar would hide the thing being chosen from.
     The sheet opens upward from the bar and closes back into it (transform + opacity only, in
     css/screens.css), and while it is open the ring belongs to it: Back leaves one level at a time —
     submenu to sheet, sheet to the bar — because a viewer who cannot tell which level they are on has no
     way to get out. */
  const PANEL_MS = 220;                 // the sheet's slide duration; keep in step with screens.css
  let returnTo = null;                  // the sheet a submenu was opened from, so Back can return to it
  let panelOpener = null;               // who opened the panel: the ring goes back there on close
  let panelScoped = false;              // whether focus.pushScope(panel) is still owed its pop
  let panelTimer = null;                // finishes the hide once the slide-out has played

  /**
   * A row of a panel. `on` marks the row that answers what is playing now — the fill plus a brand tick,
   * because a viewer walking a list with a remote decides by what is marked, not by memory. `val` is the
   * current value right-aligned (the sheet), and `chev` says the row opens something rather than choosing.
   */
  function row(label, sub, on, fn, opts = {}) {
    const b = el('button', { class: `prow${on ? ' on' : ''}`, type: 'button', onclick: () => fn() },
      el('span', { class: 'lbl' }, el('span', { text: label }), sub ? el('span', { class: 'sub', text: sub }) : null));
    if (opts.val) b.appendChild(el('span', { class: 'val', text: opts.val }));
    else if (on) b.appendChild(el('span', { class: 'val tick' }, icon('check')));
    if (opts.chev) b.appendChild(el('span', { class: 'chev' }, icon('chevRight')));
    focus.focusable(b);
    return b;
  }

  /**
   * Repaints what the four bar chips promise: their current value, and the fill that says the choice is
   * real rather than default. The value on a chip is a claim about what is playing, so everything that
   * changes a track, a copy or a speed — a press, a hub answer, a track report — goes through here.
   */
  function paintChips() {
    const q = (b) => b.querySelector('.val');
    q(btnSub).textContent = subLabel();
    q(btnAudio).textContent = audioLabel();
    q(btnQuality).textContent = qualityLabel();
    const r = Number(player.rate) || 1;
    if (!isLiveFeed) q(btnSpeed).textContent = `${r}x`;
    rate.textContent = r === 1 ? '' : `${r}x`;
    btnSub.classList.toggle('on', !!player.selectedSub);
    btnAudio.classList.toggle('on', !!player.selectedAudio
      && (player.audioTracks || []).some((t) => String(t.id) === String(player.selectedAudio)));
    btnQuality.classList.toggle('on', !!player.pinned);
    btnSpeed.classList.toggle('on', r !== 1);
  }

  function openMenu(kind, opener) {
    if (openKind === kind) return closeMenu();
    if (!openKind) {
      // The sheet was opened from the bar (a chip, or the first Down): the ring belongs to the sheet now.
      panelOpener = opener || document.activeElement;
      focus.pushScope(panel);
      panelScoped = true;
    } else if (openKind === 'all') {
      // The sheet opened this submenu, so Back returns to the sheet rather than to the film.
      returnTo = 'all';
    }
    if (kind === 'all') returnTo = null;
    openKind = kind;
    if (panelTimer) { clearTimeout(panelTimer); panelTimer = null; }
    panel.textContent = '';
    panel.hidden = false;
    // The slide is something that happens *to* a laid-out panel: un-hiding it and adding the open class
    // in the same breath lets the browser skip straight to the end state and show no motion at all.
    requestAnimationFrame(() => panel.classList.add('open'));
    if (kind === 'all') {
      panel.appendChild(el('h4', {}, icon('sliders'), el('span', { text: 'Playback settings' })));
      const nAudio = (player.audioTracks || []).length;
      panel.appendChild(row('Audio', nAudio ? `${nAudio} track${nAudio === 1 ? '' : 's'}` : null,
        false, () => openMenu('audio'), { val: audioLabel(), chev: true }));
      panel.appendChild(row('Subtitles', null, false, () => openMenu('sub'),
        { val: subLabel(), chev: true }));
      panel.appendChild(row('Quality', null, false, () => openMenu('quality'),
        { val: qualityLabel(), chev: true }));
      if (!isLiveFeed) panel.appendChild(row('Speed', null, false, () => openMenu('speed'),
        { val: `${Number(player.rate) || 1}x`, chev: true }));
      panel.appendChild(el('div', { class: 'note', text: 'Up and down pick a row; OK opens the choice; Back leaves the player.' }));
    } else if (kind === 'audio') {
      panel.appendChild(el('h4', {}, icon('audio'), el('span', { text: 'Audio' })));
      const list = player.audioTracks || [];
      if (!list.length) panel.appendChild(el('div', { class: 'note', text: 'The hub has not reported the audio tracks for this copy yet. It usually arrives with the first frame.' }));
      for (const t of list) {
        panel.appendChild(row(langName(t.language) || t.title || `Track ${t.id}`,
          [t.codec, t.default ? 'default' : ''].filter(Boolean).join(' · '),
          String(t.id) === String(player.selectedAudio),
          () => { player.setAudio(t.id).then(() => { paintChips(); menuDone(); refreshStatus(); }); }));
      }
      if (player.audioMix) {
        panel.appendChild(el('div', { class: 'note', text:
          `${player.audioMix.languageName || player.audioMix.language} audio mixed from ${player.audioMix.source || 'another source'} into this picture.` }));
      }
    } else if (kind === 'sub') {
      panel.appendChild(el('h4', {}, icon('cc'), el('span', { text: 'Subtitles' })));
      panel.appendChild(row('Off', '', !player.selectedSub,
        () => { player.setSub('off').then(() => { paintChips(); menuDone(); }); }));
      const list = player.tracks().text || [];
      if (!list.length) panel.appendChild(el('div', { class: 'note', text: 'No subtitles came back for this title. The hub looks for them by your profile’s languages.' }));
      for (const t of list) {
        panel.appendChild(row(langName(t.language) || t.title || t.label || `Subtitle ${t.id}`,
          t.kind === 'addon' ? 'from the subtitle addon' : (t.codec || 'in the file'),
          String(t.id) === String(player.selectedSub),
          () => { player.setSub(t.id).then(() => { paintChips(); menuDone(); }); }));
      }
    } else if (kind === 'quality') {
      panel.appendChild(el('h4', {}, icon('globe'), el('span', { text: 'Quality' })));
      panel.appendChild(row('Let the hub choose', 'it ranks every copy it can find',
        !(player.target && player.target.picked && player.pinned),
        () => { player.pinned = false; player.setQuality('auto').then(() => { paintChips(); menuDone(); refreshStatus(); }); }));
      for (const s of player.sources || []) {
        // Spec D: a copy that already froze on this title stays offered - it may be the biggest file and the
        // viewer may want to gamble - but it is dimmed and labelled, because pretending it is as good as the
        // others is how someone ends up re-choosing the broken link five times in a row.
        const bad = player.isFailed(s);
        const bits = [s.provider, s.quality && qualityWord(s.quality), s.sizeBytes ? fmtSize(s.sizeBytes) : null,
          s.seeders != null ? `${s.seeders} seeding` : null, bad ? 'stalled here before' : null].filter(Boolean).join(' · ');
        const r = row(s.label || s.quality || 'copy', bits,
          !!(player.target && player.target.picked && String(player.target.picked.id) === String(s.id)),
          () => { player.pinned = true; player.setQuality(s.id).then(() => { paintChips(); menuDone(); refreshStatus(); },
            () => { panel.appendChild(el('div', { class: 'note', text: 'That copy could not be opened. The hub kept the one that works.' })); }); });
        if (bad) r.classList.add('dim');
        panel.appendChild(r);
      }
      panel.appendChild(el('div', { class: 'note', text: 'Switching quality re-opens the title from the new copy, a few seconds in.' }));
    } else if (kind === 'speed') {
      panel.appendChild(el('h4', {}, icon('speed'), el('span', { text: 'Speed' })));
      const segs = el('div', { class: 'segs' });
      for (const x of [0.5, 0.75, 1, 1.25, 1.5, 2]) {
        const b = el('button', { class: `seg${player.rate === x ? ' on' : ''}`, type: 'button', text: `${x}x`,
          onclick: () => { player.setSpeed(x); paintChips(); menuDone(); } });
        focus.focusable(b);
        segs.appendChild(b);
      }
      panel.appendChild(segs);
      panel.appendChild(el('div', { class: 'note', text: 'Live channels play at their own speed.' }));
    }
    focus.focusDefault(panel.querySelector('.prow, .seg'));
  }

  /**
   * A choice has been made. If the viewer got here from the sheet, go back to the sheet — the value next
   * to the row just changed, and that is the proof the choice took. Straight from a bar chip, close.
   */
  function menuDone() {
    if (openKind !== 'all' && returnTo === 'all') openMenu('all');
    else closeMenu();
  }

  /** Back leaves one level at a time: submenu to sheet, sheet to the bar. */
  function backOut() {
    if (openKind !== 'all' && returnTo === 'all') openMenu('all');
    else closeMenu();
  }

  function closeMenu() {
    openKind = null;
    returnTo = null;
    if (panelScoped) { panelScoped = false; focus.popScope(); }
    panel.classList.remove('open');
    // The slide-out plays over `hidden`: the panel is only unmounted once it has finished, and only if
    // nothing re-opened it in the meantime.
    if (panelTimer) clearTimeout(panelTimer);
    panelTimer = setTimeout(() => {
      if (!openKind) { panel.hidden = true; panel.textContent = ''; }
    }, PANEL_MS);
    // The ring goes back to whoever opened the panel: a control that was just closed is a dead key, and
    // the viewer pressing Back expects the bar they came from, not the top of the page.
    if (chromeVisible()) {
      const to = panelOpener && panelOpener.isConnected && focus.isFocusable(panelOpener) ? panelOpener : btnPlay;
      focus.focusDefault(to);
    }
    panelOpener = null;
    arm();
  }

  /* ------------------------------------------------------------------- events */

  // True while a decision we asked for is on screen. The shell reports READY whenever it feels like it -
  // it flips between READY and BUFFERING all through a normal film - so an unrelated READY must not wipe
  // away a question the viewer has not answered yet. Only their own choice, or the picture actually
  // starting to move again, closes it.
  let asking = false;
  let playingSince = 0;
  let bufferNoteTimer = null;

  const offs = [];
  offs.push(player.on('negotiating', () => {
    setStatus('Finding a copy that will play on this TV…', 'wait');
    showLoading('Finding the best copy for this TV…', 14);
  }));
  offs.push(player.on('preparing', (d) => {
    // The hub has the copy but has not put a byte on the wire yet. Keep the screen calm and say what
    // is happening; the controller asks again by itself, so there is nothing for the viewer to press.
    const attempt = Number(d && d.attempt) || 1;
    setStatus('Preparing this copy - the first seconds are being fetched', 'wait');
    showLoading('Preparing this copy…', 24 + attempt * 10);
  }));
  function armBufferNote() {
    if (bufferNoteTimer) clearTimeout(bufferNoteTimer);
    bufferNoteTimer = setTimeout(() => {
      if (status && /^The hub is holding/.test(status.textContent)) setStatus('', null);
    }, 7000);
  }
  offs.push(player.on('ready', () => {
    if (!playingSince) playingSince = Date.now();
    loadFill.style.width = '100%';
    armBufferNote();
    const st = player.state();
    if (!asking && (st.positionMs > 400 || st.playing)) center.hidden = true;
    setPlayIcon();
    drawPosition();
    refreshStatus();
    // Native captions are painted by the shell, which does not know our bar is there.
    player.subMargin(Math.round(bottom.getBoundingClientRect().height || px(140)));
  }));
  offs.push(player.on('position', () => {
    drawPosition(); drawNext(); tickSkip();
    // A question about a frozen copy is answered by the picture moving again, not only by a keypress.
    if (asking) {
      const st = player.state();
      if (st.playing && st.positionMs > 0) {
        asking = false;
        center.hidden = true;
        refreshStatus();
      }
    }
  }));
  offs.push(player.on('buffering', (d) => {
    refreshStatus(d && d.on === false ? {} : {});
    drawPosition();
    const st = player.state();
    if (d && d.buffering && st.positionMs < 800) showLoading('Buffering the first seconds…', 68);
  }));
  offs.push(player.on('tracks', () => paintChips()));
  offs.push(player.on('speed', () => paintChips()));
  offs.push(player.on('target', (t) => {
    playingSince = 0;
    // A channel played by identity (a deep link, a zap) has no `type` in the URL. The hub names the kind
    // in its answer, so the floor becomes a live floor even when the route did not say so.
    if (t && (t.live === true || String(t.type || '').toLowerCase() === 'channel')) isLiveFeed = true;
    player.sources(t.alternatives || t.sources || []);
    paintChips();
    // The play answer is often the first thing that knows the channel's stable number, so the badge gets
    // a second look here — with the number the hub itself named.
    maybeZapBadge(t);
    if (t.title && !spec.title) title.textContent = t.title;
    if (t.tagline && !spec.subtitle) subtitle.textContent = t.tagline;
    live = !(Number(t.durationMs) > 0);
    // The hub names the following episode in the same answer that gives us this copy, so the button at the
    // edge of the picture can appear the moment there is something to press it for.
    paintEdge();
    refreshStatus();
  }));
  offs.push(player.on('retrying', () => {
    // A retry is still the loading screen, not a black picture: keep the title mark and the bar.
    setStatus('Asking for this copy again...', 'wait');
    showLoading('Asking for this copy again…', 58);
  }));
  // Self-heal, in order: the playhead froze, we asked the hub for this same copy at this same point, and if
  // that brought it back the note disappears on its own. Only a second freeze interrupts anyone.
  offs.push(player.on('healing', (d) => setStatus(
    `This copy stopped at ${clock(Math.max(0, Number(d && d.positionMs) || 0))} - asking the hub for it again...`, 'wait')));
  // If the copy came back on its own while the question was still on screen, the question is moot.
  offs.push(player.on('healed', () => { center.hidden = true; asking = false; refreshStatus(); }));
  offs.push(player.on('stuck', (d) => drawStuck(d)));
  offs.push(player.on('error', (e) => {
    center.hidden = false;
    center.textContent = '';
    // Two different dead streams. One is a copy the swarm has not started feeding yet, and the answer is
    // to wait and re-ask the same address; only a copy that died after it began, or that this browser
    // cannot decode at all, is worth sending the viewer off to look for another.
    const waiting = !!(e && e.waiting);
    center.appendChild(el('div', { class: 'big',
      text: waiting ? 'This copy has not started moving yet' : 'This copy would not play' }));
    center.appendChild(el('div', { class: 'small', text: waiting
      ? 'The hub asked the peers sharing it and nothing has arrived. Waiting longer often fixes it — '
        + 'another copy is usually ready right away.'
      : (e && e.message) || 'The hub could not open it.' }));
    // A live channel has no "another copy": it has another channel. The remote's left/right already zap,
    // and the failure card must offer the same thing instead of sending the viewer to a source picker that
    // does not exist on a live feed.
    if (isLiveFeed) {
      const next = el('button', { class: 'btn white big', type: 'button', text: 'Next channel',
        onclick: () => ctx.zap(1) });
      const prev = el('button', { class: 'btn ghost big', type: 'button', text: 'Previous channel',
        onclick: () => ctx.zap(-1) });
      const home = el('button', { class: 'btn ghost big', type: 'button', text: 'Back', onclick: () => ctx.back() });
      center.appendChild(el('div', { class: 'btns' }, next, prev, home));
      focus.focusable(next); focus.focusable(prev); focus.focusable(home);
      setStatus('This channel is not answering', 'bad');
      focus.focusDefault(next);
      return;
    }
    const wait2 = el('button', { class: 'btn white big', type: 'button', text: 'Wait and try again',
      onclick: () => player.retry() });
    const again = el('button', { class: waiting ? 'btn ghost big' : 'btn white big', type: 'button',
      text: 'Try another copy',
      onclick: () => player.switchTo({ exclude: player.tried }).then(() => { center.hidden = true; }) });
    const home = el('button', { class: 'btn ghost big', type: 'button', text: 'Back', onclick: () => ctx.back() });
    if (waiting) center.appendChild(wait2);
    center.appendChild(el('div', { class: 'btns' }, again, home));
    if (waiting) focus.focusable(wait2);
    focus.focusable(again);
    focus.focusable(home);
    // A copy still gathering its first bytes is not a failure, and a red status line says otherwise.
    setStatus((e && e.message) || 'Playback failed', waiting ? 'wait' : 'bad');
    focus.focusDefault(waiting ? wait2 : again);
  }));
  offs.push(player.on('ended', () => {
    setPlayIcon();
    if (nextEpisode()) { drawNext(); nextUp.classList.remove('hide'); return; }
    ctx.back();
  }));

  /**
   * The reload has already been spent and the picture is still frozen, so this is the viewer's decision and
   * not ours: take one of the other copies, or stay with this one and let the peers catch up. Nothing is
   * offered as a third option that is really just the retry we already tried.
   */
  function drawStuck(d) {
    show();
    center.hidden = false;
    asking = true;
    center.textContent = '';
    center.appendChild(el('div', { class: 'big', text: 'This copy has stopped moving' }));
    const alt = Number((d && d.alternates) || 0);
    center.appendChild(el('div', { class: 'small', text: `${d.label} froze here and asking the hub for it again did not start it up. `
      + (alt ? `${alt} other cop${alt === 1 ? 'y is' : 'ies are'} available on this title.`
        : 'Nothing else was offered for this title, so the only choice is to wait it out.') }));
    const buttons = [];
    if (alt) {
      const other = el('button', { class: 'btn white big', type: 'button', text: 'Choose another stream',
        onclick: () => {
          setStatus('Looking for another copy...', 'wait');
          player.giveUpOnCurrent().then((t) => { if (t) { center.hidden = true; asking = false; refreshStatus(); } },
            () => setStatus('No other copy would open', 'bad'));
        } });
      focus.focusable(other);
      buttons.push(other);
    }
    const stay = el('button', { class: alt ? 'btn ghost big' : 'btn white big', type: 'button',
      text: 'Keep watching this one',
      onclick: () => { center.hidden = true; asking = false; player.keepWatching(); refreshStatus(); } });
    focus.focusable(stay);
    buttons.push(stay);
    center.appendChild(el('div', { class: 'btns' }, ...buttons));
    focus.focusDefault(buttons[0]);
  }

  function tickSkip() {
    const st = player.state();
    if (live || player.ended) return hideSkip();
    // An intro is a thing the hub marks on an episode. No window, no button: a control that guesses is
    // worse than a control that is absent. A future intro source only has to send `introEndMs`
    // (and optionally `introStartMs`) in the play answer.
    if (isLiveFeed) return hideSkip();
    const introEnd = Number(spec.introEndMs) || 0;
    if (introEnd <= 0) return hideSkip();
    const introStart = Number(spec.introStartMs) > 0
      ? Number(spec.introStartMs)
      : Math.max(0, introEnd - SKIP_DEFAULT_LENGTH);
    if (st.positionMs >= introStart && st.positionMs < introEnd) { skipToMs = introEnd; showSkip(); }
    else hideSkip();
  }

  /* -------------------------------------------------------------------- keys */

  // Topmost while this screen lives: the transport owns left/right/OK, and digits mean "jump to".
  const handler = (e, key) => {
    show();
    if (key === 'Escape' || key === 'GoBack' || key === 'BrowserBack' || key === 'Back') {
      if (openKind) { backOut(); return true; }
      return false;                       // let the router unwind the route
    }
    if (key === 'Backspace') {
      if (openKind) { backOut(); return true; }
      return false;
    }
    if (key === 'MediaPlayPause' || key === 'PlayPause') { player.toggle(); setPlayIcon(); return true; }
    // A TV viewer expects Down to reveal what can be changed. Opening the settings sheet on the first
    // Down is more discoverable than walking right through transport buttons to the small icons.
    if (key === 'ArrowDown' && !openKind && !asking && center.hidden) { openMenu('all'); return true; }
    // A live feed has no position for a remote to jump around, so the transport keys are left to the router
    // rather than spending them on a seek that cannot land.
    // A channel has no position, so left/right are the channel keys. This is the same habit as a
    // set-top box: the remote's arrows change the channel, and there is no scrubber to land on. A sheet
    // that is open owns left/right instead — the ring has to be able to walk the panel's rows.
    if (isLiveFeed && !openKind && (key === 'ArrowLeft' || key === 'ArrowRight')) {
      ctx.zap(key === 'ArrowLeft' ? -1 : 1);
      return true;
    }
    if (isLiveFeed && (key === 'MediaTrackNext' || key === 'MediaTrackPrevious')) {
      ctx.zap(key === 'MediaTrackNext' ? 1 : -1);
      return true;
    }
    if (key === 'MediaFastForward' && !isLiveFeed) { player.seekBy(10000); return true; }
    if (key === 'MediaRewind' && !isLiveFeed) { player.seekBy(-10000); return true; }
    if (key === 'MediaTrackNext' && nextEpisode()) { playNext(); return true; }
    if (key === 'ArrowLeft' || key === 'ArrowRight') {
      const a = document.activeElement;
      // The track is the scrubber; the transport buttons and the menu chips in the same row step along the
      // band instead. Same rule as before the chips moved into the bar — they just have to be named now.
      if (a === trk || (a && bar.contains(a) && !a.classList.contains('pbtn') && !a.classList.contains('iconbtn'))) {
        player.seekBy(key === 'ArrowLeft' ? -10000 : 10000);
        return true;
      }
      return false;
    }
    if (/^[0-9]$/.test(key)) {
      const st = player.state();
      if (!live && st.durationMs) player.seek(Math.floor(st.durationMs * (Number(key) / 10)));
      return true;
    }
    return false;
  };
  pushHandler(handler);
  // The scrubber is only a control when there is a position to scrub to.
  if (!isLiveFeed) {
    focus.focusable(trk);
    trk.setAttribute('aria-label', 'Position');
    // On the bar itself the D-pad scrubs; a press lands on the point that was pointed at, which is the only
    // way to move without a keyboard.
    trk.addEventListener('click', (e) => {
      const r = trk.getBoundingClientRect();
      const st = player.state();
      if (!r.width || live || !st.durationMs) return;
      player.seek(Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)) * st.durationMs);
    });
  }

  /* ------------------------------------------------------------------ start */

  /* Where the ring lands when the film opens. app.js asks the screen this question after the screen has
     finished building, and with nothing to point at it takes the first control in the document — which since
     the way out moved to the top-left would be Back, so the very first press of OK on arrival would leave
     the film. Play is the answer: it is the biggest thing on the floor and the one a person arrives to press.
     A live feed is the same answer, because play/pause is the only transport a channel offers. */
  root.__defaultFocus = () => btnPlay;

  setPlayIcon();
  paintChips();
  drawPosition();
  paintEdge();
  show();
  // Arriving on a channel *is* a tune, however we got here — from a card, a zap, or the error card's
  // channel buttons — so the badge is checked at build time with the name we were given, and again when
  // the hub's answer names the feed and its number.
  maybeZapBadge(null);

  player.start({
    identity: spec.identity,
    profile: session.profile && session.profile.id,
    type: spec.type,
    season: spec.season,
    episode: spec.episode,
    positionMs: spec.positionMs,
    title: spec.title,
  }).then((target) => {
    if (!target) return;
    center.hidden = true;
    // Subtitles from the addon are worth fetching even when the file has none of its own.
    const langs = (effectivePrefs().subtitleLanguages || []).join(',');
    api.subs(spec.identity, session.profile && session.profile.id, langs)
      .then((r) => player.useHubSubs((r && r.subs) || []))
      .catch(() => {});
  });

  // The hub keeps "continue watching" alive from what we post, so a TV that is turned off mid-film is not a
  // title that was finished.
  const postMs = Number(token('player', 'progressPostMs', 10000));
  const post = setInterval(() => {
    const st = player.state();
    if (!st.positionMs) return;
    api.progress({
      identity: spec.identity, profile: session.profile && session.profile.id, type: spec.type,
      season: spec.season, episode: spec.episode, title: spec.title,
      positionMs: Math.round(st.positionMs), durationMs: Math.round(st.durationMs || 0),
      watchedMs: Math.round(st.watchedMs || st.positionMs),
    }).then((r) => {
      // The hub answers this with what it is doing with the bytes of this film: the rate the swarm is
      // actually delivering against the bitrate the copy needs, and how much picture is sitting in its
      // memory in front of the screen. The player keeps that so the one silent heal moves to a smaller
      // copy instead of reloading one the hub can already prove is too slow.
      player.noteStream(r && r.stream);
    }).catch(() => {});
  }, postMs);

  root.addEventListener('pointermove', show);
  root.__dispose = () => {
    clearInterval(idle);
    clearInterval(post);
    popHandler(handler);
    closeMenu();
    // A zap to another channel disposes this screen with the sheet mid-slide and the badge mid-flash:
    // the timers belong to a page nobody is looking at anymore.
    if (panelTimer) { clearTimeout(panelTimer); panelTimer = null; }
    if (zapTimer) { clearTimeout(zapTimer); zapTimer = null; }
    player.stop();
    player.dispose();
    setFeatureVisible(false);
    if (bufferNoteTimer) clearTimeout(bufferNoteTimer);
    if (native) setSurfaceLive(false);
    for (const off of offs) { try { off(); } catch (e) { /* already gone */ } }
  };
  return root;
}
