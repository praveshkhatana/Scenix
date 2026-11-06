package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.CardActionSheet
import com.scenix.tv.core.CardActions
import com.scenix.tv.core.Motion
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Item
import com.scenix.tv.core.Screens
import com.scenix.tv.core.Views

/**
 * Native search: a remote keyboard and the hub's own index, with no WebView in between.
 *
 * The layout follows the same grammar as Home. Letters are real focus stops on the left, results are
 * poster cards on the right, and the query line is the only text on screen that changes while typing.
 * A search request goes out after a short pause, never on every key, and a stale answer (the viewer
 * kept typing) is dropped by generation.
 */
class SearchActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var profileId: String
    private lateinit var queryView: TextView
    private lateinit var status: TextView
    private lateinit var results: RecyclerView
    private lateinit var gridManager: GridLayoutManager
    private lateinit var resultHeading: TextView
    private lateinit var emptyResults: TextView
    private lateinit var keys: LinearLayout
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }
    private val handler = Handler(Looper.getMainLooper())
    private val query = StringBuilder()
    private var searchGen = 0

    private val searchNow = Runnable { runSearch() }

    /** Text standing directly on the pack's wall (Glass draws a dark wall ink over its light wash). */
    private val wallInk get() = app.theme.colorOr("wallInk", app.theme.ink)
    private val wallInk2 get() = app.theme.colorOr("wallInk2", app.theme.ink2)
    private val wallFaint get() = app.theme.colorOr("wallInk2", app.theme.ink3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        profileId = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
        if (profileId.isBlank()) { finish(); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // The Glass pack's search stands on the same wall as every other screen; the
            // legacy flat page colour stays for the other packs.
            val ground = Screens.wall(app)
            if (ground != null) background = ground else setBackgroundColor(app.theme.bg)
        }
        root.addView(topBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(88f)))

        // A real search field anchors the screen.  The old bare line plus a keyboard in one corner
        // read as unfinished because the results area had no relationship to the query.
        val queryShell = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(app.theme.px(24f), 0, app.theme.px(24f), 0)
            background = Views.rounded(app.theme.surface2, app.theme.radius("field", 14f))
        }
        queryView = Views.text(app, "li", 22f, wallInk, "", bold = true, maxLines = 1)
        queryView.setTextSize(TypedValue.COMPLEX_UNIT_PX, app.theme.px(38f).toFloat())
        queryShell.addView(queryView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(queryShell, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(78f)
        ).apply {
            leftMargin = app.theme.gutter
            rightMargin = app.theme.gutter
            topMargin = app.theme.px(18f)
        })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(app.theme.gutter, 0, app.theme.gutter, app.theme.px(32f))
        }
        keys = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, app.theme.px(10f), 0, app.theme.px(12f))
        }
        content.addView(keys, LinearLayout.LayoutParams(app.theme.px(700f), ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(resultsPane(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = app.theme.px(20f)
        })

        setContentView(root)

        Motion.enter(app.theme, root)
        buildKeyboard()
        renderQuery()
        queryView.postDelayed({
            (keys.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.requestFocus()
        }, 120)
    }

    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(app.theme.gutter, 0, app.theme.gutter, 0)
            setBackgroundColor(app.theme.surface)
        }
        val back = Views.text(app, "li", 26f, app.theme.ink, "‹", bold = true, gravity = Gravity.CENTER)
        back.setPadding(app.theme.px(16f), app.theme.px(6f), app.theme.px(16f), app.theme.px(6f))
        back.isFocusable = true
        back.isClickable = true
        back.setOnClickListener { finish() }
        Focus.attach(back, back, app, app.theme.px(10f), scale = false, elevate = false)
        bar.addView(back, LinearLayout.LayoutParams(app.theme.px(56f), app.theme.px(50f)))

        val title = Views.text(app, "li", 26f, app.theme.ink, "Search", bold = true)
        title.setPadding(app.theme.px(20f), 0, 0, 0)
        bar.addView(title)
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        status = Views.text(app, "meta", 19f, app.theme.ink2, "Type a title", maxLines = 1)
        bar.addView(status)
        return bar
    }

    private fun resultsPane(): View {
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.theme.px(36f), app.theme.px(10f), 0, 0)
        }
        resultHeading = Views.text(app, "li", 26f, wallInk, "Search the catalogue", bold = true)
        resultHeading.setPadding(0, 0, 0, app.theme.px(12f))
        pane.addView(resultHeading)
        val resultHost = FrameLayout(this)
        gridManager = GridLayoutManager(this@SearchActivity, 4)
        results = RecyclerView(this).apply {
            layoutManager = gridManager
            setHasFixedSize(true)
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            visibility = View.INVISIBLE
            // The focused tile's glass slab steps out while the grid moves and returns when it
            // settles (GlassKit.slabScrolling).
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                    GlassKit.slabScrolling(rv, app, state != RecyclerView.SCROLL_STATE_IDLE)
                }
            })
            // The pane is narrower than four fixed 230 px cards plus their halo gaps, and the cards
            // then drew over their neighbours — the overlapping rows in the audit shots. Count the
            // columns from the width actually measured, and let each card fill its own column.
            addOnLayoutChangeListener { _, _, _, w, _, _, _, _, _ ->
                if (w <= 0) return@addOnLayoutChangeListener
                val cols = (w / (app.theme.px(230f) + app.theme.px(24f))).coerceAtLeast(1)
                if (gridManager.spanCount != cols) gridManager.spanCount = cols
            }
        }
        resultHost.addView(results, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        emptyResults = Views.text(app, "sub", 22f, wallFaint,
            "Choose letters to find a movie or series", gravity = Gravity.CENTER, maxLines = 2)
        emptyResults.setPadding(app.theme.px(32f), 0, app.theme.px(32f), 0)
        resultHost.addView(emptyResults, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        pane.addView(resultHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return pane
    }

    private fun buildKeyboard() {
        val rows = listOf(
            listOf("A", "B", "C", "D", "E", "F", "G"),
            listOf("H", "I", "J", "K", "L", "M", "N"),
            listOf("O", "P", "Q", "R", "S", "T", "U"),
            listOf("V", "W", "X", "Y", "Z", "SPACE", "DEL"),
        )
        for (row in rows) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            for (label in row) {
                val key = Views.text(app, "li", 22f, wallInk, label, bold = true, gravity = Gravity.CENTER)
                key.setTextSize(TypedValue.COMPLEX_UNIT_PX, app.theme.px(if (label.length > 1) 17f else 25f).toFloat())
                key.isFocusable = true
                key.isClickable = true
                key.setOnClickListener { press(label) }
                // Large, stable light plate: on a TV a focused key must be visible from across
                // the room, without the layout shift produced by the old lift-only focus.
                fun paintKey(focused: Boolean) {
                    key.background = Views.rounded(
                        if (focused) app.theme.ink else app.theme.surface2,
                        app.theme.radius("key", 10f),
                    )
                    key.setTextColor(if (focused)
                        app.theme.colorOr("keyFocusInk", app.theme.bg) else wallInk)
                }
                paintKey(false)
                // The key carries the pack's own halo focus — the same control ring the stage
                // pills and shelf chips get — instead of a colour swap alone.
                Focus.attach(key, key, app, app.theme.radius("key", 10f), scale = false, elevate = false,
                    glowInset = app.theme.focusGlowInsetSm, control = true,
                    onFocus = { focused -> paintKey(focused) })
                line.addView(key, LinearLayout.LayoutParams(app.theme.px(88f), app.theme.px(64f)).apply {
                    if (label != row.first()) marginStart = app.theme.px(8f)
                })
            }
            keys.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, app.theme.px(72f)))
        }
    }

    private fun press(label: String) {
        when (label) {
            "DEL" -> {
                if (query.isNotEmpty()) query.deleteCharAt(query.length - 1)
            }
            "SPACE" -> if (query.length < 48) query.append(' ')
            else -> if (query.length < 48) query.append(label.lowercase())
        }
        renderQuery()
        scheduleSearch()
    }

    private fun renderQuery() {
        if (query.isEmpty()) {
            queryView.text = "Type a title…"
            queryView.setTextColor(wallFaint)
        } else {
            queryView.text = query.toString() + "▌"
            queryView.setTextColor(wallInk)
        }
    }

    private fun scheduleSearch() {
        handler.removeCallbacks(searchNow)
        val q = query.toString().trim()
        if (q.length < 2) {
            searchGen++
            results.adapter = null
            results.visibility = View.INVISIBLE
            emptyResults.visibility = View.VISIBLE
            resultHeading.text = "Search the catalogue"
            emptyResults.text = if (q.isEmpty()) "Choose letters to find a movie or series" else "One more letter to search"
            status.text = if (q.isEmpty()) "Type a title" else "One more letter…"
            return
        }
        resultHeading.text = "Results"
        emptyResults.visibility = View.VISIBLE
        emptyResults.text = "Searching…"
        results.visibility = View.INVISIBLE
        status.text = "Searching…"
        handler.postDelayed(searchNow, 320)
    }

    private fun runSearch() {
        val q = query.toString().trim()
        if (q.length < 2) return
        val gen = ++searchGen
        api.search(profileId, q) { items, error ->
            if (gen != searchGen || isFinishing || isDestroyed) return@search
            if (items == null) {
                status.text = error ?: "The hub is not answering"
                return@search
            }
            status.text = when {
                items.isEmpty() -> "Nothing found for \"$q\""
                items.size == 1 -> "1 title"
                else -> "${items.size} titles"
            }
            results.adapter = ResultAdapter(items)
            results.visibility = View.VISIBLE
            emptyResults.visibility = View.GONE
        }
    }

    private fun open(item: Item) {
        startActivity(
            Intent(this, DetailActivity::class.java)
                .putExtra(DetailActivity.EXTRA_PROFILE, profileId)
                .putExtra(DetailActivity.EXTRA_IDENTITY, item.identity)
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (CardActionSheet.isOpen()) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                CardActionSheet.close()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            if (query.isNotEmpty()) {
                query.setLength(0)
                renderQuery()
                scheduleSearch()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private inner class ResultAdapter(private val items: List<Item>) : RecyclerView.Adapter<ResultHolder>() {
        private val theme = app.theme
        private val cardW = theme.px(230f)
        private val cardH = theme.px(345f)

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultHolder {
            val card = FrameLayout(this@SearchActivity).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardH).apply {
                    // Reserve room for the focused lockup's halo instead of cutting it off at
                    // the next result cell.
                    marginEnd = theme.px(24f)
                    bottomMargin = theme.px(36f)
                }
                isFocusable = true
                isClickable = true
                clipChildren = false
            }
            val glowPad = theme.focusGlowInset
            val ringOn = View(this@SearchActivity)
            card.addView(ringOn, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                leftMargin = -glowPad; topMargin = -glowPad; rightMargin = -glowPad; bottomMargin = -glowPad
            })
            val art = ImageView(this@SearchActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(theme.surface2)
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, v.width, v.height, theme.radius("card", 6f).toFloat())
                    }
                }
            }
            card.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            card.addView(View(this@SearchActivity).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(Color.argb(230, 0, 0, 0), Color.TRANSPARENT),
                )
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(100f)).apply { gravity = Gravity.BOTTOM })
            val name = Views.text(app, "caption", 18f, theme.ink, "", bold = true, maxLines = 2)
            name.setPadding(theme.px(10f), 0, theme.px(10f), theme.px(8f))
            card.addView(name, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            })
            val logo = ImageView(this@SearchActivity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
            }
            card.addView(logo, FrameLayout.LayoutParams(cardW - theme.px(24f), theme.px(64f)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = theme.px(8f)
            })
            val badge = Views.text(app, "meta", 12f, Color.WHITE, "", bold = true, maxLines = 1)
            badge.setPadding(theme.px(7f), theme.px(3f), theme.px(7f), theme.px(3f))
            badge.background = Views.rounded(Color.argb(185, 8, 8, 8), theme.px(5f))
            card.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = theme.px(8f); topMargin = theme.px(8f)
            })
            Focus.attach(card, ringOn, app, theme.radius("card", 6f), animateScale = false,
                elevate = false, glowInset = glowPad)
            GlassKit.onGlassFocus(card, art, app, theme.radius("card", 6f))
            return ResultHolder(card, art, name, logo, badge)
        }

        override fun onBindViewHolder(holder: ResultHolder, position: Int) {
            val item = items[position]
            holder.itemView.contentDescription = CardActions.announcement(app, profileId, item, buildString {
                append(item.title)
                if (item.year > 0) append(", ").append(item.year)
                if (item.match > 0) append(", ").append(item.match).append("% match")
                append(", open details")
            })
            holder.name.text = item.title
            holder.name.visibility = View.VISIBLE
            holder.logo.visibility = View.GONE
            val facts = ArrayList<String>()
            // Search results are deliberately terse in scene packs, while cinema/console packs
            // put a compact status badge over the art. Keep that policy in the pack so a new
            // theme does not need another id check here.
            if (theme.packString("searchBadges", "show") == "show") {
                if (item.isNew) facts.add("NEW")
                if (item.match > 0) facts.add("${item.match}%")
            }
            holder.badge.text = facts.joinToString(" · ")
            holder.badge.visibility = if (facts.isEmpty()) View.GONE else View.VISIBLE
            BrowseImages.load(holder.art, item.poster ?: item.backdrop, cardW)
            if (!item.logo.isNullOrBlank()) {
                holder.logo.visibility = View.VISIBLE
                BrowseImages.load(holder.logo, item.logo, cardW) { ok ->
                    holder.logo.visibility = if (ok) View.VISIBLE else View.GONE
                    holder.name.visibility = if (ok) View.GONE else View.VISIBLE
                }
            }
            holder.itemView.setOnClickListener { open(item) }
            // The same hold the home shelves answer: it opens the choice sheet, and the viewer's OK
            // on a row is what joins or leaves the profile's list.
            holder.itemView.isLongClickable = !item.isLive()
            holder.itemView.setOnLongClickListener {
                CardActions.holdSheet(this@SearchActivity, app, api, profileId, item,
                    canDismissContinue = false)
                true
            }
        }
    }

    override fun onBackPressed() {
        // The hold sheet owns the page while it is up; Back closes it rather than leaving the search.
        if (CardActionSheet.isOpen()) {
            CardActionSheet.close()
            return
        }
        super.onBackPressed()
    }

    private inner class ResultHolder(
        view: View, val art: ImageView, val name: TextView,
        val logo: ImageView, val badge: TextView,
    ) : RecyclerView.ViewHolder(view)

    companion object {
        const val EXTRA_PROFILE = "profile"
    }
}
