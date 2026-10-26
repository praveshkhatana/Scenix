'use strict';
/*
 * tools/backfill-trailers.js — fill data/trailers.json from TMDB, once per title, instead of on someone's
 * remote.
 *
 * Why this exists. The app previews a title while it has focus (`Preview.kt` asks
 * `GET /app/v1/trailer/<identity>`), and the hub answers that by asking YouTube which clip belongs to the
 * title — 1 to 9 seconds of yt-dlp, on the viewer's clock, the first time anyone asks for that title. For a
 * library of 1326 items that is the same as the feature being switched off: only the handful of titles
 * somebody had already focused had ever been answered. TMDB states the same fact in one metadata call —
 * `/movie/{id}/videos` carries the title's official trailer as a YouTube id — so this tool asks once per
 * title, in one bounded pass, and stores the id. What stays perishable is only the signed copy of the clip,
 * which core/trailers.js mints in ~2 s on demand: that half expires within hours and would be wasted space
 * stored for a week.
 *
 * usage:  node tools/backfill-trailers.js [flags]      (on the hub box, from the hub folder)
 *   --input=ratings|library|both  which titles to walk (default both: ratings carries the TMDB ids, and
 *                                 library.json adds titles the ratings pass has not reached yet)
 *   --limit=N                     stop after N titles this run
 *   --max-calls=N                 stop after N TMDB requests this run (default 1200)
 *   --sleep=MS                    pause between requests (default 250 — this is a free key, and
 *                                 core/ratings.js spends the same one all day)
 *   --find / --no-find            for a title with an IMDb id and no TMDB id, spend one /find call first
 *                                 (default: yes; without it those titles are reported as skipped)
 *   --redo-misses                 re-ask titles TMDB answered "no trailer" for, before their retry-after
 *   --resolve                     also mint the playable URL now (a local cost: ~2 s of yt-dlp per title)
 *   --dry-run                     report what would be asked, ask nothing
 *   --data=DIR                    hub data dir (default: this hub's, or DATA_DIR)
 *
 * Resumable and idempotent by construction, so it is safe to run twice and safe to interrupt. A record in
 * data/trailers.json says what this box knows and when it looked: a title that already has a videoId is
 * never asked again, and a title TMDB said "no trailer" for is not asked again for TRAILER_MISS_TTL_MS
 * (7 days), the same shape core/ratings.js uses for a certificate miss. Ctrl-C flushes the file and the next
 * run carries on where this one stopped. Nothing is ever deleted from the file, so it only grows towards
 * the truth.
 *
 * Which clip wins: type `Trailer` beats `Teaser` beats `Clip`, an official one beats an unofficial one, and
 * a 1080 upload beats 720. The YouTube id is what is stored, not a URL — core/trailers.js caps the *copy* it
 * resolves at TRAILER_MAX_HEIGHT (720p) so a preview starts on Wi-Fi, and choosing the highest-quality
 * source clip is exactly what makes that smaller copy a clean downscale instead of a fuzzy 360p original.
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');

function arg(name, dflt) {
  const hit = process.argv.find((a) => a.startsWith('--' + name + '='));
  return hit === undefined ? dflt : hit.slice(name.length + 3);
}
function flag(name, dflt = true) {
  const neg = process.argv.includes('--no-' + name);
  const pos = process.argv.includes('--' + name);
  if (neg) return false;
  if (pos) return true;
  return dflt;
}

const OPT = {
  input: arg('input', 'both'),
  limit: Number(arg('limit', 0)) || 0,
  maxCalls: Number(arg('max-calls', 1200)) || 1200,
  sleep: Number(arg('sleep', 250)),
  find: flag('find', true),
  redoMisses: flag('redo-misses', false),
  resolve: flag('resolve', false),
  dryRun: flag('dry-run', false),
};

// The same data dir the hub uses, so a run writes where the hub reads. core/trailers.js resolves
// TRAILERS_FILE from DATA_DIR the same way; pointing --data at a directory is how you run this against a
// staging copy without touching the household's file.
const DATA_DIR = arg('data', process.env.DATA_DIR || path.join(ROOT, 'data'));
process.env.DATA_DIR = DATA_DIR;
const trailers = require('../core/trailers');

const KEYS_FILE = fs.existsSync(path.join(DATA_DIR, 'keys.json'))
  ? path.join(DATA_DIR, 'keys.json') : path.join(ROOT, 'data', 'keys.json');
const RATINGS_FILE = path.join(DATA_DIR, 'ratings.json');
const LIBRARY_FILE = path.join(DATA_DIR, 'library.json');

const log = (...a) => console.log(new Date().toISOString(), ...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function readKeys() {
  try { return JSON.parse(fs.readFileSync(KEYS_FILE, 'utf8')); } catch (e) { return {}; }
}

/** Never let a key reach a log line. */
function redact(text) {
  let out = String(text || '');
  for (const v of Object.values(readKeys())) if (typeof v === 'string' && v.length > 3) out = out.split(v).join('<key>');
  return out;
}

