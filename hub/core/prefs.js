'use strict';
/*
 * core/prefs.js — what this profile wants a stream to be, in one place.
 *
 * rules/source_ranking.js and rules/size_cap.js are the only things that read these numbers to decide
 * anything, so the weights live with the rules and a household changes its mind by editing JSON, not
 * code. Everything here is additive and null-safe: an older hub/profile without these fields resolves
 * to the same behaviour it had before.
 *
 *   audioLanguages  first choice, then fallbacks, ISO-639-1 codes ("hin", "eng", "pan")
 *   quality         "highest" | "lowest" — how much the picture matters against everything else
 *   qualityCap      "auto" | "4k" | "1080p" | "720p" | "480p" — the sharpest picture this profile asks
 *                   for. Defaults to 1080p for an adult profile and 720p for a kids one: a 1080p file is
 *                   the sweet spot for a living-room Wi-Fi link, and a kid's player should not be asked
 *                   to buffer a 20 GB remux. "auto" asks for the sharpest thing that exists.
 *   maxSizeGB       files above this are dropped by rules/size_cap.js; derived from qualityCap unless
 *                   the profile says otherwise, because the two are the same decision spelled twice
 *   preferAdaptive  HTTP/HLS/ladder sources score above a torrent of the same quality: no peers to
 *                   wait for, and it can be buffered and seeked by any player
 *   maxBitrateMbps  a ceiling for the network this house actually has; over-ceiling sources sink. Not
 *                   set at all for 1080p/4k caps — a 6 Mbps clamp would silently forbid 1080p.
 */

// What each requested level means in bytes and bandwidth, and how it ranks against a source's own
// quality string. A 1080p Blu-ray rip is ~4-8 GB; a 4K remux is 15-25 GB. These are ceilings for what
// the addons are allowed to keep, not claims about a specific file.
const LEVELS = {
  '480p': { rank: 2, maxSizeGB: 2, maxBitrateMbps: 4 },
  '720p': { rank: 3, maxSizeGB: 4, maxBitrateMbps: 8 },
  '1080p': { rank: 4, maxSizeGB: 8, maxBitrateMbps: null },
  '4k': { rank: 5, maxSizeGB: 25, maxBitrateMbps: null },
  auto: { rank: 99, maxSizeGB: 25, maxBitrateMbps: null }, // "sharpest thing that exists"
};

// The words a person actually types in a settings screen, mapped onto the levels above.
const CAP_ALIASES = {
  auto: 'auto', any: 'auto', highest: 'auto', off: 'auto',
  '4k': '4k', '2160p': '4k', uhd: '4k', '4k uhd': '4k',
  '1080p': '1080p', fullhd: '1080p', fhd: '1080p', '1080': '1080p',
  '720p': '720p', hd: '720p', '720': '720p',
  '480p': '480p', sd: '480p', '480': '480p',
};

const ADULT_CAP = process.env.QUALITY_CAP || '1080p';
const KIDS_CAP = process.env.KIDS_QUALITY_CAP || '720p';
// An operator can still force one size ceiling for every profile with SIZE_CAP_GB; when they do, that
// number wins over the derived one, because it is the explicit answer to "our line is this thin".
const SIZE_CAP_GB_ENV = Number(process.env.SIZE_CAP_GB || 0) || null;
const BITRATE_ENV = Number(process.env.MAX_BITRATE_MBPS || 0) || null;

function capFor(profile) {
  const raw = String((profile && profile.prefs && profile.prefs.qualityCap) || '').trim().toLowerCase();
  const cap = CAP_ALIASES[raw];
  if (cap) return cap;
  // Not spelled, or spelled wrong: the profile's kind decides, and the word the profile actually used
  // is reported back through `qualityCapAsked` rather than quietly treated as "no ceiling".
  return profile && profile.kind === 'kids' ? KIDS_CAP : ADULT_CAP;
}

// The starting point for every profile, before any preference is spelled anywhere. Kept as a literal
// (not resolve(null)) so resolve() can spread it without a circular reference.
const PROFILE_DEFAULTS = {
  audioLanguages: ['hin', 'eng'],
  quality: 'highest',
  preferAdaptive: true,
  // The dark-red zone (tier 3) is off until the household says otherwise. Off means those titles are not
  // in home, search, Continue Watching or history for anyone — see rules/maturity.js.
  xxxEnabled: false,
};

/** Effective prefs for a profile: defaults, then profile.prefs (hub/profiles.json or data/profiles.json). */
function resolve(profile) {
  const p = (profile && profile.prefs) || {};
  const qualityCap = capFor(profile);
  const level = LEVELS[qualityCap] || LEVELS.auto;

  const out = {
    ...PROFILE_DEFAULTS,
    qualityCap,
    // What was literally asked for, so the app can show the bad spelling instead of a silent default.
    qualityCapAsked: String(p.qualityCap || '').trim() || null,
    maxSizeGB: SIZE_CAP_GB_ENV || p.maxSizeGB || level.maxSizeGB,
    maxBitrateMbps: BITRATE_ENV || p.maxBitrateMbps || level.maxBitrateMbps || null,
    // Carried from the profile, then normalised below: a settings screen may send a boolean or a word.
    xxxEnabled: p.xxxEnabled !== undefined ? p.xxxEnabled : PROFILE_DEFAULTS.xxxEnabled,
  };
  if (!Array.isArray(out.audioLanguages) || !out.audioLanguages.length) {
    out.audioLanguages = (profile && profile.languages) || PROFILE_DEFAULTS.audioLanguages;
  }
  if (!Number.isFinite(out.maxSizeGB) || out.maxSizeGB <= 0) out.maxSizeGB = level.maxSizeGB;
  if (!Number.isFinite(out.maxBitrateMbps) || out.maxBitrateMbps <= 0) out.maxBitrateMbps = null;
  if (!['highest', 'lowest'].includes(out.quality)) out.quality = 'highest';
  out.preferAdaptive = out.preferAdaptive !== false;
  // Accept the switch however the app spells it, and nothing else: a string that is not a yes is a no.
  out.xxxEnabled = out.xxxEnabled === true || ['true', '1', 'yes', 'on'].includes(String(out.xxxEnabled).toLowerCase());
  // The rank a source's quality string is compared against, plus the byte form of the size ceiling.
  out.qualityRank = level.rank;
  out.maxSizeBytes = Math.round(out.maxSizeGB * 1e9);
  return out;
}

// What a profile with nothing set at all gets, fully resolved: the values a settings screen restores.
const DEFAULTS = resolve(null);

module.exports = { DEFAULTS, LEVELS, CAP_ALIASES, ADULT_CAP, KIDS_CAP, resolve, capFor };
