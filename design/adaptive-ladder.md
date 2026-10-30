# Adaptive quality ladder

The ladder is an **opt-in** hub feature. It trades CPU and disk for the one thing copy-only playback
cannot do: change quality while the film is already running, at a segment boundary, without a decoder
jump.

It is not a packet switch between unrelated addon files. Those files are different timelines; mixing
their packets produces a different cut or a broken decode. A real ladder is made from **one** source.

## What it does

- Reads the copy the picker chose and the display ceiling the client reports.
- Chooses at most three rungs, never upscaling:
  - 720 source → 720/480
  - 1080 source → 1080/720/480
  - 4K source on a 1080 panel → 1080/720/480
  - 4K source on a 2160 panel, with `adaptive.maxHeight` 2160 → 2160/1080/720
- Transcodes all rungs from the same input with forced four-second keyframes and matching segment
  durations, so Media3 can switch variants at a boundary.
- Carries the audio stream the viewer selected and its language metadata.
- Serves `master.m3u8` and the variant playlists/segments from `data/ladder/<id>/`.
- Runs **one ladder job at a time**; opening another title evicts the old job. Finished/idle jobs are
  swept after five minutes without a request.

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
  transcoders to reach their playhead.
- The ladder carries the selected audio stream, not multiple alternate audio renditions.
- The high rung is transcoded, not copied; a future version can copy the top rendition and solve
  keyframe alignment separately.
- Bitrates are ceilings: 1080 9 Mbps, 720 4.5, 480 1.5, 1440 11, 2160 16.
