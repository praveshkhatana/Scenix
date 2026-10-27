/**
 * js/player.js — feature playback. One API, two interchangeable backends.
 *
 * The app talks to a controller: `start / pause / resume / toggle / seek / retry / setAudio / setSub / setSpeed /
 * setQuality / stop`, and reads what is happening through events (`ready, position, buffering, tracks,
 * error, ended`). Nothing outside this module touches a media element, and nothing outside it posts to
 * `/app/v1/play`. That is what lets the picture change technology without the product changing.
 *
 * Two backends, chosen once by feature detection:
 *
 *   NativeBackend — the TV shell (`window.Scenix` exists). Our best copies are Matroska with HEVC 10-bit
 *     and E-AC3, which a WebView cannot be relied on to decode and cannot switch tracks in mid-film. The
 *     shell's own player already does codecs, track selection, retries and ASS subtitles, so the page
 *     draws only the chrome and the picture sits behind it. We ask the hub for `container:"any"` here:
 *     the native player wants the file exactly as the swarm has it, no re-mux.
 *
 *   VideoBackend — a plain browser (laptop, phone, or the page opened directly). It asks the hub for
 *     `container:"mp4"` so the hub re-muxes into something Safari and Chrome can actually play, because
 *     Matroska in a browser is a coin toss.
 *
 * Trailer previews on cards and the billboard stay on DOM `<video>` in both cases (js/preview.js) —
 * they are 360p H.264/AAC, and starting and stopping one instantly is better done in the page than by
 * opening a second native surface.
 */

import { api, apiUrl, streamUrl } from '/ui/js/api.js';
import { shell, session, effectivePrefs } from '/ui/js/state.js';
import { token } from '/ui/js/theme.js';
import { mainSurface } from '/ui/js/video.js';

/*
 * Which video codecs this browser can actually put on screen, asked of the media stack instead of
 * guessed from a user-agent string. The hub never transcodes video, so handing a browser a copy in a
 * codec it cannot decode is not a quality compromise - it is a black screen with working sound. The
 * answer is a name list; an empty one means "never checked", which the hub reads as no constraint so
 * an older client keeps the behaviour it had.
 */
const CODEC_PROBES = [
  ['h264', 'video/mp4; codecs="avc1.4d0028"'],
  ['hevc', 'video/mp4; codecs="hvc1.1.6.L153.B0"'],
  ['av1', 'video/mp4; codecs="av01.0.05M.08"'],
  ['vp9', 'video/mp4; codecs="vp09.00.10.08"'],
];
// ffprobe and a container label spell these differently from a capability check; both sides collapse
// to the same family name before anyone compares them.
const CODEC_SPELLINGS = {
  avc: 'h264', avc1: 'h264', 'h.264': 'h264',
  h265: 'hevc', hvc1: 'hevc', hev1: 'hevc', x265: 'hevc', 'h.265': 'hevc',
};
let codecAnswer = null;
function playableCodecs() {
  if (codecAnswer) return codecAnswer;
  const ok = [];
  for (const [name, mime] of CODEC_PROBES) {
    try { if (window.MediaSource && window.MediaSource.isTypeSupported(mime)) ok.push(name); } catch (e) { /* no answer */ }
  }
  codecAnswer = ok;
  return ok;
}
function codecFamily(name) {
  const n = String(name || '').toLowerCase().trim();
  return CODEC_SPELLINGS[n] || n || null;
}
/** Unknown codec or unchecked browser: assume it may play, rather than refusing a film we have not measured. */
function canPlayVideo(codec) {
  const list = playableCodecs();
  if (!codec || !list.length) return true;
  return list.includes(codecFamily(codec));
}

/* ------------------------------------------------------------------ events */

function emitter() {
  const handlers = new Map();
  return {
    on(evt, cb) {
      if (!handlers.has(evt)) handlers.set(evt, []);
      handlers.get(evt).push(cb);
      return () => {
        const arr = handlers.get(evt) || [];
        const i = arr.indexOf(cb);
        if (i >= 0) arr.splice(i, 1);
      };
    },
    emit(evt, data) {
      for (const cb of (handlers.get(evt) || []).slice()) {
        try { cb(data); } catch (e) { console.error('[scenix] player listener', evt, e); }
      }
    },
  };
}

