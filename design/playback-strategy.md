# Playback strategy — making free sources feel like Scenix

Written after the owner asked: *"magnets can't play on a 2 GB TV without a torrent engine — why?
how do the existing apps handle it? can a player plugin do it better? use players that already exist."*
Plus their content preference and a reference web player.

## 1. What a magnet actually is

A magnet link is **not a stream**. It is a promise: *"this file is split into ~16–256 KB pieces,
held by some number of peers, go get them."* To play it you need an **engine** that

1. connects to peers (DHT/tracker),
2. downloads pieces **in playback order**, not file order,
3. keeps a buffer ahead of the playhead,
4. exposes the result as a **seekable stream** (HTTP with byte ranges) to an ordinary video player.

That engine is the whole product in apps like TorrServe, Nova Video Player, or the desktop apps' own core. It is CPU/RAM work (hashing every piece, managing peer connections) and it is
**at the mercy of uploaders**: if peers collectively send 0.69× realtime, no player on earth can
play it smoothly. That is exactly what we measured on this TV (0.69×, 8.5 MB/s) — the engine
worked, the swarm didn't.

Second structural gap: a torrent is **one fixed file**. There is no adaptive ladder, so there is
no "drop to 540p when Wi-Fi dips". Adaptive bitrate is what makes Scenix survive a bad network,
and a single 4.2 GB MKV cannot provide it.

## 2. How the existing ecosystem solves it

| Approach | Mechanism | Licence | Fit for us |
|---|---|---|---|
| **In-process core** (desktop apps, and standalone Scenix) | an in-process engine downloads sequentially and hands the player a local HTTP stream | MIT / BSD | Shipped for standalone as libtorrent4j on armeabi-v7a; it competes with the HW decoder and needs free storage, so the hub remains the recommended shape |
| **Companion engine app** (companion-engine pattern, used by companion player apps) | a separate app runs the engine + local HTTP server; players just open `http://127.0.0.1:PORT/…` | GPL-family for the well-known ones | **Best fit**: zero licence contamination (separate app, no linking), reuses an engine that already works on Android TV |
| **Server-side gateway** | the *hub host* runs the engine and serves the torrent to clients as an ordinary seekable HTTP stream | ours | **The Scenix-like answer**: the hub box is x86 with 32 GB and 85 Mbps wired; the TV then plays plain HTTP with no torrent code at all |
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
have no ladder. One tap = one play; the ranked array is the **Source** sheet in the player, not a
second decision on the detail page.

**Layer 1 — prefer sources that are already streams.** Community addons that return direct
HTTP/HLS (WatchHub-style scrapers, our live playlists, curated YouTube) *are* adaptive and
*are* what makes playback feel like Scenix. Torrents are the fallback that widens the library.

**Layer 2 — failover in the player (the multi-source failover pattern).**
The reference player offers `Auto`, `Auto (Mirror 1)`…`Auto (Mirror 5)`: the same title from
several sources, with automatic switching. We copy it with our word — **Source**:
if a stall exceeds ~6 s or the decoder reports an unsupported/fatal format, switch to the
next-ranked source **at the same position**, toast `Switched to Source 2 — first one stalled`,
keep going, and show the current source + its `why[]` in the overlay. Cap = list length.
This is the single biggest smoothness win, and it costs no new dependency. The web player owns this
logic; on a television it runs inside the shell and the same decisions reach the native Media3
surface through `window.Scenix`.

**Layer 3 — torrents, when they are the only option.**
`/app/v1/play` returns a `mode`, and the web player/Media3 bridge opens it accordingly:

1. `direct` — progressive HTTP + range seeking (the default).
2. `hls` / `dash` — adaptive ladders (live TV, HTTP addons).
3. `gateway` — the hub streams a torrent as seekable HTTP. To the client this is just another HTTP
   source, so it works on TV **and** web **and** mobile. The hub ships the seam
   (`core/gateway.js`, `GATEWAY=off|http`) and the engine later, as an explicit, optional,
   documented dependency — the hub stays dependency-free by default.
4. `external` — hand the URL to another app via intent when the device has one that can play it.

Standalone has its own fourth path: `TorrentEngine` (libtorrent4j, armeabi-v7a) downloads a magnet
sequentially into the app cache, chooses the wanted file, and serves it through the loopback server
with HTTP Range. Playback, pause and seek are therefore the same Media3 path as a direct file; a seek
into pieces that have not arrived waits for those pieces instead of pretending. The engine refuses a
file larger than the free space and downloads only the chosen episode from a season pack.

`mode` is the only thing that changes when a gateway is enabled: `sources[]`, `POST /app/v1/play`
and the UI stay identical. That is the contract. A browser that cannot draw the codec gets a
server-side re-wrap (`/app/v1/remux`, video copied, never transcoded); the television plays the file
as it sits on disk.

## 4. Player surface (copied from the reference, made 10-foot)

