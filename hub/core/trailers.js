'use strict';
/*
 * core/trailers.js — GET /app/v1/trailer/<identity>: a small MP4 the TV can play right now.
 *
 * Nothing here is a business service and no addon is consulted: trailers are looked up on the hub box
 * itself with yt-dlp (the same binary the kids YouTube provider already shells out to), because that is
 * the only component on this network that can turn "Inception (2010)" into a URL a 2015-era TV player
 * will open. The two flags that make YouTube answer at all are both passed, exactly as in
 * providers/kids-yt.js — without a JS runtime the extractor fails outright, and without the android
 * client it fails the bot check:
 *
 *   --js-runtimes node:/usr/bin/node  --extractor-args youtube:player_client=android
 *
 * (The `node:` prefix is not decoration: a bare path is rejected as "unsupported JavaScript runtime".)
 *
 * What is asked for is a *small progressive MP4* capped at TRAILER_MAX_HEIGHT (720p by default), never a
 * 4K remux and never a DASH pair the TV would have to multiplex: a trailer is a preview, and it has to
 * start on Wi-Fi while someone is still deciding.
 *
 * Two caches, because the two halves cost very differently:
 *   which video is the trailer   — found by an `ytsearch` (1-9 s), kept for 30 days
 *   the signed googlevideo URL   — re-resolved when it expires (signed URLs live hours), ~2 s
 * Both live in data/trailers.json, so a restart does not pay for either one twice. Concurrent requests
 * for the same identity share one lookup instead of spawning two yt-dlp processes.
 */

const { execFile } = require('child_process');
const path = require('path');
const fs = require('fs');

const YT_BIN = process.env.YT_BIN || 'yt-dlp';
// The trailer extractor needs a JS runtime; the hub box's node is what the deploy script points this at.
const YT_JS = process.env.YT_JS || '/usr/bin/node';
const YT_CLIENT = process.env.TRAILER_CLIENT || 'android';
const MAX_HEIGHT = Number(process.env.TRAILERS_MAX_HEIGHT || 720);
const BUDGET_MS = Number(process.env.TRAILER_BUDGET_MS || 12000);
const SEARCH_MS = Number(process.env.TRAILER_SEARCH_MS || 5000);
const RESOLVE_MS = Number(process.env.TRAILER_RESOLVE_MS || 5000);
const VIDEO_TTL_MS = Number(process.env.TRAILER_TTL_MS || 30 * 24 * 3600 * 1000); // which video is the trailer
const RETRY_MS = Number(process.env.TRAILER_RETRY_MS || 15 * 60 * 1000); // how long a failure is remembered
const UA = 'Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 GhartvHub/0.1';
const FILE = process.env.TRAILERS_FILE
  || path.join(process.env.DATA_DIR || path.join(__dirname, '..', 'data'), 'trailers.json');

/** Progressive MP4 only, capped at MAX_HEIGHT: a preview has to start on Wi-Fi, not buffer a remux. */
const FORMAT_LADDER = [
  `b[ext=mp4][height<=${MAX_HEIGHT}]/b[ext=mp4][height<=${MAX_HEIGHT}]/b[ext=mp4]`,
  `b[height<=${MAX_HEIGHT}]/b`,
];

let state = { trailers: {} };
let loaded = false;

function load() {
  if (loaded) return;
  loaded = true;
  try {
    const raw = JSON.parse(fs.readFileSync(FILE, 'utf8'));
    if (raw && raw.trailers && typeof raw.trailers === 'object') state = raw;
  } catch (e) {
    if (!e || e.code !== 'ENOENT') console.error(new Date().toISOString(), 'TRAILERS', e.message);
  }
}

