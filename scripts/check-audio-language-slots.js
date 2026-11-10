'use strict';
/*
 * The copy list the hub keeps for a title is trimmed, and the audio donor search reads that same list.
 * Two facts have to stay true together, and neither is obvious from the code that implements it:
 *
 *  1. a release name stating several languages must be read as stating all of them — "[Tamil + Telugu +
 *     Hindi + Eng]" is a Hindi copy as much as a Tamil one, and a donor search that sees only the first
 *     word calls it Tamil and mixes nothing; and
 *  2. the trim may not delete every copy of a language this hub serves. Band spread orders each band by
 *     swarm health, and a dub is normally the seed-poor copy under a film's original-language remuxes, so
 *     on a real 212-row title the 24 slots held none of its 11 Hindi-named copies and play() reported
 *     "audio: English stated" with no donor ever mentioned.
 *
 * No network: the upstream answer is written here.
 */

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

// A data dir of our own, so this gate never reads (or writes) the settings and addon cache of a hub that
// happens to be running on this machine.
const data = fs.mkdtempSync(path.join(os.tmpdir(), 'scenix-slots-'));
process.env.DATA_DIR = data;
process.env.SETTINGS_FILE = path.join(data, 'settings.json');
process.env.ADDONS_FILE = path.join(data, 'addons.json');
process.env.ADDON_CACHE_FILE = path.join(data, 'addon-cache.json');

const settings = require('../hub/core/settings');
const addons = require('../hub/core/addons');

const MAX = 12;

/** One upstream stream row, spelled the way an addon answers it. */
function row(quality, sizeGB, seeders, release) {
  return {
    name: 'Torrentio\n' + quality,
    title: release + ' 👤 ' + seeders + ' 💾 ' + sizeGB + ' GB',
    infoHash: 'hash' + seeders,
    externalUrl: 'magnet:?xt=urn:btih:' + 'a'.repeat(40),
    behaviorHints: { bingeGroup: 'bg' },
  };
}

// The shape that broke: hundreds of original-language copies with healthy swarms, and the household's own
// language only on copies a few dozen people are seeding.
const streams = [];
for (let i = 0; i < 30; i++) {
  streams.push(row('2160p', 16.2, 3000 - i, 'Resident.Evil.2026.2160p.iT.WEB-DL.DV.HDR10+.Atmos.H265.MP4-BTM'));
  streams.push(row('1080p', 6.3, 2000 - i, 'Resident.Evil.2026.1080p.x265.WEB-DL.DDP5.1-GRP'));
  streams.push(row('720p', 2.1, 1500 - i, 'Resident.Evil.2026.720p.x264.WEB-DL-AAC-GRP'));
}
// The weak language copies.
streams.push(row('2160p', 17.4, 21, 'Resident Evil (2026) 2160p iTunes WEB-DL [Hindi-Tamil-Telugu DDP 5.1 + English]'));
streams.push(row('1080p', 3.03, 331, 'Resident Evil (2026) HQ HDRip - 1080p - x264 - [Tamil + Telugu + Hindi + Eng] - (DD+5.1 - 640Kbps) - 3GB - ESub'));
streams.push(row('1080p', 2.9, 110, 'Resident.Evil.2026.1080p.iTunes.WEBRip.[Org.DDP5.1-Hindi DDPA5.1-English].HEVC-PunisheR'));
streams.push(row('720p', 1.22, 338, 'Resident Evil (2026) HQ HDRip - 720p - x264 - [Hindi + Eng] - (DD+5.1 - 192Kbps) - 1.2GB - ESub'));
streams.push(row('720p', 1.05, 13, 'Resident Evil (2026) 720p HQ HDRip - x264 - [Hindi + Eng] - (DD5.1)'));

settings.set({ languages: [], addons: { maxSources: MAX, minSizeMB: 100 } });
// What the quality-band spread keeps on its own — the list that left the hub holding no Hindi at all.
const bandOnly = addons.util.sourcesFromStreams('torrentio', { streams }).sources;
settings.set({ languages: ['hin', 'eng'] });
const kept = addons.util.sourcesFromStreams('torrentio', { streams }).sources;

const langsOf = (s) => (s._langs || []);
const labels = (list) => list.map((s) => s.quality + '·' + s.seeders).join(' | ');
const names = (s) => langsOf(s).filter((l) => l === 'hin' || l === 'eng');
const hin = kept.filter((s) => langsOf(s).includes('hin'));