/* ------------------------------------------------------- normalising tracks */

function normAudio(list) {
  return (Array.isArray(list) ? list : []).map((t, i) => ({
    id: String(t.id !== undefined ? t.id : `a${i}`),
    language: t.language || '',
    title: t.title || '',
    codec: t.codec || '',
    default: !!t.default,
    kind: 'embedded',
  }));
}

function normText(list) {
  return (Array.isArray(list) ? list : []).map((t, i) => ({
    id: String(t.id !== undefined ? t.id : `s${i}`),
    language: t.language || '',
    title: t.title || '',
    url: t.url || '',
    kind: t.url ? 'external' : 'embedded',
  }));
}

/* ---------------------------------------------------------- NativeBackend */

class NativeBackend {
  constructor(emit) {
    this.emit = emit;
    this.s = shell();
    this.last = { positionMs: 0, bufferedMs: 0, durationMs: 0, playing: false };
    this.bound = [];
    const listen = (name, fn) => {
      const h = (e) => fn((e && e.detail) || {});
      document.addEventListener(name, h);
      this.bound.push([name, h]);
    };
    listen('scenix:ready', (d) => { this.merge(d); emit('ready', d); });
    listen('scenix:position', (d) => { this.merge(d); emit('position', this.state()); });
    listen('scenix:tracks', (d) => emit('tracks', { audio: normAudio(d.audio), text: normText(d.text) }));
    listen('scenix:buffering', (d) => emit('buffering', { buffering: !!d.buffering, seeders: d.seeders }));
    listen('scenix:error', (d) => emit('error', { code: d.code, message: d.message || 'The player stopped' }));
    listen('scenix:ended', (d) => { this.merge(d); emit('ended', d); });
    this.poll = setInterval(() => {
      const st = this.state();
      if (st.durationMs || st.positionMs) emit('position', st);
    }, token('player', 'progressPostMs', 10000) > 0 ? 1000 : 1000);
  }

  merge(d) {
    if (!d) return;
    if (d.positionMs !== undefined && d.positionMs !== null) this.last.positionMs = Number(d.positionMs) || 0;
    if (d.bufferedMs !== undefined && d.bufferedMs !== null) this.last.bufferedMs = Number(d.bufferedMs) || 0;
    if (d.durationMs !== undefined && d.durationMs !== null) this.last.durationMs = Number(d.durationMs) || 0;
    if (d.playing !== undefined) this.last.playing = !!d.playing;
    if (d.videoWidth) this.last.videoWidth = d.videoWidth;
    if (d.videoHeight) this.last.videoHeight = d.videoHeight;
  }

  get name() { return 'native'; }
  /** The native decoder takes the container as the torrent has it — no re-mux on the hub. */
  get container() { return 'any'; }
  /** It decodes whatever the file holds, so it puts no constraint on which copy the hub picks. */
  codecs() { return []; }

  call(fn, ...args) {
    const s = this.s;
    if (!s || typeof s[fn] !== 'function') return null;
    try { return s[fn](...args); } catch (e) { console.error('[scenix] Scenix.' + fn, e); return null; }
  }