let saveTimer = null;
function save() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    const tmp = FILE + '.tmp';
    try {
      fs.mkdirSync(path.dirname(FILE), { recursive: true });
      fs.writeFileSync(tmp, JSON.stringify(state, null, 2));
      fs.renameSync(tmp, FILE);
    } catch (e) {
      console.error(new Date().toISOString(), 'TRAILERS save', e.message);
    }
  }, 500);
  if (saveTimer.unref) saveTimer.unref();
}

function ytExec(args, timeoutMs) {
  return new Promise((resolve, reject) => {
    execFile(YT_BIN, args, { timeout: timeoutMs, maxBuffer: 16 * 1024 * 1024 }, (err, stdout) => {
      if (err) return reject(err);
      resolve(stdout);
    });
  });
}

/** yt-dlp flags that make YouTube answer this box at all — see the header. */
function baseArgs() {
  return ['--no-playlist', '--js-runtimes', 'node:' + YT_JS,
    '--extractor-args', 'youtube:player_client=' + YT_CLIENT];
}

/** Find which video is this title's trailer. One search, ranked, no guessing. */
async function searchVideo(title, year) {
  const q = [title, year, 'official trailer'].filter(Boolean).join(' ');
  const j = JSON.parse(await ytExec(
    [...baseArgs(), '--flat-playlist', '-J', 'ytsearch5:"' + q + '"'], SEARCH_MS,
  ));
  const entries = (j.entries || []).filter((e) => e && e.id);
  const score = (e) => {
    const t = String(e.title || '').toLowerCase();
    let s = 0;
    if (/trailer/.test(t)) s += 100;
    if (/official/.test(t)) s += 15;
    if (/teaser|clip|scene|song|full movie|full film|extended|review|behind the scenes/.test(t)) s -= 70;
    if (Number.isFinite(e.duration)) {
      if (e.duration > 0 && e.duration <= 300) s += 30; // trailers are short
      else if (e.duration > 420) s -= 60;
    }
    return s;
  };
  const best = entries.map((e) => ({ e, s: score(e) })).sort((a, b) => b.s - a.s)[0];
  if (!best || best.s < 0) return null; // nothing that looks like a trailer, rather than a wrong clip
  return { videoId: best.e.id, videoTitle: best.e.title || null, score: best.s };
}

