#!/usr/bin/env node
'use strict';
/*
 * check-origin.mjs — the scheme/host a client reached, not the loopback address we listen on.
 *
 * A TLS terminator is the normal way to put this hub on https; without this check a proxied stream URL
 * would quietly be `http://…` and the browser would block it as mixed content. Run it after changing
 * core/origin.js or the play path.
 */
import { createRequire } from 'module';
const require = createRequire(import.meta.url);
const { origin, scheme, host } = require('../hub/core/origin.js');

let failed = 0;
function ok(name, got, want) {
  const pass = got === want;
  if (!pass) failed++;
  console.log(`${pass ? 'ok  ' : 'FAIL'} ${name}${pass ? '' : `\n     got  ${JSON.stringify(got)}\n     want ${JSON.stringify(want)}`}`);
}

const req = (headers, encrypted = false) => ({
  headers,
  socket: { encrypted },
});

ok('plain HTTP host',
  origin(req({ host: '192.168.0.24:8788' })),
  'http://192.168.0.24:8788');

ok('direct TLS socket',
  origin(req({ host: 'tv.example' }, true)),
  'https://tv.example');

ok('proxy proto and host',
  origin(req({ host: '127.0.0.1:8788', 'x-forwarded-proto': 'https, http', 'x-forwarded-host': 'tv.example:8788' })),
  'https://tv.example:8788');

ok('proxy host without proto',
  origin(req({ host: '127.0.0.1:8788', 'x-forwarded-host': 'tv.example' })),
  'http://tv.example');

ok('first forwarded proto wins',
  scheme(req({ 'x-forwarded-proto': 'HTTPS,http' })),
  'https');

ok('no host is empty, never a bare scheme',
  origin(req({})),
  '');

ok('host header fallback',
  host(req({ host: 'hub.local:8788' })),
  'hub.local:8788');

if (failed) {
  console.error(`\n${failed} origin check(s) failed`);
  process.exit(1);
}
console.log('\nall origin checks passed');
