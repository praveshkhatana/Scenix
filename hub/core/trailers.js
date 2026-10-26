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
 *
 * The first half does not have to be searched for at all. TMDB states, per title, which YouTube clip *is*
 * its official trailer — one metadata call instead of a search that has to guess. `tools/backfill-trailers.js`
 * fills that half for the whole library in one bounded pass, and this module accepts a record whose videoId
 * came from there (`source: "tmdb"`) exactly like one it found itself: the expensive, stable knowledge is
 * "which clip", and the cheap, perishable knowledge is the signed URL. A title whose id came from TMDB
 * resolves in ~2 s on first focus instead of up to 9.
 *
 * data/trailers.json has two writers on purpose (the hub, and the backfill tool run by hand), so the file is
 * re-read on a clock before anything is read from or written to it, and a record is merged half by half —
 * a running hub must not answer from, or overwrite, a stale copy of what the other writer learned. Same
 * discipline as core/ratings.js.
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
// How long "this title has no trailer upstream" is believed. A backfill that asked TMDB and got nothing
// should not be re-asked by a person pressing the same tile tomorrow; a live yt-dlp failure is retried much
// sooner (RETRY_MS above) because that failure is usually this box, not the title.
const MISS_TTL_MS = Number(process.env.TRAILER_MISS_TTL_MS || 7 * 24 * 3600 * 1000);
const UA = 'Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 ScenixHub/1.0';
const FILE = process.env.TRAILERS_FILE
  || path.join(process.env.DATA_DIR || path.join(__dirname, '..', 'data'), 'trailers.json');

/** Progressive MP4 only, capped at MAX_HEIGHT: a preview has to start on Wi-Fi, not buffer a remux. */
const FORMAT_LADDER = [
  `b[ext=mp4][height<=${MAX_HEIGHT}]/b[ext=mp4][height<=${MAX_HEIGHT}]/b[ext=mp4]`,
  `b[height<=${MAX_HEIGHT}]/b`,
];

let state = { trailers: {} };
let loaded = false;
let diskStamp = null; // mtime of the copy of data/trailers.json we are holding
let diskCheckedAt = 0;

function statMtime(f) {
  try { return fs.statSync(f).mtimeMs; } catch (e) { return null; }
}

/**
 * Merge in whatever another writer put in the file since we last read it. There are two writers of this
 * file on purpose — the hub, and `tools/backfill-trailers.js` run by hand in another process — and a hub
 * that has been up since this morning knows nothing about what the other one learned, so without this the
 * next trailer anyone watches would write the whole file back from memory and quietly delete the backfill.
 * Throttled to once a second because `peek()` runs over every tile of a home screen.
 */
function refreshFromDisk(force = false) {
  const now = Date.now();
  if (!force && now - diskCheckedAt < 1000) return;
  diskCheckedAt = now;
  const stamp = statMtime(FILE);
  if (!stamp || stamp === diskStamp) return;
  diskStamp = stamp;
  try {
    const raw = JSON.parse(fs.readFileSync(FILE, 'utf8'));
    const theirs = (raw && raw.trailers) || {};
    for (const [key, rec] of Object.entries(theirs)) state.trailers[key] = unionRecord(state.trailers[key], rec);
  } catch (e) {
    if (!e || e.code !== 'ENOENT') console.error(new Date().toISOString(), 'TRAILERS refresh', e.message);
  }
}

/**
 * Two records for one title, merged half by half, because the two halves answer different questions with
 * different clocks: *which clip is the trailer* (stable — a newer find wins, and a record that found
 * nothing never erases an id someone did find) and *the signed copy of it* (perishable — whichever has more
 * life left wins, whoever wrote it).
 */
function unionRecord(mine, theirs) {
  if (!mine) return theirs;
  if (!theirs) return mine;
  const out = { ...mine };
  const newer = Number(theirs.foundAt || 0) >= Number(mine.foundAt || 0);
  if (theirs.videoId && (newer || !out.videoId)) {
    out.videoId = theirs.videoId;
    out.videoTitle = theirs.videoTitle || out.videoTitle;
    out.source = theirs.source || out.source;
    out.error = null;
  } else if (!out.videoId && newer && (theirs.error || theirs.retryAfter)) {
    out.error = theirs.error || out.error;
    out.retryAfter = theirs.retryAfter || out.retryAfter;
  }
  if (theirs.title) out.title = theirs.title;
  if (theirs.year) out.year = theirs.year;
  out.foundAt = Math.max(Number(out.foundAt || 0), Number(theirs.foundAt || 0)) || out.foundAt || null;
  if (theirs.url && Number(theirs.expiresAt || 0) > Number(out.expiresAt || 0)) {
    out.url = theirs.url;
    out.headers = theirs.headers;
    out.kind = theirs.kind;
    out.quality = theirs.quality;
    out.durationMs = theirs.durationMs;
    out.sizeBytes = theirs.sizeBytes;
    out.expiresAt = theirs.expiresAt;
  }
  return out;
}

