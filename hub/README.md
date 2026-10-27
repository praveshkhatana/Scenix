# Scenix Hub

The content side of Scenix: one Node process, Node built-ins only, that merges several content
sources into one item model and serves it to the TV app **and** to the web app from the same
port, at the same time.

```
node index.js                    # 0.0.0.0:8788
PORT=8789 LOG_REQUESTS=1 node index.js
```

Contract: `../../sony-bravia-vu1/design/plugin-architecture.md` §1 (item model) and §2 (hub host).
Predecessor, kept route-for-route compatible: `../iptv-india-addon.js`.

## Adding a feature is adding one file

`core/registry.js` scans `providers/`, `rows/`, `rules/` at boot. Nothing else is edited.

| You want | You add |
|---|---|
| a new source (JioCinema cache, a local NAS) | `providers/<name>.js` |
| a new shelf ("Punjabi classics") | `rows/<name>.js` |
| a new filter (a bedtime cutoff) | `rules/<name>.js` |
| a new household member | a line in `profiles.json` (start from `profiles.example.json`) |

A file that throws on load is logged and skipped — `/healthz` lists it under `plugins.failed` and the
hub keeps serving every other row. `rows/punjabiclassics.js` is the worked example.

### Provider — `providers/x.js`
```js
module.exports = {
  id: 'x', kind: 'title|channel|video', priority: 30,   // lower wins a field collision
  start(),                                    // kick your own cache refresh; never awaited
  catalogs(),                                 // [{id, name, extra:[…]}]
  catalog(id, extra),                         // must be CACHE-ONLY for the home path
  meta(identity),                             // may take one bounded round trip
  play(identity, source),                     // -> {url, headers, kind, expiresAt}
  sourceTarget(identity, source),             // optional: {url, headers} this source WOULD play, with
                                              // no round trip and no child process, so core/probe.js can
                                              // measure it; null when there is nothing to measure
  stats(),                                    // for /healthz
  legacy: {                                   // optional: expose yourself on the addon-protocol routes
    idPrefixes: ['x___'], catalogIds: ['x_all'],
    manifestPart(), catalog(type, id, extra), meta(extId), stream(extId), ensure(),
  },
};
```

### Row — `rows/x.js`
```js
module.exports = {
  id: 'x', title: '…', card: 'poster|channel|continue', order: 60,
  scope: 'all|adult|kids',   // kids profiles see scope:'kids' rows ONLY
  max: 20,                   // tiles wanted; the hub trims AFTER the rules run
  async build(ctx) { return [/* items */]; },
};
// ctx: { profile, providers, store, rules, days, now, catalog(), library(), resolve() }
```

`order` is only where a row starts, not where it has to stay. An owner can switch any row off or move it
around from the settings screen, and that override is stored in `data/settings.json` as `rows.hidden` and
`rows.order` — both shipped empty, so a fresh hub draws exactly the shelves its own `rows/` files ask
for, in their own order, and nothing about the defaults depends on which of those files happen to exist.

### Rule — `rules/x.js`
```js
module.exports = { id: 'x', order: 30, async apply(item, ctx) { return item; /* or null to drop */ } };
```
Rules run in `order` on **every** item of **every** row, so a row added later cannot bypass them.

## Routes

