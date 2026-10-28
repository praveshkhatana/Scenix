package com.scenix.tv

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
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
    private var selectedAudioId: String? = null
    private var selectedSubId: String? = null
    private var audioChosen = false
    private var subChosen = false
    private var speed = 1f
    private var durationMs = 0L
    private var maxPosition = 0L
    private var lastSavedPosition = -1L
    private var nextProgressAt = 0L
    private var requestGen = 0
    private var subsGen = 0
    private var failovers = 0
    private var sameCopyRetried = false
    private val tried = LinkedHashSet<String>()
    private var liveChannels: List<Item> = emptyList()
    private var liveLoaded = false

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
    private lateinit var backButton: TextView
    private lateinit var playButton: TextView
    private lateinit var rewindButton: TextView
    private lateinit var forwardButton: TextView
    private lateinit var audioButton: TextView
    private lateinit var subButton: TextView
    private lateinit var speedButton: TextView
    private lateinit var qualityButton: TextView
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
        player?.playWhenReady = false
        saveProgress(force = true)
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
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(60000)
            .setAllowCrossProtocolRedirects(true)
        http = httpFactory

        val ctx = app
        // A hub with HUB_TOKEN protects its own URLs, including the gateway's stream. The token is
        // added only for this hub's origin: a direct third-party stream must never learn the
        // household's token.
        val base = WebShellActivity.apiBase(this)
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
        p.addListener(listener())
        player = p
    }

    private fun listener() = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> {
                    if (!errorShowing) showCenter(if (live) "Tuning…" else "Buffering…", true)
                    updatePlayLabel()
                }
                Player.STATE_READY -> {
                    hideCenter()
                    durationMs = player?.duration?.takeIf { it > 0 } ?: durationMs
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
            .put("positionMs", positionMs.coerceAtLeast(0L))
        if (spec.season > 0 && spec.episode > 0) {
            body.put("season", spec.season).put("episode", spec.episode)
        }
        if (source != null) body.put("source", source)
        if (exclude.isNotEmpty()) body.put("exclude", JSONArray(exclude.toList()))
        return body
    }

    private fun open(spec: PlaySpec, positionMs: Long) {
        saveProgress(force = true)
        this.spec = spec
        answer = null
        live = spec.type == "channel" || spec.type == "live"
        displayTitle = spec.title
        tried.clear()
        failovers = 0
        sameCopyRetried = false
        selectedAudioId = null
        selectedSubId = null
        audioChosen = false
        subChosen = false
        audioTracks = JSONArray()
        embeddedSubs = JSONArray()
        alternatives = JSONArray()
        nextEpisode = null
        currentSourceLabel = ""
        durationMs = 0L
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
        resolvePlay(spec, positionMs, WARM_TRIES)
        if (live) prefetchLiveChannels()
    }

    /** Ask the hub for the copy. Warming answers are retried; only a real refusal becomes an error. */
    private fun resolvePlay(spec: PlaySpec, positionMs: Long, warmTries: Int) {
        val gen = ++requestGen
        api.play(bodyFor(spec, positionMs, null, emptySet())) { reply, status, transport ->
            if (gen != requestGen || isFinishing || isDestroyed) return@play
            val url = str(reply, "url")
            val warming = status == 503 || reply?.optBoolean("warming", false) == true
            if ((url.isBlank() || !isPlayable(url)) && warming && warmTries > 0) {
                val wait = (reply?.optLong("retryAfterMs", 1500L) ?: 1500L).coerceIn(900L, 4000L)
                showCenter("The hub is preparing this copy…", true)
                handler.postDelayed({
                    if (gen == requestGen && !isFinishing) resolvePlay(spec, positionMs, warmTries - 1)
                }, wait)
                return@play
            }
            if (status !in 200..299 || url.isBlank() || !isPlayable(url)) {
                val why = str(reply, "error")
                    .ifBlank { transport ?: "The hub could not open this title" }
                showError(why)
                return@play
            }
            sameCopyRetried = false
            start(reply!!, positionMs, playWhenReady = true)
        }
    }

    /** The answer is good: turn it into a MediaItem and start. Used for play, switch and retry. */
    private fun start(reply: JSONObject, positionMs: Long, playWhenReady: Boolean) {
        clearError()
        answer = reply
        val type = str(reply, "type", spec?.type ?: "movie")
        live = reply.optBoolean("live", false) || type == "channel" || type == "live"
        durationMs = reply.optLong("durationMs", 0L)
        displayTitle = str(reply, "title").ifBlank { spec?.title.orEmpty() }
        nextEpisode = reply.optJSONObject("nextEpisode")
        alternatives = reply.optJSONArray("alternatives") ?: JSONArray()
        audioTracks = reply.optJSONArray("audioTracks") ?: JSONArray()
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
        p.setMediaItem(item, positionMs.coerceAtLeast(0L))
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
        for (i in 0 until hubSubs.length()) {
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
        url.startsWith("/") -> WebShellActivity.apiBase(this).trimEnd('/') + url
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
        val group = findGroup(groups, id) ?: return false
        b.setTrackTypeDisabled(type, false)
            .clearOverridesOfType(type)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(0)))
        p.trackSelectionParameters = b.build()
        return true
    }

    private fun findGroup(groups: List<Tracks.Group>, id: String): Tracks.Group? {
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
        val pos = p.currentPosition.coerceAtLeast(0L)
        val playing = p.playWhenReady
        pendingSub = subId
        pendingAudio = selectedAudioId
        audioApplied = false
        subApplied = false
        subsInItem = hubSubs.length()
        p.setMediaItem(buildItem(reply), pos)
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
        val waiting = name.contains("TIMEOUT") || name.contains("IO_NETWORK") ||
            name.contains("IO_UNSPECIFIED") || name.contains("BEHIND_LIVE_WINDOW")
        val pos = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
        if (waiting && !sameCopyRetried) {
            // A cold copy is not a broken file. One silent reload at the same playhead is the cheapest
            // correct answer, and it is what the web player does.
            sameCopyRetried = true
            showCenter(if (live) "Reconnecting to this channel…" else "Reconnecting to this copy…", true)
            handler.postDelayed({
                if (!isFinishing) answer?.let { start(it, pos, playWhenReady = true) }
            }, 1200L)
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
        askSwitch(body, pos, playWhenReady = true, warmTries = 4, automatic = true)
    }

    private fun failoverToNext() {
        val spec = spec ?: return
        val pos = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
        val exclude = tried.toMutableSet()
        clearError()
        showCenter("Trying another copy…", true)
        askSwitch(bodyFor(spec, pos, null, exclude), pos, playWhenReady = true, warmTries = 4, automatic = true)
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
            sameCopyRetried = false
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
        backButton = chip("‹  Back", compact = true) { finishToDetail() }
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
        playButton = chip("Play") { togglePlay() }
        rewindButton = chip("«  ${seekBackMs / 1000}s") { seekBy(-seekBackMs) }
        forwardButton = chip("${seekForwardMs / 1000}s  »") { seekBy(seekForwardMs) }
        audioButton = chip("Audio") { openMenu(MenuKind.AUDIO, audioButton) }
        subButton = chip("Subtitles") { openMenu(MenuKind.SUBTITLES, subButton) }
        speedButton = chip("Speed · 1×") { openMenu(MenuKind.SPEED, speedButton) }
        qualityButton = chip("Quality") { openMenu(MenuKind.QUALITY, qualityButton) }

        buttonRow.addView(playButton, wrap())
        buttonRow.addView(rewindButton, wrap().withStart(theme.px(12f)))
        buttonRow.addView(forwardButton, wrap().withStart(theme.px(12f)))
        buttonRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        buttonRow.addView(audioButton, wrap())
        buttonRow.addView(subButton, wrap().withStart(theme.px(12f)))
        buttonRow.addView(speedButton, wrap().withStart(theme.px(12f)))
        buttonRow.addView(qualityButton, wrap().withStart(theme.px(12f)))

        bottomBar.addView(buttonRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = theme.px(12f) })

        overlay.addView(bottomBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM })

        barFocusable = listOf(
            backButton, playButton, rewindButton, forwardButton,
            audioButton, subButton, speedButton, qualityButton, seekLine,
        )
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
        centerBox.addView(centerText)
        overlay.addView(centerBox, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })
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

    private enum class MenuKind { AUDIO, SUBTITLES, SPEED, QUALITY }

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
            MenuKind.QUALITY -> "Quality · ${currentSourceLabel.ifBlank { "Auto" }}"
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
        }
        if (rows.childCount > 8) {
            val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
            scroll.addView(rows)
            box.addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, theme.px(560f)
            ))
        } else {
            box.addView(rows, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        val width = theme.px(560f)
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
            return
        }
        parent.addView(menuRow(
            "Default", "picked by your languages", selectedAudioId == null,
        ) { chooseAudio(null) })
        for (i in 0 until audioTracks.length()) {
            val t = audioTracks.optJSONObject(i) ?: continue
            val id = str(t, "id")
            if (id.isBlank()) continue
            parent.addView(menuRow(trackWords(t), "", selectedAudioId == id) { chooseAudio(id) })
        }
    }

    private fun subtitleRows(parent: LinearLayout) {
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
        for (i in 0 until hubSubs.length()) {
            val s = hubSubs.optJSONObject(i) ?: continue
            val id = "hub:$i"
            val lang = str(s, "lang").ifBlank { str(s, "language") }
            val label = str(s, "label").ifBlank { str(s, "name") }.ifBlank { langName(lang) }
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
            val codec = f.codecs ?: f.sampleMimeType?.substringAfter('/') ?: ""
            out.add(
                ActualTrack(
                    id = "$prefix:${i + 1}",
                    label = listOf(head, codec.uppercase()).filter { it.isNotBlank() }.joinToString(" · "),
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
            val whyArr = src.optJSONArray("why")
            val why = (if (whyArr != null && whyArr.length() > 0) whyArr.optString(0) else "")
                .ifBlank { str(src, "status") }
                .ifBlank { str(src, "tagline") }
            parent.addView(menuRow(sourceWords(src), why, false) {
                switchTo(src)
            })
        }
        if (alternatives.length() == 0) {
            parent.addView(noteRow("The hub listed no other copies"))
        }
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
    }

    /** `s:2` / `a:3` → 1, the row's offset after the leading Default/Off row. */
    private fun trackOrdinal(id: String?): Int =
        ((id?.substringAfter(':')?.toIntOrNull() ?: 1) - 1).coerceAtLeast(0)

    private fun indexOfTrack(arr: JSONArray, id: String?): Int {
        for (i in 0 until arr.length()) if (str(arr.optJSONObject(i), "id") == id) return i
        return 0
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
        val pos = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
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
        val dur = p.duration
        val limit = if (dur > 0) dur else Long.MAX_VALUE
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, limit))
        updatePosition()
        armHide()
    }

    private fun updatePosition() {
        val p = player ?: return
        val dur = p.duration.takeIf { it > 0 } ?: durationMs
        val pos = p.currentPosition.coerceAtLeast(0L)
        maxPosition = maxOf(maxPosition, pos)
        if (!live && dur > 0) {
            seekLine.fraction = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            seekLine.buffered = (p.bufferedPosition.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            elapsed.text = clock(pos)
            remaining.text = "-" + clock((dur - pos).coerceAtLeast(0L))
        }
        seekLine.invalidate()
    }

    private fun updatePlayLabel() {
        val p = player ?: return
        playButton.text = when {
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
        val epTitle = answer?.optJSONObject("episode")?.let { str(it, "title") }.orEmpty()
        if (epTitle.isNotBlank()) bits.add(epTitle)
        val maturity = intent?.getStringExtra(EXTRA_MATURITY).orEmpty()
        if (maturity.isNotBlank()) bits.add(maturity)
        val advisories = intent?.getStringArrayListExtra(EXTRA_ADVISORIES)?.filter { it.isNotBlank() }
        if (!advisories.isNullOrEmpty()) bits.add(advisories.joinToString(" · "))
        metaView.text = bits.joinToString("  ·  ")
        liveBadge.visibility = if (live) View.VISIBLE else View.GONE
    }

    private fun paintChips() {
        audioButton.text = "Audio" + audioValue().let { if (it.isBlank()) "" else " · $it" }
        subButton.text = "Subtitles · " + subValue()
        speedButton.text = "Speed · " + formatSpeed(speed)
        qualityButton.text = "Quality" + currentSourceLabel.let {
            val q = qualityWord(it)
            if (q.isBlank()) "" else " · $q"
        }
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
        target?.post { if (target.isShown) target.requestFocus() }
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
        val pos = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
        tried.clear()
        failovers = 0
        sameCopyRetried = false
        showCenter("Preparing this copy…", true)
        resolvePlay(spec, pos, WARM_TRIES)
    }

    private fun clearError() {
        clearErrorCard()
        errorShowing = false
        errorRetry = null
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
        sameCopyRetried = false
        selectedAudioId = null
        selectedSubId = null
        audioChosen = false
        subChosen = false
        audioTracks = JSONArray()
        embeddedSubs = JSONArray()
        alternatives = JSONArray()
        nextEpisode = null
        currentSourceLabel = ""
        durationMs = 0L
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
        resolvePlay(spec, 0L, WARM_TRIES)
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
        val p = player ?: return
        if (p.isPlaying && !live) {
            val now = SystemClock.elapsedRealtime()
            if (now >= nextProgressAt) {
                nextProgressAt = now + progressPostMs
                saveProgress(force = false)
            }
        }
    }

    private fun saveProgress(force: Boolean) {
        // The playhead belongs to what the player is actually holding, not to a request that is still
        // in flight. Without this, a Next press could write the old episode's position under the new
        // episode's identity for the seconds the hub takes to answer.
        val spec = loadedSpec ?: return
        if (live || (loadedAnswer?.optBoolean("live", false) == true)) return
        val p = player ?: return
        val pos = p.currentPosition.coerceAtLeast(0L)
        if (pos <= 0L) return
        if (!force && pos == lastSavedPosition) return
        val dur = p.duration.takeIf { it > 0 } ?: durationMs
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
            showChrome(focus = !seeking)
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
        val codec = str(t, "codec").uppercase()
        val head = if (title.isNotBlank()) title else langName(lang)
        return listOf(head, codec).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun sourceWords(src: JSONObject?): String {
        if (src == null) return "Auto"
        val label = str(src, "label").ifBlank { str(src, "provider") }
        val quality = str(src, "quality")
        val provider = str(src, "provider")
        val head = label.ifBlank { provider }.ifBlank { "Copy" }
        return listOf(head, quality).filter { it.isNotBlank() }.joinToString(" · ")
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
