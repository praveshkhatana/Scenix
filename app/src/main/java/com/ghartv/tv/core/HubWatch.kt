package com.scenix.tv.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Keeps [AppContext.hub] pointed at something that answers.
 *
 * The rule the app follows: the hub is the source of truth, but a TV with no data on screen is a TV
 * nobody watches. So one probe of `GET /healthz` decides which [HubClient] the whole app uses —
 * the real HTTP client, or the bundled fixtures — and it keeps trying in the background so the
 * catalogue comes back by itself when the hub does. Screens never poll this; they just notice
 * [AppContext.offline] the next time they render.
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

    private fun check(done: ((Boolean) -> Unit)? = null) {
        probe.health { ok, _ ->
            main.post {
                val changed = apply(ok ?: false)
                done?.invoke(ok ?: false)
                if (changed) onChange?.invoke(ok ?: false)
                if (running) schedule()
            }
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
