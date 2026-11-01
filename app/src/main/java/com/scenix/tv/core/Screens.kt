package com.scenix.tv.core

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The design language of the SECONDARY screens — settings, add-ons, packs, profiles, pins, text
 * entry. These pages have no hero and no rails, but a theme pack is a complete pack: they must
 * still sit on the pack's wall, paint their rows with the pack's tile treatment (fill, stroke,
 * radius, height, gap) and hand focus through the pack's keyline. Every number is read from the
 * pack first (`colors.listFill`/`listStroke`, `radii.listItem`, `spacing.listItem`/`listGap`);
 * the fallbacks here are only what an older or third-party pack that never declared them gets.
 */
object Screens {

    fun listRadius(app: AppContext): Int = app.theme.radius("listItem", 10f)
    fun listHeight(app: AppContext): Int = app.theme.space("listItem", 88f)
    fun listGap(app: AppContext): Int = app.theme.space("listGap", 10f)

    /**
     * The ground a secondary page stands on. Clean and Kids print their Home straight on the
     * wallpaper, so their settings screens must carry the same wall or the pack breaks the moment
     * you open Settings. The console pack gets its deep navy gradient; Apple and Netflix sit on
     * plain black, which is already their Home ground.
     */
    fun wall(app: AppContext): GradientDrawable? = when (app.theme.homeLayout.hero) {
        HomeLayoutSpec.Hero.DUAL_CARDS -> GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(app.theme.color("wallA", "#8FA5B8"), app.theme.color("wallB", "#54687C")),
        )
        HomeLayoutSpec.Hero.KIDS_STAGE -> GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(app.theme.color("stageYellow", "#FFC93A"), app.theme.color("stageDeep", "#F4B81E")),
        )
        HomeLayoutSpec.Hero.FOCUSED_HUB -> GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(app.theme.surface, app.theme.bg),
        )
        else -> null
    }

    /** The pack's tile treatment for one list row: fill, optional hairline, the pack's corner. */
    fun rowFill(app: AppContext): GradientDrawable {
        val radius = listRadius(app)
        val fill = app.theme.colorOr("listFill", app.theme.surface)
        val stroke = app.theme.colorOr("listStroke", 0)
        return if (stroke != 0)
            Views.rounded(fill, radius, maxOf(1, app.theme.px(1.5f)), stroke)
        else
            Views.rounded(fill, radius)
    }

    /**
     * One settings/list row: bold label with its quiet note beneath, the current value and a
     * chevron on the right when the row leads somewhere. Focus is the pack's keyline at exactly
     * the row's own radius — never a rectangle that disagrees with the tile under it.
     */
    fun row(
        ctx: Context,
        app: AppContext,
        label: String,
        value: String,
        note: String,
        chevron: Boolean,
        onClick: () -> Unit,
    ): LinearLayout {
        val radius = listRadius(app)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = listHeight(app)
            setPadding(app.theme.px(28f), app.theme.px(14f), app.theme.px(22f), app.theme.px(14f))
            background = rowFill(app)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Views.text(app, "li", 24f, app.theme.ink, label, bold = true, maxLines = 1))
        if (note.isNotBlank()) {
            texts.addView(
                Views.text(app, "meta", 16f, app.theme.ink3, note, maxLines = 1),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = app.theme.px(3f)
                })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value.isNotBlank()) {
            row.addView(Views.text(app, "liMeta", 20f, app.theme.ink2, value, maxLines = 1, gravity = Gravity.END),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = app.theme.px(20f)
                })
        }
        if (chevron) {
            row.addView(Views.text(app, "li", 26f, app.theme.ink3, "›", gravity = Gravity.CENTER),
                LinearLayout.LayoutParams(app.theme.px(28f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = app.theme.px(12f)
                })
        }
        Focus.attach(row, row, app, radius, scale = false, elevate = false)
        return row
    }

    /** Adds a row to the list with the pack's own gap between rows. */
    fun addRow(
        ctx: Context,
        app: AppContext,
        list: LinearLayout,
        label: String,
        value: String,
        note: String,
        chevron: Boolean,
        onClick: () -> Unit,
    ) {
        list.addView(row(ctx, app, label, value, note, chevron, onClick),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = listGap(app)
            })
    }

    /**
     * A quiet uppercase heading that splits a long settings page into scannable groups. It is
     * decoration, not a D-pad stop — the walk jumps straight from a heading to the first row
     * under it.
     */
    fun section(ctx: Context, app: AppContext, list: LinearLayout, title: String) {
        val t = Views.text(app, "rowTitle", 19f, app.theme.ink3, title.uppercase(), bold = true)
        t.setLetterSpacing(0.16f)
        t.setPadding(app.theme.px(28f), 0, 0, 0)
        list.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = if (list.childCount == 0) 0 else app.theme.px(26f)
            bottomMargin = app.theme.px(10f)
        })
    }

    /**
     * The page's top strip: a focusable back chevron, the page title, and the status line actions
     * write into. It carries no plate of its own — the wall shows through, exactly like the
     * pack's own headers on Home. Returns the status view every action reports into.
     */
    fun topBar(
        ctx: Context,
        app: AppContext,
        title: String,
        onBack: () -> Unit,
    ): Pair<LinearLayout, TextView> {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(app.theme.gutter, 0, app.theme.gutter, 0)
        }
        val back = Views.text(app, "li", 26f, app.theme.ink, "‹", bold = true, gravity = Gravity.CENTER)
        back.setPadding(app.theme.px(16f), app.theme.px(6f), app.theme.px(16f), app.theme.px(6f))
        back.isClickable = true
        back.setOnClickListener { onBack() }
        Focus.attach(back, back, app, app.theme.radius("button", 10f).coerceAtMost(app.theme.px(14f)),
            scale = false, elevate = false)
        bar.addView(back, LinearLayout.LayoutParams(app.theme.px(56f), app.theme.px(50f)))
        val t = Views.text(app, "h2", 30f, app.theme.ink, title, bold = true)
        t.setPadding(app.theme.px(18f), 0, 0, 0)
        bar.addView(t)
        bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        val status = Views.text(app, "meta", 19f, app.theme.ink2, "", maxLines = 1)
        bar.addView(status)
        return bar to status
    }

    /** The panel a picker opens: the pack's modal radius and scrim, with its hairline edge. */
    fun modalFill(app: AppContext): GradientDrawable = Views.rounded(
        app.theme.color("panelScrim", "#E6000000"),
        app.theme.radius("modal", 18f),
        maxOf(1, app.theme.px(1f)),
        app.theme.colorOr("listStroke", app.theme.line),
    )

    /** One option inside an open picker. Selected rides the pack's value colour. */
    fun pickerRow(
        ctx: Context,
        app: AppContext,
        label: String,
        selected: Boolean,
        onClick: () -> Unit,
    ): LinearLayout {
        val radius = listRadius(app)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(app.theme.px(18f), 0, app.theme.px(18f), 0)
            background = if (selected) Views.rounded(app.theme.surface2, radius) else null
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
        }
        val tick = if (selected) "✓" else " "
        row.addView(
            Views.text(app, "li", 22f,
                if (selected) app.theme.color("panelValue", "#F2C33A") else app.theme.ink,
                "$tick  $label", bold = selected, maxLines = 1),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        Focus.attach(row, row, app, radius, scale = false, elevate = false)
        return row
    }
}
