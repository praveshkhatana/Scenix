package com.scenix.tv.core

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What an M3U cannot tell you: whether a channel will actually open.
 *
 * The hub answers this with `core/live-health.js` and publishes the verdict on the item
 * (`live.health`, `live.checkedAt`, `live.latencyMs`, `live.error`, `live.number`). Standalone has no
 * server behind it, so the TV does the same work itself, stores the same record, and hands the rest of
 * the app the same fields. The row can then lead with a feed that just answered instead of a dead URL
 * that happens to be first in the playlist.
 *
 * Two promises make this worth persisting:
 *  - a channel keeps the number it was first given, across restarts, so "12 · &TV" means the same
 *    thing tomorrow and the zap buttons do not shuffle under the viewer;
 *  - stale evidence becomes `unknown`, never `dead`. A channel that failed a week ago is not a
 *    channel that is broken now, and hiding it on that basis would be a lie.
 */
class LiveHealth(private val file: File) {

    private val lock = Any()
    private val channels = JSONObject()
    private val pool = Executors.newFixedThreadPool(MAX_CONCURRENCY)
    private val running = AtomicBoolean(false)

    @Volatile
    private var lastProbeAt = 0L

    @Volatile
    private var quietUntil = 0L

    init {
        load()
    }

    // ---- persistence ---------------------------------------------------------------------------

    private fun load() {
        try {
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            val stored = root.optJSONObject("channels") ?: return
            synchronized(lock) {
                for (k in stored.keys()) stored.optJSONObject(k)?.let { channels.put(k, JSONObject(it.toString())) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "live health file unreadable; starting from empty", t)
        }
    }

    private fun save() {
        try {
            val root = JSONObject()
                .put("channels", JSONObject(channels.toString()))
                .put("updatedAt", System.currentTimeMillis())
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "live health write failed", t)
        }
    }

    // ---- reading -------------------------------------------------------------------------------

    fun get(identity: String): JSONObject? = synchronized(lock) {
        channels.optJSONObject(identity)?.let { JSONObject(it.toString()) }
    }

    /** `working` | `dead` | `unknown` (never checked, or the evidence is older than a day). */
    fun healthOf(identity: String): String {
        val h = get(identity) ?: return "unknown"
        val checkedAt = h.optLong("checkedAt", 0L)
        if (checkedAt <= 0L || System.currentTimeMillis() - checkedAt > STALE_MS) return "unknown"
        return if (h.optBoolean("ok", false)) "working" else "dead"
    }

    fun numberFor(identity: String): Int = get(identity)?.optInt("number", 0) ?: 0

    /** 0 = a feed that answered, 1 = never checked / stale, 2 = watched fail. */
    fun rank(identity: String): Int = when (healthOf(identity)) {
        "working" -> 0
        "dead" -> 2
        else -> 1
    }

    /** Which variant last answered, so play can start there instead of the playlist's first URL. */
    fun workingUrl(identity: String): String? {
        val h = get(identity) ?: return null
        if (!h.optBoolean("ok", false)) return null
        if (System.currentTimeMillis() - h.optLong("checkedAt", 0L) > STALE_MS) return null
        return h.optString("url", "").takeIf { it.isNotBlank() }
    }

    /**
     * Hand every identity a number it has never had, in the order given, and never change one that
     * already exists. The first pass therefore fixes the wall; later passes only append a channel that
     * was added to the playlist after the fact.
     */
    fun assignNumbers(ordered: List<String>): Int = synchronized(lock) {
        var next = 1
        for (k in channels.keys()) next = maxOf(next, channels.optJSONObject(k)?.optInt("number", 0) ?: 0)
        if (next > 1) next += 1
        var assigned = 0
        for (identity in ordered) {
            val entry = channels.optJSONObject(identity)
            if (entry != null && entry.optInt("number", 0) > 0) continue
            val target = entry ?: JSONObject().also { channels.put(identity, it) }
            target.put("number", next++)
            assigned++
        }
        if (assigned > 0) save()
        assigned
    }

    /** Do not let a probe pass compete with a film that is actually playing. */
    fun quietFor(ms: Long) {
        quietUntil = System.currentTimeMillis() + ms
    }

