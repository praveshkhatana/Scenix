package com.scenix.tv.core

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scenix.tv.R

/**
 * The focus language of the whole app: white 4 px ring + 5 % lift + shadow, ~200 ms, scale-only
 * (cheap on 2 GB RAM), exactly the mockup's `--focus` token (kids-app-plan §8).
 *
 * The focusable view and the view that receives the ring can differ — a card's root takes focus,
 * its artwork draws the ring — which is why [attach] takes both.
 *
 * A card gets exactly one focus listener and one key listener, ever. Everything else that has to
 * know "this card is now under the eye" — the trailer dwell, the camera, the MENU-key menu — hangs
 * off those through [setCardHooks], because a second listener would simply replace the ring.
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
                // MENU on a card is its context menu (mark as watched, not interested).
                KeyEvent.KEYCODE_MENU -> {
                    val menu = hooks(focusable)?.onMenu
                    if (menu != null) { menu(focusable); true } else false
                }
                // Up and down between shelves: same column, next row. Android's focus finder is free
                // to land on whatever rectangle is nearest, which in a nested list is usually the
                // first card of the next shelf — so the viewer loses their place every row.
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                    if (isCard(focusable)) {
                        moveShelf(focusable, code == KeyEvent.KEYCODE_DPAD_DOWN)
                    } else false
                else -> false
            }
        }

        // A pointer on a TV (mouse, air-mouse, touch screen) points at the thing it means, so hover
        // has to produce the identical effect as the D-pad rather than a third look. One line, because
        // the effect itself is the focus listener.
        focusable.setOnHoverListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_HOVER_ENTER) v.requestFocus()
            // Never consume the event: a mouse click on a hovered view is an ordinary click, and the
            // view already handles those.
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
            if (isCard(v)) {
                // The travelling frame, not a ring per card: whoever owns focus owns the frame.
                if (has) FocusGlass.follow(v, ctx) else v.post { FocusGlass.sync(null, ctx) }
            }
            hooks(v)?.onFocus?.invoke(v, has)
        }
    }

    // ---- moving between shelves -------------------------------------------------------------

    /** A card, as opposed to a button or a chip: CardKit tags every card root with its holder. */
    private fun isCard(v: View): Boolean = v.getTag(R.id.scenix_card_ctx) != null

    /**
     * Up and down from a card means the same column of the next shelf.
     *
     * A mouse user points; a remote user holds the key. Neither of them expects the highlight to jump
     * to the start of the next row because that is where the focus finder found a rectangle first.
     * The launcher's grid is one list, so the column is arithmetic; here the rows are shelves inside
     * a page, so the same arithmetic has to cross both lists — next row, same index.
     *
     * When the next shelf is not on screen yet, or the row above is a heading rather than a shelf,
     * this says nothing and the normal focus search answers instead.
     */
    private fun moveShelf(card: View, down: Boolean): Boolean {
        val shelf = shelfOf(card) ?: return false
        val page = pageOf(shelf) ?: return false
        val rowItem = shelf.parent as? View ?: return false
        val rowPos = page.getChildAdapterPosition(rowItem)
        val column = shelf.getChildAdapterPosition(card)
        if (rowPos < 0 || column < 0) return false

        val next = page.findViewHolderForAdapterPosition(rowPos + if (down) 1 else -1) ?: return false
        val nextShelf = shelfOf(next.itemView) ?: return false
        val target = childAt(nextShelf, column)
        if (target == null) {
            // That column is off the edge of the shelf: bring it in, then stand on it.
            nextShelf.scrollToPosition(column)
            nextShelf.post { childAt(nextShelf, column)?.requestFocus() }
            return true
        }
        return target.requestFocus()
    }

    /** The card a shelf is currently holding at this adapter position, if it has laid one out. */
    private fun childAt(rv: RecyclerView, adapterPosition: Int): View? =
        rv.findViewHolderForAdapterPosition(adapterPosition)?.itemView

    private fun shelfOf(view: View): RecyclerView? {
        var p: ViewParent? = view.parent
        while (p != null) {
            if (p is RecyclerView && (p.layoutManager as? LinearLayoutManager)?.canScrollHorizontally() == true) return p
            p = (p as? View)?.parent
        }
        return null
    }

    private fun pageOf(shelf: RecyclerView): RecyclerView? {
        var p: ViewParent? = shelf.parent
        while (p != null) {
            if (p is RecyclerView && (p.layoutManager as? LinearLayoutManager)?.canScrollVertically() == true) return p
            p = (p as? View)?.parent
        }
        return null
    }

    /**
     * Per-card hooks, stored on the view so a recycled card replaces them instead of stacking them.
     * `onFocus` is the preview dwell and the camera; `onMenu` is the MENU-key menu.
     */
    fun setCardHooks(view: View, onFocus: ((View, Boolean) -> Unit)?, onMenu: ((View) -> Unit)?) {
        view.setTag(
            R.id.scenix_card_obs,
            if (onFocus == null && onMenu == null) null else CardHooks(onFocus, onMenu),
        )
    }

    /** Any focusable view that needs to know it took focus (the hero's Play button restyles itself). */
    fun setFocusHooks(view: View, onFocus: ((View, Boolean) -> Unit)?) = setCardHooks(view, onFocus, null)

    private class CardHooks(val onFocus: ((View, Boolean) -> Unit)?, val onMenu: ((View) -> Unit)?)

    private fun hooks(v: View): CardHooks? = v.getTag(R.id.scenix_card_obs) as? CardHooks

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
     *
     * `clipChildren = false` is the other half of the focus ring: a focused card is 10 % bigger than
     * its slot, and a clipped parent cuts the ring off on one side, which is what makes a row look
     * "crooked" on a real TV.
     */
    fun tuneRecyclerView(rv: RecyclerView) {
        rv.isFocusable = false
        rv.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rv.itemAnimator = null
        rv.clipToPadding = false
        rv.clipChildren = false
        drawFocusedOnTop(rv)
    }

    /**
     * Paint the focused child last, so a lifted card is drawn OVER its neighbours rather than cut by
     * them. `clipChildren=false` alone only stops the parents clipping; the neighbour that happens to
     * be laid out after the focused card still paints on top of it. The launcher keeps its edges
     * clean the same way.
     */
    fun drawFocusedOnTop(rv: RecyclerView) {
        rv.setChildDrawingOrderCallback { count, i -> drawingOrder(rv, count, i) }
    }

    private fun drawingOrder(rv: RecyclerView, count: Int, i: Int): Int {
        val focused = rv.findFocus() ?: return i
        var top: View = focused
        while (top.parent !== rv && top.parent is View) top = top.parent as View
        if (top.parent !== rv) return i
        val idx = rv.indexOfChild(top)
        if (idx < 0 || idx == count - 1) return i
        return when (i) {
            count - 1 -> idx
            idx -> count - 1
            else -> i
        }
    }
}
