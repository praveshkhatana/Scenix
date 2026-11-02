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
    private lateinit var loader: GameLoader
    private val main = Handler(Looper.getMainLooper())
    private var url = ""
    private var fallbackUrl = ""
    private var usedFallback = false
    private var needsCoi = false
    private var titleText = "Game"
    private var padReady = false
    private var desktopUa = false
    private var hudVisible = false
    // True from the first pass that found the game's stage: the remote becomes a finger only
    // once there is a stage to touch.
    private var stageUp = false
    private var hidHud = Runnable { hideHud() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        url = intent?.getStringExtra(EXTRA_URL).orEmpty()
        fallbackUrl = intent?.getStringExtra(EXTRA_FALLBACK).orEmpty()
        titleText = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Game" }
        padReady = intent?.getBooleanExtra(EXTRA_PAD, false) ?: false
        needsCoi = intent?.getBooleanExtra(EXTRA_COI, false) ?: false
        desktopUa = intent?.getBooleanExtra(EXTRA_DESKTOP_UA, false) ?: false
        if (url.isBlank()) { finish(); return }

        val theme = app.theme
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // Devtools on the built-in browser: `adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>`
        // and the portal's live DOM is inspectable — the only way to see what a portal page really does.
        WebView.setWebContentsDebuggingEnabled(true)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // A portal page appends "; wv" so sites can refuse embeds to WebViews. Here the game IS
            // the page — a plain Chrome identity is the honest one, and it stops ports from serving
            // their "open in a browser" fallback instead of starting.
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            // A few web arcades (the GameDistribution family behind miniplay, which carries the real
            // Subway Surfers) answer their mobile browser a bare 404 and only serve the page to a
            // desktop one. Those catalogue entries say ua:"desktop" and get this identity.
            if (desktopUa) settings.userAgentString =
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.setSupportZoom(false)
            setBackgroundColor(Color.BLACK)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView, request: WebResourceRequest,
                ): WebResourceResponse? {
                    val u = request.url
                    // Some mirrors ship the game from their own host and the tray of "related games"
                    // rides in with a little promo script inside the game frame — a frame this
                    // WebView's script can never reach (cross-origin), so the tray must die where
                    // it is requested. The prebid bundle is the ad auction the same portals run.
                    val path = u.path?.lowercase().orEmpty()
                    if (isAdHost(u) || path.endsWith("/files/scripts/scr.js") || "/prebid" in path)
                        return emptyBlock()
                    return null
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    status.visibility = View.VISIBLE
                    errorBox.visibility = View.GONE
                    loader.show()
                }

                override fun onPageFinished(view: WebView, url: String) {
                    status.visibility = View.GONE
                    // The page is up but a web arcade game still streams its engine and art after
                    // load — the wordmark keeps sweeping until the stage itself lands.
                    loader.loop()
                    // Poki and friends wrap the game in tiles, nav pills and late popups a kid
                    // shouldn't reach: carve the page down to the game's own container.
                    carve(view)
                    // Portal games play with finger swipes; a TV remote has no fingers. The remote
                    // becomes one: arrows are swipes and OK a tap on the biggest canvas — only when
                    // the page itself left the key unconsumed, so keyboard-native games are untouched.
                    view.evaluateJavascript(REMOTE_JS) { }
                    // The catalog flags ports whose own servers demand cross-origin isolation: those
                    // are multithreaded builds, and Android WebView never grants SharedArrayBuffer in
                    // any version. A single-threaded mirror gets its one hop first; otherwise say so
                    // plainly instead of leaving a black screen on screen forever.
                    if (needsCoi && !usedFallback) view.evaluateJavascript("String(!!crossOriginIsolated)") { r ->
                        if (r?.contains("true") != true) {
                            if (!switchToBackup("This port wants multithreading this WebView lacks"))
                                showError("This port is built multithreaded — Android's built-in browser component can't run it. Open it in a full browser, or look for a single-threaded build of the same game.")
                        }
                    }
                    // A host that answers 200-but-blocked shows only a sentence where the game should
                    // be; catch that too so the fallback still gets its turn before we give up.
                    view.evaluateJavascript(
                        "(document.body && document.body.innerText || '').slice(0,400)"
                    ) { raw ->
                        val text = raw?.removeSurrounding("\"")?.replace("\\n", " ") ?: ""
                        val blocked = text.contains("available in your region", true) ||
                            text.contains("disponible en tu región", true)
                        if (blocked) {
                            if (!switchToBackup("This host turns away your region")) {
                                showError("The game's home refuses to serve this region — add a mirror for it or try another title.")
                            }
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError,
                ) {
                    if (!request.isForMainFrame) return
                    android.util.Log.i("ScenixGame", "loaderror ${request.url} ${error.errorCode}")
                    if (!switchToBackup("This game could not be reached")) {
                        showError("This game could not be reached — ${error.description}")
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse,
                ) {
                    if (!request.isForMainFrame) return
                    if (errorResponse.statusCode == 403 || errorResponse.statusCode == 451) {
                        if (!switchToBackup("This host turns away your region")) {
                            showError("The game's home refuses to serve this region (HTTP ${errorResponse.statusCode}).")
                        }
                    }
                }
            }

            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onProgressChanged(view: WebView?, progress: Int) {
                    // Real page-load progress paints the wordmark fill honestly, up to 95% —
                    // Chromium reports 100 the moment the document settles, long before the game does.
                    if (progress > 0) loader.setProgress(Math.min(progress, 95) / 100f)
                }

                // Port sites announce themselves with alert() and gate their load on confirm() —
                // a dialog nobody can answer on a TV freezes the game's own script thread forever.
                // In this container the dialogs answer themselves, unseen, so the script moves on.
                override fun onJsAlert(
                    view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?,
                ): Boolean {
                    android.util.Log.i("ScenixGame", "jsalert ${message?.take(80)}")
                    result?.cancel()
                    return true
                }

                override fun onJsConfirm(
                    view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?,
                ): Boolean {
                    android.util.Log.i("ScenixGame", "jsconfirm ${message?.take(80)}")
                    result?.confirm()
                    return true
                }

                override fun onJsPrompt(
                    view: WebView?, url: String?, message: String?, defaultValue: String?,
                    result: android.webkit.JsPromptResult?,
                ): Boolean {
                    android.util.Log.i("ScenixGame", "jsprompt ${message?.take(80)}")
                    result?.confirm(defaultValue ?: "")
                    return true
                }
            }
        }
        root.addView(web, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        loader = GameLoader(app)
        loader.attach(root)
        loader.show()

        status = Views.text(app, "sub", 24f, theme.ink2, "Loading $titleText…", gravity = Gravity.CENTER)
        root.addView(status, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(80f)
        ).apply { gravity = Gravity.CENTER; topMargin = theme.px(150f) })

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
        loader.finish()
        retryBtn?.requestFocus()
    }

    // ── Fallback links ────────────────────────────────────────────────────────────────────────
    // Some ports' homes refuse certain countries outright (Tekken 4 answers a flat 403 here). The
    // catalog may carry a second address for exactly those titles: one silent hop to it before
    // showing an error, one hop only — if the backup fails too, the viewer sees why.

    /** True when the hop happened; false when there is no backup or we already used it. */
    private fun switchToBackup(reason: String): Boolean {
        if (usedFallback || fallbackUrl.isBlank()) return false
        usedFallback = true
        url = fallbackUrl
        toast("$reason — opening its backup link instead")
        web.loadUrl(fallbackUrl)
        return true
    }

    // ── Carving to the game container ───────────────────────────────────────────────────────────
    // Portal pages (Poki and friends) wrap their game in thumbnails, nav pills and late-injected
    // promo popups — content nobody asked for and not every parent wants a kid to see. The page's
    // biggest visible iframe/canvas/video is taken as THE game: everything off the path from <body>
    // down to it is hidden and the stage pinned full-screen. Only display styles are touched, so the
    // page's scripts and SDK keep running. Popups land at random times after load, so the sweep
    // keeps re-running on a slow tick for the whole session, not just until one carve lands.

    private fun carve(view: WebView) {
        object : Runnable {
            var passes = 0
            var carved = 0
            var misses = 0
            override fun run() {
                if (++passes > 60) return
                view.evaluateJavascript(CARVE_JS) { r ->
                    android.util.Log.i("ScenixGame", "carve p=$passes res=${r?.take(60)}")
                    if (r?.contains("carved") == true) {
                        ++carved
                        stageUp = true
                        // The game's own frame is on screen — the wordmark hands the stage over to it.
                        loader.finish()
                    } else ++misses
                    // The page told us in its own words why its game never boots here (no WebGPU,
                    // no adapter): say that instead of guessing at a stall.
                    val why = r?.substringAfter("|refuse:", "")?.trim('"')?.trim().orEmpty()
                    if (why.isNotEmpty() && why != r) main.post {
                        status.text = why
                        status.visibility = View.VISIBLE
                    }
                    // A portal that never puts any game frame on screen at all (Poki on some
                    // engines): after half a minute say so instead of sitting black forever.
                    if (misses == 10 && carved == 0) {
                        android.util.Log.i("ScenixGame", "never started")
                        toast("This portal hasn't started its game on this TV — try another title.")
                    }
                    main.postDelayed(this, if (carved > 0) 5000 else 3000)
                }
            }
        }.also { main.postDelayed(it, 800) }
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
        // The remote as a finger. Portal games are built for thumbs: they watch touch, not keys,
        // and the game often lives in a frame our script can't even see into. So once the stage
        // is up and the bar is away, a remote swipe becomes a real MotionEvent on the WebView
        // (trusted, crosses into any frame) and OK becomes a tap. Keys are consumed, not sent —
        // a game gets one input language, not two at once. Emulator/console ports read real
        // gamepads through the Gamepad API; those keep working untouched.
        if (!hudVisible && stageUp && !isFinishing) {
            val k = event.keyCode
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT ||
                k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN ||
                k == KeyEvent.KEYCODE_DPAD_CENTER) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) finger(k)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** One finger gesture along the WebView: swipe for arrows, tap for OK. */
    private fun finger(k: Int) {
        val w = web.width.toFloat()
        val h = web.height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val dx = minOf(w * 0.22f, 260f)
        val dy = minOf(h * 0.22f, 260f)
        val points: Array<FloatArray> = when (k) {
            KeyEvent.KEYCODE_DPAD_CENTER -> arrayOf(floatArrayOf(cx, cy), floatArrayOf(cx, cy))
            KeyEvent.KEYCODE_DPAD_LEFT -> Array(5) { i -> floatArrayOf(cx + dx - dx * 2 * i / 4f, cy) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> Array(5) { i -> floatArrayOf(cx - dx + dx * 2 * i / 4f, cy) }
            KeyEvent.KEYCODE_DPAD_UP -> Array(5) { i -> floatArrayOf(cx, cy + dy - dy * 2 * i / 4f) }
            else -> Array(5) { i -> floatArrayOf(cx, cy - dy + dy * 2 * i / 4f) }
        }
        val t0 = android.os.SystemClock.uptimeMillis()
        for (step in points.indices) {
            val p = points[step]
            val last = step == points.size - 1
            val what = when {
                step == 0 -> android.view.MotionEvent.ACTION_DOWN
                last && points.size == 2 -> android.view.MotionEvent.ACTION_UP  // a tap: hold 70ms
                last -> android.view.MotionEvent.ACTION_UP
                else -> android.view.MotionEvent.ACTION_MOVE
            }
            val delay = if (last && points.size == 2) 70L else step * 30L
            val ev = android.view.MotionEvent.obtain(t0, t0 + delay, what, p[0], p[1], 0)
            main.postDelayed({ web.dispatchTouchEvent(ev) }, delay)
        }
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

    /** The app's own controller screen — it shows what is connected, tests buttons, and opens
     *  Android's Bluetooth pairing from there (pairing itself is Android's job on a TV). */
    private fun pairController() {
        try {
            startActivity(Intent(this, ControllersActivity::class.java))
        } catch (t: Throwable) {
            try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (u: Throwable) {
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

    // A silent 200 with an empty body looks like a successful ad response whose JSON happens to be
    // unparseable, and some SDKs stall on that forever. 403 reads as "ad refused", which every
    // portal already handles, and the CORS header keeps fetch-based bidders from retry-storming.
    private fun emptyBlock(): WebResourceResponse =
        WebResourceResponse(
            "text/plain", "utf-8", 403, "Blocked",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(byteArrayOf()),
        )

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
        const val EXTRA_FALLBACK = "fallback"
        const val EXTRA_COI = "coi"
        const val EXTRA_DESKTOP_UA = "desktop_ua"

        /**
         * The remote as a finger: arrows become swipes across the biggest canvas and OK a tap on
         * its centre — but only when the page left the key unconsumed, so games that already read
         * the keyboard keep their own hands. The Gecko twin lives in scenix-carve/fake.js; change
         * both together. Idempotent.
         */
        const val REMOTE_JS = """
(function(){
 if(window.__scenixRemote)return;window.__scenixRemote=1;
 var last=0;
 function stage(){var best=null,area=0;
  try{var cs=document.querySelectorAll('canvas');
   for(var i=0;i<cs.length;i++){var r=cs[i].getBoundingClientRect();var a=r.width*r.height;
    if(a>area){area=a;best=cs[i];}}}catch(e){}
  return best||document.body||document.documentElement;}
 function T(el,x,y){try{return new Touch({identifier:1,target:el,clientX:x,clientY:y});}catch(e){return null;}}
 function fire(el,kind,x,y){try{var t=T(el,x,y);if(!t)return;
  el.dispatchEvent(new TouchEvent(kind,{bubbles:true,cancelable:true,
   touches:kind==='touchend'?[]:[t],targetTouches:kind==='touchend'?[]:[t],changedTouches:[t]}));}catch(e){}}
 window.addEventListener('keydown',function(e){
  if(e.repeat)return;var k=e.key;
  if(k!=='ArrowLeft'&&k!=='ArrowRight'&&k!=='ArrowUp'&&k!=='ArrowDown'&&k!=='Enter')return;
  setTimeout(function(){
   if(e.defaultPrevented)return;
   var now=Date.now();if(now-last<180)return;last=now;
   try{
    var el=stage(),r=el.getBoundingClientRect();
    if(k==='Enter'){var cx0=r.left+r.width/2,cy0=r.top+r.height/2;
     fire(el,'touchstart',cx0,cy0);setTimeout(function(){fire(el,'touchend',cx0,cy0);},60);return;}
    var cx=r.left+r.width/2,cy=r.top+r.height/2;
    var dx=Math.min(r.width*0.3,220),dy=Math.min(r.height*0.3,220);
    var p=[cx,cy];
    if(k==='ArrowLeft')p=[cx+dx,cy,cx-dx,cy];
    if(k==='ArrowRight')p=[cx-dx,cy,cx+dx,cy];
    if(k==='ArrowUp')p=[cx,cy+dy,cx,cy-dy];
    if(k==='ArrowDown')p=[cx,cy-dy,cx,cy+dy];
    fire(el,'touchstart',p[0],p[1]);
    for(var i=1;i<=4;i++)(function(i){setTimeout(function(){
      var x=p[0]+(p[2]-p[0])*i/4,y=p[1]+(p[3]-p[1])*i/4;
      fire(el,'touchmove',x,y);
      if(i===4)fire(el,'touchend',x,y);
    },i*30);})(i);
   }catch(t){}
  },0);
 },true);
})();
"""

        /**
         * Keep only the path to the page's biggest visible game frame and pin its stage full-screen.
         * Portal chrome, promo confetti and ad overlays are cut wherever they live — around the frame
         * in the main document AND inside same-origin frames the portals wrap games in. A portal
         * preview <video> is NOT a game frame. Every pass first restores whatever the last one hid,
         * so when a portal swaps its DOM mid-session the page is never stranded black. Answers
         * 'carved'/'none'. Idempotent.
         */
        const val CARVE_JS = """
(function(){
 try{
  var hid=window.__scenixHid||(window.__scenixHid=[]);
  while(hid.length){try{hid.pop().style.removeProperty('display');}catch(x){}}
  var pin=window.__scenixPin||(window.__scenixPin=[]);
  while(pin.length){var p0=pin.pop();try{p0.style.removeProperty('position');p0.style.removeProperty('left');
    p0.style.removeProperty('top');p0.style.removeProperty('width');p0.style.removeProperty('height');
    p0.style.removeProperty('z-index');}catch(x){}}
  function carveDoc(doc,isMain){
   var v=doc.defaultView||window,W=v.innerWidth||1,H=v.innerHeight||1;
   var mark=doc.querySelectorAll('[data-scn]');for(var r=0;r<mark.length;r++)mark[r].removeAttribute('data-scn');
   function area(e){try{var b=e.getBoundingClientRect();
     var x=Math.min(b.right,W)-Math.max(b.left,0),y=Math.min(b.bottom,H)-Math.max(b.top,0);
     return x>0&&y>0?x*y:0;}catch(x){return 0;}}
   var pick=null,best=0,ns=doc.querySelectorAll('iframe,canvas,video');
   for(var i=0;i<ns.length;i++){var n=ns[i];
     if(n.getAttribute&&n.getAttribute('aria-hidden')==='true')continue; // decorative art, not a game
     var a=area(n)*(n.tagName==='IFRAME'?2:1);if(a>best){best=a;pick=n;}}
   if(!pick||area(pick)<W*H*0.04)return false;
   var node=pick,na=area(pick);
   while(node.parentElement&&node.parentElement!==doc.body){var pa=area(node.parentElement);
     if(pa>na*1.7)break;node=node.parentElement;na=pa;}
   var chain=[];for(var p=node;p;p=p.parentElement)chain.unshift(p);
   doc.documentElement.setAttribute('data-scn','keep');
   for(var j=0;j<chain.length;j++)chain[j].setAttribute('data-scn','keep');
   function hideOff(parent,keep){var s=parent.children;
     for(var k=0;k<s.length;k++)if(s[k]!==keep){s[k].style.setProperty('display','none','important');hid.push(s[k]);}}
   // The chain runs html→body→…→stage, so the pair loop already trims every level including
   // body's other children. A separate hideOff(body, chain[0]) here asked body to keep the
   // <html> element — never one of its children — and hid every child of body: the game's own
   // wrapper included. That is the blank Subway Surfers screen.
   for(var l=0;l<chain.length-1;l++)hideOff(chain[l],chain[l+1]);
   if(isMain){
    doc.documentElement.style.setProperty('background','#000','important');
    doc.body.style.setProperty('margin','0','important');
    doc.body.style.setProperty('background','#000','important');
    node.style.setProperty('position','fixed','important');
    node.style.setProperty('left','0','important');
    node.style.setProperty('top','0','important');
    node.style.setProperty('width','100vw','important');
    node.style.setProperty('height','100vh','important');
    node.style.setProperty('z-index','2147483000','important');
    pin.push(node);
   }
   // Anything that floats OVER the stage without being part of it: popups, promo confetti, ad panels.
   // The keep-chain's own ancestors must stay visible (they hold the stage), so the test is
   // containment against the stage node — not the [data-scn] marks.
   function outsideStage(e){return e!==node&&!node.contains(e)&&!e.contains(node);}
   var all=doc.getElementsByTagName('*');
   for(var t=0;t<all.length;t++){var e=all[t];
    if(!outsideStage(e))continue;
    try{var cs=v.getComputedStyle(e);
     if((cs.position==='fixed'||cs.position==='absolute')&&(area(e)>W*H*0.02||parseInt(cs.zIndex,10)>=9)){
      e.style.setProperty('display','none','important');hid.push(e);}}catch(x){}}
   promoPass(doc,node);
   if(!v.__scenixGuard){v.__scenixGuard=1;
    new v.MutationObserver(function(ms){
     for(var m=0;m<ms.length;m++){var add=ms[m].addedNodes||[];
      for(var q=0;q<add.length;q++){var el=add[q];
       if(el.nodeType!==1)continue;
       if(el.tagName==='SCRIPT'||el.tagName==='STYLE')continue;
       if(!outsideStage(el))continue;
       var pos=false;try{var c2=v.getComputedStyle(el);pos=c2.position==='fixed'||c2.position==='absolute';}catch(x){}
       if(pos){el.style.setProperty('display','none','important');hid.push(el);continue;}
       try{if(el.querySelector&&!el.querySelector('canvas,video')&&
           el.querySelectorAll('a,button').length>=3&&el.querySelectorAll('img').length>=2){
            el.style.setProperty('display','none','important');hid.push(el);}}catch(x){}
      }}}).observe(doc.documentElement,{childList:true,subtree:true});
   }
   return true;
  }
  // Promo banners and cross-promo tile rows: a strip of game tiles always goes; a small floating
  // notice with its own close button goes too — unless it reads like loader feedback. Runs even
  // when no stage exists yet, so an unbooted page never shows chrome floating over black.
  function promoPass(doc,node){
   var v=doc.defaultView||window,W=v.innerWidth||1,H=v.innerHeight||1;
   var els=doc.querySelectorAll('div,section,aside');
   for(var i=0;i<els.length;i++){var e=els[i];
    if(node&&(e===node||node.contains(e)||e.contains(node)))continue;
    try{
     if(e.querySelector('canvas,video,iframe'))continue;
     var b=e.getBoundingClientRect();if(b.width<80||b.height<24)continue;
     var links=e.querySelectorAll('a,button').length;
     if(links>=3&&e.querySelectorAll('img').length>=2){e.style.setProperty('display','none','important');hid.push(e);continue;}
     var cs=v.getComputedStyle(e);
     if(cs.position!=='fixed'&&cs.position!=='absolute'&&cs.position!=='sticky')continue;
     if(links<1)continue;
     if(b.height>=H*0.35&&b.width>=W*0.6)continue; // big overlays are gates, not banners
     var t=(e.textContent||'').replace(/\s+/g,' ').trim();
     if(!t||t.length>220)continue;
     if(/down|loading|extract|install|verif|buffer|memory|insert|pause|started|ready/i.test(t))continue;
     e.style.setProperty('display','none','important');hid.push(e);
    }catch(x){}}
  }
  var ok=carveDoc(document,true);
  if(!ok)promoPass(document,null);
  function dive(doc,d){if(d>2)return;var fs=doc.querySelectorAll('iframe');
   for(var i=0;i<fs.length;i++){try{var cd=fs[i].contentDocument;
    if(cd&&cd.body){if(carveDoc(cd,false))ok=true;else promoPass(cd,null);dive(cd,d+1);}}catch(x){}}} // cross-origin frames: blocked, skipped
  dive(document,0);
  // A port that cannot boot on this engine says why in its own words ("This browser has no
  // WebGPU…", "WebGL is not supported…"). The sweep hides that line with the portal chrome,
  // leaving a black screen and no reason; hand it to the app so it can say it out loud.
  var refuse="";
  try{
   var seen=window.__scenixRefused||(window.__scenixRefused={});
   var REFUSE=/(has no WebGPU|no WebGPU|no usable graphics adapter|cross-origin isolation|WebGPU is switched on|WebGL[^.]{0,30}(not|no)|not supported by this browser|requires? (a browser|WebGPU|WebGL2?)|Use a (current|modern)|needs? (a|an) (modern|updated|current))/i;
   var els=document.querySelectorAll("div,section,main,output,p");
   for(var q=0;q<els.length;q++){var el=els[q];
    if(el.querySelector&&el.querySelector("canvas,iframe,video"))continue;
    var t=(el.textContent||"").replace(/\s+/g," ").trim();
    if(!t||t.length<12||t.length>240||!REFUSE.test(t)||seen[t])continue;
    seen[t]=1;refuse=t;break;}
  }catch(x){}
  return (ok?'carved':'none')+(refuse?'|refuse:'+refuse:'');
 }catch(e){}
})()
"""

        /** Ad, analytics and ad-middleware hosts the web arcades reach for. The games never need them. */
        private val AD_HOSTS = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "google-analytics.com", "googletagmanager.com", "imasdk.googleapis.com",
            "2mdn.net", "bat.bing.com", "a.poki.com", "amazon-adsystem.com",
            "criteo.com", "criteo.net", "taboola.com", "moatads.com", "adnxs.com",
            "adsafeprotected.com", "smartadserver.com",
            "mc.yandex.ru", "mc.yandex.net", "mc.yandex.com", "an.yandex.ru", "yandex.ru",
            "yandex.net", "yandex.com",
        )
    }
}
