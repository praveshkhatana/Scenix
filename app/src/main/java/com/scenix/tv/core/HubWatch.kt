package com.scenix.tv.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.scenix.tv.R

/**
 * Keeps [AppContext.hub] pointed at something that answers.
 *
 * The rule the app follows: the hub is the source of truth, but a TV with no data on screen is a TV
 * nobody watches. So one probe of `GET /healthz` decides which [HubClient] the whole app uses —
 * the real HTTP client, or the bundled fixtures — and it keeps trying in the background so the
 * catalogue comes back by itself when the hub does. Screens never poll this; they just notice
 * [AppContext.offline] the next time they render.
 *
 * A hub is a plain Node process the owner can move to any machine — a box in the house, a laptop,
 * a VPS, a Tailscale address — so there is rarely one permanent address to write into the TV.
 * Each pass walks every candidate from [Prefs.hubUrlCandidates] and stops at the first that
 * answers, remembering it so the next boot starts there. That is the difference between "the hub
 * moved and the TV is bricked" and "the TV followed it".
 *
 * If the user pinned a fixture (adb `--es fixture …`, or Settings), this stays out of the way.
 */
class HubWatch(context: Context, private val ctx: AppContext) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** A client used only for probing, so the swap of `ctx.hub` never affects the probe. */
    private val probe = HttpHub(app, ctx.prefs)

    private val pinned: Boolean = !ctx.prefs.fixture().isNullOrBlank()

    @Volatile private var online = true
    private var running = false

    /** The hub address actually serving this TV right now, for Settings to show. */
    @Volatile var activeHub: String? = null
        private set

    /** Called on the UI thread when the app flips between live hub and fixtures. */
    var onChange: ((Boolean) -> Unit)? = null

    /** First probe, then the caller decides what to show; retries continue on their own. */
    fun start(onReady: ((Boolean) -> Unit)? = null) {
        running = true
        if (pinned) {
            Log.i(TAG, "fixture pinned by the user, no hub probing")
            onReady?.invoke(false)
            return
        }
        check(onReady)
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
    }

    /**
     * One pass over the candidate list. The remembered winner is tried first, so a hub that has
     * been sitting at the same address pays for exactly one request.
     */
    private fun check(done: ((Boolean) -> Unit)? = null) {
        val list = ctx.prefs.hubUrlCandidates(app.getString(R.string.hub_urls))
        if (list.isEmpty()) {
            Log.w(TAG, "no hub address configured")
            settle(false, done)
            return
        }
        val first = list.indexOfFirst { it == ctx.prefs.hubLastWorking() }.let { if (it >= 0) it else 0 }
        tryAt(list, first, tried = 0, done = done)
    }

    /** Try candidate `i`; on a miss move to the next one. A miss is normal, not an error. */
    private fun tryAt(list: List<String>, i: Int, tried: Int, done: ((Boolean) -> Unit)?) {
        if (tried >= list.size) {
            settle(false, done)
            return
        }
        val url = list[i]
        // HttpHub re-reads the address on every request, so pointing the probe at this candidate is
        // just a preference write — and Settings then shows the hub the TV really settled on.
        if (url != ctx.prefs.hubUrl("")) ctx.prefs.setHubUrl(url)
        probe.health { ok, _ ->
            if (ok == true) {
                ctx.prefs.setHubLastWorking(url)
                activeHub = url
                Log.i(TAG, "hub answering at $url")
                main.post {
                    val changed = apply(true)
                    done?.invoke(true)
                    if (changed) onChange?.invoke(true)
                    if (running) schedule()
                }
            } else {
                Log.i(TAG, "no answer from $url (${tried + 1}/${list.size})")
                tryAt(list, (i + 1) % list.size, tried + 1, done)
            }
        }
    }

    private fun settle(ok: Boolean, done: ((Boolean) -> Unit)?) {
        main.post {
            if (!ok) activeHub = null
            val changed = apply(ok)
            done?.invoke(ok)
            if (changed) onChange?.invoke(ok)
            if (running) schedule()
        }
    }

    /** Returns true when the app's data source actually changed. */
    private fun apply(ok: Boolean): Boolean {
        if (pinned || ok == online) return false
        online = ok
        ctx.offline = !ok
        ctx.hub = if (ok) HttpHub(app, ctx.prefs) else FixtureHub(app, defaultFixture())
        Log.i(TAG, if (ok) "hub reachable again" else "hub unreachable, using $DEFAULT_FIXTURE")
        return true
    }

    /** Online: a cheap check every 30 s. Offline: retry every 10 s, because the family is waiting. */
    private fun schedule() {
        main.postDelayed({ if (running) check() }, if (online) ONLINE_MS else RETRY_MS)
    }

    companion object {
        private const val TAG = "ScenixHubWatch"
        private const val ONLINE_MS = 30_000L
        private const val RETRY_MS = 10_000L

        /** The bundled catalogue used when the hub is down. */
        const val DEFAULT_FIXTURE = "fixtures/home.json"

        fun defaultFixture(): String = DEFAULT_FIXTURE
    }
}
