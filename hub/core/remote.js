'use strict';
/*
 * Phone remote: a scan-to-type channel for the TV's Settings screen.
 *
 * The TV owns the session. On open, Settings registers a random token and a short code, draws
 * `http://<hub>:<port>/remote?t=<token>&c=<code>` as a QR, and polls this hub for key/text events.
 * A phone that scans the code loads the same self-contained remote page and posts events here. The
 * queue lives in memory only: closing Settings closes the session, and an abandoned session expires
 * after the TTL below.
 *
 * Routes (all under the same hub process, no npm dependency):
 *   POST /app/v1/remote/register   TV, normal hub auth.  {token, code?} -> {token, code, expiresAt}
 *   GET  /app/v1/remote/events     TV, session token.    ?t=&after=      -> queued events + status
 *   POST /app/v1/remote/send       phone, session token. {token, events:[...]} -> {last}
 *   GET  /app/v1/remote/state      phone, session token. ?t=             -> open/closed/expiry
 *   POST /app/v1/remote/close      TV, normal hub auth.  {token}         -> {ok}
 *
 * The token is the only secret the phone carries. It is 128 random bits in hex, so guessing it is
 * not a practical way to drive someone's television. Event payloads are bounded before they enter
 * the queue; a stuck client cannot grow the hub's memory without limit.
 */

const crypto = require('crypto');

const TOKEN_RE = /^[a-f0-9]{32}$/;
const CODE_RE = /^[A-Z2-9]{6}$/;
// Remove characters that look alike when read from a TV across the room (0/O, 1/I).
const CODE_ALPHABET = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
const TTL_MS = 15 * 60 * 1000;
const MAX_SESSIONS = 32;
const MAX_EVENTS = 256;
const MAX_BATCH = 20;
const MAX_TEXT = 256;
const KEYS = new Set([
  'UP', 'DOWN', 'LEFT', 'RIGHT', 'OK', 'BACK', 'HOME', 'MENU',
  'PLAY_PAUSE', 'REWIND', 'FAST_FORWARD', 'DELETE',
]);

/** @type {Map<string, {token:string, code:string, createdAt:number, expiresAt:number, tvSeenAt:number, phoneSeenAt:number, seq:number, events:Array, closed:boolean}>} */
const sessions = new Map();

function code6() {
  let out = '';
  for (let i = 0; i < 6; i++) out += CODE_ALPHABET[crypto.randomInt(CODE_ALPHABET.length)];
  return out;
}

function prune() {
  const at = Date.now();
  for (const [token, s] of sessions) {
    if (s.closed && at - s.closedAt > 60_000) sessions.delete(token);
    else if (s.expiresAt <= at) sessions.delete(token);
  }
}

function find(token) {
  prune();
  if (typeof token !== 'string' || !TOKEN_RE.test(token)) return null;
  return sessions.get(token) || null;
}

/** A closed/expired answer is a state of the channel, not a server error the page should retry. */
function gone(res, send, message) {
  return send(res, 410, { ok: false, error: message, closed: true });
}

function cleanEvents(raw) {
  if (!Array.isArray(raw)) return null;
  if (raw.length > MAX_BATCH) return null;
  const out = [];
  for (const e of raw) {
    if (!e || typeof e !== 'object') return null;
    if (e.type === 'key') {
      const key = String(e.key || '').toUpperCase();
      if (!KEYS.has(key)) return null;
      out.push({ type: 'key', key });
    } else if (e.type === 'text') {
      const text = String(e.text == null ? '' : e.text);
      if (!text || text.length > MAX_TEXT) return null;
      out.push({ type: 'text', text });
    } else {
      return null;
    }
  }
  return out.length ? out : null;
}

/**
 * Register or refresh the TV's channel. The TV generates the token so the QR can be drawn before
 * this request completes; the hub only accepts well-formed values and keeps the queue.
 */
function register(body) {
  prune();
  const token = String((body && body.token) || '').trim().toLowerCase();
  if (!TOKEN_RE.test(token)) return { status: 400, body: { ok: false, error: 'a 32-character hex token is required' } };
  const supplied = String((body && body.code) || '').trim().toUpperCase();
  if (supplied && !CODE_RE.test(supplied)) {
    return { status: 400, body: { ok: false, error: 'the code must be six letters or digits' } };
  }
  if (!sessions.has(token) && sessions.size >= MAX_SESSIONS) {
    // The oldest idle session is the one a person stopped looking at; evict it rather than
    // refusing to open Settings on a new screen.
    const oldest = [...sessions.entries()].sort((a, b) => a[1].tvSeenAt - b[1].tvSeenAt)[0];
    if (oldest) sessions.delete(oldest[0]);
  }
  const at = Date.now();
  const prior = sessions.get(token);
  const code = supplied || (prior && prior.code) || code6();
  const session = {
    token,
    code,
    createdAt: prior ? prior.createdAt : at,
    expiresAt: at + TTL_MS,
    tvSeenAt: at,
    phoneSeenAt: 0,
    seq: prior ? prior.seq : 0,
    events: prior ? prior.events : [],
    closed: false,
    closedAt: 0,
  };
  sessions.set(token, session);
  return {
    status: 200,
    body: { ok: true, token, code, expiresAt: session.expiresAt, ttlMs: TTL_MS },
  };
}

