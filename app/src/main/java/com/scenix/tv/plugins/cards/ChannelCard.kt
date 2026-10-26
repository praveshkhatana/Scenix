package com.scenix.tv.plugins.cards

import com.scenix.tv.core.AppContext
import com.scenix.tv.core.CardHolder
import com.scenix.tv.core.CardPlugin
import com.scenix.tv.core.Item
import com.scenix.tv.core.RowLayout
import com.scenix.tv.core.ScreenHost

/**
 * `card: "channel"` — the same tile size as every other card in the shelf, a LIVE badge with a dot,
 * and the channel name written INSIDE the artwork.
 *
 * Live TV is the one case where the art really is only a logo, so a name is not decoration — without
 * it two identical black tiles differ by nothing the viewer can read. It rides on the art over its
 * own scrim rather than under it, which is the only way to name a channel and still keep the shelves
 * the same height.
 *
 * Numbered like a real TV line-up ("12 · &TV"), with the number the hub assigned. Live TV is a
 * first-class row in the same visual language as movies (kids-app-plan §8) and everything in it is
 * the hub's `live` object — no channel lists here.
 */
object ChannelCard : CardPlugin {
    override val id get() = "channel"
    override val cardType get() = "channel"
    override val order = 20

    override fun create(ctx: AppContext, host: ScreenHost, layout: RowLayout): CardHolder =
        CardHolder.build(ctx, layout, host)

    override fun bind(holder: CardHolder, item: Item, position: Int, ctx: AppContext) {
        val theme = ctx.theme
        holder.loadArt(item)
        // Channel art is usually a logo on transparency: give it a dark plate to sit on.
        if (item.poster.isNullOrBlank() && !item.logo.isNullOrBlank()) holder.setArtBackground(theme.surface2)

        holder.badge("live", if (item.isLive()) "LIVE" else "", theme.live, theme.ink, dot = true)

        val num = item.live?.number?.takeIf { n -> n > 0 }
        holder.overlayTitle(if (num != null) "$num \u00B7 ${item.title}" else item.title)

        val q = item.live?.quality?.uppercase().orEmpty()
        holder.flag("hd", when {
            q.contains("4K") || q.contains("2160") -> "4K"
            q.contains("HD") || q.contains("1080") || q.contains("720") -> "HD"
            else -> ""
        })
    }

    override fun unbind(holder: CardHolder) {
        holder.hide("live")
        holder.hide("hd")
        holder.overlayTitle("")
    }
}
