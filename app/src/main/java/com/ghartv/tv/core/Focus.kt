package com.scenix.tv.core

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import androidx.recyclerview.widget.RecyclerView

/**
 * The focus language of the whole app: white 4 px ring + 5 % lift + shadow, ~200 ms, scale-only
 * (cheap on 2 GB RAM), exactly the mockup's `--focus` token (kids-app-plan §8).
 *
 * The focusable view and the view that receives the ring can differ — a card's root takes focus,
 * its artwork draws the ring — which is why [attach] takes both.
 */
object Focus {

    fun attach(
        focusable: View,
        ringOn: View,
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
        ringOn.foreground = plain

        if (onClick != null) {
            focusable.isClickable = true
            focusable.setOnClickListener { onClick() }
        }

        // OK always acts. A focused view that is not itself clickable used to swallow DPAD_CENTER,
        // so the press looked ignored and the next press moved focus — the "I have to press twice"
        // and "Play takes forever to press" complaints. Route OK to the nearest view that can act:
        // this view, else an ancestor that has a click handler, else a clickable child.
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

        focusable.onFocusChangeListener = View.OnFocusChangeListener { v, has ->
            ringOn.foreground = if (has) ring else plain
            val target = if (has) theme.focusScale else 1f
            val elev = if (has) theme.focusElevationPx.toFloat() else 0f
            if (scale) {
                v.animate().scaleX(target).scaleY(target).setDuration(theme.focusDurationMs.toLong()).start()
            }
            v.animate().translationZ(elev).setDuration(theme.focusDurationMs.toLong()).start()
        }
    }

    /**
     * The view that should actually respond to OK when the focused view cannot: the view itself,
     * then the nearest ancestor wired to a click (a card whose root is clickable), then the first
     * clickable thing below it. Null means nothing can act, and OK falls back to normal key handling.
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
     * while building the view does nothing — the view is not in the window yet — which is why every
     * screen opened with nothing focused and the first remote press was always wasted.
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

    /** First view a viewer could actually act on, in reading order; RecyclerViews are searched by row. */
    private fun firstActionableIn(view: View): View? {
        // The nav strip is never the answer: landing there when a screen opens is the "I pressed
        // Down twice" complaint. The viewer expects to be standing on content.
        if (view.tag == "nav") return null
        if (view is RecyclerView) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                (if (canClick(child)) child else firstActionableIn(child))?.let { return it }
            }
            return null
        }
        if (canClick(view) && view.isFocusable && shown(view)) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                firstActionableIn(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /**
     * The first card of the first shelf — where Netflix has focus when a browse screen opens, so one
     * press of OK opens something and Up reaches the billboard.
     */
    fun firstShelfCard(rv: RecyclerView): View? {
        for (i in 0 until rv.childCount) {
            val row = rv.getChildAt(i) ?: continue
            if (row.tag == "nav") continue
            val cards = firstRecyclerViewIn(row) ?: continue
            cards.getChildAt(0)?.let { return it }
        }
        return null
    }

    private fun firstRecyclerViewIn(v: View): RecyclerView? {
        if (v is RecyclerView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                firstRecyclerViewIn(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /** Ask a container to hand focus to its first focusable child (used when a screen opens). */
    fun focusFirst(container: View): Boolean = container.requestFocus() || focusFirstIn(container)

    private fun focusFirstIn(view: View): Boolean {
        if (view is RecyclerView) {
            val child = view.getChildAt(0) ?: view.findFocus()
            if (child != null && child.requestFocus()) return true
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val c = view.getChildAt(i)
                if (c.isFocusable && c.requestFocus()) return true
                if (c is ViewGroup && focusFirstIn(c)) return true
            }
        }
        return false
    }

    /**
     * D-pad correction: RecyclerViews happily walk focus sideways past the edge of a row and drop
     * it. We keep the standard search (which already goes down to the next row and up to the nav)
     * and only make sure the row itself is never a focus stop.
     */
    fun tuneRecyclerView(rv: RecyclerView) {
        rv.isFocusable = false
        rv.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rv.itemAnimator = null
        rv.clipToPadding = false
    }
}
