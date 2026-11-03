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
 * The mockup's rounded outer frame is only a presentation border; on a real TV the WHOLE screen is
 * the soft blue-grey wallpaper wash, with every block sitting directly on it: the SCENIX wordmark
 * in white at left; a DARK centred pill nav with the search target welded to its end; one
 * household stop at right (a frosted pill with the avatar and profile name, beside a settings
 * gear, both reachable from the search dot); two tinted feature cards side by side —
 * steel-blue and paler periwinkle plates carrying a white title, a dark circular play control and
 * key art bleeding from the facing edge; a row of frosted glass chips naming the household's real
 * shelves with only one chip darkened; and below the hero, captioned poster rails with rating and
 * year. Nothing is opaque black and nothing is a full-bleed billboard; motion stays gentle because
 * the whole pack reads as calm.
 */
internal class CleanHomeLayout(
    context: Context,
    private val app: AppContext,
    private val callbacks: Callbacks,
) : FrameLayout(context) {

    data class Callbacks(
        val onOpen: (Item) -> Unit,
        /** Hold OK on the card: the profile's list, without walking into the title first. */
        val onFavourite: (Item) -> Unit = {},
        val onSection: (String) -> Unit,
        val onProfile: () -> Unit,
        val onSearch: () -> Unit,
        val onSettings: () -> Unit,
        /** A chip jumps to the real shelf it names rather than inventing a filtered route. */
        val onShelf: (String) -> Unit,
    )

    private val theme = app.theme
    private var items: List<Item> = emptyList()
    private var activeIndex = 0

    val root: View get() = this
    val primaryControl: View get() = cardA
    val navigationAnchor: View? get() = pillStops.firstOrNull()
    val preferredHeightPx: Int get() = theme.px(theme.packInt("cleanHomeHeight", 480).toFloat())

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
    private lateinit var nameText: TextView
    private lateinit var searchStop: View
    private lateinit var profilePill: LinearLayout
    private lateinit var gearStop: FrameLayout

    init {
        // Transparent: the host paints the wallpaper across the entire screen behind this
        // composition (and behind the rails below it), exactly as in the mockup, where the
        // rounded outer frame was presentation framing and the wall is the whole page.

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        addView(content, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { setMargins(theme.px(56f), theme.px(24f), theme.px(56f), 0) })

        // --- header: white wordmark left, dark centred pill, household right -------------------
        val header = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
        header.addView(Views.text(app, "brand", 26f, theme.color("inkOnWall", "#FFFFFF"), "SCENIX",
            bold = true), LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })

        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Views.rounded(theme.color("pill", "#1E252E"), theme.px(999f))
            setPadding(theme.px(6f), theme.px(6f), theme.px(6f), theme.px(6f))
        }
        val sections = listOf(
            // The reference pill starts at Home; without it Clean had no one-press way back to the
            // top from Movies/Series. The wordmark stays a mark, not a second hidden stop.
            "Home" to "",
            "Movies" to "movies",
            "Series" to "series",
            "Games" to "games",
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
            clipChildren = false
            clipToPadding = false
        }
        // ONE household stop: the avatar and the name are the same job — switching who watches —
        // so they weld into one frosted pill with its chevron. No bell: there is no notifications
        // feature yet, and an icon that promises one is decoration that lies.
        profilePill = LinearLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "Profiles"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Views.rounded(theme.color("pill", "#1E252E"), theme.px(999f))
            setPadding(theme.px(6f), theme.px(6f), theme.px(18f), theme.px(6f))
        }
        avatarIni = Views.text(app, "meta", 17f, theme.ink, "?", bold = true,
            gravity = Gravity.CENTER).apply {
            background = Views.rounded(theme.color("inkOnWall", "#FFFFFF"), theme.px(999f))
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
        profilePill.addView(avatarIni, LinearLayout.LayoutParams(theme.px(44f), theme.px(44f)))
        profilePill.addView(avatarFace, LinearLayout.LayoutParams(theme.px(44f), theme.px(44f)))
        nameText = Views.text(app, "nav", 17f, theme.color("inkOnWall", "#FFFFFF"), "", bold = true,
            maxLines = 1)
        profilePill.addView(nameText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = theme.px(10f) })
        val down = Views.text(app, "nav", 15f, Color.WHITE, "⌄", bold = true, gravity = Gravity.CENTER)
        profilePill.addView(down, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = theme.px(6f) })
        Focus.attach(profilePill, profilePill, app, theme.px(999f), scale = false, elevate = false,
            onClick = callbacks.onProfile)
        pillStops += profilePill
        household.addView(profilePill, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, theme.px(56f)))

        // The settings gear: the light pack's door to the TV's own settings, reachable from the
        // search dot rather than decoration the remote can never meet.
        gearStop = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "Settings"
            background = Views.rounded(theme.color("chipFrost", "#3A4552"), theme.px(999f))
        }
        gearStop.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_nav_settings)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(theme.px(12f), theme.px(12f), theme.px(12f), theme.px(12f))
        }, LayoutParams(theme.px(48f), theme.px(48f)))
        Focus.attach(gearStop, gearStop, app, theme.px(999f), scale = false, elevate = false,
            onClick = callbacks.onSettings)
        pillStops += gearStop
        household.addView(gearStop, LinearLayout.LayoutParams(theme.px(50f), theme.px(50f)).apply {
            marginStart = theme.px(10f)
        })
        header.addView(household, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL })
        content.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(64f)))

        searchStop.nextFocusRightId = profilePill.id
        profilePill.nextFocusLeftId = searchStop.id
        profilePill.nextFocusRightId = gearStop.id
        gearStop.nextFocusLeftId = profilePill.id

        // --- the two feature cards ------------------------------------------------------------
        val cardRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val a = makeCard(deep = true)
        cardA = a.first; artA = a.second.first; titleA = a.second.second
        val b = makeCard(deep = false)
        cardB = b.first; artB = b.second.first; titleB = b.second.second
        cardRow.addView(cardA, LinearLayout.LayoutParams(0, theme.px(300f), 1f))
        cardRow.addView(cardB, LinearLayout.LayoutParams(0, theme.px(300f), 1f).apply {
            marginStart = theme.px(22f)
        })
        content.addView(cardRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(300f),
        ).apply { topMargin = theme.px(24f) })
        // The light pack speaks focus as a crisp keyline plus a soft lift — never a glow on glass.
        Focus.attach(cardA, cardA, app, theme.radius("card", 22f), scale = false, elevate = true,
            onClick = { itemA?.let(callbacks.onOpen) })
        Focus.attach(cardB, cardB, app, theme.radius("card", 22f), scale = false, elevate = true,
            onClick = { itemB?.let(callbacks.onOpen) })
        armHeroHold(cardA, { itemA }, callbacks.onFavourite)
        armHeroHold(cardB, { itemB }, callbacks.onFavourite)

        // --- frosted chip row: the household's real shelves, named exactly as they are ----------
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
        searchStop = dot
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

    /** A feature card: a tinted plate (deep steel-blue or pale periwinkle) carrying a white
     *  title, a dark circular play control and key art bleeding from the facing edge. */
    private fun makeCard(deep: Boolean): Pair<FrameLayout, Pair<ImageView, TextView>> {
        val card = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            clipChildren = false
            clipToPadding = false
            contentDescription = "Featured title. Open details"
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                if (deep) intArrayOf(theme.color("cardTintA", "#5F86B8"), theme.color("cardTintB", "#47699A"))
                else intArrayOf(theme.color("cardGlassA", "#A9BED8"), theme.color("cardGlassB", "#8CA6C6")),
            )
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
        val title = Views.text(app, "h2", 36f, theme.color("inkOnCard", "#FFFFFF"), "", bold = true,
            maxLines = 3)
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
        playRow.addView(Views.text(app, "sub", 17f, Color.WHITE, "Play", bold = true),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = theme.px(12f) })
        textCol.addView(playRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = theme.px(20f) })
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 56f))
            addView(artBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 44f))
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
            // A LIGHT frosted plate on the light wall, not the old dark navy pill: the reference's
            // chips are translucent white with a hairline rim, the active one a shade brighter.
            background = Views.rounded(
                if (active) theme.color("chipFillActive", "rgba(255,255,255,0.22)")
                else theme.color("chipFill", "rgba(255,255,255,0.10)"),
                theme.radius("chip", 999f), theme.px(1f),
                theme.color("glassRim", "rgba(255,255,255,0.38)"))
        }
        val glyph = when {
            label.contains("Trending", true) -> "\u2197"
            label.contains("New", true) -> "\u2726"
            label.contains("Top", true) || label.contains("rated", true) -> "\u2605"
            label.contains("Language", true) || label.contains("Top Hindi", true) -> "\u2691"
            else -> "\u25CF"
        }
        chip.addView(Views.text(app, "meta", 15f, Color.WHITE, glyph, bold = true),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        chip.addView(Views.text(app, "chip", 16f, Color.WHITE, label, bold = true),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = theme.px(9f) })
        Focus.attach(chip, chip, app, theme.radius("chip", 999f), scale = false, elevate = false,
            onClick = { callbacks.onShelf(shelfId) })
        return chip
    }

    /**
     * Short destination labels, as the reference draws them: Continue, Recommended, More like,
     * Top 10, Trending, Acclaimed. The conversational shelf title is not a chip caption.
     */
    private fun chipLabel(raw: String): String {
        var s = raw.trim().removePrefix("New on ")
        if (s.contains("continue", true)) s = "Continue"
        if (s.contains("because you", true)) s = "Recommended"
        if (s.contains("more like", true)) s = "More like"
        if (s.contains("top 10", true)) s = "Top 10"
        if (s.contains("trending", true)) s = "Trending"
        if (s.contains("acclaim", true)) s = "Acclaimed"
        s = s.replace(Regex("(?i)^(\\S+)\\s(movies|classics|cinema|films|shows|collection|specials)\\b.*"), "$1")
        s = s.replace(Regex("(?i)\\s+(in|on|to|by|from|for|of|that|with|you)\\s.*"), "")
        if (s.length > 14) s = s.take(13).trimEnd() + "…"
        return s
    }

    /** The shelf titles the host actually rendered below; each chip jumps to its shelf. */
    fun setChips(shelves: List<Pair<String, String>>) {
        val ids = shelves.map { it.first }
        if (ids == chipIds) return
        chipIds = ids
        chipRow.removeAllViews()
        shelves.take(7).forEachIndexed { index, (id, label) ->
            val chip = makeChip(id, chipLabel(label), index == 0)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, theme.px(52f))
            if (chipRow.childCount > 0) lp.marginStart = theme.px(14f)
            chipRow.addView(chip, lp)
        }
        cardA.nextFocusDownId = chipRow.getChildAt(0)?.id ?: View.NO_ID
        cardB.nextFocusDownId = chipRow.getChildAt(0)?.id ?: View.NO_ID
    }

    /** The header pill prints the signed-in profile's name beside its face. */
    fun setProfile(name: String, avatarUrl: String?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        avatarIni.text = trimmed.take(1).uppercase()
        nameText.text = trimmed.take(18)
        if (!avatarUrl.isNullOrBlank()) {
            BrowseImages.load(avatarFace, avatarUrl, theme.px(44f)) { ok ->
                if (ok) avatarIni.visibility = View.GONE
            }
        }
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
