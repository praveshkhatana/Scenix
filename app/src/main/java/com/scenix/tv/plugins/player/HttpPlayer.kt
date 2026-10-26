package com.scenix.tv.plugins.player

import androidx.media3.exoplayer.ExoPlayer
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Format
import com.scenix.tv.core.PlayTarget
import com.scenix.tv.core.PlayerPlugin
import com.scenix.tv.core.Source

/**
 * `kind: "http"` — a direct progressive stream (the hub's own debrid/direct providers, and the
 * proxy the launcher already knows how to feed).
 *
 * It is also the registry's fallback: a hub that invents a new kind still plays, with the same
 * ceilings, and the log says which kind had no plugin.
 */
object HttpPlayer : PlayerPlugin {
    override val id get() = "player-http"
    override val title get() = "HTTP progressive"
    override val kind get() = "http"
    override val order = 10

    override fun loadControl(app: AppContext): androidx.media3.exoplayer.LoadControl = Ceilings.loadControl(app)

    override fun configure(player: ExoPlayer, app: AppContext, source: Source, target: PlayTarget) {
        Ceilings.applyDefaults(player, app)
    }

    override fun statusLine(source: Source, target: PlayTarget): String {
        val q = source.quality.ifBlank { "auto" }
        val size = Format.size(source.sizeBytes)
        return "HTTP \u00B7 $q" + (if (size.isBlank()) "" else " \u00B7 $size")
    }
}
