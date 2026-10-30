'use strict';
/*
 * core/ladder.js — an opt-in adaptive ladder for one chosen picture.
 *
 * This is deliberately not a packet switch between unrelated addon files. A ladder is a set of
 * renditions of ONE timeline: the same source, transcoded at 1080/720/480 with forced four-second
 * keyframes, so Media3 can move between them at a segment boundary without a decoder state jump.
 *
 * It is opt-in because it costs hub CPU and disk. The default remains the copy-only path (and the
 * exact-copy / lower-copy failover in the player). `adaptive.enabled` in settings is the switch.
 *
 * A ladder job is created only after the picker has already chosen a copy. The master playlist is
 * served once both variants have written their first playlist; the child processes keep producing
 * segments while the player watches. Jobs are cleaned up after the last request or when ffmpeg exits.
 */

const { spawn } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const DATA_DIR = process.env.DATA_DIR || path.join(ROOT, 'data');
const LADDER_DIR = process.env.LADDER_DIR || path.join(DATA_DIR, 'ladder');
const FFMPEG_BIN = process.env.FFMPEG_BIN || 'ffmpeg';
const SEGMENT_SEC = Math.max(2, Number(process.env.LADDER_SEGMENT_SEC || 4));
// How long /play waits for every rendition's first playlist. A 720p source answers in ~5 s; a 1080p
// transcode on a small box needs ~9.5 s, and the old 9 s window meant the API gave up just before the
// ladder was ready and handed the player a direct copy it could not switch. The wait is a ceiling, not
// a delay: a fast source returns as soon as its playlists exist.
const FIRST_PLAYLIST_WAIT_MS = Math.max(3000, Number(process.env.LADDER_FIRST_WAIT_MS || 13000));
// A warming job is reused rather than evicted: pressing Play again on the same copy must not restart
// the transcode from zero, which is how a slow 1080p title could never reach a ready ladder.
const REUSE_WINDOW_MS = Math.max(30000, Number(process.env.LADDER_REUSE_MS || 3 * 60e3));
const IDLE_MS = Math.max(120000, Number(process.env.LADDER_IDLE_MS || 5 * 60e3));

// Highest first. These are ceilings for the transcode, not a statement that every client can decode
// every one: the source quality and the client's own display ceiling choose the slice of this table.
const ALL_VARIANTS = [
  { id: 'v2160', width: 3840, height: 2160, bandwidth: 16000000, codecs: 'avc1.640033,mp4a.40.2', maxrate: '16000k', bufsize: '32000k', audio: '192k' },
  { id: 'v1440', width: 2560, height: 1440, bandwidth: 11000000, codecs: 'avc1.640032,mp4a.40.2', maxrate: '11000k', bufsize: '22000k', audio: '192k' },
  { id: 'v1080', width: 1920, height: 1080, bandwidth: 9000000, codecs: 'avc1.640028,mp4a.40.2', maxrate: '9000k', bufsize: '18000k', audio: '192k' },
  { id: 'v720', width: 1280, height: 720, bandwidth: 4500000, codecs: 'avc1.64001f,mp4a.40.2', maxrate: '4500k', bufsize: '9000k', audio: '128k' },
  { id: 'v480', width: 854, height: 480, bandwidth: 1500000, codecs: 'avc1.4d401e,mp4a.40.2', maxrate: '1500k', bufsize: '3000k', audio: '96k' },
];

function sourceHeight(quality) {
  const q = String(quality || '').toLowerCase();
  if (q.includes('2160') || q.includes('4k')) return 2160;
  if (q.includes('1440')) return 1440;
  if (q.includes('1080')) return 1080;
  if (q.includes('720')) return 720;
  if (q.includes('480')) return 480;
  return 720;
}

function variantsFor(quality, maxHeight) {
  const top = Math.max(480, Math.min(sourceHeight(quality), Number(maxHeight) || 1080));
  // Three rungs at most. For a 1080 source that is 1080/720/480; for a 4K source on a 1080 panel the
  // 2160 rung is not offered because this client cannot draw it. Never upscale.
  const wanted = top >= 2160 ? [2160, 1080, 720]
    : top >= 1440 ? [1440, 720, 480]
      : top >= 1080 ? [1080, 720, 480]
        : top >= 720 ? [720, 480]
          : [480];
  return ALL_VARIANTS.filter((v) => wanted.includes(v.height));
}

const jobs = new Map();

// A hub restart leaves the previous process's directories behind. They are not part of any live job,
// so remove them once at boot rather than letting a 1 GB transcode sit in data/ladder forever.
try {
  if (fs.existsSync(LADDER_DIR)) {
    for (const name of fs.readdirSync(LADDER_DIR)) {
      if (!jobs.has(name)) fs.rmSync(path.join(LADDER_DIR, name), { recursive: true, force: true });
    }
  }
} catch (e) { /* best effort */ }

