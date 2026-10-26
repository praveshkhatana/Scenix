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

    /**
     * True until this holder's first bind. A row fades up once, when it first appears; a holder that
     * is reused by scrolling keeps this false, so scrolling sideways never re-triggers the entrance.
     */
    var needsEntrance = true

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

    /**
     * Bottom progress bar for `continue`-style cards — 8 px of brand red on the theme's track, the
     * mockup's `.trk`/`.pl`. It is a child of the art, so it is clipped by the art's rounded corners
     * and survives recycling because `part()` hands back the same instance.
     */
    fun progressBar(key: String, fraction: Float, color: Int, trackColor: Int): View {
        val h = ctx.theme.px(8f)
        val bar = part(key, art) {
            FrameLayout(ctx.app).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h, Gravity.BOTTOM)
            }
        }
        bar.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h, Gravity.BOTTOM)
        bar.setBackgroundColor(trackColor)
        val fill = bar.let { fl ->
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

    /**
     * A small chip in the bottom-right of the art. Unlike [badge] this is not a name — it is a
     * capability the viewer is about to use (HD, 4K, CC), so it sits opposite the title, the way the
     * mockup puts the format marks at the far end of the row.
     */
    fun flag(key: String, label: String): TextView {
        val theme = ctx.theme
        val tv = part(key, overlay) {
            val t = TextView(ctx.app)
            t.includeFontPadding = false
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, theme.type("badge", 15f))
            t.setTextColor(theme.color("hdInk", "#E0E0E0"))
            t.setPadding(theme.px(8f), theme.px(3f), theme.px(8f), theme.px(3f))
            t.gravity = Gravity.CENTER
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.BOTTOM or Gravity.END
            lp.setMargins(0, 0, theme.px(10f), theme.px(10f))
            t.layoutParams = lp
            t
        }
        tv.text = label
        tv.visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
        tv.background = if (label.isEmpty()) null else Views.rounded(theme.color("scrim", "#8C141414"), theme.radius("badge", 4f))
        return tv
    }

    /**
     * The hub's source list carries the quality words; the viewer only needs to know the best one
     * this TV can actually reach. Nothing is claimed that the hub did not say.
     */
    fun qualityFlag(item: Item): String {
        val q = item.sources.map { it.quality.lowercase() }
        return when {
            q.any { it.contains("4k") || it.contains("2160") || it.contains("uhd") } -> "4K"
            q.any { it.contains("hd") || it.contains("1080") || it.contains("720") } -> "HD"
            else -> ""
        }
    }

    /** CC only when the hub says which subtitle languages exist for this title. */
    fun subtitleFlag(item: Item): String = if (item.subtitleLanguages.isNotEmpty()) "CC" else ""

    /**
     * A name drawn INSIDE the art, for the rows whose artwork is only a logo (live channels). The
     * viewer asked for no text under the card, so the text that a channel genuinely needs rides on
     * the artwork instead, over its own scrim.
     */
    fun overlayTitle(value: String) {
        val theme = ctx.theme
        val scrim = part("titleScrim", overlay) {
            View(ctx.app).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(64f), Gravity.BOTTOM)
                background = Views.gradient(intArrayOf(0x00000000, theme.color("scrimInk", "#EB000000")), 0)
            }
        }
        scrim.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
        val tv = textPart("overlayTitle", overlay, "caption", 19f, theme.ink, bold = true)
        tv.text = value
        tv.maxLines = 1
        tv.ellipsize = android.text.TextUtils.TruncateAt.END
        tv.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
        val lp = tv.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.BOTTOM or Gravity.START
        lp.width = layout.artWidth - 2 * theme.px(14f)
        lp.setMargins(theme.px(14f), 0, theme.px(14f), theme.px(12f))
        tv.layoutParams = lp
    }

    /** A recycled card must never keep the alpha/offset of an entrance animation. */
    fun resetMotion() {
        root.animate().cancel()
        root.alpha = 1f
        root.translationY = 0f
    }

    /**
     * The card's entrance: 600 ms fade-up, staggered by position. Only the first time a row is
     * filled — a card that re-animated on every scroll step is the difference between "Netflix" and
     * a screensaver.
     */
    fun animateIn(staggerIndex: Int) {
        val theme = ctx.theme
        val ms = theme.animMs("fadeUpMs", 600)
        if (ms <= 0 || staggerIndex < 0) return
        val shift = ctx.theme.px(ctx.theme.token("anim", "fadeUpShift", 20.0).toFloat()).toFloat()
        root.alpha = 0f
        root.translationY = shift
        root.animate().alpha(1f).translationY(0f)
            .setStartDelay((staggerIndex * ctx.theme.animMs("fadeUpStagger", 70)).toLong())
            .setDuration(ms.toLong())
            .start()
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
         * the D-pad stops instead of the card itself. `onOpen` says what a press means: Detail for
         * everything the viewer has not started yet, the player for "continue watching".
         */
        fun build(
            ctx: AppContext,
            layout: RowLayout,
            host: ScreenHost,
            cardFocusable: Boolean = true,
            onOpen: ((Item) -> Unit)? = null,
        ): CardHolder {
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
                // Nothing inside a card is a focus stop: the card is the thing the remote is on, and
                // its artwork carries the selection frame.
                root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                // With the travelling frame (FocusGlass) the card draws no ring of its own: two
                // highlights on one card is the flicker this replaces.
                val ringOn = if (FocusGlass.enabled(ctx)) null else art
                Focus.attach(root, ringOn, ctx, theme.radius("card", 6f), scale = true, onClick = {
                    holder.boundItem?.let { item ->
                        val open = onOpen
                        if (open != null) open(item) else host.openDetail(item.identity, item)
                    }
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
