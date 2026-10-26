'use strict';
/*
 * core/langdb.js — what language a title is actually in, learned once and kept forever.
 *
 * The owner's language rule is hard: a house that watches Hindi, English and Punjabi should not be
 * handed a foreign film on its billboard. But a rule can only classify what it can see, and an addon
 * catalog publishes almost nothing per tile — of the 1529 titles this box has catalogued, none arrive
 * with a language. Verified rather than assumed: asking the hub for the meta of 60 titles answered a
 * language for zero of them, because a catalog tile and even a title's own meta record simply do not
 * state one. So the allowlist did the only honest thing left to it (keep what cannot be classified) and
 * the billboard filled with cinema the household will never watch.
 *
 * The fix is not another filter. It is knowing better, and keeping what we learn: one record per title,
 * written once, read by every later view of that title — the same bargain as data/ratings.json, and the
 * reason this hub does not hit an upstream API again for something it already knows.
 *
 * Unknown stays unknown, and that distinction is the whole design: `peek` returns null when the title
 * has never been asked about, and `[]` when it was asked and nothing was stated. The rule keeps unknown
 * titles exactly as it always did, and never asks a title twice.
 */
const fs = require('fs');
const path = require('path');

const FILE = path.join(__dirname, '..', 'data', 'languages.json');

/** identity -> ISO-639-3 codes. `[]` means "asked, and it has none". */
let db = new Map();
let dirty = false;
let saveTimer = null;

// The stamp of the file as this process last read or replaced it. Two different mistakes are possible
// when more than one process writes one file, and both are caught with this one number: overwriting rows
// someone else added, and never noticing rows that arrived while we were running.
let writtenAt = null;
let checkedAt = 0;

function stamp() {
  try { return fs.statSync(FILE).mtimeMs; } catch { return null; }
}

/** Fold the file's rows into memory. First answer wins, so nobody erases what is already known. */
function mergeFromDisk() {
  let raw;
  try {
    raw = JSON.parse(fs.readFileSync(FILE, 'utf8')) || {};
  } catch (e) {
    if (e.code !== 'ENOENT') console.error('[langdb] unreadable file:', e.message);
    return 0;
  }
  let gained = 0;
  for (const [k, v] of Object.entries(raw)) if (Array.isArray(v) && !db.has(k)) { db.set(k, v); gained++; }
  return gained;
}

function load() {
  mergeFromDisk();
  writtenAt = stamp();
}

/**
 * Notice what another process learned.
 *
 * The hub and a backfill run are separate processes writing this one file. A hub that read the file only
 * at boot would go on answering "never asked" for titles a backfill had already classified, which is how
 * a knowledge pass ends up changing nothing until someone restarts the box. So a read checks the file's
 * stamp — at most once a second, because one home screen touches a thousand identities — and folds in
 * whatever arrived. This is what makes `tools/backfill-languages.js` take effect without a restart.
 */
function refresh() {
  const now = Date.now();
  if (now - checkedAt < 1000) return;
  checkedAt = now;
  const s = stamp();
  if (!s || s === writtenAt) return;
  if (mergeFromDisk()) writtenAt = s;
}

/**
 * Write atomically, and never over something we have not seen.
 *
 * Measured, not feared: a backfill that had written 421 titles came back as 45, because the hub saved the
 * smaller map it had loaded at boot over the top of it. So the write re-reads the file if anyone has
 * touched it since and unions the two before replacing it, first answer winning.
 */
function writeNow() {
  if (!dirty) return;
  dirty = false;
  try {
    fs.mkdirSync(path.dirname(FILE), { recursive: true });
    const current = stamp();
    if (current !== writtenAt) mergeFromDisk();
    const tmp = FILE + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(Object.fromEntries(db)));
    fs.renameSync(tmp, FILE);
    writtenAt = stamp();
    checkedAt = Date.now();
  } catch (e) {
    console.error('[langdb] save failed:', e.message);
  }
}

/**
 * Batch the writes. A viewer opening a detail page teaches one title and should not pay for an fsync; a
 * backfill teaches thousands, so it calls `flush()` per batch and a stopped run never loses what it has
 * already fetched.
 */
function scheduleSave() {
  dirty = true;
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    writeNow();
  }, 1500);
  if (saveTimer.unref) saveTimer.unref();
}

function flush() {
  if (saveTimer) { clearTimeout(saveTimer); saveTimer = null; }
  writeNow();
}

/** What we know about this title: null when we have never asked, an array when we have. */
function peek(identity) {
  refresh();
  return db.has(identity) ? db.get(identity) : null;
}

