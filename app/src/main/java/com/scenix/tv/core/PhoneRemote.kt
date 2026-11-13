package com.scenix.tv.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.EditText
import com.scenix.tv.BrowseActivity
import com.scenix.tv.BrowseApi
import com.scenix.tv.SearchActivity
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * The phone remote, owned by the process rather than by whichever screen showed the QR.
 *
 * A pairing opened in Settings has to keep answering while the viewer walks the rest of the app: a
 * key steers whatever screen is actually in front (it enters through that Activity's
 * [Activity.dispatchKeyEvent], so pickers, the PIN pad and the player keep their own traps), and
 * typed letters go to the field on screen that wants text — a real text box, a search query, a PIN —
 * falling back to opening Search with the phrase, exactly as if the viewer had typed it on the TV.
 * The session is minted the first time a screen asks for the pairing link and lives until the TV
 * leaves the foreground; a phone left open on a table then reports the truth instead of steering
 * nothing.
 */
class PhoneRemote(context: Context, private val app: AppContext) {

    /** A screen that wants typed text itself (search's field, a PIN pad) before the default hand-off. */
    interface TextTarget {
        /** Take `text` and return true, or decline and let the remote fall through to Search. */
        fun onRemoteText(text: String): Boolean
    }

    /** Everything a pairing rail paints from. The rail never owns the session itself. */
    data class Pairing(
        val origin: String,
        val live: Boolean,
        val url: String?,
        val code: String,
        val status: String,
        val phoneConnected: Boolean,
    )

