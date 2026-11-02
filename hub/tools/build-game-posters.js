/* Re-source game posters from Wikipedia — kill the single-host dependency.
 *
 *   node hub/tools/build-game-posters.js
 *
 * The catalogue's posters used to hang off one website's CDN each (the ports file on the scraper's
 * own images). If that site ever changes or dies, every tile in the Games tab goes blank at once.
 * This pass gives each entry its art from The Wikipedia API instead: a title search, a similarity
 * gate so "GTA" never lands on some unrelated page, then the page's lead image — box art for the
 * classic titles. Entries with no confident match keep the cover they already have, which stays
 * their fallback rather than the catalogue's spine. Wikipedia's REST summary returns even the
 * fair-use covers the regular image API hides.
 *
 * Runtime: ~10 minutes for the whole catalogue (Wikimedia wants a polite rate and a real UA).
 */
'use strict';

const fs = require('fs');
const https = require('https');
const path = require('path');

const DATA_DIR = process.env.GAMES_DATA_DIR || path.join(__dirname, '..', 'data', 'games');
const UA = 'ScenixHub/1.0 (game catalogue poster refresh; https://github.com/praveshkhatana/Scenix)';
const SLEEP_MS = 380;                 // Wikimedia guidance: few requests/s, no parallel bursts
const DEP_HOSTS = /ported2browser\.com$|^imgs\.crazygames\.com$|^img\.poki-cdn\.com$/i;

