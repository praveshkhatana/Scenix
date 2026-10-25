'use strict';
/*
 * providers/hub-yt.js — curated Kids YouTube, moved from work/iptv/iptv-india-addon.js.
 *
 * An allow-list of *channels*, never a search: the kids row is built from these channels' own recent
 * uploads, so nothing outside the list can reach a child. Videos are resolved to a playable URL at
 * play time, because YouTube URLs are signed and expire within hours. Measured on the hub:
 * extraction needs a JS runtime **and** the android client, otherwise every request fails the bot
 * check ("This video is not available") — that is why play() passes
 *   --js-runtimes node:<node> --extractor-args youtube:player_client=android
 * (the `node:` prefix is required — a bare path is rejected as an unsupported runtime), as the
 * single-file addon did (YT_BIN / YT_JS / YT_CLIENT env override the binaries).
 *
 * Legacy addon ids stay `ytv___<vid>`, so the TV's installed addon is unaffected.
 * Hub identity for a video is `yt:<vid>` (the §1 merge key; a video has no year to slug).
 */

const { execFile } = require('child_process');

const PROVIDER_ID = 'kids-yt';
const YT_CATALOG_ID = 'yt_kids';
const YT_PREFIX = 'ytv___';
const YT_BIN = process.env.YT_BIN || 'yt-dlp';
const YT_JS = process.env.YT_JS || 'node';
const YT_CLIENT = process.env.YT_CLIENT || 'android';
const YT_MAX_PER_CHANNEL = Number(process.env.YT_MAX || 10);
const YT_CHANNELS = (process.env.YT_CHANNELS === undefined
  ? 'https://www.youtube.com/@Cocomelon/videos,https://www.youtube.com/@ChuChuTV/videos,' +
    'https://www.youtube.com/@SuperSimpleSongs/videos,https://www.youtube.com/@Pinkfong/videos,' +
    'https://www.youtube.com/@InfobellsHindi/videos,https://www.youtube.com/@ChhotaBheem/videos,' +
    'https://www.youtube.com/@funtoons/videos,https://www.youtube.com/@PunjabiToons/videos'
  : process.env.YT_CHANNELS).split(',').map((s) => s.trim()).filter(Boolean);
const REFRESH_MS = 6 * 60 * 60 * 1000;
const UA = 'Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 GhartvHub/0.1';
// The ladder the old addon offered on the legacy surface: 720p then 480p, both mp4.
const LADDER = [['720p', 'b[ext=mp4][height<=720]/b[ext=mp4]/b'], ['480p', 'b[ext=mp4][height<=480]/b']];

let ytVideos = []; // {vid, title, channel, poster}
let ytError = null;

function ytExec(args, timeoutMs) {
  return new Promise((resolve, reject) => {
    execFile(YT_BIN, args, { timeout: timeoutMs || 90000, maxBuffer: 32 * 1024 * 1024 }, (err, stdout) => {
      if (err) return reject(err);
      resolve(stdout);
    });
  });
}

// One channel at a time so a slow one cannot stall the whole refresh.
async function refreshYouTube() {
  if (!YT_CHANNELS.length) return;
  const out = [];
  for (const url of YT_CHANNELS) {
    const handle = (url.match(/@([^/]+)/) || [])[1] || '';
    try {
      const j = JSON.parse(await ytExec(
        ['--flat-playlist', '--playlist-end', String(YT_MAX_PER_CHANNEL), '-J', url], 60000));
      const entries = (j.entries || []).filter((e) => e && e.id);
      for (const e of entries) {
        const th = (e.thumbnails || [])[0] || {};
        out.push({
          vid: e.id,
          title: e.title || e.id,
          channel: e.channel || e.uploader || handle,
          poster: th.url || 'https://i.ytimg.com/vi/' + e.id + '/hqdefault.jpg',
        });
      }
    } catch (e) {
      console.error(new Date().toISOString(), 'youtube', url, 'failed:', String(e.message).slice(0, 90));
    }
  }
  if (out.length) {
    ytVideos = out;
    ytError = null;
  } else {
    ytError = 'no channel could be fetched (yt-dlp blocked?)';
  }
  console.log(new Date().toISOString(), 'youtube catalog:', ytVideos.length, 'videos from',
    YT_CHANNELS.length, 'channels', ytError ? '- ' + ytError : '');
}

function findVideo(remoteId) {
  return ytVideos.find((x) => YT_PREFIX + x.vid === remoteId);
}

function ytMeta(v) {
  return {
    id: YT_PREFIX + v.vid,
    type: 'tv',
    name: v.title,
    poster: v.poster,
    background: v.poster,
    posterShape: 'landscape',
    description: 'YouTube • ' + v.channel + ' • curated kids channel (no search, no recommendations)',
  };
}

// ---- hub §1 items -------------------------------------------------------------------------------

function hubItems(list) {
  return list.map((v) => ({
    identity: 'yt:' + v.vid,
    type: 'video',
    title: v.title,
    year: null,
    poster: v.poster,
    backdrop: v.poster,
    logo: null,
    match: null,
    maturity: null,
    durationMs: null,
    languages: null, // yt-dlp flat-playlist gives no language: unknown, so the language rule keeps it
    genres: ['Kids'],
    addedAt: null,
    releasedAt: null,
    inCinemas: false,
    live: null,
    progress: null,
    sources: LADDER.map(([label]) => ({
      provider: 'youtube', // what the client renders: a transport word, not our plugin name
      _addon: PROVIDER_ID,
      label,
      kind: 'http',
      quality: label,
      sizeBytes: null,
      risk: null,
    })),
  }));
}

