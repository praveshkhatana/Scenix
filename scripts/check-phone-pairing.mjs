#!/usr/bin/env node
/*
 * Phone-pairing static gate (no network): Settings keeps a large QR rail beside the safe/guarded
 * groups, the QR is drawn by the app's own encoder, and the phone remote is a token-only channel
 * served by both the hub and a standalone TV. Every assertion is source-level, so no daemon, port
 * or browser is needed.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const read = (relative) => readFileSync(resolve(root, relative), 'utf8');

const settings = read('app/src/main/java/com/scenix/tv/SettingsActivity.kt');
assert.match(settings, /CONNECT YOUR PHONE/, 'Settings must name the pinned phone rail');
assert.match(settings, /private fun buildPhoneRail\(\)/, 'Settings must build the persistent QR rail');
assert.match(settings, /private fun ensureRemoteSession\(\)/, 'Settings must mint a remote session');
assert.match(settings, /PhonePairing\.renderQr\(url/, 'the rail must render the remote URL as a QR');
assert.match(settings, /"\/remote\?t=" \+ remoteToken/, 'the QR must carry the session token');
assert.match(settings, /api\.remoteRegister\(remoteToken, remoteCode\)/, 'the TV must register its token');
assert.match(settings, /api\.remoteEvents\(token, remoteLast\)/, 'the TV must poll for phone events');
assert.match(settings, /api\.remoteClose\(token\)/, 'closing Settings must end the phone session');
assert.match(settings, /injectRemoteEvent\(event\)/, 'phone events must reach the TV input path');
assert.match(settings, /dispatchKeyEvent\(KeyEvent\(now, now, KeyEvent\.ACTION_DOWN/, 'keys go via dispatchKeyEvent');
assert.match(settings, /SearchActivity\.EXTRA_QUERY/, 'typed text must open search with the query');
assert.doesNotMatch(settings, /requireAdmin \{ showPhonePairing\(\) \}/,
  'the QR rail must be visible without the household PIN');
assert.doesNotMatch(settings, /phoneLayer/, 'the old modal QR layer must be gone');
assert.match(settings, /private fun paintSafeGroups\(/, 'safe groups must be painted without a gate');
assert.match(settings, /private fun paintGuardedGroups\(/, 'guarded groups must sit behind one PIN row');
assert.match(settings, /addRow\("Theme"/, 'Theme must stay in the safe Appearance group');
assert.doesNotMatch(settings, /requireAdmin \{ pickTheme\(\) \}/, 'Appearance must not ask for the PIN');

const pairing = read('app/src/main/java/com/scenix/tv/core/PhonePairing.kt');
assert.match(pairing, /STANDALONE_PORT = 8787/, 'standalone pairing must use :8787');
assert.match(pairing, /fun normaliseOrigin\(/, 'hub origin normalisation must exist');
assert.match(pairing, /fun standaloneUrl\(/, 'standalone URL builder must exist');
assert.match(pairing, /fun pairingUrl\(standalone/, 'pairingUrl must branch hub/standalone');
assert.match(pairing, /fun settingsUrl\(/, 'the fallback link must deep-link web Settings');
assert.match(pairing, /QrCode\.encode\(/, 'QR must use the in-house encoder');
assert.match(pairing, /fun lanIp\(\)/, 'LAN IP enumeration must exist');
assert.doesNotMatch(pairing, /QRCodeWriter|ErrorCorrectionLevel|zxing/i, 'no barcode library may return');

const qr = read('app/src/main/java/com/scenix/tv/core/QrCode.kt');
assert.match(qr, /class QrCode/, 'the pure-Kotlin encoder must exist');
assert.match(qr, /ECC_PER_BLOCK/, 'the encoder must carry Reed-Solomon block tables');

const remoteSession = read('app/src/main/java/com/scenix/tv/core/RemoteSession.kt');
assert.match(remoteSession, /\/app\/v1\/remote\/register/, 'standalone must document register');
assert.match(remoteSession, /fun events\(token: String, after: Int\)/, 'standalone must queue events');
assert.match(remoteSession, /fun send\(body: JSONObject\)/, 'standalone must accept phone sends');
assert.match(remoteSession, /TTL_MS = 15 \* 60 \* 1000L/, 'remote sessions must expire');

const loopback = read('app/src/main/java/com/scenix/tv/core/LoopbackServer.kt');
assert.match(loopback, /protected fun serveWeb\(/, 'LoopbackServer must expose serveWeb()');
assert.match(loopback, /assets\.open\(assetPath\)/, 'serveWeb must read from assets/web/');
assert.match(loopback, /"web\/\$clean"/, 'serveWeb must prefix assets with web/');
assert.match(loopback, /path == "\/remote" \|\| path == "\/remote\/"/, 'loopback must serve /remote');
assert.match(loopback, /MAX_WEB_BYTES/, 'serveWeb must bound file size');
assert.match(loopback, /webMime\(/, 'serveWeb must set Content-Type per extension');
assert.match(loopback, /path\.startsWith\("\/ui\/"\)/, 'loopback must understand the /ui/ prefix');

const local = read('app/src/main/java/com/scenix/tv/core/LocalServer.kt');
assert.match(local, /path == "\/remote" \|\| path == "\/remote\/"/, 'LocalServer must serve /remote');
assert.match(local, /serveWeb\(res, method, path\)/, 'LocalServer must call serveWeb for web paths');
assert.match(local, /path == "\/app\/v1\/remote\/register"/, 'LocalServer must route remote register');
assert.match(local, /path == "\/app\/v1\/remote\/events"/, 'LocalServer must route remote events');
assert.match(local, /path == "\/app\/v1\/remote\/send"/, 'LocalServer must route remote send');
assert.match(local, /path == "\/app\/v1\/remote\/state"/, 'LocalServer must route remote state');
assert.match(local, /path == "\/app\/v1\/remote\/close"/, 'LocalServer must route remote close');
const localHub = read('app/src/main/java/com/scenix/tv/core/LocalHub.kt');
assert.match(localHub, /ADMIN_SESSION_HEADER|x-scenix-admin-session/i, 'LocalHub must keep admin session header');

const build = read('app/build.gradle');
assert.doesNotMatch(build, /com\.google\.zxing:core/, 'ZXing must not be a dependency');
assert.match(build, /core\/QrCode\.kt/, 'the build must point at the in-house QR encoder');

const hubRemote = read('hub/core/remote.js');
assert.match(hubRemote, /\/app\/v1\/remote\/register/, 'hub must document register');
assert.match(hubRemote, /\/app\/v1\/remote\/events/, 'hub must document events');
assert.match(hubRemote, /\/app\/v1\/remote\/send/, 'hub must document send');
assert.match(hubRemote, /MAX_EVENTS/, 'hub must bound the event queue');
assert.match(hubRemote, /TTL_MS/, 'hub sessions must expire');

const hubIndex = read('hub/index.js');
assert.match(hubIndex, /require\('\.\/core\/remote'\)/, 'hub must load the remote channel');
assert.match(hubIndex, /phoneRemote/, 'remote routes must bypass the hub credential gate');
assert.match(hubIndex, /remote\.handle\(req, res, path/, 'hub must dispatch remote routes');

const remotePage = read('hub/web/remote.html');
assert.match(remotePage, /app\/v1\/remote\/send/, 'the phone page must post to the remote send route');
assert.match(remotePage, /data-key="UP"/, 'the phone page must have a D-pad');
assert.match(remotePage, /type: 'text'/, 'the phone page must send typed text');
assert.match(remotePage, /Open full Settings on this phone/, 'the phone page must link web Settings');

const adminlock = read('hub/web/js/adminlock.js');
assert.match(adminlock, /ensureSettingsAdmin/, 'hub web must export ensureSettingsAdmin');
assert.match(adminlock, /api\.unlockSettings/, 'hub web must unlock via POST /app/v1/settings/unlock');

const webui = read('hub/core/webui.js');
assert.match(webui, /urlPath === '\/'/, 'hub webui must serve / as index.html');
assert.match(webui, /urlPath\.startsWith\('\/ui\/'\)/, 'hub webui must serve /ui/*');
assert.match(webui, /remote\.html/, 'hub webui must serve the phone remote page');

// The phone Settings screen must be its own scroller: #stage is fixed with overflow hidden, so a
// `visible` screen leaves no container for vertical touch scrolling.
const screensCss = read('hub/web/css/screens.css');
assert.match(screensCss, /\.screen\.settings \{[\s\S]*?overflow-y: auto/,
  'the phone Settings screen must scroll vertically');
assert.match(screensCss, /-webkit-overflow-scrolling: touch/, 'phone scrolling must keep momentum');

console.log('PHONE PAIRING STATIC GATE OK');
