#!/usr/bin/env node
/**
 * scripts/check-stall.mjs — is the freeze rule right?
 *
 * web/js/stall.js decides when a stream that stopped moving gets one silent reload and when the viewer is
 * asked instead. Those are timing rules, and waiting six minutes on a television to find out whether they are
 * right is no way to test them, so this drives the module with a clock it controls: a playhead that advances,
 * one that stops, a reload that works, a reload that does not, and the two-minute gap between attempts.
 *
 *   node scripts/check-stall.mjs
 */

import { readFile } from 'node:fs/promises';

/* The rule lives in one file that the browser also loads, so this reads that exact text rather than a copy
   that could drift. The module is deliberately free of imports, which is what lets it be evaluated here. */
const src = await readFile(new URL('../hub/web/js/stall.js', import.meta.url), 'utf8');
const { createWatchdog, prematureEnd, TRUNCATION_GRACE_MS } =
  await import('data:text/javascript,' + encodeURIComponent(src));

let clock = 0;
const now = () => clock;
const fails = [];

function check(name, got, want) {
  const ok = JSON.stringify(got) === JSON.stringify(want);
  console.log(`${ok ? 'ok  ' : 'FAIL'}  ${name.padEnd(52)} ${JSON.stringify(got)}`);
  if (!ok) fails.push(`${name}: wanted ${JSON.stringify(want)}, got ${JSON.stringify(got)}`);
}

/** A player we can push around: the playhead only moves when the scenario says so. */
function scenario() {
  const st = { positionMs: 0, playing: true, ended: false };
  const seen = { healing: [], healed: 0, stuck: [] };
  let reloads = 0;
  const wd = createWatchdog({
    state: () => st,
    now,
    heal: () => { reloads++; },
    onHealing: (d) => seen.healing.push(d.positionMs),
    onHealed: () => { seen.healed++; },
    describe: () => ({ label: 'Copy A', alternates: 2 }),
    onStuck: (d) => seen.stuck.push(d),
  });
  return { st, seen, wd, reloads: () => reloads, advance: (ms) => { st.positionMs += ms; } };
}

// A film that is simply playing is never interrupted.
{
  clock = 0;
  const s = scenario();
  for (let i = 0; i < 30; i++) { clock += 1000; s.advance(1000); s.wd.tick(); }
  check('playing film gets no reload', [s.reloads(), s.seen.healing.length], [0, 0]);
}

// Pausing is not a stall, however long the pause.
{
  clock = 0;
  const s = scenario();
  s.st.playing = false;
  for (let i = 0; i < 60; i++) { clock += 1000; s.wd.tick(); }
  check('a held pause gets no reload', [s.reloads(), s.seen.stuck.length], [0, 0]);
}

// Under six seconds of nothing, the rule waits; at six it spends exactly one silent reload.
{
  clock = 0;
  const s = scenario();
  s.advance(5000);
  // The first look at the playhead is movement, so the silence is measured from that moment on.
  clock += 5000; s.wd.tick();
  check('five seconds of silence is not yet a stall', s.reloads(), 0);
  clock += 6000; s.wd.tick();
  check('six seconds of silence reopens the copy', [s.reloads(), s.seen.healing], [1, [5000]]);
  // And it does not reload again on every following tick while it waits to see whether that worked.
  clock += 3000; s.wd.tick();
  clock += 3000; s.wd.tick();
  check('one reload, then patience', s.reloads(), 1);
}

// The playhead moving proves the reload worked, once and only once.
{
  clock = 0;
  const s = scenario();
  s.advance(10000);
  for (let i = 0; i < 8; i++) { clock += 1000; s.wd.tick(); }
  check('reload spent before asking', [s.reloads(), s.seen.stuck.length], [1, 0]);
  s.advance(700);
  s.wd.tick();
  check('the recovery is reported once', s.seen.healed, 1);
  s.advance(700);
  s.wd.tick();
  check('and not again', s.seen.healed, 1);
}

// A reload that produced nothing: the second freeze is the viewer's decision, with what else exists named.
{
  clock = 0;
  const s = scenario();
  s.advance(20000);
  for (let i = 0; i < 8; i++) { clock += 1000; s.wd.tick(); }
  check('first freeze handled silently', [s.reloads(), s.seen.stuck.length], [1, 0]);
  for (let i = 0; i < 30; i++) { clock += 1000; s.wd.tick(); }
  check('second freeze asks the viewer', s.seen.stuck, [{ label: 'Copy A', alternates: 2 }]);
  // Asking twice in a row would be nagging.
  for (let i = 0; i < 60; i++) { clock += 1000; s.wd.tick(); }
  check('the question is asked once per copy', s.seen.stuck.length, 1);
}

