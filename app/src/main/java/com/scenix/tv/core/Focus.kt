package com.scenix.tv.core

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.animation.AccelerateDecelerateInterpolator

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
        onClick: (() -> Unit)? = null,
    ) {
        val theme = ctx.theme
        val ring = Views.ring(theme.focusRingColor, ringRadiusPx, theme.focusRingPx)
        val plain = Views.ring(android.graphics.Color.TRANSPARENT, ringRadiusPx, 0)

        focusable.isFocusable = true
        focusable.isFocusableInTouchMode = false
        ringOn?.foreground = plain

        if (onClick != null) {
            focusable.isClickable = true
            focusable.setOnClickListener { onClick() }
        }

        focusable.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
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
            ringOn?.foreground = if (has) ring else plain
            val target = if (has) theme.focusScale else 1f
            val elev = if (has) theme.focusElevationPx.toFloat() else 0f
            if (scale) {
                if (animateScale) {
                    // A 200 ms lift re-composites the whole 1080p surface for a dozen frames. On the
                    // Mali-G31 panel that is where the missed deadlines came from, so card shelves
                    // ask for the instant lift below and only small chrome animates.
                    v.animate().scaleX(target).scaleY(target).setDuration(minOf(ms, 100L))
                        .setInterpolator(AccelerateDecelerateInterpolator()).start()
                } else {
                    v.scaleX = target
                    v.scaleY = target
                }
            }
            // A translated Z animates a shadow pass every frame. On the 2 GB panel the cards get the
            // ring and scale, not an elevation animation; setup keeps it because it has one button.
            if (elevate) {
                v.animate().translationZ(elev).setDuration(ms)
                    .setInterpolator(AccelerateDecelerateInterpolator()).start()
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
