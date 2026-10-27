'use strict';
/*
 * core/readahead.js — the hub reads ahead of the player, so the player never waits on the swarm.
 *
 * Why this exists, in the hub's own words from a night of unwatchable films:
 *
 *   PLAY RATE IGNORED imdb:tt34890576 kbps=8389 reason=rateFloor
 *   REMUX START … file=1 audio=1 sub=none start=0ms range=0- plans=copy
 *   REMUX END id=… phase=body cause=stall elapsed=50029ms bytes=171504634
 *
 * 171 MB in 50 s reads like 27 Mbps and played like nothing at all: the first few seconds came out of
 * the engine's cache in a burst, and then the peers sent close to zero for the next 45, which tripped
 * the remux stall timer. A hub that pipes the engine straight to the screen passes every one of those
 * gaps through as a frozen picture. A paid caching service does not have this problem because the file
 * already sits on a fast server — it wins on *buffering*, not on magic. The free version of the same
 * effect is RAM in front of the player: pull from the peers as fast as they will push, hold a rolling
 * window of it, and let the screen read at LAN speed while the torrent bursts and pauses.
 *
 * One buffer per copy (`hash:fileId`), shared by everything that reads that copy:
 *
 *   · the native TV player's /app/v1/proxy request,
 *   · the browser's /app/v1/remux, whose ffmpeg reads the hub's own proxy on loopback (`wrapInput`),
 *   · the Play press itself, which reads a window here to learn what the copy really sustains.
 *
 * Those are the same bytes, not three downloads: the read a Play press makes to measure a copy IS the
 * pre-roll the film starts with. That is this module's second job, and the one the first log line above
 * was missing. `reason=rateFloor` means the only number on hand was "256 KB arrived in under 250 ms",
 * which proves the engine had the head of the file cached and proves nothing about the swarm. So the
 * Play press now reads continuously for `playback.sustainBudgetMs` and reports the WORST window of
 * `playback.rateWindowMs` inside it, not the average: playback dies in the worst window. On the night
 * above that measurement reads ~680 Mbps for the first window and ~0 for the second, and the copy that
 * "passed" a 256 KB probe is rejected before anyone presses play on it again.
 *
 * Bound, because RAM is not free and a reader that never stops is worse than a stall:
 *   · at most `playback.readAheadMB` bytes and at most `playback.readAheadSec` seconds of media,
 *     whichever is smaller — the seconds bound is what stops a 25 Mbps copy's cushion eating 300 MB;
 *   · at most `playback.readAheadCopies` copies held at once, least-recently-used out;
 *   · released `playback.readAheadIdleMs` after the last reader hangs up.
 * Past the bound the oldest bytes are dropped, and a player that wants one of them is seeking — which
 * costs one ranged request to the engine, exactly what it cost before this file existed.
 *
 * Every number comes from settings (core/settings.js DEFAULTS, editable in the Hub Settings screen) or
 * the environment at install time. No household-specific constant lives in this file.
 *
 * No npm: http from Node. This module does not throw into a request path — a buffer that cannot be
 * filled behaves like the pipe it replaced, only with the failure logged in words.
 */

const http = require('http');
const { URL } = require('url');

const settings = require('./settings');

const log = (...a) => console.log(new Date().toISOString(), ...a);

/** Where this hub answers HTTP on its own box; the router sets it at listen so ffmpeg can read through it. */
let SELF_ORIGIN = '';
function setSelfOrigin(origin) { SELF_ORIGIN = String(origin || '').replace(/\/+$/, ''); }

function clampNum(raw, dflt, lo, hi) {
  const n = Number(raw);
  return Number.isFinite(n) ? Math.min(hi, Math.max(lo, n)) : dflt;
}

const MB = 1048576;

/** Read fresh on every use: a PUT to /app/v1/settings takes effect on the next byte streamed. */
function cfg() {
  const p = settings.playback();
  return {
    on: p.readAhead !== false,
    maxBytes: clampNum(p.readAheadMB, 96, 8, 4096) * MB,
    maxSec: clampNum(p.readAheadSec, 90, 5, 1800),
    copies: Math.round(clampNum(p.readAheadCopies, 2, 1, 12)),
    idleMs: clampNum(p.readAheadIdleMs, 45000, 3000, 600000),
    // The pre-roll: how much picture exists before the first frame is promised to a screen.
    preSec: clampNum(p.preBufferSec, 15, 0, 600),
    preMB: clampNum(p.preBufferMB, 32, 1, 2048) * MB,
    preMs: clampNum(p.preBufferMs, 6000, 0, 30000),
    // The honest rate sample a Play press takes before it promises a copy to anyone.
    sustainMs: clampNum(p.sustainBudgetMs, 4000, 500, 30000),
    windowMs: clampNum(p.rateWindowMs, 2000, 250, 60000),
    // How many slow windows in a row, and how thin the cushion has to be, before the hub calls a copy
    // starving and lets the player move to a smaller one without asking the viewer.
    starvedWindows: Math.round(clampNum(p.starvedWindows, 2, 1, 20)),
    starvedFloor: clampNum(p.starvedFloor, 1, 0.2, 3),
    graceSec: clampNum(p.starvedGraceSec, 20, 0, 600),
    holdMs: clampNum(p.starvedHoldMs, 15 * 60e3, 10e3, 24 * 3600e3),
  };
}