// Two minutes of grace before another reload is spent on a slow line.
{
  clock = 0;
  const s = scenario();
  s.advance(30000);
  for (let i = 0; i < 8; i++) { clock += 1000; s.wd.tick(); }        // reload #1
  s.advance(500); for (let i = 0; i < 70; i++) { clock += 1000; s.advance(1000); s.wd.tick(); } // 70s of slow
  s.advance(0);
  for (let i = 0; i < 10; i++) { clock += 1000; s.wd.tick(); }       // now frozen again
  check('a fresh freeze inside the cooldown asks rather than reloads', [s.reloads(), s.seen.stuck.length], [1, 1]);
}

// The viewer chose "keep watching": that copy earns another reload after the cooldown.
{
  clock = 0;
  const s = scenario();
  s.advance(40000);
  for (let i = 0; i < 8; i++) { clock += 1000; s.wd.tick(); }
  s.wd.forgive();
  clock += 130000;
  for (let i = 0; i < 10; i++) { clock += 1000; s.wd.tick(); }
  check('staying with a stalled copy spends a second reload', s.reloads(), 2);
}

// A different copy resets everything: it has not stalled yet.
{
  clock = 0;
  const s = scenario();
  s.advance(50000);
  for (let i = 0; i < 20; i++) { clock += 1000; s.wd.tick(); }       // reload + question spent on copy A
  s.wd.reset();
  s.advance(60000);
  for (let i = 0; i < 10; i++) { clock += 1000; s.advance(1000); s.wd.tick(); }
  check('a new copy starts clean', s.seen.stuck.length, 1);
  s.advance(0);
  for (let i = 0; i < 8; i++) { clock += 1000; s.wd.tick(); }
  check('and gets its own reload', s.reloads(), 2);
}

/* ---- an ending that arrived too early is a stall, not an ending ---- */

check('a copy that stops long before its own length is cut off, not finished', () =>
  assert(prematureEnd({ positionMs: 87000, durationMs: 8880000 }) === true, 'truncation missed'));
check('a copy that reaches its real length is allowed to end', () =>
  assert(prematureEnd({ positionMs: 8879000, durationMs: 8880000 }) === false, 'real ending hijacked'));
check('the last few seconds still count as finishing', () =>
  assert(prematureEnd({ positionMs: 8880000 - TRUNCATION_GRACE_MS + 1000, durationMs: 8880000 }) === false,
    'grace ignored'));
check('a live channel with no measured length is believed', () =>
  assert(prematureEnd({ positionMs: 42000, durationMs: 0 }) === false, 'invented a stall out of live tv'));
check('an unprobed file is not called truncated', () =>
  assert(prematureEnd({ positionMs: 5000, durationMs: null }) === false, 'missing length treated as length'));

/* ---- the same patience for both routes ---- */

check('an early ending spends the silent reload', () => {
  const s = scenario([[1, [60000]]]);
  assert(s.w.force() === 'heal', 'early end was not healed');
  assert(s.calls.heal === 1, 'heal not called');
});
check('a second early ending asks instead of reloading again', () => {
  const s = scenario([[1, [60000]], [2, [70000]]]);
  s.w.force();
  assert(s.w.force() === 'stuck', 'asked the viewer too late or not at all');
  assert(s.calls.heal === 1, 'spent more than one reload on one copy');
});
check('a freeze found by the clock cannot buy a second reload via an early ending', () => {
  const s = scenario([[1, [60000]], [2, [66000]], [2, [72000]]]);
  s.tick(2);                                  // clock noticed first, reload spent
  assert(s.w.force() === null || s.w.force() === 'stuck', 'policy diverged between the two routes');
  assert(s.calls.heal === 1, 'both routes reloaded: ' + s.calls.heal);
});
check('a reload already in flight is not stacked by an early ending', () => {
  const s = scenario([[1, [60000]]]);
  s.tick(2);                                  // heals, pending stays true (position never moves)
  assert(s.w.force() === null, 'stacked a reload on top of one still running');
  assert(s.calls.heal === 1, 'heal count ' + s.calls.heal);
});

console.log(fails.length ? `\n${fails.length} check(s) failed:\n  ${fails.join('\n  ')}` : '\nall stall checks passed');
process.exit(fails.length ? 1 : 0);
