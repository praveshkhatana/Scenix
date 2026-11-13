package com.scenix.tv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.liquidglass.LiquidGlassView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Views

/**
 * The reusable parts of the Liquid Glass pack (design-contracts/glass.md).
 *
 * Home, Detail and every secondary page build their chrome from these builders rather than each
 * hand-rolling its own pill or plate, so the same component reads identically on every screen.
 * Builders consume ONLY theme tokens — no literals — so retuning the pack means editing one JSON.
 * Focus routing stays with the composition that places the part; the builders hand back the
 * focusable view.
 */
internal object GlassKit {

    /** A section word inside the header pill. Measured: no bright fill — the active word is
     * just brighter ink on a barely-there raised plate. */
    fun segment(
        context: Context,
        app: AppContext,
        label: String,
        active: Boolean,
        onClick: () -> Unit,
    ): View {
        val theme = app.theme
        val stop = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = label
            // segPad: the measured pill holds four words + the knob inside 425px — the words sit
            // close together, unlike the roomier three-stop first pass that only matched by luck.
            setPadding(theme.space("segPad", 24f), theme.px(10f),
                theme.space("segPad", 24f), theme.px(10f))
            background = if (active) Views.rounded(
                theme.color("pillActive", "rgba(255,255,255,0.08)"), theme.px(999f)) else null
        }
        stop.addView(Views.text(app, "nav", 19f,
            if (active) theme.color("segInk", "#FFFFFF") else theme.color("pillInk", "#B7C4D2"),
            label, bold = true, gravity = Gravity.CENTER), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER })
        Focus.attach(stop, stop, app, theme.px(999f), scale = false, elevate = false, onClick = onClick)
        return stop
    }

    /** The knob welded to the pill's end: a raised translucent circle, bright glyph (measured). */
    fun searchKnob(context: Context, app: AppContext, onClick: () -> Unit): View {
        val theme = app.theme
        val knob = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "Search"
            background = Views.rounded(theme.color("searchKnob", "rgba(255,255,255,0.13)"), theme.px(999f))
        }
        knob.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_nav_search)
            setColorFilter(theme.color("segInk", "#FFFFFF"))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(theme.px(14f), theme.px(14f), theme.px(14f), theme.px(14f))
        }, FrameLayout.LayoutParams(theme.px(50f), theme.px(50f)))
        Focus.attach(knob, knob, app, theme.px(999f), scale = false, elevate = false, onClick = onClick)
        return knob
    }

    class ProfilePill(val root: View, val initials: TextView,
                      val face: ImageView, val name: TextView)

    /** One household stop: avatar + name + chevron in a single frosted pill. */
    fun profilePill(context: Context, app: AppContext, onClick: () -> Unit): ProfilePill {
        val theme = app.theme
        val glass = LiquidGlassKit.isGlass(app)
        val pill = LinearLayout(context).apply {
            isFocusable = !glass
            isClickable = !glass
            contentDescription = "Profiles"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (!glass) background = Views.rounded(
                theme.color("profilePill", "rgba(8,16,28,0.80)"), theme.px(999f))
            setPadding(theme.px(6f), theme.px(6f), theme.px(18f), theme.px(6f))
        }
        val initials = Views.text(app, "meta", 17f, theme.ink, "?", bold = true,
            gravity = Gravity.CENTER).apply {
            background = Views.rounded(theme.color("inkOnWall", "#FFFFFF"), theme.px(999f))
        }
        val face = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setOval(0, 0, v.width, v.height)
                }
            }
        }
        pill.addView(initials, LinearLayout.LayoutParams(theme.px(48f), theme.px(48f)))
        pill.addView(face, LinearLayout.LayoutParams(theme.px(48f), theme.px(48f)))
        val name = Views.text(app, "nav", 19f, theme.color("inkOnWall", "#FFFFFF"), "", bold = true,
            maxLines = 1)
        pill.addView(name, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = theme.px(10f) })
        // A feather chevron vector, not the "⌄" text glyph: the character's shape and baseline
        // change with whatever font the television ships, and it read as a stray mark (owner ruling).
        pill.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_down)
            setColorFilter(theme.colorOr("wallInk", theme.ink), android.graphics.PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(theme.px(5f), theme.px(5f), theme.px(5f), theme.px(5f))
        }, LinearLayout.LayoutParams(theme.px(30f), theme.px(30f)).apply {
            marginStart = theme.px(8f)
        })
        // The lens is what the remote selects, so the id the D-pad graph points at and the halo both
        // live on the glass, not on the row of parts inside it.
        val surface = if (glass) LiquidGlassKit.wrap(app, pill, theme.px(999f)) else pill
        surface.id = View.generateViewId()
        Focus.attach(surface, surface, app, theme.px(999f), scale = false, elevate = false, onClick = onClick)
        return ProfilePill(surface, initials, face, name)
    }

    /**
     * The pack's answer to an empty or failed shelf. A bare line of text on the wall reads like a
     * bug report; the glass language is a small plate standing in the middle of the space — glyph,
     * what happened, why a retry may help, and a focusable Try again stop that re-runs the load.
     */
    fun emptyState(
        context: Context,
        app: AppContext,
        title: String,
        sub: String,
        showRetry: Boolean,
        onRetry: () -> Unit,
    ): FrameLayout {
        val theme = app.theme
        val radius = theme.radius("card", 18f)
        val card = FrameLayout(context).apply {
            background = LayerDrawable(arrayOf(
                GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(theme.color("seeAllGlassA", "rgba(232,240,248,0.16)"),
                        theme.color("seeAllGlassB", "rgba(196,210,224,0.08)")),
                ).apply { cornerRadius = radius.toFloat() },
                Views.ring(theme.color("glassRim", "rgba(255,255,255,0.38)"), radius, theme.px(1f)),
            ))
            elevation = theme.space("plateElevation", 16f) / 2f
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, radius.toFloat())
                }
            }
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(theme.px(70f), theme.px(46f), theme.px(70f), theme.px(46f))
        }
        body.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_empty_shelf)
            setColorFilter(theme.color("inkOnWall", "#FFFFFF"), android.graphics.PorterDuff.Mode.SRC_IN)
            alpha = 0.75f
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(theme.px(96f), theme.px(96f)))
        body.addView(Views.text(app, "h1", 32f, theme.colorOr("wallInk", theme.ink), title,
            bold = true, gravity = Gravity.CENTER, maxLines = 1),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = theme.px(18f) })
        body.addView(Views.text(app, "sub", 21f, theme.colorOr("wallInk2", theme.ink2), sub,
            gravity = Gravity.CENTER),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = theme.px(8f) })
        if (showRetry) {
            val retry = LinearLayout(context).apply {
                tag = "retry"
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                contentDescription = "Try again"
                background = Views.rounded(theme.color("discGlass", "rgba(255,255,255,0.16)"),
                    theme.px(999f), theme.px(1f), theme.color("glassRim", "rgba(255,255,255,0.38)"))
                setPadding(theme.px(26f), theme.px(10f), theme.px(26f), theme.px(10f))
            }
            retry.addView(Views.text(app, "nav", 20f, theme.colorOr("wallInk", theme.ink), "Try again",
                bold = true, gravity = Gravity.CENTER))
            Focus.attach(retry, retry, app, theme.px(999f), scale = false, elevate = false,
                onClick = onRetry)
            body.addView(retry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = theme.px(26f) })
        }
        card.addView(body, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER })
        return card
    }

    /** A small round stop for header chrome (the settings door). */
    fun roundStop(
        context: Context,
        app: AppContext,
        iconRes: Int,
        description: String,
        sizePx: Int,
        onClick: () -> Unit,
    ): View {
        val theme = app.theme
        val glass = LiquidGlassKit.isGlass(app)
        val stop = FrameLayout(context).apply {
            isFocusable = !glass
            isClickable = !glass
            contentDescription = description
            if (!glass) background = Views.rounded(
                theme.color("profilePill", "rgba(8,16,28,0.80)"), theme.px(999f))
        }
        stop.addView(ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding((sizePx * 0.25f).toInt(), (sizePx * 0.25f).toInt(),
                (sizePx * 0.25f).toInt(), (sizePx * 0.25f).toInt())
        }, FrameLayout.LayoutParams(sizePx, sizePx))
        val surface = if (glass) LiquidGlassKit.wrap(app, stop, theme.px(999f)) else stop
        surface.id = View.generateViewId()
        Focus.attach(surface, surface, app, theme.px(999f), scale = false, elevate = false, onClick = onClick)
        return surface
    }

    /**
     * A feature plate standing on the wall. Three SEPARATE material layers, in the order the
     * reference shows them:
     *  1. cast shadow OUTSIDE the surface — real elevation on the view (the parent chain must not
     *     clip), which is also what the focus lift rides on (Focus adds translationZ);
     *  2. the surface itself: the caller lays FULL-BLEED key art across the whole card and paints
     *     [heroOverlay]'s tint wash over it (measured: the reference's artwork is continuous; the
     *     tint is an overlay that fades clear, never a panel beside the image);
     *  3. inner light: hairline specular rim + top sheen — these read as glass edges, never as the
     *     depth cue. The tint fill below stays only as the art-not-loaded fallback.
     */
    fun plate(
        context: Context,
        app: AppContext,
        deep: Boolean,
        radiusPx: Int,
        heightPx: Int,
    ): FrameLayout {
        val theme = app.theme
        val fill = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            if (deep) intArrayOf(theme.color("cardTintA", "#2E6196"), theme.color("cardTintB", "#1F4E7E"))
            else intArrayOf(theme.color("cardGlassA", "#9FB6DC"), theme.color("cardGlassB", "#7F9CCB")),
        ).apply { cornerRadius = radiusPx.toFloat() }
        val card = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            clipChildren = false
            clipToPadding = false
            contentDescription = "Featured title. Open details"
            background = LayerDrawable(arrayOf(
                fill,
                Views.ring(theme.color("glassRim", "rgba(255,255,255,0.38)"), radiusPx, theme.px(1f)),
            ))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, radiusPx.toFloat())
                }
            }
            clipToOutline = true
            // Layer 1: the surface casts a real shadow onto the wall. Elevation (px) comes from
            // the pack; the system draws it outside the card bounds from the rounded outline.
            elevation = theme.space("plateElevation", 16f).toFloat()
        }
        // The sheen is the liquid-glass tell: translucent white fading over the plate's top band.
        card.addView(View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(theme.color("sheenTop", "rgba(255,255,255,0.16)"), Color.TRANSPARENT),
            ).apply { cornerRadius = radiusPx.toFloat() }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (heightPx * 0.45f).toInt()))
        return card
    }

    /**
     * The wash that turns full-bleed art into the reference's hero: the card's tint at the left
     * edge at measured alpha, linear to CLEAR at heroFadePct% of the width (so the subject on the
     * right stays unobstructed), plus a bottom scrim that carries the copy and Play row. Drawn
     * with exact stop positions — a plain 2-stop GradientDrawable would fade across the whole
     * width and wash the artwork out.
     */
    class HeroOverlay(context: Context, private val app: AppContext) : View(context) {
        private val paint = android.graphics.Paint()
        // These shaders are only geometry/theme dependent. Recreating them for every frame made
        // the glass hero allocate twice per draw while the artwork was otherwise completely still.
        // Keep the exact same stops and rebuild only when a size, wash, or relevant pack token moves.
        private var leftShader: android.graphics.LinearGradient? = null
        private var bottomShader: android.graphics.LinearGradient? = null
        private var shaderWidth = -1f
        private var shaderHeight = -1f
        private var shaderWash = 0
        private var shaderFade = Float.NaN
        private var shaderScrim = 0
        // The fixed pack tint for this plate slot (heroWashDeep / heroWashLight) — the reference
        // plates keep their tints whatever artwork loads beneath them.
        private var wash = 0
        fun setWash(color: Int) { if (color != wash) { wash = color; invalidate() } }
        fun currentWash(): Int = wash

        private fun ensureShaders(width: Float, height: Float, fade: Float, scrim: Int) {
            if (leftShader != null && bottomShader != null && shaderWidth == width &&
                shaderHeight == height && shaderWash == wash && shaderFade == fade &&
                shaderScrim == scrim
            ) return
            leftShader = android.graphics.LinearGradient(
                0f, 0f, width, 0f, intArrayOf(wash, Color.TRANSPARENT),
                floatArrayOf(0f, fade), android.graphics.Shader.TileMode.CLAMP,
            )
            bottomShader = android.graphics.LinearGradient(
                0f, 0f, 0f, height, intArrayOf(Color.TRANSPARENT, scrim),
                floatArrayOf(0.42f, 1f), android.graphics.Shader.TileMode.CLAMP,
            )
            shaderWidth = width
            shaderHeight = height
            shaderWash = wash
            shaderFade = fade
            shaderScrim = scrim
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w < 1f || h < 1f || wash == 0) return
            val theme = app.theme
            val fade = theme.packInt("heroFadePct", 62).coerceIn(25, 95) / 100f
            val scrim = theme.color("heroScrim", "rgba(6,16,30,0.50)")
            ensureShaders(w, h, fade, scrim)
            paint.shader = leftShader
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = bottomShader
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }

    fun heroOverlay(context: Context, app: AppContext, wash: Int): HeroOverlay =
        HeroOverlay(context, app).apply { setWash(wash) }

    /**
     * A small tile's glass plate (category/See-all tiles): the tile's own colour wash at
     * translucent alpha so the wall shows through, a hairline specular rim, and the inner top
     * sheen — the same three inner layers the hero plates use. The cast shadow comes from the
     * view's elevation, set by whoever places the tile.
     */
    fun tilePlate(app: AppContext, radiusPx: Int, washTop: Int, washBottom: Int): LayerDrawable {
        val theme = app.theme
        val wash = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(washTop, washBottom),
        ).apply { cornerRadius = radiusPx.toFloat() }
        return LayerDrawable(arrayOf(
            wash,
            Views.rounded(Color.TRANSPARENT, radiusPx, theme.px(1f),
                theme.color("glassRim", "rgba(255,255,255,0.38)")),
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(theme.color("sheenTop", "rgba(255,255,255,0.16)"), Color.TRANSPARENT),
            ).apply { cornerRadius = radiusPx.toFloat() },
        ))
    }

    /** A dark disc Play control welded to a bold word — one component, both hero plates and Detail. */
    fun playRow(context: Context, app: AppContext, label: String = "Play"): LinearLayout {
        val theme = app.theme
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Views.text(app, "meta", 20f, Color.WHITE, "\u25B6", bold = true,
                gravity = Gravity.CENTER).apply {
                background = Views.rounded(theme.color("playDisc", "rgba(6,20,42,0.88)"), theme.px(999f))
            }, LinearLayout.LayoutParams(theme.px(42f), theme.px(42f)))
            addView(Views.text(app, "sub", 22f, Color.WHITE, label, bold = true),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = theme.px(14f) })
        }
    }

    /** A frost chip: icon + label on translucent fill with a hairline rim. */
    fun chip(
        context: Context,
        app: AppContext,
        label: String,
        active: Boolean,
        heightPx: Int,
        onClick: () -> Unit,
    ): View {
        val theme = app.theme
        val radius = theme.radius("chip", 999f)
        val glass = LiquidGlassKit.isGlass(app)
        val chip = LinearLayout(context).apply {
            isFocusable = !glass
            isClickable = !glass
            orientation = LinearLayout.HORIZONTAL
            // Measured on the reference chip crop: the pictogram+label pair sits CENTERED in the
            // chip, not set off the left padding — the left-set version read cramped.
            gravity = Gravity.CENTER
            contentDescription = "Jump to $label"
            setPadding(theme.px(18f), 0, theme.px(18f), 0)
            // The lens IS the fill: a flat wash under it hides the bent backdrop and the chip is a
            // grey pill again. Only a pack without the lens wears the token fill.
            if (!glass) background = Views.rounded(
                theme.color(if (active) "chipFillActive" else "chipFill",
                    if (active) "rgba(255,255,255,0.22)" else "rgba(255,255,255,0.10)"),
                radius, theme.px(1f), theme.color("glassRim", "rgba(255,255,255,0.38)"),
            )
        }
        // Measured: reference glyphs are filled pictograms ~30px tall, far heavier than a text
        // dot; they are vector icons (deviation closed 2026-11-02: unicode text glyphs read as
        // debug markers at TV distance), sized by the chipGlyph pack token (canvas px).
        val glyphSize = theme.packInt("chipGlyph", 32)
        val glyphRes = when {
            label.contains("Continue", true) -> R.drawable.ic_chip_play
            label.contains("Trending", true) -> R.drawable.ic_chip_trend
            label.contains("New", true) || label.contains("Recommended", true) ->
                R.drawable.ic_chip_sparkle
            label.contains("Top", true) || label.contains("rated", true) ||
                label.contains("Acclaimed", true) -> R.drawable.ic_chip_star
            label.contains("More like", true) -> R.drawable.ic_chip_heart
            label.contains("My List", true) || label.contains("Just added", true) ->
                R.drawable.ic_chip_plus
            else -> R.drawable.ic_chip_globe
        }
        chip.addView(ImageView(context).apply {
            setImageResource(glyphRes)
            setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
        }, LinearLayout.LayoutParams(theme.px(glyphSize.toFloat()), theme.px(glyphSize.toFloat())))
        chip.addView(Views.text(app, "chip", 21f, Color.WHITE, label, bold = true, maxLines = 1),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = theme.px(14f) })
        // A chosen chip is the same glass, lit from inside: the fill sits INSIDE the lens, so the
        // chosen chip is brighter glass rather than a grey pill pasted over it.
        if (glass && active) chip.background =
            Views.rounded(theme.color("chipFillActive", "rgba(255,255,255,0.22)"), radius)
        // The lens carries the selection: the halo and any lift belong to the whole surface, and a
        // focus ring drawn on the content inside it would be clipped by the glass's own shape.
        val surface = if (glass) LiquidGlassKit.wrap(app, chip, radius) else chip
        surface.id = View.generateViewId()
        // Cast shadow separate from the rim, like every other Glass surface.
        surface.elevation = theme.space("chipElevation", 6f).toFloat()
        Focus.attach(surface, surface, app, radius, scale = false, elevate = false, onClick = onClick)
        return surface
    }

    /**
     * The focused tile's glass slab: a lens laid OVER a tile's artwork that refracts and rim-lights
     * that artwork while the card holds focus, so the selected poster reads as a slab of liquid glass
     * rather than a picture with a ring drawn around it.
     *
     * There is exactly ONE slab per screen ([host]), re-bound to whichever tile is focused. A lens of
     * its own per tile was the obvious design and the wrong one: the library allocates a backdrop
     * bitmap for every lens it builds, and a scrolling shelf builds and recycles dozens of holders,
     * which is how a 2 GB panel ended up with 150+ MB of native heap. One lens, one bitmap, one blur.
     *
     * The slab's material is the `focusGlass*` token family (each token falling back to the `frost*`
     * chrome tokens). `focusGlass: 0` turns it off; `focusGlassMaxH` (canvas px) keeps it off surfaces
     * too big to afford — on the CPU tier a lens costs a capture of the region under it plus an
     * effect pass over that region, measured on the Android 11 panel at ~1.5 ms for a chip's patch
     * and ~120 ms for a poster's, and far worse for the billboard's plate.
     */
    fun onGlassFocus(card: View, art: View, app: AppContext, radiusPx: Int) {
        if (!LiquidGlassKit.isGlass(app)) return
        if (app.theme.packInt("focusGlass", 1) == 0) return
        val previous = card.onFocusChangeListener
        card.onFocusChangeListener = View.OnFocusChangeListener { v, has ->
            previous?.onFocusChange(v, has)
            if (has) showGlassSlab(art, app, radiusPx)
            else hideGlassSlab(app)
        }
    }

    /** The page's content view — the one surface every tile, and the slab, stand in. */
    private fun pageOf(view: View): ViewGroup? {
        var ctx = view.context
        while (ctx is android.content.ContextWrapper && ctx !is android.app.Activity)
            ctx = ctx.baseContext
        return (ctx as? android.app.Activity)?.findViewById(android.R.id.content)
    }

    /**
     * The screen's one slab, standing in that screen's content view above every tile. Keyed by the
     * page because each screen hosts its own: a slab borrowed by Home must not follow the viewer
     * into Detail.
     */
    private val slabs = java.util.WeakHashMap<ViewGroup, LiquidGlassView>()

    private fun showGlassSlab(art: View, app: AppContext, radiusPx: Int) {
        val theme = app.theme
        if (art.width <= 0 || art.height <= 0) return
        val inset = theme.packInt("focusGlassInset", 0)
        val h = art.height - inset * 2
        // A lens costs a capture of the region it covers plus an effect pass over it, and on the
        // Android 11 panel that was measured at ~120 ms for a poster and far more for the billboard's
        // plate. So a surface taller than `focusGlassMaxH` (canvas px) keeps the pack's halo and
        // gets no slab.
        if (h > theme.px(theme.packInt("focusGlassMaxH", 420).toFloat())) {
            hideGlassSlab(app)
            return
        }
        val page = pageOf(art) ?: return
        val glass = slabs[page] ?: LiquidGlassKit.viewLens(page, app, radiusPx, "focusGlass",
            behind = false).apply {
            visibility = View.INVISIBLE
            alpha = 0f
            // No elevation: the slab is the last child of the page's own content view, so a page
            // paints it above every tile and every chrome surface already, and giving a 425x596
            // software-drawn lens a hardware layer on this panel costs more than the glass does.
            elevation = 0f
            page.addView(this, ViewGroup.LayoutParams(0, 0))
            slabs[page] = this
        }
        held[page] = art to radiusPx
        glass.cornerRadius = radiusPx.toFloat()
        LiquidGlassKit.rebind(glass, art)
        placeSlab(page, glass, art, inset)
        // The slab stands where the tile was when focus landed on it. When the page moves that tile —
        // a shelf leaving the page, a row re-laying out — the glass must come with it, or it stays
        // painted over the wall where the poster used to be.
        val past = slabMovers[page]
        if (past == null || past.first !== art) {
            past?.second?.let { m -> past.first.removeOnLayoutChangeListener(m) }
            val mover = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (held[page]?.first === art) placeSlab(page, glass, art, inset)
            }
            art.addOnLayoutChangeListener(mover)
            slabMovers[page] = art to mover
        }
        glass.visibility = View.VISIBLE
        if (fadeSlab(app)) glass.animate().alpha(1f).setDuration(120L).start()
        else { glass.animate().cancel(); glass.alpha = 1f }
    }

    /** The slab over the artwork, not over the card: a poster's name sits under the tile and stays
     *  bare text on the wall, exactly as the pack draws every unfocused tile. */
    private fun placeSlab(
        page: ViewGroup,
        glass: LiquidGlassView,
        art: View,
        inset: Int,
    ) {
        val a = IntArray(2); art.getLocationInWindow(a)
        val p = IntArray(2); page.getLocationInWindow(p)
        (glass.layoutParams as android.view.ViewGroup.MarginLayoutParams).let { lp ->
            lp.width = art.width - inset * 2
            lp.height = art.height - inset * 2
            lp.leftMargin = a[0] - p[0] + inset
            lp.topMargin = a[1] - p[1] + inset
            glass.layoutParams = lp
        }
    }

    /** Each page's slab is glued to the tile it belongs to; this is the glue, and the tile. */
    private val slabMovers =
        java.util.WeakHashMap<ViewGroup, Pair<View, View.OnLayoutChangeListener>>()

    /**
     * The fade is free on the shader tier and expensive on the CPU tier, where every frame of it
     * re-blurs the poster under the slab. On the panel that cannot afford it, the slab arrives with
     * the focus itself, which is also how the rest of the pack's focus feedback behaves there.
     */
    private fun fadeSlab(app: AppContext): Boolean =
        android.os.Build.VERSION.SDK_INT >= 33 && app.theme.motionEnabled

    /**
     * A page tells the slab when it is moving, and the slab steps out. A lens costs a fresh capture
     * and refraction of whatever tile it stands on, every frame of the scroll, for a poster that is
     * on its way past: measured on the Android 11 panel, keeping it up while the shelves scrolled
     * doubled the median frame time (24 ms to 48 ms) for something nobody looks at. When the page
     * settles, the slab comes back to the tile that holds focus.
     */
    fun slabScrolling(view: View, app: AppContext, scrolling: Boolean) {
        if (!LiquidGlassKit.isGlass(app)) return
        // Same rule as the slab, one tier out: while the page is moving, every lens on it replays
        // its last honest frame instead of recapturing and refracting the page each frame. When the
        // page settles, the lenses come back — full glass on the frame after.
        LiquidGlassKit.scrollFreeze(scrolling)
        if (app.theme.packInt("focusGlass", 1) == 0) return
        val page = pageOf(view) ?: return
        if (scrolling) hideGlassSlab(app)
        else held[page]?.let { (art, radius) ->
            if (art.isFocused && art.isAttachedToWindow) showGlassSlab(art, app, radius)
        }
    }

    /** The tile each page's slab was last standing on, so it can come back to it. */
    private val held = java.util.WeakHashMap<ViewGroup, Pair<View, Int>>()

    /**
     * The page re-laid itself out under the slab — a shelf left the page and every row below it came
     * up — so the glass is standing on bare wall where the tile used to be. Put it back on the tile
     * that holds focus.
     */
    fun reseatSlab(view: View, app: AppContext) = slabScrolling(view, app, false)

    private fun hideGlassSlab(app: AppContext) {
        for ((_, glass) in slabs) {
            if (fadeSlab(app)) {
                glass.animate().alpha(0f).setDuration(120L).withEndAction {
                    if (glass.alpha <= 0.01f) glass.visibility = View.INVISIBLE
                }.start()
            } else {
                glass.animate().cancel(); glass.alpha = 0f; glass.visibility = View.INVISIBLE
            }
        }
    }

    /**
     * The wash every Glass page stands on. The reference is a photographic wall, not a flat
     * gradient: a field sample (2026-11-02) showed a cool dark band across the top, a dark left
     * rim, a light drift across the chip/rail seam, and warm bokeh glows in the lower third.
     * Approximated as the base TL→BR gradient plus soft radial blobs from the token
     * "wallBlobs" ("x,y,r:color:alpha" triples, x/y/r in % of the stage) — original values,
     * no copied assets, and the same drawable on Home, Detail and every Settings page.
     */
    private var grainCache: android.graphics.Bitmap? = null
    private var grainStrength = -1
    private data class WallBlob(val xPct: Float, val yPct: Float, val radiusPct: Float, val color: Int, val alpha: Float)
    private data class DrawBlob(
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
        val shader: android.graphics.RadialGradient,
    )

    private fun grainBitmap(strength: Int): android.graphics.Bitmap {
        grainCache?.takeIf { !it.isRecycled && grainStrength == strength }?.let { return it }
        val side = 160
        val bmp = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
        val px = IntArray(side * side)
        val r = java.util.Random(20261102L)
        // Film grain: a salt-and-pepper jitter of faint light and dark specks around the
        // wash — symmetric so it never tints the wall one way. Its strength is the pack's dial,
        // because a lens has nothing to bend out of a smooth gradient: this speckle, and the bokeh
        // discs the same wall carries, are what make the rim of a glass surface visible at all.
        val speck = (0x0A * strength.coerceAtLeast(1)).coerceAtMost(0x40)
        for (i in px.indices) {
            px[i] = if (r.nextBoolean()) (speck shl 24) or 0x00FFFFFF
            else (speck shl 24) or 0x00000000
        }
        bmp.setPixels(px, 0, side, 0, 0, side, side)
        grainCache = bmp
        grainStrength = strength
        return bmp
    }

    /**
     * The wall, painted once per (size, tokens) into a bitmap everyone then blits.
     *
     * The wash is a gradient plus a dozen bokeh discs plus a grain tile, and it is the background of
     * every Glass page: Home, Detail, Settings, the PIN gate — and the backdrop every lens samples.
     * Painted from the tokens each time, that is a gradient, a dozen radial discs and a grain tile
     * over 1920x1080, ten-plus times a second on a 2 GB panel. Rasterised once per (size, wall
     * tokens) it is one blit, which is what made the wall affordable next to the lenses.
     */
    private var wallCache: android.graphics.Bitmap? = null
    private var wallCacheKey = ""

    /** The wall, painted once per (size, wall tokens) into a bitmap everything else blits. */
    fun wallRaster(app: AppContext, w: Int, h: Int): android.graphics.Bitmap {
        val theme = app.theme
        val key = "$w x $h " + theme.id + theme.color("wallA", "") + theme.color("wallB", "") +
            theme.packString("wallBlobs", "") + theme.packInt("wallGrain", 0)
        wallCache?.takeIf { !it.isRecycled && wallCacheKey == key }?.let { return it }
        val bmp = android.graphics.Bitmap.createBitmap(
            w.coerceAtLeast(1), h.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        val painted = wallPaint(app)
        painted.setBounds(0, 0, bmp.width, bmp.height)
        painted.draw(android.graphics.Canvas(bmp))
        wallCache = bmp
        wallCacheKey = key
        return bmp
    }

    /**
     * The page background. With `wallRaster` on (the Glass pack's default) the page blits the
     * painted wall instead of re-painting a gradient, a dozen bokeh discs and a grain tile every
     * frame the page redraws — on the 2 GB Android 11 panel that repaint was a large part of what
     * made scrolling the rails feel heavy, and it is also what every lens samples.
     */
    fun wall(app: AppContext): android.graphics.drawable.Drawable {
        if (app.theme.packInt("wallRaster", 1) == 0) return wallPaint(app)
        return object : android.graphics.drawable.Drawable() {
            override fun draw(canvas: android.graphics.Canvas) {
                canvas.drawBitmap(
                    wallRaster(app, bounds.width(), bounds.height()), 0f, 0f, null)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
            override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
        }
    }

    /** The wall, drawn from its tokens: the base wash, the bokeh discs, the film grain. */
    private fun wallPaint(app: AppContext): android.graphics.drawable.Drawable {
        val theme = app.theme
        val base = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(theme.color("wallA", "#8FA3B2"), theme.color("wallB", "#9A918C")),
        )
        val blobs = theme.packString("wallBlobs", "").split(";").mapNotNull { spec ->
            val f = spec.trim().split(":")
            if (f.size != 3) return@mapNotNull null
            val g = f[0].split(",")
            if (g.size != 3) return@mapNotNull null
            try {
                WallBlob(g[0].toFloat(), g[1].toFloat(), g[2].toFloat(),
                    Color.parseColor(f[1]), f[2].toFloat())
            } catch (e: Exception) { null }
        }
        val grain = theme.packInt("wallGrain", 0)
        if (blobs.isEmpty() && grain <= 0) return base
        return object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint()
            // The wall is kept under static content for long stretches. Its blur-like radial
            // glows therefore need to be constructed only when its bounds change, not for every
            // frame that merely composites the page above it.
            private var cachedWidth = -1
            private var cachedHeight = -1
            private var drawBlobs: List<DrawBlob> = emptyList()
            private var grainShader: android.graphics.BitmapShader? = null

            private fun prepareDrawAssets(width: Int, height: Int) {
                if (cachedWidth == width && cachedHeight == height) return
                val longest = maxOf(width, height).toFloat()
                drawBlobs = blobs.map { blob ->
                    val centerX = blob.xPct / 100f * width
                    val centerY = blob.yPct / 100f * height
                    val radius = blob.radiusPct / 100f * longest
                    val color = (blob.color and 0x00FFFFFF) or
                        ((blob.alpha * 255).toInt().coerceIn(0, 255) shl 24)
                    DrawBlob(
                        centerX,
                        centerY,
                        radius,
                        android.graphics.RadialGradient(
                            centerX, centerY, radius, color, 0,
                            android.graphics.Shader.TileMode.CLAMP,
                        ),
                    )
                }
                if (grain > 0 && grainShader == null) {
                    grainShader = android.graphics.BitmapShader(
                        grainBitmap(grain),
                        android.graphics.Shader.TileMode.REPEAT,
                        android.graphics.Shader.TileMode.REPEAT,
                    )
                }
                cachedWidth = width
                cachedHeight = height
            }

            override fun draw(canvas: android.graphics.Canvas) {
                val w = bounds.width(); val h = bounds.height()
                base.setBounds(0, 0, w, h); base.draw(canvas)
                prepareDrawAssets(w, h)
                for (blob in drawBlobs) {
                    paint.shader = blob.shader
                    canvas.drawCircle(blob.centerX, blob.centerY, blob.radius, paint)
                }
                if (grain > 0) {
                    // The reference wash is photographic film, not a vector gradient: procedural
                    // grain (built once, tiled) breaks the banding without copying its asset.
                    paint.shader = grainShader
                    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
                }
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
            override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
        }
    }
}
