'use strict';
/*
 * rules/language_allowlist.js — the HARD language rule.
 *
 * An item whose `languages` are known and none of which are in the allow-list is dropped. An item
 * whose languages are unknown (a live feed iptv-org lists no language for, a YouTube upload) is kept:
 * unknown is not the same as wrong, and dropping everything we cannot classify would empty the
 * shelves. Default allow-list is hin,eng,pan — the same three playlists iptv-org itself fetches.
 *
 * Per profile, profiles.json may override it:
 *   "rules": { "language-allowlist": { "allow": ["eng"] } }
 * and LANGS="" (env) turns the rule off for every profile.
 */
const DEFAULT_ALLOW = (process.env.LANGS === undefined ? 'hin,eng,pan' : process.env.LANGS)
  .split(',').map((s) => s.trim()).filter(Boolean);

function allowFor(ctx) {
  const r = ctx.profile && ctx.profile.rules && ctx.profile.rules['language-allowlist'];
  if (r && Array.isArray(r.allow)) return r.allow;
  if (ctx.profile && Array.isArray(ctx.profile.languages)) return ctx.profile.languages;
  return DEFAULT_ALLOW;
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
    return null;
  },
};
