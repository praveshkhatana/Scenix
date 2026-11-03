package com.scenix.tv

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Theme
import com.scenix.tv.core.Views

/**
 * The Kids stage component kit (contract: design-contracts/kids.md §3).
 *
 * One place builds every Kids part — brand disc, tab capsule, search knob, household cluster,
 * hero plates, chip capsules, play row — and each builder consumes ONLY theme tokens: no visual
 * literal stands in a Kids composition, so a tile on the rail, a card on the hero and a control
 * in a modal all speak the same plate language. Focus routing stays with the composition that
 * places the view (the same split GlassKit follows).
 *
 * Every size below is in mockup pixels read off the corrected reference (2181×1345, uniform
 * scale 0.803 to the 1920×1080 canvas): page #FBD364 flat; plates #050505; knob #FDB916;
 * hero gradient #F2AE01→#FBC000; tiles #F9C406 r24; focus bands #FDDB6F to 40 with a 4px
 * #A88200 keyline (all already tokenized in theme-kids.json).
 */
internal object KidsKit {

    private fun theme(app: AppContext) = app.theme

    /** Black brand disc carrying the real Scenix wordmark — owner round 5: no letter "S". */
    fun brandDisc(app: AppContext, d: Int): FrameLayout {
        val t = theme(app)
        return FrameLayout(app.app).apply {
            background = Views.rounded(t.color("plateInk", "#050505"), t.px(999f))
            addView(ImageView(app.app).apply {
                setImageResource(R.drawable.scenix_wordmark)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(t.px(18f), t.px(18f), t.px(18f), t.px(18f))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
            layoutParams = LinearLayout.LayoutParams(d, d)
        }
    }

    /** The black tab capsule: a rounded plate the segment stops and the search knob weld into. */
    fun tabCapsule(app: AppContext, h: Int): LinearLayout {
        val t = theme(app)
        return LinearLayout(app.app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Views.rounded(t.color("plateInk", "#050505"), t.px(999f))
            setPadding(t.px(6f), 0, t.px(3f), 0)
        }.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h) }
    }

    /** The yellow search knob at the capsule's end (reference centre (640,121), d=61 → 49). */
    fun searchKnob(app: AppContext, iconRes: Int, d: Int): FrameLayout {
        val t = theme(app)
        return FrameLayout(app.app).apply {
            background = Views.rounded(t.color("accentYellow", "#FDB916"), t.px(999f))
            addView(ImageView(app.app).apply {
                setImageResource(iconRes)
                setColorFilter(t.color("plateInk", "#050505"))
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(t.px(11f), t.px(11f), t.px(11f), t.px(11f))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    /** A tab stop inside the capsule: active segment reads page-yellow, the rest white. */
    fun segmentText(app: AppContext, label: String, active: Boolean): TextView {
        val t = theme(app)
        return Views.text(app, "nav", 21f,
            if (active) t.color("stageSheet", "#FBD364") else Color.WHITE,
            label, bold = true, gravity = Gravity.CENTER)
    }

    /**
     * The ONE household element (owner round 5: avatar and name combined): the face disc welds
     * into the left end of the black name capsule, its label and a drawn chevron ride beside it.
     * There is no separate disc standing on the page anymore.
     */
    fun nameCapsule(app: AppContext, h: Int): LinearLayout {
        val t = theme(app)
        return LinearLayout(app.app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Views.rounded(t.color("plateInk", "#050505"), t.px(999f))
            setPadding(0, 0, t.px(18f), 0)
        }.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h) }
    }

    /** Settings gear disc — the household's real door, sized to stand level with the capsule. */
    fun gearDisc(app: AppContext, iconRes: Int, d: Int): FrameLayout {
        val t = theme(app)
        return FrameLayout(app.app).apply {
            background = Views.rounded(t.color("plateInk", "#050505"), t.px(999f))
            addView(ImageView(app.app).apply {
                setImageResource(iconRes)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(t.px(15f), t.px(15f), t.px(15f), t.px(15f))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    /**
     * Hero plate. fullArt = the photo covers the whole card and the title prints over it;
     * plate = the reference's deep→bright gradient card (sampled #F2AE01 left → #FBC000 right)
     * whose key art bleeds in from the outer edge. Both round at the contract's heroRadius 32.
     */
    fun heroPlate(app: AppContext, fullArt: Boolean): GradientDrawable {
        val t = theme(app)
        return if (fullArt) Views.rounded(t.color("plateInk", "#050505"), t.radius("card", 32f))
        else GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(t.color("heroPlateL", "#F2AE01"), t.color("heroPlateR", "#FBC000"))).apply {
            cornerRadius = t.radius("card", 32f).toFloat()
        }
    }

    fun heroOutline(app: AppContext): ViewOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(v: View, o: android.graphics.Outline) {
            o.setRoundRect(0, 0, v.width, v.height, theme(app).radius("card", 32f).toFloat())
        }
    }

    /**
     * The reference's play affordance on both hero cards: a black disc (d=55 → 44) with a white
     * triangle, a white label beside it.
     */
    fun playRow(app: AppContext, label: String): LinearLayout {
        val t = theme(app)
        val row = LinearLayout(app.app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val d = t.space("kidsPlay", 44f)
        row.addView(Views.text(app, "meta", 18f, Color.WHITE, "▶", bold = true,
            gravity = Gravity.CENTER).apply {
            background = Views.rounded(t.color("plateInk", "#050505"), t.px(999f))
        }, LinearLayout.LayoutParams(d, d))
        row.addView(Views.text(app, "chip", 24f, Color.WHITE, label, bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = t.px(12f) })
        return row
    }

    /** A shelf chip: uniform black capsule, white icon glyph, white label (273×107 → 219×86). */
    fun chip(app: AppContext, glyph: String, label: String): LinearLayout {
        val t = theme(app)
        val chip = LinearLayout(app.app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = Views.rounded(t.color("plateInk", "#050505"), t.radius("chip", 999f))
        }
        if (glyph.isNotEmpty()) {
            chip.addView(Views.text(app, "chipIcon", 40f, Color.WHITE, glyph, bold = true,
                gravity = Gravity.CENTER),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        chip.addView(Views.text(app, "chip", 24f, Color.WHITE, label, bold = true,
            gravity = Gravity.CENTER, maxLines = 1).apply {
            ellipsize = android.text.TextUtils.TruncateAt.END
        },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (glyph.isNotEmpty()) marginStart = t.px(12f)
                })
        return chip
    }

    /**
     * Chip glyphs are approximated in the deviation log: system-safe marks, one per shelf kind.
     * Round 7: no mark beats a wrong one — the "●" placeholder dot read as a broken chip, so
     * shelves without a real glyph print their label alone; the heart's U+FE0E variation selector
     * is ignored by the TV emoji font (it stayed red), so Trending takes the monochrome ▲ instead
     * — the pack's glyphs are white text marks, never colour emoji.
     */
    fun chipGlyph(label: String): String = when {
        label.contains("Continue", true) -> "▶"
        label.contains("Trending", true) || label.contains("Hot", true) -> "▲"
        label.contains("New", true) -> "✶"
        label.contains("Top", true) || label.contains("rated", true) -> "★"
        else -> ""
    }
}
