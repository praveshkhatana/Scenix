package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Views
import java.io.ByteArrayInputStream

/**
 * Game mode's player: one game, full screen, no ads.
 *
 * The games are browser titles from their creators' own sites and the web arcades; we load them in
 * an uncluttered WebView rather than a portal page, which is what makes them ad-free — the portals'
 * advertising is carried by SDK hosts that never get asked for here. Where a portal does inject its
 * own ads (Poki loads IMA SDKs into the game itself), every ad and analytics host is answered with
 * an empty body before it can paint, and only the game's own runtime keeps talking.
 *
 * A real controller is the couch's answer to a keyboard: Android TV pairs it, Chromium hands it to
 * the page through the Gamepad API, and this screen reports when one shows up so nobody has to
 * wonder whether the buttons they are pressing are going anywhere. The remote still works for the
 * menus and for the games that read arrows.
 */
class GameActivity : Activity() {

    private lateinit var app: com.scenix.tv.core.AppContext
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var errorBox: LinearLayout
    private lateinit var hud: LinearLayout
    private lateinit var padChip: TextView
    private val main = Handler(Looper.getMainLooper())
    private var url = ""
    private var titleText = "Game"
    private var padReady = false
    private var hudVisible = false
    private var hidHud = Runnable { hideHud() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        url = intent?.getStringExtra(EXTRA_URL).orEmpty()
        titleText = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Game" }
        padReady = intent?.getBooleanExtra(EXTRA_PAD, false) ?: false
        if (url.isBlank()) { finish(); return }

        val theme = app.theme
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // A portal page appends "; wv" so sites can refuse embeds to WebViews. Here the game IS
            // the page — a plain Chrome identity is the honest one, and it stops ports from serving
            // their "open in a browser" fallback instead of starting.
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.setSupportZoom(false)
            setBackgroundColor(Color.BLACK)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView, request: WebResourceRequest,
                ): WebResourceResponse? {
                    val u = request.url
                    return if (isAdHost(u)) emptyBlock() else null
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    status.visibility = View.VISIBLE
                    errorBox.visibility = View.GONE
                }

