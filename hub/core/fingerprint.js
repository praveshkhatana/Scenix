'use strict';
/*
 * core/fingerprint.js — the one cheap fact that separates this film from another file with the right
 * name: how long it runs.
 *
 * A provider can return a perfectly valid MP4 carrying a different cut, a trailer reel or another film.
 * The URL is opaque and the filename is a claim. ffprobe reads the duration from the header, so the
 * picker can compare it with the title's own runtime before handing it to the player. The result is
 * cached in memory: one header read per URL, not one per Play press.
 *
 * It is not a scene hash. It is the guard that catches the common “this is a 97-minute file for a
 * 112-minute film” mistake without downloading the film or pretending a filename is proof.
 */

const { spawn } = require('child_process');

const FFPROBE_BIN = process.env.FFPROBE_BIN || 'ffprobe';
const TTL_MS = Math.max(60e3, Number(process.env.FINGERPRINT_TTL_MS || 10 * 60e3));
const FAIL_TTL_MS = Math.max(15e3, Number(process.env.FINGERPRINT_FAIL_TTL_MS || 3 * 60e3));
const TIMEOUT_MS = Math.max(1000, Number(process.env.FINGERPRINT_TIMEOUT_MS || 4000));
const cache = new Map();

function probe(url, headers, timeoutMs) {
  return new Promise((resolve) => {
    const started = Date.now();
    const args = ['-v', 'error', '-show_entries', 'format=duration', '-of', 'json'];
    const entries = Object.entries(headers || {}).filter(([, v]) => v != null && String(v) !== '');
    if (entries.length) {
      args.push('-headers', entries.map(([k, v]) => k + ': ' + v).join('\r\n') + '\r\n');
    }
    args.push(String(url));
    let child;
    try {
      child = spawn(FFPROBE_BIN, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    } catch (e) {
      return resolve({ ok: false, error: 'ffprobe could not start', ms: Date.now() - started });
    }
    let out = '';
    let err = '';
    const timer = setTimeout(() => { try { child.kill('SIGKILL'); } catch (e) { /* gone */ } }, Math.max(500, timeoutMs));
    child.stdout.on('data', (c) => { if (out.length < 2e5) out += c.toString(); });
    child.stderr.on('data', (c) => { if (err.length < 2000) err += c.toString(); });
    child.on('error', (e) => {
      clearTimeout(timer);
      resolve({ ok: false, error: e.code || e.message, ms: Date.now() - started });
    });
    child.on('close', (code) => {
      clearTimeout(timer);
      if (code !== 0) return resolve({ ok: false, error: (err.split('\n').filter(Boolean)[0] || 'ffprobe exited ' + code).slice(0, 200), ms: Date.now() - started });
      try {
        const j = JSON.parse(out);
        const sec = Number(j && j.format && j.format.duration);
        if (!Number.isFinite(sec) || sec <= 0) return resolve({ ok: false, error: 'no duration', ms: Date.now() - started });
        resolve({ ok: true, durationMs: Math.round(sec * 1000), ms: Date.now() - started });
      } catch (e) {
        resolve({ ok: false, error: 'unreadable ffprobe output', ms: Date.now() - started });
      }
    });
  });
}

/** Header duration for one URL, or null when the file cannot answer. Cached, bounded and never throws. */
async function duration(url, headers, timeoutMs = TIMEOUT_MS) {
  if (!/^https?:/i.test(String(url || ''))) return null;
  const key = String(url) + '|' + JSON.stringify(headers || {});
  const hit = cache.get(key);
  const now = Date.now();
  if (hit && now - hit.at < (hit.ok ? TTL_MS : FAIL_TTL_MS)) return hit;
  const result = await probe(url, headers, timeoutMs);
  const entry = { ...result, at: now };
  cache.set(key, entry);
  if (cache.size > 600) cache.delete(cache.keys().next().value);
  return entry;
}

/** 4% is the same tolerance the audio-donor fit uses: a different edit, not a few seconds of logos. */
function matches(expectedMs, actualMs) {
  const expected = Number(expectedMs);
  const actual = Number(actualMs);
  if (!(expected > 0) || !(actual > 0)) return true; // no opinion, not a refusal
  return Math.abs(actual - expected) <= expected * 0.04;
}

module.exports = { duration, matches, status: () => ({ entries: cache.size, bin: FFPROBE_BIN }) };
