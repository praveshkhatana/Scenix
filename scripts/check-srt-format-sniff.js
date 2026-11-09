'use strict';
/*
 * scripts/check-srt-format-sniff.js — no-network fixture checks for the subtitle format sniff.
 *
 * Some addons serve SRT bytes from a URL and name that say ".vtt"; the app trusts the hub's
 * `format` first, so a label of "vtt" for SRT bytes kills the player ("Expected WEBVTT. Got 1").
 * The hub now sniffs a small range GET through the same DNS-pinned transport that guards every
 * addon fetch. This script stubs Node's HTTP transport (like check-addon-boundary.js), so the
 * pure detector and the pinned head fetch are verified exactly, without touching the network.
 *
 *   node scripts/check-srt-format-sniff.js
 */

const assert = require('assert');
const dns = require('dns');
const { EventEmitter } = require('events');
const http = require('http');
const https = require('https');
const addons = require('../hub/core/addons');
const { util } = addons;

const SRT = '1\n00:00:01,000 --> 00:00:04,000\nHello, world\n\n2\n00:00:05,000 --> 00:00:08,000\nSecond cue\n';
const SRT_NO_NUMBER = '00:00:01,000 --> 00:00:04,000\nHello, world\n';
const VTT = 'WEBVTT\n\n00:00:01.000 --> 00:00:04.000\nHello, world\n';
const VTT_BOM = '\uFEFFWEBVTT - caption\n\n00:00:01.000 --> 00:00:04.000\nHello\n';
const ASS = '[Script Info]\nTitle: Demo\nScriptType: v4.00+\n\n[V4+ Styles]\nFormat: Name, Fontname\n\n[Events]\nFormat: Layer, Start, End, Text\n0,0:00:01.00,0:00:04.00,,Hello\n';
const TTML = '<?xml version="1.0" encoding="utf-8"?>\n<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="00:00:01.000" end="00:00:04.000">Hello</p></div></body></tt>';
const TTML_BARE = '<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="00:00:01" end="00:00:04">Hello</p></div></body></tt>';
const HTML = '<!doctype html><html><body>Not a subtitle</body></html>';
const JSON_PAGE = '{"subtitles":[]}';

function response(statusCode, headers = {}) {
  const res = new EventEmitter();
  res.statusCode = statusCode;
  res.headers = headers;
  res.resume = () => {};
  return res;
}

function emitBody(res, body) {
  if (body && body.length) res.emit('data', Buffer.from(body, 'utf8'));
  res.emit('end');
}

async function withStubbedTransport(handler, work) {
  const originalHttpGet = http.get;
  const originalHttpsGet = https.get;
  const stub = (url, options, onResponse) => {
    const req = new EventEmitter();
    req.destroy = () => { req.destroyed = true; };
    process.nextTick(() => handler({ url, options, onResponse, req }));
    return req;
  };
  http.get = stub;
  https.get = stub;
  try {
    return await work();
  } finally {
    http.get = originalHttpGet;
    https.get = originalHttpsGet;
  }
}

async function withStubbedLookup(lookup, work) {
  const originalLookup = dns.promises.lookup;
  dns.promises.lookup = lookup;
  try {
    return await work();
  } finally {
    dns.promises.lookup = originalLookup;
  }
}

function serve(body, statusCode = 200, headers = {}, seen = null) {
  return ({ onResponse, options }) => {
    if (seen) seen.push({ range: options && options.headers && options.headers.Range });
    const res = response(statusCode, headers);
    onResponse(res);
    process.nextTick(() => emitBody(res, body));
  };
}

