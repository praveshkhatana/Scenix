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
import com.scenix.tv.core.Item
import com.scenix.tv.core.LeftRail
import com.scenix.tv.core.Motion
import com.scenix.tv.core.Views
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every section of the TV app wearing one grammar - the console-library screen the owner approved
 * for Games: a quiet status line, a strip of row titles across the top with only the active one
 * boxed, full-bleed key art that crossfades as the selection moves, ONE rail of art-first tiles,
 * and the remote's legend in the lower right. The sections themselves live in a left panel (the
 * same LeftRail Home has), opened by pressing LEFT on the first tile: from any zone, LEFT opens
 * the panel, the zones swap the page in place without leaving it.
 *
 * What the rail keys do here:
 * - LEFT/RIGHT slide along the current row; the ends hold (no wrap), because the left end is where
 *   the panel door is.
 * - UP/DOWN step to the previous/next row title WITHOUT climbing to the strip - the chip follows
 *   the row, and focus lands on the same column position so the walk feels like lifting the whole
 *   wall one shelf at a time. This is the owner's request: no trip back to the menu bar to change
 *   which row you're in.
 * - OK opens the title (game -> its hub page, movie/series -> Detail, channel -> straight to tune).
 * - L1/R1 page the row-title strip, exactly as in Games.
 */
class ShelfActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var profileId: String
    private var zone = ZONE_HOME
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }

    /** One card on a shelf, whatever mode the shelf belongs to. */
    private data class Card(
        val identity: String,
        val title: String,
        val kind: String,               // movie | series | channel | game
        val poster: String,
        val backdrop: String,
        val game: GameCard?,
        val channel: LiveChannel?,
    )

    private data class Shelf(val label: String, val cards: List<Card>)

    private lateinit var stageA: ImageView
    private lateinit var stageB: ImageView
    private lateinit var stageFront: ImageView
    private lateinit var stageBack: ImageView
    private lateinit var rail: LinearLayout
    private lateinit var railScroll: HorizontalScrollView
    private lateinit var chipScroll: HorizontalScrollView
    private lateinit var chips: LinearLayout
    private lateinit var status: TextView
    private lateinit var clock: TextView
    private lateinit var who: TextView
    private lateinit var zoneLabel: TextView
    private lateinit var leftRail: LeftRail

    private val shelves = ArrayList<Shelf>()
    private val chipViews = ArrayList<View>()
    private val tiles = ArrayList<View>()
    private var activeShelf = 0
    private var selected = 0
    private var selectedCard: Card? = null
    private var stageGeneration = 0L
    private var barCaps: FeatureCaps? = null
    private var focusSettled = false

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
        zone = when (intent?.getStringExtra(EXTRA_ZONE)?.lowercase(Locale.US)) {
            "movies" -> ZONE_MOVIES
            "series" -> ZONE_SERIES
            "games" -> ZONE_GAMES
            "live" -> ZONE_LIVE
            else -> ZONE_HOME
        }

        val theme = app.theme
        val root = FrameLayout(this).apply { id = View.generateViewId(); setBackgroundColor(Console.bg) }

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
        val railHost = FrameLayout(this).apply {
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

        status = Views.text(app, "sub", 22f, Console.ink2, "Loading…", gravity = Gravity.CENTER)
        root.addView(status, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER })

        // The section panel: the owner's door between zones, opened by LEFT on a row's first tile.
        leftRail = LeftRail(this, app, railEntries())
        root.addView(leftRail, match())

        setContentView(root)
        Motion.enter(theme, root)
        startClock()
        api.caps(profileId) { caps ->
            if (!isFinishing && !isDestroyed && caps != null) barCaps = caps
        }
        loadZone(focus = true)
        api.profiles { profiles, _ ->
            if (isFinishing || isDestroyed) return@profiles
            val me = profiles?.firstOrNull { it.id == profileId } ?: profiles?.firstOrNull()
            if (me != null) {
                who.text = me.name
                profileInitial?.text = me.name.trim().firstOrNull()?.uppercase() ?: "P"
            }
        }
    }

    private fun match() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
    )

    // ---- the quiet status line ----------------------------------------------------------------

    private var profileChip: View? = null
    private var profileInitial: TextView? = null

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
        zoneLabel = Views.text(app, "meta", 13f, Console.ink3, zoneTag(zone), bold = true).apply {
            letterSpacing = 0.22f
        }
        left.addView(zoneLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = theme.px(16f) })
        strip.addView(left, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
            isClickable = true
            contentDescription = "Switch profile"
            setPadding(theme.px(10f), theme.px(4f), theme.px(10f), theme.px(4f))
            setOnClickListener { startActivity(Intent(this@ShelfActivity, ProfilesActivity::class.java)) }
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

    private fun makeLegend(): View {
        val theme = app.theme
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(legendMark("≡", "SECTIONS"))
        row.addView(legendMark("⏎", "DETAILS"))
        row.addView(legendMark("▲▼", "ROWS"))
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

    // ---- zones and data ------------------------------------------------------------------------

    private fun railEntries(): List<LeftRail.Entry> = listOf(
        LeftRail.Entry("Switch profile", R.drawable.ic_rail_profile, "profile") {
            startActivity(Intent(this, ProfilesActivity::class.java))
        },
        LeftRail.Entry("Search", R.drawable.ic_nav_search, "search") {
            startActivity(Intent(this, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_PROFILE, profileId))
        },
        LeftRail.Entry("Home", R.drawable.ic_rail_home, "home") { setZone(ZONE_HOME) },
        LeftRail.Entry("Movies", R.drawable.ic_rail_movies, "movies") { setZone(ZONE_MOVIES) },
        LeftRail.Entry("Series", R.drawable.ic_rail_series, "series") { setZone(ZONE_SERIES) },
        LeftRail.Entry("Games", R.drawable.ic_rail_games, "games") { setZone(ZONE_GAMES) },
        LeftRail.Entry("Live TV", R.drawable.ic_rail_live, "live") { setZone(ZONE_LIVE) },
        LeftRail.Entry("Settings", R.drawable.ic_nav_settings, "settings") {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PROFILE, profileId))
        },
    )

    private fun setZone(next: String) {
        if (next == zone) return
        zone = next
        zoneLabel.text = zoneTag(zone)
        loadZone(focus = true)
    }

    private fun loadZone(focus: Boolean) {
        // A zone is only as good as its answer: drop the previous zone's strip and tiles the moment
        // the switch begins, so an empty or refused zone never parades the last one's shelves.
        shelves.clear()
        chips.removeAllViews()
        chipViews.clear()
        rail.removeAllViews()
        tiles.clear()
        activeShelf = -1
        status.visibility = View.VISIBLE
        status.text = "Loading…"
        when (zone) {
            ZONE_GAMES -> api.games { rowsJson, error ->
                if (isFinishing || isDestroyed) return@games
                val shelves = parseGameRows(rowsJson)
                if (shelves.isEmpty()) {
                    status.text = error ?: "The hub has no games to show right now"
                    return@games
                }
                buildShelves(shelves)
            }
            ZONE_LIVE -> api.liveChannels(profileId) { wall, error ->
                if (isFinishing || isDestroyed) return@liveChannels
                val shelves = parseLive(wall?.channels ?: emptyList())
                if (shelves.isEmpty()) {
                    status.text = error ?: "The hub has no channels for this profile"
                    return@liveChannels
                }
                buildShelves(shelves)
            }
            // The hub's Movies and Series tabs are doors into whole sections. The zone takes that
            // deep shelf as its floor and keeps the filtered Home shelves beside it, so the strip
            // keeps variety while the zone still stands for the entire library.
            ZONE_MOVIES, ZONE_SERIES -> {
                var home: List<Shelf>? = null
                var section: Shelf? = null
                var arrived = 0
                fun finish() {
                    if (arrived < 2 || isFinishing || isDestroyed) return
                    val shelves = ArrayList<Shelf>()
                    section?.let { shelves += it }
                    shelves += home ?: emptyList()
                    if (shelves.isEmpty()) status.text = "The hub has nothing to show right now"
                    else buildShelves(shelves)
                }
                api.load(profileId) { rows, _ ->
                    if (isFinishing || isDestroyed) return@load
                    home = parseHome(rows ?: emptyList()); arrived++; finish()
                }
                api.row(profileId, zone) { title, items, _ ->
                    if (isFinishing || isDestroyed) return@row
                    val cards = items?.mapNotNull { cardFor(it) } ?: emptyList()
                    if (cards.isNotEmpty()) section = Shelf(shortLabel(title), cards)
                    arrived++; finish()
                }
            }
            else -> api.load(profileId) { rows, error ->
                if (isFinishing || isDestroyed) return@load
                val shelves = parseHome(rows ?: emptyList())
                if (shelves.isEmpty()) {
                    status.text = error ?: "The hub has nothing to show right now"
                    return@load
                }
                buildShelves(shelves)
            }
        }
    }

    /** Home rows, narrowed by the zone: Movies keeps movies, Series keeps series, Home keeps all. */
    private fun parseHome(rows: List<BrowseRow>): List<Shelf> {
        val out = ArrayList<Shelf>()
        for (row in rows) {
            val cards = row.items.mapNotNull { cardFor(it) }
            if (cards.isEmpty()) continue
            val label = if (row.id == "hero") "FEATURED" else shortLabel(row.title)
            out += Shelf(label, cards)
            if (out.size >= 14) break
        }
        return out
    }

    private fun cardFor(item: Item): Card? {
        if (item.identity.isBlank() || item.title.isBlank()) return null
        val kind = when (item.type) {
            "series" -> "series"
            "channel", "video" -> return null   // live belongs to the Live zone; clips are not browsed here
            else -> "movie"
        }
        if (zone == ZONE_MOVIES && kind != "movie") return null
        if (zone == ZONE_SERIES && kind != "series") return null
        val poster = item.poster ?: item.backdrop ?: ""
        return Card(
            identity = item.identity, title = item.title, kind = kind,
            poster = poster, backdrop = item.backdrop ?: poster, game = null, channel = null,
        )
    }

    private fun parseGameRows(arr: JSONArray?): List<Shelf> {
        if (arr == null) return emptyList()
        val out = ArrayList<Shelf>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val itemsArr = r.optJSONArray("items") ?: continue
            val cards = ArrayList<Card>()
            for (j in 0 until itemsArr.length()) {
                val o = itemsArr.optJSONObject(j) ?: continue
                val url = o.optString("playUrl")
                if (url.isBlank()) continue
                val cover = o.optString("poster").ifBlank { o.optString("backdrop") }
                val g = GameCard(
                    identity = o.optString("identity").ifBlank { "game:" + o.optString("title") },
                    title = o.optString("title"), poster = cover,
                    backdrop = o.optString("backdrop").ifBlank { cover },
                    playUrl = url, fallbackUrl = o.optString("fallbackUrl"),
                    coi = o.optBoolean("coi"), pad = o.optBoolean("pad"),
                    kb = o.optBoolean("kb", true), touch = o.optBoolean("touch"),
                    ua = o.optString("ua"), desc = o.optString("desc"),
                    category = r.optString("title"),
                )
                cards += Card(g.identity, g.title, "game", g.poster, g.backdrop, g, null)
            }
            if (cards.isNotEmpty()) out += Shelf(shortLabel(r.optString("title")), cards)
        }
        return out
    }

    private fun parseLive(channels: List<LiveChannel>): List<Shelf> {
        // Group by the hub's own categories; a household with kids does not need an adult group
        // surfaced here even when a profile's scope still carries one.
        val skip = listOf("adult", "xxx", "porn", "latenight", "late night", "juicy")
        val byGroup = LinkedHashMap<String, MutableList<Card>>()
        for (c in channels) {
            if (c.name.isBlank() || c.identity.isBlank()) continue
            val group = (c.groups.firstOrNull { it.isNotBlank() } ?: c.group.ifBlank { "CHANNELS" })
            if (skip.any { group.contains(it, ignoreCase = true) }) continue
            byGroup.getOrPut(shortLabel(group)) { ArrayList() } += Card(
                identity = c.identity, title = c.name, kind = "channel",
                poster = c.logo, backdrop = "", game = null, channel = c,
            )
        }
        return byGroup.entries.take(14).map { Shelf(it.key, it.value) }
    }

    /** Long shelf names become one uppercase word, as the reference prints them. */
    private fun shortLabel(title: String): String {
        val t = title.trim()
        return when {
            t.startsWith("Play now", true) -> "CONTROLLER"
            t.contains("Racing", true) -> "RACING"
            t.contains("Fighting", true) -> "FIGHTING"
            t.contains("classics", true) -> "CLASSICS"
            t.contains("Web", true) -> "WEB"
            t.contains("Retro", true) || t.contains("arcade", true) -> "ARCADE"
            t.contains("Every", true) -> "ALL PORTS"
            t.contains("Highlight", true) -> "FEATURED"
            else -> t.uppercase(Locale.US).let { if (it.length <= 12) it else it.split(' ').first() }
        }
    }

    // ---- the strip and rail ---------------------------------------------------------------------

    private fun buildShelves(list: List<Shelf>) {
        shelves.clear()
        chips.removeAllViews()
        chipViews.clear()
        // A zone switch rebuilds the strip around index 0 again; without this, setShelf(0) would
        // see "same shelf" and leave the PREVIOUS zone's tiles standing on the rail.
        activeShelf = -1
        // A rail is a wall of hand-built views with live image loads; inflating every card of
        // every shelf at once (a Home ALL can reach three hundred) stalls the first focus event
        // long enough to look like an ANR. The rail only ever shows a screenful, so each shelf is
        // capped here - deeper rows keep their "see all" depth on the hub, the browser page does
        // not need it.
        val capped = list.map { shelf ->
            if (shelf.cards.size <= 36) shelf else shelf.copy(cards = shelf.cards.take(36))
        }
        val all = LinkedHashMap<String, Card>()
        for (shelf in capped) {
            var n = 0
            for (card in shelf.cards) {
                if (n++ >= 8) break
                all.putIfAbsent(card.identity, card)
            }
        }
        shelves += Shelf("ALL", all.values.toList())
        capped.forEach { shelves += it }

        val theme = app.theme
        chips.addView(shoulder("L1"))
        shelves.forEachIndexed { index, shelf ->
            val stop = makeChip(index, shelf)
            chipViews += stop
            chips.addView(stop)
        }
        chips.addView(shoulder("R1"))
        status.visibility = View.GONE
        setShelf(0, focusRail = focusRequested, keepIndex = -1)
    }

    private var focusRequested = true

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

    private fun makeChip(index: Int, shelf: Shelf): View {
        val theme = app.theme
        val stop = FrameLayout(this).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "${shelf.label} shelf"
            setPadding(theme.px(16f), theme.px(9f), theme.px(16f), theme.px(9f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = theme.px(6f) }
            setOnClickListener { setShelf(index, focusRail = true, keepIndex = -1) }
            setOnFocusChangeListener { _, focused ->
                // A chip gaining focus while the rail is being repainted is Android's own
                // default-focus restore, not the viewer climbing to the strip - ignore it.
                if (focused && !repaintingRail) {
                    setShelf(index, focusRail = false, keepIndex = -1)
                    chipScroll.smoothScrollTo((left - theme.px(90f)).coerceAtLeast(0), 0)
                }
            }
        }
        val label = Views.text(
            app, "meta", 14f, if (index == activeShelf) Console.ink else Console.ink2,
            shelf.label.uppercase(Locale.US), bold = true, gravity = Gravity.CENTER,
        ).apply { letterSpacing = 0.08f }
        stop.addView(label, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER })
        stop.background = boxed(theme, index == activeShelf)
        return stop
    }

    private fun boxed(theme: com.scenix.tv.core.Theme, active: Boolean) =
        if (active) Views.rounded(Color.TRANSPARENT, theme.px(999f), theme.px(2f), Console.lineStrong) else null

    /**
     * Show one shelf's tiles. `keepIndex` >= 0 lands focus on that column of the new row (the
     * up/down wall-walk); focusRail means the rail takes focus now; -1 with no rail focus is what
     * a chip being focused calls, so the strip keeps its own focus.
     */
    private fun setShelf(index: Int, focusRail: Boolean, keepIndex: Int) {
        if (index !in shelves.indices) return
        val changed = index != activeShelf
        activeShelf = index
        val theme = app.theme
        chipViews.forEachIndexed { i, chip ->
            chip.background = boxed(theme, i == index)
            ((chip as ViewGroup).getChildAt(0) as? TextView)?.setTextColor(if (i == index) Console.ink else Console.ink2)
        }
        if (changed || tiles.isEmpty()) {
            renderRail(shelves[index].cards)
            select(keepIndex.coerceAtLeast(0), focus = false)
        }
        if (focusRail && tiles.isNotEmpty()) {
            // Take the focus NOW: emptying the rail mid-key leaves the window's default focus
            // to land on the first chip, and a focused chip switches the shelf back - the walk
            // would undo itself. The posted retry is only the safety net for pre-layout misses.
            val land = keepIndex.coerceAtLeast(0).coerceAtMost(tiles.lastIndex)
            tiles[land].requestFocus()
            rail.post { if (!railScroll.hasFocus()) focusTile(land) }
        }
    }

    // While the rail empties, Android restores default focus to the FIRST focusable view in
    // tree order - a chip - and a focused chip would switch the shelf back mid-walk.
    private var repaintingRail = false

    private fun renderRail(items: List<Card>) {
        repaintingRail = true
        rail.removeAllViews()
        tiles.clear()
        items.forEachIndexed { index, item ->
            val tile = makeTile(index, item)
            tiles += tile
            rail.addView(tile)
        }
        val firstId = tiles.firstOrNull()?.id ?: View.NO_ID
        chipViews.forEach { it.nextFocusDownId = firstId }
        val activeChip = chipViews.getOrNull(activeShelf)
        tiles.forEach { tile ->
            tile.nextFocusUpId = activeChip?.id ?: railScroll.id
            tile.nextFocusDownId = View.NO_ID
        }
        profileChip?.nextFocusDownId = activeChip?.id ?: firstId
        repaintingRail = false
    }

    private fun makeTile(index: Int, item: Card): View {
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
        if (item.kind == "channel") {
            // Channel tiles are logos on a plate, not posters; CENTER_CROP would guillotine a wordmark.
            art.scaleType = ImageView.ScaleType.FIT_CENTER
            art.setPadding(theme.px(14f), theme.px(14f), theme.px(14f), theme.px(14f))
        }
        BrowseImages.load(art, item.poster.ifBlank { item.backdrop }, width)
        val keyline = View(this).apply {
            visibility = View.INVISIBLE
            background = Views.ring(Color.argb(150, 255, 255, 255), radius, theme.px(2f))
        }
        root.addView(keyline, FrameLayout.LayoutParams(width, height))
        Focus.attach(
            root,
            halo,
            app,
            radius,
            scale = true,
            elevate = false,
            glowInset = outerPad,
            scaleTo = 1.1f,
            onClick = { open(item) },
            onFocus = { focused ->
                keyline.visibility = if (focused) View.VISIBLE else View.INVISIBLE
                if (focused) {
                    focusSettled = true
                    select(index, focus = true)
                    // Focus can arrive before the fresh rail is measured (the walk grabs it
                    // synchronously); defer the centering to after layout so root.left is real.
                    root.post { railScroll.smoothScrollTo((root.left - theme.px(120f)).coerceAtLeast(0), 0) }
                }
            },
            arrow = { key ->
                when (key) {
                    // The row walk. UP/DOWN change WHICH shelf is showing - the strip follows and
                    // the same column of tiles stays under the thumb - without a trip to the strip.
                    KeyEvent.KEYCODE_DPAD_UP -> stepShelf(-1, index)
                    KeyEvent.KEYCODE_DPAD_DOWN -> stepShelf(1, index)
                    // The left end of a row is the panel door, not a wrap-around.
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (index == 0) {
                        leftRail.open(zone); true
                    } else false
                    else -> false
                }
            },
        )
        root.isFocusableInTouchMode = true
        return root
    }

    /** Move one shelf up/down keeping the viewer's column. Ends hold so the walk feels bounded. */
    private fun stepShelf(direction: Int, column: Int): Boolean {
        val next = activeShelf + direction
        if (next !in shelves.indices) return true   // consumed: the wall ends here, don't escape focus
        setShelf(next, focusRail = true, keepIndex = column)
        return true
    }

    private fun focusTile(index: Int, tries: Int = 0) {
        val tile = tiles.getOrNull(index) ?: return
        if (tile.requestFocus()) return
        if (tries < 40) rail.postDelayed({ focusTile(index, tries + 1) }, 60L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !focusSettled && tiles.isNotEmpty()) {
            rail.postDelayed({ if (!focusSettled) focusTile(selected.coerceAtMost(tiles.lastIndex)) }, 120L)
        }
    }

    private fun select(index: Int, focus: Boolean) {
        val items = shelves.getOrNull(activeShelf)?.cards ?: return
        if (items.isEmpty()) return
        selected = index.coerceIn(0, items.lastIndex)
        val item = items[selected]
        selectedCard = item
        if (item.backdrop.isNotBlank()) setStage(item.backdrop) else setStage(item.poster)
    }

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

    private fun open(item: Card) {
        when (item.kind) {
            "game" -> item.game?.let { g ->
                startActivity(
                    Intent(this, GameHubActivity::class.java)
                        .putExtra(GameHubActivity.EXTRA_IDENTITY, g.identity)
                        .putExtra(GameHubActivity.EXTRA_TITLE, g.title)
                        .putExtra(GameHubActivity.EXTRA_POSTER, g.poster)
                        .putExtra(GameHubActivity.EXTRA_BACKDROP, g.backdrop)
                        .putExtra(GameHubActivity.EXTRA_URL, g.playUrl)
                        .putExtra(GameHubActivity.EXTRA_FALLBACK, g.fallbackUrl)
                        .putExtra(GameHubActivity.EXTRA_COI, g.coi)
                        .putExtra(GameHubActivity.EXTRA_PAD, g.pad)
                        .putExtra(GameHubActivity.EXTRA_KB, g.kb)
                        .putExtra(GameHubActivity.EXTRA_TOUCH, g.touch)
                        .putExtra(GameHubActivity.EXTRA_DESKTOP_UA, g.ua == "desktop")
                        .putExtra(GameHubActivity.EXTRA_DESC, g.desc)
                        .putExtra(GameHubActivity.EXTRA_CATEGORY, g.category)
                        .putExtra(GameHubActivity.EXTRA_PROFILE, profileId)
                )
            }
            "channel" -> startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_PROFILE, profileId)
                    .putExtra(PlayerActivity.EXTRA_IDENTITY, item.identity)
                    .putExtra(PlayerActivity.EXTRA_TYPE, "channel")
                    .putExtra(PlayerActivity.EXTRA_TITLE, item.title)
                    .putExtra(PlayerActivity.EXTRA_POSTER, item.poster)
            )
            else -> startActivity(
                Intent(this, DetailActivity::class.java)
                    .putExtra(DetailActivity.EXTRA_PROFILE, profileId)
                    .putExtra(DetailActivity.EXTRA_IDENTITY, item.identity)
                    .putExtra(DetailActivity.EXTRA_TITLE, item.title)
            )
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PROFILE, profileId))
                return true
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                if (shelves.isNotEmpty()) setShelf((activeShelf - 1 + shelves.size) % shelves.size, focusRail = true, keepIndex = -1)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (shelves.isNotEmpty()) setShelf((activeShelf + 1) % shelves.size, focusRail = true, keepIndex = -1)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                selectedCard?.let { open(it) }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        if (leftRail.isOpen) {
            leftRail.close()
            rail.post { focusTile(selected.coerceAtMost(tiles.lastIndex)) }
            return
        }
        if (zone != ZONE_HOME) {
            setZone(ZONE_HOME)
            return
        }
        super.onBackPressed()
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
        const val EXTRA_ZONE = "zone"
        const val ZONE_HOME = "home"
        const val ZONE_MOVIES = "movies"
        const val ZONE_SERIES = "series"
        const val ZONE_GAMES = "games"
        const val ZONE_LIVE = "live"

        private fun zoneTag(zone: String): String = when (zone) {
            ZONE_MOVIES -> "MOVIES"
            ZONE_SERIES -> "SERIES"
            ZONE_GAMES -> "GAMES"
            ZONE_LIVE -> "LIVE TV"
            else -> "HOME"
        }
    }
}
