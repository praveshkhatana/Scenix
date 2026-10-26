package com.scenix.tv.plugins.nav

import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.ScreenPlugin

/**
 * Nav screen: the rows the hub tagged `scope: "kids"`.
 *
 * On a kids profile the rule pipeline (plugins/rules/KidsGuard.kt) has already stripped adult items
 * out of every row, so this screen matters most for grown-up profiles, where it is an opt-in
 * corner rather than a wall.
 */
object KidsScreen : ScreenPlugin {
    override val id get() = "kids"
    override val title get() = "Kids"
    override val route get() = "kids"
    override val order = 80

    override fun render(host: ScreenHost) {
        BrowseHelper.render(host, id, title, transform = { rows -> BrowseHelper.byScope(rows, "kids") })
    }
}
