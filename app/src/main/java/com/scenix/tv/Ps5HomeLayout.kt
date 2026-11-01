package com.scenix.tv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Item
import com.scenix.tv.core.Views

/**
 * Console-style Home composition for packs which opt into `homeLayout: ps5`.
 *
 * This is deliberately not a variation of [HeroComponent].  PS5's visual grammar is a quiet
 * system/status line, a compact context strip, one centre-stage sequence of near-square content
 * tiles, and an information/action block driven by the focused tile.  The rail *is* the hero: it
 * owns the backdrop and the metadata, so it never renders a second banner or an Apple-style pill.
 *
 * The component is completely data- and callback-driven. Browse can therefore choose it through a
 * pack value without baking a `scenix-ps5` branch into navigation code.
 */
internal class Ps5HomeLayout(
    context: Context,
    private val app: AppContext,
    private val callbacks: Callbacks,
) : FrameLayout(context) {

    data class Callbacks(
        /** Focus changes the selected console tile and its ambient room. */
        val onItemFocused: (Int) -> Unit,
        /** A press on a real content tile (or Details) opens that title. */
        val onItemOpen: (Item) -> Unit,
        /** Tiny context strip actions are real routes, never decorative navbar labels. */
        val onSection: (String) -> Unit,
        val onProfile: () -> Unit,
        val onSearch: () -> Unit,
        val onSettings: () -> Unit,
    )

    private data class Tile(
        val root: FrameLayout,
        val activePlate: View,
        val item: Item,
    )

    private val theme = app.theme
    private val tiles = ArrayList<Tile>()
    private var tileIds: List<String> = emptyList()
    private var items: List<Item> = emptyList()
    private var selectedIndex = 0
    private var renderGeneration = 0L

    /** The outer browse adapter uses these boundaries for deterministic TV focus routing. */
    val root: View get() = this
    val tileStrip: View get() = tileScroll
    val detailsControl: View get() = details
    /** The first compact context filter is the Home-level return target, never a hidden host bar. */
    val navigationAnchor: View? get() = categoryStops.firstOrNull()
    val preferredHeightPx: Int get() = theme.px(theme.packInt("ps5HomeHeight", 780).toFloat())

    private val backdrop: ImageView
    private val rail: LinearLayout
    private val tileScroll: HorizontalScrollView
    private val details: TextView
    private val title: TextView
    private val meta: TextView
    private val eyebrow: TextView
    private val categoryStops = ArrayList<View>()
    private val utilityStops = ArrayList<View>()

    init {
        setBackgroundColor(theme.bg)
        clipChildren = false
        clipToPadding = false

        backdrop = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(theme.surface)
        }
        addView(backdrop, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        // The sampled backdrop needs depth but not a second card/surface. A left reading gradient
        // and a bottom fade keep the selected tile and its metadata legible at a glance.
        val atmosphere = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.argb(236, 2, 8, 20),
                    Color.argb(158, 2, 8, 20),
                    Color.argb(32, 2, 8, 20),
                ),
            )
        }
        addView(atmosphere, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        val lowerVeil = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(
                    Color.argb(238, 2, 8, 20),
                    Color.argb(110, 2, 8, 20),
                    Color.TRANSPARENT,
                ),
            )
        }
        addView(lowerVeil, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        addView(makeSystemStrip(), LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            theme.px(76f),
        ).apply {
            gravity = Gravity.TOP
            leftMargin = theme.gutter
            rightMargin = theme.gutter
            topMargin = theme.px(20f)
        })

        rail = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(theme.gutter, theme.px(34f), theme.gutter, theme.px(34f))
        }
        tileScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            isFocusable = false
            isFocusableInTouchMode = false
            clipChildren = false
            clipToPadding = false
            addView(rail, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        addView(tileScroll, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            theme.px(theme.packInt("ps5RailHeight", 300).toFloat()),
        ).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = theme.px(theme.packInt("ps5RailBottom", 196).toFloat())
        })

        val information = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(theme.gutter, 0, theme.gutter, theme.px(30f))
        }
        eyebrow = Views.text(app, "meta", 16f, theme.ink2, "SCENIX · HOME", bold = true)
        // Keep a two-line title in the dedicated lower information zone. The rail above it is
        // never allowed to become a background behind text, even for a long series name.
        title = Views.text(app, "h2", 36f, theme.ink, "", bold = true, maxLines = 2)
        meta = Views.text(app, "sub", 20f, theme.ink2, "", maxLines = 1)
        details = Views.text(app, "button", 18f, theme.onBrand, "Details", bold = true,
            gravity = Gravity.CENTER).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "Open selected title details"
            setPadding(theme.px(24f), theme.px(11f), theme.px(24f), theme.px(11f))
            background = Views.rounded(theme.brand, theme.radius("button", 999f))
        }
        Focus.attach(
            details,
            details,
            app,
            theme.radius("button", 999f),
            scale = false,
            elevate = false,
            onClick = { items.getOrNull(selectedIndex)?.let(callbacks.onItemOpen) },
        )
        information.addView(eyebrow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = theme.px(8f) })
        information.addView(title, LinearLayout.LayoutParams(
            theme.px(720f), ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        information.addView(meta, LinearLayout.LayoutParams(
            theme.px(740f), ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = theme.px(10f) })
        information.addView(details, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = theme.px(18f) })
        addView(information, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.BOTTOM })
    }

    /** Feed one shelf's items into the PS5 rail. The focused card is the sole active title. */
    fun render(newItems: List<Item>, activeIndex: Int) {
        items = newItems
        if (items.isEmpty()) {
            rail.removeAllViews()
            tiles.clear()
            tileIds = emptyList()
            title.text = ""
            meta.text = ""
            return
        }
        val ids = items.map { it.identity.ifBlank { it.title } }
        if (tileIds != ids) {
            tileIds = ids
            rail.removeAllViews()
            tiles.clear()
            items.forEachIndexed { index, item ->
                val tile = makeTile(index, item)
                tiles += tile
                rail.addView(tile.root)
            }
        }
        select(activeIndex.coerceIn(0, items.lastIndex), notify = false)
    }

    fun tileAt(index: Int): View? = tiles.getOrNull(index)?.root

    /** Selection lives on the card, not on a separate banner; reveal the focused card as needed. */
    fun focusTile(index: Int, tries: Int = 0) {
        val tile = tileAt(index) ?: return
        tileScroll.scrollTo((tile.left - theme.gutter).coerceAtLeast(0), 0)
        if (tile.requestFocus()) return
        if (tries < 8) tileScroll.postDelayed({ focusTile(index, tries + 1) }, 32L)
    }

    fun setNextFocusUp(id: Int) {
        tiles.forEach { it.root.nextFocusUpId = id }
        details.nextFocusUpId = tileAt(selectedIndex)?.id ?: id
    }

    private fun makeSystemStrip(): View {
        val strip = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // The owner's wordmark, not styled text: the PS5 strip is a brand placement like every
        // other, and its letterforms must not depend on the television's font.
        val brand = android.widget.ImageView(context).apply {
            setImageResource(R.drawable.scenix_wordmark)
            setColorFilter(theme.brand)
            scaleType = android.widget.ImageView.ScaleType.FIT_START
            contentDescription = "Scenix"
        }
        left.addView(brand, LinearLayout.LayoutParams(theme.px(122f), theme.px(28f)).apply {
            marginEnd = theme.px(24f)
        })
        val sections = listOf(
            // These are compact content filters, modelled on the reference's ALL/RACING/etc.
            // strip—not a conventional application navbar.
            "All" to "",
            "Movies" to "movies",
            "Series" to "series",
            "Games" to "games",
            "Live" to "live",
        )
        sections.forEachIndexed { index, (label, route) ->
            val stop = makeCategory(label, route, index == 0)
            categoryStops += stop
            left.addView(stop)
        }
        strip.addView(left, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })

        val utilities = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val profile = makeUtility("Profile", null, callbacks.onProfile)
        val search = makeUtility("Search", R.drawable.ic_nav_search, callbacks.onSearch)
        val settings = makeUtility("Settings", R.drawable.ic_nav_settings, callbacks.onSettings)
        utilityStops += profile
        utilityStops += search
        utilityStops += settings
        utilities.addView(profile)
        utilities.addView(search)
        utilities.addView(settings)
        strip.addView(utilities, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL })

        // The reference keeps context filters and system actions on one quiet top strip. They
        // remain visually separated, but the remote must be able to walk from Live to Profile
        // rather than getting trapped at the last filter.
        wireStops(categoryStops + utilityStops)
        return strip
    }

    private fun makeCategory(label: String, route: String, active: Boolean): View {
        val stop = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "$label section"
            setPadding(theme.px(12f), theme.px(8f), theme.px(12f), theme.px(8f))
            background = if (active) Views.rounded(
                Color.argb(64, Color.red(theme.brand), Color.green(theme.brand), Color.blue(theme.brand)),
                theme.radius("badge", 999f),
            ) else null
        }
        val labelView = Views.text(app, "meta", 15f, if (active) theme.ink else theme.ink2, label, bold = true,
            gravity = Gravity.CENTER)
        stop.addView(labelView, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER })
        Focus.attach(stop, stop, app, theme.radius("badge", 999f), scale = false, elevate = false,
            onClick = { callbacks.onSection(route) })
        return stop
    }

    private fun makeUtility(label: String, icon: Int?, action: () -> Unit): View {
        val stop = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = label
            setPadding(theme.px(12f), theme.px(7f), theme.px(12f), theme.px(7f))
        }
        val mark: View = if (icon == null) {
            // The household action is an avatar target; an initial is a resilient fallback until
            // Browse wires the real cached face into the component.
            Views.text(app, "meta", 15f, theme.ink, "P", bold = true, gravity = Gravity.CENTER).apply {
                background = Views.rounded(theme.color("surface3", "#142A4D"), theme.px(999f), theme.px(1f), theme.line)
            }
        } else {
            ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(theme.ink)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        }
        stop.addView(mark, LayoutParams(theme.px(34f), theme.px(34f)).apply { gravity = Gravity.CENTER })
        Focus.attach(stop, stop, app, theme.radius("badge", 999f), scale = false, elevate = false, onClick = action)
        return stop
    }

    private fun wireStops(stops: List<View>) {
        stops.forEachIndexed { index, stop ->
            stop.nextFocusLeftId = stops[(index - 1).coerceAtLeast(0)].id
            stop.nextFocusRightId = stops[(index + 1).coerceAtMost(stops.lastIndex)].id
        }
    }

    private fun makeTile(index: Int, item: Item): Tile {
        val width = theme.px(theme.packInt("ps5TileWidth", 174).toFloat())
        val height = theme.px(theme.packInt("ps5TileHeight", 226).toFloat())
        val outerPad = theme.px(18f)
        val radius = theme.radius("card", 10f)
        val root = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            clipChildren = false
            clipToPadding = false
            contentDescription = "${item.title}. Open details"
            layoutParams = LinearLayout.LayoutParams(width, height).apply {
                marginEnd = theme.px(18f)
            }
        }
        val halo = View(context)
        root.addView(halo, LayoutParams(width + outerPad * 2, height + outerPad * 2).apply {
            leftMargin = -outerPad
            topMargin = -outerPad
        })
        val art = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(theme.surface2)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, radius.toFloat())
                }
            }
        }
        root.addView(art, LayoutParams(width, height))
        // Console lockups are near-square media tiles, not portrait poster cards. Prefer the
        // cinematic backdrop so adjacent tiles read as one horizontal launch rail.
        BrowseImages.load(art, item.backdrop ?: item.poster, width)
        val fade = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(120, 0, 0, 0), Color.TRANSPARENT),
            )
        }
        root.addView(fade, LayoutParams(width, theme.px(62f)).apply { gravity = Gravity.BOTTOM })
        val activePlate = View(context).apply {
            visibility = View.INVISIBLE
            background = Views.ring(theme.focusGlowColor, radius, theme.px(3f))
        }
        root.addView(activePlate, LayoutParams(width, height))
        val tile = Tile(root, activePlate, item)
        Focus.attach(
            root,
            halo,
            app,
            radius,
            scale = false,
            elevate = false,
            glowInset = outerPad,
            onClick = { callbacks.onItemOpen(item) },
            onFocus = { focused -> if (focused) select(index) },
            arrow = { key ->
                when (key) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (index == 0 && tiles.isNotEmpty()) {
                        focusTile(tiles.lastIndex); true
                    } else false
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (index == tiles.lastIndex) {
                        focusTile(0); true
                    } else false
                    else -> false
                }
            },
        )
        return tile
    }

    private fun select(index: Int, notify: Boolean = true) {
        if (items.isEmpty()) return
        val clamped = index.coerceIn(0, items.lastIndex)
        val changed = clamped != selectedIndex
        selectedIndex = clamped
        val item = items[selectedIndex]
        val generation = ++renderGeneration
        BrowseImages.load(backdrop, item.backdrop ?: item.poster, theme.px(760f)) {
            // Ignore old background completions when a rapid D-pad sweep has already selected
            // another tile. BrowseImages also tags views, this guards our future presentation work.
            if (generation != renderGeneration) return@load
        }
        eyebrow.text = when {
            item.type.equals("series", true) -> "SCENIX · SERIES"
            item.isLive() -> "SCENIX · LIVE"
            else -> "SCENIX · MOVIES"
        }
        title.text = item.title
        val facts = ArrayList<String>()
        if (item.year > 0) facts += item.year.toString()
        if (item.maturity.isNotBlank()) facts += item.maturity
        item.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let { facts += it }
        if (item.rating > 0) facts += String.format(java.util.Locale.US, "★ %.1f", item.rating)
        meta.text = facts.joinToString(" · ")
        details.text = if (item.type.equals("series", true)) "Episodes" else "Details"
        details.contentDescription = "Open ${item.title} details"
        tiles.forEachIndexed { tileIndex, tile ->
            tile.activePlate.visibility = if (tileIndex == selectedIndex) View.VISIBLE else View.INVISIBLE
        }
        val selectedTileId = tileAt(selectedIndex)?.id ?: View.NO_ID
        details.nextFocusUpId = selectedTileId
        tiles.forEach { it.root.nextFocusDownId = details.id }
        (categoryStops + utilityStops).forEach { it.nextFocusDownId = selectedTileId }
        if (changed && notify) callbacks.onItemFocused(selectedIndex)
    }
}
