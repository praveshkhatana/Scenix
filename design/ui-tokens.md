# Interface tokens

Every number here was measured against a 1920×1080 television driven by a D-pad, then implemented
literally. `assets/theme.json` carries the same values for the native shell, and the web layer mirrors
them as CSS custom properties. Change a number in one place and you have broken the design.

## Grid

| Token | Value |
|---|---|
| Screen | 1920 × 1080, scaled by `min(w/1920, h/1080)` — never re-laid-out |
| Side gutter | 76 px (96 px at the top of Home) |
| Landscape tile | 320 × 180, gap 20 px, five per row plus a 120 px peek of the sixth |
| Poster tile | 280 × 420, gap 28 px |
| Row inner vertical padding | 40 px; the first row sits 140 px under the billboard |
| Billboard height | 842 px, scrims baked as gradients (never `backdrop-filter` over video) |
| Title logo | 640 × 192, falling back to 60 px / weight 900 text |

## Focus and preview

| Token | Value |
|---|---|
| Focused tile | `scale(1.10)`, 5 px `#fff` ring, 200 ms |
| Dwell to expand | 250 ms |
| Dwell to start a preview | 1500 ms |
| Scroll rule | the focused tile is centred at `itemCenter − 960` |
| Entrance | fade-up 600 ms, `translateY(20px)`, 70 ms stagger, 100 ms delay, ease `[0.43,0.13,0.23,0.96]` |
| Billboard rotation | 6000 ms, 500 ms crossfade |
| Order | D-pad order equals visual order, no traps, focus never obscured |

## Player

| Token | Value |
|---|---|
| Top chrome | `rgba(0,0,0,.55)`, 80 px sides, 48 px top |
| Bottom chrome | `rgba(0,0,0,.82)`, 96 px sides, 32 px top, 56 px bottom |
| Progress bar | 8 px tall, 4 px radius, track `rgba(255,255,255,.2)`, fill `#E50914` |
| Buttons | 144 px circles (Play 176 px); focus background `rgba(229,9,20,.85)`, 4 px `#fff`, `scale(1.08)` |
| Auto-hide | 6000 ms, three zones |
| Skip Intro | 200 × 72, offered 5–90 s in |
| Next episode | 400 × 225 card, last 7 s |

## Profiles and ranking

| Token | Value |
|---|---|
| Avatar | 280 px, gap 72 px, radius 62 px, border 3 px → 6 px on focus |
| Switch animation | container `scale(.9)` 800 ms, avatar spring (damping 12, stiffness 100), 100 ms stagger |
| PIN card | `#181818` panel, four 128 px boxes, `#232323` fields, `#2F2F2F` borders |
| Top 10 numeral | 208 px, weight 900, fill `#000`, 8 px stroke `#7C7C7C`, overlapping the poster by 96 px |
| Info line | match `#46D369` → maturity → runtime → HD badge |
| Play button | 320 × 72, white on `#E50914` |

## Palette

`#141414` page · `#181818` surface · `#232323` field · `#2F2F2F` border ·
`#FFF` text · `#B3B3B3` secondary · `#737373` muted · `#E50914` accent · `#46D369` match ·
`rgba(255,255,255,.10)` hairline. Typeface: **Scenix Sans** (Regular / Medium / Bold, bundled with the
hub). The binaries in `hub/web/font/` are a vendor face standing in for ours and must be swapped for an
open-licence equivalent with matching metrics before distribution.

## Motion law

Animate only `transform` and `opacity`. Three duration families: 120–160 ms, 200–300 ms, 500–800 ms.
Exits are faster than entries (150 out / 200 in, 480 out / 640 for a sheet). Ease-out arriving, ease-in
leaving, linear only for a scrub or a buffering ring. Infinite animation belongs to loaders only.
`prefers-reduced-motion` collapses everything to a 1 ms opacity change. Never `backdrop-filter` over
video, never a shadow on every tile, never a permanent `will-change`, never a layout read inside a key
or scroll handler.
