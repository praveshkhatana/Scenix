# Scenix design system

Native UI per platform, one definition of the product. This folder is the definition: tokens,
component contracts, fixtures and the catalog that proves the components exist. It is not a mock —
the renders come from the app itself.

```
tokens/        canonical token groups for every theme (extracted from the Android app)
contracts/     one JSON per component: anatomy, states, geometry, copy, focus, behaviour, deltas
fixtures/      the sample data every platform's catalog renders
studio.json    the catalog manifest: entries, states, themes, real/blocked status
goldens/       captured renders, one per entry × state × theme
tools/         token parity, coverage gate, image diff
```

## Why this exists

The television is the reference implementation. iOS/macOS and Windows will be native too, because a
theme-driven app changes rarely and native feel and speed matter more than shared UI code. What
must not be re-invented per platform is the definition: a colour, a focus rule, a state, a piece of
copy. Those live here and are checked, not remembered.

A component is not "done" when it looks right once. It is done when this repository can prove it:

1. **tokens** — every value used comes from `tokens/`; no hardcoded colour, size or curve.
2. **contract** — anatomy, states, geometry, copy, focus order and platform deltas are written down.
3. **catalog** — the component appears in the platform's real catalog (`studio.json` here) in every
   declared state.
4. **golden** — a render exists for every state × theme and matches on the next capture.
5. **interaction** — the D-pad/keyboard walk in the contract is tested.
6. **a11y** — contrast, focus visibility and target size pass.

Items 1 and 4 are automated. 2 and 3 are reviewed. An agent or a person can disagree about taste;
they cannot make a missing golden pass.

## Reference device

Goldens are captured on the **`scenix-tv` Android TV AVD** (1080p, 1920×1080, density 320), not on
the household television. The emulator is reproducible, does not occupy the living room, and gives
the same frame every run. Start it and target it explicitly:

```sh
$ANDROID_HOME/emulator/emulator -avd scenix-tv -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
ADB_SERIAL=emulator-5554 scripts/studio-capture.sh --write
```

The panel in the living room is still the acceptance target for *feel*; the emulator is the
reference for *pixels in the repository*.

## Run the gates

```sh
node design-system/tools/tokens.mjs check     # app assets vs canonical tokens
node design-system/tools/coverage.mjs         # contract -> catalog -> goldens, report
node design-system/tools/coverage.mjs --strict
scripts/studio-capture.sh --check             # capture the catalog from the TV and diff
```

`coverage --strict` is expected to be red while host-owned components are still inside
`BrowseActivity`. That is the debt list, not a broken build: the Studio names what is left to
extract.

## The catalog on Android

`StudioActivity` (debug builds) renders the real components, not re-drawings: `Views`, `Focus`,
`GlassKit`, `KidsKit`, `Screens`, `CardActionSheet`, and the real `PlayerActivity` chrome through
its preview intent.

- Open it from Settings → *Component studio* (debug builds only), or:
  `adb shell am start -n com.scenix.tv/.StudioActivity --es theme scenix-glass`
- The manifest is `design-system/studio.json`; Gradle carries it into the APK as an asset.
- Screen entries launch the real screen with a preview intent instead of being embedded, because a
  player is a screen, not a widget.
- `status: "blocked: ..."` means the component still lives inside an Activity. The Studio does not
  fake it.

## Add a component

1. Write `contracts/<id>.json` (copy the nearest existing contract; keep every section).
2. Add the entry to `studio.json` with its states and themes.
3. Extract or build the real component; wire the id in `StudioCatalog`.
4. Capture goldens: `scripts/studio-capture.sh --write <id>`.
5. Run both gates. Red is the honest state until the work is done.

## Platform rollout

- **Android TV** — the reference; Views; Studio + goldens.
- **iOS / macOS** — SwiftUI; `tokens/` generates `DesignTokens.swift`; contracts are implemented
  against the same fixtures and verified with snapshot tests (`swift-snapshot-testing`).
- **Windows** — WinUI 3; `tokens/` generates `Tokens.xaml`; a gallery page mirrors `studio.json`;
  captures are diffed against the same golden naming scheme under `goldens/windows/`.

Allowed differences are written in each contract's `platformDeltas`. Anything not listed there is a
parity bug, not a platform convention.