/** The byte rate the copy's own bitrate needs; null until someone tells us its bitrate. */
function needBps(needMbps) {
  const m = Number(needMbps);
  return Number.isFinite(m) && m > 0 ? (m * 1e6) / 8 : null;
}

/* ------------------------------------------------------------------ one copy, one buffer */

/**
 * One torrent file, one sequential reader, one bounded window of RAM.
 *
 * The window is [base, pos): `base` is the first byte still held, `pos` the first byte not yet fetched.
 * Consumers — an HTTP response to a screen, or ffmpeg's input — read out of that window at their own
 * pace. A consumer that wants a byte outside it is seeking: the reader is torn up and reopened there,
 * which costs one ranged request, the same price a seek cost before this module existed.
 */
class Copy {
  constructor(key, url, hash, fileId) {
    this.key = key;
    this.url = url;          // the engine's own loopback address for this file
    this.hash = hash;
    this.fileId = fileId;
    this.chunks = [];        // [{start, buf}] ascending and contiguous
    this.base = 0;           // first byte held
    this.pos = 0;            // first byte not yet fetched
    this.cursor = 0;         // next byte the engine has handed us
    this.fileSize = null;
    this.req = null;         // live engine request
    this.up = null;          // live engine response (the thing we pause and resume)
    this.resHeaders = null;  // what the engine answered the current reader with, for pass-through
    this.readerAt = null;    // the offset the current reader asked for
    this.paused = false;
    this.eof = false;
    this.error = null;
    this.everBytes = false; // has this copy ever put a byte on the wire for us
    this.anchor = 0;         // what the window is anchored to while nobody is reading (a pre-roll)
    this.sliding = false;    // measurement mode: drop behind the reader instead of pausing it
    this.readers = new Set(); // live consumers, each {off}
    this.lastUsed = Date.now();
    this.openedAt = Date.now();
    this.ttfbMs = null;
    this.needMbps = null;
    this.win = null;         // the sample window currently open
    this.windows = [];       // closed windows, oldest first
    this.slowRun = 0;        // consecutive valid windows under this copy's own bitrate
    this.starved = false;
    this.starvedAt = 0;
    this.seeks = 0;
    this.listeners = new Set();
  }

  tag() { return 'hash=' + String(this.hash).slice(0, 12) + ' file=' + this.fileId; }
  held() { return Math.max(0, this.pos - this.base); }

  /** The cushion this copy is allowed to occupy: bytes, or seconds of ITS OWN bitrate, whichever is less. */
  bound(c) {
    const bps = needBps(this.needMbps);
    const bySec = bps ? bps * c.maxSec : Infinity;
    return Math.max(2 * MB, Math.min(c.maxBytes, bySec));
  }

  /** The lowest offset anyone is still reading; the pre-roll anchor when nobody is. */
  front() {
    let f = null;
    for (const r of this.readers) if (f === null || r.off < f) f = r.off;
    return f === null ? this.anchor : f;
  }

  bufferedSec() {
    const bps = needBps(this.needMbps);
    return bps ? this.held() / bps : null;
  }

  /** Throw the window away and start the sequential read again at `at`. */
  seekTo(at) {
    this.seeks++;
    this.closeReader();
    this.chunks = [];
    this.base = Math.max(0, at);
    this.pos = this.base;
    this.cursor = this.base;
    this.eof = false;
    this.error = null;
    this.openReader(this.base);
  }

  /** Start a sequential read at `at` if there is not one running and the file is not finished. */
  ensureReader(at) {
    if (this.req || this.eof) return;
    const want = Math.max(0, at);
    if (Number.isFinite(this.fileSize) && this.fileSize > 0 && want >= this.fileSize) { this.eof = true; return; }
    if (want !== this.pos) { this.seekTo(want); return; }
    this.openReader(want);
  }

  openReader(at) {
    this.closeReader();
    this.cursor = at;
    this.readerAt = at;
    if (!this.readers.size) this.anchor = this.sliding ? at : at;
    let u;
    try { u = new URL(this.url); } catch (e) { this.error = 'bad engine url'; return; }
    const req = http.get(u, { headers: { Range: 'bytes=' + at + '-' }, agent: false, timeout: 120000 }, (got) => {
      const cr = got.headers && got.headers['content-range'];
      if (cr) { const m = /\/(\d+)\s*$/.exec(String(cr)); if (m) this.fileSize = Number(m[1]); }
      if (got.statusCode === 416 || got.statusCode >= 400) {
        this.error = got.statusCode === 416 ? 'past end of file' : 'engine HTTP ' + got.statusCode;
        this.eof = this.eof || got.statusCode === 416;
        got.resume();
        this.closeReader();
        this.wake();
        return;
      }
      this.up = got;
      this.resHeaders = { status: got.statusCode || 200, headers: got.headers };
      if (this.ttfbMs === null) {
        this.ttfbMs = Date.now() - this.openedAt;
        log('READAHEAD TTFB', this.tag(), 'ms=' + this.ttfbMs, 'range=' + at + '-',
          'status=' + (got.statusCode || 200));
      }
      this.newWindow();
      got.on('data', (b) => this.append(b));
      got.on('end', () => { this.eof = true; this.closeReader(); this.wake(); });
      got.on('error', () => { this.closeReader(); this.wake(); });
      got.on('close', () => { if (this.up === got) { this.up = null; this.closeReader(); this.wake(); } });
    });
    req.on('timeout', () => { this.error = 'engine read timeout'; req.destroy(); });
    req.on('error', (e) => {
      if (!this.error) this.error = e.message || 'engine read failed';
      this.closeReader();
      this.wake();
    });
    this.req = req;
    this.newWindow();
  }

