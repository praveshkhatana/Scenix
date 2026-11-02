# Design contract — Kids Stage (`scenix-kids`)

Mode A (pixel-replica). Source of truth: `refs/kids/reference.png` — the owner's corrected
reference (2026-11-01, "i have shared correct reference image. use this"). The reference is a
2181×1345 mockup @1x of a kids home ("HOPPERS"); its rounded outer frame is presentation
framing only — the owner said "ignore outer edge rounded corners" — so the app paints the
yellow stage across the WHOLE canvas. No marks or characters from the reference are copied;
only geometry, color, type and component behaviour are replicated.

**Viewport translation:** reference 2181×1345 (aspect 1.621) → app canvas 1920×1080
(1.778). ONE uniform scale **s = 0.803** (1080/1345) for every size; the leftover
1920 − 2181×0.803 ≈ 169 px of width is absorbed by widening hero card B and letting the
tile rail run off the right edge (the reference itself shows the rail cut mid-tile).
All geometry below is quoted as `ref px → canvas px`.

## 1. UI inventory (bounds measured off run-length scans, ref space)

| region | ref bounds | notes |
|---|---|---|
| page | whole canvas | flat yellow, no gradient |
| brand circle | (73,62)–(183,172) d=110 | black disc, white glyph |
| tab pill | (224,82)–(674,159) 450×77 | black capsule r≈38; active segment yellow text, others white |
| search knob | centre (640,121) d≈61 | yellow disc at the pill's right end, black magnifier |
| avatar disc | centre (1795,114) d≈80 | household face, separate from the pill |
| profile pill | (1849,74)–(2099,154) 250×80 | black capsule r=40; name bold white, sub quiet grey, chevron |
| hero card A | (74,238)–(846,680) 772×442 r≈40 | full-bleed photo, white title top-left, dark subtitle under it, black play disc + white label bottom-left |
| hero card B | (913,238)–(2079,680) 1166×442 r≈40 | plate with horizontal gradient deep→bright, centred lockup, key art bleeding from the RIGHT (outer) edge, play disc bottom-left |
| chip row | y 742–849 (h=107) | black capsules, uniform w=273, pitch 292 (gap 19), start x=73; white icon ~55 + white label ~30; 8th chip cut at right edge |
| tile rail | y 916–1252 (h=336) | yellow squares 336×336 r≈30, pitch 413 (gap ≈77), start x=90; character art fills the plate; caption centred below +20 |
| caption row | y ~1272–1300 | uppercase, letter-spaced, ink |
| blurred backdrop bleed | x ~1250–2181, y ~850–1345 | hero key art blurred, shows behind the rails on the right half |

Hierarchy: the two hero cards are the single primary pair; chips are shelf jumps (secondary);
tiles are browse (primary browse target); nav row is wayfinding. The reference shows only
default + one focused tile (LOAF); all other states are inferred (§3 matrix).

## 2. Tokens (→ `app/src/main/assets/theme-kids.json`; every value tagged)

### Color (sampled at named points; JPEG ±3)
| token | value | source |
|---|---|---|
| page / stageSheet / bg | #FBD364 | [m] (30,200),(2150,600),(1090,1330) — flat everywhere |
| tileFill (cardTint, surface) | #F9C406 | [m] (410,1090),(960,950),(1360,950) |
| plateInk (pill, brand, chipBg) | #050505 | [m] (300,145),(128,150),(300,795) → #000000-ish, snapped off pure black for print |
| knobYellow (search) | #FDB916 | [m] (640,140) |
| heroPlateL / heroPlateR | #F2AE01 / #FBC000 | [m] heroB left→right gradient (950,y) vs (2050,y) |
| focusGlow | #FDDB6F | [m] solid band around focused tile, (872–912, 1090) and (660, 856–894) |
| focusKeyline | #A88200 | [m] (border core ≈ #4A3301, edge ≈ #A88301); kept from round-3 device measure |
| ink (all text on light ground) | #141210 | [m] caption strokes, "Trailers" over photo (≈ #312201) |
| inkOnLight quiet meta | #6E6250 | [c] "Permium" sub-line grey; reference grey unresolved through JPEG |
| labelWhite | #FFFFFF | [m] chip labels, hero title |
| segActive | #FBD364 | [e] active segment glyph (≈ #C6A84E over black) — reads page-yellow |