// A reset mid-handshake is what api.themoviedb.org does here several times a day (core/ratings.js says the
// same thing): transient, so back off rather than record "this title has no trailer" from a hiccup.
const TRANSIENT = /ECONNRESET|ECONNREFUSED|EPIPE|ETIMEDOUT|EAI_AGAIN|socket hang up|abort|UND_ERR/i;
const TIMEOUT_MS = Number(process.env.TRAILER_BACKFILL_TIMEOUT_MS || 8000);
const RETRIES = 3;

let calls = 0;
async function getJson(url) {
  for (let attempt = 0; attempt < RETRIES; attempt++) {
    if (calls >= OPT.maxCalls) return { tooMany: true };
    calls++;
    const ac = new AbortController();
    const timer = setTimeout(() => ac.abort(), TIMEOUT_MS);
    if (timer.unref) timer.unref();
    try {
      const res = await fetch(url, {
        signal: ac.signal,
        headers: { 'user-agent': 'ScenixHub/0.1 (self-hosted; personal use)', accept: 'application/json' },
      });
      if (res.status === 429) { // TMDB's rate limit: it says when it clears, so wait that long
        const reset = Number(res.headers.get('x-ratelimit-reset-second'));
        await sleep((Number.isFinite(reset) ? reset : 1 + attempt) * 1000 + 250);
        if (attempt + 1 < RETRIES) continue;
        return null;
      }
      if (!res.ok) return null;
      return await res.json();
    } catch (e) {
      const why = String((e && e.cause && e.cause.code) || (e && e.message) || e);
      if (TRANSIENT.test(why) && attempt + 1 < RETRIES) { await sleep(400 * 2 ** attempt); continue; }
      log('UPSTREAM FAIL', redact(url.split('?')[0]), why);
      return null;
    } finally {
      clearTimeout(timer);
    }
  }
  return null;
}

/** TMDB calls this kind 'tv'; the hub, and the app, call it 'series'. */
const kindOf = (type) => (type === 'series' ? 'tv' : 'movie');
const typeOf = (kind) => (kind === 'tv' ? 'series' : 'movie');

/**
 * Which of a title's YouTube clips is its trailer. Order carries the decision: a trailer, then a teaser,
 * then anything else that has a chance of being the thing the studio released for this. An unofficial
 * upload of the same clip is worth less than the official one because it can be a re-encode, and a 1080
 * source is worth more than a 360 one for the reason spelled out in this file's header.
 */
const CLIP_TYPES = ['Trailer', 'Teaser', 'Clip', 'Featurette', 'BehindTheScenes'];
function pickTrailer(results) {
  const yt = (results || []).filter((r) => r && r.site === 'YouTube' && r.key);
  if (!yt.length) return null;
  const rank = (r) => {
    const at = CLIP_TYPES.indexOf(r.type);
    return (at >= 0 ? 100 - at * 10 : 40) + (r.official ? 25 : 0)
      + (Number(r.size) >= 1080 ? 20 : Number(r.size) >= 720 ? 12 : 0);
  };
  return yt.slice().sort((a, b) => rank(b) - rank(a))[0];
}

