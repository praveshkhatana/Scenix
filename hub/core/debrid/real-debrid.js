'use strict';
/*
 * core/debrid/real-debrid.js — the Real-Debrid adapter.
 *
 * One file is the whole provider. core/debrid.js discovers it by its `id`, hands it a token, a hard
 * deadline and a small HTTP helper, and asks for a URL. Nothing here knows about the hub, the picker or
 * the settings file, and nothing here logs: the token lives in the Authorization header and must never
 * reach a log line, a reason string or an HTTP error the UI can see.
 *
 * The flow is Real-Debrid's own documented one:
 *   POST /torrents/addMagnet          -> { id }
 *   GET  /torrents/info/{id}         -> status + files (waiting_files_selection, downloaded, …)
 *   POST /torrents/selectFiles/{id}  -> pick the video file (or the requested episode)
 *   GET  /torrents/info/{id}         -> poll until "downloaded"
 *   POST /unrestrict/link            -> { download } — ordinary HTTP the player can seek
 *
 * API reference: https://api.real-debrid.com/ (REST 1.0). The same adapter unrestricts a hoster link
 * with POST /unrestrict/link, which is one round trip and needs no torrent at all.
 */

const API = 'https://api.real-debrid.com/rest/1.0';
const ID = 'real-debrid';
const LABEL = 'Real-Debrid';
const URL_TTL_MS = Number(process.env.DEBRID_URL_TTL_MS || 2 * 3600e3);
const VIDEO_EXT = /\.(mkv|mp4|m4v|avi|ts|m2ts|mov|webm|mpg|mpeg|wmv|flv|ogv)$/i;

/*
 * Hoster domains this adapter is willing to send to the provider. Real-Debrid supports many more; the
 * list is deliberately a starting point rather than an API call on every Play press. A source an addon
 * marks `kind: "hoster"` is also attempted even when its domain is not here — the provider is the
 * authority on what it supports, and a refusal falls through to the normal playback path.
 */
const HOSTERS = [
  '1fichier.com', '2shared.com', '4shared.com', 'alfafile.net', 'brupload.net',
  'clicknupload.cc', 'clicknupload.co', 'clicknupload.me', 'clicknupload.to', 'dailyuploads.net',
  'ddownload.com', 'depositfiles.com', 'dropapk.to', 'drop.download', 'ex-load.com', 'fastfile.cc',
  'file.al', 'filefactory.com', 'filefox.cc', 'filejoker.net', 'filesfly', 'fboom.me', 'filenext.com',
  'filespace.com', 'filerice.com', 'fileupload.pw', 'hitfile.net', 'hulkshare.com', 'keep2share.cc',
  'katfile.com', 'mediafire.com', 'mega.nz', 'mexashare.com', 'nitroflare.com', 'prefiles.com',
  'rapidgator.net', 'rapidgator.to', 'rapidgator.asia', 'rapidrar.com', 'sendspace.com',
  'solidfiles.com', 'speed-down.org', 'tezfiles.com', 'turbobit.net', 'uploadbox.io',
  'uploadcloud.pro', 'uploaded.net', 'uploadgig.com', 'uploadhaven.com', 'uptobox.com',
  'usersdrive.com', 'vidoza.net', 'wdupload.com', 'worldbytez.com', 'yandex.com', 'zippyshare.com',
];

/** One authenticated call. The URL carries no secret; the token travels only in the header. */
function call(ctx, path, opts = {}) {
  const timeoutMs = Math.max(250, Math.min(Number(opts.timeoutMs) || 4000,
    Math.max(250, (ctx.deadline || Date.now() + 4000) - Date.now())));
  return ctx.http({
    url: API + path,
    method: opts.method || 'GET',
    form: opts.form || null,
    headers: { Authorization: 'Bearer ' + ctx.token, Accept: 'application/json' },
    timeoutMs,
    maxBytes: 512 * 1024,
  });
}