  closeReader() {
    const up = this.up; this.up = null;
    if (up) { try { up.destroy(); } catch (e) { /* already gone */ } }
    const rq = this.req; this.req = null;
    if (rq) { try { rq.destroy(); } catch (e) { /* already gone */ } }
    this.paused = false;
  }

  append(buf) {
    if (!buf || !buf.length) return;
    this.everBytes = true;
    if (this.ttfbMs === null) this.ttfbMs = Date.now() - this.openedAt;
    this.chunks.push({ start: this.cursor, buf });
    this.cursor += buf.length;
    this.pos = this.cursor;
    if (!this.win) this.newWindow();
    this.win.bytes += buf.length;
    this.lastUsed = Date.now();
    this.trim(cfg());
    this.wake();
  }

  /* ------------------------------------------------------------ rate measurement */

  newWindow() { this.win = { at: Date.now(), bytes: 0, capped: false }; }

  /**
   * Close the open window once it has run its length. Driven by the module tick, so a swarm that sends
   * nothing at all still scores a window — that is the case worth catching.
   */
  closeWindow(c) {
    if (!this.win) return;
    const ms = Date.now() - this.win.at;
    if (ms < c.windowMs) return;
    const mbps = (this.win.bytes * 8) / 1e6 / (ms / 1000);
    // A window in which we stopped asking for bytes because our own buffer was full says nothing about
    // the swarm; scoring it as 0 Mbps would downgrade a copy that is genuinely cached.
    this.windows.push({ at: Date.now(), ms, bytes: this.win.bytes, mbps, valid: !this.win.capped });
    if (this.windows.length > 12) this.windows.shift();
    const need = Number(this.needMbps);
    // A copy that has never handed over a single byte is warming, not starving: the engine is still
    // handshaking with the swarm, and the reader we leave open is exactly what warms it. Starvation is
    // the other thing — bytes came, the picture was moving, and then they stopped carrying it.
    if (!this.win.capped && this.everBytes && Number.isFinite(need) && need > 0) {
      // The bar here is the copy's own bitrate, not the picker's headroom: a copy feeding exactly its
      // bitrate is watchable, and one under it is eating the cushion.
      // A copy that was once too slow and then started carrying the picture is not too slow any more.
      // Both flags have to come off — the one the picker consults and the one the player reads before it
      // spends its one silent heal — or a copy that recovered in two minutes stays exiled for fifteen and
      // the household watches a worse one out of a grudge.
      if (this.starved && mbps >= need * 1.2) {
        this.starved = false;
        this.slowRun = 0;
        slow.delete(String(this.hash));
        log('READAHEAD RECOVERED', this.tag(),
          'mbps=' + mbps.toFixed(1), 'need=' + need.toFixed(1),
          'was=' + (this.starvedAt ? Math.round((Date.now() - this.starvedAt) / 1000) + 's ago' : '?'));
      }
      if (mbps < need * c.starvedFloor) this.slowRun++; else this.slowRun = 0;
      if (this.slowRun >= c.starvedWindows) this.declareStarved(mbps);
    }
    this.newWindow();
    this.wake();
  }

  declareStarved(mbps) {
    if (this.starved) return;
    const c = cfg();
    const sec = this.bufferedSec();
    // A deep cushion and a slow window is a blip the buffer is there to absorb. The copy only counts as
    // starving once the cushion is thin enough that the picture is about to stop.
    if (sec !== null && sec > c.graceSec) return;
    this.starved = true;
    this.starvedAt = Date.now();
    slow.set(String(this.hash), {
      at: Date.now(),
      until: Date.now() + c.holdMs,
      mbps: Math.round(mbps * 100) / 100,
      needMbps: Math.round((Number(this.needMbps) || 0) * 100) / 100,
    });
    log('READAHEAD STARVED', this.tag(),
      'mbps=' + mbps.toFixed(1),
      'need=' + (Number.isFinite(this.needMbps) ? this.needMbps.toFixed(1) : '?'),
      'windows=' + this.slowRun,
      'buffered=' + (sec == null ? Math.round(this.held() / MB) + 'MB' : sec.toFixed(1) + 's'),
      'runway=' + this.runway());
  }

  /** Seconds until the cushion runs dry at the last measured rate. `clear` when the rate carries it. */
  runway() {
    const need = needBps(this.needMbps);
    if (!need) return '?';
    const s = this.lastValidWindow();
    if (!s) return '?';
    const got = (s.mbps * 1e6) / 8;
    if (got >= need) return 'clear';
    return (this.held() / Math.max(1, need - got)).toFixed(1) + 's';
  }

