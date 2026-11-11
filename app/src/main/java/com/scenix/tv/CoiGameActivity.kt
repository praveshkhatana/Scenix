package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.scenix.tv.core.Focus
import com.scenix.tv.core.GameSessions
import com.scenix.tv.core.Views
import java.io.File
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

/**
 * Game mode's player for the multithreaded WASM ports (the catalog's `coi` flag).
 *
 * Android's built-in WebView never grants crossOriginIsolated/SharedArrayBuffer in any version,
 * so ports built with pthreads black-screen there. This screen runs those games on a full
 * browser engine carried inside our own APK — GeckoView. It supports the isolation headers, it
 * ships armeabi-v7a and arm64-v8a (the cheap 2 GB TVs are 32-bit), and it adds no dependency on
 * any browser app the TV may not have.
 *
 * The same stage-containment sweep GameActivity uses cuts the portal chrome away; GeckoView has
 * no host-side script eval any more, so it rides along as a built-in WebExtension content script
 * that reports every pass back over a port. The notices — game never started, region refused,
 * controller arrived — are the ones the WebView player already gives.
 */
class CoiGameActivity : Activity() {

    private lateinit var app: com.scenix.tv.core.AppContext
    private lateinit var gecko: GeckoView
    private lateinit var status: TextView
    private lateinit var errorBox: LinearLayout
    private lateinit var errLine: TextView
    private val main = Handler(Looper.getMainLooper())
    private var runtime: GeckoRuntime? = null
    private var session: GeckoSession? = null
    private var extension: WebExtension? = null
    private var carveDelegate: WebExtension.MessageDelegate? = null
    private var url = ""
    private var fallbackUrl = ""
    private var usedFallback = false
    private var titleText = "Game"
    /** The hub's stable game id: the key the page's session history is stored under. */
    private var sessionIdentity = ""
    private var padReady = false
    private var painted = false
    private var openedAt = 0L
    private var carvedPasses = 0
    private var missStreak = 0
    private var stallTold = false
    private var sawCanvasAt = 0L
    // A relayed refusal ("This browser has no WebGPU…") owns the status line for the session:
    // paint events must not hide it again and the canvas pass must not re-raise the hint.
    private var refuseShown = false
    private var hint: TextView? = null
    private var hintTold = false
    private lateinit var loader: GameLoader
    private var hud: LinearLayout? = null
    private var padChip: TextView? = null
    private var connectBtn: View? = null
    private var hudVisible = false
    private val hidHud = Runnable { hideHud() }
    // The same sweep message can reach report() twice (stock delegate path + raw dispatcher
    // path); the background stamps a sequence number on every message, so act on each once.
    private val reportedSeqs = LinkedHashMap<String, Unit>()
    private val seenPorts = LinkedHashSet<String>()
    private var portWired = false
    // A recomp-style port that complains its own server never sends the COOP/COEP headers gets
    // one shot at us injecting them: the app hands the host to bg.js over the native port and
    // reloads once.
    private var carvePort: WebExtension.Port? = null
    private var coiFixTried = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        url = intent?.getStringExtra(GameActivity.EXTRA_URL).orEmpty()
        fallbackUrl = intent?.getStringExtra(GameActivity.EXTRA_FALLBACK).orEmpty()
        titleText = intent?.getStringExtra(GameActivity.EXTRA_TITLE).orEmpty().ifBlank { "Game" }
        sessionIdentity = intent?.getStringExtra(GameActivity.EXTRA_IDENTITY).orEmpty().ifBlank { titleText }
        padReady = intent?.getBooleanExtra(GameActivity.EXTRA_PAD, false) ?: false
        if (url.isBlank()) { finish(); return }
        // Some portals wrap the game in their own iframe page whose interstitial gate only ever
        // resolves through ad-SDK callbacks — on an ad-free container that gate never opens, so
        // the game script is never injected and the screen stays black. The wrapped address is
        // the same game with the same multithreading headers, so open it directly.
        val wrap = Regex("[?&]url=([^&]+)").find(url)
        if (wrap != null && url.contains("iframe.html")) {
            val inner = java.net.URLDecoder.decode(wrap.groupValues[1], "UTF-8")
            if (inner.isNotBlank()) {
                val origin = Regex("^[a-z]+://[^/]+").find(url)?.value.orEmpty()
                url = if (inner.startsWith("/")) origin + inner else inner
            }
        }

