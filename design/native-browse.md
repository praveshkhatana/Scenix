# Native Home / browse / player

The television draws Home, Detail and Playback itself; Search and Settings stay on the merged web
interface.

```
profile gate (web)
        │  Scenix.openNativeBrowse(profile)
        ▼
  BrowseActivity  ── GET /app/v1/home ──►  hub / LocalServer
        │
        │ tile
        ▼
  DetailActivity  ── GET /app/v1/meta/<identity> ──►  hub / LocalServer
        │
        │ Play / episode row
        ▼
  PlayerActivity  ── POST /play | /switch | /progress, GET /subs ──►  hub / LocalServer
        │
        │ BACK
        ▼
  DetailActivity
```

## Why

Profiling the 2 GB VIZIO/VU1 panel showed the focus handler under 1 ms; the cost was WebView
scroll/raster. A native vertical RecyclerView of horizontal RecyclerViews reuses about 30–50 card
views instead of rebuilding a 262-card page, and moves a compositor layer for scrolling. It is the
only way to approach native Apple-TV/Netflix smoothness with this hardware.

Playback has the same argument at a different scale: the decoder was already native Media3, but the
chrome above it was a transparent WebView. Native chrome removes a composited full-screen layer,
makes every control a real focus stop, and lets the owner tune the remote behaviour without a page
reload.

## Contract

- `BrowseApi` is the only native API path. It calls `/app/v1/home`, `/app/v1/meta/<id>`,
  `/app/v1/play`, `/app/v1/switch`, `/app/v1/subs/<id>` and `/app/v1/progress`, with the same
  optional Basic token as the shell. No native screen parses or ranks sources.
- `BrowseActivity` receives the profile id from the web profile gate. It never guesses a profile: a
  missing id sends the viewer back to the web gate.
- `DetailActivity` receives the profile id and identity from a tile. Play builds a
  `PlayerActivity` intent with the profile, identity, type, title, season/episode, resume position,
  certificate and advisories. BACK in the player returns to the title page it came from.
- The player sends the same `/app/v1/play` body the web player does: `identity`, `profile`, `type`,
  `season`, `episode`, `positionMs`, `container: "any"` (the TV plays the copy's own container, so
  E-AC3/DTS passthrough survives) and, for a switch, `source` and `exclude`.
- Playback failures ask `/app/v1/switch` again with the tried copies excluded. A `503 warming`
  answer is a wait and retry at the hub's `retryAfterMs`, never an error card.
- Progress is posted every `player.progressPostMs` (default 10 s), on pause and on exit, attributed
  to the copy actually loaded while a new request is in flight.
- The page calls `Scenix.openNativeBrowse(profile)` when it mounts Home. If the page was opened from
  native, it simply finishes back to the existing BrowseActivity; otherwise it starts BrowseActivity
  and finishes.
- The web Home action, or BACK at the page root, returns to the native activity underneath. Web
  Search and Settings are unchanged. The WebShell player route remains for browser UI/deep links;
  native Detail never opens it.

## Rendering

- `BrowseActivity` — top bar (Search/Settings), a vertical RecyclerView whose first item is the hero
  and whose remaining items are shelves.
- `DetailActivity` — native hero, metadata/advisories/overview, cast strip and episode list.
- `PlayerActivity` — `PlayerView` with `useController=false`, Media3 ExoPlayer built with
  `Ceilings.loadControl`, `Ceilings.seekIncrements` and `Ceilings.applyDefaults`; source headers are
  installed on `DefaultHttpDataSource` and the optional hub token is attached only to hub-origin
  URLs, never to a third-party stream.
- The chrome is a native overlay: back, title, certificate/advisories, buffering state, play/pause,
  a drawn scrubber (elapsed/remaining), and Audio, Subtitles (including Off), Speed and
  Quality/Source panels built from the same `Focus`/`Views` language as Home. No platform dialog
  styling is used.
- Subtitles come from the play answer's `subtitleTracks` (embedded) and `GET /app/v1/subs` (side-
  loaded files). Audio, subtitle and speed choices survive a source switch; a subtitle that arrives
  after the picture started is added by reloading the same copy at the same playhead once.
- Chrome appears on any key and fades after `player.controlsHideMs` (default 6 s) while a film is
  playing. An open menu, error card, next-episode card or undo chip pins it; the ring is moved off
  the bar before the fade so nothing is hidden while focused.
- A live feed hides the scrubber, speed and ±seek buttons. At rest, left/right zaps the channel row
  from `/app/v1/home` when the hub has one; with the chrome up, left/right walks the button row so
  Audio/Subtitles remain reachable. A hub with no channel row makes zap a clean no-op.
- A series ending shows a native next-episode card. The theme's `player.autoplayCountdownMs`
  (default 7 s) rolls into the next episode, with an Undo chip that returns to the finished one.
- Each shelf is a horizontal RecyclerView; cards are `FrameLayout`s with an ImageView, a title scrim
  and the existing `Focus` ring/scale. Focus is native, not a WebView focus engine.
- `BrowseImages` uses a 48 MB LRU cache, four workers, `RGB_565`, one HTTP download per image (bounds
  decode then sampled decode), and a tag check so a recycled card cannot show the previous poster.
- Rows are recycled by RecyclerView; card views are recycled horizontally.

## What this does not solve

- Search and Settings still pay the WebView cost. Home, Detail and Playback are native.
- A native profile chooser is still a follow-up; Home receives a profile from the web gate, and the
  player trusts that profile for the session (`Prefs.profileUnlocked`).
- Skip-intro is not native: the hub's play answer has no intro window, so no button is invented.
- Trailer previews on dwelling cards remain a web-only behaviour; native cards show stills.
- A 3-hour film on a 2 GB panel is still bounded by the same decode/load ceilings in `Ceilings` and
  `theme.json`'s `player` group; changing the TV is a config edit, not a new player.
