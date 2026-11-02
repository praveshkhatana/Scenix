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

    class ProfilePill(val root: LinearLayout, val initials: TextView,
                      val face: ImageView, val name: TextView)

    /** One household stop: avatar + name + chevron in a single frosted pill. */
    fun profilePill(context: Context, app: AppContext, onClick: () -> Unit): ProfilePill {
        val theme = app.theme
        val pill = LinearLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "Profiles"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Views.rounded(theme.color("profilePill", "rgba(8,16,28,0.80)"), theme.px(999f))
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
        pill.addView(Views.text(app, "nav", 16f, Color.WHITE, "⌄", bold = true, gravity = Gravity.CENTER),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = theme.px(6f) })
        Focus.attach(pill, pill, app, theme.px(999f), scale = false, elevate = false, onClick = onClick)
        return ProfilePill(pill, initials, face, name)
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
        val stop = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = description
            background = Views.rounded(theme.color("profilePill", "rgba(8,16,28,0.80)"), theme.px(999f))
        }
        stop.addView(ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding((sizePx * 0.25f).toInt(), (sizePx * 0.25f).toInt(),
                (sizePx * 0.25f).toInt(), (sizePx * 0.25f).toInt())
        }, FrameLayout.LayoutParams(sizePx, sizePx))
        Focus.attach(stop, stop, app, theme.px(999f), scale = false, elevate = false, onClick = onClick)
        return stop
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
        // The pack default until the loaded art is sampled; setWash re-keys the wash to the
        // title's own palette — the real liquid-glass behaviour the first pass deferred.
        private var wash = 0
        fun setWash(color: Int) { if (color != wash) { wash = color; invalidate() } }
        fun currentWash(): Int = wash
        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w < 1f || h < 1f || wash == 0) return
            val theme = app.theme
            val fade = theme.packInt("heroFadePct", 62).coerceIn(25, 95) / 100f
            paint.shader = android.graphics.LinearGradient(
                0f, 0f, w, 0f, intArrayOf(wash, Color.TRANSPARENT),
                floatArrayOf(0f, fade), android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = android.graphics.LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(Color.TRANSPARENT, theme.color("heroScrim", "rgba(6,16,30,0.50)")),
                floatArrayOf(0.42f, 1f), android.graphics.Shader.TileMode.CLAMP)
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
        val chip = LinearLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            orientation = LinearLayout.HORIZONTAL
            // Measured on the reference chip crop: the pictogram+label pair sits CENTERED in the
            // chip, not set off the left padding — the left-set version read cramped.
            gravity = Gravity.CENTER
            contentDescription = "Jump to $label"
            setPadding(theme.px(18f), 0, theme.px(18f), 0)
            background = Views.rounded(
                theme.color(if (active) "chipFillActive" else "chipFill",
                    if (active) "rgba(255,255,255,0.22)" else "rgba(255,255,255,0.10)"),
                radius, theme.px(1f), theme.color("glassRim", "rgba(255,255,255,0.38)"),
            )
            // Cast shadow separate from the rim, like every other Glass surface.
            elevation = theme.space("chipElevation", 6f).toFloat()
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
        Focus.attach(chip, chip, app, radius, scale = false, elevate = false, onClick = onClick)
        return chip
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
    private fun grainBitmap(strength: Int): android.graphics.Bitmap {
        grainCache?.takeIf { !it.isRecycled && grainStrength == strength }?.let { return it }
        val side = 160
        val bmp = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(20261102L)
        val px = IntArray(side * side)
        val r = java.util.Random(20261102L)
        for (i in px.indices) {
            // Film grain: a salt-and-pepper jitter of faint light and dark specks around the
            // wash — symmetric so it never tints the wall one way.
            px[i] = if (r.nextBoolean()) 0x0BFFFFFF else 0x0B000000
        }
        bmp.setPixels(px, 0, side, 0, 0, side, side)
        grainCache = bmp
        grainStrength = strength
        return bmp
    }

    fun wall(app: AppContext): android.graphics.drawable.Drawable {
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
                Triple(Triple(g[0].toFloat(), g[1].toFloat(), g[2].toFloat()),
                    Color.parseColor(f[1]), f[2].toFloat())
            } catch (e: Exception) { null }
        }
        val grain = theme.packInt("wallGrain", 0)
        if (blobs.isEmpty() && grain <= 0) return base
        return object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint()
            override fun draw(canvas: android.graphics.Canvas) {
                val w = bounds.width(); val h = bounds.height()
                base.setBounds(0, 0, w, h); base.draw(canvas)
                val m = maxOf(w, h).toFloat()
                for ((pos, color, alpha) in blobs) {
                    val cx = pos.first / 100f * w; val cy = pos.second / 100f * h
                    val r = pos.third / 100f * m
                    val a = (color and 0x00FFFFFF) or ((alpha * 255).toInt().coerceIn(0, 255) shl 24)
                    paint.shader = android.graphics.RadialGradient(
                        cx, cy, r, a, 0, android.graphics.Shader.TileMode.CLAMP)
                    canvas.drawCircle(cx, cy, r, paint)
                }
                if (grain > 0) {
                    // The reference wash is photographic film, not a vector gradient: procedural
                    // grain (built once, tiled) breaks the banding without copying its asset.
                    paint.shader = android.graphics.BitmapShader(
                        grainBitmap(grain), android.graphics.Shader.TileMode.REPEAT,
                        android.graphics.Shader.TileMode.REPEAT)
                    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
                }
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
            override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
        }
    }
}
