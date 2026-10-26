package com.scenix.tv.plugins.cards

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
 * The billboard content — art, scrims, brand mark, title, the match/maturity/runtime/quality line,
 * the reason line and the Play / More Info / + buttons.
 *
 * One implementation, three callers: the `hero` card plugin, the Home billboard and the Detail
 * header. That is why the Home billboard and the Detail hero cannot drift apart, and why a trailer
 * that starts on the billboard behaves exactly like one that starts on the title page.
 *
 * Netflix's geometry, from the mockup: Play is a fixed 320 x 72 white pill that turns brand red
 * under the eye, More Info is the same height in translucent white, and the whole text column is a
 * bounded box, so the info line ellipsizes at the edge of the hero instead of running off a 1920 px
 * screen — the "2h 20m · PA · E" clipping the screenshot showed.
 */
object HeroContent {

    fun fill(holder: CardHolder, item: Item, ctx: AppContext, compact: Boolean = false) {
        val theme = ctx.theme
        val overlay = holder.overlay
        holder.loadArt(item)

        // The hero is the preview surface for the whole screen: whatever card the viewer stands on
        // plays here, not on the card. It lives inside the art frame so the scrims and text above it
        // never move.
        val preview = holder.part("preview", holder.art) {
            PlayerView(ctx.app).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
                useController = false
                alpha = 0f
            }
        }
        (preview as? PlayerView)?.let {
            Preview.setSurface(it)
            Preview.setHeadline(item)
            Preview.show(ctx, item)
        }

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

        // --- text + buttons column. Its width is fixed, not wrap_content: an unbounded column can
        // push the info line past the right edge of the screen, which is how the meta text ran off.
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
        lp.width = (holder.layout.artWidth - 2 * theme.gutter).coerceAtLeast(theme.px(400f))
        col.layoutParams = lp

        val mark = Views.text(ctx, "nmark", 40f, theme.brand, "", bold = true)
        mark.text = theme.brandMark()
        replaceText(col, "nmark", mark)

        val title = Views.text(ctx, if (compact) "h2" else "h1", if (compact) 56f else 104f, theme.ink, item.title, bold = true, maxLines = 2)
        replaceText(col, "title", title)

        // Info line, in Netflix's order: match, maturity box, runtime, then the format marks.
        // The last field is the only one allowed to shrink, and it is the least important one.
        val chips = LinearLayout(ctx.app)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.gravity = Gravity.CENTER_VERTICAL
        replaceRow(col, "chips", chips, theme.px(18f))

        plainText(chips, "match", if (item.match > 0) "${item.match}% Match" else "", ctx, theme.match, bold = true)
        box(chips, "mat", Format.maturity(item), ctx)
        plainText(
            chips, "runtime",
            arrayOf(Format.year(item), Format.duration(item.durationMs)).filter { it.isNotBlank() }.joinToString(" \u00B7 "),
            ctx, theme.color("ink2", "#DCDCDC"),
        )
        box(chips, "hd", holder.qualityFlag(item), ctx)
        box(chips, "cc", holder.subtitleFlag(item), ctx)
        flexText(chips, "langs", Format.languages(item), ctx)

        // reason line — the honest "why is this billboard here", straight off the model
        val reason = buildString {
            if (item.inCinemas) append("New release \u2014 opened in the last 8 weeks")
            if (item.sources.size > 1) {
                if (isNotEmpty()) append(" \u00B7 ")
                append("${item.sources.size} sources merged behind one tile")
            }
            if (isEmpty() && item.genres.isNotEmpty()) append(item.genres.joinToString(" \u00B7 "))
        }
        val reasonView = Views.text(ctx, "sub", 24f, theme.color("ink1", "#E8E8E8"), reason, maxLines = 2)
        replaceText(col, "reason", reasonView)

        // --- buttons: Play, then More Info. Ratings are not wired to the hub, so the thumbs the
        // early mockup had are gone rather than a button that can only say "not wired yet".
        val btns = LinearLayout(ctx.app)
        btns.orientation = LinearLayout.HORIZONTAL
        btns.gravity = Gravity.CENTER_VERTICAL
        replaceRow(col, "btns", btns, theme.px(26f))

        val progress = item.progress
        val resumable = progress != null && progress.watchedMs > 0 && progress.positionMs > 0
        // A series Play names the episode it is about to start. A bare "Play" on a show is a promise no
        // one can keep — the hub decides the episode (the one this profile stopped on, else the first) —
        // so the button says which one it means, the way a real service does.
        val ep = progress?.episode ?: item.firstEpisode()
        val episodeHint = if (item.type == "series" && ep != null) " ${ep.label()}" else ""
        val play = heroButton(
            ctx,
            label = (if (resumable) "Resume" else "Play") + episodeHint,
            icon = R.drawable.ic_play,
            bg = Color.WHITE,
            fg = Color.BLACK,
            focusBg = theme.brand,
            focusFg = Color.WHITE,
            width = theme.px(theme.token("hero", "playWidth", 320.0).toFloat()),
        )
        play.setOnClickListener { holder.host.play(item, holder.host.app.playableSource(item)) }
        replaceButton(btns, "play", play, ctx)

        val info = heroButton(
            ctx,
            label = "More Info",
            icon = R.drawable.ic_info,
            bg = theme.color("btnGrey", "#1AFFFFFF"),
            fg = theme.ink,
            focusBg = theme.color("btnGreyHover", "#33FFFFFF"),
            focusFg = theme.ink,
            width = 0,
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
    }

