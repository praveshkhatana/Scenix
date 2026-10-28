# Debrid / direct resolver

`hub/core/debrid.js` is an **optional** first-class source path. It asks a paid debrid service to turn a
magnet or a file-hoster link into an ordinary HTTP URL, which the player can seek the same way it seeks
any direct file. It is off by default, ships with no provider selected and no token, and is never a hard
dependency of `/app/v1/play`.

This is the one paid piece in a hub that otherwise rents nothing. The brief asks that the viewer “does
not worry about sources”; a debrid account is the fastest way to a file that already sits on a server
instead of a swarm that may not have the pieces tonight. The owner's own account is the only supported
form: Scenix does not sell, bundle, proxy or share one.

## What changes when it is off

Nothing. `debrid.ready()` is false, `capabilities.debrid` is absent, no provider request is made, no
cache is consulted, and `/app/v1/play` runs exactly the code it ran before this module existed. An
enabled switch with no token is also inert: the play path does not spend a round trip on it.

## Settings

`hub/core/settings.js` adds one neutral group:

```json
"debrid": { "enabled": false, "provider": "", "token": "" }
```

* `enabled` — the owner's switch. Off by default.
* `provider` — a provider id, which is the file name in `hub/core/debrid/`. Currently `real-debrid`.
* `token` — the owner's private API token. Write-only over HTTP.

`PUT /app/v1/settings` accepts the token. An empty or missing token means **keep the stored one**, because
the settings screen edits a copy of the settings it was given and that copy deliberately has no token in
it. `token: null` is the explicit erase. `GET /app/v1/settings` and the PUT reply return
`debrid: { enabled, provider, tokenSet }` and never `token`; the top-level `debrid` object is the
resolver's status (provider list, cache counters, last result), also without the token.

`/healthz` carries the same `debrid` status. `/app/v1/manifest` advertises `capabilities.debrid: true` only
when the resolver is enabled, has a known provider **and** a token — a client should not show a “Debrid”
badge for a switch that cannot resolve anything.

Install-time seeding (only on a hub with no settings file yet) supports `DEBRID_ENABLED=1`,
`DEBRID_PROVIDER=real-debrid` and `DEBRID_TOKEN=…`. Once the file exists, the UI owns the value; an
environment variable left in a compose file cannot silently switch a deliberately disabled account back
on.

## Playback order

Inside the Play picker, for each ranked candidate:

1. If `debrid.ready()` and the candidate is a **torrent/magnet** or a **hoster URL**, call the resolver
   first. The call is bounded to at most ~3.2 s and leaves the rest of the Play budget for the gateway. It
   is skipped when less than 2 s of that budget remains, so a provider that is thinking cannot consume the
   time the fallback needs.
2. On success the answer becomes `mode: "direct"`, `kind: "http"` (or `"hls"`), with
   `provider`, `providerLabel`, `expiresAt`, and a `debrid: { provider, providerLabel, kind, expiresAt,
   fileName, fileSize, cached }` block. `why[]` gets a sentence saying the provider served it, and the
   tagline ends in the provider's name (for example `1080p · Torrent · 3.9 GB · Real-Debrid`).
3. On failure the reason is logged and put in `tried`, with the token redacted. A 20 s negative cache
   stops the same failing magnet from costing the next press the same timeout.
4. A torrent then asks `core/gateway.js` exactly as before. A hoster URL that the resolver refused is
   accepted as the ordinary direct link it was. Neither path becomes a hard failure.
5. If the gateway also cannot serve it, the existing 503 “warming” / magnet behaviour is unchanged.

The resolver is a **preference**, not a rescoring pass: it does not rewrite `rules/source_ranking.js`, and
a copy the household's rules put first is still the copy tried first. It simply changes what “first” plays
through when the owner has paid for that.

## Provider contract

A provider is one file in `hub/core/debrid/`, discovered at boot. A broken file is logged and skipped, never
allowed to stop the hub. It exports:

```js
module.exports = {
  id: 'real-debrid',            // settings.debrid.provider, lowercase slug
  label: 'Real-Debrid',         // what the settings screen and tagline show
  hosters: ['rapidgator.net'],  // optional: domains eligible for unrestrict()

  // One hoster link -> a direct URL
  async unrestrict(url, ctx) {
    // ctx: { token, want, deadline, http, redact }
    // -> { url, kind: 'http'|'hls', expiresAt (epoch ms), provider, providerLabel,
    //      fileName?, fileSize? }  or { url: null, reason }
  },

  // A magnet or bare info hash -> the same shape
  async resolveMagnet(magnet, ctx) { /* … */ },

  // Account facts for the settings screen; not called by /healthz
  async status(ctx) { /* -> { ok, premium?, expiresAt?, reason? } */ },
};
```

`ctx.http(opts)` performs one bounded HTTP exchange:
`{ url, method, headers, form, timeoutMs, maxBytes } -> { ok, status, json, text, error, ms }`.
It never throws and never includes request headers in its answer. `ctx.deadline` is an absolute epoch;
`core/debrid.js` also wraps the whole provider call in a hard timeout, so a provider that ignores its
deadline cannot hold a Play press open. `ctx.want` is `{ season, episode }` when the viewer pressed Play on
an episode.

To add AllDebrid or Premiumize, add one file that implements those three methods; do not add a second
branch to `hub/index.js`. The status shape, redaction, cache, negative cache and fallback already live in
`core/debrid.js`.

### Real-Debrid adapter

