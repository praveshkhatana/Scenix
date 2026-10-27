/**
 * js/stall.js — noticing that a moving picture stopped moving.
 *
 * A stream that freezes partway through a film is a different emergency from one that never started. The
 * peers may have gone quiet for a moment, and the hub can re-open the very same copy at the point already
 * reached, which is worth trying once without interrupting anyone. This module decides when that moment has
 * come; it owns no media element and knows nothing about hubs, so the same rule covers the shell's player and
 * a browser's, and the timing can be argued about in a test rather than on a television.
 *
 * The rule, from design/ui-tokens.md section D: six seconds of nothing while the player still says it is
 * playing gets one silent reload at the current position. If it happens again the viewer decides, because a
 * still frame with no explanation is how people conclude an app is broken rather than a connection slow. A
 * two-minute gap between attempts keeps a genuinely slow line from being reloaded over and over.
 */

export const STALL_MS = 6000;
export const HEAL_COOLDOWN_MS = 120000;
export const HEALS_PER_COPY = 1;

/**
 * @param {object} opts
 * @param {() => object} opts.state       current {positionMs, playing, ended} as the backend reports it
 * @param {() => void} opts.heal          re-open the current copy at the position already reached
 * @param {(d: {positionMs: number}) => void} [opts.onHealing] a reload was just spent
 * @param {() => void} [opts.onHealed]    the playhead moved again, so the reload worked
 * @param {() => object} [opts.describe]  what to tell the viewer when the question has to be asked
 * @param {(d: object) => void} [opts.onStuck] the copy froze again; the viewer chooses now
 * @param {number} [opts.stallMs]
 * @param {number} [opts.cooldownMs]
 * @param {number} [opts.healsPerCopy]
 * @param {() => number} [opts.now]       clock, so a test can move time instead of waiting for it
 */
export function createWatchdog(opts) {
  const {
    state, heal, onHealing, onHealed, onStuck, describe,
    stallMs = STALL_MS, cooldownMs = HEAL_COOLDOWN_MS, healsPerCopy = HEALS_PER_COPY,
    now = () => Date.now(),
  } = opts || {};

  let lastPos = -1;
  let movedAt = now();
  let heals = 0;
  // The gap is measured from the last reload, and none has happened yet - starting it at zero would put every
  // freeze inside the first two minutes of a film beyond the cooldown, which is exactly when they happen.
  let lastHealAt = Number.NEGATIVE_INFINITY;
  let pending = false;   // a reload is in flight and we have not seen proof it worked
  let asked = false;     // the viewer has already been asked about this copy

  /** A different copy: the reload has not been spent on it and no question has been asked about it. */
  function reset() {
    lastPos = -1;
    movedAt = now();
    heals = 0;
    lastHealAt = Number.NEGATIVE_INFINITY;
    pending = false;
    asked = false;
  }

  /** The viewer chose to stay with a stalled copy, so it earns another reload later. */
  function forgive() {
    heals = 0;
    asked = false;
    pending = false;
    movedAt = now();
  }

  /** Call on a timer. Pausing is never a stall, and neither is a stream that has finished. */
  function tick() {
    const st = (typeof state === 'function' && state()) || {};
    const pos = Number(st.positionMs) || 0;
    if (pos !== lastPos) {
      lastPos = pos;
      movedAt = now();
      if (pending) {
        pending = false;
        if (onHealed) onHealed();
      }
    }
    if (!st.playing || st.ended) return null;
    if (now() - movedAt < stallMs) return null;
    return decide();
  }

  /**
   * The whole policy, in one place: spend the reload if we have one left and enough time has passed,
   * otherwise stop deciding for the viewer and ask them. Both routes here go through this function, so
   * a copy cannot buy itself a second silent reload by failing in a different way.
   */
  function decide() {
    if (heals < healsPerCopy && now() - lastHealAt > cooldownMs) {
      heals++;
      lastHealAt = now();
      // The clock restarts for this attempt: the next question is asked only if this one produced nothing.
      movedAt = now();
      pending = true;
      if (onHealing) onHealing({ positionMs: lastPos });
      if (heal) heal();
      return 'heal';
    }

    if (!asked) {
      asked = true;
      if (onStuck) onStuck((typeof describe === 'function' && describe()) || {});
      return 'stuck';
    }
    return null;
  }

  /**
   * A stall announced by something other than the clock - today that means a stream that reported its own
   * end too early. Same policy as a timed-out freeze, and it obeys the same cooldown, so a hub that keeps
   * handing back dead copies ends up asking the viewer instead of reloading forever.
   */
  function force() {
    if (pending) return null;
    movedAt = now();
    return decide();
  }

  return { tick, reset, forgive, force, stats: () => ({ heals, asked, pending }) };
}

/**
 * How far short of its own length a copy has to finish before we call it cut off rather than finished.
 * Generous on purpose: a title that really is over usually reports within a second or two of its length,
 * while a stream whose source gave up is typically minutes short of it.
 */
export const TRUNCATION_GRACE_MS = 20000;

/**
 * "The film ended early" is not a normal way for a stream to fail. When the engine stops answering, the hub
 * closes the connection after its own patience runs out, and a player reading a closed connection reports a
 * clean end of media. So an ending is only believable near the length we already measured for this copy:
 * anywhere else it is a stall, and the viewer should not be dropped back to the row they came from.
 *
 * With no known length (a live channel, a file we never probed) there is nothing to compare against, so the
 * ending stands.
 */
export function prematureEnd({ positionMs, durationMs, graceMs = TRUNCATION_GRACE_MS } = {}) {
  const dur = Number(durationMs) || 0;
  if (dur <= 0) return false;
  return (Number(positionMs) || 0) < dur - Math.max(0, Number(graceMs) || 0);
}
