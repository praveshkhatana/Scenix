package com.scenix.tv.plugins.nav

import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.ScreenPlugin

/** Nav screen: everything the hub tagged `type: "series"`. */
object SeriesScreen : ScreenPlugin {
    override val id get() = "series"
    override val title get() = "Series"
    override val route get() = "series"
    override val order = 40

    override fun render(host: ScreenHost) {
        BrowseHelper.render(host, id, title, transform = { rows -> BrowseHelper.byType(rows, "series") })
    }
}
