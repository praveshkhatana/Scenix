// One-shot link validity sweep for the games catalogue.
// For every entry's playUrl: follow redirects with a browser UA, judge by final
// status and body content (GoDaddy landers and soft-404s return 200 with nothing useful).
const fs = require('fs');
const dir = '/tmp/games-build/hub/data/games';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36';
const entries = [];
for (const f of fs.readdirSync(dir)) {
  if (!f.endsWith('.json')) continue;
  const a = JSON.parse(fs.readFileSync(dir + '/' + f));
  for (const g of (a.games || a)) entries.push({ file: f, id: g.id, title: g.title, url: g.playUrl, coi: !!g.coi });
}
async function probe(e) {
  try {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), 15000);
    const r = await fetch(e.url, { redirect: 'follow', signal: ctrl.signal, headers: { 'user-agent': UA, 'accept': 'text/html,*/*' } });
    const body = await r.text().catch(() => '');
    clearTimeout(t);
    const low = body.toLowerCase();
    let verdict = 'ok';
    if (r.status >= 400) verdict = 'http' + r.status;
    else if (body.length < 200) verdict = 'tiny:' + body.length;
    else if (low.includes('window.location.href="/lander"') || low.includes('godaddy')) verdict = 'lander';
    else if (low.includes('not found') && !low.includes('<canvas') && !low.includes('game')) verdict = 'soft404';
    else if (!low.includes('<html')) verdict = 'nohtml';
    e.verdict = verdict; e.bytes = body.length; e.finalUrl = r.url;
  } catch (err) { e.verdict = 'fail:' + (err.cause?.code || err.name || err.message).slice(0, 40); }
}
(async () => {
  const q = entries.slice();
  const workers = Array.from({ length: 10 }, async () => { while (q.length) await probe(q.shift()); });
  await Promise.all(workers);
  fs.writeFileSync('/tmp/linkcheck.json', JSON.stringify(entries, null, 1));
  const ok = entries.filter(e => e.verdict === 'ok');
  console.log('total', entries.length, 'ok', ok.length);
  for (const e of entries.filter(e => e.verdict !== 'ok')) console.log(e.file, '|', e.title, '|', e.verdict, '|', e.url);
})();
