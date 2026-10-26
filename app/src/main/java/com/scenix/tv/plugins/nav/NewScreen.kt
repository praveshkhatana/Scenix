package com.scenix.tv.plugins.nav

import com.scenix.tv.core.Item
import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.ScreenPlugin

/**
 * Nav screen: newest first. The hub's `addedAt` is the only fact used — no "new" flag, no content
 * rule — so a provider that starts stamping `addedAt` shows up here with no app change.
 */
object NewScreen : ScreenPlugin {
    override val id get() = "new"
    override val title get() = "New & Popular"
    override val route get() = "new"
    override val order = 60

    override fun render(host: ScreenHost) {
        BrowseHelper.render(host, id, title, heading = false, transform = { rows ->
            val flat = BrowseHelper.flatten(rows, "new", "Newest first")
            val sorted = flat.copy(items = flat.items.sortedWith(
                compareByDescending<Item> { it.addedAt }.thenByDescending { it.match },
            ))
            listOf(sorted).filter { it.items.isNotEmpty() }
        })
    }
}
