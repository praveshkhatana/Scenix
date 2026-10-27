'use strict';
/*
 * core/remux.js — hand a torrent copy to a browser without touching its pixels.
 *
 * Two players live in this product and they want different containers.
 *
 * The TV app plays Matroska natively (media3): it keeps the file exactly as it is, which is what makes
 * x264 10-bit and an E-AC3/DTS bitstream reach the receiver intact. It sends `container:"any"` and this
 * module stays out of its way.
 *
 * A browser has no native Matroska playback, so for `container:"mp4"` the hub re-wraps the same packets
 * into fragmented MP4 on the fly and pipes them out: `-c:v copy` always (this box has an i5-8500T and no
 * GPU; decoding 1080p24 x264 in software is how a remux becomes a slideshow), and `-c:a copy` when the
 * audio codec is legal in MP4, transcoded to AAC only when it is not (AC-3, DTS, TrueHD). Nothing here
 * re-encodes video. Ever.
 *
 * Fragmented MP4, not plain MP4: a normal MP4 starts with a `moov` atom that cannot be written until the
 * end of the file, so a stream that never ends has no header and plays nothing. `frag_keyframe +
 * empty_moov + default_base_moof` puts an empty `moov` first and appends `moof`/`mdat` pairs at keyframe
 * boundaries, which is the only MP4 shape that can be produced while it is being read. (Worth knowing:
 * the flag is spelled `empty_moov`, not `empty_moof`; this build rejects the latter, and the whole first
 * draft of this file produced a 200 whose body ffprobe could not identify.)
 *
 * Verified on this box with its own ffmpeg 7.0.2 against a live engine stream, then ffprobed the bytes:
 *   -i <engine>/play/<hash>/1 -map 0 -c:v copy -c:a copy -movflags frag_keyframe+empty_moov+default_base_moof
 *     -f mp4 pipe:1 | head -c 2000000   -> 2000000 bytes in 151 ms, ffprobe: mov,mp4 + h264 + aac
 *   -ss 600 -i … -c:a aac -b:a 192k     -> valid MP4, first byte 82 ms after start
 * That is the acceptance test, not "the process exited 0".
 *
 * Track selection is real: `?a=a:1` maps that container stream and nothing else, so choosing the Hindi
 * dub in a browser is a byte-level fact rather than a wish. `?s=s:2` burns a text subtitle in as mov_text
 * (MP4 has no other subtitle codec a browser will read).
 *
 * The child dies with the connection. `res.on('close')` is the only reliable end signal a streaming
 * browser gives, and an ffmpeg nobody is reading would otherwise sit on the engine's cache and on CPU.
 *
 * `container:"hls"` is the optional third answer: the same copy, cut into fMP4 HLS segments in a rolling
 * window under data/hls. It exists for players that want a playlist rather than one long response; the
 * mp4 path is the one the web client uses.
 *
 * No npm: child_process + http.
 */

