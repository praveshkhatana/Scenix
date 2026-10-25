package com.scenix.tv.core

import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.util.Log
import android.widget.TextView

/**
 * The PIN modal — the mockup's `#pin` screen rendered as an overlay: 780 px card, title, subtitle,
 * the dots, and a 3x3 keypad with Clear / 0 / OK.
 *
 * Used twice: the profile picker gates a kid profile with it, and Settings uses it to set or change
 * the PIN. The PIN itself is only ever compared to a SHA-256 hash in [Prefs] — the TV stores no
 * plaintext, and the hub never sees it.
 */
class PinPad(
    private val ctx: AppContext,
    private val title: String,
    private val subtitle: String,
    private val onDone: (String) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val theme = ctx.theme
    private val maxLen = theme.token("pin", "length", 4.0).toInt().coerceIn(4, 8)
    private val entered = StringBuilder(maxLen)
    private lateinit var dots: LinearLayout
    private var status: TextView? = null

    /** Full-screen scrim with the card centred; add this to the screen's container. */
    fun view(): View {
        val scrim = FrameLayout(ctx.app)
        scrim.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        scrim.setBackgroundColor(theme.color("modalScrim", "#E6000000"))
        // FOCUS_AFTER_DESCENDANTS means a focusable scrim is still not a focus stop: the D-pad walks
        // the keypad first. The scrim takes focus only as the backstop when a digit key cannot, which
        // matters because key events reach a listener only along the focused view's ancestor chain.
        // If the profile tile behind the pad keeps focus, the pad is deaf: that was the original bug.
        scrim.isFocusable = true
        scrim.isFocusableInTouchMode = true
        scrim.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        // The remote's number keys type straight into the pad — much faster than walking a 3x4 grid,
        // and it works even if focus somehow ends up outside the keypad.
        scrim.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when {
                code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                    typed((code - KeyEvent.KEYCODE_0).toString()); true
                }
                code == KeyEvent.KEYCODE_DEL -> {
                    if (entered.isNotEmpty()) { entered.setLength(entered.length - 1); redrawDots() }
                    true
                }
                code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    submit(); true
                }
                // D-pad stays with the keypad views themselves.
                else -> false
            }
        }

        val card = LinearLayout(ctx.app)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(theme.px(48f), theme.px(44f), theme.px(48f), theme.px(40f))
        card.background = Views.rounded(theme.surface, theme.radius("modal", 10f), theme.px(1f), theme.line)
        val width = Math.round(theme.type("modalWidth", 780f))
        val lp = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        scrim.addView(card, lp)

        card.addView(
            Views.text(ctx, "h3", 40f, theme.ink, title, bold = true, gravity = Gravity.CENTER),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val sub = Views.text(ctx, "sub", 24f, theme.ink2, subtitle, gravity = Gravity.CENTER, maxLines = 2)
        val subLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        subLp.topMargin = theme.px(10f)
        card.addView(sub, subLp)

        dots = LinearLayout(ctx.app)
        dots.orientation = LinearLayout.HORIZONTAL
        dots.gravity = Gravity.CENTER
        val dotsLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dotsLp.topMargin = theme.px(30f)
        dotsLp.bottomMargin = theme.px(26f)
        card.addView(dots, dotsLp)
        redrawDots()

        card.addView(keypad(scrim))

        status = Views.text(ctx, "meta", 20f, theme.brand, "", gravity = Gravity.CENTER)
        card.addView(
            status!!,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val actions = LinearLayout(ctx.app)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.CENTER
        val aLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        aLp.topMargin = theme.px(18f)
        card.addView(actions, aLp)

        val cancel = key("Cancel", text = true)
        cancel.setOnClickListener { onCancel() }
        actions.addView(cancel, keyLp())
        return scrim
    }

    private fun keypad(scrim: View): View {
        val grid = GridLayout(ctx.app)
        grid.columnCount = 3
        grid.rowCount = 4
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.CENTER
        grid.layoutParams = lp

        for (n in 1..9) grid.addView(digit(n), cellLp())
        grid.addView(key("Clear", text = true).apply { setOnClickListener { entered.setLength(0); redrawDots(); focusDigits(grid) } }, cellLp())
        grid.addView(digit(0), cellLp())
        grid.addView(key("OK", text = true).apply { setOnClickListener { submit() } }, cellLp())
        // Walking left from the first column used to leave the keypad entirely - focus went to the
        // screen behind the pad and the pad then heard nothing. Pin every cell's neighbours so the
        // D-pad circles inside the 3x4 grid instead.
        for (i in 0 until grid.childCount) {
            val cell = grid.getChildAt(i)
            if (cell.id == View.NO_ID) cell.id = View.generateViewId()
        }
        for (i in 0 until grid.childCount) {
            val cell = grid.getChildAt(i)
            val row = i / 3
            val col = i % 3
            cell.nextFocusLeftId = grid.getChildAt(row * 3 + (col + 2) % 3).id
            cell.nextFocusRightId = grid.getChildAt(row * 3 + (col + 1) % 3).id
            cell.nextFocusUpId = grid.getChildAt(((row + 3) % 4) * 3 + col).id
            cell.nextFocusDownId = grid.getChildAt(((row + 1) % 4) * 3 + col).id
        }

        grid.post { focusDigits(grid) }
        // The real focus hand-off: `grid.post {}` above runs before the overlay is added to the
        // window, so that request silently failed and the pad opened with nothing focused.
        scrim.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                active = this@PinPad
                v.post {
                    // Release whatever the screen behind the pad was holding. Key events travel up the
                    // focused view's ancestors, so a tile left focused outside the scrim keeps the whole
                    // keypad deaf to the remote, and requestFocus inside the pad cannot win against it.
                    (v.parent as? ViewGroup)?.findFocus()?.clearFocus()
                    if (!focusDigits(grid)) v.requestFocus()
                }
            }
            override fun onViewDetachedFromWindow(v: View) {
                if (active === this@PinPad) active = null
            }
        })
        return grid
    }

    /** A digit pressed on the remote's number pad, or tapped on a key. */
    /** Called by MainActivity for a number key on the remote, whatever has focus. */
    fun remoteDigit(digit: String) = typed(digit)

    private fun typed(digit: String) {
        if (entered.length < maxLen) {
            entered.append(digit)
            redrawDots()
        }
        if (entered.length >= maxLen) submit()
    }

    private fun cellLp(): GridLayout.LayoutParams {
        val p = theme.px(8f)
        return GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
            setMargins(p, p, p, p)
            width = Math.round(theme.type("keyWidth", 110f))
            height = Math.round(theme.type("keyHeight", 96f))
        }
    }

    private fun keyLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginStart = theme.px(10f)
        lp.marginEnd = theme.px(10f)
        return lp
    }

    private fun digit(n: Int): View {
        val key = key(n.toString())
        key.setOnClickListener { typed(n.toString()) }
        return key
    }

    private fun key(label: String, text: Boolean = false): View {
        val b = Views.text(
            ctx, "key", 34f, if (text) theme.ink else theme.ink, label,
            bold = !text, gravity = Gravity.CENTER,
        )
        b.isFocusable = true
        // requestFocus() silently fails in touch mode unless the view opts in, and a pad opened with a
        // tap (or a mouse) starts the whole app in touch mode.
        b.isFocusableInTouchMode = true
        b.isClickable = true
        b.background = Views.rounded(if (text) theme.surface2 else theme.color("key", "#2E2E2E"), theme.radius("key", 8f))
        Focus.attach(b, b, ctx, theme.radius("key", 8f), scale = false)
        return b
    }

    private fun focusDigits(grid: GridLayout): Boolean =
        (grid.getChildAt(0))?.requestFocus() == true

    private fun redrawDots() {
        dots.removeAllViews()
        val size = Math.round(theme.type("pinDot", 20f))
        for (i in 0 until maxLen) {
            val dot = View(ctx.app)
            val filled = i < entered.length
            dot.background = if (filled) Views.rounded(theme.brand, size / 2)
            else Views.ring(theme.color("lineStrong", "#66FFFFFF"), size / 2, theme.px(2f))
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginStart = theme.px(10f)
            lp.marginEnd = theme.px(10f)
            dots.addView(dot, lp)
        }
    }

    private fun submit() {
        if (entered.length < maxLen) {
            entered.setLength(0)
            redrawDots()
            showError("Enter $maxLen digits")
            return
        }
        if (entered.length < maxLen) {
            status?.text = "Enter all $maxLen digits"
            return
        }
        onDone(entered.toString())
    }

    /** Used by callers that need to reset after a wrong PIN. */
    fun clear() {
        entered.setLength(0)
        redrawDots()
        status?.text = ""
    }

    fun showError(msg: String) {
        status?.text = msg
        clear()
    }

    companion object {
        /**
         * The pad currently on screen, if any. The remote's number keys arrive at MainActivity but
         * are dropped on their way through the view tree, so the Activity feeds them to this
         * instance instead of hoping the keypad's own views hear them.
         */
        @JvmStatic
        var active: PinPad? = null

        /** Remove an overlay a screen added for this pad. */
        fun close(container: FrameLayout, overlay: View) {
            container.removeView(overlay)
        }
    }
}
