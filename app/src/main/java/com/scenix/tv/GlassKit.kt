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
            setPadding(theme.px(42f), theme.px(10f), theme.px(42f), theme.px(10f))
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
     * A feature plate: tinted gradient fill, hairline specular rim and an inner top sheen —
     * never a drop shadow. Returns the plate; the caller fills it (title top, Play bottom, art
     * bleeding from one edge) inside the rounded outline.
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
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "Jump to $label"
            setPadding(theme.px(26f), 0, theme.px(24f), 0)
            background = Views.rounded(
                theme.color(if (active) "chipFillActive" else "chipFill",
                    if (active) "rgba(255,255,255,0.22)" else "rgba(255,255,255,0.10)"),
                radius, theme.px(1f), theme.color("glassRim", "rgba(255,255,255,0.38)"),
            )
        }
        val glyph = when {
            label.contains("Trending", true) -> "\u2197"
            label.contains("New", true) -> "\u2726"
            label.contains("Top", true) || label.contains("rated", true) -> "\u2605"
            label.contains("Language", true) || label.contains("Hindi", true) -> "\u2691"
            else -> "\u25CF"
        }
        chip.addView(Views.text(app, "meta", 26f, Color.WHITE, glyph, bold = true),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        chip.addView(Views.text(app, "chip", 21f, Color.WHITE, label, bold = true, maxLines = 1),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = theme.px(14f) })
        Focus.attach(chip, chip, app, radius, scale = false, elevate = false, onClick = onClick)
        return chip
    }

    /** The wash every Glass page stands on: one soft photographic gradient, TL to BR. */
    fun wall(app: AppContext): GradientDrawable {
        val theme = app.theme
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(theme.color("wallA", "#8FA3B2"), theme.color("wallB", "#9A918C")),
        )
    }
}