    fun maybeProbe(targets: List<LiveTarget>, minIntervalMs: Long = PROBE_INTERVAL_MS): Boolean {
        if (targets.isEmpty()) return false
        val now = System.currentTimeMillis()
        if (now < quietUntil) return false
        if (running.get()) return false
        if (now - lastProbeAt < minIntervalMs) return false
        if (!running.compareAndSet(false, true)) return false
        lastProbeAt = now
        pool.execute {
            try {
                probeNow(targets)
            } catch (t: Throwable) {
                Log.w(TAG, "live probe pass failed", t)
            } finally {
                running.set(false)
            }
        }
        return true
    }

    /**
     * One pass, bounded concurrency, first working variant per channel wins. A probe reads only the
     * first few kilobytes: enough to see whether a server answered with a stream or with a landing
     * page, without spending a viewer's uplink on a channel nobody is watching.
     */
    fun probeNow(targets: List<LiveTarget>) {
        val latch = CountDownLatch(targets.size)
        for (target in targets) {
            pool.execute {
                try {
                    val answer = probeTarget(target)
                    synchronized(lock) {
                        val entry = channels.optJSONObject(target.identity)
                            ?: JSONObject().also { channels.put(target.identity, it) }
                        entry.put("ok", answer.ok)
                        entry.put("checkedAt", System.currentTimeMillis())
                        entry.put("latencyMs", answer.ms)
                        entry.put("url", answer.url ?: "")
                        entry.put("error", answer.error ?: JSONObject.NULL)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "probe failed for " + target.name, t)
                } finally {
                    latch.countDown()
                }
            }
        }
        try {
            latch.await(45, TimeUnit.SECONDS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        synchronized(lock) { save() }
        val ordered = targets.sortedWith(compareBy({ rank(it.identity) }, { it.name.lowercase() }))
            .map { it.identity }
        val added = assignNumbers(ordered)
        Log.i(TAG, "live probe: ${targets.size} channels, $added new numbers")
    }

    private data class Answer(val ok: Boolean, val url: String?, val error: String?, val ms: Long)

    private fun probeTarget(target: LiveTarget): Answer {
        var lastError = "no url"
        for (variant in target.variants) {
            val started = System.currentTimeMillis()
            val result = probeUrl(variant)
            if (result == null) return Answer(true, variant.url, null, System.currentTimeMillis() - started)
            lastError = result
        }
        return Answer(false, null, lastError, 0)
    }

    /** null when the URL answered like a stream, otherwise the shortest honest reason it did not. */
    private fun probeUrl(variant: LiveVariant): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(variant.url)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", originOf(variant.url))
                setRequestProperty("Range", "bytes=0-8191")
            }
            val code = conn.responseCode
            if (code !in 200..299) return "HTTP $code"
            val type = (conn.contentType ?: "").lowercase()
            if (type.contains("text/html")) return "answered with a web page"
            val text = conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                val n = input.read(buf)
                if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8)
            }
            val looksPlaylist = variant.url.contains(".m3u8", true) || type.contains("mpegurl")
            if (looksPlaylist && !text.contains("#EXTM3U", true)) return "playlist did not answer with #EXTM3U"
            if (!looksPlaylist && text.trimStart().startsWith("<")) return "answered with a web page"
            null
        } catch (t: Throwable) {
            t.message?.takeIf { it.isNotBlank() }?.take(120) ?: "no answer"
        } finally {
            conn?.disconnect()
        }
    }

    fun status(): JSONObject = synchronized(lock) {
        var working = 0
        var dead = 0
        var unknown = 0
        for (k in channels.keys()) when (healthOf(k)) {
            "working" -> working++
            "dead" -> dead++
            else -> unknown++
        }
        JSONObject()
            .put("channels", channels.length())
            .put("working", working)
            .put("dead", dead)
            .put("unknown", unknown)
            .put("probing", running.get())
            .put("lastProbeAt", if (lastProbeAt > 0) lastProbeAt else JSONObject.NULL)
    }

    companion object {
        private const val TAG = "ScenixLiveHealth"
        private const val MAX_CONCURRENCY = 5
        private const val TIMEOUT_MS = 6000
        private const val STALE_MS = 24 * 3600_000L
        private const val PROBE_INTERVAL_MS = 10 * 60_000L
        const val UA =
            "Mozilla/5.0 (Linux; Android 12; Scenix TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        fun originOf(url: String): String = try {
            val u = URL(url)
            u.protocol + "://" + u.host + if (u.port > 0) ":" + u.port else ""
        } catch (t: Throwable) {
            ""
        }
    }
}

data class LiveVariant(val url: String, val quality: String = "", val kind: String = "http")

data class LiveTarget(val identity: String, val name: String, val variants: List<LiveVariant>)
