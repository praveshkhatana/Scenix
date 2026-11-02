package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
    /** "desktop" for hosts that answer web portals only when the browser claims a desktop UA. */
    val ua: String,
    val desc: String,
)

data class GameRow(val id: String, val title: String, val items: List<GameCard>)

/**
 * The Game mode: shelves of titles that run right here, in this app, on the television's browser —
 * console and PC ports from the hub's curated directory plus the web arcades. A game is not a
 * video title; it has no sources and no subtitles. It has one address to open full-screen and an
 * answer to the only question that matters on a couch: does it work with a controller.
 *
 * The screen wears the same clothes as the rest of the native front end — the pack's bar wearing
 * the wordmark, a billboard for the featured title with its own Play button (the same stage Movies
 * and Series open on), breathing shelves, art first and the name underneath, the pack's focus
 * language on every tile.
 */
class GamesActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var profileId: String
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private var heroCard: GameCard? = null
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

    /** The floating bar: a feather chevron out, the owner's wordmark, and the mode's name. */
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
        // The wordmark, not styled text: this bar is a brand placement like every other, and its
        // letterforms must not depend on the font the television happens to carry.
        val brand = ImageView(this).apply {
            setImageResource(R.drawable.scenix_wordmark)
            setColorFilter(theme.brand, android.graphics.PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.FIT_START
            adjustViewBounds = true
            contentDescription = "Scenix"
        }
        bar.addView(brand, LinearLayout.LayoutParams(theme.px(80f), theme.px(28f)).apply {
            marginStart = theme.px(26f)
        })
        bar.addView(Views.text(app, "meta", 17f, theme.ink2, "Games", bold = true).apply {
            setPadding(theme.px(18f), 0, 0, 0)
        })
        return bar
    }

    private fun load() {
        status.visibility = View.VISIBLE
        status.text = "Opening the arcade…"
        api.games { rowsJson, error ->
            if (isFinishing || isDestroyed) return@games
            val parsed = parseRows(rowsJson)
            if (parsed.isEmpty()) {
                status.text = error ?: "The hub has no games to show right now"
                return@games
            }
            // Movies and Series open on a billboard; Game mode should not open on a bare row head.
            // The featured title becomes a stage of its own — art to the edges, its own Play button,
            // the controller answer beside the name. It is lifted out of its shelf so the same game
            // does not greet the viewer twice on one screen.
            val hero = parsed.firstOrNull { it.id == "featured" }?.items?.firstOrNull()
                ?: parsed.firstOrNull()?.items?.firstOrNull()
            heroCard = hero
            val rows = if (hero == null) parsed else parsed
                .map { row ->
                    if (row.items.contains(hero)) GameRow(row.id, row.title, row.items.filter { it !== hero })
                    else row
                }
                .filter { it.items.isNotEmpty() }
            status.visibility = View.GONE
            list.adapter = ShelfAdapter(rows, hero)
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
                        ua = o.optString("ua"),
                        desc = o.optString("desc"),
                    )
                )
            }
            if (cards.isNotEmpty()) out.add(GameRow(r.optString("id"), r.optString("title"), cards))
        }
        return out
    }

    private inner class ShelfAdapter(private val rows: List<GameRow>, private val hero: GameCard?) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = rows.size + (if (hero != null) 1 else 0)

        override fun getItemViewType(position: Int): Int =
            if (hero != null && position == 0) TYPE_HERO else TYPE_SHELF

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == TYPE_HERO) HeroHolder(createHero()) else createShelf()

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is HeroHolder) bindHero(holder, hero!!)
            else if (holder is ShelfHolder) {
                val row = rows[position - (if (hero != null) 1 else 0)]
                holder.head.text = row.title
                holder.rail.adapter = CardAdapter(row.items)
            }
        }

        // ---- the billboard ---------------------------------------------------------------------

        private lateinit var heroArt: ImageView
        private lateinit var heroName: TextView
        private lateinit var heroSub: TextView
        private lateinit var heroDesc: TextView
        private lateinit var heroPad: TextView
        private lateinit var heroPlay: TextView

        private fun createHero(): View {
            val theme = app.theme
            val h = theme.px(620f)
            val frame = FrameLayout(this@GamesActivity).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
                clipChildren = false
            }
            heroArt = ImageView(this@GamesActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(theme.surface2)
            }
            frame.addView(heroArt, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
            // A left-to-dark scrim carries the copy off the art the way the film hero does; flat
            // text over a bright screenshot reads like a different app.
            frame.addView(
                View(this@GamesActivity).apply {
                    background = GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(Color.argb(215, 0, 0, 0), Color.argb(60, 0, 0, 0), Color.TRANSPARENT),
                    )
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h),
            )
            frame.addView(
                View(this@GamesActivity).apply {
                    background = GradientDrawable(
                        GradientDrawable.Orientation.BOTTOM_TOP,
                        intArrayOf(theme.bg, Color.TRANSPARENT),
                    )
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(120f))
                    .apply { gravity = Gravity.BOTTOM },
            )
            val copy = LinearLayout(this@GamesActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(theme.gutter, 0, theme.gutter, theme.px(50f))
            }
            heroPad = Views.text(app, "meta", 14f, Color.WHITE, "GAMEPAD READY", bold = true, maxLines = 1)
            heroPad.setPadding(theme.px(10f), theme.px(4f), theme.px(10f), theme.px(4f))
            heroPad.background = Views.rounded(Color.argb(170, 8, 8, 8), theme.px(5f))
            heroPad.visibility = View.GONE
            copy.addView(heroPad, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = theme.px(14f) })
            heroName = Views.text(app, "display", 58f, theme.ink, "", bold = true, maxLines = 2)
            copy.addView(heroName, LinearLayout.LayoutParams(theme.px(760f), ViewGroup.LayoutParams.WRAP_CONTENT))
            heroSub = Views.text(app, "meta", 16f, theme.ink2, "", maxLines = 1)
            heroSub.setPadding(0, theme.px(10f), 0, 0)
            copy.addView(heroSub, LinearLayout.LayoutParams(theme.px(760f), ViewGroup.LayoutParams.WRAP_CONTENT))
            heroDesc = Views.text(app, "sub", 19f, theme.ink2, "", maxLines = 2)
            heroDesc.setPadding(0, theme.px(10f), 0, 0)
            copy.addView(heroDesc, LinearLayout.LayoutParams(theme.px(700f), ViewGroup.LayoutParams.WRAP_CONTENT))
            heroPlay = Views.text(app, "li", 22f, Color.WHITE, "PLAY", bold = true, gravity = Gravity.CENTER)
            heroPlay.setPadding(theme.px(44f), theme.px(13f), theme.px(44f), theme.px(13f))
            heroPlay.background = Views.rounded(theme.brand, theme.px(999f))
            copy.addView(heroPlay, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = theme.px(26f) })
            frame.addView(copy, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.BOTTOM or Gravity.START })
            return frame
        }

        private fun bindHero(holder: HeroHolder, g: GameCard) {
            val theme = app.theme
            heroName.text = g.title
            heroSub.text = if (g.pad) "Controller ready — play it from the couch" else "Remote & keyboard"
            heroDesc.text = g.desc
            heroDesc.visibility = if (g.desc.isBlank()) View.GONE else View.VISIBLE
            heroPad.visibility = if (g.pad) View.VISIBLE else View.GONE
            BrowseImages.load(heroArt, g.poster, theme.px(1280f))
            heroPlay.setOnClickListener { open(g) }
            Focus.attach(heroPlay, heroPlay, app, theme.px(999f), scale = false, elevate = false)
            // The stage takes the first press; DOWN walks into the shelves below it.
            heroPlay.nextFocusDownId = list.id
            heroPlay.post { heroPlay.requestFocus() }
        }

        // ---- the shelves -----------------------------------------------------------------------

        private fun createShelf(): ShelfHolder {
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
    }

    private inner class ShelfHolder(view: View, val head: TextView, val rail: RecyclerView) :
        RecyclerView.ViewHolder(view)

    private inner class HeroHolder(view: View) : RecyclerView.ViewHolder(view)

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

    }

    /** One address, opened full-screen; the engine is chosen by what the port demands. */
    private fun open(g: GameCard) {
        // Multithreaded ports need SharedArrayBuffer, which Android's WebView never grants —
        // those go to the Gecko engine embedded in our own APK instead.
        startActivity(
            Intent(this, if (g.coi) CoiGameActivity::class.java else GameActivity::class.java)
                .putExtra(GameActivity.EXTRA_TITLE, g.title)
                .putExtra(GameActivity.EXTRA_URL, g.playUrl)
                .putExtra(GameActivity.EXTRA_FALLBACK, g.fallbackUrl)
                .putExtra(GameActivity.EXTRA_COI, g.coi)
                .putExtra(GameActivity.EXTRA_PAD, g.pad)
                .putExtra(GameActivity.EXTRA_DESKTOP_UA, g.ua == "desktop")
        )
    }

    private inner class CardHolder(
        view: View, val art: ImageView, val name: TextView, val sub: TextView, val pad: TextView,
    ) : RecyclerView.ViewHolder(view)

    companion object {
        const val EXTRA_PROFILE = "profile"
        private const val TYPE_HERO = 0
        private const val TYPE_SHELF = 1
    }
}
