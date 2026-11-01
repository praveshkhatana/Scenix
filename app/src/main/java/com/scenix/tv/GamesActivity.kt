package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
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
import com.scenix.tv.core.Views
import org.json.JSONArray

/** One shelf of the Game mode, and one card on it, as the hub's /app/v1/games answers them. */
data class GameCard(
    val identity: String,
    val title: String,
    val poster: String,
    val playUrl: String,
    /** A second address for titles whose first host refuses this region; empty when there is none. */
    val fallbackUrl: String,
    /** True when the port's server demands cross-origin isolation — Android WebView never grants it. */
    val coi: Boolean,
    /** Whether this port answers a real controller — the badge that says so rides the tile. */
    val pad: Boolean,
    val desc: String,
)

data class GameRow(val id: String, val title: String, val items: List<GameCard>)

/**
 * The Game mode: shelves of titles that run right here, in this app, on the television's browser —
 * console and PC ports from the hub's curated directory plus the web arcades. A game is not a
 * video title; it has no sources and no subtitles. It has one address to open full-screen and an
 * answer to the only question that matters on a couch: does it work with a controller.
 *
 * The screen wears the same clothes as the rest of the native front end — floating bar, breathing
 * shelves, art first and the name underneath, the pack's focus language on every tile.
 */
class GamesActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var profileId: String
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }

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
        val root = FrameLayout(this).apply { setBackgroundColor(theme.bg) }
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@GamesActivity)
            setHasFixedSize(true)
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            id = View.generateViewId()
            setPadding(0, theme.px(112f), 0, theme.px(56f))
            setBackgroundColor(Color.TRANSPARENT)
        }
        root.addView(list, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(topBar(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(96f)
        ))
        status = Views.text(app, "sub", 23f, theme.ink2, "Opening the arcade…", gravity = Gravity.CENTER)
        root.addView(status, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(64f)
        ).apply { gravity = Gravity.BOTTOM })
        setContentView(root)
        load()
    }

    /** The floating bar: a feather chevron out, and the mode's name. */
    private fun topBar(): View {
        val theme = app.theme
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.gutter, theme.px(10f), theme.gutter, 0)
        }
        val back = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_left)
            setColorFilter(theme.ink, android.graphics.PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isFocusable = true
            isClickable = true
            setOnClickListener { finish() }
        }
        Focus.attach(back, back, app, theme.px(9f), scale = false, elevate = false)
        bar.addView(back, LinearLayout.LayoutParams(theme.px(34f), theme.px(34f)))
        bar.addView(Views.text(app, "rowTitle", 26f, theme.ink, "Games", bold = true).apply {
            setPadding(theme.px(26f), 0, 0, 0)
        })
        return bar
    }

    private fun load() {
        status.visibility = View.VISIBLE
        status.text = "Opening the arcade…"
        api.games { rowsJson, error ->
            if (isFinishing || isDestroyed) return@games
            val rows = parseRows(rowsJson)
            if (rows.isEmpty()) {
                status.text = error ?: "The hub has no games to show right now"
                return@games
            }
            status.visibility = View.GONE
            list.adapter = ShelfAdapter(rows)
            list.postDelayed({
                (list.findViewHolderForAdapterPosition(0)?.itemView ?: list.getChildAt(0))?.requestFocus()
            }, 150)
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
                cards.add(
                    GameCard(
                        identity = o.optString("identity"),
                        title = o.optString("title"),
                        poster = o.optString("backdrop").ifBlank { o.optString("poster") },
                        playUrl = url,
                        fallbackUrl = o.optString("fallbackUrl"),
                        coi = o.optBoolean("coi"),
                        pad = o.optBoolean("pad"),
                        desc = o.optString("desc"),
                    )
                )
            }
            if (cards.isNotEmpty()) out.add(GameRow(r.optString("id"), r.optString("title"), cards))
        }
        return out
    }

    private inner class ShelfAdapter(private val rows: List<GameRow>) :
        RecyclerView.Adapter<ShelfHolder>() {

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShelfHolder {
            val theme = app.theme
            val box = LinearLayout(this@GamesActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(0, theme.px(18f), 0, 0)
            }
            val head = Views.text(app, "rowTitle", 28f, theme.ink, "", bold = true)
            head.setPadding(theme.gutter, 0, theme.gutter, theme.px(12f))
            box.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val rail = RecyclerView(this@GamesActivity).apply {
                layoutManager = LinearLayoutManager(this@GamesActivity, LinearLayoutManager.HORIZONTAL, false)
                setHasFixedSize(true)
                setItemViewCacheSize(16)
                itemAnimator = null
                isNestedScrollingEnabled = false
                setPadding(theme.gutter, theme.px(18f), theme.gutter, theme.px(26f))
                clipToPadding = false
                clipChildren = false
            }
            box.addView(rail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return ShelfHolder(box, head, rail)
        }

        override fun onBindViewHolder(holder: ShelfHolder, position: Int) {
            val row = rows[position]
            holder.head.text = row.title
            holder.rail.adapter = CardAdapter(row.items)
        }
    }

    private inner class ShelfHolder(view: View, val head: TextView, val rail: RecyclerView) :
        RecyclerView.ViewHolder(view)

    /** Wide landscape tiles — a game's art is its screen, so it is drawn as one. */
    private inner class CardAdapter(private val items: List<GameCard>) : RecyclerView.Adapter<CardHolder>() {
        private val theme = app.theme
        private val cardW = theme.px(480f)
        private val artH = theme.px(270f)
        private val captionH = theme.px(74f)
        private val radius = theme.radius("card", 6f)

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
            val card = FrameLayout(this@GamesActivity)
            card.layoutParams = RecyclerView.LayoutParams(cardW, artH + captionH).apply {
                marginEnd = theme.px(26f)
            }
            card.isFocusable = true
            card.isClickable = true
            val art = ImageView(this@GamesActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(theme.surface2)
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, radius.toFloat())
                    }
                }
            }
            card.addView(art, FrameLayout.LayoutParams(cardW, artH).apply { gravity = Gravity.TOP })
            // The name rides under the art like every other tile in this app.
            val name = Views.text(app, "caption", 18f, theme.ink, "", bold = true, maxLines = 1)
            name.setPadding(theme.px(4f), theme.px(12f), theme.px(4f), 0)
            card.addView(name, FrameLayout.LayoutParams(cardW, captionH).apply { gravity = Gravity.BOTTOM })
            val sub = Views.text(app, "meta", 14f, theme.ink3, "", maxLines = 1)
            sub.setPadding(theme.px(4f), 0, theme.px(4f), theme.px(4f))
            card.addView(sub, FrameLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM; bottomMargin = theme.px(4f)
            })
            // The gamepad badge is the couch's whole question answered at a glance.
            val pad = Views.text(app, "meta", 13f, Color.WHITE, "GAMEPAD", bold = true, maxLines = 1)
            pad.setPadding(theme.px(8f), theme.px(3f), theme.px(8f), theme.px(3f))
            pad.background = Views.rounded(Color.argb(185, 8, 8, 8), theme.px(5f))
            card.addView(pad, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = theme.px(10f); topMargin = theme.px(10f)
            })
            val glowPad = theme.px(26f)
            val ringOn = View(this@GamesActivity)
            card.addView(ringOn, FrameLayout.LayoutParams(cardW, artH + glowPad * 2).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = -glowPad
            })
            card.clipChildren = false
            Focus.attach(card, ringOn, app, radius, animateScale = false, elevate = false,
                glowInset = glowPad)
            return CardHolder(card, art, name, sub, pad)
        }

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            val g = items[position]
            holder.name.text = g.title
            holder.sub.text = if (g.pad) "Gamepad ready" else "Remote & keys"
            holder.pad.visibility = if (g.pad) View.VISIBLE else View.GONE
            BrowseImages.load(holder.art, g.poster, theme.px(640f))
            holder.itemView.setOnClickListener { open(g) }
        }

        private fun open(g: GameCard) {
            // Multithreaded ports need SharedArrayBuffer, which Android's WebView never grants —
            // those go to the Gecko engine embedded in our own APK instead.
            startActivity(
                Intent(this@GamesActivity, if (g.coi) CoiGameActivity::class.java else GameActivity::class.java)
                    .putExtra(GameActivity.EXTRA_TITLE, g.title)
                    .putExtra(GameActivity.EXTRA_URL, g.playUrl)
                    .putExtra(GameActivity.EXTRA_FALLBACK, g.fallbackUrl)
                    .putExtra(GameActivity.EXTRA_COI, g.coi)
                    .putExtra(GameActivity.EXTRA_PAD, g.pad)
            )
        }
    }

    private inner class CardHolder(
        view: View, val art: ImageView, val name: TextView, val sub: TextView, val pad: TextView,
    ) : RecyclerView.ViewHolder(view)

    companion object {
        const val EXTRA_PROFILE = "profile"
    }
}