function load() {
  if (!loaded) {
    loaded = true;
    try {
      const raw = JSON.parse(fs.readFileSync(FILE, 'utf8'));
      if (raw && raw.trailers && typeof raw.trailers === 'object') state = raw;
      diskStamp = statMtime(FILE);
    } catch (e) {
      if (!e || e.code !== 'ENOENT') console.error(new Date().toISOString(), 'TRAILERS', e.message);
    }
  }
  refreshFromDisk();
}

let saveTimer = null;
function save(writeNow = false) {
  if (!writeNow && saveTimer) return;
  const write = () => {
    saveTimer = null;
    // Read first, then write: whoever else touched this file while we were deciding (the backfill tool, or
    // a second hub process) is merged in rather than overwritten.
    refreshFromDisk(true);
    const tmp = FILE + '.tmp';
    try {
      fs.mkdirSync(path.dirname(FILE), { recursive: true });
      fs.writeFileSync(tmp, JSON.stringify(state, null, 2));
      fs.renameSync(tmp, FILE);
      diskStamp = statMtime(FILE);
    } catch (e) {
      console.error(new Date().toISOString(), 'TRAILERS save', e.message);
    }
  };
  if (writeNow) {
    if (saveTimer) { clearTimeout(saveTimer); saveTimer = null; }
    write();
    return;
  }
  saveTimer = setTimeout(write, 500);
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
          // What the clip IS rather than how it is carried: the url is a direct copy of a YouTube video and
          // needs the headers above replayed, and `videoId` is in the same record for a client that would
          // rather use its own YouTube player. The transport is obvious from the url itself.
          kind: 'youtube',
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
  // A title the backfill asked TMDB about, and TMDB has no trailer for, is not asked again for MISS_TTL_MS.
  // This is the one case where "we know" is a fact about the title rather than a failure of this box, so it
  // is allowed to be believed for a week.
  if (rec && !rec.videoId && rec.error && now < Number(rec.retryAfter || 0)) {
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
      rec = {
        title, year, videoId: found.videoId, videoTitle: found.videoTitle,
        foundAt: now, source: 'ytsearch',
      };
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

/**
 * The raw record for an identity, including the parts `peek()` hides (which half came from where, and how
 * long a "no trailer" answer is still believed). For the one caller that has to decide whether a title is
 * worth a call at all: tools/backfill-trailers.js.
 */
function record(identity) {
  load();
  return state.trailers[String(identity)] || null;
}

/**
 * Take in a trailer finding from another writer without going near the network or yt-dlp: the backfill
 * tool's normal output. A half-answer is an ordinary thing to receive — TMDB may know which clip is the
 * trailer while this box has never fetched its URL — and `unionRecord` is what stops one half from
 * destroying the other. `entry.retryAfterMs` says how long a "nothing upstream" answer should be believed.
 */
function ingest(entry) {
  load();
  const key = String((entry && entry.identity) || '');
  if (!key) return null;
  const rec = {
    title: (entry && entry.title) || null,
    year: (entry && entry.year) || null,
    videoId: (entry && entry.videoId) || null,
    videoTitle: (entry && entry.videoTitle) || null,
    tmdbId: (entry && entry.tmdbId) || null,
    source: (entry && entry.source) || 'manual',
    foundAt: Date.now(),
  };
  if (!rec.videoId) {
    rec.error = (entry && entry.error) || 'no trailer upstream';
    rec.retryAfter = Date.now() + Number((entry && entry.retryAfterMs) || MISS_TTL_MS);
  }
  const merged = unionRecord(state.trailers[key], rec);
  state.trailers[key] = merged;
  save();
  return merged;
}

function status() {
  load();
  const list = Object.values(state.trailers);
  return {
    file: FILE,
    cached: list.length,
    // `withVideoId` is the coverage number: it says how many titles can answer "show me the preview" with a
    // 2-second resolve instead of a 1-9 second search. `withUrl` only says how many are warm right now.
    withVideoId: list.filter((t) => t.videoId).length,
    withUrl: list.filter((t) => t.url).length,
    live: list.filter((t) => t.url && Number(t.expiresAt) > Date.now()).length,
    fromTmdb: list.filter((t) => t.source === 'tmdb' && t.videoId).length,
    searched: list.filter((t) => t.source === 'ytsearch' && t.videoId).length,
    misses: list.filter((t) => !t.videoId && t.error).length,
    maxHeight: MAX_HEIGHT,
    budgetMs: BUDGET_MS,
    bin: YT_BIN,
    jsRuntime: 'node:' + YT_JS,
  };
}

module.exports = {
  forTitle, peek, record, ingest, status,
  TIME_BUDGET_MS: BUDGET_MS,
  VIDEO_TTL_MS, RETRY_MS, MISS_TTL_MS,
  flush: () => save(true),
  clear: () => { state = { trailers: {} }; loaded = true; diskStamp = null; },
  FILE,
};
