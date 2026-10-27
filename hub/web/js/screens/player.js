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
  const playIco = btnPlay.querySelector('.ico');
  const btnFwd = pbtn('fwd', '', 'Forward 10s', () => player.seekBy(10000), 'fwd');
  const btnNext = pbtn('next', '', 'Next episode', () => playNext());
  const bar = el('div', { class: 'ply-bar' },
    trk, el('div', { class: 'times' }, tLeft, tRight),
    el('div', { class: 'pbtns' }, btnBack, btnRew, btnPlay, btnFwd, btnNext));

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
  const ctl = el('div', { class: 'ctl' },
    el('span', { class: 'iconbtn dim' }, icon(player.backend === 'native' ? 'tv' : 'external'),
      el('span', { text: player.backend === 'native' ? 'TV player' : 'Browser player' })),
    rate,
    el('div', { class: 'ply-right' }, btnSub, btnAudio, btnSpeed, btnQuality));
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
  root.appendChild(bottom);
  skip.style.zIndex = '8';

  /* ------------------------------------------------------------------- chrome */

  let hidden = false;
  let idle = null;
  let openKind = null;

  function chromeVisible() { return !hidden; }

  function show() {
    hidden = false;
    for (const n of [top, hud, bottom, nextUp]) n.classList.remove('hide');
    arm();
  }

  function hide() {
    if (openKind) return;
    // Never pull the floor out from under the ring: if the focus is standing in the chrome, the chrome stays.
    const a = document.activeElement;
    if (a && (bottom.contains(a) || hud.contains(a) || (skip.parentNode === root && skip === a))) { arm(); return; }
    if (player.state().playing === false) { arm(); return; }
    hidden = true;
    for (const n of [top, hud, nextUp]) n.classList.add('hide');
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
    const ico = icon(playing ? 'pause' : 'play', { solid: true });
    if (playIco.parentNode) playIco.replaceWith(ico);
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
        const bits = [s.provider, s.quality && qualityWord(s.quality), s.sizeBytes ? fmtSize(s.sizeBytes) : null,
          s.seeders != null ? `${s.seeders} seeding` : null].filter(Boolean).join(' · ');
        panel.appendChild(row(s.label || s.quality || 'copy', bits,
          !!(player.target && player.target.picked && String(player.target.picked.id) === String(s.id)),
          () => { player.pinned = true; player.setQuality(s.id).then(() => { btnQuality.lastChild.textContent = qualityLabel(); closeMenu(); refreshStatus(); },
            () => { panel.appendChild(el('div', { class: 'note', text: 'That copy could not be opened. The hub kept the one that works.' })); }); }));
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

  const offs = [];
  offs.push(player.on('negotiating', () => {
    setStatus('Finding a copy that will play on this TV…', 'wait');
    center.textContent = '';
    center.appendChild(el('div', { class: 'big', text: 'Getting the picture' }));
    center.hidden = false;
  }));
  offs.push(player.on('ready', () => {
    center.hidden = true;
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
    refreshStatus();
  }));
  offs.push(player.on('retrying', () => { center.hidden = true; setStatus('Asking for this copy again...', 'wait'); }));
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
    setStatus((e && e.message) || 'Playback failed', 'bad');
    focus.focusDefault(waiting ? wait2 : again);
  }));
  offs.push(player.on('ended', () => {
    setPlayIcon();
    if (nextEpisode()) { drawNext(); nextUp.classList.remove('hide'); return; }
    ctx.back();
  }));

  function tickSkip() {
    const st = player.state();
    if (live || player.ended) return hideSkip();
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
    if (key === 'MediaFastForward') { player.seekBy(10000); return true; }
    if (key === 'MediaRewind') { player.seekBy(-10000); return true; }
    if (key === 'MediaTrackNext' && nextEpisode()) { playNext(); return true; }
    if (key === 'ArrowLeft' || key === 'ArrowRight') {
      const a = document.activeElement;
      if (a === trk || (a && bar.contains(a) && !a.classList.contains('pbtn'))) {
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

  /* ------------------------------------------------------------------ start */

  focus.focusDefault(btnPlay);
  setPlayIcon();
  drawPosition();
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
