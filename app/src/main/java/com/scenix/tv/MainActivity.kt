package com.scenix.tv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import com.scenix.tv.core.PinPad
import android.util.Log
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.FixtureHub
import com.scenix.tv.core.HttpHub
import com.scenix.tv.core.Item
import com.scenix.tv.core.Profile
import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.ScreenPlugin
import com.scenix.tv.core.clientFor

/**
 * The whole app is one activity: a container and a stack of screens, where a screen is a
 * [ScreenPlugin] that renders views. That is what lets navigation be registry-driven — the nav bar
 * asks the registry for screens, and this file never names one (except the two it must reach by
 * force: the profile picker and the player).
 *
 * Two launch switches, both read here and nowhere else:
 *  - `--es hub_url http://<hub-host>:8788`  → which hub to talk to
 *  - `--es fixture fixtures/home.json` → use the offline asset client instead of the network
 *
 * A build names no machine, so the first run of a fresh install has no hub to talk to: the health
 * probe finds nothing, the LAN search finds nothing, and this activity opens the pairing screen
 * ([com.scenix.tv.plugins.nav.PairingScreen]) which offers the hubs announcing themselves on the
 * network, an address to type, or standalone mode. Nothing spins and nothing shows sample rows as
 * though they were the family's library.
 *
 * A `tv://detail/<identity>` deep link lands on Detail once a profile is chosen.
 */
class MainActivity : AppCompatActivity(), ScreenHost {

    /**
     * The remote's number keys reach the window but are dropped before they get to the PIN pad's
     * views, so the pad is fed from here. Everything else follows the normal path.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Any key is a decision to move, so a trailer still waiting out its dwell is cancelled
            // here rather than opening under a card the viewer has already left.
            com.scenix.tv.core.Preview.cancelPending()
            val pad = PinPad.active
            if (pad != null && event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                pad.remoteDigit((event.keyCode - KeyEvent.KEYCODE_0).toString())
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private lateinit var root: FrameLayout
    private lateinit var ctx: AppContext

    private data class Frame(val screen: ScreenPlugin, val arg: String?)

    private val stack = ArrayList<Frame>()
    private val seeds = HashMap<String, Item>()
    private var playReq: Pair<Item, com.scenix.tv.core.Source?>? = null
    private var pendingDeepLink: String? = null
    private var pendingDeepPlay: String? = null
    private lateinit var watch: com.scenix.tv.core.HubWatch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ctx = (application as ScenixApp).ctx

        applyLaunchSwitches()

        root = FrameLayout(this)
        root.setBackgroundColor(ctx.theme.bg)
        setContentView(root)

        handleDeepLink(intent)

        // One health probe decides whether the whole app talks to the hub or to the bundled
        // fixtures, so the first screen never shows a blank page. It keeps retrying after that.
        watch = com.scenix.tv.core.HubWatch(this, ctx)
        ctx.watch = watch
        watch.onChange = { online ->
            Log.i(TAG, if (online) "hub back, re-rendering" else "hub gone, re-rendering on fixtures")
            // A different backend can do different things, so ask it again and let the nav follow.
            readCapabilities()
            if (stack.isNotEmpty()) render(stack.last().screen)
        }
        watch.start { online -> startup(online) }
    }

    /**
     * A `tv://` link that arrives while the app is already up (item 6). With the manifest at
     * `singleTop` Android hands the new Intent to this same instance instead of starting a second
     * one, so the link has to be read here — reading it only in onCreate is why `am start -d
     * tv://detail/…` looked like it did nothing.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /** `tv://detail/<identity>` and `tv://play/<identity>`, from onCreate and from onNewIntent. */
    private fun handleDeepLink(i: Intent?) {
        val uri = i?.data ?: return
        if (!uri.scheme.equals("tv", true)) return
        val identity = uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
        val route = (uri.host ?: "detail").lowercase()
        if (route != "play") routeDetail(identity) else routePlay(identity)
    }

    private fun routeDetail(identity: String) {
        if (ctx.profile == null) { pendingDeepLink = identity; return }
        openDetail(identity, seeds[identity] ?: Item(identity = identity, title = ""))
    }

    private fun routePlay(identity: String) {
        if (ctx.profile == null) { pendingDeepPlay = identity; return }
        openPlayerFor(identity)
    }

    /**
     * A bare `tv://play/<identity>` may arrive with no seed in this process, so the item is fetched
     * from the hub first; without sources there is nothing for the player to open.
     */
    private fun openPlayerFor(identity: String) {
        val seed = seeds[identity]
        if (seed != null && seed.sources.isNotEmpty()) {
            play(seed, null)
            return
        }
        ctx.hub.meta(identity, seed?.type) { item, _ ->
            runOnUiThread {
                if (item != null && item.sources.isNotEmpty()) play(item, null)
                else {
                    seeds[identity] = item ?: Item(identity = identity, title = "")
                    show("player", identity)
                }
            }
        }
    }

    /** Re-render whatever is on top, in place (MENU-key actions that change what a profile may see). */
    override fun refresh() {
        runOnUiThread { stack.lastOrNull()?.let { render(it.screen) } }
    }

