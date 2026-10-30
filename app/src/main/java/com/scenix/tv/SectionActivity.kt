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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Item
import com.scenix.tv.core.Views

/**
 * The native Movies and Series sections: one row from `/app/v1/row/<id>` drawn as a grid.
 *
 * The library and the decision about what belongs in each section stay on the backend. This screen
 * exists so the television never has to open a WebView for a catalogue page; a tile opens the native
 * Detail page exactly as a Home card does. The tiles wear the same clothes as the Home shelves —
 * art first, the name and its quiet sub-line underneath, and a ring of light when focused.
 */
class SectionActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var profileId: String
    private lateinit var section: String
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var grid: RecyclerView
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        profileId = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
        section = intent?.getStringExtra(EXTRA_SECTION).orEmpty().ifBlank { "movies" }
        if (profileId.isBlank()) {
            startActivity(Intent(this, ProfilesActivity::class.java))
            finish()
            return
        }

        val theme = app.theme
        val root = FrameLayout(this).apply { setBackgroundColor(theme.bg) }
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@SectionActivity, 6)
            setHasFixedSize(true)
            setItemViewCacheSize(24)
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            setPadding(theme.gutter, theme.px(112f), theme.gutter, theme.px(56f))
            setBackgroundColor(Color.TRANSPARENT)
        }
        root.addView(grid, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(topBar(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(96f)
        ))
        status = Views.text(app, "sub", 23f, theme.ink2, "Reading the library…", gravity = Gravity.CENTER)
        root.addView(status, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(64f)
        ).apply { gravity = Gravity.BOTTOM })
        setContentView(root)
        load()
    }

    /** A floating bar over the grid: a feather chevron home, and the section's name beside it. */
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
        title = Views.text(app, "rowTitle", 26f, theme.ink,
            if (section == "series") "Series" else "Movies", bold = true)
        title.setPadding(theme.px(26f), 0, 0, 0)
        bar.addView(title)
        return bar
    }

    private fun load() {
        status.visibility = View.VISIBLE
        status.text = "Reading the library…"
        // A derived shelf (a genre, a language) has no hub row of its own; its full list is the
        // search the hub already answers for that word.
        val query = intent?.getStringExtra(EXTRA_QUERY).orEmpty()
        if (query.isNotBlank()) {
            title.text = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { query }
            api.search(profileId, query) { items, error ->
                if (isFinishing || isDestroyed) return@search
                if (items.isNullOrEmpty()) {
                    status.text = error ?: "Nothing under this name yet"
                    return@search
                }
                status.visibility = View.GONE
                grid.adapter = CardAdapter(items)
                grid.postDelayed({
                    (grid.findViewHolderForAdapterPosition(0)?.itemView ?: grid.getChildAt(0))?.requestFocus()
                }, 120)
            }
            return
        }
        api.row(profileId, section) { name, items, error ->
            if (isFinishing || isDestroyed) return@row
            if (items == null) {
                status.text = error ?: "The hub is not answering"
                return@row
            }
            title.text = name
            if (items.isEmpty()) {
                status.text = "Nothing in this section yet"
                return@row
            }
            status.visibility = View.GONE
            grid.adapter = CardAdapter(items)
            grid.postDelayed({
                (grid.findViewHolderForAdapterPosition(0)?.itemView ?: grid.getChildAt(0))?.requestFocus()
            }, 120)
        }
    }

    private inner class CardAdapter(private val items: List<Item>) : RecyclerView.Adapter<CardHolder>() {
        private val theme = app.theme
        private val artH = theme.px(400f)
        private val captionH = theme.px(74f)
        private val radius = theme.radius("card", 6f)

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
            val card = FrameLayout(this@SectionActivity)
            card.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, artH + captionH
            ).apply {
                // Apple's shelves breathe; tiles never touch each other.
                leftMargin = theme.px(13f); rightMargin = theme.px(13f)
                bottomMargin = theme.px(30f)
            }
            card.isFocusable = true
            card.isClickable = true
            val art = ImageView(this@SectionActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(theme.surface2)
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, v.width, v.height, radius.toFloat())
                    }
                }
            }
            card.addView(art, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, artH
            ).apply { gravity = Gravity.TOP })
            // The name and its quiet sub-line sit UNDER the art, the way the Home shelves draw them.
            val name = Views.text(app, "caption", 18f, theme.ink, "", bold = true, maxLines = 1)
            name.setPadding(theme.px(4f), theme.px(12f), theme.px(4f), 0)
            card.addView(name, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, captionH
            ).apply { gravity = Gravity.BOTTOM })
            val sub = Views.text(app, "meta", 14f, theme.ink3, "", maxLines = 1)
            sub.setPadding(theme.px(4f), 0, theme.px(4f), theme.px(4f))
            card.addView(sub, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM; bottomMargin = theme.px(4f) })
            val logo = ImageView(this@SectionActivity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
            }
            card.addView(logo, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, theme.px(64f)
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = theme.px(150f)
                marginStart = theme.px(14f); marginEnd = theme.px(14f)
            })
            val badge = Views.text(app, "meta", 13f, Color.WHITE, "", bold = true, maxLines = 1)
            badge.setPadding(theme.px(8f), theme.px(3f), theme.px(8f), theme.px(3f))
            badge.background = Views.rounded(Color.argb(185, 8, 8, 8), theme.px(5f))
            card.addView(badge, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = theme.px(10f); topMargin = theme.px(10f)
            })
            // The focus ring belongs to the ART, not to the caption under it: the ring view is an
            // oversized transparent pad around the image, and the glow bands spill into that pad.
            val glowPad = theme.px(26f)
            val ringOn = View(this@SectionActivity)
            card.addView(ringOn, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, artH + glowPad * 2
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = -glowPad
            })
            card.clipChildren = false
            Focus.attach(card, ringOn, app, radius, animateScale = false, elevate = false,
                glowInset = glowPad)
            return CardHolder(card, art, name, sub, logo, badge)
        }

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            val item = items[position]
            holder.name.text = item.title
            holder.name.visibility = View.VISIBLE
            val facts = ArrayList<String>()
            if (item.year > 0) facts.add(item.year.toString())
            if (item.match > 0) facts.add("${item.match}% match")
            item.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let { facts.add(it) }
            holder.sub.text = facts.joinToString(" · ")
            holder.logo.visibility = View.GONE
            val bits = ArrayList<String>()
            if (item.isNew) bits.add("NEW")
            holder.badge.text = bits.joinToString(" · ")
            holder.badge.visibility = if (bits.isEmpty()) View.GONE else View.VISIBLE
            BrowseImages.load(holder.art, item.poster ?: item.backdrop, theme.px(400f))
            if (!item.logo.isNullOrBlank()) {
                holder.logo.visibility = View.VISIBLE
                BrowseImages.load(holder.logo, item.logo, theme.px(240f)) { ok ->
                    holder.logo.visibility = if (ok) View.VISIBLE else View.GONE
                }
            }
            holder.itemView.setOnClickListener {
                startActivity(
                    Intent(this@SectionActivity, DetailActivity::class.java)
                        .putExtra(DetailActivity.EXTRA_PROFILE, profileId)
                        .putExtra(DetailActivity.EXTRA_IDENTITY, item.identity)
                )
            }
        }
    }

    private inner class CardHolder(
        view: View, val art: ImageView, val name: TextView, val sub: TextView,
        val logo: ImageView, val badge: TextView,
    ) : RecyclerView.ViewHolder(view)

    companion object {
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_SECTION = "section"
        const val EXTRA_QUERY = "query"
        const val EXTRA_TITLE = "title"
    }
}
