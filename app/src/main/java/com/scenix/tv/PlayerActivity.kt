package com.scenix.tv

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.scenix.tv.core.HubEndpoint
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Item
import com.scenix.tv.core.Programme
import com.scenix.tv.core.Motion
import com.scenix.tv.core.Views
import com.scenix.tv.plugins.player.Ceilings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native television playback.
 *
 * The hub still owns the decision: it ranks the copies, measures them and answers `POST /app/v1/play`
 * with one URL, its headers, its tracks and the next episode. This activity only opens that answer in
 * Media3 and draws the remote-first chrome around it — play/pause, a scrubber, audio, subtitles,
 * speed and source. Nothing here re-ranks, transcodes or guesses; the same contract the web player
 * speaks is spoken natively, with the same optional Basic token.
 *
 * The behaviours that make a free copy watchable live here too:
 *   · `503 warming` is a Preparing state, not an error: ask again at the hub's `retryAfterMs`.
 *   · a copy that fails is replaced by the next ranked one at the same position, bounded, through
 *     `POST /app/v1/switch` with the tried copies excluded.
 *   · progress is posted every [progressPostMs] and on pause/exit, so Continue Watching is the hub's.
 *   · a live channel has no scrubber and no speed, and left/right at rest zaps the live row.
 *   · a series rolls into its next episode with a visible countdown and an Undo window.
 */
class PlayerActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var profileId: String
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }
    private val handler = Handler(Looper.getMainLooper())
    private val theme get() = app.theme

    // ---- what is playing ------------------------------------------------------------------
    private data class PlaySpec(
        val identity: String,
        val type: String,
        val season: Int,
        val episode: Int,
        val title: String,
    )

    /** A stable identity for a row in the episode sheet while its views are rebuilt per season. */
    private data class EpisodeRowKey(val season: Int, val episode: Int)

    private var spec: PlaySpec? = null
    /** The spec the player is actually holding, which can trail [spec] while a new answer is in flight. */
    private var loadedSpec: PlaySpec? = null
    private var loadedTitle = ""
    private var loadedAnswer: JSONObject? = null
    private var answer: JSONObject? = null
    private var live = false
    private var displayTitle = ""
    private var currentSourceLabel = ""
    /** The copy the viewer explicitly picked; empty means Auto (the hub's ranked pick). */
    private var chosenSourceKey = ""
    /** Set while a manual switch is in flight, so success can record the choice. */
    private var pendingSourceKey = ""
    private var audioTracks = JSONArray()
    /** Languages this title can play, including donors the hub can mix into the picture. */
    private var audioOptions = JSONArray()
    // The audio languages this TITLE offers, not just the copy on screen. A quality/source switch
    // re-answers /play, and a different file (or a different donor pool) used to hand back a
    // different language list: Hindi present at 480p, gone at 1080p, English only at 4K. A language
    // belongs to the title, so entries are merged by language and never dropped while the same
    // episode is playing.
    private val audioCatalog = LinkedHashMap<String, JSONObject>()
    private var audioCatalogKey = ""
    // Every copy this title has offered, keyed by its label. The Quality menu is a property of the
    // title too: after switching 1080 → 720 → Auto, the hub answered with fewer alternatives each
    // time and the previous copy vanished from the list. Remembering them keeps the menu stable and
    // still lets the hub verify a stale one when it is chosen.
    private val sourceCatalog = LinkedHashMap<String, JSONObject>()
    private var embeddedSubs = JSONArray()
    private var hubSubs = JSONArray()
    private var subsInItem = 0
    private var alternatives = JSONArray()
    private var nextEpisode: JSONObject? = null
    /** Full episode list for the series in play, read from meta the first time Episodes is opened. */
    private var episodesList: JSONArray? = null
    private var episodesFetching = false
    /** Season the Episodes menu is showing; 0 means "follow whatever episode is playing". */
    private var epSeason = 0
    /**
     * A season switch rebuilds the sheet. Keep the episode number as data across that rebuild so
     * there is one focus request, rather than a race between the default selection and a later
     * attempt to restore the viewer's position.
     */
    private var pendingEpisodeFocus: EpisodeRowKey? = null
    private var selectedAudioId: String? = null
    private var selectedSubId: String? = null
    private var audioChosen = false
    private var subChosen = false
    private var speed = 1f
    private var durationMs = 0L
    /**
     * A ladder starts on a high rung on this household's LAN instead of waiting four seconds on
     * 480p to measure the link. The override is cleared on the first rendered frame, so adaptive
     * selection takes over before the viewer can notice it was ever fixed.
     */
    private var startRungPending = false
    private var startRungLocked = false
    /** A rung the viewer pinned from the Quality menu, in pixels; 0 means adaptive selection. */
    private var forcedRungHeight = 0
    private var bandwidthMeter: DefaultBandwidthMeter? = null
    /**
     * Film time where the current HLS ladder begins. An adaptive resume is transcoded from the saved
     * playhead, so the player's own timeline starts at zero while the viewer still expects 12:34.
     * Every readout, seek and saved progress adds this back; zero for a direct copy.
     */
    private var timelineOffsetMs = 0L
    private var maxPosition = 0L
    private var lastSavedPosition = -1L
    private var nextProgressAt = 0L
    /** Live watch time, counted only while frames move, flushed to the hub as `watchMs` deltas. */
    private var liveWatchMs = 0L
    private var liveWatchBaseAt = 0L
    private var requestGen = 0
    private var subsGen = 0
    private var failovers = 0
    private var sameCopyRetries = 0
    /** Bounded re-tunes after a live feed closed its body mid-evening; reset when a picture plays. */
    private var liveEndedRetries = 0
    private var bufferingSince = 0L
    private var recoveryScheduled = false
    /** Picture the player is holding, in ms ahead of the playhead, and how long it has been shrinking. */
    private var cushionAheadMs = -1L
    private var cushionFalling = 0
    private var cushionSwitches = 0
    /**
     * A copy that is still filling is not a copy that failed. The watchdog waits before spending the
     * retry/failover budget; an ExoPlayer error still gets its own two soft reloads first.
     */
    private val stallCheck = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        val p = player ?: return@Runnable
        if (p.playbackState != Player.STATE_BUFFERING) {
            bufferingSince = 0L
            return@Runnable
        }
        val pos = filmPosition(p)
        if (sameCopyRetries < 2) {
            sameCopyRetries++
            showCenter("Still loading this copy…", true)
            // Gen-guarded like every HTTP path: a switch or an episode jump that resolved while this
            // retry was queued must not be restarted at the OLD item's playhead when the lambda fires.
            val gen = requestGen
            handler.postDelayed({
                if (!isFinishing && gen == requestGen) answer?.let { start(it, pos, playWhenReady = true) }
            }, 1200L)
            bufferingSince = 0L
            return@Runnable
        }
        if (!recoveryScheduled) {
            recoveryScheduled = true
            showCenter("This copy is taking too long — trying a lighter one…", true)
            failoverToNext(preferLower = true)
        }
    }
    private val tried = LinkedHashSet<String>()
    private var liveChannels: List<Item> = emptyList()
    private var liveLoaded = false
    private var pausedForBackground = false
    /** True while the viewer asked for the picture's own audio instead of the hub's language mix. */
    private var mixDisabled = false
    /** The last `audioMix` the hub negotiated, kept so the Audio menu can switch back to it. */
    private var mixHint: JSONObject? = null
    /** The hub can mark an episode's intro window; zero means no window, so no button is invented. */
    private var introStartMs = 0L
    private var introEndMs = 0L
    private var skipChip: TextView? = null

    // ---- media ----------------------------------------------------------------------------
    private var player: ExoPlayer? = null
    private var http: DefaultHttpDataSource.Factory? = null
    private var playerView: PlayerView? = null

    // ---- chrome ---------------------------------------------------------------------------
    private lateinit var root: FrameLayout
    private lateinit var overlay: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var buttonRow: LinearLayout
    private lateinit var seekLine: SeekLine
    /** The name of the focused control, shown above the transport while the viewer is in it. */
    private lateinit var controlHint: TextView
    private lateinit var elapsed: TextView
    private lateinit var remaining: TextView
    private lateinit var titleView: TextView
    private lateinit var metaView: TextView
    private lateinit var statusView: TextView
    private lateinit var liveBadge: TextView
    private lateinit var backButton: GlyphView
    private lateinit var playButton: GlyphView
    private lateinit var rewindButton: GlyphView
    private lateinit var forwardButton: GlyphView
    private lateinit var audioButton: GlyphView
    private lateinit var subButton: GlyphView
    private lateinit var speedButton: GlyphView
    private lateinit var qualityButton: GlyphView
    private var episodesButton: GlyphView? = null
    private lateinit var centerBox: LinearLayout
    private lateinit var centerSpinner: ProgressBar
    private lateinit var centerText: TextView

    private var panel: View? = null
    private var panelKind: MenuKind? = null
    private var panelAnchor: View? = null
    private var panelRows: List<View> = emptyList()
    private var barFocusable: List<View> = emptyList()

    private var errorShowing = false
    private var nextShowing = false
    private var undoShowing = false
    private var undoSpec: PlaySpec? = null
    /** Where the viewer sat in the episode auto-advance just left; Undo goes back TO it, not to 0. */
    private var undoPositionMs = 0L

    private val hideRunnable = Runnable { hideChrome() }
    private var ticker: Runnable? = null
    private var nextTicker: Runnable? = null
    private var undoRunnable: Runnable? = null
    private var statusClear: Runnable? = null

    private val controlsHideMs
        get() = theme.token("player", "controlsHideMs", 6000.0).toInt().coerceAtLeast(2000)
    private val progressPostMs
        get() = theme.token("player", "progressPostMs", 10000.0).toLong().coerceAtLeast(5000L)
    private val autoplayMs
        get() = theme.token("player", "autoplayCountdownMs", 7000.0).toInt().coerceAtLeast(0)
    private val seekBackMs get() = theme.seekBackMs.toLong()
    private val seekForwardMs get() = theme.seekForwardMs.toLong()

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        profileId = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
        val identity = intent?.getStringExtra(EXTRA_IDENTITY).orEmpty()
        if (profileId.isBlank() || identity.isBlank()) {
            finish()
            return
        }
        val spec = PlaySpec(
            identity = identity,
            type = intent?.getStringExtra(EXTRA_TYPE).orEmpty().ifBlank { "movie" },
            season = intent?.getIntExtra(EXTRA_SEASON, 0) ?: 0,
            episode = intent?.getIntExtra(EXTRA_EPISODE, 0) ?: 0,
            title = intent?.getStringExtra(EXTRA_TITLE).orEmpty(),
        )
        displayTitle = spec.title
        buildPlayer()
        buildViews()
        setContentView(root)
        open(spec, intent?.getLongExtra(EXTRA_POSITION_MS, 0L) ?: 0L)
        if (live || spec.type == "channel" || spec.type == "live") prefetchLiveChannels()
        ticker = object : Runnable {
            override fun run() {
                tick()
                handler.postDelayed(this, 500L)
            }
        }.also { handler.postDelayed(it, 500L) }
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) return
        // A TV asleep behind a paused activity must not keep decoding, and the hub still deserves the
        // playhead the viewer left on.
        pausedForBackground = player?.isPlaying == true
        player?.playWhenReady = false
        saveProgress(force = true)
    }

    override fun onStart() {
        super.onStart()
        // Coming back from the launcher to a paused player with the chrome already faded left a black
        // screen until a key was pressed. Bring the ring back; the viewer decides whether to resume.
        if (pausedForBackground) {
            pausedForBackground = false
            updatePlayLabel()
            showChrome(focus = true)
        }
    }

    override fun onDestroy() {
        saveProgress(force = true)
        ticker?.let { handler.removeCallbacks(it) }
        nextTicker?.let { handler.removeCallbacks(it) }
        undoRunnable?.let { handler.removeCallbacks(it) }
        statusClear?.let { handler.removeCallbacks(it) }
        handler.removeCallbacks(hideRunnable)
        panel?.let { overlay.removeView(it) }
        panel = null
        playerView?.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    // =========================================================================================
    // media
    // =========================================================================================

    private fun buildPlayer() {
        val httpFactory = DefaultHttpDataSource.Factory()
            // NOT setUserAgent: DefaultHttpDataSource applies the factory's UA AFTER the per-request
            // properties, so the hub's own User-Agent — the browser UA live feeds demand — was silently
            // replaced by ours on every IPTV request (Referer survived, UA did not). The fallback lives
            // in defaultRequestProperties instead, which the answer's headers may still override.
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(30000)
            .setAllowCrossProtocolRedirects(true)
        httpFactory.setDefaultRequestProperties(mapOf("User-Agent" to "Scenix/0.1"))
        http = httpFactory

        val ctx = app
        // A hub with HUB_TOKEN protects its own URLs, including the gateway's stream. The token is
        // added only for this hub's origin: a direct third-party stream must never learn the
        // household's token.
        val base = HubEndpoint.base(this)
        val hubOrigin = Uri.parse(base)
        val dataSource = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this, httpFactory)
        ) { dataSpec ->
            val token = app.prefs.hubToken()
            if (token.isNotBlank() && sameOrigin(dataSpec.uri, hubOrigin)) {
                val basic = Base64.encodeToString("scenix:$token".toByteArray(), Base64.NO_WRAP)
                dataSpec.withAdditionalHeaders(mapOf("Authorization" to "Basic $basic"))
            } else {
                dataSpec
            }
        }
        // An adaptive ladder begins on the rung the player's starting bandwidth estimate allows. With
        // no estimate media3 conservatively opens the lowest rung, so a 720p film visibly starts at
        // 480p and upshifts four seconds later. The hub is on this household's LAN, where even a weak
        // Wi-Fi link carries more than 9 Mbps; seeding that figure selects 720p at once for a 720/480
        // ladder, and 720p first (then 1080p by measurement) for a 1080 ladder. A remote hub keeps
        // media3's own cautious start: the LAN assumption does not hold beyond the router.
        val meterBuilder = DefaultBandwidthMeter.Builder(this)
            // A network-type callback at startup would otherwise replace the LAN seed with media3's
            // country default (often 1 Mbps) before the first segment is measured.
            .setResetOnNetworkTypeChange(false)
        if (isLocalHub(hubOrigin.host)) meterBuilder.setInitialBitrateEstimate(9_000L)
        val meter = meterBuilder.build()
        bandwidthMeter = meter
        val builder = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setBandwidthMeter(meter)
            .setLoadControl(Ceilings.loadControl(ctx))
        Ceilings.seekIncrements(builder, ctx)
        val p = builder.build()
        Ceilings.applyDefaults(p, ctx)
        // Audio focus: a television may hand the speakers to another input or app. Without this the
        // decoder keeps running silently behind it; with it the film pauses and resumes with the room.
        p.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
        p.addListener(listener())
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                Log.i(TAG, "first video frame on screen")
                // The picture is visibly playing; whatever was covering it was a state that has
                // now ended on its own.
                hasPicture = true
                if (errorShowing) clearError()
                hideCenter()
                releaseStartRung()
            }

            override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: androidx.media3.common.VideoSize) {
                Log.i(TAG, "video size ${videoSize.width}x${videoSize.height} par=${videoSize.pixelWidthHeightRatio}")
            }

            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long,
            ) {
                Log.i(TAG, "bandwidth estimate ${bitrateEstimate / 1000} kbps"
                    + " (${totalBytesLoaded}B in ${totalLoadTimeMs}ms)")
            }

            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                Log.w(TAG, "dropped $droppedFrames video frames in ${elapsedMs}ms")
            }
        })
        player = p
    }

    private fun listener() = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> {
                    if (!errorShowing) showCenter(if (live) "Tuning…" else "Buffering…", true)
                    if (bufferingSince == 0L) {
                        bufferingSince = System.currentTimeMillis()
                        handler.removeCallbacks(stallCheck)
                        handler.postDelayed(stallCheck, STALL_AFTER_MS)
                    }
                    updatePlayLabel()
                }
                Player.STATE_READY -> {
                    bufferingSince = 0L
                    handler.removeCallbacks(stallCheck)
                    hideCenter()
                    durationMs = player?.duration?.takeIf { it > 0 }?.let { d ->
                        // A resume ladder's duration is only the part after the offset; the film's
                        // full length is what the viewer should see on the bar.
                        if (timelineOffsetMs > 0) maxOf(durationMs, d + timelineOffsetMs) else d
                    } ?: durationMs
                    speed = player?.playbackParameters?.speed ?: speed
                    updatePosition()
                    updatePlayLabel()
                    applyPendingTracks()
                }
                Player.STATE_ENDED -> {
                    hideCenter()
                    updatePlayLabel()
                    onEnded()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayLabel()
            if (isPlaying) {
                bufferingSince = 0L
                liveEndedRetries = 0
                handler.removeCallbacks(stallCheck)
                // A frame is moving: no loading card and no failure card may sit on top of it. This
                // is the one place that guarantees the rule, whichever path started the picture.
                if (errorShowing) clearError()
                hideCenter()
            }
            // Only a PAUSE the viewer asked for deserves the forced write. isPlaying also flips false
            // on every rebuffer and every setMediaItem swap; forcing there makes a stall-prone copy
            // chatty with the progress store on top of the regular cadence in tick().
            if (isPlaying) armHide()
            else if (player?.playWhenReady != true) saveProgress(force = true)
        }

        override fun onTracksChanged(tracks: Tracks) {
            startOnHighRung(tracks)
            applyPendingTracks()
            paintChips()
            refreshTrackMenus()
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlaybackError(error)
        }
    }

    private fun bodyFor(
        spec: PlaySpec,
        positionMs: Long,
        source: JSONObject?,
        exclude: Set<String>,
    ): JSONObject {
        val body = JSONObject()
            .put("identity", spec.identity)
            .put("profile", profileId)
            .put("type", spec.type)
            // A television plays the copy's own container; asking for MP4/HLS would put a re-wrap on
            // the hub for no reason and throw away E-AC3/DTS passthrough.
            .put("container", "any")
            // What this decoder can actually draw. A hub that knows the codecs cannot hand over a
            // file whose video this panel will refuse after the first frame.
            .put("codecs", supportedCodecs())
            // Opt-in at the hub: an owner may ask for the transcoded HLS ladder. The hub ignores this
            // when the setting is off and returns the copy it would have returned anyway.
            .put("ladder", true)
            // The panel states its own ceiling; the hub takes the lower of this and the owner's ladder
            // setting. A 4K file is not offered a 2160 rung on this display.
            .put("displayMaxHeight", app.theme.playerMaxHeight)
            .put("displayMaxWidth", app.theme.playerMaxWidth)
            .put("positionMs", positionMs.coerceAtLeast(0L))
        if (spec.season > 0 && spec.episode > 0) {
            body.put("season", spec.season).put("episode", spec.episode)
        }
        if (source != null) body.put("source", source)
        if (exclude.isNotEmpty()) body.put("exclude", JSONArray(exclude.toList()))
        // The donor is on by default; the Audio menu's "Original audio" turns it off for this title.
        if (mixDisabled) body.put("audioMix", false)
        return body
    }

    /** The video/audio families this decoder advertises, in the names the hub's ranking speaks. */
    private fun supportedCodecs(): JSONArray {
        val out = JSONArray()
        val seen = LinkedHashSet<String>()
        fun add(name: String) { if (name.isNotBlank() && seen.add(name)) out.put(name) }
        try {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder) continue
                for (mime in info.supportedTypes) {
                    when (mime.lowercase()) {
                        "video/avc" -> add("h264")
                        "video/hevc", "video/dolby-vision" -> {
                            // A decoder that lists HEVC but only the 8-bit Main profile will open a
                            // 10-bit source and then fail with NO_EXCEEDS_CAPABILITIES, which is the
                            // black-screen-with-sound failure. Only advertise HEVC when Main10 is
                            // actually in its profile list, and stop advertising it after a decode
                            // failure taught this panel that the list was optimistic.
                            if (!app.prefs.hevcBlocked()) {
                                val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
                                val main10 = caps?.profileLevels?.any {
                                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                                } == true
                                if (main10) add("hevc")
                            }
                        }
                        "video/av01" -> add("av1")
                        "video/x-vnd.on2.vp9" -> add("vp9")
                        "video/mp4v-es" -> add("mpeg4")
                        "audio/mp4a-latm" -> add("aac")
                        "audio/ac3" -> add("ac3")
                        "audio/eac3" -> add("eac3")
                        "audio/opus" -> add("opus")
                    }
                }
            }
        } catch (t: Throwable) {
            // A codec table that cannot be read is not a reason to fail a play; it just means the
            // hub filters nothing.
        }
        return out
    }

    private fun sameTitle(a: String, b: String): Boolean {
        fun norm(x: String) = x.lowercase().replace(Regex("[^a-z0-9]+"), "").trim()
        val na = norm(a)
        val nb = norm(b)
        return na.isNotEmpty() && nb.isNotEmpty() && na == nb
    }

    private fun open(spec: PlaySpec, positionMs: Long) {
        saveProgress(force = true)
        // End the old picture NOW. Leaving the previous episode decoding means its sound plays under
        // "Preparing this copy…", and its STATE_READY keeps pictureLive() true — so when the NEW title
        // fails, showError degrades to a one-line status and the viewer is left with a spinner over
        // the wrong film. (Same reasoning in spectrum().)
        player?.let {
            try { it.stop(); it.clearMediaItems() } catch (e: Exception) { /* already gone */ }
        }
        this.spec = spec
        answer = null
        live = spec.type == "channel" || spec.type == "live"
        displayTitle = spec.title
        tried.clear()
        failovers = 0
        sameCopyRetries = 0
        bufferingSince = 0L
        recoveryScheduled = false
        mixDisabled = false
        mixHint = null
        introStartMs = 0L
        introEndMs = 0L
        skipChip?.visibility = View.GONE
        selectedAudioId = null
        selectedSubId = null
        audioChosen = false
        subChosen = false
        audioTracks = JSONArray()
        embeddedSubs = JSONArray()
        alternatives = JSONArray()
        nextEpisode = null
        epSeason = 0
        currentSourceLabel = ""
        durationMs = 0L
        timelineOffsetMs = 0L
        startRungPending = false
        startRungLocked = false
        forcedRungHeight = 0
        seekLine.fraction = 0f
        seekLine.buffered = 0f
        seekLine.invalidate()
        elapsed.text = "0:00"
        remaining.text = ""
        hideNextCard()
        hideUndo()
        clearError()
        updateHeader()
        paintChips()
        paintTransport(false)
        closeMenu(restore = false)
        showChrome(focus = true)
        showCenter("Preparing this copy…", true)
        requestSubs(spec.identity)
        resolvePlay(spec, positionMs, WARM_TRIES, null)
        if (live) prefetchLiveChannels()
    }

    /** Ask the hub for the copy. Warming answers are retried; only a real refusal becomes an error. */
    private fun resolvePlay(
        spec: PlaySpec,
        positionMs: Long,
        warmTries: Int,
        source: JSONObject?,
        extra: JSONObject? = null,
    ) {
        val gen = ++requestGen
        val body = bodyFor(spec, positionMs, source, emptySet())
        extra?.let { for (key in it.keys()) body.put(key, it.get(key)) }
        api.play(body) { reply, status, transport ->
            if (gen != requestGen || isFinishing || isDestroyed) return@play
            val url = str(reply, "url")
            val warming = status == 503 || reply?.optBoolean("warming", false) == true ||
                str(reply, "error").contains("prepar", ignoreCase = true)
            if ((url.isBlank() || !isPlayable(url)) && warming && warmTries > 0) {
                val wait = (reply?.optLong("retryAfterMs", 1500L) ?: 1500L).coerceIn(900L, 4000L)
                showCenter("The hub is preparing this copy…", true)
                handler.postDelayed({
                    if (gen == requestGen && !isFinishing) {
                        resolvePlay(spec, positionMs, warmTries - 1, source, extra)
                    }
                }, wait)
                return@play
            }
            if (status !in 200..299 || url.isBlank() || !isPlayable(url)) {
                val why = str(reply, "error")
                    .ifBlank { transport ?: "The hub could not open this title" }
                showError(why)
                return@play
            }
            sameCopyRetries = 0
            recoveryScheduled = false
            start(reply!!, positionMs, playWhenReady = true)
        }
    }

    /** The answer is good: turn it into a MediaItem and start. Used for play, switch and retry. */
    private fun start(reply: JSONObject, positionMs: Long, playWhenReady: Boolean) {
        bufferingSince = 0L
        handler.removeCallbacks(stallCheck)
        // A hub answer must belong to the title this screen opened. If a provider or a stale route
        // answers another identity, refuse it instead of putting a different film on screen.
        val expected = spec
        val answerIdentity = str(reply, "identity")
        if (expected != null && answerIdentity.isNotBlank() &&
            expected.identity.isNotBlank() && answerIdentity != expected.identity) {
            showError("The hub answered a different title. Nothing was started.")
            return
        }
        val answerTitle = str(reply, "title")
        if (expected != null && !live && answerTitle.isNotBlank() && expected.title.isNotBlank() &&
            !sameTitle(answerTitle, expected.title)) {
            showError("The hub answered a different title. Nothing was started.")
            return
        }
        clearError()
        // A failed source pick writes a status line; the new copy starting is the moment that line
        // stops being true. Leaving it up is how "not available" sat on screen over a playing film.
        setStatus("")
        answer = reply
        val type = str(reply, "type", spec?.type ?: "movie")
        live = reply.optBoolean("live", false) || type == "channel" || type == "live"
        durationMs = reply.optLong("durationMs", 0L)
        displayTitle = str(reply, "title").ifBlank { spec?.title.orEmpty() }
        nextEpisode = reply.optJSONObject("nextEpisode")
        alternatives = reply.optJSONArray("alternatives") ?: JSONArray()
        audioTracks = reply.optJSONArray("audioTracks") ?: JSONArray()
        // Reset only when the title/episode changes; a source or rung switch keeps the same book.
        val keySpec = spec
        val catalogKey = (keySpec?.identity ?: str(reply, "identity")) + "|" +
            (if (keySpec != null && keySpec.season > 0) "${keySpec.season}x${keySpec.episode}" else "")
        if (catalogKey != audioCatalogKey) {
            audioCatalog.clear()
            sourceCatalog.clear()
            audioCatalogKey = catalogKey
            chosenSourceKey = ""
            pendingSourceKey = ""
        }
        mergeAudioOptions(reply.optJSONArray("audioOptions") ?: JSONArray())
        audioOptions = JSONArray(audioCatalog.values.toList())
        introStartMs = reply.optLong("introStartMs", 0L)
        introEndMs = reply.optLong("introEndMs", 0L)
        if (introEndMs <= 0L) skipChip?.visibility = View.GONE
        // A resume ladder is transcoded from the saved playhead. Its playlist starts at zero, so the
        // player's own timeline is relative; `startMs` is the film time to add back everywhere.
        timelineOffsetMs = (reply.optJSONObject("ladder")?.optLong("startMs", 0L) ?: 0L)
            .coerceAtLeast(0L)
        startRungPending = reply.optJSONObject("ladder") != null &&
            isLocalHub(Uri.parse(HubEndpoint.base(this)).host)
        startRungLocked = false
        forcedRungHeight = 0
        val mix = reply.optJSONObject("audioMix")
        if (mix != null) {
            mixHint = mix
            if (!mixDisabled) {
                val lang = str(mix, "languageName").ifBlank { langName(str(mix, "language")) }
                val donor = str(mix, "source")
                if (lang.isNotBlank()) {
                    setStatus(lang + " audio" + if (donor.isNotBlank()) " · " + donor else "")
                }
            }
        }
        embeddedSubs = reply.optJSONArray("subtitleTracks") ?: JSONArray()
        val picked = reply.optJSONObject("picked")
        if (picked != null) {
            currentSourceLabel = sourceWords(picked)
            // A copy that played is not a failed copy. Putting it in `tried` made the next /play
            // exclude it, which is exactly why a 720p choice disappeared from the Quality list
            // after the viewer had watched it once.
            rememberSource(picked)
        }
        for (i in 0 until alternatives.length()) rememberSource(alternatives.optJSONObject(i))
        // A source switch must not silently undo the viewer's explicit audio/subtitle choice. The hub's
        // recommendation is only the starting point; once a person has chosen, that choice survives a
        // new copy (matched by stream index/id, and left alone if the new copy cannot honour it).
        val answerAudio = str(reply, "selectedAudio").takeIf { it.isNotBlank() }
        val answerSub = str(reply, "selectedSub").takeIf { it.isNotBlank() }
        if (!audioChosen) selectedAudioId = answerAudio
        if (!subChosen) selectedSubId = answerSub

        // Baseline UA first, then the answer's own headers on top: setDefaultRequestProperties REPLACES
        // the map, so a hub answer that carries only Referer must not also erase our fallback UA.
        val headers = HashMap<String, String>()
        headers["User-Agent"] = "Scenix/0.1"
        reply.optJSONObject("headers")?.let { h ->
            for (k in h.keys()) headers[k] = h.optString(k, "")
        }
        http?.setDefaultRequestProperties(headers)

        val p = player ?: return
        val item = buildItem(reply)
        val current = spec
        val changed = loadedSpec == null || current == null ||
            loadedSpec?.identity != current.identity ||
            loadedSpec?.season != current.season ||
            loadedSpec?.episode != current.episode
        if (changed) {
            maxPosition = 0L
            lastSavedPosition = -1L
            nextProgressAt = 0L
        }
        loadedSpec = current
        loadedTitle = displayTitle
        loadedAnswer = reply
        // Seek the item to the same film position in the ladder's own relative timeline. For a direct
        // copy the offset is zero, so this is the original setMediaItem(item, positionMs).
        // A live item gets TIME_UNSET — the live edge. A saved/last playhead is behind a timeshift
        // window that has already moved, and seeking to it either throws BEHIND_LIVE_WINDOW or tunes
        // into nothing.
        // A resume point at or past the end of the copy (progress written from a longer alternate cut)
        // must not land the first frame inside STATE_ENDED and fire the next-episode countdown on a
        // "resume" nobody watched.
        val capped = if (durationMs > 0 && positionMs >= durationMs - 10_000)
            (durationMs - 20_000).coerceAtLeast(0L) else positionMs
        val mediaStart = if (live) C.TIME_UNSET
            else (capped - timelineOffsetMs).coerceAtLeast(0L)
        p.setMediaItem(item, mediaStart)
        pendingAudio = selectedAudioId
        pendingSub = selectedSubId
        audioApplied = false
        subApplied = false
        subsInItem = hubSubs.length()
        p.prepare()
        p.playWhenReady = playWhenReady
        speed = p.playbackParameters.speed
        p.setPlaybackSpeed(speed)
        paintTransport(true)
        updateHeader()
        paintChips()
        hideNextCard()
        setStatus("")
        updatePosition()
        updatePlayLabel()
        if (!controlsVisible() || currentFocus == null) showChrome(focus = true) else armHide()
    }

    private fun buildItem(reply: JSONObject): MediaItem {
        val url = str(reply, "url")
        val builder = MediaItem.Builder().setUri(url)
        when (str(reply, "kind").lowercase()) {
            "hls" -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            "dash" -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
            else -> {
                val bare = url.substringBefore('?').lowercase()
                if (bare.endsWith(".m3u8")) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
                else if (bare.endsWith(".mpd")) builder.setMimeType(MimeTypes.APPLICATION_MPD)
            }
        }
        val configs = ArrayList<MediaItem.SubtitleConfiguration>()
        // An offset ladder's media timeline starts at the resume point, while an external subtitle
        // file is timed from the beginning of the film. Loading it unchanged would show every line
        // minutes early; the menu says subtitles are off rather than pretending they are in sync.
        if (timelineOffsetMs <= 0L) for (i in 0 until hubSubs.length()) {
            val s = hubSubs.optJSONObject(i) ?: continue
            val subUrl = str(s, "url").ifBlank { str(s, "src") }
            if (subUrl.isBlank()) continue
            val absolute = absoluteUrl(subUrl)
            val lang = str(s, "lang").ifBlank { str(s, "language") }.ifBlank { "und" }
            val label = str(s, "label").ifBlank { str(s, "name") }.ifBlank { langName(lang) }
            configs.add(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(absolute))
                    .setId("hub:$i")
                    .setMimeType(subtitleMime(str(s, "format"), absolute))
                    .setLanguage(lang)
                    .setLabel(label)
                    .setSelectionFlags(0)
                    .build()
            )
        }
        if (configs.isNotEmpty()) builder.setSubtitleConfigurations(configs)
        return builder.build()
    }

    private fun isPlayable(url: String): Boolean =
        url.startsWith("http://", true) || url.startsWith("https://", true)

    /**
     * A hub with HUB_TOKEN protects its own URLs, including the gateway's stream. Only a URL whose
     * scheme, host and effective port are the hub's may carry that token; a direct third-party
     * stream must never learn the household's token. String-prefix checks are not enough — a crafted
     * `http://hub:8788@elsewhere` also starts with the hub's address.
     */
    private fun sameOrigin(uri: Uri, hub: Uri): Boolean {
        val a = uri.scheme?.lowercase() ?: return false
        val b = hub.scheme?.lowercase() ?: return false
        if (a != b || !uri.host.equals(hub.host, ignoreCase = true)) return false
        fun port(u: Uri): Int =
            if (u.port > 0) u.port else if ((u.scheme ?: "").equals("https", true)) 443 else 80
        return port(uri) == port(hub)
    }

    /**
     * A ladder's first chunk is chosen before adaptive selection has measured anything, and media3
     * starts that first selection at the lowest variant. Pinning the 720 rung for the first frame
     * turns "480p for four seconds, then 720p" into "720p immediately"; [releaseStartRung] hands the
     * decision back to the adaptive selector before the second segment. A 1080/2160 rung is left to
     * that selector, because starting there before measuring a weak link is how a startup stalls.
     */
    private fun startOnHighRung(tracks: Tracks) {
        if (!startRungPending) return
        val p = player ?: return
        var group: Tracks.Group? = null
        var index = -1
        var height = -1
        for (g in tracks.groups) {
            if (g.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val h = g.getTrackFormat(i).height
                if (h in 1..720 && h > height) {
                    group = g
                    index = i
                    height = h
                }
            }
        }
        if (group == null || index < 0) {
            startRungPending = false
            return
        }
        val params = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(index)))
            .build()
        p.trackSelectionParameters = params
        startRungPending = false
        startRungLocked = true
        Log.i(TAG, "starting ladder on ${height}p")
    }

    private fun releaseStartRung() {
        if (!startRungLocked) return
        startRungLocked = false
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .build()
        Log.i(TAG, "start rung released to adaptive selection, bandwidth "
            + ((bandwidthMeter?.bitrateEstimate ?: 0L) / 1000) + " kbps")
    }

    /**
     * Whether the hub address names this household's own network: loopback, the three private IPv4
     * ranges, link-local IPv6, or a `.local` name. Only there is it safe to start a ladder on a high
     * rung before measuring the link.
     */
    private fun isLocalHub(host: String?): Boolean {
        val h = host?.trim()?.lowercase() ?: return false
        if (h.isEmpty()) return false
        if (h == "localhost" || h == "::1" || h.endsWith(".local")) return true
        // A URI host is a name or an address. Only a colon proves the latter, so a domain that happens
        // to start with "fc" is not mistaken for a unique-local IPv6 address.
        if (h.contains(':') && (h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd"))) {
            return true
        }
        val parts = h.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return a == 127 || a == 10 || (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) || (a == 169 && b == 254)
    }

    /** A hub-relative subtitle path becomes an address the decoder can open. */
    private fun absoluteUrl(url: String): String = when {
        url.startsWith("http://", true) || url.startsWith("https://", true) -> url
        url.startsWith("/") -> HubEndpoint.base(this).trimEnd('/') + url
        else -> url
    }

    // ---- tracks -----------------------------------------------------------------------------

    private var pendingAudio: String? = null
    private var pendingSub: String? = null
    private var audioApplied = false
    private var subApplied = false

    private fun applyPendingTracks() {
        val p = player ?: return
        if (!audioApplied) audioApplied = applyChoice(C.TRACK_TYPE_AUDIO, p, pendingAudio)
        if (!subApplied) subApplied = applyChoice(C.TRACK_TYPE_TEXT, p, pendingSub)
        paintChips()
    }

    /**
     * `a:1`, `s:2`, `hub:3` or null/off. The hub's ids are the container's stream indices; media3's
     * group ids match for Matroska, and position is the same order ffprobe listed. External subtitle
     * configurations are matched by the id/label this activity gave them.
     */
    private fun applyChoice(type: Int, p: ExoPlayer, id: String?): Boolean {
        val b = p.trackSelectionParameters.buildUpon()
        if (id.isNullOrBlank()) {
            b.setTrackTypeDisabled(type, type == C.TRACK_TYPE_TEXT)
            b.clearOverridesOfType(type)
            p.trackSelectionParameters = b.build()
            return true
        }
        if (id == "off") {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).clearOverridesOfType(C.TRACK_TYPE_TEXT)
            p.trackSelectionParameters = b.build()
            return true
        }
        val groups = p.currentTracks.groups.filter { it.type == type }
        // The hub's ids are ffprobe's *global* stream indexes (`a:3` on a file whose third stream is
        // audio), not positions and not what media3 numbers its groups. The answer's list is in stream
        // order, so the row's place in it is the group to select. The old `a:3` -> group 3 lookup
        // silently left a Hindi track unselected on a Telugu/Tamil/Hindi file.
        val ordered = if (type == C.TRACK_TYPE_AUDIO) audioTracks else embeddedSubs
        val group = findGroup(groups, id, ordered) ?: return false
        b.setTrackTypeDisabled(type, false)
            .clearOverridesOfType(type)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(0)))
        p.trackSelectionParameters = b.build()
        return true
    }

    /** Codec names a person recognises, not the DASH codec string the container handed media3. */
    private fun codecName(raw: String): String {
        val c = raw.lowercase()
        return when {
            c.startsWith("mp4a") || c.contains("aac") -> "AAC"
            c.startsWith("ac-3") || c.contains("ac3") -> "Dolby Digital"
            c.startsWith("ec-3") || c.contains("eac3") -> "Dolby Digital+"
            c.contains("opus") -> "Opus"
            c.contains("vorbis") -> "Vorbis"
            c.contains("flac") -> "FLAC"
            c.contains("avc") || c.contains("h264") -> "H.264"
            c.contains("hevc") || c.contains("h265") -> "HEVC"
            c.contains("av01") || c.contains("av1") -> "AV1"
            c.contains("vp9") -> "VP9"
            else -> ""
        }
    }

    private fun findGroup(groups: List<Tracks.Group>, id: String, ordered: JSONArray?): Tracks.Group? {
        // First: the language/label the hub recorded for the clicked row, matched against what media3
        // reports. The positional lookup below is only correct while the hub's list is EXACTLY the
        // container's stream list — commentary filtering, language dedupe or a cover-art stream media3
        // hides all shift it, and a positional hit then selects a silent wrong language.
        val row = ordered?.optJSONObject(positionOf(ordered, id).takeIf { it >= 0 } ?: -1)
        val rowLabel = row?.optString("label").orEmpty().ifBlank { row?.optString("name").orEmpty() }
        val rowLang = row?.optString("lang").orEmpty().ifBlank { row?.optString("language").orEmpty() }
        if (rowLabel.isNotBlank()) {
            groups.firstOrNull { g -> (0 until g.length).any { i -> g.getTrackFormat(i).label == rowLabel } }
                ?.let { return it }
        }
        val position = positionOf(ordered, id)
        if (position >= 0 && rowLang.isBlank() && rowLabel.isBlank()) {
            groups.getOrNull(position)?.let { return it }
        } else if (position >= 0 && rowLang.isNotBlank()) {
            // Language known: only trust the position if that group actually carries the language —
            // otherwise find the group that does, and use the position only as the last resort.
            groups.firstOrNull { g ->
                (0 until g.length).any { i ->
                    val f = g.getTrackFormat(i)
                    f.language == rowLang || (f.language.isNullOrBlank() && rowLang == "und")
                }
            }?.let { return it }
            groups.getOrNull(position)?.let { return it }
        } else if (position >= 0) {
            groups.getOrNull(position)?.let { return it }
        }
        if (id.startsWith("hub:")) {
            val index = id.removePrefix("hub:").toIntOrNull() ?: return null
            val sub = hubSubs.optJSONObject(index) ?: return null
            val label = str(sub, "label").ifBlank { str(sub, "name") }
            val lang = str(sub, "lang").ifBlank { str(sub, "language") }
            return groups.firstOrNull { g ->
                (0 until g.length).any { i ->
                    val f = g.getTrackFormat(i)
                    f.id == id || (label.isNotBlank() && f.label == label) ||
                        (label.isBlank() && lang.isNotBlank() && f.language == lang)
                }
            }
        }
        val want = id.substringAfter(':', id)
        val index = want.toIntOrNull()
        return groups.firstOrNull { it.mediaTrackGroup.id == want }
            ?: groups.getOrNull((index ?: 0) - 1)
    }

    private fun chooseAudio(id: String?) {
        audioChosen = id != null
        selectedAudioId = id
        pendingAudio = id
        audioApplied = false
        applyPendingTracks()
        paintChips()
        closeMenu()
    }

    private fun chooseSub(id: String?) {
        subChosen = true
        selectedSubId = id
        if (id != null && id.startsWith("hub:")) {
            val index = id.removePrefix("hub:").toIntOrNull()
            if (index != null && index >= subsInItem) {
                // This subtitle file arrived after the picture started. Adding it to a prepared item
                // is not possible in media3, so reload the same copy at the same playhead once.
                reloadWithSubs(id)
                closeMenu()
                return
            }
        }
        pendingSub = id
        subApplied = false
        applyPendingTracks()
        paintChips()
        closeMenu()
    }

    private fun reloadWithSubs(subId: String) {
        val reply = answer ?: return
        val p = player ?: return
        val pos = filmPosition(p)
        val playing = p.playWhenReady
        pendingSub = subId
        pendingAudio = selectedAudioId
        audioApplied = false
        subApplied = false
        subsInItem = hubSubs.length()
        p.setMediaItem(buildItem(reply), mediaPosition(pos))
        p.prepare()
        p.playWhenReady = playing
        showCenter("Loading subtitles…", true)
    }

    private fun requestSubs(identity: String) {
        val gen = ++subsGen
        api.subs(identity, profileId, "") { subs, _ ->
            if (gen != subsGen || isFinishing || isDestroyed) return@subs
            hubSubs = subs ?: JSONArray()
            paintChips()
        }
    }

    private fun refreshTrackMenus() {
        // A menu that is open when the container is parsed (a slow source, a switch) is repainted in
        // place, so the viewer never reads a stale list.
        val kind = panelKind ?: return
        if (kind == MenuKind.AUDIO || kind == MenuKind.SUBTITLES) rebuildMenu(kind)
    }

    // =========================================================================================
    // recovery
    // =========================================================================================

    private fun handlePlaybackError(error: PlaybackException) {
        if (isFinishing || isDestroyed) return
        val name = error.errorCodeName
        Log.w(TAG, "playback error " + name + " / " + error.errorCode + " / " + error.message)
        val detail = (error.message ?: "").lowercase()
        // A message-text match alone banned HEVC for the whole install: media3 IO errors embed the data
        // URI, so a 404 on `movie-1080p-hevc.mkv` used to block the codec permanently with no way back.
        // Only a decoder-side fault on a copy that IS hevc may do that.
        val decoderFault = error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        val pickedLabel = str(answer?.optJSONObject("picked"), "label").lowercase()
        val hevcCopy = detail.contains("hevc") || detail.contains("hvc1") ||
            pickedLabel.contains("hevc") || pickedLabel.contains("h265")
        if (!app.prefs.hevcBlocked() && decoderFault && hevcCopy) {
            // The file reached the decoder; the decoder is the part that could not draw it. Remember
            // that for this panel, then let the normal failover ask the hub for an H.264 copy.
            app.prefs.setHevcBlocked(true)
            Log.w(TAG, "HEVC disabled for this panel after a decode failure")
        }
        val waiting = name.contains("TIMEOUT") || name.contains("IO_NETWORK") ||
            name.contains("IO_UNSPECIFIED") || name.contains("BEHIND_LIVE_WINDOW")
        val pos = player?.let { filmPosition(it) } ?: 0L
        if (waiting && sameCopyRetries < 2) {
            // A cold copy is not a broken file. Two silent reloads at the same playhead, with a little
            // more room each time, are cheaper than throwing away a copy that is still filling.
            sameCopyRetries++
            quietOrCenter(if (live) "Reconnecting to this channel…" else "Reconnecting to this copy…")
            val wait = if (sameCopyRetries == 1) 1200L else 2600L
            // Gen guard: if the viewer switched or jumped episodes while this retry was queued, the
            // lambda must not restart the NEW answer at the OLD item's playhead.
            val gen = requestGen
            handler.postDelayed({
                if (!isFinishing && gen == requestGen) answer?.let { start(it, pos, playWhenReady = true) }
            }, wait)
            return
        }
        answer?.optJSONObject("picked")?.let {
            str(it, "id").takeIf { v -> v.isNotBlank() }?.let { v -> tried.add(v) }
            str(it, "label").takeIf { v -> v.isNotBlank() }?.let { v -> tried.add(v) }
        }
        if (failovers >= MAX_FAILOVERS) {
            showError(if (live) "This channel is off the air. Try another channel."
                else "Nothing on this hub would play this title. ${sourceAdvice()}")
            return
        }
        failovers++
        val spec = spec ?: return
        val exclude = tried.toMutableSet()
        val body = bodyFor(spec, pos, null, exclude)
        quietOrCenter("This copy stopped — trying another…")
        val lower = lowerAlternative()
        if (preferLowerSource(lower, spec, pos)) return
        askSwitch(body, pos, playWhenReady = true, warmTries = 10, automatic = true)
    }

    private fun failoverToNext(preferLower: Boolean = false) {
        val spec = spec ?: return
        val pos = player?.let { filmPosition(it) } ?: 0L
        // A stalled copy never raised an ExoPlayer error, but this screen HAS tried it: record it, or
        // the switch below asks the hub for the same top-ranked copy, stalls again, and the cycle
        // spends the whole episode — this path never consulted MAX_FAILOVERS at all.
        answer?.optJSONObject("picked")?.let {
            str(it, "id").takeIf { v -> v.isNotBlank() }?.let { v -> tried.add(v) }
            str(it, "label").takeIf { v -> v.isNotBlank() }?.let { v -> tried.add(v) }
        }
        if (failovers >= MAX_FAILOVERS) {
            showError(if (live) "This channel is off the air. Try another channel."
                else "Nothing on this hub would play this title. ${sourceAdvice()}")
            return
        }
        failovers++
        clearError()
        if (preferLower) {
            val lower = lowerAlternative()
            if (preferLowerSource(lower, spec, pos)) return
        }
        val exclude = tried.toMutableSet()
        quietOrCenter("Trying another copy…")
        askSwitch(bodyFor(spec, pos, null, exclude), pos, playWhenReady = true, warmTries = 10, automatic = true)
    }

    /** The best untried copy that is lighter than the one we are on, or null. */
    private fun lowerAlternative(): JSONObject? {
        val current = answer?.optJSONObject("picked") ?: return null
        val currentScore = qualityScore(str(current, "label"))
        if (currentScore <= 0) return null
        var best: JSONObject? = null
        var bestScore = -1
        for (i in 0 until alternatives.length()) {
            val alt = alternatives.optJSONObject(i) ?: continue
            val id = str(alt, "id")
            val label = str(alt, "label")
            if ((id.isNotBlank() && tried.contains(id)) || (label.isNotBlank() && tried.contains(label))) continue
            val score = qualityScore(label)
            if (score > 0 && score < currentScore && score > bestScore) {
                best = alt
                bestScore = score
            }
        }
        return best
    }

    private fun preferLowerSource(source: JSONObject?, spec: PlaySpec, pos: Long): Boolean {
        if (source == null) return false
        val picked = JSONObject()
            .put("id", str(source, "id"))
            .put("label", str(source, "label"))
            .put("provider", str(source, "provider"))
        quietOrCenter("Trying a lighter copy…")
        askSwitch(bodyFor(spec, pos, picked, tried.toMutableSet()), pos,
            playWhenReady = true, warmTries = 8, automatic = true)
        return true
    }

    private fun qualityScore(label: String): Int {
        val l = label.lowercase()
        return when {
            l.contains("2160") || l.contains("4k") -> 4
            l.contains("1080") -> 3
            l.contains("720") -> 2
            l.contains("480") -> 1
            else -> 0
        }
    }

    private fun askSwitch(
        body: JSONObject,
        positionMs: Long,
        playWhenReady: Boolean,
        warmTries: Int,
        automatic: Boolean,
    ) {
        val gen = ++requestGen
        api.switchSource(body) { reply, status, transport ->
            if (gen != requestGen || isFinishing || isDestroyed) return@switchSource
            val url = str(reply, "url")
            val warming = status == 503 || reply?.optBoolean("warming", false) == true ||
                str(reply, "error").contains("prepar", ignoreCase = true)
            if ((url.isBlank() || !isPlayable(url)) && warming && warmTries > 0) {
                val wait = (reply?.optLong("retryAfterMs", 1500L) ?: 1500L).coerceIn(900L, 4000L)
                handler.postDelayed({
                    if (gen == requestGen && !isFinishing) {
                        askSwitch(body, positionMs, playWhenReady, warmTries - 1, automatic)
                    }
                }, wait)
                return@switchSource
            }
            if (status !in 200..299 || url.isBlank() || !isPlayable(url)) {
                hideCenter()
                if (automatic) {
                    val preparing = str(reply, "error").contains("prepar", ignoreCase = true) || warming
                    if (preparing) {
                        showError(
                            "The hub is still preparing this copy. Give it a moment, then try again.",
                            title = "This copy is still being prepared",
                        )
                    } else {
                        showError(
                            str(reply, "error").ifBlank {
                                transport ?: "No other copy of this title would open"
                            }
                        )
                    }
                } else {
                    val staying = currentSourceLabel.ifBlank { "the current copy" }
                    setStatus("Couldn't switch to that copy — still playing " + staying, "warn")
                    player?.playWhenReady = playWhenReady
                }
                return@switchSource
            }
            sameCopyRetries = 0
            recoveryScheduled = false
            if (!automatic) chosenSourceKey = pendingSourceKey
            start(reply!!, positionMs, playWhenReady)
            if (!automatic) setStatus("Switched to ${currentSourceLabel.ifBlank { "another copy" }}")
        }
    }

    private fun sourceAdvice(): String {
        val alts = alternatives.length()
        return if (alts > 0) "The hub listed $alts other copies; pick one in Quality."
        else "The hub listed no other copy."
    }

    // =========================================================================================
    // view construction
    // =========================================================================================

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // SurfaceView (the default surface) sits behind the window, so the overlay draws over the
        // picture without the texture/compositing cost a transparent WebView carried.
        playerView = PlayerView(this).apply {
            useController = false
            controllerAutoShow = false
            // PlayerView is a focusable FrameLayout. With the controller off it draws nothing when it
            // takes focus, so the D-pad would land on an invisible target and every chip looked dead.
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            player = this@PlayerActivity.player
        }
        root.addView(playerView, match())

        overlay = FrameLayout(this)
        root.addView(overlay, match())

        buildTopBar()
        buildBottomBar()
        buildCenter()

        overlay.visibility = View.INVISIBLE
        // The intro prompt is the one control that lives outside the chrome: it must stay visible while
        // the film is playing with the transport hidden.
        val skip = chip("Skip intro", compact = true) { skipIntro() }
        skip.visibility = View.GONE
        root.addView(skip, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = theme.gutter
            bottomMargin = theme.px(122f)
        })
        skipChip = skip
    }

    private fun buildTopBar() {
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.gutter, theme.px(26f), theme.gutter, theme.px(24f))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(theme.color("scrimInk", "#EB141414"), Color.TRANSPARENT),
            )
        }
        backButton = icon(GLYPH_BACK, "Back") { finishToDetail() }
        topBar.addView(backButton, wrap())

        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.px(24f), 0, 0, 0)
        }
        titleView = Views.text(app, "h3", 34f, theme.ink, displayTitle, bold = true, maxLines = 1)
        metaView = Views.text(app, "sub", 22f, theme.ink2, "", maxLines = 1)
        words.addView(titleView)
        words.addView(metaView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = theme.px(4f) })
        topBar.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        liveBadge = Views.text(app, "badge", 16f, Color.WHITE, "LIVE", bold = true, gravity = Gravity.CENTER)
        liveBadge.setPadding(theme.px(10f), theme.px(3f), theme.px(10f), theme.px(3f))
        liveBadge.background = Views.rounded(theme.color("live", "#E50914"), theme.radius("badge", 4f))
        liveBadge.visibility = View.GONE
        topBar.addView(liveBadge, wrap())

        statusView = Views.text(app, "meta", 18f, theme.ink2, "", maxLines = 1)
        statusView.setPadding(theme.px(20f), 0, 0, 0)
        topBar.addView(statusView, wrap())

        overlay.addView(topBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP })
        if (live) liveBadge.visibility = View.VISIBLE
    }

    private fun buildBottomBar() {
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.gutter, theme.px(40f), theme.gutter, theme.px(38f))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(theme.color("scrimInk", "#EB141414"), Color.TRANSPARENT),
            )
        }

        val lineRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        elapsed = Views.text(app, "meta", 18f, theme.ink2, "0:00", maxLines = 1)
        remaining = Views.text(app, "meta", 18f, theme.ink2, "", maxLines = 1, gravity = Gravity.END)
        seekLine = SeekLine(this)
        seekLine.isFocusable = true
        seekLine.isClickable = true
        seekLine.setOnClickListener { togglePlay() }
        Focus.attach(seekLine, seekLine, app, theme.radius("button", 6f), scale = false, elevate = false,
            onFocus = { focused -> showControlHint(if (focused) "Timeline · LEFT / RIGHT to seek" else null) })
        val lineLp = LinearLayout.LayoutParams(0, theme.px(30f), 1f)
        lineLp.marginStart = theme.px(18f)
        lineLp.marginEnd = theme.px(18f)
        lineRow.addView(elapsed, wrap())
        lineRow.addView(seekLine, lineLp)
        lineRow.addView(remaining, wrap())
        bottomBar.addView(lineRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        seekLine.visibility = View.GONE
        elapsed.visibility = View.GONE
        remaining.visibility = View.GONE

        buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playButton = icon(GLYPH_PLAY, "Play") { togglePlay() }
        rewindButton = icon(GLYPH_REWIND, "Back ${seekBackMs / 1000} seconds", hint = false,
            seconds = (seekBackMs / 1000).toInt()) { seekBy(-seekBackMs) }
        forwardButton = icon(GLYPH_FORWARD, "Forward ${seekForwardMs / 1000} seconds", hint = false,
            seconds = (seekForwardMs / 1000).toInt()) { seekBy(seekForwardMs) }
        audioButton = icon(GLYPH_AUDIO, "Audio", flat = true) { openMenu(MenuKind.AUDIO, audioButton) }
        subButton = icon(GLYPH_SUBS, "Subtitles", flat = true) { openMenu(MenuKind.SUBTITLES, subButton) }
        speedButton = icon(GLYPH_SPEED, "Speed", flat = true) { openMenu(MenuKind.SPEED, speedButton) }
        qualityButton = icon(GLYPH_QUALITY, "Quality", flat = true) { openMenu(MenuKind.QUALITY, qualityButton) }
        // A series gets a fifth affordance: the episode list, so a viewer mid-season can jump
        // anywhere without leaving the player (the Apple TV way). Movies and live channels don't.
        if (intent?.getStringExtra(EXTRA_TYPE).orEmpty() == "series") {
            episodesButton = icon(GLYPH_EPISODES, "Episodes", flat = true) {
                episodesButton?.let { openMenu(MenuKind.EPISODES, it) }
            }
        }

        buttonRow.addView(playButton, wrap().withStart(theme.px(12f)))
        buttonRow.addView(rewindButton, wrap().withStart(theme.px(10f)))
        buttonRow.addView(forwardButton, wrap().withStart(theme.px(10f)))
        buttonRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        // One translucent capsule for the settings icons, as on the reference remote UI: the four
        // affordances read as one group, and each still takes the ring on its own.
        val options = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Views.rounded(theme.color("btnGrey", "#1AFFFFFF"), theme.px(26f))
            setPadding(theme.px(6f), theme.px(4f), theme.px(6f), theme.px(4f))
        }
        options.addView(audioButton, wrap())
        options.addView(subButton, wrap().withStart(theme.px(4f)))
        options.addView(speedButton, wrap().withStart(theme.px(4f)))
        options.addView(qualityButton, wrap().withStart(theme.px(4f)))
        episodesButton?.let { options.addView(it, wrap().withStart(theme.px(4f))) }
        buttonRow.addView(options, wrap())

        // Stremio's remote-first courtesy: the icon under the ring says what it will do, in words,
        // so a viewer never has to guess a glyph. It sits inside the bar and disappears with it.
        controlHint = Views.text(app, "meta", 16f, theme.ink2, "", maxLines = 1,
            gravity = Gravity.START)
        controlHint.visibility = View.GONE
        bottomBar.addView(controlHint, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = theme.px(12f)
            bottomMargin = theme.px(2f)
        })

        bottomBar.addView(buttonRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = theme.px(6f) })

        overlay.addView(bottomBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM })

        barFocusable = listOf(
            backButton, playButton, rewindButton, forwardButton,
            audioButton, subButton, speedButton, qualityButton,
        ) + listOfNotNull(episodesButton) + listOf(seekLine)
        wireChromeFocus()
    }

    /**
     * The transport controls are visually separated by a large flexible spacer. Geometric focus
     * search therefore preferred the scrubber over the Audio/Episodes cluster on some TV panels.
     * Give the remote an intentional left/right route so every icon is reachable without a
     * diagonal hunt; hidden controls are omitted whenever transport state changes.
     */
    private fun wireChromeFocus() {
        val controls = listOfNotNull(
            playButton,
            rewindButton.takeIf { it.visibility == View.VISIBLE },
            forwardButton.takeIf { it.visibility == View.VISIBLE },
            audioButton,
            subButton,
            speedButton.takeIf { it.visibility == View.VISIBLE },
            qualityButton,
            episodesButton,
        )
        if (controls.isEmpty()) return
        controls.forEach { if (it.id == View.NO_ID) it.id = View.generateViewId() }
        controls.forEachIndexed { index, control ->
            control.nextFocusLeftId = controls[(index - 1).coerceAtLeast(0)].id
            control.nextFocusRightId = controls[(index + 1).coerceAtMost(controls.lastIndex)].id
        }
        if (seekLine.id == View.NO_ID) seekLine.id = View.generateViewId()
        // Down from the timeline returns to the transport rather than falling into an invisible
        // player surface. Left/right remains the expected seek gesture.
        seekLine.nextFocusDownId = controls.first().id
    }

    private fun buildCenter() {
        centerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        centerSpinner = ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(theme.ink)
        }
        centerBox.addView(centerSpinner, LinearLayout.LayoutParams(theme.px(56f), theme.px(56f)))
        centerText = Views.text(app, "sub", 24f, theme.ink, "", gravity = Gravity.CENTER, maxLines = 2)
        centerText.setPadding(theme.px(20f), theme.px(14f), theme.px(20f), 0)
        // A wrap_content column measured before the first message took its width from the spinner
        // (56 px) and clipped every status to one letter. Give the line a real column.
        centerBox.addView(centerText, LinearLayout.LayoutParams(
            theme.px(760f), ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        overlay.addView(centerBox, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })
    }

    /**
     * A flat icon button for the transport row. Icons are drawn (not font glyphs and not labels):
     * on a television the row is read at three metres, and a 20 px word is harder to hit than a
     * 24 px play/pause mark. The value a control currently holds lives in its menu and in its
     * content description.
     */
    private fun icon(glyph: Int, desc: String, flat: Boolean = false, hint: Boolean = true, seconds: Int = 0, action: () -> Unit): GlyphView {
        val v = GlyphView(this, glyph, seconds)
        v.contentDescription = desc
        if (!flat) {
            // Standalone transport buttons are soft pills; the menu group shares one wider pill.
            v.background = Views.rounded(theme.color("btnGrey", "#1AFFFFFF"), theme.px(23f))
        }
        v.minimumWidth = theme.px(if (flat) 50f else 56f)
        v.minimumHeight = theme.px(46f)
        Focus.attach(
            v, v, app, theme.px(if (flat) 20f else 23f),
            scale = false, elevate = false, onClick = action,
            // Read the live contentDescription: Play becomes Pause while the film runs.
            onFocus = { focused -> if (hint) showControlHint(if (focused) v.contentDescription?.toString() else null) },
        )
        return v
    }

    /** One quiet line above the transport naming the focused control; null keeps it hidden. */
    private fun showControlHint(text: String?) {
        if (!::controlHint.isInitialized) return
        controlHint.text = text.orEmpty()
        controlHint.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun chip(
        label: String,
        compact: Boolean = false,
        selected: Boolean = false,
        arrow: ((Int) -> Boolean)? = null,
        action: () -> Unit,
    ): TextView {
        val b = Views.text(
            app, "li", if (compact) 20f else 22f,
            if (selected) theme.color("panelValue", "#F2C33A") else theme.ink,
            label, bold = true,
            gravity = Gravity.CENTER,
        )
        b.setPadding(
            theme.px(if (compact) 18f else 22f), theme.px(10f),
            theme.px(if (compact) 18f else 22f), theme.px(10f),
        )
        b.background = Views.rounded(
            if (selected) theme.color("surface2", "#232323") else theme.color("btnGrey", "#1AFFFFFF"),
            theme.radius("button", 6f),
        )
        Focus.attach(
            b, b, app, theme.radius("button", 6f), scale = false, elevate = false,
            onClick = action, arrow = arrow,
        )
        return b
    }

    private fun match() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun wrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun LinearLayout.LayoutParams.withStart(px: Int): LinearLayout.LayoutParams {
        marginStart = px
        return this
    }

    // =========================================================================================
    // menus
    // =========================================================================================

    private enum class MenuKind { AUDIO, SUBTITLES, SPEED, QUALITY, EPISODES }

    private fun openMenu(kind: MenuKind, anchor: View) {
        // A failure card owns the screen until the viewer answers it. Opening a menu behind — or on
        // top of — that card leaves two panels stacked, which is what the last screenshot showed.
        if (errorShowing) return
        if (panelKind == kind) {
            closeMenu()
            return
        }
        closeMenu(restore = false)
        panelKind = kind
        panelAnchor = anchor
        cancelHide()
        panelRows = emptyList()
        rebuildMenu(kind, keepFocus = false)
        markBarFocusable(false)
    }

    private fun rebuildMenu(kind: MenuKind, keepFocus: Boolean = true) {
        val focused = if (keepFocus) currentFocus else null
        panel?.let { overlay.removeView(it) }  // replaced by the fresh sheet on the same frame
        panel = null
        panelRows = emptyList()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.space("panelPad", 28f), theme.space("panelPadV", 24f),
                theme.space("panelPad", 28f), theme.space("panelPadV", 24f))
            background = Views.rounded(
                theme.color("panelScrim", "#E6000000"),
                theme.radius("panel", 10f),
                theme.px(1f),
                theme.color("line", "#262626"),
            )
            elevation = theme.px(12f).toFloat()
        }
        val heading = when (kind) {
            MenuKind.AUDIO -> "Audio"
            MenuKind.SUBTITLES -> "Subtitles"
            MenuKind.SPEED -> "Speed"
            MenuKind.QUALITY -> "Quality" + qualityWord(currentSourceLabel).let { if (it.isBlank()) "" else " · $it" }
            MenuKind.EPISODES -> "Episodes"
        }
        box.addView(Views.text(app, "panelTitle", 26f, theme.ink, heading, bold = true),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = theme.px(12f) })

        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        when (kind) {
            MenuKind.AUDIO -> audioRows(rows)
            MenuKind.SUBTITLES -> subtitleRows(rows)
            MenuKind.SPEED -> speedRows(rows)
            MenuKind.QUALITY -> qualityRows(rows)
            MenuKind.EPISODES -> episodesRows(rows)
        }
        if (rows.childCount > 8) {
            val scroll = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                isFocusable = false
                isFocusableInTouchMode = false
            }
            scroll.addView(rows)
            box.addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                theme.px(if (kind == MenuKind.EPISODES) 620f else 560f)
            ))
        } else {
            box.addView(rows, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        // Episode rows carry a still and two lines of copy, so the sheet is wider than a track list.
        val width = theme.px(if (kind == MenuKind.EPISODES) 700f else 560f)
        val container = FrameLayout(this)
        container.addView(box, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
        val bottom = (bottomBar.height + theme.px(16f)).coerceAtLeast(theme.px(190f))
        overlay.addView(container, FrameLayout.LayoutParams(
            width, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = theme.gutter
            bottomMargin = bottom
        })
        panel = container
        Motion.sheetIn(container, theme)

        trapFocus()
        val preservedEpisode = if (kind == MenuKind.EPISODES) pendingEpisodeFocus else null
        val target = when {
            keepFocus && focused != null && panelRows.contains(focused) -> focused
            preservedEpisode != null -> nearestEpisodeRow(preservedEpisode)
            else -> panelRows.getOrNull(selectedRowIndex(kind)) ?: panelRows.firstOrNull()
        }
        if (kind == MenuKind.EPISODES) pendingEpisodeFocus = null
        target?.post { focusMenuTarget(target, container) }
    }

    /** First/last rows point at themselves so the ring cannot fall out of an open menu. */
    private fun trapFocus() {
        if (panelRows.isEmpty()) return
        for (r in panelRows) r.id = View.generateViewId()
        panelRows.first().nextFocusUpId = panelRows.first().id
        panelRows.last().nextFocusDownId = panelRows.last().id
    }

    private fun sourceKey(o: JSONObject): String =
        str(o, "label").ifBlank { str(o, "id") }.ifBlank { str(o, "provider") }

    /** Keep the best description of a copy the title has offered, without duplicating its label. */
    private fun rememberSource(o: JSONObject?) {
        if (o == null) return
        val key = sourceKey(o)
        if (key.isBlank()) return
        sourceCatalog[key] = JSONObject(o.toString())
    }

    /** Add every language the hub offered, keeping the best evidence for each one. */
    private fun mergeAudioOptions(fresh: JSONArray) {
        for (i in 0 until fresh.length()) {
            val o = fresh.optJSONObject(i) ?: continue
            val key = str(o, "language").lowercase()
            if (key.isBlank()) continue
            val had = audioCatalog[key]
            if (had == null || audioOptionScore(o) > audioOptionScore(had)) audioCatalog[key] = o
        }
    }

    /** A real track in the picture beats a claimed language; a confirmed donor beats a filename. */
    private fun audioOptionScore(o: JSONObject): Int =
        (if (str(o, "kind") == "track") 4 else 0) +
            (if (o.optBoolean("confirmed")) 2 else 0) +
            (if (str(o, "source").isNotBlank()) 1 else 0)


    /**
     * A panel is attached after its rows are built. Requesting focus one frame too early makes
     * Android focus the first season chip instead of the episode row; retry the exact target until
     * the sheet owns it, while ignoring an older sheet that has already been replaced.
     */
    private fun focusMenuTarget(target: View, owner: View, tries: Int = 0) {
        if (panel !== owner) return
        if (target.isShown && target.requestFocus()) return
        if (tries < 8) target.postDelayed({ focusMenuTarget(target, owner, tries + 1) }, 80L)
    }

    private fun audioRows(parent: LinearLayout) {
        if (audioOptions.length() > 0) {
            // "Default" told the viewer nothing. When the hub has replaced the picture's audio, the
            // way back is the explicit "Original audio" row below; otherwise Auto is the honest name
            // for "let the hub pick the best track for this profile's languages".
            val mixActive = mixHint != null && !mixDisabled
            if (!mixActive) parent.addView(menuRow(
                "Auto", "best match for your languages", !audioChosen,
            ) { chooseAudio(null) })
            for (i in 0 until audioOptions.length()) {
                val o = audioOptions.optJSONObject(i) ?: continue
                val kind = str(o, "kind")
                val lang = str(o, "language")
                if (lang.isBlank()) continue
                val name = str(o, "languageName").ifBlank { langName(lang) }
                val source = str(o, "source")
                val mixed = kind == "mix"
                val selected = if (mixed) {
                    !mixDisabled && str(mixHint, "language").equals(lang, true)
                } else {
                    selectedAudioId != null && selectedAudioId == str(o, "id")
                }
                parent.addView(menuRow(
                    name,
                    if (mixed) "mixed from " + source.ifBlank { "another copy" } else "in this copy",
                    selected,
                ) {
                    if (mixed) reloadAudio(mix = true, language = lang, from = source)
                    else chooseAudio(str(o, "id"))
                })
            }
            addMixToggle(parent)
            return
        }
        if (audioTracks.length() == 0) {
            val actual = actualTracks(C.TRACK_TYPE_AUDIO, "a")
            parent.addView(menuRow(
                "Auto", "the copy's default track", !audioChosen,
            ) { chooseAudio(null) })
            for (t in actual) {
                parent.addView(menuRow(t.label, "", audioChosen && t.selected) { chooseAudio(t.id) })
            }
            if (actual.isEmpty()) parent.addView(noteRow("No audio list from this copy"))
            addMixToggle(parent)
            return
        }
        parent.addView(menuRow(
            "Auto", "best match for your languages", !audioChosen,
        ) { chooseAudio(null) })
        for (i in 0 until audioTracks.length()) {
            val t = audioTracks.optJSONObject(i) ?: continue
            val id = str(t, "id")
            if (id.isBlank()) continue
            val mixed = str(t, "kind") == "audio-mix"
            // A mixed row is named by its language alone; its donor's label is the note, not a second
            // copy of the same words ("Hindi · Test Hindi audio · Hindi" truncated at 560 px).
            val label = if (mixed) langName(str(t, "language")) else trackWords(t)
            val note = if (mixed) "mixed from " + str(answer?.optJSONObject("audioMix"), "source") else ""
            parent.addView(menuRow(label, note, audioChosen && selectedAudioId == id) { chooseAudio(id) })
        }
        addMixToggle(parent)
    }

    /**
     * The hub may have replaced the picture's audio with the household's language. A viewer who wants
     * the original needs a way back, and a viewer who turned it off needs a way in again; both are the
     * same round-trip to /play with `audioMix` on or off at the current playhead.
     */
    private fun addMixToggle(parent: LinearLayout) {
        val mix = mixHint ?: return
        val name = str(mix, "languageName").ifBlank { langName(str(mix, "language")) }
        if (mixDisabled) {
            parent.addView(menuRow(
                "$name audio", "mixed from " + str(mix, "source"), false,
            ) { reloadAudio(mix = true) })
        } else {
            parent.addView(menuRow(
                "Original audio", "without the $name mix", false,
            ) { reloadAudio(mix = false) })
        }
    }

    private fun reloadAudio(mix: Boolean, language: String? = null, from: String? = null) {
        val spec = spec ?: return
        val pos = player?.let { filmPosition(it) } ?: 0L
        mixDisabled = !mix
        audioChosen = false
        selectedAudioId = null
        pendingAudio = null
        audioApplied = false
        closeMenu()
        showCenter(
            if (mix) "Loading " + (language?.let { langName(it) } ?: "mixed") + " audio…"
            else "Loading the original audio…",
            true,
        )
        // Keep the copy the viewer is already watching: a language change must not re-open the whole
        // title and land on a different provider's file. The hub mixes the requested language into
        // that same picture; `from` names the donor copy when the menu already knows it.
        val picked = answer?.optJSONObject("picked")
        val source = picked?.let {
            JSONObject()
                .put("id", str(it, "id"))
                .put("label", str(it, "label"))
                .put("provider", str(it, "provider"))
        }
        val extra = JSONObject()
        if (mix) {
            val body = JSONObject().put("language", language ?: "")
            if (!from.isNullOrBlank()) body.put("source", from)
            // A menu choice is a request, not a guess: even when the picture's filename claims the
            // language, mix the donor the viewer picked. An unmeasured claim is not proof enough.
            if (!language.isNullOrBlank()) body.put("force", true)
            extra.put("audioMix", body)
            // Ask for that same language as the selected track when the picture already carries it;
            // otherwise the hub would keep its profile-first track after doing the mix.
            if (!language.isNullOrBlank()) extra.put("audio", language)
        } else {
            extra.put("audioMix", JSONObject().put("enabled", false))
        }
        resolvePlay(spec, pos, WARM_TRIES, source, extra)
    }

    private fun subtitleRows(parent: LinearLayout) {
        if (timelineOffsetMs > 0L) {
            parent.addView(noteRow("Subtitles are off on a resumed ladder"))
            return
        }
        if (embeddedSubs.length() == 0 && hubSubs.length() == 0) {
            val actual = actualTracks(C.TRACK_TYPE_TEXT, "s")
            parent.addView(menuRow(
                "Off", "", selectedSubId == null && actual.none { it.selected },
            ) { chooseSub(null) })
            for (t in actual) {
                parent.addView(menuRow(t.label, "in this copy", t.selected) { chooseSub(t.id) })
            }
            if (actual.isEmpty()) parent.addView(noteRow("No subtitles for this copy"))
            return
        }
        parent.addView(menuRow("Off", "", selectedSubId == null) { chooseSub(null) })
        for (i in 0 until embeddedSubs.length()) {
            val t = embeddedSubs.optJSONObject(i) ?: continue
            val id = str(t, "id")
            if (id.isBlank()) continue
            parent.addView(menuRow(trackWords(t), "in this copy", selectedSubId == id) { chooseSub(id) })
        }
        // Six files in one language are six identical rows unless the list numbers them.
        val totals = HashMap<String, Int>()
        for (i in 0 until hubSubs.length()) {
            val s = hubSubs.optJSONObject(i) ?: continue
            val lang = str(s, "lang").ifBlank { str(s, "language") }
            totals[lang] = (totals[lang] ?: 0) + 1
        }
        val seen = HashMap<String, Int>()
        for (i in 0 until hubSubs.length()) {
            val s = hubSubs.optJSONObject(i) ?: continue
            val id = "hub:$i"
            val lang = str(s, "lang").ifBlank { str(s, "language") }
            val nth = (seen[lang] ?: 0) + 1
            seen[lang] = nth
            val base = str(s, "label").ifBlank { str(s, "name") }.ifBlank { langName(lang) }
            val label = if ((totals[lang] ?: 0) > 1) "$base $nth" else base
            parent.addView(menuRow(label, "subtitle file", selectedSubId == id) { chooseSub(id) })
        }
    }

    private data class ActualTrack(val id: String, val label: String, val selected: Boolean)

    /**
     * A standalone hub answers `/app/v1/play` without track metadata because the file itself is the
     * catalogue. When the answer lists nothing, the container's own groups still make a working menu.
     */
    private fun actualTracks(type: Int, prefix: String): List<ActualTrack> {
        val groups = player?.currentTracks?.groups?.filter { it.type == type } ?: return emptyList()
        val out = ArrayList<ActualTrack>()
        for ((i, g) in groups.withIndex()) {
            if (g.length == 0) continue
            val f = g.getTrackFormat(0)
            val head = f.label ?: langName(f.language ?: "")
            val codec = codecName(f.codecs ?: f.sampleMimeType ?: "")
            out.add(
                ActualTrack(
                    id = "$prefix:${i + 1}",
                    label = listOf(head, codec).filter { it.isNotBlank() }.joinToString(" · "),
                    selected = g.isTrackSelected(0),
                )
            )
        }
        return out
    }

    private fun speedRows(parent: LinearLayout) {
        if (live) {
            parent.addView(noteRow("Live TV plays at 1×"))
            return
        }
        for (v in SPEEDS) {
            parent.addView(menuRow(formatSpeed(v), "", kotlin.math.abs(speed - v) < 0.01f) {
                setSpeed(v)
            })
        }
    }

    private fun qualityRows(parent: LinearLayout) {
        // A real ladder is one copy with several rungs. Choosing a rung must not ask the hub for
        // another copy: that is how a "quality" change lands on a different cut. When the answer is
        // a ladder, the top rows are its own rungs and the copy list moves below a heading.
        val rungs = ladderRungs()
        val currentKey = answer?.optJSONObject("picked")?.let { sourceKey(it) }.orEmpty()
        val others = sourceCatalog.values.toList()
        val automatic = chosenSourceKey.isEmpty()
        if (rungs.isNotEmpty()) {
            val current = if (forcedRungHeight > 0) forcedRungHeight else selectedVideoHeight()
            parent.addView(menuRow("Auto", "follow the link", forcedRungHeight == 0) {
                chooseRung(0)
            })
            for (h in rungs) {
                parent.addView(menuRow("${h}p", if (h == rungs.first()) "top rung" else "lighter", h == current && forcedRungHeight > 0) {
                    chooseRung(h)
                })
            }
            if (others.isNotEmpty()) parent.addView(noteRow("Other copies"))
        } else {
            parent.addView(menuRow("Auto", "best copy the hub can feed", automatic) {
                switchTo(null)
            })
        }
        if (rungs.isNotEmpty() && others.isNotEmpty()) {
            parent.addView(menuRow("Best copy", "let the hub choose again", automatic) { switchTo(null) })
        }
        for (src in others) {
            val key = sourceKey(src)
            val picked = key.isNotBlank() && key == chosenSourceKey
            val note = sourceNote(src) + if (key == currentKey) " · playing" else ""
            parent.addView(menuRow(sourceWords(src), note, picked) {
                switchTo(src)
            })
        }
        if (others.isEmpty() && rungs.isEmpty()) {
            parent.addView(noteRow("The hub listed no other copies"))
        }
    }

    /** The rungs the hub says this ladder has, highest first, as pixels. */
    private fun ladderRungs(): List<Int> {
        val arr = answer?.optJSONObject("ladder")?.optJSONArray("variants") ?: return emptyList()
        val out = ArrayList<Int>(arr.length())
        for (i in 0 until arr.length()) {
            val m = Regex("(\\d{3,4})").find(arr.optString(i)) ?: continue
            val h = m.groupValues[1].toIntOrNull() ?: continue
            if (h > 0 && !out.contains(h)) out.add(h)
        }
        return out.sortedDescending()
    }

    /**
     * Pin one rung of the current ladder, or return to adaptive selection with [height] zero. The
     * ladder stays the same copy and the same timeline, so this cannot show a different film.
     */
    private fun chooseRung(height: Int) {
        val p = player ?: return
        if (height <= 0) {
            forcedRungHeight = 0
            startRungPending = false
            startRungLocked = false
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .build()
        } else {
            var group: Tracks.Group? = null
            var index = -1
            for (g in p.currentTracks.groups) {
                if (g.type != C.TRACK_TYPE_VIDEO) continue
                for (i in 0 until g.length) {
                    if (!g.isTrackSupported(i)) continue
                    if (g.getTrackFormat(i).height == height) {
                        group = g
                        index = i
                        break
                    }
                }
                if (group != null) break
            }
            if (group == null || index < 0) {
                showCenter("This copy has no ${height}p rung", false)
                return
            }
            forcedRungHeight = height
            startRungPending = false
            startRungLocked = false
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(index)))
                .build()
        }
        paintChips()
        closeMenu()
    }

    /** Height of the video track the player is showing, or zero before the first tracks arrive. */
    private fun selectedVideoHeight(): Int {
        val p = player ?: return 0
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until g.length) {
                if (g.isTrackSelected(i)) return g.getTrackFormat(i).height
            }
        }
        return 0
    }

    /** The season currently visible in the Episodes menu, as a plain list of episode objects. */
    private fun episodesForSeason(): List<JSONObject> {
        val eps = episodesList ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until eps.length()) {
            val ep = eps.optJSONObject(i) ?: continue
            if (ep.optInt("season", 1) == epSeason) out.add(ep)
        }
        return out
    }

    /** Sorted, distinct seasons available to the player-side Episodes sheet. */
    private fun episodeSeasons(): List<Int> {
        val eps = episodesList ?: return emptyList()
        val seasons = ArrayList<Int>()
        for (i in 0 until eps.length()) {
            val season = eps.optJSONObject(i)?.optInt("season", 1) ?: continue
            if (season !in seasons) seasons.add(season)
        }
        seasons.sort()
        return seasons
    }

    /** Find the episode row currently carrying D-pad focus, even if a child later becomes focusable. */
    private fun focusedEpisodeRow(): EpisodeRowKey? {
        var view: View? = currentFocus
        while (view != null) {
            (view.tag as? EpisodeRowKey)?.let { return it }
            view = view.parent as? View
        }
        return null
    }

    /**
     * Find the closest row in a freshly built season. A request for E10 in a shorter season lands
     * on its last real episode instead of falling back to a non-interactive header.
     */
    private fun nearestEpisodeRow(preferred: EpisodeRowKey): View? {
        var chosen: View? = null
        var closest = Int.MAX_VALUE
        for (row in panelRows) {
            val key = row.tag as? EpisodeRowKey ?: continue
            if (key.season != preferred.season) continue
            val distance = kotlin.math.abs(key.episode - preferred.episode)
            if (chosen == null || distance < closest) {
                chosen = row
                closest = distance
            }
        }
        return chosen
    }

    /** Rebuild the sheet for [season] and retain the viewer's episode position when possible. */
    private fun showEpisodeSeason(season: Int, preferredEpisode: Int? = focusedEpisodeRow()?.episode) {
        if (epSeason == season || panelKind != MenuKind.EPISODES) return
        pendingEpisodeFocus = EpisodeRowKey(
            season,
            preferredEpisode ?: spec?.episode?.takeIf { it > 0 } ?: 1,
        )
        epSeason = season
        rebuildMenu(MenuKind.EPISODES, keepFocus = false)
    }

    /**
     * LEFT/RIGHT belongs to the season rail while the episode list is open. Android's focus search
     * otherwise only sees the vertical rows below it, which made the rail look interactive but
     * impossible to use from a remote. Cycling also makes both ends of the rail useful on a TV.
     */
    private fun moveEpisodeSeason(delta: Int): Boolean {
        if (panelKind != MenuKind.EPISODES) return false
        val seasons = episodeSeasons()
        if (seasons.size < 2) return false
        val playingIndex = seasons.indexOf(spec?.season ?: 0)
        val currentIndex = seasons.indexOf(epSeason).takeIf { it >= 0 }
            ?: playingIndex.takeIf { it >= 0 }
            ?: 0
        val nextIndex = (currentIndex + delta + seasons.size) % seasons.size
        val preferred = focusedEpisodeRow()?.episode ?: spec?.episode?.takeIf { it > 0 }
        showEpisodeSeason(seasons[nextIndex], preferred)
        return true
    }

    /**
     * The episode jump list — Apple TV's core series affordance, drawn inside the player so a viewer
     * never has to leave playback to change episode. The list is read from the hub's meta record on
     * first open; while it arrives the panel says so instead of showing an empty box.
     */
    private fun episodesRows(parent: LinearLayout) {
        val s = spec ?: return
        val eps = episodesList
        if (eps == null) {
            parent.addView(noteRow("Reading the episode list…"))
            if (!episodesFetching) {
                episodesFetching = true
                api.meta(s.identity, profileId) { record, _ ->
                    episodesFetching = false
                    if (isFinishing) return@meta
                    val list = record?.optJSONArray("episodes")
                    if (list != null && list.length() > 0) episodesList = list
                    // A cold hub can time out on a big series record (eight seasons of Game of
                    // Thrones is a heavy read). Caching that miss as "no episodes" would strand the
                    // menu on an empty list forever — leave it unset so the next open asks again.
                    if (panelKind == MenuKind.EPISODES) rebuildMenu(MenuKind.EPISODES)
                }
            }
            return
        }
        val seasons = episodeSeasons()
        if (epSeason == 0)
            epSeason = if (seasons.contains(s.season)) s.season else seasons.firstOrNull() ?: 0
        if (seasons.size > 1) {
            val chips = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            for (se in seasons) {
                chips.addView(chip(
                    "Season $se",
                    compact = true,
                    selected = se == epSeason,
                    arrow = { code ->
                        when (code) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> moveEpisodeSeason(-1)
                            KeyEvent.KEYCODE_DPAD_RIGHT -> moveEpisodeSeason(1)
                            else -> false
                        }
                    },
                ) {
                    showEpisodeSeason(se, spec?.episode?.takeIf { it > 0 })
                }, wrap().withStart(if (chips.childCount == 0) 0 else theme.px(8f)))
            }
            parent.addView(chips, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = theme.px(10f) })
        }
        var shown = 0
        for (i in 0 until eps.length()) {
            val ep = eps.optJSONObject(i) ?: continue
            if (ep.optInt("season", 1) != epSeason) continue
            if (shown >= 40) break
            shown++
            val se = ep.optInt("season", 1)
            val number = ep.optInt("episode", shown)
            val here = se == s.season && number == s.episode
            parent.addView(episodeRow(ep, number, here) {
                if (here) {
                    closeMenu()
                    return@episodeRow
                }
                closeMenu(restore = false)
                open(PlaySpec(s.identity, s.type, se, number,
                    str(ep, "title").ifBlank { s.title }), 0L)
            })
        }
        if (shown == 0) parent.addView(noteRow("The hub listed no episodes for this season"))
    }

    /**
     * One episode row of the Episodes menu: the episode's own still on the left, number and title
     * bold beside it, the synopsis in one quiet line below. A plain text row reads as a settings
     * option; the still makes it read as a list of things to watch, which is what it is.
     */
    private fun episodeRow(ep: JSONObject, number: Int, here: Boolean, action: () -> Unit): LinearLayout {
        val title = str(ep, "title")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.px(10f), theme.px(9f), theme.px(14f), theme.px(9f))
            background = Views.rounded(
                if (here) theme.color("surface2", "#232323") else Color.TRANSPARENT,
                theme.radius("listItem", 6f),
            )
            isFocusable = true
            isClickable = true
            tag = EpisodeRowKey(ep.optInt("season", 1), number)
            setOnClickListener { action() }
        }
        val radius = theme.radius("card", 6f).toFloat()
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(theme.surface2)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radius)
                }
            }
        }
        row.addView(thumb, LinearLayout.LayoutParams(theme.px(150f), theme.px(84f)))
        BrowseImages.load(thumb, str(ep, "thumb"), theme.px(150f))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.px(14f), 0, 0, 0)
        }
        copy.addView(Views.text(app, "panelRow", 22f,
            if (here) theme.color("panelValue", "#F2C33A") else theme.ink,
            (if (here) "✓  " else "") + "E%02d".format(number) +
                if (title.isNotBlank()) " · $title" else "",
            bold = true, maxLines = 1))
        val synopsis = str(ep, "synopsis")
        if (synopsis.isNotBlank()) {
            copy.addView(Views.text(app, "panelNote", 17f, theme.ink3, synopsis, maxLines = 1),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = theme.px(5f) })
        }
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        Focus.attach(
            row,
            row,
            app,
            theme.radius("listItem", 6f),
            // Round 5: a focused menu row must lift, not just ring. The pack's rowScale token
            // decides; packs that never set one keep the old static row.
            scale = theme.focusRowScale > 1f, scaleTo = theme.focusRowScale,
            control = theme.focusRowScale > 1f,
            elevate = false,
            // Activity-level routing handles the entire sheet, but keep the same rule on the
            // focused row as a boundary fallback for TV firmware that dispatches D-pad keys
            // straight to its focused child.
            arrow = { code ->
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> moveEpisodeSeason(-1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> moveEpisodeSeason(1)
                    else -> false
                }
            },
        )
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(106f)
        ).apply { topMargin = theme.space("panelRowGap", 10f) }
        panelRows = panelRows + row
        return row
    }

    private fun selectedRowIndex(kind: MenuKind): Int = when (kind) {
        MenuKind.AUDIO -> when {
            selectedAudioId == null -> 0
            audioTracks.length() == 0 -> 1 + trackOrdinal(selectedAudioId)
            else -> 1 + indexOfTrack(audioTracks, selectedAudioId)
        }
        MenuKind.SUBTITLES -> when {
            selectedSubId == null -> 0
            embeddedSubs.length() == 0 && hubSubs.length() == 0 -> 1 + trackOrdinal(selectedSubId)
            else -> {
                val emb = indexOfTrack(embeddedSubs, selectedSubId)
                if (emb >= 0) 1 + emb else 1 + embeddedSubs.length() + subsIndex(selectedSubId)
            }
        }
        MenuKind.SPEED -> SPEEDS.indexOfFirst { kotlin.math.abs(speed - it) < 0.01f }.coerceAtLeast(0)
        MenuKind.QUALITY -> 0
        MenuKind.EPISODES -> {
            val s = spec
            val eps = episodesForSeason()
            if (s == null) 0 else eps.indexOfFirst {
                it.optInt("season", 1) == s.season && it.optInt("episode", 0) == s.episode
            }.coerceAtLeast(0)
        }
    }

    /** `s:2` / `a:3` → 1, the row's offset after the leading Default/Off row. */
    private fun trackOrdinal(id: String?): Int =
        ((id?.substringAfter(':')?.toIntOrNull() ?: 1) - 1).coerceAtLeast(0)

    private fun indexOfTrack(arr: JSONArray, id: String?): Int {
        val pos = positionOf(arr, id)
        return if (pos >= 0) pos else 0
    }

    /** -1 when the id is not in this list, so a positional miss can fall back to id matching. */
    private fun positionOf(arr: JSONArray?, id: String?): Int {
        if (arr == null || id == null) return -1
        for (i in 0 until arr.length()) if (str(arr.optJSONObject(i), "id") == id) return i
        return -1
    }

    private fun subsIndex(id: String?): Int {
        if (id == null || !id.startsWith("hub:")) return 0
        return id.removePrefix("hub:").toIntOrNull() ?: 0
    }

    private fun menuRow(label: String, value: String, selected: Boolean, action: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.px(16f), 0, theme.px(16f), 0)
            background = Views.rounded(
                if (selected) theme.color("surface2", "#232323") else Color.TRANSPARENT,
                theme.radius("listItem", 6f),
            )
            isFocusable = true
            isClickable = true
            setOnClickListener { action() }
        }
        val tick = if (selected) "✓" else " "
        val l = Views.text(
            app, "panelRow", 23f, if (selected) theme.color("panelValue", "#F2C33A") else theme.ink,
            "$tick  $label", bold = selected, maxLines = 1,
        )
        row.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value.isNotBlank()) {
            row.addView(Views.text(app, "panelNote", 18f, theme.ink3, value, maxLines = 1, gravity = Gravity.END))
        }
        Focus.attach(row, row, app, theme.radius("listItem", 6f),
            scale = theme.focusRowScale > 1f, scaleTo = theme.focusRowScale,
            control = theme.focusRowScale > 1f,
            elevate = false)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(68f)
        ).apply { topMargin = theme.space("panelRowGap", 10f) }
        row.layoutParams = lp
        panelRows = panelRows + row
        return row
    }

    private fun noteRow(text: String): View {
        val v = Views.text(app, "panelNote", 18f, theme.ink3, text, maxLines = 2)
        v.setPadding(theme.px(16f), theme.px(12f), theme.px(16f), theme.px(12f))
        return v
    }

    private fun closeMenu(restore: Boolean = true) {
        val had = panel != null
        panel?.let { p ->
            // A menu leaves the way chrome does: a quick fade, never a hard pop.
            Motion.outAlpha(p, theme,
                (theme.token("anim", "cardFadeInMs", 220.0).toLong() * 2 / 3).coerceIn(60L, 400L)) {
                if (p.parent === overlay) overlay.removeView(p)
            }
        }
        panel = null
        panelKind = null
        panelRows = emptyList()
        markBarFocusable(true)
        if (had && restore) {
            val a = panelAnchor
            panelAnchor = null
            a?.post { if (a.isShown) a.requestFocus() }
        }
        panelAnchor = null
        if (had) armHide()
    }

    private fun markBarFocusable(on: Boolean) {
        for (v in barFocusable) {
            if (v === seekLine && live) continue
            v.isFocusable = on
            if (!on) v.clearFocus()
        }
    }

    private fun setSpeed(v: Float) {
        speed = v
        player?.setPlaybackSpeed(v)
        paintChips()
        closeMenu()
        setStatus("Speed ${formatSpeed(v)}")
    }

    private fun switchTo(src: JSONObject?) {
        val spec = spec ?: return
        val pos = player?.let { filmPosition(it) } ?: 0L
        val playing = player?.playWhenReady ?: true
        pendingSourceKey = src?.let { sourceKey(it) } ?: ""
        closeMenu()
        val exclude = tried.toMutableSet()
        val source = src?.let {
            JSONObject()
                .put("id", str(it, "id"))
                .put("label", str(it, "label"))
                .put("provider", str(it, "provider"))
        }
        showCenter("Switching copy…", true)
        askSwitch(
            bodyFor(spec, pos, source, exclude), pos,
            playWhenReady = playing, warmTries = 5, automatic = false,
        )
    }

    // =========================================================================================
    // playback controls
    // =========================================================================================

    private fun togglePlay() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(0)
            p.playWhenReady = true
            hideNextCard()
            hideUndo()
        } else if (p.isPlaying) {
            p.pause()
            saveProgress(force = true)
        } else {
            p.playWhenReady = true
        }
        updatePlayLabel()
        armHide()
    }

    private fun seekBy(deltaMs: Long) {
        if (live) return
        val p = player ?: return
        val lower = timelineOffsetMs
        val upper = if (durationMs > 0) maxOf(durationMs, lower) else Long.MAX_VALUE
        val target = (filmPosition(p) + deltaMs).coerceIn(lower, upper)
        p.seekTo(mediaPosition(target))
        updatePosition()
        armHide()
    }

    /** The viewer's playhead in film time, including the start of a resume ladder. */
    private fun filmPosition(p: Player): Long = (p.currentPosition + timelineOffsetMs).coerceAtLeast(0L)

    /** The same moment in the media's own timeline, which starts at zero for a ladder. */
    private fun mediaPosition(filmMs: Long): Long = (filmMs - timelineOffsetMs).coerceAtLeast(0L)

    /** Full runtime, not just the part after a resume offset. */
    private fun fullDuration(p: Player): Long {
        val media = if (p.duration > 0) p.duration + timelineOffsetMs else 0L
        return when {
            // An EVENT playlist grows while the transcoder works. Its own duration is relative to the
            // resume point, so keep the larger of that and the film length the hub stated.
            timelineOffsetMs > 0 -> maxOf(durationMs, media)
            durationMs > 0 -> durationMs
            else -> media
        }
    }

    private fun updatePosition() {
        val p = player ?: return
        val dur = fullDuration(p)
        val pos = filmPosition(p)
        maxPosition = maxOf(maxPosition, pos)
        if (!live && dur > 0) {
            seekLine.fraction = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            seekLine.buffered = ((p.bufferedPosition + timelineOffsetMs).toFloat() / dur.toFloat())
                .coerceIn(0f, 1f)
            elapsed.text = clock(pos)
            remaining.text = "-" + clock((dur - pos).coerceAtLeast(0L))
        }
        seekLine.invalidate()
    }

    private fun updatePlayLabel() {
        val p = player ?: return
        playButton.playing = p.isPlaying
        playButton.contentDescription = when {
            p.playbackState == Player.STATE_ENDED -> "Replay"
            p.isPlaying -> "Pause"
            else -> "Play"
        }
    }

    private fun updateHeader() {
        titleView.text = displayTitle
        val bits = ArrayList<String>()
        val s = spec
        if (s != null && s.season > 0 && s.episode > 0) {
            bits.add("S%02dE%02d".format(s.season, s.episode))
        }
        if (live) {
            val channel = liveChannels.firstOrNull { it.identity == s?.identity }
            val number = channel?.live?.number ?: 0
            if (number > 0) bits.add(0, "CH $number")
            // What the guide says is on right now. The hub only publishes this when the owner set
            // settings.live.xmltv, so on a guide-less hub the header reads exactly as it used to.
            val now = channel?.live?.nowProgramme
                ?: answer?.optJSONObject("live")?.optJSONObject("now")?.let { hubProgramme(it) }
            if (now != null && now.title.isNotBlank()) {
                val left = if (now.minsLeft > 0) " · ${now.minsLeft} min left" else ""
                bits.add("Now: ${now.title}$left")
            }
        }
        val epTitle = answer?.optJSONObject("episode")?.let { str(it, "title") }.orEmpty()
        if (epTitle.isNotBlank()) bits.add(epTitle)
        val maturity = intent?.getStringExtra(EXTRA_MATURITY).orEmpty()
        if (maturity.isNotBlank()) bits.add(maturity)
        val advisories = intent?.getStringArrayListExtra(EXTRA_ADVISORIES)?.filter { it.isNotBlank() }
        if (!advisories.isNullOrEmpty()) bits.add(advisories.joinToString(" · "))
        val mix = answer?.optJSONObject("audioMix")
        if (mix != null && !mixDisabled) {
            val lang = str(mix, "languageName").ifBlank { langName(str(mix, "language")) }
            if (lang.isNotBlank()) bits.add("$lang audio")
        }
        metaView.text = bits.joinToString("  ·  ")
        liveBadge.visibility = if (live) View.VISIBLE else View.GONE
    }

    /** Raw hub JSON (`live.now`) -> the few fields the header prints, for paths that hold the answer, not the Item. */
    private fun hubProgramme(o: JSONObject): Programme = Programme(
        title = o.optString("title", ""),
        startMs = o.optLong("start", 0L),
        stopMs = o.optLong("stop", 0L),
        minsLeft = o.optInt("minsLeft", 0),
        progress = o.optInt("progress", 0),
        category = o.optString("category", ""),
    )

    private fun paintChips() {
        audioButton.contentDescription = "Audio · " + audioValue()
        subButton.contentDescription = "Subtitles · " + subValue()
        speedButton.contentDescription = "Speed · " + formatSpeed(speed)
        val ladder = answer?.optJSONObject("ladder")
        val qualityValue = when {
            forcedRungHeight > 0 -> "${forcedRungHeight}p"
            ladder != null -> selectedVideoHeight().let { if (it > 0) "${it}p · Auto" else "Auto" }
            else -> currentSourceLabel.ifBlank { "Auto" }
        }
        qualityButton.contentDescription = "Quality · " + qualityValue
        // A tiny dot is the only "current value" a flat icon can carry without becoming a label:
        // audio when a specific track is chosen, CC when subtitles are on, speed when it is not 1×.
        audioButton.badge = audioChosen || (mixHint != null && !mixDisabled)
        subButton.badge = selectedSubId != null
        speedButton.badge = kotlin.math.abs(speed - 1f) > 0.01f
        updatePlayLabel()
    }

    /** A live feed has no position and no rate; a film gets the line and the transport back. */
    private fun paintTransport(ready: Boolean) {
        val show = ready && !live
        seekLine.visibility = if (show) View.VISIBLE else View.GONE
        elapsed.visibility = if (show) View.VISIBLE else View.GONE
        remaining.visibility = if (show) View.VISIBLE else View.GONE
        speedButton.visibility = if (show) View.VISIBLE else View.GONE
        rewindButton.visibility = if (show) View.VISIBLE else View.GONE
        forwardButton.visibility = if (show) View.VISIBLE else View.GONE
        if (::qualityButton.isInitialized) wireChromeFocus()
    }

    private fun audioValue(): String {
        val id = selectedAudioId ?: return "Auto"
        for (i in 0 until audioTracks.length()) {
            val t = audioTracks.optJSONObject(i) ?: continue
            if (str(t, "id") == id) return langName(str(t, "language"))
        }
        return actualTracks(C.TRACK_TYPE_AUDIO, "a").firstOrNull { it.id == id }?.label ?: "Track"
    }

    private fun subValue(): String {
        val id = selectedSubId ?: return "Off"
        for (i in 0 until embeddedSubs.length()) {
            val t = embeddedSubs.optJSONObject(i) ?: continue
            if (str(t, "id") == id) return langName(str(t, "language"))
        }
        if (id.startsWith("hub:")) {
            val i = id.removePrefix("hub:").toIntOrNull() ?: return "On"
            val s = hubSubs.optJSONObject(i)
            return langName(str(s, "lang").ifBlank { str(s, "language") })
        }
        return actualTracks(C.TRACK_TYPE_TEXT, "s").firstOrNull { it.id == id }?.label ?: "On"
    }

    // =========================================================================================
    // chrome state
    // =========================================================================================

    private fun controlsVisible(): Boolean = overlay.visibility == View.VISIBLE

    /** Chrome comes back by fading in — never a hard visibility pop over the film. */
    private fun revealChrome() {
        overlay.visibility = View.VISIBLE
        Motion.inAlpha(overlay, theme,
            theme.token("anim", "navFadeMs", 180.0).toLong().coerceIn(60L, 400L))
    }

    private fun showChrome(focus: Boolean) {
        revealChrome()
        armHide()
        if (!focus) return
        val target = when {
            errorShowing -> errorRetry
            nextShowing -> nextPlayButton
            undoShowing -> undoButton
            else -> playButton
        }
        focusSoon(target)
    }

    /**
     * Focus is requested from a post because the view can be asked for it before the window has
     * attached (right after `setContentView`). A single post sometimes still lands before the
     * hierarchy is ready, which left the chrome on screen with no ring; retry a few frames.
     */
    private fun focusSoon(target: View?, tries: Int = 0) {
        if (target == null) return
        target.post {
            if (target.isShown && target.requestFocus()) return@post
            if (tries < 6) target.postDelayed({ focusSoon(target, tries + 1) }, 150L)
        }
    }

    private fun armHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, controlsHideMs.toLong())
    }

    private fun cancelHide() {
        handler.removeCallbacks(hideRunnable)
    }

    /**
     * Netflix never drops the chrome out from under a ring that is working in a menu. A menu, an
     * error card and the next-episode countdown all pin it; the plain transport bar fades once the
     * film is playing, and the ring is moved out first so nothing is hidden while focused.
     */
    private fun hideChrome() {
        if (panel != null || errorShowing || nextShowing || undoShowing) {
            armHide()
            return
        }
        val p = player
        if (p == null || !p.isPlaying) {
            armHide()
            return
        }
        currentFocus?.clearFocus()
        Motion.outAlpha(overlay, theme,
            (theme.token("anim", "navFadeMs", 180.0).toLong() * 2 / 3).coerceIn(60L, 400L)) {
            if (overlay.alpha < 0.05f) overlay.visibility = View.INVISIBLE
        }
    }

    /**
     * An automatic transition's message. Over a running picture it is a line of status, never a
     * card: covering the film to announce "trying another copy" is worse than a quiet subtitle.
     */
    private fun quietOrCenter(text: String) {
        if (pictureLive()) setStatus(text, "warn") else showCenter(text, true)
    }

    private fun showCenter(text: String, spinner: Boolean) {        // Buffering can begin while the chrome has faded. The message is the one thing that must not
        // be invisible, so bring the layer back without stealing the ring.
        if (overlay.visibility != View.VISIBLE) {
            revealChrome()
            armHide()
        }
        centerText.text = text
        centerSpinner.visibility = if (spinner) View.VISIBLE else View.GONE
        centerBox.visibility = View.VISIBLE
        if (panel == null && currentFocus == null) {
            playButton.post { if (panel == null && currentFocus == null) playButton.requestFocus() }
        }
    }

    private fun hideCenter() {
        centerBox.visibility = View.GONE
    }

    private fun setStatus(text: String, tone: String = "") {
        statusView.text = text
        statusView.setTextColor(
            when (tone) {
                "bad" -> theme.color("brand", "#E50914")
                "warn" -> theme.color("warn", "#F2C33A")
                else -> theme.ink2
            }
        )
        statusClear?.let { handler.removeCallbacks(it) }
        if (text.isBlank()) return
        val clear = Runnable {
            statusView.text = ""
            statusView.setTextColor(theme.ink2)
        }
        statusClear = clear
        handler.postDelayed(clear, 6000L)
    }

    // ---- error card -----------------------------------------------------------------------

    private var errorRetry: TextView? = null
    private var errorCard: View? = null

    /**
     * True once a picture from this session has actually reached the screen. The viewer's rule is
     * simple: nothing may be drawn over a film that is running. A failed attempt at a *different*
     * copy is not the film's failure, so it gets a one-line status, never a card.
     */
    private var hasPicture = false

    private fun pictureLive(): Boolean {
        val p = player ?: return false
        if (p.isPlaying) return true
        if (p.playbackState == Player.STATE_READY) {
            // Paused mid-film still counts: the frame is on screen and the card the viewer saw was
            // covering a picture they could have resumed.
            return hasPicture || p.currentPosition > 0
        }
        return false
    }

    /** Hub answers arrive as lowercase machine words; the page answers in sentences. */
    private fun humaniseError(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return "The hub gave no reason."
        val said = when {
            t.contains("warm", true) || t.contains("prepar", true) ->
                "The hub is still preparing this copy — try again in a moment"
            t.contains("not found", true) -> "No source in this hub carries this title"
            else -> t.replaceFirstChar { it.uppercaseChar() }
        }
        return if (said.endsWith(".") || said.endsWith("…")) said else "$said."
    }

    private fun showError(rawMessage: String, title: String = "This copy could not be played") {
        val message = humaniseError(rawMessage)
        // A card over a running picture is the bug the viewer reported: the old copy is fine, the
        // hub was merely late with another one. Say it in the status line and leave the picture be.
        if (pictureLive()) {
            hideCenter()
            setStatus(message, "warn")
            return
        }
        hideCenter()
        hideNextCard()
        hideUndo()
        closeMenu(restore = false)
        clearErrorCard()
        errorShowing = true
        cancelHide()
        revealChrome()
        val card = cardBox()
        // One heading language across the app: ink, never a coloured title — the pack's accent
        // already rides on the chips below.
        card.addView(Views.text(app, "panelTitle", 28f, theme.ink, title, bold = true))
        card.addView(Views.text(app, "sub", 21f, theme.ink2, message, maxLines = 3),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = theme.px(10f) })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val retry = chip("Try again") { retryCurrent() }
        val other = chip("Next copy") { failoverToNext() }
        val back = chip("Back") { finishToDetail() }
        row.addView(retry, wrap())
        row.addView(other, wrap().withStart(theme.px(12f)))
        row.addView(back, wrap().withStart(theme.px(12f)))
        card.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = theme.px(18f) })
        errorRetry = retry
        errorCard = card
        overlay.addView(card, FrameLayout.LayoutParams(
            theme.px(720f), ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })
        retry.post { retry.requestFocus() }
    }

    private fun retryCurrent() {
        clearError()
        hideNextCard()
        val spec = spec ?: return
        val pos = player?.let { filmPosition(it) } ?: 0L
        tried.clear()
        failovers = 0
        sameCopyRetries = 0
        recoveryScheduled = false
        showCenter("Preparing this copy…", true)
        resolvePlay(spec, pos, WARM_TRIES, null)
    }

    private fun clearError() {
        // If the retry was pressed, the focused button is about to be removed; hand the ring back to
        // Play instead of letting the framework pick the top-bar Back arrow.
        val hadFocus = errorCard?.hasFocus() == true || errorRetry?.isFocused == true
        clearErrorCard()
        errorShowing = false
        errorRetry = null
        if (hadFocus && controlsVisible()) playButton.post { if (!playButton.hasFocus()) playButton.requestFocus() }
    }

    private fun clearErrorCard() {
        errorCard?.let { overlay.removeView(it) }
        errorCard = null
    }

    // ---- next episode ---------------------------------------------------------------------

    private var nextCard: View? = null
    private var nextPlayButton: TextView? = null
    private var nextCountdown: TextView? = null
    private var undoButton: TextView? = null
    private var undoCard: View? = null

    private fun onEnded() {
        saveProgress(force = true)
        // A live feed that simply closes its body LOOKS like the end of a film to a player: STATE_ENDED
        // on a live item is a CDN hanging up, not a finished programme. Re-tune the same channel
        // (bounded) instead of telling the viewer to press Play to watch it again.
        if (live && player?.isCurrentMediaItemLive == true) {
            if (liveEndedRetries++ < 5) {
                quietOrCenter("Reconnecting to this channel…")
                val gen = requestGen
                handler.postDelayed({
                    if (!isFinishing && !isDestroyed && gen == requestGen)
                        answer?.let { start(it, 0L, playWhenReady = true) }
                }, 800L)
                return
            }
            showError("This channel is off the air. Try another channel.")
            return
        }
        liveEndedRetries = 0
        val next = nextEpisode
        if (next == null) {
            showChrome(focus = true)
            setStatus("Finished. Press Play to watch it again.")
            return
        }
        showNextCard(next)
    }

    private fun showNextCard(next: JSONObject) {
        hideNextCard()
        hideUndo()
        clearError()
        nextShowing = true
        cancelHide()
        revealChrome()
        val season = next.optInt("season", 0)
        val episode = next.optInt("episode", 0)
        val epTitle = str(next, "title")
        val code = if (season > 0 && episode > 0) "S%02dE%02d".format(season, episode) else "Next"
        val card = cardBox()
        card.addView(Views.text(app, "meta", 17f, theme.ink3, "UP NEXT"))
        card.addView(Views.text(app, "li", 23f, theme.ink,
            "$code" + (if (epTitle.isNotBlank()) "  ·  $epTitle" else ""), bold = true, maxLines = 2),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = theme.px(6f) })
        val count = Views.text(app, "meta", 18f, theme.ink2, "", maxLines = 1)
        card.addView(count, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = theme.px(8f) })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val play = chip("Play next") { playNext() }
        val stay = chip("Cancel") { hideNextCard(); showChrome(focus = true) }
        row.addView(play, wrap())
        row.addView(stay, wrap().withStart(theme.px(12f)))
        card.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = theme.px(16f) })

        nextCard = card
        nextPlayButton = play
        nextCountdown = count
        val width = theme.px(560f)
        val bottom = (bottomBar.height + theme.px(16f)).coerceAtLeast(theme.px(190f))
        overlay.addView(card, FrameLayout.LayoutParams(
            width, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = theme.gutter
            bottomMargin = bottom
        })
        play.post { play.requestFocus() }
        startNextCountdown()
    }

    private fun startNextCountdown() {
        nextTicker?.let { handler.removeCallbacks(it) }
        if (autoplayMs <= 0) {
            nextCountdown?.text = "Press Play next, or Cancel to stay"
            return
        }
        val until = SystemClock.elapsedRealtime() + autoplayMs
        val r = object : Runnable {
            override fun run() {
                if (!nextShowing) return
                val left = until - SystemClock.elapsedRealtime()
                if (left <= 0) {
                    playNext()
                    return
                }
                nextCountdown?.text = "Starting in ${(left + 999) / 1000}s  ·  Cancel to stay"
                nextTicker = this
                handler.postDelayed(this, 250L)
            }
        }
        nextTicker = r
        handler.post(r)
    }

    private fun hideNextCard() {
        nextShowing = false
        nextTicker?.let { handler.removeCallbacks(it) }
        nextTicker = null
        nextCard?.let { overlay.removeView(it) }
        nextCard = null
        nextPlayButton = null
        nextCountdown = null
    }

    private fun playNext() {
        val next = nextEpisode ?: return
        val season = next.optInt("season", 0)
        val episode = next.optInt("episode", 0)
        if (season <= 0 || episode <= 0) return
        val old = spec ?: return
        undoSpec = old
        undoPositionMs = maxPosition
        hideNextCard()
        val title = str(next, "title").ifBlank { old.title }
        open(PlaySpec(old.identity, old.type, season, episode, if (title.isBlank()) old.title else title), 0L)
        showUndo()
    }

    private fun showUndo() {
        hideUndo()
        val previous = undoSpec ?: return
        undoShowing = true
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.px(18f), theme.px(10f), theme.px(10f), theme.px(10f))
            background = Views.rounded(theme.color("panelScrim", "#E6000000"),
                theme.radius("modal", 10f), theme.px(1f), theme.color("line", "#262626"))
        }
        card.addView(Views.text(app, "meta", 18f, theme.ink2, "Started the next episode"))
        val undo = chip("Undo", compact = true) {
            hideUndo()
            open(previous, undoPositionMs)
        }
        card.addView(undo, wrap().withStart(theme.px(14f)))
        undoCard = card
        undoButton = undo
        overlay.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = theme.gutter
            bottomMargin = (bottomBar.height + theme.px(16f)).coerceAtLeast(theme.px(190f))
        })
        revealChrome()
        cancelHide()
        undo.post { undo.requestFocus() }
        val r = Runnable { hideUndo(); armHide() }
        undoRunnable = r
        handler.postDelayed(r, 9000L)
    }

    private fun hideUndo() {
        val lostFocus = undoButton?.isFocused == true
        undoShowing = false
        undoRunnable?.let { handler.removeCallbacks(it) }
        undoRunnable = null
        undoCard?.let { overlay.removeView(it) }
        undoCard = null
        undoButton = null
        if (lostFocus && controlsVisible()) playButton.post { playButton.requestFocus() }
    }

    private fun cardBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(theme.space("panelPad", 28f), theme.space("panelPadV", 24f),
            theme.space("panelPad", 28f), theme.space("panelPadV", 24f))
        background = Views.rounded(theme.color("panelScrim", "#E6000000"),
            theme.radius("modal", 10f), theme.px(1f), theme.color("line", "#262626"))
        elevation = theme.px(16f).toFloat()
    }

    // =========================================================================================
    // live zap
    // =========================================================================================

    private fun prefetchLiveChannels() {
        if (liveLoaded) return
        liveLoaded = true
        // The zap ring is the whole channel list, not the 18-channel home-row preview it used to be
        // taken from: zapping a channel that never made the home row used to "wrap" to channel 1.
        // /app/v1/row/live is the full live shelf, ordered by health then zap number — the order the
        // hub itself publishes numbers in.
        api.row(profileId, "live") { _, items, _ ->
            if (isFinishing) return@row
            val seen = LinkedHashMap<String, Item>()
            for (item in items.orEmpty()) {
                if (item.isLive() && item.identity.isNotBlank()) seen.putIfAbsent(item.identity, item)
            }
            if (seen.isEmpty()) return@row
            liveChannels = seen.values.toList()
            // The number is only known once the live row arrives; the header may already be up.
            if (live) updateHeader()
        }
    }

    private fun zap(delta: Int) {
        if (liveChannels.size < 2) return
        val here = spec ?: return
        val at = liveChannels.indexOfFirst { it.identity == here.identity }
        val next = if (at < 0) 0 else (at + delta + liveChannels.size) % liveChannels.size
        val item = liveChannels[next]
        if (item.identity == here.identity) return
        showZapBadge(item)
        saveProgress(force = true)
        spectrum(PlaySpec(item.identity, "channel", 0, 0, item.title))
    }

    private fun spectrum(spec: PlaySpec) {
        // Flush the channel being left FIRST: its partial watch minutes belong to its own identity,
        // and by now the spec/live flags below would route the post to the NEW channel.
        saveProgress(force = true)
        this.spec = spec
        displayTitle = spec.title
        live = true
        // Stop the old feed the instant the zap is accepted. Until now the previous channel kept
        // decoding until the new answer landed, so a failed tune left the viewer looking at the new
        // channel's header and the old channel's picture — and Play/Pause acted on the old one.
        player?.let {
            try { it.stop(); it.clearMediaItems() } catch (e: Exception) { /* already gone */ }
        }
        liveEndedRetries = 0
        answer = null
        tried.clear()
        failovers = 0
        sameCopyRetries = 0
        bufferingSince = 0L
        recoveryScheduled = false
        mixDisabled = false
        mixHint = null
        introStartMs = 0L
        introEndMs = 0L
        skipChip?.visibility = View.GONE
        selectedAudioId = null
        selectedSubId = null
        audioChosen = false
        subChosen = false
        audioTracks = JSONArray()
        embeddedSubs = JSONArray()
        alternatives = JSONArray()
        nextEpisode = null
        epSeason = 0
        currentSourceLabel = ""
        durationMs = 0L
        timelineOffsetMs = 0L
        startRungPending = false
        startRungLocked = false
        forcedRungHeight = 0
        seekLine.fraction = 0f
        seekLine.buffered = 0f
        hideNextCard()
        hideUndo()
        clearError()
        updateHeader()
        paintChips()
        paintTransport(false)
        updatePlayLabel()
        showChrome(focus = true)
        showCenter("Tuning…", true)
        requestSubs(spec.identity)
        resolvePlay(spec, 0L, WARM_TRIES, null)
    }

    private fun showZapBadge(item: Item) {
        val number = item.live?.number ?: 0
        val text = buildString {
            if (number > 0) append(number).append("  ·  ")
            append(item.title)
        }
        setStatus(text)
    }

    // =========================================================================================
    // progress
    // =========================================================================================

    private fun tick() {
        if (isFinishing || isDestroyed) return
        updatePosition()
        updateSkip()
        val p = player ?: return
        watchCushion(p)
        if (p.isPlaying) {
            val now = SystemClock.elapsedRealtime()
            if (now >= nextProgressAt) {
                nextProgressAt = now + progressPostMs
                // Live takes the same cadence and posts its watch-time delta; saveProgress routes
                // on the live flag itself.
                saveProgress(force = false)
            }
        }
    }

    /**
     * The distance between the playhead and the end of what the player is holding, watched while the
     * picture is moving.
     *
     * This is the signal the old logic never looked at, and it is the one that explains "it plays a movie
     * in chunks and keeps buffering after a few minutes". A copy that fails that way is not broken: the
     * host behind it is a little slower than the film, so the cushion drains by a few hundred kilobits a
     * second and the freeze arrives minutes later, when the viewer has already settled in. The 12 s stall
     * check only starts at the freeze. A hub-cushioned copy makes the distance readable — the screen reads
     * RAM at LAN speed while the hub pulls from the host — so a *shrinking* cushion under the floor says
     * the race is being lost while there is still picture to switch on, and the viewer steps down to a
     * lighter copy instead of watching a loading card.
     */
    private fun watchCushion(p: ExoPlayer) {
        if (live || recoveryScheduled || failovers >= MAX_FAILOVERS
            || cushionSwitches >= CUSHION_SWITCHES_MAX
        ) {
            return
        }
        if (p.playbackState != Player.STATE_READY || !p.isPlaying) {
            cushionAheadMs = -1L
            cushionFalling = 0
            return
        }
        val ahead = p.bufferedPosition - p.currentPosition
        // Unknown (a live window), or a cushion so full that this sample says nothing yet.
        if (ahead <= 0L || ahead > CUSHION_FLOOR_MS * 6) {
            cushionAheadMs = -1L
            cushionFalling = 0
            return
        }
        val prev = cushionAheadMs
        cushionAheadMs = ahead
        if (prev < 0L || ahead > CUSHION_FLOOR_MS) {
            cushionFalling = 0
            return
        }
        // Only a real decline counts. Half a second of playback consumes 500 ms of cushion, so a source
        // keeping pace shows a roughly flat line and a source winning it shows a rising one.
        if (ahead < prev - 150L) cushionFalling++ else cushionFalling = 0
        if (cushionFalling < CUSHION_FALL_TICKS) return
        cushionFalling = 0
        cushionAheadMs = -1L
        cushionSwitches++
        recoveryScheduled = true
        quietOrCenter(
            if (lowerAlternative() != null) "This copy is falling behind — moving to a lighter one…"
            else "This copy is falling behind — trying another one…"
        )
        failoverToNext(preferLower = true)
    }

    /**
     * The intro prompt follows the same rule as the web player: it exists only inside the window the
     * hub marked (`introStartMs`/`introEndMs`), defaulting to the last 90 s before the marked end. With
     * no window it stays gone rather than guessing.
     */
    private fun updateSkip() {
        val chip = skipChip ?: return
        val p = player ?: return
        if (introEndMs <= 0L || !p.isPlaying) {
            if (chip.visibility == View.VISIBLE) hideSkip()
            return
        }
        val pos = filmPosition(p)
        val start = if (introStartMs > 0L) introStartMs else (introEndMs - 90_000L).coerceAtLeast(0L)
        val inside = pos >= start && pos < introEndMs
        if (inside && chip.visibility != View.VISIBLE) {
            chip.visibility = View.VISIBLE
            // With the transport hidden the prompt is the only actionable thing on screen; give it the
            // ring so one OK press skips instead of pausing.
            if (!controlsVisible() && currentFocus == null) {
                chip.post { if (chip.isShown && currentFocus == null) chip.requestFocus() }
            }
        } else if (!inside && chip.visibility == View.VISIBLE) {
            hideSkip()
        }
    }

    private fun skipIntro() {
        if (introEndMs <= 0L) return
        player?.seekTo(mediaPosition(introEndMs))
        hideSkip()
        updatePosition()
    }

    private fun hideSkip() {
        val chip = skipChip ?: return
        val had = chip.hasFocus()
        chip.visibility = View.GONE
        if (had) {
            if (controlsVisible()) playButton.post { if (!playButton.hasFocus()) playButton.requestFocus() }
            else currentFocus?.clearFocus()
        }
    }

    private fun saveProgress(force: Boolean) {
        // Bank watch time under WHATEVER is loaded now, before any identity churn below can route a
        // flush to the wrong channel.
        if (live || loadedAnswer?.optBoolean("live", false) == true) flushLiveWatch()
        // The playhead belongs to what the player is actually holding, not to a request that is still
        // in flight. Without this, a Next press could write the old episode's position under the new
        // episode's identity for the seconds the hub takes to answer.
        val spec = loadedSpec ?: return
        if (live || (loadedAnswer?.optBoolean("live", false) == true)) {
            // A live channel has no playhead to carry, but it DOES have watch time: without this the
            // hub never learns that an evening of zapping happened — no last-channel, no watched
            // history, no honest Continue row. The delta is only counted while a frame is moving, and
            // posted as an additive `watchMs` (core/store.js adds it; the max-semantics watchedMs
            // stays for files).
            val delta = flushLiveWatch()
            if (delta <= 0L) return
            api.progress(JSONObject()
                .put("identity", spec.identity)
                .put("profile", profileId)
                .put("type", spec.type)
                .put("title", loadedTitle.ifBlank { spec.title })
                .put("watchMs", delta))
            return
        }
        val p = player ?: return
        val pos = filmPosition(p)
        if (pos <= 0L) return
        if (!force && pos == lastSavedPosition) return
        val dur = fullDuration(p)
        maxPosition = maxOf(maxPosition, pos)
        lastSavedPosition = pos
        val body = JSONObject()
            .put("identity", spec.identity)
            .put("profile", profileId)
            .put("type", spec.type)
            .put("title", loadedTitle.ifBlank { spec.title })
            .put("positionMs", pos)
            .put("durationMs", dur.coerceAtLeast(0L))
            .put("watchedMs", maxPosition)
        // Name the copy this screen is actually reading. /progress used to answer every screen with
        // stats about "whichever watched copy the hub touched last" — on two simultaneous screens one
        // viewer's starved buffer downgraded the other's healthy stream. The url is what the hub
        // handed this player (its proxy, its buffer, or the direct link it cushioned), so the hub can
        // look up THIS copy's numbers.
        loadedAnswer?.optString("url")?.takeIf { it.isNotBlank() }?.let { body.put("stream", it) }
        if (spec.season > 0 && spec.episode > 0) {
            body.put("season", spec.season).put("episode", spec.episode)
            body.put("episode", JSONObject()
                .put("season", spec.season)
                .put("episode", spec.episode)
                .put("title", loadedAnswer?.optJSONObject("episode")?.let { str(it, "title") }.orEmpty()))
        }
        api.progress(body)
    }

    /**
     * Bank and return the live watch time accumulated since the last call. Counting runs from the
     * last moment a frame was moving: a feed that stops mid-evening stops earning watch time right
     * then, rather than at the next heartbeat.
     */
    private fun flushLiveWatch(): Long {
        val p = player
        val watching = p != null && p.isPlaying
        val now = SystemClock.elapsedRealtime()
        if (watching && liveWatchBaseAt == 0L) liveWatchBaseAt = now
        if (!watching && liveWatchBaseAt > 0L) liveWatchMs += now - liveWatchBaseAt
        if (watching) liveWatchBaseAt = now
        val delta = liveWatchMs
        liveWatchMs = 0L
        return delta
    }

    private fun finishToDetail() {
        saveProgress(force = true)
        player?.pause()
        finish()
    }

    // =========================================================================================
    // keys
    // =========================================================================================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN

        if (code == KeyEvent.KEYCODE_BACK) {
            if (!down) return true
            if (panel != null) {
                closeMenu()
                return true
            }
            finishToDetail()
            return true
        }

        // Any input wakes the chrome. A direction/OK pressed while it is hidden is consumed so it does
        // not also land on a control that was not on screen when the viewer pressed it; a media key or
        // channel key still acts at once, because that is what those keys are for.
        val wasHidden = !controlsVisible()
        if (down && wasHidden) {
            val seeking = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
            val ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_SPACE
            // An intro prompt with the ring on it owns OK; this is a skip, not a pause.
            if (ok && skipChip?.visibility == View.VISIBLE && skipChip?.isFocused == true) {
                skipIntro()
                return true
            }
            showChrome(focus = !seeking)
            if (seeking) focusSoon(if (live) playButton else seekLine)
            // On a television, OK while the picture is clean means "pause", not "show me the chrome
            // so I can press OK again". The chrome comes up with the ring already on Pause.
            if (ok) {
                togglePlay()
                return true
            }
            if (!seeking && !isMediaKey(code)) return true
        } else if (down) {
            armHide()
        }

        if (down) {
            when (code) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                    togglePlay(); return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    player?.playWhenReady = true; updatePlayLabel(); return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    player?.pause(); saveProgress(force = true); updatePlayLabel(); return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    if (!live) seekBy(-seekBackMs); return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    if (!live) seekBy(seekForwardMs); return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (live) zap(1) else if (nextEpisode != null) playNext()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (live) zap(-1) else { player?.seekTo(0); updatePosition() }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (panelKind == MenuKind.EPISODES) {
                        val delta = if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                        if (moveEpisodeSeason(delta)) return true
                    }
                    if (panel == null) {
                        val dir = if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                        // At rest (chrome hidden) the remote is a television remote: left/right zaps a
                        // live feed and seeks a film. With the chrome up, left/right walks the row so
                        // Audio and Subtitles stay reachable on a channel too.
                        if (wasHidden) {
                            if (live) zap(dir) else seekBy(if (dir < 0) -seekBackMs else seekForwardMs)
                            return true
                        }
                        if (!live && currentFocus === seekLine) {
                            seekBy(if (dir < 0) -seekBackMs else seekForwardMs)
                            return true
                        }
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // Down from a settled film opens the full settings sheet, like the web player.
                    if (panel == null && !wasHidden && currentFocus === seekLine) {
                        openMenu(MenuKind.AUDIO, audioButton)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // =========================================================================================
    // drawing / formatting
    // =========================================================================================

    private fun isMediaKey(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> true
        else -> false
    }


    // =========================================================================================
    // flat transport icons
    // =========================================================================================

    private class GlyphView(context: android.content.Context, val glyph: Int, val seconds: Int = 0) : View(context) {
        private val app2 = (context.applicationContext as ScenixApp).ctx
        private val theme2 get() = app2.theme
        var playing = false
            set(value) {
                if (field != value) { field = value; invalidate() }
            }
        var badge = false
            set(value) {
                if (field != value) { field = value; invalidate() }
            }

        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.WHITE
        }
        private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private val letters = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        // A plain View measures AT_MOST as "fill the parent"; without this a wrap_content icon
        // button became the whole bottom bar. The glyph box is the suggested minimum.
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            setMeasuredDimension(
                resolveSize(minimumWidth, widthSpec),
                resolveSize(minimumHeight, heightSpec),
            )
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val u = minOf(width, height).toFloat() / 34f
            stroke.strokeWidth = theme2.px(2.3f) * (u.coerceAtMost(1.2f))
            when (glyph) {
                GLYPH_PLAY -> if (playing) {
                    val w = 3.4f * u
                    val h = 10.5f * u
                    canvas.drawRoundRect(cx - 8 * u, cy - h, cx - 8 * u + w, cy + h, 1.2f * u, 1.2f * u, solid)
                    canvas.drawRoundRect(cx + 4.6f * u, cy - h, cx + 4.6f * u + w, cy + h, 1.2f * u, 1.2f * u, solid)
                } else {
                    val path = Path()
                    path.moveTo(cx - 6.5f * u, cy - 10.5f * u)
                    path.lineTo(cx + 10 * u, cy)
                    path.lineTo(cx - 6.5f * u, cy + 10.5f * u)
                    path.close()
                    canvas.drawPath(path, solid)
                }
                GLYPH_REWIND -> seekIcon(canvas, cx, cy, u, seconds.coerceAtLeast(1), back = true)
                GLYPH_FORWARD -> seekIcon(canvas, cx, cy, u, seconds.coerceAtLeast(1), back = false)
                GLYPH_BACK -> {
                    val path = Path()
                    path.moveTo(cx + 3.5f * u, cy - 8 * u)
                    path.lineTo(cx - 4 * u, cy)
                    path.lineTo(cx + 3.5f * u, cy + 8 * u)
                    canvas.drawPath(path, stroke)
                }
                GLYPH_AUDIO -> {
                    // A waveform, not a speaker: the control picks a language track, and the bars read
                    // as "sound" at a glance without implying a volume slider.
                    val heights = floatArrayOf(5f, 10f, 16f, 21f, 15f, 9f, 4f)
                    for (i in heights.indices) {
                        val x = cx + (i - 3) * 3.6f * u
                        val h = heights[i] * u
                        canvas.drawLine(x, cy - h / 2f, x, cy + h / 2f, stroke)
                    }
                }
                GLYPH_SUBS -> {
                    // A speech bubble with two cue lines and a tail, matching the reference's subtitle
                    // affordance; "CC" in a box read as a broadcast caption service.
                    val r = RectF(cx - 12 * u, cy - 10 * u, cx + 12 * u, cy + 6 * u)
                    canvas.drawRoundRect(r, 3.2f * u, 3.2f * u, stroke)
                    canvas.drawLine(cx - 7 * u, cy - 3.4f * u, cx + 7 * u, cy - 3.4f * u, stroke)
                    canvas.drawLine(cx - 7 * u, cy + 1.4f * u, cx + 2 * u, cy + 1.4f * u, stroke)
                    val tail = Path()
                    tail.moveTo(cx - 5 * u, cy + 6 * u)
                    tail.lineTo(cx - 7.5f * u, cy + 12 * u)
                    tail.lineTo(cx - 1 * u, cy + 6 * u)
                    canvas.drawPath(tail, stroke)
                }
                GLYPH_SPEED -> {
                    // A dial with ticks and a needle: the same speedometer language as the reference.
                    val r = 10 * u
                    canvas.drawCircle(cx, cy, r, stroke)
                    for (deg in intArrayOf(210, 240, 270, 300, 330)) {
                        val a = Math.toRadians(deg.toDouble())
                        canvas.drawCircle(
                            cx + (r - 2.8f * u) * Math.cos(a).toFloat(),
                            cy + (r - 2.8f * u) * Math.sin(a).toFloat(),
                            0.9f * u, solid,
                        )
                    }
                    val a = Math.toRadians(300.0)
                    canvas.drawLine(cx, cy, cx + (6.8 * u * Math.cos(a)).toFloat(), cy + (6.8 * u * Math.sin(a)).toFloat(), stroke)
                    canvas.drawCircle(cx, cy, 1.5f * u, solid)
                }
                GLYPH_QUALITY -> {
                    // A display: the quality/source sheet is about what the panel is receiving.
                    val r = RectF(cx - 12 * u, cy - 10 * u, cx + 12 * u, cy + 5 * u)
                    canvas.drawRoundRect(r, 2.4f * u, 2.4f * u, stroke)
                    canvas.drawLine(cx, cy + 5 * u, cx, cy + 9.5f * u, stroke)
                    canvas.drawLine(cx - 5 * u, cy + 9.5f * u, cx + 5 * u, cy + 9.5f * u, stroke)
                }
                GLYPH_EPISODES -> {
                    // A stacked episode list: three still-thumb blocks with a title line beside each,
                    // the same language the episode rows themselves use.
                    for (i in 0..2) {
                        val y = cy + (i - 1) * 7.4f * u
                        canvas.drawRoundRect(
                            RectF(cx - 12 * u, y - 3.2f * u, cx - 4.2f * u, y + 3.2f * u),
                            1f * u, 1f * u, stroke,
                        )
                        canvas.drawLine(cx - 1 * u, y, cx + 12 * u, y, stroke)
                    }
                }
            }
            if (badge) {
                canvas.drawCircle(width - 11 * theme2.scale, 11 * theme2.scale, 3.4f * theme2.scale, solid)
            }
        }

        /** A circular arrow with the seconds inside it — the television convention for "jump". */
        private fun seekIcon(canvas: Canvas, cx: Float, cy: Float, u: Float, seconds: Int, back: Boolean) {
            val r = 10.5f * u
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            val start = if (back) 240f else 300f
            val sweep = if (back) -300f else 300f
            canvas.drawArc(rect, start, sweep, false, stroke)
            val end = Math.toRadians((start + sweep).toDouble())
            val ex = cx + (r * Math.cos(end)).toFloat()
            val ey = cy + (r * Math.sin(end)).toFloat()
            // Tangent at the arc's end, in the direction the viewer reads as "back"/"forward".
            val tx = if (back) Math.sin(end) else -Math.sin(end)
            val ty = if (back) -Math.cos(end) else Math.cos(end)
            val tip = Math.atan2(ty, tx)
            for (delta in doubleArrayOf(2.7, -2.7)) {
                val a = tip + delta
                canvas.drawLine(
                    ex, ey,
                    ex + (4.6f * u * Math.cos(a)).toFloat(),
                    ey + (4.6f * u * Math.sin(a)).toFloat(),
                    stroke,
                )
            }
            letters.textSize = 9.4f * u
            canvas.drawText(seconds.toString(), cx, cy + 3.5f * u, letters)
        }
    }

    private inner class SeekLine(context: android.content.Context) : View(context) {
        var fraction = 0f
        var buffered = 0f
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.color("track", "#2F2F2F") }
        private val buf = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.color("lineStrong", "#8FFFFFFF") }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.brand }
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.ink }

        override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            val cy = h / 2f
            val w = width.toFloat()
            // The timeline grows under the ring: on a 10-foot screen a 6 px bar is a hairline, and
            // the focused state is where a viewer actually reads it. Buffered and played ranges keep
            // their own colours so the three depths stay distinguishable at TV distance.
            val focused = isFocused
            val bar = theme.px(if (focused) 10f else 7f).toFloat()
            val r = bar / 2f
            canvas.drawRoundRect(0f, cy - bar / 2f, w, cy + bar / 2f, r, r, track)
            if (buffered > 0f) {
                canvas.drawRoundRect(0f, cy - bar / 2f, w * buffered, cy + bar / 2f, r, r, buf)
            }
            if (fraction > 0f) {
                canvas.drawRoundRect(0f, cy - bar / 2f, w * fraction, cy + bar / 2f, r, r, fill)
            }
            val knobR = theme.px(if (focused) 13f else 9f).toFloat()
            if (w > knobR * 2f) {
                // A dark cradle under the knob keeps it legible over the white played range.
                canvas.drawCircle((w * fraction).coerceIn(knobR, w - knobR), cy, knobR + theme.px(2f), track)
                canvas.drawCircle((w * fraction).coerceIn(knobR, w - knobR), cy, knobR, knob)
            }
        }
    }

    private fun clock(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun formatSpeed(v: Float): String =
        if (kotlin.math.abs(v - 1f) < 0.01f) "1×"
        else (if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()) + "×"

    private fun trackWords(t: JSONObject?): String {
        if (t == null) return "Track"
        val lang = str(t, "language").ifBlank { str(t, "lang") }
        val title = str(t, "title")
        val codec = codecName(str(t, "codec"))
        // The language is what a person is choosing; a donor's row title ("480p · 500 MB") is where
        // it came from, not its name.
        val head = if (lang.isNotBlank()) langName(lang) else title.ifBlank { "Track" }
        val extra = title.takeIf { it.isNotBlank() && !it.equals(head, true) }.orEmpty()
        return listOf(head, extra, codec).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun sourceWords(src: JSONObject?): String {
        if (src == null) return "Auto"
        val label = str(src, "label")
        val quality = qualityWord(str(src, "quality"))
        val provider = str(src, "provider")
        // The hub often uses the quality as both the label and the quality ("480p · 480p" on a
        // YouTube copy). Never print the same word twice on one row.
        val head = label.ifBlank { provider }.ifBlank { "Copy" }
        val parts = arrayListOf(head)
        if (quality.isNotBlank() && parts.none { it.equals(quality, true) }) parts.add(quality)
        return parts.joinToString(" · ")
    }

    /** The right-hand note on a source row: who serves it and how big it is, not the ranking essay. */
    private fun sourceNote(src: JSONObject): String {
        val provider = str(src, "provider")
        val size = src.optLong("sizeBytes", 0L)
        val mode = str(src, "mode")
        val parts = arrayListOf<String>()
        if (provider.isNotBlank()) parts.add(provider.replaceFirstChar { it.uppercase() })
        if (size > 0) parts.add(sizeWords(size))
        if (mode.isNotBlank() && !mode.equals("direct", true)) parts.add(mode)
        return parts.joinToString(" · ")
    }

    private fun sizeWords(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        if (gb >= 0.95) return String.format(java.util.Locale.US, "%.1f GB", gb)
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(java.util.Locale.US, "%.0f MB", mb)
    }

    private fun qualityWord(label: String): String {
        if (label.isBlank()) return ""
        val m = Regex("(\\d{3,4}p)").find(label)
        if (m != null) return m.groupValues[1]
        return label.substringBefore(" · ").take(18)
    }

    private fun langName(code: String): String {
        if (code.isBlank()) return "Unknown"
        return LANG_NAMES[code.lowercase()] ?: code.uppercase()
    }

    private fun subtitleMime(format: String, url: String): String {
        val ext = format.lowercase().ifBlank {
            url.substringBefore('?').substringAfterLast('.', "").lowercase()
        }
        when (ext) {
            "ass", "ssa" -> return MimeTypes.TEXT_SSA
            "vtt", "webvtt" -> return MimeTypes.TEXT_VTT
            "ttml", "dfxp" -> return MimeTypes.APPLICATION_TTML
            "srt", "sub" -> return MimeTypes.APPLICATION_SUBRIP
        }
        // An extension-less signed URL still usually says what it is somewhere in its path or query
        // (?fmt=vtt, /subs/movie.ass?token=…). Guessing SubRip for a WebVTT or ASS sidecar renders
        // nothing or garbage, so look for the word before falling back to it.
        val hay = url.lowercase()
        when {
            Regex("""\bwebvtt\b|\.vtt\b|[_=]vtt\b""").containsMatchIn(hay) -> return MimeTypes.TEXT_VTT
            Regex("""\bass\b|\.ass\b|[_=]ass\b|\bssa\b""").containsMatchIn(hay) -> return MimeTypes.TEXT_SSA
            Regex("""\bttml\b|\bdfxp\b""").containsMatchIn(hay) -> return MimeTypes.APPLICATION_TTML
        }
        return MimeTypes.APPLICATION_SUBRIP
    }

    // =========================================================================================
    // extras
    // =========================================================================================

    companion object {
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_IDENTITY = "identity"
        const val EXTRA_TYPE = "type"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SEASON = "season"
        const val EXTRA_EPISODE = "episode"
        const val EXTRA_POSITION_MS = "positionMs"
        const val EXTRA_MATURITY = "maturity"
        const val EXTRA_POSTER = "poster"
        const val EXTRA_LOGO = "logo"
        const val EXTRA_ADVISORIES = "advisories"

        private const val TAG = "ScenixPlayer"
        private const val STALL_AFTER_MS = 12_000L
        /**
         * The cushion the drain watchdog acts under: picture the player holds, in ms, below which the
         * copy is worth leaving — as long as holding less of it is a *trend* and not a seek. Ten seconds
         * of film is what a switch to another copy costs, so acting here means the viewer never sees the
         * loading card this used to end in.
         */
        private const val CUSHION_FLOOR_MS = 12_000L
        /** 500 ms ticks of monotonic decline before the copy is judged to be losing the race. */
        private const val CUSHION_FALL_TICKS = 8
        /** A viewer may be moved down twice for a shrinking cushion; after that the menu is theirs. */
        private const val CUSHION_SWITCHES_MAX = 2
        private const val GLYPH_PLAY = 0
        private const val GLYPH_REWIND = 1
        private const val GLYPH_FORWARD = 2
        private const val GLYPH_AUDIO = 3
        private const val GLYPH_SUBS = 4
        private const val GLYPH_SPEED = 5
        private const val GLYPH_QUALITY = 6
        private const val GLYPH_BACK = 7
        private const val GLYPH_EPISODES = 8
        private const val WARM_TRIES = 7
        private const val MAX_FAILOVERS = 3
        private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

        private val LANG_NAMES = mapOf(
            "hin" to "Hindi", "eng" to "English", "tam" to "Tamil", "tel" to "Telugu",
            "pan" to "Punjabi", "ben" to "Bengali", "mar" to "Marathi", "guj" to "Gujarati",
            "kan" to "Kannada", "mal" to "Malayalam", "urd" to "Urdu", "spa" to "Spanish",
            "fre" to "French", "fra" to "French", "ger" to "German", "deu" to "German",
            "ita" to "Italian", "jpn" to "Japanese", "kor" to "Korean", "chi" to "Chinese",
            "zho" to "Chinese", "rus" to "Russian", "ara" to "Arabic", "por" to "Portuguese",
        )

        private fun str(o: JSONObject?, name: String, def: String = ""): String =
            if (o == null || o.isNull(name)) def else o.optString(name, def)
    }
}
