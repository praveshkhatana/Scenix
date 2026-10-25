'use strict';
/*
 * providers/local-files.js — native addon: the files you already own, on the same shelves as
 * everything else. It is also the smallest complete example of an addon in this hub: 90 lines, no
 * network, and it demonstrates the whole contract (catalogs / catalog / meta / play + rootDirs).
 *
 *   MEDIA_DIR   directory to index (default <hub>/media)
 *   LOCAL_MAX   most files per catalog (default 500)
 *
 * Bytes are served by the hub itself, through GET /app/v1/proxy?u=file://…, which answers Range
 * requests (206) so any web or mobile player can seek. That is what `rootDirs` below is for: the
 * proxy only serves files inside a directory an installed addon declared, so a query string can
 * never walk out of your media folder.
 */

const fs = require('fs');
const path = require('path');

const PROVIDER_ID = 'local-files';
const MEDIA_DIR = path.resolve(process.env.MEDIA_DIR || path.join(__dirname, '..', 'media'));
const LOCAL_MAX = Number(process.env.LOCAL_MAX || 500);
const EXT = new Set((process.env.LOCAL_EXT || '.mp4,.mkv,.webm,.m4v,.mov,.ts,.avi,.mp3,.m4a,.opus')
  .split(',').map((s) => s.trim().toLowerCase()));
const RESCAN_MS = Number(process.env.LOCAL_RESCAN_MS || 60000);

let files = []; // { identity, abs, rel, dir, size, mtimeMs }
let scannedAt = 0;
let lastError = null;

const slug = (s) => String(s).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');

function scan() {
  const out = [];
  const walk = (dir, depth) => {
    if (depth > 4 || out.length >= LOCAL_MAX) return;
    let names = [];
    try {
      names = fs.readdirSync(dir, { withFileTypes: true });
    } catch (e) {
      lastError = e.code === 'ENOENT' ? null : e.message;
      return;
    }
    for (const ent of names) {
      if (out.length >= LOCAL_MAX) return;
      const abs = path.join(dir, ent.name);
      if (ent.isDirectory()) {
        walk(abs, depth + 1);
        continue;
      }
      if (!EXT.has(path.extname(ent.name).toLowerCase())) continue;
      let st;
      try {
        st = fs.statSync(abs);
      } catch (e) {
        continue;
      }
      const rel = path.relative(MEDIA_DIR, abs);
      out.push({
        identity: 'local:' + slug(rel),
        abs,
        rel,
        dir: path.basename(path.dirname(abs)),
        size: st.size,
        mtimeMs: st.mtimeMs,
      });
    }
  };
  walk(MEDIA_DIR, 0);
  out.sort((a, b) => a.rel.localeCompare(b.rel));
  files = out;
  scannedAt = Date.now();
}

/** "The.Matrix.1999.1080p.x264.mkv" -> a title, a year and a quality guess. */
function describe(rel) {
  const base = path.basename(rel, path.extname(rel));
  const yearM = /\b(19|20)\d{2}\b/.exec(base);
  const qM = /(2160|1080|720|480)p/i.exec(base);
  const title = base
    .replace(/\.[\s\S]*?((19|20)\d{2})\./, ' ')
    .replace(/[.\-_]+/g, ' ')
    .replace(/\b(1080p|720p|480p|2160p|x264|x265|h264|h265|hindi|dvdrip|webrip|bluray)\b/gi, ' ')
    .replace(/\s{2,}/g, ' ')
    .trim();
  return {
    title: title || base,
    year: yearM ? Number(yearM[0]) : null,
    quality: qM ? qM[1] + 'p' : null,
  };
}

function toItem(f) {
  const d = describe(f.rel);
  const releasedAt = d.year ? Date.UTC(d.year, 0, 1) : null;
  return {
    identity: f.identity,
    type: 'video', // your own files are not a movie/series release; they are a video
    title: d.title,
    year: d.year,
    poster: null,
    backdrop: null,
    logo: null,
    match: null,
    maturity: null,
    durationMs: null,
    languages: null, // unknown, so the language rule leaves the item alone
    genres: f.dir && f.dir !== path.basename(MEDIA_DIR) ? [f.dir] : null,
    addedAt: null,
    releasedAt,
    inCinemas: false,
    live: null,
    progress: null,
    sources: [{
      provider: 'adaptive', // served by the hub over HTTP, byte ranges and all
      _addon: PROVIDER_ID,
      label: [d.quality, humanSize(f.size)].filter(Boolean).join(' · ') || 'local file',
      kind: 'file',
      quality: d.quality,
      sizeBytes: f.size,
      risk: null,
    }],
    _addons: [PROVIDER_ID],
  };
}

function humanSize(bytes) {
  if (!Number.isFinite(bytes)) return '';
  if (bytes >= 1e9) return (bytes / 1e9).toFixed(2).replace(/\.?0+$/, '') + ' GB';
  if (bytes >= 1e6) return Math.round(bytes / 1e6) + ' MB';
  return Math.round(bytes / 1e3) + ' KB';
}

function fresh() {
  if (!scannedAt || Date.now() - scannedAt > RESCAN_MS) scan();
  return files;
}

function find(identity) {
  return fresh().find((f) => f.identity === String(identity));
}

module.exports = {
  id: PROVIDER_ID,
  name: 'Local files',
  native: true,
  kind: 'video',
  priority: 30,

  // The proxy serves file:// URLs only inside a directory an installed addon declared.
  rootDirs: [MEDIA_DIR],

  start() {
    scan();
    const t = setInterval(scan, RESCAN_MS);
    if (t.unref) t.unref();
  },

  async catalogs() {
    return [{ id: 'files', name: 'Files in ' + path.basename(MEDIA_DIR), extra: ['search'] }];
  },

  async catalog(catalogId, extra = {}) {
    if (catalogId !== 'files') return [];
    let items = fresh().map(toItem);
    const q = String(extra.search || '').toLowerCase();
    if (q) items = items.filter((it) => String(it.title || '').toLowerCase().includes(q));
    return items;
  },

  async meta(identity) {
    const f = find(identity);
    return f ? toItem(f) : null;
  },

  /**
   * Bytes come back as a hub URL, not a path: /app/v1/proxy answers them with Range support, so a
   * browser <video> or an ExoPlayer on the TV seeks the same way through one code path.
   */
  async play(identity) {
    const f = find(identity);
    if (!f) return null;
    return {
      url: '/app/v1/proxy?u=' + encodeURIComponent('file://' + f.abs),
      headers: {},
      kind: 'file',
      expiresAt: null,
    };
  },

  stats() {
    return {
      files: files.length,
      root: MEDIA_DIR,
      scannedAt: scannedAt ? new Date(scannedAt).toISOString() : null,
      error: lastError,
    };
  },
};
