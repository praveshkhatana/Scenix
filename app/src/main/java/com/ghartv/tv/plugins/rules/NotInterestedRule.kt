package com.scenix.tv.plugins.rules

import com.scenix.tv.core.Item
import com.scenix.tv.core.RuleCtx
import com.scenix.tv.core.RulePlugin

/**
 * The MENU key on a card says "not interested"; this is where that answer comes back. The hub has no
 * notion of a viewer refusing a title, so the block list is per-profile and local to this TV, and
 * the rule drops those identities from every row on its way to the screen — billboard included.
 *
 * An empty profile id (the picker) blocks nothing, so a viewer cannot hide content before choosing
 * who is watching.
 */
object NotInterested : RulePlugin {
    override val id get() = "not-interested"
    override val title get() = "Hide titles this profile said \u201Cnot interested\u201D to (MENU key)"
    override val order = 40

    override fun applyItem(item: Item, rctx: RuleCtx): Item? {
        val profileId = rctx.profile.id
        if (profileId.isBlank() || item.identity.isBlank()) return item
        return if (item.identity in rctx.app.prefs.notInterested(profileId)) null else item
    }
}
