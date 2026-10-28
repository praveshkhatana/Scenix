package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val sc = application as ScenixApp
        app = sc.ctx
        profileId = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
        if (profileId.isBlank()) {
            // Never guess who is watching. The web profile gate is the one place a PIN is checked.
            startActivity(Intent(this, WebShellActivity::class.java)
                .putExtra(WebShellActivity.EXTRA_URL, WebShellActivity.shellUrl(this, "#/profiles")))
            finish()
            return
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(app.theme.bg)
        root.addView(topBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(96f)))

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@BrowseActivity)
            setHasFixedSize(false)
            setBackgroundColor(app.theme.bg)
            itemAnimator = null
            clipToPadding = false
            setPadding(0, app.theme.px(16f), 0, app.theme.px(64f))
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        status = Views.text(app, "sub", 24f, app.theme.ink2, "Loading the shelves…", gravity = Gravity.CENTER)
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(80f)))
        setContentView(root)
        load()
    }

    private fun topBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(app.theme.gutter, 0, app.theme.gutter, 0)
        bar.setBackgroundColor(Color.rgb(20, 20, 20))
        bar.addView(
            Views.text(app, "logo", 30f, app.theme.brand, "SCENIX", bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(navButton("Search") { openWeb("#/search") })
        bar.addView(navButton("Settings", app.theme.px(28f)) { openWeb("#/settings") })
        return bar
    }

    private fun navButton(label: String, left: Int = 0, action: () -> Unit): View {
        val b = Views.text(app, "li", 22f, app.theme.ink, label, bold = true, gravity = Gravity.CENTER)
        b.setPadding(app.theme.px(18f), app.theme.px(10f), app.theme.px(18f), app.theme.px(10f))
        b.isFocusable = true
        b.isClickable = true
        b.setOnClickListener { action() }
        Focus.attach(b, b, app, app.theme.radius("button", 6f), scale = false, elevate = false)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginStart = left
        b.layoutParams = lp
        return b
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
            val hero = rows.firstOrNull { it.id == "hero" }?.items?.firstOrNull()
            val shelves = rows.filter { it.id != "hero" && it.items.isNotEmpty() }
            val adapter = BrowseAdapter(hero, shelves)
            list.adapter = adapter
            list.postDelayed({
                val first = adapter.heroPlay ?: firstFocusable(list) ?: return@postDelayed
                first.requestFocus()
            }, 700)
        }
    }

    private fun firstFocusable(parent: ViewGroup): View? {
        for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i)
            if (c.isFocusable && c.visibility == View.VISIBLE) return c
            if (c is ViewGroup) firstFocusable(c)?.let { return it }
        }
        return null
    }

    private fun openWeb(hash: String) {
        val route = "?profile=${Uri.encode(profileId)}$hash"
        startActivity(
            Intent(this, WebShellActivity::class.java)
                .putExtra(WebShellActivity.EXTRA_URL, WebShellActivity.shellUrl(this, route))
                .putExtra(WebShellActivity.EXTRA_FROM_BROWSE, true)
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

        override fun getItemCount(): Int = (if (hero != null) 1 else 0) + shelves.size

        override fun getItemViewType(position: Int): Int =
            if (hero != null && position == 0) TYPE_HERO else TYPE_SHELF

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val theme = app.theme
            return if (viewType == TYPE_HERO) {
                val slot = FrameLayout(this@BrowseActivity)
                slot.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(540f))
                val art = ImageView(this@BrowseActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(theme.surface2)
                }
                slot.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                val scrim = View(this@BrowseActivity).apply {
                    background = GradientDrawable(
                        GradientDrawable.Orientation.BOTTOM_TOP,
                        intArrayOf(theme.bg, Color.TRANSPARENT)
                    )
                }
                slot.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                val words = LinearLayout(this@BrowseActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.BOTTOM or Gravity.START
                    setPadding(theme.gutter, theme.px(32f), theme.gutter, theme.px(44f))
                }
                val title = Views.text(app, "h1", 54f, theme.ink, "", bold = true)
                val meta = Views.text(app, "sub", 22f, theme.ink2, "")
                val play = Views.text(app, "button", 22f, theme.ink, "Play", bold = true, gravity = Gravity.CENTER)
                play.setPadding(theme.px(32f), theme.px(14f), theme.px(32f), theme.px(14f))
                play.background = Views.rounded(theme.brand, theme.radius("button", 6f))
                play.isFocusable = true
                play.isClickable = true
                Focus.attach(play, play, app, theme.radius("button", 6f), scale = false, elevate = false)
                words.addView(title)
                words.addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = theme.px(10f)
                })
                words.addView(play, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = theme.px(22f)
                })
                slot.addView(words, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                HeroHolder(slot, art, title, meta, play)
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
                val item = hero ?: return
                holder.title.text = item.title
                holder.meta.text = listOf(item.year.takeIf { it > 0 }?.toString(), item.maturity, item.genres.firstOrNull())
                    .filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
                BrowseImages.load(holder.art, item.poster ?: item.backdrop, app.theme.px(1100f))
                heroPlay = holder.play
                holder.play.setOnClickListener { openDetail(item) }
                holder.itemView.setOnClickListener { openDetail(item) }
            } else if (holder is ShelfHolder) {
                val row = shelves[position - (if (hero != null) 1 else 0)]
                holder.head.text = row.title
                holder.rail.adapter = CardAdapter(row)
            }
        }
    }

    private inner class HeroHolder(
        view: View, val art: ImageView, val title: TextView, val meta: TextView, val play: TextView,
    ) : RecyclerView.ViewHolder(view)

    private inner class ShelfHolder(
        view: View, val head: TextView, val rail: RecyclerView,
    ) : RecyclerView.ViewHolder(view)

    private inner class CardAdapter(private val row: BrowseRow) : RecyclerView.Adapter<CardHolder>() {
        private val theme = app.theme
        private val poster = row.card == "poster" || row.card == "hero"
        private val cardW = theme.px(if (poster) 280f else 320f)
        private val cardH = theme.px(if (poster) 420f else 180f)

        override fun getItemCount(): Int = row.items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
            val card = FrameLayout(this@BrowseActivity)
            card.layoutParams = RecyclerView.LayoutParams(cardW, cardH)
            card.isFocusable = true
            card.isClickable = true
            card.setBackgroundColor(Color.TRANSPARENT)
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
            card.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val shade = View(this@BrowseActivity).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(Color.argb(235, 0, 0, 0), Color.TRANSPARENT)
                )
            }
            card.addView(shade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(140f)).apply {
                gravity = Gravity.BOTTOM
            })
            val name = Views.text(app, "caption", 19f, theme.ink, "", bold = true, maxLines = 2)
            name.setPadding(theme.px(12f), 0, theme.px(12f), theme.px(10f))
            card.addView(name, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            })
            Focus.attach(card, card, app, theme.radius("card", 6f), elevate = false)
            return CardHolder(card, art, name)
        }

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            val item = row.items[position]
            holder.name.text = item.title
            BrowseImages.load(holder.art, item.poster ?: item.backdrop, cardW)
            holder.itemView.setOnClickListener { openDetail(item) }
        }
    }

    private inner class CardHolder(
        view: View, val art: ImageView, val name: TextView,
    ) : RecyclerView.ViewHolder(view)

    companion object {
        const val EXTRA_PROFILE = "profile"
        private const val TYPE_HERO = 1
        private const val TYPE_SHELF = 2
    }
}
