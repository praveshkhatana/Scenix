# Plugin architecture — "change anything without touching the rest"

Scenix has two plugin hosts and one thin shell:

- **The hub** (`hub/`) is the host for **content**: providers, rows, rules.
- **The web interface** (`hub/web/`) is the host for **presentation**: screens, cards, rows and player
  chrome. It runs in a browser and in the television's WebView from the same files.
- **The Android app** (`app/`) is not a second front end. It finds a backend, hosts that page, and
  provides a native Media3 surface underneath it.

Nothing else is hardcoded. A new live source, a new row, a new card style, a new restriction or a new
screen is one new file plus one registry line; no existing file's logic changes.

## 0. Product shape

```
   addons:  Torrentio · Cinemeta · WatchHub · YouTube · Local Files · OpenSubtitles · <yours>
                              │
                              ▼
                     H U B  (host it anywhere)
                              │
                  serves /app/v1 and the single web UI
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
        TV SHELL          ANY BROWSER      PHONE BROWSER
   (WebView + Media3)     (same page)      (same page)
```

Two rules make that shape possible, and nothing may violate them:

1. **Content enters only through addons.** The hub has no hardcoded source. Cinemeta and Torrentio are
   *installed addons* (seeded in `data/addons.json`), exactly like community ones — added, configured,
   disabled and removed through `GET/POST/DELETE /app/v1/addons`, hot, without a restart.
2. **Clients leave only through `/app/v1`.** The API is versioned, JSON, CORS-enabled and
   client-agnostic: the same endpoints serve the TV shell, a desktop browser and a phone. No payload
   contains a client-specific assumption, and no client-specific word (including the name of the
   ecosystem our addons came from) appears in a response — an addon's own name is data, our product's
   naming is ours.

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

`identity` is the merge key (`imdb:tt…`, else `norm:<slug>-<year>`). No screen ever sees duplicates.

## 2. Hub plugin host (`hub/`)

Auto-loaded by directory scan — **adding a file is the whole integration step**:

```
hub/
  index.js          http server + /app/v1 + addon-protocol compat routes   (never edited for new plugins)
  core/registry.js  readdir providers|rows|rules, validate, sort           (never edited)
  core/store.js     JSON persistence: addedAt, progress, profiles
  core/merge.js     dedupe by identity, rank sources
  providers/*.js    one file per content source
  rows/*.js         one file per home shelf
  rules/*.js        one file per filter/annotation (language, kids, maturity, size, ranking)
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

Shipped providers: `live-tv`, `local-files`, `kids-yt` (YouTube is a provider, not a special case).
Shipped rows are one file each: `continue`, `hero`, `top10`, `justadded`, `newreleases`, `live`,
`genres`, `installed`, `latenight`, the `kids_*` family and the `taste_*` shelves.
Rules: `language_allowlist`, `kids_allowlist`, `maturity`, `size_cap`, `source_ranking`.

HTTP surface (the addon-protocol routes stay, so any addon-protocol client keeps working untouched):

```
GET  /app/v1/manifest                 rows, cards, rules, providers, theme id, capabilities
GET  /app/v1/home?profile=<id>        { rows:[ { id, title, card, items[] } ] }
GET  /app/v1/row/<rowId>?profile=<id> one row (scrolling)
GET  /app/v1/search?q=&profile=       merged + deduped
GET  /app/v1/meta/<identity>          item + all sources + cast + certificate
POST /app/v1/play                     { identity, profile, source? } -> ranked stream answer
POST /app/v1/switch                   same body; ignores the measurement cache
POST /app/v1/progress                 { identity, positionMs, profile }
GET  /app/v1/profiles                 profiles + per-profile prefs and rules
GET  /app/v1/prefs?profile=<id>       per-profile playback and adult switches
POST /app/v1/prefs                    writes those switches (profile in the body)
GET  /app/v1/subs/<identity>          merged subtitles
GET  /app/v1/trailer/<identity>       resolved trailer for previews
GET  /app/v1/settings                 the owner's household settings
GET  /healthz                         per-provider counts and failed plugins
```

Capabilities in the manifest (`gateway`, `live`, `localFiles`, `remux`, `tracks`, `search`, `subtitles`,
`debrid` when a provider token is configured, …) are the **only** thing the interface may branch on.
Anything a backend cannot do is absent from the UI, not broken in it.

## 3. Interface plugin host (`hub/web/`)

The interface is ordinary ES modules served by the hub. Two registries make it extensible:

```
hub/web/js/
  app.js          boot, profile gate, route table, BACK stack     (edited only to add a screen)
  plugins.js      CARDS and ROWS maps + the shared card skeleton  (edited only to add a renderer)
  screens/*.js    home · detail · player · search · settings · profiles · channels ·
                  categories · add · manage
  player.js       the playback element (HTML5 on a browser, window.Scenix on TV)
  video.js        the one decoder used for previews and the player
  stall.js        the freeze/heal rule, shared by browser and TV
  theme.js        tokens from the manifest / CSS custom properties
  css/            app.css, screens.css, hero.css — measured 10-foot layout
```

A screen is added to the lazy `ROUTES` map in `app.js`; a shelf or card is a `registerRow` /
`registerCard` call in `plugins.js`. A new shelf shape therefore does not edit `home.js`, and a hub
that starts sending an unknown `card` falls back to the poster rail instead of breaking the page.

The same files reach every surface:

- a browser loads `http://<hub>:8788/`;
- the TV shell loads the same URL in a WebView;
- standalone serves the bundled copy of the same tree on loopback.

There is no second implementation of Home, Detail, Search or the player to keep in step.

## 4. Android app (`app/`) — shell and setup only

Kotlin, `minSdk 26`, Views (no Compose — 2 GB / 32-bit TV), Media3 ExoPlayer, no analytics.

```
core/               HubProbe · HubDiscovery · HubWatch · Models · Prefs · Format · Views
                    LocalHub · LocalServer · FixtureServer · LoopbackServer · AddonClient
                    TorrentEngine
plugins/nav/        PairingScreen — first-run hub discovery, manual address, or standalone
WebShellActivity    the product: WebView page + Media3 surface + window.Scenix bridge
```

The app holds **no content knowledge and no product screens**. It answers three questions: which
backend, how to paint a video the page asks for, and where a `tv://` link goes. `PairingScreen` is a
plain setup object; the screen/card/rule plugin registries and the in-process `HubClient` went with
the native front end. Standalone and fixture preview are HTTP servers on loopback, not clients the
page can tell apart from a hub.

## 5. What "change anything later" costs in practice

| Change | Touch |
|---|---|
| New live source (another playlist URL) | `hub/providers/x.js` or a settings change. No client change. |
| New "Punjabi Classics" row | `hub/rows/punjabiclassics.js`. No client change. |
| New kids rule (e.g. nothing newer than 1995 for one profile) | `hub/rules/retro.js`. No client change. |
| Restyle the ranked shelf | one `registerRow` in `hub/web/js/plugins.js`. |
| Card look (radius, badge, bigger posters) | `hub/web/css/` + the card renderer. |
| Focus animation / spacing / palette | `hub/web/app.css` tokens, then rebuild the APK only if the bundled offline copy must match. |
| New screen (Sports, Music) | `hub/web/js/screens/x.js` + one line in the `ROUTES` table + a hub row. |
| Swap the torrent provider | provider/addon configuration + `size_cap`. |

Nothing in that list edits an existing plugin's logic — that is the requirement.
