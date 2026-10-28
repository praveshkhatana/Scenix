'use strict';
/*
 * core/origin.js — the scheme and host a client actually reached, not the loopback address the process
 * happens to listen on.
 *
 * A hub is often put behind a TLS terminator (Caddy, nginx, a tunnel) because a television should not be
 * asked to trust a self-signed certificate and a household should not be asked to install a CA. The
 * proxy speaks https to the client and http to us; without this, every proxied stream URL and subtitle
 * URL came back as `http://…`, and the browser refused it as mixed content.
 *
 * `X-Forwarded-Proto` and `X-Forwarded-Host` are read only for the answer built for that same request.
 * They cannot grant access, and the Host header was already trusted for the same purpose.
 */

function first(value) {
  return String(value || '').split(',')[0].trim();
}

function scheme(req) {
  const forwarded = first(req.headers && req.headers['x-forwarded-proto']).toLowerCase();
  if (forwarded === 'https' || forwarded === 'http') return forwarded;
  return req.socket && req.socket.encrypted ? 'https' : 'http';
}

function host(req) {
  const forwarded = first(req.headers && req.headers['x-forwarded-host']);
  const value = forwarded || String((req.headers && req.headers.host) || '');
  return value.trim();
}

/** `https://host[:port]` — no trailing slash, so callers can append an absolute path. */
function origin(req) {
  const h = host(req);
  return h ? scheme(req) + '://' + h : '';
}

module.exports = { origin, scheme, host };