/** Resolve the signed MP4 for a video id, down the ladder until one yields a single playable file. */
async function resolveUrl(videoId) {
  const watch = 'https://www.youtube.com/watch?v=' + videoId;
  const tries = [];
  for (const fmt of FORMAT_LADDER) {
    for (const client of [YT_CLIENT, 'web_safari']) {
      try {
        const info = JSON.parse(await ytExec(
          ['--no-playlist', '--js-runtimes', 'node:' + YT_JS,
            '--extractor-args', 'youtube:player_client=' + client, '-f', fmt, '-J', watch], RESOLVE_MS,
        ));
        if (!info || !/^https?:\/\//i.test(String(info.url || ''))) continue;
        const h = info.http_headers || {};
        return {
          url: info.url,
          headers: {
            'User-Agent': h['User-Agent'] || UA,
            Referer: h.Referer || 'https://www.youtube.com/',
          },
          kind: 'http',
          quality: info.height ? info.height + 'p' : null,
          durationMs: Number.isFinite(info.duration) ? Math.round(info.duration * 1000) : null,
          sizeBytes: Number.isFinite(info.filesize) ? info.filesize : null,
          expiresAt: Date.now() + 2 * 3600 * 1000, // signed URLs live ~6 h; re-resolve after 2
          client,
        };
      } catch (e) {
        tries.push(client + ': ' + String(e.message || e).slice(0, 70));
      }
    }
  }
  return { error: tries[0] || 'no playable format' };
}

const inflight = new Map(); // identity -> promise, so two TVs asking at once share one lookup

/**
 * The trailer for a title. `known` is { title, year, type } from the hub's own metadata, which is why
 * the route resolves the identity first: a trailer is searched for by name, and the hub's item is the
 * only place a name is stated.
 */
async function forTitle(identity, known) {
  load();
  const title = known && known.title;
  if (!title) return null;
  const year = known && known.year ? String(known.year) : null;
  const rec = state.trailers[String(identity)] || null;
  const now = Date.now();

  // A live signed URL is the fast path: no yt-dlp at all.
  if (rec && rec.url && Number(rec.expiresAt) > Date.now() + 30000) {
    return { ...rec, cached: true };
  }
  // A failed lookup is not retried on every tap: a missing or blocked yt-dlp does not fix itself in a
  // minute, and each attempt costs seconds of someone's detail page. TRAILER_RETRY_MS changes the wait.
  if (rec && !rec.url && rec.error && now - Number(rec.foundAt || 0) < RETRY_MS) {
    return { ...rec, cached: true };
  }

  const key = String(identity);
  if (inflight.has(key)) return inflight.get(key);

  const run = (async () => {
    let rec = state.trailers[key] || {};
    // Still need the video id (the first, slow half) if it is missing or the record is old.
    if (!rec.videoId || now - Number(rec.foundAt || 0) > VIDEO_TTL_MS) {
      let found = null;
      let why = null;
      try {
        found = await searchVideo(title, year);
      } catch (e) {
        why = String(e.message || e).slice(0, 120); // yt-dlp missing, blocked, or out of time
      }
      if (!found) {
        state.trailers[key] = {
          ...rec, title, year, foundAt: now, url: null,
          error: why || 'no trailer found',
        };
        save();
        return { ...state.trailers[key], error: why || 'no trailer found' };
      }
      rec = { title, year, videoId: found.videoId, videoTitle: found.videoTitle, foundAt: now };
    }
    const resolved = await resolveUrl(rec.videoId);
    if (!resolved || !resolved.url) {
      const why = (resolved && resolved.error) || 'no playable url';
      state.trailers[key] = { ...rec, url: null, error: why };
      save();
      return state.trailers[key]; // the route reports this reason instead of blaming the clock
    }
    const out = {
      ...rec,
      url: resolved.url,
      headers: resolved.headers,
      kind: resolved.kind,
      quality: resolved.quality,
      durationMs: resolved.durationMs,
      sizeBytes: resolved.sizeBytes,
      expiresAt: resolved.expiresAt,
      error: null,
    };
    state.trailers[key] = out;
    save();
    return out;
  })().finally(() => inflight.delete(key));

  inflight.set(key, run);
  return run;
}

/**
 * What this hub already knows about an identity's trailer, with no network and no yt-dlp: enough to
 * answer a repeat request (the app asks again after the URL expires) without a metadata round trip.
 */
function peek(identity) {
  load();
  const rec = state.trailers[String(identity)];
  if (!rec) return null;
  return {
    title: rec.title || null,
    year: rec.year || null,
    videoId: rec.videoId || null,
    url: rec.url && Number(rec.expiresAt) > Date.now() + 30000 ? rec.url : null,
    headers: rec.url && Number(rec.expiresAt) > Date.now() + 30000 ? rec.headers : null,
    kind: rec.kind || null,
    expiresAt: rec.expiresAt || null,
    quality: rec.quality || null,
    durationMs: rec.durationMs || null,
    sizeBytes: rec.sizeBytes || null,
    error: rec.error || null,
  };
}

function status() {
  load();
  const list = Object.values(state.trailers);
  return {
    file: FILE,
    cached: list.length,
    withUrl: list.filter((t) => t.url).length,
    live: list.filter((t) => t.url && Number(t.expiresAt) > Date.now()).length,
    maxHeight: MAX_HEIGHT,
    budgetMs: BUDGET_MS,
    bin: YT_BIN,
    jsRuntime: 'node:' + YT_JS,
  };
}

module.exports = {
  forTitle, peek, status,
  TIME_BUDGET_MS: BUDGET_MS,
  clear: () => { state = { trailers: {} }; loaded = true; },
  FILE,
};
