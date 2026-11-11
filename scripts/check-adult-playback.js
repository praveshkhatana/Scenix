'use strict';
/*
 * scripts/check-adult-playback.js — does an adult/late-night tile end in a picture or an honest card?
 *
 * The owner's report was "Adult/late-night contents all failed to play": the spinner ran, then
 * nothing. This walks the adult-scope shelves of a running hub, presses Play on a few titles the same
 * way the TV does, and asserts every answer is one of exactly two things:
 *
 *   · a playable http(s) URL, or
 *   · a terminal refusal (401..499 without `warming`, carrying a `code`/`detail`/`nextAction`)
 *
 * Anything else fails the check — in particular a `warming: true` answer that carries a magnet while
 * the hub has neither a P2P gateway nor a debrid account. Nothing will ever warm that answer, so the
 * app waits forever. A 200 with a non-http URL fails too: the player cannot open a magnet.
 *
 * Read-only: it presses Play, it never changes a profile, a setting or a source. With the gateway on
 * a press may start a torrent, so point it at the household hub or a disposable one.
 *
 *   node scripts/check-adult-playback.js
 *   node scripts/check-adult-playback.js --hub http://127.0.0.1:8792 --profile 1000 --limit 8
 *
 * Exit code is 0 when every press resolved honestly (playable or terminal), 1 otherwise.
 */

const arg = (name, fallback) => {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
};

const HUB = String(arg('hub', process.env.HUB || 'http://127.0.0.1:8788')).replace(/\/+$/, '');
const PROFILE = String(arg('profile', process.env.PROFILE || '1000'));
const LIMIT = Math.max(1, Number(arg('limit', process.env.LIMIT || '6')) || 6);
const PRESS_TIMEOUT_MS = Number(arg('timeout', '30000')) || 30000;

const TERMINAL_CODES = new Set(['torrents-need-gateway', 'blocked', 'no-copy']);

async function getJson(path, timeoutMs = 8000) {
  const r = await fetch(HUB + path, { signal: AbortSignal.timeout(timeoutMs) });
  const text = await r.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch (e) { body = { raw: text.slice(0, 300) }; }
  return { status: r.status, body };
}

async function press(item) {
  const body = {
    identity: item.identity,
    profile: PROFILE,
    type: item.type || 'movie',
    container: 'any',
  };
  if (body.type === 'series') {
    const ep = item.progress && item.progress.episode;
    body.season = Number(ep && ep.season) || 1;
    body.episode = Number(ep && ep.episode) || 1;
  }
  const r = await fetch(HUB + '/app/v1/play', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(PRESS_TIMEOUT_MS),
  });
  const text = await r.text();
  let reply = null;
  try { reply = text ? JSON.parse(text) : null; } catch (e) { reply = { raw: text.slice(0, 300) }; }
  return { status: r.status, body: reply || {} };
}

function playable(url) {
  return typeof url === 'string' && /^https?:\/\//i.test(url);
}

/**
 * The TV retries a genuinely warming answer (a cold torrent on a hub that can serve one). Mirror that
 * budget here; a warming answer is only a failure after it. When the hub cannot serve torrents there
 * is nothing to wait for, so one press is the whole test.
 */
async function pressWithWarmup(item, couldServe) {
  const tries = couldServe ? 4 : 1;
  let reply = null;
  for (let i = 0; i < tries; i++) {
    reply = await press(item);
    if (reply.body && reply.body.warming === true && i < tries - 1) {
      await new Promise((r) => setTimeout(r, 1500));
      continue;
    }
    break;
  }
  return reply;
}

/**
 * The whole contract in one function. `couldServe` is the hub's own capability from /healthz: with
 * both the gateway and debrid off, no amount of waiting can produce a playable torrent link.
 */
function classify(reply, couldServe) {
  const url = reply && reply.url;
  if (reply && reply.warming === true) {
    if (!couldServe) {
      return { pass: false, why: 'warming:true while the hub has no gateway or debrid — nothing can ever warm this' };
    }
    return { pass: false, why: 'still warming after the app\'s retry budget; the press must end in a verdict' };
  }
  if (reply && TERMINAL_CODES.has(String(reply.code || ''))) {
    if (!reply.detail || !reply.nextAction) {
      return { pass: false, why: 'terminal code ' + reply.code + ' without detail/nextAction for the card' };
    }
    return { pass: true, why: 'terminal ' + reply.code };
  }
  if (reply && reply.status == null && reply.error === 'no playable source') {
    return { pass: false, why: 'no playable source without a code/detail/nextAction (spinner-then-nothing text)' };
  }
  if (url != null) {
    return playable(url)
      ? { pass: true, why: 'playable ' + String(url).slice(0, 60) }
      : { pass: false, why: '200 with a non-playable address: ' + String(url).slice(0, 60) };
  }
  return { pass: false, why: 'answer has neither a url nor a terminal no-source code' };
}