(async () => {
  // ---- pure detector: the opening bytes decide ---------------------------------
  assert.strictEqual(util.detectSubtitleFormat(SRT), 'srt', 'SRT with cue numbers');
  assert.strictEqual(util.detectSubtitleFormat(SRT_NO_NUMBER), 'srt', 'SRT without cue numbers');
  assert.strictEqual(util.detectSubtitleFormat(VTT), 'vtt', 'WebVTT');
  assert.strictEqual(util.detectSubtitleFormat(VTT_BOM), 'vtt', 'WebVTT with BOM and title');
  assert.strictEqual(util.detectSubtitleFormat(ASS), 'ass', 'ASS');
  assert.strictEqual(util.detectSubtitleFormat(TTML), 'ttml', 'TTML with XML declaration');
  assert.strictEqual(util.detectSubtitleFormat(TTML_BARE), 'ttml', 'bare TTML');
  assert.strictEqual(util.detectSubtitleFormat('\r\n\r\nWEBVTT\n\n00:00:01.000 --> 00:00:04.000\nHi'), 'vtt', 'leading blank lines');
  assert.strictEqual(util.detectSubtitleFormat('42\n'), 'srt', 'bare cue-number line');
  assert.strictEqual(util.detectSubtitleFormat(HTML), null, 'HTML is not a subtitle');
  assert.strictEqual(util.detectSubtitleFormat(JSON_PAGE), null, 'JSON is not a subtitle');
  assert.strictEqual(util.detectSubtitleFormat(''), null, 'empty');
  assert.strictEqual(util.detectSubtitleFormat(null), null, 'null sample');
  assert.strictEqual(util.detectSubtitleFormat(Buffer.from(SRT, 'utf8')), 'srt', 'Buffer sample');

  // ---- name / declared helpers --------------------------------------------------
  assert.strictEqual(util.normalizeSubtitleFormat('WEBVTT'), 'vtt');
  assert.strictEqual(util.normalizeSubtitleFormat('.srt'), 'srt');
  assert.strictEqual(util.normalizeSubtitleFormat('bogus'), null);
  assert.strictEqual(util.normalizeSubtitleFormat(''), null);
  assert.strictEqual(util.subtitleFormatFromName('English.vtt'), 'vtt');
  assert.strictEqual(util.subtitleFormatFromName('Subtitles - English.SRT'), 'srt');
  assert.strictEqual(util.subtitleFormatFromName('English'), null);
  assert.strictEqual(
    util.withSubtitleExtension('Subtitles \u2022 English.vtt', 'srt'),
    'Subtitles \u2022 English.srt',
    'lying .vtt name corrected to the sniffed truth');
  assert.strictEqual(util.withSubtitleExtension('English', 'srt'), 'English', 'no extension: name untouched');
  assert.strictEqual(util.withSubtitleExtension('English.srt', 'srt'), 'English.srt', 'agreeing name untouched');

  // ---- pinned head fetch: the exact bug (SRT bytes behind a .vtt URL) ----------
  if (typeof util.clearSubFormatCache === 'function') util.clearSubFormatCache();
  {
    const seen = [];
    await withStubbedTransport(serve(SRT, 200, {}, seen), async () => {
      const format = await util.sniffSubtitleFormat('http://8.8.8.8/English.vtt', {});
      assert.strictEqual(format, 'srt', 'SRT bytes behind a .vtt URL sniff as srt, not vtt');
      assert.strictEqual(seen.length, 1, 'one head fetch');
      assert.ok(/^bytes=0-\d+$/.test(String((seen[0] || {}).range || '')), 'a small Range header is sent');
    });
  }
  {
    await withStubbedTransport(serve(VTT), async () => {
      assert.strictEqual(await util.sniffSubtitleFormat('http://8.8.8.9/real.vtt', {}), 'vtt');
      assert.strictEqual(await util.sniffSubtitleFormat('http://8.8.8.9/real.srt', {}), 'vtt',
        'WebVTT bytes behind a .srt URL still sniff as vtt');
    });
  }
  {
    await withStubbedTransport(serve(ASS), async () => {
      assert.strictEqual(await util.sniffSubtitleFormat('http://8.8.8.10/subs/file.vtt', {}), 'ass');
    });
  }
  {
    await withStubbedTransport(serve(TTML), async () => {
      assert.strictEqual(await util.sniffSubtitleFormat('http://8.8.8.11/subs/file.vtt', {}), 'ttml');
    });
  }
  {
    // A host that ignores Range answers 200 with the whole file: the first bytes still decide.
    await withStubbedTransport(serve(SRT, 200, { 'content-length': '999999' }), async () => {
      assert.strictEqual(await util.sniffSubtitleFormat('http://8.8.8.12/ignored-range.vtt', {}), 'srt');
    });
  }
  {
    await withStubbedTransport(serve(HTML), async () => {
      assert.strictEqual(await util.sniffSubtitleFormat('http://8.8.8.13/not-a-sub.vtt', {}), null,
        'undeterminable content: null, the caller falls back to the extension');
    });
  }
  {
    await withStubbedTransport(serve('', 404), async () => {
      assert.strictEqual(await util.sniffSubtitleFormat('http://8.8.8.14/missing.vtt', {}), null,
        'an error status is unknown, never a throw');
    });
  }
  {
    let calls = 0;
    await withStubbedTransport(({ onResponse }) => {
      calls++;
      if (calls === 1) {
        onResponse(response(302, { location: 'http://8.8.8.15/final.vtt' }));
      } else {
        const res = response(200);
        onResponse(res);
        process.nextTick(() => emitBody(res, SRT));
      }
    }, async () => {
      const format = await util.sniffSubtitleFormat('http://8.8.8.15/start.vtt', {});
      assert.strictEqual(format, 'srt', 'redirects are followed inside the pinned transport');
      assert.strictEqual(calls, 2, 'one redirect, then the file');
    });
  }

  // ---- SSRF: private hosts are blocked before any connection -------------------
  {
    let called = false;
    await withStubbedTransport(() => { called = true; }, async () => {
      assert.strictEqual(await util.sniffSubtitleFormat('http://127.0.0.1/English.vtt', {}), null,
        'loopback is blocked');
      assert.strictEqual(called, false, 'no connection is attempted for a blocked host');
    });
  }
  {
    let called = false;
    await withStubbedTransport(() => { called = true; }, async () => {
      await withStubbedLookup(async () => [{ address: '192.168.50.20', family: 4 }], async () => {
        assert.strictEqual(await util.sniffSubtitleFormat('http://rebind.example/English.vtt', {}), null,
          'a hostname resolving to a private address is blocked');
        assert.strictEqual(called, false, 'no connection is attempted for a rebound host');
      });
    });
  }
  {
    // A public hostname resolves, is pinned, and sniffs exactly like an IP literal.
    await withStubbedLookup(async () => [{ address: '8.8.8.8', family: 4 }], async () => {
      await withStubbedTransport(serve(SRT), async () => {
        assert.strictEqual(await util.sniffSubtitleFormat('http://subs.example/English.vtt', {}), 'srt');
      });
    });
  }

  // ---- cache: one probe per URL per TTL ---------------------------------------
  {
    let calls = 0;
    await withStubbedTransport(({ onResponse }) => {
      calls++;
      const res = response(200);
      onResponse(res);
      process.nextTick(() => emitBody(res, SRT));
    }, async () => {
      assert.strictEqual(await util.sniffSubtitleFormat('http://8.8.8.16/cached.vtt', {}), 'srt');
      assert.strictEqual(await util.sniffSubtitleFormat('http://8.8.8.16/cached.vtt', {}), 'srt');
      assert.strictEqual(calls, 1, 'the second ask for the same URL comes from the cache');
    });
  }

  // ---- batch: concurrency-capped, order preserved, failures isolated -----------
  {
    const bodies = {
      'http://8.8.8.20/a.vtt': SRT,
      'http://8.8.8.20/b.vtt': VTT,
      'http://8.8.8.20/c.vtt': ASS,
      'http://8.8.8.20/d.vtt': TTML,
    };
    await withStubbedTransport(({ url, onResponse }) => {
      const res = response(200);
      onResponse(res);
      process.nextTick(() => emitBody(res, bodies[String(url)] || HTML));
    }, async () => {
      const formats = await util.sniffSubtitleFormats([
        'http://8.8.8.20/a.vtt',
        'http://8.8.8.20/b.vtt',
        'http://8.8.8.20/c.vtt',
        'http://8.8.8.20/d.vtt',
        'http://8.8.8.20/missing.vtt', // serves HTML -> null, the rest still land
      ]);
      assert.deepStrictEqual(formats, ['srt', 'vtt', 'ass', 'ttml', null],
        'each entry sniffs to its own truth; one unknown never breaks the batch');
    });
  }

  // ---- timeout: unknown, never a throw, and the process is held for the deadline
  {
    await withStubbedTransport(() => {}, async () => {
      const keepAlive = setInterval(() => {}, 1000);
      try {
        const format = await util.sniffSubtitleFormat('http://8.8.8.21/slow.vtt', { timeoutMs: 300 });
        assert.strictEqual(format, null, 'a timed-out probe is unknown');
      } finally {
        clearInterval(keepAlive);
      }
    });
  }

  // ---- External.subtitles integration shape (via the batch + name helpers) -------
  {
    if (typeof util.clearSubFormatCache === 'function') util.clearSubFormatCache();
    await withStubbedTransport(serve(SRT), async () => {
      const raw = [{ url: 'http://8.8.8.22/English.vtt', name: 'Subtitles \u2022 English.vtt', declared: 'vtt' }];
      const sniffed = await util.sniffSubtitleFormats(raw.map((e) => e.url));
      const truth = sniffed[0] || raw[0].declared || null;
      assert.strictEqual(truth, 'srt', 'sniffed truth beats the lying declared extension');
      assert.strictEqual(
        util.withSubtitleExtension(raw[0].name, truth),
        'Subtitles \u2022 English.srt',
        'the emitted name matches the emitted format');
    });
  }

  console.log('SRT FORMAT SNIFF SELFTEST OK');
})().catch((error) => {
  console.error('SRT FORMAT SNIFF SELFTEST FAILED:', error && error.message);
  process.exitCode = 1;
});
