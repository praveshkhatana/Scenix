package com.scenix.tv

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Item
import com.scenix.tv.core.Views

/**
 * The Liquid Glass Home composition for packs which opt into `homeHero: glass-cards`
 * (design-contracts/glass.md — replicated from the reference screenshot).
 *
 * This class only places parts and wires the D-pad graph; every part (pill segments, search knob,
 * household stop, feature plates, Play control, frost chips) comes from [GlassKit], so the same
 * components read identically on Detail and the secondary pages.  The page stands on the soft
 * wall the host paints; a dark centred pill carries a LIGHT active segment and a light search
 * knob; two unequal tinted plates form the hero band (measured 741:1067) with white titles
 * anchored top-left, a dark-disc Play bottom-left and key art bleeding from the facing edge; a
 * frost-chip row jumps to real shelves; rails caption posters below the art.
 */
internal class GlassHomeLayout(
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
        /** The household's optional modes: a pill segment for a mode nobody installed is a dead end. */
        val showsLive: Boolean = true,
        val showsGames: Boolean = true,
    )

    private val theme = app.theme
    private var items: List<Item> = emptyList()

    /** The optional modes are doors to installed add-ons, so their segments exist only when they do. */
    private fun offered(route: String): Boolean = when (route) {
        "games" -> callbacks.showsGames
        "live" -> callbacks.showsLive
        else -> true
    }

    val root: View get() = this
    val primaryControl: View get() = cardA
    val navigationAnchor: View? get() = pillStops.firstOrNull()
    val preferredHeightPx: Int
        get() = theme.px(30f) + navBandH + theme.px(44f) + heroH + theme.px(64f) + chipH

    private val navBandH = theme.space("navBand", 68f)
    /** The plate band's unconstrained height; [appliedHeroH] is what the host slot can afford. */
    private val heroH = theme.space("heroHeight", 397f)
    private var appliedHeroH = heroH
    private val chipH = theme.space("chipHeight", 104f)

    private val cardRow: LinearLayout
    private val cardA: FrameLayout
    private val cardB: FrameLayout
    private val artA: ImageView
    private val artB: ImageView
    private val titleA: TextView
    private val titleB: TextView
    // Captured ONCE in init like art/title: BrowseImages.load overwrites view.tag with its request
    // key, so a later findViewWithTag("logo") would miss the plate's logo forever and its re-bind
    // could never hide the typed fallback under a loaded lockup (owner ask, 2026-11-02).
    private val logoA: ImageView
    private val logoB: ImageView
    private var itemA: Item? = null
    private var itemB: Item? = null
    private val chipRow: LinearLayout
    /** The chip row scrolls when the pack asks for larger type: equal widths cannot hold 150% zoom. */
    private val chipScroll: HorizontalScrollView
    private lateinit var pill: LinearLayout
    /** Chrome surfaces wearing the real sampled frost (LiquidFrost). */
    private val frostSurfaces = ArrayList<LiquidFrost>()
    private val pillStops = ArrayList<View>()
    private var chipIds: List<String> = emptyList()
    private val profile: GlassKit.ProfilePill
    private lateinit var searchStop: View
    private lateinit var gearStop: View

    init {
        // Transparent: the host paints the wash across the whole screen behind this composition.
        // Never clip our own children: the hero plates cast their elevation shadow into the
        // gutter margins, and a clipped-to-bounds root cuts that shadow in a hard vertical line
        // beside the card's bottom corner (owner report, 2026-11-02).
        clipChildren = false
        clipToPadding = false

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        addView(content, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { setMargins(theme.gutter, theme.px(30f), theme.gutter, 0) })

        // --- header: wordmark left, dark centred pill, household right --------------------------
        val header = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
        // The owner's wordmark file, not styled text — the same mark the splash and the front door
        // wear, floated white on the wall as the reference measures it.
        header.addView(ImageView(context).apply {
            setImageResource(R.drawable.scenix_wordmark)
            setColorFilter(theme.color("inkOnWall", "#FFFFFF"))
            scaleType = ImageView.ScaleType.FIT_START
            contentDescription = "Scenix"
        }, LayoutParams(theme.px(140f), theme.px(49f)).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        })

        pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Views.rounded(theme.color("pill", "rgba(8,16,28,0.86)"), theme.px(999f))
            setPadding(theme.px(6f), theme.px(6f), theme.px(6f), theme.px(6f))
        }
        // Measured: the pill carries FOUR section words (Home lit on the reference) plus the knob
        // inside 425px — words set close, not the wide 3-stop spacing of the first pass.
        // goTab("") returns to the unfiltered home; the section is baked in because switching
        // sections rebuilds this page, so the pill always paints where you actually stand.
        val standing = (context as? BrowseActivity)?.intent
            ?.getStringExtra(BrowseActivity.EXTRA_TAB).orEmpty()
        listOf("Home" to "", "Movies" to "movies", "Series" to "series", "Games" to "games", "Live" to "live")
            .filter { (_, route) -> offered(route) }
            .forEach { (label, route) ->
                val stop = GlassKit.segment(context, app, label, route == standing) {
                    callbacks.onSection(route)
                }
                pillStops += stop
                pill.addView(stop)
            }
        searchStop = GlassKit.searchKnob(context, app, callbacks.onSearch)
        pill.addView(searchStop, LinearLayout.LayoutParams(theme.px(48f), theme.px(48f)).apply {
            marginStart = theme.px(6f)
        })
        header.addView(pill, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, navBandH,
        ).apply { gravity = Gravity.CENTER })

        val household = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        // ONE household stop (the reference's bell is omitted: no notifications feature yet —
        // deviation log). The gear is the pack's door to Settings, reachable from the pill.
        profile = GlassKit.profilePill(context, app, callbacks.onProfile)
        pillStops += profile.root
        household.addView(profile.root, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, navBandH))
        gearStop = GlassKit.roundStop(context, app, R.drawable.ic_nav_settings, "Settings",
            theme.px(52f), callbacks.onSettings)
        pillStops += gearStop
        household.addView(gearStop, LinearLayout.LayoutParams(theme.px(52f), theme.px(52f)).apply {
            marginStart = theme.px(10f)
        })
        header.addView(household, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL })
        content.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, navBandH))

        searchStop.nextFocusRightId = profile.root.id
        profile.root.nextFocusLeftId = searchStop.id
        profile.root.nextFocusRightId = gearStop.id
        gearStop.nextFocusLeftId = profile.root.id

        // --- the two feature plates: 741 wide beside 1067 (measured asymmetry) ------------------
        val cardRowView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        cardRow = cardRowView
        val heroR = theme.radius("hero", 26f)
        // The halo needs a canvas grown by glowInset on every side — the padded glow-canvas
        // pattern every other screen uses (see BrowseActivity's rail holder). Attaching the halo
        // to the plate itself drew the keyline as a pale hairline INSIDE the art with a wrong
        // corner radius, and the plate clips to its rounded outline, so no part of the glow could
        // spill outside the banner (owner report, 2026-11-02).
        val glowPad = theme.focusGlowInset
        val (wrapA, ringA) = heroWrap(makePlate(deep = true, radius = heroR), glowPad)
        val (wrapB, ringB) = heroWrap(makePlate(deep = false, radius = heroR), glowPad)
        cardA = wrapA
        cardB = wrapB
        artA = cardA.findViewWithTag("art")
        artB = cardB.findViewWithTag("art")
        titleA = cardA.findViewWithTag("title")
        titleB = cardB.findViewWithTag("title")
        logoA = cardA.findViewWithTag("logo")
        logoB = cardB.findViewWithTag("logo")
        cardRow.addView(cardA, LinearLayout.LayoutParams(0, heroH, 7f))
        cardRow.addView(cardB, LinearLayout.LayoutParams(0, heroH, 10f).apply {
            marginStart = theme.space("heroGap", 20f)
        })
        content.addView(cardRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, heroH,
        ).apply { topMargin = theme.px(44f) })
        // Liquid glass speaks focus as a pale halo plus lift — never a hard keyline.
        Focus.attach(cardA, ringA, app, heroR, scale = true, elevate = true,
            glowInset = glowPad, onClick = { itemA?.let(callbacks.onOpen) })
        Focus.attach(cardB, ringB, app, heroR, scale = true, elevate = true,
            glowInset = glowPad, onClick = { itemB?.let(callbacks.onOpen) })
        armHeroHold(cardA, { itemA }, callbacks.onFavourite)
        armHeroHold(cardB, { itemB }, callbacks.onFavourite)

        // --- frost chip row: the household's real shelves ---------------------------------------
        chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        // A scroll view, not a fixed row: at the accessibility zoom the chip's text and padding
        // grow, and seven equal-width chips can no longer hold their labels on a 1920 canvas. At
        // 100% the measured equal width still fills the row exactly; when it does not fit, the
        // row scrolls and focus follows the chosen chip instead of truncating every caption.
        chipScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            isFocusable = false
            isFocusableInTouchMode = false
            clipChildren = false
            clipToPadding = false
            addView(chipRow, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, chipH))
        }
        content.addView(chipScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, chipH,
        ).apply { topMargin = theme.px(64f) })

        cardA.nextFocusUpId = pillStops.first().id
        cardB.nextFocusUpId = pillStops.first().id

        // Real liquid glass on the chrome (ported from QWEA0/Liquid-Glass-Android, MIT): after the
        // first layout each surface snapshots what stands behind it, blurs it and refracts its
        // edges — instead of wearing a flat wash that merely claims to be translucent.
        post {
            frostInto(pill, navBandH / 2)
            frostInto(profile.root, navBandH / 2)
            frostInto(gearStop, theme.px(52f) / 2)
        }
    }

    /**
     * The host clamps the hero slot so one shelf stays on screen; at 150% zoom that slot is shorter
     * than the scaled stage and the fixed plate band used to draw over the first shelf heading.
     * Give the shortfall to the two plates (they crop their art) and leave the header and frosted
     * chip row at the zoomed size. At 100% and 115% the slot still holds the full stage, so the
     * measured layout is unchanged.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.EXACTLY) {
            val available = View.MeasureSpec.getSize(heightMeasureSpec)
            val deficit = (preferredHeightPx - available).coerceAtLeast(0)
            val target = (heroH - deficit).coerceAtLeast(theme.space("heroMin", 240f))
            if (target != appliedHeroH) {
                appliedHeroH = target
                (cardRow.layoutParams as? LinearLayout.LayoutParams)?.height = target
                (cardA.layoutParams as? LinearLayout.LayoutParams)?.height = target
                (cardB.layoutParams as? LinearLayout.LayoutParams)?.height = target
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Wrap the host's current fallback background under a sampled frost layer. */
    private fun frostInto(host: View, radiusPx: Int) {
        LiquidFrost.install(host, app, radiusPx, host.background)?.let { frostSurfaces += it }
    }

    /**
     * A plate composed the way the reference crop measures it: ONE continuous artwork filling
     * the whole card (centerCrop — never an image panel beside a color panel), the tint wash
     * painted OVER the art with a left-to-right fade clear at ~62% width, and the copy column
     * floated on top of the art, title top-left and Play pinned bottom-left over the scrim.
     */
    private fun makePlate(deep: Boolean, radius: Int): FrameLayout {
        val card = GlassKit.plate(context, app, deep, radius, heroH)
        // Continuous artwork: the full card, so the surface reads as one composition.
        val art = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            tag = "art"
        }
        card.addView(art, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // The measured overlay: near-opaque tint at the left edge fading to clear at
        // heroFadePct of the width, plus the bottom scrim under the Play row. The reference's
        // two plates carry FIXED tints — deep steel blue at slot A, light periwinkle at slot B —
        // regardless of the artwork underneath (measured hero composites: (45,98,150) and
        // (137,164,206)); an art-sampled wash was my own substitution and read as brown/olive
        // over warm posters.
        val overlay = GlassKit.heroOverlay(context, app,
            theme.color(if (deep) "heroWashDeep" else "heroWashLight",
                if (deep) "rgba(34,90,146,0.88)" else "rgba(126,156,203,0.80)"))
        overlay.tag = "overlay"
        card.addView(overlay, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Copy floats over the faded half: title top-left, Play pinned bottom-left.
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.px(50f), theme.px(40f), theme.px(12f), theme.px(42f))
        }
        val title = Views.text(app, "h2", 52f, theme.color("inkOnCard", "#FFFFFF"), "", bold = true,
            maxLines = 3)
        title.tag = "title"
        textCol.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        textCol.addView(View(context), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        textCol.addView(GlassKit.playRow(context, app), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(textCol, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // The reference's hero plates carry the title's OWN logo lockup painted over the art (the
        // "Si DoL" plate measures a stylized wordmark riding top-right, over the cleared half where
        // the characters show). The logo replaces the typed title when it exists; the text stays as
        // the fallback (owner ask, 2026-11-02).
        val logo = ImageView(context).apply {
            tag = "logo"
            scaleType = ImageView.ScaleType.FIT_END
            adjustViewBounds = true
            maxHeight = theme.px(150f)
            visibility = View.GONE
        }
        card.addView(logo, LayoutParams(theme.px(430f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = theme.px(34f)
            marginEnd = theme.px(40f)
        })
        return card
    }

    /**
     * A hero plate inside its focus canvas: a transparent focusable frame holding the glow
     * canvas (the plate's bounds grown by glowPad on every side, as a plain first child with
     * negative margins) UNDER the plate itself. Nothing here clips, so the cast shadow and halo
     * reach into the gutter around the banner.
     */
    private fun heroWrap(plate: View, glowPad: Int): Pair<FrameLayout, View> {
        val wrap = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        val ringOn = View(context)
        wrap.addView(ringOn, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { setMargins(-glowPad, -glowPad, -glowPad, -glowPad) })
        wrap.addView(plate, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return wrap to ringOn
    }

    /** The shelf titles the host actually rendered below; each chip jumps to its shelf. */
    fun setChips(shelves: List<Pair<String, String>>) {
        val ids = shelves.map { it.first }
        if (ids == chipIds) return
        chipIds = ids
        chipRow.removeAllViews()
        val shelvesTake = shelves.take(7)
        // Measured: the chips are one equal-width row spanning the gutter, glyph+label left set.
        val gap = theme.space("chipGap", 34f)
        // The measured equal width is a floor, not a ceiling: a chip never shrinks below the pack's
        // own 228 canvas px, so at 130/150% the row overflows into the scroll view rather than
        // cutting the label. At 100% both values are the same and the row still meets the gutter.
        val equalW = if (shelvesTake.isEmpty()) 0 else
            (resources.displayMetrics.widthPixels - theme.gutter * 2 -
                gap * (shelvesTake.size - 1)) / shelvesTake.size
        val chipW = maxOf(equalW, theme.px(228f))
        shelvesTake.forEachIndexed { index, (id, label) ->
            val chip = GlassKit.chip(context, app, chipLabel(label),
                index == 0, chipH) { callbacks.onShelf(id) }
            val lp = LinearLayout.LayoutParams(chipW, chipH)
            if (chipRow.childCount > 0) lp.marginStart = gap
            chipRow.addView(chip, lp)
            // No frost here: the reference category chips are FLAT translucent panels on the
            // wall (measured fill barely above the local wall tone with a faint rim). Sampling
            // the bright hero behind them milky-white'd the chips — glass modernization is a
            // separate ask from reference fidelity (reference-reconstruction skill §7).
        }
        cardA.nextFocusDownId = chipRow.getChildAt(0)?.id ?: View.NO_ID
        cardB.nextFocusDownId = chipRow.getChildAt(0)?.id ?: View.NO_ID
    }

    /** Theme content rule (deviation D6): the frost chips are equal-width ~228px and the hub's
     * conversational shelf names ("Top 10 in this house") truncate mid-word at ~228px. The chip
     * keeps its leading promise and drops the conversational tail; it still names the shelf it
     * jumps to — content adaptation, not a layout excuse (deviation log D6). */
    private fun chipLabel(raw: String): String {
        var s = raw.trim().removePrefix("New on ")
        if (s.contains("continue", true)) s = "Continue"
        if (s.contains("because you", true)) s = "Recommended"
        if (s.contains("more like", true)) s = "More like"
        if (s.contains("acclaim", true)) s = "Acclaimed"
        s = s.replace(Regex("(?i)^(\\S+)\\s(movies|classics|cinema|films|shows|collection|specials)\\b.*"), "$1")
        s = s.replace(Regex("(?i)\\s+(in|on|to|by|from|for|of|that|with|you)\\s.*"), "")
        if (s.length > 14) s = s.take(13).trimEnd() + "…"
        return s
    }

    /** The header pill prints the signed-in profile's name beside its face. */
    fun setProfile(name: String, avatarUrl: String?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        profile.initials.text = trimmed.take(1).uppercase()
        profile.name.text = trimmed.take(18)
        if (!avatarUrl.isNullOrBlank()) {
            BrowseImages.load(profile.face, avatarUrl, theme.px(48f)) { ok ->
                if (ok) profile.initials.visibility = View.GONE
            }
        }
    }

    fun render(slides: List<Item>, activeIndex: Int) {
        items = slides
        val idx = activeIndex.coerceIn(0, (slides.size - 1).coerceAtLeast(0))
        val a = slides.getOrNull(idx)
        // The reference shows two DIFFERENT titles side by side; one slide hides the second plate
        // rather than duplicating the first.
        val b = if (slides.size >= 2) slides[(idx + 1) % slides.size] else null
        itemA = a
        itemB = b
        bindCard(cardA, artA, titleA, logoA, a)
        bindCard(cardB, artB, titleB, logoB, b)
        cardB.visibility = if (b == null) View.GONE else View.VISIBLE
        // The hero band grew taller behind the pill and plates with this slide — re-sample what
        // the chrome is really standing over.
        frostSurfaces.forEach { it.refresh() }
    }

    private fun bindCard(card: FrameLayout, art: ImageView, title: TextView, logo: ImageView, item: Item?) {
        card.contentDescription = item?.let { "${it.title}. Open details" } ?: "Featured title"
        title.text = item?.title ?: ""
        title.visibility = View.VISIBLE
        // A recycled plate must never flash the previous title's logo while the new one loads.
        logo.visibility = View.GONE
        val url = item?.logo?.trim().orEmpty()
        if (url.isNotEmpty()) {
            BrowseImages.load(logo, url, theme.px(430f)) { ok ->
                // BrowseImages only calls back when this view still belongs to THIS request,
                // so a late answer cannot hide a rebound plate's title.
                if (ok) {
                    logo.visibility = View.VISIBLE
                    title.visibility = View.GONE
                } else {
                    logo.visibility = View.GONE
                }
            }
        }
        BrowseImages.load(art, item?.backdrop ?: item?.poster, theme.px(1000f)) { }
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
