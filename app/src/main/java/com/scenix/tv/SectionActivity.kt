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
 * Detail page exactly as a Home card does.
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(app.theme.bg)
        }
        root.addView(topBar(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(88f)
        ))
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@SectionActivity, 6)
            setHasFixedSize(true)
            setItemViewCacheSize(24)
            itemAnimator = null
            clipToPadding = false
            setPadding(app.theme.gutter, app.theme.px(16f), app.theme.gutter, app.theme.px(56f))
            setBackgroundColor(app.theme.bg)
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        status = Views.text(app, "sub", 23f, app.theme.ink2, "Reading the library…", gravity = Gravity.CENTER)
        root.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(64f)
        ))
        setContentView(root)
        load()
    }

    private fun topBar(): View {
        val theme = app.theme
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.gutter, 0, theme.gutter, 0)
            setBackgroundColor(theme.bg)
        }
        val back = Views.text(app, "li", 28f, theme.ink, "‹", bold = true, gravity = Gravity.CENTER)
        back.setPadding(theme.px(16f), theme.px(6f), theme.px(16f), theme.px(6f))
        back.isFocusable = true
        back.isClickable = true
        back.setOnClickListener { finish() }
        Focus.attach(back, back, app, theme.radius("button", 6f), scale = false, elevate = false)
        bar.addView(back, LinearLayout.LayoutParams(theme.px(56f), theme.px(50f)))
        title = Views.text(app, "rowTitle", 28f, theme.ink, if (section == "series") "Series" else "Movies", bold = true)
        title.setPadding(theme.px(20f), 0, 0, 0)
        bar.addView(title)
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        return bar
    }

    private fun load() {
        status.visibility = View.VISIBLE
        status.text = "Reading the library…"
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

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
            val card = FrameLayout(this@SectionActivity)
            card.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, theme.px(420f)
            ).apply {
                marginStart = theme.px(6f)
                marginEnd = theme.px(6f)
                bottomMargin = theme.px(18f)
            }
            card.isFocusable = true
            card.isClickable = true
            val art = ImageView(this@SectionActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(theme.surface2)
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, v.width, v.height, theme.radius("card", 6f).toFloat())
                    }
                }
            }
            card.addView(art, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            card.addView(View(this@SectionActivity).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(Color.argb(235, 0, 0, 0), Color.TRANSPARENT)
                )
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(130f)).apply {
                gravity = Gravity.BOTTOM
            })
            val name = Views.text(app, "caption", 19f, theme.ink, "", bold = true, maxLines = 2)
            name.setPadding(theme.px(12f), 0, theme.px(12f), theme.px(10f))
            card.addView(name, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM })
            val logo = ImageView(this@SectionActivity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
            }
            card.addView(logo, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, theme.px(68f)
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                marginStart = theme.px(14f); marginEnd = theme.px(14f); bottomMargin = theme.px(8f)
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
            Focus.attach(card, card, app, theme.radius("card", 6f), animateScale = false, elevate = false)
            return CardHolder(card, art, name, logo, badge)
        }

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            val item = items[position]
            holder.name.text = item.title
            holder.name.visibility = View.VISIBLE
            holder.logo.visibility = View.GONE
            val bits = ArrayList<String>()
            if (item.isNew) bits.add("NEW")
            if (item.match > 0) bits.add("${item.match}% match")
            holder.badge.text = bits.joinToString(" · ")
            holder.badge.visibility = if (bits.isEmpty()) View.GONE else View.VISIBLE
            BrowseImages.load(holder.art, item.poster ?: item.backdrop, theme.px(400f))
            if (!item.logo.isNullOrBlank()) {
                holder.logo.visibility = View.VISIBLE
                BrowseImages.load(holder.logo, item.logo, theme.px(280f)) { ok ->
                    holder.logo.visibility = if (ok) View.VISIBLE else View.GONE
                    holder.name.visibility = if (ok) View.GONE else View.VISIBLE
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
        view: View, val art: ImageView, val name: TextView,
        val logo: ImageView, val badge: TextView,
    ) : RecyclerView.ViewHolder(view)

    companion object {
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_SECTION = "section"
    }
}
