# Scenix

A self-hosted streaming system: one content hub, the same catalogue, every screen.

Scenix puts the catalogue, the sources and the policy on a machine you own. Browsers and phones
draw the web interface; the television draws Home, Movies/Series, title pages, search, settings and
playback natively, themed by the same pack files. The app carries no WebView; the web UI is what a
browser or phone gets from the hub. Nothing has to be rented, nothing is licensed per device, and no
vendor decides what your family may watch.

> **Scenix is a player, not a provider.** It ships no content of its own. Everything you see comes
> from add-ons *you* install, from your own files, or from live playlists you choose. What you play,
> and whether you may play it where you live, is yours to decide and yours to check.

```
        addons (installed by you)
                 │
                 ▼
             SCENIX HUB  ──── catalogue · profiles · settings · cache
                 │            P2P gateway · live TV · local files
        ┌────────┴────────┐
        ▼                 ▼
   TV APP                    ANY BROWSER / PHONE
   native Home, Detail,      (the web interface
   Search, Settings, Player   the hub serves)
```

One contract, two presentation surfaces. The catalogue, ranking, profiles and policy stay on the hub;
the TV shelves and title page are native; the Player and Settings are the same web code a browser gets.

## What it is not

It is not a front end for one streaming service, and not a link scraper. It merges several content
sources into **one item model**, de-duplicates the same title across them, ranks the copies by what
your household actually wants — audio language first, then quality, then size, and a swarm that cannot
feed the file passed over for one that can, because a buffering copy serves none of those three — and
hands the player one stream it can sustain, on a screen that can decode it: video is copied rather
than transcoded here, so an H.265 file on a device with no H.265 decoder is not a lower-quality
option, it is a black screen with working sound, and such a copy gives way to one the device said
it can draw.

## Features

**The hub (Node, zero npm dependencies)**
- One merged catalogue from every installed add-on, de-duplicated and ranked per profile
- A plug-in architecture: a content source, a Home shelf or a policy rule is one file in
  `providers/`, `rows/` or `rules/` — the core never learns their names
- Profiles with PIN gates, a maturity ceiling, per-profile language lists and per-profile Home
- Live TV: an M3U playlist you choose, continuous channel health, stable zap numbers and an EPG guide
- Local files: a folder on the hub's disk, streamed as ordinary seekable HTTP
- Subtitles fetched and offered per profile; audio-language mixing from source packs when no single
  copy carries the language you asked for
- An optional P2P gateway: a P2P copy becomes a seekable HTTP stream through a separate engine
  process; an optional debrid account (off by default) can serve the same copy as plain HTTP first
- Optional adaptive quality ladder (1080/720/480 rungs Media3 switches mid-play) when ffmpeg is present
- Shared access token (`HUB_TOKEN`) and optional TLS; the hub never phones home

**The TV app (Android 8+ / Android TV, native — no WebView)**
- Home, sections, Detail, Search, Settings and the Player drawn natively and themed by pack files
- Themes as layouts, not paint: Clean, Netflix-style and a console-style hub ship in the box
- My List and Continue watching live on the hub — the living room's shelf is the bedroom's shelf;
  tiles can be dismissed in place and a started title offers Resume *and* Start over
- Games mode: a curated browser-game catalogue delivered as an add-on, controller support included
- Auto-update: the app checks the project's GitHub Releases and asks before installing anything
- Standalone mode: the same contract hosted inside the TV app, with an on-device P2P engine, when
  there is no hub to pair with

**The web interface (any browser or phone)**
- The same catalogue, the same player and the same settings the hub owns; it is what configures the
  hub — add-ons, packs, languages, profiles, shelf order

## Coming soon

- A published theme-kit: a documented pack format plus component slots, so anyone can build their
  own look for the TV without touching the app's source
- Guide improvements: more automatic matching between playlists and published EPGs
- Offline: keep a title on the TV or the hub's disk and play it without any network
- A per-profile "why this shelf" peek — the ranking is already computed; surfacing it is not
- Add-on quality-of-life: an in-app directory of known-good community add-ons

## Two ways to install