/**
 * Record an answer. The first answer wins, so a later tile that says nothing cannot erase what a source
 * once stated.
 */
function learn(identity, codes) {
  if (!identity || !Array.isArray(codes)) return false;
  if (db.has(identity)) return false;
  db.set(identity, [...new Set(codes)]);
  scheduleSave();
  return true;
}

// Language names as sources state them, mapped to ISO-639-3 — the codes the rules compare against. This is
// a general vocabulary: it says what "Punjabi" means, not which languages this household watches (that
// choice is the owner's, and lives in settings).
const NAMES = {
  english: 'eng', hindi: 'hin', punjabi: 'pan', tamil: 'tam', telugu: 'tel', marathi: 'mar',
  malayalam: 'mal', bengali: 'ben', urdu: 'urd', gujarati: 'guj', kannada: 'kan', odia: 'ory',
  oriya: 'ory', bhojpuri: 'bho', maithili: 'mai', assamese: 'asm', nepali: 'nep', sinhala: 'sin',
  sinhalese: 'sin', chinese: 'zho', mandarin: 'cmn', 'mandarin chinese': 'cmn', cantonese: 'yue',
  spanish: 'spa', french: 'fra', german: 'deu', portuguese: 'por', italian: 'ita', dutch: 'nld',
  russian: 'rus', polish: 'pol', czech: 'ces', slovak: 'slk', hungarian: 'hun', romanian: 'ron',
  bulgarian: 'bul', croatian: 'hrv', serbian: 'srp', bosnian: 'bos', ukrainian: 'ukr', greek: 'ell',
  turkish: 'tur', arabic: 'ara', hebrew: 'heb', persian: 'fas', japanese: 'jpn', korean: 'kor',
  thai: 'tha', vietnamese: 'vie', indonesian: 'ind', malay: 'msa', tagalog: 'tgl', swedish: 'swe',
  norwegian: 'nob', danish: 'dan', finnish: 'fin', georgian: 'kat', armenian: 'hye',
};

// ISO-639-1 (two letters, what some sources publish) to the ISO-639-3 the rules work in: a translation
// table between two standards, not a household's preference.
const ISO1 = {
  en: 'eng', hi: 'hin', pa: 'pan', ta: 'tam', te: 'tel', mr: 'mar', ml: 'mal', bn: 'ben', ur: 'urd',
  gu: 'guj', kn: 'kan', or: 'ory', bh: 'bho', ne: 'nep', si: 'sin', zh: 'zho', es: 'spa', fr: 'fra',
  de: 'deu', pt: 'por', it: 'ita', nl: 'nld', ru: 'rus', pl: 'pol', cs: 'ces', sk: 'slk', hu: 'hun',
  ro: 'ron', bg: 'bul', hr: 'hrv', sr: 'srp', bs: 'bos', uk: 'ukr', el: 'ell', tr: 'tur', ar: 'ara',
  he: 'heb', fa: 'fas', ja: 'jpn', ko: 'kor', th: 'tha', vi: 'vie', id: 'ind', ms: 'msa', tl: 'tgl',
  sv: 'swe', no: 'nob', da: 'dan', fi: 'fin', ka: 'kat', hy: 'hye',
};

/** A source states a language by code ("hi", "eng"); the rules work in ISO-639-3 whichever it is. */
function learnFromCodes(identity, codes) {
  if (!identity || !Array.isArray(codes) || !codes.length) return false;
  const out = [];
  for (const raw of codes) {
    const key = String(raw || '').trim().toLowerCase();
    if (!key) continue;
    const code = ISO1[key] || (key.length === 3 ? key : null);
    if (code) out.push(code);
  }
  return learn(identity, out);
}

/**
 * A source states a language by name ("Hindi", "Punjabi"); the rules work in ISO-639-3. A name this table
 * does not know is dropped rather than guessed at, so an unfamiliar label leaves the title unclassified
 * instead of filing it under something invented.
 */
function learnFromNames(identity, names) {
  if (!identity || !Array.isArray(names) || !names.length) return false;
  const codes = [];
  for (const raw of names) {
    const key = String(raw || '').trim().toLowerCase();
    if (!key) continue;
    const code = NAMES[key] || (key.length === 3 ? key : null);
    if (code) codes.push(code);
  }
  return learn(identity, codes);
}

/** How much of the question this box can actually answer. */
function coverage() {
  refresh();
  let classified = 0;
  for (const v of db.values()) if (v.length) classified++;
  return { asked: db.size, classified, answeredNothing: db.size - classified };
}

load();

module.exports = { peek, learn, learnFromNames, learnFromCodes, coverage, flush, FILE, count: () => db.size };
