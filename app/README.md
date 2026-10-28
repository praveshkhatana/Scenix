# Scenix TV app

A thin Android shell for one product: the hub's web interface. It is built for a Sony BRAVIA
(Android 11, armeabi-v7a, 2 GB), but runs on Android 8+ / Android TV.

```
JAVA_HOME=<jdk17> ANDROID_HOME=<sdk> ./gradlew --no-daemon assembleDebug
# build/outputs/apk/debug/app-debug.apk
```

Nothing is installed or deployed from here.

## What the app is

- **Setup** — `MainActivity` probes for a hub. One that answers is opened at once; if none does,
  `PairingScreen` lists the hubs heard on the LAN, accepts a typed address, or starts the bundled
  standalone host. This is the only native screen in the product.
- **The product** — `WebShellActivity` loads `http://<backend>/` in a WebView. Every screen you use
  (home, detail, search, settings, player chrome) is the same `hub/web/` code a browser gets.
- **Playback** — a native Media3 `ExoPlayer` sits behind the WebView. The page calls the
  `window.Scenix` bridge (`play`, `pause`, `resume`, `seek`, `skip`, `stop`, `setSpeed`, `select`,
  `setQuality`, `setSubMarginBottom`, `openExternal`, `state`) and draws no video of its own on TV.
  The `<video>` element is used only in a browser.
- **Deep links** — `tv://detail/<identity>` and `tv://play/<identity>` open the hub page at
  `#/detail/<encoded identity>` or `#/play/<encoded identity>`.

## What the app is not

There is no native Home, Detail, Search, Live, Movies, Series, Settings or Player stack, and no
per-device "front end" switch. Those were a second product and have been removed. `PairingScreen` is
a plain setup object; there is no native screen registry left to grow back.

## Contract

The app speaks only `/app/v1`: `GET /home|manifest|meta/<id>|search|profiles|prefs|subs/<id>` and
`POST /play|progress|prefs`. The interface is served by the backend; the app contributes the media
surface, the input plumbing and the hub address. A backend that advertises a capability it does not
have is a bug in the backend, not something the app papers over.

## Offline

If a paired hub stops answering, the shell says so and retries rather than showing an empty library.
The bundled web copy in the APK is taken from `hub/web/` at build time, so an APK must be rebuilt
whenever that interface changes.
