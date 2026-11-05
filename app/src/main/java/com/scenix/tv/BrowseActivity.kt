package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Shader
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.TopNav
import com.scenix.tv.core.CardActionSheet
import com.scenix.tv.core.CardActions
import com.scenix.tv.core.Motion
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Prefs
import com.scenix.tv.core.HomeLayoutSpec
import com.scenix.tv.core.Item
import com.scenix.tv.core.LeftRail
import com.scenix.tv.core.Updates
import com.scenix.tv.core.Views

/**
 * Stage one of the native front end: the Home/browse shelves.
 *
 * The hub still owns the catalogue and the web UI still owns Detail, Player and Settings. This
 * activity renders the same `/app/v1/home` rows in a native vertical RecyclerView of horizontal
 * RecyclerViews, so a D-pad walk reuses card views and scrolls a compositor layer instead of
 * repainting a 262-card page. A tile opens the web Detail route; the web Home action comes back here.
 */
class BrowseActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var profileId: String
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }
    private var loaded = false
    /**
     * The pack's answer to an empty or failed shelf (owner ruling: a bare line of text on the
     * wall reads as a bug, not a state). A frost plate stands in the middle of the page — glyph,
     * what happened, and a focusable Try again that re-runs the load. Non-glass packs keep the
     * plain status line, which is their language.
     */
    private var emptyOverlay: View? = null

    private fun isGlassPack(): Boolean =
        app.theme.homeLayout.hero == HomeLayoutSpec.Hero.GLASS_CARDS

    private fun showEmpty(title: String, sub: String) {
        hideEmpty()
        val host = window.decorView.findViewById<ViewGroup>(android.R.id.content)
        val wrap = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        val card = GlassKit.emptyState(this, app, title, sub, true) { load() }
        wrap.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })
        host.addView(wrap, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        emptyOverlay = wrap
        if (app.theme.motionEnabled) Motion.sheetIn(card, app.theme)
        card.post { card.findViewWithTag<View>("retry")?.requestFocus() }
    }

    private fun hideEmpty() {
        (emptyOverlay as? ViewGroup)?.let { host ->
            window.decorView.findViewById<ViewGroup>(android.R.id.content).removeView(host)
        }
        emptyOverlay = null
    }
    private var stage: Stage? = null
    /** Last shelf card which changed the room. Avoids repeating backdrop work on the same card. */
    private var roamingCard: View? = null
    private var heroItem: Item? = null
    // "" = Home; "movies"/"series" open the same surface filtered to a section. The pill stays on
    // screen across those switches, so one LEFT/RIGHT walks whole sections without a trip back.
    private var section = ""
    private var skeleton: View? = null
    private var contentRoot: ViewGroup? = null
    /** The floating nav fades once the billboard has gone under it, and always shows when the
     * remote is standing on it or back at the top of the page. */
    private var chromeBar: View? = null

    // The hero and the first shelf are ONE section, the way Apple's shelf-top works: the slides the
    // hero shows are the tiles of the Featured row, and whichever side moves, the other follows.
    private var slides: List<Item> = emptyList()
    private var heroPos = 0
    private var browseAdapter: BrowseAdapter? = null
    // Which optional add-ons the household installed. The bar is painted before the hub can answer,
    // so `caps` is the last known fact (from Prefs) and `barCaps` is the snapshot this page's bar
    // was actually drawn from — the load that follows compares the fresh answer against that, not
    // against the cache, so a first launch still resolves to one shape.
    private var caps: FeatureCaps? = null
    private var barCaps: FeatureCaps? = null
    /** The Live TV doors on screen (pill, classic bar), so an older hub can still earn one from a row. */
    private var liveNav: View? = null
    // The pill floats over a full-bleed stage, so geometric focus search can't be trusted to walk
    // from a tile up into it. One named anchor on the pill and one on the list make the route
    // explicit: UP from the featured tiles lands on the bar, DOWN from the bar returns to the top.
    private var navAnchor: View? = null
    private var pillGroup: ViewGroup? = null
    /** Ordered remote stops in the floating Apple-style navigation pill. */
    private var pillStops: List<View> = emptyList()
    private var shelfListId = View.NO_ID
    // The left rail is the way to another screen from the middle of the page. Netflix and YouTube
    // put navigation on the left edge for the same reason: asking a viewer to scroll
    // back to a top bar to change tabs is a trap. It is an overlay, so the shelf keeps its place.
    private var leftRail: LeftRail? = null
    /** The page shell, so an empty library can explain itself above every theme layout. */
    private var shellView: FrameLayout? = null
    private var emptyPanel: LinearLayout? = null
    private val clock = Handler(Looper.getMainLooper())
    private var rotationPaused = false
    // Apple's billboard never moves under the viewer's hands: any key on the remote rewinds the
    // idle clock, and the slide show only resumes once the room has been quiet for a while. This
    // is what makes one press mean one step — the old free-running timer re-drew the featured
    // row mid-walk, and the dropped focus read as a double press.
    private var lastKeyAt = 0L
    private val rotateHero = object : Runnable {
        override fun run() {
            // A preview that is running is the viewer paying attention: rotation waits for it.
            val watching = browseAdapter?.heroVideo()?.visibility == View.VISIBLE
            val idleFor = android.os.SystemClock.elapsedRealtime() - lastKeyAt
            if (!rotationPaused && slides.size > 1 && !watching && idleFor > 9_000L)
                setHero((heroPos + 1) % slides.size)
            clock.postDelayed(this, app.theme.token("hero", "rotateMs", 6000.0).toLong())
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // A hold sheet owns the page. Its rows keep the D-pad and Back closes the sheet — the shelf
        // behind it, the rail and the section-walk all stay deaf until it is down.
        if (CardActionSheet.isOpen()) {
            if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                event.action == android.view.KeyEvent.ACTION_UP
            ) {
                CardActionSheet.close()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        // A section tab re-launches BrowseActivity with CLEAR_TOP, so Home is not underneath in
        // the task stack and a bare BACK would leave the app. From a section, BACK walks to the
        // unfiltered Home first; the next BACK from Home exits, as expected (owner, round 6).
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
            event.action == android.view.KeyEvent.ACTION_UP && section.isNotEmpty()) {
            goTab("")
            return true
        }
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            lastKeyAt = android.os.SystemClock.elapsedRealtime()
            val rail = leftRail
            if (rail?.isOpen == true) {
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> { rail.move(-1); return true }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { rail.move(1); return true }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                    android.view.KeyEvent.KEYCODE_BACK -> { rail.close(); return true }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER,
                    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        rail.focusedRow()?.performClick()
                        return true
                    }
                }
                return true
            }
            // LEFT at the left edge of a row (or a one-slide billboard) is the way into the rail.
            // A LEFT that can still move within the row belongs to the row.
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT &&
                shouldOpenRail(currentFocus)) {
                leftRail?.open(section)
                return true
            }
            val adapter = browseAdapter
            val focus = currentFocus
            val heroFamily = app.theme.homeLayout.hero
            // Preview tiles live inside the billboard: UP is the one explicit step from a slide
            // selector to its Play action, rather than a geometric jump to an unrelated shelf.
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                heroFamily == HomeLayoutSpec.Hero.SHARED_SLIDER &&
                adapter != null && isInside(adapter.heroSelector, focus)) {
                adapter.heroPlay?.requestFocus()
                return true
            }
            // UP once more from the billboard reaches the navigation pill.  This preserves a
            // simple, visible hierarchy: nav → hero action → featured slides → shelves.
            val anchor = navAnchor
            if (anchor != null && heroFamily != HomeLayoutSpec.Hero.FOCUSED_HUB &&
                event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP && onTopRow(focus)) {
                anchor.requestFocus()
                // The billboard is the page, not a strip above it. Once the remote is back on the
                // bar the household should see the banner, not one Play button clipped under it.
                list.scrollToPosition(0)
                return true
            }
            // DOWN out of the pill lands on the preview belonging to the visible hero slide. The
            // tile is a carousel point, so entering the hero never drops the viewer into a second
            // content rail.
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN &&
                slides.isNotEmpty() && isInside(pillGroup, currentFocus)) {
                // Only step into the preview strip when it is really on screen. A pack whose hero
                // row carries a single slide hides it, and routing DOWN into a GONE strip was a
                // dead key: the viewer pressed down from the pill or Play and nothing moved.
                if (hasHeroSelector()) {
                    focusFeatured(heroPos.coerceIn(0, slides.size - 1))
                } else {
                    adapter?.heroPlay?.requestFocus()
                }
                return true
            }
            // The billboard itself needs to be a real carousel too.  Before this route existed,
            // LEFT/RIGHT only worked after navigating down into its small Featured tiles, which
            // made the large hero appear frozen.  A step updates the hero immediately and then
            // lands focus on the matching tile, so subsequent remote presses remain predictable.
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (heroFamily == HomeLayoutSpec.Hero.SHARED_SLIDER && slides.size > 1 && adapter != null &&
                    isInside(adapter.heroRoot, focus) &&
                    // The embedded preview strip is a real slider: it owns LEFT/RIGHT so focus
                    // stays on its selected thumbnail. The Hero Play stop also supports arrows,
                    // but returning focus to Play from a preview made the selector feel detached.
                    !isInside(adapter.heroSelector, focus)) {
                    stepHero(if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                    return true
                }
            }
            // Vertical movement between shelves is routed by shelf position, not left to geometric
            // focus search. A row below the viewport is not laid out yet, so search could not find
            // it and fell back to the hero; after walking back up, DOWN was trapped on the billboard.
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN ||
                event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                if (focus != null && adapter != null && isInside(list, focus)) {
                    val down = event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    val from = outerPositionOf(focus)
                    // The focused-hub and dual-card surfaces own the relationship between their
                    // navigation strip, content tiles and actions. Let their explicit next-focus
                    // graph answer UP/DOWN rather than forcing the Apple carousel hierarchy on them.
                    if (heroFamily in setOf(HomeLayoutSpec.Hero.FOCUSED_HUB,
                            HomeLayoutSpec.Hero.DUAL_CARDS,
                            HomeLayoutSpec.Hero.GLASS_CARDS,
                            HomeLayoutSpec.Hero.KIDS_STAGE) &&
                        isInside(adapter.heroRoot, focus)) {
                        return super.dispatchKeyEvent(event)
                    }
                    if (heroFamily == HomeLayoutSpec.Hero.SHARED_SLIDER &&
                        isInside(adapter.heroSelector, focus)) {
                        // A selector is the bottom edge of the one Hero component. DOWN leaves the
                        // slider for the first ordinary shelf; it must not re-enter itself.
                        if (down) {
                            moveVertical(1, focus)
                            return true
                        }
                        adapter.heroPlay?.requestFocus()
                        return true
                    }
                    if (isInside(adapter.heroRoot, focus)) {
                        if (heroFamily == HomeLayoutSpec.Hero.CINEMA_BILLBOARD) {
                            if (down) moveVertical(1, focus) else navAnchor?.requestFocus()
                            return true
                        }
                        if (down && hasHeroSelector()) {
                            focusFeatured(heroPos.coerceIn(0, slides.lastIndex))
                            return true
                        }
                        // No preview strip to land on (a single featured title): DOWN is the
                        // ordinary way out of the billboard and belongs to the first shelf.
                        moveVertical(if (down) 1 else -1, focus)
                        return true
                    }
                    if (from >= 0) {
                        moveVertical(if (down) 1 else -1, focus)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * The floating bar belongs to the top of the page: the whole bar — wordmark, pill, and action
     * icons together — steps aside once the billboard has actually gone under it, so a shelf
     * heading never sits beneath it. It is measured from the hero's own frame, because the
     * RecyclerView's scroll offset is an estimate with variable-height rows. A strict threshold
     * with nothing re-asserting it is how Home kept no nav at all after a DOWN-and-UP: the fade
     * animates (opacity only), and standing on the bar or on the billboard always brings it back,
     * so a focus stop is never invisible.
     */
    private fun updateBrandFade() {
        val bar = chromeBar ?: return
        val f = currentFocus
        val onTopChrome = f != null && (isInside(bar, f) || outerPositionOf(f) == 0)
        val lm = list.layoutManager as? LinearLayoutManager
        val hero = lm?.findViewByPosition(0)
        // Before the list is laid out there is no hero frame to measure; the bar starts visible.
        // Hiding it on "not measured yet" is how Home came up with no wordmark at all.
        val visible = onTopChrome || (hero != null && hero.bottom > app.theme.px(96f))
        val target = if (visible) 1f else 0f
        if (bar.alpha == target) return
        val ms = app.theme.token("anim", "navFadeMs", 180.0).toLong().coerceIn(60L, 400L)
        bar.animate().cancel()
        bar.animate().alpha(target).setDuration(ms).start()
    }

    /**
     * The billboard's height. A pack may ask for a tall banner, but a banner that eats the whole
     * page is a page with no shelves on it: at the household zoom a 720dp hero filled 1080p down
     * to the last pixel and the first shelf was a sliver too short to lay out or focus. One
     * shelf's heading and a strip of its tiles always stay in view.
     */
    private fun heroSlotHeight(wantedPx: Int): Int {
        val theme = app.theme
        val room = resources.displayMetrics.heightPixels - theme.space("heroReserve", 200f)
        return wantedPx.coerceAtMost(maxOf(room, theme.px(420f)))
    }

    /**
     * The Glass caption line: "● 7.8 · 2023" — an accent dot welded to the rating, the year
     * quieter after it, exactly as the reference prints it under every poster.
     */
    /** Advance the visible hero exactly one slide, wrapping like the Featured rail. */
    private fun stepHero(delta: Int) {
        if (slides.size < 2) return
        // Direct-billboard packs deliberately use the CTA edges as their slider controls. Keep
        // the remote on that same control after its title changes; snapping More Info back to Play
        // would make Netflix navigation feel like a dropped or duplicated press.
        val retainFocus = currentFocus
        val next = Math.floorMod(heroPos + delta, slides.size)
        // Do not move the outer shelf while the remote is changing a slide. A D-pad press should
        // only change the hero, never make the whole page visibly jump.
        onSlideFocused(next)
        if (retainFocus?.requestFocus() != true) browseAdapter?.heroPlay?.requestFocus()
    }

    /** The outer-list position whose row contains [v], or -1 when it is not on a shelf. */
    private fun outerPositionOf(v: View?): Int {
        var cur: View? = v
        while (cur != null) {
            val parent = cur.parent
            if (parent === list) return list.getChildAdapterPosition(cur)
            cur = parent as? View
        }
        return -1
    }

    /**
     * One row per press, skipping rows that carry no focusable card. The column is preserved by the
     * tile's own adapter position, not by where its centre happened to be: rows differ in card width
     * (poster, landscape, ranked), so matching screen x landed on the first tile of the next row
     * whenever the new rail had only its left edge laid out — the "random tile" hop.
     */
    private fun moveVertical(direction: Int, from: View) {
        val current = outerPositionOf(from)
        if (current < 0) return
        val x = screenCenterX(from)
        val rowHeight = outerRowHeight(from) ?: app.theme.px(360f)
        var target = current + direction
        // The Hero selector is now inside the hero. Moving out of the top component lands on the
        // first ordinary shelf at position one.
        if (current == 0 && direction > 0) target = 1
        val total = list.adapter?.itemCount ?: 0
        while (target in 0 until total) {
            if (target == 0) {
                // Each structural home defines its own re-entry point: Apple returns to its
                // preview selector, Netflix to Play, and PS5 to the selected square tile.
                val hero = list.findViewHolderForAdapterPosition(0) as? HeroHolder
                if (hero?.surface?.focusEntry(heroPos) == true) {
                    // The banner is the point of the step. RecyclerView only scrolls the focused
                    // child into view, which left Play clipped at the top and the artwork above it
                    // hidden, so "back to the hero" looked like it never happened.
                    list.scrollToPosition(0)
                    return
                }
                if (hero == null) {
                    list.scrollToPosition(0)
                    list.postDelayed({
                        currentFocus?.let { focus -> moveVertical(direction, focus) }
                    }, 60L)
                }
                return
            }
            if (focusShelfAt(target, x, direction, rowHeight, 0)) return
            target += direction
        }
    }

    /** The height of the outer row containing [v], used to move the page by one row, not one page. */
    private fun outerRowHeight(v: View?): Int? {
        var cur: View? = v
        while (cur != null) {
            val parent = cur.parent
            if (parent === list) return cur.height.takeIf { it > 0 }
            cur = parent as? View
        }
        return null
    }

    /** The x centre of a view on screen, used to land on the tile in the same column below. */
    private fun screenCenterX(v: View?): Int {
        if (v == null) return 0
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return loc[0] + v.width / 2
    }

    /**
     * Focus the tile in the same visual column on the row at [position]. Rows differ in card width
     * (poster, landscape, ranked), so the column is the card nearest [x] on screen — not an adapter
     * index, which lands at the left edge when a row's width differs from the one above.
     *
     * A row that is not attached yet, or a rail whose laid-out window does not cover [x], is moved
     * one step and retried. Focusing the only card that happened to be laid out was the "jump to a
     * random tile" bug: the first frame of a new row showed the viewer its left edge.
     */
    private fun focusShelfAt(
        position: Int,
        x: Int,
        direction: Int,
        rowHeight: Int,
        tries: Int,
    ): Boolean {
        val holder = list.findViewHolderForAdapterPosition(position)
        val attached = holder?.itemView?.isAttachedToWindow == true
        if (attached && holder is ShelfHolder) {
            val rail = holder.rail
            if (rail.childCount > 0) {
                val first = rail.getChildAt(0)
                val last = rail.getChildAt(rail.childCount - 1)
                val firstCenter = screenCenterX(first)
                val lastCenter = screenCenterX(last)
                val firstPos = rail.getChildAdapterPosition(first)
                val lastPos = rail.getChildAdapterPosition(last)
                val count = rail.adapter?.itemCount ?: 0
                val needLeft = x < firstCenter - themeGapPx() && firstPos > 0
                val needRight = x > lastCenter + themeGapPx() && lastPos < count - 1
                if (needLeft) {
                    rail.scrollBy(x - firstCenter, 0)
                } else if (needRight) {
                    rail.scrollBy(x - lastCenter, 0)
                } else {
                    val target = nearestCard(rail, x) ?: first
                    if (target.requestFocus()) {
                        keepRowBelowBar(position)
                        return true
                    }
                }
            }
            // The rail is laying out, or has just been scrolled toward the viewer's column.
        } else if (attached && holder is HeroHolder) {
            if (holder.surface.primaryControl.requestFocus()) return true
        } else {
            // The row is not attached yet. Move the viewport by one row in the direction of travel;
            // top-aligning it made every UP hop look like a jump to another section.
            list.scrollBy(0, direction * rowHeight)
        }
        if (tries < 8) list.postDelayed(
            { focusShelfAt(position, x, direction, rowHeight, tries + 1) }, 45L
        )
        return true
    }

    /** A tile's own gap, used as the tolerance before scrolling a rail to reach a column. */
    private fun themeGapPx(): Int = app.theme.px(20f)

    /**
     * A row scrolled to the very top puts its heading under the floating pill and wordmark. Nudge the
     * list only when that actually happened; rows already clear of the bar are left where they are.
     */
    private fun keepRowBelowBar(position: Int) {
        // After the focus request has had its own layout pass: scrolling in the same frame was
        // undone by the RecyclerView bringing the card into view.
        list.postDelayed({
            val holder = list.findViewHolderForAdapterPosition(position) ?: return@postDelayed
            val bar = app.theme.px(96f)
            val top = holder.itemView.top
            if (top < bar) list.scrollBy(0, top - bar)
        }, 70L)
    }

    private fun nearestCard(rail: RecyclerView, x: Int): View? {
        var best: View? = null
        var bestDistance = Int.MAX_VALUE
        for (i in 0 until rail.childCount) {
            val card = rail.getChildAt(i) ?: continue
            if (!card.isFocusable || card.visibility != View.VISIBLE) continue
            val distance = kotlin.math.abs(screenCenterX(card) - x)
            if (distance < bestDistance) {
                bestDistance = distance
                best = card
            }
        }
        return best
    }

    private fun isInside(container: View?, v: View?): Boolean {
        if (container == null || v == null) return false
        if (container === v) return true
        var p: android.view.ViewParent? = v.parent
        while (p != null) {
            if (p === container) return true
            p = p.parent
        }
        return false
    }

    private fun onTopRow(f: View?): Boolean {
        if (f == null || f === navAnchor) return false
        val adapter = browseAdapter ?: return false
        if (isInside(adapter.heroRoot, f)) return true
        return isInside(adapter.heroSelector, f)
    }

    /**
     * The left rail: every destination the top pill carries, plus the profile gate, in one vertical
     * list that floats over the page. A viewer halfway down Home can change screens without a trip
     * back to the top; the rail closes onto the exact tile they left.
     */
    private fun buildLeftRail(shell: FrameLayout) {
        val nav = barCaps ?: effectiveCaps()
        val entries = ArrayList<LeftRail.Entry>()
        entries += LeftRail.Entry("Switch profile", R.drawable.ic_rail_profile, "profile") {
            startActivity(Intent(this, ProfilesActivity::class.java))
        }
        entries += LeftRail.Entry("Search", R.drawable.ic_nav_search, "search") {
            startActivity(Intent(this, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_PROFILE, profileId))
        }
        entries += LeftRail.Entry("Home", R.drawable.ic_rail_home, "home") { goTab("") }
        entries += LeftRail.Entry("Movies", R.drawable.ic_rail_movies, "movies") { goTab("movies") }
        entries += LeftRail.Entry("Series", R.drawable.ic_rail_series, "series") { goTab("series") }
        if (nav.games) {
            entries += LeftRail.Entry("Games", R.drawable.ic_rail_games, "games") {
                startActivity(Intent(this, GamesActivity::class.java).putExtra(GamesActivity.EXTRA_PROFILE, profileId))
            }
        }
        if (nav.live) {
            entries += LeftRail.Entry("Live TV", R.drawable.ic_rail_live, "live") {
                startActivity(Intent(this, LiveChannelsActivity::class.java).putExtra(LiveChannelsActivity.EXTRA_PROFILE, profileId))
            }
        }
        entries += LeftRail.Entry("Settings", R.drawable.ic_nav_settings, "settings") {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PROFILE, profileId))
        }
        val rail = LeftRail(this, app, entries)
        leftRail = rail
        shell.addView(rail, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private fun shouldOpenRail(focus: View?): Boolean {
        if (focus == null || leftRail == null) return false
        val adapter = browseAdapter
        if (adapter != null && isInside(adapter.heroRoot, focus)) {
            // A multi-slide Apple billboard owns LEFT for its carousel. Every other hero — the
            // Glass cards, the Kids stage — only hands LEFT to the rail from the first card of a
            // row: LEFT on the second card is a step back to the first, not a jump to the menu.
            if (slides.size > 1 && app.theme.homeLayout.hero == HomeLayoutSpec.Hero.SHARED_SLIDER) return false
            return firstInItsRow(focus)
        }
        if (pillGroup != null && isInside(pillGroup, focus)) {
            val first = pillStops.firstOrNull() ?: return false
            return isInside(first, focus) || first === focus
        }
        var cur: View? = focus
        while (cur != null) {
            val parent = cur.parent
            if (parent is RecyclerView && parent !== list) {
                // Only the first tile of a rail hands LEFT to the navigation rail. A tile at
                // position 1 must still be reachable from position 2, even when the rail cannot
                // scroll further; "cannot scroll left" was too eager and skipped that step.
                val position = parent.getChildAdapterPosition(cur)
                return position <= 0
            }
            cur = parent as? View
        }
        return false
    }

    /** True when no focusable sibling sits to the left in the same row. */
    private fun firstInItsRow(v: View): Boolean {
        val parent = v.parent as? ViewGroup ?: return true
        for (i in 0 until parent.indexOfChild(v)) {
            val c = parent.getChildAt(i)
            if (c.isFocusable && c.visibility == View.VISIBLE) return false
        }
        return true
    }

    override fun onBackPressed() {
        // A hold sheet owns the page while it is up: Back closes the sheet, it does not leave Home.
        if (CardActionSheet.isOpen()) {
            CardActionSheet.close()
            return
        }
        if (leftRail?.isOpen == true) {
            leftRail?.close()
            return
        }
        super.onBackPressed()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val next = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
        if (next.isNotBlank()) profileId = next
        // A pill tab switch lands back here with a different tab. Same activity, same pill — the
        // page rebuilds around the new section and the pill shows where you now stand.
        if (intent?.getStringExtra(EXTRA_TAB).orEmpty() != section) recreate() else load()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val sc = application as ScenixApp
        app = sc.ctx
        profileId = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
        section = intent?.getStringExtra(EXTRA_TAB).orEmpty()
        if (profileId.isBlank()) {
            // Never guess who is watching. The native profile gate is the one place a PIN is checked.
            startActivity(Intent(this, ProfilesActivity::class.java))
            finish()
            return
        }
        // The bar is painted from the last answer about optional add-ons; load() asks the hub again
        // and rebuilds only if the household changed its add-ons since that answer.
        caps = app.prefs.addonCaps()?.let { FeatureCaps(it.first, it.second) }
        barCaps = effectiveCaps()

        // An immersive scene uses a shared room behind the page. A PS5-style hub owns its own
        // backdrop and compact utility strip instead, so it deliberately stays out of this host
        // stage and does not inherit an Apple/Netflix application bar.
        val stageMode = app.theme.packString("stage", "off") == "focus"
        val ownsHomeNavigation = app.theme.homeLayout.navigation in setOf(
            HomeLayoutSpec.Navigation.UTILITY,
            HomeLayoutSpec.Navigation.NONE,
        )
        // The Clean and Kids compositions sit their blocks directly on the wallpaper, so the
        // wall is the whole page's background — fixed behind the rails while they scroll —
        // not a hero-slot backdrop with a seam where the hero ends.
        val fullWall: android.graphics.drawable.Drawable? = when (app.theme.homeLayout.hero) {
            HomeLayoutSpec.Hero.DUAL_CARDS -> GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(app.theme.color("wallA", "#8FA5B8"), app.theme.color("wallB", "#54687C")),
            )
            // One shared wash component — the same drawable Home, Detail and Settings paint.
            HomeLayoutSpec.Hero.GLASS_CARDS -> GlassKit.wall(app)
            HomeLayoutSpec.Hero.KIDS_STAGE -> {
                // The reference is not flat sunshine: the page edge keeps a saturated yellow rim,
                // while the whole content sits on one huge lighter-yellow sheet with soft corners.
                val ground = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(app.theme.color("stageYellow", "#FFC93A"), app.theme.color("stageDeep", "#F4B81E")),
                )
                val sheet = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = app.theme.px(56f).toFloat()
                    setColor(app.theme.color("stageSheet", "#FFF3D0"))
                }
                android.graphics.drawable.LayerDrawable(arrayOf(ground, sheet)).apply {
                    setLayerInset(1, app.theme.px(36f), app.theme.px(18f), app.theme.px(36f), 0)
                }
            }
            else -> null
        }
        val content: ViewGroup = if (stageMode) FrameLayout(this) else LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(app.theme.bg)
        }
        fullWall?.let { content.background = it }
        val root: View = if (stageMode) {
            val frame = FrameLayout(this).apply { setBackgroundColor(app.theme.bg) }
            val s = Stage()
            stage = s
            frame.addView(s.frame, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            frame.addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            frame
        } else content

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@BrowseActivity)
            setHasFixedSize(true)
            setItemViewCacheSize(24)
            setBackgroundColor(if (stageMode || fullWall != null) Color.TRANSPARENT else app.theme.bg)
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            // A surface that owns its own top controls (the console hub) begins at the display
            // edge, exactly like a stage. Other packs reserve their host bar.
            setPadding(0, if (stageMode || ownsHomeNavigation) 0 else app.theme.px(16f), 0, app.theme.px(64f))
        }
        // A declared id, not generateViewId(): the latter is a process-wide counter, so a recreated
        // activity gives the shelf a different number and the old RecyclerView state lands on an
        // unrelated view ("Wrong state class"), killing the app on a tab switch.
        shelfListId = R.id.shelf_list
        list.id = shelfListId
        contentRoot = content
        status = Views.text(app, "sub", 24f, app.theme.ink2, "", gravity = Gravity.CENTER)
        if (stageMode) {
            // Apple's scene page has no layout row for the nav: the hero fills the screen from the
            // top edge and the bar floats over its art — a stacked page would cut the banner off
            // where the bar used to end.
            content.addView(list, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            content.addView(topBar(true), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(96f)
            ))
            content.addView(status, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(80f)
            ).apply { gravity = Gravity.BOTTOM })
        } else if (ownsHomeNavigation) {
            // Ps5HomeLayout contains its own compact context and utility controls. Rendering a
            // host topBar here was the source of the incorrect Apple-like navbar in that theme.
            content.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            content.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(80f)))
        } else {
            content.addView(topBar(false), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(96f)
            ))
            content.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            content.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(80f)))
        }
        // Every pack draws its own page; the rail is a host overlay so no theme has to leave room
        // for it. A FrameLayout shell works for both the stage composition and the stacked one.
        val shell = FrameLayout(this).apply { setBackgroundColor(app.theme.bg) }
        shellView = shell
        shell.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        buildLeftRail(shell)
        setContentView(shell)
        Motion.enter(app.theme, root)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updateBrandFade()
            }
        })
        // A focus change without a scroll — UP from the first row lands on the bar itself — must
        // bring the bar back too, or the ring sits on an invisible stop.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> updateBrandFade() }
        load()
        maybeCheckUpdate()
    }

    /**
     * Auto-update, quietly: with the Settings switch on, the shell asks the project's GitHub
     * releases at most once a day. Finding a build is the app's job; installing it is always the
     * person's — the dialog offers, Android's installer confirms. (core/Updates.kt)
     */
    private fun maybeCheckUpdate() {
        val local = (application as ScenixApp).prefs
        if (!local.autoUpdate()) return
        val now = System.currentTimeMillis()
        if (now - local.lastUpdateCheck() < 20L * 3600_000L) return
        local.setLastUpdateCheck(now)
        Updates.check(this) { rel, _ ->
            if (rel == null || isFinishing || isDestroyed) return@check
            runOnUiThread { Updates.offer(this, rel) }
        }
    }

    /**
     * One intentionally soft backdrop for a pack that opts into `stage: focus`. It follows the
     * Hero's preview selectors while ordinary shelf focus leaves it quiet. Two ImageViews are
     * enough for a crossfade; `front` is whichever one is currently opaque.
     */
    private inner class Stage {
        val frame = FrameLayout(this@BrowseActivity)
        private val a = ImageView(this@BrowseActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        private val b = ImageView(this@BrowseActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        private val veil = View(this@BrowseActivity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                // The artwork is atmosphere, not the reading surface. A pack owns this three-stop
                // veil so a blue console scene remains blue instead of inheriting a black Apple
                // wash, while the lower rails retain a stable, high-contrast reading field.
                intArrayOf(
                    app.theme.color("stageVeilTop", "rgba(0,0,0,0.43)"),
                    app.theme.color("stageVeilMid", "rgba(0,0,0,0.75)"),
                    app.theme.color("stageVeilBottom", "rgba(0,0,0,0.96)"),
                )
            )
            alpha = 0.96f
        }
        private var front = a
        private var current: String? = null
        private var roaming = false

        init {
            frame.addView(a, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            frame.addView(b, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            frame.addView(veil, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }

        fun show(url: String?) {
            val clean = url?.trim().orEmpty()
            if (clean.isEmpty() || clean == current) return
            current = clean
            val incoming = if (front === a) b else a
            incoming.animate().cancel()
            incoming.alpha = 0f
            // The stage is intentionally a low-resolution, RGB_565 atmosphere image. Upscaling
            // this small sample behind the dark veil gives the background a soft blur while
            // keeping a second full-HD decode out of every hero transition.
            BrowseImages.load(incoming, clean, app.theme.px(360f).coerceIn(240, 420)) { ok ->
                if (isFinishing || isDestroyed) return@load
                if (!ok || current != clean) return@load
                incoming.animate().alpha(1f).setDuration(320).withEndAction {
                    front.alpha = 0f
                    front = incoming
                }.start()
            }
        }

        fun setRoaming(roam: Boolean) {
            if (roaming == roam) return
            roaming = roam
            // The room dims while the viewer walks the shelves. The visual softness comes from the
            // pre-sampled stage bitmap, not a live RenderEffect blur; that avoids re-rendering the
            // full 1080p window on every D-pad hop.
            veil.animate().cancel()
            veil.alpha = if (roam) 1f else 0.96f
        }
    }

    /** Every mounted card in every visible rail, for the stage's "everything but the ring dims" pass. */
    private fun eachCard(block: (View) -> Unit) {
        for (i in 0 until list.childCount) {
            val shelf = list.getChildAt(i)
            if (shelf !is ViewGroup) continue
            for (j in 0 until shelf.childCount) {
                val rail = shelf.getChildAt(j)
                if (rail is RecyclerView) {
                    for (k in 0 until rail.childCount) {
                    val card = rail.getChildAt(k)
                    // A category plate is already a wash; dimming it to 58% lets the stage show
                    // through and reads as a poster behind the text. Keep plates opaque.
                    if (card.tag == "genre") {
                        if (card.alpha != 1f) card.alpha = 1f
                    } else block(card)
                }
                }
            }
        }
    }

    private fun onCardFocused(card: View, item: Item) {
        if (roamingCard === card) return
        roamingCard = card
        // The billboard is a header, not a carousel running under the viewer's hands. Once a shelf
        // tile has focus, the rotation timer must not rebind the featured row or re-render the
        // stage while the viewer is walking the rows.
        rotationPaused = true
        // A focus-stage pack keeps one sampled room image behind its Hero rather than waking a
        // trailer decoder on every remote stop. This is structural, not an Apple-only exception:
        // PS5 and any future immersive pack opt in with `stage: focus` too.
        if (stage == null) {
            clock.removeCallbacks(trailerTick)
            clock.removeCallbacks(trailerRelease)
            hideTrailerNow()
            clock.postDelayed(trailerRelease, 250L)
        }
        val s = stage ?: return
        s.setRoaming(true)
        // The selected card has its own keyline/glow. Dimming every mounted card is a costly
        // compositor churn on each D-pad press and fights a stage's deliberately blurred depth.
        eachCard { if (it.alpha != 1f) it.alpha = 1f }
    }

    /**
     * A tile of the Featured row holds focus: the hero above it follows to that slide, and the room
     * stays lit — walking the hero's own section is browsing the hero, not roaming the shelves.
     */
    private fun onSlideFocused(pos: Int) {
        roamingCard = null
        rotationPaused = true
        // Focus must not re-position the vertical list. The former snap-to-top made DOWN from
        // Play focus a Featured tile that was cut off below the viewport. The hero and its preview
        // rail remain in the viewer's current composition; only their title-scoped state changes.
        setHero(pos)
        scheduleTrailer()
        val s = stage ?: return
        s.setRoaming(false)
        eachCard { if (it.alpha != 1f) it.alpha = 1f }
    }

    /**
     * LEFT/RIGHT on the billboard's Play button walks the carousel. The hero is its own focus stop,
     * so without this the remote asks the framework for a neighbour beside the button, finds none,
     * and the slide show appears frozen.
     */
    private fun cycleHero(delta: Int) {
        if (slides.size < 2) return
        rotationPaused = true
        lastKeyAt = android.os.SystemClock.elapsedRealtime()
        setHero((heroPos + delta + slides.size) % slides.size)
    }

    private fun setHero(pos: Int) {
        if (slides.isEmpty()) return
        val next = pos.coerceIn(0, slides.size - 1)
        val item = slides[next]
        // Re-focusing the tile already on the billboard must not tear the trailer down and start it
        // again; that loop is a visible stutter every time the ring lands on the same slide.
        if (next == heroPos && heroItem === item) return
        heroPos = next
        heroItem = item
        browseAdapter?.showSlide(heroPos)
        if (stage != null) {
            // The soft stage, hero copy, and one active preview tile change as one slide. Stage
            // images are sampled at 360px off the main thread, so this remains cheap for Apple,
            // PS5 and every future stage-driven pack.
            stage?.show(item.backdrop ?: item.poster ?: item.logo)
        } else {
            clock.removeCallbacks(trailerTick)
            clock.removeCallbacks(trailerRelease)
            hideTrailerNow()
            clock.postDelayed(trailerRelease, 250L)
        }
    }

    private val trailerTick = Runnable { loadTrailer() }
    /** A preview starts a beat after attention settles on a slide, not mid-step. */
    private fun scheduleTrailer() {
        clock.removeCallbacks(trailerTick)
        // A focus-stage pack is deliberately artwork-led. A sampled background establishes depth
        // at a fraction of the decoder/compositor cost of a looping preview, so its carousel stays
        // responsive across Apple, PS5 and future stage-driven packs.
        if (stage != null) return
        if (app.theme.token("preview", "enabled", 1.0) <= 0.0) return
        val delay = app.theme.token("preview", "delayMs", 1500.0).toLong().coerceIn(300L, 5000L)
        clock.postDelayed(trailerTick, delay)
    }

    /** The cheap half of stopping a preview: take the surface and its subtitles out of the frame. */
    private fun hideTrailerNow() {
        browseAdapter?.heroVideo()?.let {
            it.animate().cancel()
            it.alpha = 0f
            it.visibility = View.GONE
        }
        browseAdapter?.heroSubs()?.let {
            it.text = ""
            it.visibility = View.GONE
        }
    }

    /** The expensive half: stopPlayback tears the decoder down on this thread. */
    private val trailerRelease = Runnable {
        browseAdapter?.heroVideo()?.let { runCatching { it.stopPlayback() } }
    }

    private fun stopTrailer() {
        clock.removeCallbacks(trailerRelease)
        hideTrailerNow()
        browseAdapter?.heroVideo()?.let { runCatching { it.stopPlayback() } }
    }

    private fun loadTrailer() {
        val item = slides.getOrNull(heroPos) ?: return
        api.trailer(item.identity) { url, _ ->
            if (isFinishing || isDestroyed) return@trailer
            // A lookup that answers after the billboard moved on is stale art: drop it.
            if (slides.getOrNull(heroPos) !== item) return@trailer
            val video = browseAdapter?.heroVideo() ?: return@trailer
            val target = url?.takeIf { it.isNotBlank() } ?: return@trailer
            video.setOnPreparedListener { mp ->
                mp.isLooping = true
                mp.setVolume(0f, 0f)
                mp.setOnTimedTextListener { _, timed ->
                    val line = timed?.text
                    browseAdapter?.heroSubs()?.let { s ->
                        s.text = line ?: ""
                        s.visibility = if (line.isNullOrBlank()) View.GONE else View.VISIBLE
                    }
                }
                // VideoView keeps its size callback to itself, but the raw player is here: the
                // cover view learns the clip's shape from the player and measures itself to fill.
                if (video is HeroCoverVideoView) {
                    mp.setOnVideoSizeChangedListener { _, w, h -> video.coverSize(w, h) }
                    if (mp.videoWidth > 0) video.coverSize(mp.videoWidth, mp.videoHeight)
                }
                mp.start()
                video.animate().alpha(1f).setDuration(500).start()
            }
            video.setOnErrorListener { _, _, _ ->
                if (!isFinishing && !isDestroyed) video.visibility = View.GONE
                true
            }
            // A GONE VideoView has no surface, so it would never prepare. It goes up transparent
            // with the URI set, and the fade to opaque happens on the first real frame — a black
            // box over the backdrop is worse than a beat of nothing. If nothing ever prepared,
            // take it back down so the billboard is not parked on an empty slot forever.
            video.alpha = 0f
            video.visibility = View.VISIBLE
            video.setVideoURI(Uri.parse(api.absolute(target)))
            video.postDelayed({
                if (video.alpha == 0f) {
                    runCatching { video.stopPlayback() }
                    video.visibility = View.GONE
                }
            }, 8000L)
        }
    }

    private fun onChromeFocused() {
        roamingCard = null
        rotationPaused = true
        val s = stage ?: return
        s.setRoaming(false)
        eachCard { if (it.alpha != 1f) it.alpha = 1f }
    }

    private fun topBar(transparent: Boolean = false): View {
        val nav = TopNav(
            this, app, profileId, section, transparent, shelfListId,
            showsGames = (barCaps ?: effectiveCaps()).games,
            showsLive = (barCaps ?: effectiveCaps()).live,
            profileView = profileDisc(),
            callbacks = object : TopNav.Callbacks {
                override fun goTab(route: String) = this@BrowseActivity.goTab(route)
                override fun onGames() {
                    startActivity(Intent(this@BrowseActivity, GamesActivity::class.java)
                        .putExtra(GamesActivity.EXTRA_PROFILE, profileId))
                }
                override fun onLive() {
                    startActivity(Intent(this@BrowseActivity, LiveChannelsActivity::class.java)
                        .putExtra(LiveChannelsActivity.EXTRA_PROFILE, profileId))
                }
                override fun onSearch() {
                    startActivity(Intent(this@BrowseActivity, SearchActivity::class.java)
                        .putExtra(SearchActivity.EXTRA_PROFILE, profileId))
                }
                override fun onSettings() {
                    startActivity(Intent(this@BrowseActivity, SettingsActivity::class.java)
                        .putExtra(SettingsActivity.EXTRA_PROFILE, profileId))
                }
                override fun onProfile() {
                    startActivity(Intent(this@BrowseActivity, ProfilesActivity::class.java)
                        .putExtra(ProfilesActivity.EXTRA_FROM_BROWSE, true))
                }
                override fun onChromeFocused() = this@BrowseActivity.onChromeFocused()
            },
        )
        navAnchor = nav.anchor
        pillGroup = nav.group
        pillStops = nav.stops
        liveNav = nav.liveStop
        if (transparent) chromeBar = nav
        return nav
    }

    private fun profileDisc(): View {
        val theme = app.theme
        val size = theme.px(36f)
        val disc = FrameLayout(this).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, size.toFloat())
                }
            }
            background = Views.rounded(theme.surface2, size / 2, theme.px(1f), theme.line)
        }
        val face = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        disc.addView(face, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size))
        val ini = Views.text(app, "li", 16f, theme.ink2, "?", bold = true, gravity = Gravity.CENTER)
        disc.addView(ini, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })
        disc.isFocusable = true
        disc.isClickable = true
        disc.setOnClickListener {
            startActivity(
                Intent(this@BrowseActivity, ProfilesActivity::class.java)
                    .putExtra(ProfilesActivity.EXTRA_FROM_BROWSE, true)
            )
        }
        Focus.attach(disc, disc, app, size / 2, scale = false, elevate = false,
            onFocus = { if (it) onChromeFocused() })
        disc.layoutParams = LinearLayout.LayoutParams(size, size).apply {
            marginEnd = theme.px(8f)
        }
        api.profiles { people, _ ->
            if (isFinishing || isDestroyed) return@profiles
            val me = people?.firstOrNull { it.id == profileId } ?: return@profiles
            ini.text = me.name.trim().take(1).uppercase()
            if (me.avatar.isNotBlank()) {
                BrowseImages.load(face, api.absolute(me.avatar), size) { ok ->
                    if (!isFinishing && !isDestroyed) ini.visibility = if (ok) View.GONE else View.VISIBLE
                }
            }
        }
        return disc
    }

    /**
     * What the navigation may offer: the hub's last answer about optional add-ons, or — until a hub
     * answers once — the legacy signals (a live row means live TV; Games keeps its old behaviour).
     */
    private fun effectiveCaps(): FeatureCaps =
        caps ?: FeatureCaps(live = liveSeen, games = true)

    private fun load() {
        showSkeleton(true)
        // The two optional modes are doors to installed add-ons, so ask what this household has.
        // This runs beside the rows, not in front of them: a tiny answer must never hold the page,
        // and an unreachable hub must not wait out this request before showing its own error. The
        // bar was painted from the last known answer; if the household changed its add-ons since,
        // one rebuild gives the page the doors that exist now.
        api.caps { answer ->
            if (isFinishing || isDestroyed) return@caps
            if (answer != null) {
                val rendered = barCaps
                caps = answer
                app.prefs.setAddonCaps(answer.live, answer.games)
                if (rendered != null && rendered != answer) recreate()
            }
        }
        loadHome()
    }

    private fun loadHome() {
        if (section.isNotBlank()) {
            api.row(profileId, section) { _, items, error ->
                if (isFinishing || isDestroyed) return@row
                if (items.isNullOrEmpty()) {
                    showSkeleton(false)
                    if (isGlassPack()) {
                        status.visibility = View.GONE
                        showEmpty("Nothing in this section yet",
                            error ?: "The hub may still be warming its shelves. Try again in a moment.")
                    } else {
                        status.visibility = View.VISIBLE
                        status.text = error ?: "Nothing in this section yet"
                    }
                    return@row
                }
                // A hub need not carry generic movie/series rows for every profile — a kid
                // profile answers "row not for this profile" and keeps only kids shelves.
                // Fall back to the profile's own shelves filtered to this section, instead of
                // printing the hub's raw error sentence on the page.
                api.load(profileId) { rows, _ ->
                    if (isFinishing || isDestroyed) return@load
                    val pool = rows.orEmpty()
                        .filter { it.id != "hero" && it.card != "channel" }
                        .flatMap { it.items }
                        .filter { it.type == if (section == "series") "series" else "movie" }
                    if (pool.isEmpty()) {
                        showSkeleton(false)
                        status.visibility = View.VISIBLE
                        status.text = error ?: "Nothing in this section yet"
                        return@load
                    }
                    loaded = true
                    present(deriveShelves(section, pool))
                }
            }
            return
        }
        api.load(profileId) { rows, error ->
            if (isFinishing || isDestroyed) return@load
            if (rows == null) {
                loaded = false
                showSkeleton(false)
                if (isGlassPack()) {
                    status.visibility = View.GONE
                    showEmpty("The hub is not answering",
                        error ?: "Check that the hub is awake, then try again.")
                } else {
                    status.visibility = View.VISIBLE
                    status.text = error ?: "The hub is not answering"
                }
                return@load
            }
            loaded = true
            // A hub that cannot answer /app/v1/caps still earns the Live door from its own live row;
            // a hub that answered has already decided the bar's shape. Once known, the tab never
            // withdraws: a hub mid re-warm briefly answers with stale empty rows, and a tab that
            // keeps changing shape under the thumb is worse than one that arrives late.
            if (rows.any { it.id == "live" || it.card == "channel" }) liveSeen = true
            if (caps == null && liveSeen) {
                liveNav?.visibility = View.VISIBLE
                wirePillFocus()
            }
            // Live has a whole tab and a wall of its own; the Home shelves are for films and shows,
            // so the old live strip is no longer parked on the front page.
            val others = rows.filter {
                it.id != "hero" && it.items.isNotEmpty() && it.id != "live" && it.card != "channel"
            }
            // The hub sometimes sends the hero as its own row and sometimes folds the featured
            // titles into the first shelf. Either way Home opens on a hero — the viewer's first
            // sight on this set is the featured title, never a bare shelf head. Its preview tiles
            // now live *inside* the HeroComponent, so the data source does not become a duplicate
            // Featured shelf below it.
            val maxSlides = app.theme.token("hero", "maxItems", 5.0).toInt().coerceAtLeast(1)
            // A slide with no artwork at all still gets a tile, but its selector square is an empty
            // dark hole — the blank tiles in the Featured strip. Only titles with real art become
            // slides; a hub row of artless items keeps its shelf but never becomes the hero.
            fun pickSlides(items: List<Item>) =
                items.filter { !it.backdrop.isNullOrBlank() || !it.poster.isNullOrBlank() }.take(maxSlides)
            val heroRow = rows.firstOrNull { it.id == "hero" }
            val shelves: List<BrowseRow>
            if (heroRow != null && heroRow.items.isNotEmpty()) {
                slides = pickSlides(heroRow.items)
                shelves = others
            } else {
                val first = others.firstOrNull()
                if (first == null) {
                    slides = emptyList()
                    shelves = others
                } else {
                    slides = pickSlides(first.items)
                    // If the first shelf supplied the hero, do not draw that same row again under
                    // it. The hero owns its compact previews; subsequent rows remain browse rows.
                    shelves = others.drop(1)
                }
            }
            // The console-style rail IS the home screen: two lonely tiles read as a broken strip,
            // while the reference's launch rail spans the screen. When the hub's featured row is
            // thin, the rail borrows the next titles the household actually has — real items, no
            // invention — and the shelf below still shows them in full. The dual-card pack needs
            // the same guarantee: two distinct titles, never one duplicated across both cards.
            if (app.theme.homeLayout.hero in setOf(HomeLayoutSpec.Hero.FOCUSED_HUB,
                    HomeLayoutSpec.Hero.DUAL_CARDS,
                    HomeLayoutSpec.Hero.GLASS_CARDS,
                    HomeLayoutSpec.Hero.KIDS_STAGE) &&
                slides.size in 1 until maxSlides) {
                val seen = slides.mapTo(HashSet()) { it.identity.ifBlank { it.title } }
                for (row in others) {
                    if (slides.size >= maxSlides) break
                    slides = slides + row.items.filter {
                        (!it.backdrop.isNullOrBlank() || !it.poster.isNullOrBlank()) &&
                            seen.add(it.identity.ifBlank { it.title })
                    }.take(maxSlides - slides.size)
                }
            }
            present(shelves)
        }
    }

    /** Rebuild the ordered D-pad path after an optional pill action (such as Live TV) appears. */
    private fun wirePillFocus() {
        val stops = pillStops.filter { it.visibility == View.VISIBLE }
        if (stops.isEmpty()) return
        for (index in stops.indices) {
            stops[index].nextFocusLeftId = stops[(index - 1).coerceAtLeast(0)].id
            stops[index].nextFocusRightId = stops[(index + 1).coerceAtMost(stops.lastIndex)].id
        }
    }

    /**
     * A section arrives from the hub as one flat list, exactly as the web browse page finds it.
     * The shelves are the hub's own tags read back — newest, best-matched, best-rated, the genres
     * this section holds most, and the languages its titles actually carry. Nothing is invented:
     * every rail is titles the hub delivered, ordered by fields each item already sends.
     */
    private fun deriveShelves(kind: String, raw: List<Item>): List<BrowseRow> {
        val label = if (kind == "series") "Series" else "Movies"
        val byId = LinkedHashMap<String, Item>()
        for (it in raw) if (it.identity.isNotBlank() && !byId.containsKey(it.identity)) byId[it.identity] = it
        val items = ArrayList(byId.values)
        val maxSlides = app.theme.token("hero", "maxItems", 5.0).toInt().coerceAtLeast(1)
        slides = items.filter { !it.backdrop.isNullOrBlank() }
            .sortedByDescending { it.match + if (it.isNew) 20 else 0 }
            .take(maxSlides)
        val out = ArrayList<BrowseRow>()
        // HeroComponent owns the selected title's previews. A top-level Featured row would make
        // the same items look like a second browse shelf and would give them a conflicting OK
        // action, so it is deliberately not added to [out].
        // A rail shows eighteen tiles; `total` says what the shelf really holds, so See all appears
        // exactly when titles are waiting off-rail — and opens them.
        fun add(id: String, title: String, pool: List<Item>) {
            if (pool.size < 6) return
            out.add(BrowseRow(id, title, "poster", pool.take(18), pool.size))
        }
        add("trending", "Trending now", items.sortedByDescending { it.match })
        val news = items.filter { it.isNew }.ifEmpty { items }.sortedByDescending { it.addedAt }
        add("recent", "New on $label", news)
        add("top-rated", "Top rated", items.filter { it.rating > 0 }.sortedByDescending { it.rating })
        // The genres this section has the most depth in.
        val counts = LinkedHashMap<String, Int>()
        for (it in items) for (g in it.genres) if (g.isNotBlank()) counts[g] = (counts[g] ?: 0) + 1
        for ((g, n) in counts.entries.sortedByDescending { it.value }.take(6)) {
            if (n >= 8) add("genre:$g", g, items.filter { it.genres.contains(g) }.sortedByDescending { it.match })
        }
        // Indian households browse by language; the shelves come straight off each item's own tags.
        for (lang in listOf("Hindi", "Telugu", "Tamil", "Malayalam", "Kannada", "Bengali", "Marathi")) {
            val pool = items.filter { it.languages.any { l -> l.contains(lang, ignoreCase = true) } }
            if (pool.size >= 8) add("lang:$lang", "Top $lang $label", pool.sortedByDescending { it.rating * 10 + it.match / 10 })
        }
        // A kid section holds a handful of titles, and every gate above reads a rail of four as too
        // thin to print — the tab then showed a hero over an empty page. When nothing else passes,
        // the section prints itself whole rather than showing a blank below the hero (owner round 7).
        if (out.isEmpty() && items.isNotEmpty()) {
            out.add(BrowseRow("all", "All $label", "poster", items.take(18), items.size))
        }
        return out
    }

    /** Put a finished set of shelves on screen: hero, stage, rails, rotation and first focus. */
    /**
     * What a fresh standalone install sees: this TV has no hub, no add-ons and therefore no titles.
     * The screen says that in one sentence and gives the only two ways forward, instead of a black
     * page behind a nav bar (which is what standalone used to be).
     */
    private fun showEmptyState() {
        showSkeleton(false)
        status.visibility = View.GONE
        val shell = shellView ?: return
        val standalone = app.prefs.backendChoice() == Prefs.BACKEND_STANDALONE
        val panel = emptyPanel ?: buildEmptyPanel().also {
            emptyPanel = it
            shell.addView(it, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val title = panel.getChildAt(0) as? TextView
        val body = panel.getChildAt(1) as? TextView
        title?.text = if (standalone) "This TV is running on its own"
        else "This hub has no titles to show"
        body?.text = if (standalone) {
            // A fresh standalone install now ships with the same keyless default sources a fresh hub
            // seeds, so this card is only what's left when even those cannot answer — the wording
            // should not lecture about setup the install already did.
            "Scenix is the backend on this TV. If its shelves are still empty, the add-on list or the " +
                "network needs a look — Settings → Add-ons, or open " +
                "${com.scenix.tv.core.LocalServer.webBase(this).ifBlank { "this TV's address" }} " +
                "in a browser on your phone and type there."
        }
        else
            "The hub is reachable, but its shelves are empty. Add an add-on to the hub in Settings, or " +
                "check that its library has finished its first scan — or open its address on a " +
                "computer, where typing is not done with a remote."
        panel.visibility = View.VISIBLE
        panel.getChildAt(2)?.post { panel.getChildAt(2)?.requestFocus() }
    }

    private fun buildEmptyPanel(): LinearLayout {
        val theme = app.theme
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(theme.bg)
            setPadding(theme.px(80f), 0, theme.px(80f), theme.px(60f))
            visibility = View.GONE
        }
        panel.addView(Views.text(app, "h1", 40f, theme.ink, "", bold = true,
            gravity = Gravity.CENTER))
        panel.addView(Views.text(app, "sub", 22f, theme.ink2, "", gravity = Gravity.CENTER,
            maxLines = 3), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = theme.px(18f) })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun action(label: String, primary: Boolean, click: () -> Unit): View {
            val row = Views.text(app, "button", 21f,
                if (primary) theme.onBrand else theme.ink, label, bold = true,
                gravity = Gravity.CENTER)
            row.setPadding(theme.px(26f), theme.px(13f), theme.px(26f), theme.px(13f))
            row.background = if (primary) Views.rounded(theme.brand, theme.radius("button", 6f))
            else Views.rounded(theme.surface, theme.radius("button", 6f), theme.px(1f), theme.line)
            row.isFocusable = true
            row.isClickable = true
            row.contentDescription = label
            Focus.attach(row, row, app, theme.radius("button", 6f), scale = false, elevate = false,
                onClick = click)
            return row
        }
        actions.addView(action("Open Settings", primary = true) {
            startActivity(Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_PROFILE, profileId))
        })
        actions.addView(action("Connect a hub", primary = false) {
            startActivity(Intent(this, MainActivity::class.java).putExtra("setup", true))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = theme.px(20f) })
        panel.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = theme.px(30f) })
        return panel
    }

    private fun present(shelves: List<BrowseRow>) {
        // A reachable backend with nothing in it is not a black page. Standalone with no add-ons
        // is the common case for a first install, so it explains itself and offers the one useful
        // action instead of leaving the viewer on an unexplained empty Home.
        if (slides.isEmpty() && shelves.isEmpty()) {
            showEmptyState()
            return
        }
        emptyPanel?.visibility = View.GONE
        showSkeleton(false)
        status.visibility = View.GONE
        hideEmpty()
        heroPos = 0
        heroItem = slides.firstOrNull()
        stage?.show(heroItem?.backdrop ?: heroItem?.poster
            ?: shelves.firstOrNull()?.items?.firstOrNull()?.backdrop)
        val adapter = BrowseAdapter(heroItem, shelves)
        browseAdapter = adapter
        list.adapter = adapter
        list.post { updateBrandFade() }
        list.postDelayed({ updateBrandFade() }, 600)
        // The billboard follows its own embedded preview tiles. It does not run by itself: a timer
        // that rotated while the viewer walked the shelves re-rendered the stage mid-step, which
        // read as lag and fought focus.
        clock.removeCallbacks(rotateHero)
        rotationPaused = true
        // Home opens on the billboard's Play button, the way a streaming service does: it is always
        // fully on screen, and LEFT/RIGHT on it walks the carousel. Landing on a Featured tile
        // instead meant that on a zoomed stage pack the rail was not laid out yet and focus was lost
        // altogether — no ring, no key handling, a "frozen" hero.
        //
        // The first attempt is not enough: the stage trailer binds a beat later and re-binds the
        // hero, which drops the ring it had just been given. Keep re-asserting focus until the
        // viewer touches the remote, and never fight a key they have already pressed.
        val homeAt = android.os.SystemClock.elapsedRealtime()
        list.postDelayed(object : Runnable {
            var tries = 0
            override fun run() {
                if (lastKeyAt > homeAt) return
                val held = currentFocus
                if (held == null || !isInside(list, held)) {
                    adapter.focusFirstSlide()
                    val landed = currentFocus
                    if (landed == null || !isInside(list, landed)) {
                        // The profile gate is a tap, so Home can open in touch mode. A TV target
                        // refuses focus there; make the landing control an exception for this one
                        // request instead of leaving Home with no ring and a frozen hero.
                        val target = adapter.heroPlay ?: firstFocusable(list)
                        if (target?.isInTouchMode == true && !target.isFocusableInTouchMode) {
                            target.isFocusableInTouchMode = true
                        }
                        target?.requestFocus()
                    }
                }
                if (++tries < 10) list.postDelayed(this, 600)            }
        }, 400)
    }

    /** Quiet placeholder rails while the hub thinks, instead of a sentence on a black page. */
    private fun showSkeleton(on: Boolean) {
        val host = contentRoot ?: return
        if (!on) {
            skeleton?.let { host.removeView(it) }
            skeleton = null
            return
        }
        if (skeleton != null) return
        val theme = app.theme
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(theme.gutter, theme.px(560f), theme.gutter, 0)
        }
        val cardW = theme.px(280f)
        val artH = theme.px(420f)
        for (r in 0 until 2) {
            box.addView(View(this).apply { background = Views.rounded(theme.surface, theme.px(6f)) },
                android.widget.LinearLayout.LayoutParams(theme.px(300f), theme.px(22f)).apply {
                    bottomMargin = theme.px(18f)
                })
            val rail = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }
            for (c in 0 until 5) rail.addView(View(this).apply {
                background = Views.rounded(theme.surface2, theme.radius("card", 6f))
            }, android.widget.LinearLayout.LayoutParams(cardW, artH).apply { marginEnd = theme.px(26f) })
            box.addView(rail, android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, artH
            ).apply { bottomMargin = theme.px(40f) })
        }
        skeleton = box
        host.addView(box, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        box.animate().alpha(0.45f).setDuration(900).withEndAction(object : Runnable {
            override fun run() {
                if (skeleton !== box || isFinishing || isDestroyed) return
                box.animate().alpha(1f).setDuration(900).withEndAction(this).start()
            }
        }).start()
    }

    override fun onDestroy() {
        clock.removeCallbacks(rotateHero)
        super.onDestroy()
    }

    private fun firstFocusable(parent: ViewGroup): View? {
        for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i)
            if (c.isFocusable && c.visibility == View.VISIBLE) return c
            if (c is ViewGroup) firstFocusable(c)?.let { return it }
        }
        return null
    }

    /**
     * True when the hero actually exposes a preview strip with a tile for every slide. The strip
     * is built only for a multi-slide hero, so a single-slide billboard must not pretend DOWN can
     * enter it.
     */
    private fun hasHeroSelector(): Boolean =
        slides.size > 1 && browseAdapter?.heroSelector?.visibility == View.VISIBLE

    /** Move focus to this structural Home surface's entry point for a slide. */
    private fun focusFeatured(pos: Int, tries: Int = 0) {
        val component = browseAdapter?.homeSurface() ?: return
        // This route is normally fully laid out. Posting it unconditionally meant a rapid DOWN,
        // DOWN from the pill saw the pill twice and dropped the second intent. Take focus in this
        // frame whenever possible; only a cold/rebound hero needs the short layout retry.
        if (component.focusEntry(pos)) return
        if (tries < 8) component.root.postDelayed({ focusFeatured(pos, tries + 1) }, 32L)
    }

    /**
     * A pill tab. Movies and Series are this same page filtered to one section — the bar stays on
     * screen and one LEFT/RIGHT walks the whole app — so the tab re-enters Home rather than opening
     * a separate grid screen.
     */
    private fun goTab(tab: String) {
        if (tab == section) {
            list.smoothScrollToPosition(0)
            return
        }
        startActivity(
            Intent(this, BrowseActivity::class.java)
                .putExtra(EXTRA_PROFILE, profileId)
                .putExtra(EXTRA_TAB, tab)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        // Section switches rebuild the page; the new one rises under the old instead of snapping.
        overridePendingTransition(R.anim.scenix_enter, R.anim.scenix_exit)
    }

    /** A full-list page for one shelf: the hub's row by id, or a search for a derived shelf's tag. */
    private fun openRowFull(row: BrowseRow) {
        val id = row.id
        // Derived section shelves (trending, new, top-rated) are orderings of the whole section —
        // their See all is the section's own full grid.
        if (section.isNotBlank() && (id == "trending" || id == "recent" || id == "top-rated")) {
            startActivity(
                Intent(this, SectionActivity::class.java)
                    .putExtra(SectionActivity.EXTRA_PROFILE, profileId)
                    .putExtra(SectionActivity.EXTRA_SECTION, section)
            )
            return
        }
        if (id.startsWith("genre:")) {
            startActivity(
                Intent(this, SectionActivity::class.java)
                    .putExtra(SectionActivity.EXTRA_PROFILE, profileId)
                    .putExtra(SectionActivity.EXTRA_GENRE, row.title)
                    .putExtra(SectionActivity.EXTRA_TITLE, row.title)
            )
            return
        }
        if (id.startsWith("lang:")) {
            startActivity(
                Intent(this, SectionActivity::class.java)
                    .putExtra(SectionActivity.EXTRA_PROFILE, profileId)
                    .putExtra(SectionActivity.EXTRA_QUERY, id.substringAfter(':'))
                    .putExtra(SectionActivity.EXTRA_TITLE, row.title)
            )
            return
        }
        startActivity(
            Intent(this, SectionActivity::class.java)
                .putExtra(SectionActivity.EXTRA_PROFILE, profileId)
                .putExtra(SectionActivity.EXTRA_SECTION, id)
        )
    }

    /** A live tile is a channel, not a title page: pressing it tunes it. */
    private fun openLive(item: Item) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_PROFILE, profileId)
                .putExtra(PlayerActivity.EXTRA_IDENTITY, item.identity)
                .putExtra(PlayerActivity.EXTRA_TYPE, "channel")
                .putExtra(PlayerActivity.EXTRA_TITLE, item.title)
                .putExtra(PlayerActivity.EXTRA_POSTER, item.poster.orEmpty())
        )
    }

    private fun openDetail(item: Item) {
        startActivity(
            Intent(this, DetailActivity::class.java)
                .putExtra(DetailActivity.EXTRA_PROFILE, profileId)
                .putExtra(DetailActivity.EXTRA_IDENTITY, item.identity)
                .putExtra(DetailActivity.EXTRA_TITLE, item.title)
        )
    }

    /**
     * The billboard's Play button. A hero is the biggest Play in the product, so it starts the film
     * (or the remembered episode of a series) instead of opening another page to press Play again.
     * The player resolves the copy itself from identity; the tile only supplies where to resume.
     */
    private fun playItem(item: Item) {
        val p = item.progress
        val series = item.type == "series"
        val season = p?.episode?.season ?: if (series) 1 else 0
        val episode = p?.episode?.episode ?: if (series) 1 else 0
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_PROFILE, profileId)
                .putExtra(PlayerActivity.EXTRA_IDENTITY, item.identity)
                .putExtra(PlayerActivity.EXTRA_TYPE, item.type)
                .putExtra(PlayerActivity.EXTRA_TITLE, item.title)
                .putExtra(PlayerActivity.EXTRA_SEASON, season)
                .putExtra(PlayerActivity.EXTRA_EPISODE, episode)
                .putExtra(PlayerActivity.EXTRA_POSITION_MS, p?.positionMs ?: 0L)
                .putExtra(PlayerActivity.EXTRA_MATURITY, item.maturity)
                .putExtra(PlayerActivity.EXTRA_POSTER, item.poster.orEmpty())
                .putExtra(PlayerActivity.EXTRA_BACKDROP, item.backdrop.orEmpty())
                .putExtra(PlayerActivity.EXTRA_LOGO, item.logo.orEmpty())
        )
    }

    private inner class BrowseAdapter(
        private val hero: Item?,
        private val shelves: List<BrowseRow>,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        var heroPlay: View? = null
            private set
        var heroRoot: View? = null
            private set
        private var heroHolder: HeroHolder? = null
        /** Some surfaces expose an integrated selector rail; Netflix deliberately does not. */
        val heroSelector: View? get() = heroHolder?.surface?.selectorRoot

        fun homeSurface(): HomeSurface? = heroHolder?.surface

        /** Each composition chooses its own entry stop: Apple preview, Netflix Play or PS5 tile. */
        fun focusFirstSlide(): Boolean = heroHolder?.surface?.focusEntry(heroPos) ?: false

        fun heroVideo(): android.widget.VideoView? = heroHolder?.surface?.videoView

        fun heroSubs(): TextView? = heroHolder?.surface?.subtitleView

        fun showSlide(pos: Int) {
            heroPos = pos.coerceIn(0, slides.size - 1)
            heroHolder?.let { renderHero(it) }
        }

        override fun getItemCount(): Int = (if (hero != null) 1 else 0) + shelves.size

        override fun getItemViewType(position: Int): Int =
            if (hero != null && position == 0) TYPE_HERO else TYPE_SHELF

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val theme = app.theme
            return if (viewType == TYPE_HERO) {
                // The typed pack contract selects a complete structural surface, not merely a
                // paint treatment.  Its callbacks are the stable Browse responsibilities shared
                // by every family: routes, the one active title index, and profile identity.
                val surface = HomeSurfaceFactory.create(
                    this@BrowseActivity,
                    app,
                    HomeSurfaceCallbacks(
                        onPlay = ::playItem,
                        onOpen = ::openDetail,
                        onFavourite = { item ->
                            // The hero answers a hold with the same sheet as a shelf card, so the
                            // confirmation is identical wherever the ring happens to be standing.
                            CardActionSheet.open(
                                this@BrowseActivity, app, item.title,
                                listOf(CardActionSheet.Choice(
                                    if (CardActions.inList(app, profileId, item))
                                        "Remove from My List" else "Add to My List",
                                ) {
                                    CardActions.toggleMyList(
                                        this@BrowseActivity, app, api, profileId, item,
                                    )
                                }),
                            )
                        },
                        onSelected = ::onSlideFocused,
                        onStep = ::stepHero,
                        onHeroFocused = ::onChromeFocused,
                        onSection = { target ->
                            if (target == "live") {
                                startActivity(
                                    Intent(this@BrowseActivity, LiveChannelsActivity::class.java)
                                        .putExtra(LiveChannelsActivity.EXTRA_PROFILE, profileId)
                                )
                            } else if (target == "games") {
                                startActivity(
                                    Intent(this@BrowseActivity, GamesActivity::class.java)
                                        .putExtra(GamesActivity.EXTRA_PROFILE, profileId)
                                )
                            } else if (target == "home") goTab("")
                            else goTab(target)
                        },
                        onProfile = {
                            startActivity(
                                Intent(this@BrowseActivity, ProfilesActivity::class.java)
                                    .putExtra(ProfilesActivity.EXTRA_FROM_BROWSE, true)
                            )
                        },
                        onSearch = {
                            startActivity(
                                Intent(this@BrowseActivity, SearchActivity::class.java)
                                    .putExtra(SearchActivity.EXTRA_PROFILE, profileId)
                            )
                        },
                        onSettings = {
                            startActivity(
                                Intent(this@BrowseActivity, SettingsActivity::class.java)
                                    .putExtra(SettingsActivity.EXTRA_PROFILE, profileId)
                            )
                        },
                        onShelf = { shelfId ->
                            // A Clean chip is a shortcut into the real page below, not a fake tab.
                            val index = shelves.indexOfFirst { it.id == shelfId }
                            if (index >= 0) {
                                list.smoothScrollToPosition(index + (if (hero != null) 1 else 0))
                            }
                        },
                        section = this@BrowseActivity.section,
                        showsLive = effectiveCaps().live,
                        showsGames = effectiveCaps().games,
                    ),
                )
                surface.root.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    heroSlotHeight(surface.preferredHeightPx),
                )
                // Clean and Kids headers print the household's name and face in their own corner;
                // surfaces without a header cluster ignore the update.
                api.profiles { people, _ ->
                    if (isFinishing || isDestroyed) return@profiles
                    val me = people?.firstOrNull { it.id == profileId } ?: return@profiles
                    surface.setProfile(
                        me.name,
                        if (me.avatar.isNotBlank()) api.absolute(me.avatar) else null,
                    )
                }
                HeroHolder(surface)
            } else {
                val box = LinearLayout(this@BrowseActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setPadding(0, theme.space("railTopGap", 18f), 0, 0)
                }
                // Packs that print rails on a light wallpaper override these two colours in the
                // pack JSON; every other pack keeps reading them in its own ink.
                val head = Views.text(app, "rowTitle", 28f, theme.colorOr("rowInk", theme.ink), "", bold = true)
                head.setPadding(theme.gutter, 0, theme.gutter, theme.px(12f))
                box.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val rail = RailView(this@BrowseActivity).apply {
                    layoutManager = LinearLayoutManager(this@BrowseActivity, LinearLayoutManager.HORIZONTAL, false)
                    setHasFixedSize(true)
                    setItemViewCacheSize(16)
                    itemAnimator = null
                    isNestedScrollingEnabled = false
                    // 44px of clear space lets a lifted, glowing lockup remain fully visible;
                    // the former 18px top inset clipped focus so it looked like an underline.
                    setPadding(theme.gutter, theme.space("railGlowPad", 44f), theme.gutter, theme.space("railGlowPad", 44f))
                    clipToPadding = false
                    clipChildren = false
                }
                box.addView(rail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                ShelfHolder(box, head, rail)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is HeroHolder) {
                if (slides.isEmpty()) return
                heroHolder = holder
                renderHero(holder)
                heroPlay = holder.surface.primaryControl
                heroRoot = holder.surface.root
                // A PS5-style surface owns its compact context strip; it becomes the return
                // anchor rather than receiving a hidden/legacy host navbar target.
                holder.surface.navigationAnchor?.let { navAnchor = it }
                holder.surface.setNextFocusUp(
                    (holder.surface.navigationAnchor ?: navAnchor)?.id ?: View.NO_ID,
                )
                holder.surface.setChips(shelves.map { it.id to it.title })
            } else if (holder is ShelfHolder) {
                val row = shelves[position - (if (hero != null) 1 else 0)]
                holder.head.text = row.title
                val cards = ShelfAdapter(this@BrowseActivity, object : ShelfHost {
                    override fun app() = this@BrowseActivity.app
                    override fun api() = this@BrowseActivity.api
                    override fun profileId() = this@BrowseActivity.profileId
                    override fun heroPos() = this@BrowseActivity.heroPos
                    override fun navAnchorId() = navAnchor?.id ?: View.NO_ID
                    override fun onCardFocused(card: View, item: Item) = this@BrowseActivity.onCardFocused(card, item)
                    override fun onSlideFocused(position: Int) = this@BrowseActivity.onSlideFocused(position)
                    override fun focusFeatured(position: Int) = this@BrowseActivity.focusFeatured(position)
                    override fun openDetail(item: Item) = this@BrowseActivity.openDetail(item)
                    override fun openLive(item: Item) = this@BrowseActivity.openLive(item)
                    override fun openRowFull(row: BrowseRow) = this@BrowseActivity.openRowFull(row)
                }, row)
                holder.rail.adapter = cards
            }
        }

        /** Render the active title inside the selected structural surface. */
        private fun renderHero(holder: HeroHolder) {
            if (slides.isEmpty()) return
            holder.surface.render(slides, heroPos)
        }
    }

    private inner class HeroHolder(val surface: HomeSurface) : RecyclerView.ViewHolder(surface.root)

    private inner class ShelfHolder(
        view: View, val head: TextView, val rail: RecyclerView,
    ) : RecyclerView.ViewHolder(view)

    companion object {
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_TAB = "tab"
        // Whether this hub carries a Live TV row. It is the fallback signal for a hub too old to
        // answer /app/v1/caps; /app/v1/caps is the fact the navigation prefers.
        internal var liveSeen = false
        private const val TYPE_HERO = 1
        private const val TYPE_SHELF = 2
    }

    /**
     * A shelf rail that keeps its column when D-pad focus arrives from the row above or below.
     * The framework hands focus to the rail container and its default descendant search takes the
     * first tile — so every vertical hop dragged the viewer back to the left edge. Pick instead the
     * tile whose horizontal span overlaps the incoming focus rectangle the most: pressing Down from
     * the third card lands on the third card of the next shelf.
     */
    private class RailView(context: android.content.Context) : RecyclerView(context) {
        override fun onRequestFocusInDescendants(
            direction: Int,
            previouslyFocusedRect: android.graphics.Rect?,
        ): Boolean {
            val prev = previouslyFocusedRect
            if (prev != null && !prev.isEmpty && (direction and (FOCUS_UP or FOCUS_DOWN)) != 0) {
                var best: View? = null
                var bestOverlap = -1L
                for (i in 0 until childCount) {
                    val c = getChildAt(i) ?: continue
                    if (!c.isFocusable) continue
                    val r = android.graphics.Rect()
                    c.getDrawingRect(r)
                    offsetDescendantRectToMyCoords(c, r)
                    val overlap = minOf(r.right.toLong(), prev.right.toLong()) -
                        maxOf(r.left.toLong(), prev.left.toLong())
                    if (overlap > bestOverlap) {
                        bestOverlap = overlap
                        best = c
                    }
                }
                if (best != null && best.requestFocus(direction, prev)) return true
            }
            return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
        }
    }
}
