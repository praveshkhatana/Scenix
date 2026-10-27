'use strict';
/*
 * core/media.js — what is INSIDE a copy: which languages it carries audio and subtitles in.
 *
 * The addons promise; the file is the proof. A torrent title says "Hindi", the container may hold only
 * English, and the viewer found that out thirty seconds into a film with no way to change it. Nothing
 * in the catalogue metadata can answer "does this copy have Hindi audio, and does it have subtitles",
 * because the community stream list does not carry track information at all — the file does. So the hub
 * asks the file, once, and remembers.
 *
 * The question is asked with ffprobe against the engine's own stream URL
 * (`<engine>/play/<hash>/<fileId>`), not against a downloaded copy: the engine already turns the torrent
 * into a seekable HTTP file, so ffprobe reads the header and the swarm pays for a few megabytes, not the
 * film. Measured on this box (ffmpeg/ffprobe 7.0.2 static, no GPU, i5-8500T):
 *   cold hash   19.2 s   — most of it the engine reopening the torrent, not ffprobe
 *   warm hash   ~1 s     — header only
 * That is why this never sits in front of a Play press: the answer is cached forever in data/tracks.json
 * keyed by `hash:fileId`, warmed in the background off the title page, and read from the cache at play
 * time. A play request never waits more than PLAY_WAIT_MS here, and only for a probe that is already
 * running.
 *
 * What comes back is deliberately small and stable:
 *   audioTracks:    [{ id: 'a:1', language: 'hin', title: 'Hindi DD 5.1', codec: 'eac3',
 *                      channels: 6, default: true }]
 *   subtitleTracks: [{ id: 's:2', language: 'eng', title: null, codec: 'ass', format: 'ass',
 *                      url: '/app/v1/subtitle?u=…&i=2', embedded: true }]
 * `id` is the CONTAINER's own stream index (`ffprobe`'s `index`), never a number of ours: a client that
 * wants to select track `a:3` maps it straight onto its own track group (media3: group index 3-1), and a
 * remux maps it with `-map 0:3`. Renumbering here would break both.
 * `language` is ISO-639-2 as the container states it (`hin`, `eng`, `pan`), or `und` when it states
 * nothing — which is an honest answer, not a failure, and the ranking rule treats those differently.
 * Subtitle `url` goes through the hub (`/app/v1/subtitle?…`), which converts the track to WebVTT, so a
 * browser `<track>` element is never blocked by CORS and the native player is handed the same value.
 *
 * No npm, no framework: child_process + a JSON file with an atomic rename. The binary paths are env
 * driven (FFPROBE_BIN) because the server keeps its static builds in ~/bin.
 */

