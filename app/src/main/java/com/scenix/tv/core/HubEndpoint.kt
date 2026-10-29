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
            val port = runCatching { LocalServer.portFor(context.applicationContext) }.getOrDefault(0)
            if (port > 0) return "http://127.0.0.1:$port"
        }
        val candidates = app.prefs.hubUrlCandidates(context.getString(R.string.hub_urls))
        val base = candidates.firstOrNull() ?: app.prefs.hubUrl(context.getString(R.string.hub_url))
        return base.trimEnd('/')
    }
}
