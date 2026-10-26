'use strict';
/*
 * core/ratings.js — our OWN certificate database, so a title's age rating is looked up once and then
 * lives on this box forever.
 *
 * Why this exists: the addons in this house state no certificate at all (cinemeta metas carry no
 * `certification`), so every "is this for kids" decision was a guess from genres. A certificate is not a
 * guess, and asking the internet on every page view is not acceptable on a TV network. So:
 *
 *   data/ratings.json   one entry per identity, written the first time we look it up. Boards are merged,
 *                       never thrown away: a title seen again from another source gains certificates, it
 *                       does not lose the ones it had. Only "I asked and found nothing" is retried, and
 *                       only after RATINGS_MISS_TTL_MS.
 *
 * Three ways a certificate arrives:
 *   OMDb   http://www.omdbapi.com/?i=<imdb id>&apikey=<key>  -> `Rated` (one certificate, the US/UK one),
 *          Genre, Runtime, Title, Year. Keyed by IMDb id, which is already this hub's merge key.
 *   TMDB   /find/<imdb id>?external_source=imdb_id, then one detail call with
 *          append_to_response=release_dates,content_ratings -> per-country board certifications. For a
 *          MOVIE the boards are in release_dates (`certification`); for a TV series they are in
 *          content_ratings (`rating`) — two different field names, and both are read.
 *   Wikidata (bulk, no key, no per-title request) -> tools/backfill-ratings.js asks SPARQL for a few
 *          hundred IMDb ids at a time and feeds the boards in through ingest(). That is the only source
 *          that answers for titles neither OMDb nor TMDB certifies, and it costs one request per batch.
 *
 * A certificate is a FACT; what to do when there is none is a POLICY. So a record stores only facts
 * (the boards, the genres, the adult flag) plus the tier they score to, and `know()` applies the policy
 * at read time: the strictest board wins when a board answered, otherwise the inferred tier from cheap
 * signals (which row the title came from, its genres, its runtime), otherwise UNRATED. `defaulted: true`
 * says out loud that the number is a policy default and not something a board said.
 */
const fs = require('fs');
const path = require('path');
const langdb = require('./langdb');

const ROOT = path.join(__dirname, '..');
const DATA_DIR = process.env.DATA_DIR || path.join(ROOT, 'data');
const FILE = process.env.RATINGS_FILE || path.join(DATA_DIR, 'ratings.json');
// Keys live beside the data, and stay there: data/ is gitignored and rsync-excluded, so a key never
// enters the repo or a deploy. The repo's own data/ dir is tried too, so a tool run with DATA_DIR
// pointed somewhere else still finds the household's keys.
const KEYS_FILE = process.env.RATINGS_KEYS_FILE
  || (fs.existsSync(path.join(DATA_DIR, 'keys.json')) ? path.join(DATA_DIR, 'keys.json') : path.join(ROOT, 'data', 'keys.json'));
const ADDONS_FILE = process.env.ADDONS_FILE || path.join(DATA_DIR, 'addons.json');

/* ------------------------------------------------------------------ the ladder
 * The whole age policy of the hub, as a table. Edit these rows; nothing below needs to change.
 * tier 0/1/2 are "how old must you be"; tier 3 is the dark-red zone, which is not an age question at
 * all — it is a switch the household turns on (prefs.xxxEnabled).
 *
 * Bump LADDER_VERSION when this table (or the word/letter tables under it) changes: stored records keep
 * the tier their boards scored to, and a record scored by an older ladder is re-scored from its stored
 * boards — no network, no re-ask.
 */
let LADDER_VERSION = 3;
const LADDER = [
  { tier: 0, minAge: 0, certs: ['U', 'G', 'PG', 'TV-Y', 'TV-Y7', 'TV-Y7-FV', 'TV-G', 'TV-PG'] },
  // CBFC "U/A 7+" is "unrestricted, parents may guide a child over 7" — it is not a 13+ rating.
  { tier: 0, minAge: 7, certs: ['U/A 7+'] },
  { tier: 1, minAge: 13, certs: ['U/A 13+', 'PG-13', '12', '12A', '15A', 'M', 'TV-14'] },
  // "generic adult": nudity, sex, violence. Normal for adults, invisible to kids.
  { tier: 2, minAge: 16, certs: ['A', 'U/A 16+', 'R', 'NC-17', '15', '16', '18', '18A', 'R18', 'TV-MA', 'X'] },
];
const TIER3 = { tier: 3, minAge: 18 }; // XXX: no certificate says it, a flag or a folder does
// A title we cannot rate at all is treated as generic adult. Safer to hide from children than to guess,
// and it is what the big services do with unrated titles — unless a cheaper signal says otherwise, see
// inferTier(), which is what keeps a curated kids row from emptying out.
const UNRATED = { tier: 2, minAge: 16 };
// Words that mean "the certificate field is empty", not "rated for everyone".
const NOT_RATED = ['', 'N/A', 'NA', 'NR', 'NOT RATED', 'UNRATED', 'APPROVED', 'PASSED', 'GP', 'TBD'];

/*
 * Boards whose string is not in the ladder still state an age, and reading it correctly is the single
 * biggest correctness lever here: TMDB answers ~45 boards per title, and "I do not know this wording"
 * used to mean "treat it as 16+" — which hid Coco (rated `A` = everybody in Spain), Spirited Away
 * (`0` = no restriction in Germany) and Ice Age (`А` in Cyrillic, again "everybody") from a 15-year-old.
 *
 * So the tables below exist, and the order of lookup matters: the board's OWN system is read first,
 * because `A` means "for everybody" in Mexico, Spain, Bulgaria and Denmark and "adults only" in India.
 */
