package com.scenix.tv.core

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * The Netflix move: while a title sits under the eye, its artwork quietly becomes a muted looping
 * clip. Only the hero ever does this, and only after the card has been on screen long enough that
 * the viewer is clearly looking at it — a fast scroll past twenty posters must not open a decoder
 * twenty times.
 *
 * The clip comes from the hub (`GET /app/v1/trailer/<identity>`), so which trailer, from where, and
 * whether one exists at all stays hub policy. A hub that has no trailers answers nothing and the
 * artwork simply stays a still picture: the feature is additive, never required.
 *
 * Releasing is not optional on this hardware. One TV, 2 GB RAM, and a full-screen decoder already
 * reserved for real playback — so a preview dies when its card is re-bound or leaves the window.
 */
object Preview {

    /** Per-view state: a rebind bumps `token`, which cancels a clip still travelling from the hub. */
    private class Session {
        var token = 0
        var player: ExoPlayer? = null
        var pending: Runnable? = null
    }

    fun start(pv: PlayerView, ctx: AppContext, item: Item) {
        stop(pv)
        val theme = ctx.theme
        if (theme.token("preview", "enabled", 1.0) < 0.5) return

        val s = Session()
        pv.tag = s
        val token = ++s.token

        // Leaving the window (a screen change, the viewer pressing Play) must free the decoder.
        pv.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) = Unit
            override fun onViewDetachedFromWindow(v: android.view.View) { if (pv.tag === s) stop(pv) }
        })

        ctx.hub.trailer(item.identity) { url, _ ->
            val clip = url?.takeIf { it.isNotBlank() } ?: return@trailer
            // Hub callbacks arrive off the main thread; this post is both the thread hop and the
            // "hovered long enough" timer.
            val fire = Runnable {
                if (pv.tag !== s || s.token != token || !pv.isAttachedToWindow) return@Runnable
                begin(pv, s, clip, theme)
            }
            s.pending = fire
            pv.postDelayed(fire, theme.token("preview", "delayMs", 1400.0).toLong())
        }
    }

    private fun begin(pv: PlayerView, s: Session, url: String, theme: Theme) {
        s.player?.let { old -> runCatching { old.stop(); old.release() } }
        val exo = ExoPlayer.Builder(pv.context).build()
        exo.volume = 0f                                     // silent: sound stays a deliberate choice
        exo.repeatMode = Player.REPEAT_MODE_ONE             // loop the clip while the card is up
        pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM  // fill the frame, crop the edges
        pv.alpha = 0f
        pv.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) pv.animate().alpha(1f).setDuration(650L).start()
            }
        })
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        s.player = exo
    }

    fun stop(pv: PlayerView) {
        (pv.tag as? Session)?.let { s ->
            s.token++                                   // cancels a clip that has not fired yet
            s.pending?.let { pv.removeCallbacks(it) }
            s.player?.let { old ->
                old.stop()
                runCatching { old.release() }
            }
            s.player = null
        }
        pv.tag = null
        if (pv.player != null) {
            pv.player = null
            pv.alpha = 0f                               // back to the still artwork
        }
    }
}
