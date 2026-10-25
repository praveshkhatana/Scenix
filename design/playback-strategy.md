# Playback strategy — making free sources feel like Netflix

Written after the owner asked: *"magnets can't play on a 2 GB TV without a torrent engine — why?
how does Stremio handle it? can a PlayerPlugin do it better? use players that already exist."*
Plus their content preference and a reference web player (`framextv.cc`).

## 1. What a magnet actually is

A magnet link is **not a stream**. It is a promise: *"this file is split into ~16–256 KB pieces,
held by some number of peers, go get them."* To play it you need an **engine** that

1. connects to peers (DHT/tracker),
2. downloads pieces **in playback order**, not file order,
3. keeps a buffer ahead of the playhead,
4. exposes the result as a **seekable stream** (HTTP with byte ranges) to an ordinary video player.

That engine is the whole product in apps like TorrServe, Nova Video Player, or Stremio's own
`stremio-core`. It is CPU/RAM work (hashing every piece, managing peer connections) and it is
**at the mercy of uploaders**: if peers collectively send 0.69× realtime, no player on earth can
play it smoothly. That is exactly what we measured on this TV (0.69×, 8.5 MB/s) — the engine
worked, the swarm didn't.

Second structural gap: a torrent is **one fixed file**. There is no adaptive ladder, so there is
no "drop to 540p when Wi-Fi dips". Adaptive bitrate is what makes Netflix survive a bad network,
and a single 4.2 GB MKV cannot provide it.

## 2. How the existing ecosystem solves it

| Approach | Mechanism | Licence | Fit for us |
|---|---|---|---|
| **In-process core** (Stremio desktop/Android) | `stremio-core` (Rust) runs the torrent engine inside the app and hands the player a `127.0.0.1` URL | MIT | Proven, but it means a Rust torrent engine living inside our 2 GB / 32-bit app, competing with the HW decoder |
| **Companion engine app** (TorrServe pattern, used by Nova / Just Player / Stremio's `proxyUrl`) | a separate app runs the engine + local HTTP server; players just open `http://127.0.0.1:PORT/…` | GPL-family for the well-known ones | **Best fit**: zero licence contamination (separate app, no linking), reuses an engine that already works on Android TV |
| **Server-side gateway** | the *hub host* runs the engine and serves the torrent to clients as an ordinary seekable HTTP stream | ours | **The Netflix-like answer**: the hub box is x86 with 32 GB and 85 Mbps wired; the TV then plays plain HTTP with no torrent code at all |
| **Player with built-in torrent demuxer** (mpv/ffmpeg + libtorrent) | the demuxer speaks `magnet:` directly | LGPL/GPL | Good on desktop; on 32-bit Android TV it is a heavy native dependency |

## 3. Our answer: four layers, each one a plugin

**Layer 0 — pick the right source automatically (hub rule `source_ranking`).**
The owner's preference — *Hindi audio, then English; top quality; low file size* — becomes data:

```jsonc
// data/profiles.json (per profile, editable from the app: GET/POST /app/v1/prefs)
{ "audioLanguages": ["hin", "eng"], "quality": "highest",
  "maxSizeGB": 4, "maxBitrateMbps": 6, "preferAdaptive": true }
```

Every `sources[]` entry is scored and **sorted best-first**, each with a `why[]` list
(`["Hindi audio", "720p adaptive ladder", "no size cap"]`). The order of enforcement:
language allow-list → size cap → bitrate ceiling → audio-language preference → quality → size.
Torrents are never removed, but rank below an HTTP source of comparable quality, because they
have no ladder. One tap = one play; the "Play from" list is the ranked array, not a hunt.

**Layer 1 — prefer sources that are already streams.** Community addons that return direct
HTTP/HLS (WatchHub-style scrapers, our live playlists, curated YouTube) *are* adaptive and
*are* what makes playback feel like Netflix. Torrents are the fallback that widens the library.

**Layer 2 — failover in the player (the `framextv` "Mirror 1…5" pattern).**
The reference player offers `Auto`, `Auto (Mirror 1)`…`Auto (Mirror 5)`: the same title from
several sources, with automatic switching. We copy it with our word — **Source**:
if a stall exceeds ~6 s or Media3 reports an unsupported/fatal format, switch to the
next-ranked source **at the same position**, toast `Switched to Source 2 — first one stalled`,
keep going, and show the current source + its `why[]` in the overlay. Cap = list length.
This is the single biggest smoothness win, and it costs no new dependency.

**Layer 3 — torrents, when they are the only option.**
`PlayerPlugin` registry, in order of preference:

1. `Media3HttpPlayer` — progressive HTTP + range seeking (default, `mode:"direct"`).
2. `Media3HlsPlayer` / `Media3DashPlayer` — adaptive ladders (live TV, HTTP addons).
3. `GatewayPlayer` — `mode:"gateway"`: the hub streams a torrent as seekable HTTP. To the client
   this is just another HTTP source, so it works on TV **and** web **and** mobile. The hub ships
   the seam now (`core/gateway.js`, `GATEWAY=off|http`) and the engine later, as an explicit,
   optional, documented dependency — the hub stays dependency-free by default.
4. `ExternalEnginePlayer` — hand the magnet to a companion torrent app via intent
   (resolve an activity, never hardcode a package). Keeps GPL engines out of our process.

`mode` is the only thing that changes when a gateway is enabled: `sources[]`, `POST /app/v1/play`
and the UI stay identical. That is the contract.

## 4. Player surface (copied from the reference, made 10-foot)

D-pad settings panel: **Source** (Auto + ranked list) · **Subtitles** (embedded + hub-served from
`GET /app/v1/subs/<identity>?lang=hin,eng,pan`, side-loaded into Media3) · **Audio** (tracks,
default = profile's language order) · **Speed** (0.75–1.5) · **Aspect** · **Autoplay next** ·
quality ceiling readout (`2.4 Mbps · ≤720p · 30 s buffer`). Each row is one `SettingsRow` plugin.

## 5. Explicitly not doing

- No paid "debrid" service — the owner is not investing money, so the gateway runs on our own box.
- No GPL engine linked into the MIT app — engine is either the hub (server-side) or a separate app.
- No promise that a dead swarm plays smoothly. The UI says *why* a source was picked and shows the
  risk chip, so a stall is explained, not mysterious.
