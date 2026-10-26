package com.scenix.tv.core

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * The Scenix move: while a title holds the eye, the billboard's artwork quietly becomes a muted
 * looping clip of THAT title. One implementation, two callers — the Home billboard and the Detail
 * header — which is why the two can never drift apart.
 *
 * There is exactly one preview in the app at a time, and it always draws in the hero: a second
 * decoder on a 2 GB TV is how a browse screen starts dropping frames. The hero hands its
 * [PlayerView] over with [setSurface], and every later request — the billboard's own title, or the
 * card the viewer is standing on — plays into that same view.
 *
 * Nothing here is required from the hub. `GET /app/v1/trailer/<identity>` may answer nothing, and
 * the artwork simply stays a still picture. URLs are remembered for the session, so the twentieth
 * pass over a row asks once, not twenty times.
 *
 * Releasing is not optional: the preview dies when its surface leaves the window, when the screen
 * is left, and the moment real playback starts.
 */
object Preview {

    private val main = Handler(Looper.getMainLooper())

    /** Trailer URLs for this session. A null value means "asked, and the hub has none". */
    private val urls = HashMap<String, String?>()
    private val asking = HashSet<String>()

    private var surface: PlayerView? = null
    private var player: ExoPlayer? = null
    private var dwell: Runnable? = null
    private var token = 0
    private var playingIdentity = ""
    private var previewFromCard = false
    private var headline: Item? = null

    // ---- the surface ----------------------------------------------------------------------

    /**
     * The hero gives us its [PlayerView]. Home's billboard and Detail's header both do this through
     * [com.scenix.tv.plugins.cards.HeroContent], so "the preview area" is always whatever the theme
     * says the hero is.
     */
    fun setSurface(pv: PlayerView) {
        if (surface === pv) return
        if (surface != null) release()
        surface = pv
        playingIdentity = ""
        if (pv.tag !is View.OnAttachStateChangeListener) {
            val l = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) { if (surface === v) release() }
            }
            pv.tag = l
            pv.addOnAttachStateChangeListener(l)
        }
    }

    /** The hero is going away (recycled or the screen left): free the decoder and forget the view. */
    fun clearSurface(pv: PlayerView) {
        if (surface !== pv) return
        release()
        surface = null
        headline = null
    }

    /** The item the billboard is showing, so a preview can fall back to it when focus leaves a card. */
    fun setHeadline(item: Item?) {
        headline = item
    }

    // ---- dwell on a card ------------------------------------------------------------------

    /**
     * A card under the eye for `preview.delayMs` gets its trailer in the hero. Focus moving, a key
     * press or a scroll cancels what has not started, so a clip never opens under a card the viewer
     * has already left, and never mid-scroll.
     */
    fun onCardFocus(ctx: AppContext, item: Item?, hasFocus: Boolean) {
        cancelPending()
        if (!hasFocus || item == null || item.identity.isBlank()) return
        if (surface == null) return
        val delay = ctx.theme.token("preview", "delayMs", 1500.0).toLong()
        if (delay <= 0L) return
        val r = Runnable {
            dwell = null
            previewFromCard = true
            show(ctx, item)
        }
        dwell = r
        main.postDelayed(r, delay)
    }

    /** Focus left a card: go back to the billboard's own title, once, and only if nothing else won. */
    fun onCardUnfocus(ctx: AppContext) {
        cancelPending()
        if (!previewFromCard) return
        val back = headline ?: return
        val wait = (ctx.theme.token("preview", "delayMs", 1500.0) / 2.0).toLong().coerceAtLeast(300L)
        val r = Runnable {
            dwell = null
            previewFromCard = false
            show(ctx, back)
        }
        dwell = r
        main.postDelayed(r, wait)
    }

    /** Something moved (a key press, a scroll): whatever has not started yet must not start. */
    fun cancelPending() {
        dwell?.let { main.removeCallbacks(it) }
        dwell = null
    }

    fun isDwelling(): Boolean = dwell != null

    /** True while a card's clip — not the billboard's own — owns the hero. Rotation waits for this. */
    fun isCardPreview(): Boolean = previewFromCard && (dwell != null || player != null)

    // ---- playback -------------------------------------------------------------------------

    /** Play this item's trailer in the hero. Safe to call for the same item twice. */
    fun show(ctx: AppContext, item: Item) {
        val pv = surface ?: return
        if (!pv.isAttachedToWindow) return
        if (ctx.theme.token("preview", "enabled", 1.0) < 0.5) return
        if (item.identity.isBlank()) return
        if (playingIdentity == item.identity && playerActive()) return

        val mine = ++token
        url(ctx, item.identity) { clip ->
            main.post {
                // A newer request, a different surface or a detached hero wins over this answer.
                if (mine != token || surface !== pv || !pv.isAttachedToWindow) return@post
                if (clip.isNullOrBlank()) {
                    // No trailer: the still artwork stays, and the hero is not left black.
                    releasePlayer()
                    return@post
                }
                begin(pv, clip, item.identity)
            }
        }
    }

    private fun playerActive(): Boolean = player?.let { it.isPlaying || it.playbackState == Player.STATE_BUFFERING } == true

    /** The hub lookup, cached. A transport failure is not an answer, so it is retried later. */
    private fun url(ctx: AppContext, identity: String, cb: (String?) -> Unit) {
        if (urls.containsKey(identity)) {
            cb(urls[identity])
            return
        }
        if (!asking.add(identity)) return
        ctx.hub.trailer(identity, ctx.profile?.id) { value, error ->
            main.post {
                asking.remove(identity)
                if (urls.size > 300) urls.clear()
                val v = value?.takeIf { it.isNotBlank() }
                if (error != null) urls.remove(identity) else urls[identity] = v
                cb(if (error != null) null else v)
            }
        }
    }

    private fun begin(pv: PlayerView, url: String, identity: String) {
        releasePlayer()
        playingIdentity = identity
        val exo = ExoPlayer.Builder(pv.context).build()
        exo.volume = 0f                                     // silent: sound stays a deliberate choice
        exo.repeatMode = Player.REPEAT_MODE_ONE             // loop the clip while the card is up
        exo.playWhenReady = true                            // without this Media3 never actually starts
        pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM  // fill the frame, crop the edges
        pv.alpha = 0f                                       // the poster stays until the first frame
        pv.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) pv.animate().alpha(1f).setDuration(650L).start()
            }
        })
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        player = exo
    }

    private fun releasePlayer() {
        player?.let { p -> runCatching { p.stop(); p.release() } }
        player = null
        playingIdentity = ""
        surface?.let { pv ->
            pv.animate().alpha(0f).setDuration(220L)
                .withEndAction { if (surface === pv) pv.player = null }
                .start()
        }
    }

    /** Stop the clip and free the decoder. Called on screen leave, on Play, and on app stop. */
    fun release() {
        cancelPending()
        token++
        previewFromCard = false
        releasePlayer()
    }
}
