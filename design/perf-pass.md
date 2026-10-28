# Perf pass — shelf recycling and one focus pool

Date: 28 Oct 2026. Scope: `hub/web/js/plugins.js`, `hub/web/js/focus.js`, `hub/web/js/screens/home.js`,
`hub/web/app.css`. No hub-side code, no build step, no new listener outside the existing per-screen lane.

## 1. Shelves recycle their cards

`lazyMount` already mounted a row once its shell came near the scroller, but it never unmounted one. After a
walk down Home, all fourteen shelves held their eighteen cards (plus the See all tile) for the rest of the
session. On `/app/v1/home` that is up to **266 card nodes**, each with art, badges, a progress bar on some
rows and three quick-action buttons.

The lane now keeps every row shell but only the cards of rows around the viewport:

- **Mount** everything in the window: the visible rows plus **two above and two below**, and anything the
  existing 700 px preload reaches. Building the rows above the ring before the viewer presses Up is what
  keeps D-pad navigation whole.
- **Unmount** a row's card DOM when it is outside that window, keeping the `<div class="row">`, its heading,
  its `.cards` element with `data-nav="h"`, and its height.
- **Remount** by calling the row's existing `__mount(node.__items)`. A late-filled Home shelf now replaces
  whichever `.cards` box is standing (skeleton first, empty rail later) instead of replacing a detached
  skeleton; its spent `__ensure` is cleared so the recycler cannot refetch.
- **Never unmount the focused row.** If `focus.getFocus()` / `document.activeElement` lives inside it, the row
  stays mounted. No focusable card is ever left inside an empty rail.
- `.cards:empty` now keeps the rail's **full** height. The old rule only reserved `--card-h`; because
  `box-sizing: border-box`, the 40 px top and 76 px bottom padding were missing, so an unmounted row was
  116 px shorter than a mounted one. That would have moved everything below it. Measured in the harness:
  every row is 602 px both before and after recycling, with scroll height unchanged.

Why rows rather than pixels for the window: a poster shelf and a landscape shelf are different heights, and a
row window keeps the same navigation guarantee on both. The pixel preload stays as the early-mount path.

### Measured in a headless 1920×1080 harness (14 synthetic shelves × 18 cards)

| State | Mounted rows | Card nodes |
|---|---|---|
| Old behaviour after browsing Home | 14 | 266 |
| Initial Home after recycle | 4 | 76 |
| Top of page after scrolling to the bottom and back | 4 | 76 |
| Bottom of page | 4 | 76 |
| During a full D-pad walk | 4–5 | 76–95 |

The harness also walked **all 14 rows down and all 14 back up** with the focus engine, verified the See all
tile remounts in row 0, and confirmed a focused row stays mounted when the page is jumped to the bottom. The
expected panel effect is 64–71 % fewer live card nodes, images and composited layers in the common case; the
focus query pool and repaint area shrink with it. The device number still has to come from `dumpsys gfxinfo`
on the VU1 — no device access was used in this pass.

Trade-off: scrolling back into a recycled row rebuilds its cards from `__items` and re-resolves its images
(usually browser-cache hits). The window is deliberately wider than the viewport so that rebuild is never on
the frame the ring arrives; a focused row is exempt, so worst-case memory is one extra shelf against
fourteen.

## 2. One candidate pool per focus move

`focus.js` already cached rectangles and computed styles per move. It still had two smaller paths that could
do more work than necessary:

- `groupMembers` ran its own `querySelectorAll` and `visible` filter, separate from `candidates()`.
- `bandStep` and the geometric fallback each asked for candidates (the cache made that cheap, but they could
  still see different arrays).

`moveWithin` now asks for `candidates()` **once** after checking the current ring, and passes that array to
group walking, `bands()`/`bandStep()` and `geometric()`. The band lookup itself (`bandOf`) is memoised in the
same per-move cache, so the parent chain is not walked twice for the same card. No new global listener was
added; the existing `mouseover` listener and per-screen lane scroll listener are unchanged.

## 3. Quick-action buttons were left eager — deliberately

The three per-card quick actions are still built with the card. Deferring them to first focus would remove
three buttons and their icons from every never-focused card, but it would also make the first 250 ms focus
reveal depend on DOM built inside the focus callback. On the panel that is exactly where a pop or a missed
first frame would be blamed on the UI. Recycling removes those buttons from every off-window row instead,
which gets most of the node count back without touching the focus reveal.

## 4. What did not change

Card visuals, card counts (18 + See all), row order, the billboard, the detail page, search grids, channels,
the `See all` tile, and every screen's use of `lazyMount`/`dropMountedRows`. The hub is untouched.