// Wording that carries the same meaning in more than one country.
const AGE_WORDS = {
  ALL: 0, ATP: 0, APTA: 0, TP: 0, T: 0, L: 0, AL: 0, AA: 0, '0+': 0, BT: 7, // "everybody" / "all ages"
  UA: 13, 'U/A': 13, // CBFC: unrestricted, but not suitable for a child under 13
  'הותר לכל': 0, // Israel: "permitted for all"
  'GENEL İZLEYICI KITLESI': 0, // Turkey: "general audience"
  '普遍級': 0, '保護級': 7, '輔導級': 12, '限制級': 18, // Taiwan's four bands, stated in the board's own words
  // Boards that write a sentence instead of a symbol (the wording arrives verbatim from a bulk source,
  // so it is read verbatim here): each one says who may watch, in plain language.
  'A G': 0, // France, "tous publics" — the abbreviation the CNC puts on the poster
  'SUITABLE FOR A GENERAL AUDIENCE': 0, // Norway
  'PARTICULARLY SUITABLE FOR KIDS': 0, // Spain
  'PARENTAL GUIDANCE': 0, // Philippines, the band straight above "general"
  'ADULTS ONLY': 18, 'RESTRICTED': 18, // South Africa's two top bands
};
// Tokens whose meaning belongs to one country's system: keyed by board, never guessed globally. The
// counts in the comment are how often that exact string appears on this box's own certificates, which is
// the evidence each row was written from.
const BOARD_LETTERS = {
  MX: { A: 0, AA: 7, B: 12, B1: 13, C: 16, D: 18 }, // "Apto para todo público" downwards
  BG: { A: 0, B: 6, C: 12, D: 16 },
  DK: { A: 0 }, // Denmark rates A / 7 / 11 / 15
  ES: { A: 0, APTA: 0 }, // Spain: "A"/"APTA" = apta para todos
  FI: { S: 0 }, // Finland: "S" = recommended for all age groups, ahead of its 7/12/16/18 numbers
  HK: { I: 0, IIA: 11, IIB: 12, III: 18 },
  HU: { KN: 0, I: 0, II: 14, III: 16, IV: 18, V: 18 }, // "Korhatár nélkül" = no age limit
  ID: { SU: 0, B: 13, D: 21 }, // Indonesia: "Semua Umur" = all ages
  LT: { V: 0 }, // Lithuania: "visiems" = for everybody
  PE: { I: 0, II: 14, III: 18, IV: 18 },
  RO: { AP: 0, '12': 12, '15': 15, '18': 18 }, // "Aptut pentru toate vârstele"
  SE: { BTL: 0 }, // Sweden: "barntillåtet" = children are allowed; its numbers 7/11/15 come from the wording
  CL: { GTE: 0, TE: 14 },
  GR: { K: 13 }, // the bare Kappa is the truncated form of the board's "K-13"
  TH: { G: 0, P: 13 },
  AU: { M: 15, 'MA 15+': 15 }, // the Australian M is 15+, not the 13 the ladder's symbol implies
  NZ: { M: 16 },
};

// Evidence for tier 3 that is not a certificate. Also a table, also editable.
const ADULT_GENRE = ['Adult', 'Erotica'];
const ADULT_WORDS = /\b(xxx|porn|adult only|erotic|blue film|18\+ only)\b/i;
const PRIVATE_DIR = /^(private|adult|xxx|18\+|explicit)\b/i; // a media/ folder named like this

/* ------------------------------------------------------------------ "unknown is safe, not scary"
 * UNRATED has to keep working for an adult catalogue — a title with no certificate is still visible to
 * an adult (ceiling 99) — but it must not silently empty a row a parent curated for children. These are
 * the cheap signals that carry no network cost, and the same genre words rules/kids_allowlist.js uses,
 * so the two rules cannot disagree about what "children's content" means.
 */
const YOUNG_GENRES = ['Animation', 'Family', 'Kids', 'Children', 'Cartoon'];
const ADULT_HINT_GENRES = ['Horror', 'Crime', 'Thriller', 'War', 'Mystery'];
// rows/kids_*.js stamp their own id into _addons (the one field merge.dedupe carries across sources),
// which is how a rule can tell "this came from the shelf a parent curated" from "this came from search".
const KIDS_CATALOG_MARK = /^catalog:/;

/* ------------------------------------------------------------------ plumbing */
const TIMEOUT_MS = Number(process.env.RATINGS_TIMEOUT_MS || 6000);
const MISS_TTL_MS = Number(process.env.RATINGS_MISS_TTL_MS || 7 * 24 * 3600 * 1000);
const BUDGET_MS = Number(process.env.RATINGS_BUDGET_MS || 2500); // what a page view may wait for
// OMDb's free tier is about 1000 requests a day. The live path stays under it; the backfill tool does not
// have to, because it is run once and sleeps between calls.
const DAILY_CAP = Number(process.env.RATINGS_DAILY_CAP || 800);
let cappedLogged = false;

let state = { ratings: {}, day: { date: today(), calls: 0 } };
let loaded = false;
const inflight = new Map(); // identity -> promise, so one search burst asks for a title once
let saveTimer = null;
let keys = null;
let keyMtime = 0;

