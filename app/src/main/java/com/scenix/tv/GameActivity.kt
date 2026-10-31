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
    private var fallbackUrl = ""
    private var usedFallback = false
    private var titleText = "Game"
    private var padReady = false
    private var hudVisible = false
    private var hidHud = Runnable { hideHud() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        url = intent?.getStringExtra(EXTRA_URL).orEmpty()
        fallbackUrl = intent?.getStringExtra(EXTRA_FALLBACK).orEmpty()
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
                    // Poki and friends wrap the game in tiles, nav pills and late popups a kid
                    // shouldn't reach: carve the page down to the game's own container.
                    carve(view)
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
    // popups — content nobody asked for and not every parent wants a kid to see. The page's biggest
    // visible iframe or canvas is taken as THE game (a portal's preview <video> is not): everything
    // off the path from <body> down to it is hidden and the game itself pinned full-screen. Only
    // display styles are touched, so the page's scripts and SDK keep running. The frame often exists
    // only after the portal's own play button resolves, so the shears pass on a tick until one lands.

    private fun carve(view: WebView) {
        object : Runnable {
            var passes = 0
            var carved = 0
            override fun run() {
                if (++passes > 60) return
                view.evaluateJavascript(CARVE_JS) { r ->
                    if (r?.contains("carved") == true && ++carved >= 4) return@evaluateJavascript
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
        const val EXTRA_FALLBACK = "fallback"

        /**
         * Keep only the path to the page's biggest visible game frame; hide portal chrome around it
         * and pin its stage full-screen. A portal preview <video> is NOT a game frame. Every pass
         * first restores whatever the last one hid, so when a portal swaps its DOM mid-session the
         * page is never stranded black. Answers 'carved'/'none'. Idempotent.
         */
        const val CARVE_JS = """
(function(){
 try{
  var W=innerWidth||1,H=innerHeight||1;
  var hid=window.__scenixHid||(window.__scenixHid=[]);
  while(hid.length){var h=hid.pop();try{h[0].style.removeProperty('display');}catch(x){}}
  var pin=window.__scenixPin||(window.__scenixPin=[]);
  while(pin.length){var p0=pin.pop();try{p0.style.removeProperty('position');p0.style.removeProperty('left');
    p0.style.removeProperty('top');p0.style.removeProperty('width');p0.style.removeProperty('height');
    p0.style.removeProperty('z-index');p0.style.removeProperty('object-fit');}catch(x){}}
  document.documentElement.removeAttribute('data-scn');
  var all=document.querySelectorAll('[data-scn]');for(var r0=0;r0<all.length;r0++)all[r0].removeAttribute('data-scn');
  function visArea(e){try{var r=e.getBoundingClientRect();
    var x=Math.min(r.right,W)-Math.max(r.left,0),y=Math.min(r.bottom,H)-Math.max(r.top,0);
    return x>0&&y>0?x*y:0;}catch(x){return 0;}}
  var pick=null,best=0,nodes=document.querySelectorAll('iframe,canvas,video');
  for(var i=0;i<nodes.length;i++){var n=nodes[i];
    if(n.getAttribute&&n.getAttribute('aria-hidden')==='true')continue; // decorative art, not a game
    var a=visArea(n)*(n.tagName==='IFRAME'?2:1);if(a>best){best=a;pick=n;}}
  if(!pick||visArea(pick)<W*H*0.04)return 'none';
  var node=pick,na=visArea(pick);
  while(node.parentElement&&node.parentElement!==document.body){
    var pa=visArea(node.parentElement);
    if(pa>na*1.7)break;
    node=node.parentElement;na=pa;
  }
  var chain=[];for(var p=node;p;p=p.parentElement)chain.unshift(p);
  document.documentElement.setAttribute('data-scn','keep');
  for(var j=0;j<chain.length;j++)chain[j].setAttribute('data-scn','keep');
  function hideOffPath(parent,keep){var s=parent.children;
    for(var k=0;k<s.length;k++)if(s[k]!==keep){s[k].style.setProperty('display','none','important');hid.push([s[k]]);}}
  hideOffPath(document.body,chain[0]);
  for(var l=0;l<chain.length-1;l++)hideOffPath(chain[l],chain[l+1]);
  document.documentElement.style.setProperty('background','#000','important');
  document.body.style.setProperty('margin','0','important');
  document.body.style.setProperty('background','#000','important');
  node.style.setProperty('position','fixed','important');
  node.style.setProperty('left','0','important');
  node.style.setProperty('top','0','important');
  node.style.setProperty('width','100vw','important');
  node.style.setProperty('height','100vh','important');
  node.style.setProperty('z-index','2147483000','important');
  pin.push(node);
  if(!window.__scenixGuard){
    window.__scenixGuard=1;
    new MutationObserver(function(ms){
      for(var n=0;n<ms.length;n++){var add=ms[n].addedNodes||[];
        for(var q=0;q<add.length;q++){var e=add[q];
          if(e.nodeType!==1)continue;
          if(e.tagName==='SCRIPT'||e.tagName==='STYLE')continue;
          if(e.closest&&e.closest('[data-scn]'))continue;
          var pos=false;try{var cs=getComputedStyle(e);pos=cs.position==='fixed'||cs.position==='absolute';}catch(x){}
          if(pos)e.style.setProperty('display','none','important');
        }}
    }).observe(document.documentElement,{childList:true,subtree:true});
  }
  return 'carved';
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
        )
    }
}
