package com.scenix.tv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Item
import com.scenix.tv.core.Views

/**
 * Native, pack-driven home billboard.
 *
 * Browse owns the remote routes and the shared ambient stage.  This view owns only billboard
 * rendering: its current [Item], its preview dots and its Play/focus callbacks.  The component
 * intentionally has no branch on a theme id; a pack selects its structural family through
 * [com.scenix.tv.core.Theme.heroLayout].  That leaves future Netflix, PS5, Roku, Clean and
 * Magnifier packs free to describe a full, split, centred, art-free or larger-copy hero without
 * growing another set of BrowseActivity conditionals.
 */
internal class HeroComponent(
    context: Context,
    private val app: AppContext,
    private val callbacks: Callbacks,
) : FrameLayout(context) {

    data class Callbacks(
        /** A press on the actual Play control. */
        val onPlay: (Item) -> Unit,
        /** A preview tile is a carousel selector, never a second detail action. */
        val onSlideSelected: (Int) -> Unit,
        /** Lets Browse pause rotation and restore its stage when Play becomes the remote stop. */
        val onPlayFocused: () -> Unit,
    )

    private val theme = app.theme
    private val spec = theme.heroLayout
    private var current: Item? = null
    private var renderGeneration = 0L

    /** Browse reads this when it needs a stable remote boundary around the complete billboard. */
    val root: View get() = this

    /** The one D-pad stop inside the billboard. */
    val playControl: TextView

    /**
     * The slide selector is intentionally part of the billboard, rather than a second Featured
     * shelf below it. Browse uses this boundary to make UP/DOWN deterministic without reaching
     * into this component's child hierarchy.
     */
    val selectorRoot: View get() = selectorScroll

    /** Preview plumbing remains public to Browse without coupling it to the hero's child tree. */
    val videoView: HeroCoverVideoView
    val subtitleView: TextView

    private val artwork: ImageView
    private val poster: ImageView
    private val title: TextView
    private val logo: ImageView
    private val meta: TextView
    private val dots: LinearLayout
    private val selectorScroll: HorizontalScrollView
    private val selectorTrack: LinearLayout
    private var selectorIds: List<String> = emptyList()
    private val selectorTiles = ArrayList<SelectorTile>()

    private data class SelectorTile(
        val root: FrameLayout,
        val active: View,
    )

    /** The vertical height selected by the active pack, already converted to device pixels. */
    val preferredHeightPx: Int get() = spec.heightPx

    init {
        setBackgroundColor(if (spec.showArtwork) Color.TRANSPARENT else theme.bg)

        artwork = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(theme.surface2)
            visibility = if (spec.showArtwork) View.VISIBLE else View.GONE
        }
        addView(artwork, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        // The muted, looping preview is visual media, never a remote destination.  Browse starts
        // and stops it; this component only gives it a cover-sized surface in the right layer.
        videoView = HeroCoverVideoView(context).apply {
            visibility = View.GONE
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = false
            isClickable = false
        }
        addView(videoView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.CENTER })

        val scrim = View(context).apply {
            background = if (spec.split) {
                GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(theme.bg, Color.TRANSPARENT),
                )
            } else {
                GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(theme.bg, Color.TRANSPARENT),
                )
            }
            visibility = if (spec.showArtwork) View.VISIBLE else View.GONE
        }
        addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        subtitleView = Views.text(
            app, "sub", 21f, Color.WHITE, "", bold = false, maxLines = 2, gravity = Gravity.CENTER,
        ).apply {
            setPadding(theme.px(12f), theme.px(5f), theme.px(12f), theme.px(5f))
            background = Views.rounded(Color.argb(165, 0, 0, 0), theme.px(6f))
            visibility = View.GONE
        }
        addView(subtitleView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER
            bottomMargin = theme.px(118f)
            marginStart = theme.px(160f)
            marginEnd = theme.px(160f)
        })

        poster = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(theme.surface2)
            visibility = if (spec.showPoster) View.VISIBLE else View.GONE
        }
        if (spec.showPoster) {
            addView(poster, FrameLayout.LayoutParams(spec.posterWidthPx, spec.posterHeightPx).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                leftMargin = theme.gutter
            })
        }

        // The preview tiles are the hero's visual pagination. Reserve their quiet bottom band in
        // the billboard itself so a Featured shelf cannot read as a second, unrelated content row.
        val selectorHeight = theme.px(132f)
        val selectorBottom = theme.px(18f)
        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = when {
                spec.centered -> Gravity.CENTER
                spec.split -> Gravity.CENTER_VERTICAL or Gravity.START
                else -> Gravity.BOTTOM or Gravity.START
            }
            setPadding(
                if (spec.showPoster) theme.gutter + spec.posterWidthPx + theme.px(40f) else theme.gutter,
                theme.px(32f),
                theme.gutter,
                theme.px(44f) + selectorHeight + selectorBottom,
            )
        }
        title = Views.text(
            app,
            "h1",
            54f,
            theme.ink,
            "",
            bold = true,
            gravity = if (spec.centered) Gravity.CENTER else Gravity.START,
            maxLines = spec.titleLines,
        ).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.type("h1", 54f) * spec.titleScale)
        }
        logo = ImageView(context).apply {
            scaleType = if (spec.centered) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.FIT_START
            visibility = View.GONE
        }
        meta = Views.text(
            app,
            "sub",
            22f,
            theme.ink2,
            "",
            gravity = if (spec.centered) Gravity.CENTER else Gravity.START,
        )
        playControl = Views.text(
            app,
            "button",
            22f,
            theme.onBrand,
            "Play",
            bold = true,
            gravity = Gravity.CENTER,
        ).apply {
            setPadding(theme.px(32f), theme.px(14f), theme.px(32f), theme.px(14f))
            isFocusable = true
            isClickable = true
            contentDescription = "Play"
        }
        if (playControl.id == View.NO_ID) playControl.id = View.generateViewId()
        val playIdle = Views.rounded(theme.brand, theme.radius("button", 6f))
        playControl.background = playIdle
        Focus.attach(
            playControl,
            playControl,
            app,
            theme.radius("button", 6f),
            scale = false,
            elevate = false,
            onFocus = { focused ->
                // A light primary button needs a dark enough keyline to remain visible.  This is a
                // contrast decision based on the semantic brand token, not a special Apple branch.
                playControl.background = if (focused) {
                    val outline = if (Color.luminance(theme.brand) > 0.55f) {
                        Color.argb(235, 35, 36, 42)
                    } else {
                        theme.focusRingColor
                    }
                    Views.rounded(theme.brand, theme.radius("button", 6f), theme.px(3f), outline)
                } else {
                    playIdle
                }
                if (focused) callbacks.onPlayFocused()
            },
        )
        words.addView(logo, LinearLayout.LayoutParams(theme.px(560f), theme.px(130f)).apply {
            bottomMargin = theme.px(6f)
            if (spec.centered) gravity = Gravity.CENTER_HORIZONTAL
        })
        words.addView(title, LinearLayout.LayoutParams(
            if (spec.centered) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        words.addView(meta, LinearLayout.LayoutParams(
            if (spec.centered) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = theme.px(10f) })
        words.addView(playControl, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = theme.px(22f) })
        addView(words, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        selectorTrack = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(theme.px(24f), theme.px(18f), theme.px(24f), theme.px(18f))
        }
        selectorScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            isFocusable = false
            isFocusableInTouchMode = false
            clipChildren = false
            clipToPadding = false
            addView(selectorTrack, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            visibility = View.GONE
        }
        addView(selectorScroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, selectorHeight,
        ).apply {
            gravity = Gravity.BOTTOM
            leftMargin = theme.gutter - theme.px(24f)
            rightMargin = theme.gutter - theme.px(24f)
            bottomMargin = selectorBottom
        })

        dots = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        addView(dots, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = selectorHeight + selectorBottom + theme.px(20f)
        })

        playControl.setOnClickListener { current?.let(callbacks.onPlay) }
    }

    /**
     * Renders a new slide without changing Browse navigation state.  [slides] supplies the same
     * ordering used by the Featured rail, so its active marker and the billboard dots cannot drift.
     */
    fun render(item: Item, slides: List<Item>, activeIndex: Int) {
        current = item
        val generation = ++renderGeneration
        title.text = item.title
        title.visibility = View.VISIBLE
        logo.visibility = View.GONE
        logo.setImageDrawable(null)
        playControl.contentDescription = "Play ${item.title}"

        val facts = ArrayList<String>()
        if (item.match > 0) facts.add("${item.match}% match")
        if (item.isNew) facts.add("New")
        if (item.year > 0) facts.add(item.year.toString())
        if (item.rating > 0) facts.add(String.format(java.util.Locale.US, "★ %.1f", item.rating))
        if (item.maturity.isNotBlank()) facts.add(item.maturity)
        item.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let { facts.add(it) }
        meta.text = facts.joinToString(" · ")

        if (spec.showArtwork) {
            // The billboard always prefers wide art; portrait art is only the resilient fallback.
            BrowseImages.load(artwork, item.backdrop ?: item.poster, theme.px(1100f))
        } else {
            artwork.setImageDrawable(null)
        }
        if (spec.showPoster) {
            BrowseImages.load(poster, item.poster ?: item.backdrop, theme.px(420f))
        }
        if (!item.logo.isNullOrBlank()) {
            logo.visibility = View.VISIBLE
            BrowseImages.load(logo, item.logo, theme.px(560f)) { ok ->
                // An image callback from an earlier carousel slide must never hide the new title.
                if (generation != renderGeneration) return@load
                logo.visibility = if (ok) View.VISIBLE else View.GONE
                title.visibility = if (ok) View.GONE else View.VISIBLE
            }
        }
        syncSelectors(slides, activeIndex)
        paintDots(slides, activeIndex)
    }

    /** Keep the component's sole focus stop connected to Browse's floating navigation pill. */
    fun setNextFocusUp(id: Int) {
        nextFocusUpId = id
        playControl.nextFocusUpId = id
    }

    /** The thumbnail at [index] is the equivalent of a carousel bullet, just much more useful. */
    fun selectorAt(index: Int): View? = selectorTiles.getOrNull(index)?.root

    /** Focus and reveal one slide selector without moving the outer RecyclerView. */
    fun focusSelector(index: Int, tries: Int = 0) {
        val tile = selectorAt(index) ?: return
        selectorScroll.scrollTo((tile.left - theme.px(24f)).coerceAtLeast(0), 0)
        if (tile.requestFocus()) return
        if (tries < 8) selectorScroll.postDelayed({ focusSelector(index, tries + 1) }, 32L)
    }

    private fun syncSelectors(slides: List<Item>, activeIndex: Int) {
        if (slides.size < 2) {
            selectorScroll.visibility = View.GONE
            selectorTrack.removeAllViews()
            selectorTiles.clear()
            selectorIds = emptyList()
            return
        }
        selectorScroll.visibility = View.VISIBLE
        val ids = slides.map { it.identity.ifBlank { it.title } }
        if (selectorIds != ids) {
            selectorIds = ids
            selectorTrack.removeAllViews()
            selectorTiles.clear()
            slides.forEachIndexed { index, item ->
                val tile = makeSelectorTile(index, item)
                selectorTiles += tile
                selectorTrack.addView(tile.root)
            }
        }
        selectorTiles.forEachIndexed { index, tile ->
            tile.active.visibility = if (index == activeIndex) View.VISIBLE else View.INVISIBLE
        }
    }

    /**
     * A small landscape lockup makes the relationship between preview and billboard obvious at a
     * glance. It has no title/caption because that copy already lives in the hero; duplicating it
     * is what made the old Featured rail look like a second shelf.
     */
    private fun makeSelectorTile(index: Int, item: Item): SelectorTile {
        val width = theme.px(232f)
        val height = theme.px(96f)
        val radius = theme.radius("card", 6f)
        val haloPad = theme.focusGlowInsetSm
        val root = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            clipChildren = false
            clipToPadding = false
            contentDescription = "Slide ${index + 1}: ${item.title}. Select hero slide"
            nextFocusUpId = playControl.id
            layoutParams = LinearLayout.LayoutParams(width, height).apply {
                marginEnd = theme.px(14f)
            }
        }
        val halo = View(context)
        root.addView(halo, FrameLayout.LayoutParams(
            width + haloPad * 2, height + haloPad * 2,
        ).apply {
            leftMargin = -haloPad
            topMargin = -haloPad
        })
        val art = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(theme.surface2)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radius.toFloat())
                }
            }
        }
        root.addView(art, FrameLayout.LayoutParams(width, height))
        // The slide's own title stays hidden while art paints; it only appears if both load
        // attempts fail, so the tile names its slide instead of being a dark hole under the remote.
        val caption = Views.text(app, "sub", 17f, theme.ink, item.title, bold = true,
            gravity = Gravity.BOTTOM or Gravity.START, maxLines = 1).apply {
            visibility = View.GONE
            setPadding(theme.px(12f), 0, theme.px(12f), theme.px(16f))
        }
        // A dead image edge is transient on these sources; an empty square that never fills reads
        // as a broken carousel. Retry once, and if the art still refuses, name the slide instead of
        // leaving a dark hole under the remote.
        BrowseImages.load(art, item.backdrop ?: item.poster, width) { ok ->
            if (!ok) art.postDelayed({
                BrowseImages.load(art, item.backdrop ?: item.poster, width) { settled ->
                    if (!settled && art.drawable == null) caption.visibility = View.VISIBLE
                }
            }, 1500L)
        }
        val shade = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(105, 0, 0, 0), Color.TRANSPARENT),
            )
        }
        root.addView(shade, FrameLayout.LayoutParams(width, theme.px(42f)).apply {
            gravity = Gravity.BOTTOM
        })
        root.addView(caption, FrameLayout.LayoutParams(width, height))
        val active = View(context).apply {
            background = Views.rounded(theme.ink, theme.px(999f))
            visibility = View.INVISIBLE
        }
        root.addView(active, FrameLayout.LayoutParams(width - theme.px(36f), theme.px(4f)).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = theme.px(7f)
        })
        Focus.attach(
            root,
            halo,
            app,
            radius,
            scale = false,
            elevate = false,
            glowInset = haloPad,
            onClick = { callbacks.onSlideSelected(index) },
            onFocus = { focused -> if (focused) callbacks.onSlideSelected(index) },
            arrow = { code ->
                when (code) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT ->
                        if (index == 0) { focusSelector(selectorTiles.lastIndex); true } else false
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                        if (index == selectorTiles.lastIndex) { focusSelector(0); true } else false
                    else -> false
                }
            },
        )
        return SelectorTile(root, active)
    }

    private fun paintDots(slides: List<Item>, activeIndex: Int) {
        // Thumbnail previews are the carousel's only visible pagination. Keeping ordinary dots
        // alongside them gave the viewer two contradictory representations of one slider.
        val visible = spec.showDots && slides.size > 1 && selectorScroll.visibility != View.VISIBLE
        dots.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        if (dots.childCount != slides.size) {
            dots.removeAllViews()
            repeat(slides.size) {
                dots.addView(View(context), LinearLayout.LayoutParams(0, theme.px(10f)).apply {
                    leftMargin = theme.px(4f)
                    rightMargin = theme.px(4f)
                })
            }
        }
        for (index in 0 until dots.childCount) {
            val dot = dots.getChildAt(index)
            val active = index == activeIndex
            val width = when (spec.dotsStyle) {
                "dot" -> theme.px(10f)
                "pill" -> theme.px(if (active) 30f else 14f)
                else -> theme.px(if (active) 26f else 10f)
            }
            (dot.layoutParams as LinearLayout.LayoutParams).width = width
            dot.requestLayout()
            dot.background = Views.rounded(
                if (active) theme.ink else Color.argb(90, Color.red(theme.ink), Color.green(theme.ink), Color.blue(theme.ink)),
                theme.px(5f),
            )
        }
    }
}

/** A VideoView that fills its billboard slot instead of letterboxing inside it. */
internal class HeroCoverVideoView(context: Context) : VideoView(context) {
    private var videoWidth = 0
    private var videoHeight = 0

    fun coverSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        if (width > 0 && height > 0) requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (videoWidth == 0 || videoHeight == 0) return
        // VideoView's own pass fits the clip inside the slot; derive cover from the original slot
        // specs instead so trailers retain the same billboard crop as their source artwork.
        val boxWidth = MeasureSpec.getSize(widthMeasureSpec)
        val boxHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (boxWidth <= 0 || boxHeight <= 0) return
        val scale = maxOf(boxWidth.toDouble() / videoWidth, boxHeight.toDouble() / videoHeight)
        setMeasuredDimension((videoWidth * scale).toInt(), (videoHeight * scale).toInt())
    }
}
