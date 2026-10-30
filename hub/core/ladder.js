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
const FIRST_PLAYLIST_WAIT_MS = Math.max(3000, Number(process.env.LADDER_FIRST_WAIT_MS || 9000));
const IDLE_MS = Math.max(120000, Number(process.env.LADDER_IDLE_MS || 5 * 60e3));

const ALL_VARIANTS = [
  { id: 'v1080', width: 1920, height: 1080, bandwidth: 6200000, codecs: 'avc1.640028,mp4a.40.2', maxrate: '6000k', bufsize: '12000k', audio: '192k' },
  { id: 'v720', width: 1280, height: 720, bandwidth: 3200000, codecs: 'avc1.64001f,mp4a.40.2', maxrate: '3000k', bufsize: '6000k', audio: '128k' },
  { id: 'v480', width: 854, height: 480, bandwidth: 1200000, codecs: 'avc1.4d401e,mp4a.40.2', maxrate: '1100k', bufsize: '2200k', audio: '96k' },
];

const jobs = new Map();

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

function spawnVariant(job, v, url, headers) {
  const dir = path.join(job.dir, v.id);
  fs.mkdirSync(dir, { recursive: true });
  const args = [
    '-hide_banner', '-loglevel', 'error', '-nostdin', '-y',
    ...headerArgs(headers),
    '-i', url,
    '-map', '0:v:0', '-map', '0:a:0?',
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
  for (const v of job.variants) spawnVariant(job, v, url, headers);
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

async function create({ url, headers, quality }) {
  if (!url || !/^https?:/i.test(String(url))) return null;
  // One ladder at a time. Three transcode processes are already a real load; leaving yesterday's
  // title running while a new one is opened is how a hub becomes a space heater.
  for (const [oldId, old] of jobs) {
    for (const c of old.children) { try { c.kill('SIGKILL'); } catch (e) { /* gone */ } }
    try { fs.rmSync(old.dir, { recursive: true, force: true }); } catch (e) { /* best effort */ }
    jobs.delete(oldId);
    log('LADDER EVICT', oldId);
  }
  fs.mkdirSync(LADDER_DIR, { recursive: true });
  const q = String(quality || '').toLowerCase();
  // Do not upscale a 720p file to 1080: that spends CPU producing no more detail. The high variant
  // follows the copy the picker actually chose; the rest are lower.
  const variants = q.includes('2160') || q.includes('4k') ? ALL_VARIANTS.slice()
    : q.includes('1080') ? ALL_VARIANTS.slice(0, 2)
      : q.includes('720') ? ALL_VARIANTS.slice(1)
        : ALL_VARIANTS.slice(2);
  if (variants.length < 2) return null; // one rendition is not a ladder
  const id = crypto.randomBytes(6).toString('hex');
  const dir = path.join(LADDER_DIR, id);
  fs.mkdirSync(dir, { recursive: true });
  const job = {
    id, dir, url: String(url), headers: headers || {}, variants, started: false, failed: false,
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
