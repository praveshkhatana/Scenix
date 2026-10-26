package com.scenix.tv.plugins.nav

import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Episode
import com.scenix.tv.core.Format
import com.scenix.tv.core.HubError
import com.scenix.tv.core.Item
import com.scenix.tv.core.PlayTarget
import com.scenix.tv.core.PlayerPlugin
import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.ScreenPlugin
import com.scenix.tv.core.Source
import com.scenix.tv.core.Sub
import com.scenix.tv.core.Views
import com.scenix.tv.plugins.player.Ceilings
import com.scenix.tv.plugins.player.PlaybackPanel
import com.scenix.tv.plugins.player.SettingsRow

/**
 * The player: one screen, one ExoPlayer, and the hub's ranking.
 *
 * Three ideas carry it.
 *
 * **Failover.** `sources[]` arrives pre-ranked by the hub. Play opens the best one this TV can render
 * in place, and if it stalls for [stallLimitMs] or Media3 reports it cannot decode it, the next-ranked
 * source is opened at the same position with one honest line: "Switched to Source 2 — the first one
 * stalled". The retry cap is the length of the list, because there is nothing else to try.
 *
 * **Explainability.** The overlay prints which source is live and the `why[]` the hub gave for it, so
 * the automatic choice is visible, not magic.
 *
 * **Hand-off, not rendering.** A source the plugin marks [PlayerPlugin.external] (a torrent magnet)
 * never reaches Media3; it is handed to a companion engine, or the overlay says plainly that this TV
 * has nothing for it. A `mode:"gateway"` answer from the hub *is* ordinary seekable HTTP and plays
 * like anything else, which is what lets the hub move a torrent behind a gateway later with no change
 * here.
 */
object PlayerScreen : ScreenPlugin {
    override val id get() = "player"
    override val title get() = "Player"
    override val route get() = "play"
    override val showInNav = false
    override val order = 400

    private val handler = Handler(Looper.getMainLooper())

    // ---- one playback session at a time ------------------------------------------------------
    private var ctx: AppContext? = null
    private var hostRef: ScreenHost? = null
    private var root: FrameLayout? = null
    private var playerView: PlayerView? = null
    private var player: ExoPlayer? = null
    private var listener: Player.Listener? = null

    private var item: Item? = null
    private var ranked: List<Source> = emptyList()
    private var index = 0
    private var target: PlayTarget? = null

    /**
     * Which episode of a series is playing. The hub names it in the play answer (it decides: the one
     * asked for, the one this profile stopped on, else the first), and every later request for this
     * title repeats it — switching source mid-episode must not restart S01E01. `nextEpisode` is what
     * "Autoplay next" steps to.
     */
    private var curEpisode: Episode? = null
    private var nextEpisode: Episode? = null

    /** How many times the current source has been asked again after its connection went quiet. */
    private var ioRetries = 0
    private var ioRetryIndex = -1

    /** When this screen took over, and the last key it saw — this remote reports one press twice. */
    private var openedAtMs = 0L
    private var lastKeyCode = -1
    private var lastKeyAtMs = 0L

    private fun isToggleKey(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> true
        else -> false
    }

    /**
     * Errors that mean "the bytes did not arrive in time", not "this copy is broken". A cold copy is
     * worth waiting for; a wrong container or a 404 is not.
     */
    private fun isNetworkWait(name: String): Boolean = when (name) {
        "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT",
        "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
        "ERROR_CODE_IO_UNSPECIFIED",
        -> true
        else -> false
    }

    /** The "12 · &TV" overlay shown when zapping live TV. */
    private var zapBadge: android.widget.TextView? = null
    private val hideBadge = Runnable { zapBadge?.visibility = View.GONE }
    private var plugin: PlayerPlugin? = null
    private var generation = 0            // a superseded open() must not touch the screen
    private var hubSubs: List<Sub> = emptyList()

    // ---- panel state (the "Playback Settings" sheet, in D-pad form) ------------------
    private var panelLayer: FrameLayout? = null
    private var panel: PlaybackPanel? = null
    private var speed = 1.0f
    private var speedAt = 1              // index into SPEEDS
    private var subMode = -2             // -2 auto (profile languages), -1 off, >=0 text track index
    private var aspectAt = 0
    private var capAt = 0
    private var lastBitrate = 0

    // ---- chrome ------------------------------------------------------------------------------
    private var readout: TextView? = null
    private var statusLine: TextView? = null
    private var titleView: TextView? = null
    private var timeNow: TextView? = null
    private var timeTotal: TextView? = null
    private var barFill: View? = null
    private var controls: View? = null
    private var ticker: Runnable? = null
    private var hideRunnable: Runnable? = null
    private var lastPostMs = 0L
    private var stalledMs = 0L

