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
import com.scenix.tv.core.Views
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
        if (profileId.isBlank() || identity.isBlank()) {
            finish()
            return
        }
        scroll = ScrollView(this).apply { setBackgroundColor(app.theme.bg) }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, app.theme.px(64f))
        }
        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        loading()
        api.meta(identity, profileId) { record, error ->
            if (isFinishing || isDestroyed) return@meta
            if (record == null) {
                body.removeAllViews()
                body.addView(Views.text(app, "sub", 24f, app.theme.ink2, error ?: "This title is not available", gravity = Gravity.CENTER),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(240f)))
            } else {
                item = record
                paint(record)
            }
        }
    }

    private fun loading() {
        val wait = Views.text(app, "sub", 24f, app.theme.ink2, "Reading this title…", gravity = Gravity.CENTER)
        body.addView(wait, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(400f)))
    }

    private fun paint(record: JSONObject) {
        body.removeAllViews()
        val theme = app.theme
        val title = record.optString("title", "")
        val backdrop = record.optString("backdrop", "").ifBlank { record.optString("poster", "") }
        val poster = record.optString("poster", "")
        val type = record.optString("type", "movie")
        val year = record.optInt("year", 0)
        val maturity = record.optString("maturity", "")
        val duration = record.optLong("durationMs", 0L)

        val hero = FrameLayout(this).apply { setBackgroundColor(theme.surface2) }
        body.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(600f)))
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

        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.START
            setPadding(theme.gutter, theme.px(40f), theme.gutter, theme.px(36f))
        }
        words.addView(Views.text(app, "h1", 54f, theme.ink, title, bold = true, maxLines = 2))
        val meta = ArrayList<String>()
        if (year > 0) meta.add(year.toString())
        if (maturity.isNotBlank()) meta.add(maturity)
        if (duration > 0) meta.add(runtime(duration))
        if (!record.isNull("tagline")) {
            val tagline = record.optString("tagline", "").trim()
            if (tagline.isNotBlank() && !tagline.equals("null", true)) meta.add(tagline)
        }
        if (meta.isNotEmpty()) {
            words.addView(Views.text(app, "sub", 22f, theme.ink2, meta.joinToString(" · "), maxLines = 2),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = theme.px(12f)
                })
        }
        val advisories = record.optJSONArray("advisories")
        if (advisories != null && advisories.length() > 0) {
            val text = (0 until advisories.length()).map { advisories.optString(it) }.filter { it.isNotBlank() }.joinToString(" · ")
            words.addView(Views.text(app, "sub", 19f, theme.ink3, text, maxLines = 2),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = theme.px(8f)
                })
        }
        val overview = if (record.isNull("overview")) "" else record.optString("overview", "")
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
        val play = Views.text(app, "button", 23f, theme.ink, playText, bold = true, gravity = Gravity.CENTER)
        play.setPadding(theme.px(38f), theme.px(16f), theme.px(38f), theme.px(16f))
        play.background = Views.rounded(theme.brand, theme.radius("button", 6f))
        play.isFocusable = true
        play.isClickable = true
        play.setOnClickListener { startPlay(playSeason, playEpisode) }
        Focus.attach(play, play, app, theme.radius("button", 6f), scale = false, elevate = false)
        actions.addView(play, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        words.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = theme.px(24f)
        })
        hero.addView(words, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.gutter, theme.px(12f), theme.gutter, 0)
        }
        body.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val cast = record.optJSONArray("cast")
        if (cast != null && cast.length() > 0) castRow(content, cast, theme)

        if (episodes != null && episodes.length() > 0) episodeList(content, episodes, theme)
        scroll.post { play.requestFocus() }
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
            BrowseImages.load(face, person.optString("profile", ""), theme.px(112f))
            box.addView(Views.text(app, "caption", 18f, theme.ink, person.optString("name", ""), maxLines = 1, gravity = Gravity.CENTER),
                LinearLayout.LayoutParams(theme.px(150f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = theme.px(8f) })
            box.addView(Views.text(app, "meta", 15f, theme.ink3, person.optString("character", ""), maxLines = 1, gravity = Gravity.CENTER),
                LinearLayout.LayoutParams(theme.px(150f), ViewGroup.LayoutParams.WRAP_CONTENT))
            strip.addView(box)
        }
        val h = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        h.addView(strip)
        parent.addView(h, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun episodeList(parent: LinearLayout, episodes: JSONArray, theme: com.scenix.tv.core.Theme) {
        parent.addView(Views.text(app, "rowTitle", 27f, theme.ink, "Episodes", bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = theme.px(24f); bottomMargin = theme.px(10f)
            })
        for (i in 0 until minOf(episodes.length(), 24)) {
            val ep = episodes.optJSONObject(i) ?: continue
            val season = ep.optInt("season", 1)
            val number = ep.optInt("episode", i + 1)
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
            BrowseImages.load(thumb, ep.optString("thumb", ""), theme.px(160f))
            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(theme.px(14f), 0, 0, 0)
            }
            copy.addView(Views.text(app, "li", 22f, theme.ink,
                "S$season E$number · " + ep.optString("title", ""), bold = true, maxLines = 1))
            val synopsis = ep.optString("synopsis", "")
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
                val v = arr.optString(i, "")
                if (v.isNotBlank()) advisories.add(v)
            }
        }
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_PROFILE, profileId)
                .putExtra(PlayerActivity.EXTRA_IDENTITY, identity)
                .putExtra(PlayerActivity.EXTRA_TYPE, record.optString("type", "movie"))
                .putExtra(PlayerActivity.EXTRA_TITLE, record.optString("title", ""))
                .putExtra(PlayerActivity.EXTRA_SEASON, season)
                .putExtra(PlayerActivity.EXTRA_EPISODE, episode)
                .putExtra(PlayerActivity.EXTRA_POSITION_MS, position)
                .putExtra(PlayerActivity.EXTRA_MATURITY, record.optString("maturity", ""))
                .putExtra(PlayerActivity.EXTRA_POSTER, record.optString("poster", ""))
                .putExtra(PlayerActivity.EXTRA_LOGO, record.optString("logo", ""))
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
    }
}
