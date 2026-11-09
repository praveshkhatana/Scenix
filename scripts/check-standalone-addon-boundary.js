#!/usr/bin/env node
'use strict';

/*
 * Standalone runs inside the APK, so its add-on transport cannot be exercised by Node without an
 * Android runtime. This source-level gate protects the security boundary that is easy to regress:
 * a validated DNS answer must feed the actual socket connect, and public API shapes must not leak
 * configured manifest URLs. `AddonClientPinnedTransportTest` separately exercises the pinned
 * HTTP reader offline; this check is deliberately fast enough to run with every repository gate.
 */

const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const read = (relative) => fs.readFileSync(path.join(root, relative), 'utf8');
const requireMatch = (source, pattern, note) => {
  if (!pattern.test(source)) throw new Error(`standalone add-on boundary: ${note}`);
};
const requireAbsent = (source, pattern, note) => {
  if (pattern.test(source)) throw new Error(`standalone add-on boundary: ${note}`);
};

const client = read('app/src/main/java/com/scenix/tv/core/AddonClient.kt');
const localHub = read('app/src/main/java/com/scenix/tv/core/LocalHub.kt');
const localServer = read('app/src/main/java/com/scenix/tv/core/LocalServer.kt');

requireMatch(client, /data class PublicEndpoint\(val uri: URI, val addresses: List<InetAddress>\)/,
  'the validated endpoint must retain its DNS answers');
requireMatch(client, /val addresses = resolvePublicAddresses\(uri\) \?: return null[\s\S]*PublicEndpoint\(uri, addresses\)/,
  'endpoint construction must bind a URI to its validated addresses');
requireMatch(client, /for \(address in endpoint\.addresses\)[\s\S]*fetchPinnedAddress\(endpoint\.uri, address, timeout\)/,
  'the fetch path must iterate the validated address list');
requireMatch(client, /rawSocket\.connect\(InetSocketAddress\(address, port\), timeout\)/,
  'the transport must connect directly to a checked address');
requireMatch(client, /createSocket\(rawSocket, host, port, true\)[\s\S]*getDefaultHostnameVerifier\(\)\.verify\(host, tlsSocket\.session\)/,
  'HTTPS must retain SNI/certificate hostname verification over the pinned socket');
requireAbsent(client, /import java\.net\.HttpURLConnection|\.openConnection\(/,
  'URLConnection would re-resolve the hostname after validation');
requireMatch(client, /MAX_REDIRECTS = 3[\s\S]*MAX_RESPONSE_BYTES = 2L \* 1024L \* 1024L[\s\S]*MAX_HEADER_BYTES = 16 \* 1024/,
  'redirect, response and header limits must stay explicit');
requireMatch(client, /private fun publicEndpoint\(raw: String\): PublicEndpoint\?/,
  'all cache and fetch callers must use the public-endpoint gate');

requireMatch(localHub, /out\.remove\("addons"\)/,
  'standalone settings responses must omit stored manifest addresses');
requireAbsent(localHub, /\.put\("url", spec\.manifestUrl\)/,
  'standalone add-on summaries must not expose manifest URLs');
requireMatch(localHub, /\.put\("provenance", "manual"\)/,
  'standalone add-on summaries must label their unreviewed origin');
requireMatch(localHub, /private fun manifestAddress\(url: String\): String\? = AddonClient\.publicManifestUrl\(url\)/,
  'new source persistence must pass the public endpoint gate');
requireMatch(localHub, /private fun writeAddonEntries\(entries: JSONArray\)/,
  'source persistence must be isolated from generic settings writes');
requireAbsent(localServer, /\.put\("file", hub\.settingsPath\(\)\)/,
  'standalone add-on responses must not expose private file paths');

process.stdout.write('STANDALONE ADD-ON BOUNDARY STATIC GATE OK\n');
