package com.scenix.tv.core

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import com.scenix.tv.R

/**
 * The selection highlight as one object that travels, instead of one ring per card.
 *
 * This is what makes a grid of tiles feel sleek: the highlight does
 * not fade out here and fade in there, it *moves* — a critically damped glide that lands on the next
 * tile without swinging past it, at whatever speed the keys are being held. A per-card ring cannot
 * do that: two cards' animations cross-fade and the eye reads it as a flicker.
 *
 * Same algorithm as the launcher's glass:
 *  - the card's rectangle is taken in the screen root's coordinates, ignoring the scale animation
 *    (`offsetDescendantRectToMyCoords`), so the frame lands on the tile and not on where the tile
 *    will be when the scale finishes;
 *  - the frame is sized to the tile *at its focused scale*, plus a pad, so it hugs the enlarged card
 *    instead of the resting one;
 *  - it moves by translation, animated, and any scroll moves it by the same amount with no animation
 *    at all, because a highlight that lags behind a scroll looks broken.
 *
 * The launcher uses androidx.dynamicanimation springs. This app has no such dependency (and adds no
 * new ones), so the glide is a 160 ms accelerate-decelerate move — critically damped in feel, since
 * nothing overshoots and comes back.
 */
object FocusGlass {

    private var glass: View? = null
    private val rect = Rect()
    private var sizedWidth = -1
    private var sizedHeight = -1
    private var targetX = 0f
    private var targetY = 0f
    private var shown = false

    /** Off with `focus: {glass: 0}`: each card then draws its own ring, the old way. */
    fun enabled(ctx: AppContext): Boolean = ctx.theme.token("focus", "glass", 1.0) >= 0.5

    /**
     * One call per screen, from the screen's own root. Called with the same root every time, the
     * overlay is reused rather than rebuilt, so a refresh does not blink.
     */
    fun install(root: ViewGroup, ctx: AppContext) {
        if (!enabled(ctx)) {
            glass?.let { (it.parent as? ViewGroup)?.removeView(it) }
            glass = null
            return
        }
        val g = glass ?: View(ctx.app).also { glass = it }
        if (g.parent !== root) {
            (g.parent as? ViewGroup)?.removeView(g)
            // Above everything the screen draws: the frame is the top-most thing on a 10-foot UI.
            root.addView(g, ViewGroup.LayoutParams(0, 0))
        }
        g.isFocusable = false
        g.isClickable = false
        g.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        g.alpha = 0f
        // Above every child of the screen root, whatever order they were added in.
        g.translationZ = ctx.theme.px(160f).toFloat()
        sizedWidth = -1
        sizedHeight = -1
        shown = false
    }

    /** Frame the card that just took focus. */
    fun follow(card: View, ctx: AppContext) {
        val g = glass ?: return
        val parent = g.parent as? ViewGroup ?: return
        if (card.width == 0 || card.height == 0) {
            // Focus restored before the first layout: the card has no size yet, and a frame sized to
            // nothing is worse than no frame. Try again once it has.
            card.post { if (card.isFocused && card.width > 0) follow(card, ctx) }
            return
        }
        val theme = ctx.theme
        rect.set(0, 0, card.width, card.height)
        parent.offsetDescendantRectToMyCoords(card, rect)

        val scale = theme.focusScale
        val pad = theme.px(theme.token("focus", "glassPad", 6.0).toFloat())
        val w = Math.round(card.width * scale + 2 * pad)
        val h = Math.round(card.height * scale + 2 * pad)
        val x = rect.exactCenterX() - w / 2f
        val y = rect.exactCenterY() - h / 2f

        if (w != sizedWidth || h != sizedHeight) {
            sizedWidth = w
            sizedHeight = h
            val lp = g.layoutParams
            lp.width = w
            lp.height = h
            g.layoutParams = lp
            g.background = Views.ring(theme.focusRingColor, glassRadius(ctx), theme.focusRingPx)
        }
        targetX = x
        targetY = y

        val ms = theme.animMs("glassMs", 160)
        if (!shown) {
            g.animate().cancel()
            g.translationX = x
            g.translationY = y
            g.animate().alpha(1f).setDuration(if (ms > 0) (ms * 1.1f).toLong() else 0L)
                .setInterpolator(AccelerateDecelerateInterpolator()).start()
            shown = true
        } else if (ms <= 0) {
            g.translationX = x
            g.translationY = y
        } else {
            g.animate().translationX(x).translationY(y).setDuration(ms.toLong())
                .setInterpolator(AccelerateDecelerateInterpolator()).start()
        }
    }

    /** Any scroll moves the frame by the same amount, immediately: no lag, no re-layout. */
    fun shift(dx: Int, dy: Int) {
        val g = glass ?: return
        if (!shown || (dx == 0 && dy == 0)) return
        targetX -= dx
        targetY -= dy
        g.animate().cancel()
        g.translationX = g.translationX - dx
        g.translationY = g.translationY - dy
    }

    /** Focus went somewhere that is not a card: the frame fades out rather than teleporting. */
    fun hide(ctx: AppContext) {
        val g = glass ?: return
        if (!shown) return
        shown = false
        val ms = ctx.theme.animMs("glassMs", 160)
        g.animate().cancel()
        g.animate().alpha(0f).setDuration(if (ms > 0) ms.toLong() else 0L).start()
    }

    /**
     * Where focus went, without being told: called after a focus change settles. If focus landed on a
     * card, frame it; anywhere else, fade out.
     */
    fun sync(view: View?, ctx: AppContext) {
        val card = view ?: (glass?.parent as? ViewGroup)?.findFocus()
        if (card != null && card.getTag(R.id.scenix_card_ctx) != null) follow(card, ctx) else hide(ctx)
    }

    private fun glassRadius(ctx: AppContext): Int {
        // The frame hugs a card that is itself rounded, so it carries the same corner, scaled up with
        // the card, or its corners cut inside the tile's.
        return (ctx.theme.radius("card", 6f) * ctx.theme.focusScale).toInt()
    }
}
