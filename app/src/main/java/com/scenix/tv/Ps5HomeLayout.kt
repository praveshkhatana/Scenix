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
 * This is deliberately not a variation of [HeroComponent].  The reference console's grammar, read
 * frame by frame from the owner's clip, is: one quiet status line, a separate compact uppercase
 * filter strip beneath it, full-bleed key art that crossfades as the selection moves, ONE bottom
 * rail of portrait key-art tiles bleeding to the screen edges with the focused tile simply larger,
 * a tiny platform micro-label under the rail, and an outlined action chip in the lower right.
 * There is no title block: the artwork carries the name.  The rail *is* the hero.
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
        /** The household's optional modes: a category for a mode nobody installed is a dead end. */
        val showsLive: Boolean = true,
        val showsGames: Boolean = true,
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

    /** The optional modes are doors to installed add-ons, so their categories exist only when they do. */
    private fun offered(route: String): Boolean = when (route) {
        "games" -> callbacks.showsGames
        "live" -> callbacks.showsLive
        else -> true
    }

    /** The outer browse adapter uses these boundaries for deterministic TV focus routing. */
    val root: View get() = this
    val tileStrip: View get() = tileScroll
    val detailsControl: View get() = details
    /** The first compact context filter is the Home-level return target, never a hidden host bar. */
    val navigationAnchor: View? get() = categoryStops.firstOrNull()
    val preferredHeightPx: Int get() = theme.px(theme.packInt("ps5HomeHeight", 780).toFloat())

    private val stageA: ImageView
    private val stageB: ImageView
    private var stageFront: ImageView
    private var stageBack: ImageView
    private val rail: LinearLayout
    private val tileScroll: HorizontalScrollView
    private val details: TextView
    private val categoryStops = ArrayList<View>()
    private val utilityStops = ArrayList<View>()

    init {
        setBackgroundColor(theme.bg)
        clipChildren = false
        clipToPadding = false

        // Two full-bleed layers so a selection can crossfade like the console's ambient room,
        // not snap to the next key art. Only alpha animates — transform/opacity motion law.
        fun stageLayer() = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(theme.surface)
        }
        stageA = stageLayer()
        stageB = stageLayer().apply { alpha = 0f }
        stageFront = stageA
        stageBack = stageB
        addView(stageA, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        addView(stageB, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        // The reference keeps the art visible almost everywhere and only vignettes the reading
        // edges. A left gradient that approaches opaque was flattening the whole stage to navy.
        val atmosphere = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.argb(176, 2, 8, 20),
                    Color.argb(96, 2, 8, 20),
                    Color.argb(16, 2, 8, 20),
                ),
            )
        }
        addView(atmosphere, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        val lowerVeil = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(
                    Color.argb(200, 2, 8, 20),
                    Color.argb(84, 2, 8, 20),
                    Color.TRANSPARENT,
                ),
            )
        }
        addView(lowerVeil, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
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

        // The reference's category strip is its own row beneath the status line: quiet uppercase
        // text with only the active filter boxed. It never shares a line with brand or utilities.
        val categories = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val sections = listOf(
            "All" to "",
            "Movies" to "movies",
            "Series" to "series",
            "Games" to "games",
            "Live" to "live",
        ).filter { (_, route) -> offered(route) }
        sections.forEachIndexed { index, (label, route) ->
            val stop = makeCategory(label, route, index == 0)
            categoryStops += stop
            categories.addView(stop)
        }
        addView(categories, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            theme.px(44f),
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = theme.gutter
            topMargin = theme.px(112f)
        })
        // Each top row walks within itself; the vertical step between the two lines is left to
        // natural focus search so "Live" never leaps sideways into the Profile avatar.
        wireStops(categoryStops)

        rail = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            // The reference rail bleeds nearly to the screen edges; a full 80 px gutter made the
            // tiles look like an inset shelf rather than a launch rail.
            setPadding(theme.px(56f), theme.px(30f), theme.px(56f), theme.px(30f))
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
            bottomMargin = theme.px(theme.packInt("ps5RailBottom", 150).toFloat())
        })

        // Under the rail the reference places only a tiny platform micro-label — the artwork on
        // the focused tile already names the title, so no headline block competes with it.
        val microLabel = Views.text(app, "meta", 14f, theme.ink3, "SCENIX", bold = true)
        addView(microLabel, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = theme.gutter
            bottomMargin = theme.px(44f)
        })

        // The console's lower-right action cluster: a compact outlined chip (the same idiom as its
        // START THE GAME box), not a filled brand pill. The small legend marks to its left speak
        // for the remote that is actually in hand.
        details = Views.text(app, "button", 17f, theme.ink, "Details", bold = true,
            gravity = Gravity.CENTER).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "Open selected title details"
            setPadding(theme.px(26f), theme.px(10f), theme.px(26f), theme.px(10f))
            background = Views.rounded(Color.TRANSPARENT, theme.radius("button", 999f),
                theme.px(2f), theme.color("focusRing", "#EAF7FF"))
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
        val cluster = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        cluster.addView(makeLegendMark("↔", "NAVIGATE"))
        cluster.addView(makeLegendMark("OK", "SELECT"))
        cluster.addView(details, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = theme.px(30f) })
        addView(cluster, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = theme.gutter
            bottomMargin = theme.px(40f)
        })
    }

    private fun makeLegendMark(glyph: String, label: String): View {
        val group = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = Views.text(app, "badge", 12f, theme.ink2, glyph, bold = true,
            gravity = Gravity.CENTER).apply {
            background = Views.rounded(Color.TRANSPARENT, theme.px(999f), theme.px(1f), theme.line)
        }
        group.addView(mark, LayoutParams(theme.px(30f), theme.px(30f)))
        group.addView(Views.text(app, "meta", 12f, theme.ink3, label.uppercase(), bold = true),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = theme.px(8f) })
        return group
    }

    /** Feed one shelf's items into the PS5 rail. The focused card is the sole active title. */
    fun render(newItems: List<Item>, activeIndex: Int) {
        items = newItems
        if (items.isEmpty()) {
            rail.removeAllViews()
            tiles.clear()
            tileIds = emptyList()
            details.text = ""
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
        tileScroll.scrollTo((tile.left - theme.px(56f)).coerceAtLeast(0), 0)
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
        // The owner's wordmark, not styled text: the console status line is a brand placement like
        // every other, and its letterforms must not depend on the television's font.
        val brand = android.widget.ImageView(context).apply {
            setImageResource(R.drawable.scenix_wordmark)
            setColorFilter(theme.brand)
            scaleType = android.widget.ImageView.ScaleType.FIT_START
            contentDescription = "Scenix"
        }
        left.addView(brand, LinearLayout.LayoutParams(theme.px(80f), theme.px(28f)).apply {
            marginEnd = theme.px(24f)
        })
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

        // The reference keeps system actions on one quiet top line. They remain visually separated
        // from the filter row below, but the remote must be able to walk between them.
        wireStops(utilityStops)
        return strip
    }

    private fun makeCategory(label: String, route: String, active: Boolean): View {
        val stop = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "$label section"
            setPadding(theme.px(14f), theme.px(8f), theme.px(14f), theme.px(8f))
            // The reference boxes only the ACTIVE filter, with a hairline outline — never a filled
            // chip, and never more than one box in the strip at a time.
            background = if (active) Views.rounded(Color.TRANSPARENT,
                theme.radius("badge", 999f), theme.px(2f), theme.brand) else null
        }
        val labelView = Views.text(app, "meta", 14f, if (active) theme.ink else theme.ink2,
            label.uppercase(), bold = true, gravity = Gravity.CENTER)
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
        // The reference rail is portrait key art with the title printed in the artwork itself —
        // the same thing our posters are. Landscapes belong on the stage, not the rail.
        val width = theme.px(theme.packInt("ps5TileWidth", 150).toFloat())
        val height = theme.px(theme.packInt("ps5TileHeight", 225).toFloat())
        val outerPad = theme.focusGlowInsetSm
        val radius = theme.radius("card", 8f)
        val root = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            clipChildren = false
            clipToPadding = false
            contentDescription = "${item.title}. Open details"
            layoutParams = LinearLayout.LayoutParams(width, height).apply {
                marginEnd = theme.px(16f)
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
        BrowseImages.load(art, item.poster ?: item.backdrop, width)
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
            // The focused console tile is simply LARGER than its neighbours — that scale is the
            // focus. The halo stays a whisper behind it rather than the primary signal.
            scale = true,
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
        setStage(item.backdrop ?: item.poster)
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

    /** Crossfade the ambient key art exactly as the console does when a tile takes focus. */
    private fun setStage(url: String?) {
        val generation = ++renderGeneration
        val target = stageBack
        val fadeMs = theme.token("hero", "rotateFadeMs", 260.0).toLong().coerceIn(80L, 600L)
        BrowseImages.load(target, url, theme.px(960f)) { ok ->
            if (!ok || generation != renderGeneration) return@load
            target.animate().cancel()
            stageFront.animate().cancel()
            if (!theme.motionEnabled) {
                stageFront.alpha = 0f
                target.alpha = 1f
            } else {
                stageFront.animate().alpha(0f).setDuration(fadeMs).start()
                target.animate().alpha(1f).setDuration(fadeMs).start()
            }
            val previous = stageFront
            stageFront = target
            stageBack = previous
        }
    }
}
