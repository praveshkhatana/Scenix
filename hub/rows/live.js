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
 */
const liveHealth = require('../core/live-health');

module.exports = {
  id: 'live',
  title: 'Live TV',
  card: 'channel',
  order: 50,
  scope: 'all',
  max: 60,

  async build(ctx) {
    const items = await ctx.library();
    // More candidates than the row shows, so a rule filtering one channel does not shorten the wall.
    const channels = items.filter((it) => it.type === 'channel');
    channels.sort(liveHealth.compare);
    return channels.slice(0, 200);
  },
};
