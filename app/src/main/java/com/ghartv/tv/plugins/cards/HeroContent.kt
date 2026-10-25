package com.scenix.tv.plugins.cards

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.media3.ui.PlayerView
import com.scenix.tv.R
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.CardHolder
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Format
import com.scenix.tv.core.Item
import com.scenix.tv.core.Preview
import com.scenix.tv.core.Views

/**
 * The billboard content — art, scrims, brand mark, title, the maturity/quality chips, the reason
 * line and the Play / More Info / + / thumbs buttons.
 *
 * One implementation, three callers: the `hero` card plugin, the Home billboard and the Detail
 * header. That is why the Home billboard and the Detail hero cannot drift apart.
 */
object HeroContent {

    fun fill(holder: CardHolder, item: Item, ctx: AppContext, compact: Boolean = false) {
        val theme = ctx.theme
        val overlay = holder.overlay
        holder.loadArt(item)

        // Netflix: while a title holds the eye, its artwork quietly becomes a muted looping clip.
        // It lives inside the art frame so the scrims and text above it never move.
        val preview = holder.part("preview", holder.art) {
            PlayerView(ctx.app).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
                useController = false
                alpha = 0f
            }
        }
        (preview as? PlayerView)?.let { Preview.start(it, ctx, item) }

        // --- scrims: dark on the left for the text, dark at the bottom into the page background
        val leftScrim = holder.part("scrimL", overlay) {
            View(ctx.app).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }
        leftScrim.background = GradientScrim.horizontal(
            intArrayOf(theme.color("scrimInk", "#EB141414"), theme.color("scrimMid", "#8C141414"), Color.TRANSPARENT),
            floatArrayOf(0.06f, 0.42f, 0.72f),
        )
        val bottomScrim = holder.part("scrimB", overlay) {
            View(ctx.app).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }
        bottomScrim.background = GradientScrim.vertical(
            intArrayOf(Color.TRANSPARENT, theme.color("scrimMid", "#8C141414"), theme.bg),
            floatArrayOf(0f, 0.30f, 0.62f),
        )