async function resolveStream(vid, fmt, timeoutMs = 90000) {
  const watch = 'https://www.youtube.com/watch?v=' + vid;
  const url = (await ytExec(['--js-runtimes', 'node:' + YT_JS, '--extractor-args',
    'youtube:player_client=' + YT_CLIENT, '-f', fmt, '-g', watch], timeoutMs)).trim().split('\n')[0];
  if (!/^https?:\/\//.test(url)) throw new Error('no url from yt-dlp');
  return url;
}

module.exports = {
  id: PROVIDER_ID,
  name: 'Curated kids YouTube',
  native: true,
  kind: 'video',
  priority: 20,

  // Same warmup schedule as the old addon: first sweep 5 s after boot, then every 6 h.
  start() {
    setTimeout(() => refreshYouTube(), 5000).unref();
    const t = setInterval(() => refreshYouTube(), REFRESH_MS);
    if (t.unref) t.unref();
  },
  // The legacy surface must NOT wait for a YouTube sweep (the old addon never did either).
  async ensure() {},

  async catalogs() {
    return [{ id: YT_CATALOG_ID, name: 'Kids YouTube (curated channels)', extra: ['search'] }];
  },

  /** Cache-only: [] until the first YouTube sweep lands. */
  async catalog(catalogId, extra = {}) {
    if (catalogId !== YT_CATALOG_ID) return [];
    let list = ytVideos;
    const search = (extra.search || '').toLowerCase();
    if (search) list = list.filter((v) => (v.title + ' ' + v.channel).toLowerCase().includes(search));
    const items = hubItems(list);
    return Number.isFinite(extra.limit) ? items.slice(0, extra.limit) : items;
  },

  async meta(identity) {
    if (!identity || !String(identity).startsWith('yt:')) return null;
    const v = ytVideos.find((x) => 'yt:' + x.vid === identity);
    return v ? hubItems([v])[0] : null;
  },

  /** Resolve a signed googlevideo URL now — they expire within hours. */
  async play(identity, source) {
    const vid = String(identity || '').replace(/^yt:/, '');
    if (!vid) return null;
    const want = source && source.quality;
    const rungs = want ? LADDER.filter(([label]) => label === want) : LADDER;
    for (const [label, fmt] of rungs.length ? rungs : LADDER) {
      try {
        const url = await resolveStream(vid, fmt);
        return {
          url,
          headers: { Referer: 'https://www.youtube.com/', 'User-Agent': UA },
          kind: 'http',
          expiresAt: Date.now() + 2 * 60 * 60 * 1000, // signed URLs live ~6 h; re-resolve after 2
        };
      } catch (e) {
        console.error(new Date().toISOString(), 'yt play', vid, label, 'failed:',
          String(e.message).slice(0, 90));
      }
    }
    return null;
  },

  stats() {
    return { videos: ytVideos.length, channels: YT_CHANNELS.length, error: ytError, bin: YT_BIN };
  },

  // ---- legacy addon compatibility (byte-compatible with iptv-india-addon.js) ------------------
  legacy: {
    idPrefixes: [YT_PREFIX],
    catalogIds: [YT_CATALOG_ID],
    manifestPart() {
      return {
        resources: [
          { name: 'catalog', types: ['tv'], idPrefixes: [YT_CATALOG_ID] },
          { name: 'meta', types: ['tv'], idPrefixes: [YT_PREFIX] },
          { name: 'stream', types: ['tv'], idPrefixes: [YT_PREFIX] },
        ],
        catalogs: [
          {
            type: 'tv',
            id: YT_CATALOG_ID,
            name: 'Kids YouTube (curated channels)',
            extra: [{ name: 'search', isRequired: false }],
          },
        ],
        idPrefixes: [YT_PREFIX],
      };
    },
    async catalog(type, catId, extra) {
      if (type !== 'tv' || catId !== YT_CATALOG_ID) return null;
      const search = (extra.get('search') || '').toLowerCase();
      const limit = Number(extra.get('limit') || 1000);
      const list = search
        ? ytVideos.filter((v) => (v.title + ' ' + v.channel).toLowerCase().includes(search))
        : ytVideos;
      return { status: 200, body: { metas: list.slice(0, limit).map(ytMeta) } };
    },
    async meta(remoteId) {
      const v = findVideo(remoteId);
      // Old behaviour: 200 even when the video is not (or no longer) in the curated list.
      return { status: 200, body: { meta: v ? ytMeta(v) : null } };
    },
    async stream(remoteId) {
      const v = findVideo(remoteId);
      const vid = remoteId.slice(YT_PREFIX.length);
      const resolved = await Promise.all(LADDER.map(async ([label, fmt]) => {
        try {
          const url = await resolveStream(vid, fmt);
          return {
            name: 'YouTube • ' + label + (v ? ' • ' + v.channel : ''),
            title: 'YouTube ' + label + (v ? ' • ' + v.channel : ''),
            url,
            behaviorHints: {
              notWebReady: true,
              bingeGroup: 'yt-' + vid + '-' + label,
              proxyHeaders: { request: { Referer: 'https://www.youtube.com/', 'User-Agent': UA } },
            },
          };
        } catch (e) {
          console.error(new Date().toISOString(), 'yt stream', vid, label, 'failed:',
            String(e.message).slice(0, 90));
          return null;
        }
      }));
      return { status: 200, body: { streams: resolved.filter(Boolean) } };
    },
  },
};