    /** Called once the data client for this session is known. */
    private fun startup(online: Boolean) {
        // Nothing paired and no choice made yet: ask the viewer what this TV should be — a hub found
        // on the network, an address typed in, or this app on its own — instead of sitting on the
        // bundled samples or spinning on an empty network.
        if (!online && watch.unpaired) {
            show("pairing")
            return
        }
        val remembered = ctx.prefs.currentProfileId()
        if (!remembered.isNullOrBlank()) {
            ctx.hub.profiles { profiles, _ ->
                runOnUiThread {
                    val match = profiles?.firstOrNull { it.id == remembered }
                    if (match != null) enterProfile(match) else requireProfilePicker("startup")
                }
            }
        } else {
            requireProfilePicker("startup")
        }
    }

    /**
     * Ask the backend what it can do. Nothing on screen waits for the answer: it fills the one switch
     * the UI branches on (design/deployment-modes.md Rule 2), and a screen whose subject this backend
     * cannot supply leaves the nav when it arrives. A backend that says nothing leaves the list null,
     * which hides nothing — an older hub is not punished for not having the field yet.
     */
    private fun readCapabilities() {
        ctx.hub.manifest { m, _ ->
            if (m == null) return@manifest
            val changed = ctx.capabilities != m.capabilities
            ctx.capabilities = m.capabilities
            if (!changed) return@manifest
            Log.i(TAG, "backend capabilities: ${m.capabilities ?: "not stated"}")
            runOnUiThread { if (stack.isNotEmpty()) render(stack.last().screen) }
        }
    }

    /** `--es hub_url …` / `--es fixture …`, also reachable from Settings, and `tv://` links. */
    private fun applyLaunchSwitches() {
        val i = intent ?: return
        i.getStringExtra("hub_url")?.takeIf { it.isNotBlank() }?.let {
            ctx.prefs.setHubUrl(it.trim())
            // Naming a hub is the whole point of the flag: it means "show me the real library". The
            // offline fixture is a development pin that survives a relaunch, and leaving it stuck on
            // is how a TV ends up displaying last month's rows and no artwork while the hub is healthy.
            ctx.prefs.setFixture("")
            Log.i(TAG, "hub_url override: ${it.trim()} (offline preview cleared)")
        }
        i.getStringExtra("fixture")?.takeIf { it.isNotBlank() }?.let {
            ctx.prefs.setFixture(it)
            Log.i(TAG, "fixture override: $it")
        }
        ctx.hub = clientFor(ctx.prefs, this)
    }

    // ---- ScreenHost ------------------------------------------------------------------------

    override val app: AppContext get() = ctx

    override fun container(): FrameLayout {
        root.removeAllViews()
        // The selection frame belongs to the screen, not to a row: every screen gets exactly one,
        // installed before the screen draws over it.
        com.scenix.tv.core.FocusGlass.install(root, ctx)
        return root
    }

    override fun currentScreenId(): String = stack.lastOrNull()?.screen?.id ?: ""

    override fun arg(): String? = stack.lastOrNull()?.arg

    override fun show(screenId: String, arg: String?) {
        val plugin = ctx.registries.screens.get(screenId)
        if (plugin == null) {
            Log.w(TAG, "no screen registered for \"$screenId\"")
            return
        }
        leaveCurrent()
        stack.add(Frame(plugin, arg))
        render(plugin)
    }

    private fun render(plugin: ScreenPlugin) {
        root.removeAllViews()
        plugin.render(this)
        root.requestFocus()
    }

    private fun leaveCurrent() {
        stack.lastOrNull()?.screen?.onLeave(this)
    }

    override fun openDetail(identity: String, seed: Item?) {
        if (seed != null) seeds[identity] = seed
        show("detail", identity)
    }

    override fun seedItem(identity: String): Item? = seeds[identity]

    override fun play(item: Item, source: com.scenix.tv.core.Source?) {
        seeds[item.identity] = item
        playReq = item to source
        show("player", item.identity)
    }

    override fun playRequest(): Pair<Item, com.scenix.tv.core.Source?>? = playReq

    override fun requireProfilePicker(reason: String?) {
        leaveCurrent()
        stack.clear()
        ctx.profile = null
        playReq = null
        show("profiles", reason)
    }

    override fun enterProfile(profile: Profile) {
        ctx.profile = profile
        ctx.prefs.setCurrentProfileId(profile.id)
        stack.clear()
        val deep = pendingDeepLink
        val deepPlay = pendingDeepPlay
        pendingDeepLink = null
        pendingDeepPlay = null
        if (!deepPlay.isNullOrBlank()) {
            seeds[deepPlay] = seeds[deepPlay] ?: Item(identity = deepPlay, title = "")
            openPlayerFor(deepPlay)
        } else if (!deep.isNullOrBlank()) {
            seeds[deep] = seeds[deep] ?: Item(identity = deep, title = "")
            show("detail", deep)
        } else {
            show("home", null)
        }
    }

    override fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    // ---- back ------------------------------------------------------------------------------

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val current = stack.lastOrNull() ?: run { super.onBackPressed(); return }
        if (current.screen.onBack(this)) return
        if (stack.size == 1) {
            leaveCurrent()
            finish()
            return
        }
        leaveCurrent()
        stack.removeAt(stack.size - 1)
        playReq = null
        render(stack.last().screen)
    }

    override fun onStop() {
        super.onStop()
        // 2 GB TV: while the app is backgrounded, the image cache is the first thing to give back.
        ctx.images.trim()
    }

    override fun onDestroy() {
        if (::watch.isInitialized) watch.stop()
        leaveCurrent()
        super.onDestroy()
    }

    private companion object { const val TAG = "ScenixActivity" }
}