### Geometry (canvas px = ref × 0.803, rounded)
| token | ref | canvas |
|---|---|---|
| brand circle d | 110 | 88 |
| tabPill | 450×77 r38 | wrap×62 r31 |
| searchKnob d | 61 | 49 |
| avatar d | 80 | 64 — welded INSIDE the left end of the name capsule (owner round 5: one element) |
| profilePill | 250×80 r40 | ONE pill wrap×64 r32: avatar d64 + name bold 26 white + drawn chevron-down 24 (`ic_chevron_right` rotated 90°); no separate disc, no "⌄" glyph |
| heroH | 442 | 355 |
| hero A / gap / B | 772 / 67 / 1166 | gap 54; A:B keep the reference's 40:60 ratio as row weights (measured 526:805 canvas on device; the 169 px width slack is shared by both cards so the row spans full width — see §5) |
| heroRadius | 40 | 32 |
| playDisc d (hero) | 55 | 44 |
| chip | 273×107 pitch 292 | 219×86 pitch 234 gap 15, r=43 (full) |
| chipIcon box | 55 | 44 |
| tile | 336×336 r30 pitch 413 | 270×270 r24 pitch 332 gap 62 |
| tile focused | 373×373, border ~5, glow band ~40 | 300 (scale 1.11), keyline 4, glow reach 40 |
| caption gap / unfocus caps / focus caps | 20 / 22 / 31 | 16 / font 24 / font 34 |
| nav text | ~26 | 21 |
| chip label | ~30 | 24 |
| section margins | 73–74 | 59 → gutter 60 |
| vertical rhythm | nav bottom 172 → hero 238 (66); hero 680 → chips 742 (62); chips 849 → tiles 916 (67) | 53 / 50 / 54; shelf header → first tile = `spacing.railPadV` 24 + header pad 12 (owner round 5: gap was 77+12 and read as a void; Kids' glow is the page's own colour family so the trimmed band above the top row is invisible) |
| kidsHomeHeight | — | 50+88+53+355+50+86 = 682 (token `pack.kidsHomeHeight`; the hero slot may only shrink to height−`spacing.heroReserve`, and Kids sets `heroReserve: 140` so the stage is never squeezed under its own height — at 200 the chips bled over the first shelf header) |

### Typography
Family: rounded geometric sans (system default stands; [c]). Captions UPPERCASE with wide
letter-spacing ([m] letter gaps ~10 px ref). Hero title bold; card B lockup centred.

## 3. Components (KidsKit — one kit, identical on every screen)

`KidsKit` builders consume ONLY tokens from `Theme`; focus routing stays with the composition.

1. `brandDisc(d)` — plateInk circle + white mark. The disc is a focusable stop, not decoration:
   it doubles as the Home stop (round 6) — control halo, `onClick → onSection("home")`, LEFT
   from the first tab pill lands on it, RIGHT walks back.
2. `tabPill(segments, knob)` — plateInk capsule, h=tabPill; segments text segActive/white; searchKnob = knobYellow disc, black glyph, flush at the pill's right end (3 px inset).
   Segments fill the capsule height (`minimumHeight` kidsPillH, MATCH_PARENT layout height) so a
   focused segment's control halo rings the whole capsule instead of slicing through the label
   (owner round 7, "navbar focus issue"). Only the segment matching the active section paints
   segActive — on Home no segment is selected (owner round 7).
