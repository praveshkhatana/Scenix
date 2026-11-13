#!/usr/bin/env node
/* Keep the optimistic 9 Mbps seed behind the actual media-delivery boundary. */

import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const source = await readFile(
  new URL('../app/src/main/java/com/scenix/tv/PlayerActivity.kt', import.meta.url),
  'utf8',
);
const policy = await readFile(
  new URL('../app/src/main/java/com/scenix/tv/core/PlaybackOriginPolicy.kt', import.meta.url),
  'utf8',
);
const fixture = JSON.parse(await readFile(
  new URL('../app/src/main/assets/fixtures/home.json', import.meta.url),
  'utf8',
));

assert.match(
  policy,
  /LOCAL_LADDER_INITIAL_BITRATE_BPS = 9_000_000L/,
  'a verified local ladder must seed adaptive selection at 9 Mbps, not 9 kbps',
);
assert.match(
  policy,
  /if \(!hasLadder \|\| !kind\.equals\("hls", ignoreCase = true\)\) return false/,
  'only an HLS ladder may receive the LAN seed',
);
assert.match(
  policy,
  /sameOrigin\(control, media\) && isLocalHost\(media\.host\)/,
  'the policy must verify the delivery origin, not only the control endpoint',
);
assert.match(
  source,
  /PlaybackOriginPolicy\.usesLocalLadderSeed\(/,
  'PlayerActivity must apply the delivery-origin policy before building its meter',
);
assert.match(
  source,
  /startRungPending = useLocalLadderSeed/,
  'the temporary high-rung override must share the same delivery-origin policy',
);

const remoteFixtureHls = Object.values(fixture.play).filter((answer) =>
  answer && answer.kind === 'hls' && /^https:\/\//i.test(answer.url || ''),
);
assert.ok(remoteFixtureHls.length > 0, 'fixture must exercise a remote HLS delivery URL');
assert.ok(
  remoteFixtureHls.every((answer) => !answer.ladder),
  'remote fixture streams must not claim the hub-owned ladder contract',
);

console.log('PLAYER STARTUP ORIGIN POLICY GATE OK');
