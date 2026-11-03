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

    data class Choice(val label: String, val run: () -> Unit)

    /**
     * Opens the sheet over [activity]. [title] is the card's title so the viewer can see which tile
     * the sheet belongs to. Returns false when a sheet is already open, so a second hold cannot
     * stack two of them.
     */
    fun open(activity: Activity, app: AppContext, title: String, choices: List<Choice>): Boolean {
        if (choices.isEmpty() || activity.isFinishing || open != null) return false
        val theme = app.theme
        val anchor = activity.currentFocus

        val scrim = FrameLayout(activity).apply {
            setBackgroundColor(Color.argb(0xA6, 0, 0, 0))
            isClickable = true
            isFocusable = true
            setOnClickListener { close() }
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
            Views.text(app, "sub", 16f, theme.ink3, "Choose what to do with this title"),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val rows = ArrayList<View>(choices.size)
        for ((i, c) in choices.withIndex()) {
            val row = TextView(activity).apply {
                id = View.generateViewId()
                text = c.label
                setTextColor(theme.ink)
                textSize = 20f
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
        // shelf behind the scrim; UP/DOWN step through the choices and stop at the ends.
        if (rows.isNotEmpty()) {
            for (row in rows) {
                row.nextFocusLeftId = row.id
                row.nextFocusRightId = row.id
            }
            rows.first().nextFocusUpId = rows.first().id
            rows.last().nextFocusDownId = rows.last().id
        }

        val note = Views.text(app, "sub", 15f, theme.ink3, "Back closes without changing anything")
        panel.addView(
            note,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        scrim.addView(panel, FrameLayout.LayoutParams(
            theme.px(560f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ))
        scrim.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                close(); true
            } else false
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

    fun close() {
        val sheet = open ?: return
        open = null
        (sheet.parent as? ViewGroup)?.removeView(sheet)
        returnFocus?.let { v -> if (v.isAttachedToWindow) v.requestFocus() }
        returnFocus = null
    }

    fun isOpen(): Boolean = open != null
}