  load(target, opts) {
    const payload = {
      url: target.url || apiUrl(target.proxiedUrl || ''),
      headers: target.headers || {},
      positionMs: opts.positionMs || 0,
      durationMs: opts.durationMs || target.durationMs || 0,
      title: opts.title || target.title || '',
      audio: opts.audio || undefined,
      sub: opts.sub || undefined,
      subs: (opts.subs || []).map((s) => ({ url: s.url, language: s.language, label: s.label || s.title })),
      type: opts.type || 'movie',
      season: opts.season,
      episode: opts.episode,
      // Which audio and subtitle languages this household asks for, in its own order. Without them the
      // shell's track selector falls back to the Android locale, so a Hindi-first viewer on an English
      // menu system gets whatever the file's packager happened to flag as default.
      audioLanguages: ((effectivePrefs().audioLanguages) || [])
        .join(','),
      subtitleLanguages: ((effectivePrefs().subtitleLanguages) || [])
        .join(','),
      torrent: target.torrent || undefined,
      kind: target.kind || undefined,
    };
    const json = JSON.stringify(payload);
    this.last = { positionMs: payload.positionMs, bufferedMs: 0, durationMs: payload.durationMs, playing: true };
    // The shell may expose this as play(json) or play(object); accept either without assuming.
    const r = this.call('play', json);
    if (r === null && typeof this.s.play !== 'function') {
      this.emit('error', { code: 'no-native-player', message: 'The TV player is not responding' });
    }
  }

  play() { this.call('resume'); this.call('play'); }
  pause() { this.call('pause'); }
  seek(ms) { this.call('seek', Math.max(0, Math.round(ms))); this.last.positionMs = ms; }
  stop() { this.call('stop'); }
  setRate(x) { this.call('setSpeed', x); }
  /**
   * The shell's real track switch is `select(trackId)` with the container's own stream index — `a:21`,
   * `s:01`, or `off` — measured on the BRAVIA. `setAudio`/`setSub` are kept as a fallback for an older
   * shell, but a track id from the hub or from scenix:tracks goes to `select` first, or nothing changes.
   */
  setAudio(id) {
    if (!id) return false;
    if (this.s && typeof this.s.select === 'function') return this.call('select', String(id)) !== null;
    return this.call('setAudio', id) !== null;
  }
  setSub(id) {
    const track = (id === null || id === undefined) ? 'off' : String(id);
    if (this.s && typeof this.s.select === 'function') return this.call('select', track) !== null;
    return this.call('setSub', id) !== null;
  }
  /** Caps an adaptive ladder only; a progressive copy needs a fresh /app/v1/play (js/screens/player.js). */
  setQuality(height) { return this.call('setQuality', height) !== null; }
  /** Keeps native captions out from under our own control bar. */
  setSubMargin(px) { return this.call('setSubMarginBottom', px) !== null; }

  state() {
    const raw = this.call('state');
    if (raw && typeof raw === 'object') { this.merge(raw); return { ...this.last }; }
    if (typeof raw === 'string' && raw) {
      try { this.merge(JSON.parse(raw)); } catch (e) { /* keep the last known state */ }
    }
    return { ...this.last };
  }

  dispose() {
    clearInterval(this.poll);
    for (const [name, h] of this.bound) document.removeEventListener(name, h);
    this.bound = [];
  }
}

/* ----------------------------------------------------------- VideoBackend */

