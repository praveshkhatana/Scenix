// One-by-one playtest of the ENTIRE game catalogue on the 2GB-proxy AVD.
// Every title in the data files launches through the QA direct-play hook and gets judged
// like a person would: did the stage mount (carve log), is there a live canvas, does the page
// run animation frames, does remote input move pixels. Signals are appended as one JSON line
// per game to /tmp/sweep-results.jsonl, so an interrupted run resumes where it stopped, and a
// screenshot of each ending state lands in /tmp/sweep-shots/ for the human pass that turns
// "probable" into the verified flag in the data.
//   usage: DEV=emulator-5558 PKG=com.scenix.tv.debug node scripts/tvsweep-full.mjs [--redo]
import { execSync } from 'node:child_process';
import fs from 'node:fs';

const DEV = process.env.DEV || 'emulator-5558';
const PKG = process.env.PKG || 'com.scenix.tv.debug';
const DATA = process.env.DATA || '/tmp/games-build/hub/data/games';
const OUT = process.env.OUT || '/tmp/sweep-results.jsonl';
const SHOTS = process.env.SHOTS || '/tmp/sweep-shots';
const adb = (c) => { try { return execSync(`adb -s ${DEV} shell ${c}`, { encoding: 'utf8' }).trim(); } catch (e) { return String(e.stdout || '').trim(); } };
const wait = (ms) => { try { execSync(`sleep ${ms / 1000}`); } catch (e) {} };

const files = ['curated.json', 'ported2browser.json', 'crazygames.json'];
const seen = new Set(); const cand = [];
for (const f of files) {
  for (const g of JSON.parse(fs.readFileSync(`${DATA}/${f}`, 'utf8'))) {
    if (!g.playUrl || seen.has(g.id)) continue;
    seen.add(g.id); cand.push({ id: g.id, title: g.title, url: g.playUrl, verified: !!g.verified, src: f });
  }
}
if (!process.argv.includes('--redo') && fs.existsSync(OUT)) {
  const done = new Set(fs.readFileSync(OUT, 'utf8').split('\n').map((l) => { try { return JSON.parse(l).id; } catch (e) { return ''; } }));
  for (let i = cand.length - 1; i >= 0; i--) if (done.has(cand[i].id)) cand.splice(i, 1);
}
fs.mkdirSync(SHOTS, { recursive: true });
console.log('SWEEP start remaining=' + cand.length + ' total=' + seen.size);

function carveVerdict() {
  const log = adb('logcat -d -s ScenixGame');
  if (/never started/.test(log)) return 'never-started';
  const err = log.match(/loaderror (\S+) (\S+)/);
  if (err) return 'loaderror ' + err[2];
  if (/domnav=true/.test(log)) return 'carved+domnav';
  return /res="carved"/.test(log) ? 'carved' : (/res="none"/.test(log) ? 'no-canvas' : 'unknown');
}

async function pageProbe() {
  const pid = adb(`pidof ${PKG}`);
  if (!pid) return { canvas: '', frames: 0, dom: '', err: 'dead' };
  try { execSync(`adb -s ${DEV} forward tcp:9224 localabstract:webview_devtools_remote_${pid}`, { stdio: 'ignore' }); } catch (e) {}
  let targets;
  try { targets = await (await fetch('http://127.0.0.1:9224/json')).json(); } catch (e) { return { canvas: '', frames: 0, dom: 0, err: 'cdp:' + e.code }; }
  const page = targets.find((t) => t.type === 'page');
  if (!page) return { canvas: '', frames: 0, dom: 0, err: 'no-target' };
  try {
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; setTimeout(() => rej(new Error('ws-timeout')), 8000); });
    let id = 0; const pending = new Map();
    ws.onmessage = (e) => { const m = JSON.parse(e.data); if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } };
    const ev = (expression, awaitP = false) => {
      const p = new Promise((res) => {
        const n = ++id;
        pending.set(n, (m) => res(m.result?.result?.value));
        ws.send(JSON.stringify({ id: n, method: 'Runtime.evaluate', params: { expression, returnByValue: true, awaitPromise: awaitP } }));
      });
      return Promise.race([p, new Promise((r) => setTimeout(() => r('<timeout>'), 7000))]);
    };
    const out = {};
    out.canvas = await ev(`(function(){var c=[...document.querySelectorAll('canvas')].filter(x=>x.width>50&&x.height>50);return c.length?c.map(x=>x.width+'x'+x.height).join(' ; '):''})()`);
    out.frames = await ev(`new Promise(function(r){var n=0;(function f(){if(++n<25)requestAnimationFrame(f);else r(n)})()})`, true);
    out.text = await ev(`document.body?document.body.innerText.slice(0,120):''`);
    out.dom = await ev(`(document.body?[...document.body.querySelectorAll('button,[onclick],[role=button]')].length:0)+'|'+(document.body?document.body.innerText.length:0)`);
    ws.close();
    return out;
  } catch (e) { return { canvas: '', frames: 0, dom: '', err: 'probe:' + (e.message || e) }; }
}

function shot(name) {
  try {
    adb('screencap -p /sdcard/s.png');
    execSync(`adb -s ${DEV} pull /sdcard/s.png "${SHOTS}/${name}"`, { stdio: 'ignore' });
    return execSync(`md5 -q "${SHOTS}/${name}"`, { encoding: 'utf8' }).trim();
  } catch (e) { return ''; }
}

(async () => {
  let n = 0;
  for (const g of cand) {
    n++;
    adb('logcat -c');
    adb(`am force-stop ${PKG}`);
    wait(800);
    adb(`am start -n ${PKG}/com.scenix.tv.MainActivity --es qa_url "${g.url}" --es qa_title "${g.title.replace(/"/g, '')}"`);
    wait(30000);
    let verdict = carveVerdict();
    if (verdict === 'unknown') { wait(20000); verdict = carveVerdict(); }
    let probe = { canvas: '', frames: 0, dom: '' };
    try { probe = await pageProbe(); } catch (e) {}
    adb('input keyevent 21'); adb('input keyevent 21'); adb('input keyevent 22');
    adb('input keyevent 19'); adb('input keyevent 23');
    wait(4000);
    const h1 = shot(g.id + '-a.png');
    adb('input keyevent 23'); adb('input keyevent 23');
    wait(5000);
    const h2 = shot(g.id + '-b.png');
    const row = {
      n, id: g.id, title: g.title, src: g.src, url: g.url, wasVerified: g.verified,
      verdict, canvas: probe.canvas, frames: probe.frames, dom: probe.dom, text: (probe.text || '').slice(0, 80),
      changing: !!h1 && h1 !== h2, err: probe.err || '',
    };
    // A first rough grade; the screenshot pass is what actually sets the flag.
    row.grade = (verdict.startsWith('carved') || (probe.canvas && Number(probe.frames) > 10)) && Number(probe.frames) >= 10 ? 'probable'
      : (Number(probe.frames) >= 5 || /\d/.test(String(probe.dom))) ? 'dom-maybe' : 'fail';
    fs.appendFileSync(OUT, JSON.stringify(row) + '\n');
    console.log(`${n}/${cand.length} ${row.grade.padEnd(9)} ${g.title} [${verdict}] f=${probe.frames}`);
    adb('input keyevent 4'); wait(700); adb('input keyevent 4'); wait(1200);
  }
  console.log('SWEEP done');
})();
