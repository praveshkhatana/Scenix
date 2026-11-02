package com.scenix.tv

import android.content.Context
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
        val onSection: (String) -> Unit,
        val onProfile: () -> Unit,
        val onSearch: () -> Unit,
        val onSettings: () -> Unit,
        /** A chip jumps to the real shelf it names rather than inventing a filtered route. */
        val onShelf: (String) -> Unit,
    )

    private val theme = app.theme
    private var items: List<Item> = emptyList()

    val root: View get() = this
    val primaryControl: View get() = cardA
    val navigationAnchor: View? get() = pillStops.firstOrNull()
    val preferredHeightPx: Int
        get() = theme.px(30f) + navBandH + theme.px(44f) + heroH + theme.px(64f) + chipH

    private val navBandH = theme.space("navBand", 68f)
    private val heroH = theme.space("heroHeight", 397f)
    private val chipH = theme.space("chipHeight", 104f)

    private val cardA: FrameLayout
    private val cardB: FrameLayout
    private val artA: ImageView
    private val artB: ImageView
    private val titleA: TextView
    private val titleB: TextView
    private var itemA: Item? = null
    private var itemB: Item? = null
    private val chipRow: LinearLayout
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
        header.addView(Views.text(app, "brand", 34f, theme.color("inkOnWall", "#FFFFFF"), "SCENIX",
            bold = true), LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })

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
        listOf("Home" to "", "Movies" to "movies", "Series" to "series", "Live" to "live")
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
        val cardRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val heroR = theme.radius("hero", 26f)
        cardA = makePlate(deep = true, radius = heroR)
        cardB = makePlate(deep = false, radius = heroR)
        artA = cardA.findViewWithTag("art")
        artB = cardB.findViewWithTag("art")
        titleA = cardA.findViewWithTag("title")
        titleB = cardB.findViewWithTag("title")
        cardRow.addView(cardA, LinearLayout.LayoutParams(0, heroH, 7f))
        cardRow.addView(cardB, LinearLayout.LayoutParams(0, heroH, 10f).apply {
            marginStart = theme.space("heroGap", 20f)
        })
        content.addView(cardRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, heroH,
        ).apply { topMargin = theme.px(44f) })
        // Liquid glass speaks focus as a pale halo plus lift — never a hard keyline.
        Focus.attach(cardA, cardA, app, heroR, scale = true, elevate = true,
            glowInset = theme.focusGlowInset, onClick = { itemA?.let(callbacks.onOpen) })
        Focus.attach(cardB, cardB, app, heroR, scale = true, elevate = true,
            glowInset = theme.focusGlowInset, onClick = { itemB?.let(callbacks.onOpen) })

        // --- frost chip row: the household's real shelves ---------------------------------------
        chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        content.addView(chipRow, LinearLayout.LayoutParams(
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
        // heroFadePct of the width, plus the bottom scrim under the Play row. The pack tint is
        // only the starting point — bindCard re-keys it to the title's own palette once the art
        // lands (real liquid glass samples the surface beneath it).
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
        return card
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
        val chipW = if (shelvesTake.isEmpty()) 0 else
            (resources.displayMetrics.widthPixels - theme.gutter * 2 -
                gap * (shelvesTake.size - 1)) / shelvesTake.size
        shelvesTake.forEachIndexed { index, (id, label) ->
            val chip = GlassKit.chip(context, app, chipLabel(label),
                index == 0, chipH) { callbacks.onShelf(id) }
            val lp = LinearLayout.LayoutParams(chipW, chipH)
            if (chipRow.childCount > 0) lp.marginStart = gap
            chipRow.addView(chip, lp)
            // The chips sit right where the hero band meets the wall — frost makes that real.
            frostInto(chip, chipH / 2)
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
        bindCard(cardA, artA, titleA, a)
        bindCard(cardB, artB, titleB, b)
        cardB.visibility = if (b == null) View.GONE else View.VISIBLE
        // The hero band grew taller behind the pill and plates with this slide — re-sample what
        // the chrome is really standing over.
        frostSurfaces.forEach { it.refresh() }
    }

    private fun bindCard(card: FrameLayout, art: ImageView, title: TextView, item: Item?) {
        card.contentDescription = item?.let { "${it.title}. Open details" } ?: "Featured title"
        title.text = item?.title ?: ""
        BrowseImages.load(art, item?.backdrop ?: item?.poster, theme.px(1000f)) { ok ->
            if (ok) applyTitleWash(card, art)
        }
    }

    /**
     * Liquid glass samples what's under it: the wash re-keys to the loaded artwork's own palette
     * (the average of the left region the wash covers, saturated so a grey frame still tints),
     * keeping the pack token's alpha for readability. Falls back to the pack wash when no art
     * loaded. (Open item from the third pass, closed 2026-11-02.)
     */
    private fun applyTitleWash(card: FrameLayout, art: ImageView) {
        val src = (art.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return
        val overlay = card.findViewWithTag<GlassKit.HeroOverlay>("overlay") ?: return
        val small = android.graphics.Bitmap.createScaledBitmap(src, 16, 9, true)
        var r = 0; var g = 0; var b = 0; var n = 0
        for (y in 0 until 9) for (x in 0 until 7) {
            val p = small.getPixel(x, y)
            r += android.graphics.Color.red(p); g += android.graphics.Color.green(p)
            b += android.graphics.Color.blue(p); n++
        }
        val lum = (r + g + b) / (3 * n)
        fun sat(v: Int): Int = (lum + (v - lum) * 1.6f).toInt().coerceIn(0, 255)
        // Keep the plate's own token alpha (deep vs light differ); swap only the hue source.
        val alpha = android.graphics.Color.alpha(overlay.currentWash())
        overlay.setWash(android.graphics.Color.argb(alpha, sat(r / n), sat(g / n), sat(b / n)))
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
