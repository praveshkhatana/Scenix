package com.scenix.tv.plugins.player

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Views

/**
 * One row of the playback settings panel. The player owns the list, so a new setting — picture mode,
 * audio delay, whatever — is one more `SettingsRow(...)` in the list it builds. This file never
 * changes for that.
 */
data class SettingsRow(
    val id: String,
    val label: String,
    val value: String,
    val note: String? = null,           // printed under the row: a reason, or "not available on this TV"
    val enabled: Boolean = true,        // false greys it out but keeps it visible, honestly
    val onClick: () -> Unit,
)

/**
 * The D-pad playback settings panel: a right-hand column over the picture, same idea as the web
 * players' "Playback Settings" sheet. Rows cycle their value on OK, because a submenu of submenus is
 * miserable on a remote; the value column always shows where you are.
 *
 * Sizes come from theme tokens (`panel.*`), so the panel can be restyled without touching code.
 */
class PlaybackPanel(
    private val app: AppContext,
    private val title: String,
    private val rows: () -> List<SettingsRow>,
) {
    private val theme get() = app.theme

    private var scroll: ScrollView? = null
    private var list: LinearLayout? = null
    private var firstFocus: View? = null

    /** Built rows by id, so a refresh can hand focus back to the row you were on. */
    private var built = ArrayList<Pair<String, View>>()

    fun view(): View {
        val wrap = LinearLayout(app.app)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(
            theme.space("panelPad", 28f), theme.space("panelPadV", 24f),
            theme.space("panelPad", 28f), theme.space("panelPadV", 24f),
        )
        wrap.background = Views.rounded(
            theme.color("panelScrim", "#E6000000"),
            theme.radius("panel", 10f),
            theme.px(1f),
            theme.color("lineStrong", "#8FFFFFFF"),
        )

        wrap.addView(
            Views.text(app, "panelTitle", 26f, theme.ink, title, bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val sv = ScrollView(app.app)
        val rowsHost = LinearLayout(app.app)
        rowsHost.orientation = LinearLayout.VERTICAL
        sv.addView(
            rowsHost,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        wrap.addView(
            sv,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        scroll = sv
        list = rowsHost
        fill()
        return wrap
    }

    /** Re-read the row list (after a value changed) without rebuilding the panel itself. */
    fun refresh() { fill() }

    /** Put D-pad focus on the first usable row, so Down/Up walk the sheet. */
    fun focusFirst(): Boolean = firstFocus?.requestFocus() == true

    private fun fill() {
        val host = list ?: return
        val wasScroll = scroll?.scrollY ?: 0
        // Which row the viewer was standing on, so the rebuild can put them back there. Dropping
        // focus here is what made the panel feel like it "only worked once": after one OK the key
        // presses went nowhere because no view in the sheet had focus any more.
        val standingOn = built.firstOrNull { it.second.isFocused }?.first
        host.removeAllViews()
        firstFocus = null
        built = ArrayList()
        for (row in rows()) {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = theme.space("panelRowGap", 10f)
            host.addView(rowView(row), lp)
        }
        if (standingOn != null) {
            built.firstOrNull { it.first == standingOn }?.second?.let { v ->
                v.post { v.requestFocus() }
            }
        }
        scroll?.post { scroll?.scrollTo(0, wasScroll) }
    }

    private fun rowView(row: SettingsRow): View {
        val col = LinearLayout(app.app)
        col.orientation = LinearLayout.VERTICAL

        val line = LinearLayout(app.app)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.CENTER_VERTICAL
        line.setPadding(theme.px(20f), theme.px(16f), theme.px(20f), theme.px(16f))
        line.background = Views.rounded(theme.color("panelRow", "#1FFFFFFF"), theme.radius("listItem", 6f))
        line.isFocusable = row.enabled
        line.isClickable = true

        val label = Views.text(
            app, "panelRow", 23f,
            if (row.enabled) theme.ink else theme.ink3,
            row.label,
        )
        line.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val value = Views.text(
            app, "panelRow", 23f,
            when {
                !row.enabled -> theme.ink3
                row.value.isBlank() -> theme.ink3
                else -> theme.color("panelValue", "#F2C33A")
            },
            row.value,
            bold = true,
            gravity = Gravity.END,
            maxLines = 2,
        )
        line.addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        col.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (!row.note.isNullOrBlank()) {
            val note = Views.text(app, "panelNote", 18f, theme.ink3, row.note, maxLines = 3)
            val nlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            nlp.topMargin = theme.px(6f)
            nlp.marginStart = theme.px(20f)
            col.addView(note, nlp)
        }

        Focus.attach(
            line, line, app,
            ringRadiusPx = theme.radius("listItem", 6f),
            scale = false,
            onClick = { row.onClick() },
        )
        built.add(row.id to line)
        if (firstFocus == null && row.enabled) firstFocus = line
        return col
    }
}