function log(...a) { console.log(new Date().toISOString(), ...a); }

function masterText(id, variants) {
  const lines = ['#EXTM3U', '#EXT-X-VERSION:7', '#EXT-X-INDEPENDENT-SEGMENTS'];
  for (const v of variants) {
    lines.push(`#EXT-X-STREAM-INF:BANDWIDTH=${v.bandwidth},RESOLUTION=${v.width}x${v.height},CODECS="${v.codecs}"`);
    lines.push(`${v.id}/index.m3u8`);
  }
  return lines.join('\n') + '\n';
}

function headerArgs(headers) {
  const entries = Object.entries(headers || {}).filter(([, v]) => v != null && String(v) !== '');
  if (!entries.length) return [];
  const text = entries.map(([k, v]) => k + ': ' + v).join('\r\n') + '\r\n';
  return ['-headers', text];
}

function spawnVariant(job, v, url, headers, audio) {
  const dir = path.join(job.dir, v.id);
  fs.mkdirSync(dir, { recursive: true });
  const args = [
    '-hide_banner', '-loglevel', 'error', '-nostdin', '-y',
    ...headerArgs(headers),
    '-i', url,
    '-map', '0:v:0',
    // The track the player selected, not whatever happens to be first in the container. A ladder that
    // silently drops the viewer back to the original audio is a worse trade than no ladder.
    '-map', audio && audio.streamIndex != null ? ('0:' + audio.streamIndex + '?') : '0:a:0?',
    ...(audio && audio.language && audio.language !== 'und'
      ? ['-metadata:s:a:0', 'language=' + audio.language] : []),
    '-sn', '-dn',
    '-vf', 'scale=-2:' + v.height,
    '-c:v', 'libx264', '-preset', 'veryfast', '-crf', v.height >= 1080 ? '20' : '22',
    '-maxrate', v.maxrate, '-bufsize', v.bufsize,
    '-force_key_frames', 'expr:gte(t,n_forced*' + SEGMENT_SEC + ')',
    '-sc_threshold', '0',
    '-c:a', 'aac', '-b:a', v.audio, '-ac', '2',
    '-f', 'hls',
    '-hls_time', String(SEGMENT_SEC),
    '-hls_playlist_type', 'event',
    '-hls_list_size', '0',
    '-hls_flags', 'independent_segments+temp_file',
    '-hls_segment_type', 'fmp4',
    '-hls_fmp4_init_filename', 'init.mp4',
    '-hls_segment_filename', path.join(dir, 'seg%05d.m4s'),
    path.join(dir, 'index.m3u8'),
  ];
  let child;
  try {
    child = spawn(FFMPEG_BIN, args, { stdio: ['ignore', 'ignore', 'pipe'] });
  } catch (e) {
    log('LADDER SPAWN FAIL', job.id, v.id, e.message);
    return null;
  }
  let err = '';
  child.stderr.on('data', (c) => { if (err.length < 2000) err += c.toString(); });
  child.on('error', (e) => log('LADDER CHILD ERROR', job.id, v.id, e.code || e.message));
  child.on('close', (code) => {
    job.done++;
    if (code !== 0) log('LADDER EXIT', job.id, v.id, code, err.slice(-240));
  });
  job.children.push(child);
  return child;
}

function ensure(job, url, headers) {
  if (job.started || job.failed) return;
  job.started = true;
  for (const v of job.variants) spawnVariant(job, v, url, headers, job.audio);
}

function waitForFirstPlaylists(job, deadline) {
  return new Promise((resolve) => {
    const check = () => {
      const ready = job.variants.every((v) => fs.existsSync(path.join(job.dir, v.id, 'index.m3u8')));
      if (ready || Date.now() >= deadline) return resolve(ready);
      setTimeout(check, 250);
    };
    check();
  });
}

