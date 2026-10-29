# Scenix TV app

A native Android TV front end for the Scenix hub: Home, Detail and Playback are native; Search and
Settings are the hub's own web pages. It is built for a Sony BRAVIA (Android 11, armeabi-v7a, 2 GB),
but runs on Android 8+ / Android TV.

```
JAVA_HOME=<jdk17> ANDROID_HOME=<sdk> ./gradlew --no-daemon assembleDebug
# build/outputs/apk/debug/app-debug.apk
```

Nothing is installed or deployed from here.

## What the app is

- **Setup** — `MainActivity` probes for a hub. One that answers is opened at once; if none does,
  `PairingScreen` lists the hubs heard on the LAN, accepts a typed address, or starts the bundled
  standalone host. An optional **access token** field is stored for a hub that sets `HUB_TOKEN`; the
  WebView supplies it through the normal Basic-auth challenge, so the page never sees it; the native
  player attaches the same token to `/app/v1` calls and to hub-origin media URLs only. Setup is
  native; Home, Detail and Playback are native; Search and Settings are the web UI. `tv://setup` (or
  `--ez setup true` on an adb launch) reopens it on a TV that already has a backend; the page can do
  the same through `Scenix.openSetup()`.
  `--ez standalone true` chooses standalone directly, which is useful when testing both shapes without
  clearing data; `--es token …` sets the access token for a test launch.
- **Native profiles, Home, Detail and Player** — `ProfilesActivity` reads `/app/v1/profiles`, draws
  the household with the hub's faces and sends a locked profile through the native `PinActivity`
  keypad; the hub checks the PIN. `BrowseActivity` reads the same `/app/v1/home` rows and draws the
  shelves in native vertical/horizontal RecyclerViews, with ranked numerals, Continue Watching
  progress and episode stills. `DetailActivity` draws the title record (cast, advisories, episodes)
  from `/app/v1/meta`. `PlayerActivity` asks `/app/v1/play`, decodes the answer with Media3 and draws
  the remote-first chrome itself: flat icon transport, Audio/Subtitles/Speed/Quality sheets, CH
  Up/Down zapping on live TV, and a next-episode roll with Undo. Play no longer hands off to the web
  player. See [../design/native-browse.md](../design/native-browse.md).
- **Native Search + Settings** — `SearchActivity` is a remote A-Z keyboard over the hub's
  `/app/v1/search` index, with the same poster cards and focus ring as Home. `SettingsActivity`
  covers audio language, subtitle language, quality ceiling, a native Home-rows editor (this
  profile's hide/show and order), a native Live TV lineup (this profile's channels and zap order),
  the profile gate, native Source packs (IGC/audio donors), Add-ons
  (enable/disable/remove/add), and Debrid (provider, switch, write-only token). Home rows and the
  live lineup are per profile; audio/subtitles/quality always were. Add-ons, source packs, Debrid and
  the live playlist are household resources. There is no "open the web settings" path from the TV;
  `TextEntryActivity` handles the few URL/token fields.
- **The web product** — `WebShellActivity` still loads `http://<backend>/` for the full web settings,
  browser deep links and the web player. Those are the same `hub/web/` files a browser gets.
- **Torrents** — standalone carries `TorrentEngine` (libtorrent4j, 32-bit ARM). It downloads a magnet
  sequentially into `cache/torrents`, picks the requested file or episode, and serves it through the
  loopback HTTP server with Range support. The player sees an ordinary file. A copy larger than the
  free space is refused, and a far seek waits for its pieces.
- **Playback** — `PlayerActivity` is a native Media3 player with `useController=false`: play/pause,
  D-pad seek, elapsed/remaining, Audio, Subtitles (including Off), Speed and Quality/Source panels.
  It retries `503 warming` answers at the hub's `retryAfterMs`, fails over to the next ranked copy at
  the same position through `/app/v1/switch` (`exclude`, bounded), posts progress every ~10 s and on
  pause/exit, and rolls into the next episode with a countdown and Undo. Live channels have no seek
  or speed; the remote's CH Up/Down (and left/right at rest) zaps the hub's live row when it is
  available. The hub now asks IntroDB for a series episode's intro window and puts it in the play
  answer; the native player shows a "Skip intro" chip exactly inside that window and one OK press
  skips it. No known window means no button, never a guess. The transport is drawn as flat icons rather than labels, with a dot for an active audio
  track, subtitles or a non-1x speed. The optional `HUB_TOKEN` is
  used for `/app/v1` calls and for hub-origin media URLs only.
  `WebShellActivity` still carries the native bridge (`play`, `pause`, `resume`, `seek`, `skip`,
  `stop`, `setSpeed`, `select`, `setQuality`, `setSubMarginBottom`, `openExternal`, `openSetup`,
  `state`) for the web player route used by browsers/deep links; the `<video>` element is used only
  in a browser.
- **Deep links** — `tv://detail/<identity>` and `tv://play/<identity>` open the hub page at
  `#/detail/<encoded identity>` or `#/play/<encoded identity>` (the web player route). A Play press
  on native Detail does not use this route; it starts the native `PlayerActivity` directly.
  `tv://setup` opens setup.

## What the app is not

The native product surfaces are Profiles/PIN, Home, Search, Detail, Settings and the player. The
remaining web-only admin pages (Live TV groups, made categories, profile/hub-name management) are
not reachable from the TV; the WebView is used only by browser deep links and the web player route. A
Back press can never land on a web page from the native menus. There is no per-device "front end"
switch.

## Contract

The page speaks only `/app/v1`: `GET /home|manifest|meta/<id>|search|profiles|prefs|subs/<id>` and
`POST /play|switch|progress|prefs`. The native path uses the same `GET /home`, `/meta/<id>`,
`/subs/<id>` and `POST /play`, `/switch`, `/progress`, with the same optional Basic token. The
interface is served by the backend; the app contributes the media surface, the input plumbing and
the hub address. A backend that advertises a capability it does not have is a bug in the backend,
not something the app papers over.

## Offline and fixtures

If a paired hub stops answering, the shell says so and retries rather than showing an empty library.
The bundled web copy in the APK is taken from `hub/web/` at build time, so an APK must be rebuilt
whenever that interface changes.

`--es fixture fixtures/home.json` starts `FixtureServer`, which serves that page and the bundled
catalogue over loopback. It is the same HTTP path as a real backend and is read-only (no addon,
profile or progress writes), so the preview cannot pretend to be a hub. The fixture play entries use
a public HLS test stream; one magnet entry is left unplayable on purpose.
