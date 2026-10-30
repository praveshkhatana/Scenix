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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.scenix.tv.core.HubEndpoint
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Item
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

    private var spec: PlaySpec? = null
    /** The spec the player is actually holding, which can trail [spec] while a new answer is in flight. */
    private var loadedSpec: PlaySpec? = null
    private var loadedTitle = ""
    private var loadedAnswer: JSONObject? = null
    private var answer: JSONObject? = null
    private var live = false
    private var displayTitle = ""
    private var currentSourceLabel = ""
    private var audioTracks = JSONArray()
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
    private var selectedAudioId: String? = null
    private var selectedSubId: String? = null
    private var audioChosen = false
    private var subChosen = false
    private var speed = 1f
    private var durationMs = 0L
    /**
     * Film time where the current HLS ladder begins. An adaptive resume is transcoded from the saved
     * playhead, so the player's own timeline starts at zero while the viewer still expects 12:34.
     * Every readout, seek and saved progress adds this back; zero for a direct copy.
     */
    private var timelineOffsetMs = 0L
    private var maxPosition = 0L
    private var lastSavedPosition = -1L
    private var nextProgressAt = 0L
    private var requestGen = 0
    private var subsGen = 0
    private var failovers = 0
    private var sameCopyRetries = 0
    private var bufferingSince = 0L
    private var recoveryScheduled = false
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
            handler.postDelayed({
                if (!isFinishing) answer?.let { start(it, pos, playWhenReady = true) }
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
            .setUserAgent("Scenix/0.1")
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(30000)
            .setAllowCrossProtocolRedirects(true)
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
        val builder = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
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
            }

            override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: androidx.media3.common.VideoSize) {
                Log.i(TAG, "video size ${videoSize.width}x${videoSize.height} par=${videoSize.pixelWidthHeightRatio}")
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
                handler.removeCallbacks(stallCheck)
            }
            if (!isPlaying) saveProgress(force = true) else armHide()
        }

        override fun onTracksChanged(tracks: Tracks) {
            applyPendingTracks()
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
                        "video/hevc", "video/dolby-vision" -> add("hevc")
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
    private fun resolvePlay(spec: PlaySpec, positionMs: Long, warmTries: Int, source: JSONObject?) {
        val gen = ++requestGen
        api.play(bodyFor(spec, positionMs, source, emptySet())) { reply, status, transport ->
            if (gen != requestGen || isFinishing || isDestroyed) return@play
            val url = str(reply, "url")
            val warming = status == 503 || reply?.optBoolean("warming", false) == true
            if ((url.isBlank() || !isPlayable(url)) && warming && warmTries > 0) {
                val wait = (reply?.optLong("retryAfterMs", 1500L) ?: 1500L).coerceIn(900L, 4000L)
                showCenter("The hub is preparing this copy…", true)
                handler.postDelayed({
                    if (gen == requestGen && !isFinishing) resolvePlay(spec, positionMs, warmTries - 1, source)
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
        answer = reply
        val type = str(reply, "type", spec?.type ?: "movie")
        live = reply.optBoolean("live", false) || type == "channel" || type == "live"
        durationMs = reply.optLong("durationMs", 0L)
        displayTitle = str(reply, "title").ifBlank { spec?.title.orEmpty() }
        nextEpisode = reply.optJSONObject("nextEpisode")
        alternatives = reply.optJSONArray("alternatives") ?: JSONArray()
        audioTracks = reply.optJSONArray("audioTracks") ?: JSONArray()
        introStartMs = reply.optLong("introStartMs", 0L)
        introEndMs = reply.optLong("introEndMs", 0L)
        if (introEndMs <= 0L) skipChip?.visibility = View.GONE
        // A resume ladder is transcoded from the saved playhead. Its playlist starts at zero, so the
        // player's own timeline is relative; `startMs` is the film time to add back everywhere.
        timelineOffsetMs = (reply.optJSONObject("ladder")?.optLong("startMs", 0L) ?: 0L)
            .coerceAtLeast(0L)
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
            val id = str(picked, "id")
            val label = str(picked, "label")
            if (id.isNotBlank()) tried.add(id)
            if (label.isNotBlank()) tried.add(label)
        }
        // A source switch must not silently undo the viewer's explicit audio/subtitle choice. The hub's
        // recommendation is only the starting point; once a person has chosen, that choice survives a
        // new copy (matched by stream index/id, and left alone if the new copy cannot honour it).
        val answerAudio = str(reply, "selectedAudio").takeIf { it.isNotBlank() }
        val answerSub = str(reply, "selectedSub").takeIf { it.isNotBlank() }
        if (!audioChosen) selectedAudioId = answerAudio
        if (!subChosen) selectedSubId = answerSub

        val headers = HashMap<String, String>()
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
        val mediaStart = (positionMs - timelineOffsetMs).coerceAtLeast(0L)
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
        val position = positionOf(ordered, id)
        if (position >= 0) groups.getOrNull(position)?.let { return it }
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
        val waiting = name.contains("TIMEOUT") || name.contains("IO_NETWORK") ||
            name.contains("IO_UNSPECIFIED") || name.contains("BEHIND_LIVE_WINDOW")
        val pos = player?.let { filmPosition(it) } ?: 0L
        if (waiting && sameCopyRetries < 2) {
            // A cold copy is not a broken file. Two silent reloads at the same playhead, with a little
            // more room each time, are cheaper than throwing away a copy that is still filling.
            sameCopyRetries++
            showCenter(if (live) "Reconnecting to this channel…" else "Reconnecting to this copy…", true)
            val wait = if (sameCopyRetries == 1) 1200L else 2600L
            handler.postDelayed({
                if (!isFinishing) answer?.let { start(it, pos, playWhenReady = true) }
            }, wait)
            return
        }
        answer?.optJSONObject("picked")?.let {
            str(it, "id").takeIf { v -> v.isNotBlank() }?.let { v -> tried.add(v) }
            str(it, "label").takeIf { v -> v.isNotBlank() }?.let { v -> tried.add(v) }
        }
        if (failovers >= MAX_FAILOVERS) {
            showError("Nothing on this hub would play this title. ${sourceAdvice()}")
            return
        }
        failovers++
        val spec = spec ?: return
        val exclude = tried.toMutableSet()
        val body = bodyFor(spec, pos, null, exclude)
        showCenter("This copy stopped — trying another…", true)
        val lower = lowerAlternative()
        if (preferLowerSource(lower, spec, pos)) return
        askSwitch(body, pos, playWhenReady = true, warmTries = 4, automatic = true)
    }

    private fun failoverToNext(preferLower: Boolean = false) {
        val spec = spec ?: return
        val pos = player?.let { filmPosition(it) } ?: 0L
        clearError()
        if (preferLower) {
            val lower = lowerAlternative()
            if (preferLowerSource(lower, spec, pos)) return
        }
        val exclude = tried.toMutableSet()
        showCenter("Trying another copy…", true)
        askSwitch(bodyFor(spec, pos, null, exclude), pos, playWhenReady = true, warmTries = 4, automatic = true)
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
        showCenter("Trying a lighter copy…", true)
        askSwitch(bodyFor(spec, pos, picked, tried.toMutableSet()), pos,
            playWhenReady = true, warmTries = 3, automatic = true)
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
            val warming = status == 503 || reply?.optBoolean("warming", false) == true
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
                    showError(
                        str(reply, "error").ifBlank {
                            transport ?: "No other copy of this title would open"
                        }
                    )
                } else {
                    setStatus(str(reply, "error").ifBlank { "No other copy would open" })
                    player?.playWhenReady = playWhenReady
                }
                return@switchSource
            }
            sameCopyRetries = 0
            recoveryScheduled = false
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
        Focus.attach(seekLine, seekLine, app, theme.radius("button", 6f), scale = false, elevate = false)
        val lineLp = LinearLayout.LayoutParams(0, theme.px(26f), 1f)
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
        rewindButton = icon(GLYPH_BACK10, "Back ${seekBackMs / 1000} seconds") { seekBy(-seekBackMs) }
        forwardButton = icon(GLYPH_FORWARD30, "Forward ${seekForwardMs / 1000} seconds") { seekBy(seekForwardMs) }
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

        bottomBar.addView(buttonRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = theme.px(12f) })

        overlay.addView(bottomBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM })

        barFocusable = listOf(
            backButton, playButton, rewindButton, forwardButton,
            audioButton, subButton, speedButton, qualityButton,
        ) + listOfNotNull(episodesButton) + listOf(seekLine)
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
    private fun icon(glyph: Int, desc: String, flat: Boolean = false, action: () -> Unit): GlyphView {
        val v = GlyphView(this, glyph)
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
        )
        return v
    }

    private fun chip(label: String, compact: Boolean = false, action: () -> Unit): TextView {
        val b = Views.text(
            app, "li", if (compact) 20f else 22f, theme.ink, label, bold = true,
            gravity = Gravity.CENTER,
        )
        b.setPadding(
            theme.px(if (compact) 18f else 22f), theme.px(10f),
            theme.px(if (compact) 18f else 22f), theme.px(10f),
        )
        b.background = Views.rounded(theme.color("btnGrey", "#1AFFFFFF"), theme.radius("button", 6f))
        Focus.attach(b, b, app, theme.radius("button", 6f), scale = false, elevate = false, onClick = action)
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
        panel?.let { overlay.removeView(it) }
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

        trapFocus()
        val target = if (keepFocus && focused != null && panelRows.contains(focused)) focused
        else panelRows.getOrNull(selectedRowIndex(kind)) ?: panelRows.firstOrNull()
        target?.post { if (panel != null) target.requestFocus() }
    }

    /** First/last rows point at themselves so the ring cannot fall out of an open menu. */
    private fun trapFocus() {
        if (panelRows.isEmpty()) return
        for (r in panelRows) r.id = View.generateViewId()
        panelRows.first().nextFocusUpId = panelRows.first().id
        panelRows.last().nextFocusDownId = panelRows.last().id
    }

    private fun audioRows(parent: LinearLayout) {
        if (audioTracks.length() == 0) {
            val actual = actualTracks(C.TRACK_TYPE_AUDIO, "a")
            parent.addView(menuRow(
                "Default", "picked by the file", selectedAudioId == null && actual.none { it.selected },
            ) { chooseAudio(null) })
            for (t in actual) {
                parent.addView(menuRow(t.label, "", t.selected) { chooseAudio(t.id) })
            }
            if (actual.isEmpty()) parent.addView(noteRow("No audio list from this copy"))
            addMixToggle(parent)
            return
        }
        parent.addView(menuRow(
            "Default", "picked by your languages", selectedAudioId == null,
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
            parent.addView(menuRow(label, note, selectedAudioId == id) { chooseAudio(id) })
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

    private fun reloadAudio(mix: Boolean) {
        val spec = spec ?: return
        val pos = player?.let { filmPosition(it) } ?: 0L
        mixDisabled = !mix
        audioChosen = false
        selectedAudioId = null
        pendingAudio = null
        audioApplied = false
        closeMenu()
        showCenter(if (mix) "Loading the mixed audio…" else "Loading the original audio…", true)
        // Keep the copy the viewer is already watching: a language toggle must not re-open the whole
        // title and land on a different provider's file.
        val picked = answer?.optJSONObject("picked")
        val source = picked?.let {
            JSONObject()
                .put("id", str(it, "id"))
                .put("label", str(it, "label"))
                .put("provider", str(it, "provider"))
        }
        resolvePlay(spec, pos, WARM_TRIES, source)
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
        parent.addView(menuRow("Auto", "best copy the hub can feed", false) {
            switchTo(null)
        })
        for (i in 0 until alternatives.length()) {
            val src = alternatives.optJSONObject(i) ?: continue
            parent.addView(menuRow(sourceWords(src), sourceNote(src), false) {
                switchTo(src)
            })
        }
        if (alternatives.length() == 0) {
            parent.addView(noteRow("The hub listed no other copies"))
        }
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
                    episodesList = if (list == null || list.length() == 0) JSONArray() else list
                    // The viewer may have closed the panel in the meantime; only redraw a live one.
                    if (panelKind == MenuKind.EPISODES) rebuildMenu(MenuKind.EPISODES)
                }
            }
            return
        }
        val seasons = ArrayList<Int>()
        for (i in 0 until eps.length()) {
            val se = eps.optJSONObject(i)?.optInt("season", 1) ?: continue
            if (se !in seasons) seasons.add(se)
        }
        seasons.sort()
        if (epSeason == 0)
            epSeason = if (seasons.contains(s.season)) s.season else seasons.firstOrNull() ?: 0
        if (seasons.size > 1) {
            val chips = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            for (se in seasons) {
                chips.addView(chip("Season $se", compact = true) {
                    if (epSeason != se) {
                        epSeason = se
                        rebuildMenu(MenuKind.EPISODES)
                    }
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
        Focus.attach(row, row, app, theme.radius("listItem", 6f), scale = false, elevate = false)
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
        Focus.attach(row, row, app, theme.radius("listItem", 6f), scale = false, elevate = false)
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
        panel?.let { overlay.removeView(it) }
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

    private fun paintChips() {
        audioButton.contentDescription = "Audio · " + audioValue()
        subButton.contentDescription = "Subtitles · " + subValue()
        speedButton.contentDescription = "Speed · " + formatSpeed(speed)
        qualityButton.contentDescription = "Quality · " + currentSourceLabel.ifBlank { "Auto" }
        // A tiny dot is the only "current value" a flat icon can carry without becoming a label:
        // audio when a specific track is chosen, CC when subtitles are on, speed when it is not 1×.
        audioButton.badge = selectedAudioId != null
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
    }

    private fun audioValue(): String {
        val id = selectedAudioId ?: return "Default"
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

    private fun showChrome(focus: Boolean) {
        overlay.visibility = View.VISIBLE
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
        overlay.visibility = View.INVISIBLE
    }

    private fun showCenter(text: String, spinner: Boolean) {
        // Buffering can begin while the chrome has faded. The message is the one thing that must not
        // be invisible, so bring the layer back without stealing the ring.
        if (overlay.visibility != View.VISIBLE) {
            overlay.visibility = View.VISIBLE
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

    private fun showError(message: String) {
        hideCenter()
        hideNextCard()
        hideUndo()
        closeMenu(restore = false)
        clearErrorCard()
        errorShowing = true
        cancelHide()
        overlay.visibility = View.VISIBLE
        val card = cardBox()
        card.addView(Views.text(app, "panelTitle", 28f, theme.color("brand", "#E50914"),
            "This copy could not be played", bold = true))
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
        overlay.visibility = View.VISIBLE
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
            open(previous, 0L)
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
        overlay.visibility = View.VISIBLE
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
        api.load(profileId) { rows, _ ->
            if (rows == null || isFinishing) return@load
            val seen = LinkedHashMap<String, Item>()
            for (row in rows) {
                for (item in row.items) {
                    if (item.isLive() && item.identity.isNotBlank()) {
                        seen.putIfAbsent(item.identity, item)
                    }
                }
            }
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
        this.spec = spec
        displayTitle = spec.title
        live = true
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
        if (p.isPlaying && !live) {
            val now = SystemClock.elapsedRealtime()
            if (now >= nextProgressAt) {
                nextProgressAt = now + progressPostMs
                saveProgress(force = false)
            }
        }
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
        // The playhead belongs to what the player is actually holding, not to a request that is still
        // in flight. Without this, a Next press could write the old episode's position under the new
        // episode's identity for the seconds the hub takes to answer.
        val spec = loadedSpec ?: return
        if (live || (loadedAnswer?.optBoolean("live", false) == true)) return
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
        if (spec.season > 0 && spec.episode > 0) {
            body.put("season", spec.season).put("episode", spec.episode)
            body.put("episode", JSONObject()
                .put("season", spec.season)
                .put("episode", spec.episode)
                .put("title", loadedAnswer?.optJSONObject("episode")?.let { str(it, "title") }.orEmpty()))
        }
        api.progress(body)
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

    private class GlyphView(context: android.content.Context, val glyph: Int) : View(context) {
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
                GLYPH_BACK10 -> seekIcon(canvas, cx, cy, u, 10, back = true)
                GLYPH_FORWARD30 -> seekIcon(canvas, cx, cy, u, 30, back = false)
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

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            val cy = h / 2f
            val r = theme.px(3f).toFloat()
            val w = width.toFloat()
            val bar = theme.px(6f).toFloat()
            canvas.drawRoundRect(0f, cy - bar / 2f, w, cy + bar / 2f, r, r, track)
            if (buffered > 0f) {
                canvas.drawRoundRect(0f, cy - bar / 2f, w * buffered, cy + bar / 2f, r, r, buf)
            }
            if (fraction > 0f) {
                canvas.drawRoundRect(0f, cy - bar / 2f, w * fraction, cy + bar / 2f, r, r, fill)
            }
            val knobR = theme.px(8f).toFloat()
            if (w > knobR * 2f) {
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
        if (code.isBlank()) return "Default"
        return LANG_NAMES[code.lowercase()] ?: code.uppercase()
    }

    private fun subtitleMime(format: String, url: String): String {
        val ext = format.lowercase().ifBlank {
            url.substringBefore('?').substringAfterLast('.', "").lowercase()
        }
        return when (ext) {
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt", "webvtt" -> MimeTypes.TEXT_VTT
            "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
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
        private const val GLYPH_PLAY = 0
        private const val GLYPH_BACK10 = 1
        private const val GLYPH_FORWARD30 = 2
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
