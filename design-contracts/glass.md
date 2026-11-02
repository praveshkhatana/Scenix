# Design Contract — Glass (Liquid Glass) theme pack

Reference: `refs/glass/reference.png` (1355×964 web page screenshot of a liquid-glass TV app).
Replication target: Scenix Android TV, 1920×1080 canvas. Linear scale factor ×1.417 from the
reference to the canvas; vertical rhythm is re-flowed to sit above the 1080 fold (deviation D1).

Source-of-truth order: reference pixels → this contract → shared components (Views/Focus/Theme)
→ implementation. Tokens live in `app/src/main/assets/theme-glass.json`.

## 1. UI inventory (measured at reference px, converted to canvas px)

| Region | Reference bounds (x,y,w,h) | Canvas |
|---|---|---|
| wordmark (left header) | ~28,28 | x≈48, centered in nav band |
| nav pill (centred) | 530–833 × 28–72 | w≈430 h≈62, fully rounded |
| profile cluster (right) | ~1110–1330 | frosted pill avatar+name+⌄ |
| hero card A | 33–556 × 104–394 | x48 y147 w741 h411 r≈25 |
| hero card B | 570–1323 × 104–394 | x808 y147 w1067 h411 r≈25 |
| chip row | y 422–477, 7 chips | y≈598, chip h≈78, gap≈20, pill |
| shelf title | y≈548 | ≈y777 |
| poster rail | tiles y 595–858, 6 visible | w≈264 h≈373 (aspect .707), gap≈27, r≈17 |
| caption block | title y≈865, meta y≈912 | under tile: name + "● rating · year" |

Hierarchy: primary action = the two hero cards (play); secondary = nav pill segments / chips
(shelf jumps); metadata = captions. One wordmark only.

Interactive states (reference shows default only; others inferred → deviation log):

| Element | default | focus | pressed | loading/empty |
|---|---|---|---|---|
| hero card | tinted glass plate, white title top-left, dark circle Play bottom-left | lift + pale rim bloom | — | no slides → card B hidden (host rule) |
| nav segment | idle grey-ink text on dark pill | active = light fill + dark ink | — | — |
| search knob | light circle at pill end, dark glyph | keyline ring | — | — |
| profile pill | frosted dark pill, avatar + name + chevron | keyline ring | — | initials fallback |
| chip | frost + hairline rim, icon glyph + label | slightly stronger frost + keyline | — | fewer shelves → fewer chips |
| poster card | art r17 + caption below | glow halo (pale blue plates) + 1.03 scale | — | surface2 placeholder |

## 2. Tokens (canvas px unless noted; [m]=measured, [e]=estimated, [c]=chosen)