/**
 * The titles to walk. data/ratings.json is the one that knows what a title IS — its record carries title,
 * year, type and, for most of them, the TMDB id this tool needs. data/library.json is only
 * {identity: addedAt}, so it contributes identities the ratings pass has not reached yet; those are worth
 * taking (a title added yesterday should get a preview today) but they usually need a /find call first.
 */
function wanted() {
  const out = new Map(); // identity -> { identity, title, year, type, tmdbId, imdbId }
  const norm = (rec, from) => {
    if (!rec || !rec.identity) return;
    const id = String(rec.identity);
    if (/^norm:|^yt:|^local:|^channel/.test(id) && !rec.imdbId && !rec.tmdbId) return; // no upstream id to ask with
    if (/^imdb:tt\d+$/.test(id) && !rec.imdbId) rec = { ...rec, imdbId: id.slice(5) };
    out.set(id, { ...(out.get(id) || {}), ...rec, from });
  };
  if (OPT.input !== 'library') {
    try {
      const raw = JSON.parse(fs.readFileSync(RATINGS_FILE, 'utf8'));
      for (const rec of Object.values((raw && raw.ratings) || {})) norm(rec, 'ratings');
    } catch (e) { log('no ratings file:', e.message); }
  }
  if (OPT.input !== 'ratings') {
    try {
      const lib = JSON.parse(fs.readFileSync(LIBRARY_FILE, 'utf8'));
      for (const [identity, addedAt] of Object.entries(lib)) norm({ identity, addedAt }, 'library');
    } catch (e) { log('no library file:', e.message); }
  }
  return [...out.values()];
}

/** Should this title be asked at all? This is the whole "fetch it once" rule, in one function. */
function worthAsking(rec) {
  const stored = trailers.record(rec.identity);
  if (!stored) return { ask: true, why: 'never asked' };
  if (stored.videoId) {
    // The expensive half is already here. With --resolve the cheap half is worth minting now instead of on
    // someone's remote; without it, this title needs no request at all.
    const live = stored.url && Number(stored.expiresAt) > Date.now() + 60000;
    return { ask: false, resolveOnly: !live, why: 'has a videoId' };
  }
  if (!OPT.redoMisses && stored.error && Date.now() < Number(stored.retryAfter || 0)) {
    return { ask: false, why: 'miss believed until ' + new Date(Number(stored.retryAfter)).toISOString() };
  }
  return { ask: true, why: stored.error ? 'miss is old' : 'record has no videoId' };
}

async function findTmdbId(rec) {
  const key = readKeys().tmdb;
  if (!key) return null;
  const found = await getJson(
    `https://api.themoviedb.org/3/find/${encodeURIComponent(rec.imdbId)}` +
    `?external_source=imdb_id&api_key=${encodeURIComponent(key)}`,
  );
  if (!found) return null;
  const want = kindOf(rec.type || 'movie');
  const pools = [found[`${want}_results`] || [], found.movie_results || [], found.tv_results || []];
  for (const pool of pools) {
    const hit = (pool || [])[0];
    if (hit && hit.id) return { id: hit.id, type: hit.media_type ? typeOf(hit.media_type) : rec.type };
  }
  return null;
}

