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
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Item
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
    private lateinit var keys: LinearLayout
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }
    private val handler = Handler(Looper.getMainLooper())
    private val query = StringBuilder()
    private var searchGen = 0

    private val searchNow = Runnable { runSearch() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        profileId = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
        if (profileId.isBlank()) { finish(); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(app.theme.bg)
        }
        root.addView(topBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(88f)))

        queryView = Views.text(app, "li", 22f, app.theme.ink, "", bold = true, maxLines = 1)
        queryView.setTextSize(TypedValue.COMPLEX_UNIT_PX, app.theme.px(44f).toFloat())
        queryView.setPadding(app.theme.gutter, app.theme.px(14f), app.theme.gutter, app.theme.px(18f))
        root.addView(queryView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(84f)))

        val content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keys = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.theme.px(40f), app.theme.px(8f), app.theme.px(10f), app.theme.px(24f))
        }
        content.addView(keys, LinearLayout.LayoutParams(app.theme.px(700f), ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(resultsPane(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
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
            setBackgroundColor(Color.rgb(20, 20, 20))
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
            setPadding(0, app.theme.px(4f), app.theme.px(24f), 0)
        }
        val head = Views.text(app, "li", 24f, app.theme.ink, "Results", bold = true)
        head.setPadding(0, 0, 0, app.theme.px(10f))
        pane.addView(head)
        results = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@SearchActivity, 4)
            setHasFixedSize(true)
            itemAnimator = null
            setBackgroundColor(app.theme.bg)
            clipToPadding = false
        }
        pane.addView(results, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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
                val key = Views.text(app, "li", 22f, app.theme.ink, label, bold = true, gravity = Gravity.CENTER)
                key.setTextSize(TypedValue.COMPLEX_UNIT_PX, app.theme.px(if (label.length > 1) 17f else 24f).toFloat())
                key.background = Views.rounded(app.theme.surface2, app.theme.px(9f))
                key.isFocusable = true
                key.isClickable = true
                key.setOnClickListener { press(label) }
                Focus.attach(key, key, app, app.theme.px(9f), scale = false, elevate = false)
                line.addView(key, LinearLayout.LayoutParams(app.theme.px(84f), app.theme.px(60f)).apply {
                    if (label != row.first()) marginStart = app.theme.px(8f)
                })
            }
            keys.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, app.theme.px(68f)))
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
            queryView.setTextColor(app.theme.ink3)
        } else {
            queryView.text = query.toString() + "▌"
            queryView.setTextColor(app.theme.ink)
        }
    }

    private fun scheduleSearch() {
        handler.removeCallbacks(searchNow)
        val q = query.toString().trim()
        if (q.length < 2) {
            searchGen++
            results.adapter = null
            status.text = if (q.isEmpty()) "Type a title" else "One more letter…"
            return
        }
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
                layoutParams = RecyclerView.LayoutParams(cardW, cardH).apply {
                    marginEnd = theme.px(14f)
                    bottomMargin = theme.px(18f)
                }
                isFocusable = true
                isClickable = true
            }
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
            Focus.attach(card, card, app, theme.radius("card", 6f), animateScale = false, elevate = false)
            return ResultHolder(card, art, name)
        }

        override fun onBindViewHolder(holder: ResultHolder, position: Int) {
            val item = items[position]
            holder.name.text = item.title
            BrowseImages.load(holder.art, item.poster ?: item.backdrop, cardW)
            holder.itemView.setOnClickListener { open(item) }
        }
    }

    private inner class ResultHolder(view: View, val art: ImageView, val name: TextView) : RecyclerView.ViewHolder(view)

    companion object {
        const val EXTRA_PROFILE = "profile"
    }
}
