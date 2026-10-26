# On-device verification — Scenix app and hub

Run on the real BRAVIA VU1 (the debug APK) against the hub on `<server ip>:8788`.
Screenshots live in `shots/` (gitignored). Navigation was driven with `adb shell input`, reading the
focused node back from `uiautomator dump` after each step instead of counting remote presses — blind
D-pad guessing is unreliable on this app.

## What was proven

| Shot | Screen | What it shows |
|---|---|---|
| `tv-07-profiles.png` | Profile gate | Hub-driven profiles: adult tiles in brand red, kids tiles in gold, lock glyph only where `pinRequired` |
| `tv-08-pin.png` | PIN pad | A `pinRequired` profile asks for a PIN; it is hashed on the TV and never sent to the hub |
| `tv-09-home-adult.png` | Adult home | the Top 10 shelf with rank numerals, `100% Match · 1h 49m · 9 sources`, NEW / ADDED ribbons, Hindi titles in Just Added. Empty rows are no longer rendered |
| `tv-10-detail.png` | Title detail | **"2 sources merged behind one tile"**, then `Play from` with the ranked sources and their `why[]` reasoning — 720p auto-picked as Source 1. This is the "stop hunting through links" fix |
| `tmp-player-paused.png` | Player chrome | Red progress bar, `00:07:58 / 00:11:45` **resumed from the hub's saved progress**, `640x360 · 88s buffered`, key legend |
| `tv-11-player-panel.png` | Player settings | the "Playback Settings" sheet in D-pad form: Source (+ its why line), `Quality: Auto (up to 720p)`, Speed, Subtitles, Aspect ratio, Autoplay next, Audio boost |
| `tv-12-live-detail.png` | Live channel | Channel logo as backdrop, `Play from` → `Source 1 · Live`, reason `English audio (fallback)` — the audio-language preference is applied to live too |
| `tv-13-live-panel.png` | Live playback | `1280x720 · 2.0 Mbps · 22s buffered` inside a live window, `Quality … live 2.0 Mbps` — the adaptive ladder is real, not a fixed file |
| `tv-03-home-live.png` | Kids home | Kids-scoped rows only (CoComelon, kids channels); adult content is not reachable |

## Bugs found and fixed on this pass

1. **Live TV crashed.** `ClassNotFoundException:
   androidx.media3.exoplayer.hls.HlsMediaSource$Factory`. `DefaultMediaSourceFactory` picks the media
   source from the URL by reflection, and `media3-exoplayer-hls` was not a dependency — so every
   `.m3u8` channel (the whole IPTV catalogue) killed the player, while progressive sources (YouTube)
   never touched that path. Fixed by adding `media3-exoplayer-hls` + `media3-exoplayer-dash` to
   `build.gradle` and explicit `-keep` rules in `proguard-rules.pro` (the lookup is reflective, so
   release minify could strip them too). APK 6.77 MB → 11.14 MB; re-verified live at 1280x720 above.
2. **The settings button drew a padlock** (`NavBar` used `ic_lock`), which read as "this profile is
   locked". Added a stroke-style `ic_settings` gear; the lock glyph now appears only on profiles that
   actually have a PIN.
3. **Empty shelves printed a header with nothing under it** ("Continue watching" before anyone had
   watched anything). `HomeScreen` now drops rows with no items, the way a streaming service does.

## Notes for the next pass

- `screencap` on this panel returns black for the video rectangle (hardware overlay plane); the app's
  own chrome over it captures normally, so a black frame in a player shot is the TV, not the app —
  same caveat PROMPT.md gives for live TV and HDMI.
- `uiautomator dump` stops updating while the video surface has focus; verify focus on the browse
  screens and use `logcat` (`ExoPlayerImpl: Init`, `OMX_VDEC frame-info`, `c2.android.aac.decoder`)
  as the proof of playback.
- A YouTube "720p" source can still decode at 640x360: the label is the addon's declared quality, the
  frame size is what the extractor's format map actually returned under the 720p ceiling.
- ADB was closed after the pass (`adb disconnect` + `adb kill-server`); port 5555 is plaintext.
