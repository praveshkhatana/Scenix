package com.scenix.tv.plugins.cards

import com.scenix.tv.core.AppContext
import com.scenix.tv.core.CardHolder
import com.scenix.tv.core.CardPlugin
import com.scenix.tv.core.Item
import com.scenix.tv.core.RowLayout
import com.scenix.tv.core.ScreenHost

/**
 * `card: "hero"` — the full-bleed billboard card. Geometry comes from the theme's `cards.hero`
 * entry (`fullBleed: true` makes the renderer use the whole safe area instead of a fixed width);
 * the content is [HeroContent], shared with the Home billboard and the Detail header.
 */
object HeroCard : CardPlugin {
    override val id get() = "hero"
    override val cardType get() = "hero"
    override val order = 40

    override fun create(ctx: AppContext, host: ScreenHost, layout: RowLayout): CardHolder =
        CardHolder.build(ctx, layout, host, cardFocusable = false)

    override fun bind(holder: CardHolder, item: Item, position: Int, ctx: AppContext) {
        HeroContent.fill(holder, item, ctx, compact = false)
    }

    override fun unbind(holder: CardHolder) {
        HeroContent.reset(holder)
    }
}
