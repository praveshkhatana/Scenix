package com.scenix.tv.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ThreadFactory

/**
 * One addon this TV pulls content from, exactly as the owner configured it: a name to read in
 * Settings and the manifest URL they pasted. Nothing here is a constant — a build ships no addons,
 * so an empty list is a normal state and the UI says so instead of inventing shelves.
 *
 * The addon carries its own configuration in the URL path (that is how every community addon ships
 * it), so the base for every request is the manifest URL with `/manifest.json` removed, never the
 * origin.
 */
data class AddonSpec(
    val id: String,
    val name: String,
    val manifestUrl: String,
    val enabled: Boolean = true,
) {
    /** Everything but `/manifest.json`: the base every catalog/meta/stream path hangs off. */
    fun base(): String {
        val u = manifestUrl.trim().trimEnd('/')
        return if (u.endsWith("/manifest.json", true)) u.dropLast("/manifest.json".length).trimEnd('/') else u
    }
}

/** What one addon said about itself: which resources it has, which types, and its catalogs. */
class AddonManifest(val spec: AddonSpec, val body: JSONObject) {
    val resources: Map<String, JSONArray> = run {
        val out = HashMap<String, JSONArray>()
        val arr = body.optJSONArray("resources") ?: return@run out
        for (i in 0 until arr.length()) {
            when (val r = arr.opt(i)) {
                is String -> out[r] = body.optJSONArray("types") ?: JSONArray()
                is JSONObject -> {
                    val name = r.optString("name", "")
                    if (name.isNotBlank()) out[name] = r.optJSONArray("types") ?: JSONArray()
                }
            }
        }
        out
    }

    val catalogs: List<JSONObject> = run {
        val arr = body.optJSONArray("catalogs") ?: JSONArray()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    fun has(resource: String): Boolean = resources.containsKey(resource)

    /** The types this addon answers for one resource, or empty when it does not restrict them. */
    fun types(resource: String): List<String> {
        val a = resources[resource] ?: return emptyList()
        return jsonStrings(a)
    }

    /** Catalogs that answer a text search, which is what the search screen needs. */
    fun searchableCatalogs(): List<JSONObject> = catalogs.filter { c ->
        val extra = c.optJSONArray("extra")
        if (extra != null) {
            for (i in 0 until extra.length()) {
                val e = extra.optJSONObject(i)
                val n = if (e != null) e.optString("name", "") else extra.optString(i, "")
                if (n == "search") return@filter true
            }
        }
        body.optBoolean("search", false)
    }
}

/**
 * The addon protocol over plain HTTP, plus the on-device JSON cache (design/deployment-modes.md
 * Rule 2: standalone "talks to addons directly, keeps its own cache and DB on device").
 *
 * Same shape as the hub's addon host, deliberately: `manifest`, `catalog`, `meta`, `stream`,
 * `subtitles`. Everything the home screen asks for is cache-first — a fresh-enough answer comes back
 * immediately and a refresh is kicked off in the background, so a slow addon costs a shorter shelf,
 * never a hung TV. Only a detail page or a Play press waits for a live answer.
 *
 * Cache lives under `filesDir`, so it survives a restart, costs nothing to clear, and never touches
 * another app's storage. One file per URL: `{ at, url, body }`.
 */
class AddonClient(context: Context) {

    private val dir = File(context.filesDir, "scenix/addon-cache").apply { mkdirs() }
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(2, ThreadFactory { r ->
        Thread(r, "scenix-addon").apply { isDaemon = true }
    })

    /** Fetch a manifest. `wait` = block this thread for the answer; otherwise cache-only. */
    fun manifest(spec: AddonSpec, wait: Boolean, ttlMs: Long): AddonManifest? {
        val o = json(spec.manifestUrl, wait, ttlMs) ?: return null
        return AddonManifest(spec, o)
    }