    private val SPEEDS = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f)
    private val ASPECTS = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    )
    private val ASPECT_NAMES = arrayOf("Fit (default)", "Stretch", "Zoom (crop)")

    // ---- render ------------------------------------------------------------------------------

    override fun render(host: ScreenHost) {
        val app = host.app
        val theme = app.theme
        // Whatever was quietly looping in the billboard stops here: one hardware decoder, and the
        // full-screen player needs it.
        com.scenix.tv.core.Preview.release()
        ctx = app
        hostRef = host

        val container = host.container()
        val request = host.playRequest()
        val seed = request?.first ?: host.seedItem(host.arg() ?: "")
        if (seed == null) {
            container.addView(message(app, "Nothing to play."))
            return
        }

        item = seed
        ranked = app.ranked(seed)
        // A new title starts with no episode pinned: the hub picks it (the episode this profile stopped
        // on, else the first) and names it back in the play answer.
        curEpisode = seed.progress?.episode
        nextEpisode = null
        ioRetries = 0
        ioRetryIndex = -1
        val chosen = request?.second ?: app.playableSource(seed)
        index = ranked.indexOfFirst { it === chosen }.coerceAtLeast(0)

        val r = FrameLayout(app.app)
        r.setBackgroundColor(Color.BLACK)
        container.addView(
            r,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        root = r

        val pv = PlayerView(app.app)
        pv.useController = false
        pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        r.addView(pv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        playerView = pv

        val layer = FrameLayout(app.app)
        r.addView(
            layer,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        panelLayer = layer

        r.addView(buildControls(app, theme, seed), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
        ))

        // The channel badge: "12 · &TV" flashing top-left when the viewer zaps, like a real TV.
        val badge = Views.text(app, "h2", 36f, theme.ink, "", bold = true)
        badge.setPadding(theme.px(26f), theme.px(14f), theme.px(26f), theme.px(14f))
        badge.background = Views.rounded(theme.color("scrim", "#BF000000"), theme.radius("listItem", 8f))
        badge.visibility = View.GONE
        val blp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        blp.gravity = Gravity.TOP or Gravity.START
        blp.setMargins(theme.gutter, theme.navHeight + theme.px(20f), 0, 0)
        r.addView(badge, blp)
        zapBadge = badge

        r.isFocusable = true
        r.isFocusableInTouchMode = false
        openedAtMs = System.currentTimeMillis()
        r.setOnKeyListener { _, keyCode, ev -> ev.action == KeyEvent.ACTION_DOWN && onKey(keyCode) }
        // Claimed twice: once here, and once after the view is really in the window. Without the
        // deferred call the player opens with no focus at all, its own key handling never runs, and
        // the first OK press vanishes — the "Play button takes forever to press" complaint.
        r.requestFocus()
        r.post { if (root === r) r.requestFocus() }

        loadSubs(app, seed)
        openAt(index, seed.progress?.positionMs ?: 0L, null)
        // Tuning into a channel shows its number, the way a TV does it.
        if (seed.isLive()) {
            val row = app.lastHome.firstOrNull { r -> r.items.any { it.identity == seed.identity } }
            showZapBadge(seed, (row?.items?.indexOfFirst { it.identity == seed.identity } ?: -1) + 1)
        }
    }

    private fun buildControls(app: AppContext, theme: com.scenix.tv.core.Theme, seed: Item): View {
        val col = LinearLayout(app.app)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(theme.gutter, theme.px(24f), theme.gutter, theme.px(28f))
        col.background = Views.gradient(
            intArrayOf(Color.TRANSPARENT, theme.color("scrim", "#BF000000")),
            0,
        )
        controls = col

        titleView = Views.text(app, "h3", 34f, theme.ink, seed.title, bold = true, maxLines = 1)
        col.addView(titleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val bar = LinearLayout(app.app)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        blp.topMargin = theme.px(14f)
        col.addView(bar, blp)

        timeNow = Views.text(app, "readout", 20f, theme.ink2, "0:00")
        bar.addView(timeNow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // The fill is a child of the track, so scaleX moves the playhead without layout churn.
        val track = FrameLayout(app.app)
        track.setBackgroundColor(theme.color("line", "#24FFFFFF"))
        val tlp = LinearLayout.LayoutParams(0, theme.px(5f), 1f)
        tlp.marginStart = theme.px(18f)
        tlp.marginEnd = theme.px(18f)
        bar.addView(track, tlp)
        val fill = View(app.app)
        fill.setBackgroundColor(theme.brand)
        track.addView(fill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fill.scaleX = 0f
        barFill = fill

        timeTotal = Views.text(app, "readout", 20f, theme.ink2, Format.duration(seed.durationMs))
        bar.addView(timeTotal, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        readout = Views.text(app, "readout", 20f, theme.ink2, "")
        val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rlp.topMargin = theme.px(14f)
        col.addView(readout, rlp)

        statusLine = Views.text(app, "readout", 20f, theme.color("kids", "#F2C33A"), "")
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.topMargin = theme.px(8f)
        col.addView(statusLine, slp)

        val hint = Views.text(
            app, "liMeta", 18f, theme.ink3,
            if (seed.isLive()) "OK play / pause  \u00B7  Down settings  \u00B7  \u25C0 \u25B6 change channel  \u00B7  Back exit"
            else "OK play / pause  \u00B7  Down settings  \u00B7  \u25C0 \u25B6 seek  \u00B7  Back exit",
        )
        val hlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hlp.topMargin = theme.px(12f)
        col.addView(hint, hlp)

        return col
    }

    // ---- opening a source --------------------------------------------------------------------

    /**
     * Ask the hub for this ranked source and play it (or hand it off). `generation` makes a late
     * answer from a previous attempt harmless.
     */
    private fun openAt(i: Int, resumeAt: Long, announce: String?) {
        val app = ctx ?: return
        val it = item ?: return
        if (ranked.isEmpty()) {
            setStatus("This title has no sources on this hub.")
            return
        }
        index = i.coerceIn(0, ranked.size - 1)
        val src = ranked[index]
        val gen = ++generation
        // The hub's own play budget is ~11 s (it may be fetching a cold copy), so the wait is
        // normal: say what is happening rather than naming a provider that is not failing.
        setStatus("Finding the best copy\u2026")

        // An episode, once chosen, is sticky: switching source mid-episode must not silently restart
        // S01E01. On the first press 0/0 means "hub, you pick" — it answers with the episode this
        // profile stopped on, or the first one, and names it back.
        val ep = curEpisode
        app.hub.play(it.identity, src.provider, app.profile?.id, it.type,
            ep?.season ?: 0, ep?.episode ?: 0) { play, error ->
            BrowseHelper.runOnUiThread {
                if (gen != generation) return@runOnUiThread

                if (play == null) {
                    // A 403 is not a broken stream: the hub decided this title is not for this
                    // profile, so trying the next source would only fail in the same way. Say so.
                    if (HubError.forbidden(error)) {
                        setStatus("Not available for this profile.")
                        readout?.text = "This profile's content rules keep this title out. Switch profile from the top-right menu to watch it."
                        showControls()
                        return@runOnUiThread
                    }
                    setStatus("The hub could not open ${Format.provider(src)} (${error ?: "no answer"})")
                    failover("the hub could not open it")
                    return@runOnUiThread
                }

                // The hub owns the episode choice; take it back from the answer and hold it for every
                // retry, source switch and progress report of this title.
                play.episode?.let { curEpisode = it }
                play.nextEpisode?.let { nextEpisode = it }
                curEpisode?.let { e -> titleView?.text = episodeTitle(it.title, e) }

                if (!play.playable() && play.warming && play.seeders != 0) {
                    // A cold copy: the hub resolved it and is still pulling the first bytes. This is
                    // a wait, not a failure, so wait once and ask again for the same source.
                    setStatus("Finding the best copy\u2026")
                    handler.postDelayed({ if (gen == generation) openAt(index, resumeAt, null) }, 1500L)
                    return@runOnUiThread
                }

                if (!play.playable()) {
                    // A magnet, or anything else Media3 has no business opening. The hub now explains
                    // which: nobody sharing it, a link it rejected, a copy still warming.
                    val p = app.registries.playerFor(src.kind)
                    player?.pause()
                    saveProgress()
                    val hubSaid = play.whyNot()
                    val line = when {
                        hubSaid.isNotBlank() -> hubSaid
                        p == null -> "${Format.provider(src)} \u00B7 no player plugin on this TV"
                        p.external() -> p.openExternally(app, it, src, play)
                        else -> "${Format.provider(src)} \u00B7 this TV cannot open it"
                    }
                    setStatus(line)
                    readout?.text = line
                    showControls()
                    return@runOnUiThread
                }

                // `mode:"gateway"` is an ordinary seekable HTTP stream the hub fetched for us, so it
                // is handed to the http plugin whatever the source's own kind is. proxy/direct keep
                // their kind (only who fetches changes), and an unknown kind falls back to http.
                val kind = if (play.mode.equals("gateway", true)) "http" else src.kind
                val p = app.registries.playerFor(kind)
                if (p == null) {
                    setStatus("${Format.provider(src)} \u2014 no player plugin on this TV")
                    failover("this TV has no player for it")
                    return@runOnUiThread
                }
                target = play
                plugin = p
                start(app, it, src, play, p, resumeAt)
                p.warning?.let { w -> hostRef?.toast(w) }
                announce?.let { hostRef?.toast(it) }
            }
        }
    }

    private fun start(
        app: AppContext,
        it: Item,
        src: Source,
        play: PlayTarget,
        p: PlayerPlugin,
        resumeAt: Long,
    ) {
        releasePlayer()

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Scenix/0.1")
            // media3's 8 s default is the reason a film "could not be played" on this TV: a copy that is
            // not already cached takes longer than that to produce its first byte (the hub has to find
            // peers and pull the head of the file), the player gives up mid-wait, and every other copy is
            // just as cold. Waiting is not failing, so the wait is allowed to be long.
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(60000)
            .setAllowCrossProtocolRedirects(true)
        if (play.headers.isNotEmpty()) http.setDefaultRequestProperties(play.headers)

        val exo = Ceilings.seekIncrements(
            ExoPlayer.Builder(app.app)
                .setLoadControl(p.loadControl(app) ?: Ceilings.loadControl(app))
                .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(app.app, http))),
            app,
        ).build()

        p.configure(exo, app, src, play)
        applyCaps(exo)
        applyAudio(exo)
        subMode = -2

        val builder = MediaItem.Builder().setUri(play.url).setMediaId(it.identity)
        val cfgs = ArrayList<MediaItem.SubtitleConfiguration>()
        val wanted = app.profile?.subtitleLanguages ?: emptyList()
        for ((n, s) in hubSubs.withIndex()) {
            cfgs.add(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                    .setMimeType(s.mimeType)
                    .setLanguage(s.lang.ifBlank { "und" })
                    .setLabel(subLabel(s, n))
                    .setSelectionFlags(if (wanted.any { l -> s.lang.startsWith(l) }) C.SELECTION_FLAG_DEFAULT else 0)
                    .build(),
            )
        }
        if (cfgs.isNotEmpty()) builder.setSubtitleConfigurations(cfgs)

        val l = watch(exo)
        listener = l
        exo.addListener(l)
        exo.setMediaItem(builder.build(), resumeAt)
        exo.prepare()
        exo.playWhenReady = true
        exo.setPlaybackSpeed(speed)
        player = exo
        playerView?.player = exo
        stalledMs = 0L
        lastPostMs = 0L
        setStatus(sourceLine())
        applySubs(exo)
        startTicker()
        showControls()
    }

    private fun watch(exo: ExoPlayer): Player.Listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val app = ctx ?: return
            android.util.Log.w("ScenixPlayer", "playback error ${error.errorCodeName}", error)
            BrowseHelper.runOnUiThread {
                if (player !== exo) return@runOnUiThread
                val pos = exo.currentPosition
                // The pipe died, not the copy. Every other copy of this title is just as cold, so
                // spending them on the same first-byte wait ends in "No source left". Ask the same link
                // again — the hub has kept it warm and the engine has already pulled its head — and say
                // that we are waiting rather than that we failed.
                if (isNetworkWait(error.errorCodeName) && ioRetryIndex != index) {
                    ioRetryIndex = index
                    ioRetries = 0
                }
                if (isNetworkWait(error.errorCodeName) && ioRetries < 2) {
                    ioRetries++
                    val gen = generation
                    setStatus("Still fetching the first seconds\u2026")
                    showControls()
                    handler.postDelayed({ if (gen == generation) openAt(index, pos, null) }, 1500L)
                    return@runOnUiThread
                }
                failover("it could not be played (${error.errorCodeName})")
            }
            app.images.trim()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                saveProgress(finish = true)
                showControls()
                maybeAutoplayNext()
            }
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            setStatus(sourceLine())
        }
    }

    // ---- failover ----------------------------------------------------------------------------

    /** Stall limit before the next-ranked source takes over, from theme (`player.stallFailoverMs`). */
    private fun stallLimitMs(): Long =
        ctx?.theme?.token("player", "stallFailoverMs", 6000.0)?.toLong() ?: 6000L

    private fun failover(reason: String) {
        val app = ctx ?: return
        val next = (index + 1) until ranked.size
        val pick = next.firstOrNull { i ->
            val s = ranked[i]
            app.canOpen(s) && !app.opensExternally(s)
        }
        if (pick == null) {
            // Nothing else to switch to. A copy that is merely slow is not a copy that failed: a real
            // player keeps waiting and says what it is waiting for, instead of putting up an error for
            // a film that resumes ten seconds later. Only stop when it is genuinely not playing.
            val exo = player
            val waiting = exo != null && exo.playWhenReady && exo.playbackState == Player.STATE_BUFFERING
            setStatus(if (waiting) waitingLine() else "No source left on this TV \u2014 $reason")
            if (!waiting) {
                exo?.pause()
                showControls()
            }
            return
        }
        val pos = player?.currentPosition ?: 0L
        val from = index + 1
        index = pick
        setStatus("Trying Source ${pick + 1}\u2026")
        openAt(pick, pos, "Switched to Source ${pick + 1} \u2014 Source $from $reason")
    }

    /** A stall with nowhere else to go, in words the viewer can act on. */
    private fun waitingLine(): String {
        val t = target
        val seeders = t?.seeders ?: -1
        val gateway = t != null && t.mode.equals("gateway", true)
        return when {
            gateway && seeders == 0 ->
                "Nobody is sharing this copy \u2014 press Down for another one"
            gateway && seeders in 1..9 ->
                "Buffering slowly \u2014 $seeders people sharing this copy \u00B7 press Down for another"
            else -> "Buffering\u2026"
        }
    }

    /** What the overlay prints: the source, its quality, and the hub's own reason for choosing it. */
    private fun sourceLine(): String {
        val src = ranked.getOrNull(index) ?: return ""
        val facts = listOfNotNull(
            "Source ${index + 1}",
            Format.provider(src),
            src.quality.takeIf { it.isNotBlank() },
            src.mode.takeIf { it.isNotBlank() }?.uppercase(),
            Format.size(src.sizeBytes).takeIf { it.isNotEmpty() },
            plugin?.riskLabel(src),
        )
        val why = src.why.joinToString(" \u00B7 ")
        return facts.joinToString(" \u00B7 ") + if (why.isBlank()) "" else "\nwhy: $why"
    }

    private fun setStatus(msg: String) {
        statusLine?.text = msg
    }

    // ---- subtitles + audio -------------------------------------------------------------------

    private fun loadSubs(app: AppContext, it: Item) {
        val langs = app.profile?.subtitleLanguages?.ifEmpty { app.profile?.languages ?: emptyList() }
            ?: emptyList()
        app.hub.subs(it.identity, langs, app.profile?.id) { list, _ ->
            BrowseHelper.runOnUiThread {
                if (item?.identity != it.identity) return@runOnUiThread
                hubSubs = list ?: emptyList()
                setStatus(sourceLine())
            }
        }
    }

    private fun subLabel(s: Sub, n: Int): String =
        s.label.ifBlank { listOf("subtitles", s.lang, s.addon).filter { it.isNotBlank() }.joinToString(" \u00B7 ") }

    private fun applySubs(exo: ExoPlayer) {
        val b = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        when (subMode) {
            -1 -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            -2 -> {
                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                val app = ctx
                val langs = app?.profile?.subtitleLanguages?.ifEmpty { app.profile?.languages ?: emptyList() }
                    ?: emptyList()
                if (langs.isNotEmpty()) b.setPreferredTextLanguages(*langs.toTypedArray())
            }
            else -> {
                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                textTracks().getOrNull(subMode)?.let { g ->
                    b.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, listOf(0)))
                }
            }
        }
        exo.trackSelectionParameters = b.build()
    }

    private fun applyAudio(exo: ExoPlayer) {
        val app = ctx ?: return
        val langs = app.profile?.audioLanguages?.ifEmpty { app.profile?.languages ?: emptyList() }
            ?: emptyList()
        if (langs.isEmpty()) return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages(*langs.toTypedArray())
            .build()
    }

    /** Every text track Media3 can see: embedded ones plus the side-loaded subtitles the hub served. */
    private fun textTracks(): List<androidx.media3.common.Tracks.Group> =
        player?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_TEXT } ?: emptyList()

    private fun subNames(): List<String> {
        val out = ArrayList<String>()
        for (g in textTracks()) {
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                val name = listOfNotNull(
                    f.label?.takeIf { it.isNotBlank() },
                    f.language?.takeIf { it.isNotBlank() },
                    "track",
                ).firstOrNull() ?: "track"
                out.add(name)
            }
        }
        if (out.isEmpty() && hubSubs.isNotEmpty()) {
            for ((n, s) in hubSubs.withIndex()) out.add(subLabel(s, n))
        }
        return out
    }

    // ---- quality cap -------------------------------------------------------------------------

    /** One ceiling choice: size + bitrate + what the panel prints for it. */
    private data class Cap(val width: Int, val height: Int, val bitrate: Int, val label: String)

    /**
     * The ladder the viewer can pick from, Scenix order: Auto first, then rung by rung down.
     * `Auto` is the profile ceiling and lets the player walk down by itself when the network gets
     * bad; a named rung pins the top so a bigger variant is never even requested.
     *
     * 4K is offered above the profile ceiling on purpose — the panel decodes 4K60 — but the hub
     * still has to produce a 4K source for it to do anything, which is why the note says when the
     * current source is a single file that cannot change size.
     */
    private fun ladder(): List<Cap> {
        val theme = ctx?.theme
        val w = theme?.playerMaxWidth ?: 1920
        val h = theme?.playerMaxHeight ?: 1080
        val autoBitrate = theme?.playerMaxBitrate ?: 10_000_000
        return listOf(
            Cap(w, h, autoBitrate, "Auto (up to ${h}p)"),
            Cap(3840, 2160, 25_000_000, "4K"),
            Cap(1920, 1080, 10_000_000, "Full HD 1080p"),
            Cap(1280, 720, 5_000_000, "HD 720p"),
            Cap(854, 480, 2_000_000, "SD 480p"),
        )
    }

    private fun cap(): Cap {
        val l = ladder()
        return l[((capAt % l.size) + l.size) % l.size]
    }

    /** A ladder can change size mid-play; a single file cannot. Told to the viewer, not guessed. */
    private fun isAdaptive(): Boolean {
        val t = target ?: return false
        val k = t.kind.lowercase()
        if ("hls" in k || "dash" in k) return true
        // The hub hands live channels over as plain http with an .m3u8/.mpd url: that is a ladder.
        val u = t.url.lowercase()
        return ".m3u8" in u || ".mpd" in u
    }

    private fun applyCaps(exo: ExoPlayer) {
        val c = cap()
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(c.width, c.height)
            .setMaxVideoBitrate(c.bitrate)
            .build()
    }

    // ---- the settings panel ------------------------------------------------------------------

    private fun panelRows(): List<SettingsRow> {
        val app = ctx ?: return emptyList()
        val out = ArrayList<SettingsRow>()

        out.add(
            SettingsRow(
                "source", "Source", sourceName(index),
                note = ranked.getOrNull(index)?.why?.joinToString(" \u00B7 ")?.takeIf { it.isNotBlank() }
                    ?: "${ranked.size} ranked by the hub",
                onClick = {
                    if (ranked.isEmpty()) return@SettingsRow
                    val pos = player?.currentPosition ?: 0L
                    val next = (index + 1) % ranked.size
                    index = next
                    openAt(next, pos, "Playing ${sourceName(next)}")
                },
            ),
        )

        val c = cap()
        out.add(
            SettingsRow(
                "quality", "Quality", c.label,
                // Told honestly: on a ladder the rung is a ceiling the player walks down by itself;
                // on a single file the size is fixed by the file, so the only real lever is Source.
                note = if (isAdaptive()) {
                    "${c.width}x${c.height} ceiling \u00B7 ${Format.bitrate(c.bitrate)} \u00B7 now " +
                        Format.bitrate(lastBitrate).ifBlank { "unknown" } +
                        " \u00B7 Auto walks down by itself when the network gets bad"
                } else {
                    "this source is one file at one size \u00B7 use Source to pick a different quality"
                },
                onClick = {
                    capAt += 1
                    player?.let { applyCaps(it) }
                    panel?.refresh()
                },
            ),
        )

        out.add(
            SettingsRow(
                "speed", "Speed", "${SPEEDS[speedAt]}x".replace(".0", ""),
                onClick = {
                    speedAt = (speedAt + 1) % SPEEDS.size
                    speed = SPEEDS[speedAt]
                    player?.setPlaybackSpeed(speed)
                    panel?.refresh()
                },
            ),
        )

        val names = subNames()
        out.add(
            SettingsRow(
                "subs", "Subtitles",
                when {
                    subMode == -2 -> "Auto (${(ctx?.profile?.subtitleLanguages ?: emptyList()).joinToString("/").ifBlank { "profile" }})"
                    subMode == -1 -> "Off"
                    else -> names.getOrNull(subMode) ?: "Off"
                },
                note = if (names.isEmpty() && hubSubs.isEmpty()) "no subtitles for this title" else null,
                enabled = names.isNotEmpty() || hubSubs.isNotEmpty(),
                onClick = {
                    val n = names.size
                    subMode = when {
                        n == 0 && subMode < 0 -> -2
                        subMode < -1 -> -1
                        subMode == -1 -> 0
                        subMode + 1 >= n -> -2
                        else -> subMode + 1
                    }
                    player?.let { applySubs(it) }
                    panel?.refresh()
                },
            ),
        )

        out.add(
            SettingsRow(
                "aspect", "Aspect ratio", ASPECT_NAMES[aspectAt],
                onClick = {
                    aspectAt = (aspectAt + 1) % ASPECTS.size
                    playerView?.resizeMode = ASPECTS[aspectAt]
                    panel?.refresh()
                },
            ),
        )

        val pid = app.profile?.id ?: ""
        out.add(
            SettingsRow(
                "autoplay", "Autoplay next", if (app.prefs.autoplayNext(pid)) "on" else "off",
                note = "Next title in the same row, when this one ends",
                onClick = {
                    app.prefs.setAutoplayNext(pid, !app.prefs.autoplayNext(pid))
                    panel?.refresh()
                },
            ),
        )

        out.add(
            SettingsRow(
                "boost", "Audio boost", "not available on this TV",
                enabled = false,
                note = "This build caps video and bitrate instead of post-processing audio.",
                onClick = { },
            ),
        )
        return out
    }

    private fun sourceName(i: Int): String {
        val s = ranked.getOrNull(i) ?: return "none"
        return listOfNotNull(
            "Source ${i + 1}",
            Format.provider(s),
            s.quality.takeIf { it.isNotBlank() },
            if (ctx?.opensExternally(s) == true) "needs a stream engine" else null,
        ).joinToString(" \u00B7 ")
    }

    private fun openPanel() {
        val app = ctx ?: return
        val layer = panelLayer ?: return
        if (panel != null) return
        val p = PlaybackPanel(app, "Playback Settings") { panelRows() }
        val v = p.view()
        val width = app.theme.token("panel", "width", 560.0).toInt()
        layer.addView(
            v,
            FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END),
        )
        panel = p
        v.post { p.focusFirst() }
        keepControlsVisible()
    }

    private fun closePanel() {
        val layer = panelLayer ?: return
        panelLayer?.removeAllViews()
        panel = null
        layer.visibility = View.GONE
        root?.requestFocus()
        showControls()
    }

    private fun panelOpen(): Boolean = panel != null

    // ---- keys --------------------------------------------------------------------------------

    private fun onKey(keyCode: Int): Boolean {
        val exo = player
        // One physical press on this remote arrives as two key events about 100 ms apart. That is what
        // made "the Play button is slow / it double-presses": the press that opened this screen also
        // landed here and paused the film again. A repeat of the same key inside 350 ms is the same
        // press, and neither of them may toggle playback in the first second of a title.
        val now = System.currentTimeMillis()
        val duplicate = keyCode == lastKeyCode && now - lastKeyAtMs < 350L
        lastKeyCode = keyCode
        lastKeyAtMs = now
        if (duplicate || (isToggleKey(keyCode) && now - openedAtMs < 1200L)) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_SPACE -> {
                if (exo == null) return false
                if (exo.playWhenReady) {
                    exo.pause()
                    setStatus("Paused \u2014 OK resumes")
                } else {
                    exo.playWhenReady = true
                    setStatus(sourceLine())
                }
                showControls()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                // On live TV Left/Right is a channel change, not a seek: that is what makes it feel
                // like a real TV line-up.
                if (isChannel() && zap(-1)) return true
                exo?.seekBack()
                showControls()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (isChannel() && zap(+1)) return true
                exo?.seekForward()
                showControls()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_UP -> {
                if (panelOpen()) closePanel() else openPanel()
                return true
            }
            else -> return false
        }
    }

    // ---- ticker + chrome ---------------------------------------------------------------------

    private fun startTicker() {
        ticker?.let { handler.removeCallbacks(it) }
        val t = object : Runnable {
            override fun run() {
                val exo = player ?: return
                val theme = ctx?.theme ?: return
                lastBitrate = exo.videoFormat?.bitrate ?: 0
                val size = exo.videoSize
                val buffered = (exo.bufferedPosition - exo.currentPosition).coerceAtLeast(0L)
                val src = ranked.getOrNull(index)
                val parts = listOfNotNull(
                    if (size.width > 0) "${size.width}x${size.height}" else null,
                    Format.bitrate(lastBitrate).takeIf { lastBitrate > 0 },
                    "${buffered / 1000L}s buffered",
                    if (src != null) plugin?.statusLine(src, target ?: PlayTarget()) else null,
                )
                readout?.text = parts.filter { it.isNotBlank() }.joinToString(" \u00B7 ")

                val pos = exo.currentPosition
                val dur = exo.duration.coerceAtLeast(0L)
                timeNow?.text = Format.clock(pos)
                timeTotal?.text = Format.clock(dur)
                if (dur > 0L) {
                    barFill?.scaleX = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                }

                // Stall watch: buffering with the intent to play, for longer than the limit.
                if (exo.playWhenReady && exo.playbackState == Player.STATE_BUFFERING) {
                    stalledMs += 700L
                    if (stalledMs >= stallLimitMs()) {
                        stalledMs = 0L
                        failover("stalled")
                    }
                } else {
                    stalledMs = 0L
                }

                if (dur > 0L && pos > 0L && System.currentTimeMillis() - lastPostMs >= theme.playerProgressPostMs) {
                    lastPostMs = System.currentTimeMillis()
                    saveProgress()
                }
                handler.postDelayed(this, 700L)
            }
        }
        ticker = t
        handler.postDelayed(t, 700L)
    }

    private fun showControls() {
        controls?.visibility = View.VISIBLE
        controls?.alpha = 1f
        hideRunnable?.let { handler.removeCallbacks(it) }
        if (panelOpen()) return
        val hide = Runnable {
            if (!panelOpen() && player?.playWhenReady == true) controls?.visibility = View.GONE
        }
        hideRunnable = hide
        handler.postDelayed(hide, ctx?.theme?.token("player", "controlsHideMs", 3500.0)?.toLong() ?: 3500L)
    }

    private fun keepControlsVisible() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        controls?.visibility = View.VISIBLE
    }

    private fun saveProgress(finish: Boolean = false) {
        val app = ctx ?: return
        val it = item ?: return
        val exo = player ?: return
        val pos = exo.currentPosition
        if (pos <= 0L) return
        app.hub.progress(it.identity, if (finish) 0L else pos, app.profile?.id, curEpisode)
    }

    // ---- live zapping ------------------------------------------------------------------------

    private fun isChannel(): Boolean = item?.isLive() == true

    /**
     * Change channel with Left/Right, in the order of the row the channel came from, skipping the
     * ones this TV cannot play so a zap never lands on a dead frequency.
     */
    private fun zap(delta: Int): Boolean {
        val app = ctx ?: return false
        val cur = item?.identity ?: return false
        val row = app.lastHome.firstOrNull { r -> r.items.any { it.identity == cur } } ?: return false
        val n = row.items.size
        if (n < 2) return false
        val at = row.items.indexOfFirst { it.identity == cur }
        var i = at
        repeat(n - 1) {
            i = ((i + delta) % n + n) % n
            val cand = row.items[i]
            val src = app.playableSource(cand) ?: return@repeat
            item = cand
            ranked = app.ranked(cand)
            index = ranked.indexOfFirst { it === src }.coerceAtLeast(0)
            hubSubs = emptyList()
            titleView?.text = cand.title
            timeTotal?.text = Format.duration(cand.durationMs)
            loadSubs(app, cand)
            openAt(index, 0L, null)
            showZapBadge(cand, i + 1)
            showControls()
            return true
        }
        setStatus("No other channel here could be played")
        return true
    }

    /** Numbered the way an operator numbers them: the hub's number, else the position in the row. */
    private fun showZapBadge(it: Item, fallbackNumber: Int) {
        val b = zapBadge ?: return
        val num = it.live?.number?.takeIf { n -> n > 0 } ?: fallbackNumber
        b.text = "$num \u00B7 ${it.title}"
        b.visibility = View.VISIBLE
        handler.removeCallbacks(hideBadge)
        handler.postDelayed(hideBadge, 2600L)
    }

    // ---- autoplay next -----------------------------------------------------------------------

    private fun maybeAutoplayNext() {
        val app = ctx ?: return
        if (!app.prefs.autoplayNext(app.profile?.id ?: "")) return
        // A series steps within itself first: the next episode of the same show, which is what someone
        // watching a show expects. Only a film — or the last episode the hub knows about — falls through
        // to the next title in the row.
        val nx = nextEpisode
        val now = item
        if (nx != null && now != null) {
            curEpisode = nx
            nextEpisode = null   // this play answer names the one after that
            titleView?.text = episodeTitle(now.title, nx)
            hubSubs = emptyList()
            loadSubs(app, now)   // subtitles are per episode, not per show
            openAt(index, 0L, "Playing ${nx.label()}")
            return
        }
        val next = nextUp(app) ?: return
        item = next
        ranked = app.ranked(next)
        curEpisode = next.progress?.episode
        nextEpisode = null
        val chosen = app.playableSource(next)
        index = ranked.indexOfFirst { it === chosen }.coerceAtLeast(0)
        hubSubs = emptyList()
        titleView?.text = next.title
        timeTotal?.text = Format.duration(next.durationMs)
        loadSubs(app, next)
        openAt(index, 0L, "Playing next: ${next.title}")
    }

    /** "Game of Thrones · S01E04" — a series keeps its episode code on the title line. */
    private fun episodeTitle(show: String, ep: Episode): String = "$show \u00B7 ${ep.code()}"

    /** The item after this one in the row it came from, that this TV can play in place. */
    private fun nextUp(app: AppContext): Item? {
        val current = item?.identity ?: return null
        for (row in app.lastHome) {
            val at = row.items.indexOfFirst { it.identity == current }
            if (at < 0) continue
            for (i in (at + 1) until row.items.size) {
                val cand = row.items[i]
                val src = app.playableSource(cand) ?: continue
                if (!app.opensExternally(src)) return cand
            }
        }
        return null
    }

    // ---- teardown ----------------------------------------------------------------------------

    private fun releasePlayer() {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
        listener?.let { l -> player?.removeListener(l) }
        player?.stop()
        player?.release()
        player = null
        playerView?.player = null
        listener = null
        target = null
        plugin = null
        stalledMs = 0L
    }

    private fun message(app: AppContext, text: String): View {
        val theme = app.theme
        val v = Views.text(app, "h3", 34f, theme.ink2, text, gravity = Gravity.CENTER)
        v.setBackgroundColor(theme.bg)
        return v
    }

    override fun onLeave(host: ScreenHost) {
        val exo = player
        if (exo != null) {
            saveProgress(finish = exo.playbackState == Player.STATE_ENDED)
        }
        releasePlayer()
        closePanelQuiet()
        root = null
        panelLayer = null
        controls = null
        readout = null
        statusLine = null
        titleView = null
        timeNow = null
        timeTotal = null
        barFill = null
        item = null
        ranked = emptyList()
        hubSubs = emptyList()
        index = 0
        speedAt = 1
        speed = 1.0f
        subMode = -2
        aspectAt = 0
        capAt = 0
        lastBitrate = 0
        hostRef = null
        ctx = null
    }

    private fun closePanelQuiet() {
        panelLayer?.removeAllViews()
        panel = null
    }

    /** Back closes the settings sheet first, then leaves the player. */
    override fun onBack(host: ScreenHost): Boolean {
        if (panelOpen()) {
            closePanel()
            return true
        }
        return false
    }
}
