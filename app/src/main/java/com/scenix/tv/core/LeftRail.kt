package com.scenix.tv.core

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * The host overlay menu on the left edge.
 *
 * It is a real component, not a drawing: BrowseActivity and the design-system Studio both mount
 * this view and get the same rows, focus pill, scrim and close-onto-the-tile behaviour. The host
 * supplies the destinations (Games and Live TV are omitted when their add-ons are absent) and the
 * routes; the rail owns the interaction.
 */
class LeftRail(
    private val activity: Activity,
    private val app: AppContext,
    entries: List<Entry>,
) : FrameLayout(activity) {

    /** One destination row. [route] is the section id the rail should stand on when it opens. */
    data class Entry(
        val label: String,
        val iconRes: Int,
        val route: String,
        val run: () -> Unit,
    )

    private val theme = app.theme
    private val width = theme.px(340f)
    private val scrim: View
    private val panel: LinearLayout
    private val rows = ArrayList<View>()
    private var returnFocus: View? = null

    var isOpen: Boolean = false
        private set

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false

        scrim = View(activity).apply {
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { close() }
        }
        addView(scrim, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(245, 9, 10, 14))
            setPadding(theme.px(24f), theme.px(26f), theme.px(18f), theme.px(24f))
            visibility = View.GONE
            translationX = -width.toFloat()
            elevation = theme.px(18f).toFloat()
        }
        panel.addView(
            Views.text(app, "li", 20f, theme.ink2, "SCENIX", bold = true).apply {
                letterSpacing = 0.18f
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = theme.px(16f)
                bottomMargin = theme.px(18f)
            },
        )
        for (entry in entries) {
            val row = railRow(entry)
            rows += row
            panel.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, theme.px(66f)
            ).apply { bottomMargin = theme.px(4f) })
        }
        addView(panel, LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
    }

    fun open(section: String) {
        if (isOpen) return
        isOpen = true
        returnFocus = activity.currentFocus
        scrim.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        panel.animate().translationX(0f).setDuration(180L).start()
        val wanted = when (section) {
            "movies" -> "Movies"
            "series" -> "Series"
            "games" -> "Games"
            "live" -> "Live TV"
            else -> "Home"
        }
        val target = rows.firstOrNull { it.contentDescription == wanted } ?: rows.firstOrNull()
        target?.post {
            if (isOpen && !activity.isFinishing && !activity.isDestroyed) target.requestFocus()
        }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        scrim.visibility = View.GONE
        panel.animate().translationX(-width.toFloat()).setDuration(160L).withEndAction {
            if (!isOpen) panel.visibility = View.GONE
        }.start()
        returnFocus?.takeIf { it.isAttachedToWindow }?.requestFocus()
        returnFocus = null
    }

    fun move(delta: Int) {
        if (rows.isEmpty()) return
        val at = rows.indexOfFirst { it.isFocused }
        val next = if (at < 0) 0 else (at + delta).coerceIn(0, rows.lastIndex)
        rows.getOrNull(next)?.requestFocus()
    }

    fun focusedRow(): View? = rows.firstOrNull { it.isFocused }

    private fun railRow(entry: Entry): View {
        val radius = theme.px(12f)
        val idleInk = theme.color("railInk", "#F4F8FC")
        val idleIcon = theme.color("railInk2", "#B9C6D2")
        val focusInk = theme.color("railFocusInk", "#0A0F14")
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.px(16f), 0, theme.px(16f), 0)
            isFocusable = true
            isClickable = true
            contentDescription = entry.label
            background = Views.rounded(Color.TRANSPARENT, radius)
        }
        val icon = ImageView(activity).apply {
            setImageResource(entry.iconRes)
            setColorFilter(idleIcon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        row.addView(icon, LinearLayout.LayoutParams(theme.px(24f), theme.px(24f)))
        val text = Views.text(app, "li", 23f, idleInk, entry.label, bold = true)
        text.setPadding(theme.px(18f), 0, 0, 0)
        row.addView(text, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        Focus.attach(row, row, app, radius, scale = false, elevate = false,
            // Choosing a destination closes the rail first: leaving it up would cover the page the
            // viewer just asked for, and coming back from another activity would still show it.
            onClick = { close(); entry.run() },
            onFocus = { focused ->
                row.background = if (focused) Views.rounded(Color.WHITE, radius)
                else Views.rounded(Color.TRANSPARENT, radius)
                text.setTextColor(if (focused) focusInk else idleInk)
                icon.setColorFilter(if (focused) focusInk else idleIcon)
            })
        return row
    }
}
