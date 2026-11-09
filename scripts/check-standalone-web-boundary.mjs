#!/usr/bin/env node
/*
 * The Android APK is a native standalone client/backend. Standalone now hosts the bundled
 * web Settings UI on :8787 for phone pairing (settings.js), so the boundary is: static
 * web serving comes from assets/web via LocalServer+LoopbackServer.serveWeb(); the fixture
 * (FixtureServer) stays API-only and unknown paths still answer honestly via notFound.
 * A silent static revival that returns a missing page would make the TV look paired while
 * settings are unavailable, so the bundled serving must be intentional and tested.
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const read = (relative) => readFileSync(resolve(root, relative), 'utf8');
const requireMatch = (source, pattern, note) => {
  if (!pattern.test(source)) throw new Error(`standalone web boundary: ${note}`);
};
const requireAbsent = (source, pattern, note) => {
  if (pattern.test(source)) throw new Error(`standalone web boundary: ${note}`);
};

const loopback = read('app/src/main/java/com/scenix/tv/core/LoopbackServer.kt');
const local = read('app/src/main/java/com/scenix/tv/core/LocalServer.kt');
const fixture = read('app/src/main/java/com/scenix/tv/core/FixtureServer.kt');
const browse = read('app/src/main/java/com/scenix/tv/BrowseActivity.kt');
const readme = read('README.md');

requireMatch(loopback, /protected fun serveWeb\(/,
  'standalone web serving must be an explicit serveWeb(), not a silent fallthrough');
requireMatch(loopback, /assets\.open\(assetPath\)/,
  'standalone must serve the bundled web snapshot from assets/web');
requireMatch(loopback, /"web\/\$clean"/,
  'standalone must read web files via assets/web prefix');
requireMatch(loopback, /protected fun notFound\(res: Res, path: String\)/,
  'the local API server must answer unknown paths explicitly');
requireMatch(local, /serveWeb\(res, method, path\)/,
  'LocalServer must route / and /ui/* through serveWeb() for phone pairing');
requireMatch(local, /path == "\/" \|\| path == "\/index\.html" \|\| path\.startsWith\("\/ui\/"\)/,
  'LocalServer must serve / , /index.html and /ui/* as web paths');
requireMatch(local, /path == "\/app\/v1\/healthz"/,
  'standalone must expose /app/v1/healthz for the settings flow');
requireMatch(fixture, /else -> notFound\(res, path\)/,
  'fixture mode must share the API-only boundary');
requireAbsent(fixture, /serveWeb\(/,
  'fixture preview must not serve the bundled web UI');
requireAbsent(browse, /LocalServer\.webBase\(this\)/,
  'BrowseActivity must not send owners to a TV address; Settings phone pairing owns the QR');
requireMatch(browse, /Standalone does not host browser management/,
  'BrowseActivity must still explain the management boundary honestly');
requireMatch(readme, /Connect from phone|phone-pairing/i,
  'README must mention phone pairing now that standalone serves the web Settings');

process.stdout.write('STANDALONE WEB BOUNDARY STATIC GATE OK\n');
