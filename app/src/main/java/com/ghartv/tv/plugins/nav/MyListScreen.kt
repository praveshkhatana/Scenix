package com.scenix.tv.plugins.nav

import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.ScreenPlugin

/**
 * Nav screen: My List.
 *
 * For P1 the list is local (SharedPreferences per profile) because the hub's `mylist` row belongs to
 * another agent's file and does not exist yet. When it lands, this screen keeps working: the only
 * change is where the identity set comes from — see the stub list in the README.
 */
object MyListScreen : ScreenPlugin {
    override val id get() = "mylist"
    override val title get() = "My List"
    override val route get() = "mylist"
    override val order = 70

    override fun render(host: ScreenHost) {
        val ctx = host.app
        val saved = ctx.prefs.myList(ctx.profile?.id ?: "")
        BrowseHelper.render(host, id, title, heading = false, transform = { rows ->
            val ids = saved.toHashSet()
            val flat = BrowseHelper.flatten(rows, "mylist", "My List")
            val kept = flat.copy(items = flat.items.filter { ids.contains(it.identity) })
            listOf(kept).filter { it.items.isNotEmpty() }
        })
    }
}
