'use strict';
/*
 * No-network regression coverage for the listening experience:
 * - profile language order beats resolution when the file proves a preferred track;
 * - measured track evidence beats an uploader's filename claim; and
 * - the TV distinguishes a claimed donor from a confirmed mix candidate before the viewer picks it.
 */

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const ranking = require('../hub/rules/source_ranking');

const profile = {
  prefs: {
    audioLanguages: ['hin', 'eng'],
    quality: '1080p',
    maxSizeGB: 10,
    maxBitrateMbps: 0,
    preferAdaptive: false,
  },
};

function source(id, quality, audioTracks, audioLangs) {
  return {
    id,
    label: id,
    kind: 'http',
    quality,
    sizeBytes: 1_000_000_000,
    ...(audioTracks ? { audioTracks } : {}),
    ...(audioLangs ? { audioLangs } : {}),
  };
}

async function ranked(sources) {
  const item = { type: 'movie', sources };
  const answer = await ranking.apply(item, { profile });
  return answer.sources;
}

(async () => {
  const preferred = await ranked([
    source('english-1080', '1080p', [{ id: 'a:1', language: 'eng' }]),
    source('hindi-720', '720p', [{ id: 'a:1', language: 'hin' }]),
  ]);
  assert.strictEqual(preferred[0].id, 'hindi-720',
    'a verified first-choice language must beat a sharper fallback-language copy');

  const evidence = await ranked([
    source('hindi-in-name-1080', '1080p', null, ['hin']),
    source('hindi-track-720', '720p', [{ id: 'a:1', language: 'hin' }]),
  ]);
  assert.strictEqual(evidence[0].id, 'hindi-track-720',
    'a measured Hindi track must beat an unverified Hindi filename claim');

  const player = fs.readFileSync(path.join(
    __dirname, '..', 'app', 'src', 'main', 'java', 'com', 'scenix', 'tv', 'PlayerActivity.kt',
  ), 'utf8');
  assert.match(player, /val confirmed = o\.optBoolean\("confirmed"\)/,
    'the TV must inspect donor-confidence metadata');
  assert.match(player, /verify before mixing/,
    'the TV must disclose an unverified donor before a viewer chooses it');
  assert.match(player, /body\.put\("force", true\)/,
    'the explicit audio choice must still be sent as a request to the Hub');

  console.log('AUDIO MIX CONTRACT GATE OK');
})().catch((error) => {
  console.error('AUDIO MIX CONTRACT GATE FAILED:', error.message);
  process.exitCode = 1;
});
