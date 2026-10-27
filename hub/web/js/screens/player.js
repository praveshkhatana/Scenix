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
 *     next-episode, and a position bar that scrubs. Nothing is drawn as decoration.
 *   · The hub's honesty is shown, not hidden. `describePlayback` turns what the hub said about a copy —
 *     slow, re-muxing, capped, not measured — into one line in the corner, because a viewer who is told
 *     which of "slow" and "broken" they have stops blaming the television.
 *   · The chrome gets out of the way. It appears on any input and leaves after a few seconds of playback,
 *     but it never hides out from under a ring that is standing on one of its buttons.
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

const SKIP_FROM = 5000;          // spec B: the intro button lives between 5 s and 90 s
const SKIP_TO = 90000;
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
  const isLiveFeed = kind === 'channel' || kind === 'live';
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
  const status = el('div', { class: 'ply-status' });
  const hud = el('div', { class: 'ply-hud ply-fade' },
    el('div', {}, title, subtitle),
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
  const tool = (name, label, fn, val) => {
    const b = el('button', { class: 'iconbtn', type: 'button', 'aria-label': label, title: label,
      onclick: () => fn() }, icon(name), el('span', { text: label }));
    if (val) b.appendChild(el('b', { class: 'val', text: val }));
    focus.focusable(b);
    return b;
  };
  const btnSub = tool('cc', 'Subtitles', () => openMenu('sub'), subLabel());
  const btnAudio = tool('audio', 'Audio', () => openMenu('audio'), audioLabel());
  const btnSpeed = tool('speed', 'Speed', () => openMenu('speed'), '1x');
  const btnQuality = tool('globe', 'Quality', () => openMenu('quality'), qualityLabel());
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

  const skip = el('button', { class: 'skip', type: 'button', text: 'Skip intro',
    onclick: () => { player.seek(SKIP_TO); hideSkip(); } });
  focus.focusable(skip);
  let skipShown = false;

  const nextUp = el('div', { class: 'nextup ply-fade' });
  nextUp.hidden = true;

  const center = el('div', { class: 'ply-center' });
  const panel = el('div', { class: 'panel', style: 'bottom: calc(100% + 16px); right: 0;' });
  panel.hidden = true;
  bottom.appendChild(panel);

  root.appendChild(stage);
  root.appendChild(top);
  root.appendChild(hud);
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
      tLeft.textContent = 'Live';
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

  function row(label, sub, on, fn) {
    const b = el('button', { class: `prow${on ? ' on' : ''}`, type: 'button', onclick: () => fn() },
      el('span', { class: 'lbl' }, el('span', { text: label }), sub ? el('span', { class: 'sub', text: sub }) : null),
      on ? el('span', { class: 'val', text: 'On' }) : null);
    focus.focusable(b);
    return b;
  }

  /**
   * One panel, refilled per request. Opening a menu is an input, so the chrome stays up while one is open —
   * the panel is inside the bottom bar, and hiding the bar would hide the thing being chosen from.
   */
  function openMenu(kind) {
    if (openKind === kind) return closeMenu();
    openKind = kind;
    panel.hidden = false;
    panel.textContent = '';
    if (kind === 'audio') {
      panel.appendChild(el('h4', {}, icon('audio'), el('span', { text: 'Audio' })));
      const list = player.audioTracks || [];
      if (!list.length) panel.appendChild(el('div', { class: 'note', text: 'This copy carries one audio track, so there is nothing to switch.' }));
      for (const t of list) {
        panel.appendChild(row(langName(t.language) || t.title || `Track ${t.id}`,
          [t.codec, t.default ? 'default' : ''].filter(Boolean).join(' · '),
          String(t.id) === String(player.selectedAudio),
          () => { player.setAudio(t.id).then(() => { btnAudio.lastChild.textContent = audioLabel(); closeMenu(); refreshStatus(); }); }));
      }
    } else if (kind === 'sub') {
      panel.appendChild(el('h4', {}, icon('cc'), el('span', { text: 'Subtitles' })));
      panel.appendChild(row('Off', '', !player.selectedSub,
        () => { player.setSub('off').then(() => { btnSub.lastChild.textContent = subLabel(); closeMenu(); }); }));
      const list = player.tracks().text || [];
      if (!list.length) panel.appendChild(el('div', { class: 'note', text: 'No subtitles came back for this title. The hub looks for them by your profile’s languages.' }));
      for (const t of list) {
        panel.appendChild(row(langName(t.language) || t.title || t.label || `Subtitle ${t.id}`,
          t.kind === 'addon' ? 'from the subtitle addon' : (t.codec || 'in the file'),
          String(t.id) === String(player.selectedSub),
          () => { player.setSub(t.id).then(() => { btnSub.lastChild.textContent = subLabel(); closeMenu(); }); }));
      }
    } else if (kind === 'quality') {
      panel.appendChild(el('h4', {}, icon('globe'), el('span', { text: 'Quality' })));
      panel.appendChild(row('Let the hub choose', 'it ranks every copy it can find',
        !(player.target && player.target.picked && player.pinned),
        () => { player.pinned = false; player.setQuality('auto').then(() => { btnQuality.lastChild.textContent = qualityLabel(); closeMenu(); refreshStatus(); }); }));
      for (const s of player.sources || []) {
        // Spec D: a copy that already froze on this title stays offered - it may be the biggest file and the
        // viewer may want to gamble - but it is dimmed and labelled, because pretending it is as good as the
        // others is how someone ends up re-choosing the broken link five times in a row.
        const bad = player.isFailed(s);
        const bits = [s.provider, s.quality && qualityWord(s.quality), s.sizeBytes ? fmtSize(s.sizeBytes) : null,
          s.seeders != null ? `${s.seeders} seeding` : null, bad ? 'stalled here before' : null].filter(Boolean).join(' · ');
        const r = row(s.label || s.quality || 'copy', bits,
          !!(player.target && player.target.picked && String(player.target.picked.id) === String(s.id)),
          () => { player.pinned = true; player.setQuality(s.id).then(() => { btnQuality.lastChild.textContent = qualityLabel(); closeMenu(); refreshStatus(); },
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
          onclick: () => { player.setSpeed(x); btnSpeed.lastChild.textContent = `${x}x`; rate.textContent = x === 1 ? '' : `${x}x`; closeMenu(); } });
        focus.focusable(b);
        segs.appendChild(b);
      }
      panel.appendChild(segs);
      panel.appendChild(el('div', { class: 'note', text: 'Live channels play at their own speed.' }));
    }
    focus.focusDefault(panel.querySelector('.prow, .seg'));
  }

  function closeMenu() {
    openKind = null;
    panel.hidden = true;
    panel.textContent = '';
  }

  /* ------------------------------------------------------------------- events */

  // True while a decision we asked for is on screen. The shell reports READY whenever it feels like it -
  // it flips between READY and BUFFERING all through a normal film - so an unrelated READY must not wipe
  // away a question the viewer has not answered yet. Only their own choice, or the picture actually
  // starting to move again, closes it.
  let asking = false;

  const offs = [];
  offs.push(player.on('negotiating', () => {
    setStatus('Finding a copy that will play on this TV…', 'wait');
    center.textContent = '';
    center.appendChild(el('div', { class: 'big', text: 'Getting the picture' }));
    center.hidden = false;
  }));
  offs.push(player.on('ready', () => {
    if (!asking) center.hidden = true;
    setPlayIcon();
    drawPosition();
    refreshStatus();
    // Native captions are painted by the shell, which does not know our bar is there.
    player.subMargin(Math.round(bottom.getBoundingClientRect().height || px(140)));
  }));
  offs.push(player.on('position', () => { drawPosition(); drawNext(); tickSkip(); }));
  offs.push(player.on('buffering', (d) => { refreshStatus(d && d.on === false ? {} : {}); drawPosition(); }));
  offs.push(player.on('tracks', () => {
    btnAudio.lastChild.textContent = audioLabel();
    btnSub.lastChild.textContent = subLabel();
  }));
  offs.push(player.on('target', (t) => {
    player.sources(t.alternatives || t.sources || []);
    btnQuality.lastChild.textContent = qualityLabel();
    if (t.title && !spec.title) title.textContent = t.title;
    if (t.tagline && !spec.subtitle) subtitle.textContent = t.tagline;
    live = !(Number(t.durationMs) > 0);
    // The hub names the following episode in the same answer that gives us this copy, so the button at the
    // edge of the picture can appear the moment there is something to press it for.
    paintEdge();
    refreshStatus();
  }));
  offs.push(player.on('retrying', () => { center.hidden = true; setStatus('Asking for this copy again...', 'wait'); }));
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
    // An intro is a thing the hub marks on an episode. On a feature, or on a channel, a button offering to
    // jump past one would be a guess dressed up as a control.
    if (isLiveFeed || (!isSeries && !spec.introEndMs)) return hideSkip();
    const introEnd = Number(spec.introEndMs) || SKIP_TO;
    if (st.positionMs >= SKIP_FROM && st.positionMs < introEnd) showSkip();
    else hideSkip();
  }

  /* -------------------------------------------------------------------- keys */

  // Topmost while this screen lives: the transport owns left/right/OK, and digits mean "jump to".
  const handler = (e, key) => {
    show();
    if (key === 'Escape' || key === 'GoBack' || key === 'BrowserBack' || key === 'Back') {
      if (openKind) { closeMenu(); return true; }
      return false;                       // let the router unwind the route
    }
    if (key === 'Backspace') {
      if (openKind) { closeMenu(); return true; }
      return false;
    }
    if (key === 'MediaPlayPause' || key === 'PlayPause') { player.toggle(); setPlayIcon(); return true; }
    // A live feed has no position for a remote to jump around, so the transport keys are left to the router
    // rather than spending them on a seek that cannot land.
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

  focus.focusDefault(btnPlay);
  setPlayIcon();
  drawPosition();
  paintEdge();
  show();

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
    player.stop();
    player.dispose();
    setFeatureVisible(false);
    if (native) setSurfaceLive(false);
    for (const off of offs) { try { off(); } catch (e) { /* already gone */ } }
  };
  return root;
}
