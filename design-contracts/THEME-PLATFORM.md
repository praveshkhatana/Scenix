# Theme platform — component model, targets, and rollout

Status: plan, audited against the Glass and Kids packs (Nov 2026).
Audience: whoever builds the next theme, the iOS/macOS app, or the web app.

This document turns the current token packs into a **component platform**: one theme
definition, rendered by Android TV, web, iOS/iPadOS and macOS, with a Storybook-style
catalog and a safe extension path for community layouts, motion and logic.

---

## 1. What exists today (audit)

### 1.1 The pieces that are already components

| Layer | Where | Verdict |
|---|---|---|
| Design tokens | `app/src/main/assets/theme-*.json` (`colors`, `spacing`, `radii`, `type`, `focus`, `anim`, `cards`, `rows`, `chips`, `hero`, `player`, `pack`) | Good. Every value is a token; a pack can reskin without touching Kotlin. |
| Structural home family | `core/HomeLayout.kt` (`Family`, `Hero`, `Rails`, `Navigation`) + `HomeSurface.kt` factory | Good contract, but the family is an enum in Kotlin, so a new *layout* still needs native code. |
| Secondary screens | `core/Screens.kt` (wall, top bar, section, row, picker) | The right direction: one design layer used by Settings, Add-ons, Packs, Profiles, System, Live. |
| Glass surface | `LiquidFrost.kt` + `GlassKit` | Real glass (backdrop snapshot + RenderEffect/AGSL; downsample fallback under API 31), captured on scene change only. |
| Motion | `core/Motion.kt` | Small and useful; not yet declarative. |
| Installed reskins | `Theme.catalog(context, assets)`, `files/packs/<id>.json`, `basedOn` | Good safe tier: paints on a curated base, structural keys are dropped. |

### 1.2 What Glass and Kids add

Both packs are complete and perform well on the reference panel:

- **Glass**: 9-pack catalog, light wall, frosted nav pill/profile pill, two hero cards,
  chip rail, poster rails, glass chrome in the player, the `Screens` design layer across
  the settings family. Measured on emulator-5556: **50th 17 ms / 90th 20 ms,
  10% janky, 0 missed vsync** while scrolling; no PixelCopy or Frost errors.
- **Kids**: yellow stage, black pills, oversized cards, chunky tiles, stage-specific
  navigation, `KidsHomeLayout`. Measured **17/25 ms, 12.4% janky, 0 missed vsync**.

The audit found four gaps, all structural rather than visual:

1. **Layout is still code.** `KidsHomeLayout` (467 lines) and `GlassHomeLayout` hold
   geometry and behaviour in Kotlin. A community theme can change tokens but cannot ship
   a new layout without a native build.
2. **Family switches are hard-coded.** `Screens.wall` and `HomeSurfaceFactory` switch on
   `HomeLayoutSpec.Hero`; that is an interim registry, not a plugin point.
3. **Numbers leak out of the pack.** Kids/Glass pack JSON is rich, but parts of their
   geometry live in the classes. On iOS/web those numbers would have to be re-typed.
4. **No contract or catalog.** Nothing states which props/states/events a component
   guarantees, so a new pack cannot be validated and a new target cannot be generated.

The left rail, the focus chain and the player chrome are host-owned and theme-agnostic;
they stay outside the pack and every theme inherits them.

### 1.3 What CleanYourTV teaches

`Gathin23/cleanyourtv` ships themes as **folders pushed over the network** (`themes/<id>/config.json`
plus a screenshot; `tools/theme.sh` installs, `--reset` removes), including dock size/order,
columns, wallpaper, colours and reduced-motion. It also has **module cards**: any app can
offer a tile, and the owner approves it once. Two ideas worth copying:

- a theme is a **portable folder**, not a rebuild;
- extensions are **declared and approved**, not arbitrary code.

We keep those properties while adding typed components, so a theme can change a layout
without being able to break playback, focus or the host.

---

## 2. The target model

Five levels, borrowed from atomic design, mapped to concrete Scenix nouns:

| Level | Examples | Contract |
|---|---|---|
| **Token** | colour, type ramp, space, radius, elevation, blur, focus band, motion curve | typed values, aliases allowed |
| **Atom** | icon, label, button, chip, focus ring, surface, avatar, badge, progress, toggle, input, spinner | props + states, no data access |
| **Molecule** | tile, rail item, nav item, hero card, chip group, player button, seek bar, menu row, settings row, dialog, pin pad | composes atoms, emits events |
| **Organism** | hero composition, shelf/rail, top nav, left rail, player chrome, settings list, detail header, episode rail, live grid, search bar, profile gate | binds data slices, owns focus order |
| **Template / page** | Home, Detail, Player, Settings, Live, Search, Profiles, Games | composition tree + data slots |

