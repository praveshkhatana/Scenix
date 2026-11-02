# Add-ons

Content enters the hub only through add-ons (the contract lives in `core/addons.js`). A **native**
add-on is a file in `providers/` loaded at boot;
an **external** one is installed at runtime into `data/addons.json` — no code, no restart. An
external add-on speaks the community manifest protocol: `GET /manifest.json`, `/catalog/…`,
`/meta/…`, `/stream/…`, `/subtitles/…`, with its own configuration riding in the URL path, so the
base of every request is the manifest URL minus `/manifest.json`.

## Game sources from an add-on

The Game mode reads the same installed list. Every enabled **external** add-on is additionally
asked for one plain file, no protocol beyond static JSON:

```
GET <addon-base>/games.json
```

```json
{
  "rows": [
    {
      "id": "popular",
      "title": "Popular right now",
      "items": [
        {
          "id": "smash-karts",
          "title": "Smash Karts",
          "playUrl": "https://games.crazygames.com/en_US/smashkarts/index.html",
          "fallbackUrl": "",
          "poster": "https://…/cover.jpg",
          "pad": true,
          "kb": true,
          "touch": false,
          "tags": ["racing"],
          "desc": "Go-kart arena shooter."
        }
      ]
    }
  ]
}
```

Rules, as enforced by `core/games.js`:

- An item needs a non-blank `id`, `title`, and an http(s) `playUrl`; a row needs at least 3 valid
  items or it is dropped. Max 8 rows and 60 items per row per add-on; bodies over 4 MB are ignored.
- `pad` shows the GAMEPAD badge (the page answers a real controller); `kb` defaults to true — set it
  false when even arrows are useless on the screen.
- `fallbackUrl` is a second address for titles whose first host refuses this region: the player
  hops to it once, automatically, on a connection error, an HTTP 403/451, or block-text on the page.
- Shelves ride `/app/v1/games` after the built-in ones, named `ga-<addonId>-<rowId>`, with card
  identities `game:<addonId>:<itemId>` so they never collide with seeded games.
- Fetches are lazy with a small cache: a good answer rides for 6 h, a missing file is retried only
  after 10 min, and each try is bounded by `GAMES_ADDON_TIMEOUT_MS` (default 6000 ms). A dead
  add-on costs nothing — the Games shelves simply stop carrying its rows.