const { spawn, spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const DATA_DIR = process.env.DATA_DIR || path.join(ROOT, 'data');
const FILE = process.env.TRACKS_FILE || path.join(DATA_DIR, 'tracks.json');

const FFPROBE_BIN = process.env.FFPROBE_BIN || 'ffprobe';
// A cold hash costs the engine a reopen (measured 19 s above), so the child is given room; this is a
// background path, and the play path only ever reads the cache.
const PROBE_TIMEOUT_MS = Number(process.env.MEDIA_PROBE_TIMEOUT_MS || 30000);
const OK_TTL_MS = Number(process.env.MEDIA_TTL_MS || 30 * 24 * 60 * 60 * 1000); // a file does not change
const FAIL_TTL_MS = Number(process.env.MEDIA_FAIL_TTL_MS || 15 * 60 * 1000); // but try a dead end again
const MAX_ENTRIES = Number(process.env.MEDIA_MAX_ENTRIES || 600);
const CONCURRENCY = Math.max(1, Number(process.env.MEDIA_CONCURRENCY || 2));
const PREWARM_MAX = Math.max(0, Number(process.env.MEDIA_PREWARM_MAX || 3));
// A background probe gets a far longer leash than a play-path one, because nobody is waiting on it and
// what it costs is swarm time, not the viewer's patience: measured on this box, a file sitting deep
// inside a cold multi-episode pack took well over half a minute to produce its first byte, yet answered
// instantly afterwards, because that attempt had already dragged the header through the engine. The play
// budget stays short precisely because a person IS waiting there.
const PREWARM_TIMEOUT_MS = Math.max(5000, Number(process.env.MEDIA_PREWARM_TIMEOUT_MS || 90000));
// ...and a failed background probe is not an answer worth keeping as long as a good one: chasing it again
// soon usually costs nothing, since the failed attempt is what warmed the copy.
const FAIL_RETRY_MS = Math.max(15000, Number(process.env.MEDIA_PROBE_RETRY_FAIL_MS || 120000));
// The most a Play press may wait here, and only when a probe is already running. Kept under 1.5 s so it
// can never eat the play budget.
const PLAY_WAIT_MS = Math.min(1200, Number(process.env.MEDIA_PLAY_WAIT_MS || 1200));

/** ISO-639-1 as containers sometimes write it -> the ISO-639-2 codes used everywhere else in the hub. */
const ISO1 = {
  en: 'eng', hi: 'hin', pa: 'pan', ta: 'tam', te: 'tel', ml: 'mal', kn: 'kan', bn: 'ben', mr: 'mar',
  ur: 'urd', gu: 'guj', or: 'ori', as: 'asm', ne: 'nep', si: 'sin', zh: 'chi', ja: 'jpn', ko: 'kor',
  fr: 'fre', de: 'ger', es: 'spa', pt: 'por', it: 'ita', ru: 'rus', ar: 'ara', fa: 'per', pl: 'pol',
  nl: 'dut', tr: 'tur', he: 'heb', sv: 'swe', no: 'nor', da: 'dan', fi: 'fin', cs: 'cze', hu: 'hun',
  uk: 'ukr', ro: 'rum', bg: 'bul', sr: 'srp', hr: 'hrv', sk: 'slo', sl: 'slv', et: 'est', lv: 'lav',
  lt: 'lit', el: 'gre', id: 'ind', ms: 'may', th: 'tha', vi: 'vie', la: 'lat',
};

/** Subtitle codecs a browser can be handed once converted, and what to call them for the UI. */
const TEXT_SUBS = { subrip: 'srt', srt: 'srt', ass: 'ass', ssa: 'ass', webvtt: 'vtt', mov_text: 'vtt', text: 'vtt' };

function langOf(tag) {
  let s = String(tag == null ? '' : tag).trim().toLowerCase();
  if (!s) return 'und';
  const at = s.indexOf('-');
  if (at > 0) s = s.slice(0, at); // 'hin-US' style region tags still name a language
  if (s === 'und' || s === 'unknown' || s === 'mis' || s === 'zxx' || s === 'qaa') return 'und';
  if (ISO1[s]) return ISO1[s];
  return /^[a-z]{3}$/.test(s) ? s : 'und';
}

function titleOf(stream) {
  const t = stream.tags || {};
  const raw = t.title || t.handler_name || t.comment || '';
  const clean = String(raw).replace(/\s+/g, ' ').trim();
  return clean && clean.length <= 60 ? clean : null;
}

/** `/app/v1/subtitle?u=<engine url>&i=<stream index>` — relative, so no host is baked into the cache. */
function subtitleUrl(engineUrl, index) {
  return '/app/v1/subtitle?u=' + encodeURIComponent(engineUrl) + '&i=' + index;
}

/** Turn ffprobe's JSON into the two lists the clients read. Throws on unusable output. */
/**
 * The two numbers a track has, and which one this hub speaks.
 *
 * `streamIndex` is ffprobe's 0-based index into the container's stream list, and it is the number ffmpeg's
 * `-map 0:<n>` wants — so the same value drives a remux and a subtitle extraction. `id` is `a:<streamIndex>`
 * / `s:<streamIndex>`, the handle the client selects a track with.
 *
 * Worth knowing before a track ever fails to switch: a Matroska file also has a *track number* inside it,
 * and a native player may report that instead. Measured on the Spider-Verse copy this box played — ffprobe
 * said audio `index:2`, subtitle `index:0`, and the TV's player called the same two tracks `a:21` and
 * `s:01`, because that is what the Matroska header numbers them (0x15, 0x01). This ffmpeg build exposes no
 * such field, so the hub cannot publish it; a client that keys on its own player's numbering has to map
 * from the ordered list (`audioTracks` comes in stream order), not assume the number in `id` is the same
 * integer its player printed.
 */
function normalize(json, engineUrl) {
  const streams = Array.isArray(json && json.streams) ? json.streams : [];
  const audioTracks = [];
  const subtitleTracks = [];
  for (const st of streams) {
    if (!st || typeof st.index !== 'number') continue;
    const language = langOf(st.tags && st.tags.language);
    const title = titleOf(st);
    const codec = String(st.codec_name || '').toLowerCase() || null;
    const disposition = st.disposition || {};
    if (st.codec_type === 'audio') {
      audioTracks.push({
        id: 'a:' + st.index,
        streamIndex: st.index,
        language,
        title,
        codec,
        channels: Number.isFinite(st.channels) ? st.channels : null,
        default: disposition.default === 1,
      });
    } else if (st.codec_type === 'subtitle') {
      const format = TEXT_SUBS[codec] || null;
      const track = {
        id: 's:' + st.index,
        streamIndex: st.index,
        language,
        title,
        codec,
        format,
        // Text tracks are handed to any player as WebVTT through the hub; bitmap tracks (PGS, VobSub)
        // can only be decoded by a native player, so they get no url and stay selectable by id.
        url: format && engineUrl ? subtitleUrl(engineUrl, st.index) : null,
        embedded: true,
        forced: disposition.forced === 1,
        default: disposition.default === 1,
      };
      subtitleTracks.push(track);
    }
  }
  // What the picture is encoded in. This hub never transcodes video, so a copy whose codec the device
  // cannot decode is not a lower-quality option: it is a black screen with working sound, which is
  // exactly how an H.265 file presents itself to a browser with no HEVC decoder.
  const v = streams.find((st) => st && st.codec_type === 'video');
  const fmt = (json && json.format) || {};
  return {
    audioTracks,
    subtitleTracks,
    video: v ? {
      codec: String(v.codec_name || '').toLowerCase() || null,
      profile: String(v.profile || '').toLowerCase() || null,
      width: Number.isFinite(v.width) ? v.width : null,
      height: Number.isFinite(v.height) ? v.height : null,
    } : null,
    container: String(fmt.format_name || '').split(',')[0] || null,
    durationMs: Number.isFinite(parseFloat(fmt.duration)) ? Math.round(parseFloat(fmt.duration) * 1000) : null,
    sizeBytes: Number.isFinite(Number(fmt.size)) ? Number(fmt.size) : null,
    bitrateKbps: Number.isFinite(Number(fmt.bit_rate)) ? Math.round(Number(fmt.bit_rate) / 1000) : null,
  };
}

// ---- cache -----------------------------------------------------------------------------------------

let state = { entries: {}, updatedAt: null };
let loaded = false;

function load() {
  if (loaded) return state;
  loaded = true;
  try {
    const raw = JSON.parse(fs.readFileSync(FILE, 'utf8'));
    if (raw && raw.entries && typeof raw.entries === 'object') state = raw;
  } catch (e) { /* first run, or a cache we can read as empty */ }
  return state;
}

let saveTimer = null;
function scheduleSave() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    saveNow();
  }, 250);
  if (saveTimer.unref) saveTimer.unref();
}

