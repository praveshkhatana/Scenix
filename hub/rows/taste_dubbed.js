'use strict';
/*
 * rows/taste_dubbed.js — "Dubbed in Hindi", added as a NEW FILE only.
 *
 * There is no Hindi-dubbed genre to ask an addon for, so this row reads the evidence the hub already
 * holds on a title's copies. An addon that knows the audio languages of a release says so on the source
 * (`_langs`, parsed from the release name — "Dual Audio", "Hindi", "HD[Hindi]"). A title whose own
 * metadata does not say Hindi, but which has a Hindi-audio copy available, is a dub, and this is where
 * it shows up.
 *
 * Two honest limits, both visible from the outside:
 *   - The copies come from the addons' **warmed stream cache** (`sources()` is cache-only, so the row
 *     never waits on the network). Titles the hub has never looked at are not judged: they may be dubs,
 *     but nothing here says so, so the row stays short after a restart and grows as the home page warms
 *     what is on screen. The scan is capped for the same reason — a shelf answers inside the row budget.
 *   - Cinemeta states no audio language on any title, so `languages` alone cannot identify a dub; the
 *     release name is the only evidence in this household, and it is treated as evidence, not proof.
 */
const SCAN_CAP = 400; // titles examined per request: a shelf must answer inside the row budget
const WANT = 40; // enough candidates for a 30-tile row after the rules trim it

module.exports = {
  id: 'taste-dubbed',
  title: 'Dubbed in Hindi',
  card: 'poster',
  order: 68,
  scope: 'adult',
  max: 30,

  async build(ctx) {
    const items = (await ctx.library()).filter((it) => it.type === 'movie' || it.type === 'series');
    const withStreams = Object.values(ctx.providers).filter((p) => typeof p.sources === 'function');
    // Kick the background warming for what we are about to judge. Without this the shelf could stay
    // empty forever: it can only name a title whose copies the hub has looked at, and nothing else on
    // the home page happens to look at the middle of the library. The addon caps its own warming.
    for (const p of Object.values(ctx.providers)) {
      if (typeof p.warmStreams !== 'function') continue;
      try {
        p.warmStreams(items.slice(0, 60).map((it) => it.identity));
      } catch (e) { /* warming is a favour, not a contract */ }
    }
    const out = [];
    for (const it of items.slice(0, SCAN_CAP)) {
      const own = it.languages || [];
      if (own.includes('hin')) continue; // originally Hindi, not a dub
      let hindi = false;
      for (const p of withStreams) {
        let srcs = [];
        try {
          srcs = (await p.sources(it)) || [];
        } catch (e) {
          continue; // an addon that will not answer about this title simply does not vouch for it
        }
        if (srcs.some((s) => Array.isArray(s._langs) && s._langs.includes('hin'))) { hindi = true; break; }
      }
      if (!hindi) continue;
      out.push(it);
      if (out.length >= WANT) break;
    }
    // Titles whose original language is known and is not Hindi first: those are the proven dubs.
    return out.sort((a, b) => (b.languages || []).length - (a.languages || []).length);
  },
};
