# Native Home / browse / player

The television draws the profile gate, Home, Search, Detail, Settings and Playback itself; the long
tail of settings (addons, sources, household rules) is one row away in the web UI.

```
ProfilesActivity (native) ── PinActivity for a locked profile
        │  profile id
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

  SearchActivity  ── GET /search ──► results grid ──► DetailActivity
  SettingsActivity ── GET/POST /prefs ──► audio + subtitle language, quality ceiling
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
  `/app/v1/play`, `/app/v1/switch`, `/app/v1/subs/<id>`, `/app/v1/progress`, `/app/v1/search` and
  `/app/v1/prefs`, with the same optional Basic token as the shell. No native screen parses or ranks
  sources.
- `ProfilesActivity` is the front door. It reads `/app/v1/profiles`, draws the same household faces
  the web gate does, and asks `PinActivity` for a 4-digit code on a locked profile; the hub checks
  the PIN (`POST /app/v1/profiles/<id>/unlock`), never the TV. A chosen profile opens
  `BrowseActivity`; the Browse top bar's Profile action returns to the same gate with CLEAR_TOP so a
  person switch replaces the old shelf instead of stacking a second Home.
- `BrowseActivity` receives the profile id from that gate. It never guesses a profile: a missing id
  sends the viewer back to the gate.
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
- The web page still calls `Scenix.openNativeBrowse(profile)` when it mounts Home, so a browser
  session or a deep link can hand back to native; the native gate is the normal TV launch path.
- The web Home action, or BACK at the page root, returns to the native activity underneath. Web
  Search and Settings are unchanged. The WebShell player route remains for browser UI/deep links;
  native Detail never opens it.

## Rendering

- `BrowseActivity` — top bar (Profile/Search/Settings), a vertical RecyclerView whose first item is
  the hero and whose remaining items are shelves. Search opens `SearchActivity`; Settings opens
  `SettingsActivity`; neither drops into a WebView.
- `SearchActivity` — a remote A-Z keyboard (space, delete) beside a 4-column poster grid. A query
  goes to `/app/v1/search` after a 320 ms pause, results are drawn with the same card/focus language
  as Home, and OK opens native Detail. Back clears the query first, then leaves.
- `SettingsActivity` — audio language, subtitle language, quality ceiling, the profile gate, and one
  "Everything else / Open the web settings" row for addons, sources and household rules. It reads
  and writes the same `/app/v1/prefs` the web page does; choosing a language moves it to the front
  of the existing list instead of replacing the fallbacks. Ranked rows draw their numeral over the poster;
  Continue Watching draws its position line; posters and landscape rows are sized from the card kind.
- `DetailActivity` — native hero, metadata/advisories/overview, cast strip and episode list with
  stills. Long titles are capped at 1100 px and get a smaller size, and a record with no maturity or
  tagline shows its genres instead. Every string read from the hub treats an explicit `null` as
  absent (`core.Models.s()`), so "null" cannot become a visible word.
- `PlayerActivity` — `PlayerView` with `useController=false`, Media3 ExoPlayer built with
  `Ceilings.loadControl`, `Ceilings.seekIncrements` and `Ceilings.applyDefaults`; source headers are
  installed on `DefaultHttpDataSource` and the optional hub token is attached only to hub-origin
  URLs, never to a third-party stream.
- The chrome is a native overlay: back, title, certificate/advisories, buffering state, play/pause,
  a drawn scrubber (elapsed/remaining), and Audio, Subtitles (including Off), Speed and
  Quality/Source panels built from the same `Focus`/`Views` language as Home. The transport is a row
  of flat drawn icons (play/pause, -10 s, +30 s, speaker, CC, speed gauge, sliders), not word chips;
  a small dot marks a chosen audio track, subtitles-on and a non-1x speed. No platform dialog styling
  is used.
- Subtitles come from the play answer's `subtitleTracks` (embedded) and `GET /app/v1/subs` (side-
  loaded files). Audio, subtitle and speed choices survive a source switch; a subtitle that arrives
  after the picture started is added by reloading the same copy at the same playhead once.
- When the hub mixes in a language the picture does not carry (`audioMix`), the header says
  "Hindi audio", the Audio sheet names the mixed row by language and its donor, and an
  **Original audio** row re-requests `/play` with `audioMix:false` at the same playhead. A row turns
  the mix back on after that.
- Chrome appears on any key and fades after `player.controlsHideMs` (default 6 s) while a film is
  playing. An open menu, error card, next-episode card or undo chip pins it; the ring is moved off
  the bar before the fade so nothing is hidden while focused.
- A live feed hides the scrubber, speed and ±seek buttons. The remote's CH Up/Down (`CHANNEL_UP` /
  `CHANNEL_DOWN`) and media next/previous always zap the live row from `/app/v1/home`, whatever the
  chrome is doing; at rest, left/right does the same. With the chrome up, left/right walks the button
  row so Audio/Subtitles stay reachable. A hub with no channel row makes zap a clean no-op.
- A series ending shows a native next-episode card. The theme's `player.autoplayCountdownMs`
  (default 7 s) rolls into the next episode, with an Undo chip that returns to the finished one.
- Each shelf is a horizontal RecyclerView; cards are `FrameLayout`s with an ImageView, a title scrim
  and the existing `Focus` ring/scale. Focus is native, not a WebView focus engine.
- `BrowseImages` uses a 48 MB LRU cache, four workers, `RGB_565`, one HTTP download per image (bounds
  decode then sampled decode), and a tag check so a recycled card cannot show the previous poster.
- Rows are recycled by RecyclerView; card views are recycled horizontally.

## Focus

- `Focus` paints its white ring as a background layer (`LayerDrawable` over the view's own
  background), not as a foreground drawable: a `TextView`'s foreground never reached this Android 11
  panel, so the Resume button and every player chip looked unfocused while holding focus.
- `PlayerView` is explicitly non-focusable. With the controller off it draws nothing when focused,
  so the D-pad used to land on an invisible full-screen stop and the transport looked dead.
- Shelves take the lift in the frame the ring lands; animated lifts are capped at 100 ms because a
  1080p re-composite for a dozen frames is what missed deadlines on the Mali-G31.
- The image loader returns early when a card already shows the URL, so a rebind does not clear and
  re-upload a cached poster.

## What this does not solve

- The long tail of Settings (addons, sources, categories, PINs, household rules) is still the web
  UI, reached by one native row. The TV-relevant preferences are native.
- Skip-intro now has a source: `core/introdb.js` asks IntroDB's keyless
  `GET /segments?imdb_id=&season=&episode=` for a series episode (cached, 1.2 s budget) and the play
  answer carries `introStartMs`/`introEndMs`. The native player shows its "Skip intro" chip exactly
  inside that window and one OK press seeks to its end; an episode with no known window shows nothing.
- Native Search matches the hub's index; a search for a title the hub does not know returns nothing,
  as on the web.
- Trailer previews on dwelling cards remain a web-only behaviour; native cards show stills.
- Side-loaded subtitles over an HLS/DASH copy depend on Media3's behaviour; embedded tracks and
  direct-HTTP subtitle files are the tested path. Debrid needs a live real-debrid token to verify.
- A 3-hour film on a 2 GB panel is still bounded by the same decode/load ceilings in `Ceilings` and
  `theme.json`'s `player` group; changing the TV is a config edit, not a new player.