/** The provider's own words when it refuses, with anything token-shaped removed by core/debrid.js. */
function failure(r) {
  if (!r) return 'no answer from the provider';
  if (r.error) return r.error;
  const j = r.json;
  if (j && typeof j === 'object' && j.error) return String(j.error);
  if (typeof j === 'string' && j.trim()) return j.trim().slice(0, 160);
  return 'provider HTTP ' + (r.status || '?');
}

function sleep(ms) { return new Promise((resolve) => setTimeout(resolve, Math.max(0, ms))); }

function hashOf(magnet) {
  const m = /btih:([a-fA-F0-9]{40}|[a-zA-Z2-7]{32})/.exec(String(magnet || ''));
  return m ? m[1].toLowerCase() : null;
}

/** A bare 40-char info hash is a magnet missing its scheme; Real-Debrid needs the magnet form. */
function normalizeMagnet(raw) {
  const m = String(raw || '').trim();
  if (/^magnet:\?/i.test(m)) return m;
  if (/^[a-fA-F0-9]{40}$/.test(m)) return 'magnet:?xt=urn:btih:' + m.toLowerCase();
  return null;
}

function episodeOf(path) {
  const p = String(path || '').toLowerCase();
  let m = /s(\d{1,2})[\s._-]*e(?:p)?(\d{1,3})/.exec(p);
  if (!m) m = /(\d{1,2})x(\d{1,3})/.exec(p);
  if (!m) m = /x(\d{1,2})x(\d{1,3})/.exec(p);
  if (!m) return null;
  return m.length === 3 ? { season: Number(m[1]), episode: Number(m[2]) }
    : { season: 0, episode: Number(m[1]) };
}

/**
 * The file this Play press means. A season pack is one torrent holding every episode, and the largest
 * file in it is not necessarily the episode the viewer picked, so an exact SxxExx match wins before size.
 */
function pickFile(files, want) {
  const usable = (files || []).filter((f) => f && f.id !== undefined);
  const videos = usable.filter((f) => VIDEO_EXT.test(String(f.path || '')));
  if (!videos.length) return null; // a torrent with no video is not a film this hub can hand over
  const s = Number(want && want.season);
  const e = Number(want && want.episode);
  if (s > 0 && e > 0) {
    const exact = videos.filter((f) => {
      const q = episodeOf(f.path);
      return q && q.season === s && q.episode === e;
    });
    if (exact.length) return exact.sort((a, b) => Number(b.bytes || 0) - Number(a.bytes || 0))[0];
  }
  return videos.sort((a, b) => Number(b.bytes || 0) - Number(a.bytes || 0))[0];
}

function baseName(p) {
  const s = String(p || '');
  const at = s.lastIndexOf('/');
  return at >= 0 ? s.slice(at + 1) : s;
}

