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
- Runs **one ladder job at a time**; opening another title evicts the old job. Finished/idle jobs stay
  for five minutes after the last request, then are swept.
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

- Resume keeps the direct copy. A ladder generated from zero would make the viewer wait for the
  transcoders to reach their playhead. A future resume-offset ladder needs the player to carry a
  timeline offset so seek, progress and subtitles stay in film time.
- The high rung is transcoded, not copied; a future version can copy the top rendition and solve
  keyframe alignment separately.
- Segments are generated and served, but not prefetched ahead of the player or cached beyond the
  five-minute idle window.
- The HLS player may begin on the lowest rung. Raising the initial rendition without rebuffering on a
  slow link is still open.
- Bitrates are ceilings: 1080 9 Mbps, 720 4.5, 480 1.5, 1440 11, 2160 16.
