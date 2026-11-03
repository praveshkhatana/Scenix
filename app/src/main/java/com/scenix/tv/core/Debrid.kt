package com.scenix.tv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Real-Debrid for standalone mode, with the same flow as the hub's adapter: add the magnet, select the
 * video file (SxxExx first, otherwise the largest video), wait for the provider to finish, then
 * unrestrict the resulting link. A hoster link takes the one-call path.
 *
 * The token lives only in this app's private settings and travels only in the Authorization header.
 * Nothing in this file logs a request, a response body or the token.
 */
object Debrid {

    private const val API = "https://api.real-debrid.com/rest/1.0"
    private val VIDEO = Regex("\\.(mkv|mp4|m4v|avi|ts|m2ts|mov|webm|mpg|mpeg|wmv|flv|ogv)$", RegexOption.IGNORE_CASE)

    data class Result(
        val url: String? = null,
        val kind: String = "http",
        val reason: String? = null,
        val fileName: String? = null,
        val fileSize: Long = 0L,
    )

    private data class Reply(val ok: Boolean, val status: Int, val json: JSONObject?, val error: String?)

    private fun call(path: String, method: String, form: Map<String, String>?,
                     token: String, timeoutMs: Int): Reply {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(API + path)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                if (form != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
            }
            if (form != null) {
                val body = form.entries.joinToString("&") {
                    URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = try { if (text.isBlank()) null else JSONObject(text) } catch (t: Throwable) { null }
            val error = when {
                status in 200..299 -> null
                status == 401 -> "bad or expired token (401)"
                status == 403 -> "permission denied (403)"
                status == 429 -> "provider rate limit reached (429)"
                json != null && json.optString("error").isNotBlank() -> json.optString("error").take(160)
                else -> "provider HTTP $status"
            }
            return Reply(status in 200..299, status, json, error)
        } catch (t: Throwable) {
            return Reply(false, 0, null, "provider unreachable (${t.javaClass.simpleName})")
        } finally {
            conn?.disconnect()
        }
    }

    /** The file a Play press means: exact SxxExx first, otherwise the largest video. */
    private fun pickFile(files: JSONArray?, season: Int, episode: Int): JSONObject? {
        if (files == null) return null
        val videos = ArrayList<JSONObject>()
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            if (VIDEO.containsMatchIn(f.optString("path"))) videos.add(f)
        }
        if (videos.isEmpty()) return null
        if (season > 0 && episode > 0) {
            val exact = videos.filter { f ->
                val p = f.optString("path").lowercase()
                val m = Regex("s(\\d{1,2})[\\s._-]*e(?:p)?(\\d{1,3})").find(p)
                m != null && m.groupValues[1].toIntOrNull() == season &&
                    m.groupValues[2].toIntOrNull() == episode
            }
            if (exact.isNotEmpty()) return exact.maxByOrNull { it.optLong("bytes") }
        }
        return videos.maxByOrNull { it.optLong("bytes") }
    }

    private fun kindOf(url: String) = if (url.contains(".m3u8", true)) "hls" else "http"

    fun unrestrict(link: String, token: String, timeoutMs: Int = 8000): Result {
        if (!link.startsWith("http://", true) && !link.startsWith("https://", true)) {
            return Result(reason = "not an HTTP link")
        }
        val r = call("/unrestrict/link", "POST", mapOf("link" to link, "remote" to "1"), token, timeoutMs)
        if (!r.ok) return Result(reason = "unrestrict: ${r.error}")
        val one = r.json
        val raw = one?.opt("download")
        val download = if (raw is String) raw else ""
        if (download.isBlank()) {
            // A few hosters answer with an array of generated links; the first is the seekable file.
            val arr = one?.optJSONArray("download")
            val first = if (arr != null && arr.length() > 0) arr.optString(0) else ""
            if (first.isBlank()) return Result(reason = "provider returned no download link")
            return Result(first, kindOf(first))
        }
        return Result(download, kindOf(download), null,
            one?.optString("filename")?.takeIf { it.isNotBlank() },
            one?.optLong("filesize", 0L) ?: 0L)
    }

    fun resolveMagnet(magnet: String, season: Int, episode: Int, token: String,
                      timeoutMs: Int = 18000): Result {
        if (token.isBlank()) return Result(reason = "no debrid token")
        val add = call("/torrents/addMagnet", "POST", mapOf("magnet" to magnet), token, 6000)
        val id = add.json?.optString("id").orEmpty()
        if (!add.ok || id.isBlank()) return Result(reason = "could not add torrent: ${add.error ?: "no id"}")
        val deadline = System.currentTimeMillis() + timeoutMs
        var status = "queued"
        while (System.currentTimeMillis() < deadline) {
            val info = call("/torrents/info/$id", "GET", null, token, 4000)
            if (!info.ok) return Result(reason = "torrent info: ${info.error}")
            val j = info.json ?: JSONObject()
            status = j.optString("status", "queued")
            when {
                status == "waiting_files_selection" -> {
                    val file = pickFile(j.optJSONArray("files"), season, episode)
                        ?: return Result(reason = "torrent holds no video file")
                    val chosen = call("/torrents/selectFiles/$id", "POST",
                        mapOf("files" to file.optString("id")), token, 4000)
                    if (!chosen.ok && chosen.status != 202 && chosen.status != 204) {
                        return Result(reason = "could not select the video file: ${chosen.error}")
                    }
                }
                status == "downloaded" -> {
                    val links = j.optJSONArray("links")
                    val link = if (links != null && links.length() > 0) links.optString(0) else ""
                    if (link.isNotBlank()) {
                        val out = unrestrict(link, token, 8000)
                        return if (out.url != null) out else out
                    }
                }
                status in listOf("magnet_error", "error", "virus", "dead", "unsupported") ->
                    return Result(reason = "provider stopped at $status")
            }
            try { Thread.sleep(450) } catch (t: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        return Result(reason = "provider still ${status.ifBlank { "queued" }} for this torrent")
    }
}
