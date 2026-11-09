#!/usr/bin/env node

import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { captureTarget } = require('../design-system/tools/capture-target.cjs');

assert.deepEqual(
  captureTarget({ serial: 'emulator-5556' }),
  {
    serial: 'emulator-5556',
    applicationId: 'com.scenix.tv.debug',
    activityPackage: 'com.scenix.tv',
  },
);
assert.deepEqual(
  captureTarget({ serial: 'emulator-5556', applicationId: 'com.scenix.tv.qa' }),
  {
    serial: 'emulator-5556',
    applicationId: 'com.scenix.tv.qa',
    activityPackage: 'com.scenix.tv',
  },
);
assert.throws(
  () => captureTarget({ serial: '192.168.0.45:5555' }),
  /Android emulator/,
);
assert.throws(
  () => captureTarget({ serial: 'emulator-5556', applicationId: 'com.scenix.tv' }),
  /side-by-side debug or QA package/,
);

const qaManifest = await readFile(
  new URL('../app/src/qa/AndroidManifest.xml', import.meta.url),
  'utf8',
);
assert.match(
  qaManifest,
  /android:name="\.StudioActivity"[\s\S]*android:exported="true"/,
  'the QA artifact must contain the Studio activity its safe capture target names',
);
assert.match(
  qaManifest,
  /android:name="\.PlayerActivity"[\s\S]*android:exported="true"/,
  'the QA artifact must expose the real player needed by screen-level parity captures',
);

const captureRunner = await readFile(
  new URL('../design-system/tools/capture.mjs', import.meta.url),
  'utf8',
);
const studioActivity = await readFile(
  new URL('../app/src/main/java/com/scenix/tv/StudioActivity.kt', import.meta.url),
  'utf8',
);
assert.match(
  captureRunner,
  /const STUDIO = `\$\{APP_ID\}\/\$\{ACTIVITY_PACKAGE\}\.StudioActivity`/,
  'side-by-side package IDs must not be mistaken for the activity namespace',
);
assert.match(
  captureRunner,
  /const PLAYER = `\$\{APP_ID\}\/\$\{ACTIVITY_PACKAGE\}\.PlayerActivity`/,
  'screen captures must use the same source activity namespace',
);
assert.match(
  captureRunner,
  /const CAPTURE_CANVAS = '1280x720';/,
  'goldens must be captured on their fixed emulator canvas before normalization',
);
assert.match(
  studioActivity,
  /val zoom = if \(capture\) CAPTURE_ZOOM else app\.prefs\.uiZoom\(\)/,
  'capture mode must not inherit a tester’s saved accessibility zoom',
);
assert.match(
  studioActivity,
  /private const val CAPTURE_ZOOM = 1f/,
  'the golden scale must be explicit and reviewable',
);

console.log('STUDIO CAPTURE SAFETY GATE OK');
