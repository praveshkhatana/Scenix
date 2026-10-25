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
 * `card: "continue"` — 16:9 art, the red progress bar, and the honest time-left badge.
 *
 * Resume position and total length both come from the hub (`progress.positionMs` /
 * `progress.watchedMs`), so "continue watching" survives a reinstall and moves between TVs.
 */
object ContinueCard : CardPlugin {
    override val id get() = "continue"
    override val cardType get() = "continue"
    override val order = 30

    override fun create(ctx: AppContext, host: ScreenHost, layout: RowLayout): CardHolder =
        CardHolder.build(ctx, layout, host)

    override fun bind(holder: CardHolder, item: Item, position: Int, ctx: AppContext) {
        val theme = ctx.theme
        holder.loadArt(item)
        holder.title?.text = item.title

        val p = item.progress
        if (p != null && p.watchedMs > 0L) {
            holder.progressBar("prog", p.fraction, theme.brand, theme.color("track", "#47FFFFFF"))
        } else {
            holder.hide("prog")
        }

        // Time left, straight from the model. (The mockup's "S1 E4" tag needs a season/episode
        // field the shared model does not define yet — see the P1 stub list.)
        holder.badge("left", Format.timeLeft(item), theme.color("scrim", "#BF000000"), theme.ink)

        holder.metaText("resume", Format.clock(p?.positionMs ?: 0L), theme.ink3)
        holder.metaSpacer()
        holder.metaText("dur", Format.duration(item.durationMs), theme.ink3)
    }

    override fun unbind(holder: CardHolder) {
        holder.hide("prog")
        holder.hide("left")
    }
}
