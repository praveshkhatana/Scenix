package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Console
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Motion
import com.scenix.tv.core.Views
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One shelf of the Game mode, and one card on it, as the hub's /app/v1/games answers them. */
data class GameCard(
    val identity: String,
    val title: String,
    /** Portrait-ish cover for the rail — the artwork the reference prints into each launch tile. */
    val poster: String,
    /** The same art at stage scale; a wide key art when the catalogue has one. */
    val backdrop: String,
    val playUrl: String,
    /** A second address for titles whose first host refuses this region; empty when there is none. */
    val fallbackUrl: String,
    /** True when the port's server demands cross-origin isolation — Android WebView never grants it. */
    val coi: Boolean,
    /** Whether this port answers a real controller — the couch's first question. */
    val pad: Boolean,
    /** Whether the port still answers a keyboard. */
    val kb: Boolean,
    /** Whether the port answers touch gestures. */
    val touch: Boolean,
    /** "desktop" for hosts that answer web portals only when the browser claims a desktop UA. */
    val ua: String,
    val desc: String,
    /** The shelf the card came from, shown as a fact on its page. */
    val category: String,
    /** Playtested on this box class: loaded clean, ran frames, answered the remote. */
    val verified: Boolean = false,
)

data class GameRow(val id: String, val title: String, val items: List<GameCard>)

/**
 * The Game mode, wearing the console library grammar from the owner's reference clip:
 *
 * one quiet status line, a filter strip of uppercase categories with only the active one boxed,
 * full-bleed key art that crossfades as the selection moves, ONE launch rail of art-first tiles
 * bleeding to the screen edges, a platform micro-label under it and the remote's legend in the
 * lower right. There is no billboard and no title block: the rail *is* the browser here, and the
 * art names the game. Opening a tile leads to [GameHubActivity], the title's own page.
 */
class GamesActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var profileId: String
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }

    private data class Filter(val label: String, val items: List<GameCard>)

    private lateinit var stageA: ImageView
    private lateinit var stageB: ImageView
    private lateinit var stageFront: ImageView
    private lateinit var stageBack: ImageView
    private lateinit var rail: LinearLayout
    private lateinit var railScroll: HorizontalScrollView
    private lateinit var chipScroll: HorizontalScrollView
    private lateinit var chips: LinearLayout
    private lateinit var railHost: FrameLayout
    private lateinit var status: TextView
    private lateinit var clock: TextView
    private lateinit var who: TextView

    private val filters = ArrayList<Filter>()
    private val chipViews = ArrayList<View>()
    private val tiles = ArrayList<View>()
    private var activeFilter = 0
    private var selected = 0
    private var stageGeneration = 0L

    private val ticker = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var clockTick: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        profileId = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
        if (profileId.isBlank()) {
            startActivity(Intent(this, ProfilesActivity::class.java))
            finish()
            return
        }

        val theme = app.theme
        val root = FrameLayout(this).apply { setBackgroundColor(Console.bg) }

        // Two full-bleed layers so moving along the rail crossfades the room instead of snapping.
        fun stageLayer() = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Console.surface)
        }
        stageA = stageLayer()
        stageB = stageLayer().apply { alpha = 0f }
        stageFront = stageA
        stageBack = stageB
        root.addView(stageA, match())
        root.addView(stageB, match())

        // The art stays visible almost everywhere; only the reading edges deepen.
        root.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.argb(190, 4, 7, 14), Color.argb(96, 4, 7, 14), Color.argb(8, 4, 7, 14)),
            )
        }, match())
        root.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(214, 3, 5, 11), Color.argb(76, 3, 5, 11), Color.TRANSPARENT),
            )
        }, match())

        root.addView(makeStatusStrip(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(72f),
        ).apply {
            gravity = Gravity.TOP
            leftMargin = theme.gutter
            rightMargin = theme.gutter
            topMargin = theme.px(22f)
        })

        // The category strip is its own line under the status row — quiet uppercase words with
        // (L1)/(R1) shoulders, and a hairline box around the category currently showing.
        chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.px(8f), 0, theme.px(8f), 0)
        }
        chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            clipChildren = false
            clipToPadding = false
            addView(chips, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(chipScroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(54f),
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = theme.px(52f)
            rightMargin = theme.px(52f)
            topMargin = theme.px(112f)
        })

        rail = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(theme.px(46f), theme.px(26f), theme.px(46f), theme.px(26f))
        }
        railScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            isFocusable = false
            isFocusableInTouchMode = false
            clipChildren = false
            clipToPadding = false
            id = View.generateViewId()
            addView(rail, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        railHost = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        railHost.addView(railScroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        root.addView(railHost, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(theme.packInt("gameRailHeight", 306).toFloat()),
        ).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = theme.px(theme.packInt("gameRailBottom", 146).toFloat())
        })

        // Under the rail the console prints one platform micro-label — never a headline.
        val platform = Views.text(app, "meta", 13f, Console.ink3, "SCENIX", bold = true).apply {
            letterSpacing = 0.28f
        }
        root.addView(platform, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = theme.gutter
            bottomMargin = theme.px(40f)
        })

        root.addView(makeLegend(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = theme.gutter
            bottomMargin = theme.px(34f)
        })

        status = Views.text(app, "sub", 22f, Console.ink2, "Opening the library…", gravity = Gravity.CENTER)
        root.addView(status, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER })

        setContentView(root)
        Motion.enter(theme, root)
        startClock()
        load()
    }

    private fun match() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
    )

    // ---- the quiet status line ----------------------------------------------------------------

    private fun makeStatusStrip(): View {
        val theme = app.theme
        val strip = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        clock = Views.text(app, "meta", 18f, Console.ink, clockFormat.format(Date()), bold = true)
        left.addView(clock, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        val wifi = ImageView(this).apply {
            setImageResource(R.drawable.ic_wifi)
            setColorFilter(Console.ink, android.graphics.PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Connected"
        }
        left.addView(wifi, LinearLayout.LayoutParams(theme.px(28f), theme.px(28f)).apply {
            marginStart = theme.px(12f)
        })
        left.addView(Views.text(app, "meta", 13f, Console.ink3, "GAMES", bold = true).apply {
            letterSpacing = 0.22f
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = theme.px(16f) })
        strip.addView(left, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })

        // The reference's right shoulder is the signed-in person. Ours is the profile stop: an
        // initial in the pack's disc, the name, and a press that opens the household gate.
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // The library's DPAD walk belongs to the category strip and the rail; the avatar is a
            // touch affordance here, and the reference gives it no focus state.
            isFocusable = false
            isClickable = true
            contentDescription = "Switch profile"
            setPadding(theme.px(10f), theme.px(4f), theme.px(10f), theme.px(4f))
            setOnClickListener { startActivity(Intent(this@GamesActivity, ProfilesActivity::class.java)) }
        }
        val avatar = Views.text(app, "meta", 15f, Console.ink, "P", bold = true, gravity = Gravity.CENTER).apply {
            background = Views.rounded(Console.surface, theme.px(999f), theme.px(1f), Console.line)
        }
        right.addView(avatar, LinearLayout.LayoutParams(theme.px(34f), theme.px(34f)))
        who = Views.text(app, "meta", 15f, Console.ink2, "", maxLines = 1)
        right.addView(who, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = theme.px(10f) })
        strip.addView(right, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL })
        profileChip = right
        profileInitial = avatar
        return strip
    }

    private var profileChip: View? = null
    private var profileInitial: TextView? = null

    private fun makeLegend(): View {
        val theme = app.theme
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(legendMark("≡", "OPTIONS"))
        row.addView(legendMark("⏎", "DETAILS"))
        row.addView(legendMark("▶", "PLAY"))
        row.addView(legendMark("←", "BACK"))
        return row
    }

    private fun legendMark(glyph: String, label: String): View {
        val theme = app.theme
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.px(12f), 0, 0, 0)
        }
        val mark = Views.text(app, "badge", 12f, Console.ink2, glyph, bold = true, gravity = Gravity.CENTER).apply {
            background = Views.rounded(Color.TRANSPARENT, theme.px(999f), theme.px(1f), Console.line)
        }
        group.addView(mark, LinearLayout.LayoutParams(theme.px(28f), theme.px(28f)))
        group.addView(Views.text(app, "meta", 12f, Console.ink3, label, bold = true).apply {
            letterSpacing = 0.14f
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = theme.px(8f) })
        return group
    }

    // ---- data ---------------------------------------------------------------------------------

    private fun load() {
        status.visibility = View.VISIBLE
        status.text = "Opening the library…"
        api.games { rowsJson, error ->
            if (isFinishing || isDestroyed) return@games
            val rows = parseRows(rowsJson)
            if (rows.isEmpty()) {
                status.text = error ?: "The hub has no games to show right now"
                return@games
            }
            buildFilters(rows)
            status.visibility = View.GONE
            if (filters.isNotEmpty()) {
                setFilter(0, focusRail = true)
            }
        }
        api.profiles { profiles, _ ->
            if (isFinishing || isDestroyed) return@profiles
            val me = profiles?.firstOrNull { it.id == profileId } ?: profiles?.firstOrNull()
            if (me != null) {
                who.text = me.name
                profileInitial?.text = me.name.trim().firstOrNull()?.uppercase() ?: "P"
            }
            profileChip?.contentDescription = "Switch profile"
        }
    }

    private fun parseRows(arr: JSONArray?): List<GameRow> {
        if (arr == null) return emptyList()
        val out = ArrayList<GameRow>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val itemsArr = r.optJSONArray("items") ?: continue
            val cards = ArrayList<GameCard>()
            for (j in 0 until itemsArr.length()) {
                val o = itemsArr.optJSONObject(j) ?: continue
                val url = o.optString("playUrl")
                if (url.isBlank()) continue
                val cover = o.optString("poster").ifBlank { o.optString("backdrop") }
                cards.add(
                    GameCard(
                        identity = o.optString("identity").ifBlank { "game:" + o.optString("title") },
                        title = o.optString("title"),
                        poster = cover,
                        backdrop = o.optString("backdrop").ifBlank { cover },
                        playUrl = url,
                        fallbackUrl = o.optString("fallbackUrl"),
                        coi = o.optBoolean("coi"),
                        pad = o.optBoolean("pad"),
                        kb = o.optBoolean("kb", true),
                        touch = o.optBoolean("touch"),
                        ua = o.optString("ua"),
                        desc = o.optString("desc"),
                        category = r.optString("title"),
                        verified = o.optBoolean("verified"),
                    )
                )
            }
            if (cards.isNotEmpty()) out.add(GameRow(r.optString("id"), r.optString("title"), cards))
        }
        return out
    }

    /** "ALL" plus one stop per shelf. Long shelf names become one uppercase word, as printed. */
    private fun buildFilters(rows: List<GameRow>) {
        filters.clear()
        chips.removeAllViews()
        chipViews.clear()
        val all = LinkedHashMap<String, GameCard>()
        rows.forEach { row -> row.items.forEach { all.putIfAbsent(it.identity, it) } }
        filters += Filter("ALL", all.values.toList())
        rows.forEach { row -> filters += Filter(shortLabel(row.title), row.items) }

        val theme = app.theme
        chips.addView(shoulder("L1"))
        filters.forEachIndexed { index, filter ->
            val stop = makeChip(index, filter)
            chipViews += stop
            chips.addView(stop)
        }
        chips.addView(shoulder("R1"))
    }

    private fun shortLabel(title: String): String {
        val t = title.trim()
        return when {
            t.startsWith("Play now", true) -> "CONTROLLER"
            t.contains("Racing", true) -> "RACING"
            t.contains("Fighting", true) -> "FIGHTING"
            t.contains("classics", true) -> "CLASSICS"
            // "Web Arcade" also contains "arcade"; the browser shelf is its own stop.
            t.contains("Web", true) -> "WEB"
            t.contains("Retro", true) || t.contains("arcade", true) -> "ARCADE"
            t.contains("Every", true) -> "ALL PORTS"
            t.contains("Highlight", true) -> "FEATURED"
            else -> t.uppercase(Locale.US).let { if (it.length <= 12) it else it.split(' ').first() }
        }
    }

    private fun shoulder(label: String): View {
        val theme = app.theme
        val chip = Views.text(app, "badge", 10f, Console.ink3, label, bold = true, gravity = Gravity.CENTER).apply {
            background = Views.rounded(Color.TRANSPARENT, theme.px(999f), theme.px(1f), Console.line)
        }
        chip.alpha = 0.75f
        return chip.apply {
            val lp = LinearLayout.LayoutParams(theme.px(30f), theme.px(30f))
            lp.marginStart = theme.px(8f)
            lp.marginEnd = theme.px(8f)
            layoutParams = lp
        }
    }

    private fun makeChip(index: Int, filter: Filter): View {
        val theme = app.theme
        val stop = FrameLayout(this).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "${filter.label} games"
            setPadding(theme.px(16f), theme.px(9f), theme.px(16f), theme.px(9f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = theme.px(6f) }
            setOnClickListener { setFilter(index, focusRail = true) }
            setOnFocusChangeListener { _, focused ->
                if (focused) {
                    setFilter(index, focusRail = false)
                    chipScroll.smoothScrollTo((left - theme.px(90f)).coerceAtLeast(0), 0)
                }
            }
        }
        val label = Views.text(
            app, "meta", 14f, if (index == activeFilter) Console.ink else Console.ink2,
            filter.label.uppercase(Locale.US), bold = true, gravity = Gravity.CENTER,
        ).apply { letterSpacing = 0.08f }
        stop.addView(label, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER })
        stop.background = boxed(theme, index == activeFilter)
        return stop
    }

    private fun boxed(theme: com.scenix.tv.core.Theme, active: Boolean) =
        if (active) Views.rounded(Color.TRANSPARENT, theme.px(999f), theme.px(2f), Console.lineStrong) else null

    // ---- the rail -----------------------------------------------------------------------------

    private fun setFilter(index: Int, focusRail: Boolean) {
        if (index !in filters.indices) return
        val changed = index != activeFilter
        activeFilter = index
        val theme = app.theme
        chipViews.forEachIndexed { i, chip ->
            chip.background = boxed(theme, i == index)
            ((chip as ViewGroup).getChildAt(0) as? TextView)?.setTextColor(if (i == index) Console.ink else Console.ink2)
        }
        // The first call arrives with activeFilter already 0, so emptiness — not the index — is
        // what decides whether the rail still needs its tiles.
        if (changed || tiles.isEmpty()) {
            renderRail(filters[index].items)
            select(0, focus = false)
        }
        if (focusRail) rail.post { focusTile(0) }
    }

    private fun renderRail(items: List<GameCard>) {
        rail.removeAllViews()
        tiles.clear()
        items.forEachIndexed { index, item ->
            val tile = makeTile(index, item)
            tiles += tile
            rail.addView(tile)
        }
        val firstId = tiles.firstOrNull()?.id ?: View.NO_ID
        chipViews.forEach { it.nextFocusDownId = firstId }
        val activeChip = chipViews.getOrNull(activeFilter)
        tiles.forEach { tile ->
            tile.nextFocusUpId = activeChip?.id ?: railScroll.id
            tile.nextFocusDownId = View.NO_ID
        }
        profileChip?.nextFocusDownId = activeChip?.id ?: firstId
    }

    private fun makeTile(index: Int, item: GameCard): View {
        val theme = app.theme
        val width = theme.px(theme.packInt("gameTileWidth", 192).toFloat())
        val height = theme.px(theme.packInt("gameTileHeight", 256).toFloat())
        val outerPad = theme.focusGlowInsetSm
        val radius = theme.px(4f)
        val root = FrameLayout(this).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            clipChildren = false
            clipToPadding = false
            contentDescription = "${item.title}. Open details"
            layoutParams = LinearLayout.LayoutParams(width, height).apply {
                marginEnd = theme.px(theme.packInt("gameTileGap", 18).toFloat())
            }
        }
        val halo = View(this)
        root.addView(halo, FrameLayout.LayoutParams(width + outerPad * 2, height + outerPad * 2).apply {
            leftMargin = -outerPad
            topMargin = -outerPad
        })
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Console.surface)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, radius.toFloat())
                }
            }
        }
        root.addView(art, FrameLayout.LayoutParams(width, height))
        BrowseImages.load(art, item.poster.ifBlank { item.backdrop }, width)
        // The focused launch tile is simply larger than its neighbours; a quiet keyline keeps it
        // legible when the art itself is dark. No filled plate, no glow band.
        val keyline = View(this).apply {
            visibility = View.INVISIBLE
            background = Views.ring(Color.argb(150, 255, 255, 255), radius, theme.px(2f))
        }
        root.addView(keyline, FrameLayout.LayoutParams(width, height))
        // A quiet fact printed on the art itself: this title was playtested on a box like this
        // one — it loads, runs, and answers the remote. A dot you can read from across the room.
        if (item.verified) {
            val dot = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.rgb(46, 204, 113))
                }
            }
            val s = theme.px(12f)
            root.addView(dot, FrameLayout.LayoutParams(s, s).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                topMargin = theme.px(10f)
                marginEnd = theme.px(10f)
            })
        }
        Focus.attach(
            root,
            halo,
            app,
            radius,
            scale = true,
            elevate = false,
            glowInset = outerPad,
            scaleTo = 1.1f,
            onClick = { openDetail(item) },
            onFocus = { focused ->
                keyline.visibility = if (focused) View.VISIBLE else View.INVISIBLE
                if (focused) {
                    focusSettled = true
                    select(index, focus = true)
                    railScroll.smoothScrollTo((root.left - theme.px(120f)).coerceAtLeast(0), 0)
                }
            },
            arrow = { key ->
                when (key) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (index == 0 && tiles.isNotEmpty()) {
                        focusTile(tiles.lastIndex); true
                    } else false
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (index == tiles.lastIndex) {
                        focusTile(0); true
                    } else false
                    else -> false
                }
            },
        )
        // The screen is often entered by tap (a phone remote, a pointer), which leaves the window
        // in touch mode; Focus.attach turns touch-mode focus off, so requestFocus would be ignored
        // until the first D-pad key. A TV rail must own the focus the moment it exists.
        root.isFocusableInTouchMode = true
        return root
    }

    private fun focusTile(index: Int, tries: Int = 0) {
        val tile = tiles.getOrNull(index) ?: return
        if (tile.requestFocus()) return
        // A cold emulator spends well over a second in the open transition; keep trying while the
        // window is still settling instead of giving up on the first 250 ms.
        if (tries < 40) rail.postDelayed({ focusTile(index, tries + 1) }, 60L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !focusSettled && tiles.isNotEmpty()) {
            rail.postDelayed({ if (!focusSettled) focusTile(0) }, 120L)
        }
    }

    private fun select(index: Int, focus: Boolean) {
        val items = filters.getOrNull(activeFilter)?.items ?: return
        if (items.isEmpty()) return
        selected = index.coerceIn(0, items.lastIndex)
        val item = items[selected]
        // The legend's DETAILS/PLAY targets follow the selection, whether it moved by focus or by
        // a category swap that only re-rendered the rail.
        selectedItem = item
        setStage(item.backdrop.ifBlank { item.poster })
    }

    private var selectedItem: GameCard? = null
    // The first paint's focus belongs to the rail, even when the open transition outlasts the
    // short requestFocus retries; after that the focus is the viewer's to move.
    private var focusSettled = false

    private fun setStage(url: String?) {
        if (url.isNullOrBlank()) return
        val generation = ++stageGeneration
        val target = stageBack
        val fadeMs = app.theme.token("hero", "rotateFadeMs", 260.0).toLong().coerceIn(80L, 600L)
        BrowseImages.load(target, url, app.theme.px(1440f)) { ok ->
            if (!ok || generation != stageGeneration) return@load
            target.animate().cancel()
            stageFront.animate().cancel()
            if (!app.theme.motionEnabled) {
                stageFront.alpha = 0f
                target.alpha = 1f
            } else {
                stageFront.animate().alpha(0f).setDuration(fadeMs).start()
                target.animate().alpha(1f).setDuration(fadeMs).start()
            }
            val previous = stageFront
            stageFront = target
            stageBack = previous
        }
    }

    // ---- actions ------------------------------------------------------------------------------

    private fun openDetail(item: GameCard) {
        startActivity(
            Intent(this, GameHubActivity::class.java)
                .putExtra(GameHubActivity.EXTRA_IDENTITY, item.identity)
                .putExtra(GameHubActivity.EXTRA_TITLE, item.title)
                .putExtra(GameHubActivity.EXTRA_POSTER, item.poster)
                .putExtra(GameHubActivity.EXTRA_BACKDROP, item.backdrop)
                .putExtra(GameHubActivity.EXTRA_URL, item.playUrl)
                .putExtra(GameHubActivity.EXTRA_FALLBACK, item.fallbackUrl)
                .putExtra(GameHubActivity.EXTRA_COI, item.coi)
                .putExtra(GameHubActivity.EXTRA_PAD, item.pad)
                .putExtra(GameHubActivity.EXTRA_KB, item.kb)
                .putExtra(GameHubActivity.EXTRA_TOUCH, item.touch)
                .putExtra(GameHubActivity.EXTRA_DESKTOP_UA, item.ua == "desktop")
                .putExtra(GameHubActivity.EXTRA_DESC, item.desc)
                .putExtra(GameHubActivity.EXTRA_CATEGORY, item.category)
                .putExtra(GameHubActivity.EXTRA_PROFILE, profileId)
        )
    }

    private fun play(item: GameCard) {
        startActivity(
            Intent(this, if (item.coi) CoiGameActivity::class.java else GameActivity::class.java)
                .putExtra(GameActivity.EXTRA_TITLE, item.title)
                .putExtra(GameActivity.EXTRA_IDENTITY, item.identity)
                .putExtra(GameActivity.EXTRA_URL, item.playUrl)
                .putExtra(GameActivity.EXTRA_FALLBACK, item.fallbackUrl)
                .putExtra(GameActivity.EXTRA_COI, item.coi)
                .putExtra(GameActivity.EXTRA_PAD, item.pad)
                .putExtra(GameActivity.EXTRA_DESKTOP_UA, item.ua == "desktop")
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PROFILE, profileId))
                return true
            }
            // The strip prints (L1)/(R1), so those buttons must move it, as the reference shows.
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                if (filters.isNotEmpty()) {
                    setFilter((activeFilter - 1 + filters.size) % filters.size, focusRail = true)
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (filters.isNotEmpty()) {
                    setFilter((activeFilter + 1) % filters.size, focusRail = true)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                selectedItem?.let { play(it) }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startClock() {
        val tick = object : Runnable {
            override fun run() {
                clock.text = clockFormat.format(Date())
                ticker.postDelayed(this, 30_000L)
            }
        }
        clockTick = tick
        ticker.postDelayed(tick, 30_000L)
    }

    override fun onDestroy() {
        clockTick?.let { ticker.removeCallbacks(it) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
    }
}
