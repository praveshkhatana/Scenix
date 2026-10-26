package com.scenix.tv.plugins.player

import androidx.media3.exoplayer.ExoPlayer
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.PlayTarget
import com.scenix.tv.core.PlayerPlugin
import com.scenix.tv.core.Source

/**
 * `kind: "hls"` — the adaptive ladder. This is the kind the home network can actually survive: the
 * player walks down by itself when the Wi-Fi gets bad instead of stalling.
 *
 * The ceiling still applies: the ladder is told to stop at 720p / 6 Mbps so a 4 K variant is never
 * even requested on this panel.
 */
object HlsPlayer : PlayerPlugin {
    override val id get() = "player-hls"
    override val title get() = "HLS adaptive"
    override val kind get() = "hls"
    override val order = 20

    override fun loadControl(app: AppContext): androidx.media3.exoplayer.LoadControl = Ceilings.loadControl(app)

    override fun configure(player: ExoPlayer, app: AppContext, source: Source, target: PlayTarget) {
        Ceilings.applyDefaults(player, app)
        // Mixed-MIME adaptiveness is an ExoPlayer track-selector option, not a media3-common one:
        // a ladder that switches between TS and fMP4 variants needs it turned on to keep going.
        val base = player.trackSelectionParameters
        if (base is androidx.media3.exoplayer.trackselection.DefaultTrackSelector.Parameters) {
            player.trackSelectionParameters = base.buildUpon()
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .build()
        }
    }

    override fun statusLine(source: Source, target: PlayTarget): String {
        val q = source.quality.ifBlank { "ladder" }
        return "HLS ladder \u00B7 $q \u00B7 adaptive"
    }
}
