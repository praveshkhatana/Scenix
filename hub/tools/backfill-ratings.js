'use strict';
/*
 * tools/backfill-ratings.js — fill data/ratings.json in one pass, so the home screen never has to.
 *
 * Run it once on the hub box, after a deploy:
 *
 *     cd ~/tvhub && node tools/backfill-ratings.js
 *
 * What it walks: every identity the hub has ever seen (data/library.json), plus the items in the rows the
 * family actually scrolls past (top10, justadded, newreleases, punjabiclassics, installed, and the taste
 * rows) fetched from the running hub over HTTP when it answers. Channels are skipped — a live channel has
 * no certificate to find, and asking for one wastes the day's quota.
 *
 * OMDb's free tier is about 1000 lookups a day, so this sleeps between calls (SLEEP_MS, default 400) and
 * stops by itself when core/ratings.js reports its daily cap spent. It is resumable: a title already in
 * data/ratings.json is never fetched again, so running it tomorrow continues where today stopped.
 *
 * It prints, at the end, exactly how many titles got a real certificate and how many fell back to unrated.
 * That number is the honest measure of how good the kids filtering can be on this box.
 */
const fs = require('fs');
const path = require('path');
const http = require('http');

const ROOT = path.join(__dirname, '..');
const DATA_DIR = process.env.DATA_DIR || path.join(ROOT, 'data');
const SLEEP_MS = Number(process.env.SLEEP_MS || 400);
const HUB = process.env.HUB_URL || 'http://127.0.0.1:' + (process.env.PORT || 8788);
const ROWS = (process.env.ROWS || 'top10,justadded,newreleases,punjabiclassics,installed,taste-action,taste-family,taste-animation').split(',');
const PROFILE = process.env.PROFILE || 'raj';

const ratings = require(path.join(ROOT, 'core', 'ratings'));

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

async function main() {
  const library = readJson(path.join(DATA_DIR, 'library.json'), {});
  const wanted = new Map();
  for (const identity of Object.keys(library)) {
    if (worthLookingUp(identity)) wanted.set(identity, { identity });
  }
  const fromRows = (await Promise.all(ROWS.map(fetchRow))).flat();
  for (const it of fromRows) if (it && worthLookingUp(it.identity)) wanted.set(it.identity, it);

  const ids = [...wanted.values()];
  log('BACKFILL', ids.length, 'titles to look at,', ids.length - fromRows.filter((i) => i && worthLookingUp(i.identity)).length, 'of them only from the library');
  if (!ids.length) {
    log('BACKFILL nothing to do: no IMDb-backed identities in', path.join(DATA_DIR, 'library.json'));
    return;
  }

  let got = 0;
  let rated = 0;
  let unrated = 0;
  let capped = 0;
  for (let i = 0; i < ids.length; i++) {
    const it = ids[i];
    const before = ratings.status().upstreamCallsToday;
    const rec = await ratings.lookup(it.identity, { type: it.type, title: it.title, year: it.year });
    if (rec && rec.tier == null) unrated++;
    else if (rec && rec.sources && rec.sources.length) { got++; if (rec.maturity) rated++; }
    if (rec && rec.tier === 3) log('BACKFILL tier 3:', it.identity, (rec.certs && Object.keys(rec.certs).join(',')) || 'flagged adult');
    if (ratings.status().upstreamCallsToday === before) {
      // Nothing was fetched: either we already had it, or the day's quota is spent.
      capped++;
    }
    if ((i + 1) % 25 === 0) {
      const st = ratings.status();
      log('BACKFILL', i + 1 + '/' + ids.length, 'certificates', got, 'with a stated rating', rated, 'upstream calls today', st.upstreamCallsToday + '/' + st.dailyCap);
    }
    if (ratings.status().upstreamCallsToday >= ratings.status().dailyCap) {
      log('BACKFILL stopped at the daily cap', ratings.status().dailyCap, '- run it again tomorrow to continue');
      break;
    }
    await sleep(SLEEP_MS);
  }

  const st = ratings.status();
  log('BACKFILL done: titles with a record from an upstream', got,
    '| of those, a stated certificate', rated,
    '| looked up but nothing stated', st.askedNothing,
    '| tiers', JSON.stringify(st.byTier),
    '| database entries', st.entries,
    '| upstream calls today', st.upstreamCallsToday + '/' + st.dailyCap,
    '| skipped (already known or capped)', capped);
}

main().then(() => process.exit(0)).catch((e) => { log('BACKFILL failed:', e.message); process.exit(1); });