A theme never re-implements a page from scratch. It **selects and composes registered
components**, restyles them through tokens, and may supply a new layout tree made of
existing components. Anything below that (new interaction, new visual primitive) is a
new component with a versioned contract.

### 2.1 Component contract (one file per component)

```jsonc
{
  "id": "molecule.tile.poster",
  "version": 1,
  "level": "molecule",
  "props": {
    "art":        { "type": "image",  "required": true,  "sizes": ["w342", "w500"] },
    "title":      { "type": "string", "required": true },
    "secondary":  { "type": "string" },
    "progress":   { "type": "number", "min": 0, "max": 1 },
    "wide":       { "type": "boolean", "default": false }
  },
  "states": ["default", "focus", "pressed", "disabled", "loading", "empty"],
  "events": ["press", "longPress", "focus"],
  "slots": [
    { "name": "art", "accepts": ["atom.image"] },
    { "name": "caption", "accepts": ["atom.label", "atom.logo"] }
  ],
  "a11y": { "role": "button", "label": "{{title}}, open details" },
  "targets": {
    "android": { "renderer": "CardView", "focus": "host-ring" },
    "ios":     { "renderer": "PosterTile", "focus": "focusEffect" },
    "web":     { "renderer": "scenix-tile", "focus": "focus-visible" }
  },
  "budget": { "maxViews": 14, "maxLayers": 2, "maxImageBytes": 40000 }
}
```

Rules the host enforces:

- **States are exhaustive.** A renderer must draw every declared state; missing states
  are a validation error, not a silent fallback.
- **Events go up.** Components never call the hub, read prefs or start activities.
- **Focus is declarative.** Every organism declares `order: [row, column]` or explicit
  neighbour links; geometric search is a fallback, never the primary route.
- **Performance budget per component.** The compiler refuses a theme that exceeds the
  budget for its target (e.g., more than one blur layer on a `low` tier device).

### 2.2 Theme package format

A theme is a folder (or a single `.scenix-theme` archive) with four optional layers:

```
themes/glass/
  theme.json          # meta + tokens + capability requirements
  components.json     # component overrides (props defaults, states)
  layouts/home.json   # composition for one or more templates
  motion.json         # transitions, focus curves, reduced-motion fallback
  assets/             # wallpapers, icons, fonts, licence notes
```

`theme.json`:

```jsonc
{
  "id": "scene.glass",
  "name": "Glass",
  "version": "1.2.0",
  "basedOn": "scene.apple",
  "targets": ["android-tv", "web", "ios", "ipados", "macos"],
  "requires": { "caps": ["blur.backdrop"], "minApi": { "android": 31 } },
  "fallbacks": { "blur.backdrop": "blur.downsample" },
  "tokens": {
    "color.bg": "#819DA9",
    "color.ink": "#0A1420",
    "radius.card": 18,
    "type.hero": { "size": 52, "weight": 700 },
    "focus.style": "glow",
    "focus.color": "#CFE3F5"
  },
  "assets": { "wall": "assets/wall.webp" },
  "licence": { "name": "MIT", "author": "…" }
}
```

`layouts/home.json` — a small composition language, not arbitrary code:

```jsonc
{
  "template": "home",
  "root": {
    "type": "stack",
    "children": [
      { "component": "organism.wall", "props": { "tone": "color.bg" } },
      { "type": "column", "safe": "tv", "children": [
        { "component": "organism.nav.top", "props": { "items": "@nav" } },
        { "component": "organism.hero.cards",
          "props": { "items": "@hero", "max": 2, "variant": "glass" } },
        { "component": "organism.chip.row", "props": { "items": "@chips" } },
        { "component": "organism.rail", "repeat": "@rows" }
      ] }
    ]
  }
}
```

The language is deliberately small: `stack | row | column | grid | overlay | scroll`,
`component`, `slot`, `repeat`, `when`, and token references. Conditionals may read data
shape (`@rows.length`), never device APIs. This covers the layouts Scenix needs today
(Glass, Kids, Apple, Netflix, PS5, Clean, Roku, Magnifier) without a general-purpose
programming language.

