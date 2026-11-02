# Scenix Theme Kit — Phase 0

The first concrete slice of [`design-contracts/THEME-PLATFORM.md`](../design-contracts/THEME-PLATFORM.md):
typed component contracts, a portable theme-pack schema, three token fixtures derived from the
shipping Android packs, two layout examples, and a dependency-free gallery.

This directory is a **contract + catalog prototype**. It is not `themec` yet: there is no compiler,
no IR, no generated Android/web/iOS renderers. The gallery is a tokenised CSS approximation that lets
us review every component × state × pack × viewport before the compiler exists.

## Run the gallery

```sh
cd theme-kit
python3 -m http.server 8000
```

Open **<http://localhost:8000/gallery/>**.

Do not open `gallery/index.html` directly from disk: browsers block `fetch()` on `file://` pages.

The gallery accepts shareable query parameters:

```text
http://localhost:8000/gallery/?theme=scene.glass&device=tv&component=molecule.tile
```

- `theme`: `scene.apple`, `scene.glass`, `scene.kids`
- `device`: `tv` (1920×1080), `ipad` (1366×1024), `iphone` (393×852), `desktop` (1440×900)
- `component`: any declared component id

## What is here

```text
theme-kit/
  README.md
  schema/
    component.schema.json      # one component manifest (draft 2020-12)
    theme.schema.json          # one portable theme pack (draft 2020-12)
  components/
    index.json                 # gallery order / discovery (not a manifest)
    atom.icon.json
    atom.label.json
    atom.button.json
    atom.chip.json
    atom.focus-ring.json
    atom.surface.json
    atom.avatar.json
    atom.progress.json
    molecule.tile.json
    molecule.nav-item.json
    molecule.settings-row.json
    molecule.seek-bar.json
  themes/
    index.json                 # theme + layout discovery
    apple.json                 # scene.apple   · derived from theme-apple.json
    glass.json                 # scene.glass   · derived from theme-glass.json
    kids.json                  # scene.kids    · derived from theme-kids.json
    layout/
      home.glass.json          # Glass home composition
      home.kids.json           # Kids home composition
  gallery/
    index.html
    gallery.css
    gallery.js
```

Total added size is well under 300 KB. There are no images, fonts, CDN links or build steps.

## Component model

A manifest is one versioned contract in the five-level model:

| Level | In this slice |
|---|---|
| token | `themes/*.json` token groups |
| atom | icon, label, button, chip, focus-ring, surface, avatar, progress |
| molecule | tile, nav-item, settings-row, seek-bar |
| organism | referenced by the layout examples (`organism.wall`, `organism.nav.top`, `organism.hero.cards`, `organism.chip.row`, `organism.rail`); manifests are Phase 1 |
| template | `home` composition in `themes/layout/*.json`; the schema accepts `template.*` ids for Phase 1 |

Every manifest declares:

- **`props`** — named typed inputs (`string`, `number`, `integer`, `boolean`, `image`, `enum`, `color`,
  `token`, `array`, `object`) with optional `default`, `enum`, `min`, `max`, `sizes`, `maxLength`.
- **`states`** — the exhaustive list a renderer must draw. Unknown states fail schema validation.
- **`events`** — the only outputs a component may emit upward. No hub, prefs or activity calls.
- **`slots`** — named holes and the component ids each accepts.
- **`a11y`** — role and accessible label template (`{{prop}}` placeholders).
- **`targets`** — Android, iOS and web renderer names plus their focus strategy.
- **`budget`** — `maxViews`, `maxLayers`, `maxImageBytes` for the performance gate.

The gallery adds sample props only for presentation; a renderer in a real target reads props from
the host and emits the declared events. States are visual, not behavioural, in this MVP.

## Token packs

The three fixtures are **tokens only** from the shipping assets, with metadata added. Canvas,
per-screen geometry, card blocks and other non-token sections were deliberately left host-side for
Phase 0.

| Pack | id | version | basedOn | token groups | source |
|---|---|---|---|---|---|
| Apple | `scene.apple` | 1.0.0 | — | color, space, radius, type, focus, anim, player, pack | `app/src/main/assets/theme-apple.json` |
| Glass | `scene.glass` | 1.2.0 | `scene.apple` | color, space, radius, type, focus, anim, player, pack | `app/src/main/assets/theme-glass.json` |
| Kids | `scene.kids` | 1.0.0 | `scene.apple` | color, space, radius, type, focus, anim, player, pack | `app/src/main/assets/theme-kids.json` |

Counts at extraction time: Apple 46 colours / 17 spaces / 11 radii / 32 type sizes; Glass 79 / 33 / 12 / 32;
Kids 70 / 43 / 12 / 34. The player and pack blocks are preserved verbatim as tokens. No profile,
household, credential or URL data is included.

`requires.caps` and `fallbacks` are declared per pack — Glass asks for `blur.backdrop` and falls back
to `blur.downsample`; all packs ask for `focus.tv` and `image.resize`. `basedOn` is resolved by the
gallery by deep-merging token groups before rendering.

### Layout examples

