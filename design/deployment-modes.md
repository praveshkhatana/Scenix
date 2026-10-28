# Deployment modes

Scenix ships as two cooperating parts and can be installed in two shapes. The rule that makes both
shapes work for anyone is the same: **no household-specific value is ever a constant in the code.**

## Rule 1 — nothing is hardcoded

Every value that belongs to a household, a country or a taste lives in settings the owner can edit,
and is read at request time. Nothing below may appear as a literal in `hub/` or `app/`:

| What | Where it lives instead |
|---|---|
| Content languages | `settings.languages` → builds the addon request URL |
| Maturity ceiling / adult on or off | `settings.maturity`, `settings.adult` |
| Which shelves appear on Home, in what order | `settings.home.rows` |
| Which live-TV groups or channels are shown | `settings.live` |
| Addons installed and configured | `settings.addons` (persisted, hot-reloaded) |
| Quality ceiling, bitrate ceiling, buffer sizes | `settings.player` |
| Who the viewers are (names, kids flag, PIN) | `profiles` — created on first run, never shipped |
| The hub's own address | paired by the client; never baked into a build |

Environment variables are **install-time defaults only** — a way to pre-seed a settings file, never a
substitute for editing it. A default that no owner can reach through the UI is a bug.

Neutral shipped defaults: no languages preselected, no channels, no addons, one profile created on
first run.

## Rule 2 — one contract, two hosts

The client's entire knowledge of a backend is the `/app/v1` HTTP contract plus the `window.Scenix`
playback bridge. Two implementations satisfy it:

```
   HUB MODE (enhanced)                     STANDALONE MODE (simple)
   ─────────────────────                   ─────────────────────────
   Scenix Hub  ── one process, any box     the app hosts the same contract
   on the network or hosted anywhere         inside itself (LocalHub)
        │                                        │
        ├─ addons, profiles, settings            ├─ addons (same protocol)
        ├─ torrent engine + gateway              ├─ local libtorrent + local DB
        ├─ live TV, local files, trailers        ├─ direct HTTP/HLS and torrents
        └─ serves the shared UI                  └─ UI bundled in the app
                 │                                        │
                 └────────── one client UI ───────────────┘
              (browser, TV shell, phone — all the same)
```

Both shapes answer the same HTTP contract. A remote hub answers it from `hub/index.js`; standalone
answers it from `LocalHub` through `LocalServer` on loopback. The page does not branch on which one it
is talking to; it branches only on **capabilities** the backend advertises in
`GET /app/v1/manifest` — for example a hub offers the torrent gateway and live TV; standalone offers
direct sources. Anything the backend cannot do is absent from the UI, not broken in it.

### Hub mode
The hub is a separate install — the owner's NAS, an old laptop, a home server. It brings the torrent
gateway (one reader for the whole house), live TV, local files, server-side caching and the shared
catalogue DB. The app is thin: pair, browse, play.

### Standalone mode
One app, no server, nothing to install twice — the simple shape. The app runs the contract itself,
talks to addons directly, and serves the same bundled interface on a loopback port. It also carries a
libtorrent engine for the 32-bit ARM panel: a magnet is downloaded sequentially into the app cache and
served to the player as ordinary seekable HTTP. Trade-offs, stated honestly: the engine shares the
TV's 2 GB and CPU with decoding, a large film needs free storage (the app refuses a copy that does not
fit), and a far seek waits for those pieces; the hub still does this better with one engine and a
disk. Standalone remains the easy shape, not the better one.

Both shapes have run on the same panel in the same build, with only an owner setting changed between
them. In hub mode the shell loaded the paired hub's address and played through the gateway. In
standalone the app started its own contract host on a loopback port chosen at boot, served the
identical page out of its own assets, and answered `GET /app/v1/manifest` with the richer flags turned
off — `live:false, localFiles:false, remux:false, tracks:false` beside `gateway:true, search:true,
settingsAdmin:true, profileEdit:true, addonAdmin:true` — with one profile created on first run. That
state drew no audio-track or subtitle control, which is the capability rule doing its job rather than a
diminished screen.

The same page also has a **fixture preview**: `--es fixture fixtures/home.json` starts
`FixtureServer`, which serves the bundled page and catalogue on loopback. It exists to exercise the
interface without a hub and is read-only; its capabilities say so, so settings and profile writes are
absent rather than silently discarded.

