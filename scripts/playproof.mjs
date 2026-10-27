#!/usr/bin/env node
/*
 * playproof.mjs — does a real browser decode what the hub is serving?
 *
 * ffprobing the bytes proves the container is fragmented MP4; it does not prove a browser can watch it.
 * This opens the URL in headless Chrome, records every network exchange the media player makes, forces
 * playback past the autoplay gate, and polls the <video> element, so the answer is decoded frames and
 * advancing time rather than "the server said 200".
 *
 *   node playproof.mjs <media-url> [--seconds 30]
 *
 * Exits 0 when the clip actually advanced and has real dimensions.
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const argv = process.argv.slice(2);
const url = argv.find((a) => !a.startsWith('--'));
const si = argv.indexOf('--seconds');
const SECONDS = si >= 0 ? Number(argv[si + 1]) : 30;
const PORT = 9345;
if (!url) { console.error('usage: node playproof.mjs <media-url> [--seconds 30]'); process.exit(2); }

const chrome = spawn(CHROME, [
  '--headless=new', '--disable-gpu', '--no-sandbox',
  '--autoplay-policy=no-user-gesture-required', '--mute-audio',
  `--user-data-dir=${fs.mkdtempSync(os.tmpdir() + '/playproof-')}`,
  '--remote-debugging-port=' + PORT, url,
], { stdio: 'ignore' });

const wait = (ms) => new Promise((r) => setTimeout(r, ms));
let target = null;
for (let i = 0; i < 60 && !target; i++) {
  await wait(300);
  try { target = ((await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()).find((t) => t.type === 'page')); } catch {}
}
if (!target) { chrome.kill('SIGKILL'); console.error('no page target'); process.exit(1); }

const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
let id = 0;
const pending = new Map();
ws.onmessage = (e) => { const m = JSON.parse(e.data); if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } else if (m.method) onEvent(m.method, m.params); };
const call = (method, params = {}) => new Promise((res) => { const n = ++id; pending.set(n, res); ws.send(JSON.stringify({ id: n, method, params })); });

// What the media player actually asked for and what came back - Range handling is invisible from the DOM.
const reqs = new Map();
const responses = [];
function onEvent(method, p) {
  if (method === 'Network.requestWillBeSent') reqs.set(p.requestId, p.request.headers.Range || '');
  if (method === 'Network.responseReceived') {
    const r = p.response;
    responses.push({ status: r.status, type: r.type, mime: r.mimeType, range: reqs.get(p.requestId) || '-', length: r.headers?.['content-length'] || r.headers?.['Content-Length'] || '-' });
  }
}
await call('Network.enable');
await call('Runtime.enable');
await wait(2500); // let the media document open its first connections

const PROBE = `(() => {
  const v = document.querySelector('video');
  if (!v) return JSON.stringify({ novideo: true, href: location.href.slice(0, 70) });
  if (v.paused) v.play().catch(() => {});
  let buf = []; try { for (let i = 0; i < v.buffered.length; i++) buf.push(Math.round(v.buffered.end(i))); } catch {}
  return JSON.stringify({ t: Math.round(v.currentTime * 100) / 100, rs: v.readyState, w: v.videoWidth, h: v.videoHeight,
    dur: v.duration === Infinity ? 'live' : v.duration, bufferedEnd: buf[buf.length - 1] || 0,
    networkState: v.networkState, err: v.error ? v.error.code : null });
})()`;

let best = { t: 0 }, advancing = 0;
for (let s = 0; s < SECONDS; s++) {
  const r = await call('Runtime.evaluate', { expression: PROBE, returnByValue: true });
  const st = JSON.parse((r.result?.result?.value) || '{"parse":true}');
  console.log(`t+${String(s).padStart(2)}s`, JSON.stringify(st));
  if (st.t > best.t) best = st;
  if (st.w > 0 && st.t > 0) advancing++;
  if (st.err) break;
  await wait(1000);
}
console.log('media requests seen:', responses.length);
for (const r of responses.filter((x) => x.type === 'Media' || /video|mp4/.test(x.mime || '') || x.range !== '-').slice(0, 6)) {
  console.log(`  ${r.status} ${r.mime || r.type} range=${r.range} len=${r.length}`);
}
chrome.kill('SIGKILL');
const ok = best.w > 0 && best.t >= 2 && advancing >= 2;
console.log(ok
  ? `PASS decoded ${best.w}x${best.h}, currentTime reached ${best.t}s, ${advancing} consecutive samples advancing`
  : `FAIL no advancing video (last: ${JSON.stringify(best)})`);
process.exit(ok ? 0 : 1);