const { spawn, spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = path.join(__dirname, '..');
const DATA_DIR = process.env.DATA_DIR || path.join(ROOT, 'data');

const FFMPEG_BIN = process.env.FFMPEG_BIN || 'ffmpeg';
const AUDIO_KBPS = process.env.REMUX_AUDIO_KBPS || '192k';
// One ffmpeg per viewer is the point; past this a new request is refused fast rather than queueing
// behind a stream that may run for two hours.
const MAX_CHILDREN = Math.max(1, Number(process.env.REMUX_MAX_CHILDREN || 3));
// No output bytes for this long means the engine stopped feeding us (or the swarm died).
const STALL_MS = Number(process.env.REMUX_STALL_MS || 45000);
// How long to wait for ffmpeg's FIRST output byte before calling the engine cold. Longer than a normal
// start (a warm copy hands over its first fragment in well under a second) and shorter than a browser will
// sit there: a Matroska remux has to read the head and the tail of the file before it can write anything,
// and on a copy the engine has not cached, that is a swarm download, not a disk read.
const FIRST_BYTE_MS = Number(process.env.REMUX_FIRST_BYTE_MS || 25000);
const HLS_DIR = process.env.REMUX_HLS_DIR || path.join(DATA_DIR, 'hls');
const HLS_SEGMENT_SEC = Math.max(2, Number(process.env.REMUX_HLS_TIME || 4));
const HLS_WINDOW = Math.max(6, Number(process.env.REMUX_HLS_WINDOW || 20));
const HLS_IDLE_MS = Number(process.env.REMUX_HLS_IDLE_MS || 90000);

/** Codecs an MP4 container may carry that a browser will also decode without complaint. */
const MP4_AUDIO = new Set(['aac', 'mp3']);
/** Text subtitles we can convert; bitmap tracks (PGS/VobSub/DVB) cannot become WebVTT or mov_text. */
const TEXT_SUBS = { subrip: 'srt', srt: 'srt', ass: 'ass', ssa: 'ass', webvtt: 'vtt', mov_text: 'vtt', text: 'vtt' };

const children = new Map(); // id -> { child, url, hash, started, bytes, lastByte, note, mode }
const hlsJobs = new Map();  // id -> { params, child, lastSeen, dir, started }
const subCache = new Map(); // key -> { at, body }
let binSeen = { found: null, version: null, note: 'not checked', at: 0 };
let rejected = 0;

function checkBin() {
  let r;
  try {
    r = spawnSync(FFMPEG_BIN, ['-version'], { timeout: 4000, encoding: 'utf8' });
  } catch (e) {
    binSeen = { found: false, version: null, note: e.message, at: Date.now() };
    return binSeen;
  }
  if (!r || r.error || r.status !== 0) {
    binSeen = {
      found: false, version: null, at: Date.now(),
      note: r && r.error && r.error.code === 'ENOENT'
        ? FFMPEG_BIN + ' not found on PATH (set FFMPEG_BIN)' : 'ffmpeg -version failed',
    };
    return binSeen;
  }
  binSeen = {
    found: true, version: String(r.stdout || '').split('\n')[0].replace(/^ffmpeg version /, '').slice(0, 40),
    note: '', at: Date.now(),
  };
  return binSeen;
}

const stamp = () => new Date().toISOString();
const log = (...a) => console.log(stamp(), ...a);

function sendJson(res, cors, code, body, extraHeaders) {
  if (res.writableEnded) return;
  res.writeHead(code, Object.assign({
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  }, cors || {}, extraHeaders || {}));
  res.end(JSON.stringify(body));
}

/** `/play/<hash>/<fileId>` back into the two things that name a copy. */
function parseEngineUrl(u) {
  const m = /\/play\/([0-9a-fA-F]{40})\/(\d+)/.exec(String(u || ''));
  return m ? { hash: m[1].toLowerCase(), fileId: Number(m[2]) } : { hash: null, fileId: null };
}

/** `a:1`, `1`, or a media.js track id -> the container stream index, or null. */
function streamIndex(ref, kind) {
  const s = String(ref == null ? '' : ref).trim().toLowerCase();
  if (!s) return null;
  const m = new RegExp('^' + kind + ':?(\\d+)$').exec(s);
  const n = m ? Number(m[1]) : Number(s);
  return Number.isInteger(n) && n >= 0 && n < 64 ? n : null;
}

/**
 * Which audio handling to try, in order. When core/media.js already read this copy we know its codec and
 * get it right first time; when we do not know, copy first (never worse for quality) and fall back.
 */
function audioPlans(tracks, audioIdx) {
  if (!tracks) return ['copy', 'aac', 'none'];
  const list = tracks.audioTracks || [];
  if (!list.length) return ['none'];
  const pick = (audioIdx != null && list.find((t) => t.id === 'a:' + audioIdx))
    || list.find((t) => t.default) || list[0];
  return MP4_AUDIO.has(String(pick.codec || '').toLowerCase()) ? ['copy'] : ['aac'];
}

/** The single answer for "how must this copy's audio enter MP4": 'copy', 'aac' or 'none'. */
function planFor(tracks, audioIdx) {
  const plans = audioPlans(tracks, audioIdx);
  return plans[0];
}

/** The ffmpeg argument list: one shared builder so the mp4 and hls paths cannot drift apart. */
function baseArgs(p, plan, format) {
  const args = ['-hide_banner', '-loglevel', 'error'];
  const startMs = Math.max(0, Number(p.startMs) || 0);
  if (startMs > 0) args.push('-ss', (startMs / 1000).toFixed(3)); // input seek: the engine serves the right offset
  args.push('-i', p.url);
  args.push('-map', '0:v');
  if (plan !== 'none') {
    args.push('-map', p.audioIdx != null ? '0:' + p.audioIdx : '0:a:0');
  }
  if (p.subIdx != null) args.push('-map', '0:' + p.subIdx);
  if (format === 'mp4') {
    const durMs = Math.max(0, Number(p.durMs) || 0);
    if (durMs > 0) args.push('-t', (durMs / 1000).toFixed(3));
    args.push('-c:v', 'copy');
    if (plan === 'copy') args.push('-c:a', 'copy');
    else if (plan === 'aac') args.push('-c:a', 'aac', '-b:a', AUDIO_KBPS);
    else args.push('-an');
    if (p.subIdx != null) args.push('-c:s', 'mov_text');
    args.push('-movflags', 'frag_keyframe+empty_moov+default_base_moof', '-f', 'mp4', 'pipe:1');
  } else {
    args.push('-c:v', 'copy');
    if (plan === 'copy') args.push('-c:a', 'copy');
    else if (plan === 'aac') args.push('-c:a', 'aac', '-b:a', AUDIO_KBPS);
    else args.push('-an');
    if (p.subIdx != null) args.push('-c:s', 'mov_text');
    args.push(
      '-f', 'hls',
      '-hls_time', String(HLS_SEGMENT_SEC),
      '-hls_list_size', String(HLS_WINDOW),
      '-hls_segment_type', 'fmp4',
      '-hls_flags', 'delete_segments+independent_segments+omit_endlist+temp_file',
      '-hls_segment_filename', path.join(p.dir, 'seg%05d.m4s'),
      path.join(p.dir, 'index.m3u8'),
    );
  }
  return args;
}

function spawnFfmpeg(args, id) {
  const child = spawn(FFMPEG_BIN, args, { stdio: ['ignore', 'pipe', 'pipe'] });
  let err = '';
  child.stderr.on('data', (c) => {
    err += c;
    if (err.length > 4000) err = err.slice(0, 4000);
  });
  child.on('error', (e) => {
    log('REMUX FAIL', 'id=' + id, 'cause=spawn', e.code === 'ENOENT' ? FFMPEG_BIN + ' not found' : e.message);
  });
  child._stderr = () => err.replace(/\s+/g, ' ').slice(0, 200);
  return child;
}

function killChild(child, why) {
  if (!child || child.exitCode !== null || child.killed) return;
  try { child.kill('SIGTERM'); } catch (e) { /* gone */ }
  const t = setTimeout(() => {
    try { if (child.exitCode === null) child.kill('SIGKILL'); } catch (e) { /* gone */ }
  }, 500);
  if (t.unref) t.unref();
  child._killedBy = why;
}

// ---- fMP4 over one HTTP response -------------------------------------------------------------------

/**
 * GET /app/v1/remux?u=<engine play url>&v=mp4[&a=a:1][&s=s:2][&start=<ms>][&dur=<ms>]
 * Answers a fragmented MP4 stream built from the copy's own packets. `u` is validated: this endpoint is
 * for the engine's stream, not a general-purpose proxy (that job is /app/v1/proxy).
 */
/** One byte range — the only kind a media client sends. `to` may be open-ended. */
function parseRange(header) {
  const m = /^bytes=(\d+)-(\d*)$/i.exec(String(header || '').trim());
  if (!m) return null;
  const from = Number(m[1]);
  const to = m[2] === '' ? null : Number(m[2]);
  if (!Number.isFinite(from) || (to != null && to < from)) return null;
  return { from, to, limit: to == null ? 0 : to - from + 1 };
}

function serve(req, res, u, cors, tracksLookup) {
  const target = u.searchParams.get('u') || '';
  const kind = String(u.searchParams.get('v') || 'mp4').toLowerCase();
  if (!/^https?:\/\//i.test(target)) {
    rejected++;
    return sendJson(res, cors, 400, { error: 'remux needs an absolute http url in u' });
  }
  if (kind !== 'mp4') {
    rejected++;
    return sendJson(res, cors, 400, { error: 'remux serves mp4; ask for container hls for a playlist' });
  }
  const who = parseEngineUrl(target);
  const audioIdx = streamIndex(u.searchParams.get('a'), 'a');
  const subIdx = streamIndex(u.searchParams.get('s'), 's');
  const startMs = Math.max(0, Number(u.searchParams.get('start')) || 0);
  const durMs = Math.max(0, Number(u.searchParams.get('dur')) || 0);
  const tracks = who.hash && tracksLookup ? tracksLookup(who.hash, who.fileId) : null;
  const plans = audioPlans(tracks, audioIdx);
  if (subIdx != null) {
    const t = tracks && (tracks.subtitleTracks || []).find((x) => x.id === 's:' + subIdx);
    if (tracks && !t) {
      // We know this file and it has no such track: refuse instead of asking ffmpeg to fail.
      rejected++;
      return sendJson(res, cors, 400, { error: 'this copy has no subtitle track ' + subIdx });
    }
  }
  if (children.size >= MAX_CHILDREN) {
    rejected++;
    return sendJson(res, cors, 503, {
      error: 'remux busy', retryAfterMs: 3000, active: children.size, max: MAX_CHILDREN,
    }, { 'Retry-After': '3' });
  }
  // A browser — and `curl -r` — asks for a byte range. A remux is generated as it runs, so the total
  // length is not knowable and the range can only be honoured from the front: a 206 whose Content-Range
  // names the exact bytes it will send (total `*` when the total is unknowable), or a plain 200 when the
  // client only asked for everything from byte 0 onwards - which is what a browser's media element does. Ignoring the header, which is
  // what this did first, means `curl -r 0-2000000` on a 2 GB film downloaded all 2 GB and the check that
  // was supposed to prove the container worked never finished. A range starting past byte 0 can only be
  // served by asking ffmpeg to start at a *time*, which is a guess about where the bytes are: the guess is
  // made only when this file's own size and duration are known, and it is reported in the response.
  const range = parseRange(req.headers.range);
  let seekMs = startMs;
  let seekApprox = false;
  if (range && range.from > 0) {
    const est = tracks && tracks.durationMs && tracks.sizeBytes
      ? Math.round((range.from / tracks.sizeBytes) * tracks.durationMs) : 0;
    if (est > 0) {
      seekMs = startMs + est;
      seekApprox = true;
    }
  }
  const p = { url: target, audioIdx, subIdx, startMs: seekMs, durMs };
  const id = crypto.randomBytes(5).toString('hex');
  const t0 = Date.now();
  let attempt = 0;
  let gen = 0; // which attempt owns this response right now
  let settled = false;
  let bytes = 0;
  let child = null;

  const finish = (cause, detail) => {
    if (settled) return;
    settled = true;
    if (child) children.delete(id);
    log('REMUX END', 'id=' + id, 'phase=body', 'cause=' + cause, 'elapsed=' + (Date.now() - t0) + 'ms',
      'bytes=' + bytes, 'hash=' + (who.hash || '-').slice(0, 12), 'audio=' + (p.audioIdx == null ? 'first' : p.audioIdx),
      detail ? 'detail=' + detail : '');
  };

  // A stalled pipe is a dead swarm, and the viewer deserves to find out before their buffer runs dry.
  //
  // Two different silences, because they mean different things. Nothing at all out of ffmpeg means the
  // engine has not served this file yet — a Matroska reader wants the head AND the tail of the file (the
  // seek index), so a cold 2 GB copy inside a season pack can sit for minutes doing exactly that, and the
  // browser is still waiting for its first byte. Bytes that started and then stopped means the swarm died
  // mid-film. Both get a watchdog, and each attempt gets its own: an interval armed once for the whole
  // request is cleared by the first retry and never re-armed, and that is how a remux child here ran for
  // ten minutes producing nothing while the module reported a 45 s stall limit.
  let watch = null;
  let lastByte = Date.now();
  const armWatch = (plan) => {
    if (watch) clearInterval(watch);
    const started = Date.now();
    let out = 0;
    watch = setInterval(() => {
      if (settled) { clearInterval(watch); return; }
      const quiet = Date.now() - lastByte;
      if (out === 0 && quiet >= FIRST_BYTE_MS) {
        clearInterval(watch);
        killChild(child, 'no-first-byte');
        tryNext('the engine served nothing to ffmpeg in ' + FIRST_BYTE_MS + 'ms (' + plan + ')');
      } else if (out > 0 && quiet >= STALL_MS) {
        clearInterval(watch);
        killChild(child, 'stall');
        if (bytes === 0) tryNext('no bytes after ' + STALL_MS + 'ms from the engine');
        else finish('stall');
      }
      out = bytes;
    }, 5000);
    if (watch.unref) watch.unref();
    return started;
  };

  let starved = ''; // set once any attempt proves the engine had no bytes for us yet

  const tryNext = (why) => {
    if (settled) return;
    // Remember an engine that has not answered yet. The next plan may fail through a different
    // handler whose message says only "ffmpeg exited 0", and losing this fact at that point is what turns
    // a cold torrent into a hard failure the viewer is told about.
    if (/served nothing|from the engine/.test(why)) starved = why;
    if (attempt > 0) log('REMUX RETRY', 'id=' + id, 'from=' + plans[attempt - 1], 'to=' + plans[attempt], 'because=' + why);
    if (attempt >= plans.length) {
      if (watch) clearInterval(watch);
      if (!res.writableEnded && !res.headersSent) {
        // The engine not answering for the file is not the same failure as ffmpeg refusing a codec: it is
        // the cold-torrent case, and the client already knows what to do with a warming answer.
        const cold = who.hash && /served nothing|from the engine/.test(why);
        rejected++;
        sendJson(res, cors, cold ? 503 : 502, cold
          ? { error: 'warming', warming: true, detail: why, retryAfterMs: 5000, hash: who.hash, fileId: who.fileId }
          : { error: 'remux failed', detail: why, hash: who.hash, fileId: who.fileId },
          cold ? { 'Retry-After': '5' } : null);
      } else if (!res.writableEnded) res.end();
      finish('failed', why);
      return;
    }
    const plan = plans[attempt];
    const args = baseArgs(p, plan, 'mp4');
    child = spawnFfmpeg(args, id);
    const mine = child; // the outer `child` moves on when we retry; these handlers must not follow it
    const my = ++gen;
    children.set(id, { child, url: target, hash: who.hash, started: t0, mode: 'mp4', plan, note: plan });
    attempt++;
    armWatch(plan);

    const first = (chunk) => {
      mine.stdout.removeListener('data', first);
      // A killed child still has buffered bytes, and they arrive after the next attempt is already running -
      // which is how a remux here wrote its status line twice and took the whole hub down with
      // ERR_HTTP_HEADERS_SENT. Whoever is speaking, only the current attempt may answer the client.
      if (my !== gen || settled || res.headersSent || res.writableEnded) return;
      const head = {
        'Content-Type': 'video/mp4',
        'Cache-Control': 'no-store',
        'X-Remux': 'fmp4 audio=' + plan + ' video=copy',
      };
      // Only a bounded range is a genuine partial response. An open-ended one - `Range: bytes=0-`, the probe
      // every browser sends first - gets the whole stream under a plain 200, because a 206 has to name the
      // last byte it will send and a stream with no end has none. The form used here before (`bytes 0-*`, a
      // star standing where the last byte belongs) is not valid HTTP, and Chrome answered it by giving up on
      // the source entirely: the identical bytes, answered 200, decode and advance normally.
      const bounded = !!range && range.limit > 0;
      if (bounded) {
        head['Content-Range'] = 'bytes ' + range.from + '-' + (range.from + range.limit - 1) + '/'
          + (tracks && tracks.sizeBytes ? tracks.sizeBytes : '*');
      }
      if (seekApprox) head['X-Remux-Seek'] = 'approximate:' + seekMs + 'ms';
      res.writeHead(bounded ? 206 : 200, Object.assign(head, cors));
      const limit = bounded ? range.limit : 0; // 0 means the client set no upper bound
      let served = 0;
      const pump = (c) => {
        if (my !== gen || res.writableEnded || settled) return;
        if (limit && served + c.length > limit) {
          res.write(c.slice(0, limit - served));
          served = limit;
          clearInterval(watch);
          killChild(child, 'range-end'); // the film is not over; this request is
          if (!res.writableEnded) res.end();
          finish('range-end');
          return;
        }
        served += c.length;
        res.write(c);
      };
      pump(chunk);
      child.stdout.on('data', pump);
      mine.stdout.on('end', () => {
        if (my !== gen) return;
        clearInterval(watch);
        if (!res.writableEnded) res.end();
      });
    };
    mine.stdout.on('data', (c) => {
      if (my !== gen) return; // bytes from an attempt we abandoned must not feed this one's watchdog
      bytes += c.length;
      lastByte = Date.now();
    });
    mine.stdout.on('data', first); // first chunk decides the status line; pipe handles the rest
    mine.on('close', (code) => {
      if (my !== gen || settled) return;
      if (bytes === 0) {
        // Nothing came out: a wrong map or a codec the container rejects. Try the next plan honestly.
        const why = (child._stderr() || 'ffmpeg exited ' + code);
        if (/matches no streams|Invalid argument|Codec|does not contain|-c:a/.test(why) && attempt < plans.length) {
          tryNext(why);
        } else {
          clearInterval(watch);
          rejected++;
          // Same judgement as the last-resort branch of tryNext, reached from the other direction: this
          // child produced nothing, and either we already know the engine is still fetching the piece or
          // ffmpeg exited cleanly with an empty pipe, which is what reading a cold engine looks like.
          const quiet = !String(child._stderr() || '').trim() && code === 0;
          const cold = !!who.hash && (starved || quiet);
          const detail = starved || why;
          if (!res.headersSent) {
            sendJson(res, cors, cold ? 503 : 502, cold
              ? { error: 'warming', warming: true, detail, retryAfterMs: 5000, hash: who.hash, fileId: who.fileId }
              : { error: 'remux failed', detail: why, hash: who.hash, fileId: who.fileId },
              cold ? { 'Retry-After': '5' } : null);
          } else if (!res.writableEnded) res.end();
          finish('failed', detail);
        }
        return;
      }
      clearInterval(watch);
      if (code !== 0 && child._killedBy !== 'client-disconnect') finish('ffmpeg-exit-' + code, child._stderr());
      else finish(child._killedBy || 'complete');
    });
  };

  res.on('close', () => {
    clearInterval(watch);
    if (child) killChild(child, 'client-disconnect');
    if (bytes === 0 && !res.headersSent) rejected++;
    finish(child && child._killedBy ? child._killedBy : 'client-disconnect');
  });
  log('REMUX START', 'id=' + id, 'container=mp4', 'hash=' + (who.hash || '-').slice(0, 12),
    'file=' + who.fileId, 'audio=' + (p.audioIdx == null ? 'first' : p.audioIdx),
    'sub=' + (p.subIdx == null ? 'none' : p.subIdx), 'start=' + startMs + 'ms',
    range ? 'range=' + range.from + '-' + (range.to == null ? '' : range.to) : 'range=none',
    seekApprox ? 'seek=approximate-' + seekMs + 'ms' : '',
    'plans=' + plans.join('/'));
  tryNext(null);
}

/** Relative URL for a remux response. Kept relative so no host is baked into a PlayTarget. */
function mp4Url(p) {
  const q = new URLSearchParams();
  q.set('u', p.url);
  q.set('v', 'mp4');
  if (p.audio != null) q.set('a', String(p.audio));
  if (p.sub != null) q.set('s', String(p.sub));
  if (p.startMs) q.set('start', String(Math.round(p.startMs)));
  return '/app/v1/remux?' + q.toString();
}

// ---- embedded subtitles, converted once -------------------------------------------------------------

/**
 * GET /app/v1/subtitle?u=<engine play url>&i=<stream index>
 * A browser subtitle track must be same-origin or CORS-enabled, and it must be WebVTT: an .ass stream
 * inside Matroska is neither. So the hub extracts the track and converts it, and caches the text in
 * memory — the cost is one ffmpeg per copy per subtitle, not per play.
 */
function subtitle(req, res, u, cors, tracksLookup) {
  const target = u.searchParams.get('u') || '';
  const idx = streamIndex(u.searchParams.get('i'), 's');
  if (!/^https?:\/\//i.test(target) || idx == null) {
    return sendJson(res, cors, 400, { error: 'subtitle needs u and a numeric i' });
  }
  const who = parseEngineUrl(target);
  if (tracksLookup && who.hash) {
    const tracks = tracksLookup(who.hash, who.fileId);
    const t = tracks && (tracks.subtitleTracks || []).find((x) => x.id === 's:' + idx);
    if (tracks && !t) return sendJson(res, cors, 404, { error: 'no such subtitle track', i: idx });
    if (t && !TEXT_SUBS[String(t.codec || '').toLowerCase()]) {
      return sendJson(res, cors, 415, {
        error: 'this subtitle is a picture track, not text', codec: t.codec,
        hint: 'select it by id in a native player instead',
      });
    }
  }
  const key = (who.hash || target) + ':' + idx;
  const hit = subCache.get(key);
  if (hit && Date.now() - hit.at < 10 * 60 * 1000) {
    res.writeHead(200, Object.assign({
      'Content-Type': 'text/vtt; charset=utf-8', 'Cache-Control': 'public, max-age=600',
    }, cors));
    return res.end(hit.body);
  }
  const id = crypto.randomBytes(4).toString('hex');
  const child = spawnFfmpeg(['-hide_banner', '-loglevel', 'error', '-i', target, '-map', '0:' + idx,
    '-f', 'webvtt', 'pipe:1'], id);
  let body = '';
  let tooBig = false;
  // Why the extractor was stopped before it finished. A subtitle stream inside a cold torrent is read
  // from the beginning of the file, so on a slow swarm hitting the timeout is the normal outcome — and
  // an answer that says "here are your subtitles" while carrying only the WEBVTT header is a lie the
  // player then caches, because a header is a plausible-looking document.
  let cutBy = null;
  const t0 = Date.now();
  child.stdout.on('data', (c) => {
    body += c;
    if (body.length > 4e6) { tooBig = true; cutBy = 'too-large'; killChild(child, 'too-large'); }
  });
  const timer = setTimeout(() => { cutBy = 'timeout'; killChild(child, 'timeout'); }, 20000);
  if (timer.unref) timer.unref();
  child.on('close', (code) => {
    clearTimeout(timer);
    if (res.writableEnded) return;
    const cues = body.replace(/^WEBVTT[^\n]*\r?\n/, '').replace(/\r/g, '').trim();
    if (!cues) {
      log('SUB FAIL', 'id=' + id, 'i=' + idx, 'hash=' + (who.hash || '-').slice(0, 12),
        'reason=' + (cutBy || 'no-cues'), 'elapsed=' + (Date.now() - t0) + 'ms',
        child._stderr() || ('exit ' + code));
      // Nothing reached the player yet is not the same answer as this copy having no subtitles: the
      // first is worth asking again in a few seconds, the second never will be.
      if (cutBy === 'timeout') {
        return sendJson(res, cors, 503, {
          error: 'warming', warming: true, retryAfterMs: 5000, i: idx,
          note: 'this copy has to come off the swarm before its subtitles exist',
        }, { 'Retry-After': '5' });
      }
      return sendJson(res, cors, 404, { error: 'no subtitles to extract', i: idx });
    }
    // Only a finished extraction is worth pinning for ten minutes. A body cut short by the timeout is
    // real subtitles that stop halfway through the film, and caching that is how you get a viewer
    // asking why the captions died at 01:52. Short cache, and say what it is.
    const partial = cutBy === 'timeout';
    if (!partial) {
      if (subCache.size > 40) subCache.clear();
      subCache.set(key, { at: Date.now(), body });
    }
    log('SUB OK', 'id=' + id, 'i=' + idx, 'bytes=' + body.length,
      partial ? 'partial=timeout' : 'complete', 'elapsed=' + (Date.now() - t0) + 'ms');
    res.writeHead(200, Object.assign({
      'Content-Type': 'text/vtt; charset=utf-8',
      'Cache-Control': partial ? 'public, max-age=30' : 'public, max-age=600',
    }, partial ? { 'X-Subtitles-Partial': 'timeout' } : {}, cors));
    res.end(body);
  });
}

// ---- HLS (optional) ---------------------------------------------------------------------------------

/** Register a playlist URL at play time; the ffmpeg starts when the playlist is first requested. */
function hlsUrl(p) {
  const id = crypto.randomBytes(5).toString('hex');
  const dir = path.join(HLS_DIR, id);
  try { fs.mkdirSync(dir, { recursive: true }); } catch (e) { /* the sweep will say so */ }
  hlsJobs.set(id, {
    params: { url: p.url, audioIdx: streamIndex(p.audio, 'a'), subIdx: streamIndex(p.sub, 's'), startMs: p.startMs || 0, dir },
    plan: p.plan || planFor(p.tracks, streamIndex(p.audio, 'a')),
    child: null, lastSeen: Date.now(), dir, started: 0,
  });
  return '/app/v1/hls/' + id + '/index.m3u8';
}

function hlsServe(req, res, u, cors, jobId, file) {
  const job = hlsJobs.get(jobId);
  if (!job) return sendJson(res, cors, 404, { error: 'no such hls job', id: jobId });
  job.lastSeen = Date.now();
  if (!job.child) {
    const args = baseArgs(job.params, job.plan, 'hls');
    const child = spawnFfmpeg(args, jobId);
    job.child = child;
    job.started = Date.now();
    children.set(jobId, { child, url: job.params.url, hash: parseEngineUrl(job.params.url).hash, started: job.started, mode: 'hls', plan: job.plan, note: 'hls' });
    child.on('close', (code) => {
      children.delete(jobId);
      job.child = null;
      log('REMUX END', 'id=' + jobId, 'mode=hls', 'cause=' + (child._killedBy || 'ffmpeg-exit-' + code),
        'elapsed=' + (Date.now() - job.started) + 'ms', child._stderr() || '');
    });
    log('REMUX START', 'id=' + jobId, 'container=hls', 'file=' + file);
  }
  const safe = String(file || '').replace(/[^A-Za-z0-9._-]/g, '');
  const full = path.join(job.dir, safe);
  if (!full.startsWith(job.dir)) return sendJson(res, cors, 400, { error: 'bad file name' });
  let waited = 0;
  const poll = setInterval(() => {
    waited += 200;
    if (fs.existsSync(full)) {
      clearInterval(poll);
      const type = /\.m3u8$/.test(safe) ? 'application/vnd.apple.mpegurl'
        : /\.mp4$|\.m4s$/.test(safe) ? 'video/iso.segment' : 'video/mp2t';
      res.writeHead(200, Object.assign({ 'Content-Type': type, 'Cache-Control': 'no-store' }, cors));
      fs.createReadStream(full).pipe(res);
    } else if (waited > 15000 || !job.child) {
      clearInterval(poll);
      sendJson(res, cors, 404, { error: 'segment not ready', waitedMs: waited });
    }
  }, 200);
  if (poll.unref) poll.unref();
  res.on('close', () => { job.lastSeen = Date.now(); });
}

// A hub that stops mid-remux must not leave an ffmpeg behind reading a torrent: the engine counts that
// reader as a viewer holding the torrent open, and nothing else on this machine will ever close it.
// `exit` is the one hook that catches every way this hub stops — core/store.js answers SIGINT and SIGTERM
// with a flush and a process.exit — and it must be synchronous, so this is a direct SIGKILL rather than the
// polite two-stage killChild, whose second stage is a timer that will never run again.
process.on('exit', () => {
  for (const entry of children.values()) {
    try {
      if (entry.child && entry.child.exitCode === null) entry.child.kill('SIGKILL');
    } catch (e) { /* already gone */ }
  }
  for (const job of hlsJobs.values()) {
    try {
      if (job.child && job.child.exitCode === null) job.child.kill('SIGKILL');
    } catch (e) { /* already gone */ }
  }
});

// An HLS job nobody asks for any more is a running ffmpeg and a directory of segments: both must go.
const sweep = setInterval(() => {
  const now = Date.now();
  for (const [id, job] of hlsJobs) {
    if (now - job.lastSeen < HLS_IDLE_MS) continue;
    if (job.child) killChild(job.child, 'idle');
    hlsJobs.delete(id);
    try { fs.rmSync(job.dir, { recursive: true, force: true }); } catch (e) { /* best effort */ }
    log('REMUX END', 'id=' + id, 'mode=hls', 'cause=idle', 'after=' + (now - job.started) + 'ms');
  }
}, 30000);
if (sweep.unref) sweep.unref();

function status() {
  return {
    bin: FFMPEG_BIN,
    binFound: binSeen.found,
    binVersion: binSeen.version,
    binNote: binSeen.note,
    active: children.size,
    max: MAX_CHILDREN,
    rejected,
    hlsJobs: hlsJobs.size,
    running: Array.from(children.entries()).map(([id, c]) => ({
      id, mode: c.mode, audio: c.plan, hash: (c.hash || '-').slice(0, 12),
      liveMs: Date.now() - c.started, url: String(c.url).slice(0, 96),
    })),
    limits: { STALL_MS, AUDIO_KBPS, HLS_SEGMENT_SEC, HLS_WINDOW, HLS_IDLE_MS },
  };
}

checkBin();

module.exports = {
  serve, subtitle, hlsUrl, hlsServe, mp4Url, status, checkBin,
  parseEngineUrl, streamIndex, planFor,
  limits: { MAX_CHILDREN, STALL_MS, AUDIO_KBPS },
};