// 0. A release line that states an audio bitrate is not read as stating its file size. "(DD+5.1 - 640Kbps)"
// once made a 3.03 GB copy look like 640 KB, and the minimum-size floor then deleted it silently — the
// Hindi copies with the best swarms went that way.
for (const entry of streams.filter((e) => /Kbps/i.test(e.title))) {
  const t = addons.util.parseStream(entry);
  assert.ok(t.sizeBytes > 1e9, 'a bitrate in the release name was read as the file size: '
    + t.label + ' -> ' + t.sizeBytes + ' bytes');
}

// 1. A release name that states several languages is recorded as stating all of them.
const multi = kept.find((s) => s.seeders === 331); // the "[Tamil + Telugu + Hindi + Eng]" release
assert.ok(multi, 'the reservation should take the best-seeded Hindi copies (338 then 331): ' + labels(kept));
assert.deepStrictEqual(langsOf(multi).slice().sort(), ['hin', 'tam', 'tel'],
  'a multi-language release name must not be read as only its first language: got ' + JSON.stringify(langsOf(multi)));
const dual = kept.find((s) => s.seeders === 110); // "...-Hindi DDPA5.1-English]"
if (dual) assert.ok(langsOf(dual).includes('hin') && langsOf(dual).includes('eng'),
  'a copy stating both languages must state both: got ' + JSON.stringify(langsOf(dual)));

// 2. The reservation does its job: the household's language is present, where band spread alone had none.
assert.ok(!bandOnly.some((s) => langsOf(s).includes('hin')),
  'this fixture must reproduce the failure — band spread alone keeps no Hindi copy: ' + labels(bandOnly));
assert.ok(hin.length >= 1, 'a Hindi copy has to survive the trim, or the donor search has nothing to read');
assert.strictEqual(new Set(kept.map((s) => s.seeders)).size, kept.length,
  'the same copy may not be held twice in the list: ' + labels(kept));

// 3. It pays only with copies that state no language this hub serves, and it never drops one the band
// spread had already chosen for a language.
assert.strictEqual(kept.length, MAX, 'the trim must still keep exactly maxSources copies');
// The two lists come from separate calls, so copies are compared by what identifies them here.
const key = (s) => String(s.seeders);
const keptKeys = new Set(kept.map(key));
for (const s of bandOnly) {
  if (!names(s).length) continue;
  assert.ok(keptKeys.has(key(s)), 'the reservation may not evict a copy that states a hub language: ' + labels(kept));
}
const silentDropped = bandOnly.filter((s) => !names(s).length && !keptKeys.has(key(s)));
assert.ok(silentDropped.length > 0 && silentDropped.length <= Math.floor(MAX / 4),
  'language copies are paid for with unnamed ones, at most a quarter of the list: dropped '
    + silentDropped.length);
assert.ok(keptKeys.has('3000'),
  'the sharpest, best-seeded copy of the title is not what the reservation costs: ' + labels(kept));

// 4. No configured language is left without a copy, and language copies stay a minority.
for (const lang of ['hin', 'eng']) {
  assert.ok(kept.filter((s) => langsOf(s).includes(lang)).length >= 1,
    'the hub serves ' + lang + ' and the list must be able to say so: ' + labels(kept));
}
assert.ok(kept.filter((s) => names(s).length).length <= Math.max(2, Math.floor(MAX / 4) + 1),
  'reserved language copies must stay a minority of the list, got '
    + kept.filter((s) => names(s).length).length + ' of ' + MAX);

// 5. Quality bands still get their share — this is a reservation, not a new sort.
for (const band of ['2160p', '1080p', '720p']) {
  assert.ok(kept.some((s) => s.quality === band), 'band spread lost ' + band + ': ' + labels(kept));
}

// 6. A hub that asks for no language keeps the band-spread list exactly.
settings.set({ languages: [] });
const again = addons.util.sourcesFromStreams('torrentio', { streams }).sources;
assert.deepStrictEqual(again.map((s) => s.seeders), bandOnly.map((s) => s.seeders),
  'with no configured languages the reservation must change nothing');

fs.rmSync(data, { recursive: true, force: true });
console.log('AUDIO LANGUAGE SLOT GATE OK');
