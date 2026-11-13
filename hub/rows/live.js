'use strict';
/*
 * rows/live.js — the live TV wall: working channels first, in the zap order the TV app numbers.
 *
 * The order is not invented here. core/live-health.js probes each channel's own stream manifest in a
 * bounded background batch and records what answered; this row sorts on that (working, then
 * never-checked, then known-dead) and inside each class on the channel's **stable number** — a number
 * assigned once, persisted in data/live-health.json and never re-shuffled, so "1. Sab TV", "2. &TV"
 * means the same thing tomorrow and after every restart. A viewer scrolling this wall never lands on
 * a feed the hub already watched fail.
 *
 * Nothing here names an addon: every installed addon that publishes channels shows up in this row the
 * moment it is installed, and disappears the moment it is disabled. An addon that publishes channels
 * without a probed identity simply sorts as "unknown" (never checked is not the same as dead) and
 * keeps the order the addons published them in.
 *
 * The wall is read from the providers that publish CHANNELS, not from `ctx.library()`. Walking the whole
 * catalogue — every film and series on every installed addon, one catalogue after another — to find the
 * 358 things already sitting in the live provider's own list is what made this row miss its 1200 ms
 * budget and answer `[]`: the TV's zap ring was handed nothing, and on a warm hub it was handed whatever
 * had arrived before the cut, which is why zapping "rotated between a few channels".
 */
const liveHealth = require('../core/live-health');
const settings = require('../core/settings');
const merge = require('../core/merge');
const log = (...a) => console.log(new Date().toISOString(), ...a);

/**
 * Every channel every installed provider publishes, deduped by identity.
 *
 * Only providers whose `kind` says `channel` are asked, and they are asked at the same time: the
 * household's own playlist answers from memory, a third-party IPTV addon answers from the addon cache
 * (and kicks its own refresh), and one slow addon can no longer take the wall down with it.
 */
async function channelItems(ctx) {
  const providers = Object.values((ctx.providers || {}))
    .filter((p) => p && p.kind === 'channel' && typeof p.catalog === 'function');
  const answers = await Promise.all(providers.map(async (p) => {
    let cats = [];
    try {
      cats = (typeof p.catalogs === 'function' ? await p.catalogs() : []) || [];
    } catch (e) {
      log('CATALOGS FAIL', p.id, e.message);
      return [];
    }
    // The channel provider's catalog is cache-only and synchronous; the extra filters are left alone
    // so a provider that serves several channel shelves contributes all of them.
    const out = [];
    for (const c of cats) out.push(...((await ctx.catalog(p.id, c.id)) || []));
    return out.filter((it) => it && it.identity && (it.type === 'channel' || it.live));
  }));
  const all = [];
  for (const a of answers) all.push(...a);
  return merge.dedupe(all, ctx.priorities || {});
}

module.exports = {
  id: 'live',
  title: 'Live TV',
  card: 'channel',
  order: 50,
  scope: 'all',
  // A wall, not a shelf: 383 channels is normal for a playlist, and a zap ring that stops at 60 is a
  // remote that cannot reach channel 61. The route's own cap honours this (`max` is the row's answer).
  max: 700,

  /**
   * The wall before any profile's taste is applied: the profile's hidden channels and lineup removed,
   * the lineup's order applied, the household's rules still run over it by the caller. Exported because
   * `/app/v1/live/channels` (the channel manager) and this row must be the SAME list — a manager that
   * offers channels the zap ring does not know is how "channel 214" becomes untunable.
   */
  channelItems,

  async build(ctx) {
    const found = await channelItems(ctx);
    const local = (ctx.profile && ctx.profile.prefs && ctx.profile.prefs.live) || {};
    const hidden = new Set([
      ...(((settings.get().live || {}).hidden) || []),
      ...(Array.isArray(local.hidden) ? local.hidden : []),
    ]);
    let channels = found.filter((it) => !hidden.has(it.identity));
    // A profile's lineup: when `only` has entries the wall is those channels, in that order. Empty means
    // every channel the rules allow, which is the old behaviour and the sensible default for a new profile.
    const only = Array.isArray(local.only) ? local.only : [];
    if (only.length) {
      const set = new Set(only);
      channels = channels.filter((it) => set.has(it.identity));
    }
    // The profile's order, then its lineup order, then the household's; anything unmentioned keeps the
    // health-then-number order. This is the zap order the remote walks.
    const localOrder = Array.isArray(local.order) ? local.order : [];
    const wanted = localOrder.length ? localOrder : (only.length ? only : ((settings.get().live || {}).order || []));
    const rank = new Map(wanted.map((id, i) => [id, i]));
    channels.sort((a, b) => {
      const ar = rank.has(a.identity) ? rank.get(a.identity) : Number.MAX_SAFE_INTEGER;
      const br = rank.has(b.identity) ? rank.get(b.identity) : Number.MAX_SAFE_INTEGER;
      return ar - br || liveHealth.compare(a, b);
    });
    return channels;
  },
};