  lastValidWindow() {
    for (let i = this.windows.length - 1; i >= 0; i--) if (this.windows[i].valid) return this.windows[i];
    return null;
  }

  /** The worst valid window in the recent past — the number that predicts a frozen picture. */
  worstWindow(count) {
    let worst = null;
    for (const w of this.windows.slice(-Math.max(1, count || 1))) {
      if (w.valid && (worst === null || w.mbps < worst)) worst = w.mbps;
    }
    return worst;
  }

  find(offset) {
    for (let i = this.chunks.length - 1; i >= 0; i--) {
      const ch = this.chunks[i];
      if (offset >= ch.start && offset < ch.start + ch.buf.length) return ch;
    }
    return null;
  }

  /** Enforce the window: drop what nobody needs, and stop asking when the buffer is full. */
  trim(c) {
    // Behind the slowest reader is dead weight. While nobody is reading, the anchor is where the read
    // started — and the bytes ahead of the anchor are the pre-roll the film is about to be handed, so
    // they are kept and the reader is merely paused at the bound. A measurement that pauses because the
    // cushion is full scores a capped window, which is the honest report of a copy already sitting in
    // RAM, and is never mistaken for 0 Mbps.
    const low = this.readers.size ? this.front() : this.anchor;
    while (this.chunks.length && this.chunks[0].start + this.chunks[0].buf.length <= low) {
      this.base = this.chunks[0].start + this.chunks[0].buf.length;
      this.chunks.shift();
    }
    // Over the bound: drop from the front (the far side of a seek or the head of a measurement).
    let guard = 0;
    while (this.held() > this.bound(c) && this.chunks.length && guard++ < 256) {
      const ch = this.chunks[0];
      const excess = this.held() - this.bound(c);
      if (ch.buf.length > excess) {
        this.base = ch.start + excess;
        this.chunks[0] = { start: this.base, buf: ch.buf.subarray(excess) };
        break;
      }
      this.base = ch.start + ch.buf.length;
      this.chunks.shift();
    }
    const full = this.held() >= this.bound(c);
    if (full && this.up && !this.paused) {
      this.up.pause();
      this.paused = true;
      // A read we stopped ourselves cannot score a rate for the swarm.
      if (this.win) this.win.capped = true;
    } else if (!full && this.up && this.paused && this.held() < this.bound(c) * 0.7) {
      this.up.resume();
      this.paused = false;
    }
  }

  /** Resolve when `pred` holds, or after `ms`. Never rejects; a false answer means "still nothing". */
  waitFor(pred, ms) {
    return new Promise((resolve) => {
      let done = false;
      const finish = (v) => { if (!done) { done = true; clearTimeout(timer); this.listeners.delete(ent); resolve(v); } };
      let ent;
      try { if (pred()) return resolve(true); } catch (e) { return resolve(false); }
      ent = { pred: () => { const v = pred(); if (v) finish(true); return v; } };
      this.listeners.add(ent);
      const timer = setTimeout(() => finish(false), Math.max(0, ms));
      if (timer.unref) timer.unref();
    });
  }

  wake() {
    if (!this.listeners.size) return;
    for (const ent of Array.from(this.listeners)) {
      let ok = false;
      try { ok = ent.pred(); } catch (e) { ok = true; }
      if (ok) this.listeners.delete(ent);
    }
  }

  stats(c) {
    const w = this.lastValidWindow();
    const sec = this.bufferedSec();
    return {
      key: this.key,
      heldMB: Math.round((this.held() / MB) * 10) / 10,
      boundMB: Math.round(this.bound(c) / MB),
      bufferedSec: sec == null ? null : Math.round(sec),
      needMbps: Number.isFinite(this.needMbps) ? Math.round(this.needMbps * 100) / 100 : null,
      mbps: w == null ? null : Math.round(w.mbps * 100) / 100,
      runway: this.runway(),
      slowRun: this.slowRun,
      starved: this.starved,
      readers: this.readers.size,
      reading: !!this.req,
      seeks: this.seeks,
      ttfbMs: this.ttfbMs,
      windows: this.windows.slice(-4).map((x) => ({ ms: x.ms, mbps: Math.round(x.mbps * 10) / 10, valid: x.valid })),
    };
  }
}

/* ------------------------------------------------------------------ the registry of copies */

const copies = new Map(); // 'hash:fileId' -> Copy

/**
 * Copies this hub has *watched* fail to feed a player, and how long they stay out of favour. A paid
 * service does not need this bookkeeping; a free hub that re-offers the torrent that starved the viewer
 * two minutes later, as if nothing had happened, is the household's "it buffers again" experience.
 */
const slow = new Map(); // hash -> {until, mbps, needMbps}
function isSlow(hash) {
  const s = slow.get(String(hash || ''));
  if (!s) return null;
  if (s.until < Date.now()) { slow.delete(String(hash || '')); return null; }
  return s;
}
function clearSlow(hash) { slow.delete(String(hash || '')); }

/** The engine's own address for a copy — the only thing this hub reads a torrent through. */
function engineUrlOf(who) {
  const eng = String(process.env.TORR_URL || 'http://127.0.0.1:8090').replace(/\/+$/, '');
  return eng + '/play/' + who.hash + '/' + who.fileId;
}

