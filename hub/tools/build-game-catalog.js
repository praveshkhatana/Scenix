/* One-time build of the game catalogs served by the hub.
 *
 *   node hub/tools/build-game-catalog.js
 *
 * - ported2browser.json is scraped by hand (the site hides behind a human check); this script
 *   only post-processes it. The recipe, last run 2026-11-01 for all 296 titles: in an in-app
 *   browser tab on the site, fetch `/?tab=az&page=N` pages collecting /ports/<ULID> hrefs until a
 *   page adds none, then fetch each /ports/<ULID> and read h1, a.play[href],
 *   li.ctl[title="Keyboard|Gamepad|Touch: supported"], meta[property="og:image"], .tags > *.
 * - poki.json comes from the homepage HTML (featured cards, stable markup). Poki itself is not
 *   shelved by core/games.js — its SDK refuses emulated GPUs before inserting a game iframe.
 * - crazygames.json comes from fetching known popular slugs; a slug that no longer resolves is
 *   dropped, so the list can be re-run whenever it rots. */
const fs = require('fs');
const path = require('path');
const dataDir = path.join(__dirname, '..', 'data', 'games');
const UA = { 'user-agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/129.0 Safari/537.36' };

// fetch follows the 301s these portals use; https.get does not.
const get = async url => {
  try {
    const r = await fetch(url, { headers: UA, redirect: 'follow' });
    return { status: r.status, body: await r.text() };
  } catch {
    return { status: 0, body: '' };
  }
};

/* ---- ported2browser post-processing ---- */
function fixP2b() {
  const file = path.join(dataDir, 'ported2browser.json');
  const rows = JSON.parse(fs.readFileSync(file, 'utf8'));
  // The human check hid a few "Play in browser" buttons on the first pass; these are their pages.
  const fills = {
    '01M3F5FTQMGXXCDJKKNE8Q67CW': 'https://ynoproject.net/',
    '01M3MS0W03YP8X5CZWTPN3P7ND': 'https://www.lemon-web.net/applications/lemon-web-games#raft',
    '01M3MS0W0NAZ2DKZHGCBR4EEP6': 'https://www.lemon-web.net/applications/lemon-web-games#minecraft',
    '01M3MS0W0SR9Q7QNS9TM4082QG': 'https://www.lemon-web.net/applications/lemon-web-games#rocketsoccerderby',
    '01M3MWMZ7P9EYYQTVJ47VFN3GA': 'https://www.lemon-web.net/applications/lemon-web-games#quest64',
    '01M3MWMZ7ZK5V4GCHG1DSNABHC': 'https://www.lemon-web.net/applications/lemon-web-games#perfectdark',
    '01M3MWMZ81PJPHEEDDPMMVMPRW': 'https://www.lemon-web.net/applications/lemon-web-games#papermario',
    '01M3MWMZ8CEFS33K2AAR0NEMP8': 'https://www.lemon-web.net/applications/lemon-web-games#ogrebattle64personoflordlycaliber',
    '01M3PTYKPX0HZBMAXY068TMG98': 'https://isle.pizza/',
    '01M3Q05FD0XH0RG0GND1FJ43WV': 'https://redblue.benjamincrozat.com/',
    '01M3SETJRXDV4R306962KTSMR6': 'https://shandalar.dev/',
    '01M3T7CMKA7MPFBC5M2C3JEV10': 'https://cyclonefox.itch.io/xenosaga-pied-piper',
    '01M3VZRNH9PXCP8G20ZP80XNMP': 'https://steponit.online/',
    '01M3WSRP84QNVE520MZHENC4T5': 'https://oldkingscrown.fly.dev/',
    '01M40ZXWEHRG2YK2EXSTZWC45W': 'https://zoombinis.online/',
  };
  for (const r of rows) {
    if (!r.playUrl && fills[r.id]) r.playUrl = fills[r.id];
    r.tags = [...new Set(r.tags || [])].slice(0, 6);
    if ((r.desc || '').startsWith('Your recently launched')) delete r.desc;
    delete r.portBy;
  }
  fs.writeFileSync(file, JSON.stringify(rows.filter(r => r.playUrl), null, 1));
  console.log('ported2browser:', rows.filter(r => r.playUrl).length, 'games');
}

