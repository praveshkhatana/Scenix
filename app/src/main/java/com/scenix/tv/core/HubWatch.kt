package com.scenix.tv.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.scenix.tv.R

/**
 * The rule the app follows: the hub is the source of truth, but a TV with no data on screen is a TV
 * nobody watches. So one probe of `GET /healthz` decides whether the page is reachable, and it keeps
 * trying in the background so the catalogue comes back by itself when the hub does.
 *
 * A hub is a plain Node process the owner can move to any machine — a box in the house, a laptop, a
 * hosted address — so there is rarely one permanent address to write into the TV. Each pass walks
 * every candidate from [Prefs.hubUrlCandidates] and stops at the first that answers, remembering it
 * so the next boot starts there. That is the difference between "the hub moved and the TV is bricked"
 * and "the TV followed it".
 *
 * When there is no candidate at all the app does not spin: one LAN announcement search
 * ([HubDiscovery]) per pass, and if nothing answers, [unpaired] is set so the app can ask the viewer
 * what to do instead of probing an empty network forever.
 *
 * Two states stay out of this file's way completely: a pinned fixture (adb `--es fixture …`) and
 * standalone mode, where this app IS the backend and there is nothing to reach.
 */
class HubWatch(context: Context, private val ctx: AppContext) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    // Both switches are read when a pass runs, not when this object is built: Settings and the pairing
    // screen change them mid-session, and a watcher frozen at the mode the TV booted in keeps
    // overwriting the choice the viewer just made.
    private val pinned: Boolean get() = !ctx.prefs.fixture().isNullOrBlank()

    /** Standalone mode has no network backend to follow: this app answers for itself. */
    private val standalone: Boolean get() = ctx.prefs.standalone()

    @Volatile private var online = true
    private var running = false

    /** True when nothing is paired and the LAN search found no hub — the app asks instead of waiting. */
    @Volatile var unpaired = false
        private set

    /** First probe, then the caller decides what to show; retries continue on their own. */
    fun start(onReady: ((Boolean) -> Unit)? = null) {
        running = true
        if (pinned) {
            Log.i(TAG, "fixture pinned by the user, no hub probing")
            onReady?.invoke(false)
            return
        }
        if (standalone) {
            Log.i(TAG, "standalone mode: this TV is the backend, no hub probing")
            online = true
                onReady?.invoke(true)
            return
        }
        check(onReady)
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
    }

    /**
     * The viewer has just chosen a backend — an address typed in, a hub picked off the LAN list, or
     * this TV on its own. Forget the previous pass and look again now, instead of leaving the pairing
     * screen up while the old state drains out at the next scheduled probe.
     */
    fun rechoose(onReady: ((Boolean) -> Unit)? = null) {
        main.removeCallbacksAndMessages(null)
        unpaired = false
        running = true
        online = false
        start(onReady)
    }

    /**
     * One pass over the candidate list. The remembered winner is tried first, so a hub that has
     * been sitting at the same address pays for exactly one request.
     */
    private fun check(done: ((Boolean) -> Unit)? = null) {
        // A scheduled pass can land after the viewer has already chosen standalone. There is nothing
        // to reach in that mode, so the watcher stands down instead of probing a network this TV left.
        if (standalone) {
            online = true
                done?.invoke(true)
            return
        }
        val list = ctx.prefs.hubUrlCandidates(app.getString(R.string.hub_urls))
        if (list.isEmpty()) {
            // Nothing paired. Ask the network once rather than probing an empty list forever, and
            // leave the answer to the caller: with nothing found the app offers pairing or
            // standalone, which is what a stranger's first run needs.
            unpaired = true
            val target = app.getString(R.string.ssdp_search_target)
            val ms = app.resources.getInteger(R.integer.ssdp_search_ms)
            HubDiscovery.scan(app, target, ms) { found ->
                if (found.isNotEmpty()) {
                    ctx.prefs.setDiscoveredHubs(found)
                    Log.i(TAG, "found ${found.size} hub(s) on the LAN, probing them")
                    check(done)
                } else {
                    Log.i(TAG, "no hub on this network and none paired")
                    settle(false, done)
                }
            }
            return
        }
        unpaired = false
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
        // The probe re-reads this candidate directly; remembering it is what makes the next boot try
        // the hub that actually answered first.
        if (url != ctx.prefs.hubUrl("")) ctx.prefs.setHubUrl(url)
        Thread({
            val ok = HubProbe.healthy(url)
            if (ok) {
                ctx.prefs.setHubLastWorking(url)
                Log.i(TAG, "hub answering at $url")
                main.post {
                    apply(true)
                    done?.invoke(true)
                    if (running) schedule()
                }
            } else {
                Log.i(TAG, "no answer from $url (${tried + 1}/${list.size})")
                tryAt(list, (i + 1) % list.size, tried + 1, done)
            }
        }, "scenix-probe").apply { isDaemon = true }.start()
    }

    private fun settle(ok: Boolean, done: ((Boolean) -> Unit)?) {
        main.post {
            apply(ok)
            done?.invoke(ok)
            if (running) schedule()
        }
    }

    /** Remember a change of reachability so the next scheduled pass uses the right interval. */
    private fun apply(ok: Boolean) {
        if (pinned || ok == online) return
        online = ok
        Log.i(TAG, if (ok) "hub reachable again" else "hub unreachable")
    }

    /**
     * Online: a cheap check every 30 s. Offline with something paired: retry every 10 s, because the
     * family is waiting. Nothing paired: re-announce-search every 60 s, which is how a hub started
     * after this TV finds its way in without anyone touching the remote.
     */
    private fun schedule() {
        val ms = when {
            unpaired -> SEARCH_MS
            online -> ONLINE_MS
            else -> RETRY_MS
        }
        main.postDelayed({ if (running) check() }, ms)
    }

    companion object {
        private const val TAG = "ScenixHubWatch"
        private const val ONLINE_MS = 30_000L
        private const val RETRY_MS = 10_000L
        private const val SEARCH_MS = 60_000L
    }
}
