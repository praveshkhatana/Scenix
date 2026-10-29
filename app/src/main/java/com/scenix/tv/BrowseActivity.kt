package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.RenderEffect
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

    // The hero and the first shelf are ONE section, the way Apple's shelf-top works: the slides the
    // hero shows are the tiles of the Featured row, and whichever side moves, the other follows.
    private var slides: List<Item> = emptyList()
    private var heroPos = 0
    private var browseAdapter: BrowseAdapter? = null
    private val clock = Handler(Looper.getMainLooper())
    private var rotationPaused = false
    private val rotateHero = object : Runnable {
        override fun run() {
            // A preview that is running is the viewer paying attention: rotation waits for it.
            val watching = browseAdapter?.heroVideo()?.visibility == View.VISIBLE
            if (!rotationPaused && slides.size > 1 && !watching) setHero((heroPos + 1) % slides.size)
            clock.postDelayed(this, app.theme.token("hero", "rotateMs", 6000.0).toLong())
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val next = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
        if (next.isNotBlank() && next != profileId) {
            profileId = next
            load()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val sc = application as ScenixApp
        app = sc.ctx
        profileId = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
        if (profileId.isBlank()) {
            // Never guess who is watching. The native profile gate is the one place a PIN is checked.
            startActivity(Intent(this, ProfilesActivity::class.java))
            finish()
            return
        }

        // Apple's scene pack puts one stage behind the whole screen: the shelves float on it, and the
        // stage crossfades to whatever tile holds focus. Other packs keep the opaque page they had.
        val stageMode = app.theme.packString("stage", "off") == "focus"
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (stageMode) Color.TRANSPARENT else app.theme.bg)
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

        content.addView(topBar(stageMode), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(96f)
        ))
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@BrowseActivity)
            setHasFixedSize(true)
            setItemViewCacheSize(24)
            setBackgroundColor(if (stageMode) Color.TRANSPARENT else app.theme.bg)
            itemAnimator = null
            clipToPadding = false
            setPadding(0, app.theme.px(16f), 0, app.theme.px(64f))
        }
        content.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        status = Views.text(app, "sub", 24f, app.theme.ink2, "Loading the shelves…", gravity = Gravity.CENTER)
        content.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(80f)))
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val effect = if (roam) RenderEffect.createBlurEffect(24f, 24f, Shader.TileMode.CLAMP) else null
                a.setRenderEffect(effect)
                b.setRenderEffect(effect)
            }
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
        rotationPaused = false
        // A viewer walking a deep shelf is not looking at the billboard: its preview stops with them.
        stopTrailer()
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

    private fun stopTrailer() {
        browseAdapter?.heroVideo()?.let {
            runCatching { it.stopPlayback() }
            it.visibility = View.GONE
        }
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
            pill.addView(navTab("Home", active = true) { list.smoothScrollToPosition(0) })
            pill.addView(navTab("Movies") { openSection("movies") })
            pill.addView(navTab("Series") { openSection("series") })
            val live = navTab("Live TV") {
                startActivity(
                    Intent(this@BrowseActivity, LiveChannelsActivity::class.java)
                        .putExtra(LiveChannelsActivity.EXTRA_PROFILE, profileId)
                )
            }
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
            pill.addView(navIcon(R.drawable.ic_nav_settings, "Settings") {
                startActivity(
                    Intent(this@BrowseActivity, SettingsActivity::class.java)
                        .putExtra(SettingsActivity.EXTRA_PROFILE, profileId)
                )
            })
            bar.addView(pill, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER })
            return bar
        }
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(theme.gutter, 0, theme.gutter, 0)
        bar.setBackgroundColor(if (transparent) Color.TRANSPARENT else theme.bg)
        bar.addView(brand, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(navButton("Movies") { openSection("movies") },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = theme.px(22f)
            })
        bar.addView(navButton("Series") { openSection("series") },
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

    private fun navButton(label: String, left: Int = 0, action: () -> Unit): View {
        val b = Views.text(app, "li", 22f, app.theme.ink, label, bold = true, gravity = Gravity.CENTER)
        b.setPadding(app.theme.px(18f), app.theme.px(10f), app.theme.px(18f), app.theme.px(10f))
        b.isFocusable = true
        b.isClickable = true
        b.setOnClickListener { action() }
        Focus.attach(b, b, app, app.theme.radius("button", 6f), scale = false, elevate = false,
            onFocus = { if (it) onChromeFocused() })
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginStart = left
        b.layoutParams = lp
        return b
    }

    /** A section of the pill. The one you are standing on wears the white face; the rest wait as text. */
    private fun navTab(label: String, active: Boolean = false, action: () -> Unit): View {
        val b = navButton(label, app.theme.px(4f), action)
        if (active) {
            (b as TextView).setTextColor(Color.BLACK)
            b.background = Views.rounded(Color.argb(242, 242, 243, 245), app.theme.px(999f))
        }
        return b
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
        Focus.attach(iv, iv, app, theme.px(15f), scale = false, elevate = false,
            onFocus = { if (it) onChromeFocused() })
        iv.layoutParams = LinearLayout.LayoutParams(theme.px(40f), theme.px(40f)).apply {
            marginStart = theme.px(6f)
        }
        return iv
    }

    /** The household at the head of the pill: the profile's face when the hub has one, its initial otherwise. */
    private fun profileDisc(): View {
        val theme = app.theme
        val size = theme.px(44f)
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
        status.visibility = View.VISIBLE
        status.text = "Loading the shelves…"
        api.load(profileId) { rows, error ->
            if (isFinishing || isDestroyed) return@load
            if (rows == null) {
                loaded = false
                status.visibility = View.VISIBLE
                status.text = error ?: "The hub is not answering"
                return@load
            }
            loaded = true
            status.visibility = View.GONE
            // The hub sometimes sends the hero as its own row and sometimes folds the featured
            // titles into the first shelf. Either way Home opens on a hero — the viewer's first
            // sight on this set is the featured title, never a bare shelf head — and the hero's
            // slides are exactly the tiles of the Featured row that sits under it.
            val heroRow = rows.firstOrNull { it.id == "hero" }
            val others = rows.filter { it.id != "hero" && it.items.isNotEmpty() }
            val maxSlides = app.theme.token("hero", "maxItems", 5.0).toInt().coerceAtLeast(1)
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
                    if (++tries < 10) list.postDelayed(this, 300)
                }
            }, 400)
        }
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

    /** Movies/Series are native grids; the browser's `#/browse/…` route is no longer opened here. */
    private fun openSection(section: String) {
        startActivity(
            Intent(this, SectionActivity::class.java)
                .putExtra(SectionActivity.EXTRA_PROFILE, profileId)
                .putExtra(SectionActivity.EXTRA_SECTION, section)
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
        )
    }

    private inner class BrowseAdapter(
        private val hero: Item?,
        private val shelves: List<BrowseRow>,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        var heroPlay: View? = null
            private set
        private var heroHolder: HeroHolder? = null
        // The rail whose tiles ARE the hero's slides; the hero follows its focused tile and the
        // current slide keeps a rim even when focus walks away, so the two never drift apart.
        var featuredCards: CardAdapter? = null
            private set
        private var featuredRail: RecyclerView? = null

        /** There is no separate hero stop: a landing on Home stands on the Featured row, as Apple's does. */
        fun focusFirstSlide(): View? = featuredRail?.getChildAt(0)

        fun heroVideo(): android.widget.VideoView? = heroHolder?.video

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
                // looping — the home billboard's rule, not the player's.
                val video = android.widget.VideoView(this@BrowseActivity).apply {
                    visibility = View.GONE
                    setBackgroundColor(Color.TRANSPARENT)
                }
                slot.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
                HeroHolder(slot, art, video, poster, title, logo, meta, play, dots, split)
            } else {
                val box = LinearLayout(this@BrowseActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setPadding(0, theme.px(18f), 0, 0)
                }
                val head = Views.text(app, "rowTitle", 28f, theme.ink, "", bold = true)
                head.setPadding(theme.gutter, 0, theme.gutter, theme.px(12f))
                box.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val rail = RecyclerView(this@BrowseActivity).apply {
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
        view: View, val art: ImageView, val video: android.widget.VideoView,
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

        override fun getItemCount(): Int = row.items.size

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
            val holder = CardHolder(card, art, name, sub, logo, badge, num, progress, slideRing, imdb, cert, corner)
            Focus.attach(card, ringOn, app, radius, animateScale = false, elevate = false,
                glowInset = glowPad,
                onFocus = { focused ->
                    if (!focused) return@attach
                    if (featured) onSlideFocused(holder.bindingAdapterPosition)
                    else holder.item?.let { onCardFocused(card, it) }
                })
            return holder
        }

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            val item = row.items[position]
            holder.item = item
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
    }

    private inner class CardHolder(
        view: View, val art: ImageView, val name: TextView, val sub: TextView, val logo: ImageView,
        val badge: TextView, val num: TextView, val progress: android.widget.ProgressBar,
        val slideRing: View?, val imdb: TextView, val cert: TextView, val corner: View,
    ) : RecyclerView.ViewHolder(view) {
        var item: Item? = null
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
        private const val TYPE_HERO = 1
        private const val TYPE_SHELF = 2
    }
}