function saveNow() {
  const s = load();
  const keys = Object.keys(s.entries);
  if (keys.length > MAX_ENTRIES) {
    // Drop the oldest half rather than the whole file: a library watched over months stays warm.
    keys.sort((a, b) => (Number(s.entries[a].at) || 0) - (Number(s.entries[b].at) || 0));
    for (const k of keys.slice(0, keys.length - MAX_ENTRIES)) delete s.entries[k];
  }
  s.updatedAt = new Date().toISOString();
  try {
    fs.mkdirSync(path.dirname(FILE), { recursive: true });
    const tmp = FILE + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(s));
    fs.renameSync(tmp, FILE);
  } catch (e) {
    console.log(new Date().toISOString(), 'TRACKS SAVE FAIL', e.message);
  }
}

const keyOf = (hash, fileId) => String(hash) + ':' + String(fileId);
const inFlight = new Map(); // key -> Promise
let running = 0;
const queue = [];

function fresh(entry) {
  if (!entry || !Number.isFinite(entry.at)) return false;
  const age = Date.now() - Number(entry.at);
  return entry.ok === true ? age < OK_TTL_MS : age < FAIL_TTL_MS;
}

/** The cached answer for this exact copy, or null. Never waits, never spawns. */
function peek(hash, fileId) {
  if (!hash || fileId === undefined || fileId === null) return null;
  const entry = load().entries[keyOf(hash, fileId)];
  return fresh(entry) ? entry : null;
}