| | |
|---|---|
| `GET /healthz`, `GET /` | per-provider and per-row counts, loaded and **failed** plugins |
| `GET /app/v1/manifest` | rows, cards, providers, rules, theme id, and `capabilities` — what this install can do (`gateway`, `live`, `localFiles`, `remux`, `tracks`, `announce`), which is the only thing a client UI may branch on |
| `GET /app/v1/profiles` | profiles and the rows each one sees, with **both** preference views: `prefs` (what the owner typed, untouched, the thing a settings screen writes back) and `effectivePrefs` (what it resolves to once household defaults fill the gaps — the language order a player needs) |
| `GET /app/v1/home?profile=<id>` | `{ rows: [{ id, title, card, items: […] }] }` |
| `GET /app/v1/row/<id>?profile=<id>` | one row, for the app's "see all" |
| `GET /app/v1/search?q=<q>&profile=<id>` | every provider's search, merged, deduped |
| `GET /app/v1/meta/<identity>?profile=<id>` | one merged tile (403 if a rule drops it for that profile) |
| `GET /app/v1/subs/<identity>?profile=<id>&lang=hin,en` | merged subtitles (403 if the title is hidden) |
| `GET /app/v1/trailer/<identity>?profile=<id>` | `{url, headers, kind, videoId, expiresAt}` or 404 `{error:"no trailer"}`, from `data/trailers.json`: the clip id is already there for most titles, so only the perishable signed URL is minted (`yt-dlp`) when it has expired. A malformed percent-escape in any `/app/v1` path is a 400, never a crashed hub |
| `POST /app/v1/play` `{identity, profile, source?, provider?, type?, exclude?, reprobe?, container?, audio?, sub?}` | `{url, headers, kind, expiresAt, mode, proxiedUrl, picked, why, checked, probe, status, tagline, nextSource, alternatives}` plus the tracks this copy really carries (`audioTracks`, `subtitleTracks`, `selectedAudio`, `selectedSub`) and what container it is being handed in (`container`, `remuxed`) — see [Which link actually plays](#which-link-actually-plays). `container:"any"` (the default) is the native player's copy untouched; `"mp4"`/`"hls"` ask for a browser-safe re-wrap |
| `POST /app/v1/switch` `{identity, profile, exclude?, source?, type?, container?, audio?, sub?}` | same body as play, but the measurement cache is ignored: "this link is buffering, give me another" |
| `GET /app/v1/remux?u=&v=&a=&s=&start=&dur=` | the engine's own stream URL re-wrapped on the fly into fragmented MP4: video is always copied, audio copied when the container can hold it and re-encoded when a browser cannot play it. `a`/`s` are the stream indexes from the track lists (`a:1` → `a=1`), `start` seeks, `dur` stops, and a `Range` header is honoured so a probe cannot download the whole film. `GET /app/v1/hls/<hash>/<fileId>` is the same idea as a playlist |
| `GET /app/v1/subtitle?u=&i=` | one embedded text subtitle, converted to WebVTT and served with CORS, so a browser can use a track that lives inside a Matroska file. A copy the swarm has not delivered yet answers `503 {error:'warming'}` with `Retry-After: 5`, and one that was cut off mid-track says so with `X-Subtitles-Partial: timeout` — asking again reaches further, because the engine keeps what it has already pulled |
| `GET`/`PUT /app/v1/settings` | the owner's settings (`data/settings.json`): the hub's name, its content languages, its live-TV playlist, how the addon host behaves, and which home rows are switched off or reordered. The GET also reports every row this hub can put on a home screen, so a settings screen offers the choice over what actually exists rather than a guessed-at list. PUT accepts the settings flat or wrapped in `{settings: …}`, merges it, and re-applies what other parts derived from the old values |
| `GET /app/v1/addons`, `POST /app/v1/addons`, `POST /app/v1/addons/<id>/enable\|disable`, `DELETE /app/v1/addons/<id>` | the addon-protocol addons this hub answers with, installed or shipped, and which are on |
| `POST /app/v1/progress` `{profile, identity, positionMs, watchedMs}` | remembered in `data/progress.json`; **refused (403) for a tier-3 title while the adult switch is off** |
| `GET`/`POST /app/v1/prefs?profile=<id>` | per-profile `qualityCap` and `xxxEnabled`, with the defaults the app should show |
| `POST /app/v1/cache/clear` | forgets every response, probed file and judged link this process remembers, answering with the three counts it acted on (`{cleared, tracks, links}`); deleting the files by hand changes nothing until a restart, because the running hub holds them open |
| `GET /manifest.json`, `/catalog/`, `/meta/`, `/stream/` | any addon-protocol client — same ids (`inlive___`, `ytv___`), byte-identical bodies |

Every route that names a profile accepts **both** `profile=` and `profileId=` (and `profile`/`profileId` in a
JSON body), because the app has used both spellings and a route that accepts only one silently stops
filtering.

## Which link actually plays

Metadata is a promise: a label saying 1080p says nothing about whether that link is alive on this
network right now. `core/probe.js` is the hub touching it instead, so the app does not have to make the
viewer try three links to find their preference.

A probe is a ranged GET of the first ~256 KB, aborted at the budget, giving
`{ok, status, latencyMs, bytes, kbps, contentType, seekable, error, checkedAt}` — time-to-first-byte
(what the player waits before anything is on screen) and the measured rate (what decides whether
playback stalls). Results are cached in `data/probe-cache.json` keyed by url + the headers the request
replays, trusted 12 min for an ok answer and re-tried after 60 s for a failure. It never throws and
never delays a page view by more than `PROBE_BUDGET_MS`; a probe that cannot answer contributes nothing.

`rules/source_ranking.js` turns that into three fields on **every source object**, which is the row the
app renders — the app should display these, not assemble prose from `why[]`:

| field | what it is |
|---|---|
| `label` | what the addon called it (`1080p · 3.9 GB`) — unchanged |
| `tagline` | one honest line built from the facts we hold: `4K · Adaptive HLS`, `1080p · Direct MP4 · 1.9 GB · Multi-audio Hindi/English` |
| `status` | exactly three values: `available` (measured fast, or nothing says otherwise) · `unavailable` (it answered, but slower than this file needs — buffering) · `down` (the hub opened it and it was not there) |
| `checked` | `true` means `status` is a measurement. `false` means the hub could not measure this link cheaply (a magnet, a URL the addon mints at play time) and `status` is metadata's opinion |
| `probe` | the measurement itself when `checked` is true, `null` otherwise |
| `id` | stable per-source id (`src…`), what `exclude` names |

The flow the player needs mid-film:

1. `GET /app/v1/meta/<identity>` shows the picker with any cached measurement attached, and starts
   measuring the rest in the background — the response itself never waits.
2. `POST /app/v1/play {identity, profile}` measures the top candidates (max `PLAY_PROBE_MAX`, budget
   `PLAY_PROBE_BUDGET_MS`), re-ranks with the numbers in hand, and returns the best one plus
   `nextSource`.
3. Buffering? `POST /app/v1/switch {identity, profile, exclude:[picked.id]}` re-measures around the
   source just tried and hands over the next best. `exclude` accepts the id, the label, or
   `provider:label`.
4. `GET /healthz` reports `probe: {entries, working, failed, probes, cacheHits, budgetMisses, …}`.

With `PROBE_ENABLED=0` none of this happens: no measurement, no `checked`/`probe`/`status`/`tagline`
fields and no probe weights — the scores come out exactly as they did before probing existed (measured:
identical `score` per source with the flag on and off for the same title). The `id` field is the one
exception: it is always assigned, because `exclude` has to be able to name a source either way.

## The billboard — `rows/hero.js`, and one pass over the finished screen

The first row of `/app/v1/home` is a `card:"hero"` row: up to `HERO_MAX` (5) tiles the app paints full-bleed
instead of in a strip. The row is built from what this box already holds — `data/library.json`'s `addedAt`,
the certificates in `data/ratings.json`, the trailer ids in `data/trailers.json`, the sources an addon
already handed over — so a billboard costs no network and cannot make home slow. It is ranked like a
The billboard: something in cinemas, something added in the last few days, something the profile tends
to watch, with a few points of deterministic daily jitter so the screen is not identical every morning, and
a cap per genre so five tiles are not five animations.

Dedupe is the home route's job, not the row's: `arrangeHome()` (in `index.js`) makes one pass over the
finished screen, drops any billboard tile that also appears in a shelf **below** it, and puts the row first.
It also sends `title: null` for that row — a billboard has no header, and the app would render one — which is
why the module itself carries the placeholder title `Featured` (`GET /app/v1/row/hero` is a real shelf and
does need a heading). Nothing is filtered specially for the billboard: a kids profile gets a billboard of
kids-safe titles because `rules/maturity.js` and `rules/kids_allowlist.js` already removed everything else.

## Maturity — two different kinds of "adult"

`rules/maturity.js` (order 15) stamps every title with `tier` and `minAge` before any row or search is
answered. The ladder is a table at the top of that file, so changing a board's placement is a one-line edit:

| tier | minAge | what puts a title here |
|---|---|---|
| 0 | 0 | `U`, `G`, `PG`, `TV-Y` … |
| 1 | 13 | `U/A 7+`, `U/A 13+`, `PG-13`, `12A`, `TV-14` … |
| 2 | 16 | `A`, `U/A 16+`, `R`, `15`, `18`, `TV-MA` … — **and anything we could not rate** |
| 3 | 18 | the upstream's own `adult` flag, an `Adult` genre, an addon the household marked `"adult": true`, or a file under a folder named like `Private` |

An item is visible only when `minAge <= profile.age`, so a kids profile never sees tiers 2–3 — and tier 3
additionally needs that profile's `xxxEnabled` switch, which is **off** by default. Off, a tier-3 title is
absent from home, search, meta, subs, trailer, Continue watching and history, and `POST /app/v1/progress`
refuses it; on, the only thing that appears is the `Late Night` row (`rows/latenight.js`, which contributes
nothing when the switch is off). Turning it off again leaves the records alone but hides them from everyone
until it is turned back on.

The certificates come from `core/ratings.js`: a local database (`data/ratings.json`) keyed by identity,
filled **once** per title from OMDb (the spine, keyed by IMDb id) and TMDB (per-country certification plus
the `adult` flag) and never refreshed on success — only a "looked and found nothing" record expires, after
a week. Live channels and local files have no certificate to find, so they are not judged unrated: they
stay tier 0. `tools/backfill-ratings.js` fills the database for the whole library in one pass on the server;
the daily cap (`RATINGS_DAILY_CAP`) keeps us inside the free tiers and `GET /healthz` reports the counts.

**What actually reaches tier 3 is a content question, not a code question.** Two ways to put titles there,
needing no code at all:

```bash
mkdir -p ~/tvhub/media/Private      # every file under a folder named private|adult|xxx|18+|explicit
```

`providers/local-files.js` stamps the folder name in as the item's genre, and `core/ratings.js` reads that
as tier 3. Or install an adult catalogue and mark the entry by hand in `data/addons.json`:

```json
{ "id": "adultxxx", "name": "Adult movies", "url": "https://…/manifest.json",
  "enabled": true, "priority": 44, "configured": true, "adult": true }
```

`ratings.adultAddonIds()` reads that flag (mtime-checked, so it applies without a restart once the addon is
mounted) and anything arriving through that addon is tier 3 whatever its genres say — a family comedy from
an adult addon is still adult content. The flag is not settable over the API (`POST /app/v1/addons` builds
its entries from fixed fields), so it is a hand edit; hand-edited fields survive every rewrite the hub does
to that file. As a control, the same addon with `"adult": false` publishes the same titles at tier 0–2 and
they leave the shelf.

On this box, no community adult addon answers with a catalogue from this network: `adultmovies.xyz`
returns a parked-domain lander instead of JSON, `adultovies.me` / `pct-streams.420666.xyz` /
`adult-torrents.p2p-today.xyz` do not resolve, and a dozen `*-pms`/`*-cinema` Vercel and Deno deployments
404. `torrentio.strem.fun/adult=true` is reachable but stream-only (zero catalogues), so it can serve the
streams of a title some other addon published, not the shelf itself. Until a working one appears, the
`media/` folder above is the Late Night content path, and `GET /healthz` → `ratings.byTier` says how much
there is.

## Environment

| Var | Default | What it does |
|---|---|---|
| `PORT` | 8788 | listen port |
| `LOG_REQUESTS` | off | `REQ <method> <url> -> <code>` per request |
| `LANGS` | unset | **seeds** `settings.languages` on a hub that has none (the allow-list rule and the live playlists both read the setting, not this). An empty setting means the language rule is off |
| `HUB_NAME` | `Scenix Hub` | **seeds** `settings.hubName`: the name a client shows when it lists the hubs it found |
| `PLAYLIST_URL` | unset | **seeds** `settings.live.playlist`; with no playlist there is no live TV row |
| `ANNOUNCE` | on | `0` stops the LAN responder, so pairing is by typed address only |
| `ANNOUNCE_PORT` / `ANNOUNCE_INTERVAL_MS` | 1900 / 30000 | the UDP port the responder listens on and how often it says "I am here" |
| `SETTINGS_FILE` | `data/settings.json` | where the owner's settings live |
| `KIDS_BLOCK` | `Animax,MediBizTV,…` | kid-grouped channels that are still not for kids |
| `YT_BIN` | `yt-dlp` | yt-dlp path; `YT_JS` (its `--js-runtimes`), `YT_CLIENT` (`android`), `YT_MAX`, `YT_CHANNELS` |
| `SEED_ADDON_URLS` | unset | comma-separated addon-protocol upstreams that replace the shipped seed list (the shipped list is built from `settings.languages` and `settings.addons.sizeFilter`, since an addon carries its configuration in its URL) |
| `SIZE_CAP_GB` | 4 | hard per-source cap in `rules/size_cap.js` (the per-profile cap in `prefs` is what usually binds) |
| `QUALITY_CAP`, `KIDS_QUALITY_CAP` | `1080p` / `720p` | default `qualityCap` for adult and kids profiles (`auto` disables the ceiling) |
| `RATINGS_DAILY_CAP` | 800 | upstream certificate lookups per day, counted in `data/ratings.json`; browsing spends it, the backfill stops at it |
| `RATINGS_BUDGET_MS` | 2500 | how long a request may wait on one cold certificate lookup |
| `RATINGS_FILE`, `RATINGS_KEYS_FILE` | `data/ratings.json`, `data/keys.json` | the certificate database and the API keys (`{"omdb":…,"tmdb":…}`, keep it `600`) |
| `LIVE_HEALTH_TIMEOUT_MS` / `_CONCURRENCY` / `_STALE_MS` | 6000 / 8 / 24 h | the live prober: how hard it probes, and after what age a "dead" verdict is a rumour again. Channel numbers are assigned once and stay put |
| `PROBE_ENABLED` | on | `0` turns source measuring off completely: responses go back to exactly their pre-probe shape |
| `PROBE_BYTES` / `PROBE_MIN_SAMPLE_MS` / `PROBE_SAMPLE_MULTIPLIER` | 256 KB / 250 ms / 4 | how much of a stream is read, and the shortest window a rate may be measured over (a rate from a 2 ms window is not a rate) |
| `PROBE_TIMEOUT_MS` / `PROBE_BUDGET_MS` / `PROBE_CONCURRENCY` | 1200 / 1500 / 4 | one probe, one batch, and how many probes at once across the whole hub |
| `PROBE_OK_TTL_MS` / `PROBE_FAIL_TTL_MS` / `PROBE_CACHE_MAX` | 12 min / 60 s / 600 | how long a measurement is trusted, and how many are kept |
| `PLAY_PROBE_BUDGET_MS` / `PLAY_PROBE_MAX` | `PROBE_BUDGET_MS` / 6 | how long `POST /app/v1/play` waits, and how many candidates it measures per decision |
| `TRAILER_CLIENT`, `TRAILERS_MAX_HEIGHT`, `TRAILER_BUDGET_MS`, `TRAILER_TTL_MS` | `android` / 720 / 12000 / 30 d | the trailer resolver: which player it asks, the ceiling, how long one request may take, how long a found video is remembered |
| `TRAILER_MISS_TTL_MS`, `TRAILER_RETRY_MS` | 7 d / 12 h | how long a "this title has no trailer" answer is honoured before anyone asks again, and how long a failed lookup is retried after |
| `HERO_MAX` | 5 | how many tiles the billboard may carry |
| `ADDON_MAX_SOURCES` / `ADDON_MIN_SIZE_MB` | 12 / 100 | **seed** `settings.addons.maxSources` / `minSizeMB`: how many copies a picker shows, and how small a file may be before it stops looking like a film. The names these shipped under before they were settings are accepted for one release |
| `DAYS` | 45 | "just added" window |
| `HOME_BUDGET_MS`, `ROW_BUDGET_MS`, `CATALOG_BUDGET_MS`, `META_BUDGET_MS`, `ROW_MAX` | 1500/1200/800/3000/80 | time and size ceilings: `/app/v1/*` answers from cache and never waits on the network beyond these |
| `REMUX_FIRST_BYTE_MS` | 25000 | how long a re-mux waits for the engine to deliver its first byte before admitting the peers are not feeding this copy. It is also published in the manifest as `remux.warmingMs`, because the player decides "still warming" vs "this copy is dead" from this number rather than a guess |
| `ADDON_MANIFEST_RETRY_MS` | 30000 | how soon an addon whose manifest did not arrive at boot may be asked again. A source that was briefly unreachable is revived by the next viewer, not by a restart |
| `MEDIA_PROBE_TIMEOUT_MS` / `MEDIA_PREWARM_TIMEOUT_MS` | 30 s / 90 s | how long one `ffprobe` of one copy may run while a viewer waits on it, and how long the same look gets when nobody is. A file sitting deep inside a cold multi-episode pack costs far more than half a minute to produce its first byte, and a background probe is free to pay that |
| `MEDIA_PROBE_RETRY_FAIL_MS` | 2 min | how soon a failed **background** probe is chased again instead of believed. The failed attempt usually warmed the very copy it timed out on |
| `MEDIA_CONCURRENCY` / `MEDIA_PREWARM_MAX` / `MEDIA_MAX_ENTRIES` | 2 / 3 / 600 | copies probed at once, how many a title page warms in the background, and how much of that the cache keeps |
| `MEDIA_TTL_MS` / `MEDIA_FAIL_TTL_MS` / `MEDIA_PLAY_WAIT_MS` | 30 d / 15 min / ≤1.2 s | how long a track answer is trusted, how long an unreadable copy stays unreadable, and how long a play may wait for one that is already being measured |

## Data

`data/library.json` — every identity the hub has ever seen, with `addedAt` (first sight). Survives
restarts, so "Just added" means new, not "recently re-fetched". `data/progress.json` — per profile.
`data/probe-cache.json` — what each stream URL last answered (latency, rate, seekability) and when it was
last touched; it is a cache, so deleting it costs nothing but the next 1.5 s of measuring.
`data/settings.json` — what the **owner** chose (see `GET`/`PUT /app/v1/settings`): the hub's name, the
content languages, the live-TV playlist, the addon host's limits. Every one of those used to be a constant
or an environment variable, which meant a hub installed by someone else answered questions about their
household on their behalf. Environment variables only seed this file on a hub that has no value for a key
yet; the UI is the place a setting is changed. It ships with **nothing selected**: no languages, no
playlist, so a stranger gets a working hub on first boot and then chooses.
`data/tracks.json` — what `ffprobe` found inside each copy this hub has served (`hash:fileId` → its audio
and subtitle tracks, languages, codecs, and what its picture is encoded in). It is what lets the shelf say
"Hindi audio · E-AC3 5.1" instead of guessing from a filename, and it is what lets the picker refuse a
copy rather than play it badly: this hub never transcodes video, so on a browser with no H.265 decoder an
H.265 file is not lower quality, it is a black screen with working sound. A client declares the codecs it
can draw in `POST /app/v1/play` (`codecs: [...]`, empty for a device that decodes whatever the file holds),
and a copy outside that list is held back for one inside it even when the household ranking preferred it.
A record counts as a measurement only when `ffprobe` exited successfully *and* described at least one
stream: it prints an empty object on stdout when it never managed to open the file, and believing that
would have written "no audio, no subtitles, H.265 unknown" over an accurate description for weeks. A
failed re-read therefore keeps the facts of the last good one, stamped with the probe they came from
(`factsAt`), so a refused connection or an engine 404 costs nothing but the retry.
`data/hls/` — the playlists and segments for one browser asking for HLS; a job nobody asks for any more
loses its `ffmpeg` and its directory within a sweep interval.
**`profiles.json` is not in the repo**: it names real people, so only `profiles.example.json` (generic ids
`adult1`, `kid1`, …) ships, and `deploy-hub.sh` excludes the real file from both directions. A box with no
`profiles.json` boots from the example and logs one line saying so; copy the example and edit it.
`data/profiles.json` — optional, merged over `profiles.json` by profile id, and the place a runtime
per-profile rule override goes:

```json
{ "profiles": [ { "id": "<profile-id>", "rules": { "language-allowlist": { "allow": ["eng"] } } } ] }
```

`data/ratings.json` — the certificate database (one record per identity, forever) and the counter for the
daily upstream cap. `data/live-health.json` — what each stream URL answers plus each channel's fixed number.
`data/trailers.json` — one record per title: which YouTube clip is its trailer (`videoId`, from TMDB or a
search), the last resolved copy of that clip (`url`, `expiresAt`), or a dated "this title has no trailer"
answer so nobody asks again for a week. `tools/backfill-trailers.js` fills it on the server for everything
the addons publish — one metadata call per title, resumable, capped (`--max-calls`), never re-asking a title
it already answered — because a trailer looked up on someone's remote costs 1–9 s of `yt-dlp` on the TV's
clock, while a trailer looked up in advance costs about 2 s of minting the signed URL. The hub re-reads the
file when another process writes it and unions the two, so a backfill can run while the hub is up.
`data/library.json`, `data/progress.json`, `data/addons.json`
and `data/addon-cache.json` are the hub's memory. **`data/` is never rsynced by `deploy-hub.sh`** (nor is
`media/`, the family's own files), so a deploy restarts the code without touching any of it.
`data/keys.json` (mode `600`) holds the OMDb and TMDB keys: it is the only place they exist, the hub prints
their names and never their values, and without it the maturity rule falls back to the genre/title heuristics
instead of failing.
