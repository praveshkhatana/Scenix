package com.scenix.tv.plugins.cards

import com.scenix.tv.core.AppContext
import com.scenix.tv.core.CardHolder
import com.scenix.tv.core.CardPlugin
import com.scenix.tv.core.Format
import com.scenix.tv.core.Item
import com.scenix.tv.core.RowLayout
import com.scenix.tv.core.ScreenHost

/**
 * `card: "poster"` — the artwork and nothing under it.
 *
 * The caption block used to repeat the title the poster already carries and then run a meta line
 * ("98% Match · 16+ · 2h 14m · 9 sources") off the right edge of the card into its neighbour. On a
 * 10-foot UI the artwork is the label; what is worth saying at all (new, HD, subtitles) is said with
 * a mark on the art, where there is room for it.
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

        // "In cinemas" is the hub's flag (released within 8 weeks); a NEW ribbon rides it.
        if (item.inCinemas) {
            holder.badge("ribbon", "NEW", theme.brand, theme.ink)
        } else if (item.addedAt > 0L) {
            holder.badge("ribbon", Format.ago(item.addedAt), theme.color("scrim", "#BF000000"), theme.ink)
        } else {
            holder.hide("ribbon")
        }

        holder.flag("hd", holder.qualityFlag(item))
        holder.flag("cc", holder.subtitleFlag(item))
    }

    override fun unbind(holder: CardHolder) {
        holder.hide("ribbon")
        holder.hide("hd")
        holder.hide("cc")
    }
}
