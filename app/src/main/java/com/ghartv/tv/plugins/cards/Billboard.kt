package com.scenix.tv.plugins.cards

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Preview
import com.scenix.tv.core.RowLayout
import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.Item

/**
 * The billboard as Home and the "explore" screens use it: one hero card, the items that qualify for
 * it, and a timer that walks them.
 *
 * Rotation is not decoration — it is why a hub that sends no `hero` row still looks like Netflix.
 * The items come from the caller (a real hero row, or the first row with usable artwork), and this
 * file only decides when to show the next one.
 *
 * Two things stop the carousel, both of them the viewer's own actions: focus sitting on the hero's
 * buttons (nothing should move out from under a press), and a card preview or dwell owning the hero
 * (the trailer of the title being looked at outranks the billboard's).
 *
 * The dots at the bottom right are Netflix's: one per item, and the active one fills across the
 * rotation interval, so the wait is visible instead of mysterious.
 */
class Billboard(private val ctx: AppContext, private val host: ScreenHost) {

    private val theme = ctx.theme
    private val main = Handler(Looper.getMainLooper())
    private val layout = RowLayout.of(theme, ctx.screenW, "hero")
    private val holder = HeroCard.create(ctx, host, layout)
    private val dots = LinearLayout(ctx.app)
    private val fills = ArrayList<View>()
    private var anim: ValueAnimator? = null

    private var items: List<Item> = emptyList()
    private var index = 0
    private var running = false

    val view: View = FrameLayout(ctx.app).apply {
        clipChildren = false
        addView(
            holder.root,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        dots.orientation = LinearLayout.HORIZONTAL
        dots.gravity = Gravity.CENTER_VERTICAL
        val dlp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dlp.gravity = Gravity.BOTTOM or Gravity.END
        dlp.setMargins(0, 0, theme.gutter, theme.px(24f))
        addView(dots, dlp)

        // Off screen, off the clock: a hidden rotator is a decoder and a wakeup for nothing.
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { start() }
            override fun onViewDetachedFromWindow(v: View) { stop() }
        })
    }

    /** The candidates, in the order the hub sent them. One item means no rotation and no dots. */
    fun setItems(newItems: List<Item>) {
        items = newItems
        index = 0
        if (items.isEmpty()) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        buildDots()
        show()
    }

    fun current(): Item? = items.getOrNull(index)

    private fun show() {
        val item = items.getOrNull(index) ?: return
        HeroContent.fill(holder, item, ctx, compact = false)
        Preview.setHeadline(item)
        val fade = theme.animMs("rotateFadeMs", 500)
        if (fade > 0 && items.size > 1) {
            holder.root.animate().cancel()
            holder.root.alpha = 0f
            holder.root.animate().alpha(1f).setDuration(fade.toLong()).start()
        }
        fillDot()
    }

    // ---- the clock ------------------------------------------------------------------------

    private val rotateMs get() = theme.token("hero", "rotateMs", 6000.0).toLong().coerceAtLeast(1500L)

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (paused()) {
                main.postDelayed(this, TICKER)
                return
            }
            if (items.size > 1) {
                index = (index + 1) % items.size
                show()
            }
            main.postDelayed(this, rotateMs)
        }
    }

    /** The viewer is busy with this title, so nothing may move underneath them. */
    private fun paused(): Boolean =
        view.hasFocus() || Preview.isCardPreview() || Preview.isDwelling()

    private fun start() {
        if (running || items.isEmpty()) return
        running = true
        main.postDelayed(tick, if (items.size > 1) rotateMs else Long.MAX_VALUE / 2)
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
        anim?.cancel()
        anim = null
    }

    /** Called when Home is left: the clock stops and the hero's decoder goes back to the TV. */
    fun release() {
        stop()
        Preview.release()
    }

    // ---- the dots -------------------------------------------------------------------------

    private fun buildDots() {
        dots.removeAllViews()
        fills.clear()
        val count = items.size.coerceAtMost(theme.token("hero", "maxItems", 5.0).toInt().coerceAtLeast(1))
        if (count < 2) return
        val w = theme.px(44f)
        val h = theme.px(4f)
        for (i in 0 until count) {
            val dot = FrameLayout(ctx.app)
            dot.background = com.scenix.tv.core.Views.rounded(theme.color("dotTrack", "#66FFFFFF"), h / 2)
            val fill = View(ctx.app)
            fill.background = com.scenix.tv.core.Views.rounded(theme.brand, h / 2)
            fill.scaleX = 0f
            fill.pivotX = 0f
            dot.addView(fill, FrameLayout.LayoutParams(w, h))
            val lp = LinearLayout.LayoutParams(w, h)
            lp.marginEnd = theme.px(8f)
            dots.addView(dot, lp)
            fills.add(fill)
        }
    }

    private fun fillDot() {
        anim?.cancel()
        for (f in fills) f.scaleX = 0f
        val active = fills.getOrNull(index) ?: return
        if (theme.animMs("rotateFadeMs", 500) <= 0) { active.scaleX = 1f; return }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = rotateMs
            addUpdateListener { active.scaleX = it.animatedFraction }
            start()
        }
    }

    private companion object {
        /** How often the rotator re-checks whether it may move, while it is waiting. */
        const val TICKER = 400L
    }
}