        // --- text + buttons column
        val col = holder.part("heroText", overlay) {
            LinearLayout(ctx.app).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START,
                )
            }
        }
        val lp = col.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = theme.gutter
        lp.rightMargin = theme.gutter
        lp.bottomMargin = if (compact) theme.px(40f) else theme.px(78f)
        col.layoutParams = lp

        val mark = Views.text(ctx, "nmark", 40f, theme.brand, "", bold = true)
        mark.text = theme.brandMark()
        replaceText(col, "nmark", mark)

        val title = Views.text(ctx, if (compact) "h2" else "h1", if (compact) 56f else 104f, theme.ink, item.title, bold = true, maxLines = 2)
        replaceText(col, "title", title)

        // chips: maturity box, HD box, then the year · duration · languages line
        val chips = LinearLayout(ctx.app)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.gravity = Gravity.CENTER_VERTICAL
        replaceRow(col, "chips", chips, theme.px(20f))

        box(chips, "mat", Format.maturity(item), ctx)
        box(chips, "hd", if (item.sources.any { it.quality.isNotBlank() }) "HD" else "", ctx)
        val metaLine = arrayOf(
            Format.year(item),
            Format.duration(item.durationMs),
            Format.languages(item),
        ).filter { it.isNotBlank() }.joinToString(" \u00B7 ")
        val meta = Views.text(ctx, "matLine", 20f, theme.color("ink2", "#DCDCDC"), metaLine)
        val mlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        mlp.marginStart = theme.px(14f)
        chips.addView(meta, mlp)

        // reason line — the honest "why is this billboard here", straight off the model
        val reason = buildString {
            if (item.inCinemas) append("New release \u2014 opened in the last 8 weeks")
            if (item.match > 0) {
                if (isNotEmpty()) append(" \u00B7 ")
                append("${item.match}% match")
            }
            if (item.sources.size > 1) {
                if (isNotEmpty()) append(" \u00B7 ")
                append("${item.sources.size} sources merged behind one tile")
            }
            if (isEmpty() && item.genres.isNotEmpty()) append(item.genres.joinToString(" \u00B7 "))
        }
        val reasonView = Views.text(ctx, "sub", 24f, theme.color("ink1", "#E8E8E8"), reason, maxLines = 2)
        replaceText(col, "reason", reasonView)

        // buttons
        val btns = LinearLayout(ctx.app)
        btns.orientation = LinearLayout.HORIZONTAL
        btns.gravity = Gravity.CENTER_VERTICAL
        replaceRow(col, "btns", btns, theme.px(26f))

        val progress = item.progress
        val resumable = progress != null && progress.watchedMs > 0 && progress.positionMs > 0
        val play = button(
            ctx,
            label = if (resumable) "Resume" else "Play",
            bg = Color.WHITE,
            fg = Color.BLACK,
            icon = R.drawable.ic_play,
        )
        play.setOnClickListener { holder.host.play(item, holder.host.app.playableSource(item)) }
        replaceButton(btns, "play", play, ctx)

        val info = button(
            ctx,
            label = "More Info",
            bg = theme.color("btnGrey", "#B36D6D6E"),
            fg = theme.ink,
            icon = R.drawable.ic_info,
        )
        info.setOnClickListener { holder.host.openDetail(item.identity, item) }
        replaceButton(btns, "info", info, ctx)

        val listed = ctx.prefs.myList(ctx.profile?.id ?: "").contains(item.identity)
        val plus = roundButton(ctx, if (listed) R.drawable.ic_check else R.drawable.ic_plus, listed)
        plus.setOnClickListener {
            val pid = ctx.profile?.id ?: ""
            val added = ctx.prefs.toggleMyList(pid, item.identity)
            plus.setImageResource(if (added) R.drawable.ic_check else R.drawable.ic_plus)
            holder.host.toast(if (added) "Added to My List" else "Removed from My List")
        }
        replaceButton(btns, "plus", plus, ctx)

        val up = roundButton(ctx, R.drawable.ic_thumb_up, false)
        up.setOnClickListener { holder.host.toast("Ratings are not wired to the hub yet") }
        replaceButton(btns, "up", up, ctx)

        val down = roundButton(ctx, R.drawable.ic_thumb_down, false)
        down.setOnClickListener { holder.host.toast("Ratings are not wired to the hub yet") }
        replaceButton(btns, "down", down, ctx)
    }

    /** Clear the previous item's widgets before rebinding a recycled hero. */
    fun reset(holder: CardHolder) {
        // Release the preview before anything else: it is the one part holding a hardware decoder.
        (holder.find("preview") as? PlayerView)?.let { Preview.stop(it) }
        holder.find("heroText")?.visibility = View.GONE
        holder.find("scrimL")?.visibility = View.GONE
        holder.find("scrimB")?.visibility = View.GONE
    }

    // ---- button builders ------------------------------------------------------------------
    fun button(ctx: AppContext, label: String, bg: Int, fg: Int, icon: Int): LinearLayout {
        val theme = ctx.theme
        val b = LinearLayout(ctx.app)
        b.orientation = LinearLayout.HORIZONTAL
        b.gravity = Gravity.CENTER_VERTICAL
        b.setPadding(theme.px(34f), theme.px(16f), theme.px(34f), theme.px(16f))
        b.background = Views.rounded(bg, theme.radius("button", 6f))
        val iv = ImageView(ctx.app)
        iv.setImageResource(icon)
        iv.setColorFilter(fg)
        val s = Views.imageSize(ctx, "button", 26f)
        b.addView(iv, LinearLayout.LayoutParams(s, s))
        val tv = Views.text(ctx, "button", 26f, fg, label, bold = true)
        val tlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tlp.marginStart = theme.px(14f)
        b.addView(tv, tlp)
        return b
    }

    fun roundButton(ctx: AppContext, iconRes: Int, filled: Boolean): ImageView {
        val theme = ctx.theme
        val iv = ImageView(ctx.app)
        val size = theme.px(58f)
        iv.layoutParams = LinearLayout.LayoutParams(size, size)
        iv.setImageResource(iconRes)
        iv.setColorFilter(if (filled) theme.ink else theme.ink)
        iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = theme.px(15f)
        iv.setPadding(pad, pad, pad, pad)
        val ring = if (filled) theme.brand else theme.color("lineStrong", "#8FFFFFFF")
        iv.background = Views.filled(theme.color("scrim", "#8C141414"), size / 2, theme.px(2f), ring)
        return iv
    }

    /** Give a focusable control the same ring + lift language as the cards. */
    fun focusable(view: View, ctx: AppContext) {
        Focus.attach(view, view, ctx, ctx.theme.radius("button", 6f), scale = false)
    }

    private fun box(parent: LinearLayout, key: String, value: String, ctx: AppContext) {
        val theme = ctx.theme
        val tv = Views.text(ctx, "matLine", 19f, theme.ink, value)
        tv.setPadding(theme.px(12f), theme.px(2f), theme.px(12f), theme.px(2f))
        tv.background = Views.ring(theme.color("lineStrong", "#73FFFFFF"), theme.radius("chip", 4f), theme.px(1f))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginEnd = theme.px(14f)
        parent.addView(tv, lp)
        tv.visibility = if (value.isBlank()) View.GONE else View.VISIBLE
    }

    private fun replaceText(parent: LinearLayout, key: String, view: View) {
        parent.findViewWithTag<View>(key)?.let { parent.removeView(it) }
        view.tag = key
        parent.addView(view)
    }

    private fun replaceRow(parent: LinearLayout, key: String, row: LinearLayout, topMargin: Int) {
        parent.findViewWithTag<View>(key)?.let { parent.removeView(it) }
        row.tag = key
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = topMargin
        parent.addView(row, lp)
    }

    private fun replaceButton(parent: LinearLayout, key: String, view: View, ctx: AppContext) {
        parent.findViewWithTag<View>(key)?.let { parent.removeView(it) }
        view.tag = key
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (parent.childCount > 0) lp.marginStart = ctx.theme.px(16f)
        parent.addView(view, lp)
        focusable(view, ctx)
    }
}

/** Gradient with explicit stops, like the CSS `linear-gradient(...)` in the mockup. */
object GradientScrim {
    fun horizontal(colors: IntArray, stops: FloatArray): android.graphics.drawable.GradientDrawable =
        build(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, colors, stops)

    fun vertical(colors: IntArray, stops: FloatArray): android.graphics.drawable.GradientDrawable =
        build(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, colors, stops)

    private fun build(
        orientation: android.graphics.drawable.GradientDrawable.Orientation,
        colors: IntArray,
        stops: FloatArray,
    ): android.graphics.drawable.GradientDrawable {
        val d = android.graphics.drawable.GradientDrawable(orientation, colors)
        return try {
            d.setColors(colors, stops)
            d
        } catch (t: Throwable) {
            d
        }
    }
}
