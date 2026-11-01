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
 * The bright stage composition for kids packs (`homeHero: kids-stage`).
 *
 * The mockup's grammar, block by block: flat sunshine yellow behind everything; a slightly paler
 * rounded panel holding the whole home; a black circular brand mark at left; a BLACK pill nav in
 * the centre with the active tab lit in the page's own yellow and a yellow search dot welded to
 * the pill's end; a household avatar and bell at right; two oversized feature cards (one full-art
 * with the title printed over it, one tinted with key art bleeding from the edge) each carrying a
 * chunky play circle; a row of black chip pills; and below them big square character tiles with
 * black uppercase captions. Focus is a thick black keyline — the same ink as everything else —
 * never a glow.
 */
internal class KidsHomeLayout(
    context: Context,
    private val app: AppContext,
    private val callbacks: Callbacks,
) : FrameLayout(context) {

    data class Callbacks(
        val onOpen: (Item) -> Unit,
        val onSection: (String) -> Unit,
        val onProfile: () -> Unit,
        val onSearch: () -> Unit,
        val onShelf: (String) -> Unit,
    )

    private val theme = app.theme
    private var items: List<Item> = emptyList()
    private var activeIndex = 0

    val root: View get() = this
    val primaryControl: View get() = cardA
    val navigationAnchor: View? get() = pillStops.firstOrNull()
    val preferredHeightPx: Int get() = theme.px(theme.packInt("kidsHomeHeight", 620).toFloat())

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
        setBackgroundColor(theme.color("stageYellow", "#FFC42E"))

        addView(View(context).apply {
            background = Views.rounded(theme.color("panelTint", "#FFEFC2"), theme.radius("panel", 32f))
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            setMargins(theme.px(48f), theme.px(32f), theme.px(48f), theme.px(24f))
        })

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        addView(content, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply {
            setMargins(theme.px(48f), theme.px(32f), theme.px(48f), theme.px(24f))
        })

        // --- header: black brand circle, black centred pill, household cluster -----------------
        val header = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
        val mark = Views.text(app, "h2", 26f, Color.WHITE, "S", bold = true, gravity = Gravity.CENTER).apply {
            background = Views.rounded(theme.ink, theme.px(999f))
        }
        header.addView(mark, LayoutParams(theme.px(56f), theme.px(56f)).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        })

        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Views.rounded(theme.ink, theme.px(999f))
            setPadding(theme.px(8f), theme.px(7f), theme.px(7f), theme.px(7f))
        }
        val sections = listOf("Movies" to "movies", "Series" to "series", "Live" to "live")
        sections.forEachIndexed { index, (label, route) ->
            val stop = makePillStop(label, route, index == 0)
            pill.addView(stop)
        }
        pill.addView(makeSearchDot(), LinearLayout.LayoutParams(theme.px(46f), theme.px(46f)).apply {
            marginStart = theme.px(8f)
        })
        header.addView(pill, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, theme.px(60f),
        ).apply { gravity = Gravity.CENTER })

        val household = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val avatarStop = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "Profiles"
            setPadding(theme.px(8f), theme.px(4f), theme.px(8f), theme.px(4f))
        }
        avatarStop.addView(Views.text(app, "meta", 17f, Color.WHITE, "K", bold = true,
            gravity = Gravity.CENTER).apply {
            background = Views.rounded(theme.ink, theme.px(999f))
        }, LayoutParams(theme.px(46f), theme.px(46f)))
        Focus.attach(avatarStop, avatarStop, app, theme.px(999f), scale = false, elevate = false,
            onClick = callbacks.onProfile)
        pillStops += avatarStop
        household.addView(avatarStop)
        header.addView(household, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL })
        content.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(66f)))

        // --- the two oversized feature cards ---------------------------------------------------
        val cardRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val a = makeCard(fullArt = true)
        cardA = a.first; artA = a.second.first; titleA = a.second.second
        val b = makeCard(fullArt = false)
        cardB = b.first; artB = b.second.first; titleB = b.second.second
        cardRow.addView(cardA, LinearLayout.LayoutParams(0, theme.px(280f), 42f))
        cardRow.addView(cardB, LinearLayout.LayoutParams(0, theme.px(280f), 58f).apply {
            marginStart = theme.px(24f)
        })
        content.addView(cardRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(280f),
        ).apply { topMargin = theme.px(26f) })
        Focus.attach(cardA, cardA, app, theme.radius("card", 26f), scale = false, elevate = true,
            onClick = { itemA?.let(callbacks.onOpen) })
        Focus.attach(cardB, cardB, app, theme.radius("card", 26f), scale = false, elevate = true,
            onClick = { itemB?.let(callbacks.onOpen) })

        // --- black chip pills: the household's real shelves -------------------------------------
        chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        content.addView(chipRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(60f),
        ).apply { topMargin = theme.px(24f) })

        cardA.nextFocusUpId = pillStops.firstOrNull()?.id ?: View.NO_ID
        cardB.nextFocusUpId = pillStops.firstOrNull()?.id ?: View.NO_ID
    }

    private fun makePillStop(label: String, route: String, active: Boolean): View {
        val stop = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "$label section"
            setPadding(theme.px(20f), theme.px(11f), theme.px(20f), theme.px(11f))
            background = if (active) Views.rounded(
                theme.color("pillActive", "#26221B"), theme.px(999f)) else null
        }
        stop.addView(Views.text(app, "nav", 17f,
            if (active) theme.color("stageYellow", "#FFC42E") else Color.WHITE,
            label, bold = true, gravity = Gravity.CENTER), LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER })
        Focus.attach(stop, stop, app, theme.px(999f), scale = false, elevate = false,
            onClick = { callbacks.onSection(route) })
        pillStops += stop
        return stop
    }

    private fun makeSearchDot(): View {
        val dot = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "Search"
            background = Views.rounded(theme.color("accentYellow", "#FFD34E"), theme.px(999f))
        }
        dot.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_nav_search)
            setColorFilter(theme.ink)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(theme.px(12f), theme.px(12f), theme.px(12f), theme.px(12f))
        }, LayoutParams(theme.px(46f), theme.px(46f)))
        Focus.attach(dot, dot, app, theme.px(999f), scale = false, elevate = false,
            onClick = callbacks.onSearch)
        pillStops += dot
        return dot
    }

    /** fullArt = the backdrop covers the whole card and the title is printed over it (white);
     *  tinted = yellow plate with key art bleeding from the end edge and the title in black ink. */
    private fun makeCard(fullArt: Boolean): Pair<FrameLayout, Pair<ImageView, TextView>> {
        val card = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            clipChildren = false
            clipToPadding = false
            contentDescription = "Featured title. Open details"
            background = if (fullArt) Views.rounded(theme.ink, theme.radius("card", 26f))
            else Views.rounded(theme.color("cardTint", "#FFD866"), theme.radius("card", 26f))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, theme.radius("card", 26f).toFloat())
                }
            }
            clipToOutline = true
        }
        val art = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(theme.px(30f), theme.px(20f), theme.px(12f), theme.px(24f))
        }
        val inkForCard = if (fullArt) Color.WHITE else theme.ink
        val title = Views.text(app, "h2", 36f, inkForCard, "", bold = true, maxLines = 2)
        textCol.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val playRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playRow.addView(Views.text(app, "meta", 18f,
            if (fullArt) theme.ink else Color.WHITE, "▶", bold = true, gravity = Gravity.CENTER).apply {
            background = Views.rounded(if (fullArt) Color.WHITE else theme.ink, theme.px(999f))
        }, LinearLayout.LayoutParams(theme.px(46f), theme.px(46f)))
        playRow.addView(Views.text(app, "sub", 16f, inkForCard, "Play", bold = true),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = theme.px(12f) })
        textCol.addView(playRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = theme.px(16f) })
        if (fullArt) {
            card.addView(art, LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // A soft ink wash at the bottom so the printed title survives any backdrop.
            card.addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(Color.argb(150, 20, 18, 14), Color.TRANSPARENT))
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(150f)).apply {
                gravity = Gravity.BOTTOM
            })
            card.addView(textCol, LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            val artBox = FrameLayout(context)
            artBox.addView(art, LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            card.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 52f))
                addView(artBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 48f))
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
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
            setPadding(theme.px(22f), theme.px(13f), theme.px(22f), theme.px(13f))
            background = Views.rounded(theme.ink, theme.radius("chip", 999f))
        }
        val glyph = when {
            label.contains("Trending", true) -> "\u2665"
            label.contains("New", true) -> "\u2726"
            label.contains("Top", true) || label.contains("rated", true) -> "\u2605"
            else -> "\u25CF"
        }
        chip.addView(Views.text(app, "meta", 15f, Color.WHITE, glyph, bold = true),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        chip.addView(Views.text(app, "chip", 16f,
            if (active) theme.color("accentYellow", "#FFD34E") else Color.WHITE, label, bold = true),
            LinearLayout.LayoutParams(
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
        BrowseImages.load(art, item?.backdrop ?: item?.poster, theme.px(760f))
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
