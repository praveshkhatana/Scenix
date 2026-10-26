package com.scenix.tv.core

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * The HTTP half of standalone mode. The WebView front end speaks the `/app/v1` contract over HTTP
 * exactly as it does against a hub on another machine, so in this mode the app is its own hub: the
 * same routes on loopback, answered by the same [LocalHub] the native screens call, and the page
 * itself out of `assets/web`.
 *
 * One consequence worth stating: there is no second backend implementation to keep in step. The JSON
 * a hub answers and the JSON this answers come from the same functions, so a screen cannot behave one
 * way natively and another way in the WebView.
 *
 * Loopback only, ephemeral port, no authentication — nothing outside this TV can reach it, so a guest
 * on the network cannot browse a household they were not invited into.
 */
class LocalServer private constructor(
    private val hub: LocalHub,
    private val assets: AssetManager,
) {
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

    // ---- one request, one response, then the connection closes ---------------------------------
    // HTTP/1.1 with `Connection: close`: the page makes a handful of fetches at a time, and a TV with
    // 2 GB of RAM has no business holding sockets open for it.

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                val input = s.getInputStream()
                val reader = BufferedReader(input.reader(Charsets.UTF_8))
                val request = reader.readLine() ?: return
                val parts = request.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val target = parts[1]
                var length = 0
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isBlank()) break
                    if (h.length >= 15 && h.substring(0, 15).equals("Content-Length:", ignoreCase = true)) {
                        length = h.substring(15).trim().toIntOrNull() ?: 0
                    }
                }
                val body = if (length > 0) String(readBody(input, length), Charsets.UTF_8) else ""
                route(method, target, body, Res(s.getOutputStream()))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "request failed: ${t.message}")
        }
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

    // ---- routing --------------------------------------------------------------------------------
    // The contract, route for route, answered by LocalHub. Anything that is not a contract path is
    // the bundled page.

    private fun route(method: String, target: String, body: String, res: Res) {
        val path = target.substringBefore('?', "/")
        val query = queryOf(target)
        when {
            path == "/healthz" ->
                res.json(200, JSONObject().put("ok", true).put("backend", "standalone"))

            path == "/app/v1/manifest" ->
                res.json(200, hub.manifestJson())

            path == "/app/v1/profiles" && method == "POST" -> {
                val o = parse(body)
                if (o == null) res.json(400, JSONObject().put("error", "body must be JSON"))
                else res.json(200, JSONObject().put("profile", hub.addProfile(o.optString("name", ""), o.optBoolean("isKids", false))))
            }

            path == "/app/v1/profiles" -> res.json(200, hub.profilesJson())

            path == "/app/v1/home" -> res.json(200, hub.homeJson(query["profile"]))

            path.startsWith("/app/v1/row/") -> {
                val row = hub.rowJson(decode(path.removePrefix("/app/v1/row/")), query["profile"])
                if (row == null) res.json(404, JSONObject().put("error", "no such row"))
                else res.json(200, JSONObject().put("row", row))
            }

            path == "/app/v1/search" ->
                res.json(200, hub.searchJson(query["q"] ?: "", query["profile"]))

            path.startsWith("/app/v1/meta/") -> {
                val item = hub.metaJson(decode(path.removePrefix("/app/v1/meta/")), query["type"])
                if (item == null) res.json(404, JSONObject().put("error", "unknown title"))
                else res.json(200, JSONObject().put("item", item))
            }

            path == "/app/v1/play" -> {
                val o = parse(body)
                if (o == null) res.json(400, JSONObject().put("error", "body must be JSON"))
                else {
                    val answered = hub.playJson(o)
                    res.json(if (answered.has("error")) 409 else 200, answered)
                }
            }

            path == "/app/v1/progress" -> {
                val o = parse(body)
                if (o == null) res.json(400, JSONObject().put("error", "body must be JSON"))
                else res.json(200, hub.progressJson(o))
            }

            path == "/app/v1/prefs" -> {
                val o = parse(body)
                when {
                    method == "POST" && o != null -> res.json(
                        200,
                        hub.setPrefsJson(o, query["profile"] ?: o.optString("profile", "").ifBlank { null }),
                    )
                    method == "POST" -> res.json(400, JSONObject().put("error", "body must be JSON"))
                    else -> res.json(200, hub.prefsJson(query["profile"]))
                }
            }

            path == "/app/v1/settings" -> {
                val o = parse(body)
                when {
                    method == "POST" && o != null -> res.json(200, hub.setSettings(o))
                    method == "POST" -> res.json(400, JSONObject().put("error", "body must be JSON"))
                    else -> res.json(200, hub.settings())
                }
            }

            path == "/app/v1/addons" -> res.json(200, JSONObject().put("addons", hub.addonsJson()))

            path.startsWith("/app/v1/subs/") -> res.json(
                200,
                JSONObject().put(
                    "subs",
                    hub.subsJson(
                        decode(path.removePrefix("/app/v1/subs/")),
                        (query["lang"] ?: "").split(",").filter { it.isNotBlank() },
                        query["type"],
                    ),
                ),
            )

            else -> static(res, path)
        }
    }

    // ---- the bundled page ------------------------------------------------------------------------

    private fun static(res: Res, requested: String) {
        val rel = if (requested == "/" || requested.isBlank()) "index.html" else requested.removePrefix("/")
        if (rel.contains("..")) {
            res.json(400, JSONObject().put("error", "bad path"))
            return
        }
        try {
            res.send(200, contentType(rel), assets.open("web/$rel").use { it.readBytes() })
        } catch (t: Throwable) {
            res.json(404, JSONObject().put("error", "not found"))
        }
    }

    private fun contentType(path: String): String = when (path.substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private fun parse(text: String): JSONObject? = try {
        if (text.isBlank()) null else JSONObject(text)
    } catch (t: Throwable) {
        null
    }

    private fun queryOf(target: String): Map<String, String> {
        if (!target.contains('?')) return emptyMap()
        return target.substringAfter('?').split("&").mapNotNull { pair ->
            val k = decode(pair.substringBefore('='))
            if (k.isBlank()) null else k to decode(pair.substringAfter('=', ""))
        }.toMap()
    }

    private fun decode(text: String): String = try {
        java.net.URLDecoder.decode(text, "UTF-8")
    } catch (t: Throwable) {
        text
    }

    /** One request's worth of answers, written to one socket: every route answers exactly once. */
    private inner class Res(private val stream: OutputStream) {
        fun json(code: Int, o: JSONObject) =
            send(code, "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))

        fun send(code: Int, type: String, bytes: ByteArray) {
            val status = when (code) {
                200 -> "OK"
                400 -> "Bad Request"
                404 -> "Not Found"
                409 -> "Conflict"
                else -> "Error"
            }
            val head = buildString {
                append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n")
                append("Content-Type: ").append(type).append("\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
                append("Cache-Control: no-store\r\n")
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

    private companion object {
        const val TAG = "ScenixServer"
        const val POOL_THREADS = 3
        const val MAX_BODY = 256 * 1024

        @Volatile private var instance: LocalServer? = null

        /**
         * One server per process, on one port, started when the WebView front end asks for it. The
         * port is ephemeral and never written down: the page is loaded from it and every call the page
         * makes is relative, so nothing else in this app has to know the number.
         */
        fun portFor(context: Context): Int {
            instance?.let { return it.port }
            synchronized(this) {
                instance?.let { return it.port }
                val s = LocalServer(LocalHub.get(context), context.assets)
                s.start()
                instance = s
                return s.port
            }
        }
    }
}