        val theme = app.theme
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        gecko = GeckoView(this).apply {
            // The texture backend always shows the picture on these TV GPUs and translated
            // emulators — the SurfaceView path can silently get no buffers at all from the
            // GPU process there. Costs a frame of latency; worth the certainty.
            setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
        }
        root.addView(gecko, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        loader = GameLoader(app)
        loader.attach(root)
        loader.show()

        status = Views.text(app, "sub", 24f, theme.ink2, "Loading $titleText…", gravity = Gravity.CENTER)
        root.addView(status, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(80f)
        ).apply { gravity = Gravity.CENTER; topMargin = theme.px(150f) })

        hint = Views.text(app, "sub", 22f, theme.ink2, "Press any button on the remote to start", gravity = Gravity.CENTER).apply {
            visibility = View.GONE
        }
        root.addView(hint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(60f)
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
                session?.loadUri(url)
            })
        errorBox.addView(retry, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = theme.px(28f) })
        root.addView(errorBox, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        errLine = errText

        // The same bar the WebView player gives: back brings it up — what this game needs,
        // a way to pair a controller, and how to leave.
        val bar = buildHud()
        bar.visibility = View.GONE
        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.px(132f)
        ).apply { gravity = Gravity.TOP })

        setContentView(root)
        gecko.requestFocus()

        val blocking = ContentBlocking.Settings.Builder()
            .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
            .build()
        // On the Metal-translated emulator (and some cheap TV GPUs) the GPU process renders into
        // a GL layer that never reaches the view — the picture stays black while the game core
        // happily draws. Gecko reads user.js from its profile at every startup; ours turns the
        // GPU process off, which makes it composite in software (SWGL): slower, but the picture
        // always shows. 2 GB TVs may want that anyway. The first game run creates the profile, so
        // the picture fixes itself from the next launch on — the pre-created directory below
        // usually catches run one too.
        writeGeckoConfig()
        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .contentBlocking(blocking)
            .build()
        // One engine per process, kept warm between games. Gecko forbids a second runtime at a
        // time, and shutdown() finishes long after onDestroy returns — closing one game and
        // opening the next quickly used to crash the newcomer with "Only one GeckoRuntime
        // instance". The engine itself is a single parent process; every game's content processes
        // still die the moment its session closes, and the next game starts warm instead of cold.
        val rt = try {
            synchronized(Engine) {
                Engine.runtime ?: GeckoRuntime.create(applicationContext, settings).also { Engine.runtime = it }
            }
        } catch (t: Throwable) {
            showError("The embedded engine could not start on this TV — ${t.message}")
            return
        }
        runtime = rt
        // The carve script ships as an unpacked extension in our own assets. installBuiltIn is the
        // one Gecko path that accepts it unsigned: file:// installs demand a Mozilla signature, and
        // an unpacked folder needs the resource://android/assets/ form ending in a slash.
        // ensureBuiltIn compares the profile's copy against ours and reinstalls only when they
        // differ — a plain installBuiltIn keeps a stale profile copy of the same version.
        rt.webExtensionController.ensureBuiltIn("resource://android/assets/scenix-carve/", CARVE_ID)
            .accept(
                { we -> extension = we; android.util.Log.i(TAG, "carve extension ready id=${we?.id}"); start(rt, we) },
                { t -> android.util.Log.e(TAG, "carve extension install failed", t); start(rt, null) },
            )
        if (!padReady) toast("This one is built for the remote — arrows and OK")
    }

    /** Drop our pref file into the Gecko profile so the GPU process stays off from its next start. */
    private fun writeGeckoConfig() {
        try {
            val mozilla = File(filesDir, "mozilla")
            mozilla.mkdirs()
            var dir: File? = null
            val ini = File(mozilla, "profiles.ini")
            if (ini.exists()) {
                val rel = ini.readLines().firstOrNull { it.startsWith("Path=") }
                    ?.substringAfter("=")?.trim()
                if (!rel.isNullOrBlank()) dir = File(mozilla, rel)
            }
            if (dir == null) {
                dir = File(mozilla, "scenix")
                if (!ini.exists()) {
                    ini.writeText("[Profile]\nName=scenix\nIsRelative=1\nPath=scenix\nDefault=1\n")
                }
            }
            dir.mkdirs()
            File(dir, "user.js").writeText(
                "user_pref(\"layers.gpu-process.enabled\", true);\n" +
                    "user_pref(\"layers.acceleration.disabled\", false);\n"
            )
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "gecko config write failed", t)
        }
    }

    private fun start(rt: GeckoRuntime, we: WebExtension?) {
        val s = GeckoSession()
        s.open(rt)

        // The extension's background relays content-script messages through a native port; the
        // events land on an EventDispatcher (see observeExtensionEvents) and are handled there.
        // This delegate covers the case where the event carries a session binding, in which case
        // the stock controller resolves straight to it. report() dedupes by message sequence.
        carveDelegate = we?.let { ext ->
            object : WebExtension.MessageDelegate {
                override fun onConnect(port: WebExtension.Port) {
                    android.util.Log.i(TAG, "carve port connected from ${port.sender?.url}")
                    carvePort = port
                    seedCoiHosts() // headers must be in place BEFORE the first document fetch
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) { report(message) }
                    })
                }

                override fun onMessage(
                    message: String, data: Any, sender: WebExtension.MessageSender,
                ): GeckoResult<Any?>? {
                    report(data)
                    return null
                }
            }
        }
        // The background opens its native port with no session behind it, so a session-bound
        // delegate never resolves for it — bind on the extension itself. report() dedupes by seq.
        fun bind() {
            val d = carveDelegate ?: return
            if (we == null) return
            s.webExtensionController.setMessageDelegate(we, d, "carve")
            try { we.setMessageDelegate(d, "carve") } catch (t: Throwable) {
                android.util.Log.w(TAG, "extension carve delegate failed", t)
            }
        }
        bind()

        // Portal pages ask permission for popups and play their ad gates through alert/confirm —
        // in a kid's container those dialogs answer themselves: dismissed, unseen.
        s.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(
                session: GeckoSession, prompt: GeckoSession.PromptDelegate.AlertPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? =
                GeckoResult.fromValue(prompt.dismiss())

            override fun onButtonPrompt(
                session: GeckoSession, prompt: GeckoSession.PromptDelegate.ButtonPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? =
                GeckoResult.fromValue(prompt.dismiss())

            override fun onTextPrompt(
                session: GeckoSession, prompt: GeckoSession.PromptDelegate.TextPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? =
                GeckoResult.fromValue(prompt.dismiss())
        }
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                bind()
                main.post {
                    refuseShown = false
                    status.text = "Loading $titleText…"
                    status.visibility = View.VISIBLE
                    errorBox.visibility = View.GONE
                    hint?.visibility = View.GONE
                    hintTold = false
                    painted = false
                    openedAt = System.currentTimeMillis()
                    loader.show()
                }
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                main.post { loader.setProgress(Math.min(progress, 95) / 100f) }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                main.post {
                    if ((painted || !success) && !refuseShown) status.visibility = View.GONE
                    // Page settled but a first play still streams its core and ROM — keep the
                    // wordmark sweeping until the game's own canvas lands on the stage.
                    if (success) loader.loop()
                }
            }
        }
        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFirstContentfulPaint(session: GeckoSession) {
                painted = true
                // The portal's chrome painting is not the game starting — the wordmark hands over
                // when the carve sweep reports a real game canvas, and stays sweeping until then.
                main.post { if (!refuseShown) status.visibility = View.GONE }
            }

            override fun onCrash(session: GeckoSession) {
                main.post { showError("The game's page stopped unexpectedly — try again.") }
            }
        }
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadError(
                session: GeckoSession, uri: String?, error: org.mozilla.geckoview.WebRequestError,
            ): GeckoResult<String>? {
                main.post {
                    if (!switchToBackup("This game could not be reached"))
                        showError("This game could not be reached — ${error.message}")
                }
                return null // GeckoView's own page stays hidden behind our box
            }
        }
        session = s
        gecko.setSession(s)
        observeExtensionEvents(s)
        s.loadUri(url)
        // A portal that never puts any game frame on screen: after three quarters of a minute say
        // so instead of sitting black forever — the same notice the WebView player gives.
        main.postDelayed({
            if (!painted) toast("This portal hasn't started its game on this TV — try another title.")
        }, 45000)
        watchControllers()
    }

    /**
     * The extension's background opens a NATIVE port, and its messages cross into Java as
     * GeckoView:WebExtension:* events — but on THREE different dispatcher objects:
     *   Connect/Message go through the connection's dispatcher (the global instance or the
     *     session's, depending on how the engine maps the hidden background window),
     *   PortMessage/Disconnect go through the NAMED dispatcher "port:<portId>" — the very
     *     object the engine creates when the background opens the port.
     * The stock WebExtension$Listener cannot be relied on here: a background sender carries
     * no session, its delegate lookup misses, and the stock Port (which would listen on the
     * named dispatcher) is never registered. So handle all three routes ourselves;
     * report() dedupes by the sequence number the background stamps on every message.
     */
    private fun observeExtensionEvents(s: GeckoSession) {
        val events = arrayOf(
            "GeckoView:WebExtension:Connect",
            "GeckoView:WebExtension:Message",
            "GeckoView:WebExtension:PortMessage",
            "GeckoView:WebExtension:Disconnect",
        )
        val listener = org.mozilla.gecko.util.BundleEventListener { event, message, callback ->
            val portId = message.get("portId")?.toString()
            if (portId != null) watchPortDispatcher("port:$portId")
            val data = message.getBundle("data")
            android.util.Log.i(
                TAG,
                "ext $event ext=${message.getString("extensionId")} " +
                    "app=${message.getString("nativeApp")} port=$portId"
            )
            val what = data?.getString("what")
            if (what != null) {
                report(mapOf("what" to what, "val" to data?.get("val"), "seq" to data?.get("seq")))
            }
            callback?.sendSuccess(null)
        }
        try {
            val m = s.javaClass.getDeclaredMethod("getEventDispatcher").apply { isAccessible = true }
            val disp = m.invoke(s) as? org.mozilla.gecko.EventDispatcher
            android.util.Log.i(TAG, "observer: session dispatcher=$disp")
            disp?.registerUiThreadListener(listener, *events)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "session observer failed", t)
        }
        try {
            val global = org.mozilla.gecko.EventDispatcher.getInstance()
            android.util.Log.i(TAG, "observer: global dispatcher=$global")
            global.registerUiThreadListener(listener, *events)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "global observer failed", t)
        }
        // The named "port:" dispatcher comes into being when the background opens the native
        // port — a moment we may never see as an event. Poll the engine's named-dispatcher
        // registry and attach to each one as it appears.
        main.postDelayed({ pollPortDispatchers() }, 400)
    }

    private fun watchPortDispatcher(name: String) {
        if (!seenPorts.add(name)) return
        portWired = true
        try {
            val disp = org.mozilla.gecko.EventDispatcher.byName(name)
            android.util.Log.i(TAG, "watching $name dispatcher=$disp")
            disp.registerUiThreadListener(
                org.mozilla.gecko.util.BundleEventListener { event, message, callback ->
                    val data = message.getBundle("data")
                    android.util.Log.i(TAG, "port $event data=$data")
                    val what = data?.getString("what")
                    if (what != null) {
                        report(mapOf("what" to what, "val" to data?.get("val"), "seq" to data?.get("seq")))
                    }
                    callback?.sendSuccess(null)
                },
                "GeckoView:WebExtension:PortMessage",
                "GeckoView:WebExtension:Disconnect",
            )
        } catch (t: Throwable) {
            seenPorts.remove(name)
            android.util.Log.w(TAG, "watch $name failed", t)
        }
    }

    private fun pollPortDispatchers() {
        if (isFinishing) return
        try {
            val f = org.mozilla.gecko.EventDispatcher::class.java
                .getDeclaredField("sDispatchers").apply { isAccessible = true }
            val map = f.get(null)
            if (map is Map<*, *>) {
                for (k in map.keys) {
                    if (k is String && k.startsWith("port:")) watchPortDispatcher(k)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "port dispatcher poll failed", t)
        }
        if (!portWired) main.postDelayed({ pollPortDispatchers() }, 500)
    }

    /** Port messages from the carve content script: 'coi' per frame, 'carve' every sweep, 'dom' for tuning. */
    private fun report(message: Any?) {
        android.util.Log.i(TAG, "carve msg: ${message?.toString()?.take(120)}")
        val m = when (message) {
            is Map<*, *> -> message
            is org.json.JSONObject ->
                mapOf("what" to message.optString("what"), "val" to message.opt("val"), "seq" to message.opt("seq"))
            else -> return
        }
        // The same sweep can arrive through both delivery paths; the sequence number makes it once.
        val seq = m["seq"]?.toString()
        if (seq != null && seq != "null") {
            if (reportedSeqs.containsKey(seq)) return
            reportedSeqs[seq] = Unit
            while (reportedSeqs.size > 128) {
                reportedSeqs.entries.iterator().run { next(); remove() }
            }
        }
        when (m["what"]?.toString()) {
            "coi" -> {
                val c = valMap(m) ?: return
                val sab = c["sab"]?.toString()
                val canvas = c["canvas"]?.toString() == "true"
                // carve.js already tells the page it is isolated (the flag alone never blocks
                // anything here — SharedArrayBuffer builds unisolated), so the only port still
                // refusing is one that says so in its own words (the "This page needs
                // cross-origin isolation" line). For that, hand the host to bg.js so it can
                // answer subresources with our own COOP/COEP headers, and reload — once.
                val refuses = c["recomp"]?.toString() == "true"
                if (refuses && !coiFixTried) {
                    coiFixTried = true
                    seedCoiHosts()
                    main.postDelayed({ session?.loadUri(url) }, 400)
                    return
                }
                // A wrapper frame is never isolated and that is normal; and the isolation flag
                // can read false on an engine that doesn't know the portal's COEP variant while
                // SharedArrayBuffer still works. Only a frame that holds the game canvas AND
                // cannot build a SharedArrayBuffer is the real multithreading failure.
                if (sab == "no" && canvas && !usedFallback) {
                    if (!switchToBackup("This port lost its isolation headers here")) {
                        showError("This port's own server isn't sending its multithreading headers right now — try again later or another title.")
                    }
                }
            }

            "dom" -> android.util.Log.i(TAG, "carve dom: ${m["val"]}")

            // The page explained in its own words why its game will not boot on this engine
            // (no WebGPU, no adapter). Show that line — it beats a black canvas and a guess.
            // Latched: a canvas pass must not re-raise the hint, and page-stop/paint events must
            // not hide the line again behind it.
            "refuse" -> {
                val c = valMap(m) ?: return
                val t = c["t"]?.toString().orEmpty().trim()
                if (t.isNotEmpty()) main.post {
                    refuseShown = true
                    hintTold = true
                    hint?.visibility = View.GONE
                    status.text = t
                    status.visibility = View.VISIBLE
                }
            }

            "carve" -> {
                val c = valMap(m)
                val res = c?.get("res")?.toString() ?: m["val"]?.toString()
                carvedPasses = if (res == "carved") { missStreak = 0; carvedPasses + 1 } else carvedPasses
                missStreak = if (res == "carved") 0 else missStreak + 1
                if (c?.get("canvas")?.toString() == "true") {
                    // The game's own stage is up — the wordmark hands over here, not at page paint.
                    main.post { loader.finish() }
                    if (sawCanvasAt == 0L && !hintTold) {
                        // The loader finished and the stage is up, but these cores only wake
                        // for a first real key press (browser audio-unlock) — say so over the
                        // black canvas instead of leaving a dead-looking screen. The hint
                        // stays until a key actually arrives (dispatchKeyEvent) or a reload.
                        hintTold = true
                        main.post { hint?.visibility = View.VISIBLE }
                    }
                    sawCanvasAt = System.currentTimeMillis()
                }
                // The stage can be "carved" (the game frame pinned) while the game itself never
                // boots behind it — the canvas is the only honest signal that it did.
                if (!stallTold && sawCanvasAt == 0L && System.currentTimeMillis() - openedAt > 45000) {
                    stallTold = true
                    toast("Still getting this game ready — the first play downloads it. Give it a minute, or try another title.")
                }
            }
        }
    }

    /** The 'val' payload: a nested GeckoBundle from the raw path, a JSONObject from the stock one. */
    private fun valMap(m: Map<*, *>): Map<String, Any?>? {
        val v = m["val"]
        return when (v) {
            is Map<*, *> -> v as Map<String, Any?>
            is org.mozilla.gecko.util.GeckoBundle -> {
                val out = HashMap<String, Any?>()
                v.keys().forEach { out[it] = v.get(it) }
                out
            }
            is org.json.JSONObject -> {
                val out = HashMap<String, Any?>()
                v.keys().forEachRemaining { out[it] = v.opt(it) }
                out
            }
            else -> null
        }
    }

    /** Tell bg.js which host answers with our own COOP/COEP headers — before its first fetch. */
    private fun seedCoiHosts() {
        try {
            val h = android.net.Uri.parse(url).host ?: ""
            if (h.isEmpty()) return
            val msg = org.json.JSONObject()
            msg.put("what", "coiHosts")
            msg.put("val", org.json.JSONArray(listOf(h)))
            carvePort?.postMessage(msg)
            android.util.Log.i(TAG, "coi hosts seeded: $h")
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "coi host handoff failed", t)
        }
    }

    private fun switchToBackup(reason: String): Boolean {
        if (usedFallback || fallbackUrl.isBlank()) return false
        usedFallback = true
        url = fallbackUrl
        toast("$reason — opening its backup link instead")
        session?.loadUri(fallbackUrl)
        return true
    }

    private fun showError(msg: String) {
        errLine.text = msg
        errorBox.visibility = View.VISIBLE
        status.visibility = View.GONE
        loader.finish()
    }

    /** Back is a game's most dangerous button: the first press brings up the bar, one more leaves. */
    override fun onBackPressed() {
        when {
            errorBox.visibility == View.VISIBLE -> finish()
            !hudVisible -> showHud()
            else -> finish()
        }
    }

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
        val chip = Views.text(app, "meta", 16f, theme.ink2, "", maxLines = 1)
        bar.addView(chip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = theme.px(26f) })
        padChip = chip
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
        hud = bar
        return bar
    }

    private fun showHud() {
        val bar = hud ?: return
        hudVisible = true
        bar.alpha = 0f
        bar.visibility = View.VISIBLE
        bar.animate().alpha(1f).setDuration(140).start()
        updatePadChip()
        connectBtn?.requestFocus()
        main.removeCallbacks(hidHud)
        main.postDelayed(hidHud, 7000)
    }

    private fun hideHud() {
        val bar = hud ?: return
        if (!hudVisible) return
        hudVisible = false
        main.removeCallbacks(hidHud)
        bar.animate().alpha(0f).setDuration(140).withEndAction { bar.visibility = View.GONE }.start()
        gecko.requestFocus()
    }

    /** The app's own controller screen — live list, button test, and the door to Android's
     *  Bluetooth pairing (pairing itself is Android's job on a TV). */
    private fun pairController() {
        try {
            startActivity(Intent(this, ControllersActivity::class.java))
        } catch (t: Throwable) {
            try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (u: Throwable) {
                toast("Open Bluetooth settings in Android to pair a controller")
            }
        }
    }

    private fun updatePadChip() {
        val chip = padChip ?: return
        val theme = app.theme
        val pads = controllers()
        chip.text = if (pads.isEmpty())
            if (padReady) "No controller yet — remote plays menus" else "Plays with the remote"
        else "Controller connected ✓"
        chip.setTextColor(if (pads.isEmpty()) theme.ink2 else theme.ink)
    }

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

    /** Remote keys keep the bar up, and any key answers the "press any button to start" hint. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            if (hudVisible) {
                main.removeCallbacks(hidHud)
                main.postDelayed(hidHud, 7000)
            }
            if (hint?.visibility == View.VISIBLE) hint?.visibility = View.GONE
        }
        return super.dispatchKeyEvent(event)
    }

    // ── Controllers ────────────────────────────────────────────────────────────────────────────
    // Gecko reads Android gamepads through its own Gamepad layer; these notices are the same ones
    // the WebView player gives so a pad arriving mid-game is never a mystery.

    private val inputMgr by lazy { getSystemService(INPUT_SERVICE) as InputManager }

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

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        GameSessions.recordStart(this, sessionIdentity)
    }

    override fun onPause() {
        GameSessions.recordEnd(this, sessionIdentity)
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        session?.close()
        session = null
        // The extension stays installed — ensureBuiltIn compares versions and re-installs only
        // when the APK ships a newer carve, so there is no stale copy to chase out. The engine
        // stays up (see onCreate): closing the session already ends the game's content processes,
        // and shutting the whole runtime down between games only raced with the next create().
        extension = null
        runtime = null
        super.onDestroy()
    }

    /** The one Gecko engine this process is allowed to have, shared by every coi game. */
    private object Engine {
        var runtime: GeckoRuntime? = null
    }

    companion object {
        private const val TAG = "ScenixCoi"
        const val CARVE_ID = "scenix-carve@scenix.app"
    }
}
