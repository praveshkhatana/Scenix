# Web and desktop theme contract

## Purpose

Keep one product language across the native television app, the Hub-served browser
application, and the macOS desktop client while letting each input method use the
space it has well.

## Visual thesis

The catalogue remains cinematic and content-first; desktop gains precise mouse,
keyboard, and window-management affordances without becoming a separate dashboard.

## Product composition

- The existing web application is the functional product surface: profiles, browse,
  search, playback controls, settings, and source management remain route-based and
  Hub-owned.
- TV composition is the source of truth for shared themes. A desktop viewport may
  widen rails, expose hover affordances, and make long-form settings easier to scan,
  but it must retain the selected pack's typography, material, card anatomy, action
  hierarchy, focus language, and motion character.
- The macOS client uses a native pairing shell around the Hub-served surface until a
  separately verified native renderer exists. This gives feature parity without
  pretending that a wrapper is an independently complete media engine.

## Theme rules

### Invariants

- Theme tokens and pack identity come from `design-system/themes.json`.
- A target advertises a theme only when it ships the exact registered asset and a
  renderer for its declared pack.
- Focus, hover, selected, pressed, disabled, and reduced-motion states remain
  legible in every pack.
- The player stays content-first; desktop controls may be pointer-friendly but must
  not cover keyboard focus or introduce layout-shifting motion.

### Desktop adaptations

- Mouse hover is additive only: every action remains keyboard reachable and visibly
  focused.
- Persistent navigation and overlays reserve scroll space so focused controls are
  never obscured.
- Cards use transform/opacity-only transitions; no width/height animation is added
  to shelves or settings rows.
- Respect `prefers-reduced-motion`; interface state still changes immediately when
  decorative motion is suppressed.

## Pack extension assumptions

- **Observed:** Dark, Midnight, Ember, Apple, and Netflix already share exact web
  theme assets with television.
- **Observed:** PS5, Kids, and Glass have television token assets and distinct
  layout/focus rules.
- **Inferred:** Their web renderers should preserve console rail, child-safe stage,
  and liquid-glass material language respectively, while retaining mouse and keyboard
  operability.
- **Proposed:** Full browser parity is accepted only after every curated theme has a
  static availability check and a rendered-browser baseline gate.

## Scope boundaries

- Community-directory browsing remains deferred until the emulator test is complete
  and review evidence/policy is supplied.
- Standalone Android is a native local backend/client mode. It does not claim a
  remotely reachable browser-management site unless a separately secured LAN host is
  deliberately implemented.
- macOS standalone needs its own packaged local backend, P2P engine, cache lifecycle,
  and playback verification; it is not supplied by the Android implementation.
