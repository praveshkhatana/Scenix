package com.scenix.tv.plugins.player

import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Format

/**
 * The ceilings the TV shell shares, in one place, because this app is written for one known TV:
 * Sony BRAVIA, Android 11, armeabi-v7a, 2 GB RAM.
 *
 * The mockup's player readout says "720p · 6.0 Mbps", and that is a hard cap, not a hint — it is
 * what keeps a 4 K source from decoding itself to death on this panel. The numbers live in
 * `res/values/config.xml` (and `theme.json` mirrors them for the overlay text) so a faster TV is a
 * config change, not a code change.
 */
object Ceilings {

    fun applyDefaults(player: ExoPlayer, app: AppContext) {
        val theme = app.theme
        val params = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(theme.playerMaxWidth, theme.playerMaxHeight)
            .setMaxVideoBitrate(theme.playerMaxBitrate)
            .setMinVideoSize(theme.playerMinWidth, theme.playerMinHeight)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
        player.trackSelectionParameters = params
    }

    /**
     * Seek increments are fixed when the player is built (media3 has no setter for them afterwards),
     * so the plugin that knows the content hands this to whoever builds the ExoPlayer.
     */
    fun seekIncrements(builder: ExoPlayer.Builder, app: AppContext): ExoPlayer.Builder =
        builder
            .setSeekBackIncrementMs(app.theme.seekBackMs.toLong())
            .setSeekForwardIncrementMs(app.theme.seekForwardMs.toLong())

    /** 30 s behind (rewind without re-requesting) and 120 s ahead, from config/theme. */
    fun loadControl(app: AppContext): DefaultLoadControl {
        val theme = app.theme
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                theme.playerMinBufferMs,
                theme.playerForwardBufferMs,
                theme.playerPlaybackBufferMs,
                theme.playerPlaybackBufferMs * 2,
            )
            .setBackBuffer(theme.playerBackBufferMs, true)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

}