| | **Hub mode** (recommended) | **Standalone mode** (simplest) |
|---|---|---|
| What you run | the hub on any box on your network, plus the app | the app alone |
| Configuration | the Hub UI, in any browser | native TV Settings |
| P2P playback | one gateway reader shared by the household | a local engine; downloads to the TV cache and is limited by free space |
| Debrid | one account shared by the household | one account on the TV |
| Live TV | playlist + health + per-profile lineup | playlist + on-TV health + per-profile lineup |
| Local files | a folder on the hub's disk | a folder chosen on the TV (MediaStore fallback) |
| Shared cache | yes | no |

Both speak the same `/app/v1` contract, so the native TV client can use either backend and only
branches on capabilities the backend advertises. Settings on the TV has a **Connect from phone**
card (behind the household Settings PIN): scanning the QR opens the same web Settings on a
phone — in hub mode the hub URL (`:8788`), in standalone the TV's own LAN address (`:8787`,
which serves the bundled web snapshot from `assets/web/` alongside `/app/v1`). The phone
web page already guards writes with the household Settings PIN (`POST /app/v1/settings/unlock`
→ `X-Scenix-Admin-Session`); no pairing code is needed.

## Nothing is hardcoded

Languages, maturity ceiling, which shelves appear on Home and in what order, live-TV groups, add-ons,
categories and profiles are settings the owner edits — never constants in a build, never a value you
must edit a file to change. The hub's address is not a build-time constant either: each television
pairs with it on first run. A default you cannot reach through the UI is a bug.

## Everything is a plugin

Adding a content source, a shelf or a filter rule means adding one file under `hub/providers/`,
`hub/rows/` or `hub/rules/` and registering it once. The hub's router, item model and merge step never
learn the name of the thing you just added — they receive a shared item shape with a card hint and a
merge key. On the interface side, a screen or card is a module under `hub/web/js/screens/` or a
registered renderer in `hub/web/js/plugins.js`, so a new shelf style does not edit the screens that
already exist.

The playback bridge is the same idea: the television draws its screens natively and Media3 decodes
the video underneath them, so a 1080p HEVC P2P copy with E-AC3 audio and ASS subtitles plays exactly
as it sits on disk. Browsers get a remuxed copy instead; the file is never transcoded.

## Watching in your own language

An owner can add an audio-only source pack or a catalogue add-on from Settings, without touching a
line of code. When no single copy of a title carries the profile's first audio language, the hub keeps
the best picture and mixes the audio of another source into it — the same remuxing machinery, a
second input.

## Optional debrid

A debrid account is not required, and there is no paid tier of Scenix. If you already have one,
Settings → **Debrid / direct** lets the hub use it: a P2P or file-hoster link is exchanged at the
provider for an ordinary HTTP URL, so playback does not wait on a swarm. It is off by default,
accepts one provider token (Real-Debrid today), and falls back to the gateway unchanged when the
provider is down, slow or refuses the link. The token is stored only on the hub, never returned to a
browser, and never put in a log.

## First install

### 1. Install the hub on a computer that stays on

The hub is a Node process with **no npm dependencies**. Use any always-on machine on the same
network as the TV — a Mac, a Linux box, a Raspberry Pi or a Windows PC.

1. Install **Node.js 18 or newer** from <https://nodejs.org>.
2. Get the repository and start the hub:
   ```sh
   git clone https://github.com/praveshkhatana/Scenix.git
   cd Scenix
   ./scripts/install-hub.sh          # checks Node and creates hub/profiles.json
   cd hub && node index.js
   ```
   On Windows without the script: `cd hub` then `node index.js`.
3. The hub prints `http://<machine>:8788`. Open that in a browser on the same network and configure
   the things the hub owns: add-ons, source packs, languages, profiles and the hub's name.
4. Allow **TCP 8788** and **UDP 1900** through that machine's firewall. 1900 is the LAN announcement
   that lets the TV find the hub without typing an address.

The hub advertises itself on the local network. If it is on another network, note its public address
and use **Type an address** in the TV setup.

### 2. Connect the TV app

1. Build and install the Android TV app from `app/`:
   ```sh
   cd app && ./gradlew assembleDebug
   adb install -r build/outputs/apk/debug/app-debug.apk
   ```
   Or install the latest APK from this repository's [Releases](../../releases) — the app checks
   Releases itself and asks before installing an update.
