'use strict';
/*
 * core/gateway.js — the seam where a torrent becomes a seekable HTTP stream.
 *
 * Why this exists: a magnet needs a torrent engine, and a 2 GB 32-bit TV is a poor place to run one.
 * The machine that hosts the hub (x86, plenty of RAM, wired) is a good place. A gateway fetches the
 * torrent pieces in playback order and serves them as an ordinary HTTP byte stream, so the TV, a web
 * page and a phone all play the same file with no torrent code on the client at all.
 *
 * The seam, not the engine: this hub ships dependency-free, so the gateway is OFF by default and an
 * enabled-but-unimplemented mode says so out loud instead of failing mysteriously.
 *
 *   GATEWAY=off     (default) torrents are handed to the client as a magnet, mode "direct"
 *   GATEWAY=http    the hub should serve torrents as seekable HTTP; needs a torrent engine
 *
 * The client contract does not change when a real engine lands: same sources[], same
 * POST /app/v1/play, only `mode` and `url` differ. See "Making torrents play everywhere" in README.md.
 */

const MODE = String(process.env.GATEWAY || 'off').toLowerCase();

const NOTE_OFF = 'gateway not enabled: needs a torrent engine on the hub host';
const NOTE_HTTP = 'GATEWAY=http is set, but this hub build ships no torrent engine yet';

/** What the operator's /healthz shows, so a misconfigured gateway is visible without reading logs. */
function status() {
  if (MODE === 'off') return { mode: 'off', enabled: false, note: NOTE_OFF };
  if (MODE === 'http') return { mode: 'http', enabled: false, note: NOTE_HTTP };
  return { mode: MODE, enabled: false, note: 'unknown GATEWAY value "' + MODE + '": expected off or http' };
}

/**
 * Turn a playable source into a hub-served URL. Returns null when there is no gateway to use, which
 * is the normal case: the caller then hands the client the magnet (mode "direct") or the proxied
 * upstream URL (mode "proxy").
 */
function gatewayUrl(source, identity) {
  if (MODE === 'off' || !source) return null;
  if (source.kind !== 'torrent') return null;
  // An engine would register the infoHash, then serve it at a stable path the client can seek:
  //   return '/app/v1/gateway/' + encodeURIComponent(source._addon) + '/' + encodeURIComponent(identity);
  return null;
}

/** The error a client gets if GATEWAY=http is configured but nothing implements it yet. */
function notEnabledError() {
  return { error: 'gateway not enabled', gateway: status() };
}

module.exports = { MODE, status, gatewayUrl, notEnabledError };