function runProbe(hash, fileId, url, timeoutMs) {
  return new Promise((resolve) => {
    let child;
    try {
      child = spawn(FFPROBE_BIN, [
        '-hide_banner', '-loglevel', 'error',
        '-show_streams', '-show_format', '-print_format', 'json',
        url,
      ], { stdio: ['ignore', 'pipe', 'pipe'] });
    } catch (e) {
      return resolve({ ok: false, error: 'cannot start ' + FFPROBE_BIN + ': ' + e.message });
    }
    let out = '';
    let err = '';
    let done = false;
    const kill = (why) => {
      if (done) return;
      done = true;
      try { child.kill('SIGKILL'); } catch (e) { /* already gone */ }
      resolve({ ok: false, error: why });
    };
    const timer = setTimeout(() => kill('ffprobe timed out after ' + timeoutMs + 'ms'), timeoutMs);
    if (timer.unref) timer.unref();
    child.stdout.on('data', (c) => {
      out += c;
      if (out.length > 4e6) kill('ffprobe output too large');
    });
    child.stderr.on('data', (c) => {
      err += c;
      if (err.length > 8000) err = err.slice(0, 8000);
    });
    child.on('error', (e) => {
      clearTimeout(timer);
      if (done) return;
      done = true;
      resolve({ ok: false, error: 'ffprobe failed: ' + (e.code === 'ENOENT'
        ? FFPROBE_BIN + ' not found (set FFPROBE_BIN)' : e.message) });
    });
    child.on('close', (code) => {
      clearTimeout(timer);
      if (done) return;
      done = true;
      if (!out.trim()) {
        return resolve({ ok: false, error: (err.split('\n').filter(Boolean)[0] || 'ffprobe exited with ' + code).slice(0, 200) });
      }
      try {
        resolve({ ok: true, json: JSON.parse(out) });
      } catch (e) {
        resolve({ ok: false, error: 'ffprobe JSON unparseable: ' + e.message });
      }
    });
  });
}

function pump() {
  while (running < CONCURRENCY && queue.length) {
    const job = queue.shift();
    running++;
    Promise.resolve()
      .then(job.run)
      .then(job.done, job.done);
  }
}

/**
 * Ask the file what it holds, once per copy. Concurrent asks for the same copy are one probe; the
 * second caller shares the first one's answer.
 */