async function create({ url, headers, quality, audio, maxHeight }) {
  if (!url || !/^https?:/i.test(String(url))) return null;
  // The ladder follows the copy and the panel, not a fixed 720/480 assumption. A 1080 source on a
  // 1080 panel gets 1080/720/480; a 4K source on the same panel gets 1080/720/480 rather than a
  // useless 2160 rung; a 720 source gets 720/480 and is never upscaled.
  const variants = variantsFor(quality, maxHeight);
  if (variants.length < 2) return null; // one rendition is not a ladder
  const wanted = variants.map((v) => v.id).join(',');
  // Same copy, same rungs, still warming: wait on the job that already exists. Starting a new one
  // would kill the ffmpeg that was seconds from producing the playlists.
  for (const [existingId, existing] of jobs) {
    const sameRungs = existing.variants.map((v) => v.id).join(',') === wanted;
    if (existing.url === String(url) && sameRungs && !existing.failed
        && Date.now() - existing.at < REUSE_WINDOW_MS) {
      existing.lastSeen = Date.now();
      const isReady = await waitForFirstPlaylists(existing, Date.now() + FIRST_PLAYLIST_WAIT_MS);
      if (isReady) {
        log('LADDER REUSE', existingId);
        return { id: existingId, ready: true, path: '/app/v1/ladder/' + existingId + '/master.m3u8', variants: variants.map((v) => v.height + 'p') };
      }
      log('LADDER STILL WARMING', existingId);
      return { id: existingId, ready: false, path: '/app/v1/ladder/' + existingId + '/master.m3u8', variants: variants.map((v) => v.height + 'p') };
    }
  }
  // One ladder at a time. Three transcode processes are already a real load; leaving yesterday's
  // title running while a new one is opened is how a hub becomes a space heater.
  for (const [oldId, old] of jobs) {
    for (const c of old.children) { try { c.kill('SIGKILL'); } catch (e) { /* gone */ } }
    try { fs.rmSync(old.dir, { recursive: true, force: true }); } catch (e) { /* best effort */ }
    jobs.delete(oldId);
    log('LADDER EVICT', oldId);
  }
  fs.mkdirSync(LADDER_DIR, { recursive: true });
  const id = crypto.randomBytes(6).toString('hex');
  const dir = path.join(LADDER_DIR, id);
  fs.mkdirSync(dir, { recursive: true });
  const job = {
    id, dir, url: String(url), headers: headers || {}, variants, audio: audio || null,
    started: false, failed: false,
    children: [], done: 0, at: Date.now(), lastSeen: Date.now(),
  };
  jobs.set(id, job);
  fs.writeFileSync(path.join(dir, 'master.m3u8'), masterText(id, variants));
  ensure(job, job.url, job.headers);
  const ready = await waitForFirstPlaylists(job, Date.now() + FIRST_PLAYLIST_WAIT_MS);
  if (!ready) {
    log('LADDER NOT READY', id);
    // Keep the job running: a slow source can still produce a ladder on the next attempt.
    return { id, ready: false, path: '/app/v1/ladder/' + id + '/master.m3u8', variants: variants.map((v) => v.height + 'p') };
  }
  return { id, ready: true, path: '/app/v1/ladder/' + id + '/master.m3u8', variants: variants.map((v) => v.height + 'p') };
}

function fileFor(job, rel) {
  const clean = String(rel || '').replace(/\\/g, '/');
  if (!clean || clean.includes('..')) return null;
  const full = path.join(job.dir, clean);
  if (!full.startsWith(job.dir + path.sep)) return null;
  return full;
}

function contentType(name) {
  if (name.endsWith('.m3u8')) return 'application/vnd.apple.mpegurl';
  if (name.endsWith('.m4s')) return 'video/iso.segment';
  if (name.endsWith('.mp4')) return 'video/mp4';
  return 'application/octet-stream';
}

async function serve(req, res, cors, id, rel) {
  const job = jobs.get(String(id));
  if (!job) return send(res, 404, 'no such ladder');
  job.lastSeen = Date.now();
  if (rel === 'master.m3u8') {
    const ready = await waitForFirstPlaylists(job, Date.now() + FIRST_PLAYLIST_WAIT_MS);
    if (!ready) return send(res, 503, 'ladder warming');
    return file(res, job, 'master.m3u8', cors);
  }
  return file(res, job, rel, cors);
}

function send(res, status, text) {
  res.writeHead(status, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-cache' });
  res.end(text);
}

function file(res, job, rel, cors) {
  const full = fileFor(job, rel);
  if (!full || !fs.existsSync(full) || !fs.statSync(full).isFile()) return send(res, 404, 'not found');
  const body = fs.readFileSync(full);
  res.writeHead(200, Object.assign({
    'Content-Type': contentType(full),
    'Content-Length': body.length,
    'Cache-Control': 'no-cache',
  }, cors || {}));
  res.end(body);
}

function status() {
  return {
    enabled: true,
    jobs: jobs.size,
    bin: FFMPEG_BIN,
    variants: [...new Set([...jobs.values()].flatMap((j) => j.variants.map((v) => v.id)))],
    active: [...jobs.values()].filter((j) => j.started && j.done < j.variants.length).length,
  };
}

/** Kill a job when its parent title is abandoned or the process has been idle. */
function sweep(now = Date.now()) {
  for (const [id, job] of jobs) {
    if (job.done >= job.variants.length || now - job.lastSeen > IDLE_MS) {
      for (const c of job.children) { try { c.kill('SIGKILL'); } catch (e) { /* gone */ } }
      try { fs.rmSync(job.dir, { recursive: true, force: true }); } catch (e) { /* best effort */ }
      jobs.delete(id);
      log('LADDER CLEAN', id, job.done >= job.variants.length ? 'finished' : 'idle');
    }
  }
}
setInterval(() => sweep(), 60000).unref();

module.exports = { create, serve, status, sweep, LADDER_DIR };
