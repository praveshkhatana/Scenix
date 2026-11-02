# Design Replication Playbook — how any "make it look like <reference>" task runs here

Adopted 2026-11-01 after the Glass pack + an external review ("the failure mode is a missing
machine-readable design contract, not bad taste"). The rule: **no screen is coded before its
contract is approved, and no pack is declared done before the two QA gates pass.**

## Order of work (never reorder)

1. **Reference extraction → UI inventory.** Every region measured off the reference with a
   labelled grid overlay (ruler image at known scale), never eyeballed: bounds, split weights,
   gaps, radii, type sizes, sampled colors at named points (sample corners AND centre of
   gradients; a TL→BR wash breaks naive single-point color checks).
2. **Contract file** `design-contracts/<id>.md` written BEFORE any Kotlin (format below).
   Every value carries a source tag: `[m]` measured, `[e]` estimated from AA blends,
   `[c]` chosen (must have a rationale in the deviation log).
3. **Tokens into the pack JSON** `app/src/main/assets/theme-<id>.json`. Hard-coded values in
   Kotlin are a contract violation: anything a pack may vary goes through `theme.space/px/
   color/radius/type/pack*`.
4. **Components first, screens second.** Shared, state-complete components (GlassKit precedent)
   assembled into layouts; the same component must look/behave identically on every screen.
5. **STATIC FIDELITY GATE before any motion pass.** Landmark audit on an emulator capture at
   ui_zoom 1.0: every measured region within ±6 canvas px; chrome-masked pixel diff reported
   with the residual explained region-by-region. Animation may never be added to hide a static
   mismatch — if the still frame fails, the screen fails.
6. **Motion pass** — only after the static gate. Every animation in the contract's motion table:
   what moves, distance, duration, easing. No motion blocks input; all interruptible; ≤600 ms
   for routine UI; reduced-motion flag falls back to instant/fade.
7. **Interaction/state QA pass** — focus walk every nav/row/chip on the emulator; every
   component shows its default/focus/pressed/disabled/empty state per the state matrix;
   secondary pages (Detail/Settings/Search) stand on the pack's wall.

## Contract file format (machine-checkable by qa/contract_check.py)

Required sections, exact headings:

- `## 1. UI inventory` — region table with reference bounds + canvas conversion + scale factor.
- `## 2. Tokens` — subsections `### Color`, `### Typography`, `### Geometry`,
  `### Radius / elevation`; tables whose value cells contain the literal values (`#RRGGBB`,
  `rgba(...)`, bare numbers). These cells are grep-verified against the pack JSON by the
  checker: contract and pack may not drift.
- `## 3. Component rules` — per component: anatomy, padding/sizing in tokens, and the full
  **state matrix** (default / focus / pressed / disabled / loading / empty). A reference still
  only shows default; inferred states go into the deviation log.
- `## 4. Motion spec` — table of trigger → duration ms → easing → reduced-motion fallback.
- `## 5. Deviation log` — one row per deviation: item, value used, src, rationale. Every `[c]`
  or omitted reference element MUST appear here. Omissions are decisions, not oversights.
- `## 6. QA gates` — checklist copied from this playbook's gate, ticked per run with capture
  paths (qa/<id>/...) so the evidence is on disk, not in chat.

## QA gate (copy into each contract §6)

- Alignment: edges, baselines, card contents align to the gutter grid; identical components use
  identical gaps.
- Hierarchy: one primary action per screen; secondary visually quieter.
- Typography: only token sizes/weights; no arbitrary values.
- Color: only token colors; text contrast readable on the pack wall.
- Depth: materials/rims/sheen per contract; no default shadows or default buttons.
- States: every interactive element has focus + pressed + disabled + empty rendering.
- Motion: every animation has purpose, duration, easing, reduced-motion fallback; ≤600 ms.
- Consistency: same component identical across Browse/Detail/Search/Settings.
- Evidence: qa/<id>/ holds ref crop, impl capture, landmark scan output, diff stat.

## One reference → the whole theme (Mode A / Mode B)

One dashboard reference establishes the language; it cannot show every state. Screens WITH a
reference run Mode A (pixel-replica, strict). Screens WITHOUT one run Mode B (skill
`theme-extrapolation`): design around the screen's task while carrying over the invariants —
type hierarchy, component silhouettes, spacing rhythm, icon language, materials, action
hierarchy, focus language, motion character, navigation semantics — and adapting columns,
density, section order, and control placement to the screen's job. In Scenix the architecture
already enforces the split: shared product layer (BrowseActivity/data/playback) · theme system
(theme-<id>.json tokens + layout families + `Screens` wall layer + pack selectors) · per-screen
composition. Every extrapolated rule is tagged observed / inferred / proposed in the contract;
decisions stay with the agent (assumption log), escalation only for functionality/platform/
destructive/direction-level ambiguity.

## Repo-specific fixes this playbook exists to prevent

- "Make it look like the screenshot" with no inventory → layout drift (the previous failed
  attempt): fixed by §1–2 + gate 5.
- Editing the pack JSON without re-measuring, or vice versa → drift: fixed by
  `qa/contract_check.py` (run it in CI-ish habit: `python3 qa/contract_check.py <id>`).
- Using animation/skeletons to mask spacing errors: fixed by gate order 5→6.
- Trusting hub rows (they go stale, `items:[]`) or a hijacked emulator: re-curl / dedicated AVD
  before believing a bug; capture paths recorded in §6 make claims checkable.
- Reference-only states silently invented: state matrix + deviation log make them explicit.
