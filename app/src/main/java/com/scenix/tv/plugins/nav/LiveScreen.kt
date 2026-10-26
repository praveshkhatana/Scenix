package com.scenix.tv.plugins.nav

import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.ScreenPlugin

/**
 * Nav screen: live TV. Anything the hub sent as `type: "channel"` or with a `live` object.
 *
 * The `live` row on Home and this screen are the same data; this one just keeps every live item and
 * drops the movies that happen to sit between them.
 *
 * `requires = "live"`: the manifest key a backend sets when it has a channel line-up to hand out. This
 * app hosting itself has no tuner and no operator feed, so it answers that key false and this screen
 * leaves the nav — rather than sitting there as a tile that opens onto nothing.
 */
object LiveScreen : ScreenPlugin {
    override val id get() = "live"
    override val title get() = "Live TV"
    override val route get() = "live"
    override val order = 50
    override val requires = "live"

    override fun render(host: ScreenHost) {
        BrowseHelper.render(host, id, title, transform = { rows -> BrowseHelper.live(rows) })
    }
}
