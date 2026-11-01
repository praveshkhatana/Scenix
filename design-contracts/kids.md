# Kids Stage — design contract (scenix-kids)

Reference: `refs/kids/reference.png` (owner's `kids-theme.jpg`, 1199×775, measured with
pixel-replica `sample_pixels.py`). Every token below is `measured` from that image unless
tagged `chosen`. This contract is the gate for the round-3 Kids fixes (owner round 3:
black focus border, hero clipped on focus, unreadable settings modal, navigation).

## 1. Screen inventory (single native surface + its modals)
- Home (`KidsHomeLayout`): full-bleed yellow stage; brand circle + tab pill + search dot (left);
  avatar + name plate + gear (right); two oversized feature cards; black chip pills;
  stacked poster rails below.
- Secondary screens (`core/Screens.kt` wall = KIDS_STAGE): Settings, Add-ons, Debrid, Source
  packs, Live settings, Live channels, Categories, Manage profiles, System, Pin, Text entry.
- Modals: option pickers opened with CENTER from a settings row.

## 2. Measured tokens (→ theme-kids.json)
| token | value | source |
|---|---|---|
| page ground | `#F9BB12` sunshine, deepening toward the bottom | measured → `colors.stageYellow`/`stageDeep`, `KIDS_STAGE` wall |
| feature-card plate | `#FBD05D`–`#FBD56A` | measured → `colors.stageSheet` |
| rail tile plate (unfocused) | `#FAD165`, **no border** | measured → `colors.listFill #FFEDBE` (lighter variant on this pack's plates) |
| focused tile plate | lighter `#FED351` | measured |
| reference focus edge | ~1–2 px near-black `#210B00` keyline | measured — SUPERSEDED, see §3 |
| ink (all text on light ground) | `#141210` | measured → `colors.ink` |
| card radius | ≈26 dp | measured → `radii.card 26` |
| tile/list radius | ≈24 dp | measured → `radii.listItem 24` |
| chips | fully round, black plate, light text | measured → `radii.chip 999`, `colors.pill` |

## 3. Focus language — owner decision on record (round 3)
The reference paints a thin ink keyline, but the owner explicitly asked for
**"glow border / liquid glass effect"**, so the pack ships glow, not ink:
- `pack.focus: "glow"`, `pack.focusColor: #FFDB6F` (measured bright band (253,219,111) keyed to the page's yellow).
- Inset targets (rail tiles, hero rail cards, picker rows): 4-band halo — filled plates at
  `focus.glowAlphas [30,80,200]` rising from the tile, plus a 2 px WARM keyline `focus.keylineColor #A88200` (measured (168,130,0) — a liquid-glass edge, never black ink) set `keyOutset` 3 px
  outside the art. Keyline is `focusGlowColor`, never black ink (black reads as a hard border
  on a light pack — exactly the round-3 complaint).
- `focus.ringWidth: 3` (was 5), `focus.glowInset: 20`, `focus.glowInsetSm: 14`
  (was 16/12 — the halo must clear the tile AND stay inside the row bounds so the hero
  section is never cropped, §4).
- Focus contrast law (ui-ux-pro-max): ≥2 px perimeter, ≥3:1 state contrast — the alpha-224
  warm keyline on `#F9BB12` ground passes; the old `#171412` 5 px ink border failed the
  owner's intent, not contrast.

## 4. Hero visibility on focus
A focused feature card must stay FULLY inside the visible stage: card height
(`pack.kidsHomeHeight 470` band) + 1.03 scale + 20 px halo must all fit between the header
row and the chip band. Any container between the card and the root clips at the card's own
box (`clipChildren=false` chain from the card's parent up to the root, plus
`clipToPadding=false`). Halo clipping was a round-3 defect: the top band of the halo cut off
at the section edge, which read as the hero section itself being cut.

## 5. Modal (picker) spec — the unreadable-settings fix
- Dim veil: `colors.modalDim rgba(20,17,13,0.38)` (via `Screens.dimColor`) — the old hard
  `argb(190,0,0,0)` blacked the yellow page out and sank the dark panel with it.
- Panel: `colors.modalPanel #FFF6DC` light (via `Screens.modalFill`), hairline edge
  `colors.listStroke`, radius `radii.modal 18`.
- Rows: `Screens.pickerRow` — label `colors.ink #141210` on `#FFF6DC` ≈ 17:1; selected value
  `colors.panelValue #7A4E00` ≈ 5.5:1 (passes 4.5:1); selected plate `colors.surface2`;
  row focus = the §3 small-inset halo (`glowInsetSm 14`), never a flat keyline that would
  vanish on a light panel.
- Dark packs keep `panelScrim`; `modalPanel` only overrides where a pack declares it.

## 6. Navigation law
D-pad only. Every hop must land on a visible focusable; after a modal opens, focus sits on
the panel's first row (not the chevron behind it); BACK closes the modal and returns focus to
the row that opened it; from the last rail row DOWN reaches the pill/next section, UP walks
back to the header controls. No dead ends, no double-press to act.

## 7. QA gate
`python ~/.agents/skills/pixel-replica/scripts/pixel_diff.py refs/kids/reference.png
qa/kids/impl.png --out-dir qa/kids --mask dynamic art` — masked diff < 2%; side-by-side
kept in `qa/kids/`. State QA: hero focus fully visible, halo not cropped, settings picker
readable, navigation walk (§6) end-to-end by D-pad on emulator-5554 with md5-matched APK.

## Deviation log
- Focus edge: reference `#210B00` ink keyline → replaced by warm-white glow per owner's
  explicit round-3 instruction (`chosen`).
- Reference photo crops the bottom rails; rail metrics come from the app's own grid
  (`estimated`).- Round-3 device QA (2026-11-02, emulator-5554, md5-matched APK): the first palette was too
  pale — device samples showed page #FFC93A vs measured (249,187,18) and sheet #FFF3D0 vs
  measured (251,211,100). Tokens corrected to `bg/stageYellow #F9BB12`, `stageSheet #FBD364`,
  `cardTint/surface #F9C905` (measured); re-sampled device output matches the reference exactly.
- Focus state on a light sheet: a near-white glow (#FFF6D8, bands alpha 8/12/18) measured
  invisible on the cream sheet — the owner's "no glow, why a black border?" complaint. The
  reference's focused tile carries an opaque bright band (measured (253,219,111)→(251,222,133))
  closed by a thin WARM keyline (measured (168,130,0) = #A88200), not ink black. Added pack
  vocabulary `focus.glowAlphas [30,80,200]` and `focus.keylineColor #A88200`; dark packs keep
  the old 8/12/18 + glow-colour keyline defaults.
- Chips: reference chip fill (0,0,0) vs app ink #141210 — kept the pack ink (`chosen`, Δ≈20,
  indistinguishable at couch distance).
