package com.scenix.tv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Focus
import com.scenix.tv.core.HubWatch
import com.scenix.tv.core.Prefs
import com.scenix.tv.core.ScreenHost
import com.scenix.tv.plugins.nav.PairingScreen

/**
 * The launcher. There is one product — the hub's web UI in [WebShellActivity] over this device's
 * native player — so this activity does only the work that must happen before the page exists:
 *
 *  1. apply the launch switches (`--es hub_url …`, `--es fixture …`, `--es token …`, `--ez setup/standalone`);
 *  2. run [HubWatch] once to find a hub (or notice that this TV is standalone);
 *  3. if nothing is paired and nothing answers, show the native setup screen
 *     ([PairingScreen]) so a fresh TV can find or type a hub, or choose standalone;
 *  4. otherwise open [WebShellActivity] and finish.
 *
 * Deep links `tv://detail/<identity>` and `tv://play/<identity>` become the hub's own page route
 * (`#/detail/<identity>`, `#/play/<identity>`) and are handed to the shell as its `url` extra. A
 * deep link that arrives before setup keeps the route and opens it once the viewer chooses a hub.
 */
class MainActivity : AppCompatActivity(), ScreenHost {

    private lateinit var root: FrameLayout
    private lateinit var ctx: AppContext
    private lateinit var watch: HubWatch

    private var screen = ""
    private var rendering = false
    private var pendingRoute: String? = null

    /** `--ez setup true` or `tv://setup`: show the front door even when a backend is already paired. */
    private var forceSetup = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ctx = (application as ScenixApp).ctx

        applyLaunchSwitches()

        root = FrameLayout(this)
        root.setBackgroundColor(ctx.theme.bg)
        setContentView(root)

        pendingRoute = deepRoute(intent)
        forceSetup = wantsSetup(intent)

        // A forced setup is a repair, not a probe: the viewer asked to see the front door, so do not
        // race a LAN search that could open the shell underneath it.
        if (forceSetup) {
            // A repair pass must not trust yesterday's list: it may contain a router that answered
            // SSDP once, or a hub that has since moved. The address field still carries the saved one.
            // The previous activity's watcher is stopped here too, so choosing a backend does not
            // restart a watcher that belongs to a screen that no longer exists.
            ctx.watch?.stop()
            ctx.watch = null
            ctx.prefs.setDiscoveredHubs(emptyList())
            showPairing()
            return
        }

        // One probe decides whether there is a page to open. The pairing screen handles the "no
        // hub yet" answer; every other path is the web shell.
        watch = HubWatch(this, ctx)
        ctx.watch = watch
        watch.start { online -> runOnUiThread { startup(online) } }
    }

    /**
     * A `tv://` link that arrives before the shell exists (the setup screen is up, or the first probe
     * is still running). After the shell is open this activity is finished, so a later link starts a
     * fresh instance through onCreate.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val route = deepRoute(intent) ?: return
        pendingRoute = route
        if (screen == PairingScreen.id) {
            // Keep the setup screen up; choosing a hub/standalone opens this route.
            refresh()
        }
    }

    /** Called once the first hub probe (or the standalone check) has an answer. */
    private fun startup(online: Boolean) {
        if (!online && watch.unpaired) {
            showPairing()
            return
        }
        openShell(pendingRoute)
    }

    /**
     * `--es hub_url …`, `--es fixture …`, `--ez standalone true` and `--ez setup true`: development
     * and recovery switches, read before the first probe. They write the same preferences the setup
     * screen writes, so the next launch behaves like any other.
     */
    private fun applyLaunchSwitches() {
        val i = intent ?: return
        i.getStringExtra("hub_url")?.takeIf { it.isNotBlank() }?.let {
            ctx.prefs.setHubUrl(it.trim())
            ctx.prefs.setBackendChoice(Prefs.BACKEND_HUB)
            // Naming a hub means "show me the real library"; a pinned offline fixture must not
            // survive it.
            ctx.prefs.setFixture("")
            Log.i(TAG, "hub_url override: ${it.trim()} (offline preview cleared)")
        }
        i.getStringExtra("fixture")?.takeIf { it.isNotBlank() }?.let {
            ctx.prefs.setFixture(it)
            Log.i(TAG, "fixture override: $it")
        }
        i.getStringExtra("token")?.let { ctx.prefs.setHubToken(it) }
        if (i.getBooleanExtra("standalone", false)) {
            ctx.prefs.setBackendChoice(Prefs.BACKEND_STANDALONE)
            ctx.prefs.setFixture("")
            Log.i(TAG, "standalone override")
        }
    }

    /** `tv://setup` and `--ez setup true` both mean "show me the hub/standalone front door". */
    private fun wantsSetup(i: Intent?): Boolean {
        if (i == null) return false
        if (i.getBooleanExtra("setup", false)) return true
        val uri = i.data ?: return false
        return uri.scheme.equals("tv", true) &&
            (uri.host.equals("setup", true) || uri.pathSegments.firstOrNull().equals("setup", true))
    }

    /** `tv://detail/<identity>` / `tv://play/<identity>` → the page route to load. */
    private fun deepRoute(i: Intent?): String? {
        val uri = i?.data ?: return null
        if (!uri.scheme.equals("tv", true)) return null
        val first = uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        if (uri.host.equals("setup", true) || first.equals("setup", true)) return null
        val kind = if ((uri.host ?: "detail").equals("play", true)) "play" else "detail"
        return "#/$kind/${Uri.encode(first)}"
    }

    private fun showPairing() {
        PairingScreen.reset()
        screen = PairingScreen.id
        rendering = true
        PairingScreen.render(this)
        rendering = false
        root.post { Focus.focusFirst(root) }
    }

    private fun openShell(route: String?) {
        Log.i(TAG, "opening the web shell${route?.let { " at $it" } ?: ""}")
        startActivity(
            Intent(this, WebShellActivity::class.java)
                .putExtra(WebShellActivity.EXTRA_URL, WebShellActivity.shellUrl(this, route ?: ""))
        )
        finish()
    }

    // ---- ScreenHost ------------------------------------------------------------------------

    override val app: AppContext get() = ctx

    override fun container(): FrameLayout {
        root.removeAllViews()
        return root
    }

    override fun currentScreenId(): String = screen

    override fun refresh() {
        if (rendering) return
        if (screen == PairingScreen.id) showPairing()
    }

    override fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun openShell() = openShell(pendingRoute)

    // ---- lifecycle -------------------------------------------------------------------------

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen == PairingScreen.id) {
            // Setup opened from a working shell is a repair visit: Back cancels it and returns to the
            // shell already underneath, instead of opening a second one.
            if (forceSetup && (ctx.prefs.standalone() || ctx.prefs.backendChoice().isNotBlank())) {
                finish()
                return
            }
            finish()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (::watch.isInitialized) watch.stop()
        super.onDestroy()
    }

    private companion object { const val TAG = "ScenixActivity" }
}
