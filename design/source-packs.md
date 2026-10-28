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