    fun catalog(am: AddonManifest, type: String, remoteCatalogId: String, extra: String, wait: Boolean, ttlMs: Long): JSONArray? {
        val url = "${am.spec.base()}/catalog/$type/$remoteCatalogId/$extra.json"
        val o = json(url, wait, ttlMs) ?: return null
        return o.optJSONArray("metas")
    }

    fun meta(am: AddonManifest, type: String, remoteId: String, wait: Boolean, ttlMs: Long): JSONObject? {
        val url = "${am.spec.base()}/meta/$type/$remoteId.json"
        return json(url, wait, ttlMs)?.optJSONObject("meta")
    }

    fun streams(am: AddonManifest, type: String, remoteId: String, wait: Boolean, ttlMs: Long): JSONArray? {
        val url = "${am.spec.base()}/stream/$type/$remoteId.json"
        return json(url, wait, ttlMs)?.optJSONArray("streams")
    }

    fun subtitles(am: AddonManifest, type: String, remoteId: String, lang: String, wait: Boolean, ttlMs: Long): JSONArray? {
        val url = "${am.spec.base()}/subtitles/$type/$remoteId/$lang.json"
        return json(url, wait, ttlMs)?.optJSONArray("subtitles")
    }

    // ---- cache + fetch ---------------------------------------------------------------------

    private fun fileFor(url: String): File = File(dir, sha1(url) + ".json")

    @Synchronized
    private fun readCache(url: String): Pair<Long, JSONObject?>? {
        val f = fileFor(url)
        if (!f.isFile) return null
        return try {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            o.optLong("at", 0L) to o.optJSONObject("body")
        } catch (t: Throwable) {
            f.delete()
            null
        }
    }

    @Synchronized
    private fun writeCache(url: String, body: JSONObject) {
        try {
            val tmp = File(dir, sha1(url) + ".tmp")
            tmp.writeText(JSONObject().put("at", System.currentTimeMillis()).put("url", url).put("body", body).toString())
            val target = fileFor(url)
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        } catch (t: Throwable) {
            Log.w(TAG, "cache write failed for $url", t)
        }
    }

    /**
     * The whole caching rule in one place: a fresh entry answers at once; a stale one answers and
     * refreshes behind you; nothing cached answers only when the caller is willing to wait.
     */
    private fun json(url: String, wait: Boolean, ttlMs: Long): JSONObject? {
        val hit = readCache(url)
        if (hit != null) {
            val fresh = System.currentTimeMillis() - hit.first < ttlMs
            if (fresh || !wait) {
                if (!fresh) refresh(url)   // answer now, be right next time
                return hit.second
            }
        }
        if (!wait) { refresh(url); return null }
        val body = fetch(url)
        if (body != null) writeCache(url, body)
        return body ?: hit?.second
    }

    private fun refresh(url: String) {
        try {
            pool.execute { fetch(url)?.let { writeCache(url, it) } }
        } catch (t: Throwable) {
            Log.w(TAG, "refresh rejected for $url", t)
        }
    }

    private fun fetch(url: String): JSONObject? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 6000
            conn.readTimeout = 10000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "Scenix/0.1 (Android TV)")
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "GET $url -> ${conn.responseCode}")
                null
            } else JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    } catch (t: Throwable) {
        Log.w(TAG, "GET $url failed: ${t.message}")
        null
    }

    /** Drop every cached answer — what "clear cache" in Settings does — and say how many went. */
    fun clearCache(): Int {
        var cleared = 0
        try { dir.listFiles()?.forEach { if (it.delete()) cleared++ } } catch (t: Throwable) { Log.w(TAG, "cache clear failed", t) }
        return cleared
    }

    private fun sha1(s: String): String = try {
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        Integer.toHexString(s.hashCode())
    }

    companion object {
        private const val TAG = "ScenixAddon"
        /** Freshness rule for the home path: an answer younger than this is used as-is. */
        const val TTL_MS = 6L * 60L * 60L * 1000L
    }
}
