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
        control: Boolean = false,
        scaleTo: Float = 0f,
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
            style == "glow" && glowInset > 0 && control -> {
                // A compact control — a chip, a pill, a settings row — carries its own fill and label,
                // so the filled plates would paint straight over its text (owner, round 5: "content
                // not visible/readable on focus"). The control takes only the halo's edges: a soft
                // glow ring and the warm keyline hugging its border, leaving the contents fully
                // readable in every state. When the ring rides an oversized canvas behind the
                // control (a haloRow) the rings are inset INTO the canvas to land at the control's
                // edge; when it rides the control itself (a chip) they hang just outside it.
                val c = theme.focusGlowColor
                val k = theme.focusKeylineColor
                val ka = if (theme.focusKeylineExplicit) 255 else 224
                val wg = maxOf(theme.px(10f), theme.focusRingPx)
                val wk = theme.focusKeylinePx
                val halo = LayerDrawable(arrayOf(
                    Views.ring(Color.argb(theme.focusGlowAlphas[2],
                        Color.red(c), Color.green(c), Color.blue(c)),
                        ringRadiusPx + theme.px(12f), wg),
                    Views.ring(Color.argb(ka, Color.red(k), Color.green(k), Color.blue(k)),
                        ringRadiusPx + theme.px(7f), wk),
                ))
                if (ringOn === focusable) {
                    halo.setLayerInset(0, -theme.px(2f), -theme.px(2f), -theme.px(2f), -theme.px(2f))
                    halo.setLayerInset(1, -theme.px(3f), -theme.px(3f), -theme.px(3f), -theme.px(3f))
                } else {
                    val i0 = (glowInset - theme.px(12f)).coerceAtLeast(0)
                    val i1 = (glowInset - theme.px(7f)).coerceAtLeast(0)
                    halo.setLayerInset(0, i0, i0, i0, i0)
                    halo.setLayerInset(1, i1, i1, i1, i1)
                }
                halo
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
                val alphas = theme.focusGlowAlphas
                val k = theme.focusKeylineColor
                val ka = if (theme.focusKeylineExplicit) 255 else 224
                val halo = LayerDrawable(arrayOf(
                    Views.rounded(Color.argb(alphas[0], r, g, b), ringRadiusPx + outer),
                    Views.rounded(Color.argb(alphas[1], r, g, b), ringRadiusPx + middle),
                    Views.rounded(Color.argb(alphas[2], r, g, b), ringRadiusPx + near),
                    Views.ring(Color.argb(ka, Color.red(k), Color.green(k), Color.blue(k)),
                        ringRadiusPx + keyOutset, theme.focusKeylinePx),
                ))
                halo.setLayerInset(0, inset(outer), inset(outer), inset(outer), inset(outer))
                halo.setLayerInset(1, inset(middle), inset(middle), inset(middle), inset(middle))
                halo.setLayerInset(2, inset(near), inset(near), inset(near), inset(near))
                halo.setLayerInset(3, inset(keyOutset), inset(keyOutset), inset(keyOutset), inset(keyOutset))
                halo
            }
            // A compact control in a glow pack takes one crisp keyline in the glow colour — the same
            // liquid rim as the artwork's halo, just without the halo, which would smear a text
            // button into its neighbours. A thin warm-dark keyline rides just outside it so the
            // state still reads on a light pack, where the glow colour is a hair off the page.
            style == "glow" -> {
                val k = theme.focusKeylineColor
                val ka = if (theme.focusKeylineExplicit) 255 else 224
                val outset = theme.px(5f)
                LayerDrawable(arrayOf(
                    Views.ring(theme.focusGlowColor, ringRadiusPx, maxOf(2, theme.focusRingPx)),
                    Views.ring(Color.argb(ka, Color.red(k), Color.green(k), Color.blue(k)),
                        ringRadiusPx + outset, maxOf(1, theme.focusKeylinePx)),
                )).apply { setLayerInset(1, -outset, -outset, -outset, -outset) }
            }
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

        // Press-and-hold, the reference app's gesture: a card that answers a hold (`isLongClickable`)
        // decides on release, so a quick OK still opens the title and holding it past the platform's
        // long-press timeout runs the hold action instead — add to My List, leave Continue watching.
        // Key repeat intervals differ wildly across TV remotes, so the decision is made on a clock,
        // not by counting repeat events: every device delivers a DOWN and eventually an UP.
        @Suppress("DEPRECATION")
        val holdMs = android.view.ViewConfiguration.getLongPressTimeout().toLong()
        var holdTimer: Runnable? = null
        var holdFired = false
        // The clock lives on the main looper, not on the card: a shelf recycles its views, and a view
        // that is detached mid-press would take the pending hold with it.
        val holdHandler = android.os.Handler(android.os.Looper.getMainLooper())
        fun cancelHold() {
            holdTimer?.let { holdHandler.removeCallbacks(it) }
            holdTimer = null
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
                )) {
                if (code in setOf(
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE,
                    ) && holdTimer != null
                ) {
                    // Released before the hold clock ran out: an ordinary press, decided now.
                    cancelHold()
                    actionable(focusable)?.performClick()
                }
                holdFired = false
                true
            } else if (ev.action != KeyEvent.ACTION_DOWN) {
                false
            } else {
                // A row may want an arrow for itself — the hero's Featured rail wraps at its ends to
                // keep the loop illusion — before the arrow falls through to the system's focus search.
                arrow?.let { if (it(code)) return@setOnKeyListener true }
                when (code) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE, -> {
                        val act = actionable(focusable) ?: return@setOnKeyListener false
                        if (holdFired) return@setOnKeyListener true   // the hold already answered this press
                        if (act.isLongClickable && holdTimer == null && ev.repeatCount == 0 && !ev.isLongPress) {
                            holdTimer = Runnable {
                                holdTimer = null
                                holdFired = true
                                act.performLongClick()
                            }.also { holdHandler.postDelayed(it, holdMs) }
                        } else if (act.isLongClickable && (ev.repeatCount > 0 || ev.isLongPress)) {
                            // Some remotes and input stacks report the hold themselves (a repeat, or the
                            // long-press flag) rather than leaving the press open for us to time. Either
                            // one arrives only after the platform's own long-press delay, so it is the
                            // same decision the clock makes — reached by a different road.
                            cancelHold()
                            holdFired = true
                            act.performLongClick()
                        } else if (holdTimer == null) {
                            act.performClick()
                        }
                        true
                    }
                    else -> false
                }
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
            // A hold that lost the focus mid-press belongs to a card the viewer walked away from:
            // drop it rather than fire an action on a tile they are no longer pointing at.
            if (!has) cancelHold()
            paintRing(has)
            onFocus?.invoke(has)
            val target = if (has) {
                val cap = if (scaleTo > 1f) scaleTo else theme.focusScale
                if (style == "lift") Math.max(cap, 1.045f) else cap
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
