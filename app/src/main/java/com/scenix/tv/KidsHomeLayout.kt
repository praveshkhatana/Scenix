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
 * The mockup's rounded outer frame is only a presentation border; on a real TV the WHOLE screen is
 * the sunshine-yellow stage, deepening slightly toward the bottom, with every block sitting directly
 * on it: a black circular brand mark at top-left with its BLACK tab pill welded beside it and a
 * yellow search dot at the pill's end; one household stop at right — a single black pill holding
 * the avatar and the profile's name, welded beside a black settings gear, both reachable from the
 * search dot with the D-pad (a bell for a feature this app does not have would be decoration);
 * two oversized feature cards — one full-art with the title printed over the top and a play
 * circle at the foot, one yellow plate with key art BLEEDING out of its inner edge into the plate
 * — each carrying a soft standing shadow so it glows off the sheet; a row of
 * black chip pills naming the household's real shelves; and below the hero, the host's rails draw
 * chunky square captioned tiles. Focus is a thick black keyline — the same ink as everything else
 * — never a glow.
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
        val onSettings: () -> Unit,
        val onShelf: (String) -> Unit,
    )

    private val theme = app.theme
    private var items: List<Item> = emptyList()
    private var activeIndex = 0

    val root: View get() = this
    val primaryControl: View get() = cardA
    val navigationAnchor: View? get() = pillStops.firstOrNull()
    val preferredHeightPx: Int get() = theme.px(theme.packInt("kidsHomeHeight", 470).toFloat())

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
    private lateinit var avatarFace: ImageView
    private lateinit var avatarIni: TextView
    private lateinit var namePlate: TextView
    private lateinit var searchStop: View
    private lateinit var profilePill: LinearLayout
    private lateinit var gearStop: FrameLayout

    init {
        // Transparent: the host paints the sunshine stage across the entire screen behind this
        // composition and the rails below it; the yellow stage IS the page.

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        addView(content, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { setMargins(theme.px(56f), theme.px(26f), theme.px(56f), 0) })

        // --- header: brand circle + its tab pill at left, household cluster at right ------------
        val header = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
        val leftGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = Views.text(app, "h2", 26f, Color.WHITE, "S", bold = true,
            gravity = Gravity.CENTER).apply {
            background = Views.rounded(theme.ink, theme.px(999f))
        }
        leftGroup.addView(mark, LinearLayout.LayoutParams(theme.px(60f), theme.px(60f)))

        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Views.rounded(theme.color("pill", "#1E252E"), theme.px(999f))
            setPadding(theme.px(8f), theme.px(7f), theme.px(7f), theme.px(7f))
        }
        val sections = listOf("Movies" to "movies", "Series" to "series", "Live" to "live")
        sections.forEachIndexed { index, (label, route) ->
            pill.addView(makePillStop(label, route, index == 0))
        }
        pill.addView(makeSearchDot(), LinearLayout.LayoutParams(theme.px(46f), theme.px(46f)).apply {
            marginStart = theme.px(8f)
        })
        leftGroup.addView(pill, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, theme.px(60f),
        ).apply { marginStart = theme.px(18f) })
        header.addView(leftGroup, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })

        val household = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        // ONE household stop, not three. The avatar and the name are the same job — switching who
        // watches — so they weld into one black pill: a face disc, the profile's name, and its
        // quiet chevron. There is no bell: this app has no notifications yet, and an icon that
        // lies about a feature is worse than empty space.
        profilePill = LinearLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "Profiles"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Views.rounded(theme.ink, theme.px(999f))
            setPadding(theme.px(7f), theme.px(7f), theme.px(20f), theme.px(7f))
        }
        avatarFace = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setOval(0, 0, v.width, v.height)
                }
            }
        }
        avatarIni = Views.text(app, "meta", 18f, Color.WHITE, "?", bold = true,
            gravity = Gravity.CENTER).apply {
            background = Views.rounded(Color.argb(60, 255, 255, 255), theme.px(999f))
        }
        profilePill.addView(avatarIni, LinearLayout.LayoutParams(theme.px(46f), theme.px(46f)))
        profilePill.addView(avatarFace, LinearLayout.LayoutParams(theme.px(46f), theme.px(46f)))
        namePlate = Views.text(app, "nav", 17f, Color.WHITE, "", bold = true, maxLines = 1)
        profilePill.addView(namePlate, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = theme.px(12f) })
        val down = Views.text(app, "nav", 15f, theme.color("accentYellow", "#FFD34E"), "⌄",
            bold = true, gravity = Gravity.CENTER)
        profilePill.addView(down, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = theme.px(8f) })
        Focus.attach(profilePill, profilePill, app, theme.px(999f), scale = false, elevate = false,
            onClick = callbacks.onProfile)
        pillStops += profilePill
        household.addView(profilePill, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, theme.px(60f)))

        // The settings gear: without it a kids household has no door to the TV's own settings.
        gearStop = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "Settings"
            background = Views.rounded(theme.ink, theme.px(999f))
        }
        gearStop.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_nav_settings)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(theme.px(12f), theme.px(12f), theme.px(12f), theme.px(12f))
        }, LayoutParams(theme.px(50f), theme.px(50f)))
        Focus.attach(gearStop, gearStop, app, theme.px(999f), scale = false, elevate = false,
            onClick = callbacks.onSettings)
        pillStops += gearStop
        household.addView(gearStop, LinearLayout.LayoutParams(theme.px(52f), theme.px(52f)).apply {
            marginStart = theme.px(12f)
        })
        header.addView(household, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL })
        content.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(68f)))

        // The top-right stops were previously decoration the remote could never reach: spell out
        // the walk RIGHT out of the search dot — profile pill, then the gear.
        searchStop.nextFocusRightId = profilePill.id
        profilePill.nextFocusLeftId = searchStop.id
        profilePill.nextFocusRightId = gearStop.id
        gearStop.nextFocusLeftId = profilePill.id

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
        cardRow.addView(cardA, LinearLayout.LayoutParams(0, theme.px(280f), 40f))
        cardRow.addView(cardB, LinearLayout.LayoutParams(0, theme.px(280f), 60f).apply {
            marginStart = theme.px(22f)
        })
        content.addView(cardRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(280f),
        ).apply { topMargin = theme.px(20f) })
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
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(58f),
        ).apply { topMargin = theme.px(22f) })

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
            if (active) theme.color("stageYellow", "#FFC93A") else Color.WHITE,
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
        searchStop = dot
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

    /** fullArt = the backdrop covers the whole card, the title prints over its top and a black
     *  play circle sits at its foot; tinted = a yellow plate with key art bleeding from the inner
     *  edge, the title in ink and the same play circle at the foot, exactly as the mockup shows. */
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
            // A soft standing shadow: the plate glows off the sheet, as in the reference.
            elevation = theme.px(16f).toFloat()
        }
        val art = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val title = Views.text(app, "h2", 34f, Color.WHITE, "", bold = true, maxLines = 2)
        val playRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playRow.addView(Views.text(app, "meta", 18f, Color.WHITE, "\u25B6", bold = true,
            gravity = Gravity.CENTER).apply {
            background = Views.rounded(theme.ink, theme.px(999f))
        }, LinearLayout.LayoutParams(theme.px(46f), theme.px(46f)))
        playRow.addView(Views.text(app, "sub", 16f, Color.WHITE, "Play", bold = true),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = theme.px(12f) })
        if (fullArt) {
            card.addView(art, LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // Soft ink washes top and bottom so printed text survives any backdrop.
            card.addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.argb(150, 20, 18, 14), Color.TRANSPARENT))
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(150f)).apply {
                gravity = Gravity.TOP
            })
            card.addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(Color.argb(150, 20, 18, 14), Color.TRANSPARENT))
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(140f)).apply {
                gravity = Gravity.BOTTOM
            })
            card.addView(title, LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.START or Gravity.TOP
                setMargins(theme.px(26f), theme.px(20f), theme.px(20f), 0)
            })
            card.addView(playRow, LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.START or Gravity.BOTTOM
                setMargins(theme.px(20f), 0, theme.px(16f), theme.px(18f))
            })
        } else {
            val artBox = FrameLayout(context)
            artBox.addView(art, LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // The key art bleeds out of the plate: a tint gradient eats the photo's inner edge
            // instead of leaving the hard seam a pasted rectangle gives.
            artBox.addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(theme.color("cardTint", "#FFD866"), Color.TRANSPARENT))
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // The left column stacks the ink title at the top and the play row at the foot.
            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(theme.px(28f), theme.px(22f), theme.px(10f), theme.px(20f))
            }
            title.setTextColor(theme.ink)
            textCol.addView(title, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            textCol.addView(View(context), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            textCol.addView(playRow)
            card.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 46f))
                addView(artBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 54f))
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
        shelves.take(7).forEachIndexed { index, (id, label) ->
            val chip = makeChip(id, label.trim().removePrefix("New on "), index == 0)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, theme.px(52f))
            if (chipRow.childCount > 0) lp.marginStart = theme.px(14f)
            chipRow.addView(chip, lp)
        }
        cardA.nextFocusDownId = chipRow.getChildAt(0)?.id ?: View.NO_ID
        cardB.nextFocusDownId = chipRow.getChildAt(0)?.id ?: View.NO_ID
    }

    /** The household pill prints the signed-in profile's name beside its face. */
    fun setProfile(name: String, avatarUrl: String?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        avatarIni.text = trimmed.take(1).uppercase()
        namePlate.text = trimmed.take(18)
        if (!avatarUrl.isNullOrBlank()) {
            BrowseImages.load(avatarFace, avatarUrl, theme.px(46f)) { ok ->
                if (ok) avatarIni.visibility = View.GONE
            }
        }
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
