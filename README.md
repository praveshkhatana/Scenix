# Scenix

A self-hosted streaming system: one content hub, the same catalogue, every screen.

Scenix puts the catalogue, the sources and the policy on a machine you own. Browsers and phones
draw the web interface; the television draws Home, Movies/Series, title pages, search, settings and playback natively,
themed by the same pack files. The app carries no WebView; the web UI is what a browser or phone gets
from the hub. Nothing has to be rented, nothing is
licensed per device, and no vendor decides what your family may watch. If you already pay for a debrid account,
the hub can optionally use it as a faster source; it is off until you turn it on.

```
        addons (installed by you)
                 │
                 ▼
             SCENIX HUB  ──── catalogue · profiles · settings · cache
                 │            torrent gateway · live TV · local files
        ┌────────┴────────┐
        ▼                 ▼
   TV APP                    ANY BROWSER / PHONE
   native Home, Detail,      (the web interface
   Search, Settings, Player   the hub serves)
```

One contract, two presentation surfaces. The catalogue, ranking, profiles and policy stay on the hub;
the TV shelves and title page are native; the Player and Settings are the same web code a browser gets. Setup and the profile gate remain native/web handoffs as described in
[design/native-browse.md](design/native-browse.md).

## What it is not

It is not a front end for one streaming service, and not a link scraper. It merges several content
sources into **one item model**, de-duplicates the same title across them, ranks the copies by what
your household actually wants — audio language first, then quality, then size, and a swarm that cannot
feed the file passed over for one that can, because a buffering copy serves none of those three — and
hands the player one stream it can sustain, on a screen that can decode it: video is copied rather
than transcoded here, so an H.265 file on a device with no H.265 decoder is not a lower-quality
option, it is a black screen with working sound, and such a copy gives way to one the device said
it can draw.

## Two ways to install

| | **Hub mode** (recommended) | **Standalone mode** (simplest) |
|---|---|---|
| What you run | the hub on any box on your network, plus the app | the app alone |
| Configuration | the Hub UI, in any browser | the same UI, hosted in the app |
| Torrent playback | one gateway reader shared by the household | a local libtorrent engine; downloads to the TV cache and is limited by free space |
| Debrid | one account shared by the household | one account on the TV |
| Live TV | playlist + health + per-profile lineup | playlist + per-profile lineup, no health probing |
| Local files, shared cache | yes | no |

Both speak the same `/app/v1` contract, so the interface is identical and the app only branches on
capabilities the backend advertises. See [design/deployment-modes.md](design/deployment-modes.md).

## Nothing is hardcoded

Languages, maturity ceiling, which shelves appear on Home and in what order, live-TV groups, addons,
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

The playback bridge is the same idea: the TV app draws its interface in a WebView and decodes video
natively underneath it, so a 1080p HEVC torrent with E-AC3 audio and ASS subtitles plays exactly as it
sits on disk. Browsers get a remuxed copy instead; the file is never transcoded.

## Watching in your own language

An owner can add an IGC source pack, an audio-only pack, or a catalog addon like FrameXTV from Settings,
without touching a line of code. When no single copy of a title carries the profile's first audio
language, the hub keeps the best picture and mixes the audio of another source into it — the same
remuxing machinery, a second input. See [design/source-packs.md](design/source-packs.md) for the
descriptor format, how a donor is chosen, and what was measured.

## Optional debrid

A debrid account is not required, and there is no paid tier of Scenix. If you already have one, Settings →
**Debrid / direct** lets the hub use it: a magnet or a file-hoster link is exchanged at the provider for
an ordinary HTTP URL, so playback does not wait on a torrent swarm. It is off by default, accepts one
provider token (Real-Debrid today), and falls back to the gateway unchanged when the provider is down,
slow or refuses the link. The token is stored only on the hub, never returned to a browser, and never put
in a log. See [design/debrid.md](design/debrid.md) for the provider contract, security notes and what
still needs a live account to verify.

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
2. Open Scenix on the TV. On first run it tries the last backend, then searches the LAN:
   - a hub that answers is listed by the name its owner gave it — choose it;
   - or choose **Type an address** and enter `http://<machine>:8788`;
   - or choose **Keep this TV on its own** for standalone mode.
3. To change the backend later, open **Settings → Hub connection**. That row shows the current hub
   (or "Standalone") and reopens this setup screen. It is admin-gated, and kids profiles never see it.
4. The old `tv://setup` link still works from a phone or another app.

Standalone mode hosts the same web UI and contract inside the TV app. It can play direct HTTP links,
add-on catalogues and torrents through the TV's own engine, can use a Real-Debrid account, and can
draw an M3U live wall. It has no household cache, no local-file browser and no server-side remuxing.

## Running it

**Hub** — Node with built-ins only, no `npm install`:

```sh
cd hub && node index.js            # http://<this machine>:8788
```

Open that address to run the Hub UI: add addons, choose languages, reorder Home, set the maturity
ceiling, create profiles. Point an HTTP torrent engine at it (port 8090) and torrents become seekable
streams; `ffmpeg` on the machine enables the browser path. If you have a debrid account, configure it in
Settings and the hub will try it before the engine; nothing breaks if you never do.

The hub speaks HTTP by default. For HTTPS, either terminate TLS in a reverse proxy (it honours
`X-Forwarded-Proto`/`X-Forwarded-Host` when building stream and subtitle URLs) or give it a certificate
with `TLS_CERT`/`TLS_KEY`. The certificate must be trusted by the television; Scenix does not install a
CA on the device.

Set `HUB_TOKEN` to require a shared access token on the hub API and UI. A browser asks for HTTP Basic
(any username, token as the password); the TV stores the token in setup and supplies it to the WebView
automatically. `/healthz` stays public for pairing and exposes only the product name and version.

**TV app** — Android 8+ / Android TV, `./gradlew assembleDebug` in `app/`. On first run it tries a
hub it already remembers, then asks the LAN; one that answers is opened without a question. If none
does, setup lists the hubs it heard, accepts a typed address, and offers standalone mode. That is the
whole native interface: once a backend is chosen the app opens the native profile gate and stays native.
`tv://detail/…` and `tv://play/…` links pass through that gate to the native Detail/Player;
`tv://setup` brings the front door back on a TV that already has a backend.

## Layout

```
hub/            the content server (Node, built-ins only)
  providers/    a content source            rows/     a shelf on Home
  rules/        policy over items           core/     the shared machinery
  web/          the interface the hub serves and the TV shell hosts
app/            the thin client (Android / Android TV, media3)
design/         our architecture and the interface's measured numbers
```

## Licence

MIT for everything in this repository. The hub talks to third-party addon services, and, if the owner
enables it, to a debrid provider they already pay for. For torrent delivery it talks to a separate engine
process over HTTP. It links against none of them, so no external licence reaches our code.
