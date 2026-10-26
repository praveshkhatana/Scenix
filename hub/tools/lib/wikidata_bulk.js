'use strict';
/*
 * tools/lib/wikidata_bulk.js — one HTTP request per few hundred titles, instead of one request per
 * title, for the certificate database.
 *
 * Why this exists: the two per-title upstreams are OMDb (a daily quota the household burns through in an
 * afternoon) and TMDB (which from this network resets TLS connections several times a day). Neither can
 * fill a catalogue of thousands of titles in a reasonable time. Wikidata's SPARQL endpoint needs no key,
 * answers a batch of 500 IMDb ids in a couple of seconds, and carries the classification of ~25 national
 * film boards as structured data. It is not a substitute for a board's own bulk dataset — the BBFC's, for
 * one, which this box cannot reach at all — it is the one bulk route that works from here today, so the
 * backfill uses it first and leaves the per-title calls for the titles it cannot answer.
 *
 * What it is NOT: a rating we invented. Each value here is a national board's certificate attached to the
 * title by that board's own property (P1657 = the US MPA rating, P2629 = the BBFC certificate,
 * P6452 = India's CBFC certificate, and so on). Whatever the board wrote goes into core/ratings.js
 * unchanged as a string for that country, and the ladder decides what it means — so a board Wikidata
 * knows but this hub's ladder does not is still recorded, and shows up in status() as an unreadable
 * string to add to the ladder, rather than being silently dropped.
 *
 * Verified from this box before any of it was built on (the curl is in the run log): the property names
 * below are what Wikidata actually calls them. P1651, which is often quoted as the film-rating property,
 * is Wikidata's *YouTube video ID* and would have put video links in the ratings table.
 */
const ENDPOINT = process.env.WIKIDATA_ENDPOINT || 'https://query.wikidata.org/sparql';
const USER_AGENT = process.env.WIKIDATA_USER_AGENT || 'ScenixHub/1.0 (self-hosted ratings backfill; personal use)';
const TIMEOUT_MS = Number(process.env.WIKIDATA_TIMEOUT_MS || 90000);
const BATCH = Number(process.env.WIKIDATA_BATCH || 200);

/**
 * Property -> the country whose board issued it. The comment is what Wikidata calls the property; the
 * country is what core/ratings.js needs in order to interpret the string, because that board's own
 * system is read before any global table (and that ordering is the whole reason `A` can mean "for
 * everybody" in Mexico and "adults only" in India).
 */
const PROPS = [
  ['P1657', 'US'], // MPA film rating (the US certificate)
  ['P2629', 'GB'], // BBFC certificate
  ['P6452', 'IN'], // CBFC certificate (India) — near-zero coverage for Indian titles, see the run log
  ['P1981', 'DE'], // FSK age rating
  ['P914', 'DE'], // USK age rating
  ['P3156', 'AU'], // OFLC classification (Australia)
  ['P7327', 'NZ'], // OFLC classification (New Zealand)
  ['P2684', 'NL'], // Kijkwijzer rating
  ['P2756', 'JP'], // EIRIN rating
  ['P3428', 'AR'], // INCAA rating (Argentina)
  ['P3216', 'BR'], // ClassInd rating (Brazil)
  ['P3306', 'ES'], // ICAA classification (Spain)
  ['P2363', 'HU'], // NMHH classification (Hungary)
  ['P3818', 'ZA'], // FPB rating (South Africa)
  ['P3402', 'FR'], // CNC classification (France)
  ['P5970', 'NO'], // Medierådet age rating (Norway)
  ['P6657', 'CH'], // CHVRS age rating (Switzerland)
  ['P5152', 'FI'], // KAVI rating (Finland)
  ['P5201', 'SG'], // IMDA rating (Singapore)
  ['P8874', 'HK'], // Hong Kong classification
  ['P8889', 'PH'], // MTRCB rating (Philippines)
  ['P7573', 'IE'], // IFCO rating (Ireland)
  ['P9866', 'GR'], // GRAC classification (Greece)
  ['P13773', 'ID'], // LSF rating (Indonesia)
  ['P8652', 'NT'], // Netflix maturity rating: a streamer's own classification, not a board's, so it is
  // recorded under a code of our own, and core/ratings.js prefers a real board over it.
  // Not a board: language of work, collected into an array under a key no country code can collide
  // with. One query already walks these items, so asking for their language costs nothing extra, and
  // unlike a catalog tile it is a stated fact about the title rather than a guess from its name.
  ['P407', '#langs'],
];
const BY_URI = {};
// SPARQL hands back a predicate as http://www.wikidata.org/prop/direct/P1657 — not the entity URI of the
// property — so this is the key the answer is looked up by.
for (const [pid, country] of PROPS) BY_URI['http://www.wikidata.org/prop/direct/' + pid] = country;

/** Wording that names the system rather than the age, and carries no information once we know the board. */
const NOISE = /\b(certificate|certification|classification|classification system|age rating|rating|approved|freigegeben|ab|kijkwijzer|bbfc|mpaa|fsk|usk|ifco|cbfc|oflc|usk)\b/gi;