### Color
| Token | Value | Src |
|---|---|---|
| wallA / wallB (full-page wash, TL→BR base) | #859DA9 / #A5A5A7 | m (13×8 grid sample 2026-11-02: left/top uniform #849CA8 cool; right edge drifts to neutral #A5A5A7 — the previous warm #A59D9A was off-hue; the reference frame's black rounded-corner mask must be excluded when sampling edges) |
| wallBlobs (soft light-field over the base; the reference wash is smooth-photographic — strong blobs read as banding) | 50,5,90:#465A69:0.20;-8,55,45:#3A4A55:0.16;108,25,42:#2E3E4A:0.22;52,66,55:#BEC8D2:0.12;40,92,28:#D8C8B8:0.18;18,97,22:#C9C4B8:0.15;80,84,22:#C9B8A4:0.15;30,83,20:#A9B8C8:0.12 | e (alphas halved 2026-11-02: the first pass's saturated warm bokeh was inferred from poster art bleeding into the sample region; the wall itself keeps only a faint warm drift) |
| heroWashDeep / heroWashLight (OVERLAY painted on full-bleed hero art, not a panel) | rgba(34,90,146,0.95) / rgba(126,156,203,0.86) | m (fade scan of reference card A at y=190: solid rgb(34,91,145)→rgb(42,97,149) to 40%, blended 50–65%, art unobstructed from ~65%) |
| heroFadePct (wash reaches clear at % of card width) | 62 | m (same scan) |
| heroScrim (bottom wash under copy/Play, blue not black) | rgba(34,90,146,0.35) | m (reference bottom band #406E99 is LIGHTER than mid-card, i.e. a blue wash; a dark scrim was wrong) |
| plateElevation / posterElevation / chipElevation (cast shadow, canvas px) | 16 / 10 / 6 | e (shadow strength eyeballed against reference card separation from wall) |
| chipGlyph (chip pictogram size, canvas px) | 34 | m (~24 @ref ×1.417) |
| pill (nav bg) | rgba(8,16,28,0.86) sampled #0A1420 | m |
| pillActive (segment fill) | rgba(255,255,255,0.08) — no bright fill; active word = brighter ink | m (zoom: (24,34,48) vs pill (6,17,31)) |
| inkOnActiveSeg | #FFFFFF | m |
| pillInk (idle nav text) | #B7C4D2 | e (glyph AA blend) |
| searchKnob | rgba(255,255,255,0.13) raised circle, white glyph | m |
| heroTintA deep card | grad #225A92 → #2E6196 | m (grid remeasure 2026-11-01 core (34,90,146)=#225A92; first-iteration values superseded — shipped pack passed landmark audit) |
| heroTintB pale card | grad #87A2CD → #7E9CCB | m (grid remeasure sample #87A2CD; first-iteration values superseded) |
| glassRim (specular 1px on cards/chips) | rgba(255,255,255,0.38) | e (edge highlight) |
| chipFill | rgba(255,255,255,0.10) | m (chip (143,159,174) vs wall (132,155,170)) |
| inkOnGlass / captionInk | #FFFFFF (title #EAF1F6) | m |
| captionSub (meta grey-blue) | #A9BFCB | m |
| accent dot (rating) | #EF9F42 | e (core of #D3934E AA) |
| playDisc | rgba(6,20,42,0.88) | m (#05163A) |
| profilePill | rgba(8,16,28,0.80) | e |

### Typography (canvas px)
| Token | Size/wt | Src |
|---|---|---|
| brand wordmark | 34 bold | e |
| nav | 19 bold | e |
| heroTitle (h2) | 52/56 bold, maxLines 3 | m (~37 @ref) |
| playLabel | 22 bold | e |
| chip | 21 bold | e |
| rowTitle | 40 bold | e (~28 @ref) |
| caption | 26 bold | m (~18 @ref) |
| meta | 22 | m (~15 @ref) |

### Geometry
grid remeasure 2026-11-01 (ref px ×1.417 = canvas px):
gutter 57 (40@ref); pill 425×68 — FOUR words (Home/Movies/Series/Live, Home lit) + knob inside it,
segment side-padding token segPad 20 — audit 2026-11-02: 15 gave 384px, 20 lands 425±6
(first pass's 42px 3-word pill matched width only by luck);
hero top 142, height 397 (280@ref), split 744:1042 → weights 7:10,
heroGap 21; chips: equal-width row spanning the gutter (w=(1920−114−6·gap)/n, measured 228, 7-up),
height 104 (74@ref), gap 34; rail title top 772; poster 283×397 gap 21 radius 21; caption block 101
(name 35 below art, name bottomMargin 36, meta 22); play disc Ø42; hero radius 34; nav top 30,
pill→hero gap 44, hero→chip gap 64; rowTitle→poster gap 21.

### Radius / elevation
card(rail)=18, hero=26, chip/nav=999. Elevation: surfaces carry real cast shadows
(plateElevation 16 / posterElevation 10 / chipElevation 6 canvas px — system shadows from the
rounded outlines, separate from the inner rim + top sheen rgba(255,255,255,.16)→0 over top
45%); focus lift rides translationZ on the same outline. Material truth (platform research
2026-11-02): Android has NO public backdrop blur before API 36 — View.setRenderEffect (API 31)
blurs a view's OWN content, not what is behind it. The pack's "glass" is therefore: real cast
shadow + translucent tinted surface + specular rim + sheen (IMPLEMENTED), and frosted backdrop
blur of the wall behind surfaces is APPROXIMATED by the tinted wash over the wall, not by a
blur pass.

## 3. Component rules (glass-specific)

- Nav pill: centred, h62, padding 6; segments text-only; active segment light-filled pill with
  dark ink (measured); search welded at end as light circle 46 with dark glyph.
- Hero card: ONE continuous composition (owner ruling 2026-11-02, after the panel+image seam was
  rejected): full-bleed key art (centerCrop) across the WHOLE card; the card's tint is an OVERLAY
  painted over the art — near-opaque wash at the left edge fading clear at heroFadePct=62% of the
  width (measured scan of reference card A at y=190: solid rgb(34,91,145) to 40%, blended 50–65%,
  unobstructed art from ~65% right) — plus a bottom wash rgba(34,90,146,0.35) under the copy;
  title anchored TOP-left pad 40, Play row BOTTOM-left: disc 54 + label, over the scrim.
  Card A narrower than B (7:10) — measured asymmetry. The bottom wash is keyed PER TITLE: once
  the key art loads, the plate averages its upper-left region, boosts saturation ×1.6 and keeps
  the heroScrim alpha — the overlay rides the title's own colours, not one fixed blue (the
  "fixed gradient" deviation is superseded).
- Depth layers are SEPARATE: outer cast shadow = real view elevation (plate 16, poster 10,
  chip 6 canvas px — system shadow from the rounded outline, drawn OUTSIDE the surface; the
  focus lift rides on the same channel via translationZ); inner light = 1px specular rim +
  top sheen. Rim ≠ shadow; the first pass's "no drop shadow" rule was an unapproved substitution
  and is superseded.
- Chips: frost fill + hairline rim, pictogram glyph (chipGlyph 34 canvas px) + label CENTERED
  in the chip (measured on the reference crop — left-set content read cramped); height 78;
  never opaque. Labels keep the shelf's leading promise (Continue / Recommended / More like /
  Top 10 / Trending / Acclaimed / Just added — "Because you" was rejected as not meaningful).
- Rail captions: name then "● rating · year" — dot is accent token, rating number light,
  year in captionSub (reference meta line). Poster art bare (no scrim) on glass wall.
- Focus language = `glow`: pale-blue halo plates (#CFE3F5) + scale 1.03, 150 ms; controls get
  keyline (shared Focus rules). The SAME lift applies to rail tiles as to hero plates: elevated
  art outline (posterElevation) casts its shadow at rest, focus rides translationZ on that
  outline — a focused poster visibly lifts over its flat neighbours (owner ask 2026-11-02).
- Tile material is a SEPARATE pack decision from tile geometry: `pack.card` picks where the
  caption lives ("below" keeps the Glass pack's under-art names); `pack.tileMaterial="glass"`
  activates the pack's plate stack on every rail surface. Genre tiles are never opaque plates:
  the genre's diagonal colour wash at translucent alpha (0xA8 top / 0x88 bottom ≈ 66%/53% —
  wall shows through) + hairline rim + top sheen + elevation shadow — measured: wall beside a
  Comedy tile rgb(143,155,164), tile edge specular line rgb(157,196,189), interior teal lets
  wall grain through. See-all tiles: frost wash seeAllGlassA rgba(232,240,248,0.16) →
  seeAllGlassB rgba(196,210,224,0.08) + rim + sheen + discGlass chevron disc
  rgba(255,255,255,0.16) with inkOnWall glyph — the wall texture reads THROUGH the plate.
- Thumbnails ride card size: images decode at the TMDB width ladder nearest the target (w154…
  w1280 by targetWidthPx), lazy placeholders at w92; full-res originals are never fetched for
  tiles (wire-level: sizedUrl rewrites /original/ and stale /wNNN/ path segments).

## 4. Motion spec [c] (reference is a still; law from design/ui-tokens.md)

- Screen enter: fade-up 600 ms shift 20 stagger 30 (ease-enter cubic-bezier(0.22,1,0.36,1)).
- Hero rotation: crossfade 500 ms, idle rotate 6000 ms; wallpaper wash does NOT churn per slide
  ([c] perf on 2 GB TV).
- Focus: 150 ms; ring swap + scale only, cap animation cost per existing Focus rules.
- Row scroll 240 ms; nothing interruptible-blocking; reduced-motion honors animator flag.

## 5. Deviation log

Logging a deviation does NOT approve it. Each entry stands as proposed until the owner signs it;
entries the owner has rejected are marked UNRESOLVED and the implementation must not cite the
log as cover.

| Item | Value used | Src | Rationale |
|---|---|---|---|
| D1 vertical reflow | hero 397 (remeasured), rail captions brought above fold via host scroll | chosen | reference page scrolls (aspect 1.40); TV 16:9 fold must show nav+hero+chips+first rail |
| Bell icon top-right | omitted | chosen | app has no notifications feature; an icon that promises one lies |
| "Premium" subtitle under name | omitted | chosen | no plan data on profile API |
| Chip icons | unicode glyphs mapped by shelf name (existing Clean precedent) | superseded 2026-11-02 | vector pictogram set shipped (ic_chip_play/trend/sparkle/star/heart/plus/globe, chipGlyph-sized ImageViews); reference's bespoke icons still not copied glyph-for-glyph |
| Rating dot | accent #EF9F42 | estimated | AA-blended sample of small dot |
| Card tints | fixed steel/periwinkle gradients | superseded 2026-11-02 | per-title extraction implemented: the hero plate samples its loaded key art (upper-left average, saturation ×1.6, heroScrim alpha kept) and repaints the overlay; visual close-judgement vs reference pending owner look |
| Motion values | timing table §4 | chosen | still reference |
| D6 chip labels | conversational tail cut ("Top 10 in this house"→"Top 10", "Browse by category"→"Browse"), cap 22 | chosen | equal-width frost chips (≈228px) truncate hub shelf names mid-word; the chip keeps its leading promise and still names its shelf |
| Home pill segment | added as first word, active on unfiltered home | measured | reference pill shows Home lit; section switches rebuild the page so the pill paints where you stand |
| D7 wall residual | wash = gradient + 8 soft blobs, not per-pixel grain | UNRESOLVED (owner: the 2026-11-02 "residual = wall grain" attribution was made with the hero seam masked out and was never isolated) | Honest re-attribution 2026-11-02 (NO content masked): full-frame pixel diff is meaningless on content bands — the hub features DIFFERENT titles than the reference (Blue Sword vs The Widower), so heroA/heroB/rail deltas measure artwork, not material. Wall-only bands (left gutter/top strip) now sit at d7–17 mean after the wallA/wallB retune; the reference frame's black rounded-corner mask pollutes edge columns and must be excluded. "Texture floor" claim WITHDRAWN until a same-title capture isolates it. Update 2026-11-02: procedural film grain (wallGrain=1, 160×160 salt-and-pepper tile at 0x0B alpha, one BitmapShader over the wash) added so the wall stops banding like a vector gradient; still NOT the reference's photographic grain, and no same-title capture has isolated the residual yet — stays UNRESOLVED |

## 6. QA gates (three gates kept SEPARATE; run 2026-11-02, emulator scenix-glass/5558, ui_zoom 1.0)

GEOMETRY gate — pass (canvas px vs contract): pill 748–1171 w=424 (target 425) ✓; hero top 141 (142) ✓;
chips y 603–706 h=104, widths 225–228 equal ✓; rail title top ~778 (772, +6 boundary) ✓; pill centre x 960 ✓.
Geometry passing is NOT visual fidelity.

APPEARANCE gate — INCOMPLETE, judged on specimens, no masks over the evaluated effect:
- Hero: 3-way whole-card crop (reference / pre-fix seam version / current) — continuous art +
  left→right wash + blue bottom wash now match the reference's composition; the seam defect is
  fixed. Residual: wash fade is eyeball-close, not per-pixel (different artwork makes pixel diff
  on content meaningless — see D7).
- Material: cast shadows visible OUTSIDE the surfaces (plates, posters, chips) — separate from
  rim/sheen; backdrop BLUR is approximation, stated in §Radius/elevation (no API pre-36).
- Chips: centered icon+label, meaningful labels, chipGlyph 34; residual: reference pictograms are
  filled colour icons, mine are monochrome text glyphs (deviation, NOT approved).
Full-frame diff number is reported but carries no acceptance weight on content bands (D7).

APPEARANCE re-check 2026-11-02 (tile batch; root cause first): the rails' glass branches were DEAD
CODE — `glass` keyed on `pack.card=="glass"` while the pack ships `"below"`, so genre tiles painted
opaque and See-all a paper card (owner screenshots). After decoupling via `pack.tileMaterial`,
re-captured: `qa/glass/impl_genres_glass.png` (category row: translucent washes, wall through,
specular edge line), `qa/glass/spec_genre_tile_crop.png` (one tile enlarged: rim+sheen+grain
through-plate), `qa/glass/spec_seeall_glass.png` (focused See-all: frost plate, disc chevron,
"Hindi Web Series" caption bar), `qa/glass/impl_home_v4.png`
(vector chip glyphs, per-title hero wash, grain wall). Focused-tile lift pair:
`qa/glass/spec_poster_lift_pair.png` (halo + cast shadow vs flat neighbour). Geometry improved;
visual fidelity: genre/See-all material, chips and hero wash now satisfy the owner's stated asks
on-device; reference-pixel identity of the wall texture remains OPEN (D7).

INTERACTION gate — captured 2026-11-02: unfocused-vs-focused poster pair
(`/tmp/spec_poster_focus.png`: top row normal, bottom row halo + dimmed neighbours, cast shadow
intact on both states) + screenrecord of the focus walk (`/tmp/focus_walk2.mp4`); pill walk and
chip-jump walks previously verified. Glow is not clipped (railGlowPad 26 holds halo+shadow).
Evidence: `qa/glass/impl_home.png`, `qa/glass/ref_169.png`, `qa/glass/heatmap.png`.
