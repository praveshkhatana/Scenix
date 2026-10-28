# Categories — shelves the owner adds without a plugin file

Home rows are still plugin files under `hub/rows/*.js`, and the owner can still hide and reorder them in
Settings. Categories are the second half of that idea: a shelf built from a catalogue (and optionally a
genre) that an installed provider already publishes, created from the web UI rather than by adding a
file to the hub.

## Stored shape

`data/settings.json` gains one neutral, empty-by-default list:

```jsonc
{
  "categories": [
    {
      "id": "live-tv-in-live-news",   // stable, URL-safe; generated when the UI omits it
      "title": "News",                 // what the shelf header says
      "provider": "live-tv",           // a provider id from the registry
      "catalog": "in_live",            // that provider's catalogue id
      "genre": "News",                 // "" means the whole catalogue
      "enabled": true,                 // false keeps it saved but off Home
      "order": 0                       // position among categories
    }
  ]
}
```

`hub/core/settings.js` normalizes this on every read and write:

- an entry without a `provider` or `catalog` is dropped — a half-written row never reaches Home;
- `id` is kept when it is already safe, otherwise generated from provider/catalogue/genre, and made
  unique with a numeric suffix (two "Action" shelves are still two shelves);
- `title` falls back to `genre · catalogue`, then `Category`;
- `enabled` is true unless explicitly `false`;
- the list is sorted by `order` and renumbered `0..n-1`.

Defaults are empty. No provider, catalogue, genre or household label is a constant in the build.

## Rows on `/app/v1/home`

Each enabled category becomes a virtual row with id `category:<id>` and the same shape as a plugin row
(`id`, `title`, `card`, `order`, `scope`, `items`). It is appended **after** the plugin rows and walks the
same pipeline:

```
provider catalogue (ctx.catalog, existing CATALOG_BUDGET_MS)
  -> dedupe / addedAt / sources        (pipeline)
  -> rules in registry order           (maturity, language, kids, …)
  -> trim to HOME_ROW_MAX              (after the rules run)
```

Failure is not exceptional:

- an unknown or removed provider returns `[]` from `ctx.catalog`;
- a slow one is cut off by `CATALOG_BUDGET_MS`, then by the row's `HOME_BUDGET_MS`;
- a throwing provider, a cold cache, or rules that reject every item leave an empty row, and the home
  route drops empty rows exactly as it already does for plugins.

`card` is `channel` when the catalogue is a TV/channel catalogue (or its provider is a channel
provider), otherwise `poster`. `scope` is `all`, like ordinary all-scope plugin rows, so a kids profile
still sees only the hub's kids rows; the rules additionally filter every item on the adult shelves.

### Interaction with `rows.hidden` / `rows.order`

Both existing settings keep working unchanged:

- `rows.hidden` may contain a virtual id (`category:<id>`) and the row is skipped by the same
  `rowVisible()` check as a plugin row;
- `rows.order` is applied to the finished list, so it may place a category anywhere if the owner edits
  it by hand. When it does not mention categories, the home route appends them after the plugin rows in
  their own `order` sequence. This is why the Categories screen reorders `categories[].order` and never
  rewrites `rows.order`: the existing Home rows screen continues to own the plugin row order, and a
  category can never make a plugin row disappear.

`GET /app/v1/row/category:<id>` answers the same row shape, so the "See all" tile on a category shelf
opens the whole shelf in Search.

## Endpoints

`GET /app/v1/settings` now returns, alongside the existing keys:

```jsonc
{
  "categories": [ /* normalized settings.categories */ ],
  "catalogOptions": [
    {
      "provider": "live-tv",
      "providerName": "Live TV playlists",
      "catalog": "in_live",
      "name": "Live TV",
      "type": "tv",
      "card": "channel",
      "genres": ["News", "Sports", "Movies"]   // empty when the catalogue states none
    }
  ]
}
```

`catalogOptions` is built from whatever is mounted at request time:

- every provider that implements both `catalogs()` and `catalog()`, each answer bounded by
  `CATALOG_OPTIONS_BUDGET_MS`;
- the provider's display name comes from the provider, the catalogue name from the catalogue;
- genres come from the catalogue descriptor's `genreOptions`, or from the addon-protocol form
  `extra: [{ "name": "genre", "options": [...] }]`. A catalogue that states no genres simply offers
  "Any genre". Nothing in `index.js` names a provider or a genre.

Native providers can state their real genres the same way. `hub/providers/live-tv.js` now returns
`type: "tv"` and `genreOptions: genres()` on its live catalogue (the groups its playlist actually
fetched). External addons expose the `genreOptions` their manifest already carried through
`hub/core/addons.js`. A future provider needs no hub change: return the optional field from
`catalogs()`.

`PUT /app/v1/settings` accepts `{ "categories": [...] }` (or the same object wrapped in `settings`). The
UI sends only that key, so a concurrent language or playlist change is not overwritten with a stale
copy of the whole settings object.

## Web UI

- `hub/web/js/screens/settings.js` gains a **Categories** group beside **Home rows** and **Live TV**:
  a count and a button into the screen. It appears on hubs where categories or catalogue options exist.
- `hub/web/js/screens/categories.js` is registered as the `categories` route in
  `hub/web/js/app.js`. It has three parts:
  - an add row of focusable chips: **Source → Catalogue → Genre** ("Any genre" included), then
    **Add category**. The new title defaults to the genre, else the catalogue name, with a numeric
    suffix if that name is already used;
  - one row per saved category with a switch (show on Home), up/down arrows, **Rename** (through the
    existing `ctx.enterText` modal — no bare text field on a TV) and delete;
  - a status line reporting how many are on Home.
- Every action awaits `PUT /app/v1/settings` and redraws from the returned settings, so the list shows
  what the hub stored. Home reflects it on its next load.
- Focus is TV-first: chips use the existing focusable-chip language, rows are focusable controls with
  no drag gestures, and the screen's default focus is the first chip (or the first switch when no
  catalogue is available).

A category whose addon is later disabled stays in the list marked "not published right now": it can be
renamed, reordered, turned off or deleted, and it produces no Home row until a provider with that id and
catalogue answers again.
