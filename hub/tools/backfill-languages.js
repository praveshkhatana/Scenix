#!/usr/bin/env node
'use strict';
/*
 * tools/backfill-languages.js — fill data/languages.json in one pass, so the language rule can judge the
 * catalogue instead of shrugging at it.
 *
 * Why this exists. The owner's language rule is hard, but an addon catalog publishes almost nothing per
 * tile, so nearly every catalogued title arrives unclassifiable and the rule has to keep what it cannot
 * judge. Measured, not assumed: 1529 catalogued titles, none with a language; asking the hub for the meta
 * of 60 answered a language for zero of them. So the billboard filled with cinema the household will
 * never watch — not a filter problem, a knowledge problem.
 *
 * Which source actually answers, found out by testing rather than by hoping:
 *   - the addon catalog: nothing per tile;
 *   - the addon's own meta record: no language field for a series, and one for a movie only sometimes;
 *   - Wikidata P407 "language of work": free, uncapped, and sparse in practice — 4 titles out of 400 here;
 *   - TMDB `original_language`, plus `spoken_languages` when a title bothers to list them: present almost
 *     always, and already resolved — 714 of the records in data/ratings.json carry a tmdbId, so the answer
 *     is one call away with no search and no quota spent on OMDb, whose free tier is precious for the
 *     certificate ladder.
 * So this pass walks the ratings records and asks TMDB by the id we already hold. `--wikidata` runs the
 * bulk pass too: uncapped and free, and it teaches a few titles nothing else will.
 *
 * Run it on the hub box, after a deploy:
 *
 *     cd ~/tvhub && node tools/backfill-languages.js
 *     node tools/backfill-languages.js --limit=200 --sleep=250
 *     node tools/backfill-languages.js --wikidata
 *
 * Resumable and idempotent: a title already answered is skipped, every batch is on disk before the next
 * is asked, and a title positively answered with no language is remembered as answered, so it is never
 * asked again. A title whose lookup simply failed is left unasked, so the next run tries it again.
 * Bounded by default and sleeping between queries, because this is a background chore and not a reason to
 * make the family's playback wait. Then restart the hub: a running process read the file once, at boot.
 *
 * It prints coverage before and after. That number is the honest measure of how much of the language rule
 * this box can actually enforce — near zero on a fresh install, and after a pass the billboard stops
 * offering cinema the household will never watch.
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const argv = process.argv.slice(2);
const flag = (name) => argv.includes(`--${name}`);
const opt = (name, dflt) => {
  const hit = argv.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.split('=').slice(1).join('=') : dflt;
};
const LIMIT = Number(opt('limit', process.env.LIMIT || 800));
const SLEEP = Number(opt('sleep', process.env.SLEEP_MS || 250));
const BATCH = Number(opt('batch', 200));
const log = (...a) => console.log(...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const langdb = require(path.join(ROOT, 'core', 'langdb'));

const readJson = (f, dflt) => {
  try { return JSON.parse(fs.readFileSync(f, 'utf8')) || dflt; } catch { return dflt; }
};

/** TMDB by the id this box already resolved. One call, no search, no OMDb quota. */
async function tmdbPass(queue, records, key) {
  let learned = 0, answered = 0, failed = 0, done = 0;
  for (const identity of queue) {
    const rec = records[identity];
    if (!rec || !rec.tmdbId) continue;
    const kind = rec.type === 'series' ? 'tv' : 'movie';
    const url = `https://api.themoviedb.org/3/${kind}/${rec.tmdbId}?language=en-US&api_key=${encodeURIComponent(key)}`;
    let body = null;
    try {
      const res = await fetch(url, { headers: { 'user-agent': 'ScenixHub/1.0 (language backfill)' } });
      if (res.ok) body = await res.json();
    } catch { /* transient: leave the title unasked so the next run tries again */ }
    done++;
    if (!body || body.success === false || (!body.original_language && !Array.isArray(body.spoken_languages))) {
      failed++;
    } else {
      answered++;
      const codes = [
        body.original_language,
        ...(Array.isArray(body.spoken_languages) ? body.spoken_languages.map((l) => l.iso_639_1) : []),
      ];
      // A record that exists but states no language is an answer, not a failure: it is what stops the
      // next run from spending a call on the same title.
      if (!langdb.learnFromCodes(identity, codes)) langdb.learn(identity, []);
      if ((langdb.peek(identity) || []).length) learned++;
    }
    if (done % 100 === 0) log(`TMDB ${done}/${queue.length} looked up · ${learned} with a language · ${failed} unusable`);
    if (SLEEP) await sleep(SLEEP);
  }
  langdb.flush();
  log(`TMDB pass: ${answered} records read, ${learned} newly classified, ${failed} that stated nothing usable`);
}