D-pad settings sheet, opened with **Down** or the gear: **Source** (Auto + ranked list) · **Subtitles**
(embedded + hub-served from `GET /app/v1/subs/<identity>?lang=hin,eng,pan`, side-loaded into Media3) ·
**Audio** (tracks, default = profile's language order) · **Speed** (0.75–1.5) · quality ceiling readout
(`2.4 Mbps · ≤720p · 30 s buffer`). The current certificate and content notes sit in the HUD, and live
TV replaces the transport's next/previous with channel stepping.

### Card previews

A focused tile expands after 250 ms and, after 1500 ms of rest, plays its trailer in place. The hub
hands over the short raw trailer URL (not the redirecting watch page); the proxy follows the CDN
redirect, and the element is created with `muted: false`, so the preview has sound and does not depend
on the viewer pressing anything. Moving focus frees the decoder and restores the still.

## 5. Recovery — what happens when a copy stops moving

A dead stream and a finished film arrive at the player identically: the connection closes. So the hub
states the length of the copy it just handed over (`durationMs` on the play answer, measured from the
file itself), and one client module decides what to do about it — `hub/web/js/stall.js`, used unchanged
by the browser player and the TV shell (both run the same page), verified by `scripts/check-stall.mjs`.

| token | value | meaning |
| --- | --- | --- |
| `STALL_MS` | 6000 | how long "no movement while something should be playing" counts as stuck |
| `STARTUP_MS` | 30000 | the grace a cold copy gets before the stall rule applies at all |
| `TRUNCATION_GRACE_MS` | 20000 | how close to the stated length an `ended` is allowed to land before it is believed |
| `HEAL_COOLDOWN_MS` | 120000 | minimum gap between silent reloads of one copy |
| `HEALS_PER_COPY` | 1 | how many times a copy gets rescued before the viewer is asked |

The television panel is the reason the startup grace exists: a cold copy can spend 10–25 s fetching its
first byte, and the player's own *Preparing this copy* screen is already the honest thing to show. A
stall card over a film that has never started, which then outlives the wait, is worse than no card. The
rule therefore only counts a playhead that has actually moved, and a moving picture clears the card
without waiting for a keypress.

Order of mercy: reload the same copy once, at the position it stopped, silently. If bytes return, say
nothing — the viewer never learns there was a problem. If they do not, ask, in the viewer's language and
with real numbers: which copy froze, where it froze, how many other copies this title has. *Choose
another stream* excludes that copy for good (it is greyed in the quality menu as `stalled here before`);
*Keep watching this one* spends the remaining attempts and leaves the picture alone.

Both signals — a frozen clock and an ending that arrives minutes early — go through the same decision
function, so a copy cannot earn a second free reload by failing in a different way. A live channel and an
unprobed file report no length; both are believed when they say they have ended.

The hub holds up its end of the same bargain:

- **No magnet is ever passed off as a stream.** Neither first-class player runs a BitTorrent client, so a
  magnet handed over as a playable answer becomes `unknown protocol: magnet` in the decoder and a screen
  that says the file is broken. When the gateway cannot produce HTTP, the play answer is `503` with
  `warming: true`, `retryAfterMs` and the magnet kept for anyone who can use one — the viewer gets *not
  started moving yet* and a chance to wait, which is the truth.
- **A failed prepare is remembered for as long as its reason deserves**, not by one shared timer:

| kind | what happened | retried after |
| --- | --- | --- |
| `waiting` | engine answering, torrent metadata not arrived | 30 s (`GATEWAY_MISS_WAITING_MS`) |
| `engine` | the engine stopped answering mid-ask | 45 s (`GATEWAY_MISS_ENGINE_MS`) |
| `dead` | the engine read the torrent; no video inside | 10 min (`GATEWAY_MISS_DEAD_MS`) |

  One blacklist for all three is how a merely cold copy ends up refusing to open until somebody restarts
  the hub — and how a promise to "wait and try again" turns out to be answered from memory.

- **A copy is chosen on the rate it was measured delivering, not on the rate its file size implies.**
  A Play press reads the copy through the hub's own buffer for `playback.sustainBudgetMs` and scores the
  *worst* `playback.rateWindowMs` window inside it. The engine hands over a cached head at tens of MB/s
  and then goes quiet, so an average over the sample flatters a copy that dies a minute in — playback
  dies in the worst window, so the worst window is the verdict. A copy that never produced a byte is
  `warming`, not slow: the hub's reader stays open and keeps reading, because the engine's first byte has
  been measured arriving seventeen seconds after it was asked for. A copy that produced bytes and then
  stopped carrying them is `starved`, and stays out of favour for `playback.starvedHoldMs`.
- **The hub reads ahead of the player.** One bounded cushion of RAM per copy
  (`playback.readAheadMB` / `readAheadSec`, whichever is smaller) sits between the swarm and the screen:
  the player reads at LAN speed out of memory while the torrent bursts and pauses, and the one sequential
  reader is the only thing touching the engine's per-torrent cache — which is what a pass-through pipe,
  a keep-warm touch and ffmpeg all doing their own reads used to fight over. A remux reads through the
  same cushion. The cushion is filled *before* the first frame is promised
  (`playback.preBufferSec`/`preBufferMB`, never longer than `preBufferMs`) and what it bought is returned
  in the play answer as `prebuffer`, so a screen can say what it is doing.

The rate floor is what makes "auto quality" mean something here: the sharpest copy the swarm can actually
feed is chosen, and when it cannot, the viewer is moved down a rung instead of being left with a freeze.

These are timing rules, not user settings, and they stay in code: what counts as stuck, how long a heal
waits, how many heals a copy gets. The buffer sizes, the pre-roll and the length of a rate sample *are*
settings (`core/settings.js` DEFAULTS, editable on the Hub Settings screen) because how much of a film
this box may hold in memory is the owner's call about their own hardware.

## 6. Explicitly not doing

- No paid Scenix tier and no bundled subscription. An owner who already has a debrid account may
  optionally let the hub exchange a magnet for ordinary HTTP through it; with no account the gateway
  path is exactly as described above. See [debrid.md](debrid.md).
- No GPL engine linked into the MIT app — the engine is either on the hub (server-side) or a separate
  app.
- No promise that a dead swarm plays smoothly. The UI says *why* a source was picked and shows the
  risk chip, so a stall is explained, not mysterious.