2. Open Scenix on the TV. On first run it tries the last backend, then searches the LAN:
   - a hub that answers is listed by the name its owner gave it — choose it;
   - or choose **Type an address** and enter `http://<machine>:8788`;
   - or choose **Keep this TV on its own** for standalone mode.
3. To change the backend later, open **Settings → Hub connection**. That row shows the current hub
   (or "Standalone") and reopens this setup screen. It is admin-gated, and kids profiles never see it.

Standalone mode hosts the local `/app/v1` contract for the native TV app. It can play direct HTTP
links, add-on catalogues and supported P2P copies through the TV's own engine when that engine is
available, can use a Real-Debrid account, and can draw an M3U live wall with the same health ordering
and stable zap numbers a hub gives. It does not expose a browser-management site to other devices;
use Hub mode when you want browser or phone management.

**Local files** are chosen once in **Settings → Local files**. The app keeps the folder grant (SAF)
and indexes video and audio up to four levels deep; a television whose image has no system folder
picker falls back to the platform's media library with one read permission. The player receives a
loopback HTTP URL with Range support, so seeking behaves like any other copy. It has no household
cache and no server-side remuxing.

## Running it

**Hub** — Node with built-ins only, no `npm install`:

```sh
cd hub && node index.js            # http://<this machine>:8788
```

Open that address to run the Hub UI: add add-ons, choose languages, reorder Home, set the maturity
ceiling, create profiles. Point an HTTP P2P engine at it (port 8090) and P2P copies become seekable
streams; `ffmpeg` on the machine enables the browser path. If you have a debrid account, configure it
in Settings and the hub will try it before the engine; nothing breaks if you never do.

### Adaptive quality (optional)

Scenix normally copies the picture. If you want a real adaptive ladder (1080/720/480 variants that
Media3 can switch at a segment boundary), install a transcode-capable ffmpeg and put it at
`hub/bin/ffmpeg-btb` plus `hub/bin/ffprobe-btb`, then enable **Settings → Playback → Playback
smoothness → Build an adaptive quality ladder**. The hub prefers those binaries, falls back to
`hub/bin/ffmpeg` for remux-only installs, and keeps the ladder off by default because transcoding
costs CPU and disk. A resume continues through the ladder from the saved playhead, and the TV's
Quality menu changes the rung of the same stream (not the copy), so a quality change cannot land on a
different cut.

The hub speaks HTTP by default. For HTTPS, either terminate TLS in a reverse proxy (it honours
`X-Forwarded-Proto`/`X-Forwarded-Host` when building stream and subtitle URLs) or give it a certificate
with `TLS_CERT`/`TLS_KEY`. The certificate must be trusted by the television; Scenix does not install a
CA on the device.

Set `HUB_TOKEN` to require a shared access token on the hub API and UI. A browser asks for HTTP Basic
(any username, token as the password); the TV stores the token in setup and supplies it to the native
player automatically. `/healthz` stays public for pairing and exposes only the product name and version.

**TV app** — Android 8+ / Android TV, `./gradlew assembleDebug` in `app/`. On first run it tries a
hub it already remembers, then asks the LAN; one that answers is opened without a question. If none
does, setup lists the hubs it heard, accepts a typed address, and offers standalone mode. Once a
backend is chosen the app opens the native profile gate and stays native.
`tv://detail/…` and `tv://play/…` links pass through that gate to the native Detail/Player;
`tv://setup` brings the front door back on a TV that already has a backend.

## Layout

```
hub/            the content server (Node, built-ins only)
  providers/    a content source            rows/     a shelf on Home
  rules/        policy over items           core/     the shared machinery
  web/          the interface the hub serves to browsers and phones
app/            the TV client (Android / Android TV, native Views + media3)
```

## Licence

MIT for everything in this repository except the interface typeface in `hub/web/font/`, which
is a subset of Netflix Sans and carries its own notice (`hub/web/font/LICENSE.txt`).
The hub talks to third-party add-on services chosen and
installed by the owner, and, if the owner enables it, to a debrid provider they already pay for. For
P2P delivery it talks to a separate engine process over HTTP. It links against none of them, so no
external licence reaches our code. Scenix ships no media and no content metadata of its own.
