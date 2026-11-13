/* Build the public game-catalogue add-on (separate repo policy).
 *
 *   node hub/tools/build-game-addon.js [outDir]        default: ../scenix-games-addon
 *
 * The Scenix repo carries no catalogue data (copyright stays with whoever hosts the games). This
 * reads the local runtime data files and writes a folder that is an installable add-on on its
 * own: manifest.json + games.json in exactly the shape core/games.js asks any installed add-on
 * for. Push the folder to a public GitHub repo, serve it through GitHub Pages (or the raw URL),
 * and a household adds one line — the manifest URL — to get every shelf without touching our
 * repo. Re-run after any data refresh; git push publishes it. Nothing here needs the hub running.
 */
const fs = require('fs');
const path = require('path');

const dataDir = path.join(__dirname, '..', 'data', 'games');
const outDir = path.resolve(process.argv[2] || path.join(__dirname, '..', '..', '..', 'scenix-games-addon'));

const load = (f) => { try { return JSON.parse(fs.readFileSync(path.join(dataDir, f), 'utf8')); } catch (e) { return []; } };
const dedupe = (items) => [...new Map(items.map((i) => [i.title.toLowerCase(), i])).values()];

// The card shape games.json promises; the hub fills in defaults for anything left off.
const item = (g, defTouch) => ({
  id: g.id, title: g.title, poster: g.cover || g.poster || '', playUrl: g.playUrl,
  coi: !!g.coi, pad: !!g.pad, kb: g.kb !== false,
  touch: g.touch !== undefined ? !!g.touch : defTouch,
  ua: g.ua || '',
  tags: (g.tags || []).slice(0, 4), desc: (g.desc || '').slice(0, 200),
  verified: !!g.verified,
});

// Ports that can't run on a TV at all stay out even if a data refresh adds them back.
// "Cricket 07" is WebGPU-only and asks the player for their own game folder.
const DROP = [/cricket 07/i];
const keep = (list) => list.filter((g) => !DROP.some((r) => r.test(g.title || '')));

const curated = keep(load('curated.json')).map((g) => item(g, false));
const p2b = keep(load('ported2browser.json')).map((g) => item(g, false));
const crazy = keep(load('crazygames.json')).map((g) => item(g, true));
const rows = [];

if (curated.length) rows.push({ id: 'highlight', title: 'Highlighted', items: curated });

// Games played to completion on the 2GB proxy AVD (and spot-checked on the owner's Sony):
// loaded clean, ran frames, answered the remote. The shelf is the trust anchor of the library —
// The flag lives in the catalogue data itself ("verified": true), set by scripts/tvsweep.mjs
// verdicts plus a screenshot pass, so hub and addon shelve the same games without two id lists.
const tested = dedupe(curated.concat(p2b, crazy).filter((g) => g.verified));
if (tested.length) rows.push({ id: 'tested', title: 'Verified on our TV', items: tested });

const padReady = p2b.filter((g) => g.pad);
const marqueeTags = ['fighting', 'racing', 'sports', 'action', 'shooter'];
const tagRank = (g) => { const at = marqueeTags.findIndex((t) => g.tags.some((x) => x.toLowerCase() === t)); return at < 0 ? marqueeTags.length : at; };
const famous = /tekken|soulcalibur|mortal kombat|need for speed|fifa|grand theft|gta|mario|zelda|pok(e|é)mon|resident evil|silent hill|pro evolution|virtua|guitar hero|wwe|dragon ball|dbz|metal slug|crash|sonic|halo|star wars|f na[f]|angry birds/i;
rows.push({
  id: 'featured', title: 'Play now with a controller',
  items: dedupe(padReady)
    .sort((a, b) => tagRank(a) - tagRank(b) || (famous.test(b.title) ? 1 : 0) - (famous.test(a.title) ? 1 : 0))
    .slice(0, 14),
});

const shelf = (id, title, pool) => { const items = dedupe(pool); if (items.length >= 6) rows.push({ id, title, items }); };
shelf('fighting', 'Fighting', padReady.filter((g) => g.tags.some((t) => /fight/i.test(t))));
shelf('racing', 'Racing & sports', p2b.filter((g) => g.tags.some((t) => /racing|sport/i.test(t))));
shelf('classics', 'Console & PC classics', p2b.filter((g) => g.tags.some((t) => /platform|adventure|rpg|indie/i.test(t))));
shelf('retro', 'Retro & arcade', p2b.filter((g) => g.tags.some((t) => /retro|arcade|puzzle/i.test(t))));
shelf('everything', 'Every port', p2b);
// Shelf titles never carry a partner's brand — the catalogue is ours; sources live in build tools.
if (crazy.length >= 4) rows.push({ id: 'crazy', title: 'Web Arcade', items: crazy });

const manifest = {
  id: 'tv.scenix.games', name: 'Scenix Games', version: '1.0.0',
  description: 'The curated Scenix game catalogue: browser ports of console titles plus a web arcade. Ships as its own repo so the app itself carries no game data.',
  types: ['game'], resources: [{ name: 'games', types: ['game'] }], catalogs: [],
};

fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, 'manifest.json'), JSON.stringify(manifest, null, 1));
// "official" tells a Scenix hub this add-on IS its built-in catalogue published — a hub that has
// both would otherwise shelf every title twice (core/games.js skips the built-ins when it answers).
fs.writeFileSync(path.join(outDir, 'games.json'), JSON.stringify({ official: true, rows }, null, 1));
const bytes = fs.statSync(path.join(outDir, 'games.json')).size;
console.log('addon repo:', outDir);
for (const r of rows) console.log(' ', r.title.padEnd(28), r.items.length);
console.log('games.json bytes:', bytes, '(hub accepts up to 4,000,000)');
