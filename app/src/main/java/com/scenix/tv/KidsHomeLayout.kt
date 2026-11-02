package com.scenix.tv

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Item
import com.scenix.tv.core.Views

/**
 * The bright stage composition for kids packs (`homeHero: kids-stage`), assembled entirely from
 * `KidsKit` builders so every Kids plate speaks one language. Geometry and colour come ONLY
 * from the contract in design-contracts/kids.md (corrected reference, uniform scale 0.803):
 * a flat #FBD364 page; black brand disc (d88) + black tab capsule (h62) with a yellow search
 * knob (d49) at its end; an avatar disc standing on the page beside a black name capsule (h64)
 * and a gear disc; two oversized feature cards 355 tall split 40/60 — full-art printed title,
 * and a deep→bright gradient plate with key art bleeding from the outer edge, each carrying the
 * black play disc (d44) bottom-left; uniform black chips 219×86 pitch 234; and below the hero,
 * the host's rails draw square #F9C406 captioned tiles 270 r24 gap 62.
 *
 * Focus is the reference's own language (measured on the corrected reference): solid #FDDB6F
 * bands out to 40 closed by a 4px warm-dark #A88200 keyline, tiles scaling to 1.11 — never a
 * hard box, never a border-only cue (owner rounds 3–4).
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
    val preferredHeightPx: Int get() = theme.px(theme.packInt("kidsHomeHeight", 660).toFloat())

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
        // Transparent: the host paints the flat sunshine stage across the entire screen behind
        // this composition and the rails below it; the yellow stage IS the page. Nothing in the
        // hero band may crop the focus halos: their canvases reach a glowInset past their tiles.
        clipChildren = false
        clipToPadding = false

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        addView(content, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { setMargins(theme.gutter, theme.space("kidsTopMargin", 50f), theme.gutter, 0) })

        // --- header: brand disc + its tab capsule at left, household cluster at right ----------
        val headerH = theme.space("kidsNavH", 88f)
        val header = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
        val leftGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        leftGroup.addView(KidsKit.brandDisc(app, headerH, theme.brandMark))

        val pill = KidsKit.tabCapsule(app, theme.space("kidsPillH", 62f))
        val sections = listOf("Movies" to "movies", "Series" to "series", "Live" to "live")
        sections.forEachIndexed { index, (label, route) ->
            pill.addView(makePillStop(label, route, index == 0))
        }
        pill.addView(makeSearchDot(), LinearLayout.LayoutParams(
            theme.space("kidsKnob", 49f), theme.space("kidsKnob", 49f),
        ).apply { marginStart = theme.px(6f) })
        leftGroup.addView(pill, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, theme.space("kidsPillH", 62f),
        ).apply { marginStart = theme.space("kidsLogoGap", 33f) })
        header.addView(leftGroup, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })

        val household = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        // ONE household stop in the reference's shape: a face disc standing on the page, a narrow
        // gap, then the black name capsule. No bell — no notifications feature yet (owner rule);
        // the gear keeps a real door to the TV's settings instead.
        profilePill = LinearLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "Profiles"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val avatarD = theme.space("kidsAvatar", 64f)
        val avatarWrap = KidsKit.avatarDisc(app, avatarD)
        avatarFace = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setOval(0, 0, v.width, v.height)
                }
            }
        }
        avatarIni = Views.text(app, "meta", 20f, Color.WHITE, "?", bold = true,
            gravity = Gravity.CENTER).apply {
            background = Views.rounded(theme.color("stageDeep", "#FBD364"), theme.px(999f))
        }
        val inner = FrameLayout(context)
        inner.addView(avatarIni, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        inner.addView(avatarFace, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        inner.clipToOutline = true
        inner.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                o.setOval(0, 0, v.width, v.height)
            }
        }
        avatarWrap.addView(inner, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        profilePill.addView(avatarWrap, LinearLayout.LayoutParams(avatarD, avatarD))
        val capsule = KidsKit.nameCapsule(app, theme.space("kidsProfileH", 64f))
        namePlate = Views.text(app, "profileName", 26f, Color.WHITE, "", bold = true, maxLines = 1)
        capsule.addView(namePlate, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        capsule.addView(Views.text(app, "nav", 21f, Color.WHITE, "⌄", bold = true,
            gravity = Gravity.CENTER), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = theme.px(12f) })
        profilePill.addView(capsule, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, theme.space("kidsProfileH", 64f),
        ).apply { marginStart = theme.space("kidsAvatarGap", 11f) })
        Focus.attach(profilePill, profilePill, app, theme.px(999f), scale = false, elevate = false,
            glowInset = theme.focusGlowInsetSm, onClick = callbacks.onProfile)
        pillStops += profilePill
        household.addView(profilePill, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        gearStop = KidsKit.gearDisc(app, R.drawable.ic_nav_settings,
            theme.space("kidsProfileH", 64f)).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "Settings"
        }
        Focus.attach(gearStop, gearStop, app, theme.px(999f), scale = false, elevate = false,
            glowInset = theme.focusGlowInsetSm, onClick = callbacks.onSettings)
        pillStops += gearStop
        household.addView(gearStop, LinearLayout.LayoutParams(
            theme.space("kidsProfileH", 64f), theme.space("kidsProfileH", 64f),
        ).apply { marginStart = theme.space("kidsGearGap", 15f) })
        header.addView(household, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL })
        content.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, headerH))

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
        val heroH = theme.space("kidsHeroH", 355f)
        val a = makeCard(fullArt = true)
        cardA = a.first; artA = a.third.first; titleA = a.third.second
        val b = makeCard(fullArt = false)
        cardB = b.first; artB = b.third.first; titleB = b.third.second
        cardRow.addView(cardA, LinearLayout.LayoutParams(0, heroH, 40f))
        cardRow.addView(cardB, LinearLayout.LayoutParams(0, heroH, 60f).apply {
            marginStart = theme.space("kidsHeroGap", 54f)
        })
        content.addView(cardRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, heroH,
        ).apply { topMargin = theme.space("kidsHeroTopGap", 53f) })
        // The halo stands on its own oversized canvas behind the art — the rail tiles' pattern —
        // so the glow reaches past the card's edge instead of being cropped by it.
        val heroRadius = theme.radius("card", 32f)
        Focus.attach(cardA, a.second, app, heroRadius, scale = false,
            elevate = false, glowInset = theme.focusGlowInset,
            onClick = { itemA?.let(callbacks.onOpen) })
        Focus.attach(cardB, b.second, app, heroRadius, scale = false,
            elevate = false, glowInset = theme.focusGlowInset,
            onClick = { itemB?.let(callbacks.onOpen) })

        // --- black chip capsules: the household's real shelves ----------------------------------
        chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        content.addView(chipRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.space("kidsChipH", 86f),
        ).apply { topMargin = theme.space("kidsChipTopGap", 50f) })

        cardA.nextFocusUpId = pillStops.firstOrNull()?.id ?: View.NO_ID
        cardB.nextFocusUpId = pillStops.firstOrNull()?.id ?: View.NO_ID
    }

    private fun makePillStop(label: String, route: String, active: Boolean): View {
        val stop = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "$label section"
            setPadding(theme.px(22f), 0, theme.px(22f), 0)
        }
        stop.addView(KidsKit.segmentText(app, label, active), LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER })
        Focus.attach(stop, stop, app, theme.px(999f), scale = false, elevate = false,
            glowInset = theme.focusGlowInsetSm, onClick = { callbacks.onSection(route) })
        pillStops += stop
        return stop
    }

    private fun makeSearchDot(): View {
        val dot = KidsKit.searchKnob(app, R.drawable.ic_nav_search,
            theme.space("kidsKnob", 49f)).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "Search"
        }
        searchStop = dot
        Focus.attach(dot, dot, app, theme.px(999f), scale = false, elevate = false,
            glowInset = theme.focusGlowInsetSm, onClick = callbacks.onSearch)
        pillStops += dot
        return dot
    }

    /** fullArt = the backdrop covers the whole card and the title prints over its top; plate =
     *  the reference's gradient card with key art bleeding from the inner (right) edge and the
     *  lockup centred. Both carry a black play disc at the foot, exactly as measured. */
    private fun makeCard(
        fullArt: Boolean,
    ): Triple<FrameLayout, View, Pair<ImageView, TextView>> {
        val glowPad = theme.focusGlowInset
        val card = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            clipChildren = false
            clipToPadding = false
            contentDescription = "Featured title. Open details"
            background = KidsKit.heroPlate(app, fullArt)
            outlineProvider = KidsKit.heroOutline(app)
            // A soft standing shadow: the plate glows off the sheet, as in the reference.
            elevation = theme.px(16f).toFloat()
        }
        val ringOn = View(context)
        card.addView(ringOn, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { setMargins(-glowPad, -glowPad, -glowPad, -glowPad) })
        val art = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            // The artwork rounds itself; the card does NOT clip to its outline — a clipped card
            // cropped the focus halo at the card's edge (round 3).
            clipToOutline = true
            outlineProvider = KidsKit.heroOutline(app)
        }
        val title = Views.text(app, "h2", 56f, Color.WHITE, "", bold = true, maxLines = 2)
        val playRow = KidsKit.playRow(app, if (fullArt) "Play trailer" else "Play")
        if (fullArt) {
            card.addView(art, LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // Soft ink washes top and bottom so printed text survives any backdrop.
            card.addView(View(context).apply {
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.argb(150, 20, 18, 14), Color.TRANSPARENT))
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(150f)).apply {
                gravity = Gravity.TOP
            })
            card.addView(View(context).apply {
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(Color.argb(150, 20, 18, 14), Color.TRANSPARENT))
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(140f)).apply {
                gravity = Gravity.BOTTOM
            })
            card.addView(title, LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.START or Gravity.TOP
                setMargins(theme.px(30f), theme.px(26f), theme.px(20f), 0)
            })
            card.addView(playRow, LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.START or Gravity.BOTTOM
                setMargins(theme.px(24f), 0, theme.px(16f), theme.px(22f))
            })
        } else {
            val artBox = FrameLayout(context)
            artBox.addView(art, LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // The key art bleeds out of the plate: a tint gradient eats the photo's inner edge
            // instead of leaving the hard seam a pasted rectangle gives.
            artBox.addView(View(context).apply {
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(theme.color("heroPlateL", "#F2AE01"), Color.TRANSPARENT))
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // The lockup centres in the plate's left field; the play disc rides its foot.
            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(theme.px(28f), theme.px(22f), theme.px(10f), theme.px(22f))
            }
            title.setTextColor(theme.ink)
            title.gravity = Gravity.CENTER
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
        return Triple(card, ringOn, Pair(art, title))
    }

    private fun makeChip(shelfId: String, label: String): View {
        val chip = KidsKit.chip(app, KidsKit.chipGlyph(label), label).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "Jump to $label"
        }
        Focus.attach(chip, chip, app, theme.radius("chip", 999f), scale = false, elevate = false,
            glowInset = theme.focusGlowInsetSm, onClick = { callbacks.onShelf(shelfId) })
        return chip
    }

    /** The shelf titles the host actually rendered below; each chip jumps to its shelf. */
    fun setChips(shelves: List<Pair<String, String>>) {
        val ids = shelves.map { it.first }
        if (ids == chipIds) return
        chipIds = ids
        chipRow.removeAllViews()
        shelves.take(8).forEach { (id, label) ->
            val chip = makeChip(id, label.trim().removePrefix("New on "))
            val lp = LinearLayout.LayoutParams(theme.space("kidsChipW", 219f),
                theme.space("kidsChipH", 86f))
            if (chipRow.childCount > 0) lp.marginStart = theme.space("kidsChipGap", 15f)
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
            BrowseImages.load(avatarFace, avatarUrl, theme.space("kidsAvatar", 64f)) { ok ->
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