                override fun onPageFinished(view: WebView, url: String) {
                    status.visibility = View.GONE
                }

                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError,
                ) {
                    if (!request.isForMainFrame) return
                    showError("This game could not be reached — ${error.description}")
                }
            }
        }
        root.addView(web, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        status = Views.text(app, "sub", 24f, theme.ink2, "Loading $titleText…", gravity = Gravity.CENTER)
        root.addView(status, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(80f)
        ).apply { gravity = Gravity.CENTER })

        errorBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        errorBox.addView(Views.text(app, "sub", 24f, theme.ink, titleText, bold = true, gravity = Gravity.CENTER))
        val errText = Views.text(app, "sub", 19f, theme.ink2, "", gravity = Gravity.CENTER)
        errorBox.addView(errText, LinearLayout.LayoutParams(
            theme.px(900f), ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = theme.px(14f) })
        val retry = Views.text(app, "meta", 17f, Color.WHITE, "Try again", bold = true, gravity = Gravity.CENTER)
        retry.setPadding(theme.px(26f), theme.px(12f), theme.px(26f), theme.px(12f))
        retry.background = Views.rounded(theme.surface2, theme.px(10f))
        retry.isFocusable = true
        Focus.attach(retry, retry, app, theme.px(10f), scale = false, elevate = false,
            onClick = {
                errorBox.visibility = View.GONE
                status.visibility = View.VISIBLE
                web.loadUrl(url)
            })
        errorBox.addView(retry, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = theme.px(28f) })
        root.addView(errorBox, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        errLine = errText
        retryBtn = retry

        hud = buildHud()
        hud.visibility = View.GONE
        root.addView(hud, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(132f)
        ).apply { gravity = Gravity.TOP })

        setContentView(root)
        web.requestFocus()
        web.loadUrl(url)
        watchControllers()
        if (!padReady) toast("This one is built for the remote — arrows and OK")
    }

    private lateinit var errLine: TextView
    private var retryBtn: View? = null
    private var connectBtn: View? = null

    /** The bar back presses summon: what this game needs, how to pair it, how to leave. */
    private fun buildHud(): LinearLayout {
        val theme = app.theme
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.gutter, 0, theme.gutter, 0)
            background = Views.rounded(Color.argb(216, 6, 6, 8), theme.px(14f))
        }
        bar.addView(Views.text(app, "rowTitle", 25f, theme.ink, titleText, bold = true, maxLines = 1),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        padChip = Views.text(app, "meta", 16f, theme.ink2, "", maxLines = 1)
        bar.addView(padChip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = theme.px(26f) })
        val connect = Views.text(app, "meta", 16f, Color.WHITE, "Connect controller", bold = true)
        connect.setPadding(theme.px(22f), theme.px(11f), theme.px(22f), theme.px(11f))
        connect.background = Views.rounded(theme.surface2, theme.px(9f))
        connect.isFocusable = true
        Focus.attach(connect, connect, app, theme.px(9f), scale = false, elevate = false,
            onClick = { pairController() })
        bar.addView(connect)
        connectBtn = connect
        bar.addView(Views.text(app, "meta", 15f, theme.ink3, "Back again to exit").apply {
            setPadding(theme.px(22f), 0, 0, 0)
        })
        return bar
    }

    private fun showError(msg: String) {
        errLine.text = msg
        errorBox.visibility = View.VISIBLE
        status.visibility = View.GONE
        retryBtn?.requestFocus()
    }

    private fun showHud() {
        hudVisible = true
        hud.alpha = 0f
        hud.visibility = View.VISIBLE
        hud.animate().alpha(1f).setDuration(140).start()
        updatePadChip()
        // Put the ring on the Connect button so the remote has somewhere to go.
        connectBtn?.requestFocus()
        main.removeCallbacks(hidHud)
        main.postDelayed(hidHud, 7000)
    }

    private fun hideHud() {
        if (!hudVisible) return
        hudVisible = false
        main.removeCallbacks(hidHud)
        hud.animate().alpha(0f).setDuration(140).withEndAction { hud.visibility = View.GONE }.start()
        web.requestFocus()
    }

    /** Any key while the bar is up keeps it up; keys themselves still fall through to the game. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && hudVisible) {
            main.removeCallbacks(hidHud)
            main.postDelayed(hidHud, 7000)
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Back is a game's most dangerous button: it quits without asking. The first press brings up the
     * bar and says what pressing again will do — exactly one more press leaves, anywhere in the UI.
     */
    override fun onBackPressed() {
        when {
            errorBox.visibility == View.VISIBLE -> finish()
            !hudVisible -> showHud()
            else -> super.onBackPressed()
        }
    }

    // ── Controllers ────────────────────────────────────────────────────────────────────────────

    private val inputMgr by lazy { getSystemService(INPUT_SERVICE) as InputManager }

    private fun controllers(): List<android.view.InputDevice> {
        val gamepad = android.view.InputDevice.SOURCE_GAMEPAD
        return try {
            android.view.InputDevice.getDeviceIds().toList().mapNotNull { id: Int ->
                try { inputMgr.getInputDevice(id) } catch (t: Throwable) { null }
            }.filter { d: android.view.InputDevice ->
                (d.sources and gamepad) == gamepad && !d.name.contains("virtual", true)
            }
        } catch (t: Throwable) { emptyList() }
    }

    private fun updatePadChip() {
        if (!::padChip.isInitialized) return
        val theme = app.theme
        padChip.text = if (controllers().isEmpty())
            if (padReady) "No controller yet — remote plays menus" else "Plays with the remote"
        else "Controller connected ✓"
        padChip.setTextColor(if (controllers().isEmpty()) theme.ink2 else theme.ink)
    }

    /** Say out loud when a pad arrives or leaves mid-game; nobody should have to guess. */
    private fun watchControllers() {
        try {
            inputMgr.registerInputDeviceListener(object : InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(id: Int) {
                    main.post {
                        val d = try { inputMgr.getInputDevice(id) } catch (t: Throwable) { null }
                        if (d != null && (d.sources and android.view.InputDevice.SOURCE_GAMEPAD) != 0 &&
                            !d.name.contains("virtual", true)) {
                            toast("${d.name} connected — the game should hear it now")
                            if (hudVisible) updatePadChip()
                        }
                    }
                }

                override fun onInputDeviceRemoved(id: Int) {
                    main.post {
                        if (controllers().isEmpty()) {
                            toast("Controller disconnected")
                            if (hudVisible) updatePadChip()
                        }
                    }
                }

                override fun onInputDeviceChanged(id: Int) {}
            }, main)
        } catch (t: Throwable) { /* pairing notices are a nicety, not the point of play */ }
    }

    /** The TV's own Bluetooth screen — the only place a DualSense or Xbox pad can be put into range. */
    private fun pairController() {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (t: Throwable) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (u: Throwable) {
                toast("Open Bluetooth settings in Android to pair a controller")
            }
        }
    }

    // ── Ads ─────────────────────────────────────────────────────────────────────────────────────

    private fun isAdHost(u: Uri): Boolean {
        val host = u.host?.lowercase() ?: return false
        val path = u.path?.lowercase().orEmpty()
        if (host == "a.poki.com" && path.startsWith("/t")) return true
        return AD_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun emptyBlock(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(byteArrayOf()))

    override fun onPause() {
        if (::web.isInitialized) web.onPause()   // freeze drawing while Bluetooth settings sit on top
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.onResume()
        updatePadChip()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PAD = "pad"

        /** Ad, analytics and ad-middleware hosts the web arcades reach for. The games never need them. */
        private val AD_HOSTS = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "google-analytics.com", "googletagmanager.com", "imasdk.googleapis.com",
            "2mdn.net", "bat.bing.com", "a.poki.com", "amazon-adsystem.com",
            "criteo.com", "criteo.net", "taboola.com", "moatads.com", "adnxs.com",
            "adsafeprotected.com", "smartadserver.com",
        )
    }
}
