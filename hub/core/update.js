'use strict';
/*
 * core/update.js — the hub keeps itself current, so updating the app updates the hub too.
 *
 * The television already pulls its own new builds and installs them with a person pressing OK
 * (the app's core/Updates.kt, from GitHub Releases). The hub was the one piece of the household
 * that only ever moved when someone walked to its machine and typed `git pull` by hand — which is
 * how a house ends up running a new app against an old backend. The hub's checkout IS the repo,
 * so the release mechanism is a `git pull --ff-only`, and this file is the thin honest layer
 * around it.
 *
 * The rules are deliberately conservative:
 *   · A hub that is not a git checkout (copied files, an unzipped build) says so and changes
 *     nothing — there is no mystery updater for a folder git has never seen.
 *   · A checkout with someone's own uncommitted edits is never pulled over: the blocker is
 *     reported instead of decided for them. Same for a branch that cannot fast-forward — a merge
 *     is a human decision.
 *   · An update restarts the process: a fresh detached child is spawned with the same cwd and
 *     environment, and this one exits. Clients survive the second-long gap the way they survive
 *     any restart — the TV's HubWatch re-probes within 30 s, a browser reloads.
 *
 * The "are we behind?" fetch is throttled to one per minute, so a TV polling the status every
 * 30 s never hammers GitHub. Beyond the on-demand route, start() keeps the household current on
 * its own: one pass at boot, then every six hours.
 */
const { execFile, spawn } = require('child_process');
const path = require('path');

const ROOT = path.join(__dirname, '..'); // git resolves the whole checkout from inside it
const FETCH_MIN_MS = 60 * 1000;
const AUTO_MS = 6 * 60 * 60 * 1000;

/** Run git in the hub's checkout. Failures answer as data, never as exceptions. */
function git() {
  const args = ['-C', ROOT].concat(Array.from(arguments));
  return new Promise((res) => {
    execFile('git', args, {
      timeout: 45000,
      // A private origin on https would otherwise wait for a password prompt forever.
      env: Object.assign({}, process.env, { GIT_TERMINAL_PROMPT: '0' }),
    }, (err, out, errOut) => res({
      ok: !err,
      out: String(out || '').trim(),
      err: String((err && (errOut || err.message)) || '').trim().split('\n').pop(),
    }));
  });
}

let lastFetch = 0; // when the origin was last asked
let state = null;  // the last full picture, with its age
let busy = null;   // the one in-flight update, so two clients pressing at once pull once

/**
 * The truth about this checkout: what commit it runs, whether the origin has moved ahead, and —
 * when it has — whether a self-pull is allowed. [force] re-asks the origin; without it a fetch
 * younger than a minute is reused.
 */
async function status(force) {
  const now = Date.now();
  if (state && !force && now - state.measuredAt < FETCH_MIN_MS) return state;

  const inside = await git('rev-parse', '--is-inside-work-tree');
  if (!inside.ok || inside.out !== 'true') {
    state = {
      measuredAt: now, git: false, behind: 0, canUpdate: false, commit: null,
      blocked: 'this hub is not a git checkout, so there is nothing it can pull',
    };
    return state;
  }

  const branch = await git('rev-parse', '--abbrev-ref', 'HEAD');
  const head = await git('rev-parse', '--short', 'HEAD');
  const headDate = await git('log', '-1', '--format=%ct');
  const dirty = await git('status', '--porcelain', '--untracked-files=no');
  const s = {
    measuredAt: now,
    git: true,
    branch: branch.out,
    commit: head.out,
    commitDate: (Number(headDate.out) * 1000) || null,
    dirty: !!dirty.out,
    behind: 0,
    remoteCommit: null,
    blocked: null,
  };

  if (s.branch === 'HEAD') {
    s.blocked = 'this hub is on a detached commit rather than a branch';
  } else if (s.dirty) {
    s.blocked = 'this hub checkout carries uncommitted edits, and those are never pulled over';
  } else {
    if (force || now - lastFetch > FETCH_MIN_MS) {
      const f = await git('fetch', '--quiet', '--prune');
      if (!f.ok) s.fetchError = f.err || 'git fetch did not run';
      lastFetch = now;
    }
    const up = await git('rev-parse', '--abbrev-ref', '@{upstream}');
    if (!up.ok) {
      s.blocked = 'this branch has no upstream branch to pull from';
    } else {
      const behind = await git('rev-list', '--count', 'HEAD..@{upstream}');
      s.behind = Number(behind.out) || 0;
      const rc = await git('rev-parse', '--short', '@{upstream}');
      if (rc.ok) s.remoteCommit = rc.out;
      if (s.behind > 0) {
        // Behind is only actionable when this commit is an ancestor of the origin's: a checkout
        // with commits of its own needs a merge, and merges belong to a human.
        const ff = await git('merge-base', '--is-ancestor', 'HEAD', '@{upstream}');
        if (!ff.ok) s.blocked = 'this hub carries commits of its own, so a plain pull would be a merge';
      }
    }
  }
  s.canUpdate = s.git === true && s.behind > 0 && !s.blocked;
  state = s;
  return s;
}

/**
 * Bring this checkout up to its upstream: fetch, refuse anything that is not a clean
 * fast-forward, then pull. The caller restarts the process on success — the new code needs a
 * fresh process, not a hot swap.
 */
async function update() {
  if (busy) return busy;
  busy = (async () => {
    const s = await status(true);
    if (!s.git) return { updated: false, reason: s.blocked };
    if (s.behind === 0) return { updated: false, upToDate: true, commit: s.commit };
    if (s.blocked) return { updated: false, reason: s.blocked, behind: s.behind };
    const pull = await git('pull', '--ff-only');
    if (!pull.ok) return { updated: false, reason: 'git pull refused: ' + (pull.err || 'no detail') };
    const head = await git('rev-parse', '--short', 'HEAD');
    return { updated: true, from: s.commit, to: head.out, restarts: true };
  })().finally(() => { busy = null; });
  return busy;
}

/**
 * Start the next build as a fresh detached process (same cwd, same environment, same open log —
 * inherited descriptors keep writing to wherever the hub's output was going) and let this one exit.
 */
function selfRestart() {
  try {
    const child = spawn(process.execPath, process.argv.slice(1), {
      cwd: process.cwd(), env: process.env, detached: true, stdio: 'inherit',
    });
    child.unref();
  } catch (e) {
    /* If the spawn failed, exiting would take the only hub down for nothing. */
    return false;
  }
  setTimeout(() => process.exit(0), 150);
  return true;
}

/**
 * Keep the household current without anyone asking: one pass once the hub has warmed, then on a
 * slow timer. A hub that is behind but blocked says so once per pass in the log — the owner's
 * cue — and never pulls over their own work.
 */
function start(log) {
  const pass = async () => {
    try {
      const s = await status(true);
      if (!s.git) { log('HUB UPDATE', s.blocked); return; }
      if (s.behind === 0) return;
      if (s.blocked) {
        log('HUB UPDATE BEHIND', s.behind + ' commit(s) behind:', s.blocked);
        return;
      }
      const r = await update();
      if (r.updated) {
        log('HUB SELF UPDATE', r.from + ' -> ' + r.to, 'restarting the hub process');
        selfRestart();
      } else {
        log('HUB UPDATE FAIL', r.reason || 'no detail');
      }
    } catch (e) {
      log('HUB UPDATE FAIL', e.message);
    }
  };
  setTimeout(pass, 20000); // let catalogues warm first; a restart mid-boot is just rude
  const timer = setInterval(pass, AUTO_MS);
  if (timer.unref) timer.unref();
}

module.exports = { status, update, selfRestart, start };
