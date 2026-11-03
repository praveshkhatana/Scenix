package com.scenix.tv

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Screens
import com.scenix.tv.core.Views

/**
 * The Android catalogue's real component builders.
 *
 * Every function here calls the same code the television runs: [Views], [Focus], [GlassKit],
 * [KidsKit] and [Screens]. Nothing is re-drawn for the catalogue — if a component is still owned by
 * an Activity, it is marked `blocked` in `design-system/studio.json` instead of being approximated
 * here. The player is a screen, so its entry launches the real PlayerActivity in preview mode.
 */
internal object StudioCatalog {

    fun build(activity: Activity, app: AppContext, entry: StudioEntry, state: String): View? =
        when (entry.builder) {
            "atoms.type" -> atomsType(activity, app)
            "atoms.focus" -> atomsFocus(activity, app, state)
            "glass.nav" -> glassNav(activity, app, state)
            "glass.chips" -> glassChips(activity, app, state)
            "kids.chips" -> kidsChips(activity, app)
            "settings.rows" -> settingsRows(activity, app, state)
            "screens.topbar" -> screenTopBar(activity, app)
            else -> null
        }

    // ---- primitives ---------------------------------------------------------------------

    private fun stage(context: Activity, app: AppContext): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.theme.px(48f), app.theme.px(36f), app.theme.px(48f), app.theme.px(36f))
            setBackgroundColor(app.theme.bg)
        }

    private fun atomsType(context: Activity, app: AppContext): View {
        val theme = app.theme
        val col = stage(context, app)
        val rows = listOf(
            Triple("h1", 58f, "H1 · The quick brown fox"),
            Triple("h2", 44f, "H2 · The quick brown fox"),
            Triple("h3", 34f, "H3 · The quick brown fox"),
            Triple("rowTitle", 19f, "ROW TITLE · CONTINUE WATCHING"),
            Triple("li", 23f, "List item · Breaking Bad"),
            Triple("liMeta", 20f, "List meta · 2008"),
            Triple("sub", 19f, "Sub · A chemistry teacher turns to manufacturing."),
            Triple("meta", 16f, "Meta · S01E04 · 52m · 99% match"),
            Triple("badge", 15f, "BADGE · TV-MA"),
        )
        for ((token, size, text) in rows) {
            col.addView(Views.text(app, token, size, theme.ink, text, bold = token in setOf("h1", "h2", "h3", "rowTitle")))
        }
        val inks = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, theme.px(20f), 0, 0)
        }
        for ((name, color) in listOf("ink" to theme.ink, "ink2" to theme.ink2, "ink3" to theme.ink3)) {
            inks.addView(Views.text(app, "meta", 16f, color, name), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        col.addView(inks)
        return col
    }

    private fun atomsFocus(context: Activity, app: AppContext, state: String): View {
        val theme = app.theme
        val col = stage(context, app)
        col.addView(Views.text(app, "meta", 16f, theme.ink3, "The pack's focus language, on one plate."))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, theme.px(28f), 0, 0)
        }
        val plates = ArrayList<View>()
        repeat(3) { index ->
            val label = "Focus ${index + 1}"
            val plate = TextView(context).apply {
                text = label
                setTextColor(theme.ink)
                textSize = 22f
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                background = Views.rounded(theme.surface2, theme.radius("card", 16f), theme.px(1f), theme.line)
            }
            Focus.attach(plate, plate, app, theme.radius("card", 16f), scale = true, elevate = true)
            row.addView(plate, LinearLayout.LayoutParams(theme.px(280f), theme.px(140f)).apply {
                marginEnd = theme.px(24f)
            })
            plates.add(plate)
        }
        col.addView(row)
        if (state == "focused") plates.getOrNull(1)?.post { plates[1].requestFocus() }
        return col
    }

    // ---- Glass kit ----------------------------------------------------------------------

    private fun glassNav(context: Activity, app: AppContext, state: String): View {
        val theme = app.theme
        val col = stage(context, app)
        col.addView(Views.text(app, "meta", 16f, theme.ink3, "GlassKit pill, exactly as the Glass header builds it."))
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Views.rounded(theme.color("pill", "rgba(8,16,28,0.86)"), theme.px(999f))
            setPadding(theme.px(6f), theme.px(6f), theme.px(6f), theme.px(6f))
            id = View.generateViewId()
        }
        val stops = listOf("Home", "Movies", "Series", "Games", "Live")
        val views = ArrayList<View>()
        stops.forEachIndexed { index, label ->
            val stop = GlassKit.segment(context, app, label, active = state == "active" && index == 0) {}
            views.add(stop)
            pill.addView(stop, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        pill.addView(GlassKit.searchKnob(context, app) {}, LinearLayout.LayoutParams(theme.px(48f), theme.px(48f)).apply {
            marginStart = theme.px(6f)
        })
        col.addView(pill, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, theme.space("navBand", 68f)
        ).apply { topMargin = theme.px(26f) })
        if (state == "focused") views.firstOrNull()?.post { views.first().requestFocus() }
        return col
    }

    private fun glassChips(context: Activity, app: AppContext, state: String): View {
        val theme = app.theme
        val col = stage(context, app)
        col.addView(Views.text(app, "meta", 16f, theme.ink3, "Shelf-jump chips on the Glass wall."))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, theme.px(24f), 0, 0)
        }
        val labels = listOf("For you", "Movies", "Series", "Games", "Live")
        labels.forEachIndexed { index, label ->
            val chip = GlassKit.chip(context, app, label, state == "selected" && index == 0,
                theme.space("chipHeight", 104f)) {}
            row.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, theme.space("chipHeight", 104f)
            ).apply { marginEnd = theme.px(16f) })
        }
        col.addView(row)
        return col
    }

    private fun kidsChips(context: Activity, app: AppContext): View {
        val theme = app.theme
        val col = stage(context, app)
        col.addView(Views.text(app, "meta", 16f, theme.ink3, "KidsKit chips, as the Kids rails draw them."))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, theme.px(24f), 0, 0)
        }
        for (label in listOf("Kids videos", "Kids Movies", "Kids Series")) {
            row.addView(KidsKit.chip(app, KidsKit.chipGlyph(label), label), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = theme.px(16f) })
        }
        col.addView(row)
        return col
    }

    // ---- screens ------------------------------------------------------------------------

    private fun settingsRows(context: Activity, app: AppContext, state: String): View {
        val theme = app.theme
        val col = stage(context, app)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        Screens.section(context, app, list, "Your watching")
        Screens.addRow(context, app, list, "Audio language", "Hindi",
            "The hub mixes this language into copies that lack it", chevron = true) {}
        Screens.addRow(context, app, list, "Subtitles", "Hindi", "Preferred subtitle language", chevron = true) {}
        Screens.addRow(context, app, list, "Quality ceiling", "1080P", "Largest copy this TV should be offered", chevron = true) {}
        Screens.addRow(context, app, list, "Home rows", "31 of 32 shown", "This profile's shelves — hide, show or reorder", chevron = true) {}
        col.addView(list)
        if (state == "focused") {
            list.post { (1 until list.childCount).mapNotNull { list.getChildAt(it) as? View }
                .firstOrNull { it.isFocusable }?.requestFocus() }
        }
        return col
    }

    private fun screenTopBar(context: Activity, app: AppContext): View {
        val theme = app.theme
        val col = stage(context, app)
        col.setBackgroundColor(theme.bg)
        val (bar, status) = Screens.topBar(context, app, "Settings") {}
        status.text = "All changes saved"
        col.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(96f)
        ))
        return col
    }

    // ---- overlays / screens handled by the activity -------------------------------------

    fun openHoldSheet(activity: Activity, app: AppContext) {
        com.scenix.tv.core.CardActionSheet.open(activity, app, "Breaking Bad", listOf(
            com.scenix.tv.core.CardActionSheet.Choice("Add to My List") {},
            com.scenix.tv.core.CardActionSheet.Choice("Remove from Continue watching") {},
        ))
    }

    fun launchPlayerPreview(activity: Activity, state: String, themeId: String) {
        activity.startActivity(android.content.Intent(activity, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_PREVIEW, true)
            .putExtra(PlayerActivity.EXTRA_PREVIEW_STATE, state)
            .putExtra(PlayerActivity.EXTRA_THEME, themeId)
            .putExtra(PlayerActivity.EXTRA_TITLE, "The Last of Us"))
    }
}
