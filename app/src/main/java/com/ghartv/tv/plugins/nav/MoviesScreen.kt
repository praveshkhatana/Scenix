package com.scenix.tv.plugins.nav

import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.ScreenPlugin

/**
 * Nav screen: everything the hub tagged `type: "movie"`.
 *
 * The filter is the model's own field, not a name match — the hub decides what a movie is.
 */
object MoviesScreen : ScreenPlugin {
    override val id get() = "movies"
    override val title get() = "Movies"
    override val route get() = "movies"
    override val order = 30

    override fun render(host: ScreenHost) {
        BrowseHelper.render(host, id, title, transform = { rows -> BrowseHelper.byType(rows, "movie") })
    }
}