    /** Clear the previous item's widgets before rebinding a recycled hero. */
    fun reset(holder: CardHolder) {
        // Release the preview before anything else: it is the one part holding a hardware decoder.
        (holder.find("preview") as? PlayerView)?.let { Preview.clearSurface(it) }
        holder.find("heroText")?.visibility = View.GONE
        holder.find("scrimL")?.visibility = View.GONE
        holder.find("scrimB")?.visibility = View.GONE
    }

    // ---- button builders ------------------------------------------------------------------

    /**
     * A hero button: fixed height, pill radius from the theme, and the whole control is the focus
     * ring's owner. Play is white with black ink and turns brand red under the eye; More Info is
     * translucent white. Both restyle on focus through [Focus.setFocusHooks], because a card (and a
     * hero) gets exactly one focus listener.
     */
    private fun heroButton(
        ctx: AppContext,
        label: String,
        icon: Int,
        bg: Int,
        fg: Int,
        focusBg: Int,
        focusFg: Int,
        width: Int,
    ): LinearLayout {
        val theme = ctx.theme
        val radius = theme.radius("heroButton", 12f)
        val height = theme.px(theme.token("hero", "playHeight", 72.0).toFloat())
        val b = LinearLayout(ctx.app)
        b.orientation = LinearLayout.HORIZONTAL
        b.gravity = Gravity.CENTER
        b.setPadding(theme.px(30f), 0, theme.px(30f), 0)
        b.minimumHeight = height
        b.background = Views.rounded(bg, radius)
        val iv = ImageView(ctx.app)
        iv.setImageResource(icon)
        iv.setColorFilter(fg)
        val s = Views.imageSize(ctx, "button", 26f)
        b.addView(iv, LinearLayout.LayoutParams(s, s))
        val tv = Views.text(ctx, "button", 26f, fg, label, bold = true)
        val tlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tlp.marginStart = theme.px(14f)
        b.addView(tv, tlp)

        Focus.attach(b, b, ctx, radius, scale = false)
        Focus.setFocusHooks(b) { v, has ->
            v.background = Views.rounded(if (has) focusBg else bg, radius)
            (v as? LinearLayout)?.let { row ->
                (row.getChildAt(0) as? ImageView)?.setColorFilter(if (has) focusFg else fg)
                (row.getChildAt(1) as? TextView)?.setTextColor(if (has) focusFg else fg)
            }
        }
        // width 0 means "as wide as its label": only Play is a fixed 320 x 72.
        b.layoutParams = LinearLayout.LayoutParams(if (width > 0) width else ViewGroup.LayoutParams.WRAP_CONTENT, height)
        return b
    }

    fun roundButton(ctx: AppContext, iconRes: Int, filled: Boolean): ImageView {
        val theme = ctx.theme
        val iv = ImageView(ctx.app)
        val size = theme.px(58f)
        iv.layoutParams = LinearLayout.LayoutParams(size, size)
        iv.setImageResource(iconRes)
        iv.setColorFilter(theme.ink)
        iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = theme.px(15f)
        iv.setPadding(pad, pad, pad, pad)
        val ring = if (filled) theme.brand else theme.color("lineStrong", "#8FFFFFFF")
        iv.background = Views.filled(theme.color("scrim", "#8C141414"), size / 2, theme.px(2f), ring)
        Focus.attach(iv, iv, ctx, size / 2, scale = false)
        return iv
    }

    /** Give a focusable control the same ring + lift language as the cards. */
    fun focusable(view: View, ctx: AppContext) {
        Focus.attach(view, view, ctx, ctx.theme.radius("button", 6f), scale = false)
    }

    /** The maturity / HD / CC box: 2 px outline, 4 px radius, uppercase, tracked (the mockup's `.box`). */
    private fun box(parent: LinearLayout, key: String, value: String, ctx: AppContext) {
        val theme = ctx.theme
        val tv = Views.text(ctx, "matLine", 19f, theme.ink, value.uppercase())
        tv.letterSpacing = 0.08f
        tv.setPadding(theme.px(10f), theme.px(2f), theme.px(10f), theme.px(2f))
        tv.background = Views.ring(
            theme.color("matBorder", "#59FFFFFF"),
            theme.radius("chip", 4f),
            theme.px(2f),
        )
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginEnd = theme.px(12f)
        parent.addView(tv, lp)
        tv.tag = key
        tv.visibility = if (value.isBlank()) View.GONE else View.VISIBLE
    }

    /** A plain field of the info line (match, runtime). */
    private fun plainText(parent: LinearLayout, key: String, value: String, ctx: AppContext, color: Int, bold: Boolean = false) {
        val theme = ctx.theme
        val tv = Views.text(ctx, "matLine", 19f, color, value, bold = bold)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginEnd = theme.px(12f)
        parent.addView(tv, lp)
        tv.tag = key
        tv.visibility = if (value.isBlank()) View.GONE else View.VISIBLE
    }

    /**
     * The one field of the info line that may be cut: it takes whatever width is left and ellipsizes,
     * so a title in five languages never pushes the hero's text off the right edge of the TV.
     */
    private fun flexText(parent: LinearLayout, key: String, value: String, ctx: AppContext) {
        val theme = ctx.theme
        val tv = Views.text(ctx, "matLine", 19f, theme.color("ink3", "#B0B0B0"), value)
        tv.ellipsize = android.text.TextUtils.TruncateAt.END
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.marginEnd = theme.px(12f)
        parent.addView(tv, lp)
        tv.tag = key
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
        val lp = view.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (parent.childCount > 0) lp.marginStart = ctx.theme.gap
        parent.addView(view, lp)
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
