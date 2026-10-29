# Source packs, the FrameXTV addon, and mixed-language playback

Three addons make the hub able to answer "play this in my language" when no single copy of the title
carries that language. None of them names a website in the app's code: what each one points at lives in
`hub/data/settings.json`, which the owner edits.

## The addons

| Addon | File | What it is |
|---|---|---|
| `framextv` | `hub/providers/framextv.js` | a normal catalog+meta+stream addon for one aggregator API. Its base URL is a setting-like constant (`FRAMEXTV_API`), and the hub never hardcodes the upstream names behind it. |
| `igc` | `hub/providers/igc.js` | an owner-defined **source pack**: streams-only, no catalog of its own. Each descriptor is a URL template plus a language list. |
| `audio-pack` | `hub/providers/audio-pack.js` | an owner-defined **audio-only pack**: rows that carry a language's audio and no picture. They are donors, never the video. |

"IGC" is the name in the settings, in the code and in the interface. There is no other spelling for it
anywhere in this repository.

## Settings shape

```json
"igc":   { "sources": [ { "id": "…", "name": "…", "url": "…", "languages": ["hin"],
                          "quality": "1080p", "type": "movie", "headers": {}, "enabled": true } ] },
"audio": { "sources": [ …same shape, audioOnly forced… ] }
```

`url` is a template. `{title}`, `{year}`, `{type}`, `{season}`, `{episode}`, `{identity}` and `{lang}`
are substituted per item. A template containing `{lang}` expands to **one source per language** — that
is how a single descriptor offers a Hindi and an English copy as two ranked rows. Without `{lang}` the
descriptor is one source carrying the whole language list.

`headers` travel with the URL: they are applied to the player's request, to the prober's touch, and to
both ffmpeg inputs in a mix (see below). Nothing else in the app knows which host a descriptor points at.

## The donor rule

`/app/v1/play` ranks every copy as it always did. If the copy it picks does not state the profile's
first audio language, it looks for a **donor**: an `audio`-pack row, or another copy whose file states
that track. The picture stays the picture — usually the highest-quality copy that can carry the
bandwidth — and the donor's audio becomes ffmpeg's second input:

```
ffmpeg -i <picture> -i <donor audio> -map 0:v -map 1:a:<n> -c:v copy -c:a aac …
```

The response carries the result so the interface can say what happened rather than leave the viewer
guessing:

```json
"selectedAudio": "a:1",
"audioMix": { "language": "hin", "source": "…", "provider": "audio", "confirmed": true }
```

* **Source first.** A copy that already carries the language wins; a donor is only fetched when none
  does. An `audio` row is never ranked as a primary picture.
* **Stated over claimed.** A track the file itself states outranks a filename promise, exactly as the
  ranking rule does; `confirmed` vs `claimed` in `audioMix` says which one was used.
* **Seek stays honest.** With a `start=` seek the same offset is applied to both inputs, so the
  picture and the donor audio land on the same point of the film. A donor recorded from a different
  cut may drift — nothing in Scenix can align audio that was mastered apart.
* **Off on request.** `audioMix: false` (or `{ "enabled": false }`) in the play body skips the donor
  search; `audioMix: { "force": true }` mixes even when the copy already has the language.

The donor path needs `ffmpeg`, so `capabilities.audioMix` is advertised only when the binary is found;
a client on a hub without it behaves exactly as before.

## Verified on this box (2026-10-28)

With a generated 320x240 MP4 whose only audio is a 440 Hz tone, and an audio-pack row pointing at an
880 Hz M4A labelled `hin`, the pinned-IGC play returned `audioMix.language: "hin"` and
`selectedAudio: "a:1"`; the remuxed bytes probed as the original H.264 video copied plus one AAC track,
and the tone in the output measured 880 Hz — the donor's, not the picture's — with and without a 4 s
`start=`. The HLS path produced a playlist whose segments carry the same mix. The `framextv` addon
resolved a real episode of a live series to its upstream `.m3u8` at play time.

## Verified again (2026-10-29) and what the TV does with it

The hub on the television's LAN now has `ffmpeg`/`ffprobe` 7.0.2 static at `~/tvhub/bin/`, passed to
the process as `FFMPEG_BIN`/`FFPROBE_BIN` by `scripts/deploy-hub.sh`; before that the binary was
missing and `/healthz` said `remux.binFound: false`, so the feature was off even though the code was
deployed.

- **Negotiation, offline**: a 320x240 H.264 picture whose only track is 440 Hz *eng*, plus an
  audio-pack row pointing at an 880 Hz *hin* M4A. The pinned-IGC `/play` answered
  `selectedAudio: "a:1"`, one `audioTracks` row (`language: hin`, `kind: audio-mix`), `audioMix` with
  `language/source/provider/addon/plan/confirmed/claimed`, and a remux URL with `au=`, `ao=`, `ap=`,
  `u=`, `v=mp4`, `a=a:1`. `why` carried "audio: mixed Hindi from … while keeping the picture copy";
  `picked` stayed the IGC picture.