function get(who) {
  if (!who || !who.hash || who.fileId == null) return null;
  return copies.get(String(who.hash) + ':' + String(who.fileId)) || null;
}

/** The buffer for this copy, creating it if needed. null when read-ahead is switched off. */
function acquire(who) {
  const c = cfg();
  if (!c.on || !who || !who.hash || who.fileId == null) return null;
  const key = String(who.hash) + ':' + String(who.fileId);
  let copy = copies.get(key);
  if (!copy) {
    copy = new Copy(key, engineUrlOf(who), String(who.hash), Number(who.fileId));
    copies.set(key, copy);
    log('READAHEAD OPEN', copy.tag(), 'boundMB=' + Math.round(copy.bound(c) / MB), 'maxSec=' + c.maxSec);
    if (copies.size > c.copies) evictOver(c);
  }
  copy.lastUsed = Date.now();
  return copy;
}

function evictOver(c) {
  const list = Array.from(copies.values()).sort((a, b) =>
    (a.readers.size ? 1 : 0) - (b.readers.size ? 1 : 0) || a.lastUsed - b.lastUsed);
  while (copies.size > c.copies && list.length) {
    const old = list.shift();
    if (old.readers.size) break; // everyone is watching something; let it be brief
    drop(old, 'over the copy limit');
  }
}

function drop(copy, why) {
  if (!copies.has(copy.key)) return;
  copies.delete(copy.key);
  copy.closeReader();
  log('READAHEAD DROP', copy.tag(), 'heldMB=' + Math.round(copy.held() / MB), 'reason=' + why);
}

/** Tell a buffer what bitrate its copy needs, so "slow" can mean something other than a guess. */
function noteNeed(who, needMbps) {
  const copy = get(who);
  if (!copy) return;
  const n = Number(needMbps);
  if (Number.isFinite(n) && n > 0) copy.needMbps = n;
}

/* ------------------------------------------------------------------ measuring a copy */

/**
 * Read a copy continuously and report the rate its worst window sustained.
 *
 * Returns `{mbps, real, cached, avgMbps, bytes, ms, bufferedMB, windows}`:
 *   · `real: true` — at least one full window closed, so `mbps` is a rate the copy actually held for that
 *     long. This is the number the picker may act on, and the number `reason=rateFloor` never produced.
 *   · `cached: true` — our own cushion is already full of this copy and the sample could not add to it.
 *     The honest answer is that this file is sitting on a fast server, which is the whole advantage a
 *     paid service sells, and it is worth far more than a 256 KB sample of the same thing.
 *   · Neither: nothing meaningful arrived in the budget, and the copy is not ready rather than fast.
 */
async function sustain(copy, opts = {}) {
  const c = cfg();
  const budgetMs = Math.max(200, Math.min(c.sustainMs, Number(opts.budgetMs) || c.sustainMs));
  const need = Number(opts.needMbps);
  if (Number.isFinite(need) && need > 0) copy.needMbps = need;
  const at = Number.isFinite(Number(opts.at)) ? Math.max(0, Number(opts.at)) : copy.base;
  const t0 = Date.now();
  copy.openedAt = t0;
  copy.windows = [];
  copy.slowRun = 0;
  copy.sliding = true;   // keep the reader running: pausing it mid-sample hides the swarm's real rate
  copy.anchor = at;
  const alreadyHeld = copy.held();
  // Bytes we already hold stay held: they were paid for a moment ago and the player is about to want them.
  // What this sample measures is what arrives from here on.
  if (copy.base <= at && (copy.pos === copy.base || at < copy.pos)) copy.ensureReader(copy.pos);
  else copy.seekTo(at);
  const startPos = copy.pos;
  const deadline = t0 + budgetMs;
  await copy.waitFor(() => Date.now() - t0 >= deadline || (!copy.req && !copy.up && copy.windows.length > 0),
    budgetMs + 80);
  // Close whatever window is still open so a sample taken over 4 s always reports both of its 2 s halves.
  if (copy.win && copy.win.bytes >= 0 && Date.now() - copy.win.at >= c.windowMs) copy.closeWindow(c);
  const ms = Math.max(1, Date.now() - t0);
  const bytes = Math.max(0, copy.pos - startPos);
  copy.sliding = false;
  copy.lastUsed = Date.now();
  let worst = null;
  let validWindows = 0;
  for (const w of copy.windows) if (w.valid) { validWindows++; if (worst === null || w.mbps < worst) worst = w.mbps; }
  const avg = (bytes * 8) / 1e6 / (ms / 1000);
  // One window is not a rate. The engine hands over its cached head at tens of MB/s and then goes quiet,
  // and a sample that only ever saw the head called a torrent with a dead swarm 14 Mbps — the picture
  // then froze 30 seconds in. A verdict has to be able to see the collapse, so it needs more than one
  // window of the thing it is judging.
  const real = worst !== null && validWindows >= 2;
  const cushion = Math.max(alreadyHeld, copy.held());
  return {
    mbps: real ? worst : avg,
    // Bytes arrived but the sample was too short to be a rate: unproven, not proven fast and not proven
    // slow. The picker must not spend its choice on it while anything else can be tested.
    short: worst !== null && validWindows < 2,
    avgMbps: Math.round(avg * 100) / 100,
    real,
    // The cushion is already full of this copy and the sample could not add to it: the honest answer is
    // that this file is sitting on a fast server, which is the whole thing a paid service sells.
    cached: !real && cushion > 0 && cushion >= Math.min(copy.bound(c), Math.max(c.preMB, 8 * MB)),
    bytes,
    ms,
    bufferedMB: Math.round((copy.held() / MB) * 10) / 10,
    windows: copy.windows.slice(-4).map((w) => ({
      ms: w.ms, mbps: Math.round(w.mbps * 10) / 10, valid: w.valid,
    })),
  };
}

