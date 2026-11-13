'use strict';
/*
 * core/source-setup.js — bounded, one-time browser handoff for configured sources.
 *
 * A TV should be able to say "finish this on the web" without receiving a manifest address,
 * configuration schema, or a value a person supplied to a provider.  The Hub keeps a short-lived
 * opaque code which names a reviewed registry id or a Community directory id; the caller resolves
 * that id again server-side when completion happens.  Consequently a code is never an alternate
 * URL-install API and no configured address is retained in this module or emitted in its responses.
 */

const crypto = require('crypto');
const { URL } = require('url');

const TTL_MS = 10 * 60 * 1000;
const MAX_SESSIONS = 96;
const MAX_COMPLETION_ATTEMPTS = 3;
const CODE_ALPHABET = '23456789ABCDEFGHJKMNPQRSTUVWXYZ';
const CODE_PATTERN = /^SCX-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$/;

function cleanText(value, max) {
  if (typeof value !== 'string') return '';
  return value.replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, max);
}

function codeFromBytes(bytes) {
  const input = Buffer.isBuffer(bytes) ? bytes : Buffer.from(bytes || []);
  if (input.length < 12) throw new Error('setup code entropy unavailable');
  let out = '';
  for (let index = 0; index < 12; index += 1) out += CODE_ALPHABET[input[index] % CODE_ALPHABET.length];
  return `SCX-${out.slice(0, 4)}-${out.slice(4, 8)}-${out.slice(8, 12)}`;
}

function normaliseCode(value) {
  const code = String(value || '').trim().toUpperCase().replace(/\s+/g, '');
  return CODE_PATTERN.test(code) ? code : null;
}

function sourceAt(value) {
  const source = value && typeof value === 'object' ? value : {};
  const kind = source.kind === 'reviewed' || source.kind === 'community' ? source.kind : '';
  const id = cleanText(source.id, 128);
  const name = cleanText(source.name, 96);
  const revision = cleanText(source.revision, 48);
  if (!kind || !id || !name || !revision) return null;
  return Object.freeze({
    kind,
    id,
    name,
    revision,
    trust: kind === 'reviewed' ? 'reviewed' : 'community',
    nonEndorsed: kind === 'community',
  });
}

function publicSession(session, currentAt = Date.now()) {
  if (!session) return null;
  return {
    code: session.code,
    expiresAt: session.expiresAt,
    state: session.state === 'completed' ? 'completed' : 'pending',
    source: {
      name: session.source.name,
      trust: session.source.trust,
      nonEndorsed: session.source.nonEndorsed,
    },
    handoff: {
      method: 'web-code',
      expiresInSeconds: Math.max(0, Math.ceil((session.expiresAt - currentAt) / 1000)),
      instructions: 'On a phone or computer connected to this Hub, open Settings, choose Sources, then finish setup with this code. The TV never receives configuration details.',
    },
  };
}

/**
 * A configured manifest stays on the original HTTPS origin.  Path/query configuration is allowed
 * because that is how compatible providers encode it, but it is only ever handed to addons.add on
 * the Hub and must still pass the add-on network boundary and the source capability validator.
 */
function configuredManifestUrl(value, expectedManifest) {
  let candidate;
  let expected;
  try {
    candidate = new URL(String(value || '').trim());
    expected = new URL(String(expectedManifest || '').trim());
  } catch (error) {
    return { ok: false, error: 'enter the configured public manifest address supplied by this source' };
  }
  if (candidate.protocol !== 'https:' || !candidate.hostname || candidate.username || candidate.password
      || candidate.hash || !/\/manifest\.json$/i.test(candidate.pathname)) {
    return { ok: false, error: 'the configured address must be a credential-free HTTPS manifest address' };
  }
  if (expected.protocol !== 'https:' || candidate.origin !== expected.origin) {
    return { ok: false, error: 'the configured address must remain on this source’s approved origin' };
  }
  return { ok: true, value: candidate.toString() };
}

/** In-memory only: a restart invalidates every unfinished setup rather than leaving a stale capability. */
function create(options = {}) {
  const now = typeof options.now === 'function' ? options.now : () => Date.now();
  const randomBytes = typeof options.randomBytes === 'function' ? options.randomBytes : crypto.randomBytes;
  const ttlMs = Number.isFinite(options.ttlMs) ? Math.max(60_000, Math.min(TTL_MS, Math.floor(options.ttlMs))) : TTL_MS;
  const capacity = Number.isFinite(options.capacity) ? Math.max(4, Math.min(MAX_SESSIONS, Math.floor(options.capacity))) : MAX_SESSIONS;
  const sessions = new Map();

  function prune() {
    const at = now();
    for (const [code, session] of sessions) {
      if (!session || session.expiresAt <= at) sessions.delete(code);
    }
    while (sessions.size >= capacity) {
      const oldest = sessions.keys().next().value;
      if (!oldest) break;
      sessions.delete(oldest);
    }
  }

  function live(code) {
    prune();
    const normalized = normaliseCode(code);
    return normalized ? sessions.get(normalized) || null : null;
  }

  function createSession(sourceValue) {
    const source = sourceAt(sourceValue);
    if (!source) return null;
    prune();
    let code = '';
    for (let tries = 0; tries < 12; tries += 1) {
      code = codeFromBytes(randomBytes(12));
      if (!sessions.has(code)) break;
      code = '';
    }
    if (!code) throw new Error('could not allocate a one-time setup code');
    const session = {
      code,
      source,
      createdAt: now(),
      expiresAt: now() + ttlMs,
      state: 'pending',
      attempts: 0,
    };
    sessions.set(code, session);
    return publicSession(session, now());
  }

  function status(code) {
    return publicSession(live(code), now());
  }

  /** Reserve one completion at a time; another tab cannot replay the code while the source installs. */
  function beginCompletion(code) {
    const session = live(code);
    if (!session) return { ok: false, status: 404, error: 'setup code is unavailable or expired' };
    if (session.state === 'completed') return { ok: false, status: 409, error: 'this setup code has already been completed' };
    if (session.state === 'completing') return { ok: false, status: 409, error: 'this setup code is already being completed' };
    if (session.attempts >= MAX_COMPLETION_ATTEMPTS) {
      return { ok: false, status: 429, error: 'this setup code has reached its safe retry limit' };
    }
    session.state = 'completing';
    return { ok: true, code: session.code, source: session.source };
  }

  function releaseCompletion(code) {
    const session = live(code);
    if (!session || session.state !== 'completing') return null;
    session.attempts += 1;
    session.state = 'pending';
    return publicSession(session, now());
  }

  function complete(code) {
    const session = live(code);
    if (!session || session.state !== 'completing') return null;
    session.state = 'completed';
    session.completedAt = now();
    return publicSession(session, now());
  }

  return Object.freeze({
    createSession,
    status,
    beginCompletion,
    releaseCompletion,
    complete,
  });
}

module.exports = {
  TTL_MS,
  MAX_SESSIONS,
  MAX_COMPLETION_ATTEMPTS,
  CODE_PATTERN,
  normaliseCode,
  configuredManifestUrl,
  create,
};
