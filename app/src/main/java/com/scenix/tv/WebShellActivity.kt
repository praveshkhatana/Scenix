package com.scenix.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.scenix.tv.core.FixtureServer
import com.scenix.tv.core.LocalServer
import com.scenix.tv.plugins.player.Ceilings
import java.util.concurrent.TimeUnit

/**
 * The hybrid shell: the hub's web UI, with this device's real player underneath it.
 *
 * This is the answer to "why can't the TV app and the browser app look identical" — they run the same page
 * from the same hub. What differs is only where the picture comes from. A browser is handed a re-muxed
 * fragmented MP4 because that is all a browser can decode. Here the page draws every pixel of interface —
 * shelves, focus ring, control bar, the Audio / Subtitles / Quality pickers — and hands one JSON object to
 * media3, which decodes the file exactly as the torrent holds it: HEVC 10-bit, E-AC3/DTS passed through to
 * the receiver, ASS subtitles rendered natively. Re-muxing for the TV would throw all of that away and put
 * a transcode on the hub for every viewing.
 *
 * Stacking, bottom to top: [PlayerView] the picture and its native captions · [WebView] the interface,
 * transparent, on top. The page therefore paints its scrims and controls *over* the video
 * without the shell knowing where the video is, and the picture is never re-laid out by CSS. The one
 * contract that comes with it: **while a native video is live the page must not paint an opaque page
 * background** — the background belongs to the picture, and everything else on screen is a gradient or a
 * panel.
 *
 * The shell is deliberately thin. Rank, probe, self-heal and the "choose another stream" prompt live in the
 * web player module, because they must be identical on every device; the shell decodes and reports. It is
 * also deliberately not the first screen: [MainActivity] runs the one hub probe and either opens this
 * activity or, on a fresh TV with no hub, the native pairing/setup screen first.
 */
class WebShellActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var video: PlayerView
    private var player: ExoPlayer? = null
    private var http: DefaultHttpDataSource.Factory? = null
    private val main = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null
    private var backSentAtMs = 0L

    private val app get() = application as ScenixApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        // PlayerView owns the picture and the captions. Its default surface is a TextureView, which
        // composites *inside* the view hierarchy — that is exactly what lets the transparent WebView sit
        // on top with the control bar painted over live video. A SurfaceView would punch through instead.
        video = PlayerView(this).apply {
            useController = false
            controllerAutoShow = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        // A browse screen has no picture: keeping a full-screen TextureView behind the page costs the
        // panel a whole composited layer for nothing. The page asks for it when a film starts
        // (`Scenix.surface(true)`), and hands it back when the player closes.
        video.visibility = View.GONE
        root.addView(video, match())
        web = WebView(this)
        configureWeb()
        root.addView(web, match())
        setContentView(root)

        web.addJavascriptInterface(Bridge(), "Scenix")
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                // One line of glue: the native side pushes plain JSON, the page turns it into events.
                view.evaluateJavascript(
                    "window.__scenix=function(n,d){document.dispatchEvent(" +
                        "new CustomEvent(n,{detail:d||{}}))};", null
                )
                emit("scenix:shell", mapOf("device" to deviceName(), "surface" to "native"))
            }

            override fun onReceivedError(view: WebView, code: Int, desc: String, url: String) {
                // A dead hub must say so in the page, not as Android's default error screen.
                emit("scenix:shell", mapOf("device" to deviceName(), "unreachable" to url, "why" to desc))
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                android.util.Log.d("ScenixWeb", "${m.lineNumber()}: ${m.message()}")
                return true
            }
        }

        val url = intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() } ?: shellUrl(this, "")
        android.util.Log.i("ScenixWeb", "shell loading $url")
        web.loadUrl(url)
    }

    private fun match() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun configureWeb() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The page drives playback; a "user gesture" gate on a TV leaves resume-after-Back a dead
            // screen. Opening this shell *is* the user's intent, so it is not a random autoplay.
            mediaPlaybackRequiresUserGesture = false
            allowContentAccess = false
            allowFileAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            // A TV is read from three metres and the page is authored at 1920x1080; a mobile viewport
            // heuristic would zoom the whole interface out.
            useWideViewPort = true
            loadWithOverviewMode = false
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            textZoom = 100
            javaScriptCanOpenWindowsAutomatically = false
        }
        // A remote-controlled page is otherwise invisible from the outside: a screenshot shows pixels and
        // says nothing about which element holds focus, what route the app thinks it is on, or why a control
        // was skipped. Debug builds expose this page to a DevTools client forwarded over the adb link that
        // already reaches the TV, so the interface can be inspected on the device actually running it.
        if ((app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // Transparent so the picture below shows through and the page's gradients scrim it.
        web.setBackgroundColor(0x00000000)
    }

    private fun deviceName(): String =
        android.os.Build.MODEL.lowercase().replace(' ', '-').replace(Regex("[^a-z0-9-]"), "")

    // ---- the player, built on first use so a session that only browses allocates no decoder ----

    private fun exo(): ExoPlayer {
        player?.let { return it }
        // Held as a field: the addon's headers change per title, and the factory is read at open time,
        // so a play can retarget it without rebuilding the pipeline (and without leaking headers into
        // the next title).
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Scenix/0.1")
            // media3's 8 s default is what made films "fail to play" on this panel: a cold torrent can
            // take half a minute for the first byte, and the player used to give up inside that window.
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(60000)
            .setAllowCrossProtocolRedirects(true)
        http = httpFactory

        val ctx = app.ctx
        val builder = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory))
            )
            // Same ceilings as the native player: 1080p/10 Mbps on this panel, 30 s behind and 120 s
            // ahead so a rewind never re-requests, from theme.json rather than from here.
            .setLoadControl(Ceilings.loadControl(ctx))
        Ceilings.seekIncrements(builder, ctx)
        val p = builder.build()
        Ceilings.applyDefaults(p, ctx)
        p.addListener(listener())
        video.setPlayer(p)
        player = p
        return p
    }

    private fun listener() = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            emit("scenix:tracks", trackList(tracks))
        }

        override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
            emit("scenix:video", mapOf(
                "width" to size.width, "height" to size.height,
                "durationMs" to (player?.duration ?: 0L).coerceAtLeast(0L),
            ))
        }

        override fun onIsLoadingChanged(loading: Boolean) {
            emit("scenix:buffering", mapOf("buffering" to loading))
        }

        /**
         * Reported, not fixed. The page decides whether to reload the same URL at the current position
         * (one silent reload at the current position) or to ask the viewer to pick another stream — the same decision the
         * browser build makes, so the behaviour is identical on both.
         */
        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.w("ScenixWeb", "playback error ${error.errorCodeName}", error)
            // A first-byte timeout is a cold copy, not a broken file. Saying which is which lets the
            // page offer "wait" (and retry by itself) instead of sending the viewer to another source.
            val name = error.errorCodeName
            val waiting = name.contains("TIMEOUT") || name.contains("IO_NETWORK") ||
                name.contains("IO_UNSPECIFIED") || name.contains("BEHIND_LIVE_WINDOW")
            emit("scenix:error", mapOf(
                "code" to name,
                "message" to (error.message ?: ""),
                "positionMs" to (player?.currentPosition ?: 0L),
                "waiting" to waiting,
            ))
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    val p = player ?: return
                    emit("scenix:ready", mapOf(
                        "durationMs" to p.duration.coerceAtLeast(0L),
                        "positionMs" to p.currentPosition,
                    ))
                }
                Player.STATE_ENDED -> {
                    val p = player ?: return
                    emit("scenix:ended", mapOf(
                        "positionMs" to p.currentPosition,
                        "durationMs" to p.duration.coerceAtLeast(0L),
                    ))
                }
            }
        }
    }

    /** The container's own stream indices, so an id the hub produced selects the same track here. */
    private fun trackList(tracks: Tracks): Map<String, Any> {
        val audio = ArrayList<Map<String, Any?>>()
        val text = ArrayList<Map<String, Any?>>()
        for (g in tracks.groups) {
            if (g.type != C.TRACK_TYPE_AUDIO && g.type != C.TRACK_TYPE_TEXT) continue
            val entry = ArrayList<Map<String, Any?>>()
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                entry.add(mapOf(
                    "id" to (if (g.type == C.TRACK_TYPE_AUDIO) "a:" else "s:") + (g.mediaTrackGroup.id + 1) +
                        if (g.length > 1) "+$i" else "",
                    "language" to (f.language ?: "und"),
                    "title" to (f.label ?: ""),
                    "codec" to (f.codecs ?: f.sampleMimeType?.substringAfter('/') ?: ""),
                    "default" to ((f.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0),
                    "selected" to g.isTrackSelected(i),
                ))
            }
            if (g.type == C.TRACK_TYPE_AUDIO) audio.addAll(entry) else text.addAll(entry)
        }
        return mapOf("audio" to audio, "text" to text)
    }

    private fun startTicker() {
        stopTicker()
        val r = object : Runnable {
            override fun run() {
                val p = player ?: return
                emit("scenix:position", mapOf(
                    "positionMs" to p.currentPosition,
                    "bufferedMs" to p.bufferedPosition.coerceAtLeast(0L),
                    "durationMs" to p.duration.coerceAtLeast(0L),
                    "playing" to p.isPlaying,
                ))
                main.postDelayed(this, 500L)
            }
        }
        ticker = r
        main.postDelayed(r, 500L)
    }

    private fun stopTicker() {
        ticker?.let { main.removeCallbacks(it) }
        ticker = null
    }

    private fun emit(name: String, detail: Map<String, Any?>) {
        val json = try {
            org.json.JSONObject(detail).toString()
        } catch (e: Exception) {
            "{}"
        }.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        main.post { web.evaluateJavascript("window.__scenix&&__scenix('$name',$json)", null) }
    }

    /**
     * `@JavascriptInterface` methods arrive on the renderer's JS thread; media3 must be touched on the app
     * thread. Two blocking helpers so a JS call can still answer with a value. The wait is single-digit
     * milliseconds and never blocks painting.
     */
    private fun onMain(body: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) body() else {
            val latch = java.util.concurrent.CountDownLatch(1)
            main.post {
                try {
                    body()
                } finally {
                    latch.countDown()
                }
            }
            latch.await(2500L, TimeUnit.MILLISECONDS)
        }
    }

    private fun <T> onMainValue(body: () -> T, fallback: T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return body()
        var out = fallback
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post {
            try {
                out = body()
            } finally {
                latch.countDown()
            }
        }
        latch.await(2500L, TimeUnit.MILLISECONDS)
        return out
    }

    // ---- the object the page sees as `window.Scenix` ----

    inner class Bridge {

        @JavascriptInterface
        fun device(): String = deviceName()

        /**
         * Whether the native picture surface is part of the frame. On a browse screen it is not: a
         * transparent WebView over a TextureView is a second full-screen layer the GPU has to compose on
         * every scroll. On the player it is, and the page's background must be transparent so the picture
         * shows through. One call, two states, no guessing from the shell.
         */
        @JavascriptInterface
        fun surface(on: Boolean) = onMain {
            video.visibility = if (on) View.VISIBLE else View.GONE
            web.setBackgroundColor(if (on) android.graphics.Color.TRANSPARENT else android.graphics.Color.rgb(20, 20, 20))
        }

        /** `{url, headers, positionMs, subs:[{url,language,label,format}], audio, sub, title}` */
        @JavascriptInterface
        fun play(requestJson: String) {
            onMain {
                try {
                    val r = org.json.JSONObject(requestJson)
                    val url = r.optString("url")
                    if (url.isBlank()) {
                        emit("scenix:error", mapOf("code" to "SHELL_NO_URL", "message" to "play() without a url"))
                        return@onMain
                    }
                    val headers = HashMap<String, String>()
                    r.optJSONObject("headers")?.let { h ->
                        for (k in h.keys()) headers[k] = h.optString(k)
                    }
                    http?.setDefaultRequestProperties(headers)

                    val builder = MediaItem.Builder().setUri(url)
                    val cfgs = ArrayList<MediaItem.SubtitleConfiguration>()
                    r.optJSONArray("subs")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val s = arr.getJSONObject(i)
                            val u = s.optString("url")
                            if (u.isBlank()) continue
                            cfgs.add(
                                MediaItem.SubtitleConfiguration.Builder(Uri.parse(u))
                                    .setMimeType(subtitleMime(s.optString("format")))
                                    .setLanguage(s.optString("language").ifBlank { "und" })
                                    .setLabel(s.optString("label"))
                                    .setSelectionFlags(
                                        if (s.optBoolean("default")) C.SELECTION_FLAG_DEFAULT else 0
                                    )
                                    .build()
                            )
                        }
                    }
                    if (cfgs.isNotEmpty()) builder.setSubtitleConfigurations(cfgs)

                    val p = exo()
                    p.clearMediaItems()
                    p.setMediaItem(builder.build(), r.optLong("positionMs", 0L))
                    p.prepare()
                    p.playWhenReady = true
                    startTicker()
                    // An explicit `audio`/`sub` id cannot be honoured before the container is parsed, so
                    // the preferred languages do the work now and the page calls select() the moment
                    // scenix:tracks arrives.
                    applyPreferredLanguages(p, r)
                } catch (e: Exception) {
                    android.util.Log.w("ScenixWeb", "bad play request", e)
                    emit("scenix:error", mapOf("code" to "SHELL_BAD_REQUEST", "message" to (e.message ?: "")))
                }
            }
        }

        private fun applyPreferredLanguages(p: ExoPlayer, r: org.json.JSONObject) {
            val audio = r.optString("audioLanguages").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val text = r.optString("subtitleLanguages").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            var b = p.trackSelectionParameters.buildUpon()
            if (audio.isNotEmpty()) b = b.setPreferredAudioLanguages(*audio.toTypedArray())
            if (text.isNotEmpty()) b = b.setPreferredTextLanguages(*text.toTypedArray())
            p.trackSelectionParameters = b.build()
        }

        private fun subtitleMime(format: String): String = when (format.lowercase()) {
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt", "webvtt" -> MimeTypes.TEXT_VTT
            "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }

        @JavascriptInterface
        fun pause() = onMain { player?.pause() }

        @JavascriptInterface
        fun resume() = onMain { player?.playWhenReady = true }

        @JavascriptInterface
        fun seek(positionMs: Long) = onMain { player?.seekTo(positionMs) }

        @JavascriptInterface
        fun skip(deltaMs: Long) = onMain {
            val p = player ?: return@onMain
            p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, p.duration.coerceAtLeast(0L)))
        }

        @JavascriptInterface
        fun stop() = onMain {
            stopTicker()
            player?.stop()
        }

        @JavascriptInterface
        fun setSpeed(mult: Double) = onMain { player?.setPlaybackSpeed(mult.toFloat()) }

        /**
         * `a:1`, `s:2`, `off`. The number is the container's stream index, the same one the hub puts in
         * `audioTracks[].id`, so a picker built from hub metadata selects the right track here.
         */
        @JavascriptInterface
        fun select(trackId: String): Boolean = onMainValue({
            val p = player ?: return@onMainValue false
            if (trackId == "off") {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .build()
                return@onMainValue true
            }
            val parts = trackId.split(":")
            if (parts.size != 2) return@onMainValue false
            val type = when (parts[0]) {
                "a" -> C.TRACK_TYPE_AUDIO
                "s", "t" -> C.TRACK_TYPE_TEXT
                else -> return@onMainValue false
            }
            val want = parts[1]
            val groups = p.currentTracks.groups.filter { it.type == type }
            // The hub numbers tracks by the container's own stream index. media3's group ids come from the
            // extractor and match for Matroska; if they do not, position is the order ffprobe listed them
            // in, which is the same list the picker was built from.
            val group = groups.firstOrNull { it.mediaTrackGroup.id == want }
                ?: groups.getOrNull(want.toIntOrNull()?.minus(1) ?: -1)
                ?: return@onMainValue false
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(type, false)
                .clearOverridesOfType(type)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(0)))
                .build()
            true
        }, false)

        /** Ceiling for adaptive ladders (HLS/DASH). A progressive copy is changed by asking the hub again. */
        @JavascriptInterface
        fun setQuality(height: Int) = onMain {
            val p = player ?: return@onMain
            val theme = app.theme
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(
                    if (height > 0) theme.playerMaxWidth else 640,
                    if (height > 0) minOf(height, theme.playerMaxHeight) else 360,
                )
                .build()
        }

        /** Keep captions above the control bar the page is drawing — the page knows that bar's height. */
        @JavascriptInterface
        fun setSubMarginBottom(px: Int) = onMain {
            video.subtitleView?.translationY = -px.toFloat()
        }

        @JavascriptInterface
        fun openExternal(url: String) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                android.util.Log.w("ScenixWeb", "no installed app for $url", e)
                emit("scenix:shell", mapOf("device" to deviceName(), "noAppFor" to url))
            }
        }

        @JavascriptInterface
        fun exit() = runOnUiThread { finish() }

        /** A page can offer "change hub" without knowing how a TV pairs: reopen the front door. */
        @JavascriptInterface
        fun openSetup() = runOnUiThread {
            startActivity(
                Intent(this@WebShellActivity, MainActivity::class.java)
                    .putExtra("setup", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }

        @JavascriptInterface
        fun state(): String = onMainValue({
            val p = player ?: return@onMainValue "{}"
            org.json.JSONObject(mapOf(
                "playing" to p.isPlaying,
                "positionMs" to p.currentPosition,
                "bufferedMs" to p.bufferedPosition.coerceAtLeast(0L),
                "durationMs" to p.duration.coerceAtLeast(0L),
                "width" to p.videoSize.width,
                "height" to p.videoSize.height,
                "state" to p.playbackState,
            )).toString()
        }, "{}")
    }

    // ---- keys ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The page owns input. Some firmwares deliver media keys to the Activity instead of the WebView;
        // forwarded as DOM events so the page's play/pause handler still runs.
        val mediaKey = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> "MediaPlayPause"
            KeyEvent.KEYCODE_MEDIA_PLAY -> "MediaPlay"
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "MediaPause"
            KeyEvent.KEYCODE_MEDIA_NEXT -> "MediaTrackNext"
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "MediaTrackPrevious"
            KeyEvent.KEYCODE_MEDIA_STOP -> "MediaStop"
            KeyEvent.KEYCODE_MEDIA_REWIND -> "MediaRewind"
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "MediaFastForward"
            else -> null
        }
        if (mediaKey != null && event.action == KeyEvent.ACTION_DOWN) {
            emit("scenix:key", mapOf("key" to mediaKey, "keyCode" to event.keyCode))
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            if (web.canGoBack()) {
                web.goBack()
                return true
            }
            // Back closes the top-most thing (sheet, search, row) rather than quitting; the page answers
            // by setting `Scenix.exit()`, which is why this is a message and not an exit.
            val now = System.currentTimeMillis()
            if (now - backSentAtMs > 900L) {
                backSentAtMs = now
                emit("scenix:key", mapOf("key" to "Backspace", "keyCode" to 8, "code" to "Backspace"))
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        // A TV that goes to sleep mid-film should not keep decoding into a dead surface.
        if (isFinishing) return
        player?.playWhenReady = false
    }

    override fun onStart() {
        super.onStart()
        if (player?.isPlaying == false && (player?.playbackState ?: 0) == Player.STATE_READY) {
            player?.playWhenReady = true
        }
    }

    override fun onDestroy() {
        stopTicker()
        video.setPlayer(null)
        player?.release()
        player = null
        web.destroy()
        super.onDestroy()
    }

    companion object {
        /** `url` extra: an absolute page address, including a `#/route` when a deep link named one. */
        const val EXTRA_URL = "url"

        /**
         * The page root this TV should load, plus an optional page route (`#/detail/<id>`,
         * `#/play/<id>`). Hub mode is the hub's own page, exactly as a browser on the LAN opens it;
         * standalone mode is the bundle served by this app off the loopback contract server, so its
         * relative `/app/v1` calls land on this TV.
         *
         * Kept here rather than in the launcher so the launcher, a deep link and the activity's own
         * default all resolve the same address.
         */
        fun shellUrl(context: Context, route: String): String {
            val app = context.applicationContext as ScenixApp
            // A pinned fixture is a development preview: serve the bundled page and the bundled
            // answers over loopback, exactly as standalone serves its own data, so the same page code
            // is what is being previewed.
            val fixture = app.prefs.fixture()
            val root = when {
                !fixture.isNullOrBlank() -> {
                    val port = runCatching {
                        FixtureServer.portFor(context.applicationContext, fixture)
                    }.getOrDefault(0)
                    if (port > 0) "http://127.0.0.1:$port/ui/index.html" else hubRoot(context)
                }
                !app.prefs.standalone() -> hubRoot(context)
                else -> {
                    val port = runCatching { LocalServer.portFor(context.applicationContext) }.getOrDefault(0)
                    if (port <= 0) {
                        android.util.Log.e("ScenixWeb", "no local contract server; falling back to the last hub address")
                        hubRoot(context)
                    } else {
                        // The same path a hub serves it from, so nothing in the bundle has to know which
                        // mode it is in.
                        "http://127.0.0.1:$port/ui/index.html"
                    }
                }
            }
            return root + route
        }

        /** The hub this TV already uses — the candidate list [MainActivity]'s watcher walks. */
        private fun hubRoot(context: Context): String {
            val app = context.applicationContext as ScenixApp
            val candidates = app.prefs.hubUrlCandidates(context.getString(R.string.hub_urls))
            val base = candidates.firstOrNull() ?: app.prefs.hubUrl(context.getString(R.string.hub_url))
            return base.trimEnd('/') + "/"
        }
    }
}
