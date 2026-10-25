package com.scenix.tv.core

import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small view builders shared by every plugin, all reading sizes/colours from [Theme].
 * Text sizes are set in device pixels (`COMPLEX_UNIT_PX`) so the app matches the 1920x1080 mockup
 * exactly instead of guessing at the TV's density bucket.
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

    /** A token from the `type` group resolved to device pixels (icon sizes ride the same scale). */
    fun imageSize(ctx: AppContext, token: String, fallbackPx: Float): Int =
        Math.round(ctx.theme.type(token, fallbackPx))

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

    /** Filled shape with an outline in one drawable (so a background colour cannot erase the ring). */
    fun filled(color: Int, radiusPx: Int, strokePx: Int, strokeColor: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = radiusPx.toFloat()
        if (strokePx > 0) d.setStroke(strokePx, strokeColor)
        return d
    }

    /** Two-colour gradient, like the mockup's kid tiles (`linear-gradient(145deg,#F2C33A,…)`). */
    fun gradient(colors: IntArray, radiusPx: Int): GradientDrawable {
        val d = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (colors.size >= 2) colors else intArrayOf(colors.getOrElse(0) { 0 }, colors.getOrElse(0) { 0 }),
        )
        d.cornerRadius = radiusPx.toFloat()
        return d
    }

    /** Vertical scrim used over the billboard / behind the nav, like the mockup's gradients. */
    fun scrim(ctx: AppContext, topColor: Int, bottomColor: Int, horizontal: Boolean = false): GradientDrawable {
        val o = if (horizontal) GradientDrawable.Orientation.LEFT_RIGHT else GradientDrawable.Orientation.TOP_BOTTOM
        return GradientDrawable(o, intArrayOf(topColor, bottomColor))
    }

    fun icon(ctx: AppContext, resId: Int, sizePx: Int, tint: Int = ctx.theme.ink): ImageView {
        val iv = ImageView(ctx.app)
        iv.setImageResource(resId)
        iv.setColorFilter(tint)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
        return iv
    }

    /** Rounded clipping for any view (used for card art, avatars, modals). */
    fun clipRound(view: View, radiusPx: Int) {
        view.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx.toFloat())
            }
        }
        view.clipToOutline = true
    }

    fun linear(ctx: AppContext, orientation: Int): LinearLayout {
        val l = LinearLayout(ctx.app)
        l.orientation = orientation
        l.gravity = Gravity.CENTER_VERTICAL
        return l
    }

    fun lp(w: Int, h: Int): android.widget.FrameLayout.LayoutParams =
        android.widget.FrameLayout.LayoutParams(w, h)

    fun lplp(w: Int, h: Int, gravity: Int): android.widget.FrameLayout.LayoutParams =
        android.widget.FrameLayout.LayoutParams(w, h, gravity)
}