(async () => {
  const health = await getJson('/healthz');
  if (health.status !== 200) {
    console.error('FAIL: hub ' + HUB + ' /healthz returned ' + health.status);
    process.exit(1);
  }
  const gwMode = String((health.body.gateway || {}).mode || 'off').toLowerCase();
  const debridReady = (health.body.debrid || {}).ready === true;
  const couldServe = gwMode === 'http' || debridReady;
  console.log('hub=' + HUB + ' profile=' + PROFILE
    + ' gateway=' + gwMode + ' debrid=' + (debridReady ? 'ready' : 'off')
    + ' canServeTorrents=' + couldServe);

  const home = await getJson('/app/v1/home?profile=' + encodeURIComponent(PROFILE), 15000);
  if (home.status !== 200 || !home.body || !Array.isArray(home.body.rows)) {
    console.error('FAIL: /app/v1/home returned ' + home.status
      + ((home.body && home.body.error) ? ' (' + home.body.error + ')' : ''));
    process.exit(1);
  }

  // The adult shelves first: that is where the owner pressed Play. The Late Night row is the
  // XXX-gated one and only exists when the profile has that switch on. Ordinary "all" rows are the
  // fallback, so a hub whose adult shelves are empty still gets a real check rather than a skip.
  // The Late Night row carries scope "adult" when it exists, so scope alone finds both it and the
  // ordinary taste shelves. `--xxx` is documentation of intent rather than a different filter; a row
  // that does not exist in the answer cannot be pressed either way.
  const pickRows = (adultOnly) => (home.body.rows || []).filter((row) => {
    if (adultOnly && row.scope !== 'adult' && row.id !== 'latenight') return false;
    return Array.isArray(row.items) && row.items.length > 0;
  });

  let rows = pickRows(true);
  let shelf = 'adult';
  if (!rows.length) {
    rows = pickRows(false);
    shelf = 'all (the adult shelves are empty in this library)';
  }
  const seen = new Set();
  const picks = [];
  for (const row of rows) {
    for (const item of row.items) {
      if (!item || !item.identity || seen.has(item.identity)) continue;
      // A series needs an episode; one is already chosen above. Skip a title whose row is a live
      // channel, because those have their own health path and are not the reported failure.
      if (item.live) continue;
      seen.add(item.identity);
      picks.push({ row: row.id, item });
      if (picks.length >= LIMIT) break;
    }
    if (picks.length >= LIMIT) break;
  }

  console.log('shelf=' + shelf + ' titles=' + picks.length
    + ' rows=' + rows.map((r) => r.id + '(' + r.items.length + ')').join(','));
  if (!picks.length) {
    console.log('SKIP: this library has no adult-scope titles to press Play on.');
    process.exit(0);
  }

  let failed = 0;
  for (const { row, item } of picks) {
    let verdict;
    try {
      const reply = await pressWithWarmup(item, couldServe);
      verdict = classify(reply.body, couldServe);
      verdict.status = reply.status;
      verdict.code = reply.body && reply.body.code;
      verdict.error = reply.body && reply.body.error;
    } catch (e) {
      verdict = { pass: false, why: 'press failed: ' + (e && e.message ? e.message : e) };
    }
    const tag = verdict.pass ? 'PASS' : 'FAIL';
    if (!verdict.pass) failed++;
    console.log(tag + ' ' + row + ' ' + item.identity + ' "' + (item.title || '') + '"'
      + (verdict.status ? ' http=' + verdict.status : '')
      + ' — ' + verdict.why
      + (verdict.error && !verdict.why.includes(verdict.error) ? ' (' + verdict.error + ')' : ''));
  }

  if (failed) {
    console.error('\nFAILED: ' + failed + ' of ' + picks.length + ' adult presses ended in neither a picture nor an honest card.');
    process.exit(1);
  }
  console.log('\nOK: every adult press resolved honestly (' + picks.length + ' titles).');
})().catch((e) => {
  console.error('FAIL: ' + (e && e.stack ? e.stack : e));
  process.exit(1);
});