- **Bytes, offline**: the remux URL probed as one H.264 320x240 video (copied) plus one AAC track;
  decoding it and measuring the dominant tone printed **880**, not 440 — with and without `start=4000`.
- **HLS**: the playlist uses `init.mp4` + `.m4s`; `init.mp4` + `seg00000.m4s` probed as H.264 + AAC
  and the tone was 880. The first HLS request used to answer a 200 with **zero bytes** because
  `hlsServe` treated "the file exists" as "it has been written"; it now waits for a non-empty file.
- **Negative checks**: `audioMix: {enabled:false}` and `audioMix: {language:"eng"}` (the picture already
  claims English) return the direct URL with no `au`. `force:true` only mixes when a donor for that
  language exists (a Hindi-only pack cannot force an English mix) — expected, not a bug.
- **Real title**: `imdb:tt27369017` ("Hope", Korean) on the household hub negotiated
  `audioMix.language: hin` from a 480p torrent copy while keeping the 1080p direct picture, with
  `claimed: true` and `confirmed: false`. The current upstream picture/torrent copies failed at play
  time ("YouTube: a service page, not a stream", engine served nothing), so the mixed bytes could not
  be watched end to end for that title; the negotiation is the same one the offline test proved.

### The native television

`PlayerActivity` reads the same answer: the mixed track is selected (`a:1`), the top bar adds
"Hindi audio", and the Audio sheet names the row by language with "mixed from <donor>" as its note,
plus an **Original audio** row that re-requests `/play` with `audioMix:false` at the current playhead.
A viewer who turned the mix off gets the language row back to turn it on again. The web player already
prints the mix note from `player.audioMix`.


## Donor mapping and selection, 2026-10-29

- **Donor map.** The remux used `-map 1:a:<global index>`, which ffmpeg reads as "the Nth audio
  stream", so a donor whose Hindi track was `a:3` failed with `Stream map '1:a:3' matches no streams`
  and every mix answered zero bytes. It is `-map 1:<global index>` now; an absent index falls back to
  the donor's first audio (`1:a:0`), never stream 0 (the picture on a video donor). Verified with a
  forced Spider-Man mix: a confirmed Hindi donor produced H.264 + AAC with no map error.
- **Donor tracks.** A donor row's attached tracks can describe another file of the same torrent; the
  resolve step now probes the exact donor URL (1.5 s budget) and uses its global stream index and
  codec for the map/plan.
- **One picture.** `-map 0:v` mapped every video track (cover art / second angle); it is `0:v:0` so a
  mixed MP4 exposes one picture stream.
- **Native selection.** The player now maps the hub's `a:3` (global stream index) by the track's
  position in the answer's `audioTracks`, not by media3's group number. Spider-Man's Audio sheet
  shows Hindi checked on a Telugu/Tamil/Hindi file.
## Donor fit: never dub the wrong cut (2026-10-29)

A donor row only promises a language. The archive's Ransom Hindi rip measures 2 h 00 m for a 1 h 49 m
film — mapped as-is, that is eleven minutes of drift mixed with total confidence. So the hub now reads
the donor's own header before accepting it:

- `probeDonorUrl` runs `ffmpeg -i <url> -t 0.01 -f null -`; the `Duration:` line is printed before the
  body is read, so a short wait is enough. Cached per URL for 30 minutes, single-flight.
- A host that answers in ~1.2 s is measured on the press itself; a slow CDN (archive.org needs 15 s+)
  is measured in the background (`AUDIO DONOR MEASURED` in the hub log) and enforced on the next press.
- The selection loop refuses a donor whose measured length differs from the title's runtime by more
  than 4% (`DONOR_FIT_SLACK`) and logs `AUDIO DONOR SKIP ... runs X min against a Y min picture`.
  An unmeasured donor or a title with no runtime is no opinion — it stays offered.
- The same header read gives the audio codec, which overrides the plan claim: AAC/MP3 copy untouched,
  anything else re-encodes to AAC.
- The answer carries the evidence: `audioMix.donorDurationMs` and `audioMix.driftMs`.
- The probe runs `FFMPEG_BIN` (the deployed hub's static ffmpeg is not on the login PATH). It reads the
  **picture file's** own duration when the source was probed; a title's runtime is only the fallback for
  a movie. For a series the show runtime is not the episode, so no runtime-only comparison is used.
- On the merged tree the donor's probed track list (global stream indexes and codec) still decides the
  audio map; this length check is added on top, and a donor with several tracks takes its plan from the
  wanted track, not track 0.

Verified on the feature branch (2026-10-29): an 8 s test donor and the 121 min archive rip were both
skipped against a title whose runtime is 111 min (both logged with the minutes), the archive rip was
accepted blind on the first press and measured in the background, and a donor generated to the exact
6,660,000 ms runtime mixed with `driftMs: 0` and plan `copy`. The merged tree was not re-run against a
real archive donor; the binary-path and duration-source differences above are the review changes.
