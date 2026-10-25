# iptv hub

The content side of CleanYourTV: one Node process, Node built-ins only, that merges three content
sources into one item model and serves it to the Android TV app **and** to the Stremio app on the
same TV, at the same time.

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
| a new household member | a line in `profiles.json` |

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
  stats(),                                    // for /healthz
  stremio: {                                  // optional: expose yourself on the Stremio routes
    idPrefixes: ['x___'], catalogIds: ['x_all'],
    manifestPart(), catalog(type, id, extra), meta(stremioId), stream(stremioId), ensure(),
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

### Rule — `rules/x.js`
```js
module.exports = { id: 'x', order: 30, async apply(item, ctx) { return item; /* or null to drop */ } };
```
Rules run in `order` on **every** item of **every** row, so a row added later cannot bypass them.

## Routes

| | |
|---|---|
| `GET /healthz`, `GET /` | per-provider and per-row counts, loaded and **failed** plugins |
| `GET /app/v1/manifest` | rows, cards, providers, rules, theme id |
| `GET /app/v1/profiles` | profiles and the rows each one sees |
| `GET /app/v1/home?profile=<id>` | `{ rows: [{ id, title, card, items: […] }] }` |
| `GET /app/v1/row/<id>?profile=<id>` | one row, for the app's "see all" |
| `GET /app/v1/search?q=<q>&profile=<id>` | every provider's search, merged, deduped |
| `GET /app/v1/meta/<identity>?profile=<id>` | one merged tile (403 if a rule drops it for that profile) |
| `GET /app/v1/subs/<identity>?profile=<id>&lang=hin,en` | merged subtitles (403 if the title is hidden) |
| `GET /app/v1/trailer/<identity>?profile=<id>` | `{url, headers, kind, expiresAt}` or 404, resolved by `yt-dlp` and cached in `data/trailers.json` |
| `POST /app/v1/play` `{identity, provider?, source?}` | `{url, headers, kind, expiresAt}` |
| `POST /app/v1/progress` `{profile, identity, positionMs, watchedMs}` | remembered in `data/progress.json`; **refused (403) for a tier-3 title while the adult switch is off** |
| `GET`/`POST /app/v1/prefs?profile=<id>` | per-profile `qualityCap` and `xxxEnabled`, with the defaults the app should show |
| `GET /manifest.json`, `/catalog/`, `/meta/`, `/stream/` | the Stremio addon — same ids (`inlive___`, `ytv___`), byte-identical bodies |

Every route that names a profile accepts **both** `profile=` and `profileId=` (and `profile`/`profileId` in a
JSON body), because the app has used both spellings and a route that accepts only one silently stops
filtering.

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

## Environment

| Var | Default | What it does |
|---|---|---|
| `PORT` | 8788 | listen port |
| `LOG_REQUESTS` | off | `REQ <method> <url> -> <code>` per request |
| `LANGS` | `hin,eng,pan` | iptv-org playlists **and** the language allow-list (`""` disables the rule) |
| `KIDS_BLOCK` | `Animax,MediBizTV,…` | kid-grouped channels that are still not for kids |
| `YT_BIN` | `yt-dlp` | yt-dlp path; `YT_JS` (its `--js-runtimes`), `YT_CLIENT` (`android`), `YT_MAX`, `YT_CHANNELS` |
| `CINEMETA_URL`, `TORRENTIO_URL` | v3-cinemeta, tuned torrentio | stremio upstreams (`TORRENTIO_URL` carries the language + `sizefilter` tuning) |
| `SIZE_CAP_GB` | 4 | hard per-source cap in `rules/size_cap.js` (the per-profile cap in `prefs` is what usually binds) |
| `QUALITY_CAP`, `KIDS_QUALITY_CAP` | `1080p` / `720p` | default `qualityCap` for adult and kids profiles (`auto` disables the ceiling) |
| `RATINGS_DAILY_CAP` | 800 | upstream certificate lookups per day, counted in `data/ratings.json`; browsing spends it, the backfill stops at it |
| `RATINGS_BUDGET_MS` | 2500 | how long a request may wait on one cold certificate lookup |
| `RATINGS_FILE`, `RATINGS_KEYS_FILE` | `data/ratings.json`, `data/keys.json` | the certificate database and the API keys (`{"omdb":…,"tmdb":…}`, keep it `600`) |
| `LIVE_HEALTH_TIMEOUT_MS` / `_CONCURRENCY` / `_STALE_MS` | 6000 / 8 / 24 h | the live prober: how hard it probes, and after what age a "dead" verdict is a rumour again. Channel numbers are assigned once and stay put |
| `TRAILER_CLIENT`, `TRAILERS_MAX_HEIGHT`, `TRAILER_BUDGET_MS`, `TRAILER_TTL_MS` | `android` / 720 / 12000 / 30 d | the trailer resolver: which player it asks, the ceiling, how long one request may take, how long a found video is remembered |
| `STREMIO_MAX_SOURCES` / `STREMIO_MIN_SIZE_MB` | 12 / 100 | per-tile torrent pick list |
| `DAYS` | 45 | "just added" window |
| `HOME_BUDGET_MS`, `ROW_BUDGET_MS`, `CATALOG_BUDGET_MS`, `META_BUDGET_MS`, `ROW_MAX` | 1500/1200/800/3000/80 | time and size ceilings: `/app/v1/*` answers from cache and never waits on the network beyond these |

## Data

`data/library.json` — every identity the hub has ever seen, with `addedAt` (first sight). Survives
restarts, so "Just added" means new, not "recently re-fetched". `data/progress.json` — per profile.
`data/profiles.json` — optional, merged over `profiles.json` by profile id, and the place a runtime
per-profile rule override goes:

```json
{ "profiles": [ { "id": "raj", "rules": { "language-allowlist": { "allow": ["eng"] } } } ] }
```

`data/ratings.json` — the certificate database (one record per identity, forever) and the counter for the
daily upstream cap. `data/live-health.json` — what each stream URL answers plus each channel's fixed number.
`data/trailers.json` — resolved trailer URLs. `data/library.json`, `data/progress.json`, `data/addons.json`
and `data/addon-cache.json` are the hub's memory. **`data/` is never rsynced by `deploy-hub.sh`** (nor is
`media/`, the family's own files), so a deploy restarts the code without touching any of it.
`data/keys.json` (mode `600`) holds the OMDb and TMDB keys: it is the only place they exist, the hub prints
their names and never their values, and without it the maturity rule falls back to the genre/title heuristics
instead of failing.
