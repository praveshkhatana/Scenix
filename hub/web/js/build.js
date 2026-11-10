/**
 * Which build of the hub this page is talking to.
 *
 * When a screen looks wrong, the household's first question is "am I looking at the newest one" — and
 * today that question can only be answered from a terminal on the hub box. The hub already reports its
 * own git facts (core/update.js, GET /app/v1/hub/update), so this is one small cached read of an answer
 * that exists, not a new endpoint: the mark goes at the foot of every screen and on the This Hub tab.
 *
 * It says nothing the page could not work out itself; it exists so a stale tab and an old hub are two
 * different answers instead of one guess.
 */
import { api } from '/ui/js/api.js';

let cached = null;

/** The hub's build facts, asked for once per page load. Null when the hub would not say. */
export function hubBuild() {
  if (!cached) cached = Promise.resolve(api.hubUpdate()).catch(() => null);
  return cached;
}

/** One short human line: `hub 5e6a938 · games-mode`, or `hub 5e6a938 · 3 behind` when it is stale. */
export function buildLabel(s) {
  if (!s) return '';
  if (!s.git) return s.version ? `hub v${s.version}` : '';
  if (!s.commit) return '';
  const branch = s.branch && s.branch !== 'HEAD' ? ` · ${s.branch}` : '';
  const behind = Number(s.behind) > 0 ? ` · ${Number(s.behind)} behind` : '';
  return `hub ${s.commit}${branch}${behind}`;
}
