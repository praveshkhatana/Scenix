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
 * a flat #FBD364 page; a black brand disc (d88) wearing the Scenix wordmark + a black tab
 * capsule (h62) with a yellow search knob (d49) at its end; ONE household pill h64 — the face
 * disc welded into the left end of a black name capsule with a drawn chevron — beside a gear
 * disc; two oversized feature cards 355 tall split 40/60 — full-art with the title's LOGO
 * lockup over an ink wash (printed title as fallback), and a deep→bright gradient plate with
 * key art bleeding from the outer edge and its logo centred — each carrying the
 * black play disc (d44) bottom-left; uniform black chips 219×86 pitch 234 with short one-word
 * labels; and below the hero, the host's rails draw square #F9C406 tiles 270 r24 gap 62 with
 * NEW/rating badges ON the art and the ALL-CAPS name printed below. Compact chrome (chips,
 * pills, rows) takes the stroke-only halo so its contents stay readable, and rows lift by
 * `focus.rowScale` on focus (owner round 5).
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
    private val logoA: ImageView
    private val logoB: ImageView
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
        // The brand disc is not just a mark: it doubles as the Home stop. Standing in a section,
        // OK on it walks back to the unfiltered mix (owner, round 6: "there is no way to go back
        // to home screen").
        val brand = KidsKit.brandDisc(app, headerH).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "Home"
        }
        Focus.attach(brand, brand, app, theme.px(999f), scale = false, elevate = false,
            glowInset = theme.focusGlowInsetSm, control = true,
            onClick = { callbacks.onSection("home") })
        leftGroup.addView(brand)

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
        // LEFT from the first tab lands on the brand disc; RIGHT walks back onto the tabs.
        brand.nextFocusRightId = pillStops.first().id
        pillStops.first().nextFocusLeftId = brand.id
        header.addView(leftGroup, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })

        val household = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        // ONE household element (owner round 5: combine avatar and name): the face disc welds into
        // the left end of the black name capsule, with the name and a drawn chevron riding inside
        // the same pill. No bell — no notifications feature yet (owner rule); the gear keeps a
        // real door to the TV's settings instead.
        val avatarD = theme.space("kidsAvatar", 64f)
        profilePill = KidsKit.nameCapsule(app, theme.space("kidsProfileH", 64f)).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "Profiles"
        }
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
        profilePill.addView(inner, LinearLayout.LayoutParams(avatarD, avatarD))
        namePlate = Views.text(app, "profileName", 26f, Color.WHITE, "", bold = true, maxLines = 1)
        profilePill.addView(namePlate, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = theme.space("kidsChevronGap", 12f) })
        // The dropdown cue is a drawn chevron rotated to point down — the text "⌄" rendered as a
        // broken oversized caret on TV fonts (owner round 5).
        profilePill.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_right)
            rotation = 90f
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(
            theme.space("kidsChevron", 24f), theme.space("kidsChevron", 24f),
        ).apply { marginStart = theme.space("kidsChevronGap", 10f) })
        Focus.attach(profilePill, profilePill, app, theme.px(999f), scale = false, elevate = false,
            glowInset = theme.focusGlowInsetSm, control = true, onClick = callbacks.onProfile)
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
            glowInset = theme.focusGlowInsetSm, control = true, onClick = callbacks.onSettings)
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
        cardA = a.first; artA = a.third.first; titleA = a.third.second; logoA = a.third.third
        val b = makeCard(fullArt = false)
        cardB = b.first; artB = b.third.first; titleB = b.third.second; logoB = b.third.third
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
            glowInset = theme.focusGlowInsetSm, control = true,
            onClick = { callbacks.onSection(route) })
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
            glowInset = theme.focusGlowInsetSm, control = true, onClick = callbacks.onSearch)
        pillStops += dot
        return dot
    }

    /** fullArt = the backdrop covers the whole card and the title prints over its top; plate =
     *  the reference's gradient card with key art bleeding from the inner (right) edge and the
     *  lockup centred. Both carry a black play disc at the foot, exactly as measured. */
    private fun makeCard(
        fullArt: Boolean,
    ): Triple<FrameLayout, View, Triple<ImageView, TextView, ImageView>> {
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
        // Owner round 5: a title's own logo lockup is the hero's headline when the hub has one.
        val logo = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        val playRow = KidsKit.playRow(app, if (fullArt) "Play trailer" else "Play")
        if (fullArt) {
            card.addView(art, LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // Soft ink washes top and bottom so printed text survives any backdrop. The washes
            // carry the card's own corner radii: as plain rectangles they painted square grey
            // wedges past the rounded art, visible as odd corners on light key art (owner,
            // round 6).
            val washR = theme.radius("card", 32f).toFloat()
            card.addView(View(context).apply {
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.argb(150, 20, 18, 14), Color.TRANSPARENT)
                ).apply { cornerRadii = floatArrayOf(washR, washR, washR, washR, 0f, 0f, 0f, 0f) }
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(150f)).apply {
                gravity = Gravity.TOP
            })
            card.addView(View(context).apply {
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(Color.argb(150, 20, 18, 14), Color.TRANSPARENT)
                ).apply { cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, washR, washR, washR, washR) }
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(140f)).apply {
                gravity = Gravity.BOTTOM
            })
            card.addView(title, LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.START or Gravity.TOP
                setMargins(theme.px(30f), theme.px(26f), theme.px(20f), 0)
            })
            card.addView(logo, LayoutParams(
                theme.space("kidsHeroLogoW", 400f), theme.space("kidsHeroLogoH", 150f),
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
            textCol.addView(logo, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, theme.space("kidsHeroLogoH", 150f)))
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
        return Triple(card, ringOn, Triple(art, title, logo))
    }

    private fun makeChip(shelfId: String, label: String): View {
        val chip = KidsKit.chip(app, KidsKit.chipGlyph(label), label).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "Jump to $label"
        }
        Focus.attach(chip, chip, app, theme.radius("chip", 999f), scale = false, elevate = false,
            glowInset = theme.focusGlowInsetSm, control = true,
            onClick = { callbacks.onShelf(shelfId) })
        return chip
    }

    /**
     * A 219-unit capsule fits one idea, not a sentence (owner round 5: chips truncated to
     * "Continue watchi…"). Strip the hub's conversational prefaces and cap what remains.
     */
    private fun shortChipLabel(label: String): String {
        var s = label.trim()
        for (prefix in listOf("Because you watched ", "More like ", "New on ", "Trending on ")) {
            if (s.startsWith(prefix, ignoreCase = true)) s = s.substring(prefix.length)
        }
        s = when {
            s.contains("Continue", true) -> "Continue"
            s.contains("Top 10", true) -> "Top 10"
            s.contains("acclaimed", true) -> "Acclaimed"
            else -> s
        }
        val words = s.split(" ").filter { it.isNotBlank() }
        if (words.size > 3) s = words.take(3).joinToString(" ")
        return if (s.length > 20) s.take(20).trimEnd() + "…" else s
    }

    /** The shelf titles the host actually rendered below; each chip jumps to its shelf. */
    fun setChips(shelves: List<Pair<String, String>>) {
        val ids = shelves.map { it.first }
        if (ids == chipIds) return
        chipIds = ids
        chipRow.removeAllViews()
        shelves.take(8).forEach { (id, label) ->
            val chip = makeChip(id, shortChipLabel(label))
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
        bindCard(cardA, artA, titleA, logoA, a)
        bindCard(cardB, artB, titleB, logoB, b)
        cardB.visibility = if (b == null) View.GONE else View.VISIBLE
    }

    private fun bindCard(
        card: FrameLayout,
        art: ImageView,
        title: TextView,
        logo: ImageView,
        item: Item?,
    ) {
        card.contentDescription = item?.let { "${it.title}. Open details" } ?: "Featured title"
        title.text = item?.title ?: ""
        BrowseImages.load(art, item?.backdrop ?: item?.poster, theme.px(760f))
        // The hub's logo artwork, when it exists, replaces the printed title: a real logo
        // lockup is the reference's hero language (owner round 5).
        logo.tag = item?.identity ?: "none"
        logo.setImageDrawable(null)
        val url = item?.logo
        if (!url.isNullOrBlank()) {
            BrowseImages.load(logo, url, theme.px(420f)) { ok ->
                logo.visibility = if (ok) View.VISIBLE else View.GONE
                title.visibility = if (ok) View.GONE else View.VISIBLE
            }
        } else {
            logo.visibility = View.GONE
            title.visibility = View.VISIBLE
        }
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
