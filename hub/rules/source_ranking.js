'use strict';
/*
 * rules/source_ranking.js — one tile, many ways to play it: put the one this profile wants first.
 *
 * Providers never rank anything. An addon hands over sources[] and this rule scores every one of them
 * against the profile's prefs (core/prefs.js), sorts best-first and writes machine-readable reasons
 * onto each source, so the app can show "why this one" instead of guessing:
 *
 *   { "provider": "torrent",  "label": "1080p · 3.9 GB", "score": 812,
 *     "why": ["Hindi audio", "1080p", "3.9 GB under cap"] }
 *
 * The order the owner asked for is enforced as hard constraints first, preferences after:
 *   1. language allow-list   2. size cap   3. bitrate ceiling
 *   4. audio-language preference   5. quality   6. file size
 * A source that breaks a hard constraint is pushed to the bottom, not deleted: a torrent is always
 * still there for a client that can play one, even when it is not what this household wants first.
 *
 * Step 9 adds the one thing metadata cannot know: whether this link is actually delivering bytes on
 * this network right now (core/probe.js measured it). That also produces the two fields the app renders
 * as a row, so a person chooses a link instead of trying three:
 *
 *   { "label": "1080p · 3.9 GB", "tagline": "1080p · Direct MP4 · 3.9 GB · Multi-audio Hindi/English",
 *     "status": "available", "checked": true,
 *     "why": ["measured 4s ago: answered in 180ms at 6.2 Mbps, enough for the ~4 Mbps this file needs"] }
 *
 * `status` is exactly three values: "available" (measured fast, or nothing says otherwise),
 * "unavailable" (it answered, but slower than this file needs - buffering), "down" (the hub opened it
 * and it was not there). `checked: false` says the hub could not measure this one cheaply, so the
 * status is metadata's opinion and not a measurement. With PROBE_ENABLED=0 this rule adds neither
 * field and no probe weight is applied: the response is exactly what it was before probing existed.
 */

const { resolve } = require('../core/prefs');
const liveHealth = require('../core/live-health');
const probe = require('../core/probe');
const settings = require('../core/settings');

/** Weights live here, in one block, so "make quality matter more" is a two-character change. */
/** Below this many peers a torrent cannot deliver a film in real time; see `starvedSwarm` in the table. */
const STARVED_SEEDERS = 5;

const W = {
  outsideLanguages: -1000, // 1. hard: an audio language this profile never watches
  overSizeCap: -1000, // 2. hard
  overBitrateCap: -600, // 3. hard
  preferredAudio: 400, // 4. first choice; each fallback step is worth 120 less
  fallbackStep: 120,
  unpreferredAudio: -150,
  // Audio language is decided BEFORE picture and bytes in this household, so a copy whose file proves it
  // carries the profile's first-choice language gets a margin larger than everything else this rule can
  // put on the board at once: the whole size bonus (120), a full quality band (40) and the largest
  // measurement swing there is (probeFast + probeOk = 420) total 580, and this beats that. That is what
  // makes a proven Hindi 720p outrank an English-only 1080p with 400 seeders — the Spider-Verse case,
  // where the copy on offer stated English (`a:2 aac 6ch eng`, nothing Hindi at all) and had to be
  // reported as that rather than quietly served as if the household's first language were covered.
  // A name that merely says "Hindi" keeps the weaker claim weights above, so proof beats marketing.
  audioFirst: 900,    // stated in the profile's first-choice language
  audioFallback: 300, // stated in a later language on the profile's list: still proof, still beats a claim
  audioStatedWrong: -450, // stated, and proven not to be a language this profile watches
  // A dead swarm is not a slow copy, it is a copy that never starts. Every other preference in this table
  // is a matter of degree; this one disqualifies, and the picker's own walk down the list then hands the
  // viewer the live copy instead. It sits ABOVE `audioFirst` on purpose: a Hindi copy nobody is seeding
  // cannot play, so it must lose to an English copy that can.
  deadSwarm: -1500,
  // A few seeders is not a dead copy, it is a copy that cannot be fed in real time. Measured this month on
  // a Hindi title: the ranked winner had 3 seeders and served no bytes at all in 140 seconds, while a copy
  // of the same film with 225 seeders sat below it in the list. That is the buffering complaint in one row.
  // The penalty is large enough to lose to any copy the swarm can actually feed, and deliberately smaller
  // than `audioFirst`: where the file itself proves it carries the profile's language, switching copies
  // means switching language, and this household ranks that above bitrate. A thin swarm loses to a healthy
  // one only when what it offers in exchange is a name on a release title.
  starvedSwarm: -450,
  qualityMatch: 160, // 5. exactly the picture level this profile asked for
  qualityStep: 40, // per quality band away from what was asked
  qualityOverAsk: 20, // sharper than asked: still good, just not the level the cap was sized for
  webLink: -1000, // a service landing page, not a file this TV can open
  probedFeed: 60, // the live feed the hub watched answer, over an untried one of the same quality
  sizeBonus: 120, // 6. smaller is better, scaled against the cap
  adaptiveBonus: 80, // prefer a ladder the player can buffer over a swarm it has to wait for
  torrentPenalty: -60,
  probeOk: 120, // 9. measured, and it really streamed
  probeFast: 300, // ... at least the bitrate this particular file needs
  probeSlow: -260, // answered, but slower than this file needs: this is the buffering complaint
  probeDead: -1400, // hard: measured and unreachable, or a 200 that turned out to be a web page
  probeSeekable: 40, // byte ranges: the player can recover from a stall instead of restarting
  probeSlowStart: -60, // took over a second just to answer
};

