package com.scenix.tv.core

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.animation.AccelerateDecelerateInterpolator
import android.graphics.Color
import android.graphics.drawable.LayerDrawable

/**
 * The focus language of the remaining native UI (the setup screen): white ring, a small lift and a
 * shadow, scale-only so it stays cheap on a 2 GB TV.
 *
 * The focusable view and the view that receives the ring can differ — a row takes focus and its
 * background draws the ring — which is why [attach] takes both. OK always acts: a focused view that
 * is not itself clickable routes the press to the nearest view that can act, so the viewer never has
 * to press twice.
 */
object Focus {

    fun attach(
        focusable: View,
        ringOn: View?,
        ctx: AppContext,
        ringRadiusPx: Int = 0,
        scale: Boolean = true,
        animateScale: Boolean = true,
        elevate: Boolean = true,
        glowInset: Int = 0,
        onClick: (() -> Unit)? = null,
        onFocus: ((Boolean) -> Unit)? = null,
        arrow: ((Int) -> Boolean)? = null,
    ) {
        val theme = ctx.theme
        // The pack chooses the focus language. `ring` is the classic white outline; `glow` thickens it
        // in the pack's accent and lifts the card; `lift` removes the outline entirely and lets scale
        // and shadow be the focus. One place, so every screen in the pack behaves the same.
        val style = theme.focusStyle
        val ringColor = if (style == "glow") theme.focusGlowColor else theme.focusRingColor
        val ringWidth = if (style == "glow") theme.focusRingPx * 2 else theme.focusRingPx
        // A lifted poster has enough visual mass for scale and shadow to read as focus.  A settings
        // row, back button or text action does not: on a dark TV screen it appeared unfocused even
        // though Android had moved the D-pad target.  Keep lift for artwork, use a slim keyline for
        // controls that deliberately opt out of scaling.
        val liftControl = style == "lift" && !scale
        val ring: android.graphics.drawable.Drawable = when {
            style == "lift" && !liftControl -> Views.ring(android.graphics.Color.TRANSPARENT, ringRadiusPx, 0)
            liftControl -> Views.ring(theme.focusRingColor, ringRadiusPx, maxOf(1, theme.focusRingPx))
            style == "glow" && !scale -> {
                // A halo is legible around artwork, but around a compact text button it spreads
                // into neighbouring chrome. Controls get one crisp keyline instead, including
                // white primary buttons whose focus must not disappear into their fill.
                Views.ring(theme.focusRingColor, ringRadiusPx, maxOf(2, theme.focusRingPx))
            }
            style == "glow" && glowInset > 0 -> {
                // The glow target is deliberately larger than the artwork and is inserted *behind*
                // it.  Never build this from several thick strokes: every stroke has a hard inner
                // edge and Android stacks those edges into the grey bands visible below a poster.
                // These are instead translucent filled plates. The art covers their centres, so
                // only the low-cost, soft falloff outside the tile remains. One small keyline sits
                // just outside the art so the D-pad target stays unmistakable at a glance.
                val c = theme.focusGlowColor
                val r = android.graphics.Color.red(c)
                val g = android.graphics.Color.green(c)
                val b = android.graphics.Color.blue(c)
                val outer = glowInset
                val middle = (glowInset * 0.62f).toInt().coerceAtLeast(theme.px(8f))
                val near = (glowInset * 0.30f).toInt().coerceAtLeast(theme.px(4f))
                val keyOutset = theme.px(3f).coerceAtMost(glowInset)
                fun inset(outset: Int) = (glowInset - outset).coerceAtLeast(0)
                val halo = LayerDrawable(arrayOf(
                    Views.rounded(Color.argb(8, r, g, b), ringRadiusPx + outer),
                    Views.rounded(Color.argb(12, r, g, b), ringRadiusPx + middle),
                    Views.rounded(Color.argb(18, r, g, b), ringRadiusPx + near),
                    Views.ring(Color.argb(224, r, g, b), ringRadiusPx + keyOutset,
                        maxOf(1, theme.px(2f))),
                ))
                halo.setLayerInset(0, inset(outer), inset(outer), inset(outer), inset(outer))
                halo.setLayerInset(1, inset(middle), inset(middle), inset(middle), inset(middle))
                halo.setLayerInset(2, inset(near), inset(near), inset(near), inset(near))
                halo.setLayerInset(3, inset(keyOutset), inset(keyOutset), inset(keyOutset), inset(keyOutset))
                halo
            }
            style == "glow" -> Views.ring(theme.focusRingColor, ringRadiusPx, maxOf(2, theme.focusRingPx))
            else -> Views.ring(ringColor, ringRadiusPx, ringWidth)
        }
        val plain = Views.ring(android.graphics.Color.TRANSPARENT, ringRadiusPx, 0)
        val base = ringOn?.background
        // Foreground is the right layer for a container whose children reach its edges (a poster
        // card): it paints over them. It is the wrong layer for a TextView on this Android 11 panel,
        // whose foreground never reached the screen, so those views get a background LayerDrawable
        // instead. Both are the same white ring to the viewer.
        val onForeground = ringOn is ViewGroup
        if (onForeground) ringOn?.foreground = plain

        focusable.isFocusable = true
        focusable.isFocusableInTouchMode = false

        fun paintRing(focused: Boolean) {
            val target = ringOn ?: return
            if (onForeground) {
                target.foreground = if (focused) ring else plain
                return
            }
            target.background = when {
                !focused -> base
                base == null -> ring
                else -> LayerDrawable(
                    arrayOf(base.constantState?.newDrawable()?.mutate() ?: base, ring)
                )
            }
        }
        paintRing(false)

        if (onClick != null) {
            focusable.isClickable = true
            focusable.setOnClickListener { onClick() }
        }

        focusable.setOnKeyListener { _, code, ev ->
            // Activation is deliberately performed on DOWN for responsive TV feedback. Consume the
            // matching UP too: if a click starts another Activity on DOWN, letting UP fall through
            // can be delivered to the freshly created screen and invoke its newly focused Play
            // control. That was the source of an occasional profile-selection → immediate-detail
            // jump on fast devices.
            if (ev.action == KeyEvent.ACTION_UP && code in setOf(
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE,
                )) return@setOnKeyListener true
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            // A row may want an arrow for itself — the hero's Featured rail wraps at its ends to
            // keep the loop illusion — before the arrow falls through to the system's focus search.
            arrow?.let { if (it(code)) return@setOnKeyListener true }
            when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_SPACE, -> {
                    val act = actionable(focusable) ?: return@setOnKeyListener false
                    act.performClick()
                    true
                }
                else -> false
            }
        }

