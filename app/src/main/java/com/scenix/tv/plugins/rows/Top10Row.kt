package com.scenix.tv.plugins.rows

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.CardHolder
import com.scenix.tv.core.Item
import com.scenix.tv.core.RowLayout
import com.scenix.tv.core.RowPlugin

/**
 * `row: "top10"` — the one row that needs something extra: the big outlined numeral beside every
 * poster, with its right edge sliding UNDER the card so it reads as belonging to that card.
 *
 * The misalignment this used to cause: the numeral's box was sized off the art height and left
 * anchored, so a narrow "1" floated in the gutter while a wide "10" ran into its neighbour, and the
 * first card no longer lined up with the row title. Two changes fix it:
 *
 *  - the numeral column is one fixed width for the whole row, measured from the widest numeral the
 *    row can show ("10"), so every poster in the row sits at exactly the same pitch; and
 *  - the glyph is drawn right-aligned inside that column, so whatever its own width, its right edge
 *    is always the same distance under its own card.
 *
 * The column overlaps the art by `numeralOverlapPx` (the mockup's `margin-right:-26px`), the poster
 * paints over it, and the column's left edge is the row gutter — the same 76 px the row title starts
 * at. No card plugin, no screen and no other row changes.
 */
object Top10Row : RowPlugin {
    override val id get() = "row-top10"
    override val rowId get() = "top10"
    override val order = 20

    /** Numeral metrics for this row: absolute px from the theme when given, else off the art height. */
    private fun textSize(layout: RowLayout): Float =
        (if (layout.numeralTextPx > 0) layout.numeralTextPx
        else Math.round(layout.artHeight * layout.numeralTextRatio)).toFloat()

    /**
     * One column per row, wide enough for the widest numeral this row can hold. Measured, not
     * guessed: "1" and "10" are nowhere near the same width, and a proportional box is what put the
     * numerals out of line in the first place.
     */
    private fun columnWidth(layout: RowLayout): Int {
        val size = textSize(layout)
        val w = MEASURE.let { p ->
            p.textSize = size
            p.measureText("10")
        }
        return Math.round(w + layout.numeralStrokePx)
    }

    /** How far the glyph slides under the card. Capped so a numeral can never cover its own poster. */
    private fun overlap(layout: RowLayout, column: Int): Int {
        val want = if (layout.numeralOverlapPx > 0) layout.numeralOverlapPx
        else Math.round(layout.artHeight * layout.numeralOverlapRatio)
        return want.coerceIn(0, (column * 0.6f).toInt())
    }

    override fun layout(base: RowLayout): RowLayout {
        val column = columnWidth(base)
        val inset = column - overlap(base, column)
        return base.copy(
            artLeft = inset,
            slotWidth = base.artWidth + inset,
            numeralTextPx = textSize(base).toInt(),
            numeralOverlapPx = overlap(base, column),
        )
    }

    override fun explore(): Boolean = true

    override fun decorate(holder: CardHolder, item: Item, position: Int, ctx: AppContext) {
        val theme = ctx.theme
        val layout = holder.layout
        val size = textSize(layout)
        val column = columnWidth(layout)
        val red = theme.token("cards", "numeralRed", 0.0) >= 0.5

        val view = holder.part("numeral", holder.root) {
            OutlinedNumberView(ctx.app).apply {
                layoutParams = FrameLayout.LayoutParams(column, layout.artHeight)
            }
        }
        view.layoutParams = FrameLayout.LayoutParams(column, layout.artHeight)
        view.configure(
            textSizePx = size,
            fill = theme.color(if (red) "numeralFillRed" else "numeralFill", if (red) "#E50914" else "#000000"),
            stroke = theme.color(if (red) "numeralStrokeRed" else "numeralStroke", if (red) "#4A0007" else "#7C7C7C"),
            strokeWidthPx = (if (layout.numeralStrokePx > 0) layout.numeralStrokePx
            else theme.px(theme.token("cards", "numeralStrokePx", 8.0).toFloat())).toFloat(),
        )
        view.setNumber(position + 1)
        // Added first so the poster paints over the numeral's right edge, like the mockup.
        (view.parent as? ViewGroup)?.let { p ->
            p.removeView(view)
            p.addView(view, 0)
        }
    }

    private val MEASURE = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
}

/**
 * The giant rank numeral: filled black with a grey outline (the mockup's `.t10 .num`), drawn right-
 * aligned in its column so its right edge is always the same distance under its own card. Text-only,
 * never focusable, no TextView, no layout pass.
 */
class OutlinedNumberView(context: Context) : View(context) {

    private var number = 0
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#000000")
        style = Paint.Style.FILL
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7C7C7C")
        style = Paint.Style.STROKE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        strokeWidth = 3f * context.resources.displayMetrics.density
    }

    init {
        isFocusable = false
        isClickable = false
    }

    fun configure(textSizePx: Float, fill: Int, stroke: Int, strokeWidthPx: Float) {
        this.fill.textSize = textSizePx
        this.stroke.textSize = textSizePx
        this.fill.color = fill
        this.stroke.color = stroke
        this.stroke.strokeWidth = strokeWidthPx.coerceAtLeast(1f)
    }

    fun setNumber(n: Int) {
        if (number == n) return
        number = n
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (number <= 0) return
        val text = number.toString()
        val fm = fill.fontMetrics
        // Sit it on the bottom edge of the art and let it bleed, like the mockup.
        val baseline = height * BOTTOM_ANCHOR - fm.descent
        // Right-aligned in the column: the glyph's right edge is fixed, so every numeral overlaps its
        // own card by the same amount and the posters stay on a perfect pitch.
        val x = width - fill.measureText(text) - stroke.strokeWidth * RIGHT_INSET
        canvas.drawText(text, x, baseline, stroke)
        canvas.drawText(text, x, baseline, fill)
    }

    companion object {
        private const val BOTTOM_ANCHOR = 0.95f
        private const val RIGHT_INSET = 0.5f
    }
}