class VideoBackend {
  constructor(emit) {
    this.emit = emit;
    // js/video.js owns the product's only two <video> elements; the film one is created there and
    // adopted here so the count can never drift to three.
    const made = mainSurface();
    this.host = made.div.parentNode;
    this.div = made.div;
    this.video = made.video;
    // Where the response we are reading starts inside the title, and what the title's length is. A stream
    // the hub is still generating gives the element neither, so these come from us. See load().
    this.baseMs = 0;
    this.durationMs = 0;
    this.srcUrl = '';
    this.baseUrl = '';
    this.rewindable = false;
    this.everDecoded = false;
    this.video.muted = false;

    const v = this.video;
    v.addEventListener('waiting', () => emit('buffering', { buffering: true }));
    v.addEventListener('stalled', () => emit('buffering', { buffering: true, stalled: true }));
    v.addEventListener('canplay', () => emit('buffering', { buffering: false }));
    v.addEventListener('playing', () => { this.everDecoded = true; emit('buffering', { buffering: false }); });
    v.addEventListener('ended', () => emit('ended', { positionMs: v.currentTime * 1000, durationMs: v.duration * 1000 }));
    v.addEventListener('error', () => {
      const e = v.error || {};
      // A remux answer that died before its first frame is almost always the hub saying it has no video
      // for us yet. The element sees an unusable body either way and cannot read the hub's reason, so that
      // guess lives here: the two need different words, because one asks the viewer to wait and re-ask,
      // while only a copy that failed after it started gets sent away in search of another.
      // A copy the hub is still warming costs us the whole first-byte window before the element gives up,
      // while one it refuses outright comes back in under a second. Timing is what tells those apart from
      // the bare code, so patience is only offered when the wait really happened.
      const waited = Date.now() - (this.srcAt || 0);
      // The hub says how long it waits for a first byte; anything that failed after that window was refused
      // at the end of the wait, and anything that failed sooner never survived the wait at all.
      const warmingMs = Number((session.manifest && session.manifest.remux
        && session.manifest.remux.warmingMs) || 25000);
      const waiting = this.rewindable && !this.everDecoded && (e.code === 4 || e.code === 2)
        && waited >= Math.max(2000, warmingMs - 1500);
      const why = waiting ? 'Nothing arrived from this copy yet'
        : e.code === 4 ? 'This browser cannot open this file'
        : e.code === 3 ? 'The stream broke while decoding'
        : e.code === 2 ? 'The hub stopped sending data'
        : 'The stream could not be opened';
      emit('error', { code: String(e.code || ''), message: why, waiting });
    });
    v.addEventListener('loadedmetadata', () => emit('ready', {
      durationMs: (v.duration || 0) * 1000, videoWidth: v.videoWidth, videoHeight: v.videoHeight,
    }));
    this.tick = setInterval(() => {
      if (!v.isConnected) return;
      emit('position', this.state());
    }, 500);
    this.tracksUnsub = null;
  }

  get name() { return 'video'; }
  /** A browser needs what Safari and Chrome can decode, so the hub re-muxes to MP4 for us. */
  get container() { return 'mp4'; }
  /** What this browser can draw, declared so the picker never offers a codec it would only buffer. */
  codecs() { return playableCodecs(); }

  /**
   * A re-muxed stream has no length to hand the browser - the hub is generating it as it goes - so the
   * element reports one seekable position, zero, and writing `currentTime` anywhere else snaps playback back
   * to the beginning. Measured in a real browser: pressing forward ten seconds at fourteen seconds in came
   * out at 1.3 seconds. So a position we cannot reach inside this response is reached by asking the hub for
   * a stream that starts there (`&start=`, which it honours with an input seek) and remembering that this
   * response begins partway into the title. Every position we report or aim at is then offset by that base.
   */
  load(target, opts) {
    const url = streamUrl(target);
    if (!url) { this.emit('error', { code: 'no-url', message: 'The hub gave no stream address' }); return; }
    const v = this.video;
    // The hub knows how long the title is; a growing guess from the element is worse than that number.
    this.durationMs = Number(target.durationMs) || 0;
    this.baseMs = 0;
    const at = Math.max(0, Number(opts.positionMs) || 0);
    this.baseUrl = url; // the address with no offset written onto it yet
    this.srcUrl = url;  // by default this response starts at the beginning of the title
    this.srcAt = Date.now();
    this.rewindable = /\/app\/v1\/remux([?]|$)/.test(url); // only this endpoint takes a start time
    if (at > 1500 && this.rewindable) {
      this.srcUrl = this.at(this.baseUrl, at);
      this.baseMs = at;
    } else if (at > 1) {
      const seekOnce = () => {
        try { if (this.localTo(Math.round(at)) != null) v.currentTime = this.localTo(at); } catch (e) { /* before metadata */ }
        v.removeEventListener('loadedmetadata', seekOnce);
      };
      v.addEventListener('loadedmetadata', seekOnce);
    }
    v.src = this.srcUrl;
    v.load();
    const p = v.play();
    if (p && typeof p.catch === 'function') {
      p.catch((err) => {
        // Autoplay with sound is blocked until a real interaction; starting muted is the honest fix.
        if (err && (err.name === 'NotAllowedError' || /play/i.test(String(err.message || '')))) {
          v.muted = true;
          const again = v.play();
          if (again && again.catch) again.catch(() => this.emit('error', { code: 'autoplay', message: 'Press play to start' }));
          this.emit('muted-autoplay', {});
        }
      });
    }
  }

