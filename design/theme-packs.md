# Theme packs

A Scenix theme is **not a colour swatch**. It is a complete pack: tokens (colour, type, spacing,
radii, focus timing, animation) plus a `pack` block that chooses the layout, the rail shape, the card
treatment, the focus effect, the motion language and the detail-page shape. One JSON file drives the
Android TV and the browser; `assets/themes.json` is the registry both read.

The rule for a new pack is the same as for a new row: **add a file, not a branch in a screen**. A
screen asks `theme.packString("hero", "full")`, never `if (theme.id == "scenix-midnight")`.

## 1. Files

| Where | What |
|---|---|
| `app/src/main/assets/theme.json` | Classic pack, also the built-in fallback |
| `app/src/main/assets/theme-midnight.json` | Cinema pack |
| `app/src/main/assets/theme-ember.json` | Editorial pack |
| `app/src/main/assets/themes.json` | Picker registry: id, name, asset, pack label |
| `hub/web/assets/…` | The same files for the browser |
| `hub/web/css/packs.css` | The web implementation of the pack choices |

The hub serves the selected pack from `GET /app/v1/theme` (tokens + registry). The native shell reads
the asset named by `Prefs.theme`; changing the theme restarts the task at the profile gate.

## 2. The `pack` block

```jsonc
"pack": {
  "name": "Cinema",          // shown in the picker as "Midnight · Cinema"
  "hero": "centered",        // full | split | centered
  "heroAlign": "center",     // left | center (used by full)
  "heroHeight": 720,         // native hero height, 1920x1080 pixels
  "rows": "landscape",       // poster | landscape (ordinary poster rows)
  "card": "glass",           // overlay | frame | glass
  "focus": "glow",           // ring | glow | lift
  "focusColor": "#38BDF8",   // used by glow
  "motion": "fade",          // rise | fade
  "detail": "backdrop",      // backdrop | split
  "nav": "classic",          // classic | pill
  "rank": "solid",           // solid | outline
  "stage": "off"             // off | focus (native Apple scene: backdrop follows the ring)
}
```

### What each choice really does

- **hero**
  - `full` — full-bleed backdrop, words floating over it (classic native look).
  - `split` — a portrait poster beside the words (editorial).
  - `centered` — words in the middle of a full-bleed frame (cinema).
- **rows** — `landscape` turns ordinary `poster` shelves into 16:9 stills; ranked rows keep their
  numeral geometry and channel rows keep their own shape.
- **card**
  - `overlay` — art with a bottom scrim and lettering on focus.
  - `glass` — rounded plate, inset edge, a floating translucent caption chip.
  - `frame` — square plate, hairline border, always-on opaque caption bar; the logo moves into the bar.
  - `below` — the scene-card shape: 16:9 art, name and year always under the picture (Apple).
- **focus**
  - `ring` — the classic white outline.
  - `glow` — the pack accent, doubled and blurred, with a lift.
  - `lift` — no outline; scale and shadow are the focus, so even buttons that normally skip scaling
    still move under the ring.
- **motion** — `rise` keeps the short focus step; `fade` uses the pack's longer cross-fades.
- **detail** — `split` puts the poster beside the title instead of behind it.
- **nav** — `pill` floats one centred capsule over the stage (Apple); `classic` keeps the wordmark
  left and the links right.
- **rank** — `outline` draws hollow Top-10 numerals (Apple); `solid` fills them (classic/Netflix).
- **stage** — `focus` gives the native home one full-screen backdrop behind the shelves. It crossfades
  to the focused tile's art, dims every other card and blurs/dims the stage while the viewer roams;
  `off` keeps the opaque page. This is native-only; the browser's shelf layer already sits on its own
  page background.

## 3. The three packs now

| Pack | Hero | Rails | Cards | Focus | Nav | Detail |
|---|---|---|---|---|---|---|
| **Classic** (Scenix Dark) | full, left | portrait | overlay | white ring | classic | backdrop |
| **Cinema** (Midnight) | centred, full-bleed | landscape | glass | cyan glow | classic | backdrop |
| **Editorial** (Ember) | split (poster + words) | portrait | framed, caption bar | warm lift | classic | split |
| **Scene** (Apple) | full, left | landscape | names below the art | white hairline + bloom | centred pill | backdrop |
| **Classic** (Netflix) | full, left | portrait | overlay, red brand | white ring | classic | backdrop |

The Apple pack follows `design/prototypes/atv-home.html` (the other agent's prototype): 16:9 scene
cards with names underneath, a centred floating pill, lift with a white rim/bloom, hollow rank
numerals, and the native stage that follows focus (`stage: "focus"`). Netflix is the canonical red-on-black cinematic shape with portrait shelves. The packs are
therefore structurally different, not five repaints of one screen.

## 4. Adding a pack

1. Copy `theme.json` (or a new pack) to `app/src/main/assets/theme-<id>.json`.
2. Change its `id`, colours, type, and the `pack` block. Keep `canvas` at 1920x1080; every length is
   scaled by the real screen.
3. Copy the same file to `hub/web/assets/`.
4. Add one row to both `themes.json` files:
   ```json
   { "id": "scenix-<id>", "name": "Name", "asset": "theme-<id>.json", "pack": "Pack label" }
   ```
5. Build the APK and redeploy the hub. The picker appears in **Settings → Theme** and in the web
   Settings page; selecting it reloads both.
6. If the pack needs a shape the three existing choices do not cover, add one rule to
   `hub/web/css/packs.css` keyed on `data-…` and one branch in the native screen that reads the new
   `packString` value. Do it once in the shared place, not per theme id.

`scripts/check-theme-colours.mjs` still enforces `rgba()`/hex rules on the token files.
