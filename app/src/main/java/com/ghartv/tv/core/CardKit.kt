package com.scenix.tv.core

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.scenix.tv.R

/**
 * The shared card skeleton every [CardPlugin] builds on, plus the holder plugins store their
 * per-card-type views in.
 *
 * `part()` is the recycling-safe way to add a view: the first bind for a card type creates it,
 * every later bind gets the same instance back — so a card plugin can build its badges/progress
 * bar/buttons once and only update them on bind. Row plugins use it too (that is how `top10`
 * bolts numerals onto a poster card without a second card type).
 */
class CardHolder(
    val ctx: AppContext,
    val root: FrameLayout,
    val art: FrameLayout,
    val image: ImageView,
    val overlay: FrameLayout,
    val title: TextView?,
    val meta: LinearLayout,
    val layout: RowLayout,
    val host: ScreenHost,
) : RecyclerView.ViewHolder(root) {

    private val parts = HashMap<String, View>()

    /** The item currently bound — set by the row renderer, read by the click/focus handlers. */
    var boundItem: Item? = null

    /** Art radius for this card, from the theme. */
    val artRadiusPx: Int get() = ctx.theme.radius("card", 6f)

    @Suppress("UNCHECKED_CAST")
    fun <V : View> part(key: String, parent: ViewGroup, create: () -> V): V {
        parts[key]?.let { return it as V }
        val v = create()
        parent.addView(v)
        parts[key] = v
        return v
    }

    fun find(key: String): View? = parts[key]

    /** Hide a part a previous bind created (RecyclerView recycling). */
    fun hide(key: String) {
        parts[key]?.visibility = View.GONE
    }

    fun textPart(key: String, parent: ViewGroup, typeToken: String, fallbackPx: Float, color: Int, bold: Boolean = false, maxLines: Int = 1): TextView =
        part(key, parent) { Views.text(ctx, typeToken, fallbackPx, color, "", bold, Gravity.START, maxLines) }

    fun iconPart(key: String, parent: ViewGroup, resId: Int, sizePx: Int, tint: Int = ctx.theme.ink, roundBg: Int = 0, ringPx: Int = 0): ImageView =
        part(key, parent) {
            val iv = ImageView(ctx.app)
            iv.setImageResource(resId)
            iv.setColorFilter(tint)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            if (roundBg != 0) {
                iv.background = Views.ring(tint, sizePx / 2, ringPx)
                iv.setBackgroundColor(roundBg)
            }
            iv
        }

    /** Small pill in the top-left of the art (LIVE / NEW / "48 min left"). */
    fun badge(key: String, label: String, bg: Int, fg: Int, dot: Boolean = false): TextView {
        val tv = part(key, overlay) {
            val t = TextView(ctx.app)
            t.includeFontPadding = false
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ctx.theme.type("badge", 15f))
            t.setTextColor(fg)
            t.setPadding(ctx.theme.px(10f), ctx.theme.px(5f), ctx.theme.px(10f), ctx.theme.px(5f))
            t.gravity = Gravity.CENTER
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.setMargins(ctx.theme.px(12f), ctx.theme.px(12f), 0, 0)
            t.layoutParams = lp
            t
        }
        tv.text = label
        tv.visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
        tv.background = Views.rounded(bg, ctx.theme.radius("badge", 4f))
        if (dot) tv.setCompoundDrawablesWithIntrinsicBounds(dot(ctx, fg), null, null, null)
        return tv
    }

    private fun dot(ctx: AppContext, color: Int): android.graphics.drawable.Drawable {
        val d = android.graphics.drawable.GradientDrawable()
        d.shape = android.graphics.drawable.GradientDrawable.OVAL
        d.setColor(color)
        val s = ctx.theme.px(9f)
        d.setSize(s, s)
        return d
    }

    /** Bottom red progress bar for `continue`-style cards. */
    fun progressBar(key: String, fraction: Float, color: Int, trackColor: Int): View {
        val bar = part(key, art) {
            FrameLayout(ctx.app).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.theme.px(5f), Gravity.BOTTOM)
            }
        }
        bar.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.theme.px(5f), Gravity.BOTTOM)
        bar.setBackgroundColor(trackColor)
        val fill = (bar as FrameLayout).let { fl ->
            if (fl.childCount == 0) {
                val v = View(ctx.app)
                fl.addView(v, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
                v
            } else fl.getChildAt(0)
        }
        val w = Math.round(layout.artWidth * fraction.coerceIn(0f, 1f))
        fill.layoutParams = FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.MATCH_PARENT)
        fill.setBackgroundColor(color)
        return bar
    }

    /** Load the right artwork for this card's aspect, through the shared [ImageLoader]. */
    fun loadArt(item: Item) {
        val wide = layout.artWidth > layout.artHeight
        ctx.images.load(image, item.artFor(wide), layout.artWidth, layout.artHeight, ctx.theme.surface2)
    }

    fun setArtBackground(color: Int) {
        art.background = Views.rounded(color, artRadiusPx)
    }

    companion object {
        /**
         * Build the generic skeleton: art (image + overlay) with an optional caption block below.
         *
         * `cardFocusable` is false for billboard-style cards, where the buttons inside the card are
         * the D-pad stops instead of the card itself.
         */
        fun build(ctx: AppContext, layout: RowLayout, host: ScreenHost, cardFocusable: Boolean = true): CardHolder {
            val theme = ctx.theme
            val root = FrameLayout(ctx.app)
            root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )

            val col = LinearLayout(ctx.app)
            col.orientation = LinearLayout.VERTICAL
            val colLp = FrameLayout.LayoutParams(layout.artWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            colLp.leftMargin = layout.artLeft
            root.addView(col, colLp)

            val art = FrameLayout(ctx.app)
            art.layoutParams = LinearLayout.LayoutParams(layout.artWidth, layout.artHeight)
            Views.clipRound(art, theme.radius("card", 6f))
            art.setBackgroundColor(theme.surface2)
            col.addView(art)

            val image = ImageView(ctx.app)
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            art.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            val overlay = FrameLayout(ctx.app)
            art.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            var title: TextView? = null
            val meta = LinearLayout(ctx.app)
            meta.orientation = LinearLayout.HORIZONTAL
            if (layout.caption) {
                title = Views.text(ctx, "caption", 19f, theme.ink, "", bold = false, maxLines = 2)
                val tlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                tlp.topMargin = theme.captionGap
                col.addView(title, tlp)
                val mlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                mlp.topMargin = theme.px(4f)
                col.addView(meta, mlp)
            }

            val holder = CardHolder(ctx, root, art, image, overlay, title, meta, layout, host)

            // D-pad: the whole card is one focusable thing; the ring goes on the art.
            if (cardFocusable) {
                Focus.attach(root, art, ctx, theme.radius("card", 6f), scale = true, onClick = {
                    holder.boundItem?.let { host.openDetail(it.identity, it) }
                })
            } else {
                // Buttons inside the card are the focus stops; the art still lights up.
                root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                root.isFocusable = false
            }
            root.setTag(R.id.scenix_card_ctx, holder)
            return holder
        }
    }
}

/** Convenience: a left-aligned horizontal meta line ("98% Match   16+   2h 14m   2026"). */
fun CardHolder.metaText(key: String, value: String, color: Int, bold: Boolean = false): TextView {
    val tv = textPart(key, meta, "meta", 17f, color, bold)
    tv.text = value
    tv.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
    val lp = tv.layoutParams as LinearLayout.LayoutParams
    lp.leftMargin = if (meta.childCount > 1) ctx.theme.metaGap else 0
    tv.layoutParams = lp
    return tv
}

/** A weighted gap that pushes the rest of the meta line to the right edge (the mockup's `.yr`). */
fun CardHolder.metaSpacer(key: String = "metaSpacer"): View {
    val v = part(key, meta) { View(ctx.app) }
    v.layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
    return v
}
