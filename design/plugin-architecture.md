# Plugin architecture — "change anything without touching the rest"

Two plugin hosts, one contract between them:

- **The hub** (`work/iptv/hub/`) is the host for **content**: providers, rows, rules.
- **The app** (`work/sony-bravia-vu1/app/`) is the host for **presentation**: cards, screens, player behaviour, theme.

Nothing else is hardcoded. A new live source, a new row, a new card style, a new restriction — each is one new
file plus one registry line; no existing file's logic changes.

## 0. Product shape

```
   addons:  Torrentio · Cinemeta · WatchHub · YouTube · Local Files · OpenSubtitles · <yours>
                              │
                              ▼
                     H U B  (host it anywhere)
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
          TV APP          WEB APP         MOBILE APP
```

Two rules make that shape possible, and nothing may violate them:

1. **Content enters only through addons.** The hub has no hardcoded source. Cinemeta and Torrentio are
   *installed addons* (seeded in `data/addons.json`), exactly like community ones — added, configured,
   disabled and removed through `GET/POST/DELETE /app/v1/addons`, hot, without a restart.
2. **Clients leave only through `/app/v1`.** The API is versioned, JSON, CORS-enabled and client-agnostic:
   the same endpoints serve the TV app, a web player and a phone. No payload contains a client-specific
   assumption, and no client-specific word (including the name of the ecosystem our addons came from)
   appears in a response — an addon's own name is data, our product's naming is ours.

## 1. Item model (the only shared data shape)

```jsonc
{
  "identity": "imdb:tt1234567",        // merge key; fallback "norm:<slug>-<year>"
  "type": "movie | series | channel | video",
  "title": "…", "year": 2026,
  "poster": "https://…", "backdrop": "https://…", "logo": "https://…",
  "match": 98, "maturity": "16+", "durationMs": 8040000,
  "languages": ["hin","eng","pan"], "genres": ["Action"],
  "addedAt": 1761300000000,            // first sweep the hub saw it
  "releasedAt": 1767225600000,         // from Cinemeta
  "inCinemas": true,                   // released within 8 weeks
  "live": { "now": "Match", "quality": "720p" },
  "progress": { "positionMs": 0, "watchedMs": 0 },
  "sources": [                         // dedupe result: everything behind ONE tile
    { "provider": "torrentio", "label": "1080p · 3.9 GB", "kind": "torrent",
      "quality": "1080p", "sizeBytes": 4188000000, "risk": "0.7x" },
    { "provider": "hub-yt", "label": "720p", "kind": "http" }
  ]
}
```

`identity` is the merge key (`imdb:tt…`, else `norm:<slug>-<year>`). The app never sees duplicates.

## 2. Hub plugin host (`work/iptv/hub/`)

Auto-loaded by directory scan — **adding a file is the whole integration step**:

```
hub/
  index.js          http server + /app/v1 + stremio compat routes   (never edited for new plugins)
  core/registry.js  readdir providers|rows|rules, validate, sort     (never edited)
  core/store.js     JSON persistence: addedAt, progress, profiles
  core/merge.js     dedupe by identity, rank sources
  providers/*.js    one file per content source
  rows/*.js         one file per home row
  rules/*.js        one file per filter/annotation (language, kids, cap)
```

Contracts (CommonJS, plain objects — no framework):

```js
// provider
module.exports = {
  id: 'iptv-org', kind: 'channel', priority: 10,
  async catalogs() { return [{ id: 'in_live', name: 'India Live TV', extra: ['search','genre'] }] },
  async catalog(catalogId, extra) { return [item] },
  async meta(identity) { return item },
  async play(identity, source) { return { url, headers, kind, expiresAt } }
}

// row -> the home screen is these, sorted by `order`
module.exports = {
  id: 'justadded', title: 'Just added to your library', card: 'poster', order: 30,
  scope: 'all',                        // 'all' | 'adult' | 'kids'
  async build(ctx) { return [item] }   // ctx: { profile, providers, store, rules, days }
}

// rule -> applied to every item; return null to drop it
module.exports = {
  id: 'language-allowlist', order: 10,
  async apply(item, ctx) { return item }
}
```

Rows to ship: `continue` (10), `top10` (20), `justadded` (30), `newreleases` (40), `live` (50),
`kids.yt` (20/kids), `kids.school` (30/kids). Rules: `language-allowlist`, `kids-allowlist`, `size-cap`.
Providers: `iptv-org`, `hub-yt` (the existing YouTube code, moved), `stremio` (proxy to Cinemeta /
size-capped Torrentio / WatchHub for titles, configured by a URL list).

HTTP surface (the Stremio addon routes stay, so Stremio keeps working untouched):

```
GET  /app/v1/manifest                 rows, cards, rules, providers, theme id
GET  /app/v1/home?profile=<id>        { rows:[ { id, title, card, items[] } ] }
GET  /app/v1/row/<rowId>?profile=<id> one row (scrolling)
GET  /app/v1/search?q=&profile=       merged + deduped
GET  /app/v1/meta/<identity>          item + all sources
POST /app/v1/play                     { identity, provider } -> { url, headers, kind }
POST /app/v1/progress                 { identity, positionMs, profile }
GET  /app/v1/profiles                 profiles + per-profile rules
GET  /healthz                         per-provider counts
```

## 3. App plugin host (`work/sony-bravia-vu1/app/`)

Kotlin, `minSdk 26`, Views + RecyclerView (no Compose — 2 GB / 32-bit TV), Media3 ExoPlayer, no analytics.

```
core/    Plugin.kt (the interfaces) · Registry.kt (typed maps sorted by order)
         HubClient.kt · Models.kt · Theme.kt (tokens from JSON)
plugins/ rows/   RowPlugin(id, cardType, layout, fetch)
         cards/  CardPlugin(cardType, viewHolderFactory)   poster | channel | continue | hero
         rules/  RulePlugin(applyItem | applyRow)          client-side: hide-rows, dedupe guard
         player/ PlayerPlugin(kind)                        hls | dash | http | torrent
         nav/    ScreenPlugin(id, title, route)            Home | Live | Movies | Series | Kids | Search
         theme/theme.json                                  Netflix-dark tokens
PluginCatalog.kt  <- the ONE file naming built-ins; new plugin = new file + one line here
```

The app renders whatever `/app/v1/home` returns and holds **no content knowledge at all** — no channel
lists, no genres, no source names. A new row or source shows up with no app change at all; only a brand-new
*card visual* needs a rebuild.

## 4. What "change anything later" costs in practice

| Change | Touch |
|---|---|
| New live source (another playlist URL) | `hub/providers/x.js` + restart hub. No app change. |
| New "Punjabi Classics" row | `hub/rows/punjabiclassics.js`. No app change. |
| New kids rule (e.g. nothing newer than 1995 for one profile) | `hub/rules/retro.js`. No app change. |
| Card look (radius, badge, bigger posters) | `app/plugins/cards/` + `theme.json`. |
| Focus animation / spacing / light theme | `theme.json` only. |
| Swap the torrent provider | provider file + `size-cap` rule. |
| New screen (Sports, Music) | `app/plugins/nav/` + a hub row. |

Nothing in that list edits an existing plugin's logic — that is the requirement.
