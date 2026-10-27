'use strict';
/*
 * tools/diagnose-kids.js — why is a kids row empty? Run the real rule pipeline offline and say which
 * rule dropped which title, and on what evidence.
 *
 *     node tools/diagnose-kids.js                    # both kids profiles, all scope:kids rows
 *     node tools/diagnose-kids.js --profile <profile-id> --row kids_series
 *     node tools/diagnose-kids.js --verbose           # list every dropped title with its reason
 *
 * It boots the same plugin set the hub boots (providers, external addons, rules) and then builds each
 * row's candidates exactly as a page view does — dedupe, then the rules in order — counting survivors
 * after every rule. Nothing is fetched from the certificate upstreams: the run is deterministic and
 * measures the DECISION, not the network (RATINGS_DAILY_CAP is forced to 0).
 *
 * Read-only by design: it never writes data/ratings.json.
 */
const path = require('path');

process.env.RATINGS_DAILY_CAP = process.env.RATINGS_DAILY_CAP || '0';
process.env.PROBE_ENABLED = process.env.PROBE_ENABLED || '0';

const ROOT = path.join(__dirname, '..');
const registry = require(path.join(ROOT, 'core', 'registry')).load(ROOT);
const store = require(path.join(ROOT, 'core', 'store')).create(ROOT);
const addons = require(path.join(ROOT, 'core', 'addons'));
const prefs = require(path.join(ROOT, 'core', 'prefs'));
const merge = require(path.join(ROOT, 'core', 'merge'));
const ratings = require(path.join(ROOT, 'core', 'ratings'));
const shelves = require(path.join(ROOT, 'core', 'catalogs'));

const log = (...a) => console.error(new Date().toISOString(), ...a);

function arg(name, fallback) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}
const VERBOSE = process.argv.includes('--verbose');
const WANT_ROW = arg('row', null);
const WANT_PROFILE = arg('profile', null);

for (const p of registry.providers) {
  if (typeof p.start === 'function') { try { p.start(); } catch (e) { /* a cold cache is fine */ } }
}
addons.init(registry, store);

/** The row context, mirroring index.js makeCtx as closely as a tool can. */
function makeCtx(profile) {
  const ctx = {
    profile,
    store,
    providers: registry.byId,
    rules: registry.rules,
    prefs: prefs.resolve(profile),
    days: Number(process.env.DAYS || 45),
    now: Date.now(),
    async catalog(addonId, catalogId, extra = {}) {
      const p = registry.byId[addonId];
      if (!p || typeof p.catalog !== 'function') return [];
      return (await p.catalog(catalogId, extra || {})) || [];
    },
    async library() {
      const all = [];
      for (const p of registry.providers) {
        if (typeof p.catalog !== 'function') continue;
        let cats = [];
        try { cats = (typeof p.catalogs === 'function' ? await p.catalogs() : []) || []; } catch (e) { cats = []; }
        for (const c of cats) all.push(...(await ctx.catalog(p.id, c.id)));
      }
      return merge.dedupe(all, registry.priorities);
    },
    async resolve(identity) {
      const lib = await ctx.library();
      return lib.find((it) => it.identity === identity) || null;
    },
  };
  return ctx;
}

/** One item's evidence, as the rules saw it. */
function evidence(it) {
  const rec = ratings.peek(it.identity);
  const boards = [];
  for (const [country, cert] of Object.entries((rec && rec.certs) || {})) {
    const step = ratings.ladderFor(cert, country);
    if (step) boards.push({ country, cert, minAge: step.minAge, unknown: !!step.unknownBoard });
  }
  boards.sort((a, b) => b.minAge - a.minAge);
  return {
    identity: it.identity,
    title: it.title,
    type: it.type,
    maturity: it.maturity,
    tier: it.tier,
    minAge: it.minAge,
    ratingSource: it._ratingSource || null,
    genres: (it.genres || []).join(','),
    boards: rec && rec.certs ? Object.keys(rec.certs).length : 0,
    hasRecord: !!rec,
    // Which boards actually made the decision — the answer to "why is this hidden from my child".
    drivers: boards.slice(0, 3).map((b) => `${b.country}=${b.cert}${b.unknown ? '?' : ''}@${b.minAge}`).join(' '),
  };
}

