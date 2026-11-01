package com.scenix.tv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Item
import com.scenix.tv.core.Views

/**
 * The light glass Home composition for packs which opt into `homeHero: dual-cards`.
 *
 * The reference mockup's grammar, read block by block: one big rounded frosted panel floating on a
 * blurred wallpaper; inside it a quiet header (brand at left, a DARK centred pill nav with the
 * search target built in, household avatar at right); two feature cards side by side — text over a
 * tinted card plus key art bleeding from the other edge, each with a circular play control; a row
 * of glass chips for the household's real shelves with only one chip darkened; and below that,
 * captioned poster tiles. Nothing is opaque black, nothing is a full-bleed billboard, and motion
 * stays gentle because the whole pack reads as calm.
 */
internal class CleanHomeLayout(
    context: Context,
    private val app: AppContext,
    private val callbacks: Callbacks,
) : FrameLayout(context) {

    data class Callbacks(
        val onOpen: (Item) -> Unit,
        val onSection: (String) -> Unit,
        val onProfile: () -> Unit,
        val onSearch: () -> Unit,
        /** A chip jumps to the real shelf it names rather than inventing a filtered route. */
        val onShelf: (String) -> Unit,
    )

    private val theme = app.theme
    private var items: List<Item> = emptyList()
    private var activeIndex = 0

    val root: View get() = this
    val primaryControl: View get() = cardA
    val navigationAnchor: View? get() = pillStops.firstOrNull()
    val preferredHeightPx: Int get() = theme.px(theme.packInt("cleanHomeHeight", 880).toFloat())

    private val cardA: FrameLayout
    private val cardB: FrameLayout
    private val artA: ImageView
    private val artB: ImageView
    private val titleA: TextView
    private val titleB: TextView
    private var itemA: Item? = null
    private var itemB: Item? = null
    private val chipRow: LinearLayout
    private val pillStops = ArrayList<View>()
    private var chipIds: List<String> = emptyList()

    init {
        // The page behind the panel: a soft blurred-wallpaper wash, never flat black.
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(theme.color("wallA", "#7E94A8"), theme.color("wallB", "#5C7288")),
        )

        addView(View(context).apply {
            background = Views.rounded(
                theme.color("glassPanel", "#DCE6F0"), theme.radius("panel", 28f),
                theme.px(1f), theme.color("glassLine", "#FFFFFF"),
            )
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            setMargins(theme.px(48f), theme.px(36f), theme.px(48f), theme.px(28f))
        })

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        addView(content, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply {
            setMargins(theme.px(48f), theme.px(36f), theme.px(48f), theme.px(28f))
        })

        // --- header: brand left, dark centred pill, household right ---------------------------
        val header = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
        header.addView(Views.text(app, "brand", 26f, theme.ink, "SCENIX", bold = true), LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })

        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Views.rounded(theme.color("pill", "#1E252E"), theme.px(999f))
            setPadding(theme.px(6f), theme.px(6f), theme.px(6f), theme.px(6f))
        }
        val sections = listOf(
            "Movies" to "movies",
            "Series" to "series",
            "Live" to "live",
        )
        sections.forEachIndexed { index, (label, route) ->
            val stop = makePillStop(label, route, index == 0)
            pillStops += stop
            pill.addView(stop)
        }
        pill.addView(makeSearchDot(), LinearLayout.LayoutParams(theme.px(44f), theme.px(44f)).apply {
            marginStart = theme.px(6f)
        })
        header.addView(pill, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, theme.px(56f),
        ).apply { gravity = Gravity.CENTER })

        val household = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val avatar = Views.text(app, "meta", 17f, Color.WHITE, "S", bold = true,
            gravity = Gravity.CENTER).apply {
            background = Views.rounded(theme.brand, theme.px(999f))
        }
        val avatarStop = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "Profiles"
            setPadding(theme.px(8f), theme.px(6f), theme.px(8f), theme.px(6f))
        }
        avatarStop.addView(avatar, LayoutParams(theme.px(44f), theme.px(44f)))
        Focus.attach(avatarStop, avatarStop, app, theme.px(999f), scale = false, elevate = false,
            onClick = callbacks.onProfile)
        pillStops += avatarStop
        household.addView(avatarStop)
        header.addView(household, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL })
        content.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(64f),
        ))

        // --- the two feature cards ------------------------------------------------------------
        val cardRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val a = makeCard(tinted = true)
        cardA = a.first; artA = a.second.first; titleA = a.second.second
        val b = makeCard(tinted = false)
        cardB = b.first; artB = b.second.first; titleB = b.second.second
        cardRow.addView(cardA, LinearLayout.LayoutParams(0, theme.px(300f), 1f))
        cardRow.addView(cardB, LinearLayout.LayoutParams(0, theme.px(300f), 1f).apply {
            marginStart = theme.px(24f)
        })
        content.addView(cardRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(300f),
        ).apply { topMargin = theme.px(28f) })
        // The light pack speaks focus as a crisp keyline plus a soft lift — never a glow on glass.
        Focus.attach(cardA, cardA, app, theme.radius("card", 22f), scale = false, elevate = true,
            onClick = { itemA?.let(callbacks.onOpen) })
        Focus.attach(cardB, cardB, app, theme.radius("card", 22f), scale = false, elevate = true,
            onClick = { itemB?.let(callbacks.onOpen) })

        // --- glass chip row: the household's real shelves, named exactly as they are -----------
        chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        content.addView(chipRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(64f),
        ).apply { topMargin = theme.px(26f) })

        cardA.nextFocusUpId = pillStops.first().id
        cardB.nextFocusUpId = pillStops.first().id
    }

    private fun makePillStop(label: String, route: String, active: Boolean): View {
        val stop = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "$label section"
            setPadding(theme.px(18f), theme.px(10f), theme.px(18f), theme.px(10f))
            background = if (active) Views.rounded(
                theme.color("pillActive", "#3A4552"), theme.px(999f)) else null
        }
        stop.addView(Views.text(app, "nav", 17f, if (active) Color.WHITE else theme.color("pillInk", "#9FB0C0"),
            label, bold = true, gravity = Gravity.CENTER), LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER })
        Focus.attach(stop, stop, app, theme.px(999f), scale = false, elevate = false,
            onClick = { callbacks.onSection(route) })
        return stop
    }

    private fun makeSearchDot(): View {
        val dot = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "Search"
            background = Views.rounded(theme.color("pillActive", "#3A4552"), theme.px(999f))
        }
        dot.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_nav_search)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(theme.px(11f), theme.px(11f), theme.px(11f), theme.px(11f))
        }, LayoutParams(theme.px(44f), theme.px(44f)))
        Focus.attach(dot, dot, app, theme.px(999f), scale = false, elevate = false,
            onClick = callbacks.onSearch)
        pillStops += dot
        return dot
    }

    /** A feature card: tinted or glass plate, key art bleeding from the end edge, text + play. */
    private fun makeCard(tinted: Boolean): Pair<FrameLayout, Pair<ImageView, TextView>> {
        val card = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            clipChildren = false
            clipToPadding = false
            contentDescription = "Featured title. Open details"
            background = if (tinted) GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(theme.color("cardTintA", "#8FB4DC"), theme.color("cardTintB", "#6E97C8")),
            ) else Views.rounded(theme.color("glassCard", "#FFFFFF"), theme.radius("card", 22f))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, theme.radius("card", 22f).toFloat())
                }
            }
            clipToOutline = true
        }
        val art = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
        }
        // The art covers a fixed fraction; do it with weights instead of guessing at width.
        val artBox = FrameLayout(context)
        artBox.addView(art, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.px(34f), theme.px(20f), theme.px(12f), theme.px(20f))
        }
        val title = Views.text(app, "h2", 38f, theme.color("inkOnCard", "#17202B"), "", bold = true,
            maxLines = 2)
        textCol.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val playRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playRow.addView(Views.text(app, "meta", 19f, Color.WHITE, "\u25B6", bold = true,
            gravity = Gravity.CENTER).apply {
            background = Views.rounded(theme.color("pill", "#1E252E"), theme.px(999f))
        }, LinearLayout.LayoutParams(theme.px(46f), theme.px(46f)))
        playRow.addView(Views.text(app, "sub", 17f, theme.color("inkOnCard", "#17202B"), "Play",
            bold = true), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = theme.px(12f) })
        textCol.addView(playRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = theme.px(20f) })
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 54f))
            addView(artBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 46f))
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return Pair(card, Pair(art, title))
    }

    private fun makeChip(shelfId: String, label: String, active: Boolean): View {
        val chip = LinearLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "Jump to $label"
            setPadding(theme.px(20f), theme.px(13f), theme.px(20f), theme.px(13f))
            background = Views.rounded(
                if (active) theme.color("pill", "#1E252E") else theme.color("glassChip", "#FFFFFF"),
                theme.radius("chip", 999f))
        }
        val glyph = when {
            label.contains("Trending", true) -> "\u2197"
            label.contains("New", true) -> "\u2726"
            label.contains("Top", true) || label.contains("rated", true) -> "\u2605"
            label.contains("Language", true) || label.contains("Top Hindi", true) -> "\u2691"
            else -> "\u25CF"
        }
        chip.addView(Views.text(app, "meta", 15f, if (active) Color.WHITE else theme.ink, glyph,
            bold = true), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        chip.addView(Views.text(app, "chip", 16f, if (active) Color.WHITE else theme.ink, label,
            bold = true), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = theme.px(9f) })
        Focus.attach(chip, chip, app, theme.radius("chip", 999f), scale = false, elevate = false,
            onClick = { callbacks.onShelf(shelfId) })
        return chip
    }

    /** The shelf titles the host actually rendered below; each chip jumps to its shelf. */
    fun setChips(shelves: List<Pair<String, String>>) {
        val ids = shelves.map { it.first }
        if (ids == chipIds) return
        chipIds = ids
        chipRow.removeAllViews()
        shelves.take(6).forEachIndexed { index, (id, label) ->
            val chip = makeChip(id, label.trim().removePrefix("New on "), index == 0)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, theme.px(52f))
            if (chipRow.childCount > 0) lp.marginStart = theme.px(14f)
            chipRow.addView(chip, lp)
        }
        cardA.nextFocusDownId = chipRow.getChildAt(0)?.id ?: View.NO_ID
        cardB.nextFocusDownId = chipRow.getChildAt(0)?.id ?: View.NO_ID
    }

    fun render(slides: List<Item>, activeIndex: Int) {
        items = slides
        this.activeIndex = activeIndex.coerceIn(0, (slides.size - 1).coerceAtLeast(0))
        val a = slides.getOrNull(this.activeIndex)
        // The reference shows two DIFFERENT titles side by side; with one slide the second card
        // would duplicate it, so it simply is not there.
        val b = if (slides.size >= 2) slides[(this.activeIndex + 1) % slides.size] else null
        itemA = a
        itemB = b
        bindCard(cardA, artA, titleA, a)
        bindCard(cardB, artB, titleB, b)
        cardB.visibility = if (b == null) View.GONE else View.VISIBLE
    }

    private fun bindCard(card: FrameLayout, art: ImageView, title: TextView, item: Item?) {
        card.contentDescription = item?.let { "${it.title}. Open details" } ?: "Featured title"
        title.text = item?.title ?: ""
        BrowseImages.load(art, item?.backdrop ?: item?.poster, theme.px(560f))
    }

    fun focusEntry(activeIndex: Int): Boolean {
        render(items, activeIndex)
        return cardA.requestFocus()
    }

    fun setNextFocusUp(id: Int) {
        if (pillStops.isEmpty()) return
        cardA.nextFocusUpId = pillStops.first().id
        cardB.nextFocusUpId = pillStops.first().id
    }
}
