package com.scenix.tv.plugins.player

import androidx.media3.exoplayer.ExoPlayer
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.PlayTarget
import com.scenix.tv.core.PlayerPlugin
import com.scenix.tv.core.Source

/**
 * `kind: "yt"` — the hub resolved a YouTube page into a direct media URL (its own yt-dlp step), so
 * the app never needs a YouTube SDK, an API key, or a second decoder on a 2 GB panel.
 *
 * The URL is signed and short-lived, which is why `POST /app/v1/play` is called at the moment of
 * play rather than cached with the row.
 */
object YtPlayer : PlayerPlugin {
    override val id get() = "player-yt"
    override val title get() = "YouTube (hub-resolved)"
    override val kind get() = "yt"
    override val order = 30

    override fun loadControl(app: AppContext): androidx.media3.exoplayer.LoadControl = Ceilings.loadControl(app)

    override fun configure(player: ExoPlayer, app: AppContext, source: Source, target: PlayTarget) {
        Ceilings.applyDefaults(player, app)
    }

    override fun riskLabel(source: Source): String? =
        source.risk.takeIf { it.isNotBlank() } ?: "signed link \u00B7 expires"

    override fun riskToken(source: Source): String = "warn"

    override fun statusLine(source: Source, target: PlayTarget): String {
        val q = source.quality.ifBlank { "auto" }
        return "YouTube via hub \u00B7 $q \u00B7 link expires" +
            (if (target.expiresAt > 0) "" else "")
    }
}
