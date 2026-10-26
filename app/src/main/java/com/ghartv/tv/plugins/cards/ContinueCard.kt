package com.scenix.tv.plugins.cards

import com.scenix.tv.core.AppContext
import com.scenix.tv.core.CardHolder
import com.scenix.tv.core.CardPlugin
import com.scenix.tv.core.Format
import com.scenix.tv.core.Item
import com.scenix.tv.core.RowLayout
import com.scenix.tv.core.ScreenHost

/**
 * `card: "continue"` — the same art as every other card, the red bar of how far the viewer got, and
 * the honest time left. No caption: the row title already says what these are, and the bar says the
 * rest.
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

    /**
     * A row that promises to resume something has to resume it: OK goes to the player at the saved
     * position instead of the title page, which is one press fewer than every other row.
     */
    override fun open(item: Item, ctx: AppContext, host: ScreenHost) {
        host.play(item, ctx.playableSource(item))
    }

    override fun bind(holder: CardHolder, item: Item, position: Int, ctx: AppContext) {
        val theme = ctx.theme
        holder.loadArt(item)

        val p = item.progress
        if (p != null && p.positionMs > 0L) {
            holder.progressBar("prog", p.fraction, theme.brand, theme.color("track", "#2F2F2F"))
        } else {
            holder.hide("prog")
        }

        // Time left, straight from the model. (The mockup's "S1 E4" tag needs a season/episode
        // field the shared model does not define yet — see the P1 stub list.)
        holder.badge("left", Format.timeLeft(item), theme.color("scrim", "#BF000000"), theme.ink)

        holder.flag("hd", holder.qualityFlag(item))
        holder.flag("cc", holder.subtitleFlag(item))
    }

    override fun unbind(holder: CardHolder) {
        holder.hide("prog")
        holder.hide("left")
        holder.hide("hd")
        holder.hide("cc")
    }
}