function events(token, after) {
  const s = find(token);
  if (!s) return { status: 404, body: { ok: false, error: 'no such remote session', expired: true } };
  if (s.closed) return { status: 410, body: { ok: false, error: 'the TV closed the remote', closed: true } };
  const at = Date.now();
  // A TV that is still on Settings keeps its QR valid for as long as the screen is open.
  s.tvSeenAt = at;
  s.expiresAt = at + TTL_MS;
  const from = Number.isFinite(after) ? Math.max(0, Math.floor(after)) : 0;
  return {
    status: 200,
    body: {
      ok: true,
      events: s.events.filter((e) => e.seq > from),
      last: s.seq,
      code: s.code,
      expiresAt: s.expiresAt,
      phoneSeenAt: s.phoneSeenAt,
      closed: false,
    },
  };
}

function sendEvents(body) {
  const token = String((body && body.token) || '').trim().toLowerCase();
  const s = find(token);
  if (!s) return { status: 404, body: { ok: false, error: 'no such remote session', expired: true } };
  if (s.closed) return { status: 410, body: { ok: false, error: 'the TV closed the remote', closed: true } };
  const incoming = cleanEvents(body && body.events);
  if (!incoming) return { status: 400, body: { ok: false, error: 'send 1-20 key or text events' } };
  const at = Date.now();
  s.phoneSeenAt = at;
  for (const e of incoming) {
    s.seq += 1;
    s.events.push({ seq: s.seq, ...e });
  }
  if (s.events.length > MAX_EVENTS) s.events.splice(0, s.events.length - MAX_EVENTS);
  return { status: 200, body: { ok: true, last: s.seq } };
}

function state(token) {
  const s = find(token);
  if (!s) return { status: 404, body: { ok: false, error: 'no such remote session', expired: true } };
  if (s.closed) return { status: 410, body: { ok: false, error: 'the TV closed the remote', closed: true } };
  const at = Date.now();
  s.phoneSeenAt = at;
  return {
    status: 200,
    body: {
      ok: true,
      open: true,
      code: s.code,
      expiresAt: s.expiresAt,
      tvSeenAt: s.tvSeenAt,
      // The phone can say "TV is listening" without another route.
      tvListening: at - s.tvSeenAt < 15_000,
      closed: false,
    },
  };
}

function close(body) {
  const token = String((body && body.token) || '').trim().toLowerCase();
  const s = find(token);
  if (!s) return { status: 200, body: { ok: true, alreadyClosed: true } };
  s.closed = true;
  s.closedAt = Date.now();
  s.events = [];
  return { status: 200, body: { ok: true } };
}

/**
 * Answer a remote route, or return false so the main router can continue. `readBody` is the hub's
 * own JSON body reader, passed in to avoid a second HTTP parser.
 */
async function handle(req, res, path, url, send, readBody, authorized) {
  if (!path.startsWith('/app/v1/remote/') && path !== '/remote' && path !== '/remote/') return false;
  const method = req.method;
  const query = url.searchParams;

  if (path === '/app/v1/remote/register' && method === 'POST') {
    // Creating a channel changes what a phone may drive, so it needs the normal hub credential.
    if (!authorized(req)) {
      send(res, 401, { ok: false, error: 'authentication required' });
      return true;
    }
    const body = await readBody(req);
    const out = register(body);
    send(res, out.status, out.body);
    return true;
  }
  if (path === '/app/v1/remote/close' && method === 'POST') {
    if (!authorized(req)) {
      send(res, 401, { ok: false, error: 'authentication required' });
      return true;
    }
    const body = await readBody(req);
    const out = close(body);
    send(res, out.status, out.body);
    return true;
  }
  if (path === '/app/v1/remote/events' && method === 'GET') {
    const out = events(query.get('t') || '', Number(query.get('after') || 0));
    send(res, out.status, out.body);
    return true;
  }
  if (path === '/app/v1/remote/state' && method === 'GET') {
    const out = state(query.get('t') || '');
    send(res, out.status, out.body);
    return true;
  }
  if (path === '/app/v1/remote/send' && method === 'POST') {
    const body = await readBody(req);
    if (!body) {
      send(res, 400, { ok: false, error: 'expected a JSON body' });
      return true;
    }
    const out = sendEvents(body);
    send(res, out.status, out.body);
    return true;
  }
  if (path.startsWith('/app/v1/remote/')) {
    send(res, 404, { ok: false, error: 'no such remote route' });
    return true;
  }
  // `/remote` is the page itself; webui answers it.
  return false;
}

module.exports = {
  handle,
  // Exposed for the static gate and for a future route test; no other module mutates these.
  _sessions: sessions,
  TTL_MS,
  CODE_ALPHABET,
};
