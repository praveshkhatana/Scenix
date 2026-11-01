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
| wallA / wallB (full-page wash, TL→BR base) | #819DA9 / #A59D9A | m ((129,157,169)→(165,157,154) corner-sampled) |
| wallBlobs (wash is photographic, not flat — field sample 2026-11-02: top cool band, dark left rim, light seam drift, warm bokeh low) | 50,5,90:#465A69:0.22;-8,55,45:#3A4A55:0.20;108,25,42:#2E3E4A:0.25;52,66,55:#BEC8D2:0.15;40,92,28:#FFEFC8:0.35;18,97,22:#F2EDD9:0.30;80,84,22:#FFBE78:0.28;30,83,20:#96BEE6:0.22 | m (10×7 field sample vs base gradient deltas; right rim + lower-left cream added after second band audit) |
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
card(rail)=18, hero=26, chip/nav=999. Elevation: cards use 1px specular rim + inner top sheen
(gradient rgba(255,255,255,.16)→0 over top 45%), NOT drop shadows; focus adds soft shadow.

## 3. Component rules (glass-specific)

- Nav pill: centred, h62, padding 6; segments text-only; active segment light-filled pill with
  dark ink (measured); search welded at end as light circle 46 with dark glyph.
- Hero card: gradient tint plate + rim + sheen; title anchored TOP-left pad 40; Play row anchored
  BOTTOM-left: disc 54 + label; art occupies right 42–58% of each card bleeding to edge,
  clipped by card outline. Card A narrower than B (7:10) — measured asymmetry.
- Chips: frost fill + hairline rim, icon glyph + label, height 78; never opaque.
- Rail captions: name then "● rating · year" — dot is accent token, rating number light,
  year in captionSub (reference meta line). Poster art bare (no scrim) on glass wall.
- Focus language = `glow`: pale-blue halo plates (#CFE3F5) + scale 1.03, 150 ms; controls get
  keyline (shared Focus rules).

## 4. Motion spec [c] (reference is a still; law from design/ui-tokens.md)

- Screen enter: fade-up 600 ms shift 20 stagger 30 (ease-enter cubic-bezier(0.22,1,0.36,1)).
- Hero rotation: crossfade 500 ms, idle rotate 6000 ms; wallpaper wash does NOT churn per slide
  ([c] perf on 2 GB TV).
- Focus: 150 ms; ring swap + scale only, cap animation cost per existing Focus rules.
- Row scroll 240 ms; nothing interruptible-blocking; reduced-motion honors animator flag.

## 5. Deviation log

| Item | Value used | Src | Rationale |
|---|---|---|---|
| D1 vertical reflow | hero 397 (remeasured), rail captions brought above fold via host scroll | chosen | reference page scrolls (aspect 1.40); TV 16:9 fold must show nav+hero+chips+first rail |
| Bell icon top-right | omitted | chosen | app has no notifications feature; an icon that promises one lies |
| "Premium" subtitle under name | omitted | chosen | no plan data on profile API |
| Chip icons | unicode glyphs mapped by shelf name (existing Clean precedent) | estimated | reference icons are bespoke pictograms; vector set can follow |
| Rating dot | accent #EF9F42 | estimated | AA-blended sample of small dot |
| Card tints | fixed steel/periwinkle gradients | chosen | real liquid glass samples art colors at runtime; per-title tint extraction is a follow-up pass |
| Motion values | timing table §4 | chosen | still reference |
| D6 chip labels | conversational tail cut ("Top 10 in this house"→"Top 10", "Browse by category"→"Browse"), cap 22 | chosen | equal-width frost chips (≈228px) truncate hub shelf names mid-word; the chip keeps its leading promise and still names its shelf |
| Home pill segment | added as first word, active on unfiltered home | measured | reference pill shows Home lit; section switches rebuild the page so the pill paints where you stand |
| D7 wall texture floor | wash = gradient + 8 soft blobs, not per-pixel grain | chosen | reference wall is a photographic surface (droplet/bokeh grain, ±15 local deltas); blobs match its measured light field (top cool band, left rim, seam drift, warm bokeh); per-pixel grain would mean copying its asset. Band audit 2026-11-02: chrome-masked diff 22.2%, residual concentrated in wall grain only |

## 6. QA gates (run 2026-11-02, emulator scenix-glass/5558, ui_zoom 1.0)

Landmark audit — pass (canvas px vs contract): pill 748–1171 w=424 (target 425) ✓; hero top 141 (142) ✓;
chips y 603–706 h=104, widths 225–228 equal ✓; rail title top ~778 (772, +6 boundary) ✓;
wall BR (163,156,152) ≈ measured (165,157,154) ✓; pill centre x 960 ✓.
Chrome-masked diff (art+rail+avatar masked): 22.2%; residual = wall grain band (see D7).
Evidence: `qa/glass/impl_home.png`, `qa/glass/ref_169.png`, `qa/glass/heatmap.png`.
Interaction: pill walk Home→Movies (PIN-gated section opens), CENTER on active Home scrolls to top;
chips rename check (Continue / Because you / My List / More like / Top 10 / Trending / Acclaimed — no mid-word truncation).