`hub/core/debrid/real-debrid.js` uses REST 1.0 (`https://api.real-debrid.com/rest/1.0`), Bearer auth:

* `POST /unrestrict/link` — a hoster link, one call.
* `POST /torrents/addMagnet` — `{ id }` for the magnet. On an “already added/active” refusal it looks the
  hash up in `GET /torrents?limit=100` and reuses the existing torrent.
* `GET /torrents/info/{id}` — status and `files`.
* `POST /torrents/selectFiles/{id}` — selects **one** video file: the exact `SxxExx` when the viewer asked
  for an episode, otherwise the largest video file.
* Polls `info` until `downloaded` (700 ms between calls, bounded by the outer deadline), then calls
  `/unrestrict/link` on the returned host link and hands back `download`.
* `GET /user` backs `status()`; it reports premium/expiry and never the username, email or token.

Real-Debrid limits the API to 250 requests per minute. A 5 s Play attempt costs a handful of calls; the
cache and the 20 s negative cache keep a failing title from repeating them on every press.

## Cache and timeouts

Resolved URLs live in an in-memory `Map` for up to 2 hours (or the provider's own `expiresAt`, whichever is
sooner) with a 200-entry ceiling; entries within 30 s of expiry are re-resolved rather than handed out.
Nothing is written to disk, so a restart forgets every URL. Successful resolutions are keyed by provider,
info hash **and requested season/episode** — a season pack resolved for S01E04 must not serve S01E05's link
— or by the full hoster URL.

Defaults, all overridable for an operator: `DEBRID_BUDGET_MS=5000`, `DEBRID_MAX_BUDGET_MS=10000`,
`DEBRID_HTTP_TIMEOUT_MS=5000`, `DEBRID_CACHE_MS=7200000`, `DEBRID_MISS_MS=20000`,
`DEBRID_URL_TTL_MS=7200000`. The play path passes a smaller budget (≤3.2 s) than the module default.

## Security

* **At rest**: the token is a normal string in `hub/data/settings.json`. Settings writes create the file
  with mode `0600` (`-rw-------`) and `chmod` it after the rename, so an older world-readable file is
  corrected on the next save. `data/` should still be `0700` and excluded from backups you do not
  control. The token is not encrypted: a process that can read the hub's data directory can read it.
* **In transit**: the adapter uses HTTPS to Real-Debrid and sends the token only in the `Authorization:
  Bearer` header. It never puts the token in a URL, a query string or a log line.
* **In responses**: `GET /app/v1/settings`, the PUT reply and `/healthz` return `tokenSet`, never `token`.
  `settings.redacted()` is the only shape those routes use for the settings object.
* **In errors**: every reason is passed through `redactText()`, which removes the configured token, any
  `Bearer …` string and any `auth_token=…` parameter, then truncates. HTTP errors are reduced to a status
  and a short provider sentence; response bodies and request headers are never logged.
* **Network scope**: only public `http(s)` URLs are sent to a provider. `localhost`, `127/8`, `10/8`,
  `172.16/12`, `192.168/16`, link-local, `.local` and IPv6 ULA/link-local addresses are refused, so a
  provider is never asked to fetch something on the household LAN.
* **Who may use the account**: this hub's `/app/v1` is LAN-trusted and has no user authentication. Anyone
  who can reach the hub can press Play and therefore use the configured account's quota, though they
  cannot read the token. Bind the hub to the LAN/Tailscale interface you intend, and do not expose
  `/app/v1` to the public internet.
* **Revocation**: remove the token in Settings, or revoke it at the provider. A token that has appeared in
  a terminal, a screenshot or a bug report should be treated as leaked and rotated.

## What is verified, and what needs an account

Verified without an account:

* `node --check` on every changed JS file (hub core, route, settings screen, self-test).
* `node scripts/check-debrid.js` — a scripted, no-network adapter test. It asserts the episode asked for is
  the file selected, the torrent flow reaches `unrestrict`, and no token appears in a returned object.
* Default-off behaviour, settings redaction (`token` absent, `tokenSet` true), the “empty token keeps,
  null clears” PUT rule, file mode `0600`, cache/timeout helpers, hoster/private-URL classification.
* The play fallback is wired before the gateway and returns `mode:"direct"` with the provider fields; the
  disabled path is the old path.

Needs a real account to verify end-to-end:

* Token acceptance, premium status, and the exact error text Real-Debrid returns for a locked or expired
  account.
* Live `addMagnet`/`selectFiles`/`info` behaviour for a cold swarm, and how long a given torrent takes to
  reach `downloaded` (this is why the first press may fall through to the gateway).
* Which hoster domains the account can unrestrict; the built-in domain list is a starting point, and a
  source marked `kind: "hoster"` is attempted even when its domain is not listed.
* The lifetime of the generated download URLs. The adapter assumes two hours; the cache re-resolves near
  expiry rather than promising a dead link.
* Season packs whose file paths do not contain `SxxExx`; those fall back to the largest video file.

## Known limits

* Magnet resolution happens inside the Play budget. A torrent that Real-Debrid has not finished downloading
  will usually fall through to the gateway on the first press; a later press may find it cached/downloaded.
  There is no background download queue.
* One video file is selected per torrent resolution. A season pack is keyed and resolved per episode.
* No live TV, no transcoding, no subtitle fetching through the provider; those paths are unchanged.
* The resolved URL is handed to the client directly (not proxied through the hub), so the client must be
  able to reach the provider's download host.
* No OAuth app flow: the owner pastes a private API token from their provider's account page.