async function runRow(row, profile) {
  const ctx = makeCtx(profile);
  const raw = (await row.build(ctx)) || [];
  const deduped = merge.dedupe(raw, registry.priorities);
  const stages = [];
  let alive = deduped.map((it) => it);
  stages.push({ rule: '(candidates)', n: alive.length, dropped: [] });
  for (const rule of registry.rules) {
    const dropped = [];
    const next = [];
    for (const it of alive) {
      let kept;
      try { kept = await rule.apply(it, ctx); } catch (e) { kept = it; }
      if (kept === null || kept === undefined) dropped.push(evidence(it));
      else next.push(kept);
    }
    stages.push({ rule: rule.id, n: next.length, dropped });
    alive = next;
  }
  return { row: row.id, candidates: deduped.length, stages, kept: alive.slice(0, row.max || 80) };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Addon catalogs answer from cache and fetch in the background, exactly as on a page view — so the
 * first pass warms the genre shelves and the second one is the measurement.
 */
async function warmGenreShelves() {
  const ctx = makeCtx(store.profiles()[0] || { id: '-', kind: 'adult' });
  const entries = await shelves.catalogsOf(ctx);
  for (const e of entries) {
    if (!(e.cat.extra || []).includes('genre')) continue;
    for (const g of ['Animation', 'Family', 'Adventure']) {
      try { await e.provider.catalog(e.cat.id, { genre: g }); } catch (err) { /* cold is fine */ }
    }
  }
}

async function main() {
  // Addons mount and start their own cache refresh; give them a moment, exactly as a page view would.
  await sleep(Number(process.env.SETTLE_MS || 6000));
  const ctx0 = makeCtx(store.profiles()[0] || { id: '-', kind: 'adult' });
  const seen = await shelves.catalogsOf(ctx0);
  console.log('catalogs visible to shelves:', seen.length,
    seen.map((e) => e.provider.id + '/' + e.cat.id + '(' + e.cat.type + ' [' + (e.cat.extra || []).join(',') + '])').join(' '));
  await warmGenreShelves();
  await sleep(Number(process.env.WARM_MS || 6000));

  const profiles = store.profiles().filter((p) => p.kind === 'kids')
    .filter((p) => !WANT_PROFILE || p.id === WANT_PROFILE);
  const rows = registry.rows.filter((r) => r.scope === 'kids')
    .filter((r) => !WANT_ROW || r.id === WANT_ROW);

  const st = ratings.status();
  console.log('ratings database:', st.entries, 'entries,', st.withCertificate, 'with a stated certificate,',
    st.askedNothing, 'asked-and-nothing-stated, tiers', JSON.stringify(st.byTier));
  console.log('profiles:', profiles.map((p) => p.id + '(' + p.age + ')').join(' '), '| rows:', rows.map((r) => r.id).join(' '));
  console.log('');

  for (const profile of profiles) {
    for (const row of rows) {
      const out = await runRow(row, profile);
      console.log('== profile', profile.id, '(age ' + profile.age + ') row', out.row, '==');
      console.log('  candidates', out.candidates, '->', out.stages.slice(1).map((s) => s.rule + ':' + s.n).join(' -> '));
      for (const s of out.stages) {
        if (!s.dropped.length) continue;
        console.log('  dropped by', s.rule, ':', s.dropped.length);
        if (VERBOSE) for (const d of s.dropped) {
          console.log('    -', (d.title || '?').slice(0, 32).padEnd(32), '| type', String(d.type).padEnd(7),
            '| maturity', String(d.maturity).padEnd(9), '| tier', String(d.tier).padEnd(4),
            '| minAge', String(d.minAge).padEnd(3), '| boards', String(d.boards).padEnd(3),
            '| via', String(d.ratingSource).slice(0, 46).padEnd(46), '| strictest:', d.drivers,
            '| genres', d.genres.slice(0, 40));
        }
      }
      console.log('  FINAL ITEMS:', out.kept.length);
      console.log('');
    }
  }
}

main().then(() => process.exit(0)).catch((e) => { log('diagnose failed:', e.message); process.exit(1); });
