# Adaptive quality ladder

The ladder is an **opt-in** hub feature. It trades CPU and disk for the one thing copy-only playback
cannot do: change quality while the film is already running, at a segment boundary, without a decoder
jump.

It is not a packet switch between unrelated addon files. Those files are different timelines; mixing
their packets produces a different cut or a broken decode. A real ladder is made from **one** source.

## What it does

- Reads the copy the picker chose, the source's measured picture height, the owner's ceiling and the
  display ceiling the client reports.
- Chooses at most three rungs, never upscaling either the source or the panel:
  - 720 source → 720/480
  - 1080 source → 1080/720/480
  - 4K source on a 1080 panel → 1080/720/480
  - 4K source on a 2160 panel, with `adaptive.maxHeight` 2160 → 2160/1080/720
  - 480 source → 480 only, which is not a ladder, so no job is created
  - below 480 → no ladder; the direct copy plays
- Uses the **probed** height when the filename has no quality, or when the addon mints its URL at
  play time. The filename's `720p` is only a fallback when nothing measured the file.
- **Resumes with the ladder.** A play request that carries `positionMs` above one second starts the
  transcoder at that playhead (`-ss` before the input). The playlist's own timeline begins at zero;
  `ladder.startMs` tells the player where that zero sits in film time. The native player adds it back
  for the clock, seek bar, intro chip and saved progress, so a resume at 12:34 still reads 12:34.
  The returned duration remains the full film length.

### Audio

A copy with one audio track keeps that track muxed into every video variant, exactly as before.

A copy with two or more tracks becomes an HLS audio group:

```text
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="eng",DEFAULT=NO,...
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Hindi",LANGUAGE="hin",DEFAULT=YES,...
#EXT-X-STREAM-INF:...,CODECS="avc1.64001f",AUDIO="aud"
v720/index.m3u8
```

- Every video variant is picture-only: the video process maps no audio.
- Every audio track becomes an audio-only rendition of the same timeline.
- The profile's language order chooses the `DEFAULT=YES` rendition, not the container's default flag.
- The player can change language at a segment boundary. It does not ask the hub for a new `/play`, so
  it cannot land on a different copy.
- The play answer carries the real rendition ids back as `audioTracks`, so native and web menus show
  the same languages the playlist advertises.
- Cost: one extra ffmpeg process per audio track. A three-rung, two-language ladder is five processes;
  the one-job-at-a-time rule still applies.

## Delivery

- Transcodes all rungs from the same input with forced four-second keyframes and matching segment
  durations, so Media3 can switch variants at a boundary.
- Serves `master.m3u8`, the variant playlists and segments from `data/ladder/<id>/`.
- Waits for every video playlist and every audio playlist before answering ready.
- Segment responses support HTTP `Range` and are marked immutable for ten minutes; playlists stay
  `no-cache` because an EVENT playlist grows while the transcode runs. A retried segment therefore
  resumes instead of downloading from zero.
- Runs **one ladder job at a time**; opening another title evicts the old job. Finished/idle jobs stay
  for five minutes after the last request, then are swept.
- On a hub in the household's own network, the native player starts the first chunk on 720p rather
  than waiting on 480p to measure the link. It seeds media3's bandwidth estimate at 9 Mbps and pins
  the 720 rung for the first frame; adaptive selection takes over as soon as that frame is drawn. A
  remote hub keeps media3's cautious start, because the LAN assumption does not hold beyond the
  router. The network-type callback is kept from replacing that seed with the country default.
- The native Quality menu lists the ladder's own rungs, and `Auto` returns to adaptive selection.
  Choosing a rung pins that rendition of the same HLS stream; it never asks the hub for another copy,
  so a quality change cannot land on a different cut. Other copies remain below an `Other copies`
  heading for an explicit source change.
- Reuses a warming job when the URL, rung set and audio set all match. A repeat Play waits on the job
  that already exists instead of killing it.

## Configuration

```jsonc
"adaptive": {
  "enabled": false,     // owner opt-in
  "transcode": true,    // needed for a real ladder
  "maxHeight": 1080     // highest rung offered; the client ceiling may lower it
}
```

Settings → Playback → Playback smoothness exposes the toggle and top-quality picker.

## Requirements

A transcode-capable ffmpeg. The static 7.0.2 build on the reference box core-dumps when transcoding
an HTTP input. The working build is installed at:

```text
~/tvhub/bin/ffmpeg-btb
~/tvhub/bin/ffprobe-btb
```

`scripts/deploy-hub.sh` prefers those when present; otherwise it falls back to `bin/ffmpeg` for
remux-only installs.

## Limits / next steps

- A resumed ladder cannot seek before its own start point: the transcoder began at the resume
  playhead, so seeking earlier would need a second ladder from zero or a fall back to the direct copy.
- External subtitle files are timed from the beginning of the film, while an offset ladder's media
  timeline starts at the resume point. The native player therefore leaves subtitles off for a resumed
  ladder rather than showing lines minutes early. Embedded subtitle tracks are already absent from a
  ladder.
- The high rung is transcoded, not copied; a future version can copy the top rendition and solve
  keyframe alignment separately.
- The hub does not prefetch segments beyond what ffmpeg has already produced, and the TV does not
  keep a segment cache of its own. A pause longer than the idle window drops the job; a later Play
  starts a fresh ladder at the saved playhead instead of reusing the old bytes.
- Bitrates are ceilings: 1080 9 Mbps, 720 4.5, 480 1.5, 1440 11, 2160 16.
