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
 * nobody watches. So one probe of `GET /healthz` decides which [HubClient] the whole app uses — the
 * real HTTP client, or the bundled fixtures — and it keeps trying in the background so the catalogue
 * comes back by itself when the hub does. Screens never poll this; they just notice
 * [AppContext.offline] the next time they render.
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
 * Two states stay out of this file's way completely: a pinned fixture (adb `--es fixture …`, or
 * Settings) and standalone mode, where this app IS the backend and there is nothing to reach.
 */
class HubWatch(context: Context, private val ctx: AppContext) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** A client used only for probing, so the swap of `ctx.hub` never affects the probe. */
    private val probe = HttpHub(app, ctx.prefs)

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
        if (standalone) {
            Log.i(TAG, "standalone mode: this TV is the backend, no hub probing")
            online = true
            ctx.offline = false
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
        // Force the next pass to re-point ctx.hub even if reachable/unreachable has not moved: a new
        // address is a change of source, and the screens should hear about it.
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
            ctx.offline = false
            ctx.hub = LocalHub.get(app)
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
                    val known = ctx.prefs.discoveredHubs().associateBy { it.url }
                    val merged = ArrayList(known.values)
                    for (f in found) if (!known.containsKey(f.url)) merged.add(f)
                    ctx.prefs.setDiscoveredHubs(merged)
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

        /** The bundled catalogue used when the hub is down. */
        const val DEFAULT_FIXTURE = "fixtures/home.json"

        fun defaultFixture(): String = DEFAULT_FIXTURE
    }
}
