package com.scenix.tv.core

import android.content.res.AssetManager
import android.util.Log
import org.json.JSONObject
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
 * Standalone mode and the fixture preview answer the same contract from the same APK, so the
 * socket handling, the bounded body reader, the static asset server and the response writer live
 * here once. A subclass only decides what `/app/v1` means; everything else — the binding,
 * `Connection: close`, cache policy, path traversal refusal — is identical.
 */
abstract class LoopbackServer(
    protected val assets: AssetManager,
    bindAddress: InetAddress = InetAddress.getByName("127.0.0.1"),
    preferredPort: Int = 0,
) {

    /**
     * The listening socket. Standalone asks for every interface on a fixed port so a phone or laptop
     * on the same Wi-Fi can open the same page the hub serves and type add-on addresses there; the
     * fixture stays loopback-only. Any port the OS falls back to is still reported through [port].
     */
    private val server = try {
        ServerSocket(preferredPort, 8, bindAddress)
    } catch (t: Throwable) {
        // The one predictable port this backend wanted is taken by something else; the address the
        // screens show is read from `port`, so an ephemeral fallback stays honest everywhere.
        ServerSocket(0, 8, bindAddress)
    }
    private val pool = Executors.newFixedThreadPool(POOL_THREADS, ThreadFactory { r ->
        Thread(r, "scenix-server").apply { isDaemon = true }
    })
    @Volatile private var running = true

    /** The port internal `/app/v1` calls use. */
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
                // An idle read timeout, or one abandoned connection sits on one of eight pool threads
                // forever: ExoPlayer routinely leaves range sockets half-open, and a few of those
                // quietly freeze every other loopback route (play, progress, subs) on a TV-class box.
                s.soTimeout = 30_000
                s.tcpNoDelay = true
                val input = s.getInputStream()
                val request = rawLine(input) ?: return
                val parts = request.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val target = parts[1]
                var length = 0
                val headers = HashMap<String, String>()
                while (true) {
                    val h = rawLine(input) ?: break
                    if (h.isBlank()) break
                    val colon = h.indexOf(':')
                    if (colon > 0) headers[h.substring(0, colon).trim().lowercase()] = h.substring(colon + 1).trim()
                    if (h.length >= 15 && h.substring(0, 15).equals("Content-Length:", ignoreCase = true)) {
                        length = h.substring(15).trim().toIntOrNull() ?: 0
                    }
                }
                // Read the body from the same byte stream as the headers. A BufferedReader here would
                // read ahead into its own buffer, and the bytes the body is waiting for would already be
                // gone: every POST (play, progress, prefs, addon install) hung with no reply.
                val body = if (length > 0) String(readBody(input, length), Charsets.UTF_8) else ""
                route(method, target, body, Res(s.getOutputStream(), headers))
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

    // ---- bundled web surface (standalone phone pairing) ---------------------------------------
    //
    // The APK already ships the full web snapshot under assets/web/ (copyHubUi from hub/web).
    // Standalone mode serves it on the same 0.0.0.0:8787 socket the /app/v1 contract uses,
    // so a phone scanning the TV's QR opens the same Settings control centre the hub serves.
    // A fixture (FixtureServer) intentionally stays API-only and never calls serveWeb().

    /** MIME types served by the hub's webui.js, mirrored here so UI assets answer Content-Type identically. */
    private fun webMime(rel: String): String = when (rel.substringAfterLast('.', "").lowercase()) {
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
        "txt" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    /**
     * Serve one bundled web file, or return false when [path] is not a web path / the file is absent.
     *  · "/" or "/index.html" → web/index.html
     *  · "/ui/<file>"        → web/<file>   (same mapping as hub/core/webui.js's /ui/ prefix)
     * Only GET/HEAD are served; other methods are not web reads.
     */
    protected fun serveWeb(res: Res, method: String, path: String): Boolean {
        if (method != "GET" && method != "HEAD") return false
        val rel = when {
            path == "/" || path == "/index.html" -> "index.html"
            path.startsWith("/ui/") -> path.removePrefix("/ui/")
            else -> return false
        }
        // Refuse traversal and empty names: "/ui/../../assets/…" must never open a bundle file.
        val clean = rel.trim().trimStart('/')
        if (clean.isBlank() || clean.contains('\u0000') || clean.split('/').any { it == ".." || it == "." }) return false
        val assetPath = "web/$clean"
        val bytes = try {
            assets.open(assetPath).use { it.readBytes() }
        } catch (_: Throwable) {
            return false
        }
        if (bytes.size > MAX_WEB_BYTES) return false
        val type = webMime(clean)
        if (method == "HEAD") {
            res.begin(200, type, bytes.size.toLong())
            res.end()
        } else {
            res.send(200, type, bytes)
        }
        return true
    }

    /**
     * Browser UI files live on the paired Hub, not inside the TV APK. Answer unknown loopback paths
     * explicitly so a stray `http://127.0.0.1:<port>/` does not masquerade as a usable web surface.
     */
    protected fun notFound(res: Res, path: String) {
        res.json(404, JSONObject()
            .put("error", "not found")
            .put("path", path)
            .put("detail", "This TV exposes the local API only; use a paired Hub for the browser interface."))
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
    protected inner class Res(private val stream: OutputStream, val headers: Map<String, String>) {
        fun json(code: Int, o: JSONObject) =
            send(code, "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))

        fun send(code: Int, type: String, bytes: ByteArray, extra: Map<String, String> = emptyMap()) {
            begin(code, type, bytes.size.toLong(), extra)
            write(bytes)
            end()
        }

        /** Headers only, when a stream follows. Every response writes its head exactly once. */
        fun begin(code: Int, type: String, length: Long, extra: Map<String, String> = emptyMap()) {
            val status = when (code) {
                200 -> "OK"
                206 -> "Partial Content"
                400 -> "Bad Request"
                403 -> "Forbidden"
                404 -> "Not Found"
                409 -> "Conflict"
                413 -> "Payload Too Large"
                416 -> "Range Not Satisfiable"
                429 -> "Too Many Requests"
                502 -> "Bad Gateway"
                503 -> "Service Unavailable"
                else -> "Error"
            }
            // The same rule a hub's static server uses: text is re-checked every time, so a page that changed
            // in a rebuild is never hidden behind a cache, and a picture that cannot change on this TV is kept.
            val cache = if (type.startsWith("text/") || type.startsWith("application/json") ||
                type.startsWith("video/")
            ) {
                "no-cache"
            } else {
                "public, max-age=86400"
            }
            val head = buildString {
                append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n")
                append("Content-Type: ").append(type).append("\r\n")
                append("Content-Length: ").append(length).append("\r\n")
                append("Cache-Control: ").append(cache).append("\r\n")
                for ((k, v) in extra) append(k).append(": ").append(v).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            write(head.toByteArray(Charsets.UTF_8))
        }

        fun write(bytes: ByteArray): Boolean = write(bytes, 0, bytes.size)

        fun write(bytes: ByteArray, offset: Int, length: Int): Boolean = try {
            stream.write(bytes, offset, length)
            stream.flush()
            true
        } catch (t: Throwable) {
            // The player closes a range connection when it seeks. Reporting that is what stops a
            // torrent stream from reading the rest of a film into a socket nobody is listening to.
            Log.w(TAG, "response write failed: ${t.message}")
            false
        }

        fun end() {
            try { stream.flush() } catch (t: Throwable) { Log.w(TAG, "response flush failed: ${t.message}") }
        }
    }

    companion object {
        private const val TAG = "ScenixServer"
        const val POOL_THREADS = 8
        const val MAX_BODY = 256 * 1024
        private const val MAX_WEB_BYTES = 4 * 1024 * 1024
    }
}
