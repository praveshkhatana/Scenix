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
}
