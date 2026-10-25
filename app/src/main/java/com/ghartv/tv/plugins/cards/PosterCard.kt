package com.scenix.tv.plugins.cards

import com.scenix.tv.core.AppContext
import com.scenix.tv.core.CardHolder
import com.scenix.tv.core.CardPlugin
import com.scenix.tv.core.Format
import com.scenix.tv.core.Item
import com.scenix.tv.core.RowLayout
import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.metaSpacer
import com.scenix.tv.core.metaText

/**
 * `card: "poster"` — 2:3 portrait art with the caption block under it:
 * title, then "98% Match · 16+ · 2h 14m · 2026" ("N sources" where the merge matters).
 *
 * This is also the fallback for any card id the app does not know yet (see `Registry.resolve`), so
 * a hub that starts sending a new card string still renders, and the log says which renderer to add.
 */
object PosterCard : CardPlugin {
    override val id get() = "poster"
    override val cardType get() = "poster"
    override val order = 10

    override fun create(ctx: AppContext, host: ScreenHost, layout: RowLayout): CardHolder =
        CardHolder.build(ctx, layout, host)

    override fun bind(holder: CardHolder, item: Item, position: Int, ctx: AppContext) {
        val theme = ctx.theme
        holder.loadArt(item)
        holder.title?.text = item.title

        holder.metaText("match", Format.match(item), theme.match, bold = true)
        holder.metaText("mat", Format.maturity(item), theme.ink2)
        holder.metaText("dur", Format.duration(item.durationMs), theme.ink2)
        holder.metaText("src", Format.sourceCount(item), theme.ink2)
        holder.metaSpacer()
        holder.metaText("yr", Format.year(item), theme.ink3)

        // "In cinemas" is the hub's flag (released within 8 weeks); a NEW ribbon rides it.
        if (item.inCinemas) {
            holder.badge("ribbon", "NEW", theme.brand, theme.ink)
        } else if (item.addedAt > 0L) {
            holder.badge("ribbon", Format.ago(item.addedAt), theme.color("scrim", "#BF000000"), theme.ink)
        } else {
            holder.hide("ribbon")
        }
    }

    override fun unbind(holder: CardHolder) {
        holder.hide("ribbon")
    }
}
