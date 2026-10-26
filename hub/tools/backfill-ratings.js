'use strict';
/*
 * tools/backfill-ratings.js — fill data/ratings.json in one pass, so the home screen never has to.
 *
 * Run it on the hub box, after a deploy:
 *
 *     cd ~/tvhub && node tools/backfill-ratings.js --bulk=wikidata
 *
 * What it walks: every identity the hub has ever seen (data/library.json), plus the items in the rows the
 * family actually scrolls past (top10, justadded,newreleases,punjabiclassics,installed, and the taste
 * rows) fetched from the running hub over HTTP when it answers. Channels are skipped — a live channel has
 * no certificate to find, and asking for one wastes the day's quota.
 *
 * Three modes, and they cost very differently. Pick explicitly; a bare run does the cheap one.
 *
 *   (default) --bulk=wikidata  a few hundred titles per HTTP request, no key, no quota. This is the only
 *                              route that fills a catalogue of thousands in minutes, and the only bulk
 *                              source verified reachable from this box (the BBFC's own bulk dataset is not
 *                              reachable from here at all).
 *   --per-title                one OMDb call plus one TMDB call per title — the only way to get the boards
 *                              that are not on Wikidata. OMDb's free tier is about 1000 lookups a day, so
 *                              this sleeps between calls and stops by itself when core/ratings.js reports
 *                              its daily cap spent. Run it overnight, not while the family is watching.
 *   --recompute                no network at all: re-score every stored record from the boards it already
 *                              holds. That is how a fix to the ladder reaches the titles this box knows.
 *
 * It is resumable and idempotent, so a run can be stopped at any point and started again:
 *   - every batch is on disk before the next one is asked, so an interrupted run keeps what it fetched;
 *   - certificates are unioned into a title's record, never replaced, so asking for the same title twice
 *     changes nothing;
 *   - --only-missing skips titles that already have a board, which is how to continue a big run cheaply.
 *
 * It is bounded: --limit caps how many titles one run touches (default 2000; --limit=0 is the whole
 * catalogue, and you should mean it). This tool never starts an unbounded job against the internet.
 *
 * It prints the coverage before and after, and the tier distribution, so the run is measured rather than
 * described. That number is the honest measure of how good the kids filtering can be on this box.
 */
const fs = require('fs');
const path = require('path');
const http = require('http');

const ROOT = path.join(__dirname, '..');
const DATA_DIR = process.env.DATA_DIR || path.join(ROOT, 'data');

const argv = process.argv.slice(2);
const has = (name) => argv.includes('--' + name);
const opt = (name, fallback) => {
  const hit = argv.find((a) => a.startsWith('--' + name + '='));
  return hit ? hit.split('=').slice(1).join('=') : fallback;
};

const HUB = opt('hub', process.env.HUB_URL || 'http://127.0.0.1:' + (process.env.PORT || 8788));
const ROWS = opt('rows', process.env.ROWS
  || 'top10,justadded,newreleases,punjabiclassics,installed,taste-action,taste-family,taste-animation').split(',');
const PROFILE = opt('profile', process.env.PROFILE || 'raj');
const SLEEP_MS = Number(opt('sleep', process.env.SLEEP_MS || 400));
const BATCH = Number(opt('batch', process.env.BATCH || 200));
const LIMIT = Number(opt('limit', process.env.LIMIT !== undefined ? process.env.LIMIT : 2000));

const ratings = require(path.join(ROOT, 'core', 'ratings'));
const langdb = require(path.join(ROOT, 'core', 'langdb'));

const log = (...a) => console.log(new Date().toISOString(), ...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Only titles that can have a certificate: IMDb-backed movies and series. */
function worthLookingUp(identity) {
  return /^imdb:tt\d{7,10}$/i.test(String(identity));
}

function readJson(file, fallback) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (e) {
    return fallback;
  }
}

/** Ask the running hub for a row's identities. A hub that is not up costs nothing. */
function fetchRow(rowId) {
  return new Promise((resolve) => {
    const req = http.get(HUB + '/app/v1/row/' + encodeURIComponent(rowId) + '?profile=' + encodeURIComponent(PROFILE), { timeout: 12000 }, (res) => {
      let body = '';
      res.on('data', (c) => { body += c; });
      res.on('end', () => {
        try {
          resolve((JSON.parse(body).items || []).map((it) => ({ identity: it.identity, type: it.type, title: it.title, year: it.year })));
        } catch (e) {
          resolve([]);
        }
      });
    });
    req.on('timeout', () => { req.destroy(); resolve([]); });
    req.on('error', () => resolve([]));
  });
}

/** A coverage snapshot, so the run is measured and not described. */
function coverage() {
  const st = ratings.status();
  return {
    entries: st.entries,
    titlesWithABoard: st.withCertificate,
    certificateStrings: st.boardStrings,
    askedNothingStated: st.askedNothing,
    boardsWeCannotRead: st.unknownBoardStrings,
    byTier: st.byTier,
    upstreamCalls: st.upstreamCallsToday + '/' + st.dailyCap,
  };
}