// How far above the bitrate a file needs a link must deliver before it counts as fast. A source that
// only just keeps up stalls the moment the Wi-Fi does, so 25% of headroom is the bar.
const HEADROOM = 1.25;
// A file that states neither size nor bitrate still needs *something*; 3 Mbps is roughly 1080p.
const UNKNOWN_NEED_MBPS = 3;

const QUALITY_RANK = { '2160p': 5, '1080p': 4, '720p': 3, '480p': 2, '360p': 1 };
// What each ceiling the profile can ask for is called, so `why[]` can name the level that was missed.
const LEVEL_NAME = { 5: '4K', 4: '1080p', 3: '720p', 2: '480p' };
// Measured-ish averages for these codecs, used when a source states no size or bitrate.
const QUALITY_MBPS = { '2160p': 15, '1080p': 8, '720p': 5, '480p': 2.5, '360p': 1.2 };
const LANG_NAMES = {
  hin: 'Hindi', eng: 'English', pan: 'Punjabi', tam: 'Tamil', tel: 'Telugu', mal: 'Malayalam',
  kan: 'Kannada', ben: 'Bengali', mar: 'Marathi', urd: 'Urdu', guj: 'Gujarati',
  // A copy's stated language is printed whether or not the household watches it, so the names cannot stop
  // at the languages this hub's owner chose — a Russian dub is a fact about a file, and "rus" is not.
  rus: 'Russian', spa: 'Spanish', por: 'Portuguese', fre: 'French', fra: 'French', ger: 'German',
  ita: 'Italian', jpn: 'Japanese', kor: 'Korean', chi: 'Chinese', zho: 'Chinese', ara: 'Arabic',
  per: 'Persian', tur: 'Turkish', pol: 'Polish', ukr: 'Ukrainian', ned: 'Dutch', dut: 'Dutch',
};

// Three-letter codes come in two families - ISO 639-2/T (fra, zho, deu, nld) and the older bibliographic
// forms (fre, chi, ger, dut) some files still state. They name the same language, so a screen that lists
// every key prints "French" twice. Lookups answer both; the list a UI draws names each one once, under the
// code most sources actually state.
const LEGACY_CODES = /^(fre|chi|ger|dut|swe|dan|pol|gre|alb|arm|baq|bur|cze|fin|flo|geo|hol|ice|mac|may|per|rum|slo|srb|swh|tha|tib|tur|ukr|vie)$/;
function preferredCode(name, current, candidate) {
  if (!current) return candidate;
  if (LEGACY_CODES.test(current) && !LEGACY_CODES.test(candidate)) return candidate;
  return current;
}

