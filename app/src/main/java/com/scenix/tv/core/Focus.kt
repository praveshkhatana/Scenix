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
                v.animate().scaleX(target).scaleY(target).setDuration(ms)
                    .setInterpolator(AccelerateDecelerateInterpolator()).start()
            }
            v.animate().translationZ(elev).setDuration(ms)
                .setInterpolator(AccelerateDecelerateInterpolator()).start()
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

    /**
     * Put focus where the viewer expects it the moment a screen appears. Calling requestFocus()
     * while building the view does nothing — the view is not in the window yet.
     */
    fun focusOnAttach(root: View, pick: (View) -> View? = { firstActionableIn(it) }) {
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.post {
                    if (v.isAttachedToWindow) {
                        val t = pick(v)
                        if (t == null || !t.requestFocus()) focusFirst(v)
                    }
                }
            }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    /** First view a viewer could actually act on, in reading order. */
    private fun firstActionableIn(view: View): View? {
        if (canClick(view) && view.isFocusable && shown(view)) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                firstActionableIn(view.getChildAt(i))?.let { return it }
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