async function main() {
  const doBulk = has('bulk') || (!has('per-title') && !has('recompute')); // the cheap route is the default
  const doPerTitle = has('per-title');
  const doRecompute = has('recompute');
  const bulkSource = opt('bulk', 'wikidata');

  const library = readJson(path.join(DATA_DIR, 'library.json'), {});
  const wanted = new Map();
  for (const identity of Object.keys(library)) {
    if (worthLookingUp(identity)) wanted.set(identity, { identity });
  }
  const fromRows = (await Promise.all(ROWS.map(fetchRow))).flat();
  for (const it of fromRows) if (it && worthLookingUp(it.identity)) wanted.set(it.identity, it);

  let ids = [...wanted.values()];
  if (has('only-missing')) {
    ids = ids.filter((it) => {
      const rec = ratings.peek(it.identity);
      return !rec || !rec.certs || !Object.keys(rec.certs).length;
    });
  }
  const seen = ids.length;
  if (Number.isFinite(LIMIT) && LIMIT > 0 && ids.length > LIMIT) ids = ids.slice(0, LIMIT);

  const before = coverage();
  log('BACKFILL coverage before:', JSON.stringify(before));
  log('BACKFILL', ids.length + '/' + seen, 'titles to look at',
    seen > ids.length ? `(bounded by --limit ${LIMIT}; ${seen - ids.length} left for the next run)` : '(the whole catalogue)',
    '| modes', [doBulk && 'bulk=' + bulkSource, doPerTitle && 'per-title', doRecompute && 'recompute'].filter(Boolean).join(' '),
    '| hub', HUB, '| profile', PROFILE);
  if (!ids.length) {
    log('BACKFILL nothing to do: no IMDb-backed identity is waiting');
    return;
  }

  if (doBulk) {
    if (bulkSource !== 'wikidata') {
      log('BACKFILL unknown bulk source', bulkSource, '- wikidata is the only bulk source verified from this box');
    } else {
      const bulk = require(path.join(__dirname, 'lib', 'wikidata_bulk.js'));
      let titlesGained = 0;
      let boardsAdded = 0;
      await bulk.run(ids.map((it) => it.identity), {
        batch: BATCH,
        sleepMs: Number(opt('bulk-sleep', 1500)),
        log,
        onBatch: (certs) => {
          for (const [identity, cc] of Object.entries(certs)) {
            // The same query answered what language the title is in. Take that out of the certificate
            // bag before the ladder sees it, and file it where the language rule looks.
            const stated = cc['#langs'];
            delete cc['#langs'];
            if (stated) langdb.learnFromNames(identity, stated);
            const prev = ratings.peek(identity);
            const had = prev && prev.certs ? Object.keys(prev.certs).length : 0;
            const rec = ratings.ingest({ identity, certs: cc, sources: ['wikidata'] });
            const now = rec && rec.certs ? Object.keys(rec.certs).length : 0;
            if (now > had) {
              titlesGained++;
              boardsAdded += now - had;
            }
          }
          ratings.flush(); // durable per batch: a stopped run never loses a certificate it already fetched
        },
      });
      log('BACKFILL wikidata: titles that gained at least one certificate', titlesGained, '| certificates added', boardsAdded);
    }
  }

  if (doPerTitle) {
    const keys = ratings.status().keys || [];
    if (!keys.length) log('BACKFILL per-title: no upstream keys in', ratings.KEYS_FILE, '- nothing to ask, continuing');
    let nothingStated = 0;
    for (let i = 0; i < ids.length; i++) {
      const it = ids[i];
      const spentBefore = ratings.status().upstreamCallsToday;
      const rec = await ratings.lookup(it.identity, { type: it.type, title: it.title, year: it.year });
      if (rec && rec.tier == null && rec.unrated) nothingStated++;
      if (rec && rec.tier === 3) log('BACKFILL tier 3:', it.identity, (rec.certs && Object.keys(rec.certs).join(',')) || 'flagged adult');
      if (ratings.status().upstreamCallsToday === spentBefore) continue; // already known, or capped: no sleep needed
      if ((i + 1) % 25 === 0) {
        const st = ratings.status();
        log('BACKFILL per-title', i + 1 + '/' + ids.length, '| titles with a board', st.withCertificate,
          '| upstream calls', st.upstreamCallsToday + '/' + st.dailyCap);
      }
      if (ratings.status().upstreamCallsToday >= ratings.status().dailyCap) {
        log('BACKFILL stopped at the daily cap', ratings.status().dailyCap, '- run it again tomorrow to continue');
        break;
      }
      await sleep(SLEEP_MS);
    }
    if (nothingStated) log('BACKFILL per-title: asked and nothing was stated for', nothingStated, 'titles');
  }

  if (doRecompute) {
    const r = ratings.recomputeAll();
    log('BACKFILL recompute: records', r.entries, '| rescoring changed', r.changed,
      '| boards read', r.boards, '| tiers', JSON.stringify(r.byTier));
  }

  ratings.flush();
  const after = coverage();
  log('BACKFILL coverage after: ', JSON.stringify(after));
  const d = (k) => (typeof after[k] === 'number' && typeof before[k] === 'number' ? after[k] - before[k] : '');
  log('BACKFILL delta: titles with at least one board', d('titlesWithABoard'),
    '| certificate strings stored', d('certificateStrings'),
    '| boards this hub cannot read', d('boardsWeCannotRead'),
    '| tiers', JSON.stringify(before.byTier), '->', JSON.stringify(after.byTier));
}

main().then(() => process.exit(0)).catch((e) => { log('BACKFILL failed:', e.message); process.exit(1); });
