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
const https = require('https');
const crypto = require('crypto');
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
    direct: p.bufferDirect !== false,
    maxBytes: clampNum(p.readAheadMB, 96, 8, 4096) * MB,
    maxSec: clampNum(p.readAheadSec, 90, 5, 1800),
    conns: Math.round(clampNum(p.readAheadConns, 4, 1, 8)),
    chunkBytes: clampNum(p.readAheadChunkMB, 4, 1, 32) * MB,
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
    this.lastReadAt = 0;      // when a screen was last reading this, as opposed to the hub measuring it
    this.openedAt = Date.now();
    this.ttfbMs = null;
    this.needMbps = null;
    this.win = null;         // the sample window currently open
    this.windows = [];       // closed windows, oldest first
    this.slowRun = 0;        // consecutive valid windows under this copy's own bitrate
    this.starved = false;
    this.starvedAt = 0;
    this.seeks = 0;
    this.primeTarget = 0;    // a title-page pre-roll stops here, so browsing one title does not pull 96 MB
    this.listeners = new Set();
    // Parallel-range mode, for a plain internet copy rather than the engine's torrent. A free file host
    // rations each connection — measured on one tonight: 5 Mbps for the first 10 MB, down to 2 Mbps by
    // 25 MB, then a stall — so one sequential reader is the ceiling on the picture, not the film. The
    // same window is then filled by `readAheadConns` ranged reads at a time.
    this.ranges = false;
    this.originHeaders = {};   // what the host wants replayed (iKey, Referer) — the screen never sees them
    this.inflight = new Map(); // start byte -> {start, end, got, req, res, dead}
    this.queue = [];           // pieces that landed out of order, waiting for the hole before them
    this.chunkFails = 0;
    this.nextRetryAt = 0;
    // Where the last off-book (probe / gap) read ended, so a chain of them is recognised as playback.
    this.sideEnd = null;
    this.sideAt = 0;
    // A host that cuts one of our ranged reads has not refused the film: the read is re-issued after a
    // short backoff, and a screen watching a healthy body must not be told otherwise. `givenUp` is the
    // state that means "this copy will not answer", and only that state may cut a delivery short.
    this.givenUp = false;
  }

  tag() {
    if (this.ranges) {
      let where = '?';
      try { const u = new URL(this.url); where = u.hostname + u.pathname.slice(0, 28); } catch (e) { /* ? */ }
      return 'src=' + where;
    }
    return 'hash=' + String(this.hash).slice(0, 12) + ' file=' + this.fileId;
  }
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
    // The window this seek cuts short says nothing about the host: half of it belongs to the offset we are
    // leaving. Scoring it as a rate is how a copy gets exiled for the hub's own bookkeeping.
    if (this.win) this.win.capped = true;
    // Said out loud, because a player probing a container's index does this to a cushion repeatedly, and
    // every one of these throws away bytes this box already paid the host for. When playback jumps
    // backwards and re-buffers, this line is the reason and it used to be invisible.
    log('READAHEAD SEEK', this.tag(), 'to=' + at, 'from=' + this.base + '-' + this.pos,
      'dropMB=' + Math.round(this.held() / MB), 'readers=' + this.readers.size);
    this.closeReader();
    this.chunks = [];
    this.queue = [];
    this.base = Math.max(0, at);
    this.pos = this.base;
    this.cursor = this.base;
    // The anchor is what `trim` keeps bytes ahead of while nobody is reading. Left at the offset the
    // previous fill was anchored to, it sits hundreds of megabytes in front of this new window and the
    // very next trim discards every chunk this seek just started — measured tonight, a resume to byte 0
    // lost its first 4 MB to a stale anchor left by a measurement 431 MB away, and the screen's head read
    // fell off the back of the window.
    this.anchor = this.base;
    this.eof = false;
    this.error = null;
    this.givenUp = false;
    if (this.ranges) this.planFetch(cfg());
    else this.openReader(this.base);
  }

  /** Start a sequential read at `at` if there is not one running and the file is not finished. */
  ensureReader(at) {
    if (this.ranges) {
      if (this.eof) return;
      const want = Math.max(0, at);
      if (Number.isFinite(this.fileSize) && this.fileSize > 0 && want >= this.fileSize) { this.eof = true; return; }
      if (want < this.base || want > this.pos) { this.seekTo(want); return; }
      this.planFetch(cfg());
      return;
    }
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
    // A live HLS playlist can hand us an https origin to keep pulling from, and this same copy then
    // reopens readers against it all evening. http.get on an https URL doesn't fail politely — it
    // throws and takes the whole hub with it, so the library is chosen from the URL, not the habit.
    const lib = u.protocol === 'https:' ? https : http;
    const req = lib.get(u, { headers: { Range: 'bytes=' + at + '-' }, agent: false, timeout: 120000 }, (got) => {
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
    this.dropInflight();
    this.paused = false;
  }

  /** Is anything pulling bytes right now — one stream, or some number of ranged reads? */
  isActive() { return !!this.req || this.inflight.size > 0; }

  /* ------------------------------------------------ filling the window with ranged reads */

  /**
   * Keep `conns` ranged reads in flight just past the end of what we hold. This is the whole reason a
   * copy that a single socket delivers at 2 Mbps plays: four sockets pull three to four times that, and
   * the screen reads the cushion at LAN speed instead of waiting on one rationed connection.
   */
  planFetch(c) {
    if (!this.ranges || this.eof) return;
    if (this.held() >= this.capFor(c)) {
      // The cushion is full: stopping ourselves says nothing about the host's rate.
      if (this.win) this.win.capped = true;
      return;
    }
    if (Date.now() < this.nextRetryAt) return;
    if (this.chunkFails > 24) { this.error = 'host kept dropping our reads'; return; }
    // What is already coming counts as room used up. Four sockets each carrying 4 MB against a ceiling of
    // 11 MB is an over-fill the cushion then throws away, and on a host that rations each read, paying for
    // the same bytes twice is how a copy ends up slower than the one it replaced.
    if (this.capFor(c) - this.held() <= this.inflight.size * c.chunkBytes) return;
    let start = this.pos;
    let guard = 0;
    while (this.inflight.size < c.conns && guard++ < 16) {
      if (Number.isFinite(this.fileSize) && this.fileSize > 0 && start >= this.fileSize) { this.eof = true; break; }
      const end = Number.isFinite(this.fileSize) && this.fileSize > 0
        ? Math.min(start + c.chunkBytes - 1, this.fileSize - 1)
        : start + c.chunkBytes - 1;
      this.startChunk(start, end);
      start = end + 1;
    }
  }

  startChunk(start, end) {
    let u;
    try { u = new URL(this.url); } catch (e) { this.error = 'bad url'; return; }
    const mod = u.protocol === 'https:' ? https : http;
    const ent = { start, end, got: 0, req: null, res: null, dead: false };
    this.inflight.set(start, ent);
    const req = mod.get(u, {
      headers: { Accept: '*/*', 'Accept-Encoding': 'identity', ...this.originHeaders, Range: 'bytes=' + start + '-' + end },
      agent: false,
      timeout: 60000,
    }, (res) => {
      if (ent.dead) { res.resume(); return; }
      ent.res = res;
      const cr = res.headers && res.headers['content-range'];
      if (cr) { const m = /\/(\d+)\s*$/.exec(String(cr)); if (m) this.fileSize = Number(m[1]); }
      else if (res.statusCode === 200 && res.headers && res.headers['content-length'] && start === 0) {
        this.fileSize = Number(res.headers['content-length']);
      }
      if (res.statusCode === 416) {
        // Past the end of the file: everything is read.
        this.eof = true;
        res.resume();
        this.inflight.delete(start);
        this.wake();
        return;
      }
      if (res.statusCode >= 400) { res.resume(); this.failChunk(ent, 'HTTP ' + res.statusCode); return; }
      if (res.statusCode === 200 && start > 0) {
        // It answered a range with the whole body: this host has no Range support, so several sockets
        // would each download the same film. Fall back to one sequential read from the top.
        log('READAHEAD NO-RANGE', this.tag(), 'range=' + start + '-' + end, 'note=sequential fallback');
        this.ranges = false;
        this.dropInflight();
        this.chunks = [];
        this.queue = [];
        this.base = 0; this.pos = 0; this.cursor = 0;
        this.openReader(0);
        return;
      }
      if (this.ttfbMs === null) {
        this.ttfbMs = Date.now() - this.openedAt;
        log('READAHEAD TTFB', this.tag(), 'ms=' + this.ttfbMs, 'range=' + start + '-' + end,
          'status=' + res.statusCode, 'note=ranges', 'conns=' + this.inflight.size);
      }
      if (!this.resHeaders) this.resHeaders = { status: res.statusCode || 200, headers: res.headers };
      if (!this.win) this.newWindow();
      let at = start;
      res.on('data', (b) => {
        if (ent.dead) return;
        ent.got += b.length;
        this.absorb(at, b);
        at += b.length;
      });
      res.on('end', () => {
        if (ent.dead) return;
        this.inflight.delete(start);
        this.chunkFails = 0;
        // Bytes arrived: whatever the host did a moment ago is no longer the truth about this copy.
        this.error = null;
        this.givenUp = false;
        if (Number.isFinite(this.fileSize) && this.fileSize > 0 && this.pos >= this.fileSize) this.eof = true;
        this.planFetch(cfg());
        this.wake();
      });
      res.on('error', () => this.failChunk(ent, 'read failed'));
      res.on('aborted', () => this.failChunk(ent, 'host cut the read'));
    });
    req.on('timeout', () => { if (!ent.dead) { try { req.destroy(); } catch (e) {} this.failChunk(ent, 'no answer in 60s'); } });
    req.on('error', (e) => this.failChunk(ent, e.code || e.message || 'connect failed'));
    ent.req = req;
  }

  /** Land one piece of the file: bytes from a socket that finished before the one before it. */
  absorb(at, buf) {
    if (!buf || !buf.length) return;
    this.everBytes = true;
    if (this.ttfbMs === null) this.ttfbMs = Date.now() - this.openedAt;
    this.queue.push({ start: at, buf });
    if (!this.win) this.newWindow();
    this.win.bytes += buf.length;
    this.lastUsed = Date.now();
    // Only bytes that join the end of what we hold extend the window; the rest wait in the queue for the
    // hole in front of them, so `chunks` stays ascending and contiguous and `find()` stays honest.
    let moved = true;
    while (moved) {
      moved = false;
      for (let i = 0; i < this.queue.length; i++) {
        const q = this.queue[i];
        if (q.start === this.pos) {
          this.chunks.push(q);
          this.pos = q.start + q.buf.length;
          this.cursor = this.pos;
          this.queue.splice(i, 1);
          moved = true;
          break;
        }
      }
    }
    this.trim(cfg());
    this.wake();
  }

  failChunk(ent, why) {
    if (ent.dead) return;
    ent.dead = true;
    try { if (ent.req) ent.req.destroy(); } catch (e) { /* already gone */ }
    try { if (ent.res) ent.res.destroy(); } catch (e) { /* already gone */ }
    if (this.inflight.get(ent.start) === ent) this.inflight.delete(ent.start);
    this.chunkFails++;
    // A host that fails every read is a dead copy, and the picker should hear about it in the same words
    // it hears about an engine that will not answer.
    if (!this.everBytes) this.error = why;
    this.givenUp = this.chunkFails > 8;
    this.nextRetryAt = Date.now() + Math.min(8000, 300 * this.chunkFails);
    if (this.chunkFails <= 6 || this.chunkFails % 20 === 0) {
      log('READAHEAD CHUNK FAIL', this.tag(), 'range=' + ent.start + '-' + ent.end, 'why=' + why,
        'fails=' + this.chunkFails, 'heldMB=' + Math.round(this.held() / MB));
    }
    this.wake();
  }

  dropInflight() {
    for (const ent of this.inflight.values()) {
      ent.dead = true;
      try { if (ent.req) ent.req.destroy(); } catch (e) { /* already gone */ }
      try { if (ent.res) ent.res.destroy(); } catch (e) { /* already gone */ }
    }
    this.inflight.clear();
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
    // This map is a memory of *watched* failures. A copy that scored two quiet windows while the picker
    // was only measuring it never had a viewer to starve, and exiling it for fifteen minutes on a
    // transient hiccup — measured tonight: three presses in a row refused the only copy that plays,
    // because a two-second stall during measurement had been recorded as a starving viewer — is worse
    // than the problem the memory was built to solve. The picker's own per-press verdicts already handle
    // a slow copy; only a screen that was actually reading gets a vote here.
    if (!this.readers.size && Date.now() - this.lastReadAt > 10000) return;
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

  /** The ceiling right now: the whole cushion for a viewer, a fixed slice of it for a title-page pre-roll. */
  capFor(c) {
    const b = this.bound(c);
    return this.primeTarget ? Math.min(b, this.primeTarget) : b;
  }

  /** Enforce the window: drop what nobody needs, and stop asking when the buffer is full. */
  trim(c) {
    // Behind the slowest reader is dead weight. While nobody is reading, the anchor is where the read
    // started — and the bytes ahead of the anchor are the pre-roll the film is about to be handed, so
    // they are kept and the reader is merely paused at the bound. A measurement that pauses because the
    // cushion is full scores a capped window, which is the honest report of a copy already sitting in
    // RAM, and is never mistaken for 0 Mbps.
    const low = this.readers.size ? this.front() : this.anchor;
    // Never above the window itself: an anchor from a fill that has since been replaced must not be able
    // to eat the whole cushion.
    if (low > this.base) this.anchor = this.base;
    while (this.chunks.length && this.chunks[0].start + this.chunks[0].buf.length <= low) {
      this.base = this.chunks[0].start + this.chunks[0].buf.length;
      this.chunks.shift();
    }
    // Over the ceiling: what goes is the far end of a fill nobody has reached, never the part a screen is
    // about to read. This used to drop from the front, and the front of a pre-roll is exactly the head of
    // the film: measured tonight, the picker filled 16.8 MB, the cap ate bytes 0-5.6 MB, and the player's
    // very first request — offset 0, the byte the hub had just promised — found nothing, waited out its
    // header budget with the host silent behind a cancelled read, and the screen failed over. Trimming the
    // back instead costs one re-fetch at most and keeps every paid-for byte the viewer is about to want.
    const cap = this.capFor(c);
    let guard = 0;
    while (this.held() > cap && this.chunks.length && guard++ < 256) {
      if (this.chunks[0].start >= low) {
        const last = this.chunks.pop();
        this.pos = this.cursor = Math.min(this.pos, last.start);
        // Out-of-order pieces past the new end are dead weight; the hole in front of them is where the
        // window is, so they would never be reachable from it.
        this.queue = this.queue.filter((q) => q.start < this.pos);
        continue;
      }
      const ch = this.chunks[0];
      const excess = this.held() - cap;
      if (ch.buf.length > excess) {
        this.base = ch.start + excess;
        this.chunks[0] = { start: this.base, buf: ch.buf.subarray(excess) };
        break;
      }
      this.base = ch.start + ch.buf.length;
      this.chunks.shift();
    }
    const full = this.held() >= cap;
    if (this.ranges) {
      // Nothing to pause: filling is a queue of ranged reads, so planFetch simply stops issuing new ones
      // while the cushion is full, and the window it stopped on scores as capped rather than 0 Mbps.
      if (full && this.win) this.win.capped = true;
      return;
    }
    if (full && this.up && !this.paused) {
      this.up.pause();
      this.paused = true;
      // A read we stopped ourselves cannot score a rate for the swarm.
      if (this.win) this.win.capped = true;
    } else if (!full && this.up && this.paused && this.held() < cap * 0.7) {
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
      reading: this.isActive(),
      mode: this.ranges ? 'ranges' : 'stream',
      inflight: this.inflight.size,
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

/** The same bookkeeping for a copy named by url rather than by hash. */
function slowFor(who) { const k = keyOf(who); return k ? isSlow(k) : null; }
function clearSlowFor(who) { const k = keyOf(who); if (k) clearSlow(k); }

/** The engine's own address for a copy — the only thing this hub reads a torrent through. */
function engineUrlOf(who) {
  const eng = String(process.env.TORR_URL || 'http://127.0.0.1:8090').replace(/\/+$/, '');
  return eng + '/play/' + who.hash + '/' + who.fileId;
}

/**
 * The registry key for a copy: the engine's own address for a torrent, the URL for anything else. A
 * direct addon link is signed and unique per press, so the key is that URL — a re-resolve is a different
 * copy of the same film, and it gets its own cushion rather than reading someone else's stale bytes.
 */
function keyOf(who) {
  if (!who) return null;
  if (who.hash && who.fileId != null) return String(who.hash) + ':' + String(who.fileId);
  if (who.url) return 'u:' + crypto.createHash('sha1').update(String(who.url)).digest('hex').slice(0, 20);
  return null;
}

function get(who) {
  const key = keyOf(who);
  return key ? (copies.get(key) || null) : null;
}

/** The buffer for this copy, creating it if needed. null when read-ahead is switched off. */
function acquire(who) {
  const c = cfg();
  if (!c.on || !who) return null;
  const gateway = Boolean(who.hash && who.fileId != null);
  if (!gateway && !who.url) return null;
  // A plain internet copy is cushioned only while the household wants the hub between the screen and the
  // host. Turning it off puts every direct link back on one connection to wherever it came from.
  if (!gateway && !c.direct) return null;
  const key = keyOf(who);
  let copy = copies.get(key);
  if (!copy) {
    copy = gateway
      ? new Copy(key, engineUrlOf(who), String(who.hash), Number(who.fileId))
      : new Copy(key, String(who.url), key, 0);
    if (!gateway) {
      copy.ranges = true;
      copy.originHeaders = (who.headers && typeof who.headers === 'object') ? who.headers : {};
    }
    copies.set(key, copy);
    log('READAHEAD OPEN', copy.tag(), 'boundMB=' + Math.round(copy.bound(c) / MB), 'maxSec=' + c.maxSec,
      gateway ? 'mode=engine' : 'mode=ranges conns=' + c.conns + ' chunkMB=' + Math.round(c.chunkBytes / MB));
    if (copies.size > c.copies) evictOver(c);
  } else if (!gateway && who.headers) {
    // Same URL, fresher headers: a signed link can be re-presented with new request headers.
    copy.originHeaders = (who.headers && typeof who.headers === 'object') ? who.headers : copy.originHeaders;
  }
  copy.lastUsed = Date.now();
  return copy;
}

/**
 * Is a screen on this copy now, or was it here a moment ago? A player spends most of its life *between* two
 * range requests with no reader attached, and a copy the hub just measured is the film that screen is about
 * to watch, so `lastUsed` — not only the reader set — is the honest hotness signal for decisions that put
 * bytes at risk. (`declareStarved` deliberately uses `lastReadAt` instead: only a screen that really read
 * gets a vote in exile.)
 */
function isWatched(copy) {
  return copy.readers.size > 0 || Date.now() - (copy.lastUsed || 0) < 60000;
}

function evictOver(c) {
  const list = Array.from(copies.values()).sort((a, b) =>
    (isWatched(a) ? 1 : 0) - (isWatched(b) ? 1 : 0) || a.lastUsed - b.lastUsed);
  while (copies.size > c.copies && list.length) {
    const old = list.shift();
    // A copy the picker measured a moment ago is the film someone is about to watch, and the player
    // spends most of its life between two range requests with no reader attached. Dropping it for a
    // measurement cushion three candidates back is how a watched copy loses its 90 seconds of RAM and the
    // screen re-buffers from the top of the internet.
    if (isWatched(old)) break;
    drop(old, 'over the copy limit');
  }
  evictBytes(c);
}

/**
 * The copy COUNT cap says how many cushions this box may hold; it says nothing about how many
 * MEGABYTES. Browsing eight titles inside the 60 s watched window parks eight prime targets in RAM
 * while the count pass sits idle — prod logs showed total hold climbing past the configured ceiling
 * (86 MB held against a 96 MB cap, each copy inside its own window bound). The byte cap is the
 * number the box actually has: drop the least-recent unwatched copies first, and past 1.5x the cap,
 * drop recently-read copies nobody is reading THIS second, oldest first.
 */
function evictBytes(c) {
  let held = 0;
  for (const cp of copies.values()) held += cp.held();
  if (held <= c.maxBytes) return;
  const list = Array.from(copies.values()).sort((a, b) => a.lastUsed - b.lastUsed);
  for (const hard of [false, true]) {
    for (const old of list) {
      if (!copies.has(old)) continue;
      if (!hard && held <= c.maxBytes) return;
      if (hard && held <= c.maxBytes * 1.5) return;
      if (hard ? old.readers.size > 0 : isWatched(old)) continue;
      const h = old.held();
      drop(old, 'over the byte cap');
      held -= h;
    }
    if (held <= c.maxBytes) return;
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
  // A copy someone is READING right now (a /switch walks the queue without excluding the copy that is
  // playing) must not be reset and yanked: its starve history is what protects the viewer, and a seek
  // mid-read throws away the fill behind the player and measures across the seek. Sample only what
  // arrives from here on, from where the reader already sits.
  const serving = copy.readers.size > 0;
  copy.primeTarget = 0;   // a real viewer is reading now: the full bound applies
  const t0 = Date.now();
  copy.openedAt = t0;
  if (!serving) { copy.windows = []; copy.slowRun = 0; }
  const wBase = copy.windows.length;
  copy.sliding = true;   // keep the reader running: pausing it mid-sample hides the swarm's real rate
  if (!serving) copy.anchor = at;
  const alreadyHeld = copy.held();
  // Bytes we already hold stay held: they were paid for a moment ago and the player is about to want them.
  // What this sample measures is what arrives from here on.
  if (serving || (copy.base <= at && (copy.pos === copy.base || at < copy.pos))) copy.ensureReader(copy.pos);
  else copy.seekTo(at);
  const startPos = copy.pos;
  const deadline = t0 + budgetMs;
  await copy.waitFor(() => Date.now() - t0 >= deadline || (!copy.isActive() && copy.windows.length > wBase),
    budgetMs + 80);
  // Close whatever window is still open so a sample taken over 4 s always reports both of its halves.
  // A short final window counts: a Play press that spent a second on the host's time-to-first-byte has
  // less than a full window left to judge, and answering "no rate to judge" for a copy that is visibly
  // moving is how a hub ends up refusing everything. The verdict is the WORST window in the sample, so a
  // brief window can only ever make a copy look slower than it is, never faster.
  if (copy.win && Date.now() - copy.win.at >= Math.min(c.windowMs, 400)) {
    copy.closeWindow({ ...c, windowMs: Math.min(c.windowMs, 400) });
  }
  const ms = Math.max(1, Date.now() - t0);
  const bytes = Math.max(0, copy.pos - startPos);
  copy.sliding = false;
  copy.lastUsed = Date.now();
  let worst = null;
  let validWindows = 0;
  const sample = copy.windows.slice(wBase); // only windows opened during THIS sample belong to the verdict
  for (const w of sample) if (w.valid) { validWindows++; if (worst === null || w.mbps < worst) worst = w.mbps; }
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
    windows: sample.slice(-4).map((w) => ({
      ms: w.ms, mbps: Math.round(w.mbps * 10) / 10, valid: w.valid,
    })),
  };
}

/**
 * Start filling a copy before anyone presses Play.
 *
 * A title page is where the swarm has time the Play button does not. This opens the same buffer the
 * player will read and fills a fixed slice of it (24 MB by default), then pauses. On Play, the measurement
 * finds those bytes already in RAM (`cached: true`), so the press is a LAN read instead of a cold wait.
 * It is deliberately small: browsing ten titles must not pin a gigabyte of film.
 */
function prime(who, opts = {}) {
  const c = acquire(who);
  if (!c) return false;
  const need = Number(opts.needMbps);
  if (Number.isFinite(need) && need > 0) c.needMbps = need;
  const bytes = Number(opts.bytes);
  c.primeTarget = Math.max(2 * MB, Math.min(c.bound(cfg()), Number.isFinite(bytes) ? bytes : 24 * MB));
  c.anchor = Math.max(0, Number(opts.at) || 0);
  c.ensureReader(c.anchor);
  return true;
}

/**
 * Keep pulling until the cushion holds this much picture, or the clock says start anyway.
 * Called after the copy is chosen, from the same buffer the measurement filled, so on a warm copy this
 * returns at once and the first frame costs the player nothing.
 */
async function preBuffer(copy, opts = {}) {
  const c = cfg();
  copy.primeTarget = 0;
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
  const done = () => copy.held() >= target || copy.eof || !copy.isActive();
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
  // `bytes=500-0` is not a range: answering it would emit `Content-Range: bytes 500-0/…` and a
  // negative Content-Length — an HTTP parse error at the player where a clean 416 is the cure.
  if (end != null && end < begin) return { begin: 0, end: null, ranged: false, invalid: true };
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
/**
 * Is this request a peek at the index rather than a move? Bounded ranges, and anything sitting on the last
 * few megabytes of a file whose length we know, are what a container reader does to find its seek head.
 */
function isProbe(copy, want, c) {
  const span = want.end != null ? want.end - want.begin : null;
  // A container reader asks for a mouthful of index at a time — tens of kilobytes to a few hundred. A
  // bounded read of a megabyte or more is a screen asking for picture, and answering that off to one
  // socket instead of moving the cushion was measured at 0.28 Mbps on this host against a 0.43 Mbps
  // file: the film starved in plain sight while the window sat parked at the head, still "buffered".
  if (span != null && span >= 0 && span <= Math.max(1 * MB, c.chunkBytes / 4)) return true;
  const size = Number(copy.fileSize);
  if (Number.isFinite(size) && size > 0 && want.begin >= size - Math.max(16 * MB, c.chunkBytes * 4)) {
    // Sitting on the end of a known-length file is the seek head. But a long tail read there is the last
    // minutes of a film, and that belongs in the cushion like any other picture.
    return span == null || span <= Math.max(4 * MB, c.chunkBytes);
  }
  return false;
}

/**
 * One throwaway ranged read, straight from the host to the screen, outside the cushion's window.
 *
 * The window stays where the viewer is; this request gets its own socket for its few kilobytes and then
 * goes away. Nothing here feeds the rate windows on purpose — a 27 KB tail read says nothing about whether
 * the host can carry the picture, and scoring it would let a probe mask a stall.
 */
function sideRead(res, copy, want, t0) {
  return new Promise((resolve) => {
    const from = want.begin;
    const to = want.end != null ? want.end
      : (Number.isFinite(copy.fileSize) && copy.fileSize > 0 ? copy.fileSize - 1 : null);
    let u;
    try { u = new URL(copy.url); } catch (e) { resolve(false); return; }
    const mod = u.protocol === 'https:' ? https : http;
    const range = 'bytes=' + from + '-' + (to == null ? '' : to);
    const req = mod.get(u, {
      headers: { Accept: '*/*', 'Accept-Encoding': 'identity', ...copy.originHeaders, Range: range },
      agent: false,
      timeout: 30000,
    }, (up) => {
      const cr = up.headers && up.headers['content-range'];
      if (cr) { const m = /\/(\d+)\s*$/.exec(String(cr)); if (m) copy.fileSize = Number(m[1]); }
      const headers = {};
      for (const k of ['content-type', 'accept-ranges']) {
        if (up.headers[k]) headers[k] = up.headers[k];
      }
      if (!headers['accept-ranges']) headers['accept-ranges'] = 'bytes';
      if (cr) headers['content-range'] = cr;
      headers['x-hub-probe'] = '1';
      delete headers['connection'];
      res.writeHead(up.statusCode || 200, headers);
      res.flushHeaders();
      log('PROXY TTFB', copy.tag(), 'ms=' + (Date.now() - t0), 'status=' + up.statusCode,
        'range=' + range, 'note=probe', 'windowMB=' + Math.round(copy.held() / MB));
      let bytes = 0;
      let done = false;
      const finish = (cause) => {
        if (done) return;
        done = true;
        up.destroy();
        // Remember the far end of what we just delivered off-book: the next request that starts here is
        // a continuation, not a probe. Only when the host actually honoured the range — a host that
        // answered with a plain 200 full body delivered bytes from 0, and recording `from + bytes` as
        // the end would point the continuation test at bytes that were never fetched.
        copy.sideEnd = (up.statusCode === 206 || from === 0) ? from + bytes : null;
        copy.sideAt = Date.now();
        if (!res.writableEnded) { try { res.end(); } catch (e) { /* gone */ } }
        const secs = Math.max(0.001, (Date.now() - t0) / 1000);
        log(cause === 'eof' ? 'PROXY END' : 'PROXY CUT', copy.tag(), 'phase=body', 'cause=' + cause,
          'elapsed=' + Math.round(secs * 1000) + 'ms', 'bytes=' + bytes, 'note=probe',
          'windowMB=' + Math.round(copy.held() / MB));
        resolve(true);
      };
      up.on('data', (b) => { bytes += b.length; if (!res.write(b)) { try { up.pause(); } catch (e) { /* gone */ } } });
      up.on('end', () => finish('eof'));
      up.on('error', () => finish('probe-read-failed'));
      res.on('close', () => finish('client-disconnect'));
    });
    req.on('timeout', () => { req.destroy(); });
    req.on('error', (e) => {
      if (res.headersSent) { finishSide(res); resolve(true); return; }
      res.writeHead(503, { 'content-type': 'application/json', 'retry-after': '1' });
      res.end(JSON.stringify({ error: 'warming', warming: true, detail: 'probe read failed: ' + (e.message || e.code) }));
      log('PROXY FAIL', copy.tag(), 'phase=headers', 'cause=probe-read-failed', String(e.message || e.code || ''),
        'range=' + range);
      resolve(true);
    });
    req.end();
  });
}

function finishSide(res) {
  if (!res.writableEnded) { try { res.end(); } catch (e) { /* gone */ } }
}

async function serve(req, res, who, opts = {}) {
  const c = cfg();
  const copy = acquire(who);
  if (!copy) return false;
  if (Number.isFinite(Number(opts.needMbps)) && Number(opts.needMbps) > 0) copy.needMbps = Number(opts.needMbps);
  const headersMs = Math.max(1000, Number(opts.headersMs) || 45000);
  const tag = copy.tag();
  const t0 = Date.now();
  const want = rangeOf(req);
  if (want.invalid) {
    res.writeHead(416, { 'content-range': 'bytes */' +
      (Number.isFinite(copy.fileSize) && copy.fileSize > 0 ? copy.fileSize : '*') });
    res.end();
    return true;
  }

  if (want.begin === null) {
    // `bytes=-N`, a suffix read: a player probing the end of a container (Matroska keeps its seek head
    // there). The pass-through used to answer this itself, and a free file host often refuses it — the
    // 500 that a suffix range got tonight came back to the screen as ERROR_CODE_IO_BAD_HTTP_STATUS and
    // killed playback of a copy that was playing fine. Once a ranged cushion knows the file's length,
    // the suffix is just an absolute range and this box can answer it out of RAM.
    if (copy.ranges && want.suffix > 0 && Number.isFinite(copy.fileSize) && copy.fileSize > 0) {
      want.begin = Math.max(0, copy.fileSize - want.suffix);
      want.end = copy.fileSize - 1;
    } else {
      return false;
    }
  }
  const offset = want.begin;
  // What the cushion actually holds, right now. The old test treated an empty window as if it contained
  // every offset — `pos === base` made any number look inside — so a player probing the end of a
  // two-gigabyte file minutes before its first byte landed attached itself to a window that could never
  // answer, waited out the whole header budget, and failed over on a copy that was filling fine.
  const insideWindow = (o) => o >= copy.base && o < Math.max(copy.pos, copy.base + 1);

  // A player probing a container's index is not changing what it watches. Matroska keeps its seek head at
  // the END of the file and MP4 its moov atom there too, so ExoPlayer opens the front, reads a few
  // megabytes, hangs up, asks for the last 27 KB of a two-gigabyte file, and comes back — measured on this
  // exact copy tonight. A cushion has ONE window; following that probe means throwing away the 90 seconds
  // of film already in RAM, paying the host's time-to-first-byte again, and arriving back at the front with
  // nothing, which is how a request for the end of a file turned into "playback error / 2004". So a probe
  // that is small and outside the window is answered with one throwaway ranged read from the host, and the
  // window the screen is actually watching stays where it is. A real seek — an open-ended range far from
  // the window — still moves it, because then the viewer has genuinely moved.
  // …unless the screen is doing it repeatedly. A chain of bounded reads that each begin where the last one
  // ended is not an index peek, it is a player eating the film in mouthfuls; answer two mouthfuls from the
  // host and then move the cushion behind the next one, or the fill never gets where the viewer is.
  const continues = copy.sideEnd != null && Date.now() - copy.sideAt < 30000
    && Math.abs(offset - copy.sideEnd) <= Math.max(1 * MB, c.chunkBytes / 4);
  if (!insideWindow(offset) && !continues && isProbe(copy, want, c)
      && (copy.readers.size || copy.isActive() || isWatched(copy))) {
    return await sideRead(res, copy, want, t0);
  }
  // The screen re-opened a little *behind* the window. A player does this constantly: it stops one loader
  // and starts the next at its own playhead, which sits a few megabytes back because the cushion ran ahead
  // of it while the read was open. Chasing that with a seek throws away a forward fill this box already
  // paid the host for — measured tonight, eleven megabytes dropped and re-fetched every five seconds of
  // playback, which is the "it plays in chunks" complaint wearing its working clothes. Fill the small gap
  // behind with one throwaway read instead; the window the screen reads next second stays exactly where
  // it is, and the player's next request lands inside it out of RAM.
  const behind = copy.base - offset;
  if (behind > 0 && copy.held() > 0 && isWatched(copy)
      && behind <= Math.max(32 * MB, c.chunkBytes * 8)) {
    return await sideRead(res, copy, { begin: offset, end: copy.base - 1, ranged: want.ranged }, t0);
  }

  if (!insideWindow(offset)) copy.seekTo(offset);
  else copy.ensureReader(copy.pos);

  const first = await copy.waitFor(() => copy.find(offset) !== null || copy.eof || copy.givenUp, headersMs);
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
      // A total this hub has never seen stays `*` (valid per RFC 9110): inventing `offset+1` used to
      // claim a file was one byte longer than the byte just asked for, and media3 takes content-range
      // totals at their word when it computes end-of-file.
      hdrs['content-range'] = 'bytes ' + offset + '-' + (end == null ? '*' : end)
        + '/' + (Number.isFinite(copy.fileSize) && copy.fileSize > 0 ? copy.fileSize : '*');
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
  copy.lastReadAt = Date.now();
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
        if (!copy.isActive() && !copy.eof) copy.ensureReader(cursor);
        await copy.waitFor(() => !!copy.find(cursor) || copy.base > cursor || copy.eof || copy.givenUp
          || closedByClient, BODY_WAIT_MS);
        if (closedByClient) { cause = 'client-disconnect'; break; }
        // Another reader dragged the window past us (a seek, a second screen): follow it with one read.
        if (copy.base > cursor) { copy.seekTo(cursor); consumer.off = cursor; continue; }
        if (copy.find(cursor)) continue;
        cause = copy.eof ? 'eof' : (copy.givenUp || copy.error ? 'engine-error' : 'no-bytes');
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
    copy.lastReadAt = Date.now();
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
    // A cushion that stopped filling because it was full starts again as soon as a screen eats into it.
    if (copy.ranges) copy.planFetch(c);
    if (!copy.readers.size && now - copy.lastUsed > c.idleMs) drop(copy, 'idle');
  }
  // Copies the watcher protected (read a moment ago, prime target filled) can each sit at their own
  // bound; only a global pass keeps the SUM inside readAheadMB between Play presses.
  evictBytes(c);
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

/**
 * Measure a plain internet copy *through* the cushion and then hand back what the cushion holds.
 *
 * This is the difference between the two answers a free file host gives. Measured on one tonight: a
 * single connection took 5 Mbps for the first ten megabytes, decayed to 2 Mbps by 25 MB and stopped
 * dead at 30 MB after 90 seconds, while four ranged reads in parallel carried 48 MB in 36 seconds.
 * The 256 KB probe samples the burst at the front of that curve and reports "3.9 Mbps, enough", which
 * is how a 1080p copy gets promised to a screen that then freezes four minutes in. Reading through the
 * buffer measures the curve instead of its first point, and the bytes it pulls are the pre-roll the
 * film starts with — so the measurement costs the viewer nothing they would not have waited for anyway.
 *
 * Returns the same shape as `sustain`, plus the pre-roll, so the picker can judge it with the same
 * arithmetic it already uses on a torrent.
 */
async function cushionUrl(url, headers, opts = {}) {
  const c = cfg();
  if (!c.on || !c.direct || !url) return null;
  const copy = acquire({ url: String(url), headers: headers || {} });
  if (!copy) return null;
  const need = Number(opts.needMbps);
  if (Number.isFinite(need) && need > 0) copy.needMbps = need;
  // A Play press has a deadline and several copies in front of it; take a fair share of what is left
  // rather than the whole of it. The floor is not arbitrary: a verdict needs a full window plus a second
  // partial one, and the host's time-to-first-byte (measured: 500-1000 ms on a cold signed link) is
  // already spent before the first window opens.
  const left = Number.isFinite(Number(opts.leftMs)) ? Math.max(0, Number(opts.leftMs)) : c.sustainMs;
  const budgetMs = left < 1200 ? Math.max(600, left)
    : Math.max(2000, Math.min(c.sustainMs, Math.floor(left * 0.65)));
  const at = Number.isFinite(Number(opts.at)) ? Math.max(0, Number(opts.at)) : 0;
  const m = await sustain(copy, { needMbps: need, at, budgetMs });
  // Then hold enough of it that the screen starts ahead of its own first frame. The cushion keeps
  // filling while the player reads, so this waits for the difference, not for the whole pre-roll.
  const pb = await preBuffer(copy, {
    sec: Number.isFinite(need) && need > 0 ? c.preSec : 10,
    bytes: Math.max(4, Math.min(c.preMB / MB, 8)),
    budgetMs: Math.max(0, Math.min(1500, left - budgetMs)),
  });
  return Object.assign(m, {
    prebuffer: pb,
    bufferedSec: pb.sec,
    runway: copy.runway(),
    conns: c.conns,
  });
}

module.exports = {
  cfg, acquire, get, drop, noteNeed, isSlow, clearSlow, keyOf, slowFor, clearSlowFor,
  sustain, preBuffer, prime, serve, status, wrapInput, parseWho, setSelfOrigin, engineUrlOf, live,
  cushionUrl,
};
