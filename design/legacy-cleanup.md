# Legacy cleanup — one front end

Scenix has one TV interface: the hub's web UI, loaded in `WebShellActivity` over the native media3
player. The older native screen stack was reachable only by switching a device to "native" mode; it
duplicated the same product and has been removed.

## What was removed

### The native front end

- `plugins/nav/` screens: `ProfilesScreen`, `HomeScreen`, `DetailScreen`, `SearchScreen`,
  `LiveScreen`, `MoviesScreen`, `SeriesScreen`, `NewScreen`, `MyListScreen`, `KidsScreen`,
  `RowScreen`, `SettingsScreen`, `PlayerScreen`.
- `plugins/nav/BrowseHelper.kt` (the shared browse-page/nav renderer for those screens).
- The in-activity screen stack and all navigation verbs it exposed: `show`, `openDetail`, `play`,
  `seedItem`, `playRequest`, `requireProfilePicker`, `enterProfile`, `navScreens`.
- `plugins/cards/` — poster, channel, continue and hero cards, the card menu, billboard and hero
  content.
- `plugins/rows/` — the generic row adapter and the top-10 row.
- `plugins/rules/` — dedupe, hide-rows, kids guard, risk order, continue guard, not-interested.
- `plugins/player/` native player screens/implementation: `PlaybackPanel`, `HttpPlayer`,
  `HlsPlayer`, `YtPlayer`, `ExternalEnginePlayer`.
- `core/PinPad.kt`, `core/CardKit.kt`, `core/Camera.kt`, `core/NavBar.kt`, `core/FocusGlass.kt`,
  `core/Preview.kt`.
- `core/ImageLoader.kt` and its `AppContext`/`ScenixApp` wiring (the web UI loads its own artwork).
- The `Cards`, `Rows`, `Rules` and `Players` registries and their plugin interfaces, and then the
  last `ScreenPlugin` registry too: with one setup screen there is nothing left to look up.
- App-side profile state: the profile id, the local PIN hash, hidden rows, local My List,
  not-interested and autoplay preferences in `Prefs` (the hub owns all of that now).
- Theme, view and formatter helpers that only the deleted screens called.
- The per-device "front end" preference (`interface_mode`, `shellWanted`, `SHELL`/`NATIVE`) and
  the Settings switch that changed it.
- `res/values/ids.xml` (view tags used only by the deleted image loader and card/row renderers), the
  icon drawables and string resources those screens used, and the now-unused RecyclerView dependency.

## What remains

- **Pairing/setup:** `plugins/nav/PairingScreen`, a plain object called by `MainActivity`. A fresh TV
  uses it to find an announcing hub, type an address, or choose standalone. Picking either writes the
  preference and calls `ScreenHost.openShell()`.
- **Launcher:** `MainActivity` builds the app context, applies `hub_url`/`fixture` launch switches,
  runs a single `HubWatch` pass and either shows `PairingScreen` (nothing paired, nothing answers)
  or opens the shell and finishes.
- **Standalone:** `LocalHub` (the `/app/v1` backend, addons included), `LocalServer` (loopback HTTP
  and the bundled `assets/web` page), `AddonClient`, `Prefs`, `Backend`.
- **Hub client:** `HubClient`, `HttpHub`, `FixtureHub`, `HubDiscovery`, `HubWatch`, `Models`.
- **Player bridge:** `WebShellActivity`, `Ceilings`, and the media3 stack under the transparent
  WebView. The shell exposes the native player to the page through `window.Scenix`; the page draws
  the interface and controls.
- **Shared UI/core:** `AppContext`, `ScreenHost`, `Theme`, `Views`, `Focus`, `Format`.

## Deep links

`tv://detail/<identity>` and `tv://play/<identity>` become the page routes
`#/detail/<encoded identity>` and `#/play/<encoded identity>`. `MainActivity` hands the absolute page
address to `WebShellActivity` through its `url` extra, using
`WebShellActivity.shellUrl(context, route)` so hub and standalone mode resolve the same page root.
A link that arrives before setup is kept and opened once the viewer chooses a hub or standalone.