  play() { const p = this.video.play(); if (p && p.catch) p.catch(() => {}); }
  pause() { this.video.pause(); }
  /**
   * Ask the same address again. A cold copy is not a broken one: what the hub said was "the swarm has not
   * given me this file yet", and the honest answer is to wait a moment and re-ask where we left off rather
   * than lose the seat and go hunting for another copy.
   */
  retry() {
    if (!this.srcUrl) return;
    this.everDecoded = false;
    this.srcAt = Date.now();
    const v = this.video;
    try { v.src = this.srcUrl; v.load(); } catch (e) { /* a detached element cannot be reloaded */ }
    const p = v.play();
    if (p && p.catch) p.catch(() => {});
  }
  /** This response's address, with the point inside the title it starts at written onto it. */
  at(base, ms) { return base + (base.includes('?') ? '&' : '?') + 'start=' + Math.round(ms); }
  /** Where a title position sits inside the response we are currently reading. */
  localTo(ms) { return (ms - this.baseMs) / 1000; }
  /** Is that spot already ours to play without asking the hub again? */
  reachable(ms) {
    const v = this.video;
    const want = this.localTo(ms);
    if (want < 0) return false;
    const inRanges = (ranges) => {
      try {
        for (let i = 0; i < ranges.length; i++) if (want >= ranges.start(i) - 1 && want <= ranges.end(i)) return true;
      } catch (e) { /* no ranges at all */ }
      return false;
    };
    return inRanges(v.buffered) || (v.seekable.length > 0 && v.seekable.end(v.seekable.length - 1) > 1 && inRanges(v.seekable));
  }
  seek(ms) {
    const at = Math.max(0, Math.round(ms));
    if (!this.rewindable || this.reachable(at)) {
      try { this.video.currentTime = this.localTo(at); return; } catch (e) { /* fall through to a restart */ }
    }
    if (!this.baseUrl) return;
    // Restart the stream at that point. The picture keeps playing while the hub opens the new one.
    this.baseMs = at;
    const wasPlaying = !this.video.paused;
    // The loaded address moves with the offset, so a later retry lands back here instead of at the start.
    this.srcUrl = this.at(this.baseUrl, at);
    this.video.src = this.srcUrl;
    try { this.video.load(); } catch (e) { /* ignore */ }
    if (wasPlaying) { const p = this.video.play(); if (p && p.catch) p.catch(() => {}); }
  }
  stop() {
    const v = this.video;
    try { v.pause(); } catch (e) { /* ignore */ }
    v.removeAttribute('src');
    try { v.load(); } catch (e) { /* ignore */ }
  }
  setRate(x) { try { this.video.playbackRate = x; } catch (e) { /* ignore */ } }
  setAudio(id) {
    // Chrome exposes no audioTracks object; the hub does the switching for us instead.
    const list = this.video.audioTracks;
    if (!list || !list.length) return false;
    for (let i = 0; i < list.length; i++) {
      if (String(list[i].id) === String(id)) { list[i].enabled = true; return true; }
    }
    return false;
  }
  setSub(id) {
    const list = this.video.textTracks;
    if (!list) return false;
    let found = false;
    for (let i = 0; i < list.length; i++) {
      const on = id !== null && String(list[i].trackId) === String(id);
      list[i].mode = on ? 'showing' : 'hidden';
      if (on) found = true;
    }
    return id === null || found;
  }

  /** Subtitles the hub handed us as files (SRT or VTT over http) become real <track> elements. */
  addTextTracks(subs) {
    for (const s of subs) {
      const t = document.createElement('track');
      t.kind = 'subtitles';
      t.srclang = s.language || 'und';
      t.label = s.label || s.title || s.language || 'Subtitles';
      t.trackId = s.id;
      t.src = s.src;
      t.mode = 'hidden';
      this.video.appendChild(t);
    }
  }