/**
 * Keep pulling until the cushion holds this much picture, or the clock says start anyway.
 * Called after the copy is chosen, from the same buffer the measurement filled, so on a warm copy this
 * returns at once and the first frame costs the player nothing.
 */
async function preBuffer(copy, opts = {}) {
  const c = cfg();
  const wantSec = clampNum(opts.sec != null ? opts.sec : c.preSec, c.preSec, 0, 600);
  const wantBytes = clampNum(opts.bytes != null ? opts.bytes : c.preMB, c.preMB, MB, 4096 * MB);
  const budgetMs = clampNum(opts.budgetMs != null ? opts.budgetMs : c.preMs, c.preMs, 0, 30000);
  const target = Math.min(
    c.preMB,
    needBps(copy.needMbps) ? Math.max(0, c.preSec) * needBps(copy.needMbps) : Infinity,
    copy.bound(c),
  );
  const t0 = Date.now();
  copy.sliding = false;
  copy.ensureReader(copy.pos);
  const done = () => copy.held() >= target || copy.eof || (!copy.req && !copy.up);
  if (Number.isFinite(target) && !done()) await copy.waitFor(done, budgetMs);
  const sec = copy.bufferedSec();
  return {
    ms: Date.now() - t0,
    bytes: copy.held(),
    MB: Math.round((copy.held() / MB) * 10) / 10,
    sec: sec == null ? null : Math.round(sec),
    targetMB: Number.isFinite(target) ? Math.round(target / MB) : null,
    complete: !Number.isFinite(target) || copy.held() >= target,
  };
}

/* ------------------------------------------------------------------ serving a screen */

/**
 * The byte range this request asks for. Screens ask two ways: a `Range:` header (an ordinary player, a
 * seeking ffmpeg) or nothing at all (watch from the top). `start`/`end` seconds, which the app uses on
 * the remux route, mean nothing without the file's own length and are not used here.
 */
function rangeOf(req) {
  const m = /^bytes=(\d*)-(\d*)$/i.exec(String((req.headers || {}).range || ''));
  if (!m) return { begin: 0, end: null, ranged: false };
  let begin = 0;
  let end = null;
  if (m[1]) begin = Number(m[1]);
  if (m[2]) end = Number(m[2]);
  if (!m[1] && m[2]) return { begin: null, end: null, suffix: Number(m[2]), ranged: true };
  return { begin, end, ranged: true };
}

/**
 * How long a served consumer will wait for the buffer's reader to bring a byte it needs. Not a timeout on
 * playback — `serve` never cuts a body on a clock — just the ceiling on one wait before the loop re-checks
 * the engine's state and reports what stopped it.
 */
const BODY_WAIT_MS = 5 * 60e3;

/**
 * Serve one copy to one response out of the shared buffer, with the same shape as the pass-through proxy
 * it replaces: the engine's own status and range headers when they describe this range, 503 with the
 * hub's `warming` bargain when no bytes arrive in the header budget, and never a timeout on the body —
 * the whole point is that a quiet swarm is survived by the cushion instead of cut off.
 *
 * `opts.onReader` is called while a consumer is attached, so the gateway's keep-warm can tell that this
 * torrent is being watched and stop opening a competing reader at offset 0.
 */
