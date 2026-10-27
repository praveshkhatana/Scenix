# Scenix

A self-hosted streaming system: one content hub, one interface, every screen.

Scenix puts the catalogue, the sources and the policy on a machine you own, and draws the same
interface on the television, in a browser and on a phone. Nothing is rented, nothing is licensed per
device, and no vendor decides what your family may watch.

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
hands the player one stream it can sustain.

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

## Running it

**Hub** — Node with built-ins only, no `npm install`:

```sh
cd hub && node index.js            # http://<this machine>:8788
```

Open that address to run the Hub UI: add addons, choose languages, reorder Home, set the maturity
ceiling, create profiles. Point an HTTP torrent engine at it (port 8090) and torrents become seekable
streams; `ffmpeg` on the machine enables the browser path.

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

MIT for everything in this repository. The hub talks to third-party addon services and, for torrent
delivery, to a separate engine process over HTTP — it links against none of them, so no external
licence reaches our code.