### 2.3 Capabilities and fallbacks

A capability is a named fact about a target: `blur.backdrop`, `shader.agsl`,
`motion.spring`, `focus.tv`, `input.remote`, `player.exo`, `image.resize`.

The host resolves each requirement:

| Theme asks | Android TV (API 31+) | Android 11 | Web | iOS / macOS |
|---|---|---|---|---|
| `blur.backdrop` | `RenderEffect` + AGSL | downsample + wash | `backdrop-filter` (Safari `-webkit-`) | `UIVisualEffectView` / `.ultraThinMaterial` |
| `shader.agsl` | yes | no → static wash | no → CSS gradient | no → `Canvas`/Metal |
| `player.exo` | ExoPlayer/Media3 | same | hls.js/Shaka | AVPlayer |
| `focus.tv` | D-pad ring | same | `:focus-visible` | tvOS focus engine / iPad pointer |

A pack that requires a capability without a fallback is refused on that target with a
clear message, never rendered half-broken.

---

## 3. How one theme renders everywhere

```
themes/<id>/  ──►  themec (Node/TS compiler)
                     │  validate schema, resolve tokens, check component versions,
                     │  check target capabilities and budget, emit IR
                     ▼
                 theme.ir.json  +  assets/
                     │
      ┌──────────────┼───────────────────────────┐
      ▼              ▼                           ▼
  Android          Web                        iOS / macOS
  Theme.kt         CSS variables +            ScenixKit (SwiftUI)
  component map    React/Web Components       ViewFactory
  (registry ids)   component map              component map
```

The IR is the only thing renderers consume. That is what makes "write the theme once,
render it on every device" true, and it lets the gallery render any theme without an
emulator or a phone.

Renderer mapping is a **registry lookup by component id**, not code generation:

- Android: `ScenixRenderer` map `molecule.tile.poster → CardView`; the existing
  `HomeSurfaceFactory` becomes one registry entry per organism.
- Web: custom elements or React components registered under the same ids; CSS vars from
  tokens; focus-visible for keyboard/remote.
- iOS/macOS: SwiftUI `ViewFactory` with the same ids; `@Environment(\.theme)` tokens;
  tvOS focus engine for the focus ring, iPad/macOS pointer for hover.

---

## 4. The component gallery ("Storybook")

A web app in the repo, generated from the same manifests:

- **Every component × every state × every theme × every target viewport.**
  TV 1920×1080, iPad 1366×1024, iPhone 393×852, desktop 1440×900.
- **Controls** for each prop (the manifest is the schema), so a designer can try
  `wide`, `progress`, `focus` without writing code.
- **Token panel** showing the resolved values and where they came from (pack vs base).
- **Golden screenshots** per theme/target; CI diffs them (the `tools/screendiff.py`
  idea from CleanYourTV, applied to the gallery).
- **Performance card** per component: layout ms, overdraw estimate, image bytes at each
  declared size; a component that misses its budget fails the build.
- **"Copy theme snippet"** exports the current state as `components.json`/`layouts/*.json`.

This is the place a community author starts: pick a template, swap a component, see it
on TV/phone/web, export the pack.

---

## 5. Custom logic, safely

Two tiers, both declared in `theme.json` under `behaviour`:

### Tier 1 — interactions DSL (covers ~90%)

```jsonc
{
  "id": "hero.swipeFocus",
  "on": { "focus": "hero.card" },
  "do": [
    { "animate": { "target": "$self", "scale": 1.03, "duration": "motion.fast" } },
    { "emit": "hero.focused", "payload": { "index": "$index" } }
  ]
}
```

Triggers: `focus`, `blur`, `press`, `longPress`, `back`, `key`, `timer`, `data`.
Actions: `animate`, `navigate`, `play`, `setState`, `emit`, `scrollTo`, `openSheet`.
No network, no filesystem, no reflection. The compiler validates references and the
host executes the actions through the same APIs the built-in components use.

### Tier 2 — behaviour modules (rare, explicit)

For something the DSL cannot express (a custom player overlay, a game HUD), a pack may
ship a module per target:

- **Web/hub**: ES module loaded in a sandboxed frame/worker with a declared permission
  list (`player.read`, `player.seek`, `ui.toast`, `storage.theme`).
- **iOS/macOS**: a signed Swift package compiled into the app (App Store rules forbid
  downloading executable code); the pack declares it and the app ships only vetted ones.