const log = (...a) => console.log(new Date().toISOString(), ...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function load() {
  if (loaded) return;
  loaded = true;
  try {
    const raw = JSON.parse(fs.readFileSync(FILE, 'utf8'));
    if (raw && raw.ratings && typeof raw.ratings === 'object') state = raw;
    if (!state.day || typeof state.day !== 'object') state.day = { date: today(), calls: 0 };
    diskStamp = statMtime(FILE);
  } catch (e) {
    if (e.code !== 'ENOENT') log('RATINGS load failed:', e.message);
  }
}

function statMtime(file) {
  try {
    return fs.statSync(file).mtimeMs;
  } catch (e) {
    return 0;
  }
}

/**
 * Two hubs can share one data/ratings.json (the live one, a tool run, a measurement run), and the file is
 * written whole. So read the file again before writing it and union, instead of trusting what this
 * process happens to hold in memory — otherwise the last writer silently deletes the other's work.
 */
function unionRecord(mine, theirs) {
  if (!theirs) return mine;
  if (!mine) return theirs;
  const newer = Number(theirs.fetchedAt || 0) > Number(mine.fetchedAt || 0) ? theirs : mine;
  const older = newer === mine ? theirs : mine;
  const out = { ...older };
  // The newer answer wins for the scalars; the tier is re-derived from the boards anyway (scored()),
  // so a stale one on the other side of the merge cannot make a wrong decision.
  for (const [k, v] of Object.entries(newer)) if (v !== undefined && v !== null) out[k] = v;
  out.identity = mine.identity || theirs.identity || out.identity;
  out.certs = { ...(theirs.certs || {}), ...(mine.certs || {}) }; // a board is never lost to a merge
  out.genres = [...new Set([...(theirs.genres || []), ...(mine.genres || [])])];
  if (!out.genres.length) out.genres = mine.genres || theirs.genres || null;
  out.sources = [...new Set([...(theirs.sources || []), ...(mine.sources || [])])];
  out.adultFlag = Boolean(theirs.adultFlag || mine.adultFlag);
  out.fetchedAt = Math.max(Number(theirs.fetchedAt || 0), Number(mine.fetchedAt || 0)) || newer.fetchedAt;
  out.lookedUpAt = Math.max(Number(theirs.lookedUpAt || 0), Number(mine.lookedUpAt || 0)) || undefined;
  out.tmdbId = mine.tmdbId || theirs.tmdbId || null;
  out.imdbId = mine.imdbId || theirs.imdbId || null;
  out.title = mine.title || theirs.title || null;
  out.year = mine.year || theirs.year || null;
  out.type = mine.type || theirs.type || null;
  out.runtimeMs = mine.runtimeMs || theirs.runtimeMs || null;
  out.ladderVersion = newer.ladderVersion || mine.ladderVersion || theirs.ladderVersion;
  return out;
}

let diskStamp = 0;
let diskCheckedAt = 0;

/** Write now, in this tick. save() defers and unrefs its timer, so a tool that is about to exit needs this. */
function writeNow() {
  if (saveTimer) {
    clearTimeout(saveTimer);
    saveTimer = null;
  }
  try {
    try {
      const raw = JSON.parse(fs.readFileSync(FILE, 'utf8'));
      const theirs = (raw && raw.ratings) || {};
      for (const [key, rec] of Object.entries(theirs)) state.ratings[key] = unionRecord(state.ratings[key], rec);
    } catch (e) {
      if (e.code !== 'ENOENT') throw e;
    }
    fs.mkdirSync(path.dirname(FILE), { recursive: true });
    const tmp = FILE + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(state, null, 1));
    fs.renameSync(tmp, FILE); // a crash mid-write never leaves a half file
    diskStamp = statMtime(FILE);
    return true;
  } catch (e) {
    log('RATINGS save failed:', e.message);
    return false;
  }
}

/** Pick up records another process wrote, so a backfill run needs no hub restart. */
function refreshFromDisk(force = false) {
  const now = Date.now();
  if (!force && now - diskCheckedAt < 1000) return;
  diskCheckedAt = now;
  const stamp = statMtime(FILE);
  if (!stamp || stamp === diskStamp) return;
  diskStamp = stamp;
  try {
    const raw = JSON.parse(fs.readFileSync(FILE, 'utf8'));
    const theirs = (raw && raw.ratings) || {};
    for (const [key, rec] of Object.entries(theirs)) state.ratings[key] = unionRecord(state.ratings[key], rec);
    const d = raw && raw.day;
    if (d && d.date === today()) {
      state.day = { date: today(), calls: Math.max(Number(state.day.calls || 0), Number(d.calls || 0)) };
    }
  } catch (e) {
    if (e.code !== 'ENOENT') log('RATINGS refresh failed:', e.message);
  }
}

function save() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    writeNow();
  }, 500);
  if (saveTimer.unref) saveTimer.unref();
}

/** Keys are read per call and re-read if the file changed: adding a key must not need a restart. */
function readKeys() {
  try {
    const st = fs.statSync(KEYS_FILE);
    if (!keys || st.mtimeMs !== keyMtime) {
      keys = JSON.parse(fs.readFileSync(KEYS_FILE, 'utf8'));
      keyMtime = st.mtimeMs;
    }
  } catch (e) {
    keys = null;
  }
  return keys || {};
}

/** Never let a key reach a log line or an error message. */
function redact(text) {
  const k = readKeys();
  let out = String(text || '');
  for (const v of Object.values(k)) if (typeof v === 'string' && v.length > 3) out = out.split(v).join('<key>');
  return out;
}

// A reset mid-handshake (Errno 54) is what api.themoviedb.org does here several times a day, and it is
// transient: retry with a backoff instead of writing "this title has no certificate" from a hiccup.
const TRANSIENT = /ECONNRESET|ECONNREFUSED|EPIPE|ETIMEDOUT|EAI_AGAIN|socket hang up|network|abort|UND_ERR/i;
const RETRIES = Number(process.env.RATINGS_RETRIES || 3);

async function getJson(url, timeoutMs = TIMEOUT_MS) {
  for (let attempt = 0; attempt < RETRIES; attempt++) {
    const ac = new AbortController();
    const timer = setTimeout(() => ac.abort(), timeoutMs);
    if (timer.unref) timer.unref();
    try {
      const res = await fetch(url, {
        signal: ac.signal,
        headers: { 'user-agent': 'ScenixHub/1.0 (self-hosted; personal use)', accept: 'application/json' },
      });
      if (res.status === 429 && attempt + 1 < RETRIES) { // TMDB's rate limit: back off and ask again
        await sleep(500 * 2 ** attempt);
        continue;
      }
      if (!res.ok) return null;
      return await res.json();
    } catch (e) {
      const why = String((e && (e.cause && e.cause.code)) || (e && e.message) || e);
      if (TRANSIENT.test(why) && attempt + 1 < RETRIES) {
        await sleep(300 * 2 ** attempt);
        continue;
      }
      log('RATINGS upstream fail:', redact(url.replace(/([?&])(api_key|apikey)=[^&]*/g, '$1$2=<key>')), why);
      return null;
    } finally {
      clearTimeout(timer);
    }
  }
  return null;
}