3. `profileStop(avatar)` — avatarDisc (page-ring 3) + plateInk pill h=profilePill: name bold white, sub quiet grey, chevron.
4. `heroCardA(art,title,sub)` — photo full-bleed, radius 32; title white top-left, sub ink under it; playDisc bottom-left. The top/bottom ink-wash overlays carry the card's own cornerRadii — a square wash over rounded art painted grey wedges past the curve (owner round 6, "odd corners").
6. `heroCardB(art,title)` — plate gradient, centred lockup, art bleeding from the RIGHT edge, playDisc bottom-left.
7. `chip(label,icon)` — plateInk capsule, uniform width, white icon + white label.
   The row scrolls in a HorizontalScrollView (no scrollbar, OVER_SCROLL_NEVER, clipChildren/
   clipToPadding off, side padding px(20) so a focused chip rings free of the edge): walking
   RIGHT reaches every chip and the focused one scrolls into view (owner round 7, "it doesn't
   scroll so I can see the rest items"). Glyph vocabulary — ▶ Continue, ▲ Trending/Hot, ✶ New,
   ★ Top/rated; shelves without a match print the label alone. No "●" placeholder dot (read as
   a broken chip), no colour emoji: ♥ stayed red on the TV emoji font even with U+FE0E, so
   Trending wears the monochrome ▲ (owner round 7, "these tabs issue").
8. `tile(art,caption)` — tileFill plate r24, art fills, caption centred below.
9. `focusHalo(view,radiusPx)` — bands of #FDDB6F out to the measured reach + 4 px #A88200 keyline; on tiles, pair with scale 1.11.

State matrix (every interactive component): default [m] / focused [m] (tile: scale + keyline +
bands; pills/chips/hero: bands + keyline, no scale) / pressed [c] (sink alpha .85, 120 ms) /
disabled [c] (ink alpha .40) / loading [c] (plate shimmer tileFill→surface2) /
empty [c] (plate + ink dot glyph). Never a black border as the only focus cue; never a hard
box (owner round 3).

## 4. Motion (unchanged law; families from design/ui-tokens.md)
Transform + opacity only. Focus halo 130 ms in / 110 ms out; tile scale 1.11 over 130 ms;
rail scroll 240 ms; hero rotate 6 s crossfade 500 ms; screens 180 ms.
Every number first from theme.json `anim`/`focus`.

## 5. Deviation log
- [c] Outer rounded frame + black bottom bezel in the mockup = framing, ignored (owner).
- [c] Reference copy ("HOPPERS", "Scenes/Characters", "Lydia G", "Permium", character names) is the mock's content; the app shows the household's real shelves/profiles. Geometry and language replicate; words do not.
- [c] Bell circle dropped (no notifications yet — owner "fake affordance" rule); the gear keeps its slot so the cluster's geometry matches.
- [c] Character cut-outs on yellow plates vs our opaque hub posters: plates replicate the reference fill; posters stand in for the cut-outs (real content differs — landmark audit, not raw diff, is the gate here).
- [c] Blurred backdrop bleed behind the right rails: our stage backdrop already crossfades to the focused tile's art; kids pack enables it under the sheet so plate gaps carry the blur. If the blur reads muddy on device, ship flat and log.
- [m] Focus glow/keyline confirmed on the corrected reference (round-3 vocabulary was right; band measured reach ~40 ref px).
- [e] segActive, quiet-meta grey, hero radius 32, caption letter-spacing: JPEG-limited sampling; flagged for device re-sample.
- [c] Reference rail has no section header; our rails keep real shelf headers (information over decoration), ink, smaller.
- [c] Hero absolutes 620/≥936 assumed the row spans 1800 canvas, but the reference row occupies only 1631 of the 1800 (its 1.621 aspect leaves outer slack). Implementation preserves the reference's A:B ratio (measured 526:805 canvas, gap 54) and lets the row span full width — ratio-faithful reflow instead of widening B alone.
- [c] Rail tiles print a quiet meta sub-line ("New · year · match") under the caps caption; the mock shows names only. Data beats decoration here (information over decoration), kept in the pack's ink. — SUPERSEDED round 5: the meta lives ON the tile (NEW/match pill top-left, ★rating + cert corner top-right); only the name prints below.
- [c] Round 5 owner asks, implemented: hero logo lockups (`item.logo` over the title slot, printed title as fallback); brand disc wears the Scenix wordmark drawable; avatar+name merged into one pill with a drawn chevron; chips shortened (`shortChipLabel` strips the hub's conversational prefaces, glyph box 44→token chipIcon 40) because 219-px capsules truncated to "Continue watchi…"; shelf header → rail gap trimmed via `spacing.railPadV` (Kids 24 — the glow there is the page's own colour family, so the sliver above the first row is invisible).
- [c] Round 5 readability: settings rows fill `colors.listFill` #F9C406 (was the page's own colour — rows had no boundaries); player menu sheets use `colors.panelScrim` #FFF6DC (dark-ink text on dark was unreadable); every list row lifts by `focus.rowScale` with widened padding, and compact controls take the stroke-only halo (component §3.9).
- [c] Round 6: the mock's brand disc is decoration; ours doubles as the HOME stop (the section pill has no Home tab and goTab's CLEAR_TOP leaves Home underneath, so the owner found "no way to go back to home screen"). LEFT from the first tab pill reaches it, OK walks to the unfiltered mix; BACK from a section now returns Home first instead of exiting the app (BrowseActivity.dispatchKeyEvent).
- [c] Round 7: a kid profile has no generic `movies`/`series` hub row — the API answers "row not for this profile", which the tab printed as raw error text. The section now falls back to filtering that profile's own shelves by item type; and when even the derived-shelf gates (≥6 per rail) all fail on a small pool, one `all` rail prints the whole section rather than a hero over an empty page ("All Movies"). The Search keyboard stopped hard-coding its plate radius: keys read `radii.key`/`radii.field` and wear the pack's control halo like every other compact control.