    /** Set by the screen currently showing the QR; a null listener simply stops being painted. */
    var onPairing: ((Pairing) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) publish()
        }

    private val host = context.applicationContext
    private val api by lazy { BrowseApi(host, app.prefs) }
    private val handler = Handler(Looper.getMainLooper())

    private var token = ""
    private var code = ""
    private var last = 0
    private var origin = ""
    private var open = false
    private var registering = false
    private var fallbackUrl: String? = null
    private var foreground = 0
    private var currentActivity: WeakReference<Activity>? = null
    private var phoneSeenAt = 0L

    private val poll = object : Runnable {
        override fun run() = pollEvents()
    }

    // ---- lifecycle fed by ScenixApp's activity callbacks --------------------------------------

    fun noteResumed(activity: Activity) {
        currentActivity = WeakReference(activity)
    }

    fun notePaused(activity: Activity) {
        if (currentActivity?.get() === activity) currentActivity = null
    }

    /** The app as a whole came to or left the foreground; polling follows, never leads. */
    fun noteForeground(inFront: Boolean) {
        foreground += if (inFront) 1 else -1
        if (foreground < 0) foreground = 0
        if (foreground == 0) {
            // Nobody is watching this TV: end the session honestly so an abandoned phone page says
            // "the TV closed this remote" rather than pretending to still be driven.
            stopPolling()
            closeSession()
        } else {
            schedulePoll(0L)
        }
    }

    // ---- pairing session -----------------------------------------------------------------------

    /** Mint (or refresh) the session behind the pairing link. Safe to call on every Settings visit. */
    fun ensureSession() {
        if (open || registering) return
        val where = pairingOrigin()
        if (where.isNullOrBlank()) {
            origin = ""
            publish()
            return
        }
        origin = where
        fallbackUrl = null
        token = randomHex(16)
        code = randomCode()
        last = 0
        registering = true
        api.remoteRegister(token, code) { root, _ ->
            registering = false
            if (root?.optBoolean("ok") == true) {
                code = root.optString("code", code).uppercase()
                open = true
                last = 0
            } else {
                // An older compatible Hub has no remote route. The QR still has a job: it opens the
                // web Settings page, so pairing is never a dead end on an older home.
                fallbackUrl = PhonePairing.settingsUrl(origin)
            }
            publish()
            schedulePoll(0L)
        }
    }

    private fun pairingOrigin(): String? = try {
        if (app.prefs.standalone()) {
            val ip = PhonePairing.lanIp()
            val port = LocalServer.portFor(host)
            // webBase already enumerates the LAN IP; fall back to an explicit build so a bare
            // port-0 server never produces a broken QR.
            val viaBase = LocalServer.webBase(host)
            val built = PhonePairing.standaloneUrl(ip, if (port > 0) port else PhonePairing.STANDALONE_PORT)
            viaBase.takeIf { it.isNotBlank() } ?: built
        } else {
            PhonePairing.normaliseOrigin(HubEndpoint.base(host))
                ?: PhonePairing.normaliseOrigin(app.prefs.hubUrl(host.getString(com.scenix.tv.R.string.hub_url)))
        }
    } catch (_: Throwable) {
        null
    }

    private fun pairingUrl() = origin.trimEnd('/') + "/remote?t=" + token + "&c=" + code

    private fun publish() {
        val listener = onPairing ?: return
        listener(
            Pairing(
                origin = origin,
                live = open,
                url = when {
                    open -> pairingUrl()
                    !fallbackUrl.isNullOrBlank() -> fallbackUrl.orEmpty()
                    origin.isNotBlank() -> PhonePairing.settingsUrl(origin)
                    else -> null
                },
                code = code,
                status = when {
                    origin.isBlank() -> "Connect this TV to Wi-Fi, then reopen Settings."
                    open -> if (System.currentTimeMillis() - phoneSeenAt < 30_000L)
                        "Phone connected" else "Waiting for your phone…"
                    else -> "Phone remote needs a Hub update; this code opens web Settings."
                },
                phoneConnected = System.currentTimeMillis() - phoneSeenAt < 30_000L,
            ),
        )
    }

    // ---- event pump ------------------------------------------------------------------------------

    private fun schedulePoll(delayMs: Long) {
        handler.removeCallbacks(poll)
        if (!open || foreground == 0) return
        // A connected phone wants the D-pad to feel instant. A session nobody has scanned yet is only
        // worth a quiet probe, so idle TVs do not put a request on the hub every second.
        val live = System.currentTimeMillis() - phoneSeenAt < 30_000L
        handler.postDelayed(poll, delayMs + if (live) 700L else 3_000L)
    }

    private fun stopPolling() {
        handler.removeCallbacks(poll)
    }

    private fun pollEvents() {
        if (!open || foreground == 0) return
        val seen = token
        api.remoteEvents(seen, last) { root, _ ->
            if (!open || seen != token || foreground == 0) return@remoteEvents
            if (root != null) {
                val events = root.optJSONArray("events")
                if (events != null) {
                    for (i in 0 until events.length()) {
                        val event = events.optJSONObject(i) ?: continue
                        inject(event)
                    }
                }
                last = root.optInt("last", last)
                if (root.optLong("phoneSeenAt", 0L) > 0L) phoneSeenAt = root.optLong("phoneSeenAt")
                publish()
            }
            schedulePoll(0L)
        }
    }

    private fun closeSession() {
        if (!open) return
        val seen = token
        open = false
        token = ""
        code = ""
        if (seen.isNotBlank()) api.remoteClose(seen) { _, _ -> }
    }

    /**
     * A phone event lands in the same input path as the TV remote. Keys go through the foreground
     * Activity's [Activity.dispatchKeyEvent] so every screen keeps its own focus traps; typed text
     * looks for a field that wants letters before it ever becomes a search.
     */
    private fun inject(event: JSONObject) {
        val activity = currentActivity?.get() ?: return
        when (event.optString("type")) {
            "text" -> {
                val text = event.optString("text").trim()
                if (text.isNotEmpty()) deliverText(activity, text)
            }
            "key" -> {
                val key = event.optString("key").trim().uppercase()
                if (key == "HOME") {
                    goHome(activity)
                    return
                }
                val code = keyCode(key) ?: return
                dispatchKey(activity, code)
            }
        }
    }

    private fun deliverText(activity: Activity, text: String) {
        if (activity is TextTarget && activity.onRemoteText(text)) return
        val focus = activity.currentFocus
        if (focus is EditText) {
            val start = focus.selectionStart.coerceAtLeast(0)
            focus.text.replace(start, start, text)
            return
        }
        val profile = profileOf(activity)
        if (profile.isBlank()) return
        // Nothing on screen is waiting for letters, so the phrase is a search — the same thing a
        // viewer holding the TV remote would have typed.
        activity.startActivity(
            Intent(activity, SearchActivity::class.java)
                .putExtra(SearchActivity.EXTRA_PROFILE, profile)
                .putExtra(SearchActivity.EXTRA_QUERY, text.take(48)),
        )
    }

    private fun goHome(activity: Activity) {
        val profile = profileOf(activity)
        if (profile.isBlank() || activity is BrowseActivity) {
            activity.finish()
            return
        }
        activity.startActivity(
            Intent(activity, BrowseActivity::class.java)
                .putExtra(BrowseActivity.EXTRA_PROFILE, profile)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun profileOf(activity: Activity): String =
        activity.intent?.getStringExtra("profile").orEmpty()

    private fun dispatchKey(activity: Activity, code: Int) {
        val now = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        activity.dispatchKeyEvent(KeyEvent(now, now + 1, KeyEvent.ACTION_UP, code, 0))
    }

    private fun keyCode(key: String): Int? = when (key) {
        "UP" -> KeyEvent.KEYCODE_DPAD_UP
        "DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
        "LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
        "RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
        "OK" -> KeyEvent.KEYCODE_DPAD_CENTER
        "BACK" -> KeyEvent.KEYCODE_BACK
        "DELETE" -> KeyEvent.KEYCODE_DEL
        "MENU" -> KeyEvent.KEYCODE_MENU
        "PLAY_PAUSE" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        "REWIND" -> KeyEvent.KEYCODE_MEDIA_REWIND
        "FAST_FORWARD" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        else -> null
    }

    private fun randomHex(bytes: Int): String {
        val data = ByteArray(bytes)
        java.security.SecureRandom().nextBytes(data)
        return data.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun randomCode(): String {
        val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        val random = java.security.SecureRandom()
        return (1..6).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }
}
