# Scenix

A self-hosted streaming system: one content hub, one interface, every screen.

Scenix puts the catalogue, the sources and the policy on a machine you own, and draws the same
interface on the television, in a browser and on a phone. Nothing has to be rented, nothing is licensed
per device, and no vendor decides what your family may watch. If you already pay for a debrid account,
the hub can optionally use it as a faster source; it is off until you turn it on.

```
        addons (installed by you)
                 │
                 ▼
             SCENIX HUB  ──── catalogue · profiles · settings · cache
                 │            torrent gateway · live TV · local files
        ┌────────┴────────┐
        ▼                 ▼
   TV / PHONE APP      WEB APP
   (thin shell,        (same UI, served by the hub)
    native player)
```

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
| Configuration | the Hub UI, in any browser | inside the app |
| Torrent playback | one gateway reader shared by the household | on the device |
| Live TV, local files, shared cache | yes | no |

Both speak the same `/app/v1` contract, so the interface is identical and the app only branches on
capabilities the backend advertises. See [design/deployment-modes.md](design/deployment-modes.md).

## Nothing is hardcoded

Languages, maturity ceiling, which shelves appear on Home and in what order, live-TV groups, addons,
profiles and the hub's own address are settings the owner edits — never constants in a build, never
environment variables you have to know about. A default you cannot reach through the UI is a bug.

## Everything is a plugin

Adding a content source, a shelf, a card style, a filter rule or a player behaviour means adding one
file in one directory and registering it once. The hub scans `providers/`, `rows/` and `rules/`; the
clients keep one registry each. The core router, the item model and the interface never learn the name
of the thing you just added — they receive a shared item shape with a card hint and a merge key.

The playback bridge is the same idea: the TV app draws its interface in a WebView and decodes video
natively underneath it, so a 1080p HEVC torrent with E-AC3 audio and ASS subtitles plays exactly as it
sits on disk. Browsers get a remuxed copy instead; the file is never transcoded.

## Optional debrid

A debrid account is not required, and there is no paid tier of Scenix. If you already have one, Settings →
**Debrid / direct** lets the hub use it: a magnet or a file-hoster link is exchanged at the provider for
an ordinary HTTP URL, so playback does not wait on a torrent swarm. It is off by default, accepts one
provider token (Real-Debrid today), and falls back to the gateway unchanged when the provider is down,
slow or refuses the link. The token is stored only on the hub, never returned to a browser, and never put
in a log. See [design/debrid.md](design/debrid.md) for the provider contract, security notes and what
still needs a live account to verify.

## Running it

**Hub** — Node with built-ins only, no `npm install`:

```sh
cd hub && node index.js            # http://<this machine>:8788
```

Open that address to run the Hub UI: add addons, choose languages, reorder Home, set the maturity
ceiling, create profiles. Point an HTTP torrent engine at it (port 8090) and torrents become seekable
streams; `ffmpeg` on the machine enables the browser path. If you have a debrid account, configure it in
Settings and the hub will try it before the engine; nothing breaks if you never do.

**TV app** — Android 8+ / Android TV, `./gradlew assembleDebug` in `app/`. On first run it lists the
hubs it finds on the network, or you type an address, or you skip both and use standalone mode.

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