async function serve(req, res, who, opts = {}) {
  const c = cfg();
  const copy = acquire(who);
  if (!copy) return false;
  if (Number.isFinite(Number(opts.needMbps)) && Number(opts.needMbps) > 0) copy.needMbps = Number(opts.needMbps);
  const headersMs = Math.max(1000, Number(opts.headersMs) || 45000);
  const tag = copy.tag();
  const t0 = Date.now();
  const want = rangeOf(req);

  if (want.begin === null) {
    // `bytes=-N`, a suffix read: ffmpeg probing the end of a container. Rare through this route, and it
    // needs a file length we only learn from the engine, so let the old pass-through handle it.
    return false;
  }
  const offset = want.begin;
  const insideWindow = (o) => o >= copy.base && (copy.pos > copy.base ? o < copy.pos : true);
  if (!insideWindow(offset)) copy.seekTo(offset);
  else copy.ensureReader(copy.pos);

  const first = await copy.waitFor(() => copy.find(offset) !== null || copy.eof || !!copy.error, headersMs);
  if (!first || !(copy.find(offset) || copy.eof)) {
    const waited = Date.now() - t0;
    log('PROXY FAIL', tag, 'phase=headers', 'cause=readahead-no-bytes', 'elapsed=' + waited + 'ms',
      String(copy.error || (copy.req ? 'no bytes yet' : 'engine not answering')).slice(0, 120));
    if (typeof opts.onCold === 'function') opts.onCold(waited, copy.error);
    if (res.headersSent) { try { res.end(); } catch (e) { /* gone */ } return true; }
    res.writeHead(503, {
      'content-type': 'application/json',
      'retry-after': String(Math.ceil(((opts.retryAfterMs || 5000)) / 1000)),
    });
    res.end(JSON.stringify({
      error: 'warming',
      warming: true,
      detail: 'the hub has not had a single byte of this copy within ' + waited + 'ms',
      retryAfterMs: opts.retryAfterMs || 5000,
      hash: copy.hash,
      fileId: copy.fileId,
    }));
    return true;
  }
  if (copy.eof && !copy.find(offset)) {
    // The read ran off the end of the file: an empty body for a range past EOF, which is what a player
    // asking for the last few bytes of a finished file expects.
    res.writeHead(416, { 'content-range': 'bytes */' + (copy.fileSize || offset) });
    res.end();
    return true;
  }

  const end = want.end != null ? want.end
    : (Number.isFinite(copy.fileSize) && copy.fileSize > 0 ? copy.fileSize - 1 : null);
  // Prefer the engine's own answer for this exact range: media3 checks content-range and content-length,
  // and the safest thing to send it is what the engine itself said when the buffer's reader asked.
  const sameRange = copy.resHeaders && copy.readerAt === offset && want.end == null;
  const hdrs = sameRange ? { ...copy.resHeaders.headers } : {};
  if (!sameRange) {
    hdrs['accept-ranges'] = 'bytes';
    if (want.ranged) {
      hdrs['content-range'] = 'bytes ' + offset + '-' + (end == null ? '*' : end)
        + '/' + (copy.fileSize || (offset + 1));
      if (end != null) hdrs['content-length'] = String(end - offset + 1);
    } else if (Number.isFinite(copy.fileSize) && copy.fileSize > 0) {
      hdrs['content-length'] = String(Math.max(0, copy.fileSize - offset));
    }
    if (copy.resHeaders && copy.resHeaders.headers['content-type']) {
      hdrs['content-type'] = copy.resHeaders.headers['content-type'];
    }
  }
  const status = sameRange ? copy.resHeaders.status : (want.ranged ? 206 : 200);
  const bufferedSec = copy.bufferedSec();
  hdrs['x-hub-buffered'] = String(Math.round(copy.held() / MB));
  hdrs['x-hub-buffered-sec'] = bufferedSec == null ? 'unknown' : String(Math.round(bufferedSec));
  delete hdrs['connection'];
  res.writeHead(status, hdrs);
  res.flushHeaders();
  log('PROXY TTFB', tag, 'ms=' + (Date.now() - t0), 'status=' + status,
    'range=' + ((req.headers || {}).range || 'none'), 'note=readahead',
    'bufferedMB=' + Math.round(copy.held() / MB));
  if (process.env.LOG_REQUESTS === '1') log('REQ', req.method, req.url, '->', status, '(readahead)');

  const consumer = { off: offset };
  copy.readers.add(consumer);
  copy.lastUsed = Date.now();
  const offReader = typeof opts.onReader === 'function' ? opts.onReader() : null;
  let delivered = 0;
  let cause = 'eof';
  let closedByClient = false;
  const onClose = () => { closedByClient = true; copy.wake(); };
  res.on('close', onClose);

  // The body is never cut on a clock. A quiet swarm waits for the buffer's reader to bring bytes, and if
  // the swarm stops altogether the engine closes the read and `cause=engine-error` says so in the log —
  // a truncated body is how the player learns to ask for a different copy.
  let cursor = offset;
  try {
    while (end == null || cursor <= end) {
      if (res.destroyed || closedByClient) { cause = 'client-disconnect'; break; }
      const ch = copy.find(cursor);
      if (!ch) {
        if (cursor < copy.base) { copy.seekTo(cursor); consumer.off = cursor; continue; }
        if (!copy.req && !copy.eof) copy.ensureReader(cursor);
        await copy.waitFor(() => !!copy.find(cursor) || copy.base > cursor || copy.eof || !!copy.error
          || closedByClient, BODY_WAIT_MS);
        if (closedByClient) { cause = 'client-disconnect'; break; }
        // Another reader dragged the window past us (a seek, a second screen): follow it with one read.
        if (copy.base > cursor) { copy.seekTo(cursor); consumer.off = cursor; continue; }
        if (copy.find(cursor)) continue;
        cause = copy.eof ? 'eof' : (copy.error ? 'engine-error' : 'no-bytes');
        break;
      }
      const skip = Math.max(0, cursor - ch.start);
      let take = ch.buf.length - skip;
      if (end != null && ch.start + skip + take - 1 > end) take = end - (ch.start + skip) + 1;
      if (take <= 0) { cause = 'eof'; break; }
      const slice = ch.buf.subarray(skip, skip + take);
      cursor += slice.length;
      consumer.off = cursor;
      delivered += slice.length;
      if (!res.write(slice)) {
        await new Promise((r) => { res.once('drain', r); res.once('close', r); });
        if (res.destroyed || closedByClient) { cause = 'client-disconnect'; break; }
      }
    }
  } catch (e) {
    cause = 'write-failed';
  } finally {
    copy.readers.delete(consumer);
    if (typeof offReader === 'function') offReader();
    copy.lastUsed = Date.now();
  }
  const secs = Math.max(0.001, (Date.now() - t0) / 1000);
  const mbps = (delivered * 8) / 1e6 / secs;
  const last = copy.lastValidWindow();
  log(cause === 'eof' ? 'PROXY END' : 'PROXY CUT', tag, 'phase=body', 'cause=' + cause,
    'elapsed=' + Math.round(secs * 1000) + 'ms', 'bytes=' + delivered,
    'mbps=' + mbps.toFixed(1), 'bufferedMB=' + Math.round(copy.held() / MB),
    'need=' + (Number.isFinite(copy.needMbps) ? copy.needMbps.toFixed(1) : '?'),
    'got=' + (last == null ? '?' : last.mbps.toFixed(1)));
  if (!res.writableEnded) { try { res.end(); } catch (e) { /* already gone */ } }
  return true;
}