function probe(hash, fileId, url, opts = {}) {
  if (!hash || fileId === undefined || fileId === null) return Promise.resolve(null);
  if (!/^https?:\/\//i.test(String(url || ''))) return Promise.resolve(null);
  const key = keyOf(hash, fileId);
  const hit = peek(hash, fileId);
  if (hit && !opts.force) return Promise.resolve(hit);
  if (inFlight.has(key)) return inFlight.get(key);
  const timeoutMs = Number(opts.timeoutMs) > 0 ? Number(opts.timeoutMs) : PROBE_TIMEOUT_MS;
  const p = new Promise((resolve) => {
    const job = {
      run: () => runProbe(hash, fileId, url, timeoutMs),
      done: (r) => {
        running--;
        inFlight.delete(key);
        pump();
        const entry = {
          hash: String(hash),
          fileId,
          url: String(url),
          at: Date.now(),
          ok: Boolean(r && r.ok),
          error: r && r.error ? String(r.error).slice(0, 240) : null,
          ...(r && r.ok ? normalize(r.json, url) : {}),
        };
        if (entry.ok) {
          load().entries[key] = entry;
          scheduleSave();
        } else {
          // Remember the failure briefly so one unreadable copy does not spawn ffprobe per view.
          load().entries[key] = { ...entry, audioTracks: [], subtitleTracks: [] };
          scheduleSave();
        }
        resolve(entry);
      },
    };
    queue.push(job);
    pump();
  });
  inFlight.set(key, p);
  return p;
}

/**
 * The play-path read: the cache if it is there, otherwise the probe already running (waited for up to
 * PLAY_WAIT_MS), otherwise nothing — and the probe is kicked in the background for the next press. A
 * Play request must not pay for ffprobe, so this function never starts one and waits.
 */
/**
 * The freshest entry for a hash, whichever file of it was measured.
 *
 * The play path knows a hash long before the engine has told anyone which file id it will serve — the
 * ranking decision happens before `prepare`, and a torrent is nearly always one video file. Refusing to
 * answer on a hash alone would throw away a measurement the title page already paid for, and the language
 * the file states is exactly the fact that decides the ranking.
 */
function peekByHash(hash) {
  if (!hash) return null;
  const now = Date.now();
  let best = null;
  for (const [k, entry] of Object.entries(load().entries)) {
    if (!k.startsWith(hash + ':') || !fresh(entry)) continue;
    // `at` is when the probe ran. Several entries share a hash in a multi-file torrent, and when the
    // exact file was never probed the newest answer about that torrent is the one worth showing.
    if (!best || (entry.at || 0) > (best.at || 0)) best = entry;
  }
  return best;
}

async function tracksFor(hash, fileId, url, opts = {}) {
  const hit = peek(hash, fileId);
  if (hit) return hit;
  const key = keyOf(hash, fileId);
  const pending = inFlight.get(key);
  const waitMs = Number.isFinite(opts.waitMs) ? Math.min(PLAY_WAIT_MS, Math.max(0, opts.waitMs)) : PLAY_WAIT_MS;
  if (pending && waitMs > 0) {
    const raced = await Promise.race([
      pending.catch(() => null),
      new Promise((resolve) => {
        const t = setTimeout(() => resolve(null), waitMs);
        if (t.unref) t.unref();
      }),
    ]);
    if (raced) return raced;
  }
  if (url) probe(hash, fileId, url).catch(() => {}); // warm for next time, do not wait
  return peek(hash, fileId);
}

/** Background warm: the top few copies of the title someone is looking at. Fire and forget. */
function prewarm(items) {
  const list = (items || []).filter((x) => x && x.hash && x.fileId !== undefined && x.url).slice(0, PREWARM_MAX);
  for (const x of list) {
    const hit = peek(x.hash, x.fileId);
    if (hit && hit.ok !== false) continue; // a real answer is worth keeping for months
    if (hit && Date.now() - Number(hit.at || 0) < FAIL_RETRY_MS) continue;
    // A failure older than the retry window is chased again rather than believed. Every empty entry this
    // hub caches stands between the picker and the languages a copy carries, so giving up on one costs a
    // ranking fact and not just a badge on the shelf.
    probe(x.hash, x.fileId, x.url, { timeoutMs: PREWARM_TIMEOUT_MS, force: true }).catch(() => {});
  }
  return list.length;
}

// ---- binary sanity ----------------------------------------------------------------------------------

let binSeen = { found: null, version: null, at: 0, note: 'not checked' };

function checkBin() {
  let r;
  try {
    r = spawnSync(FFPROBE_BIN, ['-version'], { timeout: 4000, encoding: 'utf8' });
  } catch (e) {
    binSeen = { found: false, version: null, at: Date.now(), note: e.message };
    return binSeen;
  }
  if (!r || r.error || r.status !== 0) {
    binSeen = {
      found: false, version: null, at: Date.now(),
      note: (r && r.error && r.error.code === 'ENOENT')
        ? FFPROBE_BIN + ' not found on PATH (set FFPROBE_BIN)' : 'ffprobe -version failed',
    };
    return binSeen;
  }
  const first = String(r.stdout || '').split('\n')[0] || '';
  binSeen = { found: true, version: first.replace(/^ffprobe version /, '').slice(0, 40), at: Date.now(), note: '' };
  return binSeen;
}

function status() {
  const s = load();
  let ok = 0;
  let failed = 0;
  let withAudio = 0;
  let stated = 0;
  for (const e of Object.values(s.entries)) {
    if (e.ok === true) ok++;
    else failed++;
    const a = Array.isArray(e.audioTracks) ? e.audioTracks : [];
    if (a.length) withAudio++;
    if (a.some((t) => t.language && t.language !== 'und')) stated++;
  }
  return {
    file: FILE,
    bin: FFPROBE_BIN,
    binFound: binSeen.found,
    binVersion: binSeen.version,
    binNote: binSeen.note,
    entries: Object.keys(s.entries).length,
    readable: ok,
    unreadable: failed,
    withAudioTracks: withAudio,
    withStatedLanguage: stated,
    running,
    queued: queue.length,
    updatedAt: s.updatedAt || null,
    limits: { PROBE_TIMEOUT_MS, PREWARM_TIMEOUT_MS, FAIL_RETRY_MS, OK_TTL_MS, FAIL_TTL_MS, MAX_ENTRIES, CONCURRENCY, PREWARM_MAX, PLAY_WAIT_MS },
  };
}

checkBin(); // one spawnSync at boot so /healthz can say whether the binary is there

module.exports = {
  peek, peekByHash, probe, tracksFor, prewarm, status, checkBin,
  langOf, normalize,
  keyOf,
  FILE,
  limits: { PROBE_TIMEOUT_MS, PREWARM_TIMEOUT_MS, FAIL_RETRY_MS, OK_TTL_MS, FAIL_TTL_MS, MAX_ENTRIES, CONCURRENCY, PREWARM_MAX, PLAY_WAIT_MS },
};