`themes/layout/home.glass.json` and `home.kids.json` use only the small composition language from the
plan: `stack | row | column | grid | overlay | scroll`, `component`, `slot`, `repeat`, `when`,
`@data` references and token references. They are examples, not yet consumed by Android.

## Gallery behaviour

- Sidebar lists components by level; clicking scrolls the device preview and switches the contract JSON.
- Theme control switches Apple / Glass / Kids; tokens are applied as CSS custom properties
  (`--color-*`, `--space-*`, `--radius-*`, `--type-*`) on the device stage.
- Device control switches TV 1920×1080, iPad 1366×1024, iPhone 393×852 and desktop 1440×900.
  The frame is scaled to fit its pane; the caption shows the scale.
- Every declared state is rendered twice: a large preview with state/variant tabs, and a complete
  state grid. Tile additionally renders poster, landscape, rank and continue variants.
- The right panel shows resolved colours, spacing, radii, type, focus/motion facts and the full
  component manifest JSON with a copy button.
- Focus is painted from the pack: `ring` draws a keyline, `glow` uses the pack accent and bloom,
  `lift` uses scale and shadow. Kids' thick black ring and Glass' blue-grey bloom therefore read
  differently from Apple's white hairline.

## Add a component

1. Add `components/<level>.<name>.json` following `schema/component.schema.json`.
2. Add the filename to `components/index.json`.
3. Add a `renderCase` branch and CSS classes in `gallery/gallery.js` / `gallery.css` for the gallery.
4. Register the renderer ids in each target (Phase 1+). The manifest is the contract; the registry
   lookup is by `id`.
5. Parse-check everything:

   ```sh
   python3 -m json.tool components/<level>.<name>.json > /dev/null
   ```

A component that cannot draw one of its declared states is invalid. Do not silently fall back to
`default`: remove the state from the contract (a version bump) or implement it.

## Add a theme

1. Add `themes/<name>.json` following `schema/theme.schema.json`. Keep `id`, `name`, `version`,
   `targets`, `requires.caps`, `fallbacks`, `tokens`, `assets`, `behaviour`, `licence` and `meta`.
2. Include the four required token groups (`color`, `space`, `radius`, `type`) and, for parity with
   the built-ins, `focus`, `anim`, `player` and `pack`.
3. Optionally add `themes/layout/<template>.<name>.json`.
4. Add the filenames to `themes/index.json`.
5. Use only relative paths under `assets/`. No remote URLs, tokens or personal data.

The gallery picks up the new theme from `themes/index.json` and merges `basedOn` token groups before
applying CSS variables. A pack that changes only tokens needs no gallery code.

## Validation

Every `.json` in this directory parses with Python's `json` module. The two schemas are valid
draft 2020-12 schemas and all 12 manifests plus the three theme packs validate against them.

Reproduce the parse check:

```sh
cd theme-kit
find . -name '*.json' -print0 | xargs -0 -n1 python3 -m json.tool > /dev/null
```

Schema validation (requires the optional `jsonschema` package):

```sh
python3 - <<'PY'
import json, glob
from jsonschema import Draft202012Validator as V
comp = json.load(open('schema/component.schema.json'))
theme = json.load(open('schema/theme.schema.json'))
V.check_schema(comp); V.check_schema(theme)
for path in sorted(glob.glob('components/*.json')):
    if path.endswith('index.json'): continue
    V(comp).validate(json.load(open(path)))
    print('PASS', path)
for path in sorted(glob.glob('themes/*.json')):
    if path.endswith('index.json'): continue
    V(theme).validate(json.load(open(path)))
    print('PASS', path)
PY
```

Negative checks that must fail: deleting `states` from a manifest, declaring a state outside the
enum, removing one of the three target blocks, or omitting `budget.maxLayers`.

## Phase 0 acceptance criteria

From the plan:

> **Phase 0 — contract and gallery (2 weeks)**
> - Write the schema and 12 component manifests (atoms + tile + settings row + hero card).
> - Extract the remaining Glass/Kids geometry into pack tokens; replace family switches in
>   `Screens`/`HomeSurfaceFactory` with registry lookups.
> - Gallery MVP: 4 themes (Apple, Glass, Kids, Netflix) × 3 viewports × all states.
> - Fix catalog naming (`clean`/`kids` labels) and document the pack format.
> - Acceptance: a new pack that only changes tokens renders identically in the gallery and
>   on Android; a manifest with a missing state fails validation.

This slice covers the schema, the requested 12 manifests (atoms, tile, nav item, settings row and
seek bar — the plan's separate hero-card manifest is not in this requested set), token extraction, the
pack format documentation and the gallery engine. Netflix, the Android registry replacement
(`Screens`/`HomeSurfaceFactory`) and the device-parity/golden-screenshot gate remain Phase 0
follow-ups; the gallery currently has three themes and four device presets, as requested for this
pass. There is no compiler/IR here yet, so the gallery reads manifests directly rather than a
validated `theme.ir.json`.

## Licence

MIT, matching the repository. The packs derive from Scenix's own token assets; no third-party marks
or artwork are included.
