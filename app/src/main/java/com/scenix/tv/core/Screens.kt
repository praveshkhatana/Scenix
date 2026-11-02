package com.scenix.tv.core

import android.content.Context
import android.graphics.drawable.GradientDrawable
import com.scenix.tv.GlassKit
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
    fun wall(app: AppContext): android.graphics.drawable.Drawable? = when (app.theme.homeLayout.hero) {
        HomeLayoutSpec.Hero.DUAL_CARDS -> GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(app.theme.color("wallA", "#8FA5B8"), app.theme.color("wallB", "#54687C")),
        )
        HomeLayoutSpec.Hero.GLASS_CARDS -> GlassKit.wall(app)
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
     * The plate a FOCUSED row wears. Packs whose rows sit on their own wall (the glow packs) define
     * `colors.listFillFocused` a step deeper than `listFill` — the halo alone washed into the page
     * and the owner could not tell which row had focus; a dark surfHover plate under unchanged ink
     * text hid the content outright. Undefined elsewhere, so classic packs keep halo-only focus.
     */
    fun rowFillFocused(app: AppContext): GradientDrawable? {
        val focused = app.theme.colorOr("listFillFocused", -0x1)
        if (focused == -0x1) return null
        val radius = listRadius(app)
        val stroke = app.theme.colorOr("listStroke", 0)
        return if (stroke != 0)
            Views.rounded(focused, radius, maxOf(1, app.theme.px(1.5f)), stroke)
        else
            Views.rounded(focused, radius)
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
    ): View {
        val radius = listRadius(app)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = listHeight(app)
            // A pack that lifts its rows on focus needs extra inset so a scaled plate cannot
            // carry the label past the screen edge (owner round 5 asked for the zoom).
            setPadding(app.theme.px(if (app.theme.focusRowScale > 1f) 40f else 28f), app.theme.px(14f),
                app.theme.px(if (app.theme.focusRowScale > 1f) 40f else 22f), app.theme.px(14f))
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
        if (app.theme.focusStyle != "glow") {
            Focus.attach(row, row, app, radius, scale = false, elevate = false)
            return row
        }
        // A glow pack's row stands on its halo canvas, the rail tiles' pattern: the plate is often
        // the same colour family as the glow, so a keyline drawn ON the plate had no state
        // contrast at all. The canvas reaches past the row so the halo washes the page beside it
        // and the keyline rings the plate from outside.
        return haloRow(ctx, app, row, radius, onClick)
    }

    /**
     * Wrap a row in a transparent oversized canvas and hand focus to the wrapper (glow packs).
     * Public because screens that build their own row plate (profile rows with avatars) need the
     * same focus treatment as `row()`.
     */
    fun haloRow(
        ctx: Context,
        app: AppContext,
        row: android.view.View,
        radius: Int,
        onClick: (() -> Unit)? = null,
    ): View {
        val glowPad = app.theme.focusGlowInsetSm
        val wrap = android.widget.FrameLayout(ctx).apply {
            clipChildren = false
            clipToPadding = false
        }
        val ringOn = View(ctx)
        wrap.addView(ringOn, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { setMargins(-glowPad, -glowPad, -glowPad, -glowPad) })
        row.isFocusable = false
        row.isClickable = false
        wrap.addView(row, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        // The halo canvas has to be LARGER than the plate, not just offset behind it. With a
        // MATCH_PARENT child and negative margins the wrapper measured to the row itself, so the
        // glow sat entirely under an opaque row and the focused settings option looked like a
        // plain 1px keyline. Once the row is measured, grow the canvas by glowPad on every side.
        wrap.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val lp = ringOn.layoutParams as android.widget.FrameLayout.LayoutParams
            val w = (r - l) + glowPad * 2
            val h = (b - t) + glowPad * 2
            if (lp.width != w || lp.height != h) {
                lp.width = w
                lp.height = h
                lp.leftMargin = -glowPad
                lp.topMargin = -glowPad
                ringOn.layoutParams = lp
            }
        }
        Focus.attach(wrap, ringOn, app, radius,
            scale = app.theme.focusRowScale > 1f, scaleTo = app.theme.focusRowScale,
            elevate = false, glowInset = glowPad, control = true, onClick = onClick,
            onFocus = { has ->
                val deep = rowFillFocused(app)
                if (deep != null) row.background = if (has) deep else rowFill(app)
            })
        return wrap
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
        val item = row(ctx, app, label, value, note, chevron, onClick)
        if (item.id == View.NO_ID) item.id = View.generateViewId()
        // A settings page is a list, so its D-pad route is the list: each row names its neighbours.
        // Geometric search across a ScrollView could stop at the last painted row (the admin
        // sections below it were unreachable until the viewer touch-scrolled) and DOWN from the
        // bottom could wrap onto the Back chevron. Explicit links make the walk total and stable
        // for every pack, including one whose rows are taller than the visible band.
        var previous: View? = null
        for (i in list.childCount - 1 downTo 0) {
            val c = list.getChildAt(i)
            if (c.isFocusable) { previous = c; break }
        }
        if (previous != null) {
            previous.nextFocusDownId = item.id
            item.nextFocusUpId = previous.id
        }
        item.nextFocusDownId = item.id
        list.addView(item,
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
        val t = Views.text(app, "rowTitle", 19f,
            app.theme.colorOr("wallInk2", app.theme.ink3), title.uppercase(), bold = true)
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
        val back = Views.text(app, "li", 26f,
            app.theme.colorOr("wallInk", app.theme.ink), "‹", bold = true, gravity = Gravity.CENTER)
        back.setPadding(app.theme.px(16f), app.theme.px(6f), app.theme.px(16f), app.theme.px(6f))
        back.isClickable = true
        back.setOnClickListener { onBack() }
        Focus.attach(back, back, app, app.theme.radius("button", 10f).coerceAtMost(app.theme.px(14f)),
            scale = false, elevate = false)
        bar.addView(back, LinearLayout.LayoutParams(app.theme.px(56f), app.theme.px(50f)))
        val t = Views.text(app, "h2", 30f,
            app.theme.colorOr("wallInk", app.theme.ink), title, bold = true)
        t.setPadding(app.theme.px(18f), 0, 0, 0)
        bar.addView(t)
        bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        val status = Views.text(app, "meta", 19f,
            app.theme.colorOr("wallInk2", app.theme.ink2), "", maxLines = 1)
        bar.addView(status)
        return bar to status
    }

    /** The panel a picker opens: the pack's modal radius and scrim, with its hairline edge.
     *  `modalPanel` lets a light pack keep its page's light — a dark panel over a yellow page turns
     *  the pack's ink-on-light text invisible, which is how the Kids settings options went unreadable. */
    fun modalFill(app: AppContext): GradientDrawable = Views.rounded(
        app.theme.colorOr("modalPanel", app.theme.color("panelScrim", "#E6000000")),
        app.theme.radius("modal", 18f),
        maxOf(1, app.theme.px(1f)),
        app.theme.colorOr("listStroke", app.theme.line),
    )

    /** The veil behind an open picker. Packs with a bright page dim gently, so the panel's own
     *  surface and the page behind it both stay readable. */
    fun dimColor(app: AppContext): Int =
        app.theme.colorOr("modalDim", android.graphics.Color.argb(190, 0, 0, 0))

    /** One option inside an open picker. Selected rides the pack's value colour. */
    fun pickerRow(
        ctx: Context,
        app: AppContext,
        label: String,
        selected: Boolean,
        onClick: () -> Unit,
    ): View {
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
        // A selected option in a glow pack sits on the pack's bright focus band, not on the dark
        // panel: a pale value ink (Glass prints white) disappears there. Keep the pack's value
        // colour when the row is resting; when focus paints the band, swap to the readable wall ink.
        val valueInk = app.theme.color("panelValue", "#F2C33A")
        val onBandInk = if (app.theme.focusStyle == "glow" &&
            android.graphics.Color.luminance(valueInk) > 0.75f
        ) app.theme.colorOr("wallInk", app.theme.ink) else valueInk
        val labelView = Views.text(app, "li", 22f,
            if (selected) valueInk else app.theme.ink,
            "$tick  $label", bold = selected, maxLines = 1)
        row.addView(labelView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // In a glow pack the row stands in the panel's own halo language; on a light panel a flat
        // keyline disappears, so the row asks for the small inset the theme carries for controls.
        // In a glow pack the row stands in the panel's own halo language; on a light panel a flat
        // keyline disappears, so the row also rides a focus plate — the same tint the selected
        // row carries — giving every picker option a visible, unambiguous focused state.
        Focus.attach(row, row, app, radius, scale = false, elevate = false,
            glowInset = app.theme.focusGlowInsetSm, control = true,
            onFocus = { has ->
                if (selected) labelView.setTextColor(if (has) onBandInk else valueInk)
                else row.background =
                    if (has) Views.rounded(app.theme.colorOr("listFillFocused", app.theme.surface2), radius)
                    else null
            })
        return row
    }
}
