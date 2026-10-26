package com.scenix.tv.core

import android.view.View
import android.view.ViewParent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * The 10-foot camera. On a mouse you point at things; on a remote the only way to look at something
 * is to put it under the eye, so the row that holds the focused card slides it to the middle of the
 * band the viewer can actually see, and the page only moves down when the row sits below that band.
 *
 * This lives in core because every row is a RecyclerView and every screen may want it — no plugin
 * has to know how scroll math works to behave like Scenix.
 *
 * Deliberately conservative: a row never scrolls up to re-centre (that would pull the billboard off
 * screen the moment a card takes focus), and the row list never scrolls while the viewer is typing
 * or on a screen without rows.
 */
object Camera {

    /** Slide the row so the focused card sits in the middle of the row's visible band. */
    private fun centerInRow(card: View) {
        val row = rowOf(card) ?: return
        val lm = row.layoutManager as? LinearLayoutManager ?: return
        if (!lm.canScrollHorizontally()) return

        val left = row.paddingLeft
        val right = row.width - row.paddingRight
        if (right - left <= 0) return
        val band = left + (right - left) / 2
        val center = (card.left + card.right) / 2
        val dx = center - band
        // A card already near the middle does not need a move, and a small move looks like a wobble.
        if (Math.abs(dx) < Math.max(8, card.width / 6)) return
        row.smoothScrollBy(dx, 0)
    }

    /**
     * Lift the page only when the focused row sits below the visible band. Never pulling the page
     * back up is what keeps the billboard on screen while the viewer browses the top rows.
     */
    private fun liftPage(card: View) {
        val row = rowOf(card) ?: return
        val outer = pageOf(row) ?: return
        val lm = outer.layoutManager as? LinearLayoutManager ?: return
        if (!lm.canScrollVertically()) return

        var rowView: View = row
        var parent: ViewParent? = rowView.parent
        while (parent is View && parent !== outer) {
            rowView = parent
            parent = rowView.parent
        }
        if (rowView === row) return

        val band = (outer.paddingTop + outer.height) / 2
        val center = (rowView.top + rowView.bottom) / 2
        val dy = center - band
        if (dy >= rowView.height / 3) {
            outer.smoothScrollBy(0, dy)
            return
        }
        // Going up, the page only moves when the shelf has actually gone under the nav — re-centring
        // on the way up would drag the billboard off screen the first time the viewer looks up.
        if (rowView.top < outer.paddingTop) outer.smoothScrollBy(0, rowView.top - outer.paddingTop)
    }

    /** One entry point for a row plugin: whatever just took focus, frame it. */
    fun follow(card: View, ctx: AppContext) {
        if (ctx.theme.animMs("rowScrollMs", 320) <= 0) return
        centerInRow(card)
        liftPage(card)
    }

    private fun rowOf(card: View): RecyclerView? {
        var p: ViewParent? = card.parent
        while (p != null) {
            if (p is RecyclerView) {
                val lm = p.layoutManager as? LinearLayoutManager ?: return null
                if (lm.canScrollHorizontally()) return p
            }
            p = (p as? View)?.parent
        }
        return null
    }

    private fun pageOf(row: RecyclerView): RecyclerView? {
        var p: ViewParent? = row.parent
        while (p != null) {
            if (p is RecyclerView) {
                val lm = p.layoutManager as? LinearLayoutManager ?: return null
                if (lm.canScrollVertically()) return p
            }
            p = (p as? View)?.parent
        }
        return null
    }
}
