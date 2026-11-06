package com.scenix.tv.core

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.scenix.tv.LiquidGlassKit

/**
 * The confirmation behind a press-and-hold.
 *
 * Holding OK on a card used to act the instant the button came up: a tile left Continue watching
 * before the viewer had decided anything, and a long hold on a remote read as several holds. The
 * reference product asks first — the hold opens a short option list and the choice is made with a
 * deliberate second OK, which is also what makes the gesture safe to arm on every card.
 *
 * The sheet is the app's own furniture, not a system dialog: the pack's surface, radius and focus
 * ring, one row per action the card actually supports. BACK, or pressing the scrim, leaves the card
 * exactly as it was.
 */
object CardActionSheet {

    private var open: FrameLayout? = null
    private var returnFocus: View? = null

    /** The page the sheet stood in front of, and the focus rule it had before. */
    private var page: ViewGroup? = null
    private var pageFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS

    data class Choice(val label: String, val run: () -> Unit)

    /**
     * Opens the sheet over [activity]. [title] is the card's title so the viewer can see which tile
     * the sheet belongs to. Returns false when a sheet is already open, so a second hold cannot
     * stack two of them.
     */
    fun open(activity: Activity, app: AppContext, title: String, choices: List<Choice>): Boolean {
        if (choices.isEmpty() || activity.isFinishing || activity.isDestroyed) return false
        // A sheet is refused while one is up, so that a second hold cannot stack two of them. But a
        // sheet left behind by a screen that went away under it — profile switch, theme change, the
        // activity recreated while the scrim was still on screen — must not silence every hold that
        // comes after it. Only a scrim that is actually attached still counts as open.
        open?.let { if (it.isAttachedToWindow) return false else open = null }
        val theme = app.theme
        val anchor = activity.currentFocus

        val scrim = FrameLayout(activity).apply {
            setBackgroundColor(Color.argb(0xA6, 0, 0, 0))
            isClickable = true
            // The scrim is not a D-pad stop: it only answers a pointer that taps it. (A focusable
            // scrim took the ring from the rows and then closed the sheet on an ordinary OK.)
            isFocusable = false
            setOnClickListener { close() }
            // Whatever takes the window down — Back, a recreation, the activity ending — takes the
            // sheet's state with it and hands the page back its focus, so the next hold is never
            // refused by a sheet nobody can see.
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    if (open === v) release()
                }
            })
        }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Views.rounded(theme.surface, theme.radius("panel", 14f),
                theme.px(1f), theme.line)
            setPadding(theme.px(22f), theme.px(18f), theme.px(22f), theme.px(16f))
        }
        panel.addView(
            Views.text(app, "panelTitle", 24f, theme.ink, title, bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        panel.addView(
            Views.text(app, "panelNote", 18f, theme.ink3, "Choose what to do with this title"),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val rows = ArrayList<View>(choices.size)
        for ((i, c) in choices.withIndex()) {
            val row = TextView(activity).apply {
                id = View.generateViewId()
                text = c.label
                setTextColor(theme.ink)
                // The pack's own type scale, in device pixels, like every other label in this app.
                // A bare `textSize = 20f` is SP: on a panel running a large font scale the rows grew
                // past the sheet that holds them and the footer was pushed off the bottom.
                setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX,
                    theme.type("panelRow", 23f),
                )
                setTypeface(typeface, Typeface.BOLD)
                setPadding(theme.px(16f), theme.px(14f), theme.px(16f), theme.px(14f))
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    close()
                    c.run()
                }
            }
            val radius = theme.radius("listItem", 8f)
            Focus.attach(row, null, app, radius, scale = false, elevate = false,
                onFocus = { f ->
                    row.background = Views.rounded(
                        if (f) Color.argb(0x2E, 255, 255, 255) else Color.TRANSPARENT, radius,
                    )
                })
            row.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = theme.px(if (i == 0) 18f else 8f) }
            rows.add(row)
            panel.addView(row)
        }
        // A TV sheet is an ordered remote path. LEFT/RIGHT must not walk out of the panel into the
        // shelf behind the scrim, and UP/DOWN must step through the choices rather than let the
        // system's geometric search find a tile behind the scrim — which left the sheet standing on
        // screen while the page behind it answered OK.
        if (rows.isNotEmpty()) {
            for ((i, row) in rows.withIndex()) {
                row.nextFocusLeftId = row.id
                row.nextFocusRightId = row.id
                row.nextFocusUpId = rows[maxOf(0, i - 1)].id
                row.nextFocusDownId = rows[minOf(rows.size - 1, i + 1)].id
            }
        }

        val note = Views.text(app, "panelNote", 18f, theme.ink3,
            "Back closes without changing anything")
        panel.addView(
            note,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = theme.px(12f) },
        )

        // In the Glass pack a sheet IS a sheet of glass: the same lens the page's chrome wears,
        // standing over the dimmed picture, its rim band bending what is behind it. Every other pack
        // keeps the painted plate the contract describes.
        val face: View = if (LiquidGlassKit.isGlass(app))
            LiquidGlassKit.wrap(app, panel, theme.radius("panel", 14f))
        else panel
        scrim.addView(face, FrameLayout.LayoutParams(
            theme.px(560f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ))
        scrim.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                close(); true
            } else false
        }
        // The sheet owns the remote while it is up. The scrim only paints over the page — it does not
        // take the page out of the focus graph — so a D-pad step that the geometric search sent
        // looking for the next thing below a row found a shelf tile behind the scrim, and that tile
        // answered OK: the sheet stayed on screen while a different title opened. The page cannot
        // take focus until the sheet is down.
        val stage = activity.findViewById<ViewGroup>(android.R.id.content)
        page = stage
        stage?.let {
            pageFocusability = it.descendantFocusability
            it.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val root = activity.window.decorView as ViewGroup
        root.addView(scrim, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        open = scrim
        returnFocus = anchor
        panel.post { rows.firstOrNull()?.requestFocus() }
        return true
    }

    /**
     * The sheet is gone: the page behind it may hold focus again, and focus returns to the card the
     * sheet was opened from. Safe to call twice, and from whichever road the sheet left by.
     */
    private fun release() {
        open = null
        page?.descendantFocusability = pageFocusability
        page = null
    }

    fun close() {
        val sheet = open ?: return
        (sheet.parent as? ViewGroup)?.removeView(sheet)
        release()
        returnFocus?.let { v -> if (v.isAttachedToWindow) v.requestFocus() }
        returnFocus = null
    }

    fun isOpen(): Boolean = open != null
}
