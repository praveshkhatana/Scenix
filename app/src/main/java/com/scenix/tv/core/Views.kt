package com.scenix.tv.core

import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

/**
 * The few view builders the native setup screen needs, all reading sizes and colours from [Theme].
 * Text sizes are set in device pixels (`COMPLEX_UNIT_PX`) so the screen matches the 1920x1080
 * canvas exactly instead of guessing at the TV's density bucket.
 */
object Views {

    fun text(
        ctx: AppContext,
        typeToken: String,
        fallbackSizePx: Float,
        color: Int,
        value: String,
        bold: Boolean = false,
        gravity: Int = Gravity.START,
        maxLines: Int = 1,
    ): TextView {
        val tv = TextView(ctx.app)
        // Labels are not controls. A TextView that can take focus becomes a D-pad stop you can press,
        // and on Android TV a focused label shows a selection highlight — which is what made moving
        // up and down feel like it was selecting text instead of cards.
        tv.isFocusable = false
        tv.isFocusableInTouchMode = false
        tv.isClickable = false
        tv.isLongClickable = false
        tv.setTextIsSelectable(false)
        tv.includeFontPadding = false
        tv.setSingleLine(maxLines == 1)
        tv.maxLines = maxLines
        tv.ellipsize = android.text.TextUtils.TruncateAt.END
        tv.gravity = gravity
        tv.setTextColor(color)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, theme(ctx, typeToken, fallbackSizePx))
        tv.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        tv.text = value
        return tv
    }

    private fun theme(ctx: AppContext, token: String, fallback: Float): Float =
        ctx.theme.type(token, fallback)

    fun rounded(color: Int, radiusPx: Int, strokePx: Int = 0, strokeColor: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = radiusPx.toFloat()
        if (strokePx > 0) d.setStroke(strokePx, strokeColor)
        return d
    }

    fun ring(color: Int, radiusPx: Int, strokePx: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(android.graphics.Color.TRANSPARENT)
        d.cornerRadius = radiusPx.toFloat()
        d.setStroke(strokePx, color)
        return d
    }

    /**
     * The indeterminate spinner, drawn rather than borrowed. The framework `ProgressBar` is a green
     * circular ARROW — a "replay" glyph that happens to rotate — and on a television it reads as a
     * transport control, not as waiting. This is the same language the rest of the chrome speaks: a
     * single rounded arc, the viewer's own ink, turning at a calm constant speed.
     */
    fun spinner(app: AppContext, ink: Int, strokePx: Int): android.view.View =
        object : android.view.View(app.app) {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeCap = android.graphics.Paint.Cap.ROUND
                color = ink
                strokeWidth = strokePx.toFloat()
            }
            private var angle = 0f
            private var last = 0L

            override fun onDraw(canvas: android.graphics.Canvas) {
                val now = android.os.SystemClock.uptimeMillis()
                if (last != 0L) {
                    // A constant 240°/s. Faster spins look nervous on a 10-foot screen; slower ones
                    // read as stalled.
                    angle = (angle + (now - last) * SPINNER_DEGREES_PER_MS) % 360f
                }
                last = now
                val half = Math.min(width, height) / 2f
                val r = half - strokePx / 2f - 1f
                if (r <= 0f) return
                canvas.save()
                canvas.rotate(angle, width / 2f, height / 2f)
                canvas.drawArc(width / 2f - r, height / 2f - r, width / 2f + r, height / 2f + r,
                    0f, SPINNER_ARC_SWEEP, false, paint)
                canvas.restore()
                postInvalidateOnAnimation()
            }
        }

    private const val SPINNER_DEGREES_PER_MS = 240f / 1000f
    private const val SPINNER_ARC_SWEEP = 280f
}
