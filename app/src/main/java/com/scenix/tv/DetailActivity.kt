package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Motion
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Item
import com.scenix.tv.core.Screens
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
    // Detail borrows the home pack's control material only on the Glass pack; every other
    // pack keeps its solid brand buttons.
    private val isGlassPack get() =
        app.theme.homeLayout.hero == com.scenix.tv.core.HomeLayoutSpec.Hero.GLASS_CARDS
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
        // A title page is part of the same house as its home: packs with a wall background
        // paint it here too; the legacy flat page colour stays for the rest.
        val ground = Screens.wall(app)
        scroll = ScrollView(this).apply {
            if (ground != null) background = ground else setBackgroundColor(app.theme.bg)
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
        Motion.enter(app.theme, scroll)
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
        // The art melts into the page with the pack's scrim, not a solid page-colour block:
        // on a wall background a flat fade bar reads as a stripe across the gradient.
        hero.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(theme.color("heroScrim", "rgba(6,16,30,0.50)"), Color.TRANSPARENT))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (Screens.wall(app) != null) {
            // The hero plate's bottom edge is still a hard rectangle where the art ends and the
            // wall begins. A short band fading to the wall's own colour at that height dissolves
            // the seam — the same trick the home hero uses where its plates meet the wall.
            val wa = theme.color("wallA", "#8FA5B8"); val wb = theme.color("wallB", "#54687C")
            val t = 0.8f
            val melt = Color.argb(255,
                (android.graphics.Color.red(wa) * (1 - t) + android.graphics.Color.red(wb) * t).toInt(),
                (android.graphics.Color.green(wa) * (1 - t) + android.graphics.Color.green(wb) * t).toInt(),
                (android.graphics.Color.blue(wa) * (1 - t) + android.graphics.Color.blue(wb) * t).toInt())
            hero.addView(View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.TRANSPARENT, melt))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(140f)).apply {
                gravity = Gravity.BOTTOM
            })
        }

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
        val posMs = progress?.optLong("positionMs", 0L) ?: 0L
        val playText = if (posMs > 0L) "Resume" else "Play"
        val play = Views.text(app, "button", 23f,
            if (isGlassPack) theme.color("ink1", "#FFFFFF") else theme.onBrand,
            playText, bold = true, gravity = Gravity.CENTER)
        play.setPadding(theme.px(38f), theme.px(16f), theme.px(38f), theme.px(16f))
        // The Glass pack has no solid brand button: Play is a lit translucent chip with the
        // pack's rim — the same control material the home hero and category chips use.
        play.background = if (isGlassPack)
            Views.rounded(theme.color("chipFillActive", "rgba(255,255,255,0.16)"),
                theme.radius("button", 6f), theme.px(1f), theme.color("glassRim", "rgba(255,255,255,0.30)"))
        else Views.rounded(theme.brand, theme.radius("button", 6f))
        play.isFocusable = true
        play.isClickable = true
        play.setOnClickListener { startPlay(playSeason, playEpisode) }
        Focus.attach(play, play, app, theme.radius("button", 6f), scale = false, elevate = false)
        actions.addView(play, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // A title already started gets the honest second choice: Resume keeps the playhead, Start
        // over rewinds the hub's record and opens at zero. Visible, not buried under a long-press —
        // "watch from the beginning" is what people ask for mid-film.
        if (posMs > 0L) {
            val restart = Views.text(app, "button", 23f, theme.ink, "↺ Start over", bold = true, gravity = Gravity.CENTER)
            restart.setPadding(theme.px(28f), theme.px(16f), theme.px(28f), theme.px(16f))
            restart.background = Views.rounded(theme.surface, theme.radius("button", 6f), theme.px(1f), theme.line)
            restart.isFocusable = true
            restart.isClickable = true
            restart.setOnClickListener {
                // Rewind the hub FIRST, then play: a record that still says 40:00 while the viewer
                // watches from 0:00 is the lie the next Continue tile would print.
                api.resetProgress(profileId, identity) { }
                progress?.put("positionMs", 0L)
                progress?.put("watchedMs", 0L)
                progress?.remove("dismissedAt")
                play.text = "Play"
                actions.removeView(restart)
                startPlay(playSeason, playEpisode, fromStart = true)
            }
            Focus.attach(restart, restart, app, theme.radius("button", 6f), scale = false, elevate = false)
            actions.addView(restart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = theme.px(16f)
            })
        }
        // My List lives on the hub (data/list.json), so the living room's shelf IS the bedroom's
        // and the browser's. The hub already stamped this title's truth into record.inList; the
        // TV's prefs keep a copy for the moment the hub is briefly away. OK toggles in place.
        var onList = if (record.has("inList")) record.optBoolean("inList")
        else app.prefs.myList(profileId).contains(identity)
        val listBtn = Views.text(app, "button", 23f, theme.ink,
            if (onList) "✓ In My List" else "+ My List", bold = true, gravity = Gravity.CENTER)
        listBtn.setPadding(theme.px(28f), theme.px(16f), theme.px(28f), theme.px(16f))
        listBtn.background = Views.rounded(theme.surface, theme.radius("button", 6f), theme.px(1f), theme.line)
        listBtn.isFocusable = true
        listBtn.isClickable = true
        listBtn.setOnClickListener {
            // Optimistic: the button flips this frame. The POST confirms; a failed POST flips back.
            onList = !onList
            listBtn.text = if (onList) "✓ In My List" else "+ My List"
            val ids = app.prefs.myList(profileId)
            if (onList) { if (!ids.contains(identity)) ids.add(identity) } else ids.remove(identity)
            app.prefs.setMyList(profileId, ids)
            api.setMyList(profileId, identity, onList) { confirmed ->
                if (confirmed != onList) {
                    onList = confirmed
                    runOnUiThread { listBtn.text = if (onList) "✓ In My List" else "+ My List" }
                    val back = app.prefs.myList(profileId)
                    if (onList) { if (!back.contains(identity)) back.add(identity) } else back.remove(identity)
                    app.prefs.setMyList(profileId, back)
                }
            }
        }
        Focus.attach(listBtn, listBtn, app, theme.radius("button", 6f), scale = false, elevate = false)
        actions.addView(listBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = theme.px(16f)
        })
        words.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = theme.px(24f)
        })

        // The second band is the title's opinion of itself: trailer, seen, like, love, dislike,
        // share. Reactions live on the hub beside progress and My List (see hub/core/taste.js), so
        // the mood board on Home is informed by what this person actually pressed here.
        var reaction = record.optString("reaction").takeIf { it == "like" || it == "love" || it == "dislike" }
        var seen = record.optBoolean("seen", false)
        val social = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun socialChip(label: String, active: Boolean, click: () -> Unit): TextView {
            val b = Views.text(app, "button", 18f, if (active) theme.onBrand else theme.ink,
                label, bold = true, gravity = Gravity.CENTER)
            b.setPadding(theme.px(18f), theme.px(10f), theme.px(18f), theme.px(10f))
            b.background = if (active) Views.rounded(theme.brand, theme.radius("button", 6f))
                else Views.rounded(theme.surface, theme.radius("button", 6f), theme.px(1f), theme.line)
            b.isFocusable = true
            b.isClickable = true
            b.setOnClickListener { click() }
            Focus.attach(b, b, app, theme.radius("button", 6f), scale = false, elevate = false)
            return b
        }
        fun repaintReactions() {
            // Rebuild labels in place: a like can turn a love off, and the visible state must say so.
            for (i in 0 until social.childCount) {
                val v = social.getChildAt(i) as? TextView ?: continue
                val tag = v.tag as? String ?: continue
                val active = when (tag) {
                    "like", "love", "dislike" -> reaction == tag
                    "seen" -> seen
                    else -> false
                }
                val base = when (tag) {
                    "like" -> "Like"; "love" -> "Love"; "dislike" -> "Dislike"
                    "seen" -> "Seen"; else -> v.text.toString().removePrefix("✓ ")
                }
                v.text = if (active) "✓ $base" else base
                v.setTextColor(if (active) theme.onBrand else theme.ink)
                v.background = if (active) Views.rounded(theme.brand, theme.radius("button", 6f))
                    else Views.rounded(theme.surface, theme.radius("button", 6f), theme.px(1f), theme.line)
            }
        }
        fun setReaction(next: String) {
            reaction = if (reaction == next) null else next
            repaintReactions()
            api.react(profileId, identity, reaction ?: "none") { confirmed ->
                val value = confirmed
                if ((value ?: "none") != (reaction ?: "none")) {
                    runOnUiThread { reaction = value; repaintReactions() }
                }
            }
        }
        val trailerBtn = socialChip("▶ Trailer", false) {
            api.trailer(identity) { url, error ->
                runOnUiThread {
                    if (url.isNullOrBlank()) {
                        android.widget.Toast.makeText(this, error ?: "No trailer for this title",
                            android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            .onFailure {
                                android.widget.Toast.makeText(this, "No player for the trailer",
                                    android.widget.Toast.LENGTH_SHORT).show()
                            }
                    }
                }
            }
        }
        trailerBtn.tag = "trailer"
        social.addView(trailerBtn)
        val seenBtn = socialChip(if (seen) "✓ Seen" else "Seen", seen) {
            seen = !seen
            repaintReactions()
            api.setSeen(profileId, identity, seen) { confirmed ->
                if (confirmed != seen) runOnUiThread { seen = confirmed; repaintReactions() }
            }
        }
        seenBtn.tag = "seen"
        social.addView(seenBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = theme.px(10f) })
        for ((tag, label) in listOf("like" to "Like", "love" to "Love", "dislike" to "Dislike")) {
            val chip = socialChip(if (reaction == tag) "✓ $label" else label, reaction == tag) {
                setReaction(tag)
            }
            chip.tag = tag
            social.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = theme.px(10f) })
        }
        val shareBtn = socialChip("Share", false) {
            val text = "Watch " + s(record, "title", "") + " on Scenix"
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            runCatching { startActivity(Intent.createChooser(send, "Share")) }
        }
        shareBtn.tag = "share"
        social.addView(shareBtn);
        val socialScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            isFocusableInTouchMode = false
            clipToPadding = false
            addView(social)
        }
        words.addView(socialScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = theme.px(12f) })
        repaintReactions()
        hero.addView(words, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.gutter, theme.px(12f), theme.gutter, 0)
        }
        body.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentSlot = content

        val cast = record.optJSONArray("cast")
        if (cast != null && cast.length() > 0) castRow(content, cast, theme)

        if (episodes != null && episodes.length() > 0) {
            // A returning viewer should reopen at the season that owns their playhead, not be
            // dropped at season one and forced to rediscover where they were.
            episodeList(content, episodes, theme, resumeSeason)
        }
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
            clipChildren = false
            setPadding(0, theme.px(30f), 0, theme.px(30f))
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
                // The empty-state rule again: the heading stays only with an honest line under it,
                // and the placeholder rail collapses so there is no blank band pretending to load.
                rail.visibility = View.GONE
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
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<RelatedHolder>() {

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RelatedHolder {
            val cardW = theme.px(230f)
            val artH = theme.px(330f)
            val card = FrameLayout(this@DetailActivity)
            card.layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(
                cardW, artH + theme.px(40f)
            ).apply { marginEnd = theme.px(22f) }
            card.isFocusable = true
            card.isClickable = true
            // The glow sits beneath the artwork, leaving the poster and its caption clean.
            val glowPad = theme.focusGlowInset
            val ringOn = View(this@DetailActivity)
            card.clipChildren = false
            card.addView(ringOn, FrameLayout.LayoutParams(cardW + glowPad * 2, artH + glowPad * 2).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = -glowPad; topMargin = -glowPad
            })
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
            Focus.attach(card, ringOn, app, theme.radius("card", 6f), animateScale = false,
                elevate = false, glowInset = glowPad)
            return RelatedHolder(card, art, name)
        }

        override fun onBindViewHolder(holder: RelatedHolder, position: Int) {
            val item = items[position]
            val card = holder.itemView as FrameLayout
            card.contentDescription = buildString {
                append(item.title)
                if (item.year > 0) append(", ").append(item.year)
                append(", open details")
            }
            BrowseImages.load(holder.art, item.poster ?: item.backdrop, theme.px(230f))
            holder.name.text = item.title
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

    private inner class RelatedHolder(
        view: View,
        val art: ImageView,
        val name: TextView,
    ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view)

    private fun castRow(parent: LinearLayout, cast: JSONArray, theme: com.scenix.tv.core.Theme) {
        // Empty-state rule for the whole page: a heading never paints above content that has
        // nothing to show. An entry with no name prints nothing, so it does not count as content;
        // if no entry counts, the Cast block simply is not there (Wildwood showed a bare heading).
        val people = ArrayList<JSONObject>()
        for (i in 0 until minOf(cast.length(), 8)) {
            // The hub answers with enriched people ({name, character, profile}) when the face
            // lookup had budget, and bare names when it did not; both are people. A bare name
            // rides the initial disc below.
            val person = when (val entry = cast.opt(i)) {
                is JSONObject -> entry
                is String -> JSONObject().put("name", entry)
                else -> continue
            }
            if (s(person, "name", "").isBlank()) continue
            people.add(person)
        }
        if (people.isEmpty()) return
        parent.addView(Views.text(app, "rowTitle", 27f, theme.ink, "Cast", bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = theme.px(18f); bottomMargin = theme.px(12f)
            })
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (person in people) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(theme.px(8f), 0, theme.px(8f), 0)
            }
            // A face without a photo is still a face: the disc carries the person's initial,
            // which steps aside only when the hub supplied a real portrait.
            val faceSlot = FrameLayout(this)
            faceSlot.addView(Views.text(app, "h3", 34f, theme.ink2,
                s(person, "name", " ").trim().take(1).uppercase(), bold = true, gravity = Gravity.CENTER).apply {
                background = Views.rounded(theme.surface2, theme.px(999f))
            }, FrameLayout.LayoutParams(theme.px(112f), theme.px(112f)))
            val face = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: android.graphics.Outline) {
                        outline.setOval(0, 0, v.width, v.height)
                    }
                }
            }
            faceSlot.addView(face, FrameLayout.LayoutParams(theme.px(112f), theme.px(112f)))
            box.addView(faceSlot, LinearLayout.LayoutParams(theme.px(112f), theme.px(112f)))
            BrowseImages.load(face, s(person, "profile", ""), theme.px(112f)) { ok ->
                if (!isFinishing && !isDestroyed && ok) {
                    (faceSlot.getChildAt(0) as? TextView)?.visibility = View.GONE
                }
            }
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

    private fun episodeList(
        parent: LinearLayout,
        episodes: JSONArray,
        theme: com.scenix.tv.core.Theme,
        resumeSeason: Int,
    ) {
        // A television title page must answer two questions independently: “which season?” and
        // “which episode?”. The old implementation put every season in one scrolling column, so
        // finding S4 E08 meant passing through all of S1–S3. This is a stable, focused selector
        // followed by exactly one season's episodes—the same hierarchy viewers expect from a TV
        // detail surface.
        val sorted = ArrayList<JSONObject>()
        for (i in 0 until episodes.length()) episodes.optJSONObject(i)?.let { sorted.add(it) }
        sorted.sortWith(compareBy({ it.optInt("season", 1) }, { it.optInt("episode", 1) }))
        val bySeason = sorted.groupBy { it.optInt("season", 1) }
        val seasons = bySeason.keys.sorted()
        if (seasons.isEmpty()) return

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // The focused episode lockup deliberately grows past its own bounds. Let the halo
            // breathe instead of trimming it into a hard rectangle at the section edge.
            clipChildren = false
            clipToPadding = false
        }
        parent.addView(section, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = theme.px(30f) })

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(Views.text(app, "rowTitle", 27f, theme.ink, "Episodes", bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val seasonSummary = Views.text(app, "meta", 18f, theme.ink2, "", gravity = Gravity.END)
        heading.addView(seasonSummary)
        section.addView(heading, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = theme.px(12f) })

        val seasonStrip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            isFocusable = false
            clipToPadding = false
            setPadding(0, theme.px(4f), 0, theme.px(8f))
        }
        val seasonTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
        seasonStrip.addView(seasonTabs, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        section.addView(seasonStrip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val episodeHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        section.addView(episodeHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = theme.px(6f) })

        var selectedSeason = resumeSeason.takeIf { it in seasons } ?: seasons.first()
        var selectedTab: View? = null
        var firstEpisode: View? = null
        var episodeRail: HorizontalScrollView? = null
        val episodeTiles = ArrayList<View>()
        val tabWashes = LinkedHashMap<Int, View>()
        val tabLabels = LinkedHashMap<Int, TextView>()
        val tabViews = LinkedHashMap<Int, View>()
        val tabFocusPlates = LinkedHashMap<Int, View>()

        fun paintTabs() {
            for (season in seasons) {
                val current = season == selectedSeason
                tabWashes[season]?.visibility = if (current) View.VISIBLE else View.INVISIBLE
                tabLabels[season]?.setTextColor(if (current) theme.onBrand else theme.ink)
                // A white selected tab needs a dark focus keyline; otherwise selection and focus
                // are indistinguishable. An unselected tab keeps the normal white focus ring.
                val plate = tabFocusPlates[season]
                if (tabViews[season]?.hasFocus() == true && current) {
                    plate?.background = Views.ring(
                        Color.argb(225, 20, 21, 26), theme.px(999f), theme.px(3f),
                    )
                } else if (tabViews[season]?.hasFocus() != true) {
                    plate?.background = null
                }
            }
        }

        /**
         * Move inside the rail deliberately. Android's geometric focus search can choose a title
         * in the next section when a large landscape lockup is partly offscreen; naming the next
         * tile makes every remote press deterministic and keeps the newly focused artwork visible.
         */
        fun focusEpisode(index: Int): Boolean {
            val target = episodeTiles.getOrNull(index) ?: return false
            if (!target.requestFocus()) return false
            val rail = episodeRail
            rail?.post {
                // A season change replaces the entire rail. Never let a deferred scroll from the
                // old rail reposition a newly rendered season beneath the current focus.
                if (episodeRail === rail && target.parent != null) {
                    rail.scrollTo((target.left - theme.px(30f)).coerceAtLeast(0), 0)
                }
            }
            return true
        }

        fun addEpisode(ep: JSONObject, season: Int, railItems: LinearLayout) {
            val number = ep.optInt("episode", 1)
            // A TV episode is a thing to watch, not a settings row. Keep the familiar Apple TV
            // lockup shape: a large 16:9 still floats above its title and quiet supporting copy.
            // Four wide cards fit with a fifth peeking in, making the remote's Right affordance
            // obvious without a scrollbar or an oversized list panel.
            val cardW = theme.px(432f)
            val artH = theme.px(243f)
            val copyH = theme.px(104f)
            val cardH = artH + copyH
            val haloPad = if (theme.focusStyle == "glow") theme.focusGlowInset else theme.px(5f)
            val tile = FrameLayout(this).apply {
                id = View.generateViewId()
                isFocusable = true
                isClickable = true
                clipChildren = false
                clipToPadding = false
                contentDescription = "Season $season, episode $number, ${s(ep, "title", "Episode $number")}, play episode"
            }
            // The focus treatment belongs behind the still rather than around a dark boxed card.
            // This keeps the unselected state quiet and lets Apple/other theme packs supply their
            // own ring, glow, or lift through the shared Focus helper.
            val focusPlate = View(this)
            tile.addView(focusPlate, FrameLayout.LayoutParams(
                cardW + haloPad * 2, artH + haloPad * 2
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = -haloPad
                topMargin = -haloPad
            })
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
            tile.addView(thumb, FrameLayout.LayoutParams(cardW, artH))
            // The image loader samples to the target card width, so this rail stays crisp enough
            // to read without decoding hero-sized bitmaps for every episode.
            BrowseImages.load(thumb, s(ep, "thumb", ""), cardW)

            val badge = Views.text(app, "badge", 15f, theme.ink, "E%02d".format(number), bold = true,
                gravity = Gravity.CENTER)
            badge.setPadding(theme.px(12f), theme.px(6f), theme.px(12f), theme.px(6f))
            badge.background = Views.rounded(Color.argb(190, 0, 0, 0), theme.radius("badge", 8f))
            tile.addView(badge, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = theme.px(14f)
                topMargin = theme.px(14f)
            })

            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(theme.px(4f), theme.px(12f), theme.px(8f), 0)
            }
            val episodeTitle = s(ep, "title", "").ifBlank { "Episode $number" }
            copy.addView(Views.text(app, "li", 23f, theme.ink, episodeTitle, bold = true, maxLines = 1))
            val synopsis = s(ep, "synopsis", "")
            val durationMs = ep.optLong("durationMs", 0L)
            val detail = when {
                synopsis.isNotBlank() -> synopsis
                durationMs > 0L -> runtime(durationMs)
                else -> "Episode $number"
            }
            copy.addView(Views.text(app, "meta", 17f, theme.ink2, detail, maxLines = 2),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = theme.px(5f)
                })
            tile.addView(copy, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, copyH
            ).apply { topMargin = artH })

            tile.setOnClickListener { startPlay(season, number) }
            val tileIndex = episodeTiles.size
            Focus.attach(tile, focusPlate, app, theme.radius("card", 6f), animateScale = false,
                elevate = false, glowInset = haloPad,
                arrow = { code ->
                    when (code) {
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT ->
                            if (tileIndex == 0) true else focusEpisode(tileIndex - 1)
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                            if (tileIndex == episodeTiles.lastIndex) true else focusEpisode(tileIndex + 1)
                        android.view.KeyEvent.KEYCODE_DPAD_UP ->
                            selectedTab?.requestFocus() == true
                        else -> false
                    }
                })
            if (firstEpisode == null) firstEpisode = tile
            episodeTiles.add(tile)
            railItems.addView(tile, LinearLayout.LayoutParams(cardW, cardH).apply {
                marginEnd = theme.px(28f)
            })
        }

        fun renderSeason(season: Int) {
            selectedSeason = season
            paintTabs()
            val current = bySeason[season].orEmpty()
            seasonSummary.text = "Season $season · ${current.size} episode${if (current.size == 1) "" else "s"}"
            episodeHost.removeAllViews()
            firstEpisode = null
            episodeTiles.clear()

            val cardH = theme.px(243f) + theme.px(104f)
            val haloPad = if (theme.focusStyle == "glow") theme.focusGlowInset else theme.px(5f)
            val rail = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = false
                isFocusable = false
                isFocusableInTouchMode = false
                overScrollMode = View.OVER_SCROLL_NEVER
                clipChildren = false
                clipToPadding = false
                // The inset reserves visual room for a halo while still allowing a next card to
                // peek in at the trailing edge.
                setPadding(haloPad, haloPad, haloPad, haloPad)
            }
            val railItems = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
            }
            rail.addView(railItems, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            episodeRail = rail
            current.forEach { addEpisode(it, season, railItems) }
            episodeHost.addView(rail, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, cardH + haloPad * 2
            ).apply { topMargin = theme.px(2f) })
        }

        fun focusSeason(season: Int): Boolean {
            val target = tabViews[season] ?: return false
            // A season chip is a real selector, not merely a focus stop. Switching as the remote
            // moves keeps its white selected state, the summary, and the episode rail in sync.
            selectedTab = target
            if (selectedSeason != season) renderSeason(season)
            if (!target.requestFocus()) return false
            seasonStrip.post {
                seasonStrip.scrollTo((target.left - theme.px(32f)).coerceAtLeast(0), 0)
            }
            return true
        }

        for (season in seasons) {
            val tab = FrameLayout(this).apply {
                id = View.generateViewId()
                isFocusable = true
                isClickable = true
                clipChildren = false
            }
            val wash = View(this).apply {
                background = if (isGlassPack)
                    Views.rounded(theme.color("chipFillActive", "rgba(255,255,255,0.16)"),
                        theme.px(999f), theme.px(1f), theme.color("glassRim", "rgba(255,255,255,0.30)"))
                else Views.rounded(theme.brand, theme.px(999f))
                visibility = View.INVISIBLE
            }
            val label = Views.text(app, "button", 19f, theme.ink, "Season $season", bold = true,
                gravity = Gravity.CENTER)
            label.setPadding(theme.px(21f), theme.px(11f), theme.px(21f), theme.px(11f))
            val focusPlate = View(this)
            tab.addView(wash, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            tab.addView(label, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            tab.addView(focusPlate, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            tab.setOnClickListener {
                selectedTab = tab
                if (selectedSeason != season) renderSeason(season)
            }
            Focus.attach(tab, focusPlate, app, theme.px(999f), scale = false, elevate = false,
                onFocus = { focused ->
                    if (focused && season == selectedSeason) {
                        focusPlate.background = Views.ring(
                            Color.argb(225, 20, 21, 26), theme.px(999f), theme.px(3f),
                        )
                    }
                },
                arrow = { code ->
                    val index = seasons.indexOf(season)
                    when (code) {
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                            firstEpisode?.requestFocus() == true
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                            seasons.getOrNull(index - 1)?.let { focusSeason(it) } ?: true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            seasons.getOrNull(index + 1)?.let { focusSeason(it) } ?: true
                        }
                        else -> false
                    }
                })
            tabWashes[season] = wash
            tabLabels[season] = label
            tabViews[season] = tab
            tabFocusPlates[season] = focusPlate
            seasonTabs.addView(tab, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = theme.px(12f) })
        }
        selectedTab = tabViews[selectedSeason]
        renderSeason(selectedSeason)
    }

    private fun startPlay(season: Int, episode: Int, fromStart: Boolean = false) {
        val record = item ?: return
        val progress = record.optJSONObject("progress")
        val progressEpisode = progress?.optJSONObject("episode")
        // Resume only the episode the viewer actually stopped in. A Play on S1 E1 must not open at
        // S1 E4's playhead just because that is what Continue Watching remembers.
        val sameEpisode = season > 0 && episode > 0 &&
            progressEpisode?.optInt("season", 0) == season &&
            progressEpisode?.optInt("episode", 0) == episode
        val position = if (fromStart) 0L else if ((season <= 0 && episode <= 0) || sameEpisode) {
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
