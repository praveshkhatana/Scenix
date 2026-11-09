#!/usr/bin/env node
/*
 * Phone-pairing static gate (no network): the TV's Settings QR encodes the right origin,
 * standalone serves the bundled web UI, and the hub web already guards settings with the
 * household PIN. Every assertion is source-level so no daemon, port or browser is needed.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const read = (relative) => readFileSync(resolve(root, relative), 'utf8');

const settings = read('app/src/main/java/com/scenix/tv/SettingsActivity.kt');
assert.match(settings, /Connect from phone/, 'Settings must expose the Connect from phone card');
assert.match(settings, /private fun showPhonePairing\(\)/, 'Settings must paint the QR sheet');
assert.match(settings, /requireAdmin \{ showPhonePairing\(\) \}/, 'QR must stay behind the Settings PIN gate');
assert.match(settings, /PhonePairing\.renderQr\(url/, 'QR sheet must render via PhonePairing');
assert.match(settings, /PhonePairing\.fallbackText\(url\)/, 'QR sheet must show the typed fallback URL');
assert.match(settings, /phoneLayer/, 'Settings must track and trap the QR sheet');

const pairing = read('app/src/main/java/com/scenix/tv/core/PhonePairing.kt');
assert.match(pairing, /STANDALONE_PORT = 8787/, 'standalone pairing must use :8787');
assert.match(pairing, /fun normaliseOrigin\(/, 'hub origin normalisation must exist');
assert.match(pairing, /fun standaloneUrl\(/, 'standalone URL builder must exist');
assert.match(pairing, /fun pairingUrl\(standalone/, 'pairingUrl must branch hub/standalone');
assert.match(pairing, /QRCodeWriter/, 'QR must use ZXing QRCodeWriter (byte mode)');
assert.match(pairing, /ErrorCorrectionLevel\.M/, 'QR must use error-correction M');
assert.match(pairing, /fun lanIp\(\)/, 'LAN IP enumeration must exist');

const loopback = read('app/src/main/java/com/scenix/tv/core/LoopbackServer.kt');
assert.match(loopback, /protected fun serveWeb\(/, 'LoopbackServer must expose serveWeb()');
assert.match(loopback, /assets\.open\(assetPath\)/, 'serveWeb must read from assets/web/');
assert.match(loopback, /"web\/\$clean"/, 'serveWeb must prefix assets with web/');
assert.match(loopback, /MAX_WEB_BYTES/, 'serveWeb must bound file size');
assert.match(loopback, /webMime\(/, 'serveWeb must set Content-Type per extension');
assert.match(loopback, /path\.startsWith\("\/ui\/"\)/, 'loopback must understand the /ui/ prefix');

const local = read('app/src/main/java/com/scenix/tv/core/LocalServer.kt');
assert.match(local, /path == "\/" \|\| path == "\/index\.html" \|\| path\.startsWith\("\/ui\/"\)/,
  'LocalServer must route / and /ui/* to serveWeb');
assert.match(local, /serveWeb\(res, method, path\)/, 'LocalServer must call serveWeb for web paths');
assert.match(local, /path == "\/app\/v1\/healthz"/, 'LocalServer must expose /app/v1/healthz');
assert.match(local, /path == "\/app\/v1\/settings" /, 'LocalServer must keep /app/v1/settings');
assert.match(local, /path == "\/app\/v1\/settings\/unlock"/, 'LocalServer must keep unlock');
const localHub = read('app/src/main/java/com/scenix/tv/core/LocalHub.kt');
assert.match(localHub, /ADMIN_SESSION_HEADER|x-scenix-admin-session/i, 'LocalHub must keep admin session header');

const build = read('app/build.gradle');
assert.match(build, /com\.google\.zxing:core/, 'ZXing core must be a runtime dependency');

// Hub web: phone pairing needs nothing new — ensureSettingsAdmin already prompts for the PIN
// and stores the admin session in memory (api.js), so a phone that scans the hub URL is
// guarded exactly like any other browser.
const adminlock = read('hub/web/js/adminlock.js');
assert.match(adminlock, /ensureSettingsAdmin/, 'hub web must export ensureSettingsAdmin');
assert.match(adminlock, /api\.unlockSettings/, 'hub web must unlock via POST /app/v1/settings/unlock');

const webui = read('hub/core/webui.js');
assert.match(webui, /urlPath === '\/'/, 'hub webui must serve / as index.html');
assert.match(webui, /urlPath\.startsWith\('\/ui\/'\)/, 'hub webui must serve /ui/*');

console.log('PHONE PAIRING STATIC GATE OK');