/* ---- poki from the homepage ---- */
async function buildPoki() {
  const html = (await get('https://poki.com/en')).body;
  const seen = new Map();
  for (const m of html.matchAll(/<a[^>]*href="(\/en\/g\/([a-z0-9-]+))"[^>]*>([\s\S]*?)<\/a>/g)) {
    const slug = m[2];
    if (seen.has(slug)) continue;
    const img = /(?:data-src|src)="([^"]*img\.poki-cdn\.com[^"]*)"/.exec(m[3]);
    const title = (/alt="([^"]+)"/.exec(m[3]) || [])[1] || slug;
    // The card images come in tiny squares; ask the CDN for a landscape poster instead.
    const poster = img ? img[1].replace(/width=\d+,height=\d+/, 'width=600,height=338') : '';
    seen.set(slug, { id: 'poki-' + slug, title, playUrl: 'https://poki.com' + m[1], poster });
  }
  const out = [...seen.values()].slice(0, 100);
  fs.writeFileSync(path.join(dataDir, 'poki.json'), JSON.stringify(out, null, 1));
  console.log('poki:', out.length, 'games');
}

/* ---- crazygames from known slugs (dead slugs drop themselves) ---- */
const CG_SLUGS = ['smash-karts', 'moto-x3m', 'shellshockersio', 'agario', 'uno-online', 'geometry-dash-online',
  'worldguessr', 'hangman-cfc', '1v1-lol', 'basket-random', 'basketball-stars', 'block-blast', 'bombit-io',
  'drift-hunters', 'eaglecraft', 'fancy-pants-adventures', 'fall-stadium-last-man-standing', 'getaway-shootout',
  'hole-io', 'maker-mania', 'mountain-driving', 'paint-fun', 'park-master', 'rocket-league-2d', 'speed-racing-3',
  'stickman-archer', 'super-sauce', 'tadpol.io', 'the-next-wooden-door', 'tiny-fishing', 'tube-jumpers',
  'wheely', 'winter-dimensions', 'zombs-royale', 'city-rider', 'deers-logic', 'traffic-rider-vvq'];
async function buildCrazy() {
  const out = [];
  for (const slug of CG_SLUGS) {
    const r = await get('https://www.crazygames.com/game/' + slug);
    if (r.status !== 200) continue;
    const title = (/og:title" content="([^"]+)"/.exec(r.body) || [])[1];
    const img = (/og:image" content="([^"]+)"/.exec(r.body) || [])[1];
    if (!title || /no longer available/i.test(r.body)) continue;
    // og:title arrives as "<Name> 🏎️ Play on CrazyGames" — keep the name, drop the marketing tail.
    const clean = title.replace(/(Play on )?Crazy\s*Games.*$/i, '').replace(/[\s\p{Extended_Pictographic}\uFE0F\u200D]+$/u, '').trim();
    out.push({ id: 'cg-' + slug, title: clean || title, slug,
      playUrl: 'https://games.crazygames.com/en_US/' + slug + '/index.html', poster: img,
      // The CrazyGames wrapper never finishes booting inside Android's WebView bundle; the
      // embedded Gecko engine (the coi container) hosts it. The flag names the container now,
      // and GameActivity's "built multithreaded" notice only fires where a canvas truly lacks SAB.
      coi: true });
    console.log('  cg ok:', slug);
  }
  fs.writeFileSync(path.join(dataDir, 'crazygames.json'), JSON.stringify(out, null, 1));
  console.log('crazygames:', out.length, 'games');
}

(async () => { fixP2b(); await buildPoki(); await buildCrazy(); })();