## Rule 3 — configuration follows the backend

* **With a hub** the owner configures in the **Hub UI** (`http://<hub>:8788/` in any browser):
  languages, categories on Home, channels, addons, profiles, adult content. The app mirrors those
  settings read-only where it makes sense (a per-profile quality cap) but never owns them.
* **Standalone** the same screens are drawn **inside the app**, because they are driven by the same
  contract — one implementation of each screen, two hosts.

A user must never have to edit a file or set an environment variable to make Scenix theirs.

### Which surface a device draws

There is one. The Android app is a **thin shell**: `WebShellActivity` hosts the hub's page and a Media3
surface plays the video underneath it. The only native screen left is first-run setup — find a hub on
the LAN, type an address, or choose standalone — and an offline/"hub not answering" state. There is no
alternative native Home, Detail, Search or Player to keep in step, so a change to the interface is a
change in `hub/web/` and it reaches television, browser and phone at once.

`tv://detail/<identity>` and `tv://play/<identity>` deep links open the same page at `#/detail/…` or
`#/play/…`; they no longer name a screen of a native stack. The old per-device **Settings → Interface**
switch is gone with that stack.

## Rule 4 — pairing, not IP addresses

Nothing in a build names a machine. The client finds a hub by:

1. **LAN announce** — the hub advertises itself on the local network (UDP, Node built-ins only) and
   the client lists what it finds, by the name the owner gave it.
2. **Manual address** — typing `http://<hub>:8788` for a hub on another network or hosted remotely.
3. **Remembered** — the last working address is tried first; a hub that stops answering falls back to
   the other candidates and says so on screen rather than spinning.

With nothing paired and no choice recorded, the app probes the LAN once. A hub that answers is opened
without a question, because a stranger's first run should not require a decision the network can make.
If nothing answers, setup asks which of these three to use instead of browsing an empty library or
spinning forever; the answer is remembered on the device. Pairing is a first-run step, not a setting
inside the product; `tv://setup` reopens it, and clearing the app's data is the fallback for a device
whose backend cannot be reached at all.

## TLS and trust

The hub listens on HTTP so a fresh install works on a trusted LAN without a certificate. For anything
else:

- **Reverse proxy (recommended).** Terminate TLS in front of the hub and forward
  `X-Forwarded-Proto: https` and `X-Forwarded-Host`. `core/origin.js` uses those for every absolute URL
  the hub returns (gateway streams, remux, subtitles), so a browser does not block mixed content.
- **Direct TLS.** Set `TLS_CERT` and `TLS_KEY` to a certificate and key the TV trusts. The hub serves
  HTTPS on the same port, and LAN announcements carry `https://`. A self-signed certificate is rejected
  by the TV unless its CA is installed; Scenix does not install one.
- **Optional shared access token.** Set `HUB_TOKEN` and every route except `/healthz` asks for HTTP
  Basic (any username, token as the password) or `Bearer <token>`. The TV stores the token in setup and
  supplies it through the WebView auth handler; a browser gets the normal auth prompt. Without it the
  hub keeps the trusted-LAN behaviour. There are no user accounts or per-profile sessions; for real
  multi-user access control put an authenticating proxy in front.
- `/healthz` stays public for pairing and returns only the product name and version when the caller is
  not authorized; the full status report needs the token.

## Naming

The repository speaks with one voice: **Scenix**, hub, client, shelf/row, tile, source, stream,
profile, addon. No third-party product or project name appears in code, comments, docs or UI copy —
the exceptions are genuine runtime dependencies (Node, ffmpeg, the media player library, the torrent
engine) and the addon services an owner chooses to install. The design vocabulary in
`design/ui-tokens.md` is ours; the research that informed it stays out of this repository.

## The offline copy is built, not remembered

The Android shell carries a copy of the hub's web UI so a device with no hub reachable still opens
something. That copy is taken from the hub tree at build time, which means a device's fallback ages from
the moment its APK was built: install an APK today, change the UI tomorrow, and tomorrow's offline screen
shows today's interface. So an APK is rebuilt whenever the UI it bundles changes, not only when the shell
code changes. The build's copy task does the copying; the obligation is to run the build.