## 7. QA gates
- `qa/kids/ref_grid.png` (50 px labelled grid over the reference) is the measurement sheet.
- Device capture: emulator-5554, my APK (md5 of pulled base.apk must match the build), theme pref `scenix-kids`, walk to the state, `adb exec-out screencap` → `qa/kids/impl-*.png`.
- Landmark audit (±6 canvas px): page flat-sample; brand disc centre; pill bounds; knob centre; profile pill bounds; hero top/bottom/left/right + gap; chip h/pitch; tile size/pitch/radius; focus band reach + keyline. All [m] colors sampled back at the same relative points.
- `pixel_diff.py` chrome-masked (mask hero art, posters, captions, names): report %, explain residuals; raw <2 % only if content matched (it does not — deviation 2).
- Contract↔token parity: `contract_check.py design-contracts/kids.md app/src/main/assets/theme-kids.json` exits 0.

### Gate results (2026-11-02, build md5 416ed82ee8c6e51d0dfb70827b6747f3, ui_zoom=1.0 so device px = canvas px)
- page: flat #FBD364 at (960,150), (960,1060), (20,540) ✓
- brand disc d 88 (60..147 @y94) ✓; pill h 62 (63..125 @x500) ✓; knob d 49 ✓; gear/avatar 64 ✓; search dot in pill end ✓
- hero: top 188 (target 191 ✓), h 361 incl ~6 shadow (target 355 ✓), A 704 / gap ~50 / B 1074 — ratio 0.655 vs reference 0.662 ✓ (see §5 ratio-faithful reflow)
- chips: h 86 ✓, w ≈ 213–219 ✓, uniform pitch ✓
- rails (impl-rails.png): tile pitch 335 vs 332 ✓, plates #F9C406, caps UPPERCASE ✓, focused tile glow band + warm keyline + scale ✓
- fixed this round: tab pill was WRAP_CONTENT-height (64 px) → explicit kidsPillH; profile capsule same; hero slot clamp got its own `spacing.heroReserve` (140 for Kids) because the 200 default squeezed the 682-px stage and the chips bled over the first shelf header.
- raw pixel_diff across aspect 1.621 vs 1.778 is NOT meaningful (skill rule); landmark audit is the gate. Sign-off sheet: `qa/kids/side-by-side.png`.