/** Same map shape the screens already read, one entry per language, keyed by the code to ask for. */
const LANG_UI = (() => {
  const byName = {};
  for (const [code, name] of Object.entries(LANG_NAMES)) byName[name] = preferredCode(name, byName[name], code);
  const out = {};
  for (const name of Object.keys(byName)) out[byName[name]] = name;
  return out;
})();

/** 1 → "1st", 2 → "2nd": `why` is read by a person, so "2th" is not acceptable. */
function ord(n) {
  if (n % 100 >= 11 && n % 100 <= 13) return n + 'th';
  return n + ({ 1: 'st', 2: 'nd', 3: 'rd' }[n % 10] || 'th');
}

function humanSize(bytes) {  if (!Number.isFinite(bytes)) return '';
  if (bytes >= 1e9) return (bytes / 1e9).toFixed(2).replace(/\.?0+$/, '') + ' GB';
  if (bytes >= 1e6) return Math.round(bytes / 1e6) + ' MB';
  return Math.round(bytes / 1e3) + ' KB';
}

/** The languages this source is in, if anything states it (a torrent name, a channel's own tag). */
function sourceLanguages(source, item) {
  if (Array.isArray(source._langs) && source._langs.length) return source._langs;
  if (Array.isArray(item.languages) && item.languages.length) return item.languages;
  return null;
}

/**
 * The languages the file itself states, read out of the container by the hub (core/media.js). This is
 * evidence rather than a claim: an upload naming itself "Hindi" is marketing, an audio track tagged
 * `hin` is a fact about the bytes that are about to play. `und` is ffprobe's word for "no tag at all"
 * and proves nothing either way, so it is not a stated language.
 */
function statedLanguages(source) {
  const tracks = Array.isArray(source && source.audioTracks) ? source.audioTracks : [];
  const out = [];
  for (const t of tracks) {
    const l = String((t && t.language) || '').toLowerCase();
    if (l && l !== 'und' && l !== 'mis' && !out.includes(l)) out.push(l);
  }
  return out;
}

/** Codec names the way they appear on a receiver's own display. */
const CODEC_NAMES = {
  eac3: 'E-AC3', ac3: 'AC3', aac: 'AAC', mp3: 'MP3', dts: 'DTS', 'dts-hd': 'DTS-HD', truehd: 'TrueHD',
  flac: 'FLAC', opus: 'Opus', vorbis: 'Vorbis',
};
function codecName(codec) {
  const c = String(codec || '').toLowerCase();
  return CODEC_NAMES[c] || (c ? c.toUpperCase() : '');
}

/** Channel count in the words a soundbar uses. 6 is 5.1, and that is the difference being described. */
function channelsName(n) {
  if (n === 1) return 'mono';
  if (n === 2) return 'stereo';
  if (n === 6) return '5.1';
  if (n === 8) return '7.1';
  if (Number.isFinite(n) && n > 2) return n + '.0';
  return '';
}

function estimateMbps(source, item) {
  if (Number.isFinite(source.bitrateMbps)) return source.bitrateMbps;
  if (Number.isFinite(source.sizeBytes) && Number.isFinite(item.durationMs) && item.durationMs > 0) {
    return (source.sizeBytes * 8) / 1e6 / (item.durationMs / 1000);
  }
  return QUALITY_RANK[source.quality] ? QUALITY_MBPS[source.quality] : null;
}

/**
 * What kind of link this is, in the two words a viewer reads it as. Built from the transport the
 * addon declared, never from a guess about the file: the point of the tagline is that the person can
 * pick "4K · Adaptive HLS" over "1080p · Torrent" without opening three links to find out.
 */