/** Wikidata bulk: free and uncapped, sparse on this property, and worth a query every few hundred titles. */
async function wikidataPass(queue) {
  const bulk = require(path.join(__dirname, 'lib', 'wikidata_bulk.js'));
  await bulk.run(queue, {
    batch: BATCH,
    sleepMs: 1500,
    log,
    onBatch: (rows) => {
      for (const [identity, row] of Object.entries(rows)) {
        const names = row['#langs'];
        delete row['#langs']; // the certificate ladder works in country codes and must not see this
        if (Array.isArray(names) && names.length) langdb.learnFromNames(identity, names);
      }
      langdb.flush();
    },
  });
}

async function main() {
  const library = readJson(path.join(ROOT, 'data', 'library.json'), {});
  const store = readJson(path.join(ROOT, 'data', 'ratings.json'), {});
  const records = store.ratings || {};
  const keys = readJson(path.join(ROOT, 'data', 'keys.json'), {});

  const all = Object.keys(library).filter((id) => /^imdb:tt/i.test(id));
  // Already answered — including "answered with nothing" — is skipped; that is what makes this resumable.
  const todo = all.filter((id) => langdb.peek(id) === null);
  const withTmdb = todo.filter((id) => records[id] && records[id].tmdbId);
  const queue = LIMIT > 0 ? withTmdb.slice(0, LIMIT) : withTmdb;

  const before = langdb.coverage();
  log(`catalogue ${Object.keys(library).length} titles, ${all.length} with an IMDb id, ${withTmdb.length} with a TMDB id already resolved`);
  log(`asked ${before.asked} · classified ${before.classified} · asking for ${queue.length} now`);
  if (!keys.tmdb) log('no TMDB key in data/keys.json — nothing to ask');
  else if (queue.length) await tmdbPass(queue, records, keys.tmdb);

  // Titles with no TMDB id resolved were missed by the pass above. Asking the certificate ladder for them
  // re-runs the lookups the ratings store had written off as "asked, nothing stated" — and the ladder's own
  // answers now carry the language, so one call fills both stores and resolves the TMDB id for later. It
  // passes `force` because a tool that asks on purpose is allowed past the cap the live path respects; this
  // run is bounded by --limit and sleeps between questions instead.
  if (flag('lookup')) {
    const ratings = require(path.join(ROOT, 'core', 'ratings.js'));
    const rest = all.filter((id) => langdb.peek(id) === null).slice(0, LIMIT);
    log(`ladder: ${rest.length} titles never answered`);
    let taught = 0;
    for (const id of rest) {
      try { await ratings.lookup(id, {}, { force: true, reask: true }); } catch { /* no record, no quota, transient */ }
      if ((langdb.peek(id) || []).length) taught++;
      if (SLEEP) await sleep(SLEEP);
    }
    langdb.flush();
    log(`ladder pass: ${taught} of ${rest.length} newly classified`);
  }

  if (flag('wikidata')) {
    const rest = all.filter((id) => langdb.peek(id) === null);
    if (rest.length) { log(`Wikidata: ${rest.length} titles still unasked`); await wikidataPass(rest.slice(0, LIMIT)); }
  }

  const after = langdb.coverage();
  log(`done: asked ${after.asked} · classified ${after.classified} · asked with nothing stated ${after.answeredNothing}`);
  log(`(this run: ${after.classified - before.classified} titles newly classified)`);
  log('restart the hub so a running process reloads data/languages.json');
}

main().catch((e) => { log('FAILED', e.message); process.exit(1); });
