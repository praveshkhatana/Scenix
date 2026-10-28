# Native Home / browse

The television draws Home itself; everything else stays on the merged web interface.

```
profile gate (web)
        │  Scenix.openNativeBrowse(profile)
        ▼
  BrowseActivity  ── GET /app/v1/home ──►  hub / LocalServer
        │
        │ tile
        ▼
  WebShellActivity  #/detail/<identity>   (web Detail → web Player)
        │
        │ web Home / BACK at the root
        ▼
  BrowseActivity (the activity underneath)
```

## Why

Profiling the 2 GB VIZIO/VU1 panel showed the focus handler under 1 ms; the cost was WebView
scroll/raster. A native vertical RecyclerView of horizontal RecyclerViews reuses about 30–50 card
views instead of rebuilding a 262-card page, and moves a compositor layer for scrolling. It is the
only way to approach native Apple-TV/Netflix smoothness with this hardware.

## Contract

- `BrowseApi` is the only native read path. It calls `/app/v1/home?profile=`, with the same optional
  Basic token as the shell. No native screen parses or ranks sources.
- `BrowseActivity` receives the profile id from the web profile gate. It never guesses a profile: a
  missing id sends the viewer back to the web gate.
- A tile starts `WebShellActivity` at `#/detail/<encoded identity>` with `EXTRA_FROM_BROWSE`.
- The page calls `Scenix.openNativeBrowse(profile)` when it mounts Home. If the page was opened from
  native, it simply finishes back to the existing BrowseActivity; otherwise it starts BrowseActivity
  and finishes. That is the handoff in both directions.
- The web Home action, or BACK at the page root, returns to the native activity underneath. Web
  Detail, Search, Player and Settings are unchanged.

## Rendering

- `BrowseActivity` — top bar (Search/Settings), a vertical RecyclerView whose first item is the hero
  and whose remaining items are shelves.
- Each shelf is a horizontal RecyclerView; cards are `FrameLayout`s with an ImageView, a title scrim
  and the existing `Focus` ring/scale. Focus is native, not a WebView focus engine.
- `BrowseImages` uses a 48 MB LRU cache, four workers, `RGB_565`, one HTTP download per image (bounds
  decode then sampled decode), and a tag check so a recycled card cannot show the previous poster.
- Rows are recycled by RecyclerView; card views are recycled horizontally.

## What this does not solve

- Detail, Player, Search and Settings still pay the WebView cost. This is Stage 1: the browse surface
  is native. Native Detail is Stage 2 if the panel still needs it.
- A profile switch from web Settings returns to the native activity with the profile it was built
  from. A native profile chooser/refresh is a follow-up.
