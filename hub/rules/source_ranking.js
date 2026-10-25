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
 */

const { resolve } = require('../core/prefs');
const liveHealth = require('../core/live-health');

/** Weights live here, in one block, so "make quality matter more" is a two-character change. */
const W = {
  outsideLanguages: -1000, // 1. hard: an audio language this profile never watches
  overSizeCap: -1000, // 2. hard
  overBitrateCap: -600, // 3. hard
  preferredAudio: 400, // 4. first choice; each fallback step is worth 120 less
  fallbackStep: 120,
  unpreferredAudio: -150,
  qualityMatch: 160, // 5. exactly the picture level this profile asked for
  qualityStep: 40, // per quality band away from what was asked
  qualityOverAsk: 20, // sharper than asked: still good, just not the level the cap was sized for
  webLink: -1000, // a service landing page, not a file this TV can open
  probedFeed: 60, // the live feed the hub watched answer, over an untried one of the same quality
  sizeBonus: 120, // 6. smaller is better, scaled against the cap
  adaptiveBonus: 80, // prefer a ladder the player can buffer over a swarm it has to wait for
  torrentPenalty: -60,
};

const QUALITY_RANK = { '2160p': 5, '1080p': 4, '720p': 3, '480p': 2, '360p': 1 };
// What each ceiling the profile can ask for is called, so `why[]` can name the level that was missed.
const LEVEL_NAME = { 5: '4K', 4: '1080p', 3: '720p', 2: '480p' };
// Measured-ish averages for these codecs, used when a source states no size or bitrate.
const QUALITY_MBPS = { '2160p': 15, '1080p': 8, '720p': 5, '480p': 2.5, '360p': 1.2 };
const LANG_NAMES = {
  hin: 'Hindi', eng: 'English', pan: 'Punjabi', tam: 'Tamil', tel: 'Telugu', mal: 'Malayalam',
  kan: 'Kannada', ben: 'Bengali', mar: 'Marathi', urd: 'Urdu', guj: 'Gujarati',
};

const DEFAULT_ALLOW = (process.env.LANGS === undefined ? 'hin,eng,pan' : process.env.LANGS)
  .split(',').map((s) => s.trim()).filter(Boolean);

function humanSize(bytes) {
  if (!Number.isFinite(bytes)) return '';
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

function estimateMbps(source, item) {
  if (Number.isFinite(source.bitrateMbps)) return source.bitrateMbps;
  if (Number.isFinite(source.sizeBytes) && Number.isFinite(item.durationMs) && item.durationMs > 0) {
    return (source.sizeBytes * 8) / 1e6 / (item.durationMs / 1000);
  }
  return QUALITY_RANK[source.quality] ? QUALITY_MBPS[source.quality] : null;
}

function languageAllow(ctx) {
  const r = ctx.profile && ctx.profile.rules && ctx.profile.rules['language-allowlist'];
  if (r && Array.isArray(r.allow)) return r.allow;
  return DEFAULT_ALLOW;
}

/** Does this file fit under the profile's size ceiling? (A source that states no size is not capped.) */
function fitsCap(source, capBytes) {
  return !(Number.isFinite(source.sizeBytes) && source.sizeBytes > capBytes);
}

module.exports = {
  id: 'source-ranking',
  order: 40, // after the item rules, so it sorts what actually survived them
  weights: W,

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

      // 1. language allow-list (a hard rule already dropped such items; this keeps sources honest too)
      if (allow.length && langs && !langs.some((l) => allow.includes(l))) {
        score += W.outsideLanguages;
        why.push('audio ' + langs.map((l) => LANG_NAMES[l] || l).join('/') + ' outside your languages');
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

      // 4. audio-language preference
      if (langs) {
        const wanted = prefs.audioLanguages.map((l) => ({ l, at: langs.indexOf(l) })).filter((x) => x.at >= 0);
        if (wanted.length) {
          wanted.sort((a, b) => a.at - b.at);
          const prefIdx = prefs.audioLanguages.indexOf(wanted[0].l);
          score += W.preferredAudio - prefIdx * W.fallbackStep;
          why.push((LANG_NAMES[wanted[0].l] || wanted[0].l) + ' audio' + (prefIdx ? ' (fallback)' : ''));
        } else {
          score += W.unpreferredAudio;
          why.push('audio ' + langs.map((l) => LANG_NAMES[l] || l).join('/') + ' — not your preference');
        }
      } else {
        why.push('audio language not stated');
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