function get(url, ms = 12000) {
  return new Promise((resolve) => {
    let done = false;
    const finish = (v) => { if (!done) { done = true; clearTimeout(timer); resolve(v); } };
    const timer = setTimeout(() => { try { req.destroy(); } catch (e) {} finish(null); }, ms);
    let req;
    try {
      req = https.get(url, { headers: { 'user-agent': UA, accept: 'application/json' } }, (res) => {
        if (res.statusCode === 301 || res.statusCode === 302) {
          res.resume();
          return finish(get(new URL(res.headers.location, url).toString(), ms));
        }
        if (res.statusCode !== 200) { res.resume(); return finish(null); }
        let body = '';
        res.setEncoding('utf8');
        res.on('data', (d) => { body += d; if (body.length > 4e6) req.destroy(); });
        res.on('end', () => { try { finish(JSON.parse(body)); } catch (e) { finish(null); } });
      });
      req.on('error', () => finish(null));
      req.on('timeout', () => { req.destroy(); finish(null); });
    } catch (e) { finish(null); }
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// "Need for Speed: Most Wanted (2005)" and "Need for Speed Most Wanted" must read as the same game.
function norm(s) {
  return String(s || '').toLowerCase()
    .replace(/[‘’']/g, '')
    .replace(/\((?:\d{4}|video game|game|gamepad)\)/g, ' ')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
}
const yearOf = (s) => (String(s || '').match(/\b(19[89]\d|20[01]\d)\b/) || [])[1] || '';

// A Wikipedia page that adds one of these is a different work: the Super Mario Galaxy FILM is not
// the game. (A series page is fine when the entry itself is the series — "Unreal (Series)".)
const OTHER_WORK = /\b(film|movie|soundtrack|television series|tv series|season|episodes|album|novel)\b/i;

function accepts(want, got) {
  const a = norm(want); const b = norm(got);
  if (!a || !b) return false;
  if (OTHER_WORK.test(got) && !OTHER_WORK.test(want)) return false;
  if (a === b) return true;
  const ta = new Set(a.split(' ')); const tb = new Set(b.split(' '));
  // Every word of ours on theirs (theirs may add "(2005 video game)") or vice versa.
  const sub = (x, y) => [...x].every((t) => y.has(t) || [...y].some((u) => u.startsWith(t) && t.length > 2));
  if (!(sub(ta, tb) || sub(tb, ta))) return false;
  // A year we named must appear in their title too: "Crash Bandicoot (1996)" vs "(2020 remaster)".
  const y = yearOf(want);
  if (y && !got.includes(y)) return false;
  return true;
}

async function imageFor(title) {
  const q = encodeURIComponent(title + (/\bgame\b/i.test(title) ? '' : ' video game'));
  const s = await get(`https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${q}&srnamespace=0&srlimit=4&format=json`);
  const hits = ((s && s.query && s.query.search) || []).map((h) => h.title);
  for (const hit of hits) {
    await sleep(SLEEP_MS);
    if (!accepts(title, hit)) continue;
    const sum = await get('https://en.wikipedia.org/api/rest_v1/page/summary/' + encodeURIComponent(hit.replace(/ /g, '_')));
    let url = (sum && sum.originalimage && sum.originalimage.source) || (sum && sum.thumbnail && sum.thumbnail.source) || '';
    url = url.split('?')[0];
    if (!url || /\.(svg|gif)$/i.test(url)) {
      const t = ((sum && sum.thumbnail && sum.thumbnail.source) || '').split('?')[0];
      url = t;
    }
    if (/^https:\/\/(upload|thumb)\.wikimedia\.org\//i.test(url)) return { url, page: hit };
  }
  return null;
}

// Raw sibling of get() for game pages: HTML, not JSON, and a byte cap.
function getRaw(url, ms = 12000) {
  return new Promise((resolve) => {
    let done = false;
    const finish = (v) => { if (!done) { done = true; clearTimeout(timer); resolve(v); } };
    const timer = setTimeout(() => { try { req.destroy(); } catch (e) {} finish(''); }, ms);
    let req;
    try {
      req = https.get(url, { headers: { 'user-agent': UA, accept: 'text/html' } }, (res) => {
        if (res.statusCode === 301 || res.statusCode === 302) {
          res.resume();
          try { return finish(getRaw(new URL(res.headers.location, url).toString(), ms)); } catch (e) { return finish(''); }
        }
        if (res.statusCode !== 200) { res.resume(); return finish(''); }
        let body = '';
        res.setEncoding('utf8');
        res.on('data', (d) => { body += d; if (body.length > 6e5) { req.destroy(); finish(body); } });
        res.on('end', () => finish(body));
      });
      req.on('error', () => finish(''));
      req.on('timeout', () => { req.destroy(); finish(''); });
    } catch (e) { finish(''); }
  });
}

function metaContent(html, key) {
  for (const t of html.match(/<meta\b[^>]*>/gi) || []) {
    const attrs = {};
    for (const m of t.matchAll(/(property|name|content)\s*=\s*(?:"([^"]*)"|'([^']*)')/gi))
      attrs[m[1].toLowerCase()] = m[2] !== undefined ? m[2] : m[3];
    const k = (attrs.property || attrs.name || '').toLowerCase();
    if (k === key && attrs.content) return attrs.content;
  }
  return '';
}

// Last resort for an entry with no art anywhere else: the picture the game's own page advertises.
async function ogImage(pageUrl) {
  try {
    const u = new URL(pageUrl);
    if (!/^https?:$/.test(u.protocol)) return '';
    const html = await getRaw(u.toString());
    const img = metaContent(html, 'og:image') || metaContent(html, 'twitter:image');
    if (!img || /^data:/i.test(img)) return '';
    const abs = new URL(img, u).toString();
    return /^https:\/\//.test(abs) ? abs : '';
  } catch (e) { return ''; }
}

(async () => {
  const files = ['ported2browser.json', 'crazygames.json', 'curated.json'];
  let found = 0, kept = 0, total = 0;
  for (const f of files) {
    const file = path.join(DATA_DIR, f);
    let items;
    try { items = JSON.parse(fs.readFileSync(file, 'utf8')); } catch (e) { continue; }
    for (const g of items) {
      const cur = g.cover || g.poster || '';
      let host = '';
      try { host = new URL(cur).hostname.replace(/^www\./, ''); } catch (e) {}
      // Fill entries that have no cover at all, and replace ones that hang off a portal's CDN —
      // a game's own page is fine where it is.
      if (cur && !DEP_HOSTS.test(host)) continue;
      total++;
      const hit = await imageFor(g.title);
      if (hit) {
        g.cover = hit.url;
        g.coverSrc = 'wikipedia:' + hit.page;
        found++;
      } else if (!cur && g.playUrl) {
        // No Wikipedia page and no old cover: ask the game's own page for its picture.
        const own = await ogImage(g.playUrl);
        if (own) { g.cover = own; g.coverSrc = 'ownpage'; found++; }
        else { g.coverSrc = 'none'; kept++; }
      } else {
        g.coverSrc = 'keep:' + host;   // no confident match — the old cover stays as fallback
        kept++;
      }
      console.log((hit ? 'art ' : 'keep') + ' ' + f.padEnd(22) + ' ' + g.title.slice(0, 52));
      await sleep(SLEEP_MS);
    }
    const tmp = file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(items, null, 1));
    fs.renameSync(tmp, file);
  }
  console.log(`posters: ${found} from wikipedia, ${kept} kept (${total} portal-hosted checked)`);
})();
