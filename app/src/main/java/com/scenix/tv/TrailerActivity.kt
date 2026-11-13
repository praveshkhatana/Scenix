package com.scenix.tv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.HubEndpoint
import com.scenix.tv.core.Views

/**
 * The trailer a detail page promises, played here instead of handed to another app.
 *
 * The hub answers `/app/v1/trailer/<identity>` with a signed clip (usually a YouTube media URL)
 * and, in the same response, a proxied address that replays the headers the signed URL needs. On a
 * television there is frequently no browser or YouTube app to hand that URL to, so ACTION_VIEW
 * produced "No player for the trailer" (owner report, 2026-11-03). Media3 is already in this build
 * for the film; using it here keeps the promise inside the product.
 */
// The trailer screen owns its Media3 view configuration. Keep the experimental API acknowledgement
// at this narrow integration boundary rather than suppressing it application-wide.
@OptIn(UnstableApi::class)
class TrailerActivity : Activity() {

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app: AppContext = (application as ScenixApp).ctx
        val raw = intent?.getStringExtra(EXTRA_URL).orEmpty()
        if (raw.isBlank()) { finish(); return }
        // The hub's proxied trailer address is a path on this hub; a MediaItem needs an absolute
        // URL or ExoPlayer rejects it before the first byte.
        val url = if (raw.startsWith("/")) HubEndpoint.base(this) + raw else raw
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val view = PlayerView(this).apply {
            useController = true
            controllerAutoShow = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShutterBackgroundColor(Color.BLACK)
        }
        root.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val label = Views.text(app, "li", 20f, Color.WHITE,
            intent?.getStringExtra(EXTRA_TITLE).orEmpty(), bold = true)
        label.setPadding(app.theme.px(36f), app.theme.px(24f), app.theme.px(36f), 0)
        label.setShadowLayer(app.theme.px(8f).toFloat(), 0f, 0f, Color.BLACK)
        root.addView(label, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.START })
        setContentView(root)

        val p = ExoPlayer.Builder(this).build()
        view.player = p
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
        player = p
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
    }
}
