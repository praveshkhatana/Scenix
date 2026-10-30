package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Item
import com.scenix.tv.core.Views
import com.scenix.tv.core.s
import com.scenix.tv.core.sa
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native title page. The catalogue and the decision about which copy plays still live on the hub;
 * this page draws the record the web detail page reads (cast, advisories, episodes) with native
 * focus and scrolling. Play opens the native [PlayerActivity]; the hub still ranks and hands over
 * the copy, so nothing here parses or chooses a source.
 */
class DetailActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var profileId: String
    private lateinit var identity: String
    private var expectedTitle = ""
    private var askedFallback = false
    private val preloading = ArrayList<View>()
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }
    private var item: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val sc = application as ScenixApp
        app = sc.ctx
        profileId = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
        identity = intent?.getStringExtra(EXTRA_IDENTITY).orEmpty()
        expectedTitle = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        if (profileId.isBlank() || identity.isBlank()) {
            finish()
            return
        }
        scroll = ScrollView(this).apply {
            setBackgroundColor(app.theme.bg)
            // A ScrollView is itself a D-pad stop on Android; on a title page that stop is an
            // invisible full-screen ring. The page's controls are the only focus targets.
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, app.theme.px(64f))
        }
        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        loading()
        api.meta(identity, profileId) { record, error ->
            if (isFinishing || isDestroyed) return@meta
            // A cold hub sometimes answers with its error shape instead of a title. Painting that
            // leaves a wordless black page; say what happened instead.
            val got = record?.optString("title").orEmpty()
            val mismatch = got.isBlank() ||
                (expectedTitle.isNotBlank() && norm(got) != norm(expectedTitle))
            if (mismatch && expectedTitle.isNotBlank() && !askedFallback) {
                // The hub's shelves and its meta ids drift apart: the tile said "The Office", the
                // id it carried answered "not found" or a different title entirely. The viewer
                // clicked a name, not an id — ask for that name and follow what the hub's own
                // search vouches for.
                askedFallback = true
                api.search(profileId, expectedTitle) { items, _ ->
                    if (isFinishing || isDestroyed) return@search
                    val hit = items?.firstOrNull { norm(it.title) == norm(expectedTitle) }
                    if (hit != null && hit.identity != identity) {
                        identity = hit.identity
                        clearPreloader()
                        loading()
                        api.meta(identity, profileId) { record2, error2 ->
                            if (isFinishing || isDestroyed) return@meta
                            val got2 = record2?.optString("title").orEmpty()
                            if (record2 == null || got2.isBlank()) {
                                unavailable(error2 ?: error)
                            } else {
                                item = record2
                                paint(record2)
                            }
                        }
                    } else if (record != null && !got.isBlank()) {
                        // Search could not vouch for a better id; the record we have still has a
                        // title, so paint it rather than throw the page away.
                        item = record
                        paint(record)
                    } else {
                        unavailable(error)
                    }
                }
                return@meta
            }
            if (record == null || got.isBlank()) {
                unavailable(error)
            } else {
                item = record
                paint(record)
            }
        }
    }

    private fun unavailable(error: String?) {
        clearPreloader()
        body.addView(Views.text(app, "sub", 24f, app.theme.ink2, error ?: "This title is not available", gravity = Gravity.CENTER),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(240f)))
    }

    private fun norm(t: String): String = t.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /**
     * The preloader blocks pulse forever; a detached view whose animation was never cancelled can
     * keep painting its ghost over the real page long after it was detached. Stop the pulse before
     * the blocks leave the tree.
     */
    private fun clearPreloader() {
        for (v in preloading) v.clearAnimation()
        preloading.clear()
        body.removeAllViews()
    }

    private fun loading() {
        // A word alone reads as a dead page on a TV. The shape of the title page — its hero, its
        // lines, its buttons, the shelf below — pulses in place of the real thing until meta lands.
        val theme = app.theme
        fun pulse(v: View): View {
            v.startAnimation(android.view.animation.AlphaAnimation(0.45f, 1f).apply {
                duration = 900
                repeatCount = android.view.animation.Animation.INFINITE
                repeatMode = android.view.animation.Animation.REVERSE
            })
            return v
        }
        fun block(w: Int, h: Int, radius: Int = theme.radius("card", 6f)): View =
            pulse(View(this).apply { background = Views.rounded(theme.surface2, radius) })
                .also { preloading.add(it); it.layoutParams = LinearLayout.LayoutParams(w, h) }
        body.addView(block(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(560f), 0),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(560f)))
        body.addView(block(theme.px(680.0f), theme.px(46f)),
            LinearLayout.LayoutParams(theme.px(680.0f), theme.px(46f)).apply {
                topMargin = theme.px(26f); leftMargin = theme.gutter
            })
        body.addView(block(theme.px(420.0f), theme.px(22f)),
            LinearLayout.LayoutParams(theme.px(420.0f), theme.px(22f)).apply {
                topMargin = theme.px(14f); leftMargin = theme.gutter
            })
        body.addView(block(theme.px(190.0f), theme.px(56f), theme.radius("button", 6f)),
            LinearLayout.LayoutParams(theme.px(190.0f), theme.px(56f)).apply {
                topMargin = theme.px(24f); leftMargin = theme.gutter
            })
        body.addView(block(theme.px(300.0f), theme.px(26f)),
            LinearLayout.LayoutParams(theme.px(300.0f), theme.px(26f)).apply {
                topMargin = theme.px(34f); leftMargin = theme.gutter
            })
        val rail = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until 6) rail.addView(block(theme.px(230.0f), theme.px(330f)),
            LinearLayout.LayoutParams(theme.px(230.0f), theme.px(330f)).apply {
                if (i > 0) marginStart = theme.px(14f)
            })
        body.addView(rail, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(330f)).apply {
            topMargin = theme.px(14f); leftMargin = theme.gutter; rightMargin = theme.gutter
        })
    }

    private fun paint(record: JSONObject) {
        clearPreloader()
        val theme = app.theme
        val title = s(record, "title", "")
        val backdrop = s(record, "backdrop", "").ifBlank { s(record, "poster", "") }
        val poster = s(record, "poster", "")
        val type = s(record, "type", "movie")
        val year = record.optInt("year", 0)
        val maturity = s(record, "maturity", "")
        val duration = record.optLong("durationMs", 0L)
        val match = record.optInt("match", 0)
        val isNew = record.optBoolean("isNew", false)
        val logoUrl = s(record, "logo", "").takeIf { it.isNotBlank() }

        val split = app.theme.detailStyle == "split"
        val heroHeight = if (split) app.theme.heroHeightPx else theme.px(600f)
        val hero = FrameLayout(this).apply { setBackgroundColor(theme.surface2) }
        body.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heroHeight))
        val art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        hero.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        BrowseImages.load(art, backdrop.ifBlank { poster }, theme.px(1300f))
        hero.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(theme.bg, Color.TRANSPARENT))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val back = Views.text(app, "li", 28f, theme.ink, "‹", bold = true, gravity = Gravity.CENTER)
        back.setPadding(theme.px(16f), theme.px(6f), theme.px(16f), theme.px(6f))
        back.isFocusable = true
        back.isClickable = true
        back.setOnClickListener { finish() }
        Focus.attach(back, back, app, theme.radius("button", 6f), scale = false, elevate = false)
        hero.addView(back, FrameLayout.LayoutParams(theme.px(64f), theme.px(54f)).apply {
            leftMargin = theme.px(32f); topMargin = theme.px(24f)
        })

        // An editorial pack puts the poster beside the words instead of hiding it behind the backdrop.
        if (split) {
            val posterView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(theme.surface2)
            }
            hero.addView(posterView, FrameLayout.LayoutParams(theme.px(300f), theme.px(450f)).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                leftMargin = theme.gutter
            })
            BrowseImages.load(posterView, poster.ifBlank { backdrop }, theme.px(420f))
        }
        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (split) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.BOTTOM or Gravity.START
            setPadding(
                if (split) theme.gutter + theme.px(340f) else theme.gutter,
                theme.px(40f), theme.gutter, theme.px(36f)
            )
        }
        // A long YouTube or file title must not fill the hero a line at a time. The web caps the
        // column at 1100 px; the same cap plus a smaller size for long names keeps the meta line and
        // Play where the eye expects them.
        val heading = Views.text(app, "h1", 54f, theme.ink, title, bold = true, maxLines = 3)
        heading.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_PX,
            if (title.length > 42) theme.px(70f).toFloat() else theme.px(104f).toFloat(),
        )
        val logo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_START
            visibility = View.GONE
        }
        words.addView(logo, LinearLayout.LayoutParams(theme.px(560f), theme.px(130f)).apply {
            bottomMargin = theme.px(8f)
        })
        words.addView(heading, LinearLayout.LayoutParams(
            theme.px(1100f), ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        if (logoUrl != null) {
            logo.visibility = View.VISIBLE
            BrowseImages.load(logo, logoUrl, theme.px(560f)) { ok ->
                logo.visibility = if (ok) View.VISIBLE else View.GONE
                heading.visibility = if (ok) View.GONE else View.VISIBLE
            }
        }
        val meta = ArrayList<String>()
        if (match > 0) meta.add("$match% match")
        if (isNew) meta.add("New")
        if (year > 0) meta.add(year.toString())
        if (maturity.isNotBlank() && !maturity.equals("null", true)) meta.add(maturity)
        if (duration > 0) meta.add(runtime(duration))
        val tagline = s(record, "tagline", "").trim()
        if (tagline.isNotBlank() && !tagline.equals("null", true)) meta.add(tagline)
        val genres = record.optJSONArray("genres")
        if (genres != null) {
            val names = (0 until minOf(genres.length(), 3))
                .map { sa(genres, it, "") }
                .filter { it.isNotBlank() && !it.equals("null", true) }
            if (names.isNotEmpty()) meta.add(names.joinToString(", "))
        }
        if (meta.isNotEmpty()) {
            words.addView(Views.text(app, "sub", 22f, theme.ink2, meta.joinToString(" · "), maxLines = 2),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = theme.px(12f)
                })
        }
        val advisories = record.optJSONArray("advisories")
        if (advisories != null && advisories.length() > 0) {
            val text = (0 until advisories.length())
                .map { sa(advisories, it, "") }
                .filter { it.isNotBlank() && !it.equals("null", true) }
                .joinToString(" · ")
            words.addView(Views.text(app, "sub", 19f, theme.ink3, text, maxLines = 2),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = theme.px(8f)
                })
        }
        val overview = s(record, "overview", "")
        if (overview.isNotBlank() && !overview.equals("null", true)) {
            words.addView(Views.text(app, "sub", 21f, theme.ink2, overview, maxLines = 4),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = theme.px(18f)
                })
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val progress = record.optJSONObject("progress")
        val progressEpisode = progress?.optJSONObject("episode")
        val resumeSeason = progressEpisode?.optInt("season", 0) ?: 0
        val resumeEpisode = progressEpisode?.optInt("episode", 0) ?: 0
        val episodes = record.optJSONArray("episodes")
        val first = if (episodes != null && episodes.length() > 0) episodes.optJSONObject(0) else null
        val playSeason = if (resumeSeason > 0) resumeSeason else first?.optInt("season", 0) ?: 0
        val playEpisode = if (resumeEpisode > 0) resumeEpisode else first?.optInt("episode", 0) ?: 0
        val playText = if ((progress?.optLong("positionMs", 0L) ?: 0L) > 0L) "Resume" else "Play"
        val play = Views.text(app, "button", 23f, theme.onBrand, playText, bold = true, gravity = Gravity.CENTER)
        play.setPadding(theme.px(38f), theme.px(16f), theme.px(38f), theme.px(16f))
        play.background = Views.rounded(theme.brand, theme.radius("button", 6f))
        play.isFocusable = true
        play.isClickable = true
        play.setOnClickListener { startPlay(playSeason, playEpisode) }
        Focus.attach(play, play, app, theme.radius("button", 6f), scale = false, elevate = false)
        actions.addView(play, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // My List: the same per-household set the web UI keeps, held on this TV's prefs. OK toggles
        // it in place; the button says which side of the line the title is on.
        var onList = app.prefs.myList(profileId).contains(identity)
        val listBtn = Views.text(app, "button", 23f, theme.ink,
            if (onList) "✓ In My List" else "+ My List", bold = true, gravity = Gravity.CENTER)
        listBtn.setPadding(theme.px(28f), theme.px(16f), theme.px(28f), theme.px(16f))
        listBtn.background = Views.rounded(theme.surface, theme.radius("button", 6f), theme.px(1f), theme.line)
        listBtn.isFocusable = true
        listBtn.isClickable = true
        listBtn.setOnClickListener {
            val ids = app.prefs.myList(profileId)
            if (!ids.remove(identity)) ids.add(identity)
            app.prefs.setMyList(profileId, ids)
            onList = ids.contains(identity)
            listBtn.text = if (onList) "✓ In My List" else "+ My List"
        }
        Focus.attach(listBtn, listBtn, app, theme.radius("button", 6f), scale = false, elevate = false)
        actions.addView(listBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = theme.px(16f)
        })
        words.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = theme.px(24f)
        })
        hero.addView(words, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.gutter, theme.px(12f), theme.gutter, 0)
        }
        body.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentSlot = content

        val cast = record.optJSONArray("cast")
        if (cast != null && cast.length() > 0) castRow(content, cast, theme)

        if (episodes != null && episodes.length() > 0) episodeList(content, episodes, theme)
        relatedRow(record, theme)
        scroll.postDelayed({ play.requestFocus() }, 150L)
    }

    /**
     * "More like this" — the same honest rule the web page follows: related titles come from this
     * hub's own shelves, matched on the genres and the kind this very record carries. Nothing from
     * some outside taste engine; if the hub holds nothing alike, the row says so.
     */
    private fun relatedRow(record: JSONObject, theme: com.scenix.tv.core.Theme) {
        val type = s(record, "type", "movie")
        if (type != "movie" && type != "series") return
        val genres = ArrayList<String>()
        record.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) {
                val g = sa(arr, i, "")
                if (g.isNotBlank()) genres.add(g)
            }
        }
        val rail = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                this@DetailActivity, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
            isNestedScrollingEnabled = false
            itemAnimator = null
            clipToPadding = false
            setPadding(0, theme.px(6f), 0, theme.px(10f))
        }
        contentSlot?.addView(
            Views.text(app, "rowTitle", 27f, theme.ink, "More like this", bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = theme.px(24f); bottomMargin = theme.px(12f)
            })
        contentSlot?.addView(rail,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(390f)))
        api.row(profileId, if (type == "series") "series" else "movies") { _, pool, _ ->
            if (isFinishing || isDestroyed) return@row
            val seen = HashSet<String>()
            seen.add(identity)
            val picks = (pool ?: emptyList()).filter {
                it.identity.isNotBlank() && !seen.contains(it.identity) &&
                    (it.type == type || it.genres.any { g -> genres.contains(g) })
            }.sortedByDescending { it.genres.count { g -> genres.contains(g) } * 100 + it.match }
                .take(14)
            if (picks.isEmpty()) {
                contentSlot?.addView(Views.text(app, "sub", 20f, theme.ink3,
                    "This hub has not found anything similar yet."),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                return@row
            }
            rail.adapter = RelatedAdapter(picks, theme)
        }
    }

    private var contentSlot: LinearLayout? = null

    private inner class RelatedAdapter(
        private val items: List<Item>,
        private val theme: com.scenix.tv.core.Theme,
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
            val cardW = theme.px(230f)
            val artH = theme.px(330f)
            val card = FrameLayout(this@DetailActivity)
            card.layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(
                cardW, artH + theme.px(40f)
            ).apply { marginEnd = theme.px(22f) }
            card.isFocusable = true
            card.isClickable = true
            val art = ImageView(this@DetailActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(theme.surface2)
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, v.width, v.height, theme.radius("card", 6f).toFloat())
                    }
                }
            }
            card.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, artH))
            val name = Views.text(app, "caption", 17f, theme.ink, "", bold = true, maxLines = 1)
            name.setPadding(theme.px(4f), theme.px(8f), theme.px(4f), 0)
            card.addView(name, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(34f)).apply {
                gravity = Gravity.BOTTOM
            })
            val glowPad = theme.px(22f)
            val ringOn = View(this@DetailActivity)
            card.addView(ringOn, FrameLayout.LayoutParams(cardW + glowPad * 2, artH + glowPad * 2).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = -glowPad; topMargin = -glowPad
            })
            card.clipChildren = false
            Focus.attach(card, ringOn, app, theme.radius("card", 6f), animateScale = false,
                elevate = false, glowInset = glowPad)
            return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(card) {}
        }

        override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            val card = holder.itemView as FrameLayout
            (card.getChildAt(0) as ImageView).let { art ->
                BrowseImages.load(art, item.poster ?: item.backdrop, theme.px(230f))
            }
            (card.getChildAt(1) as TextView).text = item.title
            card.setOnClickListener {
                startActivity(
                    Intent(this@DetailActivity, DetailActivity::class.java)
                        .putExtra(EXTRA_PROFILE, profileId)
                        .putExtra(EXTRA_IDENTITY, item.identity)
                        .putExtra(EXTRA_TITLE, item.title)
                )
            }
        }
    }

    private fun castRow(parent: LinearLayout, cast: JSONArray, theme: com.scenix.tv.core.Theme) {
        parent.addView(Views.text(app, "rowTitle", 27f, theme.ink, "Cast", bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = theme.px(18f); bottomMargin = theme.px(12f)
            })
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until minOf(cast.length(), 8)) {
            val person = cast.optJSONObject(i) ?: continue
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(theme.px(8f), 0, theme.px(8f), 0)
            }
            val face = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(theme.surface2)
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: android.graphics.Outline) {
                        outline.setOval(0, 0, v.width, v.height)
                    }
                }
            }
            box.addView(face, LinearLayout.LayoutParams(theme.px(112f), theme.px(112f)))
            BrowseImages.load(face, s(person, "profile", ""), theme.px(112f))
            box.addView(Views.text(app, "caption", 18f, theme.ink, s(person, "name", ""), maxLines = 1, gravity = Gravity.CENTER),
                LinearLayout.LayoutParams(theme.px(150f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = theme.px(8f) })
            box.addView(Views.text(app, "meta", 15f, theme.ink3, s(person, "character", ""), maxLines = 1, gravity = Gravity.CENTER),
                LinearLayout.LayoutParams(theme.px(150f), ViewGroup.LayoutParams.WRAP_CONTENT))
            strip.addView(box)
        }
        val h = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            // The cast is decorative: names and faces, not buttons. A focusable scroll view would
            // take the D-pad ring on a Down press and leave it nowhere visible.
            isFocusable = false
            isFocusableInTouchMode = false
        }
        h.addView(strip)
        parent.addView(h, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun episodeList(parent: LinearLayout, episodes: JSONArray, theme: com.scenix.tv.core.Theme) {
        parent.addView(Views.text(app, "rowTitle", 27f, theme.ink, "Episodes", bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = theme.px(24f); bottomMargin = theme.px(10f)
            })
        // Season-based grouping: a long flat list of "S2 E7 · …" lines is a database dump, not a
        // season picker. Sort by season and episode and open each block under its own header, so
        // the page reads like the series actually has seasons.
        val sorted = ArrayList<JSONObject>()
        for (i in 0 until episodes.length()) episodes.optJSONObject(i)?.let { sorted.add(it) }
        sorted.sortWith(compareBy({ it.optInt("season", 1) }, { it.optInt("episode", 1) }))
        var lastSeason = -1
        for (ep in sorted.take(120)) {
            val season = ep.optInt("season", 1)
            val number = ep.optInt("episode", 1)
            if (season != lastSeason) {
                lastSeason = season
                parent.addView(Views.text(app, "rowTitle", 22f, theme.ink2, "Season $season", bold = true),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = theme.px(16f); bottomMargin = theme.px(2f)
                    })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(theme.px(14f), theme.px(12f), theme.px(14f), theme.px(12f))
                isFocusable = true
                isClickable = true
                setBackgroundColor(theme.surface)
            }
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(theme.surface2)
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, v.width, v.height, theme.radius("card", 6f).toFloat())
                    }
                }
            }
            row.addView(thumb, LinearLayout.LayoutParams(theme.px(160f), theme.px(90f)))
            BrowseImages.load(thumb, s(ep, "thumb", ""), theme.px(160f))
            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(theme.px(14f), 0, 0, 0)
            }
            copy.addView(Views.text(app, "li", 22f, theme.ink,
                "E%02d · %s".format(number, s(ep, "title", "")), bold = true, maxLines = 1))
            val synopsis = s(ep, "synopsis", "")
            if (synopsis.isNotBlank()) {
                copy.addView(Views.text(app, "meta", 18f, theme.ink2, synopsis, maxLines = 2),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = theme.px(5f)
                    })
            }
            row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.setOnClickListener { startPlay(season, number) }
            Focus.attach(row, row, app, theme.radius("listItem", 6f), scale = false, elevate = false)
            parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = theme.px(6f)
            })
        }
    }

    private fun startPlay(season: Int, episode: Int) {
        val record = item ?: return
        val progress = record.optJSONObject("progress")
        val progressEpisode = progress?.optJSONObject("episode")
        // Resume only the episode the viewer actually stopped in. A Play on S1 E1 must not open at
        // S1 E4's playhead just because that is what Continue Watching remembers.
        val sameEpisode = season > 0 && episode > 0 &&
            progressEpisode?.optInt("season", 0) == season &&
            progressEpisode?.optInt("episode", 0) == episode
        val position = if ((season <= 0 && episode <= 0) || sameEpisode) {
            progress?.optLong("positionMs", 0L) ?: 0L
        } else 0L
        val advisories = ArrayList<String>()
        record.optJSONArray("advisories")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = sa(arr, i, "")
                if (v.isNotBlank()) advisories.add(v)
            }
        }
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_PROFILE, profileId)
                .putExtra(PlayerActivity.EXTRA_IDENTITY, identity)
                .putExtra(PlayerActivity.EXTRA_TYPE, s(record, "type", "movie"))
                .putExtra(PlayerActivity.EXTRA_TITLE, s(record, "title", ""))
                .putExtra(PlayerActivity.EXTRA_SEASON, season)
                .putExtra(PlayerActivity.EXTRA_EPISODE, episode)
                .putExtra(PlayerActivity.EXTRA_POSITION_MS, position)
                .putExtra(PlayerActivity.EXTRA_MATURITY, s(record, "maturity", ""))
                .putExtra(PlayerActivity.EXTRA_POSTER, s(record, "poster", ""))
                .putExtra(PlayerActivity.EXTRA_LOGO, s(record, "logo", ""))
                .putStringArrayListExtra(PlayerActivity.EXTRA_ADVISORIES, advisories)
        )
    }

    private fun runtime(ms: Long): String {
        val min = (ms / 60000L).toInt()
        return if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_IDENTITY = "identity"
        const val EXTRA_TITLE = "title"
    }
}