(async () => {
  const key = readKeys().tmdb;
  if (!key && !OPT.dryRun) {
    console.error('no tmdb key in', KEYS_FILE, '— nothing to ask. Add it to data/keys.json (chmod 600).');
    process.exit(2);
  }
  const before = trailers.status();
  const list = wanted();
  const stats = { seen: 0, asked: 0, withTrailer: 0, noTrailer: 0, already: 0, resolved: 0, noId: 0, failed: 0, tooMany: false };
  let stopped = null;
  process.on('SIGINT', () => { stopped = 'SIGINT'; });

  for (const rec of list) {
    if (stopped) break;
    if (OPT.limit && stats.seen >= OPT.limit) break;
    stats.seen++;
    const gate = worthAsking(rec);
    if (!gate.ask) {
      if (OPT.resolve && gate.resolveOnly) {
        await trailers.forTitle(rec.identity, { title: rec.title, year: rec.year, type: rec.type });
        stats.resolved++;
      } else stats.already++;
      continue;
    }
    if (OPT.dryRun) { stats.asked++; continue; }
    if (calls >= OPT.maxCalls) { stats.tooMany = true; stopped = 'max-calls'; break; }

    let tmdbId = rec.tmdbId || null;
    let type = rec.type || 'movie';
    if (!tmdbId) {
      if (rec.imdbId && OPT.find) {
        const hit = await findTmdbId(rec);
        if (hit) { tmdbId = hit.id; type = hit.type || type; }
      }
      if (!tmdbId) { stats.noId++; continue; } // nothing to ask with: not a title TMDB indexes under an id we have
    }

    const v = await getJson(
      `https://api.themoviedb.org/3/${kindOf(type)}/${tmdbId}/videos` +
      `?api_key=${encodeURIComponent(key)}`,
    );
    if (v === null) { stats.failed++; continue; } // upstream hiccup: do not record a miss we did not learn
    if (v && v.tooMany) { stats.tooMany = true; stopped = 'max-calls'; break; }
    const best = pickTrailer(v.results);
    stats.asked++;
    if (best) {
      trailers.ingest({
        identity: rec.identity, title: rec.title || null, year: rec.year || null,
        tmdbId, videoId: best.key, videoTitle: best.name || null, source: 'tmdb',
      });
      stats.withTrailer++;
      if (OPT.resolve) {
        // Mint the signed copy now rather than on someone's remote: ~2 s of yt-dlp, and it expires in a
        // couple of hours, so this is only worth paying for the titles you are about to look at.
        await trailers.forTitle(rec.identity, { title: rec.title, year: rec.year, type });
      }
    } else {
      trailers.ingest({
        identity: rec.identity, title: rec.title || null, year: rec.year || null, tmdbId,
        source: 'tmdb', error: 'no trailer in tmdb', retryAfterMs: trailers.MISS_TTL_MS,
      });
      stats.noTrailer++;
    }
    if (stats.asked % 25 === 0) {
      trailers.flush();
      log('progress', 'asked', stats.asked, 'titles,', stats.withTrailer, 'with a trailer,', calls, 'TMDB calls');
    }
    if (OPT.sleep) await sleep(OPT.sleep);
  }

  trailers.flush();
  const after = trailers.status();
  const pct = (n) => (after.cached ? Math.round((n / after.cached) * 100) : 0) + '%';
  console.log('\n=== backfill-trailers ===');
  console.log('titles considered', stats.seen, '| asked', stats.asked, '| already known', stats.already,
    '| urls minted', stats.resolved, '| no id to ask with', stats.noId, '| upstream failures', stats.failed);
  console.log('TMDB requests', calls, stats.tooMany ? '(stopped at --max-calls: ' + stopped + ')' : stopped ? '(stopped: ' + stopped + ')' : '');
  console.log('found a trailer', stats.withTrailer, '| answered "none exists"', stats.noTrailer);
  console.log('\ndata/trailers.json before: cached', before.cached, 'withVideoId', before.withVideoId,
    'withUrl', before.withUrl, 'misses', before.misses);
  console.log('data/trailers.json after : cached', after.cached, 'withVideoId', after.withVideoId,
    pct(after.withVideoId), 'withUrl', after.withUrl, 'live', after.live,
    '(fromTmdb', after.fromTmdb, '| from ytsearch', after.searched, '| misses', after.misses, ')');
  console.log('file:', after.file);
})().catch((e) => { console.error('backfill-trailers failed:', e.stack || e.message); process.exit(1); });