### Gate results — round 5 (2026-11-02, build md5 4b44cf019536154cb3280b6addd3f951, ui_zoom=1.0)
Owner round-5 list, verified on device (captures `qa/kids/r5-*.png`):
- hero logos: HANUMAN ANSH logo lockup prints centred in card B, printed title as fallback (card A has no logo) ✓
- brand disc: Scenix wordmark on the black disc ✓; household: ONE pill (face welded into capsule, drawn chevron) ✓
- chips: no truncation ("Continue", "Top 10", "Acclaimed", "New releases"), glyph 40 ✓; focused chip keeps black fill + white text with keyline ring ✓
- rails: header→tile gap 77+12 → 24+12 ✓; square tiles wear NEW/match + ★rating + cert ON the art ✓
- category tiles: "Drama/Comedy/Crime/…" labels paint over the gradient (root cause: overlays at elevation 0 buried under the elevation-12 framed photo — all overlays now ride elevation 14) ✓
- settings/profiles: #F9C406 plates on the #FBD364 page read; focused row zooms 1.03 (pulled back from 1.06 — the plate carried the label past the screen edge; row padding widened for zooming packs) ✓
- player menus: `colors.panelScrim` → #FFF6DC cream sheet (dark-ink text was unreadable on the dark scrim); focused dialog chip reads with glow ring + keyline ✓

### Gate results — round 6 (2026-11-02, build md5 597660de6e3513ed56251d0d3bcff861, ui_zoom=1.3 functional)
- Home affordance: Movies section → UP to pill → LEFT onto the brand disc (wordmark legible, keyline ring) → OK returns to the unfiltered Home mix (hero back to The Widower) ✓; BACK from Movies lands on Home, second BACK exits to the launcher ✓ (captures r6-homefocus.png, r6-backhome.png).
- hero corners: Movies hero (Clayface, light art) top-left and bottom-right crops (r6-corner-tl.png, r6-corner-br.png) — washes follow the r32 curve, page colour shows through the corner, no grey wedges ✓.

### Gate results — round 7 (2026-11-02, builds md5 3ebac552… / a4c94af5d561762d6cbd043680ce322d / f849bbaee9b1c037bb52eaad81eb962a, ui_zoom=1.3 functional)
Owner round-7 list + full Kids-pack screen sweep, verified on emulator-5554 (captures `qa/kids/r7-*.png`):
- navbar focus: focused segment now wears a clean full-height capsule halo — ring no longer cuts through the label (r7-segf-crop.png; segments fill kidsPillH).
- active segment: Home prints NO selected tab (r7-home.png); Movies/Series mark only their own segment gold (r7-adult-movies.png, r7-adult-series.png).
- chips: no "●" placeholders, Trending now ▲ and Top rated ★ print monochrome white; the row is a HorizontalScrollView — walking RIGHT from Trending scrolled Action/Thriller into view with the focused chip's ring unclipped (r7-adult-movies/-chips-end/-chips-action.png).
- kid sections: Pinki Movies/Series fall back to her own shelves; tiny pool prints one "All Movies" rail instead of a hero over blank page (r7-movies-top.png vs r7-movies-all.png).
- sweep: search (r7-search2.png — radii.key/field tokens + halo keys), settings (r7-settings.png), profiles (r7-profiles.png), detail incl episodes (r7-detail.png, r7-detail-scrolled.png), player + controls (r7-player.png, r7-player-controls.png), live wall (r7-live.png) — all read in-pack.
