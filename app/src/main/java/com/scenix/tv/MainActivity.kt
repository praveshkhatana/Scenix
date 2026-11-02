package com.scenix.tv

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Motion
import com.scenix.tv.core.Focus
import com.scenix.tv.core.HomeLayoutSpec
import com.scenix.tv.core.HubWatch
import com.scenix.tv.core.Prefs
import com.scenix.tv.core.Screens
import com.scenix.tv.core.ScreenHost
import com.scenix.tv.core.Views
import com.scenix.tv.plugins.nav.PairingScreen

/**
 * The launcher. The product is the native television surface over the hub's `/app/v1` contract. This
 * activity does only the work that must happen before a screen exists:
 *
 *  1. apply the launch switches (`--es hub_url …`, `--es fixture …`, `--es token …`, `--ez setup/standalone`);
 *  2. run [HubWatch] once to find a hub (or notice that this TV is standalone);
 *  3. if nothing is paired and nothing answers, show the native setup screen
 *     ([PairingScreen]) so a fresh TV can find or type a hub, or choose standalone;
 *  4. otherwise open the native profile gate (or the requested web route) and finish.
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
    /** The branded wait while the first hub probe is open; faded away when a screen takes over. */
    private var splashView: View? = null
    private var splashAlive = true

    /** `--ez setup true` or `tv://setup`: show the front door even when a backend is already paired. */
    private var forceSetup = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ctx = (application as ScenixApp).ctx

        applyLaunchSwitches()

        // QA direct-play: `am start -n com.scenix.tv/.MainActivity --es qa_url <url> [--es qa_title t]
        // [--ez qa_coi true] [--ez qa_pad true] [--ez qa_ua true]` opens one game in its own player.
        // The players aren't exported; this is the door the per-game load sweep walks through.
        intent.getStringExtra("qa_url")?.let { u ->
            if (u.isNotBlank()) {
                val play = if (intent.getBooleanExtra("qa_coi", false))
                    android.content.Intent(this, CoiGameActivity::class.java)
                else android.content.Intent(this, GameActivity::class.java)
                play.putExtra(GameActivity.EXTRA_URL, u)
                    .putExtra(GameActivity.EXTRA_TITLE, intent.getStringExtra("qa_title") ?: "")
                    .putExtra(GameActivity.EXTRA_FALLBACK, intent.getStringExtra("qa_fallback") ?: "")
                    .putExtra(GameActivity.EXTRA_PAD, intent.getBooleanExtra("qa_pad", false))
                    .putExtra(GameActivity.EXTRA_DESKTOP_UA, intent.getBooleanExtra("qa_ua", false))
                startActivity(play)
                finish()
                return
            }
        }

        root = FrameLayout(this)
        root.setBackgroundColor(ctx.theme.bg)
        // The splash is not a blank page while a probe runs: the pack's wall rises, the brand mark
        // fades up on it, and one quiet line says what the box is doing. Every pack ships its wall;
        // packs without one keep their plain ground. (screen pass 2026-11-03: splash → profile → PIN → home)
        Screens.wall(ctx)?.let { root.background = it }
        val splash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
        }
        val glassGround = ctx.theme.homeLayout.hero == HomeLayoutSpec.Hero.GLASS_CARDS
        splash.addView(ImageView(this).apply {
            setImageResource(R.drawable.scenix_wordmark)
            setColorFilter(if (glassGround) Color.WHITE else ctx.theme.brand)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Scenix"
        }, LinearLayout.LayoutParams(ctx.theme.px(220f), ctx.theme.px(50f)))
        splash.addView(Views.text(ctx, "sub", 22f,
            if (glassGround) ctx.theme.color("pillInk", "#B7C4D2") else ctx.theme.ink3,
            "Finding your hub…", gravity = Gravity.CENTER),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = ctx.theme.px(14f) })
        root.addView(splash, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })
        splashView = splash
        setContentView(root)
        Motion.enter(app.theme, root)
        if (app.theme.motionEnabled) {
            splash.alpha = 0f
            splash.animate().alpha(1f).setStartDelay(120L).setDuration(500L).start()
            // The status line breathes while the probe is open, so the box reads as working, not hung.
            breathe(splash.getChildAt(1))
        }

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
        leaveSplash()
        if (!online && watch.unpaired) {
            showPairing()
            return
        }
        openShell(pendingRoute)
    }

    private fun breathe(v: View) {
        if (!splashAlive || isFinishing) return
        v.animate().alpha(0.45f).setDuration(900L).withEndAction {
            if (splashAlive && !isFinishing)
                v.animate().alpha(1f).setDuration(900L).withEndAction { breathe(v) }.start()
        }.start()
    }

    /** The branded wait fades out as a screen takes over — exits stay shorter than entries. */
    private fun leaveSplash() {
        splashAlive = false
        val s = splashView ?: return
        splashView = null
        if (!ctx.theme.motionEnabled) {
            s.visibility = View.GONE
            return
        }
        s.animate().alpha(0f).setDuration(220L).withEndAction { s.visibility = View.GONE }.start()
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
        Log.i(TAG, if (route != null) "opening the profile gate for $route" else "opening the native profile gate")
        // A relaunch from the TV home screen lands on top of the task that was left behind (Browse,
        // Detail, maybe a paused player). Starting the gate in a cleared task means a person switch
        // cannot walk back into the previous person's player. A deep link keeps its route through the
        // native gate and opens the native screen once a profile is chosen; no WebView is involved.
        val next = Intent(this, ProfilesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (route != null && route.startsWith("#/")) next.putExtra(ProfilesActivity.EXTRA_ROUTE, route)
        startActivity(next)
        // The gate rises into the splash instead of snapping over it (§4 screen-enter law).
        overridePendingTransition(R.anim.scenix_enter, R.anim.scenix_exit)
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