/* ------------------------------------------------------------------ the ladder as code */
// Bulgaria, Russia, Ukraine and Greece publish certificates in their own alphabet, and TMDB hands the
// string over as-is: a Bulgarian "А" (for everybody) is U+0410, not the Latin A, so it matched nothing.
const HOMOGLYPHS = /[АВСЕКМНОТРХΚ]/g;
const HOMOGLYPH_MAP = { А: 'A', В: 'B', С: 'C', Е: 'E', К: 'K', М: 'M', Н: 'H', О: 'O', Т: 'T', Р: 'P', Х: 'X', Κ: 'K' };

function normCert(value) {
  return String(value == null ? '' : value)
    .toUpperCase()
    .replace(HOMOGLYPHS, (ch) => HOMOGLYPH_MAP[ch] || ch)
    .replace(/[.\u00A0]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/** The tier an age maps to: a board that says 12 means tier 0 with a floor of 12, one that says 16 means tier 2. */
function tierForAge(age) {
  return age >= 16 ? 2 : age >= 13 ? 1 : 0;
}

/**
 * Pull an age out of wording we do not have a row for: "K-16", "M/12", "12+", "NC16", "MA 15+", "13",
 * and the German/Austrian/Swiss "0" which means no restriction at all.
 */
function ageFromWording(c) {
  const squeezed = c.replace(/\s+/g, '');
  if (/^Q?\d{4,}$/.test(squeezed)) return null; // a bare year or a Wikidata Q-id, not an age
  const m = /(?:^|[^\d])(\d{1,2})(?![\d])/.exec(c);
  if (!m) return null;
  const n = Number(m[1]);
  return n >= 0 && n <= 25 ? n : null;
}

/**
 * Some boards name their own system in the certificate string instead of writing the age: Hungary says
 * "CATEGORY IV" and Hong Kong says "CATEGORY IIA", and the age is the part after the name. Strip the
 * name, keep the token, and let the country's table read it.
 */
function stripSystemName(c) {
  const m = /^CATEGORY\s+(.+)$/.exec(c);
  return m ? m[1].trim() : c;
}

/**
 * Map one board's certificate to {tier, minAge}, or null when it states nothing.
 *
 * `country` is not decoration: it decides what a letter means, and it is consulted BEFORE the global
 * ladder, because the same letter points at opposite ends of two different systems ("A" is "for
 * everybody" in Mexico and "adults only" in India). Order: that board's own system, then the ladder,
 * then a word table, then an age written inside the string, then "a board spoke and we cannot read it".
 *
 * Where a certificate writes an age, that age is used rather than the ladder's coarser bucket: `12` is
 * 12, not 13, and `15` is 15, not 16 — which is the difference between a 15-year-old watching something
 * his own country says he may watch, and a hub that guessed.
 */
function ladderFor(cert, country) {
  const c = stripSystemName(normCert(cert));
  if (!c || NOT_RATED.includes(c)) return null;
  const cc = String(country || '').toUpperCase();
  const letters = BOARD_LETTERS[cc];
  if (letters && letters[c] != null) {
    return { ...tierOfAge(letters[c]), cert: c, decoded: 'the ' + cc + ' board\'s own system' };
  }
  const stated = ageFromWording(c);
  for (const row of LADDER) {
    if (!row.certs.includes(c)) continue;
    const minAge = stated == null ? row.minAge : stated;
    return { tier: tierForAge(minAge), minAge, cert: c };
  }
  if (AGE_WORDS[c] != null) return { ...tierOfAge(AGE_WORDS[c]), cert: c, decoded: 'word table' };
  if (stated != null) return { ...tierOfAge(stated), cert: c, decoded: 'age written in the wording' };
  // A certificate from a board we do not know is still a certificate: treat it as generic adult.
  return { tier: UNRATED.tier, minAge: UNRATED.minAge, cert: c, unknownBoard: true };
}
function tierOfAge(age) {
  return { tier: tierForAge(age), minAge: age };
}

/**
 * The certificate shown on the tile, and read by rules/kids_allowlist.js. Both rules have to recognise
 * the word, so the badge is chosen from the boards this household reads (IN first, then US, then GB),
 * then from any board whose wording the allowlist knows, and otherwise it is the closest word this
 * household reads for the age the boards actually gave. The raw strings are never lost: they are all in
 * `certs`.
 */
const DISPLAY_COUNTRIES = ['IN', 'US', 'GB'];
const SHARED_VOCAB = new Set([
  'U', 'G', 'PG', 'TV-Y', 'TV-Y7', 'TV-G', 'TV-PG', // what the strict kids band reads
  'PG-13', '12', '12A', '13+', '15', 'TV-14', 'UA', 'U/A', // what the teen band reads
  'A', 'R', 'NC-17', '16', '18', 'TV-MA', 'X', // what the adult band reads
]);
const CANON_BY_AGE = [
  [7, 'U'], [12, 'PG'], [15, 'PG-13'], [17, '16'], [99, '18'],
];
function displayCert(certs, rated) {
  for (const country of DISPLAY_COUNTRIES) {
    const c = normCert(certs[country]);
    if (c && SHARED_VOCAB.has(c)) return c;
  }
  const readable = (rated || [])
    .filter((r) => !r.unknownBoard && SHARED_VOCAB.has(r.cert))
    .sort((a, b) => b.minAge - a.minAge);
  if (readable.length) return readable[0].cert;
  const strictest = Math.max(0, ...(rated || []).map((r) => r.minAge));
  const hit = CANON_BY_AGE.find(([age]) => strictest <= age);
  return hit ? hit[1] : null;
}

/* ------------------------------------------------------------------ upstreams */
function imdbIdOf(identity, known) {
  const m = /^imdb:(tt\d{7,10})$/i.exec(String(identity || ''));
  if (m) return m[1];
  const k = known && known.imdbId;
  return typeof k === 'string' && /^tt\d{7,10}$/.test(k) ? k : null;
}

async function fromOMDb(imdbId) {
  const key = readKeys().omdb;
  if (!key || !imdbId) return null;
  const body = await getJson(`http://www.omdbapi.com/?i=${encodeURIComponent(imdbId)}&apikey=${encodeURIComponent(key)}`);
  if (!body || body.Response === 'False' || !body.Title) return null;
  const genres = String(body.Genre || '').split(',').map((s) => s.trim()).filter(Boolean);
  const runtime = parseInt(body.Runtime, 10);
  // The same response names the languages this title is in. The certificate ladder has no use for them,
  // but the owner's language rule does, and asking twice for what one answer already gave is the one
  // thing this box should never do.
  langdb.learnFromNames('imdb:' + imdbId, String(body.Language || '').split(','));
  return {
    source: 'omdb',
    title: body.Title,
    year: /^\d{4}$/.test(String(body.Year)) ? Number(body.Year) : null,
    cert: body.Rated, // the certificate field; "Not Rated" and "" mean nothing was stated
    genres,
    runtimeMs: Number.isFinite(runtime) && runtime > 0 ? runtime * 60000 : null,
    adultFlag: genres.some((g) => ADULT_GENRE.includes(g)),
    type: body.Type === 'series' ? 'series' : 'movie',
  };
}

/** TMDB: find by IMDb id, then one detail call carrying both the boards and the adult flag. */
async function fromTMDB(imdbId, wantedType) {
  const key = readKeys().tmdb;
  if (!key || !imdbId) return null;
  const found = await getJson(`https://api.themoviedb.org/3/find/${encodeURIComponent(imdbId)}?external_source=imdb_id&api_key=${encodeURIComponent(key)}`);
  const movie = (found && Array.isArray(found.movie_results) && found.movie_results[0]) || null;
  const tv = (found && Array.isArray(found.tv_results) && found.tv_results[0]) || null;
  const pick = wantedType === 'series' ? (tv || movie) : (movie || tv);
  if (!pick) return null;
  const kind = pick === tv ? 'tv' : 'movie';
  const detail = await getJson(`https://api.themoviedb.org/3/${kind}/${pick.id}?api_key=${encodeURIComponent(key)}&append_to_response=release_dates,content_ratings`);
  if (!detail) return null;
  const certs = {};
  // A movie's boards come under release_dates as `certification`; a series' boards come under
  // content_ratings as `rating`. Reading only the first block, and only `certification`, silently gave
  // every series in the house exactly one board (OMDb's) and no TMDB board at all.
  for (const b of (detail.release_dates && detail.release_dates.results) || []) {
    const country = String(b.iso_3166_1 || '').toUpperCase();
    if (!country) continue;
    for (const rd of b.release_dates || []) {
      const cert = normCert(rd.certification);
      if (cert && !certs[country]) certs[country] = cert;
    }
  }
  for (const b of (detail.content_ratings && detail.content_ratings.results) || []) {
    const country = String(b.iso_3166_1 || '').toUpperCase();
    if (!country) continue;
    const cert = normCert(b.rating); // NOT b.certification — this is the series field
    if (cert && !certs[country]) certs[country] = cert;
  }
  // TMDB states the original language, and sometimes the spoken ones. Free on a call we are already
  // making, and exactly the fact that decides whether this title belongs in this house at all.
  langdb.learnFromCodes('imdb:' + imdbId, [
    detail.original_language,
    ...(Array.isArray(detail.spoken_languages) ? detail.spoken_languages.map((l) => l.iso_639_1) : []),
  ]);
  return {
    source: 'tmdb',
    tmdbId: pick.id,
    type: kind === 'tv' ? 'series' : 'movie',
    title: detail.title || detail.name || pick.title || pick.name || null,
    year: (detail.release_date || detail.first_air_date || '').slice(0, 4) || null,
    certs,
    adultFlag: Boolean(detail.adult),
    genres: Array.isArray(detail.genres) ? detail.genres.map((g) => g.name) : [],
    runtimeMs: Number(detail.runtime) > 0 ? Number(detail.runtime) * 60000 : null,
  };
}

/* ------------------------------------------------------------------ scoring a record's facts */
/**
 * Which boards decide a title's age.
 *
 * `all` (the default, and the rule this household chose): the STRICTEST board wins. A title is as old as
 * the most cautious country that classified it, because the point of the tier is to protect a child who
 * is sitting on the sofa — and it is the conservative choice the moment a bulk source brings twenty
 * boards in at once.
 *
 * `home`: the boards of the countries this family actually watches (IN, US, GB) decide, and a foreign
 * board only counts when none of those answered. The case that made writing this down worth it: The
 * Nightmare Before Christmas is `PG` at home and `15` in Norway, so under `all` it vanishes from an
 * 11-year-old's shelf. Neither setting can hide something the home boards call adult — `A`, `R`, `18`
 * and `TV-MA` ARE home-board certificates — and a title with no home board falls back to `all`, so
 * `home` is never more permissive than the family's own certificate says it is.
 *
 * RATINGS_BOARD_SCOPE=home switches it. RATINGS_HOME_BOARDS says which boards count as home.
 */
const BOARD_SCOPE = process.env.RATINGS_BOARD_SCOPE === 'home' ? 'home' : 'all';
const HOME_BOARDS = String(process.env.RATINGS_HOME_BOARDS || 'IN,US,GB')
  .split(',').map((s) => s.trim().toUpperCase()).filter(Boolean);

/**
 * Returns the facts only: tier/minAge/maturity when at least one board answered, and `unrated: true`
 * when none did — which is NOT a tier, it is "no board spoke", and it is where know() brings in policy.
 * `scope`, `minAgeAllBoards` and `driver` say which rule decided and which certificate carried it, so a
 * parent asking "why is this hidden from my child" can be answered with the board's name, not a guess.
 */
function scoreRecord(rec) {
  const certs = rec.certs && typeof rec.certs === 'object' ? rec.certs : {};
  const rated = [];
  for (const [country, cert] of Object.entries(certs)) {
    const step = ladderFor(cert, country);
    if (step) rated.push({ ...step, country });
  }
  const genres = Array.isArray(rec.genres) ? rec.genres : [];
  const adult = Boolean(rec.adultFlag) || genres.some((g) => ADULT_GENRE.includes(g));
  const unknownBoards = rated.filter((r) => r.unknownBoard).length;
  if (adult) { // an explicit adult flag outrates any board
    return { tier: TIER3.tier, minAge: TIER3.minAge, maturity: displayCert(certs, rated), unrated: false, boards: rated.length, unknownBoards };
  }
  if (rated.length) {
    const home = BOARD_SCOPE === 'home' ? rated.filter((r) => HOME_BOARDS.includes(r.country)) : [];
    const deciding = home.length ? home : rated; // no home board answered -> the strictest board still decides
    const minAge = Math.max(...deciding.map((r) => r.minAge));
    const tier = Math.max(...deciding.map((r) => r.tier));
    const widest = Math.max(...rated.map((r) => r.minAge));
    return {
      tier, minAge, maturity: displayCert(certs, deciding), unrated: false,
      boards: rated.length, unknownBoards,
      scope: home.length ? 'home boards' : 'strictest board',
      minAgeAllBoards: widest, // what the other rule would have said, so a diagnostic can show both
      driver: rated.filter((r) => r.minAge === widest).map((r) => r.country + '=' + r.cert).join(' '),
    };
  }
  return { tier: null, minAge: null, maturity: null, unrated: true, boards: 0, unknownBoards };
}

// Re-scoring is done on every page view for records written by an older ladder, so memoize per record.
const scoreMemo = new WeakMap();
function scored(rec) {
  const hit = scoreMemo.get(rec);
  if (hit && hit.version === LADDER_VERSION && hit.scope === BOARD_SCOPE) return hit;
  const s = { ...scoreRecord(rec), version: LADDER_VERSION, scope: BOARD_SCOPE };
  scoreMemo.set(rec, s);
  return s;
}

/* ------------------------------------------------------------------ merge */
function merge(identity, imdbId, parts) {
  const live = parts.filter(Boolean);
  if (!live.length) return null;
  const certs = {};
  for (const p of live) {
    if (p.cert && ladderFor(p.cert, 'US')) {
      // OMDb answers with the certificate of the country its own site is served from, and for a series
      // that is always the US TV board — so it lands under US, and only fills IN when nothing said IN.
      certs.US = certs.US || normCert(p.cert);
      if (!certs.IN && String(p.cert).match(/^(U|UA|U\/A|A)( .+)?$/i)) certs.IN = normCert(p.cert);
    }
    for (const [country, cert] of Object.entries(p.certs || {})) {
      if (!country) continue;
      certs[country] = cert;
    }
  }
  const genres = [...new Set(live.flatMap((p) => p.genres || []))];
  const adultFlag = live.some((p) => p.adultFlag);
  const runtimeMs = live.map((p) => p.runtimeMs).find((v) => Number.isFinite(v)) || null;

  const base = {
    identity,
    imdbId,
    tmdbId: (live.find((p) => p.tmdbId) || {}).tmdbId || null,
    certs,
    genres: genres.length ? genres : null,
    adultFlag,
    runtimeMs,
    title: (live.find((p) => p.title) || {}).title || null,
    year: (live.find((p) => p.year) || {}).year || null,
    type: (live.find((p) => p.type) || {}).type || null,
    sources: live.map((p) => p.source),
    ladderVersion: LADDER_VERSION,
    fetchedAt: Date.now(),
  };
  const s = scoreRecord(base);
  return { ...base, tier: s.tier, minAge: s.minAge, maturity: s.maturity, unrated: s.unrated };
}

/**
 * Look a title up. Resolves with the record, or null when neither upstream answered (the caller then
 * uses the heuristic tier, and "found nothing" is remembered so the next page view does not ask again).
 *
 * `opts.force` ignores the daily cap; that is what a backfill uses, because it asks on purpose and sleeps
 * between calls. The live path never does.
 *
 * `opts.reask` additionally ignores the record already held. A stored record is normally the whole point
 * of this file — the box does not ask the same question twice — but a record written before the lookup
 * began keeping the *language* an answer states is incomplete in a way the store itself cannot see, and
 * only asking again fills it. A tool does that once, on purpose, for the whole catalogue; nothing people
 * watch does.
 */
async function lookup(identity, known = {}, opts = {}) {
  load();
  refreshFromDisk();
  const key = String(identity);
  const have = state.ratings[key];
  if (have && fresh(have) && !opts.reask) return have;
  if (inflight.has(key)) return inflight.get(key);
  if (!opts.force && spentToday() >= DAILY_CAP) {
    // The free tier is roughly 1000 lookups a day and a home screen touches a thousand identities on a
    // cold database. Past the cap the hub answers from the heuristic tier until the date rolls over;
    // tools/backfill-ratings.js asks on purpose and is allowed past the cap.
    if (!cappedLogged) {
      cappedLogged = true;
      log('RATINGS daily cap reached', DAILY_CAP, '- heuristic tier only until the date rolls over');
    }
    return have || null;
  }
  const run = (async () => {
    const imdbId = imdbIdOf(key, known);
    const parts = await Promise.all([fromOMDb(imdbId), fromTMDB(imdbId, known.type)]);
    const rec = merge(key, imdbId, parts);
    if (rec) {
      state.ratings[key] = unionRecord(have, rec);
    } else {
      // Remember the question, not just the answer, so a miss costs one round every week, not per view.
      state.ratings[key] = unionRecord(have, {
        ...(have && have.sources && have.sources.length ? have : {}),
        identity, tier: null, minAge: null, unrated: true, lookedUpAt: Date.now(), sources: [],
      });
    }
    if (imdbId) countCall(); // a title we could not ask about costs no quota
    save();
    return state.ratings[key];
  })();
  inflight.set(key, run);
  return run;
}

/* One counter, persisted with the records: the cap has to survive restarts to mean anything. */
function today() { return new Date().toISOString().slice(0, 10); }
function spentToday() {
  return state.day && state.day.date === today() ? Number(state.day.calls || 0) : 0;
}
function countCall() {
  if (!state.day || state.day.date !== today()) state.day = { date: today(), calls: 0 };
  state.day.calls = Number(state.day.calls || 0) + 1;
}

function fresh(rec) {
  if (!rec) return false;
  if (rec.sources && rec.sources.length) return true; // a real record never expires
  return Date.now() - Number(rec.lookedUpAt || 0) < MISS_TTL_MS;
}

/** The stored record for an identity, or null. Synchronous: this is what the rules run on. */
function peek(identity) {
  load();
  refreshFromDisk();
  const rec = state.ratings[String(identity)];
  return rec && fresh(rec) ? rec : null;
}

/** Await a lookup but never longer than the page's patience. */
async function lookupWithin(identity, known = {}, ms = BUDGET_MS, opts = {}) {
  let timer;
  const guard = new Promise((resolve) => { timer = setTimeout(() => resolve(null), ms); if (timer.unref) timer.unref(); });
  const p = Promise.resolve().then(() => lookup(identity, known, opts)).catch((e) => { log('RATINGS lookup failed:', e.message); return null; });
  return Promise.race([p, guard]).finally(() => clearTimeout(timer));
}

/* ------------------------------------------------------------------ bulk ingest (no per-title HTTP) */
/**
 * Feed certificates from a bulk source (tools/lib/wikidata_bulk.js and anything like it) straight into
 * the database. Idempotent by construction: the boards are unioned into whatever is already stored and
 * re-scored, so running the same batch twice changes nothing, and a bulk source can never overwrite a
 * board a live lookup found.
 *
 * `entry` = { identity, imdbId?, certs: { GB: '12A', … }, sources: ['wikidata'], genres?, title?, year?, type? }
 */
function ingest(entry) {
  load();
  refreshFromDisk();
  const key = String(entry && entry.identity || '');
  if (!key) return null;
  const prev = state.ratings[key];
  const added = {
    identity: key,
    imdbId: entry.imdbId || imdbIdOf(key) || null,
    certs: entry.certs || {},
    genres: entry.genres || null,
    title: entry.title || null,
    year: entry.year || null,
    type: entry.type || null,
    adultFlag: Boolean(entry.adultFlag),
    sources: entry.sources || ['bulk'],
    fetchedAt: Date.now(),
  };
  const merged = unionRecord(prev, added);
  // The stored tier is the score of the boards, never the policy default: a record says what the boards
  // said, and know() decides what to do when they said nothing.
  const s = scoreRecord(merged);
  merged.tier = s.tier;
  merged.minAge = s.minAge;
  merged.maturity = s.maturity;
  merged.unrated = s.unrated;
  merged.ladderVersion = LADDER_VERSION;
  if (s.unrated) merged.lookedUpAt = merged.lookedUpAt || Date.now(); // still a title we know nothing about
  state.ratings[key] = merged;
  save();
  return merged;
}

/**
 * Re-score every stored record from the boards it already holds — no network, no quota. That is how a
 * fix to the ladder reaches the titles this box already knows about, and it is idempotent: scoring is a
 * pure function of the record's facts.
 */
function recomputeAll() {
  load();
  refreshFromDisk(true);
  const byTier = { 0: 0, 1: 0, 2: 0, 3: 0 };
  let changed = 0;
  let boards = 0;
  for (const [key, rec] of Object.entries(state.ratings)) {
    const s = scoreRecord(rec);
    byTier[s.tier == null ? 'none' : s.tier] = (byTier[s.tier == null ? 'none' : s.tier] || 0) + 1;
    boards += s.boards;
    if (rec.tier !== s.tier || rec.minAge !== s.minAge || rec.maturity !== s.maturity || rec.unrated !== s.unrated) {
      state.ratings[key] = { ...rec, tier: s.tier, minAge: s.minAge, maturity: s.maturity, unrated: s.unrated, ladderVersion: LADDER_VERSION };
      changed++;
    } else if (rec.ladderVersion !== LADDER_VERSION) {
      state.ratings[key] = { ...rec, ladderVersion: LADDER_VERSION };
    }
  }
  save();
  return { changed, byTier, boards, entries: Object.keys(state.ratings).length };
}

// Addons the household marked `"adult": true` in data/addons.json. Read straight from the file, with an
// mtime check, so this rule can ask it per item without a parse per item and without importing the addon
// host (which imports the registry, which imports the rules — a circle).
let addonStamp = 0;
let addonIds = [];
function adultAddonIds() {
  try {
    const st = fs.statSync(ADDONS_FILE);
    if (st.mtimeMs !== addonStamp) {
      addonStamp = st.mtimeMs;
      const list = JSON.parse(fs.readFileSync(ADDONS_FILE, 'utf8'));
      const arr = Array.isArray(list) ? list : (list && list.addons) || [];
      addonIds = arr.filter((e) => e && e.adult === true).map((e) => String(e.id));
    }
    return addonIds;
  } catch (e) {
    return [];
  }
}

/**
 * The tier from what the hub already holds, with no network: an addon the household marked `adult`,
 * a folder in media/ named like "Private", a genre that says Adult, words in the title or synopsis.
 * Returns null when the heuristic has no opinion, which is when UNRATED applies.
 */
function heuristic(item, opts = {}) {
  if (!item) return null;
  const addonsMarked = Array.isArray(opts.adultAddons) ? opts.adultAddons : [];
  const from = Array.isArray(item._addons) ? item._addons : [];
  if (from.some((id) => addonsMarked.includes(id))) return { ...TIER3, because: 'addon marked adult' };
  const genres = Array.isArray(item.genres) ? item.genres : [];
  if (genres.some((g) => ADULT_GENRE.includes(g))) return { ...TIER3, because: 'genre Adult' };
  if (genres.some((g) => PRIVATE_DIR.test(String(g)))) return { ...TIER3, because: 'a private folder in media/' };
  const text = `${item.title || ''} ${item.description || ''}`;
  if (ADULT_WORDS.test(text)) return { ...TIER3, because: 'the title says so' };
  const stated = ladderFor(item.maturity); // some addon does state a certificate: use it
  if (stated) return { tier: stated.tier, minAge: stated.minAge, because: 'certificate ' + stated.cert };
  return null;
}

/**
 * The tier for a title NO board rated, from signals the hub already has. This is deliberately a separate
 * concept from UNRATED: "no board spoke" is not the same as "this is adult".
 *
 *   - an item that arrived through a shelf curated for children (the row stamped `catalog:<id>`) is not
 *     hidden from the child the shelf was built for; a young genre on it makes it tier 0, anything else
 *     still keeps a teen floor;
 *   - a children's genre anywhere (Animation/Family/Kids) with no adult genre on it is children's
 *     content, with a soft floor that a long feature raises a little;
 *   - an adult genre and no children's genre keeps the conservative adult default — visible to the
 *     adults, invisible to the kids, exactly as before;
 *   - anything else has no opinion, and UNRATED still applies.
 *
 * Never returns tier 3, so an inference can never hide a title from an adult.
 */
function inferTier(item, opts = {}) {
  if (!item) return null;
  if (item.type !== 'movie' && item.type !== 'series') return null; // a channel or a file has no rating to infer
  const genres = (Array.isArray(item.genres) ? item.genres : []).map((g) => String(g));
  const young = genres.filter((g) => YOUNG_GENRES.includes(g));
  const hard = genres.filter((g) => ADULT_HINT_GENRES.includes(g));
  const from = Array.isArray(item._addons) ? item._addons : [];
  const curatedForKids = from.some((id) => KIDS_CATALOG_MARK.test(String(id)));
  const runtimeMin = Number.isFinite(item.durationMs) && item.durationMs > 0 ? item.durationMs / 60000 : null;

  if (hard.length && !young.length) {
    return { tier: UNRATED.tier, minAge: UNRATED.minAge, inferred: true, because: `inferred: no board, and it is filed as ${hard[0]}` };
  }
  if (young.length && hard.length) {
    return { tier: 1, minAge: 13, inferred: true, because: `inferred: ${young[0]} but also ${hard[0]}, and no board rated it` };
  }
  if (curatedForKids) {
    return young.length
      ? { tier: 0, minAge: 0, inferred: true, because: `inferred: it is on the kids shelf and filed as ${young[0]}` }
      : { tier: 1, minAge: 13, inferred: true, because: 'inferred: it is on a kids shelf, but nothing says it is for young children' };
  }
  if (young.length) {
    const long = runtimeMin != null && runtimeMin > 150;
    return {
      tier: 0,
      minAge: long ? 12 : 7,
      inferred: true,
      because: `inferred: filed as ${young.join('/')}, no board rated it${long ? ' and it runs long' : ''}`,
    };
  }
  return null; // no cheap signal speaks: UNRATED applies
}

/**
 * What the hub knows about a title right now, cheap and synchronous, for the rules.
 * `defaulted: true` means the tier is a policy default rather than something a board said: either an
 * inference from cheap signals (`inferred: true`, and `from` says which signals) or the UNRATED floor.
 * A caller that can act differently on that (a live channel has no certificate to find) should check it.
 */
function know(identity, item, opts = {}) {
  if (!Array.isArray(opts.adultAddons)) opts = { ...opts, adultAddons: adultAddonIds() };
  const rec = peek(identity);
  const facts = rec ? scored(rec) : null;
  if (facts && !facts.unrated && facts.tier != null) {
    // A board answered. Strictest board wins, and nothing here guesses.
    return {
      tier: facts.tier, minAge: facts.minAge, maturity: facts.maturity || null,
      from: 'ratings', defaulted: false, boards: facts.boards, record: rec,
    };
  }
  const h = heuristic(item, opts);
  if (h) return { tier: h.tier, minAge: h.minAge, maturity: (facts && facts.maturity) || null, from: h.because, defaulted: false, record: rec };
  const inf = inferTier(item, opts);
  if (inf) {
    return {
      tier: inf.tier, minAge: inf.minAge,
      maturity: (facts && facts.maturity) || null, // no board spoke, so no certificate is claimed
      from: inf.because, defaulted: true, inferred: true, record: rec,
    };
  }
  return {
    tier: UNRATED.tier, minAge: UNRATED.minAge, maturity: (facts && facts.maturity) || null,
    from: rec ? 'asked, nothing stated' : 'not looked up', defaulted: true, record: rec,
  };
}

function status() {
  load();
  refreshFromDisk();
  const all = Object.values(state.ratings);
  const byTier = { 0: 0, 1: 0, 2: 0, 3: 0, none: 0 };
  let withCertificate = 0; // at least one board said something we could read
  let askedNothing = 0;
  let boardStrings = 0;
  let unknownStrings = 0;
  let boards = 0;
  for (const r of all) {
    const s = scored(r);
    byTier[s.tier == null ? 'none' : s.tier] = (byTier[s.tier == null ? 'none' : s.tier] || 0) + 1;
    if (s.boards > 0) withCertificate++;
    if (r.sources && r.sources.length && s.unrated) askedNothing++;
    boards += s.boards;
    unknownStrings += s.unknownBoards;
    boardStrings += Object.keys(r.certs || {}).length;
  }
  const k = readKeys();
  return {
    file: FILE, entries: all.length, withCertificate, askedNothing, byTier,
    boards, boardStrings, unknownBoardStrings: unknownStrings, // unknownBoardStrings is the ladder's own to-do list
    keys: Object.keys(k).filter((name) => name !== '_note' && k[name]), // never the values
    budgetMs: BUDGET_MS, missTtlMs: MISS_TTL_MS,
    upstreamCallsToday: spentToday(), dailyCap: DAILY_CAP,
    ladderRows: LADDER.length, ladderVersion: LADDER_VERSION,
    boardScope: BOARD_SCOPE, homeBoards: HOME_BOARDS, // /healthz shows which of the two rules is live
  };
}

module.exports = {
  LADDER, UNRATED, TIER3, ADULT_GENRE, PRIVATE_DIR, YOUNG_GENRES, ADULT_HINT_GENRES, AGE_WORDS, BOARD_LETTERS,
  LADDER_VERSION,
  lookup, lookupWithin, peek, know, heuristic, inferTier, status, ladderFor, imdbIdOf, adultAddonIds,
  scoreRecord, recomputeAll, ingest, unionRecord, flush: writeNow,
  SHARED_VOCAB, normCert, BOARD_SCOPE, HOME_BOARDS,
  FILE, KEYS_FILE, MISS_TTL_MS, DAILY_CAP,
};
