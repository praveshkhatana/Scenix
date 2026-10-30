package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
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
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Item
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
    private var stage: Stage? = null
    private var heroItem: Item? = null
    // "" = Home; "movies"/"series" open the same surface filtered to a section. The pill stays on
    // screen across those switches, so one LEFT/RIGHT walks whole sections without a trip back.
    private var section = ""
    private var skeleton: View? = null
    private var contentRoot: ViewGroup? = null

    // The hero and the first shelf are ONE section, the way Apple's shelf-top works: the slides the
    // hero shows are the tiles of the Featured row, and whichever side moves, the other follows.
    private var slides: List<Item> = emptyList()
    private var heroPos = 0
    private var browseAdapter: BrowseAdapter? = null
    private var liveTab: View? = null
    // The pill floats over a full-bleed stage, so geometric focus search can't be trusted to walk
    // from a tile up into it. One named anchor on the pill and one on the list make the route
    // explicit: UP from the featured tiles lands on the bar, DOWN from the bar returns to the top.
    private var navAnchor: View? = null
    private var pillGroup: ViewGroup? = null
    private var shelfListId = View.NO_ID
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
            if (slides.size > 1 && !watching && idleFor > 9_000L)
                setHero((heroPos + 1) % slides.size)
            clock.postDelayed(this, app.theme.token("hero", "rotateMs", 6000.0).toLong())
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            lastKeyAt = android.os.SystemClock.elapsedRealtime()
            // The top of the screen — the billboard or a Featured tile — answers UP with the nav pill,
            // one press in, one press back down. Nested list geometry alone never routes there.
            val anchor = navAnchor
            if (anchor != null && event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP && onTopRow(currentFocus)) {
                anchor.requestFocus()
                return true
            }
            // DOWN out of the pill lands on the Featured tile the billboard is showing, not blindly
            // on the first one — the banner and its tiles are one object, and entering it should
            // stand on the slide you can see.
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN &&
                slides.isNotEmpty() && isInside(pillGroup, currentFocus)) {
                focusFeatured(heroPos.coerceIn(0, slides.size - 1))
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isInside(container: ViewGroup?, v: View?): Boolean {
        if (container == null || v == null) return false
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
        if (f === adapter.heroPlay || f === adapter.heroRoot) return true
        val rail = adapter.featuredRail ?: return false
        var p: android.view.ViewParent? = f.parent
        while (p != null) {
            if (p === rail) return true
            p = p.parent
        }
        return false
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

        // Apple's scene pack puts one stage behind the whole screen: the shelves float on it, and the
        // stage crossfades to whatever tile holds focus. Other packs keep the opaque page they had.
        val stageMode = app.theme.packString("stage", "off") == "focus"
        val content: ViewGroup = if (stageMode) FrameLayout(this) else LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(app.theme.bg)
        }
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
            setBackgroundColor(if (stageMode) Color.TRANSPARENT else app.theme.bg)
            itemAnimator = null
            clipToPadding = false
            // Under a floating bar the billboard runs to the top edge of the screen, so the rows
            // must not be pushed down by a padding band — other packs keep their page padding.
            setPadding(0, if (stageMode) 0 else app.theme.px(16f), 0, app.theme.px(64f))
        }
        shelfListId = View.generateViewId()
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
        } else {
            content.addView(topBar(false), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(96f)
            ))
            content.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            content.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(80f)))
        }
        setContentView(root)
        load()
    }

    /**
     * The Apple scene stage: one full-screen backdrop for the whole screen, crossfading to the tile
     * under the ring and dimming/blurring while the viewer walks the shelves. Two ImageViews are
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
                intArrayOf(Color.argb(120, 0, 0, 0), Color.argb(30, 0, 0, 0), Color.argb(215, 0, 0, 0))
            )
            alpha = 0.84f
        }
        private var front = a
        private var current: String? = null

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
            BrowseImages.load(incoming, clean, app.theme.px(1400f)) { ok ->
                if (isFinishing || isDestroyed) return@load
                if (!ok || current != clean) return@load
                incoming.animate().alpha(1f).setDuration(650).withEndAction {
                    front.alpha = 0f
                    front = incoming
                }.start()
            }
        }

        fun setRoaming(roam: Boolean) {
            // The room dims while the viewer walks the shelves — the veil and the dimmed
            // neighbours land instantly, which is what the eye reads as the change. A frosted
            // blur over the backdrop (RenderEffect) was tried and removed: on the emulator's
            // translated GL it kept the whole window re-rendering for a beat after every hop
            // (62 composited frames per walk vs 16) and the first blurred frame stalled a second
            // and a half mid-step — exactly the freeze between the hero and the shelves. Apple's
            // own billboard keeps the art sharp while browsing too.
            veil.animate().alpha(if (roam) 1f else 0.84f).setDuration(350).start()
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
                    for (k in 0 until rail.childCount) block(rail.getChildAt(k))
                }
            }
        }
    }

    private fun onCardFocused(card: View, item: Item) {
        // Walking away from the billboard takes its preview with you — but releasing the media
        // player blocks the main thread for a beat, and doing it inside the keypress dropped half
        // a second of frames between the hero and the shelves. The surface comes down now, which
        // is all the eye sees; the release lands once the step has finished drawing.
        clock.removeCallbacks(trailerTick)
        clock.removeCallbacks(trailerRelease)
        hideTrailerNow()
        clock.postDelayed(trailerRelease, 250L)
        val s = stage ?: return
        s.show(item.backdrop ?: item.poster ?: item.logo)
        s.setRoaming(true)
        eachCard { it.alpha = if (it === card) 1f else 0.58f }
    }

    /**
     * A tile of the Featured row holds focus: the hero above it follows to that slide, and the room
     * stays lit — walking the hero's own section is browsing the hero, not roaming the shelves.
     */
    private fun onSlideFocused(pos: Int) {
        rotationPaused = true
        // Entering the hero's group always shows the whole hero: the banner and its tiles are one
        // section, and a banner scrolled off the top breaks that.
        list.smoothScrollToPosition(0)
        setHero(pos)
        scheduleTrailer()
        val s = stage ?: return
        s.setRoaming(false)
        eachCard { it.alpha = 1f }
    }

    private fun setHero(pos: Int) {
        if (slides.isEmpty()) return
        val prev = heroPos
        heroPos = pos.coerceIn(0, slides.size - 1)
        val item = slides[heroPos]
        heroItem = item
        browseAdapter?.showSlide(heroPos)
        browseAdapter?.syncFeaturedSlide(prev, heroPos)
        stage?.show(item.backdrop ?: item.poster ?: item.logo)
        // The trailer belongs to one slide at a time; moving the billboard moves or stops it.
        clock.removeCallbacks(trailerTick)
        stopTrailer()
    }

    private val trailerTick = Runnable { loadTrailer() }

    /** A preview starts a beat after attention settles on a slide, not mid-step. */
    private fun scheduleTrailer() {
        clock.removeCallbacks(trailerTick)
        clock.postDelayed(trailerTick, 1000L)
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
                if (video is CoverVideoView) {
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
        rotationPaused = true
        val s = stage ?: return
        s.setRoaming(false)
        s.show(heroItem?.backdrop ?: heroItem?.poster)
        eachCard { it.alpha = 1f }
    }

    private fun topBar(transparent: Boolean = false): View {
        val theme = app.theme
        val brand = Views.text(app, "logo", 30f, theme.brand, "SCENIX", bold = true)
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
            // bar teaches: face, Home, Movies, Series, Live TV, then the actions.
            val bar = FrameLayout(this)
            bar.setBackgroundColor(if (transparent) Color.TRANSPARENT else theme.bg)
            bar.addView(brand, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
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
            pill.addView(profileDisc())
            val homeTab = navTab("Home", active = section.isBlank()) { goTab("") }
            val moviesTab = navTab("Movies", active = section == "movies") { goTab("movies") }
            val seriesTab = navTab("Series", active = section == "series") { goTab("series") }
            pill.addView(homeTab)
            pill.addView(moviesTab)
            pill.addView(seriesTab)
            val live = navTab("Live TV") {
                startActivity(
                    Intent(this@BrowseActivity, LiveChannelsActivity::class.java)
                        .putExtra(LiveChannelsActivity.EXTRA_PROFILE, profileId)
                )
            }
            // Live TV earns its place in the pill: it shows once the hub actually carries the live
            // row, and stays out of the way of households that have not configured it. The answer
            // outlives a tab switch so the bar never changes shape mid-walk.
            liveTab = live
            live.visibility = if (liveSeen) View.VISIBLE else View.GONE
            pill.addView(live)
            pill.addView(View(this).apply { setBackgroundColor(theme.line) },
                LinearLayout.LayoutParams(theme.px(1f), theme.px(26f)).apply {
                    marginStart = theme.px(10f); marginEnd = theme.px(4f)
                })
            // Search and Settings ride as icons in the pill, as Apple's bar and the prototype draw them.
            pill.addView(navIcon(R.drawable.ic_nav_search, "Search") {
                startActivity(
                    Intent(this@BrowseActivity, SearchActivity::class.java)
                        .putExtra(SearchActivity.EXTRA_PROFILE, profileId)
                )
            })
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
            bar.addView(pill, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER })
            // DOWN from anywhere on the bar returns to the top of the shelves.
            for (i in 0 until pill.childCount) pill.getChildAt(i).nextFocusDownId = shelfListId
            return bar
        }
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(theme.gutter, 0, theme.gutter, 0)
        bar.setBackgroundColor(if (transparent) Color.TRANSPARENT else theme.bg)
        bar.addView(brand, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(navButton("Movies") { goTab("movies") },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = theme.px(22f)
            })
        bar.addView(navButton("Series") { goTab("series") },
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

    /** A section of the pill. The one you are standing on wears the white face; the rest wait as text. */
    private fun navTab(label: String, active: Boolean = false, action: () -> Unit): View {
        return navButton(label, app.theme.px(4f), preAttach = if (active) {
            { b ->
                b.setTextColor(Color.BLACK)
                b.background = Views.rounded(Color.argb(242, 242, 243, 245), app.theme.px(999f))
            }
        } else null, action = action)
    }

    /** The pill's icon actions: a tinted glyph on a round focus pad, same focus language as the tabs. */
    private fun navIcon(res: Int, desc: String, action: () -> Unit): View {
        val theme = app.theme
        val iv = ImageView(this).apply {
            setImageResource(res)
            contentDescription = desc
            setColorFilter(theme.ink)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        iv.isFocusable = true
        iv.isClickable = true
        iv.setOnClickListener { action() }
        // The icon gets the same explicit ring as the tabs: on a 30 px glyph a soft halo band is
        // invisible, and "is the ring here or not" has to answer at a glance.
        fun paintChrome(focused: Boolean) {
            iv.background = if (focused) Views.rounded(
                Color.argb(36, 255, 255, 255), theme.px(999f),
                theme.px(2f), Color.argb(235, 255, 255, 255)) else null
        }
        paintChrome(false)
        Focus.attach(iv, null, app, theme.px(9f), scale = false, elevate = false,
            onFocus = { f -> if (f) onChromeFocused(); paintChrome(f) })
        iv.layoutParams = LinearLayout.LayoutParams(theme.px(30f), theme.px(30f)).apply {
            marginStart = theme.px(10f); marginEnd = theme.px(10f)
        }
        return iv
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

    private fun load() {
        showSkeleton(true)
        if (section.isNotBlank()) {
            api.row(profileId, section) { _, items, error ->
                if (isFinishing || isDestroyed) return@row
                if (items.isNullOrEmpty()) {
                    showSkeleton(false)
                    status.visibility = View.VISIBLE
                    status.text = error ?: "Nothing in this section yet"
                    return@row
                }
                loaded = true
                present(deriveShelves(section, items))
            }
            return
        }
        api.load(profileId) { rows, error ->
            if (isFinishing || isDestroyed) return@load
            if (rows == null) {
                loaded = false
                showSkeleton(false)
                status.visibility = View.VISIBLE
                status.text = error ?: "The hub is not answering"
                return@load
            }
            loaded = true
            // The pill's Live TV tab appears only for households whose hub actually carries it.
            liveSeen = rows.any { it.id == "live" || it.card == "channel" }
            liveTab?.visibility = if (liveSeen) View.VISIBLE else View.GONE
            // Live has a whole tab and a wall of its own; the Home shelves are for films and shows,
            // so the old live strip is no longer parked on the front page.
            val others = rows.filter {
                it.id != "hero" && it.items.isNotEmpty() && it.id != "live" && it.card != "channel"
            }
            // The hub sometimes sends the hero as its own row and sometimes folds the featured
            // titles into the first shelf. Either way Home opens on a hero — the viewer's first
            // sight on this set is the featured title, never a bare shelf head — and the hero's
            // slides are exactly the tiles of the Featured row that sits under it.
            val maxSlides = app.theme.token("hero", "maxItems", 5.0).toInt().coerceAtLeast(1)
            val heroRow = rows.firstOrNull { it.id == "hero" }
            val shelves: List<BrowseRow>
            if (heroRow != null && heroRow.items.isNotEmpty()) {
                slides = heroRow.items.take(maxSlides)
                shelves = listOf(BrowseRow("featured", "Featured on Scenix", "poster", slides)) + others
            } else {
                val first = others.firstOrNull()
                if (first == null) {
                    slides = emptyList()
                    shelves = others
                } else {
                    slides = first.items.take(maxSlides)
                    shelves = listOf(BrowseRow("featured", "Featured on Scenix", first.card, slides)) +
                        others.drop(1)
                }
            }
            present(shelves)
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
        if (slides.isNotEmpty()) out.add(BrowseRow("featured", "Featured on $label", "poster", slides))
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
        return out
    }

    /** Put a finished set of shelves on screen: hero, stage, rails, rotation and first focus. */
    private fun present(shelves: List<BrowseRow>) {
        showSkeleton(false)
        status.visibility = View.GONE
        heroPos = 0
        heroItem = slides.firstOrNull()
        stage?.show(heroItem?.backdrop ?: heroItem?.poster
            ?: shelves.firstOrNull()?.items?.firstOrNull()?.backdrop)
        val adapter = BrowseAdapter(heroItem, shelves)
        browseAdapter = adapter
        list.adapter = adapter
        clock.removeCallbacks(rotateHero)
        rotationPaused = false
        if (slides.size > 1) {
            clock.postDelayed(rotateHero, app.theme.token("hero", "rotateMs", 6000.0).toLong())
        }
        // The first tile takes initial focus, but only once the rails have actually laid out
        // their children — on a cold load that can be well after the data lands, so retry.
        list.postDelayed(object : Runnable {
            var tries = 0
            override fun run() {
                val first = adapter.focusFirstSlide() ?: adapter.heroPlay ?: firstFocusable(list)
                if (first?.requestFocus() == true) return
                if (++tries < 20) list.postDelayed(this, 300)
            }
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

    /** Move focus to a Featured tile by position, scrolling the rail if the tile is off-screen. */
    private fun focusFeatured(pos: Int) {
        val rail = browseAdapter?.featuredRailView() ?: return
        rail.scrollToPosition(pos)
        rail.post {
            val target = rail.findViewHolderForAdapterPosition(pos)?.itemView
                ?: rail.getChildAt(if (pos == 0) 0 else (rail.childCount - 1).coerceAtLeast(0))
            target?.requestFocus()
        }
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
        if (id.startsWith("genre:") || id.startsWith("lang:")) {
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

    private inner class BrowseAdapter(
        private val hero: Item?,
        private val shelves: List<BrowseRow>,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        var heroPlay: View? = null
            private set
        var heroRoot: View? = null
            private set
        private var heroHolder: HeroHolder? = null
        // The rail whose tiles ARE the hero's slides; the hero follows its focused tile and the
        // current slide keeps a rim even when focus walks away, so the two never drift apart.
        var featuredCards: CardAdapter? = null
            private set
        var featuredRail: RecyclerView? = null
            private set

        /** There is no separate hero stop: a landing on Home stands on the Featured row, as Apple's does. */
        fun focusFirstSlide(): View? = featuredRail?.getChildAt(0)

        fun heroVideo(): android.widget.VideoView? = heroHolder?.video

        fun heroSubs(): TextView? = heroHolder?.subs

        fun featuredRailView(): RecyclerView? = featuredRail

        fun showSlide(pos: Int) {
            heroPos = pos.coerceIn(0, slides.size - 1)
            heroHolder?.let { renderHero(it) }
        }

        fun syncFeaturedSlide(prev: Int, next: Int) {
            val rail = featuredCards ?: return
            if (prev == next) return
            // Rebind only the two tiles whose rim state changed; the rest of the shelf — and the
            // ring's focus — stay exactly where they are.
            if (prev in 0 until rail.itemCount) rail.notifyItemChanged(prev)
            if (next in 0 until rail.itemCount) rail.notifyItemChanged(next)
        }

        override fun getItemCount(): Int = (if (hero != null) 1 else 0) + shelves.size

        override fun getItemViewType(position: Int): Int =
            if (hero != null && position == 0) TYPE_HERO else TYPE_SHELF

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val theme = app.theme
            return if (viewType == TYPE_HERO) {
                val slot = FrameLayout(this@BrowseActivity)
                val heroStyle = theme.heroStyle
                val split = heroStyle == "split"
                val centred = heroStyle == "centred" || heroStyle == "centered" || theme.heroAlign == "center"
                slot.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (split) theme.heroHeightPx else theme.heroHeightPx.coerceAtLeast(theme.px(540f))
                )
                val art = ImageView(this@BrowseActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(theme.surface2)
                }
                slot.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                // The trailer plays inside the banner, behind the words and the scrim, muted and
                // looping — the home billboard's rule, not the player's. It fills the banner the way
                // the backdrop does (cover, not letterbox), and it must not be a focus stop: the
                // tile rows below are the conversation, not an invisible rectangle over the hero.
                val video = CoverVideoView(this@BrowseActivity).apply {
                    visibility = View.GONE
                    setBackgroundColor(Color.TRANSPARENT)
                    isFocusable = false
                    isClickable = false
                }
                slot.addView(video, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ).apply { gravity = Gravity.CENTER })
                val scrim = View(this@BrowseActivity).apply {
                    background = if (split) GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(theme.bg, Color.TRANSPARENT)
                    ) else GradientDrawable(
                        GradientDrawable.Orientation.BOTTOM_TOP,
                        intArrayOf(theme.bg, Color.TRANSPARENT)
                    )
                }
                slot.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                // The trailer's subtitles, the same timed-text the player paints: only ever shown
                // when the clip itself carries a caption track.
                val subs = Views.text(app, "sub", 21f, Color.WHITE, "", bold = false,
                    maxLines = 2, gravity = Gravity.CENTER)
                subs.setPadding(theme.px(12f), theme.px(5f), theme.px(12f), theme.px(5f))
                subs.background = Views.rounded(Color.argb(165, 0, 0, 0), theme.px(6f))
                subs.visibility = View.GONE
                slot.addView(subs, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER
                    bottomMargin = theme.px(118f)
                    marginStart = theme.px(160f); marginEnd = theme.px(160f)
                })
                // Editorial packs put the poster in the frame and the words beside it; classic packs let
                // the backdrop fill the screen and float the words over it. The poster view exists in
                // both, hidden when the pack does not ask for it.
                val poster = ImageView(this@BrowseActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(theme.surface2)
                    visibility = if (split) View.VISIBLE else View.GONE
                }
                if (split) {
                    slot.addView(poster, FrameLayout.LayoutParams(theme.px(300f), theme.px(450f)).apply {
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        leftMargin = theme.gutter
                    })
                }
                val words = LinearLayout(this@BrowseActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = when {
                        centred -> Gravity.CENTER
                        split -> Gravity.CENTER_VERTICAL or Gravity.START
                        else -> Gravity.BOTTOM or Gravity.START
                    }
                    setPadding(
                        if (split) theme.gutter + theme.px(340f) else theme.gutter,
                        theme.px(32f), theme.gutter, theme.px(44f)
                    )
                }
                val title = Views.text(app, "h1", 54f, theme.ink, "", bold = true,
                    gravity = if (centred) Gravity.CENTER else Gravity.START)
                val logo = ImageView(this@BrowseActivity).apply {
                    scaleType = if (centred) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.FIT_START
                    visibility = View.GONE
                }
                val meta = Views.text(app, "sub", 22f, theme.ink2, "",
                    gravity = if (centred) Gravity.CENTER else Gravity.START)
                val play = Views.text(app, "button", 22f, theme.onBrand, "Play", bold = true, gravity = Gravity.CENTER)
                play.setPadding(theme.px(32f), theme.px(14f), theme.px(32f), theme.px(14f))
                play.background = Views.rounded(theme.brand, theme.radius("button", 6f))
                play.isFocusable = true
                play.isClickable = true
                Focus.attach(play, play, app, theme.radius("button", 6f), scale = false, elevate = false,
                    onFocus = { if (it) onChromeFocused() })
                words.addView(logo, LinearLayout.LayoutParams(theme.px(560f), theme.px(130f)).apply {
                    bottomMargin = theme.px(6f)
                    if (centred) gravity = Gravity.CENTER_HORIZONTAL
                })
                words.addView(title, LinearLayout.LayoutParams(
                    if (centred) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                words.addView(meta, LinearLayout.LayoutParams(
                    if (centred) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = theme.px(10f)
                })
                words.addView(play, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = theme.px(22f)
                })
                slot.addView(words, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                // The dots say how many slides the hero carries — and they match the Featured row
                // tile for tile, because the row IS the hero's slide list laid out on the floor.
                val dots = LinearLayout(this@BrowseActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    visibility = if (slides.size > 1) View.VISIBLE else View.GONE
                }
                for (i in slides.indices) {
                    dots.addView(View(this@BrowseActivity), LinearLayout.LayoutParams(
                        theme.px(10f), theme.px(10f)
                    ).apply {
                        leftMargin = theme.px(4f); rightMargin = theme.px(4f)
                    })
                }
                slot.addView(dots, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = theme.px(26f)
                })
                HeroHolder(slot, art, video, subs, poster, title, logo, meta, play, dots, split)
            } else {
                val box = LinearLayout(this@BrowseActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setPadding(0, theme.px(18f), 0, 0)
                }
                val head = Views.text(app, "rowTitle", 28f, theme.ink, "", bold = true)
                head.setPadding(theme.gutter, 0, theme.gutter, theme.px(12f))
                box.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val rail = RailView(this@BrowseActivity).apply {
                    layoutManager = LinearLayoutManager(this@BrowseActivity, LinearLayoutManager.HORIZONTAL, false)
                    setHasFixedSize(true)
                    setItemViewCacheSize(16)
                    itemAnimator = null
                    isNestedScrollingEnabled = false
                    setPadding(theme.gutter, theme.px(18f), theme.gutter, theme.px(26f))
                    clipToPadding = false
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
                val item = slides[heroPos]
                heroPlay = holder.play
                heroRoot = holder.itemView
                holder.play.nextFocusUpId = navAnchor?.id ?: View.NO_ID
                holder.itemView.nextFocusUpId = navAnchor?.id ?: View.NO_ID
                holder.play.setOnClickListener { openDetail(slides.getOrElse(heroPos) { item }) }
                holder.itemView.setOnClickListener { openDetail(slides.getOrElse(heroPos) { item }) }
            } else if (holder is ShelfHolder) {
                val row = shelves[position - (if (hero != null) 1 else 0)]
                holder.head.text = row.title
                val cards = CardAdapter(row)
                featuredCards = if (row.id == "featured") cards else null
                featuredRail = if (row.id == "featured") holder.rail else featuredRail
                holder.rail.adapter = cards
            }
        }

        /** Paint the hero block with the current slide — title, facts, art and the dot strip. */
        private fun renderHero(holder: HeroHolder) {
            val item = slides.getOrNull(heroPos) ?: return
            holder.title.text = item.title
            holder.title.visibility = View.VISIBLE
            holder.logo.visibility = View.GONE
            val facts = ArrayList<String>()
            if (item.match > 0) facts.add("${item.match}% match")
            if (item.isNew) facts.add("New")
            if (item.year > 0) facts.add(item.year.toString())
            if (item.rating > 0) facts.add(String.format(java.util.Locale.US, "★ %.1f", item.rating))
            if (item.maturity.isNotBlank()) facts.add(item.maturity)
            item.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let { facts.add(it) }
            holder.meta.text = facts.joinToString(" · ")
            // The banner shows the wide card — the same picture the stage carries — never a portrait
            // poster stretched across the frame.
            BrowseImages.load(holder.art, item.backdrop ?: item.poster, app.theme.px(1400f))
            if (holder.split) BrowseImages.load(holder.poster, item.poster ?: item.backdrop, app.theme.px(420f))
            if (!item.logo.isNullOrBlank()) {
                holder.logo.visibility = View.VISIBLE
                BrowseImages.load(holder.logo, item.logo, app.theme.px(560f)) { ok ->
                    holder.logo.visibility = if (ok) View.VISIBLE else View.GONE
                    holder.title.visibility = if (ok) View.GONE else View.VISIBLE
                }
            }
            // The slide under the ring sits as a long dash; the others wait as plain dots.
            val theme = app.theme
            for (i in 0 until holder.dots.childCount) {
                val dot = holder.dots.getChildAt(i)
                (dot.layoutParams as LinearLayout.LayoutParams).width =
                    theme.px(if (i == heroPos) 26f else 10f)
                dot.requestLayout()
                dot.background = Views.rounded(
                    if (i == heroPos) Color.WHITE else Color.argb(90, 255, 255, 255), theme.px(5f))
            }
        }
    }

    private inner class HeroHolder(
        view: View, val art: ImageView, val video: android.widget.VideoView, val subs: TextView,
        val poster: ImageView, val title: TextView, val logo: ImageView,
        val meta: TextView, val play: TextView, val dots: LinearLayout, val split: Boolean,
    ) : RecyclerView.ViewHolder(view)

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
        private val glass = theme.cardStyle == "glass"
        // Apple's scene cards keep the name under the art instead of over it.
        private val below = theme.cardStyle == "below"
        private val captionH = when {
            below -> theme.px(62f)
            frame -> theme.px(56f)
            else -> 0
        }
        private val cardW = theme.px(if (landscape) 480f else if (poster) 280f else 320f)
        private val cardH = theme.px(if (landscape) 270f else if (poster) 420f else 180f)
        private val radius = theme.radius("card", 6f)
        // When the shelf is a slice of a bigger list, its end carries one more tile: the full list.
        private val seeAll = !featured && row.total > row.items.size

        override fun getItemCount(): Int = row.items.size + if (seeAll) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
            val card = FrameLayout(this@BrowseActivity)
            card.layoutParams = RecyclerView.LayoutParams(cardW, cardH + captionH).apply {
                // Apple's shelves breathe; tiles never touch each other.
                marginEnd = theme.px(26f)
            }
            card.isFocusable = true
            card.isClickable = true
            card.setBackgroundColor(if (frame) theme.surface else Color.TRANSPARENT)
            val art = ImageView(this@BrowseActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(theme.surface2)
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, v.width, v.height, theme.radius("card", 6f).toFloat())
                    }
                }
            }
            card.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardH).apply {
                gravity = Gravity.TOP
            })
            if (!frame && !below) {
                val shade = View(this@BrowseActivity).apply {
                    background = GradientDrawable(
                        GradientDrawable.Orientation.BOTTOM_TOP,
                        intArrayOf(Color.argb(if (glass) 190 else 235, 0, 0, 0), Color.TRANSPARENT)
                    )
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
            } else if (glass) {
                card.addView(View(this@BrowseActivity).apply {
                    background = Views.rounded(Color.argb(150, 8, 10, 14),
                        theme.radius("card", 6f), theme.px(1f), Color.argb(90, 255, 255, 255))
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(84f)).apply {
                    gravity = Gravity.BOTTOM
                    leftMargin = theme.px(8f); rightMargin = theme.px(8f); bottomMargin = theme.px(8f)
                })
            }
            val name = Views.text(app, "caption", if (below) 18f else 19f, theme.ink, "", bold = true,
                maxLines = if (below) 1 else 2)
            name.setPadding(theme.px(if (below) 4f else 12f), 0,
                theme.px(if (below) 4f else 12f), theme.px(if (below) 0f else 10f))
            card.addView(name, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = when {
                    below -> theme.px(26f)
                    frame -> theme.px(8f)
                    else -> 0
                }
            })
            val sub = Views.text(app, "meta", 14f, theme.ink3, "", maxLines = 1)
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
            val badge = Views.text(app, "meta", 13f, Color.WHITE, "", bold = true, maxLines = 1)
            badge.setPadding(theme.px(8f), theme.px(3f), theme.px(8f), theme.px(3f))
            badge.background = Views.rounded(Color.argb(185, 8, 8, 8), theme.px(5f))
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
                setColorFilter(theme.ink, android.graphics.PorterDuff.Mode.SRC_IN)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(theme.px(20f), theme.px(20f), theme.px(20f), theme.px(20f))
                background = Views.rounded(theme.surface2, theme.px(999f))
                visibility = View.GONE
            }
            card.addView(chevron, FrameLayout.LayoutParams(theme.px(78f), theme.px(78f)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = (cardH - theme.px(78f)) / 2 - theme.px(24f)
            })
            // IMDb rating and the certificate ride the top-right corner, gold star and plain badge,
            // exactly as the pack's card design drew them.
            val imdb = Views.text(app, "meta", 13f, Color.parseColor("#F6C621"), "", bold = true, maxLines = 1)
            imdb.setPadding(theme.px(8f), theme.px(3f), theme.px(8f), theme.px(3f))
            imdb.background = Views.rounded(Color.argb(185, 8, 8, 8), theme.px(5f))
            val cert = Views.text(app, "meta", 13f, Color.WHITE, "", bold = true, maxLines = 1)
            cert.setPadding(theme.px(8f), theme.px(3f), theme.px(8f), theme.px(3f))
            cert.background = Views.rounded(Color.argb(185, 8, 8, 8), theme.px(5f))
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
                progressDrawable = GradientDrawable().apply {
                    setColor(Color.argb(210, 229, 9, 20))
                    cornerRadius = theme.px(3f).toFloat()
                }
                visibility = View.GONE
            }
            card.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(6f)).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = captionH
            })
            val slideRing = if (featured) View(this@BrowseActivity).apply {
                // The tile that the hero is currently showing wears a rim even when the ring is
                // elsewhere — the prototype's slide-on outline, so the pair reads as one object.
                background = Views.ring(Color.argb(140, 255, 255, 255), radius, theme.px(2f))
                visibility = View.GONE
            } else null
            if (slideRing != null) card.addView(slideRing,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardH).apply {
                    gravity = Gravity.TOP
                })
            // The focus ring belongs to the ART, not to the caption under it: the ring view is an
            // oversized transparent pad around the image, and the glow bands spill into that pad.
            val glowPad = theme.px(26f)
            val ringOn = View(this@BrowseActivity)
            card.addView(ringOn, FrameLayout.LayoutParams(cardW + glowPad * 2, cardH + glowPad * 2).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = -glowPad
                topMargin = -glowPad
            })
            card.clipChildren = false
            val holder = CardHolder(card, art, name, sub, logo, badge, num, progress, slideRing, imdb, cert, corner, chevron)
            Focus.attach(card, ringOn, app, radius, animateScale = false, elevate = false,
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
            if (seeAll && position >= row.items.size) {
                bindSeeAll(holder)
                return
            }
            val item = row.items[position]
            holder.item = item
            if (item.type == "genre") {
                bindGenre(holder, item)
                return
            }
            holder.chevron.visibility = View.GONE
            if (featured) holder.itemView.nextFocusUpId = navAnchor?.id ?: View.NO_ID
            holder.name.text = item.title
            holder.name.visibility = View.VISIBLE
            holder.sub.visibility = if (below) View.VISIBLE else View.GONE
            if (below) {
                val sub = ArrayList<String>()
                if (item.year > 0) sub.add(item.year.toString())
                if (item.match > 0) sub.add("${item.match}% match")
                item.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let { sub.add(it) }
                holder.sub.text = sub.joinToString(" · ")
            }
            holder.logo.visibility = View.GONE
            val imdbR = if (item.rating > 0) String.format(java.util.Locale.US, "★ %.1f", item.rating) else ""
            holder.imdb.text = imdbR
            holder.imdb.visibility = if (imdbR.isNotEmpty()) View.VISIBLE else View.GONE
            holder.cert.text = item.maturity
            holder.cert.visibility = if (item.maturity.isNotBlank()) View.VISIBLE else View.GONE
            holder.corner.visibility =
                if (imdbR.isNotEmpty() || item.maturity.isNotBlank()) View.VISIBLE else View.GONE
            val facts = ArrayList<String>()
            if (item.isNew) facts.add("NEW")
            if (item.match > 0) facts.add("${item.match}% match")
            holder.badge.text = facts.joinToString(" · ")
            holder.badge.visibility = if (facts.isEmpty()) View.GONE else View.VISIBLE
            BrowseImages.load(holder.art,
                if (landscape) item.backdrop ?: item.poster else item.poster ?: item.backdrop, cardW)
            if (!item.logo.isNullOrBlank()) {
                holder.logo.visibility = View.VISIBLE
                BrowseImages.load(holder.logo, item.logo, cardW) { ok ->
                    holder.logo.visibility = if (ok) View.VISIBLE else View.GONE
                    // A scene card keeps its name under the art even when the art carries a logo.
                    holder.name.visibility = if (ok && !below) View.GONE else View.VISIBLE
                }
            }
            if (ranked) {
                // A recycled tile may have worn the See all lockup; the rank column rides the left edge.
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
            }
            val p = item.progress
            if (p != null && p.watchedMs > 0L) {
                holder.progress.visibility = View.VISIBLE
                holder.progress.progress = ((p.positionMs.toFloat() / p.watchedMs.toFloat()).coerceIn(0f, 1f) * 1000).toInt()
            } else {
                holder.progress.visibility = View.GONE
            }
            holder.slideRing?.visibility = if (position == heroPos) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener {
                if (item.isLive()) openLive(item) else openDetail(item)
            }
        }

        /** The shelf's last tile: a quiet bordered card with a chevron disc and the words "See all". */
        private fun bindSeeAll(holder: CardHolder) {
            holder.item = null
            holder.art.setImageDrawable(null)
            holder.art.setBackgroundColor(Color.TRANSPARENT)
            holder.art.background = Views.rounded(theme.surface, theme.radius("card", 6f), theme.px(1f), theme.line)
            holder.name.text = row.title
            holder.name.visibility = View.VISIBLE
            holder.sub.visibility = View.GONE
            holder.logo.visibility = View.GONE
            holder.corner.visibility = View.GONE
            holder.badge.visibility = View.GONE
            holder.progress.visibility = View.GONE
            holder.chevron.visibility = View.VISIBLE
            holder.num.visibility = View.VISIBLE
            holder.num.setPadding(0, 0, 0, 0)
            holder.num.text = "See all"
            holder.num.setTextColor(theme.ink2)
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
            var hash = 0
            for (c in item.title.lowercase()) hash = (hash * 31 + c.code) % 360
            val hue = hash.toFloat()
            holder.art.setImageDrawable(null)
            holder.art.setBackgroundColor(Color.TRANSPARENT)
            holder.art.background = GradientDrawable(
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
            holder.slideRing?.visibility = View.GONE
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
            // A genre tile opens the genre's own full list, not a detail page for "genre:drama".
            holder.itemView.setOnClickListener {
                startActivity(
                    Intent(this@BrowseActivity, SectionActivity::class.java)
                        .putExtra(SectionActivity.EXTRA_PROFILE, profileId)
                        .putExtra(SectionActivity.EXTRA_QUERY, item.identity.substringAfter(':'))
                        .putExtra(SectionActivity.EXTRA_TITLE, item.title)
                )
            }
        }
    }

    private inner class CardHolder(
        view: View, val art: ImageView, val name: TextView, val sub: TextView, val logo: ImageView,
        val badge: TextView, val num: TextView, val progress: android.widget.ProgressBar,
        val slideRing: View?, val imdb: TextView, val cert: TextView, val corner: View,
        val chevron: ImageView,
    ) : RecyclerView.ViewHolder(view) {
        var item: Item? = null
    }

    /**
     * A VideoView that fills its slot instead of letterboxing into it: once the clip reports its
     * size, the view measures itself to the slot's cover scale and the slot clips the overflow —
     * a trailer in the banner reads as the banner, not as a smaller rectangle inside it.
     */
    private class CoverVideoView(ctx: android.content.Context) : android.widget.VideoView(ctx) {
        private var vw = 0
        private var vh = 0

        fun coverSize(w: Int, h: Int) {
            vw = w
            vh = h
            if (w > 0 && h > 0) requestLayout()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            if (vw == 0 || vh == 0) return
            // VideoView's own pass shrinks the view to fit the clip inside the slot, so the cover
            // scale must come from the slot's MeasureSpec — not from what that pass left behind.
            val boxW = MeasureSpec.getSize(widthMeasureSpec)
            val boxH = MeasureSpec.getSize(heightMeasureSpec)
            if (boxW <= 0 || boxH <= 0) return
            val s = maxOf(boxW.toDouble() / vw, boxH.toDouble() / vh)
            setMeasuredDimension((vw * s).toInt(), (vh * s).toInt())
        }
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_TAB = "tab"
        // Whether this hub carries a Live TV row at all. The home load learns it once; a section
        // page rebuilds the activity, and the pill must not change shape mid-session.
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
