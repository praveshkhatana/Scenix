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
import com.scenix.tv.core.CardActionSheet
import com.scenix.tv.core.CardActions
import com.scenix.tv.core.Motion
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Prefs
import com.scenix.tv.core.HomeLayoutSpec
import com.scenix.tv.core.Item
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
    private val liveNavs = ArrayList<View>()
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
    private var railPanel: LinearLayout? = null
    private var railScrim: View? = null
    private val railItems = ArrayList<View>()
    private var railOpen = false
    private var railReturn: View? = null
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
            if (railOpen) {
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> { moveRail(-1); return true }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { moveRail(1); return true }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                    android.view.KeyEvent.KEYCODE_BACK -> { closeRail(); return true }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER,
                    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        val focused = railItems.firstOrNull { it.isFocused }
                        focused?.performClick()
                        return true
                    }
                }
                return true
            }
            // LEFT at the left edge of a row (or a one-slide billboard) is the way into the rail.
            // A LEFT that can still move within the row belongs to the row.
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT &&
                shouldOpenRail(currentFocus)) {
                openRail()
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
    private fun captionDotLine(item: Item): CharSequence {
        val theme = app.theme
        val sb = android.text.SpannableStringBuilder()
        val rating = when {
            item.rating > 0 -> String.format(java.util.Locale.US, "%.1f", item.rating)
            item.match > 0 -> "${item.match}%"
            else -> null
        }
        if (rating != null) {
            sb.append("●")
            sb.setSpan(android.text.style.ForegroundColorSpan(theme.color("match", "#EF9F42")),
                0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" ").append(rating)
            sb.setSpan(android.text.style.ForegroundColorSpan(theme.color("captionInk", "#EAF1F6")),
                2, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (item.year > 0) sb.append("   ").append(item.year.toString())
        } else if (item.year > 0) {
            sb.append(item.year.toString())
        }
        if (item.isNew) {
            if (sb.isNotEmpty()) sb.append("   ")
            sb.append("New")
            sb.setSpan(android.text.style.ForegroundColorSpan(theme.color("match", "#EF9F42")),
                sb.length - 3, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

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
        val theme = app.theme
        val width = theme.px(340f)
        val scrim = View(this).apply {
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { closeRail() }
        }
        shell.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(245, 9, 10, 14))
            setPadding(theme.px(24f), theme.px(26f), theme.px(18f), theme.px(24f))
            visibility = View.GONE
            translationX = -width.toFloat()
            elevation = theme.px(18f).toFloat()
        }
        panel.addView(Views.text(app, "li", 20f, theme.ink2, "SCENIX", bold = true).apply {
            letterSpacing = 0.18f
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = theme.px(16f)
            bottomMargin = theme.px(18f)
        })
        // Profile, then Search, then the library. Search is a destination, not a footnote at the
        // bottom: it sits above Home the way the owner asked, and every row carries an icon.
        // Games and Live TV are doors to optional add-ons, so the rail offers them only when the
        // household actually installed them — an empty room is not a destination.
        val nav = barCaps ?: effectiveCaps()
        val destinations = buildList<Triple<String, Int, () -> Unit>> {
            add(Triple("Switch profile", R.drawable.ic_rail_profile) {
                startActivity(Intent(this@BrowseActivity, ProfilesActivity::class.java))
            })
            add(Triple("Search", R.drawable.ic_nav_search) {
                startActivity(Intent(this@BrowseActivity, SearchActivity::class.java)
                    .putExtra(SearchActivity.EXTRA_PROFILE, profileId))
            })
            add(Triple("Home", R.drawable.ic_rail_home) { goTab("") })
            add(Triple("Movies", R.drawable.ic_rail_movies) { goTab("movies") })
            add(Triple("Series", R.drawable.ic_rail_series) { goTab("series") })
            if (nav.games) add(Triple("Games", R.drawable.ic_rail_games) {
                startActivity(Intent(this@BrowseActivity, GamesActivity::class.java)
                    .putExtra(GamesActivity.EXTRA_PROFILE, profileId))
            })
            if (nav.live) add(Triple("Live TV", R.drawable.ic_rail_live) {
                startActivity(Intent(this@BrowseActivity, LiveChannelsActivity::class.java)
                    .putExtra(LiveChannelsActivity.EXTRA_PROFILE, profileId))
            })
            add(Triple("Settings", R.drawable.ic_nav_settings) {
                startActivity(Intent(this@BrowseActivity, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_PROFILE, profileId))
            })
        }
        for ((label, icon, action) in destinations) {
            panel.addView(railRow(label, icon, action), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, theme.px(66f)
            ).apply { bottomMargin = theme.px(4f) })
        }
        shell.addView(panel, FrameLayout.LayoutParams(
            width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        railPanel = panel
        railScrim = scrim
    }

    private fun railRow(label: String, iconRes: Int, action: () -> Unit): View {
        val theme = app.theme
        val radius = theme.px(12f)
        val idleInk = theme.color("railInk", "#F4F8FC")
        val idleIcon = theme.color("railInk2", "#B9C6D2")
        val focusInk = theme.color("railFocusInk", "#0A0F14")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.px(16f), 0, theme.px(16f), 0)
            isFocusable = true
            isClickable = true
            contentDescription = label
            background = Views.rounded(Color.TRANSPARENT, radius)
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(idleIcon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        row.addView(icon, LinearLayout.LayoutParams(theme.px(24f), theme.px(24f)))
        val text = Views.text(app, "li", 23f, idleInk, label, bold = true)
        text.setPadding(theme.px(18f), 0, 0, 0)
        row.addView(text, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        Focus.attach(row, row, app, radius, scale = false, elevate = false,
            // Choosing a destination closes the rail first: leaving it up would cover the page the
            // viewer just asked for, and coming back from another activity would still show it.
            onClick = { closeRail(); action() },
            onFocus = { focused ->
                // A WHITE pill with dark ink reads on every wall. Using the pack's brand made the
                // Glass rail bright blue (brand #2D6BFF) while the rest of Glass is neutral glass;
                // and on Kids a dark-brand pill plus dark ink made every item disappear.
                row.background = if (focused) Views.rounded(Color.WHITE, radius)
                else Views.rounded(Color.TRANSPARENT, radius)
                text.setTextColor(if (focused) focusInk else idleInk)
                icon.setColorFilter(if (focused) focusInk else idleIcon)
            })
        railItems += row
        return row
    }

    private fun openRail() {
        val panel = railPanel ?: return
        if (railOpen) return
        railOpen = true
        railReturn = currentFocus
        railScrim?.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        panel.animate().translationX(0f).setDuration(180L).start()
        val wanted = when (section) {
            "movies" -> "Movies"
            "series" -> "Series"
            "games" -> "Games"
            "live" -> "Live TV"
            else -> "Home"
        }
        val target = railItems.firstOrNull { it.contentDescription == wanted } ?: railItems.firstOrNull()
        target?.post { if (railOpen && !isFinishing && !isDestroyed) target.requestFocus() }
    }

    private fun closeRail() {
        if (!railOpen) return
        railOpen = false
        railScrim?.visibility = View.GONE
        val panel = railPanel
        // Put the remote back before the panel leaves; hiding a focused subtree otherwise hands
        // focus to whatever the framework finds first.
        railReturn?.takeIf { it.isAttachedToWindow }?.requestFocus()
        railReturn = null
        panel?.animate()?.translationX(-(panel.width.takeIf { it > 0 }
            ?: app.theme.px(340f)).toFloat())?.setDuration(160L)?.withEndAction {
            if (!railOpen) panel.visibility = View.GONE
        }?.start()
    }

    private fun moveRail(delta: Int) {
        if (railItems.isEmpty()) return
        val at = railItems.indexOfFirst { it.isFocused }
        val next = if (at < 0) 0 else (at + delta).coerceIn(0, railItems.lastIndex)
        railItems.getOrNull(next)?.requestFocus()
    }

    /**
     * LEFT only belongs to the rail when it cannot move the page: the focused card is the first in
     * its rail, the rail cannot scroll further left, or the billboard has one slide. Anything else is
     * an intra-row step and stays with the row.
     */
    private fun shouldOpenRail(focus: View?): Boolean {
        if (focus == null || railPanel == null) return false
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
        if (railOpen) {
            closeRail()
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
        val theme = app.theme
        // The owner's SCENIX-Logo.svg, not styled text: the letterforms must not change with the
        // font the television happens to ship.
        val brand = ImageView(this).apply {
            setImageResource(R.drawable.scenix_wordmark)
            // The mark is drawn from the owner's SVG in its brand red, then tinted from the active
            // pack: Apple's is off-white, Netflix's red, PS5's blue. A fixed red wordmark on a
            // white/blue pack read as a borrowed Netflix logo.
            setColorFilter(theme.brand)
            scaleType = ImageView.ScaleType.FIT_START
            contentDescription = "Scenix"
        }
        val profile = navButton("Profile") {
            startActivity(
                Intent(this@BrowseActivity, ProfilesActivity::class.java)
                    .putExtra(ProfilesActivity.EXTRA_FROM_BROWSE, true)
            )
        }
        val search = navButton("Search", theme.px(24f)) {
            startActivity(
                Intent(this@BrowseActivity, SearchActivity::class.java)
                    .putExtra(SearchActivity.EXTRA_PROFILE, profileId)
            )
        }
        val settings = navButton("Settings", theme.px(28f)) {
            startActivity(
                Intent(this@BrowseActivity, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_PROFILE, profileId)
            )
        }
        if (theme.navStyle == "pill") {
            // Apple-style packs float one centred pill over the stage; the wordmark stays at the edge.
            // The pill carries the household and the sections this app can play, in the order the web
            // bar teaches: face, Home, Movies, Series, Games, Live TV, then the actions.
            val bar = FrameLayout(this)
            bar.setBackgroundColor(if (transparent) Color.TRANSPARENT else theme.bg)
            bar.addView(brand, FrameLayout.LayoutParams(
                theme.px(80f), theme.px(28f)
            ).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                leftMargin = theme.gutter
            })
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(theme.px(10f), theme.px(6f), theme.px(10f), theme.px(6f))
                background = Views.rounded(theme.surface2, theme.px(999f), theme.px(1f), theme.line)
            }
            val pillProfile = profileDisc().apply { id = View.generateViewId() }
            pill.addView(pillProfile)
            val homeTab = navTab("Home", active = section.isBlank()) { goTab("") }.apply {
                id = View.generateViewId()
            }
            val moviesTab = navTab("Movies", active = section == "movies") { goTab("movies") }.apply {
                id = View.generateViewId()
            }
            val seriesTab = navTab("Series", active = section == "series") { goTab("series") }.apply {
                id = View.generateViewId()
            }
            pill.addView(homeTab)
            pill.addView(moviesTab)
            pill.addView(seriesTab)
            // Games is its own mode, not another catalogue tab: the hub answers a games shelf set
            // that has nothing in common with movie rows, so the tab opens its own screen. It is a
            // door to an add-on, so a household without the games add-on gets no tab.
            val nav = barCaps ?: effectiveCaps()
            val gamesTab = navTab("Games", active = false) {
                startActivity(
                    Intent(this@BrowseActivity, GamesActivity::class.java)
                        .putExtra(GamesActivity.EXTRA_PROFILE, profileId)
                )
            }.apply { id = View.generateViewId() }
            gamesTab.visibility = if (nav.games) View.VISIBLE else View.GONE
            pill.addView(gamesTab)
            val live = navTab("Live TV") {
                startActivity(
                    Intent(this@BrowseActivity, LiveChannelsActivity::class.java)
                        .putExtra(LiveChannelsActivity.EXTRA_PROFILE, profileId)
                )
            }
            // Live TV earns its place in the pill from the add-on and its playlist, not from a
            // guessed row. The answer outlives a tab switch so the bar never changes shape mid-walk.
            live.visibility = if (nav.live) View.VISIBLE else View.GONE
            liveNavs += live
            live.id = View.generateViewId()
            pill.addView(live)
            pill.addView(View(this).apply { setBackgroundColor(theme.line) },
                LinearLayout.LayoutParams(theme.px(1f), theme.px(26f)).apply {
                    marginStart = theme.px(10f); marginEnd = theme.px(4f)
                })
            // Search and Settings ride as icons in the pill, as Apple's bar and the prototype draw them.
            val searchIcon = navIcon(R.drawable.ic_nav_search, "Search") {
                startActivity(
                    Intent(this@BrowseActivity, SearchActivity::class.java)
                        .putExtra(SearchActivity.EXTRA_PROFILE, profileId)
                )
            }.apply { id = View.generateViewId() }
            pill.addView(searchIcon)
            val gear = navIcon(R.drawable.ic_nav_settings, "Settings") {
                startActivity(
                    Intent(this@BrowseActivity, SettingsActivity::class.java)
                        .putExtra(SettingsActivity.EXTRA_PROFILE, profileId)
                )
            }
            gear.id = View.generateViewId()
            // UP from the top row reaches the pill at the tab you are standing on, not at the far
            // end of the bar — walking in should land where you already are.
            navAnchor = when (section) {
                "movies" -> moviesTab
                "series" -> seriesTab
                else -> homeTab
            }
            pillGroup = pill
            pill.addView(gear)
            // Focus search treats the 1px divider and the full-screen stage as valid geometry.
            // The result was that RIGHT at the end of the tabs could fall into a shelf instead of
            // reaching Search.  A TV navigation bar is an ordered remote path, so spell it out.
            pillStops = ArrayList<View>().apply {
                add(pillProfile)
                add(homeTab)
                add(moviesTab)
                add(seriesTab)
                add(gamesTab)
                add(live)
                add(searchIcon)
                add(gear)
            }
            wirePillFocus()
            bar.addView(pill, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER })
            // DOWN from anywhere on the bar returns to the top of the shelves.
            for (i in 0 until pill.childCount) pill.getChildAt(i).nextFocusDownId = shelfListId
            if (transparent) chromeBar = bar
            return bar
        }
        // Classic/Netflix navigation uses the wordmark as its Home stop.  It used to be only
        // decoration, leaving `navAnchor` unset for these packs: UP from Hero Play then had no
        // route at all.  Give it the same padded, explicit remote target as the rest of the bar.
        val homeBrand = FrameLayout(this).apply {
            id = View.generateViewId()
            contentDescription = "Home"
            isClickable = true
            clipChildren = false
            setPadding(theme.px(10f), theme.px(7f), theme.px(10f), theme.px(7f))
            setOnClickListener { goTab("") }
        }
        // The wordmark itself has no opaque surface for Android to paint a keyline onto.  A
        // FrameLayout lets Focus use its foreground, keeping the compact ring above the mark
        // without letting a background child influence the bar's wrap-content measurement.
        // The mark's vector canvas is 351x81 dp — enormous if left at wrap-content, where it
        // towered over the bar. The wordmark is a signature, not the page's hero.
        homeBrand.addView(brand, FrameLayout.LayoutParams(
            theme.px(132f), theme.px(30f)
        ).apply { gravity = Gravity.CENTER })
        Focus.attach(homeBrand, homeBrand, app, theme.radius("button", 6f), scale = false, elevate = false,
            onFocus = { if (it) onChromeFocused() })
        val movies = navButton("Movies") { goTab("movies") }.apply { id = View.generateViewId() }
        val series = navButton("Series") { goTab("series") }.apply { id = View.generateViewId() }
        // Games is a mode, not a catalogue tab, in this bar too: without it the only way to the
        // games screens from the Netflix and Classic packs was the settings page. It is a door to
        // an add-on, so it exists only when the household installed the games add-on.
        val nav = barCaps ?: effectiveCaps()
        val games = navButton("Games") {
            startActivity(
                Intent(this@BrowseActivity, GamesActivity::class.java)
                    .putExtra(GamesActivity.EXTRA_PROFILE, profileId)
            )
        }.apply { id = View.generateViewId() }
        games.visibility = if (nav.games) View.VISIBLE else View.GONE
        // Live TV belongs in every pack's bar, not just the Apple pill — when the hub carries the
        // live add-on and a playlist. The Netflix and Classic bars were the one place a household
        // with channels could not reach them without the left rail.
        val live = navButton("Live TV") {
            startActivity(
                Intent(this@BrowseActivity, LiveChannelsActivity::class.java)
                    .putExtra(LiveChannelsActivity.EXTRA_PROFILE, profileId)
            )
        }.apply { id = View.generateViewId() }
        live.visibility = if (nav.live) View.VISIBLE else View.GONE
        liveNavs += live
        profile.id = View.generateViewId()
        search.id = View.generateViewId()
        settings.id = View.generateViewId()
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(theme.gutter, 0, theme.gutter, 0)
        bar.setBackgroundColor(if (transparent) Color.TRANSPARENT else theme.bg)
        bar.addView(homeBrand, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(movies,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = theme.px(22f)
            })
        bar.addView(series,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = theme.px(18f)
            })
        bar.addView(games,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = theme.px(18f)
            })
        bar.addView(live,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = theme.px(18f)
            })
        bar.addView(profile, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = theme.px(24f)
        })
        bar.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = theme.px(24f)
        })
        bar.addView(settings, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = theme.px(28f)
        })
        // Do not leave classic packs to geometric focus search.  The spacer occupies most of the
        // bar, and on TV that made the Home/Play boundary impossible to traverse reliably.
        navAnchor = homeBrand
        pillGroup = bar
        pillStops = listOf(homeBrand, movies, series, games, live, profile, search, settings)
        wirePillFocus()
        for (i in 0 until bar.childCount) bar.getChildAt(i).nextFocusDownId = shelfListId
        if (transparent) chromeBar = bar
        return bar
    }

    private fun navButton(
        label: String, left: Int = 0, preAttach: ((TextView) -> Unit)? = null, action: () -> Unit,
    ): View {
        val b = Views.text(app, "li", 20f, app.theme.ink, label, bold = true, gravity = Gravity.CENTER)
        b.setPadding(app.theme.px(17f), app.theme.px(9f), app.theme.px(17f), app.theme.px(9f))
        b.isFocusable = true
        b.isClickable = true
        b.setOnClickListener { action() }
        // Whatever identity the tab wears — the pill of the section you stand on — goes on before
        // the focus paint below, so walking focus over the tab and away never erases it.
        preAttach?.invoke(b)
        val plain = b.background
        val active = preAttach != null
        // A soft halo band around a 27 px line of text is invisible on a TV panel: the viewer could
        // not tell which tab in the bar held the ring. Chrome items get an explicit outline instead,
        // and the white-faced active tab turns silver while focused so its ring still reads.
        fun paintChrome(focused: Boolean) {
            b.background = when {
                !focused -> plain
                active -> Views.rounded(
                    Color.argb(255, 198, 200, 206), app.theme.px(999f),
                    app.theme.px(3f), Color.argb(235, 255, 255, 255))
                else -> Views.rounded(
                    Color.argb(36, 255, 255, 255), app.theme.px(999f),
                    app.theme.px(2f), Color.argb(235, 255, 255, 255))
            }
        }
        paintChrome(false)
        Focus.attach(b, null, app, app.theme.radius("button", 6f), scale = false, elevate = false,
            onFocus = { f -> if (f) onChromeFocused(); paintChrome(f) })
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginStart = left
        b.layoutParams = lp
        return b
    }

    /**
     * A section of the pill.  The text lives in a child and the selected face on its container;
     * a sibling paints the focus ring.  Painting focus directly onto a TextView was letting Android
     * replace the pill's drawable and intermittently hide the label itself.
     */
    private fun navTab(label: String, active: Boolean = false, action: () -> Unit): View {
        val theme = app.theme
        val chip = FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            contentDescription = label
            clipChildren = false
            setOnClickListener { action() }
        }
        chip.background = if (active) Views.rounded(theme.brand, theme.px(999f)) else null
        val words = Views.text(app, "nav", 20f, if (active) theme.onBrand else theme.ink, label,
            bold = true, gravity = Gravity.CENTER).apply {
            // The content view never takes focus; focus belongs to the whole padded target.
            isFocusable = false
            isClickable = false
            setPadding(theme.px(20f), theme.px(12f), theme.px(20f), theme.px(12f))
        }
        // A foreground drawable on this Android TV renderer can suppress child text after focus
        // leaves it.  Keep the ring in a separately-sized sibling instead.
        val focusPlate = View(this)
        chip.addView(focusPlate, FrameLayout.LayoutParams(0, 0))
        chip.addView(words, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        chip.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val lp = focusPlate.layoutParams
            if (lp.width != chip.width || lp.height != chip.height) {
                lp.width = chip.width
                lp.height = chip.height
                focusPlate.layoutParams = lp
            }
        }
        Focus.attach(chip, focusPlate, app, theme.px(999f), scale = false, elevate = false,
            onFocus = { focused ->
                // Home's white selected state is semantic, not a focus effect. Give a focused
                // selected tab a dark keyline so the remote target stays unambiguous.
                if (focused && active) {
                    focusPlate.background = Views.ring(Color.argb(225, 20, 21, 26), theme.px(999f), theme.px(3f))
                }
                if (focused) onChromeFocused()
            })
        chip.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = theme.px(4f) }
        return chip
    }

    /**
     * The pill's icon actions.  The glyph is fixed inside a full remote target instead of using
     * ImageView padding as both visual and focus geometry; that makes Search and Settings equal in
     * size and gives their focus plate room to read.
     */
    private fun navIcon(res: Int, desc: String, action: () -> Unit): View {
        val theme = app.theme
        val hit = FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            contentDescription = desc
            clipChildren = false
            setOnClickListener { action() }
        }
        val glyph = ImageView(this).apply {
            setImageResource(res)
            setColorFilter(theme.ink)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val target = theme.px(56f)
        val glyphSize = theme.px(26f)
        val focusPlate = View(this)
        hit.addView(focusPlate, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        hit.addView(glyph, FrameLayout.LayoutParams(glyphSize, glyphSize).apply {
            gravity = Gravity.CENTER
        })
        Focus.attach(hit, focusPlate, app, theme.px(999f), scale = false, elevate = false,
            onFocus = { focused ->
                if (focused) {
                    // Keep the glyph centred inside a real 56px hit target and draw its focus on
                    // the separate plate, never over the icon itself.
                    focusPlate.background = Views.ring(theme.focusRingColor, theme.px(999f), theme.px(2f))
                }
                if (focused) onChromeFocused()
                hit.background = if (focused) Views.rounded(
                    Color.argb(42, 255, 255, 255), theme.px(999f)
                ) else null
            })
        hit.layoutParams = LinearLayout.LayoutParams(target, target).apply {
            marginStart = theme.px(2f); marginEnd = theme.px(2f)
        }
        return hit
    }

    /** The household at the head of the pill: the profile's face when the hub has one, its initial otherwise. */
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
                liveNavs.forEach { it.visibility = View.VISIBLE }
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
        body?.text = if (standalone)
            "Scenix becomes the backend on this TV. Add an add-on and films and series arrive straight from it — no second machine, no hub address."
        else
            "The hub is reachable, but its shelves are empty. Add an add-on to the hub in Settings, or check that its library has finished its first scan."
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
                val cards = CardAdapter(row)
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

    private inner class CardAdapter(private val row: BrowseRow) : RecyclerView.Adapter<CardHolder>() {
        private val theme = app.theme
        private val style = row.card
        // The featured tiles are the hero's slide list: focus on one turns the billboard above it.
        private val featured = row.id == "featured"
        // The pack owns the rail shape: Midnight turns ordinary poster shelves into wide 16:9 stills,
        // Ember keeps portraits but frames them. Ranked rows keep their numeral geometry.
        private val landscape = style == "poster" && theme.rowStyle == "landscape"
        private val poster = if (landscape) false else style == "poster" || style == "rank" || style == "hero"
        private val ranked = style == "rank"
        private val frame = theme.cardStyle == "frame"
        // The pack's card token picks the tile GEOMETRY (overlay/glass/below/frame); the material
        // is a separate decision. The Glass pack draws below-art captions yet wants its rails to
        // carry the same translucent plates as its hero, so tileMaterial declares the material
        // independently of the geometry.
        private val glass = theme.cardStyle == "glass" || theme.packString("tileMaterial", "") == "glass"
        // Apple's scene cards keep the name under the art instead of over it.
        private val below = theme.cardStyle == "below"
        private val captionH = when {
            below -> theme.space("captionBlock", 62f)
            frame -> theme.px(56f)
            else -> 0
        }
        // A pack may resize the poster lockup from its spacing tokens (Glass replicates the
        // reference's 264×373 tiles at 27px gaps); packs that omit the keys keep the classic
        // geometry byte for byte. The Kids contract squares its tiles from `cards.poster` instead.
        private val tileSquare = theme.packInt("tileSquare", 0) == 1
        private val cardW = if (poster && tileSquare) theme.posterTilePx().first
            else if (poster) theme.space("posterWidth", if (landscape) 480f else 280f)
            else theme.px(320f)
        private val cardH = if (poster && tileSquare) theme.posterTilePx().second
            else if (poster) theme.space("posterHeight", if (landscape) 270f else 420f)
            else theme.px(180f)
        private val radius = if (poster && tileSquare) theme.radius("tile", 24f)
            else theme.radius("card", 6f)
        // When the shelf is a slice of a bigger list, its end carries one more tile: the full list.
        private val seeAll = !featured && row.total > row.items.size
        // Continue is the one shelf a viewer can clean up in place: long-press removes the tile and
        // the hub marks the record dismissed (the position survives; pressing the title re-arms it).
        // The mutable copy exists so the removal animates without re-reading Home.
        private val dismissible = row.id == "continue"
        // My List is the mirror image: a held tile that leaves the list should leave the shelf too.
        private val myListRow = row.id == "mylist"
        private val shown: MutableList<Item> = row.items.toMutableList()

        override fun getItemCount(): Int = shown.size + if (seeAll) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
            val card = FrameLayout(this@BrowseActivity)
            card.layoutParams = RecyclerView.LayoutParams(cardW, cardH + captionH).apply {
                // Apple's shelves breathe; tiles never touch each other. Glass measures a
                // poster gap, the Kids contract a wider plate gap; both win over the classic 26.
                marginEnd = if (theme.token("spacing", "posterGap", 0.0) > 0.0)
                    theme.space("posterGap", 26f) else theme.space("cardGap", 26f)
            }
            card.isFocusable = true
            card.isFocusableInTouchMode = true
            card.isClickable = true
            card.setBackgroundColor(if (frame) theme.surface else Color.TRANSPARENT)
            // This transparent, oversized view is the focus treatment's canvas. It must be the
            // first child so the artwork masks the centre of the halo; adding it last painted the
            // former focus bands over the poster and caption.
            val glowPad = theme.focusGlowInset
            val ringOn = View(this@BrowseActivity)
            card.clipChildren = false
            card.addView(ringOn, FrameLayout.LayoutParams(cardW + glowPad * 2, cardH + glowPad * 2).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = -glowPad
                topMargin = -glowPad
            })
            // A pack whose below-captions name a plate colour (the Kids stage frames every tile in
            // its own yellow) gets chunky framed tiles: the photo sits INSIDE a rounded plate with
            // a soft glow, as in the reference, never pasted bare onto the sheet.
            val tilePlate = if (below) theme.colorOr("cardTint", 0) else 0
            val tileInset = if (tilePlate != 0) theme.space("kidsTileInset", 12f) else 0
            if (tilePlate != 0) {
                card.addView(View(this@BrowseActivity).apply {
                    background = Views.rounded(tilePlate,
                        if (poster && tileSquare) radius else radius + theme.px(10f))
                    elevation = theme.px(10f).toFloat()
                }, FrameLayout.LayoutParams(cardW, cardH).apply { gravity = Gravity.TOP })
            }
            val art = ImageView(this@BrowseActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(theme.surface2)
                clipToOutline = true
                // The plate sits at elevation 10 to cast its glow; the photo must sit above it or
                // Android's z-order paints the plate over the artwork.
                if (tilePlate != 0) elevation = theme.px(12f).toFloat()
                // Glass tiles are bare art plates: the pack's depth cue is a real cast shadow
                // under the rounded outline, separate from the focus glow painted behind.
                if (glass) elevation = theme.space("posterElevation", 10f).toFloat()
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, v.width, v.height, theme.radius("card", 6f).toFloat())
                    }
                }
            }
            card.addView(art, FrameLayout.LayoutParams(cardW - tileInset * 2, cardH - tileInset * 2).apply {
                gravity = Gravity.TOP
                leftMargin = tileInset
                topMargin = tileInset
            })
            if (!frame && !below) {
                val shade = View(this@BrowseActivity).apply {
                    background = GradientDrawable(
                        GradientDrawable.Orientation.BOTTOM_TOP,
                        intArrayOf(Color.argb(if (glass) 190 else 235, 0, 0, 0), Color.TRANSPARENT)
                    )
                    // Parents paint by Z: the elevated glass art would cover a Z-0 shade.
                    if (glass) elevation = theme.space("posterElevation", 10f) + 2f
                }
                card.addView(shade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(140f)).apply {
                    gravity = Gravity.BOTTOM
                    bottomMargin = captionH
                })
            }
            if (frame) {
                // An editorial card is a printed plate: opaque caption bar, hairline border, art above.
                card.addView(View(this@BrowseActivity).apply { setBackgroundColor(theme.surface) },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, captionH).apply {
                        gravity = Gravity.BOTTOM
                    })
                card.addView(View(this@BrowseActivity).apply {
                    background = Views.ring(theme.line, radius, theme.px(1f))
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } else if (glass && !below) {
                // A glass tile that OVERLAYS its name gets a darker name strip welded to the art.
                // A below-caption pack must NOT get one: the strip would paint as a stray grey box
                // floating on the wall under every poster (owner ruling 2026-11-03 — the reference
                // rail caption is bare text on the wall; the tile itself is the plate).
                card.addView(View(this@BrowseActivity).apply {
                    // A console pack's glass is cool blue; a cinema pack can keep the neutral
                    // dark plate. The card shape stays shared while colour remains declarative.
                    background = Views.rounded(
                        theme.color("glassSurface", "rgba(8,10,14,0.59)"),
                        theme.radius("card", 6f),
                        theme.px(1f),
                        theme.color("glassLine", "rgba(255,255,255,0.35)"),
                    )
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(84f)).apply {
                    gravity = Gravity.BOTTOM
                    leftMargin = theme.px(8f); rightMargin = theme.px(8f); bottomMargin = theme.px(8f)
                })
            }
            val name = Views.text(app, "caption", if (below) 18f else 19f,
                theme.colorOr("captionInk", theme.ink), "", bold = true,
                maxLines = if (below) 1 else 2)
            if (poster && tileSquare) {
                // The Kids contract prints captions as letter-spaced capitals.
                name.isAllCaps = true
                name.letterSpacing = 0.12f
            }
            name.setPadding(theme.px(if (below) 4f else 12f), 0,
                theme.px(if (below) 4f else 12f), theme.px(if (below) 0f else 10f))
            card.addView(name, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = when {
                    below -> if (poster && tileSquare) theme.space("kidsCaptionGap", 16f)
                        else theme.space("captionNameBottom", 26f)
                    frame -> theme.px(8f)
                    else -> 0
                }
            })
            val sub = Views.text(app, "meta", 14f, theme.colorOr("captionSub", theme.ink3), "", maxLines = 1)
            sub.setPadding(theme.px(4f), 0, theme.px(4f), theme.px(4f))
            sub.visibility = if (below) View.VISIBLE else View.GONE
            card.addView(sub, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = theme.px(6f)
            })
            val logo = ImageView(this@BrowseActivity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
            }
            card.addView(logo, FrameLayout.LayoutParams(cardW - theme.px(28f),
                if (landscape) theme.px(64f) else theme.px(76f)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = when {
                    below -> captionH + theme.px(10f)
                    frame -> theme.px(10f)
                    else -> theme.px(8f)
                }
            })
            val badge = Views.text(app, "meta", 13f,
                if (glass) theme.color("match", "#EF9F42") else Color.WHITE, "", bold = true, maxLines = 1)
            badge.setPadding(theme.px(8f), theme.px(3f), theme.px(8f), theme.px(3f))
            badge.background = if (glass) {
                // The glass chip: the same deep-glass capsule the PIN keys and nav pill ride,
                // with the pack's hairline rim — labels sit ON the art, where the eye lands
                // first (owner ask, 2026-11-03).
                badge.setPadding(theme.px(12f), theme.px(4f), theme.px(12f), theme.px(4f))
                Views.rounded(theme.color("gatePlate", "rgba(8,16,28,0.88)"), theme.px(999f),
                    theme.px(1f), theme.color("glassRim", "rgba(255,255,255,0.38)"))
            } else Views.rounded(Color.argb(185, 8, 8, 8), theme.px(5f))
            card.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = theme.px(10f); topMargin = theme.px(10f)
            })
            val num = Views.text(app, "h1", 0f, Color.WHITE, "", bold = true, gravity = Gravity.CENTER)
            num.visibility = if (ranked) View.VISIBLE else View.GONE
            card.addView(num, FrameLayout.LayoutParams(theme.px(110f), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START
            })
            // The See all tile's badge: a feather chevron on a quiet disc, hidden on every other tile.
            val chevron = ImageView(this@BrowseActivity).apply {
                setImageResource(R.drawable.ic_chevron_right)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(theme.px(20f), theme.px(20f), theme.px(20f), theme.px(20f))
                if (glass) {
                    // On a frosted plate the disc is itself frost, with light ink.
                    setColorFilter(theme.color("inkOnWall", "#FFFFFF"), android.graphics.PorterDuff.Mode.SRC_IN)
                    background = Views.rounded(theme.color("discGlass", "rgba(255,255,255,0.16)"), theme.px(999f))
                } else {
                    setColorFilter(theme.ink, android.graphics.PorterDuff.Mode.SRC_IN)
                    background = Views.rounded(theme.surface2, theme.px(999f))
                }
                visibility = View.GONE
            }
            card.addView(chevron, FrameLayout.LayoutParams(theme.px(78f), theme.px(78f)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = (cardH - theme.px(78f)) / 2 - theme.px(24f)
            })
            // IMDb rating and the certificate ride the top-right corner, gold star and plain badge,
            // exactly as the pack's card design drew them.
            val imdb = Views.text(app, "meta", 13f, Color.parseColor("#F6C621"), "", bold = true, maxLines = 1)
            val cert = Views.text(app, "meta", 13f, Color.WHITE, "", bold = true, maxLines = 1)
            if (glass) {
                // Same deep-glass capsule as the NEW/HOT chip: the rating rides ON the art,
                // where the eye lands first (owner ask, 2026-11-03).
                imdb.setPadding(theme.px(12f), theme.px(4f), theme.px(12f), theme.px(4f))
                cert.setPadding(theme.px(12f), theme.px(4f), theme.px(12f), theme.px(4f))
                imdb.background = Views.rounded(theme.color("gatePlate", "rgba(8,16,28,0.88)"),
                    theme.px(999f), theme.px(1f), theme.color("glassRim", "rgba(255,255,255,0.38)"))
                cert.background = imdb.background
            } else {
                imdb.setPadding(theme.px(8f), theme.px(3f), theme.px(8f), theme.px(3f))
                imdb.background = Views.rounded(Color.argb(185, 8, 8, 8), theme.px(5f))
                cert.setPadding(theme.px(8f), theme.px(3f), theme.px(8f), theme.px(3f))
                cert.background = Views.rounded(Color.argb(185, 8, 8, 8), theme.px(5f))
            }
            val corner = LinearLayout(this@BrowseActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
            }
            corner.addView(imdb)
            corner.addView(cert, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = theme.px(6f) })
            card.addView(corner, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                rightMargin = theme.px(10f); topMargin = theme.px(10f)
            })
            val progress = android.widget.ProgressBar(this@BrowseActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                // The prior bar was the old Netflix red hard-code.  In the Apple pack this is a
                // quiet white playhead over a translucent track, matching the neutral controls.
                val track = GradientDrawable().apply {
                    setColor(Color.argb(84, 255, 255, 255))
                    cornerRadius = theme.px(2f).toFloat()
                }
                val fill = ClipDrawable(GradientDrawable().apply {
                    setColor(Color.argb(235, 255, 255, 255))
                    cornerRadius = theme.px(2f).toFloat()
                }, Gravity.LEFT, ClipDrawable.HORIZONTAL)
                progressDrawable = LayerDrawable(arrayOf(track, fill)).apply {
                    setId(0, android.R.id.background)
                    setId(1, android.R.id.progress)
                }
                visibility = View.GONE
            }
            card.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(4f)).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = captionH
            })
            val slideState = if (featured) View(this@BrowseActivity).apply {
                // Featured tiles are previews of the hero, not a second focus target. Apple gets
                // one short active-slide marker; other packs retain their existing full rim.
                background = if (theme.packString("featuredMarker", "rim") == "bar") {
                    // A black cradle keeps the white marker legible even over a bright poster;
                    // this is state, not another card outline, so it cannot be mistaken for focus.
                    LayerDrawable(arrayOf(
                        Views.rounded(Color.argb(205, 0, 0, 0), theme.px(999f)),
                        Views.rounded(Color.WHITE, theme.px(999f)),
                    )).apply {
                        val side = theme.px(11f)
                        setLayerInset(1, side, theme.px(3f), side, theme.px(3f))
                    }
                } else {
                    Views.ring(Color.argb(140, 255, 255, 255), radius, theme.px(2f))
                }
                visibility = View.GONE
            } else null
            if (slideState != null) {
                val markerLayout = if (theme.packString("featuredMarker", "rim") == "bar") {
                    FrameLayout.LayoutParams(theme.px(84f), theme.px(10f)).apply {
                        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        bottomMargin = captionH + theme.px(10f)
                    }
                } else {
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardH).apply {
                        gravity = Gravity.TOP
                    }
                }
                card.addView(slideState, markerLayout)
            }
            if (tilePlate != 0) {
                // On a framed tile the photo itself rides above the opaque plate; every overlay —
                // badge, rating corner, rank numeral, genre label, logo, playhead — must ride above
                // the photo or Android's z-order buries it under the plate (owner round 5: category
                // tiles rendered blank, shelf badges never showed).
                for (v in listOf(badge, corner, num, chevron, logo, progress))
                    v.elevation = theme.px(14f).toFloat()
            }
            val holder = CardHolder(card, art, name, sub, logo, badge, num, progress, slideState, imdb, cert, corner, chevron)
            if (glass) {
                // Parents paint by Z: the glass art rides posterElevation for its cast shadow, so
                // anything that stands ON the art (chips, rank column, playhead, see-all chevron)
                // needs its own step of elevation or the elevated art simply paints over it —
                // that is why the pass-13 rating/NEW chips were invisible at first.
                val overlayZ = theme.space("posterElevation", 10f) + 2f
                logo.elevation = overlayZ
                badge.elevation = overlayZ
                num.elevation = overlayZ
                progress.elevation = overlayZ
                chevron.elevation = overlayZ
                corner.elevation = overlayZ
            }
            // Glass tiles lift on focus exactly like the hero plates (halo + elevation on the
            // same resting cast shadow); other packs keep the flat, perf-first treatment.
            Focus.attach(card, ringOn, app, radius, animateScale = false, elevate = glass,
                glowInset = glowPad,
                // The hero's rail is a loop, not a corridor: walking off either end lands on the
                // other, the way the prototype's tiles wrap and the billboard never dead-ends.
                arrow = if (featured) { code ->
                    when (code) {
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                            if (holder.bindingAdapterPosition >= itemCount - 1) { focusFeatured(0); true } else false
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT ->
                            if (holder.bindingAdapterPosition == 0) { focusFeatured(itemCount - 1); true } else false
                        else -> false
                    }
                } else null,
                onFocus = { focused ->
                    if (!focused) return@attach
                    if (featured) onSlideFocused(holder.bindingAdapterPosition)
                    else holder.item?.let { onCardFocused(card, it) }
                })
            return holder
        }

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            if (seeAll && position >= shown.size) {
                bindSeeAll(holder)
                return
            }
            val item = shown[position]
            holder.item = item
            holder.itemView.contentDescription = CardActions.announcement(
                app, profileId, item, itemAnnouncement(item),
                holdHint = "holding opens its options",
            )
            if (item.type == "genre") {
                bindGenre(holder, item)
                return
            }
            // Hold OK on a card asks first: the hold opens the choice sheet and a second OK on a
            // row is the confirmation. Acting on the hold itself dropped Continue tiles the instant
            // a viewer rested a thumb on one (owner report, 2026-11-03). Every theme walks this one
            // adapter, so the gesture and its wording belong to the app rather than to a pack.
            holder.itemView.isLongClickable = item.type != "genre"
            holder.itemView.setOnLongClickListener {
                if (item.isLive() || item.type == "genre") return@setOnLongClickListener false
                val at = holder.bindingAdapterPosition
                if (at !in shown.indices) return@setOnLongClickListener false
                CardActions.holdSheet(
                    this@BrowseActivity, app, api, profileId, item,
                    canDismissContinue = dismissible,
                    afterList = { onList ->
                        val now = holder.bindingAdapterPosition
                        if (!onList && myListRow && now in shown.indices) {
                            shown.removeAt(now)
                            notifyItemRemoved(now)
                            notifyItemRangeChanged(now, shown.size - now)
                        }
                        holder.itemView.contentDescription =
                            CardActions.announcement(app, profileId, shown.getOrNull(now) ?: item,
                                itemAnnouncement(item))
                    },
                    afterDismiss = {
                        val now = holder.bindingAdapterPosition
                        if (now in shown.indices) {
                            shown.removeAt(now)
                            notifyItemRemoved(now)
                            notifyItemRangeChanged(now, shown.size - now)
                        }
                    },
                )
                true
            }
            holder.chevron.visibility = View.GONE
            if (featured) holder.itemView.nextFocusUpId = navAnchor?.id ?: View.NO_ID
            // A square framed tile (the Kids stage) wears its facts ON the art like every other
            // card pack — a NEW pill and the rating corner — while its name stays printed below
            // (owner round 5). Scene packs still keep the meta line under the artwork instead.
            val onTileFacts = below && poster && tileSquare
            holder.name.text = item.title
            holder.name.visibility = View.VISIBLE
            holder.sub.visibility = if (below && !onTileFacts) View.VISIBLE else View.GONE
            if (below && !onTileFacts) {
                if (theme.packString("captionMeta", "") == "dot") {
                    // The glass reference prints "● 7.8 · 2023": an accent dot welded to the
                    // rating, the year quieter behind it. One shared format for the whole pack.
                    // With the rating and NEW/HOT chips moved ON the art (owner ask, 2026-11-03)
                    // the glass caption keeps only the year — repeating the facts under the tile
                    // is how they got buried in the first place.
                    holder.sub.text = if (glass)
                        (if (item.year > 0) item.year.toString() else "")
                    else captionDotLine(item)
                } else {
                    val sub = ArrayList<String>()
                    // Scene cards put their facts below the artwork; overlay and glass cards use the
                    // on-art badge/corner treatment instead. This is a card-layout decision, not a
                    // named-theme exception, so any future spacious pack inherits it cleanly.
                    if (ranked && below) sub.add("#${position + 1}")
                    if (below && item.isNew) sub.add("New")
                    if (item.year > 0) sub.add(item.year.toString())
                    if (item.match > 0) sub.add("${item.match}% match")
                    if (below && item.rating > 0) {
                        sub.add(String.format(java.util.Locale.US, "★ %.1f", item.rating))
                    }
                    if (below && item.maturity.isNotBlank()) sub.add(item.maturity)
                    item.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let { sub.add(it) }
                    holder.sub.text = sub.joinToString(" · ")
                }
            }
            holder.logo.visibility = View.GONE
            holder.logo.tag = item.identity + ":logo"
            val imdbR = if (item.rating > 0) String.format(java.util.Locale.US, "★ %.1f", item.rating) else ""
            // The glass pack moved its facts ON the art (owner ask, 2026-11-03): a rating chip
            // top-right and a NEW/HOT chip top-left, deep-glass capsules over the poster — the
            // reference's own tiles carry no text at all, so on-art chips beat a caption line the
            // eye never reaches while scanning. Scene lockups still keep metadata under the image.
            val onArt = !below || glass || onTileFacts
            if (onArt) {
                holder.imdb.text = imdbR
                holder.imdb.visibility = if (imdbR.isNotEmpty()) View.VISIBLE else View.GONE
                holder.cert.text = item.maturity
                holder.cert.visibility = if (item.maturity.isNotBlank()) View.VISIBLE else View.GONE
                holder.corner.visibility =
                    if (imdbR.isNotEmpty() || item.maturity.isNotBlank()) View.VISIBLE else View.GONE
            } else {
                holder.imdb.visibility = View.GONE
                holder.cert.visibility = View.GONE
                holder.corner.visibility = View.GONE
            }
            val facts = ArrayList<String>()
            if (!below || onTileFacts) {
                if (item.isNew) facts.add("NEW")
                if (item.match > 0) facts.add("${item.match}% match")
            } else if (glass) {
                if (item.isNew) facts.add("NEW")
                // The hub's match is how strongly a title fits this house; at 90+ it IS the hot
                // signal, and it reads as one word instead of a percentage on a small chip.
                if (item.match >= 90) facts.add("HOT")
            }
            holder.badge.text = facts.joinToString(" · ")
            holder.badge.visibility = if (facts.isEmpty()) View.GONE else View.VISIBLE
            val artUrl = if (landscape) item.backdrop ?: item.poster else item.poster ?: item.backdrop
            val logoUrl = item.logo
            // Posters first, logos after: the queue is newest-first, so enqueueing a logo right
            // after its poster ran the decoration ahead of the art. Title logos belong on WIDE tiles
            // (owner ruling): a plain poster is already a title lockup, and the glass pass-12
            // exception for portrait posters is retired (2026-11-03) — the hub's poster art already
            // composes its own lockup, so the overlay double-painted it.
            BrowseImages.load(holder.art, artUrl, cardW) {
                // A wide tile carries the title logo; a poster already has its own lockup. The one
                // portrait exception is a tile with no artwork at all — a logo there names a plate
                // that would otherwise be empty.
                if (logoUrl.isNullOrBlank()) return@load
                if (!landscape && !artUrl.isNullOrBlank()) return@load
                holder.logo.visibility = View.VISIBLE
                BrowseImages.load(holder.logo, logoUrl, cardW) { ok ->
                    holder.logo.visibility = if (ok) View.VISIBLE else View.GONE
                    // A scene card keeps its name under the art even when the art carries a logo.
                    holder.name.visibility = if (ok && !below) View.GONE else View.VISIBLE
                }
            }
            if (ranked && !below) {
                // A recycled tile may have worn the See all lockup; the rank column rides the left edge.
                holder.num.visibility = View.VISIBLE
                holder.num.setPadding(0, 0, 0, 0)
                holder.num.layoutParams = FrameLayout.LayoutParams(theme.px(110f), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.START
                }
                holder.num.text = (position + 1).toString()
                holder.num.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, cardH * 0.44f)
                holder.num.setShadowLayer(theme.px(6f).toFloat(), 0f, 0f, Color.BLACK)
                if (theme.packString("rank", "solid") == "outline") {
                    // Apple's board numerals are hollow, not filled: the art stays visible through them.
                    holder.num.setTextColor(Color.WHITE)
                    holder.num.paint.style = android.graphics.Paint.Style.STROKE
                    holder.num.paint.strokeWidth = theme.px(3f).toFloat()
                } else {
                    holder.num.setTextColor(Color.WHITE)
                    holder.num.paint.style = android.graphics.Paint.Style.FILL
                }
            } else {
                holder.num.visibility = View.GONE
                // Reset mutable text paint in case this holder later serves a genre or See all tile.
                holder.num.paint.style = android.graphics.Paint.Style.FILL
                holder.num.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
            val p = item.progress
            if (p != null && p.watchedMs > 0L) {
                holder.progress.visibility = View.VISIBLE
                holder.progress.progress = ((p.positionMs.toFloat() / p.watchedMs.toFloat()).coerceIn(0f, 1f) * 1000).toInt()
            } else {
                holder.progress.visibility = View.GONE
            }
            // The marker is state, not focus: it stays on the preview belonging to the hero while
            // the visible keyline/glow belongs only to the real D-pad target.
            holder.slideState?.visibility = if (position == heroPos) {
                View.VISIBLE
            } else {
                View.GONE
            }
            holder.itemView.tag = null
            holder.itemView.setOnClickListener {
                if (item.isLive()) openLive(item) else openDetail(item)
            }
        }

        /**
         * Poster art is intentionally not announced: the card already has a short, useful label.
         * This keeps TalkBack's D-pad walk aligned with the visual hierarchy instead of reading a
         * title, badge, artwork and caption as four separate stops.
         */
        private fun itemAnnouncement(item: Item): String = buildString {
            append(item.title)
            if (item.year > 0) append(", ").append(item.year)
            if (item.match > 0) append(", ").append(item.match).append("% match")
            if (item.isLive()) append(", live")
            append(", open details")
        }

        /** The shelf's last tile: a quiet bordered card with a chevron disc and the words "See all". */
        private fun bindSeeAll(holder: CardHolder) {
            holder.item = null
            holder.itemView.tag = "see-all"
            holder.itemView.contentDescription = "See all ${row.title}"
            // Cancel any in-flight poster/logo decode from the card this view used to hold; a
            // callback that still matches the old URL would paint over the See all lockup.
            holder.art.tag = "see-all:" + row.id
            holder.logo.tag = "see-all:" + row.id
            holder.art.setImageDrawable(null)
            holder.art.setBackgroundColor(Color.TRANSPARENT)
            // Glass: a frosted translucent plate on the wall (fill + rim + sheen + cast shadow),
            // not an opaque paper card — other packs keep their bordered sheet.
            holder.art.background = if (glass) GlassKit.tilePlate(app, theme.radius("card", 6f),
                theme.color("seeAllGlassA", "rgba(232,240,248,0.16)"),
                theme.color("seeAllGlassB", "rgba(196,210,224,0.08)"))
            else Views.rounded(theme.surface, theme.radius("card", 6f), theme.px(1f), theme.line)
            holder.name.text = row.title
            holder.name.visibility = View.VISIBLE
            holder.sub.visibility = View.GONE
            holder.logo.visibility = View.GONE
            holder.corner.visibility = View.GONE
            holder.badge.visibility = View.GONE
            holder.progress.visibility = View.GONE
            holder.slideState?.visibility = View.GONE
            holder.chevron.visibility = View.VISIBLE
            holder.num.visibility = View.VISIBLE
            holder.num.setPadding(0, 0, 0, 0)
            holder.num.text = "See all"
            holder.num.setTextColor(if (glass) theme.color("inkOnWall", "#FFFFFF") else theme.ink2)
            holder.num.gravity = Gravity.CENTER
            holder.num.paint.style = android.graphics.Paint.Style.FILL
            holder.num.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            holder.num.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, theme.px(21f).toFloat())
            holder.num.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = (cardH - theme.px(78f)) / 2 + theme.px(64f)
            }
            holder.itemView.setOnClickListener { openRowFull(row) }
        }

        /**
         * A genre has no poster of its own — the hub sends null art for these tiles, and an empty
         * plate with a badge read as a broken card. So the tile IS the artwork: a diagonal colour
         * wash keyed to the genre's name (every shelf shows the same tile in the same colours),
         * with the name set large inside it, as Apple TV's category tiles are.
         */
        private fun bindGenre(holder: CardHolder, item: Item) {
            holder.itemView.tag = "genre"
            holder.itemView.contentDescription = "Browse ${item.title}"
            var hash = 0
            for (c in item.title.lowercase()) hash = (hash * 31 + c.code) % 360
            val hue = hash.toFloat()
            // The tile is a wash, not a poster. Retag the recycled views first so a late image
            // callback for the card they used to hold cannot show through the colour plate.
            holder.art.tag = "genre:" + item.identity
            holder.logo.tag = "genre:" + item.identity
            holder.art.setImageDrawable(null)
            holder.art.setBackgroundColor(Color.TRANSPARENT)
            // The Glass pack never prints an opaque plate: the genre's own colour wash sits at
            // translucent alpha over the wall with the pack's rim + sheen, and the tile's
            // elevation casts its shadow — same three material layers as every Glass surface.
            holder.art.background = if (glass) GlassKit.tilePlate(app, theme.radius("card", 6f),
                Color.HSVToColor(0xA8, floatArrayOf(hue, 0.55f, 0.78f)),
                Color.HSVToColor(0x88, floatArrayOf((hue + 42f) % 360f, 0.80f, 0.42f)))
            else GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.78f)),
                    Color.HSVToColor(floatArrayOf((hue + 42f) % 360f, 0.80f, 0.42f)),
                ),
            )
            holder.name.visibility = View.GONE
            holder.sub.visibility = View.GONE
            holder.logo.visibility = View.GONE
            holder.badge.visibility = View.GONE
            holder.corner.visibility = View.GONE
            holder.progress.visibility = View.GONE
            holder.slideState?.visibility = View.GONE
            holder.chevron.visibility = View.GONE
            holder.num.visibility = View.VISIBLE
            holder.num.text = item.title
            holder.num.setTextColor(Color.WHITE)
            holder.num.gravity = Gravity.CENTER
            holder.num.maxLines = 2
            holder.num.setPadding(theme.px(16f), 0, theme.px(16f), 0)
            holder.num.paint.style = android.graphics.Paint.Style.FILL
            holder.num.setShadowLayer(theme.px(10f).toFloat(), 0f, theme.px(2f).toFloat(), Color.argb(160, 0, 0, 0))
            holder.num.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, cardH * 0.22f)
            holder.num.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, cardH
            ).apply { gravity = Gravity.TOP }
            // A genre tile opens the genre's own full list, not a title-word search for "crime".
            // A catalogue tile (from the Browse addons row) opens that addon's catalogue instead.
            holder.itemView.setOnClickListener {
                val intent = Intent(this@BrowseActivity, SectionActivity::class.java)
                    .putExtra(SectionActivity.EXTRA_PROFILE, profileId)
                    .putExtra(SectionActivity.EXTRA_TITLE, item.title)
                if (item.identity.startsWith("catalog:")) {
                    val rest = item.identity.removePrefix("catalog:")
                    intent.putExtra(SectionActivity.EXTRA_ADDON, rest.substringBefore(':'))
                    intent.putExtra(SectionActivity.EXTRA_CATALOG, rest.substringAfter(':', ""))
                } else {
                    intent.putExtra(SectionActivity.EXTRA_GENRE, item.title)
                }
                startActivity(intent)
            }
        }
    }

    private inner class CardHolder(
        view: View, val art: ImageView, val name: TextView, val sub: TextView, val logo: ImageView,
        val badge: TextView, val num: TextView, val progress: android.widget.ProgressBar,
        val slideState: View?, val imdb: TextView, val cert: TextView, val corner: View,
        val chevron: ImageView,
    ) : RecyclerView.ViewHolder(view) {
        var item: Item? = null
    }

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
