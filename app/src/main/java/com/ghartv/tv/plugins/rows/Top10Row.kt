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
import com.scenix.tv.core.Theme

/**
 * `row: "top10"` — the one row that needs something extra: the big outlined numeral in front of
 * every poster.
 *
 * That is the whole cost of a special row: it changes geometry (the card is pushed right by the
 * numeral's width) and decorates the card the `poster` renderer already built. No card plugin, no
 * screen and no other row changes. The numeral is drawn with a [Paint] because the mockup's
 * `-webkit-text-stroke` has no TextView equivalent that survives recycling at 2 fps of scrolling.
 */
object Top10Row : RowPlugin {
    override val id get() = "row-top10"
    override val rowId get() = "top10"
    override val order = 20

    /** Numerals are proportional to the card: 0.72 of the art height wide, drawn taller than it. */
    private fun numeralWidth(layout: RowLayout): Int =
        Math.round(layout.artHeight * layout.numeralWidthRatio)

    private fun overlap(layout: RowLayout): Int =
        Math.round(layout.artHeight * layout.numeralOverlapRatio)

    override fun layout(base: RowLayout): RowLayout {
        val n = numeralWidth(base)
        return base.copy(artLeft = n, slotWidth = base.artWidth + n - overlap(base))
    }

    override fun explore(): Boolean = true

    override fun decorate(holder: CardHolder, item: Item, position: Int, ctx: AppContext) {
        val theme = ctx.theme
        val layout = holder.layout
        val n = numeralWidth(layout)
        val view = holder.part("numeral", holder.root) {
            OutlinedNumberView(ctx.app).apply {
                layoutParams = FrameLayout.LayoutParams(n, layout.artHeight)
            }
        }
        view.layoutParams = FrameLayout.LayoutParams(n, layout.artHeight)
        view.configure(
            textSizePx = layout.artHeight * theme.token("cards", "numeralTextRatio", 1.05).toFloat(),
            fill = theme.color("numeralFill", "#6F6F6F"),
            stroke = theme.color("numeralStroke", "#A5A5A5"),
            strokeWidthPx = theme.px(theme.token("cards", "numeralStrokePx", 7.0).toFloat()).toFloat(),
        )
        view.setNumber(position + 1)
        // Added first so the poster paints over the numeral's right edge, like the mockup.
        (view.parent as? ViewGroup)?.let { p ->
            p.removeView(view)
            p.addView(view, 0)
        }
    }
}

/**
 * The giant rank numeral: filled grey with a lighter outline (the mockup's `.card.rank .num`).
 * Text-only, never focusable, no TextView, no layout pass.
 */
class OutlinedNumberView(context: Context) : View(context) {

    private var number = 0
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6F6F6F")
        style = Paint.Style.FILL
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A5A5A5")
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
        val x = -fill.measureText(text) * OVERHANG
        canvas.drawText(text, x, baseline, stroke)
        canvas.drawText(text, x, baseline, fill)
    }

    companion object {
        private const val BOTTOM_ANCHOR = 0.95f
        private const val OVERHANG = 0.04f
    }
}
