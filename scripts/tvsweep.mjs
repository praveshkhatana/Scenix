// Playtest sweep on the 2GB-proxy AVD: launch each game through the QA direct-play hook,
// then judge it like a person would — did the stage mount (carve log), is there a canvas,
// does the page actually run frames, and does remote input move pixels.
import { execSync } from 'node:child_process';

const DEV = process.env.DEV || 'emulator-5558';
const PKG = 'com.scenix.tv';
const adb = (c) => { try { return execSync(`adb -s ${DEV} shell ${c}`, { encoding: 'utf8' }).trim(); } catch (e) { return String(e.stdout || '').trim(); } };
const sleep = (ms) => new Atomics.wait(Atomics.sharedMemory ? new Int32Array(new SharedArrayBuffer(4)) : new Int32Array(1), 0, 0, ms) && new Promise(() => {});
const wait = (ms) => { try { execSync(`sleep ${ms / 1000}`); } catch (e) {} };

const CANDIDATES = JSON.parse(execSync('cat /tmp/tvcands.json', { encoding: 'utf8' }));

function carveVerdict() {
  const log = adb(`logcat -d -s ScenixGame`);
  if (/never started/.test(log)) return 'never-started';
  const err = log.match(/loaderror (\S+) (\S+)/);
  if (err) return 'loaderror ' + err[2];
  return /res="carved"/.test(log) ? 'carved' : (/res="none"/.test(log) ? 'no-canvas' : 'unknown');
}

async function pageProbe() {
  const pid = adb(`pidof ${PKG}`);
  if (!pid) return { canvas: false, frames: 0, err: 'dead' };
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

function shotHash() {
  try {
    adb('screencap -p /sdcard/s.png');
    execSync(`adb -s ${DEV} pull /sdcard/s.png /tmp/s.png`, { stdio: 'ignore' });
    return execSync('md5 -q /tmp/s.png', { encoding: 'utf8' }).trim();
  } catch (e) { return ''; }
}

(async () => {
  const results = [];
  for (const g of CANDIDATES) {
    adb(`logcat -c`);
    adb(`am force-stop ${PKG}`);
    wait(800);
    adb(`am start -n ${PKG}/com.scenix.tv.MainActivity --es qa_url "${g.playUrl}" --es qa_title "${g.title.replace(/"/g, '')}"`);
    wait(30000);
    let verdict = carveVerdict();
    let probe = { canvas: '', frames: 0, dom: '' };
    try { probe = await pageProbe(); } catch (e) {}
    // even when the carve never reported a canvas, probe anyway: DOM games (idlers, card
    // games) mount fine without one, and the frame counter + text tell them apart from dead pages
    adb('input keyevent 21'); adb('input keyevent 21'); adb('input keyevent 22');
    adb('input keyevent 19'); adb('input keyevent 23');
    wait(4000);
    const h1 = shotHash();
    adb('input keyevent 23'); adb('input keyevent 23');
    wait(5000);
    const h2 = shotHash();
    probe.changing = h1 !== h2;
    const row = { title: g.title, id: g.id, verdict, canvas: probe.canvas, frames: probe.frames, dom: probe.dom, changing: !!probe.changing, err: probe.err || '', url: g.playUrl };
    results.push(row);
    console.log(JSON.stringify(row));
    adb(`input keyevent 4`); wait(700); adb(`input keyevent 4`); wait(1200);
  }
  execSync('cat > /tmp/tvsweep.json <<EOF\n' + JSON.stringify(results, null, 1) + '\nEOF');
  process.exit(0);
})();