/* ------------------------------------------------------------------ housekeeping */

const tick = setInterval(() => {
  const c = cfg();
  const now = Date.now();
  for (const copy of Array.from(copies.values())) {
    copy.closeWindow(c);
    copy.trim(c);
    if (!copy.readers.size && now - copy.lastUsed > c.idleMs) drop(copy, 'idle');
  }
  for (const [h, s] of Array.from(slow.entries())) if (s.until < now) slow.delete(h);
}, 500);
if (tick.unref) tick.unref();

function status() {
  const c = cfg();
  const list = Array.from(copies.values()).map((x) => x.stats(c));
  return {
    enabled: c.on,
    copies: list.length,
    heldMB: Math.round(list.reduce((a, x) => a + x.heldMB, 0) * 10) / 10,
    limits: {
      readAheadMB: Math.round(c.maxBytes / MB),
      readAheadSec: c.maxSec,
      readAheadCopies: c.copies,
      readAheadIdleMs: c.idleMs,
      preBufferSec: c.preSec,
      preBufferMB: Math.round(c.preMB / MB),
      preBufferMs: c.preMs,
      sustainBudgetMs: c.sustainMs,
      rateWindowMs: c.windowMs,
      starvedWindows: c.starvedWindows,
      starvedFloor: c.starvedFloor,
      starvedGraceSec: c.graceSec,
      starvedHoldMs: c.holdMs,
    },
    buffer: list,
    outOfFavour: Array.from(slow.entries()).map(([h, s]) => ({
      hash: h.slice(0, 12), mbps: s.mbps, needMbps: s.needMbps,
      retryInMs: Math.max(0, s.until - Date.now()),
    })),
  };
}

/**
 * The address ffmpeg should read a torrent from: this hub's buffer, not the engine. One reader per copy
 * is both the RAM bound and the point — the engine keeps one sequential cache per torrent, and the
 * player, the remux and the hub's own read-ahead pulling separately is what makes it jump backwards.
 */
function wrapInput(engineUrl, needMbps) {
  if (!cfg().on || !SELF_ORIGIN) return null;
  const who = parseWho(engineUrl);
  if (!who.hash || who.fileId == null) return null;
  const need = Number(needMbps);
  return SELF_ORIGIN + '/app/v1/proxy?u=' + encodeURIComponent(engineUrl)
    + (Number.isFinite(need) && need > 0 ? '&need=' + need : '');
}

/**
 * What this hub is feeding a screen right now. Usually one copy, because usually one film is playing;
 * the answer is what /app/v1/progress carries back so a screen can learn that the picture it is showing
 * is about to stop before its own watchdog has to notice a frozen frame.
 */
function live() {
  const c = cfg();
  let best = null;
  for (const copy of copies.values()) {
    if (!copy.readers.size) continue;
    if (!best || copy.lastUsed > best.lastUsed) best = copy;
  }
  if (!best) return null;
  const w = best.lastValidWindow();
  const sec = best.bufferedSec();
  return {
    hash: String(best.hash).slice(0, 12),
    fileId: best.fileId,
    mbps: w == null ? null : Math.round(w.mbps * 100) / 100,
    needMbps: Number.isFinite(best.needMbps) ? Math.round(best.needMbps * 100) / 100 : null,
    bufferedMB: Math.round((best.held() / MB) * 10) / 10,
    bufferedSec: sec == null ? null : Math.round(sec),
    slowRun: best.slowRun,
    starved: best.starved,
    runway: best.runway(),
  };
}

function parseWho(u) {
  const m = /\/play\/([0-9a-fA-F]{40})\/(\d+)/.exec(String(u || ''));
  return m ? { hash: m[1].toLowerCase(), fileId: Number(m[2]) } : { hash: null, fileId: null };
}

module.exports = {
  cfg, acquire, get, drop, noteNeed, isSlow, clearSlow,
  sustain, preBuffer, serve, status, wrapInput, parseWho, setSelfOrigin, engineUrlOf, live,
};