function kindOf(url) {
  return /\.m3u8([?#]|$)/i.test(String(url || '')) ? 'hls' : 'http';
}

/**
 * The one request that turns any supported hoster link into a direct file. Shared by `unrestrict` and
 * by the torrent path, which gets hoster links back from /torrents/info.
 */
async function unrestrict(link, ctx) {
  if (!/^https?:\/\//i.test(String(link || ''))) return { url: null, reason: 'not an HTTP link' };
  const r = await call(ctx, '/unrestrict/link', {
    method: 'POST',
    form: { link: String(link), remote: '1' },
    timeoutMs: 6000,
  });
  if (!r.ok) return { url: null, reason: 'unrestrict: ' + failure(r) };
  const j = r.json || {};
  // A few hosters (YouTube) answer with an array of generated links; the first is the file a player can
  // seek. Everything else answers with one object.
  const one = Array.isArray(j) ? (j[0] || {}) : j;
  const download = String(one.download || '');
  if (!/^https?:\/\//i.test(download)) return { url: null, reason: 'provider returned no download link' };
  return {
    url: download,
    kind: kindOf(download),
    expiresAt: Date.now() + URL_TTL_MS,
    provider: ID,
    providerLabel: LABEL,
    fileName: one.filename || null,
    fileSize: Number(one.filesize) || null,
  };
}

/** A torrent the account already holds, found by hash, for the "already added" refusal. */
async function findByHash(ctx, magnet) {
  const hash = hashOf(magnet);
  if (!hash) return null;
  const r = await call(ctx, '/torrents?limit=100', { timeoutMs: 3000 });
  if (!r.ok || !Array.isArray(r.json)) return null;
  return r.json.find((t) => String((t && t.hash) || '').toLowerCase() === hash) || null;
}

/** Add a magnet, select the requested video file, poll, and return its ordinary HTTP URL. */
async function resolveMagnet(magnet, ctx) {
  const link = normalizeMagnet(magnet);
  if (!link) return { url: null, reason: 'not a magnet link' };

  const add = await call(ctx, '/torrents/addMagnet', {
    method: 'POST', form: { magnet: link }, timeoutMs: 5000,
  });
  let id = add.ok && add.json && add.json.id ? String(add.json.id) : null;
  if (!id) {
    // "Torrent already active" is not a failure: the account may already be downloading this exact
    // hash from an earlier press. Reuse it instead of adding a second copy.
    const existing = await findByHash(ctx, link);
    if (existing && existing.id) id = String(existing.id);
    else return { url: null, reason: 'could not add torrent: ' + failure(add) };
  }

  let info = null;
  let picked = null;
  let status = 'queued';
  while (Date.now() < ctx.deadline) {
    const r = await call(ctx, '/torrents/info/' + encodeURIComponent(id), { timeoutMs: 2500 });
    if (!r.ok) return { url: null, reason: 'torrent info: ' + failure(r) };
    info = r.json || {};
    status = String(info.status || 'queued');
    if (status === 'waiting_files_selection') {
      const files = Array.isArray(info.files) ? info.files : [];
      picked = pickFile(files, ctx.want);
      if (!picked) {
        return {
          url: null,
          reason: files.length ? 'torrent holds no video file' : 'torrent has no file list yet',
        };
      }
      const sel = await call(ctx, '/torrents/selectFiles/' + encodeURIComponent(id), {
        method: 'POST', form: { files: String(picked.id) }, timeoutMs: 2500,
      });
      // 202 means "action already done", which is exactly the state we wanted.
      if (!sel.ok && sel.status !== 202) {
        return { url: null, reason: 'could not select the video file: ' + failure(sel) };
      }
    } else if (status === 'downloaded') {
      const links = Array.isArray(info.links) ? info.links : [];
      if (links.length) {
        const out = await unrestrict(String(links[0]), ctx);
        if (!out.url) return out;
        if (picked) {
          out.fileName = baseName(picked.path) || out.fileName;
          out.fileSize = Number(picked.bytes) || out.fileSize;
        }
        return out;
      }
    } else if (/^(magnet_error|error|virus|dead|unsupported)$/i.test(status)) {
      return { url: null, reason: 'provider stopped at ' + status };
    }
    await sleep(Math.min(700, Math.max(80, ctx.deadline - Date.now())));
  }
  return { url: null, reason: 'provider still ' + (status || 'queued') + ' for this torrent' };
}

/** Account state for the settings screen and /healthz. Never the username, email or token. */
async function status(ctx) {
  if (!ctx.token) return { ok: false, reason: 'no token stored' };
  const r = await call(ctx, '/user', { timeoutMs: 3000 });
  if (!r.ok) return { ok: false, reason: failure(r) };
  const j = r.json || {};
  const premium = j.type === 'premium' && Number(j.premium) > 0;
  return {
    ok: true,
    provider: ID,
    premium,
    expiresAt: j.expiration || null,
    note: premium ? 'account is premium' : 'account is not premium: torrents will not resolve',
  };
}

module.exports = {
  id: ID,
  label: LABEL,
  api: API,
  hosters: HOSTERS,
  status,
  unrestrict,
  resolveMagnet,
  // Exported for a future adapter or a local test; core/debrid.js does not depend on them.
  pickFile,
  normalizeMagnet,
};