  state() {
    const v = this.video;
    let buffered = 0;
    try {
      for (let i = 0; i < v.buffered.length; i++) buffered = Math.max(buffered, v.buffered.end(i));
    } catch (e) { /* ignore */ }
    const real = (sec) => Math.round((sec || 0) * 1000) + this.baseMs;
    return {
      positionMs: real(v.currentTime),
      bufferedMs: real(buffered), // an absolute title position, same as the native backend reports
      durationMs: this.durationMs || ((v.duration && isFinite(v.duration) ? v.duration : 0) * 1000),
      playing: !v.paused && !v.ended,
      videoWidth: v.videoWidth, videoHeight: v.videoHeight,
    };
  }

  dispose() {
    clearInterval(this.tick);
    this.stop();
    if (this.div.parentNode) this.div.parentNode.removeChild(this.div);
  }
}

/* -------------------------------------------------------------- controller */

export function backendAvailable() { return !!shell(); }
export function backendName() { return shell() ? 'native' : 'video'; }

export function createPlayer() {
  const bus = emitter();
  const backend = shell() ? new NativeBackend(bus.emit) : new VideoBackend(bus.emit);

  const c = {
    backend: backend.name,
    container: backend.container,
    target: null,
    audioTracks: [],
    textTracks: [],
    selectedAudio: null,
    selectedSub: null,
    rate: 1,
    tried: [],
    session: null,          // {identity, profile, type, season, episode, title}
    ended: false,

    on: bus.on,

    /* ---- negotiation: the hub picks (or re-picks) a copy for this backend ---- */

    async start(spec) {
      c.session = spec;
      c.tried = [];
      return c._negotiate(api.play, spec);
    },

    /**
     * "This copy is buffering — give me another one", or "give me that one instead".
     * `source` is the copy from the hub's own list (id + label + provider: the hub matches a copy by its
     * label, so all three go back); `null` means let the hub rank them again.
     */
    async switchTo({ source = null, exclude = [], reprobe } = {}) {
      if (!c.session) return null;
      return c._negotiate(api.switchSource, Object.assign({}, c.session, {
        source: source ? { id: source.id, label: source.label, provider: source.provider } : null,
        exclude: exclude.length ? exclude : c.tried,
        reprobe: reprobe === undefined ? undefined : reprobe,
      }));
    },

    async _negotiate(fn, spec) {
      const body = {
        identity: spec.identity,
        profile: spec.profile,
        type: spec.type,
        season: spec.season,
        episode: spec.episode,
        exclude: spec.exclude,
        source: spec.source ? { id: spec.source.id, label: spec.source.label, provider: spec.source.provider } : spec.source || null,
        // The one field that says which container this client can play.
        container: backend.container,
        // ...and which codecs inside any container it can decode. A capability, not a preference.
        codecs: typeof backend.codecs === 'function' ? backend.codecs() : [],
        audio: spec.audio || c.selectedAudio || undefined,
        sub: spec.sub !== undefined ? spec.sub : c.selectedSub || undefined,
      };
      for (const k of ['exclude', 'audio', 'sub', 'type', 'season', 'episode', 'source']) {
        if (body[k] === undefined || body[k] === null || body[k] === '') delete body[k];
      }
      bus.emit('negotiating', { identity: spec.identity });
      let target;
      try {
        target = await fn(body);
      } catch (e) {
        const tried = (e.body && e.body.tried) || [];
        bus.emit('error', {
          code: String(e.status || 'net'),
          message: e.message || 'The hub could not answer',
          tried,
        });
        return null;
      }
      if (!target || !target.url) {
        bus.emit('error', {
          code: 'no-source',
          message: 'Nothing on this hub could be opened for this title',
          tried: (target && target.tried) || [],
        });
        return null;
      }
      c.target = target;
      if (target.picked && target.picked.id) c.tried.push(target.picked.id);
      if (target.picked && target.picked.label) c.tried.push(target.picked.label);

      // The hub echoes the tracks it found in the file. Older builds do not, and then we say so honestly
      // rather than showing an empty menu.
      const a = normAudio(target.audioTracks);
      const t = normText(target.subtitleTracks);
      if (a.length) c.audioTracks = a;
      if (t.length) c.textTracks = t;
      c.selectedAudio = target.selectedAudio || c.selectedAudio;
      c.selectedSub = target.selectedSub !== undefined ? target.selectedSub : c.selectedSub;
      bus.emit('tracks', { audio: c.audioTracks, text: c.textTracks, from: 'hub' });

      const pos = spec.positionMs !== undefined ? spec.positionMs
        : (c.state().positionMs || 0);
      const subsForBackend = (c.hubSubs || []).filter((s) => s.src).map((s) => ({
        url: s.src, language: s.language, label: s.label, id: s.id,
      }));
      backend.load(target, {
        positionMs: pos,
        title: target.title || spec.title || '',
        type: spec.type || 'movie',
        season: spec.season, episode: spec.episode,
        audio: c.selectedAudio, sub: c.selectedSub,
        subs: subsForBackend,
      });
      if (c.rate !== 1) backend.setRate(c.rate);
      bus.emit('target', target);
      return target;
    },

    /* ---- the control surface ---- */

    play() { if (!c.ended) backend.play(); },
    pause() { backend.pause(); },
    /** Re-ask for the copy we already chose, at the point we reached. For a cold copy, not a new one. */
    retry() {
      if (backend.retry) backend.retry();
      else if (c.target) backend.load(c.target, { positionMs: c.state().positionMs });
      // The screen owns its own failure card; it clears when the re-ask starts producing anything.
      bus.emit('retrying', {});
    },
    toggle() {
      const st = c.state();
      if (st.playing) backend.pause(); else backend.play();
      bus.emit('position', st);
    },
    seek(ms) { backend.seek(Math.max(0, ms)); bus.emit('position', Object.assign(c.state(), { positionMs: ms })); },
    seekBy(deltaMs) {
      const st = c.state();
      const to = Math.max(0, Math.min(st.durationMs || Infinity, st.positionMs + deltaMs));
      c.seek(to);
    },
    setSpeed(x) { c.rate = x; backend.setRate(x); bus.emit('speed', x); },
    setAudio(id) {
      const ok = backend.setAudio(id);
      c.selectedAudio = id;
      if (!ok && backend.name === 'video') {
        // The browser cannot switch this file's audio, so the hub re-opens the copy with the track chosen.
        return c._negotiate(api.switchSource, Object.assign({}, c.session, { audio: id, positionMs: c.state().positionMs }));
      }
      bus.emit('audio', id);
      return Promise.resolve(c.target);
    },
    setSub(id) {
      const use = id === 'off' ? null : id;
      const ok = backend.setSub(use);
      c.selectedSub = use;
      if (!ok && use && backend.name === 'video') {
        return c._negotiate(api.switchSource, Object.assign({}, c.session, { sub: use, positionMs: c.state().positionMs }));
      }
      bus.emit('sub', use);
      return Promise.resolve(c.target);
    },
    /** 'auto' lets the hub rank every copy again; anything else names one copy from its own list. */
    setQuality(sourceIdOrAuto) {
      if (sourceIdOrAuto === 'auto' || !sourceIdOrAuto) {
        return c.switchTo({ source: null, exclude: c.tried.filter((x) => x !== (c.target && c.target.picked && c.target.picked.id)) });
      }
      const src = (c.sources || []).find((s) => String(s.id) === String(sourceIdOrAuto));
      if (!src) return Promise.reject(new Error('unknown source ' + sourceIdOrAuto));
      return c.switchTo({ source: src, exclude: c.tried.filter((x) => x !== src.id && x !== src.label) });
    },

    /** Subtitle files from /app/v1/subs, fetched and converted for the browser path. */
    useHubSubs(list) {
      c.hubSubs = list || [];
      if (backend.name === 'video' && typeof backend.addTextTracks === 'function') {
        backend.addTextTracks(c.hubSubs.filter((s) => s.src));
        bus.emit('tracks', { audio: c.audioTracks, text: c.textTracks.concat(c.hubSubs), from: 'addon' });
      }
    },

    state() { return backend.state(); },
    tracks() {
      return {
        audio: c.audioTracks,
        text: c.textTracks.concat((c.hubSubs || []).map((s) => ({
          id: s.id, language: s.language, title: s.label || s.title || '', url: s.url, kind: 'addon',
        }))),
      };
    },
    sources(list) { c.sources = Array.isArray(list) ? list : []; return c.sources; },

    /** Adaptive-only: cap the shell's ladder. A progressive copy is changed with setQuality(id) instead. */
    capAdaptive(height) { return typeof backend.setQuality === 'function' ? backend.setQuality(height) : false; },
    /** Tell the native caption renderer how tall our control bar currently is. */
    subMargin(px) { return typeof backend.setSubMargin === 'function' ? backend.setSubMargin(px) : false; },

    stop() { c.ended = false; backend.stop(); },
    dispose() { backend.stop(); if (backend.dispose) backend.dispose(); },
  };

  bus.on('ended', () => { c.ended = true; });
  return c;
}

