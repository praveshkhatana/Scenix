package com.scenix.tv.plugins.cards

import com.scenix.tv.core.AppContext
import com.scenix.tv.core.CardHolder
import com.scenix.tv.core.CardPlugin
import com.scenix.tv.core.Item
import com.scenix.tv.core.RowLayout
import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.metaSpacer
import com.scenix.tv.core.metaText

/**
 * `card: "channel"` — 16:9 art, a LIVE badge with a dot, "Now: …" in the match slot and the current
 * quality on the right. Live TV is a first-class row in the same visual language as movies
 * (kids-app-plan §8), and everything in it is the hub's `live` object — no channel lists here.
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
        // Numbered like a real TV line-up ("12 · &TV"), the number the hub assigned.
        val num = item.live?.number?.takeIf { n -> n > 0 }
        holder.title?.text = if (num != null) "$num \u00B7 ${item.title}" else item.title

        val now = item.live?.now ?: ""
        holder.metaText("now", if (now.isBlank()) "" else "Now: $now", theme.match, bold = true)
        holder.metaSpacer()
        holder.metaText("q", item.live?.quality ?: "", theme.ink3)
    }

    override fun unbind(holder: CardHolder) {
        holder.hide("live")
    }
}
