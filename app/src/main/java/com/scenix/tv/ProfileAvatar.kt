package com.scenix.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Views

/**
 * One identity treatment for the profile gate, Home pill and profile management. A hub image is
 * optional, so the deterministic coloured initial is a real fallback rather than an empty circle.
 */
object ProfileAvatar {

    fun create(
        context: Context,
        app: AppContext,
        name: String,
        kids: Boolean,
        avatar: String,
        api: BrowseApi,
        sizePx: Int,
    ): FrameLayout {
        val hue = ((name.lowercase().fold(0) { acc, c -> acc * 31 + c.code } and 0x7fffffff) % 360).toFloat()
        val base = Color.HSVToColor(floatArrayOf(hue, if (kids) 0.48f else 0.32f, if (kids) 0.62f else 0.44f))
        val deep = Color.HSVToColor(floatArrayOf(hue, if (kids) 0.55f else 0.38f, if (kids) 0.34f else 0.22f))
        val frame = FrameLayout(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(base, deep))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) = outline.setOval(0, 0, v.width, v.height)
            }
        }
        val initial = Views.text(app, "avatarText", 64f, app.theme.ink, name.trim().take(1).uppercase().ifBlank { "?" },
            bold = true, gravity = Gravity.CENTER).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, (sizePx * 0.38f).coerceAtLeast(app.theme.px(16f).toFloat()))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frame.addView(initial, FrameLayout.LayoutParams(sizePx, sizePx))
        frame.addView(image, FrameLayout.LayoutParams(sizePx, sizePx))
        if (avatar.isNotBlank()) {
            BrowseImages.load(image, api.absolute(avatar), sizePx) { loaded ->
                initial.visibility = if (loaded) View.GONE else View.VISIBLE
            }
        }
        return frame
    }
}
