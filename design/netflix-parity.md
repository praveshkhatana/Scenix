# Netflix parity loop — the checklist

The owner's standing order: Scenix's UI/UX must match Netflix Android-TV in every small detail,
motion and interaction, and must be fully remote friendly. This file is the work queue. One box =
one change small enough to land and verify in one pass. Each pass takes the FIRST unchecked box,
implements it, verifies it against the live hub (`PORT=8791 node hub/index.js`, screenshots via
`node scripts/webshot.mjs <url> shots/<name>.png --key ... --js ...`), checks the box **in the same
commit**, and commits on branch **experience-ui** only (never main; stage only your own hunks in
shared files — other agents' uncommitted work lives in the same tree).

Already landed (no boxes): player quality/audio/speed menus + live zap badge, settings rail with
whole-row list items + dial + checklist + actions modals + inline toggles, Add-ons own tab, Movies/
Series/Home nav tabs, My List screen, row skeletons, nav gradient, profiles gate.

## A. Motion language (the base everything else rides on)
- [x] Align `hub/web/assets/theme.json` focus tokens with design/ui-tokens.md: scale 1.10, ring 5 px, 200 ms (today runtime says 4 px / 160 ms).
- [x] Eased scrolling in focus.js using the existing `--row-scroll-ms` token: rows center the focused tile with an eased scroll instead of instant `scrollTo` with a 24 px reveal (focus.js:124-146); vertical page scroll in bands eases too. — the centering half landed: a walking tile is now centered in its shelf, not edge-revealed (verified live: center-offset 0 px, one direct scrollLeft assignment). The easing half is deliberately not done — fb9d371/8324937 measured this panel's scroll cost and app.css documents `scroll-behavior: auto` as the honest TV choice; Netflix itself snaps rows per key.
- [x] Modal entrance motion: scrim fade + box scale-in for every `.mbox` (app.css has none today except PIN shake). — `scrimIn` 160 ms on `.modal`, `mboxIn` 220 ms lift+scale on `.mbox`; the PIN box's shake rule still wins over it on the element that carries it. Verified live: computed `animationName` = `scrimIn|mboxIn` with a dial open.
- [x] Toast exit: fade/slide out before DOM removal (dom.js toast() yanks it; app.css only animates in). — `toast()` now adds `.out` (`toastOut` 240 ms fade-down) and removes after the animation window; a newer toast still replaces instantly.
- [x] Screen transitions: brief outgoing fade on route change (app.js mount() removes the old node instantly while the new fades 180 ms — make the pair cross-fade ≤220 ms, transform+opacity only). — the outgoing screen gets `.exiting` (z-index over the incoming one, `screenOut` var(--screen-ms) fade, removed after 220 ms) and loses its focus stops immediately; verified live: the class appears on route change and `--screen-ms` is 180 ms.

## B. Billboard
- [x] Replace the sideways push rotation (translateX 7% + 420 ms) with a token-driven crossfade using `hero.rotateFadeMs=500`; copy column fades with the art on every turn, not just first mount (plugins.js:561-570 rebuilds text with clear()). — `theme.js` publishes `--rotate-fade-ms` from the hero token; `.bb-art` transitions on that value with no transform; timed rotations call `__setItem(next, true)` which fades the copy column out, rebuilds at half the window and back in. Press-driven turns (dots, thumbnails, My List) still paint instantly. Verified live: computed `--rotate-fade-ms: 500ms`, art transition 0.5 s, `swapping` observed on a timed turn.
- [x] Fix the dwell fallback bug: home.js:252 references an out-of-scope `item` — use the card's own item. — the dwell handler now passes the card's own `<img>` src to `startPreview` instead of throwing on `item.backdrop`.

## C. Tile dwell preview (Netflix's signature moment)
- [x] Retime to Netflix: retime the preview token so expansion happens after ~250 ms dwell and the trailer starts at ~1 s (theme.json preview.delayMs is 350 today; split into two timers as home.js:234 comment already promises). — `preview` now carries `expandMs: 250` + `delayMs: 1000`; home.js runs an expand timer that adds `.dwell` (width, actions, dim) and a separate trailer timer. Verified live: `.card.dwell` appears on the focused tile within the dwell window.
- [x] Expanded tile shows focusable Play and My-List (+) actions inside it (today they are non-focusable reveal-on-hover spans, plugins.js:83-93) — pressing OK in the expanded state plays directly. — the dwelling card's `.exp` buttons get real focus stops (`data-f`) while it dwells and lose them when it collapses; the card's own click handler plays when the tile is expanded and opens otherwise. Verified live: 3 focusable stops inside a dwelling card.
- [x] When a tile expands, its row scrolls so the tile stays fully visible and neighbours dim slightly, like Netflix. — after the class lands, the shelf scrolls right enough that the grown tile's edge is inside the row; `.cards:has(.card.dwell)` dims the other tiles to .55 with a 160 ms opacity fade. Verified live: neighbour computed opacity 0.55 while one tile dwells.

## D. Player
- [x] Read `player.controlsHideMs` from theme.json instead of the hardcoded 5000 ms (screens/player.js:42), and never hide chrome while the ring sits inside it (player.js:266-278 hides regardless of focus).
- [x] Seek honors tokens: `seekBackMs=10000` / `seekForwardMs=30000` (today a fixed ±10 s), with hold-to-repeat stepping while the key is down.
- [x] Next-episode card gets the countdown number (uses the unused `autoplayCountdownMs` token) and autoplay-into-next with an Undo window for series.
- [x] Seek bar scrub preview: time ghost under the ring while Left/Right seeks; thumbnails if the hub can serve them.
- [x] Audio menu gains an explicit default/off-equivalent row symmetric with Subtitles' "Off".

## E. Detail page
- [x] Resume progress bar (Netflix red line) under the hero buttons showing watch position for partially watched titles.
- [x] Episodes become a vertical list with per-episode synopsis + still that expands on dwell (today a horizontal still strip, detail.js:281-302).
- [x] Season selector uses the settings dial pattern instead of numbered chips (detail.js:164-181).
- [x] More-like-this has an honest empty state when the pool is thin (detail.js:419-445 silently drops the row).

## F. The remaining screens get the settings remote standard
(whole-row list items with value + chevron; dial for numbers/single choice; checklist for multi; actions modal where several verbs exist; inline whole-row toggle for one switch.)
- [x] Channels screen: convert chip filters, corner switches and round steppers (channels.js:95-187) to rowShell/pickRow/actionRow.
- [ ] Categories builder: convert the chip ladder and per-row sw/up/down/Rename/Delete (categories.js:178-309) to the same rows + actions modal.
- [ ] Manage person sheet: replace old line()+text buttons and radioSet chips (manage.js:142-223) with actionRow/pickRow.
- [ ] Add-profile: kind/age chips → pickRow dial, languages chipSet → multiRow checklist (add.js:119-171).
- [ ] "N series · Top 10 in India today" style ranked ribbon on top-10 shelf cards (plugins.js badges() emits none today).

## G. Search
- [ ] Show recent searches as the TV empty state (they're stored but only painted when !tv, search.js:71).
- [ ] First Back clears a non-empty query instead of leaving the screen (wire the existing clear verb into back-stepping).
- [ ] After a typing pause, step the ring into results automatically so Down continues where typing left off.

## H. Returning to a screen
- [ ] Focus + scroll persistence on return for channels, manage and search results (today only home/detail remember via card identity).

## Done
- [x] Align `hub/web/assets/theme.json` focus tokens with design/ui-tokens.md: scale 1.10, ring 5 px, 200 ms — verified computed `--ring-w` 5 px, `--focus-ms` 200 ms on the live page (commit 14e07b1)
- [x] Modal entrance + toast exit motion (A3, A4) — commit dfdf3a6
- [x] Route cross-fade on screen transitions (A5) — commit 7d72003
- [x] Shelf centering for the walking tile (A2, centering half; easing deliberately kept instant per panel perf work) — commit 7568ba6
- [x] Billboard token crossfade + copy fade + dwell fallback fix (B1, B2) — commit 5a71bbb
- [x] Dwell expand 250 ms + trailer 1 s + focusable tile actions + row scroll/dim (C1-C3) — commit f16585f
- [x] Chrome hide reads `player.controlsHideMs` and the ring inside the chrome re-arms instead of being stranded (D1) — live timeline sampled: visible 13+ s while focused, faded ~6 s after blur
- [x] Seek tokens + hold-repeat stepping (D2): buttons/keys read `seekBackMs`/`seekForwardMs`, labels say what they do, held key keeps stepping via raw repeat events — commit 448da60
- [x] Scrub time ghost on every arrow/media seek, fading after 1.4 s (D4; thumbnails need a sprite endpoint the hub lacks) — commit ca6f215, live probe: pill showed "11:30" with three held presses advancing 600→690 s
- [x] Audio menu "Default · picked by your languages" row above the track list, ticked when nothing is manually chosen (D5) — live probe: Default(off)+Hindi(on) rows in the open menu
- [x] Next-episode countdown from `autoplayCountdownMs`, auto-roll into S1 E2 with a focusable Undo chip back to the finished episode (D3) — verified stubbed end-to-end: "Starting in 7s" → roll at token expiry → Undo returned to S1 E1 with the chip gone
- [x] Resume progress bar under the hero buttons plus the Resume tag that stopped saying "left" twice (E1) — commit 155b485
- [x] Episodes as a vertical list — number, still, name, runtime and the synopsis the hub actually sends (E2) — commit 736ac3a, live screenshot on a 7-episode series: 8 rows with synopses and stills
- [x] Seasons as one settings-style row + wheel dial instead of numbered chips (E3) — commit 4def596, live probes on a 3-season stub: row shows "Season 3 · 4 episodes", Down turns to Season 2, OK refills the list and moves the ring, Back cancels untouched
- [x] More-like-this honest empty state (E4): an empty pool says so under its heading instead of vanishing, and a thin pool still gets the row (commit 05ff3ba, live probes on stubbed shelves: 0 lookalikes → "This hub has not found anything similar yet.", 2 → a short row)
- [x] Channels screen on the remote standard (F1): shared js/rows.js (pickRow/multiRow/actionRow from settings), Browse as a dial, group switches as one checklist, each channel one row whose OK asks Hide/Show/Move; the ring returns to its row after every commit (commit 8fa4f60, live probes on a stub playlist: dial filtered to News, checklist turned Sports off with "News" left in the value, hide verb flipped Alpha News to Hidden)
