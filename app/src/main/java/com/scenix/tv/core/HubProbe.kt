package com.scenix.tv.core

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one question the app asks before it trusts an address: is a Scenix hub really there?
 *
 * `GET /healthz` is the hub's own statement of identity, and the body carries `"hub":"scenix-hub"`.
 * A plain `200` is not enough: routers, NAS boxes and other SSDP responders answer `LOCATION:` on
 * 1900 and will happily return *something* on port 80. Treating one of those as a hub makes setup
 * list a machine that can never show a shelf, and makes the watcher consider a dead address alive.
 */
object HubProbe {

    private const val TAG = "ScenixProbe"
    private const val BODY_MAX = 16 * 1024
    private val NAMED_HUB = Regex("\"hub\"\\s*:\\s*\"scenix-hub\"", RegexOption.IGNORE_CASE)

    /** True only for an address whose `/healthz` names this product. */
    fun healthy(base: String, timeoutMs: Int = 1500): Boolean {
        val root = base.trim().trimEnd('/')
        if (root.isBlank()) return false
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("$root/healthz").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "Scenix/0.2 (Android TV)")
            if (conn.responseCode !in 200..299) return false
            val body = conn.inputStream.bufferedReader().use { r ->
                val text = r.readText()
                if (text.length > BODY_MAX) text.take(BODY_MAX) else text
            }
            // A hub announces itself with `hub: "scenix-hub"`. The status field alone is not enough:
            // plenty of appliances answer `{"status":"ok"}`. The body is read as text rather than
            // parsed, because `/healthz` is a full status report and a bounded read must not cut a
            // valid JSON document into an invalid one.
            NAMED_HUB.containsMatchIn(body)
        } catch (t: Throwable) {
            Log.d(TAG, "no Scenix hub at $root: ${t.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
