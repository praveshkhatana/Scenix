/**
 * The hub serves its own front-end.
 *
 * Scenix is one UI that runs everywhere: the browser loads it from here, and the TV app is a thin shell
 * that displays these same files over a native video surface. That only works if the hub can hand them
 * out, so the web app lives in `web/` next to the API it calls — no separate server, no build step, and
 * an edit plus a reload is the whole deploy for the interface.
 *
 * Files are served from the root for the page itself (`/`) and under `/ui/…` for everything it loads,
 * because the root is already taken by the addon protocol (`/manifest.json`, `/catalog/…`) and by
 * `/healthz`.
 */
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const crypto = require('crypto');

const ROOT = path.join(__dirname, '..', 'web');

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain; charset=utf-8',
};

// Text changes every time somebody edits the interface; a browser caching it is the reason a fix
// "didn't work". Images and fonts are content-addressed by name instead.
const TEXTUAL = new Set(['.html', '.js', '.mjs', '.css', '.json', '.svg', '.txt']);

/** Small files only: this is a hand-written UI, not a CDN, and a stray 40 MB asset should not be read. */
const MAX_BYTES = 4 * 1024 * 1024;

function resolveFile(urlPath) {
  const rel = urlPath === '/' ? 'index.html' : urlPath.replace(/^\/ui\//, '');
  if (!rel || rel.includes('\0')) return null;
  const abs = path.resolve(ROOT, rel);
  // Everything must stay inside web/: `/ui/../../etc/passwd` is a request, not a file open.
  if (abs !== ROOT && !abs.startsWith(ROOT + path.sep)) return null;
  return abs;
}

/**
 * Serve `path` if it belongs to the interface. Returns true when a response was written, so the router
 * can fall through to the API for anything else.
 */
function serve(req, res, urlPath) {
  if (req.method !== 'GET' && req.method !== 'HEAD') return false;
  if (!(urlPath === '/' || urlPath.startsWith('/ui/'))) return false;
  const file = resolveFile(urlPath);
  if (!file) return false;
  let stat;
  try {
    stat = fs.statSync(file);
  } catch (e) {
    return false; // not our file: let the router answer 404 in its own shape
  }
  if (!stat.isFile() || stat.size > MAX_BYTES) return false;

  const ext = path.extname(file).toLowerCase();
  const type = TYPES[ext] || 'application/octet-stream';
  let body = fs.readFileSync(file);
  const etag = '"sha1-' + crypto.createHash('sha1').update(body).digest('hex').slice(0, 20) + '"';
  if (req.headers['if-none-match'] === etag) {
    res.writeHead(304, { ETag: etag });
    res.end();
    return true;
  }
  const headers = {
    'Content-Type': type,
    ETag: etag,
    'Cache-Control': TEXTUAL.has(ext) ? 'no-cache' : 'public, max-age=86400',
    'Access-Control-Allow-Origin': '*',
    'X-Content-Type-Options': 'nosniff',
  };
  if (TEXTUAL.has(ext) && /gzip/i.test(String(req.headers['accept-encoding'] || ''))) {
    body = zlib.gzipSync(body);
    headers['Content-Encoding'] = 'gzip';
    headers.Vary = 'Accept-Encoding';
  }
  headers['Content-Length'] = Buffer.byteLength(body);
  res.writeHead(200, headers);
  res.end(req.method === 'HEAD' ? undefined : body);
  return true;
}

/** What the interface actually on disk looks like — reported by /healthz so a bad deploy is visible. */
function status() {
  let index = null;
  let files = 0;
  let bytes = 0;
  try {
    const walk = (dir) => {
      for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
        if (ent.isDirectory()) walk(path.join(dir, ent.name));
        else {
          files++;
          bytes += fs.statSync(path.join(dir, ent.name)).size;
        }
      }
    };
    walk(ROOT);
    index = fs.existsSync(path.join(ROOT, 'index.html'));
  } catch (e) {
    /* no web dir yet */
  }
  return { served: !!index, files, bytes };
}

module.exports = { serve, status, ROOT };
