#!/usr/bin/env node
'use strict';

/*
 * The game player must keep JavaScript (which games need) inside a deliberately small boundary.
 * This source gate catches the two regressions that turn an internal game surface into a production
 * browser escape: an exported launcher forwarding QA URLs, or a permanently inspectable WebView.
 */

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const read = (file) => fs.readFileSync(path.join(root, file), 'utf8');
const main = read('app/src/main/java/com/scenix/tv/MainActivity.kt');
const game = read('app/src/main/java/com/scenix/tv/GameActivity.kt');
const manifest = read('app/src/main/AndroidManifest.xml');

assert.match(main,
  /if \(\(applicationInfo\.flags and ApplicationInfo\.FLAG_DEBUGGABLE\) != 0\) intent\.getStringExtra\("qa_url"\)/,
  'the exported launcher must expose qa_url only from a debuggable build');
assert.match(game,
  /WebView\.setWebContentsDebuggingEnabled\(\s*\(applicationInfo\.flags and ApplicationInfo\.FLAG_DEBUGGABLE\) != 0,?\s*\)/s,
  'WebView debugging must follow the debuggable flag');
assert.doesNotMatch(game, /setWebContentsDebuggingEnabled\(true\)/,
  'production must never enable WebView debugging unconditionally');
for (const setting of [
  'settings.allowFileAccess = false',
  'settings.allowContentAccess = false',
  'settings.javaScriptCanOpenWindowsAutomatically = false',
  'settings.setSupportMultipleWindows(false)',
]) assert.ok(game.includes(setting), `game WebView must keep ${setting}`);
assert.match(manifest,
  /<activity\s+android:name="\.GameActivity"[\s\S]*?android:exported="false"/,
  'GameActivity must stay private to the app');

console.log('GAME WEBVIEW BOUNDARY STATIC GATE OK');
