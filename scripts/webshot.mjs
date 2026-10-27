#!/usr/bin/env node
/*
 * tools/webshot.mjs — drive the web UI in a real browser and look at it.
 *
 * A plain `--screenshot` run cannot seed state or press a key, so it only ever shows the entry
 * screen. This speaks CDP over the WebSocket Node already provides: no dependency, no build.
 * It sets localStorage, navigates, presses D-pad keys like a viewer with a remote, and shoots
 * what answers — the only way to tell a screen that renders from a screen that exists.
 *
 *   node tools/webshot.mjs <url> <out.png> [--seed k=v]... [--js expr]... [--key Name]...
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';

const CHROME = process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = Number(process.env.CDP_PORT || 9333);
const argv = process.argv.slice(2);
const url = argv.shift();
const out = argv.shift();
if (!url || !out) {
  console.log('usage: webshot <url> <out.png> [--seed k=v] [--js expr] [--key Name]');
  process.exit(2);
}
const opts = { seed: [], js: [], key: [] };
while (argv.length) {
  const f = argv.shift();
  if (f === '--seed') opts.seed.push(argv.shift());
  else if (f === '--js') opts.js.push(argv.shift());
  else if (f === '--key') opts.key.push(argv.shift());
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const KEY = { Up: ['ArrowUp', 38], Down: ['ArrowDown', 40], Left: ['ArrowLeft', 37],
  Right: ['ArrowRight', 39], Enter: ['Enter', 13], Escape: ['Escape', 27],
  Backspace: ['Backspace', 8], Menu: ['ContextMenu', 93], Tab: ['Tab', 9] };

const chrome = spawn(CHROME, ['--headless=new', '--disable-gpu', '--hide-scrollbars',
  '--no-first-run', '--window-size=1920,1080', `--remote-debugging-port=${PORT}`,
  '--user-data-dir=/tmp/scenix-cdp', 'about:blank'], { stdio: 'ignore' });

let ws, nextId = 1;
const pending = new Map();
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const id = nextId++;
  pending.set(id, { resolve, reject });
  ws.send(JSON.stringify({ id, method, params }));
});
const evl = async (expression) => {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true });
  return r.result?.value ?? r.exceptionDetails?.text ?? null;
};

async function main() {
  let target;
  for (let i = 0; i < 40 && !target; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
      target = list.find((t) => t.type === 'page')?.webSocketDebuggerUrl;
    } catch { /* not up yet */ }
    if (!target) await sleep(250);
  }
  if (!target) throw new Error('no debugging port');
  ws = new WebSocket(target);
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) {
      const p = pending.get(m.id); pending.delete(m.id);
      m.error ? p.reject(new Error(m.error.message)) : p.resolve(m.result);
    } else if (m.method === 'Runtime.exceptionThrown') {
      console.log('  JS ERROR:', m.params.exceptionDetails?.exception?.description
        || m.params.exceptionDetails?.text);
    } else if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
      console.log('  console.error:', (m.params.args || []).map((a) => a.value ?? a.description).join(' ').slice(0, 200));
    }
  };
  await send('Page.enable');
  await send('Runtime.enable');

  await send('Page.navigate', { url });
  await sleep(2500);
  for (const s of opts.seed) {
    const i = s.indexOf('=');
    await evl(`localStorage.setItem(${JSON.stringify(s.slice(0, i))},${JSON.stringify(s.slice(i + 1))})`);
  }
  if (opts.seed.length) { await send('Page.navigate', { url }); await sleep(3000); }
  for (const j of opts.js) { console.log('  js:', JSON.stringify(await evl(j)).slice(0, 300)); await sleep(1500); }
  for (const k of opts.key) {
    const [name, code] = KEY[k] || [];
    if (!name) { console.log('  unknown key', k); continue; }
    await send('Input.dispatchKeyEvent', { type: 'keyDown', windowsVirtualKeyCode: code,
      nativeVirtualKeyCode: code, key: name }).catch(() => {});
    await send('Input.dispatchKeyEvent', { type: 'keyUp', windowsVirtualKeyCode: code,
      nativeVirtualKeyCode: code, key: name }).catch(() => {});
    await sleep(1000);
  }
  // A row still fetching needs longer than a fixed guess: wait for tiles to actually land.
  for (let i = 0; i < 20; i++) {
    if ((await evl(`document.querySelectorAll('.tile,.card,.poster,.shot').length`)) > 0) break;
    await sleep(700);
  }
  await sleep(1500);
  const shot = await send('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(out, Buffer.from(shot.data, 'base64'));
  console.log('wrote', out);
  console.log('state', await evl(`JSON.stringify({hash:location.hash,focus:document.activeElement?.className||null,`
    + `tiles:document.querySelectorAll('.tile,.card,.poster,.shot').length,`
    + `rows:document.querySelectorAll('.row,.shelf').length,`
    + `text:document.body.innerText.slice(0,200).replace(/\\n+/g,' | ')})`));
  chrome.kill('SIGKILL');
  process.exit(0);
}
main().catch((e) => { console.error('FAILED', e.message); chrome.kill('SIGKILL'); process.exit(1); });