/**
 * One honest line about what is happening to the picture. A slow stream is not an error, and a viewer
 * who is told which of the two they have stops blaming the television.
 */
export function describePlayback(player, extra = {}) {
  const t = player.target || {};
  const st = extra.state || player.state();
  const seeders = extra.seeders !== undefined ? extra.seeders
    : (t.torrent && t.torrent.seeders !== undefined ? t.torrent.seeders : null);

  if (extra.error) return { tone: 'bad', text: extra.error };

  if (st.durationMs && st.videoWidth && st.videoHeight && extra.stats) {
    return {
      tone: 'info',
      text: `${st.videoWidth}x${st.videoHeight} · ${player.backend === 'native' ? 'TV player' : 'browser player'}`
        + (t.mode ? ` · ${t.mode}` : '')
        + (seeders !== null ? ` · ${seeders} sharing` : ''),
    };
  }

  if (extra.buffering) {
    if (seeders === 0) return { tone: 'warn', text: 'Waiting for someone to share this copy — 0 people have it right now' };
    if (seeders !== null) return { tone: 'warn', text: `Buffering slowly — ${seeders} ${seeders === 1 ? 'person' : 'people'} sharing this copy` };
    return { tone: 'warn', text: 'Buffering' };
  }

  if (t.slow && t.neededMbps && t.measuredMbps !== undefined) {
    return {
      tone: 'warn',
      text: `This link is delivering ${t.measuredMbps} Mbps and the file needs ${t.neededMbps} — another copy may play smoother`,
    };
  }
  if (t.status === 'slow' || t.status === 'warming') {
    return { tone: 'warn', text: 'The hub is still warming this copy — the first minute may stutter' };
  }
  if (t.kind === 'hls' && player.backend === 'video') {
    return { tone: 'warn', text: 'This copy is a live HLS feed: the TV plays it, a plain browser may not' };
  }
  if (player.backend === 'video' && t.videoCodec && !canPlayVideo(t.videoCodec)) {
    return { tone: 'warn', text: 'This copy is ' + String(codecFamily(t.videoCodec)).toUpperCase()
      + ', which this browser cannot draw — the TV app plays it' };
  }
  // Warned only when the element really receives Matroska. A gateway copy arrives as fragmented MP4
  // because the hub re-wrapped it, and warning about a container nobody ever handed the decoder turned a
  // play that was working into one that looked like it might not be.
  if (t.probe && t.probe.contentType === 'video/x-matroska' && player.backend === 'video' && !t.remuxed) {
    return { tone: 'warn', text: 'This copy is Matroska — it can stutter in a browser until the hub finishes re-muxing' };
  }
  if (t.negotiating || extra.negotiating) {
    return { tone: 'info', text: 'The hub is measuring your copies and picking the one that will play' };
  }
  if (t.warnings && t.warnings.length) return { tone: 'warn', text: t.warnings[0] };
  return null;
}
