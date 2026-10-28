package com.scenix.tv.core

import android.content.res.AssetManager
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * The HTTP/1.1 plumbing both on-device backends share.
 *
 * Standalone mode and the bundled fixture preview answer the same page from the same APK, so the
 * socket handling, the bounded body reader, the static asset server and the response writer live
 * here once. A subclass only decides what `/app/v1` means; everything else — loopback binding,
 * `Connection: close`, cache policy, path traversal refusal — is identical.
 *
 * Loopback only, ephemeral port, no authentication. Nothing outside this TV can reach it.
 */
abstract class LoopbackServer(protected val assets: AssetManager) {

    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newFixedThreadPool(POOL_THREADS, ThreadFactory { r ->
        Thread(r, "scenix-server").apply { isDaemon = true }
    })
    @Volatile private var running = true

    /** The port the page and every `/app/v1` call hang off. */
    val port: Int get() = server.localPort

    fun start() {
        Thread(
            {
                while (running) {
                    try {
                        val socket = server.accept()
                        pool.execute { handle(socket) }
                    } catch (t: Throwable) {
                        if (running) Log.w(TAG, "accept failed", t)
                    }
                }
            },
            "scenix-accept",
        ).apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { server.close() } catch (t: Throwable) { Log.w(TAG, "close failed", t) }
    }

    /** One request, one response, then the connection closes. */
    protected abstract fun route(method: String, target: String, body: String, res: Res)

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                val input = s.getInputStream()
                val request = rawLine(input) ?: return
                val parts = request.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val target = parts[1]
                var length = 0
                while (true) {
                    val h = rawLine(input) ?: break
                    if (h.isBlank()) break
                    if (h.length >= 15 && h.substring(0, 15).equals("Content-Length:", ignoreCase = true)) {
                        length = h.substring(15).trim().toIntOrNull() ?: 0
                    }
                }
                // Read the body from the same byte stream as the headers. A BufferedReader here would
                // read ahead into its own buffer, and the bytes the body is waiting for would already be
                // gone: every POST (play, progress, prefs, addon install) hung with no reply.
                val body = if (length > 0) String(readBody(input, length), Charsets.UTF_8) else ""
                route(method, target, body, Res(s.getOutputStream()))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "request failed: ${t.message}")
        }
    }

    /** One CRLF/LF-terminated request line, read byte by byte so no buffered byte is lost. */
    private fun rawLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun readBody(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n.coerceAtMost(MAX_BODY))
        var got = 0
        while (got < buf.size) {
            val r = input.read(buf, got, buf.size - got)
            if (r < 0) break
            got += r
        }
        return buf.copyOf(got)
    }

    // ---- the bundled page ------------------------------------------------------------------------

    /**
     * The page is mounted at `/ui/` by a hub, and its own asset paths are written that way, so a
     * backend answers `/ui/app.css` and `/app.css` as one file. That is what lets the same untouched
     * bundle run against either backend.
     */
    protected fun static(res: Res, requested: String) {
        val rel = when {
            requested == "/" || requested.isBlank() || requested == "/ui" || requested == "/ui/" -> "index.html"
            requested.startsWith("/ui/") -> requested.removePrefix("/ui/")
            else -> requested.removePrefix("/")
        }
        if (rel.contains("..")) {
            res.json(400, JSONObject().put("error", "bad path"))
            return
        }
        val bytes = readAsset(rel)
        when {
            bytes == null -> res.json(404, JSONObject().put("error", "not found"))
            bytes.size > MAX_STATIC -> res.json(413, JSONObject().put("error", "file too large"))
            else -> res.send(200, contentType(rel), bytes)
        }
    }

    /**
     * One asset, read with a ceiling. A file in this APK that outgrew the page it belongs to is refused
     * rather than streamed, because a TV with 2 GB of RAM has no room for a mistake like that.
     */
    private fun readAsset(rel: String): ByteArray? = try {
        assets.open("web/$rel").use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
                if (total > MAX_STATIC) break
            }
            out.toByteArray()
        }
    } catch (t: Throwable) {
        null
    }

    private fun contentType(path: String): String = when (path.substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "txt" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    // ---- request helpers -------------------------------------------------------------------------

    protected fun parse(text: String): JSONObject? = try {
        if (text.isBlank()) null else JSONObject(text)
    } catch (t: Throwable) {
        null
    }

    protected fun queryOf(target: String): Map<String, String> {
        if (!target.contains('?')) return emptyMap()
        return target.substringAfter('?').split("&").mapNotNull { pair ->
            val k = decode(pair.substringBefore('='))
            if (k.isBlank()) null else k to decode(pair.substringAfter('=', ""))
        }.toMap()
    }

    protected fun decode(text: String): String = try {
        java.net.URLDecoder.decode(text, "UTF-8")
    } catch (t: Throwable) {
        text
    }

    /** One request's worth of answers, written to one socket: every route answers exactly once. */
    protected inner class Res(private val stream: OutputStream) {
        fun json(code: Int, o: JSONObject) =
            send(code, "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))

        fun send(code: Int, type: String, bytes: ByteArray) {
            val status = when (code) {
                200 -> "OK"
                400 -> "Bad Request"
                403 -> "Forbidden"
                404 -> "Not Found"
                409 -> "Conflict"
                413 -> "Payload Too Large"
                502 -> "Bad Gateway"
                503 -> "Service Unavailable"
                else -> "Error"
            }
            // The same rule a hub's static server uses: text is re-checked every time, so a page that changed
            // in a rebuild is never hidden behind a cache, and a picture that cannot change on this TV is kept.
            val cache = if (type.startsWith("text/") || type.startsWith("application/json")) {
                "no-cache"
            } else {
                "public, max-age=86400"
            }
            val head = buildString {
                append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n")
                append("Content-Type: ").append(type).append("\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
                append("Cache-Control: ").append(cache).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            try {
                stream.write(head.toByteArray(Charsets.UTF_8))
                stream.write(bytes)
                stream.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "response write failed: ${t.message}")
            }
        }
    }

    companion object {
        private const val TAG = "ScenixServer"
        const val POOL_THREADS = 3
        const val MAX_BODY = 256 * 1024
        // The ceiling a hub's own static server holds a page file to. The whole bundle is well under it; the
        // number is there so one wrong file cannot fill a TV's memory.
        private const val MAX_STATIC = 4 * 1024 * 1024
    }
}
