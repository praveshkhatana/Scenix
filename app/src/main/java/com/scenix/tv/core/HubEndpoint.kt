package com.scenix.tv.core

import android.content.Context
import com.scenix.tv.R
import com.scenix.tv.ScenixApp

/**
 * The one place the native screens resolve the `/app/v1` origin.
 *
 * Hub mode uses the paired address. Standalone and the development fixture use the app's own loopback
 * server. This is an HTTP base, not a page address: the native client never needs a WebView to find it.
 */
object HubEndpoint {
    fun base(context: Context): String {
        val app = context.applicationContext as ScenixApp
        val fixture = app.prefs.fixture()
        if (!fixture.isNullOrBlank()) {
            val port = runCatching { FixtureServer.portFor(context.applicationContext, fixture) }.getOrDefault(0)
            if (port > 0) return "http://127.0.0.1:$port"
        }
        if (app.prefs.standalone()) {
            LocalServer.pairedHub = null
            val port = runCatching { LocalServer.portFor(context.applicationContext) }.getOrDefault(0)
            if (port > 0) return "http://127.0.0.1:$port"
        }
        val candidates = app.prefs.hubUrlCandidates(context.getString(R.string.hub_urls))
        val base = (candidates.firstOrNull() ?: app.prefs.hubUrl(context.getString(R.string.hub_url))).trimEnd('/')
        // A paired TV keeps its own page. It answers nothing about data itself — it points at the hub it
        // serves — so `http://<this TV>:8787` and `http://<the hub>:8788` are one household behind two
        // addresses, and whichever one a person has in their phone opens the same settings. Best-effort:
        // a TV that cannot hold a socket still has its hub.
        if (base.isNotBlank()) {
            LocalServer.pairedHub = base
            runCatching { LocalServer.portFor(context.applicationContext) }
        }
        return base
    }
}
