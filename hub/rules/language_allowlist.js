'use strict';
/*
 * rules/language_allowlist.js — the HARD language rule.
 *
 * An item whose `languages` are known and none of which are in the allow-list is dropped. An item
 * whose languages are unknown (a live feed iptv-org lists no language for, a YouTube upload) is kept:
 * unknown is not the same as wrong, and dropping everything we cannot classify would empty the
 * shelves.
 *
 * The list is the hub's own setting — `settings.languages`, chosen by the owner, empty meaning no filter
 * at all. It ships empty because three languages are one household's policy and not a product's: a build
 * that preselects them hides most of the catalogue from a stranger and never tells them.
 *
 * Per profile, profiles.json may override it:
 *   "rules": { "language-allowlist": { "allow": ["eng"] } }
 * and LANGS (env, install-time seeding only) pre-fills the setting on a hub that has none.
 */
const settings = require('../core/settings');

/** Read when the rule runs, not when the file loads: a settings change lands on the next request. */
function allowFor(ctx) {
  const r = ctx.profile && ctx.profile.rules && ctx.profile.rules['language-allowlist'];
  if (r && Array.isArray(r.allow)) return r.allow;
  if (ctx.profile && Array.isArray(ctx.profile.languages)) return ctx.profile.languages;
  return settings.languages();
}

module.exports = {
  id: 'language-allowlist',
  order: 10,

  async apply(item, ctx) {
    const allow = allowFor(ctx);
    if (!allow.length) return item; // filter switched off
    const langs = item.languages;
    if (!Array.isArray(langs) || !langs.length) return item; // unknown language: not classifiable
    if (langs.some((l) => allow.includes(l))) return item;
    // An addon's one-word "language" field is a claim about a title; the copies are evidence about what
    // can actually be watched. Cinemeta has been seen calling The Dark Knight Dutch. Before a title
    // disappears from the shelf entirely, ask the files: if a copy states a language this profile
    // allows, the viewer can watch it, and hiding the title would be the hub contradicting its own shelf.
    const fromFiles = (item.sources || [])
      .flatMap((s) => [s.language, ...(Array.isArray(s.languages) ? s.languages : [])])
      .filter((l) => typeof l === 'string' && l);
    if (fromFiles.some((l) => allow.includes(l))) return item;
    // Deleting a title is the loudest thing a rule can do, so it needs more than one addon's single
    // word: the files have to say the same thing, or the title has to name several languages and none
    // of them be allowed. A one-word claim with nothing behind it is left on the shelf and logged —
    // "this title has no copy right now" is an honest answer, "this profile can't watch it" based on a
    // bad metadata word is not.
    if (langs.length === 1 && !fromFiles.length) {
      item._langUncorroborated = true;
      return item;
    }
    item._langDroppedBecause = { claimed: langs, files: fromFiles, allowed: allow };
    return null;
  },
};