- **Android TV**: compiled Kotlin module in the app (same store constraint); community
  packs can use the DSL, not arbitrary code.

The manifest always lists the module, its permissions and a hash; the owner approves it
once, the way CleanYourTV approves a module card.

---

## 6. Performance and correctness budgets

| Budget | Target |
|---|---|
| Focus move | ≤ 1 frame of UI-thread work; no layout pass for a move inside a rail |
| Scroll | 50th ≤ 18 ms, 90th ≤ 24 ms on the 2 GB panel; 0 missed vsync |
| Image | tile asks the size it shows; no `original`; one fallback request max |
| Blur | at most one snapshot per surface per scene change; never per frame |
| Startup | Home payload ≤ 400 KB; first paint ≤ 120 ms after profile |
| Theme install | validate + write ≤ 300 ms; rollback on parse failure |

The gallery and the device benchmark read the same numbers, so a theme cannot ship a
pretty screen that stutters on the reference TV.

---

## 7. Repository layout

```
theme-kit/
  schema/               # JSON Schema for theme, component, layout, motion
  components/           # one manifest + fixtures per component
  layouts/              # canonical templates
  compiler/             # themec (validate + IR + bundles)
  renderers/
    android/            # registry -> existing Views
    web/                # CSS vars + React wrappers
    ios/                # ScenixKit (SwiftUI)
  gallery/              # generated Storybook-style catalog
themes/                 # curated + community packs (portable folders)
app/src/main/assets/    # compiled built-in bundles (generated, not hand-edited)
hub/                    # serves packs and the gallery; no theme logic
```

---

## 8. Rollout

### Phase 0 — contract and gallery (2 weeks)
- Write the schema and 12 component manifests (atoms + tile + settings row + hero card).
- Extract the remaining Glass/Kids geometry into pack tokens; replace family switches in
  `Screens`/`HomeSurfaceFactory` with registry lookups.
- Gallery MVP: 4 themes (Apple, Glass, Kids, Netflix) × 3 viewports × all states.
- Fix catalog naming (`clean`/`kids` labels) and document the pack format.
- Acceptance: a new pack that only changes tokens renders identically in the gallery and
  on Android; a manifest with a missing state fails validation.

### Phase 1 — declarative templates on Android (3–4 weeks)
- Move Home/Detail/Settings/Player templates into `layouts/*.json`.
- Android renderer interprets the composition for the six existing families.
- Golden screenshots + perf budgets in CI.
- Acceptance: Glass and Kids become data, not Kotlin classes; benchmark unchanged.

### Phase 2 — web parity (4–6 weeks)
- React renderer over the same IR; the hub serves packs and the gallery.
- Player parity: seek/CC/audio/quality/speed/next, same focus semantics with keyboard.
- Acceptance: a pack previewed in the gallery runs on the web app without edits.

### Phase 3 — iOS/iPadOS/macOS (5–7 weeks)
- `ScenixKit` SwiftUI package: tokens, registry, focus, player (AVPlayer).
- Home/Detail/Settings/Player first; Live/Games after.
- Capability fallbacks (materials instead of AGSL, focus engine instead of rings).
- Acceptance: one pack, one IR, three targets, screenshot-equal within the agreed
  tolerance; the gallery renders all three.

### Phase 4 — community packs (3–4 weeks)
- Theme install/update from the hub (folder or archive), with signature/consent.
- Tier-1 interactions DSL; Tier-2 module path for web, vetted packages for mobile/TV.
- Author docs + `tools/theme.sh`-style push/preview CLI, like CleanYourTV.
- Acceptance: a third-party pack installs, previews, runs, and rolls back without a
  rebuild.

---

## 9. Decisions to lock now

1. **Host owns navigation, playback, data and focus invariants.** Themes own presentation
   and composition. This is why the left rail and the hero Play stay host behaviour.
2. **The IR is the product.** No renderer reads a pack's raw JSON directly; they read the
   validated IR, so a malformed pack cannot crash a device.
3. **Layouts are data; interactions are data; code is an approved exception.**
4. **Two performance tiers** (`high`, `low`) with explicit capabilities; a pack declares
   what it needs and what it falls back to.
5. **One gallery, every target.** If it is not in the gallery, it is not a component.

The Glass and Kids packs are the first proof: both are complete, both are fast on the
reference panel, and both are currently the strongest argument for finishing this model —
their layouts should become data next, not more Kotlin.