/**
 * Wikidata names a rating value with its own label: "PG-13 certificate", "12A certificate",
 * "FSK ab 12 freigegeben", "from 16 years". Strip the naming, keep what the board wrote, and hand the
 * string to the ladder — which is the only place an age is decided.
 */
function normalizeLabel(text) {
  let s = String(text || '').trim();
  if (!s) return null;
  if (/^Q\d+$/.test(s)) return null; // an entity with no English label: an id is not a certificate
  s = s.replace(NOISE, ' ').replace(/\s+/g, ' ').trim();
  if (!s || /^(none|unknown|n\/a|not rated|unrated)$/.test(s)) return null;
  return s.toUpperCase();
}

/** Build one SPARQL query for a batch of IMDb ids. */
function queryFor(imdbIds) {
  const ids = imdbIds.map((id) => `"${String(id).replace(/[^A-Za-z0-9]/g, '')}"`).join(' ');
  const preds = PROPS.map(([pid]) => 'wdt:' + pid).join(' ');
  return [
    'SELECT ?imdb ?p ?v ?label WHERE {',
    `  VALUES ?imdb { ${ids} }`,
    '  ?item wdt:P345 ?imdb .',
    `  VALUES ?p { ${preds} }`,
    '  ?item ?p ?v .',
    '  OPTIONAL { ?v rdfs:label ?label . FILTER(LANG(?label) = "en") }',
    '}',
  ].join('\n');
}

/**
 * Ask for one batch. Resolves to { identity -> { country: cert, … } } for the titles Wikidata knows
 * anything about. Network failures resolve to {} rather than throwing: a backfill run over thousands of
 * titles has to survive one endpoint hiccup, and the missing batch is simply picked up on the next run.
 */
async function fetchBatch(imdbIds) {
  const body = new URLSearchParams({ query: queryFor(imdbIds), format: 'json' });
  for (let attempt = 0; attempt < 3; attempt++) {
    const ac = new AbortController();
    const timer = setTimeout(() => ac.abort(), TIMEOUT_MS);
    if (timer.unref) timer.unref();
    try {
      const res = await fetch(ENDPOINT, {
        method: 'POST',
        signal: ac.signal,
        headers: { 'user-agent': USER_AGENT, 'content-type': 'application/x-www-form-urlencoded', accept: 'application/sparql-query+json' },
        body,
      });
      if (!res.ok) {
        if ((res.status === 429 || res.status >= 500) && attempt < 2) {
          await sleep(2000 * 2 ** attempt);
          continue;
        }
        console.log('WIKIDATA http', res.status, res.statusText);
        return {};
      }
      const json = await res.json();
      return rowsToCerts(((json || {}).results || {}).bindings || []);
    } catch (e) {
      if (attempt < 2) {
        await sleep(2000 * 2 ** attempt);
        continue;
      }
      console.log('WIKIDATA failed:', String((e && e.message) || e));
      return {};
    } finally {
      clearTimeout(timer);
    }
  }
  return {};
}

function rowsToCerts(bindings) {
  const byTitle = {};
  for (const b of bindings) {
    const imdb = b.imdb && b.imdb.value;
    const pred = b.p && b.p.value;
    const v = b.v && b.v.value;
    if (!imdb || !pred || !v) continue;
    const country = BY_URI[pred];
    if (!country) continue;
    // An entity value is only usable through its label; a literal value IS the board's wording.
    const cert = b.v.type === 'uri' ? normalizeLabel(b.label && b.label.value) : normalizeLabel(v);
    if (!cert) continue;
    const identity = 'imdb:' + imdb;
    byTitle[identity] = byTitle[identity] || {};
    if (country === '#langs') {
      // A title can be in more than one language, so this one is a list, not a first-wins board.
      (byTitle[identity]['#langs'] = byTitle[identity]['#langs'] || []).push(cert);
      continue;
    }
    if (!byTitle[identity][country]) byTitle[identity][country] = cert;
  }
  return byTitle;
}

/**
 * Walk a whole list of identities in batches. `onBatch` is called after every batch, and that is where
 * the caller writes to the database — so an interrupted run keeps everything it already fetched, and a
 * restarted run costs the batches it re-asks but never loses a certificate (ingest unions, it does not
 * overwrite).
 */
async function run(ids, { onBatch, batch = BATCH, sleepMs = 1500, log = console.log } = {}) {
  const found = {};
  let asked = 0;
  let boards = 0;
  for (let i = 0; i < ids.length; i += batch) {
    const slice = ids.slice(i, i + batch);
    const certs = await fetchBatch(slice.map((id) => id.replace(/^imdb:/i, '')));
    asked += slice.length;
    const hits = Object.keys(certs).length;
    for (const [identity, c] of Object.entries(certs)) {
      found[identity] = c;
      boards += Object.keys(c).length;
    }
    if (onBatch) await onBatch(certs, { asked, done: Math.min(i + batch, ids.length), total: ids.length });
    log(`WIKIDATA ${Math.min(i + batch, ids.length)}/${ids.length} ids asked, ${hits} answered, ${boards} certificates so far`);
    if (i + batch < ids.length && sleepMs) await sleep(sleepMs);
  }
  return { found, asked };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

module.exports = { run, fetchBatch, queryFor, normalizeLabel, PROPS, BATCH };
