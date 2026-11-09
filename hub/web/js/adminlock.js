/**
 * Household Settings-lock helpers shared by every browser administration screen.
 *
 * The server is the authority for both the PIN and the short-lived, client-bound capability. This
 * module only owns the interaction contract: prompt before a protected write, discard a rejected
 * capability, and give the owner one clear recovery path when a visit outlives its session.
 */

import {
  api,
  adminSessionActive,
  clearAdminSession,
  isSettingsSessionRefusal,
} from '/ui/js/api.js';

function lockFrom(reply) {
  const settings = reply && reply.settings && typeof reply.settings === 'object'
    ? reply.settings : reply;
  const lock = settings && settings.lock;
  return lock && typeof lock === 'object' ? lock : null;
}

function messageFor(error) {
  if (error && error.status === 429) return 'Too many attempts — wait a moment and try again';
  if (error && error.status === 401) return 'That Settings PIN is not right';
  if (error && error.status === 403) return 'That Settings PIN is not right';
  return 'The hub would not check the Settings PIN';
}

/**
 * Ensure this browser has the household capability if the current hub requires one. `knownLock` is
 * the settings response already held by the caller; passing null after a rejected write deliberately
 * re-reads it, because another device may have changed the lock while this screen was open.
 */
export async function ensureSettingsAdmin(
  ctx,
  knownLock = null,
  reason = 'Enter the household Settings PIN',
) {
  // A live capability already proves the same client against this Hub. Avoid a settings read before
  // every switch or channel edit; if the server has revoked it, the protected write below reports the
  // canonical refusal and `withSettingsAdmin` re-reads the lock before a single retry.
  if (adminSessionActive()) return true;
  let lock = knownLock;
  if (!lock || lock.enabled !== true) {
    // An explicit `enabled: false` response needs no second request. A missing/old response does:
    // that avoids deciding locally that a household is unlocked just because a stale screen knew less.
    if (lock && lock.enabled === false) return true;
    try {
      lock = lockFrom(await api.settings());
    } catch (error) {
      ctx.toast('The hub would not check the Settings lock');
      return false;
    }
  }
  if (!lock || lock.enabled !== true) return true;

  for (;;) {
    const pin = await ctx.enterSettingsPin(reason);
    if (!pin) return false;
    try {
      const answer = await api.unlockSettings(pin);
      // `pinSet: false` means another device removed the lock while the pad was open. It is safe to
      // continue, and avoids asking for a number the hub no longer keeps.
      if (answer && answer.ok !== false && (adminSessionActive() || answer.pinSet === false)) return true;
      ctx.toast('The hub did not open the Settings lock');
    } catch (error) {
      ctx.toast(messageFor(error));
    }
  }
}

/**
 * Run one protected operation. The request is retried exactly once after a server-declared expired
 * capability; all other failures stay visible to the caller, preserving the operation's own error
 * copy and avoiding accidental duplicate writes.
 */
export async function withSettingsAdmin(ctx, run, knownLock = null, reason) {
  if (!await ensureSettingsAdmin(ctx, knownLock, reason)) return null;
  try {
    return await run();
  } catch (error) {
    if (!isSettingsSessionRefusal(error)) throw error;
    clearAdminSession();
    ctx.toast('Your Settings PIN session expired. Enter it again.');
    if (!await ensureSettingsAdmin(ctx, null, reason)) return null;
    return run();
  }
}