        // A pointer on a TV (mouse, air-mouse, touch screen) points at the thing it means, so hover
        // has to produce the identical effect as the D-pad rather than a third look.
        focusable.setOnHoverListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_HOVER_ENTER) v.requestFocus()
            false
        }

        val ms = theme.focusDurationMs.toLong()
        focusable.onFocusChangeListener = View.OnFocusChangeListener { v, has ->
            paintRing(has)
            onFocus?.invoke(has)
            val target = if (has) {
                if (style == "lift") Math.max(theme.focusScale, 1.045f) else theme.focusScale
            } else 1f
            val elev = if (has) theme.focusElevationPx.toFloat() else 0f
            // Artwork uses the Apple-style lift.  Compact or full-width controls use the keyline
            // above instead, so they do not grow and shove surrounding chrome around.
            if (scale || (style == "lift" && !liftControl)) {
                // Shelves can change focus several times a second.  Respect the platform's
                // reduced-motion setting and settle synchronously when a card asked for the
                // low-cost focus path; cancelling first prevents a late animation from snapping a
                // recycled card back under the active one.
                if (animateScale && theme.motionEnabled) {
                    // A 200 ms lift re-composites the whole 1080p surface for a dozen frames. On the
                    // Mali-G31 panel that is where the missed deadlines came from, so card shelves
                    // ask for the instant lift below and only small chrome animates.
                    v.animate().scaleX(target).scaleY(target).setDuration(minOf(ms, 100L))
                        .setInterpolator(AccelerateDecelerateInterpolator()).start()
                } else {
                    v.animate().cancel()
                    v.scaleX = target
                    v.scaleY = target
                }
            }
            // A translated Z animates a shadow pass every frame. On the 2 GB panel the cards get the
            // ring and scale, not an elevation animation; setup keeps it because it has one button.
            if (elevate && theme.motionEnabled) {
                v.animate().translationZ(elev).setDuration(ms)
                    .setInterpolator(AccelerateDecelerateInterpolator()).start()
            } else if (elevate) {
                v.translationZ = elev
            }
        }
    }

    /**
     * The view that should actually respond to OK when the focused view cannot: the view itself,
     * then the nearest ancestor wired to a click, then the first clickable thing below it. Null means
     * nothing can act, and OK falls back to normal key handling.
     */
    private fun actionable(v: View): View? {
        if (canClick(v)) return v
        var up: ViewParent? = v.parent
        while (up != null) {
            if (up is View && canClick(up)) return up
            up = up.parent
        }
        if (v is ViewGroup) return firstClickableIn(v)
        return null
    }

    private fun canClick(v: View): Boolean = v.isClickable && v.hasOnClickListeners()

    private fun shown(v: View): Boolean = v.visibility == View.VISIBLE

    private fun firstClickableIn(group: ViewGroup): View? {
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i)
            if (canClick(c) && shown(c)) return c
            if (c is ViewGroup) {
                val deeper = firstClickableIn(c)
                if (deeper != null) return deeper
            }
        }
        return null
    }

    /** Ask a container to hand focus to its first focusable child. */
    fun focusFirst(container: View): Boolean = container.requestFocus() || focusFirstIn(container)

    private fun focusFirstIn(view: View): Boolean {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val c = view.getChildAt(i)
                if (c.isFocusable && c.requestFocus()) return true
                if (c is ViewGroup && focusFirstIn(c)) return true
            }
        }
        return false
    }
}
