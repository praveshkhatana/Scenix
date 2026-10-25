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

/**
 * The whole app is one activity: a container and a stack of screens, where a screen is a
 * [ScreenPlugin] that renders views. That is what lets navigation be registry-driven — the nav bar
 * asks the registry for screens, and this file never names one (except the two it must reach by
 * force: the profile picker and the player).
 *
 * Two launch switches, both read here and nowhere else:
 *  - `--es hub_url http://host:8788`  → which hub to talk to
 *  - `--es fixture fixtures/home.json` → use the offline asset client instead of the network
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
    private lateinit var watch: com.scenix.tv.core.HubWatch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ctx = (application as ScenixApp).ctx

        applyLaunchSwitches()

        root = FrameLayout(this)
        root.setBackgroundColor(ctx.theme.bg)
        setContentView(root)

        val identity = intent?.data?.let { uri ->
            if (uri.scheme == "tv" && uri.host == "detail") uri.pathSegments.firstOrNull() else null
        }
        if (!identity.isNullOrBlank()) pendingDeepLink = identity

        // One health probe decides whether the whole app talks to the hub or to the bundled
        // fixtures, so the first screen never shows a blank page. It keeps retrying after that.
        watch = com.scenix.tv.core.HubWatch(this, ctx)
        watch.onChange = { online ->
            Log.i(TAG, if (online) "hub back, re-rendering" else "hub gone, re-rendering on fixtures")
            if (stack.isNotEmpty()) render(stack.last().screen)
        }
        watch.start { startup() }
    }

    /** Called once the data client for this session is known. */
    private fun startup() {
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

    /** `--es hub_url …` / `--es fixture …`, also reachable from Settings, and `tv://` links. */
    private fun applyLaunchSwitches() {
        val i = intent ?: return
        i.getStringExtra("hub_url")?.takeIf { it.isNotBlank() }?.let {
            ctx.prefs.setHubUrl(it.trim())
            Log.i(TAG, "hub_url override: ${it.trim()}")
        }
        i.getStringExtra("fixture")?.takeIf { it.isNotBlank() }?.let {
            ctx.prefs.setFixture(it)
            Log.i(TAG, "fixture override: $it")
        }
        val fixture = ctx.prefs.fixture()
        ctx.hub = if (!fixture.isNullOrBlank()) {
            FixtureHub(this, fixture)
        } else {
            HttpHub(this, ctx.prefs)
        }
    }

    // ---- ScreenHost ------------------------------------------------------------------------

    override val app: AppContext get() = ctx

    override fun container(): FrameLayout {
        root.removeAllViews()
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
        pendingDeepLink = null
        if (!deep.isNullOrBlank()) {
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