function transportName(source, item) {
  const kind = String(source.kind || '').toLowerCase();
  const url = String(source._url || source.url || '').toLowerCase();
  const live = item && item.type === 'channel';
  if (kind === 'torrent') return 'Torrent';
  if (kind === 'link' || source.link) return 'Opens ' + (source.label || 'a service');
  if (kind === 'hls' || /\.m3u8([?#]|$)/.test(url)) return live ? 'Live HLS' : 'Adaptive HLS';
  if (kind === 'dash' || kind === 'mpd' || /\.mpd([?#]|$)/.test(url)) return live ? 'Live DASH' : 'MPEG-DASH';
  const ext = /\.(mp4|mkv|mov|webm|avi|ts|m2ts)([?#]|$)/.exec(url);
  if (ext) return 'Direct ' + ext[1].toUpperCase();
  if (live) return 'Live stream';
  if (source.provider === 'youtube') return 'YouTube stream';
  if (source.provider === 'file') return 'File on this server';
  return 'Direct stream';
}

/**
 * What a subtitle track is called on the line a person reads. Text tracks arrive from core/media.js with
 * a `format` (the converter can render them); bitmap tracks are named by codec, because the only honest
 * thing to say about a PGS stream is that a browser cannot show it.
 */
const SUB_NAMES = {
  hdmv_pgs_subtitle: 'PGS', dvd_subtitle: 'DVD', dvdspu: 'DVD', teletext: 'Teletext',
};

/**
 * One line, ~40 characters, honest: quality · transport · size · audio · subtitles. This is the row the
 * app renders, so it is assembled here rather than left for the TV to reconstruct from `why[]`.
 *
 * The audio bit names the track this profile would actually hear and what it is encoded as, because that
 * is the difference between two copies that otherwise read the same: `1080p · Torrent · 1.9 GB · Hindi
 * audio · E-AC3 5.1 · ASS subs` is a decision a person can make, `Multi-audio` is not.
 */
function taglineFor(source, item, langs, prefs, dead) {
  const bits = [];
  if (source.quality === '2160p') bits.push('4K');
  else if (source.quality) bits.push(source.quality);
  bits.push(transportName(source, item));
  if (Number.isFinite(source.sizeBytes)) bits.push(humanSize(source.sizeBytes));
  const audio = Array.isArray(source.audioTracks) ? source.audioTracks : [];
  const subs = Array.isArray(source.subtitleTracks) ? source.subtitleTracks : [];
  if (audio.length) {
    const want = (prefs && prefs.audioLanguages) || [];
    const pick = want.map((l) => audio.find((t) => t.language === l)).find(Boolean)
      || audio.find((t) => t.default) || audio[0];
    const name = LANG_NAMES[pick.language]
      || (pick.language && pick.language !== 'und' ? pick.language : null);
    bits.push([name ? name + ' audio' : 'audio',
      [codecName(pick.codec), channelsName(pick.channels)].filter(Boolean).join(' ')]
      .filter(Boolean).join(' '));
    if (audio.length > 1) {
      const others = statedLanguages(source).filter((l) => l !== pick.language);
      if (others.length) bits.push('+ ' + others.map((l) => LANG_NAMES[l] || l).join('/'));
    }
  } else if (langs && langs.length > 1) {
    bits.push('Multi-audio ' + langs.map((l) => LANG_NAMES[l] || l).join('/'));
  } else if (langs && langs.length === 1) {
    bits.push((LANG_NAMES[langs[0]] || langs[0]) + ' audio');
  }
  const subNames = [];
  for (const t of subs) {
    const n = String(t.format || SUB_NAMES[String(t.codec || '').toLowerCase()] || '').toUpperCase();
    if (n && !subNames.includes(n)) subNames.push(n);
  }
  if (subNames.length) bits.push(subNames.slice(0, 2).join('/') + ' subs');
  if (dead) bits.push('nobody is seeding it');
  const line = bits.filter(Boolean).join(' \u00b7 ');
  // A ceiling on length, but not by cutting a word in half: "SRT s" reads like a bug, and this line is
  // the one place the language facts reach a viewer who never opens the source list.
  if (line.length <= 72) return line;
  const cut = line.slice(0, 72);
  const sp = cut.lastIndexOf(' \u00b7 ') !== -1 ? cut.lastIndexOf(' \u00b7 ') : cut.lastIndexOf(' ');
  return (sp > 30 ? cut.slice(0, sp) : cut) + ' \u2026';
}

/** "just now" / "12s ago" / "4 min ago" / "2 h ago" - how current this evidence is, in words. */
function agoText(ageMs) {
  if (!Number.isFinite(ageMs) || ageMs < 2000) return 'just now';
  if (ageMs < 90000) return Math.round(ageMs / 1000) + 's ago';
  if (ageMs < 3600000) return Math.round(ageMs / 60000) + ' min ago';
  return Math.round(ageMs / 3600000) + ' h ago';
}

// The household's languages are the hub's setting, read when the rule runs: a value chosen in the UI
// changes the order on the next request, and a build that ships three languages preselected is shipping
// somebody else's household. `LANGS` (env) seeds that setting at install time and nothing more.
function languageAllow(ctx) {
  const r = ctx.profile && ctx.profile.rules && ctx.profile.rules['language-allowlist'];
  if (r && Array.isArray(r.allow)) return r.allow;
  return settings.languages();
}

/** Does this file fit under the profile's size ceiling? (A source that states no size is not capped.) */
function fitsCap(source, capBytes) {
  return !(Number.isFinite(source.sizeBytes) && source.sizeBytes > capBytes);
}

// ffprobe, a container's own label and a browser's capability check all spell the same video codec
// differently. The picker has to compare them, so they collapse to one family name here.
const VIDEO_CODEC_FAMILIES = {
  h264: ['h264', 'avc', 'avc1', 'avc3', 'h.264'],
  hevc: ['hevc', 'h265', 'hvc1', 'hev1', 'x265', 'h.265'],
  av1: ['av1', 'aom'],
  vp9: ['vp9'],
  mpeg2: ['mpeg2video', 'mpeg2'],
  mpeg4: ['mpeg4'],
  vc1: ['vc1', 'wmv3'],
};

function codecFamily(name) {
  const n = String(name || '').toLowerCase().trim();
  if (!n) return null;
  for (const [family, spellings] of Object.entries(VIDEO_CODEC_FAMILIES)) {
    if (spellings.includes(n)) return family;
  }
  return n; // an unknown codec stays itself rather than silently becoming something we can rank
}

module.exports = {
  LANG_UI,
  id: 'source-ranking',
  order: 40, // after the item rules, so it sorts what actually survived them
  weights: W,
  // Shared with the play picker: what a given copy needs to sustain playback, and how much margin to
  // demand before promising it. One place for this arithmetic, so the shelf and the picker can never
  // disagree about whether a link is fast enough.
  estimateMbps,
  HEADROOM,
  // Also shared: "what language does this copy state its audio is", which is the difference between a
  // proven Hindi track and one the uploader called Hindi in a filename.
  statedLanguages,
  codecFamily,
  // The code -> display-name table, shared with the settings screen so the owner picks
  // between "Hindi" and "Punjabi" rather than between "hin" and "pan".
  LANG_NAMES,

  async apply(item, ctx) {
    if (!Array.isArray(item.sources) || !item.sources.length) return item;
    const prefs = resolve(ctx.profile);
    const allow = languageAllow(ctx);
    const capBytes = prefs.maxSizeGB * 1e9;
    // The picture level this profile asked for (core/prefs.js), and the sharpest band that actually
    // fits its size cap. The second is what lets a fallback say *why* it fell back instead of guessing.
    const asked = Number.isFinite(prefs.qualityRank) ? prefs.qualityRank : 4;
    const bestUnderCap = item.sources.reduce(
      (m, s) => (fitsCap(s, capBytes) && (QUALITY_RANK[s.quality] || 0) > m ? QUALITY_RANK[s.quality] : m), 0);

    const scored = item.sources.map((s) => {
      let score = 0;
      const why = [];
      const langs = sourceLanguages(s, item);
      // Two different kinds of answer to "what language is the audio in": what the file states (a track
      // header, read by core/media.js) and what the upload claims (its filename, or the addon's tag).
      // Where the file speaks, it overrules the claim — that is the whole of "a copy that proves Hindi
      // audio must outrank one merely naming Hindi".
      const stated = statedLanguages(s);
      const evidence = stated.length ? stated : langs;

      // 1. language allow-list (a hard rule already dropped such items; this keeps sources honest too)
      if (allow.length && evidence && !evidence.some((l) => allow.includes(l))) {
        score += W.outsideLanguages;
        why.push('audio ' + evidence.map((l) => LANG_NAMES[l] || l).join('/') + ' outside your languages'
          + (stated.length ? ' (stated in the file)' : ''));
      }

      // 2. size cap
      if (Number.isFinite(s.sizeBytes) && s.sizeBytes > capBytes) {
        score += W.overSizeCap;
        why.push(humanSize(s.sizeBytes) + ' over your ' + prefs.maxSizeGB + ' GB cap');
      } else if (Number.isFinite(s.sizeBytes)) {
        score += Math.max(0, Math.round(W.sizeBonus * (1 - s.sizeBytes / capBytes)));
        why.push(humanSize(s.sizeBytes) + ' under your ' + prefs.maxSizeGB + ' GB cap');
      } else {
        why.push('no size cap (adaptive ladder)');
      }

      // 3. bitrate ceiling
      const mbps = estimateMbps(s, item);
      if (prefs.maxBitrateMbps && mbps && mbps > prefs.maxBitrateMbps) {
        score += W.overBitrateCap;
        why.push('~' + mbps.toFixed(1) + ' Mbps over your ' + prefs.maxBitrateMbps + ' Mbps limit');
      } else if (mbps) {
        why.push('~' + mbps.toFixed(1) + ' Mbps');
      }

      // 4. audio language — the first thing this household decides on, before picture or bytes. The order
      // is the profile's own (Hindi, then English, then Punjabi), and a stated track outranks a name that
      // merely promises one: the filename is what an uploader typed, the track header is what plays. The
      // stated weights are margins, not bonuses: `audioFirst` is bigger than every quality and size signal
      // combined, so an English-only 1080p cannot win on resolution against a Hindi copy.
      if (stated.length) {
        const wanted = prefs.audioLanguages
          .map((l) => ({ l, at: stated.indexOf(l) }))
          .filter((x) => x.at >= 0)
          .sort((a, b) => prefs.audioLanguages.indexOf(a.l) - prefs.audioLanguages.indexOf(b.l));
        if (wanted.length) {
          const prefIdx = prefs.audioLanguages.indexOf(wanted[0].l);
          score += prefIdx === 0 ? W.audioFirst : W.audioFallback;
          why.push('audio: ' + (LANG_NAMES[wanted[0].l] || wanted[0].l) + ' stated'
            + (prefIdx ? ' (your ' + ord(prefIdx + 1) + ' choice)' : '')
            + (stated.length > 1
              ? ', the file also carries ' + stated.filter((l) => l !== wanted[0].l).map((l) => LANG_NAMES[l] || l).join('/')
              : ''));
        } else {
          // Proven to be a language this profile does not watch — the strongest language evidence there is,
          // so it costs more than a filename saying the same thing would.
          score += W.audioStatedWrong;
          why.push('audio: ' + stated.map((l) => LANG_NAMES[l] || l).join('/')
            + ' stated, not a language you watch');
        }
      } else if (langs) {
        const wanted = prefs.audioLanguages.map((l) => ({ l, at: langs.indexOf(l) })).filter((x) => x.at >= 0);
        if (wanted.length) {
          wanted.sort((a, b) => a.at - b.at);
          const prefIdx = prefs.audioLanguages.indexOf(wanted[0].l);
          score += W.preferredAudio - prefIdx * W.fallbackStep;
          why.push('audio: ' + (LANG_NAMES[wanted[0].l] || wanted[0].l)
            + ' claimed by the name, not stated in the file' + (prefIdx ? ' (fallback)' : ''));
        } else {
          score += W.unpreferredAudio;
          why.push('audio ' + langs.map((l) => LANG_NAMES[l] || l).join('/') + ' — not your preference');
        }
      } else {
        why.push('audio: not stated');
      }

      // 5. quality: the level this profile asked for first, then down the ladder when it is not there.
      const rank = QUALITY_RANK[s.quality] || 0;
      if (prefs.quality === 'lowest') {
        score += (6 - rank) * W.qualityStep;
        if (s.quality) why.push(s.quality + ' (lowest first)');
      } else if (asked >= 99) {
        score += rank * W.qualityStep; // "auto": the sharpest thing that exists
        if (s.quality) why.push(s.quality + ' (sharpest available)');
      } else if (rank === asked) {
        score += W.qualityMatch;
        if (s.quality) why.push(s.quality + ' (profile quality)');
      } else if (rank > asked) {
        // Sharper than asked for: allowed — the size cap decides whether it fits — but it is not the
        // level the cap was sized around, so it does not win the picker on picture alone.
        score += W.qualityMatch - (rank - asked) * W.qualityOverAsk;
        if (s.quality) why.push(s.quality + ' above your ' + (LEVEL_NAME[asked] || asked) + ' request');
      } else {
        score += W.qualityMatch - (asked - rank) * W.qualityStep;
        if (s.quality) {
          why.push(bestUnderCap >= asked
            ? s.quality + ' — a step below your ' + (LEVEL_NAME[asked] || asked) + ' request'
            : s.quality + ' — no ' + (LEVEL_NAME[asked] || asked) + ' source above the size cap');
        }
      }

      // 6. transport: a ladder the player can buffer and seek beats a swarm it must wait for,
      // at comparable quality — but the torrent is still on the list.
      if (prefs.preferAdaptive && s.kind !== 'torrent') {
        score += W.adaptiveBonus;
        why.push('adaptive ladder, no peers to wait for');
      } else if (s.kind === 'torrent') {
        score += W.torrentPenalty;
        why.push('torrent: needs peers before it plays');
      }

      // 7. a "stream" that is really a link to a service page — what a service catalog like WatchHub
      // answers with for a title you can only subscribe to or rent. It stays in the list (the app can
      // offer "watch it on Prime"), but it is not a file this TV can open, so it never wins the picker
      // over one that is, and play() never hands the player a web page.
      if (s.link) {
        score += W.webLink;
        why.push('a link to ' + (s.label || 'a service') + ', not a file this TV can stream');
      }

      // 8. live TV: when a channel has several feeds, start the one the prober watched answer.
      if (item.type === 'channel') {
        const h = liveHealth.get(item.identity);
        if (h && h.ok && h.url && s._url === h.url) {
          score += W.probedFeed;
          why.push('the feed the hub watched answer' + (h.latencyMs != null ? ' in ' + h.latencyMs + 'ms' : ''));
        } else if (h && !h.ok) {
          score -= 20;
          why.push('the hub could not open this channel (' + (h.error || 'no answer') + ')');
        }
      }

      // 9. what the hub actually measured about this exact link (core/probe.js). This is ONE MORE
      // SIGNAL on top of the metadata ranking above, not a rewrite of it: a source that could not be
      // measured keeps precisely the score steps 1-8 gave it, and says so out loud in the why chain
      // instead of pretending to be verified.
      //
      // `status` is the three-value answer the app renders: "available" (measured fast, or not
      // measurable here and nothing says otherwise), "unavailable" (it answered, but too slowly for
      // this file - the buffering complaint), "down" (the hub opened it and it was not there).
      if (probe.ENABLED) {
        let status = 'available';
        const p = s.checked === true && s.probe ? s.probe : null;
        const age = p && Number.isFinite(p.checkedAt) ? Date.now() - Number(p.checkedAt) : null;
        const trusted = p && age != null && age <= probe.limits.OK_TTL_MS;
        const needs = mbps || UNKNOWN_NEED_MBPS;
        if (p && trusted) {
          const got = (Number.isFinite(p.kbps) ? p.kbps : 0) / 1000; // kbps -> Mbps
          const when = 'measured ' + agoText(age);
          if (!p.ok) {
            score += W.probeDead;
            status = 'down';
            why.push(when + ': the hub opened this and could not play it - ' + (p.error || 'no answer')
              + (p.latencyMs != null ? ' (' + p.latencyMs + 'ms to answer)' : ''));
          } else {
            score += W.probeOk;
            if (!p.rateReliable) {
              // It answered, and quickly enough that nothing here says otherwise - but the transfer was
              // a manifest's few hundred bytes over a millisecond, so no speed may be claimed either way.
              // Say what was measured and what was not, and leave the metadata ranking in charge.
              why.push(when + ': answered in ' + p.latencyMs + 'ms, but the response ended at ' + p.bytes
                + ' bytes - that is a manifest, not a stream, so its speed was never measured');
            } else if (got >= needs * HEADROOM) {
              score += W.probeFast;
              why.push(when + ': answered in ' + p.latencyMs + 'ms, '
                + (p.rateFloor ? 'delivering at least ' : 'at ') + got.toFixed(1)
                + ' Mbps, enough for the ~' + needs.toFixed(1) + ' Mbps this file needs');
            } else if (p.rateFloor) {
              // The sample arrived faster than it can be timed, so all we can prove is a minimum - and
              // that minimum is not high enough to promise this particular file will not stall. Say what
              // is known, claim nothing that is not.
              why.push(when + ': answered in ' + p.latencyMs + 'ms and delivered the whole sample inside '
                + p.sampleMs + 'ms - at least ' + got.toFixed(1) + ' Mbps, which is not yet proof of the '
                + '~' + needs.toFixed(1) + ' Mbps this file needs');
            } else {
              score += W.probeSlow;
              status = 'unavailable';
              why.push(when + ': only ' + got.toFixed(1) + ' Mbps over ' + Math.round(p.sampleMs)
                + 'ms, under the ~' + needs.toFixed(1) + ' Mbps this file needs: expect buffering');
            }
            if (p.seekable) {
              score += W.probeSeekable;
              why.push('seeks: it accepts byte ranges');
            }
            if (Number.isFinite(p.latencyMs) && p.latencyMs > 1000) {
              score += W.probeSlowStart;
              why.push('took ' + Math.round(p.latencyMs) + 'ms just to answer');
            }
          }
        } else if (p) {
          // Older than the probe cache's own trust window: worth telling the viewer, too old to move
          // the ranking on. A dead link can come back and a fast one can go quiet an hour later.
          why.push('measured ' + agoText(age) + ': '
            + (!p.ok ? (p.error || 'no answer')
              : p.rateReliable ? (Number.isFinite(p.kbps) ? (p.kbps / 1000).toFixed(1) + ' Mbps' : 'answered')
              : 'answered in ' + p.latencyMs + 'ms')
            + ' - too old to rank on');
        } else {
          why.push(s.kind === 'torrent' ? 'not measured: a torrent is a promise about a swarm, not a URL to open'
            : s.link ? 'not measured: a page to open, not a stream'
            : 'not measured: this addon mints its URL at play time');
        }
        s.status = status;
        s.tagline = taglineFor(s, item, langs, prefs, s.kind === 'torrent' && s.seeders === 0);
      }

      // 10. a dead swarm is not a slow copy, it is a copy that never starts. The measured case this
      // replaces: a 0-seeder torrent outranking a live one because quality outweighs everything else.
      // Language, then picture, then bytes still order the live copies among themselves — this only takes
      // the dead ones out of that contest and says so on the row, so the picker walks to a live copy
      // before it ever reaches this one. `seeders === 0` is the addon's own number; an addon that states
      // nothing leaves it null and is not penalised for it.
      if (s.kind === 'torrent' && s.seeders > 0 && s.seeders < STARVED_SEEDERS) {
        score += W.starvedSwarm;
        s.starvedSwarm = true;
        why.push(s.seeders + (s.seeders === 1 ? ' seeder' : ' seeders')
          + ': too few peers to feed this file at its own bitrate, so it stalls');
      }
      if (s.kind === 'torrent' && s.seeders === 0) {
        score += W.deadSwarm;
        s.deadSwarm = true; // a fact about the metadata, stated whether or not probing is on
        why.push('0 seeders: nobody is sharing this copy, so it will not start');
        // status/tagline stay inside the probe gate: with PROBE_ENABLED=0 this rule answers byte-for-byte
        // as it did before measuring existed, and the picker's own 0-seeder walk does not read status.
        if (probe.ENABLED) {
          s.status = 'down';
          s.tagline = taglineFor(s, item, langs, prefs, true);
        }
      }
      // The id the app sends back as `exclude` when a link buffers. Assigned here rather than in the
      // route so every response that carries sources - meta, rows, play - has it.
      if (!s.id) s.id = probe.sourceId(s);

      s.score = score;
      s.why = why;
      return s;
    });

    scored.sort((a, b) => b.score - a.score
      || String(a.risk || '9.9x').localeCompare(String(b.risk || '9.9x')));
    item.sources = scored;
    return item;
  },
};
